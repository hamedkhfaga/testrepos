package org.apache.ofbiz.loan.services;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.apache.ofbiz.base.util.Debug;
import org.apache.ofbiz.base.util.UtilDateTime;
import org.apache.ofbiz.base.util.UtilMisc;
import org.apache.ofbiz.base.util.UtilValidate;
import org.apache.ofbiz.entity.Delegator;
import org.apache.ofbiz.entity.GenericEntityException;
import org.apache.ofbiz.entity.GenericValue;
import org.apache.ofbiz.entity.util.EntityQuery;
import org.apache.ofbiz.service.DispatchContext;
import org.apache.ofbiz.service.LocalDispatcher;
import org.apache.ofbiz.service.ServiceUtil;

public final class LoanDisbursementInvoiceServices {

    public static final String MODULE = LoanDisbursementInvoiceServices.class.getName();

    private static final String LOAN_DISBURSEMENT_INVOICE_TYPE_ID = "LOAN_DISBURSEMENT";
    private static final String LOAN_REPAYMENT_INVOICE_TYPE_ID = "LOAN_REPAYMENT";
    private static final String LOAN_PRINCIPAL_ITEM_TYPE_ID = "LOAN_PRINCIPAL";
    private static final String LOAN_INTEREST_ITEM_TYPE_ID = "LOAN_INTEREST";
    private static final String LOAN_FEE_ITEM_TYPE_ID = "LOAN_FEE";

    private static final String FINANCE_MANAGED = "FINANCE_MANAGED";
    private static final String LOAN_MANAGED = "LOAN_MANAGED";

    private LoanDisbursementInvoiceServices() {}

    public static Map<String, Object> createLoanDisbursementInvoiceV1(DispatchContext dctx, Map<String, ?> context) {
        Delegator delegator = dctx.getDelegator();
        LocalDispatcher dispatcher = dctx.getDispatcher();
        GenericValue userLogin = (GenericValue) context.get("userLogin");

        try {
            String loanId = (String) context.get("loanId");
            String organizationPartyId = (String) context.get("organizationPartyId");
            String paymentId = trimToNull((String) context.get("paymentId"));
            Timestamp invoiceDate = (Timestamp) context.get("invoiceDate");

            if (UtilValidate.isEmpty(loanId)) {
                return ServiceUtil.returnError("loanId is required");
            }
            if (UtilValidate.isEmpty(organizationPartyId)) {
                return ServiceUtil.returnError("organizationPartyId is required");
            }
            if (invoiceDate == null) {
                invoiceDate = UtilDateTime.nowTimestamp();
            }

            GenericValue loan = requireLoan(delegator, loanId);
            if (!organizationPartyId.equals(loan.getString("organizationPartyId"))) {
                return ServiceUtil.returnError("organizationPartyId mismatch for loanId: " + loanId);
            }

            String partyId = loan.getString("partyId");
            String currencyUomId = loan.getString("currencyUomId");
            BigDecimal principalAmount = loan.getBigDecimal("principalAmount");

            if (UtilValidate.isEmpty(partyId) || UtilValidate.isEmpty(currencyUomId) || principalAmount == null) {
                return ServiceUtil.returnError("Loan partyId, currencyUomId, and principalAmount are required");
            }

            ensureLoanInvoiceTypeExists(delegator, LOAN_DISBURSEMENT_INVOICE_TYPE_ID);
            ensureLoanInvoiceItemTypeExists(delegator, LOAN_PRINCIPAL_ITEM_TYPE_ID);

            String existingInvoiceId = findExistingDisbursementInvoiceId(
                    delegator,
                    loanId,
                    organizationPartyId,
                    partyId
            );
            if (UtilValidate.isNotEmpty(existingInvoiceId)) {
                Map<String, Object> result = ServiceUtil.returnSuccess("Disbursement invoice already exists");
                result.put("loanId", loanId);
                result.put("paymentId", paymentId);
                result.put("invoiceId", existingInvoiceId);
                result.put("invoiceAmount", principalAmount);
                result.put("duplicateSkipped", "Y");
                return result;
            }

            String billingAccountId = trimToNull(loan.getString("billingAccountId"));
            if (UtilValidate.isEmpty(billingAccountId)) {
                return ServiceUtil.returnError("Loan missing billingAccountId: " + loanId);
            }

            Map<String, Object> invoiceCtx = new HashMap<String, Object>();
            invoiceCtx.put("invoiceTypeId", LOAN_DISBURSEMENT_INVOICE_TYPE_ID);
            invoiceCtx.put("partyIdFrom", organizationPartyId);
            invoiceCtx.put("partyId", partyId);
            invoiceCtx.put("currencyUomId", currencyUomId);
            invoiceCtx.put("billingAccountId", billingAccountId);
            invoiceCtx.put("invoiceDate", invoiceDate);
            invoiceCtx.put("statusId", "INVOICE_IN_PROCESS");
            invoiceCtx.put("referenceNumber", loanId);
            invoiceCtx.put("userLogin", userLogin);

            Map<String, Object> invoiceResp = dispatcher.runSync("createInvoice", invoiceCtx);
            if (ServiceUtil.isError(invoiceResp)) {
                return ServiceUtil.returnError("createInvoice failed: " + ServiceUtil.getErrorMessage(invoiceResp));
            }

            String invoiceId = (String) invoiceResp.get("invoiceId");
            if (UtilValidate.isEmpty(invoiceId)) {
                return ServiceUtil.returnError("createInvoice succeeded but returned no invoiceId");
            }

            Map<String, Object> itemCtx = new HashMap<String, Object>();
            itemCtx.put("invoiceId", invoiceId);
            itemCtx.put("invoiceItemTypeId", LOAN_PRINCIPAL_ITEM_TYPE_ID);
            itemCtx.put("description", "Loan principal");
            itemCtx.put("amount", principalAmount);
            itemCtx.put("quantity", BigDecimal.ONE);
            itemCtx.put("userLogin", userLogin);

            Map<String, Object> itemResp = dispatcher.runSync("createInvoiceItem", itemCtx);
            if (ServiceUtil.isError(itemResp)) {
                return ServiceUtil.returnError("createInvoiceItem failed: " + ServiceUtil.getErrorMessage(itemResp));
            }

            Map<String, Object> result = ServiceUtil.returnSuccess("Loan disbursement invoice created successfully");
            result.put("loanId", loanId);
            result.put("paymentId", paymentId);
            result.put("invoiceId", invoiceId);
            result.put("invoiceAmount", principalAmount);
            return result;

        } catch (Exception e) {
            Debug.logError(e, "Error creating loan disbursement invoice", MODULE);
            return ServiceUtil.returnError(e.getMessage());
        }
    }

