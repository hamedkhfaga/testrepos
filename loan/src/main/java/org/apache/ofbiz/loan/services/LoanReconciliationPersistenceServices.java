package org.apache.ofbiz.loan.services;

import java.math.BigDecimal;
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

public final class LoanReconciliationPersistenceServices {
    public static final String MODULE = LoanReconciliationPersistenceServices.class.getName();

    private LoanReconciliationPersistenceServices() {}

    public static Map<String, Object> persistLoanReconciliationRunV1(DispatchContext dctx, Map<String, ?> context) {
        Delegator delegator = dctx.getDelegator();
        LocalDispatcher dispatcher = dctx.getDispatcher();
        GenericValue userLogin = (GenericValue) context.get("userLogin");

        String loanId = trimToNull((String) context.get("loanId"));
        String organizationPartyId = trimToNull((String) context.get("organizationPartyId"));
        String strictDuplicateAcctg = normalizeFlag((String) context.get("strictDuplicateAcctg"));
        String requireFinAccountTrans = normalizeFlag((String) context.get("requireFinAccountTrans"));

        Timestamp startedStamp = UtilDateTime.nowTimestamp();

        try {
            Map<String, Object> reconCtx = new HashMap<String, Object>();
            reconCtx.put("loanId", loanId);
            reconCtx.put("organizationPartyId", organizationPartyId);
            reconCtx.put("strictDuplicateAcctg", strictDuplicateAcctg);
            reconCtx.put("requireFinAccountTrans", requireFinAccountTrans);
            reconCtx.put("userLogin", userLogin);

            Map<String, Object> reconResp = dispatcher.runSync("reconcileLoanAccountingV1", reconCtx);
            if (ServiceUtil.isError(reconResp)) {
                return ServiceUtil.returnError(ServiceUtil.getErrorMessage(reconResp));
            }

            Map<String, Object> reconData = extractPayload(reconResp);

            String reconciliationRunId = delegator.getNextSeqId("LoanReconciliationRun");
            int checkedLoanCount = getInt(reconData, "checkedLoanCount");
            int checkedScheduleCount = getInt(reconData, "checkedScheduleCount");
            int checkedRepaymentCount = getInt(reconData, "checkedRepaymentCount");
            int checkedPaymentCount = getInt(reconData, "checkedPaymentCount");
            int checkedAcctgTransCount = getInt(reconData, "checkedAcctgTransCount");
            int errorCount = getInt(reconData, "errorCount");
            int warningCount = getInt(reconData, "warningCount");
            int infoCount = getInt(reconData, "infoCount");
            int issueCount = getInt(reconData, "issueCount");
            Timestamp finishedStamp = UtilDateTime.nowTimestamp();

            GenericValue run = delegator.makeValue("LoanReconciliationRun");
            run.set("reconciliationRunId", reconciliationRunId);
            run.set("organizationPartyId", organizationPartyId);
            run.set("loanId", loanId);
            run.set("runTypeEnumId", "LOAN_RECON");
            run.set("statusId", errorCount == 0 ? "RECON_DONE" : "RECON_ISSUES");
            run.set("startedStamp", startedStamp);
            run.set("finishedStamp", finishedStamp);
            run.set("checkedLoanCount", Long.valueOf(checkedLoanCount));
            run.set("checkedScheduleCount", Long.valueOf(checkedScheduleCount));
            run.set("checkedRepaymentCount", Long.valueOf(checkedRepaymentCount));
            run.set("checkedPaymentCount", Long.valueOf(checkedPaymentCount));
            run.set("checkedAcctgTransCount", Long.valueOf(checkedAcctgTransCount));
            run.set("errorCount", Long.valueOf(errorCount));
            run.set("warningCount", Long.valueOf(warningCount));
            run.set("infoCount", Long.valueOf(infoCount));
            run.set("issueCount", Long.valueOf(issueCount));
            if (userLogin != null) {
                run.set("startedByUserLoginId", userLogin.getString("userLoginId"));
            }
            delegator.create(run);

            int persistedIssueCount = 0;
            Object issuesObj = reconData.get("issues");
            if (issuesObj instanceof List) {
                @SuppressWarnings("unchecked")
                List<Object> issues = (List<Object>) issuesObj;
                for (Object obj : issues) {
                    if (!(obj instanceof Map)) {
                        continue;
                    }
                    @SuppressWarnings("unchecked")
                    Map<String, Object> issueMap = (Map<String, Object>) obj;

                    GenericValue issue = delegator.makeValue("LoanReconciliationIssue");
                    issue.set("reconciliationIssueId", delegator.getNextSeqId("LoanReconciliationIssue"));
                    issue.set("reconciliationRunId", reconciliationRunId);
                    issue.set("severityEnumId", asString(issueMap.get("severity")));
                    issue.set("issueTypeEnumId", asString(issueMap.get("issueType")));
                    issue.set("organizationPartyId", asString(issueMap.get("organizationPartyId")));
                    issue.set("loanId", asString(issueMap.get("loanId")));
                    issue.set("loanScheduleId", asString(issueMap.get("loanScheduleId")));
                    issue.set("loanRepaymentId", asString(issueMap.get("loanRepaymentId")));
                    issue.set("paymentId", asString(issueMap.get("paymentId")));
                    issue.set("acctgTransId", asString(issueMap.get("acctgTransId")));
                    issue.set("expectedAmount", getBigDecimal(issueMap.get("expectedAmount")));
                    issue.set("actualAmount", getBigDecimal(issueMap.get("actualAmount")));
                    issue.set("currencyUomId", asString(issueMap.get("currencyUomId")));
                    issue.set("description", asString(issueMap.get("description")));
                    issue.set("createdStamp", finishedStamp);
                    delegator.create(issue);
                    persistedIssueCount++;
                }
            }

            Map<String, Object> payload = new LinkedHashMap<String, Object>();
            payload.put("reconciliationRunId", reconciliationRunId);
            payload.put("organizationPartyId", organizationPartyId);
            payload.put("loanId", loanId);
            payload.put("strictDuplicateAcctg", strictDuplicateAcctg);
            payload.put("requireFinAccountTrans", requireFinAccountTrans);
            payload.put("statusId", errorCount == 0 ? "RECON_DONE" : "RECON_ISSUES");
            payload.put("checkedLoanCount", Integer.valueOf(checkedLoanCount));
            payload.put("checkedScheduleCount", Integer.valueOf(checkedScheduleCount));
            payload.put("checkedRepaymentCount", Integer.valueOf(checkedRepaymentCount));
            payload.put("checkedPaymentCount", Integer.valueOf(checkedPaymentCount));
            payload.put("checkedAcctgTransCount", Integer.valueOf(checkedAcctgTransCount));
            payload.put("errorCount", Integer.valueOf(errorCount));
            payload.put("warningCount", Integer.valueOf(warningCount));
            payload.put("infoCount", Integer.valueOf(infoCount));
            payload.put("issueCount", Integer.valueOf(issueCount));
            payload.put("persistedIssueCount", Integer.valueOf(persistedIssueCount));

            Map<String, Object> result = ServiceUtil.returnSuccess("Loan reconciliation run persisted successfully");
            result.putAll(payload);
            result.put("data", new LinkedHashMap<String, Object>(payload));
            result.put("legacyData", new LinkedHashMap<String, Object>(payload));
            return result;
        } catch (Exception e) {
            Debug.logError(e, "Error persisting loan reconciliation run", MODULE);
            return ServiceUtil.returnError(e.getMessage());
        }
    }


