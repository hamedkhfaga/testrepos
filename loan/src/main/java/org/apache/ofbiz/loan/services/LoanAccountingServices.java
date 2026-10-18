package org.apache.ofbiz.loan.services;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.apache.ofbiz.base.util.Debug;
import org.apache.ofbiz.base.util.UtilDateTime;
import org.apache.ofbiz.base.util.UtilValidate;
import org.apache.ofbiz.entity.Delegator;
import org.apache.ofbiz.entity.GenericEntityException;
import org.apache.ofbiz.entity.GenericValue;
import org.apache.ofbiz.entity.util.EntityQuery;
import org.apache.ofbiz.service.DispatchContext;
import org.apache.ofbiz.service.LocalDispatcher;
import org.apache.ofbiz.service.ServiceUtil;

public final class LoanAccountingServices {
    public static final String MODULE = LoanAccountingServices.class.getName();
    private static final String DEFAULT_ACCTG_TRANS_TYPE_ID = "PAYMENT_ACCTG_TRANS";
    private static final String DEFAULT_FIN_TRANS_TYPE_ID = "DEPOSIT";
    private static final String DEFAULT_GL_FISCAL_TYPE_ID = "ACTUAL";
    private static final String DEFAULT_DISBURSEMENT_PAYMENT_TYPE_ID = "DISBURSEMENT";
    private static final String DEFAULT_DISBURSEMENT_ACCTG_TRANS_TYPE_ID = "OUTGOING_PAYMENT";

    private LoanAccountingServices() {}

    public static Map<String, Object> getActiveLoanAcctgCfg(DispatchContext dctx, Map<String, ?> context) {
        Delegator delegator = dctx.getDelegator();
        try {
            String organizationPartyId = (String) context.get("organizationPartyId");
            String repaymentSourceEnumId = (String) context.get("repaymentSourceEnumId");
            LoanAcctgConfig cfg = resolveLoanAcctgConfig(delegator, organizationPartyId, repaymentSourceEnumId, UtilDateTime.nowTimestamp());
            Map<String, Object> result = ServiceUtil.returnSuccess();
            result.put("loanAcctgCfgId", cfg.loanAcctgCfgId);
            result.put("acctgTransTypeId", cfg.acctgTransTypeId);
            result.put("glFiscalTypeId", cfg.glFiscalTypeId);
            result.put("debitGlAccountId", cfg.debitGlAccountId);
            result.put("creditGlAccountId", cfg.creditGlAccountId);
            result.put("finAccountTransTypeId", cfg.finAccountTransTypeId);
            return result;
        } catch (Exception e) {
            Debug.logError(e, "Error loading active loan accounting config", MODULE);
            return ServiceUtil.returnError(e.getMessage());
        }
    }

