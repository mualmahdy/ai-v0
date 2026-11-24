# Economic Governance Roadmap & Implementation Record

> **AUTHORITATIVE RECORD** — economic-governance implementation state on `main`.
> Baselines: Stage 1A accepted at `2eec12d`→`d2d7e54`; Stage 2 implemented on top of `d2d7e54e20b6de5931c87c5e8369f17fd58e7cc9` and accepted (pushed as `0004e1b`); Stage 2B (financial integrity, pricing consistency, workspace-aware budget management) implemented on top of `0004e1b0a97cca540fcc6359f1115aafcba69786` — see §9.
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
| **DEBT-1** | Expiry state vs lazy expiry | `BudgetCoreDaos.kt`: `mutableHoldsForReason` selects `ACTIVE/PARTIALLY_SETTLED` by status; `BudgetLedgerService.settleHold` validated the hold's mutable status only. | **RESOLVED in Stage 2B**: `settleHold` and `releaseHold` now apply TRUE-expiry validation — a hold whose TTL has passed behaves as EXPIRED regardless of whether the sweep (`markExpired`) re-stamped its stored status. The same late actual lands identically with and without the sweep (both post WITHOUT the hold, exactly once, under the active period). Evidence: `Stage2BPeriodLifecycleAcceptanceTest.late actual lands identically with and without the expiry sweep - DEBT-1`. The sweep is now pure display bookkeeping. | **RESOLVED (Stage 2B)** — historical description retained | closed |
| **DEBT-2** | Idempotent authorization replay after expiry | `BudgetLedgerService.authorizeAndReserve` replays by `byIdempotencyKey(periodId, key)` **regardless of the hold's expiry**; `HoldKeys` grants a fresh identity per `(step, attempt)` so a NEW attempt never replays, but a repeated authorization of the SAME expired triple replays the expired reservation. | A retry of the same `(executionId, stepIndex, attempt)` after TTL expiry receives a "replayed" reservation that no longer suppresses availability. Documented current semantics: replay-after-expiry returns the original hold id with `replayed=true`; the reservation is NOT renewed in place. Deliberate policy: a re-dispatch after expiry must carry a new attempt identity. Stage 2B left the identity model untouched by design (the Stage 2B order: "do not lightly change the identity model"). | OPEN — documented; do not change casually | Stage 2C (with the expiry semantics follow-up) |
| **DEBT-3** | Dispatch-state granularity | `AgentOrchestrator.kt:1447`: `dispatchStarted = true` is set immediately before `ExecutionService.executeAction` — which still performs resource resolution, offline checks, circuit-breaker checks and pre-dispatch authorization before the actual provider call. | A cancellation during pre-provider phases is treated as DISPATCHED (conservative): the reservation is retained under the TTL even though no provider request was sent. Conservative direction is correct (no fabricated release); precision is deferred. | OPEN (conservative by design) | Stage 3A (execution instrumentation) |
| **DEBT-4** | Workspace identity parity | Decide-time: `DecisionService.applyEconomicGate` derives `workspaceId` from `context.task.input.parameters["workspaceId"]`. Pre-dispatch LLM gate: `ExecutionService.executeLlmStep` derives it from the pinned `ExecutionScope` (`currentWorkspaceId()`, line 352 — rejects `"unattributed"`). | The two gates of ONE billable step may derive different workspace ids if a task's input parameter disagrees with the pinned execution scope (not reproduced in tests; both P0-02 pinning and the workspace bootstrap normally keep them equal). Divergence would make the pre-dispatch gate resolve a different policy scope than the decide gate. | OPEN — verify with a production-path integration test | Stage 3A (with DEBT-5) |
| **DEBT-5** | End-to-end lifecycle coverage | `Stage1AHoldLifecycleAcceptanceTest` (and the Stage 2 suite) construct `EconomicAuthorizationRequest`/`UsageAccountingInput` directly over the real Room-backed core — they do not drive `DecisionService → AgentOrchestrator → ExecutionService → provider boundary → accounting`. | The production chain's wiring (identity plumbing, dispatch-state derivation, workspace pinning) is covered by focused unit tests of each layer but not by ONE end-to-end test. | OPEN | Stage 3A (production-path integration harness) |
| **DEBT-6** | Late actual-cost period attribution | `HoldSettlementService.postActualWithoutHold` → `BudgetLedgerService.postUsageActual` resolves the ACTIVE budget period **at posting time**; a hold that belonged to an earlier period whose actual arrives after a new period became active settles against the NEW period (`settleHold` posts under `hold.budgetPeriodId` when the hold survives, but `postActualWithoutHold` posts under the currently-active period). | **RESOLVED in Stage 2B — the contract is now NAMED and tested**: a late actual settles against the period of its SURVIVING hold when one exists; with no surviving hold it posts under the CURRENTLY-ACTIVE period of the governing scope at post time. Financial entries are never moved between periods after the fact (append-only ledger). Evidence: `Stage2BPeriodLifecycleAcceptanceTest.late actual with a surviving hold attributes to the hold period - DEBT-6` and `.late actual with a rolled-over period attributes per the documented owner contract - DEBT-6`. | **RESOLVED (Stage 2B, documented + tested)** — historical description retained | closed |

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