    public static Map<String, Object> getLoanReconciliationRunListV1(DispatchContext dctx, Map<String, ?> context) {
        Delegator delegator = dctx.getDelegator();
        String loanId = trimToNull((String) context.get("loanId"));
        String organizationPartyId = trimToNull((String) context.get("organizationPartyId"));
        String statusId = trimToNull((String) context.get("statusId"));
        Integer viewIndexObj = toInteger(context.get("viewIndex"));
        Integer viewSizeObj = toInteger(context.get("viewSize"));
        int viewIndex = viewIndexObj != null && viewIndexObj.intValue() >= 0 ? viewIndexObj.intValue() : 0;
        int viewSize = viewSizeObj != null && viewSizeObj.intValue() > 0 ? viewSizeObj.intValue() : 20;
        try {
            List<EntityCondition> conditions = new ArrayList<EntityCondition>();
            if (UtilValidate.isNotEmpty(loanId)) conditions.add(EntityCondition.makeCondition("loanId", loanId));
            if (UtilValidate.isNotEmpty(organizationPartyId)) conditions.add(EntityCondition.makeCondition("organizationPartyId", organizationPartyId));
            if (UtilValidate.isNotEmpty(statusId)) conditions.add(EntityCondition.makeCondition("statusId", statusId));
            EntityQuery query = EntityQuery.use(delegator).from("LoanReconciliationRun").orderBy("-startedStamp", "-reconciliationRunId");
            if (!conditions.isEmpty()) query = query.where(EntityCondition.makeCondition(conditions, EntityOperator.AND));
            List<GenericValue> rows = query.queryList();
            int totalCount = rows.size();
            int fromIndex = Math.min(viewIndex * viewSize, totalCount);
            int toIndex = Math.min(fromIndex + viewSize, totalCount);
            List<Map<String, Object>> runList = new ArrayList<Map<String, Object>>();
            for (int i = fromIndex; i < toIndex; i++) runList.add(runToMap(rows.get(i), false));
            Map<String, Object> payload = new LinkedHashMap<String, Object>();
            payload.put("organizationPartyId", organizationPartyId);
            payload.put("loanId", loanId);
            payload.put("statusId", statusId);
            payload.put("viewIndex", Integer.valueOf(viewIndex));
            payload.put("viewSize", Integer.valueOf(viewSize));
            payload.put("totalCount", Integer.valueOf(totalCount));
            payload.put("runList", runList);
            return buildSuccess("Loan reconciliation run list retrieved successfully", payload);
        } catch (Exception e) {
            Debug.logError(e, "Error getting loan reconciliation run list", MODULE);
            return ServiceUtil.returnError(e.getMessage());
        }
    }

