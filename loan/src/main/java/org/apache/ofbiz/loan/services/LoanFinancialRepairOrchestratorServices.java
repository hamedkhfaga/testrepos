package org.apache.ofbiz.loan.services;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.apache.ofbiz.base.util.Debug;
import org.apache.ofbiz.base.util.UtilValidate;
import org.apache.ofbiz.entity.GenericValue;
import org.apache.ofbiz.service.DispatchContext;
import org.apache.ofbiz.service.LocalDispatcher;
import org.apache.ofbiz.service.ServiceUtil;

public final class LoanFinancialRepairOrchestratorServices {
    public static final String MODULE = LoanFinancialRepairOrchestratorServices.class.getName();

    private LoanFinancialRepairOrchestratorServices() {}

    /**
     * Phase 3 controlled financial repair orchestrator.
     * Default dryRun is Y for safety. Pass dryRun=N explicitly to mutate finance data.
     */
    public static Map<String, Object> repairLoanFinancialIntegrityV1(DispatchContext dctx, Map<String, ?> context) {
        LocalDispatcher dispatcher = dctx.getDispatcher();
        GenericValue userLogin = (GenericValue) context.get("userLogin");

        String loanId = trimToNull((String) context.get("loanId"));
        String organizationPartyId = trimToNull((String) context.get("organizationPartyId"));
        String paymentMethodId = trimToNull((String) context.get("paymentMethodId"));
        String paymentMethodTypeId = trimToNull((String) context.get("paymentMethodTypeId"));
        String paymentTypeId = trimToNull((String) context.get("paymentTypeId"));
        String autoCreateAccounting = normalizeFlag((String) context.get("autoCreateAccounting"));
        String autoCreateFinAccountTrans = normalizeFlag((String) context.get("autoCreateFinAccountTrans"));
        String dryRun = normalizeFlagDefaultY((String) context.get("dryRun"));
        String repairPaymentAmount = normalizeFlag((String) context.get("repairPaymentAmount"));
        String repairPaymentApplicationAmount = normalizeFlagDefaultY((String) context.get("repairPaymentApplicationAmount"));
        String repairAcctgEntryAmount = normalizeFlagDefaultY((String) context.get("repairAcctgEntryAmount"));

        try {
            if (UtilValidate.isEmpty(loanId)) {
                return ServiceUtil.returnError("loanId is required");
            }
            if (UtilValidate.isEmpty(organizationPartyId)) {
                return ServiceUtil.returnError("organizationPartyId is required");
            }

            Map<String, Object> result = ServiceUtil.returnSuccess("Loan financial repair completed");
            result.put("loanId", loanId);
            result.put("organizationPartyId", organizationPartyId);
            result.put("dryRun", dryRun);
            result.put("autoCreateAccounting", autoCreateAccounting);
            result.put("autoCreateFinAccountTrans", autoCreateFinAccountTrans);
            result.put("repairPaymentAmount", repairPaymentAmount);
            result.put("repairPaymentApplicationAmount", repairPaymentApplicationAmount);
            result.put("repairAcctgEntryAmount", repairAcctgEntryAmount);

            Map<String, Object> reconCtx = new HashMap<String, Object>();
            reconCtx.put("loanId", loanId);
            reconCtx.put("organizationPartyId", organizationPartyId);
            reconCtx.put("userLogin", userLogin);

            Map<String, Object> initialRecon = dispatcher.runSync("reconcileLoanAccountingV1", reconCtx);
            if (ServiceUtil.isError(initialRecon)) {
                return ServiceUtil.returnError(ServiceUtil.getErrorMessage(initialRecon));
            }
            result.put("initialReconciliation", initialRecon);

            Map<String, Object> paymentCtx = new LinkedHashMap<String, Object>();
            paymentCtx.put("loanId", loanId);
            paymentCtx.put("organizationPartyId", organizationPartyId);
            paymentCtx.put("paymentMethodId", paymentMethodId);
            paymentCtx.put("paymentMethodTypeId", paymentMethodTypeId);
            paymentCtx.put("paymentTypeId", paymentTypeId);
            paymentCtx.put("autoCreateAccounting", autoCreateAccounting);
            paymentCtx.put("autoCreateFinAccountTrans", autoCreateFinAccountTrans);
            paymentCtx.put("dryRun", dryRun);
            paymentCtx.put("userLogin", userLogin);

            Map<String, Object> paymentRepair = dispatcher.runSync("backfillLoanRepaymentPaymentV1", paymentCtx);
            if (ServiceUtil.isError(paymentRepair)) {
                return ServiceUtil.returnError(ServiceUtil.getErrorMessage(paymentRepair));
            }
            result.put("paymentRepair", paymentRepair);

            Map<String, Object> paymentApplicationCtx = new LinkedHashMap<String, Object>();
            paymentApplicationCtx.put("loanId", loanId);
            paymentApplicationCtx.put("organizationPartyId", organizationPartyId);
            paymentApplicationCtx.put("dryRun", dryRun);
            paymentApplicationCtx.put("userLogin", userLogin);

            Map<String, Object> paymentApplicationRepair = dispatcher.runSync("backfillLoanRepaymentPaymentApplicationV1", paymentApplicationCtx);
            if (ServiceUtil.isError(paymentApplicationRepair)) {
                return ServiceUtil.returnError(ServiceUtil.getErrorMessage(paymentApplicationRepair));
            }
            result.put("paymentApplicationRepair", paymentApplicationRepair);

            Map<String, Object> acctgCtx = new LinkedHashMap<String, Object>();
            acctgCtx.put("loanId", loanId);
            acctgCtx.put("organizationPartyId", organizationPartyId);
            acctgCtx.put("createFinAccountTrans", autoCreateFinAccountTrans);
            acctgCtx.put("dryRun", dryRun);
            acctgCtx.put("userLogin", userLogin);

            Map<String, Object> acctgRepair = dispatcher.runSync("backfillLoanRepaymentAccountingV1", acctgCtx);
            if (ServiceUtil.isError(acctgRepair)) {
                return ServiceUtil.returnError(ServiceUtil.getErrorMessage(acctgRepair));
            }
            result.put("accountingRepair", acctgRepair);

            Map<String, Object> amountCtx = new LinkedHashMap<String, Object>();
            amountCtx.put("loanId", loanId);
            amountCtx.put("organizationPartyId", organizationPartyId);
            amountCtx.put("dryRun", dryRun);
            amountCtx.put("repairPaymentAmount", repairPaymentAmount);
            amountCtx.put("repairPaymentApplicationAmount", repairPaymentApplicationAmount);
            amountCtx.put("repairAcctgEntryAmount", repairAcctgEntryAmount);
            amountCtx.put("userLogin", userLogin);

            Map<String, Object> amountConsistencyRepair = dispatcher.runSync("backfillLoanRepaymentAmountConsistencyV1", amountCtx);
            if (ServiceUtil.isError(amountConsistencyRepair)) {
                return ServiceUtil.returnError(ServiceUtil.getErrorMessage(amountConsistencyRepair));
            }
            result.put("amountConsistencyRepair", amountConsistencyRepair);

            Map<String, Object> finalRecon = dispatcher.runSync("reconcileLoanAccountingV1", reconCtx);
            if (ServiceUtil.isError(finalRecon)) {
                return ServiceUtil.returnError(ServiceUtil.getErrorMessage(finalRecon));
            }
            result.put("finalReconciliation", finalRecon);

            int initialErrorCount = extractInt(initialRecon, "errorCount");
            int finalErrorCount = extractInt(finalRecon, "errorCount");
            result.put("initialErrorCount", Integer.valueOf(initialErrorCount));
            result.put("finalErrorCount", Integer.valueOf(finalErrorCount));

            String finalStatus;
            if ("Y".equals(dryRun)) {
                finalStatus = finalErrorCount == 0 ? "DRY_RUN_CLEAN" : "DRY_RUN_ISSUES";
            } else if (finalErrorCount == 0) {
                finalStatus = "CLEAN";
            } else if (finalErrorCount < initialErrorCount) {
                finalStatus = "IMPROVED";
            } else {
                finalStatus = "PARTIAL";
            }
            result.put("finalStatus", finalStatus);
            return result;

        } catch (Exception e) {
            Debug.logError(e, "Error in repairLoanFinancialIntegrityV1", MODULE);
            return ServiceUtil.returnError(e.getMessage());
        }
    }


