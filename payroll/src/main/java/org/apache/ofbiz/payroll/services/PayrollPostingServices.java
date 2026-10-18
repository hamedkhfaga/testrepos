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
 * PayrollPostingServices (FINAL)
 *
 * CRITICAL FIX:
 * - Internal mapping uses DR/CR (as stored in PayrollGlMapping).
 * - OFBiz AcctgTransEntry uses debitCreditFlag D/C -> we convert DR->D and CR->C when writing.
 *
 * NOTE:
 * - AcctgTransEntry PK in your OFBiz model is (acctgTransId, acctgTransEntrySeqId).
 * - DO NOT set acctgTransEntryId (it does not exist in your model, per your runtime error).
 */
public class PayrollPostingServices {
    public static final String MODULE = PayrollPostingServices.class.getName();

    // PayrollRun statuses (adjust to your status ids)
    public static final String RUN_STATUS_APPROVED = "PAYRUN_APPROVED";
    public static final String RUN_STATUS_POSTED   = "PAYRUN_POSTED";

    // PayrollPosting.postingTypeId
    public static final String POST_TYPE_GL      = "POST_GL";
    public static final String POST_TYPE_INVOICE = "POST_INV";

    // PayrollPosting.postingStatusId
    public static final String POST_STS_NEW      = "POST_NEW";
    public static final String POST_STS_POSTED   = "POST_POSTED";
    public static final String POST_STS_ERROR    = "POST_ERROR";
    public static final String POST_STS_REVERSED = "POST_REVERSED"; // FIXED constant

    // PayrollRun.accountingPostOptionId (business selection)
    public static final String OPT_NONE    = "PAY_POST_NONE";
    public static final String OPT_GL      = "PAY_POST_GL";
    public static final String OPT_INVOICE = "PAY_POST_INVOICE";
    public static final String OPT_BOTH    = "PAY_POST_BOTH";

    // Accounting defaults (tune per your setup)
   // private static final String DEFAULT_ACCTG_TRANS_TYPE = "MANUAL_ACCTG_TRANS";
    private static final String DEFAULT_INVOICE_TYPE     = "PURCHASE_INVOICE"; // AP invoice
    private static final String DEFAULT_CURRENCY_UOM      = "USD";


// =========================
// PayrollRun Status IDs (must match StatusItem in DB)
// =========================
private static final String PR_DRAFT     = "PR_DRAFT";
private static final String PR_NEW       = "PR_NEW";
private static final String PR_PROC      = "PR_PROC";
private static final String PR_PROCESSED = "PR_PROCESSED";
private static final String PR_APPR      = "PR_APPR";
private static final String PR_POST      = "PR_POST";
private static final String PR_DONE      = "PR_DONE";
private static final String PR_FAIL      = "PR_FAIL";
private static final String PR_VOID      = "PR_VOID";