    public static Map<String, Object> createLoanRepaymentInvoiceV1(DispatchContext dctx, Map<String, ?> context) {
        Delegator delegator = dctx.getDelegator();
        LocalDispatcher dispatcher = dctx.getDispatcher();
        GenericValue userLogin = (GenericValue) context.get("userLogin");

        try {
            String loanId = (String) context.get("loanId");
            String loanScheduleId = trimToNull((String) context.get("loanScheduleId"));
            String organizationPartyId = (String) context.get("organizationPartyId");
            Timestamp invoiceDate = (Timestamp) context.get("invoiceDate");
            BigDecimal principalAmount = (BigDecimal) context.get("principalAmount");
            BigDecimal interestAmount = (BigDecimal) context.get("interestAmount");
            BigDecimal feeAmount = (BigDecimal) context.get("feeAmount");
            String financeProcessingMode = normalizeFinanceProcessingMode((String) context.get("financeProcessingMode"));

            if (UtilValidate.isEmpty(loanId)) {
                return ServiceUtil.returnError("loanId is required");
            }
            if (UtilValidate.isEmpty(organizationPartyId)) {
                return ServiceUtil.returnError("organizationPartyId is required");
            }
            if (invoiceDate == null) {
                invoiceDate = UtilDateTime.nowTimestamp();
            }

            GenericValue loan = requireLoan(delegator, loanId);
            if (!organizationPartyId.equals(loan.getString("organizationPartyId"))) {
                return ServiceUtil.returnError("organizationPartyId mismatch for loanId: " + loanId);
            }

            String billingAccountId = trimToNull(loan.getString("billingAccountId"));
            if (UtilValidate.isEmpty(billingAccountId)) {
                return ServiceUtil.returnError("Loan missing billingAccountId: " + loanId);
            }

            String partyId = loan.getString("partyId");
            String currencyUomId = loan.getString("currencyUomId");
            if (UtilValidate.isEmpty(partyId) || UtilValidate.isEmpty(currencyUomId)) {
                return ServiceUtil.returnError("Loan partyId and currencyUomId are required");
            }

            ensureLoanInvoiceTypeExists(delegator, LOAN_REPAYMENT_INVOICE_TYPE_ID);
            ensureLoanInvoiceItemTypeExists(delegator, LOAN_PRINCIPAL_ITEM_TYPE_ID);
            ensureLoanInvoiceItemTypeExists(delegator, LOAN_INTEREST_ITEM_TYPE_ID);
            ensureLoanInvoiceItemTypeExists(delegator, LOAN_FEE_ITEM_TYPE_ID);

            String referenceNumber = buildRepaymentInvoiceReference(loanId, loanScheduleId);
            String existingInvoiceId = findExistingRepaymentInvoiceId(
                    delegator,
                    loanId,
                    loanScheduleId,
                    organizationPartyId,
                    partyId
            );
            if (UtilValidate.isNotEmpty(existingInvoiceId)) {
                Map<String, Object> result = ServiceUtil.returnSuccess("Repayment invoice already exists");
                result.put("loanId", loanId);
                result.put("loanScheduleId", loanScheduleId);
                result.put("invoiceId", existingInvoiceId);
                result.put("billingAccountId", billingAccountId);
                result.put("principalAmount", principalAmount);
                result.put("interestAmount", interestAmount);
                result.put("feeAmount", feeAmount);
                result.put("duplicateSkipped", "Y");
                return result;
            }

            Map<String, Object> invoiceCtx = new HashMap<String, Object>();
            invoiceCtx.put("invoiceTypeId", LOAN_REPAYMENT_INVOICE_TYPE_ID);
            invoiceCtx.put("partyIdFrom", organizationPartyId);
            invoiceCtx.put("partyId", partyId);
            invoiceCtx.put("currencyUomId", currencyUomId);
            invoiceCtx.put("billingAccountId", billingAccountId);
            invoiceCtx.put("invoiceDate", invoiceDate);
            invoiceCtx.put("statusId", "INVOICE_IN_PROCESS");
            invoiceCtx.put("referenceNumber", referenceNumber);
            invoiceCtx.put("userLogin", userLogin);

            Map<String, Object> invoiceResp = dispatcher.runSync("createInvoice", invoiceCtx);
            if (ServiceUtil.isError(invoiceResp)) {
                return ServiceUtil.returnError("createInvoice failed: " + ServiceUtil.getErrorMessage(invoiceResp));
            }

            String invoiceId = (String) invoiceResp.get("invoiceId");
            if (UtilValidate.isEmpty(invoiceId)) {
                return ServiceUtil.returnError("createInvoice succeeded but returned no invoiceId");
            }

            createRepaymentInvoiceItem(dispatcher, userLogin, invoiceId, LOAN_PRINCIPAL_ITEM_TYPE_ID, "Loan principal repayment", principalAmount);
            createRepaymentInvoiceItem(dispatcher, userLogin, invoiceId, LOAN_INTEREST_ITEM_TYPE_ID, "Loan interest", interestAmount);
            createRepaymentInvoiceItem(dispatcher, userLogin, invoiceId, LOAN_FEE_ITEM_TYPE_ID, "Loan fee", feeAmount);

            Map<String, Object> result = ServiceUtil.returnSuccess("Loan repayment invoice created successfully");
            result.put("loanId", loanId);
            result.put("loanScheduleId", loanScheduleId);
            result.put("invoiceId", invoiceId);
            result.put("billingAccountId", billingAccountId);
            result.put("principalAmount", principalAmount);
            result.put("interestAmount", interestAmount);
            result.put("feeAmount", feeAmount);
            result.put("duplicateSkipped", "N");
            return result;

        } catch (Exception e) {
            Debug.logError(e, "Error creating loan repayment invoice", MODULE);
            return ServiceUtil.returnError(e.getMessage());
        }
    }

private static String resolveTargetPaymentStatus(GenericValue payment, String overrideStatusId) {
    if (UtilValidate.isNotEmpty(overrideStatusId)) {
        return overrideStatusId;
    }

    String paymentTypeId = payment.getString("paymentTypeId");
    String partyFrom = payment.getString("partyIdFrom");
    String partyTo = payment.getString("partyIdTo");

    // Incoming payment (customer → company)
    if ("CUSTOMER_PAYMENT".equals(paymentTypeId)) {
        return "PMNT_RECEIVED";
    }

    // Outgoing payment (company → employee/vendor)
    if ("DISBURSEMENT".equals(paymentTypeId)) {
        return "PMNT_SENT";
    }

    // Fallback (safe default)
    return "PMNT_SENT";
}
    public static Map<String, Object> generateDueLoanRepaymentInvoiceV1(DispatchContext dctx, Map<String, ?> context) {
        Delegator delegator = dctx.getDelegator();
        LocalDispatcher dispatcher = dctx.getDispatcher();
        GenericValue userLogin = (GenericValue) context.get("userLogin");

        try {
            String loanId = (String) context.get("loanId");
            String loanScheduleId = trimToNull((String) context.get("loanScheduleId"));
            String organizationPartyId = (String) context.get("organizationPartyId");
            Timestamp invoiceDate = (Timestamp) context.get("invoiceDate");
            BigDecimal principalAmount = (BigDecimal) context.get("principalAmount");
            BigDecimal interestAmount = (BigDecimal) context.get("interestAmount");
            BigDecimal feeAmount = (BigDecimal) context.get("feeAmount");

            if (UtilValidate.isEmpty(loanId)) {
                return ServiceUtil.returnError("loanId is required");
            }
            if (UtilValidate.isEmpty(organizationPartyId)) {
                return ServiceUtil.returnError("organizationPartyId is required");
            }
            if (invoiceDate == null) {
                invoiceDate = UtilDateTime.nowTimestamp();
            }

            GenericValue loan = requireLoan(delegator, loanId);
            if (!organizationPartyId.equals(loan.getString("organizationPartyId"))) {
                return ServiceUtil.returnError("organizationPartyId mismatch for loanId: " + loanId);
            }

            GenericValue schedule = null;
            if (UtilValidate.isNotEmpty(loanScheduleId)) {
                schedule = EntityQuery.use(delegator)
                        .from("PartyLoanSchedule")
                        .where("loanScheduleId", loanScheduleId)
                        .queryOne();
                if (schedule == null) {
                    return ServiceUtil.returnError("Loan schedule row not found: " + loanScheduleId);
                }
                if (!loanId.equals(schedule.getString("loanId"))) {
                    return ServiceUtil.returnError("loanScheduleId does not belong to loanId. loanId="
                            + loanId + ", loanScheduleId=" + loanScheduleId);
                }
                BigDecimal scheduleOutstanding = safeAmount(schedule.getBigDecimal("outstandingAmount"));
                if (scheduleOutstanding.compareTo(BigDecimal.ZERO) <= 0) {
                    return ServiceUtil.returnError("Loan schedule row has no outstanding amount: " + loanScheduleId);
                }
                String scheduleStatusId = trimToNull(schedule.getString("scheduleStatusId"));
                if ("LNS_PAID".equals(scheduleStatusId)) {
                    return ServiceUtil.returnError("Loan schedule row is already paid: " + loanScheduleId);
                }
            }

            if (principalAmount == null) {
                if (schedule != null) {
                    principalAmount = schedule.getBigDecimal("outstandingAmount");
                    if (principalAmount == null || principalAmount.compareTo(BigDecimal.ZERO) <= 0) {
                        principalAmount = schedule.getBigDecimal("installmentAmount");
                    }
                }
                if (principalAmount == null) {
                    principalAmount = BigDecimal.ZERO;
                }
            }
            if (interestAmount == null) {
                interestAmount = BigDecimal.ZERO;
            }
            if (feeAmount == null) {
                feeAmount = BigDecimal.ZERO;
            }

            String existingInvoiceId = findExistingRepaymentInvoiceId(
                    delegator,
                    loanId,
                    loanScheduleId,
                    organizationPartyId,
                    loan.getString("partyId")
            );
            if (UtilValidate.isNotEmpty(existingInvoiceId)) {
                Map<String, Object> result = ServiceUtil.returnSuccess("Due loan repayment invoice already exists");
                result.put("loanId", loanId);
                result.put("loanScheduleId", loanScheduleId);
                result.put("invoiceId", existingInvoiceId);
                result.put("billingAccountId", loan.getString("billingAccountId"));
                result.put("principalAmount", principalAmount);
                result.put("interestAmount", interestAmount);
                result.put("feeAmount", feeAmount);
                result.put("duplicateSkipped", "Y");
                return result;
            }

            Map<String, Object> invoiceCtx = new HashMap<String, Object>();
            invoiceCtx.put("loanId", loanId);
            invoiceCtx.put("loanScheduleId", loanScheduleId);
            invoiceCtx.put("organizationPartyId", organizationPartyId);
            invoiceCtx.put("invoiceDate", invoiceDate);
            invoiceCtx.put("principalAmount", principalAmount);
            invoiceCtx.put("interestAmount", interestAmount);
            invoiceCtx.put("feeAmount", feeAmount);
            invoiceCtx.put("userLogin", userLogin);

            Map<String, Object> invoiceResp = dispatcher.runSync("createLoanRepaymentInvoiceV1", invoiceCtx);
            if (ServiceUtil.isError(invoiceResp)) {
                return ServiceUtil.returnError(ServiceUtil.getErrorMessage(invoiceResp));
            }

            Map<String, Object> result = ServiceUtil.returnSuccess("Due loan repayment invoice generated successfully");
            result.put("loanId", loanId);
            result.put("loanScheduleId", loanScheduleId);
            result.put("invoiceId", invoiceResp.get("invoiceId"));
            result.put("billingAccountId", invoiceResp.get("billingAccountId"));
            result.put("principalAmount", principalAmount);
            result.put("interestAmount", interestAmount);
            result.put("feeAmount", feeAmount);
            result.put("duplicateSkipped", invoiceResp.get("duplicateSkipped"));
            return result;

        } catch (Exception e) {
            Debug.logError(e, "Error generating due loan repayment invoice", MODULE);
            return ServiceUtil.returnError(e.getMessage());
        }
    }


