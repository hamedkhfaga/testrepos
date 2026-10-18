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

public class HrInputServices {
    public static final String MODULE = HrInputServices.class.getName();

    // Status IDs (use your unified status file)
    private static final String OTR_APPR = "OTR_APPR";
    private static final String SNAP_READY = "SNAP_READY";

    public static Map<String, Object> approveEmployeeOvertimeRequest(DispatchContext dctx, Map<String, ?> context) {
        final Delegator delegator = dctx.getDelegator();
        final GenericValue userLogin = (GenericValue) context.get("userLogin");
        final String id = (String) context.get("employeeOvertimeRequestId");
        final String newStatusId = UtilValidate.isNotEmpty((String) context.get("newStatusId")) ? (String) context.get("newStatusId") : OTR_APPR;

        if (userLogin == null) return ServiceUtil.returnError("userLogin is required");
        if (UtilValidate.isEmpty(id)) return ServiceUtil.returnError("employeeOvertimeRequestId is required");

        try {
            GenericValue req = EntityQuery.use(delegator).from("EmployeeOvertimeRequest")
                    .where("employeeOvertimeRequestId", id).cache(false).queryOne();
            if (req == null) return ServiceUtil.returnError("EmployeeOvertimeRequest not found id=" + id);

            req.put("statusId", newStatusId);
            req.put("lastUpdatedStamp", UtilDateTime.nowTimestamp());
            req.store();

            Map<String, Object> out = ServiceUtil.returnSuccess("Overtime request approved id=" + id);
            out.put("successMessage", "Overtime request approved id=" + id);
            return out;

        } catch (Exception e) {
            Debug.logError(e, "approveEmployeeOvertimeRequest failed id=" + id, MODULE);
            return ServiceUtil.returnError("approveEmployeeOvertimeRequest failed: " + e.getMessage());
        }
    }

    public static Map<String, Object> lockPayrollInputSnapshotForRun(DispatchContext dctx, Map<String, ?> context) {
        final Delegator delegator = dctx.getDelegator();
        final GenericValue userLogin = (GenericValue) context.get("userLogin");
        final String snapId = (String) context.get("payrollInputSnapshotId");
        final String payrollRunId = (String) context.get("payrollRunId");

        if (userLogin == null) return ServiceUtil.returnError("userLogin is required");
        if (UtilValidate.isEmpty(snapId)) return ServiceUtil.returnError("payrollInputSnapshotId is required");

        try {
            GenericValue snap = EntityQuery.use(delegator).from("PayrollInputSnapshot")
                    .where("payrollInputSnapshotId", snapId).cache(false).queryOne();
            if (snap == null) return ServiceUtil.returnError("PayrollInputSnapshot not found id=" + snapId);

            // Stage#1: just mark READY; Phase#1 will validate and attach runId properly
            snap.put("statusId", SNAP_READY);
            if (UtilValidate.isNotEmpty(payrollRunId)) {
                // only if your entity has payrollRunId field; if not, remove this line
                snap.put("payrollRunId", payrollRunId);
            }
            Timestamp now = UtilDateTime.nowTimestamp();
            snap.put("lastUpdatedStamp", now);
            snap.store();

            Map<String, Object> out = ServiceUtil.returnSuccess("Snapshot locked READY id=" + snapId);
            out.put("successMessage", "Snapshot locked READY id=" + snapId);
            return out;

        } catch (Exception e) {
            Debug.logError(e, "lockPayrollInputSnapshotForRun failed id=" + snapId, MODULE);
            return ServiceUtil.returnError("lockPayrollInputSnapshotForRun failed: " + e.getMessage());
        }
    }
}