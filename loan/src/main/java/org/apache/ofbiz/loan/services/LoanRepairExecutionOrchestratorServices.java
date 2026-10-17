package org.apache.ofbiz.loan.services;

import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.apache.ofbiz.base.util.Debug;
import org.apache.ofbiz.base.util.UtilDateTime;
import org.apache.ofbiz.base.util.UtilValidate;
import org.apache.ofbiz.entity.Delegator;
import org.apache.ofbiz.entity.GenericValue;
import org.apache.ofbiz.entity.condition.EntityCondition;
import org.apache.ofbiz.entity.condition.EntityOperator;
import org.apache.ofbiz.entity.util.EntityQuery;
import org.apache.ofbiz.service.DispatchContext;
import org.apache.ofbiz.service.LocalDispatcher;
import org.apache.ofbiz.service.ServiceUtil;

/**
 * Phase 6I - Approval Execution + Retry Orchestration.
 *
 * Additive controller around the already validated repair approval and chunked repair services.
 * This class does not create accounting entries directly and does not alter the 3-transaction model.
 * It delegates execution to executeApprovedLoanRepairProposalV1 and re-runs reconciliation after execution.
 */
public final class LoanRepairExecutionOrchestratorServices {
    public static final String MODULE = LoanRepairExecutionOrchestratorServices.class.getName();

    private static final String STATUS_APPROVED = "LRP_APPROVED";
    private static final String STATUS_EXEC_FAILED = "LRP_EXEC_FAILED";
    private static final String DRY_PASSED = "LRP_DRY_PASSED";

    private LoanRepairExecutionOrchestratorServices() {}

