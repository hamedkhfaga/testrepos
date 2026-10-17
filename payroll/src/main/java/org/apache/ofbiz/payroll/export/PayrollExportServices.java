package org.apache.ofbiz.payroll.export;

import org.apache.ofbiz.base.util.Debug;
import org.apache.ofbiz.base.util.UtilDateTime;
//import org.apache.ofbiz.base.util.UtilMisc;
import org.apache.ofbiz.base.util.UtilValidate;
import org.apache.ofbiz.entity.Delegator;
import org.apache.ofbiz.entity.GenericEntityException;
import org.apache.ofbiz.entity.GenericValue;
import org.apache.ofbiz.entity.transaction.TransactionUtil;
import org.apache.ofbiz.entity.util.EntityQuery;
import org.apache.ofbiz.service.DispatchContext;
import org.apache.ofbiz.service.ServiceUtil;

// IMPORTANT: PayrollStrictRules is in services package
import org.apache.ofbiz.payroll.services.PayrollStrictRules;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.sql.Timestamp;
import java.util.Collections;
import java.util.List;
import java.util.Map;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

// OpenPDF
import com.lowagie.text.Chunk;
import com.lowagie.text.Document;
import com.lowagie.text.Element;
import com.lowagie.text.Font;
import com.lowagie.text.Image;
import com.lowagie.text.PageSize;
import com.lowagie.text.Paragraph;
import com.lowagie.text.Phrase;
import com.lowagie.text.pdf.Barcode128;
import com.lowagie.text.pdf.PdfContentByte;
import com.lowagie.text.pdf.PdfPCell;
import com.lowagie.text.pdf.PdfPTable;
import com.lowagie.text.pdf.PdfWriter;

// FOP
import org.apache.fop.apps.FOUserAgent;
import org.apache.fop.apps.Fop;
import org.apache.fop.apps.FopFactory;
import org.apache.fop.apps.MimeConstants;

import javax.xml.transform.Result;
import javax.xml.transform.Source;
import javax.xml.transform.Transformer;
import javax.xml.transform.TransformerFactory;
import javax.xml.transform.sax.SAXResult;
import javax.xml.transform.stream.StreamResult;
import javax.xml.transform.stream.StreamSource;

public class PayrollExportServices {
    public static final String MODULE = PayrollExportServices.class.getName();

    // Export request statuses
    public static final String EXP_NEW  = "EXP_REQ_NEW";
    public static final String EXP_PROC = "EXP_REQ_PROC";
    public static final String EXP_DONE = "EXP_REQ_DONE";
    public static final String EXP_ERR  = "EXP_REQ_ERR";

    // Format types (your formatTypeId values)
    public static final String FMT_CSV  = "CSV";
    public static final String FMT_HTML = "HTML";
    public static final String FMT_PDF  = "PDF";

    // Optional extended format ids you introduced
    public static final String FMT_PDF_OPENPDF = "PDF_OPENPDF";
    public static final String FMT_PDF_FOP     = "PDF_FOP";

