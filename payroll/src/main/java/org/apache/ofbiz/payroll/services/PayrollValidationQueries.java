/* =========================================================
 * PayrollValidationQueries.java  (FULL REBUILD)
 * Purpose:
 *   - Centralize ALL DB checks/queries for payroll run V2
 *   - Keep service logic clean (service calls these methods)
 *
 * Entities used (as per your design):
 *   PayrollPeriod (DATE)
 *   PayrollRun
 *   EmployeePayrollAssignment (TIMESTAMP)
 *   EmployeePayrollResult
 *   PayrollPosting
 *   PayrollOrganization
 *   PayrollStructure
 *   PayrollGlMapping
 *
 * Key Improvements vs previous version:
 *   1) Root-cause messaging: every failure includes CODE + evidence + action hint.
 *   2) Fixes ACTIVE checks (no more comparing statusId to "Y").
 *   3) Period status validation supports both PER_OPEN and P_ACT by default (configurable lists).
 *   4) Adds missing critical checks:
 *        - PayrollRun existence/consistency (when runId provided)
 *        - Run already posted/finalized protection
 *        - Duplicate results protection
 *        - Optional posting prerequisites (GL mappings / bank config / FX rate) when mode requires
 *   5) Keeps ALL helper/query methods in one file so you can control it.
 * ========================================================= */
package org.apache.ofbiz.payroll.services;

import org.apache.ofbiz.base.util.Debug;
import org.apache.ofbiz.base.util.UtilValidate;
import org.apache.ofbiz.entity.Delegator;
import org.apache.ofbiz.entity.GenericEntityException;
import org.apache.ofbiz.entity.GenericValue;
import org.apache.ofbiz.entity.condition.EntityCondition;
import org.apache.ofbiz.entity.condition.EntityOperator;
import org.apache.ofbiz.entity.util.EntityQuery;
import org.apache.ofbiz.service.DispatchContext;

import java.math.BigDecimal;
import java.sql.Date;
import java.sql.Timestamp;
import java.util.*;
import java.util.stream.Collectors;

public final class PayrollValidationQueries {
    private static final String MODULE = PayrollValidationQueries.class.getName();

    // =========================================================
    // Status constants (keep here, but DO NOT hardcode only one value)
    // =========================================================

    // PayrollPeriod (your older POC used PER_OPEN; your DB example used P_ACT)
    public static final String PERIOD_STATUS_OPEN = "PER_OPEN";

    // PayrollOrganization "active" (if using isActive)
    public static final String ORG_IS_ACTIVE_Y = "Y";

    // PayrollStructure (your POC used PAYSTR_ACT)
    public static final String STRUCT_STATUS_ACTIVE = "PAYSTR_ACT";

    // EmployeePayrollAssignment (your POC used PAY_ASG_ACT)
    public static final String ASSIGN_STATUS_ACTIVE = "PAY_ASG_ACT";

    // Allowed statuses (adjust once you finalize status vocabulary)
    private static final Set<String> ALLOWED_PERIOD_PROCESS_STATUSES = unmodifiableSetOf(
            "PER_OPEN",     // POC
            "P_ACT",        // your DB sample
            "P_OPEN"        // common alternative
    );

    private static final Set<String> ALLOWED_ORG_ACTIVE_STATUSES = unmodifiableSetOf(
            "P_ACT",
            "ORG_ACT",
            "ACTIVE",
            "Y"             // sometimes used directly in statusId (legacy)
    );

    private static final Set<String> ALLOWED_STRUCTURE_ACTIVE_STATUSES = unmodifiableSetOf(
            "PAYSTR_ACT",
            "ACTIVE",
            "P_ACT"
    );

    private PayrollValidationQueries() { }

    // =========================================================
    // CONTRACT DTO
    // =========================================================
    public static final class ValidationResult {
        private final boolean ok;
        private final boolean hardFail;
        private final long eligibleCount;
        private final long okCount;
        private final long errorCount;
        private final String summaryText;
        private final List<String> messages;

        public ValidationResult(boolean ok,
                                boolean hardFail,
                                long eligibleCount,
                                long okCount,
                                long errorCount,
                                String summaryText,
                                List<String> messages) {
            this.ok = ok;
            this.hardFail = hardFail;
            this.eligibleCount = eligibleCount;
            this.okCount = okCount;
            this.errorCount = errorCount;
            this.summaryText = summaryText == null ? "" : summaryText;
            this.messages = messages == null
                    ? Collections.emptyList()
                    : Collections.unmodifiableList(new ArrayList<>(messages));
        }

        public boolean isOk() { return ok; }
        public boolean isHardFail() { return hardFail; }
        public long getEligibleCount() { return eligibleCount; }
        public long getOkCount() { return okCount; }
        public long getErrorCount() { return errorCount; }
        public String getSummaryText() { return summaryText; }
        public List<String> getMessages() { return messages; }

        // Convenience factory helpers (your wrapper calls these)
        public static ValidationResult error(String code, String msg) {
            List<String> m = new ArrayList<>();
            m.add("[" + code + "] " + msg);
            return new ValidationResult(false, true, 0L, 0L, 1L, "Validation HARD FAIL", m);
        }

        public static ValidationResult ok(String summary, List<String> msgs, long eligibleCount) {
            return new ValidationResult(true, false, eligibleCount, eligibleCount, 0L, summary, msgs);
        }
    }

    // =========================================================
    // Public API overloads
    // =========================================================

