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

import java.sql.Timestamp;
import java.util.HashMap;
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
import org.apache.ofbiz.service.DispatchContext;
import org.apache.ofbiz.service.GenericServiceException;
import org.apache.ofbiz.service.LocalDispatcher;
import org.apache.ofbiz.service.ServiceUtil;

/**
 * Transactional request approval services for the Assets component.
 *
 * <p>The core OFBiz Requirement remains the authoritative request. AssetsRequestDetail and
 * AssetsApprovalDecision hold only asset-specific workflow attributes and decision audit data.</p>
 */
public final class AssetsApprovalServices {

    private static final String MODULE = AssetsApprovalServices.class.getName();
    private static final String RESOURCE = "AssetsUiLabels";

    private static final String STATUS_PENDING = "ASSETS_PENDING";
    private static final String STATUS_APPROVED = "ASSETS_APPROVED";
    private static final String STATUS_REJECTED = "ASSETS_REJECTED";
    private static final String STATUS_CANCELLED = "ASSETS_CANCELLED";
    private static final String DECISION_APPROVE = "ASSETS_DEC_APPROVE";
    private static final String DECISION_REJECT = "ASSETS_DEC_REJECT";
    private static final String DECISION_ENUM_TYPE = "ASSETS_DECISION";

    private AssetsApprovalServices() {
    }

