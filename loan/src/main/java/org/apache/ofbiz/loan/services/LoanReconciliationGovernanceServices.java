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
import org.apache.ofbiz.service.ServiceUtil;

/**
 * Phase 6B governance policy services for reconciliation issue repairability.
 *
 * Scope:
 * - Policy lookup and repair eligibility only.
 * - No payment/accounting/schedule/loan mutation.
 * - No repair execution.
 * - Designed to be consumed by future approval workflow, alerting, and controlled repair UI.
 */
public final class LoanReconciliationGovernanceServices {
    public static final String MODULE = LoanReconciliationGovernanceServices.class.getName();

    private static final String DEFAULT_ORG = "__GLOBAL__";

    private LoanReconciliationGovernanceServices() {}

    public static Map<String, Object> getLoanReconciliationIssuePolicyV1(DispatchContext dctx, Map<String, ?> context) {
        Delegator delegator = dctx.getDelegator();

        String policyId = trimToNull((String) context.get("policyId"));
        String organizationPartyId = trimToNull((String) context.get("organizationPartyId"));
        String issueTypeEnumId = trimToNull((String) context.get("issueTypeEnumId"));
        String severityEnumId = trimToNull((String) context.get("severityEnumId"));
        String activeOnly = normalizeFlagDefaultY((String) context.get("activeOnly"));

        try {
            List<EntityCondition> conditions = new ArrayList<EntityCondition>();
            if (UtilValidate.isNotEmpty(policyId)) {
                conditions.add(EntityCondition.makeCondition("policyId", policyId));
            }
            if (UtilValidate.isNotEmpty(organizationPartyId)) {
                conditions.add(EntityCondition.makeCondition(
                        EntityOperator.OR,
                        EntityCondition.makeCondition("organizationPartyId", organizationPartyId),
                        EntityCondition.makeCondition("organizationPartyId", DEFAULT_ORG)
                ));
            }
            if (UtilValidate.isNotEmpty(issueTypeEnumId)) {
                conditions.add(EntityCondition.makeCondition("issueTypeEnumId", issueTypeEnumId));
            }
            if (UtilValidate.isNotEmpty(severityEnumId)) {
                conditions.add(EntityCondition.makeCondition("severityEnumId", severityEnumId));
            }
            if ("Y".equals(activeOnly)) {
                conditions.add(activePolicyCondition());
            }

            EntityQuery query = EntityQuery.use(delegator)
                    .from("LoanReconciliationIssuePolicy")
                    .orderBy("organizationPartyId", "issueTypeEnumId", "severityEnumId", "riskLevelEnumId", "policyId");

            if (!conditions.isEmpty()) {
                query = query.where(EntityCondition.makeCondition(conditions, EntityOperator.AND));
            }

            List<GenericValue> rows = query.queryList();
            List<Map<String, Object>> policyList = new ArrayList<Map<String, Object>>();
            for (GenericValue row : rows) {
                policyList.add(policyToMap(row));
            }

            Map<String, Object> payload = new LinkedHashMap<String, Object>();
            payload.put("policyId", policyId);
            payload.put("organizationPartyId", organizationPartyId);
            payload.put("issueTypeEnumId", issueTypeEnumId);
            payload.put("severityEnumId", severityEnumId);
            payload.put("activeOnly", activeOnly);
            payload.put("policyCount", Integer.valueOf(policyList.size()));
            payload.put("policyList", policyList);
            return buildSuccess("Loan reconciliation issue policies retrieved successfully", payload);
        } catch (Exception e) {
            Debug.logError(e, "Error getting loan reconciliation issue policies", MODULE);
            return ServiceUtil.returnError(e.getMessage());
        }
    }