    /**
     * Create Export Request
     */
    public static Map<String, Object> createPayrollExportRequest(DispatchContext dctx, Map<String, ?> context) {
        Delegator delegator = dctx.getDelegator();
        GenericValue userLogin = (GenericValue) context.get("userLogin");

        String orgPartyId = (String) context.get("orgPartyId");
        String payrollRunId = (String) context.get("payrollRunId");
        String payrollResultId = (String) context.get("payrollResultId");
        String exportTypeId = (String) context.get("exportTypeId");
        String formatTypeId = (String) context.get("formatTypeId");
        String requestedByUserLoginId = (String) context.get("requestedByUserLoginId");

        try {
            PayrollStrictRules.requireNotEmpty("orgPartyId", orgPartyId);
            PayrollStrictRules.requireNotEmpty("exportTypeId", exportTypeId);
            PayrollStrictRules.requireNotEmpty("formatTypeId", formatTypeId);

            if (UtilValidate.isEmpty(payrollRunId) && UtilValidate.isEmpty(payrollResultId)) {
                return ServiceUtil.returnError("Either payrollRunId or payrollResultId is required");
            }

            if (UtilValidate.isEmpty(requestedByUserLoginId) && userLogin != null) {
                requestedByUserLoginId = userLogin.getString("userLoginId");
            }
            PayrollStrictRules.requireNotEmpty("requestedByUserLoginId", requestedByUserLoginId);

            String exportRequestId = delegator.getNextSeqId("PayrollExportRequest");
            GenericValue req = delegator.makeValue("PayrollExportRequest");
            req.set("exportRequestId", exportRequestId);
            req.set("orgPartyId", orgPartyId);
            req.set("payrollRunId", payrollRunId);
            req.set("payrollResultId", payrollResultId);
            req.set("exportTypeId", exportTypeId);
            req.set("formatTypeId", formatTypeId);
            req.set("requestedByUserLoginId", requestedByUserLoginId);
            req.set("requestedDate", UtilDateTime.nowTimestamp());
            req.set("statusId", EXP_NEW);
            req.create();

            Map<String, Object> out = ServiceUtil.returnSuccess();
            out.put("exportRequestId", exportRequestId);
            return out;
        } catch (Exception e) {
            Debug.logError(e, "createPayrollExportRequest failed", MODULE);
            return ServiceUtil.returnError("createPayrollExportRequest failed: " + e.getMessage());
        }
    }

    /**
     * Worker: Process Export Request
     */
    public static Map<String, Object> processPayrollExportRequest(DispatchContext dctx, Map<String, ?> context) {
        Delegator delegator = dctx.getDelegator();
        String exportRequestId = (String) context.get("exportRequestId");

        boolean beganTx = false;
        try {
            PayrollStrictRules.requireNotEmpty("exportRequestId", exportRequestId);
            beganTx = TransactionUtil.begin();

            GenericValue req = EntityQuery.use(delegator)
                    .from("PayrollExportRequest")
                    .where("exportRequestId", exportRequestId)
                    .queryOne();
            if (req == null) return ServiceUtil.returnError("Export request not found: " + exportRequestId);

            String orgPartyId = req.getString("orgPartyId");
            PayrollStrictRules.requireNotEmpty("orgPartyId", orgPartyId);

            // idempotent
            if (EXP_DONE.equals(req.getString("statusId"))) {
                GenericValue existingFile = EntityQuery.use(delegator)
                        .from("PayrollExportFile")
                        .where("exportRequestId", exportRequestId)
                        .queryFirst();
                Map<String, Object> out = ServiceUtil.returnSuccess();
                out.put("exportFileId", existingFile == null ? null : existingFile.getString("exportFileId"));
                TransactionUtil.commit(beganTx);
                return out;
            }

            // mark processing
            req.set("statusId", EXP_PROC);
            req.store();

            String formatTypeId = req.getString("formatTypeId");
            String exportTypeId = req.getString("exportTypeId");
            String payrollRunId = req.getString("payrollRunId");
            String payrollResultId = req.getString("payrollResultId");

            ExportPayload payload = buildPayload(delegator, orgPartyId, exportTypeId, payrollRunId, payrollResultId);

            // generate once
            GeneratedFile gf = generateByFormat(formatTypeId, payload);

            // persist file
            String exportFileId = delegator.getNextSeqId("PayrollExportFile");
            String baseDir = System.getProperty("ofbiz.home")
                    + File.separator + "runtime"
                    + File.separator + "payroll-exports";
            new File(baseDir).mkdirs();

            String fileName = gf.fileName;
            String filePath = baseDir + File.separator + fileName;

            try (FileOutputStream fos = new FileOutputStream(filePath)) {
                fos.write(gf.bytes);
            }

            GenericValue file = delegator.makeValue("PayrollExportFile");
            file.set("exportFileId", exportFileId);
            file.set("exportRequestId", exportRequestId);
            file.set("fileName", fileName);
            file.set("filePath", filePath);
            file.set("mimeTypeId", gf.mimeTypeId);
            file.set("fileSize", (long) gf.bytes.length);
            file.set("generatedDate", UtilDateTime.nowTimestamp());
            file.set("downloadCount", 0L);
            file.set("checksum", gf.checksum);
            file.create();

            // done
            req.set("statusId", EXP_DONE);
            req.store();

            audit(delegator, orgPartyId, "PayrollExportRequest", exportRequestId, "PAY_AUD_EXPORT");

            Map<String, Object> out = ServiceUtil.returnSuccess();
            out.put("exportFileId", exportFileId);

            TransactionUtil.commit(beganTx);
            return out;

        } catch (Exception e) {
            try { if (beganTx) TransactionUtil.rollback(beganTx, e.getMessage(), e); } catch (Exception ignored) {}
            Debug.logError(e, "processPayrollExportRequest failed", MODULE);
            safeMarkExportError(delegator, exportRequestId, e.getMessage());
            return ServiceUtil.returnError("processPayrollExportRequest failed: " + e.getMessage());
        }
    }

