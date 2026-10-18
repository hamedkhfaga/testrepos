package org.apache.ofbiz.payroll.services;

import java.sql.Timestamp;
import java.util.Map;

import org.apache.ofbiz.base.util.Debug;
import org.apache.ofbiz.base.util.UtilDateTime;
import org.apache.ofbiz.base.util.UtilGenerics;
import org.apache.ofbiz.base.util.UtilMisc;
import org.apache.ofbiz.base.util.UtilValidate;
import org.apache.ofbiz.entity.Delegator;
import org.apache.ofbiz.entity.GenericValue;
import org.apache.ofbiz.service.DispatchContext;
import org.apache.ofbiz.service.ServiceUtil;

public class PayrollAuditServicesV2 {

    public static final String MODULE = PayrollAuditServicesV2.class.getName();

    public static Map<String, Object> createPayrollActionAudit(DispatchContext dctx, Map<String, ?> context) {
        Delegator delegator = dctx.getDelegator();
        GenericValue userLogin = (GenericValue) context.get("userLogin");

        try {
            String payrollActionAuditId = delegator.getNextSeqId("PayrollActionAudit");

            Timestamp nowTs = UtilDateTime.nowTimestamp();

            GenericValue audit = delegator.makeValue("PayrollActionAudit");
            audit.set("payrollActionAuditId", payrollActionAuditId);
            audit.set("organizationPartyId", context.get("organizationPartyId"));
            audit.set("payrollRunId", context.get("payrollRunId"));
            audit.set("payrollPeriodId", context.get("payrollPeriodId"));
            audit.set("bankTransferBatchId", context.get("bankTransferBatchId"));
            audit.set("partyId", context.get("partyId"));
            audit.set("actionTypeId", context.get("actionTypeId"));
            audit.set("actionStatusId", context.get("actionStatusId"));
            audit.set("actionTs", nowTs);
            audit.set("performedByUserLoginId", userLogin != null ? userLogin.getString("userLoginId") : null);
            audit.set("reasonCode", context.get("reasonCode"));
            audit.set("messageText", context.get("messageText"));
            audit.set("requestJson", context.get("requestJson"));
            audit.set("responseJson", context.get("responseJson"));
            audit.set("referenceId", context.get("referenceId"));

            delegator.create(audit);

            Map<String, Object> out = ServiceUtil.returnSuccess("Payroll action audit created");
            Map<String, Object> data = UtilMisc.toMap(
                    "payrollActionAuditId", payrollActionAuditId,
                    "actionTypeId", context.get("actionTypeId"),
                    "actionStatusId", context.get("actionStatusId"),
                    "actionTs", nowTs
            );
            out.put("data", data);
            out.put("legacyData", data);
            return out;

        } catch (Exception e) {
            Debug.logError(e, "Error creating PayrollActionAudit", MODULE);
            return ServiceUtil.returnError("Error creating PayrollActionAudit: " + e.getMessage());
        }
    }
}