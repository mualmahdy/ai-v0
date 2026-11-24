# Economic Governance Roadmap & Implementation Record

> **AUTHORITATIVE RECORD** — economic-governance implementation state on `main`.
> Baselines: Stage 1A accepted at `2eec12d`→`d2d7e54`; Stage 2 implemented on top of `d2d7e54e20b6de5931c87c5e8369f17fd58e7cc9`.
> Older reports in `docs/` (AUDIT-2026-REPORT.md, GOVERNANCE-PHASE-REPORT.md, PHASE-1-GOVERNED-EXECUTION-REPORT.md,
> SLICE-6-CONTINUATION-STUDY.md, GAP-CLOSURE-REPORT.md, P0-CONVERGENCE-REPORT.md) are **HISTORICAL** — see
> §8 Reconciliation notes before quoting them.

---

## 1. Stage 1A — Reservation Identity & Hold Lifecycle

**Status: PARTIALLY CLOSED — ACCEPTED AS COMPLETED FOR THIS ITERATION.**

Delivered capabilities (locked by `Stage1AHoldLifecycleAcceptanceTest`, 16 tests, unchanged in Stage 2):

- Canonical billable-operation identity: `(executionId, stepIndex, attempt)` — `HoldKeys`.
- Finite reservation lifetime (`DEFAULT_HOLD_TTL_MS` = 24h), enforced lazily by the balance query.
- Legacy unbounded-hold remediation (`boundLegacyHolds` bootstrap sweep; non-destructive).
- Lifecycle accounting for billable operations without a usage report.
- Distinction between known zero cost (arm B: release) and unknown cost (arm C: conservative keep).
- Release of proven undispatched reservations (arm D).
- Conservative retention of dispatched operations with unknown actual cost (arm E).
- Late actual-cost handling (posts without the hold; real known consumption is never discarded).
- Idempotent settlement and release paths (unique idempotency indexes + replay detection F-1/F-2/F-3).
- Preservation of the legacy authorization path when no modern accounting policy covers the request.

## 2. Stage 1A Debt Register (carry-forward — recorded, NOT re-implemented)