    public static Map<String, Object> getPayrollExportFile(DispatchContext dctx, Map<String, ?> context) {
        Delegator delegator = dctx.getDelegator();
        String exportFileId = (String) context.get("exportFileId");
        try {
            PayrollStrictRules.requireNotEmpty("exportFileId", exportFileId);
            GenericValue file = EntityQuery.use(delegator)
                    .from("PayrollExportFile")
                    .where("exportFileId", exportFileId)
                    .queryOne();
            Map<String, Object> out = ServiceUtil.returnSuccess();
            out.put("exportFile", file == null ? null : file.getAllFields());
            return out;
        } catch (Exception e) {
            return ServiceUtil.returnError("getPayrollExportFile failed: " + e.getMessage());
        }
    }

    // =========================
    // Payload builder
    // =========================
    private static ExportPayload buildPayload(Delegator delegator, String orgPartyId, String exportTypeId,
                                             String payrollRunId, String payrollResultId) throws Exception {
        ExportPayload p = new ExportPayload();
        p.orgPartyId = orgPartyId;
        p.exportTypeId = exportTypeId;
        p.generatedAt = UtilDateTime.nowTimestamp();

        if (UtilValidate.isNotEmpty(payrollResultId)) {
            GenericValue res = EntityQuery.use(delegator)
                    .from("EmployeePayrollResult")
                    .where("orgPartyId", orgPartyId, "payrollResultId", payrollResultId)
                    .queryOne();
            if (res == null) throw new IllegalArgumentException("EmployeePayrollResult not found: " + payrollResultId);

            p.isSinglePayslip = true;
            p.payrollRunId = res.getString("payrollRunId");
            p.results = Collections.singletonList(res);
        } else {
            GenericValue run = EntityQuery.use(delegator)
                    .from("PayrollRun")
                    .where("orgPartyId", orgPartyId, "payrollRunId", payrollRunId)
                    .queryOne();
            if (run == null) throw new IllegalArgumentException("PayrollRun not found: " + payrollRunId);

            p.isSinglePayslip = false;
            p.payrollRunId = payrollRunId;

            List<GenericValue> results = EntityQuery.use(delegator)
                    .from("EmployeePayrollResult")
                    .where("orgPartyId", orgPartyId, "payrollRunId", payrollRunId)
                    .orderBy("partyId")
                    .queryList();
            p.results = results;
        }
        return p;
    }

    // =========================
    // Generator routing
    // =========================
    private static GeneratedFile generateByFormat(String formatTypeId, ExportPayload payload) throws Exception {
        if (UtilValidate.isEmpty(formatTypeId)) {
            throw new IllegalArgumentException("formatTypeId is empty");
        }

        if (FMT_PDF_OPENPDF.equalsIgnoreCase(formatTypeId)) {
            return generatePdfOpenPdf(payload);
        } else if (FMT_PDF_FOP.equalsIgnoreCase(formatTypeId)) {
            return generatePdfFop(payload);
        } else if (FMT_CSV.equalsIgnoreCase(formatTypeId)) {
            return generateCsv(payload);
        } else if (FMT_HTML.equalsIgnoreCase(formatTypeId)) {
            return generateHtml(payload);
        } else if (FMT_PDF.equalsIgnoreCase(formatTypeId)) {
            return generatePdfStubFromHtml(payload);
        }

        throw new IllegalArgumentException("Unsupported formatTypeId: " + formatTypeId);
    }

