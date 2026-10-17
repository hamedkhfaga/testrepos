package org.apache.ofbiz.payroll.services;

import org.apache.ofbiz.base.util.Debug;
import org.apache.ofbiz.base.util.UtilDateTime;
import org.apache.ofbiz.base.util.UtilMisc;
import org.apache.ofbiz.base.util.UtilValidate;

import org.apache.ofbiz.entity.Delegator;
import org.apache.ofbiz.entity.GenericEntityException;
import org.apache.ofbiz.entity.GenericValue;

import org.apache.ofbiz.entity.transaction.TransactionUtil;
import org.apache.ofbiz.entity.util.EntityQuery;

import org.apache.ofbiz.service.DispatchContext;
import org.apache.ofbiz.service.LocalDispatcher;
import org.apache.ofbiz.service.ServiceUtil;
import org.apache.ofbiz.payroll.util.PayrollStatusIds;

import java.sql.Timestamp;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.List;


/**
 * PayrollRunWorkflowServices (FINAL - OFBiz 24)
 *
 * Status model (aligned with PAY_RUN_STAT):
 * - PR_DRAFT      Draft (editable)
 * - PR_NEW        New (optional "created but not drafted" - kept for compatibility)
 * - PR_SUBM       Submitted (waiting approval)
 * - PR_APPR       Approved
 * - PR_PROC       Processing (in-progress)
 * - PR_PROCESSED  Processed (calculated results created)
 * - PR_POST       Posted (GL/Invoice)
 * - PR_DONE       Completed (final closed)
 * - PR_FAIL       Failed
 * - PR_VOID       Voided/Cancelled
 *
 * Canonical transitions (recommended):
 * createPayrollRun:        -> PR_DRAFT
 * submitPayrollRun:        PR_DRAFT/PR_NEW -> PR_SUBM
 * approvePayrollRun:       PR_SUBM/PR_PROCESSED -> PR_APPR
 * processPayrollRun:       PR_APPR -> PR_PROC -> PR_PROCESSED
 * complianceCheckPayrollRun: PR_PROCESSED/PR_APPR/PR_POST (no status change)
 * postPayrollRun:          PR_APPR/PR_PROCESSED -> PR_POST
 * completePayrollRun:      PR_POST -> PR_DONE
 * voidPayrollRun:          almost any non-DONE -> PR_VOID (guarded)
 *
 * Failure handling:
 * - Any service can mark PR_FAIL (with message) using markFailed(...) helper.
 *
 * Notes:
 * - Uses organizationPartyId everywhere (Option A)
 * - Strong tx hygiene: ALWAYS commit/rollback if tx started
 * - Defensive + idempotent transitions
 * - Never fails due to optional fields (ModelEntity.isField)
 * - Audit is non-blocking (optional service recordPayrollAuditEvent)
 */
public class PayrollRunWorkflowServices {

    public static final String MODULE = PayrollRunWorkflowServices.class.getName();

    // =========================
    // Status IDs (DB-aligned)
    // =========================
    private static final String PR_DRAFT     = "PR_DRAFT";
    private static final String PR_NEW       = "PR_NEW";
    private static final String PR_SUBM      = "PR_SUBM";
    private static final String PR_APPR      = "PR_APPR";
    private static final String PR_PROC      = "PR_PROC";
    private static final String PR_PROCESSED = "PR_PROCESSED";
    private static final String PR_POST      = "PR_POST";
    private static final String PR_DONE      = "PR_DONE";
    private static final String PR_FAIL      = "PR_FAIL";
    private static final String PR_VOID      = "PR_VOID";

    private static final Set<String> ALL_STATUSES = new HashSet<>(Arrays.asList(
            PR_DRAFT, PR_NEW, PR_SUBM, PR_APPR, PR_PROC, PR_PROCESSED, PR_POST, PR_DONE, PR_FAIL, PR_VOID
    ));

    // =========================
    // Accounting post options
    // =========================
    private static final String PAY_POST_NONE    = "PAY_POST_NONE";
    private static final String PAY_POST_GL      = "PAY_POST_GL";
    private static final String PAY_POST_INVOICE = "PAY_POST_INVOICE";
    private static final String PAY_POST_BOTH    = "PAY_POST_BOTH";

    // =========================================================
    // 1) Create Payroll Run  -> PR_DRAFT
    // =========================================================
public static Map<String, Object> createPayrollRun(DispatchContext dctx, Map<String, ?> context) {
    Delegator delegator = dctx.getDelegator();
    LocalDispatcher dispatcher = dctx.getDispatcher();
    GenericValue userLogin = (GenericValue) context.get("userLogin");

    String payrollPeriodId = asString(context, "payrollPeriodId");
    String organizationPartyId = firstOrg(context);
    String currencyUomId = asString(context, "currencyUomId");

    // Optional inputs (caller can override)
    String runNameIn = asString(context, "runName");
     String runTypeIdIn = asString(context, "runTypeId");
  // final String resolvedRunTypeId =
     //   UtilValidate.isNotEmpty(runTypeIdIn) ? runTypeIdIn : "RUN_MONTHLY";

    if (UtilValidate.isEmpty(payrollPeriodId)) return ServiceUtil.returnError("payrollPeriodId is required");
    if (UtilValidate.isEmpty(organizationPartyId)) return ServiceUtil.returnError("organizationPartyId is required");
    if (UtilValidate.isEmpty(currencyUomId)) return ServiceUtil.returnError("currencyUomId is required");
    if (userLogin == null) return ServiceUtil.returnError("userLogin is required");

    boolean beganTx = false;
    boolean success = false;

    try {
        beganTx = TransactionUtil.begin();

        // Hard check: PayrollPeriod exists for same org
        GenericValue period = EntityQuery.use(delegator)
                .from("PayrollPeriod")
                .where("payrollPeriodId", payrollPeriodId,
                        "organizationPartyId", organizationPartyId)
                .cache(false)
                .queryFirst();
        if (period == null) {
            return ServiceUtil.returnError("PayrollPeriod not found for org. payrollPeriodId=" + payrollPeriodId
                    + " organizationPartyId=" + organizationPartyId);
        }

 // Deterministic defaults (stable + readable)
final String defaultRunTypeId = "RUN_MONTHLY";
final String resolvedRunTypeId = UtilValidate.isNotEmpty(runTypeIdIn) ? runTypeIdIn : defaultRunTypeId;

// Prefer periodName if present; else fall back to payrollPeriodId
String periodName = null;
try { periodName = period.getString("periodName"); } catch (Exception ignored) {}

final String resolvedRunName;
if (UtilValidate.isNotEmpty(runNameIn)) {
    resolvedRunName = runNameIn;
} else {
    String base = organizationPartyId + "-" + (UtilValidate.isNotEmpty(periodName) ? periodName : payrollPeriodId);
    resolvedRunName = base + "-MONTHLY";
}

String payrollRunId = delegator.getNextSeqId("PayrollRun");
Timestamp now = UtilDateTime.nowTimestamp();

GenericValue run = delegator.makeValue("PayrollRun");
run.set("payrollRunId", payrollRunId);
run.set("organizationPartyId", organizationPartyId);
run.set("payrollPeriodId", payrollPeriodId);

// IMPORTANT: set once, no later overwrite
run.set("runTypeId", resolvedRunTypeId);
run.set("runName", resolvedRunName);

run.set("runDate", now);
run.set("currencyUomId", currencyUomId);
run.set("statusId", PR_DRAFT);

        // optional tracking fields
        safeSetIfField(run, "createdByUserLoginId", userLogin.getString("userLoginId"));
        safeSetIfField(run, "createdDate", now);
        safeSetIfField(run, "isGlPosted", "N");
        safeSetIfField(run, "isInvoicePosted", "N");
        safeSetIfField(run, "isCompliant", "N");
        safeSetIfField(run, "lastErrorMessage", null);

        delegator.create(run);

        audit(dispatcher, userLogin, organizationPartyId,
                "RUN_CREATE", "PayrollRun", payrollRunId,
                null,
                "Created payroll run status=" + PR_DRAFT
                        + " runTypeId=" + resolvedRunTypeId
                        + " runName=" + resolvedRunName,
                getSourceIp(context));

        success = true;
Debug.logInfo("CREATE_PAYROLL_RUN saving payrollRunId=" + payrollRunId
        + " runTypeId=" + resolvedRunTypeId
        + " runName=" + resolvedRunName, MODULE);
		
        Map<String, Object> out = ServiceUtil.returnSuccess();
        out.put("payrollRunId", payrollRunId);
        out.put("runTypeId", resolvedRunTypeId);
        out.put("runName", resolvedRunName);
        return out;

    } catch (Exception e) {
        Debug.logError(e, "createPayrollRun failed", MODULE);
        return ServiceUtil.returnError("createPayrollRun failed: " + safeMsg(e));
    } finally {
        endTxQuiet(beganTx, success, "createPayrollRun");
    }
}
  



  // =========================================================
    // 2) Submit Payroll Run  -> PR_SUBM
    // Allowed: PR_DRAFT, PR_NEW
    // Idempotent if already SUBM or beyond
    // =========================================================
    public static Map<String, Object> submitPayrollRun(DispatchContext dctx, Map<String, ?> context) {
        Delegator delegator = dctx.getDelegator();
        LocalDispatcher dispatcher = dctx.getDispatcher();
        GenericValue userLogin = (GenericValue) context.get("userLogin");

        String payrollRunId = asString(context, "payrollRunId");
        if (UtilValidate.isEmpty(payrollRunId)) return ServiceUtil.returnError("payrollRunId is required");
        if (userLogin == null) return ServiceUtil.returnError("userLogin is required");

        boolean beganTx = false;
        boolean success = false;

        try {
            beganTx = TransactionUtil.begin();

            GenericValue run = mustGetRun(delegator, payrollRunId);
            String orgId = run.getString("organizationPartyId");
            String statusId = run.getString("statusId");

            if (isTerminal(statusId)) {
                success = true;
                return ServiceUtil.returnSuccess();
            }

            // already submitted or later
            if (PR_SUBM.equals(statusId) || PR_APPR.equals(statusId) || PR_PROC.equals(statusId)
                    || PR_PROCESSED.equals(statusId) || PR_POST.equals(statusId)) {
                success = true;
                return ServiceUtil.returnSuccess();
            }

            if (!PR_DRAFT.equals(statusId) && !PR_NEW.equals(statusId)) {
                return ServiceUtil.returnError("PayrollRun must be DRAFT/NEW to submit. Current=" + statusId);
            }

            run.set("statusId", PR_SUBM);
            safeSetIfField(run, "submittedByUserLoginId", userLogin.getString("userLoginId"));
            safeSetIfField(run, "submittedDate", UtilDateTime.nowTimestamp());
            safeSetIfField(run, "lastErrorMessage", null);
            run.store();

            audit(dispatcher, userLogin, orgId,
                    "RUN_SUBMIT", "PayrollRun", payrollRunId,
                    statusId, "Submitted payroll run status=" + PR_SUBM, getSourceIp(context));

            success = true;
            return ServiceUtil.returnSuccess();

        } catch (Exception e) {
            Debug.logError(e, "submitPayrollRun failed", MODULE);
            return markFailedReturn(delegator, dctx.getDispatcher(), userLogin, payrollRunId, "submitPayrollRun failed", e, beganTx);
        } finally {
            endTxQuiet(beganTx, success, "submitPayrollRun");
        }
    }

