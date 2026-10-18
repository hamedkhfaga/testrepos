package org.apache.ofbiz.loan.services;

import org.apache.ofbiz.base.util.Debug;
import org.apache.ofbiz.base.util.UtilDateTime;
import org.apache.ofbiz.base.util.UtilMisc;
import org.apache.ofbiz.base.util.UtilValidate;
import org.apache.ofbiz.entity.Delegator;
import org.apache.ofbiz.entity.GenericEntityException;
import org.apache.ofbiz.entity.GenericValue;
import org.apache.ofbiz.entity.condition.EntityCondition;
import org.apache.ofbiz.entity.condition.EntityOperator;
import org.apache.ofbiz.entity.util.EntityQuery;
import org.apache.ofbiz.service.DispatchContext;
import org.apache.ofbiz.service.ServiceUtil;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

public final class LoanDashboardServices {

    public static final String MODULE = LoanDashboardServices.class.getName();

    private static final Set<String> PENDING_APPROVAL_STATUSES = setOf(
            "LN_DRAFT",
            "LN_SUBM",
            "LN_SUBMITTED",
            "LN_PENDING",
            "LN_PENDING_APPROVAL",
            "LN_REVIEW"
    );

    private static final Set<String> ACTIVE_PORTFOLIO_STATUSES = setOf(
            "LN_APPROVED",
            "LN_ACTIVE",
            "LN_PARTIAL"
    );

    private static final Set<String> SCHEDULE_OPEN_STATUSES = setOf(
            "LNS_PENDING",
            "LNS_PARTIAL",
            "LNS_OVERDUE",
            "LNS_DUE"
    );

    private LoanDashboardServices() {
    }

    public static Map<String, Object> getLoanDashboardV1(DispatchContext dctx, Map<String, ? extends Object> context) {
        GenericValue userLogin = (GenericValue) context.get("userLogin");
        String organizationPartyId = (String) context.get("organizationPartyId");
        Timestamp asOfDate = (Timestamp) context.get("asOfDate");

        if (UtilValidate.isEmpty(userLogin)) {
            return ServiceUtil.returnError("userLogin is required.");
        }
        if (UtilValidate.isEmpty(organizationPartyId)) {
            return ServiceUtil.returnError("organizationPartyId is required.");
        }
        if (asOfDate == null) {
            asOfDate = UtilDateTime.nowTimestamp();
        }

        try {
            Delegator delegator = dctx.getDelegator();

            List<GenericValue> loans = findLoansForOrganization(delegator, organizationPartyId);
            List<GenericValue> schedules = findSchedulesForOrganization(delegator, loans);

            LoanKpiSnapshot snapshot = buildRealSnapshot(loans, schedules, asOfDate);

            List<Map<String, Object>> kpis = buildKpis(snapshot);
            List<Map<String, Object>> monthlyTrend = buildMonthlyTrend();
            List<Map<String, Object>> statusSplit = buildStatusSplit(snapshot);
            List<Map<String, Object>> flowMetrics = buildFlowMetrics(snapshot);
            List<Map<String, Object>> workQueue = buildWorkQueue();

            Map<String, Object> result = ServiceUtil.returnSuccess("Loan dashboard loaded successfully");
            result.put("kpis", kpis);
            result.put("monthlyTrend", monthlyTrend);
            result.put("statusSplit", statusSplit);
            result.put("flowMetrics", flowMetrics);
            result.put("workQueue", workQueue);
            result.put("organizationPartyId", organizationPartyId);
            result.put("asOfDate", asOfDate);
            result.put("dashboardSource", "real-kpi-status-v1");

            return result;
        } catch (Exception e) {
            Debug.logError(e, "Error loading loan dashboard", MODULE);
            return ServiceUtil.returnError("Failed to load loan dashboard: " + e.getMessage());
        }
    }

    private static List<GenericValue> findLoansForOrganization(Delegator delegator, String organizationPartyId)
            throws GenericEntityException {
        return EntityQuery.use(delegator)
                .from("PartyLoan")
                .where("organizationPartyId", organizationPartyId)
                .queryList();
    }

    private static List<GenericValue> findSchedulesForOrganization(Delegator delegator,
                                                                   List<GenericValue> loans)
            throws GenericEntityException {
        List<String> loanIds = new ArrayList<>();

        for (GenericValue loan : loans) {
            String loanId = getStringAny(loan, "loanId", "LOAN_ID");
            if (UtilValidate.isNotEmpty(loanId)) {
                loanIds.add(loanId);
            }
        }

        if (loanIds.isEmpty()) {
            return new ArrayList<>();
        }

        return EntityQuery.use(delegator)
                .from("PartyLoanSchedule")
                .where(EntityCondition.makeCondition("loanId", EntityOperator.IN, loanIds))
                .queryList();
    }