    public static Map<String, Object> finalizeFullyAppliedLoanRepaymentInvoicesV1(DispatchContext dctx, Map<String, ?> context) {
        Delegator delegator = dctx.getDelegator();
        LocalDispatcher dispatcher = dctx.getDispatcher();
        GenericValue userLogin = (GenericValue) context.get("userLogin");

        try {
            String loanId = trimToNull((String) context.get("loanId"));
            String organizationPartyId = trimToNull((String) context.get("organizationPartyId"));
            String dryRun = trimToNull((String) context.get("dryRun"));
            if (UtilValidate.isEmpty(dryRun)) {
                dryRun = "Y";
            }
            String invoicePaidStatusId = trimToNull((String) context.get("invoicePaidStatusId"));
            if (UtilValidate.isEmpty(invoicePaidStatusId)) {
                invoicePaidStatusId = "INVOICE_PAID";
            }
            if (UtilValidate.isEmpty(loanId) && UtilValidate.isEmpty(organizationPartyId)) {
                return ServiceUtil.returnError("loanId or organizationPartyId is required");
            }

            List<GenericValue> invoices = findLoanRepaymentInvoices(delegator, loanId, organizationPartyId);
            int checkedInvoiceCount = 0;
            int fullyAppliedCount = 0;
            int alreadyPaidCount = 0;
            int updatedInvoiceCount = 0;
            List<Map<String, Object>> actions = new java.util.ArrayList<Map<String, Object>>();

            for (GenericValue invoice : invoices) {
                checkedInvoiceCount++;
                String invoiceId = invoice.getString("invoiceId");
                BigDecimal invoiceTotal = sumInvoiceTotal(delegator, invoiceId);
                BigDecimal appliedTotal = sumPaymentApplications(delegator, invoiceId);
                String statusId = trimToNull(invoice.getString("statusId"));
                boolean fullyApplied = invoiceTotal.compareTo(BigDecimal.ZERO) > 0
                        && appliedTotal.compareTo(invoiceTotal) >= 0;
                if (fullyApplied) {
                    fullyAppliedCount++;
                }

                Map<String, Object> action = new java.util.LinkedHashMap<String, Object>();
                action.put("invoiceId", invoiceId);
                action.put("invoiceStatusId", statusId);
                action.put("invoiceTotal", invoiceTotal);
                action.put("appliedTotal", appliedTotal);
                action.put("fullyApplied", Boolean.valueOf(fullyApplied));
                action.put("referenceNumber", invoice.getString("referenceNumber"));

                if (!fullyApplied) {
                    action.put("status", "SKIPPED");
                    action.put("reason", "Invoice is not fully applied");
                } else if (invoicePaidStatusId.equals(statusId)) {
                    alreadyPaidCount++;
                    action.put("status", "SKIPPED");
                    action.put("reason", "Invoice already paid");
                } else if ("Y".equalsIgnoreCase(dryRun)) {
                    action.put("status", "DRY_RUN");
                    action.put("targetStatusId", invoicePaidStatusId);
                } else {
                    markInvoicePaidSafely(delegator, dispatcher, invoiceId, invoicePaidStatusId, userLogin);
                    updatedInvoiceCount++;
                    action.put("status", "UPDATED");
                    action.put("targetStatusId", invoicePaidStatusId);
                }
                actions.add(action);
            }

            Map<String, Object> data = new HashMap<String, Object>();
            data.put("loanId", loanId);
            data.put("organizationPartyId", organizationPartyId);
            data.put("dryRun", dryRun);
            data.put("checkedInvoiceCount", Integer.valueOf(checkedInvoiceCount));
            data.put("fullyAppliedCount", Integer.valueOf(fullyAppliedCount));
            data.put("alreadyPaidCount", Integer.valueOf(alreadyPaidCount));
            data.put("updatedInvoiceCount", Integer.valueOf(updatedInvoiceCount));
            data.put("actions", actions);

            Map<String, Object> result = ServiceUtil.returnSuccess("Fully-applied loan repayment invoice status check completed");
            result.putAll(data);
            result.put("data", new HashMap<String, Object>(data));
            result.put("legacyData", new HashMap<String, Object>(data));
            return result;
        } catch (Exception e) {
            Debug.logError(e, "Error finalizing fully-applied loan repayment invoices", MODULE);
            return ServiceUtil.returnError(e.getMessage());
        }
    }

    private static List<GenericValue> findLoanRepaymentInvoices(Delegator delegator,
                                                                String loanId,
                                                                String organizationPartyId) throws GenericEntityException {
        List<org.apache.ofbiz.entity.condition.EntityCondition> conds = new java.util.ArrayList<org.apache.ofbiz.entity.condition.EntityCondition>();
        conds.add(org.apache.ofbiz.entity.condition.EntityCondition.makeCondition("invoiceTypeId", LOAN_REPAYMENT_INVOICE_TYPE_ID));
        if (UtilValidate.isNotEmpty(organizationPartyId)) {
            conds.add(org.apache.ofbiz.entity.condition.EntityCondition.makeCondition("partyIdFrom", organizationPartyId));
        }
        if (UtilValidate.isNotEmpty(loanId)) {
            conds.add(org.apache.ofbiz.entity.condition.EntityCondition.makeCondition("referenceNumber",
                    org.apache.ofbiz.entity.condition.EntityOperator.LIKE, loanId + "::%"));
        }
        return EntityQuery.use(delegator)
                .from("Invoice")
                .where(org.apache.ofbiz.entity.condition.EntityCondition.makeCondition(conds,
                        org.apache.ofbiz.entity.condition.EntityOperator.AND))
                .orderBy("invoiceId")
                .queryList();
    }

    public static Map<String, Object> applyLoanDisbursementPaymentToInvoiceV1(DispatchContext dctx, Map<String, ?> context) {
        return applyLoanPaymentToInvoiceInternal(dctx, context, true);
    }

    public static Map<String, Object> applyLoanRepaymentPaymentToInvoiceV1(DispatchContext dctx, Map<String, ?> context) {
        return applyLoanPaymentToInvoiceInternal(dctx, context, false);
    }

