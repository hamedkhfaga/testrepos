package org.apache.ofbiz.asseterp;

import org.apache.ofbiz.base.util.UtilDateTime;
import org.apache.ofbiz.base.util.UtilMisc;
import org.apache.ofbiz.base.util.UtilProperties;
import org.apache.ofbiz.base.util.UtilValidate;
import org.apache.ofbiz.entity.Delegator;
import org.apache.ofbiz.entity.GenericEntityException;
import org.apache.ofbiz.entity.GenericValue;
import org.apache.ofbiz.entity.util.EntityQuery;
import org.apache.ofbiz.security.Security;
import org.apache.ofbiz.service.DispatchContext;
import org.apache.ofbiz.service.GenericServiceException;
import org.apache.ofbiz.service.LocalDispatcher;
import org.apache.ofbiz.service.ServiceUtil;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.sql.Timestamp;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Asset ERP service implementations.
 *
 * Downstream OFBiz service calls use dctx.makeValidContext(serviceName, "IN", context)
 * to filter inputs dynamically — no hardcoded field lists, safe against entity model changes.
 * Direct entity writes (delegator.makeValue / setNonPKFields) are used where no OFBiz
 * service exists for the target entity.
 */
public final class AsseterpServices {

    private static final String RESOURCE = "AsseterpUiLabels";

    private AsseterpServices() {}

    // ================================================================
    // PERMISSION CHECK
    // ================================================================

    public static Map<String, Object> asseterpPermissionCheck(DispatchContext dctx,
            Map<String, ? extends Object> context) {
        Security security = dctx.getSecurity();
        GenericValue userLogin = (GenericValue) context.get("userLogin");
        String mainAction = (String) context.get("mainAction");
        if (mainAction == null) mainAction = "VIEW";

        String permissionId = "ASSETERP_" + mainAction;
        if (security.hasPermission("ASSETERP_ADMIN", userLogin)
                || security.hasPermission(permissionId, userLogin)) {
            Map<String, Object> result = ServiceUtil.returnSuccess();
            result.put("hasPermission", Boolean.TRUE);
            return result;
        }
        Map<String, Object> result = ServiceUtil.returnSuccess();
        result.put("hasPermission", Boolean.FALSE);
        result.put("failMessage", label(context, "AsseterpPermissionError",
                UtilMisc.toMap("mainAction", mainAction)));
        return result;
    }

    // ================================================================
    // LIFECYCLE AUDIT LOG
    // ================================================================

    public static Map<String, Object> asseterpCreateLifecycleEvent(DispatchContext dctx,
            Map<String, ? extends Object> context) {
        Delegator delegator = dctx.getDelegator();
        GenericValue userLogin = (GenericValue) context.get("userLogin");
        Timestamp nowTimestamp = UtilDateTime.nowTimestamp();
        String eventId = delegator.getNextSeqId("AsseterpLifecycleEvent");
        try {
            GenericValue event = delegator.makeValue("AsseterpLifecycleEvent");
            event.setNonPKFields(context);
            event.set("asseterpLifecycleEventId", eventId);
            event.set("eventDate", context.get("eventDate") != null
                    ? context.get("eventDate") : nowTimestamp);
            event.set("createdDate", nowTimestamp);
            if (userLogin != null) {
                event.set("createdByUserLogin", userLogin.getString("userLoginId"));
            }
            event.create();
        } catch (GenericEntityException e) {
            return ServiceUtil.returnError(e.getMessage());
        }
        Map<String, Object> result = ServiceUtil.returnSuccess();
        result.put("asseterpLifecycleEventId", eventId);
        return result;
    }

    // ================================================================
    // ORG CONFIGURATION
    // ================================================================

    public static Map<String, Object> asseterpCreateOrgConfig(DispatchContext dctx,
            Map<String, ? extends Object> context) {
        Delegator delegator = dctx.getDelegator();
        GenericValue userLogin = (GenericValue) context.get("userLogin");
        Timestamp now = UtilDateTime.nowTimestamp();
        String partyId = (String) context.get("partyId");
        try {
            GenericValue config = delegator.makeValue("AsseterpOrgConfig");
            config.setNonPKFields(context);
            config.set("partyId", partyId);
            config.set("isActive", "Y");
            config.set("createdDate", now);
            if (userLogin != null) {
                config.set("createdByUserLogin", userLogin.getString("userLoginId"));
            }
            delegator.createOrStore(config);
        } catch (GenericEntityException e) {
            return ServiceUtil.returnError(e.getMessage());
        }
        return ServiceUtil.returnSuccess();
    }

    public static Map<String, Object> asseterpUpdateOrgConfig(DispatchContext dctx,
            Map<String, ? extends Object> context) {
        Delegator delegator = dctx.getDelegator();
        GenericValue userLogin = (GenericValue) context.get("userLogin");
        Timestamp now = UtilDateTime.nowTimestamp();
        String partyId = (String) context.get("partyId");
        try {
            GenericValue config = delegator.findOne("AsseterpOrgConfig",
                    UtilMisc.toMap("partyId", partyId), false);
            if (config == null) {
                return ServiceUtil.returnError(label(context, "AsseterpOrgConfigNotFound",
                        UtilMisc.toMap("partyId", partyId)));
            }
            config.setNonPKFields(context);
            config.set("lastModifiedDate", now);
            if (userLogin != null) {
                config.set("lastModifiedByUserLogin", userLogin.getString("userLoginId"));
            }
            config.store();
        } catch (GenericEntityException e) {
            return ServiceUtil.returnError(e.getMessage());
        }
        return ServiceUtil.returnSuccess();
    }

    // ================================================================
    // BUDGET
    // ================================================================

    public static Map<String, Object> asseterpCreateBudget(DispatchContext dctx,
            Map<String, ? extends Object> context) {
        Delegator delegator = dctx.getDelegator();
        String budgetId = delegator.getNextSeqId("AsseterpBudget");
        try {
            GenericValue budget = delegator.makeValue("AsseterpBudget");
            budget.setNonPKFields(context);
            budget.set("asseterpBudgetId", budgetId);
            if (budget.get("statusId") == null) {
                budget.set("statusId", "ASSET_REQUESTED");
            }
            budget.create();
        } catch (GenericEntityException e) {
            return ServiceUtil.returnError(e.getMessage());
        }
        Map<String, Object> result = ServiceUtil.returnSuccess();
        result.put("asseterpBudgetId", budgetId);
        return result;
    }

    public static Map<String, Object> asseterpCreateBudgetItem(DispatchContext dctx,
            Map<String, ? extends Object> context) {
        Delegator delegator = dctx.getDelegator();
        String itemId = delegator.getNextSeqId("AsseterpBudgetItem");
        try {
            GenericValue item = delegator.makeValue("AsseterpBudgetItem");
            item.setNonPKFields(context);
            item.set("asseterpBudgetItemId", itemId);
            item.create();
        } catch (GenericEntityException e) {
            return ServiceUtil.returnError(e.getMessage());
        }
        Map<String, Object> result = ServiceUtil.returnSuccess();
        result.put("asseterpBudgetItemId", itemId);
        return result;
    }

    // ================================================================
    // ASSET REQUEST AND APPROVAL
    // ================================================================

    public static Map<String, Object> asseterpCreateAssetRequest(DispatchContext dctx,
            Map<String, ? extends Object> context) {
        Delegator delegator = dctx.getDelegator();
        LocalDispatcher dispatcher = dctx.getDispatcher();
        GenericValue userLogin = (GenericValue) context.get("userLogin");
        Timestamp now = UtilDateTime.nowTimestamp();
        String requestId = delegator.getNextSeqId("AsseterpAssetRequest");
        try {
            GenericValue request = delegator.makeValue("AsseterpAssetRequest");
            request.setNonPKFields(context);
            request.set("asseterpAssetRequestId", requestId);
            request.set("statusId", "ASSET_REQUESTED");
            request.set("requestDate", now);
            if (userLogin != null) {
                request.set("createdByUserLogin", userLogin.getString("userLoginId"));
            }
            request.create();

            // fixedAssetId must stay null here: no FixedAsset exists yet at request
            // time and the column has an FK to FixedAsset ("PENDING_x" would roll
            // back the whole transaction). The request link is sourceReferenceId.
            dispatcher.runSync("asseterpCreateLifecycleEvent", UtilMisc.toMap(
                    "partyId", context.get("partyId"),
                    "facilityId", context.get("facilityId"),
                    "statusId", "ASSET_REQUESTED",
                    "enumId", "AEVT_REQUESTED",
                    "eventDate", now,
                    "description", label(context, "AsseterpAssetRequestedEvent", null),
                    "serviceName", "asseterpCreateAssetRequest",
                    "sourceReferenceId", requestId,
                    "currencyUomId", context.get("currencyUomId"),
                    "amount", context.get("estimatedCost"),
                    "userLogin", userLogin));
        } catch (GenericEntityException | GenericServiceException e) {
            return ServiceUtil.returnError(e.getMessage());
        }
        Map<String, Object> result = ServiceUtil.returnSuccess();
        result.put("asseterpAssetRequestId", requestId);
        return result;
    }

    public static Map<String, Object> asseterpUpdateAssetRequest(DispatchContext dctx,
            Map<String, ? extends Object> context) {
        Delegator delegator = dctx.getDelegator();
        String requestId = (String) context.get("asseterpAssetRequestId");
        try {
            GenericValue request = delegator.findOne("AsseterpAssetRequest",
                    UtilMisc.toMap("asseterpAssetRequestId", requestId), false);
            if (request == null) {
                return ServiceUtil.returnError(label(context, "AsseterpAssetRequestNotFound",
                        UtilMisc.toMap("requestId", requestId)));
            }
            if (!"ASSET_REQUESTED".equals(request.getString("statusId"))) {
                return ServiceUtil.returnError(label(context, "AsseterpAssetRequestNotEditable", null));
            }
            request.setNonPKFields(context);
            request.set("asseterpAssetRequestId", requestId);
            request.store();
        } catch (GenericEntityException e) {
            return ServiceUtil.returnError(e.getMessage());
        }
        return ServiceUtil.returnSuccess();
    }

    public static Map<String, Object> asseterpCancelAssetRequest(DispatchContext dctx,
            Map<String, ? extends Object> context) {
        Delegator delegator = dctx.getDelegator();
        LocalDispatcher dispatcher = dctx.getDispatcher();
        GenericValue userLogin = (GenericValue) context.get("userLogin");
        String requestId = (String) context.get("asseterpAssetRequestId");
        try {
            GenericValue request = delegator.findOne("AsseterpAssetRequest",
                    UtilMisc.toMap("asseterpAssetRequestId", requestId), false);
            if (request == null) {
                return ServiceUtil.returnError(label(context, "AsseterpAssetRequestNotFound",
                        UtilMisc.toMap("requestId", requestId)));
            }
            request.set("statusId", "ASSET_CLOSED");
            request.store();

            // no fixedAssetId — FK to FixedAsset; the request is the sourceReferenceId
            dispatcher.runSync("asseterpCreateLifecycleEvent", UtilMisc.toMap(
                    "partyId", request.get("partyId"),
                    "statusId", "ASSET_CLOSED",
                    "enumId", "AEVT_CANCELLED",
                    "eventDate", UtilDateTime.nowTimestamp(),
                    "description", context.get("comments") != null
                            ? context.get("comments")
                            : label(context, "AsseterpAssetRequestCancelledEvent", null),
                    "serviceName", "asseterpCancelAssetRequest",
                    "sourceReferenceId", requestId,
                    "userLogin", userLogin));
        } catch (GenericEntityException | GenericServiceException e) {
            return ServiceUtil.returnError(e.getMessage());
        }
        return ServiceUtil.returnSuccess();
    }

    public static Map<String, Object> asseterpApproveAssetRequest(DispatchContext dctx,
            Map<String, ? extends Object> context) {
        Delegator delegator = dctx.getDelegator();
        LocalDispatcher dispatcher = dctx.getDispatcher();
        GenericValue userLogin = (GenericValue) context.get("userLogin");
        String requestId = (String) context.get("asseterpAssetRequestId");
        Long approvalLevel = (Long) context.get("approvalLevel");
        String decidedByPartyId = (String) context.get("decidedByPartyId");
        Timestamp now = UtilDateTime.nowTimestamp();
        try {
            GenericValue request = delegator.findOne("AsseterpAssetRequest",
                    UtilMisc.toMap("asseterpAssetRequestId", requestId), false);
            if (request == null) {
                return ServiceUtil.returnError(label(context, "AsseterpAssetRequestNotFound",
                        UtilMisc.toMap("requestId", requestId)));
            }

            String recordId = delegator.getNextSeqId("AsseterpApprovalRecord");
            GenericValue record = delegator.makeValue("AsseterpApprovalRecord");
            record.set("asseterpApprovalRecordId", recordId);
            record.set("asseterpAssetRequestId", requestId);
            record.set("approvalLevel", approvalLevel);
            record.set("decision", "APPROVED");
            record.set("decidedByPartyId", decidedByPartyId);
            record.set("decisionDate", now);
            record.set("comments", context.get("comments"));
            record.create();

            List<GenericValue> matrixRows = delegator.findByAnd("AsseterpApprovalMatrix",
                    UtilMisc.toMap("partyId", request.getString("partyId"),
                            "fixedAssetTypeId", request.getString("fixedAssetTypeId"),
                            "isActive", "Y"), null, false);

            List<GenericValue> approvedRecords = delegator.findByAnd("AsseterpApprovalRecord",
                    UtilMisc.toMap("asseterpAssetRequestId", requestId,
                            "decision", "APPROVED"), null, false);

            boolean fullyApproved = matrixRows.isEmpty()
                    || approvedRecords.size() >= matrixRows.size();

            if (fullyApproved) {
                request.set("statusId", "ASSET_APPROVED");
                request.store();

                // no fixedAssetId — FK to FixedAsset; the request is the sourceReferenceId
                dispatcher.runSync("asseterpCreateLifecycleEvent", UtilMisc.toMap(
                        "partyId", request.get("partyId"),
                        "statusId", "ASSET_APPROVED",
                        "enumId", "AEVT_APPROVED",
                        "eventDate", now,
                        "description", label(context, "AsseterpAssetApprovedEvent", null),
                        "serviceName", "asseterpApproveAssetRequest",
                        "sourceReferenceId", requestId,
                        "userLogin", userLogin));
            }

            Map<String, Object> result = ServiceUtil.returnSuccess();
            result.put("fullyApproved", fullyApproved);
            return result;
        } catch (GenericEntityException | GenericServiceException e) {
            return ServiceUtil.returnError(e.getMessage());
        }
    }

