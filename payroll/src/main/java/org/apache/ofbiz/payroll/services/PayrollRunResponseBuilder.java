// File: plugins/payroll/src/main/java/org/apache/ofbiz/payroll/services/PayrollRunResponseBuilder.java
package org.apache.ofbiz.payroll.services;

import org.apache.ofbiz.base.util.UtilDateTime;

import java.sql.Timestamp;
import java.util.*;

/**
 * Builds a consistent REST-friendly response payload for monthly payroll runs.
 *
 * Compatibility:
 * - Keeps your rich payload builder API (Builder)
 * - Adds scheduler-required API:
 *     PayrollRunResponseBuilder.ResponseBuilder
 *     PayrollRunResponseBuilder.OrgRunBuilder
 *     ResponseBuilder.withRequestContext(...)
 *     ResponseBuilder.beginOrg(...), endOrg(...), build(boolean)
 *     OrgRunBuilder.withResolved(...), withCounts(...), addMessages(...), note(...), addMessageErr(...)
 *     Stage.statusWarnOrErr(...), ok(... long, long)
 */
public final class PayrollRunResponseBuilder {

    private PayrollRunResponseBuilder() {}

    // ----------------------------
    // Constants
    // ----------------------------
    public static final String STG_OK   = "STG_OK";
    public static final String STG_WARN = "STG_WARN";
    public static final String STG_ERR  = "STG_ERR";
    public static final String STG_SKIP = "STG_SKIP";

    public static final String MSG_INFO = "INFO";
    public static final String MSG_WARN = "WARN";
    public static final String MSG_ERR  = "ERROR";

    // Recommended stage IDs
    public static final String STAGE_VALIDATE  = "VALIDATE";
    public static final String STAGE_CALCULATE = "CALCULATE";
    public static final String STAGE_POST_GL   = "POST_GL";
    public static final String STAGE_POST_INV  = "POST_INV";
    public static final String STAGE_EXPORT    = "EXPORT";
    public static final String STAGE_BANK      = "BANK";

    // =========================================================
    // Scheduler entry point (EXPECTED by PayrollSchedulerServicesV2)
    // =========================================================
    public static ResponseBuilder newBuilder() {
        return new ResponseBuilder();
    }

    // =========================================================
    // Backward compatible entry point for your original Builder
    // =========================================================
    public static Builder newPayloadBuilder() {
        return new Builder();
    }

    // =========================================================
    // Scheduler-required types
    // =========================================================

    public static final class ResponseBuilder {
        private final Map<String, Object> data = new LinkedHashMap<>();
        private final List<Map<String, Object>> messages = new ArrayList<>();
        private final List<Map<String, Object>> orgRuns = new ArrayList<>();
        private final Map<String, OrgRunBuilder> orgBuilders = new LinkedHashMap<>();

        private ResponseBuilder() {
            data.put("messages", messages);
            data.put("orgRuns", orgRuns);
            data.put("startedAt", UtilDateTime.nowTimestamp());
        }

        public ResponseBuilder withRequestContext(String requestedOrgPartyId,
                                                  String requestedPayrollPeriodId,
                                                  String requestedPayrollRunId,
                                                  String currencyUomId) {
            data.put("requestedOrganizationPartyId", requestedOrgPartyId);
            data.put("requestedPayrollPeriodId", requestedPayrollPeriodId);
            data.put("requestedPayrollRunId", requestedPayrollRunId);
            data.put("currencyUomId", currencyUomId);
            return this;
        }

        // -----------------------------------------------------
        // Scheduler-required aliases
        // -----------------------------------------------------

        /** Scheduler expects beginOrg(String) */
        public OrgRunBuilder beginOrg(String organizationPartyId) {
            return org(organizationPartyId);
        }

        /** Scheduler expects endOrg(OrgRunBuilder) */
        public ResponseBuilder endOrg(OrgRunBuilder orgRb) {
            // no-op, kept for contract
            return this;
        }

        /** Scheduler expects build(boolean) */
        public Map<String, Object> build(boolean singleOrgMode) {
            data.put("singleOrgMode", singleOrgMode);
            return build();
        }

        // -----------------------------------------------------
        // Your existing org() and build()
        // -----------------------------------------------------

        /** Get/create org builder */
        public OrgRunBuilder org(String organizationPartyId) {
            return orgBuilders.computeIfAbsent(organizationPartyId, k -> {
                OrgRunBuilder b = new OrgRunBuilder(k);
                orgRuns.add(b.data);
                return b;
            });
        }

        public ResponseBuilder addInfo(String messageId, String text) {
            messages.add(msg(MSG_INFO, messageId, text, null));
            return this;
        }

        public ResponseBuilder addWarn(String messageId, String text) {
            messages.add(msg(MSG_WARN, messageId, text, null));
            return this;
        }

