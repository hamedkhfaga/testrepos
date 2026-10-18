package org.apache.ofbiz.payroll.services;

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
import org.apache.ofbiz.entity.GenericValue;
import org.apache.ofbiz.entity.util.EntityQuery;
import org.apache.ofbiz.service.DispatchContext;
import org.apache.ofbiz.service.ServiceUtil;
import org.apache.ofbiz.payroll.util.PayrollGovernanceUtil;
import org.apache.ofbiz.payroll.util.PayrollStatusIds;

public class PayrollBankTransferServicesV3 {
    public static final String MODULE = PayrollBankTransferServicesV3.class.getName();

    public static Map<String, Object> generateBankTransferBatchV3(DispatchContext dctx, Map<String, Object> context) {
        Delegator delegator = dctx.getDelegator();

        Map<String, Object> data = new HashMap<>();
        Map<String, Object> legacyData = new HashMap<>();

        String payrollRunId = (String) context.get("payrollRunId");
        String cfgId = (String) context.get("bankTransferConfigId");
        boolean force = "Y".equalsIgnoreCase((String) context.get("forceRegenerate"));
        boolean dryRun = "Y".equalsIgnoreCase((String) context.get("dryRun"));

        if (UtilValidate.isEmpty(payrollRunId)) {
            return errorNonNull("payrollRunId is required", data, legacyData);
        }
        if (UtilValidate.isEmpty(cfgId)) {
            return errorNonNull("bankTransferConfigId is required", data, legacyData);
        }

        try {
            GenericValue run = EntityQuery.use(delegator)
                    .from("PayrollRun")
                    .where("payrollRunId", payrollRunId)
                    .queryOne();
            if (run == null) {
                return errorNonNull("PayrollRun not found: " + payrollRunId, data, legacyData);
            }

            String organizationPartyId = run.getString("organizationPartyId");
            String payrollPeriodId = run.getString("payrollPeriodId");

            PayrollGovernanceUtil.ensureRunAndPeriodEditable(
                    delegator,
                    organizationPartyId,
                    payrollPeriodId,
                    payrollRunId
            );

            List<GenericValue> instr = EntityQuery.use(delegator)
                    .from("PayrollPaymentInstruction")
                    .where("payrollRunId", payrollRunId,
                           "disbursementChannelId", "BANK")
                    .queryList();

            if (instr == null || instr.isEmpty()) {
                return errorNonNull("No BANK payment instructions for run=" + payrollRunId, data, legacyData);
            }

            long existingBatch = EntityQuery.use(delegator)
                    .from("BankTransferBatch")
                    .where("payrollRunId", payrollRunId)
                    .queryCount();

            if (existingBatch > 0 && !force) {
                return errorNonNull("Bank batch already exists. Use forceRegenerate=Y", data, legacyData);
            }

            if (!dryRun && existingBatch > 0 && force) {
                List<GenericValue> batches = EntityQuery.use(delegator)
                        .from("BankTransferBatch")
                        .where("payrollRunId", payrollRunId)
                        .queryList();
                for (GenericValue b : batches) {
                    delegator.removeByAnd(
                            "BankTransferBatchItem",
                            UtilMisc.toMap("bankTransferBatchId", b.getString("bankTransferBatchId"))
                    );
                    b.remove();
                }
            }

            Timestamp now = UtilDateTime.nowTimestamp();
            BigDecimal total = BigDecimal.ZERO;
            long itemCount = 0L;
            String batchId = "BTB_" + payrollRunId;

            if (!dryRun) {
                GenericValue batch = delegator.makeValue("BankTransferBatch");
                batch.set("bankTransferBatchId", batchId);
                batch.set("payrollRunId", payrollRunId);
                batch.set("organizationPartyId", organizationPartyId);
                batch.set("bankTransferConfigId", cfgId);
                batch.set("statusId", "BATCH_GEN");
                batch.set("generatedDateTime", now);
                batch.create();

                long seq = 1;
                for (GenericValue ppi : instr) {
                    BigDecimal amt = ppi.getBigDecimal("amount");
                    if (amt == null) {
                        amt = BigDecimal.ZERO;
                    }
                    total = total.add(amt);
                    itemCount++;

                    String itemId = delegator.getNextSeqId("BankTransferBatchItem");
                    GenericValue it = delegator.makeValue("BankTransferBatchItem");
                    it.set("bankTransferBatchItemId", itemId);
                    it.set("bankTransferBatchId", batchId);
                    it.set("paymentInstructionId", ppi.getString("paymentInstructionId"));
                    it.set("partyId", ppi.getString("partyId"));
                    it.set("amount", amt);
                    it.set("currencyUomId", ppi.getString("currencyUomId"));
                    it.set("bankName", ppi.getString("bankName"));
                    it.set("bankAccountNumber", ppi.getString("bankAccountNumber"));
                    it.set("reference", "PAYRUN_" + payrollRunId);
                    it.set("sequenceNum", seq++);
                    it.create();
                }

                GenericValue batchReload = EntityQuery.use(delegator)
                        .from("BankTransferBatch")
                        .where("bankTransferBatchId", batchId)
                        .queryOne();
                if (batchReload != null) {
                    batchReload.set("itemCount", itemCount);
                    batchReload.set("totalAmount", total);
                    batchReload.store();
                }

                run.set("generatedBankTransferBatchId", batchId);
                run.set("bankBatchGeneratedDate", now);
                run.store();
            } else {
                for (GenericValue ppi : instr) {
                    BigDecimal amt = ppi.getBigDecimal("amount");
                    if (amt == null) {
                        amt = BigDecimal.ZERO;
                    }
                    total = total.add(amt);
                    itemCount++;
                }
            }

            Map<String, Object> out = ServiceUtil.returnSuccess("###BANK_BATCH_V3### items=" + itemCount);

            data.put("payrollRunId", payrollRunId);
            data.put("organizationPartyId", organizationPartyId);
            data.put("payrollPeriodId", payrollPeriodId);
            data.put("bankTransferConfigId", cfgId);
            data.put("bankTransferBatchId", batchId);
            data.put("itemCount", itemCount);
            data.put("totalAmount", total);
            data.put("dryRun", dryRun ? "Y" : "N");
            data.put("generatedAt", now);

            legacyData.putAll(data);

            out.put("data", data);
            out.put("legacyData", legacyData);
            out.put("bankTransferBatchId", batchId);
            out.put("itemCount", itemCount);
            out.put("totalAmount", total);

            return out;

        } catch (Exception e) {
            Debug.logError(e, "generateBankTransferBatchV3 failed", MODULE);
            return errorNonNull("generateBankTransferBatchV3 failed: " + e.getMessage(), data, legacyData);
        }
    }

