package org.apache.ofbiz.assets.fixedasset;
import java.math.BigDecimal;
import java.util.HashMap;
import java.util.Map;
import org.junit.Test;
import static org.junit.Assert.*;

public class AssetsMaintenanceCostPolicyTest {
    private BigDecimal n(String s){return new BigDecimal(s);}
    @Test public void issueValuesRoundAtCurrencyPrecision(){assertEquals(n("40.00"),AssetsMaintenanceCostServices.partAmount(n("2"),n("20")));assertEquals(n("0.01"),AssetsMaintenanceCostServices.partAmount(n("0.005"),n("1")));}
    @Test(expected=IllegalArgumentException.class)public void rejectNegativeIssue(){AssetsMaintenanceCostServices.partAmount(n("-1"),n("20"));}
    @Test(expected=IllegalArgumentException.class)public void rejectZeroValue(){AssetsMaintenanceCostServices.partAmount(n("1"),BigDecimal.ZERO);}
    @Test(expected=IllegalArgumentException.class)public void rejectInvisibleValue(){AssetsMaintenanceCostServices.partAmount(n("0.001"),n("1"));}
    @Test public void acceptNativeIntegerRemainingLife(){AssetsMaintenanceCostServices.validateBook(n("1800"),n("0"),n("1800"),n("0"),Long.valueOf(36));}
    @Test public void acceptReconciledBook(){AssetsMaintenanceCostServices.validateBook(n("2000"),n("50"),n("1950"),n("200"),n("35"));}
    @Test(expected=IllegalArgumentException.class)public void rejectUnreconciledBook(){AssetsMaintenanceCostServices.validateBook(n("2000"),n("50"),n("1900"),n("200"),n("35"));}
    @Test(expected=IllegalArgumentException.class)public void rejectMissingBookValues(){AssetsMaintenanceCostServices.validateBook(n("2000"),null,n("1950"),n("200"),n("35"));}
    @Test(expected=IllegalArgumentException.class)public void rejectExpiredLife(){AssetsMaintenanceCostServices.validateBook(n("2000"),n("1800"),n("200"),n("200"),BigDecimal.ZERO);}
    @Test(expected=IllegalArgumentException.class)public void rejectFractionalRemainingLife(){AssetsMaintenanceCostServices.validateBook(n("2000"),n("50"),n("1950"),n("200"),n("35.5"));}
    @Test public void prospectiveImprovementUsesRemainingLifeAndClearsRounding(){
        BigDecimal nbv=n("2050"),total=BigDecimal.ZERO;Map<String,Object> values=new HashMap<>();values.put("salvageValue",n("200"));values.put("capitalizedCost",n("2100"));values.put("usefulLifeMonths",n("36"));
        for(int remaining=35;remaining>0;remaining--){values.put("remainingLifeMonths",BigDecimal.valueOf(remaining));values.put("netBookValue",nbv);AssetsDepreciationServices.applyProspectiveBasis(values);var result=AssetsDepreciationServices.straightLineMonthlyDepreciation(null,values);assertNotEquals(result.toString(),"error",result.get("responseMessage"));BigDecimal amount=(BigDecimal)result.get("depreciationAmount");if(remaining==35)assertEquals(0,n("52.86").compareTo(amount));nbv=nbv.subtract(amount);total=total.add(amount);}
        assertEquals(0,n("1850").compareTo(total));assertEquals(0,n("200").compareTo(nbv));
    }
    @Test(expected=IllegalArgumentException.class)public void prospectiveBasisRejectsNegativeLife(){Map<String,Object> v=new HashMap<>();v.put("remainingLifeMonths",n("-1"));v.put("netBookValue",n("500"));v.put("salvageValue",n("100"));AssetsDepreciationServices.applyProspectiveBasis(v);}
}
