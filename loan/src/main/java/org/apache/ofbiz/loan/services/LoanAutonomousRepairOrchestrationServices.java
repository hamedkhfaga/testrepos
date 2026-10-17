package org.apache.ofbiz.loan.services;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.apache.ofbiz.base.util.Debug;
import org.apache.ofbiz.base.util.UtilValidate;
import org.apache.ofbiz.entity.GenericValue;
import org.apache.ofbiz.service.DispatchContext;
import org.apache.ofbiz.service.LocalDispatcher;
import org.apache.ofbiz.service.ServiceUtil;

/**
 * Phase 6H - Autonomous Repair Orchestration & Governance Enforcement.
 *
 * This service is deliberately additive. It does not change the already validated
 * repayment/payment/invoice/accounting repair behavior. All financial mutation is
 * delegated to repairLoanFinancialIntegrityChunkedV1 so the accepted 3-transaction
 * model remains preserved:
 *   PAYMENT_ACCTG_TRANS + INCOMING_PAYMENT + PAYMENT_APPL
 */
public final class LoanAutonomousRepairOrchestrationServices {
    public static final String MODULE = LoanAutonomousRepairOrchestrationServices.class.getName();

    private LoanAutonomousRepairOrchestrationServices() {}

    public static Map<String, Object> runAutonomousLoanRepairOrchestrationV1(DispatchContext dctx, Map<String, ?> context) {
        LocalDispatcher dispatcher = dctx.getDispatcher();
        GenericValue userLogin = (GenericValue) context.get("userLogin");

        String organizationPartyId = trimToNull((String) context.get("organizationPartyId"));
        String loanIdFilter = trimToNull((String) context.get("loanId"));
        String dryRun = normalizeFlagWithDefault((String) context.get("dryRun"), "Y");
        String persistFinalRuns = normalizeFlagWithDefault((String) context.get("persistFinalRuns"), "Y");
        String stopOnError = normalizeFlag((String) context.get("stopOnError"));
        String autoCreateAccounting = normalizeFlag((String) context.get("autoCreateAccounting"));
        String autoCreateFinAccountTrans = normalizeFlag((String) context.get("autoCreateFinAccountTrans"));
        String repairPaymentApplicationAmount = normalizeFlagWithDefault((String) context.get("repairPaymentApplicationAmount"), "Y");
        String repairAcctgEntryAmount = normalizeFlagWithDefault((String) context.get("repairAcctgEntryAmount"), "Y");
        int maxLoans = normalizePositiveInt(context.get("maxLoans"), 50, 1000);
        int maxRepairs = normalizePositiveInt(context.get("maxRepairs"), 20, 200);
        int maxRepaymentsPerLoan = normalizePositiveInt(context.get("maxRepaymentsPerLoan"), 10, 100);

        try {
            if (UtilValidate.isEmpty(organizationPartyId)) {
                return ServiceUtil.returnError("organizationPartyId is required");
            }

            Map<String, Object> discoveryCtx = new LinkedHashMap<String, Object>();
            discoveryCtx.put("organizationPartyId", organizationPartyId);
            discoveryCtx.put("maxLoans", Integer.valueOf(maxLoans));
            discoveryCtx.put("dryRun", "Y");
            discoveryCtx.put("persistRuns", "N");
            discoveryCtx.put("persistBatchRun", "N");
            discoveryCtx.put("strictDuplicateAcctg", "N");
            discoveryCtx.put("requireFinAccountTrans", "N");
            discoveryCtx.put("batchRunTypeId", "AUTO_REPAIR_DISCOVERY");
            discoveryCtx.put("userLogin", userLogin);
            if (UtilValidate.isNotEmpty(loanIdFilter)) {
                discoveryCtx.put("loanIds", loanIdFilter);
            }

            Map<String, Object> discoveryResp = UtilValidate.isNotEmpty(loanIdFilter)
                    ? dispatcher.runSync("runLoanReconciliationForLoanListV1", discoveryCtx)
                    : dispatcher.runSync("runLoanReconciliationAutomationV1", discoveryCtx);
            if (ServiceUtil.isError(discoveryResp)) {
                return ServiceUtil.returnError(ServiceUtil.getErrorMessage(discoveryResp));
            }

            Map<String, Object> discoveryData = extractPayload(discoveryResp);
            @SuppressWarnings("unchecked")
            List<Map<String, Object>> rows = discoveryData.get("results") instanceof List
                    ? (List<Map<String, Object>>) discoveryData.get("results")
                    : new ArrayList<Map<String, Object>>();

            List<Map<String, Object>> autoRepairResults = new ArrayList<Map<String, Object>>();
            List<Map<String, Object>> approvalQueue = new ArrayList<Map<String, Object>>();
            List<Map<String, Object>> retryQueue = new ArrayList<Map<String, Object>>();
            List<Map<String, Object>> postRepairReconciliationResults = new ArrayList<Map<String, Object>>();
            Map<String, Integer> decisionCounts = new LinkedHashMap<String, Integer>();

            int evaluatedLoanCount = 0;
            int autoRepairLoanCount = 0;
            int repairedIssueLoanCount = 0;
            int approvalQueuedCount = 0;
            int failedRepairCount = 0;
            int postRepairCleanCount = 0;

            for (Map<String, Object> row : rows) {
                String loanId = trimToNull(asString(row.get("loanId")));
                if (UtilValidate.isEmpty(loanId)) continue;
                if (getInt(row, "errorCount") <= 0 && getInt(row, "issueCount") <= 0) continue;
                if (autoRepairLoanCount + approvalQueuedCount >= maxRepairs) break;

                evaluatedLoanCount++;

                Map<String, Object> reconCtx = new LinkedHashMap<String, Object>();
                reconCtx.put("organizationPartyId", organizationPartyId);
                reconCtx.put("loanId", loanId);
                reconCtx.put("strictDuplicateAcctg", "N");
                reconCtx.put("requireFinAccountTrans", "N");
                reconCtx.put("userLogin", userLogin);

                Map<String, Object> detailResp = dispatcher.runSync("reconcileLoanAccountingV1", reconCtx);
                if (ServiceUtil.isError(detailResp)) {
                    failedRepairCount++;
                    retryQueue.add(buildQueueRow(loanId, null, "RECONCILIATION_DETAIL_FAILED", ServiceUtil.getErrorMessage(detailResp)));
                    if ("Y".equals(stopOnError)) break;
                    continue;
                }

                Map<String, Object> detailData = extractPayload(detailResp);
                @SuppressWarnings("unchecked")
                List<Map<String, Object>> issues = detailData.get("issues") instanceof List
                        ? (List<Map<String, Object>>) detailData.get("issues")
                        : new ArrayList<Map<String, Object>>();

                Decision decision = evaluateGovernance(dispatcher, organizationPartyId, loanId, issues, userLogin, decisionCounts);

                if (decision.approvalRequired || decision.blocked || !decision.autoRepairEligible) {
                    approvalQueuedCount += createApprovalQueueRows(dispatcher, organizationPartyId, loanId, issues,
                            autoCreateAccounting, autoCreateFinAccountTrans, userLogin, approvalQueue,
                            decision.blocked ? "BLOCKED" : "APPROVAL_QUEUE", decision.reason);
                    continue;
                }

                Map<String, Object> repairCtx = new LinkedHashMap<String, Object>();
                repairCtx.put("organizationPartyId", organizationPartyId);
                repairCtx.put("loanId", loanId);
                repairCtx.put("dryRun", dryRun);
                repairCtx.put("maxRepayments", Integer.valueOf(maxRepaymentsPerLoan));
                repairCtx.put("stopOnError", "N");
                repairCtx.put("runPaymentRepair", "Y");
                repairCtx.put("runPaymentApplicationRepair", "Y");
                repairCtx.put("runAccountingRepair", "Y");
                repairCtx.put("runAmountConsistencyRepair", "Y");
                repairCtx.put("autoCreateAccounting", autoCreateAccounting);
                repairCtx.put("autoCreateFinAccountTrans", autoCreateFinAccountTrans);
                repairCtx.put("repairPaymentApplicationAmount", repairPaymentApplicationAmount);
                repairCtx.put("repairAcctgEntryAmount", repairAcctgEntryAmount);
                repairCtx.put("userLogin", userLogin);

                Map<String, Object> repairResp = dispatcher.runSync("repairLoanFinancialIntegrityChunkedV1", repairCtx);
                Map<String, Object> repairRow = new LinkedHashMap<String, Object>();
                repairRow.put("loanId", loanId);
                repairRow.put("dryRun", dryRun);
                repairRow.put("repairResult", repairResp);
                autoRepairResults.add(repairRow);
                autoRepairLoanCount++;

                if (ServiceUtil.isError(repairResp)) {
                    failedRepairCount++;
                    retryQueue.add(buildQueueRow(loanId, null, "FAILED_REPAIR", ServiceUtil.getErrorMessage(repairResp)));
                    if ("Y".equals(stopOnError)) break;
                    continue;
                }

                Map<String, Object> postResp;
                if ("Y".equals(persistFinalRuns) && !"Y".equals(dryRun)) {
                    Map<String, Object> persistCtx = new LinkedHashMap<String, Object>();
                    persistCtx.put("organizationPartyId", organizationPartyId);
                    persistCtx.put("loanId", loanId);
                    persistCtx.put("strictDuplicateAcctg", "N");
                    persistCtx.put("requireFinAccountTrans", "N");
                    persistCtx.put("userLogin", userLogin);
                    postResp = dispatcher.runSync("persistLoanReconciliationRunV1", persistCtx);
                } else {
                    postResp = dispatcher.runSync("reconcileLoanAccountingV1", reconCtx);
                }
                postRepairReconciliationResults.add(postResp);
                Map<String, Object> postData = extractPayload(postResp);
                if (getInt(postData, "errorCount") == 0) {
                    postRepairCleanCount++;
                    repairedIssueLoanCount++;
                }
            }

            Map<String, Object> dashboardCtx = new LinkedHashMap<String, Object>();
            dashboardCtx.put("organizationPartyId", organizationPartyId);
            dashboardCtx.put("userLogin", userLogin);
            Map<String, Object> dashboardResp = dispatcher.runSync("getLoanRepairAutomationDashboardV1", dashboardCtx);

            String finalStatus;
            if (failedRepairCount > 0) finalStatus = "FAILED_REPAIR";
            else if (!approvalQueue.isEmpty()) finalStatus = "APPROVAL_QUEUE";
            else if (autoRepairLoanCount > 0 && postRepairCleanCount == autoRepairLoanCount) finalStatus = "AUTO_REPAIR_COMPLETE";
            else if (autoRepairLoanCount > 0) finalStatus = "AUTO_REPAIR_PARTIAL";
            else finalStatus = "NO_AUTO_REPAIR_ACTION";

            Map<String, Object> payload = new LinkedHashMap<String, Object>();
            payload.put("organizationPartyId", organizationPartyId);
            payload.put("loanId", loanIdFilter);
            payload.put("dryRun", dryRun);
            payload.put("finalStatus", finalStatus);
            payload.put("evaluatedLoanCount", Integer.valueOf(evaluatedLoanCount));
            payload.put("autoRepairLoanCount", Integer.valueOf(autoRepairLoanCount));
            payload.put("repairedIssueLoanCount", Integer.valueOf(repairedIssueLoanCount));
            payload.put("approvalQueuedCount", Integer.valueOf(approvalQueuedCount));
            payload.put("failedRepairCount", Integer.valueOf(failedRepairCount));
            payload.put("retryQueueCount", Integer.valueOf(retryQueue.size()));
            payload.put("decisionCounts", decisionCounts);
            payload.put("discovery", discoveryResp);
            payload.put("autoRepairResults", autoRepairResults);
            payload.put("approvalQueue", approvalQueue);
            payload.put("retryQueue", retryQueue);
            payload.put("postRepairReconciliationResults", postRepairReconciliationResults);
            payload.put("dashboard", dashboardResp);
            return buildSuccess("Autonomous loan repair orchestration completed", payload);
        } catch (Exception e) {
            Debug.logError(e, "Error in runAutonomousLoanRepairOrchestrationV1", MODULE);
            return ServiceUtil.returnError(e.getMessage());
        }
    }