    private static Map<String, Object> errorNonNull(String msg, Map<String, Object> data, Map<String, Object> legacyData) {
        Map<String, Object> out = ServiceUtil.returnError(msg);
        out.put("data", data);
        out.put("legacyData", legacyData);
        return out;
    }

    public static Map<String, Object> approveBankTransferBatchV3(DispatchContext dctx, Map<String, Object> context) {
        Delegator delegator = dctx.getDelegator();

        Map<String, Object> data = new HashMap<>();
        Map<String, Object> legacyData = new HashMap<>();

        String batchId = (String) context.get("bankTransferBatchId");
        String approveNote = (String) context.get("approveNote");

        if (UtilValidate.isEmpty(batchId)) {
            return errorNonNull("bankTransferBatchId is required", data, legacyData);
        }

        try {
            GenericValue batch = EntityQuery.use(delegator)
                    .from("BankTransferBatch")
                    .where("bankTransferBatchId", batchId)
                    .queryOne();
            if (batch == null) {
                return errorNonNull("BankTransferBatch not found: " + batchId, data, legacyData);
            }

            String payrollRunId = batch.getString("payrollRunId");
            if (UtilValidate.isEmpty(payrollRunId)) {
                return errorNonNull("BankTransferBatch has no payrollRunId: " + batchId, data, legacyData);
            }

            GenericValue run = EntityQuery.use(delegator)
                    .from("PayrollRun")
                    .where("payrollRunId", payrollRunId)
                    .queryOne();
            if (run == null) {
                return errorNonNull("PayrollRun not found: " + payrollRunId, data, legacyData);
            }

            String organizationPartyId = run.getString("organizationPartyId");
            String payrollPeriodId = run.getString("payrollPeriodId");

            PayrollGovernanceUtil.ensureRunAndPeriodEditable(
                    delegator,
                    organizationPartyId,
                    payrollPeriodId,
                    payrollRunId
            );

            String st = batch.getString("statusId");
            if (!"BATCH_GEN".equals(st)) {
                return errorNonNull("Batch status not approvable: " + st, data, legacyData);
            }

            Timestamp now = UtilDateTime.nowTimestamp();
            batch.set("statusId", "BATCH_APPR");
            batch.set("approvedDateTime", now);

            GenericValue userLogin = (GenericValue) context.get("userLogin");
            if (userLogin != null) {
                batch.set("approvedByUserLoginId", userLogin.getString("userLoginId"));
            }

            if (UtilValidate.isNotEmpty(approveNote)) {
                batch.set("fileReference",
                        batch.getString("fileReference") == null ? "" : batch.getString("fileReference"));
            }

            batch.store();

            Map<String, Object> out = ServiceUtil.returnSuccess("###BANK_BATCH_APPROVE_V3### ok=1");
            data.put("bankTransferBatchId", batchId);
            data.put("payrollRunId", payrollRunId);
            data.put("organizationPartyId", organizationPartyId);
            data.put("payrollPeriodId", payrollPeriodId);
            data.put("statusAfter", "BATCH_APPR");
            data.put("approvedAt", now);

            legacyData.putAll(data);

            out.put("data", data);
            out.put("legacyData", legacyData);
            return out;

        } catch (Exception e) {
            Debug.logError(e, "approveBankTransferBatchV3 failed", MODULE);
            return errorNonNull("approveBankTransferBatchV3 failed: " + e.getMessage(), data, legacyData);
        }
    }

