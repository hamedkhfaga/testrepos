package org.apache.ofbiz.payroll.services;

import org.apache.ofbiz.base.util.Debug;
import org.apache.ofbiz.base.util.UtilDateTime;
import org.apache.ofbiz.base.util.UtilValidate;

import org.apache.ofbiz.entity.Delegator;
import org.apache.ofbiz.entity.GenericEntityException;
import org.apache.ofbiz.entity.GenericValue;
import org.apache.ofbiz.entity.transaction.TransactionUtil;
import org.apache.ofbiz.entity.util.EntityQuery;

import org.apache.ofbiz.service.DispatchContext;
import org.apache.ofbiz.service.ServiceUtil;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.util.*;
import org.apache.ofbiz.payroll.util.PayrollStatusIds;
import org.apache.ofbiz.payroll.util.PayrollGovernanceUtil;

public class PayrollAllocationServices {
    public static final String MODULE = PayrollAllocationServices.class.getName();

    // ---- Constants (keep consistent with your XML) ----
    private static final String ALLOC_STATUS_ACTIVE = "PAY_ALLOC_ACT";

    // Disbursement Channels (parent IDs)
    private static final String CH_BANK   = "BANK_FILE";
    private static final String CH_CASH   = "CASH";
    private static final String CH_CHEQUE = "CHEQUE";
    private static final String CH_WALLET = "WALLET_FILE";

    // Payment Method Types
    private static final String PMT_EFT    = "EFT_ACCOUNT";
    private static final String PMT_CASH   = "CASH";
    private static final String PMT_CHQ    = "COMPANY_CHECK";
    private static final String PMT_WALLET = "EXT_WALLET";

    private static final Set<String> SUPPORTED_CHANNELS =
            new LinkedHashSet<>(Arrays.asList(CH_BANK, CH_CASH, CH_CHEQUE, CH_WALLET));