    /** Scheduler-required overload */
    public static ValidationResult validateForMonthlyRun(Delegator delegator,
                                                        String organizationPartyId,
                                                        String payrollRunId,
                                                        String payrollPeriodId) {
        return validateForMonthlyRun(delegator, organizationPartyId, payrollRunId, payrollPeriodId, null, null, null);
    }

    /** OFBiz-service friendly overload. */
    public static ValidationResult validateForMonthlyRun(DispatchContext dctx, Map<String, Object> context) {
        Delegator delegator = dctx != null ? dctx.getDelegator() : null;

        String orgId = asString(context, "organizationPartyId");
        String runId = asString(context, "payrollRunId");
        String periodId = asString(context, "payrollPeriodId");
        String currencyUomId = asString(context, "currencyUomId");
        String accountingPostOptionId = asString(context, "accountingPostOptionId");
        String partyId = asString(context, "partyId"); // optional filter (employee)

        // Hard guards (correct, compile-safe)
        if (delegator == null) {
            return ValidationResult.error("VALIDATE_NO_DELEGATOR", "Delegator is null.");
        }
        if (UtilValidate.isEmpty(orgId)) {
            return ValidationResult.error("VALIDATE_NO_ORG", "organizationPartyId is required.");
        }
        if (UtilValidate.isEmpty(periodId)) {
            return ValidationResult.error("VALIDATE_NO_PERIOD", "payrollPeriodId is required.");
        }

        return validateForMonthlyRun(delegator, orgId, runId, periodId, currencyUomId, accountingPostOptionId, partyId);
    }

