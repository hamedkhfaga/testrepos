package org.apache.ofbiz.payroll.services;

import org.apache.ofbiz.base.util.Debug;
import org.apache.ofbiz.base.util.UtilValidate;
import org.apache.ofbiz.entity.Delegator;
import org.apache.ofbiz.entity.GenericValue;
import org.apache.ofbiz.entity.util.EntityQuery;
import org.apache.ofbiz.service.DispatchContext;
import org.apache.ofbiz.service.GenericServiceException;
import org.apache.ofbiz.service.LocalDispatcher;
import org.apache.ofbiz.service.ServiceUtil;

import java.util.HashMap;
import java.util.Map;

/**
 * PayrollPostingRouter
 *
 * Routes payroll posting after payroll processing based on PayrollRun.accountingPostOptionId.
 *
 * Expected posting options (configure in your status/enum/reference data and UI):
 *  - POST_TO_GL
 *  - POST_TO_INVOICE
 *  - POST_TO_BOTH
 *
 * Notes:
 * - This service should be called ONLY when you intentionally want to post (after approvals if required).
 * - It reads PayrollRun from DB to avoid missing context fields in REST calls.
 */
public class PayrollPostingRouter {
    public static final String MODULE = PayrollPostingRouter.class.getName();

    // Align these option IDs with your PayrollRun.accountingPostOptionId values
    private static final String OPT_GL = "POST_TO_GL";
    private static final String OPT_INVOICE = "POST_TO_INVOICE";
    private static final String OPT_BOTH = "POST_TO_BOTH";

    public static Map<String, Object> postPayrollRunAccounting(DispatchContext dctx, Map<String, ?> context) {
        final LocalDispatcher dispatcher = dctx.getDispatcher();
        final Delegator delegator = dctx.getDelegator();

        try {
            // -------------------------
            // Validate inputs
            // -------------------------
            final String payrollRunId = (String) context.get("payrollRunId");
            if (UtilValidate.isEmpty(payrollRunId)) {
                return ServiceUtil.returnError("payrollRunId is required");
            }

            // Optional inputs for invoice posting
            final String vendorPartyId = (String) context.get("vendorPartyId");
            final String invoiceTypeId = (String) context.get("invoiceTypeId");

            // Optional controls
            final Boolean forceRepost = (Boolean) context.get("forceRepost");
            final String sourceIp = (String) context.get("sourceIp");

            // userLogin is required by service definition
            final GenericValue userLogin = (GenericValue) context.get("userLogin");
            if (userLogin == null) {
                return ServiceUtil.returnError("userLogin is required");
            }

            // -------------------------
            // Load PayrollRun from DB (single source of truth)
            // -------------------------
            final GenericValue payrollRun = EntityQuery.use(delegator)
                    .from("PayrollRun")
                    .where("payrollRunId", payrollRunId)
                    .cache(false)
                    .queryOne();

            if (payrollRun == null) {
                return ServiceUtil.returnError("PayrollRun not found for payrollRunId=" + payrollRunId);
            }

            final String organizationPartyId = payrollRun.getString("organizationPartyId");
            final String option = UtilValidate.isNotEmpty(payrollRun.getString("accountingPostOptionId"))
                    ? payrollRun.getString("accountingPostOptionId")
                    : OPT_GL; // safe default

            // -------------------------
            // Build base service context
            // -------------------------
            final Map<String, Object> base = new HashMap<>();
            base.put("payrollRunId", payrollRunId);
            base.put("organizationPartyId", organizationPartyId);
            base.put("forceRepost", forceRepost);
            base.put("sourceIp", sourceIp);
            base.put("userLogin", userLogin);

            // If your downstream services need currency or period, you can pass them too:
            // base.put("currencyUomId", payrollRun.getString("currencyUomId"));
            // base.put("payrollPeriodId", payrollRun.getString("payrollPeriodId"));

            final Map<String, Object> out = ServiceUtil.returnSuccess();

            // -------------------------
            // Route to posting services
            // -------------------------
            if (OPT_GL.equals(option)) {
                Map<String, Object> r = dispatcher.runSync("postPayrollRunToGl", base);
                if (ServiceUtil.isError(r)) return r;
                out.put("glJournalId", r.get("glJournalId"));
                return out;
            }

            if (OPT_INVOICE.equals(option)) {
                Map<String, Object> inv = new HashMap<>(base);
                inv.put("vendorPartyId", vendorPartyId);
                inv.put("invoiceTypeId", invoiceTypeId);

                Map<String, Object> r = dispatcher.runSync("postPayrollRunToInvoice", inv);
                if (ServiceUtil.isError(r)) return r;
                out.put("invoiceId", r.get("invoiceId"));
                return out;
            }

            if (OPT_BOTH.equals(option)) {
                Map<String, Object> r1 = dispatcher.runSync("postPayrollRunToGl", base);
                if (ServiceUtil.isError(r1)) return r1;

                Map<String, Object> inv = new HashMap<>(base);
                inv.put("vendorPartyId", vendorPartyId);
                inv.put("invoiceTypeId", invoiceTypeId);

                Map<String, Object> r2 = dispatcher.runSync("postPayrollRunToInvoice", inv);
                if (ServiceUtil.isError(r2)) return r2;

                out.put("glJournalId", r1.get("glJournalId"));
                out.put("invoiceId", r2.get("invoiceId"));
                return out;
            }

            return ServiceUtil.returnError("Unsupported accountingPostOptionId=" + option
                    + " expected one of: " + OPT_GL + ", " + OPT_INVOICE + ", " + OPT_BOTH);

        } catch (GenericServiceException gse) {
            Debug.logError(gse, "postPayrollRunAccounting service execution failed", MODULE);
            return ServiceUtil.returnError("postPayrollRunAccounting failed: " + gse.getMessage());
        } catch (Exception e) {
            Debug.logError(e, "postPayrollRunAccounting failed", MODULE);
            return ServiceUtil.returnError("postPayrollRunAccounting failed: " + e.getMessage());
        }
    }
}
