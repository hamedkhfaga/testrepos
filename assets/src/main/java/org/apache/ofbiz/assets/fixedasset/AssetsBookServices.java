package org.apache.ofbiz.assets.fixedasset;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.sql.Date;
import java.sql.Timestamp;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import org.apache.ofbiz.base.util.Debug;
import org.apache.ofbiz.base.util.UtilDateTime;
import org.apache.ofbiz.base.util.UtilMisc;
import org.apache.ofbiz.base.util.UtilProperties;
import org.apache.ofbiz.base.util.UtilValidate;
import org.apache.ofbiz.entity.Delegator;
import org.apache.ofbiz.entity.GenericEntityException;
import org.apache.ofbiz.entity.GenericValue;
import org.apache.ofbiz.entity.util.EntityQuery;
import org.apache.ofbiz.service.DispatchContext;
import org.apache.ofbiz.service.ServiceUtil;

/** Effective-dated asset book assignment services. Core FixedAsset remains authoritative. */
public final class AssetsBookServices {
    private static final String MODULE = AssetsBookServices.class.getName();
    private static final String RESOURCE = "AssetsUiLabels";
    private static final String STATUS_ACTIVE = "ASSETS_ACTIVE";
    private static final String STATUS_IN_SERVICE = "ASSETS_IN_SERVICE";
    private static final String STATUS_PENDING = "ASSETS_PENDING";
    private static final String STATUS_APPROVED = "ASSETS_APPROVED";
    private static final String STATUS_INACTIVE = "ASSETS_INACTIVE";
    private static final String STATUS_PLANNED = "ASSETS_PLANNED";
    private static final String STATUS_CANCELLED = "ASSETS_CANCELLED";
    private static final String STATUS_POSTED = "ASSETS_POSTED";
    private static final int MONEY_SCALE = 2;
    private static final RoundingMode ROUNDING = RoundingMode.HALF_UP;

    private AssetsBookServices() { }

