package org.apache.ofbiz.payroll.services;

import org.apache.ofbiz.base.util.Debug;
import org.apache.ofbiz.base.util.UtilDateTime;
import org.apache.ofbiz.base.util.UtilMisc;
import org.apache.ofbiz.base.util.UtilValidate;

import org.apache.ofbiz.entity.Delegator;
import org.apache.ofbiz.entity.GenericEntityException;
import org.apache.ofbiz.entity.GenericValue;
import org.apache.ofbiz.entity.util.EntityQuery;

import org.apache.ofbiz.service.DispatchContext;
import org.apache.ofbiz.service.LocalDispatcher;
import org.apache.ofbiz.service.ModelService;
import org.apache.ofbiz.service.ServiceUtil;

import org.apache.ofbiz.payroll.util.PayrollStatusIds;

import java.sql.Timestamp;
import java.util.Calendar;
import java.util.List;
import java.util.Map;
import java.io.File;
/**
 * PayrollSchedulerServicesV2 (PRODUCTION SAFE + ENHANCED RESPONSE)
 *
 * Enhancements added:
 * - Uses PayrollRunResolver to resolve payrollRunId vs payrollPeriodId (one-and-only-one policy)
 * - Uses PayrollValidationQueries to produce explicit prereq messages + eligible counts
 * - Uses PayrollRunResponseBuilder to return stage-by-stage execution summary
 * - Prevents "success but nothing processed" by reporting WHY (validation + stage statuses)
 *
 * NOTE:
 * This file expects the following helper classes:
 *  - PayrollStageIds
 *  - PayrollRunResolver
 *  - PayrollValidationQueries
 *  - PayrollRunResponseBuilder
 *
 * Existing behavior preserved:
 * - Idempotent get-or-create PayrollRun
 * - Safe approve/post with skip logic
 * - Workers self-reschedule and avoid duplicate JobSandbox rows
 *
 * IMPORTANT (Unified Status):
 * - ALL payroll status IDs must come from PayrollStatusIds (PR_* / P_ACT / PAY_ASG_* / PAYSTR_*).
 * - Do NOT hardcode "PR_*" or "P_ACT" in this file.
 */
public class PayrollSchedulerServicesV2 {
    public static final String MODULE = PayrollSchedulerServicesV2.class.getName();

    // ----------------------------
    // V2 Job Names (avoid collision with existing jobs)
    // ----------------------------
    public static final String JOB_MONTHLY_PAYROLL_V2   = "PAYROLL_MONTHLY_RUNNER_V2";
    public static final String JOB_EMAIL_WORKER_V2      = "PAYROLL_EMAIL_WORKER_V2";
    public static final String JOB_SMS_WORKER_V2        = "PAYROLL_SMS_WORKER_V2";
    public static final String JOB_EXPORT_WORKER_V2     = "PAYROLL_EXPORT_WORKER_V2";
    public static final String JOB_COMPLIANCE_WORKER_V2 = "PAYROLL_COMPLIANCE_WORKER_V2";

    // ----------------------------
    // Stage IDs not in PayrollStageIds (keep local)
    // ----------------------------
    private static final String STAGE_APPROVE = "APPROVE";

    // ----------------------------
    // Worker intervals
    // ----------------------------
    private static final long EMAIL_INTERVAL_MS  = 5L * 60L * 1000L;
    private static final long SMS_INTERVAL_MS    = 5L * 60L * 1000L;
    private static final long EXPORT_INTERVAL_MS = 10L * 60L * 1000L;

    // Daily compliance time
    private static final int COMPLIANCE_HOUR = 2; // 02:00 daily

    // Monthly run time (scheduler)
    private static final int MONTHLY_RUN_DAY  = 1;
    private static final int MONTHLY_RUN_HOUR = 1;

    // ----------------------------
    // Full-cycle switches (safe defaults)
    // ----------------------------
    private static final boolean AUTO_APPROVE = true;
    private static final boolean AUTO_POST    = true;

    /**
     * Default posting option if PayrollRun.accountingPostOptionId is empty.
     * Keep aligned with your status data for PAY_POST_OPT (if you renamed, update here).
     *
     * NOTE: Your status XML currently uses PO_GL/PO_INV/PO_BOTH/PO_NONE (PAY_POST_OPT),
     * while earlier code used PAY_POST_GL/PAY_POST_INVOICE/PAY_POST_BOTH.
     * This constant is NOT a StatusItem FK (it is accountingPostOptionId field in PayrollRun),
     * so keep it consistent with your entity model.
     */
    private static final String DEFAULT_POST_OPTION = "PO_BOTH";