    private static Map<String, Object> applyLoanPaymentToInvoiceInternal(DispatchContext dctx,
                                                                         Map<String, ?> context,
                                                                         boolean requireDisbursementPayment) {
        Delegator delegator = dctx.getDelegator();
        LocalDispatcher dispatcher = dctx.getDispatcher();
        GenericValue userLogin = (GenericValue) context.get("userLogin");

        try {
            String loanId = (String) context.get("loanId");
            String paymentId = (String) context.get("paymentId");
            String invoiceId = (String) context.get("invoiceId");
            String organizationPartyId = (String) context.get("organizationPartyId");
            BigDecimal amountApplied = (BigDecimal) context.get("amountApplied");
            String paymentTargetStatusId = trimToNull((String) context.get("paymentTargetStatusId"));
            String invoiceApprovedStatusId = trimToNull((String) context.get("invoiceApprovedStatusId"));
            String invoiceReadyStatusId = trimToNull((String) context.get("invoiceReadyStatusId"));
            String invoicePaidStatusId = trimToNull((String) context.get("invoicePaidStatusId"));
            String runAccountingFlag = trimToNull((String) context.get("runAccountingFlag"));

            if (UtilValidate.isEmpty(loanId)) return ServiceUtil.returnError("loanId is required");
            if (UtilValidate.isEmpty(paymentId)) return ServiceUtil.returnError("paymentId is required");
            if (UtilValidate.isEmpty(invoiceId)) return ServiceUtil.returnError("invoiceId is required");
            if (UtilValidate.isEmpty(organizationPartyId)) return ServiceUtil.returnError("organizationPartyId is required");

            // paymentTargetStatusId resolved after payment is loaded
            if (UtilValidate.isEmpty(invoiceApprovedStatusId)) invoiceApprovedStatusId = "INVOICE_APPROVED";
            if (UtilValidate.isEmpty(invoiceReadyStatusId)) invoiceReadyStatusId = "INVOICE_READY";
            if (UtilValidate.isEmpty(invoicePaidStatusId)) invoicePaidStatusId = "INVOICE_PAID";
            if (UtilValidate.isEmpty(runAccountingFlag)) runAccountingFlag = "Y";

            GenericValue loan = requireLoan(delegator, loanId);
            if (!organizationPartyId.equals(loan.getString("organizationPartyId"))) {
                return ServiceUtil.returnError("organizationPartyId mismatch for loanId: " + loanId);
            }

            if (requireDisbursementPayment) {
                if (UtilValidate.isEmpty(loan.getString("disbursementPaymentId"))
                        || !paymentId.equals(loan.getString("disbursementPaymentId"))) {
                    return ServiceUtil.returnError("paymentId does not match loan disbursement payment");
                }
            }

            GenericValue payment = EntityQuery.use(delegator).from("Payment").where("paymentId", paymentId).queryOne();
            if (payment == null) return ServiceUtil.returnError("Payment not found: " + paymentId);

            paymentTargetStatusId = resolveTargetPaymentStatus(payment, paymentTargetStatusId);

            GenericValue invoice = EntityQuery.use(delegator).from("Invoice").where("invoiceId", invoiceId).queryOne();
            if (invoice == null) return ServiceUtil.returnError("Invoice not found: " + invoiceId);
            if (!organizationPartyId.equals(invoice.getString("partyIdFrom"))) {
                return ServiceUtil.returnError("Invoice organizationPartyId mismatch for invoiceId: " + invoiceId);
            }
            if (!requireDisbursementPayment) {
                String invoiceTypeId = trimToNull(invoice.getString("invoiceTypeId"));
                if (UtilValidate.isNotEmpty(invoiceTypeId) && !LOAN_REPAYMENT_INVOICE_TYPE_ID.equals(invoiceTypeId)) {
                    return ServiceUtil.returnError("Invoice is not a repayment invoice: " + invoiceId);
                }
            }

            if (amountApplied == null || amountApplied.compareTo(BigDecimal.ZERO) <= 0) {
                amountApplied = payment.getBigDecimal("amount");
            }
            if (amountApplied == null || amountApplied.compareTo(BigDecimal.ZERO) <= 0) {
                return ServiceUtil.returnError("amountApplied must be greater than zero");
            }
            BigDecimal invoiceTotal = sumInvoiceTotal(delegator, invoiceId);
            BigDecimal alreadyApplied = sumPaymentApplications(delegator, invoiceId);
            BigDecimal remainingInvoiceAmount = invoiceTotal.subtract(alreadyApplied);
            if (remainingInvoiceAmount.compareTo(BigDecimal.ZERO) < 0) {
                remainingInvoiceAmount = BigDecimal.ZERO;
            }

            String paymentStatusId;
            if (requireDisbursementPayment) {
                Map<String, Object> payCtx = UtilMisc.toMap(
                        "loanId", loanId,
                        "paymentId", paymentId,
                        "organizationPartyId", organizationPartyId,
                        "statusId", paymentTargetStatusId,
                        "runAccountingFlag", runAccountingFlag,
                        "userLogin", userLogin
                );
                Map<String, Object> payResp = dispatcher.runSync("updateLoanDisbursementPaymentStatusV1", payCtx);
                if (ServiceUtil.isError(payResp)) {
                    return ServiceUtil.returnError("updateLoanDisbursementPaymentStatusV1 failed: " + ServiceUtil.getErrorMessage(payResp));
                }
                paymentStatusId = (String) payResp.get("statusId");
            } else {
                Map<String, Object> payCtx = UtilMisc.toMap(
                        "paymentId", paymentId,
                        "statusId", paymentTargetStatusId,
                        "userLogin", userLogin
                );
                Map<String, Object> payResp = dispatcher.runSync("setPaymentStatus", payCtx);
                if (ServiceUtil.isError(payResp)) {
                    return ServiceUtil.returnError("setPaymentStatus failed: " + ServiceUtil.getErrorMessage(payResp));
                }
                paymentStatusId = paymentTargetStatusId;
            }

            GenericValue existingApp = EntityQuery.use(delegator)
                    .from("PaymentApplication")
                    .where("paymentId", paymentId, "invoiceId", invoiceId)
                    .queryFirst();

            String paymentApplicationId;
            if (existingApp == null) {
                if (remainingInvoiceAmount.compareTo(BigDecimal.ZERO) <= 0) {
                    return ServiceUtil.returnError("Invoice is already fully applied: " + invoiceId);
                }
                if (amountApplied.compareTo(remainingInvoiceAmount) > 0) {
                    return ServiceUtil.returnError("amountApplied exceeds remaining unapplied invoice amount for invoiceId="
                            + invoiceId + ". remaining=" + remainingInvoiceAmount + ", requested=" + amountApplied);
                }
                Map<String, Object> appCtx = new HashMap<String, Object>();
                appCtx.put("paymentId", paymentId);
                appCtx.put("invoiceId", invoiceId);
                appCtx.put("amountApplied", amountApplied);
                String overrideGlAccountId = trimToNull(payment.getString("overrideGlAccountId"));
                if (UtilValidate.isNotEmpty(overrideGlAccountId)) {
                    appCtx.put("overrideGlAccountId", overrideGlAccountId);
                }
                appCtx.put("userLogin", userLogin);

                Map<String, Object> appResp = dispatcher.runSync("createPaymentApplication", appCtx);
                if (ServiceUtil.isError(appResp)) {
                    return ServiceUtil.returnError("createPaymentApplication failed: " + ServiceUtil.getErrorMessage(appResp));
                }
                paymentApplicationId = trimToNull((String) appResp.get("paymentApplicationId"));
                if (UtilValidate.isEmpty(paymentApplicationId)) {
                    GenericValue createdApp = EntityQuery.use(delegator)
                            .from("PaymentApplication")
                            .where("paymentId", paymentId, "invoiceId", invoiceId)
                            .queryFirst();
                    paymentApplicationId = createdApp != null ? createdApp.getString("paymentApplicationId") : null;
                }
            } else {
                paymentApplicationId = existingApp.getString("paymentApplicationId");
                amountApplied = existingApp.getBigDecimal("amountApplied");
            }

        ///    if ("Y".equalsIgnoreCase(runAccountingFlag)) {
         ///       tryRunPaymentApplicationAccountingHooks(dispatcher, paymentApplicationId, paymentId, invoiceId, userLogin);
         ///   }

            BigDecimal appliedAfter = sumPaymentApplications(delegator, invoiceId);
            boolean invoiceFullyApplied = invoiceTotal.compareTo(BigDecimal.ZERO) > 0
                    && appliedAfter.compareTo(invoiceTotal) >= 0;

            if (invoiceFullyApplied) {
                markInvoicePaidSafely(delegator, dispatcher, invoiceId, invoicePaidStatusId, userLogin);
            }

            GenericValue refreshedInvoice = EntityQuery.use(delegator).from("Invoice").where("invoiceId", invoiceId).queryOne();
            String invoiceStatusId = refreshedInvoice != null ? refreshedInvoice.getString("statusId") : invoice.getString("statusId");

            Map<String, Object> result = ServiceUtil.returnSuccess("Loan payment applied to invoice successfully");
            result.put("loanId", loanId);
            result.put("paymentId", paymentId);
            result.put("invoiceId", invoiceId);
            result.put("paymentApplicationId", paymentApplicationId);
            result.put("amountApplied", amountApplied);
            result.put("paymentStatusId", paymentStatusId);
            result.put("invoiceStatusId", invoiceStatusId);
            return result;

        } catch (Exception e) {
            Debug.logError(e, "Error applying loan payment to invoice", MODULE);
            return ServiceUtil.returnError(e.getMessage());
        }
    }