    public static Map<String, Object> assetsAssignFixedAssetToBook(DispatchContext dctx,
            Map<String, ? extends Object> context) {
        Delegator delegator = dctx.getDelegator();
        Locale locale = locale(context);
        String fixedAssetId = stringValue(context.get("fixedAssetId"));
        String assetsBookId = stringValue(context.get("assetsBookId"));
        boolean dryRun = !"N".equals(context.get("dryRun"));
        try {
            GenericValue userLogin = (GenericValue) context.get("userLogin");
            GenericValue book = require(delegator, "AssetsBook", UtilMisc.toMap("assetsBookId", assetsBookId),
                    "Asset book " + assetsBookId + " was not found.");
            GenericValue asset = require(delegator, "FixedAsset", UtilMisc.toMap("fixedAssetId", fixedAssetId),
                    "Fixed asset " + fixedAssetId + " was not found in OFBiz.");
            if (!Arrays.asList(STATUS_ACTIVE, STATUS_PENDING, STATUS_APPROVED).contains(book.getString("statusId"))) {
                throw new AssetsBookException("Asset book " + assetsBookId
                        + " is not active or staged for activation. Use an active, pending, or approved book.");
            }

            String bookCurrency = book.getString("currencyUomId");
            String assetCurrency = asset.getString("purchaseCostUomId");
            if (UtilValidate.isNotEmpty(assetCurrency) && UtilValidate.isNotEmpty(bookCurrency)
                    && !bookCurrency.equals(assetCurrency)) {
                throw new AssetsBookException("Asset " + fixedAssetId + " uses " + assetCurrency + " while book "
                        + assetsBookId + " uses " + bookCurrency + ". Configure an approved currency conversion before assignment.");
            }

            Timestamp fromDate = timestamp(context.get("fromDate"), "From date is required.");
            Timestamp thruDate = (Timestamp) context.get("thruDate");
            if (thruDate != null && !thruDate.after(fromDate)) {
                throw new AssetsBookException("To date must be later than the assignment from date.");
            }
            Timestamp bookFromDate = book.getTimestamp("fromDate");
            Timestamp bookThruDate = book.getTimestamp("thruDate");
            if (bookFromDate != null && fromDate.before(bookFromDate)) {
                throw new AssetsBookException("Assignment from date cannot be before asset book " + assetsBookId + " begins.");
            }
            if (bookThruDate != null && (!fromDate.before(bookThruDate)
                    || (thruDate != null && thruDate.after(bookThruDate)))) {
                throw new AssetsBookException("Assignment dates must remain inside the effective dates of asset book "
                        + assetsBookId + ".");
            }
            validateNoOverlap(delegator, fixedAssetId, assetsBookId, fromDate, thruDate);

            BigDecimal originalCost = money(context.get("originalCost"), asset.getBigDecimal("purchaseCost"), "Original cost");
            BigDecimal capitalizedCost = money(context.get("capitalizedCost"), originalCost, "Capitalized cost");
            BigDecimal salvageValue = money(context.get("salvageValue"), asset.getBigDecimal("salvageValue"), "Salvage value");
            if (originalCost.signum() < 0 || capitalizedCost.signum() < 0 || salvageValue.signum() < 0) {
                throw new AssetsBookException("Original cost, capitalized cost, and salvage value cannot be negative.");
            }
            if (salvageValue.compareTo(capitalizedCost) > 0) {
                throw new AssetsBookException("Salvage value cannot exceed capitalized cost.");
            }

            BigDecimal accumulatedDepreciation = money(context.get("accumulatedDepreciation"), BigDecimal.ZERO,
                    "Accumulated depreciation");
            BigDecimal impairmentAmount = money(context.get("impairmentAmount"), BigDecimal.ZERO, "Impairment amount");
            BigDecimal revaluationAmount = money(context.get("revaluationAmount"), BigDecimal.ZERO, "Revaluation amount");
            if (accumulatedDepreciation.signum() < 0 || impairmentAmount.signum() < 0) {
                throw new AssetsBookException("Accumulated depreciation and impairment amount cannot be negative.");
            }
            BigDecimal calculatedNetBookValue = capitalizedCost.subtract(accumulatedDepreciation)
                    .subtract(impairmentAmount).add(revaluationAmount).setScale(MONEY_SCALE, ROUNDING);
            if (calculatedNetBookValue.signum() < 0) {
                throw new AssetsBookException("The opening balance produces a negative net book value. Review capitalized cost, "
                        + "accumulated depreciation, impairment, and revaluation amounts.");
            }
            if (context.get("netBookValue") != null) {
                BigDecimal suppliedNetBookValue = money(context.get("netBookValue"), null, "Net book value");
                if (suppliedNetBookValue.compareTo(calculatedNetBookValue) != 0) {
                    throw new AssetsBookException("Net book value must equal capitalized cost minus accumulated depreciation "
                            + "and impairment plus revaluation. Expected " + calculatedNetBookValue + ".");
                }
            }

            Long usefulLifeMonths = longValue(context.get("usefulLifeMonths"));
            if (usefulLifeMonths == null || usefulLifeMonths <= 0) {
                throw new AssetsBookException("Useful life months must be greater than zero.");
            }
            Long remainingLifeMonths = longValue(context.get("remainingLifeMonths"));
            if (remainingLifeMonths == null) remainingLifeMonths = usefulLifeMonths;
            if (remainingLifeMonths < 0 || remainingLifeMonths > usefulLifeMonths) {
                throw new AssetsBookException("Remaining life months must be between zero and useful life months.");
            }
            String customMethodId = stringValue(context.get("depreciationCustomMethodId"));
            GenericValue method = require(delegator, "CustomMethod", UtilMisc.toMap("customMethodId", customMethodId),
                    "Depreciation method " + customMethodId + " was not found.");
            if (!"DEPRECIATION_FORMULA".equals(method.getString("customMethodTypeId"))) {
                throw new AssetsBookException("Custom method " + customMethodId + " is not a DEPRECIATION_FORMULA method.");
            }
            validateEnumeration(delegator, stringValue(context.get("depConventionEnumId")), "ASSETS_CONV_TYPE", "depreciation convention");
            validateEnumeration(delegator, stringValue(context.get("prorationMethodEnumId")), "ASSETS_PRORATE_TYP", "proration method");

            Timestamp now = UtilDateTime.nowTimestamp();
            Map<String, Object> fields = new HashMap<>();
            fields.put("fixedAssetId", fixedAssetId);
            fields.put("assetsBookId", assetsBookId);
            fields.put("fromDate", fromDate);
            fields.put("thruDate", thruDate);
            fields.put("statusId", stringOrDefault(context.get("statusId"), STATUS_IN_SERVICE));
            fields.put("placedInServiceDate", context.get("placedInServiceDate"));
            fields.put("capitalizationDate", context.get("capitalizationDate"));
            fields.put("originalCost", originalCost);
            fields.put("capitalizedCost", capitalizedCost);
            fields.put("salvageValue", salvageValue);
            fields.put("depreciableBasis", capitalizedCost.subtract(salvageValue).setScale(MONEY_SCALE, ROUNDING));
            fields.put("usefulLifeMonths", usefulLifeMonths);
            fields.put("remainingLifeMonths", remainingLifeMonths);
            fields.put("depreciationCustomMethodId", customMethodId);
            fields.put("depConventionEnumId", stringValue(context.get("depConventionEnumId")));
            fields.put("prorationMethodEnumId", stringValue(context.get("prorationMethodEnumId")));
            fields.put("productionCapacity", context.get("productionCapacity"));
            fields.put("productionCapacityUomId", stringValue(context.get("productionCapacityUomId")));
            fields.put("accumulatedDepreciation", accumulatedDepreciation);
            fields.put("impairmentAmount", impairmentAmount);
            fields.put("revaluationAmount", revaluationAmount);
            fields.put("netBookValue", calculatedNetBookValue);
            fields.put("lastDepreciationDate", context.get("lastDepreciationDate"));
            fields.put("nextDepreciationDate", context.get("nextDepreciationDate"));
            fields.put("comments", context.get("comments"));
            fields.put("createdDate", now);
            fields.put("createdByUserLogin", userLogin.getString("userLoginId"));
            fields.put("lastModifiedDate", now);
            fields.put("lastModifiedByUserLogin", userLogin.getString("userLoginId"));
            fields.values().removeIf(value -> value == null || "".equals(value));

            GenericValue detail = delegator.makeValue("AssetsBookDetail", fields);
            if (!dryRun) {
                detail.create();
            }
            Map<String, Object> result = ServiceUtil.returnSuccess(message(
                    dryRun ? "AssetsBookAssignDryRunSuccess" : "AssetsBookAssignSuccess", locale,
                    "fixedAssetId", fixedAssetId, "assetsBookId", assetsBookId));
            result.put("dryRun", dryRun ? "Y" : "N");
            result.put("bookDetail", detail);
            return result;
        } catch (AssetsBookException | GenericEntityException e) {
            Debug.logError(e, "Unable to assign fixed asset to asset book", MODULE);
            return ServiceUtil.returnError(e.getMessage());
        } catch (Exception e) {
            Debug.logError(e, "Unexpected asset book assignment failure", MODULE);
            return ServiceUtil.returnError("Unable to assign asset " + fixedAssetId + " to book " + assetsBookId
                    + ". Detail: " + e.getMessage());
        }
    }

