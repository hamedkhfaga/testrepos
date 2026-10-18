package org.apache.ofbiz.assets.fixedasset;

import java.math.BigDecimal;
import java.sql.Date;
import java.sql.Timestamp;
import java.util.ArrayList;
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
import org.apache.ofbiz.entity.condition.EntityCondition;
import org.apache.ofbiz.entity.condition.EntityOperator;
import org.apache.ofbiz.entity.util.EntityQuery;
import org.apache.ofbiz.service.DispatchContext;
import org.apache.ofbiz.service.ServiceUtil;

/** Accounting-controlled lifecycle services for AssetsBook. */
public final class AssetsBookLifecycleServices {
    private static final String MODULE = AssetsBookLifecycleServices.class.getName();
    private static final String RESOURCE = "AssetsUiLabels";
    private static final String STATUS_ACTIVE = "ASSETS_ACTIVE";
    private static final String STATUS_IN_SERVICE = "ASSETS_IN_SERVICE";
    private static final String STATUS_INACTIVE = "ASSETS_INACTIVE";
    private static final String STATUS_PENDING = "ASSETS_PENDING";
    private static final String STATUS_APPROVED = "ASSETS_APPROVED";
    private static final String STATUS_CLOSED = "ASSETS_CLOSED";
    private static final String STATUS_CANCELLED = "ASSETS_CANCELLED";
    private static final String STATUS_REJECTED = "ASSETS_REJECTED";
    private static final String STATUS_PLANNED = "ASSETS_PLANNED";
    private static final String STATUS_POSTED = "ASSETS_POSTED";
    private static final List<String> DETAIL_ACTIVE_STATUSES = Arrays.asList(STATUS_ACTIVE, STATUS_IN_SERVICE);

    private AssetsBookLifecycleServices() { }

    public static Map<String, Object> assetsCreateBook(DispatchContext dctx, Map<String, ? extends Object> context) {
        Delegator delegator = dctx.getDelegator();
        Locale locale = locale(context);
        boolean dryRun = !"N".equals(stringValue(context.get("dryRun")));
        String assetsBookId = stringValue(context.get("assetsBookId"));
        try {
            if (UtilValidate.isEmpty(assetsBookId)) {
                throw new BookLifecycleException(message("AssetsErrorBookIdRequired", locale));
            }
            if (assetsBookId.length() > 20) {
                throw new BookLifecycleException(message("AssetsErrorBookIdLength", locale));
            }
            if (EntityQuery.use(delegator).from("AssetsBook").where("assetsBookId", assetsBookId).queryOne() != null) {
                throw new BookLifecycleException(message("AssetsErrorBookAlreadyExists", locale,
                        "assetsBookId", assetsBookId));
            }

            GenericValue userLogin = (GenericValue) context.get("userLogin");
            Timestamp now = UtilDateTime.nowTimestamp();
            GenericValue book = delegator.makeValue("AssetsBook");
            book.set("assetsBookId", assetsBookId);
            book.set("partyId", requiredString(context, "partyId", message("AssetsErrorBookPartyRequired", locale)));
            book.set("roleTypeId", requiredString(context, "roleTypeId", message("AssetsErrorBookRoleRequired", locale)));
            book.set("bookName", requiredString(context, "bookName", message("AssetsErrorBookNameRequired", locale)));
            if (UtilValidate.isNotEmpty(context.get("description"))) book.set("description", context.get("description"));
            book.set("bookTypeEnumId", requiredString(context, "bookTypeEnumId", message("AssetsErrorBookTypeRequired", locale)));
            book.set("statusId", stringOrDefault(context.get("statusId"), STATUS_PENDING));
            book.set("currencyUomId", requiredString(context, "currencyUomId", message("AssetsErrorBookCurrencyRequired", locale)));
            book.set("isPrimaryBook", indicator(context.get("isPrimaryBook"), "N", locale));
            book.set("isPostingBook", indicator(context.get("isPostingBook"), "N", locale));
            book.set("fromDate", requiredTimestamp(context.get("fromDate"), message("AssetsErrorBookFromDateRequired", locale)));
            if (context.get("thruDate") != null) book.set("thruDate", context.get("thruDate"));
            if (UtilValidate.isNotEmpty(context.get("comments"))) book.set("comments", context.get("comments"));
            book.set("createdDate", now);
            book.set("createdByUserLogin", userLoginId(userLogin));
            book.set("lastModifiedDate", now);
            book.set("lastModifiedByUserLogin", userLoginId(userLogin));
            validateBookDefinition(delegator, book, true, locale);
            if (!dryRun) book.create();

            Map<String, Object> result = ServiceUtil.returnSuccess(message(
                    dryRun ? "AssetsBookCreateDryRunSuccess" : "AssetsBookCreateSuccess", locale,
                    "assetsBookId", assetsBookId));
            result.put("assetsBook", book);
            result.put("dryRun", dryRun ? "Y" : "N");
            return result;
        } catch (BookLifecycleException | GenericEntityException e) {
            Debug.logError(e, "Unable to create asset book", MODULE);
            return ServiceUtil.returnError(e.getMessage());
        } catch (Exception e) {
            Debug.logError(e, "Unexpected asset book creation failure", MODULE);
            return ServiceUtil.returnError(message("AssetsErrorServiceUnexpectedFailure", locale) + " Detail: " + e.getMessage());
        }
    }

