package org.apache.ofbiz.loan.services;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.apache.ofbiz.base.util.Debug;
import org.apache.ofbiz.base.util.UtilValidate;
import org.apache.ofbiz.entity.Delegator;
import org.apache.ofbiz.entity.GenericEntityException;
import org.apache.ofbiz.entity.GenericValue;
import org.apache.ofbiz.entity.condition.EntityCondition;
import org.apache.ofbiz.entity.condition.EntityOperator;
import org.apache.ofbiz.entity.util.EntityQuery;
import org.apache.ofbiz.service.DispatchContext;
import org.apache.ofbiz.service.ServiceUtil;

public final class LoanReconciliationServices {
    public static final String MODULE = LoanReconciliationServices.class.getName();
    private static final String SOURCE_PAYROLL = "LNRS_PAYROLL";

    private LoanReconciliationServices() {}

    public static Map<String, Object> reconcileLoanAccountingV1(DispatchContext dctx, Map<String, ?> context) {
        Delegator delegator = dctx.getDelegator();

        String loanId = trimToNull((String) context.get("loanId"));
        String organizationPartyId = trimToNull((String) context.get("organizationPartyId"));
        String strictDuplicateAcctg = normalizeFlag((String) context.get("strictDuplicateAcctg"));
        String requireFinAccountTrans = normalizeFlag((String) context.get("requireFinAccountTrans"));

        try {
            if (UtilValidate.isEmpty(loanId) && UtilValidate.isEmpty(organizationPartyId)) {
                return ServiceUtil.returnError("Either loanId or organizationPartyId is required");
            }

            List<GenericValue> loans = findTargetLoans(delegator, loanId, organizationPartyId);
            if (UtilValidate.isEmpty(loans)) {
                return ServiceUtil.returnError("No PartyLoan rows matched the requested filters");
            }

            List<Map<String, Object>> issues = new ArrayList<Map<String, Object>>();
            int checkedLoanCount = 0;
            int checkedScheduleCount = 0;
            int checkedRepaymentCount = 0;
            int checkedPaymentCount = 0;
            int checkedAcctgTransCount = 0;
            int errorCount = 0;
            int warningCount = 0;
            int infoCount = 0;

            for (GenericValue loan : loans) {
                checkedLoanCount++;

                String currentLoanId = loan.getString("loanId");
                String currentOrgId = loan.getString("organizationPartyId");
                String loanCurrencyUomId = trimToNull(loan.getString("currencyUomId"));
                String loanFinAccountId = trimToNull(loan.getString("finAccountId"));

                List<GenericValue> schedules = EntityQuery.use(delegator)
                        .from("PartyLoanSchedule")
                        .where("loanId", currentLoanId)
                        .orderBy("installmentSeqNum", "loanScheduleId")
                        .queryList();

                List<GenericValue> repayments = EntityQuery.use(delegator)
                        .from("PartyLoanRepayment")
                        .where("loanId", currentLoanId)
                        .orderBy("repaymentDate", "loanRepaymentId")
                        .queryList();

                checkedScheduleCount += schedules.size();
                checkedRepaymentCount += repayments.size();

                BigDecimal totalSchedulePaid = BigDecimal.ZERO;
                BigDecimal totalScheduleOutstanding = BigDecimal.ZERO;
                BigDecimal totalRepaymentApplied = BigDecimal.ZERO;

                Map<String, BigDecimal> repaymentSumByScheduleId = new HashMap<String, BigDecimal>();
                Map<String, PaymentCheckResult> paymentCheckCache = new HashMap<String, PaymentCheckResult>();
                Set<String> checkedRepaymentInvoiceIds = new HashSet<String>();

                for (GenericValue schedule : schedules) {
                    String loanScheduleId = schedule.getString("loanScheduleId");

                    BigDecimal principalAmount = safeAmount(schedule.getBigDecimal("principalAmount"));
                    BigDecimal interestAmount = safeAmount(schedule.getBigDecimal("interestAmount"));
                    BigDecimal feeAmount = safeAmount(schedule.getBigDecimal("feeAmount"));
                    BigDecimal penaltyAmount = safeAmount(schedule.getBigDecimal("penaltyAmount"));
                    BigDecimal paidAmount = safeAmount(schedule.getBigDecimal("paidAmount"));
                    BigDecimal outstandingAmount = safeAmount(schedule.getBigDecimal("outstandingAmount"));
                    BigDecimal installmentAmount = safeAmount(schedule.getBigDecimal("installmentAmount"));
                    BigDecimal derivedInstallmentAmount = principalAmount.add(interestAmount).add(feeAmount).add(penaltyAmount);
                    BigDecimal derivedRemaining = derivedInstallmentAmount.subtract(paidAmount);
                    if (derivedRemaining.compareTo(BigDecimal.ZERO) < 0) {
                        derivedRemaining = BigDecimal.ZERO;
                    }

                    totalSchedulePaid = totalSchedulePaid.add(paidAmount);
                    totalScheduleOutstanding = totalScheduleOutstanding.add(outstandingAmount);

                    if (installmentAmount.compareTo(BigDecimal.ZERO) > 0 && installmentAmount.compareTo(derivedInstallmentAmount) != 0) {
                        issues.add(buildIssue("ERROR", "SCHEDULE_INSTALLMENT_MISMATCH",
                                currentOrgId, currentLoanId, loanScheduleId, null, null, null,
                                installmentAmount, derivedInstallmentAmount, loanCurrencyUomId,
                                "PartyLoanSchedule.installmentAmount does not equal principal+interest+fee+penalty"));
                        errorCount++;
                    }

                    if (paidAmount.compareTo(BigDecimal.ZERO) < 0 || outstandingAmount.compareTo(BigDecimal.ZERO) < 0) {
                        issues.add(buildIssue("ERROR", "SCHEDULE_NEGATIVE_AMOUNT",
                                currentOrgId, currentLoanId, loanScheduleId, null, null, null,
                                BigDecimal.ZERO, paidAmount.min(outstandingAmount), loanCurrencyUomId,
                                "Schedule row contains a negative paid/outstanding value"));
                        errorCount++;
                    }

                    if (outstandingAmount.compareTo(derivedRemaining) != 0) {
                        issues.add(buildIssue("ERROR", "SCHEDULE_BALANCE_MISMATCH",
                                currentOrgId, currentLoanId, loanScheduleId, null, null, null,
                                derivedRemaining, outstandingAmount, loanCurrencyUomId,
                                "Schedule outstandingAmount does not reconcile with installment and paid amounts"));
                        errorCount++;
                    }

                    String scheduleStatusId = trimToNull(schedule.getString("scheduleStatusId"));
                    if ("LNS_PAID".equals(scheduleStatusId) && outstandingAmount.compareTo(BigDecimal.ZERO) != 0) {
                        issues.add(buildIssue("ERROR", "SCHEDULE_STATUS_CONFLICT",
                                currentOrgId, currentLoanId, loanScheduleId, null, null, null,
                                BigDecimal.ZERO, outstandingAmount, loanCurrencyUomId,
                                "Schedule status is LNS_PAID but outstandingAmount is not zero"));
                        errorCount++;
                    } else if ("LNS_PARTIAL".equals(scheduleStatusId) && paidAmount.compareTo(BigDecimal.ZERO) <= 0) {
                        issues.add(buildIssue("WARN", "SCHEDULE_STATUS_CONFLICT",
                                currentOrgId, currentLoanId, loanScheduleId, null, null, null,
                                null, paidAmount, loanCurrencyUomId,
                                "Schedule status is LNS_PARTIAL but paidAmount is zero"));
                        warningCount++;
                    }
                }

                for (GenericValue repayment : repayments) {
                    String loanRepaymentId = repayment.getString("loanRepaymentId");
                    String loanScheduleId = trimToNull(repayment.getString("loanScheduleId"));
                    String paymentId = trimToNull(repayment.getString("paymentId"));
                    String repaymentSourceEnumId = trimToNull(repayment.getString("repaymentSourceEnumId"));
                    BigDecimal amountApplied = safeAmount(repayment.getBigDecimal("amountApplied"));
                    BigDecimal componentTotal = safeAmount(repayment.getBigDecimal("principalApplied"))
                            .add(safeAmount(repayment.getBigDecimal("interestApplied")))
                            .add(safeAmount(repayment.getBigDecimal("feeApplied")))
                            .add(safeAmount(repayment.getBigDecimal("penaltyApplied")));

                    totalRepaymentApplied = totalRepaymentApplied.add(amountApplied);

                    if (componentTotal.compareTo(amountApplied) != 0) {
                        issues.add(buildIssue("ERROR", "REPAYMENT_COMPONENT_MISMATCH",
                                currentOrgId, currentLoanId, loanScheduleId, loanRepaymentId, paymentId, null,
                                amountApplied, componentTotal, loanCurrencyUomId,
                                "Repayment amountApplied does not equal principal+interest+fee+penalty applied"));
                        errorCount++;
                    }

                    if (UtilValidate.isNotEmpty(loanScheduleId)) {
                        BigDecimal running = repaymentSumByScheduleId.containsKey(loanScheduleId)
                                ? repaymentSumByScheduleId.get(loanScheduleId) : BigDecimal.ZERO;
                        repaymentSumByScheduleId.put(loanScheduleId, running.add(amountApplied));
                    }

                    if (UtilValidate.isEmpty(paymentId)) {
                        String severity = SOURCE_PAYROLL.equals(repaymentSourceEnumId) ? "ERROR" : "WARN";
                        String message = SOURCE_PAYROLL.equals(repaymentSourceEnumId)
                                ? "Payroll repayment has no paymentId; accounting trace is incomplete"
                                : "Repayment has no paymentId";
                        issues.add(buildIssue(severity, "REPAYMENT_MISSING_PAYMENT",
                                currentOrgId, currentLoanId, loanScheduleId, loanRepaymentId, null, null,
                                amountApplied, null, loanCurrencyUomId, message));
                        if ("ERROR".equals(severity)) {
                            errorCount++;
                        } else {
                            warningCount++;
                        }
                        continue;
                    }

                    PaymentCheckResult paymentCheck = paymentCheckCache.get(paymentId);
                    if (paymentCheck == null) {
                        paymentCheck = inspectPaymentAndAccounting(delegator, loan, paymentId, currentOrgId, loanCurrencyUomId, loanFinAccountId);
                        paymentCheckCache.put(paymentId, paymentCheck);
                        checkedPaymentCount++;
                        checkedAcctgTransCount += paymentCheck.acctgTransCount;
                    }

                    if (!paymentCheck.paymentExists) {
                        issues.add(buildIssue("ERROR", "PAYMENT_NOT_FOUND",
                                currentOrgId, currentLoanId, loanScheduleId, loanRepaymentId, paymentId, null,
                                amountApplied, null, loanCurrencyUomId,
                                "Payment row not found for repayment paymentId"));
                        errorCount++;
                        continue;
                    }

                    if (paymentCheck.paymentAmount.compareTo(amountApplied) != 0) {
                        issues.add(buildIssue("ERROR", "PAYMENT_AMOUNT_MISMATCH",
                                currentOrgId, currentLoanId, loanScheduleId, loanRepaymentId, paymentId, null,
                                amountApplied, paymentCheck.paymentAmount, paymentCheck.paymentCurrencyUomId,
                                "Payment.amount does not match PartyLoanRepayment.amountApplied"));
                        errorCount++;
                    }

                    if (UtilValidate.isNotEmpty(loanCurrencyUomId)
                            && UtilValidate.isNotEmpty(paymentCheck.paymentCurrencyUomId)
                            && !loanCurrencyUomId.equals(paymentCheck.paymentCurrencyUomId)) {
                        issues.add(buildIssue("ERROR", "PAYMENT_CURRENCY_MISMATCH",
                                currentOrgId, currentLoanId, loanScheduleId, loanRepaymentId, paymentId, null,
                                null, null, paymentCheck.paymentCurrencyUomId,
                                "Payment currencyUomId does not match PartyLoan.currencyUomId"));
                        errorCount++;
                    }

                    if (!paymentCheck.hasAcctgTrans) {
                        issues.add(buildIssue("ERROR", "ACCTG_TRANS_MISSING",
                                currentOrgId, currentLoanId, loanScheduleId, loanRepaymentId, paymentId, null,
                                amountApplied, null, loanCurrencyUomId,
                                "No AcctgTrans rows found for paymentId"));
                        errorCount++;
                    } else {
                        if (!paymentCheck.hasPaymentAcctgTrans
                                || !paymentCheck.hasIncomingPaymentTrans
                                || !paymentCheck.hasPaymentApplicationTrans) {
                            issues.add(buildIssue("ERROR", "ACCTG_3TXN_MODEL_INCOMPLETE",
                                    currentOrgId, currentLoanId, loanScheduleId, loanRepaymentId, paymentId, paymentCheck.firstAcctgTransId,
                                    new BigDecimal(3), new BigDecimal(paymentCheck.expectedAcctgTypeCount), loanCurrencyUomId,
                                    "Repayment payment must preserve the accepted 3-transaction model: PAYMENT_ACCTG_TRANS + INCOMING_PAYMENT + PAYMENT_APPL"));
                            errorCount++;
                        }

                        if (paymentCheck.duplicateAcctgTypeCount > 0) {
                            String severity = "Y".equals(strictDuplicateAcctg) ? "ERROR" : "WARN";
                            issues.add(buildIssue(severity, "ACCTG_TRANS_DUPLICATE_TYPE",
                                    currentOrgId, currentLoanId, loanScheduleId, loanRepaymentId, paymentId, paymentCheck.firstAcctgTransId,
                                    BigDecimal.ZERO, new BigDecimal(paymentCheck.duplicateAcctgTypeCount), loanCurrencyUomId,
                                    "Duplicate AcctgTrans rows exist for at least one expected repayment accounting type"));
                            if ("ERROR".equals(severity)) {
                                errorCount++;
                            } else {
                                warningCount++;
                            }
                        }

                        if (paymentCheck.unexpectedAcctgTransCount > 0) {
                            issues.add(buildIssue("WARN", "ACCTG_TRANS_UNEXPECTED_TYPE",
                                    currentOrgId, currentLoanId, loanScheduleId, loanRepaymentId, paymentId, paymentCheck.firstAcctgTransId,
                                    BigDecimal.ZERO, new BigDecimal(paymentCheck.unexpectedAcctgTransCount), loanCurrencyUomId,
                                    "Additional AcctgTrans rows exist for the payment outside the expected repayment 3-transaction model"));
                            warningCount++;
                        }

                        if (paymentCheck.unbalancedAcctgTransCount > 0) {
                            issues.add(buildIssue("ERROR", "ACCTG_ENTRY_UNBALANCED",
                                    currentOrgId, currentLoanId, loanScheduleId, loanRepaymentId, paymentId, paymentCheck.firstAcctgTransId,
                                    BigDecimal.ZERO, new BigDecimal(paymentCheck.unbalancedAcctgTransCount), paymentCheck.paymentCurrencyUomId,
                                    "One or more AcctgTrans rows are not balanced individually (debit != credit)"));
                            errorCount++;
                        }

                        if (paymentCheck.acctgAmountMismatchCount > 0) {
                            issues.add(buildIssue("ERROR", "ACCTG_ENTRY_AMOUNT_MISMATCH",
                                    currentOrgId, currentLoanId, loanScheduleId, loanRepaymentId, paymentId, paymentCheck.firstAcctgTransId,
                                    paymentCheck.paymentAmount, new BigDecimal(paymentCheck.acctgAmountMismatchCount), paymentCheck.paymentCurrencyUomId,
                                    "One or more expected repayment AcctgTrans rows does not balance to the Payment.amount"));
                            errorCount++;
                        }

                        if (paymentCheck.paymentApplicationCount <= 0) {
                            issues.add(buildIssue("ERROR", "PAYMENT_APPLICATION_MISSING",
                                    currentOrgId, currentLoanId, loanScheduleId, loanRepaymentId, paymentId, null,
                                    amountApplied, BigDecimal.ZERO, loanCurrencyUomId,
                                    "Repayment payment has no PaymentApplication row"));
                            errorCount++;
                        } else if (paymentCheck.totalPaymentApplied.compareTo(amountApplied) != 0) {
                            issues.add(buildIssue("ERROR", "PAYMENT_APPLICATION_AMOUNT_MISMATCH",
                                    currentOrgId, currentLoanId, loanScheduleId, loanRepaymentId, paymentId, null,
                                    amountApplied, paymentCheck.totalPaymentApplied, loanCurrencyUomId,
                                    "Summed PaymentApplication.amountApplied does not match PartyLoanRepayment.amountApplied"));
                            errorCount++;
                        }

                        List<Map<String, Object>> invoiceIssues = inspectFullyAppliedRepaymentInvoiceStatus(
                                delegator, paymentId, currentOrgId, currentLoanId, loanScheduleId, loanRepaymentId,
                                loanCurrencyUomId, checkedRepaymentInvoiceIds);
                        if (UtilValidate.isNotEmpty(invoiceIssues)) {
                            issues.addAll(invoiceIssues);
                            warningCount += invoiceIssues.size();
                        }
                    }

                    if ("Y".equals(requireFinAccountTrans) && UtilValidate.isNotEmpty(loanFinAccountId) && paymentCheck.finAccountTransCount <= 0) {
                        issues.add(buildIssue("ERROR", "FIN_ACCOUNT_TRANS_MISSING",
                                currentOrgId, currentLoanId, loanScheduleId, loanRepaymentId, paymentId, null,
                                amountApplied, null, loanCurrencyUomId,
                                "Loan has finAccountId but no FinAccountTrans exists for paymentId"));
                        errorCount++;
                    }
                }

                for (GenericValue schedule : schedules) {
                    String loanScheduleId = schedule.getString("loanScheduleId");
                    BigDecimal schedulePaid = safeAmount(schedule.getBigDecimal("paidAmount"));
                    BigDecimal repaymentApplied = repaymentSumByScheduleId.containsKey(loanScheduleId)
                            ? repaymentSumByScheduleId.get(loanScheduleId) : BigDecimal.ZERO;

                    if (schedulePaid.compareTo(repaymentApplied) != 0) {
                        issues.add(buildIssue("ERROR", "SCHEDULE_REPAYMENT_MISMATCH",
                                currentOrgId, currentLoanId, loanScheduleId, null, null, null,
                                schedulePaid, repaymentApplied, loanCurrencyUomId,
                                "PartyLoanSchedule.paidAmount does not equal summed PartyLoanRepayment.amountApplied for the row"));
                        errorCount++;
                    }
                }

                BigDecimal loanPaidAmount = safeAmount(loan.getBigDecimal("paidAmount"));
                BigDecimal loanOutstandingAmount = safeAmount(loan.getBigDecimal("outstandingAmount"));
                BigDecimal totalRepayableAmount = safeAmount(loan.getBigDecimal("totalRepayableAmount"));

                if (loanPaidAmount.compareTo(totalRepaymentApplied) != 0) {
                    issues.add(buildIssue("ERROR", "LOAN_PAID_TOTAL_MISMATCH",
                            currentOrgId, currentLoanId, null, null, null, null,
                            loanPaidAmount, totalRepaymentApplied, loanCurrencyUomId,
                            "PartyLoan.paidAmount does not equal summed PartyLoanRepayment.amountApplied"));
                    errorCount++;
                }

                if (loanPaidAmount.compareTo(totalSchedulePaid) != 0) {
                    issues.add(buildIssue("ERROR", "LOAN_SCHEDULE_PAID_MISMATCH",
                            currentOrgId, currentLoanId, null, null, null, null,
                            loanPaidAmount, totalSchedulePaid, loanCurrencyUomId,
                            "PartyLoan.paidAmount does not equal summed PartyLoanSchedule.paidAmount"));
                    errorCount++;
                }

                BigDecimal expectedLoanOutstanding = totalRepayableAmount.subtract(loanPaidAmount);
                if (expectedLoanOutstanding.compareTo(BigDecimal.ZERO) < 0) {
                    expectedLoanOutstanding = BigDecimal.ZERO;
                }

                if (loanOutstandingAmount.compareTo(expectedLoanOutstanding) != 0) {
                    issues.add(buildIssue("ERROR", "LOAN_OUTSTANDING_MISMATCH",
                            currentOrgId, currentLoanId, null, null, null, null,
                            expectedLoanOutstanding, loanOutstandingAmount, loanCurrencyUomId,
                            "PartyLoan.outstandingAmount does not reconcile with totalRepayableAmount - paidAmount"));
                    errorCount++;
                }

                if (loanOutstandingAmount.compareTo(totalScheduleOutstanding) != 0) {
                    issues.add(buildIssue("ERROR", "LOAN_SCHEDULE_OUTSTANDING_MISMATCH",
                            currentOrgId, currentLoanId, null, null, null, null,
                            loanOutstandingAmount, totalScheduleOutstanding, loanCurrencyUomId,
                            "PartyLoan.outstandingAmount does not equal summed PartyLoanSchedule.outstandingAmount"));
                    errorCount++;
                }

                infoCount++;
            }

            Map<String, Object> payload = new LinkedHashMap<String, Object>();
            payload.put("loanId", loanId);
            payload.put("organizationPartyId", organizationPartyId);
            payload.put("strictDuplicateAcctg", strictDuplicateAcctg);
            payload.put("requireFinAccountTrans", requireFinAccountTrans);
            payload.put("checkedLoanCount", Integer.valueOf(checkedLoanCount));
            payload.put("checkedScheduleCount", Integer.valueOf(checkedScheduleCount));
            payload.put("checkedRepaymentCount", Integer.valueOf(checkedRepaymentCount));
            payload.put("checkedPaymentCount", Integer.valueOf(checkedPaymentCount));
            payload.put("checkedAcctgTransCount", Integer.valueOf(checkedAcctgTransCount));
            payload.put("errorCount", Integer.valueOf(errorCount));
            payload.put("warningCount", Integer.valueOf(warningCount));
            payload.put("infoCount", Integer.valueOf(infoCount));
            payload.put("issueCount", Integer.valueOf(issues.size()));
            payload.put("issues", issues);

            Map<String, Object> result = ServiceUtil.returnSuccess("Loan reconciliation completed successfully");
            result.putAll(payload);
            result.put("data", new LinkedHashMap<String, Object>(payload));
            result.put("legacyData", new LinkedHashMap<String, Object>(payload));
            return result;
        } catch (Exception e) {
            Debug.logError(e, "Error reconciling loan accounting", MODULE);
            return ServiceUtil.returnError(e.getMessage());
        }
    }

