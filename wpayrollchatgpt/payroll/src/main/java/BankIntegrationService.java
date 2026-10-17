package org.apache.ofbiz.payroll;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Map;

import org.apache.ofbiz.entity.Delegator;
import org.apache.ofbiz.entity.GenericValue;
import org.apache.ofbiz.entity.util.EntityQuery;
import org.apache.ofbiz.service.DispatchContext;
import org.apache.ofbiz.service.ServiceUtil;
import org.apache.ofbiz.base.util.Debug;

/**
 * Bank Integration Service - Handles bank transfer file generation
 */
public class BankIntegrationService {

    public static final String MODULE = BankIntegrationService.class.getName();

    /**
     * Generate bank transfer files
     */
    public static Map<String, Object> generateBankTransferFiles(DispatchContext dctx, Map<String, ?> context) {
        Delegator delegator = dctx.getDelegator();
        Map<String, Object> result = ServiceUtil.returnSuccess();

        try {
            String payrollIterationId = (String) context.get("payrollIterationId");
            String bankConfigId = (String) context.get("bankConfigId");

            if (bankConfigId == null) {
                bankConfigId = "DEFAULT_BANK";
            }

            // Get payroll results
            List<GenericValue> payrollResults = EntityQuery.use(delegator)
                    .from("EmployeePayrollResult")
                    .where("payrollIterationId", payrollIterationId)
                    .queryList();

            // Get bank configuration
            GenericValue bankConfig = EntityQuery.use(delegator)
                    .from("BankTransferConfig")
                    .where("configId", bankConfigId)
                    .queryOne();

            if (bankConfig == null) {
                return ServiceUtil.returnError("Bank configuration not found: " + bankConfigId);
            }

            // Generate bank file content
            String fileContent = generateBankFileContent(payrollResults, bankConfig);
            String fileName = "bank_transfer_" + payrollIterationId
                    + "_" + System.currentTimeMillis() + ".txt";
            String filePath = "/payroll/bank/" + fileName;

            // Calculate total amount
            BigDecimal totalAmount = calculateTotalNetPay(payrollResults);

            // Save bank transfer batch
            String batchId = delegator.getNextSeqId("BankTransferBatch");
            GenericValue batch = delegator.makeValue("BankTransferBatch");
            batch.set("batchId", batchId);
            batch.set("payrollIterationId", payrollIterationId);
            batch.set("bankConfigId", bankConfigId);
            batch.set("totalAmount", totalAmount);
            batch.set("totalRecords", (long) payrollResults.size());
            batch.set("generatedFile", filePath);
            batch.set("generatedDate", new java.sql.Timestamp(System.currentTimeMillis()));
            batch.set("statusId", "GENERATED");
            delegator.create(batch);

            // Save file attachment
            String attachmentId = delegator.getNextSeqId("PayrollFileAttachment");
            GenericValue attachment = delegator.makeValue("PayrollFileAttachment");
            attachment.set("attachmentId", attachmentId);
            attachment.set("payrollIterationId", payrollIterationId);
            attachment.set("fileName", fileName);
            attachment.set("filePath", filePath);
            attachment.set("fileType", "BANK_FILE");
            attachment.set("mimeType", "text/plain");
            attachment.set("fileSize", (long) fileContent.length());
            attachment.set("uploadedDate", new java.sql.Timestamp(System.currentTimeMillis()));
            delegator.create(attachment);

            result.put("batchId", batchId);
            result.put("fileName", fileName);
            result.put("filePath", filePath);
            result.put("totalRecords", payrollResults.size());
            result.put("totalAmount", totalAmount);

            Debug.logInfo("Generated bank transfer file for " + payrollResults.size() + " employees", MODULE);

        } catch (Exception e) {
            Debug.logError(e, "Error generating bank transfer files", MODULE);
            return ServiceUtil.returnError("Bank file generation failed: " + e.getMessage());
        }

        return result;
    }