    private static Decision evaluateGovernance(LocalDispatcher dispatcher, String organizationPartyId, String loanId,
            List<Map<String, Object>> issues, GenericValue userLogin, Map<String, Integer> decisionCounts) throws Exception {
        Decision decision = new Decision();
        if (issues.isEmpty()) {
            decision.approvalRequired = true;
            decision.reason = "No detailed issues found for issue loan";
            increment(decisionCounts, "NO_DETAILED_ISSUES");
            return decision;
        }
        for (Map<String, Object> issue : issues) {
            String issueType = trimToNull(asString(issue.get("issueType")));
            String severity = firstNonEmpty(trimToNull(asString(issue.get("severity"))), "ERROR");

            Map<String, Object> govCtx = new LinkedHashMap<String, Object>();
            govCtx.put("organizationPartyId", organizationPartyId);
            govCtx.put("loanId", loanId);
            govCtx.put("issueTypeEnumId", issueType);
            govCtx.put("severityEnumId", severity);
            govCtx.put("userLogin", userLogin);
            Map<String, Object> govResp = dispatcher.runSync("getRepairEligibilityForIssueV1", govCtx);
            if (ServiceUtil.isError(govResp)) {
                decision.approvalRequired = true;
                decision.reason = ServiceUtil.getErrorMessage(govResp);
                increment(decisionCounts, "GOVERNANCE_ERROR");
                continue;
            }

            String policyFound = normalizeFlag(asString(govResp.get("policyFound")));
            String autoRepairAllowed = normalizeFlag(asString(govResp.get("autoRepairAllowed")));
            String manualApprovalRequired = normalizeFlag(asString(govResp.get("manualApprovalRequired")));
            String blockBatchRepair = normalizeFlag(asString(govResp.get("blockBatchRepair")));
            String riskLevelEnumId = trimToNull(asString(govResp.get("riskLevelEnumId")));

            if ("Y".equals(blockBatchRepair) || "N".equals(policyFound)) {
                decision.blocked = true;
                decision.reason = "Governance blocked or no active policy for issueType=" + issueType;
                increment(decisionCounts, "BLOCKED");
            } else if ("Y".equals(manualApprovalRequired) || isHighRisk(riskLevelEnumId)) {
                decision.approvalRequired = true;
                decision.reason = "Manual approval required or high-risk issueType=" + issueType;
                increment(decisionCounts, "APPROVAL_REQUIRED");
            } else if ("Y".equals(autoRepairAllowed)) {
                decision.autoRepairEligible = true;
                increment(decisionCounts, "AUTO_REPAIR_ELIGIBLE");
            } else {
                decision.approvalRequired = true;
                decision.reason = "Policy does not allow automatic repair for issueType=" + issueType;
                increment(decisionCounts, "NO_AUTO_POLICY");
            }
        }
        return decision;
    }

