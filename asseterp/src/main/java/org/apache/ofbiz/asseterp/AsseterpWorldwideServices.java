/*
 * AssetERP worldwide workflow services: IFRS 16 leases, hire purchase,
 * CIP capitalization, government grants, IAS 36 impairment, IAS 37 ARO,
 * IFRS 5 held-for-sale, intercompany transfer, insurance claims and
 * software license seats.
 *
 * GL accounts resolve from AsseterpGlMapping (partyId + glMappingTypeId,
 * effective-dated, optional fixedAssetTypeId) with AsseterpOrgConfig as
 * the fallback for the classic accounts. All ledger effects go through
 * the standard createAcctgTrans / createAcctgTransEntry / postAcctgTrans
 * services, every entry result checked.
 */
package org.apache.ofbiz.asseterp;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.sql.Timestamp;
import java.util.List;
import java.util.Map;

import org.apache.ofbiz.base.util.UtilDateTime;
import org.apache.ofbiz.base.util.UtilMisc;
import org.apache.ofbiz.base.util.UtilValidate;
import org.apache.ofbiz.entity.Delegator;
import org.apache.ofbiz.entity.GenericEntityException;
import org.apache.ofbiz.entity.GenericValue;
import org.apache.ofbiz.entity.util.EntityQuery;
import org.apache.ofbiz.service.DispatchContext;
import org.apache.ofbiz.service.GenericServiceException;
import org.apache.ofbiz.service.LocalDispatcher;
import org.apache.ofbiz.service.ServiceUtil;

public final class AsseterpWorldwideServices {

    private static final int SCALE = 2;
    private static final RoundingMode RM = RoundingMode.HALF_UP;

    private AsseterpWorldwideServices() { }

    /* ==================== shared helpers ==================== */

    private static class GlException extends Exception {
        private static final long serialVersionUID = 1L;
        GlException(String msg) { super(msg); }
    }

    /** Period-close guard: refuses any posting whose transaction date falls
     *  inside a closed CustomTimePeriod of the organisation. Shared by all
     *  asseterp GL posting paths (Phase A close control). */
    public static void assertOpenPeriod(Delegator delegator,
            String organizationPartyId, Timestamp transactionDate)
            throws GenericServiceException {
        if (organizationPartyId == null || transactionDate == null) {
            return;
        }
        try {
            List<GenericValue> closed = EntityQuery.use(delegator)
                    .from("CustomTimePeriod")
                    .where("organizationPartyId", organizationPartyId,
                           "isClosed", "Y")
                    .queryList();
            long d = transactionDate.getTime();
            for (GenericValue p : closed) {
                java.util.Date from = (java.util.Date) p.get("fromDate");
                java.util.Date thru = (java.util.Date) p.get("thruDate");
                if (from != null && thru != null
                        && d >= from.getTime()
                        && d < thru.getTime() + 86400000L) {
                    throw new GenericServiceException(
                        "Fiscal period " + p.get("customTimePeriodId")
                        + " (" + from + " to " + thru + ") is closed for "
                        + organizationPartyId
                        + "; posting dated " + transactionDate
                        + " is not allowed");
                }
            }
        } catch (GenericEntityException e) {
            throw new GenericServiceException(
                "Period-close check failed: " + e.getMessage());
        }
    }

    /** AsseterpGlMapping lookup: (party, mapType, assetType) then
     *  (party, mapType, null/_NA_); falls back to the classic
     *  AsseterpOrgConfig account for the known map types. */
    private static String resolveGl(Delegator delegator, String partyId,
            String glMappingTypeId, String fixedAssetTypeId)
            throws GenericEntityException {
        Timestamp now = UtilDateTime.nowTimestamp();
        List<GenericValue> rows = EntityQuery.use(delegator)
                .from("AsseterpGlMapping")
                .where("partyId", partyId, "glMappingTypeId", glMappingTypeId)
                .filterByDate(now, "fromDate", "thruDate")
                .queryList();
        String generic = null;
        for (GenericValue row : rows) {
            String rowType = row.getString("fixedAssetTypeId");
            if (fixedAssetTypeId != null && fixedAssetTypeId.equals(rowType)) {
                return row.getString("glAccountId");
            }
            if (rowType == null || "_NA_".equals(rowType)) {
                generic = row.getString("glAccountId");
            }
        }
        if (generic != null) {
            return generic;
        }
        GenericValue cfg = delegator.findOne("AsseterpOrgConfig",
                UtilMisc.toMap("partyId", partyId), false);
        if (cfg == null) {
            return null;
        }
        switch (glMappingTypeId) {
        case "GL_MAP_COST": return cfg.getString("assetCostGlAccountId");
        case "GL_MAP_ACCUM_DEP": return cfg.getString("accumDepGlAccountId");
        case "GL_MAP_DEP_EXP": return cfg.getString("depExpenseGlAccountId");
        case "GL_MAP_GAIN": return cfg.getString("gainOnDisposalGlAccountId");
        case "GL_MAP_LOSS": return cfg.getString("lossOnDisposalGlAccountId");
        case "GL_MAP_CIP": return cfg.getString("cipGlAccountId");
        case "GL_MAP_ROU_ASSET": return cfg.getString("assetCostGlAccountId");
        case "GL_MAP_IMPAIR": return cfg.getString("lossOnDisposalGlAccountId");
        default: return null;
        }
    }

    private static String requireGl(Delegator delegator, String partyId,
            String glMappingTypeId, String fixedAssetTypeId)
            throws GenericEntityException, GlException {
        String glAccountId = resolveGl(delegator, partyId, glMappingTypeId,
                fixedAssetTypeId);
        if (UtilValidate.isEmpty(glAccountId)) {
            throw new GlException("No GL account mapped for " + glMappingTypeId
                    + " (org " + partyId + "). Add an AsseterpGlMapping row or"
                    + " complete the Org Config.");
        }
        return glAccountId;
    }

    /** One balanced journal through the standard accounting services.
     *  entries: each row {debitCredit D|C, glAccountId, amount}. */
    private static String postJournal(LocalDispatcher dispatcher,
            GenericValue userLogin, String acctgTransTypeId, String partyId,
            String fixedAssetId, Timestamp transactionDate, String description,
            String currencyUomId, List<Map<String, Object>> entries,
            boolean postNow)
            throws GenericServiceException, GlException {
        assertOpenPeriod(dispatcher.getDelegator(), partyId, transactionDate);
        Map<String, Object> transCtx = UtilMisc.toMap(
                "acctgTransTypeId", acctgTransTypeId,
                "glFiscalTypeId", "ACTUAL",
                "transactionDate", transactionDate,
                "description", description,
                "userLogin", userLogin);
        if (fixedAssetId != null) {
            transCtx.put("fixedAssetId", fixedAssetId);
        }
        Map<String, Object> transResult =
                dispatcher.runSync("createAcctgTrans", transCtx);
        if (ServiceUtil.isError(transResult)) {
            throw new GlException(ServiceUtil.getErrorMessage(transResult));
        }
        String acctgTransId = (String) transResult.get("acctgTransId");
        int seq = 1;
        for (Map<String, Object> entry : entries) {
            BigDecimal amount = (BigDecimal) entry.get("amount");
            if (amount == null || amount.signum() <= 0) {
                continue;
            }
            Map<String, Object> entryCtx = UtilMisc.toMap(
                    "acctgTransId", acctgTransId,
                    "acctgTransEntrySeqId", String.format("%05d", seq++),
                    "acctgTransEntryTypeId", "_NA_",
                    "debitCreditFlag", entry.get("debitCredit"),
                    "glAccountId", entry.get("glAccountId"),
                    "organizationPartyId", partyId,
                    "amount", amount.setScale(SCALE, RM),
                    "currencyUomId", currencyUomId,
                    "userLogin", userLogin);
            Map<String, Object> entryResult =
                    dispatcher.runSync("createAcctgTransEntry", entryCtx);
            if (ServiceUtil.isError(entryResult)) {
                throw new GlException(ServiceUtil.getErrorMessage(entryResult));
            }
        }
        if (postNow) {
            Map<String, Object> postResult = dispatcher.runSync(
                    "postAcctgTrans", UtilMisc.toMap(
                            "acctgTransId", acctgTransId,
                            "userLogin", userLogin));
            if (ServiceUtil.isError(postResult)) {
                throw new GlException(ServiceUtil.getErrorMessage(postResult));
            }
        }
        return acctgTransId;
    }