    // ----------------------------
    // Public router (workflow use)
    // ----------------------------
    public static Map<String, Object> postPayrollRunAccounting(DispatchContext dctx, Map<String, ?> context) {
        return postPayrollRun(dctx, context);
    }

/*
 * FINAL POC-SAFE REBUILD
 * - Fix router output so REST never returns {} silently
 * - Always return clear successMessage/errorMessage + data payload
 * - Ensure PayrollRun is updated for INVOICE posting (IS_INVOICE_POSTED / INVOICE_ID / INVOICE_POSTED_DATE)
 *
 * Assumptions:
 * - OPT_NONE / OPT_GL / OPT_INVOICE / OPT_BOTH values are already defined in this class
 *   and match your DB/service usage (ex: OPT_GL="PO_GL", OPT_INVOICE="PO_INV", OPT_BOTH="PO_BOTH", OPT_NONE="PO_NONE")
 * - mustGetRun(), requireOrg(), assertRunInvoicePostable(), getOrCreatePosting(), etc. exist already
 * - TransactionUtil, UtilDateTime, UtilValidate, UtilMisc, Debug, ServiceUtil are available
 */

public static Map<String, Object> postPayrollRun(DispatchContext dctx, Map<String, ?> context) {
    final Delegator delegator = dctx.getDelegator();
    final LocalDispatcher dispatcher = dctx.getDispatcher();
    final GenericValue userLogin = (GenericValue) context.get("userLogin");

    final String payrollRunId = asString(context, "payrollRunId");
    if (UtilValidate.isEmpty(payrollRunId)) {
        return ServiceUtil.returnError("payrollRunId is required");
    }
    if (userLogin == null) {
        return ServiceUtil.returnError("userLogin is required");
    }

    final boolean forceRepost = asBoolean(context.get("forceRepost"));
    final String vendorPartyIdIn = asString(context, "vendorPartyId");
    final String invoiceTypeIdIn = asString(context, "invoiceTypeId");

    try {
        final GenericValue run = mustGetRun(delegator, payrollRunId);
/*
        // Resolve posting option: request overrides run, else NONE
        String option = asString(context, "accountingPostOptionId");
        if (UtilValidate.isEmpty(option)) option = run.getString("accountingPostOptionId");
        if (UtilValidate.isEmpty(option)) option = OPT_NONE;

        final boolean wantsGl = OPT_GL.equals(option) || OPT_BOTH.equals(option);
        final boolean wantsInv = OPT_INVOICE.equals(option) || OPT_BOTH.equals(option);
*/

// new update   
// Resolve posting option
String option = asString(context, "accountingPostOptionId");
if (UtilValidate.isEmpty(option)) option = run.getString("accountingPostOptionId");
if (UtilValidate.isEmpty(option)) option = "PO_NONE";
option = normalizePostOption(option);

final boolean wantsGl  = "PO_GL".equals(option) || "PO_BOTH".equals(option);
final boolean wantsInv = "PO_INV".equals(option) || "PO_BOTH".equals(option);

if (!"PO_NONE".equals(option) && !wantsGl && !wantsInv) {
    return ServiceUtil.returnError("Unsupported accountingPostOptionId=" + option + " (expected PO_NONE|PO_GL|PO_INV|PO_BOTH)");
}

        // REST-friendly payload (single place)
        final Map<String, Object> data = new java.util.HashMap<>();
        data.put("payrollRunId", payrollRunId);
        data.put("organizationPartyId", run.getString("organizationPartyId"));
        data.put("accountingPostOptionId", option);
        data.put("forceRepost", forceRepost);

        // No posting
        if (OPT_NONE.equals(option)) {
            Map<String, Object> out = ServiceUtil.returnSuccess("Posting skipped (OPT_NONE).");
            out.put("data", data);
            out.put("glJournalId", null);
            out.put("invoiceId", null);
            return out;
        }

        // Vendor required only if invoice requested
        if (wantsInv && UtilValidate.isEmpty(vendorPartyIdIn) && UtilValidate.isEmpty(resolveOrgVendor(delegator, run.getString("organizationPartyId")))) {
            Map<String, Object> err = ServiceUtil.returnError("vendorPartyId is required for invoice posting (or configure vendor on PayrollOrganization).");
            err.put("data", data);
            return err;
        }

        String glJournalId = null;
        String invoiceId = null;

        // Run GL first (if BOTH) so PR_POST doesn't block invoice (invoice service should allow PR_POST anyway)
        if (wantsGl) {
            Map<String, Object> glRes = dispatcher.runSync("postPayrollRunToGl", UtilMisc.toMap(
                    "payrollRunId", payrollRunId,
                    "organizationPartyId", run.getString("organizationPartyId"),
                    "forceRepost", forceRepost,
                    "sourceIp", context.get("sourceIp"),
                    "userLogin", userLogin
            ));

            if (ServiceUtil.isError(glRes)) {
                Map<String, Object> err = ServiceUtil.returnError("GL posting failed: " + ServiceUtil.getErrorMessage(glRes));
                err.put("data", data);
                // keep router OUT fields consistent
                err.put("glJournalId", null);
                err.put("invoiceId", null);
                return err;
            }

            glJournalId = asString(glRes, "glJournalId");
            data.put("glJournalId", glJournalId);
        }

        if (wantsInv) {
            Map<String, Object> invRes = dispatcher.runSync("postPayrollRunToInvoice", UtilMisc.toMap(
                    "payrollRunId", payrollRunId,
                    "organizationPartyId", run.getString("organizationPartyId"),
                    "vendorPartyId", vendorPartyIdIn,
                    "invoiceTypeId", invoiceTypeIdIn,
                    "forceRepost", forceRepost,
                    "sourceIp", context.get("sourceIp"),
                    "userLogin", userLogin
            ));

            if (ServiceUtil.isError(invRes)) {
                Map<String, Object> err = ServiceUtil.returnError("Invoice posting failed: " + ServiceUtil.getErrorMessage(invRes));
                err.put("data", data);
                err.put("glJournalId", glJournalId);
                err.put("invoiceId", null);
                return err;
            }

            invoiceId = asString(invRes, "invoiceId");
            data.put("invoiceId", invoiceId);
        }

 // ----------------------------
// FINAL SUCCESS (REST-friendly)
// ----------------------------
String msg;
if (wantsGl && wantsInv) msg = "Posting completed (GL + Invoice).";
else if (wantsGl) msg = "Posting completed (GL).";
else msg = "Posting completed (Invoice).";

Map<String, Object> out = ServiceUtil.returnSuccess(msg);

// Some REST wrappers only read these keys:
out.put("successMessage", msg);
out.put("errorMessage", null);

// Keep these OUT fields for service callers
out.put("glJournalId", glJournalId);
out.put("invoiceId", invoiceId);

// Build/refresh the REST payload map (DO NOT redeclare if you already have `data`)
//if (data == null) {
 //   data = new java.util.HashMap<>();
//}
data.put("payrollRunId", payrollRunId);
data.put("organizationPartyId", run.getString("organizationPartyId"));
data.put("accountingPostOptionId", option);
data.put("forceRepost", forceRepost);
data.put("glJournalId", glJournalId);
data.put("invoiceId", invoiceId);
data.put("_buildMarker", "POSTPAYROLLRUN_ROUTER_OK");

out.put("data", data);

return out;



    } catch (Exception e) {
        Debug.logError(e, "postPayrollRun failed payrollRunId=" + payrollRunId, MODULE);
        Map<String, Object> err = ServiceUtil.returnError("postPayrollRun failed: " + safeMsg(e));
        // keep REST payload stable even in error
        Map<String, Object> data = new java.util.HashMap<>();
        data.put("payrollRunId", payrollRunId);
        err.put("data", data);
        return err;
    }
}



/* =======================================================================
 * STRICT mirror that FAILS LOUD if entity fields are not present
 * (this avoids "OK but no DB update" again)
 * ======================================================================= */
private static void mirrorRunAfterInvoice_STRICT(GenericValue run, String invoiceId, Timestamp nowTs) throws Exception {
    if (run == null) throw new IllegalArgumentException("PayrollRun is null");

    // Ensure required fields exist in entitymodel
    requireRunField(run, "isInvoicePosted");
    requireRunField(run, "invoiceId");
    requireRunField(run, "invoicePostedDate");

    run.set("isInvoicePosted", "Y");
    run.set("invoiceId", invoiceId);
    run.set("invoicePostedDate", nowTs);

    // optional clean-up
    if (run.getModelEntity() != null && run.getModelEntity().isField("lastErrorMessage")) {
        run.set("lastErrorMessage", null);
    }

    run.store();
}

private static void requireRunField(GenericValue run, String fieldName) {
    if (run.getModelEntity() == null || !run.getModelEntity().isField(fieldName)) {
        throw new IllegalStateException(
                "PayrollRun entity is missing field '" + fieldName + "'. " +
                "Fix entitymodel mapping to update PAYROLL_RUN columns (invoice fields)."
        );
    }
}

/* helpers used above (if you don't already have them) */
private static String asString(Map<String, ?> ctx, String key) {
    Object v = (ctx != null) ? ctx.get(key) : null;
    return (v == null) ? null : String.valueOf(v);
}

private static boolean asBoolean(Object v) {
    if (v instanceof Boolean) return (Boolean) v;
    if (v == null) return false;
    String s = String.valueOf(v);
    return "Y".equalsIgnoreCase(s) || "true".equalsIgnoreCase(s);
}

private static String getSourceIp(Map<String, ?> ctx) {
    String ip = asString(ctx, "sourceIp");
    if (UtilValidate.isEmpty(ip)) ip = asString(ctx, "remoteAddr"); // optional
    if (UtilValidate.isEmpty(ip)) ip = "REST";
    return ip;
}

////////////////////////////////////////////
private static void safeSetIfField(GenericValue gv, String fieldName, Object value) {
    if (gv == null || UtilValidate.isEmpty(fieldName)) return;
    try {
        if (gv.getModelEntity() != null && gv.getModelEntity().isField(fieldName)) {
            gv.set(fieldName, value);
        }
    } catch (Exception ignore) {
        // intentionally ignore: field missing or model not available
    }
}

private static String normalizePostOption(String option) {
    if (UtilValidate.isEmpty(option)) return null;

    // Accept PO_* directly
    if ("PO_NONE".equals(option) || "PO_GL".equals(option) || "PO_INV".equals(option) || "PO_BOTH".equals(option)) {
        return option;
    }

    // Accept legacy PAY_POST_* and convert to PO_*
    if ("PAY_POST_NONE".equals(option)) return "PO_NONE";
    if ("PAY_POST_GL".equals(option)) return "PO_GL";
    if ("PAY_POST_INVOICE".equals(option)) return "PO_INV";
    if ("PAY_POST_BOTH".equals(option)) return "PO_BOTH";

    return option; // unknown => let caller error out
}

