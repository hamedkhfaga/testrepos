/* Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements. See the NOTICE file for ownership.
 * Licensed under the Apache License, Version 2.0.
 * https://www.apache.org/licenses/LICENSE-2.0
 * Distributed on an AS IS BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND. */
package org.apache.ofbiz.assets.fixedasset;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.sql.Timestamp;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;
import org.apache.ofbiz.base.util.Debug;
import org.apache.ofbiz.base.util.UtilDateTime;
import org.apache.ofbiz.entity.Delegator;
import org.apache.ofbiz.entity.GenericValue;
import org.apache.ofbiz.entity.util.EntityQuery;
import org.apache.ofbiz.service.DispatchContext;
import org.apache.ofbiz.service.ServiceUtil;

/** Approval snapshots and atomic retirement; native accounting remains authoritative. */
public final class AssetsDisposalServices {
    private static final String VERSION = "CONTROLLED_V1";
    private static final List<String> OPEN = List.of("ASSETS_PENDING", "ASSETS_APPROVED");
    private static final List<String> LIVE_BOOK = List.of("ASSETS_ACTIVE", "ASSETS_IN_SERVICE", "ASSETS_POSTED");
    private static final List<String> ACCOUNT_FIELDS = List.of("assetGlAccountId", "accDepGlAccountId", "profitGlAccountId", "lossGlAccountId", "disposalProceedsGlAccountId");
    private AssetsDisposalServices() { }

    public static Map<String, Object> assetsGetDisposalWorkspace(DispatchContext ctx, Map<String, ?> in) {
        try {
            Delegator d = ctx.getDelegator();
            GenericValue user = (GenericValue) in.get("userLogin");
            Map<String, Object> result = ServiceUtil.returnSuccess();
            String assetId = text(in.get("fixedAssetId"));
            if (assetId == null && text(in.get("assetsDisposalId")) != null) assetId = one(d, "AssetsDisposal", Map.of("assetsDisposalId", in.get("assetsDisposalId"))).getString("fixedAssetId");
            String partyId = text(in.get("partyId"));
            if (assetId != null) {
                GenericValue asset = one(d, "FixedAsset", Map.of("fixedAssetId", assetId));
                partyId = asset.getString("partyId");
                AssetsRequestServices.requireOrganizationAccess(ctx, user, partyId);
                result.put("asset", asset);
                result.put("provenance", AssetsCustodyServices.provenance(d, asset));
            } else {
                if (partyId == null) throw new IllegalArgumentException("Select an organization or asset.");
                AssetsRequestServices.requireOrganizationAccess(ctx, user, partyId);
            }
            List<GenericValue> rows = new ArrayList<>();
            if (assetId != null) rows = EntityQuery.use(d).from("AssetsDisposal").where("fixedAssetId", assetId).orderBy("-createdDate").queryList();
            else rows = EntityQuery.use(d).from("AssetsDisposal").where("partyId", partyId).orderBy("-createdDate").maxRows(200).queryList();
            List<GenericValue> books = new ArrayList<>(), events = new ArrayList<>();
            for (GenericValue row : rows) {
                books.addAll(EntityQuery.use(d).from("AssetsDisposalBook").where("assetsDisposalId", row.get("assetsDisposalId")).queryList());
                events.addAll(EntityQuery.use(d).from("AssetsDisposalEvent").where("assetsDisposalId", row.get("assetsDisposalId")).orderBy("-eventDate").queryList());
            }
            result.put("rows", rows); result.put("books", books); result.put("events", events);
            return result;
        } catch (Exception e) { return failure(e); }
    }