    private static Map<String, Object> entry(String debitCredit,
            String glAccountId, BigDecimal amount) {
        return UtilMisc.toMap("debitCredit", debitCredit,
                "glAccountId", glAccountId, "amount", amount);
    }

    private static void event(LocalDispatcher dispatcher,
            GenericValue userLogin, String fixedAssetId, String partyId,
            String statusId, String enumId, Timestamp eventDate,
            String description, String serviceName, String sourceReferenceId,
            BigDecimal amount, String currencyUomId)
            throws GenericServiceException {
        Map<String, Object> ctx = UtilMisc.toMap(
                "fixedAssetId", fixedAssetId,
                "enumId", enumId,
                "eventDate", eventDate,
                "description", description,
                "serviceName", serviceName,
                "userLogin", userLogin);
        if (partyId != null) {
            ctx.put("partyId", partyId);
        }
        if (statusId != null) {
            ctx.put("statusId", statusId);
        }
        if (sourceReferenceId != null) {
            ctx.put("sourceReferenceId", sourceReferenceId);
        }
        if (amount != null) {
            ctx.put("amount", amount);
        }
        if (currencyUomId != null) {
            ctx.put("currencyUomId", currencyUomId);
        }
        dispatcher.runSync("asseterpCreateLifecycleEvent", ctx);
    }

    private static BigDecimal nz(BigDecimal value) {
        return value == null ? BigDecimal.ZERO : value;
    }

    private static boolean yes(Object flag) {
        return flag == null || "Y".equals(flag);
    }

    private static Timestamp tsOrNow(Object value) {
        return value != null ? (Timestamp) value : UtilDateTime.nowTimestamp();
    }

    private static String orgCurrency(Delegator delegator, String partyId,
            Object given) throws GenericEntityException {
        if (given != null) {
            return (String) given;
        }
        GenericValue cfg = delegator.findOne("AsseterpOrgConfig",
                UtilMisc.toMap("partyId", partyId), false);
        return cfg != null ? cfg.getString("currencyUomId") : "USD";
    }

    /* ==================== IFRS 16 lease ==================== */

    public static Map<String, Object> asseterpCreateLease(DispatchContext dctx,
            Map<String, ? extends Object> context) {
        Delegator delegator = dctx.getDelegator();
        LocalDispatcher dispatcher = dctx.getDispatcher();
        GenericValue userLogin = (GenericValue) context.get("userLogin");
        String fixedAssetId = (String) context.get("fixedAssetId");
        String partyId = (String) context.get("partyId");
        Timestamp startDate = tsOrNow(context.get("leaseStartDate"));
        long termMonths = ((Number) context.get("termMonths")).longValue();
        BigDecimal payment = (BigDecimal) context.get("monthlyPayment");
        BigDecimal annualRate = (BigDecimal) context.get("annualDiscountRate");
        if (termMonths <= 0 || payment == null || payment.signum() <= 0) {
            return ServiceUtil.returnError(
                    "termMonths and monthlyPayment must be positive");
        }
        try {
            String currencyUomId = orgCurrency(delegator, partyId,
                    context.get("currencyUomId"));
            BigDecimal monthlyRate = annualRate
                    .divide(BigDecimal.valueOf(12), 10, RM);
            // PV of ordinary annuity; zero-rate edge = n * pmt
            BigDecimal presentValue;
            if (monthlyRate.signum() == 0) {
                presentValue = payment.multiply(BigDecimal.valueOf(termMonths));
            } else {
                BigDecimal onePlus = BigDecimal.ONE.add(monthlyRate);
                BigDecimal discount = BigDecimal.ONE.divide(
                        onePlus.pow((int) termMonths), 10, RM);
                presentValue = payment.multiply(
                        BigDecimal.ONE.subtract(discount))
                        .divide(monthlyRate, 10, RM);
            }
            presentValue = presentValue.setScale(SCALE, RM);

            String leaseId = delegator.getNextSeqId("AsseterpLease");
            GenericValue lease = delegator.makeValue("AsseterpLease");
            lease.set("asseterpLeaseId", leaseId);
            lease.set("fixedAssetId", fixedAssetId);
            lease.set("lessorPartyId", context.get("lessorPartyId"));
            lease.set("leaseTypeId", context.get("leaseTypeId") != null
                    ? context.get("leaseTypeId") : "LEASE_FINANCE");
            lease.set("leaseStartDate", startDate);
            lease.set("leaseEndDate", UtilDateTime.addDaysToTimestamp(
                    startDate, (int) (termMonths * 30)));
            lease.set("discountRate", annualRate.doubleValue());
            lease.set("presentValueLiability", presentValue);
            lease.set("rouAssetValue", presentValue);
            lease.set("currencyUomId", currencyUomId);
            lease.set("statusId", "AE_LEASE_ACTIVE");
            lease.create();

            // amortization schedule
            BigDecimal opening = presentValue;
            for (int i = 1; i <= termMonths; i++) {
                BigDecimal interest = opening.multiply(monthlyRate)
                        .setScale(SCALE, RM);
                BigDecimal principal = payment.subtract(interest);
                BigDecimal closing = opening.subtract(principal);
                if (i == termMonths) {
                    // rounding trues up in the final row
                    principal = principal.add(closing);
                    closing = BigDecimal.ZERO;
                }
                GenericValue row = delegator.makeValue("AsseterpLeaseSchedule");
                row.set("asseterpLeaseScheduleId",
                        delegator.getNextSeqId("AsseterpLeaseSchedule"));
                row.set("asseterpLeaseId", leaseId);
                row.set("paymentDate", UtilDateTime.addDaysToTimestamp(
                        startDate, i * 30));
                row.set("openingLiability", opening.setScale(SCALE, RM));
                row.set("payment", payment.setScale(SCALE, RM));
                row.set("interestCharge", interest);
                row.set("principalRepayment", principal.setScale(SCALE, RM));
                row.set("closingLiability", closing.setScale(SCALE, RM));
                row.create();
                opening = closing;
            }

            GenericValue fixedAsset = delegator.findOne("FixedAsset",
                    UtilMisc.toMap("fixedAssetId", fixedAssetId), false);
            String assetTypeId = fixedAsset != null
                    ? fixedAsset.getString("fixedAssetTypeId") : null;
            String rouGl = requireGl(delegator, partyId, "GL_MAP_ROU_ASSET",
                    assetTypeId);
            String liabGl = requireGl(delegator, partyId, "GL_MAP_LEASE_LIAB",
                    assetTypeId);
            String acctgTransId = postJournal(dispatcher, userLogin,
                    "AE_LEASE_POSTING", partyId, fixedAssetId, startDate,
                    "IFRS 16 initial recognition, lease " + leaseId,
                    currencyUomId,
                    UtilMisc.toList(entry("D", rouGl, presentValue),
                            entry("C", liabGl, presentValue)),
                    yes(context.get("postNow")));
            lease.set("acctgTransId", acctgTransId);
            lease.store();

            event(dispatcher, userLogin, fixedAssetId, partyId,
                    "ASSET_LEASED_IN", "AEVT_LEASE_START", startDate,
                    "Lease recognized, PV " + presentValue,
                    "asseterpCreateLease", acctgTransId, presentValue,
                    currencyUomId);

            Map<String, Object> result = ServiceUtil.returnSuccess();
            result.put("asseterpLeaseId", leaseId);
            result.put("presentValueLiability", presentValue);
            result.put("acctgTransId", acctgTransId);
            return result;
        } catch (GlException | GenericEntityException
                | GenericServiceException e) {
            return ServiceUtil.returnError(e.getMessage());
        }
    }