    public static Map<String, Object> assetsCloseBook(DispatchContext dctx, Map<String, ? extends Object> context) {
        Delegator delegator = dctx.getDelegator();
        Locale locale = locale(context);
        boolean dryRun = !"N".equals(stringValue(context.get("dryRun")));
        String assetsBookId = stringValue(context.get("assetsBookId"));
        try {
            GenericValue userLogin = (GenericValue) context.get("userLogin");
            GenericValue book = requireBook(delegator, assetsBookId, locale);
            Timestamp effectiveDate = requiredTimestamp(context.get("effectiveDate"),
                    message("AssetsErrorBookTransitionDateRequired", locale));
            if ("Y".equals(book.getString("isPrimaryBook"))) {
                throw new BookLifecycleException(message("AssetsErrorPrimaryBookRequiresTransition", locale));
            }
            validateCloseDate(book, effectiveDate, locale);
            ScheduleControl control = inspectFutureSchedules(delegator, assetsBookId, effectiveDate, dryRun, userLogin, locale);
            List<GenericValue> details = findEffectiveDetails(delegator, assetsBookId, effectiveDate);

            if (!dryRun) {
                for (GenericValue detail : details) closeDetail(detail, effectiveDate, context.get("comments"), userLogin);
                closeBookRecord(book, effectiveDate, context.get("comments"), userLogin);
            }
            Map<String, Object> result = ServiceUtil.returnSuccess(message(
                    dryRun ? "AssetsBookCloseDryRunSuccess" : "AssetsBookCloseSuccess", locale,
                    "assetsBookId", assetsBookId));
            result.put("closedAssignmentCount", details.size());
            result.put("cancelledScheduleCount", control.cancelledCount);
            result.put("dryRun", dryRun ? "Y" : "N");
            return result;
        } catch (BookLifecycleException | GenericEntityException e) {
            Debug.logError(e, "Unable to close asset book", MODULE);
            return ServiceUtil.returnError(e.getMessage());
        } catch (Exception e) {
            Debug.logError(e, "Unexpected asset book close failure", MODULE);
            return ServiceUtil.returnError(message("AssetsErrorServiceUnexpectedFailure", locale) + " Detail: " + e.getMessage());
        }
    }

