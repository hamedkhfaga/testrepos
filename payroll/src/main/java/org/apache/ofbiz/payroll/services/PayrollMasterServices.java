package org.apache.ofbiz.payroll.services;

import org.apache.ofbiz.base.util.UtilDateTime;
import org.apache.ofbiz.base.util.UtilMisc;
import org.apache.ofbiz.base.util.UtilValidate;
import org.apache.ofbiz.entity.Delegator;
import org.apache.ofbiz.entity.GenericEntityException;
import org.apache.ofbiz.entity.GenericPK;
import org.apache.ofbiz.entity.GenericValue;
import org.apache.ofbiz.entity.util.EntityQuery;
import org.apache.ofbiz.service.DispatchContext;
import org.apache.ofbiz.service.ServiceUtil;

import java.util.List;
import java.util.Map;

public class PayrollMasterServices {

    /* ========================================================= */
    /* PayrollStructure CRUD                                     */
    /* ========================================================= */

    public static Map<String, Object> createPayrollStructure(DispatchContext dctx, Map<String, ?> context)
            throws GenericEntityException {

        Delegator delegator = dctx.getDelegator();
        GenericValue userLogin = (GenericValue) context.get("userLogin");

        GenericValue gv = delegator.makeValue("PayrollStructure");
        gv.set("payrollStructureId", delegator.getNextSeqId("PayrollStructure"));
        gv.set("organizationPartyId", context.get("organizationPartyId"));
        gv.set("structureName", context.get("structureName"));
        gv.set("description", context.get("description"));
        gv.set("createdDate", UtilDateTime.nowTimestamp());
        gv.set("createdByUserLogin", userLogin != null ? userLogin.getString("userLoginId") : null);

        delegator.create(gv);

        Map<String, Object> out = ServiceUtil.returnSuccess();
        out.put("payrollStructureId", gv.getString("payrollStructureId"));
        return out;
    }

    public static Map<String, Object> updatePayrollStructure(DispatchContext dctx, Map<String, ?> context)
            throws GenericEntityException {

        Delegator delegator = dctx.getDelegator();
        String payrollStructureId = (String) context.get("payrollStructureId");

        if (UtilValidate.isEmpty(payrollStructureId)) {
            return ServiceUtil.returnError("payrollStructureId is required");
        }

        GenericValue gv = delegator.findOne("PayrollStructure",
                UtilMisc.toMap("payrollStructureId", payrollStructureId), false);

        if (gv == null) return ServiceUtil.returnError("PayrollStructure not found");

        gv.setNonPKFields(context);
        gv.store();
        return ServiceUtil.returnSuccess();
    }

   public static Map<String, Object> deletePayrollStructure(DispatchContext dctx, Map<String, ?> context)
        throws GenericEntityException {

    Delegator delegator = dctx.getDelegator();

    String payrollStructureId = (String) context.get("payrollStructureId");
    if (UtilValidate.isEmpty(payrollStructureId)) {
        return ServiceUtil.returnError("payrollStructureId is required");
    }

    GenericValue gv = delegator.findOne("PayrollStructure",
            UtilMisc.toMap("payrollStructureId", payrollStructureId), false);

    if (gv != null) {
        gv.remove();
    }

    return ServiceUtil.returnSuccess();
}



    public static Map<String, Object> getPayrollStructureList(DispatchContext dctx, Map<String, ?> context)
            throws GenericEntityException {

        List<GenericValue> list = EntityQuery.use(dctx.getDelegator())
                .from("PayrollStructure")
                .where("organizationPartyId", context.get("organizationPartyId"))
                .queryList();

        Map<String, Object> result = ServiceUtil.returnSuccess();
        result.put("list", list);
        return result;
    }

    /* ========================================================= */
    /* EmployeePayrollAssignment CRUD                            */
    /* ========================================================= */

    public static Map<String, Object> createEmployeePayrollAssignment(DispatchContext dctx, Map<String, ?> context)
            throws GenericEntityException {

        Delegator delegator = dctx.getDelegator();
        GenericValue gv = delegator.makeValue("EmployeePayrollAssignment");

        gv.set("assignmentId", delegator.getNextSeqId("EmployeePayrollAssignment"));
        gv.setNonPKFields(context);

        delegator.create(gv);

        Map<String, Object> out = ServiceUtil.returnSuccess();
        out.put("assignmentId", gv.getString("assignmentId"));
        return out;
    }

    public static Map<String, Object> updateEmployeePayrollAssignment(DispatchContext dctx, Map<String, ?> context)
            throws GenericEntityException {

        Delegator delegator = dctx.getDelegator();
        String assignmentId = (String) context.get("assignmentId");

        if (UtilValidate.isEmpty(assignmentId)) {
            return ServiceUtil.returnError("assignmentId is required");
        }

        GenericValue gv = delegator.findOne("EmployeePayrollAssignment",
                UtilMisc.toMap("assignmentId", assignmentId), false);

        if (gv == null) return ServiceUtil.returnError("Assignment not found");

        gv.setNonPKFields(context);
        gv.store();
        return ServiceUtil.returnSuccess();
    }

   public static Map<String, Object> deleteEmployeePayrollAssignment(
        DispatchContext dctx, Map<String, ?> context) throws GenericEntityException {

    Delegator delegator = dctx.getDelegator();

    String assignmentId = (String) context.get("assignmentId");
    if (UtilValidate.isEmpty(assignmentId)) {
        return ServiceUtil.returnError("assignmentId is required");
    }

    GenericValue gv = delegator.findOne("EmployeePayrollAssignment",
            UtilMisc.toMap("assignmentId", assignmentId), false);

    if (gv != null) {
        gv.remove();
    }

    return ServiceUtil.returnSuccess();
}

    public static Map<String, Object> getEmployeePayrollAssignmentList(DispatchContext dctx, Map<String, ?> context)
            throws GenericEntityException {

        List<GenericValue> list = EntityQuery.use(dctx.getDelegator())
                .from("EmployeePayrollAssignment")
                .where("partyId", context.get("partyId"))
                .queryList();

        Map<String, Object> result = ServiceUtil.returnSuccess();
        result.put("list", list);
        return result;
    }
}
