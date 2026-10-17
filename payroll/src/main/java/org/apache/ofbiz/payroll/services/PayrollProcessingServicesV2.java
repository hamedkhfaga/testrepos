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
import org.apache.ofbiz.service.LocalDispatcher;
import org.apache.ofbiz.service.ModelService;
import org.apache.ofbiz.service.ServiceUtil;

import org.apache.ofbiz.entity.transaction.TransactionUtil;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.sql.Timestamp;
import java.util.*;
import org.apache.ofbiz.payroll.util.PayrollGovernanceUtil;
/**
 * Phase#2 Payroll processing (snapshot-driven) + Phase#2.5 hooks:
 * - Auto-create unpaid leave / absence deduction items from snapshot
 * - Attach pending PayrollAdditionItem to result + write PRL_ADD lines
 *
 * Design invariants:
 * - Snapshot is the only processing input
 * - EmployeePayrollResult is the only source for posting/payments
 * - Deterministic, idempotent with forceReprocess
 * - data/legacyData always present and non-null
 */
public class PayrollProcessingServicesV2 {
    public static final String module = PayrollProcessingServicesV2.class.getName();

    // Governance
    private static final String RUN_READY_STATUS = "PR_PROCESSED";
    private static final String RUN_DONE_STATUS  = "PR_DONE";
    private static final String SNAP_DEFAULT_STATUS = "SNAP_NEW";

    // Result header statuses
    private static final String RES_STATUS_CALC = "RES_CALC";

    // Result line types (StatusItem IDs in PAY_RES_LINE)
    // NOTE: you showed new PRL_* seed data; these are safer and clearer than P_LN_*.
    private static final String RL_STRUCT = "PRL_STRUCT";
    private static final String RL_OT     = "PRL_OT";
    private static final String RL_ADD    = "PRL_ADD";
    private static final String RL_DED    = "PRL_DED";
    private static final String RL_TAX    = "PRL_TAX";

    // Component enums seeded (PayrollStructureItem.componentTypeEnumId)
    private static final String COMP_EARNING   = "EARNING";
    private static final String COMP_ALLOWANCE = "ALLOWANCE";
    private static final String COMP_DEDUCTION = "DEDUCTION";

    // Calc methods (PayrollStructureItem.calcMethodEnumId)
    private static final String CALC_FIXED   = "FIXED";
    private static final String CALC_PERCENT = "PERCENT";

    // Structure item/member statuses seeded
    private static final String PAYSTRM_ACT = "PAYSTRM_ACT";
    private static final String PAYSTRI_ACT = "PAYSTRI_ACT";

    // OT math
    private static final BigDecimal BD_60  = new BigDecimal("60");
    private static final BigDecimal BD_100 = new BigDecimal("100");
    private static final BigDecimal STANDARD_MONTHLY_HOURS = new BigDecimal("173.33");
    private static final int MONEY_SCALE = 2;

    // Phase#2.5 leave/absence deduction defaults (until you add period workDays/workMinutes)
    private static final BigDecimal DEFAULT_MONTH_DAYS = new BigDecimal("30");     // monthly basis
    private static final BigDecimal MINUTES_PER_WORKDAY = new BigDecimal("480");   // 8*60

    // DeductionType IDs (must exist in DeductionType)
    private static final String DED_ABSENCE = "ABSENCE";
    private static final String DED_UNPAID_LEAVE = "UNPAID_LEAVE";

