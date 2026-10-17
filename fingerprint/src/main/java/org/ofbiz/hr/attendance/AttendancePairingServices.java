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

import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Map;

public class AttendancePairingServices {
    public static final String MODULE = AttendancePairingServices.class.getName();

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

    public static Map<String, Object> pairAttendancePunches(DispatchContext dctx, Map<String, ?> context) {
        Delegator delegator = dctx.getDelegator();
        try {
            String organizationPartyId = (String) context.get("organizationPartyId");
            assertOrgPresent(organizationPartyId);

            String partyId = (String) context.get("partyId");
            Date workDate = (Date) context.get("workDate");

            Timestamp fromDateTime = (Timestamp) context.get("fromDateTime");
            Timestamp thruDateTime = (Timestamp) context.get("thruDateTime");

            String deviceId = (String) context.get("deviceId");
            Boolean dryRun = (Boolean) context.get("dryRun");
            boolean isDryRun = dryRun != null && dryRun;

            if (workDate != null) {
                fromDateTime = UtilDateTime.toTimestamp(workDate);
                thruDateTime = UtilDateTime.getDayEnd(fromDateTime);
            }

            if (fromDateTime == null) {
                return ServiceUtil.returnError("Either workDate or fromDateTime is required");
            }

            if (thruDateTime == null) {
                thruDateTime = UtilDateTime.nowTimestamp();
            }

            EntityCondition cond = EntityCondition.makeCondition(
                    EntityOperator.AND,
                    EntityCondition.makeCondition("organizationPartyId", organizationPartyId),
                    EntityCondition.makeCondition(
                            "eventDateTime",
                            EntityOperator.GREATER_THAN_EQUAL_TO,
                            fromDateTime),
                    EntityCondition.makeCondition(
                            "eventDateTime",
                            EntityOperator.LESS_THAN_EQUAL_TO,
                            thruDateTime));

            List<EntityCondition> extra = new ArrayList<>();
            if (UtilValidate.isNotEmpty(partyId)) {
                extra.add(EntityCondition.makeCondition("partyId", partyId));
            }
            if (UtilValidate.isNotEmpty(deviceId)) {
                extra.add(EntityCondition.makeCondition("biometricDeviceId", deviceId));
            }

            if (!extra.isEmpty()) {
                EntityCondition extraCond = EntityCondition.makeCondition(extra, EntityOperator.AND);
                cond = EntityCondition.makeCondition(EntityOperator.AND, cond, extraCond);
            }

            List<GenericValue> logs = EntityQuery.use(delegator)
                    .from("BiometricEventLog")
                    .where(cond)
                    .orderBy("partyId", "eventDateTime")
                    .cache(false)
                    .queryList();

            long pairedCount = 0L;
            long exceptionCount = 0L;

            String currentParty = null;
            GenericValue pendingIn = null;

            for (GenericValue log : logs) {
                String logPartyId = log.getString("partyId");
                if (UtilValidate.isEmpty(logPartyId)) {
                    continue;
                }

                if (!logPartyId.equals(currentParty)) {
                    if (pendingIn != null) {
                        exceptionCount += createExceptionIfNeeded(
                                delegator,
                                isDryRun,
                                organizationPartyId,
                                pendingIn,
                                null,
                                "AT_EXC_MISSING_OUT",
                                "AT_SEV_MED",
                                getUserLoginId(context));
                    }
                    currentParty = logPartyId;
                    pendingIn = null;
                }

                String punchType = log.getString("punchTypeEnumId");
                Timestamp ts = log.getTimestamp("eventDateTime");

                if ("IN".equalsIgnoreCase(punchType) || "PUNCH_IN".equalsIgnoreCase(punchType)) {
                    if (pendingIn != null) {
                        exceptionCount += createExceptionIfNeeded(
                                delegator,
                                isDryRun,
                                organizationPartyId,
                                pendingIn,
                                null,
                                "AT_EXC_DUP_IN",
                                "AT_SEV_LOW",
                                getUserLoginId(context));
                    }
                    pendingIn = log;

                } else if ("OUT".equalsIgnoreCase(punchType) || "PUNCH_OUT".equalsIgnoreCase(punchType)) {
                    if (pendingIn == null) {
                        exceptionCount += createExceptionIfNeeded(
                                delegator,
                                isDryRun,
                                organizationPartyId,
                                null,
                                log,
                                "AT_EXC_MISSING_IN",
                                "AT_SEV_MED",
                                getUserLoginId(context));
                        continue;
                    }

                    Timestamp inTs = pendingIn.getTimestamp("eventDateTime");
                    Timestamp outTs = ts;

                    if (outTs != null && inTs != null && outTs.before(inTs)) {
                        exceptionCount += createExceptionIfNeeded(
                                delegator,
                                isDryRun,
                                organizationPartyId,
                                pendingIn,
                                log,
                                "AT_EXC_OUT_BEFORE_IN",
                                "AT_SEV_HIGH",
                                getUserLoginId(context));
                        pendingIn = null;
                        continue;
                    }

                    long workedMinutes = minutesBetween(inTs, outTs);

                    if (!isDryRun) {
                        String pairId = delegator.getNextSeqId("AttendancePunchPair");
                        GenericValue pair = delegator.makeValue("AttendancePunchPair");
                        pair.set("attendancePunchPairId", pairId);
                        pair.set("organizationPartyId", organizationPartyId);
                        pair.set("partyId", logPartyId);

                        java.sql.Date sqlWorkDate = UtilDateTime.toSqlDate(UtilDateTime.toDateString(inTs));
                        pair.set("workDate", sqlWorkDate);

                        pair.set("inEventLogId", pendingIn.getString("biometricEventLogId"));
                        pair.set("outEventLogId", log.getString("biometricEventLogId"));
                        pair.set("inDateTime", inTs);
                        pair.set("outDateTime", outTs);
                        pair.set("workedMinutes", workedMinutes);
                        pair.set("pairStatusId", "AT_PAIR_PAIRED");

                        String userLoginId = getUserLoginId(context);
                        pair.set("createdByUserLoginId", userLoginId);
                        pair.set("lastModifiedByUserLoginId", userLoginId);

                        pair.create();
                    }

                    pairedCount++;
                    pendingIn = null;

                } else {
                    exceptionCount += createExceptionIfNeeded(
                            delegator,
                            isDryRun,
                            organizationPartyId,
                            null,
                            log,
                            "AT_EXC_UNKNOWN_PUNCH",
                            "AT_SEV_LOW",
                            getUserLoginId(context));
                }
            }

            if (pendingIn != null) {
                exceptionCount += createExceptionIfNeeded(
                        delegator,
                        isDryRun,
                        organizationPartyId,
                        pendingIn,
                        null,
                        "AT_EXC_MISSING_OUT",
                        "AT_SEV_MED",
                        getUserLoginId(context));
            }

            Map<String, Object> result = ServiceUtil.returnSuccess();
            result.put("pairedCount", pairedCount);
            result.put("exceptionCount", exceptionCount);
            return result;

        } catch (Exception e) {
            Debug.logError(e, "Error in pairAttendancePunches", MODULE);
            return ServiceUtil.returnError("Error pairing punches: " + e.getMessage());
        }
    }

