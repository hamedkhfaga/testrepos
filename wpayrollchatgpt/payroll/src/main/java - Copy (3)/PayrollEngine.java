package org.apache.ofbiz.payroll;

import org.apache.ofbiz.entity.Delegator;
import org.apache.ofbiz.entity.GenericEntityException;
import org.apache.ofbiz.entity.GenericValue;
import org.apache.ofbiz.entity.condition.EntityCondition;
import org.apache.ofbiz.entity.condition.EntityOperator;
import org.apache.ofbiz.entity.transaction.TransactionUtil;
import org.apache.ofbiz.entity.util.EntityQuery;
import org.apache.ofbiz.base.util.Debug;
import org.apache.ofbiz.base.util.UtilMisc;
import org.apache.ofbiz.base.util.UtilValidate;
import org.apache.ofbiz.service.DispatchContext;
import org.apache.ofbiz.service.GenericServiceException;
import org.apache.ofbiz.service.LocalDispatcher;
import org.apache.ofbiz.service.ServiceUtil;
import org.apache.ofbiz.base.util.UtilDateTime;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Date;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Complete Payroll Processing Engine.
 * Replaces existing payroll engine with enhanced functionality.
 */
public class PayrollEngine {

    /** Module name for logging. */
    public static final String MODULE = PayrollEngine.class.getName();

    /** Resource name for UI labels. */
    public static final String RESOURCE = "HumanResUiLabels";

    // Payroll Status Constants
    public static final String STATUS_DRAFT = "PAYROLL_DRAFT";
    public static final String STATUS_CALCULATED = "PAYROLL_CALCULATED";
    public static final String STATUS_APPROVED = "PAYROLL_APPROVED";
    public static final String STATUS_PAID = "PAYROLL_PAID";
    public static final String STATUS_CANCELLED = "PAYROLL_CANCELLED";

    // Tax Configuration
    private static final BigDecimal TAX_THRESHOLD_1 = new BigDecimal("10000");
    private static final BigDecimal TAX_THRESHOLD_2 = new BigDecimal("50000");
    private static final BigDecimal TAX_RATE_1 = new BigDecimal("0.10");
    private static final BigDecimal TAX_RATE_2 = new BigDecimal("0.20");
    private static final BigDecimal TAX_RATE_3 = new BigDecimal("0.30");
    private static final BigDecimal SOCIAL_SECURITY_RATE = new BigDecimal("0.062");
    private static final BigDecimal PENSION_RATE = new BigDecimal("0.05");

    /**
     * Main Payroll Processing Service.
     * @param dctx DispatchContext
     * @param context Service context
     * @return Result map
     */
    public static Map<String, Object> processPayroll(DispatchContext dctx, Map<String, ?> context) {
        Delegator delegator = dctx.getDelegator();
        LocalDispatcher dispatcher = dctx.getDispatcher();
        GenericValue userLogin = (GenericValue) context.get("userLogin");
        Map<String, Object> result = ServiceUtil.returnSuccess();

        String payrollRunId = null;
        boolean beganTransaction = false;

        try {
            // Start transaction
            beganTransaction = TransactionUtil.begin();

            // Get parameters
            String payrollRunIdParam = (String) context.get("payrollRunId");
            String payrollPeriodId = (String) context.get("payrollPeriodId");
            Date fromDate = (Date) context.get("fromDate");
            Date thruDate = (Date) context.get("thruDate");
            String payrollType = (String) context.get("payrollType");

            if (UtilValidate.isEmpty(payrollRunIdParam)) {
                payrollRunId = delegator.getNextSeqId("PayrollRun");
            } else {
                payrollRunId = payrollRunIdParam;
            }

            Debug.logInfo("Starting payroll processing for run: " + payrollRunId, MODULE);

            // Create payroll run record
            GenericValue payrollRun = delegator.makeValue("PayrollRun");
            payrollRun.set("payrollRunId", payrollRunId);
            payrollRun.set("payrollPeriodId", payrollPeriodId);
            payrollRun.set("fromDate", fromDate);
            payrollRun.set("thruDate", thruDate);
            payrollRun.set("payrollType", payrollType != null ? payrollType : "REGULAR");
            payrollRun.set("runDate", new Timestamp(System.currentTimeMillis()));
            payrollRun.set("statusId", STATUS_DRAFT);
            payrollRun.set("createdByUserLogin", userLogin.getString("userLoginId"));
            delegator.create(payrollRun);

            // Get all active employees for this period
            List<GenericValue> activeEmployees = getActiveEmployees(delegator, fromDate, thruDate);

            if (UtilValidate.isEmpty(activeEmployees)) {
                if (beganTransaction) {
                    TransactionUtil.rollback();
                }
                return ServiceUtil.returnError("No active employees found for the specified period");
            }

            List<Map<String, Object>> employeeResults = new ArrayList<>();
            BigDecimal totalGross = BigDecimal.ZERO;
            BigDecimal totalNet = BigDecimal.ZERO;
            BigDecimal totalDeductions = BigDecimal.ZERO;
            int processedCount = 0;
            int errorCount = 0;

            // Process each employee
            for (GenericValue employee : activeEmployees) {
                String partyId = employee.getString("partyId");

                try {
                    Map<String, Object> empResult = processEmployeePayroll(
                            delegator, dispatcher, payrollRunId, partyId, fromDate, thruDate, userLogin);

                    if (empResult != null && ServiceUtil.isSuccess(empResult)) {
                        employeeResults.add(empResult);
                        processedCount++;

                        // Update totals
                        BigDecimal gross = (BigDecimal) empResult.get("grossEarnings");
                        BigDecimal net = (BigDecimal) empResult.get("netPay");
                        BigDecimal deductions = (BigDecimal) empResult.get("totalDeductions");

                        if (gross != null) {
                            totalGross = totalGross.add(gross);
                        }
                        if (net != null) {
                            totalNet = totalNet.add(net);
                        }
                        if (deductions != null) {
                            totalDeductions = totalDeductions.add(deductions);
                        }

                        // Create payroll entry
                        createPayrollEntry(delegator, payrollRunId, partyId, empResult, userLogin);
                    }
                } catch (Exception e) {
                    errorCount++;
                    Debug.logError(e, "Error processing payroll for employee: " + partyId, MODULE);
                    // Log error but continue with other employees
                    logPayrollError(delegator, payrollRunId, partyId, e.getMessage(), userLogin);
                }
            }

            // Update payroll run with totals
            payrollRun.set("totalGross", totalGross);
            payrollRun.set("totalNet", totalNet);
            payrollRun.set("totalDeductions", totalDeductions);
            payrollRun.set("employeeCount", (long) processedCount);
            payrollRun.set("errorCount", (long) errorCount);
            payrollRun.set("statusId", STATUS_CALCULATED);
            payrollRun.set("calculatedDate", new Timestamp(System.currentTimeMillis()));
            delegator.store(payrollRun);

            // Generate summary report
            generatePayrollSummary(delegator, payrollRunId, employeeResults);

            if (beganTransaction) {
                TransactionUtil.commit();
            }

            // Prepare result
            result.put("payrollRunId", payrollRunId);
            result.put("processedEmployees", processedCount);
            result.put("errors", errorCount);
            result.put("totalGross", totalGross);
            result.put("totalNet", totalNet);
            result.put("totalDeductions", totalDeductions);
            result.put("employeeResults", employeeResults);
            result.put("message", "Payroll processed successfully for " + processedCount + " employees");

            Debug.logInfo("Payroll processing completed. Processed: " + processedCount
                    + ", Errors: " + errorCount, MODULE);

        } catch (Exception e) {
            try {
                if (beganTransaction) {
                    TransactionUtil.rollback();
                }
            } catch (Exception ex) {
                Debug.logError(ex, "Error rolling back transaction", MODULE);
            }
            Debug.logError(e, "Critical error in payroll processing", MODULE);
            return ServiceUtil.returnError("Payroll processing failed: " + e.getMessage());
        }

        return result;
    }

    /**
     * Process individual employee payroll.
     * @param delegator Delegator
     * @param dispatcher LocalDispatcher
     * @param payrollRunId Payroll run ID
     * @param partyId Party ID
     * @param fromDate From date
     * @param thruDate Thru date
     * @param userLogin User login
     * @return Employee payroll result
     * @throws GenericEntityException if entity error
     * @throws GenericServiceException if service error
     */
    private static Map<String, Object> processEmployeePayroll(
            Delegator delegator, LocalDispatcher dispatcher,
            String payrollRunId, String partyId, Date fromDate, Date thruDate,
            GenericValue userLogin) throws GenericEntityException, GenericServiceException {

        Map<String, Object> result = new HashMap<>();

        try {
            // Get employee basic information
            GenericValue person = EntityQuery.use(delegator)
                    .from("Person")
                    .where("partyId", partyId)
                    .queryOne();

            if (person == null) {
                throw new GenericServiceException("Employee not found: " + partyId);
            }

            // Get employment details
            GenericValue employment = EntityQuery.use(delegator)
                    .from("Employment")
                    .where("partyIdTo", partyId)
                    .filterByDate()
                    .queryFirst();

            if (employment == null) {
                throw new GenericServiceException("No active employment found for employee: " + partyId);
            }

            // Get payroll preference
            GenericValue payrollPref = EntityQuery.use(delegator)
                    .from("PayrollPreference")
                    .where("partyId", partyId, "statusId", "ACTIVE")
                    .queryFirst();

            // Calculate basic salary
            BigDecimal baseSalary = calculateBaseSalary(employment, payrollPref, fromDate, thruDate);

            // Calculate overtime
            BigDecimal overtimePay = calculateOvertime(delegator, partyId, fromDate, thruDate);

            // Calculate allowances
            BigDecimal allowances = calculateAllowances(delegator, partyId, fromDate, thruDate);

            // Calculate bonuses
            BigDecimal bonuses = calculateBonuses(delegator, partyId, fromDate, thruDate);

            // Calculate commissions
            BigDecimal commissions = calculateCommissions(delegator, partyId, fromDate, thruDate);

            // Calculate gross earnings
            BigDecimal grossEarnings = baseSalary
                    .add(overtimePay)
                    .add(allowances)
                    .add(bonuses)
                    .add(commissions);

            // Calculate deductions
            Map<String, BigDecimal> deductions = calculateDeductions(
                    delegator, partyId, grossEarnings, fromDate, thruDate);

            BigDecimal totalDeductions = BigDecimal.ZERO;
            for (BigDecimal amount : deductions.values()) {
                totalDeductions = totalDeductions.add(amount);
            }

            // Calculate net pay
            BigDecimal netPay = grossEarnings.subtract(totalDeductions);

            // Populate result
            result.put("partyId", partyId);
            result.put("employeeName", person.getString("firstName") + " " + person.getString("lastName"));
            result.put("employeeId", employment.getString("employeeId"));
            result.put("position", employment.getString("positionTitle"));
            result.put("department", employment.getString("departmentId"));
            result.put("baseSalary", baseSalary.setScale(2, RoundingMode.HALF_UP));
            result.put("overtimePay", overtimePay.setScale(2, RoundingMode.HALF_UP));
            result.put("allowances", allowances.setScale(2, RoundingMode.HALF_UP));
            result.put("bonuses", bonuses.setScale(2, RoundingMode.HALF_UP));
            result.put("commissions", commissions.setScale(2, RoundingMode.HALF_UP));
            result.put("grossEarnings", grossEarnings.setScale(2, RoundingMode.HALF_UP));
            result.put("deductions", deductions);
            result.put("totalDeductions", totalDeductions.setScale(2, RoundingMode.HALF_UP));
            result.put("netPay", netPay.setScale(2, RoundingMode.HALF_UP));
            result.put("paymentMethod", payrollPref != null
                    ? payrollPref.getString("paymentMethodId") : "BANK_TRANSFER");

            // Generate payslip reference
            String payslipRef = "PSL" + payrollRunId + "-" + partyId + "-"
                    + new Timestamp(System.currentTimeMillis()).getTime();
            result.put("payslipReference", payslipRef);

            result.put("success", true);

        } catch (Exception e) {
            result.put("success", false);
            result.put("error", e.getMessage());
            throw new GenericServiceException("Failed to process payroll for employee "
                    + partyId + ": " + e.getMessage(), e);
        }

        return result;
    }

