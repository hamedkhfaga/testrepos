package org.apache.ofbiz.payroll;

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
import org.apache.ofbiz.service.LocalDispatcher;
import org.apache.ofbiz.service.ServiceUtil;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Payroll Engine aligned to FINAL payroll entities:
 * - PayrollStructure, EmployeePayrollAssignment
 * - PayrollIteration, EmployeePayrollResult
 * Accounting handled via payroll.acctg entities/services (Pattern A/B)
 */
public class PayrollEngine {

    public static final String MODULE = PayrollEngine.class.getName();

    // ===== Payroll Iteration Status (StatusItem IDs) =====
    // Map these to your real StatusItem seed values.
    public static final String ITER_STATUS_NEW = "PRN_NEW";
    public static final String ITER_STATUS_CALC = "PRN_GEN";        // calculated/generated
    public static final String ITER_STATUS_APPROVED = "PRN_SENT";   // approved/sent
    public static final String ITER_STATUS_ERR = "PRN_ERR";

    // ===== Payroll Accounting Pattern Status (from PAY_ACCT_PAT / StatusItem) =====
    public static final String ACCT_PATTERN_A = "PAT_GL";   // Pattern A - GL Posting
    public static final String ACCT_PATTERN_B = "PAT_PAY";  // Pattern B - PayRun (Payment/Invoice)

    // ===== Default statuses for payroll domain (do NOT reuse FP statuses) =====
    public static final String ASSIGN_STATUS_ACTIVE = "ASSIGN_ACTIVE";
    public static final String ALLOW_STATUS_ACTIVE = "ALLOW_ACTIVE";

    // ======== Main Payroll Processing (FINAL) ========