| ID | Title | Evidence (source) | Impact | Status | Proposed resolution stage |
|----|-------|-------------------|--------|--------|---------------------------|
| **DEBT-1** | Expiry state vs lazy expiry | `BudgetCoreDaos.kt`: `mutableHoldsForReason` selects `ACTIVE/PARTIALLY_SETTLED` by status; `BudgetLedgerService.settleHold` validates the hold's mutable status; lazy balance evaluation can stop counting a hold after its expiry timestamp **before** the sweep (`markExpired`) changes its stored status. | The late-actual settlement path (`postActualWithoutHold` vs `settleHold`) can diverge depending on whether the expiry sweep already ran: an EXPIRED-status hold fails `MUTABLE_HOLD_STATUSES` and the actual is posted without a settlement row, while an un-swept but timestamp-expired hold still settles normally. Both paths record the actual exactly once (idempotency keys) — the divergence is bookkeeping shape, not double-counting — but the inconsistency should be unified. | OPEN (carry-forward) | Stage 2C (expiry/settlement consistency) |
| **DEBT-2** | Idempotent authorization replay after expiry | `BudgetLedgerService.authorizeAndReserve` replays by `byIdempotencyKey(periodId, key)` **regardless of the hold's expiry**; `HoldKeys` grants a fresh identity per `(step, attempt)` so a NEW attempt never replays, but a repeated authorization of the SAME expired triple replays the expired reservation. | A retry of the same `(executionId, stepIndex, attempt)` after TTL expiry receives a "replayed" reservation that no longer suppresses availability. Documented current semantics: replay-after-expiry returns the original hold id with `replayed=true`; the reservation is NOT renewed in place. Deliberate policy: a re-dispatch after expiry must carry a new attempt identity. | OPEN — documented; do not change casually | Stage 2C (with DEBT-1) |
| **DEBT-3** | Dispatch-state granularity | `AgentOrchestrator.kt:1447`: `dispatchStarted = true` is set immediately before `ExecutionService.executeAction` — which still performs resource resolution, offline checks, circuit-breaker checks and pre-dispatch authorization before the actual provider call. | A cancellation during pre-provider phases is treated as DISPATCHED (conservative): the reservation is retained under the TTL even though no provider request was sent. Conservative direction is correct (no fabricated release); precision is deferred. | OPEN (conservative by design) | Stage 3A (execution instrumentation) |
| **DEBT-4** | Workspace identity parity | Decide-time: `DecisionService.applyEconomicGate` derives `workspaceId` from `context.task.input.parameters["workspaceId"]`. Pre-dispatch LLM gate: `ExecutionService.executeLlmStep` derives it from the pinned `ExecutionScope` (`currentWorkspaceId()`, line 352 — rejects `"unattributed"`). | The two gates of ONE billable step may derive different workspace ids if a task's input parameter disagrees with the pinned execution scope (not reproduced in tests; both P0-02 pinning and the workspace bootstrap normally keep them equal). Divergence would make the pre-dispatch gate resolve a different policy scope than the decide gate. | OPEN — verify with a production-path integration test | Stage 2B/3A (with DEBT-5) |
| **DEBT-5** | End-to-end lifecycle coverage | `Stage1AHoldLifecycleAcceptanceTest` (and the Stage 2 suite) construct `EconomicAuthorizationRequest`/`UsageAccountingInput` directly over the real Room-backed core — they do not drive `DecisionService → AgentOrchestrator → ExecutionService → provider boundary → accounting`. | The production chain's wiring (identity plumbing, dispatch-state derivation, workspace pinning) is covered by focused unit tests of each layer but not by ONE end-to-end test. | OPEN | Stage 3A (production-path integration harness) |
| **DEBT-6** | Late actual-cost period attribution | `HoldSettlementService.postActualWithoutHold` → `BudgetLedgerService.postUsageActual` resolves the ACTIVE budget period **at posting time**; a hold that belonged to an earlier period whose actual arrives after a new period became active settles against the NEW period (`settleHold` posts under `hold.budgetPeriodId` when the hold survives, but `postActualWithoutHold` posts under the currently-active period). | Two paths attribute the same logical late-actual differently: surviving-hold settle attributes to the hold's period; no-hold posting attributes to the active period. This matches the Stage-2 code comment ("the active policy at post time") but the accounting contract should name ONE owner explicitly. No silent moves exist today (append-only ledger). | OPEN — document owner + test | Stage 2C |

## 3. Stage 2 — Economic Governance Activation (Budget Policy & Pricing Control Plane)

**Status: IMPLEMENTED** (on top of `d2d7e54`; verification evidence in §6).

### 3.1 Two budget authorities — resolved

| | Legacy path | Modern accounting path |
|---|---|---|
| Write surface | Governance screen editor → `GovernanceViewModel.setWorkspaceBudgetAllocationUsd` → `ManageWorkspaceBudgetUseCase` → `EconomicGovernanceService.setAllocation` → `budget_allocations` | Budget screen editor → `BudgetViewModel.configureWorkspaceBudget` → **`BudgetPolicyManagementService`** (new) → `BudgetLedgerService.createBudget` / `createPolicyVersion` / period insert → `budget_policy` / `budget_period` |
| Read surface | Governance budget card (`workspaceBudgetSummary`) | Budget screen authority card (`workspaceAuthority`) + `BudgetDashboardService` flows |
| Enforcement | Legacy comparison in `authorize()` | Hold-based gate (`HoldBasedBudgetGate`) when an active accounting policy covers the request |

**Management semantics (implemented & tested):**

