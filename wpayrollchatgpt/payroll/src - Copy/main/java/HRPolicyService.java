package org.apache.ofbiz.payroll;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.*;

import org.apache.ofbiz.entity.Delegator;
import org.apache.ofbiz.entity.GenericEntityException;
import org.apache.ofbiz.entity.GenericValue;
import org.apache.ofbiz.entity.util.EntityQuery;
import org.apache.ofbiz.service.DispatchContext;
import org.apache.ofbiz.service.ServiceUtil;
import org.apache.ofbiz.base.util.Debug;
import org.apache.ofbiz.base.util.UtilMisc;

/**
 * HR Policy Service - Handles HR policy enforcement
 */
public class HRPolicyService {

    public static final String module = HRPolicyService.class.getName();

    /**
     * Validate payroll against HR policies
     */
    public static Map<String, Object> validatePayrollAgainstPolicies(DispatchContext dctx, Map<String, ?> context) {
        Delegator delegator = dctx.getDelegator();
        Map<String, Object> result = ServiceUtil.returnSuccess();
        
        try {
            String payrollIterationId = (String) context.get("payrollIterationId");
            List<String> policyViolations = new ArrayList<>();
            List<String> policyWarnings = new ArrayList<>();
            
            // Get payroll results
            List<GenericValue> payrollResults = EntityQuery.use(delegator)
                    .from("EmployeePayrollResult")
                    .where("payrollIterationId", payrollIterationId)
                    .queryList();
            
            // Get active HR policies
            List<GenericValue> activePolicies = EntityQuery.use(delegator)
                    .from("HRPolicy")
                    .where("isActive", "Y")
                    .queryList();
            
            for (GenericValue payrollResult : payrollResults) {
                String partyId = payrollResult.getString("partyId");
                
                // Validate against each policy
                for (GenericValue policy : activePolicies) {
                    String policyType = policy.getString("policyType");
                    Map<String, Object> validationResult = validateAgainstPolicy(
                        delegator, payrollResult, policy, policyType);
                    
                    if ("VIOLATION".equals(validationResult.get("result"))) {
                        policyViolations.add((String) validationResult.get("message"));
                    } else if ("WARNING".equals(validationResult.get("result"))) {
                        policyWarnings.add((String) validationResult.get("message"));
                    }
                }
            }
            
            result.put("policyViolations", policyViolations);
            result.put("policyWarnings", policyWarnings);
            result.put("isCompliant", policyViolations.isEmpty());
            result.put("checkedPolicies", activePolicies.size());
            
        } catch (Exception e) {
            Debug.logError(e, "Error validating payroll against policies", module);
            return ServiceUtil.returnError("Policy validation failed: " + e.getMessage());
        }
        
        return result;
    }

    /**
     * Validate payroll result against specific policy
     */
    private static Map<String, Object> validateAgainstPolicy(Delegator delegator,
                                                           GenericValue payrollResult,
                                                           GenericValue policy,
                                                           String policyType) {
        Map<String, Object> result = new HashMap<>();
        
        try {
            switch (policyType) {
                case "OVERTIME":
                    return validateOvertimePolicy(payrollResult, policy);
                case "TAX":
                    return validateTaxPolicy(payrollResult, policy);
                case "LOAN":
                    return validateLoanPolicy(delegator, payrollResult, policy);
                default:
                    result.put("result", "PASS");
                    return result;
            }
        } catch (Exception e) {
            result.put("result", "ERROR");
            result.put("message", "Policy validation error: " + e.getMessage());
            return result;
        }
    }

    /**
     * Validate overtime policy
     */
    private static Map<String, Object> validateOvertimePolicy(GenericValue payrollResult, GenericValue policy) {
        Map<String, Object> result = new HashMap<>();
        
        // Example: Check if overtime exceeds policy limits
        BigDecimal overtimeAmount = payrollResult.getBigDecimal("overtimeAmount");
        if (overtimeAmount != null && overtimeAmount.compareTo(new BigDecimal("1000")) > 0) {
            result.put("result", "WARNING");
            result.put("message", "Overtime amount exceeds recommended limit for employee: " + 
                      payrollResult.getString("partyId"));
        } else {
            result.put("result", "PASS");
        }
        
        return result;
    }

