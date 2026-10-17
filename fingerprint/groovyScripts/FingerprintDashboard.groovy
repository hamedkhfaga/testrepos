import org.apache.ofbiz.entity.condition.EntityCondition
import org.apache.ofbiz.entity.condition.EntityOperator
import org.apache.ofbiz.entity.util.EntityFindOptions
import java.sql.Date
import java.util.Calendar

// --- 1. DATE NORMALIZATION ---
Calendar cal = Calendar.getInstance()
cal.set(Calendar.HOUR_OF_DAY, 0); cal.set(Calendar.MINUTE, 0); cal.set(Calendar.SECOND, 0); cal.set(Calendar.MILLISECOND, 0)
Date today = new Date(cal.getTimeInMillis())

// --- 2. SUMMARY COUNTERS ---
def totalEmpRes = delegator.findCountByCondition("PartyRole", EntityCondition.makeCondition("roleTypeId", "EMPLOYEE"), null, null)
request.setAttribute("totalEmployees", totalEmpRes ?: 0L)

def presentRes = delegator.findCountByCondition("AttendanceDaySummary", 
    EntityCondition.makeCondition([workDate: today, summaryStatusId: "FP_SUMMARY_FINAL"], EntityOperator.AND), null, null)
request.setAttribute("presentToday", presentRes ?: 0L)

def lateCond = EntityCondition.makeCondition([
    EntityCondition.makeCondition("workDate", EntityOperator.EQUALS, today),
    EntityCondition.makeCondition("lateMinutes", EntityOperator.GREATER_THAN, 0L)
], EntityOperator.AND)
def lateRes = delegator.findCountByCondition("AttendanceDaySummary", lateCond, null, null)
request.setAttribute("lateToday", lateRes ?: 0L)

def pendingRes = delegator.findCountByCondition("AttendanceException", EntityCondition.makeCondition("exceptionStatusId", "FP_EXC_OPEN"), null, null)
request.setAttribute("pendingExceptions", pendingRes ?: 0L)

// --- 3. GRAPH DATA ---
def dailyStats = []
for (int i = 6; i >= 0; i--) {
    Calendar loopCal = Calendar.getInstance()
    loopCal.add(Calendar.DAY_OF_YEAR, -i)
    loopCal.set(Calendar.HOUR_OF_DAY, 0); loopCal.set(Calendar.MINUTE, 0); loopCal.set(Calendar.SECOND, 0); loopCal.set(Calendar.MILLISECOND, 0)
    Date tDate = new Date(loopCal.getTimeInMillis())
    def pCount = delegator.findCountByCondition("AttendanceDaySummary", EntityCondition.makeCondition("workDate", tDate), null, null)
    dailyStats.add([date: tDate.toString(), presentCount: (pCount ?: 0L), lateCount: 0L])
}
request.setAttribute("dailyStats", dailyStats)

// --- 4. RECENT PUNCHES ---
EntityFindOptions findOptions = new EntityFindOptions()
findOptions.setLimit(10)
def recentPunches = delegator.findList("BiometricEventLog", null, null, ["eventDateTime DESC"], findOptions, false)
request.setAttribute("recentPunches", recentPunches)

return "success"