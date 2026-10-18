package org.apache.ofbiz.payroll.util;

import org.apache.ofbiz.base.util.UtilValidate;
import org.apache.ofbiz.entity.Delegator;
import org.apache.ofbiz.entity.GenericEntityException;
import org.apache.ofbiz.entity.GenericValue;

public final class PayrollGovernanceUtil {

    public static final String MODULE = PayrollGovernanceUtil.class.getName();

    public static final String RUN_LOCKED = "PRUN_LOCKED";
    public static final String RUN_UNLOCKED = "PRUN_UNLOCKED";

    public static final String PERIOD_OPEN = "PPER_OPEN";
    public static final String PERIOD_LOCKED = "PPER_LOCKED";
    public static final String PERIOD_CLOSED = "PPER_CLOSED";

    private PayrollGovernanceUtil() {}

    public static GenericValue getRunLock(Delegator delegator, String payrollRunId) throws GenericEntityException {
        if (UtilValidate.isEmpty(payrollRunId)) {
            return null;
        }
        return delegator.findOne("PayrollRunLock", false, "payrollRunId", payrollRunId);
    }

    public static GenericValue getPeriodControl(Delegator delegator, String organizationPartyId, String payrollPeriodId)
            throws GenericEntityException {
        if (UtilValidate.isEmpty(organizationPartyId) || UtilValidate.isEmpty(payrollPeriodId)) {
            return null;
        }
        return delegator.findOne("PayrollPeriodControl", false,
                "organizationPartyId", organizationPartyId,
                "payrollPeriodId", payrollPeriodId);
    }

    public static boolean isRunLocked(Delegator delegator, String payrollRunId) throws GenericEntityException {
        GenericValue runLock = getRunLock(delegator, payrollRunId);
        if (runLock == null) {
            return false;
        }
        String lockStatusId = runLock.getString("lockStatusId");
        return RUN_LOCKED.equals(lockStatusId);
    }

    public static String getPeriodStatus(Delegator delegator, String organizationPartyId, String payrollPeriodId)
            throws GenericEntityException {
        GenericValue control = getPeriodControl(delegator, organizationPartyId, payrollPeriodId);
        if (control == null) {
            return PERIOD_OPEN;
        }
        String controlStatusId = control.getString("controlStatusId");
        return UtilValidate.isEmpty(controlStatusId) ? PERIOD_OPEN : controlStatusId;
    }

    public static void ensurePayrollRunEditable(Delegator delegator, String payrollRunId)
            throws GenericEntityException, IllegalStateException {
        if (isRunLocked(delegator, payrollRunId)) {
            throw new IllegalStateException("Payroll run [" + payrollRunId + "] is locked and cannot be modified.");
        }
    }

    public static void ensurePayrollPeriodOpen(Delegator delegator, String organizationPartyId, String payrollPeriodId)
            throws GenericEntityException, IllegalStateException {
        String status = getPeriodStatus(delegator, organizationPartyId, payrollPeriodId);
        if (PERIOD_LOCKED.equals(status)) {
            throw new IllegalStateException("Payroll period [" + payrollPeriodId + "] for organization [" + organizationPartyId + "] is locked.");
        }
        if (PERIOD_CLOSED.equals(status)) {
            throw new IllegalStateException("Payroll period [" + payrollPeriodId + "] for organization [" + organizationPartyId + "] is closed.");
        }
    }

    public static void ensureRunAndPeriodEditable(Delegator delegator,
                                                  String organizationPartyId,
                                                  String payrollPeriodId,
                                                  String payrollRunId)
            throws GenericEntityException, IllegalStateException {
        ensurePayrollRunEditable(delegator, payrollRunId);
        ensurePayrollPeriodOpen(delegator, organizationPartyId, payrollPeriodId);
    }
}