    /**
     * Calculate base salary.
     * @param employment Employment record
     * @param payrollPref Payroll preference
     * @param fromDate From date
     * @param thruDate Thru date
     * @return Base salary
     */
    private static BigDecimal calculateBaseSalary(GenericValue employment,
            GenericValue payrollPref, Date fromDate, Date thruDate) {

        BigDecimal salaryRate = employment.getBigDecimal("salary");
        if (salaryRate == null) {
            salaryRate = BigDecimal.ZERO;
        }

        // Check for salary adjustments in period
        List<GenericValue> adjustments = null;
        try {
            adjustments = EntityQuery.use(employment.getDelegator())
                    .from("SalaryAdjustment")
                    .where("partyId", employment.getString("partyIdTo"))
                    .queryList();
        } catch (GenericEntityException e) {
            Debug.logWarning(e, "Error fetching salary adjustments", MODULE);
        }

        BigDecimal adjustedSalary = salaryRate;
        if (UtilValidate.isNotEmpty(adjustments)) {
            for (GenericValue adj : adjustments) {
                String adjustmentType = adj.getString("adjustmentTypeId");
                BigDecimal amount = adj.getBigDecimal("amount");
                BigDecimal percentage = adj.getBigDecimal("percentage");

                if ("SALARY_INCREASE".equals(adjustmentType)) {
                    if (amount != null) {
                        adjustedSalary = adjustedSalary.add(amount);
                    } else if (percentage != null) {
                        adjustedSalary = adjustedSalary.multiply(
                                BigDecimal.ONE.add(percentage.divide(new BigDecimal("100"))));
                    }
                } else if ("SALARY_DECREASE".equals(adjustmentType)) {
                    if (amount != null) {
                        adjustedSalary = adjustedSalary.subtract(amount);
                    } else if (percentage != null) {
                        adjustedSalary = adjustedSalary.multiply(
                                BigDecimal.ONE.subtract(percentage.divide(new BigDecimal("100"))));
                    }
                }
            }
        }

        // Calculate pro-rata for partial period
        int totalDays = getDaysBetween(fromDate, thruDate);
        int workingDays = getWorkingDays(fromDate, thruDate);
        double prorataFactor = (double) workingDays / totalDays;

        BigDecimal monthlySalary = adjustedSalary;
        if (payrollPref != null && "WEEKLY".equals(payrollPref.getString("periodTypeId"))) {
            return monthlySalary.divide(new BigDecimal("4.33"), 2, RoundingMode.HALF_UP);
        } else if (payrollPref != null && "BIWEEKLY".equals(payrollPref.getString("periodTypeId"))) {
            return monthlySalary.divide(new BigDecimal("2.167"), 2, RoundingMode.HALF_UP);
        } else {
            // Monthly payroll
            return monthlySalary.multiply(new BigDecimal(prorataFactor))
                    .setScale(2, RoundingMode.HALF_UP);
        }
    }

    /**
     * Calculate overtime.
     * @param delegator Delegator
     * @param partyId Party ID
     * @param fromDate From date
     * @param thruDate Thru date
     * @return Overtime amount
     * @throws GenericEntityException if entity error
     */
    private static BigDecimal calculateOvertime(Delegator delegator, String partyId,
            Date fromDate, Date thruDate) throws GenericEntityException {

        BigDecimal totalOvertime = BigDecimal.ZERO;

        List<EntityCondition> conditions = new ArrayList<>();
        conditions.add(EntityCondition.makeCondition("partyId", partyId));
        conditions.add(EntityCondition.makeCondition("workDate",
                EntityOperator.GREATER_THAN_EQUAL_TO, fromDate));
        conditions.add(EntityCondition.makeCondition("workDate",
                EntityOperator.LESS_THAN_EQUAL_TO, thruDate));
        conditions.add(EntityCondition.makeCondition("isOvertime", "Y"));

        EntityCondition whereCond = EntityCondition.makeCondition(conditions, EntityOperator.AND);

        List<GenericValue> overtimeRecords = EntityQuery.use(delegator)
                .from("Timesheet")
                .where(whereCond)
                .queryList();

        for (GenericValue record : overtimeRecords) {
            BigDecimal hours = record.getBigDecimal("hours");
            String overtimeType = record.getString("overtimeTypeId");
            BigDecimal rateMultiplier = getOvertimeRate(overtimeType);

            // Get hourly rate
            BigDecimal hourlyRate = getHourlyRate(delegator, partyId);

            if (hours != null && hourlyRate != null) {
                BigDecimal overtimeAmount = hours.multiply(hourlyRate).multiply(rateMultiplier);
                totalOvertime = totalOvertime.add(overtimeAmount);
            }
        }

        return totalOvertime.setScale(2, RoundingMode.HALF_UP);
    }

    /**
     * Calculate allowances.
     * @param delegator Delegator
     * @param partyId Party ID
     * @param fromDate From date
     * @param thruDate Thru date
     * @return Allowances total
     * @throws GenericEntityException if entity error
     */
    private static BigDecimal calculateAllowances(Delegator delegator, String partyId,
            Date fromDate, Date thruDate) throws GenericEntityException {

        BigDecimal totalAllowances = BigDecimal.ZERO;

        // Build date range conditions
        List<EntityCondition> dateConditions = new ArrayList<>();
        dateConditions.add(EntityCondition.makeCondition("fromDate",
                EntityOperator.LESS_THAN_EQUAL_TO, thruDate));
        dateConditions.add(EntityCondition.makeCondition(
                EntityCondition.makeCondition("thruDate", EntityOperator.EQUALS, null),
                EntityOperator.OR,
                EntityCondition.makeCondition("thruDate",
                        EntityOperator.GREATER_THAN_EQUAL_TO, fromDate)));

        List<EntityCondition> allConditions = new ArrayList<>();
        allConditions.add(EntityCondition.makeCondition("partyId", partyId));
        allConditions.add(EntityCondition.makeCondition("statusId", "ACTIVE"));
        allConditions.add(EntityCondition.makeCondition(dateConditions, EntityOperator.AND));

        EntityCondition whereCondition = EntityCondition.makeCondition(allConditions, EntityOperator.AND);

        List<GenericValue> allowances = EntityQuery.use(delegator)
                .from("EmployeeAllowance")
                .where(whereCondition)
                .queryList();

        for (GenericValue allowance : allowances) {
            BigDecimal amount = allowance.getBigDecimal("amount");
            String frequency = allowance.getString("frequency");

            if (amount != null) {
                if ("MONTHLY".equals(frequency)) {
                    totalAllowances = totalAllowances.add(amount);
                } else if ("DAILY".equals(frequency)) {
                    int workingDays = getWorkingDays(fromDate, thruDate);
                    totalAllowances = totalAllowances.add(amount.multiply(new BigDecimal(workingDays)));
                } else if ("ONE_TIME".equals(frequency)) {
                    // Check if this is the correct period
                    Date effDate = allowance.getDate("effectiveDate");
                    if (effDate != null && !effDate.before(fromDate) && !effDate.after(thruDate)) {
                        totalAllowances = totalAllowances.add(amount);
                    }
                }
            }
        }

        return totalAllowances.setScale(2, RoundingMode.HALF_UP);
    }

    /**
     * Calculate deductions.
     * @param delegator Delegator
     * @param partyId Party ID
     * @param grossEarnings Gross earnings
     * @param fromDate From date
     * @param thruDate Thru date
     * @return Deductions map
     * @throws GenericEntityException if entity error
     */
    private static Map<String, BigDecimal> calculateDeductions(Delegator delegator,
            String partyId, BigDecimal grossEarnings, Date fromDate, Date thruDate)
            throws GenericEntityException {

        Map<String, BigDecimal> deductions = new LinkedHashMap<>();

        // Calculate income tax
        BigDecimal incomeTax = calculateIncomeTax(grossEarnings);
        deductions.put("incomeTax", incomeTax);

        // Calculate social security
        BigDecimal socialSecurity = calculateSocialSecurity(grossEarnings);
        deductions.put("socialSecurity", socialSecurity);

        // Calculate pension contribution
        BigDecimal pension = calculatePension(grossEarnings);
        deductions.put("pension", pension);

        // Calculate loan deductions
        BigDecimal loanDeductions = calculateLoanDeductions(delegator, partyId);
        deductions.put("loanDeductions", loanDeductions);

        // Calculate other deductions
        BigDecimal otherDeductions = calculateOtherDeductions(delegator, partyId, fromDate, thruDate);
        deductions.put("otherDeductions", otherDeductions);

        // Calculate advance salary deductions
        BigDecimal advanceDeductions = calculateAdvanceDeductions(delegator, partyId);
        deductions.put("advanceDeductions", advanceDeductions);

        return deductions;
    }

    /**
     * Calculate income tax (progressive tax system).
     * @param grossEarnings Gross earnings
     * @return Income tax
     */
    private static BigDecimal calculateIncomeTax(BigDecimal grossEarnings) {
        if (grossEarnings == null || grossEarnings.compareTo(BigDecimal.ZERO) <= 0) {
            return BigDecimal.ZERO;
        }

        if (grossEarnings.compareTo(TAX_THRESHOLD_1) <= 0) {
            return grossEarnings.multiply(TAX_RATE_1);
        } else if (grossEarnings.compareTo(TAX_THRESHOLD_2) <= 0) {
            BigDecimal tax1 = TAX_THRESHOLD_1.multiply(TAX_RATE_1);
            BigDecimal remaining = grossEarnings.subtract(TAX_THRESHOLD_1);
            BigDecimal tax2 = remaining.multiply(TAX_RATE_2);
            return tax1.add(tax2);
        } else {
            BigDecimal tax1 = TAX_THRESHOLD_1.multiply(TAX_RATE_1);
            BigDecimal tax2 = TAX_THRESHOLD_2.subtract(TAX_THRESHOLD_1).multiply(TAX_RATE_2);
            BigDecimal remaining = grossEarnings.subtract(TAX_THRESHOLD_2);
            BigDecimal tax3 = remaining.multiply(TAX_RATE_3);
            return tax1.add(tax2).add(tax3);
        }
    }

