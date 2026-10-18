/* Licensed under the Apache License, Version 2.0. */
package org.apache.ofbiz.assets.fixedasset;

import java.sql.Timestamp;
import java.util.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import org.apache.ofbiz.base.util.UtilDateTime;
import org.apache.ofbiz.base.util.Debug;
import org.apache.ofbiz.entity.*;
import org.apache.ofbiz.entity.util.EntityQuery;
import org.apache.ofbiz.service.*;
import static org.apache.ofbiz.assets.fixedasset.AssetsProcurementServices.*;

/** Physical verification evidence. Native assets, custody and Finance remain authoritative. */
public final class AssetsVerificationServices {
    private AssetsVerificationServices() { }

    public static Map<String,Object> assetsGetVerification(DispatchContext c, Map<String,?> in) {
        try {
            Delegator d=c.getDelegator(); String id=text(in,"assetsVerificationId");
            Map<String,Object> out=ServiceUtil.returnSuccess();
            if(id!=null) {
                GenericValue h=header(c,in,false); out.put("campaign",h);
                List<Map<String,Object>> rows=new ArrayList<>();
                for(GenericValue line:lines(d,id)) {
                    Map<String,Object> row=new HashMap<>(line.getAllFields());
                    if(line.get("fixedAssetId")!=null) {
                        GenericValue a=one(d,"FixedAsset",Map.of("fixedAssetId",line.get("fixedAssetId")));
                        // Do not expose a transferred asset's current data outside the campaign organization.
                        if(h.get("partyId").equals(a.get("partyId"))) {
                            row.put("currentFingerprint",fingerprint(d,a));
                            row.put("provenance",AssetsCustodyServices.provenance(d,a));
                        } else row.put("currentFingerprint","OWNERSHIP_CHANGED");
                    }
                    rows.add(row);
                }
                out.put("rows",rows); out.put("events",EntityQuery.use(d).from("AssetsVerificationEvent").where("assetsVerificationId",id).orderBy("eventDate","assetsVerificationEventId").queryList());
            } else {
                String org=required(in,"partyId"); access(c,in,org);
                List<GenericValue> all=EntityQuery.use(d).from("AssetsVerification").where("partyId",org).orderBy("-createdDate").queryList();
                int page=in.get("viewIndex")==null?0:((Number)in.get("viewIndex")).intValue();
                int size=in.get("viewSize")==null?20:((Number)in.get("viewSize")).intValue();
                if(page<0||size<1||size>100||(long)page*size>Integer.MAX_VALUE)throw new IllegalArgumentException("Invalid campaign page.");
                int start=Math.min(page*size,all.size()); out.put("campaigns",all.subList(start,Math.min(start+size,all.size())));out.put("total",all.size());
            }
            return out;
        }catch(Exception e){return failure(e);}
    }