    /**
     * Create PayrollIteration + EmployeePayrollResult rows for a period.
     *
     * Inputs:
     * - organizationPartyId (required)
     * - payrollStructureId (optional)
     * - payPeriodFrom (required)
     * - payPeriodThru (required)
     * - payDate (optional)
     * - currencyUomId (optional; if missing, derive from PayrollStructure)
     */
    public static Map<String, Object> processPayrollIteration(DispatchContext dctx, Map<String, ?> context) {
        Delegator delegator = dctx.getDelegator();
        GenericValue userLogin = (GenericValue) context.get("userLogin");

        String payrollIterationId = null;
        boolean beganTransaction = false;

        try {
            beganTransaction = TransactionUtil.begin();

            String organizationPartyId = (String) context.get("organizationPartyId");
            String payrollStructureId = (String) context.get("payrollStructureId");
            Timestamp payPeriodFrom = (Timestamp) context.get("payPeriodFrom");
            Timestamp payPeriodThru = (Timestamp) context.get("payPeriodThru");
            Timestamp payDate = (Timestamp) context.get("payDate");
            String currencyUomId = (String) context.get("currencyUomId");

            if (UtilValidate.isEmpty(organizationPartyId)) {
                return ServiceUtil.returnError("organizationPartyId is required");
            }
            if (payPeriodFrom == null || payPeriodThru == null) {
                return ServiceUtil.returnError("payPeriodFrom and payPeriodThru are required");
            }
            if (payDate == null) {
                payDate = UtilDateTime.nowTimestamp();
            }

            // Derive currency from PayrollStructure if not passed
            if (UtilValidate.isEmpty(currencyUomId) && UtilValidate.isNotEmpty(payrollStructureId)) {
                GenericValue ps = EntityQuery.use(delegator)
                        .from("PayrollStructure")
                        .where("payrollStructureId", payrollStructureId,
                               "organizationPartyId", organizationPartyId)
                        .queryOne();
                if (ps == null) {
                    return ServiceUtil.returnError("PayrollStructure not found for org/structure: " + payrollStructureId);
                }
                currencyUomId = ps.getString("currencyUomId");
            }
            if (UtilValidate.isEmpty(currencyUomId)) {
                return ServiceUtil.returnError("currencyUomId is required (or provide payrollStructureId with currency)");
            }

            payrollIterationId = delegator.getNextSeqId("PayrollIteration");

            GenericValue iter = delegator.makeValue("PayrollIteration");
            iter.set("payrollIterationId", payrollIterationId);
            iter.set("organizationPartyId", organizationPartyId);
            iter.set("iterationName", "PAY-" + payrollIterationId);
            iter.set("payPeriodFrom", payPeriodFrom);
            iter.set("payPeriodThru", payPeriodThru);
            iter.set("payDate", payDate);
            iter.set("statusId", ITER_STATUS_NEW);
            iter.set("currencyUomId", currencyUomId);
            iter.set("createdByUserLoginId", userLogin != null ? userLogin.getString("userLoginId") : null);
            iter.set("createdDate", UtilDateTime.nowTimestamp());

            // totals init
            iter.set("totalEmployees", 0L);
            iter.set("totalNetPay", BigDecimal.ZERO);
            iter.set("totalGrossPay", BigDecimal.ZERO);
            iter.set("totalDeductions", BigDecimal.ZERO);

            delegator.create(iter);

            // Fetch active assignments within org + date range
            List<GenericValue> assignments = getActiveAssignments(
                    delegator, organizationPartyId, payPeriodFrom, payPeriodThru, payrollStructureId);

            if (UtilValidate.isEmpty(assignments)) {
                if (beganTransaction) {
                    TransactionUtil.rollback();
                }
                return ServiceUtil.returnError("No active EmployeePayrollAssignment found for org/period");
            }

            BigDecimal totalGross = BigDecimal.ZERO;
            BigDecimal totalNet = BigDecimal.ZERO;
            BigDecimal totalDeds = BigDecimal.ZERO;
            long processed = 0;

            for (GenericValue asg : assignments) {
                String partyId = asg.getString("partyId");

                PayrollCalc calc = calculateEmployeePayroll(delegator, organizationPartyId, partyId, asg, payPeriodFrom, payPeriodThru);

                String payrollResultId = delegator.getNextSeqId("EmployeePayrollResult");
                GenericValue res = delegator.makeValue("EmployeePayrollResult");
                res.set("payrollResultId", payrollResultId);
                res.set("organizationPartyId", organizationPartyId);
                res.set("payrollIterationId", payrollIterationId);
                res.set("partyId", partyId);
                res.set("currencyUomId", currencyUomId);

                res.set("basicSalary", calc.basicSalary);
                res.set("overtimeAmount", calc.overtime);
                res.set("allowancesTotal", calc.allowances);
                res.set("bonuses", calc.bonuses);

                res.set("grossSalary", calc.gross);

                res.set("incomeTax", calc.incomeTax);
                res.set("socialSecurity", calc.socialSecurity);
                res.set("loanDeductions", calc.loanDeductions);
                res.set("otherDeductions", calc.otherDeductions);
                res.set("totalDeductions", calc.totalDeductions);

                res.set("netSalary", calc.net);

                res.set("createdByUserLoginId", userLogin != null ? userLogin.getString("userLoginId") : null);
                res.set("createdDate", UtilDateTime.nowTimestamp());

                delegator.create(res);

                processed++;
                totalGross = totalGross.add(calc.gross);
                totalNet = totalNet.add(calc.net);
                totalDeds = totalDeds.add(calc.totalDeductions);
            }

            iter.set("totalEmployees", processed);
            iter.set("totalGrossPay", totalGross.setScale(2, RoundingMode.HALF_UP));
            iter.set("totalNetPay", totalNet.setScale(2, RoundingMode.HALF_UP));
            iter.set("totalDeductions", totalDeds.setScale(2, RoundingMode.HALF_UP));
            iter.set("statusId", ITER_STATUS_CALC);
            delegator.store(iter);

            if (beganTransaction) {
                TransactionUtil.commit();
            }

            Map<String, Object> result = ServiceUtil.returnSuccess("Payroll iteration calculated successfully");
            result.put("payrollIterationId", payrollIterationId);
            result.put("organizationPartyId", organizationPartyId);
            result.put("currencyUomId", currencyUomId);
            result.put("totalEmployees", processed);
            result.put("totalGrossPay", totalGross);
            result.put("totalNetPay", totalNet);
            result.put("totalDeductions", totalDeds);
            return result;

        } catch (Exception e) {
            Debug.logError(e, "processPayrollIteration failed", MODULE);
            try {
                if (beganTransaction) {
                    TransactionUtil.rollback();
                }
            } catch (Exception ex) {
                Debug.logError(ex, "Rollback failed", MODULE);
            }
            return ServiceUtil.returnError("Payroll iteration processing failed: " + e.getMessage());
        }
    }