    /**
     * Main entry: Run monthly payroll for requested org or all active PayrollOrganization records.
     */
/**
 * runMonthlyPayrollV2
 *
 * Adds READINESS stage after ROUTE_PAYMENTS (post-calc) to produce actionable items to fix
 * before approval/posting/bank.
 *
 * Readiness logic:
 * - ALWAYS safe to run after CALCULATE because results exist.
 * - In early rollout we run it in non-blocking mode (warn only).
 * - When you want hard-gate before APPROVE/POST, flip readinessStrict=Y.
 *
 * Requires:
 * - service "runPayrollReadinessPack" (your PayrollReadinessServices) is deployed/exported.
 * - bankTransferConfigId is optional; if missing we still run “light readiness” checks.
 *   (I show both options below.)
 */
public static Map<String, Object> runMonthlyPayrollV2(DispatchContext dctx, Map<String, ?> context) {
    final Delegator delegator = dctx.getDelegator();
    final LocalDispatcher dispatcher = dctx.getDispatcher();

    // ----------------------------
    // Identity
    // ----------------------------
    GenericValue userLogin = (GenericValue) context.get("userLogin");
    final String userLoginId = (String) context.get("userLoginId");

    if (userLogin == null && UtilValidate.isNotEmpty(userLoginId)) {
        try {
            userLogin = EntityQuery.use(delegator)
                    .from("UserLogin")
                    .where("userLoginId", userLoginId)
                    .cache(false)
                    .queryOne();
        } catch (Exception e) {
            Debug.logError(e, "runMonthlyPayrollV2 failed loading UserLogin userLoginId=" + userLoginId, MODULE);
            return ServiceUtil.returnError("Invalid userLoginId=" + userLoginId);
        }
    }

    // ----------------------------
    // Inputs
    // ----------------------------
    final String requestedOrgPartyId = (String) context.get("organizationPartyId");
    final String requestedPayrollPeriodId = (String) context.get("payrollPeriodId");
    final String requestedPayrollRunId = (String) context.get("payrollRunId");
    final String currencyUomId = (String) context.get("currencyUomId");
    final String accountingPostOptionIdIn = (String) context.get("accountingPostOptionId");

    final String dryRunStr = (String) context.get("dryRun");
    final String testModeStr = (String) context.get("testMode");
    final String executeNowStr = (String) context.get("executeNow");

    final boolean isDryRun = "Y".equalsIgnoreCase(dryRunStr) || "true".equalsIgnoreCase(dryRunStr);
    final boolean isTestMode = "Y".equalsIgnoreCase(testModeStr) || "true".equalsIgnoreCase(testModeStr);
    final boolean doExecuteNow = "Y".equalsIgnoreCase(executeNowStr) || "true".equalsIgnoreCase(executeNowStr);

    final String partyIdFilter = (String) context.get("partyId"); // optional employee filter
    final String bankTransferConfigId = (String) context.get("bankTransferConfigId"); // optional

    // NEW: auto-generate bank batch (final workflow)
    final String autoGenBankStr = (String) context.get("autoGenerateBankBatch"); // Y/N default N
    final boolean autoGenerateBankBatch =
            "Y".equalsIgnoreCase(autoGenBankStr) || "true".equalsIgnoreCase(autoGenBankStr);

    // NEW: readiness controls
    final String readinessStrictStr = (String) context.get("readinessStrict"); // Y/N (optional)
    final boolean readinessStrict =
            "Y".equalsIgnoreCase(readinessStrictStr) || "true".equalsIgnoreCase(readinessStrictStr);

    // Optional: allow forcing recalculation (kept for compatibility)
    final Object forceRecalcObj = context.get("forceRecalc");
    final boolean forceRecalc =
            (forceRecalcObj instanceof Boolean && (Boolean) forceRecalcObj)
                    || "Y".equalsIgnoreCase(String.valueOf(forceRecalcObj))
                    || "true".equalsIgnoreCase(String.valueOf(forceRecalcObj));

    // Optional run info
    final String runTypeIdIn = (String) context.get("runTypeId");
    final String runNameIn = (String) context.get("runName");

    // ----------------------------
    // Guardrails
    // ----------------------------
    if (userLogin == null) {
        return ServiceUtil.returnError("userLogin is required (provide userLogin in webtools OR userLoginId for REST/job).");
    }
    if (UtilValidate.isEmpty(currencyUomId)) {
        return ServiceUtil.returnError("currencyUomId is required.");
    }
    if (autoGenerateBankBatch && UtilValidate.isEmpty(bankTransferConfigId)) {
        return ServiceUtil.returnError("bankTransferConfigId is required when autoGenerateBankBatch=Y");
    }

    // ----------------------------
    // Determine org(s)
    // ----------------------------
    final java.util.List<String> orgPartyIds = new java.util.ArrayList<>();
    try {
        if (UtilValidate.isNotEmpty(requestedOrgPartyId)) {
            orgPartyIds.add(requestedOrgPartyId);
        } else {
            java.util.List<GenericValue> orgs = EntityQuery.use(delegator)
                    .from("PayrollOrganization")
                    .where("isActive", "Y")
                    .cache(false)
                    .queryList();

            for (GenericValue org : orgs) {
                String orgPartyId = org.getString("organizationPartyId");
                if (UtilValidate.isNotEmpty(orgPartyId)) {
                    orgPartyIds.add(orgPartyId);
                }
            }
        }
    } catch (Exception e) {
        Debug.logError(e, "runMonthlyPayrollV2 failed loading PayrollOrganization list", MODULE);
        return ServiceUtil.returnError("Failed to load payroll organizations: " + safeMsg(e));
    }

    if (orgPartyIds.isEmpty()) {
        return ServiceUtil.returnError("No active payroll organizations found (PayrollOrganization.isActive='Y').");
    }

    Debug.logInfo("runMonthlyPayrollV2 START orgCount=" + orgPartyIds.size()
            + " requestedOrg=" + requestedOrgPartyId
            + " requestedPeriod=" + requestedPayrollPeriodId
            + " requestedRunId=" + requestedPayrollRunId
            + " currencyUomId=" + currencyUomId
            + " dryRun=" + isDryRun
            + " testMode=" + isTestMode
            + " executeNow=" + doExecuteNow
            + " forceRecalc=" + forceRecalc
            + " readinessStrict=" + (readinessStrict ? "Y" : "N")
            + " autoGenerateBankBatch=" + (autoGenerateBankBatch ? "Y" : "N")
            + " userLoginId=" + userLogin.getString("userLoginId"), MODULE);

    final java.util.Calendar cal = java.util.Calendar.getInstance();
    final int month = cal.get(java.util.Calendar.MONTH) + 1;
    final int year = cal.get(java.util.Calendar.YEAR);

    int okCount = 0;
    int errCount = 0;

    final java.util.List<String> flatErrors = new java.util.ArrayList<>();

    final PayrollRunResponseBuilder.ResponseBuilder rb =
            PayrollRunResponseBuilder.newBuilder()
                    .withRequestContext(requestedOrgPartyId, requestedPayrollPeriodId, requestedPayrollRunId, currencyUomId);

    // ----------------------------
    // Per-org loop
    // ----------------------------
    for (String organizationPartyId : orgPartyIds) {
        final PayrollRunResponseBuilder.OrgRunBuilder orgRb = rb.org(organizationPartyId);
        String currentStage = PayrollStageIds.RESOLVE;

        // per-org useful outputs
        String generatedBankTransferBatchId = null;

        try {
            Debug.logInfo("runMonthlyPayrollV2 ORG begin org=" + organizationPartyId, MODULE);

            // Optional pre-step (safe)
            java.util.Map<String, Object> baseCtx = UtilMisc.toMap(
                    "organizationPartyId", organizationPartyId,
                    "runMonth", month,
                    "runYear", year,
                    "sourceIp", "SCHEDULER",
                    "userLogin", userLogin
            );
            runIfExists(dispatcher, "buildAttendancePeriodSummary", baseCtx);

            // ----------------------------
            // STAGE 1: RESOLVE
            // ----------------------------
            currentStage = PayrollStageIds.RESOLVE;
            orgRb.stage(PayrollStageIds.RESOLVE).start();

            PayrollRunResolver.ResolvedRun rr = PayrollRunResolver.resolve(
                    delegator,
                    dispatcher,
                    userLogin,
                    organizationPartyId,
                    requestedPayrollPeriodId,
                    requestedPayrollRunId,
                    currencyUomId,
                    accountingPostOptionIdIn,
                    null,
                    (java.util.Map<String, ?>) context
            );

            if (rr == null || !rr.isOk()) {
                String msg = (rr == null) ? "Resolver returned null" : rr.getErrorMessage();
                addStageError(rb, orgRb, flatErrors, organizationPartyId, PayrollStageIds.RESOLVE, "RESOLVE_FAILED",
                        "Resolve failed: " + msg);
                errCount++;
                continue;
            }

            final String payrollPeriodId = rr.getPayrollPeriodIdResolved();
            String payrollRunId = rr.getPayrollRunIdResolved(); // MUTABLE (may create)

            orgRb.resolved(payrollRunId, payrollPeriodId);

            if (UtilValidate.isEmpty(payrollPeriodId)) {
                addStageError(rb, orgRb, flatErrors, organizationPartyId, PayrollStageIds.RESOLVE, "RESOLVE_FAILED",
                        "Resolved payrollPeriodId is empty.");
                errCount++;
                continue;
            }

            // Create/ensure PayrollRun in execute mode if missing
            if (!(isDryRun || isTestMode) && UtilValidate.isEmpty(payrollRunId)) {
                try {
                    final String ensuredPayrollRunId = ensurePayrollRunExistsForExecuteV2(
                            dispatcher, delegator,
                            organizationPartyId, payrollPeriodId,
                            currencyUomId,
                            accountingPostOptionIdIn,
                            runTypeIdIn,
                            runNameIn,
                            userLogin
                    );

                    payrollRunId = ensuredPayrollRunId;
                    orgRb.resolved(payrollRunId, payrollPeriodId);
                } catch (Exception ex) {
                    addStageError(rb, orgRb, flatErrors, organizationPartyId, PayrollStageIds.RESOLVE, "RUN_CREATE_FAILED",
                            "Failed to create PayrollRun for execute mode: " + safeMsg(ex));
                    errCount++;
                    continue;
                }
            }

            orgRb.stage(PayrollStageIds.RESOLVE).ok("Resolved period/run", 1, 0);

            // ----------------------------
            // STAGE 2: VALIDATE
            // ----------------------------
            currentStage = PayrollStageIds.VALIDATE;
            orgRb.stage(PayrollStageIds.VALIDATE).start();

            PayrollValidationQueries.ValidationResult vr =
                    PayrollValidationQueries.validateForMonthlyRun(
                            delegator,
                            organizationPartyId,
                            payrollRunId,
                            payrollPeriodId,
                            currencyUomId,
                            accountingPostOptionIdIn,
                            partyIdFilter
                    );

            if (vr == null) {
                addStageError(rb, orgRb, flatErrors, organizationPartyId, PayrollStageIds.VALIDATE, "VALIDATION",
                        "ValidationResult is null.");
                errCount++;
                continue;
            }

            orgRb.eligibleCount(safeInt(vr.getEligibleCount()));

            if (!vr.isOk()) {
                String summary = UtilValidate.isNotEmpty(vr.getSummaryText()) ? vr.getSummaryText() : "Validation failed.";
                orgRb.stage(PayrollStageIds.VALIDATE).error(summary);
                flatErrors.add("org=" + organizationPartyId + " VALIDATE: " + summary);

                if (vr.getMessages() != null) {
                    for (String m : vr.getMessages()) {
                        rb.addError("VALIDATION", "org=" + organizationPartyId + " : " + m);
                        flatErrors.add("org=" + organizationPartyId + " VALIDATE: " + m);
                    }
                }

                errCount++;
                continue;
            }

            orgRb.stage(PayrollStageIds.VALIDATE).ok(vr.getSummaryText(), safeInt(vr.getOkCount()), safeInt(vr.getErrorCount()));

            // Stop after validation for dry/test
            if (isDryRun || isTestMode) {
                orgRb.stage(PayrollStageIds.CALCULATE).warn("DryRun/TestMode enabled: calculation skipped.");
                okCount++;
                continue;
            }

            if (UtilValidate.isEmpty(payrollRunId)) {
                addStageError(rb, orgRb, flatErrors, organizationPartyId, PayrollStageIds.VALIDATE, "VALIDATION",
                        "Resolved payrollRunId is empty in execute mode (unexpected).");
                errCount++;
                continue;
            }

            // Reload run row
            GenericValue run = reloadPayrollRun(delegator, payrollRunId);
            if (run == null) {
                addStageError(rb, orgRb, flatErrors, organizationPartyId, PayrollStageIds.CALCULATE, "RUN_NOT_FOUND",
                        "PayrollRun not found: payrollRunId=" + payrollRunId);
                errCount++;
                continue;
            }

            String runStatusId = run.getString("statusId");

            // ----------------------------
            // STAGE 3: CALCULATE
            // ----------------------------
            currentStage = PayrollStageIds.CALCULATE;
            orgRb.stage(PayrollStageIds.CALCULATE).start();

            java.util.Map<String, Object> processed = dispatcher.runSync(
                    "processPayrollRun",
                    UtilMisc.toMap(
                            "payrollRunId", payrollRunId,
                            "forceRecalc", Boolean.valueOf(forceRecalc),
                            "sourceIp", "SCHEDULER",
                            "userLogin", userLogin
                    )
            );

            if (ServiceUtil.isError(processed)) {
                addStageError(rb, orgRb, flatErrors, organizationPartyId, PayrollStageIds.CALCULATE, "PROCESS_FAILED",
                        "processPayrollRun failed: " + ServiceUtil.getErrorMessage(processed));
                errCount++;
                continue;
            }

            int calculatedCount = PayrollValidationQueries.countResultsForRun(delegator, payrollRunId);
            orgRb.calculatedCount(calculatedCount);
            orgRb.stage(PayrollStageIds.CALCULATE).ok("Results created/available", calculatedCount, 0);

            // Reload after calc
            run = reloadPayrollRun(delegator, payrollRunId);
            runStatusId = (run != null) ? run.getString("statusId") : null;

            // ----------------------------
            // STAGE 4: ROUTE PAYMENTS
            // ----------------------------
            currentStage = PayrollStageIds.ROUTE_PAYMENTS;
            orgRb.stage(PayrollStageIds.ROUTE_PAYMENTS).start();

            final String strictMode = "N"; // keep N until you want hard-stop

            java.util.Map<String, Object> routed = dispatcher.runSync(
                    "routeEmployeePaymentsForRun",
                    UtilMisc.toMap(
                            "payrollRunId", payrollRunId,
                            "strictMode", strictMode,
                            "sourceIp", "SCHEDULER",
                            "userLogin", userLogin
                    )
            );

            if (ServiceUtil.isError(routed)) {
                addStageError(rb, orgRb, flatErrors, organizationPartyId, PayrollStageIds.ROUTE_PAYMENTS, "ROUTING_FAILED",
                        "routeEmployeePaymentsForRun failed: " + ServiceUtil.getErrorMessage(routed));
                errCount++;
                continue;
            }

            @SuppressWarnings("unchecked")
            java.util.Map<String, Object> routeData = (java.util.Map<String, Object>) routed.get("data");

            int routedCount = 0;
            int missingCount = 0;

            if (routeData != null) {
                Object rc = routeData.get("routedCount");
                Object mc = routeData.get("missingCount");
                routedCount = (rc instanceof Number) ? ((Number) rc).intValue() : 0;
                missingCount = (mc instanceof Number) ? ((Number) mc).intValue() : 0;
            }

            orgRb.stage(PayrollStageIds.ROUTE_PAYMENTS)
                    .ok("Routing completed (strictMode=" + strictMode + ")", routedCount, missingCount);

            // Reload after routing (optional)
            run = reloadPayrollRun(delegator, payrollRunId);
            runStatusId = (run != null) ? run.getString("statusId") : null;

            // ----------------------------
            // STAGE 5: READINESS
            // ----------------------------
            currentStage = PayrollStageIds.READINESS;
            orgRb.stage(PayrollStageIds.READINESS).start();

            if (UtilValidate.isNotEmpty(bankTransferConfigId)) {
                final String outDir = System.getProperty("ofbiz.home") + java.io.File.separator + "runtime"
                        + java.io.File.separator + "tmp" + java.io.File.separator + "payroll_readiness_pack"
                        + java.io.File.separator + payrollRunId;

                java.util.Map<String, Object> readinessRes = dispatcher.runSync(
                        "runPayrollReadinessPack",
                        UtilMisc.toMap(
                                "payrollRunId", payrollRunId,
                                "bankTransferConfigId", bankTransferConfigId,
                                "outDir", outDir,
                                "sourceIp", "SCHEDULER",
                                "userLogin", userLogin
                        )
                );

                if (ServiceUtil.isError(readinessRes)) {
                    String msg = "runPayrollReadinessPack failed: " + ServiceUtil.getErrorMessage(readinessRes);
                    if (readinessStrict) {
                        addStageError(rb, orgRb, flatErrors, organizationPartyId, PayrollStageIds.READINESS, "READINESS_FAILED", msg);
                        errCount++;
                        continue;
                    } else {
                        orgRb.stage(PayrollStageIds.READINESS).warn(msg);
                    }
                } else {
                    @SuppressWarnings("unchecked")
                    Map<String, Object> readinessData = (Map<String, Object>) readinessRes.get("data");

                    int missingAlloc = safeInt(readinessData != null ? readinessData.get("missingAllocationCount") : null);
                    int missingEft = safeInt(readinessData != null ? readinessData.get("missingEftCount") : null);
                    int missingAcct = safeInt(readinessData != null ? readinessData.get("missingAccountNumberCount") : null);

                    if (readinessStrict && (missingAlloc > 0 || missingEft > 0 || missingAcct > 0)) {
                        String msg = "Readiness NOT OK: missingAllocation=" + missingAlloc
                                + " missingEft=" + missingEft
                                + " missingAccountNumber=" + missingAcct;
                        addStageError(rb, orgRb, flatErrors, organizationPartyId, PayrollStageIds.READINESS, "READINESS_NOT_OK", msg);
                        errCount++;
                        continue;
                    }

                    orgRb.stage(PayrollStageIds.READINESS)
                            .ok("Readiness pack generated", 1, (missingAlloc + missingEft + missingAcct));
                }
            } else {
                String msg = "Readiness pack skipped: bankTransferConfigId not provided.";
                if (readinessStrict) {
                    addStageError(rb, orgRb, flatErrors, organizationPartyId, PayrollStageIds.READINESS, "READINESS_SKIPPED", msg);
                    errCount++;
                    continue;
                } else {
                    orgRb.stage(PayrollStageIds.READINESS).warn(msg);
                }
            }

            // ----------------------------
            // STAGE 6: GENERATE BANK (FINAL placement: after CALC/ROUTE/READINESS)
            // ----------------------------
            currentStage = PayrollStageIds.GENERATE_BANK;
            orgRb.stage(PayrollStageIds.GENERATE_BANK).start();

            if (autoGenerateBankBatch) {
                java.util.Map<String, Object> gbRes = dispatcher.runSync(
                        "generateBankTransferBatchForRun",
                        UtilMisc.toMap(
                                "payrollRunId", payrollRunId,
                                "bankTransferConfigId", bankTransferConfigId,
                                "sourceIp", "SCHEDULER",
                                "userLogin", userLogin
                        )
                );

if (ServiceUtil.isError(gbRes)) {
    addStageError(rb, orgRb, flatErrors, organizationPartyId, PayrollStageIds.GENERATE_BANK, "BANK_GEN_FAILED",
            "generateBankTransferBatchForRun failed: " + ServiceUtil.getErrorMessage(gbRes));
    errCount++;
    continue;
}

generatedBankTransferBatchId = (String) gbRes.get("bankTransferBatchId");

// Put the batch id into the stage message (always supported, no builder changes needed)
orgRb.stage(PayrollStageIds.GENERATE_BANK)
        .ok("Bank batch generated batchId=" + generatedBankTransferBatchId, 1, 0);

// Optional: also attach it to flatErrors/payload for easy REST parsing
flatErrors.add("org=" + organizationPartyId + " GENERATE_BANK: bankTransferBatchId=" + generatedBankTransferBatchId);

} else {
    orgRb.stage(PayrollStageIds.GENERATE_BANK)
            .warn("autoGenerateBankBatch=N: bank batch not generated.");
}
            // ----------------------------
            // STAGE 7: POST (kept, but will usually SKIP because run isn't approved)
            // NOTE: Final workflow: approval/post/upload are separate actions, not forced here.
            // ----------------------------
            int postedCount = 0;

            String accountingPostOptionId = accountingPostOptionIdIn;
            if (UtilValidate.isEmpty(accountingPostOptionId) && run != null) {
                accountingPostOptionId = run.getString("accountingPostOptionId");
            }
            if (UtilValidate.isEmpty(accountingPostOptionId)) {
                accountingPostOptionId = DEFAULT_POST_OPTION;
            }

            boolean wantsGl = "PO_GL".equals(accountingPostOptionId) || "PO_BOTH".equals(accountingPostOptionId);
            boolean wantsInv = "PO_INV".equals(accountingPostOptionId) || "PO_BOTH".equals(accountingPostOptionId);

            if (AUTO_POST) {
                currentStage = PayrollStageIds.POST_GL;

                boolean alreadyPostedFlags =
                        (run != null) && ("Y".equalsIgnoreCase(run.getString("isGlPosted"))
                                || "Y".equalsIgnoreCase(run.getString("isInvoicePosted")));

                if (run != null && (PayrollStatusIds.RUN_POST.equals(runStatusId) || alreadyPostedFlags)) {
                    if (wantsGl) orgRb.stage(PayrollStageIds.POST_GL).warn("Skipped posting: already posted (status/flags).");
                    if (wantsInv) orgRb.stage(PayrollStageIds.POST_INV).warn("Skipped posting: already posted (status/flags).");
                } else if (run != null && !PayrollStatusIds.RUN_APPR.equals(runStatusId)) {
                    if (wantsGl) orgRb.stage(PayrollStageIds.POST_GL).warn("Skipped posting: run not approved (status=" + runStatusId + ").");
                    if (wantsInv) orgRb.stage(PayrollStageIds.POST_INV).warn("Skipped posting: run not approved (status=" + runStatusId + ").");
                } else {
                    final java.util.List<String> modesToPost = new java.util.ArrayList<>();
                    if ("PO_BOTH".equals(accountingPostOptionId)) {
                        modesToPost.add("PO_GL");
                        modesToPost.add("PO_INV");
                    } else {
                        modesToPost.add(accountingPostOptionId);
                    }

                    boolean postOk = true;

                    for (String mode : modesToPost) {
                        java.util.Map<String, Object> postCtx = UtilMisc.toMap(
                                "payrollRunId", payrollRunId,
                                "organizationPartyId", organizationPartyId,
                                "accountingPostOptionId", mode,
                                "vendorPartyId", context.get("vendorPartyId"),
                                "invoiceTypeId", context.get("invoiceTypeId"),
                                "forceRepost", Boolean.FALSE,
                                "sourceIp", "SCHEDULER",
                                "userLogin", userLogin
                        );

                        java.util.Map<String, Object> postRes = runIfExistsReturn(dispatcher, "postPayrollRunAccounting", postCtx);
                        if (postRes == null) {
                            postRes = dispatcher.runSync("postPayrollRun", postCtx);
                        }

                        if (ServiceUtil.isError(postRes)) {
                            postOk = false;
                            String msg = "Posting failed (" + mode + "): " + ServiceUtil.getErrorMessage(postRes);

                            if ("PO_GL".equals(mode)) {
                                addStageError(rb, orgRb, flatErrors, organizationPartyId, PayrollStageIds.POST_GL, "POST_FAILED", msg);
                            } else if ("PO_INV".equals(mode)) {
                                addStageError(rb, orgRb, flatErrors, organizationPartyId, PayrollStageIds.POST_INV, "POST_FAILED", msg);
                            } else {
                                addStageError(rb, orgRb, flatErrors, organizationPartyId, PayrollStageIds.POST_GL, "POST_FAILED", msg);
                            }

                            errCount++;
                            break;
                        }
                    }

                    if (!postOk) {
                        continue;
                    }

                    postedCount = safeInt(PayrollValidationQueries.countPostingLinesForRun(delegator, payrollRunId));
                    orgRb.postedCount(postedCount);

                    if (wantsGl) orgRb.stage(PayrollStageIds.POST_GL).ok("GL postings created", postedCount, 0);
                    if (wantsInv) orgRb.stage(PayrollStageIds.POST_INV).ok("Invoice postings created", postedCount, 0);
                }
            } else {
                orgRb.stage(PayrollStageIds.POST_GL).warn("AUTO_POST disabled: posting skipped.");
                orgRb.stage(PayrollStageIds.POST_INV).warn("AUTO_POST disabled: posting skipped.");
            }

            // Per-org success
            okCount++;

        } catch (Exception e) {
            Debug.logError(e, "runMonthlyPayrollV2 ORG failed org=" + organizationPartyId, MODULE);
            addStageError(rb, orgRb, flatErrors, organizationPartyId, currentStage, "UNEXPECTED_EXCEPTION",
                    "Unexpected error: " + safeMsg(e));
            errCount++;
        }
    } // end per-org loop

    // ----------------------------
    // Reschedule job (if not executeNow)
    // ----------------------------
    final Timestamp nextMonthly = nextMonthlyTime(MONTHLY_RUN_DAY, MONTHLY_RUN_HOUR);
    boolean scheduleOk = true;
    String scheduleWarn = null;

    if (!doExecuteNow) {
        try {
            ensureSingleScheduled(delegator, JOB_MONTHLY_PAYROLL_V2, "runMonthlyPayrollV2", nextMonthly, userLogin);
        } catch (GenericEntityException gee) {
            scheduleOk = false;
            scheduleWarn = "Scheduling next monthly run failed: " + safeMsg(gee);
            Debug.logError(gee, "runMonthlyPayrollV2 scheduling failed jobId=" + JOB_MONTHLY_PAYROLL_V2 + " nextMonthly=" + nextMonthly, MODULE);

            rb.addError("SCHEDULE_FAILED", scheduleWarn);
            flatErrors.add("SCHEDULE: " + scheduleWarn);
        }
    }

    // ----------------------------
    // Build enhanced payload (stages + errors)
    // ----------------------------
    final java.util.Map<String, Object> enhanced = rb.build();
    final Object payloadObj = (enhanced != null) ? enhanced.get("data") : null;

    @SuppressWarnings("unchecked")
    final java.util.Map<String, Object> payload =
            (payloadObj instanceof java.util.Map) ? (java.util.Map<String, Object>) payloadObj : new java.util.HashMap<String, Object>();

    payload.put("errorsFlat", flatErrors);

    // ----------------------------
    // Build REST-friendly data
    // ----------------------------
    final boolean ok = (errCount == 0);

    final java.util.Map<String, Object> legacyData = new java.util.HashMap<>();
    legacyData.put("okCount", okCount);
    legacyData.put("errorCount", errCount);
    legacyData.put("nextRunTime", nextMonthly);
    legacyData.put("ok", ok);
    legacyData.put("scheduleOk", scheduleOk);
    if (scheduleWarn != null) legacyData.put("scheduleWarning", scheduleWarn);
    legacyData.put("payload", payload);
    legacyData.put("payloadMessage", (enhanced != null) ? enhanced.get("message") : null);
    legacyData.put("_buildMarker", "RUNMONTHLY_V2_PAYLOAD_OK");

    // ----------------------------
    // Final return (ALWAYS attach data)
    // ----------------------------
    final java.util.Map<String, Object> out =
            ServiceUtil.returnSuccess("###RUNMONTHLY_V2_ENHANCED### ok=" + okCount + " err=" + errCount);
    out.put("data", legacyData);

    if (!ok) {
        StringBuilder sb = new StringBuilder("Monthly payroll failed. ");
        if (!flatErrors.isEmpty()) {
            sb.append("Top errors: ");
            int max = Math.min(flatErrors.size(), 5);
            for (int i = 0; i < max; i++) {
                if (i > 0) sb.append(" | ");
                sb.append(flatErrors.get(i));
            }
            if (flatErrors.size() > max) sb.append(" | ... (").append(flatErrors.size()).append(" total)");
            sb.append(". ");
        }
        sb.append("See data.payload.stages and data.payload.errors.");

        java.util.Map<String, Object> err = ServiceUtil.returnError(sb.toString());
        err.put("successMessage", out.get("successMessage"));
        err.put("data", legacyData);
        return err;
    }

    return out;
}
/* ----------------------------
 * Helper used above (if you don't already have this overload):
 * safeInt(Object) to handle null / Number / String
 * ---------------------------- */
private static int safeInt(Object v) {
    if (v == null) return 0;
    if (v instanceof Number) return ((Number) v).intValue();
    try { return Integer.parseInt(String.valueOf(v)); } catch (Exception e) { return 0; }
}
   /**
     * Create PayrollRun if missing for execute mode.
     * IMPORTANT: statusId must match existing StatusItem in PAY_RUN_STAT (PR_*).
     *
     * THIS METHOD MUST NOT change an existing run status automatically.
     * If you want status transitions, do it in workflow services (approve/process/post).
     */
private static String ensurePayrollRunExistsForExecuteV2(LocalDispatcher dispatcher,
                                                        Delegator delegator,
                                                        String organizationPartyId,
                                                        String payrollPeriodId,
                                                        String currencyUomId,
                                                        String accountingPostOptionId,
                                                        String runTypeId,
                                                        String runName,
                                                        GenericValue userLogin) throws Exception {

    if (delegator == null) throw new GenericEntityException("Delegator is null");
    if (dispatcher == null) throw new GenericEntityException("Dispatcher is null");
    if (UtilValidate.isEmpty(organizationPartyId)) throw new GenericEntityException("organizationPartyId is required");
    if (UtilValidate.isEmpty(payrollPeriodId)) throw new GenericEntityException("payrollPeriodId is required");
    if (UtilValidate.isEmpty(currencyUomId)) throw new GenericEntityException("currencyUomId is required");
    if (userLogin == null) throw new GenericEntityException("userLogin is required");

    // ----------------------------
    // 1) Reuse policy (idempotency)
    // ----------------------------
    GenericValue reusable = EntityQuery.use(delegator)
            .from("PayrollRun")
            .where("organizationPartyId", organizationPartyId,
                   "payrollPeriodId", payrollPeriodId)
            .orderBy("-createdStamp")
            .cache(false)
            .queryFirst();

    if (reusable != null) {
        final String statusId = reusable.getString("statusId");

        // If already posted/finalized, do NOT reuse.
        final boolean alreadyNotReusable =
                ("Y".equalsIgnoreCase(safeStr(reusable, "isGlPosted")))
             || ("Y".equalsIgnoreCase(safeStr(reusable, "isInvoicePosted")))
             || PayrollStatusIds.RUN_PROCESSED.equals(statusId)
             || PayrollStatusIds.RUN_APPR.equals(statusId)
             || PayrollStatusIds.RUN_DONE.equals(statusId)
             || PayrollStatusIds.RUN_POST.equals(statusId);

        if (!alreadyNotReusable) {
            boolean changed = false;

            // Fill missing fields only (safe)
            if (UtilValidate.isNotEmpty(currencyUomId)
                    && hasField(reusable, "currencyUomId")
                    && UtilValidate.isEmpty(reusable.getString("currencyUomId"))) {
                reusable.set("currencyUomId", currencyUomId);
                changed = true;
            }

            if (UtilValidate.isNotEmpty(accountingPostOptionId)
                    && hasField(reusable, "accountingPostOptionId")
                    && UtilValidate.isEmpty(reusable.getString("accountingPostOptionId"))) {
                reusable.set("accountingPostOptionId", accountingPostOptionId);
                changed = true;
            }

            if (UtilValidate.isNotEmpty(runTypeId)
                    && hasField(reusable, "runTypeId")
                    && UtilValidate.isEmpty(reusable.getString("runTypeId"))) {
                reusable.set("runTypeId", runTypeId);
                changed = true;
            }

            if (UtilValidate.isNotEmpty(runName)
                    && hasField(reusable, "runName")
                    && UtilValidate.isEmpty(reusable.getString("runName"))) {
                reusable.set("runName", runName);
                changed = true;
            }

            // Reset posting flags if null (safety)
            if (hasField(reusable, "isGlPosted") && UtilValidate.isEmpty(reusable.getString("isGlPosted"))) {
                reusable.set("isGlPosted", "N");
                changed = true;
            }
            if (hasField(reusable, "isInvoicePosted") && UtilValidate.isEmpty(reusable.getString("isInvoicePosted"))) {
                reusable.set("isInvoicePosted", "N");
                changed = true;
            }

            if (changed) {
                delegator.store(reusable);
            }

            return reusable.getString("payrollRunId");
        }
    }

    // ----------------------------
    // 2) Create new run using CANONICAL service
    // ----------------------------
    Map<String, Object> createCtx = UtilMisc.<String, Object>toMap(
            "organizationPartyId", organizationPartyId,
            "payrollPeriodId", payrollPeriodId,
            "currencyUomId", currencyUomId,
            "userLogin", userLogin
    );

    if (UtilValidate.isNotEmpty(accountingPostOptionId)) createCtx.put("accountingPostOptionId", accountingPostOptionId);
    if (UtilValidate.isNotEmpty(runTypeId)) createCtx.put("runTypeId", runTypeId);
    if (UtilValidate.isNotEmpty(runName)) createCtx.put("runName", runName);

    Map<String, Object> out = dispatcher.runSync("createPayrollRun", createCtx);
    if (ServiceUtil.isError(out)) {
        throw new GenericEntityException("createPayrollRun failed: " + ServiceUtil.getErrorMessage(out));
    }

    String payrollRunId = (String) out.get("payrollRunId");
    if (UtilValidate.isEmpty(payrollRunId)) {
        throw new GenericEntityException("createPayrollRun returned empty payrollRunId");
    }
    return payrollRunId;
}

/** Field existence guard so this compiles even if some fields don't exist in entity model. */
private static boolean hasField(GenericValue gv, String fieldName) {
    try {
        return gv != null
                && gv.getModelEntity() != null
                && gv.getModelEntity().getField(fieldName) != null;
    } catch (Exception e) {
        return false;
    }
}

/** Safe getter for possibly-missing fields; returns null if field not present. */
private static String safeStr(GenericValue gv, String fieldName) {
    try {
        if (!hasField(gv, fieldName)) return null;
        return gv.getString(fieldName);
    } catch (Exception e) {
        return null;
    }
}
/** Field existence guard so this compiles even if some fields don't exist in entity model. */

