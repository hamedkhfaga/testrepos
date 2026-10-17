package org.apache.ofbiz.payroll.services;

import org.apache.ofbiz.base.util.Debug;
import org.apache.ofbiz.base.util.UtilDateTime;
import org.apache.ofbiz.base.util.UtilMisc;
import org.apache.ofbiz.base.util.UtilValidate;

import org.apache.ofbiz.entity.Delegator;
import org.apache.ofbiz.entity.GenericEntityException;
import org.apache.ofbiz.entity.GenericValue;
import org.apache.ofbiz.entity.util.EntityQuery;

import org.apache.ofbiz.service.DispatchContext;
import org.apache.ofbiz.service.ServiceUtil;

import org.apache.ofbiz.entity.transaction.TransactionUtil;

import java.io.BufferedWriter;
import java.io.File;
import java.io.FileOutputStream;
import java.io.OutputStreamWriter;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.sql.Clob;
import java.sql.Timestamp;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.apache.ofbiz.payroll.util.PayrollStatusIds;
import org.apache.ofbiz.payroll.util.PayrollGovernanceUtil;

/**
 * PayrollBankServices
 *
 * Services:
 *  - generateBankTransferBatchForRun (move here from workflow)
 *  - exportBankTransferBatchFileForRun
 *  - uploadBankFileForRun (mark uploaded + close run)
 *
 * Notes:
 *  - Idempotent behavior: if a batch already exists for run, return it.
 *  - Export writes a CSV file to runtime/tmp/payroll_bank/<payrollRunId>/...
 *  - Upload is "metadata confirm": records reference + timestamps + moves PayrollRun -> PR_DONE.
 */
public final class PayrollBankServices {
    private static final String MODULE = PayrollBankServices.class.getName();

    // Bank batch statuses (keep aligned with your PayrollStatusData.xml)
    private static final String BTB_NEW      = "BTB_NEW";
    private static final String BTB_EXPORTED = "BTB_EXPORTED";
    private static final String BTB_SENT     = "BTB_SENT";
    private static final String BTB_DONE     = "BTB_DONE";
    private static final String BTB_CONF     = "BTB_CONFIRMED";

    // Bank batch line status
    private static final String BTBL_NEW = "BTBL_NEW";

    // Payroll statuses (keep aligned with your PayrollStatusIds)
    // If you already have PayrollStatusIds, replace these literals with PayrollStatusIds.*
    private static final String PR_PROCESSED = "PR_PROCESSED";
    private static final String PR_APPR      = "PR_APPR";
    private static final String PR_POST      = "PR_POST";
    private static final String PR_DONE      = "PR_DONE";

    private PayrollBankServices() {}