    public static Map<String, Object> getRepairEligibilityForIssueV1(DispatchContext dctx, Map<String, ?> context) {
        Delegator delegator = dctx.getDelegator();

        String reconciliationIssueId = trimToNull((String) context.get("reconciliationIssueId"));
        String organizationPartyId = trimToNull((String) context.get("organizationPartyId"));
        String issueTypeEnumId = trimToNull((String) context.get("issueTypeEnumId"));
        String severityEnumId = trimToNull((String) context.get("severityEnumId"));

        try {
            GenericValue issue = null;
            if (UtilValidate.isNotEmpty(reconciliationIssueId)) {
                issue = EntityQuery.use(delegator)
                        .from("LoanReconciliationIssue")
                        .where("reconciliationIssueId", reconciliationIssueId)
                        .queryOne();
                if (issue == null) {
                    return ServiceUtil.returnError("LoanReconciliationIssue not found: " + reconciliationIssueId);
                }
                organizationPartyId = firstNonEmpty(organizationPartyId, trimToNull(issue.getString("organizationPartyId")));
                issueTypeEnumId = firstNonEmpty(issueTypeEnumId, trimToNull(issue.getString("issueTypeEnumId")));
                severityEnumId = firstNonEmpty(severityEnumId, trimToNull(issue.getString("severityEnumId")));
            }

            if (UtilValidate.isEmpty(issueTypeEnumId)) {
                return ServiceUtil.returnError("issueTypeEnumId or reconciliationIssueId is required");
            }
            if (UtilValidate.isEmpty(severityEnumId)) {
                severityEnumId = "ERROR";
            }

            GenericValue policy = findBestPolicy(delegator, organizationPartyId, issueTypeEnumId, severityEnumId);
            Map<String, Object> eligibility = buildEligibilityMap(policy, reconciliationIssueId, organizationPartyId, issueTypeEnumId, severityEnumId);

            if (issue != null) {
                eligibility.put("issue", issueToMap(issue));
            }

            return buildSuccess("Loan reconciliation repair eligibility evaluated successfully", eligibility);
        } catch (Exception e) {
            Debug.logError(e, "Error evaluating repair eligibility for reconciliation issue", MODULE);
            return ServiceUtil.returnError(e.getMessage());
        }
    }

