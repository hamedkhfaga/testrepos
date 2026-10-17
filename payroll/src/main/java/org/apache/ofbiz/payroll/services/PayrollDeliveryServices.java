package org.apache.ofbiz.payroll.services;

import org.apache.ofbiz.base.util.Debug;
import org.apache.ofbiz.base.util.UtilDateTime;
import org.apache.ofbiz.base.util.UtilMisc;
import org.apache.ofbiz.base.util.UtilValidate;
import org.apache.ofbiz.entity.Delegator;
import org.apache.ofbiz.entity.GenericValue;
import org.apache.ofbiz.entity.transaction.TransactionUtil;
import org.apache.ofbiz.entity.util.EntityQuery;
import org.apache.ofbiz.service.DispatchContext;
import org.apache.ofbiz.service.LocalDispatcher;
import org.apache.ofbiz.service.ServiceUtil;

import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

public class PayrollDeliveryServices {
    public static final String MODULE = PayrollDeliveryServices.class.getName();

    // Email queue statuses
    public static final String EMQ_NEW   = "EMQ_NEW";
    public static final String EMQ_PROC  = "EMQ_PROC";
    public static final String EMQ_SENT  = "EMQ_SENT";
    public static final String EMQ_ERR   = "EMQ_ERR";

    // SMS queue statuses
    public static final String SMSQ_NEW  = "SMSQ_NEW";
    public static final String SMSQ_PROC = "SMSQ_PROC";
    public static final String SMSQ_SENT = "SMSQ_SENT";
    public static final String SMSQ_ERR  = "SMSQ_ERR";

    /**
     * Queue an email for payslip link / run export file.
     */
    public static Map<String, Object> queuePayrollEmail(DispatchContext dctx, Map<String, ?> context) {
        Delegator delegator = dctx.getDelegator();

        String orgPartyId = (String) context.get("orgPartyId");
        String toAddress  = (String) context.get("toAddress");
        String subject    = (String) context.get("subject");
        String content    = (String) context.get("content");

        String payrollRunId = (String) context.get("payrollRunId");
        String payrollResultId = (String) context.get("payrollResultId");
        String partyId = (String) context.get("partyId");

        String attachmentExportFileId = (String) context.get("attachmentExportFileId");

        String token = (String) context.get("token");
        Timestamp expiresDate = (Timestamp) context.get("expiresDate");

        try {
            PayrollStrictRules.requireNotEmpty("orgPartyId", orgPartyId);
            PayrollStrictRules.requireNotEmpty("toAddress", toAddress);
            PayrollStrictRules.requireNotEmpty("subject", subject);
            PayrollStrictRules.requireNotEmpty("content", content);

            String emailQueueId = delegator.getNextSeqId("PayrollEmailQueue");
            GenericValue q = delegator.makeValue("PayrollEmailQueue");
            q.set("emailQueueId", emailQueueId);
            q.set("orgPartyId", orgPartyId);
            q.set("payrollRunId", payrollRunId);
            q.set("payrollResultId", payrollResultId);
            q.set("partyId", partyId);
            q.set("toAddress", toAddress);
            q.set("subject", subject);
            q.set("content", content);
            q.set("attachmentExportFileId", attachmentExportFileId);

            if (UtilValidate.isNotEmpty(token)) q.set("token", token);
            if (expiresDate != null) q.set("expiresDate", expiresDate);

            q.set("statusId", EMQ_NEW);
            q.set("createdDate", UtilDateTime.nowTimestamp());

            // optional fields; safe if entity has them
            safeSet(q, "attemptCount", 0L);
            safeSet(q, "lastAttemptDate", null);

            delegator.create(q);

            Map<String, Object> out = ServiceUtil.returnSuccess();
            out.put("emailQueueId", emailQueueId);
            return out;

        } catch (Exception e) {
            Debug.logError(e, "queuePayrollEmail failed", MODULE);
            return ServiceUtil.returnError("queuePayrollEmail failed: " + e.getMessage());
        }
    }

