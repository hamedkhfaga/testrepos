package org.apache.ofbiz.assets.fixedasset;

import java.math.BigDecimal;
import java.util.List;
import java.util.HashMap;
import java.util.Map;
import org.junit.Test;

public class AssetsProcurementPolicyTest {
    @Test public void approvedProductSnapshotSurvivesDecimalScaleNormalization() throws Exception {
        Map<String, Object> submitted = new HashMap<>();
        submitted.put("requirementTypeId", "PRODUCT_REQUIREMENT"); submitted.put("description", "Laptop");
        submitted.put("productId", "LAPTOP"); submitted.put("quantity", new BigDecimal("1.0"));
        Map<String, Object> detail = new HashMap<>(); detail.put("submissionHash", AssetsRequestServices.fingerprint(submitted));
        Map<String, Object> nativeRequirement = new HashMap<>(submitted); nativeRequirement.put("quantity", new BigDecimal("1.000000"));
        AssetsRequestServices.validateProcurementSnapshot(detail, nativeRequirement);
    }
    @Test(expected = IllegalArgumentException.class) public void changingProductAfterApprovalIsRejected() throws Exception {
        Map<String, Object> submitted = new HashMap<>();
        submitted.put("requirementTypeId", "PRODUCT_REQUIREMENT"); submitted.put("description", "Laptop");
        submitted.put("productId", "LAPTOP"); submitted.put("quantity", BigDecimal.ONE);
        Map<String, Object> detail = new HashMap<>(); detail.put("submissionHash", AssetsRequestServices.fingerprint(submitted));
        submitted.put("productId", "OTHER_PRODUCT");
        AssetsRequestServices.validateProcurementSnapshot(detail, submitted);
    }
    @Test public void completedNativeAndAssetApprovalsPermitPurchasing() {
        AssetsProcurementServices.requireApproved("ASSETS_APPROVED", "REQ_APPROVED", List.of("ASSETS_APPROVED", "ASSETS_APPROVED"));
    }
    @Test(expected = IllegalArgumentException.class) public void nativeApprovalCannotBypassAssetsTasks() {
        AssetsProcurementServices.requireApproved("ASSETS_APPROVED", "REQ_APPROVED", List.of("ASSETS_APPROVED", "ASSETS_PENDING"));
    }
    @Test(expected = IllegalArgumentException.class) public void anEmptyRouteCannotPurchase() {
        AssetsProcurementServices.requireApproved("ASSETS_APPROVED", "REQ_APPROVED", List.of());
    }
    @Test(expected = IllegalArgumentException.class) public void cancelledNativeRequirementCannotPurchase() {
        AssetsProcurementServices.requireApproved("ASSETS_APPROVED", "REQ_REJECTED", List.of("ASSETS_APPROVED"));
    }
    @Test public void exactApprovedAmountIsAllowed() {
        AssetsProcurementServices.validateApprovedAmount(new BigDecimal("2"), new BigDecimal("1200"), new BigDecimal("2400"));
    }
    @Test(expected = IllegalArgumentException.class) public void aCentOverApprovedAmountIsRejected() {
        AssetsProcurementServices.validateApprovedAmount(BigDecimal.ONE, new BigDecimal("2400.01"), new BigDecimal("2400"));
    }
    @Test(expected = IllegalArgumentException.class) public void zeroQuantityCannotCreateOrder() {
        AssetsProcurementServices.validateApprovedAmount(BigDecimal.ZERO, BigDecimal.ONE, BigDecimal.TEN);
    }
    @Test(expected = IllegalArgumentException.class) public void negativePriceCannotCreateOrder() {
        AssetsProcurementServices.validateApprovedAmount(BigDecimal.ONE, BigDecimal.ONE.negate(), BigDecimal.TEN);
    }
}
