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
import org.apache.ofbiz.service.ServiceUtil;

import java.sql.Timestamp;
import java.util.Calendar;
import java.util.List;
import java.util.Map;

/**
 * PayrollSchedulerServices (Rebuilt - "One Shot" + Safe Auto Approve/Post)
 *
 * Goals:
 * 1) Monthly payroll automation (per organization)
 * 2) Attendance summary build (before payroll processing) - optional (service-exists)
 * 3) Auto-approve - optional
 * 4) Auto-post - optional, and MUST NOT overwrite any business-selected option
 * 5) Queue workers: email/sms/export/compliance
 *    - Scheduled (self-rescheduling) workers (existing pattern)
 *    - "One shot" workers that process immediately and DO NOT schedule anything (for ops use)
 *
 * Upgrade-safe:
 * - plugin-only, no OFBiz core changes
 * - uses JobSandbox self-rescheduling pattern (works even without recurrenceRule)
 */
public class PayrollSchedulerServices {
    public static final String MODULE = PayrollSchedulerServices.class.getName();

    // ----------------------------
    // Job Names (for self-rescheduling)
    // ----------------------------
    public static final String JOB_MONTHLY_PAYROLL     = "PAYROLL_MONTHLY_RUNNER";
    public static final String JOB_EMAIL_WORKER        = "PAYROLL_EMAIL_WORKER";
    public static final String JOB_SMS_WORKER          = "PAYROLL_SMS_WORKER";
    public static final String JOB_EXPORT_WORKER       = "PAYROLL_EXPORT_WORKER";
    public static final String JOB_COMPLIANCE_WORKER   = "PAYROLL_COMPLIANCE_WORKER";

    // ----------------------------
    // Intervals / times
    // ----------------------------
    private static final long EMAIL_INTERVAL_MS  = 5L * 60L * 1000L;   // 5 minutes
    private static final long SMS_INTERVAL_MS    = 5L * 60L * 1000L;   // 5 minutes
    private static final long EXPORT_INTERVAL_MS = 10L * 60L * 1000L;  // 10 minutes

    private static final int COMPLIANCE_HOUR = 2; // 02:00 daily

    // Monthly payroll: schedule next run at day=1 01:00
    private static final int MONTHLY_RUN_DAY  = 1;
    private static final int MONTHLY_RUN_HOUR = 1;

    // ============================
    // A) MONTHLY PAYROLL RUNNER
    // ============================

