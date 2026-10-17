package org.apache.ofbiz.payroll.services;

import org.apache.ofbiz.base.util.Debug;
import org.apache.ofbiz.service.DispatchContext;
import org.apache.ofbiz.service.GenericServiceException;
import org.apache.ofbiz.service.ServiceUtil;

import java.util.Map;

public class PayrollRestServices {

    public static final String MODULE = PayrollRestServices.class.getName();

    /* ===================================================== */
    /* REST → Internal Service Delegation                     */
    /* ===================================================== */

    public static Map<String, Object> createPayrollAssignmentRest(
            DispatchContext dctx, Map<String, ?> context) {

        try {
            return dctx.getDispatcher().runSync(
                    "createEmployeePayrollAssignment",
                    context
            );
        } catch (GenericServiceException e) {
            Debug.logError(e, "createPayrollAssignmentRest failed", MODULE);
            return ServiceUtil.returnError(
                    "createPayrollAssignmentRest failed: " + e.getMessage()
            );
        }
    }

    public static Map<String, Object> processPayrollRunRest(
            DispatchContext dctx, Map<String, ?> context) {

        try {
            return dctx.getDispatcher().runSync(
                    "processPayrollRun",
                    context
            );
        } catch (GenericServiceException e) {
            Debug.logError(e, "processPayrollRunRest failed", MODULE);
            return ServiceUtil.returnError(
                    "processPayrollRunRest failed: " + e.getMessage()
            );
        }
    }

    public static Map<String, Object> approvePayrollRunRest(
            DispatchContext dctx, Map<String, ?> context) {

        try {
            return dctx.getDispatcher().runSync(
                    "approvePayrollRun",
                    context
            );
        } catch (GenericServiceException e) {
            Debug.logError(e, "approvePayrollRunRest failed", MODULE);
            return ServiceUtil.returnError(
                    "approvePayrollRunRest failed: " + e.getMessage()
            );
        }
    }

    public static Map<String, Object> generatePayslipPdfRest(
            DispatchContext dctx, Map<String, ?> context) {

        try {
            return dctx.getDispatcher().runSync(
                    "generatePayslipPdf",
                    context
            );
        } catch (GenericServiceException e) {
            Debug.logError(e, "generatePayslipPdfRest failed", MODULE);
            return ServiceUtil.returnError(
                    "generatePayslipPdfRest failed: " + e.getMessage()
            );
        }
    }
}