    private static LoanKpiSnapshot buildRealSnapshot(List<GenericValue> loans,
                                                     List<GenericValue> schedules,
                                                     Timestamp asOfDate) {
        LoanKpiSnapshot s = new LoanKpiSnapshot();

        s.totalLoans = loans.size();
        s.repaymentsToday = BigDecimal.ZERO;
        s.exceptions = 0;

        for (GenericValue loan : loans) {
            String statusId = upper(getStringAny(loan, "statusId", "loanStatusId", "STATUS_ID", "LOAN_STATUS_ID"));
            BigDecimal outstandingAmount = getBigDecimalAny(
                    loan,
                    "outstandingAmount",
                    "OUTSTANDING_AMOUNT",
                    "outstandingBalance",
                    "OUTSTANDING_BALANCE"
            );

            if (PENDING_APPROVAL_STATUSES.contains(statusId)) {
                s.pendingApprovals++;
            }

            if (ACTIVE_PORTFOLIO_STATUSES.contains(statusId)) {
                s.activePortfolio = s.activePortfolio.add(nvl(outstandingAmount));
            }

            s.incrementStatus(statusLabel(statusId));
        }

        for (GenericValue schedule : schedules) {
            String scheduleStatusId = upper(getStringAny(
                    schedule,
                    "scheduleStatusId",
                    "statusId",
                    "SCHEDULE_STATUS_ID",
                    "STATUS_ID"
            ));
            BigDecimal outstandingAmount = getBigDecimalAny(
                    schedule,
                    "outstandingAmount",
                    "OUTSTANDING_AMOUNT",
                    "remainingAmount",
                    "REMAINING_AMOUNT"
            );
            Timestamp dueDate = getTimestampAny(schedule, "dueDate", "DUE_DATE");

            boolean openStatus = SCHEDULE_OPEN_STATUSES.contains(scheduleStatusId);
            boolean hasOutstanding = nvl(outstandingAmount).compareTo(BigDecimal.ZERO) > 0;
            boolean overdue = dueDate != null && dueDate.before(asOfDate);

            if (openStatus && hasOutstanding && overdue) {
                s.overdueSchedules++;
            }
        }

        return s;
    }

    private static List<Map<String, Object>> buildKpis(LoanKpiSnapshot s) {
        List<Map<String, Object>> rows = new ArrayList<>();

        rows.add(kpi("Total Loans", String.valueOf(s.totalLoans), "live", "good"));
        rows.add(kpi("Active Portfolio", money(s.activePortfolio), "live", "good"));
        rows.add(kpi("Pending Approvals", String.valueOf(s.pendingApprovals), "live", "neutral"));
        rows.add(kpi("Overdue Schedules", String.valueOf(s.overdueSchedules), "live", "warn"));
        rows.add(kpi("Repayments Today", money(s.repaymentsToday), "starter", "neutral"));
        rows.add(kpi("Exceptions", String.valueOf(s.exceptions), "starter", "neutral"));

        return rows;
    }

    private static List<Map<String, Object>> buildStatusSplit(LoanKpiSnapshot s) {
        List<Map<String, Object>> rows = new ArrayList<>();
        int total = s.totalLoans <= 0 ? 1 : s.totalLoans;

        rows.add(statusRow("Draft", s.statusDraft, percent(s.statusDraft, total)));
        rows.add(statusRow("Approved", s.statusApproved, percent(s.statusApproved, total)));
        rows.add(statusRow("Active", s.statusActive, percent(s.statusActive, total)));
        rows.add(statusRow("Partial", s.statusPartial, percent(s.statusPartial, total)));
        rows.add(statusRow("Closed", s.statusClosed, percent(s.statusClosed, total)));

        return rows;
    }

    private static List<Map<String, Object>> buildFlowMetrics(LoanKpiSnapshot s) {
        List<Map<String, Object>> rows = new ArrayList<>();

        rows.add(flowMetric("Approvals", s.pendingApprovals, Math.max(s.pendingApprovals, 10)));
        rows.add(flowMetric("Disbursements", s.statusApproved, Math.max(s.statusApproved, 10)));
        rows.add(flowMetric("Repayments", 0, 10));
        rows.add(flowMetric("Reconciliation", 0, 10));

        return rows;
    }

    private static List<Map<String, Object>> buildMonthlyTrend() {
        List<Map<String, Object>> rows = new ArrayList<>();

        rows.add(monthPoint("Jan", 8));
        rows.add(monthPoint("Feb", 10));
        rows.add(monthPoint("Mar", 12));
        rows.add(monthPoint("Apr", 11));
        rows.add(monthPoint("May", 15));
        rows.add(monthPoint("Jun", 18));
        rows.add(monthPoint("Jul", 16));
        rows.add(monthPoint("Aug", 20));
        rows.add(monthPoint("Sep", 22));
        rows.add(monthPoint("Oct", 19));
        rows.add(monthPoint("Nov", 24));
        rows.add(monthPoint("Dec", 28));

        return rows;
    }

