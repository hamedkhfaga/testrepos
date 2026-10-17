package org.apache.ofbiz.payroll.services;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.math.BigDecimal;
import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.apache.ofbiz.base.util.Debug;
import org.apache.ofbiz.base.util.FileUtil;
import org.apache.ofbiz.base.util.UtilDateTime;
import org.apache.ofbiz.base.util.UtilFormatOut;
import org.apache.ofbiz.base.util.UtilMisc;
import org.apache.ofbiz.base.util.UtilValidate;
import org.apache.ofbiz.entity.Delegator;
import org.apache.ofbiz.entity.GenericEntityException;
import org.apache.ofbiz.entity.GenericValue;
import org.apache.ofbiz.entity.condition.EntityCondition;
import org.apache.ofbiz.entity.condition.EntityOperator;
import org.apache.ofbiz.entity.util.EntityQuery;
import org.apache.ofbiz.payroll.export.FopUtil;
import org.apache.ofbiz.service.DispatchContext;
import org.apache.ofbiz.service.LocalDispatcher;
import org.apache.ofbiz.service.ServiceUtil;

import com.lowagie.text.Chunk;
import com.lowagie.text.Document;
import com.lowagie.text.Font;
import com.lowagie.text.Image;
import com.lowagie.text.PageSize;
import com.lowagie.text.Paragraph;
import com.lowagie.text.Phrase;
import com.lowagie.text.pdf.Barcode128;
import com.lowagie.text.pdf.PdfPCell;
import com.lowagie.text.pdf.PdfPTable;
import com.lowagie.text.pdf.PdfWriter;

public final class PayrollPayslipServices {

    public static final String MODULE = PayrollPayslipServices.class.getName();

    private static final String PAYSLIP_FMT_HTML = "PLS_HTML";
    private static final String PAYSLIP_FMT_PDF = "PLS_PDF";

    private static final String PAYSLIP_STATUS_DRAFT = "PPS_DRAFT";
    private static final String PAYSLIP_STATUS_GEN = "PPS_GEN";
    private static final String PAYSLIP_STATUS_ERR = "PPS_ERR";

    private static final String AUDIT_ACTION_PAYSLIP_GEN = "PA_ACT_PAYSLIP_GEN";

    private static final String MIME_HTML = "text/html";
    private static final String MIME_PDF = "application/pdf";

    private static final String LINE_TYPE_STRUCT = "PRL_STRUCT";
    private static final String LINE_TYPE_OT = "PRL_OT";
    private static final String LINE_TYPE_ADD = "PRL_ADD";
    private static final String LINE_TYPE_BON = "PRL_BON";
    private static final String LINE_TYPE_DED = "PRL_DED";
    private static final String LINE_TYPE_TAX = "PRL_TAX";
    private static final String LINE_TYPE_LOAN = "PRL_LOAN";
    private static final String LINE_TYPE_SS = "PRL_SS";

    private PayrollPayslipServices() {}

    /* ============================================================
       Stage 4.2A - New services
       ============================================================ */

    public static Map<String, Object> getEmployeePayslipDataV1(DispatchContext dctx, Map<String, ?> context) {
        Delegator delegator = dctx.getDelegator();

        String payrollResultId = (String) context.get("payrollResultId");
        String payrollRunId = (String) context.get("payrollRunId");
        String partyId = (String) context.get("partyId");
        String organizationPartyId = (String) context.get("organizationPartyId");

        try {
            GenericValue payrollResult = resolvePayrollResult(delegator, payrollResultId, payrollRunId, partyId, organizationPartyId);
            if (payrollResult == null) {
                return errorWithPayload("EmployeePayrollResult not found for supplied criteria");
            }

            Map<String, Object> payload = buildPayslipPayload(delegator, payrollResult);

            Map<String, Object> out = ServiceUtil.returnSuccess("###PAYSLIP_DATA_V1###");
            out.put("data", payload);
            out.put("legacyData", new HashMap<String, Object>(payload));
            return out;

        } catch (Exception e) {
            Debug.logError(e, "getEmployeePayslipDataV1 failed", MODULE);
            return errorWithPayload("getEmployeePayslipDataV1 failed: " + e.getMessage());
        }
    }