        public ResponseBuilder addError(String messageId, String text) {
            messages.add(msg(MSG_ERR, messageId, text, null));
            return this;
        }

        public ResponseBuilder addError(String messageId, String text, Map<String, Object> details) {
            messages.add(msg(MSG_ERR, messageId, text, details));
            return this;
        }

        private Map<String, Object> msg(String severity, String messageId, String text, Map<String, Object> details) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("severity", severity);
            m.put("messageId", messageId == null ? "" : messageId);
            m.put("text", text == null ? "" : text);
            if (details != null && !details.isEmpty()) m.put("details", details);
            m.put("timestamp", UtilDateTime.nowTimestamp());
            return m;
        }

        /** Final OFBiz service response Map. */
        public Map<String, Object> build() {
            data.put("finishedAt", UtilDateTime.nowTimestamp());
            Map<String, Object> out = new HashMap<>();
            out.put("data", data);
            out.put("message", summarizeForLegacy());
            return out;
        }

        private String summarizeForLegacy() {
            return "runs=" + orgRuns.size() + " | msg=" + messages.size();
        }
    }

    public static final class OrgRunBuilder {
        private final Map<String, Object> data = new LinkedHashMap<>();
        private final List<Map<String, Object>> stages = new ArrayList<>();
        private final List<Map<String, Object>> messages;

        private OrgRunBuilder(String orgId) {
            data.put("organizationPartyId", orgId);
            data.put("stages", stages);
            this.messages = new ArrayList<>();
            data.put("messages", messages);
        }

        // -----------------------------------------------------
        // Scheduler-required aliases
        // -----------------------------------------------------

        /** Scheduler expects withResolved(runId, periodId) */
        public OrgRunBuilder withResolved(String payrollRunIdResolved, String payrollPeriodIdResolved) {
            return resolved(payrollRunIdResolved, payrollPeriodIdResolved);
        }

        /** Scheduler expects withCounts(eligible, calculated, posted) */
        public OrgRunBuilder withCounts(long eligibleCount, int calculatedCount, int postedCount) {
            data.put("eligibleCount", eligibleCount);
            data.put("calculatedCount", calculatedCount);
            data.put("postedCount", postedCount);
            return this;
        }

        /** Scheduler expects addMessages(List<String>) */
        public OrgRunBuilder addMessages(List<String> msgs) {
            if (msgs == null) return this;
            for (String s : msgs) {
                if (s != null && !s.trim().isEmpty()) {
                    messages.add(msg(MSG_INFO, "MSG", s, null));
                }
            }
            return this;
        }

        /** Scheduler expects note(String) */
        public OrgRunBuilder note(String text) {
            if (text != null && !text.trim().isEmpty()) {
                messages.add(msg(MSG_INFO, "NOTE", text, null));
            }
            return this;
        }

        /** Scheduler expects addMessageErr(String) */
        public OrgRunBuilder addMessageErr(String text) {
            if (text != null && !text.trim().isEmpty()) {
                messages.add(msg(MSG_ERR, "ERROR", text, null));
            }
            return this;
        }

        // -----------------------------------------------------
        // Your existing methods
        // -----------------------------------------------------

        public OrgRunBuilder resolved(String payrollRunIdResolved, String payrollPeriodIdResolved) {
            data.put("payrollRunIdResolved", payrollRunIdResolved);
            data.put("payrollPeriodIdResolved", payrollPeriodIdResolved);
            return this;
        }

        public OrgRunBuilder postingMode(String postingMode) {
            data.put("postingMode", postingMode);
            return this;
        }

        public OrgRunBuilder eligibleCount(int eligibleCount) {
            data.put("eligibleCount", eligibleCount);
            return this;
        }

        public OrgRunBuilder calculatedCount(int calculatedCount) {
            data.put("calculatedCount", calculatedCount);
            return this;
        }

        public OrgRunBuilder postedCount(int postedCount) {
            data.put("postedCount", postedCount);
            return this;
        }

        public OrgRunBuilder stageId(String stageId) {
            data.put("stageId", stageId);
            return this;
        }

        public Stage stage(String stageId) {
            return new Stage(this, stageId);
        }

        private void addStageRow(String stageId, String stageStatusId, String summaryText, long okCount, long errorCount) {
            Map<String, Object> stg = new LinkedHashMap<>();
            stg.put("stageId", stageId);
            stg.put("stageStatusId", stageStatusId);
            stg.put("okCount", okCount);
            stg.put("errorCount", errorCount);
            stg.put("summaryText", summaryText == null ? "" : summaryText);
            stg.put("timestamp", UtilDateTime.nowTimestamp());
            stages.add(stg);
        }

        private Map<String, Object> msg(String severity, String messageId, String text, Map<String, Object> details) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("severity", severity);
            m.put("messageId", messageId == null ? "" : messageId);
            m.put("text", text == null ? "" : text);
            if (details != null && !details.isEmpty()) m.put("details", details);
            m.put("timestamp", UtilDateTime.nowTimestamp());
            return m;
        }
    }

    public static final class Stage {
        private final OrgRunBuilder org;
        private final String stageId;

        private Stage(OrgRunBuilder org, String stageId) {
            this.org = org;
            this.stageId = stageId;
        }

        public Stage start() {
            org.addStageRow(stageId, STG_OK, "Started", 0L, 0L);
            return this;
        }

        /** Scheduler passes long values here, so keep long signature */
        public Stage ok(String summaryText, long okCount, long errorCount) {
            org.addStageRow(stageId, STG_OK, summaryText, okCount, errorCount);
            return this;
        }

        public Stage warn(String summaryText) {
            org.addStageRow(stageId, STG_WARN, summaryText, 0L, 0L);
            return this;
        }

        public Stage error(String summaryText) {
            org.addStageRow(stageId, STG_ERR, summaryText, 0L, 1L);
            return this;
        }

        /** Scheduler expects statusWarnOrErr(...) */
        public Stage statusWarnOrErr(String stageStatusId, String summaryText, long okCount, long errorCount) {
            org.addStageRow(stageId, stageStatusId, summaryText, okCount, errorCount);
            return this;
        }
    }

    // =========================================================
    // YOUR ORIGINAL RICH PAYLOAD BUILDER (kept intact)
    // =========================================================
    public static final class Builder {

        private final Map<String, Object> data = new LinkedHashMap<>();
        private final List<Map<String, Object>> stages = new ArrayList<>();
        private final List<Map<String, Object>> messages = new ArrayList<>();

        private Boolean ok;
        private Boolean hardFail;
        private String stageId;
        private String payrollRunId;
        private String payrollPeriodId;
        private Long eligibleCount;
        private Long okCount;
        private Long errorCount;
        private String summaryText;
        private Long resultsCount;
        private Long postingLinesCount;

        private Integer legacyOkCount;
        private Integer legacyErrorCount;
        private Timestamp legacyNextRunTime;

        private Builder() {
            data.put("stages", stages);
            data.put("messages", messages);
        }

        public Builder payrollRunIdResolved(String payrollRunIdResolved) { data.put("payrollRunIdResolved", payrollRunIdResolved); return this; }
        public Builder periodIdResolved(String payrollPeriodIdResolved) { data.put("periodIdResolved", payrollPeriodIdResolved); return this; }
        public Builder postingMode(String postingMode) { data.put("postingMode", postingMode); return this; }
        public Builder eligibleCount(int eligibleCount) { data.put("eligibleCount", eligibleCount); return this; }
        public Builder calculatedCount(int calculatedCount) { data.put("calculatedCount", calculatedCount); return this; }
        public Builder postedCount(int postedCount) { data.put("postedCount", postedCount); return this; }
        public Builder exportedCount(int exportedCount) { data.put("exportedCount", exportedCount); return this; }
        public Builder bankBatchCount(int bankBatchCount) { data.put("bankBatchCount", bankBatchCount); return this; }
        public Builder startedAtNow() { data.put("startedAt", UtilDateTime.nowTimestamp()); return this; }
        public Builder finishedAtNow() { data.put("finishedAt", UtilDateTime.nowTimestamp()); return this; }
        public Builder dryRun(boolean dryRun) { data.put("dryRun", dryRun); return this; }
        public Builder testMode(boolean testMode) { data.put("testMode", testMode); return this; }
        public Builder forceRepost(boolean forceRepost) { data.put("forceRepost", forceRepost); return this; }

        public Builder addStage(String stageId, String stageStatusId, int okCount, int errorCount, String summaryText) {
            Map<String, Object> stg = new LinkedHashMap<>();
            stg.put("stageId", stageId);
            stg.put("stageStatusId", stageStatusId);
            stg.put("okCount", okCount);
            stg.put("errorCount", errorCount);
            stg.put("summaryText", summaryText == null ? "" : summaryText);
            stg.put("timestamp", UtilDateTime.nowTimestamp());
            stages.add(stg);
            return this;
        }

        public Builder addStageSkip(String stageId, String summaryText) { return addStage(stageId, STG_SKIP, 0, 0, summaryText); }

        public Builder addInfo(String messageId, String text) { messages.add(msg(MSG_INFO, messageId, text, null)); return this; }
        public Builder addWarn(String messageId, String text) { messages.add(msg(MSG_WARN, messageId, text, null)); return this; }
        public Builder addError(String messageId, String text) { messages.add(msg(MSG_ERR, messageId, text, null)); return this; }
        public Builder addError(String messageId, String text, Map<String, Object> details) { messages.add(msg(MSG_ERR, messageId, text, details)); return this; }

        private Map<String, Object> msg(String severity, String messageId, String text, Map<String, Object> details) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("severity", severity);
            m.put("messageId", messageId == null ? "" : messageId);
            m.put("text", text == null ? "" : text);
            if (details != null && !details.isEmpty()) m.put("details", details);
            m.put("timestamp", UtilDateTime.nowTimestamp());
            return m;
        }

        public Builder legacyOkCount(Integer okCount) { this.legacyOkCount = okCount; return this; }
        public Builder legacyErrorCount(Integer errorCount) { this.legacyErrorCount = errorCount; return this; }
        public Builder legacyNextRunTime(Timestamp nextRunTime) { this.legacyNextRunTime = nextRunTime; return this; }

        public Map<String, Object> buildServiceResult() {
            Map<String, Object> out = new HashMap<>();
            out.put("data", data);
            if (legacyOkCount != null) out.put("okCount", legacyOkCount);
            if (legacyErrorCount != null) out.put("errorCount", legacyErrorCount);
            if (legacyNextRunTime != null) out.put("nextRunTime", legacyNextRunTime);
            out.put("message", summarizeForLegacy());
            return out;
        }

        private String summarizeForLegacy() {
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < stages.size(); i++) {
                Map<String, Object> s = stages.get(i);
                if (i > 0) sb.append("; ");
                sb.append(String.valueOf(s.get("stageId"))).append(":").append(String.valueOf(s.get("stageStatusId")));
            }
            if (!messages.isEmpty()) sb.append(" | msg=").append(messages.size());
            return sb.toString();
        }

        public Builder withOk(boolean ok) { this.ok = ok; data.put("ok", ok); return this; }
        public Builder withHardFail(boolean hardFail) { this.hardFail = hardFail; data.put("hardFail", hardFail); return this; }
        public Builder withStageId(String stageId) { this.stageId = stageId; data.put("stageId", stageId); return this; }
        public Builder withPayrollRunId(String payrollRunId) { this.payrollRunId = payrollRunId; data.put("payrollRunId", payrollRunId); return this; }
        public Builder withPayrollPeriodId(String payrollPeriodId) { this.payrollPeriodId = payrollPeriodId; data.put("payrollPeriodId", payrollPeriodId); return this; }
        public Builder withEligibleCount(long eligibleCount) { this.eligibleCount = eligibleCount; data.put("eligibleCount", eligibleCount); return this; }
        public Builder withOkCount(long okCount) { this.okCount = okCount; data.put("okCount", okCount); return this; }
        public Builder withErrorCount(long errorCount) { this.errorCount = errorCount; data.put("errorCount", errorCount); return this; }
        public Builder withSummaryText(String summaryText) { this.summaryText = summaryText; data.put("summaryText", summaryText == null ? "" : summaryText); return this; }

        public Builder addMessage(String msgText) {
            if (msgText != null && !msgText.trim().isEmpty()) messages.add(msg(MSG_INFO, "MSG", msgText, null));
            return this;
        }

        public Builder addMessages(List<String> msgs) { if (msgs != null) for (String m : msgs) addMessage(m); return this; }

        public Builder withValidation(PayrollValidationQueries.ValidationResult vr) {
            if (vr == null) return this;
            withOk(vr.isOk());
            withHardFail(vr.isHardFail());
            withEligibleCount(vr.getEligibleCount());
            withOkCount(vr.getOkCount());
            withErrorCount(vr.getErrorCount());
            withSummaryText(vr.getSummaryText());
            addMessages(vr.getMessages());
            return this;
        }

        public Builder withResultsCount(long resultsCount) { this.resultsCount = resultsCount; data.put("resultsCount", resultsCount); return this; }
        public Builder withPostingLinesCount(long postingLinesCount) { this.postingLinesCount = postingLinesCount; data.put("postingLinesCount", postingLinesCount); return this; }

        public Map<String, Object> build() {
            Map<String, Object> out = new HashMap<>();
            out.put("data", data);
            if (ok != null) out.put("ok", ok);
            if (hardFail != null) out.put("hardFail", hardFail);
            if (stageId != null) out.put("stageId", stageId);
            if (payrollRunId != null) out.put("payrollRunId", payrollRunId);
            if (payrollPeriodId != null) out.put("payrollPeriodId", payrollPeriodId);
            if (eligibleCount != null) out.put("eligibleCount", eligibleCount);
            if (okCount != null) out.put("okCount", okCount);
            if (errorCount != null) out.put("errorCount", errorCount);
            if (summaryText != null) out.put("summaryText", summaryText);
            if (resultsCount != null) out.put("resultsCount", resultsCount);
            if (postingLinesCount != null) out.put("postingLinesCount", postingLinesCount);
            out.put("message", summarizeForLegacy());
            return out;
        }
    }
}
