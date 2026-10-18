package org.apache.ofbiz.loan.services;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.sql.Timestamp;
import java.util.Calendar;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.apache.ofbiz.base.util.Debug;
import org.apache.ofbiz.base.util.UtilDateTime;
import org.apache.ofbiz.base.util.UtilValidate;
import org.apache.ofbiz.entity.Delegator;
import org.apache.ofbiz.entity.GenericValue;
import org.apache.ofbiz.entity.util.EntityQuery;
import org.apache.ofbiz.service.DispatchContext;
import org.apache.ofbiz.service.ServiceUtil;

public final class LoanScheduleServices {
    public static final String MODULE = LoanScheduleServices.class.getName();
    public static final String SCH_PENDING = "LNS_PENDING";
    public static final String SCH_RESCHEDULED = "LNS_RESCH";

private static final String SCH_DUE = "LNS_DUE";
private static final String SCH_PAID = "LNS_PAID";
private static final String SCH_PARTIAL = "LNS_PARTIAL";


    private LoanScheduleServices() {}

    public static Map<String, Object> generatePartyLoanSchedule(DispatchContext dctx, Map<String, ?> context) {
        Delegator delegator = dctx.getDelegator();
        GenericValue userLogin = (GenericValue) context.get("userLogin");

        try {
            String loanId = (String) context.get("loanId");
            String forceRebuild = (String) context.get("forceRebuild");

            if (UtilValidate.isEmpty(loanId)) {
                return ServiceUtil.returnError("loanId is required");
            }

            GenericValue loan = EntityQuery.use(delegator)
                    .from("PartyLoan")
                    .where("loanId", loanId)
                    .queryOne();
            if (loan == null) {
                return ServiceUtil.returnError("Loan not found: " + loanId);
            }

            List<GenericValue> existing = EntityQuery.use(delegator)
                    .from("PartyLoanSchedule")
                    .where("loanId", loanId)
                    .orderBy("installmentSeqNum")
                    .queryList();

            long existingScheduleCount = UtilValidate.isEmpty(existing) ? 0L : existing.size();
            if (existingScheduleCount > 0 && !"Y".equalsIgnoreCase(forceRebuild)) {
                Map<String, Object> data = new HashMap<String, Object>();
                data.put("loanId", loanId);
                data.put("generatedCount", Long.valueOf(0L));
                data.put("generatedScheduleCount", Long.valueOf(0L));
                data.put("existingScheduleCount", Long.valueOf(existingScheduleCount));
                data.put("forceRebuild", forceRebuild);
                data.put("installmentCount", loan.getLong("installmentCount"));
                data.put("totalRepayableAmount", loan.getBigDecimal("totalRepayableAmount"));
                return buildStandardSuccess("Loan schedule already exists", data);
            }

            if (existingScheduleCount > 0) {
                delegator.removeAll(existing);
            }

            BigDecimal totalRepayableAmount = loan.getBigDecimal("totalRepayableAmount");
            if (totalRepayableAmount == null || totalRepayableAmount.compareTo(BigDecimal.ZERO) <= 0) {
                return ServiceUtil.returnError("Loan totalRepayableAmount must be greater than zero");
            }

            Long installmentCountObj = loan.getLong("installmentCount");
            if (installmentCountObj == null || installmentCountObj.longValue() <= 0) {
                return ServiceUtil.returnError("Loan installmentCount must be greater than zero");
            }
            long installmentCount = installmentCountObj.longValue();

            BigDecimal principalTotal = loan.getBigDecimal("principalAmount");
            if (principalTotal == null) {
                principalTotal = BigDecimal.ZERO;
            }

            BigDecimal interestTotal = loan.getBigDecimal("interestAmount");
            if (interestTotal == null) {
                interestTotal = BigDecimal.ZERO;
            }

            BigDecimal feeTotal = loan.getBigDecimal("feeAmount");
            if (feeTotal == null) {
                feeTotal = BigDecimal.ZERO;
            }

            BigDecimal flatInstallment = totalRepayableAmount.divide(
                    BigDecimal.valueOf(installmentCount), 2, RoundingMode.HALF_UP);

            BigDecimal principalSplit = principalTotal.divide(
                    BigDecimal.valueOf(installmentCount), 2, RoundingMode.HALF_UP);
            BigDecimal interestSplit = interestTotal.divide(
                    BigDecimal.valueOf(installmentCount), 2, RoundingMode.HALF_UP);
            BigDecimal feeSplit = feeTotal.divide(
                    BigDecimal.valueOf(installmentCount), 2, RoundingMode.HALF_UP);

            Timestamp dueDate = loan.getTimestamp("firstDueDate");
            if (dueDate == null) {
                dueDate = UtilDateTime.nowTimestamp();
            }

            String organizationPartyId = loan.getString("organizationPartyId");

            BigDecimal remaining = totalRepayableAmount;
            long generated = 0L;

            for (int i = 1; i <= installmentCount; i++) {
                BigDecimal installmentAmount = (i == installmentCount) ? remaining : flatInstallment;

                BigDecimal principalAmount = (i == installmentCount)
                        ? totalComponentRemainder(principalTotal, principalSplit, installmentCount)
                        : principalSplit;

                BigDecimal interestAmount = (i == installmentCount)
                        ? totalComponentRemainder(interestTotal, interestSplit, installmentCount)
                        : interestSplit;

                BigDecimal feeAmount = (i == installmentCount)
                        ? totalComponentRemainder(feeTotal, feeSplit, installmentCount)
                        : feeSplit;

                String scheduleId = delegator.getNextSeqId("PartyLoanSchedule");
                GenericValue row = delegator.makeValue("PartyLoanSchedule");
                row.set("loanScheduleId", scheduleId);
                row.set("loanId", loanId);
                row.set("installmentSeqNum", Long.valueOf(i));
                row.set("duePayrollPeriodId", resolvePayrollPeriodIdForDueDate(delegator, organizationPartyId, dueDate));
                row.set("dueDate", dueDate);
                row.set("originalDueDate", dueDate);
                row.set("principalAmount", principalAmount);
                row.set("interestAmount", interestAmount);
                row.set("feeAmount", feeAmount);
                row.set("penaltyAmount", BigDecimal.ZERO);
                row.set("installmentAmount", installmentAmount);
                row.set("paidAmount", BigDecimal.ZERO);
                row.set("outstandingAmount", installmentAmount);
                row.set("openingBalance", remaining);

                remaining = remaining.subtract(installmentAmount);
                if (remaining.compareTo(BigDecimal.ZERO) < 0) {
                    remaining = BigDecimal.ZERO;
                }

                row.set("closingBalance", remaining);
                row.set("scheduleStatusId", SCH_PENDING);
                delegator.create(row);
                generated++;

                dueDate = addMonths(dueDate, 1);
            }

            LoanRepaymentServices.createLoanEvent(
                    delegator,
                    loanId,
                    null,
                    null,
                    "LNE_SCH_GEN",
                    UtilDateTime.nowTimestamp(),
                    userLogin != null ? userLogin.getString("userLoginId") : null,
                    "Loan schedule generated",
                    null
            );

            Map<String, Object> data = new HashMap<String, Object>();
            data.put("loanId", loanId);
            data.put("generatedCount", Long.valueOf(generated));
            data.put("generatedScheduleCount", Long.valueOf(generated));
            data.put("existingScheduleCount", Long.valueOf(existingScheduleCount));
            data.put("forceRebuild", forceRebuild);
            data.put("installmentCount", Long.valueOf(installmentCount));
            data.put("totalRepayableAmount", totalRepayableAmount);

            return buildStandardSuccess("Loan schedule generated successfully", data);

        } catch (Exception e) {
            Debug.logError(e, "Error generating schedule", MODULE);
            return ServiceUtil.returnError(e.getMessage());
        }
    }