    /**
     * Calculate social security.
     * @param grossEarnings Gross earnings
     * @return Social security amount
     */
    private static BigDecimal calculateSocialSecurity(BigDecimal grossEarnings) {
        if (grossEarnings == null || grossEarnings.compareTo(BigDecimal.ZERO) <= 0) {
            return BigDecimal.ZERO;
        }
        return grossEarnings.multiply(SOCIAL_SECURITY_RATE).setScale(2, RoundingMode.HALF_UP);
    }

    /**
     * Calculate pension.
     * @param grossEarnings Gross earnings
     * @return Pension amount
     */
    private static BigDecimal calculatePension(BigDecimal grossEarnings) {
        if (grossEarnings == null || grossEarnings.compareTo(BigDecimal.ZERO) <= 0) {
            return BigDecimal.ZERO;
        }
        return grossEarnings.multiply(PENSION_RATE).setScale(2, RoundingMode.HALF_UP);
    }

    /**
     * Calculate loan deductions.
     * @param delegator Delegator
     * @param partyId Party ID
     * @return Loan deductions
     * @throws GenericEntityException if entity error
     */
    private static BigDecimal calculateLoanDeductions(Delegator delegator, String partyId)
            throws GenericEntityException {

        BigDecimal totalLoanDeductions = BigDecimal.ZERO;

        List<GenericValue> activeLoans = EntityQuery.use(delegator)
                .from("EmployeeLoan")
                .where("partyId", partyId, "statusId", "ACTIVE")
                .queryList();

        for (GenericValue loan : activeLoans) {
            BigDecimal installmentAmount = loan.getBigDecimal("installmentAmount");
            if (installmentAmount != null) {
                totalLoanDeductions = totalLoanDeductions.add(installmentAmount);
            }
        }

        return totalLoanDeductions.setScale(2, RoundingMode.HALF_UP);
    }

    /**
     * Create payroll entry for employee.
     * @param delegator Delegator
     * @param payrollRunId Payroll run ID
     * @param partyId Party ID
     * @param empResult Employee result
     * @param userLogin User login
     * @throws GenericEntityException if entity error
     */
    @SuppressWarnings("unchecked")
    private static void createPayrollEntry(Delegator delegator, String payrollRunId,
            String partyId, Map<String, Object> empResult, GenericValue userLogin)
            throws GenericEntityException {

        String payrollEntryId = delegator.getNextSeqId("PayrollEntry");

        GenericValue payrollEntry = delegator.makeValue("PayrollEntry");
        payrollEntry.set("payrollEntryId", payrollEntryId);
        payrollEntry.set("payrollRunId", payrollRunId);
        payrollEntry.set("partyId", partyId);
        payrollEntry.set("employeeId", (String) empResult.get("employeeId"));
        payrollEntry.set("baseSalary", (BigDecimal) empResult.get("baseSalary"));
        payrollEntry.set("overtimePay", (BigDecimal) empResult.get("overtimePay"));
        payrollEntry.set("allowances", (BigDecimal) empResult.get("allowances"));
        payrollEntry.set("bonuses", (BigDecimal) empResult.get("bonuses"));
        payrollEntry.set("commissions", (BigDecimal) empResult.get("commissions"));
        payrollEntry.set("grossEarnings", (BigDecimal) empResult.get("grossEarnings"));

        // Safely extract deductions with proper type checking
        Map<String, BigDecimal> deductions = (Map<String, BigDecimal>) empResult.get("deductions");
        if (deductions != null) {
            payrollEntry.set("incomeTax", deductions.get("incomeTax"));
            payrollEntry.set("socialSecurity", deductions.get("socialSecurity"));
            payrollEntry.set("pension", deductions.get("pension"));
            payrollEntry.set("loanDeductions", deductions.get("loanDeductions"));
            payrollEntry.set("otherDeductions", deductions.get("otherDeductions"));
            payrollEntry.set("advanceDeductions", deductions.get("advanceDeductions"));
        }

        payrollEntry.set("totalDeductions", (BigDecimal) empResult.get("totalDeductions"));
        payrollEntry.set("netPay", (BigDecimal) empResult.get("netPay"));
        payrollEntry.set("payslipReference", (String) empResult.get("payslipReference"));
        payrollEntry.set("statusId", "ENTRY_CREATED");
        payrollEntry.set("createdByUserLogin", userLogin.getString("userLoginId"));
        payrollEntry.set("createdDate", new Timestamp(System.currentTimeMillis()));

        delegator.create(payrollEntry);

        // Create accounting transaction for this entry
        createAccountingTransaction(delegator, payrollEntryId, partyId, empResult);
    }

    /**
     * Create accounting transaction for payroll entry.
     * @param delegator Delegator
     * @param payrollEntryId Payroll entry ID
     * @param partyId Party ID
     * @param empResult Employee result
     * @throws GenericEntityException if entity error
     */
    @SuppressWarnings("unchecked")
    private static void createAccountingTransaction(Delegator delegator,
            String payrollEntryId, String partyId, Map<String, Object> empResult)
            throws GenericEntityException {

        String acctgTransId = delegator.getNextSeqId("AcctgTrans");

        GenericValue acctgTrans = delegator.makeValue("AcctgTrans");
        acctgTrans.set("acctgTransId", acctgTransId);
        acctgTrans.set("acctgTransTypeId", "PAYROLL_EXPENSE");
        acctgTrans.set("description", "Payroll for employee " + partyId);
        acctgTrans.set("transactionDate", new Timestamp(System.currentTimeMillis()));
        acctgTrans.set("isPosted", "N");
        delegator.create(acctgTrans);

        // Create debit entry (Salary Expense)
        String entryId1 = delegator.getNextSeqId("AcctgTransEntry");
        GenericValue entry1 = delegator.makeValue("AcctgTransEntry");
        entry1.set("acctgTransEntrySeqId", entryId1);
        entry1.set("acctgTransId", acctgTransId);
        entry1.set("debitCreditFlag", "D");
        entry1.set("amount", (BigDecimal) empResult.get("grossEarnings"));
        entry1.set("glAccountId", "511000"); // Salary Expense account
        entry1.set("description", "Salary expense for employee");
        delegator.create(entry1);

        // Create credit entry (Tax Payable)
        Map<String, BigDecimal> deductions = (Map<String, BigDecimal>) empResult.get("deductions");
        if (deductions != null && deductions.get("incomeTax") != null) {
            String entryId2 = delegator.getNextSeqId("AcctgTransEntry");
            GenericValue entry2 = delegator.makeValue("AcctgTransEntry");
            entry2.set("acctgTransEntrySeqId", entryId2);
            entry2.set("acctgTransId", acctgTransId);
            entry2.set("debitCreditFlag", "C");
            entry2.set("amount", deductions.get("incomeTax"));
            entry2.set("glAccountId", "221100"); // Tax Payable account
            entry2.set("description", "Income tax payable");
            delegator.create(entry2);
        }

        // Create credit entry (Net Pay)
        String entryId3 = delegator.getNextSeqId("AcctgTransEntry");
        GenericValue entry3 = delegator.makeValue("AcctgTransEntry");
        entry3.set("acctgTransEntrySeqId", entryId3);
        entry3.set("acctgTransId", acctgTransId);
        entry3.set("debitCreditFlag", "C");
        entry3.set("amount", (BigDecimal) empResult.get("netPay"));
        entry3.set("glAccountId", "201100"); // Salary Payable account
        entry3.set("description", "Net salary payable");
        delegator.create(entry3);
    }

    /**
     * Get active employees for period.
     * @param delegator Delegator
     * @param fromDate From date
     * @param thruDate Thru date
     * @return List of active employees
     * @throws GenericEntityException if entity error
     */
    private static List<GenericValue> getActiveEmployees(Delegator delegator,
            Date fromDate, Date thruDate) throws GenericEntityException {

        List<EntityCondition> conditions = new ArrayList<>();
        conditions.add(EntityCondition.makeCondition("employmentStatusId", "EMPL_ACTIVE"));
        conditions.add(EntityCondition.makeCondition("thruDate", EntityOperator.EQUALS, null));
        conditions.add(EntityCondition.makeCondition("fromDate",
                EntityOperator.LESS_THAN_EQUAL_TO, thruDate));

        EntityCondition whereCond = EntityCondition.makeCondition(conditions, EntityOperator.AND);

        return EntityQuery.use(delegator)
                .from("Employment")
                .where(whereCond)
                .orderBy("partyIdTo")
                .queryList();
    }

    /**
     * Helper: Get days between dates.
     * @param fromDate From date
     * @param thruDate Thru date
     * @return Number of days
     */
    private static int getDaysBetween(Date fromDate, Date thruDate) {
        if (fromDate == null || thruDate == null) {
            return 30;
        }
        long diff = thruDate.getTime() - fromDate.getTime();
        return (int) (diff / (1000 * 60 * 60 * 24)) + 1;
    }

    /**
     * Helper: Get working days.
     * @param fromDate From date
     * @param thruDate Thru date
     * @return Number of working days
     */
    private static int getWorkingDays(Date fromDate, Date thruDate) {
        if (fromDate == null || thruDate == null) {
            return 20;
        }

        Calendar cal = Calendar.getInstance();
        cal.setTime(fromDate);

        int workingDays = 0;
        while (!cal.getTime().after(thruDate)) {
            int dayOfWeek = cal.get(Calendar.DAY_OF_WEEK);
            if (dayOfWeek != Calendar.SATURDAY && dayOfWeek != Calendar.SUNDAY) {
                workingDays++;
            }
            cal.add(Calendar.DATE, 1);
        }
        return workingDays;
    }

    /**
     * Helper: Get overtime rate.
     * @param overtimeType Overtime type
     * @return Rate multiplier
     */
    private static BigDecimal getOvertimeRate(String overtimeType) {
        if ("NIGHT".equals(overtimeType)) {
            return new BigDecimal("1.5");
        }
        if ("HOLIDAY".equals(overtimeType)) {
            return new BigDecimal("2.0");
        }
        if ("WEEKEND".equals(overtimeType)) {
            return new BigDecimal("1.75");
        }
        return new BigDecimal("1.25"); // Regular overtime
    }

    /**
     * Helper: Get hourly rate.
     * @param delegator Delegator
     * @param partyId Party ID
     * @return Hourly rate
     * @throws GenericEntityException if entity error
     */
    private static BigDecimal getHourlyRate(Delegator delegator, String partyId)
            throws GenericEntityException {

        GenericValue employment = EntityQuery.use(delegator)
                .from("Employment")
                .where("partyIdTo", partyId)
                .filterByDate()
                .queryFirst();

        if (employment != null) {
            BigDecimal monthlySalary = employment.getBigDecimal("salary");
            if (monthlySalary != null) {
                // Assuming 160 working hours per month (8 hours * 20 days)
                return monthlySalary.divide(new BigDecimal("160"), 2, RoundingMode.HALF_UP);
            }
        }
        return new BigDecimal("15.00"); // Default hourly rate
    }