    // ---------------- PUBLIC SERVICE ----------------

public static Map<String, Object> processPayrollRunFromSnapshotV2(DispatchContext dctx, Map<String, Object> context) {
    Delegator delegator = dctx.getDelegator();
    LocalDispatcher dispatcher = dctx.getDispatcher();

    String payrollRunId = (String) context.get("payrollRunId");
    String snapshotStatusId = (String) context.get("snapshotStatusId");
    String forceReprocess = normalizeFlag((String) context.get("forceReprocess"));
    String taxTypeIdIn = (String) context.get("taxTypeId"); // optional
    GenericValue userLogin = (GenericValue) context.get("userLogin");

    Map<String, Object> data = new HashMap<>();
    Map<String, Object> legacyData = new HashMap<>();

    if (UtilValidate.isEmpty(payrollRunId)) {
        return errorNonNull("payrollRunId is required", data, legacyData);
    }
    if (UtilValidate.isEmpty(snapshotStatusId)) {
        snapshotStatusId = SNAP_DEFAULT_STATUS;
    }
    if (userLogin == null) {
        return errorNonNull("userLogin is required", data, legacyData);
    }

    boolean beganTx = false;
    try {
        beganTx = TransactionUtil.begin(3600);

        // 1) Load PayrollRun first
        GenericValue payrollRun = EntityQuery.use(delegator)
                .from("PayrollRun")
                .where("payrollRunId", payrollRunId)
                .queryOne();

        if (payrollRun == null) {
            return errorNonNull("PayrollRun not found for payrollRunId=" + payrollRunId, data, legacyData);
        }

        String organizationPartyId = payrollRun.getString("organizationPartyId");
        String payrollPeriodId = payrollRun.getString("payrollPeriodId");
        String runStatusId = payrollRun.getString("statusId");

        data.put("payrollRunId", payrollRunId);
        data.put("organizationPartyId", organizationPartyId);
        data.put("payrollPeriodId", payrollPeriodId);
        data.put("snapshotStatusId", snapshotStatusId);

        legacyData.put("payrollRunId", payrollRunId);
        legacyData.put("organizationPartyId", organizationPartyId);
        legacyData.put("payrollPeriodId", payrollPeriodId);
        legacyData.put("snapshotStatusId", snapshotStatusId);
        data.put("runStatusBefore", runStatusId);
        legacyData.put("runStatusBefore", runStatusId);

        // 2) Lifecycle pre-check: processing allowed from PR_PROCESSED,
        // or from PR_DONE only when forceReprocess=Y.
        if (!RUN_READY_STATUS.equals(runStatusId)) {
            if ("Y".equals(forceReprocess) && RUN_DONE_STATUS.equals(runStatusId)) {
                // allowed reprocess from completed run
            } else {
                auditBestEffort(dispatcher, userLogin, organizationPartyId, payrollRunId, payrollPeriodId,
                        "PAY_ACT_PROCESS_RUN", "ACT_FAIL",
                        "Run status not processable: " + runStatusId);
                return errorNonNull(
                        "Run status not processable: " + runStatusId,
                        data,
                        legacyData
                );
            }
        }

        // 3) Governance pre-check: run must be editable and period must be open
        try {
            PayrollGovernanceUtil.ensureRunAndPeriodEditable(
                    delegator,
                    organizationPartyId,
                    payrollPeriodId,
                    payrollRunId
            );
        } catch (Exception e) {
            auditBestEffort(dispatcher, userLogin, organizationPartyId, payrollRunId, payrollPeriodId,
                    "PAY_ACT_PROCESS_RUN", "ACT_FAIL",
                    "processPayrollRunFromSnapshotV2 blocked: " + e.getMessage());
            throw e;
        }

        // 3) Validate snapshot rows exist
        long snapRowCount = EntityQuery.use(delegator)
                .from("PayrollInputSnapshot")
                .where("payrollRunId", payrollRunId,
                       "snapshotStatusId", snapshotStatusId)
                .queryCount();

        if (snapRowCount == 0) {
            return errorNonNull(
                    "No PayrollInputSnapshot rows found for run=" + payrollRunId + " snapshotStatusId=" + snapshotStatusId,
                    data,
                    legacyData
            );
        }

        // 4) Idempotency / rerun
        long existingResultCount = EntityQuery.use(delegator)
                .from("EmployeePayrollResult")
                .where("payrollRunId", payrollRunId)
                .queryCount();

        if (existingResultCount > 0 && !"Y".equals(forceReprocess)) {
            data.put("snapshotRowCount", snapRowCount);
            data.put("existingResultCount", existingResultCount);
            data.put("generatedCount", 0);
            data.put("skippedBecauseAlreadyProcessed", true);

            legacyData.putAll(data);

            auditBestEffort(dispatcher, userLogin, organizationPartyId, payrollRunId, payrollPeriodId,
                    "PAY_ACT_PROCESS_RUN", "SKIPPED", "Already processed; set forceReprocess=Y to regenerate.");
            Map<String, Object> ok = ServiceUtil.returnSuccess(
                    "Already processed; set forceReprocess=Y to regenerate."
            );
            ok.put("data", data);
            ok.put("legacyData", legacyData);

            TransactionUtil.commit(beganTx);
            return ok;
        }

        if ("Y".equals(forceReprocess) && existingResultCount > 0) {
            Map<String, Object> purgeCtx = UtilMisc.toMap(
                    "payrollRunId", payrollRunId,
                    "userLogin", userLogin
            );
            Map<String, Object> purgeRes = payrollPurgeResultsForRunV2(dctx, purgeCtx);
            if (ServiceUtil.isError(purgeRes)) {
                TransactionUtil.rollback(beganTx, "Failed purge", null);
                return purgeRes;
            }
            data.put("purged", true);
            data.put("purgeSummary", purgeRes.get("data"));
        } else {
            data.put("purged", false);
        }

        // 5) Deterministic employee list from snapshot
        List<String> employeePartyIds = SnapshotAdapter.listEmployeesFromSnapshot(
                delegator, payrollRunId, snapshotStatusId
        );
        Collections.sort(employeePartyIds);

        // Resolve income tax type deterministically
        String resolvedTaxTypeId = UtilValidate.isNotEmpty(taxTypeIdIn)
                ? taxTypeIdIn
                : resolveDefaultIncomeTaxTypeId(delegator, organizationPartyId);

        int generated = 0;
        Timestamp nowTs = UtilDateTime.nowTimestamp();

        for (String partyId : employeePartyIds) {

            // A) snapshot aggregation
            CalcInput snapAgg = SnapshotAdapter.aggregateSnapshotForEmployee(
                    delegator, payrollRunId, snapshotStatusId, partyId
            );

            // B) resolve payroll structure for employee
            GenericValue payrollStructure = StructureAdapter.resolveStructureForEmployee(
                    delegator, organizationPartyId, partyId
            );
            if (payrollStructure == null) {
                return errorNonNull(
                        "No PayrollStructure resolved for org=" + organizationPartyId + " partyId=" + partyId,
                        data,
                        legacyData
                );
            }

            String payrollStructureId = payrollStructure.getString("payrollStructureId");
            String currencyUomId = payrollStructure.getString("currencyUomId");
            if (!uomExists(delegator, currencyUomId)) {
                currencyUomId = "SAR";
            }

            // C) structure items -> compute amounts
            List<GenericValue> items = StructureAdapter.listActiveItems(delegator, payrollStructureId);

            Map<String, BigDecimal> lineAmounts = new HashMap<>();
            BigDecimal basicSalary = BigDecimal.ZERO;
            BigDecimal allowancesTotal = BigDecimal.ZERO;
            BigDecimal structureDeductions = BigDecimal.ZERO;

            for (GenericValue it : items) {
                String compLineId = it.getString("componentLineId");
                String compType = it.getString("componentTypeEnumId");
                String calcMethod = it.getString("calcMethodEnumId");

                BigDecimal amt;
                if (CALC_FIXED.equals(calcMethod)) {
                    amt = toBd(it.get("amount"));
                } else if (CALC_PERCENT.equals(calcMethod)) {
                    BigDecimal pct = toBd(it.get("percent"));
                    String baseLineId = it.getString("baseComponentLineId");
                    BigDecimal baseAmt = lineAmounts.getOrDefault(baseLineId, BigDecimal.ZERO);
                    amt = baseAmt.multiply(pct).divide(BD_100, 6, RoundingMode.HALF_UP);
                } else {
                    amt = BigDecimal.ZERO;
                }

                amt = nz(amt);
                lineAmounts.put(compLineId, amt);

                if ("P_LN_BAS".equals(compLineId)) {
                    basicSalary = amt;
                }

                if (COMP_ALLOWANCE.equals(compType)) {
                    allowancesTotal = allowancesTotal.add(amt);
                } else if (COMP_DEDUCTION.equals(compType)) {
                    structureDeductions = structureDeductions.add(amt);
                }
            }

            // If basic wasn't explicitly set, fallback to first EARNING as basic
            if (basicSalary.compareTo(BigDecimal.ZERO) == 0) {
                for (GenericValue it : items) {
                    String compType = it.getString("componentTypeEnumId");
                    String calcMethod = it.getString("calcMethodEnumId");
                    if (COMP_EARNING.equals(compType) && CALC_FIXED.equals(calcMethod)) {
                        BigDecimal amt = lineAmounts.getOrDefault(
                                it.getString("componentLineId"), BigDecimal.ZERO
                        );
                        if (amt.compareTo(BigDecimal.ZERO) > 0) {
                            basicSalary = amt;
                            break;
                        }
                    }
                }
            }

            // D) overtime money
            BigDecimal otMinutes = nz(snapAgg.overtimeMinutesApproved);
            BigDecimal otHours = otMinutes.divide(BD_60, 6, RoundingMode.HALF_UP);

            BigDecimal hourlyRate = BigDecimal.ZERO;
            if (basicSalary.compareTo(BigDecimal.ZERO) > 0) {
                hourlyRate = basicSalary.divide(STANDARD_MONTHLY_HOURS, 6, RoundingMode.HALF_UP);
            }

            BigDecimal normalMultiplier = StructureAdapter.resolveNormalOtMultiplier(
                    delegator, payrollStructure
            );
            BigDecimal overtimeAmount = otHours.multiply(hourlyRate).multiply(normalMultiplier);

            // E) Create header FIRST
            String payrollResultId = delegator.getNextSeqId("EmployeePayrollResult");

            BigDecimal bonuses = BigDecimal.ZERO;
            BigDecimal grossSalary = basicSalary.add(allowancesTotal).add(overtimeAmount).add(bonuses);

            GenericValue res = delegator.makeValue("EmployeePayrollResult", UtilMisc.toMap(
                    "payrollResultId", payrollResultId,
                    "payrollRunId", payrollRunId,
                    "organizationPartyId", organizationPartyId,
                    "partyId", partyId,

                    "basicSalary", money(basicSalary),
                    "overtimeAmount", money(overtimeAmount),
                    "allowancesTotal", money(allowancesTotal),
                    "bonuses", money(bonuses),
                    "grossSalary", money(grossSalary),

                    "incomeTax", BigDecimal.ZERO,
                    "socialSecurity", BigDecimal.ZERO,
                    "loanDeductions", BigDecimal.ZERO,
                    "otherDeductions", BigDecimal.ZERO,
                    "totalDeductions", BigDecimal.ZERO,
                    "netSalary", money(grossSalary),

                    "currencyUomId", currencyUomId,
                    "statusId", RES_STATUS_CALC,
                    "versionNumber", Long.valueOf(1)
            ));
            res.create();

            // F) auto-create unpaid leave/absence deductions from snapshot
            createLeaveAndAbsenceDeductionsFromSnapshot(
                    delegator,
                    payrollResultId,
                    organizationPartyId,
                    partyId,
                    basicSalary,
                    snapAgg
            );

            // G) attach pending deductions
            DeductionTotals dedTotals = applyDeductionsAndAttach(
                    delegator,
                    payrollResultId,
                    organizationPartyId,
                    payrollPeriodId,
                    partyId,
                    currencyUomId
            );

            // H) apply due loan schedules for payroll period
            BigDecimal provisionalOtherDeductions = money(nz(dedTotals.other).add(nz(structureDeductions)));
            BigDecimal provisionalNetAvailableForLoans = money(
                    grossSalary
                            .subtract(money(dedTotals.socialSecurity))
                            .subtract(provisionalOtherDeductions)
            );
            if (provisionalNetAvailableForLoans.compareTo(BigDecimal.ZERO) < 0) {
                provisionalNetAvailableForLoans = BigDecimal.ZERO;
            }

            LoanPayrollApplyTotals loanApplyTotals = applyDueLoanSchedulesForPayroll(
                    dctx,
                    delegator,
                    dispatcher,
                    payrollResultId,
                    payrollRunId,
                    payrollPeriodId,
                    organizationPartyId,
                    partyId,
                    currencyUomId,
                    provisionalNetAvailableForLoans,
                    userLogin
            );

            // I) attach pending additions
            AdditionTotals addTotals = applyPendingAdditionsAndAttach(
                    delegator,
                    payrollResultId,
                    organizationPartyId,
                    payrollRunId,
                    partyId,
                    currencyUomId
            );

            bonuses = money(addTotals.totalAdditions);
            grossSalary = money(basicSalary.add(allowancesTotal).add(overtimeAmount).add(bonuses));

            BigDecimal socialSecurity = money(dedTotals.socialSecurity);
            BigDecimal loanDeductions = money(dedTotals.loan.add(loanApplyTotals.totalLoanDeductions));
            BigDecimal otherDeductions = money(nz(dedTotals.other).add(nz(structureDeductions)));

            // I) Tax from PayrollTaxConfig
            BigDecimal taxableIncome = grossSalary;
            BigDecimal incomeTax = money(
                    computeIncomeTaxFromConfig(
                            delegator,
                            organizationPartyId,
                            resolvedTaxTypeId,
                            taxableIncome,
                            currencyUomId
                    )
            );

            BigDecimal totalDeductions = money(
                    incomeTax.add(socialSecurity).add(loanDeductions).add(otherDeductions)
            );
            BigDecimal netSalary = money(grossSalary.subtract(totalDeductions));

            // J) Update header with final totals
            res.set("bonuses", bonuses);
            res.set("grossSalary", grossSalary);
            res.set("incomeTax", incomeTax);
            res.set("socialSecurity", socialSecurity);
            res.set("loanDeductions", loanDeductions);
            res.set("otherDeductions", otherDeductions);
            res.set("totalDeductions", totalDeductions);
            res.set("netSalary", netSalary);
            res.store();

            // K) Structure money lines
            for (GenericValue it : items) {
                String compLineId = it.getString("componentLineId");
                BigDecimal amt = lineAmounts.getOrDefault(compLineId, BigDecimal.ZERO);
                if (money(amt).compareTo(BigDecimal.ZERO) == 0) {
                    continue;
                }

                int seq = toInt(it.get("sequenceNum"), 50);
                ResultLineAdapter.createLine(
                        delegator,
                        payrollResultId,
                        currencyUomId,
                        seq,
                        RL_STRUCT,
                        compLineId,
                        "Structure component " + compLineId,
                        money(amt)
                );
            }

            // L) Overtime line
            if (money(overtimeAmount).compareTo(BigDecimal.ZERO) > 0) {
                ResultLineAdapter.createLine(
                        delegator,
                        payrollResultId,
                        currencyUomId,
                        80,
                        RL_OT,
                        "OT_PAY",
                        "Overtime pay (hours=" + otHours.toPlainString() + ", mult=" + normalMultiplier.toPlainString() + ")",
                        money(overtimeAmount)
                );
            }

            // M) Tax + deduction summary lines
            if (incomeTax.compareTo(BigDecimal.ZERO) > 0) {
                ResultLineAdapter.createLine(
                        delegator,
                        payrollResultId,
                        currencyUomId,
                        90,
                        RL_TAX,
                        "TAX",
                        "Income tax (" + resolvedTaxTypeId + ")",
                        incomeTax
                );
            }
            if (socialSecurity.compareTo(BigDecimal.ZERO) > 0) {
                ResultLineAdapter.createLine(
                        delegator,
                        payrollResultId,
                        currencyUomId,
                        91,
                        RL_DED,
                        "SSC",
                        "Social security",
                        socialSecurity
                );
            }
            if (loanDeductions.compareTo(BigDecimal.ZERO) > 0) {
                ResultLineAdapter.createLine(
                        delegator,
                        payrollResultId,
                        currencyUomId,
                        92,
                        RL_DED,
                        "LOAN",
                        "Loan deductions",
                        loanDeductions
                );
            }
            if (otherDeductions.compareTo(BigDecimal.ZERO) > 0) {
                ResultLineAdapter.createLine(
                        delegator,
                        payrollResultId,
                        currencyUomId,
                        93,
                        RL_DED,
                        "DED",
                        "Other deductions",
                        otherDeductions
                );
            }

            // N) Snapshot info lines
            ResultLineAdapter.createLine(
                    delegator, payrollResultId, currencyUomId, 110,
                    RL_STRUCT, "SNAP_WORK_MIN", "Snapshot worked minutes", money(snapAgg.workedMinutes)
            );

            ResultLineAdapter.createLine(
                    delegator, payrollResultId, currencyUomId, 120,
                    RL_STRUCT, "SNAP_OT_MIN", "Snapshot overtime approved minutes", money(snapAgg.overtimeMinutesApproved)
            );

            ResultLineAdapter.createLine(
                    delegator, payrollResultId, currencyUomId, 130,
                    RL_STRUCT, "SNAP_ABS_MIN", "Snapshot absence approved minutes", money(snapAgg.absenceMinutesApproved)
            );

            ResultLineAdapter.createLine(
                    delegator, payrollResultId, currencyUomId, 140,
                    RL_STRUCT, "SNAP_LEAVE_DAYS", "Snapshot leave approved days", money(snapAgg.leaveDaysApproved)
            );

            generated++;
        }

        // 6) finalize run
        payrollRun.set("statusId", RUN_DONE_STATUS);
        payrollRun.set("lastUpdatedStamp", nowTs);
        payrollRun.store();

        data.put("snapshotRowCount", snapRowCount);
        data.put("employeeCount", employeePartyIds.size());
        data.put("generatedCount", generated);
        data.put("runStatusAfter", RUN_DONE_STATUS);
        data.put("processedAt", nowTs);
        data.put("taxTypeIdUsed", resolvedTaxTypeId);

        legacyData.putAll(data);

        auditBestEffort(dispatcher, userLogin, organizationPartyId, payrollRunId, payrollPeriodId,
                "PAY_ACT_PROCESS_RUN", "SUCCESS", "###PAYROLL_PROCESS_V2### generated=" + generated);
        Map<String, Object> ok = ServiceUtil.returnSuccess("###PAYROLL_PROCESS_V2### generated=" + generated);
        ok.put("data", data);
        ok.put("legacyData", legacyData);

        TransactionUtil.commit(beganTx);
        return ok;

    } catch (Exception e) {
        Debug.logError(e, "processPayrollRunFromSnapshotV2 failed", module);
        try {
            if (shouldWriteProcessErrorAudit(e)) {
                auditBestEffort(dispatcher, userLogin,
                        (String) data.get("organizationPartyId"),
                        (String) data.get("payrollRunId"),
                        (String) data.get("payrollPeriodId"),
                        "PAY_ACT_PROCESS_RUN",
                        "ACT_ERROR",
                        e.getMessage());
            }
        } catch (Exception ignored) {}
        try {
            TransactionUtil.rollback(beganTx, e.getMessage(), e);
        } catch (Exception ignored) {}
        return errorNonNull("processPayrollRunFromSnapshotV2 failed: " + e.getMessage(), data, legacyData);
    }
}

private static boolean shouldWriteProcessErrorAudit(Exception e) {
    if (e == null || e.getMessage() == null) {
        return true;
    }
    String msg = e.getMessage();
    if (msg.contains("is locked and cannot be modified")) {
        return false;
    }
    if (msg.contains("is closed.")) {
        return false;
    }
    if (msg.startsWith("Run status not processable:")) {
        return false;
    }
    return true;
}


// ------------------------------------------------------------
// Deductions totals bucket
// ------------------------------------------------------------
// ------------------------------------------------------------
// Deductions totals bucket
// ------------------------------------------------------------
private static class DeductionTotals {
    BigDecimal socialSecurity = BigDecimal.ZERO;
    BigDecimal loan = BigDecimal.ZERO;
    BigDecimal other = BigDecimal.ZERO;
    long count = 0L;