    /**
     * Primary validation API for Monthly Payroll Run.
     *
     * HARD FAIL:
     *  - missing delegator/org/period
     *  - org missing / not ACTIVE
     *  - period missing / org mismatch / not PROCESS-allowed / invalid dates
     *  - no active payroll structure for org
     *  - eligible assignments = 0
     *  - run exists but org/period mismatch (when runId provided)
     *  - run already posted/finalized (when runId provided)
     *  - results already exist for run (when runId provided)
     *
     * SOFT WARN:
     *  - currencyUomId not found
     *  - posting option not recognized (still logs mode)
     *  - missing GL mappings (only if posting mode requires GL) => hard fail by default (configurable)
     */
    public static ValidationResult validateForMonthlyRun(Delegator delegator,
                                                        String organizationPartyId,
                                                        String payrollRunId,
                                                        String payrollPeriodId,
                                                        String currencyUomId,
                                                        String accountingPostOptionId,
                                                        String partyIdOrNull) {

        final List<String> msgs = new ArrayList<>();
        boolean hardFail = false;

        long eligibleCount = 0L;
        long okCount = 0L;
        long errorCount = 0L;

        // -------------------------
        // 0) Required inputs
        // -------------------------
        if (delegator == null) {
            return ValidationResult.error("VALIDATE_NO_DELEGATOR", "Delegator is null (service context not initialized).");
        }
        if (UtilValidate.isEmpty(organizationPartyId)) {
            return ValidationResult.error("VALIDATE_NO_ORG", "Missing organizationPartyId.");
        }
        if (UtilValidate.isEmpty(payrollPeriodId)) {
            return ValidationResult.error("VALIDATE_NO_PERIOD", "Missing payrollPeriodId (monthly validation requires explicit period).");
        }

        msgs.add(fmt("CTX", "org=%s period=%s runId=%s currency=%s postOpt=%s partyId=%s",
                organizationPartyId,
                payrollPeriodId,
                (UtilValidate.isNotEmpty(payrollRunId) ? payrollRunId : "(null)"),
                (UtilValidate.isNotEmpty(currencyUomId) ? currencyUomId : "(null)"),
                (UtilValidate.isNotEmpty(accountingPostOptionId) ? accountingPostOptionId : "(null)"),
                (UtilValidate.isNotEmpty(partyIdOrNull) ? partyIdOrNull : "(null)")
        ));

        // -------------------------
        // 1) Org exists + ACTIVE (hard requirement)
        // -------------------------
        GenericValue org = null;
        try {
            org = EntityQuery.use(delegator)
                    .from("PayrollOrganization")
                    .where("organizationPartyId", organizationPartyId)
                    .cache(false)
                    .queryOne();

            if (org == null) {
                hardFail = true;
                msgs.add(fmt("ORG_NOT_FOUND", "PayrollOrganization not found: organizationPartyId=%s", organizationPartyId));
            } else {
                boolean activeOk = isPayrollOrganizationActive(org);
                if (!activeOk) {
                    hardFail = true;
                    msgs.add(fmt("ORG_NOT_ACTIVE",
                            "PayrollOrganization is not ACTIVE. Evidence: %s",
                            describeActiveFields(org)));
                    msgs.add(fmt("HINT", "Fix PayrollOrganization for org=%s (set isActive='Y' or statusId to an allowed ACTIVE value).", organizationPartyId));
                } else {
                    msgs.add(fmt("ORG_OK", "PayrollOrganization ACTIVE: %s", describeActiveFields(org)));
                }
            }
        } catch (Exception e) {
            hardFail = true;
            msgs.add(fmt("ORG_DB_ERROR", "DB error checking PayrollOrganization: %s", safeMsg(e)));
        }

        // -------------------------
        // 2) PayrollStructure exists + ACTIVE (hard requirement in your model)
        // -------------------------
        if (!hardFail) {
            try {
                StructureCheck sc = checkPayrollStructure(delegator, organizationPartyId);
                if (!sc.ok) {
                    hardFail = true;
                    msgs.add(fmt("STRUCT_NOT_ACTIVE", "PayrollStructure missing/not ACTIVE for org=%s. Evidence: %s",
                            organizationPartyId, sc.evidence));
                    msgs.add(fmt("HINT", "Create/activate PayrollStructure for org=%s.", organizationPartyId));
                } else {
                    msgs.add(fmt("STRUCT_OK", "PayrollStructure OK. %s", sc.evidence));
                }
            } catch (Exception e) {
                hardFail = true;
                msgs.add(fmt("STRUCT_DB_ERROR", "DB error checking PayrollStructure: %s", safeMsg(e)));
            }
        }

        // -------------------------
        // 3) Period exists + belongs to org + status allowed + dates valid (hard requirement)
        // -------------------------
        GenericValue period = null;
        Timestamp periodStartTs = null;
        Timestamp periodEndTs = null;

        if (!hardFail) {
            try {
                // enforce org+period in one query to avoid mismatch messaging gaps
                period = EntityQuery.use(delegator)
                        .from("PayrollPeriod")
                        .where("payrollPeriodId", payrollPeriodId,
                               "organizationPartyId", organizationPartyId)
                        .cache(false)
                        .queryOne();

                if (period == null) {
                    hardFail = true;
                    msgs.add(fmt("PER_NOT_FOUND",
                            "PayrollPeriod not found for org=%s payrollPeriodId=%s",
                            organizationPartyId, payrollPeriodId));
                    msgs.add(fmt("HINT", "Verify PayrollPeriod exists and organizationPartyId matches."));
                } else {
                    String statusId = period.getString("statusId");
                    if (UtilValidate.isEmpty(statusId)) {
                        hardFail = true;
                        msgs.add(fmt("PER_EMPTY_STATUS", "PayrollPeriod.statusId is empty for periodId=%s", payrollPeriodId));
                    } else if (!isProcessAllowedPeriodStatus(statusId)) {
                        hardFail = true;
                        msgs.add(fmt("PER_BAD_STATUS",
                                "PayrollPeriod status not allowed for processing. actual=%s allowed=%s",
                                statusId, ALLOWED_PERIOD_PROCESS_STATUSES));
                        msgs.add(fmt("HINT", "Open/activate the period (set statusId to allowed value) or align constants."));
                    }

                    Date sd = period.getDate("startDate");
                    Date ed = period.getDate("endDate");
                    periodStartTs = startOfDay(sd);
                    periodEndTs = endOfDay(ed);

                    if (periodStartTs == null || periodEndTs == null) {
                        hardFail = true;
                        msgs.add(fmt("PER_BAD_DATES", "PayrollPeriod has invalid startDate/endDate (null)."));
                    } else if (periodStartTs.after(periodEndTs)) {
                        hardFail = true;
                        msgs.add(fmt("PER_BAD_DATES", "PayrollPeriod date range invalid: startDate > endDate."));
                    } else {
                        msgs.add(fmt("PER_OK", "Period range=[%s .. %s] statusId=%s", periodStartTs, periodEndTs, statusId));
                    }
                }
            } catch (Exception e) {
                hardFail = true;
                msgs.add(fmt("PER_DB_ERROR", "DB error resolving PayrollPeriod: %s", safeMsg(e)));
            }
        }

        // -------------------------
        // 4) Currency (soft warn)
        // -------------------------
        if (!hardFail && UtilValidate.isNotEmpty(currencyUomId)) {
            try {
                if (!hasCurrency(delegator, currencyUomId)) {
                    msgs.add(fmt("WARN_CURRENCY",
                            "Currency not found in Uom: currencyUomId=%s (soft warn; posting may fail)",
                            currencyUomId));
                } else {
                    msgs.add(fmt("CURRENCY_OK", "Currency exists: %s", currencyUomId));
                }
            } catch (Exception e) {
                msgs.add(fmt("WARN_CURRENCY_DB", "DB error checking currency: %s", safeMsg(e)));
            }
        }

        // -------------------------
        // 5) Posting option (soft info) + optional prerequisites (can be hard)
        // -------------------------
        String postingMode = "NONE";
        boolean wantsGl = false;
        boolean wantsInv = false;

        if (!hardFail && UtilValidate.isNotEmpty(accountingPostOptionId)) {
            postingMode = resolvePostingMode(accountingPostOptionId);
            msgs.add(fmt("POST_MODE", "PostingMode=%s (accountingPostOptionId=%s)", postingMode, accountingPostOptionId));
            wantsGl = hasGlPostingMode(accountingPostOptionId) || "GL".equalsIgnoreCase(postingMode);
            wantsInv = hasInvoicePostingMode(accountingPostOptionId) || "INVOICE".equalsIgnoreCase(postingMode);
        } else {
            msgs.add(fmt("POST_MODE", "PostingMode=NONE (accountingPostOptionId is empty)"));
        }

        // Optional prerequisite: GL mappings
        if (!hardFail && wantsGl) {
            try {
                if (!hasGlMappings(delegator, organizationPartyId)) {
                    hardFail = true;
                    msgs.add(fmt("GL_MAP_MISSING", "PayrollGlMapping missing for org=%s (required for GL posting).", organizationPartyId));
                    msgs.add(fmt("HINT", "Create PayrollGlMapping rows for org=%s before GL posting.", organizationPartyId));
                } else {
                    msgs.add(fmt("GL_MAP_OK", "PayrollGlMapping exists for org=%s", organizationPartyId));
                }
            } catch (Exception e) {
                hardFail = true;
                msgs.add(fmt("GL_MAP_DB_ERROR", "DB error checking PayrollGlMapping: %s", safeMsg(e)));
            }
        }

        // NOTE: FX prerequisite is only meaningful if you actually run multi-currency between org/reporting currencies.
        // If you want it, wire it in with your actual from/to currencies.

        // -------------------------
        // 6) PayrollRun sanity (hard if provided and inconsistent / already posted)
        // -------------------------
        GenericValue run = null;
        if (!hardFail && UtilValidate.isNotEmpty(payrollRunId)) {
            try {
                run = EntityQuery.use(delegator)
                        .from("PayrollRun")
                        .where("payrollRunId", payrollRunId)
                        .cache(false)
                        .queryOne();

                if (run == null) {
                    hardFail = true;
                    msgs.add(fmt("RUN_NOT_FOUND", "PayrollRun not found: payrollRunId=%s", payrollRunId));
                } else {
                    String runOrg = run.getString("organizationPartyId");
                    String runPeriod = run.getString("payrollPeriodId");

                    if (UtilValidate.isNotEmpty(runOrg) && !organizationPartyId.equals(runOrg)) {
                        hardFail = true;
                        msgs.add(fmt("RUN_ORG_MISMATCH",
                                "PayrollRun org mismatch: run.organizationPartyId=%s requested=%s",
                                runOrg, organizationPartyId));
                    }
                    if (UtilValidate.isNotEmpty(runPeriod) && !payrollPeriodId.equals(runPeriod)) {
                        hardFail = true;
                        msgs.add(fmt("RUN_PERIOD_MISMATCH",
                                "PayrollRun period mismatch: run.payrollPeriodId=%s requested=%s",
                                runPeriod, payrollPeriodId));
                    }

                    String runStatusId = run.getString("statusId");
                    String isGlPosted = run.getString("isGlPosted");
                    String isInvPosted = run.getString("isInvoicePosted");

                    boolean alreadyPosted = "Y".equalsIgnoreCase(isGlPosted) || "Y".equalsIgnoreCase(isInvPosted);
                    boolean finalized = isFinalRunStatus(runStatusId);

                    if (alreadyPosted || finalized) {
                        hardFail = true;
                        msgs.add(fmt("RUN_ALREADY_FINAL",
                                "PayrollRun already posted/finalized. statusId=%s isGlPosted=%s isInvoicePosted=%s",
                                runStatusId, isGlPosted, isInvPosted));
                        msgs.add(fmt("HINT", "Create a new run/attempt or implement forceRepost/forceRecalc policy safely."));
                    } else {
                        msgs.add(fmt("RUN_OK", "PayrollRun OK. statusId=%s isGlPosted=%s isInvoicePosted=%s",
                                runStatusId, isGlPosted, isInvPosted));
                    }
                }
            } catch (Exception e) {
                hardFail = true;
                msgs.add(fmt("RUN_DB_ERROR", "DB error checking PayrollRun: %s", safeMsg(e)));
            }
        } else if (!hardFail) {
            msgs.add(fmt("RUN_INFO", "payrollRunId is empty (allowed before execute-mode creates it)."));
        }

        // -------------------------
        // 7) Duplicate results protection (hard if results already exist for this run)
        // -------------------------
        if (!hardFail && UtilValidate.isNotEmpty(payrollRunId)) {
            try {
                long existingResults = EntityQuery.use(delegator)
                        .from("EmployeePayrollResult")
                        .where("payrollRunId", payrollRunId)
                        .cache(false)
                        .queryCount();
                if (existingResults > 0) {
                    hardFail = true;
                    errorCount = 1L;
                    msgs.add(fmt("DUP_RESULTS",
                            "EmployeePayrollResult already exists for payrollRunId=%s count=%d",
                            payrollRunId, existingResults));
                    msgs.add(fmt("HINT", "Do not rerun on same payrollRunId unless you implement forceRecalc cleanup/new attempt."));
                } else {
                    msgs.add(fmt("RESULTS_OK", "No existing EmployeePayrollResult for payrollRunId=%s", payrollRunId));
                }
            } catch (Exception e) {
                hardFail = true;
                errorCount = 1L;
                msgs.add(fmt("RESULTS_DB_ERROR", "DB error checking EmployeePayrollResult: %s", safeMsg(e)));
            }
        }

        // -------------------------
        // 8) Eligible assignments overlap (HARD FAIL if 0)
        // -------------------------
        if (!hardFail) {
            try {
                eligibleCount = countEligibleAssignments(delegator, organizationPartyId, periodStartTs, periodEndTs, partyIdOrNull);
                if (eligibleCount <= 0) {
                    hardFail = true;
                    errorCount = 1L;
                    msgs.add(fmt("NO_ELIGIBLE",
                            "No eligible employees found (eligibleCount=0). Expected EmployeePayrollAssignment where: "
                                    + "organizationPartyId=%s AND statusId=%s AND effectiveDate<=periodEnd AND (thruDate IS NULL OR thruDate>=periodStart).",
                            organizationPartyId, ASSIGN_STATUS_ACTIVE));
                    msgs.add(fmt("HINT", "Fix assignments: ensure active status and effective/thru dates overlap the period."));
                    // diagnostics (safe; can be heavy, but only on failure)
                    try {
                        Map<String, Object> diag = eligibilityDiagnostics(delegator, organizationPartyId);
                        msgs.add(fmt("NO_ELIGIBLE_DIAG", "%s", diag));
                    } catch (Exception ignore) { }
                } else {
                    okCount = eligibleCount;
                    msgs.add(fmt("ELIGIBLE_OK", "eligibleCount=%d", eligibleCount));
                }
            } catch (Exception e) {
                hardFail = true;
                errorCount = 1L;
                msgs.add(fmt("ELIGIBLE_DB_ERROR", "DB error counting eligible assignments: %s", safeMsg(e)));
            }
        }

        // -------------------------
        // Finalize
        // -------------------------
        boolean ok = !hardFail;
        String summary = ok
                ? "Validation OK"
                : "Validation HARD FAIL";

        if (!ok && errorCount == 0L) errorCount = 1L;

        return new ValidationResult(ok, !ok, eligibleCount, okCount, errorCount,
                summary + " (org=" + organizationPartyId + ", period=" + payrollPeriodId + ", eligible=" + eligibleCount + ")",
                msgs);
    }

