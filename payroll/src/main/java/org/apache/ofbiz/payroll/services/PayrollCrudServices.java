package org.apache.ofbiz.payroll.services;

import org.apache.ofbiz.base.util.Debug;
import org.apache.ofbiz.base.util.UtilValidate;

import org.apache.ofbiz.entity.Delegator;
import org.apache.ofbiz.entity.GenericEntityException;
import org.apache.ofbiz.entity.GenericValue;
import org.apache.ofbiz.entity.model.ModelEntity;
import org.apache.ofbiz.entity.model.ModelField;
import org.apache.ofbiz.entity.util.EntityQuery;

import org.apache.ofbiz.service.DispatchContext;
import org.apache.ofbiz.service.ServiceUtil;

import java.util.*;
import java.util.stream.Collectors;

public class PayrollCrudServices {
    public static final String MODULE = PayrollCrudServices.class.getName();

    /**
     * Allowlist of payroll entities allowed via generic CRUD.
     * This prevents someone calling payrollCreateEntity for "UserLogin" etc.
     */
  /**
 * Allowlist of payroll entities allowed via generic CRUD.
 * Prevents calling generic CRUD on non-payroll entities (e.g., UserLogin).
 *
 * IMPORTANT:
 * - Must match entitymodel XML entity-name values exactly.
 * - Multi-org enforced using organizationPartyId (NOT orgPartyId).
 */
private static final Set<String> ALLOWED_ENTITIES =
        Collections.unmodifiableSet(new HashSet<>(Arrays.asList(
                "PayrollOrganization",
                "PayrollCurrencyRate",
                "PayrollStructure",
                "EmployeePayrollAssignment",
                "InternationalEmployeeInfo",
                "OvertimePolicy",
                "HrPolicySet",
                "HrPolicy",
                "StatutoryRule",
                "PayrollTaxConfig",
                "IncomeTaxBracket",
                "SocialSecurityConfig",
                "EmployeeTaxProfile",
                "AllowanceType",
                "EmployeeAllowance",
                "DeductionType",
                "EmployeeDeduction",
                "EmployeeLoan",
                "LoanInstallment",
                "PayrollPeriodType",
                "PayrollPeriod",
                "PayrollRun",
                "EmployeePayrollResult",
                "PayrollDeductionItem",
                "EmployeePayrollResultLine",
                "EmployeeTaxDetail",
                "EmployeeYTDSummary",
                "PayrollRetroBatch",
                "PayrollRetroEmployee",
                "PayrollRetroDelta",
                "PayrollRetroPosting",
                "PayrollPosting",
                "PayrollPostingLine",
                "PayrollGlMapping",
                "PayrollInvoiceMapping",
                "PayrollExportRequest",
                "PayrollExportFile",
                "PayslipBarcode",
                "PayslipDelivery",
                "PayslipDeliveryLog",
                "PayslipAccessToken",
                "PayslipDownloadAudit",
                "EmployeeDocumentAccess",
                "BankTransferConfig",
                "BankTransferBatch",
                "BankTransferBatchLine",
                "PayrollEmailQueue",
                "PayrollSmsQueue",
                "PayrollAuditLog",
                "PayrollAuditEvent",
                "PayrollComplianceReport",
                "PayrollComplianceReportLine",
				"PayrollComplianceReport",
"PayrollComplianceReportLine",

// Phase #1 entities
"EmployeeOvertimeRequest",
"PayrollInputSnapshot",
"PayrollStructureItem",
"PayrollStructureMember",
"EmployeeAbsenceRequest",
"EmployeeLeaveRequest"
        )));


    private static boolean hasOrgField(ModelEntity me) {
        return me != null && me.getField("organizationPartyId") != null;
    }

    private static void assertAllowedEntity(String entityName) {
        if (UtilValidate.isEmpty(entityName) || !ALLOWED_ENTITIES.contains(entityName)) {
            throw new IllegalArgumentException("Entity not allowed for payroll generic CRUD: " + entityName);
        }
    }

    private static Map<String, Object> sanitizeFields(Delegator delegator, String entityName, Map<String, ?> raw)
            throws GenericEntityException {

        ModelEntity me = delegator.getModelEntity(entityName);
        if (me == null) {
            throw new GenericEntityException("Unknown entity: " + entityName);
        }

        Map<String, Object> out = new HashMap<>();
        if (raw == null) return out;

        for (Map.Entry<String, ?> e : raw.entrySet()) {
            String fieldName = e.getKey();
            Object value = e.getValue();

            ModelField mf = me.getField(fieldName);
            if (mf == null) continue; // ignore unknown fields

            // prevent overriding OFBiz audit stamps manually
            if ("createdStamp".equals(fieldName) || "createdTxStamp".equals(fieldName)
                    || "lastUpdatedStamp".equals(fieldName) || "lastUpdatedTxStamp".equals(fieldName)) {
                continue;
            }

            out.put(fieldName, value);
        }
        return out;
    }

