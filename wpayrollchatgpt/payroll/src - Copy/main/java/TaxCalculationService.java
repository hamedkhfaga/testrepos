package org.apache.ofbiz.payroll;

import org.apache.ofbiz.entity.Delegator;
import org.apache.ofbiz.entity.GenericEntityException;
import org.apache.ofbiz.entity.GenericValue;
import org.apache.ofbiz.entity.util.EntityQuery;
import org.apache.ofbiz.service.DispatchContext;
import org.apache.ofbiz.service.LocalDispatcher;
import org.apache.ofbiz.service.ServiceUtil;
import org.apache.ofbiz.base.util.Debug;
import org.apache.ofbiz.base.util.UtilMisc;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.*;

/**
 * Tax Calculation Service - Handles income tax and social security calculations
 */
public class TaxCalculationService {

    public static final String module = TaxCalculationService.class.getName();

    /**
     * Calculate income tax based on country-specific rules
     */
    public static Map<String, Object> calculateIncomeTax(DispatchContext dctx, Map<String, ?> context) {
        Delegator delegator = dctx.getDelegator();
        Map<String, Object> result = ServiceUtil.returnSuccess();
        
        try {
            String partyId = (String) context.get("partyId");
            BigDecimal grossSalary = (BigDecimal) context.get("grossSalary");
            
            if (grossSalary == null) {
                grossSalary = BigDecimal.ZERO;
            }
            
            // Get employee details to determine country
            GenericValue employeeAssignment = EntityQuery.use(delegator)
                    .from("EmployeePayrollAssignment")
                    .where("partyId", partyId, "statusId", "ACTIVE")
                    .queryOne();
            
            String employmentType = "LOCAL"; // Default
            if (employeeAssignment != null) {
                employmentType = employeeAssignment.getString("employmentType");
            }
            
            // Get international employee details if applicable
            GenericValue internationalEmp = EntityQuery.use(delegator)
                    .from("InternationalEmployee")
                    .where("partyId", partyId)
                    .queryOne();
            
            String countryGeoId = "USA"; // Default - should be configured
            if (internationalEmp != null) {
                countryGeoId = internationalEmp.getString("residenceCountryGeoId");
            }
            
            // Calculate tax based on country and employment type
            BigDecimal taxAmount = calculateTaxAmount(delegator, countryGeoId, employmentType, grossSalary);
            
            result.put("taxAmount", taxAmount);
            result.put("countryGeoId", countryGeoId);
            result.put("employmentType", employmentType);
            
        } catch (Exception e) {
            Debug.logError(e, "Error calculating income tax", module);
            return ServiceUtil.returnError("Tax calculation failed: " + e.getMessage());
        }
        
        return result;
    }