    private static void addStageError(PayrollRunResponseBuilder.ResponseBuilder rb,
                                     PayrollRunResponseBuilder.OrgRunBuilder orgRb,
                                     List<String> flatErrors,
                                     String orgId,
                                     String stageId,
                                     String code,
                                     String msg) {
        try {
            orgRb.stage(stageId).error(msg);
        } catch (Exception ignore) {
            // don't destabilize response building
        }
        rb.addError(code, "org=" + orgId + " : " + msg);
        flatErrors.add("org=" + orgId + " " + stageId + ": " + msg);
    }

    private static String safeMsg(Throwable t) {
        if (t == null) return "null";
        String m = t.getMessage();
        return UtilValidate.isNotEmpty(m) ? m : t.toString();
    }

    private static int safeInt(long v) {
        if (v > Integer.MAX_VALUE) return Integer.MAX_VALUE;
        if (v < Integer.MIN_VALUE) return Integer.MIN_VALUE;
        return (int) v;
    }

    // Scheduler-required overload (kept for compatibility)
    public static PayrollValidationQueries.ValidationResult validateForMonthlyRun(Delegator delegator,
                                                                                 String organizationPartyId,
                                                                                 String payrollRunId,
                                                                                 String payrollPeriodId,
                                                                                 String currencyUomId,
                                                                                 String accountingPostOptionId,
                                                                                 String bankTransferConfigId) {
        return PayrollValidationQueries.validateForMonthlyRun(delegator, organizationPartyId, payrollRunId, payrollPeriodId,
                currencyUomId, accountingPostOptionId, bankTransferConfigId);
    }

