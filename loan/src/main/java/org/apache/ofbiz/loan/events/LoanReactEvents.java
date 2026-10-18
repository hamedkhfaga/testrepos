package org.apache.ofbiz.loan.events;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.io.Writer;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.security.cert.X509Certificate;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import javax.net.ssl.HostnameVerifier;
import javax.net.ssl.HttpsURLConnection;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLSession;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509TrustManager;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import javax.servlet.http.HttpSession;

import org.apache.ofbiz.base.util.Debug;
import org.apache.ofbiz.base.util.UtilValidate;
import org.apache.ofbiz.entity.Delegator;
import org.apache.ofbiz.entity.GenericValue;

public final class LoanReactEvents {

    public static final String MODULE = LoanReactEvents.class.getName();
    private static final String DEFAULT_APP_TITLE = "Loan Management Workbench";
    private static final String DEFAULT_COMPANY = "Company";
    private static final String DEFAULT_API_BASE_URL = "/rest/services/";
    private static final String DEFAULT_COPYRIGHT = "© 2026 Company";

    /**
     * Dev fallback only.
     * Prefer setting one of:
     *   -Dloan.react.auth.password=...
     *   LOAN_REACT_AUTH_PASSWORD=...
     */
    private static final String DEFAULT_DEV_PASSWORD = "ofbiz";

    private LoanReactEvents() {
    }

    /**
     * Bootstrap endpoint for the React app.
     *
     * Returns JSON like:
     * {
     *   "userLoginId": "admin",
     *   "companyName": "Company",
     *   "organizationPartyId": "Company",
     *   "appTitle": "Loan Management Workbench",
     *   "apiBaseUrl": "/rest/services/",
     *   "copyright": "© 2026 Company",
     *   "accessToken": "..."
     * }
     */
    public static String getReactAuthContext(HttpServletRequest request, HttpServletResponse response) {
        response.setContentType("application/json");
        response.setCharacterEncoding("UTF-8");

        Map<String, Object> payload = new LinkedHashMap<>();

        Delegator delegator = null;
        GenericValue userLogin = null;
        String userLoginId = "";
        String organizationPartyId = DEFAULT_COMPANY;
        String companyName = DEFAULT_COMPANY;
        String accessToken = "";

        try {
            delegator = request.getAttribute("delegator") instanceof Delegator
                    ? (Delegator) request.getAttribute("delegator")
                    : null;

            HttpSession session = request.getSession(false);
            if (session != null) {
                Object userLoginObj = session.getAttribute("userLogin");
                if (userLoginObj instanceof GenericValue) {
                    userLogin = (GenericValue) userLoginObj;
                }
            }

            if (userLogin != null) {
                userLoginId = userLogin.getString("userLoginId");
            }

            String orgFromReq = request.getParameter("organizationPartyId");
            if (UtilValidate.isNotEmpty(orgFromReq)) {
                organizationPartyId = orgFromReq;
                companyName = orgFromReq;
            } else {
                Object ctxOrg = request.getAttribute("organizationPartyId");
                if (ctxOrg instanceof String && UtilValidate.isNotEmpty((String) ctxOrg)) {
                    organizationPartyId = (String) ctxOrg;
                    companyName = organizationPartyId;
                }
            }

            Debug.logInfo("getReactAuthContext: request delegator present = " + (delegator != null), MODULE);
            Debug.logInfo("getReactAuthContext: session userLogin present = " + (userLogin != null), MODULE);
            Debug.logInfo("getReactAuthContext: userLoginId = "
                    + (UtilValidate.isNotEmpty(userLoginId) ? userLoginId : "<empty>"), MODULE);

            if (delegator == null) {
                Debug.logWarning("getReactAuthContext: delegator is null.", MODULE);
            }

            if (userLogin == null || UtilValidate.isEmpty(userLoginId)) {
                Debug.logWarning("getReactAuthContext: userLogin is null/empty; REST token cannot be generated.", MODULE);
            }

            if (UtilValidate.isNotEmpty(userLoginId)) {
                accessToken = fetchRestAccessToken(request, userLoginId);

                if (UtilValidate.isEmpty(accessToken)) {
                    Debug.logWarning("getReactAuthContext: REST access token is null/empty.", MODULE);
                    accessToken = "";
                } else {
                    Debug.logInfo("getReactAuthContext: generated REST access token length = " + accessToken.length(), MODULE);
                }
            }

            payload.put("userLoginId", UtilValidate.isNotEmpty(userLoginId) ? userLoginId : "");
            payload.put("companyName", companyName);
            payload.put("organizationPartyId", organizationPartyId);
            payload.put("appTitle", DEFAULT_APP_TITLE);
            payload.put("apiBaseUrl", DEFAULT_API_BASE_URL);
            payload.put("copyright", DEFAULT_COPYRIGHT);
            payload.put("accessToken", accessToken);

            writeJson(response, payload);
            return "none";

        } catch (Exception e) {
            Debug.logError(e, "getReactAuthContext: fatal error while building React auth context.", MODULE);

            payload.clear();
            payload.put("userLoginId", UtilValidate.isNotEmpty(userLoginId) ? userLoginId : "");
            payload.put("companyName", companyName);
            payload.put("organizationPartyId", organizationPartyId);
            payload.put("appTitle", DEFAULT_APP_TITLE);
            payload.put("apiBaseUrl", DEFAULT_API_BASE_URL);
            payload.put("copyright", DEFAULT_COPYRIGHT);
            payload.put("accessToken", "");
            payload.put("error", "Unable to build auth context");

            try {
                writeJson(response, payload);
            } catch (IOException io) {
                Debug.logError(io, "getReactAuthContext: failed writing JSON error payload.", MODULE);
            }
            return "none";
        }
    }

