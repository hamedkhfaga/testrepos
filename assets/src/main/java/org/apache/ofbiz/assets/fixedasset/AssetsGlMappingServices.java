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

import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import org.apache.ofbiz.base.util.UtilDateTime;
import org.apache.ofbiz.entity.Delegator;
import org.apache.ofbiz.entity.GenericValue;
import org.apache.ofbiz.entity.util.EntityQuery;
import org.apache.ofbiz.service.DispatchContext;
import org.apache.ofbiz.service.ServiceUtil;

/** Validated, effective-dated Assets mappings referencing native Finance accounts. */
public final class AssetsGlMappingServices {
    private static final Map<String, String> ACCOUNT_CLASSES = Map.of("assetGlAccountId", "ASSET",
            "cipGlAccountId", "ASSET", "depGlAccountId", "DEPRECIATION", "accDepGlAccountId", "ACCUM_DEPRECIATION",
            "profitGlAccountId", "INCOME", "lossGlAccountId", "EXPENSE", "disposalProceedsGlAccountId", "ASSET");
    private AssetsGlMappingServices() { }

    public static Map<String, Object> assetsGetGlMappingSetup(DispatchContext dctx, Map<String, ?> context) {
        try {
            Delegator delegator = dctx.getDelegator();
            List<GenericValue> books = new ArrayList<>();
            GenericValue user = (GenericValue) context.get("userLogin");
            Set<String> allowed = new HashSet<>();
            for (GenericValue access : EntityQuery.use(delegator).from("AssetsOrganizationAccess")
                    .where("userLoginId", user.getString("userLoginId")).filterByDate().queryList()) allowed.add(access.getString("partyId"));
            boolean admin = dctx.getSecurity().hasEntityPermission("ASSETS", "_ADMIN", user);
            for (GenericValue book : EntityQuery.use(delegator).from("AssetsBook").orderBy("bookName").queryList()) {
                if (admin || allowed.contains(book.getString("partyId"))) books.add(book);
            }
            Map<String, Object> result = ServiceUtil.returnSuccess();
            result.put("books", books);
            result.put("assetTypes", EntityQuery.use(delegator).from("FixedAssetType").orderBy("description").queryList());
            String bookId = (String) context.get("assetsBookId");
            if (bookId != null && !bookId.isBlank()) {
                GenericValue book = book(dctx, context);
                result.put("mappings", EntityQuery.use(delegator).from("AssetsBookGlAccount")
                        .where("assetsBookId", bookId).orderBy("-fromDate").queryList());
                List<Map<String, Object>> accounts = new ArrayList<>();
                Timestamp now = UtilDateTime.nowTimestamp();
                for (GenericValue assignment : EntityQuery.use(delegator).from("GlAccountOrganization")
                        .where("organizationPartyId", book.getString("partyId")).queryList()) {
                    if (!effective(assignment, now)) continue;
                    GenericValue account = AssetsProcurementServices.one(delegator, "GlAccount",
                            Map.of("glAccountId", assignment.getString("glAccountId")));
                    Map<String, Object> row = new HashMap<>();
                    for (String field : List.of("glAccountId", "accountCode", "accountName", "glAccountClassId")) row.put(field, account.get(field));
                    accounts.add(row);
                }
                result.put("accounts", accounts);
            }
            return result;
        } catch (Exception e) { return error(e); }
    }

