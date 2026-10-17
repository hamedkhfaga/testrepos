package org.apache.ofbiz.payroll.services;

import org.apache.ofbiz.base.util.Debug;
import org.apache.ofbiz.base.util.UtilDateTime;
import org.apache.ofbiz.base.util.UtilMisc;
import org.apache.ofbiz.base.util.UtilValidate;

import org.apache.ofbiz.entity.Delegator;
import org.apache.ofbiz.entity.GenericValue;
import org.apache.ofbiz.entity.condition.EntityCondition;
import org.apache.ofbiz.entity.condition.EntityOperator;
import org.apache.ofbiz.entity.util.EntityQuery;

import org.apache.ofbiz.service.DispatchContext;
import org.apache.ofbiz.service.ServiceUtil;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.util.*;

/**
 * PayrollTxnItemServices
 *
 * Phase 2.5 Txn items:
 *  - Additions: PayrollAdditionItem (run-scoped, attach to EmployeePayrollResult)
 *  - Deductions: PayrollDeductionItem (NOT run-scoped in current schema, attach to EmployeePayrollResult)
 *
 * NOTE:
 *  PayrollDeductionItem per current entitymodel.xml fields:
 *    payrollDeductionItemId (PK)
 *    payrollResultId
 *    deductionTypeId
 *    amount
 *    partyId
 *    organizationPartyId
 *
 *  => No payrollRunId, no remarks. Do NOT write these fields.
 */
public class PayrollTxnItemServices {
    private static final String module = PayrollTxnItemServices.class.getName();

    // Line types used in EmployeePayrollResultLine (ensure they exist in your enums / UI expectation)
    private static final String RL_ADD = "PRL_ADD";
    private static final String RL_DED = "PRL_DED";

    // ============================================================
    // A) ADDITIONS (run-scoped)
    // ============================================================

    public static Map<String, Object> createPayrollAdditionItem(DispatchContext dctx, Map<String, ? extends Object> context) {
        Map<String, Object> result = baseResponse();
        Delegator delegator = dctx.getDelegator();

        String orgId = (String) context.get("organizationPartyId");
        String runId = (String) context.get("payrollRunId");
        String partyId = (String) context.get("partyId");
        String additionTypeId = (String) context.get("additionTypeId");
        BigDecimal amount = (BigDecimal) context.get("amount");
        String remarks = (String) context.get("remarks"); // PayrollAdditionItem supports remarks in your earlier code

        try {
            // validate required
            if (UtilValidate.isEmpty(orgId) || UtilValidate.isEmpty(runId) || UtilValidate.isEmpty(partyId)
                    || UtilValidate.isEmpty(additionTypeId) || amount == null) {
                return withError(result, "Missing required fields: organizationPartyId, payrollRunId, partyId, additionTypeId, amount");
            }
            if (amount.compareTo(BigDecimal.ZERO) == 0) {
                return withError(result, "Amount must not be zero.");
            }

            // validate AdditionType exists
            GenericValue addType = EntityQuery.use(delegator)
                    .from("AdditionType")
                    .where("additionTypeId", additionTypeId)
                    .queryOne();
            if (addType == null) {
                return withError(result, "Invalid additionTypeId: " + additionTypeId);
            }

            String id = delegator.getNextSeqId("PayrollAdditionItem");
            Timestamp now = UtilDateTime.nowTimestamp();

            GenericValue item = delegator.makeValue("PayrollAdditionItem", UtilMisc.toMap(
                    "payrollAdditionItemId", id,
                    "organizationPartyId", orgId,
                    "payrollRunId", runId,
                    "partyId", partyId,
                    "additionTypeId", additionTypeId,
                    "amount", amount,
                    "remarks", remarks,
                    "payrollResultId", null
            ));
            item.create();

            Map<String, Object> data = getData(result);
            data.put("payrollAdditionItemId", id);
            data.put("organizationPartyId", orgId);
            data.put("payrollRunId", runId);
            data.put("partyId", partyId);
            data.put("additionTypeId", additionTypeId);
            data.put("amount", amount);
            data.put("createdAt", now);

            mirrorLegacy(result);
            return result;

        } catch (Exception e) {
            Debug.logError(e, "createPayrollAdditionItem failed", module);
            return withError(result, "createPayrollAdditionItem failed: " + e.getMessage());
        }
    }

