package org.apache.ofbiz.payroll.services;

import org.apache.ofbiz.base.util.Debug;
import org.apache.ofbiz.base.util.UtilDateTime;
import org.apache.ofbiz.base.util.UtilMisc;
import org.apache.ofbiz.base.util.UtilValidate;

import org.apache.ofbiz.entity.Delegator;
import org.apache.ofbiz.entity.GenericEntityException;
import org.apache.ofbiz.entity.GenericValue;

import org.apache.ofbiz.entity.condition.EntityCondition;
import org.apache.ofbiz.entity.condition.EntityOperator;

import org.apache.ofbiz.entity.transaction.TransactionUtil;
import org.apache.ofbiz.entity.util.EntityQuery;

import org.apache.ofbiz.service.DispatchContext;
import org.apache.ofbiz.service.ServiceUtil;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.sql.Timestamp;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

/**
 * PayrollWorkflowServices - FINAL consolidated workflow
 *
 * Uses organizationPartyId everywhere.
 * Workflow states (StatusItem):
 *  - PAY_RUN_DRAFT
 *  - PAY_RUN_CALC
 *  - PAY_RUN_SUBMITTED
 *  - PAY_RUN_APPROVED
 *  - PAY_RUN_REJECTED
 *  - PAY_RUN_LOCKED
 *
 * Result states:
 *  - PAY_RES_NEW
 *  - PAY_RES_LOCKED
 *
 * Line types (StatusItem or your controlled IDs):
 *  - PAY_LINE_BASIC
 *  - PAY_LINE_ALLOW
 *  - PAY_LINE_DEDUCT
 *  - PAY_LINE_LOAN
 *  - PAY_LINE_TAX
 *  - PAY_LINE_SS
 */
public class PayrollWorkflowServices {
    public static final String MODULE = PayrollWorkflowServices.class.getName();

    // -------- Run status --------
    public static final String RUN_STATUS_DRAFT     = "PAY_RUN_DRAFT";
    public static final String RUN_STATUS_CALC      = "PAY_RUN_CALC";
    public static final String RUN_STATUS_SUBMITTED = "PAY_RUN_SUBMITTED";
    public static final String RUN_STATUS_APPROVED  = "PAY_RUN_APPROVED";
    public static final String RUN_STATUS_REJECTED  = "PAY_RUN_REJECTED";
    public static final String RUN_STATUS_LOCKED    = "PAY_RUN_LOCKED"; // optional: lock after posting

    // -------- Result status --------
    public static final String RES_STATUS_NEW    = "PAY_RES_NEW";
    public static final String RES_STATUS_LOCKED = "PAY_RES_LOCKED";

    // -------- Line types --------
    public static final String LINE_BASIC  = "PAY_LINE_BASIC";
    public static final String LINE_ALLOW  = "PAY_LINE_ALLOW";
    public static final String LINE_DEDUCT = "PAY_LINE_DEDUCT";
    public static final String LINE_LOAN   = "PAY_LINE_LOAN";
    public static final String LINE_TAX    = "PAY_LINE_TAX";
    public static final String LINE_SS     = "PAY_LINE_SS";