1. **First policy + first period** — `configureWorkspaceBudget` creates the scope descriptor, the v1 policy family and its first OPEN period. Nothing is created implicitly: no default monetary budget, no seeded authority — only an explicit user configuration.
2. **Material edit → immutable new version** — a changed (limit, currency, overage) creates the next policy version in the SAME family (PDM-G05); the previous version is RETIRED with its financial fields untouched; the new version owns a NEW open period.
3. **Period rollover** — when the governing window has ended and the material fields are unchanged, the CURRENT policy version gets a new OPEN period row (a new period is not a policy edit).
4. **Representation** — period boundaries (`period_start/end_epoch_ms`), currency (explicit, per-policy), allocation limit (`limit_micro_units` per period), overage behavior (`HARD_LIMIT | SOFT_LIMIT | REQUIRE_APPROVAL`).
5. **UI reporting** — `BudgetPolicyManagementService.workspaceAuthority(workspaceId)` is the authoritative answer to "what governs this workspace right now": `MODERN_ACCOUNTING` (policy + covering period + live balance equation), `MODERN_PERIOD_ENDED`, `LEGACY_ALLOCATION`, or `NONE`. The Budget screen renders it as the authority card; the Governance screen shows a compatibility note.
6. **Repeated writes / refreshes** — verified no-ops (`ConfigureOutcome.Unchanged`): identical material parameters never create a second policy, version or period.

### 3.2 Legacy transition semantics (documented & tested)

- The legacy `budget_allocations` path remains **functional and unchanged** for workspaces that never configure a modern policy (acceptance test 7).
- Configuring a modern policy does NOT migrate, delete or reinterpret legacy allocations; the modern ledger starts its own period from configuration time. Historical legacy consumed amounts are NOT converted into modern period balances (no silent conversion semantics — acceptance test 8).
- While a modern policy is ACTIVE with a covering period, the modern path takes precedence; the legacy allocation becomes dormant, not deleted.
- **Expired modern period (new in Stage 2):** `HoldBasedBudgetGate` now denies honestly when a modern policy exists but no accounting period covers `now` — NEVER a silent fallback to the stale legacy allocation (acceptance test 8). Renewal = configuring again (opens a new period under the current immutable version).

### 3.3 Pricing management path (implemented & tested)

- **`PricingManagementService`** (new) is the explicit path for VERIFIED pricing evidence:
  - Identity: provider (+service, +model → MODEL scope; service → SERVICE; neither → PROVIDER).
  - Prices: input / output / cached-input, micro currency units per million tokens; **at least one required** (UNKNOWN is never "configured", never zero).
  - Currency: explicit field, stored as entered (EUR stays EUR — acceptance test 10); no silent USD assumption; no 1:1 conversion at settlement (existing I-18 fail-closed path).
  - Billing classification: explicit `BillingClass`.
  - Effective window: from/until; a materially changed price is a NEW entry (append-only history).
  - Provenance: `USER_CONFIG` (+ optional operator note) — user-entered evidence is clearly distinguishable from provider-discovered evidence in every surface.
  - Version history: every user entry is mirrored by the existing `PricingCatalogService.syncFromLegacyEntries` into `pricing_profile` / `pricing_profile_version` / `pricing_component` — reproducible historical resolution via `resolveAt(provider, service, model, atEpochMs)` (acceptance tests 9–10).
- **Discovery behavior preserved:** `registerOfferingPricing` still publishes NOTHING when input pricing is unknown; `/models` discovery gaps are not papered over.
- **UNKNOWN semantics preserved:** no invented prices for Gemini/OpenAI/Ollama; no price derived from a model name; LOCAL ≠ FREE (billing class, not a price); recorded transactions keep their applied-pricing snapshots.
- **I-3 preserved:** a monetary hard limit is not advertised as fully enforceable when pricing is unknown — the Budget screen states this limitation explicitly, and unknown-priced interactions proceed without a hold (actual lands at settlement).

### 3.4 Budget UI reflects the actual authority

