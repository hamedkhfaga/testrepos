package org.apache.ofbiz.loan;

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

import java.sql.Timestamp;
import java.util.List;
import java.util.Map;

public class LoanDisbursementServices {

    public static final String module = LoanDisbursementServices.class.getName();
    private static final String LOAN_DISBURSEMENT_INVOICE_TYPE_ID = "LOAN_DISBURSEMENT";

    public static Map<String, Object> completeLoanDisbursementFromFinance(DispatchContext dctx, Map<String, ? extends Object> context) {
        Delegator delegator = dctx.getDelegator();
        LocalDispatcher dispatcher = dctx.getDispatcher();

        String loanId = (String) context.get("loanId");
        String organizationPartyId = (String) context.get("organizationPartyId");
        String paymentId = (String) context.get("paymentId");
        String acctgTransId = (String) context.get("acctgTransId");
        Timestamp disbursementDate = (Timestamp) context.get("disbursementDate");
        String disbursementMethodTypeId = (String) context.get("disbursementMethodTypeId");
        String disbursementReference = (String) context.get("disbursementReference");
        String eventNotes = (String) context.get("eventNotes");
        GenericValue userLogin = (GenericValue) context.get("userLogin");

        if (UtilValidate.isEmpty(disbursementDate)) {
            disbursementDate = UtilDateTime.nowTimestamp();
        }

        try {
            GenericValue loan = EntityQuery.use(delegator)
                    .from("PartyLoan")
                    .where("loanId", loanId)
                    .queryOne();

            if (loan == null) {
                return ServiceUtil.returnError("PartyLoan not found for loanId: " + loanId);
            }

            String loanOrgId = loan.getString("organizationPartyId");
            if (UtilValidate.isNotEmpty(organizationPartyId) && !organizationPartyId.equals(loanOrgId)) {
                return ServiceUtil.returnError("organizationPartyId mismatch for loanId: " + loanId);
            }

            String currentLoanStatusId = loan.getString("loanStatusId");
            if (!"LN_APPROVED".equals(currentLoanStatusId)
                    && !"LN_DISB_PENDING".equals(currentLoanStatusId)
                    && !"LN_ACTIVE".equals(currentLoanStatusId)) {
                return ServiceUtil.returnError("Loan status does not allow disbursement completion: " + currentLoanStatusId);
            }

            if ("LN_ACTIVE".equals(currentLoanStatusId)) {
                Map<String, Object> result = ServiceUtil.returnSuccess("Loan disbursement already completed");
                result.put("loanId", loanId);
                result.put("loanStatusId", currentLoanStatusId);
                result.put("paymentId", paymentId);
                result.put("acctgTransId", acctgTransId);
                result.put("disbursementDate", loan.getTimestamp("disbursementDate"));
                return result;
            }

            if (UtilValidate.isEmpty(paymentId)) {
                return ServiceUtil.returnError("paymentId is required");
            }

            GenericValue payment = EntityQuery.use(delegator)
                    .from("Payment")
                    .where("paymentId", paymentId)
                    .queryOne();

            if (payment == null) {
                return ServiceUtil.returnError("Payment not found: " + paymentId);
            }

            String paymentStatusId = payment.getString("statusId");
            Debug.logInfo("completeLoanDisbursementFromFinance paymentId=" + paymentId
                    + ", paymentStatusId=" + paymentStatusId, module);

            if (UtilValidate.isNotEmpty(acctgTransId)) {
                GenericValue acctgTrans = EntityQuery.use(delegator)
                        .from("AcctgTrans")
                        .where("acctgTransId", acctgTransId)
                        .queryOne();

                if (acctgTrans == null) {
                    return ServiceUtil.returnError("AcctgTrans not found: " + acctgTransId);
                }

                String isPosted = acctgTrans.getString("isPosted");
                if ("N".equals(isPosted)) {
                    return ServiceUtil.returnError("AcctgTrans is not posted: " + acctgTransId);
                }
            }

            String disbursementInvoiceId = findRelatedDisbursementInvoiceId(
                    delegator,
                    loanId,
                    loanOrgId,
                    loan.getString("partyId")
            );
            if (UtilValidate.isNotEmpty(disbursementInvoiceId)) {
                GenericValue paymentApplication = EntityQuery.use(delegator)
                        .from("PaymentApplication")
                        .where("paymentId", paymentId, "invoiceId", disbursementInvoiceId)
                        .queryFirst();

                if (paymentApplication == null) {
                    return ServiceUtil.returnError("Disbursement invoice exists but payment is not applied yet. "
                            + "paymentId=" + paymentId + ", invoiceId=" + disbursementInvoiceId);
                }
            }

            String oldLoanStatusId = currentLoanStatusId;
            loan.set("loanStatusId", "LN_ACTIVE");

            if (hasField(loan, "disbursementPaymentId")) {
                loan.set("disbursementPaymentId", paymentId);
            }
            if (hasField(loan, "disbursementAcctgTransId")) {
                loan.set("disbursementAcctgTransId", acctgTransId);
            }
            if (hasField(loan, "disbursementDate")) {
                loan.set("disbursementDate", disbursementDate);
            }
            if (hasField(loan, "activatedDate")) {
                loan.set("activatedDate", disbursementDate);
            }
            if (hasField(loan, "disbursementMethodTypeId") && UtilValidate.isNotEmpty(disbursementMethodTypeId)) {
                loan.set("disbursementMethodTypeId", disbursementMethodTypeId);
            }
            if (hasField(loan, "disbursementReference") && UtilValidate.isNotEmpty(disbursementReference)) {
                loan.set("disbursementReference", disbursementReference);
            }

            loan.store();

            try {
                String notes = UtilValidate.isNotEmpty(eventNotes)
                        ? eventNotes
                        : "Loan activated after finance completion. paymentId=" + paymentId
                        + (UtilValidate.isNotEmpty(acctgTransId) ? ", acctgTransId=" + acctgTransId : "")
                        + (UtilValidate.isNotEmpty(disbursementInvoiceId) ? ", invoiceId=" + disbursementInvoiceId : "")
                        + (UtilValidate.isNotEmpty(paymentStatusId) ? ", paymentStatusId=" + paymentStatusId : "");

                dispatcher.runSync("createPartyLoanEvent", UtilMisc.toMap(
                        "loanId", loanId,
                        "loanEventTypeId", "LNE_DISBURSE_COMPLETE",
                        "eventDate", disbursementDate,
                        "eventNotes", notes,
                        "userLogin", userLogin
                ));
            } catch (Exception e) {
                Debug.logWarning(e, "Loan activated but createPartyLoanEvent failed for loanId: " + loanId, module);
            }

            Map<String, Object> result = ServiceUtil.returnSuccess("Loan disbursement completed successfully");
            result.put("loanId", loanId);
            result.put("loanStatusId", "LN_ACTIVE");
            result.put("oldLoanStatusId", oldLoanStatusId);
            result.put("paymentId", paymentId);
            result.put("acctgTransId", acctgTransId);
            result.put("disbursementDate", disbursementDate);
            result.put("invoiceId", disbursementInvoiceId);
            return result;

        } catch (GenericEntityException e) {
            Debug.logError(e, "Entity error completing loan disbursement for loanId: " + loanId, module);
            return ServiceUtil.returnError("Entity error completing loan disbursement: " + e.getMessage());
        } catch (Exception e) {
            Debug.logError(e, "Unexpected error completing loan disbursement for loanId: " + loanId, module);
            return ServiceUtil.returnError("Unexpected error completing loan disbursement: " + e.getMessage());
        }
    }

    private static String findRelatedDisbursementInvoiceId(Delegator delegator,
                                                           String loanId,
                                                           String organizationPartyId,
                                                           String partyId) throws GenericEntityException {
        if (UtilValidate.isEmpty(loanId) || UtilValidate.isEmpty(organizationPartyId) || UtilValidate.isEmpty(partyId)) {
            return null;
        }

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

    private static boolean hasField(GenericValue value, String fieldName) {
        return value != null
                && value.getModelEntity() != null
                && value.getModelEntity().isField(fieldName);
    }
}
