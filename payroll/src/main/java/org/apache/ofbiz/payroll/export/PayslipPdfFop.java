package org.apache.ofbiz.payroll.export;

import org.apache.fop.apps.FOUserAgent;
import org.apache.fop.apps.Fop;
import org.apache.fop.apps.FopFactory;
import org.apache.fop.apps.MimeConstants;

import org.apache.ofbiz.entity.GenericValue;

import javax.xml.transform.Result;
import javax.xml.transform.Source;
import javax.xml.transform.Transformer;
import javax.xml.transform.TransformerFactory;
import javax.xml.transform.sax.SAXResult;
import javax.xml.transform.stream.StreamSource;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.nio.charset.StandardCharsets;

public final class PayslipPdfFop {
    private PayslipPdfFop() {}

    public static PayrollExportServices.GeneratedFile generate(PayrollExportServices.ExportPayload p,
                                                              String code128Value,
                                                              String qrText) throws Exception {

        // Use the existing XML builder (payload-compatible)
        String xml = PayslipXmlBuilder.build(p);

        // NOTE: you must provide a real XSL-FO template file:
        // plugins/payroll/config/payroll/payslip-fo.xsl
        String xslPath = System.getProperty("ofbiz.home") + "/plugins/payroll/config/payroll/payslip-fo.xsl";

        String fo = transformXmlToFo(xml, xslPath);

        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        FopFactory fopFactory = FopFactory.newInstance(new File(System.getProperty("ofbiz.home")).toURI());
        FOUserAgent foUserAgent = fopFactory.newFOUserAgent();
        Fop fop = fopFactory.newFop(MimeConstants.MIME_PDF, foUserAgent, baos);

        TransformerFactory tf = TransformerFactory.newInstance();
        Transformer transformer = tf.newTransformer(); // identity (FO already)
        Source src = new StreamSource(new ByteArrayInputStream(fo.getBytes(StandardCharsets.UTF_8)));
        Result res = new SAXResult(fop.getDefaultHandler());
        transformer.transform(src, res);

        byte[] pdf = baos.toByteArray();

        GenericValue r = (p != null && p.results != null && !p.results.isEmpty()) ? p.results.get(0) : null;
        String payrollResultId = r != null ? r.getString("payrollResultId") : null;

        String fileName = "payslip_" + (payrollResultId == null ? System.currentTimeMillis() : payrollResultId) + ".pdf";
        return PayrollExportServices.GeneratedFile.of(fileName, "application/pdf", pdf);
    }

    private static String transformXmlToFo(String xml, String xslPath) throws Exception {
        TransformerFactory tf = TransformerFactory.newInstance();
        Transformer t = tf.newTransformer(new StreamSource(new File(xslPath)));
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        t.transform(
                new StreamSource(new ByteArrayInputStream(xml.getBytes(StandardCharsets.UTF_8))),
                new javax.xml.transform.stream.StreamResult(out)
        );
        return out.toString(StandardCharsets.UTF_8);
    }
}
