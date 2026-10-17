package org.apache.ofbiz.loan.services;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.apache.ofbiz.base.util.Debug;
import org.apache.ofbiz.base.util.UtilDateTime;
import org.apache.ofbiz.base.util.UtilMisc;
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

public final class LoanAccountingRepairServices {
    public static final String MODULE = LoanAccountingRepairServices.class.getName();

    private static final String PAYMENT_ACCTG_TRANS = "PAYMENT_ACCTG_TRANS";
    private static final String INCOMING_PAYMENT = "INCOMING_PAYMENT";
    private static final String PAYMENT_APPL = "PAYMENT_APPL";

    private LoanAccountingRepairServices() {}

    /**
     * Phase 3 repair service.
     *
     * Scope:
     * - Repairs only repayment-backed payments linked from PartyLoanRepayment.
     * - Honors dryRun strictly. Default is Y for safety.
     * - Does not create duplicate expected accounting types.
     * - Repairs PAYMENT_ACCTG_TRANS using a direct, idempotent repair insert when partial accounting already exists.
     * - Attempts native OFBiz regeneration for INCOMING_PAYMENT and PAYMENT_APPL through safe service hooks.
     */
    public static Map<String, Object> backfillLoanRepaymentAccountingV1(DispatchContext dctx, Map<String, ?> context) {
        Delegator delegator = dctx.getDelegator();
        LocalDispatcher dispatcher = dctx.getDispatcher();
        GenericValue userLogin = (GenericValue) context.get("userLogin");

        String loanId = trimToNull((String) context.get("loanId"));
        String loanRepaymentId = trimToNull((String) context.get("loanRepaymentId"));
        String paymentIdFilter = trimToNull((String) context.get("paymentId"));
        String organizationPartyId = trimToNull((String) context.get("organizationPartyId"));
        String dryRun = normalizeFlagDefaultY((String) context.get("dryRun"));
        String createFinAccountTrans = normalizeFlag((String) context.get("createFinAccountTrans"));

        try {
            if (UtilValidate.isEmpty(loanId)
                    && UtilValidate.isEmpty(loanRepaymentId)
                    && UtilValidate.isEmpty(paymentIdFilter)
                    && UtilValidate.isEmpty(organizationPartyId)) {
                return ServiceUtil.returnError("At least one of loanId, loanRepaymentId, paymentId, organizationPartyId is required");
            }

            List<GenericValue> repayments = findTargetRepayments(delegator, loanId, loanRepaymentId, paymentIdFilter);
            if (UtilValidate.isEmpty(repayments)) {
                return ServiceUtil.returnError("No PartyLoanRepayment rows matched the requested filters");
            }

            int checkedRepaymentCount = 0;
            int candidateCount = 0;
            int skippedCount = 0;
            int existingAcctgCount = 0;
            int createdAcctgCount = 0;
            int existingFinTransCount = 0;
            int createdFinTransCount = 0;
            int repairedIncomingPaymentCount = 0;
            int repairedPaymentApplicationCount = 0;
            int errorCount = 0;

            List<Map<String, Object>> actions = new ArrayList<Map<String, Object>>();

            for (GenericValue repayment : repayments) {
                checkedRepaymentCount++;

                String currentLoanRepaymentId = repayment.getString("loanRepaymentId");
                String currentLoanId = repayment.getString("loanId");
                String paymentId = trimToNull(repayment.getString("paymentId"));
                String sourceEnumId = trimToNull(repayment.getString("repaymentSourceEnumId"));
                Timestamp repaymentDate = repayment.getTimestamp("repaymentDate");
                BigDecimal amountApplied = safeAmount(repayment.getBigDecimal("amountApplied"));
                String remarks = trimToNull(repayment.getString("remarks"));

                GenericValue loan = findLoan(delegator, currentLoanId);
                String resolvedOrgId = organizationPartyId;
                if (UtilValidate.isEmpty(resolvedOrgId) && loan != null) {
                    resolvedOrgId = trimToNull(loan.getString("organizationPartyId"));
                }
                String finAccountId = loan != null ? trimToNull(loan.getString("finAccountId")) : null;

                Map<String, Object> action = new LinkedHashMap<String, Object>();
                action.put("loanRepaymentId", currentLoanRepaymentId);
                action.put("loanId", currentLoanId);
                action.put("paymentId", paymentId);
                action.put("organizationPartyId", resolvedOrgId);
                action.put("amountApplied", amountApplied);
                action.put("repaymentSourceEnumId", sourceEnumId);

                if (UtilValidate.isEmpty(paymentId)) {
                    skippedCount++;
                    action.put("status", "SKIPPED");
                    action.put("reason", "Repayment has no paymentId; run backfillLoanRepaymentPaymentV1 first if this row should be repaired");
                    actions.add(action);
                    continue;
                }
                if (UtilValidate.isEmpty(resolvedOrgId)) {
                    errorCount++;
                    action.put("status", "ERROR");
                    action.put("reason", "organizationPartyId could not be resolved");
                    actions.add(action);
                    continue;
                }

                GenericValue payment = EntityQuery.use(delegator).from("Payment").where("paymentId", paymentId).queryOne();
                if (payment == null) {
                    errorCount++;
                    action.put("status", "ERROR");
                    action.put("reason", "Payment not found");
                    actions.add(action);
                    continue;
                }

                AccountingState state = inspectAccountingState(delegator, paymentId, resolvedOrgId, finAccountId);
                action.put("existingAcctgTransCount", Integer.valueOf(state.acctgTransCount));
                action.put("existingFinAccountTransCount", Integer.valueOf(state.finAccountTransCount));
                action.put("hasPaymentAcctgTrans", Boolean.valueOf(state.hasPaymentAcctgTrans));
                action.put("hasIncomingPaymentTrans", Boolean.valueOf(state.hasIncomingPaymentTrans));
                action.put("hasPaymentApplicationTrans", Boolean.valueOf(state.hasPaymentApplicationTrans));
                action.put("paymentApplicationCount", Integer.valueOf(state.paymentApplicationCount));

                if (state.acctgTransCount > 0) {
                    existingAcctgCount++;
                }
                if (state.finAccountTransCount > 0) {
                    existingFinTransCount++;
                }

                boolean missingPaymentAcctg = !state.hasPaymentAcctgTrans;
                boolean missingIncomingPayment = !state.hasIncomingPaymentTrans;
                boolean missingPaymentApplication = !state.hasPaymentApplicationTrans;
                boolean missingFinTrans = "Y".equals(createFinAccountTrans)
                        && UtilValidate.isNotEmpty(finAccountId)
                        && state.finAccountTransCount <= 0;

                if (!missingPaymentAcctg && !missingIncomingPayment && !missingPaymentApplication && !missingFinTrans) {
                    skippedCount++;
                    action.put("status", "SKIPPED");
                    action.put("reason", "Repayment payment already has the complete accepted 3-transaction accounting model");
                    actions.add(action);
                    continue;
                }

                action.put("missingPaymentAcctgTrans", Boolean.valueOf(missingPaymentAcctg));
                action.put("missingIncomingPaymentTrans", Boolean.valueOf(missingIncomingPayment));
                action.put("missingPaymentApplicationTrans", Boolean.valueOf(missingPaymentApplication));
                action.put("missingFinAccountTrans", Boolean.valueOf(missingFinTrans));

                if (missingPaymentApplication && state.paymentApplicationCount <= 0) {
                    skippedCount++;
                    action.put("status", "BLOCKED_DEPENDENCY");
                    action.put("reason", "Missing PaymentApplication; run backfillLoanRepaymentPaymentApplicationV1 first");
                    action.put("requiredRepairService", "backfillLoanRepaymentPaymentApplicationV1");
                    action.put("dependencyType", "PAYMENT_APPLICATION_MISSING");
                    actions.add(action);
                    continue;
                }

                candidateCount++;

                if ("Y".equals(dryRun)) {
                    action.put("status", "DRY_RUN");
                    action.put("reason", "Would attempt to repair missing repayment accounting artifacts without creating duplicates");
                    action.put("wouldCreatePaymentAcctgTrans", Boolean.valueOf(missingPaymentAcctg));
                    action.put("wouldAttemptIncomingPaymentAccounting", Boolean.valueOf(missingIncomingPayment));
                    action.put("wouldAttemptPaymentApplicationAccounting", Boolean.valueOf(missingPaymentApplication));
                    action.put("wouldCreateFinAccountTrans", Boolean.valueOf(missingFinTrans));
                    actions.add(action);
                    continue;
                }

                try {
                    if (missingPaymentAcctg) {
                        String createdPaymentAcctgTransId = createPaymentAcctgTransRepairDirect(
                                delegator,
                                loan,
                                payment,
                                currentLoanId,
                                paymentId,
                                resolvedOrgId,
                                sourceEnumId,
                                amountApplied,
                                repaymentDate,
                                remarks
                        );
                        createdAcctgCount++;
                        action.put("createdPaymentAcctgTransId", createdPaymentAcctgTransId);
                    }

                    if (missingIncomingPayment) {
                        String incomingAcctgTransId = createIncomingPaymentAcctgTransRepairDirect(
                                delegator,
                                loan,
                                payment,
                                paymentId,
                                resolvedOrgId,
                                amountApplied,
                                repaymentDate
                        );
                        repairedIncomingPaymentCount++;
                        action.put("createdIncomingPaymentAcctgTransId", incomingAcctgTransId);
                        action.put("incomingPaymentRepairStatus", "DONE");
                        action.put("incomingPaymentRepairMessage", "INCOMING_PAYMENT accounting repaired directly without changing payment status");
                    }

                    if (missingPaymentApplication) {
                        String paymentApplicationAcctgTransId = createPaymentApplicationAcctgTransRepairDirect(
                                delegator,
                                loan,
                                paymentId,
                                resolvedOrgId,
                                amountApplied,
                                repaymentDate
                        );
                        repairedPaymentApplicationCount++;
                        action.put("createdPaymentApplicationAcctgTransId", paymentApplicationAcctgTransId);
                        action.put("paymentApplicationRepairStatus", "DONE");
                        action.put("paymentApplicationRepairMessage", "PAYMENT_APPL accounting repaired directly");
                    }

                    if (missingFinTrans) {
                        Map<String, Object> finCtx = new LinkedHashMap<String, Object>();
                        finCtx.put("finAccountId", finAccountId);
                        finCtx.put("paymentId", paymentId);
                        finCtx.put("organizationPartyId", resolvedOrgId);
                        finCtx.put("amount", amountApplied);
                        finCtx.put("repaymentDate", repaymentDate);
                        finCtx.put("repaymentSourceEnumId", sourceEnumId);
                        finCtx.put("loanId", currentLoanId);
                        finCtx.put("remarks", remarks);
                        finCtx.put("userLogin", userLogin);

                        Map<String, Object> finResp = dispatcher.runSync("createFinAccountTransForLoanRepayment", finCtx);
                        if (ServiceUtil.isError(finResp)) {
                            throw new GenericEntityException("createFinAccountTransForLoanRepayment failed: " + ServiceUtil.getErrorMessage(finResp));
                        }
                        createdFinTransCount++;
                        action.put("createdFinAccountTransId", finResp.get("finAccountTransId"));
                    }

                    AccountingState afterState = inspectAccountingState(delegator, paymentId, resolvedOrgId, finAccountId);
                    action.put("afterAcctgTransCount", Integer.valueOf(afterState.acctgTransCount));
                    action.put("afterHasPaymentAcctgTrans", Boolean.valueOf(afterState.hasPaymentAcctgTrans));
                    action.put("afterHasIncomingPaymentTrans", Boolean.valueOf(afterState.hasIncomingPaymentTrans));
                    action.put("afterHasPaymentApplicationTrans", Boolean.valueOf(afterState.hasPaymentApplicationTrans));

                    if (afterState.hasPaymentAcctgTrans && afterState.hasIncomingPaymentTrans && afterState.hasPaymentApplicationTrans) {
                        action.put("status", "DONE");
                        action.put("reason", "Repayment payment now has complete accepted 3-transaction accounting model");
                    } else {
                        errorCount++;
                        action.put("status", "PARTIAL");
                        action.put("reason", "Repair ran but repayment payment still does not have the complete accepted 3-transaction accounting model");
                    }
                } catch (Exception e) {
                    errorCount++;
                    action.put("status", "ERROR");
                    action.put("reason", e.getMessage());
                    Debug.logError(e, "Error repairing accounting for loanRepaymentId=" + currentLoanRepaymentId, MODULE);
                }

                actions.add(action);
            }

            Map<String, Object> payload = new LinkedHashMap<String, Object>();
            payload.put("loanId", loanId);
            payload.put("loanRepaymentId", loanRepaymentId);
            payload.put("paymentId", paymentIdFilter);
            payload.put("organizationPartyId", organizationPartyId);
            payload.put("dryRun", dryRun);
            payload.put("createFinAccountTrans", createFinAccountTrans);
            payload.put("checkedRepaymentCount", Integer.valueOf(checkedRepaymentCount));
            payload.put("candidateCount", Integer.valueOf(candidateCount));
            payload.put("skippedCount", Integer.valueOf(skippedCount));
            payload.put("existingAcctgCount", Integer.valueOf(existingAcctgCount));
            payload.put("createdAcctgCount", Integer.valueOf(createdAcctgCount));
            payload.put("existingFinTransCount", Integer.valueOf(existingFinTransCount));
            payload.put("createdFinTransCount", Integer.valueOf(createdFinTransCount));
            payload.put("repairedIncomingPaymentCount", Integer.valueOf(repairedIncomingPaymentCount));
            payload.put("repairedPaymentApplicationCount", Integer.valueOf(repairedPaymentApplicationCount));
            payload.put("errorCount", Integer.valueOf(errorCount));
            payload.put("actions", actions);

            Map<String, Object> result = ServiceUtil.returnSuccess("Loan repayment accounting repair completed successfully");
            result.putAll(payload);
            result.put("data", new LinkedHashMap<String, Object>(payload));
            result.put("legacyData", new LinkedHashMap<String, Object>(payload));
            return result;
        } catch (Exception e) {
            Debug.logError(e, "Error running backfillLoanRepaymentAccountingV1", MODULE);
            return ServiceUtil.returnError(e.getMessage());
        }
    }


