package org.apache.ofbiz.payroll.services;

import org.apache.ofbiz.base.util.Debug;
import org.apache.ofbiz.base.util.UtilDateTime;
import org.apache.ofbiz.base.util.UtilValidate;

import org.apache.ofbiz.entity.Delegator;
import org.apache.ofbiz.entity.GenericValue;

import org.apache.ofbiz.service.DispatchContext;
import org.apache.ofbiz.service.ServiceUtil;

import java.util.Map;
import org.apache.ofbiz.entity.util.EntityQuery;
import org.apache.ofbiz.entity.transaction.TransactionUtil;

public class PayrollAuditServices {
    public static final String MODULE = PayrollAuditServices.class.getName();

    /**
     * Service entrypoint used by services.xml:
     * recordPayrollAuditEvent(DispatchContext, Map)
     *
     * This MUST exist with this exact signature, otherwise OFBiz throws NoSuchMethodException.
     * Non-fatal: audit should never rollback payroll processing.
     */
  public static Map<String, Object> recordPayrollAuditEvent(DispatchContext dctx, Map<String, ? extends Object> context) {
    // HARD RULE: audit must never fail the business service.
    boolean beganTx = false;
    boolean success = false;

    try {
        Delegator delegator = dctx.getDelegator();

        // ---- Accept multiple possible field names (compatibility) ----
        String organizationPartyId =
                firstNonEmpty((String) context.get("organizationPartyId"),
                              (String) context.get("orgPartyId"),
                              (String) context.get("orgId"));

        String eventTypeId =
                firstNonEmpty((String) context.get("eventTypeId"),
                              (String) context.get("auditEventTypeId"),
                              (String) context.get("actionTypeId"));

        // IMPORTANT for OFBiz StatusItem FK: StatusItem PK = (statusId, statusTypeId)
        // Your PayrollAuditEvent.eventTypeId -> StatusItem.statusId, so you MUST also ensure the type is PAY_AUD_EVT.
        String eventTypeStatusTypeId =
                firstNonEmpty((String) context.get("eventTypeStatusTypeId"),
                              (String) context.get("statusTypeId"),
                              (String) context.get("eventStatusTypeId"),
                              "PAY_AUD_EVT");

        String entityName =
                firstNonEmpty((String) context.get("entityName"),
                              (String) context.get("targetEntityName"),
                              "Payroll");

        String recordId =
                firstNonEmpty((String) context.get("recordId"),
                              (String) context.get("targetRecordId"),
                              (String) context.get("primaryKey"),
                              (String) context.get("pk"),
                              "N/A");

        String oldValue =
                firstNonEmpty((String) context.get("oldValue"),
                              (String) context.get("oldValuesJson"),
                              (String) context.get("oldValues"));

        String newValue =
                firstNonEmpty((String) context.get("newValue"),
                              (String) context.get("newValuesJson"),
                              (String) context.get("newValues"));

        String sourceIp =
                firstNonEmpty((String) context.get("sourceIp"),
                              (String) context.get("ipAddress"),
                              (String) context.get("clientIp"));

        String payrollRunId =
                firstNonEmpty((String) context.get("payrollRunId"),
                              (String) context.get("runId"));

        String payrollPeriodId =
                firstNonEmpty((String) context.get("payrollPeriodId"),
                              (String) context.get("periodId"));

        GenericValue userLogin = (GenericValue) context.get("userLogin");
        String userLoginId = (userLogin != null) ? userLogin.getString("userLoginId") : null;

        // Defaults (keep tolerant)
        if (UtilValidate.isEmpty(eventTypeId)) eventTypeId = "AE_INFO";
        if (UtilValidate.isEmpty(eventTypeStatusTypeId)) eventTypeStatusTypeId = "PAY_AUD_EVT";

        // Start a small TX for audit only (still non-fatal)
        beganTx = TransactionUtil.begin();

        // ---- Prevent FK violation to StatusItem (PAEV_TYPE) ----
        // If (eventTypeId, eventTypeStatusTypeId) is not in STATUS_ITEM, fall back to AE_INFO/PAY_AUD_EVT.
        if (!statusItemExists(delegator, eventTypeId, eventTypeStatusTypeId)) {
            eventTypeId = "AE_INFO";
            eventTypeStatusTypeId = "PAY_AUD_EVT";
        }

        // ---- Insert PayrollAuditEvent (never reuse acctgTransId as PK) ----
        String auditEventId = delegator.getNextSeqId("PayrollAuditEvent");

        GenericValue ev = delegator.makeValue("PayrollAuditEvent");
        ev.set("payrollAuditEventId", auditEventId);
        ev.set("organizationPartyId", organizationPartyId);
        ev.set("eventTypeId", eventTypeId);

        // If you added the field in entitymodel.xml, also set it:
        // ev.set("eventTypeStatusTypeId", eventTypeStatusTypeId);

        ev.set("entityName", entityName);
        ev.set("recordId", recordId);
        ev.set("oldValue", oldValue);
        ev.set("newValue", newValue);
        ev.set("eventDate", UtilDateTime.nowTimestamp());
        ev.set("userLoginId", userLoginId);
        ev.set("sourceIp", sourceIp);

        // New optional fields you asked to keep
        if (UtilValidate.isNotEmpty(payrollRunId)) ev.set("payrollRunId", payrollRunId);
        if (UtilValidate.isNotEmpty(payrollPeriodId)) ev.set("payrollPeriodId", payrollPeriodId);

        ev.create();

        TransactionUtil.commit(beganTx);
        success = true;
        return ServiceUtil.returnSuccess();
    } catch (Throwable t) {
        Debug.logWarning(t, "recordPayrollAuditEvent failed (non-fatal)", MODULE);
        try {
            if (beganTx && !success) TransactionUtil.rollback(beganTx, "recordPayrollAuditEvent rollback", t);
        } catch (Throwable ignored) {
        }
        // Non-fatal by design:
        return ServiceUtil.returnSuccess();
    }
}

 
    /**
     * Writes PayrollAuditEvent (preferred) if exists, else PayrollAuditLog if exists.
     * Non-fatal by design.
     */
  public static void logEvent(
        Delegator delegator,
        String organizationPartyId,
        String eventTypeId,
        String entityName,
        String recordId,
        String oldValue,
        String newValue,
        GenericValue userLogin,
        String sourceIp
) {
    try {
        if (delegator == null) return;

        // tolerant defaults (audit must never break payroll)
        final String userLoginId = (userLogin == null) ? null : userLogin.getString("userLoginId");
        final String safeEntityName = UtilValidate.isEmpty(entityName) ? "Payroll" : entityName;
        final String safeRecordId   = UtilValidate.isEmpty(recordId) ? "N/A" : recordId;

        // IMPORTANT: because PayrollAuditEvent.eventTypeId has FK to StatusItem PK,
        // you must guarantee the pair (statusId, statusTypeId) exists.
        // In your DB, audit event types are in PAY_AUD_EVT.
        final String eventTypeStatusTypeId = "PAY_AUD_EVT";

        String safeEventTypeId = eventTypeId;
        if (UtilValidate.isEmpty(safeEventTypeId)) {
            safeEventTypeId = "AE_INFO";
        }

        // ---- Prefer PayrollAuditEvent if entity exists ----
        if (delegator.getModelEntity("PayrollAuditEvent") != null) {

            // FK-safe fallback: if requested eventTypeId not found in STATUS_ITEM for PAY_AUD_EVT,
            // fall back to AE_INFO (which exists in PAY_AUD_EVT per your query).
            if (!statusItemExists(delegator, safeEventTypeId, eventTypeStatusTypeId)) {
                safeEventTypeId = "AE_INFO";
            }

            GenericValue ev = delegator.makeValue("PayrollAuditEvent");
            ev.set("payrollAuditEventId", delegator.getNextSeqId("PayrollAuditEvent"));
            ev.set("organizationPartyId", organizationPartyId);
            ev.set("eventTypeId", safeEventTypeId);
            ev.set("entityName", safeEntityName);
            ev.set("recordId", safeRecordId);
            ev.set("oldValue", oldValue);
            ev.set("newValue", newValue);
            ev.set("eventDate", UtilDateTime.nowTimestamp());
            ev.set("userLoginId", userLoginId);
            ev.set("sourceIp", sourceIp);

            // If you added these fields in entitymodel.xml and you want them here,
            // set them via a separate overload or from old/newValue parsing.
            // (This method signature doesn't receive payrollRunId/payrollPeriodId.)

            ev.create();
            return;
        }

        // ---- Fallback to PayrollAuditLog if PayrollAuditEvent doesn't exist ----
        if (delegator.getModelEntity("PayrollAuditLog") != null) {

            // For PayrollAuditLog.actionTypeId you probably want PAY_AUD_ACT,
            // but your entity relation maps actionTypeId -> StatusItem.statusId (no type),
            // so we must ensure it exists in PAY_AUD_ACT or fall back to AUD_UPD/UPDATE/AE_INFO usage.
            // Here we do a safe check against PAY_AUD_ACT; if not found, fall back to UPDATE.
            String safeActionTypeId = safeEventTypeId;
            if (!statusItemExists(delegator, safeActionTypeId, "PAY_AUD_ACT")) {
                safeActionTypeId = "UPDATE"; // present in PAY_AUD_ACT per your query
            }

            GenericValue aud = delegator.makeValue("PayrollAuditLog");
            aud.set("payrollAuditLogId", delegator.getNextSeqId("PayrollAuditLog"));
            aud.set("organizationPartyId", organizationPartyId);
            aud.set("entityName", safeEntityName);
            aud.set("recordId", safeRecordId);
            aud.set("actionTypeId", safeActionTypeId);
            aud.set("oldValuesJson", oldValue);
            aud.set("newValuesJson", newValue);
            aud.set("changedByUserLoginId", userLoginId);
            aud.set("changedDate", UtilDateTime.nowTimestamp());
            aud.set("ipAddress", sourceIp);
            aud.create();
            return;
        }

        // ---- No entity exists — just log and continue ----
        Debug.logInfo("Audit (no entity): org=" + organizationPartyId
                + " type=" + safeEventTypeId
                + " entity=" + safeEntityName
                + " recordId=" + safeRecordId
                + " user=" + userLoginId
                + " ip=" + sourceIp, MODULE);

    } catch (Exception e) {
        Debug.logWarning(e, "Payroll audit insert failed (non-fatal)", MODULE);
    }
}

/**
 * FK-safe existence check for StatusItem PK (statusId, statusTypeId).
 * This avoids Derby FK violation like you saw for (POST_GL_OK) / (RUN_POST)
 * when the FK expects the full PK.
 */
private static boolean statusItemExists(Delegator delegator, String statusId, String statusTypeId) {
    try {
        if (delegator == null) return false;
        if (UtilValidate.isEmpty(statusId) || UtilValidate.isEmpty(statusTypeId)) return false;

        GenericValue si = EntityQuery.use(delegator)
                .from("StatusItem")
                .where("statusId", statusId, "statusTypeId", statusTypeId)
                .cache(false)
                .queryOne();

        return si != null;
    } catch (Exception ignored) {
        // If check fails, return false so we fall back and avoid insert FK errors.
        return false;
    }
}

    private static String firstNonEmpty(String... vals) {
        if (vals == null) return null;
        for (String v : vals) {
            if (UtilValidate.isNotEmpty(v)) return v;
        }
        return null;
    }
}