    private static int createApprovalQueueRows(LocalDispatcher dispatcher, String organizationPartyId, String loanId,
            List<Map<String, Object>> issues, String autoCreateAccounting, String autoCreateFinAccountTrans,
            GenericValue userLogin, List<Map<String, Object>> approvalQueue, String decision, String reason) throws Exception {
        int count = 0;
        if (issues.isEmpty()) {
            approvalQueue.add(buildQueueRow(loanId, null, decision, reason));
            return 1;
        }
        for (Map<String, Object> issue : issues) {
            Map<String, Object> proposalCtx = new LinkedHashMap<String, Object>();
            proposalCtx.put("organizationPartyId", organizationPartyId);
            proposalCtx.put("loanId", loanId);
            proposalCtx.put("loanScheduleId", asString(issue.get("loanScheduleId")));
            proposalCtx.put("loanRepaymentId", asString(issue.get("loanRepaymentId")));
            proposalCtx.put("paymentId", asString(issue.get("paymentId")));
            proposalCtx.put("acctgTransId", asString(issue.get("acctgTransId")));
            proposalCtx.put("issueTypeEnumId", asString(issue.get("issueType")));
            proposalCtx.put("severityEnumId", firstNonEmpty(asString(issue.get("severity")), "ERROR"));
            proposalCtx.put("repairServiceName", "repairLoanFinancialIntegrityChunkedV1");
            proposalCtx.put("autoCreateAccounting", autoCreateAccounting);
            proposalCtx.put("autoCreateFinAccountTrans", autoCreateFinAccountTrans);
            proposalCtx.put("userLogin", userLogin);
            Map<String, Object> proposalResp = dispatcher.runSync("createLoanRepairProposalV1", proposalCtx);

            Map<String, Object> queueRow = buildQueueRow(loanId, asString(issue.get("loanRepaymentId")), decision, reason);
            queueRow.put("issueType", asString(issue.get("issueType")));
            queueRow.put("proposalResult", proposalResp);
            approvalQueue.add(queueRow);
            count++;
        }
        return count;
    }

