/*
 * Asset ERP Depreciation Formula Services
 * All services implement fixedAssetDepCalcInterface.
 *
 * IN  (from interface): fixedAssetId, expEndOfLifeYear, assetAcquiredYear,
 *                       purchaseCost, salvageValue, usageYears
 * OUT (from interface): nextDepreciationAmount (BigDecimal),
 *                       assetDepreciationTillDate (List),
 *                       assetNBVAfterDepreciation (List)
 */

import java.math.BigDecimal
import java.math.RoundingMode

// ----------------------------------------------------------------
// SYD — Sum-of-Years-Digits (IAS 16 / GAAP accelerated)
// ----------------------------------------------------------------
Map asseterpSydDepreciation() {
    int n = (parameters.expEndOfLifeYear as int) - (parameters.assetAcquiredYear as int)
    if (n <= 0) return error("SYD requires expEndOfLifeYear > assetAcquiredYear")

    BigDecimal cost    = new BigDecimal(parameters.purchaseCost.toString())
    BigDecimal salvage = parameters.salvageValue
            ? new BigDecimal(parameters.salvageValue.toString()) : BigDecimal.ZERO
    BigDecimal depreciable = cost - salvage
    int sumOfYears = (n * (n + 1)).intdiv(2)  // n*(n+1)/2
    int usageYears = parameters.usageYears as int  // years already elapsed

    // nextDepreciationAmount = SYD fraction for the upcoming year (usageYears + 1, 1-indexed)
    int nextYearIndex = usageYears + 1  // 1-indexed year we are about to post
    if (nextYearIndex > n) {
        return success([nextDepreciationAmount: BigDecimal.ZERO,
                        assetDepreciationTillDate: [],
                        assetNBVAfterDepreciation: []])
    }
    BigDecimal nextFraction = new BigDecimal(n - nextYearIndex + 1)
            .divide(new BigDecimal(sumOfYears), 10, RoundingMode.HALF_UP)
    BigDecimal nextAmount = depreciable.multiply(nextFraction).setScale(2, RoundingMode.HALF_UP)

    // Cap at remaining
    BigDecimal accumulated = from('FixedAsset')
            .where(fixedAssetId: parameters.fixedAssetId).queryOne()
            ?.getBigDecimal("depreciation") ?: BigDecimal.ZERO
    BigDecimal remaining = depreciable - accumulated
    if (nextAmount > remaining) nextAmount = remaining
    if (nextAmount < BigDecimal.ZERO) nextAmount = BigDecimal.ZERO

    // Build full schedule lists
    List depList = []
    List nbvList = []
    BigDecimal running = BigDecimal.ZERO
    for (int yr = 1; yr <= n; yr++) {
        BigDecimal frac = new BigDecimal(n - yr + 1)
                .divide(new BigDecimal(sumOfYears), 10, RoundingMode.HALF_UP)
        BigDecimal dep = depreciable.multiply(frac).setScale(2, RoundingMode.HALF_UP)
        running += dep
        depList << dep
        nbvList << (cost - running).setScale(2, RoundingMode.HALF_UP)
    }

    return success([nextDepreciationAmount: nextAmount,
                    assetDepreciationTillDate: depList,
                    assetNBVAfterDepreciation: nbvList])
}