    // =========================================================
    // 3) Approve Payroll Run -> PR_APPR
    // Allowed: PR_SUBM, PR_PROCESSED (re-approve after reprocessing)
    // Idempotent if already approved/posted/done/void
    // =========================================================
 public static Map<String, Object> approvePayrollRun(DispatchContext dctx, Map<String, ?> context) {
    final Delegator delegator = dctx.getDelegator();
    final LocalDispatcher dispatcher = dctx.getDispatcher();
    final GenericValue userLogin = (GenericValue) context.get("userLogin");

    final String payrollRunId = asString(context, "payrollRunId");
    if (UtilValidate.isEmpty(payrollRunId)) return ServiceUtil.returnError("payrollRunId is required");
    if (userLogin == null) return ServiceUtil.returnError("userLogin is required");

    boolean beganTx = false;
    boolean success = false;

    // Always return a non-null payload (aligned with your REST requirement)
    final Map<String, Object> data = new java.util.HashMap<>();
    data.put("service", "approvePayrollRun");
    data.put("payrollRunId", payrollRunId);
    data.put("userLoginId", userLogin.getString("userLoginId"));
    data.put("sourceIp", getSourceIp(context));
    data.put("timestamp", UtilDateTime.nowTimestamp());

    try {
        beganTx = TransactionUtil.begin();

        final GenericValue run = mustGetRun(delegator, payrollRunId);
        final String orgId = run.getString("organizationPartyId");
        final String oldStatusId = run.getString("statusId");
 

        data.put("organizationPartyId", orgId);
        data.put("oldStatusId", oldStatusId);

        // Terminal / idempotent states
        if (PR_APPR.equals(oldStatusId) || PR_POST.equals(oldStatusId) || PR_DONE.equals(oldStatusId) || PR_VOID.equals(oldStatusId)) {
            data.put("newStatusId", oldStatusId);
            data.put("changed", Boolean.FALSE);
            data.put("ok", Boolean.TRUE);
            data.put("message", "No change. PayrollRun already in status " + oldStatusId);

            final Map<String, Object> out = ServiceUtil.returnSuccess("No change. status=" + oldStatusId);
            out.put("payrollRunId", payrollRunId);
            out.put("statusId", oldStatusId);
            out.put("data", data);

            success = true;
            return out;
        }

        // Allow only from SUBMITTED or PROCESSED
        if (!PR_SUBM.equals(oldStatusId) && !PR_PROCESSED.equals(oldStatusId)) {
            data.put("newStatusId", oldStatusId);
            data.put("changed", Boolean.FALSE);
            data.put("ok", Boolean.FALSE);
            data.put("message", "Not allowed from status " + oldStatusId);

            final Map<String, Object> err =
                    ServiceUtil.returnError("PayrollRun must be SUBMITTED or PROCESSED to approve. Current=" + oldStatusId);
            err.put("payrollRunId", payrollRunId);
            err.put("statusId", oldStatusId);
            err.put("data", data);

            success = false;
            return err;
        }

        // Hard precondition (POC safety): must have at least one payroll result before approving.
        // If your entity differs, replace "EmployeePayrollResult" and keys accordingly.
        long resultCnt = EntityQuery.use(delegator)
                .from("EmployeePayrollResult")
                .where("payrollRunId", payrollRunId)
                .queryCount();

        data.put("resultCount", resultCnt);

        if (resultCnt <= 0) {
            data.put("newStatusId", oldStatusId);
            data.put("changed", Boolean.FALSE);
            data.put("ok", Boolean.FALSE);
            data.put("message", "Cannot approve: no EmployeePayrollResult found for runId=" + payrollRunId);

            final Map<String, Object> err =
                    ServiceUtil.returnError("Cannot approve payroll run: no payroll results exist for payrollRunId=" + payrollRunId);
            err.put("payrollRunId", payrollRunId);
            err.put("statusId", oldStatusId);
            err.put("data", data);

            success = false;
            return err;
        }

        // Approve
        run.set("statusId", PR_APPR);
        safeSetIfField(run, "approvedByUserLoginId", userLogin.getString("userLoginId"));
        safeSetIfField(run, "approvedDate", UtilDateTime.nowTimestamp());
        safeSetIfField(run, "lastErrorMessage", null);
        run.store();

        audit(dispatcher, userLogin, orgId,
                "RUN_APPROVE", "PayrollRun", payrollRunId,
                oldStatusId, "Approved payroll run status=" + PR_APPR, getSourceIp(context));

        data.put("newStatusId", PR_APPR);
        data.put("changed", Boolean.TRUE);
        data.put("ok", Boolean.TRUE);
        data.put("message", "Approved PayrollRun " + payrollRunId + " (" + oldStatusId + " -> " + PR_APPR + ")");

        final Map<String, Object> out =
                ServiceUtil.returnSuccess("Approved payroll runId=" + payrollRunId + " (" + oldStatusId + " -> " + PR_APPR + ")");
        out.put("payrollRunId", payrollRunId);
        out.put("statusId", PR_APPR);
        out.put("data", data);

        success = true;
        return out;

    } catch (Exception e) {
        Debug.logError(e, "approvePayrollRun failed", MODULE);

        Map<String, Object> err = markFailedReturn(delegator, dispatcher, userLogin, payrollRunId,
                "approvePayrollRun failed", e, beganTx);

        if (err == null) {
            err = ServiceUtil.returnError("approvePayrollRun failed: " + safeMsg(e));
        }

        data.put("ok", Boolean.FALSE);
        data.put("changed", Boolean.FALSE);
        data.put("message", "Exception: " + safeMsg(e));

        err.put("payrollRunId", payrollRunId);
        err.put("data", data);

        success = false;
        return err;

    } finally {
        endTxQuiet(beganTx, success, "approvePayrollRun");
    }
}

    // =========================================================
    // 4) Process Payroll Run (calculate)
    // Allowed start: PR_APPR
    // Steps: set PR_PROC -> engine -> set PR_PROCESSED
    // Idempotent: if already PR_PROCESSED and !forceRecalc => success
    // =========================================================
public static Map<String, Object> processPayrollRun(DispatchContext dctx, Map<String, ?> context) {
    final Delegator delegator = dctx.getDelegator();
    final LocalDispatcher dispatcher = dctx.getDispatcher();
    final GenericValue userLogin = (GenericValue) context.get("userLogin");

    final String payrollRunId = asString(context, "payrollRunId");
    final boolean forceRecalc =
            asBoolean(context.get("forceRecalc")) || asBoolean(context.get("forceRepost")); // tolerate legacy key

    if (UtilValidate.isEmpty(payrollRunId)) return ServiceUtil.returnError("payrollRunId is required");
    if (userLogin == null) return ServiceUtil.returnError("userLogin is required");

    boolean beganTx = false;
    boolean success = false;

    // Always return non-null payload for REST wrappers
    final Map<String, Object> data = new java.util.HashMap<>();
    data.put("service", "processPayrollRun");
    data.put("payrollRunId", payrollRunId);
    data.put("forceRecalc", forceRecalc);
    data.put("userLoginId", userLogin.getString("userLoginId"));
    data.put("sourceIp", getSourceIp(context));
    data.put("timestamp", UtilDateTime.nowTimestamp());

    try {
        beganTx = TransactionUtil.begin();

        final GenericValue run = mustGetRun(delegator, payrollRunId);
        final String orgId = run.getString("organizationPartyId");
        final String statusId = run.getString("statusId");

        data.put("organizationPartyId", orgId);
        data.put("oldStatusId", statusId);

        // Final states (never process)
        if (PR_POST.equals(statusId) || PR_DONE.equals(statusId) || PR_VOID.equals(statusId)) {
            Map<String, Object> err = ServiceUtil.returnError("PayrollRun is final; cannot process. Current=" + statusId);
            data.put("ok", Boolean.FALSE);
            data.put("changed", Boolean.FALSE);
            data.put("message", "Final state: " + statusId);
            err.put("data", data);
            err.put("payrollRunId", payrollRunId);
            err.put("statusId", statusId);
            success = false;
            return err;
        }

        // Idempotent: already processed and not forcing
        if (PR_PROCESSED.equals(statusId) && !forceRecalc) {
            Map<String, Object> out = ServiceUtil.returnSuccess("No change. status=" + statusId);
            data.put("ok", Boolean.TRUE);
            data.put("changed", Boolean.FALSE);
            data.put("newStatusId", statusId);
            data.put("message", "Already processed");
            out.put("data", data);
            out.put("payrollRunId", payrollRunId);
            out.put("statusId", statusId);
            success = true;
            return out;
        }

        // Governance:
        // - Normal path: must be APPROVED (or already PROCESSING/PROCESSED for idempotency)
        // - Exception: allow SUBMITTED/DRAFT/NEW only when forceRecalc=Y (scheduler/test/recovery)
        final boolean normalAllowed =
                PR_APPR.equals(statusId) || PR_PROC.equals(statusId) || PR_PROCESSED.equals(statusId);

        final boolean forceAllowed =
                forceRecalc && (PR_SUBM.equals(statusId) || PR_DRAFT.equals(statusId) || PR_NEW.equals(statusId));

        if (!normalAllowed && !forceAllowed) {
            Map<String, Object> err = ServiceUtil.returnError(
                    "PayrollRun must be APPROVED to process. Current=" + statusId
                            + " (use forceRecalc=Y for scheduler/test/recovery).");
            data.put("ok", Boolean.FALSE);
            data.put("changed", Boolean.FALSE);
            data.put("message", "Not allowed from status " + statusId);
            err.put("data", data);
            err.put("payrollRunId", payrollRunId);
            err.put("statusId", statusId);
            success = false;
            return err;
        }

        // Mark processing (only if not already PR_PROC)
        final Timestamp startTs = UtilDateTime.nowTimestamp();
        if (!PR_PROC.equals(statusId)) {
            run.set("statusId", PR_PROC);
            safeSetIfField(run, "processingStartDate", startTs);
            safeSetIfField(run, "processingByUserLoginId", userLogin.getString("userLoginId"));
            safeSetIfField(run, "lastErrorMessage", null);
            run.store();

            audit(dispatcher, userLogin, orgId,
                    "RUN_PROCESS_BEGIN", "PayrollRun", payrollRunId,
                    statusId, "Processing started status=" + PR_PROC, getSourceIp(context));
        } else {
            // already processing (retry scenario)
            safeSetIfField(run, "lastErrorMessage", null);
            run.store();
        }

        // Delegate engine (must be stable / idempotent itself)
        // IMPORTANT: engine should key by payrollRunId and org to avoid cross-org pollution.
        PayrollProcessingEngine.process(delegator, run, userLogin.getString("userLoginId"));

        // Mark processed
        run.refresh();
        run.set("statusId", PR_PROCESSED);
        safeSetIfField(run, "processingEndDate", UtilDateTime.nowTimestamp());
        safeSetIfField(run, "lastErrorMessage", null);
        run.store();

        audit(dispatcher, userLogin, orgId,
                "RUN_PROCESS_END", "PayrollRun", payrollRunId,
                PR_PROC, "Processing finished status=" + PR_PROCESSED, getSourceIp(context));

        // Optional: include result count for REST visibility (best-effort)
        long resultCnt = 0L;
        try {
            resultCnt = EntityQuery.use(delegator)
                    .from("EmployeePayrollResult")
                    .where("payrollRunId", payrollRunId)
                    .queryCount();
        } catch (Exception ignored) { }
        data.put("resultCount", resultCnt);

        Map<String, Object> out = ServiceUtil.returnSuccess("Processed payrollRunId=" + payrollRunId);
        data.put("ok", Boolean.TRUE);
        data.put("changed", Boolean.TRUE);
        data.put("newStatusId", PR_PROCESSED);
        data.put("message", "Processed successfully");

        out.put("data", data);
        out.put("payrollRunId", payrollRunId);
        out.put("statusId", PR_PROCESSED);

        success = true;
        return out;

    } catch (Exception e) {
        Debug.logError(e, "processPayrollRun failed payrollRunId=" + payrollRunId, MODULE);

        Map<String, Object> err = markFailedReturn(delegator, dispatcher, userLogin, payrollRunId,
                "processPayrollRun failed", e, beganTx);

        if (err == null) {
            err = ServiceUtil.returnError("processPayrollRun failed: " + safeMsg(e));
        }

        data.put("ok", Boolean.FALSE);
        data.put("changed", Boolean.FALSE);
        data.put("message", "Exception: " + safeMsg(e));
        err.put("data", data);
        err.put("payrollRunId", payrollRunId);

        success = false;
        return err;

    } finally {
        endTxQuiet(beganTx, success, "processPayrollRun");
    }
}