    public static Map<String, Object> getLoanReconciliationRunDetailV1(DispatchContext dctx, Map<String, ?> context) {
        Delegator delegator = dctx.getDelegator();
        String reconciliationRunId = trimToNull((String) context.get("reconciliationRunId"));
        try {
            if (UtilValidate.isEmpty(reconciliationRunId)) return ServiceUtil.returnError("reconciliationRunId is required");
            GenericValue run = EntityQuery.use(delegator).from("LoanReconciliationRun").where("reconciliationRunId", reconciliationRunId).queryOne();
            if (run == null) return ServiceUtil.returnError("LoanReconciliationRun not found: " + reconciliationRunId);
            List<GenericValue> issues = EntityQuery.use(delegator).from("LoanReconciliationIssue").where("reconciliationRunId", reconciliationRunId).orderBy("severityEnumId", "issueTypeEnumId", "reconciliationIssueId").queryList();
            List<Map<String, Object>> issueList = new ArrayList<Map<String, Object>>();
            for (GenericValue issue : issues) issueList.add(issueToMap(issue));
            Map<String, Object> payload = runToMap(run, true);
            payload.put("issues", issueList);
            payload.put("issueList", issueList);
            payload.put("persistedIssueCount", Integer.valueOf(issueList.size()));
            return buildSuccess("Loan reconciliation run detail retrieved successfully", payload);
        } catch (Exception e) {
            Debug.logError(e, "Error getting loan reconciliation run detail", MODULE);
            return ServiceUtil.returnError(e.getMessage());
        }
    }

