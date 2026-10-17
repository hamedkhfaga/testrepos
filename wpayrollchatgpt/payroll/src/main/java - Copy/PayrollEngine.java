package org.apache.ofbiz.payroll;

import org.apache.ofbiz.entity.Delegator;
import org.apache.ofbiz.entity.GenericEntityException;
import org.apache.ofbiz.entity.GenericValue;
import org.apache.ofbiz.entity.util.EntityQuery;
import org.apache.ofbiz.service.DispatchContext;
import org.apache.ofbiz.service.LocalDispatcher;
import org.apache.ofbiz.service.ServiceUtil;
import org.apache.ofbiz.service.GenericServiceException;
import org.apache.ofbiz.base.util.Debug;
import org.apache.ofbiz.base.util.UtilMisc;
import org.apache.ofbiz.entity.condition.EntityCondition;
import org.apache.ofbiz.entity.condition.EntityOperator;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.*;
import org.apache.ofbiz.entity.condition.EntityCondition;
import org.apache.ofbiz.entity.condition.EntityOperator;


/**
 * Main Payroll Engine - Core payroll processing services
 */
public class PayrollEngine {

    public static final String module = PayrollEngine.class.getName();

    /**
     * Comprehensive Payroll Calculation Service
     */
    public static Map<String, Object> calculateComprehensivePayroll(DispatchContext dctx, Map<String, ?> context) {
        LocalDispatcher dispatcher = dctx.getDispatcher();
        Delegator delegator = dctx.getDelegator();
        Map<String, Object> result = ServiceUtil.returnSuccess();
        
        try {
            String payrollIterationId = (String) context.get("payrollIterationId");
            GenericValue userLogin = (GenericValue) context.get("userLogin");
            String userLoginId = userLogin != null ? userLogin.getString("userLoginId") : "system";
            
            Debug.logInfo("Starting comprehensive payroll calculation for iteration: " + payrollIterationId, module);
            
            // Get payroll iteration
            GenericValue payrollIteration = EntityQuery.use(delegator)
                    .from("EnhancedPayrollIteration")
                    .where("payrollIterationId", payrollIterationId)
                    .queryOne();
            
            if (payrollIteration == null) {
                return ServiceUtil.returnError("Payroll iteration not found: " + payrollIterationId);
            }
            
            // Get all active employee assignments
            List<GenericValue> employeeAssignments = EntityQuery.use(delegator)
                    .from("EmployeePayrollAssignment")
                    .where("statusId", "ACTIVE")
                    .queryList();
            
            List<Map<String, Object>> payrollResults = new ArrayList<>();
            BigDecimal totalGrossPay = BigDecimal.ZERO;
            BigDecimal totalNetPay = BigDecimal.ZERO;
            BigDecimal totalDeductions = BigDecimal.ZERO;
            
            // Process each employee
            for (GenericValue assignment : employeeAssignments) {
                String partyId = assignment.getString("partyId");
                
                Map<String, Object> employeeResult = calculateEmployeePayroll(
                    delegator, dispatcher, partyId, payrollIteration);
                
                if (employeeResult != null) {
                    payrollResults.add(employeeResult);
                    
                    // Update totals
                    BigDecimal grossSalary = (BigDecimal) employeeResult.get("grossSalary");
                    BigDecimal netSalary = (BigDecimal) employeeResult.get("netSalary");
                    BigDecimal deductions = (BigDecimal) employeeResult.get("totalDeductions");
                    
                    if (grossSalary != null) totalGrossPay = totalGrossPay.add(grossSalary);
                    if (netSalary != null) totalNetPay = totalNetPay.add(netSalary);
                    if (deductions != null) totalDeductions = totalDeductions.add(deductions);
                    
                    // Save individual result
                    saveEmployeePayrollResult(delegator, payrollIterationId, partyId, employeeResult);
                }
            }
            
            // Update payroll iteration with totals
            payrollIteration.set("totalGrossPay", totalGrossPay);
            payrollIteration.set("totalNetPay", totalNetPay);
            payrollIteration.set("totalDeductions", totalDeductions);
            payrollIteration.set("totalEmployees", (long) payrollResults.size());
            payrollIteration.set("statusId", "CALCULATED");
            delegator.store(payrollIteration);
            
            result.put("payrollResults", payrollResults);
            result.put("totalEmployees", payrollResults.size());
            result.put("totalGrossPay", totalGrossPay);
            result.put("totalNetPay", totalNetPay);
            result.put("totalDeductions", totalDeductions);
            
            Debug.logInfo("Payroll calculation completed for " + payrollResults.size() + " employees", module);
            
        } catch (Exception e) {
            Debug.logError(e, "Error in comprehensive payroll calculation", module);
            return ServiceUtil.returnError("Payroll calculation failed: " + e.getMessage());
        }
        
        return result;
    }
	
