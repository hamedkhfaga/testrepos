Phase 6H - Autonomous Repair Orchestration & Governance Enforcement

Files included:
1. LoanAutonomousRepairOrchestrationServices_phase6h.java
   Copy to:
   D:\ofbiz24\ofbiz\plugins\loan\src\main\java\org\apache\ofbiz\loan\services\LoanAutonomousRepairOrchestrationServices.java

2. services_phase6h.xml
   Copy/merge to:
   D:\ofbiz24\ofbiz\plugins\loan\servicedef\services.xml

New service:
POST /rest/services/runAutonomousLoanRepairOrchestrationV1

Behavior:
- Discovery: runs reconciliation automation.
- Governance: evaluates issue repairability.
- AUTO_REPAIR_ELIGIBLE: delegates to repairLoanFinancialIntegrityChunkedV1.
- HIGH_RISK/manual approval: queues LoanRepairProposal via createLoanRepairProposalV1.
- FAILED_REPAIR: returned in retryQueue.
- POST_REPAIR: reruns reconciliation or persists final reconciliation runs.
- FINAL: returns dashboard metrics from getLoanRepairAutomationDashboardV1.

Important:
- Default dryRun = Y.
- Does not alter the validated repair/accounting services.
- Preserves accepted 3-transaction model: PAYMENT_ACCTG_TRANS + INCOMING_PAYMENT + PAYMENT_APPL.
