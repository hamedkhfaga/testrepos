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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import org.apache.ofbiz.base.util.Debug;
import org.apache.ofbiz.base.util.UtilDateTime;
import org.apache.ofbiz.entity.Delegator;
import org.apache.ofbiz.entity.GenericValue;
import org.apache.ofbiz.entity.util.EntityQuery;
import org.apache.ofbiz.service.DispatchContext;
import org.apache.ofbiz.service.ServiceUtil;
import static org.apache.ofbiz.assets.fixedasset.AssetsProcurementServices.*;

/** Operational custody; native FixedAsset and PartyFixedAssetAssignment remain authoritative. */
public final class AssetsCustodyServices {
    private AssetsCustodyServices() { }
    private static final Set<String> TYPES = Set.of("ASSIGN", "TRANSFER", "RETURN", "MOVE");
    private static final Set<String> CONDITIONS = Set.of("WORKING", "NEEDS_REPAIR", "DAMAGED");
    private static final Set<String> OPEN = Set.of("SUBMITTED", "APPROVED", "HANDED_OVER");

    public static Map<String, Object> assetsGetCustodyWorkspace(DispatchContext ctx, Map<String, ?> input) {
        try {
            Delegator d = ctx.getDelegator();
            GenericValue asset = asset(ctx, required(input, "fixedAssetId"), input);
            String id = asset.getString("fixedAssetId");
            Map<String, Object> result = ServiceUtil.returnSuccess();
            result.put("asset", asset);
            result.put("assignments", assignments(d, id));
            result.put("control", EntityQuery.use(d).from("AssetsCustodyControl").where("fixedAssetId", id).queryOne());
            result.put("movements", EntityQuery.use(d).from("AssetsCustodyMovement").where("fixedAssetId", id).orderBy("-createdDate").queryList());
            result.put("events", EntityQuery.use(d).from("AssetsCustodyEvent").where("fixedAssetId", id).orderBy("-eventDate").queryList());
            result.put("facilities", EntityQuery.use(d).from("Facility").where("ownerPartyId", asset.getString("partyId")).orderBy("facilityName").queryList());
            result.put("provenance", provenance(d, asset));
            return result;
        } catch (Exception e) { return failure(e); }
    }

    public static Map<String, Object> assetsFindCustodyRecipients(DispatchContext ctx, Map<String, ?> input) {
        try {
            Delegator d = ctx.getDelegator();
            String organization = required(input, "partyId");
            AssetsRequestServices.requireOrganizationAccess(ctx, (GenericValue) input.get("userLogin"), organization);
            Set<String> groups = organizationGroups(d, organization);
            Set<String> people = new HashSet<>();
            for (String group : groups) {
                for (GenericValue employment : EntityQuery.use(d).from("Employment").where("partyIdFrom", group).filterByDate().queryList()) {
                    if (employment.get("terminationReasonId") == null && employment.get("terminationTypeId") == null) people.add(employment.getString("partyIdTo"));
                }
            }
            String query = Objects.toString(input.get("query"), "").trim().toLowerCase(java.util.Locale.ROOT);
            List<Map<String, Object>> rows = new ArrayList<>();
            for (String id : java.util.stream.Stream.concat(groups.stream(), people.stream()).distinct().sorted().toList()) {
                GenericValue party = one(d, "Party", Map.of("partyId", id));
                if ("PARTY_DISABLED".equals(party.getString("statusId"))) continue;
                if (!groups.contains(id) && !"PERSON".equals(party.getString("partyTypeId"))) continue;
                String role = groups.contains(id) ? "INTERNAL_ORGANIZATIO" : "EMPLOYEE";
                if (EntityQuery.use(d).from("PartyRole").where("partyId", id, "roleTypeId", role).queryOne() == null) continue;
                GenericValue group = EntityQuery.use(d).from("PartyGroup").where("partyId", id).queryOne();
                GenericValue person = EntityQuery.use(d).from("Person").where("partyId", id).queryOne();
                String name = group != null ? group.getString("groupName") : person == null ? id
                        : Objects.toString(person.getString("firstName"), "") + " " + Objects.toString(person.getString("lastName"), "");
                if (!query.isEmpty() && !(id + " " + name).toLowerCase(java.util.Locale.ROOT).contains(query)) continue;
                rows.add(Map.of("partyId", id, "roleTypeId", role, "name", Objects.toString(name, id), "value", id + "|" + role));
            }
            int page = input.get("viewIndex") == null ? 0 : ((Number) input.get("viewIndex")).intValue();
            int size = input.get("viewSize") == null ? 20 : ((Number) input.get("viewSize")).intValue();
            if (page < 0 || size < 1 || size > 100 || (long) page * size > Integer.MAX_VALUE) throw new IllegalArgumentException("Invalid recipient page.");
            int start = Math.min(page * size, rows.size());
            Map<String, Object> result = ServiceUtil.returnSuccess();
            result.put("rows", rows.subList(start, Math.min(start + size, rows.size())));
            result.put("total", rows.size());
            return result;
        } catch (Exception e) { return failure(e); }
    }