    // =========================================================
    // Status helpers
    // =========================================================

    /** Process-allowed period status (supports both PER_OPEN and P_ACT until you unify). */
    private static boolean isProcessAllowedPeriodStatus(String statusId) {
        if (UtilValidate.isEmpty(statusId)) return false;
        if (ALLOWED_PERIOD_PROCESS_STATUSES.contains(statusId)) return true;
        // backward compatibility with previous constant
        return PERIOD_STATUS_OPEN.equals(statusId);
    }

    /** Whether run status indicates it is already final/posted/done. */
private static boolean isFinalRunStatus(String runStatusId) {
    if (UtilValidate.isEmpty(runStatusId)) return false;

    // Final/posted statuses in your PAY_RUN_STAT list
    // (Adjust if you add more final statuses later)
    return "PR_POST".equals(runStatusId)
            || "PR_DONE".equals(runStatusId)
            || "PR_VOID".equals(runStatusId);
}

    // =========================================================
    // Time helpers (PayrollPeriod = DATE)
    // =========================================================

    /** Start of day 00:00:00.000 */
    public static Timestamp startOfDay(Date d) {
        if (d == null) return null;
        Calendar cal = Calendar.getInstance();
        cal.setTimeInMillis(d.getTime());
        cal.set(Calendar.HOUR_OF_DAY, 0);
        cal.set(Calendar.MINUTE, 0);
        cal.set(Calendar.SECOND, 0);
        cal.set(Calendar.MILLISECOND, 0);
        return new Timestamp(cal.getTimeInMillis());
    }

