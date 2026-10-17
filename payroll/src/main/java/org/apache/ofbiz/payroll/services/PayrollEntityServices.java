package org.apache.ofbiz.payroll.services;

import org.apache.ofbiz.base.util.Debug;
import org.apache.ofbiz.base.util.UtilDateTime;
import org.apache.ofbiz.base.util.UtilValidate;
import org.apache.ofbiz.entity.Delegator;
import org.apache.ofbiz.entity.GenericEntityException;
import org.apache.ofbiz.entity.GenericValue;
import org.apache.ofbiz.entity.model.ModelEntity;
import org.apache.ofbiz.entity.transaction.GenericTransactionException;
import org.apache.ofbiz.entity.transaction.TransactionUtil;
import org.apache.ofbiz.entity.util.EntityQuery;
import org.apache.ofbiz.service.DispatchContext;
import org.apache.ofbiz.service.ServiceUtil;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Payroll entity CRUD services.
 * - Generic CRUD methods (createEntity/updateEntity/deleteEntity/findEntity/listEntity)
 * - Thin wrappers per entity: createX/updateX/deleteX/findX/listX
 */
public class PayrollEntityServices {

    public static final String MODULE = PayrollEntityServices.class.getName();

    // ---- Entity whitelist for safety (prevents calling arbitrary entities) ----
    private static final Set<String> ALLOWED_ENTITIES = Set.of(
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
            "PayrollComplianceReportLine"
    );

    // =========================================================
    // Generic CRUD
    // =========================================================

    public static Map<String, Object> createEntity(DispatchContext dctx, Map<String, ?> context) {
        Delegator delegator = dctx.getDelegator();
        Map<String, Object> result = ServiceUtil.returnSuccess();

        String entityName = (String) context.get("entityName");
        Map<String, Object> fields = castMap(context.get("fields"));

        if (!isAllowedEntity(entityName)) {
            return ServiceUtil.returnError("Entity not allowed: " + entityName);
        }
        if (fields == null) {
            return ServiceUtil.returnError("fields map is required");
        }

        boolean beganTx = false;
        try {
            beganTx = TransactionUtil.begin();

            GenericValue value = delegator.makeValue(entityName);
            sanitizeAndSetFields(delegator, entityName, value, fields);

            // Ensure PK present; if not, set first PK with delegator.getNextSeqId(entity)
            setMissingPrimaryKeyIfPossible(delegator, entityName, value);

            value.create();

            String recordId = firstPrimaryKeyValue(delegator, entityName, value);
            result.put("recordId", recordId);

            safeCommit(beganTx);
            return result;
        } catch (Exception e) {
            safeRollback(beganTx, e);
            Debug.logError(e, "createEntity failed for " + entityName, MODULE);
            return ServiceUtil.returnError("createEntity failed for " + entityName + ": " + e.getMessage());
        }
    }

    public static Map<String, Object> updateEntity(DispatchContext dctx, Map<String, ?> context) {
        Delegator delegator = dctx.getDelegator();

        String entityName = (String) context.get("entityName");
        Map<String, Object> pk = castMap(context.get("pk"));
        Map<String, Object> fields = castMap(context.get("fields"));

        if (!isAllowedEntity(entityName)) {
            return ServiceUtil.returnError("Entity not allowed: " + entityName);
        }

        // Support GET/query-param usage: build PK from context if pk map missing
        if (pk == null || pk.isEmpty()) {
            pk = extractPk(context, delegator, entityName);
        }
        if (pk == null || pk.isEmpty()) {
            return ServiceUtil.returnError("pk is required (either pk map or PK fields as query parameters)");
        }

        if (fields == null) {
            return ServiceUtil.returnError("fields map is required");
        }

        boolean beganTx = false;
        try {
            beganTx = TransactionUtil.begin();

            GenericValue existing = EntityQuery.use(delegator)
                    .from(entityName)
                    .where(pk)
                    .queryOne();

            if (existing == null) {
                return ServiceUtil.returnError("Record not found for update: " + entityName + " pk=" + pk);
            }

            sanitizeAndSetFields(delegator, entityName, existing, fields);
            existing.store();

            safeCommit(beganTx);
            return ServiceUtil.returnSuccess();
        } catch (Exception e) {
            safeRollback(beganTx, e);
            Debug.logError(e, "updateEntity failed for " + entityName, MODULE);
            return ServiceUtil.returnError("updateEntity failed for " + entityName + ": " + e.getMessage());
        }
    }