    private static void transitionInvoiceStatus(LocalDispatcher dispatcher,
                                                String invoiceId,
                                                String statusId,
                                                GenericValue userLogin) throws Exception {
        if (UtilValidate.isEmpty(invoiceId) || UtilValidate.isEmpty(statusId)) {
            return;
        }
        Map<String, Object> statusCtx = new HashMap<String, Object>();
        statusCtx.put("invoiceId", invoiceId);
        statusCtx.put("statusId", statusId);
        statusCtx.put("userLogin", userLogin);

        Map<String, Object> statusResp = dispatcher.runSync("setInvoiceStatus", statusCtx);
        if (ServiceUtil.isError(statusResp)) {
            throw new Exception("setInvoiceStatus failed for invoiceId=" + invoiceId + ", statusId=" + statusId + ": "
                    + ServiceUtil.getErrorMessage(statusResp));
        }
    }


    private static void markInvoicePaidSafely(Delegator delegator,
                                              LocalDispatcher dispatcher,
                                              String invoiceId,
                                              String invoicePaidStatusId,
                                              GenericValue userLogin) throws Exception {
        if (UtilValidate.isEmpty(invoiceId)) {
            return;
        }
        if (UtilValidate.isEmpty(invoicePaidStatusId)) {
            invoicePaidStatusId = "INVOICE_PAID";
        }

        GenericValue invoice = EntityQuery.use(delegator).from("Invoice").where("invoiceId", invoiceId).queryOne();
        if (invoice == null) {
            return;
        }
        if (invoicePaidStatusId.equals(invoice.getString("statusId"))) {
            return;
        }

        try {
            transitionInvoiceStatus(dispatcher, invoiceId, invoicePaidStatusId, userLogin);
        } catch (Exception statusError) {
            // OFBiz invoice status transitions can be restrictive depending on current status.
            // This direct update is intentionally limited to fully-applied loan invoices only.
            invoice.refresh();
            invoice.set("statusId", invoicePaidStatusId);
            invoice.store();
            Debug.logWarning("Directly marked fully-applied loan invoice as paid after setInvoiceStatus failed. invoiceId="
                    + invoiceId + ", targetStatusId=" + invoicePaidStatusId + ", reason=" + statusError.getMessage(), MODULE);
        }
    }

    private static void tryRunPaymentApplicationAccountingHooks(LocalDispatcher dispatcher,
                                                             String paymentApplicationId,
                                                             String paymentId,
                                                             String invoiceId,
                                                             GenericValue userLogin) throws Exception {
        String[] candidateServices = new String[] {
                "createPaymentApplicationAcctgTrans",
                "createAcctgTransForPaymentApplication",
                "createAcctgTransAndEntriesForPaymentApplication"
        };

        Exception lastFailure = null;
        for (String serviceName : candidateServices) {
            try {
                Map<String, Object> ctx = new HashMap<String, Object>();
                if (UtilValidate.isNotEmpty(paymentApplicationId)) {
                    ctx.put("paymentApplicationId", paymentApplicationId);
                }
                if (UtilValidate.isNotEmpty(paymentId)) {
                    ctx.put("paymentId", paymentId);
                }
                if (UtilValidate.isNotEmpty(invoiceId)) {
                    ctx.put("invoiceId", invoiceId);
                }
                ctx.put("userLogin", userLogin);

                Map<String, Object> resp = dispatcher.runSync(serviceName, ctx);
                if (!ServiceUtil.isError(resp)) {
                    return;
                }
                lastFailure = new Exception(serviceName + " failed: " + ServiceUtil.getErrorMessage(resp));
            } catch (Exception e) {
                String msg = e.getMessage();
                if (msg != null && (msg.contains("Cannot locate service by name") || msg.contains("not found"))) {
                    lastFailure = e;
                    continue;
                }
                lastFailure = e;
                continue;
            }
        }

        if (lastFailure != null) {
            throw new Exception("No payment-application accounting hook succeeded for paymentApplicationId="
                    + paymentApplicationId + ", paymentId=" + paymentId + ", invoiceId=" + invoiceId
                    + ". Last error: " + lastFailure.getMessage());
        }
    }

    public static Map<String, Object> processLoanDisbursementFinanceV1(DispatchContext dctx, Map<String, ?> context) {
        LocalDispatcher dispatcher = dctx.getDispatcher();

        try {
            String loanId = (String) context.get("loanId");
            String paymentId = (String) context.get("paymentId");
            String organizationPartyId = (String) context.get("organizationPartyId");
            Timestamp invoiceDate = (Timestamp) context.get("invoiceDate");
            BigDecimal amountApplied = (BigDecimal) context.get("amountApplied");
            GenericValue userLogin = (GenericValue) context.get("userLogin");

            Map<String, Object> invoiceCtx = UtilMisc.<String, Object>toMap(
                    "loanId", loanId,
                    "paymentId", paymentId,
                    "organizationPartyId", organizationPartyId,
                    "invoiceDate", invoiceDate,
                    "userLogin", userLogin
            );

            Map<String, Object> invoiceResp = dispatcher.runSync("createLoanDisbursementInvoiceV1", invoiceCtx);
            if (ServiceUtil.isError(invoiceResp)) {
                return ServiceUtil.returnError(ServiceUtil.getErrorMessage(invoiceResp));
            }

            String invoiceId = (String) invoiceResp.get("invoiceId");

            Map<String, Object> applyCtx = UtilMisc.<String, Object>toMap(
                    "loanId", loanId,
                    "paymentId", paymentId,
                    "invoiceId", invoiceId,
                    "organizationPartyId", organizationPartyId,
                    "amountApplied", amountApplied,
                    "userLogin", userLogin
            );

            Map<String, Object> applyResp = dispatcher.runSync("applyLoanDisbursementPaymentToInvoiceV1", applyCtx);
            if (ServiceUtil.isError(applyResp)) {
                return ServiceUtil.returnError(ServiceUtil.getErrorMessage(applyResp));
            }

            Map<String, Object> result = ServiceUtil.returnSuccess("Loan disbursement finance processing completed successfully");
            result.put("loanId", loanId);
            result.put("paymentId", paymentId);
            result.put("invoiceId", invoiceId);
            result.put("paymentApplicationId", applyResp.get("paymentApplicationId"));
            result.put("amountApplied", applyResp.get("amountApplied"));
            return result;

        } catch (Exception e) {
            Debug.logError(e, "Error processing loan disbursement finance workflow", MODULE);
            return ServiceUtil.returnError(e.getMessage());
        }
    }

    private static void createRepaymentInvoiceItem(LocalDispatcher dispatcher,
                                                   GenericValue userLogin,
                                                   String invoiceId,
                                                   String invoiceItemTypeId,
                                                   String description,
                                                   BigDecimal amount) throws Exception {
        if (amount == null || amount.compareTo(BigDecimal.ZERO) <= 0) {
            return;
        }

        Map<String, Object> itemCtx = new HashMap<String, Object>();
        itemCtx.put("invoiceId", invoiceId);
        itemCtx.put("invoiceItemTypeId", invoiceItemTypeId);
        itemCtx.put("description", description);
        itemCtx.put("amount", amount);
        itemCtx.put("quantity", BigDecimal.ONE);
        itemCtx.put("userLogin", userLogin);

        Map<String, Object> itemResp = dispatcher.runSync("createInvoiceItem", itemCtx);
        if (ServiceUtil.isError(itemResp)) {
            throw new Exception("createInvoiceItem failed: " + ServiceUtil.getErrorMessage(itemResp));
        }
    }

