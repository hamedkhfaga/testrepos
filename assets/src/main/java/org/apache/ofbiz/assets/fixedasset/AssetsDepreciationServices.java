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
import java.math.RoundingMode;
import java.sql.Date;
import java.sql.Timestamp;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import org.apache.ofbiz.base.util.Debug;
import org.apache.ofbiz.base.util.UtilDateTime;
import org.apache.ofbiz.base.util.UtilProperties;
import org.apache.ofbiz.base.util.UtilValidate;
import org.apache.ofbiz.entity.Delegator;
import org.apache.ofbiz.entity.GenericEntityException;
import org.apache.ofbiz.entity.GenericValue;
import org.apache.ofbiz.entity.condition.EntityCondition;
import org.apache.ofbiz.entity.condition.EntityOperator;
import org.apache.ofbiz.entity.util.EntityQuery;
import org.apache.ofbiz.security.Security;
import org.apache.ofbiz.service.DispatchContext;
import org.apache.ofbiz.service.GenericServiceException;
import org.apache.ofbiz.service.LocalDispatcher;
import org.apache.ofbiz.service.ServiceUtil;

/**
 * Enterprise fixed asset depreciation services for the assets plugin.
 *
 * <p>The physical FixedAsset remains owned by core OFBiz. These services operate
 * only on the Assets* financial subledger entities and hand validated accounting
 * entries to the native OFBiz general ledger services.</p>
 */
public final class AssetsDepreciationServices {

    private static final String MODULE = AssetsDepreciationServices.class.getName();
    private static final String RESOURCE = "AssetsUiLabels";

    private static final int LEDGER_SCALE = 2;
    private static final int RATE_SCALE = 4;
    private static final int QUANTITY_SCALE = 4;
    private static final RoundingMode ROUNDING = RoundingMode.HALF_UP;

    private static final BigDecimal ZERO = BigDecimal.ZERO.setScale(LEDGER_SCALE, ROUNDING);
    private static final BigDecimal ONE = BigDecimal.ONE.setScale(RATE_SCALE, ROUNDING);
    private static final BigDecimal TWO = new BigDecimal("2").setScale(RATE_SCALE, ROUNDING);
    private static final BigDecimal TWELVE = new BigDecimal("12").setScale(RATE_SCALE, ROUNDING);

    private static final String STATUS_ACTIVE = "ASSETS_ACTIVE";
    private static final String STATUS_IN_SERVICE = "ASSETS_IN_SERVICE";
    private static final String STATUS_PLANNED = "ASSETS_PLANNED";
    private static final String STATUS_POSTED = "ASSETS_POSTED";
    private static final String STATUS_REVERSED = "ASSETS_REVERSED";
    private static final String STATUS_DISPOSED = "ASSETS_DISPOSED";
    private static final String STATUS_CANCELLED = "ASSETS_CANCELLED";

    private static final String CUSTOM_METHOD_TYPE_DEPRECIATION = "DEPRECIATION_FORMULA";
    private static final String ACCTG_TRANS_TYPE_DEPRECIATION = "DEPRECIATION";
    private static final String GL_FISCAL_TYPE_ACTUAL = "ACTUAL";
    private static final String NA = "_NA_";

    private enum ProcessingMode {
        CALCULATE_ONLY,
        CREATE_SCHEDULE,
        POST
    }

    private AssetsDepreciationServices() {
    }

    public static Map<String, Object> assetsPermissionCheck(DispatchContext dctx, Map<String, ? extends Object> context) {
        Map<String, Object> result = ServiceUtil.returnSuccess();
        Locale locale = locale(context);
        GenericValue userLogin = (GenericValue) context.get("userLogin");
        String mainAction = stringValue(context.get("mainAction"));
        if (UtilValidate.isEmpty(mainAction)) {
            return ServiceUtil.returnError("Asset permission check failed: mainAction is required.");
        }
        Security security = dctx.getSecurity();
        boolean hasPermission = security.hasEntityPermission("ASSETS", "_" + mainAction, userLogin);
        result.put("hasPermission", hasPermission);
        if (!hasPermission) {
            result.put("failMessage", UtilProperties.getMessage(RESOURCE, "AssetsPermissionError", locale)
                    + " Required permission: ASSETS_" + mainAction + " or ASSETS_ADMIN.");
        }
        return result;
    }

    public static Map<String, Object> assetsFindDepreciationCandidates(DispatchContext dctx, Map<String, ? extends Object> context) {
        LocalDispatcher dispatcher = dctx.getDispatcher();
        Locale locale = locale(context);
        try {
            Map<String, Object> inputFields = new HashMap<>();
            putIfNotEmpty(inputFields, "assetsBookId", context.get("assetsBookId"));
            putIfNotEmpty(inputFields, "fixedAssetId", context.get("fixedAssetId"));
            putIfNotEmpty(inputFields, "statusId", context.get("statusId"));

            Map<String, Object> serviceContext = new HashMap<>();
            serviceContext.put("entityName", "AssetsBookDetail");
            serviceContext.put("inputFields", inputFields);
            serviceContext.put("orderBy", "assetsBookId|fixedAssetId|-fromDate");
            serviceContext.put("noConditionFind", "Y");
            serviceContext.put("filterByDate", "Y");
            putIfNotEmpty(serviceContext, "viewIndex", context.get("viewIndex"));
            putIfNotEmpty(serviceContext, "viewSize", context.get("viewSize"));
            putIfNotEmpty(serviceContext, "userLogin", context.get("userLogin"));

            Map<String, Object> findResult = dispatcher.runSync("performFindList", serviceContext);
            if (ServiceUtil.isError(findResult)) {
                return ServiceUtil.returnError("Unable to list depreciation candidates. OFBiz performFindList returned: "
                        + ServiceUtil.getErrorMessage(findResult));
            }
            Map<String, Object> result = ServiceUtil.returnSuccess("Depreciation candidates were retrieved successfully.");
            result.put("assetBookDetails", findResult.get("list"));
            result.put("listSize", findResult.get("listSize"));
            result.put("viewIndex", findResult.get("viewIndex"));
            result.put("viewSize", findResult.get("viewSize"));
            return result;
        } catch (GenericServiceException e) {
            Debug.logError(e, "Unable to retrieve depreciation candidates", MODULE);
            return ServiceUtil.returnError(message("AssetsErrorServiceUnexpectedFailure", locale)
                    + " Detail: " + e.getMessage());
        }
    }

    public static Map<String, Object> assetsValidateAssetBookPostingSetup(DispatchContext dctx, Map<String, ? extends Object> context) {
        Locale locale = locale(context);
        try {
            Delegator delegator = dctx.getDelegator();
            GenericValue assetsBook = requireAssetsBook(delegator, stringValue(context.get("assetsBookId")), locale);
            Timestamp transactionDate = resolveTransactionDate(delegator, context, null);
            validateAssetsBookEffective(assetsBook, transactionDate, locale);
            validatePartyAccountingPreference(delegator, dctx.getDispatcher(), assetsBook, transactionDate, locale);

            Map<String, Object> result = ServiceUtil.returnSuccess("Asset book posting setup is valid.");
            result.put("assetsBook", assetsBook);
            if ("Y".equals(assetsBook.getString("isPostingBook"))) {
                Map<String, Object> glResult = resolveGlAccountsInternal(delegator, assetsBook,
                        stringValue(context.get("fixedAssetId")), stringValue(context.get("fixedAssetTypeId")),
                        transactionDate, true, locale);
                result.putAll(glResult);
            }
            return result;
        } catch (GeneralAssetsException e) {
            Debug.logError(e, e.getMessage(), MODULE);
            return ServiceUtil.returnError(e.getMessage());
        } catch (Exception e) {
            Debug.logError(e, "Asset book setup validation failed", MODULE);
            return ServiceUtil.returnError(message("AssetsErrorServiceUnexpectedFailure", locale)
                    + " Detail: " + e.getMessage());
        }
    }

    public static Map<String, Object> assetsResolveAssetBookGlAccounts(DispatchContext dctx, Map<String, ? extends Object> context) {
        Locale locale = locale(context);
        try {
            Delegator delegator = dctx.getDelegator();
            GenericValue assetsBook = requireAssetsBook(delegator, stringValue(context.get("assetsBookId")), locale);
            Timestamp transactionDate = resolveTransactionDate(delegator, context, null);
            validateAssetsBookEffective(assetsBook, transactionDate, locale);
            Map<String, Object> glResult = resolveGlAccountsInternal(delegator, assetsBook,
                    stringValue(context.get("fixedAssetId")), stringValue(context.get("fixedAssetTypeId")),
                    transactionDate, true, locale);
            Map<String, Object> result = ServiceUtil.returnSuccess("Asset book GL account mapping was resolved.");
            result.putAll(glResult);
            return result;
        } catch (GeneralAssetsException e) {
            Debug.logError(e, e.getMessage(), MODULE);
            return ServiceUtil.returnError(e.getMessage());
        } catch (Exception e) {
            Debug.logError(e, "Unable to resolve asset book GL accounts", MODULE);
            return ServiceUtil.returnError(message("AssetsErrorServiceUnexpectedFailure", locale)
                    + " Detail: " + e.getMessage());
        }
    }

    public static Map<String, Object> assetsRunDepreciationFormula(DispatchContext dctx, Map<String, ? extends Object> context) {
        Locale locale = locale(context);
        try {
            FormulaResult formulaResult = runFormulaService(dctx.getDelegator(), dctx.getDispatcher(), context, locale);
            Map<String, Object> result = ServiceUtil.returnSuccess("Depreciation formula completed successfully.");
            result.put("depreciationAmount", formulaResult.depreciationAmount);
            result.put("rateFactor", formulaResult.rateFactor);
            result.put("formulaServiceName", formulaResult.serviceName);
            return result;
        } catch (GeneralAssetsException e) {
            Debug.logError(e, e.getMessage(), MODULE);
            return ServiceUtil.returnError(e.getMessage());
        } catch (Exception e) {
            Debug.logError(e, "Depreciation formula execution failed", MODULE);
            return ServiceUtil.returnError(message("AssetsErrorServiceUnexpectedFailure", locale)
                    + " Detail: " + e.getMessage());
        }
    }

