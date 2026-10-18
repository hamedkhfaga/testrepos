package org.apache.ofbiz.payroll.acctg;

import org.apache.ofbiz.base.util.Debug;
import org.apache.ofbiz.base.util.UtilDateTime;
import org.apache.ofbiz.base.util.UtilMisc;
import org.apache.ofbiz.base.util.UtilValidate;
import org.apache.ofbiz.entity.Delegator;
import org.apache.ofbiz.entity.GenericEntityException;
import org.apache.ofbiz.entity.GenericValue;
import org.apache.ofbiz.entity.condition.EntityCondition;
import org.apache.ofbiz.entity.condition.EntityOperator;
import org.apache.ofbiz.entity.transaction.TransactionUtil;
import org.apache.ofbiz.entity.util.EntityQuery;
import org.apache.ofbiz.service.DispatchContext;
import org.apache.ofbiz.service.LocalDispatcher;
import org.apache.ofbiz.service.ServiceUtil;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.sql.Timestamp;
import java.util.*;

/**
 * Payroll Accounting Service
 *
 * Works with FINAL tables:
 * - PayrollIteration (header)
 * - EmployeePayrollResult (details)
 *
 * Integrates to OFBiz accounting:
 * - Pattern A: creates AcctgTrans + AcctgTransEntry, tracks in PayrollAcctgPosting
 * - Pattern B: creates PayrollPayRun + PayrollPayRunItem (optionally later link to Payment/Invoice)
 *
 * Uses entities (your acctg entity model):
 * - PayrollAcctgConfig
 * - PayrollGlMap
 * - PayrollAcctgPosting
 * - PayrollPayConfig
 * - PayrollPayRun
 * - PayrollPayRunItem
 *
 * NOTE:
 * - No index definitions here (entity model controls PK/FK).
 * - Status IDs are StatusItem.statusId (strings), not enums.
 */
public class PayrollAccountingService {

    public static final String MODULE = PayrollAccountingService.class.getName();

    // -------------------------------
    // StatusItem / Pattern constants
    // (These must exist in StatusItem seed data)
    // -------------------------------
    public static final String PATTERN_A_GL = "PAY_PAT_A";
    public static final String PATTERN_B_PAY = "PAY_PAT_B";

    public static final String POST_LEVEL_SUMMARY = "POST_SUM";
    public static final String POST_LEVEL_EMPLOYEE = "POST_EMP";

    public static final String POSTING_STATUS_POSTED = "POSTED";
    public static final String POSTING_STATUS_ERROR = "POST_ERR";
    public static final String POSTING_STATUS_REVERSED = "POST_REV";

    public static final String PAYRUN_STATUS_NEW = "RUN_NEW";
    public static final String PAYRUN_STATUS_GENERATED = "RUN_GEN";
    public static final String PAYRUN_STATUS_ERROR = "RUN_ERR";

    public static final String PAYRUN_ITEM_STATUS_NEW = "ITEM_NEW";
    public static final String PAYRUN_ITEM_STATUS_GENERATED = "ITEM_GEN";
    public static final String PAYRUN_ITEM_STATUS_ERROR = "ITEM_ERR";

    // Component types for PayrollGlMap.componentTypeStatusId
    public static final String CMP_BASIC = "CMP_BASIC";
    public static final String CMP_OT = "CMP_OT";
    public static final String CMP_ALW = "CMP_ALW";
    public static final String CMP_BONUS = "CMP_BONUS";
    public static final String CMP_TAX = "CMP_TAX";
    public static final String CMP_SS = "CMP_SS";
    public static final String CMP_LOAN = "CMP_LOAN";
    public static final String CMP_NET = "CMP_NET";

    // Debit / Credit flags
    public static final String DR = "D";
    public static final String CR = "C";

    private static BigDecimal nvl(BigDecimal v) {
        return v == null ? BigDecimal.ZERO : v;
    }

    private static BigDecimal scale2(BigDecimal v) {
        return nvl(v).setScale(2, RoundingMode.HALF_UP);
    }