    /**
     * Preview or commit one pending approval decision.
     *
     * <p>This OFBiz 24 checkout does not expose EntityQuery.forUpdate(). The service therefore
     * serializes work with a narrow update of the request audit fields, then uses a conditional
     * compare-and-set update on the pending decision. This provides a database row lock without
     * overwriting business fields and prevents duplicate decisions.</p>
     */
    public static Map<String, Object> assetsProcessApprovalDecision(
            DispatchContext dctx, Map<String, ? extends Object> context) {
        Locale locale = locale(context);
        Delegator delegator = dctx.getDelegator();
        LocalDispatcher dispatcher = dctx.getDispatcher();
        GenericValue userLogin = (GenericValue) context.get("userLogin");
        String decisionId = stringValue(context.get("assetsApprovalDecisionId"));
        String requestedDecision = stringValue(context.get("decisionEnumId"));
        String comments = stringValue(context.get("comments"));
        boolean dryRun = isDryRun(context.get("dryRun"));

        try {
            if (UtilValidate.isEmpty(decisionId)) {
                throw new AssetsApprovalException(message("AssetsErrorApprovalDecisionIdRequired", locale));
            }
            validateRequestedDecision(delegator, requestedDecision, locale);
            if (DECISION_REJECT.equals(requestedDecision) && UtilValidate.isEmpty(comments)) {
                throw new AssetsApprovalException(message("AssetsErrorApprovalRejectCommentsRequired", locale));
            }

            GenericValue decision = findDecision(delegator, decisionId);
            if (decision == null) {
                throw new AssetsApprovalException(message("AssetsErrorApprovalDecisionNotFound", locale,
                        "assetsApprovalDecisionId", decisionId));
            }
            GenericValue requestDetail = requireRequestDetail(delegator, decision, locale);
            validateActor(dctx, decision, userLogin, locale);
            validateDecisionState(decision, locale);
            validateRequestState(requestDetail, requestedDecision, locale);
            validatePreviousDecisions(delegator, decision, locale);
            if (DECISION_REJECT.equals(requestedDecision)) {
                validateNoDownstreamProcessing(delegator, decision, locale);
            }

            if (dryRun) {
                return successResult(decision, requestDetail, requestedDecision, true, 0, locale);
            }

            lockRequestDetail(delegator, requestDetail, userLogin, locale);

            // Re-read and repeat all mutable validations after acquiring the requirement lock.
            decision = findDecision(delegator, decisionId);
            requestDetail = requireRequestDetail(delegator, decision, locale);
            validateActor(dctx, decision, userLogin, locale);
            validateDecisionState(decision, locale);
            validateRequestState(requestDetail, requestedDecision, locale);
            validatePreviousDecisions(delegator, decision, locale);
            if (DECISION_REJECT.equals(requestedDecision)) {
                validateNoDownstreamProcessing(delegator, decision, locale);
            }

            Timestamp decisionDate = UtilDateTime.nowTimestamp();
            Map<String, Object> decisionFields = new HashMap<>();
            decisionFields.put("decisionEnumId", requestedDecision);
            decisionFields.put("statusId", statusForDecision(requestedDecision));
            decisionFields.put("decisionDate", decisionDate);
            if (UtilValidate.isNotEmpty(comments)) {
                decisionFields.put("comments", comments);
            }

            EntityCondition unprocessedDecision = EntityCondition.makeCondition(List.of(
                    EntityCondition.makeCondition("assetsApprovalDecisionId", decisionId),
                    EntityCondition.makeCondition("statusId", STATUS_PENDING),
                    EntityCondition.makeCondition("decisionEnumId", EntityOperator.EQUALS, null)
            ), EntityOperator.AND);
            int updated = delegator.storeByCondition("AssetsApprovalDecision", decisionFields, unprocessedDecision);
            if (updated != 1) {
                throw new AssetsApprovalException(message("AssetsErrorApprovalDecisionAlreadyProcessed", locale,
                        "assetsApprovalDecisionId", decisionId));
            }

            int cancelledDecisionCount = 0;
            String requestStatusId = requestDetail.getString("statusId");
            if (DECISION_REJECT.equals(requestedDecision)) {
                cancelledDecisionCount = cancelRemainingDecisions(delegator, decision);
                requestStatusId = STATUS_REJECTED;
                updateRequestStatus(delegator, dispatcher, requestDetail, requestStatusId, userLogin, locale);
            } else if (countPendingDecisions(delegator, decision.getString("requirementId")) == 0
                    && STATUS_PENDING.equals(requestStatusId)) {
                requestStatusId = STATUS_APPROVED;
                updateRequestStatus(delegator, dispatcher, requestDetail, requestStatusId, userLogin, locale);
            }

            requestDetail.set("statusId", requestStatusId);
            return successResult(decision, requestDetail, requestedDecision, false,
                    cancelledDecisionCount, locale);
        } catch (AssetsApprovalException e) {
            Debug.logError(e, e.getMessage(), MODULE);
            return ServiceUtil.returnError(e.getMessage());
        } catch (GenericEntityException | GenericServiceException e) {
            Debug.logError(e, "Asset approval decision processing failed", MODULE);
            return ServiceUtil.returnError(message("AssetsErrorApprovalUnexpectedFailure", locale)
                    + " Detail: " + e.getMessage());
        } catch (RuntimeException e) {
            Debug.logError(e, "Unexpected asset approval decision failure", MODULE);
            return ServiceUtil.returnError(message("AssetsErrorApprovalUnexpectedFailure", locale)
                    + " Detail: " + e.getMessage());
        }
    }

    private static GenericValue findDecision(Delegator delegator, String decisionId) throws GenericEntityException {
        return EntityQuery.use(delegator).from("AssetsApprovalDecision")
                .where("assetsApprovalDecisionId", decisionId).cache(false).queryOne();
    }

    private static GenericValue requireRequestDetail(Delegator delegator, GenericValue decision, Locale locale)
            throws GenericEntityException, AssetsApprovalException {
        if (decision == null || UtilValidate.isEmpty(decision.getString("requirementId"))) {
            throw new AssetsApprovalException(message("AssetsErrorApprovalRequirementMissing", locale));
        }
        String requirementId = decision.getString("requirementId");
        GenericValue requestDetail = EntityQuery.use(delegator).from("AssetsRequestDetail")
                .where("requirementId", requirementId).cache(false).queryOne();
        if (requestDetail == null) {
            throw new AssetsApprovalException(message("AssetsErrorApprovalRequestDetailNotFound", locale,
                    "requirementId", requirementId));
        }
        return requestDetail;
    }

