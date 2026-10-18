package org.apache.ofbiz.payroll.util;

import org.apache.ofbiz.base.util.*;
import org.apache.ofbiz.entity.GenericValue;
import org.apache.ofbiz.service.*;

import javax.servlet.http.HttpServletRequest;
import java.util.Map;

public class PayrollAuditUtil {

    private PayrollAuditUtil() {}

    /** Safely serialize a few fields into a readable string (avoid huge blobs). */
    public static String snapshot(GenericValue gv, String... fields) {
        if (gv == null) return null;
        StringBuilder sb = new StringBuilder("{");
        for (int i = 0; i < fields.length; i++) {
            String f = fields[i];
            Object v = gv.get(f);
            sb.append(f).append("=").append(String.valueOf(v));
            if (i < fields.length - 1) sb.append(", ");
        }
        sb.append("}");
        return sb.toString();
    }

    /** Service-side audit (no request). */
    public static void audit(LocalDispatcher dispatcher,
                             GenericValue userLogin,
                             String organizationPartyId,
                             String eventTypeId,
                             String entityName,
                             String recordId,
                             String oldValue,
                             String newValue,
                             String sourceIp) {
        try {
            Map<String, Object> ctx = UtilMisc.toMap(
                    "organizationPartyId", organizationPartyId,
                    "eventTypeId", eventTypeId,
                    "entityName", entityName,
                    "recordId", recordId,
                    "oldValue", oldValue,
                    "newValue", newValue,
                    "sourceIp", sourceIp,
                    "userLogin", userLogin
            );
            dispatcher.runSync("recordPayrollAuditEvent", ctx);
        } catch (Exception ignore) {
            // Audit must never break payroll processing
        }
    }

    /** Web event audit (captures IP from request). */
    public static void auditFromRequest(LocalDispatcher dispatcher,
                                        GenericValue userLogin,
                                        HttpServletRequest request,
                                        String organizationPartyId,
                                        String eventTypeId,
                                        String entityName,
                                        String recordId,
                                        String oldValue,
                                        String newValue) {
        String ip = null;
        try { ip = request.getRemoteAddr(); } catch (Exception ignored) {}
        audit(dispatcher, userLogin, organizationPartyId, eventTypeId, entityName, recordId, oldValue, newValue, ip);
    }
}