## 9. Stage 2B — Financial Integrity, Pricing Consistency & Workspace-Aware Budget Management

**Status: IMPLEMENTED** (on top of `0004e1b`; NOT pushed — delivered for review). Stage 2 remains accepted and is not repeated; every Stage 2 service, UI surface and acceptance test is preserved (Stage 1A suite 16/16 and Stage 2 suite 12/12 re-run green, files unchanged).

### 9.1 Workstream A — Currency consistency across authorization and settlement (CRITICAL)

**The ONE budget-currency rule (now enforced end-to-end):** the budget currency is the ACTIVE POLICY's currency. An amount enters hold reservation or ledger posting only in the budget currency — converted with the recorded, effective-time-valid FX rule (`CurrencyConversionService`) at the transaction instant. Identity conversion needs no rule. No covering rule → NO hold / NO POSTED entry, NEVER an assumed 1:1, and the original amount + currency is preserved on a **DRAFT ledger entry** (posting state `DRAFT`, zero balance effect by the equation's POSTED filter) instead of being silently discarded.

- **Authorization** (`HoldBasedBudgetGate`, + optional `fx`): the estimate's currency is checked against the active policy's currency BEFORE reserving. Cross-currency estimates convert with the rule (the hold reserves the CONVERTED amount in the policy currency — the authorization reason embeds the rule id + rate for traceability). Unconvertible → honest ALLOWED with NO hold, explicitly stating the ceiling is not enforceable for that interaction (the I-3 shape).
- **Settlement** (`HoldSettlementService`): the actual converts into the hold's currency with the rule covering the settlement instant; POSTED consumption entries carry the FX attribution columns (`original_amount_precise` — the exact pre-rounded decimal, `original_currency`, `conversion_rule_id`). Without a rule: the cost is recorded as DRAFT evidence under the hold's period, the hold is KEPT (conservative, I-10 family), and a retried settlement after a rule is registered converts and settles normally (the DRAFT row remains as the mismatch history).
- **No-hold posting** (`BudgetLedgerService.postUsageActual`, + optional `fx` ctor): `amountCurrency` declares the denomination; a mismatch with the ACTIVE policy converts with the rule (attribution on the entry) or records DRAFT evidence. Overages (I-11) post in the budget currency. Converted amounts that round to zero micro record as DRAFT (a real cost is never fabricated "free").
- **Wiring**: `AppContainer.budgetLedgerService` now receives `fx = currencyConversionService` (the same service the settlement uses — one conversion authority). FX rules remain windowed/directional/fail-closed; **no production FX-rule management UI exists yet** (rules are registered programmatically / by future operator tooling — see §9.8).
- Evidence: `Stage2BCurrencyConsistencyAcceptanceTest` (9 tests): USD/USD, EUR/EUR, USD-budget+EUR-pricing with a rule (converted reservation 2,063 + converted settlement 990 + attribution `fx_EUR_USD_0`), without a rule (no hold + DRAFT evidence + idempotent), rule not covering the transaction time, estimate/actual currency mismatch (independent conversion), late actual under a changed rule (settlement-instant rule + historical reproducibility of both windows), hold-kept + retry-after-rule-registration recovery, and cross-currency overage posting.

