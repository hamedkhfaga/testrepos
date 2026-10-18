package org.ofbiz.hr.attendance;

import org.apache.ofbiz.base.util.Debug;
import org.apache.ofbiz.base.util.UtilDateTime;
import org.apache.ofbiz.base.util.UtilValidate;
import org.apache.ofbiz.entity.Delegator;
import org.apache.ofbiz.entity.GenericEntityException;
import org.apache.ofbiz.entity.GenericValue;
import org.apache.ofbiz.entity.condition.EntityCondition;
import org.apache.ofbiz.entity.condition.EntityOperator;
import org.apache.ofbiz.entity.util.EntityQuery;
import org.apache.ofbiz.service.DispatchContext;
import org.apache.ofbiz.service.ServiceUtil;

import java.sql.Time;
import java.sql.Timestamp;
import java.util.Calendar;
import java.util.Date;
import java.util.List;
import java.util.Map;

public class AttendanceCalculationServices {
    public static final String MODULE = AttendanceCalculationServices.class.getName();

    private static String getUserLoginId(Map<String, ?> context) {
        GenericValue userLogin = (GenericValue) context.get("userLogin");
        if (userLogin == null) {
            return null;
        }
        return userLogin.getString("userLoginId");
    }

    private static void assertOrgPresent(String organizationPartyId) {
        if (UtilValidate.isEmpty(organizationPartyId)) {
            throw new IllegalArgumentException("organizationPartyId is required");
        }
    }

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

    public static Map<String, Object> createWorkShift(DispatchContext dctx, Map<String, ?> context) {
        Delegator delegator = dctx.getDelegator();
        try {
            String organizationPartyId = (String) context.get("organizationPartyId");
            assertOrgPresent(organizationPartyId);

            String shiftName = (String) context.get("shiftName");
            Time startTime = (Time) context.get("startTime");
            Time endTime = (Time) context.get("endTime");
            if (UtilValidate.isEmpty(shiftName) || startTime == null || endTime == null) {
                return ServiceUtil.returnError("shiftName, startTime, endTime are required");
            }

            String shiftId = delegator.getNextSeqId("WorkShift");
            GenericValue shift = delegator.makeValue("WorkShift");
            shift.set("shiftId", shiftId);
            shift.set("organizationPartyId", organizationPartyId);
            shift.set("shiftName", shiftName);
            shift.set("startTime", startTime);
            shift.set("endTime", endTime);

            shift.set("crossMidnightFlag", context.get("crossMidnightFlag"));
            shift.set("graceInMinutes", context.get("graceInMinutes"));
            shift.set("graceOutMinutes", context.get("graceOutMinutes"));
            shift.set("breakMinutes", context.get("breakMinutes"));
            shift.set("shiftStatusId", context.get("shiftStatusId"));
            shift.set("externalRefId", context.get("externalRefId"));

            String userLoginId = getUserLoginId(context);
            shift.set("createdByUserLoginId", userLoginId);
            shift.set("lastModifiedByUserLoginId", userLoginId);
            shift.create();

            Map<String, Object> result = ServiceUtil.returnSuccess();
            result.put("shiftId", shiftId);
            return result;
        } catch (Exception e) {
            Debug.logError(e, "Error in createWorkShift", MODULE);
            return ServiceUtil.returnError("Error creating shift: " + e.getMessage());
        }
    }

    public static Map<String, Object> createAttendancePolicy(DispatchContext dctx, Map<String, ?> context) {
        Delegator delegator = dctx.getDelegator();
        try {
            String organizationPartyId = (String) context.get("organizationPartyId");
            assertOrgPresent(organizationPartyId);

            String policyName = (String) context.get("policyName");
            if (UtilValidate.isEmpty(policyName)) {
                return ServiceUtil.returnError("policyName is required");
            }

            String id = delegator.getNextSeqId("AttendancePolicy");
            GenericValue pol = delegator.makeValue("AttendancePolicy");
            pol.set("attendancePolicyId", id);
            pol.set("organizationPartyId", organizationPartyId);
            pol.set("policyName", policyName);

            pol.set("roundingMethodEnumId", context.get("roundingMethodEnumId"));
            pol.set("roundingMinutes", context.get("roundingMinutes"));
            pol.set("overtimeMethodEnumId", context.get("overtimeMethodEnumId"));
            pol.set("minOvertimeMinutes", context.get("minOvertimeMinutes"));
            pol.set("allowManualAdjustFlag", context.get("allowManualAdjustFlag"));
            pol.set("policyStatusId", context.get("policyStatusId"));
            pol.set("notes", context.get("notes"));
            pol.set("externalRefId", context.get("externalRefId"));

            String userLoginId = getUserLoginId(context);
            pol.set("createdByUserLoginId", userLoginId);
            pol.set("lastModifiedByUserLoginId", userLoginId);
            pol.create();

            Map<String, Object> result = ServiceUtil.returnSuccess();
            result.put("attendancePolicyId", id);
            return result;
        } catch (Exception e) {
            Debug.logError(e, "Error in createAttendancePolicy", MODULE);
            return ServiceUtil.returnError("Error creating policy: " + e.getMessage());
        }
    }

