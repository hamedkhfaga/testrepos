package org.apache.ofbiz.loan.services;

import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.apache.ofbiz.base.util.Debug;
import org.apache.ofbiz.base.util.UtilDateTime;
import org.apache.ofbiz.base.util.UtilValidate;
import org.apache.ofbiz.entity.Delegator;
import org.apache.ofbiz.entity.GenericEntityException;
import org.apache.ofbiz.entity.GenericValue;
import org.apache.ofbiz.entity.condition.EntityCondition;
import org.apache.ofbiz.entity.condition.EntityOperator;
import org.apache.ofbiz.entity.util.EntityQuery;
import org.apache.ofbiz.service.DispatchContext;
import org.apache.ofbiz.service.LocalDispatcher;
import org.apache.ofbiz.service.ServiceUtil;

/**
 * Phase 6C - Repair Approval Workflow.
 *
 * Additive governance/approval wrapper around existing repair services.
 * This class does not directly mutate loan, payment, invoice, accounting, or reconciliation records.
 */
public final class LoanRepairApprovalServices {
    public static final String MODULE = LoanRepairApprovalServices.class.getName();

    private static final String STATUS_PROPOSED = "LRP_PROPOSED";
    private static final String STATUS_PENDING_APPROVAL = "LRP_PENDING_APPROVAL";
    private static final String STATUS_APPROVED = "LRP_APPROVED";
    private static final String STATUS_REJECTED = "LRP_REJECTED";
    private static final String STATUS_EXECUTING = "LRP_EXECUTING";
    private static final String STATUS_EXECUTED = "LRP_EXECUTED";
    private static final String STATUS_EXEC_FAILED = "LRP_EXEC_FAILED";
    private static final String STATUS_BLOCKED = "LRP_BLOCKED";
    private static final String STATUS_OBSOLETE = "LRP_OBSOLETE";

    private static final String DRY_NOT_RUN = "LRP_DRY_NOT_RUN";
    private static final String DRY_PASSED = "LRP_DRY_PASSED";
    private static final String DRY_FAILED = "LRP_DRY_FAILED";

    private static final String DEFAULT_REPAIR_SERVICE = "repairLoanFinancialIntegrityV1";

    private LoanRepairApprovalServices() {}

    public static Map<String, Object> createLoanRepairProposalV1(DispatchContext dctx, Map<String, ?> context) {
        Delegator delegator = dctx.getDelegator();
        LocalDispatcher dispatcher = dctx.getDispatcher();
        GenericValue userLogin = (GenericValue) context.get("userLogin");

        try {
            String organizationPartyId = trimToNull((String) context.get("organizationPartyId"));
            String reconciliationIssueId = trimToNull((String) context.get("reconciliationIssueId"));
            String issueTypeEnumId = trimToNull((String) context.get("issueTypeEnumId"));
            String severityEnumId = trimToNull((String) context.get("severityEnumId"));
            String loanId = trimToNull((String) context.get("loanId"));

            if (UtilValidate.isEmpty(organizationPartyId)) {
                return ServiceUtil.returnError("organizationPartyId is required");
            }
            if (UtilValidate.isEmpty(reconciliationIssueId) && UtilValidate.isEmpty(issueTypeEnumId)) {
                return ServiceUtil.returnError("Either reconciliationIssueId or issueTypeEnumId is required");
            }

            GenericValue issue = null;
            if (UtilValidate.isNotEmpty(reconciliationIssueId)) {
                issue = EntityQuery.use(delegator)
                        .from("LoanReconciliationIssue")
                        .where("reconciliationIssueId", reconciliationIssueId)
                        .queryOne();
                if (issue == null) {
                    return ServiceUtil.returnError("LoanReconciliationIssue not found: " + reconciliationIssueId);
                }
                issueTypeEnumId = firstNonEmpty(issueTypeEnumId, issue.getString("issueTypeEnumId"));
                severityEnumId = firstNonEmpty(severityEnumId, issue.getString("severityEnumId"));
                loanId = firstNonEmpty(loanId, issue.getString("loanId"));
                organizationPartyId = firstNonEmpty(organizationPartyId, issue.getString("organizationPartyId"));
            }

            Map<String, Object> eligibilityCtx = new HashMap<String, Object>();
            eligibilityCtx.put("reconciliationIssueId", reconciliationIssueId);
            eligibilityCtx.put("organizationPartyId", organizationPartyId);
            eligibilityCtx.put("issueTypeEnumId", issueTypeEnumId);
            eligibilityCtx.put("severityEnumId", severityEnumId);
            eligibilityCtx.put("userLogin", userLogin);

            Map<String, Object> eligibility = dispatcher.runSync("getRepairEligibilityForIssueV1", eligibilityCtx);
            if (ServiceUtil.isError(eligibility)) {
                return ServiceUtil.returnError(ServiceUtil.getErrorMessage(eligibility));
            }

            String policyFound = normalizeFlag((String) eligibility.get("policyFound"));
            String autoRepairAllowed = normalizeFlag((String) eligibility.get("autoRepairAllowed"));
            String manualApprovalRequired = normalizeFlag((String) eligibility.get("manualApprovalRequired"));
            String blockBatchRepair = normalizeFlag((String) eligibility.get("blockBatchRepair"));
            String requiresDryRun = normalizeFlagDefaultY((String) eligibility.get("requiresDryRun"));
            String riskLevelEnumId = trimToNull((String) eligibility.get("riskLevelEnumId"));
            String repairEligibilityStatus = trimToNull((String) eligibility.get("repairEligibilityStatus"));
            String recommendedAction = trimToNull((String) eligibility.get("recommendedAction"));
            String repairServiceName = firstNonEmpty(trimToNull((String) context.get("repairServiceName")), trimToNull((String) eligibility.get("repairServiceName")));
            if (UtilValidate.isEmpty(repairServiceName)) {
                repairServiceName = DEFAULT_REPAIR_SERVICE;
            }

            String proposalStatusId;
            if ("Y".equals(blockBatchRepair) || !"Y".equals(policyFound)) {
                proposalStatusId = STATUS_BLOCKED;
            } else if ("Y".equals(manualApprovalRequired)) {
                proposalStatusId = STATUS_PENDING_APPROVAL;
            } else if ("Y".equals(autoRepairAllowed)) {
                proposalStatusId = STATUS_APPROVED;
            } else {
                proposalStatusId = STATUS_PROPOSED;
            }

            GenericValue existingActiveProposal = findExistingActiveProposal(delegator, organizationPartyId, loanId,
                    trimToNull(issue != null ? issue.getString("loanRepaymentId") : (String) context.get("loanRepaymentId")),
                    issueTypeEnumId);
            if (existingActiveProposal != null) {
                Map<String, Object> data = proposalData(existingActiveProposal);
                data.put("duplicateSkipped", "Y");
                data.put("existingRepairProposalId", existingActiveProposal.getString("repairProposalId"));
                return buildStandardSuccess("Active repair proposal already exists for the same issue", data);
            }

            Timestamp nowTs = UtilDateTime.nowTimestamp();
            String repairProposalId = delegator.getNextSeqId("LoanRepairProposal");
            GenericValue proposal = delegator.makeValue("LoanRepairProposal");
            proposal.set("repairProposalId", repairProposalId);
            proposal.set("organizationPartyId", organizationPartyId);
            proposal.set("reconciliationIssueId", reconciliationIssueId);
            proposal.set("loanId", loanId);
            proposal.set("loanScheduleId", issue != null ? issue.getString("loanScheduleId") : trimToNull((String) context.get("loanScheduleId")));
            proposal.set("loanRepaymentId", issue != null ? issue.getString("loanRepaymentId") : trimToNull((String) context.get("loanRepaymentId")));
            proposal.set("paymentId", issue != null ? issue.getString("paymentId") : trimToNull((String) context.get("paymentId")));
            proposal.set("acctgTransId", issue != null ? issue.getString("acctgTransId") : trimToNull((String) context.get("acctgTransId")));
            proposal.set("issueTypeEnumId", issueTypeEnumId);
            proposal.set("severityEnumId", severityEnumId);
            proposal.set("riskLevelEnumId", riskLevelEnumId);
            proposal.set("policyId", trimToNull((String) eligibility.get("policyId")));
            proposal.set("repairEligibilityStatus", repairEligibilityStatus);
            proposal.set("autoRepairAllowed", autoRepairAllowed);
            proposal.set("manualApprovalRequired", manualApprovalRequired);
            proposal.set("blockBatchRepair", blockBatchRepair);
            proposal.set("dryRunRequired", requiresDryRun);
            proposal.set("dryRunStatusId", DRY_NOT_RUN);
            proposal.set("repairServiceName", repairServiceName);
            proposal.set("recommendedAction", recommendedAction);
            proposal.set("repairPayload", safeToString(context));
            proposal.set("paymentMethodId", trimToNull((String) context.get("paymentMethodId")));
            proposal.set("paymentMethodTypeId", trimToNull((String) context.get("paymentMethodTypeId")));
            proposal.set("paymentTypeId", trimToNull((String) context.get("paymentTypeId")));
            proposal.set("autoCreateAccounting", normalizeFlag((String) context.get("autoCreateAccounting")));
            proposal.set("autoCreateFinAccountTrans", normalizeFlag((String) context.get("autoCreateFinAccountTrans")));
            proposal.set("proposalStatusId", proposalStatusId);
            proposal.set("createdByUserLoginId", userLoginId(userLogin));
            proposal.set("createdDate", nowTs);
            proposal.set("lastStatusDate", nowTs);
            delegator.create(proposal);

            createAudit(delegator, repairProposalId, "LRPA_PROPOSED", null, proposalStatusId,
                    "Repair proposal created", eligibility, userLogin);
            createAudit(delegator, repairProposalId, "LRPA_POLICY_CHECKED", proposalStatusId, proposalStatusId,
                    "Governance policy evaluated before proposal creation", eligibility, userLogin);
            if (STATUS_BLOCKED.equals(proposalStatusId)) {
                createAudit(delegator, repairProposalId, "LRPA_BLOCKED_BY_POLICY", null, proposalStatusId,
                        "Repair proposal blocked by governance policy", eligibility, userLogin);
            }

            Map<String, Object> data = proposalData(proposal);
            data.put("eligibility", eligibility);
            return buildStandardSuccess("Loan repair proposal created successfully", data);
        } catch (Exception e) {
            Debug.logError(e, "Error creating loan repair proposal", MODULE);
            return ServiceUtil.returnError(e.getMessage());
        }
    }

    public static Map<String, Object> approveLoanRepairProposalV1(DispatchContext dctx, Map<String, ?> context) {
        return decideProposal(dctx, context, true);
    }

    public static Map<String, Object> rejectLoanRepairProposalV1(DispatchContext dctx, Map<String, ?> context) {
        return decideProposal(dctx, context, false);
    }

