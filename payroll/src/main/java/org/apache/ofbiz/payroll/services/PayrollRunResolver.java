// File: plugins/payroll/src/main/java/org/apache/ofbiz/payroll/services/PayrollRunResolver.java
package org.apache.ofbiz.payroll.services;

import org.apache.ofbiz.base.util.Debug;
import org.apache.ofbiz.base.util.UtilValidate;
import org.apache.ofbiz.entity.Delegator;
import org.apache.ofbiz.entity.GenericEntityException;
import org.apache.ofbiz.entity.GenericValue;
import org.apache.ofbiz.entity.util.EntityQuery;
import org.apache.ofbiz.service.DispatchContext;
import org.apache.ofbiz.service.LocalDispatcher;

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

/**
 * Resolves payrollRunId vs payrollPeriodId vs latest open period.
 *
 * Backward compatible:
 * - Keeps Map-returning resolve(delegator, org, runId, periodId)
 * - Adds typed resolve(...) returning ResolvedRun for scheduler compile contract
 * - Adds scheduler overload resolve(delegator, dispatcher, userLogin, org, periodIdIn, runIdIn, ...)
 */
public final class PayrollRunResolver {

    private static final String MODULE = PayrollRunResolver.class.getName();

    private PayrollRunResolver() {}

    // =========================================================
    // Scheduler overload (compile contract for PayrollSchedulerServicesV2)
    // Extra parameters are accepted for compatibility but not used here.
    // =========================================================
    public static ResolvedRun resolve(Delegator delegator,
                                     LocalDispatcher dispatcher,
                                     GenericValue userLogin,
                                     String organizationPartyId,
                                     String payrollPeriodIdIn,
                                     String payrollRunIdIn,
                                     String currencyUomId,
                                     String accountingPostOptionId,
                                     Object flagsOrMode,
                                     Map<String, ?> extraCtx) {

        // Delegate to the typed resolver (strict=false)
        return resolve(delegator, organizationPartyId, payrollRunIdIn, payrollPeriodIdIn, false);
    }

    // =========================================================
    // Typed resolver (used by scheduler + service wrapper)
    // =========================================================
    public static ResolvedRun resolve(Delegator delegator,
                                     String organizationPartyId,
                                     String payrollRunIdIn,
                                     String payrollPeriodIdIn,
                                     boolean strict) {
        Map<String, Object> m = resolve(delegator, organizationPartyId, payrollRunIdIn, payrollPeriodIdIn);
        boolean ok = Boolean.TRUE.equals(m.get("ok"));
        if (!ok && strict) {
            throw new IllegalArgumentException(String.valueOf(m.get("errorMessage")));
        }
        return new ResolvedRun(
                (String) m.get("payrollRunIdResolved"),
                (String) m.get("payrollPeriodIdResolved"),
                ok,
                (String) m.get("errorCode"),
                (String) m.get("errorMessage")
        );
    }

    /** Convenience overload used by OFBiz service methods. */
    public static ResolvedRun resolve(DispatchContext dctx, Map<String, Object> context) {
        Delegator delegator = dctx != null ? dctx.getDelegator() : null;
        String orgId = asString(context, "organizationPartyId");
        String runId = asString(context, "payrollRunId");
        String periodId = asString(context, "payrollPeriodId");
        // default: non-strict (do not throw; let caller build response)
        return resolve(delegator, orgId, runId, periodId, false);
    }

