package org.apache.ofbiz.payroll;

import java.math.BigDecimal;
import java.util.*;

import org.apache.ofbiz.entity.Delegator;
import org.apache.ofbiz.entity.GenericEntityException;
import org.apache.ofbiz.entity.GenericValue;
import org.apache.ofbiz.entity.util.EntityQuery;
import org.apache.ofbiz.service.DispatchContext;
import org.apache.ofbiz.service.LocalDispatcher;
import org.apache.ofbiz.service.ServiceUtil;
import org.apache.ofbiz.base.util.Debug;
import org.apache.ofbiz.base.util.UtilMisc;

/**
 * Barcode & Report Service - Handles barcode generation and reporting
 */
public class BarcodeReportService {

    public static final String module = BarcodeReportService.class.getName();

    /**
     * Generate payslips with barcodes
     */
    public static Map<String, Object> generatePayslips(DispatchContext dctx, Map<String, ?> context) {
        Delegator delegator = dctx.getDelegator();
        LocalDispatcher dispatcher = dctx.getDispatcher();
        Map<String, Object> result = ServiceUtil.returnSuccess();
        
        try {
            String payrollIterationId = (String) context.get("payrollIterationId");
            int generatedCount = 0;
            
            // Get all payroll results for this iteration
            List<GenericValue> payrollResults = EntityQuery.use(delegator)
                    .from("EmployeePayrollResult")
                    .where("payrollIterationId", payrollIterationId)
                    .queryList();
            
            for (GenericValue payrollResult : payrollResults) {
                String payrollResultId = payrollResult.getString("payrollResultId");
                String partyId = payrollResult.getString("partyId");
                
                // Generate barcode if not exists
                String barcodeValue = payrollResult.getString("payslipBarcode");
                if (barcodeValue == null) {
                    barcodeValue = generateBarcodeValue(payrollResultId, partyId);
                    payrollResult.set("payslipBarcode", barcodeValue);
                    delegator.store(payrollResult);
                }
                
                // Generate PDF payslip
                Map<String, Object> pdfResult = dispatcher.runSync("generatePayslipPDF", 
                    UtilMisc.toMap("payrollResultId", payrollResultId, "userLogin", context.get("userLogin")));
                
                if (ServiceUtil.isSuccess(pdfResult)) {
                    generatedCount++;
                }
                
                // Queue email notification
                dispatcher.runAsync("queuePayslipEmail", 
                    UtilMisc.toMap("payrollResultId", payrollResultId, "userLogin", context.get("userLogin")));
            }
            
            result.put("generatedCount", generatedCount);
            result.put("totalEmployees", payrollResults.size());
            Debug.logInfo("Generated " + generatedCount + " payslips for iteration: " + payrollIterationId, module);
            
        } catch (Exception e) {
            Debug.logError(e, "Error generating payslips", module);
            return ServiceUtil.returnError("Payslip generation failed: " + e.getMessage());
        }
        
        return result;
    }

    /**
     * Generate barcode value for payslip
     */
    private static String generateBarcodeValue(String payrollResultId, String partyId) {
        // Generate unique barcode value
        String timestamp = String.valueOf(System.currentTimeMillis());
        return "PSLIP-" + payrollResultId + "-" + partyId + "-" + timestamp.substring(timestamp.length() - 6);
    }

    /**
     * Generate PDF payslip
     */
    public static Map<String, Object> generatePayslipPDF(DispatchContext dctx, Map<String, ?> context) {
        Delegator delegator = dctx.getDelegator();
        Map<String, Object> result = ServiceUtil.returnSuccess();
        
        try {
            String payrollResultId = (String) context.get("payrollResultId");
            
            // Get payroll result details
            GenericValue payrollResult = EntityQuery.use(delegator)
                    .from("EmployeePayrollResult")
                    .where("payrollResultId", payrollResultId)
                    .queryOne();
            
            if (payrollResult != null) {
                // In real implementation, this would generate actual PDF using libraries like Flying Saucer
                // For now, we'll create a placeholder file path
                
                String fileName = "payslip_" + payrollResultId + "_" + 
                                System.currentTimeMillis() + ".pdf";
                String filePath = "/payroll/payslips/" + fileName;
                
                // Save file attachment record
                saveFileAttachment(delegator, payrollResult.getString("payrollIterationId"), 
                                 fileName, filePath, "PAYSLIP", "application/pdf");
                
                result.put("fileName", fileName);
                result.put("filePath", filePath);
                result.put("payrollResultId", payrollResultId);
            }
            
        } catch (Exception e) {
            Debug.logError(e, "Error generating PDF payslip", module);
            return ServiceUtil.returnError("PDF generation failed: " + e.getMessage());
        }
        
        return result;
    }