    public static Map<String, Object> listPayrollAdditionItems(DispatchContext dctx, Map<String, ? extends Object> context) {
        Map<String, Object> result = baseResponse();
        Delegator delegator = dctx.getDelegator();

        String orgId = (String) context.get("organizationPartyId");
        String runId = (String) context.get("payrollRunId");
        String partyId = (String) context.get("partyId");
        String onlyPending = (String) context.get("onlyPending");

        try {
            List<EntityCondition> conds = new ArrayList<>();
            if (UtilValidate.isNotEmpty(orgId)) conds.add(EntityCondition.makeCondition("organizationPartyId", orgId));
            if (UtilValidate.isNotEmpty(runId)) conds.add(EntityCondition.makeCondition("payrollRunId", runId));
            if (UtilValidate.isNotEmpty(partyId)) conds.add(EntityCondition.makeCondition("partyId", partyId));
            if ("Y".equalsIgnoreCase(onlyPending)) {
                conds.add(EntityCondition.makeCondition("payrollResultId", EntityOperator.EQUALS, null));
            }

            EntityQuery q = EntityQuery.use(delegator).from("PayrollAdditionItem");
            if (!conds.isEmpty()) {
                q.where(EntityCondition.makeCondition(conds, EntityOperator.AND));
            }

            List<GenericValue> rows = q.orderBy("payrollRunId", "partyId", "payrollAdditionItemId").queryList();

            List<Map<String, Object>> items = new ArrayList<>();
            for (GenericValue gv : rows) {
                Map<String, Object> m = new HashMap<>();
                m.put("payrollAdditionItemId", gv.getString("payrollAdditionItemId"));
                m.put("organizationPartyId", gv.getString("organizationPartyId"));
                m.put("payrollRunId", gv.getString("payrollRunId"));
                m.put("partyId", gv.getString("partyId"));
                m.put("additionTypeId", gv.getString("additionTypeId"));
                m.put("amount", gv.getBigDecimal("amount"));
                m.put("payrollResultId", gv.getString("payrollResultId"));
                m.put("remarks", gv.getString("remarks"));
                items.add(m);
            }

            Map<String, Object> data = getData(result);
            data.put("items", items);
            data.put("count", items.size());

            mirrorLegacy(result);
            return result;

        } catch (Exception e) {
            Debug.logError(e, "listPayrollAdditionItems failed", module);
            return withError(result, "listPayrollAdditionItems failed: " + e.getMessage());
        }
    }

    /**
     * Internal helper: attach pending additions to a result and create PRL_ADD lines.
     * Called from processPayrollRunFromSnapshotV2 after header created.
     */
    public static int attachPendingAdditionsToResult(Delegator delegator,
                                                     String organizationPartyId,
                                                     String payrollRunId,
                                                     String partyId,
                                                     String payrollResultId,
                                                     String currencyUomId,
                                                     long startSeq) throws Exception {

        List<GenericValue> pending = EntityQuery.use(delegator).from("PayrollAdditionItem")
                .where("organizationPartyId", organizationPartyId,
                        "payrollRunId", payrollRunId,
                        "partyId", partyId,
                        "payrollResultId", null)
                .orderBy("payrollAdditionItemId")
                .queryList();

        int attached = 0;
        for (GenericValue item : pending) {
            String additionTypeId = item.getString("additionTypeId");
            BigDecimal amt = nz(item.getBigDecimal("amount"));

            // attach to result
            item.set("payrollResultId", payrollResultId);
            item.store();

            // description
            GenericValue addType = EntityQuery.use(delegator).from("AdditionType")
                    .where("additionTypeId", additionTypeId).queryOne();
            String desc = (addType != null && UtilValidate.isNotEmpty(addType.getString("description")))
                    ? addType.getString("description")
                    : ("Addition " + additionTypeId);

            // line
            String lineId = delegator.getNextSeqId("EmployeePayrollResultLine");
            long seq = startSeq + attached;

            GenericValue line = delegator.makeValue("EmployeePayrollResultLine", UtilMisc.toMap(
                    "resultLineId", lineId,
                    "payrollResultId", payrollResultId,
                    "lineTypeId", RL_ADD,
                    "referenceId", item.getString("payrollAdditionItemId"),
                    "description", desc,
                    "amount", amt,
                    "currencyUomId", currencyUomId,
                    "sequenceNum", seq
            ));
            line.create();

            attached++;
        }
        return attached;
    }

