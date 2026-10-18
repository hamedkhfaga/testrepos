package org.apache.ofbiz.loan.services;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.apache.ofbiz.base.util.Debug;
import org.apache.ofbiz.base.util.UtilValidate;
import org.apache.ofbiz.entity.Delegator;
import org.apache.ofbiz.entity.GenericValue;
import org.apache.ofbiz.entity.condition.EntityCondition;
import org.apache.ofbiz.entity.condition.EntityOperator;
import org.apache.ofbiz.entity.util.EntityQuery;
import org.apache.ofbiz.service.DispatchContext;
import org.apache.ofbiz.service.ServiceUtil;

public final class LoanOriginationServices {

    public static final String MODULE = LoanOriginationServices.class.getName();

    private LoanOriginationServices() {}

    public static Map<String, Object> getPartyLoanListV1(DispatchContext dctx, Map<String, ?> context) {
        Delegator delegator = dctx.getDelegator();

        try {
            String organizationPartyId = (String) context.get("organizationPartyId");
            String loanId = trimToNull((String) context.get("loanId"));
            String partyId = trimToNull((String) context.get("partyId"));
            String statusId = trimToNull((String) context.get("statusId"));

            if (UtilValidate.isEmpty(organizationPartyId)) {
                return ServiceUtil.returnError("organizationPartyId is required");
            }

            List<EntityCondition> conditions = new ArrayList<EntityCondition>();
            conditions.add(EntityCondition.makeCondition("organizationPartyId", organizationPartyId));

            if (UtilValidate.isNotEmpty(loanId)) {
                conditions.add(EntityCondition.makeCondition("loanId", loanId));
            }

            if (UtilValidate.isNotEmpty(partyId)) {
                conditions.add(EntityCondition.makeCondition("partyId", partyId));
            }

            if (UtilValidate.isNotEmpty(statusId)) {
                conditions.add(EntityCondition.makeCondition("loanStatusId", statusId));
            }

            EntityCondition cond = EntityCondition.makeCondition(conditions, EntityOperator.AND);

            List<GenericValue> rows = EntityQuery.use(delegator)
                    .from("PartyLoan")
                    .where(cond)
                    .orderBy("-createdStamp", "-loanId")
                    .queryList();

            List<Map<String, Object>> loanList = new ArrayList<Map<String, Object>>();
            for (GenericValue row : rows) {
                Map<String, Object> loan = new HashMap<String, Object>();
                loan.put("loanId", row.getString("loanId"));
                loan.put("loanNumber", row.getString("loanNumber"));
                loan.put("partyId", row.getString("partyId"));
                loan.put("organizationPartyId", row.getString("organizationPartyId"));
                loan.put("loanTypeEnumId", row.getString("loanTypeEnumId"));
                loan.put("currencyUomId", row.getString("currencyUomId"));
                loan.put("loanStatusId", row.getString("loanStatusId"));
                loan.put("principalAmount", big(row.getBigDecimal("principalAmount")));
                loan.put("interestAmount", big(row.getBigDecimal("interestAmount")));
                loan.put("feeAmount", big(row.getBigDecimal("feeAmount")));
                loan.put("totalRepayableAmount", big(row.getBigDecimal("totalRepayableAmount")));
                loan.put("paidAmount", big(row.getBigDecimal("paidAmount")));
                loan.put("outstandingAmount", big(row.getBigDecimal("outstandingAmount")));
                loan.put("installmentCount", row.getLong("installmentCount"));
                loan.put("applicationDate", row.getTimestamp("applicationDate"));
                loan.put("firstDueDate", row.getTimestamp("firstDueDate"));
                loan.put("approvalDate", row.getTimestamp("approvalDate"));
                loan.put("disbursementDate", row.getTimestamp("disbursementDate"));
                loan.put("remarks", row.getString("remarks"));
                loanList.add(loan);
            }

            Map<String, Object> data = new HashMap<String, Object>();
            data.put("loanList", loanList);
            data.put("count", Integer.valueOf(loanList.size()));

            Map<String, Object> out = ServiceUtil.returnSuccess();
            out.putAll(data);
            out.put("data", new HashMap<String, Object>(data));
            out.put("legacyData", new HashMap<String, Object>(data));
            return out;

        } catch (Exception e) {
            Debug.logError(e, "Error loading loan origination list", MODULE);
            Map<String, Object> out = ServiceUtil.returnError(e.getMessage());
            out.put("data", new HashMap<String, Object>());
            out.put("legacyData", new HashMap<String, Object>());
            return out;
        }
    }

