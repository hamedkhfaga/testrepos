package org.apache.ofbiz.payroll.services;

import org.apache.ofbiz.base.util.Debug;
import org.apache.ofbiz.base.util.UtilDateTime;
import org.apache.ofbiz.base.util.UtilMisc;
import org.apache.ofbiz.base.util.UtilValidate;
import org.apache.ofbiz.entity.Delegator;
import org.apache.ofbiz.entity.GenericEntityException;
import org.apache.ofbiz.entity.GenericValue;
import org.apache.ofbiz.entity.condition.EntityCondition;
import org.apache.ofbiz.entity.condition.EntityOperator;
import org.apache.ofbiz.entity.model.ModelEntity;
import org.apache.ofbiz.entity.util.EntityQuery;
import org.apache.ofbiz.service.DispatchContext;
import org.apache.ofbiz.service.LocalDispatcher;
import org.apache.ofbiz.service.ServiceUtil;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public class PayrollReconciliationServices {

    private static final String MODULE = PayrollReconciliationServices.class.getName();

    private static final String AUDIT_ACTION_RECON = "PA_ACT_RECON";
    private static final String RECON_OK = "RECON_OK";
    private static final String RECON_WARN = "RECON_WARN";
    private static final String RECON_FAIL = "RECON_FAIL";

    public static Map<String, Object> reconcilePayrollRunV1(DispatchContext dctx, Map<String, ?> context) {
        Delegator delegator = dctx.getDelegator();
        LocalDispatcher dispatcher = dctx.getDispatcher();

        GenericValue userLogin = (GenericValue) context.get("userLogin");
        String payrollRunId = (String) context.get("payrollRunId");
        String organizationPartyId = (String) context.get("organizationPartyId");
        BigDecimal toleranceAmount = (BigDecimal) context.get("toleranceAmount");
        if (toleranceAmount == null) {
            toleranceAmount = new BigDecimal("0.01");
        }

        if (UtilValidate.isEmpty(payrollRunId)) {
            return errorWithPayload("payrollRunId is required");
        }

        try {
            EntityCondition resultCond = EntityCondition.makeCondition("payrollRunId", payrollRunId);
            if (UtilValidate.isNotEmpty(organizationPartyId)) {
                resultCond = EntityCondition.makeCondition(resultCond, EntityOperator.AND,
                        EntityCondition.makeCondition("organizationPartyId", organizationPartyId));
            }

            List<GenericValue> results = EntityQuery.use(delegator)
                    .from("EmployeePayrollResult")
                    .where(resultCond)
                    .queryList();

            BigDecimal resultNetAmount = BigDecimal.ZERO;
            BigDecimal resultGrossAmount = BigDecimal.ZERO;
            BigDecimal resultDedAmount = BigDecimal.ZERO;
            int resultEmployeeCount = 0;

            for (GenericValue row : results) {
                resultEmployeeCount++;
                resultNetAmount = resultNetAmount.add(nvl(row.getBigDecimal("netSalary")));
                resultGrossAmount = resultGrossAmount.add(nvl(row.getBigDecimal("grossSalary")));
                resultDedAmount = resultDedAmount.add(nvl(row.getBigDecimal("totalDeductions")));
            }

            BigDecimal instructionAmount = BigDecimal.ZERO;
            BigDecimal bankInstructionAmount = BigDecimal.ZERO;
            BigDecimal nonBankInstructionAmount = BigDecimal.ZERO;
            int instructionRowCount = 0;
            List<String> instructionDistinctEmployees = new ArrayList<>();

            if (entityExists(delegator, "PayrollPaymentInstruction")) {
                EntityCondition instrCond = EntityCondition.makeCondition("payrollRunId", payrollRunId);
                if (UtilValidate.isNotEmpty(organizationPartyId) && fieldExists(delegator, "PayrollPaymentInstruction", "organizationPartyId")) {
                    instrCond = EntityCondition.makeCondition(instrCond, EntityOperator.AND,
                            EntityCondition.makeCondition("organizationPartyId", organizationPartyId));
                }

                List<GenericValue> instructions = EntityQuery.use(delegator)
                        .from("PayrollPaymentInstruction")
                        .where(instrCond)
                        .queryList();

                for (GenericValue row : instructions) {
                    instructionRowCount++;

                    String empPartyId = safeGetString(row, "partyId");
                    if (UtilValidate.isNotEmpty(empPartyId) && !instructionDistinctEmployees.contains(empPartyId)) {
                        instructionDistinctEmployees.add(empPartyId);
                    }

                    BigDecimal amt = firstBigDecimal(row, "amount", "paymentAmount", "netAmount");
                    instructionAmount = instructionAmount.add(amt);

                    String channel = firstString(row, "disbursementChannelId", "paymentMethodTypeId", "paymentMethodId");
                    if (isBankChannel(channel)) {
                        bankInstructionAmount = bankInstructionAmount.add(amt);
                    } else {
                        nonBankInstructionAmount = nonBankInstructionAmount.add(amt);
                    }
                }
            }

            BigDecimal bankBatchAmount = BigDecimal.ZERO;
            int batchCount = 0;
            int bankBatchRowCount = 0;
            List<String> bankDistinctEmployees = new ArrayList<>();

            List<String> bankBatchIds = findBankBatchIdsForRun(delegator, payrollRunId, organizationPartyId);
            batchCount = bankBatchIds.size();

            for (String batchId : bankBatchIds) {
                BatchScanResult scan = scanBankBatchAmounts(delegator, batchId);
                bankBatchAmount = bankBatchAmount.add(scan.totalAmount);
                bankBatchRowCount += scan.rowCount;
                for (String emp : scan.distinctEmployees) {
                    if (!bankDistinctEmployees.contains(emp)) {
                        bankDistinctEmployees.add(emp);
                    }
                }
            }

            List<String> issues = new ArrayList<>();
            List<String> warnings = new ArrayList<>();

            if (resultEmployeeCount == 0) {
                issues.add("No EmployeePayrollResult rows found for payrollRunId=" + payrollRunId);
            }

            if (instructionRowCount == 0) {
                warnings.add("No PayrollPaymentInstruction rows found for payrollRunId=" + payrollRunId);
            }

            if (!amountEquals(resultNetAmount, instructionAmount, toleranceAmount)) {
                issues.add("Result net total does not match payment instruction total: resultNet="
                        + resultNetAmount + ", instructionAmount=" + instructionAmount);
            }

            if (batchCount > 0 && !amountEquals(bankInstructionAmount, bankBatchAmount, toleranceAmount)) {
                issues.add("Bank instruction total does not match bank batch total: bankInstructionAmount="
                        + bankInstructionAmount + ", bankBatchAmount=" + bankBatchAmount);
            }

            if (instructionDistinctEmployees.size() > 0 && instructionDistinctEmployees.size() < resultEmployeeCount) {
                warnings.add("Instruction distinct employee count is less than result employee count: instructionEmployees="
                        + instructionDistinctEmployees.size() + ", resultEmployees=" + resultEmployeeCount);
            }

            if (batchCount > 0 && bankInstructionAmount.compareTo(BigDecimal.ZERO) == 0) {
                issues.add("Bank batch exists but bank payment instruction total is zero");
            }

            if (batchCount == 0 && bankInstructionAmount.compareTo(BigDecimal.ZERO) > 0) {
                warnings.add("Bank payment instructions exist but no bank batch was found");
            }

            String reconciliationStatus;
            if (!issues.isEmpty()) {
                reconciliationStatus = RECON_FAIL;
            } else if (!warnings.isEmpty()) {
                reconciliationStatus = RECON_WARN;
            } else {
                reconciliationStatus = RECON_OK;
            }

            Map<String, Object> totals = new LinkedHashMap<>();
            totals.put("resultGrossAmount", resultGrossAmount);
            totals.put("resultDeductionAmount", resultDedAmount);
            totals.put("resultNetAmount", resultNetAmount);
            totals.put("instructionAmount", instructionAmount);
            totals.put("bankInstructionAmount", bankInstructionAmount);
            totals.put("nonBankInstructionAmount", nonBankInstructionAmount);
            totals.put("bankBatchAmount", bankBatchAmount);
            totals.put("toleranceAmount", toleranceAmount);

            Map<String, Object> counts = new LinkedHashMap<>();
            counts.put("resultEmployeeCount", resultEmployeeCount);
            counts.put("instructionRowCount", instructionRowCount);
            counts.put("instructionEmployeeCount", instructionDistinctEmployees.size());
            counts.put("bankBatchCount", batchCount);
            counts.put("bankBatchRowCount", bankBatchRowCount);
            counts.put("bankEmployeeCount", bankDistinctEmployees.size());

            Map<String, Object> data = new LinkedHashMap<>();
            data.put("payrollRunId", payrollRunId);
            data.put("organizationPartyId", organizationPartyId);
            data.put("reconciliationStatus", reconciliationStatus);
            data.put("totals", totals);
            data.put("counts", counts);
            data.put("issues", issues);
            data.put("warnings", warnings);
            data.put("bankBatchIds", bankBatchIds);

            writeAuditBestEffort(delegator, dispatcher, userLogin,
                    AUDIT_ACTION_RECON,
                    payrollRunId,
                    null,
                    organizationPartyId,
                    null,
                    "PayrollRun",
                    payrollRunId,
                    reconciliationStatus,
                    "Reconciliation complete issues=" + issues.size() + ", warnings=" + warnings.size(),
                    issues.isEmpty() ? null : String.join(" | ", issues));

            Map<String, Object> out = ServiceUtil.returnSuccess("###PAYROLL_RECONCILE_V1### status=" + reconciliationStatus);
            out.put("reconciliationStatus", reconciliationStatus);
            out.put("data", data);
            out.put("legacyData", new HashMap<>(data));
            return out;

        } catch (Exception e) {
            Debug.logError(e, "reconcilePayrollRunV1 failed", MODULE);
            writeAuditBestEffort(delegator, dispatcher, userLogin,
                    AUDIT_ACTION_RECON,
                    payrollRunId,
                    null,
                    organizationPartyId,
                    null,
                    "PayrollRun",
                    payrollRunId,
                    "ERROR",
                    null,
                    e.getMessage());
            return errorWithPayload("reconcilePayrollRunV1 failed: " + e.getMessage());
        }
    }

    private static List<String> findBankBatchIdsForRun(Delegator delegator,
                                                       String payrollRunId,
                                                       String organizationPartyId) throws GenericEntityException {
        List<String> batchIds = new ArrayList<>();

        if (!entityExists(delegator, "BankTransferBatch")) {
            return batchIds;
        }

        EntityCondition cond = EntityCondition.makeCondition("payrollRunId", payrollRunId);
        if (UtilValidate.isNotEmpty(organizationPartyId) && fieldExists(delegator, "BankTransferBatch", "organizationPartyId")) {
            cond = EntityCondition.makeCondition(cond, EntityOperator.AND,
                    EntityCondition.makeCondition("organizationPartyId", organizationPartyId));
        }

        List<GenericValue> rows = EntityQuery.use(delegator)
                .from("BankTransferBatch")
                .where(cond)
                .queryList();

        for (GenericValue row : rows) {
            String batchId = firstString(row, "bankTransferBatchId", "batchId");
            if (UtilValidate.isNotEmpty(batchId) && !batchIds.contains(batchId)) {
                batchIds.add(batchId);
            }
        }
        return batchIds;
    }

    private static BatchScanResult scanBankBatchAmounts(Delegator delegator, String bankTransferBatchId) throws GenericEntityException {
        String[] candidateEntities = new String[] {
                "BankTransferBatchItem",
                "PayrollBankTransferBatchItem",
                "BankTransferInstruction",
                "PayrollBankTransferItem"
        };

        for (String entityName : candidateEntities) {
            if (!entityExists(delegator, entityName)) {
                continue;
            }
            if (!fieldExists(delegator, entityName, "bankTransferBatchId")
                    && !fieldExists(delegator, entityName, "batchId")) {
                continue;
            }

            String batchField = fieldExists(delegator, entityName, "bankTransferBatchId") ? "bankTransferBatchId" : "batchId";

            List<GenericValue> rows = EntityQuery.use(delegator)
                    .from(entityName)
                    .where(batchField, bankTransferBatchId)
                    .queryList();

            BatchScanResult result = new BatchScanResult();
            for (GenericValue row : rows) {
                result.rowCount++;
                result.totalAmount = result.totalAmount.add(firstBigDecimal(row, "amount", "paymentAmount", "netAmount"));
                String empPartyId = firstString(row, "partyId", "employeePartyId");
                if (UtilValidate.isNotEmpty(empPartyId) && !result.distinctEmployees.contains(empPartyId)) {
                    result.distinctEmployees.add(empPartyId);
                }
            }
            return result;
        }

        return new BatchScanResult();
    }

    private static boolean entityExists(Delegator delegator, String entityName) {
        try {
            ModelEntity me = delegator.getModelEntity(entityName);
            return me != null;
        } catch (Exception e) {
            return false;
        }
    }

    private static boolean fieldExists(Delegator delegator, String entityName, String fieldName) {
        try {
            ModelEntity me = delegator.getModelEntity(entityName);
            return me != null && me.isField(fieldName);
        } catch (Exception e) {
            return false;
        }
    }

    private static boolean amountEquals(BigDecimal a, BigDecimal b, BigDecimal tolerance) {
        return a.subtract(b).abs().compareTo(tolerance) <= 0;
    }

    private static boolean isBankChannel(String channel) {
        if (UtilValidate.isEmpty(channel)) {
            return false;
        }
        String v = channel.toUpperCase();
        return v.contains("BANK");
    }

    private static BigDecimal nvl(BigDecimal value) {
        return value != null ? value : BigDecimal.ZERO;
    }

    private static String safeGetString(GenericValue gv, String fieldName) {
        return gv.getModelEntity().isField(fieldName) ? gv.getString(fieldName) : null;
    }

    private static String firstString(GenericValue gv, String... fieldNames) {
        for (String f : fieldNames) {
            if (gv.getModelEntity().isField(f)) {
                String v = gv.getString(f);
                if (UtilValidate.isNotEmpty(v)) {
                    return v;
                }
            }
        }
        return null;
    }

    private static BigDecimal firstBigDecimal(GenericValue gv, String... fieldNames) {
        for (String f : fieldNames) {
            if (gv.getModelEntity().isField(f)) {
                Object v = gv.get(f);
                if (v instanceof BigDecimal) {
                    return (BigDecimal) v;
                }
                if (v != null) {
                    try {
                        return new BigDecimal(String.valueOf(v));
                    } catch (Exception ignored) {
                    }
                }
            }
        }
        return BigDecimal.ZERO;
    }

    private static void writeAuditBestEffort(Delegator delegator,
                                             LocalDispatcher dispatcher,
                                             GenericValue userLogin,
                                             String actionTypeEnumId,
                                             String payrollRunId,
                                             String payrollPeriodId,
                                             String organizationPartyId,
                                             String partyId,
                                             String entityName,
                                             String entityId,
                                             String actionStatusId,
                                             String responseSummary,
                                             String errorMessage) {
        try {
            if (!entityExists(delegator, "PayrollActionAudit")) {
                return;
            }

            GenericValue audit = delegator.makeValue("PayrollActionAudit");
            if (audit.getModelEntity().isField("payrollActionAuditId")) {
                audit.set("payrollActionAuditId", delegator.getNextSeqId("PayrollActionAudit"));
            } else if (audit.getModelEntity().isField("auditId")) {
                audit.set("auditId", delegator.getNextSeqId("PayrollActionAudit"));
            }

            setIfFieldExists(audit, "actionTypeEnumId", actionTypeEnumId);
            setIfFieldExists(audit, "payrollRunId", payrollRunId);
            setIfFieldExists(audit, "payrollPeriodId", payrollPeriodId);
            setIfFieldExists(audit, "organizationPartyId", organizationPartyId);
            setIfFieldExists(audit, "partyId", partyId);
            setIfFieldExists(audit, "entityName", entityName);
            setIfFieldExists(audit, "entityId", entityId);
            setIfFieldExists(audit, "actionStatusId", actionStatusId);
            setIfFieldExists(audit, "responseSummary", responseSummary);
            setIfFieldExists(audit, "errorMessage", errorMessage);
            setIfFieldExists(audit, "performedByUserLoginId", userLogin != null ? userLogin.getString("userLoginId") : null);
            setIfFieldExists(audit, "performedStamp", UtilDateTime.nowTimestamp());
            setIfFieldExists(audit, "createdStamp", UtilDateTime.nowTimestamp());
            setIfFieldExists(audit, "createdTxStamp", UtilDateTime.nowTimestamp());
            setIfFieldExists(audit, "lastUpdatedStamp", UtilDateTime.nowTimestamp());
            setIfFieldExists(audit, "lastUpdatedTxStamp", UtilDateTime.nowTimestamp());

            delegator.create(audit);
        } catch (Exception e) {
            Debug.logWarning("PayrollActionAudit write skipped: " + e.getMessage(), MODULE);
        }
    }

    private static void setIfFieldExists(GenericValue gv, String fieldName, Object value) {
        if (gv.getModelEntity().isField(fieldName)) {
            gv.set(fieldName, value);
        }
    }

    private static Map<String, Object> errorWithPayload(String msg) {
        Map<String, Object> out = ServiceUtil.returnError(msg);
        out.put("data", new HashMap<String, Object>());
        out.put("legacyData", new HashMap<String, Object>());
        return out;
    }

    private static class BatchScanResult {
        BigDecimal totalAmount = BigDecimal.ZERO;
        int rowCount = 0;
        List<String> distinctEmployees = new ArrayList<>();
    }
}