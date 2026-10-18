package org.apache.ofbiz.loan.services;

import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.Arrays;
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
 * Phase 6A automation services for loan reconciliation.
 *
 * Scope: automation only. These services run reconciliation and optionally persist
 * reconciliation run headers/issues through persistLoanReconciliationRunV1. They do not
 * repair loans, payments, invoices, accounting, schedules, or repayments.
 */
public final class LoanReconciliationAutomationServices {
    public static final String MODULE = LoanReconciliationAutomationServices.class.getName();

    private static final int DEFAULT_MAX_LOANS = 200;
    private static final int HARD_MAX_LOANS = 1000;

    private LoanReconciliationAutomationServices() {}

    public static Map<String, Object> runDailyLoanReconciliationBatchV1(DispatchContext dctx, Map<String, ?> context) {
        Map<String, Object> next = new LinkedHashMap<String, Object>();
        next.putAll(context);
        if (UtilValidate.isEmpty((String) context.get("batchRunTypeId"))) {
            next.put("batchRunTypeId", "DAILY_LOAN_RECON");
        }
        if (UtilValidate.isEmpty((String) context.get("persistBatchRun"))) {
            next.put("persistBatchRun", "Y");
        }
        if (UtilValidate.isEmpty((String) context.get("dryRun"))) {
            next.put("dryRun", "Y");
        }
        return runLoanReconciliationForOrgV1(dctx, next);
    }

    public static Map<String, Object> runLoanReconciliationForOrgV1(DispatchContext dctx, Map<String, ?> context) {
        Delegator delegator = dctx.getDelegator();

        String organizationPartyId = trimToNull((String) context.get("organizationPartyId"));
        String loanStatusId = trimToNull((String) context.get("loanStatusId"));
        int maxLoans = normalizeMaxLoans(context.get("maxLoans"));

        try {
            if (UtilValidate.isEmpty(organizationPartyId)) {
                return ServiceUtil.returnError("organizationPartyId is required");
            }

            List<EntityCondition> conditions = new ArrayList<EntityCondition>();
            conditions.add(EntityCondition.makeCondition("organizationPartyId", organizationPartyId));
            if (UtilValidate.isNotEmpty(loanStatusId)) {
                conditions.add(EntityCondition.makeCondition("loanStatusId", loanStatusId));
            }

            List<GenericValue> loanRows = EntityQuery.use(delegator)
                    .from("PartyLoan")
                    .where(EntityCondition.makeCondition(conditions, EntityOperator.AND))
                    .orderBy("loanId")
                    .maxRows(maxLoans)
                    .queryList();

            List<String> loanIds = new ArrayList<String>();
            for (GenericValue loan : loanRows) {
                loanIds.add(loan.getString("loanId"));
            }

            Map<String, Object> next = new LinkedHashMap<String, Object>();
            next.putAll(context);
            next.put("loanIds", join(loanIds));
            next.put("organizationPartyId", organizationPartyId);
            next.put("resolvedLoanCount", Integer.valueOf(loanIds.size()));
            return runLoanReconciliationForLoanListV1(dctx, next);
        } catch (Exception e) {
            Debug.logError(e, "Error resolving org loan list for Phase 6A reconciliation automation", MODULE);
            return ServiceUtil.returnError(e.getMessage());
        }
    }

