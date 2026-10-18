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
 * International HR Service - Handles expatriates and international employees
 */
public class InternationalHRService {

    public static final String module = InternationalHRService.class.getName();

    /**
     * Calculate allowances for international employees
     */
    public static Map<String, Object> calculateAllowances(DispatchContext dctx, Map<String, ?> context) {
        Delegator delegator = dctx.getDelegator();
        Map<String, Object> result = ServiceUtil.returnSuccess();
        
        try {
            String partyId = (String) context.get("partyId");
            BigDecimal totalAllowances = BigDecimal.ZERO;
            Map<String, BigDecimal> allowanceBreakdown = new HashMap<>();
            
            // Get international employee details
            GenericValue internationalEmp = EntityQuery.use(delegator)
                    .from("InternationalEmployee")
                    .where("partyId", partyId)
                    .queryOne();
            
            if (internationalEmp != null && "EXPAT".equals(getEmploymentType(delegator, partyId))) {
                // Calculate expatriate allowances
                BigDecimal housingAllowance = internationalEmp.getBigDecimal("housingAllowance");
                BigDecimal educationAllowance = internationalEmp.getBigDecimal("educationAllowance");
                BigDecimal transportationAllowance = internationalEmp.getBigDecimal("transportationAllowance");
                BigDecimal repatriationAllowance = internationalEmp.getBigDecimal("repatriationAllowance");
                
                if (housingAllowance != null) {
                    totalAllowances = totalAllowances.add(housingAllowance);
                    allowanceBreakdown.put("HOUSING", housingAllowance.setScale(2, RoundingMode.HALF_UP));
                }
                if (educationAllowance != null) {
                    totalAllowances = totalAllowances.add(educationAllowance);
                    allowanceBreakdown.put("EDUCATION", educationAllowance.setScale(2, RoundingMode.HALF_UP));
                }
                if (transportationAllowance != null) {
                    totalAllowances = totalAllowances.add(transportationAllowance);
                    allowanceBreakdown.put("TRANSPORTATION", transportationAllowance.setScale(2, RoundingMode.HALF_UP));
                }
                if (repatriationAllowance != null) {
                    totalAllowances = totalAllowances.add(repatriationAllowance);
                    allowanceBreakdown.put("REPATRIATION", repatriationAllowance.setScale(2, RoundingMode.HALF_UP));
                }
            } else {
                // Calculate local employee allowances
                List<GenericValue> employeeAllowances = EntityQuery.use(delegator)
                        .from("EmployeeAllowance")
                        .where("partyId", partyId)
                        .queryList();
                
                for (GenericValue allowance : employeeAllowances) {
                    BigDecimal amount = allowance.getBigDecimal("amount");
                    if (amount != null) {
                        totalAllowances = totalAllowances.add(amount);
                        String allowanceTypeId = allowance.getString("allowanceTypeId");
                        allowanceBreakdown.put(allowanceTypeId, amount.setScale(2, RoundingMode.HALF_UP));
                    }
                }
            }
            
            result.put("totalAllowances", totalAllowances.setScale(2, RoundingMode.HALF_UP));
            result.put("allowanceBreakdown", allowanceBreakdown);
            
        } catch (Exception e) {
            Debug.logError(e, "Error calculating allowances", module);
            return ServiceUtil.returnError("Allowance calculation failed: " + e.getMessage());
        }
        
        return result;
    }

