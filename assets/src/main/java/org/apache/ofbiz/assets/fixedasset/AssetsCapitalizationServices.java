/* Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements. See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0.
 * You may obtain a copy at http://www.apache.org/licenses/LICENSE-2.0
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 */
package org.apache.ofbiz.assets.fixedasset;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.apache.ofbiz.base.util.UtilDateTime;
import org.apache.ofbiz.entity.Delegator;
import org.apache.ofbiz.entity.GenericValue;
import org.apache.ofbiz.entity.util.EntityQuery;
import org.apache.ofbiz.service.DispatchContext;
import org.apache.ofbiz.service.ServiceUtil;
import static org.apache.ofbiz.assets.fixedasset.AssetsProcurementServices.*;

/** Convert received, inspected, invoiced stock to a native fixed asset atomically. */
public final class AssetsCapitalizationServices {
    private AssetsCapitalizationServices() { }

    public static Map<String, Object> assetsInspectReceipt(DispatchContext dctx, Map<String, ?> context) {
        try {
            Delegator d = dctx.getDelegator();
            GenericValue user = (GenericValue) context.get("userLogin");
            String reqId = required(context, "requirementId");
            GenericValue detail = one(d, "AssetsRequestDetail", Map.of("requirementId", reqId));
            AssetsRequestServices.requireOrganizationAccess(dctx, user, detail.getString("partyId"));
            AssetsProcurementServices.lockRecord(detail);
            String receiptId = required(context, "receiptId");
            GenericValue receipt = one(d, "ShipmentReceipt", Map.of("receiptId", receiptId));
            one(d, "OrderRequirementCommitment", Map.of("requirementId", reqId,
                    "orderId", receipt.getString("orderId"), "orderItemSeqId", receipt.getString("orderItemSeqId")));
            if (!EntityQuery.use(d).from("AssetsCapitalization").where("receiptId", receiptId).queryList().isEmpty()) {
                throw new IllegalArgumentException("An already-capitalized receipt cannot be reinspected.");
            }
            one(d, "OrderRole", Map.of("orderId", receipt.getString("orderId"), "partyId", detail.getString("partyId"), "roleTypeId", "BILL_TO_CUSTOMER"));
            GenericValue stock = one(d, "InventoryItem", Map.of("inventoryItemId", receipt.getString("inventoryItemId")));
            if (!detail.getString("partyId").equals(stock.getString("ownerPartyId"))) throw new IllegalArgumentException("Receipt stock belongs to a different organization.");
            String status = required(context, "statusId");
            if (!List.of("ASSETS_APPROVED", "ASSETS_REJECTED").contains(status)) throw new IllegalArgumentException("Choose an accepted or rejected inspection outcome.");
            if (receipt.getBigDecimal("quantityAccepted") == null || receipt.getBigDecimal("quantityAccepted").signum() <= 0) {
                throw new IllegalArgumentException("The receipt has no accepted stock to inspect.");
            }
            GenericValue inspection = d.makeValue("AssetsReceiptInspection");
            inspection.set("receiptId", receiptId);
            inspection.set("inspectionDate", UtilDateTime.nowTimestamp());
            inspection.set("requirementId", reqId);
            inspection.set("partyId", detail.getString("partyId"));
            inspection.set("statusId", status);
            inspection.set("comments", required(context, "comments"));
            inspection.set("inspectedByUserLogin", user.getString("userLoginId"));
            inspection.create();
            return ServiceUtil.returnSuccess();
        } catch (Exception e) { return error(e); }
    }

