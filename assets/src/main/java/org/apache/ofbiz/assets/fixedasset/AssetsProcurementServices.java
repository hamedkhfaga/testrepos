/* Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements. See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License. You may obtain a copy at http://www.apache.org/licenses/LICENSE-2.0
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 */
package org.apache.ofbiz.assets.fixedasset;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.apache.ofbiz.base.util.UtilDateTime;
import org.apache.ofbiz.entity.Delegator;
import org.apache.ofbiz.entity.GenericValue;
import org.apache.ofbiz.entity.util.EntityQuery;
import org.apache.ofbiz.service.DispatchContext;
import org.apache.ofbiz.service.ServiceUtil;

/** Atomic Assets-to-native-purchasing bridge. No purchasing entities are duplicated. */
public final class AssetsProcurementServices {
    private AssetsProcurementServices() { }

    static void lockRecord(GenericValue value) throws org.apache.ofbiz.entity.GenericEntityException {
        GenericValue audit = value.getDelegator().makeValue(value.getEntityName(), value.getPrimaryKey());
        audit.set("lastModifiedDate", UtilDateTime.nowTimestamp());
        audit.store();
        value.refresh();
    }


    public static Map<String, Object> assetsReceivePurchaseOrder(DispatchContext dctx, Map<String, ?> context) {
        try {
            Delegator delegator = dctx.getDelegator();
            GenericValue user = (GenericValue) context.get("userLogin");
            String requirementId = required(context, "requirementId");
            String orderId = required(context, "orderId");
            String seq = required(context, "orderItemSeqId");
            GenericValue detail = one(delegator, "AssetsRequestDetail", Map.of("requirementId", requirementId));
            String organization = detail.getString("partyId");
            AssetsRequestServices.requireOrganizationAccess(dctx, user, organization);
            one(delegator, "OrderRequirementCommitment", Map.of("requirementId", requirementId, "orderId", orderId, "orderItemSeqId", seq));
            GenericValue order = one(delegator, "OrderHeader", Map.of("orderId", orderId));
            if (!"PURCHASE_ORDER".equals(order.getString("orderTypeId")) || !"ORDER_APPROVED".equals(order.getString("statusId"))) {
                throw new IllegalArgumentException("The native purchase order must be approved before receipt.");
            }
            one(delegator, "OrderRole", Map.of("orderId", orderId, "partyId", organization, "roleTypeId", "BILL_TO_CUSTOMER"));
            GenericValue facility = one(delegator, "Facility", Map.of("facilityId", required(context, "facilityId")));
            if (!organization.equals(facility.getString("ownerPartyId"))) throw new IllegalArgumentException("The receiving facility must belong to the asset organization.");
            GenericValue item = one(delegator, "OrderItem", Map.of("orderId", orderId, "orderItemSeqId", seq));
            GenericValue nativeRequirement = one(delegator, "Requirement", Map.of("requirementId", requirementId));
            AssetsRequestServices.validateProcurementSnapshot(detail, nativeRequirement);
            if (!nativeRequirement.getString("productId").equals(item.getString("productId"))
                    || nativeRequirement.getBigDecimal("quantity").compareTo(item.getBigDecimal("quantity")) != 0) {
                throw new IllegalArgumentException("The purchase order line no longer agrees with the approved asset requirement.");
            }
            BigDecimal accepted = (BigDecimal) context.get("quantityAccepted");
            BigDecimal rejected = (BigDecimal) context.get("quantityRejected");
            if (accepted == null || rejected == null || accepted.signum() < 0 || rejected.signum() < 0 || accepted.add(rejected).signum() <= 0) {
                throw new IllegalArgumentException("Enter nonnegative accepted and rejected quantities, with a positive total.");
            }
            boolean dryRun = !"N".equals(context.get("dryRun"));
            if (!dryRun) { AssetsProcurementServices.lockRecord(detail); }
            BigDecimal received = BigDecimal.ZERO;
            for (GenericValue receipt : EntityQuery.use(delegator).from("ShipmentReceipt").where("orderId", orderId, "orderItemSeqId", seq).queryList()) {
                if (receipt.getBigDecimal("quantityAccepted") != null) received = received.add(receipt.getBigDecimal("quantityAccepted"));
            }
            BigDecimal cancelled = item.getBigDecimal("cancelQuantity") == null ? BigDecimal.ZERO : item.getBigDecimal("cancelQuantity");
            BigDecimal outstanding = item.getBigDecimal("quantity").subtract(cancelled).subtract(received);
            if (accepted.add(rejected).compareTo(outstanding) > 0) throw new IllegalArgumentException("Receipt exceeds the unreceived order quantity.");
            String inventoryType = required(context, "inventoryItemTypeId");
            one(delegator, "InventoryItemType", Map.of("inventoryItemTypeId", inventoryType));
            if ("SERIALIZED_INV_ITEM".equals(inventoryType)
                    && (accepted.compareTo(BigDecimal.ONE) != 0 || context.get("serialNumber") == null)) {
                throw new IllegalArgumentException("Receive one serialized asset at a time with its serial number.");
            }
            Map<String, Object> result = ServiceUtil.returnSuccess();
            result.put("outstandingQuantity", outstanding);
            result.put("dryRun", dryRun ? "Y" : "N");
            if (dryRun) return result;
            GenericValue vendor = EntityQuery.use(delegator).from("OrderRole").where("orderId", orderId, "roleTypeId", "BILL_FROM_VENDOR").queryFirst();
            if (vendor == null) throw new IllegalArgumentException("The purchase order has no vendor role.");
            Map<String, Object> shipment = call(dctx, "createShipment", Map.of("shipmentTypeId", "PURCHASE_SHIPMENT",
                    "primaryOrderId", orderId, "statusId", "PURCH_SHIP_CREATED", "partyIdFrom", vendor.getString("partyId"),
                    "partyIdTo", organization, "destinationFacilityId", facility.getString("facilityId"), "userLogin", user));
            String shipmentId = required(shipment, "shipmentId");
            Map<String, Object> receive = new HashMap<>();
            receive.put("userLogin", user);
            receive.put("orderId", orderId);
            receive.put("orderItemSeqId", seq);
            receive.put("shipmentId", shipmentId);
            receive.put("productId", item.getString("productId"));
            receive.put("facilityId", facility.getString("facilityId"));
            receive.put("ownerPartyId", organization);
            receive.put("inventoryItemTypeId", inventoryType);
            receive.put("quantityAccepted", accepted);
            receive.put("quantityRejected", rejected);
            receive.put("unitCost", item.getBigDecimal("unitPrice"));
            receive.put("currencyUomId", order.getString("currencyUom"));
            receive.put("datetimeReceived", UtilDateTime.nowTimestamp());
            for (String field : List.of("serialNumber", "rejectionId")) if (context.get(field) != null) receive.put(field, context.get(field));
            call(dctx, "receiveInventoryProduct", receive);
            call(dctx, "updateShipment", Map.of("shipmentId", shipmentId, "statusId", "PURCH_SHIP_RECEIVED", "userLogin", user));
            result.put("shipmentId", shipmentId);
            result.put("receipts", EntityQuery.use(delegator).from("ShipmentReceipt").where("shipmentId", shipmentId).queryList());
            return result;
        } catch (Exception e) {
            return ServiceUtil.returnError(e instanceof IllegalArgumentException ? e.getMessage()
                    : "Assets receipt failed. No shipment or inventory changes were committed.");
        }
    }