    /**
     * Phase 6E controlled amount consistency repair.
     *
     * Scope:
     * - Repairs historical amount mismatches only for repayment-backed payments.
     * - Default dryRun is Y.
     * - Payment.amount repair is OFF by default because it changes a financial source record.
     * - PaymentApplication.amountApplied and AcctgTransEntry amount repair are ON by default.
     * - Uses PartyLoanRepayment.amountApplied as the conservative source of truth.
     */
    public static Map<String, Object> backfillLoanRepaymentAmountConsistencyV1(DispatchContext dctx, Map<String, ?> context) {
        Delegator delegator = dctx.getDelegator();

        String loanId = trimToNull((String) context.get("loanId"));
        String loanRepaymentId = trimToNull((String) context.get("loanRepaymentId"));
        String paymentIdFilter = trimToNull((String) context.get("paymentId"));
        String organizationPartyId = trimToNull((String) context.get("organizationPartyId"));
        String dryRun = normalizeFlagDefaultY((String) context.get("dryRun"));
        String repairPaymentAmount = normalizeFlag((String) context.get("repairPaymentAmount"));
        String repairPaymentApplicationAmount = normalizeFlagDefaultY((String) context.get("repairPaymentApplicationAmount"));
        String repairAcctgEntryAmount = normalizeFlagDefaultY((String) context.get("repairAcctgEntryAmount"));

        try {
            if (UtilValidate.isEmpty(loanId)
                    && UtilValidate.isEmpty(loanRepaymentId)
                    && UtilValidate.isEmpty(paymentIdFilter)
                    && UtilValidate.isEmpty(organizationPartyId)) {
                return ServiceUtil.returnError("At least one of loanId, loanRepaymentId, paymentId, organizationPartyId is required");
            }

            List<GenericValue> repayments = findTargetRepayments(delegator, loanId, loanRepaymentId, paymentIdFilter);
            if (UtilValidate.isEmpty(repayments)) {
                return ServiceUtil.returnError("No PartyLoanRepayment rows matched the requested filters");
            }

            int checkedRepaymentCount = 0;
            int candidateCount = 0;
            int skippedCount = 0;
            int repairedPaymentCount = 0;
            int repairedPaymentApplicationCount = 0;
            int repairedAcctgEntryCount = 0;
            int blockedCount = 0;
            int errorCount = 0;
            List<Map<String, Object>> actions = new ArrayList<Map<String, Object>>();

            for (GenericValue repayment : repayments) {
                checkedRepaymentCount++;

                String currentLoanRepaymentId = repayment.getString("loanRepaymentId");
                String currentLoanId = repayment.getString("loanId");
                String paymentId = trimToNull(repayment.getString("paymentId"));
                BigDecimal expectedAmount = safeAmount(repayment.getBigDecimal("amountApplied"));

                GenericValue loan = findLoan(delegator, currentLoanId);
                String resolvedOrgId = organizationPartyId;
                if (UtilValidate.isEmpty(resolvedOrgId) && loan != null) {
                    resolvedOrgId = trimToNull(loan.getString("organizationPartyId"));
                }

                Map<String, Object> action = new LinkedHashMap<String, Object>();
                action.put("loanRepaymentId", currentLoanRepaymentId);
                action.put("loanId", currentLoanId);
                action.put("paymentId", paymentId);
                action.put("organizationPartyId", resolvedOrgId);
                action.put("expectedAmount", expectedAmount);

                if (UtilValidate.isEmpty(paymentId)) {
                    skippedCount++;
                    action.put("status", "SKIPPED");
                    action.put("reason", "Repayment has no paymentId; amount consistency cannot be repaired here");
                    actions.add(action);
                    continue;
                }
                if (UtilValidate.isEmpty(resolvedOrgId)) {
                    errorCount++;
                    action.put("status", "ERROR");
                    action.put("reason", "organizationPartyId could not be resolved");
                    actions.add(action);
                    continue;
                }
                if (expectedAmount.compareTo(BigDecimal.ZERO) <= 0) {
                    blockedCount++;
                    action.put("status", "BLOCKED_DATA_QUALITY");
                    action.put("reason", "PartyLoanRepayment.amountApplied must be greater than zero before amount consistency repair");
                    actions.add(action);
                    continue;
                }

                GenericValue payment = EntityQuery.use(delegator).from("Payment").where("paymentId", paymentId).queryOne();
                if (payment == null) {
                    errorCount++;
                    action.put("status", "ERROR");
                    action.put("reason", "Payment not found");
                    actions.add(action);
                    continue;
                }

                BigDecimal paymentAmount = safeAmount(payment.getBigDecimal("amount"));
                List<GenericValue> apps = EntityQuery.use(delegator)
                        .from("PaymentApplication")
                        .where("paymentId", paymentId)
                        .orderBy("paymentApplicationId")
                        .queryList();
                BigDecimal totalApplied = BigDecimal.ZERO;
                for (GenericValue app : apps) {
                    totalApplied = totalApplied.add(safeAmount(app.getBigDecimal("amountApplied")));
                }

                List<GenericValue> entries = findExpectedRepaymentAcctgEntries(delegator, paymentId, resolvedOrgId);
                boolean paymentMismatch = paymentAmount.compareTo(expectedAmount) != 0;
                boolean appMismatch = apps.size() > 0 && totalApplied.compareTo(expectedAmount) != 0;
                boolean acctgMismatch = entriesAmountMismatch(entries, expectedAmount);

                action.put("paymentAmount", paymentAmount);
                action.put("paymentApplicationCount", Integer.valueOf(apps.size()));
                action.put("paymentApplicationAmount", totalApplied);
                action.put("expectedAcctgEntryCount", Integer.valueOf(entries.size()));
                action.put("paymentAmountMismatch", Boolean.valueOf(paymentMismatch));
                action.put("paymentApplicationAmountMismatch", Boolean.valueOf(appMismatch));
                action.put("acctgEntryAmountMismatch", Boolean.valueOf(acctgMismatch));

                if (!paymentMismatch && !appMismatch && !acctgMismatch) {
                    skippedCount++;
                    action.put("status", "SKIPPED");
                    action.put("reason", "Payment, payment application, and repayment accounting amounts are already consistent");
                    actions.add(action);
                    continue;
                }

                candidateCount++;

                if (paymentMismatch && !"Y".equals(repairPaymentAmount)) {
                    blockedCount++;
                    action.put("status", "BLOCKED_APPROVAL_REQUIRED");
                    action.put("reason", "Payment.amount differs from PartyLoanRepayment.amountApplied. Re-run with repairPaymentAmount=Y only after approval because Payment is a financial source record");
                    action.put("requiredFlag", "repairPaymentAmount=Y");
                    actions.add(action);
                    continue;
                }
                if (appMismatch && apps.size() != 1) {
                    blockedCount++;
                    action.put("status", "BLOCKED_DATA_QUALITY");
                    action.put("reason", "PaymentApplication amount repair requires exactly one PaymentApplication for the payment");
                    actions.add(action);
                    continue;
                }

                action.put("wouldRepairPaymentAmount", Boolean.valueOf(paymentMismatch && "Y".equals(repairPaymentAmount)));
                action.put("wouldRepairPaymentApplicationAmount", Boolean.valueOf(appMismatch && "Y".equals(repairPaymentApplicationAmount)));
                action.put("wouldRepairAcctgEntryAmount", Boolean.valueOf(acctgMismatch && "Y".equals(repairAcctgEntryAmount)));

                if ("Y".equals(dryRun)) {
                    action.put("status", "DRY_RUN");
                    action.put("reason", "Would repair historical repayment amount consistency using PartyLoanRepayment.amountApplied as source of truth");
                    actions.add(action);
                    continue;
                }

                try {
                    if (paymentMismatch && "Y".equals(repairPaymentAmount)) {
                        payment.set("amount", expectedAmount);
                        payment.store();
                        repairedPaymentCount++;
                        action.put("repairedPaymentAmount", expectedAmount);
                    }

                    if (appMismatch && "Y".equals(repairPaymentApplicationAmount)) {
                        GenericValue app = apps.get(0);
                        app.set("amountApplied", expectedAmount);
                        app.store();
                        repairedPaymentApplicationCount++;
                        action.put("repairedPaymentApplicationId", app.getString("paymentApplicationId"));
                        action.put("repairedPaymentApplicationAmount", expectedAmount);
                    }

                    if (acctgMismatch && "Y".equals(repairAcctgEntryAmount)) {
                        int updated = repairAcctgEntryAmounts(entries, expectedAmount);
                        repairedAcctgEntryCount += updated;
                        action.put("repairedAcctgEntryCount", Integer.valueOf(updated));
                    }

                    action.put("status", "DONE");
                    action.put("reason", "Historical repayment amount consistency repaired successfully");
                } catch (Exception e) {
                    errorCount++;
                    action.put("status", "ERROR");
                    action.put("reason", e.getMessage());
                    Debug.logError(e, "Error repairing amount consistency for loanRepaymentId=" + currentLoanRepaymentId, MODULE);
                }

                actions.add(action);
            }

            Map<String, Object> payload = new LinkedHashMap<String, Object>();
            payload.put("loanId", loanId);
            payload.put("loanRepaymentId", loanRepaymentId);
            payload.put("paymentId", paymentIdFilter);
            payload.put("organizationPartyId", organizationPartyId);
            payload.put("dryRun", dryRun);
            payload.put("repairPaymentAmount", repairPaymentAmount);
            payload.put("repairPaymentApplicationAmount", repairPaymentApplicationAmount);
            payload.put("repairAcctgEntryAmount", repairAcctgEntryAmount);
            payload.put("checkedRepaymentCount", Integer.valueOf(checkedRepaymentCount));
            payload.put("candidateCount", Integer.valueOf(candidateCount));
            payload.put("skippedCount", Integer.valueOf(skippedCount));
            payload.put("blockedCount", Integer.valueOf(blockedCount));
            payload.put("repairedPaymentCount", Integer.valueOf(repairedPaymentCount));
            payload.put("repairedPaymentApplicationCount", Integer.valueOf(repairedPaymentApplicationCount));
            payload.put("repairedAcctgEntryCount", Integer.valueOf(repairedAcctgEntryCount));
            payload.put("errorCount", Integer.valueOf(errorCount));
            payload.put("actions", actions);

            Map<String, Object> result = ServiceUtil.returnSuccess("Loan repayment amount consistency repair completed successfully");
            result.putAll(payload);
            result.put("data", new LinkedHashMap<String, Object>(payload));
            result.put("legacyData", new LinkedHashMap<String, Object>(payload));
            return result;
        } catch (Exception e) {
            Debug.logError(e, "Error running backfillLoanRepaymentAmountConsistencyV1", MODULE);
            return ServiceUtil.returnError(e.getMessage());
        }
    }