    public static Map<String, Object> deleteEntity(DispatchContext dctx, Map<String, ?> context) {
        Delegator delegator = dctx.getDelegator();

        String entityName = (String) context.get("entityName");
        Map<String, Object> pk = castMap(context.get("pk"));

        if (!isAllowedEntity(entityName)) {
            return ServiceUtil.returnError("Entity not allowed: " + entityName);
        }

        // Support GET/query-param usage
        if (pk == null || pk.isEmpty()) {
            pk = extractPk(context, delegator, entityName);
        }
        if (pk == null || pk.isEmpty()) {
            return ServiceUtil.returnError("pk is required (either pk map or PK fields as query parameters)");
        }

        boolean beganTx = false;
        try {
            beganTx = TransactionUtil.begin();

            GenericValue existing = EntityQuery.use(delegator)
                    .from(entityName)
                    .where(pk)
                    .queryOne();

            if (existing == null) {
                return ServiceUtil.returnError("Record not found for delete: " + entityName + " pk=" + pk);
            }

            existing.remove();
            safeCommit(beganTx);
            return ServiceUtil.returnSuccess();
        } catch (Exception e) {
            safeRollback(beganTx, e);
            Debug.logError(e, "deleteEntity failed for " + entityName, MODULE);
            return ServiceUtil.returnError("deleteEntity failed for " + entityName + ": " + e.getMessage());
        }
    }

    public static Map<String, Object> findEntity(DispatchContext dctx, Map<String, ?> context) {
        Delegator delegator = dctx.getDelegator();

        String entityName = (String) context.get("entityName");
        Map<String, Object> pk = castMap(context.get("pk"));

        if (!isAllowedEntity(entityName)) {
            return ServiceUtil.returnError("Entity not allowed: " + entityName);
        }

        // Support GET/query-param usage
        if (pk == null || pk.isEmpty()) {
            pk = extractPk(context, delegator, entityName);
        }
        if (pk == null || pk.isEmpty()) {
            return ServiceUtil.returnError("pk is required (either pk map or PK fields as query parameters)");
        }

        try {
            GenericValue gv = EntityQuery.use(delegator)
                    .from(entityName)
                    .where(pk)
                    .queryOne();

            Map<String, Object> out = ServiceUtil.returnSuccess();
            out.put("record", (gv == null) ? null : gv.getAllFields());
            return out;
        } catch (Exception e) {
            Debug.logError(e, "findEntity failed for " + entityName, MODULE);
            return ServiceUtil.returnError("findEntity failed for " + entityName + ": " + e.getMessage());
        }
    }

    public static Map<String, Object> listEntity(DispatchContext dctx, Map<String, ?> context) {
        Delegator delegator = dctx.getDelegator();

        String entityName = (String) context.get("entityName");
        Map<String, Object> filters = castMap(context.get("filters"));
        List<String> orderBy = castList(context.get("orderBy"));

        Integer viewSize = (Integer) context.get("viewSize");
        Integer viewIndex = (Integer) context.get("viewIndex");

        if (!isAllowedEntity(entityName)) {
            return ServiceUtil.returnError("Entity not allowed: " + entityName);
        }

        try {
            EntityQuery eq = EntityQuery.use(delegator).from(entityName);

            if (filters != null && !filters.isEmpty()) {
                eq = eq.where(filters);
            }
            if (orderBy != null && !orderBy.isEmpty()) {
                eq = eq.orderBy(orderBy);
            }

            int size = (viewSize == null || viewSize <= 0) ? 50 : viewSize;
            int index = (viewIndex == null || viewIndex < 0) ? 0 : viewIndex;
            int offset = index * size;

            List<GenericValue> list = eq.queryList();
            int total = list.size();

            List<Map<String, Object>> page = new ArrayList<>();
            for (int i = offset; i < Math.min(offset + size, total); i++) {
                page.add(list.get(i).getAllFields());
            }

            Map<String, Object> out = ServiceUtil.returnSuccess();
            out.put("list", page);
            out.put("listSize", total);
            return out;
        } catch (Exception e) {
            Debug.logError(e, "listEntity failed for " + entityName, MODULE);
            return ServiceUtil.returnError("listEntity failed for " + entityName + ": " + e.getMessage());
        }
    }

    // =========================================================
    // Per-entity wrappers (example only – add others similarly)
    // =========================================================

    public static Map<String, Object> deletePayrollOrganization(DispatchContext dctx, Map<String, ?> context) {
        return deleteWrap(dctx, context, "PayrollOrganization");
    }

    // =========================================================
    // Wrapper helpers
    // =========================================================

    private static Map<String, Object> createWrap(DispatchContext dctx, Map<String, ?> context, String entityName) {
        Map<String, Object> ctx = new HashMap<>();
        ctx.put("entityName", entityName);
        ctx.put("fields", extractFields(context, entityName));
        return createEntity(dctx, ctx);
    }

    private static Map<String, Object> updateWrap(DispatchContext dctx, Map<String, ?> context, String entityName) {
        Map<String, Object> ctx = new HashMap<>();
        ctx.put("entityName", entityName);
        ctx.put("pk", extractPk(context, dctx.getDelegator(), entityName));
        ctx.put("fields", extractFields(context, entityName));
        return updateEntity(dctx, ctx);
    }

    private static Map<String, Object> deleteWrap(DispatchContext dctx, Map<String, ?> context, String entityName) {
        Map<String, Object> ctx = new HashMap<>();
        ctx.put("entityName", entityName);
        ctx.put("pk", extractPk(context, dctx.getDelegator(), entityName));
        return deleteEntity(dctx, ctx);
    }