    public static Map<String, Object> assetsSubmitCustodyMovement(DispatchContext ctx, Map<String, ?> input) {
        try {
            Delegator d = ctx.getDelegator();
            GenericValue user = (GenericValue) input.get("userLogin");
            GenericValue asset = asset(ctx, required(input, "fixedAssetId"), input);
            String id = asset.getString("fixedAssetId"), type = required(input, "movementType");
            String condition = required(input, "conditionId");
            if (!TYPES.contains(type) || !CONDITIONS.contains(condition)) throw new IllegalArgumentException("Choose a supported movement and asset condition.");
            String comments = required(input, "comments");
            if (asset.get("actualEndOfLife") != null) throw new IllegalArgumentException("A retired asset cannot enter a custody workflow.");
            AssetsDisposalServices.requireNoOpenDisposal(d, id);
            for (GenericValue book : EntityQuery.use(d).from("AssetsBookDetail").where("fixedAssetId", id).filterByDate().queryList()) {
                if ("ASSETS_PENDING".equals(book.getString("statusId"))) throw new IllegalArgumentException("Place the capitalized asset in service before custody assignment.");
            }
            boolean preview = !"N".equals(input.get("dryRun"));
            GenericValue control = control(d, id, !preview);
            AssetsDisposalServices.requireNoOpenDisposal(d, id);
            AssetsMaintenanceServices.requireNoInProcess(d, id);
            List<GenericValue> current = assignments(d, id);
            if (current.size() > 1) throw new IllegalArgumentException("Multiple active native assignments need reconciliation before custody can move.");
            GenericValue source = current.isEmpty() ? null : current.get(0);
            String target = optional(input.get("toPartyId"));
            String targetRole = optional(input.get("toRoleTypeId"));
            validateRequest(type, source != null, source == null ? null : source.getString("partyId"),
                    source == null ? null : source.getString("roleTypeId"), target, targetRole,
                    control == null ? "AVAILABLE" : control.getString("availability"));
            ensureNoOpen(d, id);
            String facility = required(input, "toFacilityId");
            validateFacility(d, asset.getString("partyId"), facility, optional(input.get("toLocationSeqId")));
            if ("MOVE".equals(type)) {
                target = source == null ? null : source.getString("partyId");
                targetRole = source == null ? null : source.getString("roleTypeId");
                if (facility.equals(asset.getString("locatedAtFacilityId"))
                        && Objects.equals(optional(input.get("toLocationSeqId")), asset.getString("locatedAtLocationSeqId"))) {
                    throw new IllegalArgumentException("Choose a different facility or location.");
                }
            }
            if (target != null) validateRecipient(d, asset.getString("partyId"), target, targetRole);
            if (!"RETURN".equals(type) && !"WORKING".equals(condition)) throw new IllegalArgumentException("Only a working asset may be issued or transferred. Return damaged assets for inspection.");
            Map<String, Object> result = ServiceUtil.returnSuccess();
            result.put("dryRun", preview ? "Y" : "N");
            result.put("provenance", provenance(d, asset));
            if (preview) return result;
            GenericValue movement = d.makeValue("AssetsCustodyMovement");
            movement.set("assetsCustodyMovementId", d.getNextSeqId("AssetsCustodyMovement"));
            movement.set("fixedAssetId", id);
            movement.set("organizationPartyId", asset.getString("partyId"));
            movement.set("movementType", type);
            movement.set("statusId", "SUBMITTED");
            movement.set("fromPartyId", source == null ? null : source.getString("partyId"));
            movement.set("fromRoleTypeId", source == null ? null : source.getString("roleTypeId"));
            movement.set("assignmentFromDate", source == null ? null : source.getTimestamp("fromDate"));
            movement.set("fromFacilityId", asset.getString("locatedAtFacilityId"));
            movement.set("fromLocationSeqId", asset.getString("locatedAtLocationSeqId"));
            movement.set("toPartyId", target);
            movement.set("toRoleTypeId", targetRole);
            movement.set("toFacilityId", facility);
            movement.set("toLocationSeqId", optional(input.get("toLocationSeqId")));
            movement.set("conditionId", condition);
            movement.set("comments", comments);
            movement.set("requestedByUserLogin", user.getString("userLoginId"));
            movement.set("createdDate", UtilDateTime.nowTimestamp());
            movement.set("lastModifiedDate", UtilDateTime.nowTimestamp());
            movement.create();
            event(d, movement, user, "SUBMIT", null, "SUBMITTED", comments, false);
            result.put("assetsCustodyMovementId", movement.getString("assetsCustodyMovementId"));
            return result;
        } catch (Exception e) { return failure(e); }
    }