    // =========================================================
    // 1) Create PayrollRun (DRAFT)
    // =========================================================
    public static Map<String, Object> createPayrollRun(DispatchContext dctx, Map<String, ?> context) {
        Delegator delegator = dctx.getDelegator();
        GenericValue userLogin = (GenericValue) context.get("userLogin");

        String organizationPartyId = (String) context.get("organizationPartyId");
        String payrollPeriodId = (String) context.get("payrollPeriodId");
        String currencyUomId = (String) context.get("currencyUomId");
        String valuationCurrencyUomId = (String) context.get("valuationCurrencyUomId");
        String runName = (String) context.get("runName");
        String runTypeId = (String) context.get("runTypeId");

        if (UtilValidate.isEmpty(organizationPartyId) || UtilValidate.isEmpty(payrollPeriodId) || UtilValidate.isEmpty(currencyUomId)) {
            return ServiceUtil.returnError("organizationPartyId, payrollPeriodId, currencyUomId are required");
        }

        boolean beganTx = false;
        try {
            beganTx = TransactionUtil.begin();

            // validate period belongs to org
            GenericValue period = EntityQuery.use(delegator).from("PayrollPeriod")
                    .where("payrollPeriodId", payrollPeriodId,
                           "organizationPartyId", organizationPartyId)
                    .queryOne();
            if (period == null) {
                return ServiceUtil.returnError("PayrollPeriod not found for organizationPartyId: " + payrollPeriodId);
            }

            String payrollRunId = delegator.getNextSeqId("PayrollRun");
            GenericValue run = delegator.makeValue("PayrollRun");
            run.set("payrollRunId", payrollRunId);
            run.set("organizationPartyId", organizationPartyId);
            run.set("payrollPeriodId", payrollPeriodId);
            run.set("runName", "runName");
            run.set("runTypeId", runTypeId);
            run.set("runDate", UtilDateTime.nowTimestamp());
            run.set("currencyUomId", currencyUomId);
            run.set("valuationCurrencyUomId", valuationCurrencyUomId);
            run.set("statusId", RUN_STATUS_DRAFT);

            if (userLogin != null && hasField(delegator, "PayrollRun", "createdByUserLoginId")) {
                run.set("createdByUserLoginId", userLogin.getString("userLoginId"));
            }
            if (hasField(delegator, "PayrollRun", "createdDate")) {
                run.set("createdDate", UtilDateTime.nowTimestamp());
            }

            run.create();

            PayrollAuditServices.logEvent(delegator, organizationPartyId,
                    "RUN_CREATE", "PayrollRun", payrollRunId, null, safeJson(run), userLogin, null);

            TransactionUtil.commit(beganTx);

            Map<String, Object> res = ServiceUtil.returnSuccess();
            res.put("payrollRunId", payrollRunId);
            return res;
        } catch (Exception e) {
            try {
            TransactionUtil.rollback(beganTx, e.getMessage(), e);
        } catch (Exception rollbackEx) {
            Debug.logError(rollbackEx, "Rollback failed (ignored)", MODULE);
        }
Debug.logError(e, "createPayrollRun failed", MODULE);
            return ServiceUtil.returnError("createPayrollRun failed: " + e.getMessage());
        }
    }

