package org.apache.ofbiz.payroll.services;

import org.apache.ofbiz.base.util.UtilValidate;
import org.apache.ofbiz.entity.Delegator;
import org.apache.ofbiz.entity.GenericValue;
import org.apache.ofbiz.entity.model.ModelEntity;
import org.apache.ofbiz.entity.util.EntityQuery;

import java.sql.Timestamp;
import java.util.*;

public final class PayrollStrictRules {

    private PayrollStrictRules() {}

    // ==========================
    // Status IDs (configurable)
    // ==========================
    // If you want, read these from a properties file later.
    public static final String RUN_STATUS_DRAFT      = "PAY_RUN_DRAFT";
    public static final String RUN_STATUS_CALCULATED = "PAY_RUN_CALC";
    public static final String RUN_STATUS_APPROVED   = "PAY_RUN_APPR";
    public static final String RUN_STATUS_POSTED     = "PAY_RUN_POSTED";

    public static final String RESULT_STATUS_NEW     = "PAY_RES_NEW";
    public static final String RESULT_STATUS_LOCKED  = "PAY_RES_LOCK";

    // Result line types
    public static final String LINE_TYPE_BASIC   = "PAY_LINE_BASIC";
    public static final String LINE_TYPE_ALLOW   = "PAY_LINE_ALLOW";
    public static final String LINE_TYPE_DEDUCT  = "PAY_LINE_DEDUCT";
    public static final String LINE_TYPE_LOAN    = "PAY_LINE_LOAN";
    public static final String LINE_TYPE_TAX     = "PAY_LINE_TAX";
    public static final String LINE_TYPE_SS      = "PAY_LINE_SS";

    // ==========================
    // Multi-tenant enforcement
    // ==========================
    // Entities that MUST carry orgPartyId for tenant isolation.
    public static final Set<String> TENANT_ENTITIES = Set.of(
        "PayrollOrganization",
        "PayrollCurrencyRate",
        "PayrollPeriod",
        "PayrollRun",
        "EmployeePayrollAssignment",
        "EmployeeAllowance",
        "EmployeeDeduction",
        "EmployeeLoan",
        "PayrollAuditLog",
        "BankTransferBatch",
        "BankTransferBatchLine",
        "EmployeePayrollResult",
        "EmployeePayrollResultLine",
        "EmployeeTaxDetail",
        "EmployeeYTDSummary",
        "PayrollExportRequest",
        "PayrollExportFile",
        "PayrollEmailQueue",
        "PayrollSmsQueue",
        "EmployeeDocumentAccess"
    );

    // ===========
    // Validation
    // ===========
    public static void requireNotEmpty(String field, Object value) {
        if (value == null) throw new IllegalArgumentException(field + " is required");
        if (value instanceof String && UtilValidate.isEmpty((String) value)) {
            throw new IllegalArgumentException(field + " is required");
        }
    }

    public static boolean entityHasField(Delegator delegator, String entityName, String fieldName) {
        try {
            ModelEntity me = delegator.getModelEntity(entityName);
            return me != null && me.isField(fieldName);
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * Strict tenant rule:
     * - If entity requires orgPartyId, then orgPartyId must be present in service context.
     * - On create/update/list/find/delete we enforce it.
     */
    public static void enforceTenant(Delegator delegator, String entityName, Map<String, Object> fieldsOrPkOrFilters, String orgPartyId) {
        if (!TENANT_ENTITIES.contains(entityName)) return;

        requireNotEmpty("orgPartyId", orgPartyId);

        // If entity actually has orgPartyId field, we set/require it.
        if (entityHasField(delegator, entityName, "orgPartyId")) {
            Object existing = fieldsOrPkOrFilters.get("orgPartyId");
            if (existing != null && !orgPartyId.equals(existing.toString())) {
                throw new IllegalArgumentException("Tenant violation: orgPartyId mismatch for " + entityName);
            }
            fieldsOrPkOrFilters.put("orgPartyId", orgPartyId);
        }
    }

    // ==============================
    // Uniqueness rules (runtime)
    // ==============================

    /**
     * Enforce unique run per (orgPartyId, payrollPeriodId, runType) if your model supports it.
     * If you do not have runType, it enforces per (orgPartyId, payrollPeriodId).
     */
    public static void ensureUniquePayrollRun(Delegator delegator, String orgPartyId, String payrollPeriodId, String runType, String currentRunId) throws Exception {
        requireNotEmpty("orgPartyId", orgPartyId);
        requireNotEmpty("payrollPeriodId", payrollPeriodId);

        EntityQuery q = EntityQuery.use(delegator).from("PayrollRun")
            .where("orgPartyId", orgPartyId, "payrollPeriodId", payrollPeriodId);

        if (UtilValidate.isNotEmpty(runType) && entityHasField(delegator, "PayrollRun", "runTypeId")) {
            q = q.where("runTypeId", runType);
        }

        List<GenericValue> existing = q.queryList();
        for (GenericValue gv : existing) {
            String id = gv.getString("payrollRunId");
            if (currentRunId == null || !currentRunId.equals(id)) {
                throw new IllegalArgumentException("Duplicate PayrollRun for period/org (and runType). Existing payrollRunId=" + id);
            }
        }
    }

    /**
     * Enforce unique result per (payrollRunId, partyId).
     * Done in calculate to avoid duplicates even without DB unique index.
     */
    public static void ensureUniqueEmployeeResult(Delegator delegator, String payrollRunId, String partyId) throws Exception {
        GenericValue existing = EntityQuery.use(delegator).from("EmployeePayrollResult")
            .where("payrollRunId", payrollRunId, "partyId", partyId)
            .queryFirst();
        if (existing != null) {
            throw new IllegalArgumentException("Duplicate EmployeePayrollResult for run/party. payrollResultId=" + existing.getString("payrollResultId"));
        }
    }

    // ==========================
    // Effective dating helpers
    // ==========================
    public static boolean isActiveInPeriod(Timestamp effectiveDate, Timestamp thruDate, Timestamp periodStart, Timestamp periodEnd) {
        if (effectiveDate != null && effectiveDate.after(periodEnd)) return false;
        if (thruDate != null && thruDate.before(periodStart)) return false;
        return true;
    }

public static String getResultLinePkFieldName(Delegator delegator) {
    if (entityHasField(delegator, "EmployeePayrollResultLine", "resultLineId")) return "resultLineId";
    if (entityHasField(delegator, "EmployeePayrollResultLine", "employeePayrollResultLineId")) return "employeePayrollResultLineId";
    return "resultLineId";
}

}