    public static Map<String, Object> assetsProcessCustodyMovement(DispatchContext ctx, Map<String, ?> input) {
        try {
            Delegator d = ctx.getDelegator();
            GenericValue user = (GenericValue) input.get("userLogin");
            GenericValue movement = one(d, "AssetsCustodyMovement", Map.of("assetsCustodyMovementId", required(input, "assetsCustodyMovementId")));
            GenericValue asset = asset(ctx, movement.getString("fixedAssetId"), input);
            GenericValue control = control(d, asset.getString("fixedAssetId"), true);
            movement.refresh();
            String action = required(input, "action"), before = movement.getString("statusId");
            String after = nextState(before, action);
            String actor = user.getString("userLoginId");
            String comments = required(input, "comments");
            boolean admin = ctx.getSecurity().hasEntityPermission("ASSETS", "_ADMIN", user);
            boolean delegated = admin && "Y".equals(input.get("onBehalf"));
            if ("APPROVE".equals(action) || "REJECT".equals(action)) {
                requireApprovalAuthority(admin || ctx.getSecurity().hasEntityPermission("ASSETS", "_APPROVE", user),
                        movement.getString("requestedByUserLogin"), actor, delegated);
                if ("APPROVE".equals(action) && (!ctx.getSecurity().hasEntityPermission("ACCOUNTING", "_CREATE", user)
                        || !ctx.getSecurity().hasEntityPermission("ACCOUNTING", "_UPDATE", user))) {
                    throw new IllegalArgumentException("The approver needs native OFBiz fixed-asset create/update authority before authorizing custody changes.");
                }
            } else if ("CANCEL".equals(action)) {
                if (!actor.equals(movement.getString("requestedByUserLogin")) && !admin) throw new IllegalArgumentException("Only the requester or Assets administrator may cancel.");
            } else if ("HANDOVER".equals(action)) {
                String sender = movement.getString("fromPartyId");
                if (sender != null && !sender.equals(user.getString("partyId")) && !delegated) throw new IllegalArgumentException("Handover must be recorded by the current custodian or an attesting administrator.");
                if (sender == null && !admin && !ctx.getSecurity().hasEntityPermission("ASSETS", "_UPDATE", user)) throw new IllegalArgumentException("Assets issue permission is required.");
            } else if ("ACKNOWLEDGE".equals(action)) {
                String recipient = movement.getString("toPartyId");
                requireReceiptActor(recipient, user.getString("partyId"), delegated);
            }
            if (!List.of("REJECT", "CANCEL").contains(action)) validateSnapshot(d, movement, asset);
            Timestamp now = UtilDateTime.nowTimestamp();
            if ("ACKNOWLEDGE".equals(action)) {
                String receivedCondition = required(input, "conditionId");
                if (!CONDITIONS.contains(receivedCondition)) throw new IllegalArgumentException("Record the condition at receipt.");
                complete(ctx, movement, asset, user, now);
                control.set("conditionId", receivedCondition);
                control.set("availability", "WORKING".equals(receivedCondition)
                        ? ("RETURN".equals(movement.getString("movementType")) || movement.get("toPartyId") == null ? "AVAILABLE" : "ASSIGNED") : "QUARANTINE");
                control.store();
                movement.set("receivedConditionId", receivedCondition);
                movement.set("acknowledgedDate", now);
                movement.set("acknowledgedByUserLogin", actor);
            } else if ("APPROVE".equals(action)) {
                movement.set("approvedDate", now); movement.set("approvedByUserLogin", actor);
            } else if ("HANDOVER".equals(action)) {
                movement.set("handoverDate", now); movement.set("handoverByUserLogin", actor);
            }
            movement.set("statusId", after);
            movement.set("lastModifiedDate", now);
            movement.store();
            event(d, movement, user, action, before, after, comments, delegated);
            Map<String, Object> result = ServiceUtil.returnSuccess();
            result.put("statusId", after);
            result.put("assetsCustodyMovementId", movement.getString("assetsCustodyMovementId"));
            return result;
        } catch (Exception e) { return failure(e); }
    }