    public static Map<String, Object> assignShiftToParty(DispatchContext dctx, Map<String, ?> context) {
        Delegator delegator = dctx.getDelegator();
        try {
            String organizationPartyId = (String) context.get("organizationPartyId");
            assertOrgPresent(organizationPartyId);

            String partyId = (String) context.get("partyId");
            String shiftId = (String) context.get("shiftId");
            if (UtilValidate.isEmpty(partyId) || UtilValidate.isEmpty(shiftId)) {
                return ServiceUtil.returnError("partyId and shiftId are required");
            }

            Timestamp fromDate = (Timestamp) context.get("fromDate");
            if (fromDate == null) {
                fromDate = UtilDateTime.nowTimestamp();
            }

            GenericValue assg = delegator.makeValue("PartyShiftAssignment");
            assg.set("partyId", partyId);
            assg.set("shiftId", shiftId);
            assg.set("fromDate", fromDate);
            assg.set("organizationPartyId", organizationPartyId);
            assg.set("thruDate", context.get("thruDate"));
            assg.set("notes", context.get("notes"));

            String userLoginId = getUserLoginId(context);
            assg.set("createdByUserLoginId", userLoginId);
            assg.set("lastModifiedByUserLoginId", userLoginId);

            assg.create();

            Map<String, Object> result = ServiceUtil.returnSuccess();
            result.put("effectiveFromDate", fromDate);
            result.put("assigned", Boolean.TRUE);
            return result;
        } catch (Exception e) {
            Debug.logError(e, "Error in assignShiftToParty", MODULE);
            return ServiceUtil.returnError("Error assigning shift: " + e.getMessage());
        }
    }

    public static Map<String, Object> assignAttendancePolicyToParty(DispatchContext dctx, Map<String, ?> context) {
        Delegator delegator = dctx.getDelegator();
        try {
            String organizationPartyId = (String) context.get("organizationPartyId");
            assertOrgPresent(organizationPartyId);

            String partyId = (String) context.get("partyId");
            String policyId = (String) context.get("attendancePolicyId");
            if (UtilValidate.isEmpty(partyId) || UtilValidate.isEmpty(policyId)) {
                return ServiceUtil.returnError("partyId and attendancePolicyId are required");
            }

            Timestamp fromDate = (Timestamp) context.get("fromDate");
            if (fromDate == null) {
                fromDate = UtilDateTime.nowTimestamp();
            }

            GenericValue assg = delegator.makeValue("PartyAttendancePolicy");
            assg.set("partyId", partyId);
            assg.set("attendancePolicyId", policyId);
            assg.set("fromDate", fromDate);
            assg.set("organizationPartyId", organizationPartyId);
            assg.set("thruDate", context.get("thruDate"));

            String userLoginId = getUserLoginId(context);
            assg.set("createdByUserLoginId", userLoginId);
            assg.set("lastModifiedByUserLoginId", userLoginId);

            assg.create();

            Map<String, Object> result = ServiceUtil.returnSuccess();
            result.put("effectiveFromDate", fromDate);
            result.put("assigned", Boolean.TRUE);
            return result;
        } catch (Exception e) {
            Debug.logError(e, "Error in assignAttendancePolicyToParty", MODULE);
            return ServiceUtil.returnError("Error assigning policy: " + e.getMessage());
        }
    }