    /**
     * Runs monthly payroll for all INTERNAL_ORGANIZATION parties.
     *
     * Inputs (all optional unless stated):
     * - runMonth (Integer): default current month
     * - runYear  (Integer): default current year
     * - payrollPeriodId (String): if provided, createPayrollRun will use it (recommended if you have period table)
     * - buildAttendance (Boolean): default TRUE -> call buildAttendancePeriodSummary if service exists
     * - autoApprove (Boolean): default TRUE -> call approvePayrollRun if service exists
     * - autoPost (Boolean): default FALSE -> call postPayrollRun if service exists and option resolves to non-NONE
     *
     * SAFE Posting option resolution (NO OVERWRITE):
     * - If context.accountingPostOptionId provided -> used as runtime override ONLY (not persisted)
     * - Else read PayrollRun.accountingPostOptionId from DB
     * - If still empty OR equals PAY_POST_NONE -> skip posting
     *
     * Additional post inputs (passed ONLY if provided):
     * - vendorPartyId (String)
     * - invoiceTypeId (String)
     * - forceRepost (Boolean)
     *
     * This scheduled version re-schedules itself monthly (no duplicates).
     */
    public static Map<String, Object> runMonthlyPayroll(DispatchContext dctx, Map<String, ?> context) {
        Delegator delegator = dctx.getDelegator();
        LocalDispatcher dispatcher = dctx.getDispatcher();
        GenericValue userLogin = (GenericValue) context.get("userLogin");

        try {
            // Orgs
            List<GenericValue> orgRoles = EntityQuery.use(delegator)
                    .from("PartyRole")
                    .where("roleTypeId", "INTERNAL_ORGANIZATION")
                    .queryList();

            Calendar cal = Calendar.getInstance();
            Integer runMonth = (Integer) context.get("runMonth");
            Integer runYear  = (Integer) context.get("runYear");
            if (runMonth == null) runMonth = cal.get(Calendar.MONTH) + 1; // 1..12
            if (runYear == null) runYear = cal.get(Calendar.YEAR);

            String payrollPeriodId = (String) context.get("payrollPeriodId");

            Boolean buildAttendance = (Boolean) context.get("buildAttendance");
            Boolean autoApprove = (Boolean) context.get("autoApprove");
            Boolean autoPost = (Boolean) context.get("autoPost");

            // Defaults
            if (buildAttendance == null) buildAttendance = Boolean.TRUE;
            if (autoApprove == null) autoApprove = Boolean.TRUE;
            if (autoPost == null) autoPost = Boolean.FALSE;

            int okCount = 0;
            int errCount = 0;

            for (GenericValue orgRole : orgRoles) {
                String organizationPartyId = orgRole.getString("partyId");
                try {
                    // Base context for create
                    Map<String, Object> createCtx = UtilMisc.toMap(
                            "organizationPartyId", organizationPartyId,
                            "userLogin", userLogin
                    );

                    // If your createPayrollRun supports month/year, pass them
                    createCtx.put("runMonth", runMonth);
                    createCtx.put("runYear", runYear);

                    // If you rely on PayrollPeriod, pass payrollPeriodId (recommended if mandatory in your service)
                    if (UtilValidate.isNotEmpty(payrollPeriodId)) {
                        createCtx.put("payrollPeriodId", payrollPeriodId);
                    }

                    // Attendance summary first (optional)
                    if (Boolean.TRUE.equals(buildAttendance)) {
                        runIfExists(dispatcher, "buildAttendancePeriodSummary", UtilMisc.toMap(
                                "organizationPartyId", organizationPartyId,
                                "runMonth", runMonth,
                                "runYear", runYear,
                                "userLogin", userLogin
                        ));
                    }

                    // Create run
                    Map<String, Object> created = dispatcher.runSync("createPayrollRun", createCtx);
                    if (ServiceUtil.isError(created)) {
                        errCount++;
                        Debug.logError("Monthly payroll createPayrollRun failed for org=" + organizationPartyId
                                + " err=" + ServiceUtil.getErrorMessage(created), MODULE);
                        continue;
                    }

                    String payrollRunId = (String) created.get("payrollRunId");
                    if (UtilValidate.isEmpty(payrollRunId)) {
                        errCount++;
                        Debug.logError("Monthly payroll createPayrollRun did not return payrollRunId for org=" + organizationPartyId, MODULE);
                        continue;
                    }

                    // Process (calculate)
                    Map<String, Object> processed = dispatcher.runSync("processPayrollRun", UtilMisc.toMap(
                            "payrollRunId", payrollRunId,
                            "forceRecalc", context.get("forceRecalc"),
                            "sourceIp", context.get("sourceIp"),
                            "userLogin", userLogin
                    ));
                    if (ServiceUtil.isError(processed)) {
                        errCount++;
                        Debug.logError("Monthly payroll processPayrollRun failed for org=" + organizationPartyId
                                + " run=" + payrollRunId
                                + " err=" + ServiceUtil.getErrorMessage(processed), MODULE);
                        continue;
                    }

                    // Auto approve (optional)
                    if (Boolean.TRUE.equals(autoApprove)) {
                        runIfExists(dispatcher, "approvePayrollRun", UtilMisc.toMap(
                                "payrollRunId", payrollRunId,
                                "sourceIp", context.get("sourceIp"),
                                "userLogin", userLogin
                        ));
                    }

                    // Auto post (optional, SAFE - no overwrite)
                    if (Boolean.TRUE.equals(autoPost)) {
                        safeAutoPostPayrollRun(dctx, context, payrollRunId, organizationPartyId);
                    }

                    okCount++;

                } catch (Exception perOrg) {
                    errCount++;
                    Debug.logError(perOrg, "Monthly payroll failed for org=" + organizationPartyId, MODULE);
                }
            }

            // Reschedule itself monthly (no duplicates)
            Timestamp nextMonthly = nextMonthlyTime(MONTHLY_RUN_DAY, MONTHLY_RUN_HOUR);
            ensureSingleScheduled(delegator, JOB_MONTHLY_PAYROLL, "runMonthlyPayroll", nextMonthly, userLogin);

            Map<String, Object> out = ServiceUtil.returnSuccess("Monthly payroll executed. ok=" + okCount + " err=" + errCount);
            out.put("okCount", okCount);
            out.put("errorCount", errCount);
            out.put("nextRunTime", nextMonthly);
            return out;

        } catch (Exception e) {
            Debug.logError(e, "runMonthlyPayroll failed", MODULE);
            return ServiceUtil.returnError("runMonthlyPayroll failed: " + e.getMessage());
        }
    }