    public static Map<String, Object> assetsSaveGlMapping(DispatchContext dctx, Map<String, ?> context) {
        try {
            Delegator delegator = dctx.getDelegator();
            GenericValue book = book(dctx, context);
            Timestamp start = (Timestamp) context.get("fromDate");
            if (start == null || !effective(book, start)) throw new IllegalArgumentException("The mapping date must fall within the book's effective dates.");
            String asset = normalized((String) context.get("fixedAssetId"));
            String type = normalized((String) context.get("fixedAssetTypeId"));
            if (asset != null) {
                GenericValue master = AssetsProcurementServices.one(delegator, "FixedAsset", Map.of("fixedAssetId", asset));
                if (!book.getString("partyId").equals(master.getString("partyId"))) throw new IllegalArgumentException("Asset and book must belong to the same organization.");
                if (type != null && !type.equals(master.getString("fixedAssetTypeId"))) throw new IllegalArgumentException("Asset type does not match the asset.");
            }
            if (type != null) AssetsProcurementServices.one(delegator, "FixedAssetType", Map.of("fixedAssetTypeId", type));
            String cost = AssetsProcurementServices.required(context, "assetGlAccountId");
            String expense = AssetsProcurementServices.required(context, "depGlAccountId");
            String accumulated = AssetsProcurementServices.required(context, "accDepGlAccountId");
            if (cost.equals(expense) || cost.equals(accumulated) || expense.equals(accumulated)) {
                throw new IllegalArgumentException("Cost, depreciation expense and accumulated depreciation require distinct accounts.");
            }
            for (Map.Entry<String, String> field : ACCOUNT_CLASSES.entrySet()) {
                String id = normalized((String) context.get(field.getKey()));
                if (id != null) validateAccount(delegator, book.getString("partyId"), id, field.getValue(), start);
            }
            // Serialize configuration changes on the book, preserving its effective dates.
            AssetsProcurementServices.lockRecord(book);
            // Never rewrite account selection for a period already represented in GL.
            for (GenericValue schedule : EntityQuery.use(delegator).from("AssetsBookDepSchedule")
                    .where("assetsBookId", book.getString("assetsBookId")).queryList()) {
                if (asset != null && !asset.equals(schedule.getString("fixedAssetId"))) continue;
                if (type != null) {
                    GenericValue master = AssetsProcurementServices.one(delegator, "FixedAsset",
                            Map.of("fixedAssetId", schedule.getString("fixedAssetId")));
                    if (!type.equals(master.getString("fixedAssetTypeId"))) continue;
                }
                String posted = schedule.getString("postedAcctgTransId");
                if (posted == null) continue;
                GenericValue trans = AssetsProcurementServices.one(delegator, "AcctgTrans", Map.of("acctgTransId", posted));
                if (trans.getTimestamp("transactionDate").compareTo(start) >= 0) {
                    throw new IllegalArgumentException("Choose a mapping date after existing posted transactions; historical mappings cannot be rewritten.");
                }
            }
            for (GenericValue disposal : EntityQuery.use(delegator).from("AssetsDisposalBook")
                    .where("assetsBookId", book.getString("assetsBookId"), "statusId", "ASSETS_DISPOSED").queryList()) {
                if (asset != null && !asset.equals(disposal.getString("fixedAssetId"))) continue;
                if (type != null) {
                    GenericValue master = AssetsProcurementServices.one(delegator, "FixedAsset", Map.of("fixedAssetId", disposal.getString("fixedAssetId")));
                    if (!type.equals(master.getString("fixedAssetTypeId"))) continue;
                }
                if (disposal.getString("acctgTransId") == null) continue;
                GenericValue transaction = AssetsProcurementServices.one(delegator, "AcctgTrans", Map.of("acctgTransId", disposal.getString("acctgTransId")));
                if (!start.after(transaction.getTimestamp("transactionDate"))) throw new IllegalArgumentException("Choose a mapping date after existing posted disposal journals; retirement account history cannot be rewritten.");
            }
            for (GenericValue improvement : EntityQuery.use(delegator).from("AssetsMaintenanceCostBook").where("assetsBookId", book.getString("assetsBookId")).queryList()) {
                if (asset != null && !asset.equals(improvement.getString("fixedAssetId"))) continue;
                if (type != null && !type.equals(AssetsProcurementServices.one(delegator, "FixedAsset", Map.of("fixedAssetId", improvement.get("fixedAssetId"))).getString("fixedAssetTypeId"))) continue;
                GenericValue transaction = AssetsProcurementServices.one(delegator, "AcctgTrans", Map.of("acctgTransId", improvement.get("acctgTransId")));
                if (!start.after(transaction.getTimestamp("transactionDate"))) throw new IllegalArgumentException("Choose a mapping date after existing posted capital-improvement journals.");
            }
            for (GenericValue prior : EntityQuery.use(delegator).from("AssetsBookGlAccount")
                    .where("assetsBookId", book.getString("assetsBookId"), "statusId", "ASSETS_ACTIVE").queryList()) {
                if (!Objects.equals(asset, normalized(prior.getString("fixedAssetId")))
                        || !Objects.equals(type, normalized(prior.getString("fixedAssetTypeId")))) continue;
                if (prior.getTimestamp("fromDate") == null || prior.getTimestamp("fromDate").compareTo(start) >= 0) {
                    throw new IllegalArgumentException("An equal or later mapping exists for this scope. Choose a later effective date.");
                }
                Timestamp end = prior.getTimestamp("thruDate");
                if (end == null || end.after(start)) { prior.set("thruDate", start); prior.store(); }
            }
            GenericValue mapping = delegator.makeValue("AssetsBookGlAccount");
            String id = delegator.getNextSeqId("AssetsBookGlAccount");
            mapping.set("assetsBookGlAccountId", id);
            mapping.set("assetsBookId", book.getString("assetsBookId"));
            mapping.set("partyId", book.getString("partyId"));
            mapping.set("roleTypeId", book.getString("roleTypeId"));
            mapping.set("fixedAssetId", asset);
            mapping.set("fixedAssetTypeId", type);
            mapping.set("fromDate", start);
            mapping.set("statusId", "ASSETS_ACTIVE");
            for (String field : ACCOUNT_CLASSES.keySet()) mapping.set(field, normalized((String) context.get(field)));
            mapping.set("comments", context.get("comments"));
            mapping.set("createdDate", UtilDateTime.nowTimestamp());
            mapping.set("createdByUserLogin", ((GenericValue) context.get("userLogin")).getString("userLoginId"));
            mapping.create();
            String reasonId = normalized((String) context.get("capitalizationReasonId"));
            if (reasonId != null) {
                if (reasonId.length() > 20) throw new IllegalArgumentException("The capitalization stock reason ID cannot exceed 20 characters.");
                GenericValue reason = EntityQuery.use(delegator).from("VarianceReason").where("varianceReasonId", reasonId).queryOne();
                if (reason == null) {
                    reason = delegator.create("VarianceReason", Map.of("varianceReasonId", reasonId,
                            "description", AssetsProcurementServices.required(context, "capitalizationReasonDescription")));
                }
                GenericValue enumeration = EntityQuery.use(delegator).from("Enumeration").where("enumId", reasonId).queryOne();
                if (enumeration == null) {
                    delegator.create("Enumeration", Map.of("enumId", reasonId, "enumTypeId", "IID_REASON",
                            "description", reason.getString("description")));
                } else if (!"IID_REASON".equals(enumeration.getString("enumTypeId"))) {
                    throw new IllegalArgumentException("This reason ID is already used by another enumeration type.");
                }
                GenericValue assignment = EntityQuery.use(delegator).from("VarianceReasonGlAccount")
                        .where("varianceReasonId", reasonId, "organizationPartyId", book.getString("partyId")).queryOne();
                if (assignment == null) {
                    AssetsProcurementServices.call(dctx, "createVarianceReasonGlAccount", Map.of("varianceReasonId", reasonId,
                            "organizationPartyId", book.getString("partyId"), "glAccountId", cost, "userLogin", context.get("userLogin")));
                } else if (!cost.equals(assignment.getString("glAccountId"))) {
                    throw new IllegalArgumentException("This stock reason already maps to a different cost account; choose a distinct reason.");
                }
            }
            Map<String, Object> result = ServiceUtil.returnSuccess();
            result.put("assetsBookGlAccountId", id);
            return result;
        } catch (Exception e) { return error(e); }
    }

