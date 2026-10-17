package org.apache.ofbiz.payroll.events;

import org.apache.ofbiz.base.util.UtilDateTime;
import org.apache.ofbiz.base.util.UtilValidate;
import org.apache.ofbiz.entity.Delegator;
import org.apache.ofbiz.entity.GenericEntityException;
import org.apache.ofbiz.entity.GenericValue;
import org.apache.ofbiz.entity.util.EntityQuery;

import javax.servlet.ServletOutputStream;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.io.*;
import java.sql.Timestamp;

public class PayslipDownloadEvents {

    /**
     * Streams payslip PDF directly to browser using secure token.
     * URL: /payroll/control/downloadPayslipPdf?token=...
     */
    public static String downloadPayslipPdf(HttpServletRequest request, HttpServletResponse response)
            throws Exception {

        Delegator delegator = (Delegator) request.getAttribute("delegator");

        String token = request.getParameter("token");
        if (UtilValidate.isEmpty(token)) {
            writePlainError(response, HttpServletResponse.SC_BAD_REQUEST, "token is required");
            return "error";
        }

        GenericValue tok = EntityQuery.use(delegator)
                .from("PayslipAccessToken")
                .where("token", token)
                .queryFirst();

        if (tok == null) {
            writePlainError(response, HttpServletResponse.SC_NOT_FOUND, "Invalid token");
            return "error";
        }

        Timestamp now = UtilDateTime.nowTimestamp();
        Timestamp expires = tok.getTimestamp("expiresDate");

        if (expires != null && expires.before(now)) {
            tok.set("statusId", "TOKEN_EXPIRED");
            tok.store();
            writePlainError(response, HttpServletResponse.SC_GONE, "Token expired");
            return "error";
        }

        if ("TOKEN_USED".equals(tok.getString("statusId"))) {
            writePlainError(response, HttpServletResponse.SC_GONE, "Token already used");
            return "error";
        }

        // IMPORTANT: Do not accept a file path from user input
        // Use controlled naming based on payrollRunItemId
        String payrollRunItemId = tok.getString("payrollRunItemId");
        if (UtilValidate.isEmpty(payrollRunItemId)) {
            writePlainError(response, HttpServletResponse.SC_INTERNAL_SERVER_ERROR, "Token misconfigured");
            return "error";
        }

        String relativePdfPath = "runtime/payslips/payslip_" + payrollRunItemId + ".pdf";
        File pdfFile = new File(relativePdfPath);

        if (!pdfFile.exists() || !pdfFile.isFile()) {
            writePlainError(response, HttpServletResponse.SC_NOT_FOUND, "Payslip file not found");
            return "error";
        }

        // Mark token used BEFORE streaming (prevents reuse even if client disconnects)
        tok.set("statusId", "TOKEN_USED");
        tok.set("usedDate", now);
        tok.store();

        // Stream PDF
        response.reset();
        response.setStatus(HttpServletResponse.SC_OK);
        response.setContentType("application/pdf");
        response.setHeader("X-Content-Type-Options", "nosniff");
        response.setHeader("Cache-Control", "no-store, no-cache, must-revalidate, max-age=0");
        response.setHeader("Pragma", "no-cache");
        response.setDateHeader("Expires", 0);

        String downloadName = "payslip_" + payrollRunItemId + ".pdf";
        response.setHeader("Content-Disposition", "attachment; filename=\"" + downloadName + "\"");
        response.setContentLengthLong(pdfFile.length());

        try (InputStream in = new BufferedInputStream(new FileInputStream(pdfFile));
             ServletOutputStream out = response.getOutputStream()) {

            byte[] buf = new byte[8192];
            int read;
            while ((read = in.read(buf)) != -1) {
                out.write(buf, 0, read);
            }
            out.flush();
        }

        return "success";
    }

    private static void writePlainError(HttpServletResponse response, int status, String msg) throws IOException {
        response.reset();
        response.setStatus(status);
        response.setContentType("text/plain; charset=UTF-8");
        response.getWriter().write(msg);
        response.getWriter().flush();
    }
}
