package org.apache.ofbiz.payroll.services;

import java.math.BigDecimal;
import java.util.Map;
import org.apache.ofbiz.base.util.UtilDateTime;
import org.apache.ofbiz.base.util.UtilMisc;
import org.apache.ofbiz.entity.Delegator;
import org.apache.ofbiz.entity.GenericValue;
import org.apache.ofbiz.entity.util.EntityQuery; // THIS WAS THE MISSING PIECE
import org.apache.ofbiz.service.DispatchContext;
import org.apache.ofbiz.service.LocalDispatcher;
import org.apache.ofbiz.service.ServiceUtil;

public class LoanServices {

    public static Map<String, Object> postLoanRepayment(DispatchContext dctx, Map<String, ? extends Object> context) {
        Delegator delegator = dctx.getDelegator();
        LocalDispatcher dispatcher = dctx.getDispatcher();
        
        String finAccountId = (String) context.get("finAccountId");
        String partyIdTo = (String) context.get("partyIdTo");
        BigDecimal repaymentAmount = (BigDecimal) context.get("amount");

        try {
            // 1. Get the System User to bypass "You must be logged in" errors
            GenericValue systemUser = EntityQuery.use(delegator).from("UserLogin").where("userLoginId", "system").queryOne();

            // 2. Create the Transaction record
            String transId = delegator.getNextSeqId("FinAccountTrans");
            GenericValue trans = delegator.makeValue("FinAccountTrans");
            trans.set("finAccountTransId", transId);
            trans.set("finAccountId", finAccountId);
            trans.set("finAccountTransTypeId", "DEPOSIT");
            trans.set("partyId", partyIdTo);
            trans.set("transactionDate", UtilDateTime.nowTimestamp());
            trans.set("entryDate", UtilDateTime.nowTimestamp());
            trans.set("amount", repaymentAmount.abs()); 
            trans.set("statusId", "FIN_STAT_POSTED");
            trans.create();

            // 3. Trigger Balance Refresh using the SYSTEM user to satisfy security
            dispatcher.runSync("updateFinAccountBalancesFromTrans", 
                UtilMisc.toMap("finAccountId", finAccountId, 
                               "finAccountTransId", transId, 
                               "userLogin", systemUser)); 

            return ServiceUtil.returnSuccess("Repayment posted. ID: " + transId);
        } catch (Exception e) {
            return ServiceUtil.returnError("Execution Error: " + e.getMessage());
        }
    }

    public static Map<String, Object> createLoanBenefit(DispatchContext dctx, Map<String, ? extends Object> context) {
        Delegator delegator = dctx.getDelegator();
        try {
            GenericValue pb = delegator.makeValue("PartyBenefit");
            pb.set("roleTypeIdFrom", "INTERNAL_ORGANIZATIO");
            pb.set("roleTypeIdTo", "EMPLOYEE");
            pb.set("partyIdFrom", "Company");
            pb.set("partyIdTo", (String) context.get("partyIdTo"));
            pb.set("benefitTypeId", "DED_ADV");
            pb.set("fromDate", UtilDateTime.nowTimestamp());
            pb.set("cost", (BigDecimal) context.get("cost"));
            pb.create();
            return ServiceUtil.returnSuccess("PartyBenefit created.");
        } catch (Exception e) {
            return ServiceUtil.returnError("Error: " + e.getMessage());
        }
    }
}