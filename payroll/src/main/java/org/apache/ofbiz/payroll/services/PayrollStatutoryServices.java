package org.apache.ofbiz.payroll.services;

import org.apache.ofbiz.base.util.UtilValidate;
import org.apache.ofbiz.entity.Delegator;
import org.apache.ofbiz.entity.GenericEntityException;
import org.apache.ofbiz.entity.GenericValue;
import org.apache.ofbiz.entity.util.EntityQuery;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class PayrollStatutoryServices {

    private static final BigDecimal ONE_HUNDRED = new BigDecimal("100");

    /* ===================================================== */
    /* Income Tax (Progressive)                               */
    /* ===================================================== */

    /**
     * Progressive tax based on brackets in IncomeTaxBracket.
     *
     * Expected fields:
     * - organizationPartyId
     * - fromAmount (required)
     * - toAmount (optional; null means "no upper cap")
     * - taxRate (percentage)
     */
    public static BigDecimal calculateIncomeTax(
            Delegator delegator,
            String organizationPartyId,
            BigDecimal taxableIncome) throws GenericEntityException {

        if (delegator == null) throw new GenericEntityException("delegator is required");
        if (UtilValidate.isEmpty(organizationPartyId)) throw new GenericEntityException("organizationPartyId is required");
        if (taxableIncome == null) taxableIncome = BigDecimal.ZERO;

        List<GenericValue> brackets = EntityQuery.use(delegator)
                .from("IncomeTaxBracket")
                .where("organizationPartyId", organizationPartyId)
                .orderBy("fromAmount")
                .queryList();

        BigDecimal tax = BigDecimal.ZERO;

        for (GenericValue b : brackets) {
            BigDecimal from = nz(b.getBigDecimal("fromAmount"));
            BigDecimal to = b.getBigDecimal("toAmount"); // can be null for last bracket
            BigDecimal rate = nz(b.getBigDecimal("taxRate"));

            // income below/at bracket start => nothing taxable in this bracket
            if (taxableIncome.compareTo(from) <= 0) {
                continue;
            }

            BigDecimal upper = (to == null) ? taxableIncome : taxableIncome.min(to);

            BigDecimal taxablePart = upper.subtract(from);
            if (taxablePart.signum() <= 0) {
                continue;
            }

            BigDecimal add = taxablePart.multiply(rate).divide(ONE_HUNDRED, 6, RoundingMode.HALF_UP);
            tax = tax.add(add);

            // stop when income is within this bracket
            if (to == null || taxableIncome.compareTo(to) <= 0) {
                break;
            }
        }

        return tax;
    }

    /* ===================================================== */
    /* Statutory (GOSI / Insurance)                           */
    /* ===================================================== */

    /**
     * Calculates statutory contributions based on StatutoryRule rows.
     *
     * NOTE:
     * - The "PAYROLL_APPROVED" check MUST be done by caller (it needs PayrollRun record).
     * - This method is pure calculation only.
     *
     * Expected fields in StatutoryRule:
     * - organizationPartyId
     * - minBaseAmount (optional)
     * - maxBaseAmount (optional)
     * - employeeRate (percentage)
     * - employerRate (percentage)
     */
    public static Map<String, BigDecimal> calculateStatutory(
            Delegator delegator,
            String organizationPartyId,
            BigDecimal baseAmount) throws GenericEntityException {

        if (delegator == null) throw new GenericEntityException("delegator is required");
        if (UtilValidate.isEmpty(organizationPartyId)) throw new GenericEntityException("organizationPartyId is required");
        if (baseAmount == null) baseAmount = BigDecimal.ZERO;

        List<GenericValue> rules = EntityQuery.use(delegator)
                .from("StatutoryRule")
                .where("organizationPartyId", organizationPartyId)
                .queryList();

        BigDecimal emp = BigDecimal.ZERO;
        BigDecimal empr = BigDecimal.ZERO;

        for (GenericValue r : rules) {
            BigDecimal min = r.getBigDecimal("minBaseAmount"); // may be null
            BigDecimal max = r.getBigDecimal("maxBaseAmount"); // may be null

            BigDecimal capped = baseAmount;

            if (min != null) capped = capped.max(min);
            if (max != null) capped = capped.min(max);

            BigDecimal empRate = nz(r.getBigDecimal("employeeRate"));
            BigDecimal emprRate = nz(r.getBigDecimal("employerRate"));

            if (empRate.signum() != 0) {
                emp = emp.add(capped.multiply(empRate).divide(ONE_HUNDRED, 6, RoundingMode.HALF_UP));
            }
            if (emprRate.signum() != 0) {
                empr = empr.add(capped.multiply(emprRate).divide(ONE_HUNDRED, 6, RoundingMode.HALF_UP));
            }
        }

        Map<String, BigDecimal> result = new HashMap<>();
        result.put("EMPLOYEE", emp);
        result.put("EMPLOYER", empr);
        return result;
    }

    private static BigDecimal nz(BigDecimal v) {
        return v == null ? BigDecimal.ZERO : v;
    }
}
