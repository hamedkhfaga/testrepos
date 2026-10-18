package org.apache.ofbiz.loan.services;

import java.sql.Timestamp;
import java.util.Map;

import org.apache.ofbiz.base.util.Debug;
import org.apache.ofbiz.base.util.UtilDateTime;
import org.apache.ofbiz.base.util.UtilValidate;
import org.apache.ofbiz.entity.Delegator;
import org.apache.ofbiz.entity.GenericEntityException;
import org.apache.ofbiz.entity.GenericValue;
import org.apache.ofbiz.entity.util.EntityQuery;
import org.apache.ofbiz.service.DispatchContext;
import org.apache.ofbiz.service.ServiceUtil;

public final class LoanAgreementServices {

    public static final String MODULE = LoanAgreementServices.class.getName();

    private static final String AGREEMENT_TYPE_ID = "LOAN_AGREEMENT";
    private static final String PROVIDER_ROLE_TYPE_ID = "LOAN_PROVIDER";
    private static final String BORROWER_ROLE_TYPE_ID = "LOAN_BORROWER";

    private LoanAgreementServices() {}

    public static Map<String, Object> createLoanAgreementV1(DispatchContext dctx, Map<String, ?> context) {
        Delegator delegator = dctx.getDelegator();

        try {
            String loanId = (String) context.get("loanId");
            Timestamp agreementDate = (Timestamp) context.get("agreementDate");

            if (UtilValidate.isEmpty(loanId)) {
                return ServiceUtil.returnError("loanId is required");
            }
            if (agreementDate == null) {
                agreementDate = UtilDateTime.nowTimestamp();
            }

            GenericValue loan = requireLoan(delegator, loanId);

            String existingAgreementId = trimToNull(loan.getString("agreementId"));
            if (UtilValidate.isNotEmpty(existingAgreementId)) {
                Map<String, Object> result = ServiceUtil.returnSuccess("Loan agreement already exists");
                result.put("loanId", loanId);
                result.put("agreementId", existingAgreementId);
                result.put("duplicateSkipped", "Y");
                return result;
            }

            ensureAgreementTypeExists(delegator, AGREEMENT_TYPE_ID);
            ensureRoleTypeExists(delegator, PROVIDER_ROLE_TYPE_ID);
            ensureRoleTypeExists(delegator, BORROWER_ROLE_TYPE_ID);

            String organizationPartyId = loan.getString("organizationPartyId");
            String borrowerPartyId = loan.getString("partyId");
            if (UtilValidate.isEmpty(organizationPartyId) || UtilValidate.isEmpty(borrowerPartyId)) {
                return ServiceUtil.returnError("Loan organizationPartyId and partyId are required");
            }

            ensurePartyRoleExists(delegator, organizationPartyId, PROVIDER_ROLE_TYPE_ID, "organizationPartyId");
            ensurePartyRoleExists(delegator, borrowerPartyId, BORROWER_ROLE_TYPE_ID, "borrower partyId");

            Timestamp fromDate = firstNonNull(
                    loan.getTimestamp("approvalDate"),
                    loan.getTimestamp("applicationDate"),
                    agreementDate
            );
            Timestamp thruDate = loan.getTimestamp("maturityDate");

            String agreementId = delegator.getNextSeqId("Agreement");

            GenericValue agreement = delegator.makeValue("Agreement");
            agreement.set("agreementId", agreementId);
            agreement.set("agreementTypeId", AGREEMENT_TYPE_ID);
            agreement.set("partyIdFrom", organizationPartyId);
            agreement.set("partyIdTo", borrowerPartyId);
            agreement.set("fromDate", fromDate);
            agreement.set("thruDate", thruDate);
            agreement.set("description", "Loan agreement for loanId=" + loanId);
            if (agreement.getModelEntity().isField("statusId")) {
                agreement.set("statusId", "AGR_ACTIVE");
            }
            if (agreement.getModelEntity().isField("agreementDate")) {
                agreement.set("agreementDate", agreementDate);
            }
            if (agreement.getModelEntity().isField("agreementText")) {
                agreement.set("agreementText", buildAgreementText(loan));
            }
            delegator.create(agreement);

            createAgreementRole(delegator, agreementId, organizationPartyId, PROVIDER_ROLE_TYPE_ID);
            createAgreementRole(delegator, agreementId, borrowerPartyId, BORROWER_ROLE_TYPE_ID);

            createAgreementAttribute(delegator, agreementId, "loanId", loanId);
            createAgreementAttribute(delegator, agreementId, "currencyUomId", loan.getString("currencyUomId"));
            createAgreementAttribute(delegator, agreementId, "principalAmount", stringValue(loan.getBigDecimal("principalAmount")));
            createAgreementAttribute(delegator, agreementId, "interestAmount", stringValue(loan.getBigDecimal("interestAmount")));
            createAgreementAttribute(delegator, agreementId, "feeAmount", stringValue(loan.getBigDecimal("feeAmount")));
            createAgreementAttribute(delegator, agreementId, "totalRepayableAmount", stringValue(loan.getBigDecimal("totalRepayableAmount")));
            createAgreementAttribute(delegator, agreementId, "installmentCount", stringValue(loan.getLong("installmentCount")));
            createAgreementAttribute(delegator, agreementId, "loanTypeEnumId", loan.getString("loanTypeEnumId"));
            createAgreementAttribute(delegator, agreementId, "repaymentChannelEnumId", loan.getString("repaymentChannelEnumId"));

            loan.set("agreementId", agreementId);
            loan.store();

            Map<String, Object> result = ServiceUtil.returnSuccess("Loan agreement created successfully");
            result.put("loanId", loanId);
            result.put("agreementId", agreementId);
            result.put("organizationPartyId", organizationPartyId);
            result.put("partyId", borrowerPartyId);
            result.put("agreementTypeId", AGREEMENT_TYPE_ID);
            result.put("providerRoleTypeId", PROVIDER_ROLE_TYPE_ID);
            result.put("borrowerRoleTypeId", BORROWER_ROLE_TYPE_ID);
            return result;

        } catch (Exception e) {
            Debug.logError(e, "Error creating loan agreement", MODULE);
            return ServiceUtil.returnError(e.getMessage());
        }
    }