    /**
     * Direct repair for the custom loan repayment PAYMENT_ACCTG_TRANS only.
     *
     * Why this exists:
     * createAcctgTransForLoanRepayment intentionally blocks any paymentId that already has
     * an AcctgTrans row. That is correct for normal runtime idempotency, but not sufficient
     * for Phase 3 historical repairs where an older payment may have PAYMENT_APPL but is
     * missing PAYMENT_ACCTG_TRANS. This method is therefore intentionally narrow:
     * it creates exactly one PAYMENT_ACCTG_TRANS, only after the caller has verified that
     * this expected type is missing.
     */
    private static String createPaymentAcctgTransRepairDirect(
            Delegator delegator,
            GenericValue loan,
            GenericValue payment,
            String loanId,
            String paymentId,
            String organizationPartyId,
            String repaymentSourceEnumId,
            BigDecimal amount,
            Timestamp repaymentDate,
            String remarks) throws GenericEntityException {

        if (UtilValidate.isEmpty(paymentId)) {
            throw new GenericEntityException("paymentId is required for PAYMENT_ACCTG_TRANS repair");
        }
        if (UtilValidate.isEmpty(organizationPartyId)) {
            throw new GenericEntityException("organizationPartyId is required for PAYMENT_ACCTG_TRANS repair");
        }
        if (amount == null || amount.compareTo(BigDecimal.ZERO) <= 0) {
            throw new GenericEntityException("amount must be greater than zero for PAYMENT_ACCTG_TRANS repair");
        }

        GenericValue existingPaymentAcctg = EntityQuery.use(delegator)
                .from("AcctgTrans")
                .where("paymentId", paymentId, "acctgTransTypeId", PAYMENT_ACCTG_TRANS)
                .queryFirst();
        if (existingPaymentAcctg != null) {
            return existingPaymentAcctg.getString("acctgTransId");
        }

        LoanAcctgPostingCfg cfg = resolveLoanAcctgPostingCfg(delegator, organizationPartyId, repaymentSourceEnumId);
        if (UtilValidate.isEmpty(cfg.debitGlAccountId) || UtilValidate.isEmpty(cfg.creditGlAccountId)) {
            throw new GenericEntityException("Unable to resolve debit/credit GL accounts for repayment accounting repair. organizationPartyId="
                    + organizationPartyId + ", repaymentSourceEnumId=" + repaymentSourceEnumId);
        }
        validateGlAccountOrganization(delegator, cfg.debitGlAccountId, organizationPartyId, "PAYMENT_ACCTG_TRANS debit");
        validateGlAccountOrganization(delegator, cfg.creditGlAccountId, organizationPartyId, "PAYMENT_ACCTG_TRANS credit");

        String acctgTransId = delegator.getNextSeqId("AcctgTrans");
        Timestamp txDate = repaymentDate != null ? repaymentDate : UtilDateTime.nowTimestamp();
        String currencyUomId = null;
        if (payment != null) {
            currencyUomId = trimToNull(payment.getString("currencyUomId"));
        }
        if (UtilValidate.isEmpty(currencyUomId) && loan != null) {
            currencyUomId = trimToNull(loan.getString("currencyUomId"));
        }

        GenericValue trans = delegator.makeValue("AcctgTrans");
        trans.set("acctgTransId", acctgTransId);
        trans.set("acctgTransTypeId", PAYMENT_ACCTG_TRANS);
        trans.set("glFiscalTypeId", UtilValidate.isNotEmpty(cfg.glFiscalTypeId) ? cfg.glFiscalTypeId : "ACTUAL");
        trans.set("transactionDate", txDate);
        trans.set("isPosted", "Y");
        trans.set("paymentId", paymentId);
        trans.set("description", "Loan repayment paymentId=" + paymentId + " source=" + repaymentSourceEnumId
                + (UtilValidate.isNotEmpty(remarks) ? " repair=" + remarks : " repair=Phase3"));
        delegator.create(trans);

        createAcctgTransEntryRepairDirect(delegator, acctgTransId, "00001", organizationPartyId,
                cfg.debitGlAccountId, "D", amount, currencyUomId, loan);
        createAcctgTransEntryRepairDirect(delegator, acctgTransId, "00002", organizationPartyId,
                cfg.creditGlAccountId, "C", amount, currencyUomId, loan);

        return acctgTransId;
    }

