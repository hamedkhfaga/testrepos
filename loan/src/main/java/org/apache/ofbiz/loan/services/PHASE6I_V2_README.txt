Phase 6I v2 patch

Files:
- LoanRepairExecutionOrchestratorServices_phase6i_v2.java
- services_phase6i_v2.xml

Fixes:
1. Passes proposal-level fields into dry-run and execution calls:
   organizationPartyId, loanId, loanScheduleId, loanRepaymentId, paymentId, acctgTransId.
2. Scopes chunked repair to maxRepayments=1 by default for proposal execution.
3. Accepts both maxBatchSize and maxProposals in Java.
4. Allows repairProposalId-only execution by deriving organizationPartyId from LoanRepairProposal.

Install:
1. Copy LoanRepairExecutionOrchestratorServices_phase6i_v2.java to:
   D:\ofbiz24\ofbiz\plugins\loan\src\main\java\org\apache\ofbiz\loan\services\LoanRepairExecutionOrchestratorServices.java
2. Replace/update the existing Phase 6I service XML definitions using services_phase6i_v2.xml.
3. Run: .\gradlew clean compileJava
4. Restart OFBiz.