    public static Map<String, Object> runLoanReconciliationForLoanListV1(DispatchContext dctx, Map<String, ?> context) {
        Delegator delegator = dctx.getDelegator();
        LocalDispatcher dispatcher = dctx.getDispatcher();
        GenericValue userLogin = (GenericValue) context.get("userLogin");

        String organizationPartyId = trimToNull((String) context.get("organizationPartyId"));
        String loanIdsRaw = trimToNull((String) context.get("loanIds"));
        String dryRun = normalizeFlag((String) context.get("dryRun"));
        String persistRuns = normalizeFlag((String) context.get("persistRuns"));
        String persistBatchRun = normalizeFlagWithDefault((String) context.get("persistBatchRun"), "Y");
        String strictDuplicateAcctg = normalizeFlag((String) context.get("strictDuplicateAcctg"));
        String requireFinAccountTrans = normalizeFlag((String) context.get("requireFinAccountTrans"));
        String batchRunTypeId = firstNonEmpty(trimToNull((String) context.get("batchRunTypeId")), "MANUAL_LOAN_RECON");
        int maxLoans = normalizeMaxLoans(context.get("maxLoans"));

        Timestamp startedStamp = UtilDateTime.nowTimestamp();
        String batchRunId = null;

        int requestedLoanCount = 0;
        int checkedLoanCount = 0;
        int successCount = 0;
        int cleanCount = 0;
        int issueCount = 0;
        int errorCount = 0;
        int persistedRunCount = 0;
        int skippedCount = 0;
        int totalReconciliationIssueCount = 0;

        List<Map<String, Object>> resultRows = new ArrayList<Map<String, Object>>();

        try {
            if (UtilValidate.isEmpty(organizationPartyId)) {
                return ServiceUtil.returnError("organizationPartyId is required");
            }
            List<String> loanIds = parseLoanIds(loanIdsRaw, maxLoans);
            requestedLoanCount = loanIds.size();
            if (loanIds.isEmpty()) {
                return ServiceUtil.returnError("loanIds is required for runLoanReconciliationForLoanListV1");
            }

            if ("Y".equals(persistBatchRun)) {
                batchRunId = delegator.getNextSeqId("LoanReconciliationBatchRun");
                GenericValue batch = delegator.makeValue("LoanReconciliationBatchRun");
                batch.set("batchRunId", batchRunId);
                batch.set("organizationPartyId", organizationPartyId);
                batch.set("batchRunTypeId", batchRunTypeId);
                batch.set("statusId", "BATCH_RUNNING");
                batch.set("dryRun", dryRun);
                batch.set("persistRuns", persistRuns);
                batch.set("startedStamp", startedStamp);
                if (userLogin != null) {
                    batch.set("startedByUserLoginId", userLogin.getString("userLoginId"));
                }
                delegator.create(batch);
            }

            for (String loanId : loanIds) {
                Map<String, Object> row = new LinkedHashMap<String, Object>();
                row.put("loanId", loanId);
                row.put("organizationPartyId", organizationPartyId);
                row.put("dryRun", dryRun);
                row.put("persistRuns", persistRuns);

                try {
                    if (UtilValidate.isEmpty(loanId)) {
                        skippedCount++;
                        row.put("status", "SKIPPED");
                        row.put("message", "Blank loanId skipped");
                        resultRows.add(row);
                        continue;
                    }

                    GenericValue loan = EntityQuery.use(delegator)
                            .from("PartyLoan")
                            .where("loanId", loanId)
                            .queryOne();
                    if (loan == null) {
                        errorCount++;
                        row.put("status", "ERROR");
                        row.put("message", "PartyLoan not found");
                        resultRows.add(row);
                        persistBatchResult(delegator, batchRunId, row, null, 0, 0, "ERROR", "PartyLoan not found");
                        continue;
                    }
                    String loanOrg = trimToNull(loan.getString("organizationPartyId"));
                    if (UtilValidate.isNotEmpty(organizationPartyId) && !organizationPartyId.equals(loanOrg)) {
                        errorCount++;
                        row.put("status", "ERROR");
                        row.put("message", "Loan organization mismatch; automation will not reconcile cross-org loans");
                        resultRows.add(row);
                        persistBatchResult(delegator, batchRunId, row, null, 0, 0, "ERROR", "Loan organization mismatch");
                        continue;
                    }

                    Map<String, Object> serviceCtx = new LinkedHashMap<String, Object>();
                    serviceCtx.put("loanId", loanId);
                    serviceCtx.put("organizationPartyId", organizationPartyId);
                    serviceCtx.put("strictDuplicateAcctg", strictDuplicateAcctg);
                    serviceCtx.put("requireFinAccountTrans", requireFinAccountTrans);
                    serviceCtx.put("userLogin", userLogin);

                    Map<String, Object> resp;
                    String reconciliationRunId = null;
                    if ("Y".equals(persistRuns) && !"Y".equals(dryRun)) {
                        resp = dispatcher.runSync("persistLoanReconciliationRunV1", serviceCtx);
                        Object rid = resp.get("reconciliationRunId");
                        reconciliationRunId = rid == null ? null : rid.toString();
                        if (UtilValidate.isEmpty(reconciliationRunId) && resp.get("data") instanceof Map) {
                            @SuppressWarnings("unchecked")
                            Map<String, Object> dataMap = (Map<String, Object>) resp.get("data");
                            Object nestedRid = dataMap.get("reconciliationRunId");
                            reconciliationRunId = nestedRid == null ? null : nestedRid.toString();
                        }
                    } else {
                        resp = dispatcher.runSync("reconcileLoanAccountingV1", serviceCtx);
                    }

                    if (ServiceUtil.isError(resp)) {
                        errorCount++;
                        String message = ServiceUtil.getErrorMessage(resp);
                        row.put("status", "ERROR");
                        row.put("message", message);
                        resultRows.add(row);
                        persistBatchResult(delegator, batchRunId, row, reconciliationRunId, 0, 0, "ERROR", message);
                        continue;
                    }

                    Map<String, Object> payload = extractPayload(resp);
                    int perLoanErrorCount = getInt(payload, "errorCount");
                    int perLoanIssueCount = getInt(payload, "issueCount");
                    int checkedAcctgTransCount = getInt(payload, "checkedAcctgTransCount");
                    String status = perLoanErrorCount == 0 ? "CLEAN" : "ISSUES";

                    checkedLoanCount++;
                    successCount++;
                    totalReconciliationIssueCount += perLoanIssueCount;
                    if (perLoanErrorCount == 0) {
                        cleanCount++;
                    } else {
                        issueCount++;
                    }

                    row.put("status", status);
                    row.put("message", perLoanErrorCount == 0 ? "Reconciliation clean" : "Reconciliation issues detected");
                    row.put("reconciliationRunId", reconciliationRunId);
                    row.put("errorCount", Integer.valueOf(perLoanErrorCount));
                    row.put("issueCount", Integer.valueOf(perLoanIssueCount));
                    row.put("warningCount", Integer.valueOf(getInt(payload, "warningCount")));
                    row.put("checkedAcctgTransCount", Integer.valueOf(checkedAcctgTransCount));
                    row.put("checkedScheduleCount", Integer.valueOf(getInt(payload, "checkedScheduleCount")));
                    row.put("checkedRepaymentCount", Integer.valueOf(getInt(payload, "checkedRepaymentCount")));
                    row.put("checkedPaymentCount", Integer.valueOf(getInt(payload, "checkedPaymentCount")));
                    resultRows.add(row);

                    if (UtilValidate.isNotEmpty(reconciliationRunId)) {
                        persistedRunCount++;
                    }
                    persistBatchResult(delegator, batchRunId, row, reconciliationRunId, perLoanErrorCount, perLoanIssueCount, status, (String) row.get("message"));
                } catch (Exception e) {
                    errorCount++;
                    Debug.logError(e, "Error reconciling loanId=" + loanId + " in Phase 6A automation", MODULE);
                    row.put("status", "ERROR");
                    row.put("message", e.getMessage());
                    resultRows.add(row);
                    persistBatchResult(delegator, batchRunId, row, null, 0, 0, "ERROR", e.getMessage());
                }
            }

            Timestamp finishedStamp = UtilDateTime.nowTimestamp();
            String batchStatusId = errorCount > 0 ? "BATCH_ERRORS" : (issueCount > 0 ? "BATCH_ISSUES" : "BATCH_DONE");
            updateBatchRun(delegator, batchRunId, batchStatusId, finishedStamp,
                    requestedLoanCount, checkedLoanCount, successCount, cleanCount, issueCount,
                    errorCount, persistedRunCount, totalReconciliationIssueCount, skippedCount);

            Map<String, Object> payload = new LinkedHashMap<String, Object>();
            payload.put("batchRunId", batchRunId);
            payload.put("organizationPartyId", organizationPartyId);
            payload.put("batchRunTypeId", batchRunTypeId);
            payload.put("dryRun", dryRun);
            payload.put("persistRuns", persistRuns);
            payload.put("persistBatchRun", persistBatchRun);
            payload.put("statusId", batchStatusId);
            payload.put("requestedLoanCount", Integer.valueOf(requestedLoanCount));
            payload.put("checkedLoanCount", Integer.valueOf(checkedLoanCount));
            payload.put("successCount", Integer.valueOf(successCount));
            payload.put("cleanCount", Integer.valueOf(cleanCount));
            payload.put("issueLoanCount", Integer.valueOf(issueCount));
            payload.put("errorCount", Integer.valueOf(errorCount));
            payload.put("skippedCount", Integer.valueOf(skippedCount));
            payload.put("persistedRunCount", Integer.valueOf(persistedRunCount));
            payload.put("totalReconciliationIssueCount", Integer.valueOf(totalReconciliationIssueCount));
            payload.put("results", resultRows);
            return buildSuccess("Loan reconciliation automation batch completed successfully", payload);
        } catch (Exception e) {
            Debug.logError(e, "Error running Phase 6A loan reconciliation automation", MODULE);
            try {
                updateBatchRun(delegator, batchRunId, "BATCH_ERROR", UtilDateTime.nowTimestamp(),
                        requestedLoanCount, checkedLoanCount, successCount, cleanCount, issueCount,
                        errorCount + 1, persistedRunCount, totalReconciliationIssueCount, skippedCount);
            } catch (Exception ignored) {
                Debug.logWarning(ignored, "Could not update failed LoanReconciliationBatchRun", MODULE);
            }
            return ServiceUtil.returnError(e.getMessage());
        }
    }