    public static Map<String, Object> asseterpRejectAssetRequest(DispatchContext dctx,
            Map<String, ? extends Object> context) {
        Delegator delegator = dctx.getDelegator();
        LocalDispatcher dispatcher = dctx.getDispatcher();
        GenericValue userLogin = (GenericValue) context.get("userLogin");
        String requestId = (String) context.get("asseterpAssetRequestId");
        Long approvalLevel = (Long) context.get("approvalLevel");
        Timestamp now = UtilDateTime.nowTimestamp();
        try {
            GenericValue request = delegator.findOne("AsseterpAssetRequest",
                    UtilMisc.toMap("asseterpAssetRequestId", requestId), false);
            if (request == null) {
                return ServiceUtil.returnError(label(context, "AsseterpAssetRequestNotFound",
                        UtilMisc.toMap("requestId", requestId)));
            }

            String recordId = delegator.getNextSeqId("AsseterpApprovalRecord");
            GenericValue record = delegator.makeValue("AsseterpApprovalRecord");
            record.set("asseterpApprovalRecordId", recordId);
            record.set("asseterpAssetRequestId", requestId);
            record.set("approvalLevel", approvalLevel);
            record.set("decision", "REJECTED");
            record.set("decidedByPartyId", context.get("decidedByPartyId"));
            record.set("decisionDate", now);
            record.set("comments", context.get("comments"));
            record.create();

            request.set("statusId", "ASSET_REJECTED");
            request.store();

            // no fixedAssetId — FK to FixedAsset; the request is the sourceReferenceId
            dispatcher.runSync("asseterpCreateLifecycleEvent", UtilMisc.toMap(
                    "partyId", request.get("partyId"),
                    "statusId", "ASSET_REJECTED",
                    "enumId", "AEVT_REJECTED",
                    "eventDate", now,
                    "description", label(context, "AsseterpAssetRejectedEvent", null),
                    "serviceName", "asseterpRejectAssetRequest",
                    "sourceReferenceId", requestId,
                    "userLogin", userLogin));
        } catch (GenericEntityException | GenericServiceException e) {
            return ServiceUtil.returnError(e.getMessage());
        }
        return ServiceUtil.returnSuccess();
    }

    // ================================================================
    // ASSET REGISTRATION
    // ================================================================

    public static Map<String, Object> asseterpRegisterAsset(DispatchContext dctx,
            Map<String, ? extends Object> context) {
        LocalDispatcher dispatcher = dctx.getDispatcher();
        Delegator delegator = dctx.getDelegator();
        GenericValue userLogin = (GenericValue) context.get("userLogin");
        Timestamp nowTimestamp = UtilDateTime.nowTimestamp();
        try {
            // makeValidContext filters to exactly what createFixedAsset declares — dynamic and safe
            Map<String, Object> createCtx = dctx.makeValidContext("createFixedAsset", "IN", context);
            createCtx.put("userLogin", userLogin);

            Map<String, Object> createResult = dispatcher.runSync("createFixedAsset", createCtx);
            if (ServiceUtil.isError(createResult)) return createResult;

            String fixedAssetId = (String) (createResult.get("fixedAssetId") != null
                    ? createResult.get("fixedAssetId") : context.get("fixedAssetId"));

            if (UtilValidate.isNotEmpty((String) context.get("fixedAssetIdentTypeId"))
                    && UtilValidate.isNotEmpty((String) context.get("idValue"))) {
                Map<String, Object> identResult = dispatcher.runSync("createFixedAssetIdent",
                        UtilMisc.toMap(
                                "fixedAssetId", fixedAssetId,
                                "fixedAssetIdentTypeId", context.get("fixedAssetIdentTypeId"),
                                "idValue", context.get("idValue"),
                                "userLogin", userLogin));
                if (ServiceUtil.isError(identResult)) return identResult;
            }

            if (UtilValidate.isNotEmpty((String) context.get("custodianPartyId"))) {
                Map<String, Object> assignResult = dispatcher.runSync("asseterpAssignAssetCustody",
                        UtilMisc.toMap(
                                "fixedAssetId", fixedAssetId,
                                "partyId", context.get("partyId"),
                                "custodianPartyId", context.get("custodianPartyId"),
                                "facilityId", context.get("facilityId"),
                                "roleTypeId", "ASSET_CUSTODIAN",
                                "fromDate", nowTimestamp,
                                "userLogin", userLogin));
                if (ServiceUtil.isError(assignResult)) return assignResult;
            }

            // Link back to the originating request if provided
            String requestId = (String) context.get("asseterpAssetRequestId");
            if (UtilValidate.isNotEmpty(requestId)) {
                try {
                    GenericValue request = delegator.findOne("AsseterpAssetRequest",
                            UtilMisc.toMap("asseterpAssetRequestId", requestId), false);
                    if (request != null) {
                        request.set("statusId", "ASSET_RECEIVED");
                        request.store();
                    }
                } catch (GenericEntityException ignored) {}
            }

            // Auto-link to the OFBiz standard Straight-Line depreciation method so that
            // the accounting/ListFixedAssets screen (calculateFixedAssetDepreciation) never
            // throws "Depreciation method not found" for a freshly registered asset.
            // PK = (depreciationCustomMethodId, fixedAssetId) — upsert-safe.
            try {
                Map<String, Object> depMethodCtx = UtilMisc.toMap(
                        "depreciationCustomMethodId", "STR_LINE_DEP_FORMULA",
                        "fixedAssetId", fixedAssetId,
                        "fromDate", nowTimestamp,
                        "userLogin", userLogin);
                // Use createOrStore so re-registering the same asset is idempotent.
                GenericValue existing = delegator.findOne("FixedAssetDepMethod",
                        UtilMisc.toMap("depreciationCustomMethodId", "STR_LINE_DEP_FORMULA",
                                       "fixedAssetId", fixedAssetId), false);
                if (existing == null) {
                    dispatcher.runSync("createFixedAssetDepMethod", depMethodCtx);
                }
            } catch (Exception ignored) {
                // Non-fatal — the asseterp engine uses its own method records; this is only
                // needed for the OFBiz standard accounting screen to render without error.
            }

            Map<String, Object> lifecycleResult = dispatcher.runSync("asseterpCreateLifecycleEvent",
                    UtilMisc.toMap(
                            "fixedAssetId", fixedAssetId,
                            "partyId", context.get("partyId"),
                            "facilityId", context.get("facilityId"),
                            "statusId", "ASSET_REGISTERED",
                            "enumId", "AEVT_REGISTERED",
                            "eventDate", nowTimestamp,
                            "description", label(context, "AsseterpAssetRegisteredEvent", null),
                            "serviceName", "asseterpRegisterAsset",
                            "sourceReferenceId", requestId,
                            "currencyUomId", context.get("purchaseCostUomId"),
                            "amount", context.get("purchaseCost"),
                            "userLogin", userLogin));
            if (ServiceUtil.isError(lifecycleResult)) return lifecycleResult;

            Map<String, Object> result = ServiceUtil.returnSuccess();
            result.put("fixedAssetId", fixedAssetId);
            return result;
        } catch (GenericServiceException e) {
            return ServiceUtil.returnError(e.getMessage());
        }
    }

    // ================================================================
    // DEPRECIATION BOOK
    // ================================================================

    public static Map<String, Object> asseterpCreateAssetBook(DispatchContext dctx,
            Map<String, ? extends Object> context) {
        Delegator delegator = dctx.getDelegator();
        LocalDispatcher dispatcher = dctx.getDispatcher();
        GenericValue userLogin = (GenericValue) context.get("userLogin");
        Timestamp now = UtilDateTime.nowTimestamp();
        String bookId = delegator.getNextSeqId("AsseterpAssetBook");
        try {
            // One active book per book type per asset — a second active book of the
            // same type would double-depreciate. Deactivate the old one first.
            String reqFixedAssetId = (String) context.get("fixedAssetId");
            String reqBookTypeId   = (String) context.get("bookTypeId");
            List<GenericValue> activeSame = EntityQuery.use(delegator)
                    .from("AsseterpAssetBook")
                    .where("fixedAssetId", reqFixedAssetId, "bookTypeId", reqBookTypeId)
                    .filterByDate()
                    .queryList();
            if (UtilValidate.isNotEmpty(activeSame)) {
                return ServiceUtil.returnError("Asset " + reqFixedAssetId
                        + " already has an active " + reqBookTypeId + " book ("
                        + activeSame.get(0).getString("asseterpAssetBookId")
                        + "). Deactivate it first (asseterpDeactivateAssetBook) before creating a new one.");
            }

            GenericValue book = delegator.makeValue("AsseterpAssetBook");
            book.setNonPKFields(context);
            book.set("asseterpAssetBookId", bookId);
            book.set("fromDate", now);

            // Seed accumulatedDepreciation from FixedAsset.depreciation when not supplied,
            // so the book starts in sync with what has already been posted historically.
            if (book.get("accumulatedDepreciation") == null) {
                String fixedAssetId = (String) context.get("fixedAssetId");
                BigDecimal existingDep = BigDecimal.ZERO;
                if (fixedAssetId != null) {
                    GenericValue fa = delegator.findOne("FixedAsset",
                            UtilMisc.toMap("fixedAssetId", fixedAssetId), true);
                    if (fa != null && fa.getBigDecimal("depreciation") != null) {
                        existingDep = fa.getBigDecimal("depreciation");
                    }
                }
                book.set("accumulatedDepreciation", existingDep);
                // Also initialise netBookValue from purchaseCost - existingDep
                if (book.get("netBookValue") == null) {
                    GenericValue fa2 = (fixedAssetId != null)
                            ? delegator.findOne("FixedAsset", UtilMisc.toMap("fixedAssetId", fixedAssetId), true)
                            : null;
                    BigDecimal cost = (fa2 != null && fa2.getBigDecimal("purchaseCost") != null)
                            ? fa2.getBigDecimal("purchaseCost") : BigDecimal.ZERO;
                    book.set("netBookValue", cost.subtract(existingDep));
                }
            }
            book.create();

            // Auto-create FixedAssetDepMethod so the OFBiz calculation engine can
            // dispatch to the correct Groovy service without manual setup.
            String depMethodId = (String) context.get("depreciationMethodId");
            String fixedAssetIdForBook = (String) context.get("fixedAssetId");
            if (depMethodId != null && fixedAssetIdForBook != null) {
                String customMethodId = resolveCustomMethodId(delegator, depMethodId);
                if (customMethodId != null) {
                    upsertFixedAssetDepMethod(delegator, fixedAssetIdForBook, customMethodId, now);
                }
            }

            dispatcher.runSync("asseterpCreateLifecycleEvent", UtilMisc.toMap(
                    "fixedAssetId", context.get("fixedAssetId"),
                    "enumId", "AEVT_BOOK_CREATED",
                    "eventDate", now,
                    "description", label(context, "AsseterpAssetBookCreatedEvent", null),
                    "serviceName", "asseterpCreateAssetBook",
                    "sourceReferenceId", bookId,
                    "userLogin", userLogin));
        } catch (GenericEntityException | GenericServiceException e) {
            return ServiceUtil.returnError(e.getMessage());
        }
        Map<String, Object> result = ServiceUtil.returnSuccess();
        result.put("asseterpAssetBookId", bookId);
        return result;
    }

    public static Map<String, Object> asseterpUpdateAssetBook(DispatchContext dctx,
            Map<String, ? extends Object> context) {
        Delegator delegator = dctx.getDelegator();
        LocalDispatcher dispatcher = dctx.getDispatcher();
        GenericValue userLogin = (GenericValue) context.get("userLogin");
        String bookId = (String) context.get("asseterpAssetBookId");
        Timestamp now = UtilDateTime.nowTimestamp();
        try {
            GenericValue book = delegator.findOne("AsseterpAssetBook",
                    UtilMisc.toMap("asseterpAssetBookId", bookId), false);
            if (book == null) {
                return ServiceUtil.returnError(label(context, "AsseterpAssetBookNotFound",
                        UtilMisc.toMap("bookId", bookId)));
            }

            // Record the change per IAS 8 before applying
            String changeId = delegator.getNextSeqId("AsseterpEstimateChange");
            GenericValue change = delegator.makeValue("AsseterpEstimateChange");
            change.set("asseterpEstimateChangeId", changeId);
            change.set("fixedAssetId", book.getString("fixedAssetId"));
            change.set("asseterpAssetBookId", bookId);
            change.set("changeDate", now);
            change.set("changeTypeId", "DEP_ESTIMATE");
            change.set("reason", context.get("reason"));
            change.set("approvedByPartyId", context.get("approvedByPartyId"));

            StringBuilder oldVal = new StringBuilder();
            StringBuilder newVal = new StringBuilder();
            if (context.get("usefulLifeYears") != null) {
                oldVal.append("usefulLifeYears=").append(book.get("usefulLifeYears"));
                newVal.append("usefulLifeYears=").append(context.get("usefulLifeYears"));
                book.set("usefulLifeYears", context.get("usefulLifeYears"));
            }
            if (context.get("salvageValue") != null) {
                oldVal.append(" salvageValue=").append(book.get("salvageValue"));
                newVal.append(" salvageValue=").append(context.get("salvageValue"));
                book.set("salvageValue", context.get("salvageValue"));
            }
            if (context.get("depreciationMethodId") != null) {
                oldVal.append(" method=").append(book.get("depreciationMethodId"));
                newVal.append(" method=").append(context.get("depreciationMethodId"));
                book.set("depreciationMethodId", context.get("depreciationMethodId"));
                // Keep FixedAssetDepMethod in sync so the calculation engine uses the new method
                String newCustomMethodId = resolveCustomMethodId(delegator,
                        (String) context.get("depreciationMethodId"));
                if (newCustomMethodId != null && book.getString("fixedAssetId") != null) {
                    upsertFixedAssetDepMethod(delegator, book.getString("fixedAssetId"),
                            newCustomMethodId, now);
                }
            }
            change.set("oldValue", oldVal.toString());
            change.set("newValue", newVal.toString());
            change.create();
            book.store();

            dispatcher.runSync("asseterpCreateLifecycleEvent", UtilMisc.toMap(
                    "fixedAssetId", book.getString("fixedAssetId"),
                    "enumId", "AEVT_BOOK_CHANGED",
                    "eventDate", now,
                    "description", label(context, "AsseterpAssetBookChangedEvent", null),
                    "serviceName", "asseterpUpdateAssetBook",
                    "sourceReferenceId", bookId,
                    "userLogin", userLogin));
        } catch (GenericEntityException | GenericServiceException e) {
            return ServiceUtil.returnError(e.getMessage());
        }
        return ServiceUtil.returnSuccess();
    }