    public static Map<String, Object> getLoanReconciliationIssueListV1(DispatchContext dctx, Map<String, ?> context) {
        Delegator delegator = dctx.getDelegator();
        String reconciliationRunId = trimToNull((String) context.get("reconciliationRunId"));
        String loanId = trimToNull((String) context.get("loanId"));
        String organizationPartyId = trimToNull((String) context.get("organizationPartyId"));
        String severityEnumId = trimToNull((String) context.get("severityEnumId"));
        String issueTypeEnumId = trimToNull((String) context.get("issueTypeEnumId"));
        String paymentId = trimToNull((String) context.get("paymentId"));
        try {
            List<EntityCondition> conditions = new ArrayList<EntityCondition>();
            if (UtilValidate.isNotEmpty(reconciliationRunId)) conditions.add(EntityCondition.makeCondition("reconciliationRunId", reconciliationRunId));
            if (UtilValidate.isNotEmpty(loanId)) conditions.add(EntityCondition.makeCondition("loanId", loanId));
            if (UtilValidate.isNotEmpty(organizationPartyId)) conditions.add(EntityCondition.makeCondition("organizationPartyId", organizationPartyId));
            if (UtilValidate.isNotEmpty(severityEnumId)) conditions.add(EntityCondition.makeCondition("severityEnumId", severityEnumId));
            if (UtilValidate.isNotEmpty(issueTypeEnumId)) conditions.add(EntityCondition.makeCondition("issueTypeEnumId", issueTypeEnumId));
            if (UtilValidate.isNotEmpty(paymentId)) conditions.add(EntityCondition.makeCondition("paymentId", paymentId));
            EntityQuery query = EntityQuery.use(delegator).from("LoanReconciliationIssue").orderBy("-createdStamp", "reconciliationIssueId");
            if (!conditions.isEmpty()) query = query.where(EntityCondition.makeCondition(conditions, EntityOperator.AND));
            List<GenericValue> rows = query.queryList();
            List<Map<String, Object>> issueList = new ArrayList<Map<String, Object>>();
            for (GenericValue issue : rows) issueList.add(issueToMap(issue));
            Map<String, Object> payload = new LinkedHashMap<String, Object>();
            payload.put("reconciliationRunId", reconciliationRunId);
            payload.put("organizationPartyId", organizationPartyId);
            payload.put("loanId", loanId);
            payload.put("severityEnumId", severityEnumId);
            payload.put("issueTypeEnumId", issueTypeEnumId);
            payload.put("paymentId", paymentId);
            payload.put("issueCount", Integer.valueOf(issueList.size()));
            payload.put("issueList", issueList);
            payload.put("issues", issueList);
            return buildSuccess("Loan reconciliation issue list retrieved successfully", payload);
        } catch (Exception e) {
            Debug.logError(e, "Error getting loan reconciliation issue list", MODULE);
            return ServiceUtil.returnError(e.getMessage());
        }
    }

    public static Map<String, Object> getLatestLoanReconciliationStatusV1(DispatchContext dctx, Map<String, ?> context) {
        Delegator delegator = dctx.getDelegator();
        String loanId = trimToNull((String) context.get("loanId"));
        String organizationPartyId = trimToNull((String) context.get("organizationPartyId"));
        try {
            if (UtilValidate.isEmpty(loanId) && UtilValidate.isEmpty(organizationPartyId)) return ServiceUtil.returnError("Either loanId or organizationPartyId is required");
            List<EntityCondition> conditions = new ArrayList<EntityCondition>();
            if (UtilValidate.isNotEmpty(loanId)) conditions.add(EntityCondition.makeCondition("loanId", loanId));
            if (UtilValidate.isNotEmpty(organizationPartyId)) conditions.add(EntityCondition.makeCondition("organizationPartyId", organizationPartyId));
            GenericValue latestRun = EntityQuery.use(delegator).from("LoanReconciliationRun").where(EntityCondition.makeCondition(conditions, EntityOperator.AND)).orderBy("-startedStamp", "-reconciliationRunId").queryFirst();
            Map<String, Object> payload = new LinkedHashMap<String, Object>();
            payload.put("organizationPartyId", organizationPartyId);
            payload.put("loanId", loanId);
            if (latestRun == null) {
                payload.put("hasRun", "N");
                payload.put("latestStatusId", "RECON_NOT_RUN");
                payload.put("latestRun", null);
            } else {
                payload.put("hasRun", "Y");
                payload.put("latestStatusId", latestRun.getString("statusId"));
                payload.put("latestRun", runToMap(latestRun, true));
                payload.put("errorCount", toInt(latestRun.get("errorCount")));
                payload.put("warningCount", toInt(latestRun.get("warningCount")));
                payload.put("issueCount", toInt(latestRun.get("issueCount")));
            }
            return buildSuccess("Latest loan reconciliation status retrieved successfully", payload);
        } catch (Exception e) {
            Debug.logError(e, "Error getting latest loan reconciliation status", MODULE);
            return ServiceUtil.returnError(e.getMessage());
        }
    }