    public static Map<String, Object> assetsCreatePurchaseOrder(DispatchContext dctx, Map<String, ?> context) {
        try {
            Delegator delegator = dctx.getDelegator();
            GenericValue user = (GenericValue) context.get("userLogin");
            String requirementId = required(context, "requirementId");
            GenericValue detail = one(delegator, "AssetsRequestDetail", Map.of("requirementId", requirementId));
            String organization = detail.getString("partyId");
            AssetsRequestServices.requireOrganizationAccess(dctx, user, organization);
            if (!dctx.getSecurity().hasEntityPermission("ORDERMGR", "_CREATE", user)
                    && !dctx.getSecurity().hasEntityPermission("ORDERMGR", "_PURCHASE_CREATE", user)) {
                return ServiceUtil.returnError("Native purchase-order creation permission is required.");
            }
            // Updating our own row obtains a transaction lock, serializing requests
            // across all Assets purchase entry points, including concurrent retries.
            boolean dryRun = !"N".equals(context.get("dryRun"));
            if (!dryRun) {
                AssetsProcurementServices.lockRecord(detail);
            }
            GenericValue requirement = one(delegator, "Requirement", Map.of("requirementId", requirementId));
            AssetsRequestServices.validateProcurementSnapshot(detail, requirement);
            List<GenericValue> approvals = EntityQuery.use(delegator).from("AssetsApprovalDecision")
                    .where("requirementId", requirementId).queryList();
            if (!EntityQuery.use(delegator).from("OrderRequirementCommitment")
                    .where("requirementId", requirementId).queryList().isEmpty()) {
                throw new IllegalArgumentException("This requirement already has a purchase commitment. Open its existing order.");
            }
            requireApproved(detail.getString("statusId"), requirement.getString("statusId"),
                    approvals.stream().map(a -> a.getString("statusId")).toList());
            if (!"PRODUCT_REQUIREMENT".equals(requirement.getString("requirementTypeId"))) {
                throw new IllegalArgumentException("Purchasing requires an approved product requirement.");
            }
            String productId = required(requirement, "productId");
            one(delegator, "Product", Map.of("productId", productId));
            BigDecimal quantity = requirement.getBigDecimal("quantity");
            BigDecimal price = (BigDecimal) context.get("unitPrice");
            validateApprovedAmount(quantity, price, detail.getBigDecimal("estimatedCapitalizedCost"));
            String supplier = required(context, "supplierPartyId");
            one(delegator, "PartyRole", Map.of("partyId", supplier, "roleTypeId", "SUPPLIER"));
            String address = required(context, "shipToContactMechId");
            one(delegator, "PostalAddress", Map.of("contactMechId", address));
            if (EntityQuery.use(delegator).from("PartyContactMech")
                    .where("partyId", organization, "contactMechId", address).filterByDate().queryFirst() == null) {
                throw new IllegalArgumentException("The receiving address must belong to the requesting organization.");
            }
            String method = required(context, "shipmentMethodTypeId");
            String carrier = required(context, "carrierPartyId");
            one(delegator, "CarrierShipmentMethod", Map.of("shipmentMethodTypeId", method,
                    "partyId", carrier, "roleTypeId", "CARRIER"));
            List<Map<String, Object>> items = List.of(Map.of("productId", productId, "quantity", quantity,
                    "unitPrice", price, "orderItemTypeId", "PRODUCT_ORDER_ITEM"));
            Map<String, Object> totals = call(dctx, "uiCalculateOrderTotals", Map.of("partyId", supplier,
                    "orderTypeId", "PURCHASE_ORDER", "currencyUom", detail.getString("currencyUomId"),
                    "orderItems", items, "userLogin", user));
            BigDecimal total = (BigDecimal) totals.get("grandTotal");
            validateApprovedAmount(BigDecimal.ONE, total, detail.getBigDecimal("estimatedCapitalizedCost"));
            Map<String, Object> result = ServiceUtil.returnSuccess();
            result.put("requirementId", requirementId);
            result.put("quantity", quantity);
            result.put("orderTotal", total);
            result.put("currencyUomId", detail.getString("currencyUomId"));
            result.put("dryRun", dryRun ? "Y" : "N");
            if (dryRun) return result;

            Map<String, Object> order = new HashMap<>();
            order.put("userLogin", user);
            order.put("partyId", supplier);
            order.put("orderTypeId", "PURCHASE_ORDER");
            order.put("currencyUom", detail.getString("currencyUomId"));
            order.put("orderName", requirement.getString("description"));
            order.put("orderItems", items);
            order.put("orderAdjustments", new ArrayList<GenericValue>());
            order.put("shipToContactMechId", address);
            order.put("shipmentMethodTypeId", method);
            order.put("carrierPartyId", carrier);
            order.put("grandTotal", total);
            for (String role : List.of("billFromVendorPartyId", "shipFromVendorPartyId", "supplierAgentPartyId")) {
                order.put(role, supplier);
            }
            for (String role : List.of("placingCustomerPartyId", "billToCustomerPartyId",
                    "shipToCustomerPartyId", "endUserCustomerPartyId")) order.put(role, organization);
            if (requirement.getString("facilityId") != null) order.put("originFacilityId", requirement.getString("facilityId"));
            // Reuse the installed REST-safe order adapter, which computes native
            // prices/tax and calls storeOrder. No shared adapter code is changed.
            Map<String, Object> created = call(dctx, "uiCreateOrder", order);
            String orderId = required(created, "orderId");
            GenericValue header = one(delegator, "OrderHeader", Map.of("orderId", orderId));
            if (!"PURCHASE_ORDER".equals(header.getString("orderTypeId"))
                    || !detail.getString("currencyUomId").equals(header.getString("currencyUom"))) {
                throw new IllegalStateException("Native order did not match the approved purchase context.");
            }
            validateApprovedAmount(BigDecimal.ONE, header.getBigDecimal("grandTotal"), detail.getBigDecimal("estimatedCapitalizedCost"));
            call(dctx, "createOrderRequirementCommitment", Map.of("orderId", orderId, "orderItemSeqId", "00001",
                    "requirementId", requirementId, "quantity", quantity, "userLogin", user));
            call(dctx, "updateRequirement", Map.of("requirementId", requirementId, "statusId", "REQ_ORDERED", "userLogin", user));
            result.put("orderId", orderId);
            result.put("orderItemSeqId", "00001");
            return result;
        } catch (Exception e) {
            return ServiceUtil.returnError(e instanceof IllegalArgumentException ? e.getMessage()
                    : "Assets purchase creation failed. No purchase or requirement changes were committed.");
        }
    }