    // =========================================================
    // Public: postPayrollRunToGl
    // =========================================================
public static Map<String, Object> postPayrollRunToGl(DispatchContext dctx, Map<String, ?> context) {
    final Delegator delegator = dctx.getDelegator();
    final LocalDispatcher dispatcher = dctx.getDispatcher();
    final GenericValue userLogin = (GenericValue) context.get("userLogin");

    String payrollRunId = asString(context, "payrollRunId");
    String organizationPartyId = firstOrg(context);
    String glFiscalTypeId = asString(context, "glFiscalTypeId");
    String glJournalName = asString(context, "glJournalName");
    boolean forceRepost = asBoolean(context.get("forceRepost"));

    if (UtilValidate.isEmpty(payrollRunId)) return ServiceUtil.returnError("payrollRunId is required");
    if (userLogin == null) return ServiceUtil.returnError("userLogin is required");

    boolean beganTx = false;
    boolean success = false;

    // for response
    final Map<String, Object> data = new java.util.HashMap<>();
    data.put("payrollRunId", payrollRunId);

    try {
        beganTx = TransactionUtil.begin();

        GenericValue run = mustGetRun(delegator, payrollRunId);
        if (UtilValidate.isEmpty(organizationPartyId)) {
            organizationPartyId = run.getString("organizationPartyId");
        }
        requireOrg(organizationPartyId);

        // Must be in approved state to post (your rule)
        assertRunPostable(run);

        GenericValue posting = getOrCreatePosting(delegator, organizationPartyId, payrollRunId, POST_TYPE_GL);

        // If already posted and not forcing repost
        if (isPosted(posting) && !forceRepost) {
            String glId = posting.getString("glJournalId");
            if (UtilValidate.isEmpty(glId)) {
                // fallback to run
                glId = run.getString("glJournalId");
            }

            success = true;

            Map<String, Object> ok = ServiceUtil.returnSuccess("Already posted to GL");
            ok.put("glJournalId", glId);
            ok.put("payrollPostingId", posting.getString("payrollPostingId"));

            data.put("payrollPostingId", posting.getString("payrollPostingId"));
            data.put("glJournalId", glId);
            data.put("statusId", run.getString("statusId"));
            data.put("isGlPosted", run.getString("isGlPosted"));
            data.put("glPostedDate", run.get("glPostedDate"));
            ok.put("data", data);

            return ok;
        }

        // Repost scenario: reverse old posting + create new version
        if (forceRepost && isPosted(posting)) {
            posting.set("postingStatusId", POST_STS_REVERSED);
            posting.store();

            audit(dispatcher, userLogin, organizationPartyId,
                    "POST_GL_REPOST", "PayrollPosting",
                    posting.getString("payrollPostingId"),
                    null, "forceRepost=true", null);

            posting = createPosting(delegator, organizationPartyId, payrollRunId,
                    POST_TYPE_GL, nextVersion(posting), POST_STS_NEW);
        }

        // Aggregate payroll amounts
        List<PayrollAmountLine> agg = aggregatePayrollLines(delegator, organizationPartyId, payrollRunId);
        if (agg == null || agg.isEmpty()) {
            setPostingError(posting, "No payroll lines found to post (run=" + payrollRunId + ")");

            audit(dispatcher, userLogin, organizationPartyId,
                    "POST_GL_ERR", "PayrollRun", payrollRunId,
                    null, "No lines", null);

            return ServiceUtil.returnError("No payroll lines found for run " + payrollRunId);
        }

        // Build GL lines + validate
        List<GlPostingLine> glLines = buildGlLinesFromMapping(delegator, organizationPartyId, agg);
        assertBalanced(glLines);

        // Create accounting transaction + entries
        String acctgTransId = createAcctgTrans(
                delegator,
                organizationPartyId,
                payrollRunId,
                glFiscalTypeId,
                glJournalName,
                userLogin
        );

        createAcctgTransEntries(delegator, acctgTransId, organizationPartyId, glLines, userLogin);
        writePostingLinesGl(delegator, posting, glLines);

        // Mark posting as POSTED
        Timestamp nowTs = UtilDateTime.nowTimestamp();

        posting.set("postingStatusId", POST_STS_POSTED);
        posting.set("postedDate", nowTs);
        posting.set("postedByUserLoginId", userLoginId(userLogin));
        posting.set("glJournalId", acctgTransId);
        posting.set("errorMessage", null);
        posting.store();

        // -----------------------------------------------------------------
        // CRITICAL FIX: mirror GL info onto PayrollRun (GUARANTEE glJournalId)
        // -----------------------------------------------------------------
        // Keep simple: set only fields that exist in the entity model
        setIfField(run, "isGlPosted", "Y");
        setIfField(run, "glPostedDate", nowTs);

        // IMPORTANT: do NOT swallow this one.
        // If this field name is wrong in your entity model, we throw and you'll see it.
        requireField(run, "glJournalId");
        run.set("glJournalId", acctgTransId);

        // mark run posted (POC)
        setIfField(run, "statusId", "PR_POST");

        // clear last error if present
        setIfField(run, "lastErrorMessage", null);

        run.store();

        // Audit OK
        audit(dispatcher, userLogin, organizationPartyId,
                "POST_GL_OK", "PayrollRun", payrollRunId,
                null, "acctgTransId=" + acctgTransId, null);

        success = true;

        Map<String, Object> out = ServiceUtil.returnSuccess("Posted to GL: acctgTransId=" + acctgTransId);
        out.put("payrollPostingId", posting.getString("payrollPostingId"));
        out.put("glJournalId", acctgTransId);

        // REST wrapper friendly
        data.put("payrollPostingId", posting.getString("payrollPostingId"));
        data.put("glJournalId", acctgTransId);
        data.put("isGlPosted", "Y");
        data.put("glPostedDate", nowTs);
        data.put("statusId", "PR_POST");
        out.put("data", data);

        return out;

    } catch (Exception e) {
        Debug.logError(e, "postPayrollRunToGl failed runId=" + payrollRunId, MODULE);

        // Best-effort store posting error (do not throw)
        try {
            String orgId = organizationPartyId;
            if (UtilValidate.isEmpty(orgId)) {
                try {
                    GenericValue run = UtilValidate.isNotEmpty(payrollRunId) ? mustGetRun(delegator, payrollRunId) : null;
                    if (run != null) orgId = run.getString("organizationPartyId");
                } catch (Exception ignore) {}
            }
            if (UtilValidate.isNotEmpty(orgId) && UtilValidate.isNotEmpty(payrollRunId)) {
                GenericValue posting = getOrCreatePosting(delegator, orgId, payrollRunId, POST_TYPE_GL);
                setPostingError(posting, safeMsg(e));
            }
        } catch (Exception ignore) {}

        return ServiceUtil.returnError("postPayrollRunToGl failed: " + safeMsg(e));

    } finally {
        endTxQuiet(beganTx, success, "postPayrollRunToGl");
    }
}

/** Set field only if it exists in the entity model. */
private static void setIfField(GenericValue gv, String fieldName, Object value) {
    if (gv == null) return;
    try {
        if (gv.getModelEntity() != null && gv.getModelEntity().isField(fieldName)) {
            gv.set(fieldName, value);
        }
    } catch (Exception ignore) {}
}

/** Throw if field missing (use for critical fields like glJournalId). */
private static void requireField(GenericValue gv, String fieldName) {
    if (gv == null || gv.getModelEntity() == null || !gv.getModelEntity().isField(fieldName)) {
        throw new IllegalStateException("Entity " + (gv == null ? "null" : gv.getEntityName())
                + " missing required field: " + fieldName
                + " (fix entitymodel mapping, then retry)");
    }
}




/* =======================================================================
 * FINAL REBUILD: postPayrollRunToInvoice
 * - Always returns successMessage + data payload
 * - Ensures PayrollRun gets updated (NO silent skip) for invoice fields
 * - Allows invoice posting when run is PR_APPR OR PR_POST (GL already posted)
 * ======================================================================= */
public static Map<String, Object> postPayrollRunToInvoice(DispatchContext dctx, Map<String, ?> context) {
    final Delegator delegator = dctx.getDelegator();
    final LocalDispatcher dispatcher = dctx.getDispatcher();
    final GenericValue userLogin = (GenericValue) context.get("userLogin");

    final String payrollRunId = asString(context, "payrollRunId");   // <-- CRITICAL FIX
    String organizationPartyId = firstOrg(context);
    String vendorPartyId = asString(context, "vendorPartyId");
    String invoiceTypeId = asString(context, "invoiceTypeId");
    final boolean forceRepost = asBoolean(context.get("forceRepost"));

    if (UtilValidate.isEmpty(payrollRunId)) return ServiceUtil.returnError("payrollRunId is required");
    if (userLogin == null) return ServiceUtil.returnError("userLogin is required");

    boolean beganTx = false;
    boolean success = false;
///

Debug.logInfo("ENTER postPayrollRunToInvoice runId=" + payrollRunId
        + " orgId=" + organizationPartyId + " vendorPartyId=" + vendorPartyId
        + " invoiceTypeId=" + invoiceTypeId, MODULE);


///
    try {
        beganTx = TransactionUtil.begin();

        final GenericValue run = mustGetRun(delegator, payrollRunId);

        if (UtilValidate.isEmpty(organizationPartyId)) {
            organizationPartyId = run.getString("organizationPartyId");
        }
        requireOrg(organizationPartyId);

        // Allow invoice posting even if status is PR_POST (after GL post), as long as invoice not posted.
        assertRunInvoicePostable(run);

        if (UtilValidate.isEmpty(invoiceTypeId)) invoiceTypeId = DEFAULT_INVOICE_TYPE;

        if (UtilValidate.isEmpty(vendorPartyId)) {
            vendorPartyId = resolveOrgVendor(delegator, organizationPartyId);
        }
        if (UtilValidate.isEmpty(vendorPartyId)) {
            // business error, no exception: return error but keep tx clean
            success = true;
            return ServiceUtil.returnError("vendorPartyId is required (or configure vendor on PayrollOrganization).");
        }

        GenericValue posting = getOrCreatePosting(delegator, organizationPartyId, payrollRunId, POST_TYPE_INVOICE);

        // Already posted
        if (isPosted(posting) && !forceRepost) {
            String invId = posting.getString("invoiceId");
            if (UtilValidate.isEmpty(invId)) {
                invId = run.getString("invoiceId"); // fallback if posting row missing it
            }

            Map<String, Object> out = ServiceUtil.returnSuccess("Already posted to Invoice");
            out.put("invoiceId", invId);
            out.put("payrollPostingId", posting.getString("payrollPostingId"));

            Map<String, Object> data = new java.util.HashMap<>();
            data.put("payrollRunId", payrollRunId);
            data.put("organizationPartyId", organizationPartyId);
            data.put("invoiceId", invId);
            data.put("payrollPostingId", posting.getString("payrollPostingId"));
            out.put("data", data);

            success = true;
            return out;
        }

        // Repost: reverse old posting, create new version
        if (forceRepost && isPosted(posting)) {
            posting.set("postingStatusId", POST_STS_REVERSED);
            posting.store();

            audit(dispatcher, userLogin, organizationPartyId,
                    "POST_INV_REPOST", "PayrollPosting",
                    posting.getString("payrollPostingId"),
                    null, "forceRepost=true", getSourceIp(context));

            posting = createPosting(delegator, organizationPartyId, payrollRunId, POST_TYPE_INVOICE,
                    nextVersion(posting), POST_STS_NEW);
        }

        // Aggregate
        final List<PayrollAmountLine> agg = aggregatePayrollLines(delegator, organizationPartyId, payrollRunId);
        if (agg == null || agg.isEmpty()) {
            setPostingError(posting, "No payroll lines found to post (run=" + payrollRunId + ")");
            audit(dispatcher, userLogin, organizationPartyId,
                    "POST_INV_ERR", "PayrollRun", payrollRunId,
                    null, "No lines", getSourceIp(context));

            Map<String, Object> err = ServiceUtil.returnError("No payroll lines found for run " + payrollRunId);
            Map<String, Object> data = new java.util.HashMap<>();
            data.put("payrollRunId", payrollRunId);
            data.put("organizationPartyId", organizationPartyId);
            err.put("data", data);

            success = true; // commit so posting error stays
            return err;
        }

        final List<InvoicePostingLine> invLines = buildInvoiceLinesFromMapping(delegator, organizationPartyId, agg);
        if (invLines == null || invLines.isEmpty()) {
            setPostingError(posting, "No invoice lines to post (mapping returned empty/zero).");
            audit(dispatcher, userLogin, organizationPartyId,
                    "POST_INV_ERR", "PayrollRun", payrollRunId,
                    null, "No invoice lines", getSourceIp(context));

            Map<String, Object> err = ServiceUtil.returnError("No invoice lines to post (mapping returned empty/zero).");
            Map<String, Object> data = new java.util.HashMap<>();
            data.put("payrollRunId", payrollRunId);
            data.put("organizationPartyId", organizationPartyId);
            err.put("data", data);

            success = true; // commit so posting error stays
            return err;
        }

        // Create invoice
        final String invoiceId = createInvoice(delegator, organizationPartyId, vendorPartyId, invoiceTypeId, run, userLogin);
        createInvoiceItems(delegator, invoiceId, invLines, run, userLogin);

        // Posting row
        writePostingLinesInvoice(delegator, posting, invLines);

        posting.set("postingStatusId", POST_STS_POSTED);
        posting.set("postedDate", UtilDateTime.nowTimestamp());
        posting.set("postedByUserLoginId", userLoginId(userLogin));
        posting.set("invoiceId", invoiceId);
        posting.set("errorMessage", null);
        posting.store();

        // Mirror to PayrollRun (and enforce fields)
        mirrorRunAfterInvoice(delegator, run, invoiceId, userLogin);

        // HARD-ENFORCE the columns you said are not updating (safeSetIfField must exist in this class)
        Timestamp nowTs = UtilDateTime.nowTimestamp();
        safeSetIfField(run, "isInvoicePosted", "Y");
        safeSetIfField(run, "invoiceId", invoiceId);
        safeSetIfField(run, "invoicePostedDate", nowTs);
        safeSetIfField(run, "lastErrorMessage", null);
        run.store();

        // Status remains PR_POST for POC
        maybeMarkRunPosted(delegator, run, userLogin);

        audit(dispatcher, userLogin, organizationPartyId,
                "POST_INV_OK", "PayrollRun", payrollRunId,
                null, "invoiceId=" + invoiceId, getSourceIp(context));

        Map<String, Object> out = ServiceUtil.returnSuccess("Posted to Invoice: invoiceId=" + invoiceId);
        out.put("invoiceId", invoiceId);
        out.put("payrollPostingId", posting.getString("payrollPostingId"));

        Map<String, Object> data = new java.util.HashMap<>();
        data.put("payrollRunId", payrollRunId);
        data.put("organizationPartyId", organizationPartyId);
        data.put("invoiceId", invoiceId);
        data.put("invoicePostedDate", nowTs);
        data.put("isInvoicePosted", "Y");
        data.put("payrollPostingId", posting.getString("payrollPostingId"));
        out.put("data", data);
///
//Map<String, Object> out = ServiceUtil.returnSuccess();
out.put("invoiceId", invoiceId);
//return out;
///
        success = true;
        return out;

    } catch (Exception e) {
        Debug.logError(e, "postPayrollRunToInvoice failed payrollRunId=" + payrollRunId, MODULE);
        return ServiceUtil.returnError("postPayrollRunToInvoice failed: " + safeMsg(e));
    } finally {
        endTxQuiet(beganTx, success, "postPayrollRunToInvoice");
    }
}


/**
 * Invoice-postable rule for your current POC flow:
 * - Must not be VOID/DONE
 * - Must be approved OR already PR_POST (after GL posting)
 * - Must not already be invoice-posted unless forceRepost is used (handled outside)
 */
private static void assertRunInvoicePostable(GenericValue run) throws Exception {
    final String statusId = run.getString("statusId");

    if (PR_VOID.equals(statusId) || PR_DONE.equals(statusId)) {
        throw new IllegalStateException("PayrollRun not postable. status=" + statusId);
    }

    // Allow invoice posting after approval OR after GL moved it to PR_POST
    if (!(PR_APPR.equals(statusId) || PR_POST.equals(statusId) || PR_PROCESSED.equals(statusId))) {
        throw new IllegalStateException("PayrollRun must be APPROVED (or POST/PROCESSED) to post invoice. Current=" + statusId);
    }
}
  // =========================================================
    // Helpers: Org + Run checks
    // =========================================================
    private static String firstOrg(Map<String, ?> context) {
        String organizationPartyId = (String) context.get("organizationPartyId");
        if (UtilValidate.isEmpty(organizationPartyId)) organizationPartyId = (String) context.get("orgPartyId");
        return organizationPartyId;
    }