    /**
     * Executes approved repair proposals in small batches.
     * Safe defaults: dryRun=Y, maxProposals=10, stopOnError=N.
     */
    public static Map<String, Object> executeApprovedLoanRepairBatchV1(DispatchContext dctx, Map<String, ?> context) {
        Delegator delegator = dctx.getDelegator();
        LocalDispatcher dispatcher = dctx.getDispatcher();
        GenericValue userLogin = (GenericValue) context.get("userLogin");

        String organizationPartyId = trimToNull((String) context.get("organizationPartyId"));
        String repairProposalId = trimToNull((String) context.get("repairProposalId"));
        String loanId = trimToNull((String) context.get("loanId"));
        String dryRun = normalizeFlagDefaultY((String) context.get("dryRun"));
        String stopOnError = normalizeFlag((String) context.get("stopOnError"));
        String runDryRunBeforeExecute = normalizeFlagDefaultY((String) context.get("runDryRunBeforeExecute"));
        String rerunReconciliation = normalizeFlagDefaultY((String) context.get("rerunReconciliation"));
        int maxProposals = normalizeMax(firstNonNull(context.get("maxBatchSize"), context.get("maxProposals")), 10, 100);

        int queuedCount = 0;
        int processedCount = 0;
        int dryRunPassedCount = 0;
        int executedCount = 0;
        int failedCount = 0;
        int skippedCount = 0;
        int postRepairCleanCount = 0;
        int postRepairIssueCount = 0;

        List<Map<String, Object>> proposalResults = new ArrayList<Map<String, Object>>();
        Timestamp startedStamp = UtilDateTime.nowTimestamp();

        try {
            if (UtilValidate.isEmpty(organizationPartyId) && UtilValidate.isNotEmpty(repairProposalId)) {
                GenericValue selectedProposal = EntityQuery.use(delegator)
                        .from("LoanRepairProposal")
                        .where("repairProposalId", repairProposalId)
                        .queryOne();
                if (selectedProposal != null) {
                    organizationPartyId = trimToNull(selectedProposal.getString("organizationPartyId"));
                }
            }
            if (UtilValidate.isEmpty(organizationPartyId)) {
                return ServiceUtil.returnError("organizationPartyId is required");
            }

            List<GenericValue> proposals = findProposals(delegator, organizationPartyId, repairProposalId, loanId, STATUS_APPROVED, maxProposals);
            queuedCount = proposals.size();

            for (GenericValue proposal : proposals) {
                String currentProposalId = proposal.getString("repairProposalId");
                String currentLoanId = proposal.getString("loanId");
                Map<String, Object> row = new LinkedHashMap<String, Object>();
                row.put("repairProposalId", currentProposalId);
                row.put("loanId", currentLoanId);
                row.put("organizationPartyId", organizationPartyId);
                row.put("initialProposalStatusId", proposal.getString("proposalStatusId"));
                row.put("dryRun", dryRun);

                try {
                    if ("Y".equals(dryRun)) {
                        row.put("status", "DRY_RUN");
                        row.put("reason", "Would execute approved repair proposal");
                        skippedCount++;
                        proposalResults.add(row);
                        continue;
                    }

                    if ("Y".equals(runDryRunBeforeExecute) && !DRY_PASSED.equals(proposal.getString("dryRunStatusId"))) {
                        Map<String, Object> dryCtx = new LinkedHashMap<String, Object>();
                        dryCtx.put("repairProposalId", currentProposalId);
                        enrichProposalExecutionContext(dryCtx, proposal, context, organizationPartyId);
                        dryCtx.put("userLogin", userLogin);
                        Map<String, Object> dryResp = dispatcher.runSync("dryRunLoanRepairProposalV1", dryCtx);
                        row.put("dryRunResult", dryResp);
                        if (ServiceUtil.isError(dryResp)) {
                            failedCount++;
                            row.put("status", "DRY_RUN_FAILED");
                            row.put("reason", ServiceUtil.getErrorMessage(dryResp));
                            proposalResults.add(row);
                            if ("Y".equals(stopOnError)) break;
                            continue;
                        }
                        dryRunPassedCount++;
                    }

                    Map<String, Object> execCtx = new LinkedHashMap<String, Object>();
                    execCtx.put("repairProposalId", currentProposalId);
                    enrichProposalExecutionContext(execCtx, proposal, context, organizationPartyId);
                    execCtx.put("userLogin", userLogin);
                    Map<String, Object> execResp = dispatcher.runSync("executeApprovedLoanRepairProposalV1", execCtx);
                    row.put("executionResult", execResp);
                    processedCount++;

                    if (ServiceUtil.isError(execResp)) {
                        failedCount++;
                        row.put("status", "EXEC_FAILED");
                        row.put("reason", ServiceUtil.getErrorMessage(execResp));

                        Map<String, Object> retryCtx = new LinkedHashMap<String, Object>();
                        retryCtx.put("organizationPartyId", organizationPartyId);
                        retryCtx.put("repairProposalId", currentProposalId);
                        retryCtx.put("loanId", currentLoanId);
                        retryCtx.put("failureReason", ServiceUtil.getErrorMessage(execResp));
                        retryCtx.put("userLogin", userLogin);
                        row.put("retryQueueResult", dispatcher.runSync("enqueueLoanRepairRetryV1", retryCtx));
                        proposalResults.add(row);
                        if ("Y".equals(stopOnError)) break;
                        continue;
                    }

                    executedCount++;
                    row.put("status", "EXECUTED");
                    row.put("reason", "Approved proposal executed successfully");

                    if ("Y".equals(rerunReconciliation) && UtilValidate.isNotEmpty(currentLoanId)) {
                        Map<String, Object> reconCtx = new LinkedHashMap<String, Object>();
                        reconCtx.put("organizationPartyId", organizationPartyId);
                        reconCtx.put("loanId", currentLoanId);
                        reconCtx.put("strictDuplicateAcctg", "N");
                        reconCtx.put("requireFinAccountTrans", "N");
                        reconCtx.put("userLogin", userLogin);
                        Map<String, Object> recon = dispatcher.runSync("reconcileLoanAccountingV1", reconCtx);
                        row.put("postRepairReconciliation", recon);
                        int errors = getInt(extractPayload(recon), "errorCount");
                        if (errors == 0) postRepairCleanCount++; else postRepairIssueCount++;
                    }


                    Map<String, Object> auditCtx = new LinkedHashMap<String, Object>();
                    auditCtx.put("organizationPartyId", organizationPartyId);
                    auditCtx.put("repairProposalId", currentProposalId);
                    auditCtx.put("loanId", currentLoanId);
                    auditCtx.put("executionStatusId", row.get("status"));
                    auditCtx.put("repairServiceName", proposal.getString("repairServiceName"));
                    auditCtx.put("finalStatus", row.get("status"));
                    auditCtx.put("resultPayload", row);
                    auditCtx.put("userLogin", userLogin);
                    Map<String, Object> auditResp = dispatcher.runSync("persistLoanRepairExecutionAuditV1", auditCtx);
                    row.put("executionAuditResult", auditResp);
                    proposalResults.add(row);
                } catch (Exception rowEx) {
                    Debug.logError(rowEx, "Phase 6I approved proposal execution failed for proposal " + currentProposalId, MODULE);
                    failedCount++;
                    row.put("status", "ERROR");
                    row.put("reason", rowEx.getMessage());
                    proposalResults.add(row);
                    if ("Y".equals(stopOnError)) break;
                }
            }

            Map<String, Object> payload = new LinkedHashMap<String, Object>();
            payload.put("organizationPartyId", organizationPartyId);
            payload.put("repairProposalId", repairProposalId);
            payload.put("loanId", loanId);
            payload.put("dryRun", dryRun);
            payload.put("stopOnError", stopOnError);
            payload.put("runDryRunBeforeExecute", runDryRunBeforeExecute);
            payload.put("rerunReconciliation", rerunReconciliation);
            payload.put("maxProposals", Integer.valueOf(maxProposals));
            payload.put("maxBatchSize", Integer.valueOf(maxProposals));
            payload.put("queuedCount", Integer.valueOf(queuedCount));
            payload.put("processedCount", Integer.valueOf(processedCount));
            payload.put("dryRunPassedCount", Integer.valueOf(dryRunPassedCount));
            payload.put("executedCount", Integer.valueOf(executedCount));
            payload.put("failedCount", Integer.valueOf(failedCount));
            payload.put("skippedCount", Integer.valueOf(skippedCount));
            payload.put("postRepairCleanCount", Integer.valueOf(postRepairCleanCount));
            payload.put("postRepairIssueCount", Integer.valueOf(postRepairIssueCount));
            payload.put("startedStamp", startedStamp);
            payload.put("finishedStamp", UtilDateTime.nowTimestamp());
            payload.put("proposalResults", proposalResults);
            payload.put("finalStatus", "Y".equals(dryRun) ? "DRY_RUN" : failedCount == 0 ? "EXECUTED" : executedCount > 0 ? "PARTIAL" : "FAILED");
            return buildSuccess("Approved loan repair proposal batch execution completed", payload);
        } catch (Exception e) {
            Debug.logError(e, "Error in executeApprovedLoanRepairBatchV1", MODULE);
            return ServiceUtil.returnError(e.getMessage());
        }
    }

