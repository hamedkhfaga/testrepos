package org.apache.ofbiz.loan.services;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Arrays;

import org.apache.ofbiz.base.util.Debug;
import org.apache.ofbiz.base.util.UtilValidate;
import org.apache.ofbiz.entity.Delegator;
import org.apache.ofbiz.entity.GenericValue;
import org.apache.ofbiz.entity.condition.EntityCondition;
import org.apache.ofbiz.entity.condition.EntityOperator;
import org.apache.ofbiz.entity.util.EntityQuery;
import org.apache.ofbiz.service.DispatchContext;
import org.apache.ofbiz.service.ServiceUtil;

public final class LoanUiServices {

    public static final String MODULE = LoanUiServices.class.getName();

    private LoanUiServices() {}

    public static Map<String, Object> getDuePartyLoanInstallmentsForPayroll(
            DispatchContext dctx, Map<String, ?> context) {

        Delegator delegator = dctx.getDelegator();

        try {

            EntityCondition cond = EntityCondition.makeCondition(EntityOperator.AND,

                EntityCondition.makeCondition("loanPartyId", context.get("partyId")),

                EntityCondition.makeCondition("loanOrganizationPartyId",
                        context.get("organizationPartyId")),

                EntityCondition.makeCondition("scheduleDuePayrollPeriodId",
                        context.get("payrollPeriodId")),

                EntityCondition.makeCondition("loanPayrollEligibleFlag", "Y"),

                EntityCondition.makeCondition("loanLoanStatusId",
                        EntityOperator.IN,
                        Arrays.asList("LN_ACTIVE", "LN_PARTIAL")),

                EntityCondition.makeCondition("scheduleScheduleStatusId",
                        EntityOperator.IN,
                        Arrays.asList(
                                "LNS_PENDING",
                                "LNS_DUE",
                                "LNS_PARTIAL",
                                "LNS_OVERDUE"))
            );

            List<GenericValue> dueRows = EntityQuery.use(delegator)
                    .from("PartyLoanDueInstallmentView")
                    .where(cond)
                    .orderBy("loanAllocationOrderEnumId",
                             "scheduleDuePayrollPeriodId",
                             "scheduleDueDate",
                             "scheduleInstallmentSeqNum",
                             "loanLoanId",
                             "scheduleLoanScheduleId")
                    .queryList();

            Map<String, Object> data = new HashMap<>();
            data.put("dueRows", dueRows);
            data.put("dueCount", dueRows != null ? dueRows.size() : 0);

            Map<String, Object> out = ServiceUtil.returnSuccess();
            out.putAll(data);

            out.put("data", new HashMap<>(data));
            out.put("legacyData", new HashMap<>(data));

            return out;

        } catch (Exception e) {

            Debug.logError(e, "Error loading due payroll loan installments", MODULE);

            Map<String, Object> out = ServiceUtil.returnError(e.getMessage());
            out.put("data", new HashMap<>());
            out.put("legacyData", new HashMap<>());

            return out;
        }
    }

    public static Map<String, Object> getLoanPortfolioDashboardV1(DispatchContext dctx, Map<String, ?> context) {
        Delegator delegator = dctx.getDelegator();
        try {
            String org = trimToNull((String) context.get("organizationPartyId"));
            String statusFilter = trimToNull((String) context.get("loanStatusId"));
            java.util.List<EntityCondition> conds = new ArrayList<EntityCondition>();
            if (UtilValidate.isNotEmpty(org)) conds.add(EntityCondition.makeCondition("organizationPartyId", org));
            if (UtilValidate.isNotEmpty(statusFilter)) conds.add(EntityCondition.makeCondition("loanStatusId", statusFilter));
            EntityQuery q = EntityQuery.use(delegator).from("PartyLoan");
            if (!conds.isEmpty()) q = q.where(EntityCondition.makeCondition(conds, EntityOperator.AND));
            java.util.List<GenericValue> loans = q.orderBy("organizationPartyId", "loanStatusId", "loanId").queryList();
            Map<String, Map<String,Object>> byStatus = new LinkedHashMap<String, Map<String,Object>>();
            BigDecimal principal=BigDecimal.ZERO, repayable=BigDecimal.ZERO, paid=BigDecimal.ZERO, outstanding=BigDecimal.ZERO;
            for (GenericValue loan: loans) {
                String st = trimToNull(loan.getString("loanStatusId")); if (UtilValidate.isEmpty(st)) st="_UNKNOWN_";
                Map<String,Object> row = byStatus.get(st);
                if (row == null) { row = amountRow("loanStatusId", st); byStatus.put(st,row); }
                BigDecimal p=safeAmount(loan.getBigDecimal("principalAmount"));
                BigDecimal r=safeAmount(loan.getBigDecimal("totalRepayableAmount"));
                BigDecimal pd=safeAmount(loan.getBigDecimal("paidAmount"));
                BigDecimal o=safeAmount(loan.getBigDecimal("outstandingAmount"));
                bumpLoanRow(row,p,r,pd,o); principal=principal.add(p); repayable=repayable.add(r); paid=paid.add(pd); outstanding=outstanding.add(o);
            }
            Map<String,Object> data=new LinkedHashMap<String,Object>();
            data.put("organizationPartyId",org); data.put("loanStatusId",statusFilter); data.put("loanCount",Integer.valueOf(loans.size()));
            data.put("principalAmount",principal); data.put("totalRepayableAmount",repayable); data.put("paidAmount",paid); data.put("outstandingAmount",outstanding);
            data.put("statusRows",new ArrayList<Map<String,Object>>(byStatus.values()));
            return standardSuccess(data);
        } catch (Exception e) { Debug.logError(e,"Error loading loan portfolio dashboard",MODULE); return standardError(e); }
    }