    /**
     * One-time sync: for every AsseterpAssetBook where accumulatedDepreciation is
     * zero (or null) but the underlying FixedAsset already has depreciation posted,
     * copy FixedAsset.depreciation → book.accumulatedDepreciation and recompute
     * book.netBookValue = purchaseCost - accumulatedDepreciation.
     *
     * Safe to re-run — only updates books where book value = 0 AND asset value > 0.
     * Optionally scoped to a single fixedAssetId.
     */
    public static Map<String, Object> asseterpSyncBookBalances(DispatchContext dctx,
            Map<String, ? extends Object> context) {
        Delegator delegator = dctx.getDelegator();
        String scopeAssetId = (String) context.get("fixedAssetId");
        long updatedCount = 0L;
        try {
            List<GenericValue> books;
            if (scopeAssetId != null) {
                books = delegator.findByAnd("AsseterpAssetBook",
                        UtilMisc.toMap("fixedAssetId", scopeAssetId), null, false);
            } else {
                books = EntityQuery.use(delegator).from("AsseterpAssetBook").queryList();
            }
            for (GenericValue book : books) {
                BigDecimal bookAccum = book.getBigDecimal("accumulatedDepreciation");
                // Only sync books that have zero/null accumulated dep
                if (bookAccum != null && bookAccum.compareTo(BigDecimal.ZERO) > 0) continue;

                String faid = book.getString("fixedAssetId");
                GenericValue fa = delegator.findOne("FixedAsset",
                        UtilMisc.toMap("fixedAssetId", faid), true);
                if (fa == null) continue;

                BigDecimal assetDep  = fa.getBigDecimal("depreciation");
                BigDecimal assetCost = fa.getBigDecimal("purchaseCost");
                if (assetDep == null || assetDep.compareTo(BigDecimal.ZERO) == 0) continue;

                // Copy asset depreciation into the book
                book.set("accumulatedDepreciation", assetDep);
                BigDecimal cost = assetCost != null ? assetCost : BigDecimal.ZERO;
                book.set("netBookValue", cost.subtract(assetDep));
                book.store();
                updatedCount++;
            }
        } catch (GenericEntityException e) {
            return ServiceUtil.returnError(e.getMessage());
        }
        Map<String, Object> result = ServiceUtil.returnSuccess();
        result.put("updatedCount", updatedCount);
        return result;
    }

    public static Map<String, Object> asseterpDeactivateAssetBook(DispatchContext dctx,
            Map<String, ? extends Object> context) {
        Delegator delegator = dctx.getDelegator();
        String bookId = (String) context.get("asseterpAssetBookId");
        try {
            GenericValue book = delegator.findOne("AsseterpAssetBook",
                    UtilMisc.toMap("asseterpAssetBookId", bookId), false);
            if (book == null) return ServiceUtil.returnError("Book not found: " + bookId);
            book.set("thruDate", UtilDateTime.nowTimestamp());
            book.store();
        } catch (GenericEntityException e) {
            return ServiceUtil.returnError(e.getMessage());
        }
        return ServiceUtil.returnSuccess();
    }

    // ================================================================
    // CUSTODY AND TRANSFER
    // ================================================================

    public static Map<String, Object> asseterpAssignAssetCustody(DispatchContext dctx,
            Map<String, ? extends Object> context) {
        Delegator delegator = dctx.getDelegator();
        LocalDispatcher dispatcher = dctx.getDispatcher();
        GenericValue userLogin = (GenericValue) context.get("userLogin");
        Timestamp fromDate = context.get("fromDate") != null
                ? (Timestamp) context.get("fromDate") : UtilDateTime.nowTimestamp();
        try {
            // Bridge: custodianPartyId maps to partyId in createPartyFixedAssetAssignment
            Map<String, Object> bridge = new HashMap<>(context);
            bridge.put("partyId", context.get("custodianPartyId"));
            bridge.put("allocatedDate", fromDate);

            Map<String, Object> assignCtx = dctx.makeValidContext(
                    "createPartyFixedAssetAssignment", "IN", bridge);
            assignCtx.put("userLogin", userLogin);
            assignCtx.put("fromDate", fromDate);

            Map<String, Object> assignResult = dispatcher.runSync(
                    "createPartyFixedAssetAssignment", assignCtx);
            if (ServiceUtil.isError(assignResult)) return assignResult;

            // Write internal custody record
            String custodyId = delegator.getNextSeqId("AsseterpCustodyRecord");
            GenericValue custodyRecord = delegator.makeValue("AsseterpCustodyRecord");
            custodyRecord.set("asseterpCustodyRecordId", custodyId);
            custodyRecord.set("fixedAssetId", context.get("fixedAssetId"));
            custodyRecord.set("custodianPartyId", context.get("custodianPartyId"));
            custodyRecord.set("facilityId", context.get("facilityId"));
            custodyRecord.set("assignedDate", fromDate);
            custodyRecord.set("notes", context.get("notes"));
            if (userLogin != null) {
                custodyRecord.set("assignedByPartyId", userLogin.getString("partyId"));
            }
            custodyRecord.create();

            dispatcher.runSync("asseterpCreateLifecycleEvent", UtilMisc.toMap(
                    "fixedAssetId", context.get("fixedAssetId"),
                    "partyId", context.get("partyId"),
                    "facilityId", context.get("facilityId"),
                    "statusId", "ASSET_ASSIGNED",
                    "enumId", "AEVT_ASSIGNED",
                    "eventDate", fromDate,
                    "description", label(context, "AsseterpAssetAssignedEvent", null),
                    "serviceName", "asseterpAssignAssetCustody",
                    "sourceReferenceId", custodyId,
                    "userLogin", userLogin));
            return ServiceUtil.returnSuccess();
        } catch (GenericEntityException | GenericServiceException e) {
            return ServiceUtil.returnError(e.getMessage());
        }
    }

    public static Map<String, Object> asseterpTransferAsset(DispatchContext dctx,
            Map<String, ? extends Object> context) {
        Delegator delegator = dctx.getDelegator();
        LocalDispatcher dispatcher = dctx.getDispatcher();
        GenericValue userLogin = (GenericValue) context.get("userLogin");
        String fixedAssetId = (String) context.get("fixedAssetId");
        Timestamp transferDate = context.get("transferDate") != null
                ? (Timestamp) context.get("transferDate") : UtilDateTime.nowTimestamp();
        try {
            // Close the current active custody record
            List<GenericValue> openCustody = delegator.findByAnd("AsseterpCustodyRecord",
                    UtilMisc.toMap("fixedAssetId", fixedAssetId), null, false);
            for (GenericValue cr : openCustody) {
                if (cr.get("returnDate") == null) {
                    cr.set("returnDate", transferDate);
                    cr.store();
                }
            }

            // Close OFBiz PartyFixedAssetAssignment
            List<GenericValue> activeAssignments = delegator.findByAnd(
                    "PartyFixedAssetAssignment",
                    UtilMisc.toMap("fixedAssetId", fixedAssetId), null, false);
            for (GenericValue assignment : activeAssignments) {
                if (assignment.get("thruDate") == null) {
                    Map<String, Object> updCtx = dctx.makeValidContext(
                            "updatePartyFixedAssetAssignment", "IN", assignment);
                    updCtx.put("thruDate", transferDate);
                    updCtx.put("userLogin", userLogin);
                    Map<String, Object> updResult = dispatcher.runSync(
                            "updatePartyFixedAssetAssignment", updCtx);
                    if (ServiceUtil.isError(updResult)) return updResult;
                }
            }

            // Open new custody for the new custodian
            Map<String, Object> assignResult = dispatcher.runSync("asseterpAssignAssetCustody",
                    UtilMisc.toMap(
                            "fixedAssetId", fixedAssetId,
                            "partyId", context.get("partyId"),
                            "custodianPartyId", context.get("newCustodianPartyId"),
                            "facilityId", context.get("newFacilityId"),
                            "roleTypeId", "ASSET_CUSTODIAN",
                            "fromDate", transferDate,
                            "notes", context.get("notes"),
                            "userLogin", userLogin));
            if (ServiceUtil.isError(assignResult)) return assignResult;

            dispatcher.runSync("asseterpCreateLifecycleEvent", UtilMisc.toMap(
                    "fixedAssetId", fixedAssetId,
                    "partyId", context.get("partyId"),
                    "facilityId", context.get("newFacilityId"),
                    "statusId", "ASSET_TRANSFERRED",
                    "enumId", "AEVT_TRANSFERRED",
                    "eventDate", transferDate,
                    "description", label(context, "AsseterpAssetTransferredEvent", null),
                    "serviceName", "asseterpTransferAsset",
                    "userLogin", userLogin));
            return ServiceUtil.returnSuccess();
        } catch (GenericEntityException | GenericServiceException e) {
            return ServiceUtil.returnError(e.getMessage());
        }
    }

    // ================================================================
    // MAINTENANCE
    // ================================================================

    public static Map<String, Object> asseterpCreateMaintenanceWorkOrder(DispatchContext dctx,
            Map<String, ? extends Object> context) {
        LocalDispatcher dispatcher = dctx.getDispatcher();
        GenericValue userLogin = (GenericValue) context.get("userLogin");
        Timestamp nowTimestamp = UtilDateTime.nowTimestamp();
        try {
            // makeValidContext passes only fields createFixedAssetMaint declares
            Map<String, Object> maintCtx = dctx.makeValidContext(
                    "createFixedAssetMaint", "IN", context);
            maintCtx.put("userLogin", userLogin);
            if (maintCtx.get("statusId") == null) {
                maintCtx.put("statusId", "FAM_CREATED");
            }

            Map<String, Object> maintResult = dispatcher.runSync("createFixedAssetMaint", maintCtx);
            if (ServiceUtil.isError(maintResult)) return maintResult;

            dispatcher.runSync("asseterpCreateLifecycleEvent", UtilMisc.toMap(
                    "fixedAssetId", context.get("fixedAssetId"),
                    "partyId", context.get("partyId"),
                    "statusId", "ASSET_IN_MAINT",
                    "enumId", "AEVT_MAINT_CREATED",
                    "eventDate", nowTimestamp,
                    "description", label(context, "AsseterpMaintenanceCreatedEvent", null),
                    "serviceName", "asseterpCreateMaintenanceWorkOrder",
                    "sourceReferenceId", maintResult.get("maintHistSeqId"),
                    "userLogin", userLogin));

            Map<String, Object> result = ServiceUtil.returnSuccess();
            result.put("maintHistSeqId", maintResult.get("maintHistSeqId"));
            return result;
        } catch (GenericServiceException e) {
            return ServiceUtil.returnError(e.getMessage());
        }
    }

    public static Map<String, Object> asseterpCompleteMaintenanceWorkOrder(DispatchContext dctx,
            Map<String, ? extends Object> context) {
        Delegator delegator = dctx.getDelegator();
        LocalDispatcher dispatcher = dctx.getDispatcher();
        GenericValue userLogin = (GenericValue) context.get("userLogin");
        String fixedAssetId = (String) context.get("fixedAssetId");
        String maintHistSeqId = (String) context.get("maintHistSeqId");
        Timestamp completionDate = context.get("completionDate") != null
                ? (Timestamp) context.get("completionDate") : UtilDateTime.nowTimestamp();
        try {
            GenericValue maint = delegator.findOne("FixedAssetMaint",
                    UtilMisc.toMap("fixedAssetId", fixedAssetId,
                            "maintHistSeqId", maintHistSeqId), false);
            if (maint == null) {
                return ServiceUtil.returnError(label(context, "AsseterpMaintenanceNotFound", null));
            }
            // FixedAssetMaint has no actualCompletionDate field — the completion
            // moment is recorded on the lifecycle event below (eventDate).
            maint.set("statusId", "FAM_COMPLETED");
            maint.store();

            dispatcher.runSync("asseterpCreateLifecycleEvent", UtilMisc.toMap(
                    "fixedAssetId", fixedAssetId,
                    "partyId", context.get("partyId"),
                    "statusId", "ASSET_ACTIVE",
                    "enumId", "AEVT_MAINT_COMPLETED",
                    "eventDate", completionDate,
                    "description", context.get("comments") != null
                            ? context.get("comments")
                            : label(context, "AsseterpMaintenanceCompletedEvent", null),
                    "serviceName", "asseterpCompleteMaintenanceWorkOrder",
                    "sourceReferenceId", maintHistSeqId,
                    "userLogin", userLogin));
            return ServiceUtil.returnSuccess();
        } catch (GenericEntityException | GenericServiceException e) {
            return ServiceUtil.returnError(e.getMessage());
        }
    }

    public static Map<String, Object> asseterpRecordMeterReading(DispatchContext dctx,
            Map<String, ? extends Object> context) {
        Delegator delegator = dctx.getDelegator();
        LocalDispatcher dispatcher = dctx.getDispatcher();
        GenericValue userLogin = (GenericValue) context.get("userLogin");
        Timestamp now = UtilDateTime.nowTimestamp();
        String fixedAssetId      = (String) context.get("fixedAssetId");
        String productMeterTypeId = (String) context.get("productMeterTypeId");
        Timestamp readingDate    = context.get("readingDate") != null
                ? (Timestamp) context.get("readingDate") : now;
        BigDecimal meterValue    = (BigDecimal) context.get("meterValue");
        try {
            // standard createFixedAssetMeter service (no direct core write)
            Map<String, Object> meterResult = dispatcher.runSync(
                    "createFixedAssetMeter", UtilMisc.toMap(
                            "fixedAssetId", fixedAssetId,
                            "productMeterTypeId", productMeterTypeId,
                            "readingDate", readingDate,
                            "meterValue", meterValue,
                            "userLogin", userLogin));
            if (ServiceUtil.isError(meterResult)) {
                return meterResult;
            }

            dispatcher.runSync("asseterpCreateLifecycleEvent", UtilMisc.toMap(
                    "fixedAssetId", fixedAssetId,
                    "enumId", "AEVT_METER_READING",
                    "eventDate", readingDate,
                    "description", label(context, "AsseterpMeterReadingEvent", null),
                    "serviceName", "asseterpRecordMeterReading",
                    "sourceReferenceId", fixedAssetId + ":" + productMeterTypeId,
                    "userLogin", userLogin));
        } catch (GenericServiceException e) {
            return ServiceUtil.returnError(e.getMessage());
        }
        return ServiceUtil.returnSuccess();
    }

    // ================================================================
    // DEPRECIATION
    // ================================================================

    /** Look up FixedAssetTypeGlAccount for GL account resolution. Returns null when not found. */
    private static GenericValue resolveFixedAssetTypeGlAccount(Delegator delegator,
            String fixedAssetTypeId, String fixedAssetId, String organizationPartyId) {
        try {
            return delegator.findOne("FixedAssetTypeGlAccount",
                    UtilMisc.toMap("fixedAssetTypeId", fixedAssetTypeId != null ? fixedAssetTypeId : "_NA_",
                                   "fixedAssetId",      fixedAssetId != null ? fixedAssetId : "_NA_",
                                   "organizationPartyId", organizationPartyId), true);
        } catch (GenericEntityException e) {
            return null;
        }
    }

    public static Map<String, Object> asseterpCalculateDepreciation(DispatchContext dctx,
            Map<String, ? extends Object> context) {
        LocalDispatcher dispatcher = dctx.getDispatcher();
        GenericValue userLogin = (GenericValue) context.get("userLogin");
        Timestamp nowTimestamp = UtilDateTime.nowTimestamp();
        try {
            Map<String, Object> depCtx = dctx.makeValidContext(
                    "calculateFixedAssetDepreciation", "IN", context);
            depCtx.put("userLogin", userLogin);

            Map<String, Object> depreciationResult = dispatcher.runSync(
                    "calculateFixedAssetDepreciation", depCtx);
            if (ServiceUtil.isError(depreciationResult)) return depreciationResult;

            dispatcher.runSync("asseterpCreateLifecycleEvent", UtilMisc.toMap(
                    "fixedAssetId", context.get("fixedAssetId"),
                    "partyId", context.get("partyId"),
                    "enumId", "AEVT_DEP_CALC",
                    "eventDate", nowTimestamp,
                    "description", label(context, "AsseterpDepreciationCalculatedEvent", null),
                    "serviceName", "asseterpCalculateDepreciation",
                    "amount", depreciationResult.get("nextDepreciationAmount"),
                    "userLogin", userLogin));
            return depreciationResult;
        } catch (GenericServiceException e) {
            return ServiceUtil.returnError(e.getMessage());
        }
    }