    /**
     * One-shot version of monthly payroll runner:
     * - does NOT schedule next run
     * - intended for manual ops execution (fast, deterministic)
     *
     * Same parameters as runMonthlyPayroll.
     */
    public static Map<String, Object> runMonthlyPayrollOneShot(DispatchContext dctx, Map<String, ?> context) {
        // Reuse logic but without scheduling: simply call internal worker method.
        // To keep code simple and stable, we invoke runMonthlyPayroll and then DO NOT schedule here.
        // However runMonthlyPayroll schedules at end. So we implement directly by calling the same body without scheduling.
        return runMonthlyPayrollInternal(dctx, context, false);
    }

    /**
     * Internal implementation for monthly payroll.
     * @param doReschedule if true schedules the next monthly job.
     */
    private static Map<String, Object> runMonthlyPayrollInternal(DispatchContext dctx, Map<String, ?> context, boolean doReschedule) {
        Delegator delegator = dctx.getDelegator();
        LocalDispatcher dispatcher = dctx.getDispatcher();
        GenericValue userLogin = (GenericValue) context.get("userLogin");

        try {
            List<GenericValue> orgRoles = EntityQuery.use(delegator)
                    .from("PartyRole")
                    .where("roleTypeId", "INTERNAL_ORGANIZATION")
                    .queryList();

            Calendar cal = Calendar.getInstance();
            Integer runMonth = (Integer) context.get("runMonth");
            Integer runYear  = (Integer) context.get("runYear");
            if (runMonth == null) runMonth = cal.get(Calendar.MONTH) + 1;
            if (runYear == null) runYear = cal.get(Calendar.YEAR);

            String payrollPeriodId = (String) context.get("payrollPeriodId");

            Boolean buildAttendance = (Boolean) context.get("buildAttendance");
            Boolean autoApprove = (Boolean) context.get("autoApprove");
            Boolean autoPost = (Boolean) context.get("autoPost");

            if (buildAttendance == null) buildAttendance = Boolean.TRUE;
            if (autoApprove == null) autoApprove = Boolean.TRUE;
            if (autoPost == null) autoPost = Boolean.FALSE;

            int okCount = 0;
            int errCount = 0;

            for (GenericValue orgRole : orgRoles) {
                String organizationPartyId = orgRole.getString("partyId");
                try {
                    Map<String, Object> createCtx = UtilMisc.toMap(
                            "organizationPartyId", organizationPartyId,
                            "userLogin", userLogin
                    );
                    createCtx.put("runMonth", runMonth);
                    createCtx.put("runYear", runYear);
                    if (UtilValidate.isNotEmpty(payrollPeriodId)) {
                        createCtx.put("payrollPeriodId", payrollPeriodId);
                    }

                    if (Boolean.TRUE.equals(buildAttendance)) {
                        runIfExists(dispatcher, "buildAttendancePeriodSummary", UtilMisc.toMap(
                                "organizationPartyId", organizationPartyId,
                                "runMonth", runMonth,
                                "runYear", runYear,
                                "userLogin", userLogin
                        ));
                    }

                    Map<String, Object> created = dispatcher.runSync("createPayrollRun", createCtx);
                    if (ServiceUtil.isError(created)) {
                        errCount++;
                        Debug.logError("Monthly payroll createPayrollRun failed for org=" + organizationPartyId
                                + " err=" + ServiceUtil.getErrorMessage(created), MODULE);
                        continue;
                    }

                    String payrollRunId = (String) created.get("payrollRunId");
                    if (UtilValidate.isEmpty(payrollRunId)) {
                        errCount++;
                        Debug.logError("Monthly payroll createPayrollRun did not return payrollRunId for org=" + organizationPartyId, MODULE);
                        continue;
                    }

                    Map<String, Object> processed = dispatcher.runSync("processPayrollRun", UtilMisc.toMap(
                            "payrollRunId", payrollRunId,
                            "forceRecalc", context.get("forceRecalc"),
                            "sourceIp", context.get("sourceIp"),
                            "userLogin", userLogin
                    ));
                    if (ServiceUtil.isError(processed)) {
                        errCount++;
                        Debug.logError("Monthly payroll processPayrollRun failed for org=" + organizationPartyId
                                + " run=" + payrollRunId
                                + " err=" + ServiceUtil.getErrorMessage(processed), MODULE);
                        continue;
                    }

                    if (Boolean.TRUE.equals(autoApprove)) {
                        runIfExists(dispatcher, "approvePayrollRun", UtilMisc.toMap(
                                "payrollRunId", payrollRunId,
                                "sourceIp", context.get("sourceIp"),
                                "userLogin", userLogin
                        ));
                    }

                    if (Boolean.TRUE.equals(autoPost)) {
                        safeAutoPostPayrollRun(dctx, context, payrollRunId, organizationPartyId);
                    }

                    okCount++;

                } catch (Exception perOrg) {
                    errCount++;
                    Debug.logError(perOrg, "Monthly payroll failed for org=" + organizationPartyId, MODULE);
                }
            }

            if (doReschedule) {
                Timestamp nextMonthly = nextMonthlyTime(MONTHLY_RUN_DAY, MONTHLY_RUN_HOUR);
                ensureSingleScheduled(delegator, JOB_MONTHLY_PAYROLL, "runMonthlyPayroll", nextMonthly, userLogin);
            }

            Map<String, Object> out = ServiceUtil.returnSuccess("Monthly payroll executed. ok=" + okCount + " err=" + errCount);
            out.put("okCount", okCount);
            out.put("errorCount", errCount);
            return out;

        } catch (Exception e) {
            Debug.logError(e, "runMonthlyPayrollInternal failed", MODULE);
            return ServiceUtil.returnError("runMonthlyPayrollInternal failed: " + e.getMessage());
        }
    }