    // ============================================================
    // 1) ROUTER: Dispatch accounting by selected pattern A/B
    // ============================================================
    public static Map<String, Object> dispatchPayrollAccounting(DispatchContext dctx, Map<String, ?> context) {
        Delegator delegator = dctx.getDelegator();
        LocalDispatcher dispatcher = dctx.getDispatcher();
        GenericValue userLogin = (GenericValue) context.get("userLogin");

        try {
            String payrollIterationId = (String) context.get("payrollIterationId");
            if (UtilValidate.isEmpty(payrollIterationId)) {
                // allow old param name (some callers used payrollRunId)
                payrollIterationId = (String) context.get("payrollRunId");
            }
            if (UtilValidate.isEmpty(payrollIterationId)) {
                return ServiceUtil.returnError("payrollIterationId is required");
            }
            if (userLogin == null) {
                return ServiceUtil.returnError("userLogin is required");
            }

            GenericValue iteration = EntityQuery.use(delegator)
                    .from("PayrollIteration")
                    .where("payrollIterationId", payrollIterationId)
                    .queryOne();

            if (iteration == null) {
                return ServiceUtil.returnError("PayrollIteration not found: " + payrollIterationId);
            }

            String organizationPartyId = iteration.getString("organizationPartyId");
            if (UtilValidate.isEmpty(organizationPartyId)) {
                return ServiceUtil.returnError("PayrollIteration.organizationPartyId is required for accounting routing");
            }

            // Optional override: payrollStructureId passed from UI
            String payrollStructureId = (String) context.get("payrollStructureId");

            GenericValue acctgConfig = findActivePayrollAcctgConfig(delegator, organizationPartyId, payrollStructureId, UtilDateTime.nowTimestamp());
            if (acctgConfig == null) {
                return ServiceUtil.returnError("No active PayrollAcctgConfig found for org=" + organizationPartyId
                        + (UtilValidate.isNotEmpty(payrollStructureId) ? (" structure=" + payrollStructureId) : "")
                        + ". Please seed/enable PayrollAcctgConfig.");
            }

            String patternStatusId = acctgConfig.getString("acctgPatternStatusId");
            if (UtilValidate.isEmpty(patternStatusId)) {
                return ServiceUtil.returnError("PayrollAcctgConfig.acctgPatternStatusId is required");
            }

            Map<String, Object> svcCtx = new HashMap<>();
            svcCtx.put("payrollIterationId", payrollIterationId);
            svcCtx.put("organizationPartyId", organizationPartyId);
            svcCtx.put("payrollStructureId", payrollStructureId);
            svcCtx.put("userLogin", userLogin);

            if (PATTERN_A_GL.equals(patternStatusId)) {
                return dispatcher.runSync("postPayrollAccountingPatternA", svcCtx);
            } else if (PATTERN_B_PAY.equals(patternStatusId)) {
                return dispatcher.runSync("generatePayrollPayRunPatternB", svcCtx);
            } else {
                return ServiceUtil.returnError("Unsupported accounting pattern: " + patternStatusId
                        + " (expected " + PATTERN_A_GL + " or " + PATTERN_B_PAY + ")");
            }

        } catch (Exception e) {
            Debug.logError(e, "dispatchPayrollAccounting failed", MODULE);
            return ServiceUtil.returnError("dispatchPayrollAccounting failed: " + e.getMessage());
        }
    }