    /**
     * Calculate bonuses.
     * @param delegator Delegator
     * @param partyId Party ID
     * @param fromDate From date
     * @param thruDate Thru date
     * @return Bonuses total
     * @throws GenericEntityException if entity error
     */
    private static BigDecimal calculateBonuses(Delegator delegator, String partyId,
            Date fromDate, Date thruDate) throws GenericEntityException {

        BigDecimal totalBonuses = BigDecimal.ZERO;

        List<EntityCondition> conditions = new ArrayList<>();
        conditions.add(EntityCondition.makeCondition("partyId", partyId));
        conditions.add(EntityCondition.makeCondition("statusId", "APPROVED"));
        conditions.add(EntityCondition.makeCondition("bonusDate",
                EntityOperator.GREATER_THAN_EQUAL_TO, fromDate));
        conditions.add(EntityCondition.makeCondition("bonusDate",
                EntityOperator.LESS_THAN_EQUAL_TO, thruDate));

        EntityCondition whereCond = EntityCondition.makeCondition(conditions, EntityOperator.AND);

        List<GenericValue> bonuses = EntityQuery.use(delegator)
                .from("EmployeeBonus")
                .where(whereCond)
                .queryList();

        for (GenericValue bonus : bonuses) {
            BigDecimal amount = bonus.getBigDecimal("amount");
            if (amount != null) {
                totalBonuses = totalBonuses.add(amount);
            }
        }

        return totalBonuses.setScale(2, RoundingMode.HALF_UP);
    }

    /**
     * Calculate commissions.
     * @param delegator Delegator
     * @param partyId Party ID
     * @param fromDate From date
     * @param thruDate Thru date
     * @return Commissions total
     * @throws GenericEntityException if entity error
     */
    private static BigDecimal calculateCommissions(Delegator delegator, String partyId,
            Date fromDate, Date thruDate) throws GenericEntityException {

        BigDecimal totalCommissions = BigDecimal.ZERO;

        List<EntityCondition> conditions = new ArrayList<>();
        conditions.add(EntityCondition.makeCondition("partyId", partyId));
        conditions.add(EntityCondition.makeCondition("statusId", "EARNED"));
        conditions.add(EntityCondition.makeCondition("commissionDate",
                EntityOperator.GREATER_THAN_EQUAL_TO, fromDate));
        conditions.add(EntityCondition.makeCondition("commissionDate",
                EntityOperator.LESS_THAN_EQUAL_TO, thruDate));

        EntityCondition whereCond = EntityCondition.makeCondition(conditions, EntityOperator.AND);

        List<GenericValue> commissions = EntityQuery.use(delegator)
                .from("SalesCommission")
                .where(whereCond)
                .queryList();

        for (GenericValue commission : commissions) {
            BigDecimal amount = commission.getBigDecimal("amount");
            if (amount != null) {
                totalCommissions = totalCommissions.add(amount);
            }
        }

        return totalCommissions.setScale(2, RoundingMode.HALF_UP);
    }

    /**
     * Calculate other deductions.
     * @param delegator Delegator
     * @param partyId Party ID
     * @param fromDate From date
     * @param thruDate Thru date
     * @return Other deductions total
     * @throws GenericEntityException if entity error
     */
    private static BigDecimal calculateOtherDeductions(Delegator delegator, String partyId,
            Date fromDate, Date thruDate) throws GenericEntityException {

        BigDecimal totalOtherDeductions = BigDecimal.ZERO;

        // Build date range conditions
        List<EntityCondition> dateConditions = new ArrayList<>();
        dateConditions.add(EntityCondition.makeCondition("effectiveDate",
                EntityOperator.LESS_THAN_EQUAL_TO, thruDate));
        dateConditions.add(EntityCondition.makeCondition(
                EntityCondition.makeCondition("thruDate", EntityOperator.EQUALS, null),
                EntityOperator.OR,
                EntityCondition.makeCondition("thruDate",
                        EntityOperator.GREATER_THAN_EQUAL_TO, fromDate)));

        List<EntityCondition> allConditions = new ArrayList<>();
        allConditions.add(EntityCondition.makeCondition("partyId", partyId));
        allConditions.add(EntityCondition.makeCondition("statusId", "ACTIVE"));
        allConditions.add(EntityCondition.makeCondition(dateConditions, EntityOperator.AND));

        EntityCondition whereCondition = EntityCondition.makeCondition(allConditions, EntityOperator.AND);

        List<GenericValue> otherDeds = EntityQuery.use(delegator)
                .from("EmployeeDeduction")
                .where(whereCondition)
                .queryList();

        for (GenericValue deduction : otherDeds) {
            BigDecimal amount = deduction.getBigDecimal("amount");
            if (amount != null) {
                totalOtherDeductions = totalOtherDeductions.add(amount);
            }
        }

        return totalOtherDeductions.setScale(2, RoundingMode.HALF_UP);
    }

    /**
     * Calculate advance deductions.
     * @param delegator Delegator
     * @param partyId Party ID
     * @return Advance deductions total
     * @throws GenericEntityException if entity error
     */
    private static BigDecimal calculateAdvanceDeductions(Delegator delegator, String partyId)
            throws GenericEntityException {

        BigDecimal totalAdvanceDeductions = BigDecimal.ZERO;

        List<GenericValue> advances = EntityQuery.use(delegator)
                .from("SalaryAdvance")
                .where("partyId", partyId, "statusId", "APPROVED", "repaymentStatus", "PENDING")
                .queryList();

        for (GenericValue advance : advances) {
            BigDecimal monthlyDeduction = advance.getBigDecimal("monthlyDeduction");
            if (monthlyDeduction != null) {
                totalAdvanceDeductions = totalAdvanceDeductions.add(monthlyDeduction);
            }
        }

        return totalAdvanceDeductions.setScale(2, RoundingMode.HALF_UP);
    }

    /**
     * Log payroll error.
     * @param delegator Delegator
     * @param payrollRunId Payroll run ID
     * @param partyId Party ID
     * @param errorMessage Error message
     * @param userLogin User login
     * @throws GenericEntityException if entity error
     */
    private static void logPayrollError(Delegator delegator, String payrollRunId,
            String partyId, String errorMessage, GenericValue userLogin)
            throws GenericEntityException {

        String errorId = delegator.getNextSeqId("PayrollErrorLog");

        GenericValue errorLog = delegator.makeValue("PayrollErrorLog");
        errorLog.set("errorLogId", errorId);
        errorLog.set("payrollRunId", payrollRunId);
        errorLog.set("partyId", partyId);
        errorLog.set("errorMessage", errorMessage);
        errorLog.set("loggedByUserLogin", userLogin.getString("userLoginId"));
        errorLog.set("logDate", new Timestamp(System.currentTimeMillis()));

        delegator.create(errorLog);
    }

    /**
     * Generate payroll summary.
     * @param delegator Delegator
     * @param payrollRunId Payroll run ID
     * @param employeeResults Employee results
     * @throws GenericEntityException if entity error
     */
    private static void generatePayrollSummary(Delegator delegator, String payrollRunId,
            List<Map<String, Object>> employeeResults) throws GenericEntityException {

        String summaryId = delegator.getNextSeqId("PayrollSummary");

        StringBuilder summaryBuilder = new StringBuilder();
        summaryBuilder.append("Payroll Summary for Run: ").append(payrollRunId).append("\n");
        summaryBuilder.append("Generated on: ").append(new Timestamp(System.currentTimeMillis())).append("\n");
        summaryBuilder.append("================================================================================\n");

        if (employeeResults != null) {
            for (Map<String, Object> empResult : employeeResults) {
                String name = (empResult.get("employeeName") != null)
                        ? empResult.get("employeeName").toString()
                        : "N/A";

                Object grossObj = empResult.get("grossEarnings");
                Object netObj = empResult.get("netPay");

                double gross = (grossObj instanceof BigDecimal)
                        ? ((BigDecimal) grossObj).doubleValue()
                        : (grossObj != null ? Double.parseDouble(grossObj.toString()) : 0.0);

                double net = (netObj instanceof BigDecimal)
                        ? ((BigDecimal) netObj).doubleValue()
                        : (netObj != null ? Double.parseDouble(netObj.toString()) : 0.0);

                summaryBuilder.append(String.format("%-30s: %12.2f (Gross) - %12.2f (Net)\n",
                        name, gross, net));
            }
        }

        GenericValue summary = delegator.makeValue("PayrollSummary");
        summary.set("payrollSummaryId", summaryId);
        summary.set("payrollRunId", payrollRunId);
        summary.set("summaryText", summaryBuilder.toString());
        summary.set("generatedDate", new Timestamp(System.currentTimeMillis()));

        delegator.create(summary);
    }

    /**
     * Additional Services.
     */

    /**
     * Approve payroll run service.
     * @param dctx DispatchContext
     * @param context Service context
     * @return Result map
     */
    public static Map<String, Object> approvePayrollRun(DispatchContext dctx, Map<String, ?> context) {
        Delegator delegator = dctx.getDelegator();
        LocalDispatcher dispatcher = dctx.getDispatcher();
        Map<String, Object> result = ServiceUtil.returnSuccess();

        try {
            String payrollRunId = (String) context.get("payrollRunId");
            GenericValue userLogin = (GenericValue) context.get("userLogin");

            if (UtilValidate.isEmpty(payrollRunId)) {
                return ServiceUtil.returnError("Payroll Run ID is required");
            }

            GenericValue payrollRun = EntityQuery.use(delegator)
                    .from("PayrollRun")
                    .where("payrollRunId", payrollRunId)
                    .queryOne();

            if (payrollRun == null) {
                return ServiceUtil.returnError("Payroll run not found: " + payrollRunId);
            }

            // Check if already approved
            if (STATUS_APPROVED.equals(payrollRun.getString("statusId"))) {
                return ServiceUtil.returnError("Payroll run already approved");
            }

            // Update status to approved
            payrollRun.set("statusId", STATUS_APPROVED);
            payrollRun.set("approvedByUserLogin", userLogin.getString("userLoginId"));
            payrollRun.set("approvedDate", new Timestamp(System.currentTimeMillis()));
            delegator.store(payrollRun);

            // Generate payslips
            dispatcher.runAsync("generatePayslips", UtilMisc.toMap(
                    "payrollRunId", payrollRunId,
                    "userLogin", userLogin));

            // Generate bank payment file
            dispatcher.runAsync("generateBankPaymentFile", UtilMisc.toMap(
                    "payrollRunId", payrollRunId,
                    "userLogin", userLogin));

            // Post accounting transactions
            dispatcher.runAsync("postPayrollAccounting", UtilMisc.toMap(
                    "payrollRunId", payrollRunId,
                    "userLogin", userLogin));

            result.put("payrollRunId", payrollRunId);
            result.put("message", "Payroll run approved successfully");

        } catch (Exception e) {
            Debug.logError(e, "Error approving payroll run", MODULE);
            return ServiceUtil.returnError("Failed to approve payroll: " + e.getMessage());
        }

        return result;
    }