    public static Map<String, Object> generateEmployeePayslipV1(DispatchContext dctx, Map<String, ?> context) {
        Delegator delegator = dctx.getDelegator();
        LocalDispatcher dispatcher = dctx.getDispatcher();

        GenericValue userLogin = (GenericValue) context.get("userLogin");
        String payrollResultId = (String) context.get("payrollResultId");
        String payrollRunId = (String) context.get("payrollRunId");
        String partyId = (String) context.get("partyId");
        String organizationPartyId = (String) context.get("organizationPartyId");
        String payslipFormatEnumId = UtilValidate.isEmpty((String) context.get("payslipFormatEnumId"))
                ? PAYSLIP_FMT_HTML
                : (String) context.get("payslipFormatEnumId");
        boolean forceRegenerate = "Y".equalsIgnoreCase((String) context.get("forceRegenerate"));

        try {
            GenericValue payrollResult = resolvePayrollResult(delegator, payrollResultId, payrollRunId, partyId, organizationPartyId);
            if (payrollResult == null) {
                return errorWithPayload("EmployeePayrollResult not found for supplied criteria");
            }

            payrollResultId = payrollResult.getString("payrollResultId");
            payrollRunId = payrollResult.getString("payrollRunId");
            partyId = payrollResult.getString("partyId");
            organizationPartyId = payrollResult.getString("organizationPartyId");

            GenericValue existing = null;
            if (entityExists(delegator, "PayrollPayslip")) {
                existing = EntityQuery.use(delegator)
                        .from("PayrollPayslip")
                        .where("payrollResultId", payrollResultId,
                               "payslipFormatEnumId", payslipFormatEnumId,
                               "statusId", PAYSLIP_STATUS_GEN)
                        .queryFirst();
            }

            if (existing != null && !forceRegenerate) {
                Map<String, Object> data = buildPayslipRecordPayload(existing);
                Map<String, Object> out = ServiceUtil.returnSuccess("###PAYSLIP_ALREADY_EXISTS###");
                out.put("payslipId", existing.getString("payslipId"));
                out.put("contentId", existing.getString("contentId"));
                out.put("dataResourceId", existing.getString("dataResourceId"));
                out.put("data", data);
                out.put("legacyData", new HashMap<String, Object>(data));
                return out;
            }

            Map<String, Object> payload = buildPayslipPayload(delegator, payrollResult);
            String html = renderPayslipHtml(payload);

            Timestamp nowTs = UtilDateTime.nowTimestamp();
            String fileName = "PAYSLIP_" + payrollRunId + "_" + partyId
                    + (PAYSLIP_FMT_PDF.equals(payslipFormatEnumId) ? ".pdf" : ".html");

            String exportFileId = null;
            byte[] binaryContent;
            String mimeTypeId;

            if (PAYSLIP_FMT_PDF.equals(payslipFormatEnumId)) {
                List<GenericValue> lines = EntityQuery.use(delegator)
                        .from("EmployeePayrollResultLine")
                        .where("payrollResultId", payrollResultId)
                        .orderBy("sequenceNum")
                        .queryList();

                binaryContent = buildOpenPdf(payrollResult, lines);
                mimeTypeId = MIME_PDF;
            } else {
                binaryContent = html.getBytes("UTF-8");
                mimeTypeId = MIME_HTML;
            }

            if (entityExists(delegator, "PayrollExportFile")) {
                exportFileId = storeExportFile(delegator, fileName, mimeTypeId, binaryContent);
            }

            String payslipId = null;
            if (entityExists(delegator, "PayrollPayslip")) {
                payslipId = upsertPayrollPayslip(delegator, existing, payrollResult, userLogin, payslipFormatEnumId,
                        mimeTypeId, fileName, nowTs, exportFileId, html);
            }

            writeAuditBestEffort(delegator, dispatcher, userLogin,
                    AUDIT_ACTION_PAYSLIP_GEN,
                    payrollRunId,
                    safeGetString(payrollResult, "payrollPeriodId"),
                    organizationPartyId,
                    partyId,
                    "PayrollPayslip",
                    payslipId,
                    "SUCCESS",
                    "Payslip generated format=" + payslipFormatEnumId,
                    null);

            Map<String, Object> data = new LinkedHashMap<String, Object>();
            data.put("payslipId", payslipId);
            data.put("payrollResultId", payrollResultId);
            data.put("payrollRunId", payrollRunId);
            data.put("partyId", partyId);
            data.put("organizationPartyId", organizationPartyId);
            data.put("payslipFormatEnumId", payslipFormatEnumId);
            data.put("statusId", PAYSLIP_STATUS_GEN);
            data.put("contentId", null);
            data.put("dataResourceId", null);
            data.put("mimeTypeId", mimeTypeId);
            data.put("fileName", fileName);
            data.put("exportFileId", exportFileId);
            data.put("htmlContent", PAYSLIP_FMT_HTML.equals(payslipFormatEnumId) ? html : null);

            Map<String, Object> out = ServiceUtil.returnSuccess("###PAYSLIP_GENERATED###");
            out.put("payslipId", payslipId);
            out.put("contentId", null);
            out.put("dataResourceId", null);
            out.put("data", data);
            out.put("legacyData", new HashMap<String, Object>(data));
            return out;

        } catch (Exception e) {
            Debug.logError(e, "generateEmployeePayslipV1 failed", MODULE);
            writeAuditBestEffort(delegator, dispatcher, userLogin,
                    AUDIT_ACTION_PAYSLIP_GEN,
                    payrollRunId,
                    null,
                    organizationPartyId,
                    partyId,
                    "PayrollPayslip",
                    null,
                    "ERROR",
                    null,
                    e.getMessage());
            return errorWithPayload("generateEmployeePayslipV1 failed: " + e.getMessage());
        }
    }