    public static Map<String, Object> assetsTransitionPrimaryBook(DispatchContext dctx,
            Map<String, ? extends Object> context) {
        Delegator delegator = dctx.getDelegator();
        Locale locale = locale(context);
        boolean dryRun = !"N".equals(stringValue(context.get("dryRun")));
        String predecessorId = stringValue(context.get("predecessorAssetsBookId"));
        String successorId = stringValue(context.get("successorAssetsBookId"));
        try {
            if (UtilValidate.isEmpty(predecessorId) || UtilValidate.isEmpty(successorId) || predecessorId.equals(successorId)) {
                throw new BookLifecycleException(message("AssetsErrorBookTransitionPairRequired", locale));
            }
            GenericValue userLogin = (GenericValue) context.get("userLogin");
            GenericValue predecessor = requireBook(delegator, predecessorId, locale);
            GenericValue successor = requireBook(delegator, successorId, locale);
            Timestamp transitionDate = requiredTimestamp(context.get("transitionDate"),
                    message("AssetsErrorBookTransitionDateRequired", locale));

            validateTransitionBooks(delegator, predecessor, successor, transitionDate, dryRun, locale);
            ScheduleControl control = inspectFutureSchedules(delegator, predecessorId, transitionDate,
                    dryRun, userLogin, locale);
            List<GenericValue> predecessorDetails = findEffectiveDetails(delegator, predecessorId, transitionDate);
            int createdAssignments = 0;
            for (GenericValue oldDetail : predecessorDetails) {
                GenericValue successorDetail = findEffectiveDetail(delegator, successorId,
                        oldDetail.getString("fixedAssetId"), transitionDate);
                if (successorDetail == null) createdAssignments++;
            }

            if (!dryRun) {
                for (GenericValue oldDetail : predecessorDetails) {
                    GenericValue successorDetail = findEffectiveDetail(delegator, successorId,
                            oldDetail.getString("fixedAssetId"), transitionDate);
                    if (successorDetail == null) createSuccessorDetail(delegator, oldDetail, successorId,
                            transitionDate, context.get("comments"), userLogin);
                    closeDetail(oldDetail, transitionDate, context.get("comments"), userLogin);
                }
                closeBookRecord(predecessor, transitionDate, context.get("comments"), userLogin);
                successor.set("fromDate", transitionDate);
                successor.set("statusId", STATUS_ACTIVE);
                successor.set("isPrimaryBook", "Y");
                successor.set("isPostingBook", "Y");
                successor.set("lastModifiedDate", UtilDateTime.nowTimestamp());
                successor.set("lastModifiedByUserLogin", userLoginId(userLogin));
                successor.set("comments", appendComment(successor.getString("comments"), stringValue(context.get("comments"))));
                successor.store();
            }

            Map<String, Object> result = ServiceUtil.returnSuccess(message(
                    dryRun ? "AssetsBookTransitionDryRunSuccess" : "AssetsBookTransitionSuccess", locale,
                    "predecessorAssetsBookId", predecessorId, "successorAssetsBookId", successorId));
            result.put("migratedAssignmentCount", predecessorDetails.size());
            result.put("createdAssignmentCount", createdAssignments);
            result.put("cancelledScheduleCount", control.cancelledCount);
            result.put("transitionDate", transitionDate);
            result.put("dryRun", dryRun ? "Y" : "N");
            return result;
        } catch (BookLifecycleException | GenericEntityException e) {
            Debug.logError(e, "Unable to transition primary asset book", MODULE);
            return ServiceUtil.returnError(e.getMessage());
        } catch (Exception e) {
            Debug.logError(e, "Unexpected primary asset book transition failure", MODULE);
            return ServiceUtil.returnError(message("AssetsErrorServiceUnexpectedFailure", locale) + " Detail: " + e.getMessage());
        }
    }