    // =========================================================
    // 5) Compliance check (no status change)
    // Allowed: PR_PROCESSED, PR_APPR, PR_POST
    // =========================================================
    public static Map<String, Object> complianceCheckPayrollRun(DispatchContext dctx, Map<String, ?> context) {
        Delegator delegator = dctx.getDelegator();
        LocalDispatcher dispatcher = dctx.getDispatcher();
        GenericValue userLogin = (GenericValue) context.get("userLogin");

        String payrollRunId = asString(context, "payrollRunId");
        if (UtilValidate.isEmpty(payrollRunId)) return ServiceUtil.returnError("payrollRunId is required");
        if (userLogin == null) return ServiceUtil.returnError("userLogin is required");

        boolean beganTx = false;
        boolean success = false;

        try {
            beganTx = TransactionUtil.begin();

            GenericValue run = mustGetRun(delegator, payrollRunId);
            String orgId = run.getString("organizationPartyId");
            String statusId = run.getString("statusId");

            if (!PR_PROCESSED.equals(statusId) && !PR_APPR.equals(statusId) && !PR_POST.equals(statusId)) {
                return ServiceUtil.returnError("PayrollRun must be PROCESSED/APPROVED/POSTED before compliance check. Current=" + statusId);
            }

            safeSetIfField(run, "isCompliant", "Y");
            safeSetIfField(run, "complianceCheckedByUserLoginId", userLogin.getString("userLoginId"));
            safeSetIfField(run, "complianceCheckDate", UtilDateTime.nowTimestamp());
            run.store();

            audit(dispatcher, userLogin, orgId,
                    "RUN_COMPLIANCE_OK", "PayrollRun", payrollRunId,
                    null, "Compliance checked", getSourceIp(context));

            success = true;
            return ServiceUtil.returnSuccess();

        } catch (Exception e) {
            Debug.logError(e, "complianceCheckPayrollRun failed", MODULE);
            return ServiceUtil.returnError("complianceCheckPayrollRun failed: " + safeMsg(e));
        } finally {
            endTxQuiet(beganTx, success, "complianceCheckPayrollRun");
        }
    }