    // ============================================================
    // B) DEDUCTIONS (current schema: NOT run-scoped)
    // ============================================================

    public static Map<String, Object> createPayrollDeductionItem(DispatchContext dctx, Map<String, ? extends Object> context) {
        Map<String, Object> result = baseResponse();
        Delegator delegator = dctx.getDelegator();

        String orgId = (String) context.get("organizationPartyId");
        String partyId = (String) context.get("partyId");
        String deductionTypeId = (String) context.get("deductionTypeId");
        BigDecimal amount = (BigDecimal) context.get("amount");

        try {
            if (UtilValidate.isEmpty(orgId) || UtilValidate.isEmpty(partyId)
                    || UtilValidate.isEmpty(deductionTypeId) || amount == null) {
                return withError(result, "Missing required fields: organizationPartyId, partyId, deductionTypeId, amount");
            }
            if (amount.compareTo(BigDecimal.ZERO) == 0) {
                return withError(result, "Amount must not be zero.");
            }
            if (amount.signum() < 0) {
                return withError(result, "Deduction amount must be positive.");
            }

            // validate DeductionType exists
            GenericValue dedType = EntityQuery.use(delegator)
                    .from("DeductionType")
                    .where("deductionTypeId", deductionTypeId)
                    .queryOne();
            if (dedType == null) {
                return withError(result, "Invalid deductionTypeId: " + deductionTypeId);
            }

            // Optional idempotency: do not create duplicates if there's already a pending row for same org/party/type
            long pendingExists = EntityQuery.use(delegator)
                    .from("PayrollDeductionItem")
                    .where("organizationPartyId", orgId,
                            "partyId", partyId,
                            "deductionTypeId", deductionTypeId,
                            "payrollResultId", null)
                    .queryCount();
            if (pendingExists > 0) {
                Map<String, Object> data = getData(result);
                data.put("created", false);
                data.put("skippedBecausePendingExists", true);
                data.put("organizationPartyId", orgId);
                data.put("partyId", partyId);
                data.put("deductionTypeId", deductionTypeId);
                data.put("amount", amount);
                mirrorLegacy(result);
                return result;
            }

            String id = delegator.getNextSeqId("PayrollDeductionItem");
            Timestamp now = UtilDateTime.nowTimestamp();

            GenericValue item = delegator.makeValue("PayrollDeductionItem", UtilMisc.toMap(
                    "payrollDeductionItemId", id,
                    "organizationPartyId", orgId,
                    "partyId", partyId,
                    "deductionTypeId", deductionTypeId,
                    "amount", amount,
                    "payrollResultId", null
            ));
            item.create();

            Map<String, Object> data = getData(result);
            data.put("payrollDeductionItemId", id);
            data.put("created", true);
            data.put("organizationPartyId", orgId);
            data.put("partyId", partyId);
            data.put("deductionTypeId", deductionTypeId);
            data.put("amount", amount);
            data.put("createdAt", now);

            mirrorLegacy(result);
            return result;

        } catch (Exception e) {
            Debug.logError(e, "createPayrollDeductionItem failed", module);
            return withError(result, "createPayrollDeductionItem failed: " + e.getMessage());
        }
    }

