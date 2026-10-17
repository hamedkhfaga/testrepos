package org.apache.ofbiz.assets.fixedasset;

import java.math.BigDecimal;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.Test;
import static org.junit.Assert.*;

public class AssetsDisposalCalculationTest {
    private Map<String, Object> book(String cost, String dep, String proceeds) {
        Map<String,Object> b = new HashMap<>();
        b.put("originalCostAmount", new BigDecimal(cost)); b.put("accumulatedDepAmount",new BigDecimal(dep)); b.put("proceedsAllocatedAmount",new BigDecimal(proceeds));
        b.put("assetGlAccountId","cost"); b.put("accDepGlAccountId","accum"); b.put("profitGlAccountId","gain"); b.put("lossGlAccountId","loss"); b.put("disposalProceedsGlAccountId","clearing"); return b;
    }
    private List<Map<String,Object>> journal(String cost,String dep,String proceeds) {
        List<Map<String,Object>> rows=AssetsDisposalServices.entries(book(cost,dep,proceeds),"org","USD");
        BigDecimal balance=BigDecimal.ZERO;
        for(Map<String,Object> row:rows) {
            BigDecimal a=(BigDecimal)row.get("amount"); assertTrue(a.signum()>0);
            balance=balance.add(a.multiply("D".equals(row.get("debitCreditFlag"))?BigDecimal.ONE:BigDecimal.ONE.negate()));
            assertEquals("org",row.get("organizationPartyId")); assertEquals("USD",row.get("currencyUomId"));
        }
        assertEquals(0,balance.compareTo(BigDecimal.ZERO)); return rows;
    }
    @Test public void scrapRemovesCostAndAccumulatedDepreciation() { List<Map<String,Object>> rows=journal("1200","300","0"); assertEquals(3,rows.size()); assertEquals(new BigDecimal("900.00"),rows.stream().filter(r->"loss".equals(r.get("glAccountId"))).findFirst().orElseThrow().get("amount")); }
    @Test public void saleAboveNbvRecognizesGain() { List<Map<String,Object>> rows=journal("1200","300","1100"); assertEquals(new BigDecimal("200.00"),rows.stream().filter(r->"gain".equals(r.get("glAccountId"))).findFirst().orElseThrow().get("amount")); }
    @Test public void saleBelowNbvRecognizesLoss() { journal("1200","300","700"); assertEquals(new BigDecimal("-200.00"),AssetsDisposalServices.gainLoss(new BigDecimal("1200"),new BigDecimal("300"),new BigDecimal("700"))); }
    @Test public void fullyDepreciatedScrapBalancesWithoutPnl() { assertEquals(2,journal("1200","1200","0").size()); }
    @Test public void saleAtNbvHasNoPnlEntry() { assertEquals(3,journal("1200","300","900").size()); }
    @Test public void noDepreciationScrapHasTwoEntries() { assertEquals(2,journal("1500","0","0").size()); }
    @Test public void centsRemainExact() { journal("1200.01","300.02","900.00"); }
    @Test(expected=IllegalArgumentException.class) public void accumulatedCannotExceedCost() { journal("100","101","0"); }
    @Test(expected=IllegalArgumentException.class) public void negativeProceedsRejected() { journal("100","0","-1"); }
    @Test(expected=IllegalArgumentException.class) public void missingRequiredAccountRejected() { Map<String,Object>b=book("100","0","0");b.remove("lossGlAccountId");AssetsDisposalServices.entries(b,"org","USD"); }
    @Test public void nativeAccountingCanAugmentEntryMetadata() { List<Map<String,Object>> rows=journal("100","0","0"); rows.get(0).put("glAccountTypeId","OTHER_EXPENSE"); assertEquals("OTHER_EXPENSE", rows.get(0).get("glAccountTypeId")); }
    @Test public void postingCurrencyMustMatchCurrentOrganizationPreference() { AssetsDisposalServices.validateBaseCurrency("USD","USD"); AssetsDisposalServices.validateBaseCurrency("SAR","SAR"); }
    @Test(expected=IllegalArgumentException.class) public void changedBaseCurrencyNeedsConversionWorkflow() { AssetsDisposalServices.validateBaseCurrency("USD","SAR"); }
    @Test public void orderedApprovalPosting() { assertEquals("ASSETS_APPROVED",AssetsDisposalServices.nextStatus("ASSETS_PENDING","APPROVE"));assertEquals("ASSETS_DISPOSED",AssetsDisposalServices.nextStatus("ASSETS_APPROVED","POST")); }
    @Test(expected=IllegalArgumentException.class) public void postBeforeApprovalRejected() { AssetsDisposalServices.nextStatus("ASSETS_PENDING","POST"); }
    @Test(expected=IllegalArgumentException.class) public void disposedCannotReopen() { AssetsDisposalServices.nextStatus("ASSETS_DISPOSED","APPROVE"); }
    @Test(expected=IllegalArgumentException.class) public void makerApprovalNeedsExplicitAdminAttestation() { AssetsDisposalServices.requireSeparateApprover("maker","maker",true,false); }
    @Test public void separateApproverOrAttestingAdminAllowed() { AssetsDisposalServices.requireSeparateApprover("maker","approver",false,false);AssetsDisposalServices.requireSeparateApprover("maker","maker",true,true); }
}
