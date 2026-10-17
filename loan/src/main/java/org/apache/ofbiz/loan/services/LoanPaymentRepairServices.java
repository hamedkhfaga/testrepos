package org.apache.ofbiz.loan.services;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.apache.ofbiz.base.util.Debug;
import org.apache.ofbiz.base.util.UtilDateTime;
import org.apache.ofbiz.base.util.UtilValidate;
import org.apache.ofbiz.entity.Delegator;
import org.apache.ofbiz.entity.GenericEntityException;
import org.apache.ofbiz.entity.GenericValue;
import org.apache.ofbiz.entity.condition.EntityCondition;
import org.apache.ofbiz.entity.condition.EntityOperator;
import org.apache.ofbiz.entity.util.EntityQuery;
import org.apache.ofbiz.service.DispatchContext;
import org.apache.ofbiz.service.LocalDispatcher;
import org.apache.ofbiz.service.ServiceUtil;

public final class LoanPaymentRepairServices {
    public static final String MODULE = LoanPaymentRepairServices.class.getName();

    private LoanPaymentRepairServices() {}

    public static Map<String, Object> backfillLoanRepaymentPaymentV1(DispatchContext dctx, Map<String, ?> context) {
        Delegator delegator = dctx.getDelegator();
        LocalDispatcher dispatcher = dctx.getDispatcher();
        GenericValue userLogin = (GenericValue) context.get("userLogin");

        String loanId = trimToNull((String) context.get("loanId"));
        String loanRepaymentId = trimToNull((String) context.get("loanRepaymentId"));
        String organizationPartyId = trimToNull((String) context.get("organizationPartyId"));
        String dryRun = normalizeFlagDefaultY((String) context.get("dryRun"));
        String autoCreateAccounting = normalizeFlag((String) context.get("autoCreateAccounting"));
        String autoCreateFinAccountTrans = normalizeFlag((String) context.get("autoCreateFinAccountTrans"));
        String paymentTypeId = trimToNull((String) context.get("paymentTypeId"));
        String paymentMethodTypeId = trimToNull((String) context.get("paymentMethodTypeId"));
        String paymentMethodId = trimToNull((String) context.get("paymentMethodId"));
        String linkDuplicatePaymentIfSafe = normalizeFlag((String) context.get("linkDuplicatePaymentIfSafe"));

        try {
            if (UtilValidate.isEmpty(loanId) && UtilValidate.isEmpty(loanRepaymentId) && UtilValidate.isEmpty(organizationPartyId)) {
                return ServiceUtil.returnError("At least one of loanId, loanRepaymentId, organizationPartyId is required");
            }

            List<GenericValue> repayments = findTargetRepayments(delegator, loanId, loanRepaymentId);
            if (UtilValidate.isEmpty(repayments)) {
                return ServiceUtil.returnError("No PartyLoanRepayment rows matched the requested filters");
            }

            int checkedRepaymentCount = 0;
            int candidateCount = 0;
            int skippedCount = 0;
            int createdPaymentCount = 0;
            int existingPaymentCount = 0;
            int createdAcctgCount = 0;
            int createdFinTransCount = 0;
            int errorCount = 0;

            List<Map<String, Object>> actions = new ArrayList<Map<String, Object>>();

            for (GenericValue repayment : repayments) {
                checkedRepaymentCount++;

                String currentLoanRepaymentId = repayment.getString("loanRepaymentId");
                String currentLoanId = repayment.getString("loanId");
                String existingPaymentId = trimToNull(repayment.getString("paymentId"));
                String repaymentSourceEnumId = trimToNull(repayment.getString("repaymentSourceEnumId"));
                String remarks = trimToNull(repayment.getString("remarks"));
                Timestamp repaymentDate = repayment.getTimestamp("repaymentDate");
                if (repaymentDate == null) {
                    repaymentDate = UtilDateTime.nowTimestamp();
                }
                BigDecimal amountApplied = safeAmount(repayment.getBigDecimal("amountApplied"));

                GenericValue loan = findLoan(delegator, currentLoanId);
                if (loan == null) {
                    errorCount++;
                    actions.add(buildAction(currentLoanRepaymentId, currentLoanId, existingPaymentId, "ERROR", "PartyLoan not found", null, null, null));
                    continue;
                }

                String resolvedOrgId = UtilValidate.isNotEmpty(organizationPartyId) ? organizationPartyId : trimToNull(loan.getString("organizationPartyId"));
                String finAccountId = trimToNull(loan.getString("finAccountId"));

                if (UtilValidate.isNotEmpty(existingPaymentId)) {
                    existingPaymentCount++;
                    skippedCount++;

                    Map<String, Object> action = buildAction(
                            currentLoanRepaymentId, currentLoanId, existingPaymentId,
                            "SKIPPED", "Repayment already has paymentId", null, null, null);
                    action.put("organizationPartyId", resolvedOrgId);
                    action.put("amountApplied", amountApplied);
                    action.put("repaymentSourceEnumId", repaymentSourceEnumId);
                    action.put("alreadyLinkedPaymentId", existingPaymentId);
                    actions.add(action);
                    continue;
                }

                candidateCount++;

                if (UtilValidate.isEmpty(resolvedOrgId)) {
                    errorCount++;
                    actions.add(buildAction(currentLoanRepaymentId, currentLoanId, null, "ERROR", "organizationPartyId could not be resolved", null, null, null));
                    continue;
                }

                if (amountApplied.compareTo(BigDecimal.ZERO) <= 0) {
                    errorCount++;
                    actions.add(buildAction(currentLoanRepaymentId, currentLoanId, null, "ERROR", "amountApplied must be greater than zero", null, null, null));
                    continue;
                }

                String resolvedPaymentMethodId = paymentMethodId;
                String resolvedPaymentMethodTypeId = paymentMethodTypeId;
                String paymentMethodResolutionStrategy = "REQUEST";

                if (UtilValidate.isEmpty(resolvedPaymentMethodId)) {
                    resolvedPaymentMethodId = resolveRepairPaymentMethodId(
                            delegator,
                            loan,
                            repaymentSourceEnumId,
                            resolvedOrgId
                    );
                    if (UtilValidate.isNotEmpty(resolvedPaymentMethodId)) {
                        paymentMethodResolutionStrategy = "AUTO_PAYMENT_METHOD";
                    }
                }

                if (UtilValidate.isEmpty(resolvedPaymentMethodTypeId)) {
                    resolvedPaymentMethodTypeId = resolveRepairPaymentMethodTypeId(repaymentSourceEnumId);
                    if (!"AUTO_PAYMENT_METHOD".equals(paymentMethodResolutionStrategy)) {
                        paymentMethodResolutionStrategy = "AUTO_PAYMENT_METHOD_TYPE";
                    }
                }

                if (!"Y".equals(dryRun)
                        && UtilValidate.isEmpty(resolvedPaymentMethodId)
                        && UtilValidate.isEmpty(resolvedPaymentMethodTypeId)) {
                    errorCount++;
                    Map<String, Object> action = buildAction(currentLoanRepaymentId, currentLoanId, null, "CONFIGURATION_ERROR",
                            "No eligible paymentMethodId or paymentMethodTypeId could be resolved for repayment repair",
                            null, null, null);
                    action.put("organizationPartyId", resolvedOrgId);
                    action.put("repaymentSourceEnumId", repaymentSourceEnumId);
                    action.put("recommendedAction", "Provide paymentMethodId/paymentMethodTypeId or configure default payment method for repair execution");
                    actions.add(action);
                    continue;
                }

                DuplicatePaymentCheck duplicate = findDuplicatePaymentCandidate(delegator, loan, repayment, amountApplied, repaymentDate);
                if (duplicate.found) {
                    if ("Y".equals(linkDuplicatePaymentIfSafe)) {
                        Map<String, Object> linkCtx = new LinkedHashMap<String, Object>();
                        linkCtx.put("loanId", currentLoanId);
                        linkCtx.put("loanRepaymentId", currentLoanRepaymentId);
                        linkCtx.put("paymentId", duplicate.paymentId);
                        linkCtx.put("organizationPartyId", resolvedOrgId);
                        linkCtx.put("dryRun", dryRun);
                        linkCtx.put("requirePaymentApplication", "Y");
                        linkCtx.put("requireAccounting", "Y");
                        linkCtx.put("userLogin", userLogin);
                        Map<String, Object> linkResp = linkOrphanLoanRepaymentPaymentV1(dctx, linkCtx);
                        Map<String, Object> action = buildAction(currentLoanRepaymentId, currentLoanId, duplicate.paymentId,
                                ServiceUtil.isError(linkResp) ? "ERROR" : ("Y".equals(dryRun) ? "DRY_RUN_LINK" : "LINKED"),
                                ServiceUtil.isError(linkResp) ? ServiceUtil.getErrorMessage(linkResp) : "Existing duplicate/orphan payment safely linked to repayment",
                                null, null, null);
                        action.put("duplicatePaymentId", duplicate.paymentId);
                        action.put("linkResult", linkResp);
                        actions.add(action);
                        if (ServiceUtil.isError(linkResp)) {
                            errorCount++;
                        } else if (!"Y".equals(dryRun)) {
                            existingPaymentCount++;
                            skippedCount++;
                        }
                        continue;
                    }
                    errorCount++;
                    Map<String, Object> action = buildAction(currentLoanRepaymentId, currentLoanId, null, "ERROR",
                            "Possible duplicate payment candidate already exists; refusing automatic backfill",
                            duplicate.paymentId, null, null);
                    action.put("duplicatePaymentId", duplicate.paymentId);
                    action.put("recommendedAction", "Run linkOrphanLoanRepaymentPaymentV1 or re-run with linkDuplicatePaymentIfSafe=Y after approval");
                    actions.add(action);
                    continue;
                }

                if ("Y".equals(dryRun)) {
                    Map<String, Object> action = buildAction(currentLoanRepaymentId, currentLoanId, null, "DRY_RUN",
                            "Would create missing payment for repayment", null, null, null);
                    action.put("organizationPartyId", resolvedOrgId);
                    action.put("amountApplied", amountApplied);
                    action.put("repaymentSourceEnumId", repaymentSourceEnumId);
                    action.put("wouldCreateAccounting", Boolean.valueOf("Y".equals(autoCreateAccounting)));
                    action.put("wouldCreateFinAccountTrans", Boolean.valueOf("Y".equals(autoCreateFinAccountTrans) && UtilValidate.isNotEmpty(finAccountId)));
                    actions.add(action);
                    continue;
                }

                try {
                    Map<String, Object> paymentCtx = new LinkedHashMap<String, Object>();
                    paymentCtx.put("paymentTypeId", UtilValidate.isNotEmpty(paymentTypeId) ? paymentTypeId : "CUSTOMER_PAYMENT");
                    paymentCtx.put("partyIdFrom", loan.getString("partyId"));
                    paymentCtx.put("partyIdTo", resolvedOrgId);
                    paymentCtx.put("amount", amountApplied);
                    paymentCtx.put("currencyUomId", loan.getString("currencyUomId"));
                    paymentCtx.put("effectiveDate", repaymentDate);
                    paymentCtx.put("paymentMethodTypeId", resolvedPaymentMethodTypeId);
                    paymentCtx.put("paymentMethodId", resolvedPaymentMethodId);
                    paymentCtx.put("comments", buildPaymentComment(loan, repayment, remarks));
                    paymentCtx.put("userLogin", userLogin);

                    Map<String, Object> paymentResp = dispatcher.runSync("createPayment", paymentCtx);
                    if (ServiceUtil.isError(paymentResp)) {
                        throw new GenericEntityException(ServiceUtil.getErrorMessage(paymentResp));
                    }

                    String createdPaymentId = trimToNull((String) paymentResp.get("paymentId"));
                    if (UtilValidate.isEmpty(createdPaymentId)) {
                        throw new GenericEntityException("createPayment succeeded but returned no paymentId");
                    }

                    repayment.set("paymentId", createdPaymentId);
                    repayment.store();

                    createdPaymentCount++;

                    String createdAcctgTransId = null;
                    String createdFinAccountTransId = null;

                    if ("Y".equals(autoCreateAccounting)) {
                        Map<String, Object> acctgCtx = new LinkedHashMap<String, Object>();
                        acctgCtx.put("paymentId", createdPaymentId);
                        acctgCtx.put("organizationPartyId", resolvedOrgId);
                        acctgCtx.put("amount", amountApplied);
                        acctgCtx.put("repaymentDate", repaymentDate);
                        acctgCtx.put("repaymentSourceEnumId", repaymentSourceEnumId);
                        acctgCtx.put("loanId", currentLoanId);
                        acctgCtx.put("remarks", remarks);
                        acctgCtx.put("userLogin", userLogin);

                        Map<String, Object> acctgResp = dispatcher.runSync("createAcctgTransForLoanRepayment", acctgCtx);
                        if (ServiceUtil.isError(acctgResp)) {
                            throw new GenericEntityException(ServiceUtil.getErrorMessage(acctgResp));
                        }
                        createdAcctgTransId = (String) acctgResp.get("acctgTransId");
                        createdAcctgCount++;
                    }

                    if ("Y".equals(autoCreateFinAccountTrans) && UtilValidate.isNotEmpty(finAccountId)) {
                        Map<String, Object> finCtx = new LinkedHashMap<String, Object>();
                        finCtx.put("finAccountId", finAccountId);
                        finCtx.put("paymentId", createdPaymentId);
                        finCtx.put("organizationPartyId", resolvedOrgId);
                        finCtx.put("amount", amountApplied);
                        finCtx.put("repaymentDate", repaymentDate);
                        finCtx.put("repaymentSourceEnumId", repaymentSourceEnumId);
                        finCtx.put("loanId", currentLoanId);
                        finCtx.put("remarks", remarks);
                        finCtx.put("userLogin", userLogin);

                        Map<String, Object> finResp = dispatcher.runSync("createFinAccountTransForLoanRepayment", finCtx);
                        if (ServiceUtil.isError(finResp)) {
                            throw new GenericEntityException(ServiceUtil.getErrorMessage(finResp));
                        }
                        createdFinAccountTransId = (String) finResp.get("finAccountTransId");
                        createdFinTransCount++;
                    }

                    Map<String, Object> action = buildAction(currentLoanRepaymentId, currentLoanId, createdPaymentId, "DONE",
                            "Missing payment backfilled successfully", createdPaymentId, createdAcctgTransId, createdFinAccountTransId);
                    action.put("organizationPartyId", resolvedOrgId);
                    action.put("amountApplied", amountApplied);
                    action.put("repaymentSourceEnumId", repaymentSourceEnumId);
                    action.put("autoResolvedPaymentMethodId", resolvedPaymentMethodId);
                    action.put("autoResolvedPaymentMethodTypeId", resolvedPaymentMethodTypeId);
                    action.put("paymentMethodResolutionStrategy", paymentMethodResolutionStrategy);
                    actions.add(action);

                } catch (Exception e) {
                    errorCount++;
                    Debug.logError(e, "Error backfilling payment for loanRepaymentId=" + currentLoanRepaymentId, MODULE);
                    actions.add(buildAction(currentLoanRepaymentId, currentLoanId, null, "ERROR", e.getMessage(), null, null, null));
                }
            }

            Map<String, Object> payload = new LinkedHashMap<String, Object>();
            payload.put("loanId", loanId);
            payload.put("loanRepaymentId", loanRepaymentId);
            payload.put("organizationPartyId", organizationPartyId);
            payload.put("dryRun", dryRun);
            payload.put("autoCreateAccounting", autoCreateAccounting);
            payload.put("autoCreateFinAccountTrans", autoCreateFinAccountTrans);
            payload.put("checkedRepaymentCount", Integer.valueOf(checkedRepaymentCount));
            payload.put("candidateCount", Integer.valueOf(candidateCount));
            payload.put("skippedCount", Integer.valueOf(skippedCount));
            payload.put("existingPaymentCount", Integer.valueOf(existingPaymentCount));
            payload.put("createdPaymentCount", Integer.valueOf(createdPaymentCount));
            payload.put("createdAcctgCount", Integer.valueOf(createdAcctgCount));
            payload.put("createdFinTransCount", Integer.valueOf(createdFinTransCount));
            payload.put("errorCount", Integer.valueOf(errorCount));
            payload.put("actions", actions);

            Map<String, Object> result = ServiceUtil.returnSuccess("Loan repayment payment backfill completed successfully");
            result.putAll(payload);
            result.put("data", new LinkedHashMap<String, Object>(payload));
            result.put("legacyData", new LinkedHashMap<String, Object>(payload));
            return result;
        } catch (Exception e) {
            Debug.logError(e, "Error running backfillLoanRepaymentPaymentV1", MODULE);
            return ServiceUtil.returnError(e.getMessage());
        }
    }