    public static Map<String, Object> assetsCalculateDepreciationForPeriod(DispatchContext dctx, Map<String, ? extends Object> context) {
        return processDepreciation(dctx, context, ProcessingMode.CALCULATE_ONLY);
    }

    public static Map<String, Object> assetsCreateDepreciationSchedule(DispatchContext dctx, Map<String, ? extends Object> context) {
        return processDepreciation(dctx, context, ProcessingMode.CREATE_SCHEDULE);
    }

    public static Map<String, Object> assetsPostDepreciationForPeriod(DispatchContext dctx, Map<String, ? extends Object> context) {
        return processDepreciation(dctx, context, ProcessingMode.POST);
    }

    public static Map<String, Object> assetsPostDepreciationForAssetBook(DispatchContext dctx, Map<String, ? extends Object> context) {
        return processDepreciation(dctx, context, ProcessingMode.POST);
    }

    public static Map<String, Object> assetsReverseDepreciationPosting(DispatchContext dctx, Map<String, ? extends Object> context) {
        Locale locale = locale(context);
        boolean dryRun = isDryRun(context);
        try {
            Delegator delegator = dctx.getDelegator();
            LocalDispatcher dispatcher = dctx.getDispatcher();
            GenericValue userLogin = (GenericValue) context.get("userLogin");
            String scheduleId = stringValue(context.get("assetsBookDepScheduleId"));
            if (UtilValidate.isEmpty(scheduleId)) {
                return ServiceUtil.returnError("Reverse depreciation failed: assetsBookDepScheduleId is required.");
            }
            GenericValue schedule = EntityQuery.use(delegator).from("AssetsBookDepSchedule")
                    .where("assetsBookDepScheduleId", scheduleId).queryOne();
            if (schedule == null) {
                return ServiceUtil.returnError(message("AssetsErrorDepScheduleNotFound", locale)
                        + " Schedule ID: " + scheduleId + ".");
            }
            if (!STATUS_POSTED.equals(schedule.getString("statusId"))) {
                return ServiceUtil.returnError("Reverse depreciation failed for schedule " + scheduleId
                        + ": only ASSETS_POSTED rows can be reversed. Current status is "
                        + schedule.getString("statusId") + ".");
            }
            if (UtilValidate.isEmpty(schedule.getString("postedAcctgTransId"))) {
                return ServiceUtil.returnError("Reverse depreciation failed for schedule " + scheduleId
                        + ": no posted accounting transaction is linked to this row.");
            }
            GenericValue reversalBook = requireAssetsBook(delegator, schedule.getString("assetsBookId"), locale);
            AssetsRequestServices.requireOrganizationAccess(dctx, userLogin, reversalBook.getString("partyId"));
            GenericValue bookDetail = findActiveBookDetail(delegator, schedule.getString("assetsBookId"),
                    schedule.getString("fixedAssetId"), toTimestampEnd(schedule.getDate("periodEndDate")));
            if (bookDetail == null) throw new GeneralAssetsException("Active asset book detail was not found.");
            if (!dryRun) { lockByAuditStore(bookDetail, userLogin); schedule.refresh(); }
            if (!STATUS_POSTED.equals(schedule.getString("statusId"))) {
                throw new GeneralAssetsException("This depreciation posting has already been reversed.");
            }
            GenericValue latest = EntityQuery.use(delegator).from("AssetsBookDepSchedule")
                    .where("fixedAssetId", schedule.getString("fixedAssetId"), "assetsBookId", schedule.getString("assetsBookId"), "statusId", STATUS_POSTED)
                    .orderBy("-periodEndDate").queryFirst();
            if (latest == null || !scheduleId.equals(latest.getString("assetsBookDepScheduleId"))) {
                throw new GeneralAssetsException("Reverse the most recent depreciation period first.");
            }
            if (schedule.get("priorRemainingLifeMonths") == null || schedule.get("priorNetBookValue") == null) {
                throw new GeneralAssetsException("This legacy posting has no reversal balance snapshot. Reconcile its opening balances before reversal.");
            }
            if (money(bookDetail.get("accumulatedDepreciation")).compareTo(money(schedule.get("accumulatedDepreciation"))) != 0
                    || money(bookDetail.get("netBookValue")).compareTo(money(schedule.get("netBookValue"))) != 0
                    || positiveInt(bookDetail.get("remainingLifeMonths"), 0) != positiveInt(schedule.get("priorRemainingLifeMonths"), 0) - 1) {
                throw new GeneralAssetsException("The book was adjusted after this posting. Reconcile later adjustments before reversing depreciation.");
            }
            GenericValue original = EntityQuery.use(delegator).from("AcctgTrans")
                    .where("acctgTransId", schedule.getString("postedAcctgTransId")).queryOne();
            if (original == null || !"Y".equals(original.getString("isPosted"))) {
                throw new GeneralAssetsException("The original journal must be posted before reversal.");
            }
            validateOpenFiscalPeriod(dispatcher, reversalBook.getString("partyId"), original.getTimestamp("transactionDate"), locale, userLogin);
            if (dryRun) {
                Map<String, Object> result = ServiceUtil.returnSuccess("Dry run completed. The depreciation schedule can be reversed.");
                result.put("assetsBookDepScheduleId", scheduleId);
                result.put("postedAcctgTransId", schedule.getString("postedAcctgTransId"));
                return result;
            }


            Map<String, Object> reverseContext = new HashMap<>();
            reverseContext.put("fromAcctgTransId", schedule.getString("postedAcctgTransId"));
            reverseContext.put("revert", "Y");
            reverseContext.put("userLogin", userLogin);
            Map<String, Object> reverseResult = dispatcher.runSync("copyAcctgTransAndEntries", reverseContext);
            if (ServiceUtil.isError(reverseResult)) {
                return ServiceUtil.returnError("Reverse depreciation failed for schedule " + scheduleId
                        + ". OFBiz copyAcctgTransAndEntries returned: " + ServiceUtil.getErrorMessage(reverseResult));
            }
            String reversalAcctgTransId = stringValue(reverseResult.get("acctgTransId"));
            Map<String, Object> postContext = new HashMap<>();
            postContext.put("acctgTransId", reversalAcctgTransId);
            postContext.put("userLogin", userLogin);
            Map<String, Object> postResult = postUnlessAlreadyPosted(dispatcher, postContext);
            if (ServiceUtil.isError(postResult)) {
                return ServiceUtil.returnError("Reverse depreciation failed for schedule " + scheduleId
                        + ". OFBiz postAcctgTrans could not post reversal " + reversalAcctgTransId
                        + ". Detail: " + ServiceUtil.getErrorMessage(postResult));
            }

            BigDecimal amount = money(schedule.get("depreciationAmount"));
            BigDecimal accumulated = money(schedule.get("priorAccumulatedDepreciation"));
            BigDecimal nbv = money(schedule.get("priorNetBookValue"));
            bookDetail.set("remainingLifeMonths", schedule.get("priorRemainingLifeMonths"));
            bookDetail.set("lastDepreciationDate", schedule.get("priorLastDepreciationDate"));
            bookDetail.set("nextDepreciationDate", schedule.get("priorNextDepreciationDate"));
            Timestamp now = UtilDateTime.nowTimestamp();
            bookDetail.set("accumulatedDepreciation", accumulated);
            bookDetail.set("netBookValue", nbv);
            bookDetail.set("lastModifiedDate", now);
            bookDetail.set("lastModifiedByUserLogin", userLoginId(userLogin));
            bookDetail.store();

            schedule.set("statusId", STATUS_REVERSED);
            schedule.set("reversalAcctgTransId", stringValue(reverseResult.get("acctgTransId")));
            schedule.set("lastModifiedDate", now);
            schedule.set("lastModifiedByUserLogin", userLoginId(userLogin));
            schedule.store();

            Map<String, Object> result = ServiceUtil.returnSuccess("Depreciation posting was reversed successfully.");
            result.put("assetsBookDepScheduleId", scheduleId);
            result.put("reversalAcctgTransId", reversalAcctgTransId);
            return result;
        } catch (Exception e) {
            Debug.logError(e, "Depreciation reversal failed", MODULE);
            return ServiceUtil.returnError(message("AssetsErrorServiceUnexpectedFailure", locale)
                    + " Detail: " + e.getMessage());
        }
    }

    public static Map<String, Object> straightLineMonthlyDepreciation(DispatchContext dctx, Map<String, ? extends Object> context) {
        Locale locale = locale(context);
        try {
            BigDecimal basis = depreciableBasis(context);
            int usefulLifeMonths = positiveInt(context.get("usefulLifeMonths"), "usefulLifeMonths");
            BigDecimal amount = basis.divide(new BigDecimal(Integer.toString(usefulLifeMonths)), LEDGER_SCALE, ROUNDING);
            // Clear currency rounding residue in the final straight-line month.
            if (positiveInt(context.get("remainingLifeMonths"), usefulLifeMonths) == 1) {
                amount = netBookValue(context).subtract(money(context.get("salvageValue"))).max(ZERO);
            }
            BigDecimal rate = ONE.divide(new BigDecimal(Integer.toString(usefulLifeMonths)), RATE_SCALE, ROUNDING);
            return formulaSuccess(context, amount, rate);
        } catch (GeneralAssetsException e) {
            Debug.logError(e, e.getMessage(), MODULE);
            return ServiceUtil.returnError(e.getMessage());
        } catch (ArithmeticException e) {
            Debug.logError(e, "Straight-line monthly depreciation failed", MODULE);
            return ServiceUtil.returnError(message("AssetsErrorServiceUnexpectedFailure", locale)
                    + " Detail: " + e.getMessage());
        }
    }