    // ----------------------------
    // Queue workers V2 (self-reschedule)
    // ----------------------------
    public static Map<String, Object> processPayrollEmailQueueWorkerV2(DispatchContext dctx, Map<String, ?> context) {
        return runWorkerAndReschedule(dctx, context,
                "processPayrollEmailQueue",
                JOB_EMAIL_WORKER_V2,
                "processPayrollEmailQueueWorkerV2",
                EMAIL_INTERVAL_MS);
    }

    public static Map<String, Object> processPayrollSmsQueueWorkerV2(DispatchContext dctx, Map<String, ?> context) {
        return runWorkerAndReschedule(dctx, context,
                "processPayrollSmsQueue",
                JOB_SMS_WORKER_V2,
                "processPayrollSmsQueueWorkerV2",
                SMS_INTERVAL_MS);
    }

    public static Map<String, Object> processPayrollExportQueueWorkerV2(DispatchContext dctx, Map<String, ?> context) {
        return runWorkerAndReschedule(dctx, context,
                "processPayrollExportRequestQueue",
                JOB_EXPORT_WORKER_V2,
                "processPayrollExportQueueWorkerV2",
                EXPORT_INTERVAL_MS);
    }

    public static Map<String, Object> processPayrollComplianceDailyWorkerV2(DispatchContext dctx, Map<String, ?> context) {
        LocalDispatcher dispatcher = dctx.getDispatcher();
        Delegator delegator = dctx.getDelegator();
        GenericValue userLogin = (GenericValue) context.get("userLogin");

        try {
            runIfExists(dispatcher, "generatePayrollComplianceReport", UtilMisc.<String, Object>toMap(
                    "periodYear", context.get("periodYear"),
                    "periodMonth", context.get("periodMonth"),
                    "reportTypeId", context.get("reportTypeId"),
                    "sourceIp", "SCHEDULER",
                    "userLogin", userLogin
            ));
        } catch (Exception e) {
            Debug.logError(e, "V2 compliance worker failed", MODULE);
        }

        try {
            Timestamp nextRun = nextDailyTime(COMPLIANCE_HOUR);
            ensureSingleScheduled(delegator, JOB_COMPLIANCE_WORKER_V2, "processPayrollComplianceDailyWorkerV2", nextRun, userLogin);
        } catch (Exception e) {
            Debug.logError(e, "V2 compliance reschedule failed", MODULE);
            return ServiceUtil.returnError("V2 compliance reschedule failed: " + e.getMessage());
        }

        return ServiceUtil.returnSuccess();
    }