    public static Map<String, Object> processLoanRepaymentFinanceV1(DispatchContext dctx, Map<String, ?> context) {
        LocalDispatcher dispatcher = dctx.getDispatcher();
        GenericValue userLogin = (GenericValue) context.get("userLogin");

        try {
            String loanId = (String) context.get("loanId");
            String organizationPartyId = (String) context.get("organizationPartyId");
            String loanScheduleId = (String) context.get("loanScheduleId");
            BigDecimal amountApplied = (BigDecimal) context.get("amountApplied");
            Timestamp repaymentDate = (Timestamp) context.get("repaymentDate");
            String repaymentSourceEnumId = (String) context.get("repaymentSourceEnumId");
            String remarks = (String) context.get("remarks");
            String paymentId = trimToNull((String) context.get("paymentId"));
            String createPaymentFlag = (String) context.get("createPaymentFlag");
            String paymentTypeId = (String) context.get("paymentTypeId");
            String paymentMethodTypeId = (String) context.get("paymentMethodTypeId");
            String paymentMethodId = (String) context.get("paymentMethodId");
            String payrollRunId = (String) context.get("payrollRunId");
            String payrollResultId = (String) context.get("payrollResultId");
            String finAccountId = (String) context.get("finAccountId");
            BigDecimal principalAmount = (BigDecimal) context.get("principalAmount");
            BigDecimal interestAmount = (BigDecimal) context.get("interestAmount");
            BigDecimal feeAmount = (BigDecimal) context.get("feeAmount");
String financeProcessingMode = normalizeFinanceProcessingMode((String) context.get("financeProcessingMode"));
            if (UtilValidate.isEmpty(loanId)) {
                return ServiceUtil.returnError("loanId is required");
            }
            if (UtilValidate.isEmpty(organizationPartyId)) {
                return ServiceUtil.returnError("organizationPartyId is required");
            }
            if (amountApplied == null || amountApplied.compareTo(BigDecimal.ZERO) <= 0) {
                return ServiceUtil.returnError("amountApplied must be greater than zero");
            }
            if (repaymentDate == null) {
                repaymentDate = UtilDateTime.nowTimestamp();
            }

            GenericValue loan = requireLoan(dctx.getDelegator(), loanId);
            String billingAccountId = trimToNull(loan.getString("billingAccountId"));

            Map<String, Object> repayCtx = new HashMap<String, Object>();
            repayCtx.put("loanId", loanId);
            repayCtx.put("loanScheduleId", loanScheduleId);
            repayCtx.put("amountApplied", amountApplied);
            repayCtx.put("repaymentDate", repaymentDate);
            repayCtx.put("repaymentSourceEnumId", repaymentSourceEnumId);
            repayCtx.put("remarks", remarks);
            repayCtx.put("organizationPartyId", organizationPartyId);
            repayCtx.put("paymentId", paymentId);
            repayCtx.put("createPaymentFlag", createPaymentFlag);
            repayCtx.put("paymentTypeId", paymentTypeId);
            repayCtx.put("paymentMethodTypeId", paymentMethodTypeId);
            repayCtx.put("paymentMethodId", paymentMethodId);
            repayCtx.put("payrollRunId", payrollRunId);
            repayCtx.put("payrollResultId", payrollResultId);
            repayCtx.put("finAccountId", finAccountId);
            repayCtx.put("billingAccountId", billingAccountId);
            repayCtx.put("financeProcessingMode", financeProcessingMode);
            repayCtx.put("userLogin", userLogin);

            Map<String, Object> repayResp = dispatcher.runSync("recordPartyLoanRepayment", repayCtx);
            if (ServiceUtil.isError(repayResp)) {
                return ServiceUtil.returnError(ServiceUtil.getErrorMessage(repayResp));
            }

            @SuppressWarnings("unchecked")
            Map<String, Object> repayData = (Map<String, Object>) repayResp.get("data");
            if (repayData != null && UtilValidate.isEmpty(paymentId)) {
                paymentId = trimToNull((String) repayData.get("paymentId"));
            }
            if (UtilValidate.isEmpty(paymentId)) {
                paymentId = trimToNull((String) repayResp.get("paymentId"));
            }

            BigDecimal totalApplied = amountApplied;
            if (repayData != null && repayData.get("totalApplied") instanceof BigDecimal) {
                totalApplied = (BigDecimal) repayData.get("totalApplied");
            }

            if (principalAmount == null && interestAmount == null && feeAmount == null) {
                principalAmount = totalApplied;
                interestAmount = BigDecimal.ZERO;
                feeAmount = BigDecimal.ZERO;
            } else {
                if (principalAmount == null) principalAmount = BigDecimal.ZERO;
                if (interestAmount == null) interestAmount = BigDecimal.ZERO;
                if (feeAmount == null) feeAmount = BigDecimal.ZERO;
            }

            Map<String, Object> invoiceCtx = new HashMap<String, Object>();
            invoiceCtx.put("loanId", loanId);
            invoiceCtx.put("organizationPartyId", organizationPartyId);
            invoiceCtx.put("invoiceDate", repaymentDate);
            invoiceCtx.put("principalAmount", principalAmount);
            invoiceCtx.put("interestAmount", interestAmount);
            invoiceCtx.put("feeAmount", feeAmount);
            invoiceCtx.put("userLogin", userLogin);

            Map<String, Object> invoiceResp = dispatcher.runSync("createLoanRepaymentInvoiceV1", invoiceCtx);
            if (ServiceUtil.isError(invoiceResp)) {
                return ServiceUtil.returnError(ServiceUtil.getErrorMessage(invoiceResp));
            }

            String invoiceId = (String) invoiceResp.get("invoiceId");

            Map<String, Object> applyCtx = new HashMap<String, Object>();
            applyCtx.put("loanId", loanId);
            applyCtx.put("paymentId", paymentId);
            applyCtx.put("invoiceId", invoiceId);
            applyCtx.put("organizationPartyId", organizationPartyId);
            applyCtx.put("amountApplied", totalApplied);
            applyCtx.put("userLogin", userLogin);

            Map<String, Object> applyResp = dispatcher.runSync("applyLoanRepaymentPaymentToInvoiceV1", applyCtx);
            if (ServiceUtil.isError(applyResp)) {
                return ServiceUtil.returnError(ServiceUtil.getErrorMessage(applyResp));
            }

            Map<String, Object> result = ServiceUtil.returnSuccess("Loan repayment finance processed successfully");
            result.put("loanId", loanId);
            result.put("loanScheduleId", repayData != null ? repayData.get("loanScheduleId") : loanScheduleId);
            result.put("repaymentRecordId", repayData != null ? repayData.get("repaymentRecordId") : null);
            result.put("paymentId", paymentId);
            result.put("invoiceId", invoiceId);
            result.put("billingAccountId", billingAccountId);
            result.put("amountApplied", totalApplied);
            result.put("principalAmount", principalAmount);
            result.put("interestAmount", interestAmount);
            result.put("feeAmount", feeAmount);
            result.put("loanStatusIdAfter", repayData != null ? repayData.get("loanStatusIdAfter") : null);
            result.put("outstandingAmountAfter", repayData != null ? repayData.get("outstandingAmountAfter") : null);
            result.put("financeProcessingMode", financeProcessingMode);
            return result;

        } catch (Exception e) {
            Debug.logError(e, "Error processing loan repayment finance", MODULE);
            return ServiceUtil.returnError(e.getMessage());
        }
    }

    public static Map<String, Object> closeLoanPaymentV1(DispatchContext dctx, Map<String, ?> context) {
        Delegator delegator = dctx.getDelegator();
        GenericValue userLogin = (GenericValue) context.get("userLogin");

        try {
            String paymentId = trimToNull((String) context.get("paymentId"));
            String targetStatusId = trimToNull((String) context.get("targetStatusId"));
            if (UtilValidate.isEmpty(paymentId)) {
                return ServiceUtil.returnError("paymentId is required");
            }
            if (UtilValidate.isEmpty(targetStatusId)) {
                targetStatusId = "PMNT_SENT";
            }

            GenericValue payment = EntityQuery.use(delegator).from("Payment").where("paymentId", paymentId).queryOne();
            if (payment == null) {
                return ServiceUtil.returnError("Payment not found: " + paymentId);
            }

            String currentStatusId = payment.getString("statusId");
            if (!targetStatusId.equals(currentStatusId)) {
                payment.set("statusId", targetStatusId);
                payment.store();

                try {
                    GenericValue paymentStatus = delegator.makeValue("PaymentStatus");
                    paymentStatus.set("paymentStatusId", delegator.getNextSeqId("PaymentStatus"));
                    paymentStatus.set("paymentId", paymentId);
                    paymentStatus.set("statusId", targetStatusId);
                    if (paymentStatus.getModelEntity().isField("statusDate")) {
                        paymentStatus.set("statusDate", UtilDateTime.nowTimestamp());
                    }
                    if (paymentStatus.getModelEntity().isField("changeByUserLoginId") && userLogin != null) {
                        paymentStatus.set("changeByUserLoginId", userLogin.getString("userLoginId"));
                    }
                    delegator.create(paymentStatus);
                } catch (Exception ignore) {
                }
            }

            Map<String, Object> result = ServiceUtil.returnSuccess("Loan payment closed successfully");
            result.put("paymentId", paymentId);
            result.put("statusId", targetStatusId);
            return result;

        } catch (Exception e) {
            Debug.logError(e, "Error closing loan payment", MODULE);
            return ServiceUtil.returnError(e.getMessage());
        }
    }