    public static Map<String, Object> exportBankTransferFileV3(DispatchContext dctx, Map<String, Object> context) {
        Delegator delegator = dctx.getDelegator();

        Map<String, Object> data = new HashMap<>();
        Map<String, Object> legacyData = new HashMap<>();

        String batchId = (String) context.get("bankTransferBatchId");
        String outDir = (String) context.get("outDir");
        String fileName = (String) context.get("fileName");
        boolean dryRun = "Y".equalsIgnoreCase((String) context.get("dryRun"));

        if (UtilValidate.isEmpty(batchId)) {
            return errorNonNull("bankTransferBatchId is required", data, legacyData);
        }
        if (UtilValidate.isEmpty(outDir)) {
            return errorNonNull("outDir is required", data, legacyData);
        }

        try {
            GenericValue batch = EntityQuery.use(delegator)
                    .from("BankTransferBatch")
                    .where("bankTransferBatchId", batchId)
                    .queryOne();
            if (batch == null) {
                return errorNonNull("BankTransferBatch not found: " + batchId, data, legacyData);
            }

            String payrollRunId = batch.getString("payrollRunId");
            if (UtilValidate.isEmpty(payrollRunId)) {
                return errorNonNull("BankTransferBatch has no payrollRunId: " + batchId, data, legacyData);
            }

            GenericValue run = EntityQuery.use(delegator)
                    .from("PayrollRun")
                    .where("payrollRunId", payrollRunId)
                    .queryOne();
            if (run == null) {
                return errorNonNull("PayrollRun not found: " + payrollRunId, data, legacyData);
            }

            String organizationPartyId = run.getString("organizationPartyId");
            String payrollPeriodId = run.getString("payrollPeriodId");

            PayrollGovernanceUtil.ensureRunAndPeriodEditable(
                    delegator,
                    organizationPartyId,
                    payrollPeriodId,
                    payrollRunId
            );

            String st = batch.getString("statusId");
            if (!"BATCH_APPR".equals(st) && !"BATCH_GEN".equals(st)) {
                return errorNonNull("Batch status not exportable: " + st, data, legacyData);
            }

            List<GenericValue> items = EntityQuery.use(delegator)
                    .from("BankTransferBatchItem")
                    .where("bankTransferBatchId", batchId)
                    .orderBy("sequenceNum ASC")
                    .queryList();
            if (items == null || items.isEmpty()) {
                return errorNonNull("No BankTransferBatchItem rows for batch=" + batchId, data, legacyData);
            }

            BigDecimal total = BigDecimal.ZERO;
            for (GenericValue it : items) {
                BigDecimal amt = it.getBigDecimal("amount");
                if (amt != null) {
                    total = total.add(amt);
                }
            }

            String fn = UtilValidate.isNotEmpty(fileName) ? fileName : ("BANK_" + batchId + ".csv");
            java.io.File dir = new java.io.File(outDir);
            java.io.File f = new java.io.File(dir, fn);

            if (!dryRun) {
                if (!dir.exists() && !dir.mkdirs()) {
                    return errorNonNull("Unable to create output directory: " + outDir, data, legacyData);
                }

                try (java.io.PrintWriter pw = new java.io.PrintWriter(
                        new java.io.OutputStreamWriter(
                                new java.io.FileOutputStream(f),
                                java.nio.charset.StandardCharsets.UTF_8))) {

                    pw.println("SEQ,PARTY_ID,BANK_NAME,BANK_ACCOUNT,AMOUNT,CURRENCY,REFERENCE");

                    for (GenericValue it : items) {
                        String seq = it.getString("sequenceNum");
                        String partyId = it.getString("partyId");
                        String bankName = safeCsv(it.getString("bankName"));
                        String acc = safeCsv(it.getString("bankAccountNumber"));
                        BigDecimal amt = it.getBigDecimal("amount");
                        String cur = it.getString("currencyUomId");
                        String ref = safeCsv(it.getString("reference"));

                        pw.println(
                                safeCsv(seq) + "," +
                                safeCsv(partyId) + "," +
                                bankName + "," +
                                acc + "," +
                                (amt == null ? "0" : amt.toPlainString()) + "," +
                                safeCsv(cur) + "," +
                                ref
                        );
                    }
                }

                batch.set("fileReference", f.getAbsolutePath());
                batch.set("statusId", "BATCH_EXPORTED");
                batch.store();
            }

            Map<String, Object> out = ServiceUtil.returnSuccess("###BANK_EXPORT_V3### ok=1");

            data.put("bankTransferBatchId", batchId);
            data.put("payrollRunId", payrollRunId);
            data.put("organizationPartyId", organizationPartyId);
            data.put("payrollPeriodId", payrollPeriodId);
            data.put("itemCount", (long) items.size());
            data.put("totalAmount", total);
            data.put("dryRun", dryRun ? "Y" : "N");
            data.put("fileName", fn);
            data.put("filePath", f.getAbsolutePath());
            data.put("fileReference", f.getAbsolutePath());

            legacyData.putAll(data);

            out.put("data", data);
            out.put("legacyData", legacyData);
            out.put("filePath", f.getAbsolutePath());
            out.put("fileReference", f.getAbsolutePath());
            out.put("itemCount", (long) items.size());
            out.put("totalAmount", total);

            return out;

        } catch (Exception e) {
            Debug.logError(e, "exportBankTransferFileV3 failed", MODULE);
            return errorNonNull("exportBankTransferFileV3 failed: " + e.getMessage(), data, legacyData);
        }
    }

    private static String safeCsv(String s) {
        if (s == null) {
            return "";
        }
        String v = s.replace("\"", "\"\"");
        if (v.contains(",") || v.contains("\n") || v.contains("\r")) {
            return "\"" + v + "\"";
        }
        return v;
    }
}