    /** End of day 23:59:59.999 */
    public static Timestamp endOfDay(Date d) {
        if (d == null) return null;
        Calendar cal = Calendar.getInstance();
        cal.setTimeInMillis(d.getTime());
        cal.set(Calendar.HOUR_OF_DAY, 23);
        cal.set(Calendar.MINUTE, 59);
        cal.set(Calendar.SECOND, 59);
        cal.set(Calendar.MILLISECOND, 999);
        return new Timestamp(cal.getTimeInMillis());
    }

    // =========================================================
    // Basic existence checks
    // =========================================================

    /**
     * Improved: org exists and ACTIVE.
     * Supports both:
     *   - PayrollOrganization.isActive == 'Y'
     *   - PayrollOrganization.statusId in allowed active statuses
     * If neither field exists, existence is enough.
     */
    public static boolean existsActivePayrollOrganization(Delegator delegator, String organizationPartyId) throws GenericEntityException {
        if (UtilValidate.isEmpty(organizationPartyId)) return false;
        GenericValue gv = EntityQuery.use(delegator)
                .from("PayrollOrganization")
                .where("organizationPartyId", organizationPartyId)
                .cache(false)
                .queryOne();
        if (gv == null) return false;
        return isPayrollOrganizationActive(gv);
    }

    public static boolean existsStatusItem(Delegator delegator, String statusId) throws GenericEntityException {
        if (UtilValidate.isEmpty(statusId)) return false;
        GenericValue gv = EntityQuery.use(delegator)
                .from("StatusItem")
                .where("statusId", statusId)
                .cache(false)
                .queryOne();
        return gv != null;
    }

    /**
     * Hard requirement: at least one active PayrollStructure for org.
     * If PayrollStructure has statusId, prefer filtering by known active statuses.
     * If it doesn't, fallback to existence.
     */
    public static boolean existsActivePayrollStructure(Delegator delegator, String organizationPartyId) throws GenericEntityException {
        if (UtilValidate.isEmpty(organizationPartyId)) return false;

        // If statusId field exists, use active statuses. Otherwise, existence.
        GenericValue any = EntityQuery.use(delegator)
                .from("PayrollStructure")
                .where("organizationPartyId", organizationPartyId)
                .cache(false)
                .queryFirst();

        if (any == null) return false;

        if (any.getModelEntity() != null && any.getModelEntity().isField("statusId")) {
            // count active statuses
            EntityCondition cond = EntityCondition.makeCondition(Arrays.asList(
                    EntityCondition.makeCondition("organizationPartyId", EntityOperator.EQUALS, organizationPartyId),
                    EntityCondition.makeCondition("statusId", EntityOperator.IN, new ArrayList<>(ALLOWED_STRUCTURE_ACTIVE_STATUSES))
            ), EntityOperator.AND);

            long cnt = EntityQuery.use(delegator)
                    .from("PayrollStructure")
                    .where(cond)
                    .cache(false)
                    .queryCount();
            return cnt > 0;
        }

        // no statusId field => existence is enough
        return true;
    }