    private static void createAcctgTransEntryRepairDirect(
            Delegator delegator,
            String acctgTransId,
            String seqId,
            String organizationPartyId,
            String glAccountId,
            String debitCreditFlag,
            BigDecimal amount,
            String currencyUomId,
            GenericValue loan) throws GenericEntityException {

        GenericValue entry = delegator.makeValue("AcctgTransEntry");
        entry.set("acctgTransId", acctgTransId);
        entry.set("acctgTransEntrySeqId", seqId);
        entry.set("acctgTransEntryTypeId", "_NA_");
        entry.set("organizationPartyId", organizationPartyId);
        entry.set("glAccountId", glAccountId);
        entry.set("debitCreditFlag", debitCreditFlag);
        entry.set("amount", amount);
        entry.set("origAmount", amount);
        if (UtilValidate.isNotEmpty(currencyUomId)) {
            entry.set("currencyUomId", currencyUomId);
            entry.set("origCurrencyUomId", currencyUomId);
        }
        if (loan != null && UtilValidate.isNotEmpty(loan.getString("partyId"))) {
            entry.set("partyId", loan.getString("partyId"));
        }
        delegator.create(entry);
    }

    private static LoanAcctgPostingCfg resolveLoanAcctgPostingCfg(
            Delegator delegator,
            String organizationPartyId,
            String repaymentSourceEnumId) throws GenericEntityException {

        LoanAcctgPostingCfg cfg = new LoanAcctgPostingCfg();
        String source = UtilValidate.isNotEmpty(repaymentSourceEnumId) ? repaymentSourceEnumId : "LNRS_MANUAL";

        List<GenericValue> cfgRows = EntityQuery.use(delegator)
                .from("LoanAcctgCfg")
                .where("organizationPartyId", organizationPartyId, "isActive", "Y")
                .orderBy("-fromDate", "loanAcctgCfgId")
                .queryList();

        for (GenericValue cfgRow : cfgRows) {
            GenericValue dtl = EntityQuery.use(delegator)
                    .from("LoanAcctgCfgDtl")
                    .where("loanAcctgCfgId", cfgRow.getString("loanAcctgCfgId"), "repaymentSourceEnumId", source)
                    .queryOne();
            if (dtl != null) {
                cfg.acctgTransTypeId = trimToNull(dtl.getString("acctgTransTypeId"));
                cfg.glFiscalTypeId = trimToNull(dtl.getString("glFiscalTypeId"));
                cfg.debitGlAccountId = trimToNull(dtl.getString("debitGlAccountId"));
                cfg.creditGlAccountId = trimToNull(dtl.getString("creditGlAccountId"));
                return cfg;
            }
        }

        // Last-resort fallback matches the already validated manual repayment posting in this environment.
        cfg.acctgTransTypeId = PAYMENT_ACCTG_TRANS;
        cfg.glFiscalTypeId = "ACTUAL";
        cfg.debitGlAccountId = "111000";
        cfg.creditGlAccountId = "124100";
        return cfg;
    }