    public static Map<String, Object> assetsCapitalizeReceipt(DispatchContext dctx, Map<String, ?> context) {
        try {
            Delegator d = dctx.getDelegator();
            GenericValue user = (GenericValue) context.get("userLogin");
            String reqId = required(context, "requirementId");
            GenericValue detail = one(d, "AssetsRequestDetail", Map.of("requirementId", reqId));
            String organization = detail.getString("partyId");
            AssetsRequestServices.requireOrganizationAccess(dctx, user, organization);
            boolean dryRun = !"N".equals(context.get("dryRun"));
            if (!dryRun) { AssetsProcurementServices.lockRecord(detail); }
            if (!"ASSETS_APPROVED".equals(detail.getString("statusId"))) throw new IllegalArgumentException("The Assets request must remain approved.");
            List<GenericValue> decisions = EntityQuery.use(d).from("AssetsApprovalDecision").where("requirementId", reqId).queryList();
            if (decisions.isEmpty() || decisions.stream().anyMatch(a -> !"ASSETS_APPROVED".equals(a.getString("statusId")))) {
                throw new IllegalArgumentException("All Assets approval tasks must be approved.");
            }
            String receiptId = required(context, "receiptId");
            GenericValue receipt = one(d, "ShipmentReceipt", Map.of("receiptId", receiptId));
            one(d, "OrderRequirementCommitment", Map.of("requirementId", reqId,
                    "orderId", receipt.getString("orderId"), "orderItemSeqId", receipt.getString("orderItemSeqId")));
            GenericValue inspection = EntityQuery.use(d).from("AssetsReceiptInspection").where("receiptId", receiptId).orderBy("-inspectionDate").queryFirst();
            if (inspection == null || !"ASSETS_APPROVED".equals(inspection.getString("statusId"))) {
                throw new IllegalArgumentException("An accepted Assets inspection is required before capitalization.");
            }
            if (!dryRun) { AssetsProcurementServices.lockRecord(inspection); }
            BigDecimal used = BigDecimal.ZERO;
            for (GenericValue cap : EntityQuery.use(d).from("AssetsCapitalization").where("receiptId", receiptId).queryList()) {
                used = used.add(cap.getBigDecimal("quantity") == null ? BigDecimal.ONE : cap.getBigDecimal("quantity"));
            }
            if (receipt.getBigDecimal("quantityAccepted").subtract(used).compareTo(BigDecimal.ONE) < 0) {
                throw new IllegalArgumentException("All accepted units on this receipt are already capitalized.");
            }
            String invoiceId = required(context, "invoiceId");
            String invoiceSeq = required(context, "invoiceItemSeqId");
            GenericValue invoice = one(d, "Invoice", Map.of("invoiceId", invoiceId));
            if (!"PURCHASE_INVOICE".equals(invoice.getString("invoiceTypeId"))
                    || !organization.equals(invoice.getString("partyId"))
                    || !List.of("INVOICE_READY", "INVOICE_PAID").contains(invoice.getString("statusId"))) {
                throw new IllegalArgumentException("A ready or paid purchase invoice for the asset organization is required.");
            }
            GenericValue billing = one(d, "OrderItemBilling", Map.of("orderId", receipt.getString("orderId"),
                    "orderItemSeqId", receipt.getString("orderItemSeqId"), "invoiceId", invoiceId, "invoiceItemSeqId", invoiceSeq));
            if (billing.getString("shipmentReceiptId") != null && !receiptId.equals(billing.getString("shipmentReceiptId"))) {
                throw new IllegalArgumentException("Invoice billing belongs to a different receipt.");
            }
            BigDecimal billedUsed = BigDecimal.ZERO;
            for (GenericValue cap : EntityQuery.use(d).from("AssetsCapitalization")
                    .where("invoiceId", invoiceId, "invoiceItemSeqId", invoiceSeq).queryList()) {
                billedUsed = billedUsed.add(cap.getBigDecimal("quantity") == null ? BigDecimal.ONE : cap.getBigDecimal("quantity"));
            }
            if (billing.getBigDecimal("quantity") == null || billing.getBigDecimal("quantity").subtract(billedUsed).compareTo(BigDecimal.ONE) < 0) {
                throw new IllegalArgumentException("The linked invoice has no remaining billed unit available for capitalization.");
            }
            boolean postedInvoice = false;
            for (GenericValue journal : EntityQuery.use(d).from("AcctgTrans").where("invoiceId", invoiceId).queryList()) {
                if ("Y".equals(journal.getString("isPosted")) && "ACTUAL".equals(journal.getString("glFiscalTypeId"))) {
                    if (EntityQuery.use(d).from("AcctgTransEntry").where("acctgTransId", journal.getString("acctgTransId"),
                            "organizationPartyId", organization).queryFirst() != null) postedInvoice = true;
                }
            }
            if (!postedInvoice) throw new IllegalArgumentException("Supplier invoice accounting must be posted before capitalization.");
            GenericValue invoiceItem = one(d, "InvoiceItem", Map.of("invoiceId", invoiceId, "invoiceItemSeqId", invoiceSeq));
            if (invoiceItem.getBigDecimal("quantity") == null || invoiceItem.getBigDecimal("quantity").subtract(billedUsed).compareTo(BigDecimal.ONE) < 0) {
                throw new IllegalArgumentException("The invoice item has no remaining unit available for capitalization.");
            }
            GenericValue vendor = EntityQuery.use(d).from("OrderRole").where("orderId", receipt.getString("orderId"), "roleTypeId", "BILL_FROM_VENDOR").queryFirst();
            if (vendor == null || !vendor.getString("partyId").equals(invoice.getString("partyIdFrom"))) {
                throw new IllegalArgumentException("Invoice supplier does not match the purchase order vendor.");
            }
            GenericValue inventory = one(d, "InventoryItem", Map.of("inventoryItemId", receipt.getString("inventoryItemId")));
            if (!organization.equals(inventory.getString("ownerPartyId")) || !receipt.getString("productId").equals(invoiceItem.getString("productId"))) {
                throw new IllegalArgumentException("Receipt stock, invoice product and asset organization do not match.");
            }
            boolean serialized = "SERIALIZED_INV_ITEM".equals(inventory.getString("inventoryItemTypeId"));
            if (!serialized && !"NON_SERIAL_INV_ITEM".equals(inventory.getString("inventoryItemTypeId"))) throw new IllegalArgumentException("Unsupported received inventory type.");
            if (serialized && (!"INV_AVAILABLE".equals(inventory.getString("statusId")) || inventory.getString("serialNumber") == null)) {
                throw new IllegalArgumentException("Serialized capitalization requires an available received item with a serial number.");
            }
            if (inventory.getBigDecimal("quantityOnHandTotal").compareTo(BigDecimal.ONE) < 0
                    || inventory.getBigDecimal("availableToPromiseTotal").compareTo(BigDecimal.ONE) < 0) {
                throw new IllegalArgumentException("An unreserved received unit must be available for capitalization.");
            }
            String bookId = required(context, "assetsBookId");
            GenericValue book = one(d, "AssetsBook", Map.of("assetsBookId", bookId));
            if (!organization.equals(book.getString("partyId")) || !"Y".equals(book.getString("isPostingBook"))) {
                throw new IllegalArgumentException("Select the organization's posting book for capitalization.");
            }
            String currency = book.getString("currencyUomId");
            if (!currency.equals(inventory.getString("currencyUomId")) || !currency.equals(invoice.getString("currencyUomId"))) {
                throw new IllegalArgumentException("Receipt, invoice and book currencies must match; foreign-currency valuation must be resolved first.");
            }
            BigDecimal cost = inventory.getBigDecimal("unitCost");
            if (cost == null || cost.signum() <= 0 || invoiceItem.getBigDecimal("amount").compareTo(cost) != 0) {
                throw new IllegalArgumentException("Invoice unit amount must agree with received inventory unit cost before capitalization.");
            }
            Map<String, BigDecimal> receiptDebits = new HashMap<>();
            for (GenericValue receiptJournal : EntityQuery.use(d).from("AcctgTrans")
                    .where("shipmentId", receipt.getString("shipmentId"), "acctgTransTypeId", "SHIPMENT_RECEIPT", "isPosted", "Y").queryList()) {
                for (GenericValue line : EntityQuery.use(d).from("AcctgTransEntry")
                        .where("acctgTransId", receiptJournal.getString("acctgTransId"), "organizationPartyId", organization,
                                "productId", receipt.getString("productId"), "debitCreditFlag", "D", "glAccountTypeId", "INVENTORY_ACCOUNT").queryList()) {
                    if (currency.equals(line.getString("currencyUomId"))) receiptDebits.merge(line.getString("glAccountId"), line.getBigDecimal("amount"), BigDecimal::add);
                }
            }
            if (receiptDebits.size() != 1 || receiptDebits.values().iterator().next().compareTo(cost) < 0) {
                throw new IllegalArgumentException("A posted receipt inventory valuation in the book currency is required before capitalization.");
            }
            String sourceAccount = receiptDebits.keySet().iterator().next();
            BigDecimal priorCost = BigDecimal.ZERO;
            BigDecimal priorQuantity = BigDecimal.ZERO;
            for (GenericValue cap : EntityQuery.use(d).from("AssetsCapitalization").where("requirementId", reqId).queryList()) {
                priorCost = priorCost.add(cap.getBigDecimal("capitalizedAmount"));
                priorQuantity = priorQuantity.add(cap.getBigDecimal("quantity") == null ? BigDecimal.ONE : cap.getBigDecimal("quantity"));
            }
            GenericValue nativeRequirement = one(d, "Requirement", Map.of("requirementId", reqId));
            AssetsRequestServices.validateProcurementSnapshot(detail, nativeRequirement);
            if (!receipt.getString("productId").equals(nativeRequirement.getString("productId"))
                    || nativeRequirement.getBigDecimal("quantity").subtract(priorQuantity).compareTo(BigDecimal.ONE) < 0) {
                throw new IllegalArgumentException("Capitalization must match the approved requirement's product and remaining quantity.");
            }
            validateApprovedAmount(BigDecimal.ONE, priorCost.add(cost), detail.getBigDecimal("estimatedCapitalizedCost"));
            BigDecimal salvage = (BigDecimal) context.get("salvageValue");
            Long life = (Long) context.get("usefulLifeMonths");
            if (salvage == null || salvage.signum() < 0 || salvage.compareTo(cost) >= 0 || life == null || life <= 0) {
                throw new IllegalArgumentException("Enter a positive useful life and salvage value between zero and cost.");
            }
            GenericValue method = one(d, "CustomMethod", Map.of("customMethodId", required(context, "depreciationCustomMethodId")));
            if (!"DEPRECIATION_FORMULA".equals(method.getString("customMethodTypeId"))) throw new IllegalArgumentException("Choose a registered depreciation formula.");
            Timestamp now = UtilDateTime.nowTimestamp();
            Map<String, Object> setup = call(dctx, "assetsValidateAssetBookPostingSetup", Map.of("assetsBookId", bookId,
                    "fixedAssetTypeId", detail.getString("fixedAssetTypeId"), "transactionDate", now, "userLogin", user));
            String assetAccount = required(setup, "assetGlAccountId");
            if (assetAccount.equals(sourceAccount)) throw new IllegalArgumentException("The asset cost account must differ from the receipt inventory account.");
            String reason = required(context, "varianceReasonId");
            GenericValue reasonAccount = one(d, "VarianceReasonGlAccount", Map.of("varianceReasonId", reason, "organizationPartyId", organization));
            if (!assetAccount.equals(reasonAccount.getString("glAccountId"))) throw new IllegalArgumentException("The configured capitalization stock reason must debit the book's asset cost account.");
            AssetsGlMappingServices.validateAccount(d, organization, assetAccount, "ASSET", now);
            Map<String, Object> result = ServiceUtil.returnSuccess();
            result.put("capitalizedAmount", cost);
            result.put("currencyUomId", currency);
            result.put("assetGlAccountId", assetAccount);
            result.put("sourceGlAccountId", sourceAccount);
            result.put("dryRun", dryRun ? "Y" : "N");
            if (dryRun) return result;

            Map<String, Object> create = new HashMap<>();
            create.put("userLogin", user);
            if (context.get("fixedAssetId") != null) create.put("fixedAssetId", context.get("fixedAssetId"));
            create.put("fixedAssetTypeId", detail.getString("fixedAssetTypeId"));
            create.put("fixedAssetName", required(context, "fixedAssetName"));
            create.put("partyId", organization);
            create.put("roleTypeId", book.getString("roleTypeId"));
            create.put("instanceOfProductId", receipt.getString("productId"));
            create.put("acquireOrderId", receipt.getString("orderId"));
            create.put("acquireOrderItemSeqId", receipt.getString("orderItemSeqId"));
            create.put("purchaseCost", cost);
            create.put("purchaseCostUomId", currency);
            create.put("salvageValue", salvage);
            create.put("depreciation", BigDecimal.ZERO);
            if (serialized) create.put("serialNumber", inventory.getString("serialNumber"));
            create.put("dateAcquired", receipt.getTimestamp("datetimeReceived"));
            create.put("expectedEndOfLife", java.sql.Date.valueOf(now.toLocalDateTime().toLocalDate().plusMonths(life)));
            String fixedAssetId = required(call(dctx, "createFixedAsset", create), "fixedAssetId");
            String physicalId;
            if (serialized) {
                // The native variance wrapper explicitly rejects serialized items.
                // Compose its native detail/accounting services with the serialized
                // issue status, preserving QOH, ATP and accounting quantities.
                physicalId = required(call(dctx, "createPhysicalInventory", Map.of("comments", "Asset capitalization " + fixedAssetId,
                        "userLogin", user)), "physicalInventoryId");
                call(dctx, "createInventoryItemDetail", Map.of("inventoryItemId", inventory.getString("inventoryItemId"),
                        "physicalInventoryId", physicalId, "quantityOnHandDiff", BigDecimal.ONE.negate(),
                        "availableToPromiseDiff", BigDecimal.ONE.negate(), "accountingQuantityDiff", BigDecimal.ONE.negate(),
                        "reasonEnumId", reason, "description", "Asset capitalization " + fixedAssetId, "userLogin", user));
                call(dctx, "updateInventoryItem", Map.of("inventoryItemId", inventory.getString("inventoryItemId"),
                        "fixedAssetId", fixedAssetId, "statusId", "INV_DELIVERED", "userLogin", user));
                call(dctx, "createAcctgTransForPhysicalInventoryVariance", Map.of("physicalInventoryId", physicalId, "userLogin", user));
            } else {
                Map<String, Object> conversion = call(dctx, "createPhysicalInventoryAndVariance", Map.of("inventoryItemId", inventory.getString("inventoryItemId"),
                        "quantityOnHandVar", BigDecimal.ONE.negate(), "availableToPromiseVar", BigDecimal.ONE.negate(),
                        "varianceReasonId", reason, "comments", "Asset capitalization " + fixedAssetId, "userLogin", user));
                physicalId = required(conversion, "physicalInventoryId");
            }
            GenericValue trans = EntityQuery.use(d).from("AcctgTrans").where("physicalInventoryId", physicalId).queryFirst();
            if (trans == null || !"Y".equals(trans.getString("isPosted"))) throw new IllegalStateException("Native inventory conversion did not produce a posted journal.");
            BigDecimal assetDebit = BigDecimal.ZERO;
            BigDecimal otherCredit = BigDecimal.ZERO;
            for (GenericValue entry : EntityQuery.use(d).from("AcctgTransEntry").where("acctgTransId", trans.getString("acctgTransId")).queryList()) {
                if (!organization.equals(entry.getString("organizationPartyId")) || !currency.equals(entry.getString("currencyUomId"))) throw new IllegalStateException("Unexpected native conversion ledger context.");
                if (assetAccount.equals(entry.getString("glAccountId"))) {
                    assetDebit = assetDebit.add("D".equals(entry.getString("debitCreditFlag")) ? entry.getBigDecimal("amount") : entry.getBigDecimal("amount").negate());
                } else {
                    if (!sourceAccount.equals(entry.getString("glAccountId"))) throw new IllegalStateException("Native conversion credited a different account from the posted receipt valuation.");
                    otherCredit = otherCredit.add("C".equals(entry.getString("debitCreditFlag")) ? entry.getBigDecimal("amount") : entry.getBigDecimal("amount").negate());
                }
            }
            if (assetDebit.compareTo(cost) != 0 || otherCredit.compareTo(cost) != 0) throw new IllegalStateException("Native conversion did not move exactly the received unit cost to the asset account.");
            Map<String, Object> assignment = new HashMap<>();
            assignment.put("userLogin", user);
            assignment.put("fixedAssetId", fixedAssetId);
            assignment.put("assetsBookId", bookId);
            assignment.put("fromDate", now);
            assignment.put("capitalizationDate", now);
            assignment.put("originalCost", cost);
            assignment.put("capitalizedCost", cost);
            assignment.put("salvageValue", salvage);
            assignment.put("usefulLifeMonths", life);
            assignment.put("remainingLifeMonths", life);
            assignment.put("depreciationCustomMethodId", required(context, "depreciationCustomMethodId"));
            assignment.put("statusId", "ASSETS_PENDING");
            assignment.put("dryRun", "N");
            call(dctx, "assetsAssignFixedAssetToBook", assignment);
            GenericValue cap = d.makeValue("AssetsCapitalization");
            String capId = d.getNextSeqId("AssetsCapitalization");
            cap.set("assetsCapitalizationId", capId);
            cap.set("fixedAssetId", fixedAssetId);
            cap.set("assetsBookId", bookId);
            cap.set("inventoryItemId", inventory.getString("inventoryItemId"));
            cap.set("physicalInventoryId", physicalId);
            cap.set("quantity", BigDecimal.ONE);
            cap.set("requirementId", reqId);
            cap.set("receiptId", receiptId);
            cap.set("orderId", receipt.getString("orderId"));
            cap.set("orderItemSeqId", receipt.getString("orderItemSeqId"));
            cap.set("shipmentId", receipt.getString("shipmentId"));
            cap.set("invoiceId", invoiceId);
            cap.set("invoiceItemSeqId", invoiceSeq);
            cap.set("capitalizationDate", now);
            cap.set("capitalizedAmount", cost);
            cap.set("expensedAmount", BigDecimal.ZERO);
            cap.set("currencyUomId", currency);
            cap.set("acctgTransId", trans.getString("acctgTransId"));
            cap.set("statusId", "ASSETS_POSTED");
            cap.set("createdDate", now);
            cap.set("createdByUserLogin", user.getString("userLoginId"));
            cap.create();
            event(d, user, fixedAssetId, reqId, receipt.getString("orderId"), invoiceId,
                    trans.getString("acctgTransId"), "ASSETS_EVT_CAPITAL", "ASSETS_POSTED", "Received stock capitalized to fixed asset");
            if (detail.getString("fixedAssetId") == null) { detail.set("fixedAssetId", fixedAssetId); detail.store(); }
            result.put("fixedAssetId", fixedAssetId);
            result.put("assetsCapitalizationId", capId);
            result.put("acctgTransId", trans.getString("acctgTransId"));
            return result;
        } catch (Exception e) { return error(e); }
    }