    private static List<GenericValue> findTargetLoans(Delegator delegator, String loanId, String organizationPartyId)
            throws GenericEntityException {
        List<EntityCondition> conditions = new ArrayList<EntityCondition>();
        if (UtilValidate.isNotEmpty(loanId)) {
            conditions.add(EntityCondition.makeCondition("loanId", loanId));
        }
        if (UtilValidate.isNotEmpty(organizationPartyId)) {
            conditions.add(EntityCondition.makeCondition("organizationPartyId", organizationPartyId));
        }

        EntityQuery query = EntityQuery.use(delegator).from("PartyLoan");
        if (!conditions.isEmpty()) {
            query = query.where(EntityCondition.makeCondition(conditions, EntityOperator.AND));
        }
        return query.orderBy("loanId").queryList();
    }

    private static PaymentCheckResult inspectPaymentAndAccounting(
            Delegator delegator,
            GenericValue loan,
            String paymentId,
            String organizationPartyId,
            String defaultCurrencyUomId,
            String finAccountId) throws GenericEntityException {

        PaymentCheckResult result = new PaymentCheckResult();
        result.paymentId = paymentId;
        result.paymentAmount = BigDecimal.ZERO;
        result.paymentCurrencyUomId = defaultCurrencyUomId;
        result.acctgTransCount = 0;
        result.expectedAcctgTypeCount = 0;
        result.duplicateAcctgTypeCount = 0;
        result.unexpectedAcctgTransCount = 0;
        result.unbalancedAcctgTransCount = 0;
        result.acctgAmountMismatchCount = 0;
        result.paymentApplicationCount = 0;
        result.totalPaymentApplied = BigDecimal.ZERO;
        result.finAccountTransCount = 0;

        GenericValue payment = EntityQuery.use(delegator)
                .from("Payment")
                .where("paymentId", paymentId)
                .queryOne();

        if (payment == null) {
            result.paymentExists = false;
            return result;
        }

        result.paymentExists = true;
        result.paymentAmount = safeAmount(payment.getBigDecimal("amount"));
        result.paymentCurrencyUomId = trimToNull(payment.getString("currencyUomId"));
        if (UtilValidate.isEmpty(result.paymentCurrencyUomId) && loan != null) {
            result.paymentCurrencyUomId = trimToNull(loan.getString("currencyUomId"));
        }

        List<GenericValue> applications = EntityQuery.use(delegator)
                .from("PaymentApplication")
                .where("paymentId", paymentId)
                .queryList();
        result.paymentApplicationCount = applications.size();
        for (GenericValue application : applications) {
            result.totalPaymentApplied = result.totalPaymentApplied.add(safeAmount(application.getBigDecimal("amountApplied")));
        }

        List<GenericValue> acctgTransRows = EntityQuery.use(delegator)
                .from("AcctgTrans")
                .where("paymentId", paymentId)
                .orderBy("acctgTransId")
                .queryList();

        result.hasAcctgTrans = !acctgTransRows.isEmpty();
        result.acctgTransCount = acctgTransRows.size();
        if (!acctgTransRows.isEmpty()) {
            result.firstAcctgTransId = acctgTransRows.get(0).getString("acctgTransId");
        }

        Map<String, Integer> typeCounts = new HashMap<String, Integer>();

        for (GenericValue acctgTrans : acctgTransRows) {
            String acctgTransId = acctgTrans.getString("acctgTransId");
            String acctgTransTypeId = trimToNull(acctgTrans.getString("acctgTransTypeId"));
            if (UtilValidate.isEmpty(acctgTransId)) {
                continue;
            }

            if (isExpectedRepaymentAcctgType(acctgTransTypeId)) {
                Integer current = typeCounts.get(acctgTransTypeId);
                typeCounts.put(acctgTransTypeId, Integer.valueOf(current == null ? 1 : current.intValue() + 1));
            } else {
                result.unexpectedAcctgTransCount++;
            }

            List<GenericValue> entries;
            if (UtilValidate.isNotEmpty(organizationPartyId)) {
                entries = EntityQuery.use(delegator)
                        .from("AcctgTransEntry")
                        .where("acctgTransId", acctgTransId, "organizationPartyId", organizationPartyId)
                        .queryList();
            } else {
                entries = EntityQuery.use(delegator)
                        .from("AcctgTransEntry")
                        .where("acctgTransId", acctgTransId)
                        .queryList();
            }

            BigDecimal transDebit = BigDecimal.ZERO;
            BigDecimal transCredit = BigDecimal.ZERO;
            for (GenericValue entry : entries) {
                BigDecimal amount = safeAmount(entry.getBigDecimal("amount"));
                String debitCreditFlag = trimToNull(entry.getString("debitCreditFlag"));
                if ("D".equals(debitCreditFlag)) {
                    transDebit = transDebit.add(amount);
                } else if ("C".equals(debitCreditFlag)) {
                    transCredit = transCredit.add(amount);
                }
            }

            if (transDebit.compareTo(transCredit) != 0) {
                result.unbalancedAcctgTransCount++;
            }
            if (isExpectedRepaymentAcctgType(acctgTransTypeId)
                    && transDebit.compareTo(result.paymentAmount) != 0) {
                result.acctgAmountMismatchCount++;
            }
        }

        result.hasPaymentAcctgTrans = typeCounts.containsKey("PAYMENT_ACCTG_TRANS");
        result.hasIncomingPaymentTrans = typeCounts.containsKey("INCOMING_PAYMENT");
        result.hasPaymentApplicationTrans = typeCounts.containsKey("PAYMENT_APPL");
        result.expectedAcctgTypeCount = (result.hasPaymentAcctgTrans ? 1 : 0)
                + (result.hasIncomingPaymentTrans ? 1 : 0)
                + (result.hasPaymentApplicationTrans ? 1 : 0);

        for (Integer count : typeCounts.values()) {
            if (count != null && count.intValue() > 1) {
                result.duplicateAcctgTypeCount += count.intValue() - 1;
            }
        }

        if (UtilValidate.isNotEmpty(finAccountId)) {
            result.finAccountTransCount = EntityQuery.use(delegator)
                    .from("FinAccountTrans")
                    .where("paymentId", paymentId, "finAccountId", finAccountId)
                    .queryCount();
        }

        return result;
    }