    public static Map<String, Object> generatePayrollRunPayslipsV1(DispatchContext dctx, Map<String, ?> context) {
        Delegator delegator = dctx.getDelegator();

        String payrollRunId = (String) context.get("payrollRunId");
        String organizationPartyId = (String) context.get("organizationPartyId");
        String payslipFormatEnumId = UtilValidate.isEmpty((String) context.get("payslipFormatEnumId"))
                ? PAYSLIP_FMT_HTML
                : (String) context.get("payslipFormatEnumId");
        String forceRegenerate = (String) context.get("forceRegenerate");
        GenericValue userLogin = (GenericValue) context.get("userLogin");

        if (UtilValidate.isEmpty(payrollRunId)) {
            return errorWithPayload("payrollRunId is required");
        }

        long generatedCount = 0L;
        long skippedCount = 0L;
        long errorCount = 0L;
        List<Map<String, Object>> errors = new ArrayList<Map<String, Object>>();

        try {
            EntityCondition cond = EntityCondition.makeCondition("payrollRunId", payrollRunId);
            if (UtilValidate.isNotEmpty(organizationPartyId)) {
                cond = EntityCondition.makeCondition(cond, EntityOperator.AND,
                        EntityCondition.makeCondition("organizationPartyId", organizationPartyId));
            }

            List<GenericValue> results = EntityQuery.use(delegator)
                    .from("EmployeePayrollResult")
                    .where(cond)
                    .orderBy("partyId")
                    .queryList();

            for (GenericValue result : results) {
                String rowPayrollResultId = result.getString("payrollResultId");
                String rowPartyId = result.getString("partyId");

                try {
                    if (entityExists(delegator, "PayrollPayslip") && !"Y".equalsIgnoreCase(forceRegenerate)) {
                        GenericValue existing = EntityQuery.use(delegator)
                                .from("PayrollPayslip")
                                .where("payrollResultId", rowPayrollResultId,
                                       "payslipFormatEnumId", payslipFormatEnumId,
                                       "statusId", PAYSLIP_STATUS_GEN)
                                .queryFirst();
                        if (existing != null) {
                            skippedCount++;
                            continue;
                        }
                    }

                    Map<String, Object> oneCtx = new HashMap<String, Object>();
                    oneCtx.put("payrollResultId", rowPayrollResultId);
                    oneCtx.put("organizationPartyId", result.getString("organizationPartyId"));
                    oneCtx.put("payslipFormatEnumId", payslipFormatEnumId);
                    oneCtx.put("forceRegenerate", forceRegenerate);
                    oneCtx.put("userLogin", userLogin);

                    Map<String, Object> oneResp = generateEmployeePayslipV1(dctx, oneCtx);
                    if (ServiceUtil.isError(oneResp)) {
                        errorCount++;
                        Map<String, Object> err = new LinkedHashMap<String, Object>();
                        err.put("payrollResultId", rowPayrollResultId);
                        err.put("partyId", rowPartyId);
                        err.put("message", ServiceUtil.getErrorMessage(oneResp));
                        errors.add(err);
                    } else {
                        generatedCount++;
                    }
                } catch (Exception inner) {
                    errorCount++;
                    Map<String, Object> err = new LinkedHashMap<String, Object>();
                    err.put("payrollResultId", rowPayrollResultId);
                    err.put("partyId", rowPartyId);
                    err.put("message", inner.getMessage());
                    errors.add(err);
                }
            }

            Map<String, Object> data = new LinkedHashMap<String, Object>();
            data.put("payrollRunId", payrollRunId);
            data.put("organizationPartyId", organizationPartyId);
            data.put("payslipFormatEnumId", payslipFormatEnumId);
            data.put("generatedCount", Long.valueOf(generatedCount));
            data.put("skippedCount", Long.valueOf(skippedCount));
            data.put("errorCount", Long.valueOf(errorCount));
            data.put("errors", errors);

            Map<String, Object> out = ServiceUtil.returnSuccess("###PAYSLIP_BATCH_GENERATED### generated=" + generatedCount);
            out.put("generatedCount", Long.valueOf(generatedCount));
            out.put("skippedCount", Long.valueOf(skippedCount));
            out.put("errorCount", Long.valueOf(errorCount));
            out.put("data", data);
            out.put("legacyData", new HashMap<String, Object>(data));
            return out;

        } catch (Exception e) {
            Debug.logError(e, "generatePayrollRunPayslipsV1 failed", MODULE);
            return errorWithPayload("generatePayrollRunPayslipsV1 failed: " + e.getMessage());
        }
    }

    /* ============================================================
       Existing methods kept
       ============================================================ */

    public static Map<String, Object> generatePayslipBarcode(DispatchContext dctx, Map<String, ?> context) {
        Delegator delegator = dctx.getDelegator();
        String payrollResultId = (String) context.get("payrollResultId");
        String barcodeTypeId = (String) context.get("barcodeTypeId");
        if (UtilValidate.isEmpty(payrollResultId)) return ServiceUtil.returnError("payrollResultId is required");
        if (UtilValidate.isEmpty(barcodeTypeId)) barcodeTypeId = "CODE128";

        try {
            GenericValue res = EntityQuery.use(delegator).from("EmployeePayrollResult")
                    .where("payrollResultId", payrollResultId).queryOne();
            if (res == null) return ServiceUtil.returnError("EmployeePayrollResult not found: " + payrollResultId);

            String payrollRunId = res.getString("payrollRunId");
            String barcodeValue = payrollRunId + "|" + payrollResultId;

            GenericValue existing = null;
            if (entityExists(delegator, "PayslipBarcode")) {
                existing = EntityQuery.use(delegator).from("PayslipBarcode")
                        .where("payrollResultId", payrollResultId).queryFirst();
            }

            Timestamp now = UtilDateTime.nowTimestamp();
            String payslipBarcodeId = null;
            if (existing == null) {
                if (!entityExists(delegator, "PayslipBarcode")) {
                    return ServiceUtil.returnError("PayslipBarcode entity is not available");
                }
                payslipBarcodeId = delegator.getNextSeqId("PayslipBarcode");
                GenericValue b = delegator.makeValue("PayslipBarcode");
                b.set("payslipBarcodeId", payslipBarcodeId);
                b.set("payrollResultId", payrollResultId);
                b.set("barcodeValue", barcodeValue);
                b.set("barcodeTypeId", barcodeTypeId);
                b.set("generatedDate", now);
                b.set("scanCount", Long.valueOf(0));
                delegator.create(b);
            } else {
                payslipBarcodeId = existing.getString("payslipBarcodeId");
                existing.set("barcodeValue", barcodeValue);
                existing.set("barcodeTypeId", barcodeTypeId);
                existing.set("generatedDate", now);
                delegator.store(existing);
            }

            Map<String, Object> out = ServiceUtil.returnSuccess();
            out.put("payslipBarcodeId", payslipBarcodeId);
            out.put("barcodeValue", barcodeValue);
            return out;

        } catch (Exception e) {
            Debug.logError(e, "generatePayslipBarcode failed", MODULE);
            return ServiceUtil.returnError("generatePayslipBarcode failed: " + e.getMessage());
        }
    }