    /**
     * Retries failed proposals by moving them back to approved state and delegating to the approved execution batch.
     * Safe default dryRun=Y; with dryRun=N, only proposals in LRP_EXEC_FAILED are retried.
     */
    public static Map<String, Object> retryFailedLoanRepairProposalsV1(DispatchContext dctx, Map<String, ?> context) {
        Delegator delegator = dctx.getDelegator();
        GenericValue userLogin = (GenericValue) context.get("userLogin");

        String organizationPartyId = trimToNull((String) context.get("organizationPartyId"));
        String repairProposalId = trimToNull((String) context.get("repairProposalId"));
        String loanId = trimToNull((String) context.get("loanId"));
        String dryRun = normalizeFlagDefaultY((String) context.get("dryRun"));
        int maxProposals = normalizeMax(firstNonNull(context.get("maxBatchSize"), context.get("maxProposals")), 10, 100);

        List<Map<String, Object>> retryRows = new ArrayList<Map<String, Object>>();
        int queuedCount = 0;
        int markedApprovedCount = 0;
        int skippedCount = 0;

        try {
            if (UtilValidate.isEmpty(organizationPartyId) && UtilValidate.isNotEmpty(repairProposalId)) {
                GenericValue selectedProposal = EntityQuery.use(delegator)
                        .from("LoanRepairProposal")
                        .where("repairProposalId", repairProposalId)
                        .queryOne();
                if (selectedProposal != null) {
                    organizationPartyId = trimToNull(selectedProposal.getString("organizationPartyId"));
                }
            }
            if (UtilValidate.isEmpty(organizationPartyId)) {
                return ServiceUtil.returnError("organizationPartyId is required");
            }

            List<GenericValue> failed = findProposals(delegator, organizationPartyId, repairProposalId, loanId, STATUS_EXEC_FAILED, maxProposals);
            queuedCount = failed.size();
            for (GenericValue proposal : failed) {
                Map<String, Object> row = new LinkedHashMap<String, Object>();
                row.put("repairProposalId", proposal.getString("repairProposalId"));
                row.put("loanId", proposal.getString("loanId"));
                row.put("initialProposalStatusId", proposal.getString("proposalStatusId"));
                row.put("dryRun", dryRun);

                if ("Y".equals(dryRun)) {
                    skippedCount++;
                    row.put("status", "DRY_RUN");
                    row.put("reason", "Would mark failed proposal approved for retry");
                    retryRows.add(row);
                    continue;
                }

                proposal.set("proposalStatusId", STATUS_APPROVED);
                proposal.set("executionStatusId", null);
                proposal.set("lastStatusDate", UtilDateTime.nowTimestamp());
                if (userLogin != null) proposal.set("approvedByUserLoginId", userLogin.getString("userLoginId"));
                proposal.store();
                markedApprovedCount++;
                row.put("status", "MARKED_APPROVED_FOR_RETRY");
                row.put("reason", "Failed proposal moved back to approved status for retry execution");
                retryRows.add(row);
            }

            Map<String, Object> nextCtx = new LinkedHashMap<String, Object>();
            nextCtx.putAll(context);
            nextCtx.put("dryRun", dryRun);
            nextCtx.put("userLogin", userLogin);
            Map<String, Object> executionResult = executeApprovedLoanRepairBatchV1(dctx, nextCtx);

            Map<String, Object> payload = new LinkedHashMap<String, Object>();
            payload.put("organizationPartyId", organizationPartyId);
            payload.put("repairProposalId", repairProposalId);
            payload.put("loanId", loanId);
            payload.put("dryRun", dryRun);
            payload.put("maxProposals", Integer.valueOf(maxProposals));
            payload.put("maxBatchSize", Integer.valueOf(maxProposals));
            payload.put("queuedCount", Integer.valueOf(queuedCount));
            payload.put("markedApprovedCount", Integer.valueOf(markedApprovedCount));
            payload.put("skippedCount", Integer.valueOf(skippedCount));
            payload.put("retryRows", retryRows);
            payload.put("executionResult", executionResult);
            payload.put("finalStatus", ServiceUtil.isError(executionResult) ? "FAILED" : "Y".equals(dryRun) ? "DRY_RUN" : "RETRIED");
            return buildSuccess("Failed loan repair proposal retry orchestration completed", payload);
        } catch (Exception e) {
            Debug.logError(e, "Error in retryFailedLoanRepairProposalsV1", MODULE);
            return ServiceUtil.returnError(e.getMessage());
        }
    }