    public static Map<String, Object> assetsReleaseCustodyQuarantine(DispatchContext ctx, Map<String, ?> input) {
        try {
            Delegator d = ctx.getDelegator();
            GenericValue asset = asset(ctx, required(input, "fixedAssetId"), input);
            GenericValue user = (GenericValue) input.get("userLogin");
            GenericValue control = control(d, asset.getString("fixedAssetId"), true);
            ensureNoOpen(d, asset.getString("fixedAssetId"));
            if (!"QUARANTINE".equals(control.getString("availability")) || !assignments(d, asset.getString("fixedAssetId")).isEmpty()) {
                throw new IllegalArgumentException("Only an unassigned asset in quarantine can be released after inspection.");
            }
            String comments = required(input, "comments");
            control.set("availability", "AVAILABLE"); control.set("conditionId", "WORKING"); control.store();
            GenericValue e = d.makeValue("AssetsCustodyEvent");
            e.set("assetsCustodyEventId", d.getNextSeqId("AssetsCustodyEvent"));
            e.set("fixedAssetId", asset.getString("fixedAssetId"));
            e.set("action", "RELEASE"); e.set("fromStatusId", "QUARANTINE"); e.set("toStatusId", "AVAILABLE");
            e.set("actorUserLoginId", user.getString("userLoginId")); e.set("comments", comments);
            e.set("eventDate", UtilDateTime.nowTimestamp()); e.create();
            return ServiceUtil.returnSuccess();
        } catch (Exception e) { return failure(e); }
    }

    static String nextState(String state, String action) {
        if ("APPROVE".equals(action) && "SUBMITTED".equals(state)) return "APPROVED";
        if ("REJECT".equals(action) && Set.of("SUBMITTED", "APPROVED").contains(state)) return "REJECTED";
        if ("CANCEL".equals(action) && Set.of("SUBMITTED", "APPROVED").contains(state)) return "CANCELLED";
        if ("HANDOVER".equals(action) && "APPROVED".equals(state)) return "HANDED_OVER";
        if ("ACKNOWLEDGE".equals(action) && "HANDED_OVER".equals(state)) return "COMPLETED";
        throw new IllegalArgumentException("This custody action is not allowed in the current state.");
    }

    static void requireApprovalAuthority(boolean authorized, String requester, String actor, boolean delegated) {
        if (!authorized) throw new IllegalArgumentException("Assets approval permission is required.");
        if (Objects.equals(requester, actor) && !delegated) throw new IllegalArgumentException("Another approver is required; administrators may explicitly attest approval on behalf with a reason.");
    }

    static void requireReceiptActor(String recipient, String actorParty, boolean delegated) {
        if ((recipient == null || !recipient.equals(actorParty)) && !delegated) {
            throw new IllegalArgumentException("The receiving custodian must acknowledge; department/store receipt needs an attesting administrator.");
        }
    }

    static void validateRequest(String type, boolean assigned, String currentParty, String currentRole,
            String target, String targetRole, String availability) {
        if (!TYPES.contains(type)) throw new IllegalArgumentException("Unsupported custody movement.");
        if ("QUARANTINE".equals(availability) && !"RETURN".equals(type)) throw new IllegalArgumentException("Asset is quarantined; record inspection release before issue or transfer.");
        if ("ASSIGN".equals(type) && assigned) throw new IllegalArgumentException("The asset is already assigned; use transfer or return.");
        if (Set.of("TRANSFER", "RETURN").contains(type) && !assigned) throw new IllegalArgumentException("This action requires a current custodian.");
        if (Set.of("ASSIGN", "TRANSFER").contains(type) && (target == null || targetRole == null)) throw new IllegalArgumentException("Choose a receiving employee or department.");
        if ("RETURN".equals(type) && (target != null || targetRole != null)) throw new IllegalArgumentException("Returns close custody; a new custodian requires a separate assignment.");
        if ("TRANSFER".equals(type) && Objects.equals(currentParty, target) && Objects.equals(currentRole, targetRole)) throw new IllegalArgumentException("A transfer requires a different custodian.");
    }