    // ============================================================
    // 2) PATTERN A: GL Posting (AcctgTrans) + PayrollAcctgPosting
    // ============================================================
    public static Map<String, Object> postPayrollAccountingPatternA(DispatchContext dctx, Map<String, ?> context) {
        Delegator delegator = dctx.getDelegator();
        GenericValue userLogin = (GenericValue) context.get("userLogin");

        boolean beganTx = false;
        try {
            String payrollIterationId = (String) context.get("payrollIterationId");
            String organizationPartyId = (String) context.get("organizationPartyId");
            String payrollStructureId = (String) context.get("payrollStructureId"); // optional

            if (UtilValidate.isEmpty(payrollIterationId)) return ServiceUtil.returnError("payrollIterationId is required");
            if (UtilValidate.isEmpty(organizationPartyId)) return ServiceUtil.returnError("organizationPartyId is required");
            if (userLogin == null) return ServiceUtil.returnError("userLogin is required");

            GenericValue iteration = EntityQuery.use(delegator)
                    .from("PayrollIteration")
                    .where("payrollIterationId", payrollIterationId)
                    .queryOne();
            if (iteration == null) {
                return ServiceUtil.returnError("PayrollIteration not found: " + payrollIterationId);
            }

            Timestamp now = UtilDateTime.nowTimestamp();

            GenericValue acctgConfig = findActivePayrollAcctgConfig(delegator, organizationPartyId, payrollStructureId, now);
            if (acctgConfig == null) {
                return ServiceUtil.returnError("No active PayrollAcctgConfig found for Pattern A (org=" + organizationPartyId + ")");
            }

            // prevent duplicate posting if already posted
            GenericValue existingPosting = EntityQuery.use(delegator)
                    .from("PayrollAcctgPosting")
                    .where("organizationPartyId", organizationPartyId, "payrollIterationId", payrollIterationId)
                    .queryFirst();

            if (existingPosting != null && POSTING_STATUS_POSTED.equals(existingPosting.getString("statusId"))) {
                Map<String, Object> ok = ServiceUtil.returnSuccess("PayrollIteration already posted (Pattern A)");
                ok.put("postingId", existingPosting.getString("postingId"));
                ok.put("acctgTransId", existingPosting.getString("acctgTransId"));
                return ok;
            }

            beganTx = TransactionUtil.begin();

            // Create posting row first (so errors can be recorded deterministically)
            GenericValue posting = existingPosting != null ? existingPosting : delegator.makeValue("PayrollAcctgPosting");
            if (existingPosting == null) {
                posting.set("postingId", delegator.getNextSeqId("PayrollAcctgPosting"));
                posting.set("organizationPartyId", organizationPartyId);
                posting.set("payrollIterationId", payrollIterationId);
                posting.set("statusId", POSTING_STATUS_ERROR); // temporary until success
                delegator.create(posting);
            } else {
                posting.set("statusId", POSTING_STATUS_ERROR);
                posting.set("errorMessage", null);
                delegator.store(posting);
            }

            String postingLevelStatusId = acctgConfig.getString("postingLevelStatusId");
            if (UtilValidate.isEmpty(postingLevelStatusId)) {
                postingLevelStatusId = POST_LEVEL_SUMMARY; // safe default
            }

            // Load employee results
            List<GenericValue> results = EntityQuery.use(delegator)
                    .from("EmployeePayrollResult")
                    .where("payrollIterationId", payrollIterationId, "organizationPartyId", organizationPartyId)
                    .queryList();

            if (UtilValidate.isEmpty(results)) {
                throw new GenericEntityException("No EmployeePayrollResult found for payrollIterationId=" + payrollIterationId);
            }

            // Determine accounting transaction date: use iteration payDate if set else now
            Timestamp transDate = iteration.getTimestamp("payDate");
            if (transDate == null) transDate = now;

            // Create one AcctgTrans header (summary or employee-level entries both can share the header)
            String acctgTransId = delegator.getNextSeqId("AcctgTrans");
            GenericValue acctgTrans = delegator.makeValue("AcctgTrans");
            acctgTrans.set("acctgTransId", acctgTransId);
            acctgTrans.set("acctgTransTypeId", UtilValidate.isNotEmpty(acctgConfig.getString("acctgTransTypeId"))
                    ? acctgConfig.getString("acctgTransTypeId")
                    : "PAYROLL");
            acctgTrans.set("transactionDate", transDate);
            acctgTrans.set("description", "Payroll Posting (Iteration " + payrollIterationId + ")");
            acctgTrans.set("isPosted", "N");
            delegator.create(acctgTrans);

            // Build amounts
            if (POST_LEVEL_EMPLOYEE.equals(postingLevelStatusId)) {
                // Create entries per employee (still under same AcctgTrans for simplicity)
                for (GenericValue r : results) {
                    Map<String, BigDecimal> comp = buildComponentAmountsFromResult(r);
                    createAcctgEntriesForComponents(delegator, acctgConfig, organizationPartyId, payrollStructureId, acctgTransId, comp,
                            "Emp " + r.getString("partyId") + " Iter " + payrollIterationId);
                }
            } else {
                // Summary posting: aggregate totals across all employees
                Map<String, BigDecimal> agg = new LinkedHashMap<>();
                agg.put(CMP_BASIC, BigDecimal.ZERO);
                agg.put(CMP_OT, BigDecimal.ZERO);
                agg.put(CMP_ALW, BigDecimal.ZERO);
                agg.put(CMP_BONUS, BigDecimal.ZERO);
                agg.put(CMP_TAX, BigDecimal.ZERO);
                agg.put(CMP_SS, BigDecimal.ZERO);
                agg.put(CMP_LOAN, BigDecimal.ZERO);
                agg.put(CMP_NET, BigDecimal.ZERO);

                for (GenericValue r : results) {
                    Map<String, BigDecimal> comp = buildComponentAmountsFromResult(r);
                    for (Map.Entry<String, BigDecimal> e : comp.entrySet()) {
                        agg.put(e.getKey(), nvl(agg.get(e.getKey())).add(nvl(e.getValue())));
                    }
                }

                createAcctgEntriesForComponents(delegator, acctgConfig, organizationPartyId, payrollStructureId, acctgTransId, agg,
                        "Summary Iter " + payrollIterationId);
            }

            // Mark posting success
            posting.set("acctgTransId", acctgTransId);
            posting.set("statusId", POSTING_STATUS_POSTED);
            posting.set("postedDate", now);
            posting.set("postedByUserLoginId", userLogin.getString("userLoginId"));
            posting.set("errorMessage", null);
            delegator.store(posting);

            TransactionUtil.commit(beganTx);

            Map<String, Object> ok = ServiceUtil.returnSuccess("PayrollIteration posted to GL (Pattern A)");
            ok.put("postingId", posting.getString("postingId"));
            ok.put("acctgTransId", acctgTransId);
            ok.put("postingLevelStatusId", postingLevelStatusId);
            return ok;

        } catch (Exception e) {
            Debug.logError(e, "postPayrollAccountingPatternA failed", MODULE);
            try { TransactionUtil.rollback(beganTx, e.getMessage(), e); } catch (Exception ignore) {}

            // Best-effort update posting row to error if we can identify it
            try {
                String payrollIterationId = (String) context.get("payrollIterationId");
                String organizationPartyId = (String) context.get("organizationPartyId");
                GenericValue posting = EntityQuery.use(delegator)
                        .from("PayrollAcctgPosting")
                        .where("organizationPartyId", organizationPartyId, "payrollIterationId", payrollIterationId)
                        .queryFirst();
                if (posting != null) {
                    posting.set("statusId", POSTING_STATUS_ERROR);
                    posting.set("errorMessage", e.getMessage());
                    delegator.store(posting);
                }
            } catch (Exception inner) {
                Debug.logWarning(inner, "Failed updating PayrollAcctgPosting error state", MODULE);
            }

            return ServiceUtil.returnError("Pattern A posting failed: " + e.getMessage());
        }
    }