    public static Map<String, Object> closeLoanInvoiceV1(DispatchContext dctx, Map<String, ?> context) {
        Delegator delegator = dctx.getDelegator();
        GenericValue userLogin = (GenericValue) context.get("userLogin");

        try {
            String invoiceId = trimToNull((String) context.get("invoiceId"));
            String targetStatusId = trimToNull((String) context.get("targetStatusId"));
            if (UtilValidate.isEmpty(invoiceId)) {
                return ServiceUtil.returnError("invoiceId is required");
            }
            if (UtilValidate.isEmpty(targetStatusId)) {
                targetStatusId = "INVOICE_READY";
            }

            GenericValue invoice = EntityQuery.use(delegator).from("Invoice").where("invoiceId", invoiceId).queryOne();
            if (invoice == null) {
                return ServiceUtil.returnError("Invoice not found: " + invoiceId);
            }

            BigDecimal invoiceTotal = sumInvoiceTotal(delegator, invoiceId);
            BigDecimal totalApplied = sumPaymentApplications(delegator, invoiceId);
            if (totalApplied.compareTo(invoiceTotal) < 0) {
                return ServiceUtil.returnError("Invoice is not fully applied yet: " + invoiceId);
            }

            String currentStatusId = invoice.getString("statusId");
            if (!targetStatusId.equals(currentStatusId)) {
                invoice.set("statusId", targetStatusId);
                if (invoice.getModelEntity().isField("paidDate")) {
                    invoice.set("paidDate", UtilDateTime.nowTimestamp());
                }
                invoice.store();

                try {
                    GenericValue invoiceStatus = delegator.makeValue("InvoiceStatus");
                    invoiceStatus.set("invoiceStatusId", delegator.getNextSeqId("InvoiceStatus"));
                    invoiceStatus.set("invoiceId", invoiceId);
                    invoiceStatus.set("statusId", targetStatusId);
                    if (invoiceStatus.getModelEntity().isField("statusDate")) {
                        invoiceStatus.set("statusDate", UtilDateTime.nowTimestamp());
                    }
                    if (invoiceStatus.getModelEntity().isField("changeByUserLoginId") && userLogin != null) {
                        invoiceStatus.set("changeByUserLoginId", userLogin.getString("userLoginId"));
                    }
                    delegator.create(invoiceStatus);
                } catch (Exception ignore) {
                }
            }

            Map<String, Object> result = ServiceUtil.returnSuccess("Loan invoice closed successfully");
            result.put("invoiceId", invoiceId);
            result.put("statusId", targetStatusId);
            result.put("invoiceTotal", invoiceTotal);
            result.put("totalApplied", totalApplied);
            return result;

        } catch (Exception e) {
            Debug.logError(e, "Error closing loan invoice", MODULE);
            return ServiceUtil.returnError(e.getMessage());
        }
    }

    public static Map<String, Object> processLoanDisbursementEndToEndV1(DispatchContext dctx, Map<String, ?> context) {
        LocalDispatcher dispatcher = dctx.getDispatcher();
        GenericValue userLogin = (GenericValue) context.get("userLogin");

        try {
            String loanId = (String) context.get("loanId");
            String organizationPartyId = (String) context.get("organizationPartyId");
            Timestamp disbursementDate = (Timestamp) context.get("disbursementDate");
            String paymentMethodTypeId = (String) context.get("paymentMethodTypeId");
            String paymentMethodId = (String) context.get("paymentMethodId");
            BigDecimal amountApplied = (BigDecimal) context.get("amountApplied");
            String financeProcessingMode = normalizeFinanceProcessingMode((String) context.get("financeProcessingMode"));

            Map<String, Object> disbCtx = new HashMap<String, Object>();
            disbCtx.put("loanId", loanId);
            disbCtx.put("disbursementDate", disbursementDate);
            disbCtx.put("createPaymentFlag", "Y");
            disbCtx.put("paymentMethodTypeId", paymentMethodTypeId);
            disbCtx.put("paymentMethodId", paymentMethodId);
            disbCtx.put("userLogin", userLogin);

            Map<String, Object> disbResp = dispatcher.runSync("disbursePartyLoan", disbCtx);
            if (ServiceUtil.isError(disbResp)) {
                return ServiceUtil.returnError(ServiceUtil.getErrorMessage(disbResp));
            }

            @SuppressWarnings("unchecked")
            Map<String, Object> disbData = (Map<String, Object>) disbResp.get("data");
            String paymentId = disbData != null ? (String) disbData.get("paymentId") : (String) disbResp.get("paymentId");

            Map<String, Object> finCtx = new HashMap<String, Object>();
            finCtx.put("loanId", loanId);
            finCtx.put("paymentId", paymentId);
            finCtx.put("organizationPartyId", organizationPartyId);
            finCtx.put("amountApplied", amountApplied);
            finCtx.put("userLogin", userLogin);

            Map<String, Object> finResp = dispatcher.runSync("processLoanDisbursementFinanceV1", finCtx);
            if (ServiceUtil.isError(finResp)) {
                return ServiceUtil.returnError(ServiceUtil.getErrorMessage(finResp));
            }
            String invoiceId = (String) finResp.get("invoiceId");

            if (!LOAN_MANAGED.equals(financeProcessingMode)) {
                Map<String, Object> result = ServiceUtil.returnSuccess("Loan disbursement prepared successfully; continue finance processing in OFBiz and then finalize the loan");
                result.put("loanId", loanId);
                result.put("paymentId", paymentId);
                result.put("invoiceId", invoiceId);
                result.put("paymentApplicationId", finResp.get("paymentApplicationId"));
                result.put("loanStatusId", "LN_DISB_PENDING");
                result.put("financeProcessingMode", financeProcessingMode);
                return result;
            }

            String paymentTargetStatusId = trimToNull((String) context.get("paymentTargetStatusId"));
            String invoiceTargetStatusId = trimToNull((String) context.get("invoiceTargetStatusId"));

            Map<String, Object> closePayCtx = UtilMisc.<String, Object>toMap(
                    "paymentId", paymentId,
                    "targetStatusId", paymentTargetStatusId,
                    "userLogin", userLogin
            );
            Map<String, Object> closePayResp = dispatcher.runSync("closeLoanPaymentV1", closePayCtx);
            if (ServiceUtil.isError(closePayResp)) {
                return ServiceUtil.returnError(ServiceUtil.getErrorMessage(closePayResp));
            }

            Map<String, Object> acctgCtx = new HashMap<String, Object>();
            acctgCtx.put("loanId", loanId);
            acctgCtx.put("paymentId", paymentId);
            acctgCtx.put("organizationPartyId", organizationPartyId);
            acctgCtx.put("amount", amountApplied);
            acctgCtx.put("transactionDate", disbursementDate);
            acctgCtx.put("remarks", "Loan-managed end-to-end disbursement accounting");
            acctgCtx.put("paymentTypeId", "DISBURSEMENT");
            acctgCtx.put("userLogin", userLogin);

            Map<String, Object> acctgResp = dispatcher.runSync("createAcctgTransForLoanDisbursementV1", acctgCtx);
            if (ServiceUtil.isError(acctgResp)) {
                return ServiceUtil.returnError(ServiceUtil.getErrorMessage(acctgResp));
            }
            String acctgTransId = (String) acctgResp.get("acctgTransId");

            Map<String, Object> closeInvCtx = UtilMisc.<String, Object>toMap(
                    "invoiceId", invoiceId,
                    "targetStatusId", invoiceTargetStatusId,
                    "userLogin", userLogin
            );
            Map<String, Object> closeInvResp = dispatcher.runSync("closeLoanInvoiceV1", closeInvCtx);
            if (ServiceUtil.isError(closeInvResp)) {
                return ServiceUtil.returnError(ServiceUtil.getErrorMessage(closeInvResp));
            }

            Map<String, Object> finalizeCtx = new HashMap<String, Object>();
            finalizeCtx.put("loanId", loanId);
            finalizeCtx.put("organizationPartyId", organizationPartyId);
            finalizeCtx.put("paymentId", paymentId);
            finalizeCtx.put("acctgTransId", acctgTransId);
            finalizeCtx.put("userLogin", userLogin);

            Map<String, Object> finalResp = dispatcher.runSync("finalizeLoanDisbursementV1", finalizeCtx);
            if (ServiceUtil.isError(finalResp)) {
                return ServiceUtil.returnError(ServiceUtil.getErrorMessage(finalResp));
            }

            Map<String, Object> result = ServiceUtil.returnSuccess("Loan disbursement end-to-end processed successfully");
            result.put("loanId", loanId);
            result.put("paymentId", paymentId);
            result.put("invoiceId", invoiceId);
            result.put("paymentStatusId", closePayResp.get("statusId"));
            result.put("invoiceStatusId", closeInvResp.get("statusId"));
            result.put("paymentApplicationId", finResp.get("paymentApplicationId"));
            result.put("acctgTransId", acctgTransId != null ? acctgTransId : finalResp.get("acctgTransId"));
            result.put("loanStatusId", finalResp.get("loanStatusId"));
            result.put("financeProcessingMode", financeProcessingMode);
            return result;

        } catch (Exception e) {
            Debug.logError(e, "Error processing loan disbursement end-to-end", MODULE);
            return ServiceUtil.returnError(e.getMessage());
        }
    }

