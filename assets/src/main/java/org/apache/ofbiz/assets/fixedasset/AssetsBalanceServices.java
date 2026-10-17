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
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.apache.ofbiz.entity.Delegator;
import org.apache.ofbiz.entity.GenericValue;
import org.apache.ofbiz.entity.util.EntityQuery;
import org.apache.ofbiz.service.DispatchContext;
import org.apache.ofbiz.service.ServiceUtil;

/** Read-only book balances with a signed, linked GL movement comparison. */
public final class AssetsBalanceServices {
    private AssetsBalanceServices() { }

    public static Map<String, Object> assetsGetAccumDep(DispatchContext dctx, Map<String, ?> context) {
        try {
            Delegator delegator = dctx.getDelegator();
            GenericValue user = (GenericValue) context.get("userLogin");
            @SuppressWarnings("unchecked")
            List<String> assetIds = (List<String>) context.get("fixedAssetIds");
            if (assetIds == null || assetIds.isEmpty()) throw new IllegalArgumentException("AssetsBalanceAssetIdsRequired");
            if (assetIds.size() > 500) throw new IllegalArgumentException("AssetsBalanceTooManyAssets");
            List<Map<String, Object>> rows = new ArrayList<>();
            for (String assetId : new HashSet<>(assetIds)) {
                GenericValue asset = EntityQuery.use(delegator).from("FixedAsset").where("fixedAssetId", assetId).queryOne();
                if (asset == null || asset.getString("partyId") == null || asset.getString("partyId").isBlank()) {
                    rows.add(unavailableBalance(assetId, asset == null ? "ASSET_NOT_FOUND" : "MISSING_ORGANIZATION"));
                    continue;
                }
                if (EntityQuery.use(delegator).from("PartyRole").where("partyId", asset.getString("partyId"),
                        "roleTypeId", "INTERNAL_ORGANIZATIO").queryOne() == null) {
                    rows.add(unavailableBalance(assetId, "INVALID_OWNING_ORGANIZATION"));
                    continue;
                }
                try {
                    AssetsRequestServices.requireOrganizationAccess(dctx, user, asset.getString("partyId"));
                } catch (IllegalArgumentException denied) {
                    if (!"AssetsRequestOrganizationDenied".equals(denied.getMessage())) throw denied;
                    rows.add(unavailableBalance(assetId, "ORGANIZATION_ACCESS_DENIED"));
                    continue;
                }
                Map<String, Object> row = new HashMap<>();
                row.put("fixedAssetId", assetId);
                Map<String, Object> bookFilter = new HashMap<>();
                bookFilter.put("partyId", asset.getString("partyId"));
                bookFilter.put("statusId", "ASSETS_ACTIVE");
                if (context.get("assetsBookId") == null) bookFilter.put("isPrimaryBook", "Y");
                else bookFilter.put("assetsBookId", context.get("assetsBookId"));
                List<GenericValue> books = EntityQuery.use(delegator).from("AssetsBook").where(bookFilter).filterByDate().queryList();
                if (books.size() > 1) {
                    rows.add(unavailableBalance(assetId, "AMBIGUOUS_ACTIVE_BOOK"));
                    continue;
                }
                GenericValue book = books.isEmpty() ? null : books.get(0);
                List<GenericValue> details = book == null ? List.of() : EntityQuery.use(delegator).from("AssetsBookDetail")
                        .where("fixedAssetId", assetId, "assetsBookId", book.getString("assetsBookId"))
                        .filterByDate().queryList().stream().filter(d -> List.of("ASSETS_ACTIVE", "ASSETS_IN_SERVICE", "ASSETS_POSTED", "ASSETS_DISPOSED")
                                .contains(d.getString("statusId"))).toList();
                if (details.size() > 1) {
                    rows.add(unavailableBalance(assetId, "AMBIGUOUS_ACTIVE_BOOK"));
                    continue;
                }
                if (details.isEmpty()) {
                    rows.add(unavailableBalance(assetId, book == null ? "NO_ACTIVE_PRIMARY_BOOK" : "NO_ACTIVE_BOOK_ASSIGNMENT"));
                    continue;
                }
                GenericValue detail = details.get(0);
                if ("ASSETS_DISPOSED".equals(detail.getString("statusId"))) {
                    boolean postedRetirement = false;
                    for (GenericValue disposalBook : EntityQuery.use(delegator).from("AssetsDisposalBook")
                            .where("fixedAssetId", assetId, "assetsBookId", book.getString("assetsBookId"), "statusId", "ASSETS_DISPOSED").queryList()) {
                        GenericValue disposal = EntityQuery.use(delegator).from("AssetsDisposal").where("assetsDisposalId", disposalBook.get("assetsDisposalId")).queryOne();
                        String journalId = disposalBook.getString("acctgTransId");
                        GenericValue journal = journalId == null ? null : EntityQuery.use(delegator).from("AcctgTrans").where("acctgTransId", journalId).queryOne();
                        if (disposal != null && "CONTROLLED_V1".equals(disposal.getString("workflowVersion"))
                                && "ASSETS_DISPOSED".equals(disposal.getString("statusId")) && journal != null
                                && "Y".equals(journal.getString("isPosted")) && assetId.equals(journal.getString("fixedAssetId"))) postedRetirement = true;
                    }
                    if (!postedRetirement || asset.get("actualEndOfLife") == null
                            || detail.get("capitalizedCost") == null || detail.get("accumulatedDepreciation") == null || detail.get("netBookValue") == null
                            || zero(detail.getBigDecimal("revaluationAmount")).signum() != 0 || zero(detail.getBigDecimal("impairmentAmount")).signum() != 0
                            || zero(detail.getBigDecimal("capitalizedCost")).signum() != 0
                            || zero(detail.getBigDecimal("accumulatedDepreciation")).signum() != 0
                            || zero(detail.getBigDecimal("netBookValue")).signum() != 0) {
                        rows.add(unavailableBalance(assetId, "DISPOSAL_RECONCILIATION_REQUIRED"));
                        continue;
                    }
                }
                BigDecimal cost = detail.getBigDecimal("capitalizedCost");
                if (cost == null) cost = detail.getBigDecimal("originalCost");
                if (cost == null) {
                    rows.add(unavailableBalance(assetId, "MISSING_CAPITALIZED_COST"));
                    continue;
                }
                BigDecimal accumulated = zero(detail.getBigDecimal("accumulatedDepreciation"));
                BigDecimal nbv = cost.add(zero(detail.getBigDecimal("revaluationAmount")))
                        .subtract(zero(detail.getBigDecimal("impairmentAmount"))).subtract(accumulated);
                row.put("assetsBookId", book.getString("assetsBookId"));
                row.put("currencyUomId", book.getString("currencyUomId"));
                row.put("balanceAvailable", "Y");
                row.put("balanceSource", "ASSETS_BOOK_SUBLEDGER");
                row.put("accumulatedDepreciation", accumulated);
                row.put("netBookValue", nbv);
                row.put("capitalizedCost", cost);
                if ("Y".equals(book.getString("isPostingBook"))) {
                    Set<String> accounts = new HashSet<>();
                    for (GenericValue mapping : EntityQuery.use(delegator).from("AssetsBookGlAccount")
                            .where("assetsBookId", book.getString("assetsBookId")).queryList()) {
                        if (mapping.getString("accDepGlAccountId") != null) accounts.add(mapping.getString("accDepGlAccountId"));
                    }
                    Set<String> transactions = new HashSet<>();
                    for (GenericValue schedule : EntityQuery.use(delegator).from("AssetsBookDepSchedule")
                            .where("assetsBookId", book.getString("assetsBookId"), "fixedAssetId", assetId).queryList()) {
                        if (schedule.getString("postedAcctgTransId") != null) transactions.add(schedule.getString("postedAcctgTransId"));
                        if (schedule.getString("reversalAcctgTransId") != null) transactions.add(schedule.getString("reversalAcctgTransId"));
                    }
                    for (GenericValue disposal : EntityQuery.use(delegator).from("AssetsDisposalBook")
                            .where("assetsBookId", book.getString("assetsBookId"), "fixedAssetId", assetId, "statusId", "ASSETS_DISPOSED").queryList()) {
                        if (disposal.getString("acctgTransId") != null) transactions.add(disposal.getString("acctgTransId"));
                    }
                    BigDecimal gl = BigDecimal.ZERO;
                    boolean comparable = !accounts.isEmpty();
                    for (String id : transactions) {
                        GenericValue trans = EntityQuery.use(delegator).from("AcctgTrans").where("acctgTransId", id).queryOne();
                        if (trans == null || !"Y".equals(trans.getString("isPosted")) || !assetId.equals(trans.getString("fixedAssetId"))) continue;
                        for (GenericValue entry : EntityQuery.use(delegator).from("AcctgTransEntry")
                                .where("acctgTransId", id, "organizationPartyId", book.getString("partyId")).queryList()) {
                            if (!accounts.contains(entry.getString("glAccountId"))) continue;
                            if (!java.util.Objects.equals(entry.getString("currencyUomId"), book.getString("currencyUomId"))) {
                                comparable = false;
                                continue;
                            }
                            gl = gl.add(signedAccumulation(entry.getBigDecimal("amount"), entry.getString("debitCreditFlag")));
                        }
                    }
                    row.put("glComparisonAvailable", comparable ? "Y" : "N");
                    if (comparable) {
                        row.put("linkedPostedGlMovement", gl);
                        row.put("subledgerMinusLinkedGl", accumulated.subtract(gl));
                    }
                    // This movement comparison excludes unrelated/opening journals; it is not a full account reconciliation.
                    row.put("glComparisonScope", "LINKED_DEPRECIATION_AND_DISPOSAL_ONLY");
                }
                rows.add(row);
            }
            Map<String, Object> result = ServiceUtil.returnSuccess();
            result.put("assetList", rows);
            return result;
        } catch (Exception e) { return AssetsRequestServices.failure(context, e); }
    }

    /** Unavailable rows contain no financial values; infrastructure errors still fail the request. */
    static Map<String, Object> unavailableBalance(String assetId, String reason) {
        Map<String, Object> row = new HashMap<>();
        row.put("fixedAssetId", assetId);
        row.put("balanceAvailable", "N");
        row.put("balanceSource", reason);
        row.put("balanceReason", reason);
        return row;
    }

    static BigDecimal signedAccumulation(BigDecimal amount, String flag) {
        if (!"C".equals(flag) && !"D".equals(flag)) throw new IllegalArgumentException("AssetsBalanceInvalidEntry");
        return "C".equals(flag) ? zero(amount) : zero(amount).negate();
    }

    private static BigDecimal zero(BigDecimal value) { return value == null ? BigDecimal.ZERO : value; }
}