    private static void requireOrg(String organizationPartyId) {
        if (UtilValidate.isEmpty(organizationPartyId)) throw new IllegalArgumentException("organizationPartyId is required");
    }

    private static GenericValue mustGetRun(Delegator delegator, String payrollRunId) throws GenericEntityException {
        GenericValue run = EntityQuery.use(delegator).from("PayrollRun").where("payrollRunId", payrollRunId).queryOne();
        if (run == null) throw new IllegalArgumentException("PayrollRun not found: " + payrollRunId);
        return run;
    }

  
 

private static void maybeMarkRunPosted(Delegator delegator,
                                      GenericValue run,
                                      GenericValue userLogin)
        throws GenericEntityException {

    String statusId = run.getString("statusId");

    // already posted or completed → nothing to do
    if (PR_POST.equals(statusId) || PR_DONE.equals(statusId)) return;

    run.set("statusId", PR_POST);

    if (hasField(delegator, "PayrollRun", "postedDate")) {
        run.set("postedDate", UtilDateTime.nowTimestamp());
    }

    if (hasField(delegator, "PayrollRun", "postedByUserLoginId") && userLogin != null) {
        run.set("postedByUserLoginId", userLogin.getString("userLoginId"));
    }

    run.store();
}



  private static void mirrorRunAfterGl(Delegator delegator, GenericValue run, String acctgTransId, GenericValue userLogin) throws GenericEntityException {
        if (hasField(delegator, "PayrollRun", "glJournalId")) run.set("glJournalId", acctgTransId);
        if (hasField(delegator, "PayrollRun", "isGlPosted")) run.set("isGlPosted", "Y");
        if (hasField(delegator, "PayrollRun", "glPostedDate")) run.set("glPostedDate", UtilDateTime.nowTimestamp());
        run.store();
    }