    // ============================================================
    // 3) PATTERN B: Create PayRun (PayrollPayRun/Item)
    // ============================================================
    public static Map<String, Object> generatePayrollPayRunPatternB(DispatchContext dctx, Map<String, ?> context) {
        Delegator delegator = dctx.getDelegator();
        GenericValue userLogin = (GenericValue) context.get("userLogin");

        boolean beganTx = false;
        try {
            String payrollIterationId = (String) context.get("payrollIterationId");
            String organizationPartyId = (String) context.get("organizationPartyId");
            String payrollStructureId = (String) context.get("payrollStructureId"); // optional

            if (UtilValidate.isEmpty(payrollIterationId)) return ServiceUtil.returnError("payrollIterationId is required");
            if (UtilValidate.isEmpty(organizationPartyId)) return ServiceUtil.returnError("organizationPartyId is required");
            if (userLogin == null) return ServiceUtil.returnError("userLogin is required");

            GenericValue iteration = EntityQuery.use(delegator)
                    .from("PayrollIteration")
                    .where("payrollIterationId", payrollIterationId)
                    .queryOne();
            if (iteration == null) {
                return ServiceUtil.returnError("PayrollIteration not found: " + payrollIterationId);
            }

            Timestamp now = UtilDateTime.nowTimestamp();

            // Ensure config exists (so admin control is enforced)
            GenericValue payCfg = findActivePayrollPayConfig(delegator, organizationPartyId, payrollStructureId, now);
            if (payCfg == null) {
                return ServiceUtil.returnError("No active PayrollPayConfig found for Pattern B (org=" + organizationPartyId + "). Please seed/enable PayrollPayConfig.");
            }

            // Prevent duplicates: if a payrun already exists for iteration+org, return it
            GenericValue existingRun = EntityQuery.use(delegator)
                    .from("PayrollPayRun")
                    .where("organizationPartyId", organizationPartyId, "payrollIterationId", payrollIterationId)
                    .queryFirst();
            if (existingRun != null) {
                Map<String, Object> ok = ServiceUtil.returnSuccess("PayRun already exists for this PayrollIteration (Pattern B)");
                ok.put("payRunId", existingRun.getString("payRunId"));
                ok.put("statusId", existingRun.getString("statusId"));
                return ok;
            }

            beganTx = TransactionUtil.begin();

            String payRunId = delegator.getNextSeqId("PayrollPayRun");
            GenericValue payRun = delegator.makeValue("PayrollPayRun");
            payRun.set("payRunId", payRunId);
            payRun.set("organizationPartyId", organizationPartyId);
            payRun.set("payrollIterationId", payrollIterationId);
            payRun.set("statusId", PAYRUN_STATUS_GENERATED);
            payRun.set("createdByUserLoginId", userLogin.getString("userLoginId"));
            payRun.set("createdDate", now);
            delegator.create(payRun);

            // Create items from EmployeePayrollResult netSalary
            List<GenericValue> results = EntityQuery.use(delegator)
                    .from("EmployeePayrollResult")
                    .where("payrollIterationId", payrollIterationId, "organizationPartyId", organizationPartyId)
                    .queryList();

            if (UtilValidate.isEmpty(results)) {
                throw new GenericEntityException("No EmployeePayrollResult found for payrollIterationId=" + payrollIterationId);
            }

            int createdItems = 0;
            for (GenericValue r : results) {
                BigDecimal net = r.getBigDecimal("netSalary");
                if (net == null) net = r.getBigDecimal("netPay"); // safety if older field name exists
                net = scale2(net);

                // Skip zero rows (optional policy) - keep as item if you want audit trail
                // if (net.compareTo(BigDecimal.ZERO) == 0) continue;

                String itemId = delegator.getNextSeqId("PayrollPayRunItem");
                GenericValue item = delegator.makeValue("PayrollPayRunItem");
                item.set("payRunItemId", itemId);
                item.set("payRunId", payRunId);
                item.set("partyId", r.getString("partyId"));
                item.set("amount", net);

                String currencyUomId = r.getString("currencyUomId");
                if (UtilValidate.isEmpty(currencyUomId)) {
                    currencyUomId = iteration.getString("currencyUomId");
                }
                item.set("currencyUomId", currencyUomId);

                item.set("statusId", PAYRUN_ITEM_STATUS_GENERATED);
                delegator.create(item);
                createdItems++;
            }

            TransactionUtil.commit(beganTx);

            Map<String, Object> ok = ServiceUtil.returnSuccess("PayRun generated (Pattern B)");
            ok.put("payRunId", payRunId);
            ok.put("itemsCreated", createdItems);
            ok.put("payMethodTypeId", payCfg.getString("payMethodTypeId"));
            ok.put("paymentTypeId", payCfg.getString("paymentTypeId"));
            ok.put("invoiceTypeId", payCfg.getString("invoiceTypeId"));
            return ok;

        } catch (Exception e) {
            Debug.logError(e, "generatePayrollPayRunPatternB failed", MODULE);
            try { TransactionUtil.rollback(beganTx, e.getMessage(), e); } catch (Exception ignore) {}
            return ServiceUtil.returnError("Pattern B payrun failed: " + e.getMessage());
        }
    }