    /**
     * Carries proposal-level identifiers into the delegated approval execution service.
     * This prevents broad repair execution when a proposal was created for a specific repayment.
     */
    private static void enrichProposalExecutionContext(Map<String, Object> target, GenericValue proposal, Map<String, ?> originalContext, String organizationPartyId) {
        putIfNotEmpty(target, "organizationPartyId", firstNonEmpty(organizationPartyId, proposal.getString("organizationPartyId")));
        putIfNotEmpty(target, "loanId", proposal.getString("loanId"));
        putIfNotEmpty(target, "loanScheduleId", proposal.getString("loanScheduleId"));
        putIfNotEmpty(target, "loanRepaymentId", proposal.getString("loanRepaymentId"));
        putIfNotEmpty(target, "paymentId", proposal.getString("paymentId"));
        putIfNotEmpty(target, "acctgTransId", proposal.getString("acctgTransId"));

        // Keep chunked repair scoped to the proposal row unless caller explicitly overrides.
        putDefaultIfAbsent(target, originalContext, "maxRepayments", Integer.valueOf(1));
        putDefaultIfAbsent(target, originalContext, "runPaymentRepair", "Y");
        putDefaultIfAbsent(target, originalContext, "runPaymentApplicationRepair", "Y");
        putDefaultIfAbsent(target, originalContext, "runAccountingRepair", "Y");
        putDefaultIfAbsent(target, originalContext, "runAmountConsistencyRepair", "Y");
        putDefaultIfAbsent(target, originalContext, "autoCreateAccounting", "N");
        putDefaultIfAbsent(target, originalContext, "autoCreateFinAccountTrans", "N");
        putDefaultIfAbsent(target, originalContext, "repairPaymentApplicationAmount", "Y");
        putDefaultIfAbsent(target, originalContext, "repairAcctgEntryAmount", "Y");
        putDefaultIfAbsent(target, originalContext, "linkDuplicatePaymentIfSafe", "Y");
        putDefaultIfAbsent(target, originalContext, "requirePaymentApplication", "Y");
        putDefaultIfAbsent(target, originalContext, "requireAccounting", "Y");

        copyOriginalIfPresent(target, originalContext, "paymentMethodId");
        copyOriginalIfPresent(target, originalContext, "paymentMethodTypeId");
        copyOriginalIfPresent(target, originalContext, "paymentTypeId");
    }

