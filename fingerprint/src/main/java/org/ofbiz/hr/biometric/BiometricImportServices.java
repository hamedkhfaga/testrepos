package org.ofbiz.hr.biometric;

import org.apache.ofbiz.base.util.Debug;
import org.apache.ofbiz.base.util.UtilDateTime;
import org.apache.ofbiz.base.util.UtilValidate;
import org.apache.ofbiz.entity.Delegator;
import org.apache.ofbiz.entity.GenericEntityException;
import org.apache.ofbiz.entity.GenericValue;
import org.apache.ofbiz.entity.condition.EntityCondition;
import org.apache.ofbiz.entity.condition.EntityOperator;
import org.apache.ofbiz.entity.util.EntityQuery;
import org.apache.ofbiz.service.DispatchContext;
import org.apache.ofbiz.service.ServiceUtil;

import java.sql.Timestamp;
import java.util.List;
import java.util.Map;

/**
 * Biometric ingestion services (import batch, event log, processed status).
 * Designed for REST/device ingestion. Idempotency is enforced when vendorEventId is provided.
 */
public class BiometricImportServices {
    public static final String MODULE = BiometricImportServices.class.getName();

    private static String getUserLoginId(Map<String, ?> context) {
        GenericValue userLogin = (GenericValue) context.get("userLogin");
        return (userLogin != null) ? userLogin.getString("userLoginId") : null;
    }

    private static void assertOrgPresent(String organizationPartyId) {
        if (UtilValidate.isEmpty(organizationPartyId)) {
            throw new IllegalArgumentException("organizationPartyId is required");
        }
    }

    private static void assertDeviceInOrg(Delegator delegator, String organizationPartyId, String biometricDeviceId) throws GenericEntityException {
        GenericValue dev = EntityQuery.use(delegator)
                .from("BiometricDevice")
                .where("biometricDeviceId", biometricDeviceId)
                .cache(false)
                .queryOne();

        if (dev == null) {
            throw new IllegalArgumentException("BiometricDevice not found: " + biometricDeviceId);
        }
        if (!organizationPartyId.equals(dev.getString("organizationPartyId"))) {
            throw new IllegalArgumentException("BiometricDevice does not belong to organizationPartyId");
        }
    }

    public static Map<String, Object> createBiometricImportBatch(DispatchContext dctx, Map<String, ?> context) {
        Delegator delegator = dctx.getDelegator();
        try {
            String organizationPartyId = (String) context.get("organizationPartyId");
            assertOrgPresent(organizationPartyId);

            String importSourceEnumId = (String) context.get("importSourceEnumId");
            if (UtilValidate.isEmpty(importSourceEnumId)) {
                return ServiceUtil.returnError("importSourceEnumId is required");
            }

            String biometricDeviceId = (String) context.get("biometricDeviceId");
            if (UtilValidate.isNotEmpty(biometricDeviceId)) {
                assertDeviceInOrg(delegator, organizationPartyId, biometricDeviceId);
            }

            String batchId = delegator.getNextSeqId("BiometricImportBatch");
            GenericValue batch = delegator.makeValue("BiometricImportBatch");
            batch.set("biometricImportBatchId", batchId);
            batch.set("organizationPartyId", organizationPartyId);
            batch.set("biometricDeviceId", biometricDeviceId);

            batch.set("importSourceEnumId", importSourceEnumId);
            batch.set("sourceFileName", context.get("sourceFileName"));
            batch.set("sourceChecksum", context.get("sourceChecksum"));
            batch.set("importFromDateTime", context.get("importFromDateTime"));
            batch.set("importThruDateTime", context.get("importThruDateTime"));

            // Optional: initialize status
            batch.set("importStatusId", "BIO_IMPORT_CREATED"); // seed this StatusItem
            batch.set("startedDateTime", UtilDateTime.nowTimestamp());

            String userLoginId = getUserLoginId(context);
            batch.set("createdByUserLoginId", userLoginId);
            batch.set("lastModifiedByUserLoginId", userLoginId);

            batch.create();

            Map<String, Object> result = ServiceUtil.returnSuccess();
            result.put("biometricImportBatchId", batchId);
            return result;
        } catch (Exception e) {
            Debug.logError(e, "Error in createBiometricImportBatch", MODULE);
            return ServiceUtil.returnError("Error creating import batch: " + e.getMessage());
        }
    }