    public static Map<String, Object> evaluateLoanReconciliationIssueRepairabilityV1(DispatchContext dctx, Map<String, ?> context) {
        Delegator delegator = dctx.getDelegator();

        String reconciliationRunId = trimToNull((String) context.get("reconciliationRunId"));
        String organizationPartyId = trimToNull((String) context.get("organizationPartyId"));
        String loanId = trimToNull((String) context.get("loanId"));
        String severityEnumId = trimToNull((String) context.get("severityEnumId"));
        String issueTypeEnumId = trimToNull((String) context.get("issueTypeEnumId"));
        Integer viewIndexObj = toInteger(context.get("viewIndex"));
        Integer viewSizeObj = toInteger(context.get("viewSize"));
        int viewIndex = viewIndexObj != null && viewIndexObj.intValue() >= 0 ? viewIndexObj.intValue() : 0;
        int viewSize = viewSizeObj != null && viewSizeObj.intValue() > 0 ? viewSizeObj.intValue() : 200;

        try {
            List<EntityCondition> conditions = new ArrayList<EntityCondition>();
            if (UtilValidate.isNotEmpty(reconciliationRunId)) {
                conditions.add(EntityCondition.makeCondition("reconciliationRunId", reconciliationRunId));
            }
            if (UtilValidate.isNotEmpty(organizationPartyId)) {
                conditions.add(EntityCondition.makeCondition("organizationPartyId", organizationPartyId));
            }
            if (UtilValidate.isNotEmpty(loanId)) {
                conditions.add(EntityCondition.makeCondition("loanId", loanId));
            }
            if (UtilValidate.isNotEmpty(severityEnumId)) {
                conditions.add(EntityCondition.makeCondition("severityEnumId", severityEnumId));
            }
            if (UtilValidate.isNotEmpty(issueTypeEnumId)) {
                conditions.add(EntityCondition.makeCondition("issueTypeEnumId", issueTypeEnumId));
            }

            EntityQuery query = EntityQuery.use(delegator)
                    .from("LoanReconciliationIssue")
                    .orderBy("reconciliationRunId", "loanId", "issueTypeEnumId", "reconciliationIssueId");
            if (!conditions.isEmpty()) {
                query = query.where(EntityCondition.makeCondition(conditions, EntityOperator.AND));
            }

            List<GenericValue> allRows = query.queryList();
            int totalCount = allRows.size();
            int fromIndex = Math.min(viewIndex * viewSize, totalCount);
            int toIndex = Math.min(fromIndex + viewSize, totalCount);

            int autoRepairAllowedCount = 0;
            int manualApprovalRequiredCount = 0;
            int blockedCount = 0;
            int noPolicyCount = 0;
            int criticalRiskCount = 0;
            int highRiskCount = 0;
            int mediumRiskCount = 0;
            int lowRiskCount = 0;

            List<Map<String, Object>> eligibilityList = new ArrayList<Map<String, Object>>();
            for (int i = fromIndex; i < toIndex; i++) {
                GenericValue issue = allRows.get(i);
                String issueOrg = trimToNull(issue.getString("organizationPartyId"));
                String issueType = trimToNull(issue.getString("issueTypeEnumId"));
                String issueSeverity = trimToNull(issue.getString("severityEnumId"));

                GenericValue policy = findBestPolicy(delegator, issueOrg, issueType, issueSeverity);
                Map<String, Object> row = buildEligibilityMap(policy,
                        trimToNull(issue.getString("reconciliationIssueId")), issueOrg, issueType, issueSeverity);
                row.put("issue", issueToMap(issue));
                eligibilityList.add(row);

                if ("Y".equals(row.get("autoRepairAllowed"))) autoRepairAllowedCount++;
                if ("Y".equals(row.get("manualApprovalRequired"))) manualApprovalRequiredCount++;
                if ("Y".equals(row.get("blockBatchRepair"))) blockedCount++;
                if ("N".equals(row.get("policyFound"))) noPolicyCount++;

                String risk = trimToNull((String) row.get("riskLevelEnumId"));
                if ("RECON_RISK_CRITICAL".equals(risk)) criticalRiskCount++;
                else if ("RECON_RISK_HIGH".equals(risk)) highRiskCount++;
                else if ("RECON_RISK_MEDIUM".equals(risk)) mediumRiskCount++;
                else if ("RECON_RISK_LOW".equals(risk)) lowRiskCount++;
            }

            Map<String, Object> payload = new LinkedHashMap<String, Object>();
            payload.put("reconciliationRunId", reconciliationRunId);
            payload.put("organizationPartyId", organizationPartyId);
            payload.put("loanId", loanId);
            payload.put("severityEnumId", severityEnumId);
            payload.put("issueTypeEnumId", issueTypeEnumId);
            payload.put("viewIndex", Integer.valueOf(viewIndex));
            payload.put("viewSize", Integer.valueOf(viewSize));
            payload.put("totalCount", Integer.valueOf(totalCount));
            payload.put("evaluatedCount", Integer.valueOf(eligibilityList.size()));
            payload.put("autoRepairAllowedCount", Integer.valueOf(autoRepairAllowedCount));
            payload.put("manualApprovalRequiredCount", Integer.valueOf(manualApprovalRequiredCount));
            payload.put("blockedCount", Integer.valueOf(blockedCount));
            payload.put("noPolicyCount", Integer.valueOf(noPolicyCount));
            payload.put("criticalRiskCount", Integer.valueOf(criticalRiskCount));
            payload.put("highRiskCount", Integer.valueOf(highRiskCount));
            payload.put("mediumRiskCount", Integer.valueOf(mediumRiskCount));
            payload.put("lowRiskCount", Integer.valueOf(lowRiskCount));
            payload.put("eligibilityList", eligibilityList);
            return buildSuccess("Loan reconciliation issue repairability evaluated successfully", payload);
        } catch (Exception e) {
            Debug.logError(e, "Error evaluating reconciliation issue repairability", MODULE);
            return ServiceUtil.returnError(e.getMessage());
        }
    }

