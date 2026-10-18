import org.apache.ofbiz.service.ServiceUtil
import org.apache.ofbiz.base.util.Debug

module = "PayrollStartup"

// Wait for entity engine to be fully initialized
if (delegator == null) {
    Debug.logError("Delegator not available yet", module)
    return
}

try {
    Debug.logInfo("=== Payroll Startup: Creating Database Views ===", module)
    
    // Run the view creation service
    result = dispatcher.runSync("createAllPayrollViews", [:])
    
    if (ServiceUtil.isSuccess(result)) {
        Debug.logInfo("Payroll views created: " + result.get("viewsCreated") + " views", module)
    } else {
        Debug.logError("Failed to create payroll views: " + result.get("errorMessage"), module)
    }
    
} catch (Exception e) {
    Debug.logError(e, "Error in payroll startup script: " + e.getMessage(), module)
}