    public static Map<String, Object> dryRunLoanRepairProposalV1(DispatchContext dctx, Map<String, ?> context) {
        Delegator delegator = dctx.getDelegator();
        LocalDispatcher dispatcher = dctx.getDispatcher();
        GenericValue userLogin = (GenericValue) context.get("userLogin");

        try {
            String repairProposalId = trimToNull((String) context.get("repairProposalId"));
            GenericValue proposal = requireProposal(delegator, repairProposalId);
            String statusId = proposal.getString("proposalStatusId");
            if (phase10bIsTerminalProposalStatus(statusId)) {
                return phase10bReadOnlyError("Dry-run", proposal);
            }
            if (!(STATUS_APPROVED.equals(statusId) || STATUS_PROPOSED.equals(statusId))) {
                return ServiceUtil.returnError("Dry-run is allowed only for approved/proposed proposals. Current status: " + statusId);
            }
            if (STATUS_EXECUTED.equals(statusId) || STATUS_EXECUTING.equals(statusId)) {
                return ServiceUtil.returnError("Proposal is already executing/executed: " + repairProposalId);
            }

            Map<String, Object> governance = recheckGovernance(dispatcher, proposal, userLogin);
            if (ServiceUtil.isError(governance)) {
                markDryRunFailed(delegator, proposal, governance, userLogin);
                return governance;
            }

            Map<String, Object> repairCtx = buildRepairContext(proposal, context, userLogin, "Y");
            Map<String, Object> repairResult = dispatcher.runSync(proposal.getString("repairServiceName"), repairCtx);
            if (ServiceUtil.isError(repairResult)) {
                markDryRunFailed(delegator, proposal, repairResult, userLogin);
                return ServiceUtil.returnError(ServiceUtil.getErrorMessage(repairResult));
            }

            proposal.set("dryRunStatusId", DRY_PASSED);
            proposal.set("dryRunDate", UtilDateTime.nowTimestamp());
            proposal.set("dryRunResult", safeToString(repairResult));
            proposal.store();
            createAudit(delegator, repairProposalId, "LRPA_DRY_RUN_PASSED", statusId, statusId,
                    "Dry-run passed", repairResult, userLogin);

            Map<String, Object> data = proposalData(proposal);
            data.put("dryRunResult", repairResult);
            return buildStandardSuccess("Loan repair proposal dry-run passed", data);
        } catch (Exception e) {
            Debug.logError(e, "Error running dry-run for loan repair proposal", MODULE);
            return ServiceUtil.returnError(e.getMessage());
        }
    }

    public static Map<String, Object> executeApprovedLoanRepairProposalV1(DispatchContext dctx, Map<String, ?> context) {
        Delegator delegator = dctx.getDelegator();
        LocalDispatcher dispatcher = dctx.getDispatcher();
        GenericValue userLogin = (GenericValue) context.get("userLogin");

        try {
            String repairProposalId = trimToNull((String) context.get("repairProposalId"));
            GenericValue proposal = requireProposal(delegator, repairProposalId);
            String currentStatusId = proposal.getString("proposalStatusId");

            if (phase10bIsTerminalProposalStatus(currentStatusId)) {
                return phase10bReadOnlyError("Execution", proposal);
            }
            if (STATUS_EXECED_OR_EXECUTING(currentStatusId)) {
                return ServiceUtil.returnError("Proposal is already executing or executed: " + repairProposalId);
            }
            if (!STATUS_APPROVED.equals(currentStatusId)) {
                return ServiceUtil.returnError("Only approved proposals can be executed. Current status: " + currentStatusId);
            }

            Map<String, Object> governance = recheckGovernance(dispatcher, proposal, userLogin);
            if (ServiceUtil.isError(governance)) {
                blockProposal(delegator, proposal, currentStatusId, governance, userLogin);
                return governance;
            }

            if ("Y".equals(normalizeFlagDefaultY(proposal.getString("dryRunRequired")))
                    && !DRY_PASSED.equals(proposal.getString("dryRunStatusId"))) {
                return ServiceUtil.returnError("Dry-run must pass before execution. repairProposalId=" + repairProposalId);
            }

            proposal.set("proposalStatusId", STATUS_EXECUTING);
            proposal.set("executionStatusId", STATUS_EXECUTING);
            proposal.set("executedByUserLoginId", userLoginId(userLogin));
            proposal.set("executionDate", UtilDateTime.nowTimestamp());
            proposal.set("lastStatusDate", UtilDateTime.nowTimestamp());
            proposal.store();
            createAudit(delegator, repairProposalId, "LRPA_EXEC_STARTED", currentStatusId, STATUS_EXECUTING,
                    "Approved repair execution started", null, userLogin);

            Map<String, Object> repairCtx = buildRepairContext(proposal, context, userLogin, "N");
            Map<String, Object> executionResult = dispatcher.runSync(proposal.getString("repairServiceName"), repairCtx);

            proposal.refresh();
            if (ServiceUtil.isError(executionResult)) {
                proposal.set("proposalStatusId", STATUS_EXEC_FAILED);
                proposal.set("executionStatusId", STATUS_EXEC_FAILED);
                proposal.set("executionResult", safeToString(executionResult));
                proposal.set("lastStatusDate", UtilDateTime.nowTimestamp());
                proposal.store();
                createAudit(delegator, repairProposalId, "LRPA_EXEC_FAILED", STATUS_EXECUTING, STATUS_EXEC_FAILED,
                        "Approved repair execution failed", executionResult, userLogin);
                return ServiceUtil.returnError(ServiceUtil.getErrorMessage(executionResult));
            }

            proposal.set("proposalStatusId", STATUS_EXECUTED);
            proposal.set("executionStatusId", STATUS_EXECUTED);
            proposal.set("executionResult", safeToString(executionResult));
            proposal.set("lastStatusDate", UtilDateTime.nowTimestamp());
            proposal.store();
            createAudit(delegator, repairProposalId, "LRPA_EXEC_SUCCEEDED", STATUS_EXECUTING, STATUS_EXECUTED,
                    "Approved repair execution completed", executionResult, userLogin);

            Map<String, Object> data = proposalData(proposal);
            data.put("executionResult", executionResult);
            return buildStandardSuccess("Approved loan repair proposal executed successfully", data);
        } catch (Exception e) {
            Debug.logError(e, "Error executing approved loan repair proposal", MODULE);
            return ServiceUtil.returnError(e.getMessage());
        }
    }

    public static Map<String, Object> getLoanRepairProposalListV1(DispatchContext dctx, Map<String, ?> context) {
        Delegator delegator = dctx.getDelegator();
        try {
            List<EntityCondition> conds = new ArrayList<EntityCondition>();
            addEq(conds, "organizationPartyId", trimToNull((String) context.get("organizationPartyId")));
            addEq(conds, "loanId", trimToNull((String) context.get("loanId")));
            addEq(conds, "reconciliationIssueId", trimToNull((String) context.get("reconciliationIssueId")));
            addEq(conds, "proposalStatusId", trimToNull((String) context.get("proposalStatusId")));
            addEq(conds, "issueTypeEnumId", trimToNull((String) context.get("issueTypeEnumId")));
            EntityCondition where = conds.isEmpty() ? null : EntityCondition.makeCondition(conds, EntityOperator.AND);

            EntityQuery query = EntityQuery.use(delegator)
                    .from("LoanRepairProposal")
                    .orderBy("-createdDate");
            if (where != null) {
                query.where(where);
            }
            List<GenericValue> rows = query.queryList();

            List<Map<String, Object>> proposalList = new ArrayList<Map<String, Object>>();
            for (GenericValue row : rows) {
                proposalList.add(proposalData(row));
            }
            Map<String, Object> data = new HashMap<String, Object>();
            data.put("proposalCount", Integer.valueOf(proposalList.size()));
            data.put("proposalList", proposalList);
            return buildStandardSuccess("Loan repair proposal list retrieved successfully", data);
        } catch (Exception e) {
            Debug.logError(e, "Error retrieving loan repair proposal list", MODULE);
            return ServiceUtil.returnError(e.getMessage());
        }
    }

    public static Map<String, Object> getLoanRepairProposalAuditListV1(DispatchContext dctx, Map<String, ?> context) {
        Delegator delegator = dctx.getDelegator();
        try {
            String repairProposalId = trimToNull((String) context.get("repairProposalId"));
            if (UtilValidate.isEmpty(repairProposalId)) {
                return ServiceUtil.returnError("repairProposalId is required");
            }
            List<GenericValue> rows = EntityQuery.use(delegator)
                    .from("LoanRepairProposalAudit")
                    .where("repairProposalId", repairProposalId)
                    .orderBy("createdDate")
                    .queryList();

            List<Map<String, Object>> auditList = new ArrayList<Map<String, Object>>();
            for (GenericValue row : rows) {
                Map<String, Object> item = new LinkedHashMap<String, Object>();
                item.put("repairProposalAuditId", row.getString("repairProposalAuditId"));
                item.put("repairProposalId", row.getString("repairProposalId"));
                item.put("auditEventTypeId", row.getString("auditEventTypeId"));
                item.put("fromStatusId", row.getString("fromStatusId"));
                item.put("toStatusId", row.getString("toStatusId"));
                item.put("message", row.getString("message"));
                item.put("payload", row.getString("payload"));
                item.put("userLoginId", row.getString("userLoginId"));
                item.put("createdDate", row.getTimestamp("createdDate"));
                auditList.add(item);
            }
            Map<String, Object> data = new HashMap<String, Object>();
            data.put("repairProposalId", repairProposalId);
            data.put("auditCount", Integer.valueOf(auditList.size()));
            data.put("auditList", auditList);
            return buildStandardSuccess("Loan repair proposal audit list retrieved successfully", data);
        } catch (Exception e) {
            Debug.logError(e, "Error retrieving loan repair proposal audit list", MODULE);
            return ServiceUtil.returnError(e.getMessage());
        }
    }