    // =========================================================
    // routeEmployeePaymentsForRun
    // =========================================================
public static Map<String, Object> routeEmployeePaymentsForRun(DispatchContext dctx, Map<String, Object> context) {
    Delegator delegator = dctx.getDelegator();

    String payrollRunId = (String) context.get("payrollRunId");
    boolean strict = "Y".equalsIgnoreCase((String) context.get("strictMode"));

    Map<String, Object> data = new LinkedHashMap<>();
    data.put("service", "routeEmployeePaymentsForRun");
    data.put("payrollRunId", payrollRunId);
    data.put("strictMode", strict ? "Y" : "N");
    data.put("generatedAt", UtilDateTime.nowTimestamp());

    if (UtilValidate.isEmpty(payrollRunId)) {
        Map<String, Object> err = ServiceUtil.returnError("payrollRunId is required");
        err.put("data", data);
        return err;
    }

    int netPosCount = 0;
    int routed = 0;
    int missing = 0;
    int updatedRows = 0;

    Map<String, Integer> routedByChannel = new LinkedHashMap<>();
    List<String> missingLines = new ArrayList<>();
    List<String> updatedSamples = new ArrayList<>();

    boolean beganTx = false;

    try {
        // Start TX explicitly so updates cannot silently vanish
        beganTx = TransactionUtil.begin();

        GenericValue run = EntityQuery.use(delegator)
                .from("PayrollRun")
                .where("payrollRunId", payrollRunId)
                .cache(false)
                .queryOne();

        if (run == null) {
            TransactionUtil.rollback(beganTx, "PayrollRun not found", null);
            Map<String, Object> err = ServiceUtil.returnError("PayrollRun not found: " + payrollRunId);
            err.put("data", data);
            return err;
        }

        String orgId = run.getString("organizationPartyId");
        String payrollPeriodId = run.getString("payrollPeriodId");
        String defaultChannel = run.getString("defaultDisbursementChannelId");
        String mode = run.getString("disbursementModeId"); // SINGLE / MIXED

        // Governance / period validation
        PayrollGovernanceUtil.ensureRunAndPeriodEditable(
                delegator,
                orgId,
                payrollPeriodId,
                payrollRunId
        );

        if (UtilValidate.isEmpty(defaultChannel)) {
            defaultChannel = CH_BANK;
        }
        if (UtilValidate.isEmpty(mode)) {
            mode = "MIXED";
        }

        data.put("organizationPartyId", orgId);
        data.put("payrollPeriodId", payrollPeriodId);
        data.put("defaultDisbursementChannelId", defaultChannel);
        data.put("disbursementModeId", mode);

        Timestamp now = UtilDateTime.nowTimestamp();

        List<GenericValue> results = EntityQuery.use(delegator)
                .from("EmployeePayrollResult")
                .where("payrollRunId", payrollRunId)
                .cache(false)
                .queryList();

        for (GenericValue r : results) {
            BigDecimal net = r.getBigDecimal("netSalary");
            if (net == null || net.signum() <= 0) {
                continue;
            }
            netPosCount++;

            String partyId = r.getString("partyId");

            RoutingDecision decision;
            if ("SINGLE".equalsIgnoreCase(mode)) {
                decision = chooseForSingleMode(delegator, partyId, now, defaultChannel);
            } else {
                decision = chooseForSingleMode(delegator, partyId, now, defaultChannel);
            }

            if (decision == null || UtilValidate.isEmpty(decision.channel)) {
                missing++;
                if (missingLines.size() < 25) {
                    missingLines.add(partyId + ": no routing decision");
                }
                if (strict) {
                    TransactionUtil.rollback(beganTx, "Missing routing for partyId=" + partyId, null);
                    Map<String, Object> err = ServiceUtil.returnError("Missing routing decision for employee: " + partyId);
                    data.put("organizationPartyId", orgId);
                    data.put("payrollPeriodId", payrollPeriodId);
                    data.put("netPositiveCount", netPosCount);
                    data.put("routedCount", routed);
                    data.put("missingCount", missing);
                    data.put("updatedRows", updatedRows);
                    data.put("routedByChannel", routedByChannel);
                    data.put("missingLines", missingLines);
                    data.put("updatedSamples", updatedSamples);
                    err.put("data", data);
                    return err;
                }
                continue;
            }

            boolean changed = false;

            String oldChannel = r.getString("disbursementChannelId");
            String oldPaymentMethodId = r.getString("paymentMethodId");

            if (!Objects.equals(oldChannel, decision.channel)) {
                r.set("disbursementChannelId", decision.channel);
                changed = true;
            }
            if (!Objects.equals(oldPaymentMethodId, decision.paymentMethodId)) {
                r.set("paymentMethodId", decision.paymentMethodId);
                changed = true;
            }

            if (changed) {
                r.store();
                updatedRows++;

                if (updatedSamples.size() < 25) {
                    updatedSamples.add(
                            partyId + ": channel=" + decision.channel
                                    + ", paymentMethodId=" + decision.paymentMethodId
                                    + (UtilValidate.isNotEmpty(decision.reason) ? ", reason=" + decision.reason : "")
                    );
                }
            }

            routed++;
            routedByChannel.put(decision.channel,
                    routedByChannel.getOrDefault(decision.channel, 0) + 1);
        }

        TransactionUtil.commit(beganTx);

        data.put("organizationPartyId", orgId);
        data.put("payrollPeriodId", payrollPeriodId);
        data.put("netPositiveCount", netPosCount);
        data.put("routedCount", routed);
        data.put("missingCount", missing);
        data.put("updatedRows", updatedRows);
        data.put("routedByChannel", routedByChannel);
        data.put("missingLines", missingLines);
        data.put("updatedSamples", updatedSamples);

        Map<String, Object> out = ServiceUtil.returnSuccess(
                "###ROUTE_EMPLOYEE_PAYMENTS### routed=" + routed + " missing=" + missing + " updated=" + updatedRows
        );
        out.put("data", data);
        return out;

    } catch (Exception e) {
        Debug.logError(e, "Error in routeEmployeePaymentsForRun", MODULE);
        try {
            TransactionUtil.rollback(beganTx, "routeEmployeePaymentsForRun failed", e);
        } catch (Exception ignored) {}
        Map<String, Object> err = ServiceUtil.returnError("routeEmployeePaymentsForRun failed: " + safeMsg(e));
        err.put("data", data);
        return err;
    }
}

