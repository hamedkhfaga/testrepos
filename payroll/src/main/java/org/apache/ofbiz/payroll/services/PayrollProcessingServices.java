package org.apache.ofbiz.payroll.services;

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
import java.util.Arrays;
import java.util.Calendar;
import java.util.List;
import java.util.Map;

public class PayrollProcessingServices {
    public static final String MODULE = PayrollProcessingServices.class.getName();

    // Line types (create as StatusItem under PAY_LINE_TYPE)
    private static final String LINE_BASIC = "PAY_LINE_BASIC";
    private static final String LINE_ALLOWANCE = "PAY_LINE_ALLOWANCE";
    private static final String LINE_DEDUCTION = "PAY_LINE_DEDUCTION";
    private static final String LINE_LOAN = "PAY_LINE_LOAN";
    private static final String LINE_TAX = "PAY_LINE_TAX";
    private static final String LINE_SS = "PAY_LINE_SS";
    private static final String LINE_NET = "PAY_LINE_NET";

    // Loan deduction switch (example deduction type)
    private static final String DED_TYPE_LOAN = "WT_DED_LOAN";
    private static final String DED_STATUS_ACTIVE = "PAY_DED_ACTIVE";

    // Loan / installment statuses
    private static final String LOAN_STATUS_ACTIVE = "PAY_LOAN_ACTIVE";
    private static final String INST_STATUS_DUE = "PAY_LNINST_DUE";

    private static BigDecimal bd(Object v) {
        if (v == null) return BigDecimal.ZERO;
        if (v instanceof BigDecimal) return (BigDecimal) v;
        if (v instanceof Double) return BigDecimal.valueOf((Double) v);
        if (v instanceof Long) return BigDecimal.valueOf((Long) v);
        if (v instanceof Integer) return BigDecimal.valueOf((Integer) v);
        return new BigDecimal(String.valueOf(v));
    }

    private static BigDecimal nz(BigDecimal v) { return v == null ? BigDecimal.ZERO : v; }

    private static Timestamp now() { return UtilDateTime.nowTimestamp(); }

    /** payrollRunId only; orgPartyId taken from PayrollRun */
    private static GenericValue getRunById(Delegator delegator, String payrollRunId) throws GenericEntityException {
        GenericValue run = EntityQuery.use(delegator)
                .from("PayrollRun")
                .where("payrollRunId", payrollRunId)
                .queryOne();
        if (run == null) throw new GenericEntityException("PayrollRun not found: " + payrollRunId);
        return run;
    }

    private static GenericValue getPeriod(Delegator delegator, String orgPartyId, String payrollPeriodId) throws GenericEntityException {
        GenericValue p = EntityQuery.use(delegator)
                .from("PayrollPeriod")
                .where("payrollPeriodId", payrollPeriodId, "orgPartyId", orgPartyId)
                .queryOne();
        if (p == null) throw new GenericEntityException("PayrollPeriod not found: " + payrollPeriodId);
        return p;
    }

    /** active if effectiveDate <= periodEnd AND (thruDate is null OR thruDate >= periodStart) */
    private static EntityCondition activeByDateCond(Timestamp periodStartTs, Timestamp periodEndTs) {
        return EntityCondition.makeCondition(
                Arrays.asList(
                        EntityCondition.makeCondition("effectiveDate", EntityOperator.LESS_THAN_EQUAL_TO, periodEndTs),
                        EntityCondition.makeCondition(
                                EntityCondition.makeCondition("thruDate", EntityOperator.EQUALS, null),
                                EntityOperator.OR,
                                EntityCondition.makeCondition("thruDate", EntityOperator.GREATER_THAN_EQUAL_TO, periodStartTs)
                        )
                ),
                EntityOperator.AND
        );
    }

