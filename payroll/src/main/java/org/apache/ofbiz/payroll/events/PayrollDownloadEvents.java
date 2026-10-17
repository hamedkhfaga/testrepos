package org.apache.ofbiz.payroll.events;

import java.io.*;
import java.sql.Timestamp;

import javax.servlet.ServletOutputStream;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import org.apache.ofbiz.base.util.Debug;
import org.apache.ofbiz.base.util.UtilDateTime;
import org.apache.ofbiz.base.util.UtilValidate;
import org.apache.ofbiz.entity.Delegator;
import org.apache.ofbiz.entity.GenericValue;
import org.apache.ofbiz.entity.util.EntityQuery;
import java.util.Locale;


/**
 * Payroll download events:
 * 1) downloadExportFile (by exportFileId)
 * 2) downloadPayslipPdf (by token; streaming PDF; unlimited downloads until expiry; audit)
 */
public final class PayrollDownloadEvents {
    public static final String MODULE = PayrollDownloadEvents.class.getName();

    private PayrollDownloadEvents() {}

    /* ===================================================== */
    /* 1) Existing: Export File download                      */
    /* ===================================================== */

    public static String downloadExportFile(HttpServletRequest request, HttpServletResponse response) {
        try {
            Delegator delegator = (Delegator) request.getAttribute("delegator");
            String exportFileId = request.getParameter("exportFileId");
            if (UtilValidate.isEmpty(exportFileId)) {
                return writePlainError(request, response, HttpServletResponse.SC_BAD_REQUEST, "exportFileId is required");
            }

            GenericValue file = EntityQuery.use(delegator)
                    .from("PayrollExportFile")
                    .where("exportFileId", exportFileId)
                    .queryOne();

            if (file == null) {
                return writePlainError(request, response, HttpServletResponse.SC_NOT_FOUND,
                        "PayrollExportFile not found: " + exportFileId);
            }

            String path = file.getString("filePath");
            String name = file.getString("fileName");
            if (UtilValidate.isEmpty(path) || UtilValidate.isEmpty(name)) {
                return writePlainError(request, response, HttpServletResponse.SC_INTERNAL_SERVER_ERROR,
                        "Export file path/name missing for: " + exportFileId);
            }

            File f = new File(path);
            if (!f.exists() || !f.isFile()) {
                return writePlainError(request, response, HttpServletResponse.SC_NOT_FOUND,
                        "File not found on disk: " + path);
            }

            // Best-effort content type
            String contentType = file.getString("contentType");
            if (UtilValidate.isEmpty(contentType)) {
                // fallback based on extension
                contentType = guessContentTypeByName(name);
            }

            streamFile(response, f, contentType, name);
            return "success";

        } catch (Exception e) {
            Debug.logError(e, "downloadExportFile failed", MODULE);
            request.setAttribute("_ERROR_MESSAGE_", "downloadExportFile failed: " + e.getMessage());
            return "error";
        }
    }

    /* ===================================================== */
    /* 2) NEW: Payslip PDF download by token (stream)         */
    /* ===================================================== */

