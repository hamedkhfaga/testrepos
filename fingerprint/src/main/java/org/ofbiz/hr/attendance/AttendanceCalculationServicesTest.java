package org.ofbiz.hr.attendance;

import org.junit.Assert;
import org.junit.Test;

import java.lang.reflect.Method;
import java.sql.Time;
import java.sql.Timestamp;
import java.util.Calendar;
import java.util.Date;

public class AttendanceCalculationServicesTest {

    @Test
    public void testBuildDateTimeUsesWorkDateAndTime() throws Exception {
        Method buildDateTime = AttendanceCalculationServices.class.getDeclaredMethod("buildDateTime", Date.class, Time.class);
        buildDateTime.setAccessible(true);

        Calendar c = Calendar.getInstance();
        c.set(Calendar.YEAR, 2026);
        c.set(Calendar.MONTH, Calendar.JANUARY);
        c.set(Calendar.DAY_OF_MONTH, 1);
        c.set(Calendar.HOUR_OF_DAY, 0);
        c.set(Calendar.MINUTE, 0);
        c.set(Calendar.SECOND, 0);
        c.set(Calendar.MILLISECOND, 0);

        Date workDate = c.getTime();
        Time time = Time.valueOf("08:30:15");

        Timestamp ts = (Timestamp) buildDateTime.invoke(null, workDate, time);
        Assert.assertNotNull(ts);

        Calendar c2 = Calendar.getInstance();
        c2.setTimeInMillis(ts.getTime());

        Assert.assertEquals(2026, c2.get(Calendar.YEAR));
        Assert.assertEquals(Calendar.JANUARY, c2.get(Calendar.MONTH));
        Assert.assertEquals(1, c2.get(Calendar.DAY_OF_MONTH));

        Assert.assertEquals(8, c2.get(Calendar.HOUR_OF_DAY));
        Assert.assertEquals(30, c2.get(Calendar.MINUTE));
        Assert.assertEquals(15, c2.get(Calendar.SECOND));
    }

    @Test
    public void testCrossMidnightShiftDurationMinutes() throws Exception {
        Method buildDateTime = AttendanceCalculationServices.class.getDeclaredMethod("buildDateTime", Date.class, Time.class);
        buildDateTime.setAccessible(true);

        // Work date: 2026-01-01
        Calendar c = Calendar.getInstance();
        c.set(2026, Calendar.JANUARY, 1, 0, 0, 0);
        c.set(Calendar.MILLISECOND, 0);
        Date workDate = c.getTime();

        Timestamp start = (Timestamp) buildDateTime.invoke(null, workDate, Time.valueOf("22:00:00"));
        Timestamp endSameDay = (Timestamp) buildDateTime.invoke(null, workDate, Time.valueOf("06:00:00"));

        // Cross-midnight adjustment like in AttendanceCalculationServices:
        // if end is not after start, add 24h
        Timestamp end = endSameDay;
        if (!end.after(start)) {
            end = new Timestamp(end.getTime() + 24L * 60L * 60L * 1000L);
        }

        long minutes = (end.getTime() - start.getTime()) / 60_000L;
        Assert.assertEquals(8L * 60L, minutes);
    }

    @Test
    public void testMinutesBetweenBasic() {
        Timestamp a = Timestamp.valueOf("2026-01-01 10:00:00");
        Timestamp b = Timestamp.valueOf("2026-01-01 11:45:00");
        long mins = (b.getTime() - a.getTime()) / 60_000L;
        Assert.assertEquals(105L, mins);
    }
}