    /**
     * Phase 6J - safely link an existing orphan/duplicate payment to a PartyLoanRepayment.
     * This does not create Payment, PaymentApplication, or AcctgTrans records. It only sets
     * PartyLoanRepayment.paymentId after validating amount, parties, payment application, and
     * the accepted 3-transaction accounting model.
     */
    public static Map<String, Object> linkOrphanLoanRepaymentPaymentV1(DispatchContext dctx, Map<String, ?> context) {
        Delegator delegator = dctx.getDelegator();

        String loanId = trimToNull((String) context.get("loanId"));
        String loanRepaymentId = trimToNull((String) context.get("loanRepaymentId"));
        String paymentId = trimToNull((String) context.get("paymentId"));
        String organizationPartyId = trimToNull((String) context.get("organizationPartyId"));
        String dryRun = normalizeFlagDefaultY((String) context.get("dryRun"));
        String requirePaymentApplication = normalizeFlagDefaultY((String) context.get("requirePaymentApplication"));
        String requireAccounting = normalizeFlagDefaultY((String) context.get("requireAccounting"));

        try {
            if (UtilValidate.isEmpty(loanRepaymentId)) return ServiceUtil.returnError("loanRepaymentId is required");
            if (UtilValidate.isEmpty(paymentId)) return ServiceUtil.returnError("paymentId is required");

            GenericValue repayment = EntityQuery.use(delegator)
                    .from("PartyLoanRepayment")
                    .where("loanRepaymentId", loanRepaymentId)
                    .queryOne();
            if (repayment == null) return ServiceUtil.returnError("PartyLoanRepayment not found: " + loanRepaymentId);
            if (UtilValidate.isNotEmpty(loanId) && !loanId.equals(repayment.getString("loanId"))) {
                return ServiceUtil.returnError("loanId does not match PartyLoanRepayment.loanId");
            }
            String existingPaymentId = trimToNull(repayment.getString("paymentId"));
            if (UtilValidate.isNotEmpty(existingPaymentId)) {
                if (existingPaymentId.equals(paymentId)) {
                    Map<String, Object> payload = new LinkedHashMap<String, Object>();
                    payload.put("loanId", repayment.getString("loanId"));
                    payload.put("loanRepaymentId", loanRepaymentId);
                    payload.put("paymentId", paymentId);
                    payload.put("status", "SKIPPED");
                    payload.put("reason", "Repayment already linked to requested paymentId");
                    return buildStandardSuccess("Loan repayment payment link already exists", payload);
                }
                return ServiceUtil.returnError("Repayment already linked to different paymentId: " + existingPaymentId);
            }

            GenericValue loan = findLoan(delegator, repayment.getString("loanId"));
            if (loan == null) return ServiceUtil.returnError("PartyLoan not found: " + repayment.getString("loanId"));
            String resolvedOrgId = UtilValidate.isNotEmpty(organizationPartyId) ? organizationPartyId : trimToNull(loan.getString("organizationPartyId"));

            GenericValue payment = EntityQuery.use(delegator).from("Payment").where("paymentId", paymentId).queryOne();
            if (payment == null) return ServiceUtil.returnError("Payment not found: " + paymentId);

            BigDecimal expectedAmount = safeAmount(repayment.getBigDecimal("amountApplied"));
            BigDecimal paymentAmount = safeAmount(payment.getBigDecimal("amount"));
            if (expectedAmount.compareTo(paymentAmount) != 0) {
                return ServiceUtil.returnError("Payment amount does not match repayment amountApplied");
            }
            if (!safeEquals(loan.getString("partyId"), payment.getString("partyIdFrom"))) {
                return ServiceUtil.returnError("Payment partyIdFrom does not match loan borrower partyId");
            }
            if (!safeEquals(resolvedOrgId, payment.getString("partyIdTo"))) {
                return ServiceUtil.returnError("Payment partyIdTo does not match organizationPartyId");
            }
            if (!safeEquals(loan.getString("currencyUomId"), payment.getString("currencyUomId"))) {
                return ServiceUtil.returnError("Payment currencyUomId does not match loan currencyUomId");
            }

            long paymentApplicationCount = EntityQuery.use(delegator)
                    .from("PaymentApplication")
                    .where("paymentId", paymentId)
                    .queryCount();
            if ("Y".equals(requirePaymentApplication) && paymentApplicationCount <= 0L) {
                return ServiceUtil.returnError("PaymentApplication is required before linking orphan payment");
            }

            List<GenericValue> acctgTrans = EntityQuery.use(delegator)
                    .from("AcctgTrans")
                    .where("paymentId", paymentId)
                    .queryList();
            boolean hasPaymentAcctgTrans = false;
            boolean hasIncomingPaymentTrans = false;
            boolean hasPaymentApplicationTrans = false;
            for (GenericValue row : acctgTrans) {
                String typeId = row.getString("acctgTransTypeId");
                if ("PAYMENT_ACCTG_TRANS".equals(typeId)) hasPaymentAcctgTrans = true;
                if ("INCOMING_PAYMENT".equals(typeId)) hasIncomingPaymentTrans = true;
                if ("PAYMENT_APPL".equals(typeId)) hasPaymentApplicationTrans = true;
            }
            if ("Y".equals(requireAccounting) && !(hasPaymentAcctgTrans && hasIncomingPaymentTrans && hasPaymentApplicationTrans)) {
                return ServiceUtil.returnError("Payment does not have the complete accepted 3-transaction accounting model");
            }

            if (!"Y".equals(dryRun)) {
                repayment.set("paymentId", paymentId);
                repayment.store();
            }

            Map<String, Object> payload = new LinkedHashMap<String, Object>();
            payload.put("loanId", repayment.getString("loanId"));
            payload.put("loanRepaymentId", loanRepaymentId);
            payload.put("loanScheduleId", repayment.getString("loanScheduleId"));
            payload.put("organizationPartyId", resolvedOrgId);
            payload.put("paymentId", paymentId);
            payload.put("dryRun", dryRun);
            payload.put("expectedAmount", expectedAmount);
            payload.put("paymentAmount", paymentAmount);
            payload.put("paymentApplicationCount", Long.valueOf(paymentApplicationCount));
            payload.put("acctgTransCount", Integer.valueOf(acctgTrans.size()));
            payload.put("hasPaymentAcctgTrans", Boolean.valueOf(hasPaymentAcctgTrans));
            payload.put("hasIncomingPaymentTrans", Boolean.valueOf(hasIncomingPaymentTrans));
            payload.put("hasPaymentApplicationTrans", Boolean.valueOf(hasPaymentApplicationTrans));
            payload.put("status", "Y".equals(dryRun) ? "DRY_RUN" : "DONE");
            payload.put("reason", "Existing orphan/duplicate payment validated and linked to repayment");
            return buildStandardSuccess("Loan repayment orphan payment link repair completed successfully", payload);
        } catch (Exception e) {
            Debug.logError(e, "Error running linkOrphanLoanRepaymentPaymentV1", MODULE);
            return ServiceUtil.returnError(e.getMessage());
        }
    }

