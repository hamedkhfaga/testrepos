package org.apache.ofbiz.payroll;

import java.util.List;
import java.util.Map;

import org.apache.ofbiz.base.util.Debug;
import org.apache.ofbiz.base.util.UtilMisc;
import org.apache.ofbiz.entity.Delegator;
import org.apache.ofbiz.entity.GenericEntityException;
import org.apache.ofbiz.entity.GenericValue;
import org.apache.ofbiz.entity.util.EntityQuery;
import org.apache.ofbiz.service.DispatchContext;
import org.apache.ofbiz.service.LocalDispatcher;
import org.apache.ofbiz.service.ServiceUtil;

/**
 * Barcode & Report Service - Handles barcode generation and reporting
 */
public class BarcodeReportService {

    public static final String MODULE = BarcodeReportService.class.getName();

    public static Map<String, Object> generatePayslips(DispatchContext dctx, Map<String, ?> context) {
        Delegator delegator = dctx.getDelegator();
        LocalDispatcher dispatcher = dctx.getDispatcher();
        Map<String, Object> result = ServiceUtil.returnSuccess();

        try {
            String payrollIterationId = (String) context.get("payrollIterationId");
            int generatedCount = 0;

            List<GenericValue> payrollResults = EntityQuery.use(delegator)
                    .from("EmployeePayrollResult")
                    .where("payrollIterationId", payrollIterationId)
                    .queryList();

            for (GenericValue payrollResult : payrollResults) {
                String payrollResultId = payrollResult.getString("payrollResultId");
                String partyId = payrollResult.getString("partyId");

                String barcodeValue = payrollResult.getString("payslipBarcode");
                if (barcodeValue == null) {
                    barcodeValue = generateBarcodeValue(payrollResultId, partyId);
                    payrollResult.set("payslipBarcode", barcodeValue);
                    delegator.store(payrollResult);
                }

                Map<String, Object> pdfResult = dispatcher.runSync(
                        "generatePayslipPDF",
                        UtilMisc.toMap(
                                "payrollResultId", payrollResultId,
                                "userLogin", context.get("userLogin")));

                if (ServiceUtil.isSuccess(pdfResult)) {
                    generatedCount++;
                }

                dispatcher.runAsync(
                        "queuePayslipEmail",
                        UtilMisc.toMap(
                                "payrollResultId", payrollResultId,
                                "userLogin", context.get("userLogin")));
            }

            result.put("generatedCount", generatedCount);
            result.put("totalEmployees", payrollResults.size());

            Debug.logInfo(
                    "Generated " + generatedCount
                            + " payslips for iteration: "
                            + payrollIterationId,
                    MODULE);

        } catch (Exception e) {
            Debug.logError(e, "Error generating payslips", MODULE);
            return ServiceUtil.returnError("Payslip generation failed: " + e.getMessage());
        }

        return result;
    }

    private static String generateBarcodeValue(String payrollResultId, String partyId) {
        String timestamp = String.valueOf(System.currentTimeMillis());
        return "PSLIP-" + payrollResultId + "-" + partyId + "-"
                + timestamp.substring(timestamp.length() - 6);
    }

    public static Map<String, Object> generatePayslipPDF(DispatchContext dctx, Map<String, ?> context) {
        Delegator delegator = dctx.getDelegator();
        Map<String, Object> result = ServiceUtil.returnSuccess();

        try {
            String payrollResultId = (String) context.get("payrollResultId");

            GenericValue payrollResult = EntityQuery.use(delegator)
                    .from("EmployeePayrollResult")
                    .where("payrollResultId", payrollResultId)
                    .queryOne();

            if (payrollResult != null) {
                String fileName = "payslip_" + payrollResultId + "_"
                        + System.currentTimeMillis() + ".pdf";
                String filePath = "/payroll/payslips/" + fileName;

                saveFileAttachment(
                        delegator,
                        payrollResult.getString("payrollIterationId"),
                        fileName,
                        filePath,
                        "PAYSLIP",
                        "application/pdf");

                result.put("fileName", fileName);
                result.put("filePath", filePath);
                result.put("payrollResultId", payrollResultId);
            }

        } catch (Exception e) {
            Debug.logError(e, "Error generating PDF payslip", MODULE);
            return ServiceUtil.returnError("PDF generation failed: " + e.getMessage());
        }

        return result;
    }