    private static List<Map<String, Object>> inspectFullyAppliedRepaymentInvoiceStatus(
            Delegator delegator,
            String paymentId,
            String organizationPartyId,
            String loanId,
            String loanScheduleId,
            String loanRepaymentId,
            String currencyUomId,
            Set<String> checkedInvoiceIds) throws GenericEntityException {

        List<Map<String, Object>> invoiceIssues = new ArrayList<Map<String, Object>>();
        if (UtilValidate.isEmpty(paymentId)) {
            return invoiceIssues;
        }

        List<GenericValue> applications = EntityQuery.use(delegator)
                .from("PaymentApplication")
                .where("paymentId", paymentId)
                .queryList();

        for (GenericValue application : applications) {
            String invoiceId = trimToNull(application.getString("invoiceId"));
            if (UtilValidate.isEmpty(invoiceId) || checkedInvoiceIds.contains(invoiceId)) {
                continue;
            }

            GenericValue invoice = EntityQuery.use(delegator)
                    .from("Invoice")
                    .where("invoiceId", invoiceId)
                    .queryOne();
            if (invoice == null) {
                continue;
            }
            if (!"LOAN_REPAYMENT".equals(trimToNull(invoice.getString("invoiceTypeId")))) {
                continue;
            }

            checkedInvoiceIds.add(invoiceId);

            BigDecimal invoiceTotal = sumInvoiceTotal(delegator, invoiceId);
            BigDecimal appliedTotal = sumInvoicePaymentApplications(delegator, invoiceId);
            String statusId = trimToNull(invoice.getString("statusId"));

            if (invoiceTotal.compareTo(BigDecimal.ZERO) > 0
                    && appliedTotal.compareTo(invoiceTotal) >= 0
                    && !"INVOICE_PAID".equals(statusId)) {
                invoiceIssues.add(buildIssue("WARN", "REPAYMENT_INVOICE_STATUS_NOT_PAID",
                        organizationPartyId, loanId, loanScheduleId, loanRepaymentId, paymentId, null,
                        invoiceTotal, appliedTotal, currencyUomId,
                        "Repayment invoice is fully applied but statusId is " + statusId
                                + "; expected INVOICE_PAID. invoiceId=" + invoiceId));
            }
        }

        return invoiceIssues;
    }