    public static Map<String, Object> asseterpPostLeasePayment(
            DispatchContext dctx, Map<String, ? extends Object> context) {
        Delegator delegator = dctx.getDelegator();
        LocalDispatcher dispatcher = dctx.getDispatcher();
        GenericValue userLogin = (GenericValue) context.get("userLogin");
        String scheduleId = (String) context.get("asseterpLeaseScheduleId");
        try {
            GenericValue row = delegator.findOne("AsseterpLeaseSchedule",
                    UtilMisc.toMap("asseterpLeaseScheduleId", scheduleId),
                    false);
            if (row == null) {
                return ServiceUtil.returnError(
                        "Lease schedule row not found: " + scheduleId);
            }
            if (row.get("acctgTransId") != null) {
                return ServiceUtil.returnError(
                        "Schedule row already posted: " + scheduleId);
            }
            GenericValue lease = delegator.findOne("AsseterpLease",
                    UtilMisc.toMap("asseterpLeaseId",
                            row.get("asseterpLeaseId")), false);
            String partyId = null;
            GenericValue fixedAsset = delegator.findOne("FixedAsset",
                    UtilMisc.toMap("fixedAssetId",
                            lease.get("fixedAssetId")), false);
            // GL org: the org that recognized the lease (OrgConfig owner).
            // The register stores it on the initial AcctgTrans; read the
            // mapping org from any entry of the recognition transaction.
            GenericValue initialEntry = EntityQuery.use(delegator)
                    .from("AcctgTransEntry")
                    .where("acctgTransId", lease.get("acctgTransId"))
                    .queryFirst();
            if (initialEntry != null) {
                partyId = initialEntry.getString("organizationPartyId");
            }
            if (partyId == null) {
                return ServiceUtil.returnError(
                        "Cannot determine GL organization for lease "
                                + lease.get("asseterpLeaseId"));
            }
            String assetTypeId = fixedAsset != null
                    ? fixedAsset.getString("fixedAssetTypeId") : null;
            String currencyUomId = lease.getString("currencyUomId");
            String liabGl = requireGl(delegator, partyId,
                    "GL_MAP_LEASE_LIAB", assetTypeId);
            String intGl = requireGl(delegator, partyId,
                    "GL_MAP_INT_EXP", assetTypeId);
            String cashGl = requireGl(delegator, partyId,
                    "GL_MAP_CASH", assetTypeId);
            String acctgTransId = postJournal(dispatcher, userLogin,
                    "AE_LEASE_POSTING", partyId,
                    lease.getString("fixedAssetId"),
                    row.getTimestamp("paymentDate"),
                    "Lease payment, schedule " + scheduleId, currencyUomId,
                    UtilMisc.toList(
                            entry("D", intGl, nz(row.getBigDecimal("interestCharge"))),
                            entry("D", liabGl, nz(row.getBigDecimal("principalRepayment"))),
                            entry("C", cashGl, nz(row.getBigDecimal("payment")))),
                    yes(context.get("postNow")));
            row.set("acctgTransId", acctgTransId);
            row.store();
            if (nz(row.getBigDecimal("closingLiability"))
                    .compareTo(new BigDecimal("0.01")) < 0) {
                lease.set("statusId", "AE_LEASE_EXPIRED");
                lease.store();
            }
            event(dispatcher, userLogin, lease.getString("fixedAssetId"),
                    partyId, null, "AEVT_LEASE_PAY",
                    row.getTimestamp("paymentDate"),
                    "Lease payment posted", "asseterpPostLeasePayment",
                    acctgTransId, row.getBigDecimal("payment"),
                    currencyUomId);
            Map<String, Object> result = ServiceUtil.returnSuccess();
            result.put("acctgTransId", acctgTransId);
            return result;
        } catch (GlException | GenericEntityException
                | GenericServiceException e) {
            return ServiceUtil.returnError(e.getMessage());
        }
    }

    public static Map<String, Object> asseterpTerminateLease(
            DispatchContext dctx, Map<String, ? extends Object> context) {
        Delegator delegator = dctx.getDelegator();
        LocalDispatcher dispatcher = dctx.getDispatcher();
        GenericValue userLogin = (GenericValue) context.get("userLogin");
        try {
            GenericValue lease = delegator.findOne("AsseterpLease",
                    UtilMisc.toMap("asseterpLeaseId",
                            context.get("asseterpLeaseId")), false);
            if (lease == null) {
                return ServiceUtil.returnError("Lease not found");
            }
            lease.set("statusId", "AE_LEASE_TERM");
            lease.set("leaseEndDate", tsOrNow(context.get("terminationDate")));
            lease.store();
            event(dispatcher, userLogin, lease.getString("fixedAssetId"),
                    null, null, "AEVT_LEASE_END",
                    tsOrNow(context.get("terminationDate")),
                    context.get("reason") != null
                            ? (String) context.get("reason")
                            : "Lease terminated",
                    "asseterpTerminateLease", null, null, null);
            return ServiceUtil.returnSuccess();
        } catch (GenericEntityException | GenericServiceException e) {
            return ServiceUtil.returnError(e.getMessage());
        }
    }

    /* ==================== hire purchase ==================== */

    public static Map<String, Object> asseterpCreateHirePurchase(
            DispatchContext dctx, Map<String, ? extends Object> context) {
        Delegator delegator = dctx.getDelegator();
        String fixedAssetId = (String) context.get("fixedAssetId");
        BigDecimal principal = (BigDecimal) context.get("principalAmount");
        BigDecimal annualRate = (BigDecimal) context.get("interestRate");
        long termMonths = ((Number) context.get("termMonths")).longValue();
        Timestamp startDate = tsOrNow(context.get("startDate"));
        if (principal == null || principal.signum() <= 0 || termMonths <= 0) {
            return ServiceUtil.returnError(
                    "principalAmount and termMonths must be positive");
        }
        try {
            BigDecimal monthlyRate = annualRate
                    .divide(BigDecimal.valueOf(12), 10, RM);
            BigDecimal installment;
            if (monthlyRate.signum() == 0) {
                installment = principal.divide(
                        BigDecimal.valueOf(termMonths), SCALE, RM);
            } else {
                BigDecimal onePlus = BigDecimal.ONE.add(monthlyRate);
                BigDecimal discount = BigDecimal.ONE.divide(
                        onePlus.pow((int) termMonths), 10, RM);
                installment = principal.multiply(monthlyRate)
                        .divide(BigDecimal.ONE.subtract(discount), SCALE, RM);
            }
            String hpId = delegator.getNextSeqId("AsseterpHirePurchase");
            GenericValue hp = delegator.makeValue("AsseterpHirePurchase");
            hp.set("asseterpHirePurchaseId", hpId);
            hp.set("fixedAssetId", fixedAssetId);
            hp.set("financierPartyId", context.get("financierPartyId"));
            hp.set("principalAmount", principal.setScale(SCALE, RM));
            hp.set("interestRate", annualRate.doubleValue());
            hp.set("termMonths", termMonths);
            hp.set("startDate", startDate);
            hp.set("currencyUomId", context.get("currencyUomId") != null
                    ? context.get("currencyUomId") : "USD");
            hp.set("statusId", "AE_HP_ACTIVE");
            hp.create();

            BigDecimal opening = principal;
            for (int i = 1; i <= termMonths; i++) {
                BigDecimal finance = opening.multiply(monthlyRate)
                        .setScale(SCALE, RM);
                BigDecimal princPart = installment.subtract(finance);
                BigDecimal closing = opening.subtract(princPart);
                if (i == termMonths) {
                    princPart = princPart.add(closing);
                    closing = BigDecimal.ZERO;
                }
                GenericValue row =
                        delegator.makeValue("AsseterpHirePurchaseSchedule");
                row.set("asseterpHirePurchaseScheduleId", delegator
                        .getNextSeqId("AsseterpHirePurchaseSchedule"));
                row.set("asseterpHirePurchaseId", hpId);
                row.set("dueDate",
                        UtilDateTime.addDaysToTimestamp(startDate, i * 30));
                row.set("openingBalance", opening.setScale(SCALE, RM));
                row.set("installment", installment);
                row.set("principal", princPart.setScale(SCALE, RM));
                row.set("financeCharge", finance);
                row.set("closingBalance", closing.setScale(SCALE, RM));
                row.create();
                opening = closing;
            }
            Map<String, Object> result = ServiceUtil.returnSuccess();
            result.put("asseterpHirePurchaseId", hpId);
            result.put("monthlyInstallment", installment);
            return result;
        } catch (GenericEntityException e) {
            return ServiceUtil.returnError(e.getMessage());
        }
    }