    // =========================================================
    // 6) Post Payroll Run -> PR_POST
    // Allowed: PR_APPR or PR_PROCESSED (some orgs allow post right after processed)
    // Idempotent if already PR_POST/PR_DONE
    // =========================================================
 public static Map<String, Object> postPayrollRun(DispatchContext dctx, Map<String, ?> context) {
    final Delegator delegator = dctx.getDelegator();
    final LocalDispatcher dispatcher = dctx.getDispatcher();
    final GenericValue userLogin = (GenericValue) context.get("userLogin");

    final String payrollRunId = asString(context, "payrollRunId");
    final String orgIdIn = asString(context, "organizationPartyId"); // optional, verify vs run
    String accountingPostOptionId = asString(context, "accountingPostOptionId");
    final boolean forceRepost = asBoolean(context.get("forceRepost"));

    if (UtilValidate.isEmpty(payrollRunId)) return ServiceUtil.returnError("payrollRunId is required");
    if (userLogin == null) return ServiceUtil.returnError("userLogin is required");

    boolean beganTx = false;
    boolean success = false;

    // Always return non-null payload for REST
    final Map<String, Object> data = new java.util.HashMap<>();
    data.put("service", "postPayrollRun");
    data.put("payrollRunId", payrollRunId);
    data.put("userLoginId", userLogin.getString("userLoginId"));
    data.put("sourceIp", getSourceIp(context));
    data.put("timestamp", UtilDateTime.nowTimestamp());
    data.put("forceRepost", forceRepost);
 
    try {
        beganTx = TransactionUtil.begin();

        final GenericValue run = mustGetRun(delegator, payrollRunId);
        final String orgId = run.getString("organizationPartyId");
        final String oldStatusId = run.getString("statusId");

        data.put("organizationPartyId", orgId);
        data.put("oldStatusId", oldStatusId);

        // Optional input org check (helps catch wrong payloads)
        if (UtilValidate.isNotEmpty(orgIdIn) && !orgId.equals(orgIdIn)) {
            Map<String, Object> err = ServiceUtil.returnError(
                    "organizationPartyId mismatch. Input=" + orgIdIn + " but PayrollRun.org=" + orgId);
            data.put("ok", Boolean.FALSE);
            data.put("changed", Boolean.FALSE);
            data.put("message", "Org mismatch");
            err.put("data", data);
            err.put("payrollRunId", payrollRunId);
            err.put("statusId", oldStatusId);
            success = false;
            return err;
        }

        // Terminal state guard (DONE is terminal; VOID blocks)
        if (PR_DONE.equals(oldStatusId)) {
            Map<String, Object> err = ServiceUtil.returnError("PayrollRun is DONE; cannot post/repost. Current=" + oldStatusId);
            data.put("ok", Boolean.FALSE);
            data.put("changed", Boolean.FALSE);
            data.put("message", "DONE");
            err.put("data", data);
            err.put("payrollRunId", payrollRunId);
            err.put("statusId", oldStatusId);
            success = false;
            return err;
        }
        if (PR_VOID.equals(oldStatusId)) {
            Map<String, Object> err = ServiceUtil.returnError("PayrollRun is VOIDED; cannot post. Current=" + oldStatusId);
            data.put("ok", Boolean.FALSE);
            data.put("changed", Boolean.FALSE);
            data.put("message", "VOIDED");
            err.put("data", data);
            err.put("payrollRunId", payrollRunId);
            err.put("statusId", oldStatusId);
            success = false;
            return err;
        }

        // Governance: allow posting from APPROVED/PROCESSED.
        // If already POSTED, allow ONLY when forceRepost=true (channel-specific repost).
        if (!PR_APPR.equals(oldStatusId) && !PR_PROCESSED.equals(oldStatusId) && !PR_POST.equals(oldStatusId)) {
            Map<String, Object> err = ServiceUtil.returnError(
                    "PayrollRun must be APPROVED or PROCESSED to post (or POSTED with forceRepost=true). Current=" + oldStatusId);
            data.put("ok", Boolean.FALSE);
            data.put("changed", Boolean.FALSE);
            data.put("message", "Not allowed from status " + oldStatusId);
            err.put("data", data);
            err.put("payrollRunId", payrollRunId);
            err.put("statusId", oldStatusId);
            success = false;
            return err;
        }
        if (PR_POST.equals(oldStatusId) && !forceRepost) {
            data.put("newStatusId", oldStatusId);
            data.put("changed", Boolean.FALSE);
            data.put("ok", Boolean.TRUE);
            data.put("message", "No change. PayrollRun already in status " + oldStatusId + " (set forceRepost=true to repost)");

            Map<String, Object> out = ServiceUtil.returnSuccess("No change. status=" + oldStatusId);
            out.put("payrollRunId", payrollRunId);
            out.put("statusId", oldStatusId);
            out.put("data", data);
            success = true;
            return out;
        }

        // Resolve post option (input -> run field -> default)
        if (UtilValidate.isEmpty(accountingPostOptionId) && run.getModelEntity() != null
                && run.getModelEntity().isField("accountingPostOptionId")) {
            accountingPostOptionId = run.getString("accountingPostOptionId");
        }

        // Normalize aliases (prevents PO_INV bug)
        if (UtilValidate.isNotEmpty(accountingPostOptionId)) {
            String opt = accountingPostOptionId.trim();
            if ("PO_GL".equalsIgnoreCase(opt)) accountingPostOptionId = PAY_POST_GL;
            else if ("PO_INV".equalsIgnoreCase(opt) || "PO_INVOICE".equalsIgnoreCase(opt)) accountingPostOptionId = PAY_POST_INVOICE;
            else if ("PO_BOTH".equalsIgnoreCase(opt)) accountingPostOptionId = PAY_POST_BOTH;
            else if ("PO_NONE".equalsIgnoreCase(opt)) accountingPostOptionId = PAY_POST_NONE;
        }

        if (UtilValidate.isEmpty(accountingPostOptionId)) accountingPostOptionId = PAY_POST_GL;
        data.put("postOptionId", accountingPostOptionId);

        // POC safety: Must have results before posting/reposting
        long resultCnt = EntityQuery.use(delegator)
                .from("EmployeePayrollResult")
                .where("payrollRunId", payrollRunId)
                .queryCount();
        data.put("resultCount", resultCnt);

        if (resultCnt <= 0) {
            Map<String, Object> err = ServiceUtil.returnError(
                    "Cannot post: no EmployeePayrollResult found for payrollRunId=" + payrollRunId);
            data.put("ok", Boolean.FALSE);
            data.put("changed", Boolean.FALSE);
            data.put("message", "No results to post");
            err.put("data", data);
            err.put("payrollRunId", payrollRunId);
            err.put("statusId", oldStatusId);
            success = false;
            return err;
        }

        // Handle NONE explicitly (do NOT mark posted)
        if (PAY_POST_NONE.equals(accountingPostOptionId)) {
            audit(dispatcher, userLogin, orgId,
                    "RUN_POST_SKIP", "PayrollRun", payrollRunId,
                    oldStatusId, "Post option NONE (no posting executed)", getSourceIp(context));

            data.put("newStatusId", oldStatusId);
            data.put("changed", Boolean.FALSE);
            data.put("ok", Boolean.TRUE);
            data.put("didGl", Boolean.FALSE);
            data.put("didInvoice", Boolean.FALSE);
            data.put("message", "Skipped posting (PAY_POST_NONE)");

            Map<String, Object> out = ServiceUtil.returnSuccess("Skipped posting (PAY_POST_NONE)");
            out.put("payrollRunId", payrollRunId);
            out.put("statusId", oldStatusId);
            out.put("data", data);

            success = true;
            return out;
        }

        // Existing posting state (for channel-specific repost decisions)
        boolean alreadyGlPosted = "Y".equalsIgnoreCase(asString(run, "isGlPosted"));
        boolean alreadyInvPosted = "Y".equalsIgnoreCase(asString(run, "isInvoicePosted"));
        String existingGlJournalId = asString(run, "glJournalId");
        String existingInvoiceId = asString(run, "invoiceId");

        data.put("alreadyGlPosted", alreadyGlPosted);
        data.put("alreadyInvoicePosted", alreadyInvPosted);
        data.put("existingGlJournalId", existingGlJournalId);
        data.put("existingInvoiceId", existingInvoiceId);

        // Determine what needs to run
        boolean wantsGl = PAY_POST_GL.equals(accountingPostOptionId) || PAY_POST_BOTH.equals(accountingPostOptionId);
        boolean wantsInv = PAY_POST_INVOICE.equals(accountingPostOptionId) || PAY_POST_BOTH.equals(accountingPostOptionId);

        boolean needGl = wantsGl && (forceRepost || !alreadyGlPosted || UtilValidate.isEmpty(existingGlJournalId));
        boolean needInv = wantsInv && (forceRepost || !alreadyInvPosted || UtilValidate.isEmpty(existingInvoiceId));
///
Debug.logInfo("postPayrollRun INVOICE_DECISION runId=" + payrollRunId
        + " wantsInv=" + wantsInv
        + " needInv=" + needInv
        + " alreadyInvPosted=" + alreadyInvPosted
        + " existingInvoiceId=" + existingInvoiceId
        + " option=" + accountingPostOptionId, MODULE);

///
        data.put("needGl", needGl);
        data.put("needInvoice", needInv);

        // If user asked for a channel but it is already posted and forceRepost=false, we would have returned earlier for PR_POST.
        // Here (forceRepost=true or status not PR_POST), if neither channel is needed, return no-change safely.
        if (!needGl && !needInv) {
            data.put("newStatusId", oldStatusId);
            data.put("changed", Boolean.FALSE);
            data.put("ok", Boolean.TRUE);
            data.put("message", "No work needed. Posting already satisfied for requested option.");

            Map<String, Object> out = ServiceUtil.returnSuccess("No change. Posting already satisfied.");
            out.put("payrollRunId", payrollRunId);
            out.put("statusId", oldStatusId);
            out.put("data", data);

            success = true;
            return out;
        }

        final Map<String, Object> out = ServiceUtil.returnSuccess();
        boolean didGl = false;
        boolean didInv = false;

        // -------- GL Posting --------
        if (needGl) {
            Map<String, Object> ctx = UtilMisc.toMap(
                    "payrollRunId", payrollRunId,
                    "organizationPartyId", orgId,
                    "forceRepost", forceRepost ? "Y" : "N",
                    "userLogin", userLogin
            );
            Map<String, Object> res = dispatcher.runSync("postPayrollRunToGl", ctx);
            if (ServiceUtil.isError(res)) {
                res.put("data", data);
                return res;
            }

            Object glJournalId = res.get("glJournalId");
            if (UtilValidate.isEmpty(glJournalId)) {
                Map<String, Object> err = ServiceUtil.returnError(
                        "postPayrollRunToGl returned success but glJournalId is empty. Refusing to mark GL posted.");
                data.put("ok", Boolean.FALSE);
                data.put("changed", Boolean.FALSE);
                data.put("message", "Missing glJournalId");
                err.put("data", data);
                err.put("payrollRunId", payrollRunId);
                err.put("statusId", oldStatusId);
                success = false;
                return err;
            }

            out.put("glJournalId", glJournalId);
            didGl = true;
        } else if (wantsGl) {
            // preserve existing id for response clarity
            if (UtilValidate.isNotEmpty(existingGlJournalId)) out.put("glJournalId", existingGlJournalId);
        }

        // -------- Invoice Posting --------
        if (needInv) {
            Map<String, Object> ctx = UtilMisc.toMap(
                    "payrollRunId", payrollRunId,
                    "organizationPartyId", orgId,
                    "vendorPartyId", context.get("vendorPartyId"),
                    "invoiceTypeId", context.get("invoiceTypeId"),
                    "forceRepost", forceRepost ? "Y" : "N",
                    "userLogin", userLogin
            );
            Map<String, Object> res = dispatcher.runSync("postPayrollRunToInvoice", ctx);
            if (ServiceUtil.isError(res)) {
                res.put("data", data);
                return res;
            }

            Object invoiceId = res.get("invoiceId");
            if (UtilValidate.isEmpty(invoiceId)) {
                Map<String, Object> err = ServiceUtil.returnError(
                        "postPayrollRunToInvoice returned success but invoiceId is empty. Refusing to mark invoice posted.");
                data.put("ok", Boolean.FALSE);
                data.put("changed", Boolean.FALSE);
                data.put("message", "Missing invoiceId");
                err.put("data", data);
                err.put("payrollRunId", payrollRunId);
                err.put("statusId", oldStatusId);
                success = false;
                return err;
            }

            out.put("invoiceId", invoiceId);
            didInv = true;
        } else if (wantsInv) {
            // preserve existing id for response clarity
            if (UtilValidate.isNotEmpty(existingInvoiceId)) out.put("invoiceId", existingInvoiceId);
        }

        // Must have actually done something in this call
        if (!didGl && !didInv) {
            Map<String, Object> err = ServiceUtil.returnError(
                    "Nothing posted in this call. Refusing to update PayrollRun.");
            data.put("ok", Boolean.FALSE);
            data.put("changed", Boolean.FALSE);
            data.put("message", "No posting executed");
            err.put("data", data);
            err.put("payrollRunId", payrollRunId);
            err.put("statusId", oldStatusId);
            success = false;
            return err;
        }

        // Update run flags/dates/IDs; status becomes PR_POST if not already
        Timestamp now = UtilDateTime.nowTimestamp();
        if (!PR_POST.equals(oldStatusId)) {
            run.set("statusId", PR_POST);
        }

        if (didGl) {
            safeSetIfField(run, "isGlPosted", "Y");
            safeSetIfField(run, "glPostedDate", now);
            safeSetIfField(run, "glJournalId", out.get("glJournalId"));
        }
        if (didInv) {
            safeSetIfField(run, "isInvoicePosted", "Y");
            safeSetIfField(run, "invoicePostedDate", now);
            safeSetIfField(run, "invoiceId", out.get("invoiceId"));
        }
		///
      safeSetIfField(run, "accountingPostOptionId", accountingPostOptionId);
///
        safeSetIfField(run, "lastErrorMessage", null);
        run.store();

        audit(dispatcher, userLogin, orgId,
                "RUN_POST", "PayrollRun", payrollRunId,
                oldStatusId,
                "Posted payroll run option=" + accountingPostOptionId + " forceRepost=" + (forceRepost ? "Y" : "N"),
                getSourceIp(context));

        // Build response payload
        data.put("newStatusId", PR_POST.equals(oldStatusId) ? PR_POST : PR_POST);
        data.put("changed", Boolean.TRUE);
        data.put("ok", Boolean.TRUE);
        data.put("didGl", didGl);
        data.put("didInvoice", didInv);
        data.put("glJournalId", out.get("glJournalId"));
        data.put("invoiceId", out.get("invoiceId"));
        data.put("message", "Posted successfully");

        out.put("payrollRunId", payrollRunId);
        out.put("statusId", PR_POST.equals(oldStatusId) ? PR_POST : PR_POST);
        out.put("data", data);

        success = true;
        return out;

    } catch (Exception e) {
        Debug.logError(e, "postPayrollRun failed", MODULE);

        Map<String, Object> err = markFailedReturn(delegator, dispatcher, userLogin, payrollRunId,
                "postPayrollRun failed", e, beganTx);

        if (err == null) {
            err = ServiceUtil.returnError("postPayrollRun failed: " + safeMsg(e));
        }

        data.put("ok", Boolean.FALSE);
        data.put("changed", Boolean.FALSE);
        data.put("message", "Exception: " + safeMsg(e));
        err.put("payrollRunId", payrollRunId);
        err.put("data", data);

        success = false;
        return err;

    } finally {
        endTxQuiet(beganTx, success, "postPayrollRun");
    }
}

// Helper overload: safely read String field from GenericValue without exceptions
private static String asString(GenericValue gv, String fieldName) {
    try {
        Object v = (gv == null) ? null : gv.get(fieldName);
        return v == null ? null : String.valueOf(v);
    } catch (Exception ignored) {
        return null;
    }
}