    // =========================================================
    // 2) Calculate PayrollRun (idempotent)
    //    DRAFT/REJECTED -> CALC
    // =========================================================
    public static Map<String, Object> calculatePayrollRun(DispatchContext dctx, Map<String, ?> context) {
        Delegator delegator = dctx.getDelegator();
        GenericValue userLogin = (GenericValue) context.get("userLogin");

        String payrollRunId = (String) context.get("payrollRunId");
        Boolean forceRecalc = (Boolean) context.get("forceRecalc");

        if (UtilValidate.isEmpty(payrollRunId)) {
            return ServiceUtil.returnError("payrollRunId is required");
        }

        boolean beganTx = false;
        try {
            beganTx = TransactionUtil.begin();

            GenericValue run = EntityQuery.use(delegator).from("PayrollRun")
                    .where("payrollRunId", payrollRunId)
                    .queryOne();
            if (run == null) return ServiceUtil.returnError("PayrollRun not found: " + payrollRunId);

            String organizationPartyId = run.getString("organizationPartyId");
            if (UtilValidate.isEmpty(organizationPartyId)) return ServiceUtil.returnError("PayrollRun.organizationPartyId is required");

            String statusId = run.getString("statusId");
            if (RUN_STATUS_APPROVED.equals(statusId) || RUN_STATUS_LOCKED.equals(statusId)) {
                return ServiceUtil.returnError("PayrollRun is approved/locked and cannot be recalculated");
            }

            // allow recalc from DRAFT or REJECTED, else only if forceRecalc=Y
            if (!(RUN_STATUS_DRAFT.equals(statusId) || RUN_STATUS_REJECTED.equals(statusId)) && !Boolean.TRUE.equals(forceRecalc)) {
                return ServiceUtil.returnError("PayrollRun must be DRAFT/REJECTED (or forceRecalc=Y). Current=" + statusId);
            }

            String payrollPeriodId = run.getString("payrollPeriodId");
            if (UtilValidate.isEmpty(payrollPeriodId)) return ServiceUtil.returnError("payrollPeriodId missing on PayrollRun");

            GenericValue period = EntityQuery.use(delegator).from("PayrollPeriod")
                    .where("payrollPeriodId", payrollPeriodId,
                           "organizationPartyId", organizationPartyId)
                    .queryOne();
            if (period == null) {
                return ServiceUtil.returnError("PayrollPeriod not found for org: " + payrollPeriodId);
            }

            Timestamp periodStart = UtilDateTime.toTimestamp(period.getDate("startDate"));
            Timestamp periodEnd   = UtilDateTime.toTimestamp(period.getDate("endDate"));

            // Clear existing results (idempotent)
            clearExistingRunResults(delegator, organizationPartyId, payrollRunId);

            // Active assignments for org within the period
            List<GenericValue> assignments = EntityQuery.use(delegator)
                    .from("EmployeePayrollAssignment")
                    .where(EntityCondition.makeCondition(Arrays.asList(
                            EntityCondition.makeCondition("organizationPartyId", EntityOperator.EQUALS, organizationPartyId),
                            EntityCondition.makeCondition("effectiveDate", EntityOperator.LESS_THAN_EQUAL_TO, periodEnd),
                            EntityCondition.makeCondition(
                                    EntityCondition.makeCondition("thruDate", EntityOperator.EQUALS, null),
                                    EntityOperator.OR,
                                    EntityCondition.makeCondition("thruDate", EntityOperator.GREATER_THAN_EQUAL_TO, periodStart)
                            )
                    ), EntityOperator.AND))
                    .queryList();

            BigDecimal totalGross = BigDecimal.ZERO;
            BigDecimal totalDed   = BigDecimal.ZERO;
            BigDecimal totalNet   = BigDecimal.ZERO;

            for (GenericValue asg : assignments) {
                String partyId = asg.getString("partyId");
                if (UtilValidate.isEmpty(partyId)) continue;

                // Uniqueness: ensure no existing result for (run, party)
                ensureUniqueResult(delegator, payrollRunId, partyId);

                BigDecimal baseSalary = nvlMoney(asg.getBigDecimal("baseSalary"));

                BigDecimal allowances = sumEmployeeAllowances(delegator, organizationPartyId, partyId, periodEnd);
                BigDecimal deductions = sumEmployeeDeductions(delegator, organizationPartyId, partyId, periodEnd);
                BigDecimal loanDed    = sumLoanInstallmentsDue(delegator, organizationPartyId, partyId, periodStart, periodEnd);

                BigDecimal gross = baseSalary.add(allowances);

                BigDecimal incomeTax = calculateIncomeTax(delegator, organizationPartyId, partyId, gross, periodEnd);
                BigDecimal socialSec = calculateSocialSecurity(delegator, organizationPartyId, partyId, gross, periodEnd);

                BigDecimal totalDeductions = deductions.add(loanDed).add(incomeTax).add(socialSec);
                BigDecimal net = gross.subtract(totalDeductions);

                String payrollResultId = delegator.getNextSeqId("EmployeePayrollResult");
                GenericValue res = delegator.makeValue("EmployeePayrollResult");
                res.set("payrollResultId", payrollResultId);
                res.set("payrollRunId", payrollRunId);
                res.set("organizationPartyId", organizationPartyId);
                res.set("partyId", partyId);

                res.set("basicSalary", baseSalary);
                res.set("allowancesTotal", allowances);
                res.set("grossSalary", gross);

                res.set("incomeTax", incomeTax);
                res.set("socialSecurity", socialSec);
                res.set("loanDeductions", loanDed);
                res.set("otherDeductions", deductions);
                res.set("totalDeductions", totalDeductions);
                res.set("netSalary", net);

                res.set("currencyUomId", run.getString("currencyUomId"));
                res.set("valuationCurrencyUomId", run.getString("valuationCurrencyUomId"));
                res.set("exchangeRate", run.get("exchangeRate"));

                // payment fields (if exist on result)
                if (hasField(delegator, "EmployeePayrollResult", "paymentMethodId")) res.set("paymentMethodId", asg.get("paymentMethodId"));
                if (hasField(delegator, "EmployeePayrollResult", "finAccountId")) res.set("finAccountId", asg.get("finAccountId"));

                res.set("versionNumber", 1L);
                res.set("statusId", RES_STATUS_NEW);
                res.set("isPayslipGenerated", "N");
                res.set("isPayslipSent", "N");

                res.create();

                // Lines (accounting style)
                createLine(delegator, payrollResultId, LINE_BASIC, null, "Basic Salary", baseSalary, run.getString("currencyUomId"), 10);
                if (allowances.signum() != 0) createLine(delegator, payrollResultId, LINE_ALLOW, null, "Allowances", allowances, run.getString("currencyUomId"), 20);
                if (deductions.signum() != 0) createLine(delegator, payrollResultId, LINE_DEDUCT, null, "Deductions", deductions.negate(), run.getString("currencyUomId"), 30);
                if (loanDed.signum() != 0) createLine(delegator, payrollResultId, LINE_LOAN, null, "Loan Installments", loanDed.negate(), run.getString("currencyUomId"), 40);
                if (incomeTax.signum() != 0) createLine(delegator, payrollResultId, LINE_TAX, null, "Income Tax", incomeTax.negate(), run.getString("currencyUomId"), 50);
                if (socialSec.signum() != 0) createLine(delegator, payrollResultId, LINE_SS, null, "Social Security", socialSec.negate(), run.getString("currencyUomId"), 60);

                // tax detail record (optional)
                if (incomeTax.signum() != 0) {
                    GenericValue taxDet = delegator.makeValue("EmployeeTaxDetail");
                    taxDet.set("taxDetailId", delegator.getNextSeqId("EmployeeTaxDetail"));
                    taxDet.set("payrollResultId", payrollResultId);
                    taxDet.set("taxTypeId", "PAY_TAX_INCOME");
                    taxDet.set("taxableAmount", gross);
                    taxDet.set("taxRate", null);
                    taxDet.set("calculatedTax", incomeTax);
                    taxDet.create();
                }

                totalGross = totalGross.add(gross);
                totalDed   = totalDed.add(totalDeductions);
                totalNet   = totalNet.add(net);
            }

            run.set("totalEmployees", (long) assignments.size());
            run.set("totalGrossPay", totalGross);
            run.set("totalDeductions", totalDed);
            run.set("totalNetPay", totalNet);

            run.set("statusId", RUN_STATUS_CALC);
            if (hasField(delegator, "PayrollRun", "calculatedDate")) run.set("calculatedDate", UtilDateTime.nowTimestamp());
            if (hasField(delegator, "PayrollRun", "calculatedByUserLoginId") && userLogin != null) {
                run.set("calculatedByUserLoginId", userLogin.getString("userLoginId"));
            }
            run.store();

            PayrollAuditServices.logEvent(delegator, organizationPartyId,
                    "RUN_CALC", "PayrollRun", payrollRunId, null, safeJson(run), userLogin, null);

            TransactionUtil.commit(beganTx);
            return ServiceUtil.returnSuccess();
        } catch (Exception e) {
            try {
            TransactionUtil.rollback(beganTx, e.getMessage(), e);
        } catch (Exception rollbackEx) {
            Debug.logError(rollbackEx, "Rollback failed (ignored)", MODULE);
        }
Debug.logError(e, "calculatePayrollRun failed", MODULE);
            return ServiceUtil.returnError("calculatePayrollRun failed: " + e.getMessage());
        }
    }