    /**
     * Calculate tax amount based on country configuration
     */
    private static BigDecimal calculateTaxAmount(Delegator delegator, String countryGeoId,
                                                String employmentType, BigDecimal grossSalary) 
                                                throws GenericEntityException {
        
        // For expats, check if tax equalization applies
        if ("EXPAT".equals(employmentType)) {
            GenericValue internationalEmp = EntityQuery.use(delegator)
                    .from("InternationalEmployee")
                    .where("residenceCountryGeoId", countryGeoId)
                    .queryOne();
            
            if (internationalEmp != null && "Y".equals(internationalEmp.getString("isTaxEqualization"))) {
                // Apply tax equalization logic
                return calculateTaxEqualization(delegator, countryGeoId, grossSalary);
            }
        }
        
        // Get tax configuration for the country
        List<GenericValue> taxConfigs = EntityQuery.use(delegator)
                .from("PayrollTaxConfig")
                .where("countryGeoId", countryGeoId, "isActive", "Y")
                .orderBy("minIncome")
                .queryList();
        
        BigDecimal taxAmount = BigDecimal.ZERO;
        BigDecimal remainingIncome = grossSalary;
        
        // Calculate progressive tax
        for (GenericValue taxConfig : taxConfigs) {
            BigDecimal minIncome = taxConfig.getBigDecimal("minIncome");
            BigDecimal maxIncome = taxConfig.getBigDecimal("maxIncome");
            BigDecimal taxRate = taxConfig.getBigDecimal("taxRate");
            BigDecimal additionalAmount = taxConfig.getBigDecimal("additionalAmount");
            
            if (minIncome == null) continue;
            
            if (maxIncome == null || grossSalary.compareTo(minIncome) > 0) {
                BigDecimal taxableAmount;
                if (maxIncome == null || grossSalary.compareTo(maxIncome) <= 0) {
                    taxableAmount = grossSalary.subtract(minIncome).max(BigDecimal.ZERO);
                } else {
                    taxableAmount = maxIncome.subtract(minIncome).max(BigDecimal.ZERO);
                }
                
                if (taxableAmount.compareTo(BigDecimal.ZERO) > 0 && taxRate != null) {
                    BigDecimal bracketTax = taxableAmount.multiply(taxRate.divide(new BigDecimal(100), 10, RoundingMode.HALF_UP));
                    taxAmount = taxAmount.add(bracketTax);
                }
                
                if (additionalAmount != null) {
                    taxAmount = taxAmount.add(additionalAmount);
                }
            }
        }
        
        return taxAmount.setScale(2, RoundingMode.HALF_UP);
    }

    /**
     * Calculate social security contributions
     */
    public static Map<String, Object> calculateSocialSecurity(DispatchContext dctx, Map<String, ?> context) {
        Delegator delegator = dctx.getDelegator();
        Map<String, Object> result = ServiceUtil.returnSuccess();
        
        try {
            String partyId = (String) context.get("partyId");
            BigDecimal grossSalary = (BigDecimal) context.get("grossSalary");
            
            if (grossSalary == null) {
                grossSalary = BigDecimal.ZERO;
            }
            
            // Get employee country
            GenericValue internationalEmp = EntityQuery.use(delegator)
                    .from("InternationalEmployee")
                    .where("partyId", partyId)
                    .queryOne();
            
            String countryGeoId = "USA"; // Default
            if (internationalEmp != null) {
                countryGeoId = internationalEmp.getString("residenceCountryGeoId");
            }
            
            // Get social security configuration
            GenericValue ssConfig = EntityQuery.use(delegator)
                    .from("SocialSecurityConfig")
                    .where("countryGeoId", countryGeoId)
                    .queryOne();
            
            BigDecimal ssAmount = BigDecimal.ZERO;
            
            if (ssConfig != null) {
                BigDecimal employeeRate = ssConfig.getBigDecimal("employeeRate");
                BigDecimal maxBase = ssConfig.getBigDecimal("maxContributionBase");
                
                BigDecimal contributableSalary = grossSalary;
                if (maxBase != null && grossSalary.compareTo(maxBase) > 0) {
                    contributableSalary = maxBase;
                }
                
                if (employeeRate != null) {
                    ssAmount = contributableSalary.multiply(employeeRate.divide(new BigDecimal(100), 10, RoundingMode.HALF_UP));
                }
            }
            
            result.put("socialSecurityAmount", ssAmount.setScale(2, RoundingMode.HALF_UP));
            result.put("countryGeoId", countryGeoId);
            
        } catch (Exception e) {
            Debug.logError(e, "Error calculating social security", module);
            return ServiceUtil.returnError("Social security calculation failed: " + e.getMessage());
        }
        
        return result;
    }

    /**
     * Tax equalization calculation for expatriates
     */
    private static BigDecimal calculateTaxEqualization(Delegator delegator, String countryGeoId, 
                                                      BigDecimal grossSalary) throws GenericEntityException {
        // Simplified tax equalization logic
        // In practice, this would involve complex calculations comparing home and host country taxes
        
        // For now, return a flat rate for demonstration
        return grossSalary.multiply(new BigDecimal("0.15")).setScale(2, RoundingMode.HALF_UP); // 15% flat for expats
    }
}