    public static Map<String, Object> generatePayslipPdfOpenPdf(DispatchContext dctx, Map<String, ?> context) {
        Delegator delegator = dctx.getDelegator();

        String payrollResultId = (String) context.get("payrollResultId");
        String fileName = (String) context.get("fileName");

        if (UtilValidate.isEmpty(payrollResultId)) return ServiceUtil.returnError("payrollResultId is required");
        if (UtilValidate.isEmpty(fileName)) fileName = "Payslip_" + payrollResultId + ".pdf";

        try {
            GenericValue res = EntityQuery.use(delegator).from("EmployeePayrollResult")
                    .where("payrollResultId", payrollResultId).queryOne();
            if (res == null) return ServiceUtil.returnError("EmployeePayrollResult not found: " + payrollResultId);

            generatePayslipBarcode(dctx, UtilMisc.toMap("payrollResultId", payrollResultId, "userLogin", context.get("userLogin")));

            List<GenericValue> lines = EntityQuery.use(delegator).from("EmployeePayrollResultLine")
                    .where("payrollResultId", payrollResultId)
                    .orderBy("sequenceNum")
                    .queryList();

            byte[] pdfBytes = buildOpenPdf(res, lines);

            String exportFileId = storeExportFile(delegator, fileName, MIME_PDF, pdfBytes);

            Map<String, Object> out = ServiceUtil.returnSuccess();
            out.put("exportFileId", exportFileId);
            return out;

        } catch (Exception e) {
            Debug.logError(e, "generatePayslipPdfOpenPdf failed", MODULE);
            return ServiceUtil.returnError("generatePayslipPdfOpenPdf failed: " + e.getMessage());
        }
    }

    public static Map<String, Object> generatePayslipPdfFop(DispatchContext dctx, Map<String, ?> context) {
        Delegator delegator = dctx.getDelegator();

        String payrollResultId = (String) context.get("payrollResultId");
        String foTemplatePath = (String) context.get("foTemplatePath");
        String fileName = (String) context.get("fileName");
        if (UtilValidate.isEmpty(payrollResultId)) return ServiceUtil.returnError("payrollResultId is required");
        if (UtilValidate.isEmpty(foTemplatePath)) return ServiceUtil.returnError("foTemplatePath is required");
        if (UtilValidate.isEmpty(fileName)) fileName = "Payslip_" + payrollResultId + "_FOP.pdf";

        try {
            GenericValue res = EntityQuery.use(delegator).from("EmployeePayrollResult")
                    .where("payrollResultId", payrollResultId).queryOne();
            if (res == null) return ServiceUtil.returnError("EmployeePayrollResult not found: " + payrollResultId);

            String fo = FileUtil.readString("UTF-8", new File(foTemplatePath));
            fo = fo.replace("${payrollResultId}", nvlString(payrollResultId))
                   .replace("${payrollRunId}", nvlString(res.getString("payrollRunId")))
                   .replace("${partyId}", nvlString(res.getString("partyId")))
                   .replace("${netSalary}", String.valueOf(nvl(res.getBigDecimal("netSalary"))));

            byte[] pdfBytes = FopUtil.foToPdfBytes(fo);
            String exportFileId = storeExportFile(delegator, fileName, MIME_PDF, pdfBytes);

            Map<String, Object> out = ServiceUtil.returnSuccess();
            out.put("exportFileId", exportFileId);
            return out;

        } catch (Exception e) {
            Debug.logError(e, "generatePayslipPdfFop failed", MODULE);
            return ServiceUtil.returnError("generatePayslipPdfFop failed: " + e.getMessage());
        }
    }

    /* ============================================================
       Shared helpers
       ============================================================ */

