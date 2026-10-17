package org.apache.ofbiz.payroll.services;

import java.sql.Timestamp;
import java.util.Map;
import java.util.LinkedHashMap;
import org.apache.ofbiz.base.util.Debug;
import org.apache.ofbiz.base.util.UtilDateTime;
import org.apache.ofbiz.base.util.UtilMisc;
import org.apache.ofbiz.base.util.UtilValidate;
import org.apache.ofbiz.entity.Delegator;
import org.apache.ofbiz.entity.GenericValue;
import org.apache.ofbiz.service.DispatchContext;
import org.apache.ofbiz.service.LocalDispatcher;
import org.apache.ofbiz.service.ServiceUtil;

public class PayrollGovernanceServices {

    public static final String MODULE = PayrollGovernanceServices.class.getName();

    private static final String RUN_LOCKED = "PRUN_LOCKED";
    private static final String RUN_UNLOCKED = "PRUN_UNLOCKED";

    private static final String PERIOD_OPEN = "PPER_OPEN";
    private static final String PERIOD_LOCKED = "PPER_LOCKED";
    private static final String PERIOD_CLOSED = "PPER_CLOSED";

    public static Map<String, Object> lockPayrollRun(DispatchContext dctx, Map<String, ?> context) {
        Delegator delegator = dctx.getDelegator();
        LocalDispatcher dispatcher = dctx.getDispatcher();
        GenericValue userLogin = (GenericValue) context.get("userLogin");

        String payrollRunId = (String) context.get("payrollRunId");
        String organizationPartyId = (String) context.get("organizationPartyId");
        String lockReason = (String) context.get("lockReason");

        try {
            if (UtilValidate.isEmpty(payrollRunId)) {
                return ServiceUtil.returnError("payrollRunId is required");
            }

            Timestamp nowTs = UtilDateTime.nowTimestamp();
            boolean changed = false;
            GenericValue row = delegator.findOne("PayrollRunLock", false, "payrollRunId", payrollRunId);

            if (row == null) {
                row = delegator.makeValue("PayrollRunLock");
                row.set("payrollRunId", payrollRunId);
                row.set("organizationPartyId", organizationPartyId);
                row.set("lockStatusId", RUN_LOCKED);
                row.set("lockedByUserLoginId", userLogin != null ? userLogin.getString("userLoginId") : null);
                row.set("lockedDate", nowTs);
                row.set("lockReason", lockReason);
                delegator.create(row);
                changed = true;
            } else if (!RUN_LOCKED.equals(row.getString("lockStatusId"))) {
                row.set("organizationPartyId", organizationPartyId);
                row.set("lockStatusId", RUN_LOCKED);
                row.set("lockedByUserLoginId", userLogin != null ? userLogin.getString("userLoginId") : null);
                row.set("lockedDate", nowTs);
                row.set("lockReason", lockReason);
                delegator.store(row);
                changed = true;
            }

            dispatcher.runSync("createPayrollActionAudit", UtilMisc.toMap(
                    "organizationPartyId", organizationPartyId,
                    "payrollRunId", payrollRunId,
                    "actionTypeId", "PACT_RUN_LOCK",
                    "actionStatusId", "ACT_SUCCESS",
                    "messageText", "Payroll run locked",
                    "reasonCode", "RUN_LOCK",
                    "userLogin", userLogin
            ));

            Map<String, Object> out = ServiceUtil.returnSuccess("Payroll run locked");
            Map<String, Object> data = UtilMisc.toMap(
                    "payrollRunId", payrollRunId,
                    "lockStatusId", RUN_LOCKED,
                    "changed", changed ? "Y" : "N"
            );
            out.put("data", data);
            out.put("legacyData", data);
            return out;

        } catch (Exception e) {
            Debug.logError(e, "Error locking payroll run", MODULE);
            return ServiceUtil.returnError("Error locking payroll run: " + e.getMessage());
        }
    }
public static Map<String, Object> unlockPayrollRun(DispatchContext dctx, Map<String, ?> context) {
    Delegator delegator = dctx.getDelegator();
    LocalDispatcher dispatcher = dctx.getDispatcher();
    GenericValue userLogin = (GenericValue) context.get("userLogin");

    String payrollRunId = (String) context.get("payrollRunId");
    String organizationPartyId = (String) context.get("organizationPartyId");
    String unlockReason = (String) context.get("unlockReason");

    Map<String, Object> data = new LinkedHashMap<>();
    data.put("payrollRunId", payrollRunId);
    data.put("organizationPartyId", organizationPartyId);
    data.put("unlockReason", unlockReason);

    try {
        if (UtilValidate.isEmpty(payrollRunId)) {
            Map<String, Object> err = ServiceUtil.returnError("payrollRunId is required");
            err.put("data", data);
            err.put("legacyData", data);
            return err;
        }

        // Resolve org from PayrollRun if missing
        if (UtilValidate.isEmpty(organizationPartyId)) {
            GenericValue run = delegator.findOne("PayrollRun", false, "payrollRunId", payrollRunId);
            if (run != null) {
                organizationPartyId = run.getString("organizationPartyId");
                data.put("organizationPartyId", organizationPartyId);
            }
        }

        Timestamp nowTs = UtilDateTime.nowTimestamp();
        boolean changed = false;

        GenericValue row = delegator.findOne("PayrollRunLock", false, "payrollRunId", payrollRunId);

        if (row == null) {
            row = delegator.makeValue("PayrollRunLock");
            row.set("payrollRunId", payrollRunId);
            row.set("organizationPartyId", organizationPartyId);
            row.set("lockStatusId", RUN_UNLOCKED);
            row.set("unlockedByUserLoginId", userLogin != null ? userLogin.getString("userLoginId") : null);
            row.set("unlockedDate", nowTs);
            row.set("unlockReason", unlockReason);
            delegator.create(row);
            changed = true;
        } else if (!RUN_UNLOCKED.equals(row.getString("lockStatusId"))) {
            if (UtilValidate.isEmpty(row.getString("organizationPartyId")) && UtilValidate.isNotEmpty(organizationPartyId)) {
                row.set("organizationPartyId", organizationPartyId);
            }
            row.set("lockStatusId", RUN_UNLOCKED);
            row.set("unlockedByUserLoginId", userLogin != null ? userLogin.getString("userLoginId") : null);
            row.set("unlockedDate", nowTs);
            row.set("unlockReason", unlockReason);
            delegator.store(row);
            changed = true;
        }

        dispatcher.runSync("createPayrollActionAudit", UtilMisc.toMap(
                "organizationPartyId", organizationPartyId,
                "payrollRunId", payrollRunId,
                "actionTypeId", "PACT_RUN_UNLOCK",
                "actionStatusId", "ACT_SUCCESS",
                "messageText", changed ? "Payroll run unlocked" : "Payroll run already unlocked",
                "reasonCode", "RUN_UNLOCK",
                "userLogin", userLogin
        ));

        data.put("lockStatusId", RUN_UNLOCKED);
        data.put("changed", changed ? "Y" : "N");
        data.put("unlockedAt", nowTs);

        Map<String, Object> out = ServiceUtil.returnSuccess(
                changed ? "Payroll run unlocked" : "Payroll run already unlocked"
        );
        out.put("data", data);
        out.put("legacyData", data);
        return out;

    } catch (Exception e) {
        Debug.logError(e, "Error unlocking payroll run", MODULE);
        Map<String, Object> err = ServiceUtil.returnError("Error unlocking payroll run: " + e.getMessage());
        err.put("data", data);
        err.put("legacyData", data);
        return err;
    }
}

public static Map<String, Object> lockPayrollPeriod(DispatchContext dctx, Map<String, ?> context) {
    Delegator delegator = dctx.getDelegator();
    LocalDispatcher dispatcher = dctx.getDispatcher();
    GenericValue userLogin = (GenericValue) context.get("userLogin");

    String organizationPartyId = (String) context.get("organizationPartyId");
    String payrollPeriodId = (String) context.get("payrollPeriodId");
    String lockReason = (String) context.get("lockReason");

    Map<String, Object> data = new LinkedHashMap<>();
    data.put("organizationPartyId", organizationPartyId);
    data.put("payrollPeriodId", payrollPeriodId);
    data.put("lockReason", lockReason);

    try {
        if (UtilValidate.isEmpty(organizationPartyId) || UtilValidate.isEmpty(payrollPeriodId)) {
            Map<String, Object> err = ServiceUtil.returnError("organizationPartyId and payrollPeriodId are required");
            err.put("data", data);
            err.put("legacyData", data);
            return err;
        }

        Timestamp nowTs = UtilDateTime.nowTimestamp();
        boolean changed = false;

        GenericValue row = delegator.findOne("PayrollPeriodControl", false,
                "organizationPartyId", organizationPartyId,
                "payrollPeriodId", payrollPeriodId);

        if (row == null) {
            row = delegator.makeValue("PayrollPeriodControl");
            row.set("organizationPartyId", organizationPartyId);
            row.set("payrollPeriodId", payrollPeriodId);
            row.set("controlStatusId", PERIOD_LOCKED);
            row.set("lockedByUserLoginId", userLogin != null ? userLogin.getString("userLoginId") : null);
            row.set("lockedDate", nowTs);
            row.set("lockReason", lockReason);
            delegator.create(row);
            changed = true;
        } else {
            String currentStatus = row.getString("controlStatusId");

            if (PERIOD_CLOSED.equals(currentStatus)) {
                data.put("controlStatusId", currentStatus);
                data.put("changed", "N");

                Map<String, Object> err = ServiceUtil.returnError(
                        "Payroll period is already closed and cannot be locked."
                );
                err.put("data", data);
                err.put("legacyData", data);
                return err;
            }

            if (!PERIOD_LOCKED.equals(currentStatus)) {
                row.set("controlStatusId", PERIOD_LOCKED);
                row.set("lockedByUserLoginId", userLogin != null ? userLogin.getString("userLoginId") : null);
                row.set("lockedDate", nowTs);
                row.set("lockReason", lockReason);
                delegator.store(row);
                changed = true;
            }
        }

        dispatcher.runSync("createPayrollActionAudit", UtilMisc.toMap(
                "organizationPartyId", organizationPartyId,
                "payrollPeriodId", payrollPeriodId,
                "actionTypeId", "PACT_PERIOD_LOCK",
                "actionStatusId", "ACT_SUCCESS",
                "messageText", changed ? "Payroll period locked" : "Payroll period already locked",
                "reasonCode", "PERIOD_LOCK",
                "userLogin", userLogin
        ));

        data.put("controlStatusId", PERIOD_LOCKED);
        data.put("changed", changed ? "Y" : "N");
        data.put("lockedAt", nowTs);

        Map<String, Object> out = ServiceUtil.returnSuccess(
                changed ? "Payroll period locked" : "Payroll period already locked"
        );
        out.put("data", data);
        out.put("legacyData", data);
        return out;

    } catch (Exception e) {
        Debug.logError(e, "Error locking payroll period", MODULE);
        Map<String, Object> err = ServiceUtil.returnError("Error locking payroll period: " + e.getMessage());
        err.put("data", data);
        err.put("legacyData", data);
        return err;
    }
}
    public static Map<String, Object> closePayrollPeriod(DispatchContext dctx, Map<String, ?> context) {
        Delegator delegator = dctx.getDelegator();
        LocalDispatcher dispatcher = dctx.getDispatcher();
        GenericValue userLogin = (GenericValue) context.get("userLogin");

        String organizationPartyId = (String) context.get("organizationPartyId");
        String payrollPeriodId = (String) context.get("payrollPeriodId");
        String closeReason = (String) context.get("closeReason");

        try {
            if (UtilValidate.isEmpty(organizationPartyId) || UtilValidate.isEmpty(payrollPeriodId)) {
                return ServiceUtil.returnError("organizationPartyId and payrollPeriodId are required");
            }

            Timestamp nowTs = UtilDateTime.nowTimestamp();
            boolean changed = false;
            GenericValue row = delegator.findOne("PayrollPeriodControl", false,
                    "organizationPartyId", organizationPartyId,
                    "payrollPeriodId", payrollPeriodId);

            if (row == null) {
                row = delegator.makeValue("PayrollPeriodControl");
                row.set("organizationPartyId", organizationPartyId);
                row.set("payrollPeriodId", payrollPeriodId);
                row.set("controlStatusId", PERIOD_CLOSED);
                row.set("closedByUserLoginId", userLogin != null ? userLogin.getString("userLoginId") : null);
                row.set("closedDate", nowTs);
                row.set("closeReason", closeReason);
                delegator.create(row);
                changed = true;
            } else {
                String currentStatus = row.getString("controlStatusId");
                if (!PERIOD_CLOSED.equals(currentStatus)) {
                    row.set("controlStatusId", PERIOD_CLOSED);
                    row.set("closedByUserLoginId", userLogin != null ? userLogin.getString("userLoginId") : null);
                    row.set("closedDate", nowTs);
                    row.set("closeReason", closeReason);
                    delegator.store(row);
                    changed = true;
                }
            }

            dispatcher.runSync("createPayrollActionAudit", UtilMisc.toMap(
                    "organizationPartyId", organizationPartyId,
                    "payrollPeriodId", payrollPeriodId,
                    "actionTypeId", "PACT_PERIOD_CLOSE",
                    "actionStatusId", "ACT_SUCCESS",
                    "messageText", changed ? "Payroll period closed" : "Payroll period already closed",
                    "reasonCode", "PERIOD_CLOSE",
                    "userLogin", userLogin
            ));

            Map<String, Object> out = ServiceUtil.returnSuccess(changed ? "Payroll period closed" : "Payroll period already closed");
            Map<String, Object> data = UtilMisc.toMap(
                    "organizationPartyId", organizationPartyId,
                    "payrollPeriodId", payrollPeriodId,
                    "controlStatusId", PERIOD_CLOSED,
                    "changed", changed ? "Y" : "N"
            );
            out.put("data", data);
            out.put("legacyData", data);
            return out;

        } catch (Exception e) {
            Debug.logError(e, "Error closing payroll period", MODULE);
            return ServiceUtil.returnError("Error closing payroll period: " + e.getMessage());
        }
    }
	
	
	
