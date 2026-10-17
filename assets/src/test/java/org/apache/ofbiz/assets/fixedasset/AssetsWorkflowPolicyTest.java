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
import java.util.Map;
import junit.framework.TestCase;

/** Business cases independent of a running OFBiz database. */
public class AssetsWorkflowPolicyTest extends TestCase {
    private Map<String, Object> request() {
        return new HashMap<>(Map.of("roleTypeId", "INTERNAL_ORGANIZATIO", "fixedAssetTypeId", "LAPTOP",
                "requestTypeEnumId", "NEW", "budgetStatusEnumId", "SECURED", "currencyUomId", "SAR"));
    }

    public void testThresholdsIncludeBoundaryWithoutRoundingAwayExcess() {
        Map<String, Object> rule = Map.of("currencyUomId", "SAR", "minAmount", new BigDecimal("1000"), "maxAmount", new BigDecimal("5000"));
        assertTrue(AssetsRequestServices.matches(rule, request(), new BigDecimal("1000.00")));
        assertTrue(AssetsRequestServices.matches(rule, request(), new BigDecimal("5000.00")));
        assertFalse(AssetsRequestServices.matches(rule, request(), new BigDecimal("5000.001")));
        assertFalse(AssetsRequestServices.matches(rule, request(), new BigDecimal("999.99")));
    }

    public void testThresholdDoesNotCompareDifferentCurrencies() {
        assertFalse(AssetsRequestServices.matches(Map.of("currencyUomId", "USD", "maxAmount", new BigDecimal("5000")), request(), new BigDecimal("100")));
        assertFalse(AssetsRequestServices.matches(Map.of("maxAmount", new BigDecimal("5000")), request(), new BigDecimal("100")));
    }

    public void testUnbudgetedExceptionDoesNotAffectSecuredRequest() {
        Map<String, Object> rule = Map.of("budgetStatusEnumId", "UNBUDGETED");
        Map<String, Object> request = request();
        assertFalse(AssetsRequestServices.matches(rule, request, BigDecimal.ONE));
        request.put("budgetStatusEnumId", "UNBUDGETED");
        assertTrue(AssetsRequestServices.matches(rule, request, BigDecimal.ONE));
    }

    public void testAssetTypeSpecificRuleDoesNotLeakToOtherTypes() {
        assertFalse(AssetsRequestServices.matches(Map.of("fixedAssetTypeId", "VEHICLE"), request(), BigDecimal.ONE));
        assertTrue(AssetsRequestServices.matches(Map.of(), request(), BigDecimal.ONE));
    }

    public void testReversalNetsAccumulatedDepreciationToZero() {
        BigDecimal amount = new BigDecimal("137.42");
        BigDecimal posted = AssetsBalanceServices.signedAccumulation(amount, "C");
        BigDecimal reversed = AssetsBalanceServices.signedAccumulation(amount, "D");
        assertEquals(0, posted.add(reversed).compareTo(BigDecimal.ZERO));
        assertEquals(0, posted.add(reversed).add(AssetsBalanceServices.signedAccumulation(new BigDecimal("125.00"), "C"))
                .compareTo(new BigDecimal("125.00")));
    }

    public void testUnknownAccountingDirectionIsRejected() {
        try {
            AssetsBalanceServices.signedAccumulation(BigDecimal.ONE, "X");
            fail("Unknown debit/credit flag must fail rather than inflate balances");
        } catch (IllegalArgumentException expected) {
            assertEquals("AssetsBalanceInvalidEntry", expected.getMessage());
        }
    }
}