    private static List<GenericValue> getActiveAssignments(Delegator delegator, String orgPartyId,
                                                          Timestamp periodStartTs, Timestamp periodEndTs) throws GenericEntityException {

        EntityCondition cond = EntityCondition.makeCondition(
                Arrays.asList(
                        EntityCondition.makeCondition("orgPartyId", EntityOperator.EQUALS, orgPartyId),
                        activeByDateCond(periodStartTs, periodEndTs)
                ),
                EntityOperator.AND
        );

        return EntityQuery.use(delegator)
                .from("EmployeePayrollAssignment")
                .where(cond)
                .queryList();
    }

    private static BigDecimal sumEmployeeAllowances(Delegator delegator, String orgPartyId, String partyId,
                                                   Timestamp periodStartTs, Timestamp periodEndTs) throws GenericEntityException {

        EntityCondition cond = EntityCondition.makeCondition(
                Arrays.asList(
                        EntityCondition.makeCondition("orgPartyId", EntityOperator.EQUALS, orgPartyId),
                        EntityCondition.makeCondition("partyId", EntityOperator.EQUALS, partyId),
                        activeByDateCond(periodStartTs, periodEndTs)
                ),
                EntityOperator.AND
        );

        List<GenericValue> rows = EntityQuery.use(delegator).from("EmployeeAllowance").where(cond).queryList();
        BigDecimal total = BigDecimal.ZERO;
        for (GenericValue r : rows) total = total.add(bd(r.get("amount")));
        return total;
    }

    private static BigDecimal sumEmployeeDeductions(Delegator delegator, String orgPartyId, String partyId,
                                                   Timestamp periodStartTs, Timestamp periodEndTs) throws GenericEntityException {

        EntityCondition cond = EntityCondition.makeCondition(
                Arrays.asList(
                        EntityCondition.makeCondition("orgPartyId", EntityOperator.EQUALS, orgPartyId),
                        EntityCondition.makeCondition("partyId", EntityOperator.EQUALS, partyId),
                        activeByDateCond(periodStartTs, periodEndTs)
                ),
                EntityOperator.AND
        );

        List<GenericValue> rows = EntityQuery.use(delegator).from("EmployeeDeduction").where(cond).queryList();
        BigDecimal total = BigDecimal.ZERO;
        for (GenericValue r : rows) total = total.add(bd(r.get("amount")));
        return total;
    }

    /**
     * OFBiz 24 SAFE: no filterByDate(). Use EntityCondition date window.
     * If your EmployeeDeduction doesn't have orgPartyId, remove that condition.
     */
    private static boolean hasLoanDeductionEnabled(Delegator delegator, String orgPartyId, String partyId, Timestamp onDate)
            throws GenericEntityException {

        EntityCondition onDateCond = EntityCondition.makeCondition(
                Arrays.asList(
                        EntityCondition.makeCondition("effectiveDate", EntityOperator.LESS_THAN_EQUAL_TO, onDate),
                        EntityCondition.makeCondition(
                                EntityCondition.makeCondition("thruDate", EntityOperator.EQUALS, null),
                                EntityOperator.OR,
                                EntityCondition.makeCondition("thruDate", EntityOperator.GREATER_THAN_EQUAL_TO, onDate)
                        )
                ),
                EntityOperator.AND
        );

        EntityCondition cond = EntityCondition.makeCondition(
                Arrays.asList(
                        EntityCondition.makeCondition("orgPartyId", EntityOperator.EQUALS, orgPartyId),
                        EntityCondition.makeCondition("partyId", EntityOperator.EQUALS, partyId),
                        EntityCondition.makeCondition("deductionTypeId", EntityOperator.EQUALS, DED_TYPE_LOAN),
                        EntityCondition.makeCondition("statusId", EntityOperator.EQUALS, DED_STATUS_ACTIVE),
                        onDateCond
                ),
                EntityOperator.AND
        );

        return EntityQuery.use(delegator)
                .from("EmployeeDeduction")
                .where(cond)
                .queryCount() > 0;
    }

