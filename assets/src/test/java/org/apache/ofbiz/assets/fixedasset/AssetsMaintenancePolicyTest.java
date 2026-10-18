package org.apache.ofbiz.assets.fixedasset;
import java.sql.Timestamp;
import org.junit.Test;
import static org.junit.Assert.*;
public class AssetsMaintenancePolicyTest {
 @Test public void ordinaryCycle(){assertEquals("FAM_SCHEDULED",AssetsMaintenanceServices.targetStatus("FAM_CREATED","SCHEDULE"));assertEquals("FAM_IN_PROCESS",AssetsMaintenanceServices.targetStatus("FAM_SCHEDULED","START"));assertEquals("FAM_COMPLETED",AssetsMaintenanceServices.targetStatus("FAM_IN_PROCESS","COMPLETE"));}
 @Test public void queuedCancellation(){assertEquals("FAM_CANCELLED",AssetsMaintenanceServices.targetStatus("FAM_CREATED","CANCEL"));assertEquals("FAM_CANCELLED",AssetsMaintenanceServices.targetStatus("FAM_SCHEDULED","CANCEL"));}
 @Test public void retriesKeepState(){for(String[] v:new String[][]{{"FAM_CREATED","CREATE"},{"FAM_SCHEDULED","SCHEDULE"},{"FAM_IN_PROCESS","START"},{"FAM_COMPLETED","COMPLETE"},{"FAM_CANCELLED","CANCEL"}})if(!v[1].equals("CREATE"))assertEquals(v[0],AssetsMaintenanceServices.targetStatus(v[0],v[1]));}
 @Test(expected=IllegalArgumentException.class)public void noCompleteBeforeStart(){AssetsMaintenanceServices.targetStatus("FAM_SCHEDULED","COMPLETE");}
 @Test(expected=IllegalArgumentException.class)public void noUnscheduledStart(){AssetsMaintenanceServices.targetStatus("FAM_CREATED","START");}
 @Test(expected=IllegalArgumentException.class)public void noCancelStartedWork(){AssetsMaintenanceServices.targetStatus("FAM_IN_PROCESS","CANCEL");}
 @Test(expected=IllegalArgumentException.class)public void noReopenCompletedWork(){AssetsMaintenanceServices.targetStatus("FAM_COMPLETED","START");}
 @Test(expected=IllegalArgumentException.class)public void noStartCancelledWork(){AssetsMaintenanceServices.targetStatus("FAM_CANCELLED","START");}
 @Test(expected=IllegalArgumentException.class)public void rejectUnknownAction(){AssetsMaintenanceServices.targetStatus("FAM_CREATED","POST");}
 @Test public void validDates(){AssetsMaintenanceServices.validateDates(Timestamp.valueOf("2026-10-17 10:00:00"),Timestamp.valueOf("2026-10-17 11:00:00"));}
 @Test(expected=IllegalArgumentException.class)public void rejectEqualDates(){AssetsMaintenanceServices.validateDates(Timestamp.valueOf("2026-10-17 10:00:00"),Timestamp.valueOf("2026-10-17 10:00:00"));}
 @Test(expected=IllegalArgumentException.class)public void rejectReversedDates(){AssetsMaintenanceServices.validateDates(Timestamp.valueOf("2026-10-17 11:00:00"),Timestamp.valueOf("2026-10-17 10:00:00"));}
 @Test(expected=IllegalArgumentException.class)public void rejectMissingDate(){AssetsMaintenanceServices.validateDates(null,Timestamp.valueOf("2026-10-17 11:00:00"));}
}