    // =========================================================
    // Routing internals
    // =========================================================
    private static class RoutingDecision {
        final String channel;
        final String paymentMethodId;
        final String reason;
        RoutingDecision(String channel, String pmId, String reason) {
            this.channel = channel;
            this.paymentMethodId = pmId;
            this.reason = reason;
        }
    }

    private static RoutingDecision chooseForSingleMode(Delegator delegator, String partyId, Timestamp now, String forcedChannel)
            throws GenericEntityException {

        if (UtilValidate.isEmpty(forcedChannel)) forcedChannel = CH_BANK;

        // Try allocations that match forced channel (or inferred)
        List<GenericValue> allocs = findActiveAllocations(delegator, partyId, now);
        for (GenericValue a : allocs) {
            String allocCh = a.getString("disbursementChannelId");
            String pmId = a.getString("paymentMethodId");

            if (UtilValidate.isNotEmpty(allocCh) && forcedChannel.equals(allocCh)) {
                return new RoutingDecision(forcedChannel, pmId, "SINGLE_MATCH_ALLOC_CHANNEL");
            }

            if (UtilValidate.isEmpty(allocCh) && UtilValidate.isNotEmpty(pmId)) {
                String inferred = inferChannelFromPaymentMethodId(delegator, pmId);
                if (forcedChannel.equals(inferred)) {
                    return new RoutingDecision(forcedChannel, pmId, "SINGLE_MATCH_INFERRED_PM");
                }
            }
        }

        // fallback payment method by expected type
        String pmFallback = findAnyPartyPaymentMethodByType(delegator, partyId, requiredPaymentMethodType(forcedChannel));
        return new RoutingDecision(forcedChannel, pmFallback, "SINGLE_FORCED_FALLBACK_PM");
    }

    private static RoutingDecision chooseFromAllocations(Delegator delegator, String partyId, Timestamp now, String defaultChannel)
            throws GenericEntityException {

        if (UtilValidate.isEmpty(defaultChannel)) defaultChannel = CH_BANK;

        List<GenericValue> allocs = findActiveAllocations(delegator, partyId, now);

        if (allocs.isEmpty()) {
            String pmFallback = findAnyPartyPaymentMethodByType(delegator, partyId, requiredPaymentMethodType(defaultChannel));
            return new RoutingDecision(defaultChannel, pmFallback, "MIXED_NO_ALLOCS_FALLBACK_DEFAULT");
        }

        for (GenericValue a : allocs) {
            String allocChannel = a.getString("disbursementChannelId");
            String pmId = a.getString("paymentMethodId");

            if (UtilValidate.isNotEmpty(allocChannel)) {
                return new RoutingDecision(allocChannel, pmId, "ALLOC_EXPLICIT_CHANNEL");
            }
            if (UtilValidate.isNotEmpty(pmId)) {
                String inferred = inferChannelFromPaymentMethodId(delegator, pmId);
                if (UtilValidate.isNotEmpty(inferred)) {
                    return new RoutingDecision(inferred, pmId, "ALLOC_INFERRED_FROM_PM");
                }
            }
        }

        String pmFallback = findAnyPartyPaymentMethodByType(delegator, partyId, requiredPaymentMethodType(defaultChannel));
        return new RoutingDecision(defaultChannel, pmFallback, "MIXED_NO_CHANNEL_FALLBACK_DEFAULT");
    }

    private static List<GenericValue> findActiveAllocations(Delegator delegator, String partyId, Timestamp now)
            throws GenericEntityException {

        // IMPORTANT: matches your XML status PAY_ALLOC_ACT
        return EntityQuery.use(delegator)
                .from("EmployeePaymentAllocation")
                .where("partyId", partyId, "statusId", ALLOC_STATUS_ACTIVE)
                .orderBy("prioritySeq")
                .filterByDate(now) // uses fromDate/thruDate
                .cache(false)
                .queryList();
    }