    // =========================
    // CSV / HTML
    // =========================
    private static GeneratedFile generateCsv(ExportPayload p) throws Exception {
        StringBuilder sb = new StringBuilder();
        sb.append("orgPartyId,payrollRunId,payrollResultId,partyId,grossSalary,totalDeductions,netSalary,currencyUomId\n");
        for (GenericValue r : p.results) {
            sb.append(csv(p.orgPartyId)).append(",");
            sb.append(csv(p.payrollRunId)).append(",");
            sb.append(csv(r.getString("payrollResultId"))).append(",");
            sb.append(csv(r.getString("partyId"))).append(",");
            sb.append(csv(nvl(r.getBigDecimal("grossSalary")))).append(",");
            sb.append(csv(nvl(r.getBigDecimal("totalDeductions")))).append(",");
            sb.append(csv(nvl(r.getBigDecimal("netSalary")))).append(",");
            sb.append(csv(r.getString("currencyUomId"))).append("\n");
        }

        byte[] bytes = sb.toString().getBytes(StandardCharsets.UTF_8);
        String fileName = (p.isSinglePayslip ? "payslip_" : "payrun_") + p.payrollRunId + "_" + System.currentTimeMillis() + ".csv";
        return GeneratedFile.of(fileName, "text/csv", bytes);
    }

    private static GeneratedFile generateHtml(ExportPayload p) throws Exception {
        String title = p.isSinglePayslip ? "Payslip" : "Payroll Run Summary";
        StringBuilder sb = new StringBuilder();
        sb.append("<html><head><meta charset=\"UTF-8\"><title>").append(esc(title)).append("</title></head><body>");
        sb.append("<h2>").append(esc(title)).append("</h2>");
        sb.append("<div>Org: ").append(esc(p.orgPartyId)).append("</div>");
        sb.append("<div>Run: ").append(esc(p.payrollRunId)).append("</div>");
        sb.append("<div>Generated: ").append(esc(String.valueOf(p.generatedAt))).append("</div>");
        sb.append("<hr/>");

        sb.append("<table border=\"1\" cellpadding=\"5\" cellspacing=\"0\">");
        sb.append("<tr>")
                .append("<th>Party</th><th>Result</th><th>Gross</th><th>Deductions</th><th>Net</th><th>Currency</th>")
                .append("</tr>");

        for (GenericValue r : p.results) {
            sb.append("<tr>");
            sb.append("<td>").append(esc(r.getString("partyId"))).append("</td>");
            sb.append("<td>").append(esc(r.getString("payrollResultId"))).append("</td>");
            sb.append("<td>").append(esc(nvl(r.getBigDecimal("grossSalary")).toPlainString())).append("</td>");
            sb.append("<td>").append(esc(nvl(r.getBigDecimal("totalDeductions")).toPlainString())).append("</td>");
            sb.append("<td>").append(esc(nvl(r.getBigDecimal("netSalary")).toPlainString())).append("</td>");
            sb.append("<td>").append(esc(r.getString("currencyUomId"))).append("</td>");
            sb.append("</tr>");
        }
        sb.append("</table>");
        sb.append("</body></html>");

        byte[] bytes = sb.toString().getBytes(StandardCharsets.UTF_8);
        String fileName = (p.isSinglePayslip ? "payslip_" : "payrun_") + p.payrollRunId + "_" + System.currentTimeMillis() + ".html";
        return GeneratedFile.of(fileName, "text/html", bytes);
    }

    private static GeneratedFile generatePdfStubFromHtml(ExportPayload p) throws Exception {
        GeneratedFile html = generateHtml(p);
        String marker = "PDF_RENDER_NOT_CONFIGURED\n\n"
                + "This is a placeholder. Configure Apache FOP (XSL-FO) or OpenPDF.\n\n"
                + "HTML SOURCE BELOW:\n\n";
        byte[] bytes = concat(marker.getBytes(StandardCharsets.UTF_8), html.bytes);
        String fileName = (p.isSinglePayslip ? "payslip_" : "payrun_") + p.payrollRunId + "_" + System.currentTimeMillis() + ".pdf";
        return GeneratedFile.of(fileName, "application/pdf", bytes);
    }