    private static String fetchRestAccessToken(HttpServletRequest request, String userLoginId) {
        HttpURLConnection conn = null;
        try {
            String password = resolveReactAuthPassword();
            String tokenUrl = buildRestAuthTokenUrl(request);

            Debug.logInfo("fetchRestAccessToken: tokenUrl = " + tokenUrl, MODULE);
            Debug.logInfo("fetchRestAccessToken: userLoginId = " + userLoginId, MODULE);
            Debug.logInfo("fetchRestAccessToken: password present = " + UtilValidate.isNotEmpty(password), MODULE);

            URL url = new URL(tokenUrl);
            conn = openConnection(url);

            conn.setRequestMethod("POST");
            conn.setDoOutput(true);
            conn.setUseCaches(false);
            conn.setConnectTimeout(15000);
            conn.setReadTimeout(30000);
            conn.setRequestProperty("Accept", "application/json");
            conn.setRequestProperty("Authorization", "Basic " + base64(userLoginId + ":" + password));

            // Some servers behave better with an explicit empty body on POST
            try (OutputStream os = conn.getOutputStream()) {
                os.write(new byte[0]);
                os.flush();
            }

            int status = conn.getResponseCode();
            String responseBody = readResponseBody(conn, status);

            Debug.logInfo("fetchRestAccessToken: HTTP status = " + status, MODULE);
            Debug.logInfo("fetchRestAccessToken: response body = " + responseBody, MODULE);

            if (status < 200 || status >= 300) {
                Debug.logWarning("fetchRestAccessToken: non-success status from /rest/auth/token: " + status, MODULE);
                return "";
            }

            String accessToken = extractJsonStringValue(responseBody, "access_token");
            if (UtilValidate.isEmpty(accessToken)) {
                Debug.logWarning("fetchRestAccessToken: access_token not found in response.", MODULE);
                return "";
            }

            return accessToken;
        } catch (Exception e) {
            Debug.logError(e, "fetchRestAccessToken: failed to obtain REST access token.", MODULE);
            return "";
        } finally {
            if (conn != null) {
                conn.disconnect();
            }
        }
    }

    private static String resolveReactAuthPassword() {
        String sysProp = System.getProperty("loan.react.auth.password");
        if (UtilValidate.isNotEmpty(sysProp)) {
            return sysProp;
        }

        String env = System.getenv("LOAN_REACT_AUTH_PASSWORD");
        if (UtilValidate.isNotEmpty(env)) {
            return env;
        }

        return DEFAULT_DEV_PASSWORD;
    }

    private static String buildRestAuthTokenUrl(HttpServletRequest request) {
        String scheme = UtilValidate.isNotEmpty(request.getScheme()) ? request.getScheme() : "https";
        String serverName = UtilValidate.isNotEmpty(request.getServerName()) ? request.getServerName() : "localhost";
        int serverPort = request.getServerPort();

        StringBuilder sb = new StringBuilder(128);
        sb.append(scheme).append("://").append(serverName);

        boolean includePort = true;
        if (("http".equalsIgnoreCase(scheme) && serverPort == 80)
                || ("https".equalsIgnoreCase(scheme) && serverPort == 443)) {
            includePort = false;
        }

        if (includePort && serverPort > 0) {
            sb.append(':').append(serverPort);
        }

        sb.append("/rest/auth/token");
        return sb.toString();
    }