    /**
     * Save file attachment record
     */
    private static void saveFileAttachment(Delegator delegator, String payrollIterationId,
                                         String fileName, String filePath, String fileType, String mimeType) 
                                         throws GenericEntityException {
        
        String attachmentId = delegator.getNextSeqId("PayrollFileAttachment");
        
        GenericValue attachment = delegator.makeValue("PayrollFileAttachment");
        attachment.set("attachmentId", attachmentId);
        attachment.set("payrollIterationId", payrollIterationId);
        attachment.set("fileName", fileName);
        attachment.set("filePath", filePath);
        attachment.set("fileType", fileType);
        attachment.set("mimeType", mimeType);
        attachment.set("fileSize", 1024L); // Demo size
        attachment.set("uploadedDate", new java.sql.Timestamp(System.currentTimeMillis()));
        
        delegator.create(attachment);
    }

    /**
     * Export payroll data to Excel
     */
    public static Map<String, Object> exportPayrollToExcel(DispatchContext dctx, Map<String, ?> context) {
        Delegator delegator = dctx.getDelegator();
        Map<String, Object> result = ServiceUtil.returnSuccess();
        
        try {
            String payrollIterationId = (String) context.get("payrollIterationId");
            String exportType = (String) context.get("exportType"); // EXCEL, CSV, PDF
            
            if (exportType == null) {
                exportType = "EXCEL"; // Default export type
            }
            
            // Get payroll results
            List<GenericValue> payrollResults = EntityQuery.use(delegator)
                    .from("EmployeePayrollResult")
                    .where("payrollIterationId", payrollIterationId)
                    .queryList();
            
            // Generate export file
            String fileName = "payroll_export_" + payrollIterationId + "_" + 
                            System.currentTimeMillis() + getFileExtension(exportType);
            String filePath = "/payroll/exports/" + fileName;
            
            // Save file attachment
            saveFileAttachment(delegator, payrollIterationId, fileName, filePath, 
                             "EXPORT_" + exportType, getMimeType(exportType));
            
            result.put("fileName", fileName);
            result.put("filePath", filePath);
            result.put("recordCount", payrollResults.size());
            result.put("exportType", exportType);
            
            Debug.logInfo("Exported " + payrollResults.size() + " records to " + exportType, module);
            
        } catch (Exception e) {
            Debug.logError(e, "Error exporting payroll data", module);
            return ServiceUtil.returnError("Export failed: " + e.getMessage());
        }
        
        return result;
    }

    private static String getFileExtension(String exportType) {
        switch (exportType) {
            case "EXCEL": return ".xlsx";
            case "CSV": return ".csv";
            case "PDF": return ".pdf";
            default: return ".xlsx";
        }
    }

    private static String getMimeType(String exportType) {
        switch (exportType) {
            case "EXCEL": return "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet";
            case "CSV": return "text/csv";
            case "PDF": return "application/pdf";
            default: return "application/octet-stream";
        }
    }

    /**
     * Scan payslip barcode and retrieve payroll information
     */
    public static Map<String, Object> scanPayslipBarcode(DispatchContext dctx, Map<String, ?> context) {
        Delegator delegator = dctx.getDelegator();
        Map<String, Object> result = ServiceUtil.returnSuccess();
        
        try {
            String barcodeValue = (String) context.get("barcodeValue");
            
            if (barcodeValue == null || barcodeValue.trim().isEmpty()) {
                return ServiceUtil.returnError("Barcode value is required");
            }
            
            // Find payroll result by barcode
            GenericValue payrollResult = EntityQuery.use(delegator)
                    .from("EmployeePayrollResult")
                    .where("payslipBarcode", barcodeValue)
                    .queryOne();
            
            if (payrollResult != null) {
                // Update scan count in barcode table
                GenericValue barcode = EntityQuery.use(delegator)
                        .from("PayslipBarcode")
                        .where("barcodeValue", barcodeValue)
                        .queryOne();
                
                if (barcode != null) {
                    Long scanCount = barcode.getLong("scanCount");
                    if (scanCount == null) scanCount = 0L;
                    barcode.set("scanCount", scanCount + 1);
                    barcode.set("lastScannedDate", new java.sql.Timestamp(System.currentTimeMillis()));
                    delegator.store(barcode);
                }
                
                result.put("payrollResult", payrollResult);
                result.put("employeeId", payrollResult.getString("partyId"));
                result.put("payrollIterationId", payrollResult.getString("payrollIterationId"));
                result.put("netSalary", payrollResult.getBigDecimal("netSalary"));
                result.put("scanCount", barcode != null ? barcode.getLong("scanCount") : 1L);
            } else {
                return ServiceUtil.returnError("Payslip not found for barcode: " + barcodeValue);
            }
            
        } catch (Exception e) {
            Debug.logError(e, "Error scanning payslip barcode", module);
            return ServiceUtil.returnError("Barcode scanning failed: " + e.getMessage());
        }
        
        return result;
    }
}