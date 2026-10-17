# Assets enhancement implementation status

Updated: 15 October 2026.

Target: the existing `/assets` UI and OFBiz `assets` plugin. Asset ERP remains an unchanged alternative. This file records the implementation so work can be resumed without relying on chat history.

## First implementation increment

Implemented in source:

- Replaced the New Asset Request menu's direct fixed-asset form with a dedicated request form.
- Added Java `assetsCreateAssetRequest`: validates references, amount and organization access; resolves active approval rules; previews the required route; calls native OFBiz `createRequirement`; creates asset-specific detail, approval tasks and request audit event in the service transaction.
- Approval routing uses effective-dated, organization/type/request/budget/currency rules. Amount limits are configuration data, not UI decisions. Rules with monetary thresholds must identify a currency. All matching rules are required; sequence numbers establish ordered stages.
- Added retry protection with a unique submission key and payload fingerprint. Replaying an identical committed request returns its existing Requirement; reusing a key for different input fails. The database unique index protects concurrent duplicate submissions; a racing submission can fail and must be retried with its original key.
- Added an administrator approval-rule editor and Java `assetsSaveApprovalRule` validation.
- Connected Approval Workbench to the existing actionable approval-task page. Existing Java approval logic validates the assigned actor/sequence and synchronizes native Requirement status on final approval/rejection.
- Added `AssetsOrganizationAccess` and administrator grant/update services. Non-administrators need effective organization access for the new request/setup/balance endpoints in addition to module permissions. This does not retrofit organization security into every existing generic finder or older workflow.
- Implemented missing Java `assetsGetAccumDep` using active book-specific subledger values. Its optional GL comparison nets debit reversals and credit depreciation on linked posted schedules and matching accumulation accounts. It explicitly excludes unrelated/opening journals and is not a full GL reconciliation.
- Asset list/profile no longer substitute zero depreciation or purchase cost for missing book balances. Errors and unavailable balances are visible.
- Corrected invalid Assets service permission declarations to schema-supported permission checks; added the Assets approval permission to the plugin's permission seed.
- Added English/Arabic messages and labels, 27 isolated demo records, six policy tests and five database integration tests.

All newly authored backend business implementation is Java. Configuration administration also uses OFBiz's built-in entity-auto service engine. No Groovy implementation was added.

## Verification actually performed

| Check | Result |
|---|---|
| Compile two new Java service classes and both test classes against existing local OFBiz classes/cached dependencies | Passed using JDK 17 |
| Java policy tests: thresholds, currency isolation, exception routing, asset-type routing, signed reversal and invalid accounting direction | 6 passed |
| Assets entity model against local OFBiz XSD | Passed |
| Assets service model against local OFBiz XSD with local service-group dependency | Passed |
| New sample records against current native/plugin entity field names and ID lengths | 27 rows passed |
| Java method/descriptor contract resolution for the four new Java services | Passed |
| Parse all Assets JSX plus API and shared labels; verify new-page labels exist | 145 files passed |
| Bundle the full UI directly with the installed native esbuild | Passed; existing duplicate-label/SFA warnings |
| Re-bundle the four changed/new main Assets pages after final UI edits | Passed; existing duplicate-label warnings |
| Compare protected Asset ERP backend/UI/API hashes | 95 files unchanged |
| Normal Vite production build | Blocked before app compilation by Windows `spawn EPERM` in Vite's real-path/config loader |
| Local OFBiz HTTPS reachability after network permission | HTTP 200 |
| Signed-in browser verification | Browser discovery timed out twice |
| Five OFBiz database integration tests | Supplied and compiled; NOT executed |
| Load new services/schema/demo into the running OFBiz process | NOT performed |

The direct esbuild checks do not replace Vite's production build or live functional tests. Changes are installed in the source folders, not accepted as a live production deployment. Source backups are retained in this chat's workspace. Existing unrelated UI changes were left alone.

## Sample data and activation

New file in the plugin: `data/AssetsRequestWorkflowDemoData.xml`, registered as a demo reader resource.