    // =========================================================
    // PRODUCTION FIX HELPERS
    // =========================================================

    private static GenericValue reloadPayrollRun(Delegator delegator, String payrollRunId) throws GenericEntityException {
        GenericValue run = EntityQuery.use(delegator)
                .from("PayrollRun")
                .where("payrollRunId", payrollRunId)
                .cache(false)
                .queryOne();
        if (run == null) throw new GenericEntityException("PayrollRun not found: " + payrollRunId);
        return run;
    }

    // =========================================================
    // Helpers (workers + optional service execution)
    // =========================================================
    private static Map<String, Object> runWorkerAndReschedule(DispatchContext dctx,
                                                             Map<String, ?> context,
                                                             String targetServiceName,
                                                             String jobName,
                                                             String workerServiceName,
                                                             long intervalMs) {
        LocalDispatcher dispatcher = dctx.getDispatcher();
        Delegator delegator = dctx.getDelegator();
        GenericValue userLogin = (GenericValue) context.get("userLogin");

        try {
            runIfExists(dispatcher, targetServiceName, UtilMisc.<String, Object>toMap("userLogin", userLogin));
        } catch (Exception e) {
            Debug.logError(e, "V2 worker target failed: " + targetServiceName, MODULE);
        }

        try {
            Timestamp nextRun = new Timestamp(System.currentTimeMillis() + intervalMs);
            ensureSingleScheduled(delegator, jobName, workerServiceName, nextRun, userLogin);
        } catch (Exception e) {
            Debug.logError(e, "V2 worker reschedule failed: " + jobName, MODULE);
            return ServiceUtil.returnError("V2 reschedule failed: " + e.getMessage());
        }

        return ServiceUtil.returnSuccess();
    }

