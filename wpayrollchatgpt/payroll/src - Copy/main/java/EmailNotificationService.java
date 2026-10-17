package org.apache.ofbiz.payroll;

import java.math.BigDecimal;
import java.util.*;

import org.apache.ofbiz.entity.Delegator;
import org.apache.ofbiz.entity.GenericEntityException;
import org.apache.ofbiz.entity.GenericValue;
import org.apache.ofbiz.entity.util.EntityQuery;
import org.apache.ofbiz.service.DispatchContext;
import org.apache.ofbiz.service.ServiceUtil;
import org.apache.ofbiz.base.util.Debug;
import org.apache.ofbiz.base.util.UtilMisc;

/**
 * Email Notification Service - Handles payroll notifications
 */
public class EmailNotificationService {

    public static final String module = EmailNotificationService.class.getName();

    /**
     * Queue payslip email for employee
     */
    public static Map<String, Object> queuePayslipEmail(DispatchContext dctx, Map<String, ?> context) {
        Delegator delegator = dctx.getDelegator();
        Map<String, Object> result = ServiceUtil.returnSuccess();
        
        try {
            String payrollResultId = (String) context.get("payrollResultId");
            
            // Get payroll result and employee details
            GenericValue payrollResult = EntityQuery.use(delegator)
                    .from("EmployeePayrollResult")
                    .where("payrollResultId", payrollResultId)
                    .queryOne();
            
            if (payrollResult != null) {
                String partyId = payrollResult.getString("partyId");
                
                // Get employee email (in real implementation, fetch from PartyContactMech)
                String employeeEmail = "employee_" + partyId + "@company.com"; // Demo email
                
                // Create email queue entry
                String emailQueueId = delegator.getNextSeqId("PayrollEmailQueue");
                
                GenericValue emailQueue = delegator.makeValue("PayrollEmailQueue");
                emailQueue.set("emailQueueId", emailQueueId);
                emailQueue.set("payrollIterationId", payrollResult.getString("payrollIterationId"));
                emailQueue.set("partyId", partyId);
                emailQueue.set("emailType", "PAYSLIP");
                emailQueue.set("toAddress", employeeEmail);
                emailQueue.set("subject", "Your Payslip is Ready");
                emailQueue.set("content", generatePayslipEmailContent(payrollResult));
                emailQueue.set("statusId", "PENDING");
                emailQueue.set("createdDate", new java.sql.Timestamp(System.currentTimeMillis()));
                
                delegator.create(emailQueue);
                
                result.put("emailQueueId", emailQueueId);
                result.put("toAddress", employeeEmail);
            }
            
        } catch (Exception e) {
            Debug.logError(e, "Error queueing payslip email", module);
            return ServiceUtil.returnError("Email queue failed: " + e.getMessage());
        }
        
        return result;
    }

    /**
     * Generate email content for payslip
     */
    private static String generatePayslipEmailContent(GenericValue payrollResult) {
        StringBuilder content = new StringBuilder();
        content.append("Dear Employee,\n\n");
        content.append("Your payslip for the period is now available.\n\n");
        
        BigDecimal grossSalary = payrollResult.getBigDecimal("grossSalary");
        BigDecimal netSalary = payrollResult.getBigDecimal("netSalary");
        BigDecimal totalDeductions = payrollResult.getBigDecimal("totalDeductions");
        
        content.append("Gross Salary: ").append(grossSalary != null ? grossSalary.toString() : "0.00").append("\n");
        content.append("Net Salary: ").append(netSalary != null ? netSalary.toString() : "0.00").append("\n");
        content.append("Total Deductions: ").append(totalDeductions != null ? totalDeductions.toString() : "0.00").append("\n\n");
        content.append("Please login to the employee portal to view and download your payslip.\n\n");
        content.append("Thank you,\nPayroll Department");
        
        return content.toString();
    }

