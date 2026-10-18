package org.apache.ofbiz.payroll.services;

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

public final class PayrollSetupServices {

    public static final String MODULE = PayrollSetupServices.class.getName();

    private PayrollSetupServices() { }

    // =========================================================
    // PayrollGlMapping CRUD
    // =========================================================

    public static Map<String, Object> createPayrollGlMapping(DispatchContext dctx, Map<String, ?> context) {
        Delegator delegator = dctx.getDelegator();

        String lineTypeId = (String) context.get("lineTypeId");
        String glAccountId = (String) context.get("glAccountId");
        String drCrFlag = (String) context.get("drCrFlag");

        if (UtilValidate.isEmpty(lineTypeId)) {
            return ServiceUtil.returnError("lineTypeId is required");
        }
        if (UtilValidate.isEmpty(glAccountId)) {
            return ServiceUtil.returnError("glAccountId is required");
        }
        if (UtilValidate.isEmpty(drCrFlag) || (!"DR".equals(drCrFlag) && !"CR".equals(drCrFlag))) {
            return ServiceUtil.returnError("drCrFlag is required and must be DR or CR");
        }

        String orgPartyId = (String) context.get("orgPartyId");
        String isActive = normalizeIndicator((String) context.get("isActive"), "Y");

        Timestamp fromDate = (Timestamp) context.get("fromDate");
        Timestamp thruDate = (Timestamp) context.get("thruDate");
        if (fromDate == null) {
            fromDate = UtilDateTime.nowTimestamp();
        }
        if (thruDate != null && thruDate.before(fromDate)) {
            return ServiceUtil.returnError("thruDate must be >= fromDate");
        }

        try {
            // Optional but recommended: prevent duplicate active mappings for same org + lineType
            // (You can relax this if you want multiple accounts per line type)
            GenericValue existing = EntityQuery.use(delegator)
                    .from("PayrollGlMapping")
                    .where("orgPartyId", orgPartyId,
                           "lineTypeId", lineTypeId,
                           "isActive", "Y")
                    .queryFirst();
            if (existing != null) {
                return ServiceUtil.returnError("Active GL mapping already exists for orgPartyId=" + orgPartyId
                        + " lineTypeId=" + lineTypeId + ". Disable it first or update it.");
            }

            String payrollGlMappingId = delegator.getNextSeqId("PayrollGlMapping");

            GenericValue gv = delegator.makeValue("PayrollGlMapping");
            gv.set("payrollGlMappingId", payrollGlMappingId);
            gv.set("orgPartyId", orgPartyId);
            gv.set("lineTypeId", lineTypeId);
            gv.set("glAccountId", glAccountId);
            gv.set("drCrFlag", drCrFlag);
            gv.set("isActive", isActive);
            gv.set("fromDate", fromDate);
            gv.set("thruDate", thruDate);

            delegator.create(gv);

            Map<String, Object> result = ServiceUtil.returnSuccess();
            result.put("payrollGlMappingId", payrollGlMappingId);
            return result;

        } catch (GenericEntityException e) {
            Debug.logError(e, "Error creating PayrollGlMapping", MODULE);
            return ServiceUtil.returnError("Error creating PayrollGlMapping: " + e.getMessage());
        }
    }

    public static Map<String, Object> updatePayrollGlMapping(DispatchContext dctx, Map<String, ?> context) {
        Delegator delegator = dctx.getDelegator();
        String payrollGlMappingId = (String) context.get("payrollGlMappingId");
        if (UtilValidate.isEmpty(payrollGlMappingId)) {
            return ServiceUtil.returnError("payrollGlMappingId is required");
        }

        try {
            GenericValue gv = EntityQuery.use(delegator)
                    .from("PayrollGlMapping")
                    .where("payrollGlMappingId", payrollGlMappingId)
                    .queryOne();

            if (gv == null) {
                return ServiceUtil.returnError("PayrollGlMapping not found: " + payrollGlMappingId);
            }

            setIfPresent(gv, "orgPartyId", context.get("orgPartyId"));
            setIfPresent(gv, "lineTypeId", context.get("lineTypeId"));
            setIfPresent(gv, "glAccountId", context.get("glAccountId"));

            Object drCr = context.get("drCrFlag");
            if (drCr != null) {
                String drCrFlag = (String) drCr;
                if (!"DR".equals(drCrFlag) && !"CR".equals(drCrFlag)) {
                    return ServiceUtil.returnError("drCrFlag must be DR or CR");
                }
                gv.set("drCrFlag", drCrFlag);
            }

            Object isActive = context.get("isActive");
            if (isActive != null) {
                gv.set("isActive", normalizeIndicator((String) isActive, "Y"));
            }

            Timestamp fromDate = (Timestamp) context.get("fromDate");
            Timestamp thruDate = (Timestamp) context.get("thruDate");
            if (fromDate != null) {
                gv.set("fromDate", fromDate);
            }
            if (thruDate != null) {
                gv.set("thruDate", thruDate);
            }

            Timestamp effectiveFrom = (Timestamp) gv.get("fromDate");
            Timestamp effectiveThru = (Timestamp) gv.get("thruDate");
            if (effectiveFrom != null && effectiveThru != null && effectiveThru.before(effectiveFrom)) {
                return ServiceUtil.returnError("thruDate must be >= fromDate");
            }

            delegator.store(gv);
            return ServiceUtil.returnSuccess();

        } catch (GenericEntityException e) {
            Debug.logError(e, "Error updating PayrollGlMapping", MODULE);
            return ServiceUtil.returnError("Error updating PayrollGlMapping: " + e.getMessage());
        }
    }

