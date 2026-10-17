package org.apache.ofbiz.payroll.services;

import org.apache.ofbiz.base.util.Debug;
import org.apache.ofbiz.base.util.UtilDateTime;
import org.apache.ofbiz.base.util.UtilValidate;

import org.apache.ofbiz.entity.Delegator;
import org.apache.ofbiz.entity.GenericValue;
import org.apache.ofbiz.entity.GenericEntityException;
import org.apache.ofbiz.entity.util.EntityQuery;
import org.apache.ofbiz.entity.condition.EntityCondition;
import org.apache.ofbiz.entity.condition.EntityOperator;

import org.apache.ofbiz.service.DispatchContext;
import org.apache.ofbiz.service.ServiceUtil;

import java.io.File;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.util.*;
 

public class PayrollReadinessServices {
    public static final String MODULE = PayrollReadinessServices.class.getName();

    // --- Routing constants (align with your data / IDs) ---
    private static final String CH_BANK_FILE  = "BANK_FILE";
    private static final String CH_CASH       = "CASH";
    private static final String CH_CHEQUE     = "CHEQUE";
    private static final String CH_WALLET     = "WALLET_FILE";

    // PaymentMethodTypeId expectations (must exist in PaymentMethodType)
    private static final String PMT_EFT       = "EFT_ACCOUNT";
    private static final String PMT_CASH      = "CASH";
    private static final String PMT_CHQ       = "COMPANY_CHECK";
    // Wallet type is environment-specific; readiness will verify it exists.
    // Example user attempted: EXT_WALLET (but it's NOT in PaymentMethodType table in your DB)
    // We'll treat any wallet allocation as: pmType must exist (configured) + (optional) extra checks
    // You can set a default wallet payment method type you want:
    private static final String DEFAULT_WALLET_PMT = "EXT_WALLET";

    // Common: your allocation status
    private static final String ALLOC_ACTIVE  = "PAY_ALLOC_ACT";

    // ID length constraints (you asked max 20 chars)
    private static final int MAX_ID_LEN = 20;

