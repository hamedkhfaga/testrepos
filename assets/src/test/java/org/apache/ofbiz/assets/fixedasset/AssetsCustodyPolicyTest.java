package org.apache.ofbiz.assets.fixedasset;
import org.junit.Test;
import static org.junit.Assert.*;

public class AssetsCustodyPolicyTest {
 @Test public void orderedHandoverAndReceiptAreRequired() {
  assertEquals("APPROVED", AssetsCustodyServices.nextState("SUBMITTED", "APPROVE"));
  assertEquals("HANDED_OVER", AssetsCustodyServices.nextState("APPROVED", "HANDOVER"));
  assertEquals("COMPLETED", AssetsCustodyServices.nextState("HANDED_OVER", "ACKNOWLEDGE"));
  assertThrows(IllegalArgumentException.class, () -> AssetsCustodyServices.nextState("SUBMITTED", "HANDOVER"));
  assertThrows(IllegalArgumentException.class, () -> AssetsCustodyServices.nextState("APPROVED", "ACKNOWLEDGE"));
 }
 @Test public void completedMovementsCannotBeRepeatedOrReopened() {
  for (String action : new String[]{"APPROVE","REJECT","CANCEL","HANDOVER","ACKNOWLEDGE"}) {
   assertThrows(IllegalArgumentException.class, () -> AssetsCustodyServices.nextState("COMPLETED", action));
  }
 }
 @Test public void physicalHandoverCannotBeSilentlyCancelled() {
  assertThrows(IllegalArgumentException.class, () -> AssetsCustodyServices.nextState("HANDED_OVER", "CANCEL"));
  assertThrows(IllegalArgumentException.class, () -> AssetsCustodyServices.nextState("HANDED_OVER", "REJECT"));
 }
 @Test public void rejectionAndCancellationAreTerminal() {
  assertEquals("REJECTED", AssetsCustodyServices.nextState("SUBMITTED", "REJECT"));
  assertEquals("CANCELLED", AssetsCustodyServices.nextState("APPROVED", "CANCEL"));
  assertThrows(IllegalArgumentException.class, () -> AssetsCustodyServices.nextState("REJECTED", "APPROVE"));
  assertThrows(IllegalArgumentException.class, () -> AssetsCustodyServices.nextState("CANCELLED", "APPROVE"));
 }
 @Test public void issuedAssetsCannotBeAssignedAgain() {
  assertThrows(IllegalArgumentException.class, () -> AssetsCustodyServices.validateRequest("ASSIGN",true,"A","EMPLOYEE","B","EMPLOYEE","ASSIGNED"));
 }
 @Test public void transferAndReturnRequireAnExistingAssignment() {
  assertThrows(IllegalArgumentException.class, () -> AssetsCustodyServices.validateRequest("TRANSFER",false,null,null,"B","EMPLOYEE","AVAILABLE"));
  assertThrows(IllegalArgumentException.class, () -> AssetsCustodyServices.validateRequest("RETURN",false,null,null,null,null,"AVAILABLE"));
 }
 @Test public void transferMustActuallyChangeTheCustodian() {
  assertThrows(IllegalArgumentException.class, () -> AssetsCustodyServices.validateRequest("TRANSFER",true,"A","EMPLOYEE","A","EMPLOYEE","ASSIGNED"));
  AssetsCustodyServices.validateRequest("TRANSFER",true,"A","EMPLOYEE","B","EMPLOYEE","ASSIGNED");
 }
 @Test public void returnedAssetsDoNotAcquireANewCustodian() {
  assertThrows(IllegalArgumentException.class, () -> AssetsCustodyServices.validateRequest("RETURN",true,"A","EMPLOYEE","B","EMPLOYEE","ASSIGNED"));
  AssetsCustodyServices.validateRequest("RETURN",true,"A","EMPLOYEE",null,null,"ASSIGNED");
 }
 @Test public void quarantineBlocksReissueAndMovementUntilInspectionRelease() {
  for(String type:new String[]{"ASSIGN","TRANSFER","MOVE"}) assertThrows(IllegalArgumentException.class,
   () -> AssetsCustodyServices.validateRequest(type,false,null,null,"B","EMPLOYEE","QUARANTINE"));
 }
 @Test public void requesterCannotApproveWithoutExplicitAdministrativeAttestation() {
  assertThrows(IllegalArgumentException.class, () -> AssetsCustodyServices.requireApprovalAuthority(true,"requester","requester",false));
  assertThrows(IllegalArgumentException.class, () -> AssetsCustodyServices.requireApprovalAuthority(false,"requester","other",true));
  AssetsCustodyServices.requireApprovalAuthority(true,"requester","approver",false);
  AssetsCustodyServices.requireApprovalAuthority(true,"requester","requester",true);
 }
 @Test public void receiptMustBeRecordedByRecipientOrAttestingAdministrator() {
  assertThrows(IllegalArgumentException.class, () -> AssetsCustodyServices.requireReceiptActor("employee","other",false));
  assertThrows(IllegalArgumentException.class, () -> AssetsCustodyServices.requireReceiptActor(null,"admin",false));
  AssetsCustodyServices.requireReceiptActor("employee","employee",false);
  AssetsCustodyServices.requireReceiptActor(null,"admin",true);
 }
}