    public static Map<String, Object> deletePayrollGlMapping(DispatchContext dctx, Map<String, ?> context) {
        Delegator delegator = dctx.getDelegator();
        String payrollGlMappingId = (String) context.get("payrollGlMappingId");
        if (UtilValidate.isEmpty(payrollGlMappingId)) {
            return ServiceUtil.returnError("payrollGlMappingId is required");
        }

        try {
            GenericValue gv = EntityQuery.use(delegator)
                    .from("PayrollGlMapping")
                    .where("payrollGlMappingId", payrollGlMappingId)
                    .queryOne();
            if (gv == null) {
                return ServiceUtil.returnSuccess(); // idempotent delete
            }
            delegator.removeValue(gv);
            return ServiceUtil.returnSuccess();

        } catch (GenericEntityException e) {
            Debug.logError(e, "Error deleting PayrollGlMapping", MODULE);
            return ServiceUtil.returnError("Error deleting PayrollGlMapping: " + e.getMessage());
        }
    }

    public static Map<String, Object> getPayrollGlMapping(DispatchContext dctx, Map<String, ?> context) {
        Delegator delegator = dctx.getDelegator();
        String payrollGlMappingId = (String) context.get("payrollGlMappingId");
        if (UtilValidate.isEmpty(payrollGlMappingId)) {
            return ServiceUtil.returnError("payrollGlMappingId is required");
        }

        try {
            GenericValue gv = EntityQuery.use(delegator)
                    .from("PayrollGlMapping")
                    .where("payrollGlMappingId", payrollGlMappingId)
                    .queryOne();

            Map<String, Object> result = ServiceUtil.returnSuccess();
            result.put("glMapping", gv);
            return result;

        } catch (GenericEntityException e) {
            Debug.logError(e, "Error reading PayrollGlMapping", MODULE);
            return ServiceUtil.returnError("Error reading PayrollGlMapping: " + e.getMessage());
        }
    }

    // =========================================================
    // PayrollInvoiceMapping CRUD
    // =========================================================

    public static Map<String, Object> createPayrollInvoiceMapping(DispatchContext dctx, Map<String, ?> context) {
        Delegator delegator = dctx.getDelegator();

        String lineTypeId = (String) context.get("lineTypeId");
        String invoiceItemTypeId = (String) context.get("invoiceItemTypeId");
        if (UtilValidate.isEmpty(lineTypeId)) {
            return ServiceUtil.returnError("lineTypeId is required");
        }
        if (UtilValidate.isEmpty(invoiceItemTypeId)) {
            return ServiceUtil.returnError("invoiceItemTypeId is required");
        }

        String orgPartyId = (String) context.get("orgPartyId");
        String isActive = normalizeIndicator((String) context.get("isActive"), "Y");

        Timestamp fromDate = (Timestamp) context.get("fromDate");
        Timestamp thruDate = (Timestamp) context.get("thruDate");
        if (fromDate == null) {
            fromDate = UtilDateTime.nowTimestamp();
        }
        if (thruDate != null && thruDate.before(fromDate)) {
            return ServiceUtil.returnError("thruDate must be >= fromDate");
        }

        try {
            GenericValue existing = EntityQuery.use(delegator)
                    .from("PayrollInvoiceMapping")
                    .where("orgPartyId", orgPartyId,
                           "lineTypeId", lineTypeId,
                           "isActive", "Y")
                    .queryFirst();
            if (existing != null) {
                return ServiceUtil.returnError("Active Invoice mapping already exists for orgPartyId=" + orgPartyId
                        + " lineTypeId=" + lineTypeId + ". Disable it first or update it.");
            }

            String payrollInvoiceMappingId = delegator.getNextSeqId("PayrollInvoiceMapping");

            GenericValue gv = delegator.makeValue("PayrollInvoiceMapping");
            gv.set("payrollInvoiceMappingId", payrollInvoiceMappingId);
            gv.set("orgPartyId", orgPartyId);
            gv.set("lineTypeId", lineTypeId);
            gv.set("invoiceItemTypeId", invoiceItemTypeId);
            gv.set("isActive", isActive);
            gv.set("fromDate", fromDate);
            gv.set("thruDate", thruDate);

            delegator.create(gv);

            Map<String, Object> result = ServiceUtil.returnSuccess();
            result.put("payrollInvoiceMappingId", payrollInvoiceMappingId);
            return result;

        } catch (GenericEntityException e) {
            Debug.logError(e, "Error creating PayrollInvoiceMapping", MODULE);
            return ServiceUtil.returnError("Error creating PayrollInvoiceMapping: " + e.getMessage());
        }
    }