    public static Map<String, Object> calculateAttendanceDaySummary(DispatchContext dctx, Map<String, ?> context) {
        Delegator delegator = dctx.getDelegator();
        try {
            String organizationPartyId = (String) context.get("organizationPartyId");
            assertOrgPresent(organizationPartyId);

            String partyId = (String) context.get("partyId");
            Date workDate = (Date) context.get("workDate");
            Boolean recalc = (Boolean) context.get("recalculate");
            Boolean dryRun = (Boolean) context.get("dryRun");
            boolean isRecalc = recalc != null && recalc;
            boolean isDryRun = dryRun != null && dryRun;

            if (UtilValidate.isEmpty(partyId) || workDate == null) {
                return ServiceUtil.returnError("partyId and workDate are required");
            }

            java.sql.Date sqlWorkDate = new java.sql.Date(workDate.getTime());

            GenericValue existing = EntityQuery.use(delegator)
                    .from("AttendanceDaySummary")
                    .where("organizationPartyId", organizationPartyId,
                            "partyId", partyId,
                            "workDate", sqlWorkDate)
                    .cache(false)
                    .queryFirst();

            if (existing != null && !isRecalc) {
                Map<String, Object> result = ServiceUtil.returnSuccess();
                result.put("attendanceDaySummaryId", existing.getString("attendanceDaySummaryId"));
                result.put("workedMinutes", toLong(existing.get("workedMinutes")));
                result.put("overtimeMinutes", toLong(existing.get("overtimeMinutes")));
                result.put("lateMinutes", toLong(existing.get("lateMinutes")));
                result.put("earlyLeaveMinutes", toLong(existing.get("earlyLeaveMinutes")));
                result.put("summaryStatusId", existing.getString("summaryStatusId"));
                return result;
            }

            GenericValue shift = getEffectiveShift(delegator, organizationPartyId, partyId, workDate);
            GenericValue policy = getEffectivePolicy(delegator, organizationPartyId, partyId, workDate);

            long scheduledMinutes = 0L;
            if (shift != null) {
                scheduledMinutes = computeScheduledMinutes(shift);
            }

            List<GenericValue> pairs = EntityQuery.use(delegator)
                    .from("AttendancePunchPair")
                    .where("organizationPartyId", organizationPartyId,
                            "partyId", partyId,
                            "workDate", sqlWorkDate)
                    .orderBy("inDateTime")
                    .cache(false)
                    .queryList();

            long workedMinutes = 0L;
            Timestamp firstIn = null;
            Timestamp lastOut = null;

            for (GenericValue pair : pairs) {
                workedMinutes += toLong(pair.get("workedMinutes"));
                Timestamp inTs = pair.getTimestamp("inDateTime");
                Timestamp outTs = pair.getTimestamp("outDateTime");

                if (inTs != null && (firstIn == null || inTs.before(firstIn))) {
                    firstIn = inTs;
                }
                if (outTs != null && (lastOut == null || outTs.after(lastOut))) {
                    lastOut = outTs;
                }
            }

            long lateMinutes = 0L;
            long earlyLeaveMinutes = 0L;

            if (shift != null) {
                Time startTime = (Time) shift.get("startTime");
                Time endTime = (Time) shift.get("endTime");

                if (startTime != null && endTime != null) {
                    long graceIn = toLong(shift.get("graceInMinutes"));
                    long graceOut = toLong(shift.get("graceOutMinutes"));

                    Timestamp shiftStart = buildDateTime(workDate, startTime);
                    Timestamp shiftEnd = buildDateTime(workDate, endTime);

                    boolean crossMidnight = "Y".equalsIgnoreCase(shift.getString("crossMidnightFlag"));
                    if (crossMidnight && shiftEnd != null && shiftStart != null && !shiftEnd.after(shiftStart)) {
                        shiftEnd = new Timestamp(shiftEnd.getTime() + 24L * 60L * 60L * 1000L);
                    }

                    if (firstIn != null && shiftStart != null) {
                        long diff = minutesBetween(shiftStart, firstIn) - graceIn;
                        if (diff > 0) {
                            lateMinutes = diff;
                        }
                    }

                    if (lastOut != null && shiftEnd != null) {
                        long diff = minutesBetween(lastOut, shiftEnd) - graceOut;
                        if (diff > 0) {
                            earlyLeaveMinutes = diff;
                        }
                    }
                }
            }

            long overtimeMinutes = 0L;
            long minOvertime = 0L;
            if (policy != null) {
                minOvertime = toLong(policy.get("minOvertimeMinutes"));
            }

            long extra = workedMinutes - scheduledMinutes;
            if (extra > minOvertime) {
                overtimeMinutes = extra;
            }

            boolean absence = workedMinutes <= 0L;
            String summaryStatusId = "AT_SUM_CALCULATED";

            String summaryId;
            if (existing != null) {
                summaryId = existing.getString("attendanceDaySummaryId");
            } else {
                summaryId = delegator.getNextSeqId("AttendanceDaySummary");
            }

            if (!isDryRun) {
                GenericValue sum;
                if (existing != null) {
                    sum = existing;
                } else {
                    sum = delegator.makeValue("AttendanceDaySummary");
                }

                sum.set("attendanceDaySummaryId", summaryId);
                sum.set("organizationPartyId", organizationPartyId);
                sum.set("partyId", partyId);
                sum.set("workDate", sqlWorkDate);

                if (shift != null) {
                    sum.set("shiftId", shift.getString("shiftId"));
                }
                if (policy != null) {
                    sum.set("attendancePolicyId", policy.getString("attendancePolicyId"));
                }

                sum.set("scheduledMinutes", scheduledMinutes);
                sum.set("workedMinutes", workedMinutes);
                sum.set("overtimeMinutes", overtimeMinutes);
                sum.set("lateMinutes", lateMinutes);
                sum.set("earlyLeaveMinutes", earlyLeaveMinutes);
                sum.set("absenceFlag", absence ? "Y" : "N");

                sum.set("summaryStatusId", summaryStatusId);
                sum.set("calculatedDateTime", UtilDateTime.nowTimestamp());
                sum.set("calculatedByUserLoginId", getUserLoginId(context));

                String userLoginId = getUserLoginId(context);
                if (existing == null) {
                    sum.set("createdByUserLoginId", userLoginId);
                }
                sum.set("lastModifiedByUserLoginId", userLoginId);

                if (existing == null) {
                    sum.create();
                } else {
                    sum.store();
                }
            }

            Map<String, Object> result = ServiceUtil.returnSuccess();
            result.put("attendanceDaySummaryId", summaryId);
            result.put("workedMinutes", workedMinutes);
            result.put("overtimeMinutes", overtimeMinutes);
            result.put("lateMinutes", lateMinutes);
            result.put("earlyLeaveMinutes", earlyLeaveMinutes);
            result.put("summaryStatusId", summaryStatusId);
            return result;

        } catch (Exception e) {
            Debug.logError(e, "Error in calculateAttendanceDaySummary", MODULE);
            return ServiceUtil.returnError("Error calculating day summary: " + e.getMessage());
        }
    }