    private static void enforceOrgScope(Delegator delegator, String entityName, String organizationPartyId, Map<String, Object> fieldsOrPk)
            throws GenericEntityException {

        ModelEntity me = delegator.getModelEntity(entityName);
        if (me == null) return;

        if (hasOrgField(me)) {
            if (UtilValidate.isEmpty(organizationPartyId)) {
                throw new GenericEntityException("organizationPartyId is required for entity " + entityName);
            }
            // Force organizationPartyId to the passed organizationPartyId (cannot be overridden)
            fieldsOrPk.put("organizationPartyId", organizationPartyId);
        }
    }

    private static GenericValue findOneStrict(Delegator delegator, String entityName, Map<String, Object> pk)
            throws GenericEntityException {

        GenericValue gv = EntityQuery.use(delegator)
                .from(entityName)
                .where(pk)
                .queryOne();

        if (gv == null) {
            throw new GenericEntityException("Record not found: " + entityName + " pk=" + pk);
        }
        return gv;
    }

    public static Map<String, Object> createEntity(DispatchContext dctx, Map<String, ?> context) {
        Delegator delegator = dctx.getDelegator();
        Map<String, Object> result = ServiceUtil.returnSuccess();

        try {
            String entityName = (String) context.get("entityName");
            String organizationPartyId = (String) context.get("organizationPartyId");
            @SuppressWarnings("unchecked")
            Map<String, ?> fieldsRaw = (Map<String, ?>) context.get("fields");

            assertAllowedEntity(entityName);

            Map<String, Object> fields = sanitizeFields(delegator, entityName, fieldsRaw);
            enforceOrgScope(delegator, entityName, organizationPartyId, fields);

            GenericValue value = delegator.makeValue(entityName, fields);

            // Generic PK generation when single-PK and empty
            ModelEntity me = delegator.getModelEntity(entityName);
            List<String> pkFields = me.getPkFieldNames();
            if (pkFields.size() == 1) {
                String pkField = pkFields.get(0);
                if (UtilValidate.isEmpty(value.get(pkField))) {
                    String seqId = delegator.getNextSeqId(entityName);
                    value.set(pkField, seqId);
                    result.put("resultId", seqId);
                } else {
                    result.put("resultId", String.valueOf(value.get(pkField)));
                }
            }

            value.create();
            return result;

        } catch (Exception e) {
            Debug.logError(e, MODULE);
            return ServiceUtil.returnError("payrollCreateEntity failed: " + e.getMessage());
        }
    }

    public static Map<String, Object> updateEntity(DispatchContext dctx, Map<String, ?> context) {
        Delegator delegator = dctx.getDelegator();

        try {
            String entityName = (String) context.get("entityName");
            String organizationPartyId = (String) context.get("organizationPartyId");
            @SuppressWarnings("unchecked")
            Map<String, ?> pkRaw = (Map<String, ?>) context.get("pk");
            @SuppressWarnings("unchecked")
            Map<String, ?> fieldsRaw = (Map<String, ?>) context.get("fields");

            assertAllowedEntity(entityName);

            Map<String, Object> pk = new HashMap<>();
            if (pkRaw != null) {
                for (Map.Entry<String, ?> e : pkRaw.entrySet()) {
                    pk.put(e.getKey(), e.getValue());
                }
            }
            enforceOrgScope(delegator, entityName, organizationPartyId, pk);

            GenericValue existing = findOneStrict(delegator, entityName, pk);

            Map<String, Object> fields = sanitizeFields(delegator, entityName, fieldsRaw);
            // never allow changing organizationPartyId
            fields.remove("organizationPartyId");

            existing.setNonPKFields(fields);
            existing.store();

            return ServiceUtil.returnSuccess();
        } catch (Exception e) {
            Debug.logError(e, MODULE);
            return ServiceUtil.returnError("payrollUpdateEntity failed: " + e.getMessage());
        }
    }

public static Map<String, Object> listEntity(DispatchContext dctx, Map<String, ?> context) {
    return list(dctx, context);
}