    /**
     * OFBiz 24 SAFE: no .condition(...) chaining.
     * Also: sums ALL installments due in range (not just queryFirst()).
     */
    public static BigDecimal sumLoanInstallmentsDue(Delegator delegator,
                                                   String orgPartyId,
                                                   String partyId,
                                                   Timestamp periodStart,
                                                   Timestamp periodEnd) throws GenericEntityException {

        BigDecimal total = BigDecimal.ZERO;

        // Some models use orgPartyId; yours uses organizationPartyId. Keep exact from your entity.
        List<GenericValue> loans = EntityQuery.use(delegator)
                .from("EmployeeLoan")
                .where("partyId", partyId,
                       "organizationPartyId", orgPartyId,
                       "statusId", LOAN_STATUS_ACTIVE)
                .queryList();

        if (UtilValidate.isEmpty(loans)) return BigDecimal.ZERO;

        for (GenericValue loan : loans) {
            String employeeLoanId = loan.getString("employeeLoanId");
            if (UtilValidate.isEmpty(employeeLoanId)) continue;

            EntityCondition instCond = EntityCondition.makeCondition(
                    Arrays.asList(
                            EntityCondition.makeCondition("employeeLoanId", EntityOperator.EQUALS, employeeLoanId),
                            EntityCondition.makeCondition("statusId", EntityOperator.EQUALS, INST_STATUS_DUE),
                            EntityCondition.makeCondition("dueDate", EntityOperator.GREATER_THAN_EQUAL_TO, periodStart),
                            EntityCondition.makeCondition("dueDate", EntityOperator.LESS_THAN_EQUAL_TO, periodEnd)
                    ),
                    EntityOperator.AND
            );

            List<GenericValue> insts = EntityQuery.use(delegator)
                    .from("LoanInstallment")
                    .where(instCond)
                    .orderBy("dueDate")
                    .queryList();

            for (GenericValue inst : insts) {
                total = total.add(nz(inst.getBigDecimal("amount")));
            }
        }

        return total;
    }

    private static BigDecimal calculateIncomeTax(Delegator delegator, String orgPartyId, String countryGeoId,
                                                int taxYear, BigDecimal taxableIncome) throws GenericEntityException {

        EntityCondition cond = EntityCondition.makeCondition(
                Arrays.asList(
                        EntityCondition.makeCondition("orgPartyId", EntityOperator.EQUALS, orgPartyId),
                        EntityCondition.makeCondition("countryGeoId", EntityOperator.EQUALS, countryGeoId),
                        EntityCondition.makeCondition("taxYear", EntityOperator.EQUALS, taxYear)
                ),
                EntityOperator.AND
        );

        List<GenericValue> brackets = EntityQuery.use(delegator)
                .from("PayrollTaxConfig")
                .where(cond)
                .orderBy("minIncome ASC")
                .queryList();

        BigDecimal tax = BigDecimal.ZERO;
        for (GenericValue b : brackets) {
            BigDecimal min = bd(b.get("minIncome"));
            BigDecimal max = b.get("maxIncome") == null ? null : bd(b.get("maxIncome"));
            BigDecimal rate = b.get("taxRate") == null ? BigDecimal.ZERO : bd(b.get("taxRate"));
            BigDecimal add = b.get("additionalAmount") == null ? BigDecimal.ZERO : bd(b.get("additionalAmount"));

            boolean inRange = taxableIncome.compareTo(min) >= 0 && (max == null || taxableIncome.compareTo(max) <= 0);
            if (inRange) {
                tax = taxableIncome.multiply(rate).add(add);
                break;
            }
        }
        return tax.max(BigDecimal.ZERO);
    }

