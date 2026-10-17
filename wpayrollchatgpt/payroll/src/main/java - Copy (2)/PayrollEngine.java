package org.apache.ofbiz.payroll;

import org.apache.ofbiz.entity.Delegator;
import org.apache.ofbiz.entity.GenericEntityException;
import org.apache.ofbiz.entity.GenericValue;
import org.apache.ofbiz.entity.util.EntityQuery;
import org.apache.ofbiz.entity.util.EntityFindOptions;
import org.apache.ofbiz.service.DispatchContext;
import org.apache.ofbiz.service.LocalDispatcher;
import org.apache.ofbiz.service.ServiceUtil;
import org.apache.ofbiz.service.GenericServiceException;
import org.apache.ofbiz.base.util.Debug;
import org.apache.ofbiz.base.util.UtilMisc;
import org.apache.ofbiz.base.util.UtilValidate;
import org.apache.ofbiz.entity.condition.EntityCondition;
import org.apache.ofbiz.entity.condition.EntityOperator;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.*;

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

            BigDecimal pendingLoans = BigDecimal.ZERO;
            for (GenericValue loan : pendingLoansList) {
                BigDecimal remainingAmount = loan.getBigDecimal("remainingAmount");
                if (remainingAmount != null) {
                    pendingLoans = pendingLoans.add(remainingAmount);
                }
            }

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
     * Find Employee List Service
     */
    public static Map<String, Object> findEmployeeList(DispatchContext dctx, Map<String, ?> context) {
        Delegator delegator = dctx.getDelegator();
        Map<String, Object> result = ServiceUtil.returnSuccess();

        try {
            // Get parameters
            String firstName = (String) context.get("firstName");
            String lastName = (String) context.get("lastName");
            String department = (String) context.get("department");
            String employeeId = (String) context.get("employeeId");
            String statusId = (String) context.get("statusId");
            Integer viewSize = (Integer) context.get("viewSize");
            Integer viewIndex = (Integer) context.get("viewIndex");
            
            // Set defaults
            if (viewSize == null || viewSize <= 0) viewSize = 20;
            if (viewIndex == null || viewIndex < 0) viewIndex = 0;
            
            // Build conditions
            List<EntityCondition> conditionList = new ArrayList<>();
            
            if (UtilValidate.isNotEmpty(firstName)) {
                conditionList.add(EntityCondition.makeCondition("firstName", 
                    EntityOperator.LIKE, "%" + firstName + "%"));
            }
            
            if (UtilValidate.isNotEmpty(lastName)) {
                conditionList.add(EntityCondition.makeCondition("lastName", 
                    EntityOperator.LIKE, "%" + lastName + "%"));
            }
            
            if (UtilValidate.isNotEmpty(department)) {
                conditionList.add(EntityCondition.makeCondition("department", department));
            }
            
            if (UtilValidate.isNotEmpty(employeeId)) {
                conditionList.add(EntityCondition.makeCondition("employeeId", employeeId));
            }
            
            if (UtilValidate.isNotEmpty(statusId)) {
                conditionList.add(EntityCondition.makeCondition("statusId", statusId));
            } else {
                // Default to active employees
                conditionList.add(EntityCondition.makeCondition("statusId", "EMPL_ACTIVE"));
            }
            
            EntityCondition whereCondition = null;
            if (!conditionList.isEmpty()) {
                whereCondition = EntityCondition.makeCondition(conditionList, EntityOperator.AND);
            }
            
            // Create find options
            EntityFindOptions findOptions = new EntityFindOptions();
            findOptions.setMaxRows(viewSize);
            findOptions.setResultSetType(EntityFindOptions.TYPE_SCROLL_INSENSITIVE);
            
            // Find employees
            List<GenericValue> employeeList = delegator.findList("Employee", 
                whereCondition, null, Arrays.asList("lastName", "firstName"), 
                findOptions, false);
            
            // Get total count - FIXED: Use long instead of int
            long totalCount = delegator.findCountByCondition("Employee", whereCondition, null, null);
            
            // Prepare result list
            List<Map<String, Object>> resultList = new ArrayList<>();
            
            for (GenericValue employee : employeeList) {
                Map<String, Object> empMap = new HashMap<>();
                empMap.put("employeeId", employee.getString("employeeId"));
                empMap.put("firstName", employee.getString("firstName"));
                empMap.put("lastName", employee.getString("lastName"));
                empMap.put("fullName", employee.getString("firstName") + " " + employee.getString("lastName"));
                empMap.put("department", employee.getString("department"));
                empMap.put("position", employee.getString("position"));
                empMap.put("email", employee.getString("email"));
                empMap.put("phone", employee.getString("phone"));
                empMap.put("hireDate", employee.get("hireDate"));
                empMap.put("statusId", employee.getString("statusId"));
                empMap.put("salary", employee.get("salary"));
                
                // Determine employee type
                GenericValue intlEmployee = EntityQuery.use(delegator)
                    .from("InternationalEmployee")
                    .where("employeeId", employee.getString("employeeId"))
                    .queryOne();
                    
                empMap.put("employeeType", (intlEmployee != null) ? "INTERNATIONAL" : "REGULAR");
                
                resultList.add(empMap);
            }
            
            // Set results
            result.put("employeeList", resultList);
            result.put("listSize", totalCount);
            result.put("viewIndex", viewIndex);
            result.put("viewSize", viewSize);
            
            Debug.logInfo("Found " + totalCount + " employees", module);
                
        } catch (GenericEntityException e) {
            Debug.logError(e, "Error finding employee list", module);
            return ServiceUtil.returnError("Error finding employee list: " + e.getMessage());
        }
        
        return result;
    }

    /**
     * Create Employee Assignment Service
     */
    public static Map<String, Object> createEmployeeAssignment(DispatchContext dctx, Map<String, ?> context) {
        Delegator delegator = dctx.getDelegator();
        Map<String, Object> result = ServiceUtil.returnSuccess();

        try {
            // Get parameters
            String employeeId = (String) context.get("employeeId");
            String assignmentName = (String) context.get("assignmentName");
            Date startDate = (Date) context.get("startDate");
            Date endDate = (Date) context.get("endDate");
            String comments = (String) context.get("comments");
            GenericValue userLogin = (GenericValue) context.get("userLogin");
            
            // Validate required fields
            if (UtilValidate.isEmpty(employeeId)) {
                return ServiceUtil.returnError("Employee ID is required");
            }
            
            if (UtilValidate.isEmpty(assignmentName)) {
                return ServiceUtil.returnError("Assignment name is required");
            }
            
            if (startDate == null) {
                return ServiceUtil.returnError("Start date is required");
            }
            
            // Check if employee exists
            GenericValue employee = EntityQuery.use(delegator)
                .from("Employee")
                .where("employeeId", employeeId)
                .queryOne();
                
            if (employee == null) {
                return ServiceUtil.returnError("Employee not found: " + employeeId);
            }
            
            // Create assignment ID
            String assignmentId = delegator.getNextSeqId("EmployeeAssignment");
            
            // Create assignment record
            GenericValue newAssignment = delegator.makeValue("EmployeeAssignment");
            newAssignment.set("assignmentId", assignmentId);
            newAssignment.set("employeeId", employeeId);
            newAssignment.set("assignmentName", assignmentName);
            newAssignment.set("startDate", startDate);
            newAssignment.set("endDate", endDate);
            newAssignment.set("comments", comments);
            newAssignment.set("statusId", "ASSIGN_ACTIVE");
            newAssignment.set("createdByUserLogin", userLogin.getString("userLoginId"));
            newAssignment.set("lastModifiedByUserLogin", userLogin.getString("userLoginId"));
            newAssignment.set("createdDate", new java.sql.Timestamp(System.currentTimeMillis()));
            
            delegator.create(newAssignment);
            
            // Set result
            result.put("assignmentId", assignmentId);
            result.put("assignmentName", assignmentName);
            result.put("employeeId", employeeId);
            result.put("employeeName", employee.getString("firstName") + " " + employee.getString("lastName"));
            
            Debug.logInfo("Created employee assignment " + assignmentId + " for employee: " + employeeId, module);
            
        } catch (GenericEntityException e) {
            Debug.logError(e, "Error creating employee assignment", module);
            return ServiceUtil.returnError("Error creating assignment: " + e.getMessage());
        }
        
        return result;
    }

    /**
     * Find International Employee List Service
     */
    public static Map<String, Object> findInternationalEmployeeList(DispatchContext dctx, Map<String, ?> context) {
        Delegator delegator = dctx.getDelegator();
        Map<String, Object> result = ServiceUtil.returnSuccess();

        try {
            // Get parameters
            String nationality = (String) context.get("nationality");
            String visaType = (String) context.get("visaType");
            Date visaExpiryFrom = (Date) context.get("visaExpiryFrom");
            Date visaExpiryTo = (Date) context.get("visaExpiryTo");
            Integer viewSize = (Integer) context.get("viewSize");
            Integer viewIndex = (Integer) context.get("viewIndex");
            
            // Set defaults
            if (viewSize == null || viewSize <= 0) viewSize = 20;
            if (viewIndex == null || viewIndex < 0) viewIndex = 0;
            
            // Build conditions
            List<EntityCondition> conditionList = new ArrayList<>();
            
            if (UtilValidate.isNotEmpty(nationality)) {
                conditionList.add(EntityCondition.makeCondition("nationality", nationality));
            }
            
            if (UtilValidate.isNotEmpty(visaType)) {
                conditionList.add(EntityCondition.makeCondition("visaType", visaType));
            }
            
            if (visaExpiryFrom != null) {
                conditionList.add(EntityCondition.makeCondition("visaExpiry", 
                    EntityOperator.GREATER_THAN_EQUAL_TO, visaExpiryFrom));
            }
            
            if (visaExpiryTo != null) {
                conditionList.add(EntityCondition.makeCondition("visaExpiry", 
                    EntityOperator.LESS_THAN_EQUAL_TO, visaExpiryTo));
            }
            
            EntityCondition whereCondition = null;
            if (!conditionList.isEmpty()) {
                whereCondition = EntityCondition.makeCondition(conditionList, EntityOperator.AND);
            }
            
            // Find international employees
            EntityFindOptions findOptions = new EntityFindOptions();
            findOptions.setMaxRows(viewSize);
            
            List<GenericValue> intlEmployeeList = delegator.findList("InternationalEmployee", 
                whereCondition, null, Arrays.asList("employeeId"), 
                findOptions, false);
            
            // Get total count - FIXED: Use long instead of int
            long totalCount = delegator.findCountByCondition("InternationalEmployee", whereCondition, null, null);
            
            // Prepare result
            List<Map<String, Object>> resultList = new ArrayList<>();
            
            for (GenericValue intlEmp : intlEmployeeList) {
                // Get employee details
                GenericValue employee = EntityQuery.use(delegator)
                    .from("Employee")
                    .where("employeeId", intlEmp.getString("employeeId"))
                    .queryOne();
                
                if (employee != null) {
                    Map<String, Object> empMap = new HashMap<>();
                    empMap.put("employeeId", employee.getString("employeeId"));
                    empMap.put("firstName", employee.getString("firstName"));
                    empMap.put("lastName", employee.getString("lastName"));
                    empMap.put("department", employee.getString("department"));
                    empMap.put("position", employee.getString("position"));
                    empMap.put("nationality", intlEmp.getString("nationality"));
                    empMap.put("visaType", intlEmp.getString("visaType"));
                    empMap.put("visaExpiry", intlEmp.get("visaExpiry"));
                    empMap.put("passportNumber", intlEmp.getString("passportNumber"));
                    empMap.put("residenceCountry", intlEmp.getString("residenceCountry"));
                    
                    resultList.add(empMap);
                }
            }
            
            // Set results
            result.put("intlEmployeeList", resultList);
            result.put("listSize", totalCount);
            
            Debug.logInfo("Found " + totalCount + " international employees", module);
            
        } catch (GenericEntityException e) {
            Debug.logError(e, "Error finding international employees", module);
            return ServiceUtil.returnError("Error finding international employees: " + e.getMessage());
        }
        
        return result;
    }

    /**
     * Create International Employee Service
     */
    public static Map<String, Object> createInternationalEmployee(DispatchContext dctx, Map<String, ?> context) {
        Delegator delegator = dctx.getDelegator();
        Map<String, Object> result = ServiceUtil.returnSuccess();

        try {
            // Get parameters
            String employeeId = (String) context.get("employeeId");
            String nationality = (String) context.get("nationality");
            String visaType = (String) context.get("visaType");
            Date visaExpiry = (Date) context.get("visaExpiry");
            String passportNumber = (String) context.get("passportNumber");
            String residenceCountry = (String) context.get("residenceCountry");
            GenericValue userLogin = (GenericValue) context.get("userLogin");
            
            // Validate required fields
            if (UtilValidate.isEmpty(employeeId)) {
                return ServiceUtil.returnError("Employee ID is required");
            }
            
            if (UtilValidate.isEmpty(nationality)) {
                return ServiceUtil.returnError("Nationality is required");
            }
            
            if (UtilValidate.isEmpty(visaType)) {
                return ServiceUtil.returnError("Visa type is required");
            }
            
            if (visaExpiry == null) {
                return ServiceUtil.returnError("Visa expiry date is required");
            }
            
            // Check if employee exists
            GenericValue employee = EntityQuery.use(delegator)
                .from("Employee")
                .where("employeeId", employeeId)
                .queryOne();
                
            if (employee == null) {
                return ServiceUtil.returnError("Employee not found: " + employeeId);
            }
            
            // Check if already an international employee
            GenericValue existingIntl = EntityQuery.use(delegator)
                .from("InternationalEmployee")
                .where("employeeId", employeeId)
                .queryOne();
                
            if (existingIntl != null) {
                return ServiceUtil.returnError("Employee is already registered as international employee");
            }
            
            // Create international employee record
            GenericValue intlEmployee = delegator.makeValue("InternationalEmployee");
            intlEmployee.set("employeeId", employeeId);
            intlEmployee.set("nationality", nationality);
            intlEmployee.set("visaType", visaType);
            intlEmployee.set("visaExpiry", visaExpiry);
            intlEmployee.set("passportNumber", passportNumber);
            intlEmployee.set("residenceCountry", residenceCountry);
            intlEmployee.set("createdByUserLogin", userLogin.getString("userLoginId"));
            intlEmployee.set("createdDate", new java.sql.Timestamp(System.currentTimeMillis()));
            
            delegator.create(intlEmployee);
            
            // Update employee type
            employee.set("employeeType", "INTERNATIONAL");
            delegator.store(employee);
            
            // Set result
            result.put("employeeId", employeeId);
            result.put("employeeName", employee.getString("firstName") + " " + employee.getString("lastName"));
            result.put("nationality", nationality);
            result.put("visaType", visaType);
            
            Debug.logInfo("Created international employee record for: " + employeeId, module);
            
        } catch (GenericEntityException e) {
            Debug.logError(e, "Error creating international employee", module);
            return ServiceUtil.returnError("Error creating international employee: " + e.getMessage());
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
	
	
	 public static Map<String, Object> getEmployeeListData(DispatchContext dctx, Map<String, ? extends Object> context) {
        Map<String, Object> result = ServiceUtil.returnSuccess();
        
        try {
            // Query to get employees with their details
            List<GenericValue> employeeList = EntityQuery.use(dctx.getDelegator())
                .from("Person")
                .orderBy("lastName", "firstName")
                .queryList();
            
            // Query to get payroll assignments
            List<GenericValue> assignments = EntityQuery.use(dctx.getDelegator())
                .from("EmployeePayrollAssignment")
                .orderBy("effectiveDate")
                .filterByDate()
                .queryList();
            
            // Query to get allowance totals per employee
            List<GenericValue> allowances = EntityQuery.use(dctx.getDelegator())
                .from("EmployeeAllowance")
                .select("partyId", "allowanceTypeId", "amount")
                .orderBy("partyId")
                .filterByDate()
                .queryList();
            
            result.put("employeeList", employeeList);
            result.put("assignments", assignments);
            result.put("allowances", allowances);
            
        } catch (GenericEntityException e) {
            return ServiceUtil.returnError("Error retrieving employee data: " + e.getMessage());
        }
        
        return result;
    }
}