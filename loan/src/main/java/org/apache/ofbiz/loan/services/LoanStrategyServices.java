package org.apache.ofbiz.loan.services;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.apache.ofbiz.base.util.Debug;
import org.apache.ofbiz.base.util.UtilDateTime;
import org.apache.ofbiz.base.util.UtilMisc;
import org.apache.ofbiz.base.util.UtilValidate;
import org.apache.ofbiz.entity.Delegator;
import org.apache.ofbiz.entity.GenericValue;
import org.apache.ofbiz.entity.condition.EntityCondition;
import org.apache.ofbiz.entity.condition.EntityOperator;
import org.apache.ofbiz.entity.util.EntityQuery;
import org.apache.ofbiz.service.DispatchContext;
import org.apache.ofbiz.service.ServiceUtil;

public final class LoanStrategyServices {
    public static final String MODULE = LoanStrategyServices.class.getName();

    private static final String SCH_PENDING = "LNS_PENDING";
    private static final String SCH_DUE = "LNS_DUE";
    private static final String SCH_PARTIAL = "LNS_PARTIAL";
    private static final String SCH_OVERDUE = "LNS_OVERDUE";

    private LoanStrategyServices() {}

    public static Map<String, Object> previewPartyLoanPayrollAllocationPlan(DispatchContext dctx, Map<String, ?> context) {
        Delegator delegator = dctx.getDelegator();

        try {
            String partyId = (String) context.get("partyId");
            String organizationPartyId = (String) context.get("organizationPartyId");
            String payrollPeriodId = (String) context.get("payrollPeriodId");
            BigDecimal netAvailableForLoans = (BigDecimal) context.get("netAvailableForLoans");

            if (UtilValidate.isEmpty(partyId) || UtilValidate.isEmpty(organizationPartyId) || UtilValidate.isEmpty(payrollPeriodId)) {
                return ServiceUtil.returnError("partyId, organizationPartyId, payrollPeriodId are required");
            }
            if (netAvailableForLoans == null) {
                netAvailableForLoans = BigDecimal.ZERO;
            }

            List<GenericValue> dueRows = EntityQuery.use(delegator)
                    .from("PartyLoanDueInstallmentView")
                    .where(EntityCondition.makeCondition(EntityOperator.AND,
                            EntityCondition.makeCondition("loanPartyId", partyId),
                            EntityCondition.makeCondition("loanOrganizationPartyId", organizationPartyId),
                            EntityCondition.makeCondition("scheduleDuePayrollPeriodId", payrollPeriodId),
                            EntityCondition.makeCondition("loanPayrollEligibleFlag", "Y"),
                            EntityCondition.makeCondition("loanLoanStatusId", EntityOperator.IN,
                                    java.util.Arrays.asList("LN_ACTIVE", "LN_PARTIAL")),
                            EntityCondition.makeCondition("scheduleScheduleStatusId", EntityOperator.IN,
                                    java.util.Arrays.asList(SCH_PENDING, SCH_DUE, SCH_PARTIAL, SCH_OVERDUE))
                    ))
                    .orderBy("loanAllocationOrderEnumId",
                             "scheduleDuePayrollPeriodId",
                             "scheduleDueDate",
                             "scheduleInstallmentSeqNum",
                             "loanLoanId",
                             "scheduleLoanScheduleId")
                    .queryList();

            List<Map<String, Object>> plannedRows = new ArrayList<Map<String, Object>>();
            BigDecimal totalDue = BigDecimal.ZERO;
            BigDecimal totalPlannedApply = BigDecimal.ZERO;
            BigDecimal remaining = money(netAvailableForLoans);
            int skippedInstallmentCount = 0;
            int partialInstallmentCount = 0;

            Timestamp nowTs = UtilDateTime.nowTimestamp();

            for (GenericValue row : dueRows) {
                BigDecimal scheduleOutstanding = money(firstNonNull(row.getBigDecimal("scheduleOutstandingAmount"),
                        row.getBigDecimal("scheduleInstallmentAmount"), BigDecimal.ZERO));
                if (scheduleOutstanding.compareTo(BigDecimal.ZERO) <= 0) {
                    continue;
                }

                totalDue = totalDue.add(scheduleOutstanding);

                String loanId = row.getString("loanLoanId");
                GenericValue loan = EntityQuery.use(delegator)
                        .from("PartyLoan")
                        .where("loanId", loanId)
                        .queryOne();

                String allowPartialPaymentFlag = normalizeFlag(loan != null ? loan.getString("allowPartialPaymentFlag") : "N");
                BigDecimal loanOutstanding = money(loan != null ? loan.getBigDecimal("outstandingAmount") : scheduleOutstanding);
                BigDecimal maxAllowed = min(scheduleOutstanding, loanOutstanding);

                BigDecimal recommendedApplyAmount = BigDecimal.ZERO;
                String reasonCode;
                if (remaining.compareTo(BigDecimal.ZERO) <= 0) {
                    reasonCode = "INSUFFICIENT_NET";
                    skippedInstallmentCount++;
                } else if (remaining.compareTo(maxAllowed) >= 0) {
                    recommendedApplyAmount = maxAllowed;
                    reasonCode = "FULL";
                } else if ("Y".equals(allowPartialPaymentFlag)) {
                    recommendedApplyAmount = remaining;
                    reasonCode = "PARTIAL";
                    partialInstallmentCount++;
                } else {
                    reasonCode = "SKIPPED_PARTIAL_NOT_ALLOWED";
                    skippedInstallmentCount++;
                }

                Map<String, Object> plan = new HashMap<String, Object>();
                plan.put("loanId", loanId);
                plan.put("loanScheduleId", row.getString("scheduleLoanScheduleId"));
                plan.put("duePayrollPeriodId", row.getString("scheduleDuePayrollPeriodId"));
                plan.put("dueDate", row.getTimestamp("scheduleDueDate"));
                plan.put("installmentSeqNum", row.getLong("scheduleInstallmentSeqNum"));
                plan.put("scheduleStatusId", row.getString("scheduleScheduleStatusId"));
                plan.put("allocationOrderEnumId", row.getString("loanAllocationOrderEnumId"));
                plan.put("repaymentChannelEnumId", row.getString("loanRepaymentChannelEnumId"));
                plan.put("allowPartialPaymentFlag", allowPartialPaymentFlag);
                plan.put("scheduleOutstandingAmount", scheduleOutstanding);
                plan.put("loanOutstandingAmount", loanOutstanding);
                plan.put("recommendedApplyAmount", money(recommendedApplyAmount));
                plan.put("reasonCode", reasonCode);
                plan.put("overdueFlag", isOverdue(row.getTimestamp("scheduleDueDate"), nowTs) ? "Y" : "N");
                plannedRows.add(plan);

                totalPlannedApply = totalPlannedApply.add(money(recommendedApplyAmount));
                remaining = money(remaining.subtract(recommendedApplyAmount));
            }

            Map<String, Object> data = new HashMap<String, Object>();
            data.put("partyId", partyId);
            data.put("organizationPartyId", organizationPartyId);
            data.put("payrollPeriodId", payrollPeriodId);
            data.put("netAvailableForLoans", money(netAvailableForLoans));
            data.put("plannedRows", plannedRows);
            data.put("plannedCount", Integer.valueOf(plannedRows.size()));
            data.put("totalDue", money(totalDue));
            data.put("totalPlannedApply", money(totalPlannedApply));
            data.put("totalShortfall", money(totalDue.subtract(totalPlannedApply).max(BigDecimal.ZERO)));
            data.put("remainingNetAfterPlan", money(remaining));
            data.put("skippedInstallmentCount", Integer.valueOf(skippedInstallmentCount));
            data.put("partialInstallmentCount", Integer.valueOf(partialInstallmentCount));

            Map<String, Object> out = ServiceUtil.returnSuccess("Loan payroll allocation plan prepared successfully");
            out.putAll(data);
            out.put("data", new HashMap<String, Object>(data));
            out.put("legacyData", new HashMap<String, Object>(data));
            return out;

        } catch (Exception e) {
            Debug.logError(e, "Error previewing payroll allocation plan", MODULE);
            Map<String, Object> out = ServiceUtil.returnError(e.getMessage());
            out.put("data", new HashMap<String, Object>());
            out.put("legacyData", new HashMap<String, Object>());
            return out;
        }
    }

