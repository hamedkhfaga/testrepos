Phase 6F.2 Chunked Batch Executor Patch

Rebuilt files only:
1) LoanFinancialRepairOrchestratorServices_phase6f2.java
   Copy to:
   D:\ofbiz24\ofbiz\plugins\loan\src\main\java\org\apache\ofbiz\loan\services\LoanFinancialRepairOrchestratorServices.java

2) services_phase6f2.xml
   Merge/copy to:
   D:\ofbiz24\ofbiz\plugins\loan\servicedef\services.xml

New service:
repairLoanFinancialIntegrityChunkedV1

Purpose:
- repayment-level chunk repair
- avoids parent long transaction timeout by declaring use-transaction="false"
- processes maxRepayments rows per call
- calls payment repair -> payment application repair -> accounting repair -> amount consistency repair
- safe dryRun default is Y

Recommended compile:
cd D:\ofbiz24\ofbiz
.\gradlew compileJava

Then restart OFBiz.