    // =========================================================
    // 7) Complete Payroll Run -> PR_DONE
    // Allowed: PR_POST
    // =========================================================
    public static Map<String, Object> completePayrollRun(DispatchContext dctx, Map<String, ?> context) {
        Delegator delegator = dctx.getDelegator();
        LocalDispatcher dispatcher = dctx.getDispatcher();
        GenericValue userLogin = (GenericValue) context.get("userLogin");

        String payrollRunId = asString(context, "payrollRunId");
        if (UtilValidate.isEmpty(payrollRunId)) return ServiceUtil.returnError("payrollRunId is required");
        if (userLogin == null) return ServiceUtil.returnError("userLogin is required");

        boolean beganTx = false;
        boolean success = false;

        try {
            beganTx = TransactionUtil.begin();

            GenericValue run = mustGetRun(delegator, payrollRunId);
            String orgId = run.getString("organizationPartyId");
            String statusId = run.getString("statusId");

            if (PR_DONE.equals(statusId)) {
                success = true;
                return ServiceUtil.returnSuccess();
            }
            if (!PR_POST.equals(statusId)) {
                return ServiceUtil.returnError("PayrollRun must be POSTED to complete. Current=" + statusId);
            }

            run.set("statusId", PR_DONE);
            safeSetIfField(run, "completedByUserLoginId", userLogin.getString("userLoginId"));
            safeSetIfField(run, "completedDate", UtilDateTime.nowTimestamp());
            safeSetIfField(run, "lastErrorMessage", null);
            run.store();

            audit(dispatcher, userLogin, orgId,
                    "RUN_COMPLETE", "PayrollRun", payrollRunId,
                    PR_POST, "Completed payroll run status=" + PR_DONE, getSourceIp(context));

            success = true;
            return ServiceUtil.returnSuccess();

        } catch (Exception e) {
            Debug.logError(e, "completePayrollRun failed", MODULE);
            return markFailedReturn(delegator, dispatcher, userLogin, payrollRunId, "completePayrollRun failed", e, beganTx);
        } finally {
            endTxQuiet(beganTx, success, "completePayrollRun");
        }
    }

    // =========================================================
    // 8) Void Payroll Run -> PR_VOID
    // Allowed: any except PR_DONE (guarded)
    // =========================================================
    public static Map<String, Object> voidPayrollRun(DispatchContext dctx, Map<String, ?> context) {
        Delegator delegator = dctx.getDelegator();
        LocalDispatcher dispatcher = dctx.getDispatcher();
        GenericValue userLogin = (GenericValue) context.get("userLogin");

        String payrollRunId = asString(context, "payrollRunId");
        String reason = asString(context, "reason");
        if (UtilValidate.isEmpty(payrollRunId)) return ServiceUtil.returnError("payrollRunId is required");
        if (userLogin == null) return ServiceUtil.returnError("userLogin is required");
        if (UtilValidate.isEmpty(reason)) reason = "Voided by user";

        boolean beganTx = false;
        boolean success = false;

        try {
            beganTx = TransactionUtil.begin();

            GenericValue run = mustGetRun(delegator, payrollRunId);
            String orgId = run.getString("organizationPartyId");
            String statusId = run.getString("statusId");

            if (PR_VOID.equals(statusId)) {
                success = true;
                return ServiceUtil.returnSuccess();
            }
            if (PR_DONE.equals(statusId)) {
                return ServiceUtil.returnError("PayrollRun is COMPLETED; cannot void. Current=" + statusId);
            }

            run.set("statusId", PR_VOID);
            safeSetIfField(run, "voidedByUserLoginId", userLogin.getString("userLoginId"));
            safeSetIfField(run, "voidedDate", UtilDateTime.nowTimestamp());
            safeSetIfField(run, "voidReason", reason);
            run.store();

            audit(dispatcher, userLogin, orgId,
                    "RUN_VOID", "PayrollRun", payrollRunId,
                    statusId, "Voided payroll run reason=" + reason, getSourceIp(context));

            success = true;
            return ServiceUtil.returnSuccess();

        } catch (Exception e) {
            Debug.logError(e, "voidPayrollRun failed", MODULE);
            return ServiceUtil.returnError("voidPayrollRun failed: " + safeMsg(e));
        } finally {
            endTxQuiet(beganTx, success, "voidPayrollRun");
        }
    }

    // =========================================================
    // 9) Reopen to Draft (Ops/Admin) -> PR_DRAFT
    // Allowed: PR_FAIL, PR_SUBM, PR_APPR, PR_PROCESSED
    // Not allowed: PR_POST, PR_DONE, PR_VOID
    // =========================================================
    public static Map<String, Object> reopenPayrollRunToDraft(DispatchContext dctx, Map<String, ?> context) {
        Delegator delegator = dctx.getDelegator();
        LocalDispatcher dispatcher = dctx.getDispatcher();
        GenericValue userLogin = (GenericValue) context.get("userLogin");

        String payrollRunId = asString(context, "payrollRunId");
        String reason = asString(context, "reason");

        if (UtilValidate.isEmpty(payrollRunId)) return ServiceUtil.returnError("payrollRunId is required");
        if (userLogin == null) return ServiceUtil.returnError("userLogin is required");
        if (UtilValidate.isEmpty(reason)) reason = "Reopened to draft";

        boolean beganTx = false;
        boolean success = false;

        try {
            beganTx = TransactionUtil.begin();

            GenericValue run = mustGetRun(delegator, payrollRunId);
            String orgId = run.getString("organizationPartyId");
            String statusId = run.getString("statusId");

            if (PR_DRAFT.equals(statusId)) {
                success = true;
                return ServiceUtil.returnSuccess();
            }
            if (PR_POST.equals(statusId) || PR_DONE.equals(statusId) || PR_VOID.equals(statusId)) {
                return ServiceUtil.returnError("Cannot reopen from POSTED/DONE/VOID. Current=" + statusId);
            }

            if (!PR_FAIL.equals(statusId) && !PR_SUBM.equals(statusId) && !PR_APPR.equals(statusId) && !PR_PROCESSED.equals(statusId)) {
                return ServiceUtil.returnError("Reopen allowed only from FAIL/SUBM/APPR/PROCESSED. Current=" + statusId);
            }

            run.set("statusId", PR_DRAFT);
            safeSetIfField(run, "reopenedByUserLoginId", userLogin.getString("userLoginId"));
            safeSetIfField(run, "reopenedDate", UtilDateTime.nowTimestamp());
            safeSetIfField(run, "reopenReason", reason);
            run.store();

            audit(dispatcher, userLogin, orgId,
                    "RUN_REOPEN", "PayrollRun", payrollRunId,
                    statusId, "Reopened to draft reason=" + reason, getSourceIp(context));

            success = true;
            return ServiceUtil.returnSuccess();

        } catch (Exception e) {
            Debug.logError(e, "reopenPayrollRunToDraft failed", MODULE);
            return ServiceUtil.returnError("reopenPayrollRunToDraft failed: " + safeMsg(e));
        } finally {
            endTxQuiet(beganTx, success, "reopenPayrollRunToDraft");
        }
    }

    // =========================================================
    // 10) Set status (Admin override) - guarded
    // Input: payrollRunId, statusId
    // =========================================================
    public static Map<String, Object> setPayrollRunStatus(DispatchContext dctx, Map<String, ?> context) {
        Delegator delegator = dctx.getDelegator();
        LocalDispatcher dispatcher = dctx.getDispatcher();
        GenericValue userLogin = (GenericValue) context.get("userLogin");

        String payrollRunId = asString(context, "payrollRunId");
        String newStatusId = asString(context, "statusId");
        String reason = asString(context, "reason");

        if (UtilValidate.isEmpty(payrollRunId)) return ServiceUtil.returnError("payrollRunId is required");
        if (UtilValidate.isEmpty(newStatusId)) return ServiceUtil.returnError("statusId is required");
        if (userLogin == null) return ServiceUtil.returnError("userLogin is required");
        if (!ALL_STATUSES.contains(newStatusId)) return ServiceUtil.returnError("Invalid statusId: " + newStatusId);
        if (UtilValidate.isEmpty(reason)) reason = "Admin override";

        boolean beganTx = false;
        boolean success = false;

        try {
            beganTx = TransactionUtil.begin();

            GenericValue run = mustGetRun(delegator, payrollRunId);
            String orgId = run.getString("organizationPartyId");
            String oldStatus = run.getString("statusId");

            if (PR_DONE.equals(oldStatus)) {
                return ServiceUtil.returnError("Cannot override COMPLETED run");
            }

            run.set("statusId", newStatusId);
            safeSetIfField(run, "statusOverrideByUserLoginId", userLogin.getString("userLoginId"));
            safeSetIfField(run, "statusOverrideDate", UtilDateTime.nowTimestamp());
            safeSetIfField(run, "statusOverrideReason", reason);
            run.store();

            audit(dispatcher, userLogin, orgId,
                    "RUN_STATUS_OVERRIDE", "PayrollRun", payrollRunId,
                    oldStatus, "Override status to " + newStatusId + " reason=" + reason, getSourceIp(context));

            success = true;
            return ServiceUtil.returnSuccess();

        } catch (Exception e) {
            Debug.logError(e, "setPayrollRunStatus failed", MODULE);
            return ServiceUtil.returnError("setPayrollRunStatus failed: " + safeMsg(e));
        } finally {
            endTxQuiet(beganTx, success, "setPayrollRunStatus");
        }
    }

    // =========================================================
    // 11) Mark failed (public service) -> PR_FAIL
    // Input: payrollRunId, errorMessage
    // =========================================================
    public static Map<String, Object> markPayrollRunFailed(DispatchContext dctx, Map<String, ?> context) {
        Delegator delegator = dctx.getDelegator();
        LocalDispatcher dispatcher = dctx.getDispatcher();
        GenericValue userLogin = (GenericValue) context.get("userLogin");

        String payrollRunId = asString(context, "payrollRunId");
        String errorMessage = asString(context, "errorMessage");

        if (UtilValidate.isEmpty(payrollRunId)) return ServiceUtil.returnError("payrollRunId is required");
        if (userLogin == null) return ServiceUtil.returnError("userLogin is required");
        if (UtilValidate.isEmpty(errorMessage)) errorMessage = "Marked failed";

        boolean beganTx = false;
        boolean success = false;

        try {
            beganTx = TransactionUtil.begin();
            GenericValue run = mustGetRun(delegator, payrollRunId);

            String orgId = run.getString("organizationPartyId");
            String oldStatus = run.getString("statusId");

            if (PR_DONE.equals(oldStatus) || PR_VOID.equals(oldStatus)) {
                return ServiceUtil.returnError("Cannot mark FAILED after DONE/VOID. Current=" + oldStatus);
            }

            run.set("statusId", PR_FAIL);
            safeSetIfField(run, "lastErrorMessage", trimToLen(errorMessage, 4000));
            safeSetIfField(run, "failedDate", UtilDateTime.nowTimestamp());
            safeSetIfField(run, "failedByUserLoginId", userLogin.getString("userLoginId"));
            run.store();

            audit(dispatcher, userLogin, orgId,
                    "RUN_FAIL", "PayrollRun", payrollRunId,
                    oldStatus, "Failed: " + errorMessage, getSourceIp(context));

            success = true;
            return ServiceUtil.returnSuccess();

        } catch (Exception e) {
            Debug.logError(e, "markPayrollRunFailed failed", MODULE);
            return ServiceUtil.returnError("markPayrollRunFailed failed: " + safeMsg(e));
        } finally {
            endTxQuiet(beganTx, success, "markPayrollRunFailed");
        }
    }