    private static void putDefaultIfAbsent(Map<String, Object> target, Map<String, ?> source, String key, Object defaultValue) {
        if (source != null && source.get(key) != null) {
            target.put(key, source.get(key));
        } else if (!target.containsKey(key)) {
            target.put(key, defaultValue);
        }
    }

    private static void copyOriginalIfPresent(Map<String, Object> target, Map<String, ?> source, String key) {
        if (source != null && source.get(key) != null) {
            target.put(key, source.get(key));
        }
    }

    private static void putIfNotEmpty(Map<String, Object> target, String key, String value) {
        String trimmed = trimToNull(value);
        if (trimmed != null) target.put(key, trimmed);
    }

    private static Object firstNonNull(Object first, Object second) {
        return first != null ? first : second;
    }

    private static String firstNonEmpty(String first, String second) {
        return UtilValidate.isNotEmpty(first) ? first : trimToNull(second);
    }

    private static List<GenericValue> findProposals(Delegator delegator, String organizationPartyId, String repairProposalId,
            String loanId, String statusId, int maxRows) throws Exception {
        List<EntityCondition> conditions = new ArrayList<EntityCondition>();
        conditions.add(EntityCondition.makeCondition("organizationPartyId", organizationPartyId));
        conditions.add(EntityCondition.makeCondition("proposalStatusId", statusId));
        if (UtilValidate.isNotEmpty(repairProposalId)) conditions.add(EntityCondition.makeCondition("repairProposalId", repairProposalId));
        if (UtilValidate.isNotEmpty(loanId)) conditions.add(EntityCondition.makeCondition("loanId", loanId));
        return EntityQuery.use(delegator)
                .from("LoanRepairProposal")
                .where(EntityCondition.makeCondition(conditions, EntityOperator.AND))
                .orderBy("repairProposalId")
                .maxRows(maxRows)
                .queryList();
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> extractPayload(Map<String, Object> resp) {
        if (resp == null) return new LinkedHashMap<String, Object>();
        Object data = resp.get("data");
        if (data instanceof Map) return (Map<String, Object>) data;
        return resp;
    }

    private static int getInt(Map<String, Object> map, String key) {
        if (map == null || !map.containsKey(key) || map.get(key) == null) return 0;
        Object value = map.get(key);
        if (value instanceof Number) return ((Number) value).intValue();
        try { return Integer.parseInt(value.toString()); } catch (Exception e) { return 0; }
    }

    private static int normalizeMax(Object value, int defaultValue, int hardMax) {
        int parsed = defaultValue;
        if (value instanceof Number) parsed = ((Number) value).intValue();
        else if (value != null) {
            try { parsed = Integer.parseInt(value.toString()); } catch (Exception e) { parsed = defaultValue; }
        }
        if (parsed <= 0) parsed = defaultValue;
        return Math.min(parsed, hardMax);
    }

    private static String trimToNull(String value) {
        if (value == null) return null;
        String trimmed = value.trim();
        return trimmed.length() == 0 ? null : trimmed;
    }

    private static String normalizeFlag(String value) {
        return "Y".equalsIgnoreCase(trimToNull(value)) ? "Y" : "N";
    }

    private static String normalizeFlagDefaultY(String value) {
        String trimmed = trimToNull(value);
        if (trimmed == null) return "Y";
        return "Y".equalsIgnoreCase(trimmed) ? "Y" : "N";
    }

    private static Map<String, Object> buildSuccess(String message, Map<String, Object> payload) {
        Map<String, Object> result = ServiceUtil.returnSuccess(message);
        result.putAll(payload);
        result.put("data", new LinkedHashMap<String, Object>(payload));
        result.put("legacyData", new LinkedHashMap<String, Object>(payload));
        return result;
    }
}