    public static Map<String, Object> assetsRequestDisposal(DispatchContext ctx, Map<String, ?> in) {
        try {
            Delegator d = ctx.getDelegator();
            String id = required(in, "fixedAssetId");
            GenericValue asset = one(d, "FixedAsset", Map.of("fixedAssetId", id));
            GenericValue user = (GenericValue) in.get("userLogin");
            AssetsRequestServices.requireOrganizationAccess(ctx, user, asset.getString("partyId"));
            boolean preview = !"N".equals(in.get("dryRun"));
            if (!preview) { AssetsCustodyServices.control(d, id, true); asset.refresh(); lockDetails(d, id); }
            requireNoOpenDisposal(d, id);
            Map<String, Object> plan = plan(ctx, asset, in);
            Map<String, Object> result = ServiceUtil.returnSuccess(); result.put("plan", plan);
            if (preview) return result;
            if (!Objects.equals(in.get("expectedFingerprint"), plan.get("fingerprint"))) throw new IllegalArgumentException("Preview again: asset balances, dates or account selection changed.");
            String disposalId = d.getNextSeqId("AssetsDisposal");
            GenericValue row = d.makeValue("AssetsDisposal");
            row.set("assetsDisposalId", disposalId); row.set("fixedAssetId", id); row.set("partyId", asset.getString("partyId"));
            row.set("workflowVersion", VERSION); row.set("statusId", "ASSETS_PENDING");
            row.set("disposalTypeEnumId", in.get("disposalTypeEnumId")); row.set("disposalDate", in.get("disposalDate"));
            row.set("currencyUomId", plan.get("currencyUomId")); row.set("proceedsAmount", plan.get("proceedsAmount"));
            row.set("invoiceId", text(in.get("invoiceId"))); row.set("invoiceItemSeqId", text(in.get("invoiceItemSeqId")));
            row.set("valuationFingerprint", plan.get("fingerprint")); row.set("comments", required(in, "comments"));
            row.set("createdDate", UtilDateTime.nowTimestamp()); row.set("createdByUserLogin", user.getString("userLoginId")); row.set("lastModifiedDate", UtilDateTime.nowTimestamp()); row.create();
            for (Map<String, Object> book : plans(plan)) {
                GenericValue snapshot = d.makeValue("AssetsDisposalBook"); snapshot.set("assetsDisposalId", disposalId); snapshot.set("fixedAssetId", id);
                for (String field : List.of("assetsBookId", "netBookValue", "originalCostAmount", "accumulatedDepAmount", "proceedsAllocatedAmount", "gainLossAmount", "isPostingBook")) snapshot.set(field, book.get(field));
                for (String field : ACCOUNT_FIELDS) snapshot.set(field, book.get(field));
                snapshot.set("statusId", "ASSETS_PENDING"); snapshot.create();
            }
            if (row.getString("invoiceId") != null) reserveInvoice(d, row);
            event(d, row, user, "SUBMIT", in);
            result.put("assetsDisposalId", disposalId); return result;
        } catch (Exception e) { return failure(e); }
    }

    public static Map<String, Object> assetsProcessDisposal(DispatchContext ctx, Map<String, ?> in) {
        try {
            Delegator d = ctx.getDelegator(); GenericValue user = (GenericValue) in.get("userLogin");
            GenericValue request = one(d, "AssetsDisposal", Map.of("assetsDisposalId", required(in, "assetsDisposalId")));
            if (!VERSION.equals(request.getString("workflowVersion"))) throw new IllegalArgumentException("Legacy disposal records require reconciliation; they cannot be posted by this workflow.");
            AssetsRequestServices.requireOrganizationAccess(ctx, user, request.getString("partyId"));
            GenericValue control = AssetsCustodyServices.control(d, request.getString("fixedAssetId"), true);
            AssetsProcurementServices.lockRecord(request);
            String action = required(in, "action"); required(in, "comments");
            boolean admin = ctx.getSecurity().hasEntityPermission("ASSETS", "_ADMIN", user);
            boolean approve = admin || ctx.getSecurity().hasEntityPermission("ASSETS", "_APPROVE", user);
            boolean update = admin || ctx.getSecurity().hasEntityPermission("ASSETS", "_UPDATE", user);
            if ("POST".equals(action) && "ASSETS_DISPOSED".equals(request.getString("statusId"))) {
                if (!approve) throw new IllegalArgumentException("Disposal posting permission is required.");
                GenericValue trans = one(d, "AcctgTrans", Map.of("acctgTransId", request.getString("acctgTransId")));
                if (!"Y".equals(trans.getString("isPosted"))) throw new IllegalArgumentException("The linked retirement journal must be reconciled.");
                return result(request, "Y");
            }
            String next = nextStatus(request.getString("statusId"), action);
            if ("CANCEL".equals(action)) {
                if (!update || (!admin && !user.getString("userLoginId").equals(request.getString("createdByUserLogin")))) throw new IllegalArgumentException("Only the requester or administrator may cancel before posting.");
            } else {
                if (!approve) throw new IllegalArgumentException("Assets approval permission is required.");
                if (List.of("APPROVE", "POST").contains(action)) requireSeparateApprover(request.getString("createdByUserLogin"), user.getString("userLoginId"), admin, "Y".equals(in.get("onBehalf")));
            }
            if (List.of("APPROVE", "POST").contains(action)) {
                GenericValue asset = one(d, "FixedAsset", Map.of("fixedAssetId", request.getString("fixedAssetId")));
                lockDetails(d, asset.getString("fixedAssetId"));
                Map<String, Object> current = plan(ctx, asset, request);
                if (!Objects.equals(request.get("valuationFingerprint"), current.get("fingerprint"))) throw new IllegalArgumentException("The submitted valuation changed. Cancel and submit a new disposal for approval.");
                if ("APPROVE".equals(action)) {
                    request.set("approvedByUserLogin", user.getString("userLoginId")); request.set("approvedDate", UtilDateTime.nowTimestamp()); request.set("approvalComments", in.get("comments"));
                } else post(ctx, request, asset, current, user, control);
            }
            request.set("statusId", next); request.set("lastModifiedDate", UtilDateTime.nowTimestamp()); request.store();
            for (GenericValue book : EntityQuery.use(d).from("AssetsDisposalBook").where("assetsDisposalId", request.get("assetsDisposalId")).queryList()) { book.set("statusId", next); book.store(); }
            if (List.of("REJECT", "CANCEL").contains(action) && request.get("invoiceId") != null) {
                GenericValue claim = one(d, "AssetsDisposalInvoiceClaim", Map.of("invoiceId", request.getString("invoiceId")));
                if (request.get("assetsDisposalId").equals(claim.get("assetsDisposalId"))) { claim.set("statusId", "ASSETS_CANCELLED"); claim.set("lastModifiedDate", UtilDateTime.nowTimestamp()); claim.store(); }
            }
            event(d, request, user, action, in); return result(request, "N");
        } catch (Exception e) { return failure(e); }
    }