    private static BigDecimal calculateSocialSecurity(Delegator delegator, String orgPartyId, String countryGeoId,
                                                     Timestamp onDate, BigDecimal gross) throws GenericEntityException {

        EntityCondition cond = EntityCondition.makeCondition(
                Arrays.asList(
                        EntityCondition.makeCondition("orgPartyId", EntityOperator.EQUALS, orgPartyId),
                        EntityCondition.makeCondition("countryGeoId", EntityOperator.EQUALS, countryGeoId),
                        EntityCondition.makeCondition("effectiveDate", EntityOperator.LESS_THAN_EQUAL_TO, onDate),
                        EntityCondition.makeCondition(
                                EntityCondition.makeCondition("thruDate", EntityOperator.EQUALS, null),
                                EntityOperator.OR,
                                EntityCondition.makeCondition("thruDate", EntityOperator.GREATER_THAN_EQUAL_TO, onDate)
                        )
                ),
                EntityOperator.AND
        );

        GenericValue cfg = EntityQuery.use(delegator).from("SocialSecurityConfig").where(cond).queryFirst();
        if (cfg == null) return BigDecimal.ZERO;

        BigDecimal rate = bd(cfg.get("employeeRate"));
        BigDecimal maxBase = cfg.get("maxContributionBase") == null ? null : bd(cfg.get("maxContributionBase"));
        BigDecimal minBase = cfg.get("minContributionBase") == null ? null : bd(cfg.get("minContributionBase"));

        BigDecimal base = gross;
        if (maxBase != null && base.compareTo(maxBase) > 0) base = maxBase;
        if (minBase != null && base.compareTo(minBase) < 0) base = minBase;

        return base.multiply(rate).max(BigDecimal.ZERO);
    }

    private static GenericValue upsertResultHeader(Delegator delegator, String orgPartyId, String payrollRunId,
                                                  String partyId, String currencyUomId) throws GenericEntityException {

        GenericValue existing = EntityQuery.use(delegator)
                .from("EmployeePayrollResult")
                .where("orgPartyId", orgPartyId, "payrollRunId", payrollRunId, "partyId", partyId)
                .queryFirst();

        if (existing != null) return existing;

        GenericValue res = delegator.makeValue("EmployeePayrollResult");
        res.set("payrollResultId", delegator.getNextSeqId("EmployeePayrollResult"));
        res.set("orgPartyId", orgPartyId);
        res.set("payrollRunId", payrollRunId);
        res.set("partyId", partyId);
        res.set("currencyUomId", currencyUomId);
        res.set("versionNumber", 1L);
        res.set("isPayslipGenerated", "N");
        res.set("isPayslipSent", "N");
        res.create();
        return res;
    }

    private static void clearExistingLinesAndTax(Delegator delegator, String payrollResultId) throws GenericEntityException {
        delegator.removeByAnd("EmployeePayrollResultLine", UtilMisc.toMap("payrollResultId", payrollResultId));
        // If EmployeeTaxDetail entity exists in your plugin, keep this:
        delegator.removeByAnd("EmployeeTaxDetail", UtilMisc.toMap("payrollResultId", payrollResultId));
    }

    private static void createLine(Delegator delegator, String payrollResultId, String lineTypeId,
                                   String desc, BigDecimal amount) throws GenericEntityException {
        GenericValue line = delegator.makeValue("EmployeePayrollResultLine");
        line.set("resultLineId", delegator.getNextSeqId("EmployeePayrollResultLine"));
        line.set("payrollResultId", payrollResultId);
        line.set("lineTypeId", lineTypeId);
        line.set("description", desc);
        line.set("amount", amount);
        line.create();
    }

    private static void createTaxDetail(Delegator delegator, String payrollResultId, String taxTypeId,
                                        BigDecimal taxable, BigDecimal rate, BigDecimal tax) throws GenericEntityException {
        GenericValue td = delegator.makeValue("EmployeeTaxDetail");
        td.set("taxDetailId", delegator.getNextSeqId("EmployeeTaxDetail"));
        td.set("payrollResultId", payrollResultId);
        td.set("taxTypeId", taxTypeId);
        td.set("taxableAmount", taxable);
        td.set("taxRate", rate);
        td.set("calculatedTax", tax);
        td.create();
    }

