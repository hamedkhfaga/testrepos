import org.apache.ofbiz.entity.util.EntityQuery
import org.apache.ofbiz.base.util.UtilDateTime
import org.apache.ofbiz.entity.condition.EntityCondition
import org.apache.ofbiz.entity.condition.EntityOperator

// Calculate date range for today
todayStart = UtilDateTime.getDayStart(nowTimestamp)

// 1. Count Total Biometric Devices
context.totalDevices = EntityQuery.use(delegator).from("BiometricDevice").where("statusId", "BDEV_ENABLED").queryCount()

// 2. Count Punches Today (from BiometricEventLog)
context.punchesToday = EntityQuery.use(delegator).from("BiometricEventLog")
    .where(EntityCondition.makeCondition("eventDateTime", EntityOperator.GREATER_THAN_EQUAL_TO, todayStart))
    .queryCount()

// 3. Import Failures (from BiometricImportBatch)
context.failedImports = EntityQuery.use(delegator).from("BiometricImportBatch")
    .where("importStatusId", "IMPT_FAILED")
    .queryCount()

// 4. Pending Exceptions (from AttendanceException)
context.pendingExceptions = EntityQuery.use(delegator).from("AttendanceException")
    .where("exceptionStatusId", "ATT_EXC_PENDING")
    .queryCount()