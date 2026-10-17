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
 * Verification audit services.
 * This does NOT perform biometric matching (that is device/vendor responsibility).
 * It records verification attempts (success/fail) for audit, security, and compliance.
 */
public class BiometricVerificationServices {
    public static final String MODULE = BiometricVerificationServices.class.getName();

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
        if (UtilValidate.isEmpty(biometricDeviceId)) {
            return;
        }
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

    public static Map<String, Object> logBiometricVerificationAttempt(DispatchContext dctx, Map<String, ?> context) {
        Delegator delegator = dctx.getDelegator();
        try {
            String organizationPartyId = (String) context.get("organizationPartyId");
            assertOrgPresent(organizationPartyId);

            String biometricDeviceId = (String) context.get("biometricDeviceId");
            assertDeviceInOrg(delegator, organizationPartyId, biometricDeviceId);

            String resultStatusId = (String) context.get("resultStatusId");
            if (UtilValidate.isEmpty(resultStatusId)) {
                return ServiceUtil.returnError("resultStatusId is required");
            }

            Timestamp attemptDateTime = (Timestamp) context.get("attemptDateTime");
            if (attemptDateTime == null) {
                attemptDateTime = UtilDateTime.nowTimestamp();
            }

            String attemptId = delegator.getNextSeqId("BiometricVerificationAttempt");
            GenericValue attempt = delegator.makeValue("BiometricVerificationAttempt");
            attempt.set("biometricVerificationAttemptId", attemptId);
            attempt.set("organizationPartyId", organizationPartyId);

            attempt.set("biometricDeviceId", biometricDeviceId);
            attempt.set("partyId", context.get("partyId"));
            attempt.set("partyBiometricId", context.get("partyBiometricId"));

            attempt.set("attemptDateTime", attemptDateTime);
            attempt.set("verifyModeEnumId", context.get("verifyModeEnumId"));
            attempt.set("resultStatusId", resultStatusId);
            attempt.set("matchScore", context.get("matchScore"));
            attempt.set("rawPayload", context.get("rawPayload"));
            attempt.set("externalRefId", context.get("externalRefId"));

            // Audit stamps are automatic in OFBiz, but keep userLogin link if needed later
            attempt.set("createdStamp", UtilDateTime.nowTimestamp());
            attempt.set("createdTxStamp", UtilDateTime.nowTimestamp());
            attempt.set("lastUpdatedStamp", UtilDateTime.nowTimestamp());
            attempt.set("lastUpdatedTxStamp", UtilDateTime.nowTimestamp());

            attempt.create();

            Map<String, Object> result = ServiceUtil.returnSuccess();
            result.put("biometricVerificationAttemptId", attemptId);
            return result;

        } catch (Exception e) {
            Debug.logError(e, "Error in logBiometricVerificationAttempt", MODULE);
            return ServiceUtil.returnError("Error logging verification attempt: " + e.getMessage());
        }
    }
}