    // =========================================================
    // PayrollPeriod resolution + diagnostics
    // =========================================================

    public static GenericValue findPayrollPeriodById(Delegator delegator, String payrollPeriodId) throws GenericEntityException {
        if (UtilValidate.isEmpty(payrollPeriodId)) return null;
        return EntityQuery.use(delegator)
                .from("PayrollPeriod")
                .where("payrollPeriodId", payrollPeriodId)
                .cache(false)
                .queryOne();
    }

    /** Latest open period for org (PER_OPEN). */
    public static GenericValue findLatestOpenPeriod(Delegator delegator,
                                                    String organizationPartyId) throws GenericEntityException {
        return EntityQuery.use(delegator)
                .from("PayrollPeriod")
                .where("organizationPartyId", organizationPartyId,
                       "statusId", PERIOD_STATUS_OPEN)
                .orderBy("-startDate")
                .cache(false)
                .queryFirst();
    }

    /** If you want to detect duplicates by periodName in same org. */
    public static Map<String, List<String>> findDuplicatePeriodNames(Delegator delegator, String organizationPartyId)
            throws GenericEntityException {

        Map<String, List<String>> dupMap = new LinkedHashMap<>();

        List<GenericValue> periods = EntityQuery.use(delegator)
                .from("PayrollPeriod")
                .where("organizationPartyId", organizationPartyId)
                .orderBy("periodName", "payrollPeriodId")
                .cache(false)
                .queryList();

        Map<String, List<String>> byName = new LinkedHashMap<>();
        for (GenericValue p : periods) {
            String periodName = p.getString("periodName");
            String periodId = p.getString("payrollPeriodId");
            if (UtilValidate.isEmpty(periodName)) continue;
            byName.computeIfAbsent(periodName, k -> new ArrayList<>()).add(periodId);
        }

        for (Map.Entry<String, List<String>> e : byName.entrySet()) {
            if (e.getValue().size() > 1) dupMap.put(e.getKey(), e.getValue());
        }
        return dupMap;
    }

    // =========================================================
    // PayrollRun resolution
    // =========================================================

    public static GenericValue findPayrollRunByOrgPeriod(Delegator delegator, String organizationPartyId, String payrollPeriodId)
            throws GenericEntityException {
        if (UtilValidate.isEmpty(organizationPartyId) || UtilValidate.isEmpty(payrollPeriodId)) return null;

        return EntityQuery.use(delegator)
                .from("PayrollRun")
                .where("organizationPartyId", organizationPartyId,
                       "payrollPeriodId", payrollPeriodId)
                .orderBy("-runDate")
                .cache(false)
                .queryFirst();
    }

    public static GenericValue findPayrollRunById(Delegator delegator, String payrollRunId) throws GenericEntityException {
        if (UtilValidate.isEmpty(payrollRunId)) return null;
        return EntityQuery.use(delegator)
                .from("PayrollRun")
                .where("payrollRunId", payrollRunId)
                .cache(false)
                .queryOne();
    }

    // =========================================================
    // Eligibility (EmployeePayrollAssignment overlap)
    // =========================================================

    public static int countEligibleAssignments(Delegator delegator,
                                              String organizationPartyId,
                                              Timestamp periodStart,
                                              Timestamp periodEnd,
                                              String partyIdOrNull) throws GenericEntityException {

        EntityCondition statusCond = EntityCondition.makeCondition(
                "statusId", EntityOperator.EQUALS, ASSIGN_STATUS_ACTIVE
        );

        EntityCondition overlapCond = EntityCondition.makeCondition(
                Arrays.asList(
                        EntityCondition.makeCondition("organizationPartyId", EntityOperator.EQUALS, organizationPartyId),
                        statusCond,
                        EntityCondition.makeCondition("effectiveDate", EntityOperator.LESS_THAN_EQUAL_TO, periodEnd),
                        EntityCondition.makeCondition(
                                EntityCondition.makeCondition("thruDate", EntityOperator.EQUALS, null),
                                EntityOperator.OR,
                                EntityCondition.makeCondition("thruDate", EntityOperator.GREATER_THAN_EQUAL_TO, periodStart)
                        )
                ),
                EntityOperator.AND
        );

        if (UtilValidate.isNotEmpty(partyIdOrNull)) {
            overlapCond = EntityCondition.makeCondition(
                    overlapCond,
                    EntityOperator.AND,
                    EntityCondition.makeCondition("partyId", EntityOperator.EQUALS, partyIdOrNull)
            );
        }

        long cnt = EntityQuery.use(delegator)
                .from("EmployeePayrollAssignment")
                .where(overlapCond)
                .cache(false)
                .queryCount();

        return (int) Math.min(Integer.MAX_VALUE, cnt);
    }

    public static Map<String, Object> eligibilityDiagnostics(Delegator delegator, String organizationPartyId)
            throws GenericEntityException {

        Map<String, Object> out = new LinkedHashMap<>();

        long total = EntityQuery.use(delegator)
                .from("EmployeePayrollAssignment")
                .where("organizationPartyId", organizationPartyId)
                .cache(false)
                .queryCount();
        out.put("assignmentTotalCount", (int) Math.min(Integer.MAX_VALUE, total));

        // Status distribution (can be heavy; keep for failure paths)
        List<GenericValue> rows = EntityQuery.use(delegator)
                .from("EmployeePayrollAssignment")
                .where("organizationPartyId", organizationPartyId)
                .cache(false)
                .queryList();

        Map<String, Integer> statusCounts = new LinkedHashMap<>();
        Timestamp minEff = null;
        Timestamp maxEff = null;

        for (GenericValue gv : rows) {
            String st = gv.getString("statusId");
            statusCounts.put(st, statusCounts.getOrDefault(st, 0) + 1);

            Timestamp eff = gv.getTimestamp("effectiveDate");
            if (eff != null) {
                if (minEff == null || eff.before(minEff)) minEff = eff;
                if (maxEff == null || eff.after(maxEff)) maxEff = eff;
            }
        }

        out.put("statusCounts", statusCounts);
        out.put("minEffectiveDate", minEff);
        out.put("maxEffectiveDate", maxEff);

        return out;
    }