    BigDecimal total() {
        return nz(socialSecurity).add(nz(loan)).add(nz(other));
    }
}
// ------------------------------------------------------------
// Apply BOTH recurring (EmployeeDeduction) + queued txn (PayrollDeductionItem)
// PayrollDeductionItem is NOT run-scoped (no payrollRunId), so it is "next-run queue"
// ------------------------------------------------------------
/**
 * Build PayrollDeductionItem rows ATTACHED to payrollResultId for the given employee+period.
 *
 * Source of truth:
 *  - EmployeeDeduction (effective for payroll period)
 *
 * Output:
 *  - Creates PayrollDeductionItem with payrollResultId set (immediately attached)
 *  - Creates PRL_DED lines in EmployeePayrollResultLine
 *  - Returns bucket totals (socialSecurity/loan/other)
 */
private static DeductionTotals applyDeductionsAndAttach(Delegator delegator,
                                                       String payrollResultId,
                                                       String organizationPartyId,
                                                       String payrollPeriodId,
                                                       String partyId,
                                                       String currencyUomId) throws Exception {

    DeductionTotals totals = new DeductionTotals();

    // ---- 1) Resolve PayrollPeriod date range (adjust field names if your entity uses fromDate/thruDate) ----
    GenericValue period = EntityQuery.use(delegator)
            .from("PayrollPeriod")
            .where("payrollPeriodId", payrollPeriodId)
            .queryOne();

    Timestamp periodStart = null;
    Timestamp periodEnd = null;
    if (period != null) {
        // Try common names; keep robust to your schema
        periodStart = firstTs(period, "startDate", "fromDate", "periodStartDate", "fromDateTime");
        periodEnd   = firstTs(period, "endDate", "thruDate", "periodEndDate", "thruDateTime");
    }

    Timestamp now = UtilDateTime.nowTimestamp();
    Timestamp endForCompare = (periodEnd != null) ? periodEnd : now;

    // ---- 2) Query EmployeeDeduction effective in the period ----
    List<EntityCondition> conds = new ArrayList<>();
    conds.add(EntityCondition.makeCondition("organizationPartyId", organizationPartyId));
    conds.add(EntityCondition.makeCondition("partyId", partyId));

    // active statuses (your data shows P_ACT and ACTIVE)
    conds.add(EntityCondition.makeCondition("statusId", EntityOperator.IN, Arrays.asList("P_ACT", "ACTIVE")));

    // effectiveDate <= periodEnd (or now)
    conds.add(EntityCondition.makeCondition("effectiveDate", EntityOperator.LESS_THAN_EQUAL_TO, endForCompare));

    if (periodStart != null) {
        // (thruDate IS NULL OR thruDate >= periodStart)
        EntityCondition thruNull = EntityCondition.makeCondition("thruDate", EntityOperator.EQUALS, null);
        EntityCondition thruGe = EntityCondition.makeCondition("thruDate", EntityOperator.GREATER_THAN_EQUAL_TO, periodStart);
        conds.add(EntityCondition.makeCondition(Arrays.asList(thruNull, thruGe), EntityOperator.OR));
    }

    List<GenericValue> empDeds = EntityQuery.use(delegator)
            .from("EmployeeDeduction")
            .where(EntityCondition.makeCondition(conds, EntityOperator.AND))
            .orderBy("deductionTypeId", "employeeDeductionId")
            .queryList();

    // ---- 3) Create attached PayrollDeductionItem rows + PRL_DED lines ----
    long seq = 600;
    for (GenericValue ed : empDeds) {
        String deductionTypeId = ed.getString("deductionTypeId");
        BigDecimal amt = nz(ed.getBigDecimal("amount"));
        if (money(amt).compareTo(BigDecimal.ZERO) == 0) continue;
        if (amt.signum() < 0) amt = amt.abs(); // store positive

        // FK protection: DeductionType must exist
        GenericValue dedType = EntityQuery.use(delegator)
                .from("DeductionType")
                .where("deductionTypeId", deductionTypeId)
                .queryOne();
        if (dedType == null) {
            throw new IllegalArgumentException("Invalid DeductionType: " + deductionTypeId + " for partyId=" + partyId);
        }

        // Create PayrollDeductionItem (NOTE: your entity fields do NOT include payrollRunId)
        String payrollDeductionItemId = delegator.getNextSeqId("PayrollDeductionItem");
        GenericValue pdi = delegator.makeValue("PayrollDeductionItem", UtilMisc.toMap(
                "payrollDeductionItemId", payrollDeductionItemId,
                "payrollResultId", payrollResultId,
                "deductionTypeId", deductionTypeId,
                "amount", money(amt),
                "partyId", partyId,
                "organizationPartyId", organizationPartyId
        ));
        pdi.create();

        String desc = dedType.getString("description");
        if (UtilValidate.isEmpty(desc)) desc = "Deduction " + deductionTypeId;

        // Create PRL_DED line
        ResultLineAdapter.createLine(delegator, payrollResultId, currencyUomId, (int) seq,
                "PRL_DED",
                payrollDeductionItemId,     // referenceId
                desc,
                money(amt));
        seq++;

        // ---- 4) Bucket totals (simple deterministic mapping) ----
        if ("SSC".equalsIgnoreCase(deductionTypeId) || deductionTypeId.startsWith("SSC_")) {
            totals.socialSecurity = totals.socialSecurity.add(money(amt));
        } else if ("LOAN".equalsIgnoreCase(deductionTypeId)
                || deductionTypeId.startsWith("LOAN_")
                || "DED_ADV".equalsIgnoreCase(deductionTypeId)) {
            totals.loan = totals.loan.add(money(amt));
        } else {
            totals.other = totals.other.add(money(amt));
        }

        totals.count++;
    }

    return totals;
}

/** Tiny utility to read first non-null timestamp field from a GV. */
private static Timestamp firstTs(GenericValue gv, String... fieldNames) {
    for (String f : fieldNames) {
        try {
            Timestamp t = gv.getTimestamp(f);
            if (t != null) return t;
        } catch (Exception ignored) {}
    }
    return null;
}
/* ///
private static class DeductionTotals {
    BigDecimal socialSecurity = BigDecimal.ZERO;
    BigDecimal loan = BigDecimal.ZERO;
    BigDecimal other = BigDecimal.ZERO;
    long count = 0;
}
*/
// ------------------------------------------------------------
// Recurring deductions from EmployeeDeduction (effective dated)
// ------------------------------------------------------------
private static void applyEmployeeDeductions(Delegator delegator,
                                           String payrollResultId,
                                           String orgId,
                                           String partyId,
                                           Timestamp periodDate,
                                           String currencyUomId,
                                           DeductionTotals totals) throws Exception {

    List<GenericValue> rec = EntityQuery.use(delegator).from("EmployeeDeduction")
            .where("organizationPartyId", orgId,
                   "partyId", partyId)
            .queryList();

    long seq = 6100L;

    for (GenericValue gv : rec) {
        Timestamp eff = gv.getTimestamp("effectiveDate");
        Timestamp thru = gv.getTimestamp("thruDate");
        if (!isEffectiveOn(periodDate, eff, thru)) continue;

        // Optional active filter (only if your data uses statusId consistently)
        // String st = gv.getString("statusId");
        // if (UtilValidate.isNotEmpty(st) && !"PAYDED_ACT".equals(st)) continue;

        String deductionTypeId = gv.getString("deductionTypeId");
        BigDecimal amt = money(gv.getBigDecimal("amount"));
        if (amt.compareTo(BigDecimal.ZERO) <= 0) continue;

        GenericValue type = EntityQuery.use(delegator).from("DeductionType")
                .where("deductionTypeId", deductionTypeId)
                .queryOne();

        String desc = (type != null && UtilValidate.isNotEmpty(type.getString("description")))
                ? type.getString("description")
                : ("Deduction " + deductionTypeId);

        bucketDeduction(totals, deductionTypeId, amt);

        ResultLineAdapter.createLine(delegator, payrollResultId, currencyUomId, (int) seq,
                RL_DED,
                "EMPDED:" + gv.getString("employeeDeductionId"),
                desc,
                amt);

        seq += 1;
    }
}

// ------------------------------------------------------------
// Pending queued txn deductions from PayrollDeductionItem (NOT run scoped)
// Attach row to payrollResultId + create PRL_DED lines
// ------------------------------------------------------------
private static void applyPendingPayrollDeductionItems(Delegator delegator,
                                                     String payrollResultId,
                                                     String orgId,
                                                     String partyId,
                                                     String currencyUomId,
                                                     DeductionTotals totals) throws Exception {

    List<GenericValue> pending = EntityQuery.use(delegator).from("PayrollDeductionItem")
            .where("organizationPartyId", orgId,
                   "partyId", partyId,
                   "payrollResultId", null)
            .orderBy("payrollDeductionItemId")
            .queryList();

    long seq = 6200L;

    for (GenericValue item : pending) {
        String deductionTypeId = item.getString("deductionTypeId");
        BigDecimal amt = money(item.getBigDecimal("amount"));
        if (amt.compareTo(BigDecimal.ZERO) <= 0) continue;

        // attach to this result
        item.set("payrollResultId", payrollResultId);
        item.store();

        GenericValue type = EntityQuery.use(delegator).from("DeductionType")
                .where("deductionTypeId", deductionTypeId)
                .queryOne();

        String desc = (type != null && UtilValidate.isNotEmpty(type.getString("description")))
                ? type.getString("description")
                : ("Deduction " + deductionTypeId);

        bucketDeduction(totals, deductionTypeId, amt);

        ResultLineAdapter.createLine(delegator, payrollResultId, currencyUomId, (int) seq,
                RL_DED,
                "TXNDED:" + item.getString("payrollDeductionItemId"),
                desc,
                amt);

        seq += 1;
    }
}

// ------------------------------------------------------------
// Helpers
// ------------------------------------------------------------
private static boolean isEffectiveOn(Timestamp period, Timestamp eff, Timestamp thru) {
    if (period == null) return true;
    if (eff != null && period.before(eff)) return false;
    if (thru != null && !period.before(thru)) return false; // thru exclusive
    return true;
}

private static Timestamp resolvePeriodDate(Delegator delegator, String orgId, String payrollPeriodId) {
    try {
        if (UtilValidate.isEmpty(payrollPeriodId)) return UtilDateTime.nowTimestamp();

        GenericValue p = EntityQuery.use(delegator).from("PayrollPeriod")
                .where("payrollPeriodId", payrollPeriodId,
                       "organizationPartyId", orgId)
                .queryOne();

        if (p != null) {
            Timestamp from = p.getTimestamp("fromDate");
            if (from != null) return from;
        }
    } catch (Exception ignored) {}
    return UtilDateTime.nowTimestamp();
}

/**
 * Temporary classification convention.
 * Recommended later: add DeductionType.categoryEnumId (SSC/LOAN/OTHER).
 */
private static void bucketDeduction(DeductionTotals totals, String deductionTypeId, BigDecimal amt) {
    if (deductionTypeId == null) {
        totals.other = totals.other.add(amt);
        return;
    }
    String up = deductionTypeId.toUpperCase(Locale.ROOT);
    if (up.startsWith("SSC")) {
        totals.socialSecurity = totals.socialSecurity.add(amt);
    } else if (up.startsWith("LOAN")) {
        totals.loan = totals.loan.add(amt);
    } else {
        totals.other = totals.other.add(amt);
    }
}