    private static GenericValue resolvePayrollResult(Delegator delegator,
                                                     String payrollResultId,
                                                     String payrollRunId,
                                                     String partyId,
                                                     String organizationPartyId) throws GenericEntityException {
        if (UtilValidate.isNotEmpty(payrollResultId)) {
            return EntityQuery.use(delegator)
                    .from("EmployeePayrollResult")
                    .where("payrollResultId", payrollResultId)
                    .queryFirst();
        }

        if (UtilValidate.isEmpty(payrollRunId) || UtilValidate.isEmpty(partyId)) {
            return null;
        }

        EntityCondition cond = EntityCondition.makeCondition(UtilMisc.toList(
                EntityCondition.makeCondition("payrollRunId", payrollRunId),
                EntityCondition.makeCondition("partyId", partyId)
        ), EntityOperator.AND);

        if (UtilValidate.isNotEmpty(organizationPartyId)) {
            cond = EntityCondition.makeCondition(cond, EntityOperator.AND,
                    EntityCondition.makeCondition("organizationPartyId", organizationPartyId));
        }

        return EntityQuery.use(delegator)
                .from("EmployeePayrollResult")
                .where(cond)
                .queryFirst();
    }

    private static Map<String, Object> buildPayslipPayload(Delegator delegator, GenericValue payrollResult) throws GenericEntityException {
        String payrollResultId = payrollResult.getString("payrollResultId");
        String partyId = payrollResult.getString("partyId");
        String currencyUomId = safeGetString(payrollResult, "currencyUomId");

        GenericValue person = null;
        if (entityExists(delegator, "Person")) {
            person = EntityQuery.use(delegator).from("Person").where("partyId", partyId).queryFirst();
        }

        String employeeName = buildPartyDisplayName(person, partyId);

        List<GenericValue> lines = EntityQuery.use(delegator)
                .from("EmployeePayrollResultLine")
                .where("payrollResultId", payrollResultId)
                .orderBy("sequenceNum", "lineTypeId", "referenceId")
                .queryList();

        List<Map<String, Object>> earningLines = new ArrayList<Map<String, Object>>();
        List<Map<String, Object>> deductionLines = new ArrayList<Map<String, Object>>();
        List<Map<String, Object>> infoLines = new ArrayList<Map<String, Object>>();

        for (GenericValue line : lines) {
            Map<String, Object> row = new LinkedHashMap<String, Object>();
            row.put("lineTypeId", line.getString("lineTypeId"));
            row.put("referenceId", line.getString("referenceId"));
            row.put("description", line.getString("description"));
            row.put("amount", nvl(line.getBigDecimal("amount")));
            row.put("currencyUomId", line.getString("currencyUomId"));
            row.put("sequenceNum", line.get("sequenceNum"));

          String bucket = classifyResultLine(line.getString("lineTypeId"), line.getString("referenceId"));
            if ("EARNING".equals(bucket)) {
                earningLines.add(row);
            } else if ("DEDUCTION".equals(bucket)) {
                deductionLines.add(row);
            } else {
                infoLines.add(row);
            }
        }

        Map<String, Object> payload = new LinkedHashMap<String, Object>();
        payload.put("payrollResultId", payrollResultId);
        payload.put("payrollRunId", payrollResult.getString("payrollRunId"));
        payload.put("payrollPeriodId", safeGetString(payrollResult, "payrollPeriodId"));
        payload.put("partyId", partyId);
        payload.put("organizationPartyId", payrollResult.getString("organizationPartyId"));
        payload.put("employeeName", employeeName);
        payload.put("currencyUomId", currencyUomId);

        payload.put("basicSalary", nvl(payrollResult.getBigDecimal("basicSalary")));
        payload.put("overtimeAmount", nvl(payrollResult.getBigDecimal("overtimeAmount")));
        payload.put("allowancesTotal", nvl(payrollResult.getBigDecimal("allowancesTotal")));
        payload.put("bonuses", nvl(payrollResult.getBigDecimal("bonuses")));
        payload.put("grossSalary", nvl(payrollResult.getBigDecimal("grossSalary")));
        payload.put("incomeTax", nvl(payrollResult.getBigDecimal("incomeTax")));
        payload.put("socialSecurity", nvl(payrollResult.getBigDecimal("socialSecurity")));
        payload.put("loanDeductions", nvl(payrollResult.getBigDecimal("loanDeductions")));
        payload.put("otherDeductions", nvl(payrollResult.getBigDecimal("otherDeductions")));
        payload.put("totalDeductions", nvl(payrollResult.getBigDecimal("totalDeductions")));
        payload.put("netSalary", nvl(payrollResult.getBigDecimal("netSalary")));

        payload.put("paymentMethodId", safeGetString(payrollResult, "paymentMethodId"));
        payload.put("finAccountId", safeGetString(payrollResult, "finAccountId"));
        payload.put("bankAccountNumber", safeGetString(payrollResult, "bankAccountNumber"));
        payload.put("bankName", safeGetString(payrollResult, "bankName"));
        payload.put("disbursementChannelId", safeGetString(payrollResult, "disbursementChannelId"));
        payload.put("paymentPreferenceSeqNum", safeGetString(payrollResult, "paymentPreferenceSeqNum"));
        payload.put("statusId", safeGetString(payrollResult, "statusId"));

        payload.put("earningLines", earningLines);
        payload.put("deductionLines", deductionLines);
        payload.put("infoLines", infoLines);

        return payload;
    }

