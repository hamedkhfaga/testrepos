package org.apache.ofbiz.payroll;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.apache.ofbiz.entity.Delegator;
import org.apache.ofbiz.entity.GenericEntityException;
import org.apache.ofbiz.entity.GenericValue;
import org.apache.ofbiz.entity.util.EntityQuery;
import org.apache.ofbiz.service.DispatchContext;
import org.apache.ofbiz.service.ServiceUtil;
import org.apache.ofbiz.base.util.Debug;

/**
 * Loan Management Service - Handles employee loans and deductions
 */
public class LoanManagementService {

    public static final String MODULE = LoanManagementService.class.getName();

    /**
     * Calculate loan deductions for payroll
     */
    public static Map<String, Object> calculateLoanDeductions(DispatchContext dctx, Map<String, ?> context) {
        Delegator delegator = dctx.getDelegator();
        Map<String, Object> result = ServiceUtil.returnSuccess();

        try {
            String partyId = (String) context.get("partyId");
            BigDecimal totalLoanDeductions = BigDecimal.ZERO;
            List<Map<String, Object>> activeLoans = new ArrayList<>();

            // Get all active loans for employee
            List<GenericValue> employeeLoans = EntityQuery.use(delegator)
                    .from("EmployeeLoan")
                    .where("partyId", partyId, "statusId", "ACTIVE")
                    .queryList();

            for (GenericValue loan : employeeLoans) {
                BigDecimal monthlyInstallment = loan.getBigDecimal("monthlyInstallment");
                if (monthlyInstallment != null) {
                    totalLoanDeductions = totalLoanDeductions.add(monthlyInstallment);

                    Map<String, Object> loanInfo = new HashMap<>();
                    loanInfo.put("loanId", loan.getString("loanId"));
                    loanInfo.put("loanType", loan.getString("loanType"));
                    loanInfo.put("monthlyInstallment", monthlyInstallment.setScale(2, RoundingMode.HALF_UP));
                    loanInfo.put("remainingInstallments", loan.getLong("remainingInstallments"));
                    activeLoans.add(loanInfo);

                    // Update remaining installments
                    Long remaining = loan.getLong("remainingInstallments");
                    if (remaining != null && remaining > 1) {
                        loan.set("remainingInstallments", remaining - 1);
                        delegator.store(loan);
                    } else {
                        loan.set("statusId", "COMPLETED");
                        loan.set("endDate", new java.sql.Timestamp(System.currentTimeMillis()));
                        delegator.store(loan);
                    }
                }
            }

            result.put("totalLoanDeductions", totalLoanDeductions.setScale(2, RoundingMode.HALF_UP));
            result.put("activeLoans", activeLoans);
            result.put("loanCount", activeLoans.size());

        } catch (Exception e) {
            Debug.logError(e, "Error calculating loan deductions", MODULE);
            return ServiceUtil.returnError("Loan deduction calculation failed: " + e.getMessage());
        }

        return result;
    }

    /**
     * Create new employee loan
     */
    public static Map<String, Object> createEmployeeLoan(DispatchContext dctx, Map<String, ?> context) {
        Delegator delegator = dctx.getDelegator();
        Map<String, Object> result = ServiceUtil.returnSuccess();

        try {
            String loanId = delegator.getNextSeqId("EmployeeLoan");
            String partyId = (String) context.get("partyId");
            String loanType = (String) context.get("loanType");
            BigDecimal loanAmount = (BigDecimal) context.get("loanAmount");
            BigDecimal interestRate = (BigDecimal) context.get("interestRate");
            Long totalInstallments = (Long) context.get("totalInstallments");

            GenericValue userLogin = (GenericValue) context.get("userLogin");
            String approvedBy = userLogin != null ? userLogin.getString("userLoginId") : "system";

            // Validate required fields
            if (loanAmount == null || totalInstallments == null) {
                return ServiceUtil.returnError("Loan amount and total installments are required");
            }

            if (interestRate == null) {
                interestRate = BigDecimal.ZERO;
            }

            // Calculate monthly installment
            BigDecimal monthlyInstallment = calculateMonthlyInstallment(
                    loanAmount, interestRate, totalInstallments);

            GenericValue loan = delegator.makeValue("EmployeeLoan");
            loan.set("loanId", loanId);
            loan.set("partyId", partyId);
            loan.set("loanType", loanType);
            loan.set("loanAmount", loanAmount.setScale(2, RoundingMode.HALF_UP));
            loan.set("interestRate", interestRate.setScale(4, RoundingMode.HALF_UP));
            loan.set("totalInstallments", totalInstallments);
            loan.set("remainingInstallments", totalInstallments);
            loan.set("monthlyInstallment", monthlyInstallment);
            loan.set("startDate", new java.sql.Timestamp(System.currentTimeMillis()));
            loan.set("statusId", "APPROVED");
            loan.set("approvedBy", approvedBy);
            loan.set("approvedDate", new java.sql.Timestamp(System.currentTimeMillis()));

            delegator.create(loan);

            // Create installment schedule
            createLoanInstallmentSchedule(
                    delegator, loanId, monthlyInstallment, totalInstallments);

            result.put("loanId", loanId);
            result.put("monthlyInstallment", monthlyInstallment);
            result.put("statusId", "APPROVED");

            Debug.logInfo("Created new employee loan: " + loanId + " for employee: " + partyId, MODULE);

        } catch (Exception e) {
            Debug.logError(e, "Error creating employee loan", MODULE);
            return ServiceUtil.returnError("Loan creation failed: " + e.getMessage());
        }

        return result;
    }

    /**
     * Calculate monthly loan installment
     */
    private static BigDecimal calculateMonthlyInstallment(BigDecimal loanAmount,
                                                           BigDecimal interestRate,
                                                           Long totalInstallments) {
        if (interestRate == null || interestRate.compareTo(BigDecimal.ZERO) == 0) {
            // Simple division for no interest
            return loanAmount.divide(new BigDecimal(totalInstallments), 2, RoundingMode.HALF_UP);
        }

        // EMI calculation: P * r * (1+r)^n / ((1+r)^n - 1)
        BigDecimal monthlyRate = interestRate.divide(new BigDecimal("1200"), 10, RoundingMode.HALF_UP);
        BigDecimal power = BigDecimal.ONE.add(monthlyRate).pow(totalInstallments.intValue());

        BigDecimal emi = loanAmount
                .multiply(monthlyRate)
                .multiply(power)
                .divide(power.subtract(BigDecimal.ONE), 2, RoundingMode.HALF_UP);

        return emi;
    }

    /**
     * Create loan installment schedule
     */
    private static void createLoanInstallmentSchedule(Delegator delegator,
                                                      String loanId,
                                                      BigDecimal monthlyInstallment,
                                                      Long totalInstallments) throws GenericEntityException {
        Calendar cal = Calendar.getInstance();

        for (int i = 1; i <= totalInstallments; i++) {
            String installmentId = delegator.getNextSeqId("LoanInstallment");
            cal.add(Calendar.MONTH, 1);

            GenericValue installment = delegator.makeValue("LoanInstallment");
            installment.set("installmentId", installmentId);
            installment.set("loanId", loanId);
            installment.set("installmentNumber", (long) i);
            installment.set("dueDate", new java.sql.Timestamp(cal.getTimeInMillis()));
            installment.set("installmentAmount", monthlyInstallment.setScale(2, RoundingMode.HALF_UP));
            installment.set("statusId", "PENDING");

            delegator.create(installment);
        }
    }
}