    // =========================================================
    // Map-based resolver (kept as-is)
    // =========================================================
    public static Map<String, Object> resolve(Delegator delegator,
                                             String organizationPartyId,
                                             String payrollRunIdIn,
                                             String payrollPeriodIdIn) {
        if (delegator == null) {
            return error("RESOLVE_NO_DELEGATOR", "Delegator is null");
        }
        if (UtilValidate.isEmpty(organizationPartyId)) {
            return error("RESOLVE_NO_ORG", "organizationPartyId is required to resolve payroll period/run");
        }

        try {
            // Rule 1: payrollRunId provided (strongest)
            if (UtilValidate.isNotEmpty(payrollRunIdIn)) {
                GenericValue run = EntityQuery.use(delegator)
                        .from("PayrollRun")
                        .where("payrollRunId", payrollRunIdIn,
                               "organizationPartyId", organizationPartyId)
                        .queryOne();
                if (run == null) {
                    return error("RESOLVE_RUN_NOT_FOUND",
                            "PayrollRun not found for org=" + organizationPartyId + ", payrollRunId=" + payrollRunIdIn);
                }
                String resolvedPeriodId = run.getString("payrollPeriodId");
                if (UtilValidate.isEmpty(resolvedPeriodId)) {
                    return error("RESOLVE_RUN_NO_PERIOD",
                            "PayrollRun " + payrollRunIdIn + " has no payrollPeriodId");
                }
                GenericValue period = EntityQuery.use(delegator)
        .from("PayrollPeriod")
        .where("payrollPeriodId", resolvedPeriodId,
               "organizationPartyId", organizationPartyId)
        .queryOne();

ensureRunDefaults(delegator, run, period, organizationPartyId, resolvedPeriodId);

return ok(run.getString("payrollRunId"), resolvedPeriodId);
            }

            // Rule 2: payrollPeriodId provided
            String periodIdResolved = payrollPeriodIdIn;

            // Rule 3: neither provided => latest open period
            if (UtilValidate.isEmpty(periodIdResolved)) {
                GenericValue period = EntityQuery.use(delegator)
                        .from("PayrollPeriod")
                        .where("organizationPartyId", organizationPartyId,
                               "statusId", PayrollStageIds.PER_STATUS_OPEN)
                        .orderBy("-startDate") // latest startDate
                        .queryFirst();
                if (period == null) {
                    return error("RESOLVE_NO_OPEN_PERIOD",
                            "No OPEN PayrollPeriod found for org=" + organizationPartyId
                                    + " (expected statusId=" + PayrollStageIds.PER_STATUS_OPEN + ")");
                }
                periodIdResolved = period.getString("payrollPeriodId");
            } else {
                // Validate the period exists for org
                GenericValue period = EntityQuery.use(delegator)
                        .from("PayrollPeriod")
                        .where("payrollPeriodId", periodIdResolved,
                               "organizationPartyId", organizationPartyId)
                        .queryOne();
                if (period == null) {
                    return error("RESOLVE_PERIOD_NOT_FOUND",
                            "PayrollPeriod not found for org=" + organizationPartyId + ", payrollPeriodId=" + periodIdResolved);
                }
            }

            // Resolve run for (org, period)
            long cnt = EntityQuery.use(delegator)
                    .from("PayrollRun")
                    .where("organizationPartyId", organizationPartyId,
                           "payrollPeriodId", periodIdResolved)
                    .queryCount();

            if (cnt == 0) {
                return ok(null, periodIdResolved);
            }
            if (cnt == 1) {
                GenericValue run = EntityQuery.use(delegator)
                        .from("PayrollRun")
                        .where("organizationPartyId", organizationPartyId,
                               "payrollPeriodId", periodIdResolved)
                        .orderBy("-runDate")
                        .queryFirst();
               // return ok(run != null ? run.getString("payrollRunId") : null, periodIdResolved);
			   GenericValue period = EntityQuery.use(delegator)
        .from("PayrollPeriod")
        .where("payrollPeriodId", periodIdResolved,
               "organizationPartyId", organizationPartyId)
        .queryOne();

ensureRunDefaults(delegator, run, period, organizationPartyId, periodIdResolved);

return ok(run != null ? run.getString("payrollRunId") : null, periodIdResolved);
            }

            return error("RESOLVE_AMBIGUOUS_RUN",
                    "Multiple PayrollRun rows found for org=" + organizationPartyId
                            + ", payrollPeriodId=" + periodIdResolved + ". Provide payrollRunId explicitly.");

        } catch (GenericEntityException e) {
            Debug.logError(e, "Error resolving PayrollRun/PayrollPeriod", MODULE);
            return error("RESOLVE_DB_ERROR", "DB error while resolving run/period: " + e.getMessage());
        }
    }

    // =========================================================
    // DTO required by scheduler compile contract
    // =========================================================
    public static final class ResolvedRun {
        private final String payrollRunIdResolved;
        private final String payrollPeriodIdResolved;
        private final boolean ok;
        private final String errorCode;
        private final String errorMessage;

        public ResolvedRun(String payrollRunIdResolved,
                           String payrollPeriodIdResolved,
                           boolean ok,
                           String errorCode,
                           String errorMessage) {
            this.payrollRunIdResolved = payrollRunIdResolved;
            this.payrollPeriodIdResolved = payrollPeriodIdResolved;
            this.ok = ok;
            this.errorCode = errorCode;
            this.errorMessage = errorMessage;
        }

        public String getPayrollRunIdResolved() { return payrollRunIdResolved; }
        public String getPayrollPeriodIdResolved() { return payrollPeriodIdResolved; }

        public boolean isOk() { return ok; }
        public String getErrorCode() { return errorCode; }
        public String getErrorMessage() { return errorMessage; }
    }

    // =========================================================
    // Helpers
    // =========================================================
    private static Map<String, Object> ok(String payrollRunIdResolved, String payrollPeriodIdResolved) {
        Map<String, Object> m = new HashMap<>();
        m.put("ok", Boolean.TRUE);
        m.put("payrollRunIdResolved", payrollRunIdResolved);      // may be null
        m.put("payrollPeriodIdResolved", payrollPeriodIdResolved);
        return Collections.unmodifiableMap(m);
    }

    private static Map<String, Object> error(String code, String msg) {
        Map<String, Object> m = new HashMap<>();
        m.put("ok", Boolean.FALSE);
        m.put("errorCode", code);
        m.put("errorMessage", msg);
        return Collections.unmodifiableMap(m);
    }

    private static String asString(Map<String, Object> ctx, String key) {
        if (ctx == null) return null;
        Object v = ctx.get(key);
        return v == null ? null : String.valueOf(v);
    }

private static void ensureRunDefaults(Delegator delegator,
                                      GenericValue run,
                                      GenericValue period,
                                      String organizationPartyId,
                                      String payrollPeriodIdResolved) throws GenericEntityException {
    if (run == null) return;

    boolean changed = false;

    String runTypeId = run.getString("runTypeId");
    if (UtilValidate.isEmpty(runTypeId)) {
        run.set("runTypeId", "RUN_MONTHLY");
        changed = true;
    }

    String runName = run.getString("runName");
    if (UtilValidate.isEmpty(runName)) {
        String periodName = null;
        try { periodName = period != null ? period.getString("periodName") : null; } catch (Exception ignored) {}
        String base = organizationPartyId + "-" + (UtilValidate.isNotEmpty(periodName) ? periodName : payrollPeriodIdResolved);
        run.set("runName", base + runTypeId );
        changed = true;
    }

    // Only persist if we actually set something
    if (changed) {
        run.store();
    }
}


}