    private static class LoanPayrollApplyTotals {
        BigDecimal totalLoanDeductions = BigDecimal.ZERO;
        int appliedCount = 0;
    }


private static LoanPayrollApplyTotals applyDueLoanSchedulesForPayroll(
        DispatchContext dctx,
        Delegator delegator,
        LocalDispatcher dispatcher,
        String payrollResultId,
        String payrollRunId,
        String payrollPeriodId,
        String organizationPartyId,
        String partyId,
        String currencyUomId,
        BigDecimal startingNetAvailableForLoans,
        GenericValue userLogin) throws Exception {

    LoanPayrollApplyTotals out = new LoanPayrollApplyTotals();

    Map<String, Object> dueCtx = UtilMisc.<String, Object>toMap(
            "partyId", partyId,
            "organizationPartyId", organizationPartyId,
            "payrollPeriodId", payrollPeriodId,
            "userLogin", userLogin
    );

    Map<String, Object> dueResp = dispatcher.runSync("getDuePartyLoanInstallmentsForPayroll", dueCtx);
    if (ServiceUtil.isError(dueResp)) {
        throw new GenericEntityException(
                "Failed to load due loan schedules for payroll: "
                        + ServiceUtil.getErrorMessage(dueResp)
        );
    }

    List<GenericValue> dueRows = extractDueRows(dueResp);
    if (dueRows == null || dueRows.isEmpty()) {
        return out;
    }

    dueRows.sort(PayrollProcessingServicesV2::compareDueLoanRows);

    BigDecimal remainingNetAvailableForLoans = money(startingNetAvailableForLoans);
    int seq = 6400;
    Timestamp nowTs = UtilDateTime.nowTimestamp();

    for (GenericValue dueRow : dueRows) {
        String loanId = dueRow.getString("loanLoanId");
        String loanScheduleId = dueRow.getString("scheduleLoanScheduleId");
        Long installmentSeqNum = dueRow.getLong("scheduleInstallmentSeqNum");

        if (UtilValidate.isEmpty(loanId) || UtilValidate.isEmpty(loanScheduleId)) {
            continue;
        }

        GenericValue schedule = EntityQuery.use(delegator)
                .select("loanScheduleId", "outstandingAmount", "installmentAmount", "paidAmount",
                        "scheduleStatusId", "dueDate", "processedPayrollRunId", "repaymentSourceEnumId")
                .from("PartyLoanSchedule")
                .where("loanScheduleId", loanScheduleId)
                .queryOne();

        if (schedule == null) {
            throw new GenericEntityException("PartyLoanSchedule not found for loanScheduleId=" + loanScheduleId);
        }

        BigDecimal currentOutstanding = money(firstNonNullBigDecimal(
                schedule.getBigDecimal("outstandingAmount"),
                dueRow.getBigDecimal("scheduleOutstandingAmount"),
                dueRow.getBigDecimal("scheduleInstallmentAmount")
        ));
        if (currentOutstanding.compareTo(BigDecimal.ZERO) <= 0) {
            currentOutstanding = money(firstNonNullBigDecimal(
                    schedule.getBigDecimal("installmentAmount"),
                    BigDecimal.ZERO,
                    BigDecimal.ZERO
            ).subtract(firstNonNullBigDecimal(schedule.getBigDecimal("paidAmount"), BigDecimal.ZERO, BigDecimal.ZERO)));
            if (currentOutstanding.compareTo(BigDecimal.ZERO) < 0) {
                currentOutstanding = BigDecimal.ZERO;
            }
        }

        String scheduleStatusId = schedule.getString("scheduleStatusId");
        if (currentOutstanding.compareTo(BigDecimal.ZERO) <= 0
                || "LNS_PAID".equals(scheduleStatusId)
                || "LNS_CANCELLED".equals(scheduleStatusId)) {
            continue;
        }

        String alreadyProcessedRunId = schedule.getString("processedPayrollRunId");
        String alreadyRepaymentSourceEnumId = schedule.getString("repaymentSourceEnumId");
        if (payrollRunId.equals(alreadyProcessedRunId) && "LNRS_PAYROLL".equals(alreadyRepaymentSourceEnumId)) {
            continue;
        }

        String referenceId = "LOAN:" + loanScheduleId;

        GenericValue existingLine = EntityQuery.use(delegator)
                .select("resultLineId")
                .from("EmployeePayrollResultLine")
                .where("payrollResultId", payrollResultId,
                       "lineTypeId", RL_DED,
                       "referenceId", referenceId)
                .queryFirst();

        if (existingLine != null) {
            continue;
        }

        BigDecimal amount = money(firstNonNullBigDecimal(
                dueRow.getBigDecimal("scheduleOutstandingAmount"),
                dueRow.getBigDecimal("scheduleInstallmentAmount"),
                currentOutstanding
        ));

        if (amount.compareTo(currentOutstanding) > 0) {
            amount = currentOutstanding;
        }
        if (amount.compareTo(BigDecimal.ZERO) <= 0) {
            continue;
        }

        if (remainingNetAvailableForLoans.compareTo(BigDecimal.ZERO) <= 0) {
            continue;
        }

        GenericValue loan = EntityQuery.use(delegator)
                .select("allowPartialPaymentFlag")
                .from("PartyLoan")
                .where("loanId", loanId)
                .queryOne();

        String allowPartialPaymentFlag = loan != null ? normalizeFlag(loan.getString("allowPartialPaymentFlag")) : "N";

        BigDecimal amountToApply = amount;
        if (remainingNetAvailableForLoans.compareTo(amountToApply) < 0) {
            if ("Y".equals(allowPartialPaymentFlag)) {
                amountToApply = money(remainingNetAvailableForLoans);
            } else {
                continue;
            }
        }

        if (amountToApply.compareTo(BigDecimal.ZERO) <= 0) {
            continue;
        }

        Map<String, Object> applyCtx = UtilMisc.<String, Object>toMap(
                "loanId", loanId,
                "loanScheduleId", loanScheduleId,
                "organizationPartyId", organizationPartyId,
                "partyId", partyId,
                "payrollRunId", payrollRunId,
                "payrollResultId", payrollResultId,
                "amountApplied", amountToApply,
                "currencyUomId", currencyUomId,
                "repaymentSourceEnumId", "LNRS_PAYROLL",
                "createPaymentFlag", "Y",
                "paymentTypeId", "CUSTOMER_PAYMENT",
                "paymentMethodTypeId", "EXT_BILLACT",
                "repaymentDate", nowTs,
                "remarks", "Applied from payroll run " + payrollRunId,
                "userLogin", userLogin
        );

        Map<String, Object> applyResp = dispatcher.runSync("recordPartyLoanRepayment", applyCtx);
        if (ServiceUtil.isError(applyResp)) {
            throw new GenericEntityException(
                    "Failed to apply payroll loan deduction for loanId=" + loanId + " loanScheduleId=" + loanScheduleId + ". "
                            + ServiceUtil.getErrorMessage(applyResp)
            );
        }

        Map<String, Object> nestedData = null;
        Object nestedObj = applyResp.get("data");
        if (nestedObj instanceof Map) {
            @SuppressWarnings("unchecked")
            Map<String, Object> typed = (Map<String, Object>) nestedObj;
            nestedData = typed;
        }

        String duplicateSkipped = nestedData != null ? (String) nestedData.get("duplicateSkipped") : (String) applyResp.get("duplicateSkipped");
        BigDecimal appliedAmount = nestedData != null
                ? money(firstNonNullBigDecimal((BigDecimal) nestedData.get("totalApplied"), amountToApply, BigDecimal.ZERO))
                : amountToApply;

        if ("Y".equals(duplicateSkipped) || appliedAmount.compareTo(BigDecimal.ZERO) <= 0) {
            continue;
        }

        // Re-read fresh schedule after repayment service updated balances.
        // Only stamp payroll traceability + normalize status using refreshed values.
        GenericValue refreshedSchedule = EntityQuery.use(delegator)
                .from("PartyLoanSchedule")
                .where("loanScheduleId", loanScheduleId)
                .queryOne();

        if (refreshedSchedule == null) {
            throw new GenericEntityException("PartyLoanSchedule not found after repayment apply for loanScheduleId=" + loanScheduleId);
        }

        BigDecimal refreshedOutstanding = money(firstNonNullBigDecimal(
                refreshedSchedule.getBigDecimal("outstandingAmount"),
                BigDecimal.ZERO,
                BigDecimal.ZERO
        ));
        BigDecimal refreshedInstallment = money(firstNonNullBigDecimal(
                refreshedSchedule.getBigDecimal("installmentAmount"),
                dueRow.getBigDecimal("scheduleInstallmentAmount"),
                appliedAmount
        ));
        BigDecimal refreshedPaid = money(firstNonNullBigDecimal(
                refreshedSchedule.getBigDecimal("paidAmount"),
                BigDecimal.ZERO,
                BigDecimal.ZERO
        ));

        if (refreshedOutstanding.compareTo(BigDecimal.ZERO) < 0) {
            refreshedOutstanding = BigDecimal.ZERO;
        }

        String normalizedScheduleStatus;
        if (refreshedOutstanding.compareTo(BigDecimal.ZERO) == 0
                || refreshedPaid.compareTo(refreshedInstallment) >= 0) {
            normalizedScheduleStatus = "LNS_PAID";
        } else if (refreshedPaid.compareTo(BigDecimal.ZERO) > 0) {
            normalizedScheduleStatus = "LNS_PARTIAL";
        } else {
            Timestamp refreshedDueDate = firstNonNullTimestamp(
                    refreshedSchedule.getTimestamp("dueDate"),
                    dueRow.getTimestamp("scheduleDueDate"),
                    null
            );
            normalizedScheduleStatus = (refreshedDueDate != null && !refreshedDueDate.after(nowTs))
                    ? "LNS_DUE"
                    : "LNS_PENDING";
        }

        refreshedSchedule.set("processedPayrollRunId", payrollRunId);
        refreshedSchedule.set("processedPayrollResultId", payrollResultId);
        refreshedSchedule.set("repaymentSourceEnumId", "LNRS_PAYROLL");
        refreshedSchedule.set("scheduleStatusId", normalizedScheduleStatus);
        refreshedSchedule.store();

        remainingNetAvailableForLoans = money(remainingNetAvailableForLoans.subtract(appliedAmount));
        if (remainingNetAvailableForLoans.compareTo(BigDecimal.ZERO) < 0) {
            remainingNetAvailableForLoans = BigDecimal.ZERO;
        }

        String desc = "Loan deduction"
                + (installmentSeqNum != null ? " installment #" + installmentSeqNum : "")
                + " (loanId=" + loanId + ")";

        ResultLineAdapter.createLine(
                delegator,
                payrollResultId,
                currencyUomId,
                seq,
                RL_DED,
                referenceId,
                desc,
                appliedAmount
        );

        out.totalLoanDeductions = out.totalLoanDeductions.add(appliedAmount);
        out.appliedCount++;
        seq++;
    }

    return out;
}

@SuppressWarnings("unchecked")
private static List<GenericValue> extractDueRows(Map<String, Object> serviceResp) {
    Object top = serviceResp.get("dueRows");
    if (top instanceof List) return (List<GenericValue>) top;
    Object dataObj = serviceResp.get("data");
    if (dataObj instanceof Map) {
        Object nested = ((Map<String, Object>) dataObj).get("dueRows");
        if (nested instanceof List) return (List<GenericValue>) nested;
    }
    return Collections.emptyList();
}

private static int compareDueLoanRows(GenericValue a, GenericValue b) {
    int c = compareNullableString(
            a.getString("loanAllocationOrderEnumId"),
            b.getString("loanAllocationOrderEnumId")
    );
    if (c != 0) return c;

    c = compareNullableString(
            a.getString("scheduleDuePayrollPeriodId"),
            b.getString("scheduleDuePayrollPeriodId")
    );
    if (c != 0) return c;

    c = compareNullableTimestamp(
            firstNonNullTimestamp(a.getTimestamp("scheduleDueDate"), null, null),
            firstNonNullTimestamp(b.getTimestamp("scheduleDueDate"), null, null)
    );
    if (c != 0) return c;

    c = compareNullableLong(
            firstNonNullLong(a.getLong("scheduleInstallmentSeqNum"), null),
            firstNonNullLong(b.getLong("scheduleInstallmentSeqNum"), null)
    );
    if (c != 0) return c;

    c = compareNullableString(
            a.getString("loanLoanId"),
            b.getString("loanLoanId")
    );
    if (c != 0) return c;

    return compareNullableString(
            a.getString("scheduleLoanScheduleId"),
            b.getString("scheduleLoanScheduleId")
    );
}

private static int compareNullableLong(Long a, Long b) {
    if (a == null && b == null) return 0;
    if (a == null) return 1;
    if (b == null) return -1;
    return a.compareTo(b);
}

private static int compareNullableString(String a, String b) {
    if (a == null && b == null) return 0;
    if (a == null) return 1;
    if (b == null) return -1;
    return a.compareTo(b);
}

private static int compareNullableTimestamp(Timestamp a, Timestamp b) {
    if (a == null && b == null) return 0;
    if (a == null) return 1;
    if (b == null) return -1;
    return a.compareTo(b);
}

private static Long firstNonNullLong(Long a, Long b) {
    return a != null ? a : b;
}

private static Timestamp firstNonNullTimestamp(Timestamp a, Timestamp b, Timestamp c) {
    if (a != null) return a;
    if (b != null) return b;
    return c;
}

private static String firstNonEmptyString(String... values) {
    if (values == null) return null;
    for (String value : values) {
        if (UtilValidate.isNotEmpty(value)) {
            return value;
        }
    }
    return null;
}

private static BigDecimal firstNonNullBigDecimal(BigDecimal a, BigDecimal b, BigDecimal fallback) {
    if (a != null) return a;
    if (b != null) return b;
    return fallback;
}