    private static void validateBookDefinition(Delegator delegator, GenericValue book, boolean creating, Locale locale)
            throws GenericEntityException, BookLifecycleException {
        Timestamp fromDate = book.getTimestamp("fromDate");
        Timestamp thruDate = book.getTimestamp("thruDate");
        if (thruDate != null && !thruDate.after(fromDate)) {
            throw new BookLifecycleException(message("AssetsErrorBookDateRange", locale));
        }
        require(delegator, "Party", UtilMisc.toMap("partyId", book.getString("partyId")),
                message("AssetsErrorBookPartyNotFound", locale, "partyId", book.getString("partyId")));
        require(delegator, "PartyRole", UtilMisc.toMap("partyId", book.getString("partyId"),
                "roleTypeId", book.getString("roleTypeId")), message("AssetsErrorBookPartyRoleMissing", locale));
        GenericValue bookType = require(delegator, "Enumeration", UtilMisc.toMap("enumId", book.getString("bookTypeEnumId")),
                message("AssetsErrorBookTypeInvalid", locale));
        if (!"ASSETS_BOOK_TYPE".equals(bookType.getString("enumTypeId"))) {
            throw new BookLifecycleException(message("AssetsErrorBookTypeInvalid", locale));
        }
        GenericValue status = require(delegator, "StatusItem", UtilMisc.toMap("statusId", book.getString("statusId")),
                message("AssetsErrorBookStatusInvalid", locale));
        if (!"ASSETS_STATUS".equals(status.getString("statusTypeId"))
                || !Arrays.asList(STATUS_PENDING, STATUS_APPROVED, STATUS_ACTIVE).contains(book.getString("statusId"))) {
            throw new BookLifecycleException(message("AssetsErrorBookStatusInvalid", locale));
        }
        GenericValue currency = require(delegator, "Uom", UtilMisc.toMap("uomId", book.getString("currencyUomId")),
                message("AssetsErrorBookCurrencyInvalid", locale));
        if (!"CURRENCY_MEASURE".equals(currency.getString("uomTypeId"))) {
            throw new BookLifecycleException(message("AssetsErrorBookCurrencyInvalid", locale));
        }

        boolean primary = "Y".equals(book.getString("isPrimaryBook"));
        boolean posting = "Y".equals(book.getString("isPostingBook"));
        if (primary && !posting) throw new BookLifecycleException(message("AssetsErrorPrimaryBookMustPost", locale));
        if (!primary && posting) throw new BookLifecycleException(message("AssetsErrorSecondaryBookCannotPost", locale));
        if (primary || posting) validateBaseCurrency(delegator, book, locale);

        if (STATUS_ACTIVE.equals(book.getString("statusId"))) {
            Timestamp now = UtilDateTime.nowTimestamp();
            if (fromDate.after(now) || (thruDate != null && !thruDate.after(now))) {
                throw new BookLifecycleException(message("AssetsErrorActiveBookDate", locale));
            }
            if (primary) {
                List<GenericValue> primaryBooks = EntityQuery.use(delegator).from("AssetsBook")
                        .where("partyId", book.getString("partyId"), "isPrimaryBook", "Y").queryList();
                for (GenericValue existing : primaryBooks) {
                    if (!existing.getString("assetsBookId").equals(book.getString("assetsBookId"))
                            && !Arrays.asList(STATUS_CANCELLED, STATUS_REJECTED).contains(existing.getString("statusId"))) {
                        if (creating) throw new BookLifecycleException(message("AssetsErrorPrimaryBookUseTransition", locale,
                                "assetsBookId", existing.getString("assetsBookId")));
                    }
                }
            }
        }
    }