    private static Map<String, Object> plan(DispatchContext ctx, GenericValue asset, Map<String, ?> in) throws Exception {
        Delegator d = ctx.getDelegator(); String id = asset.getString("fixedAssetId");
        if (asset.get("actualEndOfLife") != null) throw new IllegalArgumentException("This asset is already retired.");
        String type = required(in, "disposalTypeEnumId");
        GenericValue enumRow = one(d, "Enumeration", Map.of("enumId", type));
        if (!"ASSETS_DISP_TYPE".equals(enumRow.getString("enumTypeId"))) throw new IllegalArgumentException("Select an Assets disposal type.");
        if (!List.of("ASSETS_DISP_SALE", "ASSETS_DISP_SCRAP", "ASSETS_DISP_DONATE", "ASSETS_DISP_LOSS").contains(type)) throw new IllegalArgumentException("Unsupported disposal type.");
        Timestamp date = (Timestamp) in.get("disposalDate"); if (date == null) throw new IllegalArgumentException("Disposal date is required.");
        LocalDate day = date.toLocalDateTime().toLocalDate();
        if (day.isAfter(LocalDate.now()) || (asset.getTimestamp("dateAcquired") != null && day.isBefore(asset.getTimestamp("dateAcquired").toLocalDateTime().toLocalDate()))) throw new IllegalArgumentException("Disposal date must be on or after acquisition and cannot be in the future.");
        if (!EntityQuery.use(d).from("PartyFixedAssetAssignment").where("fixedAssetId", id).filterByDate().queryList().isEmpty()) throw new IllegalArgumentException("Complete the asset custody return before disposal.");
        for (GenericValue move : EntityQuery.use(d).from("AssetsCustodyMovement").where("fixedAssetId", id).queryList()) if (List.of("SUBMITTED", "APPROVED", "HANDED_OVER").contains(move.getString("statusId"))) throw new IllegalArgumentException("Complete or cancel the open custody movement before disposal.");
        if (!EntityQuery.use(d).from("FixedAsset").where("parentFixedAssetId", id).queryList().stream().filter(child -> child.get("actualEndOfLife") == null).toList().isEmpty()) throw new IllegalArgumentException("Dispose or detach live child assets through their own workflows first.");
        for (GenericValue lease : EntityQuery.use(d).from("AssetsLeaseContract").where("fixedAssetId", id).queryList()) {
            if (List.of("ASSETS_ACTIVE", "ASSETS_APPROVED", "ASSETS_IN_SERVICE").contains(lease.getString("statusId"))) throw new IllegalArgumentException("Close or settle the active asset lease through its own workflow before disposal.");
        }
        for (GenericValue maintenance : EntityQuery.use(d).from("FixedAssetMaint").where("fixedAssetId", id).queryList()) {
            if (List.of("FAM_CREATED", "FAM_SCHEDULED", "FAM_IN_PROCESS").contains(maintenance.getString("statusId"))) throw new IllegalArgumentException("Complete or cancel open native asset maintenance before disposal.");
        }
        if (!EntityQuery.use(d).from("AssetsMaintenanceCost").where("fixedAssetId", id, "statusId", "OPEN").queryList().isEmpty()) throw new IllegalArgumentException("Return or settle open maintenance costs before disposal.");
        BigDecimal proceeds = money(in.get("proceedsAmount"));
        if (proceeds.signum() < 0 || (!"ASSETS_DISP_SALE".equals(type) && proceeds.signum() != 0) || ("ASSETS_DISP_SALE".equals(type) && proceeds.signum() == 0)) throw new IllegalArgumentException("Sale requires positive net proceeds; scrap, donation and loss require zero proceeds.");
        List<Map<String, Object>> books = new ArrayList<>(); Set<String> seen = new HashSet<>(); int postingBooks = 0; String currency = null;
        for (GenericValue detail : EntityQuery.use(d).from("AssetsBookDetail").where("fixedAssetId", id).filterByDate(date).orderBy("assetsBookId").queryList()) {
            if (!LIVE_BOOK.contains(detail.getString("statusId"))) {
                if ("ASSETS_PENDING".equals(detail.getString("statusId"))) throw new IllegalArgumentException("Place every capitalized book in service before retirement.");
                continue;
            }
            String bookId = detail.getString("assetsBookId"); if (!seen.add(bookId)) throw new IllegalArgumentException("Conflicting effective asset book assignments.");
            GenericValue book = one(d, "AssetsBook", Map.of("assetsBookId", bookId));
            if (!"ASSETS_ACTIVE".equals(book.getString("statusId")) || !AssetsGlMappingServices.effective(book, date) || !asset.get("partyId").equals(book.get("partyId"))) throw new IllegalArgumentException("Each book must be active, effective and owned by the asset organization.");
            if (currency == null) currency = book.getString("currencyUomId");
            if (!currency.equals(book.getString("currencyUomId"))) throw new IllegalArgumentException("Mixed-currency books require an approved currency-conversion disposal workflow.");
            if (detail.get("capitalizedCost") == null && detail.get("originalCost") == null) throw new IllegalArgumentException("The asset book cost is missing.");
            BigDecimal cost = money(detail.get("capitalizedCost") == null ? detail.get("originalCost") : detail.get("capitalizedCost"));
            if (cost.signum() == 0 && proceeds.signum() == 0) throw new IllegalArgumentException("Zero-cost retirement requires a separate non-financial retirement procedure.");
            BigDecimal accum = money(detail.get("accumulatedDepreciation"));
            if (money(detail.get("revaluationAmount")).signum() != 0 || money(detail.get("impairmentAmount")).signum() != 0) throw new IllegalArgumentException("Revalued or impaired assets require a valuation-reserve disposal workflow; ordinary cost disposal is blocked.");
            BigDecimal gain = gainLoss(cost, accum, proceeds);
            if (detail.getTimestamp("placedInServiceDate") == null || day.isBefore(detail.getTimestamp("placedInServiceDate").toLocalDateTime().toLocalDate())) throw new IllegalArgumentException("Disposal must follow placement in service.");
            LocalDate cutoff = day.withDayOfMonth(1).minusDays(1);
            if (!detail.getTimestamp("placedInServiceDate").toLocalDateTime().toLocalDate().isAfter(cutoff) && cost.subtract(accum).compareTo(money(detail.get("salvageValue"))) > 0
                    && (detail.getTimestamp("lastDepreciationDate") == null || detail.getTimestamp("lastDepreciationDate").toLocalDateTime().toLocalDate().isBefore(cutoff))) throw new IllegalArgumentException("Post depreciation through the previous month end before disposal. Partial current-month depreciation is not generated by disposal.");
            for (GenericValue schedule : EntityQuery.use(d).from("AssetsBookDepSchedule").where("fixedAssetId", id, "assetsBookId", bookId, "statusId", "ASSETS_POSTED").queryList()) {
                if (schedule.getDate("periodEndDate").toLocalDate().isAfter(day)) throw new IllegalArgumentException("Posted depreciation extends past disposal; reconcile or reverse it first.");
            }
            Map<String, Object> row = new TreeMap<>(); row.put("assetsBookId", bookId); row.put("currencyUomId", currency); row.put("isPostingBook", book.getString("isPostingBook"));
            row.put("bookDetailFromDate", detail.getTimestamp("fromDate")); row.put("originalCostAmount", cost); row.put("accumulatedDepAmount", accum); row.put("netBookValue", cost.subtract(accum)); row.put("proceedsAllocatedAmount", proceeds); row.put("gainLossAmount", gain);
            if ("Y".equals(book.getString("isPostingBook"))) {
                postingBooks++; GenericValue mapping = mapping(d, book, asset, date);
                for (String field : ACCOUNT_FIELDS) row.put(field, mapping.get(field));
                row.put("assetsBookGlAccountId", mapping.get("assetsBookGlAccountId"));
                AssetsGlMappingServices.validateAccount(d, asset.getString("partyId"), required(row, "assetGlAccountId"), "ASSET", date);
                if (accum.signum() > 0) AssetsGlMappingServices.validateAccount(d, asset.getString("partyId"), required(row, "accDepGlAccountId"), "ACCUM_DEPRECIATION", date);
                if (gain.signum() < 0) AssetsGlMappingServices.validateAccount(d, asset.getString("partyId"), required(row, "lossGlAccountId"), "EXPENSE", date);
                if (gain.signum() > 0) AssetsGlMappingServices.validateAccount(d, asset.getString("partyId"), required(row, "profitGlAccountId"), "INCOME", date);
                if (proceeds.signum() > 0) AssetsGlMappingServices.validateAccount(d, asset.getString("partyId"), required(row, "disposalProceedsGlAccountId"), "ASSET", date);
                Set<String> selected = new HashSet<>();
                for (String field : ACCOUNT_FIELDS) if (text(row.get(field)) != null && !selected.add(text(row.get(field)))) throw new IllegalArgumentException("Disposal cost, accumulated depreciation, proceeds and gain/loss require distinct accounts.");
            }
            books.add(row);
        }
        if (books.isEmpty() || postingBooks != 1) throw new IllegalArgumentException("Exactly one active posting book is required; other books may be non-posting in the same currency.");
        GenericValue preferences = one(d, "PartyAcctgPreference", Map.of("partyId", asset.getString("partyId")));
        validateBaseCurrency(currency, preferences.getString("baseCurrencyUomId"));
        if (proceeds.signum() > 0) validateSaleInvoice(d, in, asset.getString("partyId"), currency, proceeds, books, date);
        else if (text(in.get("invoiceId")) != null || text(in.get("invoiceItemSeqId")) != null) throw new IllegalArgumentException("Zero-proceeds disposal does not accept a sale invoice.");
        Map<String, Object> plan = new TreeMap<>(); plan.put("fixedAssetId", id); plan.put("partyId", asset.get("partyId")); plan.put("disposalTypeEnumId", type); plan.put("disposalDate", date.toString()); plan.put("proceedsAmount", proceeds); plan.put("currencyUomId", currency);
        plan.put("invoiceId", text(in.get("invoiceId"))); plan.put("invoiceItemSeqId", text(in.get("invoiceItemSeqId"))); plan.put("books", books);
        plan.put("fingerprint", fingerprint(plan.toString())); return plan;
    }