    public static Map<String, Object> decliningBalanceDepreciation(DispatchContext dctx, Map<String, ? extends Object> context) {
        try {
            BigDecimal netBookValue = netBookValue(context);
            BigDecimal rate = optionalRate(context.get("depreciationRate"));
            if (rate == null) {
                int usefulLifeMonths = positiveInt(context.get("usefulLifeMonths"), "usefulLifeMonths");
                BigDecimal years = new BigDecimal(Integer.toString(usefulLifeMonths)).divide(TWELVE, RATE_SCALE, ROUNDING);
                rate = TWO.divide(years, RATE_SCALE, ROUNDING);
            }
            BigDecimal amount = netBookValue.multiply(rate).divide(TWELVE, LEDGER_SCALE, ROUNDING);
            return formulaSuccess(context, amount, rate);
        } catch (GeneralAssetsException | ArithmeticException e) {
            Debug.logError(e, "Declining balance depreciation failed", MODULE);
            return ServiceUtil.returnError("Declining balance depreciation failed. Detail: " + e.getMessage());
        }
    }

    public static Map<String, Object> sumOfYearsDigitsDepreciation(DispatchContext dctx, Map<String, ? extends Object> context) {
        try {
            BigDecimal basis = depreciableBasis(context);
            int usefulLifeMonths = positiveInt(context.get("usefulLifeMonths"), "usefulLifeMonths");
            int remainingLifeMonths = positiveInt(context.get("remainingLifeMonths"), usefulLifeMonths);
            if (usefulLifeMonths % 12 != 0 || remainingLifeMonths > usefulLifeMonths) {
                throw new GeneralAssetsException("Sum-of-years-digits requires a useful life in whole years and remaining life within useful life.");
            }
            int years = usefulLifeMonths / 12;
            int remainingYears = years - (usefulLifeMonths - remainingLifeMonths) / 12;
            BigDecimal denominator = BigDecimal.valueOf((long) years * (years + 1) / 2).multiply(TWELVE);
            BigDecimal numerator = BigDecimal.valueOf(remainingYears);
            BigDecimal rate = numerator.divide(denominator, RATE_SCALE, ROUNDING);
            BigDecimal amount = basis.multiply(numerator).divide(denominator, LEDGER_SCALE, ROUNDING);
            return formulaSuccess(context, amount, rate);
        } catch (GeneralAssetsException | ArithmeticException e) {
            Debug.logError(e, "Sum-of-years-digits depreciation failed", MODULE);
            return ServiceUtil.returnError("Sum-of-years-digits depreciation failed. Detail: " + e.getMessage());
        }
    }

    public static Map<String, Object> unitsOfProductionDepreciation(DispatchContext dctx, Map<String, ? extends Object> context) {
        try {
            BigDecimal basis = depreciableBasis(context);
            BigDecimal usageQuantity = requiredPositiveQuantity(context.get("usageQuantity"), "usageQuantity");
            BigDecimal productionCapacity = requiredPositiveQuantity(context.get("productionCapacity"), "productionCapacity");
            BigDecimal rate = usageQuantity.divide(productionCapacity, RATE_SCALE, ROUNDING);
            BigDecimal amount = basis.multiply(usageQuantity).divide(productionCapacity, LEDGER_SCALE, ROUNDING);
            return formulaSuccess(context, amount, rate);
        } catch (GeneralAssetsException | ArithmeticException e) {
            Debug.logError(e, "Units-of-production depreciation failed", MODULE);
            return ServiceUtil.returnError("Units-of-production depreciation failed. Detail: " + e.getMessage());
        }
    }

    public static Map<String, Object> macrsDepreciation(DispatchContext dctx, Map<String, ? extends Object> context) {
        try {
            BigDecimal rate = optionalRate(context.get("macrsRate"));
            if (rate == null) {
                rate = optionalRate(context.get("rateFactor"));
            }
            if (rate == null) {
                throw new GeneralAssetsException("MACRS depreciation requires macrsRate or rateFactor from configuration. "
                        + "Suggestion: store the legal MACRS table rate in the CustomMethod caller context for the target period.");
            }
            BigDecimal amount = depreciableBasis(context).multiply(rate).setScale(LEDGER_SCALE, ROUNDING);
            return formulaSuccess(context, amount, rate.setScale(RATE_SCALE, ROUNDING));
        } catch (GeneralAssetsException e) {
            Debug.logError(e, e.getMessage(), MODULE);
            return ServiceUtil.returnError(e.getMessage());
        }
    }

    public static Map<String, Object> componentDepreciation(DispatchContext dctx, Map<String, ? extends Object> context) {
        try {
            BigDecimal componentCost = optionalMoney(context.get("componentCost"));
            Map<String, Object> derived = new HashMap<>(context);
            if (componentCost != null) {
                derived.put("capitalizedCost", componentCost);
                derived.put("depreciableBasis", componentCost.subtract(money(context.get("salvageValue"))).max(ZERO));
            }
            return straightLineMonthlyDepreciation(dctx, derived);
        } catch (RuntimeException e) {
            Debug.logError(e, "Component depreciation failed", MODULE);
            return ServiceUtil.returnError("Component depreciation failed. Detail: " + e.getMessage());
        }
    }

    private static Map<String, Object> processDepreciation(DispatchContext dctx, Map<String, ? extends Object> context, ProcessingMode mode) {
        Locale locale = locale(context);
        boolean dryRun = mode == ProcessingMode.CALCULATE_ONLY || isDryRun(context);
        try {
            Delegator delegator = dctx.getDelegator();
            LocalDispatcher dispatcher = dctx.getDispatcher();
            GenericValue userLogin = (GenericValue) context.get("userLogin");
            String assetsBookId = stringValue(context.get("assetsBookId"));
            if (UtilValidate.isEmpty(assetsBookId)) {
                return ServiceUtil.returnError("Depreciation processing failed: assetsBookId is required.");
            }
            GenericValue assetsBook = requireAssetsBook(delegator, assetsBookId, locale);
            AssetsRequestServices.requireOrganizationAccess(dctx, userLogin, assetsBook.getString("partyId"));
            PeriodWindow period = resolvePeriodWindow(delegator, context);
            Timestamp transactionDate = resolveTransactionDate(delegator, context, period);

            validateAssetsBookEffective(assetsBook, transactionDate, locale);
            validatePartyAccountingPreference(delegator, dispatcher, assetsBook, transactionDate, locale);
            if (mode == ProcessingMode.POST && "Y".equals(assetsBook.getString("isPostingBook"))) {
                validateOpenFiscalPeriod(dispatcher, assetsBook.getString("partyId"), transactionDate, locale, userLogin);
            }

            List<GenericValue> bookDetails = findCandidateBookDetails(delegator, assetsBookId,
                    stringValue(context.get("fixedAssetId")), transactionDate, stringValue(context.get("statusId")));
            if (bookDetails.isEmpty()) {
                return ServiceUtil.returnError("No eligible AssetsBookDetail rows were found for assetsBookId " + assetsBookId
                        + " and period " + period.periodStartDate + " to " + period.periodEndDate
                        + ". Suggestion: confirm the asset book detail status is ASSETS_ACTIVE or ASSETS_IN_SERVICE and its effective dates include the period.");
            }

            List<Map<String, Object>> resultItems = new ArrayList<>();
            List<Map<String, Object>> proposedEntries = new ArrayList<>();
            String assetsDepRunId = null;
            if (!dryRun && mode != ProcessingMode.CALCULATE_ONLY) {
                assetsDepRunId = createDepRun(delegator, assetsBook, period, transactionDate, userLogin, mode);
            }

            int processedCount = 0;
            int skippedCount = 0;
            for (GenericValue bookDetail : bookDetails) {
                ProcessingItem item = processOneBookDetail(delegator, dispatcher, assetsBook, bookDetail, period,
                        transactionDate, assetsDepRunId, dryRun, mode, userLogin, locale);
                resultItems.add(item.resultMap);
                if (item.proposedEntries != null) {
                    proposedEntries.addAll(item.proposedEntries);
                }
                if (item.processed) {
                    processedCount++;
                } else {
                    skippedCount++;
                }
            }

            if (!dryRun && UtilValidate.isNotEmpty(assetsDepRunId)) {
                GenericValue run = EntityQuery.use(delegator).from("AssetsDepRun")
                        .where("assetsDepRunId", assetsDepRunId).queryOne();
                if (run != null) {
                    run.set("statusId", processedCount > 0 ? STATUS_POSTED : STATUS_PLANNED);
                    run.set("postedDate", mode == ProcessingMode.POST ? UtilDateTime.nowTimestamp() : null);
                    run.set("lastModifiedDate", UtilDateTime.nowTimestamp());
                    run.set("lastModifiedByUserLogin", userLoginId(userLogin));
                    run.store();
                }
            }

            Map<String, Object> result = ServiceUtil.returnSuccess(successMessage(mode, dryRun, processedCount, skippedCount));
            result.put("assetsDepRunId", assetsDepRunId);
            result.put("processedCount", processedCount);
            result.put("skippedCount", skippedCount);
            result.put("resultItems", resultItems);
            result.put("proposedAcctgTransEntries", proposedEntries);
            result.put("dryRun", dryRun ? "Y" : "N");
            return result;
        } catch (GeneralAssetsException e) {
            Debug.logError(e, e.getMessage(), MODULE);
            return ServiceUtil.returnError(e.getMessage());
        } catch (Exception e) {
            Debug.logError(e, "Asset depreciation processing failed", MODULE);
            return ServiceUtil.returnError(message("AssetsErrorServiceUnexpectedFailure", locale)
                    + " Detail: " + e.getMessage());
        }
    }