 /* ============================================================
 *  Service: generateBankTransferBatchForRun
 * ============================================================ */
public static Map<String, Object> generateBankTransferBatchForRun(DispatchContext dctx, Map<String, ?> context) {
    final Delegator delegator = dctx.getDelegator();
    final GenericValue userLogin = (GenericValue) context.get("userLogin");

    final String payrollRunId = asString(context, "payrollRunId");
    final String bankTransferConfigId = asString(context, "bankTransferConfigId");
    final String sourceIp = asString(context, "sourceIp");

    if (UtilValidate.isEmpty(payrollRunId)) return ServiceUtil.returnError("payrollRunId is required");
    if (UtilValidate.isEmpty(bankTransferConfigId)) return ServiceUtil.returnError("bankTransferConfigId is required");
    if (userLogin == null) return ServiceUtil.returnError("userLogin is required");

    boolean beganTx = false;
    boolean success = false;

    final Map<String, Object> data = new HashMap<>();
    data.put("service", "generateBankTransferBatchForRun");
    data.put("payrollRunId", payrollRunId);
    data.put("bankTransferConfigId", bankTransferConfigId);
    data.put("userLoginId", userLogin.getString("userLoginId"));
    data.put("sourceIp", sourceIp);
    data.put("timestamp", UtilDateTime.nowTimestamp());

    try {
        beganTx = TransactionUtil.begin();

        // 1) Load run
        final GenericValue run = EntityQuery.use(delegator)
                .from("PayrollRun")
                .where("payrollRunId", payrollRunId)
                .cache(false)
                .queryOne();
        if (run == null) {
            Map<String, Object> err = ServiceUtil.returnError("PayrollRun not found payrollRunId=" + payrollRunId);
            err.put("data", data);
            return err;
        }

        final String orgId = run.getString("organizationPartyId");
        final String payrollPeriodId = run.getString("payrollPeriodId");
        final String runStatusId = run.getString("statusId");
        final String runCurrency = run.getString("currencyUomId");

        // Governance pre-check: run editable + period open
        PayrollGovernanceUtil.ensureRunAndPeriodEditable(
                delegator,
                orgId,
                payrollPeriodId,
                payrollRunId
        );

        data.put("organizationPartyId", orgId);
        data.put("payrollPeriodId", payrollPeriodId);
        data.put("runStatusId", runStatusId);

        // Governance: allow generation only after payroll is finalized enough.
        if (!(PR_PROCESSED.equals(runStatusId) || PR_APPR.equals(runStatusId) || PR_POST.equals(runStatusId))) {
            Map<String, Object> err = ServiceUtil.returnError(
                    "Bank batch can be generated only after payroll is PROCESSED/APPROVED/POSTED. Current=" + runStatusId);
            err.put("data", data);
            return err;
        }

        // 1.1) Idempotency check: if already generated and linked on PayrollRun -> return it
        final String linkedBatchId = run.getString("generatedBankTransferBatchId");
        if (UtilValidate.isNotEmpty(linkedBatchId)) {
            data.put("bankTransferBatchId", linkedBatchId);
            data.put("ok", Boolean.TRUE);
            data.put("changed", Boolean.FALSE);

            Map<String, Object> out = ServiceUtil.returnSuccess("Bank batch already exists batchId=" + linkedBatchId);
            out.put("bankTransferBatchId", linkedBatchId);
            out.put("data", data);

            success = true;
            return out;
        }

        // 1.2) Idempotency check (DB): if a batch already exists for this run -> return it + writeback
        final GenericValue existing = EntityQuery.use(delegator)
                .from("BankTransferBatch")
                .where("payrollRunId", payrollRunId)
                .orderBy("-generatedDate")
                .cache(false)
                .queryFirst();
        if (existing != null) {
            final String existingId = existing.getString("bankTransferBatchId");
            writeBackGeneratedBatchToRun(delegator, payrollRunId, existingId, userLogin.getString("userLoginId"));

            data.put("bankTransferBatchId", existingId);
            data.put("ok", Boolean.TRUE);
            data.put("changed", Boolean.FALSE);

            Map<String, Object> out = ServiceUtil.returnSuccess("Bank batch already exists batchId=" + existingId);
            out.put("bankTransferBatchId", existingId);
            out.put("data", data);

            success = true;
            return out;
        }

        // 2) Load config
        final GenericValue cfg = EntityQuery.use(delegator)
                .from("BankTransferConfig")
                .where("bankTransferConfigId", bankTransferConfigId)
                .cache(false)
                .queryOne();
        if (cfg == null) {
            Map<String, Object> err = ServiceUtil.returnError("BankTransferConfig not found id=" + bankTransferConfigId);
            err.put("data", data);
            return err;
        }

        final String cfgOrg = cfg.getString("organizationPartyId");
        if (UtilValidate.isNotEmpty(cfgOrg) && !cfgOrg.equals(orgId)) {
            Map<String, Object> err = ServiceUtil.returnError(
                    "BankTransferConfig org mismatch. cfg.org=" + cfgOrg + " run.org=" + orgId);
            err.put("data", data);
            return err;
        }
        if (!"Y".equalsIgnoreCase(cfg.getString("isActive"))) {
            Map<String, Object> err = ServiceUtil.returnError("BankTransferConfig is not active id=" + bankTransferConfigId);
            err.put("data", data);
            return err;
        }

        // 3) Load results
        final List<GenericValue> results = EntityQuery.use(delegator)
                .from("EmployeePayrollResult")
                .where("payrollRunId", payrollRunId)
                .orderBy("payrollResultId")
                .cache(false)
                .queryList();
        if (UtilValidate.isEmpty(results)) {
            Map<String, Object> err = ServiceUtil.returnError("No EmployeePayrollResult found for payrollRunId=" + payrollRunId);
            err.put("data", data);
            return err;
        }

        // 4) Create batch header
        final String bankTransferBatchId = delegator.getNextSeqId("BankTransferBatch");
        final Timestamp nowTs = UtilDateTime.nowTimestamp();

        final GenericValue batch = delegator.makeValue("BankTransferBatch");
        batch.put("bankTransferBatchId", bankTransferBatchId);
        batch.put("organizationPartyId", orgId);
        batch.put("payrollRunId", payrollRunId);
        batch.put("bankTransferConfigId", bankTransferConfigId);

        String batchCurrency = runCurrency;
        if (UtilValidate.isEmpty(batchCurrency)) {
            batchCurrency = results.get(0).getString("currencyUomId");
        }
        batch.put("currencyUomId", batchCurrency);

        batch.put("totalAmount", BigDecimal.ZERO);
        batch.put("totalRecords", Long.valueOf(0));
        batch.put("generatedDate", nowTs);
        batch.put("statusId", BTB_NEW);

        safePut(batch, "createdByUserLoginId", userLogin.getString("userLoginId"));
        safePut(batch, "createdDate", nowTs);

        delegator.create(batch);

        // 5) Create lines from allocations (EFT only, as currently implemented)
        long createdLines = 0L;
        long skippedNonPositive = 0L;
        long skippedNoAlloc = 0L;
        long skippedNoPaymentMethod = 0L;
        long skippedNoEftAccount = 0L;

        BigDecimal totalAmount = BigDecimal.ZERO;
        long seq = 1L;

        final Timestamp now = UtilDateTime.nowTimestamp();

        for (GenericValue r : results) {
            BigDecimal net = r.getBigDecimal("netSalary");
            if (net == null) net = BigDecimal.ZERO;
            if (net.signum() <= 0) {
                skippedNonPositive++;
                continue;
            }

            final String partyId = r.getString("partyId");
            BigDecimal remaining = net;

            final List<GenericValue> allocs = EntityQuery.use(delegator)
                    .from("EmployeePaymentAllocation")
                    .where(
                            org.apache.ofbiz.entity.condition.EntityCondition.makeCondition(
                                    java.util.Arrays.asList(
                                            org.apache.ofbiz.entity.condition.EntityCondition.makeCondition(
                                                    "partyId",
                                                    org.apache.ofbiz.entity.condition.EntityOperator.EQUALS,
                                                    partyId),
                                            org.apache.ofbiz.entity.condition.EntityCondition.makeCondition(
                                                    "statusId",
                                                    org.apache.ofbiz.entity.condition.EntityOperator.EQUALS,
                                                    "PAY_ALLOC_ACT"),
                                            org.apache.ofbiz.entity.condition.EntityCondition.makeCondition(
                                                    "fromDate",
                                                    org.apache.ofbiz.entity.condition.EntityOperator.LESS_THAN_EQUAL_TO,
                                                    now),
                                            org.apache.ofbiz.entity.condition.EntityCondition.makeCondition(
                                                    org.apache.ofbiz.entity.condition.EntityCondition.makeCondition(
                                                            "thruDate",
                                                            org.apache.ofbiz.entity.condition.EntityOperator.EQUALS,
                                                            null),
                                                    org.apache.ofbiz.entity.condition.EntityOperator.OR,
                                                    org.apache.ofbiz.entity.condition.EntityCondition.makeCondition(
                                                            "thruDate",
                                                            org.apache.ofbiz.entity.condition.EntityOperator.GREATER_THAN_EQUAL_TO,
                                                            now)
                                            )
                                    )
                            )
                    )
                    .orderBy("prioritySeq")
                    .cache(false)
                    .queryList();

            if (UtilValidate.isEmpty(allocs)) {
                skippedNoAlloc++;
                continue;
            }

            for (GenericValue a : allocs) {
                if (remaining.signum() <= 0) break;

                final String allocType = a.getString("allocationTypeEnumId");
                final BigDecimal allocValue = bd(a.get("allocationValue"));
                final String isRemainder = a.getString("isDefaultRemainder");
                final String paymentMethodId = a.getString("paymentMethodId");

                if (UtilValidate.isEmpty(paymentMethodId)) {
                    skippedNoPaymentMethod++;
                    continue;
                }

                final GenericValue pm = EntityQuery.use(delegator)
                        .from("PaymentMethod")
                        .where("paymentMethodId", paymentMethodId)
                        .cache(false)
                        .queryOne();
                if (pm == null) {
                    skippedNoPaymentMethod++;
                    continue;
                }

                if (!"EFT_ACCOUNT".equals(pm.getString("paymentMethodTypeId"))) {
                    skippedNoPaymentMethod++;
                    continue;
                }

                final GenericValue eft = EntityQuery.use(delegator)
                        .from("EftAccount")
                        .where("paymentMethodId", paymentMethodId)
                        .cache(false)
                        .queryOne();
                if (eft == null) {
                    skippedNoEftAccount++;
                    continue;
                }

                BigDecimal payAmt = BigDecimal.ZERO;

                if ("Y".equalsIgnoreCase(isRemainder)) {
                    payAmt = remaining;
                } else if ("PAY_ALLOC_AMT".equals(allocType)) {
                    payAmt = remaining.min(allocValue);
                } else if ("PAY_ALLOC_PCT".equals(allocType)) {
                    payAmt = net.multiply(allocValue)
                            .divide(new BigDecimal("100"), 2, RoundingMode.HALF_UP);
                    if (payAmt.compareTo(remaining) > 0) {
                        payAmt = remaining;
                    }
                } else {
                    continue;
                }

                if (payAmt.signum() <= 0) continue;

                final String lineId = delegator.getNextSeqId("BankTransferBatchLine");
                final GenericValue line = delegator.makeValue("BankTransferBatchLine");
                line.put("bankTransferBatchLineId", lineId);
                line.put("bankTransferBatchId", bankTransferBatchId);
                line.put("payrollResultId", r.getString("payrollResultId"));

                line.put("partyId", partyId);
                line.put("paymentMethodId", paymentMethodId);
                line.put("paymentAllocationId", a.getString("paymentAllocationId"));

                line.put("amount", payAmt);
                line.put("currencyUomId",
                        UtilValidate.isNotEmpty(r.getString("currencyUomId"))
                                ? r.getString("currencyUomId")
                                : batchCurrency);
                line.put("beneficiaryName", resolvePartyDisplayName(delegator, partyId));

                line.put("bankName", eft.getString("bankName"));
                line.put("bankAccountNumber", eft.getString("accountNumber"));

                line.put("statusId", BTBL_NEW);
                line.put("sequenceNum", Long.valueOf(seq++));

                delegator.create(line);

                createdLines++;
                totalAmount = totalAmount.add(payAmt);
                remaining = remaining.subtract(payAmt);
            }
        }

        // 6) Update batch totals
        batch.put("totalAmount", totalAmount);
        batch.put("totalRecords", Long.valueOf(createdLines));
        batch.store();

        // 7) Write-back to PayrollRun (Pattern A)
        writeBackGeneratedBatchToRun(delegator, payrollRunId, bankTransferBatchId, userLogin.getString("userLoginId"));

        data.put("bankTransferBatchId", bankTransferBatchId);
        data.put("createdLines", createdLines);
        data.put("skippedNonPositiveNet", skippedNonPositive);
        data.put("skippedNoAllocation", skippedNoAlloc);
        data.put("skippedNoPaymentMethod", skippedNoPaymentMethod);
        data.put("skippedNoEftAccount", skippedNoEftAccount);
        data.put("totalAmount", totalAmount);
        data.put("currencyUomId", batchCurrency);
        data.put("ok", Boolean.TRUE);
        data.put("changed", Boolean.TRUE);

        Map<String, Object> out = ServiceUtil.returnSuccess(
                "Bank transfer batch generated batchId=" + bankTransferBatchId + " lines=" + createdLines);
        out.put("bankTransferBatchId", bankTransferBatchId);
        out.put("data", data);

        success = true;
        return out;

    } catch (Exception e) {
        Debug.logError(e, "generateBankTransferBatchForRun failed payrollRunId=" + payrollRunId, MODULE);
        Map<String, Object> err = ServiceUtil.returnError("generateBankTransferBatchForRun failed: " + safeMsg(e));
        data.put("ok", Boolean.FALSE);
        data.put("changed", Boolean.FALSE);
        data.put("message", "Exception: " + safeMsg(e));
        err.put("data", data);
        success = false;
        return err;

    } finally {
        endTxQuiet(beganTx, success, "generateBankTransferBatchForRun");
    }
}



/* ============================================================
 *  Service: exportBankTransferBatchFileForRun
 *    - Generates CSV file (simple POC)
 *    - Updates batch status -> BTB_EXPORTED
 *    - Returns exportFilePath + exportFileName + bankTransferBatchId
 * ============================================================ */
public static Map<String, Object> exportBankTransferBatchFileForRun(DispatchContext dctx, Map<String, ?> context) {
    final Delegator delegator = dctx.getDelegator();
    final GenericValue userLogin = (GenericValue) context.get("userLogin");

    final String payrollRunId = asString(context, "payrollRunId");
    final String bankTransferBatchIdIn = asString(context, "bankTransferBatchId");
    final String exportFormat = defaultString(asString(context, "exportFormat"), "CSV");
    final String outDirIn = asString(context, "outDir");
    final String sourceIp = asString(context, "sourceIp");

    if (UtilValidate.isEmpty(payrollRunId)) return ServiceUtil.returnError("payrollRunId is required");
    if (userLogin == null) return ServiceUtil.returnError("userLogin is required");
    if (!"CSV".equalsIgnoreCase(exportFormat)) return ServiceUtil.returnError("Only exportFormat=CSV supported for now.");

    boolean beganTx = false;
    boolean success = false;

    final Map<String, Object> data = new HashMap<>();
    data.put("service", "exportBankTransferBatchFileForRun");
    data.put("payrollRunId", payrollRunId);
    data.put("bankTransferBatchId", bankTransferBatchIdIn);
    data.put("exportFormat", exportFormat);
    data.put("outDir", outDirIn);
    data.put("userLoginId", userLogin.getString("userLoginId"));
    data.put("sourceIp", sourceIp);
    data.put("timestamp", UtilDateTime.nowTimestamp());

    try {
        beganTx = TransactionUtil.begin();

        // Load run
        final GenericValue run = EntityQuery.use(delegator)
                .from("PayrollRun")
                .where("payrollRunId", payrollRunId)
                .cache(false)
                .queryOne();
        if (run == null) {
            Map<String, Object> err = ServiceUtil.returnError("PayrollRun not found payrollRunId=" + payrollRunId);
            err.put("data", data);
            return err;
        }

        final String organizationPartyId = run.getString("organizationPartyId");
        final String payrollPeriodId = run.getString("payrollPeriodId");
        final String runStatusId = run.getString("statusId");

        PayrollGovernanceUtil.ensureRunAndPeriodEditable(
                delegator,
                organizationPartyId,
                payrollPeriodId,
                payrollRunId
        );

        data.put("organizationPartyId", organizationPartyId);
        data.put("payrollPeriodId", payrollPeriodId);
        data.put("runStatusId", runStatusId);

        if (!PR_POST.equals(runStatusId)) {
            Map<String, Object> err = ServiceUtil.returnError(
                    "Export bank file allowed only when PayrollRun status is PR_POST. Current=" + runStatusId);
            err.put("data", data);
            return err;
        }

        // Determine batchId
        String batchId = bankTransferBatchIdIn;
        if (UtilValidate.isEmpty(batchId)) {
            batchId = run.getString("generatedBankTransferBatchId");
        }
        if (UtilValidate.isEmpty(batchId)) {
            Map<String, Object> err = ServiceUtil.returnError(
                    "No bank batch linked to PayrollRun. generatedBankTransferBatchId is empty.");
            err.put("data", data);
            return err;
        }

        // Load batch
        final GenericValue batch = EntityQuery.use(delegator)
                .from("BankTransferBatch")
                .where("bankTransferBatchId", batchId)
                .cache(false)
                .queryOne();
        if (batch == null) {
            Map<String, Object> err = ServiceUtil.returnError("BankTransferBatch not found bankTransferBatchId=" + batchId);
            err.put("data", data);
            return err;
        }
        if (!payrollRunId.equals(batch.getString("payrollRunId"))) {
            Map<String, Object> err = ServiceUtil.returnError(
                    "Batch/run mismatch. batch.payrollRunId=" + batch.getString("payrollRunId") + " requested=" + payrollRunId);
            err.put("data", data);
            return err;
        }

        // Load lines
        final List<GenericValue> lines = EntityQuery.use(delegator)
                .from("BankTransferBatchLine")
                .where("bankTransferBatchId", batchId)
                .orderBy("sequenceNum")
                .cache(false)
                .queryList();
        if (UtilValidate.isEmpty(lines)) {
            Map<String, Object> err = ServiceUtil.returnError("No BankTransferBatchLine found for bankTransferBatchId=" + batchId);
            err.put("data", data);
            return err;
        }

        // Build output path
        final String baseDir = UtilValidate.isNotEmpty(outDirIn)
                ? outDirIn
                : System.getProperty("ofbiz.home") + File.separator + "runtime"
                    + File.separator + "tmp" + File.separator + "payroll_bank"
                    + File.separator + payrollRunId;

        final File dir = new File(baseDir);
        if (!dir.exists() && !dir.mkdirs()) {
            Map<String, Object> err = ServiceUtil.returnError("Failed to create output directory: " + baseDir);
            err.put("data", data);
            return err;
        }

        final String fileName = "bank_batch_" + batchId + "_" + UtilDateTime.nowAsString() + ".csv";
        final File outFile = new File(dir, fileName);

        try (BufferedWriter bw = new BufferedWriter(new OutputStreamWriter(new FileOutputStream(outFile), "UTF-8"))) {
            bw.write("SEQUENCE_NUM,BENEFICIARY_NAME,BANK_NAME,BANK_ACCOUNT_NUMBER,AMOUNT,CURRENCY_UOM_ID");
            bw.newLine();

            for (GenericValue l : lines) {
                final Long seq = (Long) l.get("sequenceNum");
                final String beneficiary = safeCsv(l.getString("beneficiaryName"));
                final String bankName = safeCsv(l.getString("bankName"));
                final String acct = safeCsv(clobAnyToString(l.get("bankAccountNumber")));
                final BigDecimal amt = (BigDecimal) l.get("amount");
                final String cur = safeCsv(l.getString("currencyUomId"));

                bw.write((seq != null ? seq.toString() : "")
                        + "," + beneficiary
                        + "," + bankName
                        + "," + acct
                        + "," + (amt != null ? amt.toPlainString() : "")
                        + "," + cur);
                bw.newLine();
            }
        }

        // Update batch status -> EXPORTED (idempotent)
        final String oldBatchStatus = batch.getString("statusId");
        if (!BTB_EXPORTED.equals(oldBatchStatus)
                && !BTB_SENT.equals(oldBatchStatus)
                && !BTB_DONE.equals(oldBatchStatus)
                && !BTB_CONF.equals(oldBatchStatus)) {
            batch.put("statusId", BTB_EXPORTED);
            batch.store();
        }

        data.put("bankTransferBatchId", batchId);
        data.put("exportFileName", fileName);
        data.put("exportFilePath", outFile.getAbsolutePath());
        data.put("batchOldStatusId", oldBatchStatus);
        data.put("batchNewStatusId", batch.getString("statusId"));
        data.put("recordCount", lines.size());
        data.put("ok", Boolean.TRUE);
        data.put("changed", Boolean.TRUE);

        Map<String, Object> out = ServiceUtil.returnSuccess("Bank batch exported file=" + fileName);
        out.put("bankTransferBatchId", batchId);
        out.put("exportFileName", fileName);
        out.put("exportFilePath", outFile.getAbsolutePath());
        out.put("data", data);

        success = true;
        return out;

    } catch (Exception e) {
        Debug.logError(e, "exportBankTransferBatchFileForRun failed payrollRunId=" + payrollRunId, MODULE);
        Map<String, Object> err = ServiceUtil.returnError("exportBankTransferBatchFileForRun failed: " + safeMsg(e));
        data.put("ok", Boolean.FALSE);
        data.put("changed", Boolean.FALSE);
        data.put("message", "Exception: " + safeMsg(e));
        err.put("data", data);
        success = false;
        return err;

    } finally {
        endTxQuiet(beganTx, success, "exportBankTransferBatchFileForRun");
    }
}
    /* ============================================================
     *  Service: uploadBankFileForRun (metadata confirm)
     *    - Validates PR_POST
     *    - Requires batch exists + (recommended) batch already exported
     *    - Writes bankFileReference + uploadedBy/date
     *    - Marks PayrollRun status -> PR_DONE and sets doneBy/date
     *    - Updates batch status -> BTB_CONFIRMED
     * ============================================================ */
public static Map<String, Object> uploadBankFileForRun(DispatchContext dctx, Map<String, ?> context) {
    final Delegator delegator = dctx.getDelegator();
    final GenericValue userLogin = (GenericValue) context.get("userLogin");

    final String payrollRunId = asString(context, "payrollRunId");
    final String bankTransferBatchIdIn = asString(context, "bankTransferBatchId"); // optional
    final String bankFileReference = asString(context, "bankFileReference");       // required (filename/ref)
    final String sourceIp = asString(context, "sourceIp");

    if (UtilValidate.isEmpty(payrollRunId)) return ServiceUtil.returnError("payrollRunId is required");
    if (userLogin == null) return ServiceUtil.returnError("userLogin is required");
    if (UtilValidate.isEmpty(bankFileReference)) return ServiceUtil.returnError("bankFileReference is required");

    boolean beganTx = false;
    boolean success = false;

    final Map<String, Object> data = new HashMap<>();
    data.put("service", "uploadBankFileForRun");
    data.put("payrollRunId", payrollRunId);
    data.put("bankTransferBatchId", bankTransferBatchIdIn);
    data.put("bankFileReference", bankFileReference);
    data.put("userLoginId", userLogin.getString("userLoginId"));
    data.put("sourceIp", sourceIp);
    data.put("timestamp", UtilDateTime.nowTimestamp());

    try {
        beganTx = TransactionUtil.begin();

        final GenericValue run = EntityQuery.use(delegator)
                .from("PayrollRun")
                .where("payrollRunId", payrollRunId)
                .cache(false)
                .queryOne();
        if (run == null) {
            Map<String, Object> err = ServiceUtil.returnError("PayrollRun not found payrollRunId=" + payrollRunId);
            err.put("data", data);
            return err;
        }

        final String runStatusId = run.getString("statusId");
        if (!PR_POST.equals(runStatusId)) {
            Map<String, Object> err = ServiceUtil.returnError(
                    "uploadBankFileForRun allowed only when PayrollRun status is PR_POST. Current=" + runStatusId);
            err.put("data", data);
            return err;
        }

        // Determine batchId
        String batchId = bankTransferBatchIdIn;
        if (UtilValidate.isEmpty(batchId)) batchId = run.getString("generatedBankTransferBatchId");
        if (UtilValidate.isEmpty(batchId)) {
            Map<String, Object> err = ServiceUtil.returnError("No bank batch linked to PayrollRun (generatedBankTransferBatchId is empty).");
            err.put("data", data);
            return err;
        }

        final GenericValue batch = EntityQuery.use(delegator)
                .from("BankTransferBatch")
                .where("bankTransferBatchId", batchId)
                .cache(false)
                .queryOne();
        if (batch == null) {
            Map<String, Object> err = ServiceUtil.returnError("BankTransferBatch not found bankTransferBatchId=" + batchId);
            err.put("data", data);
            return err;
        }
        if (!payrollRunId.equals(batch.getString("payrollRunId"))) {
            Map<String, Object> err = ServiceUtil.returnError(
                    "Batch/run mismatch. batch.payrollRunId=" + batch.getString("payrollRunId") + " requested=" + payrollRunId);
            err.put("data", data);
            return err;
        }

        final String oldBatchStatusId = batch.getString("statusId");

        // Governance: must be exported first (or already SENT for retry)
        if (!(BTB_EXPORTED.equals(oldBatchStatusId) || BTB_SENT.equals(oldBatchStatusId))) {
            Map<String, Object> err = ServiceUtil.returnError(
                    "uploadBankFileForRun requires batch status BTB_EXPORTED (or BTB_SENT if retry). Current=" + oldBatchStatusId);
            err.put("data", data);
            return err;
        }

        final boolean alreadyUploaded =
                UtilValidate.isNotEmpty(run.getString("bankFileUploadedByUserLoginId"))
                        || run.get("bankFileUploadedDate") != null;

        // If already uploaded and batch already SENT => idempotent OK
        if (alreadyUploaded && BTB_SENT.equals(oldBatchStatusId)) {
            data.put("bankTransferBatchId", batchId);
            data.put("oldRunStatusId", runStatusId);
            data.put("newRunStatusId", runStatusId);
            data.put("oldBatchStatusId", oldBatchStatusId);
            data.put("newBatchStatusId", oldBatchStatusId);
            data.put("ok", Boolean.TRUE);
            data.put("changed", Boolean.FALSE);

            Map<String, Object> out = ServiceUtil.returnSuccess("Already uploaded (SENT). No changes payrollRunId=" + payrollRunId);
            out.put("bankTransferBatchId", batchId);
            out.put("data", data);
            success = true;
            return out;
        }

        final Timestamp nowTs = UtilDateTime.nowTimestamp();

        // Write upload fields (safePut for new columns)
        safePut(run, "bankFileUploadedByUserLoginId", userLogin.getString("userLoginId"));
        safePut(run, "bankFileUploadedDate", nowTs);
        safePut(run, "bankFileReference", bankFileReference);
        run.store();

        // Move batch to SENT
        if (!BTB_SENT.equals(oldBatchStatusId)) {
            batch.put("statusId", BTB_SENT);
            batch.store();
        }

        data.put("bankTransferBatchId", batchId);
        data.put("oldRunStatusId", runStatusId);
        data.put("newRunStatusId", run.getString("statusId")); // unchanged PR_POST
        data.put("oldBatchStatusId", oldBatchStatusId);
        data.put("newBatchStatusId", batch.getString("statusId"));
        data.put("ok", Boolean.TRUE);
        data.put("changed", Boolean.TRUE);

        Map<String, Object> out = ServiceUtil.returnSuccess("Bank file marked SENT (uploaded). payrollRunId=" + payrollRunId);
        out.put("bankTransferBatchId", batchId);
        out.put("data", data);

        success = true;
        return out;

    } catch (Exception e) {
        Debug.logError(e, "uploadBankFileForRun failed payrollRunId=" + payrollRunId, MODULE);
        Map<String, Object> err = ServiceUtil.returnError("uploadBankFileForRun failed: " + safeMsg(e));
        data.put("ok", Boolean.FALSE);
        data.put("changed", Boolean.FALSE);
        data.put("message", "Exception: " + safeMsg(e));
        err.put("data", data);
        success = false;
        return err;

    } finally {
        endTxQuiet(beganTx, success, "uploadBankFileForRun");
    }
}

public static Map<String, Object> markBankFileUploadedForRun(DispatchContext dctx, Map<String, ?> context) {
    final Delegator delegator = dctx.getDelegator();
    final GenericValue userLogin = (GenericValue) context.get("userLogin");

    final String payrollRunId = asString(context, "payrollRunId");
    final String bankTransferBatchIdIn = asString(context, "bankTransferBatchId"); // optional
    final String bankFileReference = asString(context, "bankFileReference");       // required
    final String sourceIp = asString(context, "sourceIp");

    if (UtilValidate.isEmpty(payrollRunId)) return ServiceUtil.returnError("payrollRunId is required");
    if (userLogin == null) return ServiceUtil.returnError("userLogin is required");
    if (UtilValidate.isEmpty(bankFileReference)) return ServiceUtil.returnError("bankFileReference is required");

    boolean beganTx = false;
    boolean success = false;

    final Map<String, Object> data = new HashMap<>();
    data.put("service", "markBankFileUploadedForRun");
    data.put("payrollRunId", payrollRunId);
    data.put("bankTransferBatchId", bankTransferBatchIdIn);
    data.put("bankFileReference", bankFileReference);
    data.put("userLoginId", userLogin.getString("userLoginId"));
    data.put("sourceIp", sourceIp);
    data.put("timestamp", UtilDateTime.nowTimestamp());

    try {
        beganTx = TransactionUtil.begin();

        final GenericValue run = EntityQuery.use(delegator)
                .from("PayrollRun")
                .where("payrollRunId", payrollRunId)
                .cache(false)
                .queryOne();

        if (run == null) {
            Map<String, Object> err = ServiceUtil.returnError("PayrollRun not found payrollRunId=" + payrollRunId);
            err.put("data", data);
            return err;
        }

        final String runStatusId = run.getString("statusId");
        if (!PR_POST.equals(runStatusId)) {
            Map<String, Object> err = ServiceUtil.returnError(
                    "markBankFileUploadedForRun allowed only when PayrollRun status is PR_POST. Current=" + runStatusId);
            err.put("data", data);
            return err;
        }

        // Determine batchId (optional but recommended)
        String batchId = bankTransferBatchIdIn;
        if (UtilValidate.isEmpty(batchId)) {
            batchId = run.getString("generatedBankTransferBatchId");
        }
        if (UtilValidate.isEmpty(batchId)) {
            Map<String, Object> err = ServiceUtil.returnError(
                    "No bank batch linked to PayrollRun. generatedBankTransferBatchId is empty.");
            err.put("data", data);
            return err;
        }

        final GenericValue batch = EntityQuery.use(delegator)
                .from("BankTransferBatch")
                .where("bankTransferBatchId", batchId)
                .cache(false)
                .queryOne();
        if (batch == null) {
            Map<String, Object> err = ServiceUtil.returnError("BankTransferBatch not found bankTransferBatchId=" + batchId);
            err.put("data", data);
            return err;
        }
        if (!payrollRunId.equals(batch.getString("payrollRunId"))) {
            Map<String, Object> err = ServiceUtil.returnError(
                    "Batch/run mismatch. batch.payrollRunId=" + batch.getString("payrollRunId") + " requested=" + payrollRunId);
            err.put("data", data);
            return err;
        }

        // Optional governance: require export first (same policy as uploadBankFileForRun)
        final String batchStatusId = batch.getString("statusId");
        if (!(BTB_EXPORTED.equals(batchStatusId) || BTB_SENT.equals(batchStatusId)
                || BTB_DONE.equals(batchStatusId) || BTB_CONF.equals(batchStatusId))) {
            Map<String, Object> err = ServiceUtil.returnError(
                    "Bank upload mark requires batch status >= BTB_EXPORTED. Current=" + batchStatusId);
            err.put("data", data);
            return err;
        }

        // Idempotent: if already marked, return ok
        if (UtilValidate.isNotEmpty(run.getString("bankFileUploadedByUserLoginId"))
                || run.get("bankFileUploadedDate") != null) {

            data.put("bankTransferBatchId", batchId);
            data.put("ok", Boolean.TRUE);
            data.put("changed", Boolean.FALSE);

            Map<String, Object> out = ServiceUtil.returnSuccess("Bank file already marked uploaded payrollRunId=" + payrollRunId);
            out.put("bankTransferBatchId", batchId);
            out.put("data", data);
            success = true;
            return out;
        }

        final Timestamp nowTs = UtilDateTime.nowTimestamp();

        // Update PayrollRun only (do NOT change run status here)
        safePut(run, "bankFileUploadedByUserLoginId", userLogin.getString("userLoginId"));
        safePut(run, "bankFileUploadedDate", nowTs);
        safePut(run, "bankFileReference", bankFileReference);
        run.store();

        // Update batch status -> SENT (or CONFIRMED if you prefer)
        // SENT = "we uploaded to bank", CONFIRMED = "bank confirmed"
        if (!BTB_CONF.equals(batchStatusId)) {
            batch.put("statusId", BTB_SENT);
            batch.store();
        }

        data.put("bankTransferBatchId", batchId);
        data.put("oldRunStatusId", runStatusId);
        data.put("newRunStatusId", run.getString("statusId"));
        data.put("oldBatchStatusId", batchStatusId);
        data.put("newBatchStatusId", batch.getString("statusId"));
        data.put("ok", Boolean.TRUE);
        data.put("changed", Boolean.TRUE);

        Map<String, Object> out = ServiceUtil.returnSuccess("Marked bank file uploaded payrollRunId=" + payrollRunId);
        out.put("bankTransferBatchId", batchId);
        out.put("data", data);

        success = true;
        return out;

    } catch (Exception e) {
        Debug.logError(e, "markBankFileUploadedForRun failed payrollRunId=" + payrollRunId, MODULE);
        Map<String, Object> err = ServiceUtil.returnError("markBankFileUploadedForRun failed: " + safeMsg(e));
        data.put("ok", Boolean.FALSE);
        data.put("changed", Boolean.FALSE);
        data.put("message", "Exception: " + safeMsg(e));
        err.put("data", data);
        success = false;
        return err;

    } finally {
        endTxQuiet(beganTx, success, "markBankFileUploadedForRun");
    }
}
 
public static Map<String, Object> confirmBankTransferBatchForRun(DispatchContext dctx, Map<String, ?> context) {
    final Delegator delegator = dctx.getDelegator();
    final GenericValue userLogin = (GenericValue) context.get("userLogin");

    final String payrollRunId = asString(context, "payrollRunId");
    final String bankTransferBatchIdIn = asString(context, "bankTransferBatchId"); // optional
    final String confirmReference = asString(context, "confirmReference");         // optional (bank confirmation id)
    final String sourceIp = asString(context, "sourceIp");

    if (UtilValidate.isEmpty(payrollRunId)) return ServiceUtil.returnError("payrollRunId is required");
    if (userLogin == null) return ServiceUtil.returnError("userLogin is required");

    boolean beganTx = false;
    boolean success = false;

    final Map<String, Object> data = new HashMap<>();
    data.put("service", "confirmBankTransferBatchForRun");
    data.put("payrollRunId", payrollRunId);
    data.put("bankTransferBatchId", bankTransferBatchIdIn);
    data.put("confirmReference", confirmReference);
    data.put("userLoginId", userLogin.getString("userLoginId"));
    data.put("sourceIp", sourceIp);
    data.put("timestamp", UtilDateTime.nowTimestamp());

    try {
        beganTx = TransactionUtil.begin();

        final GenericValue run = EntityQuery.use(delegator)
                .from("PayrollRun")
                .where("payrollRunId", payrollRunId)
                .cache(false)
                .queryOne();

        if (run == null) {
            Map<String, Object> err = ServiceUtil.returnError("PayrollRun not found payrollRunId=" + payrollRunId);
            err.put("data", data);
            return err;
        }

        final String oldRunStatusId = run.getString("statusId");

        // Confirm allowed when run is POST (still open) OR already DONE (idempotent / partial repair)
        if (!(PR_POST.equals(oldRunStatusId) || PR_DONE.equals(oldRunStatusId))) {
            Map<String, Object> err = ServiceUtil.returnError(
                    "confirmBankTransferBatchForRun allowed only when PayrollRun is PR_POST (or PR_DONE). Current=" + oldRunStatusId);
            err.put("data", data);
            return err;
        }

        // Determine batchId
        String batchId = bankTransferBatchIdIn;
        if (UtilValidate.isEmpty(batchId)) batchId = run.getString("generatedBankTransferBatchId");

        if (UtilValidate.isEmpty(batchId)) {
            Map<String, Object> err = ServiceUtil.returnError(
                    "No bank batch linked to PayrollRun. generatedBankTransferBatchId is empty.");
            err.put("data", data);
            return err;
        }

        final GenericValue batch = EntityQuery.use(delegator)
                .from("BankTransferBatch")
                .where("bankTransferBatchId", batchId)
                .cache(false)
                .queryOne();

        if (batch == null) {
            Map<String, Object> err = ServiceUtil.returnError("BankTransferBatch not found bankTransferBatchId=" + batchId);
            err.put("data", data);
            return err;
        }

        if (!payrollRunId.equals(batch.getString("payrollRunId"))) {
            Map<String, Object> err = ServiceUtil.returnError(
                    "Batch/run mismatch. batch.payrollRunId=" + batch.getString("payrollRunId") + " requested=" + payrollRunId);
            err.put("data", data);
            return err;
        }

        final String oldBatchStatusId = batch.getString("statusId");

        // Option A governance:
        // Confirm requires SENT (uploaded/submitted) OR already CONFIRMED.
        // (Do NOT allow EXPORTED here.)
        if (!(BTB_SENT.equals(oldBatchStatusId) || BTB_CONF.equals(oldBatchStatusId))) {
            Map<String, Object> err = ServiceUtil.returnError(
                    "confirmBankTransferBatchForRun requires batch status BTB_SENT (or already BTB_CONFIRMED). Current=" + oldBatchStatusId);
            err.put("data", data);
            return err;
        }

        final Timestamp nowTs = UtilDateTime.nowTimestamp();
        boolean didChange = false;

        // 1) Ensure batch is CONFIRMED
        if (!BTB_CONF.equals(oldBatchStatusId)) {
            batch.put("statusId", BTB_CONF);

            // If you later add a column for confirmReference, use safePut here.
            // safePut(batch, "confirmReference", confirmReference);

            batch.store();
            didChange = true;
        }

        // 2) Ensure run is DONE (only if still POST)
        if (PR_POST.equals(oldRunStatusId)) {
            run.put("statusId", PR_DONE);
            safePut(run, "doneByUserLoginId", userLogin.getString("userLoginId"));
            safePut(run, "doneDate", nowTs);
            run.store();
            didChange = true;
        }

        // 3) Fully idempotent case (already confirmed + already done) => didChange stays false

        data.put("bankTransferBatchId", batchId);
        data.put("oldRunStatusId", oldRunStatusId);
        data.put("newRunStatusId", run.getString("statusId"));
        data.put("oldBatchStatusId", oldBatchStatusId);
        data.put("newBatchStatusId", batch.getString("statusId"));
        data.put("ok", Boolean.TRUE);
        data.put("changed", Boolean.valueOf(didChange));

        Map<String, Object> out;
        if (!didChange) {
            out = ServiceUtil.returnSuccess("Already confirmed. PayrollRun already DONE payrollRunId=" + payrollRunId);
        } else {
            out = ServiceUtil.returnSuccess("Bank batch confirmed. PayrollRun closed DONE payrollRunId=" + payrollRunId);
        }

        out.put("bankTransferBatchId", batchId);
        out.put("data", data);

        success = true;
        return out;

    } catch (Exception e) {
        Debug.logError(e, "confirmBankTransferBatchForRun failed payrollRunId=" + payrollRunId, MODULE);
        Map<String, Object> err = ServiceUtil.returnError("confirmBankTransferBatchForRun failed: " + safeMsg(e));
        data.put("ok", Boolean.FALSE);
        data.put("changed", Boolean.FALSE);
        data.put("message", "Exception: " + safeMsg(e));
        err.put("data", data);
        success = false;
        return err;

    } finally {
        endTxQuiet(beganTx, success, "confirmBankTransferBatchForRun");
    }
}
 /* ============================================================
     * Helpers
     * ============================================================ */