    public static Map<String, Object> reviewMissedPartyLoanSchedules(DispatchContext dctx, Map<String, ?> context) {
        Delegator delegator = dctx.getDelegator();

        try {
            String organizationPartyId = (String) context.get("organizationPartyId");
            String payrollPeriodId = (String) context.get("payrollPeriodId");

            if (UtilValidate.isEmpty(organizationPartyId) || UtilValidate.isEmpty(payrollPeriodId)) {
                return ServiceUtil.returnError("organizationPartyId and payrollPeriodId are required");
            }

            Timestamp nowTs = UtilDateTime.nowTimestamp();

            List<GenericValue> rows = EntityQuery.use(delegator)
                    .from("PartyLoanDueInstallmentView")
                    .where(EntityCondition.makeCondition(EntityOperator.AND,
                            EntityCondition.makeCondition("loanOrganizationPartyId", organizationPartyId),
                            EntityCondition.makeCondition("scheduleDuePayrollPeriodId", payrollPeriodId),
                            EntityCondition.makeCondition("loanLoanStatusId", EntityOperator.IN,
                                    java.util.Arrays.asList("LN_ACTIVE", "LN_PARTIAL")),
                            EntityCondition.makeCondition("scheduleScheduleStatusId", EntityOperator.IN,
                                    java.util.Arrays.asList(SCH_PENDING, SCH_DUE, SCH_PARTIAL))
                    ))
                    .orderBy("scheduleDueDate", "scheduleInstallmentSeqNum", "loanLoanId", "scheduleLoanScheduleId")
                    .queryList();

            int reviewedCount = 0;
            int markedOverdueCount = 0;

            for (GenericValue row : rows) {
                reviewedCount++;
                String scheduleId = row.getString("scheduleLoanScheduleId");

                GenericValue schedule = EntityQuery.use(delegator)
                        .from("PartyLoanSchedule")
                        .where("loanScheduleId", scheduleId)
                        .queryOne();
                if (schedule == null) {
                    continue;
                }

                BigDecimal outstanding = money(firstNonNull(schedule.getBigDecimal("outstandingAmount"), BigDecimal.ZERO, BigDecimal.ZERO));
                Timestamp dueDate = firstNonNull(schedule.getTimestamp("dueDate"), row.getTimestamp("scheduleDueDate"), null);
                String statusId = schedule.getString("scheduleStatusId");

                if (outstanding.compareTo(BigDecimal.ZERO) > 0
                        && dueDate != null
                        && !dueDate.after(nowTs)
                        && !SCH_OVERDUE.equals(statusId)
                        && !"LNS_PAID".equals(statusId)) {
                    schedule.set("scheduleStatusId", SCH_OVERDUE);
                    schedule.store();
                    markedOverdueCount++;
                }
            }

            Map<String, Object> data = UtilMisc.toMap(
                    "organizationPartyId", organizationPartyId,
                    "payrollPeriodId", payrollPeriodId,
                    "reviewedCount", Integer.valueOf(reviewedCount),
                    "markedOverdueCount", Integer.valueOf(markedOverdueCount)
            );

            Map<String, Object> out = ServiceUtil.returnSuccess("Missed loan schedules reviewed successfully");
            out.putAll(data);
            out.put("data", new HashMap<String, Object>(data));
            out.put("legacyData", new HashMap<String, Object>(data));
            return out;

        } catch (Exception e) {
            Debug.logError(e, "Error reviewing missed loan schedules", MODULE);
            Map<String, Object> out = ServiceUtil.returnError(e.getMessage());
            out.put("data", new HashMap<String, Object>());
            out.put("legacyData", new HashMap<String, Object>());
            return out;
        }
    }

    private static String normalizeFlag(String value) {
        return "Y".equalsIgnoreCase(value) ? "Y" : "N";
    }

    private static boolean isOverdue(Timestamp dueDate, Timestamp nowTs) {
        return dueDate != null && nowTs != null && !dueDate.after(nowTs);
    }

    private static BigDecimal money(BigDecimal value) {
        if (value == null) return BigDecimal.ZERO.setScale(2, java.math.RoundingMode.HALF_UP);
        return value.setScale(2, java.math.RoundingMode.HALF_UP);
    }

    private static BigDecimal min(BigDecimal a, BigDecimal b) {
        if (a == null) return b == null ? BigDecimal.ZERO : b;
        if (b == null) return a;
        return a.compareTo(b) <= 0 ? a : b;
    }

    private static <T> T firstNonNull(T a, T b, T c) {
        return a != null ? a : (b != null ? b : c);
    }
}