    public static Map<String, Object> updatePayrollInvoiceMapping(DispatchContext dctx, Map<String, ?> context) {
        Delegator delegator = dctx.getDelegator();
        String payrollInvoiceMappingId = (String) context.get("payrollInvoiceMappingId");
        if (UtilValidate.isEmpty(payrollInvoiceMappingId)) {
            return ServiceUtil.returnError("payrollInvoiceMappingId is required");
        }

        try {
            GenericValue gv = EntityQuery.use(delegator)
                    .from("PayrollInvoiceMapping")
                    .where("payrollInvoiceMappingId", payrollInvoiceMappingId)
                    .queryOne();

            if (gv == null) {
                return ServiceUtil.returnError("PayrollInvoiceMapping not found: " + payrollInvoiceMappingId);
            }

            setIfPresent(gv, "orgPartyId", context.get("orgPartyId"));
            setIfPresent(gv, "lineTypeId", context.get("lineTypeId"));
            setIfPresent(gv, "invoiceItemTypeId", context.get("invoiceItemTypeId"));

            Object isActive = context.get("isActive");
            if (isActive != null) {
                gv.set("isActive", normalizeIndicator((String) isActive, "Y"));
            }

            Timestamp fromDate = (Timestamp) context.get("fromDate");
            Timestamp thruDate = (Timestamp) context.get("thruDate");
            if (fromDate != null) {
                gv.set("fromDate", fromDate);
            }
            if (thruDate != null) {
                gv.set("thruDate", thruDate);
            }

            Timestamp effectiveFrom = (Timestamp) gv.get("fromDate");
            Timestamp effectiveThru = (Timestamp) gv.get("thruDate");
            if (effectiveFrom != null && effectiveThru != null && effectiveThru.before(effectiveFrom)) {
                return ServiceUtil.returnError("thruDate must be >= fromDate");
            }

            delegator.store(gv);
            return ServiceUtil.returnSuccess();

        } catch (GenericEntityException e) {
            Debug.logError(e, "Error updating PayrollInvoiceMapping", MODULE);
            return ServiceUtil.returnError("Error updating PayrollInvoiceMapping: " + e.getMessage());
        }
    }

    public static Map<String, Object> deletePayrollInvoiceMapping(DispatchContext dctx, Map<String, ?> context) {
        Delegator delegator = dctx.getDelegator();
        String payrollInvoiceMappingId = (String) context.get("payrollInvoiceMappingId");
        if (UtilValidate.isEmpty(payrollInvoiceMappingId)) {
            return ServiceUtil.returnError("payrollInvoiceMappingId is required");
        }

        try {
            GenericValue gv = EntityQuery.use(delegator)
                    .from("PayrollInvoiceMapping")
                    .where("payrollInvoiceMappingId", payrollInvoiceMappingId)
                    .queryOne();
            if (gv == null) {
                return ServiceUtil.returnSuccess(); // idempotent delete
            }
            delegator.removeValue(gv);
            return ServiceUtil.returnSuccess();

        } catch (GenericEntityException e) {
            Debug.logError(e, "Error deleting PayrollInvoiceMapping", MODULE);
            return ServiceUtil.returnError("Error deleting PayrollInvoiceMapping: " + e.getMessage());
        }
    }