    private static void writeBackGeneratedBatchToRun(Delegator delegator, String payrollRunId, String bankTransferBatchId, String userLoginId) {
        try {
            GenericValue runToUpdate = EntityQuery.use(delegator)
                    .from("PayrollRun")
                    .where("payrollRunId", payrollRunId)
                    .cache(false)
                    .queryOne();

            if (runToUpdate != null) {
                runToUpdate.put("requiresBankBatch", "Y");
                runToUpdate.put("generatedBankTransferBatchId", bankTransferBatchId);
                runToUpdate.put("bankBatchGeneratedDate", UtilDateTime.nowTimestamp());
                runToUpdate.put("bankBatchGeneratedByUserLoginId", userLoginId);
                runToUpdate.store();
            }
        } catch (Exception e) {
            // Do not fail the whole service if write-back fails (batch already created)
            Debug.logWarning(e, "Bank batch created but PayrollRun write-back failed payrollRunId=" + payrollRunId, MODULE);
        }
    }

    private static String asString(Map<String, ?> ctx, String key) {
        Object v = (ctx != null) ? ctx.get(key) : null;
        return (v == null) ? null : String.valueOf(v);
    }

    private static String defaultString(String v, String def) {
        return UtilValidate.isNotEmpty(v) ? v : def;
    }