    public static Map<String, Object> reschedulePartyLoanInstallment(DispatchContext dctx, Map<String, ?> context) {
        Delegator delegator = dctx.getDelegator();
        GenericValue userLogin = (GenericValue) context.get("userLogin");

        try {
            String loanScheduleId = (String) context.get("loanScheduleId");
            Timestamp newDueDate = (Timestamp) context.get("newDueDate");
            String changeReasonEnumId = (String) context.get("changeReasonEnumId");
            String remarks = (String) context.get("remarks");

            if (UtilValidate.isEmpty(loanScheduleId)) {
                return ServiceUtil.returnError("loanScheduleId is required");
            }
            if (newDueDate == null) {
                return ServiceUtil.returnError("newDueDate is required");
            }

            GenericValue row = EntityQuery.use(delegator)
                    .from("PartyLoanSchedule")
                    .where("loanScheduleId", loanScheduleId)
                    .queryOne();
            if (row == null) {
                return ServiceUtil.returnError("Schedule row not found: " + loanScheduleId);
            }

            Timestamp oldDueDate = row.getTimestamp("dueDate");
            String loanId = row.getString("loanId");

            BigDecimal paidAmount = row.getBigDecimal("paidAmount");
            if (paidAmount == null) {
                paidAmount = BigDecimal.ZERO;
            }
            if (paidAmount.compareTo(BigDecimal.ZERO) > 0) {
                return ServiceUtil.returnError("Cannot reschedule a loan installment that already has repayment activity: " + loanScheduleId);
            }
            if (UtilValidate.isNotEmpty(row.getString("processedPayrollRunId"))
                    || UtilValidate.isNotEmpty(row.getString("processedPayrollResultId"))) {
                return ServiceUtil.returnError("Cannot reschedule a loan installment already linked to payroll processing: " + loanScheduleId);
            }

            if (row.getTimestamp("originalDueDate") == null && oldDueDate != null) {
                row.set("originalDueDate", oldDueDate);
            }

            row.set("dueDate", newDueDate);
            row.set("duePayrollPeriodId", resolvePayrollPeriodIdFromLoan(delegator, loanId, newDueDate));
            row.set("scheduleStatusId", newDueDate.after(UtilDateTime.nowTimestamp()) ? SCH_PENDING : SCH_DUE);
            row.set("changeReasonEnumId", changeReasonEnumId);
            row.store();

            LoanRepaymentServices.createLoanEvent(
                    delegator,
                    row.getString("loanId"),
                    loanScheduleId,
                    null,
                    "LNE_RESCHEDULE",
                    UtilDateTime.nowTimestamp(),
                    userLogin != null ? userLogin.getString("userLoginId") : null,
                    remarks,
                    null
            );

            Map<String, Object> data = new HashMap<String, Object>();
            data.put("loanId", row.getString("loanId"));
            data.put("loanScheduleId", loanScheduleId);
            data.put("oldDueDate", oldDueDate);
            data.put("newDueDate", newDueDate);
            data.put("duePayrollPeriodId", row.getString("duePayrollPeriodId"));
            data.put("scheduleStatusId", row.getString("scheduleStatusId"));
            data.put("changeReasonEnumId", changeReasonEnumId);

            return buildStandardSuccess("Loan installment rescheduled successfully", data);

        } catch (Exception e) {
            Debug.logError(e, "Error rescheduling installment", MODULE);
            return ServiceUtil.returnError(e.getMessage());
        }
    }