    public static Map<String, Object> getPayrollInvoiceMapping(DispatchContext dctx, Map<String, ?> context) {
        Delegator delegator = dctx.getDelegator();
        String payrollInvoiceMappingId = (String) context.get("payrollInvoiceMappingId");
        if (UtilValidate.isEmpty(payrollInvoiceMappingId)) {
            return ServiceUtil.returnError("payrollInvoiceMappingId is required");
        }

        try {
            GenericValue gv = EntityQuery.use(delegator)
                    .from("PayrollInvoiceMapping")
                    .where("payrollInvoiceMappingId", payrollInvoiceMappingId)
                    .queryOne();

            Map<String, Object> result = ServiceUtil.returnSuccess();
            result.put("invMapping", gv);
            return result;

        } catch (GenericEntityException e) {
            Debug.logError(e, "Error reading PayrollInvoiceMapping", MODULE);
            return ServiceUtil.returnError("Error reading PayrollInvoiceMapping: " + e.getMessage());
        }
    }

    // =========================================================
    // PayrollOrganization update (incl. per-org vendor)
    // =========================================================

    public static Map<String, Object> updatePayrollOrganization(DispatchContext dctx, Map<String, ?> context) {
        Delegator delegator = dctx.getDelegator();
        String orgPartyId = (String) context.get("orgPartyId");
        if (UtilValidate.isEmpty(orgPartyId)) {
            return ServiceUtil.returnError("orgPartyId is required");
        }

        try {
            GenericValue org = EntityQuery.use(delegator)
                    .from("PayrollOrganization")
                    .where("orgPartyId", orgPartyId)
                    .queryOne();

            if (org == null) {
                // Optional: allow create if missing
                org = delegator.makeValue("PayrollOrganization");
                org.set("orgPartyId", orgPartyId);
                org.set("isActive", "Y");
                delegator.create(org);
            }

            setIfPresent(org, "orgName", context.get("orgName"));
            setIfPresent(org, "defaultCurrencyUomId", context.get("defaultCurrencyUomId"));
            setIfPresent(org, "countryGeoId", context.get("countryGeoId"));

            // This field must exist in your entity. If you named it differently, rename here.
            setIfPresent(org, "payrollVendorPartyId", context.get("payrollVendorPartyId"));

            Object isActive = context.get("isActive");
            if (isActive != null) {
                org.set("isActive", normalizeIndicator((String) isActive, "Y"));
            }

            delegator.store(org);
            return ServiceUtil.returnSuccess();

        } catch (GenericEntityException e) {
            Debug.logError(e, "Error updating PayrollOrganization", MODULE);
            return ServiceUtil.returnError("Error updating PayrollOrganization: " + e.getMessage());
        }
    }

    // =========================================================
    // PayrollRun accounting post option update (explicit)
    // =========================================================

    public static Map<String, Object> updatePayrollRunPostOption(DispatchContext dctx, Map<String, ?> context) {
        Delegator delegator = dctx.getDelegator();

        String payrollRunId = (String) context.get("payrollRunId");
        if (UtilValidate.isEmpty(payrollRunId)) {
            return ServiceUtil.returnError("payrollRunId is required");
        }

        String accountingPostOptionId = (String) context.get("accountingPostOptionId");
        if (UtilValidate.isNotEmpty(accountingPostOptionId)) {
            // Hard allow-list (security)
            if (!"PAY_POST_NONE".equals(accountingPostOptionId)
                    && !"PAY_POST_GL".equals(accountingPostOptionId)
                    && !"PAY_POST_INVOICE".equals(accountingPostOptionId)
                    && !"PAY_POST_BOTH".equals(accountingPostOptionId)) {
                return ServiceUtil.returnError("Invalid accountingPostOptionId: " + accountingPostOptionId);
            }
        }

        try {
            GenericValue run = EntityQuery.use(delegator)
                    .from("PayrollRun")
                    .where("payrollRunId", payrollRunId)
                    .queryOne();

            if (run == null) {
                return ServiceUtil.returnError("PayrollRun not found: " + payrollRunId);
            }

            run.set("accountingPostOptionId", accountingPostOptionId);
            delegator.store(run);

            return ServiceUtil.returnSuccess();

        } catch (GenericEntityException e) {
            Debug.logError(e, "Error updating PayrollRun posting option", MODULE);
            return ServiceUtil.returnError("Error updating PayrollRun posting option: " + e.getMessage());
        }
    }

    // =========================================================
    // Helpers
    // =========================================================

    private static String normalizeIndicator(String value, String defaultValue) {
        if (UtilValidate.isEmpty(value)) {
            return defaultValue;
        }
        return "N".equalsIgnoreCase(value) ? "N" : "Y";
    }

    private static void setIfPresent(GenericValue gv, String fieldName, Object value) {
        if (value != null) {
            gv.set(fieldName, value);
        }
    }
}