    /** Service entry point aligned to your services.xml */
    public static Map<String, Object> processPayrollRun(DispatchContext dctx, Map<String, ?> context) {
        Delegator delegator = dctx.getDelegator();

        try {
            String payrollRunId = (String) context.get("payrollRunId");
            Boolean forceRecalc = (Boolean) context.get("forceRecalc");

            if (UtilValidate.isEmpty(payrollRunId)) {
                return ServiceUtil.returnError("payrollRunId is required");
            }

            GenericValue run = getRunById(delegator, payrollRunId);
            String orgPartyId = run.getString("orgPartyId");
            if (UtilValidate.isEmpty(orgPartyId)) {
                return ServiceUtil.returnError("PayrollRun orgPartyId is empty. payrollRunId=" + payrollRunId);
            }

            GenericValue period = getPeriod(delegator, orgPartyId, run.getString("payrollPeriodId"));

            Timestamp periodStartTs = UtilDateTime.toTimestamp(period.getDate("startDate"));
            Timestamp periodEndTs = UtilDateTime.getDayEnd(UtilDateTime.toTimestamp(period.getDate("endDate")));

            String currencyUomId = run.getString("currencyUomId");
            int taxYear = UtilDateTime.toCalendar(periodStartTs).get(Calendar.YEAR);

            GenericValue org = EntityQuery.use(delegator).from("PayrollOrganization")
                    .where("orgPartyId", orgPartyId).queryOne();
            String countryGeoId = org != null ? org.getString("countryGeoId") : null;

            List<GenericValue> assignments = getActiveAssignments(delegator, orgPartyId, periodStartTs, periodEndTs);

            BigDecimal totalGross = BigDecimal.ZERO;
            BigDecimal totalDed = BigDecimal.ZERO;
            BigDecimal totalNet = BigDecimal.ZERO;

            int employeeCount = 0;

            for (GenericValue a : assignments) {
                String partyId = a.getString("partyId");
                if (UtilValidate.isEmpty(partyId)) continue;

                employeeCount++;

                BigDecimal basic = bd(a.get("baseSalary"));
                BigDecimal allowances = sumEmployeeAllowances(delegator, orgPartyId, partyId, periodStartTs, periodEndTs);
                BigDecimal deductions = sumEmployeeDeductions(delegator, orgPartyId, partyId, periodStartTs, periodEndTs);

                BigDecimal loanDed = BigDecimal.ZERO;
                if (hasLoanDeductionEnabled(delegator, orgPartyId, partyId, periodEndTs)) {
                    loanDed = sumLoanInstallmentsDue(delegator, orgPartyId, partyId, periodStartTs, periodEndTs);
                }

                BigDecimal gross = basic.add(allowances);
                BigDecimal taxableIncome = gross;

                BigDecimal tax = (UtilValidate.isEmpty(countryGeoId)) ? BigDecimal.ZERO
                        : calculateIncomeTax(delegator, orgPartyId, countryGeoId, taxYear, taxableIncome);

                BigDecimal ss = (UtilValidate.isEmpty(countryGeoId)) ? BigDecimal.ZERO
                        : calculateSocialSecurity(delegator, orgPartyId, countryGeoId, periodEndTs, gross);

                BigDecimal totalDeductions = deductions.add(loanDed).add(tax).add(ss);
                BigDecimal net = gross.subtract(totalDeductions);

                GenericValue res = upsertResultHeader(delegator, orgPartyId, payrollRunId, partyId, currencyUomId);

                if (Boolean.TRUE.equals(forceRecalc)) {
                    clearExistingLinesAndTax(delegator, res.getString("payrollResultId"));
                }

                res.set("basicSalary", basic);
                res.set("allowancesTotal", allowances);
                res.set("grossSalary", gross);

                res.set("incomeTax", tax);
                res.set("socialSecurity", ss);
                res.set("loanDeductions", loanDed);
                res.set("otherDeductions", deductions);
                res.set("totalDeductions", totalDeductions);

                res.set("netSalary", net);

                res.set("paymentMethodId", a.get("paymentMethodId"));
                res.set("finAccountId", a.get("finAccountId"));

                res.store();

                createLine(delegator, res.getString("payrollResultId"), LINE_BASIC, "Basic Salary", basic);
                if (allowances.signum() != 0) createLine(delegator, res.getString("payrollResultId"), LINE_ALLOWANCE, "Allowances", allowances);
                if (deductions.signum() != 0) createLine(delegator, res.getString("payrollResultId"), LINE_DEDUCTION, "Deductions", deductions);
                if (loanDed.signum() != 0) createLine(delegator, res.getString("payrollResultId"), LINE_LOAN, "Loan Installments", loanDed);
                if (tax.signum() != 0) createLine(delegator, res.getString("payrollResultId"), LINE_TAX, "Income Tax", tax);
                if (ss.signum() != 0) createLine(delegator, res.getString("payrollResultId"), LINE_SS, "Social Security", ss);
                createLine(delegator, res.getString("payrollResultId"), LINE_NET, "Net Salary", net);

                if (tax.signum() != 0) {
                    createTaxDetail(delegator, res.getString("payrollResultId"),
                            "PAY_TAX_INCOME", taxableIncome, BigDecimal.ZERO, tax);
                }

                // NOTE: you had a bigger YTD method earlier. Keep it if your entity has those fields.
                // Here we do minimal safe YTD update, compatible with your earlier method signature if you keep it:
                upsertYtd(delegator, orgPartyId, partyId, taxYear, currencyUomId,
                        gross, tax, ss, allowances, BigDecimal.ZERO, net);

                totalGross = totalGross.add(gross);
                totalDed = totalDed.add(totalDeductions);
                totalNet = totalNet.add(net);
            }

            run.set("totalEmployees", (long) employeeCount);
            run.set("totalGrossPay", totalGross);
            run.set("totalDeductions", totalDed);
            run.set("totalNetPay", totalNet);
            run.store();

            return ServiceUtil.returnSuccess();

        } catch (Exception e) {
            Debug.logError(e, MODULE);
            return ServiceUtil.returnError("processPayrollRun failed: " + e.getMessage());
        }
    }