    private static ProcessingItem processOneBookDetail(Delegator delegator, LocalDispatcher dispatcher, GenericValue assetsBook,
            GenericValue bookDetail, PeriodWindow period, Timestamp transactionDate, String assetsDepRunId, boolean dryRun,
            ProcessingMode mode, GenericValue userLogin, Locale locale) throws GenericEntityException, GenericServiceException, GeneralAssetsException {
        String fixedAssetId = bookDetail.getString("fixedAssetId");
        String assetsBookId = bookDetail.getString("assetsBookId");
        Map<String, Object> item = new LinkedHashMap<>();
        item.put("fixedAssetId", fixedAssetId);
        item.put("assetsBookId", assetsBookId);
        item.put("periodStartDate", period.periodStartDate);
        item.put("periodEndDate", period.periodEndDate);

        if (!dryRun) lockByAuditStore(bookDetail, userLogin);
        GenericValue fixedAsset = EntityQuery.use(delegator).from("FixedAsset")
                .where("fixedAssetId", fixedAssetId).cache().queryOne();
        Timestamp placedInServiceDate = bookDetail.getTimestamp("placedInServiceDate");
        if (placedInServiceDate != null && placedInServiceDate.after(transactionDate)) {
            item.put("status", "SKIPPED");
            item.put("message", "The asset is not yet placed in service for this depreciation period.");
            return new ProcessingItem(false, item, Collections.emptyList());
        }
        Date actualEndOfLife = fixedAsset == null ? null : fixedAsset.getDate("actualEndOfLife");
        if (actualEndOfLife != null && !actualEndOfLife.after(new Date(transactionDate.getTime()))) {
            item.put("status", "SKIPPED");
            item.put("message", "The physical asset reached its actual end-of-life date before this period.");
            return new ProcessingItem(false, item, Collections.emptyList());
        }
        if (positiveInt(bookDetail.get("remainingLifeMonths"), 0) <= 0
                || netBookValueFromBookDetail(bookDetail).compareTo(money(bookDetail.get("salvageValue"))) <= 0) {
            item.put("status", "SKIPPED");
            item.put("message", "Depreciation is complete because remaining life is zero or NBV has reached salvage value.");
            return new ProcessingItem(false, item, Collections.emptyList());
        }

        GenericValue existingSchedule = findScheduleForPeriod(delegator, fixedAssetId, assetsBookId, period);
        if (isAlreadyPosted(existingSchedule)) {
            throw new GeneralAssetsException(message("AssetsErrorDepScheduleAlreadyPosted", locale)
                    + " Fixed asset: " + fixedAssetId + ", book: " + assetsBookId
                    + ", period: " + period.periodStartDate + " to " + period.periodEndDate + ".");
        }
        GenericValue latestPostedSchedule = EntityQuery.use(delegator).from("AssetsBookDepSchedule")
                .where("fixedAssetId", fixedAssetId, "assetsBookId", assetsBookId, "statusId", STATUS_POSTED)
                .orderBy("-periodEndDate").queryFirst();
        Timestamp lastPosted = bookDetail.getTimestamp("lastDepreciationDate");
        Date lastPeriodEnd = latestPostedSchedule == null
                ? (lastPosted == null ? null : new Date(lastPosted.getTime())) : latestPostedSchedule.getDate("periodEndDate");
        if (lastPeriodEnd != null && !period.periodStartDate.after(lastPeriodEnd)) {
            throw new GeneralAssetsException("Depreciation periods must follow the last posted period without overlap. Reverse later periods first.");
        }

        FormulaResult formulaResult = runFormulaForBookDetail(delegator, dispatcher, bookDetail, period, transactionDate, userLogin, locale);
        if (Arrays.asList("straightLineMonthlyDepreciation", "decliningBalanceDepreciation",
                "sumOfYearsDigitsDepreciation", "componentDepreciation").contains(formulaResult.serviceName)) {
            validateMonthlyPeriod(period.periodStartDate, period.periodEndDate);
        }
        BigDecimal amount = capDepreciationAmount(bookDetail, formulaResult.depreciationAmount);
        item.put("formulaServiceName", formulaResult.serviceName);
        item.put("rateFactor", formulaResult.rateFactor);
        item.put("depreciationAmount", amount);
        if (amount.compareTo(ZERO) <= 0) {
            item.put("status", "SKIPPED");
            item.put("message", "No depreciation amount is available without reducing NBV below salvage value.");
            return new ProcessingItem(false, item, Collections.emptyList());
        }

        BigDecimal newAccumulated = money(bookDetail.get("accumulatedDepreciation")).add(amount).setScale(LEDGER_SCALE, ROUNDING);
        BigDecimal newNetBookValue = computeNetBookValue(bookDetail, newAccumulated);
        item.put("accumulatedDepreciation", newAccumulated);
        item.put("netBookValue", newNetBookValue);

        List<Map<String, Object>> acctgEntries = Collections.emptyList();
        String acctgTransId = null;
        String scheduleId = existingSchedule != null ? existingSchedule.getString("assetsBookDepScheduleId") : null;

        if (mode == ProcessingMode.POST && "Y".equals(assetsBook.getString("isPostingBook"))) {
            String fixedAssetTypeId = fixedAsset == null ? null : fixedAsset.getString("fixedAssetTypeId");
            Map<String, Object> glAccounts = resolveGlAccountsInternal(delegator, assetsBook, fixedAssetId, fixedAssetTypeId,
                    transactionDate, true, locale);
            acctgEntries = buildDepreciationAcctgEntries(assetsBook, amount, glAccounts, fixedAssetId);
            item.put("debitGlAccountId", glAccounts.get("depGlAccountId"));
            item.put("creditGlAccountId", glAccounts.get("accDepGlAccountId"));
            item.put("acctgTransEntries", acctgEntries);
        }

        if (!dryRun && mode != ProcessingMode.CALCULATE_ONLY) {
            GenericValue schedule = upsertSchedule(delegator, existingSchedule, fixedAssetId, assetsBookId, period,
                    amount, newAccumulated, newNetBookValue, assetsDepRunId, userLogin);
            scheduleId = schedule.getString("assetsBookDepScheduleId");
            if (mode == ProcessingMode.POST) {
                schedule.set("priorRemainingLifeMonths", bookDetail.get("remainingLifeMonths"));
                schedule.set("priorLastDepreciationDate", bookDetail.get("lastDepreciationDate"));
                schedule.set("priorNextDepreciationDate", bookDetail.get("nextDepreciationDate"));
                schedule.set("priorAccumulatedDepreciation", bookDetail.get("accumulatedDepreciation"));
                schedule.set("priorNetBookValue", bookDetail.get("netBookValue"));
                schedule.set("reversalAcctgTransId", null);
                schedule.store();
            }
            if (mode == ProcessingMode.POST && "Y".equals(assetsBook.getString("isPostingBook"))) {
                acctgTransId = createAndPostDepreciationTransaction(dispatcher, assetsBook, fixedAssetId, amount,
                        acctgEntries, transactionDate, userLogin);
                schedule.set("postedAcctgTransId", acctgTransId);
                schedule.set("statusId", STATUS_POSTED);
                schedule.store();
            } else if (mode == ProcessingMode.POST) {
                schedule.set("statusId", STATUS_POSTED);
                schedule.store();
            }
            if (mode == ProcessingMode.POST) {
                updateBookDetailAfterPosting(bookDetail, amount, newAccumulated, newNetBookValue, transactionDate, userLogin);
            }
            createDepRunItem(delegator, assetsDepRunId, scheduleId, fixedAssetId, assetsBookId, amount, acctgTransId,
                    mode == ProcessingMode.POST ? STATUS_POSTED : STATUS_PLANNED, userLogin);
        }

        item.put("assetsBookDepScheduleId", scheduleId);
        item.put("acctgTransId", acctgTransId);
        item.put("status", dryRun ? "DRY_RUN" : "PROCESSED");
        return new ProcessingItem(true, item, acctgEntries);
    }

    private static FormulaResult runFormulaForBookDetail(Delegator delegator, LocalDispatcher dispatcher, GenericValue bookDetail,
            PeriodWindow period, Timestamp transactionDate, GenericValue userLogin, Locale locale)
            throws GenericEntityException, GenericServiceException, GeneralAssetsException {
        Map<String, Object> formulaContext = new HashMap<>();
        formulaContext.put("depreciationCustomMethodId", bookDetail.getString("depreciationCustomMethodId"));
        formulaContext.put("fixedAssetId", bookDetail.getString("fixedAssetId"));
        formulaContext.put("assetsBookId", bookDetail.getString("assetsBookId"));
        formulaContext.put("capitalizedCost", money(bookDetail.get("capitalizedCost")));
        formulaContext.put("originalCost", money(bookDetail.get("originalCost")));
        formulaContext.put("salvageValue", money(bookDetail.get("salvageValue")));
        formulaContext.put("depreciableBasis", money(bookDetail.get("depreciableBasis")));
        formulaContext.put("usefulLifeMonths", bookDetail.get("usefulLifeMonths"));
        formulaContext.put("remainingLifeMonths", bookDetail.get("remainingLifeMonths"));
        formulaContext.put("accumulatedDepreciation", money(bookDetail.get("accumulatedDepreciation")));
        formulaContext.put("netBookValue", netBookValueFromBookDetail(bookDetail));
        formulaContext.put("productionCapacity", optionalQuantity(bookDetail.get("productionCapacity")));
        formulaContext.put("productionCapacityUomId", bookDetail.getString("productionCapacityUomId"));
        formulaContext.put("periodStartDate", period.periodStartDate);
        formulaContext.put("periodEndDate", period.periodEndDate);
        formulaContext.put("customTimePeriodId", period.customTimePeriodId);
        formulaContext.put("transactionDate", transactionDate);
        formulaContext.put("userLogin", userLogin);
        formulaContext.put("locale", locale);
        return runFormulaService(delegator, dispatcher, formulaContext, locale);
    }