    private static GenericValue findBestPolicy(Delegator delegator, String organizationPartyId, String issueTypeEnumId, String severityEnumId) throws Exception {
        List<EntityCondition> conditions = new ArrayList<EntityCondition>();
        conditions.add(EntityCondition.makeCondition("issueTypeEnumId", issueTypeEnumId));
        conditions.add(activePolicyCondition());
        if (UtilValidate.isNotEmpty(severityEnumId)) {
            conditions.add(EntityCondition.makeCondition("severityEnumId", severityEnumId));
        }
        if (UtilValidate.isNotEmpty(organizationPartyId)) {
            conditions.add(EntityCondition.makeCondition(
                    EntityOperator.OR,
                    EntityCondition.makeCondition("organizationPartyId", organizationPartyId),
                    EntityCondition.makeCondition("organizationPartyId", DEFAULT_ORG)
            ));
        } else {
            conditions.add(EntityCondition.makeCondition("organizationPartyId", DEFAULT_ORG));
        }

        List<GenericValue> policies = EntityQuery.use(delegator)
                .from("LoanReconciliationIssuePolicy")
                .where(EntityCondition.makeCondition(conditions, EntityOperator.AND))
                .orderBy("organizationPartyId", "-fromDate", "policyId")
                .queryList();

        if (UtilValidate.isEmpty(policies)) {
            return null;
        }
        if (UtilValidate.isNotEmpty(organizationPartyId)) {
            for (GenericValue policy : policies) {
                if (organizationPartyId.equals(policy.getString("organizationPartyId"))) {
                    return policy;
                }
            }
        }
        return policies.get(0);
    }

    private static EntityCondition activePolicyCondition() {
        Timestamp now = UtilDateTime.nowTimestamp();
        return EntityCondition.makeCondition(EntityOperator.AND,
                EntityCondition.makeCondition(EntityOperator.OR,
                        EntityCondition.makeCondition("isActive", null),
                        EntityCondition.makeCondition("isActive", "Y")),
                EntityCondition.makeCondition(EntityOperator.OR,
                        EntityCondition.makeCondition("fromDate", null),
                        EntityCondition.makeCondition("fromDate", EntityOperator.LESS_THAN_EQUAL_TO, now)),
                EntityCondition.makeCondition(EntityOperator.OR,
                        EntityCondition.makeCondition("thruDate", null),
                        EntityCondition.makeCondition("thruDate", EntityOperator.GREATER_THAN, now))
        );
    }

    private static Map<String, Object> buildEligibilityMap(GenericValue policy, String reconciliationIssueId,
            String organizationPartyId, String issueTypeEnumId, String severityEnumId) {
        Map<String, Object> payload = new LinkedHashMap<String, Object>();
        payload.put("reconciliationIssueId", reconciliationIssueId);
        payload.put("organizationPartyId", organizationPartyId);
        payload.put("issueTypeEnumId", issueTypeEnumId);
        payload.put("severityEnumId", severityEnumId);

        if (policy == null) {
            payload.put("policyFound", "N");
            payload.put("repairEligibilityStatus", "MANUAL_REVIEW_REQUIRED");
            payload.put("autoRepairAllowed", "N");
            payload.put("manualApprovalRequired", "Y");
            payload.put("blockBatchRepair", "Y");
            payload.put("requiresDryRun", "Y");
            payload.put("requiresBackupReview", "Y");
            payload.put("riskLevelEnumId", "RECON_RISK_HIGH");
            payload.put("recommendedAction", "No active governance policy found. Manual review is required before repair.");
            return payload;
        }

        String autoRepairAllowed = normalizeFlag(policy.getString("autoRepairAllowed"));
        String manualApprovalRequired = normalizeFlag(policy.getString("manualApprovalRequired"));
        String blockBatchRepair = normalizeFlag(policy.getString("blockBatchRepair"));
        String requiresDryRun = normalizeFlag(policy.getString("requiresDryRun"));
        String requiresBackupReview = normalizeFlag(policy.getString("requiresBackupReview"));

        String status;
        if ("Y".equals(blockBatchRepair)) {
            status = "BLOCKED";
        } else if ("Y".equals(autoRepairAllowed) && "N".equals(manualApprovalRequired)) {
            status = "AUTO_REPAIR_CANDIDATE";
        } else if ("Y".equals(manualApprovalRequired)) {
            status = "APPROVAL_REQUIRED";
        } else {
            status = "REVIEW_ONLY";
        }

        payload.put("policyFound", "Y");
        payload.put("repairEligibilityStatus", status);
        payload.put("policy", policyToMap(policy));
        payload.put("policyId", policy.getString("policyId"));
        payload.put("autoRepairAllowed", autoRepairAllowed);
        payload.put("manualApprovalRequired", manualApprovalRequired);
        payload.put("blockBatchRepair", blockBatchRepair);
        payload.put("requiresDryRun", requiresDryRun);
        payload.put("requiresBackupReview", requiresBackupReview);
        payload.put("riskLevelEnumId", policy.getString("riskLevelEnumId"));
        payload.put("repairServiceName", policy.getString("repairServiceName"));
        payload.put("recommendedAction", policy.getString("recommendedAction"));
        return payload;
    }

