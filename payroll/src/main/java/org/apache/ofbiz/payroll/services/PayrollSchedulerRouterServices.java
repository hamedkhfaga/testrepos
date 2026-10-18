package org.apache.ofbiz.payroll.services;

import org.apache.ofbiz.base.util.Debug;
import org.apache.ofbiz.base.util.UtilValidate;
import org.apache.ofbiz.base.util.UtilProperties;
import org.apache.ofbiz.service.DispatchContext;
import org.apache.ofbiz.service.LocalDispatcher;
import org.apache.ofbiz.service.ServiceUtil;

import java.util.HashMap;
import java.util.Map;

public class PayrollSchedulerRouterServices {
    public static final String MODULE = PayrollSchedulerRouterServices.class.getName();

    // Property fallback (set in general.properties or a payroll.properties you load)
    // payroll.scheduler.pattern = A | B
    private static final String PROP_PATTERN = "payroll.scheduler.pattern";

    public static Map<String, Object> runMonthlyPayroll(DispatchContext dctx, Map<String, ?> context) {
        return route(dctx, context, "runMonthlyPayrollA", "runMonthlyPayrollB");
    }

    public static Map<String, Object> processPayrollEmailQueueWorker(DispatchContext dctx, Map<String, ?> context) {
        return route(dctx, context, "processPayrollEmailQueueWorkerA", "processPayrollEmailQueueWorkerB");
    }

    public static Map<String, Object> processPayrollSmsQueueWorker(DispatchContext dctx, Map<String, ?> context) {
        return route(dctx, context, "processPayrollSmsQueueWorkerA", "processPayrollSmsQueueWorkerB");
    }

    public static Map<String, Object> processPayrollExportQueueWorker(DispatchContext dctx, Map<String, ?> context) {
        return route(dctx, context, "processPayrollExportQueueWorkerA", "processPayrollExportQueueWorkerB");
    }

    public static Map<String, Object> processPayrollComplianceDailyWorker(DispatchContext dctx, Map<String, ?> context) {
        return route(dctx, context, "processPayrollComplianceDailyWorkerA", "processPayrollComplianceDailyWorkerB");
    }

    private static Map<String, Object> route(DispatchContext dctx,
                                            Map<String, ?> context,
                                            String serviceA,
                                            String serviceB) {
        LocalDispatcher dispatcher = dctx.getDispatcher();

        try {
            String pattern = (String) context.get("schedulerPattern");
            if (UtilValidate.isEmpty(pattern)) {
                // fallback from properties
                // tries default first, then general.properties
                pattern = UtilProperties.getPropertyValue("general", PROP_PATTERN);
            }
            if (UtilValidate.isEmpty(pattern)) {
                pattern = "A"; // hard default
            }

            String target = "B".equalsIgnoreCase(pattern) ? serviceB : serviceA;

            // pass-through: forward entire context
            Map<String, Object> fwd = new HashMap<>();
            fwd.putAll((Map<? extends String, ?>) context);

            Debug.logInfo("PayrollSchedulerRouter: pattern=" + pattern + " -> " + target, MODULE);
            Map<String, Object> res = dispatcher.runSync(target, fwd);

            if (ServiceUtil.isError(res)) {
                return res;
            }
            return res;

        } catch (Exception e) {
            Debug.logError(e, "PayrollSchedulerRouter route failed", MODULE);
            return ServiceUtil.returnError("Router failed: " + e.getMessage());
        }
    }
}