    public static Map<String, Object> assetsPlaceInService(DispatchContext dctx, Map<String, ?> context) {
        try {
            Delegator d = dctx.getDelegator();
            GenericValue user = (GenericValue) context.get("userLogin");
            String assetId = required(context, "fixedAssetId");
            String bookId = required(context, "assetsBookId");
            GenericValue cap = EntityQuery.use(d).from("AssetsCapitalization")
                    .where("fixedAssetId", assetId, "assetsBookId", bookId).queryOne();
            if (cap == null) throw new IllegalArgumentException("A posted Assets capitalization is required.");
            GenericValue book = one(d, "AssetsBook", Map.of("assetsBookId", bookId));
            AssetsRequestServices.requireOrganizationAccess(dctx, user, book.getString("partyId"));
            GenericValue master = one(d, "FixedAsset", Map.of("fixedAssetId", assetId));
            if (!book.getString("partyId").equals(master.getString("partyId")) || master.get("actualEndOfLife") != null
                    || !"ASSETS_ACTIVE".equals(book.getString("statusId")) || !AssetsGlMappingServices.effective(book, UtilDateTime.nowTimestamp())) {
                throw new IllegalArgumentException("An active book and an asset owned by its organization, without retirement, are required.");
            }
            AssetsProcurementServices.lockRecord(cap);
            GenericValue detail = EntityQuery.use(d).from("AssetsBookDetail").where("fixedAssetId", assetId,
                    "assetsBookId", bookId).filterByDate().queryOne();
            if (detail == null) throw new IllegalArgumentException("No effective asset book assignment exists.");
            Map<String, Object> result = ServiceUtil.returnSuccess();
            result.put("fixedAssetId", assetId);
            if ("ASSETS_IN_SERVICE".equals(detail.getString("statusId"))) { result.put("replayed", "Y"); return result; }
            if (!"ASSETS_PENDING".equals(detail.getString("statusId"))) throw new IllegalArgumentException("Only a capitalized, pending asset can be placed in service.");
            GenericValue trans = one(d, "AcctgTrans", Map.of("acctgTransId", cap.getString("acctgTransId")));
            if (!"Y".equals(trans.getString("isPosted"))) throw new IllegalArgumentException("The capitalization journal must be posted.");
            Timestamp now = UtilDateTime.nowTimestamp();
            detail.set("statusId", "ASSETS_IN_SERVICE");
            detail.set("placedInServiceDate", now);
            detail.set("nextDepreciationDate", Timestamp.valueOf(now.toLocalDateTime().withDayOfMonth(now.toLocalDateTime().toLocalDate().lengthOfMonth())));
            detail.set("lastModifiedDate", now);
            detail.set("lastModifiedByUserLogin", user.getString("userLoginId"));
            detail.store();
            cap.set("placedInServiceDate", now);
            cap.set("lastModifiedByUserLogin", user.getString("userLoginId"));
            cap.store();
            event(d, user, assetId, cap.getString("requirementId"), cap.getString("orderId"), cap.getString("invoiceId"),
                    cap.getString("acctgTransId"), "ASSETS_EVT_SERVICE", "ASSETS_IN_SERVICE", "Asset placed in service");
            result.put("replayed", "N");
            return result;
        } catch (Exception e) { return error(e); }
    }

    private static void event(Delegator d, GenericValue user, String assetId, String reqId, String orderId,
            String invoiceId, String journal, String type, String status, String description) throws Exception {
        GenericValue event = d.makeValue("AssetsLifecycleEvent");
        event.set("assetsLifecycleEventId", d.getNextSeqId("AssetsLifecycleEvent"));
        event.set("fixedAssetId", assetId);
        event.set("requirementId", reqId);
        event.set("orderId", orderId);
        event.set("invoiceId", invoiceId);
        event.set("acctgTransId", journal);
        event.set("eventTypeEnumId", type);
        event.set("statusId", status);
        event.set("description", description);
        event.set("eventDate", UtilDateTime.nowTimestamp());
        event.set("createdDate", UtilDateTime.nowTimestamp());
        event.set("createdByUserLogin", user.getString("userLoginId"));
        event.create();
    }

    private static Map<String, Object> error(Exception e) {
        org.apache.ofbiz.base.util.Debug.logError(e, "Assets capitalization/inspection failed", AssetsCapitalizationServices.class.getName());
        return ServiceUtil.returnError(e instanceof IllegalArgumentException ? e.getMessage()
                : "Assets capitalization failed. No asset, stock or accounting changes were committed. Check native accounting setup and the server log.");
    }
}