    private static String inferChannelFromPaymentMethodId(Delegator delegator, String paymentMethodId) throws GenericEntityException {
        GenericValue pm = EntityQuery.use(delegator)
                .from("PaymentMethod")
                .where("paymentMethodId", paymentMethodId)
                .cache(false)
                .queryOne();
        if (pm == null) return null;
        return inferChannelFromPaymentMethodType(pm.getString("paymentMethodTypeId"));
    }

    private static String inferChannelFromPaymentMethodType(String paymentMethodTypeId) {
        if (UtilValidate.isEmpty(paymentMethodTypeId)) return null;
        switch (paymentMethodTypeId) {
            case PMT_EFT:    return CH_BANK;
            case PMT_CASH:   return CH_CASH;
            case PMT_CHQ:    return CH_CHEQUE;
            case PMT_WALLET: return CH_WALLET;
            default:         return null;
        }
    }

    private static String requiredPaymentMethodType(String channel) {
        if (CH_BANK.equals(channel))   return PMT_EFT;
        if (CH_CASH.equals(channel))   return PMT_CASH;
        if (CH_CHEQUE.equals(channel)) return PMT_CHQ;
        if (CH_WALLET.equals(channel)) return PMT_WALLET;
        return null;
    }

    private static String findAnyPartyPaymentMethodByType(Delegator delegator, String partyId, String paymentMethodTypeId)
            throws GenericEntityException {

        if (UtilValidate.isEmpty(paymentMethodTypeId)) return null;

        GenericValue pm = EntityQuery.use(delegator)
                .from("PaymentMethod")
                .where("partyId", partyId, "paymentMethodTypeId", paymentMethodTypeId)
                .cache(false)
                .queryFirst();

        return pm == null ? null : pm.getString("paymentMethodId");
    }

    private static void validateChannelAndDestination(Delegator delegator, String partyId, String channel, String paymentMethodId, boolean strict)
            throws GenericEntityException {

        if (!SUPPORTED_CHANNELS.contains(channel)) {
            String msg = "Unsupported disbursementChannelId=" + channel + " for partyId=" + partyId;
            if (strict) throw new RuntimeException(msg);
            Debug.logWarning(msg, MODULE);
            return;
        }

        // For BANK_FILE: enforce EFT_ACCOUNT + EftAccount.accountNumber
        if (CH_BANK.equals(channel)) {
            if (UtilValidate.isEmpty(paymentMethodId)) {
                String msg = "BANK_FILE requires paymentMethodId (EFT_ACCOUNT) for partyId=" + partyId;
                if (strict) throw new RuntimeException(msg);
                Debug.logWarning(msg, MODULE);
                return;
            }

            GenericValue pm = EntityQuery.use(delegator)
                    .from("PaymentMethod")
                    .where("paymentMethodId", paymentMethodId)
                    .cache(false)
                    .queryOne();

            if (pm == null) {
                String msg = "PaymentMethod not found: " + paymentMethodId + " partyId=" + partyId;
                if (strict) throw new RuntimeException(msg);
                Debug.logWarning(msg, MODULE);
                return;
            }

            if (!PMT_EFT.equals(pm.getString("paymentMethodTypeId"))) {
                String msg = "BANK_FILE expects EFT_ACCOUNT but got " + pm.getString("paymentMethodTypeId")
                        + " paymentMethodId=" + paymentMethodId + " partyId=" + partyId;
                if (strict) throw new RuntimeException(msg);
                Debug.logWarning(msg, MODULE);
            }

            GenericValue ea = EntityQuery.use(delegator)
                    .from("EftAccount")
                    .where("paymentMethodId", paymentMethodId)
                    .cache(false)
                    .queryOne();

            if (ea == null || UtilValidate.isEmpty(ea.getString("accountNumber"))) {
                String msg = "Missing EftAccount/accountNumber for paymentMethodId=" + paymentMethodId + " partyId=" + partyId;
                if (strict) throw new RuntimeException(msg);
                Debug.logWarning(msg, MODULE);
            }
        }
    }

    private static String safeMsg(Exception e) {
        return e.getMessage() != null ? e.getMessage() : e.getClass().getName();
    }
}