    public static Map<String, Object> listPayrollDeductionItems(DispatchContext dctx, Map<String, ? extends Object> context) {
        Map<String, Object> result = baseResponse();
        Delegator delegator = dctx.getDelegator();

        String orgId = (String) context.get("organizationPartyId");
        String partyId = (String) context.get("partyId");
        String deductionTypeId = (String) context.get("deductionTypeId");
        String onlyPending = (String) context.get("onlyPending");

        try {
            List<EntityCondition> conds = new ArrayList<>();
            if (UtilValidate.isNotEmpty(orgId)) conds.add(EntityCondition.makeCondition("organizationPartyId", orgId));
            if (UtilValidate.isNotEmpty(partyId)) conds.add(EntityCondition.makeCondition("partyId", partyId));
            if (UtilValidate.isNotEmpty(deductionTypeId)) conds.add(EntityCondition.makeCondition("deductionTypeId", deductionTypeId));
            if ("Y".equalsIgnoreCase(onlyPending)) {
                conds.add(EntityCondition.makeCondition("payrollResultId", EntityOperator.EQUALS, null));
            }

            EntityQuery q = EntityQuery.use(delegator).from("PayrollDeductionItem");
            if (!conds.isEmpty()) {
                q.where(EntityCondition.makeCondition(conds, EntityOperator.AND));
            }

            List<GenericValue> rows = q.orderBy("organizationPartyId", "partyId", "payrollDeductionItemId").queryList();

            List<Map<String, Object>> items = new ArrayList<>();
            for (GenericValue gv : rows) {
                Map<String, Object> m = new HashMap<>();
                m.put("payrollDeductionItemId", gv.getString("payrollDeductionItemId"));
                m.put("organizationPartyId", gv.getString("organizationPartyId"));
                m.put("partyId", gv.getString("partyId"));
                m.put("deductionTypeId", gv.getString("deductionTypeId"));
                m.put("amount", gv.getBigDecimal("amount"));
                m.put("payrollResultId", gv.getString("payrollResultId"));
                items.add(m);
            }

            Map<String, Object> data = getData(result);
            data.put("items", items);
            data.put("count", items.size());

            mirrorLegacy(result);
            return result;

        } catch (Exception e) {
            Debug.logError(e, "listPayrollDeductionItems failed", module);
            return withError(result, "listPayrollDeductionItems failed: " + e.getMessage());
        }
    }