    private static void validateRequestedDecision(Delegator delegator, String decisionEnumId, Locale locale)
            throws GenericEntityException, AssetsApprovalException {
        if (!DECISION_APPROVE.equals(decisionEnumId) && !DECISION_REJECT.equals(decisionEnumId)) {
            throw new AssetsApprovalException(message("AssetsErrorApprovalInvalidDecision", locale));
        }
        GenericValue enumeration = EntityQuery.use(delegator).from("Enumeration")
                .where("enumId", decisionEnumId, "enumTypeId", DECISION_ENUM_TYPE).cache().queryOne();
        if (enumeration == null) {
            throw new AssetsApprovalException(message("AssetsErrorApprovalInvalidDecision", locale));
        }
    }

    private static void validateActor(DispatchContext dctx, GenericValue decision, GenericValue userLogin, Locale locale)
            throws AssetsApprovalException {
        String userPartyId = userLogin == null ? null : userLogin.getString("partyId");
        String approverPartyId = decision.getString("approverPartyId");
        boolean administrator = dctx.getSecurity().hasEntityPermission("ASSETS", "_ADMIN", userLogin);
        if (!administrator && (UtilValidate.isEmpty(approverPartyId) || !approverPartyId.equals(userPartyId))) {
            throw new AssetsApprovalException(message("AssetsErrorApprovalUnauthorizedApprover", locale,
                    "approverPartyId", approverPartyId));
        }
    }

    private static void validateDecisionState(GenericValue decision, Locale locale) throws AssetsApprovalException {
        if (!STATUS_PENDING.equals(decision.getString("statusId"))
                || UtilValidate.isNotEmpty(decision.getString("decisionEnumId"))) {
            throw new AssetsApprovalException(message("AssetsErrorApprovalDecisionAlreadyProcessed", locale,
                    "assetsApprovalDecisionId", decision.getString("assetsApprovalDecisionId")));
        }
    }

    private static void validateRequestState(GenericValue requestDetail, String requestedDecision, Locale locale)
            throws AssetsApprovalException {
        String statusId = requestDetail.getString("statusId");
        if (DECISION_REJECT.equals(requestedDecision) && !STATUS_PENDING.equals(statusId)) {
            throw new AssetsApprovalException(message("AssetsErrorApprovalRequestAlreadyFinal", locale,
                    "requirementId", requestDetail.getString("requirementId"), "statusId", statusId));
        }
        if (DECISION_APPROVE.equals(requestedDecision)
                && !STATUS_PENDING.equals(statusId) && !STATUS_APPROVED.equals(statusId)) {
            throw new AssetsApprovalException(message("AssetsErrorApprovalRequestAlreadyFinal", locale,
                    "requirementId", requestDetail.getString("requirementId"), "statusId", statusId));
        }
    }

    private static void validatePreviousDecisions(Delegator delegator, GenericValue decision, Locale locale)
            throws GenericEntityException, AssetsApprovalException {
        Long sequenceNum = decision.getLong("sequenceNum");
        if (sequenceNum == null) {
            return;
        }
        EntityCondition previousCondition = EntityCondition.makeCondition(List.of(
                EntityCondition.makeCondition("requirementId", decision.getString("requirementId")),
                EntityCondition.makeCondition("sequenceNum", EntityOperator.LESS_THAN, sequenceNum)
        ), EntityOperator.AND);
        List<GenericValue> previous = EntityQuery.use(delegator).from("AssetsApprovalDecision")
                .where(previousCondition).orderBy("sequenceNum").cache(false).queryList();
        for (GenericValue earlier : previous) {
            if (!STATUS_APPROVED.equals(earlier.getString("statusId"))) {
                throw new AssetsApprovalException(message("AssetsErrorApprovalEarlierStepPending", locale,
                        "assetsApprovalDecisionId", earlier.getString("assetsApprovalDecisionId")));
            }
        }
    }