    private static BigDecimal sumInvoiceTotal(Delegator delegator, String invoiceId) throws GenericEntityException {
        BigDecimal total = BigDecimal.ZERO;
        if (UtilValidate.isEmpty(invoiceId)) {
            return total;
        }
        List<GenericValue> items = EntityQuery.use(delegator)
                .from("InvoiceItem")
                .where("invoiceId", invoiceId)
                .queryList();
        for (GenericValue item : items) {
            BigDecimal amount = safeAmount(item.getBigDecimal("amount"));
            BigDecimal quantity = safeAmount(item.getBigDecimal("quantity"));
            if (quantity.compareTo(BigDecimal.ZERO) <= 0) {
                quantity = BigDecimal.ONE;
            }
            total = total.add(amount.multiply(quantity));
        }
        return total;
    }

    private static BigDecimal sumInvoicePaymentApplications(Delegator delegator, String invoiceId) throws GenericEntityException {
        BigDecimal total = BigDecimal.ZERO;
        if (UtilValidate.isEmpty(invoiceId)) {
            return total;
        }
        List<GenericValue> applications = EntityQuery.use(delegator)
                .from("PaymentApplication")
                .where("invoiceId", invoiceId)
                .queryList();
        for (GenericValue application : applications) {
            total = total.add(safeAmount(application.getBigDecimal("amountApplied")));
        }
        return total;
    }