    // ============================================================
    // Helpers: Config lookup (active by date, org, optional structure)
    // ============================================================
    private static GenericValue findActivePayrollAcctgConfig(Delegator delegator, String orgPartyId, String payrollStructureId, Timestamp asOf)
            throws GenericEntityException {

        List<EntityCondition> conds = new ArrayList<>();
        conds.add(EntityCondition.makeCondition("organizationPartyId", orgPartyId));
        conds.add(EntityCondition.makeCondition("isActive", "Y"));

        // date window: fromDate <= asOf and (thruDate is null or thruDate >= asOf)
        conds.add(EntityCondition.makeCondition("fromDate", EntityOperator.LESS_THAN_EQUAL_TO, asOf));
        conds.add(EntityCondition.makeCondition(
                EntityCondition.makeCondition("thruDate", EntityOperator.EQUALS, null),
                EntityOperator.OR,
                EntityCondition.makeCondition("thruDate", EntityOperator.GREATER_THAN_EQUAL_TO, asOf)
        ));

        // Prefer structure-specific if provided, else org-level
        if (UtilValidate.isNotEmpty(payrollStructureId)) {
            // first try structure specific
            GenericValue v = EntityQuery.use(delegator)
                    .from("PayrollAcctgConfig")
                    .where(EntityCondition.makeCondition(conds, EntityOperator.AND),
                            EntityCondition.makeCondition("payrollStructureId", payrollStructureId))
                    .orderBy("-fromDate")
                    .queryFirst();
            if (v != null) return v;
        }

        // fallback org-level (payrollStructureId null)
        return EntityQuery.use(delegator)
                .from("PayrollAcctgConfig")
                .where(EntityCondition.makeCondition(conds, EntityOperator.AND),
                        EntityCondition.makeCondition("payrollStructureId", EntityOperator.EQUALS, null))
                .orderBy("-fromDate")
                .queryFirst();
    }

    private static GenericValue findActivePayrollPayConfig(Delegator delegator, String orgPartyId, String payrollStructureId, Timestamp asOf)
            throws GenericEntityException {

        List<EntityCondition> conds = new ArrayList<>();
        conds.add(EntityCondition.makeCondition("organizationPartyId", orgPartyId));
        conds.add(EntityCondition.makeCondition("isActive", "Y"));
        conds.add(EntityCondition.makeCondition("fromDate", EntityOperator.LESS_THAN_EQUAL_TO, asOf));
        conds.add(EntityCondition.makeCondition(
                EntityCondition.makeCondition("thruDate", EntityOperator.EQUALS, null),
                EntityOperator.OR,
                EntityCondition.makeCondition("thruDate", EntityOperator.GREATER_THAN_EQUAL_TO, asOf)
        ));

        if (UtilValidate.isNotEmpty(payrollStructureId)) {
            GenericValue v = EntityQuery.use(delegator)
                    .from("PayrollPayConfig")
                    .where(EntityCondition.makeCondition(conds, EntityOperator.AND),
                            EntityCondition.makeCondition("payrollStructureId", payrollStructureId))
                    .orderBy("-fromDate")
                    .queryFirst();
            if (v != null) return v;
        }

        return EntityQuery.use(delegator)
                .from("PayrollPayConfig")
                .where(EntityCondition.makeCondition(conds, EntityOperator.AND),
                        EntityCondition.makeCondition("payrollStructureId", EntityOperator.EQUALS, null))
                .orderBy("-fromDate")
                .queryFirst();
    }