    /**
     * Materializer service:
     * Create pending PayrollDeductionItem rows from EmployeeDeduction effective at "asOfDate".
     *
     * This solves your current issue where EmployeeDeduction exists but PayrollDeductionItem is empty.
     *
     * IN:
     *  - organizationPartyId (required)
     *  - partyId (optional: if empty => all employees in org)
     *  - asOfDate (optional: default now)
     *  - forceRebuild (optional Y/N): if Y => delete existing pending items first (payrollResultId is null)
     *
     * OUT:
     *  - createdCount, skippedCount, deletedCount, asOfDate
     */
    public static Map<String, Object> ensurePayrollDeductionItemsFromEmployeeDeduction(DispatchContext dctx, Map<String, ? extends Object> context) {
        Map<String, Object> result = baseResponse();
        Delegator delegator = dctx.getDelegator();

        String orgId = (String) context.get("organizationPartyId");
        String partyId = (String) context.get("partyId");
        String forceRebuild = normalizeFlag((String) context.get("forceRebuild"));

        Timestamp asOf = (Timestamp) context.get("asOfDate");
        if (asOf == null) {
            asOf = UtilDateTime.nowTimestamp();
        }

        if (UtilValidate.isEmpty(orgId)) {
            return withError(result, "organizationPartyId is required");
        }

        int created = 0;
        int skipped = 0;
        int deleted = 0;

        try {
            // Optional cleanup of pending items for org/party
            if ("Y".equals(forceRebuild)) {
                List<EntityCondition> delConds = new ArrayList<>();
                delConds.add(EntityCondition.makeCondition("organizationPartyId", orgId));
                delConds.add(EntityCondition.makeCondition("payrollResultId", EntityOperator.EQUALS, null));
                if (UtilValidate.isNotEmpty(partyId)) {
                    delConds.add(EntityCondition.makeCondition("partyId", partyId));
                }

                List<GenericValue> pend = EntityQuery.use(delegator).from("PayrollDeductionItem")
                        .where(EntityCondition.makeCondition(delConds, EntityOperator.AND))
                        .queryList();
                for (GenericValue gv : pend) {
                    gv.remove();
                    deleted++;
                }
            }

            // Build EmployeeDeduction effective conditions:
            // effectiveDate <= asOf AND (thruDate is null OR thruDate > asOf)
            List<EntityCondition> conds = new ArrayList<>();
            conds.add(EntityCondition.makeCondition("organizationPartyId", orgId));
            if (UtilValidate.isNotEmpty(partyId)) {
                conds.add(EntityCondition.makeCondition("partyId", partyId));
            }
            conds.add(EntityCondition.makeCondition("effectiveDate", EntityOperator.LESS_THAN_EQUAL_TO, asOf));
            conds.add(EntityCondition.makeCondition(
                    EntityCondition.makeCondition("thruDate", EntityOperator.EQUALS, null),
                    EntityOperator.OR,
                    EntityCondition.makeCondition("thruDate", EntityOperator.GREATER_THAN, asOf)
            ));

            List<GenericValue> empDeds = EntityQuery.use(delegator)
                    .from("EmployeeDeduction")
                    .where(EntityCondition.makeCondition(conds, EntityOperator.AND))
                    .orderBy("partyId", "deductionTypeId", "employeeDeductionId")
                    .queryList();

            for (GenericValue ed : empDeds) {
                String p = ed.getString("partyId");
                String dt = ed.getString("deductionTypeId");
                BigDecimal amt = nz(ed.getBigDecimal("amount"));

                if (UtilValidate.isEmpty(p) || UtilValidate.isEmpty(dt) || amt.compareTo(BigDecimal.ZERO) <= 0) {
                    skipped++;
                    continue;
                }

                // validate DeductionType exists (protect against bad POC)
                GenericValue dedType = EntityQuery.use(delegator).from("DeductionType")
                        .where("deductionTypeId", dt).queryOne();
                if (dedType == null) {
                    skipped++;
                    continue;
                }

                // idempotency: if pending already exists, skip
                long exists = EntityQuery.use(delegator).from("PayrollDeductionItem")
                        .where("organizationPartyId", orgId,
                                "partyId", p,
                                "deductionTypeId", dt,
                                "payrollResultId", null)
                        .queryCount();

                if (exists > 0) {
                    skipped++;
                    continue;
                }

                String id = delegator.getNextSeqId("PayrollDeductionItem");
                GenericValue item = delegator.makeValue("PayrollDeductionItem", UtilMisc.toMap(
                        "payrollDeductionItemId", id,
                        "organizationPartyId", orgId,
                        "partyId", p,
                        "deductionTypeId", dt,
                        "amount", amt,
                        "payrollResultId", null
                ));
                item.create();
                created++;
            }

            Map<String, Object> data = getData(result);
            data.put("organizationPartyId", orgId);
            data.put("partyId", partyId);
            data.put("asOfDate", asOf);
            data.put("forceRebuild", forceRebuild);
            data.put("deletedCount", deleted);
            data.put("createdCount", created);
            data.put("skippedCount", skipped);

            mirrorLegacy(result);
            return result;

        } catch (Exception e) {
            Debug.logError(e, "ensurePayrollDeductionItemsFromEmployeeDeduction failed", module);
            return withError(result, "ensurePayrollDeductionItemsFromEmployeeDeduction failed: " + e.getMessage());
        }
    }