    private static Map<String, Object> extractPayload(Map<String, Object> serviceResp) {
        Object dataObj = serviceResp.get("data");
        if (dataObj instanceof Map) {
            @SuppressWarnings("unchecked")
            Map<String, Object> dataMap = (Map<String, Object>) dataObj;
            return dataMap;
        }
        return serviceResp;
    }

    private static int getInt(Map<String, Object> map, String key) {
        if (map == null) return 0;
        Object obj = map.get(key);
        if (obj instanceof Number) return ((Number) obj).intValue();
        if (obj != null) {
            try { return Integer.parseInt(obj.toString()); } catch (Exception ignored) {}
        }
        return 0;
    }

    private static int normalizePositiveInt(Object obj, int defaultValue, int hardMax) {
        int value = defaultValue;
        if (obj instanceof Number) {
            value = ((Number) obj).intValue();
        } else if (obj != null && UtilValidate.isNotEmpty(obj.toString())) {
            try { value = Integer.parseInt(obj.toString()); } catch (Exception ignored) {}
        }
        if (value <= 0) value = defaultValue;
        return Math.min(value, hardMax);
    }

    private static boolean isHighRisk(String riskLevelEnumId) {
        return "RECON_RISK_HIGH".equals(riskLevelEnumId) || "RECON_RISK_CRITICAL".equals(riskLevelEnumId);
    }