    private static List<GenericValue> findTargetRepayments(Delegator delegator, String loanId, String loanRepaymentId)
            throws GenericEntityException {
        List<EntityCondition> conditions = new ArrayList<EntityCondition>();
        if (UtilValidate.isNotEmpty(loanId)) {
            conditions.add(EntityCondition.makeCondition("loanId", loanId));
        }
        if (UtilValidate.isNotEmpty(loanRepaymentId)) {
            conditions.add(EntityCondition.makeCondition("loanRepaymentId", loanRepaymentId));
        }

        return EntityQuery.use(delegator)
                .from("PartyLoanRepayment")
                .where(EntityCondition.makeCondition(conditions, EntityOperator.AND))
                .orderBy("loanId", "repaymentDate", "loanRepaymentId")
                .queryList();
    }

    private static GenericValue findLoan(Delegator delegator, String loanId) throws GenericEntityException {
        if (UtilValidate.isEmpty(loanId)) {
            return null;
        }
        return EntityQuery.use(delegator).from("PartyLoan").where("loanId", loanId).queryOne();
    }

    private static String resolveRepairPaymentMethodId(
            Delegator delegator,
            GenericValue loan,
            String repaymentSourceEnumId,
            String organizationPartyId) throws GenericEntityException {

        if (loan == null) {
            return null;
        }

        String borrowerPartyId = trimToNull(loan.getString("partyId"));
        if (UtilValidate.isNotEmpty(borrowerPartyId)) {
            GenericValue borrowerMethod = EntityQuery.use(delegator)
                    .from("PaymentMethod")
                    .where("partyId", borrowerPartyId)
                    .orderBy("-fromDate", "paymentMethodId")
                    .queryFirst();
            if (borrowerMethod != null) {
                return trimToNull(borrowerMethod.getString("paymentMethodId"));
            }
        }

        if (UtilValidate.isNotEmpty(organizationPartyId)) {
            GenericValue orgMethod = EntityQuery.use(delegator)
                    .from("PaymentMethod")
                    .where("partyId", organizationPartyId)
                    .orderBy("-fromDate", "paymentMethodId")
                    .queryFirst();
            if (orgMethod != null) {
                return trimToNull(orgMethod.getString("paymentMethodId"));
            }
        }

        return null;
    }