    // =========================================================
    // Helpers
    // =========================================================

    private static String firstOrg(Map<String, ?> context) {
        String organizationPartyId = asString(context, "organizationPartyId");
        if (UtilValidate.isEmpty(organizationPartyId)) organizationPartyId = asString(context, "orgPartyId");
        return organizationPartyId;
    }

    private static GenericValue mustGetRun(Delegator delegator, String payrollRunId) throws GenericEntityException {
        GenericValue run = EntityQuery.use(delegator)
                .from("PayrollRun")
                .where("payrollRunId", payrollRunId)
                .queryOne();
        if (run == null) throw new IllegalArgumentException("PayrollRun not found: " + payrollRunId);
        return run;
    }

    private static boolean isTerminal(String statusId) {
        return PR_DONE.equals(statusId) || PR_VOID.equals(statusId);
    }

    private static void safeSetIfField(GenericValue gv, String fieldName, Object value) {
        try {
            if (gv != null && gv.getModelEntity() != null && gv.getModelEntity().isField(fieldName)) {
                gv.set(fieldName, value);
            }
        } catch (Exception ignored) {
            // never fail due to optional fields
        }
    }

    private static String getSourceIp(Map<String, ?> context) {
        Object v = context.get("sourceIp");
        return v == null ? null : String.valueOf(v);
    }

    private static String asString(Map<String, ?> ctx, String key) {
        if (ctx == null) return null;
        Object v = ctx.get(key);
        return v == null ? null : String.valueOf(v);
    }

    private static boolean asBoolean(Object v) {
        if (v == null) return false;
        if (v instanceof Boolean) return (Boolean) v;
        String s = String.valueOf(v).trim();
        return "Y".equalsIgnoreCase(s) || "YES".equalsIgnoreCase(s)
                || "TRUE".equalsIgnoreCase(s) || "1".equals(s);
    }

    private static String trimToLen(String s, int max) {
        if (s == null) return null;
        if (s.length() <= max) return s;
        return s.substring(0, max);
    }

    private static String safeMsg(Throwable t) {
        if (t == null) return "null";
        String m = t.getMessage();
        return UtilValidate.isNotEmpty(m) ? m : t.toString();
    }

    private static void endTxQuiet(boolean beganTx, boolean success, String label) {
        try {
            if (!beganTx) return;
            if (success) {
                TransactionUtil.commit(beganTx);
            } else {
                TransactionUtil.rollback(beganTx, label + " rollback", null);
            }
        } catch (Exception ignored) {
            // keep stable
        }
    }