    private static String createIncomingPaymentAcctgTransRepairDirect(
            Delegator delegator,
            GenericValue loan,
            GenericValue payment,
            String paymentId,
            String organizationPartyId,
            BigDecimal amount,
            Timestamp repaymentDate) throws GenericEntityException {

        GenericValue existing = EntityQuery.use(delegator)
                .from("AcctgTrans")
                .where("paymentId", paymentId, "acctgTransTypeId", INCOMING_PAYMENT)
                .queryFirst();
        if (existing != null) {
            return existing.getString("acctgTransId");
        }

        validateGlAccountOrganization(delegator, "111100", organizationPartyId, "INCOMING_PAYMENT debit");
        validateGlAccountOrganization(delegator, "126000", organizationPartyId, "INCOMING_PAYMENT credit");

        String acctgTransId = delegator.getNextSeqId("AcctgTrans");
        Timestamp txDate = repaymentDate != null ? repaymentDate : UtilDateTime.nowTimestamp();
        String currencyUomId = trimToNull(payment.getString("currencyUomId"));
        if (UtilValidate.isEmpty(currencyUomId) && loan != null) {
            currencyUomId = trimToNull(loan.getString("currencyUomId"));
        }

        GenericValue trans = delegator.makeValue("AcctgTrans");
        trans.set("acctgTransId", acctgTransId);
        trans.set("acctgTransTypeId", INCOMING_PAYMENT);
        trans.set("glFiscalTypeId", "ACTUAL");
        trans.set("transactionDate", txDate);
        trans.set("postedDate", UtilDateTime.nowTimestamp());
        trans.set("isPosted", "Y");
        trans.set("paymentId", paymentId);
        trans.set("description", "Phase3 repair: incoming payment accounting for loan repayment paymentId=" + paymentId);
        delegator.create(trans);

        // Matches the validated OFBiz incoming-payment posting observed in this environment:
        // Dr 111100 GENERAL CHECKING ACCOUNT / Cr 126000 UNAPPLIED PAYMENTS.
        createSimpleAcctgEntryRepairDirect(delegator, acctgTransId, "00001", organizationPartyId,
                "111100", "D", amount, currencyUomId, loan != null ? loan.getString("partyId") : null);
        createSimpleAcctgEntryRepairDirect(delegator, acctgTransId, "00002", organizationPartyId,
                "126000", "C", amount, currencyUomId, loan != null ? loan.getString("partyId") : null);

        return acctgTransId;
    }