    private static void validateTransitionBooks(Delegator delegator, GenericValue predecessor, GenericValue successor,
            Timestamp transitionDate, boolean dryRun, Locale locale) throws GenericEntityException, BookLifecycleException {
        if (!STATUS_ACTIVE.equals(predecessor.getString("statusId"))
                || !"Y".equals(predecessor.getString("isPrimaryBook"))
                || !"Y".equals(predecessor.getString("isPostingBook"))) {
            throw new BookLifecycleException(message("AssetsErrorPredecessorNotActivePrimary", locale));
        }
        if (!Arrays.asList(STATUS_PENDING, STATUS_APPROVED).contains(successor.getString("statusId"))
                || !"Y".equals(successor.getString("isPrimaryBook"))
                || !"Y".equals(successor.getString("isPostingBook"))) {
            throw new BookLifecycleException(message("AssetsErrorSuccessorNotReady", locale));
        }
        if (!predecessor.getString("partyId").equals(successor.getString("partyId"))
                || !predecessor.getString("roleTypeId").equals(successor.getString("roleTypeId"))) {
            throw new BookLifecycleException(message("AssetsErrorBookTransitionPartyMismatch", locale));
        }
        if (!transitionDate.after(predecessor.getTimestamp("fromDate"))) {
            throw new BookLifecycleException(message("AssetsErrorBookTransitionAfterStart", locale));
        }
        Timestamp predecessorThru = predecessor.getTimestamp("thruDate");
        if (predecessorThru != null && predecessorThru.compareTo(transitionDate) != 0) {
            throw new BookLifecycleException(message("AssetsErrorBookTransitionBoundary", locale));
        }
        Timestamp successorFrom = successor.getTimestamp("fromDate");
        if (successorFrom != null && successorFrom.compareTo(transitionDate) != 0) {
            throw new BookLifecycleException(message("AssetsErrorBookTransitionBoundary", locale));
        }
        Timestamp successorThru = successor.getTimestamp("thruDate");
        if (successorThru != null && !successorThru.after(transitionDate)) {
            throw new BookLifecycleException(message("AssetsErrorBookDateRange", locale));
        }
        if (!dryRun && transitionDate.after(UtilDateTime.nowTimestamp())) {
            throw new BookLifecycleException(message("AssetsErrorFutureBookActivation", locale));
        }
        validateBaseCurrency(delegator, successor, locale);
        List<GenericValue> books = EntityQuery.use(delegator).from("AssetsBook")
                .where("partyId", predecessor.getString("partyId"), "isPrimaryBook", "Y", "statusId", STATUS_ACTIVE)
                .queryList();
        for (GenericValue book : books) {
            String id = book.getString("assetsBookId");
            if (!id.equals(predecessor.getString("assetsBookId")) && !id.equals(successor.getString("assetsBookId"))
                    && isEffective(book, transitionDate)) {
                throw new BookLifecycleException(message("AssetsErrorMultipleActivePrimaryBooks", locale,
                        "assetsBookId", id));
            }
        }
    }

    private static void validateBaseCurrency(Delegator delegator, GenericValue book, Locale locale)
            throws GenericEntityException, BookLifecycleException {
        GenericValue preference = EntityQuery.use(delegator).from("PartyAcctgPreference")
                .where("partyId", book.getString("partyId")).queryOne();
        if (preference == null) throw new BookLifecycleException(message("AssetsErrorBookAccountingPreferenceMissing", locale));
        if (!book.getString("currencyUomId").equals(preference.getString("baseCurrencyUomId"))) {
            throw new BookLifecycleException(message("AssetsErrorBookBaseCurrencyMismatch", locale,
                    "currencyUomId", book.getString("currencyUomId"),
                    "baseCurrencyUomId", preference.getString("baseCurrencyUomId")));
        }
    }

    private static void validateCloseDate(GenericValue book, Timestamp effectiveDate, Locale locale)
            throws BookLifecycleException {
        if (!Arrays.asList(STATUS_ACTIVE, STATUS_APPROVED).contains(book.getString("statusId"))) {
            throw new BookLifecycleException(message("AssetsErrorBookNotClosable", locale));
        }
        if (!effectiveDate.after(book.getTimestamp("fromDate"))) {
            throw new BookLifecycleException(message("AssetsErrorBookCloseAfterStart", locale));
        }
        Timestamp thruDate = book.getTimestamp("thruDate");
        if (thruDate != null && thruDate.compareTo(effectiveDate) != 0) {
            throw new BookLifecycleException(message("AssetsErrorBookTransitionBoundary", locale));
        }
    }