    /**
     * Phase 6K - closes stale duplicate repair proposals while keeping the newest active proposal
     * for each business issue key. This service does not delete proposal rows; it marks older
     * duplicates as rejected to preserve audit history.
     */
    public static Map<String, Object> cleanupDuplicateLoanRepairProposalsV1(DispatchContext dctx, Map<String, ?> context) {
        Delegator delegator = dctx.getDelegator();
        GenericValue userLogin = (GenericValue) context.get("userLogin");

        String organizationPartyId = trimToNull((String) context.get("organizationPartyId"));
        String dryRun = normalizeFlagDefaultY((String) context.get("dryRun"));
        int maxRows = parsePositiveInt(context.get("maxRows"), 500, 5000);

        try {
            if (UtilValidate.isEmpty(organizationPartyId)) {
                return ServiceUtil.returnError("organizationPartyId is required");
            }

            List<EntityCondition> conds = new ArrayList<EntityCondition>();
            conds.add(EntityCondition.makeCondition("organizationPartyId", EntityOperator.EQUALS, organizationPartyId));
            conds.add(EntityCondition.makeCondition("proposalStatusId", EntityOperator.IN, cleanupCandidateStatuses()));

            List<GenericValue> rows = EntityQuery.use(delegator)
                    .from("LoanRepairProposal")
                    .where(EntityCondition.makeCondition(conds, EntityOperator.AND))
                    .orderBy("organizationPartyId", "loanId", "loanRepaymentId", "issueTypeEnumId", "-createdDate", "-repairProposalId")
                    .maxRows(maxRows)
                    .queryList();

            Map<String, List<GenericValue>> grouped = new LinkedHashMap<String, List<GenericValue>>();
            for (GenericValue proposal : rows) {
                String key = duplicateKey(proposal);
                List<GenericValue> list = grouped.get(key);
                if (list == null) {
                    list = new ArrayList<GenericValue>();
                    grouped.put(key, list);
                }
                list.add(proposal);
            }

            int checkedProposalCount = rows.size();
            int duplicateGroupCount = 0;
            int retainedCount = 0;
            int wouldCloseCount = 0;
            int closedDuplicateCount = 0;
            List<Map<String, Object>> actions = new ArrayList<Map<String, Object>>();
            Timestamp nowTs = UtilDateTime.nowTimestamp();

            for (Map.Entry<String, List<GenericValue>> entry : grouped.entrySet()) {
                List<GenericValue> list = entry.getValue();
                if (list.size() <= 1) {
                    continue;
                }
                duplicateGroupCount++;
                GenericValue retained = list.get(0);
                retainedCount++;

                Map<String, Object> retainAction = new LinkedHashMap<String, Object>();
                retainAction.put("businessKey", entry.getKey());
                retainAction.put("repairProposalId", retained.getString("repairProposalId"));
                retainAction.put("loanId", retained.getString("loanId"));
                retainAction.put("loanRepaymentId", retained.getString("loanRepaymentId"));
                retainAction.put("issueTypeEnumId", retained.getString("issueTypeEnumId"));
                retainAction.put("proposalStatusId", retained.getString("proposalStatusId"));
                retainAction.put("status", "RETAINED");
                retainAction.put("reason", "Newest proposal retained for duplicate business issue key");
                actions.add(retainAction);

                for (int i = 1; i < list.size(); i++) {
                    GenericValue stale = list.get(i);
                    String fromStatusId = stale.getString("proposalStatusId");
                    Map<String, Object> action = new LinkedHashMap<String, Object>();
                    action.put("businessKey", entry.getKey());
                    action.put("repairProposalId", stale.getString("repairProposalId"));
                    action.put("loanId", stale.getString("loanId"));
                    action.put("loanRepaymentId", stale.getString("loanRepaymentId"));
                    action.put("issueTypeEnumId", stale.getString("issueTypeEnumId"));
                    action.put("fromStatusId", fromStatusId);
                    action.put("toStatusId", STATUS_REJECTED);
                    if ("Y".equals(dryRun)) {
                        wouldCloseCount++;
                        action.put("status", "DRY_RUN");
                        action.put("reason", "Would close stale duplicate proposal");
                    } else {
                        stale.set("proposalStatusId", STATUS_REJECTED);
                        stale.set("rejectedByUserLoginId", userLoginId(userLogin));
                        stale.set("rejectedDate", nowTs);
                        stale.set("rejectionReason", "Phase 6K cleanup: stale duplicate proposal superseded by " + retained.getString("repairProposalId"));
                        stale.set("lastStatusDate", nowTs);
                        stale.store();
                        createAudit(delegator, stale.getString("repairProposalId"), "LRPA_DUPLICATE_CLOSED",
                                fromStatusId, STATUS_REJECTED,
                                "Stale duplicate repair proposal closed by Phase 6K cleanup", action, userLogin);
                        closedDuplicateCount++;
                        action.put("status", "DONE");
                        action.put("reason", "Stale duplicate proposal closed");
                    }
                    action.put("retainedRepairProposalId", retained.getString("repairProposalId"));
                    actions.add(action);
                }
            }

            Map<String, Object> data = new LinkedHashMap<String, Object>();
            data.put("organizationPartyId", organizationPartyId);
            data.put("dryRun", dryRun);
            data.put("maxRows", Integer.valueOf(maxRows));
            data.put("checkedProposalCount", Integer.valueOf(checkedProposalCount));
            data.put("duplicateGroupCount", Integer.valueOf(duplicateGroupCount));
            data.put("retainedCount", Integer.valueOf(retainedCount));
            data.put("wouldCloseCount", Integer.valueOf(wouldCloseCount));
            data.put("closedDuplicateCount", Integer.valueOf(closedDuplicateCount));
            data.put("actions", actions);
            data.put("finalStatus", "Y".equals(dryRun) ? "DRY_RUN" : "DONE");
            return buildStandardSuccess("Duplicate loan repair proposal cleanup completed successfully", data);
        } catch (Exception e) {
            Debug.logError(e, "Error cleaning duplicate loan repair proposals", MODULE);
            return ServiceUtil.returnError(e.getMessage());
        }
    }

    private static Map<String, Object> decideProposal(DispatchContext dctx, Map<String, ?> context, boolean approve) {
        Delegator delegator = dctx.getDelegator();
        GenericValue userLogin = (GenericValue) context.get("userLogin");
        try {
            String repairProposalId = trimToNull((String) context.get("repairProposalId"));
            GenericValue proposal = requireProposal(delegator, repairProposalId);
            String currentStatusId = proposal.getString("proposalStatusId");
            if (STATUS_EXECUTED.equals(currentStatusId) || STATUS_EXECUTING.equals(currentStatusId)
                    || STATUS_EXEC_FAILED.equals(currentStatusId)) {
                return ServiceUtil.returnError("Cannot approve/reject proposal after execution lifecycle has started. Current status: " + currentStatusId);
            }
            if (STATUS_BLOCKED.equals(currentStatusId)) {
                return ServiceUtil.returnError("Blocked proposals cannot be approved/rejected manually. Current status: " + currentStatusId);
            }
            if (STATUS_REJECTED.equals(currentStatusId)) {
                return ServiceUtil.returnError("Proposal is already rejected: " + repairProposalId);
            }
            if (STATUS_OBSOLETE.equals(currentStatusId)) {
                return ServiceUtil.returnError("Obsolete proposals are read-only and cannot be approved/rejected. Current status: " + currentStatusId);
            }

            Timestamp nowTs = UtilDateTime.nowTimestamp();
            String toStatus = approve ? STATUS_APPROVED : STATUS_REJECTED;
            proposal.set("proposalStatusId", toStatus);
            proposal.set("lastStatusDate", nowTs);
            if (approve) {
                proposal.set("approvedByUserLoginId", userLoginId(userLogin));
                proposal.set("approvedDate", nowTs);
            } else {
                proposal.set("rejectedByUserLoginId", userLoginId(userLogin));
                proposal.set("rejectedDate", nowTs);
                proposal.set("rejectionReason", trimToNull((String) context.get("rejectionReason")));
            }
            proposal.store();
            createAudit(delegator, repairProposalId, approve ? "LRPA_APPROVED" : "LRPA_REJECTED",
                    currentStatusId, toStatus,
                    approve ? "Repair proposal approved" : "Repair proposal rejected",
                    context, userLogin);
            return buildStandardSuccess(approve ? "Loan repair proposal approved successfully" : "Loan repair proposal rejected successfully",
                    proposalData(proposal));
        } catch (Exception e) {
            Debug.logError(e, approve ? "Error approving loan repair proposal" : "Error rejecting loan repair proposal", MODULE);
            return ServiceUtil.returnError(e.getMessage());
        }
    }

    private static Map<String, Object> recheckGovernance(LocalDispatcher dispatcher, GenericValue proposal, GenericValue userLogin) throws Exception {
        Map<String, Object> eligibilityCtx = new HashMap<String, Object>();
        eligibilityCtx.put("reconciliationIssueId", proposal.getString("reconciliationIssueId"));
        eligibilityCtx.put("organizationPartyId", proposal.getString("organizationPartyId"));
        eligibilityCtx.put("issueTypeEnumId", proposal.getString("issueTypeEnumId"));
        eligibilityCtx.put("severityEnumId", proposal.getString("severityEnumId"));
        eligibilityCtx.put("userLogin", userLogin);
        Map<String, Object> eligibility = dispatcher.runSync("getRepairEligibilityForIssueV1", eligibilityCtx);
        if (ServiceUtil.isError(eligibility)) {
            return eligibility;
        }
        if ("Y".equals(normalizeFlag((String) eligibility.get("blockBatchRepair")))) {
            return ServiceUtil.returnError("Current governance policy blocks repair execution for issueTypeEnumId="
                    + proposal.getString("issueTypeEnumId"));
        }
        if ("Y".equals(normalizeFlag((String) eligibility.get("manualApprovalRequired")))
                && !STATUS_APPROVED.equals(proposal.getString("proposalStatusId"))) {
            return ServiceUtil.returnError("Current governance policy requires approval before execution");
        }
        if (!"Y".equals(normalizeFlag((String) eligibility.get("policyFound")))) {
            return ServiceUtil.returnError("No active governance policy found; execution blocked");
        }
        return eligibility;
    }

    private static Map<String, Object> buildRepairContext(GenericValue proposal, Map<String, ?> overrideContext,
            GenericValue userLogin, String dryRun) {
        Map<String, Object> ctx = new LinkedHashMap<String, Object>();
        putIfNotEmpty(ctx, "loanId", proposal.getString("loanId"));
        putIfNotEmpty(ctx, "loanScheduleId", proposal.getString("loanScheduleId"));
        putIfNotEmpty(ctx, "loanRepaymentId", proposal.getString("loanRepaymentId"));
        putIfNotEmpty(ctx, "paymentId", proposal.getString("paymentId"));
        putIfNotEmpty(ctx, "acctgTransId", proposal.getString("acctgTransId"));
        putIfNotEmpty(ctx, "organizationPartyId", proposal.getString("organizationPartyId"));
        putIfNotEmpty(ctx, "paymentMethodId", firstNonEmpty(trimToNull((String) overrideContext.get("paymentMethodId")), proposal.getString("paymentMethodId")));
        putIfNotEmpty(ctx, "paymentMethodTypeId", firstNonEmpty(trimToNull((String) overrideContext.get("paymentMethodTypeId")), proposal.getString("paymentMethodTypeId")));
        putIfNotEmpty(ctx, "paymentTypeId", firstNonEmpty(trimToNull((String) overrideContext.get("paymentTypeId")), proposal.getString("paymentTypeId")));
        ctx.put("autoCreateAccounting", firstNonEmpty(trimToNull((String) overrideContext.get("autoCreateAccounting")), proposal.getString("autoCreateAccounting")));
        ctx.put("autoCreateFinAccountTrans", firstNonEmpty(trimToNull((String) overrideContext.get("autoCreateFinAccountTrans")), proposal.getString("autoCreateFinAccountTrans")));
        ctx.put("linkDuplicatePaymentIfSafe", firstNonEmpty(trimToNull((String) overrideContext.get("linkDuplicatePaymentIfSafe")), "Y"));
        ctx.put("requirePaymentApplication", firstNonEmpty(trimToNull((String) overrideContext.get("requirePaymentApplication")), "Y"));
        ctx.put("requireAccounting", firstNonEmpty(trimToNull((String) overrideContext.get("requireAccounting")), "Y"));
        ctx.put("dryRun", dryRun);
        ctx.put("userLogin", userLogin);
        return ctx;
    }

    private static GenericValue findExistingActiveProposal(Delegator delegator, String organizationPartyId, String loanId,
            String loanRepaymentId, String issueTypeEnumId) throws GenericEntityException {
        List<EntityCondition> conds = new ArrayList<EntityCondition>();
        addEq(conds, "organizationPartyId", organizationPartyId);
        addEq(conds, "loanId", loanId);
        addEq(conds, "loanRepaymentId", loanRepaymentId);
        addEq(conds, "issueTypeEnumId", issueTypeEnumId);
        List<String> activeStatuses = new ArrayList<String>();
        activeStatuses.add(STATUS_PROPOSED);
        activeStatuses.add(STATUS_PENDING_APPROVAL);
        activeStatuses.add(STATUS_APPROVED);
        activeStatuses.add(STATUS_EXECUTING);
        conds.add(EntityCondition.makeCondition("proposalStatusId", EntityOperator.IN, activeStatuses));
        return EntityQuery.use(delegator)
                .from("LoanRepairProposal")
                .where(EntityCondition.makeCondition(conds, EntityOperator.AND))
                .orderBy("-createdDate", "-repairProposalId")
                .queryFirst();
    }

    private static GenericValue requireProposal(Delegator delegator, String repairProposalId) throws GenericEntityException {
        if (UtilValidate.isEmpty(repairProposalId)) {
            throw new GenericEntityException("repairProposalId is required");
        }
        GenericValue proposal = EntityQuery.use(delegator)
                .from("LoanRepairProposal")
                .where("repairProposalId", repairProposalId)
                .queryOne();
        if (proposal == null) {
            throw new GenericEntityException("LoanRepairProposal not found: " + repairProposalId);
        }
        return proposal;
    }

    private static void markDryRunFailed(Delegator delegator, GenericValue proposal, Map<String, Object> result,
            GenericValue userLogin) throws GenericEntityException {
        String currentStatusId = proposal.getString("proposalStatusId");
        proposal.set("dryRunStatusId", DRY_FAILED);
        proposal.set("dryRunDate", UtilDateTime.nowTimestamp());
        proposal.set("dryRunResult", safeToString(result));
        proposal.store();
        createAudit(delegator, proposal.getString("repairProposalId"), "LRPA_DRY_RUN_FAILED", currentStatusId, currentStatusId,
                "Dry-run failed", result, userLogin);
    }

