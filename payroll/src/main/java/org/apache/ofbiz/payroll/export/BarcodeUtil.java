package org.apache.ofbiz.payroll.export;

import com.google.zxing.BarcodeFormat;
import com.google.zxing.common.BitMatrix;
import com.google.zxing.qrcode.QRCodeWriter;
import com.google.zxing.client.j2se.MatrixToImageWriter;
import com.google.zxing.oned.Code128Writer;

import java.io.*;
import java.nio.charset.StandardCharsets;

public final class BarcodeUtil {
    private BarcodeUtil() {}

    public static byte[] buildQrPng(String text, int sizePx) throws Exception {
        QRCodeWriter qr = new QRCodeWriter();
        BitMatrix matrix = qr.encode(text, BarcodeFormat.QR_CODE, sizePx, sizePx);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        MatrixToImageWriter.writeToStream(matrix, "PNG", out);
        return out.toByteArray();
    }

    public static byte[] buildCode128Png(String text, int widthPx, int heightPx) throws Exception {
        Code128Writer w = new Code128Writer();
        BitMatrix matrix = w.encode(text, BarcodeFormat.CODE_128, widthPx, heightPx);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        MatrixToImageWriter.writeToStream(matrix, "PNG", out);
        return out.toByteArray();
    }

    public static String writeTempFile(byte[] bytes, String fileName, String subDir) throws Exception {
        String ofbizHome = System.getProperty("ofbiz.home");
        File dir = new File(ofbizHome, "runtime/tmp/" + (subDir == null ? "payroll" : subDir));
        if (!dir.exists() && !dir.mkdirs()) {
            throw new IllegalStateException("Cannot create temp dir: " + dir.getAbsolutePath());
        }
        File f = new File(dir, fileName);
        try (FileOutputStream fos = new FileOutputStream(f)) {
            fos.write(bytes);
        }
        return f.getAbsolutePath();
    }

    // Replace with HMAC token in production
    public static String buildSimpleToken(String payrollResultId, long version) {
        String raw = payrollResultId + "|" + version + "|" + System.currentTimeMillis();
        return java.util.Base64.getUrlEncoder().withoutPadding()
                .encodeToString(raw.getBytes(StandardCharsets.UTF_8));
    }
}