    // ---------------- PHASE#2.5: LEAVE/ABSENCE -> DEDUCTION ITEMS ----------------

    /**
     * Creates ABSENCE and UNPAID_LEAVE deduction items directly linked to payrollResultId.
     * Calculation basis (deterministic default):
     * - dailyRate = basicSalary / 30
     * - unpaidLeaveDeduction = dailyRate * leaveDaysApproved
     * - absenceDeduction = dailyRate * (absenceMinutesApproved / 480)
     */
    private static void createLeaveAndAbsenceDeductionsFromSnapshot(Delegator delegator,
                                                                    String payrollResultId,
                                                                    String organizationPartyId,
                                                                    String partyId,
                                                                    BigDecimal basicSalary,
                                                                    CalcInput snapAgg) throws GenericEntityException {
        BigDecimal dailyRate = BigDecimal.ZERO;
        if (nz(basicSalary).compareTo(BigDecimal.ZERO) > 0) {
            dailyRate = nz(basicSalary).divide(DEFAULT_MONTH_DAYS, 6, RoundingMode.HALF_UP);
        }

        BigDecimal leaveDays = nz(snapAgg.leaveDaysApproved);
        BigDecimal absMinutes = nz(snapAgg.absenceMinutesApproved);

        BigDecimal unpaidLeaveDed = money(dailyRate.multiply(leaveDays));
        BigDecimal absenceDed = money(dailyRate.multiply(absMinutes.divide(MINUTES_PER_WORKDAY, 6, RoundingMode.HALF_UP)));

        if (unpaidLeaveDed.compareTo(BigDecimal.ZERO) > 0) {
            createResultDeduction(delegator, payrollResultId, organizationPartyId, partyId, DED_UNPAID_LEAVE, unpaidLeaveDed);
        }
        if (absenceDed.compareTo(BigDecimal.ZERO) > 0) {
            createResultDeduction(delegator, payrollResultId, organizationPartyId, partyId, DED_ABSENCE, absenceDed);
        }
    }