    public static Map<String, Object> finalizeBiometricImportBatch(DispatchContext dctx, Map<String, ?> context) {
        Delegator delegator = dctx.getDelegator();
        try {
            String organizationPartyId = (String) context.get("organizationPartyId");
            assertOrgPresent(organizationPartyId);

            String batchId = (String) context.get("biometricImportBatchId");
            String importStatusId = (String) context.get("importStatusId");
            if (UtilValidate.isEmpty(batchId) || UtilValidate.isEmpty(importStatusId)) {
                return ServiceUtil.returnError("biometricImportBatchId and importStatusId are required");
            }

            GenericValue batch = EntityQuery.use(delegator)
                    .from("BiometricImportBatch")
                    .where("biometricImportBatchId", batchId)
                    .cache(false)
                    .queryOne();

            if (batch == null) {
                return ServiceUtil.returnError("BiometricImportBatch not found: " + batchId);
            }
            if (!organizationPartyId.equals(batch.getString("organizationPartyId"))) {
                return ServiceUtil.returnError("Import batch does not belong to organizationPartyId");
            }

            batch.set("importStatusId", importStatusId);
            batch.set("recordCount", context.get("recordCount"));
            batch.set("errorCount", context.get("errorCount"));
            batch.set("errorMessage", context.get("errorMessage"));
            batch.set("completedDateTime", UtilDateTime.nowTimestamp());
            batch.set("lastModifiedByUserLoginId", getUserLoginId(context));
            batch.store();

            Map<String, Object> result = ServiceUtil.returnSuccess();
            result.put("finalized", Boolean.TRUE);
            return result;
        } catch (Exception e) {
            Debug.logError(e, "Error in finalizeBiometricImportBatch", MODULE);
            return ServiceUtil.returnError("Error finalizing batch: " + e.getMessage());
        }
    }

    public static Map<String, Object> createBiometricEventLog(DispatchContext dctx, Map<String, ?> context) {
        Delegator delegator = dctx.getDelegator();
        try {
            String organizationPartyId = (String) context.get("organizationPartyId");
            assertOrgPresent(organizationPartyId);

            String biometricDeviceId = (String) context.get("biometricDeviceId");
            if (UtilValidate.isEmpty(biometricDeviceId)) {
                return ServiceUtil.returnError("biometricDeviceId is required");
            }
            assertDeviceInOrg(delegator, organizationPartyId, biometricDeviceId);

            Timestamp eventDateTime = (Timestamp) context.get("eventDateTime");
            if (eventDateTime == null) {
                return ServiceUtil.returnError("eventDateTime is required");
            }

            String vendorEventId = (String) context.get("vendorEventId");

            // Idempotency: if vendorEventId present, enforce (org + device + vendorEventId) uniqueness at service layer.
            if (UtilValidate.isNotEmpty(vendorEventId)) {
                GenericValue existing = EntityQuery.use(delegator)
                        .from("BiometricEventLog")
                        .where("organizationPartyId", organizationPartyId,
                               "biometricDeviceId", biometricDeviceId,
                               "vendorEventId", vendorEventId)
                        .cache(false)
                        .queryFirst();

                if (existing != null) {
                    Map<String, Object> result = ServiceUtil.returnSuccess();
                    result.put("biometricEventLogId", existing.getString("biometricEventLogId"));
                    result.put("createdNew", Boolean.FALSE);
                    result.put("resolvedPartyId", existing.getString("partyId"));
                    result.put("processStatusId", existing.getString("processStatusId"));
                    return result;
                }
            }

            // Resolve partyId (direct, else by deviceUserId, else by badgeBarcode)
            String partyId = (String) context.get("partyId");
            String deviceUserId = (String) context.get("deviceUserId");
            String badgeBarcode = (String) context.get("badgeBarcode");

            String resolvedPartyId = partyId;
            if (UtilValidate.isEmpty(resolvedPartyId)) {
                resolvedPartyId = resolvePartyId(delegator, organizationPartyId, biometricDeviceId, deviceUserId, badgeBarcode, eventDateTime);
            }

            String logId = delegator.getNextSeqId("BiometricEventLog");
            GenericValue log = delegator.makeValue("BiometricEventLog");
            log.set("biometricEventLogId", logId);
            log.set("organizationPartyId", organizationPartyId);
            log.set("biometricImportBatchId", context.get("biometricImportBatchId"));
            log.set("biometricDeviceId", biometricDeviceId);

            log.set("partyId", resolvedPartyId);
            log.set("deviceUserId", deviceUserId);
            log.set("vendorEventId", vendorEventId);

            log.set("eventDateTime", eventDateTime);
            log.set("eventTypeEnumId", context.get("eventTypeEnumId"));
            log.set("punchTypeEnumId", context.get("punchTypeEnumId"));
            log.set("verifyModeEnumId", context.get("verifyModeEnumId"));
            log.set("workLocationFacilityId", context.get("workLocationFacilityId"));

            log.set("rawPayload", context.get("rawPayload"));
            log.set("externalRefId", context.get("externalRefId"));

            // Initial process status
            String initialStatus = UtilValidate.isNotEmpty(resolvedPartyId) ? "BIO_EVT_RECEIVED" : "BIO_EVT_UNRESOLVED";
            log.set("processStatusId", initialStatus);

            String userLoginId = getUserLoginId(context);
            log.set("createdByUserLoginId", userLoginId);
            log.set("lastModifiedByUserLoginId", userLoginId);

            log.create();

            Map<String, Object> result = ServiceUtil.returnSuccess();
            result.put("biometricEventLogId", logId);
            result.put("createdNew", Boolean.TRUE);
            result.put("resolvedPartyId", resolvedPartyId);
            result.put("processStatusId", initialStatus);
            return result;

        } catch (Exception e) {
            Debug.logError(e, "Error in createBiometricEventLog", MODULE);
            return ServiceUtil.returnError("Error ingesting event log: " + e.getMessage());
        }
    }