    public static Map<String, Object> asseterpPostAssetDepreciation(DispatchContext dctx,
            Map<String, ? extends Object> context) {
        Delegator delegator = dctx.getDelegator();
        LocalDispatcher dispatcher = dctx.getDispatcher();
        GenericValue userLogin = (GenericValue) context.get("userLogin");
        Timestamp transactionDate = context.get("transactionDate") != null
                ? (Timestamp) context.get("transactionDate") : UtilDateTime.nowTimestamp();
        String fixedAssetId = (String) context.get("fixedAssetId");
        try {
            GenericValue fixedAsset = delegator.findOne("FixedAsset",
                    UtilMisc.toMap("fixedAssetId", fixedAssetId), false);
            if (fixedAsset == null) {
                return ServiceUtil.returnError(label(context, "AsseterpFixedAssetNotFound",
                        UtilMisc.toMap("fixedAssetId", fixedAssetId)));
            }

            String partyId = (String) context.get("partyId");
            String currencyUomId = (String) (context.get("currencyUomId") != null
                    ? context.get("currencyUomId") : fixedAsset.get("purchaseCostUomId"));
            BigDecimal amount = (BigDecimal) context.get("amount");

            if (amount == null) {
                // Dispatch to OFBiz formula via FixedAssetDepMethod -> CustomMethod
                BigDecimal purchaseCost = fixedAsset.getBigDecimal("purchaseCost");
                if (purchaseCost == null || purchaseCost.compareTo(BigDecimal.ZERO) <= 0) {
                    Map<String, Object> sk = ServiceUtil.returnSuccess();
                    sk.put("skipped", Boolean.TRUE);
                    sk.put("skipReason", "noPurchaseCost");
                    return sk;
                }
                // Book salvage override takes priority over FixedAsset.salvageValue
                BigDecimal salvageValue = context.get("salvageValueOverride") != null
                        ? (BigDecimal) context.get("salvageValueOverride")
                        : fixedAsset.getBigDecimal("salvageValue");
                if (salvageValue == null) salvageValue = BigDecimal.ZERO;
                // Accumulated depreciation: caller override first (memo books pass
                // their own per-book balance = opening + sum of the book's lines);
                // otherwise the GL — single source of truth for the POSTING book.
                BigDecimal accumulated = BigDecimal.ZERO;
                if (context.get("priorAccumOverride") != null) {
                    accumulated = (BigDecimal) context.get("priorAccumOverride");
                } else {
                    try {
                        List<GenericValue> postedTrans = EntityQuery.use(delegator)
                                .from("AcctgTrans")
                                .where("acctgTransTypeId", "DEPRECIATION",
                                       "isPosted", "Y",
                                       "fixedAssetId", fixedAssetId)
                                .queryList();
                        for (GenericValue t : postedTrans) {
                            List<GenericValue> credits = delegator.findByAnd("AcctgTransEntry",
                                    UtilMisc.toMap("acctgTransId", t.getString("acctgTransId"),
                                                   "debitCreditFlag", "C"), null, true);
                            for (GenericValue c : credits) {
                                BigDecimal a = c.getBigDecimal("amount");
                                if (a != null) accumulated = accumulated.add(a);
                            }
                        }
                    } catch (Exception ignoreAccum) {}
                }
                BigDecimal remaining = purchaseCost.subtract(salvageValue).subtract(accumulated);
                if (remaining.compareTo(BigDecimal.ZERO) <= 0) {
                    Map<String, Object> sk = ServiceUtil.returnSuccess();
                    sk.put("skipped", Boolean.TRUE);
                    sk.put("skipReason", "fullyDepreciated");
                    return sk;
                }
                LocalDate acqDate = toLocalDate(fixedAsset, "dateAcquired");
                LocalDate eolDate = toLocalDate(fixedAsset, "expectedEndOfLife");
                if (acqDate == null || eolDate == null) {
                    Map<String, Object> sk = ServiceUtil.returnSuccess();
                    sk.put("skipped", Boolean.TRUE);
                    sk.put("skipReason", "missingDates");
                    return sk;
                }
                long totalYears = ChronoUnit.YEARS.between(acqDate, eolDate);
                if (totalYears <= 0) {
                    Map<String, Object> sk = ServiceUtil.returnSuccess();
                    sk.put("skipped", Boolean.TRUE);
                    sk.put("skipReason", "noUsefulLife");
                    return sk;
                }
                // Use periodDate (not current date) to compute how many years used so far
                LocalDate periodLocal = toLocalDate(transactionDate);
                int periodYear = (periodLocal != null) ? periodLocal.getYear() : LocalDate.now().getYear();
                int usageYears = periodYear - acqDate.getYear();

                // Resolve active depreciation method for this asset
                try {
                    GenericValue depMethod = EntityQuery.use(delegator)
                            .from("FixedAssetDepMethod")
                            .where("fixedAssetId", fixedAssetId)
                            .filterByDate()
                            .queryFirst();
                    if (depMethod != null) {
                        GenericValue customMethod = delegator.findOne("CustomMethod",
                                UtilMisc.toMap("customMethodId",
                                        depMethod.getString("depreciationCustomMethodId")), true);
                        if (customMethod != null) {
                            Map<String, Object> calcCtx = new HashMap<>();
                            calcCtx.put("fixedAssetId", fixedAssetId);
                            calcCtx.put("expEndOfLifeYear", String.valueOf(eolDate.getYear()));
                            calcCtx.put("assetAcquiredYear", String.valueOf(acqDate.getYear()));
                            calcCtx.put("purchaseCost", purchaseCost);
                            calcCtx.put("salvageValue", salvageValue);
                            calcCtx.put("usageYears", usageYears);
                            calcCtx.put("userLogin", userLogin);
                            Map<String, Object> calcResult = dispatcher.runSync(
                                    customMethod.getString("customMethodName"), calcCtx, 0, true);
                            if (!ServiceUtil.isError(calcResult)) {
                                amount = (BigDecimal) calcResult.get("nextDepreciationAmount");
                            }
                        }
                    }
                } catch (Exception ignore) { /* fall through to SL fallback */ }

                // Fallback: straight-line when no method is configured
                if (amount == null) {
                    amount = purchaseCost.subtract(salvageValue)
                            .divide(BigDecimal.valueOf(totalYears), 2, RoundingMode.HALF_UP);
                }
                // Cap at remaining depreciable amount
                if (amount.compareTo(remaining) > 0) amount = remaining;
            }
            if (amount == null || amount.compareTo(BigDecimal.ZERO) <= 0) {
                Map<String, Object> sk = ServiceUtil.returnSuccess();
                sk.put("skipped", Boolean.TRUE);
                sk.put("skipReason", "zeroAmount");
                return sk;
            }
            if (currencyUomId == null) {
                return ServiceUtil.returnError(label(context, "AsseterpCurrencyRequired", null));
            }

            String debitGlAccountId = (String) context.get("debitGlAccountId");
            String creditGlAccountId = (String) context.get("creditGlAccountId");
            if ((debitGlAccountId == null || creditGlAccountId == null) && partyId != null) {
                String assetTypeId = fixedAsset.getString("fixedAssetTypeId");
                // Priority 1: FixedAssetTypeGlAccount by specific type + specific asset
                GenericValue glMap = resolveFixedAssetTypeGlAccount(delegator, assetTypeId, fixedAssetId, partyId);
                // Priority 2: by specific type + any asset
                if (glMap == null) glMap = resolveFixedAssetTypeGlAccount(delegator, assetTypeId, "_NA_", partyId);
                // Priority 3: wildcard (all types, all assets)
                if (glMap == null) glMap = resolveFixedAssetTypeGlAccount(delegator, "_NA_", "_NA_", partyId);
                if (glMap != null) {
                    if (debitGlAccountId == null)  debitGlAccountId  = glMap.getString("depGlAccountId");
                    if (creditGlAccountId == null) creditGlAccountId = glMap.getString("accDepGlAccountId");
                }
                // Fallback: AsseterpOrgConfig org-level defaults
                if (debitGlAccountId == null || creditGlAccountId == null) {
                    GenericValue orgConfig = delegator.findOne("AsseterpOrgConfig",
                            UtilMisc.toMap("partyId", partyId), false);
                    if (orgConfig != null) {
                        if (debitGlAccountId == null)  debitGlAccountId  = orgConfig.getString("depExpenseGlAccountId");
                        if (creditGlAccountId == null) creditGlAccountId = orgConfig.getString("accumDepGlAccountId");
                    }
                }
            }
            if (debitGlAccountId == null || creditGlAccountId == null) {
                return ServiceUtil.returnError(label(context, "AsseterpDepreciationGlRequired", null));
            }

            Map<String, Object> result = ServiceUtil.returnSuccess();
            result.put("amount", amount);
            result.put("debitGlAccountId", debitGlAccountId);
            result.put("creditGlAccountId", creditGlAccountId);
            result.put("currencyUomId", currencyUomId);

            if ("Y".equalsIgnoreCase((String) context.get("postNow"))) {
                String acctgTransId = createGlTransaction(dispatcher, dctx, context, userLogin,
                        "DEPRECIATION", fixedAssetId, partyId, transactionDate,
                        label(context, "AsseterpDepreciationPostingDescription",
                                UtilMisc.toMap("fixedAssetId", fixedAssetId)),
                        debitGlAccountId, creditGlAccountId, amount, currencyUomId, "Y");
                if (acctgTransId == null) {
                    return ServiceUtil.returnError(label(context, "AsseterpGlTransactionFailed", null));
                }
                BigDecimal existingDep = fixedAsset.getBigDecimal("depreciation");
                fixedAsset.set("depreciation",
                        (existingDep != null ? existingDep : BigDecimal.ZERO).add(amount));
                fixedAsset.store();
                dispatcher.runSync("asseterpCreateLifecycleEvent", UtilMisc.toMap(
                        "fixedAssetId", fixedAssetId,
                        "partyId", partyId,
                        "enumId", "AEVT_DEP_POSTED",
                        "eventDate", transactionDate,
                        "description", label(context, "AsseterpDepreciationPostedEvent", null),
                        "serviceName", "asseterpPostAssetDepreciation",
                        "sourceReferenceId", acctgTransId,
                        "currencyUomId", currencyUomId,
                        "amount", amount,
                        "userLogin", userLogin));
                result.put("acctgTransId", acctgTransId);
            }
            return result;
        } catch (GenericEntityException | GenericServiceException e) {
            return ServiceUtil.returnError(e.getMessage());
        }
    }

    /**
     * Returns all assets assigned to the org that have an AsseterpAssetBook
     * for the requested bookTypeId (or all books when bookTypeId is null).
     * Each row merges FixedAsset fields with book fields so the UI can display
     * a rich asset-by-book view and use it as a preview before running depreciation.
     */
    public static Map<String, Object> asseterpFindAssetsByBook(DispatchContext dctx,
            Map<String, ? extends Object> context) {
        Delegator delegator = dctx.getDelegator();
        String partyId    = (String) context.get("partyId");
        String bookTypeId = (String) context.get("bookTypeId");
        List<Map<String, Object>> assetList = new ArrayList<>();
        try {
            List<GenericValue> assignments = delegator.findByAnd("PartyFixedAssetAssignment",
                    UtilMisc.toMap("partyId", partyId), null, false);
            for (GenericValue assignment : assignments) {
                if (assignment.get("thruDate") != null) continue;
                String fixedAssetId = assignment.getString("fixedAssetId");
                GenericValue asset = delegator.findOne("FixedAsset",
                        UtilMisc.toMap("fixedAssetId", fixedAssetId), true);
                if (asset == null) continue;

                // Find books for this asset — filter by bookTypeId if supplied
                List<GenericValue> books;
                if (bookTypeId != null) {
                    books = EntityQuery.use(delegator)
                            .from("AsseterpAssetBook")
                            .where("fixedAssetId", fixedAssetId, "bookTypeId", bookTypeId)
                            .filterByDate()
                            .queryList();
                } else {
                    books = EntityQuery.use(delegator)
                            .from("AsseterpAssetBook")
                            .where("fixedAssetId", fixedAssetId)
                            .filterByDate()
                            .queryList();
                }

                if (books == null || books.isEmpty()) {
                    // Asset has no book — include with empty book fields so UI can flag it
                    if (bookTypeId != null) continue; // skip if filtering by specific book
                    Map<String, Object> row = new HashMap<>(asset.getAllFields());
                    row.put("hasBook", Boolean.FALSE);
                    assetList.add(row);
                } else {
                    for (GenericValue book : books) {
                        Map<String, Object> row = new HashMap<>(asset.getAllFields());
                        // Book fields overlay asset fields (book values take priority)
                        row.put("asseterpAssetBookId",     book.get("asseterpAssetBookId"));
                        row.put("bookTypeId",              book.get("bookTypeId"));
                        row.put("depreciationMethodId",    book.get("depreciationMethodId"));
                        row.put("usefulLifeYears",         book.get("usefulLifeYears"));
                        row.put("bookSalvageValue",        book.get("salvageValue"));
                        row.put("accumulatedDepreciation", book.get("accumulatedDepreciation"));
                        row.put("netBookValue",            book.get("netBookValue"));
                        row.put("lastDepRunDate",          book.get("lastDepRunDate"));
                        row.put("hasBook", Boolean.TRUE);
                        assetList.add(row);
                    }
                }
            }
        } catch (GenericEntityException e) {
            return ServiceUtil.returnError(e.getMessage());
        }
        Map<String, Object> result = ServiceUtil.returnSuccess();
        result.put("assetList", assetList);
        return result;
    }

    // ================================================================
    // GL-BASED ACCUMULATED DEPRECIATION QUERY
    // ================================================================

