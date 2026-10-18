package org.apache.ofbiz.payroll.services;
import org.apache.ofbiz.base.util.Debug;
import org.apache.ofbiz.base.util.UtilDateTime;
import org.apache.ofbiz.base.util.UtilMisc;
import org.apache.ofbiz.base.util.UtilValidate;

import org.apache.ofbiz.entity.Delegator;
import org.apache.ofbiz.entity.GenericEntityException;
import org.apache.ofbiz.entity.GenericValue;
import org.apache.ofbiz.entity.condition.EntityCondition;
import org.apache.ofbiz.entity.condition.EntityOperator;
import org.apache.ofbiz.entity.util.EntityQuery;

import org.apache.ofbiz.service.DispatchContext;
import org.apache.ofbiz.service.LocalDispatcher;
import org.apache.ofbiz.service.ServiceUtil;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.apache.ofbiz.service.ServiceUtil;
import org.apache.ofbiz.payroll.util.PayrollStatusIds;
import org.apache.ofbiz.payroll.util.PayrollGovernanceUtil;


public class PayrollSnapshotServices {
    public static final String MODULE = PayrollSnapshotServices.class.getName();

    // Entities
    private static final String ENTITY_OT_REQ    = "EmployeeOvertimeRequest";
    private static final String ENTITY_PERIOD    = "PayrollPeriod";
    private static final String ENTITY_RUN       = "PayrollRun";
    private static final String ENTITY_SNAPSHOT  = "PayrollInputSnapshot";

  // Status IDs
private static final String OT_STATUS_APPR  = "OT_APPR";
private static final String OT_STATUS_USED  = "OT_USED";

// Snapshot
private static final String SNAP_STATUS_NEW = "SNAP_NEW";

// Snapshot source
private static final String SRC_BUILDER     = "SRC_POLICY";


    // Enum IDs you ALREADY loaded in HR_SOURCE_TYPE:
    // SRC_FP, SRC_API, SRC_MANUAL, SRC_POLICY
    // For fully system-generated builder, SRC_POLICY is best.
    
