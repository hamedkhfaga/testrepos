/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */
package org.apache.ofbiz.qrcore;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.sql.Timestamp;
import java.util.Base64;
import java.util.HashMap;
import java.util.Map;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

import org.apache.ofbiz.base.util.Debug;
import org.apache.ofbiz.base.util.UtilDateTime;
import org.apache.ofbiz.base.util.UtilProperties;
import org.apache.ofbiz.base.util.UtilValidate;
import org.apache.ofbiz.entity.Delegator;
import org.apache.ofbiz.entity.GenericValue;
import org.apache.ofbiz.entity.util.EntityQuery;
import org.apache.ofbiz.service.DispatchContext;
import org.apache.ofbiz.service.ServiceUtil;

/**
 * QrCore — central secured QR services for all OFBiz and custom applications.
 * Token format: QR1|docType|docId|org|qrCodeId|issuedAtMillis|hmacBase64Url
 * The HMAC-SHA256 secret lives in config/qrcore.properties (server-side only);
 * the database stores only a SHA-256 hash of the token.
 */
public final class QrCoreServices {

    private static final String MODULE = QrCoreServices.class.getName();
    private static final String RESOURCE = "qrcore";
    private static final String VERSION = "QR1";
    private static final char SEP = '|';

    private QrCoreServices() { }

    private static String secret() {
        String s = UtilProperties.getPropertyValue(RESOURCE, "qrcore.secret");
        if (UtilValidate.isEmpty(s) || "CHANGE-ME".equals(s)) {
            Debug.logWarning("qrcore.secret is not set to a strong value — "
                    + "set it in plugins/qrcore/config/qrcore.properties", MODULE);
        }
        return UtilValidate.isEmpty(s) ? "CHANGE-ME" : s;
    }