    private static FormulaResult runFormulaService(Delegator delegator, LocalDispatcher dispatcher, Map<String, ? extends Object> context,
            Locale locale) throws GenericEntityException, GenericServiceException, GeneralAssetsException {
        String customMethodId = stringValue(context.get("depreciationCustomMethodId"));
        if (UtilValidate.isEmpty(customMethodId)) {
            throw new GeneralAssetsException(message("AssetsErrorDepMethodRequired", locale)
                    + " Suggestion: set depreciationCustomMethodId on AssetsBookDetail.");
        }
        GenericValue customMethod = EntityQuery.use(delegator).from("CustomMethod")
                .where("customMethodId", customMethodId).cache().queryOne();
        if (customMethod == null) {
            throw new GeneralAssetsException("Depreciation method " + customMethodId
                    + " was not found in CustomMethod. Suggestion: load or create a DEPRECIATION_FORMULA CustomMethod.");
        }
        if (!CUSTOM_METHOD_TYPE_DEPRECIATION.equals(customMethod.getString("customMethodTypeId"))) {
            throw new GeneralAssetsException("CustomMethod " + customMethodId + " has type "
                    + customMethod.getString("customMethodTypeId")
                    + " but must be DEPRECIATION_FORMULA for asset depreciation.");
        }
        String serviceName = customMethod.getString("customMethodName");
        if (UtilValidate.isEmpty(serviceName)) {
            throw new GeneralAssetsException("CustomMethod " + customMethodId
                    + " has no customMethodName. Suggestion: set customMethodName to the depreciation formula service.");
        }
        Map<String, Object> formulaContext = new HashMap<>(context);
        formulaContext.put("customMethodId", customMethodId);
        Map<String, Object> serviceResult = dispatcher.runSync(serviceName, formulaContext);
        if (ServiceUtil.isError(serviceResult)) {
            throw new GeneralAssetsException("Depreciation formula service " + serviceName + " failed for CustomMethod "
                    + customMethodId + ". Detail: " + ServiceUtil.getErrorMessage(serviceResult));
        }
        BigDecimal amount = firstDecimal(serviceResult, LEDGER_SCALE, "depreciationAmount", "amount", "assetDepreciationAmount");
        if (amount == null) {
            throw new GeneralAssetsException("Depreciation formula service " + serviceName
                    + " did not return depreciationAmount. Suggestion: return a BigDecimal field named depreciationAmount.");
        }
        BigDecimal rate = firstDecimal(serviceResult, RATE_SCALE, "rateFactor", "depreciationRate");
        return new FormulaResult(serviceName, amount.setScale(LEDGER_SCALE, ROUNDING),
                rate == null ? null : rate.setScale(RATE_SCALE, ROUNDING));
    }

    private static GenericValue requireAssetsBook(Delegator delegator, String assetsBookId, Locale locale)
            throws GenericEntityException, GeneralAssetsException {
        if (UtilValidate.isEmpty(assetsBookId)) {
            throw new GeneralAssetsException(message("AssetsErrorBookRequired", locale));
        }
        GenericValue assetsBook = EntityQuery.use(delegator).from("AssetsBook")
                .where("assetsBookId", assetsBookId).queryOne();
        if (assetsBook == null) {
            throw new GeneralAssetsException(message("AssetsErrorBookNotFound", locale)
                    .replace("${assetsBookId}", assetsBookId));
        }
        return assetsBook;
    }

    private static void validateAssetsBookEffective(GenericValue assetsBook, Timestamp transactionDate, Locale locale)
            throws GeneralAssetsException {
        if (!STATUS_ACTIVE.equals(assetsBook.getString("statusId"))) {
            throw new GeneralAssetsException("Asset book " + assetsBook.getString("assetsBookId") + " has status "
                    + assetsBook.getString("statusId") + " and cannot process depreciation. Suggestion: select an active book.");
        }
        if (!isEffective(assetsBook, transactionDate)) {
            throw new GeneralAssetsException("Asset book " + assetsBook.getString("assetsBookId")
                    + " is not effective on " + transactionDate
                    + ". Suggestion: use a date within AssetsBook.fromDate (inclusive) and thruDate (exclusive), or transition to the active successor book.");
        }
    }

    private static void validatePartyAccountingPreference(Delegator delegator, LocalDispatcher dispatcher, GenericValue assetsBook,
            Timestamp transactionDate, Locale locale) throws GenericEntityException, GenericServiceException, GeneralAssetsException {
        String partyId = assetsBook.getString("partyId");
        GenericValue preference = EntityQuery.use(delegator).from("PartyAcctgPreference")
                .where("partyId", partyId).cache().queryOne();
        if (preference == null) {
            throw new GeneralAssetsException("PartyAcctgPreference is missing for partyId " + partyId
                    + ". Suggestion: configure Accounting Preferences for the legal entity before running asset depreciation.");
        }
        String baseCurrencyUomId = preference.getString("baseCurrencyUomId");
        String bookCurrencyUomId = assetsBook.getString("currencyUomId");
        if (UtilValidate.isNotEmpty(bookCurrencyUomId) && UtilValidate.isNotEmpty(baseCurrencyUomId)
                && !bookCurrencyUomId.equals(baseCurrencyUomId)) {
            throw new GeneralAssetsException("Currency mismatch for asset book " + assetsBook.getString("assetsBookId")
                    + ". Book currency is " + bookCurrencyUomId + " but PartyAcctgPreference.baseCurrencyUomId for partyId "
                    + partyId + " is " + baseCurrencyUomId
                    + ". Suggestion: align AssetsBook.currencyUomId with the company base currency or create a dedicated conversion design.");
        }
    }

    private static void validateOpenFiscalPeriod(LocalDispatcher dispatcher, String partyId, Timestamp transactionDate,
            Locale locale, GenericValue userLogin) throws GenericServiceException, GeneralAssetsException {
        Map<String, Object> periodContext = new HashMap<>();
        periodContext.put("organizationPartyId", partyId);
        periodContext.put("findDate", transactionDate);
        periodContext.put("excludeNoOrganizationPeriods", "Y");
        periodContext.put("onlyIncludePeriodTypeIdList", Arrays.asList("FISCAL_MONTH", "FISCAL_QUARTER", "FISCAL_YEAR"));
        periodContext.put("userLogin", userLogin);
        Map<String, Object> periodResult = dispatcher.runSync("findCustomTimePeriods", periodContext);
        if (ServiceUtil.isError(periodResult)) {
            throw new GeneralAssetsException("Unable to validate fiscal period for partyId " + partyId
                    + ". OFBiz findCustomTimePeriods returned: " + ServiceUtil.getErrorMessage(periodResult));
        }
        List<?> periods = (List<?>) periodResult.get("customTimePeriodList");
        if (UtilValidate.isEmpty(periods)) {
            throw new GeneralAssetsException("No open fiscal period was found for partyId " + partyId + " and transaction date "
                    + transactionDate + ". Suggestion: create CustomTimePeriod records for this legal entity.");
        }
        for (Object periodObject : periods) {
            GenericValue period = (GenericValue) periodObject;
            if ("Y".equals(period.getString("isClosed"))) {
                throw new GeneralAssetsException("The fiscal period " + period.getString("customTimePeriodId")
                        + " is closed for transaction date " + transactionDate
                        + ". Suggestion: reopen the period or choose an open transaction date.");
            }
        }
    }

    private static Map<String, Object> resolveGlAccountsInternal(Delegator delegator, GenericValue assetsBook, String fixedAssetId,
            String fixedAssetTypeId, Timestamp transactionDate, boolean required, Locale locale)
            throws GenericEntityException, GeneralAssetsException {
        String resolvedFixedAssetTypeId = resolveFixedAssetTypeId(delegator, fixedAssetId, fixedAssetTypeId);
        List<GenericValue> mappings = EntityQuery.use(delegator).from("AssetsBookGlAccount")
                .where("assetsBookId", assetsBook.getString("assetsBookId"), "statusId", STATUS_ACTIVE)
                .orderBy("-fromDate").queryList();
        GenericValue selected = null;
        int selectedScore = -1;
        for (GenericValue mapping : mappings) {
            if (!isEffective(mapping, transactionDate)) {
                continue;
            }
            if (!sameOrBlank(mapping.getString("partyId"), assetsBook.getString("partyId"))
                    || !sameOrBlank(mapping.getString("roleTypeId"), assetsBook.getString("roleTypeId"))) {
                continue;
            }
            int assetScore = specificity(mapping.getString("fixedAssetId"), fixedAssetId);
            int typeScore = specificity(mapping.getString("fixedAssetTypeId"), resolvedFixedAssetTypeId);
            if (assetScore < 0 || typeScore < 0) continue;
            int score = assetScore * 10 + typeScore;
            if (score > selectedScore) {
                selected = mapping;
                selectedScore = score;
            }
        }
        if (selected == null && required) {
            throw new GeneralAssetsException(message("AssetsErrorGlMappingMissing", locale)
                    + " Book: " + assetsBook.getString("assetsBookId") + ", fixedAssetId: " + fixedAssetId
                    + ", fixedAssetTypeId: " + resolvedFixedAssetTypeId
                    + ". Suggestion: create an active AssetsBookGlAccount mapping with depGlAccountId and accDepGlAccountId.");
        }
        Map<String, Object> result = new HashMap<>();
        result.put("assetsBookGlAccount", selected);
        result.put("fixedAssetTypeId", resolvedFixedAssetTypeId);
        if (selected != null) {
            requireGlAccount(selected, "depGlAccountId", "debit depreciation expense", locale);
            requireGlAccount(selected, "accDepGlAccountId", "credit accumulated depreciation", locale);
            try {
                AssetsGlMappingServices.validateAccount(delegator, assetsBook.getString("partyId"),
                        selected.getString("depGlAccountId"), "DEPRECIATION", transactionDate);
                AssetsGlMappingServices.validateAccount(delegator, assetsBook.getString("partyId"),
                        selected.getString("accDepGlAccountId"), "ACCUM_DEPRECIATION", transactionDate);
            } catch (Exception e) {
                throw new GeneralAssetsException("Invalid depreciation GL mapping: " + e.getMessage());
            }
            result.put("depGlAccountId", selected.getString("depGlAccountId"));
            result.put("accDepGlAccountId", selected.getString("accDepGlAccountId"));
            result.put("assetGlAccountId", selected.getString("assetGlAccountId"));
        }
        return result;
    }

