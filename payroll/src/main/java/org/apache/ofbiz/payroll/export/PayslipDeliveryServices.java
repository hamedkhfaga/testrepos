package org.apache.ofbiz.payroll.export;

import org.apache.ofbiz.base.util.*;
import org.apache.ofbiz.entity.*;
import org.apache.ofbiz.entity.util.EntityQuery;
import org.apache.ofbiz.service.*;

import java.sql.Timestamp;
import java.util.*;

public class PayslipDeliveryServices {

    public static Map<String, Object> sendPayslipsForPayrollRun(DispatchContext dctx, Map<String, ?> context)
            throws Exception {

        Delegator delegator = dctx.getDelegator();
        LocalDispatcher dispatcher = dctx.getDispatcher();

        String payrollRunId = (String) context.get("payrollRunId");
        Timestamp expiresDate = (Timestamp) context.get("expiresDate");
        if (expiresDate == null) {
            expiresDate = UtilDateTime.addDaysToTimestamp(UtilDateTime.nowTimestamp(), 7);
        }

        // Verify run approved
        GenericValue run = delegator.findOne("PayrollRun", UtilMisc.toMap("payrollRunId", payrollRunId), false);
        if (run == null) return ServiceUtil.returnError("PayrollRun not found");
        if (!"PAYROLL_APPROVED".equals(run.getString("statusId"))) {
            return ServiceUtil.returnError("Payslips can be sent only after payroll approval");
        }

        List<GenericValue> items = EntityQuery.use(delegator)
                .from("PayrollRunItem")
                .where("payrollRunId", payrollRunId)
                .queryList();

        int sent = 0, failed = 0;

        for (GenericValue item : items) {
            String payrollRunItemId = item.getString("payrollRunItemId");
            String partyId = item.getString("partyId");

            try {
                // 1) Generate PDF (your existing service)
                Map<String, Object> pdfRes = dispatcher.runSync(
                        "generatePayslipPdf",
                        UtilMisc.toMap("payrollRunItemId", payrollRunItemId, "userLogin", context.get("userLogin"))
                );

                if (ServiceUtil.isError(pdfRes)) {
                    throw new Exception(ServiceUtil.getErrorMessage(pdfRes));
                }

                String pdfFile = (String) pdfRes.get("pdfFile");

                // 2) Find employee email
                String email = resolveEmployeeEmail(delegator, partyId);
                if (UtilValidate.isEmpty(email)) {
                    throw new Exception("No email found for partyId=" + partyId);
                }

                // 3) Create token
                String token = UUID.randomUUID().toString().replace("-", "");

                GenericValue tok = delegator.makeValue("PayslipAccessToken");
                tok.set("payslipAccessTokenId", delegator.getNextSeqId("PayslipAccessToken"));
                tok.set("token", token);
                tok.set("payrollRunItemId", payrollRunItemId);
                tok.set("partyId", partyId);
                tok.set("expiresDate", expiresDate);
                tok.set("statusId", "TOKEN_ACTIVE");
                tok.set("createdDate", UtilDateTime.nowTimestamp());
                delegator.create(tok);

                // 4) Build secure link
                // NOTE: set this in a config/property in real use; keeping simple here
                String baseUrl = (String) context.get("baseUrl");
                if (UtilValidate.isEmpty(baseUrl)) {
                    baseUrl = "https://localhost:8443";
                }
                String link = baseUrl + "/rest/services/payroll/downloadPayslipByToken?token=" + token;

                // 5) Send email via OFBiz service
                String subject = "Your Payslip - Payroll Run " + payrollRunId;
                String body = "Dear Employee,\n\n"
                        + "Your payslip is ready. Download securely using this link (expires on "
                        + expiresDate + "):\n\n"
                        + link + "\n\n"
                        + "Regards,\nPayroll Team";

                Map<String, Object> mailCtx = UtilMisc.toMap(
                        "sendTo", email,
                        "subject", subject,
                        "body", body,
                        "contentType", "text/plain",
                        "userLogin", context.get("userLogin")
                );

                Map<String, Object> mailRes = dispatcher.runSync("sendMail", mailCtx);
                if (ServiceUtil.isError(mailRes)) {
                    throw new Exception(ServiceUtil.getErrorMessage(mailRes));
                }

                // 6) Delivery log
                createDeliveryLog(delegator, payrollRunItemId, partyId, email, "SENT", null);
                sent++;

            } catch (Exception e) {
                createDeliveryLog(delegator,
                        item.getString("payrollRunItemId"),
                        item.getString("partyId"),
                        null,
                        "FAILED",
                        e.getMessage());
                failed++;
            }
        }

        Map<String, Object> result = ServiceUtil.returnSuccess();
        result.put("sentCount", sent);
        result.put("failedCount", failed);
        return result;
    }