    private static void increment(Map<String, Integer> counts, String key) {
        Integer current = counts.get(key);
        counts.put(key, Integer.valueOf(current == null ? 1 : current.intValue() + 1));
    }

    private static Map<String, Object> buildQueueRow(String loanId, String loanRepaymentId, String status, String reason) {
        Map<String, Object> row = new LinkedHashMap<String, Object>();
        row.put("loanId", loanId);
        row.put("loanRepaymentId", loanRepaymentId);
        row.put("status", status);
        row.put("reason", reason);
        return row;
    }

    private static String normalizeFlag(String value) {
        return "Y".equalsIgnoreCase(value) ? "Y" : "N";
    }

    private static String normalizeFlagWithDefault(String value, String defaultValue) {
        if (UtilValidate.isEmpty(value)) return defaultValue;
        return normalizeFlag(value);
    }

    private static String firstNonEmpty(String a, String b) {
        return UtilValidate.isNotEmpty(a) ? a : b;
    }

    private static String trimToNull(String value) {
        if (value == null) return null;
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    private static String asString(Object obj) {
        if (obj == null) return null;
        String s = obj.toString();
        return s.trim().isEmpty() ? null : s;
    }

    private static Map<String, Object> buildSuccess(String message, Map<String, Object> payload) {
        Map<String, Object> result = ServiceUtil.returnSuccess(message);
        result.putAll(payload);
        result.put("data", new LinkedHashMap<String, Object>(payload));
        result.put("legacyData", new LinkedHashMap<String, Object>(payload));
        return result;
    }

    private static final class Decision {
        boolean autoRepairEligible;
        boolean approvalRequired;
        boolean blocked;
        String reason;
    }
}