    // ============================================================
    // Helpers: Build component amounts from EmployeePayrollResult
    // ============================================================
    private static Map<String, BigDecimal> buildComponentAmountsFromResult(GenericValue r) {
        Map<String, BigDecimal> comp = new LinkedHashMap<>();

        // FINAL entity fields (with safe fallbacks)
        BigDecimal basic = nvl(r.getBigDecimal("basicSalary"));
        BigDecimal ot = nvl(r.getBigDecimal("overtimeAmount"));
        BigDecimal alw = nvl(r.getBigDecimal("allowancesTotal"));
        BigDecimal bonus = nvl(r.getBigDecimal("bonuses"));

        BigDecimal tax = nvl(r.getBigDecimal("incomeTax"));
        BigDecimal ss = nvl(r.getBigDecimal("socialSecurity"));
        BigDecimal loan = nvl(r.getBigDecimal("loanDeductions"));

        BigDecimal net = r.getBigDecimal("netSalary");
        if (net == null) net = r.getBigDecimal("netPay");
        net = nvl(net);

        comp.put(CMP_BASIC, scale2(basic));
        comp.put(CMP_OT, scale2(ot));
        comp.put(CMP_ALW, scale2(alw));
        comp.put(CMP_BONUS, scale2(bonus));
        comp.put(CMP_TAX, scale2(tax));
        comp.put(CMP_SS, scale2(ss));
        comp.put(CMP_LOAN, scale2(loan));
        comp.put(CMP_NET, scale2(net));

        return comp;
    }

    // ============================================================
    // Helpers: Create accounting entries using PayrollGlMap + defaults
    // ============================================================
    private static void createAcctgEntriesForComponents(
            Delegator delegator,
            GenericValue acctgConfig,
            String orgPartyId,
            String payrollStructureId,
            String acctgTransId,
            Map<String, BigDecimal> compAmounts,
            String entryDescPrefix) throws GenericEntityException {

        // Defaults from config (mandatory)
        String defaultDebit = acctgConfig.getString("defaultDebitGlAccountId");
        String defaultCredit = acctgConfig.getString("defaultCreditGlAccountId");

        if (UtilValidate.isEmpty(defaultDebit) || UtilValidate.isEmpty(defaultCredit)) {
            throw new GenericEntityException("PayrollAcctgConfig must have defaultDebitGlAccountId and defaultCreditGlAccountId for Pattern A");
        }

        // Special liability defaults (recommended mandatory)
        String payrollPayable = acctgConfig.getString("payrollPayableGlAccountId");
        String taxPayable = acctgConfig.getString("taxPayableGlAccountId");
        String ssPayable = acctgConfig.getString("ssPayableGlAccountId");
        String loanPayable = acctgConfig.getString("loanPayableGlAccountId");

        // 1) Expense side (Debit): BASIC/OT/ALW/BONUS (if configured) else default debit
        postComponent(delegator, orgPartyId, payrollStructureId, acctgTransId, CMP_BASIC, DR, compAmounts.get(CMP_BASIC), entryDescPrefix, defaultDebit, acctgConfig);
        postComponent(delegator, orgPartyId, payrollStructureId, acctgTransId, CMP_OT, DR, compAmounts.get(CMP_OT), entryDescPrefix, defaultDebit, acctgConfig);
        postComponent(delegator, orgPartyId, payrollStructureId, acctgTransId, CMP_ALW, DR, compAmounts.get(CMP_ALW), entryDescPrefix, defaultDebit, acctgConfig);
        postComponent(delegator, orgPartyId, payrollStructureId, acctgTransId, CMP_BONUS, DR, compAmounts.get(CMP_BONUS), entryDescPrefix, defaultDebit, acctgConfig);

        // 2) Liability side (Credit): TAX/SS/LOAN -> payable accounts (or mapping/default credit)
        postComponent(delegator, orgPartyId, payrollStructureId, acctgTransId, CMP_TAX, CR, compAmounts.get(CMP_TAX), entryDescPrefix,
                UtilValidate.isNotEmpty(taxPayable) ? taxPayable : defaultCredit, acctgConfig);

        postComponent(delegator, orgPartyId, payrollStructureId, acctgTransId, CMP_SS, CR, compAmounts.get(CMP_SS), entryDescPrefix,
                UtilValidate.isNotEmpty(ssPayable) ? ssPayable : defaultCredit, acctgConfig);

        postComponent(delegator, orgPartyId, payrollStructureId, acctgTransId, CMP_LOAN, CR, compAmounts.get(CMP_LOAN), entryDescPrefix,
                UtilValidate.isNotEmpty(loanPayable) ? loanPayable : defaultCredit, acctgConfig);

        // 3) Net payable (Credit): CMP_NET -> payroll payable (or mapping/default credit)
        postComponent(delegator, orgPartyId, payrollStructureId, acctgTransId, CMP_NET, CR, compAmounts.get(CMP_NET), entryDescPrefix,
                UtilValidate.isNotEmpty(payrollPayable) ? payrollPayable : defaultCredit, acctgConfig);
    }

