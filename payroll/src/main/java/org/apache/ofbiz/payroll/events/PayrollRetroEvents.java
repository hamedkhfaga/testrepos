package org.apache.ofbiz.payroll.events;

import org.apache.ofbiz.base.util.Debug;
import org.apache.ofbiz.base.util.UtilMisc;
import org.apache.ofbiz.base.util.UtilValidate;
import org.apache.ofbiz.entity.GenericValue;
import org.apache.ofbiz.service.LocalDispatcher;
import org.apache.ofbiz.service.ServiceUtil;
import org.apache.ofbiz.service.GenericServiceException;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.util.*;

public class PayrollRetroEvents {
    public static final String MODULE = PayrollRetroEvents.class.getName();

    /**
     * Reads:
     * - payrollRetroBatchId
     * - partyIdsText (textarea: one per line OR comma separated)
     *
     * Builds:
     * - partyIdList (List<String>)
     *
     * Calls service: addEmployeesToRetroBatch
     */
    public static String addEmployeesToRetroBatchEvent(HttpServletRequest request, HttpServletResponse response) {
        try {
            LocalDispatcher dispatcher = (LocalDispatcher) request.getAttribute("dispatcher");
            if (dispatcher == null) {
                dispatcher = (LocalDispatcher) request.getSession().getAttribute("dispatcher");
            }

            GenericValue userLogin = (GenericValue) request.getSession().getAttribute("userLogin");
            if (dispatcher == null) {
                request.setAttribute("_ERROR_MESSAGE_", "Dispatcher not available");
                return "error";
            }
            if (userLogin == null) {
                request.setAttribute("_ERROR_MESSAGE_", "UserLogin is required");
                return "error";
            }

            String batchId = request.getParameter("payrollRetroBatchId");
            String partyIdsText = request.getParameter("partyIdsText");

            if (UtilValidate.isEmpty(batchId)) {
                request.setAttribute("_ERROR_MESSAGE_", "payrollRetroBatchId is required");
                return "error";
            }
            if (UtilValidate.isEmpty(partyIdsText)) {
                request.setAttribute("_ERROR_MESSAGE_", "partyIdsText is required (one partyId per line)");
                return "error";
            }

            // Normalize input: split lines, trim, remove blanks, de-duplicate (preserve order)
            Set<String> uniq = new LinkedHashSet<>();

            // Support either newline-separated OR comma-separated (or mixed)
            String normalized = partyIdsText.replace(",", "\n");
            String[] lines = normalized.split("\\r?\\n");

            for (String line : lines) {
                String v = (line == null) ? null : line.trim();
                if (UtilValidate.isNotEmpty(v)) {
                    uniq.add(v);
                }
            }

            if (uniq.isEmpty()) {
                request.setAttribute("_ERROR_MESSAGE_", "No valid partyIds found in partyIdsText");
                return "error";
            }

            List<String> partyIdList = new ArrayList<>(uniq);

            Map<String, Object> svcCtx = UtilMisc.toMap(
                    "payrollRetroBatchId", batchId,
                    "partyIdList", partyIdList,
                    "userLogin", userLogin
            );

            Map<String, Object> res = dispatcher.runSync("addEmployeesToRetroBatch", svcCtx);
            if (ServiceUtil.isError(res)) {
                request.setAttribute("_ERROR_MESSAGE_", ServiceUtil.getErrorMessage(res));
                return "error";
            }

            request.setAttribute("_EVENT_MESSAGE_", "Employees added: " + partyIdList.size());
            return "success";

        } catch (GenericServiceException e) {
            Debug.logError(e, "addEmployeesToRetroBatchEvent service error", MODULE);
            request.setAttribute("_ERROR_MESSAGE_", "Add employees failed: " + e.getMessage());
            return "error";
        } catch (Exception e) {
            Debug.logError(e, "addEmployeesToRetroBatchEvent failed", MODULE);
            request.setAttribute("_ERROR_MESSAGE_", "Add employees failed: " + e.getMessage());
            return "error";
        }
    }
}