    /**
     * Approve PayrollIteration then trigger accounting based on PayrollAcctgConfig pattern (A/B).
     * Required: payrollIterationId
     */
    public static Map<String, Object> approvePayrollIteration(DispatchContext dctx, Map<String, ?> context) {
        Delegator delegator = dctx.getDelegator();
        LocalDispatcher dispatcher = dctx.getDispatcher();
        GenericValue userLogin = (GenericValue) context.get("userLogin");

        try {
            String payrollIterationId = (String) context.get("payrollIterationId");
            if (UtilValidate.isEmpty(payrollIterationId)) {
                return ServiceUtil.returnError("payrollIterationId is required");
            }

            GenericValue iter = EntityQuery.use(delegator)
                    .from("PayrollIteration")
                    .where("payrollIterationId", payrollIterationId)
                    .queryOne();
            if (iter == null) {
                return ServiceUtil.returnError("PayrollIteration not found: " + payrollIterationId);
            }

            String orgId = iter.getString("organizationPartyId");
            if (UtilValidate.isEmpty(orgId)) {
                return ServiceUtil.returnError("PayrollIteration.organizationPartyId is missing for: " + payrollIterationId);
            }

            // Mark approved
            iter.set("statusId", ITER_STATUS_APPROVED);
            iter.set("approvedByUserLoginId", userLogin != null ? userLogin.getString("userLoginId") : null);
            iter.set("approvedDate", UtilDateTime.nowTimestamp());
            delegator.store(iter);

            // Prefer structure-level config if payrollStructureId exists on iteration (optional design).
            // If you don't store payrollStructureId on PayrollIteration, it will just fallback to org config.
            String payrollStructureId = iter.getString("payrollStructureId"); // optional field in your model (safe even if null)

            GenericValue acctgCfg = null;

            if (UtilValidate.isNotEmpty(payrollStructureId)) {
                acctgCfg = EntityQuery.use(delegator)
                        .from("PayrollAcctgConfig")
                        .where("organizationPartyId", orgId,
                               "payrollStructureId", payrollStructureId)
                        .filterByDate()
                        .orderBy("-fromDate")
                        .queryFirst();
            }

            if (acctgCfg == null) {
                acctgCfg = EntityQuery.use(delegator)
                        .from("PayrollAcctgConfig")
                        .where("organizationPartyId", orgId)
                        .filterByDate()
                        .orderBy("-fromDate")
                        .queryFirst();
            }

            if (acctgCfg == null) {
                Map<String, Object> ok = ServiceUtil.returnSuccess(
                        "Iteration approved. No PayrollAcctgConfig found; accounting not triggered.");
                ok.put("payrollIterationId", payrollIterationId);
                ok.put("organizationPartyId", orgId);
                return ok;
            }

            String pattern = acctgCfg.getString("acctgPatternStatusId");

            if (ACCT_PATTERN_A.equals(pattern)) {
                dispatcher.runAsync("postPayrollAccountingPatternA", UtilMisc.toMap(
                        "payrollIterationId", payrollIterationId,
                        "userLogin", userLogin
                ));
            } else if (ACCT_PATTERN_B.equals(pattern)) {
                dispatcher.runAsync("generatePayrollPayRunPatternB", UtilMisc.toMap(
                        "payrollIterationId", payrollIterationId,
                        "userLogin", userLogin
                ));
            } else {
                Debug.logWarning("Unknown acctgPatternStatusId=" + pattern + " for org=" + orgId, MODULE);
            }

            Map<String, Object> result = ServiceUtil.returnSuccess("Payroll iteration approved successfully");
            result.put("payrollIterationId", payrollIterationId);
            result.put("organizationPartyId", orgId);
            result.put("acctgPatternStatusId", pattern);
            return result;

        } catch (Exception e) {
            Debug.logError(e, "approvePayrollIteration failed", MODULE);
            return ServiceUtil.returnError("Failed to approve payroll iteration: " + e.getMessage());
        }
    }