    /**
     * Process email queue
     */
    public static Map<String, Object> processEmailQueue(DispatchContext dctx, Map<String, ?> context) {
        Delegator delegator = dctx.getDelegator();
        Map<String, Object> result = ServiceUtil.returnSuccess();
        
        try {
            int processedCount = 0;
            int failedCount = 0;
            
            // Get pending emails
            List<GenericValue> pendingEmails = EntityQuery.use(delegator)
                    .from("PayrollEmailQueue")
                    .where("statusId", "PENDING")
                    .queryList();
            
            for (GenericValue email : pendingEmails) {
                try {
                    // In real implementation, integrate with OFBiz's email service
                    // For demo, we'll simulate email sending
                    String toAddress = email.getString("toAddress");
                    
                    // Simulate email sending
                    Debug.logInfo("Sending email to: " + toAddress, module);
                    
                    // Update status to sent
                    email.set("statusId", "SENT");
                    email.set("sentDate", new java.sql.Timestamp(System.currentTimeMillis()));
                    delegator.store(email);
                    
                    processedCount++;
                    
                    // Small delay to simulate processing
                    try {
                        Thread.sleep(100);
                    } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                        Debug.logWarning("Email processing interrupted", module);
                    }
                    
                } catch (Exception e) {
                    try {
                        email.set("statusId", "FAILED");
                        email.set("errorMessage", e.getMessage());
                        delegator.store(email);
                    } catch (GenericEntityException gee) {
                        Debug.logError(gee, "Failed to update email status for: " + email.getString("emailQueueId"), module);
                    }
                    failedCount++;
                    Debug.logError(e, "Failed to send email: " + email.getString("emailQueueId"), module);
                }
            }
            
            result.put("processedCount", processedCount);
            result.put("failedCount", failedCount);
            result.put("totalEmails", pendingEmails.size());
            
        } catch (Exception e) {
            Debug.logError(e, "Error processing email queue", module);
            return ServiceUtil.returnError("Email queue processing failed: " + e.getMessage());
        }
        
        return result;
    }

    /**
     * Send payroll completion notification to HR
     */
    public static Map<String, Object> sendPayrollCompletionNotification(DispatchContext dctx, Map<String, ?> context) {
        Delegator delegator = dctx.getDelegator();
        Map<String, Object> result = ServiceUtil.returnSuccess();
        
        try {
            String payrollIterationId = (String) context.get("payrollIterationId");
            
            // Get payroll iteration details
            GenericValue payrollIteration = EntityQuery.use(delegator)
                    .from("EnhancedPayrollIteration")
                    .where("payrollIterationId", payrollIterationId)
                    .queryOne();
            
            if (payrollIteration != null) {
                // Create notification for HR
                String emailQueueId = delegator.getNextSeqId("PayrollEmailQueue");
                
                GenericValue emailQueue = delegator.makeValue("PayrollEmailQueue");
                emailQueue.set("emailQueueId", emailQueueId);
                emailQueue.set("payrollIterationId", payrollIterationId);
                emailQueue.set("emailType", "PAYROLL_COMPLETION");
                emailQueue.set("toAddress", "hr@company.com");
                emailQueue.set("subject", "Payroll Processing Completed - " + payrollIterationId);
                emailQueue.set("content", generateCompletionEmailContent(payrollIteration));
                emailQueue.set("statusId", "PENDING");
                emailQueue.set("createdDate", new java.sql.Timestamp(System.currentTimeMillis()));
                
                delegator.create(emailQueue);
                
                result.put("emailQueueId", emailQueueId);
                result.put("notificationType", "PAYROLL_COMPLETION");
            }
            
        } catch (Exception e) {
            Debug.logError(e, "Error sending payroll completion notification", module);
            return ServiceUtil.returnError("Payroll completion notification failed: " + e.getMessage());
        }
        
        return result;
    }

    /**
     * Generate completion email content
     */
    private static String generateCompletionEmailContent(GenericValue payrollIteration) {
        StringBuilder content = new StringBuilder();
        content.append("Payroll Processing Completed\n\n");
        content.append("Payroll Iteration: ").append(payrollIteration.getString("payrollIterationId")).append("\n");
        content.append("Total Employees: ").append(payrollIteration.get("totalEmployees")).append("\n");
        content.append("Total Gross Pay: ").append(payrollIteration.get("totalGrossPay")).append("\n");
        content.append("Total Net Pay: ").append(payrollIteration.get("totalNetPay")).append("\n");
        content.append("Status: ").append(payrollIteration.get("statusId")).append("\n\n");
        content.append("The payroll run has been successfully completed and is ready for review.");
        
        return content.toString();
    }
}