    // =========================================================
    // 3) Submit (CALC -> SUBMITTED)  [optional workflow gate]
    // =========================================================
    public static Map<String, Object> submitPayrollRun(DispatchContext dctx, Map<String, ?> context) {
        Delegator delegator = dctx.getDelegator();
        GenericValue userLogin = (GenericValue) context.get("userLogin");
        String payrollRunId = (String) context.get("payrollRunId");

        if (UtilValidate.isEmpty(payrollRunId)) return ServiceUtil.returnError("payrollRunId is required");

        boolean beganTx = false;
        try {
            beganTx = TransactionUtil.begin();

            GenericValue run = EntityQuery.use(delegator).from("PayrollRun")
                    .where("payrollRunId", payrollRunId).queryOne();
            if (run == null) return ServiceUtil.returnError("PayrollRun not found: " + payrollRunId);

            String organizationPartyId = run.getString("organizationPartyId");

            String statusId = run.getString("statusId");
            if (!RUN_STATUS_CALC.equals(statusId)) {
                return ServiceUtil.returnError("Only CALCULATED runs can be submitted. Current=" + statusId);
            }

            run.set("statusId", RUN_STATUS_SUBMITTED);
            run.store();

            PayrollAuditServices.logEvent(delegator, organizationPartyId,
                    "RUN_SUBMIT", "PayrollRun", payrollRunId, null, safeJson(run), userLogin, null);

            TransactionUtil.commit(beganTx);
            return ServiceUtil.returnSuccess();
        } catch (Exception e) {
            try {
            TransactionUtil.rollback(beganTx, e.getMessage(), e);
        } catch (Exception rollbackEx) {
            Debug.logError(rollbackEx, "Rollback failed (ignored)", MODULE);
        }
Debug.logError(e, "submitPayrollRun failed", MODULE);
            return ServiceUtil.returnError("submitPayrollRun failed: " + e.getMessage());
        }
    }

