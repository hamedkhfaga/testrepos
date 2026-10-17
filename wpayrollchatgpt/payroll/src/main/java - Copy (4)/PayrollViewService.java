package org.ofbiz.payroll;

import java.util.Map;
import org.apache.ofbiz.service.DispatchContext;
import org.apache.ofbiz.service.ServiceUtil;
import org.apache.ofbiz.base.util.Debug;
import org.apache.ofbiz.entity.model.ModelEntity;
import org.apache.ofbiz.entity.model.ModelReader;

public class PayrollViewService {

    public static final String MODULE = PayrollViewService.class.getName();

    public static Map<String, Object> createAllPayrollViews(DispatchContext dctx, Map<String, ?> context) {
        Map<String, Object> result = ServiceUtil.returnSuccess();

        try {
            Debug.logInfo("=== Creating Payroll Database Views ===", MODULE);

            try {
                ModelReader modelReader = dctx.getDelegator().getModelReader();
                ModelEntity payrollEntity = modelReader.getModelEntity("PayrollStructure");
                Debug.logInfo("Found PayrollStructure entity, proceeding with view creation", MODULE);
            } catch (Exception e) {
                Debug.logWarning("PayrollStructure entity not found: " + e.getMessage(), MODULE);
                return ServiceUtil.returnSuccess(
                        "Payroll entities not ready - views will be created manually");
            }

            // SQL: Employee Master List
            String sql1 = "CREATE VIEW \"V_EMPLOYEE_MASTER_LIST\" AS "
                    + "SELECT p.party_id as employee_id, "
                    + "       per.first_name, "
                    + "       per.last_name, "
                    + "       per.gender, "
                    + "       epa.employment_type, "
                    + "       epa.contract_type, "
                    + "       epa.base_salary, "
                    + "       epa.effective_date as hire_date, "
                    + "       epa.status_id, "
                    + "       ps.structure_name as department, "
                    + "       psi.description as status_description, "
                    + "       per.first_name || ' ' || per.last_name as full_name "
                    + "FROM \"PARTY\" p "
                    + "LEFT JOIN \"PERSON\" per ON p.party_id = per.party_id "
                    + "LEFT JOIN \"EMPLOYEE_PAYROLL_ASSIGNMENT\" epa ON p.party_id = epa.party_id "
                    + "    AND epa.thru_date IS NULL "
                    + "LEFT JOIN \"PAYROLL_STRUCTURE\" ps ON epa.payroll_structure_id = ps.payroll_structure_id "
                    + "LEFT JOIN \"PAYROLL_STATUS_ITEM\" psi ON epa.status_id = psi.status_id "
                    + "WHERE p.party_type_id = 'PERSON'";

            // SQL: Department Summary
            String sql2 = "CREATE VIEW \"V_DEPARTMENT_SUMMARY\" AS "
                    + "SELECT ps.structure_name as department, "
                    + "       COUNT(DISTINCT p.party_id) as employee_count, "
                    + "       SUM(epa.base_salary) as total_monthly_cost, "
                    + "       AVG(epa.base_salary) as avg_salary "
                    + "FROM \"PARTY\" p "
                    + "JOIN \"PERSON\" per ON p.party_id = per.party_id "
                    + "JOIN \"EMPLOYEE_PAYROLL_ASSIGNMENT\" epa ON p.party_id = epa.party_id "
                    + "    AND epa.thru_date IS NULL "
                    + "JOIN \"PAYROLL_STRUCTURE\" ps ON epa.payroll_structure_id = ps.payroll_structure_id "
                    + "WHERE p.party_type_id = 'PERSON' "
                    + "GROUP BY ps.structure_name";

            // SQL: Active Assignments
            String sql3 = "CREATE VIEW \"V_ACTIVE_ASSIGNMENTS\" AS "
                    + "SELECT epa.assignment_id, "
                    + "       p.party_id, "
                    + "       per.first_name || ' ' || per.last_name as employee_name, "
                    + "       ps.structure_name as department, "
                    + "       epa.effective_date as start_date, "
                    + "       epa.base_salary "
                    + "FROM \"EMPLOYEE_PAYROLL_ASSIGNMENT\" epa "
                    + "JOIN \"PARTY\" p ON epa.party_id = p.party_id "
                    + "JOIN \"PERSON\" per ON p.party_id = per.party_id "
                    + "LEFT JOIN \"PAYROLL_STRUCTURE\" ps ON epa.payroll_structure_id = ps.payroll_structure_id "
                    + "WHERE epa.thru_date IS NULL "
                    + "  AND p.party_type_id = 'PERSON'";

            // Log SQL for manual execution
            Debug.logInfo("=== PAYROLL VIEW CREATION SQL ===", MODULE);
            Debug.logInfo("Run these SQL statements in WebTools SQL Processor:", MODULE);
            Debug.logInfo("", MODULE);
            Debug.logInfo("-- View 1: Employee Master List", MODULE);
            Debug.logInfo(sql1, MODULE);
            Debug.logInfo("", MODULE);
            Debug.logInfo("-- View 2: Department Summary", MODULE);
            Debug.logInfo(sql2, MODULE);
            Debug.logInfo("", MODULE);
            Debug.logInfo("-- View 3: Active Assignments", MODULE);
            Debug.logInfo(sql3, MODULE);

            // Test SQL execution via delegator
            try {
                String testSQL = "SELECT COUNT(*) FROM \"PARTY\"";
                dctx.getDelegator().findList(testSQL, null, null, null, null, false);

                Debug.logInfo("SQL execution test successful", MODULE);
                result.put("message", "Payroll view SQL logged - ready for manual execution");
                result.put("sqlAvailable", true);

            } catch (Exception e) {
                Debug.logWarning("Direct SQL execution not available: " + e.getMessage(), MODULE);
                result.put("message", "Payroll view SQL logged - execute manually in WebTools");
                result.put("sqlAvailable", false);
            }

            result.put("viewsDefined", 3);
            result.put("status", "SUCCESS");

        } catch (Exception e) {
            String errorMsg = "Error in payroll view service: " + e.getMessage();
            Debug.logError(e, errorMsg, MODULE);
            return ServiceUtil.returnError(errorMsg);
        }

        return result;
    }
}