    public static Map<String, Object> createAcctgTransForLoanRepayment(DispatchContext dctx, Map<String, ?> context) {
        Delegator delegator = dctx.getDelegator();
        try {
            String paymentId = (String) context.get("paymentId");
            String organizationPartyId = (String) context.get("organizationPartyId");
            BigDecimal amount = (BigDecimal) context.get("amount");
            Timestamp repaymentDate = (Timestamp) context.get("repaymentDate");
            String repaymentSourceEnumId = (String) context.get("repaymentSourceEnumId");
            String loanId = (String) context.get("loanId");
            String remarks = (String) context.get("remarks");

            if (UtilValidate.isEmpty(paymentId)) {
                return ServiceUtil.returnError("paymentId is required");
            }
            if (UtilValidate.isEmpty(organizationPartyId)) {
                return ServiceUtil.returnError("organizationPartyId is required");
            }
            if (amount == null || amount.compareTo(BigDecimal.ZERO) <= 0) {
                return ServiceUtil.returnError("amount must be greater than zero");
            }
            if (repaymentDate == null) {
                repaymentDate = UtilDateTime.nowTimestamp();
            }
            if (UtilValidate.isEmpty(repaymentSourceEnumId)) {
                repaymentSourceEnumId = "LNRS_MANUAL";
            }

            GenericValue payment = EntityQuery.use(delegator).from("Payment").where("paymentId", paymentId).queryOne();
            if (payment == null) {
                return ServiceUtil.returnError("Payment not found: " + paymentId);
            }

            List<GenericValue> existingAcctg = EntityQuery.use(delegator)
                    .from("AcctgTrans")
                    .where("paymentId", paymentId)
                    .orderBy("acctgTransId")
                    .queryList();
            if (UtilValidate.isNotEmpty(existingAcctg)) {
                String existingAcctgTransId = existingAcctg.get(0).getString("acctgTransId");
                return ServiceUtil.returnError("Duplicate accounting is blocked for paymentId=" + paymentId
                        + "; existing AcctgTrans already present: " + existingAcctgTransId);
            }

            String currencyUomId = payment.getString("currencyUomId");
            if (UtilValidate.isEmpty(currencyUomId)) {
                currencyUomId = "USD";
            }

            LoanAcctgConfig cfg = resolveLoanAcctgConfig(delegator, organizationPartyId, repaymentSourceEnumId, repaymentDate);

            String acctgTransId = delegator.getNextSeqId("AcctgTrans");
            String description = buildAcctgDescription(loanId, paymentId, repaymentSourceEnumId, remarks);

            GenericValue acctgTrans = delegator.makeValue("AcctgTrans");
            acctgTrans.set("acctgTransId", acctgTransId);
            acctgTrans.set("acctgTransTypeId", firstNonEmpty(cfg.acctgTransTypeId, DEFAULT_ACCTG_TRANS_TYPE_ID));
            acctgTrans.set("glFiscalTypeId", firstNonEmpty(cfg.glFiscalTypeId, DEFAULT_GL_FISCAL_TYPE_ID));
            acctgTrans.set("partyId", organizationPartyId);
            acctgTrans.set("paymentId", paymentId);
            acctgTrans.set("transactionDate", repaymentDate);
            acctgTrans.set("description", description);
            acctgTrans.set("isPosted", "Y");
            delegator.create(acctgTrans);

            GenericValue debit = delegator.makeValue("AcctgTransEntry");
            debit.set("acctgTransId", acctgTransId);
            debit.set("acctgTransEntrySeqId", "00001");
            debit.set("description", description);
            debit.set("partyId", organizationPartyId);
            debit.set("glAccountId", cfg.debitGlAccountId);
            debit.set("debitCreditFlag", "D");
            debit.set("organizationPartyId", organizationPartyId);
            debit.set("amount", amount);
            debit.set("currencyUomId", currencyUomId);
            debit.set("origAmount", amount);
            debit.set("origCurrencyUomId", currencyUomId);
            delegator.create(debit);

            GenericValue credit = delegator.makeValue("AcctgTransEntry");
            credit.set("acctgTransId", acctgTransId);
            credit.set("acctgTransEntrySeqId", "00002");
            credit.set("description", description);
            credit.set("partyId", organizationPartyId);
            credit.set("glAccountId", cfg.creditGlAccountId);
            credit.set("debitCreditFlag", "C");
            credit.set("organizationPartyId", organizationPartyId);
            credit.set("amount", amount);
            credit.set("currencyUomId", currencyUomId);
            credit.set("origAmount", amount);
            credit.set("origCurrencyUomId", currencyUomId);
            delegator.create(credit);

            Map<String, Object> result = ServiceUtil.returnSuccess();
            result.put("paymentId", paymentId);
            result.put("acctgTransId", acctgTransId);
            return result;
        } catch (Exception e) {
            Debug.logError(e, "Error creating loan repayment accounting transaction", MODULE);
            return ServiceUtil.returnError(e.getMessage());
        }
    }


    public static Map<String, Object> getActiveLoanDisbursementAcctgCfg(DispatchContext dctx, Map<String, ?> context) {
        Delegator delegator = dctx.getDelegator();
        try {
            String organizationPartyId = (String) context.get("organizationPartyId");
            String paymentTypeId = (String) context.get("paymentTypeId");
            if (UtilValidate.isEmpty(paymentTypeId)) {
                paymentTypeId = DEFAULT_DISBURSEMENT_PAYMENT_TYPE_ID;
            }

            LoanDisbursementAcctgConfig cfg = resolveLoanDisbursementAcctgConfig(
                    delegator,
                    organizationPartyId,
                    paymentTypeId,
                    UtilDateTime.nowTimestamp());

            Map<String, Object> result = ServiceUtil.returnSuccess();
            result.put("paymentTypeId", paymentTypeId);
            result.put("acctgTransTypeId", cfg.acctgTransTypeId);
            result.put("glFiscalTypeId", cfg.glFiscalTypeId);
            result.put("debitGlAccountId", cfg.debitGlAccountId);
            result.put("creditGlAccountId", cfg.creditGlAccountId);
            result.put("creditSourceType", cfg.creditSourceType);
            return result;
        } catch (Exception e) {
            Debug.logError(e, "Error loading loan disbursement accounting config", MODULE);
            return ServiceUtil.returnError(e.getMessage());
        }
    }

