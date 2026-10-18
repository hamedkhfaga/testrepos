package org.apache.ofbiz.payroll.services;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.apache.ofbiz.base.util.Debug;
import org.apache.ofbiz.base.util.UtilDateTime;
import org.apache.ofbiz.base.util.UtilValidate;
import org.apache.ofbiz.base.util.UtilMisc;

import org.apache.ofbiz.entity.Delegator;
import org.apache.ofbiz.entity.GenericValue;
import org.apache.ofbiz.entity.util.EntityQuery;

import org.apache.ofbiz.service.DispatchContext;
import org.apache.ofbiz.service.ServiceUtil;
import org.apache.ofbiz.payroll.util.PayrollGovernanceUtil;
public class PayrollPaymentRoutingServicesV3 {
    public static final String MODULE = PayrollPaymentRoutingServicesV3.class.getName();

    private static final String PPI_NEW = "PPI_NEW";

public static Map<String, Object> routeEmployeePaymentsForRunV3(DispatchContext dctx, Map<String, Object> context) {
    Delegator delegator = dctx.getDelegator();

    Map<String, Object> data = new HashMap<>();
    Map<String, Object> legacyData = new HashMap<>();

    String payrollRunId = (String) context.get("payrollRunId");
    String forceRerouteS = (String) context.get("forceReroute");
    String defaultChannelId = (String) context.get("defaultDisbursementChannelId");
    String dryRunS = (String) context.get("dryRun");

    boolean forceReroute = "Y".equalsIgnoreCase(forceRerouteS);
    boolean dryRun = "Y".equalsIgnoreCase(dryRunS);

    if (UtilValidate.isEmpty(payrollRunId)) {
        return errorNonNull("payrollRunId is required", data, legacyData);
    }

    try {
        GenericValue run = EntityQuery.use(delegator)
                .from("PayrollRun")
                .where("payrollRunId", payrollRunId)
                .queryOne();

        if (run == null) {
            return errorNonNull("PayrollRun not found: " + payrollRunId, data, legacyData);
        }

        String organizationPartyId = run.getString("organizationPartyId");
        String payrollPeriodId = run.getString("payrollPeriodId");
        String statusId = run.getString("statusId");

        // Governance pre-check: do this before any routing logic
        PayrollGovernanceUtil.ensureRunAndPeriodEditable(
                delegator,
                organizationPartyId,
                payrollPeriodId,
                payrollRunId
        );

        // Routing business rule: allow only at PR_POST or PR_DONE
        if (!"PR_POST".equals(statusId) && !"PR_DONE".equals(statusId)) {
            return errorNonNull("Run status not routable: " + statusId, data, legacyData);
        }

        long existing = EntityQuery.use(delegator)
                .from("PayrollPaymentInstruction")
                .where("payrollRunId", payrollRunId)
                .queryCount();

        if (existing > 0 && !forceReroute) {
            return errorNonNull("Payment instructions already exist. Use forceReroute=Y", data, legacyData);
        }

        if (!dryRun && existing > 0 && forceReroute) {
            delegator.removeByAnd("PayrollPaymentInstruction", UtilMisc.toMap("payrollRunId", payrollRunId));
        }

        List<GenericValue> results = EntityQuery.use(delegator)
                .from("EmployeePayrollResult")
                .where("payrollRunId", payrollRunId)
                .queryList();

        if (results == null || results.isEmpty()) {
            return errorNonNull("No EmployeePayrollResult found for run=" + payrollRunId, data, legacyData);
        }

        Timestamp now = UtilDateTime.nowTimestamp();

        long instrCount = 0L;
        long bankCount = 0L;
        long cashCount = 0L;
        long chequeCount = 0L;
        long walletCount = 0L;

        for (GenericValue r : results) {
            String partyId = r.getString("partyId");
            BigDecimal net = r.getBigDecimal("netSalary");
            if (net == null) {
                net = BigDecimal.ZERO;
            }

            // Choose channel: prefer result.disbursementChannelId; fallback to default; else BANK
            String channel = r.getString("disbursementChannelId");
            if (UtilValidate.isEmpty(channel)) {
                channel = defaultChannelId;
            }
            if (UtilValidate.isEmpty(channel)) {
                channel = "BANK";
            }

            if (!dryRun) {
                String id = delegator.getNextSeqId("PayrollPaymentInstruction");

                GenericValue ppi = delegator.makeValue("PayrollPaymentInstruction");
                ppi.set("paymentInstructionId", id);
                ppi.set("payrollRunId", payrollRunId);
                ppi.set("organizationPartyId", organizationPartyId);
                ppi.set("partyId", partyId);
                ppi.set("amount", net);
                ppi.set("currencyUomId", r.getString("currencyUomId"));
                ppi.set("disbursementChannelId", channel);

                // Bank details (if present in result)
                ppi.set("bankName", r.getString("bankName"));
                ppi.set("bankAccountNumber", r.getString("bankAccountNumber"));
                ppi.set("finAccountId", r.getString("finAccountId"));

                ppi.set("paymentMethodId", r.getString("paymentMethodId"));
                ppi.set("statusId", PPI_NEW);
                ppi.set("createdDateTime", now);

                ppi.create();
            }

            instrCount++;

            if ("BANK".equalsIgnoreCase(channel)) {
                bankCount++;
            } else if ("CASH".equalsIgnoreCase(channel)) {
                cashCount++;
            } else if ("CHEQUE".equalsIgnoreCase(channel)) {
                chequeCount++;
            } else if ("WALLET".equalsIgnoreCase(channel)) {
                walletCount++;
            }
        }

        Map<String, Object> out = ServiceUtil.returnSuccess("###PAYROLL_PAY_ROUTE_V3### instructions=" + instrCount);

        data.put("payrollRunId", payrollRunId);
        data.put("organizationPartyId", organizationPartyId);
        data.put("payrollPeriodId", payrollPeriodId);
        data.put("instructionCount", instrCount);
        data.put("bankCount", bankCount);
        data.put("cashCount", cashCount);
        data.put("chequeCount", chequeCount);
        data.put("walletCount", walletCount);
        data.put("dryRun", dryRun ? "Y" : "N");
        data.put("forceReroute", forceReroute ? "Y" : "N");
        data.put("routedAt", now);

        legacyData.putAll(data);

        out.put("data", data);
        out.put("legacyData", legacyData);
        out.put("instructionCount", instrCount);

        return out;

    } catch (Exception e) {
        Debug.logError(e, "routeEmployeePaymentsForRunV3 failed", MODULE);
        return ServiceUtil.returnError("routeEmployeePaymentsForRunV3 failed: " + e.getMessage());
    }
}
    private static Map<String, Object> errorNonNull(String msg, Map<String, Object> data, Map<String, Object> legacyData) {
        Map<String, Object> out = ServiceUtil.returnError(msg);
        out.put("data", data);
        out.put("legacyData", legacyData);
        return out;
    }
}