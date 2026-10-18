/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */
package org.apache.ofbiz.assets.fixedasset;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import org.apache.ofbiz.base.util.Debug;
import org.apache.ofbiz.base.util.UtilDateTime;
import org.apache.ofbiz.base.util.UtilProperties;
import org.apache.ofbiz.base.util.UtilValidate;
import org.apache.ofbiz.entity.Delegator;
import org.apache.ofbiz.entity.GenericValue;
import org.apache.ofbiz.entity.util.EntityQuery;
import org.apache.ofbiz.service.DispatchContext;
import org.apache.ofbiz.service.ServiceUtil;

/** Assets-specific orchestration; native OFBiz owns the Requirement header. */
public final class AssetsRequestServices {
    private static final String MODULE = AssetsRequestServices.class.getName();
    private static final List<String> DETAIL_FIELDS = List.of("partyId", "roleTypeId", "fixedAssetTypeId",
            "requestTypeEnumId", "priorityEnumId", "budgetStatusEnumId", "currencyUomId",
            "estimatedCapitalizedCost", "requestedInServiceDate", "comments");

    private AssetsRequestServices() { }

    public static Map<String, Object> assetsGetRequestSetup(DispatchContext dctx, Map<String, ?> context) {
        try {
            GenericValue user = (GenericValue) context.get("userLogin");
            boolean admin = dctx.getSecurity().hasEntityPermission("ASSETS", "_ADMIN", user);
            Delegator delegator = dctx.getDelegator();
            List<GenericValue> access = EntityQuery.use(delegator).from("AssetsOrganizationAccess")
                    .where("userLoginId", user.getString("userLoginId")).filterByDate().queryList();
            List<Map<String, Object>> organizations = new ArrayList<>();
            for (GenericValue role : EntityQuery.use(delegator).from("PartyRole")
                    .where("roleTypeId", "INTERNAL_ORGANIZATIO").queryList()) {
                String id = role.getString("partyId");
                if (!admin && access.stream().noneMatch(a -> id.equals(a.getString("partyId")))) continue;
                GenericValue group = EntityQuery.use(delegator).from("PartyGroup").where("partyId", id).queryOne();
                organizations.add(Map.of("partyId", id, "name", group == null ? id : group.getString("groupName"),
                        "roleTypeId", role.getString("roleTypeId")));
            }
            Map<String, Object> result = ServiceUtil.returnSuccess();
            result.put("organizations", organizations);
            result.put("assetTypes", EntityQuery.use(delegator).from("FixedAssetType").orderBy("description").queryList());
            result.put("requirementTypes", EntityQuery.use(delegator).from("RequirementType").orderBy("description").queryList());
            result.put("currencies", EntityQuery.use(delegator).from("Uom").where("uomTypeId", "CURRENCY_MEASURE").orderBy("uomId").queryList());
            result.put("requestTypes", enums(delegator, "ASSETS_REQ_TYPE"));
            result.put("priorities", enums(delegator, "ASSETS_PRIORITY"));
            result.put("budgetStatuses", enums(delegator, "ASSETS_BUDGET_TYP"));
            return result;
        } catch (Exception e) {
            return failure(context, e);
        }
    }

