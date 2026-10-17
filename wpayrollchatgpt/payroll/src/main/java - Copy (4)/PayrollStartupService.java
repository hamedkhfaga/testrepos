package org.ofbiz.payroll;

import java.util.HashMap;
import java.util.Map;
import org.apache.ofbiz.service.DispatchContext;
import org.apache.ofbiz.service.ServiceUtil;
import org.apache.ofbiz.base.util.Debug;
import org.apache.ofbiz.entity.GenericValue;

public class PayrollStartupService {

    public static final String MODULE = PayrollStartupService.class.getName();

    public static Map<String, Object> onStartupCreateViews(DispatchContext dctx, Map<String, ?> context) {

        try {
            Debug.logInfo("=== Payroll Startup Service Triggered ===", MODULE);

            // Wait for OFBiz to fully initialize
            Thread.sleep(8000);

            // Check if payroll tables exist
            boolean tablesExist = false;
            try {
                // CORRECT: Cast null to Object for varargs
                GenericValue payrollStruct = dctx.getDelegator().findOne("PayrollStructure", true, (Object) null);
                if (payrollStruct != null) {
                    tablesExist = true;
                    Debug.logInfo("✓ PayrollStructure table exists with data", MODULE);
                } else {
                    // Table exists but is empty
                    tablesExist = true;
                    Debug.logInfo("✓ PayrollStructure table exists (empty)", MODULE);
                }
            } catch (Exception e) {
                Debug.logWarning("PayrollStructure table not ready yet: " + e.getMessage(), MODULE);
                // Wait and try one more time
                Thread.sleep(3000);
                try {
                    GenericValue payrollStruct = dctx.getDelegator().findOne("PayrollStructure", true, (Object) null);
                    if (payrollStruct != null) {
                        tablesExist = true;
                        Debug.logInfo("✓ PayrollStructure table now exists", MODULE);
                    } else {
                        tablesExist = true; // Table exists even if empty
                    }
                } catch (Exception e2) {
                    Debug.logError("✗ PayrollStructure table still not available: " + e2.getMessage(), MODULE);
                }
            }

            if (tablesExist) {
                // Run the view creation service
                Map<String, Object> serviceContext = new HashMap<>();
                Map<String, Object> result = dctx.getDispatcher().runSync("createAllPayrollViews", serviceContext);

                if (ServiceUtil.isSuccess(result)) {
                    String message = (String) result.get("message");
                    Debug.logInfo("✅ " + message, MODULE);

                    // Check if SQL was logged for manual execution
                    Boolean sqlAvailable = (Boolean) result.get("sqlAvailable");
                    if (sqlAvailable != null && sqlAvailable) {
                        Debug.logInfo("SQL execution available - views may be created automatically", MODULE);
                    } else {
                        Debug.logInfo("Execute SQL manually from logs to create views", MODULE);
                    }

                    return ServiceUtil.returnSuccess("Payroll startup: " + message);
                } else {
                    String errorMsg = ServiceUtil.getErrorMessage(result);
                    Debug.logError("❌ View service failed: " + errorMsg, MODULE);
                    return ServiceUtil.returnSuccess("Payroll startup completed with errors");
                }
            } else {
                Debug.logWarning("⚠️ Payroll tables not found - view creation skipped", MODULE);
                return ServiceUtil.returnSuccess("Payroll tables not ready - run createAllPayrollViews manually later");
            }

        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            Debug.logError(e, "Startup interrupted", MODULE);
            return ServiceUtil.returnError("Startup interrupted");
        } catch (Exception e) {
            Debug.logError(e, "Payroll startup error", MODULE);
            return ServiceUtil.returnSuccess("Startup completed with warnings");
        }
    }
}