    public static Map<String, Object> asseterpPayHirePurchaseInstallment(
            DispatchContext dctx, Map<String, ? extends Object> context) {
        Delegator delegator = dctx.getDelegator();
        LocalDispatcher dispatcher = dctx.getDispatcher();
        GenericValue userLogin = (GenericValue) context.get("userLogin");
        try {
            GenericValue row = delegator.findOne(
                    "AsseterpHirePurchaseSchedule",
                    UtilMisc.toMap("asseterpHirePurchaseScheduleId",
                            context.get("asseterpHirePurchaseScheduleId")),
                    false);
            if (row == null) {
                return ServiceUtil.returnError("HP schedule row not found");
            }
            if (row.get("paidDate") != null) {
                return ServiceUtil.returnError("Installment already paid");
            }
            GenericValue hp = delegator.findOne("AsseterpHirePurchase",
                    UtilMisc.toMap("asseterpHirePurchaseId",
                            row.get("asseterpHirePurchaseId")), false);
            GenericValue custody = EntityQuery.use(delegator)
                    .from("AsseterpOrgConfig").queryFirst();
            String partyId = custody != null
                    ? custody.getString("partyId") : null;
            // prefer the org that owns the asset's register entry
            GenericValue fixedAsset = delegator.findOne("FixedAsset",
                    UtilMisc.toMap("fixedAssetId", hp.get("fixedAssetId")),
                    false);
            String assetTypeId = fixedAsset != null
                    ? fixedAsset.getString("fixedAssetTypeId") : null;
            String currencyUomId = hp.getString("currencyUomId");
            String liabGl = requireGl(delegator, partyId, "GL_MAP_HP_LIAB",
                    assetTypeId);
            String intGl = requireGl(delegator, partyId, "GL_MAP_INT_EXP",
                    assetTypeId);
            String cashGl = requireGl(delegator, partyId, "GL_MAP_CASH",
                    assetTypeId);
            Timestamp paidDate = tsOrNow(context.get("paidDate"));
            String acctgTransId = postJournal(dispatcher, userLogin,
                    "AE_HP_POSTING", partyId, hp.getString("fixedAssetId"),
                    paidDate, "HP installment "
                            + row.get("asseterpHirePurchaseScheduleId"),
                    currencyUomId,
                    UtilMisc.toList(
                            entry("D", liabGl, nz(row.getBigDecimal("principal"))),
                            entry("D", intGl, nz(row.getBigDecimal("financeCharge"))),
                            entry("C", cashGl, nz(row.getBigDecimal("installment")))),
                    yes(context.get("postNow")));
            row.set("paidDate", paidDate);
            row.set("acctgTransId", acctgTransId);
            row.store();
            long unpaid = EntityQuery.use(delegator)
                    .from("AsseterpHirePurchaseSchedule")
                    .where("asseterpHirePurchaseId",
                            hp.get("asseterpHirePurchaseId"))
                    .queryList().stream()
                    .filter(r -> r.get("paidDate") == null).count();
            if (unpaid == 0) {
                hp.set("statusId", "AE_HP_SETTLED");
                hp.store();
            }
            event(dispatcher, userLogin, hp.getString("fixedAssetId"),
                    partyId, null, "AEVT_HP_PAY", paidDate,
                    "Hire purchase installment paid",
                    "asseterpPayHirePurchaseInstallment", acctgTransId,
                    row.getBigDecimal("installment"), currencyUomId);
            Map<String, Object> result = ServiceUtil.returnSuccess();
            result.put("acctgTransId", acctgTransId);
            return result;
        } catch (GlException | GenericEntityException
                | GenericServiceException e) {
            return ServiceUtil.returnError(e.getMessage());
        }
    }

    /* ==================== CIP ==================== */

    public static Map<String, Object> asseterpAddCipCost(DispatchContext dctx,
            Map<String, ? extends Object> context) {
        Delegator delegator = dctx.getDelegator();
        LocalDispatcher dispatcher = dctx.getDispatcher();
        GenericValue userLogin = (GenericValue) context.get("userLogin");
        String projectId = (String) context.get("asseterpCipProjectId");
        BigDecimal amount = (BigDecimal) context.get("amount");
        try {
            GenericValue project = delegator.findOne("AsseterpCipProject",
                    UtilMisc.toMap("asseterpCipProjectId", projectId), false);
            if (project == null) {
                return ServiceUtil.returnError(
                        "CIP project not found: " + projectId);
            }
            if (!"AE_CIP_IN_PROG".equals(project.getString("statusId"))) {
                return ServiceUtil.returnError(
                        "CIP project is not in progress");
            }
            String partyId = project.getString("partyId");
            String currencyUomId = orgCurrency(delegator, partyId,
                    project.get("currencyUomId"));
            Timestamp costDate = tsOrNow(context.get("costDate"));
            String costId = delegator.getNextSeqId("AsseterpCipCost");
            GenericValue cost = delegator.makeValue("AsseterpCipCost");
            cost.set("asseterpCipCostId", costId);
            cost.set("asseterpCipProjectId", projectId);
            cost.set("invoiceId", context.get("invoiceId"));
            cost.set("invoiceItemSeqId", context.get("invoiceItemSeqId"));
            cost.set("costDate", costDate);
            cost.set("amount", amount.setScale(SCALE, RM));
            cost.set("currencyUomId", currencyUomId);
            cost.set("description", context.get("description"));
            String acctgTransId = null;
            if ("Y".equals(context.get("postGl"))) {
                String cipGl = requireGl(delegator, partyId, "GL_MAP_CIP",
                        null);
                String cashGl = requireGl(delegator, partyId, "GL_MAP_CASH",
                        null);
                acctgTransId = postJournal(dispatcher, userLogin,
                        "AE_CIP_CAPITALIZE", partyId, null, costDate,
                        "CIP cost, project " + projectId, currencyUomId,
                        UtilMisc.toList(entry("D", cipGl, amount),
                                entry("C", cashGl, amount)), true);
                cost.set("glAccountId", cipGl);
            }
            cost.create();
            project.set("totalCost",
                    nz(project.getBigDecimal("totalCost")).add(amount));
            project.store();
            Map<String, Object> result = ServiceUtil.returnSuccess();
            result.put("asseterpCipCostId", costId);
            result.put("acctgTransId", acctgTransId);
            return result;
        } catch (GlException | GenericEntityException
                | GenericServiceException e) {
            return ServiceUtil.returnError(e.getMessage());
        }
    }

