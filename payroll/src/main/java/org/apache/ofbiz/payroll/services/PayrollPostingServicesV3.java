package org.apache.ofbiz.payroll.services;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.apache.ofbiz.base.util.Debug;
import org.apache.ofbiz.base.util.UtilDateTime;
import org.apache.ofbiz.base.util.UtilMisc;
import org.apache.ofbiz.base.util.UtilValidate;

import org.apache.ofbiz.entity.Delegator;
import org.apache.ofbiz.entity.GenericValue;
import org.apache.ofbiz.entity.util.EntityQuery;

import org.apache.ofbiz.service.DispatchContext;
import org.apache.ofbiz.service.LocalDispatcher;
import org.apache.ofbiz.service.ModelService;
import org.apache.ofbiz.service.ServiceUtil;
import org.apache.ofbiz.payroll.util.PayrollGovernanceUtil;


public class PayrollPostingServicesV3 {
    public static final String MODULE = PayrollPostingServicesV3.class.getName();

    // Options (match your existing enums/constants)
    private static final String PO_GL  = "PO_GL";
    private static final String PO_INV = "PO_INV";

public static Map<String, Object> postPayrollRunV3(DispatchContext dctx, Map<String, Object> context) {
    Delegator delegator = dctx.getDelegator();
    LocalDispatcher dispatcher = dctx.getDispatcher();

    Map<String, Object> data = new HashMap<>();
    Map<String, Object> legacyData = new HashMap<>();

    String payrollRunId = (String) context.get("payrollRunId");
    String postOptionId = (String) context.get("accountingPostOptionId");
    String forceRepostS = (String) context.get("forceRepost");
    boolean forceRepost = "Y".equalsIgnoreCase(forceRepostS);
    GenericValue userLogin = (GenericValue) context.get("userLogin");

    if (UtilValidate.isEmpty(payrollRunId)) {
        return errorNonNull("payrollRunId is required", data, legacyData);
    }
    if (UtilValidate.isEmpty(postOptionId)) {
        return errorNonNull("accountingPostOptionId is required", data, legacyData);
    }

    try {
        GenericValue run = EntityQuery.use(delegator)
                .from("PayrollRun")
                .where("payrollRunId", payrollRunId)
                .queryOne();

        if (run == null) {
            return errorNonNull("PayrollRun not found: " + payrollRunId, data, legacyData);
        }

        String organizationPartyId = run.getString("organizationPartyId");
        String payrollPeriodId = run.getString("payrollPeriodId");
        String runStatusId = run.getString("statusId");

        // Governance pre-check: do this before any posting logic
        try {
            PayrollGovernanceUtil.ensureRunAndPeriodEditable(
                    delegator,
                    organizationPartyId,
                    payrollPeriodId,
                    payrollRunId
            );
        } catch (Exception e) {
            auditBestEffort(dispatcher, userLogin, organizationPartyId, payrollRunId, payrollPeriodId,
                    "PAY_ACT_POST_RUN", "ACT_FAIL",
                    "postPayrollRunV3 blocked: " + e.getMessage());
            throw e;
        }

        // Governance/business rule: posting allowed from PR_DONE.
        // If already PR_POST, replay without force becomes idempotent success.
        if ("PR_POST".equals(runStatusId)) {
            if (!forceRepost) {
                data.put("payrollRunId", payrollRunId);
                data.put("organizationPartyId", organizationPartyId);
                data.put("payrollPeriodId", payrollPeriodId);
                data.put("runStatusBefore", runStatusId);
                data.put("runStatusAfter", "PR_POST");
                data.put("accountingPostOptionId", postOptionId);
                data.put("forceRepost", "N");
                data.put("changed", "N");

                legacyData.putAll(data);

                auditBestEffort(dispatcher, userLogin, organizationPartyId, payrollRunId, payrollPeriodId,
                        "PAY_ACT_POST_RUN", "SKIPPED", "Payroll run already posted");

                Map<String, Object> ok = ServiceUtil.returnSuccess("Payroll run already posted");
                ok.put("data", data);
                ok.put("legacyData", legacyData);
                return ok;
            }
        } else if (!"PR_DONE".equals(runStatusId)) {
            auditBestEffort(dispatcher, userLogin, organizationPartyId, payrollRunId, payrollPeriodId,
                    "PAY_ACT_POST_RUN", "ACT_FAIL", "Run status not postable: " + runStatusId);
            return errorNonNull("Run status not postable: " + runStatusId, data, legacyData);
        }

        long resultCount = EntityQuery.use(delegator)
                .from("EmployeePayrollResult")
                .where("payrollRunId", payrollRunId)
                .queryCount();

        if (resultCount <= 0) {
            return errorNonNull("No EmployeePayrollResult found for run=" + payrollRunId, data, legacyData);
        }

        // Prevent double posting unless forceRepost=Y
        String isGlPosted = run.getString("isGlPosted");
        String isInvPosted = run.getString("isInvoicePosted");

        if (!forceRepost) {
            if (PO_GL.equals(postOptionId) && "Y".equalsIgnoreCase(isGlPosted)) {
                return errorNonNull("Already GL posted. Use forceRepost=Y", data, legacyData);
            }
            if (PO_INV.equals(postOptionId) && "Y".equalsIgnoreCase(isInvPosted)) {
                return errorNonNull("Already Invoice posted. Use forceRepost=Y", data, legacyData);
            }
        }

        Timestamp now = UtilDateTime.nowTimestamp();

        // Phase #3 foundation: mark flags + store placeholder ids.
        // Later replace placeholder ids with actual posting service outputs.
        String glJournalId = null;
        String invoiceId = null;

        if (PO_GL.equals(postOptionId)) {
            glJournalId = "GLJ_" + payrollRunId; // placeholder
            run.set("isGlPosted", "Y");
            run.set("glJournalId", glJournalId);
            run.set("glPostedDate", now);
        } else if (PO_INV.equals(postOptionId)) {
            invoiceId = "INV_" + payrollRunId; // placeholder
            run.set("isInvoicePosted", "Y");
            run.set("invoiceId", invoiceId);
            run.set("invoicePostedDate", now);
        } else {
            return errorNonNull("Unknown accountingPostOptionId: " + postOptionId, data, legacyData);
        }

        // After posting: PR_POST
        run.set("statusId", "PR_POST");
        run.store();

        Map<String, Object> out = ServiceUtil.returnSuccess("###PAYROLL_POST_V3### posted=" + resultCount);

        data.put("payrollRunId", payrollRunId);
        data.put("organizationPartyId", organizationPartyId);
        data.put("payrollPeriodId", payrollPeriodId);
        data.put("runStatusBefore", runStatusId);
        data.put("runStatusAfter", "PR_POST");
        data.put("postedCount", resultCount);
        data.put("glJournalId", glJournalId);
        data.put("invoiceId", invoiceId);
        data.put("accountingPostOptionId", postOptionId);
        data.put("postedAt", now);
        data.put("forceRepost", forceRepost ? "Y" : "N");
        data.put("changed", "Y");

        legacyData.putAll(data);

        auditBestEffort(dispatcher, userLogin, organizationPartyId, payrollRunId, payrollPeriodId,
                "PAY_ACT_POST_RUN", "SUCCESS", "###PAYROLL_POST_V3### posted=" + resultCount);

        out.put("data", data);
        out.put("legacyData", legacyData);
        out.put("postedCount", resultCount);
        out.put("glJournalId", glJournalId);
        out.put("invoiceId", invoiceId);

        return out;

    } catch (Exception e) {
        Debug.logError(e, "postPayrollRunV3 failed", MODULE);
        try {
            if (shouldWritePostErrorAudit(e)) {
                auditBestEffort(dispatcher, userLogin,
                        (String) data.get("organizationPartyId"),
                        (String) data.get("payrollRunId"),
                        (String) data.get("payrollPeriodId"),
                        "PAY_ACT_POST_RUN",
                        "ACT_ERROR",
                        e.getMessage());
            }
        } catch (Exception ignored) {}
        return ServiceUtil.returnError("postPayrollRunV3 failed: " + e.getMessage());
    }
}

private static boolean shouldWritePostErrorAudit(Exception e) {
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
    if (msg.startsWith("Run status not postable:")) {
        return false;
    }
    return true;
}
    private static Map<String, Object> errorNonNull(String msg, Map<String, Object> data, Map<String, Object> legacyData) {
        Map<String, Object> out = ServiceUtil.returnError(msg);
        if (data != null) out.put("data", data);
        if (legacyData != null) out.put("legacyData", legacyData);
        return out;
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
            Debug.logWarning(e, "Best-effort payroll audit failed for postPayrollRunV3 actionTypeId=" + actionTypeId, MODULE);
        }
    }


}