Do not reload all OFBiz demo data. The new file contains pending examples and is intended for a test environment; reimporting it after approvals would reset those demo decisions. It does not clear tables, change passwords, create payments or post journals.

Activation order:

1. Compile/restart OFBiz through the established local development process so the new Java classes, entity model, unique index and service definitions are active. No core or Asset ERP source changes are required.
2. Import only `plugins/assets/data/AssetsSecurityPermissionSeedData.xml` if the newly declared approval permission is needed. Preserve existing group assignments and organization policy.
3. Import only `plugins/assets/data/AssetsRequestWorkflowDemoData.xml` using OFBiz's normal XML import facility. Native common seeds (SAR currency, MANAGER and INTERNAL_ORGANIZATIO roles, REQ_CREATED status) and the Assets type/status seeds must already be loaded.
4. Use an existing Assets administrator account for the demonstration. The file creates no login credentials. For ordinary users, grant their required module permissions and organization access using the administrator services.
5. Run the registered Assets test suite against an isolated test delegator after loading the same fixtures there. Confirm the new tests are actually discovered and executed before reporting integration success.

| Example | IDs / expected behavior |
|---|---|
| Demo organization | `ASDEMO_ORG` |
| Demo asset type | `ASDEMO_LAPTOP` |
| Native requirement type | `ASSETS_ACQUIRE` |
| Budget-secured request | `ASDEMO_REQ_01`, amount SAR 4,200; one pending task `ASDEMO_DEC_01` |
| Unbudgeted request | `ASDEMO_REQ_02`, amount SAR 6,200; tasks `ASDEMO_DEC_02A` then `ASDEMO_DEC_02B` |
| First approver | `ASDEMO_APPR1`, MANAGER role |
| Additional exception approver | `ASDEMO_APPR2`, MANAGER role |
| Rule setup | `ASDEMO_BASE` plus `ASDEMO_EXCEPTION` |
| Separate balance inquiry asset | `ASDEMO_ASSET_01`; independent of the two requests |
| Primary corporate book | `ASDEMO_CORP`: accumulated depreciation SAR 1,200; NBV SAR 3,000 |
| Memo book | `ASDEMO_MEMO`: accumulated depreciation SAR 1,500; NBV SAR 2,700 |

The balance examples are opening-balance inquiry fixtures, with no GL mappings or depreciation formula assigned. They deliberately are not ready for financial posting. Configure accounting and formula prerequisites through the proper services before testing posting; do not mistake the opening values for posted GL balances.

## Manual acceptance scenarios after activation

1. Open Assets → Approval Rules and inspect the two demo rules. They belong only to the demo organization; do not route real company requests to demo approvers.
2. Open Assets → New Asset Request. Select the demo organization, asset type, requirement type, New request type, a priority, SAR and a positive estimate. Select Budget Secured and preview: expect one approval task and no records created by preview.
3. Change to Unbudgeted and preview: expect two ordered tasks. Submit: expect a native Requirement and asset-specific request/tasks, with no new FixedAsset or journal for that request.
4. Open Assets → Approval Workbench. A suitably authorized administrator can act on the static demo tasks; an ordinary approver needs their login linked to the assigned party. Processing the second unbudgeted task before the first must fail.
5. Approve the first task, then the second: the extension becomes ASSETS_APPROVED and the native Requirement becomes REQ_APPROVED. Repeating a processed decision must fail.
6. On a fresh service-created test request, reject with comments: expect rejection and cancellation of remaining pending tasks. The same fixture cannot demonstrate both approved and rejected outcomes without a controlled reset.
7. Select USD while only the SAR demo rules match: expect a no-route error and no created Requirement.
8. Repeat an identical committed request with its original submissionKey: expect the same requirementId. Change its description while retaining the key: expect a conflict.
9. Call `assetsGetAccumDep` for the balance inquiry asset: expect the corporate values by default. Supply `assetsBookId=ASDEMO_MEMO`: expect the memo values, with no GL movement presented as a memo balance.
10. Validate permission denial and organization isolation using an ordinary test user before accepting the new endpoints.

## Implementation roadmap — not yet complete

