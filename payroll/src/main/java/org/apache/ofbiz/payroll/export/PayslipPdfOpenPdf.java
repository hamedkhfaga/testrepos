package org.apache.ofbiz.payroll.export;

import com.lowagie.text.Document;
import com.lowagie.text.Element;
import com.lowagie.text.Font;
import com.lowagie.text.PageSize;
import com.lowagie.text.Paragraph;
import com.lowagie.text.Phrase;
import com.lowagie.text.Chunk;
import com.lowagie.text.pdf.PdfPCell;
import com.lowagie.text.pdf.PdfPTable;
import com.lowagie.text.pdf.PdfWriter;

import org.apache.ofbiz.entity.GenericValue;

import java.io.ByteArrayOutputStream;
import java.math.BigDecimal;

public final class PayslipPdfOpenPdf {
    private PayslipPdfOpenPdf() {}

    public static PayrollExportServices.GeneratedFile generate(PayrollExportServices.ExportPayload p,
                                                              String code128Value,
                                                              String qrText) throws Exception {

        GenericValue r = (p != null && p.results != null && !p.results.isEmpty()) ? p.results.get(0) : null;

        String payrollResultId = r != null ? r.getString("payrollResultId") : "";
        String partyId = r != null ? r.getString("partyId") : "";
        String currency = r != null ? r.getString("currencyUomId") : "";

        BigDecimal gross = r != null ? r.getBigDecimal("grossSalary") : BigDecimal.ZERO;
        BigDecimal ded   = r != null ? r.getBigDecimal("totalDeductions") : BigDecimal.ZERO;
        BigDecimal net   = r != null ? r.getBigDecimal("netSalary") : BigDecimal.ZERO;

        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        Document doc = new Document(PageSize.A4, 36, 36, 36, 36);
        PdfWriter.getInstance(doc, baos);

        Font titleFont = new Font(Font.HELVETICA, 14, Font.BOLD);
        Font normalFont = new Font(Font.HELVETICA, 10, Font.NORMAL);
        Font boldFont = new Font(Font.HELVETICA, 10, Font.BOLD);

        doc.open();

        doc.add(new Paragraph("Payslip", titleFont));
        doc.add(Chunk.NEWLINE);

        doc.add(new Paragraph("Org: " + safe(p.orgPartyId), normalFont));
        doc.add(new Paragraph("Run: " + safe(p.payrollRunId), normalFont));
        doc.add(new Paragraph("Employee ID: " + safe(partyId), normalFont));
        doc.add(new Paragraph("Payroll Result: " + safe(payrollResultId), normalFont));
        doc.add(new Paragraph("Currency: " + safe(currency), normalFont));
        doc.add(Chunk.NEWLINE);

        PdfPTable totals = new PdfPTable(2);
        totals.setWidthPercentage(60f);
        totals.setHorizontalAlignment(Element.ALIGN_LEFT);

        addKV(totals, "Gross Salary", money(gross), boldFont, normalFont);
        addKV(totals, "Total Deductions", money(ded), boldFont, normalFont);
        addKV(totals, "Net Salary", money(net), boldFont, normalFont);

        doc.add(totals);
        doc.close();

        byte[] bytes = baos.toByteArray();
        String fileName = "payslip_" + (payrollResultId.isEmpty() ? System.currentTimeMillis() : payrollResultId) + ".pdf";
        return PayrollExportServices.GeneratedFile.of(fileName, "application/pdf", bytes);
    }

    private static void addKV(PdfPTable t, String k, String v, Font kFont, Font vFont) {
        PdfPCell c1 = new PdfPCell(new Phrase(k, kFont));
        PdfPCell c2 = new PdfPCell(new Phrase(v, vFont));
        c1.setBorder(PdfPCell.NO_BORDER);
        c2.setBorder(PdfPCell.NO_BORDER);
        c1.setPadding(4f);
        c2.setPadding(4f);
        t.addCell(c1);
        t.addCell(c2);
    }

    private static String money(BigDecimal v) {
        return v == null ? "0" : v.toPlainString();
    }

    private static String safe(String s) {
        return s == null ? "" : s;
    }
}