    /**
     * Returns accumulated depreciation per asset computed from AcctgTransEntry CREDIT rows.
     * This is the single source of truth — no stored fields, no drift.
     *
     * IN:  fixedAssetIds (List<String>) — assets to query; empty = all
     * OUT: accumDepMap  (Map<String,BigDecimal>) fixedAssetId → accumulatedDepreciation
     *      assetList    (List<Map>) — [{fixedAssetId, accumulatedDepreciation, netBookValue}]
     */
    @SuppressWarnings("unchecked")
    public static Map<String, Object> asseterpGetAccumDep(DispatchContext dctx,
            Map<String, ? extends Object> context) {
        Delegator delegator = dctx.getDelegator();
        List<String> fixedAssetIds = (List<String>) context.get("fixedAssetIds");

        Map<String, BigDecimal> accumMap = new HashMap<>();
        List<Map<String, Object>> assetList = new ArrayList<>();
        try {
            // Query all AcctgTrans of type Depreciation linked to fixed assets
            List<GenericValue> transList = EntityQuery.use(delegator)
                    .from("AcctgTrans")
                    .where("acctgTransTypeId", "DEPRECIATION", "isPosted", "Y")
                    .queryList();

            Set<String> transIds = new HashSet<>();
            Map<String, String> transToAsset = new HashMap<>();
            for (GenericValue t : transList) {
                String faid = t.getString("fixedAssetId");
                if (faid == null) continue;
                if (fixedAssetIds != null && !fixedAssetIds.isEmpty()
                        && !fixedAssetIds.contains(faid)) continue;
                transIds.add(t.getString("acctgTransId"));
                transToAsset.put(t.getString("acctgTransId"), faid);
            }

            // Sum CREDIT entries for each asset
            for (String transId : transIds) {
                List<GenericValue> entries = delegator.findByAnd("AcctgTransEntry",
                        UtilMisc.toMap("acctgTransId", transId, "debitCreditFlag", "C"),
                        null, true);
                String faid = transToAsset.get(transId);
                for (GenericValue entry : entries) {
                    BigDecimal amt = entry.getBigDecimal("amount");
                    if (amt == null || amt.compareTo(BigDecimal.ZERO) <= 0) continue;
                    accumMap.merge(faid, amt, BigDecimal::add);
                }
            }

            // Build assetList with netBookValue derived from purchaseCost
            Set<String> idsToReport = fixedAssetIds != null && !fixedAssetIds.isEmpty()
                    ? new HashSet<>(fixedAssetIds) : accumMap.keySet();
            for (String faid : idsToReport) {
                BigDecimal accum = accumMap.getOrDefault(faid, BigDecimal.ZERO);
                GenericValue fa = delegator.findOne("FixedAsset",
                        UtilMisc.toMap("fixedAssetId", faid), true);
                BigDecimal cost = (fa != null && fa.getBigDecimal("purchaseCost") != null)
                        ? fa.getBigDecimal("purchaseCost") : BigDecimal.ZERO;
                Map<String, Object> row = new HashMap<>();
                row.put("fixedAssetId", faid);
                row.put("accumulatedDepreciation", accum);
                row.put("netBookValue", cost.subtract(accum));
                assetList.add(row);
            }

        } catch (GenericEntityException e) {
            return ServiceUtil.returnError(e.getMessage());
        }

        Map<String, Object> result = ServiceUtil.returnSuccess();
        result.put("accumDepMap", accumMap);
        result.put("assetList", assetList);
        return result;
    }

    @SuppressWarnings("unchecked")
    public static Map<String, Object> asseterpRunDepreciation(DispatchContext dctx,
            Map<String, ? extends Object> context) {
        Delegator delegator = dctx.getDelegator();
        LocalDispatcher dispatcher = dctx.getDispatcher();
        GenericValue userLogin = (GenericValue) context.get("userLogin");
        String partyId = (String) context.get("partyId");
        Timestamp periodDate = (Timestamp) context.get("periodDate");
        String bookTypeId = (String) context.get("bookTypeId");
        String postNow = (String) context.get("postNow");
        Timestamp now = UtilDateTime.nowTimestamp();
        if (periodDate == null) {
            // scheduled (JobSandbox) runs pass no explicit date: run as of now
            periodDate = now;
        }
        try {
            AsseterpWorldwideServices.assertOpenPeriod(delegator, partyId, periodDate);
        } catch (GenericServiceException e) {
            return ServiceUtil.returnError(e.getMessage());
        }

        String customTimePeriodId = (String) context.get("customTimePeriodId");
        boolean isDryRun = "Y".equalsIgnoreCase((String) context.get("dryRun"));
        // Optional subset filter: when supplied, only these assets are processed
        @SuppressWarnings("unchecked")
        List<String> onlyAssetIds = (List<String>) context.get("fixedAssetIds");
        String runId = isDryRun ? "DRY-RUN-PREVIEW" : delegator.getNextSeqId("AsseterpDepreciationRun");
        BigDecimal totalDepAmount = BigDecimal.ZERO;
        long processedCount = 0;
        List<Map<String, Object>> dryRunPreview = isDryRun ? new ArrayList<>() : null;

        try {
            String effectiveBookTypeId = bookTypeId != null ? bookTypeId : "BOOK_GAAP";

            // Resolve the org's posting book (AsseterpOrgConfig.postingBookTypeId).
            // Only that book may write GL entries; every other book type is a memo
            // book whose accumulated depreciation lives in its own line history.
            String postingBookTypeId = "BOOK_GAAP";
            try {
                GenericValue orgCfg = delegator.findOne("AsseterpOrgConfig",
                        UtilMisc.toMap("partyId", partyId), true);
                if (orgCfg != null && UtilValidate.isNotEmpty(orgCfg.getString("postingBookTypeId"))) {
                    postingBookTypeId = orgCfg.getString("postingBookTypeId");
                }
            } catch (Exception ignoreCfg) {}
            boolean isMemoBook = !effectiveBookTypeId.equals(postingBookTypeId);
            if (isMemoBook && "Y".equalsIgnoreCase(postNow)) {
                return ServiceUtil.returnError("Book type " + effectiveBookTypeId
                        + " is a memo book - GL posting is reserved for the posting book "
                        + postingBookTypeId + " (AsseterpOrgConfig.postingBookTypeId)."
                        + " Run memo books without posting.");
            }

            // Create run header — skipped entirely in dry-run mode (no DB writes)
            if (!isDryRun) {
                GenericValue run = delegator.makeValue("AsseterpDepreciationRun");
                run.set("asseterpDepreciationRunId", runId);
                run.set("partyId", partyId);
                run.set("customTimePeriodId", customTimePeriodId);
                run.set("periodDate", periodDate);
                run.set("bookTypeId", effectiveBookTypeId);
                run.set("runDate", now);
                run.set("statusId", "ASSET_ACTIVE");
                if (userLogin != null) {
                    run.set("runByPartyId", userLogin.getString("partyId"));
                }
                run.create();
            }

            // Collect the CustomMethod IDs that correspond to this book type.
            // AsseterpAssetBook.depreciationMethodId holds enum IDs like DEP_SL, DEP_DDB, etc.
            // We resolve each to its AE_DEP_*_FORMULA CustomMethod via the seed data lookup.
            List<String> methodIdsForBook = new ArrayList<>();
            try {
                List<GenericValue> bookRows = EntityQuery.use(delegator)
                        .from("AsseterpAssetBook")
                        .where("bookTypeId", effectiveBookTypeId)
                        .filterByDate()
                        .queryList();
                for (GenericValue lr : bookRows) {
                    String depMethodEnumId = lr.getString("depreciationMethodId");
                    String customMethodId  = resolveCustomMethodId(delegator, depMethodEnumId);
                    if (customMethodId != null && !methodIdsForBook.contains(customMethodId)) {
                        methodIdsForBook.add(customMethodId);
                    }
                }
            } catch (Exception ignore) {}

            // Find all active FixedAssets for this org via PartyFixedAssetAssignment
            List<GenericValue> assignments = delegator.findByAnd("PartyFixedAssetAssignment",
                    UtilMisc.toMap("partyId", partyId), null, false);

            // Track processed assets to avoid double-processing IFRS child components
            Set<String> processedAssets = new HashSet<>();

            // Build list: assets that have an active FixedAssetDepMethod for this book's methods
            // This uses the OFBiz native entity — no AsseterpAssetBook dependency for selection
            List<String> assetIdsToProcess = new ArrayList<>();
            for (GenericValue assignment : assignments) {
                if (assignment.get("thruDate") != null) continue;
                String aid = assignment.getString("fixedAssetId");
                if (assetIdsToProcess.contains(aid)) continue;
                if (UtilValidate.isNotEmpty(onlyAssetIds) && !onlyAssetIds.contains(aid)) continue;
                // Check FixedAssetDepMethod for an active method belonging to this book
                List<GenericValue> depMethods = EntityQuery.use(delegator)
                        .from("FixedAssetDepMethod")
                        .where("fixedAssetId", aid)
                        .filterByDate()
                        .queryList();
                boolean hasBookMethod = false;
                for (GenericValue dm : depMethods) {
                    String mid = dm.getString("depreciationCustomMethodId");
                    if (methodIdsForBook.isEmpty() || methodIdsForBook.contains(mid)) {
                        hasBookMethod = true;
                        break;
                    }
                }
                if (hasBookMethod) assetIdsToProcess.add(aid);
            }

            for (int assetIdx = 0; assetIdx < assetIdsToProcess.size(); assetIdx++) {
                String fixedAssetId = assetIdsToProcess.get(assetIdx);
                if (processedAssets.contains(fixedAssetId)) continue;

                // Guard: skip if already posted for this period
                try {
                    boolean alreadyPosted = false;
                    if (customTimePeriodId != null) {
                        // Precise check: any existing run for same org + period has a line for this asset
                        List<GenericValue> periodRuns = delegator.findByAnd("AsseterpDepreciationRun",
                                UtilMisc.toMap("partyId", partyId, "customTimePeriodId", customTimePeriodId),
                                null, false);
                        for (GenericValue pr : periodRuns) {
                            if (runId.equals(pr.getString("asseterpDepreciationRunId"))) continue;
                            List<GenericValue> prLines = delegator.findByAnd("AsseterpDepreciationLine",
                                    UtilMisc.toMap("asseterpDepreciationRunId",
                                            pr.getString("asseterpDepreciationRunId"),
                                            "fixedAssetId", fixedAssetId), null, false);
                            if (!prLines.isEmpty()) { alreadyPosted = true; break; }
                        }
                    } else {
                        // Calendar-year guard when no CustomTimePeriod is supplied
                        LocalDate periodLocalDate = toLocalDate(periodDate);
                        if (periodLocalDate != null) {
                            int periodYear = periodLocalDate.getYear();
                            LocalDate yearStart = LocalDate.of(periodYear, 1, 1);
                            LocalDate yearEnd   = LocalDate.of(periodYear, 12, 31);
                            List<GenericValue> existingLines = delegator.findByAnd(
                                    "AsseterpDepreciationLine",
                                    UtilMisc.toMap("fixedAssetId", fixedAssetId), null, false);
                            for (GenericValue el : existingLines) {
                                String elRunId = el.getString("asseterpDepreciationRunId");
                                GenericValue elRun = delegator.findOne("AsseterpDepreciationRun",
                                        UtilMisc.toMap("asseterpDepreciationRunId", elRunId), false);
                                if (elRun != null) {
                                    LocalDate elPeriod = toLocalDate(elRun, "periodDate");
                                    if (elPeriod != null
                                            && !elPeriod.isBefore(yearStart)
                                            && !elPeriod.isAfter(yearEnd)) {
                                        alreadyPosted = true;
                                        break;
                                    }
                                }
                            }
                        }
                    }
                    if (alreadyPosted) { processedAssets.add(fixedAssetId); continue; }
                } catch (Exception ignoreDupCheck) { /* non-fatal: proceed */ }

                // Add IFRS component children to the processing queue (if not already queued)
                try {
                    List<GenericValue> children = delegator.findByAnd("FixedAsset",
                            UtilMisc.toMap("parentFixedAssetId", fixedAssetId), null, false);
                    for (GenericValue child : children) {
                        String childId = child.getString("fixedAssetId");
                        if (!assetIdsToProcess.contains(childId)) {
                            assetIdsToProcess.add(childId);
                        }
                    }
                } catch (Exception ignoreChildren) { /* non-fatal */ }

                // Load the active book for this asset + bookTypeId — provides method/salvage/life overrides
                GenericValue assetBook = null;
                String asseterpAssetBookId = null;
                try {
                    List<GenericValue> books = EntityQuery.use(delegator)
                            .from("AsseterpAssetBook")
                            .where("fixedAssetId", fixedAssetId, "bookTypeId", effectiveBookTypeId)
                            .filterByDate()
                            .queryList();
                    if (books != null && !books.isEmpty()) {
                        assetBook = books.get(0);
                        asseterpAssetBookId = assetBook.getString("asseterpAssetBookId");
                    }
                } catch (Exception ignoreBook) { /* non-fatal — fall back to FixedAsset defaults */ }

                // Build call context — pass book salvage override when available.
                // In dry-run: force postNow=N so asseterpPostAssetDepreciation calculates
                // only, without creating any AcctgTrans / AcctgTransEntry rows.
                Map<String, Object> postCtx = new HashMap<>();
                postCtx.put("fixedAssetId", fixedAssetId);
                postCtx.put("partyId", partyId);
                postCtx.put("transactionDate", periodDate);
                postCtx.put("postNow", isDryRun ? "N" : postNow);
                postCtx.put("userLogin", userLogin);
                if (assetBook != null && assetBook.getBigDecimal("salvageValue") != null) {
                    postCtx.put("salvageValueOverride", assetBook.getBigDecimal("salvageValue"));
                }

                // Memo book: prior accumulated = book opening balance + sum of the
                // book's own depreciation lines. Never the GL (that belongs to the
                // posting book) — each book keeps its own accumulation truth.
                BigDecimal memoAccum = null;
                if (isMemoBook && assetBook != null) {
                    memoAccum = assetBook.getBigDecimal("accumulatedDepreciation") != null
                            ? assetBook.getBigDecimal("accumulatedDepreciation") : BigDecimal.ZERO;
                    try {
                        List<GenericValue> priorLines = EntityQuery.use(delegator)
                                .from("AsseterpDepreciationLine")
                                .where("asseterpAssetBookId", asseterpAssetBookId)
                                .queryList();
                        for (GenericValue pl : priorLines) {
                            BigDecimal a = pl.getBigDecimal("depAmount");
                            if (a != null) memoAccum = memoAccum.add(a);
                        }
                    } catch (Exception ignoreLines) {}
                    postCtx.put("priorAccumOverride", memoAccum);
                }
                Map<String, Object> postResult = dispatcher.runSync("asseterpPostAssetDepreciation",
                        postCtx, 0, true);
                if (ServiceUtil.isError(postResult)) continue;
                if (Boolean.TRUE.equals(postResult.get("skipped"))) continue;

                BigDecimal lineAmount = (BigDecimal) postResult.get("amount");
                if (lineAmount == null || lineAmount.compareTo(BigDecimal.ZERO) <= 0) continue;
                if ("Y".equalsIgnoreCase(postNow)) {
                    totalDepAmount = totalDepAmount.add(lineAmount);
                }
                processedCount++;

                // Opening NBV — posting book: purchaseCost minus GL accumulated;
                // memo book: purchaseCost minus the book's own accumulation.
                BigDecimal openingNbv;
                try {
                    BigDecimal priorAccum;
                    if (isMemoBook && memoAccum != null) {
                        priorAccum = memoAccum;
                    } else {
                        Map<String, Object> accumResult = dispatcher.runSync("asseterpGetAccumDep",
                                UtilMisc.toMap("fixedAssetIds",
                                        java.util.Arrays.asList(fixedAssetId), "userLogin", userLogin));
                        Map<String, BigDecimal> accumMap =
                                (Map<String, BigDecimal>) accumResult.get("accumDepMap");
                        priorAccum = (accumMap != null && accumMap.containsKey(fixedAssetId))
                                ? accumMap.get(fixedAssetId) : BigDecimal.ZERO;
                    }
                    GenericValue assetForCost = delegator.findOne("FixedAsset",
                            UtilMisc.toMap("fixedAssetId", fixedAssetId), true);
                    BigDecimal cost = (assetForCost != null
                            && assetForCost.getBigDecimal("purchaseCost") != null)
                            ? assetForCost.getBigDecimal("purchaseCost") : BigDecimal.ZERO;
                    openingNbv = cost.subtract(priorAccum);
                } catch (Exception e) {
                    openingNbv = BigDecimal.ZERO;
                }
                BigDecimal closingNbv = openingNbv.subtract(lineAmount);

                if (isDryRun) {
                    // Dry-run: collect preview data in memory — no DB writes
                    Map<String, Object> preview = new LinkedHashMap<>();
                    preview.put("fixedAssetId", fixedAssetId);
                    preview.put("asseterpAssetBookId", asseterpAssetBookId);
                    preview.put("depreciationMethodId",
                            assetBook != null ? assetBook.getString("depreciationMethodId") : null);
                    preview.put("depAmount", lineAmount);
                    preview.put("openingNbv", openingNbv);
                    preview.put("closingNbv", closingNbv);
                    preview.put("debitGlAccountId",  postResult.get("debitGlAccountId"));
                    preview.put("creditGlAccountId", postResult.get("creditGlAccountId"));
                    dryRunPreview.add(preview);
                } else {
                    // Real run: write depreciation line to DB
                    String lineId = delegator.getNextSeqId("AsseterpDepreciationLine");
                    GenericValue line = delegator.makeValue("AsseterpDepreciationLine");
                    line.set("asseterpDepreciationLineId", lineId);
                    line.set("asseterpDepreciationRunId", runId);
                    line.set("fixedAssetId", fixedAssetId);
                    line.set("asseterpAssetBookId", asseterpAssetBookId);
                    line.set("depreciationMethodId",
                            assetBook != null ? assetBook.getString("depreciationMethodId") : null);
                    line.set("depAmount", lineAmount);
                    line.set("debitGlAccountId", postResult.get("debitGlAccountId"));
                    line.set("creditGlAccountId", postResult.get("creditGlAccountId"));
                    line.set("openingNbv", openingNbv);
                    line.set("closingNbv", closingNbv);
                    line.set("acctgTransId", postResult.get("acctgTransId"));
                    line.create();
                }

                // Balances are no longer stored — GL (AcctgTransEntry) is the single source of truth
                processedAssets.add(fixedAssetId);
            }

            if (!isDryRun) {
                // Update run totals — only auto-close in full-post mode
                GenericValue run = delegator.findOne("AsseterpDepreciationRun",
                        UtilMisc.toMap("asseterpDepreciationRunId", runId), false);
                if (run != null) {
                    if ("Y".equalsIgnoreCase(postNow)) {
                        run.set("totalDepAmount", totalDepAmount);
                        run.set("statusId", "ASSET_CLOSED");
                    }
                    run.store();
                }

                if ("Y".equalsIgnoreCase(postNow)) {
                    dispatcher.runSync("asseterpCreateLifecycleEvent", UtilMisc.toMap(
                            "partyId", partyId,
                            "enumId", "AEVT_DEP_RUN",
                            "eventDate", now,
                            "description", label(context, "AsseterpDepreciationRunEvent", null),
                            "serviceName", "asseterpRunDepreciation",
                            "sourceReferenceId", runId,
                            "amount", totalDepAmount,
                            "userLogin", userLogin));
                }
            }

            Map<String, Object> result = ServiceUtil.returnSuccess();
            result.put("asseterpDepreciationRunId", runId);
            result.put("totalDepAmount", totalDepAmount);
            result.put("processedCount", processedCount);
            if (isDryRun) result.put("dryRunPreview", dryRunPreview);
            return result;
        } catch (GenericEntityException | GenericServiceException e) {
            return ServiceUtil.returnError(e.getMessage());
        }
    }