    /**
     * Worker: processes N emails in NEW status.
     *
     * Inputs:
     * - maxItems (optional, default 50)
     * - baseUrl (optional) used to build token link
     * - userLogin (optional) passed to sendMail if required
     */
    public static Map<String, Object> processPayrollEmailQueue(DispatchContext dctx, Map<String, ?> context) {
        LocalDispatcher dispatcher = dctx.getDispatcher();
        Delegator delegator = dctx.getDelegator();

        Integer maxItems = (Integer) context.get("maxItems");
        int limit = (maxItems == null || maxItems <= 0) ? 50 : maxItems;

        String baseUrl = (String) context.get("baseUrl");
        if (UtilValidate.isEmpty(baseUrl)) baseUrl = "https://localhost:8443";

        int sent = 0;
        int errors = 0;

        List<String> lockedIds = new ArrayList<>();

        // 1) Lock batch (short tx): NEW -> PROC
        boolean beganTx = false;
        try {
            beganTx = TransactionUtil.begin();

            List<GenericValue> list = EntityQuery.use(delegator)
                    .from("PayrollEmailQueue")
                    .where("statusId", EMQ_NEW)
                    .orderBy("createdDate", "emailQueueId")
                    .queryList();

            int count = 0;
            for (GenericValue q : list) {
                if (count >= limit) break;
                q.set("statusId", EMQ_PROC);
                q.store();
                lockedIds.add(q.getString("emailQueueId"));
                count++;
            }

            TransactionUtil.commit(beganTx);
        } catch (Exception e) {
            try { if (beganTx) TransactionUtil.rollback(beganTx, e.getMessage(), e); } catch (Exception ignored) {}
            return ServiceUtil.returnError("processPayrollEmailQueue lock phase failed: " + e.getMessage());
        }

        // 2) Send outside tx, then update status in short tx
        for (String emailQueueId : lockedIds) {
            try {
                GenericValue q = EntityQuery.use(delegator)
                        .from("PayrollEmailQueue")
                        .where("emailQueueId", emailQueueId)
                        .queryOne();
                if (q == null) continue;

                String orgPartyId = q.getString("orgPartyId");
                String to = q.getString("toAddress");
                String subject = q.getString("subject");
                String body = q.getString("content");

                // token link support
                String token = q.getString("token");
                Timestamp expiresDate = q.getTimestamp("expiresDate");
                if (UtilValidate.isNotEmpty(token)) {
                    String link = baseUrl + "/payroll/control/downloadPayslipPdf?token=" + token;
                    String expTxt = (expiresDate != null) ? (" (expires: " + expiresDate + ")") : "";
                    body = body + "\n\nPayslip download link" + expTxt + ":\n" + link + "\n";
                }

                // Attachment info (optional)
                String attachmentExportFileId = q.getString("attachmentExportFileId");
                String attachmentPath = null;
                String attachmentName = null;
                if (UtilValidate.isNotEmpty(attachmentExportFileId)) {
                    GenericValue file = EntityQuery.use(delegator)
                            .from("PayrollExportFile")
                            .where("exportFileId", attachmentExportFileId)
                            .queryOne();
                    if (file != null) {
                        attachmentPath = file.getString("filePath");
                        attachmentName = file.getString("fileName");
                    }
                }

                Map<String, Object> mailCtx = UtilMisc.toMap(
                        "sendTo", to,
                        "subject", subject,
                        "body", body,
                        "contentType", "text/plain"
                );

                if (context.get("userLogin") != null) {
                    mailCtx.put("userLogin", context.get("userLogin"));
                }

                // Only pass attachment keys if you have mail wrapper service that supports them
                if (attachmentPath != null) {
                    mailCtx.put("attachmentFileName", attachmentName);
                    mailCtx.put("attachmentFilePath", attachmentPath);
                }

                Map<String, Object> resp = dispatcher.runSync("sendMail", mailCtx);
                if (ServiceUtil.isError(resp)) {
                    throw new Exception(ServiceUtil.getErrorMessage(resp));
                }

                // Mark SENT (short tx)
                boolean tx2 = false;
                try {
                    tx2 = TransactionUtil.begin();
                    GenericValue q2 = delegator.findOne("PayrollEmailQueue",
                            UtilMisc.toMap("emailQueueId", emailQueueId), false);
                    if (q2 != null) {
                        q2.set("statusId", EMQ_SENT);
                        safeSet(q2, "sentDate", UtilDateTime.nowTimestamp());
                        safeSet(q2, "errorMessage", null);
                        bumpAttemptSafe(q2);
                        q2.store();
                        sent++;
                    }
                    TransactionUtil.commit(tx2);
                } catch (Exception e) {
                    try { if (tx2) TransactionUtil.rollback(tx2, e.getMessage(), e); } catch (Exception ignored) {}
                    errors++;
                }

                auditSafe(delegator, orgPartyId, "PayrollEmailQueue", emailQueueId, "PAY_AUD_EMAIL_SENT");

            } catch (Exception ex) {
                errors++;

                // Mark ERR (short tx)
                boolean tx3 = false;
                try {
                    tx3 = TransactionUtil.begin();
                    GenericValue q3 = delegator.findOne("PayrollEmailQueue",
                            UtilMisc.toMap("emailQueueId", emailQueueId), false);
                    if (q3 != null) {
                        q3.set("statusId", EMQ_ERR);
                        safeSet(q3, "errorMessage", trimToLen(ex.getMessage(), 2000));
                        bumpAttemptSafe(q3);
                        q3.store();
                    }
                    TransactionUtil.commit(tx3);
                } catch (Exception e) {
                    try { if (tx3) TransactionUtil.rollback(tx3, e.getMessage(), e); } catch (Exception ignored) {}
                }
            }
        }

        Map<String, Object> out = ServiceUtil.returnSuccess();
        out.put("sentCount", sent);
        out.put("errorCount", errors);
        out.put("lockedCount", lockedIds.size());
        return out;
    }