    /**
     * SAFE auto-post implementation:
     * - DOES NOT overwrite any business option
     * - Uses context override only for this run, otherwise reads PayrollRun.accountingPostOptionId from DB
     * - Skips posting if empty or PAY_POST_NONE
     */
    private static void safeAutoPostPayrollRun(DispatchContext dctx,
                                              Map<String, ?> context,
                                              String payrollRunId,
                                              String organizationPartyId) throws Exception {

        LocalDispatcher dispatcher = dctx.getDispatcher();
        Delegator delegator = dctx.getDelegator();
        GenericValue userLogin = (GenericValue) context.get("userLogin");

        // A) runtime override only (never persisted)
        String requestedPostOptionId = (String) context.get("accountingPostOptionId");
        String effectivePostOptionId = null;

        if (UtilValidate.isNotEmpty(requestedPostOptionId)) {
            effectivePostOptionId = requestedPostOptionId;
        } else {
            // B) read from PayrollRun (business config)
            GenericValue runGV = EntityQuery.use(delegator)
                    .from("PayrollRun")
                    .where("payrollRunId", payrollRunId)
                    .cache(false)
                    .queryOne();
            if (runGV != null) {
                effectivePostOptionId = runGV.getString("accountingPostOptionId");
            }
        }

        // C) safe default: skip posting
        if (UtilValidate.isEmpty(effectivePostOptionId) || "PAY_POST_NONE".equals(effectivePostOptionId)) {
            Debug.logInfo("AutoPost skipped for org=" + organizationPartyId + " run=" + payrollRunId
                    + " option=" + effectivePostOptionId, MODULE);
            return;
        }

        // Additional inputs: pass ONLY if provided (no default overwrite)
        String vendorPartyId = (String) context.get("vendorPartyId");
        String invoiceTypeId = (String) context.get("invoiceTypeId");
        Boolean forceRepost = (Boolean) context.get("forceRepost");
        String sourceIp = (String) context.get("sourceIp");

        Map<String, Object> postCtx = UtilMisc.toMap(
                "payrollRunId", payrollRunId,
                "accountingPostOptionId", effectivePostOptionId,
                "forceRepost", forceRepost,
                "sourceIp", sourceIp,
                "userLogin", userLogin
        );

        if (UtilValidate.isNotEmpty(vendorPartyId)) {
            postCtx.put("vendorPartyId", vendorPartyId);
        }
        if (UtilValidate.isNotEmpty(invoiceTypeId)) {
            postCtx.put("invoiceTypeId", invoiceTypeId);
        }

        // Call postPayrollRun only if it exists
        runIfExists(dispatcher, "postPayrollRun", postCtx);
    }

