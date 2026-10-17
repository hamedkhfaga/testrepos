package org.apache.ofbiz.payroll.services;

import org.apache.ofbiz.base.util.Debug;
import org.apache.ofbiz.base.util.UtilDateTime;
import org.apache.ofbiz.base.util.UtilMisc;
import org.apache.ofbiz.base.util.UtilValidate;
import org.apache.ofbiz.entity.Delegator;
import org.apache.ofbiz.entity.GenericEntityException;
import org.apache.ofbiz.entity.GenericValue;
import org.apache.ofbiz.entity.transaction.TransactionUtil;
import org.apache.ofbiz.entity.util.EntityQuery;
import org.apache.ofbiz.service.DispatchContext;
import org.apache.ofbiz.service.LocalDispatcher;
import org.apache.ofbiz.service.ServiceUtil;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.util.List;
import java.util.Map;
import org.apache.ofbiz.payroll.util.PayrollRetroUtil;


public class PayrollRetroServices {
    public static final String MODULE = PayrollRetroServices.class.getName();

    // Batch statuses
    private static final String RETRO_NEW      = "RETRO_NEW";
    private static final String RETRO_CALC     = "RETRO_CALC";
    private static final String RETRO_APPROVED = "RETRO_APPROVED";
    private static final String RETRO_POSTED   = "RETRO_POSTED";

    public static Map<String, Object> createPayrollRetroBatch(DispatchContext dctx, Map<String, ?> context) {
        Delegator delegator = dctx.getDelegator();
        GenericValue userLogin = (GenericValue) context.get("userLogin");

        try {
            String orgId = (String) context.get("organizationPartyId");
            Integer month = (Integer) context.get("periodMonth");
            Integer year = (Integer) context.get("periodYear");
            Timestamp effectiveDate = (Timestamp) context.get("effectiveDate");

            if (UtilValidate.isEmpty(orgId) || month == null || year == null || effectiveDate == null) {
                return ServiceUtil.returnError("organizationPartyId, periodMonth, periodYear, effectiveDate are required");
            }

            String id = delegator.getNextSeqId("PayrollRetroBatch");
            GenericValue bat = delegator.makeValue("PayrollRetroBatch");
            bat.set("payrollRetroBatchId", id);
            bat.set("organizationPartyId", orgId);
            bat.set("periodMonth", month);
            bat.set("periodYear", year);
            bat.set("effectiveDate", effectiveDate);
            bat.set("applyInPayrollRunId", context.get("applyInPayrollRunId"));
            bat.set("postMode", context.get("postMode"));
            bat.set("reason", context.get("reason"));
            bat.set("statusId", RETRO_NEW);
            bat.set("createdDate", UtilDateTime.nowTimestamp());
            bat.set("createdByUserLoginId", userLogin != null ? userLogin.getString("userLoginId") : null);

            delegator.create(bat);

            Map<String, Object> out = ServiceUtil.returnSuccess();
            out.put("payrollRetroBatchId", id);
            return out;

        } catch (Exception e) {
            Debug.logError(e, "createPayrollRetroBatch failed", MODULE);
            return ServiceUtil.returnError("createPayrollRetroBatch failed: " + e.getMessage());
        }
    }

    /**
     * Add employees list to retro batch.
     * Inputs: payrollRetroBatchId, partyIdList (List<String>)
     */
    public static Map<String, Object> addEmployeesToRetroBatch(DispatchContext dctx, Map<String, ?> context) {
        Delegator delegator = dctx.getDelegator();
        try {
            String batchId = (String) context.get("payrollRetroBatchId");
            @SuppressWarnings("unchecked")
            List<String> partyIdList = (List<String>) context.get("partyIdList");

            if (UtilValidate.isEmpty(batchId) || partyIdList == null || partyIdList.isEmpty()) {
                return ServiceUtil.returnError("payrollRetroBatchId and partyIdList are required");
            }

            for (String partyId : partyIdList) {
                String id = delegator.getNextSeqId("PayrollRetroEmployee");
                GenericValue re = delegator.makeValue("PayrollRetroEmployee");
                re.set("payrollRetroEmployeeId", id);
                re.set("payrollRetroBatchId", batchId);
                re.set("partyId", partyId);
                re.set("statusId", "RETRO_EMP_NEW");
                delegator.create(re);
            }

            return ServiceUtil.returnSuccess();
        } catch (Exception e) {
            Debug.logError(e, "addEmployeesToRetroBatch failed", MODULE);
            return ServiceUtil.returnError("addEmployeesToRetroBatch failed: " + e.getMessage());
        }
    }