    public static Map<String, Object> processLoanRepaymentEndToEndV1(DispatchContext dctx, Map<String, ?> context) {
        LocalDispatcher dispatcher = dctx.getDispatcher();
        GenericValue userLogin = (GenericValue) context.get("userLogin");

        try {
            String financeProcessingMode = normalizeFinanceProcessingMode((String) context.get("financeProcessingMode"));

            Map<String, Object> financeCtx = new HashMap<String, Object>();
            for (Map.Entry<String, ?> entry : context.entrySet()) {
                financeCtx.put(entry.getKey(), entry.getValue());
            }
            financeCtx.put("financeProcessingMode", financeProcessingMode);
            financeCtx.put("userLogin", userLogin);

            Map<String, Object> financeResp = dispatcher.runSync("processLoanRepaymentFinanceV1", financeCtx);
            if (ServiceUtil.isError(financeResp)) {
                return ServiceUtil.returnError(ServiceUtil.getErrorMessage(financeResp));
            }

            if (!LOAN_MANAGED.equals(financeProcessingMode)) {
                Map<String, Object> result = ServiceUtil.returnSuccess("Loan repayment prepared successfully; continue finance processing in OFBiz if required");
                result.putAll(financeResp);
                result.put("financeProcessingMode", financeProcessingMode);
                return result;
            }

            String paymentId = (String) financeResp.get("paymentId");
            String invoiceId = (String) financeResp.get("invoiceId");
            String paymentTargetStatusId = trimToNull((String) context.get("paymentTargetStatusId"));
            String invoiceTargetStatusId = trimToNull((String) context.get("invoiceTargetStatusId"));

            Map<String, Object> closePayCtx = UtilMisc.<String, Object>toMap(
                    "paymentId", paymentId,
                    "targetStatusId", paymentTargetStatusId,
                    "userLogin", userLogin
            );
            Map<String, Object> closePayResp = dispatcher.runSync("closeLoanPaymentV1", closePayCtx);
            if (ServiceUtil.isError(closePayResp)) {
                return ServiceUtil.returnError(ServiceUtil.getErrorMessage(closePayResp));
            }

            Map<String, Object> closeInvCtx = UtilMisc.<String, Object>toMap(
                    "invoiceId", invoiceId,
                    "targetStatusId", invoiceTargetStatusId,
                    "userLogin", userLogin
            );
            Map<String, Object> closeInvResp = dispatcher.runSync("closeLoanInvoiceV1", closeInvCtx);
            if (ServiceUtil.isError(closeInvResp)) {
                return ServiceUtil.returnError(ServiceUtil.getErrorMessage(closeInvResp));
            }

            Map<String, Object> result = ServiceUtil.returnSuccess("Loan repayment end-to-end processed successfully");
            result.putAll(financeResp);
            result.put("paymentStatusId", closePayResp.get("statusId"));
            result.put("invoiceStatusId", closeInvResp.get("statusId"));
            result.put("financeProcessingMode", financeProcessingMode);
            return result;

        } catch (Exception e) {
            Debug.logError(e, "Error processing loan repayment end-to-end", MODULE);
            return ServiceUtil.returnError(e.getMessage());
        }
    }

    private static BigDecimal sumInvoiceTotal(Delegator delegator, String invoiceId) throws GenericEntityException {
        List<GenericValue> items = EntityQuery.use(delegator).from("InvoiceItem").where("invoiceId", invoiceId).queryList();
        BigDecimal total = BigDecimal.ZERO;
        if (items != null) {
            for (GenericValue item : items) {
                BigDecimal amount = item.getBigDecimal("amount");
                BigDecimal quantity = item.getBigDecimal("quantity");
                if (amount != null) {
                    total = total.add(quantity != null ? amount.multiply(quantity) : amount);
                }
            }
        }
        return total;
    }

    private static BigDecimal sumPaymentApplications(Delegator delegator, String invoiceId) throws GenericEntityException {
        List<GenericValue> apps = EntityQuery.use(delegator).from("PaymentApplication").where("invoiceId", invoiceId).queryList();
        BigDecimal total = BigDecimal.ZERO;
        if (apps != null) {
            for (GenericValue app : apps) {
                BigDecimal amt = app.getBigDecimal("amountApplied");
                if (amt != null) {
                    total = total.add(amt);
                }
            }
        }
        return total;
    }

    private static GenericValue requireLoan(Delegator delegator, String loanId) throws GenericEntityException {
        GenericValue loan = EntityQuery.use(delegator)
                .from("PartyLoan")
                .where("loanId", loanId)
                .queryOne();
        if (loan == null) {
            throw new GenericEntityException("Loan not found: " + loanId);
        }
        return loan;
    }

    private static void ensureLoanInvoiceTypeExists(Delegator delegator, String invoiceTypeId) throws GenericEntityException {
        GenericValue invoiceType = EntityQuery.use(delegator)
                .from("InvoiceType")
                .where("invoiceTypeId", invoiceTypeId)
                .queryOne();
        if (invoiceType == null) {
            throw new GenericEntityException("InvoiceType not found: " + invoiceTypeId + ". Load XML seed first.");
        }
    }

    private static void ensureLoanInvoiceItemTypeExists(Delegator delegator, String invoiceItemTypeId) throws GenericEntityException {
        GenericValue invoiceItemType = EntityQuery.use(delegator)
                .from("InvoiceItemType")
                .where("invoiceItemTypeId", invoiceItemTypeId)
                .queryOne();
        if (invoiceItemType == null) {
            throw new GenericEntityException("InvoiceItemType not found: " + invoiceItemTypeId + ". Load XML seed first.");
        }
    }

    private static String findExistingDisbursementInvoiceId(Delegator delegator,
                                                            String loanId,
                                                            String organizationPartyId,
                                                            String partyId) throws GenericEntityException {
        List<GenericValue> invoiceRows = EntityQuery.use(delegator)
                .from("Invoice")
                .where("invoiceTypeId", LOAN_DISBURSEMENT_INVOICE_TYPE_ID,
                        "partyIdFrom", organizationPartyId,
                        "partyId", partyId,
                        "referenceNumber", loanId)
                .orderBy("-invoiceDate", "-invoiceId")
                .queryList();

        return UtilValidate.isNotEmpty(invoiceRows) ? invoiceRows.get(0).getString("invoiceId") : null;
    }

    private static String buildRepaymentInvoiceReference(String loanId, String loanScheduleId) {
        if (UtilValidate.isEmpty(loanId)) {
            return null;
        }
        return UtilValidate.isNotEmpty(loanScheduleId) ? (loanId + "::" + loanScheduleId) : loanId;
    }

    private static String findExistingRepaymentInvoiceId(Delegator delegator,
                                                         String loanId,
                                                         String loanScheduleId,
                                                         String organizationPartyId,
                                                         String partyId) throws GenericEntityException {
        String scheduleReference = buildRepaymentInvoiceReference(loanId, loanScheduleId);
        List<GenericValue> invoiceRows = EntityQuery.use(delegator)
                .from("Invoice")
                .where("invoiceTypeId", LOAN_REPAYMENT_INVOICE_TYPE_ID,
                        "partyIdFrom", organizationPartyId,
                        "partyId", partyId)
                .orderBy("-invoiceDate", "-invoiceId")
                .queryList();

        if (UtilValidate.isEmpty(invoiceRows)) {
            return null;
        }

        for (GenericValue invoice : invoiceRows) {
            String referenceNumber = trimToNull(invoice.getString("referenceNumber"));
            if (scheduleReference != null && scheduleReference.equals(referenceNumber)) {
                return invoice.getString("invoiceId");
            }
            if (UtilValidate.isEmpty(loanScheduleId) && loanId.equals(referenceNumber)) {
                return invoice.getString("invoiceId");
            }
        }
        return null;
    }

    private static BigDecimal safeAmount(BigDecimal value) {
        return value != null ? value : BigDecimal.ZERO;
    }

    private static String normalizeFinanceProcessingMode(String value) {
        String normalized = trimToNull(value);
        if (normalized == null) {
            return FINANCE_MANAGED;
        }
        if (LOAN_MANAGED.equalsIgnoreCase(normalized)) {
            return LOAN_MANAGED;
        }
        return FINANCE_MANAGED;
    }

    private static String trimToNull(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }
}