    private static void upsertYtd(Delegator delegator, String orgPartyId, String partyId, int year, String currencyUomId,
                                  BigDecimal gross, BigDecimal tax, BigDecimal ss, BigDecimal allowances,
                                  BigDecimal overtime, BigDecimal net) throws GenericEntityException {

        GenericValue ytd = EntityQuery.use(delegator)
                .from("EmployeeYTDSummary")
                .where("orgPartyId", orgPartyId,
                       "partyId", partyId,
                       "calendarYear", (long) year,
                       "currencyUomId", currencyUomId)
                .queryFirst();

        if (ytd == null) {
            ytd = delegator.makeValue("EmployeeYTDSummary");
            ytd.set("employeeYtdSummaryId", delegator.getNextSeqId("EmployeeYTDSummary"));
            ytd.set("orgPartyId", orgPartyId);
            ytd.set("partyId", partyId);
            ytd.set("calendarYear", (long) year);
            ytd.set("currencyUomId", currencyUomId);

            ytd.set("ytdGross", BigDecimal.ZERO);
            ytd.set("ytdTax", BigDecimal.ZERO);
            ytd.set("ytdSocialSecurity", BigDecimal.ZERO);
            ytd.set("ytdAllowances", BigDecimal.ZERO);
            ytd.set("ytdOvertime", BigDecimal.ZERO);
            ytd.set("ytdNet", BigDecimal.ZERO);
            ytd.set("lastCalculatedDate", now());

            ytd.create();
        }

        ytd.set("ytdGross", bd(ytd.get("ytdGross")).add(nz(gross)));
        ytd.set("ytdTax", bd(ytd.get("ytdTax")).add(nz(tax)));
        ytd.set("ytdSocialSecurity", bd(ytd.get("ytdSocialSecurity")).add(nz(ss)));
        ytd.set("ytdAllowances", bd(ytd.get("ytdAllowances")).add(nz(allowances)));
        ytd.set("ytdOvertime", bd(ytd.get("ytdOvertime")).add(nz(overtime)));
        ytd.set("ytdNet", bd(ytd.get("ytdNet")).add(nz(net)));
        ytd.set("lastCalculatedDate", now());
        ytd.store();
    }