    public static Map<String, Object> getLoanReconciliationDashboardV1(DispatchContext dctx, Map<String, ?> context) {
        Delegator delegator = dctx.getDelegator();
        String organizationPartyId = trimToNull((String) context.get("organizationPartyId"));
        String loanId = trimToNull((String) context.get("loanId"));
        try {
            if (UtilValidate.isEmpty(organizationPartyId)) return ServiceUtil.returnError("organizationPartyId is required");
            List<EntityCondition> runConditions = new ArrayList<EntityCondition>();
            runConditions.add(EntityCondition.makeCondition("organizationPartyId", organizationPartyId));
            if (UtilValidate.isNotEmpty(loanId)) runConditions.add(EntityCondition.makeCondition("loanId", loanId));
            List<GenericValue> runs = EntityQuery.use(delegator).from("LoanReconciliationRun").where(EntityCondition.makeCondition(runConditions, EntityOperator.AND)).orderBy("-startedStamp", "-reconciliationRunId").queryList();
            int cleanRunCount = 0, issueRunCount = 0, totalErrorCount = 0, totalWarningCount = 0, totalIssueCount = 0;
            List<Map<String, Object>> recentRuns = new ArrayList<Map<String, Object>>();
            for (int i = 0; i < runs.size(); i++) {
                GenericValue run = runs.get(i);
                if ("RECON_DONE".equals(run.getString("statusId"))) cleanRunCount++; else issueRunCount++;
                totalErrorCount += toInt(run.get("errorCount"));
                totalWarningCount += toInt(run.get("warningCount"));
                totalIssueCount += toInt(run.get("issueCount"));
                if (i < 10) recentRuns.add(runToMap(run, false));
            }
            List<EntityCondition> issueConditions = new ArrayList<EntityCondition>();
            issueConditions.add(EntityCondition.makeCondition("organizationPartyId", organizationPartyId));
            if (UtilValidate.isNotEmpty(loanId)) issueConditions.add(EntityCondition.makeCondition("loanId", loanId));
            List<GenericValue> issues = EntityQuery.use(delegator).from("LoanReconciliationIssue").where(EntityCondition.makeCondition(issueConditions, EntityOperator.AND)).queryList();
            Map<String, Integer> bySeverity = new LinkedHashMap<String, Integer>();
            Map<String, Integer> byType = new LinkedHashMap<String, Integer>();
            for (GenericValue issue : issues) {
                increment(bySeverity, trimToNull(issue.getString("severityEnumId")));
                increment(byType, trimToNull(issue.getString("issueTypeEnumId")));
            }
            Map<String, Object> payload = new LinkedHashMap<String, Object>();
            payload.put("organizationPartyId", organizationPartyId);
            payload.put("loanId", loanId);
            payload.put("totalRunCount", Integer.valueOf(runs.size()));
            payload.put("cleanRunCount", Integer.valueOf(cleanRunCount));
            payload.put("issueRunCount", Integer.valueOf(issueRunCount));
            payload.put("totalErrorCount", Integer.valueOf(totalErrorCount));
            payload.put("totalWarningCount", Integer.valueOf(totalWarningCount));
            payload.put("totalIssueCount", Integer.valueOf(totalIssueCount));
            payload.put("latestRun", runs.isEmpty() ? null : runToMap(runs.get(0), true));
            payload.put("recentRuns", recentRuns);
            payload.put("issueSummaryBySeverity", mapToRows(bySeverity, "severityEnumId"));
            payload.put("issueSummaryByType", mapToRows(byType, "issueTypeEnumId"));
            return buildSuccess("Loan reconciliation dashboard retrieved successfully", payload);
        } catch (Exception e) {
            Debug.logError(e, "Error getting loan reconciliation dashboard", MODULE);
            return ServiceUtil.returnError(e.getMessage());
        }
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
        if (map == null) {
            return 0;
        }
        Object obj = map.get(key);
        if (obj instanceof Number) {
            return ((Number) obj).intValue();
        }
        if (obj != null) {
            try {
                return Integer.parseInt(obj.toString());
            } catch (Exception ignored) {
            }
        }
        return 0;
    }