    // ======== Employee Payroll Calculation (baseline) ========

    private static class PayrollCalc {
        BigDecimal basicSalary = BigDecimal.ZERO;
        BigDecimal overtime = BigDecimal.ZERO;
        BigDecimal allowances = BigDecimal.ZERO;
        BigDecimal bonuses = BigDecimal.ZERO;

        BigDecimal gross = BigDecimal.ZERO;

        BigDecimal incomeTax = BigDecimal.ZERO;
        BigDecimal socialSecurity = BigDecimal.ZERO;
        BigDecimal loanDeductions = BigDecimal.ZERO;
        BigDecimal otherDeductions = BigDecimal.ZERO;
        BigDecimal totalDeductions = BigDecimal.ZERO;

        BigDecimal net = BigDecimal.ZERO;
    }

    /**
     * Minimal calculation using FINAL entities:
     * - baseSalary from EmployeePayrollAssignment.baseSalary
     * - allowances from EmployeeAllowance
     * - loan deductions from EmployeeLoan.monthlyInstallment (simplified)
     * - tax/social security placeholder (link later to PayrollTaxConfig/SocialSecurityConfig)
     */
    private static PayrollCalc calculateEmployeePayroll(
            Delegator delegator,
            String organizationPartyId,
            String partyId,
            GenericValue assignment,
            Timestamp fromDate,
            Timestamp thruDate) throws GenericEntityException {

        PayrollCalc calc = new PayrollCalc();

        BigDecimal base = assignment.getBigDecimal("baseSalary");
        calc.basicSalary = nvl(base);

        calc.allowances = calculateAllowances(delegator, organizationPartyId, partyId, fromDate, thruDate);
        calc.loanDeductions = calculateLoanDeductions(delegator, organizationPartyId, partyId);

        calc.incomeTax = BigDecimal.ZERO;
        calc.socialSecurity = BigDecimal.ZERO;
        calc.otherDeductions = BigDecimal.ZERO;

        calc.gross = calc.basicSalary.add(calc.overtime).add(calc.allowances).add(calc.bonuses);
        calc.totalDeductions = calc.incomeTax.add(calc.socialSecurity).add(calc.loanDeductions).add(calc.otherDeductions);
        calc.net = calc.gross.subtract(calc.totalDeductions);

        // scale
        calc.basicSalary = calc.basicSalary.setScale(2, RoundingMode.HALF_UP);
        calc.allowances = calc.allowances.setScale(2, RoundingMode.HALF_UP);
        calc.loanDeductions = calc.loanDeductions.setScale(2, RoundingMode.HALF_UP);
        calc.gross = calc.gross.setScale(2, RoundingMode.HALF_UP);
        calc.totalDeductions = calc.totalDeductions.setScale(2, RoundingMode.HALF_UP);
        calc.net = calc.net.setScale(2, RoundingMode.HALF_UP);

        return calc;
    }