    // ---------- Public Service ----------
 // add at top of file if missing:
 

public static Map<String, Object> runPayrollReadinessPack(DispatchContext dctx, Map<String, ?> context) {
    Delegator delegator = dctx.getDelegator();
    GenericValue userLogin = (GenericValue) context.get("userLogin");

    String payrollRunId = asString(context, "payrollRunId");
    String bankTransferConfigId = asString(context, "bankTransferConfigId"); // optional now
    String outDir = asString(context, "outDir");

    if (UtilValidate.isEmpty(payrollRunId)) return ServiceUtil.returnError("payrollRunId is required");
    if (userLogin == null) return ServiceUtil.returnError("userLogin is required");

    // Default outDir root
    if (UtilValidate.isEmpty(outDir)) {
        outDir = System.getProperty("ofbiz.home") + File.separator + "runtime" + File.separator + "tmp"
                + File.separator + "payroll_readiness_pack";
    }

    // Always write under a per-run folder to avoid collisions
    outDir = outDir + File.separator + payrollRunId;

    Map<String, Object> data = new HashMap<>();
    data.put("service", "runPayrollReadinessPack");
    data.put("payrollRunId", payrollRunId);
    data.put("bankTransferConfigId", bankTransferConfigId);
    data.put("outDir", outDir);
    data.put("generatedAt", UtilDateTime.nowTimestamp());

    try {
        File dir = new File(outDir);
        if (!dir.exists() && !dir.mkdirs()) {
            return withDataErr("Cannot create outDir=" + outDir, data);
        }

        Timestamp now = UtilDateTime.nowTimestamp();

        // 1) Run summary
        GenericValue run = EntityQuery.use(delegator)
                .from("PayrollRun")
                .where("payrollRunId", payrollRunId)
                .cache(false)
                .queryOne();
        if (run == null) return withDataErr("PayrollRun not found payrollRunId=" + payrollRunId, data);

        writeText(outDir, "01_payroll_run_summary.txt",
                "PAYROLL_RUN_ID=" + run.getString("payrollRunId") + "\n" +
                "ORG=" + run.getString("organizationPartyId") + "\n" +
                "PERIOD=" + run.getString("payrollPeriodId") + "\n" +
                "STATUS=" + run.getString("statusId") + "\n" +
                "CURRENCY=" + run.getString("currencyUomId") + "\n" +
                "RUN_TYPE_ID=" + nullToEmpty(run.getString("runTypeId")) + "\n" +
                "RUN_NAME=" + nullToEmpty(run.getString("runName")) + "\n" +
                "DISBURSEMENT_MODE_ID=" + nullToEmpty(run.getString("disbursementModeId")) + "\n" +
                "DEFAULT_DISBURSEMENT_CHANNEL_ID=" + nullToEmpty(run.getString("defaultDisbursementChannelId")) + "\n" +
                "IS_GL_POSTED=" + nullToEmpty(run.getString("isGlPosted")) + "\n" +
                "IS_INVOICE_POSTED=" + nullToEmpty(run.getString("isInvoicePosted")) + "\n" +
                "LAST_UPDATED=" + run.getTimestamp("lastUpdatedStamp") + "\n"
        );

        // 2) Config summary (optional)
        if (UtilValidate.isNotEmpty(bankTransferConfigId)) {
            GenericValue cfg = EntityQuery.use(delegator)
                    .from("BankTransferConfig")
                    .where("bankTransferConfigId", bankTransferConfigId)
                    .cache(false)
                    .queryOne();
            if (cfg == null) {
                writeText(outDir, "02_bank_transfer_config.txt",
                        "ERROR: BankTransferConfig not found id=" + bankTransferConfigId + "\n");
                data.put("bankTransferConfigMissing", Boolean.TRUE);
            } else {
                writeText(outDir, "02_bank_transfer_config.txt",
                        "BANK_TRANSFER_CONFIG_ID=" + cfg.getString("bankTransferConfigId") + "\n" +
                        "ORG=" + cfg.getString("organizationPartyId") + "\n" +
                        "BANK_NAME=" + cfg.getString("bankName") + "\n" +
                        "BANK_CODE=" + cfg.getString("bankCode") + "\n" +
                        "IS_ACTIVE=" + cfg.getString("isActive") + "\n" +
                        "STATUS=" + cfg.getString("statusId") + "\n" +
                        "HEADER_TEMPLATE=" + nullToEmpty(cfg.getString("headerTemplate")) + "\n" +
                        "DETAIL_TEMPLATE=" + nullToEmpty(cfg.getString("detailTemplate")) + "\n" +
                        "FOOTER_TEMPLATE=" + nullToEmpty(cfg.getString("footerTemplate")) + "\n" +
                        "FIELD_SEPARATOR=" + nullToEmpty(cfg.getString("fieldSeparator")) + "\n"
                );
                data.put("bankTransferConfigMissing", Boolean.FALSE);
            }
        } else {
            writeText(outDir, "02_bank_transfer_config.txt",
                    "INFO: bankTransferConfigId not provided.\n");
        }

        // 3) Results counts
        List<GenericValue> results = EntityQuery.use(delegator)
                .from("EmployeePayrollResult")
                .where("payrollRunId", payrollRunId)
                .cache(false)
                .queryList();

        long pos = 0, zero = 0, neg = 0;
        Set<String> partiesNetPos = new LinkedHashSet<>();
        Map<String, BigDecimal> netByParty = new LinkedHashMap<>();

        for (GenericValue r : results) {
            BigDecimal net = nz(r.getBigDecimal("netSalary"));
            if (net.signum() > 0) {
                pos++;
                String partyId = r.getString("partyId");
                partiesNetPos.add(partyId);
                netByParty.put(partyId, net);
            } else if (net.signum() < 0) {
                neg++;
            } else {
                zero++;
            }
        }

        writeText(outDir, "03_results_counts.txt",
                "net_pos_cnt=" + pos + "\nnet_zero_cnt=" + zero + "\nnet_neg_cnt=" + neg + "\ntotal_cnt=" + results.size() + "\n");

        // 4) Validate disbursement channels master exists
        List<String> missingChannelMaster = new ArrayList<>();
        for (String chId : Arrays.asList(CH_BANK_FILE, CH_CASH, CH_CHEQUE, CH_WALLET)) {
            if (!existsPayrollDisbursementChannel(delegator, chId)) {
                missingChannelMaster.add(chId);
            }
        }
        writeText(outDir, "06_missing_disbursement_channel_master.txt",
                "MISSING_CHANNEL_ID\n" + String.join("\n", missingChannelMaster) + "\n");
        data.put("missingDisbursementChannelMasterCount", missingChannelMaster.size());

        // 5) Routing matrix (from EmployeePaymentAllocation)
        List<String> matrixLines = new ArrayList<>();
        matrixLines.add("PARTY_ID\tNET_SALARY\tALLOC_ID\tCHANNEL\tPM_ID\tPM_TYPE\tIS_REMAINDER\tALLOC_TYPE\tALLOC_VALUE\tSTATUS");

        for (String partyId : partiesNetPos) {
            List<GenericValue> allocs = findActiveAllocations(delegator, partyId, now);

            if (allocs.isEmpty()) {
                matrixLines.add(partyId + "\t" + netByParty.get(partyId) + "\t\t\t\t\t\t\t\tMISSING_ALLOCATION");
                continue;
            }

            allocs.sort(Comparator.comparingLong(a ->
                    a.getLong("prioritySeq") == null ? Long.MAX_VALUE : a.getLong("prioritySeq")
            ));

            for (GenericValue a : allocs) {
                String allocId = a.getString("paymentAllocationId");
                String channel = a.getString("disbursementChannelId");
                String pmId = a.getString("paymentMethodId");
                String isRem = a.getString("isDefaultRemainder");
                String allocType = a.getString("allocationTypeEnumId");
                String allocVal = String.valueOf(a.get("allocationValue"));
                String st = a.getString("statusId");

                String pmType = "";
                if (UtilValidate.isNotEmpty(pmId)) {
                    GenericValue pm = EntityQuery.use(delegator).from("PaymentMethod")
                            .where("paymentMethodId", pmId).cache(false).queryOne();
                    if (pm != null) pmType = pm.getString("paymentMethodTypeId");
                }

                matrixLines.add(
                        partyId + "\t" + netByParty.get(partyId) + "\t" +
                        nullToEmpty(allocId) + "\t" +
                        nullToEmpty(channel) + "\t" +
                        nullToEmpty(pmId) + "\t" +
                        nullToEmpty(pmType) + "\t" +
                        nullToEmpty(isRem) + "\t" +
                        nullToEmpty(allocType) + "\t" +
                        nullToEmpty(allocVal) + "\t" +
                        nullToEmpty(st)
                );
            }
        }
        writeText(outDir, "04_payment_routing_matrix.txt", String.join("\n", matrixLines) + "\n");

        // 6) Issues list
        List<String> issues = new ArrayList<>();
        issues.add("SEVERITY\tCODE\tPARTY_ID\tALLOC_ID\tDETAIL");

        int missingAlloc = 0;
        int missingPm = 0;
        int missingPmType = 0;
        int missingEft = 0;
        int missingEftAcctNo = 0;
        int missingChannel = 0;
        int idLenWarn = 0;

        Set<String> paymentMethodTypes = loadPaymentMethodTypes(delegator);

        for (String partyId : partiesNetPos) {
            List<GenericValue> allocs = findActiveAllocations(delegator, partyId, now);

            if (allocs.isEmpty()) {
                missingAlloc++;
                issues.add("ERROR\tMISSING_ALLOCATION\t" + partyId + "\t\tNo active EmployeePaymentAllocation with statusId=" + ALLOC_ACTIVE);
                continue;
            }

            int remainderCnt = 0;

            for (GenericValue a : allocs) {
                String allocId = a.getString("paymentAllocationId");
                String channel = a.getString("disbursementChannelId");
                String pmId = a.getString("paymentMethodId");
                String isRem = a.getString("isDefaultRemainder");

                if ("Y".equalsIgnoreCase(isRem)) remainderCnt++;

                if (UtilValidate.isNotEmpty(allocId) && allocId.length() > MAX_ID_LEN) {
                    idLenWarn++;
                    issues.add("WARN\tID_TOO_LONG\t" + partyId + "\t" + allocId +
                            "\tpaymentAllocationId length=" + allocId.length() + " (> " + MAX_ID_LEN + ")");
                }
                if (UtilValidate.isNotEmpty(pmId) && pmId.length() > MAX_ID_LEN) {
                    idLenWarn++;
                    issues.add("WARN\tID_TOO_LONG\t" + partyId + "\t" + nullToEmpty(allocId) +
                            "\tpaymentMethodId length=" + pmId.length() + " (> " + MAX_ID_LEN + ")");
                }

                // disbursementChannelId required (allocation)
                if (UtilValidate.isEmpty(channel)) {
                    missingChannel++;
                    issues.add("ERROR\tMISSING_CHANNEL\t" + partyId + "\t" + nullToEmpty(allocId) +
                            "\tdisbursementChannelId is empty");
                    continue;
                }

                // Validate channel exists in PayrollDisbursementChannel (PK channelId)
                if (!existsPayrollDisbursementChannel(delegator, channel)) {
                    missingChannel++;
                    issues.add("ERROR\tUNKNOWN_CHANNEL\t" + partyId + "\t" + nullToEmpty(allocId) +
                            "\tdisbursementChannelId=" + channel + " not found in PayrollDisbursementChannel");
                }

                if (UtilValidate.isEmpty(pmId)) {
                    missingPm++;
                    issues.add("ERROR\tMISSING_PAYMENT_METHOD\t" + partyId + "\t" + nullToEmpty(allocId) +
                            "\tpaymentMethodId is empty (required for channel=" + channel + ")");
                    continue;
                }

                GenericValue pm = EntityQuery.use(delegator).from("PaymentMethod")
                        .where("paymentMethodId", pmId).cache(false).queryOne();
                if (pm == null) {
                    missingPm++;
                    issues.add("ERROR\tPAYMENT_METHOD_NOT_FOUND\t" + partyId + "\t" + nullToEmpty(allocId) +
                            "\tpaymentMethodId=" + pmId + " missing (FK breaker: EPAL_PM)");
                    continue;
                }

                String pmType = pm.getString("paymentMethodTypeId");
                if (UtilValidate.isEmpty(pmType)) {
                    missingPmType++;
                    issues.add("ERROR\tPAYMENT_METHOD_TYPE_EMPTY\t" + partyId + "\t" + nullToEmpty(allocId) +
                            "\tpaymentMethodId=" + pmId + " has empty paymentMethodTypeId");
                    continue;
                }

                if (!paymentMethodTypes.contains(pmType)) {
                    missingPmType++;
                    issues.add("ERROR\tPAYMENT_METHOD_TYPE_MISSING\t" + partyId + "\t" + nullToEmpty(allocId) +
                            "\tpaymentMethodTypeId=" + pmType + " not found in PaymentMethodType (FK PAYMETH_TYPE)");
                    continue;
                }

                // Channel-specific validation
                if (CH_BANK_FILE.equals(channel)) {
                    if (!PMT_EFT.equals(pmType)) {
                        issues.add("ERROR\tBANK_WRONG_PM_TYPE\t" + partyId + "\t" + nullToEmpty(allocId) +
                                "\tBANK_FILE requires paymentMethodTypeId=" + PMT_EFT + " but found " + pmType);
                        missingEft++;
                    } else {
                        GenericValue ea = EntityQuery.use(delegator).from("EftAccount")
                                .where("paymentMethodId", pmId).cache(false).queryOne();
                        if (ea == null) {
                            issues.add("ERROR\tEFT_ACCOUNT_MISSING\t" + partyId + "\t" + nullToEmpty(allocId) +
                                    "\tEftAccount missing for paymentMethodId=" + pmId);
                            missingEft++;
                        } else {
                            String acctNo = ea.getString("accountNumber");
                            if (UtilValidate.isEmpty(acctNo) || acctNo.trim().isEmpty()) {
                                issues.add("ERROR\tEFT_ACCOUNT_NO_NUMBER\t" + partyId + "\t" + nullToEmpty(allocId) +
                                        "\tEftAccount.accountNumber empty for paymentMethodId=" + pmId);
                                missingEftAcctNo++;
                            }
                        }
                    }
                }

                if (CH_CASH.equals(channel) && !PMT_CASH.equals(pmType)) {
                    issues.add("ERROR\tCASH_WRONG_PM_TYPE\t" + partyId + "\t" + nullToEmpty(allocId) +
                            "\tCASH requires paymentMethodTypeId=" + PMT_CASH + " but found " + pmType);
                }

                if (CH_CHEQUE.equals(channel) && !PMT_CHQ.equals(pmType)) {
                    issues.add("ERROR\tCHEQUE_WRONG_PM_TYPE\t" + partyId + "\t" + nullToEmpty(allocId) +
                            "\tCHEQUE requires paymentMethodTypeId=" + PMT_CHQ + " but found " + pmType);
                }
            }

            if (remainderCnt == 0) {
                issues.add("WARN\tNO_REMAINDER\t" + partyId + "\t\tNo isDefaultRemainder=Y allocation found (recommended exactly one)");
            } else if (remainderCnt > 1) {
                issues.add("WARN\tMULTI_REMAINDER\t" + partyId + "\t\tMultiple remainder allocations found count=" + remainderCnt + " (should be 1)");
            }
        }

        writeText(outDir, "05_routing_issues.txt", String.join("\n", issues) + "\n");

        // Summary stats in payload
        data.put("netPosCount", partiesNetPos.size());
        data.put("missingAllocationCount", missingAlloc);
        data.put("missingPaymentMethodCount", missingPm);
        data.put("missingPaymentMethodTypeCount", missingPmType);
        data.put("missingEftCount", missingEft);
        data.put("missingEftAccountNumberCount", missingEftAcctNo);
        data.put("missingDisbursementChannelCount", missingChannel);
        data.put("idLengthWarningsCount", idLenWarn);

        boolean ok = (missingAlloc == 0 && missingPm == 0 && missingPmType == 0
                && missingChannel == 0 && missingEft == 0 && missingEftAcctNo == 0);
        data.put("ok", ok);

        Map<String, Object> out = ServiceUtil.returnSuccess(
                ok ? ("Readiness pack generated at: " + outDir)
                   : ("Readiness generated with issues (see 05_routing_issues.txt) at: " + outDir)
        );
        out.put("data", data);
        return out;

    } catch (Exception e) {
        Debug.logError(e, "runPayrollReadinessPack failed", MODULE);
        Map<String, Object> err = ServiceUtil.returnError("runPayrollReadinessPack failed: " + safeMsg(e));
        data.put("ok", Boolean.FALSE);
        err.put("data", data);
        return err;
    }
}
    // ---------- Master checks ----------
 private static boolean existsPayrollDisbursementChannel(Delegator delegator, String channelId) throws GenericEntityException {
    if (UtilValidate.isEmpty(channelId)) return false;

    GenericValue ch = EntityQuery.use(delegator)
            .from("PayrollDisbursementChannel")
            .where("channelId", channelId)   // ✅ correct PK field
            .cache(false)
            .queryOne();

    return (ch != null);
}
    private static Set<String> loadPaymentMethodTypes(Delegator delegator) throws GenericEntityException {
        List<GenericValue> list = EntityQuery.use(delegator).from("PaymentMethodType")
                .select("paymentMethodTypeId")
                .cache(false).queryList();
        Set<String> s = new HashSet<>();
        for (GenericValue gv : list) {
            s.add(gv.getString("paymentMethodTypeId"));
        }
        return s;
    }