    private static void createResultDeduction(Delegator delegator,
                                              String payrollResultId,
                                              String organizationPartyId,
                                              String partyId,
                                              String deductionTypeId,
                                              BigDecimal amount) throws GenericEntityException {
        if (amount == null || amount.compareTo(BigDecimal.ZERO) <= 0) return;

        String id = delegator.getNextSeqId("PayrollDeductionItem");
        GenericValue di = delegator.makeValue("PayrollDeductionItem", UtilMisc.toMap(
                "payrollDeductionItemId", id,
                "payrollResultId", payrollResultId,
                "deductionTypeId", deductionTypeId,
                "amount", amount,
                "partyId", partyId,
                "organizationPartyId", organizationPartyId
        ));
        di.create();
    }

    // ---------------- PHASE#2.5: ADDITIONS (pending attach) ----------------

    private static class AdditionTotals {
        BigDecimal totalAdditions = BigDecimal.ZERO;
        int attachedCount = 0;
    }

    /**
     * Attach pending rows in PAYROLL_ADDITION_ITEM to payrollResultId, and write PRL_ADD lines.
     * Pending means payrollResultId IS NULL OR payrollResultId = ''.
     *
     * FK note: EmployeePayrollResult must already exist before this runs.
     */
    private static AdditionTotals applyPendingAdditionsAndAttach(Delegator delegator,
                                                                String payrollResultId,
                                                                String organizationPartyId,
                                                                String payrollRunId,
                                                                String partyId,
                                                                String currencyUomId) throws GenericEntityException {
        AdditionTotals out = new AdditionTotals();

        EntityCondition pendingCond = EntityCondition.makeCondition(
                EntityOperator.AND,
                EntityCondition.makeCondition("organizationPartyId", EntityOperator.EQUALS, organizationPartyId),
                EntityCondition.makeCondition("partyId", EntityOperator.EQUALS, partyId),
                EntityCondition.makeCondition("payrollRunId", EntityOperator.EQUALS, payrollRunId),
                EntityCondition.makeCondition(
                        EntityOperator.OR,
                        EntityCondition.makeCondition("payrollResultId", EntityOperator.EQUALS, null),
                        EntityCondition.makeCondition("payrollResultId", EntityOperator.EQUALS, "")
                )
        );

        List<GenericValue> pending = EntityQuery.use(delegator)
                .from("PayrollAdditionItem")
                .where(pendingCond)
                .queryList();

        if (pending == null || pending.isEmpty()) return out;

        int seq = 900;
        for (GenericValue a : pending) {
            BigDecimal amt = money(toBd(a.get("amount")));
            if (amt.compareTo(BigDecimal.ZERO) == 0) continue;

            String addTypeId = a.getString("additionTypeId");

            // Attach to result (FK-safe)
            a.set("payrollResultId", payrollResultId);
            a.store();

            // Description from AdditionType if exists
            String desc = "Addition " + addTypeId;
            try {
                GenericValue t = EntityQuery.use(delegator).from("AdditionType")
                        .where("additionTypeId", addTypeId).queryOne();
                if (t != null && UtilValidate.isNotEmpty(t.getString("description"))) {
                    desc = t.getString("description");
                }
            } catch (Exception ignored) {}

            // Write result line
            ResultLineAdapter.createLine(delegator, payrollResultId, currencyUomId, seq,
                    RL_ADD,
                    a.getString("payrollAdditionItemId"),
                    desc,
                    amt);

            out.totalAdditions = out.totalAdditions.add(amt);
            out.attachedCount++;
            seq += 1;
        }
        return out;
    }