    private static BigDecimal calculateAllowances(Delegator delegator, String orgId, String partyId,
                                                 Timestamp fromDate, Timestamp thruDate) throws GenericEntityException {

        List<EntityCondition> conds = new ArrayList<>();
        conds.add(EntityCondition.makeCondition("organizationPartyId", orgId));
        conds.add(EntityCondition.makeCondition("partyId", partyId));

        // IMPORTANT: do not reuse FP_USER_ACTIVE. Use payroll allowance active status.
        conds.add(EntityCondition.makeCondition("statusId", ALLOW_STATUS_ACTIVE));

        // date overlap
        conds.add(EntityCondition.makeCondition("effectiveDate", EntityOperator.LESS_THAN_EQUAL_TO, thruDate));
        conds.add(EntityCondition.makeCondition(
                EntityCondition.makeCondition("thruDate", EntityOperator.EQUALS, null),
                EntityOperator.OR,
                EntityCondition.makeCondition("thruDate", EntityOperator.GREATER_THAN_EQUAL_TO, fromDate)
        ));

        List<GenericValue> rows = EntityQuery.use(delegator)
                .from("EmployeeAllowance")
                .where(EntityCondition.makeCondition(conds, EntityOperator.AND))
                .queryList();

        BigDecimal total = BigDecimal.ZERO;
        for (GenericValue r : rows) {
            total = total.add(nvl(r.getBigDecimal("amount")));
        }
        return total;
    }

    private static BigDecimal calculateLoanDeductions(Delegator delegator, String orgId, String partyId)
            throws GenericEntityException {

        List<GenericValue> loans = EntityQuery.use(delegator)
                .from("EmployeeLoan")
                .where("organizationPartyId", orgId, "partyId", partyId)
                .queryList();

        BigDecimal total = BigDecimal.ZERO;
        for (GenericValue loan : loans) {
            BigDecimal monthly = loan.getBigDecimal("monthlyInstallment");
            total = total.add(nvl(monthly));
        }
        return total;
    }

    private static List<GenericValue> getActiveAssignments(Delegator delegator, String orgId,
                                                          Timestamp fromDate, Timestamp thruDate,
                                                          String payrollStructureId) throws GenericEntityException {

        List<EntityCondition> conds = new ArrayList<>();
        conds.add(EntityCondition.makeCondition("organizationPartyId", orgId));

        if (UtilValidate.isNotEmpty(payrollStructureId)) {
            conds.add(EntityCondition.makeCondition("payrollStructureId", payrollStructureId));
        }

        // date overlap
        conds.add(EntityCondition.makeCondition("effectiveDate", EntityOperator.LESS_THAN_EQUAL_TO, thruDate));
        conds.add(EntityCondition.makeCondition(
                EntityCondition.makeCondition("thruDate", EntityOperator.EQUALS, null),
                EntityOperator.OR,
                EntityCondition.makeCondition("thruDate", EntityOperator.GREATER_THAN_EQUAL_TO, fromDate)
        ));

        // IMPORTANT: do not reuse FP_USER_ACTIVE. Use payroll assignment active status.
        conds.add(EntityCondition.makeCondition("assignmentStatusId", ASSIGN_STATUS_ACTIVE));

        return EntityQuery.use(delegator)
                .from("EmployeePayrollAssignment")
                .where(EntityCondition.makeCondition(conds, EntityOperator.AND))
                .orderBy("partyId")
                .queryList();
    }

    private static BigDecimal nvl(BigDecimal v) {
        return v == null ? BigDecimal.ZERO : v;
    }

    private static BigDecimal toBigDecimal(Object obj) {
        if (obj == null) return null;
        if (obj instanceof BigDecimal) return (BigDecimal) obj;
        if (obj instanceof Double) return BigDecimal.valueOf((Double) obj);
        if (obj instanceof Float) return BigDecimal.valueOf((Float) obj);
        if (obj instanceof Long) return BigDecimal.valueOf((Long) obj);
        if (obj instanceof Integer) return BigDecimal.valueOf((Integer) obj);
        if (obj instanceof String) return new BigDecimal((String) obj);
        throw new IllegalArgumentException("Cannot convert to BigDecimal: " + obj);
    }

    // ======== Assignment Services (FINAL field names) ========