    // ---------- Allocation helpers ----------
    private static List<GenericValue> findActiveAllocations(Delegator delegator, String partyId, Timestamp now) throws GenericEntityException {
        // IMPORTANT: do NOT assume statusId = PAY_ALLOC_ACT in all environments.
        // Here we enforce it because your seed data uses PAY_ALLOC_ACT.
        // If you want more flexible: allow ACTIVE too.
        return EntityQuery.use(delegator).from("EmployeePaymentAllocation")
                .where("partyId", partyId, "statusId", ALLOC_ACTIVE)
                .filterByDate(now) // fromDate/thruDate
                .cache(false)
                .queryList();
    }

    // ---------- Small utils ----------
    private static Map<String, Object> withDataErr(String msg, Map<String, Object> data) {
        Map<String, Object> err = ServiceUtil.returnError(msg);
        err.put("data", data);
        return err;
    }

    private static void writeText(String outDir, String fileName, String content) throws Exception {
        File f = new File(outDir, fileName);
        try (FileOutputStream fos = new FileOutputStream(f)) {
            fos.write(content.getBytes(StandardCharsets.UTF_8));
        }
    }

    private static BigDecimal nz(BigDecimal v) { return v == null ? BigDecimal.ZERO : v; }

    private static String safeMsg(Exception e) {
        String m = e.getMessage();
        return (m != null) ? m : e.getClass().getName();
    }

    private static String asString(Map<String, ?> ctx, String key) {
        Object v = ctx.get(key);
        return v == null ? null : String.valueOf(v).trim();
    }

    private static String nullToEmpty(String v) {
        return v == null ? "" : v;
    }
}