    private static BigDecimal getBigDecimal(Object obj) {
        if (obj instanceof BigDecimal) {
            return (BigDecimal) obj;
        }
        if (obj instanceof Number) {
            return new BigDecimal(obj.toString());
        }
        if (obj != null && UtilValidate.isNotEmpty(obj.toString())) {
            try {
                return new BigDecimal(obj.toString());
            } catch (Exception ignored) {
            }
        }
        return null;
    }

    private static String asString(Object obj) {
        if (obj == null) {
            return null;
        }
        String s = obj.toString();
        return s.trim().isEmpty() ? null : s;
    }




    /** Phase 6K - persist durable repair execution audit rows for dashboards and backend closure. */
    public static Map<String, Object> persistLoanRepairExecutionAuditV1(DispatchContext dctx, Map<String, ?> context) {
        Delegator delegator = dctx.getDelegator();
        GenericValue userLogin = (GenericValue) context.get("userLogin");

        String organizationPartyId = trimToNull((String) context.get("organizationPartyId"));
        String repairProposalId = trimToNull((String) context.get("repairProposalId"));
        String loanId = trimToNull((String) context.get("loanId"));
        String executionStatusId = trimToNull((String) context.get("executionStatusId"));
        String repairServiceName = trimToNull((String) context.get("repairServiceName"));
        String finalStatus = trimToNull((String) context.get("finalStatus"));
        Integer beforeErrorCount = toInteger(context.get("beforeErrorCount"));
        Integer afterErrorCount = toInteger(context.get("afterErrorCount"));
        String resultPayload = safeToString(context.get("resultPayload"));

        try {
            if (UtilValidate.isEmpty(organizationPartyId)) return ServiceUtil.returnError("organizationPartyId is required");
            if (UtilValidate.isEmpty(repairProposalId)) return ServiceUtil.returnError("repairProposalId is required");

            String executionAuditId = delegator.getNextSeqId("LoanRepairExecutionAudit");
            Timestamp now = UtilDateTime.nowTimestamp();
            GenericValue audit = delegator.makeValue("LoanRepairExecutionAudit");
            audit.set("executionAuditId", executionAuditId);
            audit.set("organizationPartyId", organizationPartyId);
            audit.set("repairProposalId", repairProposalId);
            audit.set("loanId", loanId);
            audit.set("executionStatusId", UtilValidate.isNotEmpty(executionStatusId) ? executionStatusId : finalStatus);
            audit.set("repairServiceName", repairServiceName);
            audit.set("beforeErrorCount", beforeErrorCount != null ? Long.valueOf(beforeErrorCount.intValue()) : null);
            audit.set("afterErrorCount", afterErrorCount != null ? Long.valueOf(afterErrorCount.intValue()) : null);
            audit.set("finalStatus", finalStatus);
            audit.set("resultPayload", resultPayload);
            audit.set("executedByUserLoginId", userLogin != null ? userLogin.getString("userLoginId") : null);
            audit.set("executedStamp", now);
            delegator.create(audit);

            Map<String, Object> payload = new LinkedHashMap<String, Object>();
            payload.put("executionAuditId", executionAuditId);
            payload.put("organizationPartyId", organizationPartyId);
            payload.put("repairProposalId", repairProposalId);
            payload.put("loanId", loanId);
            payload.put("executionStatusId", audit.getString("executionStatusId"));
            payload.put("finalStatus", finalStatus);
            return buildSuccess("Loan repair execution audit persisted successfully", payload);
        } catch (Exception e) {
            Debug.logError(e, "Error persisting loan repair execution audit", MODULE);
            return ServiceUtil.returnError(e.getMessage());
        }
    }