    /**
     * Generate payslips service.
     * @param dctx DispatchContext
     * @param context Service context
     * @return Result map
     */
    @SuppressWarnings("unchecked")
    public static Map<String, Object> generatePayslips(DispatchContext dctx, Map<String, ?> context) {
        Delegator delegator = dctx.getDelegator();
        Map<String, Object> result = ServiceUtil.returnSuccess();

        try {
            String payrollRunId = (String) context.get("payrollRunId");
            List<GenericValue> payrollEntries = EntityQuery.use(delegator)
                    .from("PayrollEntry")
                    .where("payrollRunId", payrollRunId)
                    .queryList();
            List<Map<String, Object>> payslips = new ArrayList<>();
            for (GenericValue entry : payrollEntries) {
                Map<String, Object> payslip = generatePayslip(delegator, entry);
                payslips.add(payslip);
                // Update entry with payslip info
                entry.set("payslipGenerated", "Y");
                entry.set("payslipDate", new Timestamp(System.currentTimeMillis()));
                delegator.store(entry);
            }

            result.put("payrollRunId", payrollRunId);
            result.put("payslipsGenerated", payslips.size());
            result.put("payslips", payslips);

        } catch (Exception e) {
            Debug.logError(e, "Error generating payslips", MODULE);
            return ServiceUtil.returnError("Failed to generate payslips: " + e.getMessage());
        }

        return result;
    }

    /**
     * Generate individual payslip.
     * @param delegator Delegator
     * @param payrollEntry Payroll entry
     * @return Payslip map
     * @throws GenericEntityException if entity error
     */
    private static Map<String, Object> generatePayslip(Delegator delegator, GenericValue payrollEntry)
            throws GenericEntityException {

        Map<String, Object> payslip = new HashMap<>();

        String partyId = payrollEntry.getString("partyId");
        GenericValue person = EntityQuery.use(delegator)
                .from("Person")
                .where("partyId", partyId)
                .queryOne();

        GenericValue employment = EntityQuery.use(delegator)
                .from("Employment")
                .where("partyIdTo", partyId)
                .filterByDate()
                .queryFirst();

        payslip.put("employeeId", payrollEntry.getString("employeeId"));
        payslip.put("employeeName", person != null
                ? person.getString("firstName") + " " + person.getString("lastName") : "");
        payslip.put("position", employment != null ? employment.getString("positionTitle") : "");
        payslip.put("department", employment != null ? employment.getString("departmentId") : "");
        payslip.put("payPeriod", payrollEntry.get("payrollRunId"));
        payslip.put("paymentDate", new Timestamp(System.currentTimeMillis()));
        payslip.put("baseSalary", payrollEntry.getBigDecimal("baseSalary"));
        payslip.put("overtimePay", payrollEntry.getBigDecimal("overtimePay"));
        payslip.put("allowances", payrollEntry.getBigDecimal("allowances"));
        payslip.put("bonuses", payrollEntry.getBigDecimal("bonuses"));
        payslip.put("commissions", payrollEntry.getBigDecimal("commissions"));
        payslip.put("grossEarnings", payrollEntry.getBigDecimal("grossEarnings"));
        payslip.put("incomeTax", payrollEntry.getBigDecimal("incomeTax"));
        payslip.put("socialSecurity", payrollEntry.getBigDecimal("socialSecurity"));
        payslip.put("pension", payrollEntry.getBigDecimal("pension"));
        payslip.put("loanDeductions", payrollEntry.getBigDecimal("loanDeductions"));
        payslip.put("otherDeductions", payrollEntry.getBigDecimal("otherDeductions"));
        payslip.put("advanceDeductions", payrollEntry.getBigDecimal("advanceDeductions"));
        payslip.put("totalDeductions", payrollEntry.getBigDecimal("totalDeductions"));
        payslip.put("netPay", payrollEntry.getBigDecimal("netPay"));
        payslip.put("payslipReference", payrollEntry.getString("payslipReference"));

        // Generate QR code for payslip
        String qrCodeData = "PAYSLIP:" + payrollEntry.getString("payslipReference")
                + ":AMOUNT:" + payrollEntry.getBigDecimal("netPay")
                + ":DATE:" + new Timestamp(System.currentTimeMillis());
        payslip.put("qrCodeData", qrCodeData);

        return payslip;
    }

    /**
     * Get payroll report service.
     * @param dctx DispatchContext
     * @param context Service context
     * @return Result map
     */
    @SuppressWarnings("unchecked")
    public static Map<String, Object> getPayrollReport(DispatchContext dctx, Map<String, ?> context) {
        Delegator delegator = dctx.getDelegator();
        Map<String, Object> result = ServiceUtil.returnSuccess();

        try {
            String payrollRunId = (String) context.get("payrollRunId");
            String reportType = (String) context.get("reportType");

            GenericValue payrollRun = EntityQuery.use(delegator)
                    .from("PayrollRun")
                    .where("payrollRunId", payrollRunId)
                    .queryOne();

            List<GenericValue> payrollEntries = EntityQuery.use(delegator)
                    .from("PayrollEntry")
                    .where("payrollRunId", payrollRunId)
                    .orderBy("employeeId")
                    .queryList();

            Map<String, Object> report = new HashMap<>();

            if ("DETAILED".equals(reportType)) {
                report = generateDetailedReport(payrollRun, payrollEntries);
            } else if ("SUMMARY".equals(reportType)) {
                report = generateSummaryReport(payrollRun, payrollEntries);
            } else if ("TAX".equals(reportType)) {
                report = generateTaxReport(payrollRun, payrollEntries);
            } else {
                report = generateDefaultReport(payrollRun, payrollEntries);
            }

            result.put("payrollRunId", payrollRunId);
            result.put("reportType", reportType);
            result.put("report", report);
            result.put("generatedDate", new Timestamp(System.currentTimeMillis()));

        } catch (Exception e) {
            Debug.logError(e, "Error generating payroll report", MODULE);
            return ServiceUtil.returnError("Failed to generate report: " + e.getMessage());
        }

        return result;
    }

    /**
     * Generate detailed report.
     * @param payrollRun Payroll run
     * @param payrollEntries Payroll entries
     * @return Detailed report
     * @throws GenericEntityException if entity error
     */
    private static Map<String, Object> generateDetailedReport(GenericValue payrollRun,
            List<GenericValue> payrollEntries) throws GenericEntityException {

        Map<String, Object> report = new HashMap<>();
        List<Map<String, Object>> entries = new ArrayList<>();

        for (GenericValue entry : payrollEntries) {
            Map<String, Object> detail = new HashMap<>();
            detail.put("employeeId", entry.getString("employeeId"));
            detail.put("baseSalary", entry.getBigDecimal("baseSalary"));
            detail.put("overtimePay", entry.getBigDecimal("overtimePay"));
            detail.put("allowances", entry.getBigDecimal("allowances"));
            detail.put("bonuses", entry.getBigDecimal("bonuses"));
            detail.put("commissions", entry.getBigDecimal("commissions"));
            detail.put("grossEarnings", entry.getBigDecimal("grossEarnings"));
            detail.put("incomeTax", entry.getBigDecimal("incomeTax"));
            detail.put("socialSecurity", entry.getBigDecimal("socialSecurity"));
            detail.put("pension", entry.getBigDecimal("pension"));
            detail.put("loanDeductions", entry.getBigDecimal("loanDeductions"));
            detail.put("otherDeductions", entry.getBigDecimal("otherDeductions"));
            detail.put("advanceDeductions", entry.getBigDecimal("advanceDeductions"));
            detail.put("totalDeductions", entry.getBigDecimal("totalDeductions"));
            detail.put("netPay", entry.getBigDecimal("netPay"));
            entries.add(detail);
        }

        report.put("reportType", "DETAILED");
        report.put("payrollPeriod", payrollRun.get("fromDate") + " to " + payrollRun.get("thruDate"));
        report.put("totalEmployees", payrollRun.get("employeeCount"));
        report.put("totalGross", payrollRun.get("totalGross"));
        report.put("totalNet", payrollRun.get("totalNet"));
        report.put("totalDeductions", payrollRun.get("totalDeductions"));
        report.put("entries", entries);

        return report;
    }

    /**
     * Generate summary report.
     * @param payrollRun Payroll run
     * @param payrollEntries Payroll entries
     * @return Summary report
     */
    private static Map<String, Object> generateSummaryReport(GenericValue payrollRun,
            List<GenericValue> payrollEntries) {

        Map<String, Object> report = new HashMap<>();

        report.put("reportType", "SUMMARY");
        report.put("payrollRunId", payrollRun.get("payrollRunId"));
        report.put("payrollPeriod", payrollRun.get("fromDate") + " to " + payrollRun.get("thruDate"));
        report.put("runDate", payrollRun.get("runDate"));
        report.put("status", payrollRun.get("statusId"));
        report.put("totalEmployees", payrollRun.get("employeeCount"));
        report.put("totalGross", payrollRun.get("totalGross"));
        report.put("totalNet", payrollRun.get("totalNet"));
        report.put("totalDeductions", payrollRun.get("totalDeductions"));
        report.put("errorCount", payrollRun.get("errorCount"));

        return report;
    }

    /**
     * Generate tax report.
     * @param payrollRun Payroll run
     * @param payrollEntries Payroll entries
     * @return Tax report
     */
    private static Map<String, Object> generateTaxReport(GenericValue payrollRun,
            List<GenericValue> payrollEntries) {

        Map<String, Object> report = new HashMap<>();
        BigDecimal totalTax = BigDecimal.ZERO;
        BigDecimal totalSocialSecurity = BigDecimal.ZERO;
        BigDecimal totalPension = BigDecimal.ZERO;

        for (GenericValue entry : payrollEntries) {
            totalTax = totalTax.add(entry.getBigDecimal("incomeTax"));
            totalSocialSecurity = totalSocialSecurity.add(entry.getBigDecimal("socialSecurity"));
            totalPension = totalPension.add(entry.getBigDecimal("pension"));
        }

        report.put("reportType", "TAX");
        report.put("payrollPeriod", payrollRun.get("fromDate") + " to " + payrollRun.get("thruDate"));
        report.put("totalEmployees", payrollRun.get("employeeCount"));
        report.put("totalTaxWithheld", totalTax);
        report.put("totalSocialSecurity", totalSocialSecurity);
        report.put("totalPension", totalPension);
        report.put("totalTaxPayable", totalTax.add(totalSocialSecurity).add(totalPension));

        return report;
    }

    /**
     * Generate default report.
     * @param payrollRun Payroll run
     * @param payrollEntries Payroll entries
     * @return Default report
     */
    private static Map<String, Object> generateDefaultReport(GenericValue payrollRun,
            List<GenericValue> payrollEntries) {
        return generateSummaryReport(payrollRun, payrollEntries);
    }