    private static void persistBatchResult(Delegator delegator, String batchRunId, Map<String, Object> row,
            String reconciliationRunId, int errorCount, int issueCount, String statusId, String message) throws Exception {
        if (UtilValidate.isEmpty(batchRunId)) {
            return;
        }
        GenericValue result = delegator.makeValue("LoanReconciliationBatchResult");
        result.set("batchResultId", delegator.getNextSeqId("LoanReconciliationBatchResult"));
        result.set("batchRunId", batchRunId);
        result.set("reconciliationRunId", reconciliationRunId);
        result.set("organizationPartyId", asString(row.get("organizationPartyId")));
        result.set("loanId", asString(row.get("loanId")));
        result.set("statusId", statusId);
        result.set("errorCount", Long.valueOf(errorCount));
        result.set("issueCount", Long.valueOf(issueCount));
        result.set("message", message);
        result.set("createdStamp", UtilDateTime.nowTimestamp());
        delegator.create(result);
    }

    private static void updateBatchRun(Delegator delegator, String batchRunId, String statusId, Timestamp finishedStamp,
            int requestedLoanCount, int checkedLoanCount, int successCount, int cleanCount, int issueCount,
            int errorCount, int persistedRunCount, int totalIssueCount, int skippedCount) throws Exception {
        if (UtilValidate.isEmpty(batchRunId)) {
            return;
        }
        GenericValue batch = EntityQuery.use(delegator)
                .from("LoanReconciliationBatchRun")
                .where("batchRunId", batchRunId)
                .queryOne();
        if (batch == null) {
            return;
        }
        batch.set("statusId", statusId);
        batch.set("finishedStamp", finishedStamp);
        batch.set("requestedLoanCount", Long.valueOf(requestedLoanCount));
        batch.set("checkedLoanCount", Long.valueOf(checkedLoanCount));
        batch.set("successCount", Long.valueOf(successCount));
        batch.set("cleanCount", Long.valueOf(cleanCount));
        batch.set("issueLoanCount", Long.valueOf(issueCount));
        batch.set("errorCount", Long.valueOf(errorCount));
        batch.set("skippedCount", Long.valueOf(skippedCount));
        batch.set("persistedRunCount", Long.valueOf(persistedRunCount));
        batch.set("totalIssueCount", Long.valueOf(totalIssueCount));
        batch.store();
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

    private static int normalizeMaxLoans(Object obj) {
        int value = DEFAULT_MAX_LOANS;
        if (obj instanceof Number) {
            value = ((Number) obj).intValue();
        } else if (obj != null && UtilValidate.isNotEmpty(obj.toString())) {
            try { value = Integer.parseInt(obj.toString()); } catch (Exception ignored) {}
        }
        if (value <= 0) value = DEFAULT_MAX_LOANS;
        return Math.min(value, HARD_MAX_LOANS);
    }

    private static List<String> parseLoanIds(String loanIdsRaw, int maxLoans) {
        List<String> out = new ArrayList<String>();
        if (UtilValidate.isEmpty(loanIdsRaw)) {
            return out;
        }
        List<String> pieces = Arrays.asList(loanIdsRaw.split("[,\\s]+"));
        for (String piece : pieces) {
            String value = trimToNull(piece);
            if (UtilValidate.isEmpty(value)) continue;
            if (!out.contains(value)) out.add(value);
            if (out.size() >= maxLoans) break;
        }
        return out;
    }

    private static String join(List<String> values) {
        StringBuilder sb = new StringBuilder();
        for (String value : values) {
            if (sb.length() > 0) sb.append(',');
            sb.append(value);
        }
        return sb.toString();
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
}