	public static Map<String, Object> getRecentPayrolls(DispatchContext dctx, Map<String, Object> context) {
    Delegator delegator = dctx.getDelegator();
    Map<String, Object> result = ServiceUtil.returnSuccess();

    try {
        // Get the last 10 payroll iterations
        List<GenericValue> recentList = EntityQuery.use(delegator)
                .from("PayrollIteration")
                .orderBy("-createdStamp")
                .maxRows(10)
                .queryList();

        // Return list to UI
        result.put("recentPayrolls", recentList);

    } catch (Exception e) {
        return ServiceUtil.returnError("Failed to fetch recent payrolls: " + e.getMessage());
    }

    return result;
}


  /**
 * Get Payroll Statistics Service
 */

/**
 * Get Payroll Statistics Service
 */
public static Map<String, Object> getPayrollStatistics(DispatchContext dctx, Map<String, ?> context) {
    Delegator delegator = dctx.getDelegator();
    Map<String, Object> result = ServiceUtil.returnSuccess();

    try {
        // Total employees
        long totalEmployees = EntityQuery.use(delegator)
                .from("EmployeePayrollAssignment")
                .where("statusId", "ACTIVE")
                .queryCount();

        // Active payroll iterations (CALCULATED or APPROVED)
        long activePayrolls = EntityQuery.use(delegator)
                .from("EnhancedPayrollIteration")
                .where(EntityCondition.makeCondition("statusId", EntityOperator.IN, Arrays.asList("CALCULATED", "APPROVED")))
                .queryCount();

        // Pending loans (sum of remainingAmount)
        List<GenericValue> pendingLoansList = EntityQuery.use(delegator)
                .from("EmployeeLoan")
                .where("statusId", "PENDING")
                .queryList();

        BigDecimal pendingLoans = pendingLoansList.stream()
                .map(e -> e.getBigDecimal("remainingAmount") != null ? e.getBigDecimal("remainingAmount") : BigDecimal.ZERO)
                .reduce(BigDecimal.ZERO, BigDecimal::add);

        // This month's payroll iterations
        Calendar cal = Calendar.getInstance();
        cal.set(Calendar.DAY_OF_MONTH, 1);
        cal.set(Calendar.HOUR_OF_DAY, 0);
        cal.set(Calendar.MINUTE, 0);
        cal.set(Calendar.SECOND, 0);
        cal.set(Calendar.MILLISECOND, 0);
        java.sql.Timestamp startOfMonth = new java.sql.Timestamp(cal.getTimeInMillis());

        cal.add(Calendar.MONTH, 1);
        cal.add(Calendar.MILLISECOND, -1);
        java.sql.Timestamp endOfMonth = new java.sql.Timestamp(cal.getTimeInMillis());

        // Combine conditions for this month and status APPROVED
        List<EntityCondition> conditions = Arrays.asList(
                EntityCondition.makeCondition("statusId", EntityOperator.EQUALS, "APPROVED"),
                EntityCondition.makeCondition("payPeriodFrom", EntityOperator.GREATER_THAN_EQUAL_TO, startOfMonth),
                EntityCondition.makeCondition("payPeriodFrom", EntityOperator.LESS_THAN_EQUAL_TO, endOfMonth)
        );

        long thisMonthPayroll = EntityQuery.use(delegator)
                .from("EnhancedPayrollIteration")
                .where(EntityCondition.makeCondition(conditions, EntityOperator.AND))
                .queryCount();

        // Put results
        result.put("totalEmployees", totalEmployees);
        result.put("activePayrolls", activePayrolls);
        result.put("pendingLoans", pendingLoans.setScale(2, RoundingMode.HALF_UP));
        result.put("thisMonthPayroll", thisMonthPayroll);

    } catch (GenericEntityException e) {
        Debug.logError(e, "Error fetching payroll statistics", module);
        return ServiceUtil.returnError("Failed to get payroll statistics: " + e.getMessage());
    }

    return result;
}

