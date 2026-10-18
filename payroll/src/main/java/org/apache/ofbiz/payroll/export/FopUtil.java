package org.apache.ofbiz.payroll.export;

import java.io.*;
import javax.xml.transform.*;
import javax.xml.transform.stream.*;

import org.apache.fop.apps.*;
import javax.xml.transform.sax.SAXResult;

public final class FopUtil {
    private FopUtil() {}

    public static byte[] foToPdfBytes(String foXml) throws Exception {
        FopFactory fopFactory = FopFactory.newInstance(new File(".").toURI());
        ByteArrayOutputStream out = new ByteArrayOutputStream();

        FOUserAgent userAgent = fopFactory.newFOUserAgent();
        Fop fop = fopFactory.newFop(MimeConstants.MIME_PDF, userAgent, out);

        TransformerFactory tf = TransformerFactory.newInstance();
        Transformer transformer = tf.newTransformer();

        Source src = new StreamSource(new StringReader(foXml));
        Result res = new SAXResult(fop.getDefaultHandler());
        transformer.transform(src, res);

        return out.toByteArray();
    }
}
