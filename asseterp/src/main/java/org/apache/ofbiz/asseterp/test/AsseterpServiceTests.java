package org.apache.ofbiz.asseterp.test;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.apache.ofbiz.entity.GenericValue;
import org.apache.ofbiz.service.ServiceUtil;
import org.apache.ofbiz.service.testtools.OFBizTestCase;

/**
 * Integration tests for asseterpRegisterAsset and asseterpCreateLifecycleEvent.
 */
public class AsseterpServiceTests extends OFBizTestCase {

    public AsseterpServiceTests(String name) {
        super(name);
    }

    /**
     * Tests asseterpRegisterAsset end-to-end:
     *   1. Returns success with a non-null fixedAssetId
     *   2. FixedAsset row exists in DB with correct fixedAssetName
     *   3. AsseterpLifecycleEvent row was written
     *   4. PK field is asseterpLifecycleEventId (not the old assetLifecycleEventId)
     *   5. Date field is eventDate (not the old asseterpEventDate)
     */
    public void testRegisterAsset() throws Exception {
        GenericValue userLogin = getUserLogin("system");
        String fixedAssetId = null;

        try {
            Map<String, Object> ctx = new HashMap<>();
            ctx.put("fixedAssetTypeId", "ASSET_IT_HW");
            ctx.put("fixedAssetName", "Test Laptop Asseterp 001");
            ctx.put("serialNumber", "SN-AERPTEST-001");
            ctx.put("partyId", "Company");
            ctx.put("userLogin", userLogin);

            Map<String, Object> result = getDispatcher().runSync("asseterpRegisterAsset", ctx);

            assertTrue("asseterpRegisterAsset should return success: "
                    + ServiceUtil.getErrorMessage(result), ServiceUtil.isSuccess(result));

            fixedAssetId = (String) result.get("fixedAssetId");
            assertNotNull("fixedAssetId must be returned in service result", fixedAssetId);
            assertFalse("fixedAssetId must not be empty", fixedAssetId.isEmpty());

            // Verify FixedAsset row — use 'false' to bypass cache (no immutable object issues)
            GenericValue asset = getDelegator().findOne("FixedAsset",
                    map("fixedAssetId", fixedAssetId), false);
            assertNotNull("FixedAsset row must exist for id=" + fixedAssetId, asset);
            assertEquals("FixedAsset fixedAssetName mismatch",
                    "Test Laptop Asseterp 001", asset.getString("fixedAssetName"));

            // Verify lifecycle event
            List<GenericValue> events = getDelegator().findByAnd("AsseterpLifecycleEvent",
                    map("fixedAssetId", fixedAssetId, "asseterpEventTypeId", "ASSET_REGISTERED"),
                    null, false);
            assertFalse("AsseterpLifecycleEvent must exist for asset " + fixedAssetId,
                    events.isEmpty());

            GenericValue event = events.get(0);
            // PK field name: asseterpLifecycleEventId (was wrong as assetLifecycleEventId)
            assertNotNull("asseterpLifecycleEventId must be set",
                    event.getString("asseterpLifecycleEventId"));
            // Date field name: eventDate (was wrong as asseterpEventDate)
            assertNotNull("eventDate must be set",
                    event.getTimestamp("eventDate"));

        } finally {
            if (fixedAssetId != null) {
                getDelegator().removeByAnd("AsseterpLifecycleEvent",
                        map("fixedAssetId", fixedAssetId));
                getDelegator().removeByAnd("PartyFixedAssetAssignment",
                        map("fixedAssetId", fixedAssetId));
                getDelegator().removeByAnd("FixedAssetIdent",
                        map("fixedAssetId", fixedAssetId));
                // Remove FixedAsset by finding a fresh (non-cached) copy first
                GenericValue asset = getDelegator().findOne("FixedAsset",
                        map("fixedAssetId", fixedAssetId), false);
                if (asset != null) {
                    getDelegator().removeByPrimaryKey(asset.getPrimaryKey());
                }
            }
        }
    }

    private static Map<String, Object> map(String... kv) {
        Map<String, Object> m = new HashMap<>();
        for (int i = 0; i < kv.length - 1; i += 2) {
            m.put(kv[i], kv[i + 1]);
        }
        return m;
    }
}