- `BudgetScreen` (Stage 7 observatory) gained: the **authority card**, the **budget editor** (modern policy path), and **pricing management** (list + USER_CONFIG form + live KNOWN/UNKNOWN probe).
- The misleading empty-state text was corrected: the screen no longer claims the Governance allocation editor creates a modern accounting period (it never did); the Governance editor is now explicitly labeled "التخصيص القديم (legacy) — لا ينشئ فترة محاسبية حديثة".
- The Governance budget card shows which budget ACTUALLY governs (modern policy note / expired-period warning).
- All displayed values originate from the authoritative persistence/services (`BudgetPolicyManagementService.workspaceAuthority`, `BudgetLedgerService.availableForPeriod`, `BudgetDashboardService` Room flows, `PricingRepositoryPort.allPricing`).

### 3.5 Production integration & safe activation

- `ENABLE_HOLD_BASED_BUDGET_GATE` stays `true` (never disabled).
- No global default monetary budget; no seeded prices; no new hard limit imposed on existing users.
- A modern policy becomes active ONLY after explicit user configuration for the workspace scope.
- Preserved behaviors (all re-tested): no active modern policy → legacy path; UNKNOWN estimate → no hold (I-3); known positive actual → accounted; known zero → nothing fabricated; unknown after dispatch → conservative; actual above reservation → full amount recorded (I-11); currency mismatch without a conversion rule → honest no-op (I-18), never 1:1.

### 3.6 Production wiring (verified on `main` + Stage 2)

- `AppContainer.budgetPolicyManagementService` — Room DB + `BudgetLedgerService` + `RoomEconomicStore` (legacy view).
- `AppContainer.pricingManagementService` — `RoomEconomicStore` + `PricingCatalogService`.
- `BudgetViewModelFactory` passes both + `economicGovernanceService` + the active-workspace provider into `BudgetViewModel`.
- `GovernanceViewModelFactory` passes `budgetPolicyManagementService` into `GovernanceViewModel` (read-only projection).
- Existing production wiring (verified, NOT resurrected from old reports): `HoldSettlementService`, `CostBreakdownService` (via `EconomicGovernanceService.accountUsage`), `BudgetActualsFeed` (consumed by `AgentOrchestrator` + `DecisionService`), `PricingCatalogService` (via `CostBreakdownService`), `CurrencyConversionService` (via settlement + reconciliation).

## 4. Schema & migration status

**No schema change in Stage 2.** All work rides the existing v28 schema (`budget_policy`, `budget_period`, `budget_hold`, `budget_hold_settlement`, `budget_ledger_entry`, `pricing_entries`, `pricing_profile*`, `budget_allocations`). No migration, no destructive conversion, no database reset.

## 5. Test evidence (Stage 2)

`Stage2GovernanceActivationAcceptanceTest` — 12 tests, all passing, real in-memory Room DB with production triggers, real production services (`EconomicGovernanceService` → `HoldBasedBudgetGate` → `BudgetLedgerService`; `accountUsageSuspend` → `HoldSettlementService`; `BudgetPolicyManagementService`; `PricingManagementService` + `PricingCatalogService`; `BudgetDashboardService` flows; `RoomEconomicStore` as the single repository instance, exactly as AppContainer wires it):

1. Create through the management path → policy + period + scope descriptor + authority.
2. Edit → immutable new version in the same family (v1 RETIRED, fields frozen).
3. Modern budget visible through the real dashboard projection (balance equation over raw rows).
4. Explicitly configured modern policy selected by the production authorization path (hold under the configured period, canonical idempotency key).
5. Priced interaction: reservation + settle against actual usage (exact entry, remainder released, exact availability).
6. UNKNOWN pricing: no fabricated price, no fabricated monetary cost, no hold, honest UNKNOWN statuses.
7. Legacy workspace without a modern policy: legacy verdicts, zero holds, observing creates nothing.
8. Legacy data + modern policy: modern governs while active; expired period → honest DENY (no silent legacy fallback); renewal reopens a period.
9. Historical pricing resolution stable across price versions (catalog windows + engine precedence).
10. Currency + effective-window correctness (EUR stored; window end → UNKNOWN).
11. Repeated writes/refreshes → no duplicate policies, periods, versions or pricing entries.
12. Empty modern ledger honest: NONE ≠ unlimited ≠ zero-cost; zero-limit policy is a real ceiling.