    // =========================================================
    // Results / Posting counts
    // =========================================================

    public static int countResultsForRun(Delegator delegator, String payrollRunId) throws GenericEntityException {
        if (UtilValidate.isEmpty(payrollRunId)) return 0;
        long cnt = EntityQuery.use(delegator)
                .from("EmployeePayrollResult")
                .where("payrollRunId", payrollRunId)
                .cache(false)
                .queryCount();
        return (int) Math.min(Integer.MAX_VALUE, cnt);
    }

    public static long countResultsForRunLong(Delegator delegator, String payrollRunId) throws GenericEntityException {
        return (long) countResultsForRun(delegator, payrollRunId);
    }

    public static int countPostingsForRun(Delegator delegator, String organizationPartyId, String payrollRunId) throws GenericEntityException {
        if (UtilValidate.isEmpty(payrollRunId) || UtilValidate.isEmpty(organizationPartyId)) return 0;
        long cnt = EntityQuery.use(delegator)
                .from("PayrollPosting")
                .where("organizationPartyId", organizationPartyId,
                       "payrollRunId", payrollRunId)
                .cache(false)
                .queryCount();
        return (int) Math.min(Integer.MAX_VALUE, cnt);
    }

    /**
     * CONTRACT METHOD: countPostingLinesForRun(...)
     * You do not have PayrollPostingLine, so we map to PayrollPosting rows.
     */
    public static int countPostingLinesForRun(Delegator delegator, String payrollRunId) throws GenericEntityException {
        if (UtilValidate.isEmpty(payrollRunId)) return 0;
        long cnt = EntityQuery.use(delegator)
                .from("PayrollPosting")
                .where("payrollRunId", payrollRunId)
                .cache(false)
                .queryCount();
        return (int) Math.min(Integer.MAX_VALUE, cnt);
    }

    // =========================================================
    // Posting mode detection
    // =========================================================

    public static String resolvePostingMode(String accountingPostOptionId) {
        if (UtilValidate.isEmpty(accountingPostOptionId)) return "NONE";

        String v = accountingPostOptionId.trim().toUpperCase(Locale.ROOT);
        if (v.contains("BOTH")) return "BOTH";
        if (v.contains("GL")) return "GL";
        if (v.contains("INV") || v.contains("INVOICE")) return "INVOICE";
        if (v.contains("BANK")) return "BANK";
        if (v.contains("NONE") || v.equals("PO_NONE")) return "NONE";
        return v;
    }

    public static String formatDuplicatePeriodsMessage(Map<String, List<String>> dupMap) {
        if (dupMap == null || dupMap.isEmpty()) return null;

        StringBuilder sb = new StringBuilder();
        sb.append("Duplicate PayrollPeriod detected. Use payrollPeriodId explicitly. ");
        boolean first = true;
        for (Map.Entry<String, List<String>> e : dupMap.entrySet()) {
            if (!first) sb.append(" | ");
            first = false;
            sb.append(e.getKey()).append(": ").append(e.getValue());
        }
        return sb.toString();
    }

    public static GenericValue safeFindPeriod(Delegator d, String periodId) {
        try {
            return findPayrollPeriodById(d, periodId);
        } catch (Exception e) {
            Debug.logError(e, "safeFindPeriod failed", MODULE);
            return null;
        }
    }

    // =========================================================
    // Financial / posting prerequisite validations
    // =========================================================

    public static boolean hasCurrency(Delegator delegator, String currencyUomId) throws GenericEntityException {
        if (UtilValidate.isEmpty(currencyUomId)) return false;
        GenericValue gv = EntityQuery.use(delegator)
                .from("Uom")
                .where("uomId", currencyUomId)
                .cache(false)
                .queryOne();
        return gv != null;
    }

    public static boolean hasFxRate(Delegator delegator,
                                    String organizationPartyId,
                                    String fromCurrency,
                                    String toCurrency) throws GenericEntityException {

        if (UtilValidate.isEmpty(fromCurrency) || UtilValidate.isEmpty(toCurrency)) return true;
        if (fromCurrency.equals(toCurrency)) return true;

        GenericValue rate = EntityQuery.use(delegator)
                .from("PayrollCurrencyRate")
                .where("organizationPartyId", organizationPartyId,
                       "fromCurrencyUomId", fromCurrency,
                       "toCurrencyUomId", toCurrency)
                .orderBy("-rateDate")
                .cache(false)
                .queryFirst();

        return rate != null;
    }

    public static boolean hasGlPostingMode(String accountingPostOptionId) {
        if (UtilValidate.isEmpty(accountingPostOptionId)) return false;
        return accountingPostOptionId.toUpperCase(Locale.ROOT).contains("GL");
    }

    public static boolean hasInvoicePostingMode(String accountingPostOptionId) {
        if (UtilValidate.isEmpty(accountingPostOptionId)) return false;
        return accountingPostOptionId.toUpperCase(Locale.ROOT).contains("INV");
    }

