/* Licensed under the Apache License, Version 2.0. */
package org.apache.ofbiz.assets.fixedasset;

import java.sql.Timestamp;
import java.util.*;
import org.apache.ofbiz.base.util.UtilDateTime;
import org.apache.ofbiz.entity.*;
import org.apache.ofbiz.entity.util.EntityQuery;
import org.apache.ofbiz.service.*;
import static org.apache.ofbiz.assets.fixedasset.AssetsProcurementServices.*;

/** Assets policy and audit around authoritative native FixedAssetMaint/WorkEffort services. */
public final class AssetsMaintenanceServices {
    private AssetsMaintenanceServices() { }
    public static Map<String,Object> assetsGetMaintenanceWorkspace(DispatchContext c, Map<String,?> in) {
        try {
            Delegator d=c.getDelegator(); GenericValue a=asset(c,in); String id=a.getString("fixedAssetId");
            List<Map<String,Object>> rows=new ArrayList<>();
            for(GenericValue m:EntityQuery.use(d).from("FixedAssetMaint").where("fixedAssetId",id).orderBy("-maintHistSeqId").queryList()) {
                Map<String,Object> r=new HashMap<>(m.getAllFields());
                GenericValue extension=EntityQuery.use(d).from("AssetsMaintenanceControl").where("fixedAssetId",id,"maintHistSeqId",m.getString("maintHistSeqId")).queryOne();
                r.put("controlled",extension!=null?"Y":"N"); if(extension!=null)r.putAll(extension.getAllFields());
                GenericValue w=EntityQuery.use(d).from("WorkEffort").where("workEffortId",m.getString("scheduleWorkEffortId")).queryOne();
                if(w!=null)for(String f:List.of("workEffortName","description","estimatedStartDate","estimatedCompletionDate","actualStartDate","actualCompletionDate","currentStatusId"))r.put(f,w.get(f));
                rows.add(r);
            }
            Map<String,Object> out=ServiceUtil.returnSuccess();out.put("asset",a);out.put("rows",rows);
            out.put("types",EntityQuery.use(d).from("ProductMaintType").orderBy("description").queryList());
            out.put("events",EntityQuery.use(d).from("AssetsMaintenanceEvent").where("fixedAssetId",id).orderBy("-eventDate").queryList());
            out.put("provenance",AssetsCustodyServices.provenance(d,a)); return out;
        }catch(Exception e){return fail(e);}
    }
    public static Map<String,Object> assetsCreateMaintenance(DispatchContext c, Map<String,?> in) {
        try {
            Delegator d=c.getDelegator(); GenericValue a=asset(c,in); String id=a.getString("fixedAssetId");
            boolean preview=!"N".equals(in.get("dryRun")); AssetsCustodyServices.control(d,id,!preview); operable(d,a);
            String mode=required(in,"maintenanceKind"),name=required(in,"workEffortName").trim(),comments=required(in,"comments");
            if(!Set.of("PREVENTIVE","CORRECTIVE").contains(mode)||name.length()>100)throw new IllegalArgumentException("Select preventive/corrective maintenance and a title of at most 100 characters.");
            String type=required(in,"productMaintTypeId");one(d,"ProductMaintType",Map.of("productMaintTypeId",type));
            Timestamp start=(Timestamp)in.get("estimatedStartDate"),end=(Timestamp)in.get("estimatedCompletionDate");validateDates(start,end);
            String key=required(in,"submissionKey"); if(key.length()>60)throw new IllegalArgumentException("Invalid submission key.");
            String signature=java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(String.join("|",mode,type,name,start.toString(),end.toString(),comments).getBytes(java.nio.charset.StandardCharsets.UTF_8)));
            GenericValue prior=EntityQuery.use(d).from("AssetsMaintenanceControl").where("fixedAssetId",id,"submissionKey",key).queryOne();
            if(prior!=null){if(!signature.equals(prior.getString("requestFingerprint")))throw new IllegalArgumentException("Submission key already used with different maintenance details.");return response(prior,"Y");}
            if(preview){Map<String,Object> out=ServiceUtil.returnSuccess();out.put("dryRun","Y");out.put("fixedAssetId",id);return out;}
            GenericValue user=(GenericValue)in.get("userLogin");
            Map<String,Object> result=call(c,"createFixedAssetMaint",Map.of("fixedAssetId",id,"productMaintTypeId",type,"statusId","FAM_CREATED","estimatedStartDate",start,"estimatedCompletionDate",end,"userLogin",user));
            String seq=(String)result.get("maintHistSeqId");GenericValue maint=one(d,"FixedAssetMaint",Map.of("fixedAssetId",id,"maintHistSeqId",seq));
            String work=maint.getString("scheduleWorkEffortId"); if(work==null)throw new IllegalStateException("Native maintenance did not create a work effort.");
            call(c,"updateWorkEffort",Map.of("workEffortId",work,"workEffortName",name,"description",comments,"userLogin",user));
            GenericValue ctl=d.makeValue("AssetsMaintenanceControl");ctl.set("fixedAssetId",id);ctl.set("maintHistSeqId",seq);ctl.set("partyId",a.getString("partyId"));ctl.set("maintenanceKind",mode);ctl.set("submissionKey",key);ctl.set("requestFingerprint",signature);ctl.set("lastStatusId","FAM_CREATED");ctl.set("createdByUserLogin",user.getString("userLoginId"));ctl.set("createdDate",UtilDateTime.nowTimestamp());ctl.create();
            event(d,ctl,user,"CREATE",comments);return response(ctl,"N");
        }catch(Exception e){return fail(e);}
    }
    public static Map<String,Object> assetsProcessMaintenance(DispatchContext c, Map<String,?> in) {
        try {
            Delegator d=c.getDelegator();GenericValue a=asset(c,in);String id=a.getString("fixedAssetId");AssetsCustodyServices.control(d,id,true);operable(d,a);
            String seq=required(in,"maintHistSeqId"),action=required(in,"action"),comments=required(in,"comments");
            GenericValue ctl=one(d,"AssetsMaintenanceControl",Map.of("fixedAssetId",id,"maintHistSeqId",seq));lock(ctl);
            if(!Objects.equals(ctl.getString("partyId"),a.getString("partyId")))throw new IllegalArgumentException("Asset ownership changed; reconcile this maintenance record.");
            GenericValue m=one(d,"FixedAssetMaint",Map.of("fixedAssetId",id,"maintHistSeqId",seq));lock(m);
            String old=m.getString("statusId"),target=targetStatus(old,action);
            if(!Objects.equals(ctl.getString("lastStatusId"),old))throw new IllegalArgumentException("Native maintenance changed outside Assets; reconcile before continuing.");
            GenericValue w=one(d,"WorkEffort",Map.of("workEffortId",m.getString("scheduleWorkEffortId")));lock(w);
            if(!id.equals(w.getString("fixedAssetId")) || !"WEPT_MAINTENANCE".equals(w.getString("workEffortPurposeTypeId"))) throw new IllegalArgumentException("Native work effort does not belong to this asset maintenance.");
            if(target.equals(old)){verifyWork(w,target);return response(ctl,"Y");}
            GenericValue user=(GenericValue)in.get("userLogin");
            if(!c.getSecurity().hasEntityPermission("ASSETS","_APPROVE",user)&&!c.getSecurity().hasEntityPermission("ASSETS","_ADMIN",user))throw new IllegalArgumentException("Assets approval permission is required for maintenance state changes.");
            String expected="FAM_CREATED".equals(old)?"CAL_TENTATIVE":"FAM_SCHEDULED".equals(old)||"FAM_IN_PROCESS".equals(old)?"CAL_ACCEPTED":null;
            if(!Objects.equals(expected,w.getString("currentStatusId")))throw new IllegalArgumentException("Native work effort status needs reconciliation.");
            Timestamp now=UtilDateTime.nowTimestamp();Map<String,Object> wp=new HashMap<>();wp.put("workEffortId",w.getString("workEffortId"));wp.put("userLogin",user);
            if("SCHEDULE".equals(action)){validateDates(w.getTimestamp("estimatedStartDate"),w.getTimestamp("estimatedCompletionDate"));wp.put("currentStatusId","CAL_ACCEPTED");call(c,"updateWorkEffort",wp);}
            if("START".equals(action)){noOpenMovement(d,id);requireNoInProcess(d,id);if(w.get("actualStartDate")!=null)throw new IllegalArgumentException("Actual maintenance start already exists; reconcile first.");wp.put("actualStartDate",now);call(c,"updateWorkEffort",wp);}
            if("COMPLETE".equals(action)){if(w.get("actualStartDate")==null||w.getTimestamp("actualStartDate").after(now))throw new IllegalArgumentException("A valid actual start is required before completion.");}
            if("CANCEL".equals(action)){wp.put("currentStatusId","CAL_CANCELLED");call(c,"updateWorkEffort",wp);}
            call(c,"updateFixedAssetMaint",Map.of("fixedAssetId",id,"maintHistSeqId",seq,"statusId",target,"userLogin",user));
            verifyWork(one(d,"WorkEffort",Map.of("workEffortId",w.getString("workEffortId"))),target);
            ctl.set("lastStatusId",target);ctl.store();event(d,ctl,user,action,comments);return response(ctl,"N");
        }catch(Exception e){return fail(e);}
    }
    static String targetStatus(String state,String action){
        String target=Map.of("SCHEDULE","FAM_SCHEDULED","START","FAM_IN_PROCESS","COMPLETE","FAM_COMPLETED","CANCEL","FAM_CANCELLED").get(action);
        if(target==null)throw new IllegalArgumentException("Unknown maintenance action.");
        if(target.equals(state))return state;
        boolean valid="SCHEDULE".equals(action)&&"FAM_CREATED".equals(state)||"START".equals(action)&&"FAM_SCHEDULED".equals(state)||"COMPLETE".equals(action)&&"FAM_IN_PROCESS".equals(state)||"CANCEL".equals(action)&&Set.of("FAM_CREATED","FAM_SCHEDULED").contains(state);
        if(!valid)throw new IllegalArgumentException("Maintenance action is not allowed from the current state.");return target;
    }
    static void validateDates(Timestamp start,Timestamp end){if(start==null||end==null||!end.after(start))throw new IllegalArgumentException("Planned completion must be after planned start.");}
    static void requireNoInProcess(Delegator d,String id)throws Exception{
        if(!EntityQuery.use(d).from("FixedAssetMaint").where("fixedAssetId",id,"statusId","FAM_IN_PROCESS").queryList().isEmpty())throw new IllegalArgumentException("Complete in-process maintenance before moving custody or location.");
    }
    private static void lock(GenericValue value) throws Exception { value.set("lastUpdatedStamp",UtilDateTime.nowTimestamp()); value.store(); value.refresh(); }
    private static void verifyWork(GenericValue w,String status){
        String expected="FAM_CREATED".equals(status)?"CAL_TENTATIVE":"FAM_COMPLETED".equals(status)?"CAL_COMPLETED":"FAM_CANCELLED".equals(status)?"CAL_CANCELLED":"CAL_ACCEPTED";
        if(!expected.equals(w.getString("currentStatusId"))||"FAM_COMPLETED".equals(status)&&w.get("actualCompletionDate")==null||"FAM_IN_PROCESS".equals(status)&&w.get("actualStartDate")==null)throw new IllegalArgumentException("Native maintenance/work effort evidence is incomplete.");
    }
    private static GenericValue asset(DispatchContext c,Map<String,?> in)throws Exception{GenericValue a=one(c.getDelegator(),"FixedAsset",Map.of("fixedAssetId",required(in,"fixedAssetId")));if(a.get("partyId")==null)throw new IllegalArgumentException("Owning organization is required.");AssetsRequestServices.requireOrganizationAccess(c,(GenericValue)in.get("userLogin"),a.getString("partyId"));return a;}
    private static void operable(Delegator d,GenericValue a)throws Exception{if(a.get("actualEndOfLife")!=null)throw new IllegalArgumentException("A retired asset cannot enter maintenance.");AssetsDisposalServices.requireNoOpenDisposal(d,a.getString("fixedAssetId"));for(GenericValue b:EntityQuery.use(d).from("AssetsBookDetail").where("fixedAssetId",a.getString("fixedAssetId")).filterByDate().queryList())if("ASSETS_PENDING".equals(b.getString("statusId")))throw new IllegalArgumentException("Place the asset in service before maintenance.");}
    private static void noOpenMovement(Delegator d,String id)throws Exception{for(GenericValue m:EntityQuery.use(d).from("AssetsCustodyMovement").where("fixedAssetId",id).queryList())if(Set.of("SUBMITTED","APPROVED","HANDED_OVER").contains(m.getString("statusId")))throw new IllegalArgumentException("Complete or cancel open custody movements before starting maintenance.");}
    private static void event(Delegator d,GenericValue ctl,GenericValue user,String action,String comments)throws Exception{GenericValue e=d.makeValue("AssetsMaintenanceEvent");e.set("assetsMaintenanceEventId",d.getNextSeqId("AssetsMaintenanceEvent"));for(String k:List.of("fixedAssetId","maintHistSeqId"))e.set(k,ctl.get(k));e.set("eventAction",action);e.set("statusId",ctl.getString("lastStatusId"));e.set("actorUserLoginId",user.getString("userLoginId"));e.set("comments",comments);e.set("eventDate",UtilDateTime.nowTimestamp());e.create();}
    private static Map<String,Object> response(GenericValue ctl,String replay){Map<String,Object> r=ServiceUtil.returnSuccess();r.put("fixedAssetId",ctl.getString("fixedAssetId"));r.put("maintHistSeqId",ctl.getString("maintHistSeqId"));r.put("statusId",ctl.getString("lastStatusId"));r.put("replayed",replay);return r;}
    private static Map<String,Object> fail(Exception e){org.apache.ofbiz.base.util.Debug.logError(e,"Assets maintenance failed","AssetsMaintenanceServices");return ServiceUtil.returnError(e.getMessage()==null?"Maintenance failed; inspect server logs.":e.getMessage());}
}