    public static Map<String, Object> asseterpCapitalizeCipProject(
            DispatchContext dctx, Map<String, ? extends Object> context) {
        Delegator delegator = dctx.getDelegator();
        LocalDispatcher dispatcher = dctx.getDispatcher();
        GenericValue userLogin = (GenericValue) context.get("userLogin");
        String projectId = (String) context.get("asseterpCipProjectId");
        try {
            GenericValue project = delegator.findOne("AsseterpCipProject",
                    UtilMisc.toMap("asseterpCipProjectId", projectId), false);
            if (project == null) {
                return ServiceUtil.returnError(
                        "CIP project not found: " + projectId);
            }
            if (!"AE_CIP_IN_PROG".equals(project.getString("statusId"))) {
                return ServiceUtil.returnError(
                        "CIP project is not in progress");
            }
            BigDecimal total = nz(project.getBigDecimal("totalCost"));
            if (total.signum() <= 0) {
                return ServiceUtil.returnError(
                        "CIP project has no accumulated cost");
            }
            String partyId = project.getString("partyId");
            String currencyUomId = orgCurrency(delegator, partyId,
                    project.get("currencyUomId"));
            Timestamp capDate = tsOrNow(context.get("capitalizedDate"));
            GenericValue cfg = delegator.findOne("AsseterpOrgConfig",
                    UtilMisc.toMap("partyId", partyId), false);
            String fixedAssetTypeId = context.get("fixedAssetTypeId") != null
                    ? (String) context.get("fixedAssetTypeId")
                    : (cfg != null
                            ? cfg.getString("defaultFixedAssetTypeId")
                            : null);
            // standard service creates the register entry
            Map<String, Object> faCtx = UtilMisc.toMap(
                    "fixedAssetTypeId", fixedAssetTypeId,
                    "fixedAssetName", context.get("fixedAssetName") != null
                            ? context.get("fixedAssetName")
                            : project.get("projectName"),
                    "purchaseCost", total,
                    "purchaseCostUomId", currencyUomId,
                    "dateAcquired", capDate,
                    "actualServiceLife", null,
                    "userLogin", userLogin);
            Map<String, Object> faResult =
                    dispatcher.runSync("createFixedAsset", faCtx);
            if (ServiceUtil.isError(faResult)) {
                return faResult;
            }
            String fixedAssetId = (String) faResult.get("fixedAssetId");
            String costGl = requireGl(delegator, partyId, "GL_MAP_COST",
                    fixedAssetTypeId);
            String cipGl = requireGl(delegator, partyId, "GL_MAP_CIP", null);
            String acctgTransId = postJournal(dispatcher, userLogin,
                    "AE_CIP_CAPITALIZE", partyId, fixedAssetId, capDate,
                    "CIP capitalization, project " + projectId,
                    currencyUomId,
                    UtilMisc.toList(entry("D", costGl, total),
                            entry("C", cipGl, total)),
                    yes(context.get("postNow")));
            project.set("statusId", "AE_CIP_CAPIT");
            project.set("fixedAssetId", fixedAssetId);
            project.set("capitalizedDate", capDate);
            project.set("acctgTransId", acctgTransId);
            project.store();
            event(dispatcher, userLogin, fixedAssetId, partyId,
                    "ASSET_CAPITALIZED", "AEVT_CIP_CAP", capDate,
                    "Capitalized from CIP " + projectId,
                    "asseterpCapitalizeCipProject", acctgTransId, total,
                    currencyUomId);
            Map<String, Object> result = ServiceUtil.returnSuccess();
            result.put("fixedAssetId", fixedAssetId);
            result.put("acctgTransId", acctgTransId);
            return result;
        } catch (GlException | GenericEntityException
                | GenericServiceException e) {
            return ServiceUtil.returnError(e.getMessage());
        }
    }

    /* ==================== government grants ==================== */

    public static Map<String, Object> asseterpAmortizeGovGrant(
            DispatchContext dctx, Map<String, ? extends Object> context) {
        Delegator delegator = dctx.getDelegator();
        LocalDispatcher dispatcher = dctx.getDispatcher();
        GenericValue userLogin = (GenericValue) context.get("userLogin");
        try {
            GenericValue grant = delegator.findOne("AsseterpGovGrant",
                    UtilMisc.toMap("asseterpGovGrantId",
                            context.get("asseterpGovGrantId")), false);
            if (grant == null) {
                return ServiceUtil.returnError("Grant not found");
            }
            BigDecimal deferred = grant.get("deferredBalance") != null
                    ? grant.getBigDecimal("deferredBalance")
                    : nz(grant.getBigDecimal("grantAmount"));
            if (deferred.signum() <= 0) {
                return ServiceUtil.returnError("Grant fully amortized");
            }
            long months = grant.get("amortizationPeriodMonths") != null
                    ? grant.getLong("amortizationPeriodMonths") : 12L;
            BigDecimal monthly = nz(grant.getBigDecimal("grantAmount"))
                    .divide(BigDecimal.valueOf(Math.max(1, months)),
                            SCALE, RM);
            BigDecimal amount = monthly.min(deferred);
            GenericValue fixedAsset = delegator.findOne("FixedAsset",
                    UtilMisc.toMap("fixedAssetId",
                            grant.get("fixedAssetId")), false);
            // GL org from the asset's org config (single-org registers)
            GenericValue cfg = EntityQuery.use(delegator)
                    .from("AsseterpOrgConfig").queryFirst();
            String partyId = cfg != null ? cfg.getString("partyId") : null;
            String currencyUomId = orgCurrency(delegator, partyId,
                    grant.get("currencyUomId"));
            String defGl = requireGl(delegator, partyId, "GL_MAP_GRANT_DEF",
                    null);
            String incGl = requireGl(delegator, partyId, "GL_MAP_GRANT_INC",
                    null);
            Timestamp periodDate = tsOrNow(context.get("periodDate"));
            String acctgTransId = postJournal(dispatcher, userLogin,
                    "AE_GRANT_AMORT", partyId,
                    grant.getString("fixedAssetId"), periodDate,
                    "Grant amortization "
                            + grant.get("asseterpGovGrantId"),
                    currencyUomId,
                    UtilMisc.toList(entry("D", defGl, amount),
                            entry("C", incGl, amount)),
                    yes(context.get("postNow")));
            String amortId = delegator.getNextSeqId("AsseterpGovGrantAmort");
            GenericValue amort = delegator.makeValue("AsseterpGovGrantAmort");
            amort.set("asseterpGovGrantAmortId", amortId);
            amort.set("asseterpGovGrantId",
                    grant.get("asseterpGovGrantId"));
            amort.set("periodDate", periodDate);
            amort.set("amortAmount", amount);
            amort.set("postedDate", UtilDateTime.nowTimestamp());
            amort.set("acctgTransId", acctgTransId);
            amort.create();
            BigDecimal remaining = deferred.subtract(amount);
            grant.set("deferredBalance", remaining);
            if (remaining.signum() <= 0) {
                grant.set("statusId", "AE_GG_COMPLETE");
            }
            grant.store();
            event(dispatcher, userLogin, grant.getString("fixedAssetId"),
                    partyId, null, "AEVT_GRANT_AMORT", periodDate,
                    "Grant amortized " + amount, "asseterpAmortizeGovGrant",
                    acctgTransId, amount, currencyUomId);
            Map<String, Object> result = ServiceUtil.returnSuccess();
            result.put("asseterpGovGrantAmortId", amortId);
            result.put("amortAmount", amount);
            result.put("acctgTransId", acctgTransId);
            return result;
        } catch (GlException | GenericEntityException
                | GenericServiceException e) {
            return ServiceUtil.returnError(e.getMessage());
        }
    }

    /* ==================== IAS 36 impairment ==================== */