    private static List<Map<String, Object>> buildWorkQueue() {
        List<Map<String, Object>> rows = new ArrayList<>();

        rows.add(workItem("10060", "PAY_EMP001", "Awaiting Approval", "$1,200", "High"));
        rows.add(workItem("10072", "PAY_EMP005", "Ready for Disbursement", "$8,400", "Medium"));
        rows.add(workItem("10084", "PAY_EMP003", "Schedule Review", "$2,900", "Medium"));
        rows.add(workItem("10091", "PAY_EMP008", "Reconciliation Exception", "$6,200", "High"));
        rows.add(workItem("10102", "PAY_EMP002", "Partial Repayment Review", "$950", "Low"));

        return rows;
    }

    private static String statusLabel(String rawStatusId) {
        String statusId = upper(rawStatusId);

        if ("LN_DRAFT".equals(statusId)) {
            return "Draft";
        }
        if ("LN_APPROVED".equals(statusId)) {
            return "Approved";
        }
        if ("LN_ACTIVE".equals(statusId)) {
            return "Active";
        }
        if ("LN_PARTIAL".equals(statusId)) {
            return "Partial";
        }
        if ("LN_CLOSED".equals(statusId)) {
            return "Closed";
        }
        return "Other";
    }

    private static Map<String, Object> kpi(String label, String value, String delta, String tone) {
        return UtilMisc.<String, Object>toMap(
                "label", label,
                "value", value,
                "delta", delta,
                "tone", tone
        );
    }

    private static Map<String, Object> monthPoint(String month, int value) {
        return UtilMisc.<String, Object>toMap(
                "month", month,
                "value", value
        );
    }

    private static Map<String, Object> statusRow(String label, int value, int percent) {
        return UtilMisc.<String, Object>toMap(
                "label", label,
                "value", value,
                "percent", percent
        );
    }

    private static Map<String, Object> flowMetric(String label, int actual, int target) {
        return UtilMisc.<String, Object>toMap(
                "label", label,
                "actual", actual,
                "target", target
        );
    }

    private static Map<String, Object> workItem(String id, String borrower, String status, String amount, String priority) {
        return UtilMisc.<String, Object>toMap(
                "id", id,
                "borrower", borrower,
                "status", status,
                "amount", amount,
                "priority", priority
        );
    }

    private static BigDecimal nvl(BigDecimal value) {
        return value == null ? BigDecimal.ZERO : value;
    }

    private static String money(BigDecimal amount) {
        return "$" + nvl(amount).setScale(2, java.math.RoundingMode.HALF_UP).toPlainString();
    }

    private static int percent(int value, int total) {
        if (total <= 0) {
            return 0;
        }
        return Math.round((value * 100.0f) / total);
    }

    private static String upper(String s) {
        return s == null ? "" : s.trim().toUpperCase();
    }

    private static Set<String> setOf(String... values) {
        Set<String> set = new HashSet<>();
        if (values != null) {
            for (String v : values) {
                set.add(v);
            }
        }
        return set;
    }

    private static String getStringAny(GenericValue gv, String... names) {
        if (gv == null || names == null) {
            return null;
        }
        for (String name : names) {
            if (gv.getModelEntity().isField(name)) {
                Object value = gv.get(name);
                if (value != null) {
                    return String.valueOf(value);
                }
            }
        }
        return null;
    }

    private static BigDecimal getBigDecimalAny(GenericValue gv, String... names) {
        if (gv == null || names == null) {
            return BigDecimal.ZERO;
        }
        for (String name : names) {
            if (gv.getModelEntity().isField(name)) {
                Object value = gv.get(name);
                if (value instanceof BigDecimal) {
                    return (BigDecimal) value;
                }
                if (value != null) {
                    try {
                        return new BigDecimal(String.valueOf(value));
                    } catch (Exception ignored) {
                    }
                }
            }
        }
        return BigDecimal.ZERO;
    }

    private static Timestamp getTimestampAny(GenericValue gv, String... names) {
        if (gv == null || names == null) {
            return null;
        }
        for (String name : names) {
            if (gv.getModelEntity().isField(name)) {
                Object value = gv.get(name);
                if (value instanceof Timestamp) {
                    return (Timestamp) value;
                }
            }
        }
        return null;
    }

    private static final class LoanKpiSnapshot {
        int totalLoans;
        BigDecimal activePortfolio = BigDecimal.ZERO;
        int pendingApprovals;
        int overdueSchedules;
        BigDecimal repaymentsToday = BigDecimal.ZERO;
        int exceptions;

        int statusDraft;
        int statusApproved;
        int statusActive;
        int statusPartial;
        int statusClosed;

        void incrementStatus(String label) {
            if ("Draft".equals(label)) {
                statusDraft++;
            } else if ("Approved".equals(label)) {
                statusApproved++;
            } else if ("Active".equals(label)) {
                statusActive++;
            } else if ("Partial".equals(label)) {
                statusPartial++;
            } else if ("Closed".equals(label)) {
                statusClosed++;
            }
        }
    }
}