    private static void mirrorRunAfterInvoice(Delegator delegator, GenericValue run, String invoiceId, GenericValue userLogin) throws GenericEntityException {
        if (hasField(delegator, "PayrollRun", "invoiceId")) run.set("invoiceId", invoiceId);
        if (hasField(delegator, "PayrollRun", "isInvoicePosted")) run.set("isInvoicePosted", "Y");
        if (hasField(delegator, "PayrollRun", "invoicePostedDate")) run.set("invoicePostedDate", UtilDateTime.nowTimestamp());
        run.store();
    }

    private static String userLoginId(GenericValue userLogin) {
        return userLogin == null ? null : userLogin.getString("userLoginId");
    }

    private static boolean hasField(Delegator delegator, String entityName, String fieldName) {
        try {
            return delegator.getModelEntity(entityName).isField(fieldName);
        } catch (Exception e) {
            return false;
        }
    }

    // =========================================================
    // Idempotency: PayrollPosting
    // =========================================================
    private static GenericValue getOrCreatePosting(Delegator delegator, String organizationPartyId, String payrollRunId, String postingTypeId)
            throws GenericEntityException {

        EntityCondition cond = EntityCondition.makeCondition(UtilMisc.toList(
                EntityCondition.makeCondition("organizationPartyId", EntityOperator.EQUALS, organizationPartyId),
                EntityCondition.makeCondition("payrollRunId", EntityOperator.EQUALS, payrollRunId),
                EntityCondition.makeCondition("postingTypeId", EntityOperator.EQUALS, postingTypeId)
        ), EntityOperator.AND);

        GenericValue posting = EntityQuery.use(delegator).from("PayrollPosting").where(cond).orderBy("-postingVersion").queryFirst();
        if (posting == null) {
            posting = createPosting(delegator, organizationPartyId, payrollRunId, postingTypeId, 1L, POST_STS_NEW);
        }
        return posting;
    }

    private static GenericValue createPosting(Delegator delegator, String organizationPartyId, String payrollRunId,
                                             String postingTypeId, Long version, String statusId) throws GenericEntityException {
        GenericValue p = delegator.makeValue("PayrollPosting");
        p.set("payrollPostingId", delegator.getNextSeqId("PayrollPosting"));
        p.set("organizationPartyId", organizationPartyId);
        p.set("payrollRunId", payrollRunId);
        p.set("postingTypeId", postingTypeId);
        p.set("postingStatusId", statusId);
        p.set("postingVersion", version == null ? 1L : version);
        delegator.create(p);
        return p;
    }

    private static Long nextVersion(GenericValue posting) {
        try {
            Long v = posting.getLong("postingVersion");
            if (v == null) v = 1L;
            return v + 1;
        } catch (Exception e) {
            return 2L;
        }
    }

    private static boolean isPosted(GenericValue posting) {
        return posting != null && POST_STS_POSTED.equals(posting.getString("postingStatusId"));
    }

    private static void setPostingError(GenericValue posting, String msg) throws GenericEntityException {
        posting.set("postingStatusId", POST_STS_ERROR);
        posting.set("errorMessage", truncate(msg, 2000));
        posting.store();
    }

    // =========================================================
    // Aggregate payroll lines
    // =========================================================
 private static List<PayrollAmountLine> aggregatePayrollLines(
        Delegator delegator,
        String organizationPartyId,
        String payrollRunId) throws GenericEntityException {

    List<GenericValue> results = EntityQuery.use(delegator)
            .from("EmployeePayrollResult")
            .where("organizationPartyId", organizationPartyId,
                   "payrollRunId", payrollRunId)
            .queryList();

    Map<String, BigDecimal> sumByLineType = new LinkedHashMap<>();
    String currencyUomId = null;

    boolean resHasCcy = hasField(delegator, "EmployeePayrollResult", "currencyUomId");
    boolean lineHasCcy = hasField(delegator, "EmployeePayrollResultLine", "currencyUomId");

    for (GenericValue r : results) {
        if (currencyUomId == null && resHasCcy) {
            currencyUomId = r.getString("currencyUomId");
        }

        String payrollResultId = r.getString("payrollResultId");
        if (UtilValidate.isEmpty(payrollResultId)) continue;

        List<GenericValue> lines = EntityQuery.use(delegator)
                .from("EmployeePayrollResultLine")
                .where("payrollResultId", payrollResultId)
                .queryList();

        for (GenericValue l : lines) {
            String lineTypeId = l.getString("lineTypeId");
            if (UtilValidate.isEmpty(lineTypeId)) continue;

            BigDecimal amt = nvl(l.getBigDecimal("amount"));
            sumByLineType.put(lineTypeId, nvl(sumByLineType.get(lineTypeId)).add(amt));

            if (currencyUomId == null && lineHasCcy) {
                String c = l.getString("currencyUomId");
                if (UtilValidate.isNotEmpty(c)) currencyUomId = c;
            }
        }
    }

    if (UtilValidate.isEmpty(currencyUomId)) currencyUomId = DEFAULT_CURRENCY_UOM;

    List<PayrollAmountLine> out = new ArrayList<>();
    for (Map.Entry<String, BigDecimal> e : sumByLineType.entrySet()) {
        BigDecimal v = e.getValue();
        if (v == null) continue;

        BigDecimal amt = v.setScale(2, RoundingMode.HALF_UP);
        if (amt.compareTo(BigDecimal.ZERO) == 0) continue;

        out.add(new PayrollAmountLine(e.getKey(), amt, currencyUomId));
    }
    return out;
}

    // =========================================================
    // Mapping (GL/Invoice) with global fallback
    // =========================================================
    private static List<GlPostingLine> buildGlLinesFromMapping(
            Delegator delegator,
            String organizationPartyId,
            List<PayrollAmountLine> aggLines
    ) throws GenericEntityException {

        List<GlPostingLine> glLines = new ArrayList<>();
        Timestamp now = UtilDateTime.nowTimestamp();

        for (PayrollAmountLine pl : aggLines) {
            List<GenericValue> maps = findGlMappingsForLineType(delegator, organizationPartyId, pl.lineTypeId, now);
            if (maps == null || maps.isEmpty()) {
                throw new IllegalArgumentException("Missing PayrollGlMapping for lineTypeId=" + pl.lineTypeId +
                        " (org=" + organizationPartyId + " or global)");
            }

            BigDecimal amt = pl.amount.abs().setScale(2, RoundingMode.HALF_UP);
            if (amt.signum() == 0) continue;

            boolean hasDr = false;
            boolean hasCr = false;

            for (GenericValue map : maps) {
                String glAccountId = map.getString("glAccountId");
                String drCrFlag = map.getString("drCrFlag"); // DR/CR

                if (UtilValidate.isEmpty(glAccountId) || UtilValidate.isEmpty(drCrFlag)) {
                    throw new IllegalArgumentException("Invalid PayrollGlMapping for lineTypeId=" + pl.lineTypeId);
                }

                String dc = drCrFlag.trim().toUpperCase(Locale.ROOT);
                if (!"DR".equals(dc) && !"CR".equals(dc)) {
                    throw new IllegalArgumentException("Invalid DR_CR_FLAG=" + drCrFlag);
                }

                if ("DR".equals(dc)) hasDr = true;
                if ("CR".equals(dc)) hasCr = true;

                glLines.add(new GlPostingLine(glAccountId, dc, amt, pl.currencyUomId, pl.lineTypeId)); // DR/CR
            }

            if (!hasDr || !hasCr) {
                throw new IllegalArgumentException("Incomplete GL mapping for lineTypeId=" + pl.lineTypeId +
                        " (need both DR and CR). Found DR=" + hasDr + " CR=" + hasCr);
            }
        }

        return glLines;
    }