    private static void postComponent(
            Delegator delegator,
            String orgPartyId,
            String payrollStructureId,
            String acctgTransId,
            String componentTypeStatusId,
            String drCrFlag,
            BigDecimal amount,
            String entryDescPrefix,
            String fallbackGlAccountId,
            GenericValue acctgConfig) throws GenericEntityException {

        amount = scale2(amount);
        if (amount.compareTo(BigDecimal.ZERO) == 0) {
            return; // no entry needed
        }

        String glAccountId = resolveGlAccountForComponent(delegator, orgPartyId, payrollStructureId, componentTypeStatusId, drCrFlag, UtilDateTime.nowTimestamp());
        if (UtilValidate.isEmpty(glAccountId)) {
            glAccountId = fallbackGlAccountId; // mandatory fallback
        }

        GenericValue entry = delegator.makeValue("AcctgTransEntry");
        entry.set("acctgTransId", acctgTransId);

        // In OFBiz, AcctgTransEntry is usually keyed by acctgTransEntrySeqId (string). Use a numeric-like seq.
        // Many installs use "00001" format, but getNextSeqId is safer and respects id max length.
        entry.set("acctgTransEntrySeqId", delegator.getNextSeqId("AcctgTransEntry"));

        entry.set("debitCreditFlag", drCrFlag);
        entry.set("amount", amount);
        entry.set("glAccountId", glAccountId);
        entry.set("description", entryDescPrefix + " " + componentTypeStatusId + " " + drCrFlag);

        // Optional: set organizationPartyId if your AcctgTransEntry model has it
        // entry.set("organizationPartyId", orgPartyId);

        delegator.create(entry);
    }

    /**
     * Resolve GL account from PayrollGlMap.
     * Search order:
     * 1) org + structure + component + drcr (active by date)
     * 2) org + (structure null) + component + drcr (active by date)
     * Returns null if no mapping found (caller uses fallback).
     */
    private static String resolveGlAccountForComponent(
            Delegator delegator,
            String orgPartyId,
            String payrollStructureId,
            String componentTypeStatusId,
            String drCrFlag,
            Timestamp asOf) throws GenericEntityException {

        List<EntityCondition> base = new ArrayList<>();
        base.add(EntityCondition.makeCondition("organizationPartyId", orgPartyId));
        base.add(EntityCondition.makeCondition("componentTypeStatusId", componentTypeStatusId));
        base.add(EntityCondition.makeCondition("drCrFlag", drCrFlag));
        base.add(EntityCondition.makeCondition("isActive", "Y"));
        base.add(EntityCondition.makeCondition("fromDate", EntityOperator.LESS_THAN_EQUAL_TO, asOf));
        base.add(EntityCondition.makeCondition(
                EntityCondition.makeCondition("thruDate", EntityOperator.EQUALS, null),
                EntityOperator.OR,
                EntityCondition.makeCondition("thruDate", EntityOperator.GREATER_THAN_EQUAL_TO, asOf)
        ));

        if (UtilValidate.isNotEmpty(payrollStructureId)) {
            GenericValue v = EntityQuery.use(delegator)
                    .from("PayrollGlMap")
                    .where(EntityCondition.makeCondition(base, EntityOperator.AND),
                            EntityCondition.makeCondition("payrollStructureId", payrollStructureId))
                    .orderBy("-fromDate")
                    .queryFirst();
            if (v != null) return v.getString("glAccountId");
        }

        GenericValue v2 = EntityQuery.use(delegator)
                .from("PayrollGlMap")
                .where(EntityCondition.makeCondition(base, EntityOperator.AND),
                        EntityCondition.makeCondition("payrollStructureId", EntityOperator.EQUALS, null))
                .orderBy("-fromDate")
                .queryFirst();
        return v2 != null ? v2.getString("glAccountId") : null;
    }

