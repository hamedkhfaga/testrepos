package org.apache.ofbiz.assets.fixedasset;

import java.math.BigDecimal;
import java.util.HashMap;
import java.util.Map;
import org.junit.Test;
import static org.junit.Assert.*;

public class AssetsDepreciationCalculationTest {
    private Map<String, Object> inputs() {
        Map<String, Object> values = new HashMap<>();
        values.put("capitalizedCost", new BigDecimal("1200"));
        values.put("salvageValue", new BigDecimal("120"));
        values.put("usefulLifeMonths", new BigDecimal("36"));
        return values;
    }

    private void amount(String expected, Map<String, Object> result) {
        assertEquals(result.toString(), 0, new BigDecimal(expected)
                .compareTo((BigDecimal) result.get("depreciationAmount")));
    }

    @Test public void monthlyPeriodAcceptsLeapMonthAndRejectsPartialOrQuarter() throws Exception {
        AssetsDepreciationServices.validateMonthlyPeriod(java.sql.Date.valueOf("2028-02-01"), java.sql.Date.valueOf("2028-02-29"));
        assertThrows(Exception.class, () -> AssetsDepreciationServices.validateMonthlyPeriod(java.sql.Date.valueOf("2026-10-15"), java.sql.Date.valueOf("2026-11-14")));
        assertThrows(Exception.class, () -> AssetsDepreciationServices.validateMonthlyPeriod(java.sql.Date.valueOf("2026-10-01"), java.sql.Date.valueOf("2026-12-31")));
    }
    @Test public void straightLineUsesCostLessSalvage() {
        amount("30", AssetsDepreciationServices.straightLineMonthlyDepreciation(null, inputs()));
    }

    @Test public void straightLineClearsFinalRoundingResidueWithoutGoingBelowSalvage() {
        Map<String, Object> values = inputs();
        values.put("capitalizedCost", new BigDecimal("100"));
        values.put("salvageValue", BigDecimal.ZERO);
        values.put("usefulLifeMonths", BigDecimal.valueOf(3));
        BigDecimal accumulated = BigDecimal.ZERO;
        for (int remaining = 3; remaining > 0; remaining--) {
            values.put("remainingLifeMonths", BigDecimal.valueOf(remaining));
            values.put("accumulatedDepreciation", accumulated);
            var result = AssetsDepreciationServices.straightLineMonthlyDepreciation(null, values);
            amount(remaining == 1 ? "33.34" : "33.33", result);
            accumulated = accumulated.add((BigDecimal) result.get("depreciationAmount"));
        }
        assertEquals(0, new BigDecimal("100").compareTo(accumulated));
    }

    @Test public void sumOfYearsUsesAnnualWeightsAndChangesAfterTwelveMonths() {
        Map<String, Object> values = inputs();
        String[] monthlyAmounts = {"45", "30", "15"};
        BigDecimal total = BigDecimal.ZERO;
        for (int month = 0; month < 36; month++) {
            values.put("remainingLifeMonths", BigDecimal.valueOf(36 - month));
            Map<String, Object> result = AssetsDepreciationServices.sumOfYearsDigitsDepreciation(null, values);
            amount(monthlyAmounts[month / 12], result);
            total = total.add((BigDecimal) result.get("depreciationAmount"));
        }
        assertEquals(0, new BigDecimal("1080").compareTo(total));
    }

    @Test public void sumOfYearsRejectsUnsupportedFractionalYears() {
        Map<String, Object> values = inputs();
        values.put("usefulLifeMonths", BigDecimal.valueOf(18));
        assertEquals("error", AssetsDepreciationServices.sumOfYearsDigitsDepreciation(null, values).get("responseMessage"));
    }

    @Test public void productionDoesNotRoundRatioBeforeMultiplyingCost() {
        Map<String, Object> values = inputs();
        values.put("usageQuantity", BigDecimal.ONE);
        values.put("productionCapacity", BigDecimal.valueOf(30000));
        amount("0.04", AssetsDepreciationServices.unitsOfProductionDepreciation(null, values));
    }

    @Test public void productionCapsAtRemainingDepreciableValue() {
        Map<String, Object> values = inputs();
        values.put("usageQuantity", BigDecimal.valueOf(20000));
        values.put("productionCapacity", BigDecimal.valueOf(10000));
        values.put("accumulatedDepreciation", BigDecimal.valueOf(1000));
        values.put("netBookValue", BigDecimal.valueOf(200));
        amount("80", AssetsDepreciationServices.unitsOfProductionDepreciation(null, values));
    }
}