    public static Map<String, Object> assetsRemoveFixedAssetFromBook(DispatchContext dctx,
            Map<String, ? extends Object> context) {
        Delegator delegator = dctx.getDelegator();
        Locale locale = locale(context);
        String fixedAssetId = stringValue(context.get("fixedAssetId"));
        String assetsBookId = stringValue(context.get("assetsBookId"));
        boolean dryRun = !"N".equals(context.get("dryRun"));
        try {
            Timestamp fromDate = timestamp(context.get("fromDate"), "Assignment from date is required.");
            Timestamp effectiveDate = timestamp(context.get("effectiveDate"), "Removal effective date is required.");
            GenericValue detail = require(delegator, "AssetsBookDetail", UtilMisc.toMap(
                    "fixedAssetId", fixedAssetId, "assetsBookId", assetsBookId, "fromDate", fromDate),
                    "The selected asset book assignment was not found. Refresh the profile and try again.");
            if (!effectiveDate.after(fromDate)) {
                throw new AssetsBookException("Removal effective date must be later than the assignment from date.");
            }
            if (detail.getTimestamp("thruDate") != null) {
                throw new AssetsBookException("This asset book assignment is already closed on " + detail.getTimestamp("thruDate") + ".");
            }

            List<GenericValue> schedules = EntityQuery.use(delegator).from("AssetsBookDepSchedule")
                    .where("fixedAssetId", fixedAssetId, "assetsBookId", assetsBookId).queryList();
            int cancelledCount = 0;
            Date effectiveDay = new Date(effectiveDate.getTime());
            for (GenericValue schedule : schedules) {
                Date periodStart = schedule.getDate("periodStartDate");
                if (STATUS_POSTED.equals(schedule.getString("statusId")) && periodStart != null && !periodStart.before(effectiveDay)) {
                    throw new AssetsBookException("Posted depreciation schedule " + schedule.getString("assetsBookDepScheduleId")
                            + " exists on or after the removal date. Reverse that accounting entry before removing the asset from the book.");
                }
                if (STATUS_PLANNED.equals(schedule.getString("statusId")) && periodStart != null && !periodStart.before(effectiveDay)) {
                    cancelledCount++;
                    if (!dryRun) {
                        schedule.set("statusId", STATUS_CANCELLED);
                        schedule.set("lastModifiedDate", UtilDateTime.nowTimestamp());
                        GenericValue userLogin = (GenericValue) context.get("userLogin");
                        schedule.set("lastModifiedByUserLogin", userLogin.getString("userLoginId"));
                        schedule.store();
                    }
                }
            }

            if (!dryRun) {
                GenericValue userLogin = (GenericValue) context.get("userLogin");
                detail.set("thruDate", effectiveDate);
                detail.set("statusId", STATUS_INACTIVE);
                detail.set("comments", appendComment(detail.getString("comments"), stringValue(context.get("comments"))));
                detail.set("lastModifiedDate", UtilDateTime.nowTimestamp());
                detail.set("lastModifiedByUserLogin", userLogin.getString("userLoginId"));
                detail.store();
            }
            Map<String, Object> result = ServiceUtil.returnSuccess(message(
                    dryRun ? "AssetsBookRemoveDryRunSuccess" : "AssetsBookRemoveSuccess", locale,
                    "fixedAssetId", fixedAssetId, "assetsBookId", assetsBookId));
            result.put("dryRun", dryRun ? "Y" : "N");
            result.put("cancelledScheduleCount", cancelledCount);
            return result;
        } catch (AssetsBookException | GenericEntityException e) {
            Debug.logError(e, "Unable to remove fixed asset from asset book", MODULE);
            return ServiceUtil.returnError(e.getMessage());
        } catch (Exception e) {
            Debug.logError(e, "Unexpected asset book removal failure", MODULE);
            return ServiceUtil.returnError("Unable to remove asset " + fixedAssetId + " from book " + assetsBookId
                    + ". Detail: " + e.getMessage());
        }
    }