    /**
     * Queue SMS
     */
    public static Map<String, Object> queuePayrollSms(DispatchContext dctx, Map<String, ?> context) {
        Delegator delegator = dctx.getDelegator();

        String orgPartyId = (String) context.get("orgPartyId");
        String toNumber = (String) context.get("toNumber");
        String messageText = (String) context.get("messageText");

        String payrollRunId = (String) context.get("payrollRunId");
        String payrollResultId = (String) context.get("payrollResultId");
        String partyId = (String) context.get("partyId");

        try {
            PayrollStrictRules.requireNotEmpty("orgPartyId", orgPartyId);
            PayrollStrictRules.requireNotEmpty("toNumber", toNumber);
            PayrollStrictRules.requireNotEmpty("messageText", messageText);

            String smsQueueId = delegator.getNextSeqId("PayrollSmsQueue");
            GenericValue q = delegator.makeValue("PayrollSmsQueue");
            q.set("smsQueueId", smsQueueId);
            q.set("orgPartyId", orgPartyId);
            q.set("payrollRunId", payrollRunId);
            q.set("payrollResultId", payrollResultId);
            q.set("partyId", partyId);
            q.set("toNumber", toNumber);
            q.set("messageText", messageText);
            q.set("statusId", SMSQ_NEW);
            q.set("createdDate", UtilDateTime.nowTimestamp());

            safeSet(q, "attemptCount", 0L);
            safeSet(q, "lastAttemptDate", null);

            delegator.create(q);

            Map<String, Object> out = ServiceUtil.returnSuccess();
            out.put("smsQueueId", smsQueueId);
            return out;

        } catch (Exception e) {
            Debug.logError(e, "queuePayrollSms failed", MODULE);
            return ServiceUtil.returnError("queuePayrollSms failed: " + e.getMessage());
        }
    }