    /**
     * Reverse payroll run service.
     * @param dctx DispatchContext
     * @param context Service context
     * @return Result map
     */
    public static Map<String, Object> reversePayrollRun(DispatchContext dctx, Map<String, ?> context) {
        Delegator delegator = dctx.getDelegator();
        LocalDispatcher dispatcher = dctx.getDispatcher();
        Map<String, Object> result = ServiceUtil.returnSuccess();

        try {
            String payrollRunId = (String) context.get("payrollRunId");
            String reason = (String) context.get("reason");
            GenericValue userLogin = (GenericValue) context.get("userLogin");

            if (UtilValidate.isEmpty(payrollRunId)) {
                return ServiceUtil.returnError("Payroll Run ID is required");
            }

            GenericValue payrollRun = EntityQuery.use(delegator)
                    .from("PayrollRun")
                    .where("payrollRunId", payrollRunId)
                    .queryOne();

            if (payrollRun == null) {
                return ServiceUtil.returnError("Payroll run not found: " + payrollRunId);
            }

            // Check if can be reversed
            String status = payrollRun.getString("statusId");
            if (STATUS_PAID.equals(status)) {
                return ServiceUtil.returnError("Cannot reverse paid payroll run");
            }

            // Update status to cancelled
            payrollRun.set("statusId", STATUS_CANCELLED);
            payrollRun.set("cancelledByUserLogin", userLogin.getString("userLoginId"));
            payrollRun.set("cancelledDate", new Timestamp(System.currentTimeMillis()));
            payrollRun.set("cancellationReason", reason);
            delegator.store(payrollRun);

            // Reverse accounting entries
            dispatcher.runAsync("reversePayrollAccounting", UtilMisc.toMap(
                    "payrollRunId", payrollRunId,
                    "reason", reason,
                    "userLogin", userLogin));

            result.put("payrollRunId", payrollRunId);
            result.put("message", "Payroll run reversed successfully");

        } catch (Exception e) {
            Debug.logError(e, "Error reversing payroll run", MODULE);
            return ServiceUtil.returnError("Failed to reverse payroll: " + e.getMessage());
        }

        return result;
    }

    /**
     * Calculate employee year-to-date service.
     * @param dctx DispatchContext
     * @param context Service context
     * @return Result map
     */
    public static Map<String, Object> calculateEmployeeYearToDate(DispatchContext dctx, Map<String, ?> context) {
        Delegator delegator = dctx.getDelegator();
        Map<String, Object> result = ServiceUtil.returnSuccess();

        try {
            String partyId = (String) context.get("partyId");
            Integer year = (Integer) context.get("year");

            if (UtilValidate.isEmpty(partyId)) {
                return ServiceUtil.returnError("Employee ID is required");
            }

            if (year == null) {
                Calendar cal = Calendar.getInstance();
                year = cal.get(Calendar.YEAR);
            }

            Calendar cal = Calendar.getInstance();
            cal.set(year, Calendar.JANUARY, 1, 0, 0, 0);
            Date yearStart = cal.getTime();
            cal.set(year, Calendar.DECEMBER, 31, 23, 59, 59);
            Date yearEnd = cal.getTime();

            List<EntityCondition> conditions = new ArrayList<>();
            conditions.add(EntityCondition.makeCondition("partyId", partyId));
            conditions.add(EntityCondition.makeCondition("statusId", STATUS_PAID));
            conditions.add(EntityCondition.makeCondition("runDate",
                    EntityOperator.GREATER_THAN_EQUAL_TO, yearStart));
            conditions.add(EntityCondition.makeCondition("runDate",
                    EntityOperator.LESS_THAN_EQUAL_TO, yearEnd));

            EntityCondition whereCond = EntityCondition.makeCondition(conditions, EntityOperator.AND);

            List<GenericValue> payrollRuns = EntityQuery.use(delegator)
                    .from("PayrollRun")
                    .where(whereCond)
                    .queryList();

            BigDecimal ytdGross = BigDecimal.ZERO;
            BigDecimal ytdTax = BigDecimal.ZERO;
            BigDecimal ytdNet = BigDecimal.ZERO;

            for (GenericValue payrollRun : payrollRuns) {
                List<GenericValue> entries = EntityQuery.use(delegator)
                        .from("PayrollEntry")
                        .where("payrollRunId", payrollRun.getString("payrollRunId"),
                                "partyId", partyId)
                        .queryList();

                for (GenericValue entry : entries) {
                    ytdGross = ytdGross.add(entry.getBigDecimal("grossEarnings"));
                    ytdTax = ytdTax.add(entry.getBigDecimal("incomeTax"));
                    ytdNet = ytdNet.add(entry.getBigDecimal("netPay"));
                }
            }

            result.put("partyId", partyId);
            result.put("year", year);
            result.put("ytdGross", ytdGross.setScale(2, RoundingMode.HALF_UP));
            result.put("ytdTax", ytdTax.setScale(2, RoundingMode.HALF_UP));
            result.put("ytdNet", ytdNet.setScale(2, RoundingMode.HALF_UP));
            result.put("payrollRunsCount", payrollRuns.size());

        } catch (Exception e) {
            Debug.logError(e, "Error calculating YTD for employee", MODULE);
            return ServiceUtil.returnError("Failed to calculate YTD: " + e.getMessage());
        }

        return result;
    }

    /**
     * Get payroll statistics service.
     * @param dctx DispatchContext
     * @param context Service context
     * @return Result map
     */
    public static Map<String, Object> getPayrollStatistics(DispatchContext dctx, Map<String, ?> context) {
        Delegator delegator = dctx.getDelegator();
        Map<String, Object> result = ServiceUtil.returnSuccess();

        try {
            // Total active employees
            long totalEmployees = EntityQuery.use(delegator)
                    .from("Employment")
                    .where("employmentStatusId", "EMPL_ACTIVE")
                    .filterByDate()
                    .queryCount();

            // Current month payroll runs
            Calendar cal = Calendar.getInstance();
            cal.set(Calendar.DAY_OF_MONTH, 1);
            cal.set(Calendar.HOUR_OF_DAY, 0);
            cal.set(Calendar.MINUTE, 0);
            cal.set(Calendar.SECOND, 0);
            Date monthStart = cal.getTime();

            cal.add(Calendar.MONTH, 1);
            cal.add(Calendar.DAY_OF_MONTH, -1);
            Date monthEnd = cal.getTime();

            long thisMonthPayrolls = EntityQuery.use(delegator)
                    .from("PayrollRun")
                    .where(EntityCondition.makeCondition("runDate",
                            EntityOperator.GREATER_THAN_EQUAL_TO, monthStart))
                    .queryCount();

            // Pending payroll runs
            long pendingPayrolls = EntityQuery.use(delegator)
                    .from("PayrollRun")
                    .where(EntityCondition.makeCondition("statusId",
                            EntityOperator.NOT_EQUAL, STATUS_PAID))
                    .queryCount();

            // Total payroll amount this year
            cal = Calendar.getInstance();
            cal.set(Calendar.MONTH, Calendar.JANUARY);
            cal.set(Calendar.DAY_OF_MONTH, 1);
            cal.set(Calendar.HOUR_OF_DAY, 0);
            cal.set(Calendar.MINUTE, 0);
            cal.set(Calendar.SECOND, 0);
            Date yearStart = cal.getTime();

            List<GenericValue> yearPayrolls = EntityQuery.use(delegator)
                    .from("PayrollRun")
                    .where(EntityCondition.makeCondition("runDate",
                            EntityOperator.GREATER_THAN_EQUAL_TO, yearStart))
                    .queryList();

            BigDecimal yearTotal = BigDecimal.ZERO;
            for (GenericValue run : yearPayrolls) {
                BigDecimal totalNet = run.getBigDecimal("totalNet");
                if (totalNet != null) {
                    yearTotal = yearTotal.add(totalNet);
                }
            }

            result.put("totalEmployees", totalEmployees);
            result.put("thisMonthPayrolls", thisMonthPayrolls);
            result.put("pendingPayrolls", pendingPayrolls);
            result.put("yearTotal", yearTotal.setScale(2, RoundingMode.HALF_UP));

        } catch (Exception e) {
            Debug.logError(e, "Error getting payroll statistics", MODULE);
            result.put("error", "Failed to get statistics: " + e.getMessage());
        }

        return result;
    }
	
	
 public static Map<String, Object> createEmployeePayrollAssignmentxzyold(DispatchContext dctx, Map<String, ? extends Object> context) {
        Map<String, Object> result = ServiceUtil.returnSuccess();
        
        try {
            // Get parameters from form
            String partyId = (String) context.get("partyId");
            String payrollStructureId = (String) context.get("payrollStructureId");
            String employmentType = (String) context.get("employmentType");
            String contractType = (String) context.get("contractType");
            BigDecimal baseSalary = (BigDecimal) context.get("baseSalary");
            Timestamp effectiveDate = (Timestamp) context.get("effectiveDate");
            Timestamp thruDate = (Timestamp) context.get("thruDate");
            String statusId = (String) context.get("statusId");
            String comments = (String) context.get("comments");
            
            Debug.logInfo("Creating EmployeePayrollAssignment for partyId: " + partyId, MODULE);
            
            // Validate required fields
            if (UtilValidate.isEmpty(partyId)) {
                return ServiceUtil.returnError("Employee ID is required");
            }
            if (UtilValidate.isEmpty(payrollStructureId)) {
                return ServiceUtil.returnError("Payroll Structure is required");
            }
            if (effectiveDate == null) {
                return ServiceUtil.returnError("Effective Date is required");
            }
            
            // Check if employee exists
            GenericValue person = dctx.getDelegator().findOne("Person", 
                UtilMisc.toMap("partyId", partyId), false);
            if (person == null) {
                return ServiceUtil.returnError("Employee with ID " + partyId + " does not exist");
            }
            
            // Check if payroll structure exists
            GenericValue payrollStructure = dctx.getDelegator().findOne("PayrollStructure", 
                UtilMisc.toMap("payrollStructureId", payrollStructureId), false);
            if (payrollStructure == null) {
                return ServiceUtil.returnError("Payroll Structure with ID " + payrollStructureId + " does not exist");
            }
            
            // Generate assignment ID
            String assignmentId = dctx.getDelegator().getNextSeqId("EmployeePayrollAssignment");
            
            // Create entity instance
            GenericValue assignment = dctx.getDelegator().makeValue("EmployeePayrollAssignment");
            assignment.set("assignmentId", assignmentId);
            assignment.set("partyId", partyId);
            assignment.set("payrollStructureId", payrollStructureId);
            
            if (UtilValidate.isNotEmpty(employmentType)) {
                assignment.set("employmentType", employmentType);
            }
            
            if (UtilValidate.isNotEmpty(contractType)) {
                assignment.set("contractType", contractType);
            }
            
            if (baseSalary != null) {
                assignment.set("baseSalary", baseSalary);
            }
            
            assignment.set("effectiveDate", effectiveDate);
            
            if (thruDate != null) {
                assignment.set("thruDate", thruDate);
            }
            
            if (UtilValidate.isNotEmpty(statusId)) {
                assignment.set("statusId", statusId);
            } else {
                assignment.set("statusId", "ACTIVE"); // Default status
            }
            
            if (UtilValidate.isNotEmpty(comments)) {
                assignment.set("comments", comments);
            }
            
            // Set audit fields
            Timestamp now = new Timestamp(new Date().getTime());
            assignment.set("createdDate", now);
            assignment.set("lastModifiedDate", now);
            
            GenericValue userLogin = (GenericValue) context.get("userLogin");
            if (userLogin != null) {
                String userLoginId = userLogin.getString("userLoginId");
                assignment.set("createdByUserLogin", userLoginId);
                assignment.set("lastModifiedByUserLogin", userLoginId);
            }
            
            // Save to database
            dctx.getDelegator().create(assignment);
            
            Debug.logInfo("Successfully created EmployeePayrollAssignment: " + assignmentId, MODULE);
            
            // Return success
            result.put("assignmentId", assignmentId);
            result.put("responseMessage", "Employee payroll assignment created successfully");
            
            return result;
            
        } catch (GenericEntityException e) {
            Debug.logError(e, "Database error in createEmployeePayrollAssignment", MODULE);
            return ServiceUtil.returnError("Database error: " + e.getMessage());
        } catch (Exception e) {
            Debug.logError(e, "Unexpected error in createEmployeePayrollAssignment", MODULE);
            return ServiceUtil.returnError("Unexpected error: " + e.getMessage());
        }
    }
    