    /**
     * Calculate retro deltas for employees in batch.
     *
     * OLD amounts:
     * - from EmployeePayrollResult for same org + party + period month/year
     *
     * NEW amounts:
     * - recompute through PayrollRetroUtil hook (safe stub)
     */
    public static Map<String, Object> calculatePayrollRetroBatch(DispatchContext dctx, Map<String, ?> context) {
        Delegator delegator = dctx.getDelegator();

        String batchId = (String) context.get("payrollRetroBatchId");
        if (UtilValidate.isEmpty(batchId)) return ServiceUtil.returnError("payrollRetroBatchId is required");

        boolean began = false;
        int deltaLines = 0;

        try {
            began = TransactionUtil.begin();

            GenericValue bat = delegator.findOne("PayrollRetroBatch",
                    UtilMisc.toMap("payrollRetroBatchId", batchId), false);
            if (bat == null) return ServiceUtil.returnError("Retro batch not found");
            if (!RETRO_NEW.equals(bat.getString("statusId"))) {
                return ServiceUtil.returnError("Retro batch must be RETRO_NEW to calculate");
            }

            String orgId = bat.getString("organizationPartyId");
            Integer monthObj = bat.getLong("periodMonth") != null ? bat.getLong("periodMonth").intValue() : null;
            Integer yearObj = bat.getLong("periodYear") != null ? bat.getLong("periodYear").intValue() : null;

            if (UtilValidate.isEmpty(orgId) || monthObj == null || yearObj == null) {
                return ServiceUtil.returnError("Retro batch missing organizationPartyId/periodMonth/periodYear");
            }

            int month = monthObj;
            int year = yearObj;

            List<GenericValue> emps = EntityQuery.use(delegator)
                    .from("PayrollRetroEmployee")
                    .where("payrollRetroBatchId", batchId)
                    .queryList();

            // Clear existing deltas (re-calc)
            delegator.removeByAnd("PayrollRetroDelta", UtilMisc.toMap("payrollRetroBatchId", batchId));

            for (GenericValue emp : emps) {
                String partyId = emp.getString("partyId");
                if (UtilValidate.isEmpty(partyId)) continue;

                // OLD: try to find existing payroll result for that period
                // NOTE: If your EmployeePayrollResult doesn't have periodMonth/periodYear, adjust this query.
                GenericValue oldRes = EntityQuery.use(delegator)
                        .from("EmployeePayrollResult")
                        .where("orgPartyId", orgId,
                               "partyId", partyId,
                               "periodMonth", month,
                               "periodYear", year)
                        .queryFirst();

                if (oldRes == null) {
                    // no original payroll => skip employee
                    continue;
                }

                BigDecimal oldGross = nvl(oldRes.getBigDecimal("grossSalary"));
                BigDecimal oldDed   = nvl(oldRes.getBigDecimal("totalDeductions"));
                BigDecimal oldNet   = nvl(oldRes.getBigDecimal("netSalary"));

                // NEW: recompute via hook (stub returns empty map unless you implement it)
                Map<String, BigDecimal> newCalc = PayrollRetroUtil.recalculateForRetro(dctx, orgId, partyId, month, year);

                BigDecimal newGross = nvl(newCalc.get("gross"));
                BigDecimal newDed   = nvl(newCalc.get("deductions"));
                BigDecimal newNet   = nvl(newCalc.get("net"));

                String originalResultId = oldRes.getString("payrollResultId");

                // Store delta lines (only non-zero)
                deltaLines += storeDelta(delegator, batchId, partyId, originalResultId,
                        "GROSS", null, oldGross, newGross);

                deltaLines += storeDelta(delegator, batchId, partyId, originalResultId,
                        "DEDUCTIONS", null, oldDed, newDed);

                deltaLines += storeDelta(delegator, batchId, partyId, originalResultId,
                        "NET", null, oldNet, newNet);
            }

            bat.set("statusId", RETRO_CALC);
            bat.store();

            TransactionUtil.commit(began);

            Map<String, Object> out = ServiceUtil.returnSuccess();
            out.put("deltaLineCount", deltaLines);
            return out;

        } catch (Exception e) {
            try { if (began) TransactionUtil.rollback(began, e.getMessage(), e); } catch (Exception ignored) {}
            Debug.logError(e, "calculatePayrollRetroBatch failed", MODULE);
            return ServiceUtil.returnError("calculatePayrollRetroBatch failed: " + e.getMessage());
        }
    }

    public static Map<String, Object> approvePayrollRetroBatch(DispatchContext dctx, Map<String, ?> context) {
        Delegator delegator = dctx.getDelegator();
        GenericValue userLogin = (GenericValue) context.get("userLogin");

        try {
            String batchId = (String) context.get("payrollRetroBatchId");
            if (UtilValidate.isEmpty(batchId)) return ServiceUtil.returnError("payrollRetroBatchId is required");

            GenericValue bat = delegator.findOne("PayrollRetroBatch",
                    UtilMisc.toMap("payrollRetroBatchId", batchId), false);
            if (bat == null) return ServiceUtil.returnError("Retro batch not found");
            if (!RETRO_CALC.equals(bat.getString("statusId"))) {
                return ServiceUtil.returnError("Retro batch must be RETRO_CALC to approve");
            }

            bat.set("statusId", RETRO_APPROVED);
            bat.set("approvedDate", UtilDateTime.nowTimestamp());
            bat.set("approvedByUserLoginId", userLogin != null ? userLogin.getString("userLoginId") : null);
            bat.store();

            return ServiceUtil.returnSuccess();
        } catch (Exception e) {
            Debug.logError(e, "approvePayrollRetroBatch failed", MODULE);
            return ServiceUtil.returnError("approvePayrollRetroBatch failed: " + e.getMessage());
        }
    }