    /**
     * Generate bank file content based on configuration
     */
    private static String generateBankFileContent(List<GenericValue> payrollResults,
                                                  GenericValue bankConfig) {
        StringBuilder content = new StringBuilder();
        String fileFormat = bankConfig.getString("fileFormat");
        String fieldSeparator = bankConfig.getString("fieldSeparator");
        if (fieldSeparator == null) {
            fieldSeparator = "|";
        }

        // Add header
        String headerTemplate = bankConfig.getString("headerTemplate");
        if (headerTemplate != null) {
            content.append(headerTemplate.replace("{RECORD_COUNT}", String.valueOf(payrollResults.size()))
                    .replace("{DATE}", new Date().toString()));
            content.append("\n");
        }

        // Add detail records
        for (GenericValue result : payrollResults) {
            String detailTemplate = bankConfig.getString("detailTemplate");
            if (detailTemplate != null) {
                String record = detailTemplate
                        .replace("{EMPLOYEE_ID}", safeString(result.getString("partyId")))
                        .replace("{ACCOUNT_NUMBER}", safeString(result.getString("bankAccountNumber")))
                        .replace("{AMOUNT}", safeBigDecimal(result.getBigDecimal("netSalary")))
                        .replace("{BANK_NAME}", safeString(result.getString("bankName")));
                content.append(record).append("\n");
            } else {
                content.append(safeString(result.getString("partyId"))).append(fieldSeparator)
                        .append(safeString(result.getString("bankAccountNumber"))).append(fieldSeparator)
                        .append(safeBigDecimal(result.getBigDecimal("netSalary"))).append(fieldSeparator)
                        .append(safeString(result.getString("bankName"))).append("\n");
            }
        }

        // Add footer
        String footerTemplate = bankConfig.getString("footerTemplate");
        if (footerTemplate != null) {
            BigDecimal totalAmount = calculateTotalNetPay(payrollResults);
            content.append(footerTemplate.replace("{TOTAL_AMOUNT}", totalAmount.toString()));
        }

        return content.toString();
    }

    /**
     * Calculate total net pay from payroll results
     */
    private static BigDecimal calculateTotalNetPay(List<GenericValue> payrollResults) {
        BigDecimal total = BigDecimal.ZERO;
        for (GenericValue result : payrollResults) {
            BigDecimal netSalary = result.getBigDecimal("netSalary");
            if (netSalary != null) {
                total = total.add(netSalary);
            }
        }
        return total.setScale(2, RoundingMode.HALF_UP);
    }

    /**
     * Safe string conversion with null handling
     */
    private static String safeString(String value) {
        return value != null ? value : "";
    }

    /**
     * Safe BigDecimal conversion with null handling
     */
    private static String safeBigDecimal(BigDecimal value) {
        if (value != null) {
            return value.setScale(2, RoundingMode.HALF_UP).toString();
        }
        return "0.00";
    }

    /**
     * Validate bank account details
     */
    public static Map<String, Object> validateBankAccounts(DispatchContext dctx, Map<String, ?> context) {
        Delegator delegator = dctx.getDelegator();
        Map<String, Object> result = ServiceUtil.returnSuccess();

        try {
            String payrollIterationId = (String) context.get("payrollIterationId");
            List<String> invalidAccounts = new ArrayList<>();
            List<String> missingAccounts = new ArrayList<>();

            // Get payroll results
            List<GenericValue> payrollResults = EntityQuery.use(delegator)
                    .from("EmployeePayrollResult")
                    .where("payrollIterationId", payrollIterationId)
                    .queryList();

            for (GenericValue payrollResult : payrollResults) {
                String partyId = payrollResult.getString("partyId");
                String bankAccountNumber = payrollResult.getString("bankAccountNumber");

                if (bankAccountNumber == null || bankAccountNumber.trim().isEmpty()) {
                    missingAccounts.add(partyId);
                } else if (!isValidBankAccount(bankAccountNumber)) {
                    invalidAccounts.add(partyId + " (" + bankAccountNumber + ")");
                }
            }

            result.put("invalidAccounts", invalidAccounts);
            result.put("missingAccounts", missingAccounts);
            result.put("validAccountsCount", payrollResults.size() - invalidAccounts.size() - missingAccounts.size());
            result.put("isValid", invalidAccounts.isEmpty() && missingAccounts.isEmpty());

        } catch (Exception e) {
            Debug.logError(e, "Error validating bank accounts", MODULE);
            return ServiceUtil.returnError("Bank account validation failed: " + e.getMessage());
        }

        return result;
    }

    /**
     * Simple bank account validation
     */
    private static boolean isValidBankAccount(String accountNumber) {
        if (accountNumber == null || accountNumber.trim().isEmpty()) {
            return false;
        }

        String cleanAccount = accountNumber.replaceAll("[^0-9]", "");
        return cleanAccount.length() >= 8 && cleanAccount.length() <= 20;
    }
}
