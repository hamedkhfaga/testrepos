package org.apache.ofbiz.payroll.services;

import org.apache.ofbiz.entity.*;
import org.apache.ofbiz.entity.util.EntityQuery;
import org.apache.ofbiz.service.*;
import org.apache.ofbiz.base.util.*;

import java.util.*;

public class AttendanceAggregationServices {

    public static Map<String, Object> buildAttendancePeriodSummary(
            DispatchContext dctx, Map<String, ?> context)
            throws GenericEntityException {

        Delegator delegator = dctx.getDelegator();

        String partyId = (String) context.get("partyId");
        int month = (Integer) context.get("month");
        int year = (Integer) context.get("year");

        List<GenericValue> days = EntityQuery.use(delegator)
                .from("AttendanceDaySummary")
                .where(
                    "partyId", partyId,
                    "organizationPartyId", context.get("organizationPartyId")
                )
                .queryList();

        int payableDays = 0;
        int absentDays = 0;
        long overtime = 0;
        long late = 0;
        long early = 0;

        for (GenericValue d : days) {
            String status = d.getString("attendanceStatusId");

            if ("ATT_PRESENT".equals(status)) payableDays++;
            if ("ATT_ABSENT".equals(status)) absentDays++;

            overtime += d.getLong("overtimeMinutes");
            late += d.getLong("lateMinutes");
            early += d.getLong("earlyLeaveMinutes");
        }

        GenericValue ps = delegator.makeValue("AttendancePeriodSummary");
        ps.set("attendancePeriodSummaryId",
                delegator.getNextSeqId("AttendancePeriodSummary"));
        ps.set("partyId", partyId);
        ps.set("periodMonth", month);
        ps.set("periodYear", year);
        ps.set("payableDays", payableDays);
        ps.set("absentDays", absentDays);
        ps.set("overtimeMinutes", overtime);
        ps.set("lateMinutes", late);
        ps.set("earlyLeaveMinutes", early);
        ps.set("organizationPartyId", context.get("organizationPartyId"));

        delegator.createOrStore(ps);

        return ServiceUtil.returnSuccess();
    }
}