    // ================================================================
    // DEPRECIATION — MANUAL WORKFLOW HELPERS
    // ================================================================

    /** Post a single pre-calculated depreciation line to the GL (manual approval workflow). */
    public static Map<String, Object> asseterpPostDepreciationLine(DispatchContext dctx,
            Map<String, ? extends Object> context) {
        Delegator delegator = dctx.getDelegator();
        LocalDispatcher dispatcher = dctx.getDispatcher();
        GenericValue userLogin = (GenericValue) context.get("userLogin");
        String lineId = (String) context.get("asseterpDepreciationLineId");
        try {
            GenericValue line = delegator.findOne("AsseterpDepreciationLine",
                    UtilMisc.toMap("asseterpDepreciationLineId", lineId), false);
            if (line == null)
                return ServiceUtil.returnError("Depreciation line not found: " + lineId);
            if (line.getString("acctgTransId") != null) {
                Map<String, Object> r = ServiceUtil.returnSuccess("Already posted");
                r.put("acctgTransId", line.getString("acctgTransId"));
                return r;
            }

            String runId = line.getString("asseterpDepreciationRunId");
            GenericValue run = delegator.findOne("AsseterpDepreciationRun",
                    UtilMisc.toMap("asseterpDepreciationRunId", runId), false);
            if (run == null)
                return ServiceUtil.returnError("Depreciation run not found: " + runId);

            String fixedAssetId = line.getString("fixedAssetId");
            String partyId = run.getString("partyId");
            BigDecimal amount = line.getBigDecimal("depAmount");
            Timestamp transactionDate = run.getTimestamp("periodDate");
            if (transactionDate == null) transactionDate = UtilDateTime.nowTimestamp();
            String debitGlAccountId = line.getString("debitGlAccountId");
            String creditGlAccountId = line.getString("creditGlAccountId");

            if (amount == null || amount.compareTo(BigDecimal.ZERO) <= 0)
                return ServiceUtil.returnError(label(context, "AsseterpDepreciationAmountRequired", null));

            GenericValue fixedAsset = delegator.findOne("FixedAsset",
                    UtilMisc.toMap("fixedAssetId", fixedAssetId), false);
            String currencyUomId = (fixedAsset != null) ? fixedAsset.getString("purchaseCostUomId") : "USD";
            if (currencyUomId == null) currencyUomId = "USD";

            if (debitGlAccountId == null || creditGlAccountId == null) {
                GenericValue orgConfig = delegator.findOne("AsseterpOrgConfig",
                        UtilMisc.toMap("partyId", partyId), false);
                if (orgConfig != null) {
                    if (debitGlAccountId == null) debitGlAccountId = orgConfig.getString("depExpenseGlAccountId");
                    if (creditGlAccountId == null) creditGlAccountId = orgConfig.getString("accumDepGlAccountId");
                }
            }
            if (debitGlAccountId == null || creditGlAccountId == null)
                return ServiceUtil.returnError(label(context, "AsseterpDepreciationGlRequired", null));

            String acctgTransId = createGlTransaction(delegator, dispatcher, dctx, context, userLogin,
                    "DEPRECIATION", fixedAssetId, partyId, transactionDate,
                    label(context, "AsseterpDepreciationPostingDescription",
                            UtilMisc.toMap("fixedAssetId", fixedAssetId)),
                    debitGlAccountId, creditGlAccountId, amount, currencyUomId, "Y");
            if (acctgTransId == null)
                return ServiceUtil.returnError(label(context, "AsseterpGlTransactionFailed", null));

            // Update accumulated depreciation on the asset
            if (fixedAsset != null) {
                BigDecimal existingDep = fixedAsset.getBigDecimal("depreciation");
                fixedAsset.set("depreciation",
                        (existingDep != null ? existingDep : BigDecimal.ZERO).add(amount));
                fixedAsset.store();
            }

            // Update the line
            line.set("acctgTransId", acctgTransId);
            BigDecimal openNbv = line.getBigDecimal("openingNbv");
            if (openNbv != null) line.set("closingNbv", openNbv.subtract(amount));
            line.store();

            // Lifecycle event
            dispatcher.runSync("asseterpCreateLifecycleEvent", UtilMisc.toMap(
                    "fixedAssetId", fixedAssetId, "partyId", partyId,
                    "enumId", "AEVT_DEP_POSTED", "eventDate", transactionDate,
                    "description", label(context, "AsseterpDepreciationPostedEvent", null),
                    "serviceName", "asseterpPostDepreciationLine",
                    "sourceReferenceId", acctgTransId,
                    "currencyUomId", currencyUomId, "amount", amount, "userLogin", userLogin));

            Map<String, Object> result = ServiceUtil.returnSuccess();
            result.put("acctgTransId", acctgTransId);
            result.put("asseterpDepreciationLineId", lineId);
            return result;
        } catch (GenericEntityException | GenericServiceException e) {
            return ServiceUtil.returnError(e.getMessage());
        }
    }

    /** Close a depreciation run: sum posted lines, set ASSET_CLOSED, fire lifecycle event. */
    public static Map<String, Object> asseterpCloseDepreciationRun(DispatchContext dctx,
            Map<String, ? extends Object> context) {
        Delegator delegator = dctx.getDelegator();
        LocalDispatcher dispatcher = dctx.getDispatcher();
        GenericValue userLogin = (GenericValue) context.get("userLogin");
        String runId = (String) context.get("asseterpDepreciationRunId");
        try {
            GenericValue run = delegator.findOne("AsseterpDepreciationRun",
                    UtilMisc.toMap("asseterpDepreciationRunId", runId), false);
            if (run == null) return ServiceUtil.returnError("Run not found: " + runId);

            List<GenericValue> lines = delegator.findByAnd("AsseterpDepreciationLine",
                    UtilMisc.toMap("asseterpDepreciationRunId", runId), null, false);

            BigDecimal totalPosted = BigDecimal.ZERO;
            long postedCount = 0;
            for (GenericValue l : lines) {
                if (l.getString("acctgTransId") != null) {
                    BigDecimal a = l.getBigDecimal("depAmount");
                    if (a != null) totalPosted = totalPosted.add(a);
                    postedCount++;
                }
            }

            run.set("totalDepAmount", totalPosted);
            run.set("statusId", "ASSET_CLOSED");
            run.store();

            dispatcher.runSync("asseterpCreateLifecycleEvent", UtilMisc.toMap(
                    "partyId", run.getString("partyId"),
                    "enumId", "AEVT_DEP_RUN",
                    "eventDate", UtilDateTime.nowTimestamp(),
                    "description", label(context, "AsseterpDepreciationRunEvent", null),
                    "serviceName", "asseterpCloseDepreciationRun",
                    "sourceReferenceId", runId,
                    "amount", totalPosted,
                    "userLogin", userLogin));

            Map<String, Object> result = ServiceUtil.returnSuccess();
            result.put("totalDepAmount", totalPosted);
            result.put("postedCount", postedCount);
            return result;
        } catch (GenericEntityException | GenericServiceException e) {
            return ServiceUtil.returnError(e.getMessage());
        }
    }

    // ================================================================
    // DISPOSAL
    // ================================================================

    public static Map<String, Object> asseterpRetireAsset(DispatchContext dctx,
            Map<String, ? extends Object> context) {
        Delegator delegator = dctx.getDelegator();
        LocalDispatcher dispatcher = dctx.getDispatcher();
        GenericValue userLogin = (GenericValue) context.get("userLogin");
        String fixedAssetId = (String) context.get("fixedAssetId");
        String partyId = (String) context.get("partyId");
        Timestamp retirementDate = context.get("retirementDate") != null
                ? (Timestamp) context.get("retirementDate") : UtilDateTime.nowTimestamp();
        try {
            GenericValue fixedAsset = delegator.findOne("FixedAsset",
                    UtilMisc.toMap("fixedAssetId", fixedAssetId), false);
            if (fixedAsset == null) {
                return ServiceUtil.returnError(label(context, "AsseterpFixedAssetNotFound",
                        UtilMisc.toMap("fixedAssetId", fixedAssetId)));
            }

            String acctgTransId = postDisposalGlEntries(delegator, dispatcher, dctx, context,
                    userLogin, fixedAsset, partyId, retirementDate,
                    BigDecimal.ZERO, "ASSET_RETIREMENT", "ASSET_RETIRED");

            fixedAsset.set("dateLastServiced", retirementDate);
            fixedAsset.store();

            dispatcher.runSync("asseterpCreateLifecycleEvent", UtilMisc.toMap(
                    "fixedAssetId", fixedAssetId,
                    "partyId", partyId,
                    "statusId", "ASSET_RETIRED",
                    "enumId", "AEVT_RETIRED",
                    "eventDate", retirementDate,
                    "description", context.get("reason") != null
                            ? context.get("reason")
                            : label(context, "AsseterpAssetRetiredEvent", null),
                    "serviceName", "asseterpRetireAsset",
                    "sourceReferenceId", acctgTransId,
                    "userLogin", userLogin));

            Map<String, Object> result = ServiceUtil.returnSuccess();
            result.put("acctgTransId", acctgTransId);
            return result;
        } catch (GenericEntityException | GenericServiceException e) {
            return ServiceUtil.returnError(e.getMessage());
        }
    }

    public static Map<String, Object> asseterpScrapAsset(DispatchContext dctx,
            Map<String, ? extends Object> context) {
        Delegator delegator = dctx.getDelegator();
        LocalDispatcher dispatcher = dctx.getDispatcher();
        GenericValue userLogin = (GenericValue) context.get("userLogin");
        String fixedAssetId = (String) context.get("fixedAssetId");
        String partyId = (String) context.get("partyId");
        Timestamp scrapDate = context.get("scrapDate") != null
                ? (Timestamp) context.get("scrapDate") : UtilDateTime.nowTimestamp();
        try {
            GenericValue fixedAsset = delegator.findOne("FixedAsset",
                    UtilMisc.toMap("fixedAssetId", fixedAssetId), false);
            if (fixedAsset == null) {
                return ServiceUtil.returnError(label(context, "AsseterpFixedAssetNotFound",
                        UtilMisc.toMap("fixedAssetId", fixedAssetId)));
            }

            String acctgTransId = postDisposalGlEntries(delegator, dispatcher, dctx, context,
                    userLogin, fixedAsset, partyId, scrapDate,
                    BigDecimal.ZERO, "ASSET_RETIREMENT", "ASSET_SCRAPPED");

            fixedAsset.set("dateLastServiced", scrapDate);
            fixedAsset.store();

            dispatcher.runSync("asseterpCreateLifecycleEvent", UtilMisc.toMap(
                    "fixedAssetId", fixedAssetId,
                    "partyId", partyId,
                    "statusId", "ASSET_SCRAPPED",
                    "enumId", "AEVT_SCRAPPED",
                    "eventDate", scrapDate,
                    "description", context.get("reason") != null
                            ? context.get("reason")
                            : label(context, "AsseterpAssetScrappedEvent", null),
                    "serviceName", "asseterpScrapAsset",
                    "sourceReferenceId", acctgTransId,
                    "userLogin", userLogin));

            Map<String, Object> result = ServiceUtil.returnSuccess();
            result.put("acctgTransId", acctgTransId);
            return result;
        } catch (GenericEntityException | GenericServiceException e) {
            return ServiceUtil.returnError(e.getMessage());
        }
    }

