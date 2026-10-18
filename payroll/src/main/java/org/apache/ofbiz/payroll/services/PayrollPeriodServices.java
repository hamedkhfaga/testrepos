package org.apache.ofbiz.payroll.services;

import org.apache.ofbiz.base.util.Debug;
import org.apache.ofbiz.base.util.UtilValidate;
import org.apache.ofbiz.entity.Delegator;
import org.apache.ofbiz.entity.GenericEntityException;
import org.apache.ofbiz.entity.GenericValue;
import org.apache.ofbiz.entity.condition.EntityCondition;
import org.apache.ofbiz.entity.condition.EntityOperator;
import org.apache.ofbiz.entity.util.EntityQuery;
import org.apache.ofbiz.service.DispatchContext;
import org.apache.ofbiz.service.ServiceUtil;

import java.math.BigDecimal;
import java.sql.Date;
import java.util.Calendar;
import java.util.Map;

/**
 * PayrollPeriodServices
 * - resolvePayrollPeriodForMonth: resolves PayrollPeriod for (org + year + month)
 *   and returns default org currency (Option A).
 */
public class PayrollPeriodServices {
    public static final String MODULE = PayrollPeriodServices.class.getName();

    /**
     * Resolve payrollPeriodId for (organizationPartyId + periodYear + periodMonth).
     *
     * Strategy:
     * - Calculate firstDay/lastDay of the requested month.
     * - Find PayrollPeriod where:
     *     organizationPartyId = ?
     *     startDate <= lastDay
     *     endDate   >= firstDay
     *   plus optional filters (periodTypeId/statusId).
     * - Return payrollPeriodId.
     * - Currency resolved from PayrollOrganization.defaultCurrencyUomId.
     */
    public static Map<String, Object> resolvePayrollPeriodForMonth(DispatchContext dctx, Map<String, ?> context) {
        Delegator delegator = dctx.getDelegator();

        try {
            String organizationPartyId = (String) context.get("organizationPartyId");
            Long periodYear = (Long) context.get("periodYear");
            Long periodMonth = (Long) context.get("periodMonth"); // 1..12

            String periodTypeId = (String) context.get("periodTypeId");
            String statusId = (String) context.get("statusId");

            if (UtilValidate.isEmpty(organizationPartyId)) {
                return ServiceUtil.returnError("organizationPartyId is required");
            }
            if (periodYear == null) {
                return ServiceUtil.returnError("periodYear is required");
            }
            if (periodMonth == null || periodMonth < 1 || periodMonth > 12) {
                return ServiceUtil.returnError("periodMonth is required and must be between 1 and 12");
            }

            // Compute first/last day of month as java.sql.Date (PayrollPeriod uses date fields)
            Calendar cal = Calendar.getInstance();
            cal.set(Calendar.YEAR, periodYear.intValue());
            cal.set(Calendar.MONTH, periodMonth.intValue() - 1);
            cal.set(Calendar.DAY_OF_MONTH, 1);
            setStartOfDay(cal);
            Date firstDay = new Date(cal.getTimeInMillis());

            cal.set(Calendar.DAY_OF_MONTH, cal.getActualMaximum(Calendar.DAY_OF_MONTH));
            setEndOfDay(cal);
            Date lastDay = new Date(cal.getTimeInMillis());

            // Build query condition
            EntityCondition cond = EntityCondition.makeCondition(
                    EntityCondition.makeCondition("organizationPartyId", EntityOperator.EQUALS, organizationPartyId),
                    EntityOperator.AND,
                    EntityCondition.makeCondition(
                            EntityCondition.makeCondition("startDate", EntityOperator.LESS_THAN_EQUAL_TO, lastDay),
                            EntityOperator.AND,
                            EntityCondition.makeCondition("endDate", EntityOperator.GREATER_THAN_EQUAL_TO, firstDay)
                    )
            );

            if (UtilValidate.isNotEmpty(periodTypeId)) {
                cond = EntityCondition.makeCondition(cond, EntityOperator.AND,
                        EntityCondition.makeCondition("periodTypeId", EntityOperator.EQUALS, periodTypeId));
            }
            if (UtilValidate.isNotEmpty(statusId)) {
                cond = EntityCondition.makeCondition(cond, EntityOperator.AND,
                        EntityCondition.makeCondition("statusId", EntityOperator.EQUALS, statusId));
            }

            // Prefer the most recently starting matching period
            GenericValue payrollPeriod = EntityQuery.use(delegator)
                    .from("PayrollPeriod")
                    .where(cond)
                    .orderBy("-startDate")
                    .queryFirst();

            if (payrollPeriod == null) {
                return ServiceUtil.returnError(
                        "No PayrollPeriod found for org=" + organizationPartyId +
                        " year=" + periodYear + " month=" + periodMonth +
                        " (expected a period covering " + firstDay + " .. " + lastDay + ")"
                );
            }

            String payrollPeriodId = payrollPeriod.getString("payrollPeriodId");

            // Resolve currency from PayrollOrganization.defaultCurrencyUomId (Option A)
            GenericValue org = EntityQuery.use(delegator)
                    .from("PayrollOrganization")
                    .where("organizationPartyId", organizationPartyId)
                    .queryOne();

            if (org == null) {
                return ServiceUtil.returnError("PayrollOrganization not found for organizationPartyId=" + organizationPartyId);
            }

            String currencyUomId = org.getString("defaultCurrencyUomId");
            if (UtilValidate.isEmpty(currencyUomId)) {
                return ServiceUtil.returnError("PayrollOrganization.defaultCurrencyUomId is empty for org=" + organizationPartyId);
            }

            Map<String, Object> out = ServiceUtil.returnSuccess();
            out.put("payrollPeriodId", payrollPeriodId);
            out.put("currencyUomId", currencyUomId);

            // Default: valuation same currency; exchangeRate = 1
            out.put("valuationCurrencyUomId", currencyUomId);
            out.put("exchangeRate", BigDecimal.ONE);

            out.put("periodStartDate", firstDay);
            out.put("periodEndDate", lastDay);

            return out;

        } catch (GenericEntityException gee) {
            Debug.logError(gee, "resolvePayrollPeriodForMonth entity error", MODULE);
            return ServiceUtil.returnError("resolvePayrollPeriodForMonth entity error: " + gee.getMessage());
        } catch (Exception e) {
            Debug.logError(e, "resolvePayrollPeriodForMonth failed", MODULE);
            return ServiceUtil.returnError("resolvePayrollPeriodForMonth failed: " + e.getMessage());
        }
    }

    private static void setStartOfDay(Calendar cal) {
        cal.set(Calendar.HOUR_OF_DAY, 0);
        cal.set(Calendar.MINUTE, 0);
        cal.set(Calendar.SECOND, 0);
        cal.set(Calendar.MILLISECOND, 0);
    }

    private static void setEndOfDay(Calendar cal) {
        cal.set(Calendar.HOUR_OF_DAY, 23);
        cal.set(Calendar.MINUTE, 59);
        cal.set(Calendar.SECOND, 59);
        cal.set(Calendar.MILLISECOND, 999);
    }
}
