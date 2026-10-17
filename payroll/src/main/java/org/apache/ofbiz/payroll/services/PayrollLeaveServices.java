package org.apache.ofbiz.payroll.services;

import org.apache.ofbiz.base.util.Debug;
import org.apache.ofbiz.base.util.UtilDateTime;
import org.apache.ofbiz.base.util.UtilValidate;

import org.apache.ofbiz.entity.Delegator;
import org.apache.ofbiz.entity.GenericValue;
import org.apache.ofbiz.entity.util.EntityQuery;

import org.apache.ofbiz.service.DispatchContext;
import org.apache.ofbiz.service.ServiceUtil;

import java.sql.Timestamp;
import java.util.HashMap;
import java.util.Map;

public class PayrollLeaveServices {
    private static final String module = PayrollLeaveServices.class.getName();

    public static Map<String, Object> approveEmployeeLeaveRequest(DispatchContext dctx, Map<String, ? extends Object> context) {
        Map<String, Object> result = ServiceUtil.returnSuccess();
        Map<String, Object> data = new HashMap<>();
        Map<String, Object> legacyData = new HashMap<>();
        result.put("data", data);
        result.put("legacyData", legacyData);

        Delegator delegator = dctx.getDelegator();
        String leaveRequestId = (String) context.get("leaveRequestId");
        GenericValue approverUserLogin = (GenericValue) context.get("approverUserLogin");

        try {
            if (UtilValidate.isEmpty(leaveRequestId)) {
                return withError(result, "leaveRequestId is required");
            }

            GenericValue lr = EntityQuery.use(delegator).from("EmployeeLeaveRequest")
                    .where("leaveRequestId", leaveRequestId).queryOne();

            if (lr == null) {
                return withError(result, "Leave request not found: " + leaveRequestId);
            }

            String statusId = lr.getString("statusId");
            if (!"LR_NEW".equals(statusId)) {
                return withError(result, "Leave request must be LR_NEW to approve. Current=" + statusId);
            }

            Timestamp now = UtilDateTime.nowTimestamp();
            lr.set("statusId", "LR_APPR");
            lr.set("approvedDateTime", now);
            if (approverUserLogin != null) {
                lr.set("approverUserLoginId", approverUserLogin.getString("userLoginId"));
            }
            lr.store();

            data.put("leaveRequestId", leaveRequestId);
            data.put("statusId", "LR_APPR");
            data.put("approvedDateTime", now);
            legacyData.putAll(data);
            return result;

        } catch (Exception e) {
            Debug.logError(e, "approveEmployeeLeaveRequest failed", module);
            return withError(result, "approveEmployeeLeaveRequest failed: " + e.getMessage());
        }
    }

    private static Map<String, Object> withError(Map<String, Object> base, String msg) {
        base.put("responseMessage", "error");
        base.put("errorMessage", msg);
        base.put("data", base.get("data") == null ? new HashMap<>() : base.get("data"));
        base.put("legacyData", base.get("legacyData") == null ? new HashMap<>() : base.get("legacyData"));
        return base;
    }
}