    public static Map<String, Object> generatePayslipsForRun(DispatchContext dctx, Map<String, ?> context) {
        Delegator delegator = dctx.getDelegator();

        try {
            String payrollRunId = (String) context.get("payrollRunId");
            if (UtilValidate.isEmpty(payrollRunId)) {
                return ServiceUtil.returnError("payrollRunId is required");
            }

            GenericValue run = getRunById(delegator, payrollRunId);
            String orgPartyId = run.getString("orgPartyId");

            List<GenericValue> results = EntityQuery.use(delegator)
                    .from("EmployeePayrollResult")
                    .where("orgPartyId", orgPartyId, "payrollRunId", payrollRunId)
                    .queryList();

            for (GenericValue r : results) {
                r.set("isPayslipGenerated", "Y");
                r.set("payslipRef", "RUN:" + payrollRunId + "|RES:" + r.getString("payrollResultId"));
                r.store();
            }

            return ServiceUtil.returnSuccess();
        } catch (Exception e) {
            Debug.logError(e, MODULE);
            return ServiceUtil.returnError("generatePayslipsForRun failed: " + e.getMessage());
        }
    }

    public static Map<String, Object> buildBankTransferBatchForRun(DispatchContext dctx, Map<String, ?> context) {
        Delegator delegator = dctx.getDelegator();
        Map<String, Object> out = ServiceUtil.returnSuccess();

        try {
            String payrollRunId = (String) context.get("payrollRunId");
            String cfgId = (String) context.get("bankTransferConfigId");

            if (UtilValidate.isEmpty(payrollRunId) || UtilValidate.isEmpty(cfgId)) {
                return ServiceUtil.returnError("payrollRunId, bankTransferConfigId are required");
            }

            GenericValue run = getRunById(delegator, payrollRunId);
            String orgPartyId = run.getString("orgPartyId");

            GenericValue batch = delegator.makeValue("BankTransferBatch");
            String batchId = delegator.getNextSeqId("BankTransferBatch");
            batch.set("bankTransferBatchId", batchId);
            batch.set("orgPartyId", orgPartyId);
            batch.set("payrollRunId", payrollRunId);
            batch.set("bankTransferConfigId", cfgId);
            batch.set("currencyUomId", run.getString("currencyUomId"));
            batch.set("generatedDate", now());
            batch.create();

            BigDecimal total = BigDecimal.ZERO;
            long count = 0;

            List<GenericValue> results = EntityQuery.use(delegator)
                    .from("EmployeePayrollResult")
                    .where("orgPartyId", orgPartyId, "payrollRunId", payrollRunId)
                    .queryList();

            long seq = 1;
            for (GenericValue r : results) {
                BigDecimal net = bd(r.get("netSalary"));
                if (net.compareTo(BigDecimal.ZERO) <= 0) continue;

                GenericValue line = delegator.makeValue("BankTransferBatchLine");
                line.set("bankTransferBatchLineId", delegator.getNextSeqId("BankTransferBatchLine"));
                line.set("bankTransferBatchId", batchId);
                line.set("payrollResultId", r.getString("payrollResultId"));
                line.set("partyId", r.getString("partyId"));
                line.set("amount", net);
                line.set("currencyUomId", r.getString("currencyUomId"));
                line.set("sequenceNum", seq++);
                line.create();

                total = total.add(net);
                count++;
            }

            batch.set("totalAmount", total);
            batch.set("totalRecords", count);
            batch.store();

            out.put("bankTransferBatchId", batchId);
            return out;

        } catch (Exception e) {
            Debug.logError(e, MODULE);
            return ServiceUtil.returnError("buildBankTransferBatchForRun failed: " + e.getMessage());
        }
    }
}