    public static Map<String, Object> asseterpRecordImpairment(
            DispatchContext dctx, Map<String, ? extends Object> context) {
        Delegator delegator = dctx.getDelegator();
        LocalDispatcher dispatcher = dctx.getDispatcher();
        GenericValue userLogin = (GenericValue) context.get("userLogin");
        String fixedAssetId = (String) context.get("fixedAssetId");
        String partyId = (String) context.get("partyId");
        BigDecimal recoverable = (BigDecimal) context.get("recoverableAmount");
        try {
            GenericValue fixedAsset = delegator.findOne("FixedAsset",
                    UtilMisc.toMap("fixedAssetId", fixedAssetId), false);
            if (fixedAsset == null) {
                return ServiceUtil.returnError(
                        "Fixed asset not found: " + fixedAssetId);
            }
            BigDecimal cost = nz(fixedAsset.getBigDecimal("purchaseCost"));
            BigDecimal accDep = nz(fixedAsset.getBigDecimal("depreciation"));
            BigDecimal carrying = cost.subtract(accDep);
            BigDecimal loss = carrying.subtract(nz(recoverable));
            if (loss.signum() <= 0) {
                return ServiceUtil.returnError("No impairment: recoverable "
                        + recoverable + " >= carrying " + carrying);
            }
            String currencyUomId = orgCurrency(delegator, partyId,
                    fixedAsset.get("purchaseCostUomId"));
            String assetTypeId = fixedAsset.getString("fixedAssetTypeId");
            String lossGl = requireGl(delegator, partyId, "GL_MAP_IMPAIR",
                    assetTypeId);
            String accDepGl = requireGl(delegator, partyId,
                    "GL_MAP_ACCUM_DEP", assetTypeId);
            Timestamp when = tsOrNow(context.get("assessmentDate"));
            String acctgTransId = postJournal(dispatcher, userLogin,
                    "AE_IMPAIRMENT", partyId, fixedAssetId, when,
                    "Impairment loss (IAS 36)", currencyUomId,
                    UtilMisc.toList(entry("D", lossGl, loss),
                            entry("C", accDepGl, loss)),
                    yes(context.get("postNow")));
            String impId = delegator.getNextSeqId("AsseterpImpairment");
            GenericValue imp = delegator.makeValue("AsseterpImpairment");
            imp.set("asseterpImpairmentId", impId);
            imp.set("fixedAssetId", fixedAssetId);
            imp.set("asseterpCguId", context.get("asseterpCguId"));
            imp.set("assessmentDate", when);
            imp.set("carryingAmount", carrying.setScale(SCALE, RM));
            imp.set("recoverableAmount", recoverable.setScale(SCALE, RM));
            imp.set("impairmentLoss", loss.setScale(SCALE, RM));
            imp.set("currencyUomId", currencyUomId);
            imp.set("statusId", "AE_IMP_ASSESSED");
            imp.set("acctgTransId", acctgTransId);
            imp.create();
            // register NBV stays consistent with the disposal math
            fixedAsset.set("depreciation", accDep.add(loss));
            fixedAsset.store();
            event(dispatcher, userLogin, fixedAssetId, partyId, null,
                    "AEVT_IMPAIRED", when, "Impairment loss " + loss,
                    "asseterpRecordImpairment", acctgTransId, loss,
                    currencyUomId);
            Map<String, Object> result = ServiceUtil.returnSuccess();
            result.put("asseterpImpairmentId", impId);
            result.put("impairmentLoss", loss);
            result.put("acctgTransId", acctgTransId);
            return result;
        } catch (GlException | GenericEntityException
                | GenericServiceException e) {
            return ServiceUtil.returnError(e.getMessage());
        }
    }

    /* ==================== IAS 37 ARO ==================== */

    public static Map<String, Object> asseterpCreateAro(DispatchContext dctx,
            Map<String, ? extends Object> context) {
        Delegator delegator = dctx.getDelegator();
        LocalDispatcher dispatcher = dctx.getDispatcher();
        GenericValue userLogin = (GenericValue) context.get("userLogin");
        String fixedAssetId = (String) context.get("fixedAssetId");
        String partyId = (String) context.get("partyId");
        BigDecimal estimatedCost = (BigDecimal) context.get("estimatedCost");
        BigDecimal annualRate = (BigDecimal) context.get("annualDiscountRate");
        Timestamp recognition = tsOrNow(context.get("recognitionDate"));
        Timestamp settle = (Timestamp) context.get("expectedSettleDate");
        try {
            double years = Math.max(0.0,
                    (settle.getTime() - recognition.getTime())
                            / (365.25 * 24 * 3600 * 1000.0));
            double factor = Math.pow(1.0 + annualRate.doubleValue(), -years);
            BigDecimal presentValue = estimatedCost
                    .multiply(BigDecimal.valueOf(factor))
                    .setScale(SCALE, RM);
            String currencyUomId = orgCurrency(delegator, partyId,
                    context.get("currencyUomId"));
            GenericValue fixedAsset = delegator.findOne("FixedAsset",
                    UtilMisc.toMap("fixedAssetId", fixedAssetId), false);
            String assetTypeId = fixedAsset != null
                    ? fixedAsset.getString("fixedAssetTypeId") : null;
            String costGl = requireGl(delegator, partyId, "GL_MAP_COST",
                    assetTypeId);
            String aroGl = requireGl(delegator, partyId, "GL_MAP_ARO_LIAB",
                    assetTypeId);
            String acctgTransId = postJournal(dispatcher, userLogin,
                    "AE_ARO_ACCRETION", partyId, fixedAssetId, recognition,
                    "ARO initial recognition (IAS 37)", currencyUomId,
                    UtilMisc.toList(entry("D", costGl, presentValue),
                            entry("C", aroGl, presentValue)),
                    yes(context.get("postNow")));
            String aroId = delegator.getNextSeqId("AsseterpAro");
            GenericValue aro = delegator.makeValue("AsseterpAro");
            aro.set("asseterpAroId", aroId);
            aro.set("fixedAssetId", fixedAssetId);
            aro.set("recognitionDate", recognition);
            aro.set("estimatedCost", estimatedCost.setScale(SCALE, RM));
            aro.set("discountRate", annualRate.doubleValue());
            aro.set("presentValue", presentValue);
            aro.set("currencyUomId", currencyUomId);
            aro.set("expectedSettleDate", settle);
            aro.set("statusId", "AE_ARO_ACTIVE");
            aro.set("acctgTransId", acctgTransId);
            aro.create();
            event(dispatcher, userLogin, fixedAssetId, partyId, null,
                    "AEVT_ARO_UNWIND", recognition,
                    "ARO recognized at PV " + presentValue,
                    "asseterpCreateAro", acctgTransId, presentValue,
                    currencyUomId);
            Map<String, Object> result = ServiceUtil.returnSuccess();
            result.put("asseterpAroId", aroId);
            result.put("presentValue", presentValue);
            result.put("acctgTransId", acctgTransId);
            return result;
        } catch (GlException | GenericEntityException
                | GenericServiceException e) {
            return ServiceUtil.returnError(e.getMessage());
        }
    }

    public static Map<String, Object> asseterpUnwindAro(DispatchContext dctx,
            Map<String, ? extends Object> context) {
        Delegator delegator = dctx.getDelegator();
        LocalDispatcher dispatcher = dctx.getDispatcher();
        GenericValue userLogin = (GenericValue) context.get("userLogin");
        try {
            GenericValue aro = delegator.findOne("AsseterpAro",
                    UtilMisc.toMap("asseterpAroId",
                            context.get("asseterpAroId")), false);
            if (aro == null) {
                return ServiceUtil.returnError("ARO not found");
            }
            if (!"AE_ARO_ACTIVE".equals(aro.getString("statusId"))) {
                return ServiceUtil.returnError("ARO is not active");
            }
            GenericValue last = EntityQuery.use(delegator)
                    .from("AsseterpAroSchedule")
                    .where("asseterpAroId", aro.get("asseterpAroId"))
                    .orderBy("-periodDate").queryFirst();
            BigDecimal opening = last != null
                    ? nz(last.getBigDecimal("closingBalance"))
                    : nz(aro.getBigDecimal("presentValue"));
            BigDecimal monthlyRate = BigDecimal
                    .valueOf(aro.getDouble("discountRate"))
                    .divide(BigDecimal.valueOf(12), 10, RM);
            BigDecimal interest = opening.multiply(monthlyRate)
                    .setScale(SCALE, RM);
            BigDecimal closing = opening.add(interest);
            GenericValue cfg = EntityQuery.use(delegator)
                    .from("AsseterpOrgConfig").queryFirst();
            String partyId = cfg != null ? cfg.getString("partyId") : null;
            String currencyUomId = aro.getString("currencyUomId");
            String intGl = requireGl(delegator, partyId, "GL_MAP_INT_EXP",
                    null);
            String aroGl = requireGl(delegator, partyId, "GL_MAP_ARO_LIAB",
                    null);
            Timestamp periodDate = tsOrNow(context.get("periodDate"));
            String acctgTransId = postJournal(dispatcher, userLogin,
                    "AE_ARO_ACCRETION", partyId,
                    aro.getString("fixedAssetId"), periodDate,
                    "ARO accretion " + aro.get("asseterpAroId"),
                    currencyUomId,
                    UtilMisc.toList(entry("D", intGl, interest),
                            entry("C", aroGl, interest)),
                    yes(context.get("postNow")));
            String schedId = delegator.getNextSeqId("AsseterpAroSchedule");
            GenericValue row = delegator.makeValue("AsseterpAroSchedule");
            row.set("asseterpAroScheduleId", schedId);
            row.set("asseterpAroId", aro.get("asseterpAroId"));
            row.set("periodDate", periodDate);
            row.set("openingBalance", opening);
            row.set("interestUnwound", interest);
            row.set("closingBalance", closing);
            row.set("acctgTransId", acctgTransId);
            row.create();
            event(dispatcher, userLogin, aro.getString("fixedAssetId"),
                    partyId, null, "AEVT_ARO_UNWIND", periodDate,
                    "ARO accretion " + interest, "asseterpUnwindAro",
                    acctgTransId, interest, currencyUomId);
            Map<String, Object> result = ServiceUtil.returnSuccess();
            result.put("asseterpAroScheduleId", schedId);
            result.put("interestUnwound", interest);
            result.put("acctgTransId", acctgTransId);
            return result;
        } catch (GlException | GenericEntityException
                | GenericServiceException e) {
            return ServiceUtil.returnError(e.getMessage());
        }
    }

