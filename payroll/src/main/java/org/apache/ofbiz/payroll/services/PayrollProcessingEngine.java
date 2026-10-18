package org.apache.ofbiz.payroll.services;

import org.apache.ofbiz.base.util.UtilDateTime;
import org.apache.ofbiz.base.util.UtilValidate;
import org.apache.ofbiz.entity.Delegator;
import org.apache.ofbiz.entity.GenericValue;
import org.apache.ofbiz.entity.condition.EntityCondition;
import org.apache.ofbiz.entity.condition.EntityOperator;
import org.apache.ofbiz.entity.util.EntityQuery;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.util.Arrays;
import java.util.Calendar;
import java.util.List;

public final class PayrollProcessingEngine {

    private PayrollProcessingEngine() {}

    // controlled line types (align with your masters if you have them)
    public static final String LT_BASIC      = "BASIC";
    public static final String LT_OVERTIME   = "OVERTIME";
    public static final String LT_ALLOWANCE  = "ALLOWANCE";
    public static final String LT_BONUS      = "BONUS";
    public static final String LT_TAX        = "TAX";
    public static final String LT_SOCSEC     = "SOC_SEC";
    public static final String LT_LOAN       = "LOAN";
    public static final String LT_DEDUCTION  = "DEDUCTION";
    public static final String LT_ADJUSTMENT = "ADJUSTMENT";