    private static GenericValue asset(DispatchContext ctx, String id, Map<String, ?> input) throws Exception {
        GenericValue value = one(ctx.getDelegator(), "FixedAsset", Map.of("fixedAssetId", id));
        if (value.get("partyId") == null) throw new IllegalArgumentException("The asset must have an owning organization.");
        AssetsRequestServices.requireOrganizationAccess(ctx, (GenericValue) input.get("userLogin"), value.getString("partyId"));
        return value;
    }

    static GenericValue control(Delegator d, String id, boolean lock) throws Exception {
        GenericValue value = EntityQuery.use(d).from("AssetsCustodyControl").where("fixedAssetId", id).queryOne();
        if (!lock) return value;
        if (value == null) {
            value = d.makeValue("AssetsCustodyControl", Map.of("fixedAssetId", id, "availability", assignments(d, id).isEmpty() ? "AVAILABLE" : "ASSIGNED", "conditionId", "UNKNOWN", "lastModifiedDate", UtilDateTime.nowTimestamp()));
            value.create();
        }
        lockRecord(value);
        return value;
    }

    private static List<GenericValue> assignments(Delegator d, String id) throws Exception {
        return EntityQuery.use(d).from("PartyFixedAssetAssignment").where("fixedAssetId", id).filterByDate().queryList();
    }

    private static void ensureNoOpen(Delegator d, String id) throws Exception {
        for (GenericValue m : EntityQuery.use(d).from("AssetsCustodyMovement").where("fixedAssetId", id).queryList()) {
            if (OPEN.contains(m.getString("statusId"))) throw new IllegalArgumentException("Complete or cancel the asset's existing custody movement first.");
        }
    }

    private static Set<String> organizationGroups(Delegator d, String organization) throws Exception {
        Set<String> found = new HashSet<>();
        List<String> pending = new ArrayList<>(List.of(organization));
        for (int i = 0; i < pending.size(); i++) {
            String parent = pending.get(i);
            if (!found.add(parent)) continue;
            if (found.size() > 2000) throw new IllegalArgumentException("Organization hierarchy is too large; narrow the operating organization.");
            for (GenericValue relation : EntityQuery.use(d).from("PartyRelationship")
                    .where("partyIdFrom", parent, "partyRelationshipTypeId", "GROUP_ROLLUP").filterByDate().queryList()) {
                String child = relation.getString("partyIdTo");
                if (EntityQuery.use(d).from("PartyGroup").where("partyId", child).queryOne() != null && !found.contains(child)) pending.add(child);
            }
        }
        return found;
    }

    private static void validateRecipient(Delegator d, String organization, String party, String role) throws Exception {
        one(d, "PartyRole", Map.of("partyId", party, "roleTypeId", role));
        GenericValue target = one(d, "Party", Map.of("partyId", party));
        if ("PARTY_DISABLED".equals(target.getString("statusId"))) throw new IllegalArgumentException("The recipient is disabled.");
        Set<String> groups = organizationGroups(d, organization);
        if ("INTERNAL_ORGANIZATIO".equals(role) && groups.contains(party)) return;
        if ("EMPLOYEE".equals(role) && "PERSON".equals(target.getString("partyTypeId"))) {
            for (String group : groups) {
                for (GenericValue employment : EntityQuery.use(d).from("Employment").where("partyIdFrom", group, "partyIdTo", party).filterByDate().queryList()) {
                    if (employment.get("terminationReasonId") == null && employment.get("terminationTypeId") == null) return;
                }
            }
        }
        throw new IllegalArgumentException("Custodians must be active employees or departments within the asset organization.");
    }

    private static void validateFacility(Delegator d, String organization, String facility, String location) throws Exception {
        if (!organization.equals(one(d, "Facility", Map.of("facilityId", facility)).getString("ownerPartyId"))) throw new IllegalArgumentException("The destination facility must belong to the asset organization.");
        if (location != null) one(d, "FacilityLocation", Map.of("facilityId", facility, "locationSeqId", location));
    }