    // =========================
    // OpenPDF generator
    // =========================
    private static GeneratedFile generatePdfOpenPdf(ExportPayload p) throws Exception {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();

        Document document = new Document(PageSize.A4, 36, 36, 36, 36);
        PdfWriter writer = PdfWriter.getInstance(document, baos);

        document.open();

        Font titleFont = new Font(Font.HELVETICA, 14, Font.BOLD);
        Font normalFont = new Font(Font.HELVETICA, 10, Font.NORMAL);
        Font headerFont = new Font(Font.HELVETICA, 10, Font.BOLD);

        String title = p.isSinglePayslip ? "Payslip" : "Payroll Run Summary";
        Paragraph h = new Paragraph(title, titleFont);
        h.setSpacingAfter(10f);
        document.add(h);

        document.add(new Paragraph("Org: " + safe(p.orgPartyId), normalFont));
        document.add(new Paragraph("Run: " + safe(p.payrollRunId), normalFont));
        document.add(new Paragraph("Generated: " + safe(String.valueOf(p.generatedAt)), normalFont));
        document.add(Chunk.NEWLINE);

        PdfPTable table = new PdfPTable(6);
        table.setWidthPercentage(100f);
        table.setWidths(new float[]{2.2f, 2.4f, 1.6f, 1.6f, 1.6f, 1.2f});

        addHeaderCell(table, "Party", headerFont);
        addHeaderCell(table, "Result", headerFont);
        addHeaderCell(table, "Gross", headerFont);
        addHeaderCell(table, "Deductions", headerFont);
        addHeaderCell(table, "Net", headerFont);
        addHeaderCell(table, "Curr", headerFont);

        for (GenericValue r : p.results) {
            addBodyCell(table, safe(r.getString("partyId")), normalFont);
            addBodyCell(table, safe(r.getString("payrollResultId")), normalFont);
            addBodyCell(table, money(r.getBigDecimal("grossSalary")), normalFont);
            addBodyCell(table, money(r.getBigDecimal("totalDeductions")), normalFont);
            addBodyCell(table, money(r.getBigDecimal("netSalary")), normalFont);
            addBodyCell(table, safe(r.getString("currencyUomId")), normalFont);
        }

        document.add(table);
        document.close();

        byte[] bytes = baos.toByteArray();
        String fileName = (p.isSinglePayslip ? "payslip_" : "payrun_") + p.payrollRunId + "_" + System.currentTimeMillis() + ".pdf";
        return GeneratedFile.of(fileName, "application/pdf", bytes);
    }

    @SuppressWarnings("unused")
    private static Image buildCode128Image(PdfWriter writer, String codeValue) throws Exception {
        Barcode128 code128 = new Barcode128();
        code128.setCodeType(Barcode128.CODE128);
        code128.setCode(codeValue);

        PdfContentByte cb = writer.getDirectContent();
        Image img = code128.createImageWithBarcode(cb, null, null);
        img.scalePercent(100f);
        return img;
    }

    private static void addHeaderCell(PdfPTable table, String text, Font font) {
        PdfPCell cell = new PdfPCell(new Phrase(text, font));
        cell.setHorizontalAlignment(Element.ALIGN_CENTER);
        cell.setPadding(6f);
        table.addCell(cell);
    }

    private static void addBodyCell(PdfPTable table, String text, Font font) {
        PdfPCell cell = new PdfPCell(new Phrase(text, font));
        cell.setPadding(5f);
        table.addCell(cell);
    }