    private static ScheduleControl inspectFutureSchedules(Delegator delegator, String assetsBookId,
            Timestamp effectiveDate, boolean dryRun, GenericValue userLogin, Locale locale)
            throws GenericEntityException, BookLifecycleException {
        Date effectiveDay = new Date(effectiveDate.getTime());
        List<GenericValue> schedules = EntityQuery.use(delegator).from("AssetsBookDepSchedule")
                .where("assetsBookId", assetsBookId).queryList();
        int cancelled = 0;
        for (GenericValue schedule : schedules) {
            Date start = schedule.getDate("periodStartDate");
            Date end = schedule.getDate("periodEndDate");
            boolean onOrAfter = start != null && !start.before(effectiveDay);
            boolean crosses = start != null && end != null && start.before(effectiveDay) && !end.before(effectiveDay);
            if (STATUS_POSTED.equals(schedule.getString("statusId")) && (onOrAfter || crosses)) {
                throw new BookLifecycleException(message("AssetsErrorBookFuturePostedSchedule", locale,
                        "assetsBookDepScheduleId", schedule.getString("assetsBookDepScheduleId")));
            }
            if (crosses) {
                throw new BookLifecycleException(message("AssetsErrorBookTransitionPeriodBoundary", locale,
                        "assetsBookDepScheduleId", schedule.getString("assetsBookDepScheduleId")));
            }
            if (STATUS_PLANNED.equals(schedule.getString("statusId")) && onOrAfter) {
                cancelled++;
                if (!dryRun) {
                    schedule.set("statusId", STATUS_CANCELLED);
                    schedule.set("lastModifiedDate", UtilDateTime.nowTimestamp());
                    schedule.set("lastModifiedByUserLogin", userLoginId(userLogin));
                    schedule.store();
                }
            }
        }
        return new ScheduleControl(cancelled);
    }

    private static List<GenericValue> findEffectiveDetails(Delegator delegator, String assetsBookId,
            Timestamp effectiveDate) throws GenericEntityException {
        List<EntityCondition> conditions = new ArrayList<>();
        conditions.add(EntityCondition.makeCondition("assetsBookId", assetsBookId));
        conditions.add(EntityCondition.makeCondition("statusId", EntityOperator.IN, DETAIL_ACTIVE_STATUSES));
        conditions.add(EntityCondition.makeCondition("fromDate", EntityOperator.LESS_THAN_EQUAL_TO, effectiveDate));
        conditions.add(EntityCondition.makeCondition(Arrays.asList(
                EntityCondition.makeCondition("thruDate", null),
                EntityCondition.makeCondition("thruDate", EntityOperator.GREATER_THAN, effectiveDate)), EntityOperator.OR));
        return EntityQuery.use(delegator).from("AssetsBookDetail")
                .where(EntityCondition.makeCondition(conditions, EntityOperator.AND)).orderBy("fixedAssetId").queryList();
    }

    private static GenericValue findEffectiveDetail(Delegator delegator, String assetsBookId, String fixedAssetId,
            Timestamp effectiveDate) throws GenericEntityException {
        for (GenericValue detail : findEffectiveDetails(delegator, assetsBookId, effectiveDate)) {
            if (fixedAssetId.equals(detail.getString("fixedAssetId"))) return detail;
        }
        return null;
    }

    private static void createSuccessorDetail(Delegator delegator, GenericValue oldDetail, String successorId,
            Timestamp transitionDate, Object comments, GenericValue userLogin) throws GenericEntityException {
        Timestamp now = UtilDateTime.nowTimestamp();
        Map<String, Object> fields = new HashMap<>();
        fields.put("fixedAssetId", oldDetail.getString("fixedAssetId"));
        fields.put("assetsBookId", successorId);
        fields.put("fromDate", transitionDate);
        fields.put("statusId", oldDetail.getString("statusId"));
        for (String name : Arrays.asList("placedInServiceDate", "capitalizationDate", "originalCost", "capitalizedCost",
                "salvageValue", "depreciableBasis", "usefulLifeMonths", "remainingLifeMonths",
                "depreciationCustomMethodId", "depConventionEnumId", "prorationMethodEnumId", "productionCapacity",
                "productionCapacityUomId", "accumulatedDepreciation", "impairmentAmount", "revaluationAmount",
                "netBookValue", "lastDepreciationDate", "nextDepreciationDate")) {
            if (oldDetail.get(name) != null) fields.put(name, oldDetail.get(name));
        }
        fields.put("comments", appendComment(oldDetail.getString("comments"), stringValue(comments)));
        fields.put("createdDate", now);
        fields.put("createdByUserLogin", userLoginId(userLogin));
        fields.put("lastModifiedDate", now);
        fields.put("lastModifiedByUserLogin", userLoginId(userLogin));
        delegator.makeValue("AssetsBookDetail", fields).create();
    }