    private static void validateSaleInvoice(Delegator d, Map<String, ?> in, String org, String currency, BigDecimal proceeds, List<Map<String, Object>> books, Timestamp date) throws Exception {
        String invoiceId = required(in, "invoiceId"), seq = required(in, "invoiceItemSeqId");
        GenericValue invoice = one(d, "Invoice", Map.of("invoiceId", invoiceId));
        if (!org.equals(invoice.getString("partyIdFrom")) || !currency.equals(invoice.getString("currencyUomId")) || !"SALES_INVOICE".equals(invoice.getString("invoiceTypeId"))
                || !List.of("INVOICE_READY", "INVOICE_SENT", "INVOICE_PAID").contains(invoice.getString("statusId")) || invoice.getTimestamp("invoiceDate").after(date)) throw new IllegalArgumentException("Link a posted native sales invoice from this organization, in book currency, dated no later than disposal.");
        GenericValue item = one(d, "InvoiceItem", Map.of("invoiceId", invoiceId, "invoiceItemSeqId", seq));
        Map<String, Object> posting = books.stream().filter(b -> "Y".equals(b.get("isPostingBook"))).findFirst().orElseThrow();
        String clearing = required(posting, "disposalProceedsGlAccountId");
        if (!clearing.equals(item.getString("overrideGlAccountId")) || money(item.get("amount")).multiply(item.getBigDecimal("quantity") == null ? BigDecimal.ONE : item.getBigDecimal("quantity")).compareTo(proceeds) != 0) throw new IllegalArgumentException("The dedicated invoice line must credit the configured disposal clearing account for the exact net proceeds (excluding tax).");
        BigDecimal credit = BigDecimal.ZERO;
        for (GenericValue trans : EntityQuery.use(d).from("AcctgTrans").where("invoiceId", invoiceId, "isPosted", "Y").queryList()) {
            if (trans.getTimestamp("transactionDate").after(date)) throw new IllegalArgumentException("The sale invoice journal is dated after disposal.");
            for (GenericValue entry : EntityQuery.use(d).from("AcctgTransEntry").where("acctgTransId", trans.get("acctgTransId"), "organizationPartyId", org, "glAccountId", clearing).queryList()) {
                if (!currency.equals(entry.getString("currencyUomId"))) throw new IllegalArgumentException("Invoice clearing journal currency does not match.");
                credit = credit.add(money(entry.get("amount")).multiply("C".equals(entry.getString("debitCreditFlag")) ? BigDecimal.ONE : BigDecimal.ONE.negate()));
            }
        }
        if (credit.compareTo(proceeds) != 0) throw new IllegalArgumentException("Posted native invoice clearing credits must equal the net disposal proceeds; draft or unrelated invoices cannot be used.");
        GenericValue claim = EntityQuery.use(d).from("AssetsDisposalInvoiceClaim").where("invoiceId", invoiceId).queryOne();
        if (claim != null && !"ASSETS_CANCELLED".equals(claim.getString("statusId")) && !Objects.equals(claim.get("assetsDisposalId"), in.get("assetsDisposalId"))) throw new IllegalArgumentException("This invoice is already reserved for another disposal.");
    }

