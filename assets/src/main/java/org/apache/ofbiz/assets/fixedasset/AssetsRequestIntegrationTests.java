/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */
package org.apache.ofbiz.assets.fixedasset;

import java.math.BigDecimal;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.apache.ofbiz.entity.GenericValue;
import org.apache.ofbiz.service.ServiceUtil;
import org.apache.ofbiz.service.testtools.OFBizTestCase;

/** Run against a test delegator after AssetsRequestWorkflowDemoData.xml is loaded. */
public class AssetsRequestIntegrationTests extends OFBizTestCase {
    public AssetsRequestIntegrationTests(String name) { super(name); }

    private Map<String, Object> request() throws Exception {
        Map<String, Object> input = new HashMap<>(Map.of("partyId", "ASDEMO_ORG", "roleTypeId", "INTERNAL_ORGANIZATIO",
                "fixedAssetTypeId", "ASDEMO_LAPTOP", "requirementTypeId", "ASSETS_ACQUIRE",
                "requestTypeEnumId", "ASSETS_REQ_NEW", "priorityEnumId", "ASSETS_PRI_NORMAL",
                "budgetStatusEnumId", "ASSETS_BUD_SECURED", "currencyUomId", "SAR",
                "estimatedCapitalizedCost", new BigDecimal("4200.00"), "description", "Integration test request"));
        input.put("submissionKey", UUID.randomUUID().toString());
        input.put("userLogin", getUserLogin("admin"));
        input.put("dryRun", "Y");
        return input;
    }

    public void testPreviewCreatesNoNativeRequirementOrAsset() throws Exception {
        long requests = from("Requirement").queryCount();
        long assets = from("FixedAsset").queryCount();
        Map<String, Object> result = getDispatcher().runSync("assetsCreateAssetRequest", request());
        assertTrue(ServiceUtil.getErrorMessage(result), ServiceUtil.isSuccess(result));
        assertEquals(1, ((List<?>) result.get("approvalTasks")).size());
        assertEquals(requests, from("Requirement").queryCount());
        assertEquals(assets, from("FixedAsset").queryCount());
    }

    public void testUnbudgetedRouteRequiresTwoDecisions() throws Exception {
        Map<String, Object> input = request();
        input.put("budgetStatusEnumId", "ASSETS_BUD_UNBUDGT");
        Map<String, Object> result = getDispatcher().runSync("assetsCreateAssetRequest", input);
        assertTrue(ServiceUtil.getErrorMessage(result), ServiceUtil.isSuccess(result));
        assertEquals(2, ((List<?>) result.get("approvalTasks")).size());
    }

    public void testNoRouteFailsWithoutCreatingRecords() throws Exception {
        Map<String, Object> input = request();
        input.put("currencyUomId", "USD");
        input.put("dryRun", "N");
        long count = from("Requirement").queryCount();
        Map<String, Object> result = getDispatcher().runSync("assetsCreateAssetRequest", input);
        assertTrue(ServiceUtil.isError(result));
        assertEquals(count, from("Requirement").queryCount());
    }

    @SuppressWarnings("unchecked")
    public void testCorporateAndMemoBalancesStayIndependent() throws Exception {
        Map<String, Object> input = new HashMap<>();
        input.put("fixedAssetIds", List.of("ASDEMO_ASSET_01"));
        input.put("userLogin", getUserLogin("admin"));
        Map<String, Object> result = getDispatcher().runSync("assetsGetAccumDep", input);
        assertTrue(ServiceUtil.getErrorMessage(result), ServiceUtil.isSuccess(result));
        Map<String, Object> corporate = ((List<Map<String, Object>>) result.get("assetList")).get(0);
        assertEquals("ASDEMO_CORP", corporate.get("assetsBookId"));
        assertEquals(0, new BigDecimal("3000.00").compareTo((BigDecimal) corporate.get("netBookValue")));
        input.put("assetsBookId", "ASDEMO_MEMO");
        result = getDispatcher().runSync("assetsGetAccumDep", input);
        assertTrue(ServiceUtil.getErrorMessage(result), ServiceUtil.isSuccess(result));
        Map<String, Object> memo = ((List<Map<String, Object>>) result.get("assetList")).get(0);
        assertEquals("ASDEMO_MEMO", memo.get("assetsBookId"));
        assertEquals(0, new BigDecimal("2700.00").compareTo((BigDecimal) memo.get("netBookValue")));
        assertFalse(memo.containsKey("linkedPostedGlMovement"));
    }

    public void testCommitRetryApprovalAndNativeStatus() throws Exception {
        Map<String, Object> input = request();
        input.put("dryRun", "N");
        String id = null;
        try {
            long assets = from("FixedAsset").queryCount();
            Map<String, Object> result = getDispatcher().runSync("assetsCreateAssetRequest", input);
            assertTrue(ServiceUtil.getErrorMessage(result), ServiceUtil.isSuccess(result));
            id = (String) result.get("requirementId");
            assertNotNull(id);
            Map<String, Object> retry = getDispatcher().runSync("assetsCreateAssetRequest", input);
            assertEquals(id, retry.get("requirementId"));
            assertEquals("Y", retry.get("replayed"));
            assertEquals(assets, from("FixedAsset").queryCount());
            List<GenericValue> tasks = from("AssetsApprovalDecision").where("requirementId", id).queryList();
            assertEquals(1, tasks.size());
            Map<String, Object> decision = Map.of("assetsApprovalDecisionId", tasks.get(0).getString("assetsApprovalDecisionId"),
                    "decisionEnumId", "ASSETS_DEC_APPROVE", "dryRun", "N", "userLogin", getUserLogin("admin"));
            Map<String, Object> approved = getDispatcher().runSync("assetsProcessApprovalDecision", decision);
            assertTrue(ServiceUtil.getErrorMessage(approved), ServiceUtil.isSuccess(approved));
            assertEquals("REQ_APPROVED", from("Requirement").where("requirementId", id).queryOne().getString("statusId"));
            assertTrue(ServiceUtil.isError(getDispatcher().runSync("assetsProcessApprovalDecision", decision)));
            input.put("description", "Different submission with reused key");
            assertTrue(ServiceUtil.isError(getDispatcher().runSync("assetsCreateAssetRequest", input)));
        } finally {
            if (id != null) {
                for (String entity : List.of("AssetsLifecycleEvent", "AssetsApprovalDecision", "AssetsRequestDetail", "RequirementStatus")) {
                    getDelegator().removeByAnd(entity, Map.of("requirementId", id));
                }
                getDelegator().removeByAnd("Requirement", Map.of("requirementId", id));
            }
        }
    }
}