| Stage | Scope | Current state |
|---|---|---|
| 1 | Request creation, routing, rule administration, missing balance API and test foundations | Source implemented; activation and integration acceptance pending |
| 2 | Request revision/cancellation, organization-wide security retrofit, CAPEX availability/reservation/commitment controls | Not implemented in this increment |
| 3 | Native RFQ/quote comparison/PO conversion, receipt/inspection and asset capitalization/in-service | Existing models/worklists; orchestration and acceptance outstanding |
| 4 | Custody acknowledgement/return, location/department transfers, tags and physical verification | Existing worklists; operational completion and ERP feature adaptation outstanding |
| 5 | Preventive/corrective maintenance, work orders, meters, inventory issuance and cost treatment | Existing native links/worklists; completion outstanding |
| 6 | Financial posting hardening, complete book/period/currency reconciliation, revaluation, impairment, estimate changes, components | Existing partial capabilities; full acceptance outstanding |
| 7 | Lease/ROU/liability schedules, rentals, insurance/claims, warranty, compliance, fleet and booking | Existing data models/worklists; completion outstanding |
| 8 | Disposal approval, sale/scrap/donation, native invoice/payment trace, balanced accounting and retirement controls | Existing data models/worklists; completion outstanding |
| 9 | CIP, grants, ARO, held-for-sale, intercompany and intangible/license requirements | Requires business-policy-specific implementation and testing |
| 10 | Full import/export, alerts, jobs, reports, audit and international/localization acceptance | Partial existing capability; completion outstanding |

Every stage needs suitable sample scenarios, negative tests, permission tests, native-document links and financial reconciliation where relevant. Asset ERP must remain unchanged and available throughout. Retirement or migration of Asset ERP remains outside this implementation until explicitly agreed.

## Boundaries

- No Asset ERP source or sample data was changed or loaded.
- No OFBiz source outside `plugins/assets` was changed.
- UI changes are confined to Assets pages/API and Assets-prefixed entries in the shared label file.
- No core service was replaced. New orchestration exists only for Assets-specific gaps; native createRequirement and the existing native requirement-update path remain authoritative.
- No production journals were created, no data was imported into the live server, and no global loadAll command was run.
- This increment does not establish worldwide workflow coverage or accounting compliance.

## 2026-10-16 live implementation update (supersedes earlier activation notes)

The current Assets-only increment has been activated and tested against local OFBiz. New Java services bridge approved native product requirements to native purchasing, receipt, inspection, stock capitalization and placement in service. GL mapping now validates actual organization account assignments and precedence. Depreciation has locking, duplicate/overlap checks, snapshot-based latest-first reversal and straight-line final-period rounding correction. New source records and journals are real TEST ONLY local accounting transactions; earlier statements in this document that no live records were created apply only to the earlier increment.

Verified request-to-depreciation case: requirement10080, PO10100, receipt10060, invoice10060, assetAT26-REQ-SL, capitalization10310, depreciation10313 after test reversal10312. Cost2000USD, accumulated50, NBV1950. Serialized case: requirement10090, PO10110, receipt/invoice10070, assetAT26-REQ-SERIAL, serialAT26-SERIAL-LAP-01, capitalization10316 for1800USD. Both inventory quantities/accounting quantities are zero after conversion. The serialized asset remains pending placement in service for acceptance testing.

17 policy/calculation tests and service/entity XSD validation pass. Assets UI targeted bundles, label checks and identifier checks pass. The full worldwide lifecycle is NOT complete: operational custody/maintenance/verification/disposal orchestration, broader financial policy, scalability and ordinary-user/concurrency acceptance remain outstanding. Latest final UI acceptance is blocked on signing in to a fresh verification tab after the prior browser automation connection stopped accepting control. No Asset ERP or OFBiz core source was changed by this increment. Existing differences from an older Asset ERP hash list are not a valid current-turn baseline and were left untouched.

See the user-facing 2026-10-16 implementation and test guide and live evidence in the current Codex task outputs. Preserve the sample records as test evidence; do not reload completed fixtures as if the workflow had run.