    // =========================================================
    // 4) Approve (SUBMITTED or CALC -> APPROVED) + lock results
    // =========================================================
    public static Map<String, Object> approvePayrollRun(DispatchContext dctx, Map<String, ?> context) {
        Delegator delegator = dctx.getDelegator();
        GenericValue userLogin = (GenericValue) context.get("userLogin");
        String payrollRunId = (String) context.get("payrollRunId");

        if (UtilValidate.isEmpty(payrollRunId)) return ServiceUtil.returnError("payrollRunId is required");

        boolean beganTx = false;
        try {
            beganTx = TransactionUtil.begin();

            GenericValue run = EntityQuery.use(delegator).from("PayrollRun")
                    .where("payrollRunId", payrollRunId).queryOne();
            if (run == null) return ServiceUtil.returnError("PayrollRun not found: " + payrollRunId);

            String organizationPartyId = run.getString("organizationPartyId");

            String statusId = run.getString("statusId");
            if (!(RUN_STATUS_SUBMITTED.equals(statusId) || RUN_STATUS_CALC.equals(statusId))) {
                return ServiceUtil.returnError("PayrollRun must be SUBMITTED or CALCULATED before approval. Current=" + statusId);
            }

            // Validate totals == sum(results)
            validateRunTotals(delegator, payrollRunId, run);

            // Lock results
            List<GenericValue> results = EntityQuery.use(delegator).from("EmployeePayrollResult")
                    .where("organizationPartyId", organizationPartyId, "payrollRunId", payrollRunId)
                    .queryList();
            for (GenericValue r : results) {
                r.set("statusId", RES_STATUS_LOCKED);
                if (hasField(delegator, "EmployeePayrollResult", "isLocked")) r.set("isLocked", "Y");
                r.store();
            }

            run.set("statusId", RUN_STATUS_APPROVED);
            run.set("approvedDate", UtilDateTime.nowTimestamp());
            if (hasField(delegator, "PayrollRun", "approvedByUserLoginId") && userLogin != null) {
                run.set("approvedByUserLoginId", userLogin.getString("userLoginId"));
            }
            run.store();

            PayrollAuditServices.logEvent(delegator, organizationPartyId,
                    "RUN_APPROVE", "PayrollRun", payrollRunId, null, safeJson(run), userLogin, null);

            TransactionUtil.commit(beganTx);
            return ServiceUtil.returnSuccess();
        } catch (Exception e) {
            try {
            TransactionUtil.rollback(beganTx, e.getMessage(), e);
        } catch (Exception rollbackEx) {
            Debug.logError(rollbackEx, "Rollback failed (ignored)", MODULE);
        }
Debug.logError(e, "approvePayrollRun failed", MODULE);
            return ServiceUtil.returnError("approvePayrollRun failed: " + e.getMessage());
        }
    }

    // =========================================================
    // 5) Reject (SUBMITTED/APPROVED -> REJECTED)
    // =========================================================
    public static Map<String, Object> rejectPayrollRun(DispatchContext dctx, Map<String, ?> context) {
        Delegator delegator = dctx.getDelegator();
        GenericValue userLogin = (GenericValue) context.get("userLogin");
        String payrollRunId = (String) context.get("payrollRunId");
        String reason = (String) context.get("reason");

        if (UtilValidate.isEmpty(payrollRunId)) return ServiceUtil.returnError("payrollRunId is required");

        boolean beganTx = false;
        try {
            beganTx = TransactionUtil.begin();

            GenericValue run = EntityQuery.use(delegator).from("PayrollRun")
                    .where("payrollRunId", payrollRunId).queryOne();
            if (run == null) return ServiceUtil.returnError("PayrollRun not found: " + payrollRunId);

            String organizationPartyId = run.getString("organizationPartyId");

            String statusId = run.getString("statusId");
            if (!(RUN_STATUS_SUBMITTED.equals(statusId) || RUN_STATUS_APPROVED.equals(statusId))) {
                return ServiceUtil.returnError("Run can be rejected only from SUBMITTED/APPROVED. Current=" + statusId);
            }

            run.set("statusId", RUN_STATUS_REJECTED);
            if (UtilValidate.isNotEmpty(reason) && hasField(delegator, "PayrollRun", "runName")) {
                String rn = run.getString("runName");
                run.set("runName", (rn == null ? "" : rn) + " [REJECTED]");
            }
            run.store();

            PayrollAuditServices.logEvent(delegator, organizationPartyId,
                    "RUN_REJECT", "PayrollRun", payrollRunId, null, safeJson(run), userLogin, reason);

            TransactionUtil.commit(beganTx);
            return ServiceUtil.returnSuccess();
        } catch (Exception e) {
            try {
            TransactionUtil.rollback(beganTx, e.getMessage(), e);
        } catch (Exception rollbackEx) {
            Debug.logError(rollbackEx, "Rollback failed (ignored)", MODULE);
        }
Debug.logError(e, "rejectPayrollRun failed", MODULE);
            return ServiceUtil.returnError("rejectPayrollRun failed: " + e.getMessage());
        }
    }