    public static Map<String, Object> createAcctgTransForLoanDisbursementV1(DispatchContext dctx, Map<String, ?> context) {
        Delegator delegator = dctx.getDelegator();
        try {
            String loanId = (String) context.get("loanId");
            String paymentId = (String) context.get("paymentId");
            String organizationPartyId = (String) context.get("organizationPartyId");
            BigDecimal amount = (BigDecimal) context.get("amount");
            Timestamp transactionDate = (Timestamp) context.get("transactionDate");
            String remarks = (String) context.get("remarks");
            String paymentTypeId = (String) context.get("paymentTypeId");

            if (UtilValidate.isEmpty(loanId)) {
                return ServiceUtil.returnError("loanId is required");
            }
            if (UtilValidate.isEmpty(paymentId)) {
                return ServiceUtil.returnError("paymentId is required");
            }
            if (UtilValidate.isEmpty(organizationPartyId)) {
                return ServiceUtil.returnError("organizationPartyId is required");
            }
            if (transactionDate == null) {
                transactionDate = UtilDateTime.nowTimestamp();
            }
            if (UtilValidate.isEmpty(paymentTypeId)) {
                paymentTypeId = DEFAULT_DISBURSEMENT_PAYMENT_TYPE_ID;
            }

            GenericValue loan = EntityQuery.use(delegator).from("PartyLoan").where("loanId", loanId).queryOne();
            if (loan == null) {
                return ServiceUtil.returnError("Loan not found: " + loanId);
            }

            GenericValue payment = EntityQuery.use(delegator).from("Payment").where("paymentId", paymentId).queryOne();
            if (payment == null) {
                return ServiceUtil.returnError("Payment not found: " + paymentId);
            }

            if (amount == null || amount.compareTo(BigDecimal.ZERO) <= 0) {
                amount = payment.getBigDecimal("amount");
            }
            if (amount == null || amount.compareTo(BigDecimal.ZERO) <= 0) {
                return ServiceUtil.returnError("amount must be greater than zero");
            }

            List<GenericValue> existingAcctg = EntityQuery.use(delegator)
                    .from("AcctgTrans")
                    .where("paymentId", paymentId)
                    .orderBy("acctgTransId")
                    .queryList();
            if (UtilValidate.isNotEmpty(existingAcctg)) {
                Map<String, Object> result = ServiceUtil.returnSuccess();
                result.put("paymentId", paymentId);
                result.put("acctgTransId", existingAcctg.get(0).getString("acctgTransId"));
                result.put("duplicateSkipped", "Y");
                return result;
            }

            String currencyUomId = payment.getString("currencyUomId");
            if (UtilValidate.isEmpty(currencyUomId)) {
                currencyUomId = "USD";
            }

            LoanDisbursementAcctgConfig cfg = resolveLoanDisbursementAcctgConfig(
                    delegator,
                    organizationPartyId,
                    paymentTypeId,
                    transactionDate);

            String resolvedCreditGlAccountId = cfg.creditGlAccountId;
            if (UtilValidate.isEmpty(resolvedCreditGlAccountId)) {
                resolvedCreditGlAccountId = resolveCreditGlAccountIdForDisbursement(delegator, payment, organizationPartyId);
            }
            if (UtilValidate.isEmpty(resolvedCreditGlAccountId)) {
                return ServiceUtil.returnError("Unable to resolve credit GL account for loan disbursement paymentId=" + paymentId);
            }

            String acctgTransId = delegator.getNextSeqId("AcctgTrans");
            String description = buildDisbursementAcctgDescription(loanId, paymentId, remarks);

            GenericValue acctgTrans = delegator.makeValue("AcctgTrans");
            acctgTrans.set("acctgTransId", acctgTransId);
            acctgTrans.set("acctgTransTypeId", firstNonEmpty(cfg.acctgTransTypeId, DEFAULT_DISBURSEMENT_ACCTG_TRANS_TYPE_ID));
            acctgTrans.set("glFiscalTypeId", firstNonEmpty(cfg.glFiscalTypeId, DEFAULT_GL_FISCAL_TYPE_ID));
            acctgTrans.set("partyId", organizationPartyId);
            acctgTrans.set("paymentId", paymentId);
            acctgTrans.set("transactionDate", transactionDate);
            acctgTrans.set("description", description);
            acctgTrans.set("isPosted", "N");
            delegator.create(acctgTrans);

            GenericValue credit = delegator.makeValue("AcctgTransEntry");
            credit.set("acctgTransId", acctgTransId);
            credit.set("acctgTransEntrySeqId", "00001");
            credit.set("description", description);
            credit.set("partyId", organizationPartyId);
            credit.set("glAccountId", resolvedCreditGlAccountId);
            credit.set("debitCreditFlag", "C");
            credit.set("organizationPartyId", organizationPartyId);
            credit.set("amount", amount);
            credit.set("currencyUomId", currencyUomId);
            credit.set("origAmount", amount);
            credit.set("origCurrencyUomId", currencyUomId);
            delegator.create(credit);

            GenericValue debit = delegator.makeValue("AcctgTransEntry");
            debit.set("acctgTransId", acctgTransId);
            debit.set("acctgTransEntrySeqId", "00002");
            debit.set("description", description);
            debit.set("partyId", organizationPartyId);
            debit.set("glAccountId", cfg.debitGlAccountId);
            debit.set("debitCreditFlag", "D");
            debit.set("organizationPartyId", organizationPartyId);
            debit.set("amount", amount);
            debit.set("currencyUomId", currencyUomId);
            debit.set("origAmount", amount);
            debit.set("origCurrencyUomId", currencyUomId);
            delegator.create(debit);

            Map<String, Object> result = ServiceUtil.returnSuccess();
            result.put("loanId", loanId);
            result.put("paymentId", paymentId);
            result.put("acctgTransId", acctgTransId);
            result.put("debitGlAccountId", cfg.debitGlAccountId);
            result.put("creditGlAccountId", resolvedCreditGlAccountId);
            return result;
        } catch (Exception e) {
            Debug.logError(e, "Error creating loan disbursement accounting transaction", MODULE);
            return ServiceUtil.returnError(e.getMessage());
        }
    }

