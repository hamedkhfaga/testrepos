package org.apache.ofbiz.loan.services;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.apache.ofbiz.base.util.UtilMisc;
import org.apache.ofbiz.base.util.UtilValidate;
import org.apache.ofbiz.entity.Delegator;
import org.apache.ofbiz.entity.GenericValue;
import org.apache.ofbiz.entity.condition.EntityCondition;
import org.apache.ofbiz.entity.condition.EntityConditionList;
import org.apache.ofbiz.entity.condition.EntityExpr;
import org.apache.ofbiz.entity.condition.EntityOperator;
import org.apache.ofbiz.entity.util.EntityQuery;
import org.apache.ofbiz.service.DispatchContext;
import org.apache.ofbiz.service.ServiceUtil;

public final class PartyLovServices {

    private PartyLovServices() {}

 public static Map<String, Object> searchPartyLovV1(DispatchContext dctx, Map<String, ? extends Object> context) {
    Delegator delegator = dctx.getDelegator();

    String partyId = trim((String) context.get("partyId"));
    String query = trim((String) context.get("query"));
    String roleTypeId = trim((String) context.get("roleTypeId"));

    @SuppressWarnings("unchecked")
    List<String> roleTypeIds = (List<String>) context.get("roleTypeIds");

    Integer viewIndex = (Integer) context.get("viewIndex");
    Integer viewSize = (Integer) context.get("viewSize");
    if (viewIndex == null || viewIndex < 0) {
        viewIndex = 0;
    }
    if (viewSize == null || viewSize <= 0) {
        viewSize = 20;
    }

    try {
        List<EntityCondition> conds = new ArrayList<>();

        if (UtilValidate.isNotEmpty(partyId)) {
            conds.add(EntityCondition.makeCondition("partyId", EntityOperator.LIKE, partyId + "%"));
        }

        if (UtilValidate.isNotEmpty(query)) {
            conds.add(EntityCondition.makeCondition(
                UtilMisc.toList(
                    EntityCondition.makeCondition("partyId", EntityOperator.LIKE, "%" + query + "%"),
                    EntityCondition.makeCondition("firstName", EntityOperator.LIKE, "%" + query + "%"),
                    EntityCondition.makeCondition("lastName", EntityOperator.LIKE, "%" + query + "%"),
                    EntityCondition.makeCondition("groupName", EntityOperator.LIKE, "%" + query + "%")
                ),
                EntityOperator.OR
            ));
        }

        List<String> effectiveRoleTypeIds = new ArrayList<>();
        if (UtilValidate.isNotEmpty(roleTypeIds)) {
            for (String item : roleTypeIds) {
                String trimmed = trim(item);
                if (UtilValidate.isNotEmpty(trimmed)) {
                    effectiveRoleTypeIds.add(trimmed);
                }
            }
        }
        if (effectiveRoleTypeIds.isEmpty() && UtilValidate.isNotEmpty(roleTypeId)) {
            effectiveRoleTypeIds.add(roleTypeId);
        }

        if (!effectiveRoleTypeIds.isEmpty()) {
            List<GenericValue> partyRoleRows = EntityQuery.use(delegator)
                .from("PartyRole")
                .where(EntityCondition.makeCondition("roleTypeId", EntityOperator.IN, effectiveRoleTypeIds))
                .queryList();

            List<String> roleFilteredPartyIds = partyRoleRows.stream()
                .map(gv -> gv.getString("partyId"))
                .filter(UtilValidate::isNotEmpty)
                .distinct()
                .toList();

            if (roleFilteredPartyIds.isEmpty()) {
                Map<String, Object> result = ServiceUtil.returnSuccess();
                result.put("partyList", new ArrayList<Map<String, Object>>());
                result.put("partyCount", 0);
                return result;
            }

            conds.add(EntityCondition.makeCondition("partyId", EntityOperator.IN, roleFilteredPartyIds));
        }

        EntityCondition whereCond = conds.isEmpty()
            ? null
            : EntityCondition.makeCondition(conds, EntityOperator.AND);

        List<GenericValue> rows = EntityQuery.use(delegator)
            .from("PartyNameView")
            .where(whereCond)
            .orderBy("partyId")
            .maxRows(viewSize)
            .offset(viewIndex * viewSize)
            .queryList();

        List<Map<String, Object>> partyList = new ArrayList<>();
        for (GenericValue row : rows) {
            Map<String, Object> item = new HashMap<>();
            item.put("partyId", row.getString("partyId"));
            item.put("partyName", buildPartyName(row));
            item.put("partyTypeId", row.getString("partyTypeId"));
            item.put("statusId", row.getString("statusId"));
            partyList.add(item);
        }

        Map<String, Object> result = ServiceUtil.returnSuccess();
        result.put("partyList", partyList);
        result.put("partyCount", partyList.size());
        return result;

    } catch (Exception e) {
        return ServiceUtil.returnError("searchPartyLovV1 failed: " + e.getMessage());
    }
}
    private static String trim(String s) {
        return s == null ? "" : s.trim();
    }

    private static String buildPartyName(GenericValue row) {
        String groupName = row.getString("groupName");
        if (UtilValidate.isNotEmpty(groupName)) return groupName;

        String firstName = row.getString("firstName");
        String lastName = row.getString("lastName");
        String full = ((firstName == null ? "" : firstName) + " " + (lastName == null ? "" : lastName)).trim();
        return UtilValidate.isNotEmpty(full) ? full : row.getString("partyId");
    }
}