    // Payroll run statuses: allow building only in these (tweak if you want)
    private static boolean isRunStatusBuildable(String runStatusId) {
        // You can expand this list if needed.
        return "PR_NEW".equals(runStatusId)
                || "PR_DRAFT".equals(runStatusId)
                || "PR_SUBM".equals(runStatusId)
                || "PR_PROC".equals(runStatusId);
    }
// helper: overlap check for leave range against payroll period
private static boolean overlaps(java.sql.Date from, java.sql.Date thru, java.sql.Date periodStart, java.sql.Date periodEnd) {
    if (from == null || thru == null || periodStart == null || periodEnd == null) return false;
    return !from.after(periodEnd) && !thru.before(periodStart);
}

private static BigDecimal sumUnpaidLeaveDays(Delegator delegator, String orgId, String partyId,
                                           java.sql.Date periodStart, java.sql.Date periodEnd) throws Exception {
    BigDecimal total = BigDecimal.ZERO;

    // pull approved leave rows for employee; filter overlap in Java (safe and simple)
    java.util.List<GenericValue> rows = EntityQuery.use(delegator).from("EmployeeLeaveRequest")
            .where("organizationPartyId", orgId,
                   "partyId", partyId,
                   "statusId", "LR_APPR",
                   "paidFlag", "N")
            .queryList();

    for (GenericValue lr : rows) {
        java.sql.Date from = lr.getDate("fromDate");
        java.sql.Date thru = lr.getDate("thruDate");
        if (overlaps(from, thru, periodStart, periodEnd)) {
            BigDecimal days = asBigDecimal(lr.get("totalDays"));
            if (days != null) total = total.add(days);
        }
    }
    return total;
}

private static long sumUnpaidAbsenceMinutes(Delegator delegator, String orgId, String partyId,
                                          java.sql.Date periodStart, java.sql.Date periodEnd) throws Exception {
    // workDate is a date; filter by range at DB level
    java.util.List<GenericValue> rows = EntityQuery.use(delegator).from("EmployeeAbsenceRequest")
            .where("organizationPartyId", orgId,
                   "partyId", partyId,
                   "statusId", "ABS_APPR",
                   "paidFlag", "N")
            .queryList();

    long total = 0L;
    for (GenericValue ar : rows) {
        java.sql.Date d = ar.getDate("workDate");
        if (d != null && !d.before(periodStart) && !d.after(periodEnd)) {
            Long mins = ar.getLong("minutes");
            if (mins != null) total += mins;
        }
    }
    return total;
}


private static void createResultDeduction(Delegator delegator,
                                        String payrollResultId,
                                        String orgId,
                                        String partyId,
                                        String deductionTypeId,
                                        BigDecimal amount) throws Exception {
    if (amount == null || amount.compareTo(BigDecimal.ZERO) <= 0) return;

    String id = delegator.getNextSeqId("PayrollDeductionItem");
    GenericValue di = delegator.makeValue("PayrollDeductionItem", UtilMisc.toMap(
            "payrollDeductionItemId", id,
            "payrollResultId", payrollResultId,
            "deductionTypeId", deductionTypeId,
            "amount", amount,
            "partyId", partyId,
            "organizationPartyId", orgId
    ));
    di.create();
}


public static Map<String, Object> buildPayrollInputSnapshotV2(DispatchContext dctx, Map<String, ?> context) {
    Delegator delegator = dctx.getDelegator();
    LocalDispatcher dispatcher = dctx.getDispatcher();
    GenericValue userLogin = (GenericValue) context.get("userLogin");

    String organizationPartyId = (String) context.get("organizationPartyId");
    String payrollRunId = (String) context.get("payrollRunId");
    String payrollPeriodId = (String) context.get("payrollPeriodId");

    String forceRebuildStr = (String) context.get("forceRebuild"); // Y/N
    String dryRunStr       = (String) context.get("dryRun");       // Y/N

    boolean forceRebuild = "Y".equalsIgnoreCase(forceRebuildStr);
    boolean dryRun = "Y".equalsIgnoreCase(dryRunStr);

    // keep internal knob
    // boolean markOvertimeUsed = true;
    boolean markOvertimeUsed = false;

    Map<String, Object> out = ServiceUtil.returnSuccess();
    Map<String, Object> data = new HashMap<>();
    Map<String, Object> legacyData = new HashMap<>();
    out.put("data", data);
    out.put("legacyData", legacyData);

    try {
        if (UtilValidate.isEmpty(organizationPartyId)
                || UtilValidate.isEmpty(payrollRunId)
                || UtilValidate.isEmpty(payrollPeriodId)) {
            return ServiceUtil.returnError("organizationPartyId, payrollRunId, payrollPeriodId are required.");
        }
        if (userLogin == null) {
            return ServiceUtil.returnError("userLogin is required.");
        }

        data.put("organizationPartyId", organizationPartyId);
        data.put("payrollRunId", payrollRunId);
        data.put("payrollPeriodId", payrollPeriodId);
        data.put("dryRun", dryRun ? "Y" : "N");
        data.put("forceRebuild", forceRebuild ? "Y" : "N");

        // 0) Validate payroll period (org + period)
        GenericValue period = EntityQuery.use(delegator)
                .from(ENTITY_PERIOD)
                .where("organizationPartyId", organizationPartyId,
                       "payrollPeriodId", payrollPeriodId)
                .queryOne();

        if (period == null) {
            return ServiceUtil.returnError(
                    "PayrollPeriod not found for org=" + organizationPartyId + " period=" + payrollPeriodId
            );
        }

        java.sql.Date periodStart = period.getDate("startDate");
        java.sql.Date periodEnd = period.getDate("endDate");
        if (periodStart == null || periodEnd == null) {
            return ServiceUtil.returnError(
                    "PayrollPeriod startDate/endDate is missing for " + payrollPeriodId
            );
        }
        Timestamp periodStartTs = new Timestamp(periodStart.getTime());

        // 1) Validate payroll run belongs to org + period and status is buildable
        GenericValue run = EntityQuery.use(delegator)
                .from(ENTITY_RUN)
                .where("organizationPartyId", organizationPartyId,
                       "payrollRunId", payrollRunId)
                .queryOne();

        if (run == null) {
            return ServiceUtil.returnError(
                    "PayrollRun not found for org=" + organizationPartyId + " run=" + payrollRunId
            );
        }

        String runPeriodId = run.getString("payrollPeriodId");
        if (!payrollPeriodId.equals(runPeriodId)) {
            return ServiceUtil.returnError(
                    "PayrollRun " + payrollRunId + " is for period " + runPeriodId
                            + " but request period is " + payrollPeriodId
            );
        }

        // Governance pre-check: correct location
        PayrollGovernanceUtil.ensureRunAndPeriodEditable(
                delegator,
                organizationPartyId,
                payrollPeriodId,
                payrollRunId
        );

        String runStatusId = run.getString("statusId");

        // Governance/status rule:
        // allow build at PR_PROCESSED (normal)
        // allow rebuild at PR_DONE only when forceRebuild=Y
        boolean isProcessed = PayrollStatusIds.RUN_PROCESSED.equals(runStatusId);
        boolean isDone = PayrollStatusIds.RUN_DONE.equals(runStatusId);

        if (!(isProcessed || (isDone && forceRebuild))) {
            return ServiceUtil.returnError("Run status is not buildable: " + runStatusId);
        }

        String snapshotBatchId = "SNAP_" + UUID.randomUUID().toString().replace("-", "")
                .substring(0, 16).toUpperCase();
        Timestamp now = UtilDateTime.nowTimestamp();

        Debug.logInfo("SNAPSHOT_BUILDER_V2: org=" + organizationPartyId
                + " run=" + payrollRunId
                + " period=" + payrollPeriodId
                + " start=" + periodStart
                + " end=" + periodEnd
                + " forceRebuild=" + forceRebuild
                + " dryRun=" + dryRun, MODULE);

        // 2) Existing snapshots handling
        long existingCnt = EntityQuery.use(delegator)
                .from(ENTITY_SNAPSHOT)
                .where("organizationPartyId", organizationPartyId,
                       "payrollRunId", payrollRunId,
                       "payrollPeriodId", payrollPeriodId)
                .queryCount();

        if (existingCnt > 0 && !forceRebuild) {
            return ServiceUtil.returnError(
                    "Snapshots already exist for this run/period (" + existingCnt
                            + "). Use forceRebuild=Y to rebuild cleanly."
            );
        }

        if (existingCnt > 0 && forceRebuild && !dryRun) {
            List<GenericValue> existing = EntityQuery.use(delegator)
                    .from(ENTITY_SNAPSHOT)
                    .where("organizationPartyId", organizationPartyId,
                           "payrollRunId", payrollRunId,
                           "payrollPeriodId", payrollPeriodId)
                    .queryList();
            for (GenericValue gv : existing) {
                gv.remove();
            }
            Debug.logInfo("SNAPSHOT_BUILDER_V2: removed existing snapshots=" + existingCnt, MODULE);
        }

        // ==========================================================
        // 3) Collect inputs into per-employee aggregates
        // ==========================================================
        class Agg {
            long otMinutes = 0L;
            long absenceMinutes = 0L;
            BigDecimal unpaidLeaveDays = BigDecimal.ZERO;

            String overtimeRequestId = null;
            String absenceRequestId = null;
            String leaveRequestId = null;
        }

        Map<String, Agg> byEmployee = new HashMap<>();

        // ==========================================================
        // 3.0) Seed employees from active assignments
        // ==========================================================
        EntityCondition assgnCond = EntityCondition.makeCondition(Arrays.asList(
                EntityCondition.makeCondition("organizationPartyId", EntityOperator.EQUALS, organizationPartyId),
                EntityCondition.makeCondition(
                        EntityCondition.makeCondition("thruDate", EntityOperator.EQUALS, null),
                        EntityOperator.OR,
                        EntityCondition.makeCondition("thruDate", EntityOperator.GREATER_THAN, periodStartTs)
                )
        ), EntityOperator.AND);

        List<GenericValue> assgnRows = EntityQuery.use(delegator)
                .from("EmployeePayrollAssignment")
                .where(assgnCond)
                .queryList();

        for (GenericValue asg : assgnRows) {
            String partyId = asg.getString("partyId");
            if (UtilValidate.isNotEmpty(partyId)) {
                byEmployee.computeIfAbsent(partyId, k -> new Agg());
            }
        }

        Debug.logInfo("SNAPSHOT_BUILDER_V2: seeded employees from assignments=" + byEmployee.size(), MODULE);

        // ==========================================================
        // 3.a) Overtime approved/used in period, payFlag=Y
        // ==========================================================
        EntityCondition otCond = EntityCondition.makeCondition(Arrays.asList(
                EntityCondition.makeCondition("organizationPartyId", EntityOperator.EQUALS, organizationPartyId),
                EntityCondition.makeCondition("payFlag", EntityOperator.EQUALS, "Y"),
                EntityCondition.makeCondition("statusId", EntityOperator.IN, Arrays.asList(OT_STATUS_APPR, OT_STATUS_USED)),
                EntityCondition.makeCondition("workDate", EntityOperator.GREATER_THAN_EQUAL_TO, periodStart),
                EntityCondition.makeCondition("workDate", EntityOperator.LESS_THAN_EQUAL_TO, periodEnd)
        ), EntityOperator.AND);

        List<GenericValue> otRows = EntityQuery.use(delegator)
                .from(ENTITY_OT_REQ)
                .where(otCond)
                .queryList();

        Debug.logInfo("SNAPSHOT_BUILDER_V2: overtime rows in period=" + (otRows == null ? 0 : otRows.size()), MODULE);

        long otSkippedAlreadySnap = 0L;
        for (GenericValue ot : otRows) {
            String overtimeRequestId = ot.getString("overtimeRequestId");
            if (UtilValidate.isNotEmpty(overtimeRequestId)) {
                long existsLink = EntityQuery.use(delegator)
                        .from(ENTITY_SNAPSHOT)
                        .where("organizationPartyId", organizationPartyId,
                               "overtimeRequestId", overtimeRequestId)
                        .queryCount();
                if (existsLink > 0) {
                    otSkippedAlreadySnap++;
                    continue;
                }
            }

            String partyId = ot.getString("partyId");
            if (UtilValidate.isEmpty(partyId)) continue;

            long approvedMinutes = asBigDecimal(ot.get("approvedMinutes")).longValue();
            if (approvedMinutes <= 0L) {
                approvedMinutes = asBigDecimal(ot.get("requestedMinutes")).longValue();
            }
            if (approvedMinutes <= 0L) continue;

            Agg a = byEmployee.computeIfAbsent(partyId, k -> new Agg());
            a.otMinutes += approvedMinutes;

            if (UtilValidate.isEmpty(a.overtimeRequestId)) {
                a.overtimeRequestId = overtimeRequestId;
            }
        }

        // ==========================================================
        // 3.b) Unpaid leave days (approved) overlapping period
        // ==========================================================
        EntityCondition lrCond = EntityCondition.makeCondition(Arrays.asList(
                EntityCondition.makeCondition("organizationPartyId", EntityOperator.EQUALS, organizationPartyId),
                EntityCondition.makeCondition("statusId", EntityOperator.EQUALS, "LV_APPR"),
                EntityCondition.makeCondition("paidFlag", EntityOperator.EQUALS, "N")
        ), EntityOperator.AND);

        List<GenericValue> leaveRows = EntityQuery.use(delegator)
                .from("EmployeeLeaveRequest")
                .where(lrCond)
                .queryList();

        long leaveCount = (leaveRows == null ? 0 : leaveRows.size());
        Debug.logInfo("SNAPSHOT_BUILDER_V2: unpaid approved leave rows (org-wide)=" + leaveCount, MODULE);

        for (GenericValue lr : leaveRows) {
            String partyId = lr.getString("partyId");
            if (UtilValidate.isEmpty(partyId)) continue;

            java.sql.Date from = lr.getDate("fromDate");
            java.sql.Date thru = lr.getDate("thruDate");
            if (from == null || thru == null) continue;

            boolean overlap = !(from.after(periodEnd) || thru.before(periodStart));
            if (!overlap) continue;

            BigDecimal days = asBigDecimal(lr.get("totalDays"));
            if (days == null) days = BigDecimal.ZERO;
            if (days.compareTo(BigDecimal.ZERO) <= 0) continue;

            Agg a = byEmployee.computeIfAbsent(partyId, k -> new Agg());
            a.unpaidLeaveDays = a.unpaidLeaveDays.add(days);

            if (UtilValidate.isEmpty(a.leaveRequestId)) {
                a.leaveRequestId = lr.getString("leaveRequestId");
            }
        }

        // ==========================================================
        // 3.c) Unpaid absence minutes (approved) within period
        // ==========================================================
        EntityCondition absCond = EntityCondition.makeCondition(Arrays.asList(
                EntityCondition.makeCondition("organizationPartyId", EntityOperator.EQUALS, organizationPartyId),
                EntityCondition.makeCondition("statusId", EntityOperator.EQUALS, "ABS_APPR"),
                EntityCondition.makeCondition("paidFlag", EntityOperator.EQUALS, "N"),
                EntityCondition.makeCondition("workDate", EntityOperator.GREATER_THAN_EQUAL_TO, periodStart),
                EntityCondition.makeCondition("workDate", EntityOperator.LESS_THAN_EQUAL_TO, periodEnd)
        ), EntityOperator.AND);

        List<GenericValue> absRows = EntityQuery.use(delegator)
                .from("EmployeeAbsenceRequest")
                .where(absCond)
                .queryList();

        Debug.logInfo("SNAPSHOT_BUILDER_V2: unpaid approved absence rows in period=" + (absRows == null ? 0 : absRows.size()), MODULE);

        for (GenericValue ar : absRows) {
            String partyId = ar.getString("partyId");
            if (UtilValidate.isEmpty(partyId)) continue;

            Long mins = ar.getLong("minutes");
            if (mins == null) mins = 0L;
            if (mins <= 0L) continue;

            Agg a = byEmployee.computeIfAbsent(partyId, k -> new Agg());
            a.absenceMinutes += mins;

            if (UtilValidate.isEmpty(a.absenceRequestId)) {
                a.absenceRequestId = ar.getString("absenceRequestId");
            }
        }

        // ==========================================================
        // 4) Insert snapshot rows (1 per employee aggregate)
        // ==========================================================
        long inserted = 0L;
        long skippedNoActivity = 0L;

        for (Map.Entry<String, Agg> e : byEmployee.entrySet()) {
            String partyId = e.getKey();
            Agg a = e.getValue();

            if (dryRun) {
                inserted++;
                continue;
            }

            Map<String, Object> fields = UtilMisc.toMap(
                    "payrollRunId", payrollRunId,
                    "payrollPeriodId", payrollPeriodId,
                    "partyId", partyId,
                    "workDate", null,

                    "scheduledMinutes", 0L,
                    "workedMinutes", 0L,
                    "overtimeMinutesApproved", a.otMinutes,
                    "lateMinutes", 0L,
                    "earlyLeaveMinutes", 0L,
                    "absenceMinutesApproved", a.absenceMinutes,
                    "leaveDaysApproved", a.unpaidLeaveDays,

                    "sourceTypeEnumId", SRC_BUILDER,
                    "snapshotStatusId", SNAP_STATUS_NEW,

                    "overtimeRequestId", a.overtimeRequestId,
                    "absenceRequestId", a.absenceRequestId,
                    "leaveRequestId", a.leaveRequestId,

                    "generatedDateTime", now,
                    "generatedByUserLoginId", userLogin.getString("userLoginId"),
                    "notes", "AGG:OT+UNPAID_LEAVE+UNPAID_ABS"
            );

            Map<String, Object> crudCtx = UtilMisc.toMap(
                    "entityName", ENTITY_SNAPSHOT,
                    "organizationPartyId", organizationPartyId,
                    "fields", fields,
                    "userLogin", userLogin
            );

            Map<String, Object> res = dispatcher.runSync("payrollCreateEntity", crudCtx);
            if (ServiceUtil.isError(res)) {
                return res;
            }

            inserted++;
        }

        // ==========================================================
        // 5) Mark overtime used (optional)
        // ==========================================================
        long overtimeMarkedUsed = 0L;
        if (!dryRun && markOvertimeUsed && otRows != null) {
            for (GenericValue ot : otRows) {
                String overtimeRequestId = ot.getString("overtimeRequestId");
                if (UtilValidate.isEmpty(overtimeRequestId)) continue;

                long existsLink = EntityQuery.use(delegator)
                        .from(ENTITY_SNAPSHOT)
                        .where("organizationPartyId", organizationPartyId,
                               "overtimeRequestId", overtimeRequestId)
                        .queryCount();
                if (existsLink > 0) {
                    String st = ot.getString("statusId");
                    if (OT_STATUS_APPR.equals(st)) {
                        ot.set("statusId", OT_STATUS_USED);
                        ot.store();
                        overtimeMarkedUsed++;
                    }
                }
            }
        }

        Debug.logInfo("SNAPSHOT_BUILDER_V2: inserted=" + inserted
                + " skippedNoActivity=" + skippedNoActivity
                + " otSkippedAlreadySnap=" + otSkippedAlreadySnap
                + " overtimeMarkedUsed=" + overtimeMarkedUsed, MODULE);

        out.put("successMessage", "###SNAPSHOT_BUILD_V2### inserted=" + inserted);

        String snapshotStatusId = (String) context.get("snapshotStatusId");
        if (UtilValidate.isEmpty(snapshotStatusId)) {
            snapshotStatusId = SNAP_STATUS_NEW;
        }

        data.put("snapshotBatchId", snapshotBatchId);
        data.put("snapshotCount", inserted);
        data.put("skippedNoActivity", skippedNoActivity);
        data.put("otSkippedAlreadySnap", otSkippedAlreadySnap);
        data.put("overtimeMarkedUsed", overtimeMarkedUsed);
        data.put("dryRun", dryRun ? "Y" : "N");
        data.put("forceRebuild", forceRebuild ? "Y" : "N");
        data.put("snapshotStatusId", snapshotStatusId);

        legacyData.putAll(data);

        out.put("data", data);
        out.put("legacyData", legacyData);
        return out;

    } catch (GenericEntityException gee) {
        Debug.logError(gee, "buildPayrollInputSnapshotV2 entity error", MODULE);
        return ServiceUtil.returnError("buildPayrollInputSnapshotV2 failed (entity): " + gee.getMessage());
    } catch (Exception ex) {
        Debug.logError(ex, "buildPayrollInputSnapshotV2 failed", MODULE);
        return ServiceUtil.returnError("buildPayrollInputSnapshotV2 failed: " + ex.getMessage());
    }
}
private static BigDecimal asBigDecimal(Object v) {
    if (v == null) return BigDecimal.ZERO;
    if (v instanceof BigDecimal) return (BigDecimal) v;
    if (v instanceof Long) return BigDecimal.valueOf((Long) v);
    if (v instanceof Integer) return BigDecimal.valueOf(((Integer) v).longValue());
    if (v instanceof String) {
        String s = ((String) v).trim();
        return s.isEmpty() ? BigDecimal.ZERO : new BigDecimal(s);
    }
    if (v instanceof Number) return BigDecimal.valueOf(((Number) v).doubleValue());
    throw new IllegalArgumentException("Unsupported numeric type: " + v.getClass().getName());
}


}