    // ============================
    // B) QUEUE WORKERS - SCHEDULED
    // ============================

    public static Map<String, Object> processPayrollEmailQueueWorker(DispatchContext dctx, Map<String, ?> context) {
        return runWorkerAndReschedule(dctx, context,
                "processPayrollEmailQueue",
                JOB_EMAIL_WORKER,
                "processPayrollEmailQueueWorker",
                EMAIL_INTERVAL_MS);
    }

    public static Map<String, Object> processPayrollSmsQueueWorker(DispatchContext dctx, Map<String, ?> context) {
        return runWorkerAndReschedule(dctx, context,
                "processPayrollSmsQueue",
                JOB_SMS_WORKER,
                "processPayrollSmsQueueWorker",
                SMS_INTERVAL_MS);
    }

    /**
     * Recommended: implement queue-style service: processPayrollExportRequestQueue
     * which scans pending export requests and processes up to maxItems.
     */
    public static Map<String, Object> processPayrollExportQueueWorker(DispatchContext dctx, Map<String, ?> context) {
        return runWorkerAndReschedule(dctx, context,
                "processPayrollExportRequestQueue",
                JOB_EXPORT_WORKER,
                "processPayrollExportQueueWorker",
                EXPORT_INTERVAL_MS);
    }

    public static Map<String, Object> processPayrollComplianceDailyWorker(DispatchContext dctx, Map<String, ?> context) {
        LocalDispatcher dispatcher = dctx.getDispatcher();
        Delegator delegator = dctx.getDelegator();
        GenericValue userLogin = (GenericValue) context.get("userLogin");

        try {
            runIfExists(dispatcher, "generatePayrollComplianceReport", UtilMisc.toMap("userLogin", userLogin));
        } catch (Exception e) {
            Debug.logError(e, "Compliance worker failed", MODULE);
        }

        try {
            Timestamp nextRun = nextDailyTime(COMPLIANCE_HOUR);
            ensureSingleScheduled(delegator, JOB_COMPLIANCE_WORKER, "processPayrollComplianceDailyWorker", nextRun, userLogin);
        } catch (Exception e) {
            Debug.logError(e, "Compliance reschedule failed", MODULE);
            return ServiceUtil.returnError("Compliance reschedule failed: " + e.getMessage());
        }

        return ServiceUtil.returnSuccess();
    }

    // ============================
    // C) QUEUE WORKERS - ONE SHOT
    // ============================

    /**
     * One-shot: process email queue ONCE (no scheduling).
     */
    public static Map<String, Object> processPayrollEmailQueueOnce(DispatchContext dctx, Map<String, ?> context) {
        return runWorkerOnce(dctx, context, "processPayrollEmailQueue");
    }

    /**
     * One-shot: process SMS queue ONCE (no scheduling).
     */
    public static Map<String, Object> processPayrollSmsQueueOnce(DispatchContext dctx, Map<String, ?> context) {
        return runWorkerOnce(dctx, context, "processPayrollSmsQueue");
    }

