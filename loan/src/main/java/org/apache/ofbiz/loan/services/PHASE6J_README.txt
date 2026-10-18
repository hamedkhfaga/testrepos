Phase 6J - Orphan Payment Link Repair + Proposal Deduplication

Actual changes in this rebuilt package:

1. LoanPaymentRepairServices.java
   - Added linkOrphanLoanRepaymentPaymentV1.
   - Validates repayment/payment amount, parties, currency.
   - Requires PaymentApplication by default.
   - Requires full accepted 3-transaction accounting model by default:
     PAYMENT_ACCTG_TRANS + INCOMING_PAYMENT + PAYMENT_APPL.
   - Only links PartyLoanRepayment.paymentId to existing Payment; does not create finance records.
   - Added linkDuplicatePaymentIfSafe=Y path in backfillLoanRepaymentPaymentV1.

2. LoanRepairApprovalServices.java
   - Prevents duplicate active proposals for same organizationPartyId + loanId + loanRepaymentId + issueTypeEnumId.
   - Passes linkDuplicatePaymentIfSafe=Y into approved repair execution context.

3. LoanRepairExecutionOrchestratorServices.java
   - Passes linkDuplicatePaymentIfSafe=Y, requirePaymentApplication=Y, requireAccounting=Y to proposal execution.

4. services.xml
   - Adds linkOrphanLoanRepaymentPaymentV1.
   - Adds linkDuplicatePaymentIfSafe input to backfillLoanRepaymentPaymentV1.

Install:
Copy safe-name files to their live paths, run gradlew clean compileJava, then restart OFBiz.