    private static String createPaymentApplicationAcctgTransRepairDirect(
            Delegator delegator,
            GenericValue loan,
            String paymentId,
            String organizationPartyId,
            BigDecimal fallbackAmount,
            Timestamp repaymentDate) throws GenericEntityException {

        GenericValue existing = EntityQuery.use(delegator)
                .from("AcctgTrans")
                .where("paymentId", paymentId, "acctgTransTypeId", PAYMENT_APPL)
                .queryFirst();
        if (existing != null) {
            return existing.getString("acctgTransId");
        }

        GenericValue app = EntityQuery.use(delegator)
                .from("PaymentApplication")
                .where("paymentId", paymentId)
                .orderBy("paymentApplicationId")
                .queryFirst();
        if (app == null) {
            throw new GenericEntityException("PaymentApplication is missing; cannot create PAYMENT_APPL accounting safely for paymentId=" + paymentId);
        }

        BigDecimal amount = safeAmount(app.getBigDecimal("amountApplied"));
        if (amount.compareTo(BigDecimal.ZERO) <= 0) {
            amount = fallbackAmount;
        }
        if (amount == null || amount.compareTo(BigDecimal.ZERO) <= 0) {
            throw new GenericEntityException("amount must be greater than zero for PAYMENT_APPL repair");
        }

        validateGlAccountOrganization(delegator, "126000", organizationPartyId, "PAYMENT_APPL debit");
        validateGlAccountOrganization(delegator, "120000", organizationPartyId, "PAYMENT_APPL credit");

        String acctgTransId = delegator.getNextSeqId("AcctgTrans");
        Timestamp txDate = repaymentDate != null ? repaymentDate : UtilDateTime.nowTimestamp();
        String invoiceId = trimToNull(app.getString("invoiceId"));
        String currencyUomId = loan != null ? trimToNull(loan.getString("currencyUomId")) : null;

        GenericValue trans = delegator.makeValue("AcctgTrans");
        trans.set("acctgTransId", acctgTransId);
        trans.set("acctgTransTypeId", PAYMENT_APPL);
        trans.set("glFiscalTypeId", "ACTUAL");
        trans.set("transactionDate", txDate);
        trans.set("postedDate", UtilDateTime.nowTimestamp());
        trans.set("isPosted", "Y");
        trans.set("paymentId", paymentId);
        if (UtilValidate.isNotEmpty(invoiceId)) {
            trans.set("invoiceId", invoiceId);
        }
        trans.set("description", "Phase3 repair: payment application accounting for loan repayment paymentId=" + paymentId);
        delegator.create(trans);

        // Matches the validated OFBiz payment-application posting observed in this environment:
        // Dr 126000 UNAPPLIED PAYMENTS / Cr 120000 ACCOUNTS RECEIVABLE.
        createSimpleAcctgEntryRepairDirect(delegator, acctgTransId, "00001", organizationPartyId,
                "126000", "D", amount, currencyUomId, loan != null ? loan.getString("partyId") : null);
        createSimpleAcctgEntryRepairDirect(delegator, acctgTransId, "00002", organizationPartyId,
                "120000", "C", amount, currencyUomId, loan != null ? loan.getString("partyId") : null);

        return acctgTransId;
    }