    /**
     * Phase 6F enterprise batch repair orchestration.
     * Runs organization-wide reconciliation, queues affected loans, and delegates each loan to repairLoanFinancialIntegrityV1.
     */
    public static Map<String, Object> repairLoanFinancialIntegrityBatchV1(DispatchContext dctx, Map<String, ?> context) {
        LocalDispatcher dispatcher = dctx.getDispatcher();
        GenericValue userLogin = (GenericValue) context.get("userLogin");

        String organizationPartyId = trimToNull((String) context.get("organizationPartyId"));
        String loanIdFilter = trimToNull((String) context.get("loanId"));
        String dryRun = normalizeFlagDefaultY((String) context.get("dryRun"));
        String autoCreateAccounting = normalizeFlag((String) context.get("autoCreateAccounting"));
        String autoCreateFinAccountTrans = normalizeFlag((String) context.get("autoCreateFinAccountTrans"));
        String repairPaymentAmount = normalizeFlag((String) context.get("repairPaymentAmount"));
        String repairPaymentApplicationAmount = normalizeFlagDefaultY((String) context.get("repairPaymentApplicationAmount"));
        String repairAcctgEntryAmount = normalizeFlagDefaultY((String) context.get("repairAcctgEntryAmount"));
        String stopOnError = normalizeFlag((String) context.get("stopOnError"));
        Integer maxLoansValue = asInteger(context.get("maxLoans"));
        int maxLoans = maxLoansValue != null && maxLoansValue.intValue() > 0 ? maxLoansValue.intValue() : 10;

        try {
            if (UtilValidate.isEmpty(organizationPartyId)) {
                return ServiceUtil.returnError("organizationPartyId is required");
            }

            Map<String, Object> result = ServiceUtil.returnSuccess("Loan financial batch repair completed");
            result.put("organizationPartyId", organizationPartyId);
            result.put("loanId", loanIdFilter);
            result.put("dryRun", dryRun);
            result.put("maxLoans", Integer.valueOf(maxLoans));
            result.put("autoCreateAccounting", autoCreateAccounting);
            result.put("autoCreateFinAccountTrans", autoCreateFinAccountTrans);
            result.put("repairPaymentAmount", repairPaymentAmount);
            result.put("repairPaymentApplicationAmount", repairPaymentApplicationAmount);
            result.put("repairAcctgEntryAmount", repairAcctgEntryAmount);
            result.put("stopOnError", stopOnError);

            Map<String, Object> reconCtx = new LinkedHashMap<String, Object>();
            reconCtx.put("organizationPartyId", organizationPartyId);
            if (UtilValidate.isNotEmpty(loanIdFilter)) {
                reconCtx.put("loanId", loanIdFilter);
            }
            reconCtx.put("strictDuplicateAcctg", "N");
            reconCtx.put("requireFinAccountTrans", "N");
            reconCtx.put("userLogin", userLogin);

            Map<String, Object> initialRecon = dispatcher.runSync("reconcileLoanAccountingV1", reconCtx);
            if (ServiceUtil.isError(initialRecon)) {
                return ServiceUtil.returnError(ServiceUtil.getErrorMessage(initialRecon));
            }
            result.put("initialReconciliation", initialRecon);

            List<Map<String, Object>> issues = extractIssueList(initialRecon);
            Map<String, Integer> issueTypeCounts = new LinkedHashMap<String, Integer>();
            Map<String, Map<String, Integer>> loanIssueTypeCounts = new LinkedHashMap<String, Map<String, Integer>>();
            Set<String> queuedLoanIds = new HashSet<String>();
            List<String> loanQueue = new ArrayList<String>();

            for (Map<String, Object> issue : issues) {
                String issueLoanId = trimToNull((String) issue.get("loanId"));
                String issueType = trimToNull((String) issue.get("issueType"));
                if (UtilValidate.isNotEmpty(issueType)) {
                    Integer current = issueTypeCounts.get(issueType);
                    issueTypeCounts.put(issueType, Integer.valueOf(current == null ? 1 : current.intValue() + 1));
                }
                if (UtilValidate.isEmpty(issueLoanId)) continue;
                if (UtilValidate.isNotEmpty(loanIdFilter) && !loanIdFilter.equals(issueLoanId)) continue;

                Map<String, Integer> perLoanCounts = loanIssueTypeCounts.get(issueLoanId);
                if (perLoanCounts == null) {
                    perLoanCounts = new LinkedHashMap<String, Integer>();
                    loanIssueTypeCounts.put(issueLoanId, perLoanCounts);
                }
                if (UtilValidate.isNotEmpty(issueType)) {
                    Integer currentForLoan = perLoanCounts.get(issueType);
                    perLoanCounts.put(issueType, Integer.valueOf(currentForLoan == null ? 1 : currentForLoan.intValue() + 1));
                }

                // Phase 6F hardening:
                // Queue only issues handled by the financial repair pipeline.
                // Loan-only balance issues such as LOAN_SCHEDULE_OUTSTANDING_MISMATCH must be handled by a later
                // balance recalculation phase and should not call repayment repair services, because some of those
                // loans legitimately have no PartyLoanRepayment rows and OFBiz marks the transaction rollback-only.
                if (!isBatchFinancialRepairIssue(issueType)) continue;

                if (!queuedLoanIds.contains(issueLoanId)) {
                    queuedLoanIds.add(issueLoanId);
                    loanQueue.add(issueLoanId);
                }
            }

            result.put("initialIssueTypeCounts", issueTypeCounts);
            result.put("loanIssueTypeCounts", loanIssueTypeCounts);
            result.put("queuedLoanCount", Integer.valueOf(loanQueue.size()));

            List<Map<String, Object>> loanResults = new ArrayList<Map<String, Object>>();
            int processedLoanCount = 0;
            int improvedLoanCount = 0;
            int cleanLoanCount = 0;
            int partialLoanCount = 0;
            int errorLoanCount = 0;
            int skippedLoanCount = 0;

            for (String currentLoanId : loanQueue) {
                if (processedLoanCount >= maxLoans) {
                    skippedLoanCount++;
                    Map<String, Object> skipped = new LinkedHashMap<String, Object>();
                    skipped.put("loanId", currentLoanId);
                    skipped.put("status", "SKIPPED_MAX_LOANS");
                    skipped.put("reason", "Batch maxLoans limit reached");
                    loanResults.add(skipped);
                    continue;
                }

                Map<String, Object> repairCtx = new LinkedHashMap<String, Object>();
                repairCtx.put("loanId", currentLoanId);
                repairCtx.put("organizationPartyId", organizationPartyId);
                repairCtx.put("dryRun", dryRun);
                repairCtx.put("autoCreateAccounting", autoCreateAccounting);
                repairCtx.put("autoCreateFinAccountTrans", autoCreateFinAccountTrans);
                repairCtx.put("repairPaymentAmount", repairPaymentAmount);
                repairCtx.put("repairPaymentApplicationAmount", repairPaymentApplicationAmount);
                repairCtx.put("repairAcctgEntryAmount", repairAcctgEntryAmount);
                copyIfPresent(context, repairCtx, "paymentMethodId");
                copyIfPresent(context, repairCtx, "paymentMethodTypeId");
                copyIfPresent(context, repairCtx, "paymentTypeId");
                repairCtx.put("userLogin", userLogin);

                Map<String, Object> loanResult = new LinkedHashMap<String, Object>();
                loanResult.put("loanId", currentLoanId);

                try {
                    Map<String, Object> repairResult = dispatcher.runSync("repairLoanFinancialIntegrityV1", repairCtx);
                    if (ServiceUtil.isError(repairResult)) {
                        errorLoanCount++;
                        loanResult.put("status", "ERROR");
                        loanResult.put("reason", ServiceUtil.getErrorMessage(repairResult));
                        loanResult.put("repairResult", repairResult);
                        loanResults.add(loanResult);
                        processedLoanCount++;
                        if ("Y".equals(stopOnError)) break;
                        continue;
                    }
                    String finalStatus = trimToNull((String) repairResult.get("finalStatus"));
                    int initialErrorCount = extractInt(repairResult, "initialErrorCount");
                    int finalErrorCount = extractInt(repairResult, "finalErrorCount");
                    loanResult.put("status", finalStatus);
                    loanResult.put("initialErrorCount", Integer.valueOf(initialErrorCount));
                    loanResult.put("finalErrorCount", Integer.valueOf(finalErrorCount));
                    loanResult.put("repairResult", repairResult);
                    if ("CLEAN".equals(finalStatus) || "DRY_RUN_CLEAN".equals(finalStatus)) {
                        cleanLoanCount++;
                    } else if ("IMPROVED".equals(finalStatus)) {
                        improvedLoanCount++;
                    } else {
                        partialLoanCount++;
                    }
                    loanResults.add(loanResult);
                    processedLoanCount++;
                } catch (Exception e) {
                    errorLoanCount++;
                    loanResult.put("status", "ERROR");
                    loanResult.put("reason", e.getMessage());
                    loanResults.add(loanResult);
                    processedLoanCount++;
                    Debug.logError(e, "Error repairing loanId=" + currentLoanId + " in repairLoanFinancialIntegrityBatchV1", MODULE);
                    if ("Y".equals(stopOnError)) break;
                }
            }

            Map<String, Object> finalRecon = dispatcher.runSync("reconcileLoanAccountingV1", reconCtx);
            if (ServiceUtil.isError(finalRecon)) {
                return ServiceUtil.returnError(ServiceUtil.getErrorMessage(finalRecon));
            }
            result.put("finalReconciliation", finalRecon);
            int initialErrorCount = extractInt(initialRecon, "errorCount");
            int finalErrorCount = extractInt(finalRecon, "errorCount");
            result.put("initialErrorCount", Integer.valueOf(initialErrorCount));
            result.put("finalErrorCount", Integer.valueOf(finalErrorCount));
            result.put("processedLoanCount", Integer.valueOf(processedLoanCount));
            result.put("improvedLoanCount", Integer.valueOf(improvedLoanCount));
            result.put("cleanLoanCount", Integer.valueOf(cleanLoanCount));
            result.put("partialLoanCount", Integer.valueOf(partialLoanCount));
            result.put("errorLoanCount", Integer.valueOf(errorLoanCount));
            result.put("skippedLoanCount", Integer.valueOf(skippedLoanCount));
            result.put("loanResults", loanResults);
            String finalStatus;
            if ("Y".equals(dryRun)) finalStatus = finalErrorCount == 0 ? "DRY_RUN_CLEAN" : "DRY_RUN_ISSUES";
            else if (finalErrorCount == 0) finalStatus = "CLEAN";
            else if (finalErrorCount < initialErrorCount) finalStatus = "IMPROVED";
            else if (processedLoanCount > 0) finalStatus = "PARTIAL";
            else finalStatus = "NO_ACTION";
            result.put("finalStatus", finalStatus);
            result.put("data", new LinkedHashMap<String, Object>(result));
            result.put("legacyData", new LinkedHashMap<String, Object>(result));
            return result;
        } catch (Exception e) {
            Debug.logError(e, "Error in repairLoanFinancialIntegrityBatchV1", MODULE);
            return ServiceUtil.returnError(e.getMessage());
        }
    }