    private static void validateNoOverlap(Delegator delegator, String fixedAssetId, String assetsBookId,
            Timestamp fromDate, Timestamp thruDate) throws GenericEntityException, AssetsBookException {
        List<GenericValue> rows = EntityQuery.use(delegator).from("AssetsBookDetail")
                .where("fixedAssetId", fixedAssetId, "assetsBookId", assetsBookId).queryList();
        for (GenericValue row : rows) {
            Timestamp existingFrom = row.getTimestamp("fromDate");
            Timestamp existingThru = row.getTimestamp("thruDate");
            boolean startsBeforeNewEnd = thruDate == null || existingFrom.before(thruDate);
            boolean endsAfterNewStart = existingThru == null || existingThru.after(fromDate);
            if (startsBeforeNewEnd && endsAfterNewStart) {
                throw new AssetsBookException("Asset " + fixedAssetId + " already has an overlapping assignment in book "
                        + assetsBookId + " beginning " + existingFrom + ". Close the current assignment first.");
            }
        }
    }

    private static void validateEnumeration(Delegator delegator, String enumId, String expectedType, String label)
            throws GenericEntityException, AssetsBookException {
        if (UtilValidate.isEmpty(enumId)) return;
        GenericValue value = require(delegator, "Enumeration", UtilMisc.toMap("enumId", enumId),
                "Selected " + label + " " + enumId + " was not found.");
        if (!expectedType.equals(value.getString("enumTypeId"))) {
            throw new AssetsBookException("Selected " + label + " " + enumId + " does not belong to " + expectedType + ".");
        }
    }

    private static GenericValue require(Delegator delegator, String entityName, Map<String, ?> fields, String error)
            throws GenericEntityException, AssetsBookException {
        GenericValue value = EntityQuery.use(delegator).from(entityName).where(fields).queryOne();
        if (value == null) throw new AssetsBookException(error);
        return value;
    }

    private static BigDecimal money(Object value, BigDecimal fallback, String label) throws AssetsBookException {
        BigDecimal amount = value instanceof BigDecimal ? (BigDecimal) value : fallback;
        if (amount == null) amount = BigDecimal.ZERO;
        try {
            return amount.setScale(MONEY_SCALE, ROUNDING);
        } catch (ArithmeticException e) {
            throw new AssetsBookException(label + " is invalid.");
        }
    }

    private static Timestamp timestamp(Object value, String error) throws AssetsBookException {
        if (value instanceof Timestamp) return (Timestamp) value;
        throw new AssetsBookException(error);
    }

    private static Long longValue(Object value) {
        if (value instanceof Number) return ((Number) value).longValue();
        return UtilValidate.isEmpty(value) ? null : Long.valueOf(value.toString());
    }

    private static String stringValue(Object value) { return value == null ? null : value.toString(); }
    private static String stringOrDefault(Object value, String fallback) {
        String text = stringValue(value);
        return UtilValidate.isEmpty(text) ? fallback : text;
    }
    private static Locale locale(Map<String, ? extends Object> context) {
        Object locale = context.get("locale");
        return locale instanceof Locale ? (Locale) locale : Locale.getDefault();
    }
    private static String appendComment(String existing, String addition) {
        if (UtilValidate.isEmpty(addition)) return existing;
        return UtilValidate.isEmpty(existing) ? addition : existing + " | " + addition;
    }
    private static String message(String key, Locale locale, Object... pairs) {
        Map<String, Object> values = new HashMap<>();
        for (int i = 0; i + 1 < pairs.length; i += 2) values.put(String.valueOf(pairs[i]), pairs[i + 1]);
        return UtilProperties.getMessage(RESOURCE, key, values, locale);
    }

    private static final class AssetsBookException extends Exception {
        private AssetsBookException(String message) { super(message); }
    }
}