    private static GenericValue requireLoan(Delegator delegator, String loanId) throws GenericEntityException {
        GenericValue loan = EntityQuery.use(delegator)
                .from("PartyLoan")
                .where("loanId", loanId)
                .queryOne();
        if (loan == null) {
            throw new GenericEntityException("Loan not found: " + loanId);
        }
        return loan;
    }

    private static void ensureAgreementTypeExists(Delegator delegator, String agreementTypeId) throws GenericEntityException {
        GenericValue agreementType = EntityQuery.use(delegator)
                .from("AgreementType")
                .where("agreementTypeId", agreementTypeId)
                .queryOne();
        if (agreementType == null) {
            throw new GenericEntityException("AgreementType not found: " + agreementTypeId + ". Load XML seed first.");
        }
    }

    private static void ensureRoleTypeExists(Delegator delegator, String roleTypeId) throws GenericEntityException {
        GenericValue roleType = EntityQuery.use(delegator)
                .from("RoleType")
                .where("roleTypeId", roleTypeId)
                .queryOne();
        if (roleType == null) {
            throw new GenericEntityException("RoleType not found: " + roleTypeId + ". Load XML seed first.");
        }
    }

    private static void ensurePartyRoleExists(Delegator delegator, String partyId, String roleTypeId, String partyLabel)
            throws GenericEntityException {
        if (UtilValidate.isEmpty(partyId)) {
            throw new GenericEntityException("Cannot approve loan. Required " + partyLabel + " is empty.");
        }
        if (UtilValidate.isEmpty(roleTypeId)) {
            throw new GenericEntityException("Cannot approve loan. Required roleTypeId is empty for " + partyLabel
                    + "=" + partyId + ".");
        }

        GenericValue party = EntityQuery.use(delegator)
                .from("Party")
                .where("partyId", partyId)
                .queryOne();
        if (party == null) {
            throw new GenericEntityException("Cannot approve loan. Party not found for " + partyLabel + "=" + partyId
                    + ". Create the party before loan approval.");
        }

        GenericValue partyRole = EntityQuery.use(delegator)
                .from("PartyRole")
                .where("partyId", partyId, "roleTypeId", roleTypeId)
                .queryOne();
        if (partyRole == null) {
            throw new GenericEntityException("Cannot approve loan. Missing PartyRole for " + partyLabel + "=" + partyId
                    + ", roleTypeId=" + roleTypeId
                    + ". Add PartyRole " + partyId + " / " + roleTypeId
                    + " before approving the loan.");
        }
    }

    private static void createAgreementRole(Delegator delegator, String agreementId, String partyId, String roleTypeId)
            throws GenericEntityException {
        GenericValue existing = EntityQuery.use(delegator)
                .from("AgreementRole")
                .where("agreementId", agreementId, "partyId", partyId, "roleTypeId", roleTypeId)
                .queryOne();
        if (existing != null) {
            return;
        }

        GenericValue role = delegator.makeValue("AgreementRole");
        role.set("agreementId", agreementId);
        role.set("partyId", partyId);
        role.set("roleTypeId", roleTypeId);
        delegator.create(role);
    }

    private static void createAgreementAttribute(Delegator delegator, String agreementId, String attrName, String attrValue)
            throws GenericEntityException {
        if (UtilValidate.isEmpty(attrName) || attrValue == null) {
            return;
        }

        GenericValue existing = EntityQuery.use(delegator)
                .from("AgreementAttribute")
                .where("agreementId", agreementId, "attrName", attrName)
                .queryOne();
        if (existing != null) {
            return;
        }

        GenericValue attr = delegator.makeValue("AgreementAttribute");
        attr.set("agreementId", agreementId);
        attr.set("attrName", attrName);
        attr.set("attrValue", attrValue);
        delegator.create(attr);
    }

    private static Timestamp firstNonNull(Timestamp... values) {
        if (values == null) {
            return null;
        }
        for (Timestamp value : values) {
            if (value != null) {
                return value;
            }
        }
        return null;
    }

    private static String stringValue(Object value) {
        return value != null ? String.valueOf(value) : null;
    }

    private static String trimToNull(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    private static String buildAgreementText(GenericValue loan) {
        StringBuilder sb = new StringBuilder();
        sb.append("Loan Agreement");
        if (loan != null) {
            sb.append(" | loanId=").append(loan.getString("loanId"));
            if (loan.getBigDecimal("principalAmount") != null) {
                sb.append(" | principal=").append(loan.getBigDecimal("principalAmount"));
            }
            if (loan.getBigDecimal("totalRepayableAmount") != null) {
                sb.append(" | totalRepayable=").append(loan.getBigDecimal("totalRepayableAmount"));
            }
            if (loan.getLong("installmentCount") != null) {
                sb.append(" | installments=").append(loan.getLong("installmentCount"));
            }
        }
        return sb.toString();
    }
}