    public static Map<String, Object> getLoanScheduleAgingDashboardV1(DispatchContext dctx, Map<String, ?> context) {
        Delegator delegator = dctx.getDelegator();
        try {
            String org = trimToNull((String) context.get("organizationPartyId"));
            String loanId = trimToNull((String) context.get("loanId"));
            java.util.List<GenericValue> loans = findLoans(delegator, org, loanId);
            java.util.List<String> loanIds = new ArrayList<String>(); for (GenericValue l: loans) loanIds.add(l.getString("loanId"));
            java.util.List<GenericValue> schedules = new ArrayList<GenericValue>();
            if (!loanIds.isEmpty()) schedules = EntityQuery.use(delegator).from("PartyLoanSchedule").where(EntityCondition.makeCondition("loanId",EntityOperator.IN,loanIds)).orderBy("loanId","installmentSeqNum","loanScheduleId").queryList();
            Map<String, Map<String,Object>> byStatus = new LinkedHashMap<String, Map<String,Object>>(); BigDecimal paid=BigDecimal.ZERO,out=BigDecimal.ZERO;
            for (GenericValue s: schedules) {
                String st=trimToNull(s.getString("scheduleStatusId")); if(UtilValidate.isEmpty(st)) st="_UNKNOWN_";
                Map<String,Object> row=byStatus.get(st); if(row==null){row=scheduleRow(st); byStatus.put(st,row);} 
                BigDecimal p=safeAmount(s.getBigDecimal("paidAmount")); BigDecimal o=safeAmount(s.getBigDecimal("outstandingAmount"));
                row.put("scheduleCount",Integer.valueOf(((Integer)row.get("scheduleCount")).intValue()+1));
                row.put("paidAmount",((BigDecimal)row.get("paidAmount")).add(p)); row.put("outstandingAmount",((BigDecimal)row.get("outstandingAmount")).add(o)); paid=paid.add(p); out=out.add(o);
            }
            Map<String,Object> data=new LinkedHashMap<String,Object>(); data.put("organizationPartyId",org); data.put("loanId",loanId); data.put("loanCount",Integer.valueOf(loans.size())); data.put("scheduleCount",Integer.valueOf(schedules.size())); data.put("totalPaidAmount",paid); data.put("totalOutstandingAmount",out); data.put("statusRows",new ArrayList<Map<String,Object>>(byStatus.values())); return standardSuccess(data);
        } catch(Exception e){ Debug.logError(e,"Error loading loan schedule aging dashboard",MODULE); return standardError(e); }
    }

    public static Map<String, Object> getLoanRepaymentDashboardV1(DispatchContext dctx, Map<String, ?> context) {
        Delegator delegator = dctx.getDelegator();
        try {
            String org=trimToNull((String)context.get("organizationPartyId")); String loanId=trimToNull((String)context.get("loanId"));
            java.util.List<GenericValue> loans=findLoans(delegator,org,loanId); java.util.List<String> loanIds=new ArrayList<String>(); for(GenericValue l:loans)loanIds.add(l.getString("loanId"));
            java.util.List<GenericValue> reps=new ArrayList<GenericValue>(); if(!loanIds.isEmpty()) reps=EntityQuery.use(delegator).from("PartyLoanRepayment").where(EntityCondition.makeCondition("loanId",EntityOperator.IN,loanIds)).orderBy("loanId","repaymentDate","loanRepaymentId").queryList();
            Map<String, Map<String,Object>> bySource=new LinkedHashMap<String, Map<String,Object>>(); Map<String, Map<String,Object>> byMethod=new LinkedHashMap<String, Map<String,Object>>(); BigDecimal total=BigDecimal.ZERO;
            for(GenericValue r:reps){BigDecimal amt=safeAmount(r.getBigDecimal("amountApplied")); total=total.add(amt); accumulateAmount(bySource,"repaymentSourceEnumId",r.getString("repaymentSourceEnumId"),amt); accumulateAmount(byMethod,"repaymentMethodEnumId",r.getString("repaymentMethodEnumId"),amt);}
            Map<String,Object> data=new LinkedHashMap<String,Object>(); data.put("organizationPartyId",org); data.put("loanId",loanId); data.put("loanCount",Integer.valueOf(loans.size())); data.put("repaymentCount",Integer.valueOf(reps.size())); data.put("totalAmountApplied",total); data.put("sourceRows",new ArrayList<Map<String,Object>>(bySource.values())); data.put("methodRows",new ArrayList<Map<String,Object>>(byMethod.values())); return standardSuccess(data);
        } catch(Exception e){ Debug.logError(e,"Error loading loan repayment dashboard",MODULE); return standardError(e); }
    }