  private static String resolvePayrollPeriodIdFromLoan(Delegator delegator, String loanId, Timestamp dueDate) {
    try {
        if (UtilValidate.isEmpty(loanId) || dueDate == null) {
            return null;
        }

        GenericValue loan = EntityQuery.use(delegator)
                .from("PartyLoan")
                .where("loanId", loanId)
                .queryOne();
        if (loan == null) {
            return null;
        }

        return resolvePayrollPeriodIdForDueDate(
                delegator,
                loan.getString("organizationPartyId"),
                dueDate
        );
    } catch (Exception e) {
        Debug.logWarning(e, "Unable to resolve payroll period from loanId=" + loanId, MODULE);
        return null;
    }
}

private static String resolvePayrollPeriodIdForDueDate(Delegator delegator, String organizationPartyId, Timestamp dueDate) {
    try {
        if (UtilValidate.isEmpty(organizationPartyId) || dueDate == null) {
            return null;
        }

        List<GenericValue> periods = EntityQuery.use(delegator)
                .from("PayrollPeriod")
                .where("organizationPartyId", organizationPartyId)
                .orderBy("startDate")
                .queryList();

        if (UtilValidate.isEmpty(periods)) {
            Debug.logWarning("No PayrollPeriod rows found for organizationPartyId=" + organizationPartyId, MODULE);
            return null;
        }

        for (GenericValue period : periods) {
            Timestamp start = firstTimestamp(period, "startDate", "fromDate", "periodStartDate", "fromDateTime");
            Timestamp end   = firstTimestamp(period, "endDate", "thruDate", "periodEndDate", "thruDateTime");
            Timestamp pay   = firstTimestamp(period, "payDate", "paymentDate");

            if (start != null && end != null) {
                if (!dueDate.before(start) && !dueDate.after(end)) {
                    String payrollPeriodId = period.getString("payrollPeriodId");
                    Debug.logInfo("Resolved dueDate " + dueDate + " to payrollPeriodId=" + payrollPeriodId
                            + " by range for organizationPartyId=" + organizationPartyId, MODULE);
                    return payrollPeriodId;
                }
            }

            if (start != null && isSameMonth(start, dueDate)) {
                String payrollPeriodId = period.getString("payrollPeriodId");
                Debug.logInfo("Resolved dueDate " + dueDate + " to payrollPeriodId=" + payrollPeriodId
                        + " by startDate month match for organizationPartyId=" + organizationPartyId, MODULE);
                return payrollPeriodId;
            }

            if (pay != null && isSameMonth(pay, dueDate)) {
                String payrollPeriodId = period.getString("payrollPeriodId");
                Debug.logInfo("Resolved dueDate " + dueDate + " to payrollPeriodId=" + payrollPeriodId
                        + " by payDate month match for organizationPartyId=" + organizationPartyId, MODULE);
                return payrollPeriodId;
            }
        }

        Debug.logWarning("No PayrollPeriod matched dueDate=" + dueDate
                + " for organizationPartyId=" + organizationPartyId, MODULE);
    } catch (Exception e) {
        Debug.logWarning(e, "Unable to resolve payroll period for organizationPartyId=" + organizationPartyId
                + ", dueDate=" + dueDate, MODULE);
    }

    return null;
}

private static Timestamp firstTimestamp(GenericValue value, String... fieldNames) {
    if (value == null || fieldNames == null) {
        return null;
    }

    for (String fieldName : fieldNames) {
        if (UtilValidate.isEmpty(fieldName)) {
            continue;
        }
        try {
            Object obj = value.get(fieldName);
            if (obj == null) {
                continue;
            }
            if (obj instanceof Timestamp) {
                return (Timestamp) obj;
            }
            if (obj instanceof java.sql.Date) {
                return new Timestamp(((java.sql.Date) obj).getTime());
            }
            if (obj instanceof java.util.Date) {
                return new Timestamp(((java.util.Date) obj).getTime());
            }
        } catch (Exception ignored) {
        }
    }

    return null;
}

private static boolean isSameMonth(Timestamp a, Timestamp b) {
    if (a == null || b == null) {
        return false;
    }

    java.util.Calendar ca = java.util.Calendar.getInstance();
    ca.setTimeInMillis(a.getTime());

    java.util.Calendar cb = java.util.Calendar.getInstance();
    cb.setTimeInMillis(b.getTime());

    return ca.get(java.util.Calendar.YEAR) == cb.get(java.util.Calendar.YEAR)
            && ca.get(java.util.Calendar.MONTH) == cb.get(java.util.Calendar.MONTH);
}