    /**
     * Strict engine:
     * - PayrollRun uses organizationPartyId
     * - EmployeePayrollAssignment uses organizationPartyId
     * - All queries/creates use organizationPartyId ONLY (no orgPartyId anywhere)
     */
    public static void process(Delegator delegator, GenericValue payrollRun, String processedByUserLoginId) throws Exception {

        // --- Validate run fields ---
        final String payrollRunId = payrollRun.getString("payrollRunId");
        if (UtilValidate.isEmpty(payrollRunId)) {
            throw new IllegalArgumentException("PayrollRun.payrollRunId is required");
        }

        final String organizationPartyId = payrollRun.getString("organizationPartyId");
        if (UtilValidate.isEmpty(organizationPartyId)) {
            throw new IllegalArgumentException("PayrollRun.organizationPartyId is required. payrollRunId=" + payrollRunId);
        }

        final String currencyUomId = payrollRun.getString("currencyUomId");
        if (UtilValidate.isEmpty(currencyUomId)) {
            throw new IllegalArgumentException("PayrollRun.currencyUomId is required. payrollRunId=" + payrollRunId);
        }

        final String payrollPeriodId = payrollRun.getString("payrollPeriodId");
        if (UtilValidate.isEmpty(payrollPeriodId)) {
            throw new IllegalArgumentException("PayrollRun.payrollPeriodId is required. payrollRunId=" + payrollRunId);
        }

        final Timestamp now = UtilDateTime.nowTimestamp();

        // --- Period ---
        GenericValue period = EntityQuery.use(delegator)
                .from("PayrollPeriod")
                .where("payrollPeriodId", payrollPeriodId)
                .queryOne();

        if (period == null) {
            throw new IllegalStateException("PayrollPeriod not found. payrollPeriodId=" + payrollPeriodId + " payrollRunId=" + payrollRunId);
        }

        final Timestamp periodStart = toTimestampSafe(period.get("startDate"));
        final Timestamp periodEnd   = toTimestampSafe(period.get("endDate"));
        if (periodStart == null || periodEnd == null) {
            throw new IllegalStateException("PayrollPeriod.startDate/endDate are required. payrollPeriodId=" + payrollPeriodId);
        }

        // 1) Active assignments in period (DB-filtered)
        EntityCondition activeCond = EntityCondition.makeCondition(Arrays.asList(
                EntityCondition.makeCondition("organizationPartyId", EntityOperator.EQUALS, organizationPartyId),
                EntityCondition.makeCondition("effectiveDate", EntityOperator.LESS_THAN_EQUAL_TO, periodEnd),
                EntityCondition.makeCondition(
                        EntityCondition.makeCondition("thruDate", EntityOperator.EQUALS, null),
                        EntityOperator.OR,
                        EntityCondition.makeCondition("thruDate", EntityOperator.GREATER_THAN_EQUAL_TO, periodStart)
                )
        ), EntityOperator.AND);

        List<GenericValue> assignments = EntityQuery.use(delegator)
                .from("EmployeePayrollAssignment")
                .where(activeCond)
                .queryList();

        if (assignments.isEmpty()) {
            payrollRun.set("totalEmployees", 0L);
            payrollRun.set("totalGrossPay", BigDecimal.ZERO);
            payrollRun.set("totalDeductions", BigDecimal.ZERO);
            payrollRun.set("totalNetPay", BigDecimal.ZERO);
            payrollRun.set("lastUpdatedStamp", now);
            payrollRun.store();
            return;
        }

        long totalEmployees = 0L;
        BigDecimal totalGross = BigDecimal.ZERO;
        BigDecimal totalDed = BigDecimal.ZERO;
        BigDecimal totalNet = BigDecimal.ZERO;

        for (GenericValue asg : assignments) {
            String partyId = asg.getString("partyId");
            if (UtilValidate.isEmpty(partyId)) {
                continue; // skip bad data
            }
            totalEmployees++;

            // 2) Compute components
            BigDecimal basic      = nz(asg.getBigDecimal("baseSalary"));
            BigDecimal overtime   = computeOvertime(delegator, organizationPartyId, partyId, payrollRun, periodStart, periodEnd);
            BigDecimal allowances = computeAllowances(delegator, organizationPartyId, partyId, periodStart, periodEnd);
            BigDecimal bonuses    = BigDecimal.ZERO; // extend later

            BigDecimal taxableBase = basic.add(overtime).add(allowances).add(bonuses);
            BigDecimal tax         = computeTax(delegator, organizationPartyId, partyId, taxableBase, currencyUomId);
            BigDecimal social      = computeSocialSecurity(delegator, organizationPartyId, partyId, basic.add(overtime).add(allowances), currencyUomId);

            BigDecimal loan     = computeLoanDeductions(delegator, organizationPartyId, partyId, periodStart, periodEnd);
            BigDecimal otherDed = computeDeductions(delegator, organizationPartyId, partyId, periodStart, periodEnd);

            BigDecimal gross      = basic.add(overtime).add(allowances).add(bonuses);
            BigDecimal deductions = tax.add(social).add(loan).add(otherDed);
            BigDecimal net        = gross.subtract(deductions);

            // 3) Write EmployeePayrollResult (STRICT: uses organizationPartyId)
            String payrollResultId = delegator.getNextSeqId("EmployeePayrollResult");

            GenericValue res = delegator.makeValue("EmployeePayrollResult",
                    "payrollResultId", payrollResultId,
                    "payrollRunId", payrollRunId,
                    "organizationPartyId", organizationPartyId,
                    "partyId", partyId,

                    "basicSalary", basic,
                    "overtimeAmount", overtime,
                    "allowancesTotal", allowances,
                    "bonuses", bonuses,
                    "grossSalary", gross,

                    "incomeTax", tax,
                    "socialSecurity", social,
                    "loanDeductions", loan,
                    "otherDeductions", otherDed,
                    "totalDeductions", deductions,

                    "netSalary", net,
                    "currencyUomId", currencyUomId,
                    "versionNumber", 1L
            );
            delegator.create(res);

            // 4) Write lines (deductions negative for reporting)
            createLine(delegator, payrollResultId, LT_BASIC, "Basic Salary", basic, currencyUomId, 10);
            if (overtime.signum() != 0)   createLine(delegator, payrollResultId, LT_OVERTIME, "Overtime", overtime, currencyUomId, 20);
            if (allowances.signum() != 0) createLine(delegator, payrollResultId, LT_ALLOWANCE, "Allowances", allowances, currencyUomId, 30);
            if (bonuses.signum() != 0)    createLine(delegator, payrollResultId, LT_BONUS, "Bonuses", bonuses, currencyUomId, 40);

            if (tax.signum() != 0)        createLine(delegator, payrollResultId, LT_TAX, "Income Tax", tax.negate(), currencyUomId, 60);
            if (social.signum() != 0)     createLine(delegator, payrollResultId, LT_SOCSEC, "Social Security", social.negate(), currencyUomId, 70);
            if (loan.signum() != 0)       createLine(delegator, payrollResultId, LT_LOAN, "Loan Deduction", loan.negate(), currencyUomId, 80);
            if (otherDed.signum() != 0)   createLine(delegator, payrollResultId, LT_DEDUCTION, "Other Deductions", otherDed.negate(), currencyUomId, 90);

            // 5) YTD summary (STRICT: uses organizationPartyId)
            int year = UtilDateTime.toCalendar(periodStart).get(Calendar.YEAR);
            upsertYtd(delegator, organizationPartyId, partyId, year, currencyUomId, gross, deductions, net);

            totalGross = totalGross.add(gross);
            totalDed   = totalDed.add(deductions);
            totalNet   = totalNet.add(net);
        }

        payrollRun.set("totalEmployees", totalEmployees);
        payrollRun.set("totalGrossPay", totalGross);
        payrollRun.set("totalDeductions", totalDed);
        payrollRun.set("totalNetPay", totalNet);
        payrollRun.set("lastUpdatedStamp", now);
        payrollRun.store();
    }

