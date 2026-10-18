package org.apache.ofbiz.payroll.services;

import org.apache.ofbiz.base.util.*;
import org.apache.ofbiz.entity.*;
import org.apache.ofbiz.entity.condition.*;
import org.apache.ofbiz.entity.util.EntityQuery;
import org.apache.ofbiz.service.*;

import java.util.*;

public class PayrollRetroQueryServices {
    public static final String MODULE = PayrollRetroQueryServices.class.getName();

   public static Map<String, Object> getPayrollRetroDeltaList(DispatchContext dctx, Map<String, ?> context) {
    Delegator delegator = dctx.getDelegator();

    try {
        String payrollRetroBatchId = (String) context.get("payrollRetroBatchId");
        if (UtilValidate.isEmpty(payrollRetroBatchId)) {
            return ServiceUtil.returnError("payrollRetroBatchId is required");
        }

        // Canonical org scope (optional) - validate against PayrollRetroBatch because Delta has no org field
        String organizationPartyId = (String) context.get("organizationPartyId");
        if (UtilValidate.isNotEmpty(organizationPartyId)) {
            GenericValue batch = EntityQuery.use(delegator)
                    .from("PayrollRetroBatch")
                    .where("payrollRetroBatchId", payrollRetroBatchId)
                    .queryOne();

            if (batch == null) {
                return ServiceUtil.returnError("PayrollRetroBatch not found for id: " + payrollRetroBatchId);
            }

            String batchOrg = batch.getString("organizationPartyId");
            if (!organizationPartyId.equals(batchOrg)) {
                return ServiceUtil.returnError("Unauthorized batch access: organizationPartyId mismatch");
            }
        }

        String partyId = (String) context.get("partyId");
        String lineTypeId = (String) context.get("lineTypeId");
        String statusId = (String) context.get("statusId");

        List<EntityCondition> conds = new ArrayList<>();
        conds.add(EntityCondition.makeCondition("payrollRetroBatchId", EntityOperator.EQUALS, payrollRetroBatchId));

        if (UtilValidate.isNotEmpty(partyId)) {
            conds.add(EntityCondition.makeCondition("partyId", EntityOperator.EQUALS, partyId));
        }
        if (UtilValidate.isNotEmpty(lineTypeId)) {
            conds.add(EntityCondition.makeCondition("lineTypeId", EntityOperator.EQUALS, lineTypeId));
        }
        if (UtilValidate.isNotEmpty(statusId)) {
            conds.add(EntityCondition.makeCondition("statusId", EntityOperator.EQUALS, statusId));
        }

        List<GenericValue> list = EntityQuery.use(delegator)
                .from("PayrollRetroDelta")
                .where(EntityCondition.makeCondition(conds, EntityOperator.AND))
                .orderBy("partyId", "lineTypeId", "payComponentTypeId")
                .queryList();

        Map<String, Object> out = ServiceUtil.returnSuccess();
        out.put("list", list);
        return out;

    } catch (Exception e) {
        Debug.logError(e, "getPayrollRetroDeltaList failed", MODULE);
        return ServiceUtil.returnError("getPayrollRetroDeltaList failed: " + e.getMessage());
    }
}

    /**
     * List Retro Batches (optionally filtered by org/year/month/status).
     * Output key matches screen usage: "list".
     *
     * Expected entities/fields (adjust if your model differs):
     *  - PayrollRetroBatch.payrollRetroBatchId
     *  - PayrollRetroBatch.organizationPartyId (or orgPartyId)
     *  - PayrollRetroBatch.periodYear
     *  - PayrollRetroBatch.periodMonth
     *  - PayrollRetroBatch.statusId
     *  - PayrollRetroBatch.createdDate (or lastUpdatedStamp)
     */
   public static Map<String, Object> getPayrollRetroBatchList(DispatchContext dctx, Map<String, ?> context) {
    Delegator delegator = dctx.getDelegator();

    try {
        String organizationPartyId = (String) context.get("organizationPartyId");
        Long periodYear = (Long) context.get("periodYear");
        Long periodMonth = (Long) context.get("periodMonth");
        String statusId = (String) context.get("statusId");

        List<EntityCondition> conds = new ArrayList<>();

        if (UtilValidate.isNotEmpty(organizationPartyId)) {
            conds.add(EntityCondition.makeCondition("organizationPartyId", EntityOperator.EQUALS, organizationPartyId));
        }
        if (periodYear != null) {
            conds.add(EntityCondition.makeCondition("periodYear", EntityOperator.EQUALS, periodYear));
        }
        if (periodMonth != null) {
            conds.add(EntityCondition.makeCondition("periodMonth", EntityOperator.EQUALS, periodMonth));
        }
        if (UtilValidate.isNotEmpty(statusId)) {
            conds.add(EntityCondition.makeCondition("statusId", EntityOperator.EQUALS, statusId));
        }

        EntityQuery q = EntityQuery.use(delegator).from("PayrollRetroBatch");
        if (!conds.isEmpty()) {
            q = q.where(EntityCondition.makeCondition(conds, EntityOperator.AND));
        }

        // Prefer createdDate if exists; otherwise lastUpdatedStamp
        List<GenericValue> list = q.orderBy("-createdDate", "-lastUpdatedStamp").queryList();

        Map<String, Object> out = ServiceUtil.returnSuccess();
        out.put("list", list);
        return out;

    } catch (Exception e) {
        Debug.logError(e, "getPayrollRetroBatchList failed", MODULE);
        return ServiceUtil.returnError("getPayrollRetroBatchList failed: " + e.getMessage());
    }
}

}