    public static Map<String, Object> createFinAccountTransForLoanRepayment(DispatchContext dctx, Map<String, ?> context) {
        LocalDispatcher dispatcher = dctx.getDispatcher();
        GenericValue userLogin = (GenericValue) context.get("userLogin");
        Delegator delegator = dctx.getDelegator();
        try {
            String finAccountId = (String) context.get("finAccountId");
            String paymentId = (String) context.get("paymentId");
            BigDecimal amount = (BigDecimal) context.get("amount");
            Timestamp repaymentDate = (Timestamp) context.get("repaymentDate");
            String organizationPartyId = (String) context.get("organizationPartyId");
            String repaymentSourceEnumId = (String) context.get("repaymentSourceEnumId");
            if (UtilValidate.isEmpty(finAccountId)) {
                return ServiceUtil.returnError("finAccountId is required");
            }
            if (UtilValidate.isEmpty(paymentId)) {
                return ServiceUtil.returnError("paymentId is required");
            }
            if (amount == null || amount.compareTo(BigDecimal.ZERO) <= 0) {
                return ServiceUtil.returnError("amount must be greater than zero");
            }
            if (repaymentDate == null) {
                repaymentDate = UtilDateTime.nowTimestamp();
            }
            if (UtilValidate.isEmpty(repaymentSourceEnumId)) {
                repaymentSourceEnumId = "LNRS_MANUAL";
            }

            List<GenericValue> existingFin = EntityQuery.use(delegator)
                    .from("FinAccountTrans")
                    .where("paymentId", paymentId, "finAccountId", finAccountId)
                    .orderBy("finAccountTransId")
                    .queryList();
            if (UtilValidate.isNotEmpty(existingFin)) {
                Map<String, Object> result = ServiceUtil.returnSuccess();
                result.put("paymentId", paymentId);
                result.put("finAccountId", finAccountId);
                result.put("finAccountTransId", existingFin.get(0).getString("finAccountTransId"));
                result.put("duplicateSkipped", "Y");
                return result;
            }

            LoanAcctgConfig cfg = resolveLoanAcctgConfig(delegator, organizationPartyId, repaymentSourceEnumId, repaymentDate);

            Map<String, Object> finCtx = new HashMap<String, Object>();
            finCtx.put("finAccountId", finAccountId);
            finCtx.put("finAccountTransTypeId", firstNonEmpty(cfg.finAccountTransTypeId, DEFAULT_FIN_TRANS_TYPE_ID));
            finCtx.put("amount", amount);
            finCtx.put("paymentId", paymentId);
            finCtx.put("transactionDate", repaymentDate);
            finCtx.put("comments", "Loan repayment paymentId=" + paymentId);
            finCtx.put("userLogin", userLogin);

            Map<String, Object> finResp = dispatcher.runSync("createFinAccountTrans", finCtx);
            if (ServiceUtil.isError(finResp)) {
                return ServiceUtil.returnError(ServiceUtil.getErrorMessage(finResp));
            }

            Map<String, Object> result = ServiceUtil.returnSuccess();
            result.put("paymentId", paymentId);
            result.put("finAccountId", finAccountId);
            result.put("finAccountTransId", finResp.get("finAccountTransId"));
            return result;
        } catch (Exception e) {
            Debug.logError(e, "Error creating loan repayment fin account transaction", MODULE);
            return ServiceUtil.returnError(e.getMessage());
        }
    }