    private static String resolveRepairPaymentMethodTypeId(String repaymentSourceEnumId) {
        if ("LNRS_PAYROLL".equals(repaymentSourceEnumId)) {
            return "CASH";
        }
        return "CASH";
    }

    private static DuplicatePaymentCheck findDuplicatePaymentCandidate(
            Delegator delegator,
            GenericValue loan,
            GenericValue repayment,
            BigDecimal amountApplied,
            Timestamp repaymentDate) throws GenericEntityException {

        DuplicatePaymentCheck out = new DuplicatePaymentCheck();
        out.found = false;

        if (loan == null) {
            return out;
        }

        String partyIdFrom = trimToNull(loan.getString("partyId"));
        String partyIdTo = trimToNull(loan.getString("organizationPartyId"));
        String currencyUomId = trimToNull(loan.getString("currencyUomId"));

        if (UtilValidate.isEmpty(partyIdFrom) || UtilValidate.isEmpty(partyIdTo) || UtilValidate.isEmpty(currencyUomId)) {
            return out;
        }

        List<GenericValue> candidates = EntityQuery.use(delegator)
                .from("Payment")
                .where("partyIdFrom", partyIdFrom,
                       "partyIdTo", partyIdTo,
                       "amount", amountApplied,
                       "currencyUomId", currencyUomId)
                .orderBy("-effectiveDate", "-paymentId")
                .queryList();

        String marker = "loanRepaymentId=" + repayment.getString("loanRepaymentId");
        for (GenericValue payment : candidates) {
            String comments = trimToNull(payment.getString("comments"));
            if (UtilValidate.isNotEmpty(comments) && comments.contains(marker)) {
                out.found = true;
                out.paymentId = payment.getString("paymentId");
                return out;
            }

            Timestamp effectiveDate = payment.getTimestamp("effectiveDate");
            if (effectiveDate != null && repaymentDate != null && effectiveDate.equals(repaymentDate)) {
                out.found = true;
                out.paymentId = payment.getString("paymentId");
                return out;
            }
        }

        return out;
    }