    public static Map<String, Object> getLoanTypeOptionsV1(DispatchContext dctx, Map<String, ?> context) {
        Delegator delegator = dctx.getDelegator();

        try {
            List<GenericValue> rows = EntityQuery.use(delegator)
                    .from("LoanTypePolicy")
                    .orderBy("sequenceNum", "loanTypeEnumId")
                    .queryList();

            List<Map<String, Object>> options = new ArrayList<Map<String, Object>>();
            Set<String> seen = new HashSet<String>();

            for (GenericValue row : rows) {
                String loanTypeEnumId = row.getString("loanTypeEnumId");
                if (UtilValidate.isEmpty(loanTypeEnumId) || seen.contains(loanTypeEnumId)) {
                    continue;
                }

                // Hide legacy generic loan types from the Create Loan dropdown.
                if ("LOANTYP_EMP".equals(loanTypeEnumId) || "LOANTYP_CUST".equals(loanTypeEnumId)) {
                    continue;
                }

                seen.add(loanTypeEnumId);

                GenericValue enumRow = EntityQuery.use(delegator)
                        .from("Enumeration")
                        .where("enumId", loanTypeEnumId, "enumTypeId", "LOAN_TYPE")
                        .cache(true)
                        .queryOne();

                String optionLabel = enumRow != null ? enumRow.getString("description") : null;
                if (UtilValidate.isEmpty(optionLabel)) {
                    optionLabel = row.getString("description");
                }
                if (UtilValidate.isEmpty(optionLabel)) {
                    optionLabel = loanTypeEnumId;
                }

                Map<String, Object> option = new HashMap<String, Object>();
                option.put("optionValue", loanTypeEnumId);
                option.put("optionLabel", optionLabel);

                options.add(option);
            }

            Map<String, Object> data = new HashMap<String, Object>();
            data.put("loanTypeOptions", options);
            data.put("count", Integer.valueOf(options.size()));

            Map<String, Object> out = ServiceUtil.returnSuccess();
            out.putAll(data);
            out.put("data", new HashMap<String, Object>(data));
            out.put("legacyData", new HashMap<String, Object>(data));
            return out;

        } catch (Exception e) {
            Debug.logError(e, "Error loading loan type options", MODULE);
            Map<String, Object> out = ServiceUtil.returnError(e.getMessage());
            out.put("data", new HashMap<String, Object>());
            out.put("legacyData", new HashMap<String, Object>());
            return out;
        }
    }

    public static Map<String, Object> getLoanStatusOptionsV1(DispatchContext dctx, Map<String, ?> context) {
        try {
            List<Map<String, Object>> options = new ArrayList<Map<String, Object>>();

            options.add(option("LN_DRAFT", "Draft", "LoanStatusDraft"));
            options.add(option("LN_APPROVED", "Approved", "LoanStatusApproved"));
            options.add(option("LN_ACTIVE", "Active", "LoanStatusActive"));
            options.add(option("LN_PARTIAL", "Partial", "LoanStatusPartial"));
            options.add(option("LN_CLOSED", "Closed", "LoanStatusClosed"));

            Map<String, Object> data = new HashMap<String, Object>();
            data.put("statusOptions", options);
            data.put("count", Integer.valueOf(options.size()));

            Map<String, Object> out = ServiceUtil.returnSuccess();
            out.putAll(data);
            out.put("data", new HashMap<String, Object>(data));
            out.put("legacyData", new HashMap<String, Object>(data));
            return out;

        } catch (Exception e) {
            Debug.logError(e, "Error loading loan status options", MODULE);
            Map<String, Object> out = ServiceUtil.returnError(e.getMessage());
            out.put("data", new HashMap<String, Object>());
            out.put("legacyData", new HashMap<String, Object>());
            return out;
        }
    }

    private static Map<String, Object> option(String optionValue, String optionLabel, String optionLabelKey) {
        Map<String, Object> option = new HashMap<String, Object>();
        option.put("optionValue", optionValue);
        option.put("optionLabel", optionLabel);
        option.put("optionLabelKey", optionLabelKey);
        return option;
    }

    private static String trimToNull(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    private static BigDecimal big(BigDecimal value) {
        return value != null ? value : BigDecimal.ZERO;
    }
}
