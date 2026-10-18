package org.apache.ofbiz.payroll.export;

import org.apache.ofbiz.entity.GenericValue;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.util.List;

/**
 * Simple XML builder for a single payslip based on ExportPayload.
 * - Uses the FIRST result row in payload.results
 * - Does NOT depend on any "resultLines" model
 */
public final class PayslipXmlBuilder {
    private PayslipXmlBuilder() {}

    public static String build(PayrollExportServices.ExportPayload p) {
        GenericValue r = first(p == null ? null : p.results);

        String orgPartyId = safe(p == null ? null : p.orgPartyId);
        String payrollRunId = safe(p == null ? null : p.payrollRunId);
        Timestamp generatedAt = p == null ? null : p.generatedAt;

        String payrollResultId = safe(getStr(r, "payrollResultId"));
        String partyId = safe(getStr(r, "partyId"));
        String currencyUomId = safe(getStr(r, "currencyUomId"));

        BigDecimal grossSalary = getBd(r, "grossSalary");
        BigDecimal totalDeductions = getBd(r, "totalDeductions");
        BigDecimal netSalary = getBd(r, "netSalary");

        StringBuilder sb = new StringBuilder(512);
        sb.append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>");
        sb.append("<payslip>");

        sb.append(tag("orgPartyId", orgPartyId));
        sb.append(tag("payrollRunId", payrollRunId));
        if (generatedAt != null) sb.append(tag("generatedAt", String.valueOf(generatedAt)));

        sb.append(tag("payrollResultId", payrollResultId));
        sb.append(tag("partyId", partyId));
        sb.append(tag("currencyUomId", currencyUomId));

        sb.append(tag("grossSalary", money(grossSalary)));
        sb.append(tag("totalDeductions", money(totalDeductions)));
        sb.append(tag("netSalary", money(netSalary)));

        sb.append("</payslip>");
        return sb.toString();
    }

    private static GenericValue first(List<GenericValue> list) {
        return (list != null && !list.isEmpty()) ? list.get(0) : null;
    }

    private static String getStr(GenericValue gv, String field) {
        return gv == null ? null : gv.getString(field);
    }

    private static BigDecimal getBd(GenericValue gv, String field) {
        BigDecimal v = gv == null ? null : gv.getBigDecimal(field);
        return v == null ? BigDecimal.ZERO : v;
    }

    private static String tag(String name, String value) {
        return "<" + name + ">" + xmlEsc(value) + "</" + name + ">";
    }

    private static String xmlEsc(String s) {
        if (s == null) return "";
        return s.replace("&", "&amp;")
                .replace("<", "&lt;")
                .replace(">", "&gt;")
                .replace("\"", "&quot;")
                .replace("'", "&apos;");
    }

    private static String safe(String s) {
        return s == null ? "" : s;
    }

    private static String money(BigDecimal v) {
        return (v == null ? BigDecimal.ZERO : v).toPlainString();
    }
}