    private static List<GenericValue> findGlMappingsForLineType(
            Delegator delegator,
            String organizationPartyId,
            String lineTypeId,
            Timestamp now
    ) throws GenericEntityException {

        List<GenericValue> orgMaps = EntityQuery.use(delegator)
                .from("PayrollGlMapping")
                .where("organizationPartyId", organizationPartyId,
                        "lineTypeId", lineTypeId,
                        "isActive", "Y")
                .filterByDate(now, "fromDate", "thruDate")
                .orderBy("drCrFlag")
                .cache(false)
                .queryList();

        if (orgMaps != null && !orgMaps.isEmpty()) return orgMaps;

        return EntityQuery.use(delegator)
                .from("PayrollGlMapping")
                .where("organizationPartyId", null,
                        "lineTypeId", lineTypeId,
                        "isActive", "Y")
                .filterByDate(now, "fromDate", "thruDate")
                .orderBy("drCrFlag")
                .cache(false)
                .queryList();
    }

private static List<InvoicePostingLine> buildInvoiceLinesFromMapping(
        Delegator delegator,
        String organizationPartyId,
        List<PayrollAmountLine> aggLines) throws GenericEntityException {

    List<InvoicePostingLine> inv = new ArrayList<>();
    if (aggLines == null || aggLines.isEmpty()) return inv;

    for (PayrollAmountLine pl : aggLines) {
        if (pl == null) continue;

        String lineTypeId = pl.lineTypeId;
        if (UtilValidate.isEmpty(lineTypeId)) {
            // Skip bad data; do not break the whole posting
            continue;
        }

        GenericValue map = findActiveInvoiceMapping(delegator, organizationPartyId, lineTypeId);
        if (map == null) {
            throw new IllegalArgumentException(
                    "Missing PayrollInvoiceMapping for lineTypeId=" + lineTypeId
                            + " (org=" + organizationPartyId + " or global)");
        }

        String invoiceItemTypeId = map.getString("invoiceItemTypeId");
        if (UtilValidate.isEmpty(invoiceItemTypeId)) {
            throw new IllegalArgumentException("Invalid PayrollInvoiceMapping for lineTypeId=" + lineTypeId
                    + " (invoiceItemTypeId is empty)");
        }

        // IMPORTANT: keep sign (do NOT abs). Payroll lines already carry +/- semantics.
        BigDecimal amt = nvl(pl.amount).setScale(2, RoundingMode.HALF_UP);

        // Skip true zero after rounding
        if (amt.compareTo(BigDecimal.ZERO) == 0) continue;

        String currencyUomId = UtilValidate.isNotEmpty(pl.currencyUomId) ? pl.currencyUomId : DEFAULT_CURRENCY_UOM;

        inv.add(new InvoicePostingLine(invoiceItemTypeId, amt, currencyUomId, lineTypeId));
    }

    return inv;
}
/**
 * Finds the best effective mapping (org-specific first, then global),
 * honoring isActive/fromDate/thruDate when those fields exist.
 */
/**
 * Finds the best effective mapping (org-specific first, then global),
 * honoring isActive/fromDate/thruDate when those fields exist.
 */
private static GenericValue findActiveInvoiceMapping(Delegator delegator, String orgId, String lineTypeId)
        throws GenericEntityException {

    Timestamp now = UtilDateTime.nowTimestamp();

    // 1) Try org-specific
    GenericValue m = queryEffectiveInvoiceMapping(delegator, orgId, lineTypeId, now);
    if (m != null) return m;

    // 2) Try global (organizationPartyId null/empty)
    return queryEffectiveInvoiceMapping(delegator, null, lineTypeId, now);
}

private static GenericValue queryEffectiveInvoiceMapping(Delegator delegator, String orgIdOrNull, String lineTypeId, Timestamp now)
        throws GenericEntityException {

    // Base condition
    List<org.apache.ofbiz.entity.condition.EntityCondition> conds = new ArrayList<>();
    conds.add(org.apache.ofbiz.entity.condition.EntityCondition.makeCondition("lineTypeId", lineTypeId));

    if (UtilValidate.isNotEmpty(orgIdOrNull)) {
        conds.add(org.apache.ofbiz.entity.condition.EntityCondition.makeCondition("organizationPartyId", orgIdOrNull));
    } else {
        // global mapping: org is null or empty (handle both)
        conds.add(org.apache.ofbiz.entity.condition.EntityCondition.makeCondition(
                org.apache.ofbiz.entity.condition.EntityOperator.OR,
                org.apache.ofbiz.entity.condition.EntityCondition.makeCondition("organizationPartyId", null),
                org.apache.ofbiz.entity.condition.EntityCondition.makeCondition("organizationPartyId", "")
        ));
    }

    // Optional: isActive
    if (hasField(delegator, "PayrollInvoiceMapping", "isActive")) {
        conds.add(org.apache.ofbiz.entity.condition.EntityCondition.makeCondition("isActive", "Y"));
    }

    // Optional: fromDate/thruDate effective dating
    if (hasField(delegator, "PayrollInvoiceMapping", "fromDate")) {
        conds.add(org.apache.ofbiz.entity.condition.EntityCondition.makeCondition(
                org.apache.ofbiz.entity.condition.EntityOperator.OR,
                org.apache.ofbiz.entity.condition.EntityCondition.makeCondition("fromDate", null),
                org.apache.ofbiz.entity.condition.EntityCondition.makeCondition("fromDate",
                        org.apache.ofbiz.entity.condition.EntityOperator.LESS_THAN_EQUAL_TO, now)
        ));
    }
    if (hasField(delegator, "PayrollInvoiceMapping", "thruDate")) {
        conds.add(org.apache.ofbiz.entity.condition.EntityCondition.makeCondition(
                org.apache.ofbiz.entity.condition.EntityOperator.OR,
                org.apache.ofbiz.entity.condition.EntityCondition.makeCondition("thruDate", null),
                org.apache.ofbiz.entity.condition.EntityCondition.makeCondition("thruDate",
                        org.apache.ofbiz.entity.condition.EntityOperator.GREATER_THAN, now)
        ));
    }

    return EntityQuery.use(delegator)
            .from("PayrollInvoiceMapping")
            .where(org.apache.ofbiz.entity.condition.EntityCondition.makeCondition(conds))
            .orderBy("-fromDate") // best recent mapping if multiple
            .queryFirst();
}

  

  private static GenericValue findInvoiceMapping(Delegator delegator, String organizationPartyId, String lineTypeId) throws GenericEntityException {
        EntityCondition base = EntityCondition.makeCondition(UtilMisc.toList(
                EntityCondition.makeCondition("lineTypeId", EntityOperator.EQUALS, lineTypeId),
                EntityCondition.makeCondition("isActive", EntityOperator.EQUALS, "Y")
        ), EntityOperator.AND);

        EntityCondition orgCond = EntityCondition.makeCondition("organizationPartyId", EntityOperator.EQUALS, organizationPartyId);
        EntityCondition globalCond = EntityCondition.makeCondition("organizationPartyId", EntityOperator.EQUALS, null);

        EntityCondition where = EntityCondition.makeCondition(UtilMisc.toList(
                base,
                EntityCondition.makeCondition(UtilMisc.toList(orgCond, globalCond), EntityOperator.OR)
        ), EntityOperator.AND);

        List<GenericValue> list = EntityQuery.use(delegator).from("PayrollInvoiceMapping")
                .where(where)
                .orderBy("-organizationPartyId", "-fromDate")
                .queryList();

        return list == null || list.isEmpty() ? null : list.get(0);
    }

    // =========================================================
    // GL posting helpers (DR/CR)
    // =========================================================
    private static void assertBalanced(List<GlPostingLine> glLines) {
        BigDecimal dr = BigDecimal.ZERO;
        BigDecimal cr = BigDecimal.ZERO;

        for (GlPostingLine l : glLines) {
            String dc = l.drCrFlag == null ? "" : l.drCrFlag.trim().toUpperCase(Locale.ROOT);
            if ("DR".equals(dc)) dr = dr.add(l.amount);
            else if ("CR".equals(dc)) cr = cr.add(l.amount);
            else throw new IllegalArgumentException("Invalid drCrFlag: " + l.drCrFlag + " (expected DR/CR)");
        }

        if (dr.setScale(2, RoundingMode.HALF_UP).compareTo(cr.setScale(2, RoundingMode.HALF_UP)) != 0) {
            throw new IllegalArgumentException("GL journal not balanced. DR=" + dr + " CR=" + cr + ". Fix mappings.");
        }
    }