    /**
     * Central failure handler used by workflow methods.
     * - Best effort update to PR_FAIL
     * - Returns ServiceUtil.returnError with safe message
     */
    private static Map<String, Object> markFailedReturn(Delegator delegator,
                                                       LocalDispatcher dispatcher,
                                                       GenericValue userLogin,
                                                       String payrollRunId,
                                                       String label,
                                                       Exception e,
                                                       boolean beganTx) {
        try {
            if (UtilValidate.isNotEmpty(payrollRunId)) {
                // If current tx is marked rollback only, we still try best-effort store.
                GenericValue run = EntityQuery.use(delegator)
                        .from("PayrollRun")
                        .where("payrollRunId", payrollRunId)
                        .queryOne();
                if (run != null) {
                    String orgId = run.getString("organizationPartyId");
                    String oldStatus = run.getString("statusId");

                    if (!PR_DONE.equals(oldStatus) && !PR_VOID.equals(oldStatus)) {
                        run.set("statusId", PR_FAIL);
                        safeSetIfField(run, "lastErrorMessage", trimToLen(label + ": " + safeMsg(e), 4000));
                        safeSetIfField(run, "failedDate", UtilDateTime.nowTimestamp());
                        if (userLogin != null) safeSetIfField(run, "failedByUserLoginId", userLogin.getString("userLoginId"));
                        run.store();

                        audit(dispatcher, userLogin, orgId,
                                "RUN_FAIL", "PayrollRun", payrollRunId,
                                oldStatus, "Failed: " + safeMsg(e), null);
                    }
                }
            }
        } catch (Exception ignored) {
            // do not block error response
        }
        Debug.logError(e, label, MODULE);
        return ServiceUtil.returnError(label + ": " + safeMsg(e));
    }

/*

public static Map<String, Object> generateBankTransferBatchForRun(DispatchContext dctx, Map<String, ?> context) {
    final Delegator delegator = dctx.getDelegator();
    final GenericValue userLogin = (GenericValue) context.get("userLogin");

    final String payrollRunId = asString(context, "payrollRunId");
    final String bankTransferConfigId = asString(context, "bankTransferConfigId");
    final String sourceIp = asString(context, "sourceIp");

    if (UtilValidate.isEmpty(payrollRunId)) return ServiceUtil.returnError("payrollRunId is required");
    if (UtilValidate.isEmpty(bankTransferConfigId)) return ServiceUtil.returnError("bankTransferConfigId is required");
    if (userLogin == null) return ServiceUtil.returnError("userLogin is required");

    boolean beganTx = false;
    boolean success = false;

    final Map<String, Object> data = new java.util.HashMap<>();
    data.put("service", "generateBankTransferBatchForRun");
    data.put("payrollRunId", payrollRunId);
    data.put("bankTransferConfigId", bankTransferConfigId);
    data.put("userLoginId", userLogin.getString("userLoginId"));
    data.put("sourceIp", sourceIp);

    final java.sql.Timestamp auditTs = UtilDateTime.nowTimestamp();
    data.put("timestamp", auditTs);

    try {
        beganTx = TransactionUtil.begin();

        // 1) Load run
        final GenericValue run = EntityQuery.use(delegator)
                .from("PayrollRun")
                .where("payrollRunId", payrollRunId)
                .cache(false)
                .queryOne();
        if (run == null) {
            Map<String, Object> err = ServiceUtil.returnError("PayrollRun not found payrollRunId=" + payrollRunId);
            err.put("data", data);
            return err;
        }

        final String orgId = run.getString("organizationPartyId");
        final String runStatusId = run.getString("statusId");
        final String runCurrency = run.getString("currencyUomId");

        data.put("organizationPartyId", orgId);
        data.put("runStatusId", runStatusId);

        // Governance (Pattern A): allow after payroll is finalized.
        // Accept PROCESSED, APPROVED, or POSTED (POST is later than PROCESSED/APPROVED).
        if (!(PayrollStatusIds.RUN_PROCESSED.equals(runStatusId)
                || PayrollStatusIds.RUN_APPR.equals(runStatusId)
                || PayrollStatusIds.RUN_POST.equals(runStatusId))) {

            Map<String, Object> err = ServiceUtil.returnError(
                    "Bank batch can be generated only after payroll is PROCESSED/APPROVED/POSTED. Current=" + runStatusId);
            err.put("data", data);
            return err;
        }

        // Idempotency: if already generated, return existing
        final String existingBatchId = run.getString("generatedBankTransferBatchId");
        if (UtilValidate.isNotEmpty(existingBatchId)) {
            Map<String, Object> out = ServiceUtil.returnSuccess("Bank batch already exists batchId=" + existingBatchId);
            data.put("bankTransferBatchId", existingBatchId);
            data.put("ok", Boolean.TRUE);
            data.put("changed", Boolean.FALSE);
            out.put("bankTransferBatchId", existingBatchId);
            out.put("data", data);
            success = true;
            return out;
        }

        // 2) Load config
        final GenericValue cfg = EntityQuery.use(delegator)
                .from("BankTransferConfig")
                .where("bankTransferConfigId", bankTransferConfigId)
                .cache(false)
                .queryOne();

        if (cfg == null) {
            Map<String, Object> err = ServiceUtil.returnError("BankTransferConfig not found id=" + bankTransferConfigId);
            err.put("data", data);
            return err;
        }

        final String cfgOrg = cfg.getString("organizationPartyId");
        if (UtilValidate.isNotEmpty(cfgOrg) && !cfgOrg.equals(orgId)) {
            Map<String, Object> err = ServiceUtil.returnError(
                    "BankTransferConfig org mismatch. cfg.org=" + cfgOrg + " run.org=" + orgId);
            err.put("data", data);
            return err;
        }

        if (!"Y".equalsIgnoreCase(cfg.getString("isActive"))) {
            Map<String, Object> err = ServiceUtil.returnError("BankTransferConfig is not active id=" + bankTransferConfigId);
            err.put("data", data);
            return err;
        }

        // 3) Load results
        final List<GenericValue> results = EntityQuery.use(delegator)
                .from("EmployeePayrollResult")
                .where("payrollRunId", payrollRunId)
                .orderBy("payrollResultId")
                .cache(false)
                .queryList();

        if (UtilValidate.isEmpty(results)) {
            Map<String, Object> err = ServiceUtil.returnError("No EmployeePayrollResult found for payrollRunId=" + payrollRunId);
            err.put("data", data);
            return err;
        }

        // 4) Create batch header
        final String bankTransferBatchId = delegator.getNextSeqId("BankTransferBatch");
        final GenericValue batch = delegator.makeValue("BankTransferBatch");
        batch.put("bankTransferBatchId", bankTransferBatchId);
        batch.put("organizationPartyId", orgId);
        batch.put("payrollRunId", payrollRunId);
        batch.put("bankTransferConfigId", bankTransferConfigId);

        String batchCurrency = runCurrency;
        if (UtilValidate.isEmpty(batchCurrency)) batchCurrency = results.get(0).getString("currencyUomId");
        batch.put("currencyUomId", batchCurrency);

        batch.put("totalAmount", java.math.BigDecimal.ZERO);
        batch.put("totalRecords", Long.valueOf(0));
        batch.put("generatedDate", auditTs);
        batch.put("statusId", "BTB_NEW");
        batch.put("createdByUserLoginId", userLogin.getString("userLoginId"));
        batch.put("createdDate", auditTs);

        delegator.create(batch);

        // 5) Create lines from allocations (Scenario B)
        long createdLines = 0L;
        long skippedNonPositive = 0L;
        long skippedNoAlloc = 0L;
        long skippedNoPaymentMethod = 0L;
        long skippedNoEftAccount = 0L;

        java.math.BigDecimal totalAmount = java.math.BigDecimal.ZERO;
        long seq = 1L;

        final java.sql.Timestamp now = auditTs;

        for (GenericValue r : results) {
            java.math.BigDecimal net = r.getBigDecimal("netSalary");
            if (net == null) net = java.math.BigDecimal.ZERO;
            if (net.signum() <= 0) {
                skippedNonPositive++;
                continue;
            }

            final String partyId = r.getString("partyId");
            java.math.BigDecimal remaining = net;

            // Active allocations for this employee
            final List<GenericValue> allocs = EntityQuery.use(delegator)
                    .from("EmployeePaymentAllocation")
                    .where(org.apache.ofbiz.entity.condition.EntityCondition.makeCondition(java.util.Arrays.asList(
                            org.apache.ofbiz.entity.condition.EntityCondition.makeCondition("partyId", org.apache.ofbiz.entity.condition.EntityOperator.EQUALS, partyId),
                            org.apache.ofbiz.entity.condition.EntityCondition.makeCondition("statusId", org.apache.ofbiz.entity.condition.EntityOperator.EQUALS, "PAY_ALLOC_ACT"),
                            org.apache.ofbiz.entity.condition.EntityCondition.makeCondition("fromDate", org.apache.ofbiz.entity.condition.EntityOperator.LESS_THAN_EQUAL_TO, now),
                            org.apache.ofbiz.entity.condition.EntityCondition.makeCondition(
                                    org.apache.ofbiz.entity.condition.EntityCondition.makeCondition("thruDate", org.apache.ofbiz.entity.condition.EntityOperator.EQUALS, null),
                                    org.apache.ofbiz.entity.condition.EntityOperator.OR,
                                    org.apache.ofbiz.entity.condition.EntityCondition.makeCondition("thruDate", org.apache.ofbiz.entity.condition.EntityOperator.GREATER_THAN_EQUAL_TO, now)
                            )
                    )))
                    .orderBy("prioritySeq")
                    .cache(false)
                    .queryList();

            if (UtilValidate.isEmpty(allocs)) {
                skippedNoAlloc++;
                continue; // strict mode could error; Pattern A keeps it non-blocking
            }

            for (GenericValue a : allocs) {
                if (remaining.signum() <= 0) break;

                final String allocType = a.getString("allocationTypeEnumId"); // PAY_ALLOC_AMT / PAY_ALLOC_PCT
                final java.math.BigDecimal allocValue = bd(a.get("allocationValue"));
                final String isRemainder = a.getString("isDefaultRemainder"); // Y/N
                final String paymentMethodId = a.getString("paymentMethodId");

                if (UtilValidate.isEmpty(paymentMethodId)) {
                    skippedNoPaymentMethod++;
                    continue;
                }

                // Load payment method
                final GenericValue pm = EntityQuery.use(delegator)
                        .from("PaymentMethod")
                        .where("paymentMethodId", paymentMethodId)
                        .cache(false)
                        .queryOne();
                if (pm == null) {
                    skippedNoPaymentMethod++;
                    continue;
                }

                // Only EFT_ACCOUNT supported for now
                if (!"EFT_ACCOUNT".equals(pm.getString("paymentMethodTypeId"))) {
                    skippedNoPaymentMethod++;
                    continue;
                }

                final GenericValue eft = EntityQuery.use(delegator)
                        .from("EftAccount")
                        .where("paymentMethodId", paymentMethodId)
                        .cache(false)
                        .queryOne();
                if (eft == null) {
                    skippedNoEftAccount++;
                    continue;
                }

                java.math.BigDecimal payAmt = java.math.BigDecimal.ZERO;

                if ("Y".equalsIgnoreCase(isRemainder)) {
                    payAmt = remaining;
                } else if ("PAY_ALLOC_AMT".equals(allocType)) {
                    payAmt = remaining.min(allocValue);
                } else if ("PAY_ALLOC_PCT".equals(allocType)) {
                    // percent of net, capped by remaining
                    java.math.BigDecimal pct = allocValue;
                    payAmt = net.multiply(pct).divide(new java.math.BigDecimal("100"), 2, java.math.RoundingMode.HALF_UP);
                    if (payAmt.compareTo(remaining) > 0) payAmt = remaining;
                } else {
                    // Unknown type -> skip
                    continue;
                }

                if (payAmt.signum() <= 0) continue;

                final String lineId = delegator.getNextSeqId("BankTransferBatchLine");
                final GenericValue line = delegator.makeValue("BankTransferBatchLine");
                line.put("bankTransferBatchLineId", lineId);
                line.put("bankTransferBatchId", bankTransferBatchId);
                line.put("payrollResultId", r.getString("payrollResultId"));

                line.put("partyId", partyId);
                line.put("paymentMethodId", paymentMethodId);
                line.put("paymentAllocationId", a.getString("paymentAllocationId"));

                line.put("amount", payAmt);
                line.put("currencyUomId", UtilValidate.isNotEmpty(r.getString("currencyUomId")) ? r.getString("currencyUomId") : batchCurrency);
                line.put("beneficiaryName", resolvePartyDisplayName(delegator, partyId));

                // map EFT -> bank fields
                line.put("bankName", eft.getString("bankName"));
                line.put("bankAccountNumber", eft.getString("accountNumber")); // may be encrypted if entity field has encrypt="true"

                line.put("statusId", "BTBL_NEW");
                line.put("sequenceNum", Long.valueOf(seq++));

                delegator.create(line);

                createdLines++;
                totalAmount = totalAmount.add(payAmt);
                remaining = remaining.subtract(payAmt);
            }

            // strict mode example:
            // if (remaining.signum() > 0) return ServiceUtil.returnError("Unallocated remainder for partyId="+partyId+" remaining="+remaining);
        }

        // 6) Update batch totals
        batch.put("totalAmount", totalAmount);
        batch.put("totalRecords", Long.valueOf(createdLines));
        batch.store();

        // 7) Write-back to PayrollRun (Pattern A)
        // IMPORTANT: inside same TX; do not fail whole service if write-back fails.
        try {
            GenericValue runToUpdate = EntityQuery.use(delegator)
                    .from("PayrollRun")
                    .where("payrollRunId", payrollRunId)
                    .cache(false)
                    .queryOne();

            if (runToUpdate != null) {
                runToUpdate.put("requiresBankBatch", "Y");
                runToUpdate.put("generatedBankTransferBatchId", bankTransferBatchId);
                runToUpdate.put("bankBatchGeneratedDate", auditTs);
                runToUpdate.put("bankBatchGeneratedByUserLoginId", userLogin.getString("userLoginId"));

                // Optional: keep run-level config aligned (safe)
                if (UtilValidate.isEmpty(runToUpdate.getString("bankTransferConfigId"))) {
                    runToUpdate.put("bankTransferConfigId", bankTransferConfigId);
                }

                runToUpdate.store();
            }
        } catch (Exception e) {
            Debug.logWarning(e,
                    "generateBankTransferBatchForRun: bank batch created but PayrollRun write-back failed payrollRunId=" + payrollRunId,
                    MODULE);
        }

        // Build output
        data.put("bankTransferBatchId", bankTransferBatchId);
        data.put("createdLines", createdLines);
        data.put("skippedNonPositiveNet", skippedNonPositive);
        data.put("skippedNoAllocation", skippedNoAlloc);
        data.put("skippedNoPaymentMethod", skippedNoPaymentMethod);
        data.put("skippedNoEftAccount", skippedNoEftAccount);
        data.put("totalAmount", totalAmount);
        data.put("currencyUomId", batchCurrency);
        data.put("ok", Boolean.TRUE);
        data.put("changed", Boolean.TRUE);

        Map<String, Object> out = ServiceUtil.returnSuccess(
                "Bank transfer batch generated batchId=" + bankTransferBatchId + " lines=" + createdLines);
        out.put("bankTransferBatchId", bankTransferBatchId);
        out.put("data", data);

        success = true;
        return out;

    } catch (Exception e) {
        Debug.logError(e, "generateBankTransferBatchForRun failed payrollRunId=" + payrollRunId, MODULE);
        Map<String, Object> err = ServiceUtil.returnError("generateBankTransferBatchForRun failed: " + safeMsg(e));
        data.put("ok", Boolean.FALSE);
        data.put("changed", Boolean.FALSE);
        data.put("message", "Exception: " + safeMsg(e));
        err.put("data", data);
        success = false;
        return err;

    } finally {
        endTxQuiet(beganTx, success, "generateBankTransferBatchForRun");
    }
}


public static Map<String, Object> exportBankTransferFileForBatch(DispatchContext dctx, Map<String, ?> context) {
    final Delegator delegator = dctx.getDelegator();
    final GenericValue userLogin = (GenericValue) context.get("userLogin");

    final String bankTransferBatchId = asString(context, "bankTransferBatchId");
    final String exportFormatIn = asString(context, "exportFormat"); // CSV (default)
    String outDir = asString(context, "outDir");
    final String sourceIp = asString(context, "sourceIp");

    if (userLogin == null) return ServiceUtil.returnError("userLogin is required");
    if (UtilValidate.isEmpty(bankTransferBatchId)) return ServiceUtil.returnError("bankTransferBatchId is required");

    final String exportFormat = UtilValidate.isNotEmpty(exportFormatIn) ? exportFormatIn.trim().toUpperCase() : "CSV";

    boolean beganTx = false;
    boolean success = false;

    final Map<String, Object> data = new HashMap<>();
    data.put("service", "exportBankTransferFileForBatch");
    data.put("bankTransferBatchId", bankTransferBatchId);
    data.put("exportFormat", exportFormat);
    data.put("sourceIp", sourceIp);
    data.put("userLoginId", userLogin.getString("userLoginId"));
    data.put("timestamp", UtilDateTime.nowTimestamp());

    try {
        beganTx = TransactionUtil.begin();

        GenericValue batch = EntityQuery.use(delegator)
                .from("BankTransferBatch")
                .where("bankTransferBatchId", bankTransferBatchId)
                .cache(false)
                .queryOne();

        if (batch == null) {
            Map<String, Object> err = ServiceUtil.returnError("BankTransferBatch not found id=" + bankTransferBatchId);
            err.put("data", data);
            return err;
        }

        final String payrollRunId = batch.getString("payrollRunId");
        final String batchStatusId = batch.getString("statusId");

        data.put("payrollRunId", payrollRunId);
        data.put("batchStatusId", batchStatusId);

        // Governance: allow export only from NEW/GENERATED/EXPORTED (idempotent)
        if (!( "BTB_NEW".equals(batchStatusId) || "BTB_GENERATED".equals(batchStatusId) || "BTB_EXPORTED".equals(batchStatusId) )) {
            Map<String, Object> err = ServiceUtil.returnError("Batch not exportable in status=" + batchStatusId);
            err.put("data", data);
            return err;
        }

        List<GenericValue> lines = EntityQuery.use(delegator)
                .from("BankTransferBatchLine")
                .where("bankTransferBatchId", bankTransferBatchId)
                .orderBy("sequenceNum")
                .cache(false)
                .queryList();

        if (UtilValidate.isEmpty(lines)) {
            Map<String, Object> err = ServiceUtil.returnError("No lines found for batchId=" + bankTransferBatchId);
            err.put("data", data);
            return err;
        }

        // Default output directory under OFBiz runtime
        if (UtilValidate.isEmpty(outDir)) {
            outDir = System.getProperty("ofbiz.home")
                    + File.separator + "runtime"
                    + File.separator + "tmp"
                    + File.separator + "bank_exports"
                    + File.separator + bankTransferBatchId;
        }

        File dir = new File(outDir);
        if (!dir.exists() && !dir.mkdirs()) {
            Map<String, Object> err = ServiceUtil.returnError("Failed to create outDir=" + outDir);
            err.put("data", data);
            return err;
        }

        final String ts = UtilDateTime.nowAsString().replaceAll("[:\\s]", "_");
        final String fileName = "BANK_BATCH_" + bankTransferBatchId + "_" + ts + ".csv";
        final File outFile = new File(dir, fileName);

        // CSV export
        try (java.io.OutputStream os = new java.io.FileOutputStream(outFile);
             java.io.OutputStreamWriter osw = new java.io.OutputStreamWriter(os, java.nio.charset.StandardCharsets.UTF_8);
             java.io.BufferedWriter bw = new java.io.BufferedWriter(osw)) {

            // Header (keep simple; extend later for WPS/SAMA)
            bw.write("sequenceNum,beneficiaryName,bankName,bankAccountNumber,amount,currencyUomId");
            bw.newLine();

            for (GenericValue line : lines) {
                String seq = String.valueOf(line.getLong("sequenceNum"));
                String ben = safeCsv(line.getString("beneficiaryName"));
                String bank = safeCsv(line.getString("bankName"));

                // IMPORTANT: if bankAccountNumber is encrypted/clob, Entity Engine getString() will normally return usable text
                String acct = safeCsv(line.getString("bankAccountNumber"));

                java.math.BigDecimal amt = line.getBigDecimal("amount");
                String cur = safeCsv(line.getString("currencyUomId"));

                bw.write(seq + "," + ben + "," + bank + "," + acct + "," + (amt != null ? amt.toPlainString() : "0") + "," + cur);
                bw.newLine();
            }
        }

        // Update batch status
        batch.put("statusId", "BTB_EXPORTED");
        batch.store();

        // Optional: store reference on PayrollRun (traceability)
        if (UtilValidate.isNotEmpty(payrollRunId)) {
            try {
                GenericValue run = EntityQuery.use(delegator)
                        .from("PayrollRun")
                        .where("payrollRunId", payrollRunId)
                        .cache(false)
                        .queryOne();
                if (run != null) {
                    // keep as file reference only; "uploaded" is a different service
                    run.put("bankFileReference", outFile.getAbsolutePath());
                    run.store();
                }
            } catch (Exception e) {
                Debug.logWarning(e, "exportBankTransferFileForBatch: could not write PayrollRun.bankFileReference payrollRunId=" + payrollRunId, MODULE);
            }
        }

        data.put("exportFileName", fileName);
        data.put("exportPath", outFile.getAbsolutePath());
        data.put("lineCount", lines.size());
        data.put("ok", Boolean.TRUE);
        data.put("changed", Boolean.TRUE);

        Map<String, Object> out = ServiceUtil.returnSuccess("Bank batch exported file=" + fileName);
        out.put("exportFileName", fileName);
        out.put("exportPath", outFile.getAbsolutePath());
        out.put("data", data);

        success = true;
        return out;

    } catch (Exception e) {
        Debug.logError(e, "exportBankTransferFileForBatch failed batchId=" + bankTransferBatchId, MODULE);
        Map<String, Object> err = ServiceUtil.returnError("exportBankTransferFileForBatch failed: " + safeMsg(e));
        data.put("ok", Boolean.FALSE);
        data.put("changed", Boolean.FALSE);
        err.put("data", data);
        success = false;
        return err;
    } finally {
        endTxQuiet(beganTx, success, "exportBankTransferFileForBatch");
    }
}



public static Map<String, Object> markBankFileUploadedForRun(DispatchContext dctx, Map<String, ?> context) {
    final Delegator delegator = dctx.getDelegator();
    final GenericValue userLogin = (GenericValue) context.get("userLogin");

    final String payrollRunId = asString(context, "payrollRunId");
    final String bankFileReference = asString(context, "bankFileReference");
    final String sourceIp = asString(context, "sourceIp");

    if (userLogin == null) return ServiceUtil.returnError("userLogin is required");
    if (UtilValidate.isEmpty(payrollRunId)) return ServiceUtil.returnError("payrollRunId is required");
    if (UtilValidate.isEmpty(bankFileReference)) return ServiceUtil.returnError("bankFileReference is required");

    boolean beganTx = false;
    boolean success = false;

    final Map<String, Object> data = new HashMap<>();
    data.put("service", "markBankFileUploadedForRun");
    data.put("payrollRunId", payrollRunId);
    data.put("bankFileReference", bankFileReference);
    data.put("sourceIp", sourceIp);
    data.put("userLoginId", userLogin.getString("userLoginId"));
    data.put("timestamp", UtilDateTime.nowTimestamp());

    try {
        beganTx = TransactionUtil.begin();

        GenericValue run = EntityQuery.use(delegator)
                .from("PayrollRun")
                .where("payrollRunId", payrollRunId)
                .cache(false)
                .queryOne();

        if (run == null) {
            Map<String, Object> err = ServiceUtil.returnError("PayrollRun not found payrollRunId=" + payrollRunId);
            err.put("data", data);
            return err;
        }

        final String oldStatusId = run.getString("statusId");
        data.put("oldStatusId", oldStatusId);

        // Governance: only after POST
        if (!PayrollStatusIds.RUN_POST.equals(oldStatusId)) {
            Map<String, Object> err = ServiceUtil.returnError("Bank upload confirmation allowed only when run is POSTED (PR_POST). Current=" + oldStatusId);
            err.put("data", data);
            return err;
        }

        // Write upload info
        run.put("bankFileUploadedByUserLoginId", userLogin.getString("userLoginId"));
        run.put("bankFileUploadedDate", UtilDateTime.nowTimestamp());
        run.put("bankFileReference", bankFileReference);

        // Finalize payroll run
        run.put("statusId", PayrollStatusIds.RUN_DONE);
        run.put("doneByUserLoginId", userLogin.getString("userLoginId"));
        run.put("doneDate", UtilDateTime.nowTimestamp());

        run.store();

        // Update batch status if we can resolve it from run
        final String batchId = run.getString("generatedBankTransferBatchId");
        if (UtilValidate.isNotEmpty(batchId)) {
            try {
                GenericValue batch = EntityQuery.use(delegator)
                        .from("BankTransferBatch")
                        .where("bankTransferBatchId", batchId)
                        .cache(false)
                        .queryOne();
                if (batch != null) {
                    // choose your final status: SENT vs CONFIRMED
                    batch.put("statusId", "BTB_SENT");
                    batch.store();
                }
            } catch (Exception e) {
                Debug.logWarning(e, "markBankFileUploadedForRun: batch update failed batchId=" + batchId, MODULE);
            }
        }

        data.put("newStatusId", PayrollStatusIds.RUN_DONE);
        data.put("bankTransferBatchId", run.getString("generatedBankTransferBatchId"));
        data.put("ok", Boolean.TRUE);
        data.put("changed", Boolean.TRUE);

        Map<String, Object> out = ServiceUtil.returnSuccess("Bank file uploaded confirmed. Run " + payrollRunId + " -> PR_DONE");
        out.put("data", data);

        success = true;
        return out;

    } catch (Exception e) {
        Debug.logError(e, "markBankFileUploadedForRun failed payrollRunId=" + payrollRunId, MODULE);
        Map<String, Object> err = ServiceUtil.returnError("markBankFileUploadedForRun failed: " + safeMsg(e));
        data.put("ok", Boolean.FALSE);
        data.put("changed", Boolean.FALSE);
        err.put("data", data);
        success = false;
        return err;
    } finally {
        endTxQuiet(beganTx, success, "markBankFileUploadedForRun");
    }
}

*/


// CSV-safe helper (minimal)
private static String safeCsv(String s) {
    if (s == null) return "";
    String x = s.replace("\"", "\"\"");
    if (x.contains(",") || x.contains("\n") || x.contains("\r")) {
        return "\"" + x + "\"";
    }
    return x;
}

private static java.math.BigDecimal bd(Object o) {
    if (o == null) {
        return java.math.BigDecimal.ZERO;
    }
    if (o instanceof java.math.BigDecimal) {
        return (java.math.BigDecimal) o;
    }
    if (o instanceof Number) {
        return new java.math.BigDecimal(((Number) o).toString());
    }
    if (o instanceof String) {
        String s = ((String) o).trim();
        if (s.isEmpty()) {
            return java.math.BigDecimal.ZERO;
        }
        return new java.math.BigDecimal(s);
    }
    return java.math.BigDecimal.ZERO;
}

/** Resolve best-effort beneficiary display name from Party/Person/PartyGroup. */
private static String resolvePartyDisplayName(Delegator delegator, String partyId) {
    if (UtilValidate.isEmpty(partyId)) return null;
    try {
        GenericValue person = EntityQuery.use(delegator).from("Person").where("partyId", partyId).cache(false).queryOne();
        if (person != null) {
            String first = person.getString("firstName");
            String last = person.getString("lastName");
            String full = (UtilValidate.isNotEmpty(first) ? first : "") + (UtilValidate.isNotEmpty(last) ? (" " + last) : "");
            full = full.trim();
            return UtilValidate.isNotEmpty(full) ? full : partyId;
        }
        GenericValue group = EntityQuery.use(delegator).from("PartyGroup").where("partyId", partyId).cache(false).queryOne();
        if (group != null) {
            String name = group.getString("groupName");
            return UtilValidate.isNotEmpty(name) ? name : partyId;
        }
    } catch (Exception ignored) { }
    return partyId;
}

    /**
     * Non-blocking audit hook. Requires a service:
     * recordPayrollAuditEvent(organizationPartyId,eventTypeId,entityName,recordId,oldValue,newValue,eventDate,userLoginId,sourceIp,userLogin)
     */
    private static void audit(LocalDispatcher dispatcher,
                              GenericValue userLogin,
                              String organizationPartyId,
                              String eventTypeId,
                              String entityName,
                              String recordId,
                              String oldValue,
                              String newValue,
                              String sourceIp) {
        try {
            Map<String, Object> ctx = UtilMisc.toMap(
                    "organizationPartyId", organizationPartyId,
                    "eventTypeId", eventTypeId,
                    "entityName", entityName,
                    "recordId", recordId,
                    "oldValue", oldValue,
                    "newValue", newValue,
                    "sourceIp", sourceIp,
                    "userLogin", userLogin
            );
            dispatcher.runSync("recordPayrollAuditEvent", ctx);
        } catch (Exception ignored) {
            // never block workflow due to audit
        }
    }
}