    private static String resolveFixedAssetTypeId(Delegator delegator, String fixedAssetId, String fixedAssetTypeId)
            throws GenericEntityException, GeneralAssetsException {
        if (UtilValidate.isNotEmpty(fixedAssetTypeId) || UtilValidate.isEmpty(fixedAssetId)) {
            return fixedAssetTypeId;
        }
        GenericValue fixedAsset = EntityQuery.use(delegator).from("FixedAsset")
                .where("fixedAssetId", fixedAssetId).cache().queryOne();
        if (fixedAsset == null) {
            throw new GeneralAssetsException("FixedAsset " + fixedAssetId
                    + " was not found. Suggestion: create or select a valid OFBiz FixedAsset before resolving asset book GL accounts.");
        }
        return fixedAsset.getString("fixedAssetTypeId");
    }

    private static void requireGlAccount(GenericValue mapping, String fieldName, String purpose, Locale locale) throws GeneralAssetsException {
        if (UtilValidate.isEmpty(mapping.getString(fieldName))) {
            throw new GeneralAssetsException(message("AssetsErrorGlMappingMissing", locale)
                    + " Mapping " + mapping.getString("assetsBookGlAccountId") + " is missing " + fieldName
                    + " for " + purpose + ". Suggestion: complete the AssetsBookGlAccount setup.");
        }
    }

    private static List<Map<String, Object>> buildDepreciationAcctgEntries(GenericValue assetsBook, BigDecimal amount,
            Map<String, Object> glAccounts, String fixedAssetId) {
        String partyId = assetsBook.getString("partyId");
        String currencyUomId = assetsBook.getString("currencyUomId");
        Map<String, Object> debit = new HashMap<>();
        debit.put("organizationPartyId", partyId);
        debit.put("glAccountId", glAccounts.get("depGlAccountId"));
        debit.put("debitCreditFlag", "D");
        debit.put("amount", amount);
        debit.put("origAmount", amount);
        debit.put("currencyUomId", currencyUomId);
        debit.put("origCurrencyUomId", currencyUomId);
        debit.put("acctgTransEntryTypeId", NA);

        Map<String, Object> credit = new HashMap<>();
        credit.put("organizationPartyId", partyId);
        credit.put("glAccountId", glAccounts.get("accDepGlAccountId"));
        credit.put("debitCreditFlag", "C");
        credit.put("amount", amount);
        credit.put("origAmount", amount);
        credit.put("currencyUomId", currencyUomId);
        credit.put("origCurrencyUomId", currencyUomId);
        credit.put("acctgTransEntryTypeId", NA);
        return Arrays.asList(debit, credit);
    }

    private static String createAndPostDepreciationTransaction(LocalDispatcher dispatcher, GenericValue assetsBook, String fixedAssetId,
            BigDecimal amount, List<Map<String, Object>> acctgEntries, Timestamp transactionDate, GenericValue userLogin)
            throws GenericServiceException, GeneralAssetsException {
        Map<String, Object> createContext = new HashMap<>();
        createContext.put("acctgTransTypeId", ACCTG_TRANS_TYPE_DEPRECIATION);
        createContext.put("glFiscalTypeId", GL_FISCAL_TYPE_ACTUAL);
        createContext.put("transactionDate", transactionDate);
        createContext.put("fixedAssetId", fixedAssetId);
        createContext.put("partyId", assetsBook.getString("partyId"));
        createContext.put("roleTypeId", assetsBook.getString("roleTypeId"));
        createContext.put("description", "Assets depreciation for fixed asset " + fixedAssetId
                + ", book " + assetsBook.getString("assetsBookId"));
        createContext.put("acctgTransEntries", acctgEntries);
        createContext.put("userLogin", userLogin);
        Map<String, Object> createResult = dispatcher.runSync("createAcctgTransAndEntries", createContext);
        if (ServiceUtil.isError(createResult)) {
            throw new GeneralAssetsException("OFBiz createAcctgTransAndEntries failed for fixed asset " + fixedAssetId
                    + ". Amount: " + amount + ". Detail: " + ServiceUtil.getErrorMessage(createResult)
                    + ". Suggestion: verify GL accounts, internal organization role, and accounting preferences.");
        }
        String acctgTransId = stringValue(createResult.get("acctgTransId"));
        Map<String, Object> postContext = new HashMap<>();
        postContext.put("acctgTransId", acctgTransId);
        postContext.put("userLogin", userLogin);
        Map<String, Object> postResult = postUnlessAlreadyPosted(dispatcher, postContext);
        if (ServiceUtil.isError(postResult)) {
            throw new GeneralAssetsException("OFBiz postAcctgTrans failed for fixed asset " + fixedAssetId
                    + " and accounting transaction " + acctgTransId + ". Detail: " + ServiceUtil.getErrorMessage(postResult)
                    + ". Suggestion: confirm the transaction balances and the fiscal period is open.");
        }
        return acctgTransId;
    }

    // Native accounting SECAs may post during creation. Read the persisted state;
    // never interpret an arbitrary posting error as a successful journal.
    private static Map<String, Object> postUnlessAlreadyPosted(LocalDispatcher dispatcher, Map<String, Object> context)
            throws GenericServiceException, GeneralAssetsException {
        String id = stringValue(context.get("acctgTransId"));
        if (UtilValidate.isEmpty(id)) {
            throw new GeneralAssetsException("Native accounting did not return an accounting transaction ID.");
        }
        try {
            GenericValue transaction = EntityQuery.use(dispatcher.getDelegator()).from("AcctgTrans")
                    .where("acctgTransId", id).queryOne();
            if (transaction == null) {
                throw new GeneralAssetsException("Accounting transaction " + id + " was not created.");
            }
            if (!"Y".equals(transaction.getString("isPosted"))) {
                Map<String, Object> result = dispatcher.runSync("postAcctgTrans", context);
                if (ServiceUtil.isError(result)) {
                    return result;
                }
                transaction.refresh();
            }
            if (!"Y".equals(transaction.getString("isPosted"))) {
                throw new GeneralAssetsException("Accounting transaction " + id + " remains unposted.");
            }
            return ServiceUtil.returnSuccess();
        } catch (GenericEntityException e) {
            throw new GeneralAssetsException("Cannot verify posting state for " + id + ": " + e.getMessage());
        }
    }

    private static GenericValue upsertSchedule(Delegator delegator, GenericValue existingSchedule, String fixedAssetId, String assetsBookId,
            PeriodWindow period, BigDecimal amount, BigDecimal accumulated, BigDecimal nbv, String assetsDepRunId, GenericValue userLogin)
            throws GenericEntityException {
        Timestamp now = UtilDateTime.nowTimestamp();
        GenericValue schedule = existingSchedule;
        if (schedule == null) {
            schedule = delegator.makeValue("AssetsBookDepSchedule");
            schedule.set("assetsBookDepScheduleId", delegator.getNextSeqId("AssetsBookDepSchedule"));
            schedule.set("fixedAssetId", fixedAssetId);
            schedule.set("assetsBookId", assetsBookId);
            schedule.set("periodStartDate", period.periodStartDate);
            schedule.set("periodEndDate", period.periodEndDate);
            schedule.set("customTimePeriodId", period.customTimePeriodId);
            schedule.set("createdDate", now);
            schedule.set("createdByUserLogin", userLoginId(userLogin));
        }
        schedule.set("depreciationAmount", amount);
        schedule.set("accumulatedDepreciation", accumulated);
        schedule.set("netBookValue", nbv);
        schedule.set("assetsDepRunId", assetsDepRunId);
        schedule.set("statusId", STATUS_PLANNED);
        schedule.set("lastModifiedDate", now);
        schedule.set("lastModifiedByUserLogin", userLoginId(userLogin));
        if (existingSchedule == null) {
            delegator.create(schedule);
        } else {
            schedule.store();
        }
        return schedule;
    }

    private static void createDepRunItem(Delegator delegator, String assetsDepRunId, String scheduleId, String fixedAssetId,
            String assetsBookId, BigDecimal amount, String acctgTransId, String statusId, GenericValue userLogin) throws GenericEntityException {
        if (UtilValidate.isEmpty(assetsDepRunId) || UtilValidate.isEmpty(scheduleId)) {
            return;
        }
        GenericValue runItem = delegator.makeValue("AssetsDepRunItem");
        runItem.set("assetsDepRunId", assetsDepRunId);
        runItem.set("assetsBookDepScheduleId", scheduleId);
        runItem.set("fixedAssetId", fixedAssetId);
        runItem.set("assetsBookId", assetsBookId);
        runItem.set("amount", amount);
        runItem.set("acctgTransId", acctgTransId);
        runItem.set("statusId", statusId);
        delegator.createOrStore(runItem);
    }

