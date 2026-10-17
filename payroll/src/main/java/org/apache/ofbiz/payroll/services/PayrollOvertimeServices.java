package org.apache.ofbiz.payroll.services;

import org.apache.ofbiz.base.util.Debug;
import org.apache.ofbiz.base.util.UtilDateTime;
import org.apache.ofbiz.base.util.UtilMisc;
import org.apache.ofbiz.base.util.UtilValidate;

import org.apache.ofbiz.entity.GenericValue;
import org.apache.ofbiz.service.DispatchContext;
import org.apache.ofbiz.service.ServiceUtil;

import java.sql.Timestamp;
import java.util.HashMap;
import java.util.Map;

/**
 * Phase #1 - Overtime request lifecycle (create -> submit -> approve/reject).
 * Uses PayrollCrudServices (payrollCreateEntity/payrollUpdateEntity) safely.
 */
public class PayrollOvertimeServices {
    public static final String MODULE = PayrollOvertimeServices.class.getName();

    // TODO: replace with your DB StatusItem IDs once your status data is loaded.
    // Keep these as placeholders only.
    private static final String STATUS_DRAFT = "OT_DRAFT";
    private static final String STATUS_SUBM  = "OT_SUBM";
    private static final String STATUS_APPR  = "OT_APPR";
    private static final String STATUS_REJ   = "OT_REJ";

    private static final String ENTITY_OT_REQ = "EmployeeOvertimeRequest";
    private static final String PK_FIELD      = "employeeOvertimeRequestId"; // adjust if your PK field differs

    public static Map<String, Object> createEmployeeOvertimeRequestV2(DispatchContext dctx, Map<String, ?> context) {
        GenericValue userLogin = (GenericValue) context.get("userLogin");

        String organizationPartyId = (String) context.get("organizationPartyId");
        String employeePartyId = (String) context.get("employeePartyId");
        String payrollPeriodId = (String) context.get("payrollPeriodId");
        Timestamp fromDate = (Timestamp) context.get("fromDate");
        Timestamp thruDate = (Timestamp) context.get("thruDate");

        try {
            if (UtilValidate.isEmpty(organizationPartyId)
                    || UtilValidate.isEmpty(employeePartyId)
                    || fromDate == null) {
                return ServiceUtil.returnError("organizationPartyId, employeePartyId, fromDate are required.");
            }

            // Build fields - MUST match entitymodel field names.
            Map<String, Object> fields = new HashMap<>();
            fields.put("employeePartyId", employeePartyId);
            if (UtilValidate.isNotEmpty(payrollPeriodId)) fields.put("payrollPeriodId", payrollPeriodId);
            fields.put("fromDate", fromDate);
            if (thruDate != null) fields.put("thruDate", thruDate);

            fields.put("overtimeHours", context.get("overtimeHours"));
            fields.put("overtimeRate", context.get("overtimeRate"));
            fields.put("overtimeAmount", context.get("overtimeAmount"));
            fields.put("reason", context.get("reason"));
            fields.put("comments", context.get("comments"));

            // lifecycle
            fields.put("statusId", STATUS_DRAFT);

            // optional audit fields (only if exist in model; PayrollCrudServices will ignore unknown fields)
            fields.put("createdDate", UtilDateTime.nowTimestamp());
            fields.put("createdByUserLoginId", userLogin.getString("userLoginId"));

            Map<String, Object> crudCtx = UtilMisc.toMap(
                    "entityName", ENTITY_OT_REQ,
                    "organizationPartyId", organizationPartyId,
                    "fields", fields,
                    "userLogin", userLogin
            );

            Map<String, Object> crudRes = dctx.getDispatcher().runSync("payrollCreateEntity", crudCtx);
            if (ServiceUtil.isError(crudRes)) return crudRes;

            String reqId = (String) crudRes.get("resultId"); // <-- your real CRUD output

            Map<String, Object> out = ServiceUtil.returnSuccess();
            out.put("employeeOvertimeRequestId", reqId);
            out.put("statusId", STATUS_DRAFT);
            return out;

        } catch (Exception e) {
            Debug.logError(e, "createEmployeeOvertimeRequestV2 failed", MODULE);
            return ServiceUtil.returnError("createEmployeeOvertimeRequestV2 failed: " + e.getMessage());
        }
    }