    public static Map<String,Object> assetsCreateVerification(DispatchContext c, Map<String,?> in) {
        try {
            Delegator d=c.getDelegator(); String org=required(in,"partyId");access(c,in,org);
            String title=required(in,"campaignName"),reason=required(in,"comments"),key=required(in,"submissionKey");
            if(title.length()>100||key.length()>60)throw new IllegalArgumentException("Campaign title/key is too long.");
            String facility=text(in,"facilityId"),custodian=text(in,"custodianPartyId");
            if(facility!=null)validateLocation(d,org,facility,null);
            if(custodian!=null)one(d,"Party",Map.of("partyId",custodian));
            List<String> selected=new ArrayList<>();
            if(in.get("fixedAssetIds") instanceof List<?> list)for(Object value:list){String s=String.valueOf(value).trim();if(!s.isEmpty())selected.add(s);}
            selected=selected.stream().distinct().sorted().toList();
            String signature=hash(Arrays.asList(org,title,facility,custodian,selected,reason).toString());
            GenericValue old=EntityQuery.use(d).from("AssetsVerification").where("partyId",org,"submissionKey",key).queryOne();
            if(old!=null){if(!signature.equals(old.getString("requestFingerprint")))throw new IllegalArgumentException("Submission key already used with other campaign details.");return response(old,"Y");}
            List<GenericValue> eligible=new ArrayList<>();
            for(GenericValue a:EntityQuery.use(d).from("FixedAsset").where("partyId",org).orderBy("fixedAssetId").queryList()) {
                if(a.get("actualEndOfLife")!=null||facility!=null&&!facility.equals(a.getString("locatedAtFacilityId")))continue;
                if(custodian!=null&&!assignments(d,a.getString("fixedAssetId")).stream().anyMatch(x->custodian.equals(x.getString("partyId"))))continue;
                if(!selected.isEmpty()&&!selected.contains(a.getString("fixedAssetId")))continue;
                eligible.add(a);
            }
            if(eligible.isEmpty()||eligible.size()>1000||!selected.isEmpty()&&eligible.size()!=selected.size())throw new IllegalArgumentException("Select 1–1000 active assets in this organization and scope. Every selected asset must qualify.");
            GenericValue h=d.makeValue("AssetsVerification");h.set("assetsVerificationId",d.getNextSeqId("AssetsVerification"));h.set("partyId",org);h.set("campaignName",title);h.set("facilityId",facility);h.set("custodianPartyId",custodian);h.set("statusId","COUNTING");h.set("submissionKey",key);h.set("requestFingerprint",signature);h.set("createdByUserLogin",user(in));h.set("createdDate",UtilDateTime.nowTimestamp());h.create();
            for(GenericValue a:eligible) {
                GenericValue line=newLine(d,h,a.getString("fixedAssetId"),a.getString("fixedAssetId"),"Y");
                line.set("assetName",a.get("fixedAssetName"));line.set("expectedFacilityId",a.get("locatedAtFacilityId"));line.set("expectedLocationSeqId",a.get("locatedAtLocationSeqId"));
                List<GenericValue> custodians=assignments(d,a.getString("fixedAssetId"));
                if(custodians.size()==1){line.set("expectedCustodianPartyId",custodians.get(0).get("partyId"));line.set("expectedRoleTypeId",custodians.get(0).get("roleTypeId"));}
                GenericValue condition=EntityQuery.use(d).from("AssetsCustodyControl").where("fixedAssetId",a.get("fixedAssetId")).queryOne();if(condition!=null)line.set("expectedConditionId",condition.get("conditionId"));line.set("assignmentCount",(long)custodians.size());line.set("snapshotFingerprint",fingerprint(d,a));line.create();
            }
            event(d,h,null,in,"CREATE",reason);return response(h,"N");
        }catch(Exception e){return failure(e);}
    }

    public static Map<String,Object> assetsRecordVerificationCount(DispatchContext c, Map<String,?> in) {
        try {
            GenericValue h=header(c,in,true);requireState(h,"COUNTING");Delegator d=c.getDelegator();
            String scan=required(in,"scanCode").trim(),finding=required(in,"finding"),condition=required(in,"conditionId"),comments=required(in,"comments");
            validateCount(finding,condition);if(scan.length()>100)throw new IllegalArgumentException("Scan code is too long.");
            String facility=text(in,"observedFacilityId"),location=text(in,"observedLocationSeqId"),custodian=text(in,"observedCustodianPartyId");
            if("MISSING".equals(finding)&&(facility!=null||location!=null||custodian!=null))throw new IllegalArgumentException("A missing asset cannot have an observed location or custodian.");
            validateLocation(d,h.getString("partyId"),facility,location);
            if(custodian!=null)one(d,"Party",Map.of("partyId",custodian));
            Set<String> matches=new TreeSet<>();
            GenericValue direct=EntityQuery.use(d).from("FixedAsset").where("fixedAssetId",scan,"partyId",h.get("partyId")).queryOne();if(direct!=null)matches.add(scan);
            for(GenericValue a:EntityQuery.use(d).from("FixedAsset").where("serialNumber",scan,"partyId",h.get("partyId")).queryList())matches.add(a.getString("fixedAssetId"));
            for(GenericValue ident:EntityQuery.use(d).from("FixedAssetIdent").where("idValue",scan).queryList()) {
                GenericValue a=one(d,"FixedAsset",Map.of("fixedAssetId",ident.get("fixedAssetId")));if(h.get("partyId").equals(a.get("partyId")))matches.add(a.getString("fixedAssetId"));
            }
            if(matches.size()>1)throw new IllegalArgumentException("Ambiguous scan: use the unique asset ID.");
            String assetId=matches.isEmpty()?null:matches.iterator().next();
            GenericValue line=EntityQuery.use(d).from("AssetsVerificationLine").where("assetsVerificationId",h.get("assetsVerificationId"),assetId==null?"scanCode":"fixedAssetId",assetId==null?scan:assetId).queryOne();
            if(line==null) {
                if(!"FOUND".equals(finding))throw new IllegalArgumentException("Only a found unexpected item can be added.");
                line=newLine(d,h,assetId,scan,"N");line.create();
            }
            String sig=hash(Arrays.asList(scan,finding,condition,facility,location,custodian,comments).toString());
            if(line.get("countedDate")!=null){if(sig.equals(line.getString("countFingerprint")))return lineResponse(line,"Y");throw new IllegalArgumentException("Already counted. Reopen this line with a documented reason before recounting.");}
            line.set("finding",finding);line.set("conditionId",condition);line.set("observedFacilityId",facility);line.set("observedLocationSeqId",location);line.set("observedCustodianPartyId",custodian);line.set("countFingerprint",sig);line.set("countedByUserLogin",user(in));line.set("countedDate",UtilDateTime.nowTimestamp());line.set("countComments",comments);
            boolean match="Y".equals(line.getString("expected"))&&"FOUND".equals(finding)&&"WORKING".equals(condition)&&Objects.equals(facility,line.get("expectedFacilityId"))&&Objects.equals(location,line.get("expectedLocationSeqId"))&&Objects.equals(custodian,line.get("expectedCustodianPartyId"))&&((Number)line.get("assignmentCount")).longValue()<=1&&(line.get("expectedConditionId")==null||condition.equals(line.get("expectedConditionId")));
            line.set("discrepancy",match?"N":"Y");line.store();event(d,h,line,in,"COUNT",comments);return lineResponse(line,"N");
        }catch(Exception e){return failure(e);}
    }