    public static Map<String, Object> getLoanAccountingDashboardV1(DispatchContext dctx, Map<String, ?> context) {
        Delegator delegator = dctx.getDelegator();
        try {
            String org=trimToNull((String)context.get("organizationPartyId")); String loanId=trimToNull((String)context.get("loanId"));
            java.util.List<GenericValue> loans=findLoans(delegator,org,loanId); java.util.List<String> loanIds=new ArrayList<String>(); java.util.List<String> payIds=new ArrayList<String>();
            for(GenericValue l:loans){loanIds.add(l.getString("loanId")); String p=trimToNull(l.getString("disbursementPaymentId")); if(UtilValidate.isNotEmpty(p)) payIds.add(p);} 
            if(!loanIds.isEmpty()){ for(GenericValue r: EntityQuery.use(delegator).from("PartyLoanRepayment").where(EntityCondition.makeCondition("loanId",EntityOperator.IN,loanIds)).queryList()){String p=trimToNull(r.getString("paymentId")); if(UtilValidate.isNotEmpty(p)) payIds.add(p);} }
            java.util.List<GenericValue> rows=new ArrayList<GenericValue>(); if(!payIds.isEmpty()) rows=EntityQuery.use(delegator).from("AcctgTrans").where(EntityCondition.makeCondition("paymentId",EntityOperator.IN,payIds)).orderBy("paymentId","acctgTransTypeId","acctgTransId").queryList();
            Map<String, Map<String,Object>> byType=new LinkedHashMap<String, Map<String,Object>>(); int posted=0, unposted=0; for(GenericValue a:rows){accumulateCount(byType,"acctgTransTypeId",a.getString("acctgTransTypeId")); if("Y".equals(a.getString("isPosted"))) posted++; else unposted++;}
            Map<String,Object> data=new LinkedHashMap<String,Object>(); data.put("organizationPartyId",org); data.put("loanId",loanId); data.put("loanCount",Integer.valueOf(loans.size())); data.put("paymentCount",Integer.valueOf(payIds.size())); data.put("acctgTransCount",Integer.valueOf(rows.size())); data.put("postedAcctgTransCount",Integer.valueOf(posted)); data.put("unpostedAcctgTransCount",Integer.valueOf(unposted)); data.put("typeRows",new ArrayList<Map<String,Object>>(byType.values())); return standardSuccess(data);
        } catch(Exception e){ Debug.logError(e,"Error loading loan accounting dashboard",MODULE); return standardError(e); }
    }