    /**
     * Worker: process SMS queue.
     * Calls provider abstraction service: "sendSmsProvider"
     */
    public static Map<String, Object> processPayrollSmsQueue(DispatchContext dctx, Map<String, ?> context) {
        LocalDispatcher dispatcher = dctx.getDispatcher();
        Delegator delegator = dctx.getDelegator();

        Integer maxItems = (Integer) context.get("maxItems");
        int limit = (maxItems == null || maxItems <= 0) ? 50 : maxItems;

        int sent = 0;
        int errors = 0;

        List<String> lockedIds = new ArrayList<>();

        // 1) Lock batch (short tx)
        boolean beganTx = false;
        try {
            beganTx = TransactionUtil.begin();

            List<GenericValue> list = EntityQuery.use(delegator)
                    .from("PayrollSmsQueue")
                    .where("statusId", SMSQ_NEW)
                    .orderBy("createdDate", "smsQueueId")
                    .queryList();

            int count = 0;
            for (GenericValue q : list) {
                if (count >= limit) break;
                q.set("statusId", SMSQ_PROC);
                q.store();
                lockedIds.add(q.getString("smsQueueId"));
                count++;
            }

            TransactionUtil.commit(beganTx);
        } catch (Exception e) {
            try { if (beganTx) TransactionUtil.rollback(beganTx, e.getMessage(), e); } catch (Exception ignored) {}
            return ServiceUtil.returnError("processPayrollSmsQueue lock phase failed: " + e.getMessage());
        }

        // 2) Send each locked row outside tx
        for (String smsQueueId : lockedIds) {
            try {
                GenericValue q = EntityQuery.use(delegator)
                        .from("PayrollSmsQueue")
                        .where("smsQueueId", smsQueueId)
                        .queryOne();
                if (q == null) continue;

                String orgPartyId = q.getString("orgPartyId");

                Map<String, Object> smsCtx = UtilMisc.toMap(
                        "toNumber", q.getString("toNumber"),
                        "messageText", q.getString("messageText")
                );
                if (context.get("userLogin") != null) {
                    smsCtx.put("userLogin", context.get("userLogin"));
                }

                Map<String, Object> resp = dispatcher.runSync("sendSmsProvider", smsCtx);
                if (ServiceUtil.isError(resp)) {
                    throw new Exception(ServiceUtil.getErrorMessage(resp));
                }

                // Mark SENT (short tx)
                boolean tx2 = false;
                try {
                    tx2 = TransactionUtil.begin();
                    GenericValue q2 = delegator.findOne("PayrollSmsQueue",
                            UtilMisc.toMap("smsQueueId", smsQueueId), false);
                    if (q2 != null) {
                        q2.set("statusId", SMSQ_SENT);
                        safeSet(q2, "sentDate", UtilDateTime.nowTimestamp());
                        safeSet(q2, "providerMessageId", (String) resp.get("providerMessageId"));
                        safeSet(q2, "errorMessage", null);
                        bumpAttemptSafe(q2);
                        q2.store();
                        sent++;
                    }
                    TransactionUtil.commit(tx2);
                } catch (Exception e) {
                    try { if (tx2) TransactionUtil.rollback(tx2, e.getMessage(), e); } catch (Exception ignored) {}
                    errors++;
                }

                auditSafe(delegator, orgPartyId, "PayrollSmsQueue", smsQueueId, "PAY_AUD_SMS_SENT");

            } catch (Exception ex) {
                errors++;

                boolean tx3 = false;
                try {
                    tx3 = TransactionUtil.begin();
                    GenericValue q3 = delegator.findOne("PayrollSmsQueue",
                            UtilMisc.toMap("smsQueueId", smsQueueId), false);
                    if (q3 != null) {
                        q3.set("statusId", SMSQ_ERR);
                        safeSet(q3, "errorMessage", trimToLen(ex.getMessage(), 2000));
                        bumpAttemptSafe(q3);
                        q3.store();
                    }
                    TransactionUtil.commit(tx3);
                } catch (Exception e) {
                    try { if (tx3) TransactionUtil.rollback(tx3, e.getMessage(), e); } catch (Exception ignored) {}
                }
            }
        }

        Map<String, Object> out = ServiceUtil.returnSuccess();
        out.put("sentCount", sent);
        out.put("errorCount", errors);
        out.put("lockedCount", lockedIds.size());
        return out;
    }

    /* ===================================================== */
    /* Helpers                                                */
    /* ===================================================== */

    private static void bumpAttemptSafe(GenericValue q) {
        try {
            Long attempt = q.getLong("attemptCount");
            if (attempt == null) attempt = 0L;
            q.set("attemptCount", attempt + 1);
            q.set("lastAttemptDate", UtilDateTime.nowTimestamp());
        } catch (Exception ignored) {
            // field may not exist
        }
    }

    private static void auditSafe(Delegator delegator, String orgPartyId, String entityName, String recordId, String actionTypeId) {
        try {
            GenericValue aud = delegator.makeValue("PayrollAuditLog");
            aud.set("payrollAuditLogId", delegator.getNextSeqId("PayrollAuditLog"));
            aud.set("orgPartyId", orgPartyId);
            aud.set("entityName", entityName);
            aud.set("recordId", recordId);
            aud.set("actionTypeId", actionTypeId);
            aud.set("changedDate", UtilDateTime.nowTimestamp());
            delegator.create(aud);
        } catch (Exception ignored) {}
    }

    private static String trimToLen(String s, int max) {
        if (s == null) return null;
        if (max <= 0) return "";
        return (s.length() <= max) ? s : s.substring(0, max);
    }

    /**
     * Safe set: won’t crash if the field doesn’t exist on entity.
     */
    private static void safeSet(GenericValue gv, String fieldName, Object value) {
        try {
            gv.set(fieldName, value);
        } catch (Exception ignored) {}
    }
}