    // Optional: Update service
    public static Map<String, Object> updateEmployeePayrollAssignment(DispatchContext dctx, Map<String, ? extends Object> context) {
        try {
            String assignmentId = (String) context.get("assignmentId");
            
            if (UtilValidate.isEmpty(assignmentId)) {
                return ServiceUtil.returnError("Assignment ID is required for update");
            }
            
            GenericValue assignment = dctx.getDelegator().findOne("EmployeePayrollAssignment", 
                UtilMisc.toMap("assignmentId", assignmentId), false);
            
            if (assignment == null) {
                return ServiceUtil.returnError("Assignment not found with ID: " + assignmentId);
            }
            
            // Update fields
            if (context.get("baseSalary") != null) {
                assignment.set("baseSalary", context.get("baseSalary"));
            }
            
            if (context.get("thruDate") != null) {
                assignment.set("thruDate", (Timestamp) context.get("thruDate"));
            }
            
            if (context.get("statusId") != null) {
                assignment.set("statusId", (String) context.get("statusId"));
            }
            
            if (context.get("comments") != null) {
                assignment.set("comments", (String) context.get("comments"));
            }
            
            // Update audit fields
            assignment.set("lastModifiedDate", new Timestamp(new Date().getTime()));
            
            GenericValue userLogin = (GenericValue) context.get("userLogin");
            if (userLogin != null) {
                assignment.set("lastModifiedByUserLogin", userLogin.getString("userLoginId"));
            }
            
            dctx.getDelegator().store(assignment);
            
            return ServiceUtil.returnSuccess("Assignment updated successfully");
            
        } catch (Exception e) {
            Debug.logError(e, "Error updating assignment", MODULE);
            return ServiceUtil.returnError("Error: " + e.getMessage());
        }
    }
	
 
    private static BigDecimal toBigDecimal(Object obj) {
        if (obj == null) return null;
        if (obj instanceof BigDecimal) return (BigDecimal) obj;
        if (obj instanceof Double) return BigDecimal.valueOf((Double) obj);
        if (obj instanceof Float) return BigDecimal.valueOf((Float) obj);
        if (obj instanceof String) return new BigDecimal((String) obj);
        throw new IllegalArgumentException("Cannot convert to BigDecimal: " + obj);
    }


public static Map<String, Object> createEmployeePayrollAssignmentsuccesswithoutmessage(
        DispatchContext dctx, Map<String, ? extends Object> context) {

    Map<String, Object> result = ServiceUtil.returnSuccess();
    final String MODULE = "PayrollEngine"; // for Debug logging

    try {
        // Get parameters from form
        String partyId = (String) context.get("partyId");
        String payrollStructureId = (String) context.get("payrollStructureId");
        String employmentType = (String) context.get("employmentType");
        String contractType = (String) context.get("contractType");
        BigDecimal baseSalary = toBigDecimal(context.get("baseSalary"));
        Timestamp effectiveDate = (Timestamp) context.get("effectiveDate");
        Timestamp thruDate = (Timestamp) context.get("thruDate");
        String statusId = (String) context.get("statusId");
        String comments = (String) context.get("comments");

        Debug.logInfo("Creating EmployeePayrollAssignment for partyId: " + partyId, MODULE);

        // Validate required fields
        if (UtilValidate.isEmpty(partyId)) {
            return ServiceUtil.returnError("Employee ID is required");
        }
        if (UtilValidate.isEmpty(payrollStructureId)) {
            return ServiceUtil.returnError("Payroll Structure is required");
        }
        if (effectiveDate == null) {
            return ServiceUtil.returnError("Effective Date is required");
        }

        Delegator delegator = dctx.getDelegator();

        // Check if employee exists
        GenericValue person = delegator.findOne("Person", UtilMisc.toMap("partyId", partyId), false);
        if (person == null) {
            return ServiceUtil.returnError("Employee with ID " + partyId + " does not exist");
        }

        // Check if payroll structure exists
        GenericValue payrollStructure = delegator.findOne("PayrollStructure",
                UtilMisc.toMap("payrollStructureId", payrollStructureId), false);
        if (payrollStructure == null) {
            return ServiceUtil.returnError("Payroll Structure with ID " + payrollStructureId + " does not exist");
        }

        // Generate assignment ID
        String assignmentId = delegator.getNextSeqId("EmployeePayrollAssignment");

        // Create entity instance
        GenericValue assignment = delegator.makeValue("EmployeePayrollAssignment");
        assignment.set("assignmentId", assignmentId);
        assignment.set("partyId", partyId);
        assignment.set("payrollStructureId", payrollStructureId);

        if (UtilValidate.isNotEmpty(employmentType)) {
            assignment.set("employmentType", employmentType);
        }
        if (UtilValidate.isNotEmpty(contractType)) {
            assignment.set("contractType", contractType);
        }
        if (baseSalary != null) {
            assignment.set("baseSalary", baseSalary);
        }
        assignment.set("effectiveDate", effectiveDate);

        // Default thruDate to end of year if not provided
        if (thruDate == null) {
            Calendar cal = Calendar.getInstance();
            cal.setTime(effectiveDate);
            cal.set(Calendar.MONTH, Calendar.DECEMBER);
            cal.set(Calendar.DAY_OF_MONTH, 31);
            cal.set(Calendar.HOUR_OF_DAY, 23);
            cal.set(Calendar.MINUTE, 59);
            cal.set(Calendar.SECOND, 59);
            cal.set(Calendar.MILLISECOND, 0);
            thruDate = new Timestamp(cal.getTimeInMillis());
        }
        assignment.set("thruDate", thruDate);

        // Status
        if (UtilValidate.isNotEmpty(statusId)) {
            assignment.set("statusId", statusId);
        } else {
            assignment.set("statusId", "ASSIGN_ACTIVE"); // Default status
        }
        if (UtilValidate.isNotEmpty(comments)) {
            assignment.set("comments", comments);
        }

        // Set audit fields
        Timestamp now = new Timestamp(new Date().getTime());
        assignment.set("createdDate", now);
        assignment.set("lastModifiedDate", now);

        GenericValue userLogin = (GenericValue) context.get("userLogin");
        if (userLogin != null) {
            String userLoginId = userLogin.getString("userLoginId");
            assignment.set("createdByUserLogin", userLoginId);
            assignment.set("lastModifiedByUserLogin", userLoginId);
        }

        // Save to database
        delegator.create(assignment);

        Debug.logInfo("Successfully created EmployeePayrollAssignment: " + assignmentId, MODULE);

        // Return success
        result.put("assignmentId", assignmentId);
        result.put("responseMessage", "Employee payroll assignment created successfully");

        return result;

    } catch (GenericEntityException e) {
        Debug.logError(e, "Database error in createEmployeePayrollAssignment", MODULE);
        return ServiceUtil.returnError("Database error: " + e.getMessage());
    } catch (Exception e) {
        Debug.logError(e, "Unexpected error in createEmployeePayrollAssignment", MODULE);
        return ServiceUtil.returnError("Unexpected error: " + e.getMessage());
    }
}

 
 
 
 
 
    public static Map<String, Object> createEmployeePayrollAssignmentworkingwitoutmessage(
            DispatchContext dctx, Map<String, ? extends Object> context) {

        Map<String, Object> result = ServiceUtil.returnSuccess();
        try {
            // Get parameters from form
            String partyId = (String) context.get("partyId");
            String payrollStructureId = (String) context.get("payrollStructureId");
            String employmentType = (String) context.get("employmentType");
            String contractType = (String) context.get("contractType");
            BigDecimal baseSalary = toBigDecimal(context.get("baseSalary"));
            Timestamp effectiveDate = (Timestamp) context.get("effectiveDate");
            Timestamp thruDate = (Timestamp) context.get("thruDate");
            String statusId = (String) context.get("statusId");
            String comments = (String) context.get("comments");

            Debug.logInfo("Creating EmployeePayrollAssignment for partyId: " + partyId, MODULE);

            // Validate required fields
            if (UtilValidate.isEmpty(partyId)) {
                return ServiceUtil.returnError("Employee ID is required");
            }
            if (UtilValidate.isEmpty(payrollStructureId)) {
                return ServiceUtil.returnError("Payroll Structure is required");
            }
            if (effectiveDate == null) {
                return ServiceUtil.returnError("Effective Date is required");
            }

            Delegator delegator = dctx.getDelegator();

            // Check if employee exists
            GenericValue person = delegator.findOne("Person", UtilMisc.toMap("partyId", partyId), false);
            if (person == null) {
                return ServiceUtil.returnError("Employee with ID " + partyId + " does not exist");
            }

            // Check if payroll structure exists
            GenericValue payrollStructure = delegator.findOne("PayrollStructure",
                    UtilMisc.toMap("payrollStructureId", payrollStructureId), false);
            if (payrollStructure == null) {
                return ServiceUtil.returnError("Payroll Structure with ID " + payrollStructureId + " does not exist");
            }

            // Generate assignment ID
            String assignmentId = delegator.getNextSeqId("EmployeePayrollAssignment");

            // Create entity instance
            GenericValue assignment = delegator.makeValue("EmployeePayrollAssignment");
            assignment.set("assignmentId", assignmentId);
            assignment.set("partyId", partyId);
            assignment.set("payrollStructureId", payrollStructureId);

            if (UtilValidate.isNotEmpty(employmentType)) {
                assignment.set("employmentType", employmentType);
            }
            if (UtilValidate.isNotEmpty(contractType)) {
                assignment.set("contractType", contractType);
            }
            if (baseSalary != null) {
                assignment.set("baseSalary", baseSalary);
            }
            assignment.set("effectiveDate", effectiveDate);

            // Default thruDate to end of year if not provided
            if (thruDate == null) {
                Calendar cal = Calendar.getInstance();
                cal.setTime(effectiveDate);
                cal.set(Calendar.MONTH, Calendar.DECEMBER);
                cal.set(Calendar.DAY_OF_MONTH, 31);
                cal.set(Calendar.HOUR_OF_DAY, 23);
                cal.set(Calendar.MINUTE, 59);
                cal.set(Calendar.SECOND, 59);
                cal.set(Calendar.MILLISECOND, 0);
                thruDate = new Timestamp(cal.getTimeInMillis());
            }
            assignment.set("thruDate", thruDate);

            // Status
            if (UtilValidate.isNotEmpty(statusId)) {
                assignment.set("statusId", statusId);
            } else {
                assignment.set("statusId", "ASSIGN_ACTIVE"); // Default status
            }
            if (UtilValidate.isNotEmpty(comments)) {
                assignment.set("comments", comments);
            }

            // Set audit fields
            Timestamp now = UtilDateTime.nowTimestamp();
            assignment.set("createdDate", now);
            assignment.set("lastModifiedDate", now);

            GenericValue userLogin = (GenericValue) context.get("userLogin");
            if (userLogin != null) {
                String userLoginId = userLogin.getString("userLoginId");
                assignment.set("createdByUserLogin", userLoginId);
                assignment.set("lastModifiedByUserLogin", userLoginId);
            }

            // Save to database
            delegator.create(assignment);

            Debug.logInfo("Successfully created EmployeePayrollAssignment: " + assignmentId, MODULE);

            // Return success with assignmentId
            result.put("assignmentId", assignmentId);
            result.put("responseMessage", "Employee payroll assignment created successfully");

        } catch (GenericEntityException e) {
            Debug.logError(e, "Database error in createEmployeePayrollAssignment", MODULE);
            return ServiceUtil.returnError("Database error: " + e.getMessage());
        } catch (Exception e) {
            Debug.logError(e, "Unexpected error in createEmployeePayrollAssignment", MODULE);
            return ServiceUtil.returnError("Unexpected error: " + e.getMessage());
        }

        return result;
    }

 
   public static Map<String, Object> createEmployeePayrollAssignment(
        DispatchContext dctx, Map<String, ? extends Object> context) {

    Delegator delegator = dctx.getDelegator();

    try {
        // ------------------ Parameters ------------------
        String partyId = (String) context.get("partyId");
        String payrollStructureId = (String) context.get("payrollStructureId");
        String employmentType = (String) context.get("employmentType");
        String contractType = (String) context.get("contractType");
        BigDecimal baseSalary = toBigDecimal(context.get("baseSalary"));
        Timestamp effectiveDate = (Timestamp) context.get("effectiveDate");
        Timestamp thruDate = (Timestamp) context.get("thruDate");
        String statusId = (String) context.get("statusId");
        String comments = (String) context.get("comments");
        GenericValue userLogin = (GenericValue) context.get("userLogin");

        Debug.logInfo("Creating EmployeePayrollAssignment for partyId=" + partyId, MODULE);

        // ------------------ Validation ------------------
        if (UtilValidate.isEmpty(partyId)) {
            return ServiceUtil.returnError("Employee ID is required.");
        }
        if (UtilValidate.isEmpty(payrollStructureId)) {
            return ServiceUtil.returnError("Payroll Structure is required.");
        }
        if (effectiveDate == null) {
            return ServiceUtil.returnError("Effective Date is required.");
        }

        if (delegator.findOne("Person", UtilMisc.toMap("partyId", partyId), false) == null) {
            return ServiceUtil.returnError("Employee does not exist: " + partyId);
        }

        if (delegator.findOne("PayrollStructure",
                UtilMisc.toMap("payrollStructureId", payrollStructureId), false) == null) {
            return ServiceUtil.returnError("Payroll Structure does not exist: " + payrollStructureId);
        }

        // ------------------ Defaults ------------------
        if (thruDate == null) {
            Calendar cal = Calendar.getInstance();
            cal.setTime(effectiveDate);
            cal.set(Calendar.MONTH, Calendar.DECEMBER);
            cal.set(Calendar.DAY_OF_MONTH, 31);
            cal.set(Calendar.HOUR_OF_DAY, 23);
            cal.set(Calendar.MINUTE, 59);
            cal.set(Calendar.SECOND, 59);
            cal.set(Calendar.MILLISECOND, 0);
            thruDate = new Timestamp(cal.getTimeInMillis());
        }

        if (UtilValidate.isEmpty(statusId)) {
            statusId = "ASSIGN_ACTIVE";
        }

        // ------------------ Create Entity ------------------
        String assignmentId = delegator.getNextSeqId("EmployeePayrollAssignment");

        GenericValue assignment = delegator.makeValue("EmployeePayrollAssignment");
        assignment.set("assignmentId", assignmentId);
        assignment.set("partyId", partyId);
        assignment.set("payrollStructureId", payrollStructureId);
        assignment.set("employmentType", employmentType);
        assignment.set("contractType", contractType);
        assignment.set("baseSalary", baseSalary);
        assignment.set("effectiveDate", effectiveDate);
        assignment.set("thruDate", thruDate);
        assignment.set("statusId", statusId);
        assignment.set("comments", comments);

        if (userLogin != null) {
            assignment.set("createdByUserLogin", userLogin.getString("userLoginId"));
            assignment.set("lastModifiedByUserLogin", userLogin.getString("userLoginId"));
        }
Timestamp now = UtilDateTime.nowTimestamp();  // <- This must exist
assignment.set("createdDate", now);
assignment.set("lastModifiedDate", now);

        delegator.create(assignment);

        Debug.logInfo("EmployeePayrollAssignment created: " + assignmentId, MODULE);

        // ------------------ SUCCESS MESSAGE ------------------
        Map<String, Object> result = ServiceUtil.returnSuccess();
result.put("assignmentId", assignmentId);
result.put("responseMessage", "Employee payroll assignment created successfully");
//return result;
return ServiceUtil.returnSuccess ("Employee payroll assignment created successfully"  + result);

   } catch (GenericEntityException e) {
    Debug.logError(e, "Database error", MODULE);

    // Return detailed error message for the screen
    String detailedMessage = "Database error: " + e.getMessage();
    if (e.getCause() != null) {
        detailedMessage += " | Cause: " + e.getCause().toString();
    }
    return ServiceUtil.returnError(detailedMessage);
} catch (Exception e) {
    Debug.logError(e, "Unexpected error", MODULE);

    String detailedMessage = "Unexpected error: " + e.getMessage();
    if (e.getCause() != null) {
        detailedMessage += " | Cause: " + e.getCause().toString();
    }
    return ServiceUtil.returnError(detailedMessage);
}

}

