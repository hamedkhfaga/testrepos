package org.apache.ofbiz.payroll.export;

public final class FopBarcodeAssets {
    private FopBarcodeAssets() {}

    public static String toFileUrl(String absolutePath) {
        String p = absolutePath.replace('\\', '/');
        if (!p.startsWith("/")) {
            return "file:/" + p; // Windows: C:/...
        }
        return "file:" + p;     // Linux: /opt/...
    }
}