    private static void blockProposal(Delegator delegator, GenericValue proposal, String fromStatusId,
            Map<String, Object> result, GenericValue userLogin) throws GenericEntityException {
        proposal.set("proposalStatusId", STATUS_BLOCKED);
        proposal.set("lastStatusDate", UtilDateTime.nowTimestamp());
        proposal.store();
        createAudit(delegator, proposal.getString("repairProposalId"), "LRPA_BLOCKED_BY_POLICY", fromStatusId, STATUS_BLOCKED,
                "Repair proposal blocked by governance re-check", result, userLogin);
    }

    private static void createAudit(Delegator delegator, String repairProposalId, String auditEventTypeId,
            String fromStatusId, String toStatusId, String message, Object payload, GenericValue userLogin)
            throws GenericEntityException {
        GenericValue audit = delegator.makeValue("LoanRepairProposalAudit");
        audit.set("repairProposalAuditId", delegator.getNextSeqId("LoanRepairProposalAudit"));
        audit.set("repairProposalId", repairProposalId);
        audit.set("auditEventTypeId", auditEventTypeId);
        audit.set("fromStatusId", fromStatusId);
        audit.set("toStatusId", toStatusId);
        audit.set("message", message);
        audit.set("payload", safeToString(payload));
        audit.set("userLoginId", userLoginId(userLogin));
        audit.set("createdDate", UtilDateTime.nowTimestamp());
        delegator.create(audit);
    }

    private static Map<String, Object> proposalData(GenericValue proposal) {
        Map<String, Object> data = new LinkedHashMap<String, Object>();
        data.put("repairProposalId", proposal.getString("repairProposalId"));
        data.put("organizationPartyId", proposal.getString("organizationPartyId"));
        data.put("reconciliationIssueId", proposal.getString("reconciliationIssueId"));
        data.put("loanId", proposal.getString("loanId"));
        data.put("loanScheduleId", proposal.getString("loanScheduleId"));
        data.put("loanRepaymentId", proposal.getString("loanRepaymentId"));
        data.put("paymentId", proposal.getString("paymentId"));
        data.put("acctgTransId", proposal.getString("acctgTransId"));
        data.put("issueTypeEnumId", proposal.getString("issueTypeEnumId"));
        data.put("severityEnumId", proposal.getString("severityEnumId"));
        data.put("riskLevelEnumId", proposal.getString("riskLevelEnumId"));
        data.put("policyId", proposal.getString("policyId"));
        data.put("repairEligibilityStatus", proposal.getString("repairEligibilityStatus"));
        data.put("autoRepairAllowed", proposal.getString("autoRepairAllowed"));
        data.put("manualApprovalRequired", proposal.getString("manualApprovalRequired"));
        data.put("blockBatchRepair", proposal.getString("blockBatchRepair"));
        data.put("dryRunRequired", proposal.getString("dryRunRequired"));
        data.put("dryRunStatusId", proposal.getString("dryRunStatusId"));
        data.put("proposalStatusId", proposal.getString("proposalStatusId"));
        data.put("repairServiceName", proposal.getString("repairServiceName"));
        data.put("recommendedAction", proposal.getString("recommendedAction"));
        data.put("createdByUserLoginId", proposal.getString("createdByUserLoginId"));
        data.put("approvedByUserLoginId", proposal.getString("approvedByUserLoginId"));
        data.put("rejectedByUserLoginId", proposal.getString("rejectedByUserLoginId"));
        data.put("executedByUserLoginId", proposal.getString("executedByUserLoginId"));
        data.put("createdDate", proposal.getTimestamp("createdDate"));
        data.put("approvedDate", proposal.getTimestamp("approvedDate"));
        data.put("rejectedDate", proposal.getTimestamp("rejectedDate"));
        data.put("dryRunDate", proposal.getTimestamp("dryRunDate"));
        data.put("executionDate", proposal.getTimestamp("executionDate"));
        data.put("lastStatusDate", proposal.getTimestamp("lastStatusDate"));
        return data;
    }

    private static Map<String, Object> buildStandardSuccess(String successMessage, Map<String, Object> data) {
        Map<String, Object> out = ServiceUtil.returnSuccess(successMessage);
        Map<String, Object> safeData = new HashMap<String, Object>();
        if (data != null) {
            safeData.putAll(data);
            out.putAll(data);
        }
        out.put("data", safeData);
        out.put("legacyData", new HashMap<String, Object>(safeData));
        return out;
    }

    private static void addEq(List<EntityCondition> conds, String fieldName, String value) {
        if (UtilValidate.isNotEmpty(value)) {
            conds.add(EntityCondition.makeCondition(fieldName, EntityOperator.EQUALS, value));
        }
    }

    private static void putIfNotEmpty(Map<String, Object> map, String key, String value) {
        if (UtilValidate.isNotEmpty(value)) {
            map.put(key, value);
        }
    }

    private static boolean STATUS_EXECED_OR_EXECUTING(String statusId) {
        return STATUS_EXECUTED.equals(statusId) || STATUS_EXECUTING.equals(statusId);
    }


    private static int parsePositiveInt(Object value, int defaultValue, int maxValue) {
        if (value == null) {
            return defaultValue;
        }
        int parsed = defaultValue;
        try {
            if (value instanceof Number) {
                parsed = ((Number) value).intValue();
            } else {
                parsed = Integer.parseInt(String.valueOf(value));
            }
        } catch (Exception ignored) {
            parsed = defaultValue;
        }
        if (parsed <= 0) {
            parsed = defaultValue;
        }
        return Math.min(parsed, maxValue);
    }

    private static List<String> cleanupCandidateStatuses() {
        List<String> statuses = new ArrayList<String>();
        statuses.add(STATUS_PROPOSED);
        statuses.add(STATUS_PENDING_APPROVAL);
        statuses.add(STATUS_APPROVED);
        statuses.add(STATUS_BLOCKED);
        statuses.add(STATUS_EXEC_FAILED);
        return statuses;
    }

    private static String duplicateKey(GenericValue proposal) {
        StringBuilder key = new StringBuilder();
        key.append(safeKey(proposal.getString("organizationPartyId"))).append('|');
        key.append(safeKey(proposal.getString("loanId"))).append('|');
        key.append(safeKey(proposal.getString("loanScheduleId"))).append('|');
        key.append(safeKey(proposal.getString("loanRepaymentId"))).append('|');
        key.append(safeKey(proposal.getString("paymentId"))).append('|');
        key.append(safeKey(proposal.getString("acctgTransId"))).append('|');
        key.append(safeKey(proposal.getString("issueTypeEnumId")));
        return key.toString();
    }

    private static String safeKey(String value) {
        String safe = trimToNull(value);
        return safe == null ? "~" : safe;
    }

    private static String userLoginId(GenericValue userLogin) {
        return userLogin != null ? userLogin.getString("userLoginId") : null;
    }

    private static String trimToNull(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.length() == 0 ? null : trimmed;
    }

    private static String normalizeFlag(String value) {
        return "Y".equalsIgnoreCase(trimToNull(value)) ? "Y" : "N";
    }

    private static String normalizeFlagDefaultY(String value) {
        String normalized = trimToNull(value);
        if (UtilValidate.isEmpty(normalized)) {
            return "Y";
        }
        return "Y".equalsIgnoreCase(normalized) ? "Y" : "N";
    }

    private static String firstNonEmpty(String first, String second) {
        return UtilValidate.isNotEmpty(first) ? first : second;
    }

    private static String safeToString(Object value) {
        if (value == null) {
            return null;
        }
        String text = String.valueOf(value);
        return text.length() > 3900 ? text.substring(0, 3900) : text;
    }

    /**
     * Phase 6L - close open repair proposals that are obsolete because
     * the current reconciliation for their loan is now clean.
     *
     * This service does not delete proposals. It marks eligible rows as
     * LRP_OBSOLETE and keeps the lifecycle auditable.
     */
    public static Map<String, Object> closeObsoleteLoanRepairProposalsV1(DispatchContext dctx, Map<String, ?> context) {
        Delegator delegator = dctx.getDelegator();
        LocalDispatcher dispatcher = dctx.getDispatcher();
        GenericValue userLogin = (GenericValue) context.get("userLogin");

        String organizationPartyId = trimToNull((String) context.get("organizationPartyId"));
        String loanId = trimToNull((String) context.get("loanId"));
        String dryRun = normalizeFlagDefaultY((String) context.get("dryRun"));
        int maxRows = phase6lParsePositiveInt(context.get("maxRows"), 200, 5000);

        int checkedProposalCount = 0;
        int eligibleProposalCount = 0;
        int obsoleteClosedCount = 0;
        int wouldCloseCount = 0;
        int skippedCount = 0;
        int errorCount = 0;

        List<Map<String, Object>> actions = new ArrayList<Map<String, Object>>();

        try {
            if (UtilValidate.isEmpty(organizationPartyId)) {
                return ServiceUtil.returnError("organizationPartyId is required");
            }

            List<EntityCondition> conditions = new ArrayList<EntityCondition>();
            conditions.add(EntityCondition.makeCondition("organizationPartyId", organizationPartyId));
            conditions.add(EntityCondition.makeCondition("proposalStatusId", EntityOperator.IN, phase6lObsoleteCandidateStatuses()));
            if (UtilValidate.isNotEmpty(loanId)) {
                conditions.add(EntityCondition.makeCondition("loanId", loanId));
            }

            List<GenericValue> proposals = EntityQuery.use(delegator)
                    .from("LoanRepairProposal")
                    .where(EntityCondition.makeCondition(conditions, EntityOperator.AND))
                    .orderBy("-createdDate", "-repairProposalId")
                    .maxRows(maxRows)
                    .queryList();

            Map<String, Map<String, Object>> reconCache = new LinkedHashMap<String, Map<String, Object>>();

            for (GenericValue proposal : proposals) {
                checkedProposalCount++;

                String repairProposalId = proposal.getString("repairProposalId");
                String currentLoanId = trimToNull(proposal.getString("loanId"));
                String fromStatusId = proposal.getString("proposalStatusId");

                Map<String, Object> action = new LinkedHashMap<String, Object>();
                action.put("repairProposalId", repairProposalId);
                action.put("loanId", currentLoanId);
                action.put("loanScheduleId", proposal.getString("loanScheduleId"));
                action.put("loanRepaymentId", proposal.getString("loanRepaymentId"));
                action.put("issueTypeEnumId", proposal.getString("issueTypeEnumId"));
                action.put("fromStatusId", fromStatusId);
                action.put("dryRun", dryRun);

                if (UtilValidate.isEmpty(currentLoanId)) {
                    skippedCount++;
                    action.put("status", "SKIPPED");
                    action.put("reason", "Proposal has no loanId; cannot verify current reconciliation state");
                    actions.add(action);
                    continue;
                }

                Map<String, Object> reconData = reconCache.get(currentLoanId);
                if (reconData == null) {
                    Map<String, Object> reconCtx = new LinkedHashMap<String, Object>();
                    reconCtx.put("organizationPartyId", organizationPartyId);
                    reconCtx.put("loanId", currentLoanId);
                    reconCtx.put("strictDuplicateAcctg", "N");
                    reconCtx.put("requireFinAccountTrans", "N");
                    reconCtx.put("userLogin", userLogin);

                    Map<String, Object> reconResp = dispatcher.runSync("reconcileLoanAccountingV1", reconCtx);
                    if (ServiceUtil.isError(reconResp)) {
                        errorCount++;
                        action.put("status", "ERROR");
                        action.put("reason", ServiceUtil.getErrorMessage(reconResp));
                        actions.add(action);
                        continue;
                    }
                    reconData = phase6lExtractPayload(reconResp);
                    reconCache.put(currentLoanId, reconData);
                }

                int currentErrorCount = phase6lGetInt(reconData, "errorCount");
                int currentIssueCount = phase6lGetInt(reconData, "issueCount");
                int currentWarningCount = phase6lGetInt(reconData, "warningCount");

                action.put("currentErrorCount", Integer.valueOf(currentErrorCount));
                action.put("currentIssueCount", Integer.valueOf(currentIssueCount));
                action.put("currentWarningCount", Integer.valueOf(currentWarningCount));

                if (currentErrorCount == 0 && currentIssueCount == 0) {
                    eligibleProposalCount++;
                    action.put("toStatusId", STATUS_OBSOLETE);
                    action.put("reason", "Current reconciliation is clean; open repair proposal is obsolete");

                    if ("Y".equals(dryRun)) {
                        wouldCloseCount++;
                        action.put("status", "DRY_RUN");
                    } else {
                        proposal.set("proposalStatusId", STATUS_OBSOLETE);
                        proposal.set("lastStatusDate", UtilDateTime.nowTimestamp());
                        proposal.store();

                        obsoleteClosedCount++;
                        action.put("status", "DONE");

                        try {
                            createAudit(delegator, repairProposalId, "LRPA_MARKED_OBSOLETE",
                                    fromStatusId, STATUS_OBSOLETE,
                                    "Proposal marked obsolete because current reconciliation is clean", action, userLogin);
                        } catch (Exception auditEx) {
                            Debug.logWarning(auditEx, "Could not create obsolete proposal audit for " + repairProposalId, MODULE);
                        }
                    }
                } else {
                    skippedCount++;
                    action.put("status", "SKIPPED");
                    action.put("reason", "Current reconciliation still has issues; proposal remains active");
                }

                actions.add(action);
            }

            Map<String, Object> payload = new LinkedHashMap<String, Object>();
            payload.put("organizationPartyId", organizationPartyId);
            payload.put("loanId", loanId);
            payload.put("dryRun", dryRun);
            payload.put("maxRows", Integer.valueOf(maxRows));
            payload.put("checkedProposalCount", Integer.valueOf(checkedProposalCount));
            payload.put("eligibleProposalCount", Integer.valueOf(eligibleProposalCount));
            payload.put("wouldCloseCount", Integer.valueOf(wouldCloseCount));
            payload.put("obsoleteClosedCount", Integer.valueOf(obsoleteClosedCount));
            payload.put("skippedCount", Integer.valueOf(skippedCount));
            payload.put("errorCount", Integer.valueOf(errorCount));
            payload.put("actions", actions);
            payload.put("finalStatus", "Y".equals(dryRun) ? "DRY_RUN" : errorCount > 0 ? "COMPLETED_WITH_ERRORS" : "DONE");

            return buildStandardSuccess("Obsolete loan repair proposal resolution completed successfully", payload);
        } catch (Exception e) {
            Debug.logError(e, "Error closing obsolete loan repair proposals", MODULE);
            return ServiceUtil.returnError(e.getMessage());
        }
    }

