package org.apache.ofbiz.loan.services;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.apache.ofbiz.base.util.Debug;
import org.apache.ofbiz.base.util.UtilValidate;
import org.apache.ofbiz.entity.Delegator;
import org.apache.ofbiz.entity.GenericValue;
import org.apache.ofbiz.entity.condition.EntityCondition;
import org.apache.ofbiz.entity.condition.EntityOperator;
import org.apache.ofbiz.entity.util.EntityQuery;
import org.apache.ofbiz.service.DispatchContext;
import org.apache.ofbiz.service.ServiceUtil;

public final class LoanScheduleReadServices {

    public static final String MODULE = LoanScheduleReadServices.class.getName();

    private LoanScheduleReadServices() {}

    public static Map<String, Object> getPartyLoanScheduleListV1(DispatchContext dctx, Map<String, ?> context) {
        Delegator delegator = dctx.getDelegator();

        try {
            String loanId = trimToNull((String) context.get("loanId"));
            String loanScheduleId = trimToNull((String) context.get("loanScheduleId"));
            String statusId = trimToNull((String) context.get("statusId"));

            if (UtilValidate.isEmpty(loanId) && UtilValidate.isEmpty(loanScheduleId)) {
                return ServiceUtil.returnError("loanId or loanScheduleId is required");
            }

            List<EntityCondition> conditions = new ArrayList<EntityCondition>();

            if (UtilValidate.isNotEmpty(loanId)) {
                conditions.add(EntityCondition.makeCondition("loanId", loanId));
            }

            if (UtilValidate.isNotEmpty(loanScheduleId)) {
                conditions.add(EntityCondition.makeCondition("loanScheduleId", loanScheduleId));
            }

            if (UtilValidate.isNotEmpty(statusId)) {
                conditions.add(EntityCondition.makeCondition("scheduleStatusId", statusId));
            }

            EntityCondition cond = EntityCondition.makeCondition(conditions, EntityOperator.AND);

            List<GenericValue> rows = EntityQuery.use(delegator)
                    .from("PartyLoanSchedule")
                    .where(cond)
                    .orderBy("installmentSeqNum", "loanScheduleId")
                    .queryList();

            GenericValue loan = null;
            if (UtilValidate.isNotEmpty(loanId)) {
                loan = EntityQuery.use(delegator)
                        .from("PartyLoan")
                        .where("loanId", loanId)
                        .queryOne();
            } else if (UtilValidate.isNotEmpty(loanScheduleId) && UtilValidate.isNotEmpty(rows)) {
                String fetchedLoanId = rows.get(0).getString("loanId");
                if (UtilValidate.isNotEmpty(fetchedLoanId)) {
                    loan = EntityQuery.use(delegator)
                            .from("PartyLoan")
                            .where("loanId", fetchedLoanId)
                            .queryOne();
                }
            }

            List<Map<String, Object>> scheduleRows = new ArrayList<Map<String, Object>>();

            for (GenericValue row : rows) {
                Map<String, Object> item = new HashMap<String, Object>();

                item.put("loanScheduleId", row.getString("loanScheduleId"));
                item.put("loanId", row.getString("loanId"));
                item.put("installmentSeqNum", row.getLong("installmentSeqNum"));
                item.put("dueDate", ts(row.getTimestamp("dueDate")));
                item.put("originalDueDate", ts(row.getTimestamp("originalDueDate")));
                item.put("duePayrollPeriodId", row.getString("duePayrollPeriodId"));
                item.put("principalAmount", decimal(row.getBigDecimal("principalAmount")));
                item.put("interestAmount", decimal(row.getBigDecimal("interestAmount")));
                item.put("feeAmount", decimal(row.getBigDecimal("feeAmount")));
                item.put("penaltyAmount", decimal(row.getBigDecimal("penaltyAmount")));
                item.put("installmentAmount", decimal(row.getBigDecimal("installmentAmount")));
                item.put("paidAmount", decimal(row.getBigDecimal("paidAmount")));
                item.put("outstandingAmount", decimal(row.getBigDecimal("outstandingAmount")));
                item.put("openingBalance", decimal(row.getBigDecimal("openingBalance")));
                item.put("closingBalance", decimal(row.getBigDecimal("closingBalance")));
                item.put("scheduleStatusId", row.getString("scheduleStatusId"));
                item.put("changeReasonEnumId", row.getString("changeReasonEnumId"));
                item.put("processedPayrollRunId", row.getString("processedPayrollRunId"));
                item.put("processedPayrollResultId", row.getString("processedPayrollResultId"));
                item.put("repaymentSourceEnumId", row.getString("repaymentSourceEnumId"));

                if (loan != null) {
                    item.put("organizationPartyId", loan.getString("organizationPartyId"));
                    item.put("partyId", loan.getString("partyId"));
                    item.put("currencyUomId", loan.getString("currencyUomId"));
                    item.put("loanStatusId", loan.getString("loanStatusId"));
                    item.put("loanTypeEnumId", loan.getString("loanTypeEnumId"));
                }

                scheduleRows.add(item);
            }

            Map<String, Object> data = new HashMap<String, Object>();
            data.put("loanId", loan != null ? loan.getString("loanId") : loanId);
            data.put("scheduleRows", scheduleRows);
            data.put("count", Integer.valueOf(scheduleRows.size()));

            Map<String, Object> out = ServiceUtil.returnSuccess();
            out.putAll(data);
            out.put("data", new HashMap<String, Object>(data));
            out.put("legacyData", new HashMap<String, Object>(data));
            return out;

        } catch (Exception e) {
            Debug.logError(e, "Error loading party loan schedule list", MODULE);
            Map<String, Object> out = ServiceUtil.returnError(e.getMessage());
            out.put("data", new HashMap<String, Object>());
            out.put("legacyData", new HashMap<String, Object>());
            return out;
        }
    }

    private static String trimToNull(String value) {
        if (value == null) return null;
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    private static BigDecimal decimal(BigDecimal value) {
        return value != null ? value : BigDecimal.ZERO;
    }

    private static String ts(Timestamp value) {
        return value != null ? value.toString() : null;
    }
}