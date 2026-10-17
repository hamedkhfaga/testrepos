Phase 6K - Backend Closure & Enterprise Hardening

Rebuilt files:
- LoanRepairApprovalServices.java
- LoanRepairExecutionOrchestratorServices.java
- LoanReconciliationPersistenceServices.java
- services.xml
- entitymodel.xml

Added services:
- closeDuplicateLoanRepairProposalsV1
- persistLoanRepairExecutionAuditV1
- enqueueLoanRepairRetryV1

Added entities:
- LoanRepairExecutionAudit
- LoanRepairRetryQueue

Notes:
- Core 3-transaction accounting model is unchanged.
- Duplicate cleanup rejects stale active proposals and preserves audit.
- Execution orchestrator now attempts durable audit persistence and retry queue persistence.
- Run entity load/update for entitymodel.xml before using the new persistence services.