    private static void saveFileAttachment(
            Delegator delegator,
            String payrollIterationId,
            String fileName,
            String filePath,
            String fileType,
            String mimeType) throws GenericEntityException {

        String attachmentId = delegator.getNextSeqId("PayrollFileAttachment");

        GenericValue attachment = delegator.makeValue("PayrollFileAttachment");
        attachment.set("attachmentId", attachmentId);
        attachment.set("payrollIterationId", payrollIterationId);
        attachment.set("fileName", fileName);
        attachment.set("filePath", filePath);
        attachment.set("fileType", fileType);
        attachment.set("mimeType", mimeType);
        attachment.set("fileSize", 1024L);
        attachment.set("uploadedDate", new java.sql.Timestamp(System.currentTimeMillis()));

        delegator.create(attachment);
    }

    public static Map<String, Object> exportPayrollToExcel(DispatchContext dctx, Map<String, ?> context) {
        Delegator delegator = dctx.getDelegator();
        Map<String, Object> result = ServiceUtil.returnSuccess();

        try {
            String payrollIterationId = (String) context.get("payrollIterationId");
            String exportType = (String) context.get("exportType");

            if (exportType == null) {
                exportType = "EXCEL";
            }

            List<GenericValue> payrollResults = EntityQuery.use(delegator)
                    .from("EmployeePayrollResult")
                    .where("payrollIterationId", payrollIterationId)
                    .queryList();

            String fileName = "payroll_export_" + payrollIterationId + "_"
                    + System.currentTimeMillis() + getFileExtension(exportType);
            String filePath = "/payroll/exports/" + fileName;

            saveFileAttachment(
                    delegator,
                    payrollIterationId,
                    fileName,
                    filePath,
                    "EXPORT_" + exportType,
                    getMimeType(exportType));

            result.put("fileName", fileName);
            result.put("filePath", filePath);
            result.put("recordCount", payrollResults.size());
            result.put("exportType", exportType);

            Debug.logInfo(
                    "Exported " + payrollResults.size()
                            + " records to " + exportType,
                    MODULE);

        } catch (Exception e) {
            Debug.logError(e, "Error exporting payroll data", MODULE);
            return ServiceUtil.returnError("Export failed: " + e.getMessage());
        }

        return result;
    }

    private static String getFileExtension(String exportType) {
        switch (exportType) {
        case "CSV":
            return ".csv";
        case "PDF":
            return ".pdf";
        case "EXCEL":
        default:
            return ".xlsx";
        }
    }

    private static String getMimeType(String exportType) {
        switch (exportType) {
        case "CSV":
            return "text/csv";
        case "PDF":
            return "application/pdf";
        case "EXCEL":
        default:
            return "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet";
        }
    }

    public static Map<String, Object> scanPayslipBarcode(DispatchContext dctx, Map<String, ?> context) {
        Delegator delegator = dctx.getDelegator();
        Map<String, Object> result = ServiceUtil.returnSuccess();

        try {
            String barcodeValue = (String) context.get("barcodeValue");

            if (barcodeValue == null || barcodeValue.trim().isEmpty()) {
                return ServiceUtil.returnError("Barcode value is required");
            }

            GenericValue payrollResult = EntityQuery.use(delegator)
                    .from("EmployeePayrollResult")
                    .where("payslipBarcode", barcodeValue)
                    .queryOne();

            if (payrollResult == null) {
                return ServiceUtil.returnError("Payslip not found for barcode: " + barcodeValue);
            }

            result.put("payrollResult", payrollResult);
            result.put("employeeId", payrollResult.getString("partyId"));
            result.put("payrollIterationId", payrollResult.getString("payrollIterationId"));
            result.put("netSalary", payrollResult.getBigDecimal("netSalary"));

        } catch (Exception e) {
            Debug.logError(e, "Error scanning payslip barcode", MODULE);
            return ServiceUtil.returnError("Barcode scanning failed: " + e.getMessage());
        }

        return result;
    }
}