    public static Map<String, Object> assetsCreateAssetRequest(DispatchContext dctx, Map<String, ?> context) {
        try {
            Delegator delegator = dctx.getDelegator();
            GenericValue user = (GenericValue) context.get("userLogin");
            String partyId = required(context, "partyId");
            String roleTypeId = required(context, "roleTypeId");
            requireOrganizationAccess(dctx, user, partyId);
            requireRow(delegator, "PartyRole", Map.of("partyId", partyId, "roleTypeId", roleTypeId));
            requireRow(delegator, "FixedAssetType", Map.of("fixedAssetTypeId", required(context, "fixedAssetTypeId")));
            requireRow(delegator, "RequirementType", Map.of("requirementTypeId", required(context, "requirementTypeId")));
            String description = required(context, "description");
            requireEnum(delegator, required(context, "requestTypeEnumId"), "ASSETS_REQ_TYPE");
            requireEnum(delegator, required(context, "priorityEnumId"), "ASSETS_PRIORITY");
            requireEnum(delegator, required(context, "budgetStatusEnumId"), "ASSETS_BUDGET_TYP");
            requireRow(delegator, "Uom", Map.of("uomId", required(context, "currencyUomId"), "uomTypeId", "CURRENCY_MEASURE"));
            BigDecimal amount = (BigDecimal) context.get("estimatedCapitalizedCost");
            if (amount == null || amount.signum() <= 0) throw new IllegalArgumentException("AssetsRequestPositiveCost");
            if ("PRODUCT_REQUIREMENT".equals(context.get("requirementTypeId"))) {
                requireRow(delegator, "Product", Map.of("productId", required(context, "productId")));
                BigDecimal quantity = (BigDecimal) context.get("quantity");
                if (quantity == null || quantity.signum() <= 0) throw new IllegalArgumentException("AssetsRequestPositiveQuantity");
            }
            if (UtilValidate.isNotEmpty(context.get("facilityId"))) {
                GenericValue facility = EntityQuery.use(delegator).from("Facility")
                        .where("facilityId", context.get("facilityId")).queryOne();
                if (facility == null || !partyId.equals(facility.getString("ownerPartyId"))) {
                    throw new IllegalArgumentException("AssetsRequestInvalidFacility");
                }
            }
            Timestamp now = UtilDateTime.nowTimestamp();
            String submissionKey = required(context, "submissionKey");
            if (submissionKey.length() > 60) throw new IllegalArgumentException("AssetsRequestInvalidKey");
            String fingerprint = fingerprint(context);
            GenericValue previous = EntityQuery.use(delegator).from("AssetsRequestDetail")
                    .where("submissionKey", submissionKey).queryOne();
            if (previous != null) {
                if (!user.getString("userLoginId").equals(previous.getString("createdByUserLogin"))
                        || !fingerprint.equals(previous.getString("submissionHash"))) {
                    throw new IllegalArgumentException("AssetsRequestKeyConflict");
                }
                Map<String, Object> result = ServiceUtil.returnSuccess();
                result.put("requirementId", previous.getString("requirementId"));
                result.put("requestStatusId", previous.getString("statusId"));
                result.put("replayed", "Y");
                return result;
            }

            List<GenericValue> rules = EntityQuery.use(delegator).from("AssetsApprovalRule")
                    .where("partyId", partyId, "statusId", "ASSETS_ACTIVE").filterByDate().queryList();
            List<Map<String, Object>> tasks = new ArrayList<>();
            for (GenericValue rule : rules) {
                if (!matches(rule, context, amount)) continue;
                String approver = rule.getString("approverPartyId");
                String approverRole = rule.getString("approverRoleTypeId");
                if (UtilValidate.isEmpty(approver) || UtilValidate.isEmpty(approverRole)
                        || rule.getLong("sequenceNum") == null || rule.getLong("sequenceNum") <= 0) {
                    throw new IllegalArgumentException("AssetsRequestRouteIncomplete");
                }
                requireRow(delegator, "PartyRole", Map.of("partyId", approver, "roleTypeId", approverRole));
                // Every matching rule is a required decision; parallel rules may share a sequence.
                tasks.add(Map.of("assetsApprovalRuleId", rule.getString("assetsApprovalRuleId"),
                        "approverPartyId", approver, "approverRoleTypeId", approverRole,
                        "sequenceNum", rule.getLong("sequenceNum")));
            }
            if (tasks.isEmpty()) throw new IllegalArgumentException("AssetsRequestNoApprovalRoute");
            tasks.sort(Comparator.comparingLong(t -> (Long) t.get("sequenceNum")));
            Map<String, Object> result = ServiceUtil.returnSuccess();
            result.put("approvalTasks", tasks);
            result.put("requestStatusId", "ASSETS_PENDING");
            result.put("dryRun", "N".equals(context.get("dryRun")) ? "N" : "Y");
            if (!"N".equals(context.get("dryRun"))) return result;

            Map<String, Object> nativeContext = new HashMap<>();
            nativeContext.put("requirementTypeId", context.get("requirementTypeId"));
            nativeContext.put("description", description);
            nativeContext.put("estimatedBudget", amount);
            nativeContext.put("requiredByDate", context.get("requestedInServiceDate"));
            for (String field : List.of("productId", "quantity", "facilityId")) {
                if (context.get(field) != null) nativeContext.put(field, context.get(field));
            }
            nativeContext.put("userLogin", user);
            Map<String, Object> nativeResult = dctx.getDispatcher().runSync("createRequirement", nativeContext);
            if (ServiceUtil.isError(nativeResult)) return nativeResult;
            if (UtilValidate.isEmpty(nativeResult.get("requirementId"))) throw new IllegalStateException("Native service returned no requirement ID");
            String requirementId = (String) nativeResult.get("requirementId");
            GenericValue detail = delegator.makeValue("AssetsRequestDetail");
            for (String field : DETAIL_FIELDS) detail.set(field, context.get(field));
            detail.set("requirementId", requirementId);
            detail.set("statusId", "ASSETS_PENDING");
            detail.set("submissionKey", submissionKey);
            detail.set("submissionHash", fingerprint);
            detail.set("createdDate", now);
            detail.set("createdByUserLogin", user.getString("userLoginId"));
            detail.set("lastModifiedDate", now);
            detail.set("lastModifiedByUserLogin", user.getString("userLoginId"));
            detail.create();
            for (Map<String, Object> task : tasks) {
                GenericValue decision = delegator.makeValue("AssetsApprovalDecision", task);
                decision.set("assetsApprovalDecisionId", delegator.getNextSeqId("AssetsApprovalDecision"));
                decision.set("requirementId", requirementId);
                decision.set("statusId", "ASSETS_PENDING");
                decision.set("createdDate", now);
                decision.set("createdByUserLogin", user.getString("userLoginId"));
                decision.create();
            }
            GenericValue event = delegator.makeValue("AssetsLifecycleEvent");
            event.set("assetsLifecycleEventId", delegator.getNextSeqId("AssetsLifecycleEvent"));
            event.set("requirementId", requirementId);
            event.set("eventTypeEnumId", "ASSETS_EVT_REQUEST");
            event.set("statusId", "ASSETS_PENDING");
            event.set("eventDate", now);
            event.set("description", description);
            event.set("createdDate", now);
            event.set("createdByUserLogin", user.getString("userLoginId"));
            event.create();
            result.put("requirementId", requirementId);
            result.put("replayed", "N");
            return result;
        } catch (Exception e) {
            return failure(context, e);
        }
    }