    private static void validateSnapshot(Delegator d, GenericValue m, GenericValue asset) throws Exception {
        asset.refresh();
        if (asset.get("actualEndOfLife") != null) throw new IllegalArgumentException("A retired asset cannot move through an operational custody workflow.");
        AssetsDisposalServices.requireNoOpenDisposal(d, asset.getString("fixedAssetId"));
        if (!m.getString("organizationPartyId").equals(asset.getString("partyId"))) throw new IllegalArgumentException("Asset ownership changed; reconcile the movement.");
        List<GenericValue> current = assignments(d, asset.getString("fixedAssetId"));
        if (current.size() != (m.get("fromPartyId") == null ? 0 : 1)) throw new IllegalArgumentException("Native custody changed after submission.");
        if (!current.isEmpty()) {
            GenericValue a = current.get(0);
            if (!Objects.equals(a.get("partyId"), m.get("fromPartyId")) || !Objects.equals(a.get("roleTypeId"), m.get("fromRoleTypeId"))
                    || !Objects.equals(a.get("fromDate"), m.get("assignmentFromDate"))) throw new IllegalArgumentException("Native custodian changed after submission.");
        }
        if (!Objects.equals(asset.get("locatedAtFacilityId"), m.get("fromFacilityId"))
                || !Objects.equals(asset.get("locatedAtLocationSeqId"), m.get("fromLocationSeqId"))) throw new IllegalArgumentException("Native location changed after submission.");
        validateFacility(d, m.getString("organizationPartyId"), m.getString("toFacilityId"), m.getString("toLocationSeqId"));
        if (m.get("toPartyId") != null) validateRecipient(d, m.getString("organizationPartyId"), m.getString("toPartyId"), m.getString("toRoleTypeId"));
    }

    private static void complete(DispatchContext ctx, GenericValue m, GenericValue asset, GenericValue user, Timestamp now) throws Exception {
        // The prior approval authorizes the native mutations. Keep native service
        // permissions under that active approver; record the actual receiver in
        // the immutable custody event rather than granting receivers Finance access.
        GenericValue executor = one(ctx.getDelegator(), "UserLogin", Map.of("userLoginId", m.getString("approvedByUserLogin")));
        if ("N".equals(executor.getString("enabled"))
                || !(ctx.getSecurity().hasEntityPermission("ASSETS", "_APPROVE", executor)
                || ctx.getSecurity().hasEntityPermission("ASSETS", "_ADMIN", executor))) {
            throw new IllegalArgumentException("The authorizing approver must remain active and authorized.");
        }
        AssetsRequestServices.requireOrganizationAccess(ctx, executor, m.getString("organizationPartyId"));
        if (!"MOVE".equals(m.getString("movementType"))) {
            if (m.get("fromPartyId") != null) call(ctx, "updatePartyFixedAssetAssignment", Map.of("fixedAssetId", asset.getString("fixedAssetId"),
                    "partyId", m.getString("fromPartyId"), "roleTypeId", m.getString("fromRoleTypeId"), "fromDate", m.getTimestamp("assignmentFromDate"), "thruDate", now, "userLogin", executor));
            if (m.get("toPartyId") != null) call(ctx, "createPartyFixedAssetAssignment", Map.of("fixedAssetId", asset.getString("fixedAssetId"),
                    "partyId", m.getString("toPartyId"), "roleTypeId", m.getString("toRoleTypeId"), "fromDate", now, "allocatedDate", now,
                    "comments", "Assets custody " + m.getString("assetsCustodyMovementId"), "userLogin", executor));
        }
        Map<String, Object> update = new HashMap<>();
        update.put("fixedAssetId", asset.getString("fixedAssetId")); update.put("fixedAssetTypeId", asset.getString("fixedAssetTypeId"));
        update.put("locatedAtFacilityId", m.getString("toFacilityId")); update.put("locatedAtLocationSeqId", m.get("toLocationSeqId"));
        update.put("userLogin", executor);
        call(ctx, "updateFixedAsset", update);
        GenericValue lifecycle = ctx.getDelegator().makeValue("AssetsLifecycleEvent");
        lifecycle.set("assetsLifecycleEventId", ctx.getDelegator().getNextSeqId("AssetsLifecycleEvent"));
        lifecycle.set("fixedAssetId", asset.getString("fixedAssetId")); lifecycle.set("eventTypeEnumId", "ASSETS_EVT_TRANSFER");
        lifecycle.set("statusId", "ASSETS_ACTIVE"); lifecycle.set("eventDate", now); lifecycle.set("createdDate", now);
        lifecycle.set("createdByUserLogin", user.getString("userLoginId")); lifecycle.set("facilityId", m.getString("toFacilityId"));
        lifecycle.set("description", "Custody " + m.getString("movementType") + " " + m.getString("assetsCustodyMovementId")); lifecycle.create();
    }