    /** Phase 6K - enqueue a failed proposal into a durable retry queue. */
    public static Map<String, Object> enqueueLoanRepairRetryV1(DispatchContext dctx, Map<String, ?> context) {
        Delegator delegator = dctx.getDelegator();
        GenericValue userLogin = (GenericValue) context.get("userLogin");

        String organizationPartyId = trimToNull((String) context.get("organizationPartyId"));
        String repairProposalId = trimToNull((String) context.get("repairProposalId"));
        String loanId = trimToNull((String) context.get("loanId"));
        String failureReason = safeToString(context.get("failureReason"));
        Integer retryCount = toInteger(context.get("retryCount"));
        Timestamp nextRetryDate = (Timestamp) context.get("nextRetryDate");

        try {
            if (UtilValidate.isEmpty(organizationPartyId)) return ServiceUtil.returnError("organizationPartyId is required");
            if (UtilValidate.isEmpty(repairProposalId)) return ServiceUtil.returnError("repairProposalId is required");

            GenericValue existing = EntityQuery.use(delegator)
                    .from("LoanRepairRetryQueue")
                    .where("repairProposalId", repairProposalId, "retryStatusId", "LRR_RETRY_PENDING")
                    .queryFirst();
            Timestamp now = UtilDateTime.nowTimestamp();
            String retryQueueId;
            if (existing != null) {
                retryQueueId = existing.getString("retryQueueId");
                Long current = existing.getLong("retryCount");
                existing.set("retryCount", Long.valueOf(current == null ? 1L : current.longValue() + 1L));
                existing.set("failureReason", failureReason);
                existing.set("nextRetryDate", nextRetryDate);
                existing.set("lastUpdatedByUserLoginId", userLogin != null ? userLogin.getString("userLoginId") : null);
                existing.set("lastUpdatedDate", now);
                existing.store();
            } else {
                retryQueueId = delegator.getNextSeqId("LoanRepairRetryQueue");
                GenericValue retry = delegator.makeValue("LoanRepairRetryQueue");
                retry.set("retryQueueId", retryQueueId);
                retry.set("organizationPartyId", organizationPartyId);
                retry.set("repairProposalId", repairProposalId);
                retry.set("loanId", loanId);
                retry.set("retryStatusId", "LRR_RETRY_PENDING");
                retry.set("retryCount", Long.valueOf(retryCount != null ? retryCount.intValue() : 0));
                retry.set("failureReason", failureReason);
                retry.set("nextRetryDate", nextRetryDate);
                retry.set("createdByUserLoginId", userLogin != null ? userLogin.getString("userLoginId") : null);
                retry.set("createdDate", now);
                retry.set("lastUpdatedByUserLoginId", userLogin != null ? userLogin.getString("userLoginId") : null);
                retry.set("lastUpdatedDate", now);
                delegator.create(retry);
            }

            Map<String, Object> payload = new LinkedHashMap<String, Object>();
            payload.put("retryQueueId", retryQueueId);
            payload.put("organizationPartyId", organizationPartyId);
            payload.put("repairProposalId", repairProposalId);
            payload.put("loanId", loanId);
            payload.put("retryStatusId", "LRR_RETRY_PENDING");
            return buildSuccess("Loan repair retry queued successfully", payload);
        } catch (Exception e) {
            Debug.logError(e, "Error enqueueing loan repair retry", MODULE);
            return ServiceUtil.returnError(e.getMessage());
        }
    }

    private static Map<String, Object> buildSuccess(String message, Map<String, Object> payload) {
        Map<String, Object> result = ServiceUtil.returnSuccess(message);
        result.putAll(payload);
        result.put("data", new LinkedHashMap<String, Object>(payload));
        result.put("legacyData", new LinkedHashMap<String, Object>(payload));
        return result;
    }