    private static Map<String, Object> policyToMap(GenericValue row) {
        Map<String, Object> map = new LinkedHashMap<String, Object>();
        map.put("policyId", row.getString("policyId"));
        map.put("organizationPartyId", row.getString("organizationPartyId"));
        map.put("issueTypeEnumId", row.getString("issueTypeEnumId"));
        map.put("severityEnumId", row.getString("severityEnumId"));
        map.put("riskLevelEnumId", row.getString("riskLevelEnumId"));
        map.put("autoRepairAllowed", row.getString("autoRepairAllowed"));
        map.put("manualApprovalRequired", row.getString("manualApprovalRequired"));
        map.put("blockBatchRepair", row.getString("blockBatchRepair"));
        map.put("requiresDryRun", row.getString("requiresDryRun"));
        map.put("requiresBackupReview", row.getString("requiresBackupReview"));
        map.put("repairServiceName", row.getString("repairServiceName"));
        map.put("recommendedAction", row.getString("recommendedAction"));
        map.put("description", row.getString("description"));
        map.put("isActive", row.getString("isActive"));
        map.put("fromDate", row.getTimestamp("fromDate"));
        map.put("thruDate", row.getTimestamp("thruDate"));
        return map;
    }

    private static Map<String, Object> issueToMap(GenericValue row) {
        Map<String, Object> map = new LinkedHashMap<String, Object>();
        map.put("reconciliationIssueId", row.getString("reconciliationIssueId"));
        map.put("reconciliationRunId", row.getString("reconciliationRunId"));
        map.put("severityEnumId", row.getString("severityEnumId"));
        map.put("issueTypeEnumId", row.getString("issueTypeEnumId"));
        map.put("organizationPartyId", row.getString("organizationPartyId"));
        map.put("loanId", row.getString("loanId"));
        map.put("loanScheduleId", row.getString("loanScheduleId"));
        map.put("loanRepaymentId", row.getString("loanRepaymentId"));
        map.put("paymentId", row.getString("paymentId"));
        map.put("acctgTransId", row.getString("acctgTransId"));
        map.put("expectedAmount", row.getBigDecimal("expectedAmount"));
        map.put("actualAmount", row.getBigDecimal("actualAmount"));
        map.put("currencyUomId", row.getString("currencyUomId"));
        map.put("description", row.getString("description"));
        map.put("createdStamp", row.getTimestamp("createdStamp"));
        return map;
    }

    private static Map<String, Object> buildSuccess(String message, Map<String, Object> payload) {
        Map<String, Object> result = ServiceUtil.returnSuccess(message);
        result.putAll(payload);
        result.put("data", new LinkedHashMap<String, Object>(payload));
        result.put("legacyData", new LinkedHashMap<String, Object>(payload));
        return result;
    }

    private static Integer toInteger(Object value) {
        if (value instanceof Integer) return (Integer) value;
        if (value instanceof Number) return Integer.valueOf(((Number) value).intValue());
        if (value != null) {
            try { return Integer.valueOf(value.toString()); } catch (Exception ignored) {}
        }
        return null;
    }

    private static String normalizeFlagDefaultY(String value) {
        return "N".equalsIgnoreCase(value) ? "N" : "Y";
    }

    private static String normalizeFlag(String value) {
        return "Y".equalsIgnoreCase(value) ? "Y" : "N";
    }

    private static String firstNonEmpty(String first, String second) {
        return UtilValidate.isNotEmpty(first) ? first : second;
    }

    private static String trimToNull(String value) {
        if (value == null) return null;
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }
}