    public static Map<String, Object> asseterpSellAsset(DispatchContext dctx,
            Map<String, ? extends Object> context) {
        Delegator delegator = dctx.getDelegator();
        LocalDispatcher dispatcher = dctx.getDispatcher();
        GenericValue userLogin = (GenericValue) context.get("userLogin");
        String fixedAssetId = (String) context.get("fixedAssetId");
        String partyId = (String) context.get("partyId");
        BigDecimal saleAmount = (BigDecimal) context.get("saleAmount");
        Timestamp saleDate = context.get("saleDate") != null
                ? (Timestamp) context.get("saleDate") : UtilDateTime.nowTimestamp();
        try {
            GenericValue fixedAsset = delegator.findOne("FixedAsset",
                    UtilMisc.toMap("fixedAssetId", fixedAssetId), false);
            if (fixedAsset == null) {
                return ServiceUtil.returnError(label(context, "AsseterpFixedAssetNotFound",
                        UtilMisc.toMap("fixedAssetId", fixedAssetId)));
            }

            String acctgTransId = postDisposalGlEntries(delegator, dispatcher, dctx, context,
                    userLogin, fixedAsset, partyId, saleDate,
                    saleAmount, "ASSET_RETIREMENT", "ASSET_SOLD");

            // Calculate gain/loss = sale proceeds - net book value
            BigDecimal cost = fixedAsset.getBigDecimal("purchaseCost");
            BigDecimal accDep = fixedAsset.getBigDecimal("depreciation");
            BigDecimal nbv = (cost != null ? cost : BigDecimal.ZERO)
                    .subtract(accDep != null ? accDep : BigDecimal.ZERO);
            BigDecimal gainLoss = saleAmount.subtract(nbv);

            fixedAsset.set("dateLastServiced", saleDate);
            fixedAsset.store();

            dispatcher.runSync("asseterpCreateLifecycleEvent", UtilMisc.toMap(
                    "fixedAssetId", fixedAssetId,
                    "partyId", partyId,
                    "statusId", "ASSET_SOLD",
                    "enumId", "AEVT_SOLD",
                    "eventDate", saleDate,
                    "description", label(context, "AsseterpAssetSoldEvent", null),
                    "serviceName", "asseterpSellAsset",
                    "sourceReferenceId", acctgTransId,
                    "currencyUomId", context.get("currencyUomId"),
                    "amount", saleAmount,
                    "userLogin", userLogin));

            Map<String, Object> result = ServiceUtil.returnSuccess();
            result.put("acctgTransId", acctgTransId);
            result.put("gainLossAmount", gainLoss);
            return result;
        } catch (GenericEntityException | GenericServiceException e) {
            return ServiceUtil.returnError(e.getMessage());
        }
    }

    // ================================================================
    // PHYSICAL VERIFICATION
    // ================================================================

    public static Map<String, Object> asseterpCreateVerificationPlan(DispatchContext dctx,
            Map<String, ? extends Object> context) {
        Delegator delegator = dctx.getDelegator();
        String planId = delegator.getNextSeqId("AsseterpVerificationPlan");
        try {
            GenericValue plan = delegator.makeValue("AsseterpVerificationPlan");
            plan.setNonPKFields(context);
            plan.set("asseterpVerificationPlanId", planId);
            if (plan.get("statusId") == null) plan.set("statusId", "ASSET_ACTIVE");
            plan.create();
        } catch (GenericEntityException e) {
            return ServiceUtil.returnError(e.getMessage());
        }
        Map<String, Object> result = ServiceUtil.returnSuccess();
        result.put("asseterpVerificationPlanId", planId);
        return result;
    }

    public static Map<String, Object> asseterpRecordVerificationResult(DispatchContext dctx,
            Map<String, ? extends Object> context) {
        Delegator delegator = dctx.getDelegator();
        String lineId = delegator.getNextSeqId("AsseterpVerificationLine");
        try {
            GenericValue line = delegator.makeValue("AsseterpVerificationLine");
            line.setNonPKFields(context);
            line.set("asseterpVerificationLineId", lineId);
            if (line.get("scanDate") == null) line.set("scanDate", UtilDateTime.nowTimestamp());
            line.create();
        } catch (GenericEntityException e) {
            return ServiceUtil.returnError(e.getMessage());
        }
        Map<String, Object> result = ServiceUtil.returnSuccess();
        result.put("asseterpVerificationLineId", lineId);
        return result;
    }

    public static Map<String, Object> asseterpCompleteVerificationPlan(DispatchContext dctx,
            Map<String, ? extends Object> context) {
        Delegator delegator = dctx.getDelegator();
        LocalDispatcher dispatcher = dctx.getDispatcher();
        GenericValue userLogin = (GenericValue) context.get("userLogin");
        String planId = (String) context.get("asseterpVerificationPlanId");
        Timestamp completedDate = context.get("completedDate") != null
                ? (Timestamp) context.get("completedDate") : UtilDateTime.nowTimestamp();
        try {
            GenericValue plan = delegator.findOne("AsseterpVerificationPlan",
                    UtilMisc.toMap("asseterpVerificationPlanId", planId), false);
            if (plan == null) {
                return ServiceUtil.returnError(label(context, "AsseterpVerificationPlanNotFound", null));
            }
            plan.set("statusId", "ASSET_CLOSED");
            plan.set("completedDate", completedDate);
            plan.store();

            List<GenericValue> lines = delegator.findByAnd("AsseterpVerificationLine",
                    UtilMisc.toMap("asseterpVerificationPlanId", planId,
                            "result", "FOUND"), null, false);
            for (GenericValue line : lines) {
                dispatcher.runSync("asseterpCreateLifecycleEvent", UtilMisc.toMap(
                        "fixedAssetId", line.getString("fixedAssetId"),
                        "partyId", context.get("partyId"),
                        "enumId", "AEVT_VERIFIED",
                        "eventDate", completedDate,
                        "description", label(context, "AsseterpAssetVerifiedEvent", null),
                        "serviceName", "asseterpCompleteVerificationPlan",
                        "sourceReferenceId", planId,
                        "userLogin", userLogin));
            }
        } catch (GenericEntityException | GenericServiceException e) {
            return ServiceUtil.returnError(e.getMessage());
        }
        return ServiceUtil.returnSuccess();
    }

    // ================================================================
    // ASSET TAGGING
    // ================================================================

    public static Map<String, Object> asseterpIssueAssetTag(DispatchContext dctx,
            Map<String, ? extends Object> context) {
        Delegator delegator = dctx.getDelegator();
        LocalDispatcher dispatcher = dctx.getDispatcher();
        GenericValue userLogin = (GenericValue) context.get("userLogin");
        Timestamp now = UtilDateTime.nowTimestamp();
        String tagId = delegator.getNextSeqId("AsseterpAssetTag");
        try {
            GenericValue tag = delegator.makeValue("AsseterpAssetTag");
            tag.setNonPKFields(context);
            tag.set("asseterpAssetTagId", tagId);
            tag.set("statusId", "ASSET_ACTIVE");
            if (tag.get("issuedDate") == null) tag.set("issuedDate", now);
            tag.create();

            dispatcher.runSync("asseterpCreateLifecycleEvent", UtilMisc.toMap(
                    "fixedAssetId", context.get("fixedAssetId"),
                    "enumId", "AEVT_TAGGED",
                    "eventDate", now,
                    "description", label(context, "AsseterpAssetTaggedEvent", null),
                    "serviceName", "asseterpIssueAssetTag",
                    "sourceReferenceId", tagId,
                    "userLogin", userLogin));
        } catch (GenericEntityException | GenericServiceException e) {
            return ServiceUtil.returnError(e.getMessage());
        }
        Map<String, Object> result = ServiceUtil.returnSuccess();
        result.put("asseterpAssetTagId", tagId);
        return result;
    }

    public static Map<String, Object> asseterpRetireAssetTag(DispatchContext dctx,
            Map<String, ? extends Object> context) {
        Delegator delegator = dctx.getDelegator();
        String tagId = (String) context.get("asseterpAssetTagId");
        try {
            GenericValue tag = delegator.findOne("AsseterpAssetTag",
                    UtilMisc.toMap("asseterpAssetTagId", tagId), false);
            if (tag == null) {
                return ServiceUtil.returnError(label(context, "AsseterpAssetTagNotFound", null));
            }
            tag.set("statusId", "ASSET_RETIRED");
            if (context.get("replacedByTagId") != null) {
                tag.set("replacedByTagId", context.get("replacedByTagId"));
            }
            tag.store();
        } catch (GenericEntityException e) {
            return ServiceUtil.returnError(e.getMessage());
        }
        return ServiceUtil.returnSuccess();
    }

    // ================================================================
    // IAS 16 REVALUATION
    // ================================================================

    public static Map<String, Object> asseterpRevalueAsset(DispatchContext dctx,
            Map<String, ? extends Object> context) {
        Delegator delegator = dctx.getDelegator();
        LocalDispatcher dispatcher = dctx.getDispatcher();
        GenericValue userLogin = (GenericValue) context.get("userLogin");
        String fixedAssetId = (String) context.get("fixedAssetId");
        String partyId = (String) context.get("partyId");
        BigDecimal fairValue = (BigDecimal) context.get("fairValue");
        Timestamp revaluationDate = context.get("revaluationDate") != null
                ? (Timestamp) context.get("revaluationDate") : UtilDateTime.nowTimestamp();
        try {
            GenericValue fixedAsset = delegator.findOne("FixedAsset",
                    UtilMisc.toMap("fixedAssetId", fixedAssetId), false);
            if (fixedAsset == null) {
                return ServiceUtil.returnError(label(context, "AsseterpFixedAssetNotFound",
                        UtilMisc.toMap("fixedAssetId", fixedAssetId)));
            }

            BigDecimal preRevalCost = fixedAsset.getBigDecimal("purchaseCost");
            BigDecimal preRevalAccumDep = fixedAsset.getBigDecimal("depreciation");
            if (preRevalCost == null) preRevalCost = BigDecimal.ZERO;
            if (preRevalAccumDep == null) preRevalAccumDep = BigDecimal.ZERO;

            BigDecimal nbv = preRevalCost.subtract(preRevalAccumDep);
            BigDecimal surplus = fairValue.compareTo(nbv) > 0
                    ? fairValue.subtract(nbv) : BigDecimal.ZERO;
            BigDecimal deficit = nbv.compareTo(fairValue) > 0
                    ? nbv.subtract(fairValue) : BigDecimal.ZERO;

            // Resolve currency
            String currencyUomId = (String) (context.get("currencyUomId") != null
                    ? context.get("currencyUomId") : fixedAsset.get("purchaseCostUomId"));

            // Post GL entry if GL accounts are available
            String acctgTransId = null;
            if (partyId != null && currencyUomId != null) {
                GenericValue orgConfig = delegator.findOne("AsseterpOrgConfig",
                        UtilMisc.toMap("partyId", partyId), false);
                if (orgConfig != null) {
                    BigDecimal adjustmentAmount = surplus.compareTo(BigDecimal.ZERO) > 0
                            ? surplus : deficit;
                    if (adjustmentAmount.compareTo(BigDecimal.ZERO) > 0) {
                        // Surplus: debit Asset Cost, credit Revaluation Reserve (use assetCostGlAccountId as debit)
                        // Deficit: debit P&L loss, credit Asset Cost
                        String debitGl = surplus.compareTo(BigDecimal.ZERO) > 0
                                ? orgConfig.getString("assetCostGlAccountId")
                                : orgConfig.getString("lossOnDisposalGlAccountId");
                        String creditGl = surplus.compareTo(BigDecimal.ZERO) > 0
                                ? orgConfig.getString("assetCostGlAccountId")
                                : orgConfig.getString("assetCostGlAccountId");
                        if (debitGl != null && creditGl != null) {
                            acctgTransId = createGlTransaction(delegator,
                                    dispatcher, dctx, context, userLogin,
                                    "REVALUATION", fixedAssetId, partyId, revaluationDate,
                                    label(context, "AsseterpRevaluationPostingDescription",
                                            UtilMisc.toMap("fixedAssetId", fixedAssetId)),
                                    debitGl, creditGl, adjustmentAmount, currencyUomId,
                                    (String) context.get("postNow"));
                        }
                    }
                }
            }

            // Update FixedAsset purchase cost to fair value
            fixedAsset.set("purchaseCost", fairValue);
            fixedAsset.set("depreciation", BigDecimal.ZERO);
            fixedAsset.store();

            // Write revaluation record
            String revId = delegator.getNextSeqId("AsseterpRevaluation");
            GenericValue revaluation = delegator.makeValue("AsseterpRevaluation");
            revaluation.set("asseterpRevaluationId", revId);
            revaluation.set("fixedAssetId", fixedAssetId);
            revaluation.set("revaluationDate", revaluationDate);
            revaluation.set("preRevalCost", preRevalCost);
            revaluation.set("preRevalAccumDep", preRevalAccumDep);
            revaluation.set("fairValue", fairValue);
            revaluation.set("surplusAmount", surplus);
            revaluation.set("deficitAmount", deficit);
            revaluation.set("currencyUomId", currencyUomId);
            revaluation.set("performedByPartyId", context.get("performedByPartyId"));
            revaluation.set("acctgTransId", acctgTransId);
            revaluation.set("notes", context.get("notes"));
            revaluation.create();

            dispatcher.runSync("asseterpCreateLifecycleEvent", UtilMisc.toMap(
                    "fixedAssetId", fixedAssetId,
                    "partyId", partyId,
                    "enumId", "AEVT_REVALUED",
                    "eventDate", revaluationDate,
                    "description", label(context, "AsseterpAssetRevaluedEvent", null),
                    "serviceName", "asseterpRevalueAsset",
                    "sourceReferenceId", revId,
                    "currencyUomId", currencyUomId,
                    "amount", fairValue,
                    "userLogin", userLogin));

            Map<String, Object> result = ServiceUtil.returnSuccess();
            result.put("asseterpRevaluationId", revId);
            result.put("acctgTransId", acctgTransId);
            return result;
        } catch (GenericEntityException | GenericServiceException e) {
            return ServiceUtil.returnError(e.getMessage());
        }
    }

    // ================================================================
    // ALERT MANAGEMENT
    // ================================================================

    public static Map<String, Object> asseterpAcknowledgeAlert(DispatchContext dctx,
            Map<String, ? extends Object> context) {
        Delegator delegator = dctx.getDelegator();
        String alertId = (String) context.get("asseterpAlertId");
        Timestamp acknowledgedDate = context.get("acknowledgedDate") != null
                ? (Timestamp) context.get("acknowledgedDate") : UtilDateTime.nowTimestamp();
        try {
            GenericValue alert = delegator.findOne("AsseterpAlert",
                    UtilMisc.toMap("asseterpAlertId", alertId), false);
            if (alert == null) {
                return ServiceUtil.returnError(label(context, "AsseterpAlertNotFound", null));
            }
            alert.set("statusId", "ASSET_CLOSED");
            alert.set("acknowledgedByPartyId", context.get("acknowledgedByPartyId"));
            alert.set("acknowledgedDate", acknowledgedDate);
            alert.store();
        } catch (GenericEntityException e) {
            return ServiceUtil.returnError(e.getMessage());
        }
        return ServiceUtil.returnSuccess();
    }