    private static Map<String, Object> buildPayslipRecordPayload(GenericValue payslip) {
        Map<String, Object> data = new LinkedHashMap<String, Object>();
        data.put("payslipId", payslip.getString("payslipId"));
        data.put("payrollResultId", payslip.getString("payrollResultId"));
        data.put("payrollRunId", payslip.getString("payrollRunId"));
        data.put("partyId", payslip.getString("partyId"));
        data.put("organizationPartyId", payslip.getString("organizationPartyId"));
        data.put("payslipFormatEnumId", payslip.getString("payslipFormatEnumId"));
        data.put("statusId", payslip.getString("statusId"));
        data.put("contentId", payslip.getString("contentId"));
        data.put("dataResourceId", payslip.getString("dataResourceId"));
        data.put("mimeTypeId", payslip.getString("mimeTypeId"));
        data.put("fileName", payslip.getString("fileName"));
        data.put("notes", payslip.getString("notes"));
        return data;
    }

    private static String upsertPayrollPayslip(Delegator delegator,
                                               GenericValue existing,
                                               GenericValue payrollResult,
                                               GenericValue userLogin,
                                               String payslipFormatEnumId,
                                               String mimeTypeId,
                                               String fileName,
                                               Timestamp nowTs,
                                               String exportFileId,
                                               String html) throws GenericEntityException {
        GenericValue payslip = existing;
        if (payslip == null) {
            payslip = delegator.makeValue("PayrollPayslip");
            payslip.set("payslipId", delegator.getNextSeqId("PayrollPayslip"));
            payslip.set("payrollRunId", payrollResult.getString("payrollRunId"));
            payslip.set("payrollResultId", payrollResult.getString("payrollResultId"));
            payslip.set("organizationPartyId", payrollResult.getString("organizationPartyId"));
            payslip.set("partyId", payrollResult.getString("partyId"));
            payslip.set("payslipFormatEnumId", payslipFormatEnumId);
            payslip.set("statusId", PAYSLIP_STATUS_DRAFT);
        }

        if (hasField(payslip, "contentId")) {
            payslip.set("contentId", null);
        }
        if (hasField(payslip, "dataResourceId")) {
            payslip.set("dataResourceId", null);
        }
        if (hasField(payslip, "mimeTypeId")) {
            payslip.set("mimeTypeId", mimeTypeId);
        }
        if (hasField(payslip, "fileName")) {
            payslip.set("fileName", fileName);
        }
        if (hasField(payslip, "versionNumber")) {
            Object current = payslip.get("versionNumber");
            if (current == null) {
                payslip.set("versionNumber", BigDecimal.ONE);
            }
        }
        if (hasField(payslip, "generatedDate")) {
            payslip.set("generatedDate", nowTs);
        }
        if (hasField(payslip, "generatedByUserLoginId")) {
            payslip.set("generatedByUserLoginId", userLogin != null ? userLogin.getString("userLoginId") : null);
        }
        if (hasField(payslip, "notes")) {
            String noteText = html;
            if (UtilValidate.isNotEmpty(exportFileId)) {
                noteText = "exportFileId=" + exportFileId + "\n" + html;
            }
            payslip.set("notes", noteText);
        }
        if (hasField(payslip, "statusId")) {
            payslip.set("statusId", PAYSLIP_STATUS_GEN);
        }

        if (existing == null) {
            delegator.create(payslip);
        } else {
            delegator.store(payslip);
        }
        return payslip.getString("payslipId");
    }

private static String classifyResultLine(String lineTypeId, String referenceId) {
    if (UtilValidate.isEmpty(lineTypeId)) {
        return "INFO";
    }

    String ref = referenceId == null ? "" : referenceId.toUpperCase();

    // Snapshot / operational metrics
    if (ref.startsWith("SNAP_")) {
        return "INFO";
    }

    // Structure-only non-posted components: keep as informational
    if ("P_LN_TAX".equals(ref) || "P_LN_SSE".equals(ref) || "P_LN_SS".equals(ref)) {
        return "INFO";
    }

    // Known earning-side structure components
    if ("P_LN_BAS".equals(ref) || "P_LN_HOU".equals(ref) || "P_LN_TRA".equals(ref) || "P_LN_ALL".equals(ref)) {
        return "EARNING";
    }

    // Actual earning lines
    if (LINE_TYPE_OT.equals(lineTypeId)
            || LINE_TYPE_ADD.equals(lineTypeId)
            || LINE_TYPE_BON.equals(lineTypeId)) {
        return "EARNING";
    }

    // Actual deduction lines only
    if (LINE_TYPE_DED.equals(lineTypeId)
            || LINE_TYPE_TAX.equals(lineTypeId)
            || LINE_TYPE_LOAN.equals(lineTypeId)
            || LINE_TYPE_SS.equals(lineTypeId)) {
        return "DEDUCTION";
    }

    // Remaining structure lines default to info, not deduction
    if (LINE_TYPE_STRUCT.equals(lineTypeId)) {
        return "INFO";
    }

    if (lineTypeId.startsWith("PRL_DED") || lineTypeId.startsWith("PRL_TAX")) {
        return "DEDUCTION";
    }

    if (lineTypeId.startsWith("PRL_")) {
        return "INFO";
    }

    return "INFO";
}