    /**
     * FINAL EmployeePayrollAssignment create service.
     * Sets:
     * - organizationPartyId (derived from PayrollStructure)
     * - employmentTypeStatusId / contractTypeStatusId / assignmentStatusId
     * - createdByUserLoginId / lastModifiedByUserLoginId
     */
    public static Map<String, Object> createEmployeePayrollAssignment(DispatchContext dctx, Map<String, ? extends Object> context) {
        Delegator delegator = dctx.getDelegator();
        GenericValue userLogin = (GenericValue) context.get("userLogin");

        try {
            String partyId = (String) context.get("partyId");
            String payrollStructureId = (String) context.get("payrollStructureId");

            String employmentTypeStatusId = (String) context.get("employmentTypeStatusId");
            String contractTypeStatusId = (String) context.get("contractTypeStatusId");
            String assignmentStatusId = (String) context.get("assignmentStatusId");

            BigDecimal baseSalary = toBigDecimal(context.get("baseSalary"));
            Timestamp effectiveDate = (Timestamp) context.get("effectiveDate");
            Timestamp thruDate = (Timestamp) context.get("thruDate");
            String comments = (String) context.get("comments");

            if (UtilValidate.isEmpty(partyId)) return ServiceUtil.returnError("partyId is required");
            if (UtilValidate.isEmpty(payrollStructureId)) return ServiceUtil.returnError("payrollStructureId is required");
            if (effectiveDate == null) return ServiceUtil.returnError("effectiveDate is required");

            if (delegator.findOne("Person", UtilMisc.toMap("partyId", partyId), false) == null) {
                return ServiceUtil.returnError("Employee does not exist: " + partyId);
            }

            GenericValue payrollStructure = delegator.findOne("PayrollStructure",
                    UtilMisc.toMap("payrollStructureId", payrollStructureId), false);
            if (payrollStructure == null) {
                return ServiceUtil.returnError("PayrollStructure does not exist: " + payrollStructureId);
            }

            String organizationPartyId = payrollStructure.getString("organizationPartyId");
            if (UtilValidate.isEmpty(organizationPartyId)) {
                return ServiceUtil.returnError("PayrollStructure.organizationPartyId is missing for: " + payrollStructureId);
            }

            if (UtilValidate.isEmpty(assignmentStatusId)) {
                assignmentStatusId = ASSIGN_STATUS_ACTIVE;
            }

            String assignmentId = delegator.getNextSeqId("EmployeePayrollAssignment");
            Timestamp now = UtilDateTime.nowTimestamp();

            GenericValue assignment = delegator.makeValue("EmployeePayrollAssignment");
            assignment.set("assignmentId", assignmentId);
            assignment.set("organizationPartyId", organizationPartyId);
            assignment.set("partyId", partyId);
            assignment.set("payrollStructureId", payrollStructureId);

            assignment.set("employmentTypeStatusId", employmentTypeStatusId);
            assignment.set("contractTypeStatusId", contractTypeStatusId);
            assignment.set("assignmentStatusId", assignmentStatusId);

            assignment.set("baseSalary", baseSalary);
            assignment.set("effectiveDate", effectiveDate);
            assignment.set("thruDate", thruDate);
            assignment.set("comments", comments);

            assignment.set("createdDate", now);
            assignment.set("lastModifiedDate", now);

            if (userLogin != null) {
                assignment.set("createdByUserLoginId", userLogin.getString("userLoginId"));
                assignment.set("lastModifiedByUserLoginId", userLogin.getString("userLoginId"));
            }

            delegator.create(assignment);

            Map<String, Object> result = ServiceUtil.returnSuccess("Employee payroll assignment created successfully");
            result.put("assignmentId", assignmentId);
            result.put("organizationPartyId", organizationPartyId);
            return result;

        } catch (Exception e) {
            Debug.logError(e, "createEmployeePayrollAssignment failed", MODULE);
            return ServiceUtil.returnError("Failed to create assignment: " + e.getMessage());
        }
    }

    /**
     * REST create payroll assignment - same logic, same fields.
     */
    public static Map<String, Object> createPayrollAssignmentRest(DispatchContext dctx, Map<String, ? extends Object> context) {
        return createEmployeePayrollAssignment(dctx, context);
    }
}