    public static Map<String, Object> downloadPayslipByToken(DispatchContext dctx, Map<String, ?> context)
            throws GenericEntityException {

        Delegator delegator = dctx.getDelegator();
        String token = (String) context.get("token");
        if (UtilValidate.isEmpty(token)) {
            return ServiceUtil.returnError("token is required");
        }

        GenericValue tok = EntityQuery.use(delegator)
                .from("PayslipAccessToken")
                .where("token", token)
                .queryFirst();

        if (tok == null) return ServiceUtil.returnError("Invalid token");

        Timestamp now = UtilDateTime.nowTimestamp();
        if (tok.getTimestamp("expiresDate").before(now)) {
            tok.set("statusId", "TOKEN_EXPIRED");
            tok.store();
            return ServiceUtil.returnError("Token expired");
        }

        if ("TOKEN_USED".equals(tok.getString("statusId"))) {
            return ServiceUtil.returnError("Token already used");
        }

        String payrollRunItemId = tok.getString("payrollRunItemId");

        // Your generated PDF naming convention:
        String pdfFile = "runtime/payslips/payslip_" + payrollRunItemId + ".pdf";

        tok.set("statusId", "TOKEN_USED");
        tok.set("usedDate", now);
        tok.store();

        Map<String, Object> result = ServiceUtil.returnSuccess();
        result.put("pdfFile", pdfFile);
        return result;
    }

    /* ----------------------------------------------------- */
    /* Helpers                                               */
    /* ----------------------------------------------------- */

    private static String resolveEmployeeEmail(Delegator delegator, String partyId) throws GenericEntityException {
        // Most OFBiz setups store emails in PartyContactMech + ContactMech + Telecom/Postal.
        // We search for PRIMARY_EMAIL in PartyContactMechPurpose.
        GenericValue pcm = EntityQuery.use(delegator)
                .from("PartyContactMechPurpose")
                .where("partyId", partyId, "contactMechPurposeTypeId", "PRIMARY_EMAIL")
                .queryFirst();

        if (pcm == null) return null;

        GenericValue partyContactMech = EntityQuery.use(delegator)
                .from("PartyContactMech")
                .where("partyId", partyId, "contactMechId", pcm.getString("contactMechId"))
                .queryFirst();

        if (partyContactMech == null) return null;

        GenericValue contactMech = delegator.findOne("ContactMech",
                UtilMisc.toMap("contactMechId", partyContactMech.getString("contactMechId")), false);

        if (contactMech == null) return null;

        GenericValue email = delegator.findOne("EmailAddress",
                UtilMisc.toMap("contactMechId", contactMech.getString("contactMechId")), false);

        return (email != null) ? email.getString("emailAddress") : null;
    }

    private static void createDeliveryLog(Delegator delegator,
                                         String payrollRunItemId,
                                         String partyId,
                                         String email,
                                         String status,
                                         String err) throws GenericEntityException {

        GenericValue log = delegator.makeValue("PayslipDeliveryLog");
        log.set("payslipDeliveryLogId", delegator.getNextSeqId("PayslipDeliveryLog"));
        log.set("payrollRunItemId", payrollRunItemId);
        log.set("partyId", partyId);
        log.set("emailAddress", email);
        log.set("sentDate", UtilDateTime.nowTimestamp());
        log.set("statusId", status);
        log.set("errorMessage", err);
        delegator.create(log);
    }
}