    /**
     * Calculate payroll for individual employee
     */
    private static Map<String, Object> calculateEmployeePayroll(Delegator delegator,
                                                               LocalDispatcher dispatcher,
                                                               String partyId,
                                                               GenericValue payrollIteration) 
                                                               throws GenericEntityException, GenericServiceException {
        Map<String, Object> result = new HashMap<>();
        
        try {
            // Get employee assignment
            GenericValue assignment = EntityQuery.use(delegator)
                    .from("EmployeePayrollAssignment")
                    .where("partyId", partyId, "statusId", "ACTIVE")
                    .queryOne();
            
            if (assignment == null) {
                Debug.logWarning("No active payroll assignment found for employee: " + partyId, module);
                return null;
            }
            
            BigDecimal basicSalary = assignment.getBigDecimal("baseSalary");
            if (basicSalary == null) basicSalary = BigDecimal.ZERO;
            
            // Calculate components
            Map<String, Object> overtimeResult = dispatcher.runSync("calculateOvertime", 
                UtilMisc.toMap("partyId", partyId, "payrollIterationId", payrollIteration.get("payrollIterationId")));
            BigDecimal overtimeAmount = (BigDecimal) overtimeResult.get("overtimeAmount");
            if (overtimeAmount == null) overtimeAmount = BigDecimal.ZERO;
            
            Map<String, Object> allowanceResult = dispatcher.runSync("calculateAllowances", 
                UtilMisc.toMap("partyId", partyId));
            BigDecimal allowancesTotal = (BigDecimal) allowanceResult.get("totalAllowances");
            if (allowancesTotal == null) allowancesTotal = BigDecimal.ZERO;
            
            // Calculate gross salary
            BigDecimal grossSalary = basicSalary.add(overtimeAmount).add(allowancesTotal);
            
            // Calculate deductions
            Map<String, Object> taxResult = dispatcher.runSync("calculateIncomeTax", 
                UtilMisc.toMap("partyId", partyId, "grossSalary", grossSalary));
            BigDecimal taxAmount = (BigDecimal) taxResult.get("taxAmount");
            if (taxAmount == null) taxAmount = BigDecimal.ZERO;
            
            Map<String, Object> ssResult = dispatcher.runSync("calculateSocialSecurity", 
                UtilMisc.toMap("partyId", partyId, "grossSalary", grossSalary));
            BigDecimal socialSecurity = (BigDecimal) ssResult.get("socialSecurityAmount");
            if (socialSecurity == null) socialSecurity = BigDecimal.ZERO;
            
            Map<String, Object> loanResult = dispatcher.runSync("calculateLoanDeductions", 
                UtilMisc.toMap("partyId", partyId));
            BigDecimal loanDeductions = (BigDecimal) loanResult.get("totalLoanDeductions");
            if (loanDeductions == null) loanDeductions = BigDecimal.ZERO;
            
            // Calculate net salary
            BigDecimal totalDeductions = taxAmount.add(socialSecurity).add(loanDeductions);
            BigDecimal netSalary = grossSalary.subtract(totalDeductions);
            
            // Populate result
            result.put("partyId", partyId);
            result.put("basicSalary", basicSalary.setScale(2, RoundingMode.HALF_UP));
            result.put("overtimeAmount", overtimeAmount.setScale(2, RoundingMode.HALF_UP));
            result.put("allowancesTotal", allowancesTotal.setScale(2, RoundingMode.HALF_UP));
            result.put("grossSalary", grossSalary.setScale(2, RoundingMode.HALF_UP));
            result.put("incomeTax", taxAmount.setScale(2, RoundingMode.HALF_UP));
            result.put("socialSecurity", socialSecurity.setScale(2, RoundingMode.HALF_UP));
            result.put("loanDeductions", loanDeductions.setScale(2, RoundingMode.HALF_UP));
            result.put("totalDeductions", totalDeductions.setScale(2, RoundingMode.HALF_UP));
            result.put("netSalary", netSalary.setScale(2, RoundingMode.HALF_UP));
            
        } catch (Exception e) {
            Debug.logError(e, "Error calculating payroll for employee: " + partyId, module);
            throw new GenericServiceException(e);
        }
        
        return result;
    }