    private static void createLine(Delegator delegator, String payrollResultId, String lineTypeId,
                                   String desc, BigDecimal amount, String currencyUomId, int seq) throws Exception {
        String id = delegator.getNextSeqId("EmployeePayrollResultLine");
        GenericValue ln = delegator.makeValue("EmployeePayrollResultLine",
                "resultLineId", id,
                "payrollResultId", payrollResultId,
                "lineTypeId", lineTypeId,
                "description", desc,
                "amount", nz(amount),
                "currencyUomId", currencyUomId,
                "sequenceNum", (long) seq
        );
        delegator.create(ln);
    }

    // ---------------- Computation helpers (scoped by organizationPartyId) ----------------

    private static BigDecimal computeAllowances(Delegator d, String organizationPartyId, String partyId, Timestamp start, Timestamp end) throws Exception {
        EntityCondition cond = EntityCondition.makeCondition(Arrays.asList(
                EntityCondition.makeCondition("organizationPartyId", EntityOperator.EQUALS, organizationPartyId),
                EntityCondition.makeCondition("partyId", EntityOperator.EQUALS, partyId),
                EntityCondition.makeCondition(
                        EntityCondition.makeCondition("effectiveDate", EntityOperator.EQUALS, null),
                        EntityOperator.OR,
                        EntityCondition.makeCondition("effectiveDate", EntityOperator.LESS_THAN_EQUAL_TO, end)
                ),
                EntityCondition.makeCondition(
                        EntityCondition.makeCondition("thruDate", EntityOperator.EQUALS, null),
                        EntityOperator.OR,
                        EntityCondition.makeCondition("thruDate", EntityOperator.GREATER_THAN_EQUAL_TO, start)
                )
        ), EntityOperator.AND);

        List<GenericValue> list = EntityQuery.use(d).from("EmployeeAllowance").where(cond).queryList();
        BigDecimal sum = BigDecimal.ZERO;
        for (GenericValue a : list) sum = sum.add(nz(a.getBigDecimal("amount")));
        return sum;
    }

    private static BigDecimal computeDeductions(Delegator d, String organizationPartyId, String partyId, Timestamp start, Timestamp end) throws Exception {
        EntityCondition cond = EntityCondition.makeCondition(Arrays.asList(
                EntityCondition.makeCondition("organizationPartyId", EntityOperator.EQUALS, organizationPartyId),
                EntityCondition.makeCondition("partyId", EntityOperator.EQUALS, partyId),
                EntityCondition.makeCondition(
                        EntityCondition.makeCondition("effectiveDate", EntityOperator.EQUALS, null),
                        EntityOperator.OR,
                        EntityCondition.makeCondition("effectiveDate", EntityOperator.LESS_THAN_EQUAL_TO, end)
                ),
                EntityCondition.makeCondition(
                        EntityCondition.makeCondition("thruDate", EntityOperator.EQUALS, null),
                        EntityOperator.OR,
                        EntityCondition.makeCondition("thruDate", EntityOperator.GREATER_THAN_EQUAL_TO, start)
                )
        ), EntityOperator.AND);

        List<GenericValue> list = EntityQuery.use(d).from("EmployeeDeduction").where(cond).queryList();
        BigDecimal sum = BigDecimal.ZERO;
        for (GenericValue a : list) sum = sum.add(nz(a.getBigDecimal("amount")));
        return sum;
    }