    private static void closeDetail(GenericValue detail, Timestamp effectiveDate, Object comments, GenericValue userLogin)
            throws GenericEntityException {
        detail.set("thruDate", effectiveDate);
        detail.set("statusId", STATUS_INACTIVE);
        detail.set("comments", appendComment(detail.getString("comments"), stringValue(comments)));
        detail.set("lastModifiedDate", UtilDateTime.nowTimestamp());
        detail.set("lastModifiedByUserLogin", userLoginId(userLogin));
        detail.store();
    }

    private static void closeBookRecord(GenericValue book, Timestamp effectiveDate, Object comments, GenericValue userLogin)
            throws GenericEntityException {
        book.set("thruDate", effectiveDate);
        book.set("statusId", STATUS_CLOSED);
        book.set("comments", appendComment(book.getString("comments"), stringValue(comments)));
        book.set("lastModifiedDate", UtilDateTime.nowTimestamp());
        book.set("lastModifiedByUserLogin", userLoginId(userLogin));
        book.store();
    }

    private static boolean isEffective(GenericValue value, Timestamp date) {
        Timestamp from = value.getTimestamp("fromDate");
        Timestamp thru = value.getTimestamp("thruDate");
        return (from == null || !from.after(date)) && (thru == null || thru.after(date));
    }

    private static GenericValue requireBook(Delegator delegator, String assetsBookId, Locale locale)
            throws GenericEntityException, BookLifecycleException {
        if (UtilValidate.isEmpty(assetsBookId)) throw new BookLifecycleException(message("AssetsErrorBookRequired", locale));
        return require(delegator, "AssetsBook", UtilMisc.toMap("assetsBookId", assetsBookId),
                message("AssetsErrorBookNotFound", locale).replace("${assetsBookId}", assetsBookId));
    }

    private static GenericValue require(Delegator delegator, String entityName, Map<String, ?> fields, String error)
            throws GenericEntityException, BookLifecycleException {
        GenericValue value = EntityQuery.use(delegator).from(entityName).where(fields).queryOne();
        if (value == null) throw new BookLifecycleException(error);
        return value;
    }

    private static String requiredString(Map<String, ? extends Object> context, String name, String error)
            throws BookLifecycleException {
        String value = stringValue(context.get(name));
        if (UtilValidate.isEmpty(value)) throw new BookLifecycleException(error);
        return value;
    }

    private static Timestamp requiredTimestamp(Object value, String error) throws BookLifecycleException {
        if (value instanceof Timestamp) return (Timestamp) value;
        throw new BookLifecycleException(error);
    }

    private static String indicator(Object value, String fallback, Locale locale) throws BookLifecycleException {
        String text = stringOrDefault(value, fallback);
        if (!"Y".equals(text) && !"N".equals(text)) {
            throw new BookLifecycleException(message("AssetsErrorIndicatorInvalid", locale));
        }
        return text;
    }

    private static String appendComment(String existing, String addition) {
        if (UtilValidate.isEmpty(addition)) return existing;
        return UtilValidate.isEmpty(existing) ? addition : existing + " | " + addition;
    }

    private static String stringOrDefault(Object value, String fallback) {
        String text = stringValue(value);
        return UtilValidate.isEmpty(text) ? fallback : text;
    }

    private static String stringValue(Object value) { return value == null ? null : value.toString(); }
    private static String userLoginId(GenericValue userLogin) { return userLogin == null ? null : userLogin.getString("userLoginId"); }
    private static Locale locale(Map<String, ? extends Object> context) {
        return context.get("locale") instanceof Locale ? (Locale) context.get("locale") : Locale.getDefault();
    }

    private static String message(String key, Locale locale, Object... pairs) {
        Map<String, Object> values = new HashMap<>();
        for (int i = 0; i + 1 < pairs.length; i += 2) values.put(String.valueOf(pairs[i]), pairs[i + 1]);
        return UtilProperties.getMessage(RESOURCE, key, values, locale);
    }

    private static final class ScheduleControl {
        private final int cancelledCount;
        private ScheduleControl(int cancelledCount) { this.cancelledCount = cancelledCount; }
    }

    private static final class BookLifecycleException extends Exception {
        private BookLifecycleException(String message) { super(message); }
    }
}