    private static long minutesBetween(Timestamp start, Timestamp end) {
        long diffMs = end.getTime() - start.getTime();
        return diffMs / 60_000L;
    }

    private static Timestamp buildDateTime(Date workDate, Time time) {
        if (workDate == null || time == null) {
            return null;
        }

        Calendar cal = Calendar.getInstance();
        cal.setTime(workDate);

        @SuppressWarnings("deprecation")
        int hh = time.getHours();
        @SuppressWarnings("deprecation")
        int mm = time.getMinutes();
        @SuppressWarnings("deprecation")
        int ss = time.getSeconds();

        cal.set(Calendar.HOUR_OF_DAY, hh);
        cal.set(Calendar.MINUTE, mm);
        cal.set(Calendar.SECOND, ss);
        cal.set(Calendar.MILLISECOND, 0);

        return new Timestamp(cal.getTimeInMillis());
    }

    private static long computeScheduledMinutes(GenericValue shift) {
        try {
            Time start = (Time) shift.get("startTime");
            Time end = (Time) shift.get("endTime");
            if (start == null || end == null) {
                return 0L;
            }

            boolean cross = "Y".equalsIgnoreCase(shift.getString("crossMidnightFlag"));
            long breakMin = toLong(shift.get("breakMinutes"));

            Date ref = UtilDateTime.nowDate();
            Timestamp startTs = buildDateTime(ref, start);
            Timestamp endTs = buildDateTime(ref, end);

            if (cross && endTs != null && startTs != null && !endTs.after(startTs)) {
                endTs = new Timestamp(endTs.getTime() + 24L * 60L * 60L * 1000L);
            }

            long minutes = minutesBetween(startTs, endTs) - breakMin;
            return Math.max(0L, minutes);
        } catch (Exception ignored) {
            return 0L;
        }
    }