    private static void createSimpleAcctgEntryRepairDirect(
            Delegator delegator,
            String acctgTransId,
            String seqId,
            String organizationPartyId,
            String glAccountId,
            String debitCreditFlag,
            BigDecimal amount,
            String currencyUomId,
            String partyId) throws GenericEntityException {

        GenericValue entry = delegator.makeValue("AcctgTransEntry");
        entry.set("acctgTransId", acctgTransId);
        entry.set("acctgTransEntrySeqId", seqId);
        entry.set("acctgTransEntryTypeId", "_NA_");
        entry.set("organizationPartyId", organizationPartyId);
        entry.set("glAccountId", glAccountId);
        entry.set("debitCreditFlag", debitCreditFlag);
        entry.set("amount", amount);
        entry.set("origAmount", amount);
        if (UtilValidate.isNotEmpty(currencyUomId)) {
            entry.set("currencyUomId", currencyUomId);
            entry.set("origCurrencyUomId", currencyUomId);
        }
        if (UtilValidate.isNotEmpty(partyId)) {
            entry.set("partyId", partyId);
        }
        entry.set("reconcileStatusId", "AES_NOT_RECONCILED");
        delegator.create(entry);
    }

    private static AccountingState inspectAccountingState(Delegator delegator, String paymentId, String organizationPartyId, String finAccountId)
            throws GenericEntityException {
        AccountingState state = new AccountingState();

        List<GenericValue> acctgTransRows = EntityQuery.use(delegator)
                .from("AcctgTrans")
                .where("paymentId", paymentId)
                .queryList();
        state.acctgTransCount = acctgTransRows.size();
        for (GenericValue trans : acctgTransRows) {
            String type = trimToNull(trans.getString("acctgTransTypeId"));
            if (PAYMENT_ACCTG_TRANS.equals(type)) {
                state.hasPaymentAcctgTrans = true;
            } else if (INCOMING_PAYMENT.equals(type)) {
                state.hasIncomingPaymentTrans = true;
            } else if (PAYMENT_APPL.equals(type)) {
                state.hasPaymentApplicationTrans = true;
            }
        }

        List<GenericValue> apps = EntityQuery.use(delegator)
                .from("PaymentApplication")
                .where("paymentId", paymentId)
                .queryList();
        state.paymentApplicationCount = apps.size();

        if (UtilValidate.isNotEmpty(finAccountId)) {
            state.finAccountTransCount = (int) EntityQuery.use(delegator)
                    .from("FinAccountTrans")
                    .where("paymentId", paymentId, "finAccountId", finAccountId)
                    .queryCount();
        }
        return state;
    }

    private static HookResult tryRepairIncomingPaymentAccounting(LocalDispatcher dispatcher, GenericValue payment, GenericValue userLogin) {
        String paymentId = payment.getString("paymentId");
        String currentStatusId = trimToNull(payment.getString("statusId"));
        try {
            Map<String, Object> ctx = UtilMisc.toMap("paymentId", paymentId, "statusId", "PMNT_RECEIVED", "userLogin", userLogin);
            Map<String, Object> resp = dispatcher.runSync("setPaymentStatus", ctx);
            if (ServiceUtil.isError(resp)) {
                return new HookResult(false, "ERROR", "setPaymentStatus(PMNT_RECEIVED) failed: " + ServiceUtil.getErrorMessage(resp));
            }
            return new HookResult(true, "DONE", "setPaymentStatus(PMNT_RECEIVED) executed; verify INCOMING_PAYMENT accounting after rerun");
        } catch (Exception e) {
            return new HookResult(false, "ERROR", "setPaymentStatus(PMNT_RECEIVED) failed from current status " + currentStatusId + ": " + e.getMessage());
        }
    }

    private static HookResult tryRepairPaymentApplicationAccounting(LocalDispatcher dispatcher, Delegator delegator, String paymentId, GenericValue userLogin)
            throws GenericEntityException {
        GenericValue app = EntityQuery.use(delegator)
                .from("PaymentApplication")
                .where("paymentId", paymentId)
                .orderBy("paymentApplicationId")
                .queryFirst();
        if (app == null) {
            return new HookResult(false, "SKIPPED", "PaymentApplication is missing; cannot regenerate PAYMENT_APPL accounting safely from accounting repair service");
        }

        String paymentApplicationId = app.getString("paymentApplicationId");
        String invoiceId = trimToNull(app.getString("invoiceId"));
        String[] serviceNames = new String[] {
                "createPaymentApplicationAcctgTrans",
                "createAcctgTransForPaymentApplication",
                "createAcctgTransAndEntriesForPaymentApplication"
        };

        StringBuilder failures = new StringBuilder();
        for (int i = 0; i < serviceNames.length; i++) {
            String svc = serviceNames[i];
            try {
                Map<String, Object> ctx = new LinkedHashMap<String, Object>();
                ctx.put("paymentApplicationId", paymentApplicationId);
                ctx.put("paymentId", paymentId);
                if (UtilValidate.isNotEmpty(invoiceId)) {
                    ctx.put("invoiceId", invoiceId);
                }
                ctx.put("userLogin", userLogin);
                Map<String, Object> resp = dispatcher.runSync(svc, ctx);
                if (!ServiceUtil.isError(resp)) {
                    return new HookResult(true, "DONE", svc + " executed for paymentApplicationId=" + paymentApplicationId);
                }
                failures.append(svc).append(": ").append(ServiceUtil.getErrorMessage(resp)).append("; ");
            } catch (GenericServiceException e) {
                failures.append(svc).append(": ").append(e.getMessage()).append("; ");
            } catch (Exception e) {
                failures.append(svc).append(": ").append(e.getMessage()).append("; ");
            }
        }
        return new HookResult(false, "ERROR", "No payment application accounting hook succeeded. " + failures.toString());
    }