    public static Map<String, Object> getLoanDashboardDetailRowsV1(DispatchContext dctx, Map<String, ?> context) {
        Delegator delegator = dctx.getDelegator();
        try {
            String org = trimToNull((String) context.get("organizationPartyId"));
            String loanId = trimToNull((String) context.get("loanId"));
            String status = trimToNull((String) context.get("loanStatusId"));
            java.util.List<EntityCondition> conds = new ArrayList<EntityCondition>();
            if (UtilValidate.isNotEmpty(org)) conds.add(EntityCondition.makeCondition("organizationPartyId", org));
            if (UtilValidate.isNotEmpty(loanId)) conds.add(EntityCondition.makeCondition("loanId", loanId));
            if (UtilValidate.isNotEmpty(status)) conds.add(EntityCondition.makeCondition("loanStatusId", status));
            EntityQuery q = EntityQuery.use(delegator).from("PartyLoan");
            if (!conds.isEmpty()) q = q.where(EntityCondition.makeCondition(conds, EntityOperator.AND));
            java.util.List<GenericValue> loans = q.orderBy("organizationPartyId", "loanStatusId", "loanId").queryList();
            java.util.List<Map<String,Object>> rows = new ArrayList<Map<String,Object>>();
            for (GenericValue loan : loans) {
                Map<String,Object> row = new LinkedHashMap<String,Object>();
                row.put("loanId", loan.getString("loanId")); row.put("loanNumber", loan.getString("loanNumber"));
                row.put("organizationPartyId", loan.getString("organizationPartyId")); row.put("partyId", loan.getString("partyId"));
                row.put("loanStatusId", loan.getString("loanStatusId")); row.put("loanTypeEnumId", loan.getString("loanTypeEnumId"));
                row.put("currencyUomId", loan.getString("currencyUomId")); row.put("principalAmount", safeAmount(loan.getBigDecimal("principalAmount")));
                row.put("totalRepayableAmount", safeAmount(loan.getBigDecimal("totalRepayableAmount"))); row.put("paidAmount", safeAmount(loan.getBigDecimal("paidAmount")));
                row.put("outstandingAmount", safeAmount(loan.getBigDecimal("outstandingAmount"))); row.put("installmentCount", loan.get("installmentCount"));
                row.put("approvalDate", loan.get("approvalDate")); row.put("disbursementDate", loan.get("disbursementDate"));
                row.put("disbursementPaymentId", loan.getString("disbursementPaymentId")); row.put("disbursementAcctgTransId", loan.getString("disbursementAcctgTransId"));
                rows.add(row);
            }
            Map<String,Object> data = new LinkedHashMap<String,Object>();
            data.put("organizationPartyId", org); data.put("loanId", loanId); data.put("loanStatusId", status); data.put("rowCount", Integer.valueOf(rows.size())); data.put("rows", rows);
            return standardSuccess(data);
        } catch(Exception e){ Debug.logError(e,"Error loading loan dashboard detail rows",MODULE); return standardError(e); }
    }

    public static Map<String, Object> getLoanScheduleDetailRowsV1(DispatchContext dctx, Map<String, ?> context) {
        Delegator delegator = dctx.getDelegator();
        try {
            String org=trimToNull((String)context.get("organizationPartyId")); String loanId=trimToNull((String)context.get("loanId")); String status=trimToNull((String)context.get("scheduleStatusId"));
            java.util.List<GenericValue> loans=findLoans(delegator,org,loanId); java.util.List<String> loanIds=new ArrayList<String>(); for(GenericValue l:loans)loanIds.add(l.getString("loanId"));
            java.util.List<GenericValue> schedules=new ArrayList<GenericValue>();
            if(!loanIds.isEmpty()){ java.util.List<EntityCondition> c=new ArrayList<EntityCondition>(); c.add(EntityCondition.makeCondition("loanId",EntityOperator.IN,loanIds)); if(UtilValidate.isNotEmpty(status)) c.add(EntityCondition.makeCondition("scheduleStatusId",status)); schedules=EntityQuery.use(delegator).from("PartyLoanSchedule").where(EntityCondition.makeCondition(c,EntityOperator.AND)).orderBy("loanId","installmentSeqNum","loanScheduleId").queryList(); }
            java.util.List<Map<String,Object>> rows=new ArrayList<Map<String,Object>>();
            for(GenericValue s:schedules){ Map<String,Object> r=new LinkedHashMap<String,Object>(); r.put("loanScheduleId",s.getString("loanScheduleId")); r.put("loanId",s.getString("loanId")); r.put("installmentSeqNum",s.get("installmentSeqNum")); r.put("dueDate",s.get("dueDate")); r.put("duePayrollPeriodId",s.getString("duePayrollPeriodId")); r.put("principalAmount",safeAmount(s.getBigDecimal("principalAmount"))); r.put("installmentAmount",safeAmount(s.getBigDecimal("installmentAmount"))); r.put("paidAmount",safeAmount(s.getBigDecimal("paidAmount"))); r.put("outstandingAmount",safeAmount(s.getBigDecimal("outstandingAmount"))); r.put("scheduleStatusId",s.getString("scheduleStatusId")); r.put("repaymentSourceEnumId",s.getString("repaymentSourceEnumId")); r.put("repaymentMethodEnumId",s.getString("repaymentMethodEnumId")); rows.add(r); }
            Map<String,Object> data=new LinkedHashMap<String,Object>(); data.put("organizationPartyId",org); data.put("loanId",loanId); data.put("scheduleStatusId",status); data.put("rowCount",Integer.valueOf(rows.size())); data.put("rows",rows); return standardSuccess(data);
        } catch(Exception e){ Debug.logError(e,"Error loading loan schedule detail rows",MODULE); return standardError(e); }
    }

