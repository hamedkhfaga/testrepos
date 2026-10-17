package org.apache.ofbiz.payroll;

import org.apache.ofbiz.base.util.Debug;
import org.apache.ofbiz.entity.Delegator;
import org.apache.ofbiz.entity.GenericValue;
import org.apache.ofbiz.entity.util.EntityQuery;
import org.apache.ofbiz.service.LocalDispatcher;
import org.apache.ofbiz.service.ServiceUtil;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.util.Map;

public class PayrollEvents {
    
    public static final String module = PayrollEvents.class.getName();
    
    public static String setupEmployeeList(HttpServletRequest request, HttpServletResponse response) {
        try {
            // Get delegator from request
            Delegator delegator = (Delegator) request.getAttribute("delegator");
            LocalDispatcher dispatcher = (LocalDispatcher) request.getAttribute("dispatcher");
            GenericValue userLogin = (GenericValue) request.getSession().getAttribute("userLogin");
            
            if (delegator == null) {
                Debug.logError("Delegator is null in setupEmployeeList", module);
                request.setAttribute("_ERROR_MESSAGE_", "System error: Delegator not available");
                return "error";
            }
            
            // Get total employees count
            // IMPORTANT: Replace "Employee" with your actual entity name
            long totalEmployees = EntityQuery.use(delegator)
                    .from("Employee")
                    .queryCount();
            
            // Get active employees count - adjust based on your entity structure
            long activeEmployees = EntityQuery.use(delegator)
                    .from("Employee")
                    .where("statusId", "EMP_ACTIVE")  // Adjust field name and value
                    .queryCount();
            
            // Get inactive employees count
            long inactiveEmployees = totalEmployees - activeEmployees;
            
            // Set values in request
            request.setAttribute("totalEmployees", totalEmployees);
            request.setAttribute("activeEmployees", activeEmployees);
            request.setAttribute("inactiveEmployees", inactiveEmployees);
            
            // Set submenu context
            request.setAttribute("subMenuName", "EmployeeManagementSubMenu");
            
            return "success";
            
        } catch (Exception e) {
            Debug.logError(e, "Error setting up employee list", module);
            request.setAttribute("_ERROR_MESSAGE_", e.getMessage());
            return "error";
        }
    }
}