    static Map<String, Object> provenance(Delegator d, GenericValue asset) throws Exception {
        List<GenericValue> caps = EntityQuery.use(d).from("AssetsCapitalization").where("fixedAssetId", asset.getString("fixedAssetId")).queryList();
        for (GenericValue cap : caps) {
            if (cap.get("requirementId") == null || cap.get("receiptId") == null || cap.get("invoiceId") == null || cap.get("orderId") == null) continue;
            GenericValue request = EntityQuery.use(d).from("AssetsRequestDetail").where("requirementId", cap.getString("requirementId")).queryOne();
            GenericValue receipt = EntityQuery.use(d).from("ShipmentReceipt").where("receiptId", cap.getString("receiptId")).queryOne();
            GenericValue journal = EntityQuery.use(d).from("AcctgTrans").where("acctgTransId", cap.getString("acctgTransId")).queryOne();
            if (request == null || request.get("submissionHash") == null || receipt == null || journal == null || !"Y".equals(journal.getString("isPosted"))) continue;
            if (!"ASSETS_POSTED".equals(cap.getString("statusId"))) continue;
            GenericValue commitment = EntityQuery.use(d).from("OrderRequirementCommitment").where("requirementId", cap.get("requirementId"),
                    "orderId", cap.get("orderId"), "orderItemSeqId", cap.get("orderItemSeqId")).queryOne();
            GenericValue billing = EntityQuery.use(d).from("OrderItemBilling").where("orderId", cap.get("orderId"), "orderItemSeqId", cap.get("orderItemSeqId"),
                    "invoiceId", cap.get("invoiceId"), "invoiceItemSeqId", cap.get("invoiceItemSeqId")).queryOne();
            GenericValue creation = EntityQuery.use(d).from("AssetsLifecycleEvent").where("fixedAssetId", asset.getString("fixedAssetId"),
                    "eventTypeEnumId", "ASSETS_EVT_CAPITAL", "acctgTransId", cap.get("acctgTransId")).queryFirst();
            if (commitment == null || billing == null || creation == null || !Objects.equals(billing.get("shipmentReceiptId"), cap.get("receiptId"))) continue;
            if (!Objects.equals(asset.get("acquireOrderId"), cap.get("orderId")) || !Objects.equals(receipt.get("orderId"), cap.get("orderId"))
                    || !Objects.equals(asset.get("partyId"), request.get("partyId"))) continue;
            Map<String, Object> proof = new LinkedHashMap<>();
            proof.put("source", "ASSETS_MANAGEMENT"); proof.put("verified", "Y");
            for (String field : List.of("requirementId", "orderId", "orderItemSeqId", "receiptId", "invoiceId", "acctgTransId", "assetsCapitalizationId", "createdByUserLogin")) proof.put(field, cap.get(field));
            return proof;
        }
        return Map.of("source", "UNVERIFIED", "verified", "N");
    }

    private static void event(Delegator d, GenericValue m, GenericValue user, String action, String before, String after, String comments, boolean delegated) throws Exception {
        GenericValue e = d.makeValue("AssetsCustodyEvent"); e.set("assetsCustodyEventId", d.getNextSeqId("AssetsCustodyEvent"));
        e.set("assetsCustodyMovementId", m.getString("assetsCustodyMovementId")); e.set("fixedAssetId", m.getString("fixedAssetId"));
        e.set("action", action); e.set("fromStatusId", before); e.set("toStatusId", after); e.set("comments", comments);
        e.set("onBehalf", delegated ? "Y" : "N"); e.set("actorUserLoginId", user.getString("userLoginId")); e.set("eventDate", UtilDateTime.nowTimestamp()); e.create();
    }
    private static String optional(Object value) { return value == null || value.toString().isBlank() ? null : value.toString().trim(); }
    private static Map<String, Object> failure(Exception e) {
        Debug.logError(e, "Assets custody transaction failed", AssetsCustodyServices.class.getName());
        return ServiceUtil.returnError(e instanceof IllegalArgumentException ? e.getMessage() : "Custody processing failed. No partial custody or location changes were committed; check the native service log.");
    }
}