    public static Map<String, Object> assetsSaveApprovalRule(DispatchContext dctx, Map<String, ?> context) {
        try {
            Delegator delegator = dctx.getDelegator();
            String id = required(context, "assetsApprovalRuleId");
            String partyId = required(context, "partyId");
            requireRow(delegator, "PartyRole", Map.of("partyId", partyId, "roleTypeId", "INTERNAL_ORGANIZATIO"));
            requireRow(delegator, "PartyRole", Map.of("partyId", partyId, "roleTypeId", required(context, "roleTypeId")));
            requireRow(delegator, "PartyRole", Map.of("partyId", required(context, "approverPartyId"),
                    "roleTypeId", required(context, "approverRoleTypeId")));
            if (context.get("sequenceNum") == null || (Long) context.get("sequenceNum") <= 0) {
                throw new IllegalArgumentException("AssetsRequestRouteIncomplete");
            }
            for (String field : List.of("requestTypeEnumId", "budgetStatusEnumId")) {
                if (UtilValidate.isNotEmpty(context.get(field))) requireEnum(delegator, (String) context.get(field),
                        field.equals("requestTypeEnumId") ? "ASSETS_REQ_TYPE" : "ASSETS_BUDGET_TYP");
            }
            if (UtilValidate.isNotEmpty(context.get("fixedAssetTypeId"))) requireRow(delegator, "FixedAssetType",
                    Map.of("fixedAssetTypeId", context.get("fixedAssetTypeId")));
            BigDecimal min = (BigDecimal) context.get("minAmount");
            BigDecimal max = (BigDecimal) context.get("maxAmount");
            if ((min != null && min.signum() < 0) || (max != null && max.signum() < 0)
                    || (min != null && max != null && min.compareTo(max) > 0)
                    || ((min != null || max != null) && UtilValidate.isEmpty(context.get("currencyUomId")))) {
                throw new IllegalArgumentException("AssetsRequestRuleRange");
            }
            if (UtilValidate.isNotEmpty(context.get("currencyUomId"))) requireRow(delegator, "Uom",
                    Map.of("uomId", context.get("currencyUomId"), "uomTypeId", "CURRENCY_MEASURE"));
            Timestamp fromDate = (Timestamp) context.get("fromDate");
            Timestamp thruDate = (Timestamp) context.get("thruDate");
            if (fromDate != null && thruDate != null && !thruDate.after(fromDate)) throw new IllegalArgumentException("AssetsRequestRuleRange");
            requireRow(delegator, "StatusItem", Map.of("statusId", required(context, "statusId"), "statusTypeId", "ASSETS_STATUS"));
            GenericValue rule = EntityQuery.use(delegator).from("AssetsApprovalRule").where("assetsApprovalRuleId", id).queryOne();
            boolean create = rule == null;
            if (create) rule = delegator.makeValue("AssetsApprovalRule", Map.of("assetsApprovalRuleId", id));
            for (String field : List.of("partyId", "roleTypeId", "fixedAssetTypeId", "requestTypeEnumId", "budgetStatusEnumId",
                    "minAmount", "maxAmount", "currencyUomId", "approverPartyId", "approverRoleTypeId", "sequenceNum", "statusId",
                    "fromDate", "thruDate", "description", "comments")) rule.set(field, context.get(field));
            if (create) rule.create(); else rule.store();
            Map<String, Object> result = ServiceUtil.returnSuccess();
            result.put("assetsApprovalRuleId", id);
            return result;
        } catch (Exception e) { return failure(context, e); }
    }