    static void validateAccount(Delegator delegator, String organization, String id, String expectedClass, Timestamp date) throws Exception {
        GenericValue account = AssetsProcurementServices.one(delegator, "GlAccount", Map.of("glAccountId", id));
        GenericValue assignment = EntityQuery.use(delegator).from("GlAccountOrganization")
                .where("glAccountId", id, "organizationPartyId", organization).queryOne();
        if (assignment == null || !effective(assignment, date)) throw new IllegalArgumentException("GL account " + id + " is not effective for organization " + organization + ".");
        String current = account.getString("glAccountClassId");
        Set<String> seen = new HashSet<>();
        while (current != null && seen.add(current)) {
            if (expectedClass.equals(current)) return;
            GenericValue cls = AssetsProcurementServices.one(delegator, "GlAccountClass", Map.of("glAccountClassId", current));
            current = cls.getString("parentClassId");
        }
        throw new IllegalArgumentException("GL account " + id + " must belong to class " + expectedClass + ".");
    }

    private static GenericValue book(DispatchContext dctx, Map<String, ?> context) throws Exception {
        GenericValue book = AssetsProcurementServices.one(dctx.getDelegator(), "AssetsBook",
                Map.of("assetsBookId", AssetsProcurementServices.required(context, "assetsBookId")));
        AssetsRequestServices.requireOrganizationAccess(dctx, (GenericValue) context.get("userLogin"), book.getString("partyId"));
        return book;
    }
    static boolean effective(GenericValue row, Timestamp date) {
        Timestamp start = row.getTimestamp("fromDate");
        Timestamp end = row.getTimestamp("thruDate");
        return (start == null || !start.after(date)) && (end == null || end.after(date));
    }
    static String normalized(String value) { return value == null || value.isBlank() || "_NA_".equals(value) ? null : value; }
    private static Map<String, Object> error(Exception e) {
        return ServiceUtil.returnError(e instanceof IllegalArgumentException ? e.getMessage() : "Assets GL mapping failed; no mapping changes were committed.");
    }
}