    /**
     * One-shot: process export request queue ONCE (no scheduling).
     */
    public static Map<String, Object> processPayrollExportQueueOnce(DispatchContext dctx, Map<String, ?> context) {
        return runWorkerOnce(dctx, context, "processPayrollExportRequestQueue");
    }

    /**
     * One-shot: process compliance generation ONCE (no scheduling).
     */
    public static Map<String, Object> processPayrollComplianceOnce(DispatchContext dctx, Map<String, ?> context) {
        LocalDispatcher dispatcher = dctx.getDispatcher();
        GenericValue userLogin = (GenericValue) context.get("userLogin");
        try {
            runIfExists(dispatcher, "generatePayrollComplianceReport", UtilMisc.toMap("userLogin", userLogin));
            return ServiceUtil.returnSuccess();
        } catch (Exception e) {
            Debug.logError(e, "Compliance one-shot failed", MODULE);
            return ServiceUtil.returnError("Compliance one-shot failed: " + e.getMessage());
        }
    }

    /**
     * One-shot: run ALL pending workers in a single call (no scheduling).
     * This is the "ops one-shot" to process everything when needed.
     */
    public static Map<String, Object> runAllPayrollWorkersOnce(DispatchContext dctx, Map<String, ?> context) {
        int ok = 0;
        int err = 0;

        Map<String, Object> r1 = processPayrollEmailQueueOnce(dctx, context);
        if (ServiceUtil.isError(r1)) err++; else ok++;

        Map<String, Object> r2 = processPayrollSmsQueueOnce(dctx, context);
        if (ServiceUtil.isError(r2)) err++; else ok++;

        Map<String, Object> r3 = processPayrollExportQueueOnce(dctx, context);
        if (ServiceUtil.isError(r3)) err++; else ok++;

        Map<String, Object> r4 = processPayrollComplianceOnce(dctx, context);
        if (ServiceUtil.isError(r4)) err++; else ok++;

        Map<String, Object> out = ServiceUtil.returnSuccess("runAllPayrollWorkersOnce completed. ok=" + ok + " err=" + err);
        out.put("okCount", ok);
        out.put("errorCount", err);
        return out;
    }

    // =========================================================
    // Helpers
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
            runIfExists(dispatcher, targetServiceName, UtilMisc.toMap("userLogin", userLogin));
        } catch (Exception e) {
            Debug.logError(e, "Worker target failed: " + targetServiceName, MODULE);
        }

        try {
            Timestamp nextRun = new Timestamp(System.currentTimeMillis() + intervalMs);
            ensureSingleScheduled(delegator, jobName, workerServiceName, nextRun, userLogin);
        } catch (Exception e) {
            Debug.logError(e, "Worker reschedule failed: " + jobName, MODULE);
            return ServiceUtil.returnError("Reschedule failed: " + e.getMessage());
        }

        return ServiceUtil.returnSuccess();
    }

    private static Map<String, Object> runWorkerOnce(DispatchContext dctx, Map<String, ?> context, String targetServiceName) {
        LocalDispatcher dispatcher = dctx.getDispatcher();
        GenericValue userLogin = (GenericValue) context.get("userLogin");

        try {
            runIfExists(dispatcher, targetServiceName, UtilMisc.toMap("userLogin", userLogin));
            return ServiceUtil.returnSuccess();
        } catch (Exception e) {
            Debug.logError(e, "One-shot worker failed: " + targetServiceName, MODULE);
            return ServiceUtil.returnError("One-shot worker failed (" + targetServiceName + "): " + e.getMessage());
        }
    }

    /**
     * Call a service only if it exists (upgrade-safe + optional dependencies).
     */
    private static void runIfExists(LocalDispatcher dispatcher, String serviceName, Map<String, Object> ctx) throws Exception {
        try {
            dispatcher.getDispatchContext().getModelService(serviceName);
        } catch (Exception notFound) {
            return; // not installed in this build
        }
        Map<String, Object> res = dispatcher.runSync(serviceName, ctx);
        if (ServiceUtil.isError(res)) {
            throw new Exception(serviceName + " error: " + ServiceUtil.getErrorMessage(res));
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
        job.set("maxRetry", Long.valueOf(3));
        job.set("currentRetryCount", Long.valueOf(0));

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