    private static String renderPayslipHtml(Map<String, Object> payload) {
        String currency = stringVal(payload.get("currencyUomId"));
        String employeeName = escapeHtml(stringVal(payload.get("employeeName")));
        String partyId = escapeHtml(stringVal(payload.get("partyId")));
        String payrollRunId = escapeHtml(stringVal(payload.get("payrollRunId")));
        String payrollPeriodId = escapeHtml(stringVal(payload.get("payrollPeriodId")));
        String paymentMethodId = escapeHtml(stringVal(payload.get("paymentMethodId")));
        String disbursementChannelId = escapeHtml(stringVal(payload.get("disbursementChannelId")));

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> earningLines = (List<Map<String, Object>>) payload.get("earningLines");
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> deductionLines = (List<Map<String, Object>>) payload.get("deductionLines");

        StringBuilder sb = new StringBuilder(4096);
        sb.append("<html><head><meta charset=\"UTF-8\"/>");
        sb.append("<title>Payslip</title>");
        sb.append("<style>");
        sb.append("body{font-family:Arial,Helvetica,sans-serif;font-size:12px;color:#222;margin:24px;}");
        sb.append("h2,h3{margin:8px 0;}");
        sb.append("table{width:100%;border-collapse:collapse;margin-top:8px;}");
        sb.append("th,td{border:1px solid #ccc;padding:6px;text-align:left;}");
        sb.append(".summary td{font-weight:bold;}");
        sb.append(".meta{margin-bottom:14px;}");
        sb.append("</style></head><body>");

        sb.append("<h2>Payslip</h2>");
        sb.append("<div class='meta'>");
        sb.append("<p><strong>Employee:</strong> ").append(employeeName).append(" (").append(partyId).append(")</p>");
        sb.append("<p><strong>Payroll Run:</strong> ").append(payrollRunId).append("</p>");
        sb.append("<p><strong>Payroll Period:</strong> ").append(payrollPeriodId).append("</p>");
        sb.append("<p><strong>Payment Method:</strong> ").append(paymentMethodId).append("</p>");
        sb.append("<p><strong>Disbursement Channel:</strong> ").append(disbursementChannelId).append("</p>");
        sb.append("</div>");

        sb.append("<table class='summary'>");
        sb.append("<tr><td>Basic Salary</td><td>").append(formatAmt(payload.get("basicSalary"), currency)).append("</td></tr>");
        sb.append("<tr><td>Overtime</td><td>").append(formatAmt(payload.get("overtimeAmount"), currency)).append("</td></tr>");
        sb.append("<tr><td>Allowances</td><td>").append(formatAmt(payload.get("allowancesTotal"), currency)).append("</td></tr>");
        sb.append("<tr><td>Bonuses</td><td>").append(formatAmt(payload.get("bonuses"), currency)).append("</td></tr>");
        sb.append("<tr><td>Gross Salary</td><td>").append(formatAmt(payload.get("grossSalary"), currency)).append("</td></tr>");
        sb.append("<tr><td>Total Deductions</td><td>").append(formatAmt(payload.get("totalDeductions"), currency)).append("</td></tr>");
        sb.append("<tr><td>Net Salary</td><td>").append(formatAmt(payload.get("netSalary"), currency)).append("</td></tr>");
        sb.append("</table>");

        sb.append("<h3>Earnings</h3>");
        sb.append("<table>");
        sb.append("<tr><th>Type</th><th>Reference</th><th>Description</th><th>Amount</th></tr>");
        if (earningLines != null) {
            for (Map<String, Object> row : earningLines) {
                sb.append("<tr>");
                sb.append("<td>").append(escapeHtml(stringVal(row.get("lineTypeId")))).append("</td>");
                sb.append("<td>").append(escapeHtml(stringVal(row.get("referenceId")))).append("</td>");
                sb.append("<td>").append(escapeHtml(stringVal(row.get("description")))).append("</td>");
                sb.append("<td>").append(formatAmt(row.get("amount"), currency)).append("</td>");
                sb.append("</tr>");
            }
        }
        sb.append("</table>");

        sb.append("<h3>Deductions</h3>");
        sb.append("<table>");
        sb.append("<tr><th>Type</th><th>Reference</th><th>Description</th><th>Amount</th></tr>");
        if (deductionLines != null) {
            for (Map<String, Object> row : deductionLines) {
                sb.append("<tr>");
                sb.append("<td>").append(escapeHtml(stringVal(row.get("lineTypeId")))).append("</td>");
                sb.append("<td>").append(escapeHtml(stringVal(row.get("referenceId")))).append("</td>");
                sb.append("<td>").append(escapeHtml(stringVal(row.get("description")))).append("</td>");
                sb.append("<td>").append(formatAmt(row.get("amount"), currency)).append("</td>");
                sb.append("</tr>");
            }
        }
        sb.append("</table>");

        sb.append("</body></html>");
        return sb.toString();
    }

    private static byte[] buildOpenPdf(GenericValue res, List<GenericValue> lines) throws Exception {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        Document doc = new Document(PageSize.A4, 36, 36, 36, 36);
        PdfWriter writer = PdfWriter.getInstance(doc, baos);
        doc.open();

        Font h1 = new Font(Font.HELVETICA, 16, Font.BOLD);
        Font normal = new Font(Font.HELVETICA, 10, Font.NORMAL);
        Font bold = new Font(Font.HELVETICA, 10, Font.BOLD);

        doc.add(new Paragraph("Payslip", h1));
        doc.add(new Paragraph("Payroll Run: " + res.getString("payrollRunId"), normal));
        doc.add(new Paragraph("Employee PartyId: " + res.getString("partyId"), normal));
        doc.add(new Paragraph("Currency: " + res.getString("currencyUomId"), normal));
        doc.add(Chunk.NEWLINE);

        PdfPTable t = new PdfPTable(new float[]{70, 30});
        t.setWidthPercentage(100);

        addCell(t, "Line", bold);
        addCell(t, "Amount", bold);

        for (GenericValue l : lines) {
            addCell(t, l.getString("description"), normal);
            addCell(t, String.valueOf(l.getBigDecimal("amount")), normal);
        }
        doc.add(t);

        doc.add(Chunk.NEWLINE);

        String barcodeText = res.getString("payrollRunId") + "|" + res.getString("payrollResultId");
        Barcode128 barcode = new Barcode128();
        barcode.setCode(barcodeText);
        barcode.setCodeType(Barcode128.CODE128);
        Image barcodeImg = barcode.createImageWithBarcode(writer.getDirectContent(), null, null);
        barcodeImg.scalePercent(120);
        doc.add(new Paragraph("Barcode:", bold));
        doc.add(barcodeImg);

        doc.close();
        writer.close();
        return baos.toByteArray();
    }