    private static long minutesBetween(Timestamp start, Timestamp end) {
        if (start == null || end == null) {
            return 0L;
        }
        long diffMs = end.getTime() - start.getTime();
        return diffMs / 60_000L;
    }

    private static long createExceptionIfNeeded(
            Delegator delegator,
            boolean dryRun,
            String organizationPartyId,
            GenericValue inLog,
            GenericValue outLog,
            String exceptionTypeEnumId,
            String severityEnumId,
            String userLoginId) throws GenericEntityException {

        if (dryRun) {
            return 1L;
        }

        String partyId;
        if (inLog != null) {
            partyId = inLog.getString("partyId");
        } else if (outLog != null) {
            partyId = outLog.getString("partyId");
        } else {
            partyId = null;
        }

        Timestamp eventTs;
        if (inLog != null) {
            eventTs = inLog.getTimestamp("eventDateTime");
        } else if (outLog != null) {
            eventTs = outLog.getTimestamp("eventDateTime");
        } else {
            eventTs = UtilDateTime.nowTimestamp();
        }

        java.sql.Date workDate = UtilDateTime.toSqlDate(UtilDateTime.toDateString(eventTs));

        String relatedEventLogId;
        if (outLog != null) {
            relatedEventLogId = outLog.getString("biometricEventLogId");
        } else if (inLog != null) {
            relatedEventLogId = inLog.getString("biometricEventLogId");
        } else {
            relatedEventLogId = null;
        }

        String excId = delegator.getNextSeqId("AttendanceException");
        GenericValue exc = delegator.makeValue("AttendanceException");
        exc.set("attendanceExceptionId", excId);
        exc.set("organizationPartyId", organizationPartyId);
        exc.set("partyId", partyId);
        exc.set("workDate", workDate);
        exc.set("exceptionTypeEnumId", exceptionTypeEnumId);
        exc.set("severityEnumId", severityEnumId);
        exc.set("relatedEventLogId", relatedEventLogId);
        exc.set("relatedPunchPairId", null);
        exc.set("exceptionStatusId", "AT_EXC_OPEN");
        exc.set("createdByUserLoginId", userLoginId);
        exc.set("lastModifiedByUserLoginId", userLoginId);
        exc.create();

        return 1L;
    }
}