### 9.2 Workstream B — Period lifecycle & accounting-state consistency

**The accepted lifecycle contract** (documented on `BudgetPolicyManagementService`):

1. A period that reaches its end timestamp stops being ACTIVE for every decision (window filter) — its DERIVED lifecycle (`BudgetDashboardService.PeriodLifecycle`) is `ENDED`, or `ENDED_WITH_OUTSTANDING_HOLDS` while mutable TTL-unexpired holds remain.
2. A period superseded by a new policy version derives `SUPERSEDED` — it can never receive new holds (its policy is RETIRED) but keeps owning its outstanding holds and late settlements.
3. Rollover (new period under the SAME immutable version) treats the ended window exactly like (1).
4. The stored status `CLOSED` is an EXPLICIT terminal marker, written only when the window ended AND no mutable unexpired hold remains (`BudgetPeriodDao.closeExpiredSettledPeriods`, invoked from `createPolicyVersion`, from rollover, and from the failure-isolated bootstrap sweep next to the hold-expiry pass). It is bookkeeping convergence, never a precondition for honesty.
5. Late-actual attribution is the DEBT-6 contract (surviving hold's period / currently-active period at post time; never moved after the fact).

- The dashboard row now carries the stored period status, the owning policy's status, the accounting scope (type + platform object id) and the outstanding-hold count — the inputs of the derived lifecycle and the workspace scoping.
- Evidence: `Stage2BPeriodLifecycleAcceptanceTest` (8 tests): ENDED/CLOSED derivation + sweep convergence, ENDED_WITH_OUTSTANDING_HOLDS (never closed while obligations exist), SUPERSEDED derivation + closure after the window, rollover closes the ended settled period, DEBT-1 sweep-invariance, DEBT-6 both attribution arms, and policy-field immutability across versions.

### 9.3 Workstream C — Pricing identity, append-only history & mirror recovery

- **Collision-resistant identity**: `PricingManagementService.userEntryId` now embeds a SHA-256 digest of the FULL material content (prices, currency, billing class, window, provenance). Two materially different writes under the SAME injected clock timestamp can no longer collide-and-replace (the old id derived identity fields + effectiveFrom only, and the `@Upsert` surface silently REPLACED the first row). Identical re-saves reproduce the same id and remain verified no-ops.
- **Honest partial failure**: a catalog-sync failure AFTER the pricing entry was persisted is reported (`RegisterOutcome.Registered.catalogSyncFailure`), never swallowed; the entry stands.
- **Defined recovery**: `resyncPricingCatalog()` (the idempotent mirror), any later successful save (the mirror processes every unrepresented entry), and `ensureSyncedAndResolve` on read. Historical `resolveAt` results stay stable — the mirror only APPENDS missing versions.
- Evidence: `Stage2BPricingIntegrityAcceptanceTest` (10 tests) — same-timestamp materially-different writes both survive, idempotent re-saves, ordered windows, simulated sync failure (entry persisted + catalog stale + honest outcome), retry heals (later save AND explicit resync), historical reproducibility before/after new versions, concurrent same-identity registration (exactly-once rows).

### 9.4 Workstream D — Pricing provenance & verification honesty

- **USER_CONFIG ≠ verified**: the catalog's `verificationStatus = UNVERIFIED` stamp is CORRECT and stays; nothing promotes USER_CONFIG to VERIFIED (only a future independently-verified pricing pipeline may). The UI badge now says **"مُدخل يدوياً — غير مُتحقق بشكل مستقل"** (user-entered — not independently verified) instead of the previous "موثّق يدوياً" ("manually verified").
- **Evidence reference** (new `UserPricingRequest.evidenceReference` + provenance grammar `USER_CONFIG[:note][|ref=…]` + a UI field): the URL/document the price was attested against — user-ATTESTED accuracy, explicitly distinct from independent verification, displayed under the entry.
- **Identity validation** (`PricingManagementService.PricingIdentityStatus` + AppContainer probe over the REAL provider registry): a pricing entry referencing an unknown provider or service carries an honest warning in the outcome and the UI — never a silent validity. The write stays possible (pre-registration); model ids are deliberately not existence-checked (models are dynamic; the KNOWN/UNKNOWN probe reports honest absence).
- Evidence: the WS-D tests in `Stage2BPricingIntegrityAcceptanceTest` (UNVERIFIED stamp + provenance chain + evidence reference; identity-probe verdicts; note-only change is the documented no-op).

### 9.5 Workstream E — Workspace-aware budget state & dashboard freshness

- `BudgetViewModel` accepts the **active workspace as a real stream** (`WorkspaceRuntimeService.activeWorkspace.map { it?.id }`, wired in `BudgetViewModelFactory`). On switch: authority + all workspace-specific projections re-bind (`flatMapLatest`), in-flight reads of the previous workspace are discarded (guarded before every state write — defense in depth beyond the cancellation), the stale selected period is cleared, and the null-workspace state renders an honest empty feed (never another workspace's periods).
- **Scoped feed**: `BudgetDashboardDao.periodBalancesForScopeFlow` filters periods by accounting scope (the Budget screen shows THE WORKSPACE's periods only — the documented product contract; global/provider views remain a future labeled surface). The unscoped `periodBalances(now)` stays for the legacy observatory/compat.
- **TTL freshness without DB mutation**: `BudgetDashboardService.periodBalancesForScope` re-binds its Room query with a FRESH clock value on a refresh signal (default: every 60 s) — an expired reservation stops counting as active WITHOUT any database write and WITHOUT ViewModel recreation. Table changes still emit immediately between ticks.
- Evidence: `BudgetViewModelWorkspaceTest` (7 tests) — switch A→B (authority never crosses, scoped feed, selection reset), null workspace, TTL advance without DB mutation (stored hold status untouched), rapid-switch inversion freedom; `BudgetScreenGovernanceFlowTest` exercises the switch through the actual screen.

### 9.6 Workstream F — Monetary input integrity

- `MoneyInput` (new): the single exact-decimal parser for operator-entered money. Rejects NaN/±Infinity/negatives/`+`-prefix/commas/non-numeric with explicit user-facing reasons; **at most 6 fractional digits** (the micro unit IS 10⁻⁶); supported range 0..10¹² whole units (micro fits a Long with headroom); exact `BigDecimal` arithmetic (`5.999999` stays 5,999,999 micro — no binary-float truncation); scientific notation accepted as exact decimal. Currency: exactly 3 ASCII letters, case-normalized, never silently defaulted.
- The editors submit raw STRINGS; the ViewModel validates and surfaces a **visible error** (`BudgetUiState.inputError`, rendered next to the offending editor with `budget_editor_validation` / `pricing_form_validation` tags) — never a silent no-op. Period length is validated in 1..3650 (previously silently coerced). The budget editor gained an explicit currency field (was hardcoded USD).
- Evidence: the WS-F tests in `BudgetViewModelWorkspaceTest` (invalid set writes nothing; zero is a real ceiling; the 10¹² maximum and 3650-day period accepted exactly; the pure-parser regression set).

### 9.7 Workstream G — Compose/UI verification of the actual product flows

`BudgetScreenGovernanceFlowTest` (7 Robolectric Compose tests over REAL Room + REAL production services, no mocks): configure a modern policy through the editor (real policy + period + authority card + lifecycle badge), edit the limit (immutable v2 in the same family), reopen a period after the window ends (rollover; ended period CLOSED), register a user-configured price (honest provenance + UNVERIFIED catalog stamp), switch the active workspace (authority + scoped feed + selection), the currency-mismatch honesty note, and invalid input producing a visible error while writing nothing. (Harness note: the in-memory DB callback must seed the Stage-2 `unit_definition`/`metric_definition` rows — `pricing_component` carries FKs onto them; the REAL migration seeds these.)

### 9.8 Stage 2B debt register (order §11)

| ID | Title | Source locations | Impact | Status / evidence | Intended resolution stage |
|----|-------|------------------|--------|-------------------|---------------------------|
| **2B-1** | Currency mismatch between cost estimate and budget authorization | `HoldBasedBudgetGate` (extracted `amountMicro` only, ignored the estimate's currency); `postUsageActual` (posted the raw amount as policy currency) | Cross-currency amounts could be reserved/posted as if 1:1; settlement's honest `CURRENCY_MISMATCH` no-op dropped real costs from the accounting record | **RESOLVED** — WS-A rule (§9.1); `Stage2BCurrencyConsistencyAcceptanceTest` (9 tests, stored holds/settlements/entries/balances) | closed |
| **2B-2** | Price identity collision / possible historical overwrite | `PricingManagementService.userEntryId` (identity + effectiveFrom only) × `PricingEntryDao.upsert` (@Upsert replaces) | Two materially different writes under one clock tick silently replaced the first | **RESOLVED** — content-digest identity; `Stage2BPricingIntegrityAcceptanceTest` test 1 | closed |
| **2B-3** | Pricing catalog mirror recovery after partial failure | `registerUserConfiguredPricing` (entry write then sync; failure propagated opaquely), `syncFromLegacyEntries` skip-by-source | Entry could stand with a stale mirror and no defined recovery | **RESOLVED** — honest `catalogSyncFailure` + `resyncPricingCatalog()` + next-save/read healing; tests 4–5 | closed |
| **2B-4** | User-configured vs independently verified pricing semantics | Catalog `verificationStatus=UNVERIFIED` × UI badge "موثّق يدوياً" | User-entered provenance was presented as verified | **RESOLVED** — honest badge + `evidenceReference` provenance grammar; WS-D tests | closed |
| **2B-5** | Provider/service/model identity validation | `registerUserConfiguredPricing` accepted any identity | An entry referencing a nonexistent identity appeared valid while never matching runtime requests | **RESOLVED (warning semantics)** — `PricingIdentityStatus` over the real registry, surfaced in outcome + UI; hard rejection deliberately not chosen (pre-registration); model existence intentionally un-checked (dynamic, honest UNKNOWN probe) | closed as designed |
| **2B-6** | Period status lifecycle after expiration and policy versioning | `createPolicyVersion` (retired versions, left periods OPEN); expired periods stored OPEN; dashboard rendered neither | Stale periods visually OPEN indefinitely; supersession invisible | **RESOLVED** — derived lifecycle + explicit closure when safe; `Stage2BPeriodLifecycleAcceptanceTest` (8 tests) | closed |
| **2B-7** | Workspace-aware budget dashboard refresh | `BudgetViewModel` (one-shot workspace provider, refresh-only); `periodBalancesFlow` (no scope filter) | Stale workspace authority; cross-scope period presentation; stale selected period | **RESOLVED** — workspace stream + scoped feed + stale-read guards + selection reset; `BudgetViewModelWorkspaceTest` + the Compose switch test | closed |
| **2B-8** | Dashboard TTL freshness | `periodBalances(nowEpochMs())` bound once at VM construction | Expired reservations kept counting until recreation | **RESOLVED** — refresh-signal re-binding (60 s default, injectable signal); the TTL test advances the clock with zero DB writes | closed |
| **2B-9** | Monetary input precision and finite-value validation | `BudgetScreen`/`BudgetViewModel` Double parsing + `toLong()` | NaN/Infinity/range/precision defects; silent no-ops; silent period coercion | **RESOLVED** — `MoneyInput` exact-decimal parser + visible errors; WS-F regression set | closed |
| **2B-10** | UI automation and workspace-switching coverage | No Compose-level coverage of the Stage 2 product flows | Editor/authority/provenance/switching behavior unverified at the UI layer | **RESOLVED** — `BudgetScreenGovernanceFlowTest` (7 Compose tests, real Room, no mocks) | closed |
| **2B-11** | Current CI unit-test failure and its unresolved baseline discrepancy | `SearchIntelligenceServiceTest.rank for TEMPORAL intent weights recency highest` — `computeRecencyScore` decays with a 30-day half-life against WALL-CLOCK `ZonedDateTime.now()`; the fixture's "recent" item is dated 2026-09-05 | **Deterministic, calendar-driven failure — NOT flaky, NOT a Stage 2 regression.** The modeled crossover is 2026-11-18: the parent commit `d2d7e54`'s green Build workflow ran ~2026-11-16 (margin 0.0028 — genuinely green then); from 2026-11-18 every code version fails identically. Reproduced on a clean `d2d7e54` worktree AND on Stage 2 HEAD `0004e1b` (identical `ComparisonFailure`, 3/3 repeat runs). The Stage 2 report's "reproduces on the pristine parent baseline" is empirically true TODAY, but the inference "pre-existing at the parent's CI run" is wrong — the test ROVED with the calendar. Out of economic-governance scope (unrelated search behavior): NOT fixed here per the Stage 2B order; the search owner should pin the fixture to a relative date or an injected clock. | **OPEN — recorded with reproducible evidence; fix owned by the search component** | next search-intelligence iteration |

### 9.9 Stage 2B verification (delivery run, 2026-11-24)

| Command | Outcome |
|---|---|
| `./gradlew :app:compileDebugKotlin` | BUILD SUCCESSFUL |
| `./gradlew :app:compileDebugUnitTestKotlin` | BUILD SUCCESSFUL |
| Focused budget suites (`com.example.application.budget.*` + `BudgetViewModel*` + `BudgetScreenGovernanceFlowTest`) | **227 tests, 0 failures** — includes the UNCHANGED Stage 1A suite (16/16) and Stage 2 suite (12/12) |
| Full `:app:testDebugUnitTest` | see the delivery report — **1 pre-existing failure**: the calendar-dependent `SearchIntelligenceServiceTest` TEMPORAL test (2B-11), failing identically on the pristine parent baseline |
| `./gradlew :app:assembleDebug` | BUILD SUCCESSFUL (APK assembled) |
| `git diff --check` | clean |
| `connectedAndroidTest` | NOT RUN — no device/emulator in the sandbox (environmental blocker, not a pass) |

Schema: **no change** — all Stage 2B work rides the existing v28 schema (`DRAFT` posting state, FX attribution columns, `period_status` and provenance TEXT grammar were already present; the CHECK constraints admit them). No migration, no destructive conversion, no database reset.

### 9.10 Remaining limitations & next stages

1. **FX-rule management surface**: conversion rules exist and are honored, but there is NO operator UI to register them — until one ships, cross-currency estimates/actuals under policies in another currency take the honest unconvertible paths (no hold / DRAFT evidence). Multi-currency monetary enforcement is therefore NOT complete — by design it fails honestly.
2. **Stage 3A — production-path execution harness** (DEBT-3, DEBT-4, DEBT-5).
3. **Stage 2C** — the remaining DEBT-2 identity/replay semantics follow-up.
4. **Labeled global/provider budget views** — the workspace view deliberately excludes other scopes; a future labeled surface can expose them.
5. **2B-11** — the search test's calendar-dependent fixture (owner: search component).