    // ============================================================
    // OPTIONAL: Reversal service skeleton (Pattern A)
    // ============================================================
    public static Map<String, Object> reversePayrollAccountingPatternA(DispatchContext dctx, Map<String, ?> context) {
        Delegator delegator = dctx.getDelegator();
        GenericValue userLogin = (GenericValue) context.get("userLogin");

        boolean beganTx = false;
        try {
            String postingId = (String) context.get("postingId");
            if (UtilValidate.isEmpty(postingId)) {
                return ServiceUtil.returnError("postingId is required");
            }
            if (userLogin == null) {
                return ServiceUtil.returnError("userLogin is required");
            }

            GenericValue posting = EntityQuery.use(delegator)
                    .from("PayrollAcctgPosting")
                    .where("postingId", postingId)
                    .queryOne();

            if (posting == null) {
                return ServiceUtil.returnError("PayrollAcctgPosting not found: " + postingId);
            }

            if (!POSTING_STATUS_POSTED.equals(posting.getString("statusId"))) {
                return ServiceUtil.returnError("Only POSTED postings can be reversed. Current status=" + posting.getString("statusId"));
            }

            String acctgTransId = posting.getString("acctgTransId");
            if (UtilValidate.isEmpty(acctgTransId)) {
                return ServiceUtil.returnError("Posting has no acctgTransId to reverse");
            }

            beganTx = TransactionUtil.begin();

            // Minimal reversal approach:
            // - Create a new AcctgTrans with opposite debit/credit lines for every entry in original.
            // - Link reversalPostingId both ways.
            Timestamp now = UtilDateTime.nowTimestamp();

            String revAcctgTransId = delegator.getNextSeqId("AcctgTrans");
            GenericValue revTrans = delegator.makeValue("AcctgTrans");
            revTrans.set("acctgTransId", revAcctgTransId);
            revTrans.set("acctgTransTypeId", "PAYROLL_REV");
            revTrans.set("transactionDate", now);
            revTrans.set("description", "Reversal of Payroll Posting " + acctgTransId);
            revTrans.set("isPosted", "N");
            delegator.create(revTrans);

            List<GenericValue> origEntries = EntityQuery.use(delegator)
                    .from("AcctgTransEntry")
                    .where("acctgTransId", acctgTransId)
                    .queryList();

            for (GenericValue oe : origEntries) {
                GenericValue re = delegator.makeValue("AcctgTransEntry");
                re.set("acctgTransId", revAcctgTransId);
                re.set("acctgTransEntrySeqId", delegator.getNextSeqId("AcctgTransEntry"));
                String dc = oe.getString("debitCreditFlag");
                re.set("debitCreditFlag", DR.equals(dc) ? CR : DR);
                re.set("amount", oe.getBigDecimal("amount"));
                re.set("glAccountId", oe.getString("glAccountId"));
                re.set("description", "REV " + oe.getString("description"));
                delegator.create(re);
            }

            // Create reversal posting record
            GenericValue revPosting = delegator.makeValue("PayrollAcctgPosting");
            String revPostingId = delegator.getNextSeqId("PayrollAcctgPosting");
            revPosting.set("postingId", revPostingId);
            revPosting.set("organizationPartyId", posting.getString("organizationPartyId"));
            revPosting.set("payrollIterationId", posting.getString("payrollIterationId"));
            revPosting.set("acctgTransId", revAcctgTransId);
            revPosting.set("statusId", POSTING_STATUS_REVERSED);
            revPosting.set("postedDate", now);
            revPosting.set("postedByUserLoginId", userLogin.getString("userLoginId"));
            revPosting.set("reversalPostingId", postingId);
            delegator.create(revPosting);

            // Link original to reversal
            posting.set("statusId", POSTING_STATUS_REVERSED);
            posting.set("reversalPostingId", revPostingId);
            delegator.store(posting);

            TransactionUtil.commit(beganTx);

            Map<String, Object> ok = ServiceUtil.returnSuccess("Payroll posting reversed (Pattern A)");
            ok.put("originalPostingId", postingId);
            ok.put("reversalPostingId", revPostingId);
            ok.put("reversalAcctgTransId", revAcctgTransId);
            return ok;

        } catch (Exception e) {
            Debug.logError(e, "reversePayrollAccountingPatternA failed", MODULE);
            try { TransactionUtil.rollback(beganTx, e.getMessage(), e); } catch (Exception ignore) {}
            return ServiceUtil.returnError("Reversal failed: " + e.getMessage());
        }
    }
}