    /**
     * Save individual employee payroll result
     */
    private static void saveEmployeePayrollResult(Delegator delegator, String payrollIterationId,
                                                 String partyId, Map<String, Object> employeeResult) 
                                                 throws GenericEntityException {
        
        String payrollResultId = delegator.getNextSeqId("EmployeePayrollResult");
        
        GenericValue payrollResult = delegator.makeValue("EmployeePayrollResult");
        payrollResult.set("payrollResultId", payrollResultId);
        payrollResult.set("payrollIterationId", payrollIterationId);
        payrollResult.set("partyId", partyId);
        payrollResult.set("basicSalary", (BigDecimal) employeeResult.get("basicSalary"));
        payrollResult.set("overtimeAmount", (BigDecimal) employeeResult.get("overtimeAmount"));
        payrollResult.set("allowancesTotal", (BigDecimal) employeeResult.get("allowancesTotal"));
        payrollResult.set("grossSalary", (BigDecimal) employeeResult.get("grossSalary"));
        payrollResult.set("incomeTax", (BigDecimal) employeeResult.get("incomeTax"));
        payrollResult.set("socialSecurity", (BigDecimal) employeeResult.get("socialSecurity"));
        payrollResult.set("loanDeductions", (BigDecimal) employeeResult.get("loanDeductions"));
        payrollResult.set("totalDeductions", (BigDecimal) employeeResult.get("totalDeductions"));
        payrollResult.set("netSalary", (BigDecimal) employeeResult.get("netSalary"));
        
        // Generate barcode for payslip
        String barcodeValue = "PSLIP-" + payrollResultId + "-" + System.currentTimeMillis();
        payrollResult.set("payslipBarcode", barcodeValue);
        
        delegator.create(payrollResult);
        
        // Save barcode separately for tracking
        savePayslipBarcode(delegator, payrollResultId, barcodeValue);
    }

    /**
     * Save payslip barcode for tracking
     */
    private static void savePayslipBarcode(Delegator delegator, String payrollResultId, 
                                          String barcodeValue) throws GenericEntityException {
        
        String barcodeId = delegator.getNextSeqId("PayslipBarcode");
        
        GenericValue barcode = delegator.makeValue("PayslipBarcode");
        barcode.set("barcodeId", barcodeId);
        barcode.set("payrollResultId", payrollResultId);
        barcode.set("barcodeValue", barcodeValue);
        barcode.set("barcodeType", "QR_CODE");
        barcode.set("generatedDate", new java.sql.Timestamp(System.currentTimeMillis()));
        barcode.set("scanCount", 0L);
        
        delegator.create(barcode);
    }

    /**
     * Finalize and approve payroll run
     */
    public static Map<String, Object> finalizePayrollRun(DispatchContext dctx, Map<String, ?> context) {
        Delegator delegator = dctx.getDelegator();
        LocalDispatcher dispatcher = dctx.getDispatcher();
        
        try {
            String payrollIterationId = (String) context.get("payrollIterationId");
            GenericValue userLogin = (GenericValue) context.get("userLogin");
            String userLoginId = userLogin != null ? userLogin.getString("userLoginId") : "system";
            
            // Update status to approved
            GenericValue payrollIteration = EntityQuery.use(delegator)
                    .from("EnhancedPayrollIteration")
                    .where("payrollIterationId", payrollIterationId)
                    .queryOne();
            
            if (payrollIteration != null) {
                payrollIteration.set("statusId", "APPROVED");
                payrollIteration.set("approvedBy", userLoginId);
                payrollIteration.set("approvedDate", new java.sql.Timestamp(System.currentTimeMillis()));
                delegator.store(payrollIteration);
                
                // Trigger payslip generation and email notifications
                dispatcher.runAsync("generatePayslips", 
                    UtilMisc.toMap("payrollIterationId", payrollIterationId, "userLogin", context.get("userLogin")));
                
                // Generate bank transfer files
                dispatcher.runAsync("generateBankTransferFiles", 
                    UtilMisc.toMap("payrollIterationId", payrollIterationId, "userLogin", context.get("userLogin")));
            }
            
            return ServiceUtil.returnSuccess("Payroll run finalized successfully");
            
        } catch (Exception e) {
            Debug.logError(e, "Error finalizing payroll run", module);
            return ServiceUtil.returnError("Failed to finalize payroll: " + e.getMessage());
        }
    }
}