    // =========================================================
    // 6) Lock (APPROVED -> LOCKED) optional
    // =========================================================
    public static Map<String, Object> lockPayrollRun(DispatchContext dctx, Map<String, ?> context) {
        Delegator delegator = dctx.getDelegator();
        GenericValue userLogin = (GenericValue) context.get("userLogin");
        String payrollRunId = (String) context.get("payrollRunId");

        if (UtilValidate.isEmpty(payrollRunId)) return ServiceUtil.returnError("payrollRunId is required");

        boolean beganTx = false;
        try {
            beganTx = TransactionUtil.begin();

            GenericValue run = EntityQuery.use(delegator).from("PayrollRun")
                    .where("payrollRunId", payrollRunId).queryOne();
            if (run == null) return ServiceUtil.returnError("PayrollRun not found: " + payrollRunId);

            String organizationPartyId = run.getString("organizationPartyId");

            if (!RUN_STATUS_APPROVED.equals(run.getString("statusId"))) {
                return ServiceUtil.returnError("Only APPROVED runs can be locked");
            }

            run.set("statusId", RUN_STATUS_LOCKED);
            run.store();

            PayrollAuditServices.logEvent(delegator, organizationPartyId,
                    "RUN_LOCK", "PayrollRun", payrollRunId, null, safeJson(run), userLogin, null);

            TransactionUtil.commit(beganTx);
            return ServiceUtil.returnSuccess();
        } catch (Exception e) {
            try {
            TransactionUtil.rollback(beganTx, e.getMessage(), e);
        } catch (Exception rollbackEx) {
            Debug.logError(rollbackEx, "Rollback failed (ignored)", MODULE);
        }
Debug.logError(e, "lockPayrollRun failed", MODULE);
            return ServiceUtil.returnError("lockPayrollRun failed: " + e.getMessage());
        }
    }

    // =========================================================
    // Helpers
    // =========================================================

    private static void validateRunTotals(Delegator delegator, String payrollRunId, GenericValue run) throws Exception {
        BigDecimal gross = BigDecimal.ZERO;
        BigDecimal ded   = BigDecimal.ZERO;
        BigDecimal net   = BigDecimal.ZERO;

        List<GenericValue> results = EntityQuery.use(delegator).from("EmployeePayrollResult")
                .where("payrollRunId", payrollRunId)
                .queryList();

        for (GenericValue r : results) {
            gross = gross.add(nvlMoney(r.getBigDecimal("grossSalary")));
            ded   = ded.add(nvlMoney(r.getBigDecimal("totalDeductions")));
            net   = net.add(nvlMoney(r.getBigDecimal("netSalary")));
        }

        if (gross.compareTo(nvlMoney(run.getBigDecimal("totalGrossPay"))) != 0) {
            throw new IllegalArgumentException("Run totalGrossPay mismatch vs sum(results)");
        }
        if (ded.compareTo(nvlMoney(run.getBigDecimal("totalDeductions"))) != 0) {
            throw new IllegalArgumentException("Run totalDeductions mismatch vs sum(results)");
        }
        if (net.compareTo(nvlMoney(run.getBigDecimal("totalNetPay"))) != 0) {
            throw new IllegalArgumentException("Run totalNetPay mismatch vs sum(results)");
        }
    }