    private static void post(DispatchContext ctx, GenericValue request, GenericValue asset, Map<String, Object> plan, GenericValue user, GenericValue control) throws Exception {
        Delegator d = ctx.getDelegator(); String id = asset.getString("fixedAssetId");
        for (Map<String, Object> book : plans(plan)) {
            if ("Y".equals(book.get("isPostingBook"))) {
                List<Map<String, Object>> entries = entries(book, request.getString("partyId"), request.getString("currencyUomId"));
                if (entries.isEmpty()) throw new IllegalArgumentException("A zero-cost, zero-proceeds retirement requires a separate non-financial retirement procedure.");
                Map<String, Object> created = call(ctx, "createAcctgTransAndEntries", Map.of("acctgTransTypeId", "OTHER_INTERNAL", "glFiscalTypeId", "ACTUAL", "transactionDate", request.getTimestamp("disposalDate"), "fixedAssetId", id, "description", "Assets disposal " + request.getString("assetsDisposalId") + " / " + id + " / " + book.get("assetsBookId"), "acctgTransEntries", entries, "userLogin", user));
                String transId = (String) created.get("acctgTransId"); GenericValue trans = one(d, "AcctgTrans", Map.of("acctgTransId", transId));
                if (!"Y".equals(trans.getString("isPosted"))) call(ctx, "postAcctgTrans", Map.of("acctgTransId", transId, "userLogin", user));
                trans.refresh(); if (!"Y".equals(trans.getString("isPosted"))) throw new IllegalArgumentException("OFBiz did not post the disposal journal; retirement rolled back.");
                request.set("acctgTransId", transId);
                GenericValue snapshot = one(d, "AssetsDisposalBook", Map.of("assetsDisposalId", request.get("assetsDisposalId"), "assetsBookId", book.get("assetsBookId")));
                snapshot.set("acctgTransId", transId); snapshot.store();
            }
            GenericValue detail = EntityQuery.use(d).from("AssetsBookDetail").where("fixedAssetId", id, "assetsBookId", book.get("assetsBookId"), "fromDate", book.get("bookDetailFromDate")).queryOne();
            for (String field : List.of("capitalizedCost", "accumulatedDepreciation", "netBookValue", "salvageValue", "depreciableBasis")) detail.set(field, BigDecimal.ZERO);
            detail.set("remainingLifeMonths", 0L); detail.set("nextDepreciationDate", null); detail.set("statusId", "ASSETS_DISPOSED");
            detail.set("lastModifiedDate", UtilDateTime.nowTimestamp()); detail.set("lastModifiedByUserLogin", user.getString("userLoginId")); detail.store();
        }
        for (GenericValue schedule : EntityQuery.use(d).from("AssetsBookDepSchedule").where("fixedAssetId", id, "statusId", "ASSETS_PLANNED").queryList()) { schedule.set("statusId", "ASSETS_CANCELLED"); schedule.set("lastModifiedDate", UtilDateTime.nowTimestamp()); schedule.set("lastModifiedByUserLogin", user.getString("userLoginId")); schedule.store(); }
        call(ctx, "updateFixedAsset", Map.of("fixedAssetId", id, "fixedAssetTypeId", asset.getString("fixedAssetTypeId"), "actualEndOfLife", java.sql.Date.valueOf(request.getTimestamp("disposalDate").toLocalDateTime().toLocalDate()), "userLogin", user));
        control.set("availability", "RETIRED"); control.set("lastModifiedDate", UtilDateTime.nowTimestamp()); control.store();
        request.set("postedByUserLogin", user.getString("userLoginId")); request.set("postedDate", UtilDateTime.nowTimestamp());
        GenericValue lifecycle = d.makeValue("AssetsLifecycleEvent", Map.of("assetsLifecycleEventId", d.getNextSeqId("AssetsLifecycleEvent"), "fixedAssetId", id, "eventTypeEnumId", "ASSETS_EVT_RETIRE", "eventDate", request.getTimestamp("disposalDate"), "statusId", "ASSETS_DISPOSED", "description", "Controlled Assets disposal " + request.getString("assetsDisposalId"), "createdDate", UtilDateTime.nowTimestamp(), "createdByUserLogin", user.getString("userLoginId")));
        lifecycle.create();
    }