    private static BigDecimal computeLoanDeductions(Delegator d, String organizationPartyId, String partyId, Timestamp start, Timestamp end) throws Exception {
        List<GenericValue> loans = EntityQuery.use(d).from("EmployeeLoan")
                .where("organizationPartyId", organizationPartyId, "partyId", partyId)
                .queryList();

        BigDecimal sum = BigDecimal.ZERO;
        for (GenericValue l : loans) sum = sum.add(nz(l.getBigDecimal("monthlyInstallment")));
        return sum;
    }

    private static BigDecimal computeTax(Delegator d, String organizationPartyId, String partyId, BigDecimal taxable, String currencyUomId) throws Exception {
        if (taxable == null || taxable.signum() <= 0) return BigDecimal.ZERO;

        GenericValue cfg = EntityQuery.use(d).from("PayrollTaxConfig")
                .where("organizationPartyId", organizationPartyId)
                .queryFirst();

        if (cfg == null) return BigDecimal.ZERO;

        BigDecimal rate = cfg.getBigDecimal("taxRate");
        if (rate == null) return BigDecimal.ZERO;

        // assumes rate is decimal (0.15). Adjust if your config stores percent (15).
        return taxable.multiply(rate);
    }

    private static BigDecimal computeSocialSecurity(Delegator d, String organizationPartyId, String partyId, BigDecimal base, String currencyUomId) throws Exception {
        if (base == null || base.signum() <= 0) return BigDecimal.ZERO;

        GenericValue cfg = EntityQuery.use(d).from("SocialSecurityConfig")
                .where("organizationPartyId", organizationPartyId)
                .queryFirst();

        if (cfg == null) return BigDecimal.ZERO;

        BigDecimal rate = cfg.getBigDecimal("employeeRate");
        if (rate == null) return BigDecimal.ZERO;

        return base.multiply(rate);
    }

    private static BigDecimal computeOvertime(Delegator d, String organizationPartyId, String partyId, GenericValue run, Timestamp start, Timestamp end) {
        // integrate Attendance/Timesheet later
        return BigDecimal.ZERO;
    }

    private static void upsertYtd(Delegator d, String organizationPartyId, String partyId, int year, String currencyUomId,
                                  BigDecimal gross, BigDecimal deductions, BigDecimal net) throws Exception {

        GenericValue ytd = EntityQuery.use(d).from("EmployeeYTDSummary")
                .where("organizationPartyId", organizationPartyId,
                        "partyId", partyId,
                        "calendarYear", (long) year,
                        "currencyUomId", currencyUomId)
                .queryFirst();

        if (ytd == null) {
            String id = d.getNextSeqId("EmployeeYTDSummary");
            ytd = d.makeValue("EmployeeYTDSummary",
                    "employeeYtdSummaryId", id,
                    "organizationPartyId", organizationPartyId,
                    "partyId", partyId,
                    "calendarYear", (long) year,
                    "currencyUomId", currencyUomId,
                    "ytdGross", nz(gross),
                    "ytdNet", nz(net),
                    "ytdTax", BigDecimal.ZERO,
                    "ytdSocialSecurity", BigDecimal.ZERO,
                    "lastCalculatedDate", UtilDateTime.nowTimestamp()
            );
            d.create(ytd);
        } else {
            ytd.set("ytdGross", nz(ytd.getBigDecimal("ytdGross")).add(nz(gross)));
            ytd.set("ytdNet", nz(ytd.getBigDecimal("ytdNet")).add(nz(net)));
            ytd.set("lastCalculatedDate", UtilDateTime.nowTimestamp());
            ytd.store();
        }
    }

    private static BigDecimal nz(BigDecimal v) {
        return v == null ? BigDecimal.ZERO : v;
    }

    /**
     * Accepts java.sql.Timestamp, java.sql.Date, java.util.Date
     */
    private static Timestamp toTimestampSafe(Object v) {
        if (v == null) return null;
        if (v instanceof Timestamp) return (Timestamp) v;
        if (v instanceof java.sql.Date) return new Timestamp(((java.sql.Date) v).getTime());
        if (v instanceof java.util.Date) return new Timestamp(((java.util.Date) v).getTime());
        return null;
    }
}