    /**
     * Calculate overtime for employees
     */
    public static Map<String, Object> calculateOvertime(DispatchContext dctx, Map<String, ?> context) {
        Delegator delegator = dctx.getDelegator();
        Map<String, Object> result = ServiceUtil.returnSuccess();
        
        try {
            String partyId = (String) context.get("partyId");
            String payrollIterationId = (String) context.get("payrollIterationId");
            BigDecimal overtimeAmount = BigDecimal.ZERO;
            Long overtimeHours = 0L;
            
            // Get employee basic salary for overtime calculation
            GenericValue assignment = EntityQuery.use(delegator)
                    .from("EmployeePayrollAssignment")
                    .where("partyId", partyId, "statusId", "ACTIVE")
                    .queryOne();
            
            if (assignment != null) {
                BigDecimal basicSalary = assignment.getBigDecimal("baseSalary");
                if (basicSalary == null) basicSalary = BigDecimal.ZERO;
                
                // Calculate hourly rate (assuming 160 hours per month)
                BigDecimal hourlyRate = basicSalary.divide(new BigDecimal(160), 2, RoundingMode.HALF_UP);
                
                // In real implementation, this would integrate with time tracking system
                // For demo, using fixed overtime hours or get from context
                if (context.get("overtimeHours") != null) {
                    overtimeHours = (Long) context.get("overtimeHours");
                } else {
                    overtimeHours = 10L; // Default demo value
                }
                
                // Overtime rate: 1.5x for regular overtime
                overtimeAmount = hourlyRate.multiply(new BigDecimal("1.5"))
                                          .multiply(new BigDecimal(overtimeHours));
            }
            
            result.put("overtimeAmount", overtimeAmount.setScale(2, RoundingMode.HALF_UP));
            result.put("overtimeHours", overtimeHours);
            
        } catch (Exception e) {
            Debug.logError(e, "Error calculating overtime", module);
            return ServiceUtil.returnError("Overtime calculation failed: " + e.getMessage());
        }
        
        return result;
    }

    /**
     * Get employment type for employee
     */
    private static String getEmploymentType(Delegator delegator, String partyId) 
            throws GenericEntityException {
        GenericValue assignment = EntityQuery.use(delegator)
                .from("EmployeePayrollAssignment")
                .where("partyId", partyId, "statusId", "ACTIVE")
                .queryOne();
        
        if (assignment != null) {
            String employmentType = assignment.getString("employmentType");
            return employmentType != null ? employmentType : "LOCAL";
        }
        return "LOCAL";
    }

    /**
     * Validate work permit and visa expiry
     */
    public static Map<String, Object> validateEmployeeDocuments(DispatchContext dctx, Map<String, ?> context) {
        Delegator delegator = dctx.getDelegator();
        Map<String, Object> result = ServiceUtil.returnSuccess();
        
        try {
            String partyId = (String) context.get("partyId");
            List<String> expiringDocuments = new ArrayList<>();
            List<String> expiredDocuments = new ArrayList<>();
            
            GenericValue internationalEmp = EntityQuery.use(delegator)
                    .from("InternationalEmployee")
                    .where("partyId", partyId)
                    .queryOne();
            
            if (internationalEmp != null) {
                java.sql.Date visaExpiry = internationalEmp.getDate("visaExpiryDate");
                java.sql.Date workPermitExpiry = internationalEmp.getDate("workPermitExpiry");
                java.sql.Date currentDate = new java.sql.Date(System.currentTimeMillis());
                
                // Check visa expiry (30 days warning)
                if (visaExpiry != null) {
                    long diffDays = (visaExpiry.getTime() - currentDate.getTime()) / (1000 * 60 * 60 * 24);
                    if (diffDays < 0) {
                        expiredDocuments.add("VISA");
                    } else if (diffDays <= 30) {
                        expiringDocuments.add("VISA (expires in " + diffDays + " days)");
                    }
                }
                
                // Check work permit expiry
                if (workPermitExpiry != null) {
                    long diffDays = (workPermitExpiry.getTime() - currentDate.getTime()) / (1000 * 60 * 60 * 24);
                    if (diffDays < 0) {
                        expiredDocuments.add("WORK_PERMIT");
                    } else if (diffDays <= 30) {
                        expiringDocuments.add("WORK_PERMIT (expires in " + diffDays + " days)");
                    }
                }
            }
            
            result.put("expiringDocuments", expiringDocuments);
            result.put("expiredDocuments", expiredDocuments);
            result.put("isDocumentsValid", expiredDocuments.isEmpty());
            
        } catch (Exception e) {
            Debug.logError(e, "Error validating employee documents", module);
            return ServiceUtil.returnError("Document validation failed: " + e.getMessage());
        }
        
        return result;
    }
}