    public static Map<String, Object> deleteEntity(DispatchContext dctx, Map<String, ?> context) {
        Delegator delegator = dctx.getDelegator();

        try {
            String entityName = (String) context.get("entityName");
            String organizationPartyId = (String) context.get("organizationPartyId");
            @SuppressWarnings("unchecked")
            Map<String, ?> pkRaw = (Map<String, ?>) context.get("pk");

            assertAllowedEntity(entityName);

            Map<String, Object> pk = new HashMap<>();
            if (pkRaw != null) {
                for (Map.Entry<String, ?> e : pkRaw.entrySet()) {
                    pk.put(e.getKey(), e.getValue());
                }
            }
            enforceOrgScope(delegator, entityName, organizationPartyId, pk);

            GenericValue existing = findOneStrict(delegator, entityName, pk);
            existing.remove();

            return ServiceUtil.returnSuccess();
        } catch (Exception e) {
            Debug.logError(e, MODULE);
            return ServiceUtil.returnError("payrollDeleteEntity failed: " + e.getMessage());
        }
    }

    public static Map<String, Object> findOne(DispatchContext dctx, Map<String, ?> context) {
        Delegator delegator = dctx.getDelegator();
        Map<String, Object> out = ServiceUtil.returnSuccess();

        try {
            String entityName = (String) context.get("entityName");
            String organizationPartyId = (String) context.get("organizationPartyId");
            @SuppressWarnings("unchecked")
            Map<String, ?> pkRaw = (Map<String, ?>) context.get("pk");

            assertAllowedEntity(entityName);

            Map<String, Object> pk = new HashMap<>();
            if (pkRaw != null) {
                for (Map.Entry<String, ?> e : pkRaw.entrySet()) {
                    pk.put(e.getKey(), e.getValue());
                }
            }
            enforceOrgScope(delegator, entityName, organizationPartyId, pk);

            GenericValue existing = findOneStrict(delegator, entityName, pk);
            out.put("record", existing.getAllFields());
            return out;

        } catch (Exception e) {
            Debug.logError(e, MODULE);
            return ServiceUtil.returnError("payrollFindOne failed: " + e.getMessage());
        }
    }

    public static Map<String, Object> list(DispatchContext dctx, Map<String, ?> context) {
        Delegator delegator = dctx.getDelegator();
        Map<String, Object> out = ServiceUtil.returnSuccess();

        try {
            String entityName = (String) context.get("entityName");
            String organizationPartyId = (String) context.get("organizationPartyId");
            @SuppressWarnings("unchecked")
            Map<String, ?> filtersRaw = (Map<String, ?>) context.get("filters");
            @SuppressWarnings("unchecked")
            List<String> orderBy = (List<String>) context.get("orderBy");
            Integer viewIndex = (Integer) context.get("viewIndex");
            Integer viewSize = (Integer) context.get("viewSize");

            assertAllowedEntity(entityName);

            ModelEntity me = delegator.getModelEntity(entityName);

            Map<String, Object> filters = sanitizeFields(delegator, entityName, filtersRaw);
            if (filters == null) filters = new HashMap<>();

            // enforce org scope for list queries too
            if (hasOrgField(me)) {
                if (UtilValidate.isEmpty(organizationPartyId)) {
                    return ServiceUtil.returnError("organizationPartyId is required for list on " + entityName);
                }
                filters.put("organizationPartyId", organizationPartyId);
            }

            int idx = (viewIndex != null && viewIndex >= 0) ? viewIndex : 0;
            int size = (viewSize != null && viewSize > 0) ? viewSize : 50;

            long total = EntityQuery.use(delegator)
                    .from(entityName)
                    .where(filters)
                    .queryCount();

            // In OFBiz 24, queryList(EntityFindOptions) is not available.
            // Use maxRows + cursorScrollInsensitive then apply offset in-memory.
            int fetchMax = Math.min(Integer.MAX_VALUE - 1, (idx + 1) * size);

            EntityQuery eq = EntityQuery.use(delegator)
                    .from(entityName)
                    .where(filters)
                    .cursorScrollInsensitive()
                    .maxRows(fetchMax);

            if (orderBy != null && !orderBy.isEmpty()) {
                eq.orderBy(orderBy);
            }

            List<GenericValue> full = eq.queryList();

            int from = Math.min(idx * size, full.size());
            int to = Math.min(from + size, full.size());
            List<GenericValue> page = full.subList(from, to);

            List<Map<String, Object>> records = page.stream()
                    .map(GenericValue::getAllFields)
                    .collect(Collectors.toList());

            out.put("records", records);
            out.put("totalCount", total);
            out.put("viewIndex", idx);
            out.put("viewSize", size);
            return out;

        } catch (Exception e) {
            Debug.logError(e, MODULE);
            return ServiceUtil.returnError("payrollList failed: " + e.getMessage());
        }
    }
}