    private static Map<String, Object> runToMap(GenericValue run, boolean includeCounts) {
        Map<String, Object> row = new LinkedHashMap<String, Object>();
        row.put("reconciliationRunId", run.getString("reconciliationRunId"));
        row.put("organizationPartyId", run.getString("organizationPartyId"));
        row.put("loanId", run.getString("loanId"));
        row.put("runTypeEnumId", run.getString("runTypeEnumId"));
        row.put("statusId", run.getString("statusId"));
        row.put("startedByUserLoginId", run.getString("startedByUserLoginId"));
        row.put("startedStamp", run.getTimestamp("startedStamp"));
        row.put("finishedStamp", run.getTimestamp("finishedStamp"));
        if (includeCounts) {
            row.put("checkedLoanCount", toInt(run.get("checkedLoanCount")));
            row.put("checkedScheduleCount", toInt(run.get("checkedScheduleCount")));
            row.put("checkedRepaymentCount", toInt(run.get("checkedRepaymentCount")));
            row.put("checkedPaymentCount", toInt(run.get("checkedPaymentCount")));
            row.put("checkedAcctgTransCount", toInt(run.get("checkedAcctgTransCount")));
            row.put("errorCount", toInt(run.get("errorCount")));
            row.put("warningCount", toInt(run.get("warningCount")));
            row.put("infoCount", toInt(run.get("infoCount")));
            row.put("issueCount", toInt(run.get("issueCount")));
        }
        return row;
    }

    private static Map<String, Object> issueToMap(GenericValue issue) {
        Map<String, Object> row = new LinkedHashMap<String, Object>();
        row.put("reconciliationIssueId", issue.getString("reconciliationIssueId"));
        row.put("reconciliationRunId", issue.getString("reconciliationRunId"));
        row.put("severityEnumId", issue.getString("severityEnumId"));
        row.put("issueTypeEnumId", issue.getString("issueTypeEnumId"));
        row.put("organizationPartyId", issue.getString("organizationPartyId"));
        row.put("loanId", issue.getString("loanId"));
        row.put("loanScheduleId", issue.getString("loanScheduleId"));
        row.put("loanRepaymentId", issue.getString("loanRepaymentId"));
        row.put("paymentId", issue.getString("paymentId"));
        row.put("acctgTransId", issue.getString("acctgTransId"));
        row.put("expectedAmount", issue.getBigDecimal("expectedAmount"));
        row.put("actualAmount", issue.getBigDecimal("actualAmount"));
        row.put("currencyUomId", issue.getString("currencyUomId"));
        row.put("description", issue.getString("description"));
        row.put("createdStamp", issue.getTimestamp("createdStamp"));
        return row;
    }

    private static void increment(Map<String, Integer> counts, String key) {
        if (UtilValidate.isEmpty(key)) key = "_NA_";
        Integer current = counts.get(key);
        counts.put(key, Integer.valueOf(current == null ? 1 : current.intValue() + 1));
    }

    private static List<Map<String, Object>> mapToRows(Map<String, Integer> counts, String keyName) {
        List<Map<String, Object>> rows = new ArrayList<Map<String, Object>>();
        for (Map.Entry<String, Integer> entry : counts.entrySet()) {
            Map<String, Object> row = new LinkedHashMap<String, Object>();
            row.put(keyName, entry.getKey());
            row.put("count", entry.getValue());
            rows.add(row);
        }
        return rows;
    }

    private static Integer toInteger(Object obj) {
        if (obj == null) return null;
        if (obj instanceof Number) return Integer.valueOf(((Number) obj).intValue());
        try { return Integer.valueOf(obj.toString()); } catch (Exception e) { return null; }
    }

    private static int toInt(Object obj) {
        Integer value = toInteger(obj);
        return value == null ? 0 : value.intValue();
    }


    private static String safeToString(Object value) {
        if (value == null) return null;
        String text = String.valueOf(value);
        return text.length() > 3900 ? text.substring(0, 3900) : text;
    }

    private static String normalizeFlag(String value) {
        return "Y".equalsIgnoreCase(value) ? "Y" : "N";
    }

    private static String trimToNull(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }
}
