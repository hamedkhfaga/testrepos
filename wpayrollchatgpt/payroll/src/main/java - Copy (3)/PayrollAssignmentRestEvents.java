package org.apache.ofbiz.payroll.rest;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.ofbiz.base.util.Debug;
import org.apache.ofbiz.base.util.UtilDateTime;
import org.apache.ofbiz.base.util.UtilValidate;
import org.apache.ofbiz.entity.Delegator;
import org.apache.ofbiz.entity.GenericValue;
import org.apache.ofbiz.entity.condition.EntityCondition;
import org.apache.ofbiz.entity.condition.EntityOperator;
import org.apache.ofbiz.entity.util.EntityFindOptions;
import org.apache.ofbiz.entity.util.EntityQuery;
import org.apache.ofbiz.service.LocalDispatcher;
import org.apache.ofbiz.service.ServiceUtil;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.io.BufferedReader;
import java.math.BigDecimal;
import java.sql.Timestamp;
import java.util.*;

public class PayrollAssignmentRestEvents {

    public static final String MODULE = PayrollAssignmentRestEvents.class.getName();
    private static final ObjectMapper MAPPER = new ObjectMapper();

    public static String handle(HttpServletRequest request, HttpServletResponse response) {
        try {
            Delegator delegator = (Delegator) request.getAttribute("delegator");
            LocalDispatcher dispatcher = (LocalDispatcher) request.getAttribute("dispatcher");

            if (delegator == null || dispatcher == null) {
                writeError(response, 500, "Internal Server Error",
                        "Missing delegator/dispatcher. Check payrollapi web.xml init-params (entityDelegatorName/localDispatcherName).");
                return "success";
            }

            // If you want auth=true later, enforce userLogin here.
            GenericValue userLogin = getUserLogin(request);

            String method = safeUpper(request.getMethod());
            String assignmentId = extractAssignmentIdFromUri(request);

            if ("POST".equals(method)) {
                create(request, response, dispatcher, userLogin);
                return "success";
            }

            if ("GET".equals(method)) {
                if (UtilValidate.isNotEmpty(assignmentId)) {
                    getOne(response, delegator, assignmentId);
                } else {
                    find(request, response, delegator);
                }
                return "success";
            }

            if ("PUT".equals(method)) {
                if (UtilValidate.isEmpty(assignmentId)) {
                    writeError(response, 400, "Bad Request",
                            "Missing assignmentId in path. Use PUT /payrollapi/control/assignments/{assignmentId}");
                    return "success";
                }
                update(request, response, dispatcher, userLogin, assignmentId);
                return "success";
            }

            if ("DELETE".equals(method)) {
                if (UtilValidate.isEmpty(assignmentId)) {
                    writeError(response, 400, "Bad Request",
                            "Missing assignmentId in path. Use DELETE /payrollapi/control/assignments/{assignmentId}");
                    return "success";
                }
                softDelete(response, dispatcher, userLogin, assignmentId);
                return "success";
            }

            writeError(response, 405, "Method Not Allowed", "Unsupported method: " + method);
            return "success";

        } catch (Exception e) {
            Debug.logError(e, "Unexpected error in PayrollAssignmentRestEvents.handle()", MODULE);
            writeError(response, 500, "Internal Server Error", "Unexpected error: " + e.getMessage());
            return "success";
        }
    }

    private static void create(HttpServletRequest request, HttpServletResponse response,
                               LocalDispatcher dispatcher, GenericValue userLogin) throws Exception {

        Map<String, Object> body = readJsonBodyAsMap(request);

        String partyId = asString(body.get("partyId"));
        String payrollStructureId = asString(body.get("payrollStructureId"));
        Timestamp effectiveDate = toTimestamp(body.get("effectiveDate"));

        if (UtilValidate.isEmpty(partyId) || UtilValidate.isEmpty(payrollStructureId) || effectiveDate == null) {
            writeError(response, 400, "Bad Request",
                    "Missing required fields. Required: partyId, payrollStructureId, effectiveDate");
            return;
        }

        Map<String, Object> ctx = new HashMap<>();
        ctx.put("partyId", partyId);
        ctx.put("payrollStructureId", payrollStructureId);
        ctx.put("effectiveDate", effectiveDate);

        putIfNotEmpty(ctx, "employmentType", asString(body.get("employmentType")));
        putIfNotEmpty(ctx, "contractType", asString(body.get("contractType")));
        putIfNotNull(ctx, "baseSalary", toBigDecimal(body.get("baseSalary")));
        putIfNotNull(ctx, "thruDate", toTimestamp(body.get("thruDate")));
        putIfNotEmpty(ctx, "statusId", asString(body.get("statusId")));
        putIfNotEmpty(ctx, "comments", asString(body.get("comments")));

        // If your service requires auth, enforce it:
        if (userLogin != null) {
            ctx.put("userLogin", userLogin);
        }

        Map<String, Object> result = dispatcher.runSync("createEmployeePayrollAssignment", ctx);

        if (ServiceUtil.isError(result)) {
            writeError(response, 422, "Unprocessable Entity", ServiceUtil.getErrorMessage(result));
            return;
        }

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("statusCode", 200);
        out.put("statusDescription", "OK");
        out.put("data", Collections.singletonMap("assignmentId", result.get("assignmentId")));
        writeJson(response, 200, out);
    }