// ----------------------------------------------------------------
// UOP — Units of Production (IAS 16)
// Reads cumulative FixedAssetMeter values for all productMeterTypeId rows.
// nextDepreciationAmount = rate * totalUnitsToDate - alreadyAccumulated
// ----------------------------------------------------------------
Map asseterpUopDepreciation() {
    BigDecimal cost    = new BigDecimal(parameters.purchaseCost.toString())
    BigDecimal salvage = parameters.salvageValue
            ? new BigDecimal(parameters.salvageValue.toString()) : BigDecimal.ZERO
    BigDecimal depreciable = cost - salvage

    GenericValue fixedAsset = from('FixedAsset')
            .where(fixedAssetId: parameters.fixedAssetId).queryOne()
    BigDecimal capacity = fixedAsset?.getBigDecimal("productionCapacity")
    if (!capacity || capacity <= BigDecimal.ZERO) {
        return error("UOP depreciation requires productionCapacity on FixedAsset ${parameters.fixedAssetId}")
    }

    // Sum all meter readings across all meter types for this asset
    List<GenericValue> readings = from('FixedAssetMeter')
            .where(fixedAssetId: parameters.fixedAssetId).queryList()
    BigDecimal totalUnits = readings
            .collect { it.getBigDecimal("meterValue") ?: BigDecimal.ZERO }
            .inject(BigDecimal.ZERO) { a, b -> a + b }

    BigDecimal rate = depreciable.divide(capacity, 10, RoundingMode.HALF_UP)
    BigDecimal totalEarnedByUnits = rate.multiply(totalUnits).setScale(2, RoundingMode.HALF_UP)

    BigDecimal accumulated = fixedAsset?.getBigDecimal("depreciation") ?: BigDecimal.ZERO
    BigDecimal remaining   = depreciable - accumulated
    BigDecimal nextAmount  = totalEarnedByUnits - accumulated

    if (nextAmount < BigDecimal.ZERO) nextAmount = BigDecimal.ZERO
    if (nextAmount > remaining)        nextAmount = remaining

    BigDecimal closingNbv = (cost - accumulated - nextAmount).setScale(2, RoundingMode.HALF_UP)

    return success([nextDepreciationAmount: nextAmount,
                    assetDepreciationTillDate: [nextAmount],
                    assetNBVAfterDepreciation: [closingNbv]])
}

// ----------------------------------------------------------------
// RB — Reducing Balance (Diminishing Balance)
// FORMULA: charge = NBV * rate%
// rate = 1 - (salvage/cost)^(1/n)   (geometric rate that exhausts to salvage)
// If salvage = 0 we use rate = 2/n (same as 200DB but applied to NBV each year).
// ----------------------------------------------------------------
Map asseterpRbDepreciation() {
    int n = (parameters.expEndOfLifeYear as int) - (parameters.assetAcquiredYear as int)
    if (n <= 0) return error("RB requires expEndOfLifeYear > assetAcquiredYear")

    BigDecimal cost    = new BigDecimal(parameters.purchaseCost.toString())
    BigDecimal salvage = parameters.salvageValue
            ? new BigDecimal(parameters.salvageValue.toString()) : BigDecimal.ZERO
    if (cost <= BigDecimal.ZERO) return error("RB requires purchaseCost > 0")

    // Geometric rate: r = 1 - (S/C)^(1/n). Falls back to 2/n when salvage=0.
    double rate
    if (salvage > BigDecimal.ZERO && cost > BigDecimal.ZERO) {
        rate = 1.0 - Math.pow((salvage / cost).doubleValue(), 1.0 / n)
    } else {
        rate = 2.0 / n
    }
    BigDecimal bigRate = new BigDecimal(rate).setScale(10, RoundingMode.HALF_UP)

    // Build full schedule
    List depList = []
    List nbvList = []
    BigDecimal nbv = cost
    for (int yr = 0; yr < n; yr++) {
        BigDecimal dep = nbv.multiply(bigRate).setScale(2, RoundingMode.HALF_UP)
        // Last year: reduce exactly to salvage
        if (yr == n - 1) dep = (nbv - salvage).max(BigDecimal.ZERO)
        nbv = (nbv - dep).setScale(2, RoundingMode.HALF_UP)
        depList << dep
        nbvList << nbv
    }

    GenericValue fixedAsset = from('FixedAsset')
            .where(fixedAssetId: parameters.fixedAssetId).queryOne()
    BigDecimal accumulated = fixedAsset?.getBigDecimal("depreciation") ?: BigDecimal.ZERO
    int usageYears = parameters.usageYears as int
    BigDecimal nextAmount = usageYears < depList.size()
            ? depList[usageYears] : BigDecimal.ZERO

    // Cap at remaining depreciable value
    BigDecimal remaining = (cost - salvage - accumulated).max(BigDecimal.ZERO)
    if (nextAmount > remaining) nextAmount = remaining

    return success([nextDepreciationAmount: nextAmount,
                    assetDepreciationTillDate: depList,
                    assetNBVAfterDepreciation: nbvList])
}