    /* ==================== IFRS 5 held-for-sale ==================== */

    public static Map<String, Object> asseterpReclassifyHeldForSale(
            DispatchContext dctx, Map<String, ? extends Object> context) {
        Delegator delegator = dctx.getDelegator();
        LocalDispatcher dispatcher = dctx.getDispatcher();
        GenericValue userLogin = (GenericValue) context.get("userLogin");
        String fixedAssetId = (String) context.get("fixedAssetId");
        String partyId = (String) context.get("partyId");
        BigDecimal fairValue =
                (BigDecimal) context.get("fairValueLessCostsToSell");
        try {
            GenericValue fixedAsset = delegator.findOne("FixedAsset",
                    UtilMisc.toMap("fixedAssetId", fixedAssetId), false);
            if (fixedAsset == null) {
                return ServiceUtil.returnError(
                        "Fixed asset not found: " + fixedAssetId);
            }
            BigDecimal cost = nz(fixedAsset.getBigDecimal("purchaseCost"));
            BigDecimal accDep = nz(fixedAsset.getBigDecimal("depreciation"));
            BigDecimal carrying = cost.subtract(accDep);
            BigDecimal writedown = carrying.subtract(nz(fairValue)).max(
                    BigDecimal.ZERO);
            BigDecimal hfsValue = carrying.subtract(writedown);
            String currencyUomId = orgCurrency(delegator, partyId,
                    fixedAsset.get("purchaseCostUomId"));
            String assetTypeId = fixedAsset.getString("fixedAssetTypeId");
            String costGl = requireGl(delegator, partyId, "GL_MAP_COST",
                    assetTypeId);
            String accDepGl = requireGl(delegator, partyId,
                    "GL_MAP_ACCUM_DEP", assetTypeId);
            String hfsGl = requireGl(delegator, partyId, "GL_MAP_HFS_ASSET",
                    assetTypeId);
            Timestamp when = tsOrNow(context.get("reclassificationDate"));
            List<Map<String, Object>> entries = UtilMisc.toList(
                    entry("D", hfsGl, hfsValue),
                    entry("D", accDepGl, accDep),
                    entry("C", costGl, cost));
            if (writedown.signum() > 0) {
                String lossGl = requireGl(delegator, partyId,
                        "GL_MAP_IMPAIR", assetTypeId);
                entries.add(entry("D", lossGl, writedown));
            }
            String acctgTransId = postJournal(dispatcher, userLogin,
                    "AE_HFS_RECLASS", partyId, fixedAssetId, when,
                    "Held-for-sale reclassification (IFRS 5)",
                    currencyUomId, entries, yes(context.get("postNow")));
            String hfsId = delegator.getNextSeqId("AsseterpHeldForSale");
            GenericValue hfs = delegator.makeValue("AsseterpHeldForSale");
            hfs.set("asseterpHeldForSaleId", hfsId);
            hfs.set("fixedAssetId", fixedAssetId);
            hfs.set("reclassificationDate", when);
            hfs.set("carryingAmountAtReclassification",
                    carrying.setScale(SCALE, RM));
            hfs.set("fairValueLessCostsToSell",
                    nz(fairValue).setScale(SCALE, RM));
            hfs.set("writtenDownAmount", writedown.setScale(SCALE, RM));
            hfs.set("currencyUomId", currencyUomId);
            hfs.set("expectedSaleDate", context.get("expectedSaleDate"));
            hfs.set("statusId", "AE_HFS_ACTIVE");
            hfs.set("acctgTransId", acctgTransId);
            hfs.create();
            event(dispatcher, userLogin, fixedAssetId, partyId,
                    "ASSET_HELD_SALE", "AEVT_HELD_SALE", when,
                    "Reclassified to held-for-sale at " + hfsValue,
                    "asseterpReclassifyHeldForSale", acctgTransId, hfsValue,
                    currencyUomId);
            Map<String, Object> result = ServiceUtil.returnSuccess();
            result.put("asseterpHeldForSaleId", hfsId);
            result.put("writtenDownAmount", writedown);
            result.put("acctgTransId", acctgTransId);
            return result;
        } catch (GlException | GenericEntityException
                | GenericServiceException e) {
            return ServiceUtil.returnError(e.getMessage());
        }
    }

    /* ==================== intercompany transfer ==================== */