    static boolean matches(Map<String, ?> rule, Map<String, ?> request, BigDecimal amount) {
        for (String field : List.of("roleTypeId", "fixedAssetTypeId", "requestTypeEnumId", "budgetStatusEnumId", "currencyUomId")) {
            Object value = rule.get(field);
            if (value != null && !value.toString().isBlank() && !Objects.equals(value, request.get(field))) return false;
        }
        BigDecimal min = (BigDecimal) rule.get("minAmount");
        BigDecimal max = (BigDecimal) rule.get("maxAmount");
        // Monetary thresholds are never compared across currencies.
        if ((min != null || max != null) && UtilValidate.isEmpty(rule.get("currencyUomId"))) return false;
        return (min == null || amount.compareTo(min) >= 0) && (max == null || amount.compareTo(max) <= 0);
    }

    static void requireOrganizationAccess(DispatchContext dctx, GenericValue user, String partyId) throws Exception {
        requireRow(dctx.getDelegator(), "PartyRole", Map.of("partyId", partyId, "roleTypeId", "INTERNAL_ORGANIZATIO"));
        if (dctx.getSecurity().hasEntityPermission("ASSETS", "_ADMIN", user)) return;
        if (EntityQuery.use(dctx.getDelegator()).from("AssetsOrganizationAccess")
                .where("partyId", partyId, "userLoginId", user.getString("userLoginId")).filterByDate().queryFirst() == null) {
            throw new IllegalArgumentException("AssetsRequestOrganizationDenied");
        }
    }

    private static List<GenericValue> enums(Delegator delegator, String type) throws Exception {
        return EntityQuery.use(delegator).from("Enumeration").where("enumTypeId", type).orderBy("sequenceId", "enumId").queryList();
    }

    private static void requireEnum(Delegator delegator, String id, String type) throws Exception {
        requireRow(delegator, "Enumeration", Map.of("enumId", id, "enumTypeId", type));
    }

    private static void requireRow(Delegator delegator, String entity, Map<String, ?> fields) throws Exception {
        if (EntityQuery.use(delegator).from(entity).where(fields).queryFirst() == null) {
            throw new IllegalArgumentException("AssetsRequestInvalidReference");
        }
    }

    private static String required(Map<String, ?> context, String field) {
        Object value = context.get(field);
        if (value == null || value.toString().isBlank()) throw new IllegalArgumentException("AssetsRequestMissingField");
        return value.toString().trim();
    }

    static String fingerprint(Map<String, ?> context) throws Exception {
        Map<String, String> values = new LinkedHashMap<>();
        for (String field : DETAIL_FIELDS) {
            Object value = context.get(field);
            values.put(field, value instanceof BigDecimal ? ((BigDecimal) value).stripTrailingZeros().toPlainString() : Objects.toString(value, ""));
        }
        values.put("description", Objects.toString(context.get("description"), "").trim());
        values.put("requirementTypeId", Objects.toString(context.get("requirementTypeId"), ""));
        for (String field : List.of("productId", "quantity", "facilityId")) {
            Object value = context.get(field);
            if (value != null) values.put(field, value instanceof BigDecimal
                    ? ((BigDecimal) value).stripTrailingZeros().toPlainString() : value.toString());
        }
        // Length-delimited values avoid ambiguity from user-supplied separator characters.
        StringBuilder data = new StringBuilder();
        values.forEach((k, v) -> data.append(k.length()).append(':').append(k).append(v.length()).append(':').append(v));
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(data.toString().getBytes(StandardCharsets.UTF_8)));
    }

    static void validateProcurementSnapshot(Map<String, ?> detail, Map<String, ?> requirement) throws Exception {
        Map<String, Object> original = new HashMap<>();
        for (String field : DETAIL_FIELDS) original.put(field, detail.get(field));
        for (String field : List.of("description", "requirementTypeId", "productId", "quantity", "facilityId")) {
            original.put(field, requirement.get(field));
        }
        if (!fingerprint(original).equals(detail.get("submissionHash"))) {
            throw new IllegalArgumentException("The requirement changed after Assets submission or has no approval snapshot. Amend and reapprove it before purchasing or capitalization.");
        }
    }

    static Map<String, Object> failure(Map<String, ?> context, Exception e) {
        Debug.logError(e, "Assets workflow service failed", MODULE);
        java.util.Locale locale = context.get("locale") instanceof java.util.Locale ? (java.util.Locale) context.get("locale") : java.util.Locale.ENGLISH;
        String key = e instanceof IllegalArgumentException ? e.getMessage() : "AssetsRequestServiceFailed";
        return ServiceUtil.returnError(UtilProperties.getMessage("AssetsUiLabels", key, locale));
    }
}