    private static void clearExistingRunResults(Delegator delegator, String organizationPartyId, String payrollRunId) throws Exception {
        List<GenericValue> resList = EntityQuery.use(delegator).from("EmployeePayrollResult")
                .where("organizationPartyId", organizationPartyId, "payrollRunId", payrollRunId)
                .queryList();

        for (GenericValue res : resList) {
            String payrollResultId = res.getString("payrollResultId");

            EntityQuery.use(delegator).from("EmployeePayrollResultLine")
                    .where("payrollResultId", payrollResultId)
                    .queryList().forEach(PayrollWorkflowServices::safeRemove);

            EntityQuery.use(delegator).from("EmployeeTaxDetail")
                    .where("payrollResultId", payrollResultId)
                    .queryList().forEach(PayrollWorkflowServices::safeRemove);

            safeRemove(res);
        }
    }

    private static void safeRemove(GenericValue gv) {
        try { gv.remove(); } catch (Exception ignored) { }
    }

    private static void ensureUniqueResult(Delegator delegator, String payrollRunId, String partyId) throws Exception {
        GenericValue exists = EntityQuery.use(delegator).from("EmployeePayrollResult")
                .where("payrollRunId", payrollRunId, "partyId", partyId)
                .queryFirst();
        if (exists != null) {
            throw new IllegalStateException("Duplicate EmployeePayrollResult exists for payrollRunId=" + payrollRunId + " partyId=" + partyId);
        }
    }

    private static BigDecimal sumEmployeeAllowances(Delegator delegator, String organizationPartyId, String partyId, Timestamp asOf) throws Exception {
        List<GenericValue> list = EntityQuery.use(delegator).from("EmployeeAllowance")
                .where(EntityCondition.makeCondition(Arrays.asList(
                        EntityCondition.makeCondition("organizationPartyId", EntityOperator.EQUALS, organizationPartyId),
                        EntityCondition.makeCondition("partyId", EntityOperator.EQUALS, partyId),
                        EntityCondition.makeCondition("effectiveDate", EntityOperator.LESS_THAN_EQUAL_TO, asOf),
                        EntityCondition.makeCondition(
                                EntityCondition.makeCondition("thruDate", EntityOperator.EQUALS, null),
                                EntityOperator.OR,
                                EntityCondition.makeCondition("thruDate", EntityOperator.GREATER_THAN_EQUAL_TO, asOf)
                        )
                ), EntityOperator.AND))
                .queryList();

        BigDecimal sum = BigDecimal.ZERO;
        for (GenericValue gv : list) sum = sum.add(nvlMoney(gv.getBigDecimal("amount")));
        return sum;
    }

    private static BigDecimal sumEmployeeDeductions(Delegator delegator, String organizationPartyId, String partyId, Timestamp asOf) throws Exception {
        List<GenericValue> list = EntityQuery.use(delegator).from("EmployeeDeduction")
                .where(EntityCondition.makeCondition(Arrays.asList(
                        EntityCondition.makeCondition("organizationPartyId", EntityOperator.EQUALS, organizationPartyId),
                        EntityCondition.makeCondition("partyId", EntityOperator.EQUALS, partyId),
                        EntityCondition.makeCondition("effectiveDate", EntityOperator.LESS_THAN_EQUAL_TO, asOf),
                        EntityCondition.makeCondition(
                                EntityCondition.makeCondition("thruDate", EntityOperator.EQUALS, null),
                                EntityOperator.OR,
                                EntityCondition.makeCondition("thruDate", EntityOperator.GREATER_THAN_EQUAL_TO, asOf)
                        )
                ), EntityOperator.AND))
                .queryList();

        BigDecimal sum = BigDecimal.ZERO;
        for (GenericValue gv : list) sum = sum.add(nvlMoney(gv.getBigDecimal("amount")));
        return sum;
    }

    private static BigDecimal sumLoanInstallmentsDue(Delegator delegator, String organizationPartyId, String partyId, Timestamp start, Timestamp end) throws Exception {
        List<GenericValue> loans = EntityQuery.use(delegator).from("EmployeeLoan")
                .where("organizationPartyId", organizationPartyId, "partyId", partyId)
                .queryList();

        BigDecimal sum = BigDecimal.ZERO;
        for (GenericValue loan : loans) {
            String loanId = loan.getString("loanId");
            List<GenericValue> inst = EntityQuery.use(delegator).from("LoanInstallment")
                    .where(EntityCondition.makeCondition(Arrays.asList(
                            EntityCondition.makeCondition("loanId", EntityOperator.EQUALS, loanId),
                            EntityCondition.makeCondition("dueDate", EntityOperator.GREATER_THAN_EQUAL_TO, start),
                            EntityCondition.makeCondition("dueDate", EntityOperator.LESS_THAN_EQUAL_TO, end),
                            EntityCondition.makeCondition(
                                    EntityCondition.makeCondition("paidDate", EntityOperator.EQUALS, null),
                                    EntityOperator.OR,
                                    EntityCondition.makeCondition("statusId", EntityOperator.NOT_EQUAL, "PAY_INST_PAID")
                            )
                    ), EntityOperator.AND))
                    .queryList();

            for (GenericValue i : inst) sum = sum.add(nvlMoney(i.getBigDecimal("installmentAmount")));
        }
        return sum;
    }