    public static Map<String,Object> assetsProcessVerification(DispatchContext c, Map<String,?> in) {
        try {
            GenericValue h=header(c,in,true);Delegator d=c.getDelegator();String action=required(in,"action"),comments=required(in,"comments");
            if("SUBMIT".equals(action)) {
                requireState(h,"COUNTING");for(GenericValue l:lines(d,h.getString("assetsVerificationId")))if(l.get("countedDate")==null)throw new IllegalArgumentException("Explicitly count every expected asset, including missing findings, before submission.");
                h.set("statusId","REVIEW");h.set("submittedDate",UtilDateTime.nowTimestamp());
            } else if("RECOUNT".equals(action)) {
                requireState(h,"COUNTING");GenericValue l=one(d,"AssetsVerificationLine",Map.of("assetsVerificationId",h.get("assetsVerificationId"),"assetsVerificationLineId",required(in,"assetsVerificationLineId")));
                if(l.get("countedDate")==null)throw new IllegalArgumentException("This line has not been counted.");
                for(String field:List.of("finding","conditionId","observedFacilityId","observedLocationSeqId","observedCustodianPartyId","countFingerprint","countedByUserLogin","countedDate","countComments","discrepancy"))l.set(field,null);l.store();event(d,h,l,in,"RECOUNT",comments);return response(h,"N");
            } else if("CLOSE".equals(action)) {
                requireState(h,"REVIEW");approve(c,in,h.getString("createdByUserLogin"));int exceptions=0;
                for(GenericValue l:lines(d,h.getString("assetsVerificationId"))) {
                    if(l.get("decision")==null)throw new IllegalArgumentException("Review every count line before closing the campaign.");
                    if("EXCEPTION".equals(l.getString("decision")))exceptions++;
                    else validateResolved(d,h,l,l.getString("decision"),l.getString("assetsCustodyMovementId"));
                }
                h.set("statusId",exceptions>0?"CLOSED_EXCEPTIONS":"CLOSED");h.set("exceptionCount",(long)exceptions);h.set("closedDate",UtilDateTime.nowTimestamp());h.set("closedByUserLogin",user(in));
            } else if("CANCEL".equals(action)) {
                requireState(h,"COUNTING");approve(c,in,h.getString("createdByUserLogin"));h.set("statusId","CANCELLED");
            } else throw new IllegalArgumentException("Unknown campaign action.");
            h.store();event(d,h,null,in,action,comments);return response(h,"N");
        }catch(Exception e){return failure(e);}
    }

    public static Map<String,Object> assetsReviewVerificationLine(DispatchContext c,Map<String,?> in) {
        try {
            GenericValue h=header(c,in,true);requireState(h,"REVIEW");Delegator d=c.getDelegator();
            GenericValue l=one(d,"AssetsVerificationLine",Map.of("assetsVerificationId",h.get("assetsVerificationId"),"assetsVerificationLineId",required(in,"assetsVerificationLineId")));
            approve(c,in,l.getString("countedByUserLogin"));String decision=required(in,"decision"),comments=required(in,"comments"),movement=text(in,"assetsCustodyMovementId");
            if(l.get("decision")!=null)throw new IllegalArgumentException("This line is already reviewed.");
            if(!Set.of("CONFIRMED","CORRECTED","EXCEPTION").contains(decision))throw new IllegalArgumentException("Select a valid review decision.");
            if(!"CORRECTED".equals(decision)&&movement!=null)throw new IllegalArgumentException("Link a movement only for a completed correction.");if(!"EXCEPTION".equals(decision))validateResolved(d,h,l,decision,movement);
            l.set("decision",decision);l.set("reviewComments",comments);l.set("reviewedByUserLogin",user(in));l.set("reviewedDate",UtilDateTime.nowTimestamp());l.set("reviewedOnBehalf","Y".equals(in.get("onBehalf"))?"Y":"N");l.set("assetsCustodyMovementId",movement);l.store();event(d,h,l,in,"REVIEW_"+decision,comments);return lineResponse(l,"N");
        }catch(Exception e){return failure(e);}
    }