    public static Map<String, Object> submitEmployeeOvertimeRequestV2(DispatchContext dctx, Map<String, ?> context) {
        GenericValue userLogin = (GenericValue) context.get("userLogin");

        String organizationPartyId = (String) context.get("organizationPartyId");
        String reqId = (String) context.get("employeeOvertimeRequestId");

        try {
            if (UtilValidate.isEmpty(organizationPartyId) || UtilValidate.isEmpty(reqId)) {
                return ServiceUtil.returnError("organizationPartyId and employeeOvertimeRequestId are required.");
            }

            Map<String, Object> pk = UtilMisc.toMap(PK_FIELD, reqId);

            Map<String, Object> fields = new HashMap<>();
            fields.put("statusId", STATUS_SUBM);
            fields.put("comments", context.get("comments"));
            fields.put("lastUpdatedDate", UtilDateTime.nowTimestamp());
            fields.put("lastUpdatedByUserLoginId", userLogin.getString("userLoginId"));

            Map<String, Object> crudCtx = UtilMisc.toMap(
                    "entityName", ENTITY_OT_REQ,
                    "organizationPartyId", organizationPartyId,
                    "pk", pk,
                    "fields", fields,
                    "userLogin", userLogin
            );

            Map<String, Object> crudRes = dctx.getDispatcher().runSync("payrollUpdateEntity", crudCtx);
            if (ServiceUtil.isError(crudRes)) return crudRes;

            Map<String, Object> out = ServiceUtil.returnSuccess();
            out.put("statusId", STATUS_SUBM);
            return out;

        } catch (Exception e) {
            Debug.logError(e, "submitEmployeeOvertimeRequestV2 failed", MODULE);
            return ServiceUtil.returnError("submitEmployeeOvertimeRequestV2 failed: " + e.getMessage());
        }
    }

    public static Map<String, Object> approveEmployeeOvertimeRequestV2(DispatchContext dctx, Map<String, ?> context) {
        GenericValue userLogin = (GenericValue) context.get("userLogin");

        String organizationPartyId = (String) context.get("organizationPartyId");
        String reqId = (String) context.get("employeeOvertimeRequestId");

        try {
            if (UtilValidate.isEmpty(organizationPartyId) || UtilValidate.isEmpty(reqId)) {
                return ServiceUtil.returnError("organizationPartyId and employeeOvertimeRequestId are required.");
            }

            Map<String, Object> pk = UtilMisc.toMap(PK_FIELD, reqId);

            Map<String, Object> fields = new HashMap<>();
            fields.put("statusId", STATUS_APPR);
            fields.put("approvalComments", context.get("approvalComments"));
            fields.put("approvedDate", UtilDateTime.nowTimestamp());
            fields.put("approvedByUserLoginId", userLogin.getString("userLoginId"));

            Map<String, Object> crudCtx = UtilMisc.toMap(
                    "entityName", ENTITY_OT_REQ,
                    "organizationPartyId", organizationPartyId,
                    "pk", pk,
                    "fields", fields,
                    "userLogin", userLogin
            );

            Map<String, Object> crudRes = dctx.getDispatcher().runSync("payrollUpdateEntity", crudCtx);
            if (ServiceUtil.isError(crudRes)) return crudRes;

            Map<String, Object> out = ServiceUtil.returnSuccess();
            out.put("statusId", STATUS_APPR);
            return out;

        } catch (Exception e) {
            Debug.logError(e, "approveEmployeeOvertimeRequestV2 failed", MODULE);
            return ServiceUtil.returnError("approveEmployeeOvertimeRequestV2 failed: " + e.getMessage());
        }
    }

    public static Map<String, Object> rejectEmployeeOvertimeRequestV2(DispatchContext dctx, Map<String, ?> context) {
        GenericValue userLogin = (GenericValue) context.get("userLogin");

        String organizationPartyId = (String) context.get("organizationPartyId");
        String reqId = (String) context.get("employeeOvertimeRequestId");

        try {
            if (UtilValidate.isEmpty(organizationPartyId) || UtilValidate.isEmpty(reqId)) {
                return ServiceUtil.returnError("organizationPartyId and employeeOvertimeRequestId are required.");
            }

            Map<String, Object> pk = UtilMisc.toMap(PK_FIELD, reqId);

            Map<String, Object> fields = new HashMap<>();
            fields.put("statusId", STATUS_REJ);
            fields.put("rejectionReason", context.get("rejectionReason"));
            fields.put("rejectedDate", UtilDateTime.nowTimestamp());
            fields.put("rejectedByUserLoginId", userLogin.getString("userLoginId"));

            Map<String, Object> crudCtx = UtilMisc.toMap(
                    "entityName", ENTITY_OT_REQ,
                    "organizationPartyId", organizationPartyId,
                    "pk", pk,
                    "fields", fields,
                    "userLogin", userLogin
            );

            Map<String, Object> crudRes = dctx.getDispatcher().runSync("payrollUpdateEntity", crudCtx);
            if (ServiceUtil.isError(crudRes)) return crudRes;

            Map<String, Object> out = ServiceUtil.returnSuccess();
            out.put("statusId", STATUS_REJ);
            return out;

        } catch (Exception e) {
            Debug.logError(e, "rejectEmployeeOvertimeRequestV2 failed", MODULE);
            return ServiceUtil.returnError("rejectEmployeeOvertimeRequestV2 failed: " + e.getMessage());
        }
    }
}