    // ---------------- PURGE ----------------

    /**
     * Purge results for run:
     * - delete EmployeePayrollResultLine by payrollResultId
     * - delete PayrollDeductionItem by payrollResultId (FK safety)
     * - delete PayrollAdditionItem links by payrollResultId (optional but recommended)
     * - delete EmployeePayrollResult by payrollRunId
     */
    public static Map<String, Object> payrollPurgeResultsForRunV2(DispatchContext dctx, Map<String, Object> context) {
        Delegator delegator = dctx.getDelegator();
        String payrollRunId = (String) context.get("payrollRunId");

        Map<String, Object> data = new HashMap<>();
        Map<String, Object> legacyData = new HashMap<>();

        if (UtilValidate.isEmpty(payrollRunId)) {
            return errorNonNull("payrollRunId is required", data, legacyData);
        }

        boolean beganTx = false;
        try {
            beganTx = TransactionUtil.begin(3600);

            List<GenericValue> results = EntityQuery.use(delegator)
                    .from("EmployeePayrollResult")
                    .where("payrollRunId", payrollRunId)
                    .queryList();

            long deletedLines = 0;
            long deletedHeaders = results.size();
            long deletedDeductions = 0;
            long detachedAdditions = 0;

            for (GenericValue r : results) {
                String payrollResultId = r.getString("payrollResultId");
                if (UtilValidate.isEmpty(payrollResultId)) continue;

                long cnt = EntityQuery.use(delegator)
                        .from("EmployeePayrollResultLine")
                        .where("payrollResultId", payrollResultId)
                        .queryCount();
                deletedLines += cnt;
                delegator.removeByAnd("EmployeePayrollResultLine", UtilMisc.toMap("payrollResultId", payrollResultId));

                long dcnt = EntityQuery.use(delegator)
                        .from("PayrollDeductionItem")
                        .where("payrollResultId", payrollResultId)
                        .queryCount();
                deletedDeductions += dcnt;
                delegator.removeByAnd("PayrollDeductionItem", UtilMisc.toMap("payrollResultId", payrollResultId));

                // Detach additions so they can be re-attached on next run when forceReprocess=Y
                List<GenericValue> adds = EntityQuery.use(delegator)
                        .from("PayrollAdditionItem")
                        .where("payrollResultId", payrollResultId)
                        .queryList();
                for (GenericValue a : adds) {
                    a.set("payrollResultId", null);
                    a.store();
                    detachedAdditions++;
                }
            }

            delegator.removeByAnd("EmployeePayrollResult", UtilMisc.toMap("payrollRunId", payrollRunId));

            data.put("payrollRunId", payrollRunId);
            data.put("deletedHeaders", deletedHeaders);
            data.put("deletedLines", deletedLines);
            data.put("deletedDeductionItemsLinked", deletedDeductions);
            data.put("detachedAdditionItems", detachedAdditions);
            legacyData.putAll(data);

            Map<String, Object> ok = ServiceUtil.returnSuccess("Purged results for run " + payrollRunId);
            ok.put("data", data);
            ok.put("legacyData", legacyData);

            TransactionUtil.commit(beganTx);
            return ok;

        } catch (Exception e) {
            Debug.logError(e, "payrollPurgeResultsForRunV2 failed", module);
            try { TransactionUtil.rollback(beganTx, e.getMessage(), e); } catch (Exception ignored) {}
            return errorNonNull("payrollPurgeResultsForRunV2 failed: " + e.getMessage(), data, legacyData);
        }
    }

    // ---------------- DEDUCTIONS (pending attach) ----------------
/*
    private static class DeductionTotals {
        BigDecimal socialSecurity = BigDecimal.ZERO;
        BigDecimal loan = BigDecimal.ZERO;
        BigDecimal other = BigDecimal.ZERO;
    }
*/
    /**
     * Attach pending rows in PAYROLL_DEDUCTION_ITEM to payrollResultId, then bucket totals.
     * Pending means payrollResultId IS NULL OR payrollResultId = ''.
     *
     * FK note: EmployeePayrollResult must already exist before this runs.
     */
 /*DeductionTotals dedTotals = applyDeductionsAndAttach(
        delegator,
        payrollResultId,
        organizationPartyId,
        payrollPeriodId,
        partyId,
        currencyUomId
);*/
    // ---------------- TAX (PayrollTaxConfig) ----------------

    private static BigDecimal computeIncomeTaxFromConfig(Delegator delegator,
                                                        String organizationPartyId,
                                                        String taxTypeId,
                                                        BigDecimal income,
                                                        String currencyUomId) throws GenericEntityException {
        if (UtilValidate.isEmpty(taxTypeId)) return BigDecimal.ZERO;
        if (income == null || income.compareTo(BigDecimal.ZERO) <= 0) return BigDecimal.ZERO;

        java.sql.Date today = new java.sql.Date(UtilDateTime.nowTimestamp().getTime());

        List<GenericValue> cfg = EntityQuery.use(delegator)
                .from("PayrollTaxConfig")
                .where("organizationPartyId", organizationPartyId,
                       "taxTypeId", taxTypeId)
                .queryList();

        if (cfg == null || cfg.isEmpty()) return BigDecimal.ZERO;

        List<GenericValue> rows = new ArrayList<>();
        for (GenericValue r : cfg) {
            String st = r.getString("statusId");
            if (UtilValidate.isNotEmpty(st) && !"P_ACT".equals(st)) continue;

            java.sql.Date from = r.getDate("effectiveFromDate");
            java.sql.Date thru = r.getDate("effectiveThruDate");
            if (from != null && from.after(today)) continue;
            if (thru != null && !thru.after(today)) continue;

            String rowCurr = r.getString("currencyUomId");
            if (UtilValidate.isNotEmpty(rowCurr) && UtilValidate.isNotEmpty(currencyUomId) && !rowCurr.equals(currencyUomId)) {
                continue;
            }
            rows.add(r);
        }
        if (rows.isEmpty()) return BigDecimal.ZERO;

        rows.sort((a, b) -> toBd(a.get("minIncome")).compareTo(toBd(b.get("minIncome"))));

        GenericValue chosen = null;
        for (GenericValue r : rows) {
            BigDecimal min = toBd(r.get("minIncome"));
            BigDecimal max = toBd(r.get("maxIncome"));
            boolean maxOpen = (max == null || max.compareTo(BigDecimal.ZERO) == 0);

            if (income.compareTo(min) >= 0 && (maxOpen || income.compareTo(max) <= 0)) {
                chosen = r;
            }
        }
        if (chosen == null) chosen = rows.get(rows.size() - 1);

        Double rateObj = chosen.getDouble("taxRate");
        BigDecimal rate = rateObj == null ? BigDecimal.ZERO : new BigDecimal(Double.toString(rateObj));
        BigDecimal add = toBd(chosen.get("additionalAmount"));

        BigDecimal tax = income.multiply(rate).divide(BD_100, 6, RoundingMode.HALF_UP).add(nz(add));
        return money(tax);
    }

    private static String resolveDefaultIncomeTaxTypeId(Delegator delegator, String organizationPartyId) throws GenericEntityException {
        // If you do not have PayrollTaxType entity in your plugin, keep this as null and pass taxTypeId explicitly.
        List<GenericValue> types = EntityQuery.use(delegator)
                .from("PayrollTaxType")
                .where("organizationPartyId", organizationPartyId,
                       "isActive", "Y")
                .queryList();

        if (types == null || types.isEmpty()) return null;

        for (GenericValue t : types) {
            if ("INC".equalsIgnoreCase(t.getString("taxTypeCode"))) {
                return t.getString("taxTypeId");
            }
        }
        return types.get(0).getString("taxTypeId");
    }

    // ---------------- Snapshot aggregation ----------------

    private static class CalcInput {
        BigDecimal scheduledMinutes = BigDecimal.ZERO;
        BigDecimal workedMinutes = BigDecimal.ZERO;
        BigDecimal overtimeMinutesApproved = BigDecimal.ZERO;
        BigDecimal lateMinutes = BigDecimal.ZERO;
        BigDecimal earlyLeaveMinutes = BigDecimal.ZERO;
        BigDecimal absenceMinutesApproved = BigDecimal.ZERO;
        BigDecimal leaveDaysApproved = BigDecimal.ZERO;
    }