    private static void validateNoDownstreamProcessing(Delegator delegator, GenericValue decision, Locale locale)
            throws GenericEntityException, AssetsApprovalException {
        String requirementId = decision.getString("requirementId");
        long capitalizationCount = EntityQuery.use(delegator).from("AssetsCapitalization")
                .where("requirementId", requirementId).cache(false).queryCount();
        String fixedAssetId = decision.getString("fixedAssetId");
        long bookAssignmentCount = 0;
        if (UtilValidate.isNotEmpty(fixedAssetId)) {
            EntityCondition activeBookAssignment = EntityCondition.makeCondition(List.of(
                    EntityCondition.makeCondition("fixedAssetId", fixedAssetId),
                    EntityCondition.makeCondition("statusId", EntityOperator.IN,
                            List.of("ASSETS_ACTIVE", "ASSETS_IN_SERVICE", "ASSETS_POSTED"))
            ), EntityOperator.AND);
            bookAssignmentCount = EntityQuery.use(delegator).from("AssetsBookDetail")
                    .where(activeBookAssignment).cache(false).queryCount();
        }
        if (capitalizationCount > 0 || bookAssignmentCount > 0) {
            throw new AssetsApprovalException(message("AssetsErrorApprovalDownstreamProcessingStarted", locale,
                    "requirementId", requirementId));
        }
    }

    private static void lockRequestDetail(Delegator delegator, GenericValue requestDetail,
            GenericValue userLogin, Locale locale) throws GenericEntityException, AssetsApprovalException {
        Map<String, Object> lockFields = new HashMap<>();
        lockFields.put("lastModifiedDate", UtilDateTime.nowTimestamp());
        lockFields.put("lastModifiedByUserLogin", userLogin == null ? null : userLogin.getString("userLoginId"));
        int locked = delegator.storeByCondition("AssetsRequestDetail", lockFields,
                EntityCondition.makeCondition("requirementId", requestDetail.getString("requirementId")));
        if (locked != 1) {
            throw new AssetsApprovalException(message("AssetsErrorApprovalRequestLockFailed", locale,
                    "requirementId", requestDetail.getString("requirementId")));
        }
    }

    private static int cancelRemainingDecisions(Delegator delegator, GenericValue decision)
            throws GenericEntityException {
        EntityCondition remainingPending = EntityCondition.makeCondition(List.of(
                EntityCondition.makeCondition("requirementId", decision.getString("requirementId")),
                EntityCondition.makeCondition("assetsApprovalDecisionId", EntityOperator.NOT_EQUAL,
                        decision.getString("assetsApprovalDecisionId")),
                EntityCondition.makeCondition("statusId", STATUS_PENDING),
                EntityCondition.makeCondition("decisionEnumId", EntityOperator.EQUALS, null)
        ), EntityOperator.AND);
        return delegator.storeByCondition("AssetsApprovalDecision",
                Map.of("statusId", STATUS_CANCELLED), remainingPending);
    }

    private static long countPendingDecisions(Delegator delegator, String requirementId)
            throws GenericEntityException {
        EntityCondition condition = EntityCondition.makeCondition(List.of(
                EntityCondition.makeCondition("requirementId", requirementId),
                EntityCondition.makeCondition("statusId", STATUS_PENDING),
                EntityCondition.makeCondition("decisionEnumId", EntityOperator.EQUALS, null)
        ), EntityOperator.AND);
        return EntityQuery.use(delegator).from("AssetsApprovalDecision")
                .where(condition).cache(false).queryCount();
    }