    static void validateCount(String finding,String condition) {
        if(!Set.of("FOUND","MISSING").contains(finding)||!Set.of("WORKING","NEEDS_REPAIR","DAMAGED","UNKNOWN").contains(condition)||"MISSING".equals(finding)&&!"UNKNOWN".equals(condition))throw new IllegalArgumentException("Record FOUND with condition, or MISSING with UNKNOWN condition.");
    }
    private static void validateResolved(Delegator d,GenericValue h,GenericValue l,String decision,String movement)throws Exception {
        if(l.get("fixedAssetId")==null)throw new IllegalArgumentException("Unregistered observations remain exceptions until a later verified campaign.");
        GenericValue a=one(d,"FixedAsset",Map.of("fixedAssetId",l.get("fixedAssetId")));AssetsCustodyServices.control(d,a.getString("fixedAssetId"),true);a.refresh();
        if(!h.get("partyId").equals(a.get("partyId"))||a.get("actualEndOfLife")!=null)throw new IllegalArgumentException("Ownership/retirement changed; retain an explicit exception.");
        if("CONFIRMED".equals(decision)) {
            if(!"N".equals(l.getString("discrepancy"))||!Objects.equals(l.get("snapshotFingerprint"),fingerprint(d,a)))throw new IllegalArgumentException("Only an unchanged matching asset can be confirmed. Review the variance or complete a custody correction.");
        } else {
            if(!"FOUND".equals(l.getString("finding"))||!"WORKING".equals(l.getString("conditionId"))||movement==null)throw new IllegalArgumentException("Corrected findings require a found working asset and a completed custody movement.");
            GenericValue m=one(d,"AssetsCustodyMovement",Map.of("assetsCustodyMovementId",movement));
            if(!a.get("fixedAssetId").equals(m.get("fixedAssetId"))||!"COMPLETED".equals(m.getString("statusId"))||m.getTimestamp("acknowledgedDate").before(h.getTimestamp("createdDate")))throw new IllegalArgumentException("Use this asset's completed custody correction acknowledged during this campaign.");
            GenericValue currentCondition=one(d,"AssetsCustodyControl",Map.of("fixedAssetId",a.get("fixedAssetId")));if(!"WORKING".equals(m.getString("receivedConditionId"))||!"WORKING".equals(currentCondition.getString("conditionId"))||"QUARANTINE".equals(currentCondition.getString("availability")))throw new IllegalArgumentException("Native condition changed; keep the unresolved exception.");List<GenericValue> assignments=assignments(d,a.getString("fixedAssetId"));String custodian=assignments.size()==1?assignments.get(0).getString("partyId"):null;
            if(assignments.size()>1||!Objects.equals(l.get("observedFacilityId"),a.get("locatedAtFacilityId"))||!Objects.equals(l.get("observedLocationSeqId"),a.get("locatedAtLocationSeqId"))||!Objects.equals(l.get("observedCustodianPartyId"),custodian))throw new IllegalArgumentException("Current native location/custody still differs from the physical finding.");
        }
    }
    private static GenericValue header(DispatchContext c,Map<String,?> in,boolean lock)throws Exception {
        GenericValue h=one(c.getDelegator(),"AssetsVerification",Map.of("assetsVerificationId",required(in,"assetsVerificationId")));access(c,in,h.getString("partyId"));
        if(lock){h.set("lastUpdatedStamp",UtilDateTime.nowTimestamp());h.store();h.refresh();}return h;
    }
    private static void access(DispatchContext c,Map<String,?> in,String org)throws Exception{AssetsRequestServices.requireOrganizationAccess(c,(GenericValue)in.get("userLogin"),org);}
    private static void approve(DispatchContext c,Map<String,?> in,String maker)throws Exception {
        GenericValue u=(GenericValue)in.get("userLogin");boolean admin=c.getSecurity().hasEntityPermission("ASSETS","_ADMIN",u);
        if(!admin&&!c.getSecurity().hasEntityPermission("ASSETS","_APPROVE",u))throw new IllegalArgumentException("Assets approval permission is required.");
        AssetsDisposalServices.requireSeparateApprover(maker,user(in),admin,"Y".equals(in.get("onBehalf")));
    }
    private static List<GenericValue> lines(Delegator d,String id)throws Exception{return EntityQuery.use(d).from("AssetsVerificationLine").where("assetsVerificationId",id).orderBy("assetsVerificationLineId").queryList();}
    private static List<GenericValue> assignments(Delegator d,String id)throws Exception{return EntityQuery.use(d).from("PartyFixedAssetAssignment").where("fixedAssetId",id).filterByDate().orderBy("partyId","roleTypeId","fromDate").queryList();}
    private static String fingerprint(Delegator d,GenericValue a)throws Exception {
        List<Object> values=new ArrayList<>();for(String f:List.of("fixedAssetId","partyId","locatedAtFacilityId","locatedAtLocationSeqId","serialNumber","actualEndOfLife"))values.add(a.get(f));
        for(GenericValue x:assignments(d,a.getString("fixedAssetId")))values.add(Arrays.asList(x.get("partyId"),x.get("roleTypeId"),x.get("fromDate"),x.get("thruDate")));
        GenericValue condition=EntityQuery.use(d).from("AssetsCustodyControl").where("fixedAssetId",a.get("fixedAssetId")).queryOne();if(condition!=null)values.add(Arrays.asList(condition.get("conditionId"),condition.get("availability")));return hash(values.toString());
    }
    private static void validateLocation(Delegator d,String org,String facility,String location)throws Exception {
        if(location!=null&&facility==null)throw new IllegalArgumentException("A location requires its facility.");
        if(facility!=null){GenericValue f=one(d,"Facility",Map.of("facilityId",facility));if(!org.equals(f.getString("ownerPartyId")))throw new IllegalArgumentException("Facility must belong to this organization.");}
        if(location!=null)one(d,"FacilityLocation",Map.of("facilityId",facility,"locationSeqId",location));
    }
    private static GenericValue newLine(Delegator d,GenericValue h,String asset,String scan,String expected) {
        GenericValue l=d.makeValue("AssetsVerificationLine");l.set("assetsVerificationLineId",d.getNextSeqId("AssetsVerificationLine"));l.set("assetsVerificationId",h.get("assetsVerificationId"));l.set("fixedAssetId",asset);l.set("scanCode",scan);l.set("expected",expected);return l;
    }
    private static void requireState(GenericValue h,String state){if(!state.equals(h.getString("statusId")))throw new IllegalArgumentException("Campaign must be "+state+" for this action.");}
    private static String text(Map<String,?> m,String key){Object v=m.get(key);return v==null||v.toString().trim().isEmpty()?null:v.toString().trim();}
    private static String user(Map<String,?> in){return ((GenericValue)in.get("userLogin")).getString("userLoginId");}
    private static String hash(String s)throws Exception{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(s.getBytes(StandardCharsets.UTF_8)));}
    private static Map<String,Object> response(GenericValue h,String replay){Map<String,Object> r=ServiceUtil.returnSuccess();r.put("assetsVerificationId",h.get("assetsVerificationId"));r.put("statusId",h.get("statusId"));r.put("replayed",replay);return r;}
    private static Map<String,Object> lineResponse(GenericValue l,String replay){Map<String,Object> r=ServiceUtil.returnSuccess();r.put("assetsVerificationLineId",l.get("assetsVerificationLineId"));r.put("replayed",replay);return r;}
    private static void event(Delegator d,GenericValue h,GenericValue l,Map<String,?> in,String action,String comments)throws Exception {
        GenericValue e=d.makeValue("AssetsVerificationEvent");e.set("assetsVerificationEventId",d.getNextSeqId("AssetsVerificationEvent"));e.set("assetsVerificationId",h.get("assetsVerificationId"));if(l!=null){e.set("assetsVerificationLineId",l.get("assetsVerificationLineId"));e.set("evidence",new TreeMap<>(l.getAllFields()).toString());}e.set("eventAction",action);e.set("actorUserLoginId",user(in));e.set("onBehalf","Y".equals(in.get("onBehalf"))?"Y":"N");e.set("comments",comments);e.set("eventDate",UtilDateTime.nowTimestamp());e.create();
    }
    private static Map<String,Object> failure(Exception e){Debug.logError(e,"Assets physical verification failed","AssetsVerificationServices");return ServiceUtil.returnError(e instanceof IllegalArgumentException?e.getMessage():"Verification failed; no campaign or register changes committed. Inspect server logs.");}
}