    private static String toDebitCreditFlag(String drCr) {
        String s = drCr == null ? "" : drCr.trim().toUpperCase(Locale.ROOT);
        if ("DR".equals(s)) return "D";
        if ("CR".equals(s)) return "C";
        if ("D".equals(s) || "C".equals(s)) return s;
        throw new IllegalArgumentException("Invalid drCrFlag=" + drCr + " (expected DR/CR)");
    }

   private static final String DEFAULT_ACCTG_TRANS_TYPE = "INTERNAL_ACCTG_TRANS";

private static String createAcctgTrans(Delegator delegator,
                                       String organizationPartyId,
                                       String payrollRunId,
                                       String glFiscalTypeId,
                                       String glJournalName,
                                       GenericValue userLogin) throws GenericEntityException {

    // Validate acctgTransTypeId exists (prevents FK failure)
    GenericValue txType = EntityQuery.use(delegator)
            .from("AcctgTransType")
            .where("acctgTransTypeId", DEFAULT_ACCTG_TRANS_TYPE)
            .queryOne();
    if (txType == null) {
        throw new IllegalArgumentException("Invalid DEFAULT_ACCTG_TRANS_TYPE=" + DEFAULT_ACCTG_TRANS_TYPE
                + " (not found in AcctgTransType)");
    }

    String acctgTransId = delegator.getNextSeqId("AcctgTrans");
    GenericValue at = delegator.makeValue("AcctgTrans");
    at.set("acctgTransId", acctgTransId);
    at.set("acctgTransTypeId", DEFAULT_ACCTG_TRANS_TYPE);

    Timestamp now = UtilDateTime.nowTimestamp();
    if (hasField(delegator, "AcctgTrans", "transactionDate")) at.set("transactionDate", now);
    if (hasField(delegator, "AcctgTrans", "entryDate")) at.set("entryDate", now);

    if (hasField(delegator, "AcctgTrans", "description")) {
        if (UtilValidate.isNotEmpty(glJournalName)) {
            // Use provided name if you want it to appear in journals
            at.set("description", glJournalName);
        } else {
            at.set("description", "Payroll Run Posting: " + payrollRunId);
        }
    }

    if (UtilValidate.isNotEmpty(glFiscalTypeId) && hasField(delegator, "AcctgTrans", "glFiscalTypeId")) {
        at.set("glFiscalTypeId", glFiscalTypeId);
    }

    // Keep this only if your model really has glJournalName column (often it doesn't)
    if (UtilValidate.isNotEmpty(glJournalName) && hasField(delegator, "AcctgTrans", "glJournalName")) {
        at.set("glJournalName", glJournalName);
    }

    if (hasField(delegator, "AcctgTrans", "partyId")) {
        at.set("partyId", organizationPartyId);
    }

    if (userLogin != null && hasField(delegator, "AcctgTrans", "createdByUserLogin")) {
        at.set("createdByUserLogin", userLogin.getString("userLoginId"));
    }

    // Optional: ensure isPosted exists and defaults to N (some schemas require it)
    if (hasField(delegator, "AcctgTrans", "isPosted")) {
        at.set("isPosted", "N");
    }

    delegator.create(at);
    return acctgTransId;
}


    /**
     * FIXED:
     * - DO NOT set acctgTransEntryId (not a field in your model)
     * - Use (acctgTransId, acctgTransEntrySeqId) as PK
     * - Convert DR/CR -> D/C for debitCreditFlag
     */
    private static void createAcctgTransEntries(
            Delegator delegator,
            String acctgTransId,
            String organizationPartyId,
            List<GlPostingLine> lines,
            GenericValue userLogin
    ) throws GenericEntityException {

        long seq = 1;
        for (GlPostingLine l : lines) {
            GenericValue e = delegator.makeValue("AcctgTransEntry");

            e.set("acctgTransId", acctgTransId);
            e.set("acctgTransEntrySeqId", String.format("%05d", seq++));

            e.set("glAccountId", l.glAccountId);

            if (UtilValidate.isNotEmpty(organizationPartyId) && hasField(delegator, "AcctgTransEntry", "organizationPartyId")) {
                e.set("organizationPartyId", organizationPartyId);
            }

            String dc = toDebitCreditFlag(l.drCrFlag); // D or C
            e.set("debitCreditFlag", dc);

            // In your field list, "amount" exists (use it only)
            e.set("amount", l.amount);

            if (hasField(delegator, "AcctgTransEntry", "currencyUomId")) {
                e.set("currencyUomId", l.currencyUomId);
            }

            if (hasField(delegator, "AcctgTransEntry", "description")) {
                e.set("description", "Payroll " + l.lineTypeId);
            }

            // IMPORTANT: do NOT set createdByUserLogin unless your AcctgTransEntry model has it.
            // Your field dump did not show it, so we skip it to avoid next runtime error.

            delegator.create(e);
        }
    }

    private static void writePostingLinesGl(Delegator delegator, GenericValue posting, List<GlPostingLine> glLines) throws GenericEntityException {
        for (GlPostingLine l : glLines) {
            GenericValue pl = delegator.makeValue("PayrollPostingLine");
            pl.set("payrollPostingLineId", delegator.getNextSeqId("PayrollPostingLine"));
            pl.set("payrollPostingId", posting.getString("payrollPostingId"));
            pl.set("lineTypeId", l.lineTypeId);
            pl.set("amount", l.amount);
            if (hasField(delegator, "PayrollPostingLine", "currencyUomId")) pl.set("currencyUomId", l.currencyUomId);
            pl.set("glAccountId", l.glAccountId);
            pl.set("drCrFlag", l.drCrFlag); // DR/CR for traceability
            delegator.create(pl);
        }
    }

    // =========================================================
    // Invoice posting helpers
    // =========================================================
    private static String createInvoice(Delegator delegator,
                                        String organizationPartyId,
                                        String vendorPartyId,
                                        String invoiceTypeId,
                                        GenericValue run,
                                        GenericValue userLogin) throws GenericEntityException {

        String invoiceId = delegator.getNextSeqId("Invoice");
        GenericValue inv = delegator.makeValue("Invoice");
        inv.set("invoiceId", invoiceId);

///

inv.set("statusId", "INVOICE_IN_PROCESS");   // common initial status
inv.set("roleTypeId", "BILL_FROM_VENDOR");   // common for purchase invoice

///


        inv.set("invoiceTypeId", invoiceTypeId);
        if (hasField(delegator, "Invoice", "invoiceDate")) inv.set("invoiceDate", UtilDateTime.nowTimestamp());

        if (hasField(delegator, "Invoice", "partyIdFrom")) inv.set("partyIdFrom", vendorPartyId);
        if (hasField(delegator, "Invoice", "partyId")) inv.set("partyId", organizationPartyId);

        if (hasField(delegator, "Invoice", "description")) {
            inv.set("description", "Payroll Run " + run.getString("payrollRunId"));
        }

        String currencyUomId = hasField(delegator, "PayrollRun", "currencyUomId") ? run.getString("currencyUomId") : null;
        if (UtilValidate.isEmpty(currencyUomId)) currencyUomId = DEFAULT_CURRENCY_UOM;
        if (hasField(delegator, "Invoice", "currencyUomId")) inv.set("currencyUomId", currencyUomId);

        if (userLogin != null && hasField(delegator, "Invoice", "createdByUserLogin")) {
            inv.set("createdByUserLogin", userLogin.getString("userLoginId"));
        }

        delegator.create(inv);
        return invoiceId;
    }