    public static Map<String, Object> getLoanRepaymentDetailRowsV1(DispatchContext dctx, Map<String, ?> context) {
        Delegator delegator = dctx.getDelegator();
        try {
            String org=trimToNull((String)context.get("organizationPartyId")); String loanId=trimToNull((String)context.get("loanId")); String source=trimToNull((String)context.get("repaymentSourceEnumId"));
            java.util.List<GenericValue> loans=findLoans(delegator,org,loanId); java.util.List<String> loanIds=new ArrayList<String>(); for(GenericValue l:loans)loanIds.add(l.getString("loanId"));
            java.util.List<GenericValue> reps=new ArrayList<GenericValue>();
            if(!loanIds.isEmpty()){ java.util.List<EntityCondition> c=new ArrayList<EntityCondition>(); c.add(EntityCondition.makeCondition("loanId",EntityOperator.IN,loanIds)); if(UtilValidate.isNotEmpty(source)) c.add(EntityCondition.makeCondition("repaymentSourceEnumId",source)); reps=EntityQuery.use(delegator).from("PartyLoanRepayment").where(EntityCondition.makeCondition(c,EntityOperator.AND)).orderBy("loanId","repaymentDate","loanRepaymentId").queryList(); }
            java.util.List<Map<String,Object>> rows=new ArrayList<Map<String,Object>>();
            for(GenericValue p:reps){ Map<String,Object> r=new LinkedHashMap<String,Object>(); r.put("loanRepaymentId",p.getString("loanRepaymentId")); r.put("loanId",p.getString("loanId")); r.put("loanScheduleId",p.getString("loanScheduleId")); r.put("paymentId",p.getString("paymentId")); r.put("repaymentDate",p.get("repaymentDate")); r.put("amountApplied",safeAmount(p.getBigDecimal("amountApplied"))); r.put("principalApplied",safeAmount(p.getBigDecimal("principalApplied"))); r.put("repaymentSourceEnumId",p.getString("repaymentSourceEnumId")); r.put("repaymentMethodEnumId",p.getString("repaymentMethodEnumId")); r.put("externalReference",p.getString("externalReference")); r.put("repaymentStatusId",p.getString("repaymentStatusId")); rows.add(r); }
            Map<String,Object> data=new LinkedHashMap<String,Object>(); data.put("organizationPartyId",org); data.put("loanId",loanId); data.put("repaymentSourceEnumId",source); data.put("rowCount",Integer.valueOf(rows.size())); data.put("rows",rows); return standardSuccess(data);
        } catch(Exception e){ Debug.logError(e,"Error loading loan repayment detail rows",MODULE); return standardError(e); }
    }

    public static Map<String, Object> getLoanAccountingDetailRowsV1(DispatchContext dctx, Map<String, ?> context) {
        Delegator delegator = dctx.getDelegator();
        try {
            String org=trimToNull((String)context.get("organizationPartyId")); String loanId=trimToNull((String)context.get("loanId")); String paymentId=trimToNull((String)context.get("paymentId")); String type=trimToNull((String)context.get("acctgTransTypeId"));
            java.util.List<String> paymentIds=new ArrayList<String>();
            if(UtilValidate.isNotEmpty(paymentId)) paymentIds.add(paymentId); else { java.util.List<GenericValue> loans=findLoans(delegator,org,loanId); java.util.List<String> loanIds=new ArrayList<String>(); for(GenericValue l:loans){loanIds.add(l.getString("loanId")); String p=trimToNull(l.getString("disbursementPaymentId")); if(UtilValidate.isNotEmpty(p))paymentIds.add(p);} if(!loanIds.isEmpty()){for(GenericValue r:EntityQuery.use(delegator).from("PartyLoanRepayment").where(EntityCondition.makeCondition("loanId",EntityOperator.IN,loanIds)).queryList()){String p=trimToNull(r.getString("paymentId")); if(UtilValidate.isNotEmpty(p))paymentIds.add(p);}} }
            java.util.List<GenericValue> acctg=new ArrayList<GenericValue>();
            if(!paymentIds.isEmpty()){ java.util.List<EntityCondition> c=new ArrayList<EntityCondition>(); c.add(EntityCondition.makeCondition("paymentId",EntityOperator.IN,paymentIds)); if(UtilValidate.isNotEmpty(type)) c.add(EntityCondition.makeCondition("acctgTransTypeId",type)); acctg=EntityQuery.use(delegator).from("AcctgTrans").where(EntityCondition.makeCondition(c,EntityOperator.AND)).orderBy("paymentId","acctgTransTypeId","acctgTransId").queryList(); }
            java.util.List<Map<String,Object>> rows=new ArrayList<Map<String,Object>>();
            for(GenericValue a:acctg){ Map<String,Object> r=new LinkedHashMap<String,Object>(); r.put("acctgTransId",a.getString("acctgTransId")); r.put("acctgTransTypeId",a.getString("acctgTransTypeId")); r.put("paymentId",a.getString("paymentId")); r.put("invoiceId",a.getString("invoiceId")); r.put("partyId",a.getString("partyId")); r.put("transactionDate",a.get("transactionDate")); r.put("isPosted",a.getString("isPosted")); r.put("postedDate",a.get("postedDate")); r.put("description",a.getString("description")); rows.add(r); }
            Map<String,Object> data=new LinkedHashMap<String,Object>(); data.put("organizationPartyId",org); data.put("loanId",loanId); data.put("paymentId",paymentId); data.put("acctgTransTypeId",type); data.put("rowCount",Integer.valueOf(rows.size())); data.put("rows",rows); return standardSuccess(data);
        } catch(Exception e){ Debug.logError(e,"Error loading loan accounting detail rows",MODULE); return standardError(e); }
    }