    private static String b64(byte[] bytes) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    private static String hmac(String payload) throws Exception {
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(secret().getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
        return b64(mac.doFinal(payload.getBytes(StandardCharsets.UTF_8)));
    }

    private static String sha256(String value) throws Exception {
        return b64(MessageDigest.getInstance("SHA-256")
                .digest(value.getBytes(StandardCharsets.UTF_8)));
    }

    private static String buildToken(String docType, String docId, String org,
            String qrCodeId, long issuedAt) throws Exception {
        String payload = VERSION + SEP + docType + SEP + docId + SEP
                + (org == null ? "" : org) + SEP + qrCodeId + SEP + issuedAt;
        return payload + SEP + hmac(payload);
    }

    /** qrcoreGenerate */
    public static Map<String, Object> generate(DispatchContext dctx, Map<String, ?> context) {
        Delegator delegator = dctx.getDelegator();
        String docType = (String) context.get("docType");
        String docId = (String) context.get("docId");
        String org = (String) context.get("organizationPartyId");
        String channel = UtilValidate.isEmpty((String) context.get("channel"))
                ? "DOCUMENT" : (String) context.get("channel");
        GenericValue userLogin = (GenericValue) context.get("userLogin");
        try {
            GenericValue qr = EntityQuery.use(delegator).from("QrCode")
                    .where("docType", docType, "docId", docId, "channel", channel)
                    .queryFirst();
            if (qr == null) {
                String qrCodeId = delegator.getNextSeqId("QrCode");
                long issuedAt = System.currentTimeMillis();
                String token = buildToken(docType, docId, org, qrCodeId, issuedAt);
                qr = delegator.makeValue("QrCode");
                qr.set("qrCodeId", qrCodeId);
                qr.set("docType", docType);
                qr.set("docId", docId);
                qr.set("organizationPartyId", org);
                qr.set("channel", channel);
                qr.set("statusId", "QRC_ACTIVE");
                qr.set("tokenHash", sha256(token));
                qr.set("issuedAtMillis", issuedAt);
                qr.set("expireAt", context.get("expireAt"));
                qr.set("targetUrl", context.get("targetUrl"));
                qr.set("issuedByUserLogin", userLogin != null
                        ? userLogin.getString("userLoginId") : null);
                qr.set("scanCount", 0L);
                qr.create();
            }
            long issuedAt = qr.getLong("issuedAtMillis");
            String token = buildToken(qr.getString("docType"), qr.getString("docId"),
                    qr.getString("organizationPartyId"), qr.getString("qrCodeId"), issuedAt);
            Map<String, Object> result = ServiceUtil.returnSuccess();
            result.put("qrCodeId", qr.getString("qrCodeId"));
            result.put("token", token);
            result.put("statusId", qr.getString("statusId"));
            return result;
        } catch (Exception e) {
            Debug.logError(e, MODULE);
            return ServiceUtil.returnError("qrcoreGenerate failed: " + e.getMessage());
        }
    }

    /** qrcoreVerify */
    public static Map<String, Object> verify(DispatchContext dctx, Map<String, ?> context) {
        Delegator delegator = dctx.getDelegator();
        String token = (String) context.get("token");
        GenericValue userLogin = (GenericValue) context.get("userLogin");
        String result = "INVALID_SIGNATURE";
        GenericValue qr = null;
        try {
            String[] parts = token == null ? new String[0] : token.split("\\|");
            if (parts.length == 7 && VERSION.equals(parts[0])) {
                String payload = String.join("|", parts[0], parts[1], parts[2],
                        parts[3], parts[4], parts[5]);
                boolean sigOk = MessageDigest.isEqual(
                        hmac(payload).getBytes(StandardCharsets.UTF_8),
                        parts[6].getBytes(StandardCharsets.UTF_8));
                if (sigOk) {
                    qr = EntityQuery.use(delegator).from("QrCode")
                            .where("qrCodeId", parts[4]).queryOne();
                    if (qr == null || !sha256(token).equals(qr.getString("tokenHash"))) {
                        result = "UNKNOWN";
                        qr = null;
                    } else if ("QRC_REVOKED".equals(qr.getString("statusId"))) {
                        result = "REVOKED";
                    } else {
                        Timestamp expireAt = qr.getTimestamp("expireAt");
                        if (expireAt != null && expireAt.before(UtilDateTime.nowTimestamp())) {
                            result = "EXPIRED";
                        } else {
                            result = "VALID";
                        }
                    }
                }
            }
            // record every attempt — the tracking trail for customer/B2B scans
            GenericValue ev = delegator.makeValue("QrScanEvent");
            ev.set("scanEventId", delegator.getNextSeqId("QrScanEvent"));
            ev.set("qrCodeId", qr != null ? qr.getString("qrCodeId") : null);
            ev.set("scanDate", UtilDateTime.nowTimestamp());
            ev.set("result", result);
            ev.set("scannedByUserLogin", userLogin != null
                    ? userLogin.getString("userLoginId") : null);
            ev.set("sourceIp", context.get("sourceIp"));
            ev.set("userAgent", context.get("userAgent"));
            ev.create();
            if (qr != null) {
                Long count = qr.getLong("scanCount");
                qr.set("scanCount", (count == null ? 0L : count) + 1L);
                qr.set("lastScanDate", UtilDateTime.nowTimestamp());
                qr.store();
            }
            Map<String, Object> out = ServiceUtil.returnSuccess();
            out.put("valid", "VALID".equals(result));
            out.put("result", result);
            if (qr != null) {
                out.put("qrCodeId", qr.getString("qrCodeId"));
                out.put("docType", qr.getString("docType"));
                out.put("docId", qr.getString("docId"));
                out.put("organizationPartyId", qr.getString("organizationPartyId"));
                out.put("channel", qr.getString("channel"));
                out.put("targetUrl", qr.getString("targetUrl"));
            }
            return out;
        } catch (Exception e) {
            Debug.logError(e, MODULE);
            return ServiceUtil.returnError("qrcoreVerify failed: " + e.getMessage());
        }
    }

    private static Map<String, Object> setStatus(DispatchContext dctx,
            Map<String, ?> context, String statusId) {
        try {
            GenericValue qr = EntityQuery.use(dctx.getDelegator()).from("QrCode")
                    .where("qrCodeId", context.get("qrCodeId")).queryOne();
            if (qr == null) {
                return ServiceUtil.returnError("QR code not found: " + context.get("qrCodeId"));
            }
            qr.set("statusId", statusId);
            qr.store();
            Map<String, Object> out = ServiceUtil.returnSuccess();
            out.put("statusId", statusId);
            return out;
        } catch (Exception e) {
            Debug.logError(e, MODULE);
            return ServiceUtil.returnError("status change failed: " + e.getMessage());
        }
    }

    /** qrcoreRevoke */
    public static Map<String, Object> revoke(DispatchContext dctx, Map<String, ?> context) {
        return setStatus(dctx, context, "QRC_REVOKED");
    }

    /** qrcoreReactivate */
    public static Map<String, Object> reactivate(DispatchContext dctx, Map<String, ?> context) {
        return setStatus(dctx, context, "QRC_ACTIVE");
    }
}
