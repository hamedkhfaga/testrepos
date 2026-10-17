import org.apache.ofbiz.entity.util.EntityQuery
import org.apache.ofbiz.entity.condition.EntityCondition
import org.apache.ofbiz.entity.condition.EntityOperator
import java.sql.Timestamp
import java.time.Duration
import java.time.LocalTime
import java.time.format.DateTimeFormatter

// 1. Set up Date Range (Default to last 30 days if not provided)
def thruDate = context.thruDate ?: org.apache.ofbiz.base.util.UtilDateTime.nowTimestamp()
def fromDate = context.fromDate ?: org.apache.ofbiz.base.util.UtilDateTime.addDaysToTimestamp(thruDate, -30)

// 2. Fetch Summaries (The "Engine" of the report)
// We use AttendanceDaySummary because it is pre-calculated and faster for 100+ employees
def reportRows = EntityQuery.use(delegator)
    .from("AttendanceDaySummary")
    .where(EntityCondition.makeCondition([
        EntityCondition.makeCondition("workDate", EntityOperator.GREATER_THAN_EQUAL_TO, new java.sql.Date(fromDate.getTime())),
        EntityCondition.makeCondition("workDate", EntityOperator.LESS_THAN_EQUAL_TO, new java.sql.Date(thruDate.getTime()))
    ], EntityOperator.AND))
    .orderBy("workDate DESC", "partyId ASC")
    .queryList()

// 3. Process data for the list
def rows = []
def reportTotals = [totalWorked: 0, totalLate: 0, totalOT: 0, totalAbsences: 0]

reportRows.each { summary ->
    // Get Person Details for the Name column
    def person = EntityQuery.use(delegator).from("Person").where("partyId", summary.partyId).cache().queryOne()
    def fullName = person ? "${person.firstName} ${person.lastName}" : "Unknown"

    def row = [
        workDate: summary.workDate,
        partyId: summary.partyId,
        name: fullName,
        workedHours: String.format("%.2f", summary.workedMinutes / 60.0),
        lateMinutes: summary.lateMinutes,
        overtimeMinutes: summary.overtimeMinutes,
        status: (summary.workedMinutes > 0) ? "PRESENT" : "ABSENT"
    ]

    // Update Totals
    reportTotals.totalWorked += (summary.workedMinutes / 60.0)
    reportTotals.totalLate += summary.lateMinutes
    reportTotals.totalOT += summary.overtimeMinutes
    if (row.status == "ABSENT") reportTotals.totalAbsences++

    rows << row
}

context.reportRows = rows
context.reportTotals = reportTotals
context.fromDate = fromDate
context.thruDate = thruDate