    public static Map<String, Object> getLoanProfileV1(DispatchContext dctx, Map<String, ?> context) {
        Delegator delegator = dctx.getDelegator();
        try {
            String organizationPartyId = trimToNull((String) context.get("organizationPartyId"));
            String loanId = trimToNull((String) context.get("loanId"));
            if (UtilValidate.isEmpty(loanId)) {
                return ServiceUtil.returnError("loanId is required");
            }

            GenericValue loan = EntityQuery.use(delegator).from("PartyLoan").where("loanId", loanId).queryOne();
            if (loan == null) {
                return ServiceUtil.returnError("Loan not found: " + loanId);
            }
            if (UtilValidate.isNotEmpty(organizationPartyId)
                    && !organizationPartyId.equals(loan.getString("organizationPartyId"))) {
                return ServiceUtil.returnError("organizationPartyId mismatch for loanId: " + loanId);
            }
            if (UtilValidate.isEmpty(organizationPartyId)) {
                organizationPartyId = loan.getString("organizationPartyId");
            }

            String borrowerPartyId = loan.getString("partyId");
            String agreementId = trimToNull(loan.getString("agreementId"));
            String billingAccountId = trimToNull(loan.getString("billingAccountId"));
            String disbursementPaymentId = trimToNull(loan.getString("disbursementPaymentId"));
            String disbursementAcctgTransId = trimToNull(loan.getString("disbursementAcctgTransId"));

            Map<String,Object> data = new LinkedHashMap<String,Object>();
            data.put("loan", gvToMap(loan));
            data.put("borrowerParty", gvToMap(EntityQuery.use(delegator).from("Party").where("partyId", borrowerPartyId).queryOne()));
            data.put("organizationParty", gvToMap(EntityQuery.use(delegator).from("Party").where("partyId", organizationPartyId).queryOne()));

            data.put("agreement", UtilValidate.isNotEmpty(agreementId) ? gvToMap(EntityQuery.use(delegator).from("Agreement").where("agreementId", agreementId).queryOne()) : new LinkedHashMap<String,Object>());
            data.put("agreementRoles", UtilValidate.isNotEmpty(agreementId) ? gvListToMaps(EntityQuery.use(delegator).from("AgreementRole").where("agreementId", agreementId).orderBy("roleTypeId", "partyId").queryList()) : new ArrayList<Map<String,Object>>());
            data.put("agreementAttributes", UtilValidate.isNotEmpty(agreementId) ? gvListToMaps(EntityQuery.use(delegator).from("AgreementAttribute").where("agreementId", agreementId).orderBy("attrName").queryList()) : new ArrayList<Map<String,Object>>());

            data.put("billingAccount", UtilValidate.isNotEmpty(billingAccountId) ? gvToMap(EntityQuery.use(delegator).from("BillingAccount").where("billingAccountId", billingAccountId).queryOne()) : new LinkedHashMap<String,Object>());
            data.put("billingAccountRoles", UtilValidate.isNotEmpty(billingAccountId) ? gvListToMaps(EntityQuery.use(delegator).from("BillingAccountRole").where("billingAccountId", billingAccountId).orderBy("roleTypeId", "partyId").queryList()) : new ArrayList<Map<String,Object>>());

            java.util.List<GenericValue> schedules = EntityQuery.use(delegator).from("PartyLoanSchedule").where("loanId", loanId).orderBy("installmentSeqNum", "loanScheduleId").queryList();
            java.util.List<GenericValue> repayments = EntityQuery.use(delegator).from("PartyLoanRepayment").where("loanId", loanId).orderBy("repaymentDate", "loanRepaymentId").queryList();
            java.util.List<GenericValue> events = EntityQuery.use(delegator).from("PartyLoanEvent").where("loanId", loanId).orderBy("eventDate", "loanEventId").queryList();

            java.util.List<String> paymentIds = new ArrayList<String>();
            if (UtilValidate.isNotEmpty(disbursementPaymentId)) paymentIds.add(disbursementPaymentId);
            for (GenericValue repayment : repayments) {
                String paymentId = trimToNull(repayment.getString("paymentId"));
                if (UtilValidate.isNotEmpty(paymentId) && !paymentIds.contains(paymentId)) paymentIds.add(paymentId);
            }

            java.util.List<GenericValue> payments = new ArrayList<GenericValue>();
            java.util.List<GenericValue> paymentApplications = new ArrayList<GenericValue>();
            java.util.List<GenericValue> accountingRows = new ArrayList<GenericValue>();
            if (!paymentIds.isEmpty()) {
                payments = EntityQuery.use(delegator).from("Payment").where(EntityCondition.makeCondition("paymentId", EntityOperator.IN, paymentIds)).orderBy("paymentId").queryList();
                paymentApplications = EntityQuery.use(delegator).from("PaymentApplication").where(EntityCondition.makeCondition("paymentId", EntityOperator.IN, paymentIds)).orderBy("paymentId", "paymentApplicationId").queryList();
                accountingRows = EntityQuery.use(delegator).from("AcctgTrans").where(EntityCondition.makeCondition("paymentId", EntityOperator.IN, paymentIds)).orderBy("paymentId", "acctgTransTypeId", "acctgTransId").queryList();
            }

            java.util.List<String> invoiceIds = new ArrayList<String>();
            if (!paymentApplications.isEmpty()) {
                for (GenericValue app : paymentApplications) {
                    String invoiceId = trimToNull(app.getString("invoiceId"));
                    if (UtilValidate.isNotEmpty(invoiceId) && !invoiceIds.contains(invoiceId)) invoiceIds.add(invoiceId);
                }
            }
            java.util.List<GenericValue> invoices = new ArrayList<GenericValue>();
            java.util.List<GenericValue> invoiceItems = new ArrayList<GenericValue>();
            if (!invoiceIds.isEmpty()) {
                invoices = EntityQuery.use(delegator).from("Invoice").where(EntityCondition.makeCondition("invoiceId", EntityOperator.IN, invoiceIds)).orderBy("invoiceTypeId", "invoiceId").queryList();
                invoiceItems = EntityQuery.use(delegator).from("InvoiceItem").where(EntityCondition.makeCondition("invoiceId", EntityOperator.IN, invoiceIds)).orderBy("invoiceId", "invoiceItemSeqId").queryList();
            }

            data.put("schedules", gvListToMaps(schedules));
            data.put("repayments", gvListToMaps(repayments));
            data.put("events", gvListToMaps(events));
            data.put("payments", gvListToMaps(payments));
            data.put("paymentApplications", gvListToMaps(paymentApplications));
            data.put("invoices", gvListToMaps(invoices));
            data.put("invoiceItems", gvListToMaps(invoiceItems));
            data.put("accounting", gvListToMaps(accountingRows));

            Map<String,Object> workflow = new LinkedHashMap<String,Object>();
            String statusId = trimToNull(loan.getString("loanStatusId"));
            workflow.put("loanStatusId", statusId);
            workflow.put("nextAction", resolveProfileNextAction(statusId, loan, schedules));
            workflow.put("isAgreementReady", Boolean.valueOf(UtilValidate.isNotEmpty(agreementId)));
            workflow.put("isBillingReady", Boolean.valueOf(UtilValidate.isNotEmpty(billingAccountId)));
            workflow.put("isDisbursementReady", Boolean.valueOf(UtilValidate.isNotEmpty(disbursementPaymentId)));
            workflow.put("isDisbursementAccountingReady", Boolean.valueOf(UtilValidate.isNotEmpty(disbursementAcctgTransId)));
            workflow.put("scheduleCount", Integer.valueOf(schedules.size()));
            workflow.put("repaymentCount", Integer.valueOf(repayments.size()));
            workflow.put("paymentCount", Integer.valueOf(payments.size()));
            workflow.put("invoiceCount", Integer.valueOf(invoices.size()));
            workflow.put("accountingCount", Integer.valueOf(accountingRows.size()));
            data.put("workflowState", workflow);

            data.put("organizationPartyId", organizationPartyId);
            data.put("loanId", loanId);
            return standardSuccess(data);
        } catch(Exception e){ Debug.logError(e,"Error loading loan profile",MODULE); return standardError(e); }
    }