    static void validateBaseCurrency(String bookCurrency, String baseCurrency) {
        if (bookCurrency == null || !bookCurrency.equals(baseCurrency)) throw new IllegalArgumentException("Posting book currency must match the organization's current base currency; use a currency-conversion workflow otherwise.");
    }
    static BigDecimal gainLoss(BigDecimal cost, BigDecimal accumulated, BigDecimal proceeds) {
        if (cost == null || accumulated == null || proceeds == null || cost.signum() < 0 || accumulated.signum() < 0 || accumulated.compareTo(cost) > 0 || proceeds.signum() < 0) throw new IllegalArgumentException("Cost, accumulated depreciation and proceeds are inconsistent.");
        return proceeds.subtract(cost.subtract(accumulated)).setScale(2, RoundingMode.UNNECESSARY);
    }
    static List<Map<String, Object>> entries(Map<String, Object> book, String org, String currency) {
        BigDecimal cost = money(book.get("originalCostAmount")), accumulated = money(book.get("accumulatedDepAmount")), proceeds = money(book.get("proceedsAllocatedAmount"));
        BigDecimal gain = gainLoss(cost, accumulated, proceeds); List<Map<String, Object>> rows = new ArrayList<>();
        add(rows, org, currency, book.get("accDepGlAccountId"), "D", accumulated);
        add(rows, org, currency, book.get("disposalProceedsGlAccountId"), "D", proceeds);
        add(rows, org, currency, book.get("lossGlAccountId"), "D", gain.signum() < 0 ? gain.negate() : BigDecimal.ZERO);
        add(rows, org, currency, book.get("assetGlAccountId"), "C", cost);
        add(rows, org, currency, book.get("profitGlAccountId"), "C", gain.signum() > 0 ? gain : BigDecimal.ZERO);
        return rows;
    }
    private static void add(List<Map<String, Object>> rows, String org, String currency, Object account, String side, BigDecimal amount) {
        if (amount.signum() == 0) return; if (text(account) == null) throw new IllegalArgumentException("A required disposal account is missing.");
        rows.add(new HashMap<>(Map.of("organizationPartyId", org, "glAccountId", account, "debitCreditFlag", side, "amount", amount, "origAmount", amount, "currencyUomId", currency, "origCurrencyUomId", currency, "acctgTransEntryTypeId", "_NA_")));
    }
    static String nextStatus(String state, String action) {
        if ("APPROVE".equals(action) && "ASSETS_PENDING".equals(state)) return "ASSETS_APPROVED";
        if ("POST".equals(action) && "ASSETS_APPROVED".equals(state)) return "ASSETS_DISPOSED";
        if ("REJECT".equals(action) && OPEN.contains(state)) return "ASSETS_REJECTED";
        if ("CANCEL".equals(action) && OPEN.contains(state)) return "ASSETS_CANCELLED";
        throw new IllegalArgumentException("This disposal action is not allowed in the current state.");
    }
    static void requireSeparateApprover(String maker, String actor, boolean admin, boolean onBehalf) {
        if (maker.equals(actor) && !(admin && onBehalf)) throw new IllegalArgumentException("Another approver is required; an administrator must explicitly attest with a documented reason to act on behalf.");
    }
    static void requireNoOpenDisposal(Delegator d, String id) throws Exception {
        for (GenericValue row : EntityQuery.use(d).from("AssetsDisposal").where("fixedAssetId", id).queryList()) if (OPEN.contains(row.getString("statusId"))) throw new IllegalArgumentException("An open disposal exists; complete or cancel it first.");
    }
    private static GenericValue mapping(Delegator d, GenericValue book, GenericValue asset, Timestamp date) throws Exception {
        GenericValue selected = null; int score = -1; boolean ambiguous = false;
        for (GenericValue row : EntityQuery.use(d).from("AssetsBookGlAccount").where("assetsBookId", book.get("assetsBookId"), "statusId", "ASSETS_ACTIVE").queryList()) {
            if (!AssetsGlMappingServices.effective(row, date)) continue;
            String a = AssetsGlMappingServices.normalized(row.getString("fixedAssetId")), type = AssetsGlMappingServices.normalized(row.getString("fixedAssetTypeId"));
            if ((a != null && !a.equals(asset.getString("fixedAssetId"))) || (type != null && !type.equals(asset.getString("fixedAssetTypeId")))) continue;
            if (row.get("partyId") != null && !book.get("partyId").equals(row.get("partyId"))) continue;
            int rank = (a == null ? 0 : 10) + (type == null ? 0 : 1);
            if (rank == score) ambiguous = true;
            if (rank > score) { selected = row; score = rank; ambiguous = false; }
        }
        if (ambiguous) throw new IllegalArgumentException("Conflicting disposal account mappings at the same specificity.");
        if (selected == null) throw new IllegalArgumentException("Configure effective book/type/asset disposal GL accounts first."); return selected;
    }
    private static void reserveInvoice(Delegator d, GenericValue request) throws Exception {
        GenericValue claim = EntityQuery.use(d).from("AssetsDisposalInvoiceClaim").where("invoiceId", request.get("invoiceId")).queryOne();
        if (claim != null) {
            AssetsProcurementServices.lockRecord(claim);
            if (!"ASSETS_CANCELLED".equals(claim.getString("statusId"))) throw new IllegalArgumentException("Sale invoice is already reserved.");
        } else claim = d.makeValue("AssetsDisposalInvoiceClaim", Map.of("invoiceId", request.get("invoiceId")));
        boolean fresh = claim.get("assetsDisposalId") == null;
        claim.set("assetsDisposalId", request.get("assetsDisposalId")); claim.set("statusId", "ASSETS_ACTIVE"); claim.set("lastModifiedDate", UtilDateTime.nowTimestamp());
        if (fresh) claim.create(); else claim.store();
    }
    private static void lockDetails(Delegator d, String id) throws Exception {
        Set<String> books = new java.util.TreeSet<>();
        for (GenericValue detail : EntityQuery.use(d).from("AssetsBookDetail").where("fixedAssetId", id).orderBy("assetsBookId", "fromDate").queryList()) {
            AssetsProcurementServices.lockRecord(detail); books.add(detail.getString("assetsBookId"));
        }
        // GL configuration changes lock the same book, keeping approval/posting selection stable.
        for (String bookId : books) AssetsProcurementServices.lockRecord(one(d, "AssetsBook", Map.of("assetsBookId", bookId)));
    }
    private static void event(Delegator d, GenericValue request, GenericValue user, String action, Map<String, ?> in) throws Exception {
        GenericValue row = d.makeValue("AssetsDisposalEvent", Map.of("assetsDisposalEventId", d.getNextSeqId("AssetsDisposalEvent"), "assetsDisposalId", request.get("assetsDisposalId"), "fixedAssetId", request.get("fixedAssetId"), "eventAction", action, "statusId", request.get("statusId"), "actorUserLoginId", user.get("userLoginId"), "onBehalf", "Y".equals(in.get("onBehalf")) ? "Y" : "N", "comments", required(in, "comments"), "eventDate", UtilDateTime.nowTimestamp())); row.create();
    }
    private static Map<String, Object> result(GenericValue row, String replayed) { Map<String, Object> out = ServiceUtil.returnSuccess(); out.put("assetsDisposalId", row.get("assetsDisposalId")); out.put("statusId", row.get("statusId")); out.put("acctgTransId", row.get("acctgTransId")); out.put("replayed", replayed); return out; }
    @SuppressWarnings("unchecked") private static List<Map<String, Object>> plans(Map<String, Object> plan) { return (List<Map<String, Object>>) plan.get("books"); }
    private static GenericValue one(Delegator d, String entity, Map<String, ?> key) throws Exception { return AssetsProcurementServices.one(d, entity, key); }
    private static Map<String, Object> call(DispatchContext ctx, String name, Map<String, Object> in) throws Exception { return AssetsProcurementServices.call(ctx, name, in); }
    private static String required(Map<String, ?> in, String field) { String value = text(in.get(field)); if (value == null) throw new IllegalArgumentException(field + " is required."); return value; }
    private static String text(Object value) { return value == null || value.toString().isBlank() ? null : value.toString().trim(); }
    private static BigDecimal money(Object value) { return value == null ? BigDecimal.ZERO.setScale(2) : new BigDecimal(value.toString()).setScale(2, RoundingMode.UNNECESSARY); }
    private static String fingerprint(String input) throws Exception { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(input.getBytes(StandardCharsets.UTF_8))); }
    private static Map<String, Object> failure(Exception e) { Debug.logError(e, "Assets disposal failed; transaction rolled back", AssetsDisposalServices.class.getName()); return ServiceUtil.returnError(e instanceof IllegalArgumentException ? e.getMessage() : "Disposal failed. No retirement or journal changes were committed. Review the native accounting setup and server log."); }
}