Stage 1A suite (`Stage1AHoldLifecycleAcceptanceTest`): 16/16 green, file unchanged in Stage 2. Focused regression set (all `com.example.application.budget.*` suites + `BudgetViewModelTest` + `GovernanceViewModelTest`): 199 tests, 0 failures (182 budget + 17 ViewModel; one `GovernanceViewModelTest.rejectSensitiveAction` await timed out once under a parallel filter run, passes deterministically when re-run — unrelated to governance projection).

## 6. Verification commands & outcomes (Stage 2 run, 2026-11-24)

Executed in the delivery sandbox (Linux, JDK 21.0.13, Android SDK: platforms;android-36 + build-tools;36.0.0; sandbox-only mirror wiring: Aliyun proxies + locally seeded plugin markers because Maven Central throttled the sandbox IP with HTTP 429 and Aliyun carried two permanently-502 marker cache entries — the repo's own build files are untouched):

| Command | Outcome |
|---|---|
| `./gradlew :app:compileDebugKotlin` | BUILD SUCCESSFUL |
| `./gradlew :app:compileDebugUnitTestKotlin` | BUILD SUCCESSFUL (via `testDebugUnitTest` compile step) |
| `./gradlew :app:testDebugUnitTest` (full) | 1537 tests completed across 215 classes, **1 failed** — `SearchIntelligenceServiceTest.rank for TEMPORAL intent weights recency highest`, a PRE-EXISTING failure on the pristine baseline `d2d7e54` (reproduced with the Stage 2 worktree stashed), unrelated to economic governance and out of Stage 2 scope |
| `./gradlew :app:assembleDebug` | BUILD SUCCESSFUL (3m48s, APK assembled) |
| `git diff --check` | clean |
| `connectedAndroidTest` | NOT RUN — no device/emulator in the sandbox (adb lists none); environmental blocker, not a pass |

## 7. Remaining limitations & next stages

1. **Stage 2B — UI-automation + workspace lifecycle:** instrumented/UI tests for the editor flows; multi-workspace authority switching.
2. **Stage 2C — settlement/period consistency:** resolve DEBT-1, DEBT-2, DEBT-6 (one documented owner for late actuals; unified expiry-state semantics).
3. **Stage 3A — production-path integration harness:** DEBT-5 (DecisionService → AgentOrchestrator → ExecutionService → provider boundary → accounting), which also closes DEBT-4 verification and hardens DEBT-3 with a precise provider-call probe.
4. **Pricing coverage honesty:** a provider-registry join so the Budget screen can list KNOWN vs UNKNOWN per registered provider (today the screen lists evidence entries + the live probe per identity).
5. **Legacy → modern migration tooling** (deliberate, user-initiated): currently the transition is per-workspace explicit configuration; no bulk migration is offered by design.

## 8. Reconciliation notes (older reports vs current `main`)

- "BudgetDashboardService sat instantiated in the container with no consumer" — **STALE** since Stage 7: `BudgetViewModel`/`BudgetScreen` consume it (see `BudgetViewModelFactory`).
- "PricingCatalogService is dormant" — **STALE**: it is production-wired through `CostBreakdownService` (`costBreakdownRecorder` in `EconomicGovernanceService.accountUsage`) and, since Stage 2, through `PricingManagementService`.
- `RationalizationService` / `CurrentStateService` — **not wired** in AppContainer on current `main` (Stage-5 services with no production wiring; do not resurrect without a consumer).
- `BudgetForecastService`, `ReconciliationService`, `ProviderBillingService` — instantiated in AppContainer but without a user-facing consumer surface (management/import UIs remain future stages).
- `EntitlementService` — production-wired via `BudgetActualsFeed`.
- Any older statement claiming the Governance budget editor creates accounting periods was **never true** and is now corrected in the UI text.