    private static java.util.List<GenericValue> findLoans(Delegator delegator, String org, String loanId) throws Exception { java.util.List<EntityCondition> c=new ArrayList<EntityCondition>(); if(UtilValidate.isNotEmpty(org))c.add(EntityCondition.makeCondition("organizationPartyId",org)); if(UtilValidate.isNotEmpty(loanId))c.add(EntityCondition.makeCondition("loanId",loanId)); EntityQuery q=EntityQuery.use(delegator).from("PartyLoan"); if(!c.isEmpty())q=q.where(EntityCondition.makeCondition(c,EntityOperator.AND)); return q.orderBy("organizationPartyId","loanId").queryList(); }
    private static Map<String,Object> amountRow(String key,String val){Map<String,Object> r=new LinkedHashMap<String,Object>(); r.put(key,val); r.put("loanCount",Integer.valueOf(0)); r.put("principalAmount",BigDecimal.ZERO); r.put("totalRepayableAmount",BigDecimal.ZERO); r.put("paidAmount",BigDecimal.ZERO); r.put("outstandingAmount",BigDecimal.ZERO); return r;}
    private static Map<String,Object> scheduleRow(String st){Map<String,Object> r=new LinkedHashMap<String,Object>(); r.put("scheduleStatusId",st); r.put("scheduleCount",Integer.valueOf(0)); r.put("paidAmount",BigDecimal.ZERO); r.put("outstandingAmount",BigDecimal.ZERO); return r;}
    private static void bumpLoanRow(Map<String,Object> r, BigDecimal p, BigDecimal repay, BigDecimal paid, BigDecimal out){r.put("loanCount",Integer.valueOf(((Integer)r.get("loanCount")).intValue()+1)); r.put("principalAmount",((BigDecimal)r.get("principalAmount")).add(p)); r.put("totalRepayableAmount",((BigDecimal)r.get("totalRepayableAmount")).add(repay)); r.put("paidAmount",((BigDecimal)r.get("paidAmount")).add(paid)); r.put("outstandingAmount",((BigDecimal)r.get("outstandingAmount")).add(out));}
    private static void accumulateAmount(Map<String, Map<String,Object>> rows,String key,String val,BigDecimal amt){val=trimToNull(val); if(UtilValidate.isEmpty(val))val="_UNKNOWN_"; Map<String,Object> r=rows.get(val); if(r==null){r=new LinkedHashMap<String,Object>(); r.put(key,val); r.put("count",Integer.valueOf(0)); r.put("amount",BigDecimal.ZERO); rows.put(val,r);} r.put("count",Integer.valueOf(((Integer)r.get("count")).intValue()+1)); r.put("amount",((BigDecimal)r.get("amount")).add(amt));}
    private static void accumulateCount(Map<String, Map<String,Object>> rows,String key,String val){val=trimToNull(val); if(UtilValidate.isEmpty(val))val="_UNKNOWN_"; Map<String,Object> r=rows.get(val); if(r==null){r=new LinkedHashMap<String,Object>(); r.put(key,val); r.put("count",Integer.valueOf(0)); rows.put(val,r);} r.put("count",Integer.valueOf(((Integer)r.get("count")).intValue()+1));}

