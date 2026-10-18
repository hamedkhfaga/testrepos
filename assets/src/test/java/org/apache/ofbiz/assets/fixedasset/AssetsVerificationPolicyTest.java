package org.apache.ofbiz.assets.fixedasset;
import org.junit.Test;

public class AssetsVerificationPolicyTest {
    @Test public void workingAssetCanBeCounted(){AssetsVerificationServices.validateCount("FOUND","WORKING");}
    @Test public void damagedFindingsAreEvidenceNotFinancialActions(){AssetsVerificationServices.validateCount("FOUND","DAMAGED");AssetsVerificationServices.validateCount("FOUND","NEEDS_REPAIR");}
    @Test public void uncertainConditionCanBeRecorded(){AssetsVerificationServices.validateCount("FOUND","UNKNOWN");}
    @Test public void missingRequiresExplicitUnknownCondition(){AssetsVerificationServices.validateCount("MISSING","UNKNOWN");}
    @Test(expected=IllegalArgumentException.class)public void missingCannotClaimWorkingCondition(){AssetsVerificationServices.validateCount("MISSING","WORKING");}
    @Test(expected=IllegalArgumentException.class)public void invalidFindingIsRejected(){AssetsVerificationServices.validateCount("MATCHED","WORKING");}
    @Test(expected=IllegalArgumentException.class)public void invalidConditionIsRejected(){AssetsVerificationServices.validateCount("FOUND","GOOD");}
}