    // =========================
    // FOP generator
    // =========================
    private static GeneratedFile generatePdfFop(ExportPayload p) throws Exception {
        String xml = buildPayrollXml(p);

        // Put template file in: plugins/payroll/config/payroll/payslip-fo.xsl
        String xslPath = System.getProperty("ofbiz.home") + "/plugins/payroll/config/payroll/payslip-fo.xsl";

        String fo = transformXmlToFo(xml, xslPath);

        ByteArrayOutputStream baos = new ByteArrayOutputStream();

        FopFactory fopFactory = FopFactory.newInstance(new File(System.getProperty("ofbiz.home")).toURI());
        FOUserAgent foUserAgent = fopFactory.newFOUserAgent();

        Fop fop = fopFactory.newFop(MimeConstants.MIME_PDF, foUserAgent, baos);

        TransformerFactory tf = TransformerFactory.newInstance();
        Transformer transformer = tf.newTransformer(); // identity transform (FO already)
        Source src = new StreamSource(new ByteArrayInputStream(fo.getBytes(StandardCharsets.UTF_8)));
        Result res = new SAXResult(fop.getDefaultHandler());
        transformer.transform(src, res);

        byte[] bytes = baos.toByteArray();
        String fileName = (p.isSinglePayslip ? "payslip_" : "payrun_") + p.payrollRunId + "_" + System.currentTimeMillis() + ".pdf";
        return GeneratedFile.of(fileName, "application/pdf", bytes);
    }

    private static String buildPayrollXml(ExportPayload p) {
        StringBuilder sb = new StringBuilder();
        sb.append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>");
        sb.append("<payroll>");
        sb.append("<orgPartyId>").append(xmlEsc(p.orgPartyId)).append("</orgPartyId>");
        sb.append("<payrollRunId>").append(xmlEsc(p.payrollRunId)).append("</payrollRunId>");
        sb.append("<generatedAt>").append(xmlEsc(String.valueOf(p.generatedAt))).append("</generatedAt>");
        sb.append("<items>");
        for (GenericValue r : p.results) {
            sb.append("<item>");
            sb.append("<partyId>").append(xmlEsc(r.getString("partyId"))).append("</partyId>");
            sb.append("<payrollResultId>").append(xmlEsc(r.getString("payrollResultId"))).append("</payrollResultId>");
            sb.append("<grossSalary>").append(xmlEsc(money(r.getBigDecimal("grossSalary")))).append("</grossSalary>");
            sb.append("<totalDeductions>").append(xmlEsc(money(r.getBigDecimal("totalDeductions")))).append("</totalDeductions>");
            sb.append("<netSalary>").append(xmlEsc(money(r.getBigDecimal("netSalary")))).append("</netSalary>");
            sb.append("<currencyUomId>").append(xmlEsc(r.getString("currencyUomId"))).append("</currencyUomId>");
            sb.append("</item>");
        }
        sb.append("</items>");
        sb.append("</payroll>");
        return sb.toString();
    }

    private static String transformXmlToFo(String xml, String xslPath) throws Exception {
        TransformerFactory tf = TransformerFactory.newInstance();
        Transformer t = tf.newTransformer(new StreamSource(new File(xslPath)));

        ByteArrayOutputStream out = new ByteArrayOutputStream();
        t.transform(
                new StreamSource(new ByteArrayInputStream(xml.getBytes(StandardCharsets.UTF_8))),
                new StreamResult(out)
        );
        return out.toString(StandardCharsets.UTF_8);
    }

    private static String xmlEsc(String s) {
        if (s == null) return "";
        return s.replace("&", "&amp;")
                .replace("<", "&lt;")
                .replace(">", "&gt;")
                .replace("\"", "&quot;")
                .replace("'", "&apos;");
    }

    // =========================
    // Barcode persistence helper (optional)
    // =========================
    @SuppressWarnings("unused")
    private static void upsertPayslipBarcode(Delegator delegator,
                                            String payrollResultId,
                                            String barcodeValue,
                                            String barcodeTypeId) throws GenericEntityException {
        String id = delegator.getNextSeqId("PayslipBarcode");
        GenericValue bc = delegator.makeValue("PayslipBarcode");
        bc.set("payslipBarcodeId", id);
        bc.set("payrollResultId", payrollResultId);
        bc.set("barcodeValue", barcodeValue);
        bc.set("barcodeTypeId", barcodeTypeId);
        bc.set("generatedDate", UtilDateTime.nowTimestamp());
        bc.set("scanCount", 0L);
        delegator.create(bc);
    }