    private static Map<String, Object> findWrap(DispatchContext dctx, Map<String, ?> context, String entityName) {
        Map<String, Object> ctx = new HashMap<>();
        ctx.put("entityName", entityName);
        ctx.put("pk", extractPk(context, dctx.getDelegator(), entityName));
        return findEntity(dctx, ctx);
    }

    private static Map<String, Object> listWrap(DispatchContext dctx, Map<String, ?> context, String entityName) {
        Map<String, Object> ctx = new HashMap<>();
        ctx.put("entityName", entityName);
        ctx.put("filters", extractFilters(context));
        ctx.put("orderBy", context.get("orderBy"));
        ctx.put("viewSize", context.get("viewSize"));
        ctx.put("viewIndex", context.get("viewIndex"));
        return listEntity(dctx, ctx);
    }

    // =========================================================
    // Internals
    // =========================================================

    private static boolean isAllowedEntity(String entityName) {
        return UtilValidate.isNotEmpty(entityName) && ALLOWED_ENTITIES.contains(entityName);
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> castMap(Object o) {
        if (o == null) return null;
        if (o instanceof Map) return (Map<String, Object>) o;
        return null;
    }

    @SuppressWarnings("unchecked")
    private static List<String> castList(Object o) {
        if (o == null) return null;
        if (o instanceof List) return (List<String>) o;
        return null;
    }

    private static void sanitizeAndSetFields(Delegator delegator, String entityName, GenericValue target,
                                             Map<String, Object> fields) throws GenericEntityException {
        ModelEntity me = delegator.getModelEntity(entityName);
        for (Map.Entry<String, Object> e : fields.entrySet()) {
            String fieldName = e.getKey();
            if (!me.isField(fieldName)) {
                continue; // ignore unknown
            }
            if (me.getPkFieldNames().contains(fieldName) && e.getValue() == null) {
                continue; // don't null PK
            }
            target.set(fieldName, e.getValue());
        }

        if (me.isField("createdDate") && target.get("createdDate") == null) {
            target.set("createdDate", UtilDateTime.nowTimestamp());
        }
    }

    private static void setMissingPrimaryKeyIfPossible(Delegator delegator, String entityName, GenericValue value)
            throws GenericEntityException {

        ModelEntity me = delegator.getModelEntity(entityName);
        List<String> pks = me.getPkFieldNames();
        if (pks == null || pks.isEmpty()) return;

        if (pks.size() == 1) {
            String pkField = pks.get(0);
            if (value.get(pkField) == null) {
                value.set(pkField, delegator.getNextSeqId(entityName));
            }
        }
    }

    private static String firstPrimaryKeyValue(Delegator delegator, String entityName, GenericValue value)
            throws GenericEntityException {

        ModelEntity me = delegator.getModelEntity(entityName);
        List<String> pks = me.getPkFieldNames();
        if (pks == null || pks.isEmpty()) return null;
        Object v = value.get(pks.get(0));
        return (v == null) ? null : String.valueOf(v);
    }

    private static Map<String, Object> extractFields(Map<String, ?> context, String entityName) {
        Map<String, Object> out = new HashMap<>();

        Object fieldsObj = context.get("fields");
        if (fieldsObj instanceof Map) {
            Map<String, Object> fields = castMap(fieldsObj);
            if (fields != null) out.putAll(fields);
            return out;
        }

        for (Map.Entry<String, ?> e : context.entrySet()) {
            String k = e.getKey();
            if ("userLogin".equals(k) || "locale".equals(k) || "timeZone".equals(k)
                    || "entityName".equals(k) || "pk".equals(k) || "filters".equals(k)
                    || "orderBy".equals(k) || "viewSize".equals(k) || "viewIndex".equals(k)) {
                continue;
            }
            out.put(k, e.getValue());
        }
        return out;
    }

    private static Map<String, Object> extractPk(Map<String, ?> context, Delegator delegator, String entityName) {
        Object pkObj = context.get("pk");
        if (pkObj instanceof Map) return castMap(pkObj);

        Map<String, Object> pk = new HashMap<>();
        try {
            ModelEntity me = delegator.getModelEntity(entityName);
            for (String pkField : me.getPkFieldNames()) {
                Object v = context.get(pkField);
                if (v != null) pk.put(pkField, v);
            }
        } catch (Exception ignored) {
            // ignore
        }
        return pk;
    }

    private static Map<String, Object> extractFilters(Map<String, ?> context) {
        Object filtersObj = context.get("filters");
        if (filtersObj instanceof Map) return castMap(filtersObj);

        // Minimal default filter extraction; callers should pass filters map when needed.
        return null;
    }

    private static void safeRollback(boolean beganTx, Exception cause) {
        try {
            TransactionUtil.rollback(beganTx, cause.getMessage(), cause);
        } catch (GenericTransactionException gte) {
            Debug.logError(gte, "Rollback failed (ignored)", MODULE);
        }
    }

    private static void safeCommit(boolean beganTx) {
        try {
            TransactionUtil.commit(beganTx);
        } catch (GenericTransactionException gte) {
            Debug.logError(gte, "Commit failed (ignored)", MODULE);
        }
    }
}