    private static String createDepRun(Delegator delegator, GenericValue assetsBook, PeriodWindow period, Timestamp transactionDate,
            GenericValue userLogin, ProcessingMode mode) throws GenericEntityException {
        Timestamp now = UtilDateTime.nowTimestamp();
        String assetsDepRunId = delegator.getNextSeqId("AssetsDepRun");
        GenericValue run = delegator.makeValue("AssetsDepRun");
        run.set("assetsDepRunId", assetsDepRunId);
        run.set("assetsBookId", assetsBook.getString("assetsBookId"));
        run.set("partyId", assetsBook.getString("partyId"));
        run.set("roleTypeId", assetsBook.getString("roleTypeId"));
        run.set("customTimePeriodId", period.customTimePeriodId);
        run.set("statusId", STATUS_PLANNED);
        run.set("runDate", now);
        run.set("transactionDate", transactionDate);
        run.set("fromDate", toTimestampStart(period.periodStartDate));
        run.set("thruDate", toTimestampEnd(period.periodEndDate));
        run.set("description", "Assets depreciation " + mode + " for book " + assetsBook.getString("assetsBookId"));
        run.set("createdDate", now);
        run.set("createdByUserLogin", userLoginId(userLogin));
        run.set("lastModifiedDate", now);
        run.set("lastModifiedByUserLogin", userLoginId(userLogin));
        delegator.create(run);
        return assetsDepRunId;
    }

    private static void updateBookDetailAfterPosting(GenericValue bookDetail, BigDecimal amount, BigDecimal accumulated,
            BigDecimal nbv, Timestamp transactionDate, GenericValue userLogin) throws GenericEntityException {
        int remainingLifeMonths = positiveInt(bookDetail.get("remainingLifeMonths"), 0);
        if (remainingLifeMonths > 0) {
            bookDetail.set("remainingLifeMonths", new BigDecimal(Integer.toString(remainingLifeMonths - 1)));
        }
        bookDetail.set("accumulatedDepreciation", accumulated);
        bookDetail.set("netBookValue", nbv);
        bookDetail.set("lastDepreciationDate", transactionDate);
        bookDetail.set("nextDepreciationDate", UtilDateTime.adjustTimestamp(transactionDate, java.util.Calendar.MONTH, 1));
        bookDetail.set("lastModifiedDate", UtilDateTime.nowTimestamp());
        bookDetail.set("lastModifiedByUserLogin", userLoginId(userLogin));
        bookDetail.store();
    }

    private static List<GenericValue> findCandidateBookDetails(Delegator delegator, String assetsBookId, String fixedAssetId,
            Timestamp transactionDate, String requestedStatusId) throws GenericEntityException {
        List<EntityCondition> conditions = new ArrayList<>();
        conditions.add(EntityCondition.makeCondition("assetsBookId", assetsBookId));
        if (UtilValidate.isNotEmpty(fixedAssetId)) {
            conditions.add(EntityCondition.makeCondition("fixedAssetId", fixedAssetId));
        }
        if (UtilValidate.isNotEmpty(requestedStatusId)) {
            conditions.add(EntityCondition.makeCondition("statusId", requestedStatusId));
        } else {
            conditions.add(EntityCondition.makeCondition("statusId", EntityOperator.IN, Arrays.asList(STATUS_ACTIVE, STATUS_IN_SERVICE)));
        }
        conditions.add(EntityCondition.makeCondition("fromDate", EntityOperator.LESS_THAN_EQUAL_TO, transactionDate));
        conditions.add(EntityCondition.makeCondition(
                Arrays.asList(EntityCondition.makeCondition("thruDate", null),
                        EntityCondition.makeCondition("thruDate", EntityOperator.GREATER_THAN, transactionDate)),
                EntityOperator.OR));
        return EntityQuery.use(delegator).from("AssetsBookDetail")
                .where(EntityCondition.makeCondition(conditions, EntityOperator.AND))
                .orderBy("fixedAssetId", "-fromDate").queryList();
    }

    private static GenericValue findActiveBookDetail(Delegator delegator, String assetsBookId, String fixedAssetId, Timestamp transactionDate)
            throws GenericEntityException {
        List<GenericValue> details = findCandidateBookDetails(delegator, assetsBookId, fixedAssetId, transactionDate, null);
        return details.isEmpty() ? null : details.get(0);
    }

    private static GenericValue findScheduleForPeriod(Delegator delegator, String fixedAssetId, String assetsBookId, PeriodWindow period)
            throws GenericEntityException {
        return EntityQuery.use(delegator).from("AssetsBookDepSchedule")
                .where("fixedAssetId", fixedAssetId, "assetsBookId", assetsBookId,
                        "periodStartDate", period.periodStartDate, "periodEndDate", period.periodEndDate)
                .queryFirst();
    }

    private static boolean isAlreadyPosted(GenericValue schedule) {
        return schedule != null && STATUS_POSTED.equals(schedule.getString("statusId"));
    }

    /*
     * This OFBiz 24 checkout does not expose EntityQuery.forUpdate(). Storing an
     * audit stamp inside the current transaction gives the database a write on
     * the target row, while the unique schedule index prevents duplicate period
     * rows if two workers race for the same asset/book/period.
     */
    private static void lockByAuditStore(GenericValue value, GenericValue userLogin) throws GenericEntityException {
        GenericValue audit = value.getDelegator().makeValue(value.getEntityName(), value.getPrimaryKey());
        if (value.getModelEntity().isField("lastModifiedDate")) {
            audit.set("lastModifiedDate", UtilDateTime.nowTimestamp());
        }
        if (value.getModelEntity().isField("lastModifiedByUserLogin")) {
            audit.set("lastModifiedByUserLogin", userLoginId(userLogin));
        }
        audit.store();
        value.refresh();
    }

    static void validateMonthlyPeriod(Date start, Date end) throws GeneralAssetsException {
        java.time.LocalDate first = start.toLocalDate();
        java.time.LocalDate last = end.toLocalDate();
        if (first.getDayOfMonth() != 1 || !last.equals(first.withDayOfMonth(first.lengthOfMonth()))) {
            throw new GeneralAssetsException("Monthly depreciation requires one complete calendar month. Partial-month conventions need an explicitly supported formula.");
        }
    }

    private static PeriodWindow resolvePeriodWindow(Delegator delegator, Map<String, ? extends Object> context)
            throws GenericEntityException, GeneralAssetsException {
        String customTimePeriodId = stringValue(context.get("customTimePeriodId"));
        if (UtilValidate.isNotEmpty(customTimePeriodId)) {
            GenericValue period = EntityQuery.use(delegator).from("CustomTimePeriod")
                    .where("customTimePeriodId", customTimePeriodId).cache().queryOne();
            if (period == null) {
                throw new GeneralAssetsException("CustomTimePeriod " + customTimePeriodId
                        + " was not found. Suggestion: use a valid accounting period.");
            }
            return new PeriodWindow(customTimePeriodId, (Date) period.get("fromDate"), (Date) period.get("thruDate"));
        }
        Date periodStartDate = sqlDate(context.get("periodStartDate"));
        Date periodEndDate = sqlDate(context.get("periodEndDate"));
        if (periodStartDate == null || periodEndDate == null) {
            throw new GeneralAssetsException("Depreciation period is required. Provide customTimePeriodId or both periodStartDate and periodEndDate.");
        }
        if (periodEndDate.before(periodStartDate)) {
            throw new GeneralAssetsException("Invalid depreciation period: periodEndDate must be on or after periodStartDate.");
        }
        return new PeriodWindow(null, periodStartDate, periodEndDate);
    }

    private static Timestamp resolveTransactionDate(Delegator delegator, Map<String, ? extends Object> context, PeriodWindow period)
            throws GenericEntityException, GeneralAssetsException {
        Timestamp transactionDate = timestamp(context.get("transactionDate"));
        if (transactionDate != null) {
            return transactionDate;
        }
        PeriodWindow resolved = period == null ? resolvePeriodWindow(delegator, context) : period;
        return toTimestampEnd(resolved.periodEndDate);
    }

    private static boolean isEffective(GenericValue value, Timestamp transactionDate) {
        Timestamp fromDate = (Timestamp) value.get("fromDate");
        Timestamp thruDate = (Timestamp) value.get("thruDate");
        return (fromDate == null || !fromDate.after(transactionDate)) && (thruDate == null || thruDate.after(transactionDate));
    }

    private static int specificity(String configuredValue, String targetValue) {
        if (UtilValidate.isEmpty(configuredValue) || NA.equals(configuredValue)) {
            return 0;
        }
        return configuredValue.equals(targetValue) ? 10 : -1000;
    }

    private static boolean sameOrBlank(String configuredValue, String targetValue) {
        return UtilValidate.isEmpty(configuredValue) || configuredValue.equals(targetValue);
    }

    private static String successMessage(ProcessingMode mode, boolean dryRun, int processedCount, int skippedCount) {
        String action = mode == ProcessingMode.POST ? "posting" : mode == ProcessingMode.CREATE_SCHEDULE ? "schedule creation" : "calculation";
        return (dryRun ? "Dry run " : "Asset depreciation ") + action + " completed. Processed: "
                + processedCount + ", skipped: " + skippedCount + ".";
    }

    private static Map<String, Object> formulaSuccess(Map<String, ? extends Object> context, BigDecimal amount, BigDecimal rate) {
        BigDecimal cappedAmount = capAmountFromContext(context, amount);
        Map<String, Object> result = ServiceUtil.returnSuccess("Depreciation formula completed successfully.");
        result.put("depreciationAmount", cappedAmount.setScale(LEDGER_SCALE, ROUNDING));
        result.put("rateFactor", rate == null ? null : rate.setScale(RATE_SCALE, ROUNDING));
        return result;
    }