    private static void find(HttpServletRequest request, HttpServletResponse response,
                             Delegator delegator) throws Exception {

        String partyId = request.getParameter("partyId");

        int viewIndex = parseIntOrDefault(request.getParameter("viewIndex"), 0);
        int viewSize = parseIntOrDefault(request.getParameter("viewSize"), 50);
        if (viewIndex < 0) viewIndex = 0;
        if (viewSize <= 0) viewSize = 50;
        if (viewSize > 500) viewSize = 500;

        int offset = viewIndex * viewSize;

        List<EntityCondition> conds = new ArrayList<>();
        if (UtilValidate.isNotEmpty(partyId)) {
            conds.add(EntityCondition.makeCondition("partyId", EntityOperator.EQUALS, partyId));
        }

        EntityCondition where = conds.isEmpty()
                ? null
                : EntityCondition.makeCondition(conds, EntityOperator.AND);

        EntityFindOptions efo = new EntityFindOptions();
        efo.setResultSetType(EntityFindOptions.TYPE_SCROLL_INSENSITIVE);
        efo.setMaxRows(viewSize);

        List<GenericValue> rows = EntityQuery.use(delegator)
                .from("EmployeePayrollAssignment")
                .where(where)
                .orderBy("effectiveDate DESC")
                .cursorScrollInsensitive()
                .queryList();

        // Apply paging safely in-memory if needed
        // (EntityFindOptions offset support varies by OFBiz/version/DB; this is robust)
        List<GenericValue> page = new ArrayList<>();
        for (int i = offset; i < rows.size() && page.size() < viewSize; i++) {
            page.add(rows.get(i));
        }

        long total = EntityQuery.use(delegator)
                .from("EmployeePayrollAssignment")
                .where(where)
                .queryCount();

        List<Map<String, Object>> dataRows = new ArrayList<>();
        for (GenericValue gv : page) {
            dataRows.add(toMapSafe(gv));
        }

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("statusCode", 200);
        out.put("statusDescription", "OK");

        Map<String, Object> data = new LinkedHashMap<>();
        data.put("viewIndex", viewIndex);
        data.put("viewSize", viewSize);
        data.put("total", total);
        data.put("assignments", dataRows);

        out.put("data", data);
        writeJson(response, 200, out);
    }

    private static void getOne(HttpServletResponse response, Delegator delegator, String assignmentId) throws Exception {
        GenericValue gv = EntityQuery.use(delegator)
                .from("EmployeePayrollAssignment")
                .where("assignmentId", assignmentId)
                .queryOne();

        if (gv == null) {
            writeError(response, 404, "Not Found", "Assignment not found: " + assignmentId);
            return;
        }

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("statusCode", 200);
        out.put("statusDescription", "OK");
        out.put("data", toMapSafe(gv));
        writeJson(response, 200, out);
    }

    private static void update(HttpServletRequest request, HttpServletResponse response,
                               LocalDispatcher dispatcher, GenericValue userLogin,
                               String assignmentId) throws Exception {

        Map<String, Object> body = readJsonBodyAsMap(request);

        Map<String, Object> ctx = new HashMap<>();
        ctx.put("assignmentId", assignmentId);

        putIfNotNull(ctx, "baseSalary", toBigDecimal(body.get("baseSalary")));
        putIfNotNull(ctx, "thruDate", toTimestamp(body.get("thruDate")));
        putIfNotEmpty(ctx, "statusId", asString(body.get("statusId")));
        putIfNotEmpty(ctx, "comments", asString(body.get("comments")));

        if (userLogin != null) {
            ctx.put("userLogin", userLogin);
        }

        if (ctx.size() <= 1 || (ctx.size() == 2 && ctx.containsKey("userLogin"))) {
            writeError(response, 400, "Bad Request",
                    "No updatable fields provided. Allowed: baseSalary, thruDate, statusId, comments");
            return;
        }

        Map<String, Object> result = dispatcher.runSync("updateEmployeePayrollAssignment", ctx);

        if (ServiceUtil.isError(result)) {
            writeError(response, 422, "Unprocessable Entity", ServiceUtil.getErrorMessage(result));
            return;
        }

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("statusCode", 200);
        out.put("statusDescription", "OK");
        out.put("data", Collections.singletonMap("assignmentId", assignmentId));
        writeJson(response, 200, out);
    }

    private static void softDelete(HttpServletResponse response, LocalDispatcher dispatcher,
                                   GenericValue userLogin, String assignmentId) throws Exception {

        Map<String, Object> ctx = new HashMap<>();
        ctx.put("assignmentId", assignmentId);
        ctx.put("thruDate", UtilDateTime.nowTimestamp());
        ctx.put("statusId", "ASSIGN_INACTIVE");

        if (userLogin != null) {
            ctx.put("userLogin", userLogin);
        }

        Map<String, Object> result = dispatcher.runSync("updateEmployeePayrollAssignment", ctx);

        if (ServiceUtil.isError(result)) {
            writeError(response, 422, "Unprocessable Entity", ServiceUtil.getErrorMessage(result));
            return;
        }

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("statusCode", 200);
        out.put("statusDescription", "OK");
        out.put("data", Collections.singletonMap("assignmentId", assignmentId));
        writeJson(response, 200, out);
    }