// ----------------------------------------------------------------
// NONE — Non-Depreciable (Land, perpetual assets)
// Always returns zero — no depreciation posted.
// ----------------------------------------------------------------
Map asseterpNoneDepreciation() {
    GenericValue fixedAsset = from('FixedAsset')
            .where(fixedAssetId: parameters.fixedAssetId).queryOne()
    if (!fixedAsset) return error("FixedAsset not found: ${parameters.fixedAssetId}")
    BigDecimal cost = fixedAsset.getBigDecimal("purchaseCost") ?: BigDecimal.ZERO
    return success([nextDepreciationAmount: BigDecimal.ZERO,
                    assetDepreciationTillDate: [BigDecimal.ZERO],
                    assetNBVAfterDepreciation: [cost]])
}

// ----------------------------------------------------------------
// MACRS — Modified Accelerated Cost Recovery System (US IRS Rev. Proc. 87-57)
// Property class read from FixedAssetAttribute.attrName = 'MACRS_PROPERTY_CLASS'
// (attrValue: "3YR", "5YR", "7YR", "10YR", "15YR", "20YR")
// No salvage value under MACRS.
// ----------------------------------------------------------------
Map asseterpMacrsDepreciation() {
    // Half-year convention rates (percent, 200% DB switching to SL)
    Map MACRS_RATES = [
        "3YR" : [33.33, 44.45, 14.81, 7.41],
        "5YR" : [20.00, 32.00, 19.20, 11.52, 11.52, 5.76],
        "7YR" : [14.29, 24.49, 17.49, 12.49,  8.93,  8.92,  8.93, 4.46],
        "10YR": [10.00, 18.00, 14.40, 11.52,  9.22,  7.37,  6.55, 6.55, 6.56, 6.55, 3.28],
        "15YR": [5.00, 9.50, 8.55, 7.70, 6.93, 6.23, 5.90, 5.90, 5.91, 5.90, 5.91, 5.90, 5.91, 5.90, 5.91, 2.95],
        "20YR": [3.750, 7.219, 6.677, 6.177, 5.713, 5.285, 4.888, 4.522, 4.462, 4.461, 4.462, 4.461, 4.462, 4.461, 4.462, 4.461, 4.462, 4.461, 4.462, 4.461, 2.231]
    ]

    // Read property class from FixedAssetAttribute
    GenericValue attr = from('FixedAssetAttribute')
            .where(fixedAssetId: parameters.fixedAssetId, attrName: 'MACRS_PROPERTY_CLASS')
            .queryOne()
    String propertyClass = attr?.attrValue ?: "5YR"

    List rates = MACRS_RATES[propertyClass]
    if (!rates) return error("Unknown MACRS property class: ${propertyClass}. Valid: ${MACRS_RATES.keySet()}")

    BigDecimal cost     = new BigDecimal(parameters.purchaseCost.toString())
    int usageYears      = parameters.usageYears as int  // 0 = first year

    // Build full schedule
    List depList = []
    List nbvList = []
    BigDecimal running = BigDecimal.ZERO
    rates.each { r ->
        BigDecimal dep = cost.multiply(new BigDecimal(r.toString())
                .divide(new BigDecimal("100"), 10, RoundingMode.HALF_UP))
                .setScale(2, RoundingMode.HALF_UP)
        running += dep
        depList << dep
        nbvList << (cost - running).setScale(2, RoundingMode.HALF_UP)
    }

    if (usageYears >= rates.size()) {
        return success([nextDepreciationAmount: BigDecimal.ZERO,
                        assetDepreciationTillDate: depList,
                        assetNBVAfterDepreciation: nbvList])
    }

    BigDecimal nextAmount = depList[usageYears]

    // Cap at remaining (MACRS no salvage, depreciates to zero)
    GenericValue fixedAsset = from('FixedAsset')
            .where(fixedAssetId: parameters.fixedAssetId).queryOne()
    BigDecimal accumulated = fixedAsset?.getBigDecimal("depreciation") ?: BigDecimal.ZERO
    BigDecimal remaining   = cost - accumulated
    if (nextAmount > remaining) nextAmount = remaining
    if (nextAmount < BigDecimal.ZERO) nextAmount = BigDecimal.ZERO

    return success([nextDepreciationAmount: nextAmount,
                    assetDepreciationTillDate: depList,
                    assetNBVAfterDepreciation: nbvList])
}