    private static LoanAcctgConfig resolveLoanAcctgConfig(Delegator delegator, String organizationPartyId, String repaymentSourceEnumId, Timestamp whenTs)
            throws GenericEntityException {
        if (UtilValidate.isEmpty(organizationPartyId)) {
            throw new GenericEntityException("organizationPartyId is required to resolve loan accounting config");
        }
        if (UtilValidate.isEmpty(repaymentSourceEnumId)) {
            throw new GenericEntityException("repaymentSourceEnumId is required to resolve loan accounting config");
        }
        if (whenTs == null) {
            whenTs = UtilDateTime.nowTimestamp();
        }

        GenericValue header = EntityQuery.use(delegator)
                .from("LoanAcctgCfg")
                .where("organizationPartyId", organizationPartyId, "isActive", "Y")
                .orderBy("-fromDate")
                .queryFirst();
        if (header == null) {
            throw new GenericEntityException("No active LoanAcctgCfg found for organizationPartyId=" + organizationPartyId);
        }

        Timestamp fromDate = header.getTimestamp("fromDate");
        Timestamp thruDate = header.getTimestamp("thruDate");
        if (fromDate != null && fromDate.after(whenTs)) {
            throw new GenericEntityException("LoanAcctgCfg is not yet active for organizationPartyId=" + organizationPartyId);
        }
        if (thruDate != null && !thruDate.after(whenTs)) {
            throw new GenericEntityException("LoanAcctgCfg is expired for organizationPartyId=" + organizationPartyId);
        }

        GenericValue detail = EntityQuery.use(delegator)
                .from("LoanAcctgCfgDtl")
                .where("loanAcctgCfgId", header.getString("loanAcctgCfgId"), "repaymentSourceEnumId", repaymentSourceEnumId)
                .queryOne();
        if (detail == null) {
            throw new GenericEntityException("No LoanAcctgCfgDtl found for loanAcctgCfgId=" + header.getString("loanAcctgCfgId")
                    + " and repaymentSourceEnumId=" + repaymentSourceEnumId);
        }

        requireGlAccount(delegator, detail.getString("debitGlAccountId"), organizationPartyId, "debitGlAccountId");
        requireGlAccount(delegator, detail.getString("creditGlAccountId"), organizationPartyId, "creditGlAccountId");

        LoanAcctgConfig cfg = new LoanAcctgConfig();
        cfg.loanAcctgCfgId = header.getString("loanAcctgCfgId");
        cfg.acctgTransTypeId = detail.getString("acctgTransTypeId");
        cfg.glFiscalTypeId = detail.getString("glFiscalTypeId");
        cfg.debitGlAccountId = detail.getString("debitGlAccountId");
        cfg.creditGlAccountId = detail.getString("creditGlAccountId");
        cfg.finAccountTransTypeId = detail.getString("finAccountTransTypeId");
        return cfg;
    }