    private static GenericValue getEffectiveShift(Delegator delegator, String orgId, String partyId, Date workDate)
            throws GenericEntityException {
        Timestamp dayStart = UtilDateTime.getDayStart(UtilDateTime.toTimestamp(workDate));

        EntityCondition cond = EntityCondition.makeCondition(
                EntityOperator.AND,
                EntityCondition.makeCondition("organizationPartyId", orgId),
                EntityCondition.makeCondition("partyId", partyId),
                EntityCondition.makeCondition("fromDate", EntityOperator.LESS_THAN_EQUAL_TO, dayStart),
                EntityCondition.makeCondition(
                        EntityOperator.OR,
                        EntityCondition.makeCondition("thruDate", EntityOperator.EQUALS, null),
                        EntityCondition.makeCondition("thruDate", EntityOperator.GREATER_THAN_EQUAL_TO, dayStart)));

        GenericValue assg = EntityQuery.use(delegator)
                .from("PartyShiftAssignment")
                .where(cond)
                .orderBy("-fromDate")
                .cache(false)
                .queryFirst();

        if (assg == null) {
            return null;
        }

        String shiftId = assg.getString("shiftId");
        if (UtilValidate.isEmpty(shiftId)) {
            return null;
        }

        return EntityQuery.use(delegator)
                .from("WorkShift")
                .where("shiftId", shiftId, "organizationPartyId", orgId)
                .cache(false)
                .queryOne();
    }

    private static GenericValue getEffectivePolicy(Delegator delegator, String orgId, String partyId, Date workDate)
            throws GenericEntityException {
        Timestamp dayStart = UtilDateTime.getDayStart(UtilDateTime.toTimestamp(workDate));

        EntityCondition cond = EntityCondition.makeCondition(
                EntityOperator.AND,
                EntityCondition.makeCondition("organizationPartyId", orgId),
                EntityCondition.makeCondition("partyId", partyId),
                EntityCondition.makeCondition("fromDate", EntityOperator.LESS_THAN_EQUAL_TO, dayStart),
                EntityCondition.makeCondition(
                        EntityOperator.OR,
                        EntityCondition.makeCondition("thruDate", EntityOperator.EQUALS, null),
                        EntityCondition.makeCondition("thruDate", EntityOperator.GREATER_THAN_EQUAL_TO, dayStart)));

        GenericValue assg = EntityQuery.use(delegator)
                .from("PartyAttendancePolicy")
                .where(cond)
                .orderBy("-fromDate")
                .cache(false)
                .queryFirst();

        if (assg == null) {
            return null;
        }

        String policyId = assg.getString("attendancePolicyId");
        if (UtilValidate.isEmpty(policyId)) {
            return null;
        }

        return EntityQuery.use(delegator)
                .from("AttendancePolicy")
                .where("attendancePolicyId", policyId, "organizationPartyId", orgId)
                .cache(false)
                .queryOne();
    }

public static Map<String, Object> resolveAttendanceException(DispatchContext dctx, Map<String, ?> context) {
        Delegator delegator = dctx.getDelegator();
        try {
            String attendanceExceptionId = (String) context.get("attendanceExceptionId");
            String organizationPartyId = (String) context.get("organizationPartyId");
            String exceptionStatusId = (String) context.get("exceptionStatusId");
            String resolutionNote = (String) context.get("resolutionNote");
            
            assertOrgPresent(organizationPartyId);

            // 1. Find the record
            GenericValue exceptionEntry = EntityQuery.use(delegator)
                    .from("AttendanceException")
                    .where("attendanceExceptionId", attendanceExceptionId)
                    .queryOne();

            if (exceptionEntry == null) {
                return ServiceUtil.returnError("Attendance Exception record not found with ID: " + attendanceExceptionId);
            }

            // 2. Set the resolution fields
            exceptionEntry.set("exceptionStatusId", exceptionStatusId);
            exceptionEntry.set("resolutionNote", resolutionNote);
            exceptionEntry.set("organizationPartyId", organizationPartyId);
            
            // Set Audit/Resolution info
            String userLoginId = getUserLoginId(context);
            Timestamp now = UtilDateTime.nowTimestamp();
            
            exceptionEntry.set("resolvedByUserLoginId", userLoginId);
            exceptionEntry.set("resolvedDateTime", now);
            exceptionEntry.set("lastModifiedByUserLoginId", userLoginId);

            // 3. Store the update
            exceptionEntry.store();

            // 4. Return success with the required 'resolved' OUT parameter
            Map<String, Object> result = ServiceUtil.returnSuccess("Exception resolved successfully.");
            result.put("resolved", Boolean.TRUE);
            return result;

        } catch (Exception e) {
            Debug.logError(e, "Error in resolveAttendanceException", MODULE);
            return ServiceUtil.returnError("Error resolving exception: " + e.getMessage());
        }
    }



}