    public static String downloadPayslipPdf(HttpServletRequest request, HttpServletResponse response) {
        try {
            Delegator delegator = (Delegator) request.getAttribute("delegator");

            String token = request.getParameter("token");
            if (UtilValidate.isEmpty(token)) {
                return writePlainError(request, response, HttpServletResponse.SC_BAD_REQUEST, "token is required");
            }

            GenericValue tok = EntityQuery.use(delegator)
                    .from("PayslipAccessToken")
                    .where("token", token)
                    .queryFirst();

            if (tok == null) {
                return writePlainError(request, response, HttpServletResponse.SC_NOT_FOUND, "Invalid token");
            }

            Timestamp now = UtilDateTime.nowTimestamp();

            // status check
            String statusId = tok.getString("statusId");
            if (!"TOKEN_ACTIVE".equals(statusId)) {
                return writePlainError(request, response, HttpServletResponse.SC_GONE, "Token not active");
            }

            // expiry check
            Timestamp expires = tok.getTimestamp("expiresDate");
            if (expires != null && expires.before(now)) {
                tok.set("statusId", "TOKEN_EXPIRED");
                tok.store();
                return writePlainError(request, response, HttpServletResponse.SC_GONE, "Token expired");
            }

            String payrollRunItemId = tok.getString("payrollRunItemId");
            if (UtilValidate.isEmpty(payrollRunItemId)) {
                return writePlainError(request, response, HttpServletResponse.SC_INTERNAL_SERVER_ERROR, "Token misconfigured");
            }

            // IMPORTANT: never accept file path from request; use controlled naming.
            String relativePdfPath = "runtime/payslips/payslip_" + payrollRunItemId + ".pdf";
            File pdfFile = new File(relativePdfPath);

            if (!pdfFile.exists() || !pdfFile.isFile()) {
                return writePlainError(request, response, HttpServletResponse.SC_NOT_FOUND, "Payslip file not found");
            }

            // Unlimited downloads until expiry:
            // - increment downloadCount (best effort)
            Long downloadCount = tok.getLong("downloadCount");
            if (downloadCount == null) downloadCount = 0L;
            tok.set("downloadCount", downloadCount + 1);
            tok.store();

            // Audit (best-effort; do not block download)
            try {
                GenericValue audit = delegator.makeValue("PayslipDownloadAudit");
                audit.set("payslipDownloadAuditId", delegator.getNextSeqId("PayslipDownloadAudit"));
                audit.set("token", token);
                audit.set("partyId", tok.getString("partyId"));
                audit.set("payrollRunItemId", payrollRunItemId);
                audit.set("downloadDate", now);
                audit.set("remoteIp", request.getRemoteAddr());
                audit.set("userAgent", request.getHeader("User-Agent"));
                delegator.create(audit);
            } catch (Exception ignore) {}

            String downloadName = "payslip_" + payrollRunItemId + ".pdf";
            streamFile(response, pdfFile, "application/pdf", downloadName);
            return "success";

        } catch (Exception e) {
            Debug.logError(e, "downloadPayslipPdf failed", MODULE);
            request.setAttribute("_ERROR_MESSAGE_", "downloadPayslipPdf failed: " + e.getMessage());
            return "error";
        }
    }

    /* ===================================================== */
    /* Helpers                                               */
    /* ===================================================== */

    private static void streamFile(HttpServletResponse response, File file, String contentType, String downloadName)
            throws IOException {

        response.reset();
        response.setStatus(HttpServletResponse.SC_OK);

        response.setContentType(contentType);
        response.setHeader("X-Content-Type-Options", "nosniff");
        response.setHeader("Cache-Control", "no-store, no-cache, must-revalidate, max-age=0");
        response.setHeader("Pragma", "no-cache");
        response.setDateHeader("Expires", 0);

        response.setHeader("Content-Disposition", "attachment; filename=\"" + sanitizeFilename(downloadName) + "\"");
        response.setContentLengthLong(file.length());

        try (InputStream in = new BufferedInputStream(new FileInputStream(file));
             ServletOutputStream out = response.getOutputStream()) {

            byte[] buf = new byte[8192];
            int len;
            while ((len = in.read(buf)) != -1) {
                out.write(buf, 0, len);
            }
            out.flush();
        }
    }

    private static String writePlainError(HttpServletRequest request, HttpServletResponse response, int status, String msg)
            throws IOException {

        request.setAttribute("_ERROR_MESSAGE_", msg);

        response.reset();
        response.setStatus(status);
        response.setContentType("text/plain; charset=UTF-8");
        response.setHeader("Cache-Control", "no-store");
        response.getWriter().write(msg);
        response.getWriter().flush();
        return "error";
    }

    private static String guessContentTypeByName(String name) {
        String n = name.toLowerCase(Locale.ROOT);
        if (n.endsWith(".pdf")) return "application/pdf";
        if (n.endsWith(".csv")) return "text/csv";
        if (n.endsWith(".txt")) return "text/plain";
        if (n.endsWith(".zip")) return "application/zip";
        if (n.endsWith(".xml")) return "application/xml";
        return "application/octet-stream";
    }

    private static String sanitizeFilename(String name) {
        if (name == null) return "download";
        // remove CR/LF and quotes to avoid header injection
        return name.replace("\r", "").replace("\n", "").replace("\"", "'");
    }
}
