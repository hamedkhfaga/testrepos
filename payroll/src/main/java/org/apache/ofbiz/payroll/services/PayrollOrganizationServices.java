package org.apache.ofbiz.payroll.services;

import org.apache.ofbiz.base.util.Debug;
import org.apache.ofbiz.base.util.UtilValidate;
import org.apache.ofbiz.entity.Delegator;
import org.apache.ofbiz.entity.GenericEntityException;
import org.apache.ofbiz.entity.GenericValue;
import org.apache.ofbiz.entity.util.EntityQuery;
import org.apache.ofbiz.service.DispatchContext;
import org.apache.ofbiz.service.ServiceUtil;

import java.util.Map;

public class PayrollOrganizationServices {
    public static final String MODULE = PayrollOrganizationServices.class.getName();

    public static Map<String, Object> createPayrollOrganization(DispatchContext dctx, Map<String, ?> context) {
        Delegator delegator = dctx.getDelegator();
        try {
            String organizationPartyId = (String) context.get("organizationPartyId");
            String parentOrganizationPartyId = (String) context.get("parentOrganizationPartyId");
            String orgName = (String) context.get("orgName");
            String defaultCurrencyUomId = (String) context.get("defaultCurrencyUomId");
            String countryGeoId = (String) context.get("countryGeoId");
            String isActive = (String) context.get("isActive");

            if (UtilValidate.isEmpty(organizationPartyId)) {
                return ServiceUtil.returnError("organizationPartyId is required");
            }
            if (UtilValidate.isEmpty(defaultCurrencyUomId)) {
                return ServiceUtil.returnError("defaultCurrencyUomId is required");
            }

            // Prevent duplicate PK
            GenericValue exists = EntityQuery.use(delegator)
                    .from("PayrollOrganization")
                    .where("organizationPartyId", organizationPartyId)
                    .queryOne();
            if (exists != null) {
                return ServiceUtil.returnError("PayrollOrganization already exists for organizationPartyId=" + organizationPartyId);
            }

            GenericValue org = delegator.makeValue("PayrollOrganization");
            org.set("organizationPartyId", organizationPartyId);
            org.set("parentOrganizationPartyId", parentOrganizationPartyId);
            org.set("orgName", orgName);
            org.set("defaultCurrencyUomId", defaultCurrencyUomId);
            org.set("countryGeoId", countryGeoId);
            org.set("isActive", UtilValidate.isEmpty(isActive) ? "Y" : isActive);

            delegator.create(org);

            return ServiceUtil.returnSuccess();
        } catch (Exception e) {
            Debug.logError(e, "createPayrollOrganization failed", MODULE);
            return ServiceUtil.returnError("createPayrollOrganization failed: " + e.getMessage());
        }
    }

    public static Map<String, Object> updatePayrollOrganization(DispatchContext dctx, Map<String, ?> context) {
        Delegator delegator = dctx.getDelegator();
        try {
            String organizationPartyId = (String) context.get("organizationPartyId");
            if (UtilValidate.isEmpty(organizationPartyId)) {
                return ServiceUtil.returnError("organizationPartyId is required");
            }

            GenericValue org = EntityQuery.use(delegator)
                    .from("PayrollOrganization")
                    .where("organizationPartyId", organizationPartyId)
                    .queryOne();

            if (org == null) {
                return ServiceUtil.returnError("PayrollOrganization not found: organizationPartyId=" + organizationPartyId);
            }

            // Update only provided fields
            if (context.containsKey("parentOrganizationPartyId")) {
                org.set("parentOrganizationPartyId", context.get("parentOrganizationPartyId"));
            }
            if (context.containsKey("orgName")) {
                org.set("orgName", context.get("orgName"));
            }
            if (context.containsKey("defaultCurrencyUomId")) {
                org.set("defaultCurrencyUomId", context.get("defaultCurrencyUomId"));
            }
            if (context.containsKey("countryGeoId")) {
                org.set("countryGeoId", context.get("countryGeoId"));
            }
            if (context.containsKey("isActive")) {
                org.set("isActive", context.get("isActive"));
            }

            org.store();
            return ServiceUtil.returnSuccess();
        } catch (GenericEntityException e) {
            Debug.logError(e, "updatePayrollOrganization failed", MODULE);
            return ServiceUtil.returnError("updatePayrollOrganization failed: " + e.getMessage());
        }
    }
}