    private static class SnapshotAdapter {
        static List<String> listEmployeesFromSnapshot(Delegator delegator, String payrollRunId, String snapshotStatusId)
                throws GenericEntityException {

            List<GenericValue> rows = EntityQuery.use(delegator)
                    .from("PayrollInputSnapshot")
                    .where("payrollRunId", payrollRunId,
                           "snapshotStatusId", snapshotStatusId)
                    .queryList();

            Set<String> ids = new HashSet<>();
            for (GenericValue r : rows) {
                String partyId = r.getString("partyId");
                if (UtilValidate.isNotEmpty(partyId)) ids.add(partyId);
            }
            return new ArrayList<>(ids);
        }

        static CalcInput aggregateSnapshotForEmployee(Delegator delegator, String payrollRunId, String snapshotStatusId, String partyId)
                throws GenericEntityException {

            List<GenericValue> rows = EntityQuery.use(delegator)
                    .from("PayrollInputSnapshot")
                    .where("payrollRunId", payrollRunId,
                           "snapshotStatusId", snapshotStatusId,
                           "partyId", partyId)
                    .queryList();

            CalcInput in = new CalcInput();
            for (GenericValue r : rows) {
                in.scheduledMinutes        = in.scheduledMinutes.add(toBd(r.get("scheduledMinutes")));
                in.workedMinutes           = in.workedMinutes.add(toBd(r.get("workedMinutes")));
                in.overtimeMinutesApproved = in.overtimeMinutesApproved.add(toBd(r.get("overtimeMinutesApproved")));
                in.lateMinutes             = in.lateMinutes.add(toBd(r.get("lateMinutes")));
                in.earlyLeaveMinutes       = in.earlyLeaveMinutes.add(toBd(r.get("earlyLeaveMinutes")));
                in.absenceMinutesApproved  = in.absenceMinutesApproved.add(toBd(r.get("absenceMinutesApproved")));
                in.leaveDaysApproved       = in.leaveDaysApproved.add(toBd(r.get("leaveDaysApproved")));
            }
            return in;
        }
    }

    // ---------------- PayrollStructure selection + items + OT policy ----------------

    private static class StructureAdapter {

        static GenericValue resolveStructureForEmployee(Delegator delegator, String organizationPartyId, String partyId)
                throws GenericEntityException {

            List<GenericValue> mem = EntityQuery.use(delegator)
                    .from("PayrollStructureMember")
                    .where("organizationPartyId", organizationPartyId,
                           "partyId", partyId)
                    .orderBy("-fromDate")
                    .queryList();

            Timestamp now = UtilDateTime.nowTimestamp();
            for (GenericValue m : mem) {
                Timestamp from = (Timestamp) m.get("fromDate");
                Timestamp thru = (Timestamp) m.get("thruDate");
                if (from != null && from.after(now)) continue;
                if (thru != null && !thru.after(now)) continue;

                String st = m.getString("statusId");
                if (UtilValidate.isNotEmpty(st) && !PAYSTRM_ACT.equals(st)) continue;

                String structureId = m.getString("payrollStructureId");
                if (UtilValidate.isEmpty(structureId)) continue;

                GenericValue s = EntityQuery.use(delegator)
                        .from("PayrollStructure")
                        .where("payrollStructureId", structureId,
                               "organizationPartyId", organizationPartyId)
                        .queryOne();

                if (s != null) return s;
            }

            return resolveActiveStructureForOrg(delegator, organizationPartyId);
        }

        static GenericValue resolveActiveStructureForOrg(Delegator delegator, String organizationPartyId) throws GenericEntityException {
            List<GenericValue> list = EntityQuery.use(delegator)
                    .from("PayrollStructure")
                    .where("organizationPartyId", organizationPartyId)
                    .orderBy("-fromDate")
                    .queryList();

            if (list == null || list.isEmpty()) return null;

            Timestamp now = UtilDateTime.nowTimestamp();
            for (GenericValue s : list) {
                Timestamp from = (Timestamp) s.get("fromDate");
                Timestamp thru = (Timestamp) s.get("thruDate");
                if (from != null && from.after(now)) continue;
                if (thru != null && !thru.after(now)) continue;
                return s;
            }
            return list.get(0);
        }

        static List<GenericValue> listActiveItems(Delegator delegator, String payrollStructureId) throws GenericEntityException {
            return EntityQuery.use(delegator)
                    .from("PayrollStructureItem")
                    .where("payrollStructureId", payrollStructureId,
                           "statusId", PAYSTRI_ACT)
                    .orderBy("sequenceNum")
                    .queryList();
        }

        static BigDecimal resolveNormalOtMultiplier(Delegator delegator, GenericValue payrollStructure) throws GenericEntityException {
            if (payrollStructure == null) return BigDecimal.ONE;

            String otpId = payrollStructure.getString("overtimePolicyId");
            if (UtilValidate.isEmpty(otpId)) return BigDecimal.ONE;

            GenericValue pol = EntityQuery.use(delegator)
                    .from("OvertimePolicy")
                    .where("overtimePolicyId", otpId)
                    .queryOne();

            if (pol == null) return BigDecimal.ONE;

            BigDecimal m = toBd(pol.get("normalMultiplier"));
            if (m.compareTo(BigDecimal.ZERO) <= 0) return BigDecimal.ONE;
            return m;
        }
    }

    // ---------------- Result line writer ----------------

    private static class ResultLineAdapter {
        static void createLine(Delegator delegator,
                               String payrollResultId,
                               String currencyUomId,
                               int seq,
                               String lineTypeId,
                               String referenceId,
                               String desc,
                               BigDecimal amount) {

            String lineId = delegator.getNextSeqId("EmployeePayrollResultLine");
            GenericValue line = delegator.makeValue("EmployeePayrollResultLine", UtilMisc.toMap(
                    "resultLineId", lineId,
                    "payrollResultId", payrollResultId,
                    "lineTypeId", lineTypeId,
                    "referenceId", referenceId,
                    "description", desc,
                    "amount", amount == null ? BigDecimal.ZERO : amount,
                    "currencyUomId", currencyUomId,
                    "sequenceNum", new BigDecimal(seq)
            ));
            try {
                line.create();
            } catch (Exception e) {
                throw new RuntimeException("Failed creating EmployeePayrollResultLine, lineTypeId=" + lineTypeId
                        + " ref=" + referenceId + " desc=" + desc + " : " + e.getMessage(), e);
            }
        }
    }

    // ---------------- Utility / DB guards ----------------

    private static boolean uomExists(Delegator delegator, String uomId) throws GenericEntityException {
        if (UtilValidate.isEmpty(uomId)) return false;
        return EntityQuery.use(delegator).from("Uom").where("uomId", uomId).queryCount() > 0;
    }

    private static BigDecimal toBd(Object v) {
        if (v == null) return BigDecimal.ZERO;
        if (v instanceof BigDecimal) return (BigDecimal) v;
        if (v instanceof Number) return new BigDecimal(((Number) v).toString());
        if (v instanceof String && UtilValidate.isNotEmpty((String) v)) return new BigDecimal((String) v);
        return BigDecimal.ZERO;
    }

    private static BigDecimal nz(BigDecimal v) { return v == null ? BigDecimal.ZERO : v; }

    private static BigDecimal money(BigDecimal v) {
        v = nz(v);
        return v.setScale(MONEY_SCALE, RoundingMode.HALF_UP);
    }

    private static int toInt(Object v, int def) {
        if (v == null) return def;
        if (v instanceof Number) return ((Number) v).intValue();
        try { return Integer.parseInt(v.toString()); } catch (Exception ignored) { return def; }
    }

    // ---------------- Non-null response utilities ----------------

    private static Map<String, Object> errorNonNull(String msg, Map<String, Object> data, Map<String, Object> legacyData) {
        Map<String, Object> res = ServiceUtil.returnError(msg);
        if (data == null) data = new HashMap<>();
        if (legacyData == null) legacyData = new HashMap<>();
        data.putIfAbsent("errorMessage", msg);
        legacyData.putIfAbsent("errorMessage", msg);
        res.put("data", data);
        res.put("legacyData", legacyData);
        return res;
    }

    private static String normalizeFlag(String v) {
        if (UtilValidate.isEmpty(v)) return "N";
        v = v.trim().toUpperCase(Locale.ROOT);
        return ("Y".equals(v) || "TRUE".equals(v) || "1".equals(v)) ? "Y" : "N";
    }

    private static void auditBestEffort(LocalDispatcher dispatcher,
                                        GenericValue userLogin,
                                        String organizationPartyId,
                                        String payrollRunId,
                                        String payrollPeriodId,
                                        String actionTypeId,
                                        String actionStatusId,
                                        String messageText) {
        if (dispatcher == null || userLogin == null) {
            return;
        }
        try {
            Map<String, Object> ctx = new HashMap<>();
            ctx.put("organizationPartyId", organizationPartyId);
            ctx.put("payrollRunId", payrollRunId);
            ctx.put("payrollPeriodId", payrollPeriodId);
            ctx.put("actionTypeId", actionTypeId);
            ctx.put("actionStatusId", actionStatusId);
            ctx.put("messageText", messageText);
            ctx.put("userLogin", userLogin);
            dispatcher.runSync("createPayrollActionAudit", ctx);
        } catch (Exception e) {
            Debug.logWarning(e, "Best-effort payroll audit failed for actionTypeId=" + actionTypeId, module);
        }
    }


}