    private static void requireGlAccount(Delegator delegator, String glAccountId, String organizationPartyId, String fieldName) throws GenericEntityException {
        if (UtilValidate.isEmpty(glAccountId)) {
            throw new GenericEntityException(fieldName + " is empty in LoanAcctgCfgDtl");
        }
        GenericValue gl = EntityQuery.use(delegator).from("GlAccount").where("glAccountId", glAccountId).queryOne();
        if (gl == null) {
            throw new GenericEntityException(fieldName + " not found in GlAccount: " + glAccountId);
        }
        GenericValue orgLink = EntityQuery.use(delegator)
                .from("GlAccountOrganization")
                .where("glAccountId", glAccountId, "organizationPartyId", organizationPartyId)
                .filterByDate()
                .queryFirst();
        if (orgLink == null) {
            throw new GenericEntityException(fieldName + " is not active for organizationPartyId=" + organizationPartyId + ": " + glAccountId);
        }
    }


    private static LoanDisbursementAcctgConfig resolveLoanDisbursementAcctgConfig(Delegator delegator,
                                                                                  String organizationPartyId,
                                                                                  String paymentTypeId,
                                                                                  Timestamp whenTs)
            throws GenericEntityException {
        if (UtilValidate.isEmpty(organizationPartyId)) {
            throw new GenericEntityException("organizationPartyId is required to resolve loan disbursement accounting config");
        }
        if (whenTs == null) {
            whenTs = UtilDateTime.nowTimestamp();
        }
        if (UtilValidate.isEmpty(paymentTypeId)) {
            paymentTypeId = DEFAULT_DISBURSEMENT_PAYMENT_TYPE_ID;
        }

        GenericValue header = EntityQuery.use(delegator)
                .from("LoanAcctgCfg")
                .where("organizationPartyId", organizationPartyId, "isActive", "Y")
                .orderBy("-fromDate")
                .queryFirst();
        if (header == null) {
            throw new GenericEntityException("No active LoanAcctgCfg found for organizationPartyId=" + organizationPartyId);
        }

        Timestamp fromDate = header.getTimestamp("fromDate");
        Timestamp thruDate = header.getTimestamp("thruDate");
        if (fromDate != null && fromDate.after(whenTs)) {
            throw new GenericEntityException("LoanAcctgCfg is not yet active for organizationPartyId=" + organizationPartyId);
        }
        if (thruDate != null && !thruDate.after(whenTs)) {
            throw new GenericEntityException("LoanAcctgCfg is expired for organizationPartyId=" + organizationPartyId);
        }

        GenericValue detail = EntityQuery.use(delegator)
                .from("LoanAcctgCfgDtl")
                .where("loanAcctgCfgId", header.getString("loanAcctgCfgId"),
                       "repaymentSourceEnumId", "LNACFG_DISBURSEMENT")
                .queryOne();
        if (detail == null) {
            throw new GenericEntityException("No LoanAcctgCfgDtl found for loanAcctgCfgId="
                    + header.getString("loanAcctgCfgId")
                    + " and repaymentSourceEnumId=LNACFG_DISBURSEMENT");
        }

        LoanDisbursementAcctgConfig cfg = new LoanDisbursementAcctgConfig();
        cfg.paymentTypeId = paymentTypeId;
        cfg.acctgTransTypeId = firstNonEmpty(detail.getString("acctgTransTypeId"), DEFAULT_DISBURSEMENT_ACCTG_TRANS_TYPE_ID);
        cfg.glFiscalTypeId = firstNonEmpty(detail.getString("glFiscalTypeId"), DEFAULT_GL_FISCAL_TYPE_ID);
        cfg.debitGlAccountId = detail.getString("debitGlAccountId");
        cfg.creditGlAccountId = detail.getString("creditGlAccountId");
        cfg.creditSourceType = UtilValidate.isNotEmpty(cfg.creditGlAccountId) ? "CFG_FIXED" : "PAYMENT_METHOD";

        requireGlAccount(delegator, cfg.debitGlAccountId, organizationPartyId, "debitGlAccountId");
        if (UtilValidate.isNotEmpty(cfg.creditGlAccountId)) {
            requireGlAccount(delegator, cfg.creditGlAccountId, organizationPartyId, "creditGlAccountId");
        }

        return cfg;
    }

