package org.ofbiz.hr.biometric;

import org.apache.ofbiz.base.util.Debug;
import org.apache.ofbiz.base.util.UtilDateTime;
import org.apache.ofbiz.base.util.UtilValidate;
import org.apache.ofbiz.entity.Delegator;
import org.apache.ofbiz.entity.GenericEntityException;
import org.apache.ofbiz.entity.GenericValue;
import org.apache.ofbiz.entity.util.EntityQuery;
import org.apache.ofbiz.service.DispatchContext;
import org.apache.ofbiz.service.ServiceUtil;

import java.sql.Timestamp;
import java.util.Map;

/**
 * Biometric master data services (device, device-user mapping, template store, credential, enrollment).
 *
 * Enterprise rules enforced here:
 * - Multi-org segregation via organizationPartyId
 * - Basic validation and safe defaults
 * - No REST-specific concerns here (ingest is in BiometricImportServices)
 */
public class BiometricServices {
    public static final String MODULE = BiometricServices.class.getName();

    private static String getUserLoginId(Map<String, ?> context) {
        GenericValue userLogin = (GenericValue) context.get("userLogin");
        return (userLogin != null) ? userLogin.getString("userLoginId") : null;
    }

    private static void assertOrgPresent(String organizationPartyId) throws IllegalArgumentException {
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
        String devOrg = dev.getString("organizationPartyId");
        if (!organizationPartyId.equals(devOrg)) {
            throw new IllegalArgumentException("BiometricDevice does not belong to organizationPartyId");
        }
    }

    public static Map<String, Object> createBiometricDevice(DispatchContext dctx, Map<String, ?> context) {
        Delegator delegator = dctx.getDelegator();
        try {
            String organizationPartyId = (String) context.get("organizationPartyId");
            assertOrgPresent(organizationPartyId);

            String deviceName = (String) context.get("deviceName");
            if (UtilValidate.isEmpty(deviceName)) {
                return ServiceUtil.returnError("deviceName is required");
            }

            String biometricDeviceId = delegator.getNextSeqId("BiometricDevice");
            GenericValue dev = delegator.makeValue("BiometricDevice");
            dev.set("biometricDeviceId", biometricDeviceId);
            dev.set("organizationPartyId", organizationPartyId);
            dev.set("deviceName", deviceName);

            dev.set("serialNumber", context.get("serialNumber"));
            dev.set("vendorName", context.get("vendorName"));
            dev.set("modelName", context.get("modelName"));
            dev.set("deviceTypeEnumId", context.get("deviceTypeEnumId"));
            dev.set("ipAddress", context.get("ipAddress"));
            dev.set("port", context.get("port"));
            dev.set("timeZoneId", context.get("timeZoneId"));
            dev.set("facilityId", context.get("facilityId"));
            dev.set("geoPointId", context.get("geoPointId"));
            dev.set("externalSystemRef", context.get("externalSystemRef"));
            dev.set("statusId", context.get("statusId"));
            dev.set("fromDate", context.get("fromDate"));
            dev.set("thruDate", context.get("thruDate"));

            String userLoginId = getUserLoginId(context);
            dev.set("createdByUserLoginId", userLoginId);
            dev.set("lastModifiedByUserLoginId", userLoginId);

            dev.create();

            Map<String, Object> result = ServiceUtil.returnSuccess();
            result.put("biometricDeviceId", biometricDeviceId);
            return result;
        } catch (Exception e) {
            Debug.logError(e, "Error in createBiometricDevice", MODULE);
            return ServiceUtil.returnError("Error creating biometric device: " + e.getMessage());
        }
    }

    public static Map<String, Object> updateBiometricDevice(DispatchContext dctx, Map<String, ?> context) {
        Delegator delegator = dctx.getDelegator();
        try {
            String organizationPartyId = (String) context.get("organizationPartyId");
            assertOrgPresent(organizationPartyId);

            String biometricDeviceId = (String) context.get("biometricDeviceId");
            if (UtilValidate.isEmpty(biometricDeviceId)) {
                return ServiceUtil.returnError("biometricDeviceId is required");
            }

            GenericValue dev = EntityQuery.use(delegator)
                    .from("BiometricDevice")
                    .where("biometricDeviceId", biometricDeviceId)
                    .cache(false)
                    .queryOne();

            if (dev == null) {
                return ServiceUtil.returnError("BiometricDevice not found: " + biometricDeviceId);
            }
            if (!organizationPartyId.equals(dev.getString("organizationPartyId"))) {
                return ServiceUtil.returnError("BiometricDevice does not belong to organizationPartyId");
            }

            // Only set non-null fields (partial update)
            setIfPresent(dev, "deviceName", context.get("deviceName"));
            setIfPresent(dev, "serialNumber", context.get("serialNumber"));
            setIfPresent(dev, "vendorName", context.get("vendorName"));
            setIfPresent(dev, "modelName", context.get("modelName"));
            setIfPresent(dev, "deviceTypeEnumId", context.get("deviceTypeEnumId"));
            setIfPresent(dev, "ipAddress", context.get("ipAddress"));
            setIfPresent(dev, "port", context.get("port"));
            setIfPresent(dev, "timeZoneId", context.get("timeZoneId"));
            setIfPresent(dev, "facilityId", context.get("facilityId"));
            setIfPresent(dev, "geoPointId", context.get("geoPointId"));
            setIfPresent(dev, "externalSystemRef", context.get("externalSystemRef"));
            setIfPresent(dev, "statusId", context.get("statusId"));
            setIfPresent(dev, "fromDate", context.get("fromDate"));
            setIfPresent(dev, "thruDate", context.get("thruDate"));

            dev.set("lastModifiedByUserLoginId", getUserLoginId(context));
            dev.store();

            Map<String, Object> result = ServiceUtil.returnSuccess();
            result.put("updated", Boolean.TRUE);
            return result;
        } catch (Exception e) {
            Debug.logError(e, "Error in updateBiometricDevice", MODULE);
            return ServiceUtil.returnError("Error updating biometric device: " + e.getMessage());
        }
    }