    private static void runIfExists(LocalDispatcher dispatcher, String serviceName, Map<String, Object> ctx) throws Exception {
        if (!serviceExists(dispatcher, serviceName)) return;
        Map<String, Object> res = dispatcher.runSync(serviceName, ctx);
        if (ServiceUtil.isError(res)) {
            throw new Exception(serviceName + " error: " + ServiceUtil.getErrorMessage(res));
        }
    }

    private static Map<String, Object> runIfExistsReturn(LocalDispatcher dispatcher, String serviceName, Map<String, Object> ctx) throws Exception {
        if (!serviceExists(dispatcher, serviceName)) return null;
        Map<String, Object> res = dispatcher.runSync(serviceName, ctx);
        if (ServiceUtil.isError(res)) {
            throw new Exception(serviceName + " error: " + ServiceUtil.getErrorMessage(res));
        }
        return res;
    }

    private static boolean serviceExists(LocalDispatcher dispatcher, String serviceName) {
        try {
            DispatchContext dctx = dispatcher.getDispatchContext();
            ModelService ms = dctx.getModelService(serviceName);
            return ms != null;
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * Prevent duplicate pending jobs: (jobName + serviceName + status=PENDING).
     */
    private static void ensureSingleScheduled(Delegator delegator,
                                             String jobName,
                                             String serviceName,
                                             Timestamp nextRun,
                                             GenericValue userLogin) throws GenericEntityException {

        GenericValue existing = EntityQuery.use(delegator).from("JobSandbox")
                .where("jobName", jobName,
                        "serviceName", serviceName,
                        "statusId", "SERVICE_PENDING")
                .cache(false)
                .queryFirst();

        if (existing != null) return;

        GenericValue job = delegator.makeValue("JobSandbox");
        job.set("jobId", delegator.getNextSeqId("JobSandbox"));
        job.set("jobName", jobName);
        job.set("serviceName", serviceName);
        job.set("poolId", "pool");
        job.set("runAsUser", userLogin == null ? "system" : userLogin.getString("userLoginId"));
        job.set("runTime", nextRun.getTime());
        job.set("statusId", "SERVICE_PENDING");
        job.set("maxRetry", 3L);
        job.set("currentRetryCount", 0L);

        delegator.create(job);
    }

    private static Timestamp nextDailyTime(int hour) {
        Calendar c = Calendar.getInstance();
        c.setTime(UtilDateTime.nowTimestamp());

        c.set(Calendar.HOUR_OF_DAY, hour);
        c.set(Calendar.MINUTE, 0);
        c.set(Calendar.SECOND, 0);
        c.set(Calendar.MILLISECOND, 0);

        if (c.getTimeInMillis() <= System.currentTimeMillis()) {
            c.add(Calendar.DAY_OF_MONTH, 1);
        }
        return new Timestamp(c.getTimeInMillis());
    }

    private static Timestamp nextMonthlyTime(int dayOfMonth, int hour) {
        Calendar c = Calendar.getInstance();
        c.setTime(UtilDateTime.nowTimestamp());

        c.set(Calendar.DAY_OF_MONTH, dayOfMonth);
        c.set(Calendar.HOUR_OF_DAY, hour);
        c.set(Calendar.MINUTE, 0);
        c.set(Calendar.SECOND, 0);
        c.set(Calendar.MILLISECOND, 0);

        if (c.getTimeInMillis() <= System.currentTimeMillis()) {
            c.add(Calendar.MONTH, 1);
            c.set(Calendar.DAY_OF_MONTH, dayOfMonth);
            c.set(Calendar.HOUR_OF_DAY, hour);
        }
        return new Timestamp(c.getTimeInMillis());
    }
}