    private static boolean isSameYear(Timestamp a, Timestamp b) {
        Calendar ca = Calendar.getInstance();
        ca.setTimeInMillis(a.getTime());
        Calendar cb = Calendar.getInstance();
        cb.setTimeInMillis(b.getTime());
        return ca.get(Calendar.YEAR) == cb.get(Calendar.YEAR);
    }

  
    private static Map<String, Object> buildStandardSuccess(String successMessage, Map<String, Object> data) {
        Map<String, Object> out = ServiceUtil.returnSuccess(successMessage);

        Map<String, Object> safeData = new HashMap<String, Object>();
        if (data != null) {
            safeData.putAll(data);
            out.putAll(data);
        }

        out.put("data", safeData);
        out.put("legacyData", new HashMap<String, Object>(safeData));
        return out;
    }

    private static BigDecimal totalComponentRemainder(BigDecimal total, BigDecimal split, long count) {
        if (total == null) {
            return BigDecimal.ZERO;
        }
        return total.subtract(split.multiply(BigDecimal.valueOf(count - 1L)));
    }

    private static Timestamp addMonths(Timestamp ts, int months) {
        Calendar cal = Calendar.getInstance();
        cal.setTimeInMillis(ts.getTime());
        cal.add(Calendar.MONTH, months);
        return new Timestamp(cal.getTimeInMillis());
    }
}
