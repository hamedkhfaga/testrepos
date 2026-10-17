package org.apache.ofbiz.payroll.export;

import com.lowagie.text.Image;
import com.lowagie.text.pdf.Barcode128;
import com.lowagie.text.pdf.PdfContentByte;
import com.lowagie.text.pdf.PdfWriter;

public final class OpenPdfBarcodeRenderer {
    private OpenPdfBarcodeRenderer() {}

    public static Image buildCode128Image(PdfWriter writer, String codeValue) throws Exception {
        Barcode128 code128 = new Barcode128();
        code128.setCodeType(Barcode128.CODE128);
        code128.setCode(codeValue);

        PdfContentByte cb = writer.getDirectContent();
        Image img = code128.createImageWithBarcode(cb, null, null);
        img.scalePercent(90f);
        return img;
    }

    public static Image buildQrImage(String qrText, int sizePx) throws Exception {
        byte[] png = BarcodeUtil.buildQrPng(qrText, sizePx);
        Image qr = Image.getInstance(png);
        qr.scaleToFit(90f, 90f);
        return qr;
    }
}