    private static String resolveCreditGlAccountIdFromExistingOutgoingPayments(Delegator delegator,
                                                                               String organizationPartyId)
            throws GenericEntityException {
        List<GenericValue> rows = EntityQuery.use(delegator)
                .from("AcctgTransEntry")
                .where("organizationPartyId", organizationPartyId, "debitCreditFlag", "C")
                .orderBy("-acctgTransId", "acctgTransEntrySeqId")
                .queryList();

        for (GenericValue row : rows) {
            String glAccountId = row.getString("glAccountId");
            if (UtilValidate.isNotEmpty(glAccountId)) {
                return glAccountId;
            }
        }
        return null;
    }

    private static String resolveCreditGlAccountIdForDisbursement(Delegator delegator,
                                                                  GenericValue payment,
                                                                  String organizationPartyId)
            throws GenericEntityException {
        if (payment == null) {
            return null;
        }

        String paymentMethodId = payment.getString("paymentMethodId");
        String paymentMethodTypeId = payment.getString("paymentMethodTypeId");

        if (UtilValidate.isNotEmpty(paymentMethodId)) {
            GenericValue finAccount = EntityQuery.use(delegator)
                    .from("FinAccount")
                    .where("finAccountId", paymentMethodId)
                    .queryOne();
            if (finAccount != null) {
                String glAccountId = finAccount.getString("glAccountId");
                if (UtilValidate.isNotEmpty(glAccountId)) {
                    requireGlAccount(delegator, glAccountId, organizationPartyId, "creditGlAccountId");
                    return glAccountId;
                }
            }
        }

        String glAccountId = resolveCreditGlAccountIdFromExistingOutgoingPayments(delegator, organizationPartyId);
        if (UtilValidate.isNotEmpty(glAccountId)) {
            requireGlAccount(delegator, glAccountId, organizationPartyId, "creditGlAccountId");
            return glAccountId;
        }

        throw new GenericEntityException("Unable to resolve credit GL account from payment method/accounting setup"
                + " for paymentMethodId=" + paymentMethodId
                + ", paymentMethodTypeId=" + paymentMethodTypeId
                + ", organizationPartyId=" + organizationPartyId);
    }

    private static String buildDisbursementAcctgDescription(String loanId, String paymentId, String remarks) {
        StringBuilder sb = new StringBuilder();
        sb.append("Loan disbursement");
        if (UtilValidate.isNotEmpty(loanId)) {
            sb.append(" loanId=").append(loanId);
        }
        if (UtilValidate.isNotEmpty(paymentId)) {
            sb.append(" paymentId=").append(paymentId);
        }
        if (UtilValidate.isNotEmpty(remarks)) {
            sb.append(" - ").append(remarks);
        }
        return sb.toString();
    }

    private static String firstNonEmpty(String primary, String fallback) {
        return UtilValidate.isNotEmpty(primary) ? primary : fallback;
    }

    private static String buildAcctgDescription(String loanId, String paymentId, String repaymentSourceEnumId, String remarks) {
        StringBuilder sb = new StringBuilder();
        sb.append("Loan repayment");
        if (UtilValidate.isNotEmpty(loanId)) {
            sb.append(" loanId=").append(loanId);
        }
        if (UtilValidate.isNotEmpty(paymentId)) {
            sb.append(" paymentId=").append(paymentId);
        }
        if (UtilValidate.isNotEmpty(repaymentSourceEnumId)) {
            sb.append(" source=").append(repaymentSourceEnumId);
        }
        if (UtilValidate.isNotEmpty(remarks)) {
            sb.append(" - ").append(remarks);
        }
        return sb.toString();
    }


    private static final class LoanDisbursementAcctgConfig {
        String paymentTypeId;
        String acctgTransTypeId;
        String glFiscalTypeId;
        String debitGlAccountId;
        String creditGlAccountId;
        String creditSourceType;
    }

    private static final class LoanAcctgConfig {
        String loanAcctgCfgId;
        String acctgTransTypeId;
        String glFiscalTypeId;
        String debitGlAccountId;
        String creditGlAccountId;
        String finAccountTransTypeId;
    }
}