    /**
     * Internal helper: attach pending deductions to a result and create PRL_DED lines.
     * Called from processPayrollRunFromSnapshotV2 after header created.
     *
     * IMPORTANT: because PayrollDeductionItem has no payrollRunId in schema,
     * this attaches ALL pending items for (org, party).
     */
    public static int attachPendingDeductionsToResult(Delegator delegator,
                                                      String organizationPartyId,
                                                      String partyId,
                                                      String payrollResultId,
                                                      String currencyUomId,
                                                      long startSeq) throws Exception {

        List<GenericValue> pending = EntityQuery.use(delegator).from("PayrollDeductionItem")
                .where("organizationPartyId", organizationPartyId,
                        "partyId", partyId,
                        "payrollResultId", null)
                .orderBy("payrollDeductionItemId")
                .queryList();

        int attached = 0;
        for (GenericValue item : pending) {
            String deductionTypeId = item.getString("deductionTypeId");
            BigDecimal amt = nz(item.getBigDecimal("amount"));

            // attach
            item.set("payrollResultId", payrollResultId);
            item.store();

            GenericValue dedType = EntityQuery.use(delegator).from("DeductionType")
                    .where("deductionTypeId", deductionTypeId).queryOne();
            String desc = (dedType != null && UtilValidate.isNotEmpty(dedType.getString("description")))
                    ? dedType.getString("description")
                    : ("Deduction " + deductionTypeId);

            String lineId = delegator.getNextSeqId("EmployeePayrollResultLine");
            long seq = startSeq + attached;

            GenericValue line = delegator.makeValue("EmployeePayrollResultLine", UtilMisc.toMap(
                    "resultLineId", lineId,
                    "payrollResultId", payrollResultId,
                    "lineTypeId", RL_DED,
                    "referenceId", item.getString("payrollDeductionItemId"),
                    "description", desc,
                    "amount", amt,
                    "currencyUomId", currencyUomId,
                    "sequenceNum", seq
            ));
            line.create();

            attached++;
        }
        return attached;
    }

    // ============================================================
    // Helpers
    // ============================================================

    private static Map<String, Object> baseResponse() {
        Map<String, Object> result = ServiceUtil.returnSuccess();
        result.put("data", new HashMap<String, Object>());
        result.put("legacyData", new HashMap<String, Object>());
        return result;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> getData(Map<String, Object> result) {
        Map<String, Object> data = (Map<String, Object>) result.get("data");
        if (data == null) {
            data = new HashMap<>();
            result.put("data", data);
        }
        return data;
    }

    @SuppressWarnings("unchecked")
    private static void mirrorLegacy(Map<String, Object> result) {
        Map<String, Object> data = (Map<String, Object>) result.get("data");
        Map<String, Object> legacy = (Map<String, Object>) result.get("legacyData");
        if (legacy == null) {
            legacy = new HashMap<>();
            result.put("legacyData", legacy);
        }
        if (data != null) legacy.putAll(data);
    }

    private static Map<String, Object> withError(Map<String, Object> base, String msg) {
        base.put("responseMessage", "error");
        base.put("errorMessage", msg);
        if (base.get("data") == null) base.put("data", new HashMap<>());
        if (base.get("legacyData") == null) base.put("legacyData", new HashMap<>());
        return base;
    }

    private static String normalizeFlag(String v) {
        if (UtilValidate.isEmpty(v)) return "N";
        return "Y".equalsIgnoreCase(v) || "true".equalsIgnoreCase(v) ? "Y" : "N";
    }

    private static BigDecimal nz(BigDecimal v) {
        return v == null ? BigDecimal.ZERO : v;
    }
}