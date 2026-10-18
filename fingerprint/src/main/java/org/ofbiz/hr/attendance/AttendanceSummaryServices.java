package org.ofbiz.hr.attendance;

import freemarker.template.Configuration;
import freemarker.template.Template;

import org.apache.fop.apps.Fop;
import org.apache.fop.apps.FopFactory;
import org.apache.fop.apps.MimeConstants;

import org.apache.ofbiz.base.util.Debug;
import org.apache.ofbiz.base.util.UtilDateTime;
import org.apache.ofbiz.base.util.UtilValidate;
import org.apache.ofbiz.entity.Delegator;
import org.apache.ofbiz.entity.GenericValue;
import org.apache.ofbiz.entity.condition.EntityCondition;
import org.apache.ofbiz.entity.condition.EntityOperator;
import org.apache.ofbiz.entity.util.EntityQuery;
import org.apache.ofbiz.service.DispatchContext;
import org.apache.ofbiz.service.ServiceUtil;

import javax.xml.transform.Transformer;
import javax.xml.transform.TransformerFactory;
import javax.xml.transform.sax.SAXResult;
import javax.xml.transform.stream.StreamSource;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.StringWriter;
import java.nio.charset.StandardCharsets;
import java.sql.Date;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Option A export stack:
 * - PDF: FreeMarker -> XSL-FO -> Apache FOP -> PDF bytes
 * - Excel: FreeMarker -> SpreadsheetML XML -> .xls bytes (Excel-compatible)
 *
 * Note:
 * - True XLSX generation requires Apache POI (Option B).
 */
public class AttendanceSummaryServices {
    public static final String MODULE = AttendanceSummaryServices.class.getName();

    private static long toLong(Object val) {
        if (val == null) {
            return 0L;
        }
        if (val instanceof Number) {
            return ((Number) val).longValue();
        }
        try {
            return Long.parseLong(String.valueOf(val));
        } catch (Exception ignored) {
            return 0L;
        }
    }

    private static void assertOrgPresent(String organizationPartyId) {
        if (UtilValidate.isEmpty(organizationPartyId)) {
            throw new IllegalArgumentException("organizationPartyId is required");
        }
    }