    private static int phase6lParsePositiveInt(Object value, int defaultValue, int maxValue) {
        if (value == null) return defaultValue;
        try {
            int parsed;
            if (value instanceof Number) {
                parsed = ((Number) value).intValue();
            } else {
                parsed = Integer.parseInt(String.valueOf(value).trim());
            }
            if (parsed <= 0) return defaultValue;
            return Math.min(parsed, maxValue);
        } catch (Exception e) {
            return defaultValue;
        }
    }

    private static List<String> phase6lObsoleteCandidateStatuses() {
        List<String> statuses = new ArrayList<String>();
        statuses.add(STATUS_PROPOSED);
        statuses.add(STATUS_PENDING_APPROVAL);
        statuses.add(STATUS_APPROVED);
        statuses.add(STATUS_BLOCKED);
        statuses.add(STATUS_EXEC_FAILED);
        return statuses;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> phase6lExtractPayload(Map<String, Object> response) {
        if (response == null) return new LinkedHashMap<String, Object>();
        Object data = response.get("data");
        if (data instanceof Map) {
            Object nested = ((Map<String, Object>) data).get("data");
            if (nested instanceof Map) return (Map<String, Object>) nested;
            return (Map<String, Object>) data;
        }
        Object legacy = response.get("legacyData");
        if (legacy instanceof Map) return (Map<String, Object>) legacy;
        return response;
    }

    private static int phase6lGetInt(Map<String, Object> map, String key) {
        if (map == null) return 0;
        Object value = map.get(key);
        if (value instanceof Number) return ((Number) value).intValue();
        if (value == null) return 0;
        try {
            return Integer.parseInt(String.valueOf(value));
        } catch (Exception e) {
            return 0;
        }
    }



    public static Map<String, Object> getLoanGovernanceQueueV1(DispatchContext dctx, Map<String, ?> context) {
        Delegator delegator = dctx.getDelegator();
        try {
            String organizationPartyId = trimToNull((String) context.get("organizationPartyId"));
            String loanId = trimToNull((String) context.get("loanId"));
            String proposalStatusId = trimToNull((String) context.get("proposalStatusId"));
            String issueTypeEnumId = trimToNull((String) context.get("issueTypeEnumId"));
            String severityEnumId = trimToNull((String) context.get("severityEnumId"));
            String riskLevelEnumId = trimToNull((String) context.get("riskLevelEnumId"));
            String assignedToUserLoginId = trimToNull((String) context.get("assignedToUserLoginId"));
            int viewIndex = phase10ParseInt(context.get("viewIndex"), 0, 0, 100000);
            int viewSize = phase10ParseInt(context.get("viewSize"), 25, 1, 500);
            int maxRows = (viewIndex + 1) * viewSize;

            if (UtilValidate.isEmpty(organizationPartyId)) {
                return ServiceUtil.returnError("organizationPartyId is required");
            }

            List<EntityCondition> conds = new ArrayList<EntityCondition>();
            conds.add(EntityCondition.makeCondition("organizationPartyId", organizationPartyId));
            if (UtilValidate.isNotEmpty(loanId)) conds.add(EntityCondition.makeCondition("loanId", loanId));
            if (UtilValidate.isNotEmpty(proposalStatusId)) {
                conds.add(EntityCondition.makeCondition("proposalStatusId", proposalStatusId));
            } else {
                conds.add(EntityCondition.makeCondition("proposalStatusId", EntityOperator.IN, phase10QueueStatuses()));
            }
            if (UtilValidate.isNotEmpty(issueTypeEnumId)) conds.add(EntityCondition.makeCondition("issueTypeEnumId", issueTypeEnumId));
            if (UtilValidate.isNotEmpty(severityEnumId)) conds.add(EntityCondition.makeCondition("severityEnumId", severityEnumId));
            if (UtilValidate.isNotEmpty(riskLevelEnumId)) conds.add(EntityCondition.makeCondition("riskLevelEnumId", riskLevelEnumId));

            List<GenericValue> proposals = EntityQuery.use(delegator)
                    .from("LoanRepairProposal")
                    .where(EntityCondition.makeCondition(conds, EntityOperator.AND))
                    .orderBy("-createdDate", "-repairProposalId")
                    .maxRows(maxRows)
                    .queryList();

            List<Map<String, Object>> allRows = new ArrayList<Map<String, Object>>();
            int pendingApprovalCount = 0, approvedCount = 0, blockedCount = 0, failedCount = 0;
            int obsoleteCount = 0, executedCount = 0, unassignedCount = 0, overSlaCount = 0;

            for (GenericValue proposal : proposals) {
                Map<String, Object> row = phase10ProposalQueueRow(delegator, proposal);
                String rowAssignedTo = trimToNull((String) row.get("assignedToUserLoginId"));
                if (UtilValidate.isNotEmpty(assignedToUserLoginId) && !assignedToUserLoginId.equals(rowAssignedTo)) {
                    continue;
                }
                String status = (String) row.get("proposalStatusId");
                if (STATUS_PENDING_APPROVAL.equals(status)) pendingApprovalCount++;
                if (STATUS_APPROVED.equals(status)) approvedCount++;
                if (STATUS_BLOCKED.equals(status)) blockedCount++;
                if (STATUS_EXEC_FAILED.equals(status)) failedCount++;
                if (STATUS_OBSOLETE.equals(status)) obsoleteCount++;
                if (STATUS_EXECUTED.equals(status)) executedCount++;
                if (UtilValidate.isEmpty(rowAssignedTo)) unassignedCount++;
                if ("OVER_SLA".equals(row.get("slaStatus"))) overSlaCount++;
                allRows.add(row);
            }

            int totalCount = allRows.size();
            int fromIndex = Math.min(viewIndex * viewSize, totalCount);
            int toIndex = Math.min(fromIndex + viewSize, totalCount);
            List<Map<String, Object>> pageRows = new ArrayList<Map<String, Object>>(allRows.subList(fromIndex, toIndex));

            Map<String, Object> data = new LinkedHashMap<String, Object>();
            data.put("organizationPartyId", organizationPartyId);
            data.put("loanId", loanId);
            data.put("proposalStatusId", proposalStatusId);
            data.put("issueTypeEnumId", issueTypeEnumId);
            data.put("severityEnumId", severityEnumId);
            data.put("riskLevelEnumId", riskLevelEnumId);
            data.put("assignedToUserLoginId", assignedToUserLoginId);
            data.put("viewIndex", Integer.valueOf(viewIndex));
            data.put("viewSize", Integer.valueOf(viewSize));
            data.put("totalCount", Integer.valueOf(totalCount));
            data.put("queueList", pageRows);
            data.put("proposalList", pageRows);
            data.put("pendingApprovalCount", Integer.valueOf(pendingApprovalCount));
            data.put("approvedCount", Integer.valueOf(approvedCount));
            data.put("blockedCount", Integer.valueOf(blockedCount));
            data.put("failedCount", Integer.valueOf(failedCount));
            data.put("obsoleteCount", Integer.valueOf(obsoleteCount));
            data.put("executedCount", Integer.valueOf(executedCount));
            data.put("unassignedCount", Integer.valueOf(unassignedCount));
            data.put("overSlaCount", Integer.valueOf(overSlaCount));
            data.put("finalStatus", totalCount > 0 ? "QUEUE_READY" : "QUEUE_EMPTY");

            return buildStandardSuccess("Loan governance queue retrieved successfully", data);
        } catch (Exception e) {
            Debug.logError(e, "Error retrieving loan governance queue", MODULE);
            return ServiceUtil.returnError(e.getMessage());
        }
    }

    public static Map<String, Object> assignLoanGovernanceProposalV1(DispatchContext dctx, Map<String, ?> context) {
        Delegator delegator = dctx.getDelegator();
        GenericValue userLogin = (GenericValue) context.get("userLogin");
        try {
            String repairProposalId = trimToNull((String) context.get("repairProposalId"));
            String assignedToUserLoginId = trimToNull((String) context.get("assignedToUserLoginId"));
            String assignmentComment = trimToNull((String) context.get("assignmentComment"));
            if (UtilValidate.isEmpty(repairProposalId)) return ServiceUtil.returnError("repairProposalId is required");
            if (UtilValidate.isEmpty(assignedToUserLoginId)) return ServiceUtil.returnError("assignedToUserLoginId is required");

            GenericValue proposal = requireProposal(delegator, repairProposalId);
            String currentStatusId = proposal.getString("proposalStatusId");
            if (phase10bIsTerminalProposalStatus(currentStatusId)) {
                return phase10bReadOnlyError("Assignment", proposal);
            }
            if (!phase10bIsActionableProposalStatus(currentStatusId)) {
                return ServiceUtil.returnError("Assignment is not allowed for current proposal status: " + currentStatusId);
            }
            Timestamp nowTs = UtilDateTime.nowTimestamp();

            GenericValue existing = EntityQuery.use(delegator)
                    .from("LoanRepairProposalAssignment")
                    .where("repairProposalId", repairProposalId, "thruDate", null)
                    .queryFirst();
            if (existing != null) {
                existing.set("thruDate", nowTs);
                existing.set("assignmentStatusId", "LRP_ASGN_CLOSED");
                existing.store();
            }

            String assignmentId = delegator.getNextSeqId("LoanRepairProposalAssignment");
            GenericValue assignment = delegator.makeValue("LoanRepairProposalAssignment");
            assignment.set("assignmentId", assignmentId);
            assignment.set("repairProposalId", repairProposalId);
            assignment.set("organizationPartyId", proposal.getString("organizationPartyId"));
            assignment.set("loanId", proposal.getString("loanId"));
            assignment.set("assignedToUserLoginId", assignedToUserLoginId);
            assignment.set("assignedByUserLoginId", userLoginId(userLogin));
            assignment.set("assignedDate", nowTs);
            assignment.set("assignmentStatusId", "LRP_ASGN_ACTIVE");
            assignment.set("assignmentComment", assignmentComment);
            delegator.create(assignment);

            Map<String, Object> auditData = new LinkedHashMap<String, Object>();
            auditData.put("assignmentId", assignmentId);
            auditData.put("assignedToUserLoginId", assignedToUserLoginId);
            auditData.put("assignmentComment", assignmentComment);
            createAudit(delegator, repairProposalId, "LRPA_ASSIGNED", proposal.getString("proposalStatusId"),
                    proposal.getString("proposalStatusId"), "Repair proposal assigned", auditData, userLogin);

            Map<String, Object> data = proposalData(proposal);
            data.put("assignmentId", assignmentId);
            data.put("assignedToUserLoginId", assignedToUserLoginId);
            data.put("assignedByUserLoginId", userLoginId(userLogin));
            data.put("assignedDate", nowTs);
            data.put("assignmentStatusId", "LRP_ASGN_ACTIVE");
            return buildStandardSuccess("Loan governance proposal assigned successfully", data);
        } catch (Exception e) {
            Debug.logError(e, "Error assigning loan governance proposal", MODULE);
            return ServiceUtil.returnError(e.getMessage());
        }
    }

    public static Map<String, Object> unassignLoanGovernanceProposalV1(DispatchContext dctx, Map<String, ?> context) {
        Delegator delegator = dctx.getDelegator();
        GenericValue userLogin = (GenericValue) context.get("userLogin");
        try {
            String repairProposalId = trimToNull((String) context.get("repairProposalId"));
            String unassignmentComment = trimToNull((String) context.get("unassignmentComment"));
            if (UtilValidate.isEmpty(repairProposalId)) return ServiceUtil.returnError("repairProposalId is required");
            GenericValue proposal = requireProposal(delegator, repairProposalId);
            Timestamp nowTs = UtilDateTime.nowTimestamp();
            int closedCount = 0;
            List<GenericValue> assignments = EntityQuery.use(delegator)
                    .from("LoanRepairProposalAssignment")
                    .where("repairProposalId", repairProposalId, "thruDate", null)
                    .queryList();
            for (GenericValue assignment : assignments) {
                assignment.set("thruDate", nowTs);
                assignment.set("assignmentStatusId", "LRP_ASGN_CLOSED");
                assignment.store();
                closedCount++;
            }
            Map<String, Object> auditData = new LinkedHashMap<String, Object>();
            auditData.put("closedAssignmentCount", Integer.valueOf(closedCount));
            auditData.put("unassignmentComment", unassignmentComment);
            createAudit(delegator, repairProposalId, "LRPA_UNASSIGNED", proposal.getString("proposalStatusId"),
                    proposal.getString("proposalStatusId"), "Repair proposal unassigned", auditData, userLogin);
            Map<String, Object> data = proposalData(proposal);
            data.put("closedAssignmentCount", Integer.valueOf(closedCount));
            return buildStandardSuccess("Loan governance proposal unassigned successfully", data);
        } catch (Exception e) {
            Debug.logError(e, "Error unassigning loan governance proposal", MODULE);
            return ServiceUtil.returnError(e.getMessage());
        }
    }

    public static Map<String, Object> addLoanGovernanceCommentV1(DispatchContext dctx, Map<String, ?> context) {
        Delegator delegator = dctx.getDelegator();
        GenericValue userLogin = (GenericValue) context.get("userLogin");
        try {
            String repairProposalId = trimToNull((String) context.get("repairProposalId"));
            String commentText = trimToNull((String) context.get("commentText"));
            String commentTypeEnumId = firstNonEmpty(trimToNull((String) context.get("commentTypeEnumId")), "LRP_COMMENT");
            if (UtilValidate.isEmpty(repairProposalId)) return ServiceUtil.returnError("repairProposalId is required");
            if (UtilValidate.isEmpty(commentText)) return ServiceUtil.returnError("commentText is required");

            GenericValue proposal = requireProposal(delegator, repairProposalId);
            Timestamp nowTs = UtilDateTime.nowTimestamp();
            String commentId = delegator.getNextSeqId("LoanRepairProposalComment");
            GenericValue comment = delegator.makeValue("LoanRepairProposalComment");
            comment.set("commentId", commentId);
            comment.set("repairProposalId", repairProposalId);
            comment.set("organizationPartyId", proposal.getString("organizationPartyId"));
            comment.set("loanId", proposal.getString("loanId"));
            comment.set("commentTypeEnumId", commentTypeEnumId);
            comment.set("commentText", commentText);
            comment.set("createdByUserLoginId", userLoginId(userLogin));
            comment.set("createdDate", nowTs);
            delegator.create(comment);

            Map<String, Object> auditData = new LinkedHashMap<String, Object>();
            auditData.put("commentId", commentId);
            auditData.put("commentTypeEnumId", commentTypeEnumId);
            createAudit(delegator, repairProposalId, "LRPA_COMMENT_ADDED", proposal.getString("proposalStatusId"),
                    proposal.getString("proposalStatusId"), "Governance comment added", auditData, userLogin);

            Map<String, Object> data = phase10CommentData(comment);
            return buildStandardSuccess("Loan governance comment added successfully", data);
        } catch (Exception e) {
            Debug.logError(e, "Error adding loan governance comment", MODULE);
            return ServiceUtil.returnError(e.getMessage());
        }
    }

    public static Map<String, Object> getLoanGovernanceCommentListV1(DispatchContext dctx, Map<String, ?> context) {
        Delegator delegator = dctx.getDelegator();
        try {
            String repairProposalId = trimToNull((String) context.get("repairProposalId"));
            String organizationPartyId = trimToNull((String) context.get("organizationPartyId"));
            String loanId = trimToNull((String) context.get("loanId"));
            int viewIndex = phase10ParseInt(context.get("viewIndex"), 0, 0, 100000);
            int viewSize = phase10ParseInt(context.get("viewSize"), 25, 1, 500);

            List<EntityCondition> conds = new ArrayList<EntityCondition>();
            if (UtilValidate.isNotEmpty(repairProposalId)) conds.add(EntityCondition.makeCondition("repairProposalId", repairProposalId));
            if (UtilValidate.isNotEmpty(organizationPartyId)) conds.add(EntityCondition.makeCondition("organizationPartyId", organizationPartyId));
            if (UtilValidate.isNotEmpty(loanId)) conds.add(EntityCondition.makeCondition("loanId", loanId));
            if (conds.isEmpty()) return ServiceUtil.returnError("repairProposalId, organizationPartyId, or loanId is required");

            List<GenericValue> comments = EntityQuery.use(delegator)
                    .from("LoanRepairProposalComment")
                    .where(EntityCondition.makeCondition(conds, EntityOperator.AND))
                    .orderBy("-createdDate", "-commentId")
                    .queryList();

            int totalCount = comments.size();
            int fromIndex = Math.min(viewIndex * viewSize, totalCount);
            int toIndex = Math.min(fromIndex + viewSize, totalCount);
            List<Map<String, Object>> rows = new ArrayList<Map<String, Object>>();
            for (GenericValue comment : comments.subList(fromIndex, toIndex)) {
                rows.add(phase10CommentData(comment));
            }
            Map<String, Object> data = new LinkedHashMap<String, Object>();
            data.put("repairProposalId", repairProposalId);
            data.put("organizationPartyId", organizationPartyId);
            data.put("loanId", loanId);
            data.put("viewIndex", Integer.valueOf(viewIndex));
            data.put("viewSize", Integer.valueOf(viewSize));
            data.put("totalCount", Integer.valueOf(totalCount));
            data.put("commentList", rows);
            return buildStandardSuccess("Loan governance comment list retrieved successfully", data);
        } catch (Exception e) {
            Debug.logError(e, "Error retrieving loan governance comments", MODULE);
            return ServiceUtil.returnError(e.getMessage());
        }
    }

    public static Map<String, Object> approveLoanGovernanceProposalV1(DispatchContext dctx, Map<String, ?> context) {
        return approveLoanRepairProposalV1(dctx, context);
    }

    public static Map<String, Object> rejectLoanGovernanceProposalV1(DispatchContext dctx, Map<String, ?> context) {
        return rejectLoanRepairProposalV1(dctx, context);
    }

    private static Map<String, Object> phase10ProposalQueueRow(Delegator delegator, GenericValue proposal) throws GenericEntityException {
        Map<String, Object> row = proposalData(proposal);
        String repairProposalId = proposal.getString("repairProposalId");
        Timestamp createdDate = proposal.getTimestamp("createdDate");
        long ageHours = 0L;
        if (createdDate != null) {
            long millis = UtilDateTime.nowTimestamp().getTime() - createdDate.getTime();
            if (millis > 0L) ageHours = millis / (1000L * 60L * 60L);
        }
        String risk = trimToNull(proposal.getString("riskLevelEnumId"));
        int slaHours = phase10SlaHours(risk, proposal.getString("severityEnumId"));
        String slaStatus = ageHours >= slaHours ? "OVER_SLA" : ageHours >= (slaHours * 75L / 100L) ? "NEAR_SLA" : "WITHIN_SLA";

        GenericValue assignment = EntityQuery.use(delegator)
                .from("LoanRepairProposalAssignment")
                .where("repairProposalId", repairProposalId, "thruDate", null)
                .orderBy("-assignedDate", "-assignmentId")
                .queryFirst();
        if (assignment != null) {
            row.put("assignmentId", assignment.getString("assignmentId"));
            row.put("assignedToUserLoginId", assignment.getString("assignedToUserLoginId"));
            row.put("assignedByUserLoginId", assignment.getString("assignedByUserLoginId"));
            row.put("assignedDate", assignment.getTimestamp("assignedDate"));
            row.put("assignmentStatusId", assignment.getString("assignmentStatusId"));
            row.put("assignmentComment", assignment.getString("assignmentComment"));
        }

        long commentCount = EntityQuery.use(delegator)
                .from("LoanRepairProposalComment")
                .where("repairProposalId", repairProposalId)
                .queryCount();

        row.put("source", "proposal");
        row.put("queueKey", repairProposalId);
        row.put("ageHours", Long.valueOf(ageHours));
        row.put("slaHours", Integer.valueOf(slaHours));
        row.put("slaStatus", slaStatus);
        row.put("commentCount", Long.valueOf(commentCount));
        row.put("priorityRank", Integer.valueOf(phase10PriorityRank(proposal, slaStatus)));
        row.put("actionable", phase10ActionableFlag(proposal));
        row.put("readOnly", phase10bIsTerminalProposalStatus(proposal.getString("proposalStatusId")) ? "Y" : "N");
        row.put("actionReason", phase10bIsTerminalProposalStatus(proposal.getString("proposalStatusId"))
                ? "Closed proposal - read-only"
                : ("Y".equals(phase10ActionableFlag(proposal)) ? "Actionable governance proposal" : "Not actionable for current status"));
        return row;
    }

    private static Map<String, Object> phase10CommentData(GenericValue comment) {
        Map<String, Object> data = new LinkedHashMap<String, Object>();
        data.put("commentId", comment.getString("commentId"));
        data.put("repairProposalId", comment.getString("repairProposalId"));
        data.put("organizationPartyId", comment.getString("organizationPartyId"));
        data.put("loanId", comment.getString("loanId"));
        data.put("commentTypeEnumId", comment.getString("commentTypeEnumId"));
        data.put("commentText", comment.getString("commentText"));
        data.put("createdByUserLoginId", comment.getString("createdByUserLoginId"));
        data.put("createdDate", comment.getTimestamp("createdDate"));
        return data;
    }

    private static List<String> phase10QueueStatuses() {
        List<String> statuses = new ArrayList<String>();
        statuses.add(STATUS_PROPOSED);
        statuses.add(STATUS_PENDING_APPROVAL);
        statuses.add(STATUS_APPROVED);
        statuses.add(STATUS_BLOCKED);
        statuses.add(STATUS_EXEC_FAILED);
        statuses.add(STATUS_EXECUTING);
        statuses.add(STATUS_OBSOLETE);
        statuses.add(STATUS_EXECUTED);
        return statuses;
    }

    private static int phase10SlaHours(String riskLevelEnumId, String severityEnumId) {
        if ("CRITICAL".equals(riskLevelEnumId) || "ERROR".equals(severityEnumId)) return 24;
        if ("HIGH".equals(riskLevelEnumId)) return 48;
        if ("MEDIUM".equals(riskLevelEnumId)) return 72;
        return 120;
    }

    private static int phase10PriorityRank(GenericValue proposal, String slaStatus) {
        int rank = 100;
        if ("OVER_SLA".equals(slaStatus)) rank -= 50;
        if ("NEAR_SLA".equals(slaStatus)) rank -= 25;
        if ("CRITICAL".equals(proposal.getString("riskLevelEnumId"))) rank -= 20;
        if ("HIGH".equals(proposal.getString("riskLevelEnumId"))) rank -= 15;
        if ("ERROR".equals(proposal.getString("severityEnumId"))) rank -= 10;
        if (STATUS_EXEC_FAILED.equals(proposal.getString("proposalStatusId"))) rank -= 30;
        return rank;
    }

    private static String phase10ActionableFlag(GenericValue proposal) {
        String status = proposal.getString("proposalStatusId");
        if (STATUS_PENDING_APPROVAL.equals(status) || STATUS_APPROVED.equals(status)
                || STATUS_EXEC_FAILED.equals(status) || STATUS_BLOCKED.equals(status)) return "Y";
        return "N";
    }

    private static int phase10ParseInt(Object value, int defaultValue, int minValue, int maxValue) {
        try {
            int parsed;
            if (value == null) return defaultValue;
            if (value instanceof Number) parsed = ((Number) value).intValue();
            else parsed = Integer.parseInt(String.valueOf(value).trim());
            if (parsed < minValue) return minValue;
            if (parsed > maxValue) return maxValue;
            return parsed;
        } catch (Exception e) {
            return defaultValue;
        }
    }

    private static boolean phase10bIsTerminalProposalStatus(String statusId) {
        return STATUS_EXECUTED.equals(statusId) || STATUS_OBSOLETE.equals(statusId) || STATUS_REJECTED.equals(statusId);
    }

    private static boolean phase10bIsActionableProposalStatus(String statusId) {
        return STATUS_PROPOSED.equals(statusId)
                || STATUS_PENDING_APPROVAL.equals(statusId)
                || STATUS_APPROVED.equals(statusId)
                || STATUS_BLOCKED.equals(statusId)
                || STATUS_EXEC_FAILED.equals(statusId);
    }

    private static Map<String, Object> phase10bReadOnlyError(String actionName, GenericValue proposal) {
        String statusId = proposal != null ? proposal.getString("proposalStatusId") : null;
        String repairProposalId = proposal != null ? proposal.getString("repairProposalId") : null;
        return ServiceUtil.returnError(actionName + " is not allowed for closed/read-only repair proposal. repairProposalId="
                + repairProposalId + ", current status: " + statusId);
    }


    public static Map<String, Object> getLoanGovernanceProposalDetailV1(DispatchContext dctx, Map<String, ?> context) {
        Delegator delegator = dctx.getDelegator();
        try {
            String repairProposalId = trimToNull((String) context.get("repairProposalId"));
            if (UtilValidate.isEmpty(repairProposalId)) {
                return ServiceUtil.returnError("repairProposalId is required");
            }
            GenericValue proposal = requireProposal(delegator, repairProposalId);
            Map<String, Object> data = new LinkedHashMap<String, Object>();
            data.put("repairProposalId", repairProposalId);
            data.put("proposal", proposalData(proposal));
            data.put("assignmentHistory", phase11AssignmentHistory(delegator, repairProposalId));
            data.put("commentList", phase11CommentList(delegator, repairProposalId));
            data.put("auditTimeline", phase11AuditTimeline(delegator, repairProposalId));
            data.put("relatedEntities", phase11RelatedEntities(delegator, proposal));
            data.put("financeTrace", phase11FinanceTrace(delegator, proposal));
            data.put("latestReconciliation", phase11LatestReconciliation(delegator, proposal));
            data.put("finalStatus", "DETAIL_READY");
            return buildStandardSuccess("Loan governance proposal detail retrieved successfully", data);
        } catch (Exception e) {
            Debug.logError(e, "Error retrieving loan governance proposal detail", MODULE);
            return ServiceUtil.returnError(e.getMessage());
        }
    }

    public static Map<String, Object> getLoanGovernanceProposalTimelineV1(DispatchContext dctx, Map<String, ?> context) {
        Delegator delegator = dctx.getDelegator();
        try {
            String repairProposalId = trimToNull((String) context.get("repairProposalId"));
            if (UtilValidate.isEmpty(repairProposalId)) {
                return ServiceUtil.returnError("repairProposalId is required");
            }
            GenericValue proposal = requireProposal(delegator, repairProposalId);
            List<Map<String, Object>> timeline = new ArrayList<Map<String, Object>>();
            phase11AddProposalMilestones(timeline, proposal);
            timeline.addAll(phase11AuditTimeline(delegator, repairProposalId));
            timeline.addAll(phase11CommentTimeline(delegator, repairProposalId));
            timeline.addAll(phase11AssignmentTimeline(delegator, repairProposalId));
            phase11SortTimeline(timeline);
            Map<String, Object> data = new LinkedHashMap<String, Object>();
            data.put("repairProposalId", repairProposalId);
            data.put("timeline", timeline);
            data.put("timelineCount", Integer.valueOf(timeline.size()));
            data.put("finalStatus", "TIMELINE_READY");
            return buildStandardSuccess("Loan governance proposal timeline retrieved successfully", data);
        } catch (Exception e) {
            Debug.logError(e, "Error retrieving loan governance proposal timeline", MODULE);
            return ServiceUtil.returnError(e.getMessage());
        }
    }

    public static Map<String, Object> getLoanGovernanceRelatedFinanceTraceV1(DispatchContext dctx, Map<String, ?> context) {
        Delegator delegator = dctx.getDelegator();
        try {
            String repairProposalId = trimToNull((String) context.get("repairProposalId"));
            if (UtilValidate.isEmpty(repairProposalId)) {
                return ServiceUtil.returnError("repairProposalId is required");
            }
            GenericValue proposal = requireProposal(delegator, repairProposalId);
            Map<String, Object> data = phase11FinanceTrace(delegator, proposal);
            data.put("repairProposalId", repairProposalId);
            data.put("loanId", proposal.getString("loanId"));
            data.put("loanRepaymentId", proposal.getString("loanRepaymentId"));
            data.put("finalStatus", "FINANCE_TRACE_READY");
            return buildStandardSuccess("Loan governance finance trace retrieved successfully", data);
        } catch (Exception e) {
            Debug.logError(e, "Error retrieving loan governance finance trace", MODULE);
            return ServiceUtil.returnError(e.getMessage());
        }
    }

    private static List<Map<String, Object>> phase11AssignmentHistory(Delegator delegator, String repairProposalId) throws GenericEntityException {
        List<Map<String, Object>> rows = new ArrayList<Map<String, Object>>();
        List<GenericValue> assignments = EntityQuery.use(delegator).from("LoanRepairProposalAssignment").where("repairProposalId", repairProposalId).orderBy("-assignedDate", "-assignmentId").queryList();
        for (GenericValue assignment : assignments) {
            Map<String, Object> row = new LinkedHashMap<String, Object>();
            row.put("assignmentId", assignment.getString("assignmentId"));
            row.put("repairProposalId", assignment.getString("repairProposalId"));
            row.put("organizationPartyId", assignment.getString("organizationPartyId"));
            row.put("loanId", assignment.getString("loanId"));
            row.put("assignedToUserLoginId", assignment.getString("assignedToUserLoginId"));
            row.put("assignedByUserLoginId", assignment.getString("assignedByUserLoginId"));
            row.put("assignedDate", assignment.getTimestamp("assignedDate"));
            row.put("thruDate", assignment.getTimestamp("thruDate"));
            row.put("assignmentStatusId", assignment.getString("assignmentStatusId"));
            row.put("assignmentComment", assignment.getString("assignmentComment"));
            rows.add(row);
        }
        return rows;
    }

    private static List<Map<String, Object>> phase11CommentList(Delegator delegator, String repairProposalId) throws GenericEntityException {
        List<Map<String, Object>> rows = new ArrayList<Map<String, Object>>();
        List<GenericValue> comments = EntityQuery.use(delegator).from("LoanRepairProposalComment").where("repairProposalId", repairProposalId).orderBy("-createdDate", "-commentId").queryList();
        for (GenericValue comment : comments) {
            rows.add(phase10CommentData(comment));
        }
        return rows;
    }

    private static List<Map<String, Object>> phase11AuditTimeline(Delegator delegator, String repairProposalId) throws GenericEntityException {
        List<Map<String, Object>> rows = new ArrayList<Map<String, Object>>();
        List<GenericValue> audits = EntityQuery.use(delegator).from("LoanRepairProposalAudit").where("repairProposalId", repairProposalId).orderBy("createdDate", "repairProposalAuditId").queryList();
        for (GenericValue audit : audits) {
            Map<String, Object> row = new LinkedHashMap<String, Object>();
            row.put("timelineType", "AUDIT");
            row.put("repairProposalAuditId", audit.getString("repairProposalAuditId"));
            row.put("repairProposalId", audit.getString("repairProposalId"));
            row.put("auditEventTypeId", audit.getString("auditEventTypeId"));
            row.put("fromStatusId", audit.getString("fromStatusId"));
            row.put("toStatusId", audit.getString("toStatusId"));
            row.put("message", audit.getString("message"));
            row.put("payload", audit.getString("payload"));
            row.put("createdByUserLoginId", audit.getString("userLoginId"));
            row.put("createdDate", audit.getTimestamp("createdDate"));
            row.put("eventDate", audit.getTimestamp("createdDate"));
            rows.add(row);
        }
        return rows;
    }

    private static List<Map<String, Object>> phase11CommentTimeline(Delegator delegator, String repairProposalId) throws GenericEntityException {
        List<Map<String, Object>> rows = new ArrayList<Map<String, Object>>();
        List<GenericValue> comments = EntityQuery.use(delegator).from("LoanRepairProposalComment").where("repairProposalId", repairProposalId).orderBy("createdDate", "commentId").queryList();
        for (GenericValue comment : comments) {
            Map<String, Object> row = new LinkedHashMap<String, Object>();
            row.put("timelineType", "COMMENT");
            row.put("commentId", comment.getString("commentId"));
            row.put("repairProposalId", repairProposalId);
            row.put("message", comment.getString("commentText"));
            row.put("commentTypeEnumId", comment.getString("commentTypeEnumId"));
            row.put("createdByUserLoginId", comment.getString("createdByUserLoginId"));
            row.put("createdDate", comment.getTimestamp("createdDate"));
            row.put("eventDate", comment.getTimestamp("createdDate"));
            rows.add(row);
        }
        return rows;
    }

    private static List<Map<String, Object>> phase11AssignmentTimeline(Delegator delegator, String repairProposalId) throws GenericEntityException {
        List<Map<String, Object>> rows = new ArrayList<Map<String, Object>>();
        List<GenericValue> assignments = EntityQuery.use(delegator).from("LoanRepairProposalAssignment").where("repairProposalId", repairProposalId).orderBy("assignedDate", "assignmentId").queryList();
        for (GenericValue assignment : assignments) {
            Map<String, Object> row = new LinkedHashMap<String, Object>();
            row.put("timelineType", "ASSIGNMENT");
            row.put("assignmentId", assignment.getString("assignmentId"));
            row.put("repairProposalId", repairProposalId);
            row.put("message", "Assigned to " + assignment.getString("assignedToUserLoginId"));
            row.put("assignedToUserLoginId", assignment.getString("assignedToUserLoginId"));
            row.put("assignedByUserLoginId", assignment.getString("assignedByUserLoginId"));
            row.put("assignmentStatusId", assignment.getString("assignmentStatusId"));
            row.put("assignmentComment", assignment.getString("assignmentComment"));
            row.put("createdByUserLoginId", assignment.getString("assignedByUserLoginId"));
            row.put("createdDate", assignment.getTimestamp("assignedDate"));
            row.put("eventDate", assignment.getTimestamp("assignedDate"));
            rows.add(row);
            if (assignment.getTimestamp("thruDate") != null) {
                Map<String, Object> closed = new LinkedHashMap<String, Object>();
                closed.put("timelineType", "ASSIGNMENT_CLOSED");
                closed.put("assignmentId", assignment.getString("assignmentId"));
                closed.put("repairProposalId", repairProposalId);
                closed.put("message", "Assignment closed");
                closed.put("createdDate", assignment.getTimestamp("thruDate"));
                closed.put("eventDate", assignment.getTimestamp("thruDate"));
                rows.add(closed);
            }
        }
        return rows;
    }

    private static void phase11AddProposalMilestones(List<Map<String, Object>> timeline, GenericValue proposal) {
        phase11AddMilestone(timeline, "PROPOSAL_CREATED", "Proposal created", proposal.getTimestamp("createdDate"), proposal.getString("createdByUserLoginId"), proposal);
        phase11AddMilestone(timeline, "PROPOSAL_APPROVED", "Proposal approved", proposal.getTimestamp("approvedDate"), proposal.getString("approvedByUserLoginId"), proposal);
        phase11AddMilestone(timeline, "PROPOSAL_REJECTED", "Proposal rejected", proposal.getTimestamp("rejectedDate"), proposal.getString("rejectedByUserLoginId"), proposal);
        phase11AddMilestone(timeline, "DRY_RUN", "Dry-run completed", proposal.getTimestamp("dryRunDate"), proposal.getString("createdByUserLoginId"), proposal);
        phase11AddMilestone(timeline, "EXECUTION", "Execution completed", proposal.getTimestamp("executionDate"), proposal.getString("executedByUserLoginId"), proposal);
        phase11AddMilestone(timeline, "LAST_STATUS", "Last status changed", proposal.getTimestamp("lastStatusDate"), proposal.getString("createdByUserLoginId"), proposal);
    }

    private static void phase11AddMilestone(List<Map<String, Object>> timeline, String type, String message, Timestamp date, String userLoginId, GenericValue proposal) {
        if (date == null) return;
        Map<String, Object> row = new LinkedHashMap<String, Object>();
        row.put("timelineType", type);
        row.put("repairProposalId", proposal.getString("repairProposalId"));
        row.put("proposalStatusId", proposal.getString("proposalStatusId"));
        row.put("message", message);
        row.put("createdByUserLoginId", userLoginId);
        row.put("createdDate", date);
        row.put("eventDate", date);
        timeline.add(row);
    }

    private static void phase11SortTimeline(List<Map<String, Object>> timeline) {
        java.util.Collections.sort(timeline, new java.util.Comparator<Map<String, Object>>() {
            public int compare(Map<String, Object> a, Map<String, Object> b) {
                Timestamp at = (Timestamp) a.get("eventDate");
                Timestamp bt = (Timestamp) b.get("eventDate");
                if (at == null && bt == null) return 0;
                if (at == null) return 1;
                if (bt == null) return -1;
                return at.compareTo(bt);
            }
        });
    }

    private static Map<String, Object> phase11RelatedEntities(Delegator delegator, GenericValue proposal) throws GenericEntityException {
        Map<String, Object> data = new LinkedHashMap<String, Object>();
        String loanId = proposal.getString("loanId");
        String loanScheduleId = proposal.getString("loanScheduleId");
        String loanRepaymentId = proposal.getString("loanRepaymentId");
        String paymentId = proposal.getString("paymentId");
        data.put("loan", phase11ValueMap(UtilValidate.isNotEmpty(loanId) ? EntityQuery.use(delegator).from("PartyLoan").where("loanId", loanId).queryOne() : null));
        data.put("schedule", phase11ValueMap(UtilValidate.isNotEmpty(loanScheduleId) ? EntityQuery.use(delegator).from("PartyLoanSchedule").where("loanScheduleId", loanScheduleId).queryOne() : null));
        GenericValue repayment = UtilValidate.isNotEmpty(loanRepaymentId) ? EntityQuery.use(delegator).from("PartyLoanRepayment").where("loanRepaymentId", loanRepaymentId).queryOne() : null;
        data.put("repayment", phase11ValueMap(repayment));
        String resolvedPaymentId = firstNonEmpty(paymentId, repayment != null ? repayment.getString("paymentId") : null);
        data.put("payment", phase11ValueMap(UtilValidate.isNotEmpty(resolvedPaymentId) ? EntityQuery.use(delegator).from("Payment").where("paymentId", resolvedPaymentId).queryOne() : null));
        data.put("resolvedPaymentId", resolvedPaymentId);
        return data;
    }

    private static Map<String, Object> phase11FinanceTrace(Delegator delegator, GenericValue proposal) throws GenericEntityException {
        Map<String, Object> data = new LinkedHashMap<String, Object>();
        String loanRepaymentId = proposal.getString("loanRepaymentId");
        String paymentId = proposal.getString("paymentId");
        GenericValue repayment = UtilValidate.isNotEmpty(loanRepaymentId) ? EntityQuery.use(delegator).from("PartyLoanRepayment").where("loanRepaymentId", loanRepaymentId).queryOne() : null;
        if (UtilValidate.isEmpty(paymentId) && repayment != null) paymentId = repayment.getString("paymentId");
        data.put("loanRepaymentId", loanRepaymentId);
        data.put("paymentId", paymentId);
        data.put("repayment", phase11ValueMap(repayment));
        data.put("payment", phase11ValueMap(UtilValidate.isNotEmpty(paymentId) ? EntityQuery.use(delegator).from("Payment").where("paymentId", paymentId).queryOne() : null));

        List<Map<String, Object>> appsOut = new ArrayList<Map<String, Object>>();
        if (UtilValidate.isNotEmpty(paymentId)) {
            List<GenericValue> apps = EntityQuery.use(delegator).from("PaymentApplication").where("paymentId", paymentId).orderBy("paymentApplicationId").queryList();
            for (GenericValue app : apps) {
                Map<String, Object> appMap = phase11ValueMap(app);
                String invoiceId = app.getString("invoiceId");
                appMap.put("invoice", phase11ValueMap(UtilValidate.isNotEmpty(invoiceId) ? EntityQuery.use(delegator).from("Invoice").where("invoiceId", invoiceId).queryOne() : null));
                List<Map<String, Object>> items = new ArrayList<Map<String, Object>>();
                if (UtilValidate.isNotEmpty(invoiceId)) {
                    List<GenericValue> invoiceItems = EntityQuery.use(delegator).from("InvoiceItem").where("invoiceId", invoiceId).orderBy("invoiceItemSeqId").queryList();
                    for (GenericValue item : invoiceItems) items.add(phase11ValueMap(item));
                }
                appMap.put("invoiceItems", items);
                appsOut.add(appMap);
            }
        }
        data.put("paymentApplications", appsOut);
        data.put("paymentApplicationCount", Integer.valueOf(appsOut.size()));

        List<Map<String, Object>> transOut = new ArrayList<Map<String, Object>>();
        if (UtilValidate.isNotEmpty(paymentId)) {
            List<GenericValue> transList = EntityQuery.use(delegator).from("AcctgTrans").where("paymentId", paymentId).orderBy("acctgTransId").queryList();
            for (GenericValue trans : transList) {
                Map<String, Object> transMap = phase11ValueMap(trans);
                List<Map<String, Object>> entries = new ArrayList<Map<String, Object>>();
                List<GenericValue> entryList = EntityQuery.use(delegator).from("AcctgTransEntry").where("acctgTransId", trans.getString("acctgTransId")).orderBy("acctgTransEntrySeqId").queryList();
                for (GenericValue entry : entryList) entries.add(phase11ValueMap(entry));
                transMap.put("entries", entries);
                transMap.put("entryCount", Integer.valueOf(entries.size()));
                transOut.add(transMap);
            }
        }
        data.put("acctgTransList", transOut);
        data.put("acctgTransCount", Integer.valueOf(transOut.size()));
        return data;
    }

    private static Map<String, Object> phase11LatestReconciliation(Delegator delegator, GenericValue proposal) throws GenericEntityException {
        String loanId = proposal.getString("loanId");
        String organizationPartyId = proposal.getString("organizationPartyId");
        List<EntityCondition> conds = new ArrayList<EntityCondition>();
        if (UtilValidate.isNotEmpty(organizationPartyId)) conds.add(EntityCondition.makeCondition("organizationPartyId", organizationPartyId));
        if (UtilValidate.isNotEmpty(loanId)) conds.add(EntityCondition.makeCondition("loanId", loanId));
        if (conds.isEmpty()) return new LinkedHashMap<String, Object>();
        GenericValue run = EntityQuery.use(delegator).from("LoanReconciliationRun").where(EntityCondition.makeCondition(conds, EntityOperator.AND)).orderBy("-startedStamp", "-reconciliationRunId").queryFirst();
        Map<String, Object> data = phase11ValueMap(run);
        if (run != null) {
            List<Map<String, Object>> issuesOut = new ArrayList<Map<String, Object>>();
            List<GenericValue> issues = EntityQuery.use(delegator).from("LoanReconciliationIssue").where("reconciliationRunId", run.getString("reconciliationRunId")).orderBy("severityEnumId", "issueTypeEnumId").queryList();
            for (GenericValue issue : issues) issuesOut.add(phase11ValueMap(issue));
            data.put("issues", issuesOut);
            data.put("issueList", issuesOut);
            data.put("persistedIssueCount", Integer.valueOf(issuesOut.size()));
        }
        return data;
    }

    private static Map<String, Object> phase11ValueMap(GenericValue value) {
        Map<String, Object> data = new LinkedHashMap<String, Object>();
        if (value == null) return data;
        for (String fieldName : value.getAllKeys()) data.put(fieldName, value.get(fieldName));
        return data;
    }


}