    static void requireApproved(String assetsStatus, String nativeStatus, List<String> decisions) {
        if (!"ASSETS_APPROVED".equals(assetsStatus) || !"REQ_APPROVED".equals(nativeStatus)
                || decisions.isEmpty() || decisions.stream().anyMatch(s -> !"ASSETS_APPROVED".equals(s))) {
            throw new IllegalArgumentException("All Assets approvals and native requirement approval must be complete before purchasing.");
        }
    }

    static void validateApprovedAmount(BigDecimal quantity, BigDecimal price, BigDecimal approved) {
        if (quantity == null || quantity.signum() <= 0 || price == null || price.signum() <= 0
                || approved == null || quantity.multiply(price).compareTo(approved) > 0) {
            throw new IllegalArgumentException("Positive quantity and price are required; the purchase amount cannot exceed the approved amount.");
        }
    }

    static String required(Map<String, ?> values, String field) {
        Object value = values.get(field);
        if (value == null || value.toString().isBlank()) throw new IllegalArgumentException("Missing required field: " + field);
        return value.toString();
    }

    static GenericValue one(Delegator delegator, String entity, Map<String, ?> key) throws Exception {
        GenericValue value = EntityQuery.use(delegator).from(entity).where(key).queryOne();
        if (value == null) throw new IllegalArgumentException("Missing " + entity + " reference.");
        return value;
    }

    static Map<String, Object> call(DispatchContext dctx, String name, Map<String, ?> input) throws Exception {
        Map<String, Object> result = dctx.getDispatcher().runSync(name, input);
        if (ServiceUtil.isError(result) || ServiceUtil.isFailure(result)) {
            throw new IllegalStateException("Native service failed: " + name);
        }
        return result;
    }
}