    private static Map<String,Object> gvToMap(GenericValue value){
        Map<String,Object> row=new LinkedHashMap<String,Object>();
        if(value==null)return row;
        for(String fieldName:value.getAllKeys()) row.put(fieldName,value.get(fieldName));
        return row;
    }
    private static java.util.List<Map<String,Object>> gvListToMaps(java.util.List<GenericValue> values){
        java.util.List<Map<String,Object>> rows=new ArrayList<Map<String,Object>>();
        if(values==null)return rows;
        for(GenericValue value:values) rows.add(gvToMap(value));
        return rows;
    }
    private static String resolveProfileNextAction(String loanStatusId, GenericValue loan, java.util.List<GenericValue> schedules){
        if("LN_DRAFT".equals(loanStatusId))return "APPROVE_LOAN";
        if("LN_APPROVED".equals(loanStatusId))return "DISBURSE_LOAN";
        if("LN_DISB_PENDING".equals(loanStatusId))return "COMPLETE_DISBURSEMENT_FINANCE";
        if("LN_ACTIVE".equals(loanStatusId)||"LN_PARTIAL".equals(loanStatusId)){
            BigDecimal outstanding=safeAmount(loan.getBigDecimal("outstandingAmount"));
            return outstanding.compareTo(BigDecimal.ZERO)>0?"RECORD_REPAYMENT":"CLOSE_LOAN";
        }
        if("LN_CLOSED".equals(loanStatusId))return "NO_ACTION_CLOSED";
        return "REVIEW_LOAN_STATUS";
    }

    private static BigDecimal safeAmount(BigDecimal v){return v!=null?v:BigDecimal.ZERO;}
    private static String trimToNull(String v){if(v==null)return null; String t=v.trim(); return t.isEmpty()?null:t;}
    private static Map<String,Object> standardSuccess(Map<String,Object> data){Map<String,Object> out=ServiceUtil.returnSuccess(); out.putAll(data); out.put("data",new LinkedHashMap<String,Object>(data)); out.put("legacyData",new LinkedHashMap<String,Object>(data)); return out;}
    private static Map<String,Object> standardError(Exception e){Map<String,Object> out=ServiceUtil.returnError(e.getMessage()); out.put("data",new LinkedHashMap<String,Object>()); out.put("legacyData",new LinkedHashMap<String,Object>()); return out;}

}