    private static BigDecimal bd(Object o) {
        if (o == null) return BigDecimal.ZERO;
        if (o instanceof BigDecimal) return (BigDecimal) o;
        try {
            return new BigDecimal(String.valueOf(o));
        } catch (Exception e) {
            return BigDecimal.ZERO;
        }
    }

    private static void endTxQuiet(boolean beganTx, boolean success, String svcName) {
        if (!beganTx) return;
        try {
            if (success) {
                TransactionUtil.commit();
            } else {
                TransactionUtil.rollback();
            }
        } catch (Exception e) {
            Debug.logError(e, "Tx end failed service=" + svcName, MODULE);
        }
    }

    private static String safeMsg(Throwable t) {
        if (t == null) return "null";
        String m = t.getMessage();
        return (m != null) ? m : t.toString();
    }

    private static void safePut(GenericValue gv, String fieldName, Object value) {
        try {
            if (gv.getModelEntity() != null && gv.getModelEntity().isField(fieldName)) {
                gv.put(fieldName, value);
            }
        } catch (Throwable ignore) {
            // safe: field might not exist in some environments
        }
    }

    private static String resolvePartyDisplayName(Delegator delegator, String partyId) throws GenericEntityException {
        if (UtilValidate.isEmpty(partyId)) return "";
        GenericValue p = EntityQuery.use(delegator)
                .from("PartyNameView")
                .where("partyId", partyId)
                .cache(false)
                .queryFirst();
        if (p == null) return partyId;
        String fullName = p.getString("groupName");
        if (UtilValidate.isEmpty(fullName)) {
            String fn = p.getString("firstName");
            String ln = p.getString("lastName");
            fullName = (fn != null ? fn : "") + (ln != null ? (" " + ln) : "");
            fullName = fullName.trim();
        }
        return UtilValidate.isNotEmpty(fullName) ? fullName : partyId;
    }

    private static String clobAnyToString(Object v) {
        if (v == null) return "";
        if (v instanceof String) return (String) v;
        if (v instanceof Clob) {
            try {
                Clob c = (Clob) v;
                long len = c.length();
                if (len <= 0) return "";
                return c.getSubString(1, (int) Math.min(len, Integer.MAX_VALUE));
            } catch (Exception e) {
                return "";
            }
        }
        // Derby sometimes returns driver-specific objects; fallback:
        return String.valueOf(v);
    }

    private static String safeCsv(String s) {
        if (s == null) return "";
        // Wrap with quotes if contains comma/quote/newline
        boolean needs = s.contains(",") || s.contains("\"") || s.contains("\n") || s.contains("\r");
        String out = s.replace("\"", "\"\"");
        return needs ? ("\"" + out + "\"") : out;
    }
}