    private static void addCell(PdfPTable t, String txt, Font f) {
        PdfPCell c = new PdfPCell(new Phrase(txt == null ? "" : txt, f));
        c.setPadding(6);
        t.addCell(c);
    }

    private static String storeExportFile(Delegator delegator, String fileName, String mimeType, byte[] bytes) throws Exception {
        String exportFileId = delegator.getNextSeqId("PayrollExportFile");

        String dir = System.getProperty("ofbiz.home") + "/runtime/tmp/payroll";
        File fdir = new File(dir);
        if (!fdir.exists()) {
            fdir.mkdirs();
        }

        String filePath = dir + "/" + exportFileId + "_" + fileName;
        FileOutputStream fos = null;
        try {
            fos = new FileOutputStream(filePath);
            fos.write(bytes);
        } finally {
            if (fos != null) {
                fos.close();
            }
        }

        GenericValue file = delegator.makeValue("PayrollExportFile");
        file.set("exportFileId", exportFileId);
        file.set("exportRequestId", null);
        file.set("fileName", fileName);
        file.set("filePath", filePath);
        file.set("mimeTypeId", mimeType);
        file.set("fileSize", Long.valueOf(bytes.length));
        file.set("generatedDate", UtilDateTime.nowTimestamp());
        file.set("downloadCount", Long.valueOf(0));
        delegator.create(file);

        return exportFileId;
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
            if (hasField(audit, "payrollActionAuditId")) {
                audit.set("payrollActionAuditId", delegator.getNextSeqId("PayrollActionAudit"));
            } else if (hasField(audit, "auditId")) {
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

    private static boolean entityExists(Delegator delegator, String entityName) {
        try {
            return delegator.getModelEntity(entityName) != null;
        } catch (Exception e) {
            return false;
        }
    }

    private static boolean hasField(GenericValue gv, String fieldName) {
        return gv != null && gv.getModelEntity() != null && gv.getModelEntity().isField(fieldName);
    }

    private static void setIfFieldExists(GenericValue gv, String fieldName, Object value) {
        if (hasField(gv, fieldName)) {
            gv.set(fieldName, value);
        }
    }

    private static BigDecimal nvl(BigDecimal value) {
        return value != null ? value : BigDecimal.ZERO;
    }

    private static String safeGetString(GenericValue gv, String fieldName) {
        return hasField(gv, fieldName) ? gv.getString(fieldName) : null;
    }

    private static String buildPartyDisplayName(GenericValue person, String fallbackPartyId) {
        if (person == null) {
            return fallbackPartyId;
        }
        String firstName = person.getString("firstName");
        String middleName = person.getString("middleName");
        String lastName = person.getString("lastName");

        StringBuilder sb = new StringBuilder();
        if (UtilValidate.isNotEmpty(firstName)) {
            sb.append(firstName);
        }
        if (UtilValidate.isNotEmpty(middleName)) {
            if (sb.length() > 0) sb.append(" ");
            sb.append(middleName);
        }
        if (UtilValidate.isNotEmpty(lastName)) {
            if (sb.length() > 0) sb.append(" ");
            sb.append(lastName);
        }
        return sb.length() > 0 ? sb.toString() : fallbackPartyId;
    }

    private static String stringVal(Object value) {
        return value == null ? "" : String.valueOf(value);
    }

    private static String nvlString(String value) {
        return value == null ? "" : value;
    }

  private static String formatAmt(Object value, String currency) {
    BigDecimal bd;
    if (value instanceof BigDecimal) {
        bd = (BigDecimal) value;
    } else if (value == null || String.valueOf(value).trim().isEmpty()) {
        bd = BigDecimal.ZERO;
    } else {
        bd = new BigDecimal(String.valueOf(value));
    }

    try {
        return escapeHtml(UtilFormatOut.formatCurrency(bd, currency, null));
    } catch (Throwable t) {
        return escapeHtml(bd.toPlainString() + (UtilValidate.isNotEmpty(currency) ? " " + currency : ""));
    }
}
    private static String escapeHtml(String in) {
        if (in == null) return "";
        return in.replace("&", "&amp;")
                .replace("<", "&lt;")
                .replace(">", "&gt;")
                .replace("\"", "&quot;")
                .replace("'", "&#39;");
    }

    private static Map<String, Object> errorWithPayload(String msg) {
        Map<String, Object> out = ServiceUtil.returnError(msg);
        out.put("data", new HashMap<String, Object>());
        out.put("legacyData", new HashMap<String, Object>());
        return out;
    }
}