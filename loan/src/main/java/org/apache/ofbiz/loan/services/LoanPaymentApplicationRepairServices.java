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

public final class LoanPaymentApplicationRepairServices {
    public static final String MODULE = LoanPaymentApplicationRepairServices.class.getName();

    private static final String LOAN_REPAYMENT_INVOICE_TYPE_ID = "LOAN_REPAYMENT";
    private static final String LOAN_PRINCIPAL_ITEM_TYPE_ID = "LOAN_PRINCIPAL";

    private LoanPaymentApplicationRepairServices() {}

    public static Map<String, Object> backfillLoanRepaymentPaymentApplicationV1(DispatchContext dctx, Map<String, ?> context) {
        Delegator delegator = dctx.getDelegator();
        LocalDispatcher dispatcher = dctx.getDispatcher();
        GenericValue userLogin = (GenericValue) context.get("userLogin");

        String loanId = trimToNull((String) context.get("loanId"));
        String loanRepaymentId = trimToNull((String) context.get("loanRepaymentId"));
        String paymentIdFilter = trimToNull((String) context.get("paymentId"));
        String organizationPartyId = trimToNull((String) context.get("organizationPartyId"));
        String dryRun = normalizeFlagDefaultY((String) context.get("dryRun"));
        String createInvoiceIfMissing = normalizeFlagDefaultY((String) context.get("createInvoiceIfMissing"));

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
            int existingPaymentApplicationCount = 0;
            int createdInvoiceCount = 0;
            int existingInvoiceCount = 0;
            int createdPaymentApplicationCount = 0;
            int errorCount = 0;

            List<Map<String, Object>> actions = new ArrayList<Map<String, Object>>();

            for (GenericValue repayment : repayments) {
                checkedRepaymentCount++;

                String currentLoanRepaymentId = repayment.getString("loanRepaymentId");
                String currentLoanId = repayment.getString("loanId");
                String loanScheduleId = trimToNull(repayment.getString("loanScheduleId"));
                String paymentId = trimToNull(repayment.getString("paymentId"));
                String repaymentSourceEnumId = trimToNull(repayment.getString("repaymentSourceEnumId"));
                Timestamp repaymentDate = repayment.getTimestamp("repaymentDate");
                if (repaymentDate == null) {
                    repaymentDate = UtilDateTime.nowTimestamp();
                }
                BigDecimal amountApplied = safeAmount(repayment.getBigDecimal("amountApplied"));

                Map<String, Object> action = new LinkedHashMap<String, Object>();
                action.put("loanRepaymentId", currentLoanRepaymentId);
                action.put("loanId", currentLoanId);
                action.put("loanScheduleId", loanScheduleId);
                action.put("paymentId", paymentId);
                action.put("amountApplied", amountApplied);
                action.put("repaymentSourceEnumId", repaymentSourceEnumId);

                GenericValue loan = findLoan(delegator, currentLoanId);
                if (loan == null) {
                    errorCount++;
                    action.put("status", "ERROR");
                    action.put("reason", "PartyLoan not found");
                    actions.add(action);
                    continue;
                }

                String resolvedOrgId = UtilValidate.isNotEmpty(organizationPartyId)
                        ? organizationPartyId : trimToNull(loan.getString("organizationPartyId"));
                action.put("organizationPartyId", resolvedOrgId);

                if (UtilValidate.isEmpty(paymentId)) {
                    skippedCount++;
                    action.put("status", "SKIPPED");
                    action.put("reason", "Repayment has no paymentId; run backfillLoanRepaymentPaymentV1 first");
                    action.put("requiredRepairService", "backfillLoanRepaymentPaymentV1");
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
                if (!resolvedOrgId.equals(loan.getString("organizationPartyId"))) {
                    errorCount++;
                    action.put("status", "ERROR");
                    action.put("reason", "organizationPartyId mismatch for loanId: " + currentLoanId);
                    actions.add(action);
                    continue;
                }
                if (amountApplied.compareTo(BigDecimal.ZERO) <= 0) {
                    errorCount++;
                    action.put("status", "ERROR");
                    action.put("reason", "amountApplied must be greater than zero");
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

                GenericValue existingApp = EntityQuery.use(delegator)
                        .from("PaymentApplication")
                        .where("paymentId", paymentId)
                        .queryFirst();
                if (existingApp != null) {
                    existingPaymentApplicationCount++;
                    skippedCount++;
                    action.put("status", "SKIPPED");
                    action.put("reason", "PaymentApplication already exists for paymentId");
                    action.put("paymentApplicationId", existingApp.getString("paymentApplicationId"));
                    action.put("invoiceId", existingApp.getString("invoiceId"));
                    actions.add(action);
                    continue;
                }

                String invoiceId = findExistingRepairInvoiceId(delegator, currentLoanId, loanScheduleId, currentLoanRepaymentId, paymentId, resolvedOrgId);
                boolean invoiceExists = UtilValidate.isNotEmpty(invoiceId);
                if (invoiceExists) {
                    existingInvoiceCount++;
                }

                if (!invoiceExists && !"Y".equals(createInvoiceIfMissing)) {
                    skippedCount++;
                    action.put("status", "BLOCKED_DEPENDENCY");
                    action.put("reason", "Missing repayment invoice and createInvoiceIfMissing is not enabled");
                    action.put("requiredRepair", "create repayment invoice");
                    actions.add(action);
                    continue;
                }

                candidateCount++;
                action.put("existingInvoiceId", invoiceId);
                action.put("wouldCreateInvoice", Boolean.valueOf(!invoiceExists));
                action.put("wouldCreatePaymentApplication", Boolean.TRUE);

                if ("Y".equals(dryRun)) {
                    action.put("status", "DRY_RUN");
                    action.put("reason", invoiceExists
                            ? "Would apply existing payment to existing repayment invoice"
                            : "Would create repair repayment invoice and apply existing payment");
                    actions.add(action);
                    continue;
                }

                try {
                    if (!invoiceExists) {
                        invoiceId = createRepairRepaymentInvoice(
                                dispatcher,
                                userLogin,
                                currentLoanId,
                                loanScheduleId,
                                currentLoanRepaymentId,
                                paymentId,
                                resolvedOrgId,
                                loan,
                                repaymentDate,
                                amountApplied
                        );
                        createdInvoiceCount++;
                        action.put("createdInvoiceId", invoiceId);
                    }

                    Map<String, Object> applyCtx = new HashMap<String, Object>();
                    applyCtx.put("loanId", currentLoanId);
                    applyCtx.put("paymentId", paymentId);
                    applyCtx.put("invoiceId", invoiceId);
                    applyCtx.put("organizationPartyId", resolvedOrgId);
                    applyCtx.put("amountApplied", amountApplied);
                    applyCtx.put("userLogin", userLogin);

                    Map<String, Object> applyResp = dispatcher.runSync("applyLoanRepaymentPaymentToInvoiceV1", applyCtx);
                    if (ServiceUtil.isError(applyResp)) {
                        throw new GenericEntityException(ServiceUtil.getErrorMessage(applyResp));
                    }

                    String paymentApplicationId = trimToNull((String) applyResp.get("paymentApplicationId"));
                    if (UtilValidate.isEmpty(paymentApplicationId)) {
                        GenericValue createdApp = EntityQuery.use(delegator)
                                .from("PaymentApplication")
                                .where("paymentId", paymentId, "invoiceId", invoiceId)
                                .queryFirst();
                        paymentApplicationId = createdApp != null ? createdApp.getString("paymentApplicationId") : null;
                    }
                    if (UtilValidate.isEmpty(paymentApplicationId)) {
                        throw new GenericEntityException("Payment application repair completed but no paymentApplicationId could be verified");
                    }

                    createdPaymentApplicationCount++;
                    action.put("status", "DONE");
                    action.put("reason", "Missing repayment payment application repaired successfully");
                    action.put("invoiceId", invoiceId);
                    action.put("paymentApplicationId", paymentApplicationId);
                    action.put("paymentStatusId", applyResp.get("paymentStatusId"));
                    action.put("invoiceStatusId", applyResp.get("invoiceStatusId"));
                } catch (Exception e) {
                    errorCount++;
                    action.put("status", "ERROR");
                    action.put("reason", e.getMessage());
                    Debug.logError(e, "Error repairing payment application for loanRepaymentId=" + currentLoanRepaymentId, MODULE);
                }

                actions.add(action);
            }

            Map<String, Object> payload = new LinkedHashMap<String, Object>();
            payload.put("loanId", loanId);
            payload.put("loanRepaymentId", loanRepaymentId);
            payload.put("paymentId", paymentIdFilter);
            payload.put("organizationPartyId", organizationPartyId);
            payload.put("dryRun", dryRun);
            payload.put("createInvoiceIfMissing", createInvoiceIfMissing);
            payload.put("checkedRepaymentCount", Integer.valueOf(checkedRepaymentCount));
            payload.put("candidateCount", Integer.valueOf(candidateCount));
            payload.put("skippedCount", Integer.valueOf(skippedCount));
            payload.put("existingPaymentApplicationCount", Integer.valueOf(existingPaymentApplicationCount));
            payload.put("existingInvoiceCount", Integer.valueOf(existingInvoiceCount));
            payload.put("createdInvoiceCount", Integer.valueOf(createdInvoiceCount));
            payload.put("createdPaymentApplicationCount", Integer.valueOf(createdPaymentApplicationCount));
            payload.put("errorCount", Integer.valueOf(errorCount));
            payload.put("actions", actions);

            Map<String, Object> result = ServiceUtil.returnSuccess("Loan repayment payment application repair completed successfully");
            result.putAll(payload);
            result.put("data", new LinkedHashMap<String, Object>(payload));
            result.put("legacyData", new LinkedHashMap<String, Object>(payload));
            return result;
        } catch (Exception e) {
            Debug.logError(e, "Error running backfillLoanRepaymentPaymentApplicationV1", MODULE);
            return ServiceUtil.returnError(e.getMessage());
        }
    }

    private static List<GenericValue> findTargetRepayments(Delegator delegator, String loanId, String loanRepaymentId, String paymentId)
            throws GenericEntityException {
        List<EntityCondition> conditions = new ArrayList<EntityCondition>();
        if (UtilValidate.isNotEmpty(loanId)) {
            conditions.add(EntityCondition.makeCondition("loanId", loanId));
        }
        if (UtilValidate.isNotEmpty(loanRepaymentId)) {
            conditions.add(EntityCondition.makeCondition("loanRepaymentId", loanRepaymentId));
        }
        if (UtilValidate.isNotEmpty(paymentId)) {
            conditions.add(EntityCondition.makeCondition("paymentId", paymentId));
        }
        return EntityQuery.use(delegator)
                .from("PartyLoanRepayment")
                .where(EntityCondition.makeCondition(conditions, EntityOperator.AND))
                .orderBy("loanId", "repaymentDate", "loanRepaymentId")
                .queryList();
    }

    private static GenericValue findLoan(Delegator delegator, String loanId) throws GenericEntityException {
        if (UtilValidate.isEmpty(loanId)) return null;
        return EntityQuery.use(delegator).from("PartyLoan").where("loanId", loanId).queryOne();
    }

    private static String findExistingRepairInvoiceId(Delegator delegator,
                                                      String loanId,
                                                      String loanScheduleId,
                                                      String loanRepaymentId,
                                                      String paymentId,
                                                      String organizationPartyId) throws GenericEntityException {
        List<String> refs = new ArrayList<String>();
        refs.add(buildRepairReference(loanId, loanScheduleId, loanRepaymentId, paymentId));
        refs.add("LOAN-REPAY-" + loanId + (UtilValidate.isNotEmpty(loanScheduleId) ? "-" + loanScheduleId : ""));

        for (String ref : refs) {
            GenericValue invoice = EntityQuery.use(delegator)
                    .from("Invoice")
                    .where("invoiceTypeId", LOAN_REPAYMENT_INVOICE_TYPE_ID,
                            "partyIdFrom", organizationPartyId,
                            "referenceNumber", ref)
                    .queryFirst();
            if (invoice != null) {
                return invoice.getString("invoiceId");
            }
        }
        return null;
    }

    private static String createRepairRepaymentInvoice(LocalDispatcher dispatcher,
                                                       GenericValue userLogin,
                                                       String loanId,
                                                       String loanScheduleId,
                                                       String loanRepaymentId,
                                                       String paymentId,
                                                       String organizationPartyId,
                                                       GenericValue loan,
                                                       Timestamp invoiceDate,
                                                       BigDecimal amountApplied) throws Exception {
        String partyId = loan.getString("partyId");
        String currencyUomId = loan.getString("currencyUomId");
        if (UtilValidate.isEmpty(partyId)) {
            throw new GenericEntityException("Loan partyId is required to create repair repayment invoice");
        }
        if (UtilValidate.isEmpty(currencyUomId)) {
            currencyUomId = "USD";
        }

        Map<String, Object> invoiceCtx = new HashMap<String, Object>();
        invoiceCtx.put("invoiceTypeId", LOAN_REPAYMENT_INVOICE_TYPE_ID);
        invoiceCtx.put("partyIdFrom", organizationPartyId);
        invoiceCtx.put("partyId", partyId);
        invoiceCtx.put("currencyUomId", currencyUomId);
        String billingAccountId = trimToNull(loan.getString("billingAccountId"));
        if (UtilValidate.isNotEmpty(billingAccountId)) {
            invoiceCtx.put("billingAccountId", billingAccountId);
        }
        invoiceCtx.put("invoiceDate", invoiceDate != null ? invoiceDate : UtilDateTime.nowTimestamp());
        invoiceCtx.put("statusId", "INVOICE_IN_PROCESS");
        invoiceCtx.put("referenceNumber", buildRepairReference(loanId, loanScheduleId, loanRepaymentId, paymentId));
        // Keep description intentionally simple. OFBiz input policy may reject punctuation-rich dynamic text.
        invoiceCtx.put("description", "Loan repayment repair invoice");
        invoiceCtx.put("userLogin", userLogin);

        Map<String, Object> invoiceResp = dispatcher.runSync("createInvoice", invoiceCtx);
        if (ServiceUtil.isError(invoiceResp)) {
            throw new GenericEntityException("createInvoice failed: " + ServiceUtil.getErrorMessage(invoiceResp));
        }
        String invoiceId = trimToNull((String) invoiceResp.get("invoiceId"));
        if (UtilValidate.isEmpty(invoiceId)) {
            throw new GenericEntityException("createInvoice succeeded but returned no invoiceId");
        }

        Map<String, Object> itemCtx = new HashMap<String, Object>();
        itemCtx.put("invoiceId", invoiceId);
        itemCtx.put("invoiceItemTypeId", LOAN_PRINCIPAL_ITEM_TYPE_ID);
        itemCtx.put("description", "Loan repayment repair item");
        itemCtx.put("amount", amountApplied);
        itemCtx.put("quantity", BigDecimal.ONE);
        itemCtx.put("userLogin", userLogin);

        Map<String, Object> itemResp = dispatcher.runSync("createInvoiceItem", itemCtx);
        if (ServiceUtil.isError(itemResp)) {
            throw new GenericEntityException("createInvoiceItem failed: " + ServiceUtil.getErrorMessage(itemResp));
        }

        return invoiceId;
    }

    private static String buildRepairReference(String loanId, String loanScheduleId, String loanRepaymentId, String paymentId) {
        StringBuilder sb = new StringBuilder("LOAN-REPAY-REPAIR-");
        sb.append(loanId);
        if (UtilValidate.isNotEmpty(loanScheduleId)) sb.append("-").append(loanScheduleId);
        if (UtilValidate.isNotEmpty(loanRepaymentId)) sb.append("-").append(loanRepaymentId);
        if (UtilValidate.isNotEmpty(paymentId)) sb.append("-").append(paymentId);
        return sb.toString();
    }

    private static BigDecimal safeAmount(BigDecimal value) {
        return value != null ? value : BigDecimal.ZERO;
    }

    private static String trimToNull(String value) {
        if (value == null) return null;
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    private static String normalizeFlagDefaultY(String value) {
        return value == null || "Y".equalsIgnoreCase(value) ? "Y" : "N";
    }
}
