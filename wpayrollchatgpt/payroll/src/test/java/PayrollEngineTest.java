package org.ofbiz.payroll;

import org.apache.ofbiz.entity.Delegator;
import org.apache.ofbiz.entity.DelegatorFactory;
import org.apache.ofbiz.service.LocalDispatcher;
import org.apache.ofbiz.service.ServiceDispatcher;  // correct dispatcher factory
import org.apache.ofbiz.service.ServiceUtil;

import org.junit.Before;
import org.junit.Test;
import java.util.HashMap;
import java.util.Map;

import static org.junit.Assert.assertTrue;
import static org.junit.Assert.assertNotNull;

public class PayrollEngineTest {

    private Delegator delegator;
    private LocalDispatcher dispatcher;

    @Before
    public void setUp() throws Exception {
        delegator = DelegatorFactory.getDelegator("default");
        dispatcher = ServiceDispatcher.getLocalDispatcher("default", delegator);
    }

    @Test
    public void testPayrollCalculation() throws Exception {
        Map<String, Object> context = new HashMap<>();
        context.put("employeeId", "EMP001");
        context.put("payPeriodId", "2025-12");

        Map<String, Object> result = dispatcher.runSync("calculatePayroll", context);
        assertTrue("Payroll calculation failed", ServiceUtil.isSuccess(result));
        assertNotNull("Payroll amount missing", result.get("payrollAmount"));
    }
}