    public static Map<String, Object> markBiometricEventLogProcessed(DispatchContext dctx, Map<String, ?> context) {
        Delegator delegator = dctx.getDelegator();
        try {
            String organizationPartyId = (String) context.get("organizationPartyId");
            assertOrgPresent(organizationPartyId);

            String biometricEventLogId = (String) context.get("biometricEventLogId");
            String processStatusId = (String) context.get("processStatusId");
            if (UtilValidate.isEmpty(biometricEventLogId) || UtilValidate.isEmpty(processStatusId)) {
                return ServiceUtil.returnError("biometricEventLogId and processStatusId are required");
            }

            GenericValue log = EntityQuery.use(delegator)
                    .from("BiometricEventLog")
                    .where("biometricEventLogId", biometricEventLogId)
                    .cache(false)
                    .queryOne();

            if (log == null) {
                return ServiceUtil.returnError("BiometricEventLog not found: " + biometricEventLogId);
            }
            if (!organizationPartyId.equals(log.getString("organizationPartyId"))) {
                return ServiceUtil.returnError("Event does not belong to organizationPartyId");
            }

            log.set("processStatusId", processStatusId);
            if (context.get("errorMessage") != null) {
                log.set("errorMessage", context.get("errorMessage"));
            }
            log.set("lastModifiedByUserLoginId", getUserLoginId(context));
            log.store();

            Map<String, Object> result = ServiceUtil.returnSuccess();
            result.put("updated", Boolean.TRUE);
            return result;

        } catch (Exception e) {
            Debug.logError(e, "Error in markBiometricEventLogProcessed", MODULE);
            return ServiceUtil.returnError("Error updating event status: " + e.getMessage());
        }
    }

    /**
     * Resolve partyId using active BiometricDeviceUser mappings.
     * Priority:
     * - deviceUserId within same device
     * - badgeBarcode within organization (device-agnostic); if multiple, pick latest fromDate
     */
    private static String resolvePartyId(Delegator delegator,
                                        String organizationPartyId,
                                        String biometricDeviceId,
                                        String deviceUserId,
                                        String badgeBarcode,
                                        Timestamp eventDateTime) throws GenericEntityException {

        Timestamp now = (eventDateTime != null) ? eventDateTime : UtilDateTime.nowTimestamp();

        // 1) Resolve by deviceUserId (device-specific)
        if (UtilValidate.isNotEmpty(deviceUserId)) {
            EntityCondition active = EntityCondition.makeCondition(EntityOperator.AND,
                    EntityCondition.makeCondition("organizationPartyId", organizationPartyId),
                    EntityCondition.makeCondition("biometricDeviceId", biometricDeviceId),
                    EntityCondition.makeCondition("deviceUserId", deviceUserId),
                    EntityCondition.makeCondition("fromDate", EntityOperator.LESS_THAN_EQUAL_TO, now),
                    EntityCondition.makeCondition(EntityOperator.OR,
                            EntityCondition.makeCondition("thruDate", EntityOperator.EQUALS, null),
                            EntityCondition.makeCondition("thruDate", EntityOperator.GREATER_THAN, now)));

            GenericValue map = EntityQuery.use(delegator)
                    .from("BiometricDeviceUser")
                    .where(active)
                    .orderBy("-fromDate")
                    .cache(false)
                    .queryFirst();

            if (map != null) {
                return map.getString("partyId");
            }
        }

        // 2) Resolve by badgeBarcode (org-wide)
        if (UtilValidate.isNotEmpty(badgeBarcode)) {
            EntityCondition active = EntityCondition.makeCondition(EntityOperator.AND,
                    EntityCondition.makeCondition("organizationPartyId", organizationPartyId),
                    EntityCondition.makeCondition("badgeBarcode", badgeBarcode),
                    EntityCondition.makeCondition("fromDate", EntityOperator.LESS_THAN_EQUAL_TO, now),
                    EntityCondition.makeCondition(EntityOperator.OR,
                            EntityCondition.makeCondition("thruDate", EntityOperator.EQUALS, null),
                            EntityCondition.makeCondition("thruDate", EntityOperator.GREATER_THAN, now)));

            List<GenericValue> maps = EntityQuery.use(delegator)
                    .from("BiometricDeviceUser")
                    .where(active)
                    .orderBy("-fromDate")
                    .cache(false)
                    .queryList();

            if (UtilValidate.isNotEmpty(maps)) {
                return maps.get(0).getString("partyId");
            }
        }

        return null;
    }
}