    private static BigDecimal capAmountFromContext(Map<String, ? extends Object> context, BigDecimal amount) {
        BigDecimal salvage = money(context.get("salvageValue"));
        BigDecimal nbv = netBookValue(context);
        BigDecimal max = nbv.subtract(salvage).setScale(LEDGER_SCALE, ROUNDING);
        if (max.compareTo(ZERO) <= 0) {
            return ZERO;
        }
        if (amount.compareTo(max) > 0) {
            return max;
        }
        return amount.max(ZERO).setScale(LEDGER_SCALE, ROUNDING);
    }

    private static BigDecimal capDepreciationAmount(GenericValue bookDetail, BigDecimal amount) {
        BigDecimal max = netBookValueFromBookDetail(bookDetail).subtract(money(bookDetail.get("salvageValue"))).setScale(LEDGER_SCALE, ROUNDING);
        if (max.compareTo(ZERO) <= 0) {
            return ZERO;
        }
        return amount.compareTo(max) > 0 ? max : amount.max(ZERO).setScale(LEDGER_SCALE, ROUNDING);
    }

    private static BigDecimal computeNetBookValue(GenericValue bookDetail, BigDecimal accumulated) {
        BigDecimal capitalizedCost = money(bookDetail.get("capitalizedCost"));
        BigDecimal impairment = money(bookDetail.get("impairmentAmount"));
        BigDecimal revaluation = money(bookDetail.get("revaluationAmount"));
        return capitalizedCost.subtract(accumulated).subtract(impairment).add(revaluation).setScale(LEDGER_SCALE, ROUNDING).max(ZERO);
    }

    private static BigDecimal netBookValueFromBookDetail(GenericValue bookDetail) {
        BigDecimal nbv = optionalMoney(bookDetail.get("netBookValue"));
        if (nbv != null) {
            return nbv;
        }
        return computeNetBookValue(bookDetail, money(bookDetail.get("accumulatedDepreciation")));
    }

    private static BigDecimal depreciableBasis(Map<String, ? extends Object> context) throws GeneralAssetsException {
        BigDecimal basis = optionalMoney(context.get("depreciableBasis"));
        if (basis != null && basis.compareTo(ZERO) > 0) {
            return basis;
        }
        return money(context.get("capitalizedCost")).subtract(money(context.get("salvageValue"))).setScale(LEDGER_SCALE, ROUNDING).max(ZERO);
    }

    private static BigDecimal netBookValue(Map<String, ? extends Object> context) {
        BigDecimal nbv = optionalMoney(context.get("netBookValue"));
        if (nbv != null) {
            return nbv;
        }
        return money(context.get("capitalizedCost")).subtract(money(context.get("accumulatedDepreciation"))).setScale(LEDGER_SCALE, ROUNDING).max(ZERO);
    }

    private static BigDecimal firstDecimal(Map<String, Object> map, int scale, String... fieldNames) {
        for (String fieldName : fieldNames) {
            if (map.containsKey(fieldName) && map.get(fieldName) != null) {
                return decimal(map.get(fieldName), scale);
            }
        }
        return null;
    }

    private static BigDecimal money(Object value) {
        BigDecimal result = optionalMoney(value);
        return result == null ? ZERO : result.setScale(LEDGER_SCALE, ROUNDING);
    }

    private static BigDecimal optionalMoney(Object value) {
        return optionalDecimal(value, LEDGER_SCALE);
    }

    private static BigDecimal optionalRate(Object value) {
        return optionalDecimal(value, RATE_SCALE);
    }

    private static BigDecimal optionalQuantity(Object value) {
        return optionalDecimal(value, QUANTITY_SCALE);
    }

    private static BigDecimal decimal(Object value, int scale) {
        BigDecimal result = optionalDecimal(value, scale);
        return result == null ? BigDecimal.ZERO.setScale(scale, ROUNDING) : result;
    }

    private static BigDecimal optionalDecimal(Object value, int scale) {
        if (value == null) {
            return null;
        }
        if (value instanceof BigDecimal) {
            return ((BigDecimal) value).setScale(scale, ROUNDING);
        }
        if (value instanceof Number || value instanceof String) {
            String text = value.toString();
            if (UtilValidate.isEmpty(text)) {
                return null;
            }
            return new BigDecimal(text).setScale(scale, ROUNDING);
        }
        throw new IllegalArgumentException("Unsupported numeric value: " + value);
    }

    private static BigDecimal requiredPositiveMoney(Object value, String fieldName) throws GeneralAssetsException {
        BigDecimal result = optionalMoney(value);
        if (result == null || result.compareTo(ZERO) <= 0) {
            throw new GeneralAssetsException(fieldName + " must be greater than zero.");
        }
        return result;
    }

    private static BigDecimal requiredPositiveQuantity(Object value, String fieldName) throws GeneralAssetsException {
        BigDecimal result = optionalQuantity(value);
        if (result == null || result.compareTo(BigDecimal.ZERO) <= 0) {
            throw new GeneralAssetsException(fieldName + " must be greater than zero.");
        }
        return result;
    }

    private static int positiveInt(Object value, String fieldName) throws GeneralAssetsException {
        int result = positiveInt(value, -1);
        if (result <= 0) {
            throw new GeneralAssetsException(fieldName + " must be greater than zero.");
        }
        return result;
    }

    private static int positiveInt(Object value, int defaultValue) {
        if (value == null) {
            return defaultValue;
        }
        BigDecimal decimal = value instanceof BigDecimal ? (BigDecimal) value : new BigDecimal(value.toString());
        return decimal.intValue();
    }

    private static Date sqlDate(Object value) throws GeneralAssetsException {
        if (value == null) {
            return null;
        }
        if (value instanceof Date) {
            return (Date) value;
        }
        if (value instanceof Timestamp) {
            return new Date(((Timestamp) value).getTime());
        }
        if (value instanceof java.util.Date) {
            return new Date(((java.util.Date) value).getTime());
        }
        try {
            return UtilDateTime.toSqlDate(value.toString());
        } catch (RuntimeException e) {
            throw new GeneralAssetsException("Invalid date value [" + value + "]. Use the OFBiz date format yyyy-MM-dd.");
        }
    }

    private static Timestamp timestamp(Object value) throws GeneralAssetsException {
        if (value == null) {
            return null;
        }
        if (value instanceof Timestamp) {
            return (Timestamp) value;
        }
        if (value instanceof Date) {
            return toTimestampEnd((Date) value);
        }
        if (value instanceof java.util.Date) {
            return new Timestamp(((java.util.Date) value).getTime());
        }
        String text = value.toString();
        try {
            return Timestamp.valueOf(text);
        } catch (IllegalArgumentException e) {
            try {
                return UtilDateTime.toTimestamp(text);
            } catch (RuntimeException nested) {
                throw new GeneralAssetsException("Invalid timestamp value [" + value
                        + "]. Use an OFBiz timestamp such as yyyy-MM-dd HH:mm:ss.S.");
            }
        }
    }

    private static Timestamp toTimestampStart(Date date) {
        LocalDate localDate = date.toLocalDate();
        return Timestamp.valueOf(LocalDateTime.of(localDate, LocalTime.MIN));
    }

    private static Timestamp toTimestampEnd(Date date) {
        LocalDate localDate = date.toLocalDate();
        return Timestamp.valueOf(LocalDateTime.of(localDate, LocalTime.of(23, 59, 59)));
    }

    private static boolean isDryRun(Map<String, ? extends Object> context) {
        Object dryRun = context.get("dryRun");
        return dryRun == null || !"N".equalsIgnoreCase(dryRun.toString());
    }

    private static void putIfNotEmpty(Map<String, Object> map, String key, Object value) {
        if (UtilValidate.isNotEmpty(value)) {
            map.put(key, value);
        }
    }

    private static String stringValue(Object value) {
        return value == null ? null : value.toString();
    }

    private static String userLoginId(GenericValue userLogin) {
        return userLogin == null ? null : userLogin.getString("userLoginId");
    }

    private static Locale locale(Map<String, ? extends Object> context) {
        Object locale = context.get("locale");
        return locale instanceof Locale ? (Locale) locale : Locale.getDefault();
    }

    private static String message(String labelKey, Locale locale) {
        return UtilProperties.getMessage(RESOURCE, labelKey, locale);
    }

    private static final class PeriodWindow {
        private final String customTimePeriodId;
        private final Date periodStartDate;
        private final Date periodEndDate;

        private PeriodWindow(String customTimePeriodId, Date periodStartDate, Date periodEndDate) {
            this.customTimePeriodId = customTimePeriodId;
            this.periodStartDate = periodStartDate;
            this.periodEndDate = periodEndDate;
        }
    }

    private static final class FormulaResult {
        private final String serviceName;
        private final BigDecimal depreciationAmount;
        private final BigDecimal rateFactor;

        private FormulaResult(String serviceName, BigDecimal depreciationAmount, BigDecimal rateFactor) {
            this.serviceName = serviceName;
            this.depreciationAmount = depreciationAmount;
            this.rateFactor = rateFactor;
        }
    }

    private static final class ProcessingItem {
        private final boolean processed;
        private final Map<String, Object> resultMap;
        private final List<Map<String, Object>> proposedEntries;

        private ProcessingItem(boolean processed, Map<String, Object> resultMap, List<Map<String, Object>> proposedEntries) {
            this.processed = processed;
            this.resultMap = resultMap;
            this.proposedEntries = proposedEntries;
        }
    }

    private static final class GeneralAssetsException extends Exception {
        private static final long serialVersionUID = 1L;

        private GeneralAssetsException(String message) {
            super(message);
        }
    }
}

