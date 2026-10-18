package org.apache.ofbiz.payroll.services;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.apache.ofbiz.base.util.Debug;
import org.apache.ofbiz.base.util.UtilDateTime;
import org.apache.ofbiz.base.util.UtilMisc;
import org.apache.ofbiz.base.util.UtilValidate;

import org.apache.ofbiz.entity.Delegator;
import org.apache.ofbiz.entity.GenericValue;
import org.apache.ofbiz.entity.condition.EntityCondition;
import org.apache.ofbiz.entity.condition.EntityOperator;
import org.apache.ofbiz.entity.util.EntityQuery;

import org.apache.ofbiz.service.DispatchContext;
import org.apache.ofbiz.service.ServiceUtil;

public class PayrollComplianceServices {
    public static final String MODULE = PayrollComplianceServices.class.getName();

    public static Map<String, Object> generatePayrollComplianceReport(DispatchContext dctx, Map<String, ?> ctx) {
        Delegator d = dctx.getDelegator();
        GenericValue u = (GenericValue) ctx.get("userLogin");

        String orgId = (String) ctx.get("organizationPartyId");
        Integer y = (Integer) ctx.get("periodYear");
        Integer m = (Integer) ctx.get("periodMonth");
        String type = (String) ctx.get("reportTypeId");

        if (UtilValidate.isEmpty(orgId) || y == null || m == null || UtilValidate.isEmpty(type)) {
            return ServiceUtil.returnError("organizationPartyId, periodYear, periodMonth, reportTypeId are required");
        }

        try {
            String repId = d.getNextSeqId("PayrollComplianceReport");
            GenericValue rep = d.makeValue("PayrollComplianceReport");
            rep.set("payrollComplianceReportId", repId);
            rep.set("organizationPartyId", orgId);
            rep.set("reportTypeId", type);
            rep.set("periodYear", y);
            rep.set("periodMonth", m);
            rep.set("generatedDate", UtilDateTime.nowTimestamp());
            rep.set("generatedByUserLoginId", u != null ? u.getString("userLoginId") : null);
            rep.set("statusId", "REP_NEW");
            d.create(rep);

            BigDecimal totalTax = BigDecimal.ZERO;

            // NOTE: PayrollItem must exist in your model; otherwise replace with your real source entity.
            List<GenericValue> items = EntityQuery.use(d)
                    .from("PayrollItem")
                    .where("organizationPartyId", orgId,
                           "periodYear", y,
                           "periodMonth", m)
                    .queryList();

            for (GenericValue it : items) {
                BigDecimal tax = it.getBigDecimal("taxAmount");
                if (tax != null) totalTax = totalTax.add(tax);
            }

            GenericValue line = d.makeValue("PayrollComplianceReportLine");
            line.set("payrollComplianceReportLineId", d.getNextSeqId("PayrollComplianceReportLine"));
            line.set("payrollComplianceReportId", repId);
            line.set("metricTypeId", "TAX");
            line.set("amount", totalTax);
            d.create(line);

            return UtilMisc.toMap("responseMessage", "success", "payrollComplianceReportId", repId);

        } catch (Exception e) {
            Debug.logError(e, "Error generating compliance report", MODULE);
            return ServiceUtil.returnError("Error generating compliance report: " + e.getMessage());
        }
    }

    public static Map<String, Object> getPayrollComplianceReportList(DispatchContext dctx, Map<String, ?> ctx) {
        Delegator d = dctx.getDelegator();

        try {
            String organizationPartyId = (String) ctx.get("organizationPartyId");
            Long periodYear = (Long) ctx.get("periodYear");
            Long periodMonth = (Long) ctx.get("periodMonth");
            String reportTypeId = (String) ctx.get("reportTypeId");
            String statusId = (String) ctx.get("statusId");

            Integer viewIndex = (Integer) ctx.get("viewIndex");
            Integer viewSize = (Integer) ctx.get("viewSize");
            if (viewIndex == null || viewIndex < 0) viewIndex = 0;
            if (viewSize == null || viewSize <= 0) viewSize = 20;

            List<EntityCondition> conds = new ArrayList<>();
            if (UtilValidate.isNotEmpty(organizationPartyId)) {
                conds.add(EntityCondition.makeCondition("organizationPartyId", organizationPartyId));
            }
            if (periodYear != null) conds.add(EntityCondition.makeCondition("periodYear", periodYear));
            if (periodMonth != null) conds.add(EntityCondition.makeCondition("periodMonth", periodMonth));
            if (UtilValidate.isNotEmpty(reportTypeId)) conds.add(EntityCondition.makeCondition("reportTypeId", reportTypeId));
            if (UtilValidate.isNotEmpty(statusId)) conds.add(EntityCondition.makeCondition("statusId", statusId));

            EntityCondition where = conds.isEmpty() ? null : EntityCondition.makeCondition(conds, EntityOperator.AND);

            long listSize = EntityQuery.use(d)
                    .from("PayrollComplianceReport")
                    .where(where)
                    .queryCount();

            // OFBiz 24-safe paging: fetch up to (viewIndex+1)*viewSize then slice in-memory
            int fetchMax = Math.min(Integer.MAX_VALUE - 1, (viewIndex + 1) * viewSize);

            List<GenericValue> full = EntityQuery.use(d)
                    .from("PayrollComplianceReport")
                    .where(where)
                    .orderBy("-generatedDate")
                    .cursorScrollInsensitive()
                    .maxRows(fetchMax)
                    .queryList();

            int from = Math.min(viewIndex * viewSize, full.size());
            int to = Math.min(from + viewSize, full.size());
            List<GenericValue> page = full.subList(from, to);

            Map<String, Object> out = ServiceUtil.returnSuccess();
            out.put("list", page);
            out.put("listSize", listSize);
            out.put("viewIndex", viewIndex);
            out.put("viewSize", viewSize);
            return out;

        } catch (Exception e) {
            Debug.logError(e, "getPayrollComplianceReportList failed", MODULE);
            return ServiceUtil.returnError("getPayrollComplianceReportList failed: " + e.getMessage());
        }
    }
}