    public static Map<String, Object> exportAttendanceSummary(DispatchContext dctx, Map<String, ?> context) {
        Delegator delegator = dctx.getDelegator();
        try {
            String organizationPartyId = (String) context.get("organizationPartyId");
            assertOrgPresent(organizationPartyId);

            java.util.Date fromWorkDate = (java.util.Date) context.get("fromWorkDate");
            java.util.Date thruWorkDate = (java.util.Date) context.get("thruWorkDate");
            if (fromWorkDate == null || thruWorkDate == null) {
                return ServiceUtil.returnError("fromWorkDate and thruWorkDate are required");
            }

            String partyId = (String) context.get("partyId");
            String exportFormatEnumId = (String) context.get("exportFormatEnumId");
            if (UtilValidate.isEmpty(exportFormatEnumId)) {
                return ServiceUtil.returnError("exportFormatEnumId is required (PDF/HTML/TXT/XLS/XLSX)");
            }

            Date fromSql = new Date(fromWorkDate.getTime());
            Date thruSql = new Date(thruWorkDate.getTime());

            EntityCondition cond = EntityCondition.makeCondition(
                    EntityOperator.AND,
                    EntityCondition.makeCondition("organizationPartyId", organizationPartyId),
                    EntityCondition.makeCondition("workDate", EntityOperator.GREATER_THAN_EQUAL_TO, fromSql),
                    EntityCondition.makeCondition("workDate", EntityOperator.LESS_THAN_EQUAL_TO, thruSql));

            if (UtilValidate.isNotEmpty(partyId)) {
                cond = EntityCondition.makeCondition(
                        EntityOperator.AND,
                        cond,
                        EntityCondition.makeCondition("partyId", partyId));
            }

            List<GenericValue> summaries = EntityQuery.use(delegator)
                    .from("AttendanceDaySummary")
                    .where(cond)
                    .orderBy("partyId", "workDate")
                    .cache(false)
                    .queryList();

            List<Map<String, Object>> rows = new ArrayList<>();

            long totalWorked = 0L;
            long totalOT = 0L;
            long totalLate = 0L;
            long totalEarly = 0L;
            long absentDays = 0L;

            for (GenericValue s : summaries) {
                Map<String, Object> r = new HashMap<>();

                r.put("partyId", s.getString("partyId"));
                r.put("workDate", String.valueOf(s.get("workDate")));

                long worked = toLong(s.get("workedMinutes"));
                long ot = toLong(s.get("overtimeMinutes"));
                long late = toLong(s.get("lateMinutes"));
                long early = toLong(s.get("earlyLeaveMinutes"));

                String absenceFlag = s.getString("absenceFlag");
                if (absenceFlag == null) {
                    absenceFlag = "N";
                }

                r.put("workedMinutes", worked);
                r.put("overtimeMinutes", ot);
                r.put("lateMinutes", late);
                r.put("earlyLeaveMinutes", early);
                r.put("absenceFlag", absenceFlag);

                totalWorked += worked;
                totalOT += ot;
                totalLate += late;
                totalEarly += early;

                if ("Y".equalsIgnoreCase(absenceFlag)) {
                    absentDays++;
                }

                rows.add(r);
            }

            Map<String, Object> totals = new HashMap<>();
            totals.put("totalWorkedMinutes", totalWorked);
            totals.put("totalOvertimeMinutes", totalOT);
            totals.put("totalLateMinutes", totalLate);
            totals.put("totalEarlyLeaveMinutes", totalEarly);
            totals.put("absentDays", absentDays);

            Map<String, Object> model = new HashMap<>();
            model.put("orgId", organizationPartyId);
            model.put("fromDate", UtilDateTime.toDateString(fromWorkDate));
            model.put("thruDate", UtilDateTime.toDateString(thruWorkDate));
            model.put("partyId", partyId);
            model.put("rows", rows);
            model.put("totals", totals);

            String fmt = exportFormatEnumId.trim().toUpperCase(Locale.ROOT);

            String baseName = "attendance_"
                    + UtilDateTime.toDateString(fromWorkDate)
                    + "_to_"
                    + UtilDateTime.toDateString(thruWorkDate);

            if ("PDF".equals(fmt)) {
                String fo = renderFtlToString("templates/attendance/AttendanceSummary.fo.ftl", model);
                byte[] pdfBytes = renderFoToPdfBytes(fo);

                Map<String, Object> result = ServiceUtil.returnSuccess();
                result.put("fileName", baseName + ".pdf");
                result.put("mimeTypeId", "application/pdf");
                result.put("fileBytes", pdfBytes);
                result.put("contentId", null);
                return result;
            }

            if ("XLS".equals(fmt) || "EXCEL".equals(fmt) || "XLSX".equals(fmt)) {
                String xml = renderFtlToString("templates/attendance/AttendanceSummary.xls.ftl", model);
                byte[] bytes = xml.getBytes(StandardCharsets.UTF_8);

                Map<String, Object> result = ServiceUtil.returnSuccess();
                result.put("fileName", baseName + ".xls");
                result.put("mimeTypeId", "application/vnd.ms-excel");
                result.put("fileBytes", bytes);
                result.put("contentId", null);
                return result;
            }

            if ("HTML".equals(fmt)) {
                String text = "Attendance Summary\nOrg: " + organizationPartyId + "\nPeriod: "
                        + UtilDateTime.toDateString(fromWorkDate) + " - "
                        + UtilDateTime.toDateString(thruWorkDate) + "\n";

                String html = "<html><head><meta charset=\"utf-8\"></head><body><pre>"
                        + escapeHtml(text)
                        + "</pre></body></html>";

                Map<String, Object> result = ServiceUtil.returnSuccess();
                result.put("fileName", baseName + ".html");
                result.put("mimeTypeId", "text/html");
                result.put("fileBytes", html.getBytes(StandardCharsets.UTF_8));
                result.put("contentId", null);
                return result;
            }

            String txt = "Attendance Summary Export\nOrg: " + organizationPartyId + "\nPeriod: "
                    + UtilDateTime.toDateString(fromWorkDate) + " - "
                    + UtilDateTime.toDateString(thruWorkDate) + "\nRows: "
                    + rows.size() + "\n";

            Map<String, Object> result = ServiceUtil.returnSuccess();
            result.put("fileName", baseName + ".txt");
            result.put("mimeTypeId", "text/plain");
            result.put("fileBytes", txt.getBytes(StandardCharsets.UTF_8));
            result.put("contentId", null);
            return result;

        } catch (Exception e) {
            Debug.logError(e, "Error in exportAttendanceSummary (Option A)", MODULE);
            return ServiceUtil.returnError("Error exporting attendance summary: " + e.getMessage());
        }
    }

    /* ------------------------- FreeMarker rendering ------------------------- */

    private static String renderFtlToString(String classpathLocation, Map<String, Object> model) throws Exception {
        Configuration cfg = new Configuration(Configuration.VERSION_2_3_31);
        cfg.setDefaultEncoding("UTF-8");
        cfg.setClassLoaderForTemplateLoading(Thread.currentThread().getContextClassLoader(), "/");
        cfg.setLogTemplateExceptions(false);
        cfg.setWrapUncheckedExceptions(true);

        Template template = cfg.getTemplate(classpathLocation);

        StringWriter out = new StringWriter();
        template.process(model, out);
        return out.toString();
    }

    /* ------------------------- FO -> PDF (Apache FOP) ------------------------- */

    private static byte[] renderFoToPdfBytes(String foXml) throws Exception {
        FopFactory fopFactory = FopFactory.newInstance(new java.io.File(".").toURI());

        try (ByteArrayOutputStream baos = new ByteArrayOutputStream()) {
            Fop fop = fopFactory.newFop(MimeConstants.MIME_PDF, baos);

            TransformerFactory transformerFactory = TransformerFactory.newInstance();
            Transformer transformer = transformerFactory.newTransformer();

            StreamSource src = new StreamSource(
                    new ByteArrayInputStream(foXml.getBytes(StandardCharsets.UTF_8)));
            SAXResult res = new SAXResult(fop.getDefaultHandler());

            transformer.transform(src, res);
            return baos.toByteArray();
        }
    }

    private static String escapeHtml(String text) {
        if (text == null) {
            return "";
        }
        return text.replace("&", "&amp;")
                .replace("<", "&lt;")
                .replace(">", "&gt;");
    }
}