    private static boolean isExpectedRepaymentAcctgType(String acctgTransTypeId) {
        return "PAYMENT_ACCTG_TRANS".equals(acctgTransTypeId)
                || "INCOMING_PAYMENT".equals(acctgTransTypeId)
                || "PAYMENT_APPL".equals(acctgTransTypeId);
    }

    private static Map<String, Object> buildIssue(
            String severity,
            String issueType,
            String organizationPartyId,
            String loanId,
            String loanScheduleId,
            String loanRepaymentId,
            String paymentId,
            String acctgTransId,
            BigDecimal expectedAmount,
            BigDecimal actualAmount,
            String currencyUomId,
            String description) {

        Map<String, Object> issue = new LinkedHashMap<String, Object>();
        issue.put("severity", severity);
        issue.put("issueType", issueType);
        issue.put("organizationPartyId", organizationPartyId);
        issue.put("loanId", loanId);
        issue.put("loanScheduleId", loanScheduleId);
        issue.put("loanRepaymentId", loanRepaymentId);
        issue.put("paymentId", paymentId);
        issue.put("acctgTransId", acctgTransId);
        issue.put("expectedAmount", expectedAmount);
        issue.put("actualAmount", actualAmount);
        issue.put("currencyUomId", currencyUomId);
        issue.put("description", description);
        return issue;
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

    private static final class PaymentCheckResult {
        String paymentId;
        boolean paymentExists;
        boolean hasAcctgTrans;
        int acctgTransCount;
        int expectedAcctgTypeCount;
        int duplicateAcctgTypeCount;
        int unexpectedAcctgTransCount;
        int unbalancedAcctgTransCount;
        int acctgAmountMismatchCount;
        int paymentApplicationCount;
        long finAccountTransCount;
        String firstAcctgTransId;
        String paymentCurrencyUomId;
        BigDecimal paymentAmount;
        BigDecimal totalPaymentApplied;
        boolean hasPaymentAcctgTrans;
        boolean hasIncomingPaymentTrans;
        boolean hasPaymentApplicationTrans;
    }
}