    // Strict default: 0 if no config/profile
    private static BigDecimal calculateIncomeTax(Delegator delegator, String organizationPartyId, String partyId, BigDecimal gross, Timestamp asOf) throws Exception {
        GenericValue taxProfile = EntityQuery.use(delegator).from("EmployeeTaxProfile")
                .where("organizationPartyId", organizationPartyId, "partyId", partyId)
                .queryFirst();
        if (taxProfile == null) return BigDecimal.ZERO;

        String countryGeoId = taxProfile.getString("countryGeoId");
        if (UtilValidate.isEmpty(countryGeoId)) return BigDecimal.ZERO;

        GenericValue cfg = EntityQuery.use(delegator).from("PayrollTaxConfig")
                .where("organizationPartyId", organizationPartyId, "countryGeoId", countryGeoId)
                .queryFirst();
        if (cfg == null) return BigDecimal.ZERO;

        BigDecimal rate = BigDecimal.ZERO;
        Object r = cfg.get("taxRate");
        if (r instanceof BigDecimal) rate = (BigDecimal) r;
        if (r instanceof Double) rate = BigDecimal.valueOf((Double) r);

        return gross.multiply(rate).setScale(2, RoundingMode.HALF_UP);
    }

    private static BigDecimal calculateSocialSecurity(Delegator delegator, String organizationPartyId, String partyId, BigDecimal gross, Timestamp asOf) throws Exception {
        GenericValue taxProfile = EntityQuery.use(delegator).from("EmployeeTaxProfile")
                .where("organizationPartyId", organizationPartyId, "partyId", partyId)
                .queryFirst();
        if (taxProfile == null) return BigDecimal.ZERO;

        String countryGeoId = taxProfile.getString("countryGeoId");
        if (UtilValidate.isEmpty(countryGeoId)) return BigDecimal.ZERO;

        GenericValue cfg = EntityQuery.use(delegator).from("SocialSecurityConfig")
                .where("organizationPartyId", organizationPartyId, "countryGeoId", countryGeoId)
                .orderBy("-effectiveDate")
                .queryFirst();
        if (cfg == null) return BigDecimal.ZERO;

        BigDecimal rate = BigDecimal.ZERO;
        Object r = cfg.get("employeeRate");
        if (r instanceof BigDecimal) rate = (BigDecimal) r;
        if (r instanceof Double) rate = BigDecimal.valueOf((Double) r);

        return gross.multiply(rate).setScale(2, RoundingMode.HALF_UP);
    }

    private static void createLine(Delegator delegator, String payrollResultId, String lineTypeId,
                                   String referenceId, String description, BigDecimal amount,
                                   String currencyUomId, int seq) throws GenericEntityException {
        GenericValue line = delegator.makeValue("EmployeePayrollResultLine");
        line.set("resultLineId", delegator.getNextSeqId("EmployeePayrollResultLine"));
        line.set("payrollResultId", payrollResultId);
        line.set("lineTypeId", lineTypeId);
        line.set("referenceId", referenceId);
        line.set("description", description);
        line.set("amount", amount.setScale(2, RoundingMode.HALF_UP));
        line.set("currencyUomId", currencyUomId);
        line.set("sequenceNum", (long) seq);
        line.create();
    }

    private static BigDecimal nvlMoney(BigDecimal v) {
        return v == null ? BigDecimal.ZERO : v;
    }

    private static boolean hasField(Delegator delegator, String entityName, String fieldName) {
        try {
            return delegator.getModelEntity(entityName) != null
                    && delegator.getModelEntity(entityName).getField(fieldName) != null;
        } catch (Exception e) {
            return false;
        }
    }

    private static String safeJson(GenericValue gv) {
        try {
            return gv == null ? null : gv.getAllFields().toString();
        } catch (Exception e) {
            return null;
        }
    }
}