    public static Map<String, Object> createBiometricDeviceUser(DispatchContext dctx, Map<String, ?> context) {
        Delegator delegator = dctx.getDelegator();
        try {
            String organizationPartyId = (String) context.get("organizationPartyId");
            assertOrgPresent(organizationPartyId);

            String biometricDeviceId = (String) context.get("biometricDeviceId");
            String partyId = (String) context.get("partyId");
            String deviceUserId = (String) context.get("deviceUserId");

            if (UtilValidate.isEmpty(biometricDeviceId) || UtilValidate.isEmpty(partyId) || UtilValidate.isEmpty(deviceUserId)) {
                return ServiceUtil.returnError("biometricDeviceId, partyId, and deviceUserId are required");
            }

            assertDeviceInOrg(delegator, organizationPartyId, biometricDeviceId);

            Timestamp fromDate = (Timestamp) context.get("fromDate");
            if (fromDate == null) {
                fromDate = UtilDateTime.nowTimestamp();
            }

            // Idempotent for same PK: return created=false, but update optional fields (safe upsert)
            GenericValue existing = EntityQuery.use(delegator)
                    .from("BiometricDeviceUser")
                    .where("biometricDeviceId", biometricDeviceId, "partyId", partyId, "fromDate", fromDate)
                    .cache(false)
                    .queryOne();

            boolean created;
            GenericValue map;
            if (existing != null) {
                map = existing;
                created = false;
            } else {
                map = delegator.makeValue("BiometricDeviceUser");
                map.set("biometricDeviceId", biometricDeviceId);
                map.set("partyId", partyId);
                map.set("fromDate", fromDate);
                created = true;
            }

            map.set("organizationPartyId", organizationPartyId);
            map.set("deviceUserId", deviceUserId);

            setIfPresent(map, "cardNumber", context.get("cardNumber"));
            setIfPresent(map, "badgeId", context.get("badgeId"));
            setIfPresent(map, "badgeBarcode", context.get("badgeBarcode"));
            setIfPresent(map, "badgeBarcodeTypeEnumId", context.get("badgeBarcodeTypeEnumId"));
            setIfPresent(map, "deviceUserStatusId", context.get("deviceUserStatusId"));
            setIfPresent(map, "thruDate", context.get("thruDate"));
            setIfPresent(map, "externalRefId", context.get("externalRefId"));

            String userLoginId = getUserLoginId(context);
            if (created) {
                map.set("createdByUserLoginId", userLoginId);
            }
            map.set("lastModifiedByUserLoginId", userLoginId);

            if (created) {
                map.create();
            } else {
                map.store();
            }

            Map<String, Object> result = ServiceUtil.returnSuccess();
            result.put("created", created);
            result.put("effectiveFromDate", fromDate);
            return result;

        } catch (Exception e) {
            Debug.logError(e, "Error in createBiometricDeviceUser", MODULE);
            return ServiceUtil.returnError("Error creating device user mapping: " + e.getMessage());
        }
    }

    public static Map<String, Object> expireBiometricDeviceUser(DispatchContext dctx, Map<String, ?> context) {
        Delegator delegator = dctx.getDelegator();
        try {
            String organizationPartyId = (String) context.get("organizationPartyId");
            assertOrgPresent(organizationPartyId);

            String biometricDeviceId = (String) context.get("biometricDeviceId");
            String partyId = (String) context.get("partyId");
            Timestamp fromDate = (Timestamp) context.get("fromDate");
            Timestamp thruDate = (Timestamp) context.get("thruDate");

            if (UtilValidate.isEmpty(biometricDeviceId) || UtilValidate.isEmpty(partyId) || fromDate == null) {
                return ServiceUtil.returnError("biometricDeviceId, partyId, and fromDate are required");
            }

            GenericValue map = EntityQuery.use(delegator)
                    .from("BiometricDeviceUser")
                    .where("biometricDeviceId", biometricDeviceId, "partyId", partyId, "fromDate", fromDate)
                    .cache(false)
                    .queryOne();

            if (map == null) {
                return ServiceUtil.returnError("BiometricDeviceUser mapping not found (biometricDeviceId, partyId, fromDate)");
            }
            if (!organizationPartyId.equals(map.getString("organizationPartyId"))) {
                return ServiceUtil.returnError("Mapping does not belong to organizationPartyId");
            }

            if (thruDate == null) {
                thruDate = UtilDateTime.nowTimestamp();
            }

            map.set("thruDate", thruDate);
            map.set("lastModifiedByUserLoginId", getUserLoginId(context));
            map.store();

            Map<String, Object> result = ServiceUtil.returnSuccess();
            result.put("expired", Boolean.TRUE);
            return result;
        } catch (Exception e) {
            Debug.logError(e, "Error in expireBiometricDeviceUser", MODULE);
            return ServiceUtil.returnError("Error expiring mapping: " + e.getMessage());
        }
    }

    // Internal helper: field assignment only if non-null
    private static void setIfPresent(GenericValue gv, String field, Object value) {
        if (value != null) {
            gv.set(field, value);
        }
    }

    /* -----------------------------------------------------------------------
     * NOTE:
     * storeBiometricTemplate, createPartyBiometricCredential, enrollPartyBiometric
     * can be implemented next; you requested only core services for now.
     * ----------------------------------------------------------------------- */
}