    public static Map<String, Object> asseterpIntercompanyTransferAsset(
            DispatchContext dctx, Map<String, ? extends Object> context) {
        Delegator delegator = dctx.getDelegator();
        LocalDispatcher dispatcher = dctx.getDispatcher();
        GenericValue userLogin = (GenericValue) context.get("userLogin");
        String fixedAssetId = (String) context.get("fixedAssetId");
        String fromPartyId = (String) context.get("fromPartyId");
        String toPartyId = (String) context.get("toPartyId");
        Timestamp when = tsOrNow(context.get("transferDate"));
        try {
            GenericValue fixedAsset = delegator.findOne("FixedAsset",
                    UtilMisc.toMap("fixedAssetId", fixedAssetId), false);
            if (fixedAsset == null) {
                return ServiceUtil.returnError(
                        "Fixed asset not found: " + fixedAssetId);
            }
            BigDecimal cost = nz(fixedAsset.getBigDecimal("purchaseCost"));
            BigDecimal accDep = nz(fixedAsset.getBigDecimal("depreciation"));
            BigDecimal nbv = cost.subtract(accDep);
            String currencyUomId = orgCurrency(delegator, fromPartyId,
                    fixedAsset.get("purchaseCostUomId"));
            String assetTypeId = fixedAsset.getString("fixedAssetTypeId");
            // from-org: derecognize at NBV against IC receivable
            String fromAcctgTransId = postJournal(dispatcher, userLogin,
                    "AE_IC_TRANSFER", fromPartyId, fixedAssetId, when,
                    "IC transfer out to " + toPartyId, currencyUomId,
                    UtilMisc.toList(
                            entry("D", requireGl(delegator, fromPartyId,
                                    "GL_MAP_IC_RECV", assetTypeId), nbv),
                            entry("D", requireGl(delegator, fromPartyId,
                                    "GL_MAP_ACCUM_DEP", assetTypeId), accDep),
                            entry("C", requireGl(delegator, fromPartyId,
                                    "GL_MAP_COST", assetTypeId), cost)),
                    yes(context.get("postNow")));
            // to-org: recognize at NBV against IC payable
            String toAcctgTransId = postJournal(dispatcher, userLogin,
                    "AE_IC_TRANSFER", toPartyId, fixedAssetId, when,
                    "IC transfer in from " + fromPartyId, currencyUomId,
                    UtilMisc.toList(
                            entry("D", requireGl(delegator, toPartyId,
                                    "GL_MAP_COST", assetTypeId), nbv),
                            entry("C", requireGl(delegator, toPartyId,
                                    "GL_MAP_IC_PAY", assetTypeId), nbv)),
                    yes(context.get("postNow")));
            // receiving org carries the asset at NBV as its new basis
            fixedAsset.set("purchaseCost", nbv);
            fixedAsset.set("depreciation", BigDecimal.ZERO);
            fixedAsset.store();
            // custody moves to the receiving organization (standard svc)
            Map<String, Object> assignment = dctx.makeValidContext(
                    "createPartyFixedAssetAssignment", "IN",
                    UtilMisc.toMap("partyId", toPartyId,
                            "fixedAssetId", fixedAssetId,
                            "roleTypeId", "ASSET_CUSTODIAN",
                            "fromDate", when,
                            "statusId", "PAS_ASSIGNED_ACTIVE",
                            "userLogin", userLogin));
            Map<String, Object> assignResult = dispatcher.runSync(
                    "createPartyFixedAssetAssignment", assignment);
            if (ServiceUtil.isError(assignResult)) {
                return assignResult;
            }
            String xferId = delegator
                    .getNextSeqId("AsseterpIntercompanyTransfer");
            GenericValue xfer = delegator
                    .makeValue("AsseterpIntercompanyTransfer");
            xfer.set("asseterpIntercompanyTransferId", xferId);
            xfer.set("fixedAssetId", fixedAssetId);
            xfer.set("fromPartyId", fromPartyId);
            xfer.set("toPartyId", toPartyId);
            xfer.set("transferDate", when);
            xfer.set("transferValue", nbv.setScale(SCALE, RM));
            xfer.set("currencyUomId", currencyUomId);
            xfer.set("fromAcctgTransId", fromAcctgTransId);
            xfer.set("toAcctgTransId", toAcctgTransId);
            xfer.set("statusId", "AE_ICX_POSTED");
            if (userLogin != null) {
                xfer.set("authorizedByPartyId",
                        userLogin.getString("partyId"));
            }
            xfer.set("notes", context.get("notes"));
            xfer.create();
            event(dispatcher, userLogin, fixedAssetId, toPartyId,
                    "ASSET_TRANSFERRED", "AEVT_IC_XFER", when,
                    "IC transfer " + fromPartyId + " -> " + toPartyId
                            + " at NBV " + nbv,
                    "asseterpIntercompanyTransferAsset", xferId, nbv,
                    currencyUomId);
            Map<String, Object> result = ServiceUtil.returnSuccess();
            result.put("asseterpIntercompanyTransferId", xferId);
            result.put("transferValue", nbv);
            result.put("fromAcctgTransId", fromAcctgTransId);
            result.put("toAcctgTransId", toAcctgTransId);
            return result;
        } catch (GlException | GenericEntityException
                | GenericServiceException e) {
            return ServiceUtil.returnError(e.getMessage());
        }
    }

    /* ==================== insurance claims ==================== */

    public static Map<String, Object> asseterpSettleInsuranceClaim(
            DispatchContext dctx, Map<String, ? extends Object> context) {
        Delegator delegator = dctx.getDelegator();
        LocalDispatcher dispatcher = dctx.getDispatcher();
        GenericValue userLogin = (GenericValue) context.get("userLogin");
        try {
            GenericValue claim = delegator.findOne("AsseterpInsuranceClaim",
                    UtilMisc.toMap("asseterpInsuranceClaimId",
                            context.get("asseterpInsuranceClaimId")), false);
            if (claim == null) {
                return ServiceUtil.returnError("Claim not found");
            }
            if (!"AE_IC_FILED".equals(claim.getString("statusId"))) {
                return ServiceUtil.returnError("Claim is not in Filed state");
            }
            boolean rejected = "Y".equals(context.get("rejected"));
            Timestamp when = tsOrNow(context.get("settlementDate"));
            if (rejected) {
                claim.set("statusId", "AE_IC_REJECTED");
            } else {
                claim.set("statusId", "AE_IC_SETTLED");
                claim.set("settledAmount", context.get("settledAmount"));
                claim.set("settlementDate", when);
            }
            claim.store();
            GenericValue policy = delegator.findOne("AsseterpInsurance",
                    UtilMisc.toMap("asseterpInsuranceId",
                            claim.get("asseterpInsuranceId")), false);
            event(dispatcher, userLogin,
                    policy != null ? policy.getString("fixedAssetId") : null,
                    null, null, "AEVT_INS_CLAIM", when,
                    rejected ? "Insurance claim rejected"
                            : "Insurance claim settled",
                    "asseterpSettleInsuranceClaim",
                    claim.getString("asseterpInsuranceClaimId"),
                    (BigDecimal) context.get("settledAmount"),
                    policy != null ? policy.getString("currencyUomId") : null);
            return ServiceUtil.returnSuccess();
        } catch (GenericEntityException | GenericServiceException e) {
            return ServiceUtil.returnError(e.getMessage());
        }
    }

    /* ==================== software license seats ==================== */

    public static Map<String, Object> asseterpAssignLicenseSeat(
            DispatchContext dctx, Map<String, ? extends Object> context) {
        Delegator delegator = dctx.getDelegator();
        String licenseId = (String) context.get("asseterpSoftwareLicenseId");
        try {
            GenericValue license = delegator.findOne("AsseterpSoftwareLicense",
                    UtilMisc.toMap("asseterpSoftwareLicenseId", licenseId),
                    false);
            if (license == null) {
                return ServiceUtil.returnError("License not found");
            }
            Long totalSeats = license.getLong("totalSeats");
            if (totalSeats != null) {
                long used = EntityQuery.use(delegator)
                        .from("AsseterpLicenseSeat")
                        .where("asseterpSoftwareLicenseId", licenseId)
                        .queryList().stream()
                        .filter(s -> s.get("releaseDate") == null).count();
                if (used >= totalSeats) {
                    return ServiceUtil.returnError("All " + totalSeats
                            + " seats of license " + licenseId
                            + " are assigned");
                }
            }
            String seatId = delegator.getNextSeqId("AsseterpLicenseSeat");
            GenericValue seat = delegator.makeValue("AsseterpLicenseSeat");
            seat.set("asseterpLicenseSeatId", seatId);
            seat.set("asseterpSoftwareLicenseId", licenseId);
            seat.set("assignedToPartyId", context.get("assignedToPartyId"));
            seat.set("deviceFixedAssetId", context.get("deviceFixedAssetId"));
            seat.set("assignmentDate", UtilDateTime.nowTimestamp());
            seat.create();
            Map<String, Object> result = ServiceUtil.returnSuccess();
            result.put("asseterpLicenseSeatId", seatId);
            return result;
        } catch (GenericEntityException e) {
            return ServiceUtil.returnError(e.getMessage());
        }
    }

    public static Map<String, Object> asseterpReleaseLicenseSeat(
            DispatchContext dctx, Map<String, ? extends Object> context) {
        Delegator delegator = dctx.getDelegator();
        try {
            GenericValue seat = delegator.findOne("AsseterpLicenseSeat",
                    UtilMisc.toMap("asseterpLicenseSeatId",
                            context.get("asseterpLicenseSeatId")), false);
            if (seat == null) {
                return ServiceUtil.returnError("Seat not found");
            }
            seat.set("releaseDate", UtilDateTime.nowTimestamp());
            seat.store();
            return ServiceUtil.returnSuccess();
        } catch (GenericEntityException e) {
            return ServiceUtil.returnError(e.getMessage());
        }
    }
}