    private static void createInvoiceItems(Delegator delegator,
                                          String invoiceId,
                                          List<InvoicePostingLine> lines,
                                          GenericValue run,
                                          GenericValue userLogin) throws GenericEntityException {

        String currencyUomId = hasField(delegator, "PayrollRun", "currencyUomId") ? run.getString("currencyUomId") : null;
        if (UtilValidate.isEmpty(currencyUomId)) currencyUomId = DEFAULT_CURRENCY_UOM;

        long seq = 1;
        for (InvoicePostingLine l : lines) {
            String itemSeqId = String.format("%05d", seq++);

            GenericValue ii = delegator.makeValue("InvoiceItem");
            ii.set("invoiceId", invoiceId);
            ii.set("invoiceItemSeqId", itemSeqId);
            ii.set("invoiceItemTypeId", l.invoiceItemTypeId);

            if (hasField(delegator, "InvoiceItem", "quantity")) ii.set("quantity", BigDecimal.ONE);
            if (hasField(delegator, "InvoiceItem", "amount")) ii.set("amount", l.amount);
            if (hasField(delegator, "InvoiceItem", "currencyUomId")) ii.set("currencyUomId", currencyUomId);

            if (hasField(delegator, "InvoiceItem", "description")) {
                ii.set("description", "Payroll " + l.lineTypeId);
            }

            if (userLogin != null && hasField(delegator, "InvoiceItem", "createdByUserLogin")) {
                ii.set("createdByUserLogin", userLogin.getString("userLoginId"));
            }

            delegator.create(ii);
        }
    }

    private static void writePostingLinesInvoice(Delegator delegator, GenericValue posting, List<InvoicePostingLine> invLines) throws GenericEntityException {
        for (InvoicePostingLine l : invLines) {
            GenericValue pl = delegator.makeValue("PayrollPostingLine");
            pl.set("payrollPostingLineId", delegator.getNextSeqId("PayrollPostingLine"));
            pl.set("payrollPostingId", posting.getString("payrollPostingId"));
            pl.set("lineTypeId", l.lineTypeId);
            pl.set("amount", l.amount);
            if (hasField(delegator, "PayrollPostingLine", "currencyUomId")) pl.set("currencyUomId", l.currencyUomId);
            pl.set("invoiceItemTypeId", l.invoiceItemTypeId);
            delegator.create(pl);
        }
    }

    private static String resolveOrgVendor(Delegator delegator, String organizationPartyId) {
        try {
            GenericValue org = EntityQuery.use(delegator).from("PayrollOrganization")
                    .where("organizationPartyId", organizationPartyId)
                    .queryFirst();
            if (org == null) return null;
            if (org.getModelEntity().isField("payrollVendorPartyId")) return org.getString("payrollVendorPartyId");
            if (org.getModelEntity().isField("vendorPartyId")) return org.getString("vendorPartyId");
            return null;
        } catch (Exception e) {
            return null;
        }
    }

    // =========================================================
    // Audit (non-blocking)
    // =========================================================
 private static void audit(LocalDispatcher dispatcher,
                          GenericValue userLogin,
                          String organizationPartyId,
                          String eventTypeId,
                          String entityName,
                          String recordId,
                          String oldValue,
                          String newValue,
                          String sourceIp) {
    // Audit must NEVER break the business transaction.
    // It is best-effort, swallow all exceptions, and avoid calling service if required inputs are missing.
    if (dispatcher == null) return;
    if (userLogin == null) return;
    if (UtilValidate.isEmpty(organizationPartyId)) return;
    if (UtilValidate.isEmpty(eventTypeId)) return;
    if (UtilValidate.isEmpty(entityName)) return;
    if (UtilValidate.isEmpty(recordId)) return;

    try {
        Map<String, Object> ctx = UtilMisc.toMap(
                "organizationPartyId", organizationPartyId,
                "eventTypeId", eventTypeId,
                // If you rebuild entity/service to use StatusItem composite PK, also pass:
                // "eventTypeStatusTypeId", "PAY_AUD_EVT",
                "entityName", entityName,
                "recordId", recordId,
                "oldValue", oldValue,
                "newValue", newValue,
                "sourceIp", sourceIp,
                "userLogin", userLogin
        );

        // Avoid participating in caller TX if possible (depends on service definition).
        // Even if it joins TX, we still swallow exceptions.
        Map<String, Object> resp = dispatcher.runSync("recordPayrollAuditEvent", ctx);

        // Optional: if service returns error and you want silent behavior, do nothing.
        // If you want debug logs only:
        // if (ServiceUtil.isError(resp)) Debug.logWarning("Audit failed: " + ServiceUtil.getErrorMessage(resp), MODULE);

    } catch (Throwable t) {
        // absolutely non-fatal; do not rethrow
        // Optional debug only:
        // Debug.logWarning(t, "Payroll audit failed (non-fatal)", MODULE);
    }
}
  // =========================================================
    // Utility
    // =========================================================
    private static void rollbackQuiet(boolean beganTx, Exception e) {
        try {
            if (beganTx) TransactionUtil.rollback(beganTx, e.getMessage(), e);
        } catch (Exception ignored) {}
    }

    private static BigDecimal nvl(BigDecimal v) {
        return v == null ? BigDecimal.ZERO : v;
    }

 private static void assertRunPostable(GenericValue run) {
    String statusId = run.getString("statusId");
    if (statusId != null) statusId = statusId.trim();

    // Block hard-stop states
    if (PR_VOID.equals(statusId)) {
        throw new IllegalArgumentException("PayrollRun is VOIDED; cannot post. Current status=" + statusId);
    }
    if (PR_FAIL.equals(statusId)) {
        throw new IllegalArgumentException("PayrollRun is FAILED; cannot post. Current status=" + statusId);
    }
    if (PR_DRAFT.equals(statusId) || PR_NEW.equals(statusId) || PR_PROC.equals(statusId)) {
        throw new IllegalArgumentException("PayrollRun is not processed yet; cannot post. Current status=" + statusId);
    }

    // ✅ Allow posting only when Approved
    // (Optionally also allow PR_PROCESSED if your business allows posting before approval)
    if (!PR_APPR.equals(statusId) && !PR_PROCESSED.equals(statusId)) {
        throw new IllegalArgumentException("PayrollRun must be APPROVED (or PROCESSED) before posting. Current status=" + statusId);
    }
}

    // =========================================================
    // MISSING METHODS (added to make the file COMPLETE/COMPILABLE)
    // =========================================================

   // private static void setPostingError(GenericValue posting, String msg) throws GenericEntityException {
  ///      posting.set("postingStatusId", POST_STS_ERROR);
  //      if (hasField(posting.getDelegator(), "PayrollPosting", "errorMessage")) {
 //           posting.set("errorMessage", truncate(msg, 2000));
  //      }
  //      posting.store();
  //  }

    private static String truncate(String s, int max) {
        if (s == null) return null;
        if (max <= 0) return "";
        return (s.length() <= max) ? s : s.substring(0, max);
    }

    // Data structures
    private static final class PayrollAmountLine {
        final String lineTypeId;
        final BigDecimal amount;
        final String currencyUomId;
        PayrollAmountLine(String lineTypeId, BigDecimal amount, String currencyUomId) {
            this.lineTypeId = lineTypeId;
            this.amount = amount;
            this.currencyUomId = currencyUomId;
        }
    }

    private static final class GlPostingLine {
        final String glAccountId;
        final String drCrFlag; // DR/CR
        final BigDecimal amount;
        final String currencyUomId;
        final String lineTypeId;
        GlPostingLine(String glAccountId, String drCrFlag, BigDecimal amount, String currencyUomId, String lineTypeId) {
            this.glAccountId = glAccountId;
            this.drCrFlag = drCrFlag;
            this.amount = amount;
            this.currencyUomId = currencyUomId;
            this.lineTypeId = lineTypeId;
        }
    }

    private static final class InvoicePostingLine {
        final String invoiceItemTypeId;
        final BigDecimal amount;
        final String currencyUomId;
        final String lineTypeId;
        InvoicePostingLine(String invoiceItemTypeId, BigDecimal amount, String currencyUomId, String lineTypeId) {
            this.invoiceItemTypeId = invoiceItemTypeId;
            this.amount = amount;
            this.currencyUomId = currencyUomId;
            this.lineTypeId = lineTypeId;
        }
    }
	
	// =========================================================
// Local helpers (keep in this class to avoid cross-file deps)
// =========================================================
/*  new   version  build by chatgpt 
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
*/ 
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
        // never block
    }
}

}