    private static void updateRequestStatus(Delegator delegator, LocalDispatcher dispatcher,
            GenericValue requestDetail, String assetsStatusId, GenericValue userLogin, Locale locale)
            throws GenericEntityException, GenericServiceException, AssetsApprovalException {
        Map<String, Object> fields = new HashMap<>();
        fields.put("statusId", assetsStatusId);
        fields.put("lastModifiedDate", UtilDateTime.nowTimestamp());
        fields.put("lastModifiedByUserLogin", userLogin == null ? null : userLogin.getString("userLoginId"));
        int updated = delegator.storeByCondition("AssetsRequestDetail", fields,
                EntityCondition.makeCondition("requirementId", requestDetail.getString("requirementId")));
        if (updated != 1) {
            throw new AssetsApprovalException(message("AssetsErrorApprovalRequestStatusUpdateFailed", locale,
                    "requirementId", requestDetail.getString("requirementId")));
        }

        GenericValue requirement = EntityQuery.use(delegator).from("Requirement")
                .where("requirementId", requestDetail.getString("requirementId")).cache(false).queryOne();
        if (requirement == null) {
            throw new AssetsApprovalException(message("AssetsErrorApprovalRequirementMissing", locale));
        }
        String nativeStatusId = STATUS_APPROVED.equals(assetsStatusId) ? "REQ_APPROVED" : "REQ_REJECTED";
        if (!nativeStatusId.equals(requirement.getString("statusId"))) {
            Map<String, Object> updateContext = new HashMap<>();
            updateContext.put("requirementId", requirement.getString("requirementId"));
            updateContext.put("statusId", nativeStatusId);
            updateContext.put("userLogin", userLogin);
            Map<String, Object> updateResult = dispatcher.runSync("updateRequirement", updateContext);
            if (ServiceUtil.isError(updateResult)) {
                throw new AssetsApprovalException(message("AssetsErrorApprovalNativeRequirementUpdateFailed", locale,
                        "requirementId", requirement.getString("requirementId")) + " Detail: "
                        + ServiceUtil.getErrorMessage(updateResult));
            }
        }
    }

    private static Map<String, Object> successResult(GenericValue decision, GenericValue requestDetail,
            String requestedDecision, boolean dryRun, int cancelledDecisionCount, Locale locale)
            throws GenericEntityException {
        String decisionStatusId = statusForDecision(requestedDecision);
        String requestStatusId = requestDetail.getString("statusId");
        if (DECISION_REJECT.equals(requestedDecision)) {
            requestStatusId = STATUS_REJECTED;
        }
        String messageKey = dryRun ? "AssetsApprovalDecisionDryRunSuccess" : "AssetsApprovalDecisionSuccess";
        Map<String, Object> result = ServiceUtil.returnSuccess(message(messageKey, locale,
                "assetsApprovalDecisionId", decision.getString("assetsApprovalDecisionId"),
                "requirementId", decision.getString("requirementId")));
        result.put("assetsApprovalDecisionId", decision.getString("assetsApprovalDecisionId"));
        result.put("requirementId", decision.getString("requirementId"));
        result.put("decisionEnumId", requestedDecision);
        result.put("decisionStatusId", decisionStatusId);
        result.put("requestStatusId", requestStatusId);
        result.put("cancelledDecisionCount", cancelledDecisionCount);
        result.put("dryRun", dryRun ? "Y" : "N");
        return result;
    }

    private static String statusForDecision(String decisionEnumId) {
        return DECISION_APPROVE.equals(decisionEnumId) ? STATUS_APPROVED : STATUS_REJECTED;
    }

    private static boolean isDryRun(Object value) {
        return value == null || !"N".equalsIgnoreCase(value.toString());
    }

    private static String stringValue(Object value) {
        return value == null ? null : value.toString();
    }

    private static Locale locale(Map<String, ? extends Object> context) {
        Object value = context.get("locale");
        return value instanceof Locale ? (Locale) value : Locale.getDefault();
    }

    private static String message(String key, Locale locale, Object... pairs) {
        Map<String, Object> values = new HashMap<>();
        for (int i = 0; i + 1 < pairs.length; i += 2) {
            values.put(String.valueOf(pairs[i]), pairs[i + 1]);
        }
        return UtilProperties.getMessage(RESOURCE, key, values, locale);
    }

    private static final class AssetsApprovalException extends Exception {
        private AssetsApprovalException(String message) {
            super(message);
        }
    }
}