    // ---------------- helpers ----------------

    private static GenericValue getUserLogin(HttpServletRequest request) {
        try {
            if (request.getSession(false) == null) return null;
            Object o = request.getSession(false).getAttribute("userLogin");
            return (o instanceof GenericValue) ? (GenericValue) o : null;
        } catch (Exception ignored) {
            return null;
        }
    }

    private static String extractAssignmentIdFromUri(HttpServletRequest request) {
        try {
            String uri = request.getRequestURI();
            if (uri == null) return null;
            int idx = uri.indexOf("/assignments/");
            if (idx < 0) return null;
            String tail = uri.substring(idx + "/assignments/".length());
            if (UtilValidate.isEmpty(tail)) return null;
            int q = tail.indexOf('?');
            if (q >= 0) tail = tail.substring(0, q);
            int slash = tail.indexOf('/');
            if (slash >= 0) tail = tail.substring(0, slash);
            return UtilValidate.isEmpty(tail) ? null : tail.trim();
        } catch (Exception e) {
            return null;
        }
    }

    private static Map<String, Object> readJsonBodyAsMap(HttpServletRequest request) throws Exception {
        StringBuilder sb = new StringBuilder();
        try (BufferedReader reader = request.getReader()) {
            String line;
            while ((line = reader.readLine()) != null) sb.append(line);
        }
        String body = sb.toString().trim();
        if (UtilValidate.isEmpty(body)) return new HashMap<>();
        return MAPPER.readValue(body, new TypeReference<Map<String, Object>>() {});
    }

    private static void writeJson(HttpServletResponse response, int httpStatus, Map<String, Object> payload) {
        try {
            response.setStatus(httpStatus);
            response.setContentType("application/json");
            response.setCharacterEncoding("UTF-8");
            response.setHeader("Cache-Control", "no-store, no-cache, must-revalidate, max-age=0");
            response.setHeader("Pragma", "no-cache");
            response.getWriter().write(MAPPER.writeValueAsString(payload));
        } catch (Exception e) {
            Debug.logError(e, "Unable to write JSON response", MODULE);
        }
    }

    private static void writeError(HttpServletResponse response, int httpStatus, String statusDescription, String message) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("statusCode", httpStatus);
        out.put("statusDescription", statusDescription);
        out.put("errorMessage", message);
        writeJson(response, httpStatus, out);
    }

    private static void putIfNotEmpty(Map<String, Object> map, String key, String value) {
        if (UtilValidate.isNotEmpty(value)) map.put(key, value);
    }

    private static void putIfNotNull(Map<String, Object> map, String key, Object value) {
        if (value != null) map.put(key, value);
    }

    private static String asString(Object o) {
        if (o == null) return null;
        String s = String.valueOf(o).trim();
        return s.isEmpty() ? null : s;
    }

    private static BigDecimal toBigDecimal(Object o) {
        if (o == null) return null;
        if (o instanceof BigDecimal) return (BigDecimal) o;
        if (o instanceof Number) return BigDecimal.valueOf(((Number) o).doubleValue());
        String s = asString(o);
        if (s == null) return null;
        try {
            return new BigDecimal(s);
        } catch (Exception e) {
            return null;
        }
    }

    private static Timestamp toTimestamp(Object o) {
        if (o == null) return null;
        if (o instanceof Timestamp) return (Timestamp) o;
        if (o instanceof Number) return new Timestamp(((Number) o).longValue());

        String s = asString(o);
        if (s == null) return null;

        try {
            long ms = Long.parseLong(s);
            return new Timestamp(ms);
        } catch (Exception ignored) {
        }

        try {
            return UtilDateTime.toTimestamp(s);
        } catch (Exception ignored) {
        }

        return null;
    }

    private static int parseIntOrDefault(String s, int def) {
        if (UtilValidate.isEmpty(s)) return def;
        try {
            return Integer.parseInt(s.trim());
        } catch (Exception e) {
            return def;
        }
    }

    private static String safeUpper(String s) {
        return (s == null) ? "" : s.trim().toUpperCase(Locale.ROOT);
    }

    private static Map<String, Object> toMapSafe(GenericValue gv) {
        Map<String, Object> m = new LinkedHashMap<>();
        if (gv == null) return m;

        Map<String, Object> raw = gv.getAllFields();
        if (raw != null) {
            for (Map.Entry<String, Object> e : raw.entrySet()) {
                Object val = e.getValue();
                if (val instanceof Timestamp) {
                    m.put(e.getKey(), ((Timestamp) val).getTime());
                } else {
                    m.put(e.getKey(), val);
                }
            }
        }
        return m;
    }
}