    private static HttpURLConnection openConnection(URL url) throws Exception {
        if ("https".equalsIgnoreCase(url.getProtocol())) {
            trustAllForLocalHttps();
            HttpsURLConnection conn = (HttpsURLConnection) url.openConnection();
            conn.setHostnameVerifier(new HostnameVerifier() {
                @Override
                public boolean verify(String hostname, SSLSession session) {
                    return true;
                }
            });
            return conn;
        }
        return (HttpURLConnection) url.openConnection();
    }

    /**
     * Local-dev helper for self-signed localhost certs.
     * Do not keep this pattern for production-grade external calls.
     */
    private static void trustAllForLocalHttps() throws Exception {
        TrustManager[] trustAllCerts = new TrustManager[] {
                new X509TrustManager() {
                    @Override
                    public void checkClientTrusted(X509Certificate[] chain, String authType) {
                    }

                    @Override
                    public void checkServerTrusted(X509Certificate[] chain, String authType) {
                    }

                    @Override
                    public X509Certificate[] getAcceptedIssuers() {
                        return new X509Certificate[0];
                    }
                }
        };

        SSLContext sc = SSLContext.getInstance("TLS");
        sc.init(null, trustAllCerts, new SecureRandom());
        HttpsURLConnection.setDefaultSSLSocketFactory(sc.getSocketFactory());
    }

    private static String readResponseBody(HttpURLConnection conn, int status) throws IOException {
        InputStream is = null;
        try {
            is = (status >= 200 && status < 300) ? conn.getInputStream() : conn.getErrorStream();
            if (is == null) {
                return "";
            }

            StringBuilder sb = new StringBuilder(512);
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(is, StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    sb.append(line);
                }
            }
            return sb.toString();
        } finally {
            if (is != null) {
                try {
                    is.close();
                } catch (IOException ignored) {
                }
            }
        }
    }

    private static String extractJsonStringValue(String json, String key) {
        if (UtilValidate.isEmpty(json) || UtilValidate.isEmpty(key)) {
            return "";
        }

        Pattern pattern = Pattern.compile("\"" + Pattern.quote(key) + "\"\\s*:\\s*\"([^\"]+)\"");
        Matcher matcher = pattern.matcher(json);
        return matcher.find() ? matcher.group(1) : "";
    }

    private static String base64(String value) {
        return java.util.Base64.getEncoder().encodeToString(value.getBytes(StandardCharsets.UTF_8));
    }

    private static void writeJson(HttpServletResponse response, Map<String, Object> payload) throws IOException {
        Writer writer = response.getWriter();
        writer.write(toJson(payload));
        writer.flush();
    }

    private static String toJson(Map<String, Object> payload) {
        StringBuilder sb = new StringBuilder(256);
        sb.append('{');

        boolean first = true;
        for (Map.Entry<String, Object> entry : payload.entrySet()) {
            if (!first) {
                sb.append(',');
            }
            first = false;

            sb.append('"').append(escapeJson(entry.getKey())).append('"').append(':');

            Object value = entry.getValue();
            if (value == null) {
                sb.append("null");
            } else if (value instanceof Number || value instanceof Boolean) {
                sb.append(String.valueOf(value));
            } else {
                sb.append('"').append(escapeJson(String.valueOf(value))).append('"');
            }
        }

        sb.append('}');
        return sb.toString();
    }

    private static String escapeJson(String s) {
        if (s == null) {
            return "";
        }

        StringBuilder sb = new StringBuilder(s.length() + 16);
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"':
                    sb.append("\\\"");
                    break;
                case '\\':
                    sb.append("\\\\");
                    break;
                case '\b':
                    sb.append("\\b");
                    break;
                case '\f':
                    sb.append("\\f");
                    break;
                case '\n':
                    sb.append("\\n");
                    break;
                case '\r':
                    sb.append("\\r");
                    break;
                case '\t':
                    sb.append("\\t");
                    break;
                default:
                    if (c < 0x20) {
                        sb.append(String.format("\\u%04x", (int) c));
                    } else {
                        sb.append(c);
                    }
                    break;
            }
        }
        return sb.toString();
    }
}