    // =========================
    // Helpers
    // =========================
    private static void safeMarkExportError(Delegator delegator, String exportRequestId, String msg) {
        if (UtilValidate.isEmpty(exportRequestId)) return;
        try {
            GenericValue req = EntityQuery.use(delegator)
                    .from("PayrollExportRequest")
                    .where("exportRequestId", exportRequestId)
                    .queryOne();
            if (req == null) return;
            req.set("statusId", EXP_ERR);

            if (PayrollStrictRules.entityHasField(delegator, "PayrollExportRequest", "errorMessage")) {
                req.set("errorMessage", truncate(String.valueOf(msg), 2000));
            }
            req.store();
        } catch (Exception ignored) {}
    }
private static String truncate(String s, int max) {
    if (s == null) return null;
    if (s.length() <= max) return s;
    return s.substring(0, max);
}

    private static void audit(Delegator delegator, String orgPartyId, String entityName, String recordId, String actionTypeId) {
        try {
            GenericValue aud = delegator.makeValue("PayrollAuditLog");
            aud.set("payrollAuditLogId", delegator.getNextSeqId("PayrollAuditLog"));
            aud.set("orgPartyId", orgPartyId);
            aud.set("entityName", entityName);
            aud.set("recordId", recordId);
            aud.set("actionTypeId", actionTypeId);
            aud.set("changedDate", UtilDateTime.nowTimestamp());
            aud.create();
        } catch (Exception ignored) {}
    }

    private static BigDecimal nvl(BigDecimal v) { return v == null ? BigDecimal.ZERO : v; }

    private static String csv(Object o) {
        String s = o == null ? "" : String.valueOf(o);
        if (s.contains(",") || s.contains("\"") || s.contains("\n")) {
            s = s.replace("\"", "\"\"");
            return "\"" + s + "\"";
        }
        return s;
    }

    private static String esc(String s) {
        if (s == null) return "";
        return s.replace("&","&amp;").replace("<","&lt;").replace(">","&gt;").replace("\"","&quot;");
    }

    private static byte[] concat(byte[] a, byte[] b) {
        byte[] out = new byte[a.length + b.length];
        System.arraycopy(a, 0, out, 0, a.length);
        System.arraycopy(b, 0, out, a.length, b.length);
        return out;
    }

    private static String money(BigDecimal v) {
        if (v == null) v = BigDecimal.ZERO;
        return v.toPlainString();
    }

    private static String safe(String s) {
        return s == null ? "" : s;
    }

    private static String sha256Hex(byte[] bytes) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] dig = md.digest(bytes);
            StringBuilder sb = new StringBuilder(dig.length * 2);
            for (byte b : dig) sb.append(String.format("%02x", b));
            return sb.toString();
        } catch (NoSuchAlgorithmException e) {
            return null;
        }
    }

    // =========================
    // Inner models (MUST be public because other classes import them)
    // =========================
     // =========================
    // Inner models
    // =========================

    public static final class ExportPayload {
        public String orgPartyId;
        public String exportTypeId;
        public String payrollRunId;
        public boolean isSinglePayslip;
        public Timestamp generatedAt;

        // Main result list (already used)
        public List<GenericValue> results = Collections.emptyList();

        // Single-payslip convenience fields
        public String payrollResultId;
        public String partyId;
        public String employeeName;
        public String currencyUomId;

        public BigDecimal grossSalary;
        public BigDecimal totalDeductions;
        public BigDecimal netSalary;

        // Optional detail lines (safe even if unused)
        public List<GenericValue> resultLines;
    }

    public static final class GeneratedFile {
        public String fileName;
        public String mimeTypeId;
        public byte[] bytes;
        public String checksum;

        public static GeneratedFile of(String fileName, String mimeTypeId, byte[] bytes) {
            GeneratedFile g = new GeneratedFile();
            g.fileName = fileName;
            g.mimeTypeId = mimeTypeId;
            g.bytes = bytes;
            g.checksum = sha256Hex(bytes);
            return g;
        }
    }
}