    private static List<GenericValue> findExpectedRepaymentAcctgEntries(Delegator delegator, String paymentId, String organizationPartyId)
            throws GenericEntityException {
        List<GenericValue> entries = new ArrayList<GenericValue>();
        List<GenericValue> transRows = EntityQuery.use(delegator)
                .from("AcctgTrans")
                .where("paymentId", paymentId)
                .queryList();
        for (GenericValue trans : transRows) {
            String type = trimToNull(trans.getString("acctgTransTypeId"));
            if (!(PAYMENT_ACCTG_TRANS.equals(type) || INCOMING_PAYMENT.equals(type) || PAYMENT_APPL.equals(type))) {
                continue;
            }
            List<GenericValue> rowEntries = EntityQuery.use(delegator)
                    .from("AcctgTransEntry")
                    .where("acctgTransId", trans.getString("acctgTransId"), "organizationPartyId", organizationPartyId)
                    .queryList();
            entries.addAll(rowEntries);
        }
        return entries;
    }

    private static boolean entriesAmountMismatch(List<GenericValue> entries, BigDecimal expectedAmount) {
        for (GenericValue entry : entries) {
            if (safeAmount(entry.getBigDecimal("amount")).compareTo(expectedAmount) != 0) {
                return true;
            }
        }
        return false;
    }

    private static int repairAcctgEntryAmounts(List<GenericValue> entries, BigDecimal expectedAmount) throws GenericEntityException {
        int updated = 0;
        for (GenericValue entry : entries) {
            if (safeAmount(entry.getBigDecimal("amount")).compareTo(expectedAmount) != 0) {
                entry.set("amount", expectedAmount);
                entry.set("origAmount", expectedAmount);
                entry.store();
                updated++;
            }
        }
        return updated;
    }

    private static List<GenericValue> findTargetRepayments(Delegator delegator, String loanId, String loanRepaymentId, String paymentId)
            throws GenericEntityException {
        List<EntityCondition> conditions = new ArrayList<EntityCondition>();
        if (UtilValidate.isNotEmpty(loanId)) conditions.add(EntityCondition.makeCondition("loanId", loanId));
        if (UtilValidate.isNotEmpty(loanRepaymentId)) conditions.add(EntityCondition.makeCondition("loanRepaymentId", loanRepaymentId));
        if (UtilValidate.isNotEmpty(paymentId)) conditions.add(EntityCondition.makeCondition("paymentId", paymentId));

        EntityQuery query = EntityQuery.use(delegator).from("PartyLoanRepayment");
        if (!conditions.isEmpty()) {
            query = query.where(EntityCondition.makeCondition(conditions, EntityOperator.AND));
        }
        return query.orderBy("loanId", "repaymentDate", "loanRepaymentId").queryList();
    }

    private static GenericValue findLoan(Delegator delegator, String loanId) throws GenericEntityException {
        if (UtilValidate.isEmpty(loanId)) return null;
        return EntityQuery.use(delegator).from("PartyLoan").where("loanId", loanId).queryOne();
    }

    private static BigDecimal safeAmount(BigDecimal value) {
        return value != null ? value : BigDecimal.ZERO;
    }

    private static String trimToNull(String value) {
        if (value == null) return null;
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    private static String normalizeFlag(String value) {
        return "Y".equalsIgnoreCase(value) ? "Y" : "N";
    }

    private static String normalizeFlagDefaultY(String value) {
        return value == null || "Y".equalsIgnoreCase(value) ? "Y" : "N";
    }

    private static void validateGlAccountOrganization(Delegator delegator,
                                                          String glAccountId,
                                                          String organizationPartyId,
                                                          String postingContext) throws GenericEntityException {
        if (UtilValidate.isEmpty(glAccountId)) {
            throw new GenericEntityException("CONFIGURATION_ERROR: Missing GL account for " + postingContext);
        }
        if (UtilValidate.isEmpty(organizationPartyId)) {
            throw new GenericEntityException("CONFIGURATION_ERROR: Missing organizationPartyId for " + postingContext);
        }
        GenericValue mapping = EntityQuery.use(delegator)
                .from("GlAccountOrganization")
                .where("glAccountId", glAccountId, "organizationPartyId", organizationPartyId)
                .queryOne();
        if (mapping == null) {
            throw new GenericEntityException("CONFIGURATION_ERROR: GL account " + glAccountId
                    + " is not configured for organizationPartyId=" + organizationPartyId
                    + " while posting " + postingContext
                    + ". Add GlAccountOrganization mapping or update LoanAcctgCfgDtl before execution.");
        }
    }

    private static final class AccountingState {
        int acctgTransCount;
        int paymentApplicationCount;
        int finAccountTransCount;
        boolean hasPaymentAcctgTrans;
        boolean hasIncomingPaymentTrans;
        boolean hasPaymentApplicationTrans;
    }

    private static final class LoanAcctgPostingCfg {
        String acctgTransTypeId;
        String glFiscalTypeId;
        String debitGlAccountId;
        String creditGlAccountId;
    }

    private static final class HookResult {
        final boolean success;
        final String status;
        final String message;
        HookResult(boolean success, String status, String message) {
            this.success = success;
            this.status = status;
            this.message = message;
        }
    }
}