    /**
     * Phase 6F.2 chunked financial repair executor.
     *
     * This service is intentionally repayment-level and should be declared with use-transaction="false".
     * Each child repair service call can commit independently, which avoids long parent transactions and
     * prevents one repayment from forcing the whole batch to roll back.
     */
    public static Map<String, Object> repairLoanFinancialIntegrityChunkedV1(DispatchContext dctx, Map<String, ?> context) {
        LocalDispatcher dispatcher = dctx.getDispatcher();
        GenericValue userLogin = (GenericValue) context.get("userLogin");

        String organizationPartyId = trimToNull((String) context.get("organizationPartyId"));
        String loanIdFilter = trimToNull((String) context.get("loanId"));
        String dryRun = normalizeFlagDefaultY((String) context.get("dryRun"));
        String stopOnError = normalizeFlag((String) context.get("stopOnError"));
        String autoCreateFinAccountTrans = normalizeFlag((String) context.get("autoCreateFinAccountTrans"));
        String repairPaymentAmount = normalizeFlag((String) context.get("repairPaymentAmount"));
        String repairPaymentApplicationAmount = normalizeFlagDefaultY((String) context.get("repairPaymentApplicationAmount"));
        String repairAcctgEntryAmount = normalizeFlagDefaultY((String) context.get("repairAcctgEntryAmount"));
        String runPaymentRepair = normalizeFlagDefaultY((String) context.get("runPaymentRepair"));
        String runPaymentApplicationRepair = normalizeFlagDefaultY((String) context.get("runPaymentApplicationRepair"));
        String runAccountingRepair = normalizeFlagDefaultY((String) context.get("runAccountingRepair"));
        String runAmountConsistencyRepair = normalizeFlagDefaultY((String) context.get("runAmountConsistencyRepair"));
        Integer maxRepaymentsValue = asInteger(context.get("maxRepayments"));
        int maxRepayments = maxRepaymentsValue != null && maxRepaymentsValue.intValue() > 0
                ? maxRepaymentsValue.intValue() : 3;

        try {
            if (UtilValidate.isEmpty(organizationPartyId)) {
                return ServiceUtil.returnError("organizationPartyId is required");
            }

            Map<String, Object> result = ServiceUtil.returnSuccess("Loan financial chunked repair completed");
            result.put("organizationPartyId", organizationPartyId);
            result.put("loanId", loanIdFilter);
            result.put("dryRun", dryRun);
            result.put("maxRepayments", Integer.valueOf(maxRepayments));
            result.put("stopOnError", stopOnError);
            result.put("runPaymentRepair", runPaymentRepair);
            result.put("runPaymentApplicationRepair", runPaymentApplicationRepair);
            result.put("runAccountingRepair", runAccountingRepair);
            result.put("runAmountConsistencyRepair", runAmountConsistencyRepair);

            Map<String, Object> reconCtx = new LinkedHashMap<String, Object>();
            reconCtx.put("organizationPartyId", organizationPartyId);
            if (UtilValidate.isNotEmpty(loanIdFilter)) {
                reconCtx.put("loanId", loanIdFilter);
            }
            reconCtx.put("strictDuplicateAcctg", "N");
            reconCtx.put("requireFinAccountTrans", "N");
            reconCtx.put("userLogin", userLogin);

            Map<String, Object> initialRecon = dispatcher.runSync("reconcileLoanAccountingV1", reconCtx);
            if (ServiceUtil.isError(initialRecon)) {
                return ServiceUtil.returnError(ServiceUtil.getErrorMessage(initialRecon));
            }
            result.put("initialReconciliation", initialRecon);
            int initialErrorCount = extractInt(initialRecon, "errorCount");

            List<Map<String, Object>> issues = extractIssueList(initialRecon);
            Map<String, Integer> issueTypeCounts = new LinkedHashMap<String, Integer>();
            Set<String> queuedRepaymentIds = new HashSet<String>();
            List<Map<String, Object>> repaymentQueue = new ArrayList<Map<String, Object>>();

            for (Map<String, Object> issue : issues) {
                String issueType = trimToNull((String) issue.get("issueType"));
                String issueLoanId = trimToNull((String) issue.get("loanId"));
                String loanRepaymentId = trimToNull((String) issue.get("loanRepaymentId"));
                if (UtilValidate.isNotEmpty(issueType)) {
                    Integer current = issueTypeCounts.get(issueType);
                    issueTypeCounts.put(issueType, Integer.valueOf(current == null ? 1 : current.intValue() + 1));
                }
                if (!isBatchFinancialRepairIssue(issueType)) continue;
                if (UtilValidate.isEmpty(loanRepaymentId)) continue;
                if (UtilValidate.isNotEmpty(loanIdFilter) && !loanIdFilter.equals(issueLoanId)) continue;
                if (queuedRepaymentIds.contains(loanRepaymentId)) continue;

                Map<String, Object> queueItem = new LinkedHashMap<String, Object>();
                queueItem.put("loanId", issueLoanId);
                queueItem.put("loanRepaymentId", loanRepaymentId);
                queueItem.put("issueType", issueType);
                queueItem.put("issue", issue);
                queuedRepaymentIds.add(loanRepaymentId);
                repaymentQueue.add(queueItem);
            }

            result.put("initialIssueTypeCounts", issueTypeCounts);
            result.put("queuedRepaymentCount", Integer.valueOf(repaymentQueue.size()));

            List<Map<String, Object>> repaymentResults = new ArrayList<Map<String, Object>>();
            int processedRepaymentCount = 0;
            int skippedRepaymentCount = 0;
            int errorRepaymentCount = 0;
            int improvedRepaymentCount = 0;

            for (Map<String, Object> queueItem : repaymentQueue) {
                String currentLoanId = trimToNull((String) queueItem.get("loanId"));
                String currentRepaymentId = trimToNull((String) queueItem.get("loanRepaymentId"));
                String issueType = trimToNull((String) queueItem.get("issueType"));

                if (processedRepaymentCount >= maxRepayments) {
                    skippedRepaymentCount++;
                    Map<String, Object> skipped = new LinkedHashMap<String, Object>();
                    skipped.put("loanId", currentLoanId);
                    skipped.put("loanRepaymentId", currentRepaymentId);
                    skipped.put("issueType", issueType);
                    skipped.put("status", "SKIPPED_MAX_REPAYMENTS");
                    skipped.put("reason", "Chunk maxRepayments limit reached");
                    repaymentResults.add(skipped);
                    continue;
                }

                Map<String, Object> row = new LinkedHashMap<String, Object>();
                row.put("loanId", currentLoanId);
                row.put("loanRepaymentId", currentRepaymentId);
                row.put("issueType", issueType);

                try {
                    boolean rowHadAction = false;
                    boolean rowHadError = false;

                    if ("REPAYMENT_MISSING_PAYMENT".equals(issueType) && "Y".equals(runPaymentRepair)) {
                        Map<String, Object> paymentCtx = new LinkedHashMap<String, Object>();
                        paymentCtx.put("loanId", currentLoanId);
                        paymentCtx.put("loanRepaymentId", currentRepaymentId);
                        paymentCtx.put("organizationPartyId", organizationPartyId);
                        paymentCtx.put("dryRun", dryRun);
                        paymentCtx.put("autoCreateAccounting", "N");
                        paymentCtx.put("autoCreateFinAccountTrans", "N");
                        copyIfPresent(context, paymentCtx, "paymentMethodId");
                        copyIfPresent(context, paymentCtx, "paymentMethodTypeId");
                        copyIfPresent(context, paymentCtx, "paymentTypeId");
                        paymentCtx.put("userLogin", userLogin);

                        Map<String, Object> paymentRepair = dispatcher.runSync("backfillLoanRepaymentPaymentV1", paymentCtx);
                        row.put("paymentRepair", paymentRepair);
                        rowHadAction = true;
                        if (ServiceUtil.isError(paymentRepair) || extractInt(paymentRepair, "errorCount") > 0) {
                            rowHadError = true;
                        }
                    }

                    if (!rowHadError && "Y".equals(runPaymentApplicationRepair)) {
                        Map<String, Object> appCtx = new LinkedHashMap<String, Object>();
                        appCtx.put("loanId", currentLoanId);
                        appCtx.put("loanRepaymentId", currentRepaymentId);
                        appCtx.put("organizationPartyId", organizationPartyId);
                        appCtx.put("dryRun", dryRun);
                        appCtx.put("createInvoiceIfMissing", "Y");
                        appCtx.put("userLogin", userLogin);

                        Map<String, Object> paymentApplicationRepair = dispatcher.runSync("backfillLoanRepaymentPaymentApplicationV1", appCtx);
                        row.put("paymentApplicationRepair", paymentApplicationRepair);
                        rowHadAction = true;
                        if (ServiceUtil.isError(paymentApplicationRepair) || extractInt(paymentApplicationRepair, "errorCount") > 0) {
                            rowHadError = true;
                        }
                    }

                    if (!rowHadError && "Y".equals(runAccountingRepair)) {
                        Map<String, Object> acctgCtx = new LinkedHashMap<String, Object>();
                        acctgCtx.put("loanId", currentLoanId);
                        acctgCtx.put("loanRepaymentId", currentRepaymentId);
                        acctgCtx.put("organizationPartyId", organizationPartyId);
                        acctgCtx.put("dryRun", dryRun);
                        acctgCtx.put("createFinAccountTrans", autoCreateFinAccountTrans);
                        acctgCtx.put("userLogin", userLogin);

                        Map<String, Object> accountingRepair = dispatcher.runSync("backfillLoanRepaymentAccountingV1", acctgCtx);
                        row.put("accountingRepair", accountingRepair);
                        rowHadAction = true;
                        if (ServiceUtil.isError(accountingRepair) || extractInt(accountingRepair, "errorCount") > 0) {
                            rowHadError = true;
                        }
                    }

                    if (!rowHadError && "Y".equals(runAmountConsistencyRepair)) {
                        Map<String, Object> amountCtx = new LinkedHashMap<String, Object>();
                        amountCtx.put("loanId", currentLoanId);
                        amountCtx.put("loanRepaymentId", currentRepaymentId);
                        amountCtx.put("organizationPartyId", organizationPartyId);
                        amountCtx.put("dryRun", dryRun);
                        amountCtx.put("repairPaymentAmount", repairPaymentAmount);
                        amountCtx.put("repairPaymentApplicationAmount", repairPaymentApplicationAmount);
                        amountCtx.put("repairAcctgEntryAmount", repairAcctgEntryAmount);
                        amountCtx.put("userLogin", userLogin);

                        Map<String, Object> amountRepair = dispatcher.runSync("backfillLoanRepaymentAmountConsistencyV1", amountCtx);
                        row.put("amountConsistencyRepair", amountRepair);
                        rowHadAction = true;
                        if (ServiceUtil.isError(amountRepair) || extractInt(amountRepair, "errorCount") > 0) {
                            rowHadError = true;
                        }
                    }

                    if (rowHadError) {
                        row.put("status", "ERROR");
                        errorRepaymentCount++;
                    } else if (rowHadAction) {
                        row.put("status", "Y".equals(dryRun) ? "DRY_RUN" : "DONE");
                        improvedRepaymentCount++;
                    } else {
                        row.put("status", "SKIPPED_NO_ACTION");
                        skippedRepaymentCount++;
                    }

                    repaymentResults.add(row);
                    processedRepaymentCount++;
                    if (rowHadError && "Y".equals(stopOnError)) break;
                } catch (Exception e) {
                    errorRepaymentCount++;
                    processedRepaymentCount++;
                    row.put("status", "ERROR");
                    row.put("reason", e.getMessage());
                    repaymentResults.add(row);
                    Debug.logError(e, "Error repairing loanRepaymentId=" + currentRepaymentId
                            + " in repairLoanFinancialIntegrityChunkedV1", MODULE);
                    if ("Y".equals(stopOnError)) break;
                }
            }

            Map<String, Object> finalRecon = dispatcher.runSync("reconcileLoanAccountingV1", reconCtx);
            if (ServiceUtil.isError(finalRecon)) {
                return ServiceUtil.returnError(ServiceUtil.getErrorMessage(finalRecon));
            }
            int finalErrorCount = extractInt(finalRecon, "errorCount");

            result.put("finalReconciliation", finalRecon);
            result.put("initialErrorCount", Integer.valueOf(initialErrorCount));
            result.put("finalErrorCount", Integer.valueOf(finalErrorCount));
            result.put("processedRepaymentCount", Integer.valueOf(processedRepaymentCount));
            result.put("improvedRepaymentCount", Integer.valueOf(improvedRepaymentCount));
            result.put("errorRepaymentCount", Integer.valueOf(errorRepaymentCount));
            result.put("skippedRepaymentCount", Integer.valueOf(skippedRepaymentCount));
            result.put("repaymentResults", repaymentResults);

            String finalStatus;
            if ("Y".equals(dryRun)) finalStatus = finalErrorCount == 0 ? "DRY_RUN_CLEAN" : "DRY_RUN_ISSUES";
            else if (finalErrorCount == 0) finalStatus = "CLEAN";
            else if (finalErrorCount < initialErrorCount) finalStatus = "IMPROVED";
            else if (processedRepaymentCount > 0) finalStatus = "PARTIAL";
            else finalStatus = "NO_ACTION";
            result.put("finalStatus", finalStatus);
            result.put("data", new LinkedHashMap<String, Object>(result));
            result.put("legacyData", new LinkedHashMap<String, Object>(result));
            return result;
        } catch (Exception e) {
            Debug.logError(e, "Error in repairLoanFinancialIntegrityChunkedV1", MODULE);
            return ServiceUtil.returnError(e.getMessage());
        }
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> extractIssueList(Map<String, Object> serviceResp) {
        Object dataObj = serviceResp.get("data");
        if (dataObj instanceof Map) {
            Object issues = ((Map<String, Object>) dataObj).get("issues");
            if (issues instanceof List) return (List<Map<String, Object>>) issues;
        }
        Object issues = serviceResp.get("issues");
        if (issues instanceof List) return (List<Map<String, Object>>) issues;
        return new ArrayList<Map<String, Object>>();
    }

    private static boolean isBatchFinancialRepairIssue(String issueType) {
        if (UtilValidate.isEmpty(issueType)) return false;
        return "REPAYMENT_MISSING_PAYMENT".equals(issueType)
                || "PAYMENT_APPLICATION_MISSING".equals(issueType)
                || "ACCTG_TRANS_MISSING".equals(issueType)
                || "ACCTG_3TXN_MODEL_INCOMPLETE".equals(issueType)
                || "PAYMENT_AMOUNT_MISMATCH".equals(issueType)
                || "PAYMENT_APPLICATION_AMOUNT_MISMATCH".equals(issueType)
                || "ACCTG_ENTRY_AMOUNT_MISMATCH".equals(issueType);
    }

    private static Integer asInteger(Object value) {
        if (value instanceof Number) return Integer.valueOf(((Number) value).intValue());
        if (value instanceof String) {
            try { return Integer.valueOf(((String) value).trim()); } catch (Exception e) { return null; }
        }
        return null;
    }

    private static void copyIfPresent(Map<String, ?> source, Map<String, Object> target, String key) {
        Object value = source.get(key);
        if (value != null) target.put(key, value);
    }

    private static int extractInt(Map<String, Object> serviceResp, String key) {
        Object dataObj = serviceResp.get("data");
        if (dataObj instanceof Map) {
            @SuppressWarnings("unchecked")
            Map<String, Object> data = (Map<String, Object>) dataObj;
            Object value = data.get(key);
            if (value instanceof Number) return ((Number) value).intValue();
        }
        Object value = serviceResp.get(key);
        if (value instanceof Number) return ((Number) value).intValue();
        return 0;
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
}