    /**
     * Post retro deltas (difference only).
     * - Requires RETRO_APPROVED
     * - Creates PayrollRetroPosting record
     */
    public static Map<String, Object> postPayrollRetroBatch(DispatchContext dctx, Map<String, ?> context) {
        Delegator delegator = dctx.getDelegator();
        LocalDispatcher dispatcher = dctx.getDispatcher();
        GenericValue userLogin = (GenericValue) context.get("userLogin");

        String batchId = (String) context.get("payrollRetroBatchId");
        if (UtilValidate.isEmpty(batchId)) return ServiceUtil.returnError("payrollRetroBatchId is required");

        boolean began = false;
        try {
            began = TransactionUtil.begin();

            GenericValue bat = delegator.findOne("PayrollRetroBatch",
                    UtilMisc.toMap("payrollRetroBatchId", batchId), false);
            if (bat == null) return ServiceUtil.returnError("Retro batch not found");
            if (!RETRO_APPROVED.equals(bat.getString("statusId"))) {
                return ServiceUtil.returnError("Retro batch must be RETRO_APPROVED to post");
            }

            String postMode = bat.getString("postMode");
            if (UtilValidate.isEmpty(postMode)) postMode = "GL";

            List<GenericValue> deltas = EntityQuery.use(delegator)
                    .from("PayrollRetroDelta")
                    .where("payrollRetroBatchId", batchId)
                    .queryList();

            for (GenericValue d : deltas) {
                BigDecimal delta = nvl(d.getBigDecimal("deltaAmount"));
                if (delta.compareTo(BigDecimal.ZERO) == 0) continue;

                if ("GL".equals(postMode)) {
                    Map<String, Object> glCtx = UtilMisc.toMap(
                            "organizationPartyId", bat.getString("organizationPartyId"),
                            "transactionDate", UtilDateTime.nowTimestamp(),
                            "amount", delta,
                            "description", "Retro payroll delta " + d.getString("lineTypeId") + " batch " + batchId,
                            "userLogin", userLogin
                    );
                    dispatcher.runSync("createPayrollRetroAcctgTrans", glCtx);

                } else if ("INVOICE".equals(postMode)) {
                    Map<String, Object> invCtx = UtilMisc.toMap(
                            "organizationPartyId", bat.getString("organizationPartyId"),
                            "partyId", d.getString("partyId"),
                            "amount", delta,
                            "description", "Retro payroll delta batch " + batchId,
                            "userLogin", userLogin
                    );
                    dispatcher.runSync("createPayrollRetroInvoice", invCtx);
                }

                d.set("statusId", "RETRO_LINE_POSTED");
                d.store();
            }

            String postingId = delegator.getNextSeqId("PayrollRetroPosting");
            GenericValue p = delegator.makeValue("PayrollRetroPosting");
            p.set("payrollRetroPostingId", postingId);
            p.set("payrollRetroBatchId", batchId);
            p.set("postMode", postMode);
            p.set("postedDate", UtilDateTime.nowTimestamp());
            p.set("postedByUserLoginId", userLogin != null ? userLogin.getString("userLoginId") : null);
            p.set("statusId", "RETRO_POST_OK");
            delegator.create(p);

            bat.set("statusId", RETRO_POSTED);
            bat.store();

            TransactionUtil.commit(began);
            return ServiceUtil.returnSuccess();

        } catch (Exception e) {
            try { if (began) TransactionUtil.rollback(began, e.getMessage(), e); } catch (Exception ignored) {}
            Debug.logError(e, "postPayrollRetroBatch failed", MODULE);
            return ServiceUtil.returnError("postPayrollRetroBatch failed: " + e.getMessage());
        }
    }

    /* --------------------------- helpers --------------------------- */

    private static BigDecimal nvl(BigDecimal v) { return v == null ? BigDecimal.ZERO : v; }

    private static int storeDelta(Delegator delegator, String batchId, String partyId, String originalResultId,
                                  String lineTypeId, String payComponentTypeId,
                                  BigDecimal oldAmt, BigDecimal newAmt) throws GenericEntityException {
        BigDecimal delta = nvl(newAmt).subtract(nvl(oldAmt));
        if (delta.compareTo(BigDecimal.ZERO) == 0) return 0;

        String id = delegator.getNextSeqId("PayrollRetroDelta");
        GenericValue d = delegator.makeValue("PayrollRetroDelta");
        d.set("payrollRetroDeltaId", id);
        d.set("payrollRetroBatchId", batchId);
        d.set("partyId", partyId);
        d.set("originalPayrollResultId", originalResultId); // <-- make sure your entity field name matches
        d.set("lineTypeId", lineTypeId);
        d.set("payComponentTypeId", payComponentTypeId);
        d.set("oldAmount", oldAmt);
        d.set("newAmount", newAmt);
        d.set("deltaAmount", delta);
        d.set("statusId", "RETRO_LINE_NEW");
        delegator.create(d);
        return 1;
    }
}