    // ================================================================
    // PRIVATE HELPERS
    // ================================================================

    /**
     * Creates an accounting transaction with one debit and one credit entry.
     * Returns the acctgTransId on success, null on failure.
     */
    private static String createGlTransaction(Delegator delegator, LocalDispatcher dispatcher,
            DispatchContext dctx, Map<String, ? extends Object> context, GenericValue userLogin,
            String acctgTransTypeId, String fixedAssetId, String partyId,
            Timestamp transactionDate, String description,
            String debitGlAccountId, String creditGlAccountId,
            BigDecimal amount, String currencyUomId, String postNow)
            throws GenericServiceException, GenericEntityException {

        AsseterpWorldwideServices.assertOpenPeriod(delegator, partyId, transactionDate);
        Map<String, Object> transResult = dispatcher.runSync("createAcctgTrans",
                UtilMisc.toMap(
                        "acctgTransTypeId", acctgTransTypeId,
                        "fixedAssetId", fixedAssetId,
                        "partyId", partyId,
                        "transactionDate", transactionDate,
                        "glFiscalTypeId", "ACTUAL",
                        "description", description,
                        "userLogin", userLogin));
        if (ServiceUtil.isError(transResult)) return null;

        String acctgTransId = (String) transResult.get("acctgTransId");

        Map<String, Object> debitResult = dispatcher.runSync("createAcctgTransEntry",
                UtilMisc.toMap(
                        "acctgTransId", acctgTransId,
                        "organizationPartyId", partyId,
                        "glAccountId", debitGlAccountId,
                        "amount", amount,
                        "currencyUomId", currencyUomId,
                        "debitCreditFlag", "D",
                        "description", description,
                        "userLogin", userLogin));
        if (ServiceUtil.isError(debitResult)) return null;

        Map<String, Object> creditResult = dispatcher.runSync("createAcctgTransEntry",
                UtilMisc.toMap(
                        "acctgTransId", acctgTransId,
                        "organizationPartyId", partyId,
                        "glAccountId", creditGlAccountId,
                        "amount", amount,
                        "currencyUomId", currencyUomId,
                        "debitCreditFlag", "C",
                        "description", description,
                        "userLogin", userLogin));
        if (ServiceUtil.isError(creditResult)) return null;

        if ("Y".equals(postNow)) {
            dispatcher.runSync("postAcctgTrans",
                    UtilMisc.toMap("acctgTransId", acctgTransId, "userLogin", userLogin));
        }
        return acctgTransId;
    }

    /**
     * Posts disposal GL entries: removes asset cost and accumulated depreciation from books.
     * Gain/Loss = saleProceeds - (cost - accumDep). Zero saleProceeds for retire/scrap.
     */
    private static String postDisposalGlEntries(Delegator delegator, LocalDispatcher dispatcher,
            DispatchContext dctx, Map<String, ? extends Object> context, GenericValue userLogin,
            GenericValue fixedAsset, String partyId, Timestamp disposalDate,
            BigDecimal saleProceeds, String acctgTransTypeId, String eventTypeId)
            throws GenericServiceException, GenericEntityException {

        if (partyId == null) return null;

        GenericValue orgConfig = delegator.findOne("AsseterpOrgConfig",
                UtilMisc.toMap("partyId", partyId), false);
        if (orgConfig == null) return null;

        BigDecimal cost = fixedAsset.getBigDecimal("purchaseCost");
        BigDecimal accDep = fixedAsset.getBigDecimal("depreciation");
        if (cost == null) cost = BigDecimal.ZERO;
        if (accDep == null) accDep = BigDecimal.ZERO;

        BigDecimal nbv = cost.subtract(accDep);
        BigDecimal gainLoss = saleProceeds.subtract(nbv);

        String currencyUomId = (String) (context.get("currencyUomId") != null
                ? context.get("currencyUomId") : fixedAsset.get("purchaseCostUomId"));
        String assetCostGl = orgConfig.getString("assetCostGlAccountId");
        String accumDepGl = orgConfig.getString("accumDepGlAccountId");
        String gainGl = orgConfig.getString("gainOnDisposalGlAccountId");
        String lossGl = orgConfig.getString("lossOnDisposalGlAccountId");

        if (assetCostGl == null || accumDepGl == null || currencyUomId == null) return null;

        String description = label(context, "AsseterpDisposalPostingDescription",
                UtilMisc.toMap("fixedAssetId", fixedAsset.getString("fixedAssetId")));

        Map<String, Object> transResult = dispatcher.runSync("createAcctgTrans",
                UtilMisc.toMap(
                        "acctgTransTypeId", acctgTransTypeId,
                        "fixedAssetId", fixedAsset.getString("fixedAssetId"),
                        "partyId", partyId,
                        "transactionDate", disposalDate,
                        "glFiscalTypeId", "ACTUAL",
                        "description", description,
                        "userLogin", userLogin));
        if (ServiceUtil.isError(transResult)) return null;

        String acctgTransId = (String) transResult.get("acctgTransId");

        // Debit: Cash / Receivable for the sale proceeds (balanced sale)
        if (saleProceeds != null && saleProceeds.compareTo(BigDecimal.ZERO) > 0) {
            String cashGl = null;
            List<GenericValue> cashMaps = EntityQuery.use(delegator)
                    .from("AsseterpGlMapping")
                    .where("partyId", partyId, "glMappingTypeId", "GL_MAP_CASH")
                    .filterByDate(disposalDate, "fromDate", "thruDate")
                    .queryList();
            if (!cashMaps.isEmpty()) {
                cashGl = cashMaps.get(0).getString("glAccountId");
            }
            if (cashGl == null) {
                return null; // proceeds unmappable: do not post an unbalanced journal
            }
            Map<String, Object> cashEntry = dispatcher.runSync("createAcctgTransEntry", UtilMisc.toMap(
                    "acctgTransId", acctgTransId,
                    "organizationPartyId", partyId,
                    "glAccountId", cashGl,
                    "amount", saleProceeds,
                    "currencyUomId", currencyUomId,
                    "debitCreditFlag", "D",
                    "description", description,
                    "userLogin", userLogin));
            if (ServiceUtil.isError(cashEntry)) return null;
        }

        // Debit: Accumulated Depreciation (remove from books)
        if (accDep.compareTo(BigDecimal.ZERO) > 0) {
            Map<String, Object> r1 = dispatcher.runSync("createAcctgTransEntry", UtilMisc.toMap(
                    "acctgTransId", acctgTransId,
                    "organizationPartyId", partyId,
                    "glAccountId", accumDepGl,
                    "amount", accDep,
                    "currencyUomId", currencyUomId,
                    "debitCreditFlag", "D",
                    "description", description,
                    "userLogin", userLogin));
            if (ServiceUtil.isError(r1)) return null;
        }

        // Credit: Asset Cost (remove from books)
        if (cost.compareTo(BigDecimal.ZERO) > 0) {
            Map<String, Object> r2 = dispatcher.runSync("createAcctgTransEntry", UtilMisc.toMap(
                    "acctgTransId", acctgTransId,
                    "organizationPartyId", partyId,
                    "glAccountId", assetCostGl,
                    "amount", cost,
                    "currencyUomId", currencyUomId,
                    "debitCreditFlag", "C",
                    "description", description,
                    "userLogin", userLogin));
            if (ServiceUtil.isError(r2)) return null;
        }

        // Gain or Loss on disposal
        if (gainLoss.compareTo(BigDecimal.ZERO) > 0 && gainGl != null) {
            // Gain: credit Gain on Disposal
            dispatcher.runSync("createAcctgTransEntry", UtilMisc.toMap(
                    "acctgTransId", acctgTransId,
                    "organizationPartyId", partyId,
                    "glAccountId", gainGl,
                    "amount", gainLoss,
                    "currencyUomId", currencyUomId,
                    "debitCreditFlag", "C",
                    "description", description,
                    "userLogin", userLogin));
        } else if (gainLoss.compareTo(BigDecimal.ZERO) < 0 && lossGl != null) {
            // Loss: debit Loss on Disposal
            dispatcher.runSync("createAcctgTransEntry", UtilMisc.toMap(
                    "acctgTransId", acctgTransId,
                    "organizationPartyId", partyId,
                    "glAccountId", lossGl,
                    "amount", gainLoss.negate(),
                    "currencyUomId", currencyUomId,
                    "debitCreditFlag", "D",
                    "description", description,
                    "userLogin", userLogin));
        }

        if ("Y".equals(context.get("postNow"))) {
            dispatcher.runSync("postAcctgTrans",
                    UtilMisc.toMap("acctgTransId", acctgTransId, "userLogin", userLogin));
        }
        return acctgTransId;
    }

    // Overload without delegator param (uses dctx)
    private static String createGlTransaction(LocalDispatcher dispatcher,
            DispatchContext dctx, Map<String, ? extends Object> context, GenericValue userLogin,
            String acctgTransTypeId, String fixedAssetId, String partyId,
            Timestamp transactionDate, String description,
            String debitGlAccountId, String creditGlAccountId,
            BigDecimal amount, String currencyUomId, String postNow)
            throws GenericServiceException, GenericEntityException {
        return createGlTransaction(dctx.getDelegator(), dispatcher, dctx, context, userLogin,
                acctgTransTypeId, fixedAssetId, partyId, transactionDate, description,
                debitGlAccountId, creditGlAccountId, amount, currencyUomId, postNow);
    }

    /** Read a date field safely regardless of whether it is stored as Date or Timestamp. */
    private static LocalDate toLocalDate(GenericValue gv, String field) {
        Object v = gv.get(field);
        if (v == null) return null;
        if (v instanceof java.sql.Timestamp) return ((java.sql.Timestamp) v).toLocalDateTime().toLocalDate();
        if (v instanceof java.sql.Date)      return ((java.sql.Date) v).toLocalDate();
        return null;
    }

    private static LocalDate toLocalDate(Timestamp ts) {
        if (ts == null) return null;
        return ts.toLocalDateTime().toLocalDate();
    }

    private static String label(Map<String, ? extends Object> context, String key,
            Map<String, ? extends Object> params) {
        Locale locale = (Locale) context.get("locale");
        return UtilProperties.getMessage(RESOURCE, key,
                params != null ? params : UtilMisc.toMap(), locale);
    }

    /**
     * Maps an AsseterpAssetBook depreciationMethodId (e.g. "DEP_SL") to the
     * corresponding CustomMethod.customMethodId (e.g. "AE_DEP_SL_FORMULA").
     * The convention is: AE_DEP_{enumId without "DEP_"}_FORMULA.
     * Falls back to a DB lookup on CustomMethod.customMethodName for flexibility.
     */
    private static final Map<String, String> DEP_METHOD_TO_CUSTOM_METHOD = new HashMap<>();
    static {
        DEP_METHOD_TO_CUSTOM_METHOD.put("DEP_SL",   "AE_DEP_SL_FORMULA");
        DEP_METHOD_TO_CUSTOM_METHOD.put("DEP_DDB",  "AE_DEP_DDB_FORMULA");
        DEP_METHOD_TO_CUSTOM_METHOD.put("DEP_SYD",  "AE_DEP_SYD_FORMULA");
        DEP_METHOD_TO_CUSTOM_METHOD.put("DEP_UOP",  "AE_DEP_UOP_FORMULA");
        DEP_METHOD_TO_CUSTOM_METHOD.put("DEP_RB",   "AE_DEP_RB_FORMULA");
        DEP_METHOD_TO_CUSTOM_METHOD.put("DEP_NONE", "AE_DEP_NONE_FORMULA");
        DEP_METHOD_TO_CUSTOM_METHOD.put("DEP_MACRS","AE_DEP_MACRS_FORMULA");
    }

    static String resolveCustomMethodId(Delegator delegator, String depMethodEnumId) {
        if (depMethodEnumId == null) return null;
        String mapped = DEP_METHOD_TO_CUSTOM_METHOD.get(depMethodEnumId);
        if (mapped != null) return mapped;
        // Fallback: find any CustomMethod of type DEPRECIATION_FORMULA whose name
        // contains the enum suffix (e.g. "Sl", "Ddb") — for unknown custom enums.
        try {
            List<GenericValue> customs = EntityQuery.use(delegator)
                    .from("CustomMethod")
                    .where("customMethodTypeId", "DEPRECIATION_FORMULA")
                    .queryList();
            String suffix = depMethodEnumId.replace("DEP_", "").toLowerCase();
            for (GenericValue cm : customs) {
                String name = cm.getString("customMethodName");
                if (name != null && name.toLowerCase().contains(suffix)) {
                    return cm.getString("customMethodId");
                }
            }
        } catch (GenericEntityException ignore) {}
        return null;
    }

    /**
     * Creates or updates the FixedAssetDepMethod record for an asset.
     * Expires the previous active record (sets thruDate) before inserting the new one,
     * so the calculation engine always has exactly one active method per asset.
     */
    static void upsertFixedAssetDepMethod(Delegator delegator, String fixedAssetId,
            String customMethodId, Timestamp now) {
        upsertFixedAssetDepMethod(delegator, null, null, fixedAssetId, customMethodId, now);
    }

    /** Dispatcher-aware variant: the insert goes through the standard
     *  createFixedAssetDepMethod service when a dispatcher is given. */
    static void upsertFixedAssetDepMethod(Delegator delegator, LocalDispatcher dispatcher,
            GenericValue userLogin, String fixedAssetId,
            String customMethodId, Timestamp now) {
        try {
            // Expire any existing active FixedAssetDepMethod for this asset
            List<GenericValue> existing = EntityQuery.use(delegator)
                    .from("FixedAssetDepMethod")
                    .where("fixedAssetId", fixedAssetId)
                    .filterByDate()
                    .queryList();
            for (GenericValue dm : existing) {
                if (!customMethodId.equals(dm.getString("depreciationCustomMethodId"))) {
                    dm.set("thruDate", now);
                    dm.store();
                } else {
                    return; // already set to same method — nothing to do
                }
            }
            // Insert new active record through the standard service
            if (dispatcher != null) {
                dispatcher.runSync("createFixedAssetDepMethod", UtilMisc.toMap(
                        "fixedAssetId", fixedAssetId,
                        "depreciationCustomMethodId", customMethodId,
                        "fromDate", now,
                        "userLogin", userLogin));
            } else {
                GenericValue newDm = delegator.makeValue("FixedAssetDepMethod");
                newDm.set("fixedAssetId", fixedAssetId);
                newDm.set("depreciationCustomMethodId", customMethodId);
                newDm.set("fromDate", now);
                delegator.create(newDm);
            }
        } catch (GenericEntityException | GenericServiceException ignore) {
            // best-effort sync; the book row remains the source of truth
        }
    }
}