 public static Map<String, Object> createPayrollAssignmentRest(DispatchContext dctx, Map<String, ? extends Object> context) {
    Delegator delegator = dctx.getDelegator();
    GenericValue userLogin = (GenericValue) context.get("userLogin");

    try {
        // ------------------ Parameters ------------------
        String partyId = (String) context.get("partyId");
        String payrollStructureId = (String) context.get("payrollStructureId");
        String employmentType = (String) context.get("employmentType");
        String contractType = (String) context.get("contractType");
        BigDecimal baseSalary = toBigDecimal(context.get("baseSalary"));
        Timestamp effectiveDate = (Timestamp) context.get("effectiveDate");
        Timestamp thruDate = (Timestamp) context.get("thruDate");
        String statusId = (String) context.get("statusId");
        String comments = (String) context.get("comments");

        // ------------------ Validation ------------------
        List<String> errors = new ArrayList<>();
        if (UtilValidate.isEmpty(partyId)) errors.add("partyId is required");
        if (UtilValidate.isEmpty(payrollStructureId)) errors.add("payrollStructureId is required");
        if (effectiveDate == null) errors.add("effectiveDate is required");

        if (!errors.isEmpty()) {
            return ServiceUtil.returnError("Validation failed: " + String.join(", ", errors));
        }

        if (delegator.findOne("Person", UtilMisc.toMap("partyId", partyId), false) == null) {
            return ServiceUtil.returnError("Employee does not exist: " + partyId);
        }

        if (delegator.findOne("PayrollStructure",
                UtilMisc.toMap("payrollStructureId", payrollStructureId), false) == null) {
            return ServiceUtil.returnError("Payroll Structure does not exist: " + payrollStructureId);
        }

        // ------------------ Defaults ------------------
        if (thruDate == null) {
            Calendar cal = Calendar.getInstance();
            cal.setTime(effectiveDate);
            cal.set(Calendar.MONTH, Calendar.DECEMBER);
            cal.set(Calendar.DAY_OF_MONTH, 31);
            cal.set(Calendar.HOUR_OF_DAY, 23);
            cal.set(Calendar.MINUTE, 59);
            cal.set(Calendar.SECOND, 59);
            cal.set(Calendar.MILLISECOND, 0);
            thruDate = new Timestamp(cal.getTimeInMillis());
        }

        if (UtilValidate.isEmpty(statusId)) statusId = "ASSIGN_ACTIVE";

        // ------------------ Create Entity ------------------
        String assignmentId = delegator.getNextSeqId("EmployeePayrollAssignment");
        GenericValue assignment = delegator.makeValue("EmployeePayrollAssignment");
        assignment.set("assignmentId", assignmentId);
        assignment.set("partyId", partyId);
        assignment.set("payrollStructureId", payrollStructureId);
        assignment.set("employmentType", employmentType);
        assignment.set("contractType", contractType);
        assignment.set("baseSalary", baseSalary);
        assignment.set("effectiveDate", effectiveDate);
        assignment.set("thruDate", thruDate);
        assignment.set("statusId", statusId);
        assignment.set("comments", comments);

        Timestamp now = UtilDateTime.nowTimestamp();
        assignment.set("createdDate", now);
        assignment.set("lastModifiedDate", now);

        if (userLogin != null) {
            assignment.set("createdByUserLogin", userLogin.getString("userLoginId"));
            assignment.set("lastModifiedByUserLogin", userLogin.getString("userLoginId"));
        }

        delegator.create(assignment);

        // ------------------ Return Success ------------------
       Map<String, Object> result = ServiceUtil.returnSuccess("Employee payroll assignment created successfully");
result.put("assignmentId", assignmentId);
result.put("responseMessage", "Employee payroll assignment created successfully");
return result;


    } catch (GenericEntityException e) {
        Debug.logError(e, "Database error while creating payroll assignment", MODULE);
        return ServiceUtil.returnError("Database error: " + e.getMessage());
    } catch (Exception e) {
        Debug.logError(e, "Unexpected error while creating payroll assignment", MODULE);
        return ServiceUtil.returnError("Unexpected error: " + e.getMessage());
    }
}


}