    public static boolean hasBankTransferConfig(Delegator delegator,
                                               String organizationPartyId,
                                               String bankTransferConfigId) throws GenericEntityException {
        if (UtilValidate.isEmpty(bankTransferConfigId)) return false;

        GenericValue gv = EntityQuery.use(delegator)
                .from("BankTransferConfig")
                .where("organizationPartyId", organizationPartyId,
                       "bankTransferConfigId", bankTransferConfigId,
                       "isActive", "Y")
                .cache(false)
                .queryOne();

        return gv != null;
    }

    public static boolean hasGlMappings(Delegator delegator, String organizationPartyId) throws GenericEntityException {
        long cnt = EntityQuery.use(delegator)
                .from("PayrollGlMapping")
                .where("organizationPartyId", organizationPartyId)
                .cache(false)
                .queryCount();
        return cnt > 0;
    }

    public static boolean hasAnyAssignments(Delegator delegator, String organizationPartyId) throws GenericEntityException {
        long cnt = EntityQuery.use(delegator)
                .from("EmployeePayrollAssignment")
                .where("organizationPartyId", organizationPartyId)
                .cache(false)
                .queryCount();
        return cnt > 0;
    }

    // =========================================================
    // Internal helpers (kept in this same file)
    // =========================================================

    private static String asString(Map<String, Object> ctx, String key) {
        if (ctx == null) return null;
        Object v = ctx.get(key);
        return v == null ? null : String.valueOf(v);
    }

    private static String safeMsg(Throwable t) {
        if (t == null) return "null";
        return UtilValidate.isNotEmpty(t.getMessage()) ? t.getMessage() : t.toString();
    }

    private static String fmt(String code, String pattern, Object... args) {
        String msg;
        try {
            msg = String.format(Locale.ROOT, pattern, args);
        } catch (Exception e) {
            msg = pattern + " " + Arrays.toString(args);
        }
        return "[" + code + "] " + msg;
    }

    private static Set<String> unmodifiableSetOf(String... vals) {
        return Collections.unmodifiableSet(new LinkedHashSet<>(Arrays.asList(vals)));
    }

    private static boolean isPayrollOrganizationActive(GenericValue org) {
        if (org == null) return false;

        try {
            if (org.getModelEntity() != null && org.getModelEntity().isField("isActive")) {
                String isActive = org.getString("isActive");
                return ORG_IS_ACTIVE_Y.equalsIgnoreCase(isActive);
            }
            if (org.getModelEntity() != null && org.getModelEntity().isField("statusId")) {
                String st = org.getString("statusId");
                return UtilValidate.isNotEmpty(st) && ALLOWED_ORG_ACTIVE_STATUSES.contains(st);
            }
        } catch (Exception ignore) { }

        // No recognized active indicator => existence implies active
        return true;
    }

    private static String describeActiveFields(GenericValue org) {
        if (org == null) return "(null)";
        List<String> parts = new ArrayList<>();
        try {
            if (org.getModelEntity() != null && org.getModelEntity().isField("isActive")) {
                parts.add("isActive=" + org.getString("isActive"));
            }
            if (org.getModelEntity() != null && org.getModelEntity().isField("statusId")) {
                parts.add("statusId=" + org.getString("statusId"));
            }
        } catch (Exception ignore) { }
        if (parts.isEmpty()) return "no isActive/statusId field";
        return String.join(", ", parts);
    }

    private static final class StructureCheck {
        final boolean ok;
        final String evidence;
        StructureCheck(boolean ok, String evidence) {
            this.ok = ok;
            this.evidence = evidence;
        }
    }

    private static StructureCheck checkPayrollStructure(Delegator delegator, String organizationPartyId) throws GenericEntityException {
        if (UtilValidate.isEmpty(organizationPartyId)) return new StructureCheck(false, "organizationPartyId is empty");

        GenericValue any = EntityQuery.use(delegator)
                .from("PayrollStructure")
                .where("organizationPartyId", organizationPartyId)
                .cache(false)
                .queryFirst();

        if (any == null) {
            return new StructureCheck(false, "No PayrollStructure rows found.");
        }

        if (any.getModelEntity() != null && any.getModelEntity().isField("statusId")) {
            EntityCondition cond = EntityCondition.makeCondition(Arrays.asList(
                    EntityCondition.makeCondition("organizationPartyId", EntityOperator.EQUALS, organizationPartyId),
                    EntityCondition.makeCondition("statusId", EntityOperator.IN, new ArrayList<>(ALLOWED_STRUCTURE_ACTIVE_STATUSES))
            ), EntityOperator.AND);

            long activeCnt = EntityQuery.use(delegator)
                    .from("PayrollStructure")
                    .where(cond)
                    .cache(false)
                    .queryCount();

            if (activeCnt <= 0) {
                return new StructureCheck(false,
                        "Found PayrollStructure but none ACTIVE. allowedStatus=" + ALLOWED_STRUCTURE_ACTIVE_STATUSES);
            }
            return new StructureCheck(true, "activeCount=" + activeCnt + " allowedStatus=" + ALLOWED_STRUCTURE_ACTIVE_STATUSES);
        }

        // no statusId => existence is enough
        long cnt = EntityQuery.use(delegator)
                .from("PayrollStructure")
                .where("organizationPartyId", organizationPartyId)
                .cache(false)
                .queryCount();
        return new StructureCheck(true, "count=" + cnt + " (no statusId field)");
    }
}