    /**
     * Validate tax policy
     */
    private static Map<String, Object> validateTaxPolicy(GenericValue payrollResult, GenericValue policy) {
        Map<String, Object> result = new HashMap<>();
        
        // Example: Check if tax calculation seems reasonable
        BigDecimal grossSalary = payrollResult.getBigDecimal("grossSalary");
        BigDecimal incomeTax = payrollResult.getBigDecimal("incomeTax");
        
        if (grossSalary != null && incomeTax != null && grossSalary.compareTo(BigDecimal.ZERO) > 0) {
            BigDecimal taxRate = incomeTax.divide(grossSalary, 4, RoundingMode.HALF_UP)
                                         .multiply(new BigDecimal(100));
            
            if (taxRate.compareTo(new BigDecimal("50")) > 0) {
                result.put("result", "VIOLATION");
                result.put("message", "Tax rate exceeds maximum allowed for employee: " + 
                          payrollResult.getString("partyId") + " (" + taxRate.setScale(2, RoundingMode.HALF_UP) + "%)");
            } else {
                result.put("result", "PASS");
            }
        } else {
            result.put("result", "PASS");
        }
        
        return result;
    }

    /**
     * Validate loan policy
     */
    private static Map<String, Object> validateLoanPolicy(Delegator delegator, 
                                                         GenericValue payrollResult,
                                                         GenericValue policy) throws GenericEntityException {
        Map<String, Object> result = new HashMap<>();
        
        String partyId = payrollResult.getString("partyId");
        BigDecimal loanDeductions = payrollResult.getBigDecimal("loanDeductions");
        BigDecimal netSalary = payrollResult.getBigDecimal("netSalary");
        
        if (loanDeductions != null && netSalary != null && netSalary.compareTo(BigDecimal.ZERO) > 0) {
            BigDecimal deductionRate = loanDeductions.divide(netSalary, 4, RoundingMode.HALF_UP)
                                                    .multiply(new BigDecimal(100));
            
            // Policy: Loan deductions should not exceed 40% of net salary
            if (deductionRate.compareTo(new BigDecimal("40")) > 0) {
                result.put("result", "VIOLATION");
                result.put("message", "Loan deductions exceed policy limit for employee: " + 
                          partyId + " (" + deductionRate.setScale(2, RoundingMode.HALF_UP) + "%)");
            } else {
                result.put("result", "PASS");
            }
        } else {
            result.put("result", "PASS");
        }
        
        return result;
    }

    /**
     * Get applicable policies for employee
     */
    public static Map<String, Object> getEmployeePolicies(DispatchContext dctx, Map<String, ?> context) {
        Delegator delegator = dctx.getDelegator();
        Map<String, Object> result = ServiceUtil.returnSuccess();
        
        try {
            String partyId = (String) context.get("partyId");
            List<Map<String, Object>> applicablePolicies = new ArrayList<>();
            
            // Get employee country and employment type
            GenericValue assignment = EntityQuery.use(delegator)
                    .from("EmployeePayrollAssignment")
                    .where("partyId", partyId, "statusId", "ACTIVE")
                    .queryOne();
            
            String countryGeoId = "USA"; // Default
            String employmentType = "LOCAL";
            
            if (assignment != null) {
                employmentType = assignment.getString("employmentType");
                if (employmentType == null) {
                    employmentType = "LOCAL";
                }
                
                GenericValue internationalEmp = EntityQuery.use(delegator)
                        .from("InternationalEmployee")
                        .where("partyId", partyId)
                        .queryOne();
                
                if (internationalEmp != null) {
                    countryGeoId = internationalEmp.getString("residenceCountryGeoId");
                    if (countryGeoId == null) {
                        countryGeoId = "USA";
                    }
                }
            }
            
            // Get policies for employee's country and employment type
            List<GenericValue> policies = EntityQuery.use(delegator)
                    .from("HRPolicy")
                    .where("countryGeoId", countryGeoId, "isActive", "Y")
                    .queryList();
            
            for (GenericValue policy : policies) {
                Map<String, Object> policyInfo = new HashMap<>();
                policyInfo.put("policyId", policy.getString("policyId"));
                policyInfo.put("policyName", policy.getString("policyName"));
                policyInfo.put("policyType", policy.getString("policyType"));
                policyInfo.put("effectiveDate", policy.getDate("effectiveDate"));
                
                applicablePolicies.add(policyInfo);
            }
            
            result.put("applicablePolicies", applicablePolicies);
            result.put("countryGeoId", countryGeoId);
            result.put("employmentType", employmentType);
            
        } catch (Exception e) {
            Debug.logError(e, "Error getting employee policies", module);
            return ServiceUtil.returnError("Failed to get employee policies: " + e.getMessage());
        }
        
        return result;
    }
}