    private static Map<String, Object> buildAction(
            String loanRepaymentId,
            String loanId,
            String paymentId,
            String status,
            String reason,
            String createdPaymentId,
            String createdAcctgTransId,
            String createdFinAccountTransId) {
        Map<String, Object> action = new LinkedHashMap<String, Object>();
        action.put("loanRepaymentId", loanRepaymentId);
        action.put("loanId", loanId);
        action.put("paymentId", paymentId);
        action.put("status", status);
        action.put("reason", reason);
        action.put("createdPaymentId", createdPaymentId);
        action.put("createdAcctgTransId", createdAcctgTransId);
        action.put("createdFinAccountTransId", createdFinAccountTransId);
        return action;
    }

    private static String buildPaymentComment(GenericValue loan, GenericValue repayment, String remarks) {
        StringBuilder sb = new StringBuilder();
        sb.append("Loan repayment backfill");
        if (loan != null && UtilValidate.isNotEmpty(loan.getString("loanId"))) {
            sb.append(" loanId=").append(loan.getString("loanId"));
        }
        if (repayment != null && UtilValidate.isNotEmpty(repayment.getString("loanRepaymentId"))) {
            sb.append(" loanRepaymentId=").append(repayment.getString("loanRepaymentId"));
        }
        if (repayment != null && UtilValidate.isNotEmpty(repayment.getString("repaymentSourceEnumId"))) {
            sb.append(" source=").append(repayment.getString("repaymentSourceEnumId"));
        }
        if (UtilValidate.isNotEmpty(remarks)) {
            sb.append(" - ").append(remarks);
        }
        return sb.toString();
    }

    private static Map<String, Object> buildStandardSuccess(String message, Map<String, Object> payload) {
        Map<String, Object> result = ServiceUtil.returnSuccess(message);
        result.putAll(payload);
        result.put("data", new LinkedHashMap<String, Object>(payload));
        result.put("legacyData", new LinkedHashMap<String, Object>(payload));
        return result;
    }

    private static boolean safeEquals(String left, String right) {
        String l = trimToNull(left);
        String r = trimToNull(right);
        return l == null ? r == null : l.equals(r);
    }

    private static BigDecimal safeAmount(BigDecimal value) {
        return value != null ? value : BigDecimal.ZERO;
    }

    private static String trimToNull(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    private static String normalizeFlag(String value) {
        return "Y".equalsIgnoreCase(value) ? "Y" : "N";
    }

    private static String normalizeFlagDefaultY(String value) {
        return value == null || "Y".equalsIgnoreCase(value) ? "Y" : "N";
    }

    private static final class DuplicatePaymentCheck {
        boolean found;
        String paymentId;
    }
}