	public static Map<String, Object> reopenPayrollPeriod(DispatchContext dctx, Map<String, ?> context) {
    Delegator delegator = dctx.getDelegator();
    LocalDispatcher dispatcher = dctx.getDispatcher();
    GenericValue userLogin = (GenericValue) context.get("userLogin");

    String organizationPartyId = (String) context.get("organizationPartyId");
    String payrollPeriodId = (String) context.get("payrollPeriodId");
    String reopenReason = (String) context.get("reopenReason");

    Map<String, Object> data = new LinkedHashMap<>();
    data.put("organizationPartyId", organizationPartyId);
    data.put("payrollPeriodId", payrollPeriodId);
    data.put("reopenReason", reopenReason);

    try {
        if (UtilValidate.isEmpty(organizationPartyId) || UtilValidate.isEmpty(payrollPeriodId)) {
            Map<String, Object> err = ServiceUtil.returnError("organizationPartyId and payrollPeriodId are required");
            err.put("data", data);
            err.put("legacyData", data);
            return err;
        }

        GenericValue row = delegator.findOne("PayrollPeriodControl", false,
                "organizationPartyId", organizationPartyId,
                "payrollPeriodId", payrollPeriodId);

        if (row == null) {
            Map<String, Object> err = ServiceUtil.returnError(
                    "Payroll period control not found for org=" + organizationPartyId + ", period=" + payrollPeriodId
            );
            err.put("data", data);
            err.put("legacyData", data);
            return err;
        }

        String currentStatus = row.getString("controlStatusId");

        if (PERIOD_OPEN.equals(currentStatus)) {
            dispatcher.runSync("createPayrollActionAudit", UtilMisc.toMap(
                    "organizationPartyId", organizationPartyId,
                    "payrollPeriodId", payrollPeriodId,
                    "actionTypeId", "PACT_PERIOD_REOPEN",
                    "actionStatusId", "ACT_SUCCESS",
                    "messageText", "Payroll period already open",
                    "reasonCode", "PERIOD_REOPEN",
                    "userLogin", userLogin
            ));

            data.put("controlStatusId", PERIOD_OPEN);
            data.put("changed", "N");

            Map<String, Object> out = ServiceUtil.returnSuccess("Payroll period already open");
            out.put("data", data);
            out.put("legacyData", data);
            return out;
        }

        if (!PERIOD_CLOSED.equals(currentStatus)) {
            Map<String, Object> err = ServiceUtil.returnError(
                    "Payroll period is not closed: " + payrollPeriodId
            );
            err.put("data", data);
            err.put("legacyData", data);
            return err;
        }

        Timestamp nowTs = UtilDateTime.nowTimestamp();

        row.set("controlStatusId", PERIOD_OPEN);
        row.set("closeReason", null);
        row.store();

        dispatcher.runSync("createPayrollActionAudit", UtilMisc.toMap(
                "organizationPartyId", organizationPartyId,
                "payrollPeriodId", payrollPeriodId,
                "actionTypeId", "PACT_PERIOD_REOPEN",
                "actionStatusId", "ACT_SUCCESS",
                "messageText", "Payroll period reopened",
                "reasonCode", "PERIOD_REOPEN",
                "userLogin", userLogin
        ));

        data.put("controlStatusId", PERIOD_OPEN);
        data.put("changed", "Y");
        data.put("reopenedAt", nowTs);

        Map<String, Object> out = ServiceUtil.returnSuccess("Payroll period reopened");
        out.put("data", data);
        out.put("legacyData", data);
        return out;

    } catch (Exception e) {
        Debug.logError(e, "Error reopening payroll period", MODULE);
        Map<String, Object> err = ServiceUtil.returnError("Error reopening payroll period: " + e.getMessage());
        err.put("data", data);
        err.put("legacyData", data);
        return err;
    }
}
}