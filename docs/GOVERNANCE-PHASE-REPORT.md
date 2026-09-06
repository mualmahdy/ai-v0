# GOVERNANCE PHASE — Implementation Report
## Intelligence Governance & Sustainability: Capability & Evolution Radar + Token/Economic Budget Governance

- Repository: https://github.com/mualmahdy/ai-v0 (branch `main`)
- Base commit: `765207b` (docs: 2026 conversion report)
- Phase directive: "EXECUTION ORDER — AI-V0 Intelligence Governance & Sustainability Phase"
- Validation environment: JVM unit tests + Robolectric (Android SDK 36/36.1, AGP 9.1.1, Kotlin 2.2.10, Room 2.7.0)
- Test result: **245/245 tests pass, 0 failures, 0 skipped** (`:app:testDebugUnitTest`)
- Build result: **`assembleDebug` SUCCESSFUL** (full APK, 98 MB)

Status legend used below (per directive Section 19): `IMPLEMENTED / INTEGRATED / TESTED / ANDROID-VALIDATED / PRODUCTION-PROVEN / PARTIAL / BLOCKED / UNKNOWN`

> **Honest scope note:** "ANDROID-VALIDATED" below means *Robolectric + Room-in-memory validated on the JVM with Android framework stubs*. **No physical Android device or emulator was available in this environment** — no foreground-execution, process-death-on-device, or provisioning-download validation on real hardware was performed. Nothing in this phase is claimed PRODUCTION-PROVEN.

---

## A. Files / modules changed

### New files (18)
| File | Layer | Purpose |
|---|---|---|
| `domain/core/radar/CapabilityRadarModels.kt` | domain | Track A models: OperationalCapabilityState (9 values), CapabilityEvidence, dimensions, health, trend, gaps, changes, recommendations, snapshots, declarations |
| `domain/core/budget/EconomicModels.kt` | domain | Track B models: TokenUsageRecord, TokenQuota, BillingClass, MoneyAmount, PricingEntry (+scope/precedence), UsageCostRecord, BudgetScope (9 levels), BudgetPolicy/Allocation, RateLimitStatus, CostEstimate, gate request/result |
| `domain/ports/radar/CapabilityRadarPersistencePort.kt` | domain | Radar persistence boundary |
| `domain/ports/budget/BudgetPersistencePorts.kt` | domain | Pricing / CostLedger / BudgetAllocation ports |
| `infrastructure/persistence/entities/GovernanceEntities.kt` | infra | 7 Room v10 entities |
| `infrastructure/persistence/dao/GovernanceDaos.kt` | infra | 7 DAOs with scope-aggregation SQL |
| `infrastructure/persistence/radar/RoomCapabilityRadarStore.kt` | infra | Radar port implementation (JSON encoding private to adapter) |
| `infrastructure/persistence/budget/RoomEconomicStore.kt` | infra | Economic ports implementation |
| `application/radar/CapabilityRadarService.kt` | application | The radar: derivation pipeline, event ingestion, gaps, changes, recommendations, decision-engine check surface |
| `application/budget/EconomicGovernanceService.kt` | application | Economic facade: authorize (budget+rate), accountUsage (ledger), pricing resolution, estimation, budget management |
| `application/budget/RateLimitGovernor.kt` | application | RPM/TPM sliding windows + 429 backoff gate |
| `presentation/ui/screens/GovernanceObservatoryScreen.kt` | presentation | The observatory (LAST layer) |
| `test/.../testing/GovernanceTestFakes.kt` | test | In-memory port fakes mirroring Room semantics |
| `test/.../budget/EconomicGovernanceServiceTest.kt` | test | 19 budget-track tests |
| `test/.../radar/CapabilityRadarServiceTest.kt` | test | 16 radar-track tests |
| `test/.../DecisionEconomicGateTest.kt` | test | 5 decision-engine gate tests |
| `test/.../AgentOrchestratorGovernanceIntegrationTest.kt` | test | 4 orchestrator integration tests |
| `test/.../governance/GovernancePersistenceTest.kt` + `GovernanceMigrationTest.kt` | test | Room v10 round-trip + raw-SQL MIGRATION_9_TO_10 validation |

### Modified files (17)
`ExecutionEvents.kt` (3 new event types + honest UsageBudgetUpdate semantics), `LlmModels.kt` (TokenUsage cached/total/estimated), `AppDatabase.kt` (v9→v10, 7 entities+DAOs+MIGRATION_9_TO_10), `DecisionService.kt` (capability + economic gates in evaluate), `AgentOrchestrator.kt` (quota gate, security ceiling, accounting, workspace stamping, live consumedTokens), `ExecutionService.kt` (usage attribution, budget enrichment, rate-limit recording), `TelemetryService.kt` (workspace attribution, new events), `RoomTelemetryRepository.kt` (2 aggregation bug fixes), `RoomVectorStoreAdapter.kt` (workspace isolation fix), `ProviderControlPlaneService.kt` (radar evidence sink + pricing publisher), `GeminiLlmAdapter.kt`, `OpenAiCompatibleLlmAdapter.kt`, `OpenAiCompatibleAdapter.kt` (honest usage; no fabricated budget), `AppContainer.kt` (full DI wiring + bootstrap), `MainViewModel.kt` + `UiState.kt` + `MainAppScreen.kt` (observatory wiring + navigation), `StudioComponents.kt` (new event rows), `TaskModels.kt` + `ResilienceModels.kt` (migration annotations), `gradle.properties` (removed machine-specific `org.gradle.java.home` portability fix).

---

## B. New domain concepts

**Track A — Capability & Evolution Radar** (state: **IMPLEMENTED / INTEGRATED / TESTED**; UI **TESTED** via compile+snapshot-less unit paths):
`OperationalCapabilityState{UNKNOWN,PLANNED,AVAILABLE,PARTIAL,DEGRADED,BLOCKED,FAILED,DISABLED,DEPRECATED}`, `CapabilityEvaluationDimensions` (12 nullable evaluation axes: declared/implemented/configured/provisioned/dependencyAvailable/runtimeAvailable/runtimeValidated/resourceUsable/policyAllowed/healthConfirmed/uiExposed/evidenceFresh), `CapabilityEvidence` (14 attribution fields, 14 EvidenceSource values matching real emission sites), `EvidenceOutcome`, `CapabilityHealth` + `CapabilityTrend`, `CapabilityGap` (12 GapReasons), `CapabilityChangeRecord` (11 ChangeTypes = evolution detection), `RadarRecommendation` (10 types, 4 priorities), `RadarSnapshot`, `RadarCapabilityCheck` (decision-engine surface).

**Track B — Economic governance** (state: **IMPLEMENTED / INTEGRATED / TESTED**): `TokenUsageRecord` (input/output/cached/provider-total + isEstimate), `TokenQuota` (execution limits, distinct from money), `BillingClass{FREE,PAID,TRIAL,CREDIT,LOCAL,UNKNOWN}` (LOCAL ≠ FREE enforced), `MoneyAmount` (Long micro-units, EXPLICIT currency, null=UNKNOWN), `PricingEntry` (scope PROVIDER/SERVICE/MODEL with MODEL>PROVIDER precedence, versioning, effective-time windows, provenance), `UsageCostRecord` (ledger row with full attribution + applied pricing snapshot), `BudgetScopeType` (SYSTEM→PROVIDER→SERVICE→MODEL→AGENT→WORKSPACE→TASK→EXECUTION→STEP), `BudgetPolicy` (HARD_LIMIT / SOFT_LIMIT / AUTO_DOWNGRADE / AUTO_LOCAL_FALLBACK / REQUIRE_APPROVAL), `RateLimitStatus` (RPM/TPM independent of budget), `CostEstimate` (UNKNOWN when impossible), `EconomicAuthorizationRequest/Result`.

---

## C. New persistence entities / tables (Room v9 → v10, purely additive)

`capability_evidence` (append-only, 3 indices) · `radar_capability_states` (PK capabilityKey+workspaceId with `__global__` sentinel) · `capability_changes` · `radar_recommendations` (dismissable) · `pricing_entries` (deterministic ids, effective windows) · `cost_ledger_entries` (5 scope indices, NULL cost = UNKNOWN) · `budget_allocations` (PK scopeType+scopeId, policy CSV). Migration validated against raw SQLite (existing v9 data preserved, DDL insertable) — **TESTED (Robolectric)**.

---

## D. New services

- `CapabilityRadarService` — derivation pipeline (declaration registry → registry facts → evidence → state → changes → gaps → recommendations → persisted snapshot), event-driven from the **same execution event bus** telemetry uses (no second bus), control-plane evidence via late-bound sink, `checkCapability()` for the decision engine, `deriveSnapshot()` conflated.
- `EconomicGovernanceService` — `authorize()` (pre-execution budget+rate gate over hierarchical scopes), `accountUsageSuspend()` (usage→pricing resolution→cost ledger→telemetry→RPM/TPM window), `estimateCost()` (history-average based, honest UNKNOWN), `resolvePricing()` (MODEL>PROVIDER precedence), `setAllocation/budgetStatusFor/allBudgetStatuses`.
- `RateLimitGovernor` — per-scope sliding windows, 429 backoff (`blockedUntilEpochMs`), limits from configuration only (never invented).

---

## E. Decision Engine changes — status: IMPLEMENTED / INTEGRATED / TESTED

`DecisionService.evaluate()` now enforces the directive's ordering:
1. **Permission/Policy FIRST** — existing `enforceGovernance` (offline policy, tool security) unchanged; a budget allow can never override a security deny (verified by test).
2. **Capability gate** (`applyCapabilityGate`) — radar-derived state; BLOCKED/FAILED → explicit REPLAN with `CAPABILITY_*` reason (no silent execution).
3. **Economic gate** (`applyEconomicGate`) — `authorize()` verdict mapping: DENIED→REPLAN(BUDGET_DENIED); APPROVAL_REQUIRED→ASK_USER (never silent spend); DOWNGRADE/LOCAL_FALLBACK→REPLAN with preferLocal; WARNED/ALLOWED→proceed. Proven to prevent paid provider calls BEFORE execution (test asserts provider call count stays 0).

---

## F. AgentOrchestrator changes — IMPLEMENTED / INTEGRATED / TESTED

- **Pre-execution token quota gate** (TASK scope execution limit): paid action types stop once `accumulatedTokens >= tokenLimit` (BudgetGateDecision + Degraded events; loop breaks) — the legacy "budget never stops anything" defect is closed.
- **Security session ceiling wired**: `validateTokenBudget` (previously dead, zero callers) now called per gated action with an honest per-step estimate (4096 tokens, documented heuristic) — security ordering preserved.
- **Post-execution accounting**: `execResult.usageDetail` → `accountUsageSuspend` → persistent ledger → real `CostRecorded` event (cost UNKNOWN stays UNKNOWN on the bus).
- **Live budget consumption**: `currentTask.budget.consumedTokens` updated every step (legacy: never incremented — delegation carve-outs always saw the full 30000).
- **Workspace stamping**: active workspace flows into task parameters → decision context, economic gate, ledger, evidence.
- Bounded execution/recovery/checkpoint behavior untouched (no second orchestration path).

---

## G. Provider / resource / model changes — IMPLEMENTED / INTEGRATED / PARTIAL

- All three LLM adapters now report **measured usage only**: prompt/completion + **cached tokens + provider total** where published (Gemini `cachedContentTokenCount/totalTokenCount`; OpenAI `prompt_tokens_details.cached_tokens/total_tokens`); heuristic chars/4 substitutions are flagged `isEstimatedUsage=true`.
- The fabricated `remainingBudgetTokens = 30000 - consumed` per-call value is **gone** (REMAINING_UNKNOWN sentinel at the adapter; real task-budget-derived value computed once in ExecutionService).
- `ProviderControlPlaneService` emits radar evidence on materialize/validate/disable/discover (late-bound sink, no second bus) and publishes MODEL-scope `PricingEntry`s from offerings that carry prices (provenance-tagged; price-less offerings publish NOTHING — billing class stays UNKNOWN).
- PARTIAL: `ProviderDescriptor.rateLimitRequestsPerMin` remains unpopulated (see N); discovery pricing depends on provider-side price publication.

---

## H. Workspace-scoping changes — IMPLEMENTED / TESTED

- **Memory leak fixed**: `RoomVectorStoreAdapter` previously wrote `workspaceId=null` and retrieved globally (`getAllActiveMemories`) — agent loops in workspace B read workspace A's memories. Now writes and reads are scoped by the wired active-workspace provider.
- Telemetry `MetricDimensions.workspaceId` populated on every metric row (previously always NULL).
- Radar states/evidence/recommendations, cost ledger, budget statuses: workspace-scoped by design and by query.
- Decision context receives the workspace via task parameters (previously always null on the orchestrator path).
- Known residual: tasks/decision-cases/Q-table remain app-global (pre-existing architecture; see O).

---

## I. Telemetry changes — IMPLEMENTED / TESTED

- New event types on the SAME bus: `BudgetGateDecision`, `CostRecorded`, `RateLimitEncountered` — handled in TelemetryService (counters, WARN audit on budget denial, COST_USD metric only for USD — no silent FX), and ingested by the radar as capability evidence (BUDGET_EVENT / RATE_LIMIT_EVENT sources).
- **Bug fixes**: (1) `dimensionSummaries()` previously filtered out TOKEN_USAGE and COST_USD rows → token/cost totals were structurally zero — fixed; (2) COST_USD divisor was 10,000 instead of 1,000,000 (micro-USD contract) — fixed.
- Provider attribution on TOKEN_USAGE metrics (previously the hard-coded string "unknown").
- Async write path preserved (telemetry never blocks the runtime).

---

## J. UI changes — IMPLEMENTED (backend-truth only)

`GovernanceObservatoryScreen` (nav: "المزيد" → مرصد الحوكمة والاستدامة):
- **Radar section**: per-capability state chips (color-coded), health/trend/evidence-count/last-evidence, rationale, evidence-based recommendations (dismissible, persisted), detected changes timeline.
- **Budget section**: workspace allocation summary (allocated/consumed/remaining/utilization with UNKNOWN rendered as UNKNOWN), workspace token total, budget allocation editor (USD → HARD_LIMIT + AUTO_LOCAL_FALLBACK policy, enforced by the decision gate), recent ledger entries (tokens, cost with UNKNOWN display, billing class labels, ESTIMATED vs ACTUAL colors, provider/model attribution).
- Studio timeline rows for the new events; TokenBudgetGauge now displays REAL enriched values.
- No fabricated or assumed data anywhere: empty states say so.

---

## K. Existing tokenBudget migration — audit table (directive Section 9)

| Legacy concept | Location | Classification | Migration outcome |
|---|---|---|---|
| `DecisionState.remainingTokenBudget` (feature idx 8) | DecisionModels/CaseBase/CbrMdpEngine | planning signal | Kept as token-quota signal fed from TaskBudget; not a monetary budget |
| `TaskBudget.tokenLimit` | TaskModels | execution limit (token QUOTA) | **Now enforced** (orchestrator pre-execution gate) |
| `TaskBudget.consumedTokens` | TaskModels/ExecutionService | quota tracking (never updated) | **Fixed**: live per-step update; delegation carve-out now real |
| `TaskBudget.maxCostEstimatedUsd` | TaskModels | monetary hint | Deprecated; authority → BudgetAllocation (never silently reinterpreted) |
| `AgentBudget.maxTokens/usedTokens` | AgentModels | declared agent quota | Declared quota kept; LIVE agent-level enforcement via ledger AGENT-scope sums in the economic gate (in-memory field not mutated — see O) |
| `UsageBudgetUpdate.remainingBudgetTokens` (hardcoded 30000 ×3 adapters) | adapters | fabricated UI display | **Fixed**: adapters emit measured usage + REMAINING_UNKNOWN; ExecutionService enriches with real task-budget remaining |
| `SessionEntity.totalTokensConsumed` + `recordSessionTokens` | Daos | obsolete (dead DAO) | Documented known debt; sessionId does not reach the execution path (see O) |
| `AgentLifecycleService.evaluateBudget` | application/agent | dead enforcement hook | Superseded by the economic gate + quota gate (same semantics: BLOCK/THROTTLE) — kept for its tests, documented, NOT double-wired (no duplicated parallel concept) |
| `SecurityGuardService.validateTokenBudget` | application/security | dead security ceiling | **Wired** as the pre-budget security ceiling in the orchestrator (ordering: security BEFORE budget) |
| `ResourceQuota` / `QuotaUsage` / `QuotaAction` | ResilienceModels | dead models | Deprecated in place with migration notes; concepts owned by BudgetAllocation/RateLimitGovernor/TaskBudget |
| `RagPipelineService.maxTokenBudget` (dead param) | application/rag | obsolete param | Documented known debt (v2 RagIntelligenceService enforces budgets correctly) |
| `WorkspaceContextModels.APPROACHING_BUDGET` | workspace context | dead suggestion kind | Documented known debt (governance screen surfaces budget state instead) |
| `ModelDescriptor.estimatedCostPer1kTokensUsd` | ModelModels | read-never pricing hint | Bridged where offerings publish prices → PricingEntry (MODEL scope); field itself documented legacy |
| `TokenUsage.estimatedCostUsd` | LlmModels | never-set display hint | Documented legacy; authoritative cost lives in the cost ledger |
| `DecisionAction.estimatedCost` constants (0.002 etc.) | DecisionService | demo values feeding MDP cost penalty | Left as scoring prior (MDP cost shaping), NOT presented as monetary truth anywhere; documented |

No token count was reinterpreted as a monetary budget anywhere in the migration.

---

## L. Tests added / modified — 245 total, 0 failed

New suites (44 tests):
- `EconomicGovernanceServiceTest` (19): pricing precedence MODEL>PROVIDER, expired versions, UNKNOWN-never-fabricated, billing classes (LOCAL≠FREE), exact integer micro cost math (incl. cached-token pricing), ESTIMATED vs ACTUAL vs UNKNOWN, hierarchical WORKSPACE/AGENT scopes, hard/soft limits, approval, downgrade, local-fallback, unknown-amount track-only ceilings, RPM window denial, TPM independence from token budgets, estimation (history-average + UNKNOWN).
- `CapabilityRadarServiceTest` (16): declaration coverage, PLANNED-vs-AVAILABLE honesty, PARTIAL for unprovisioned, DISABLED, policy BLOCKED (offline), dependency BLOCKED, DEGRADED from evidence, FAILED without prior success, RESTORED change detection, persistence across service recreation, workspace isolation, gap reason taxonomy, actionable recommendations, UI-integration gap, checkCapability semantics, ONNX provisioning dimension + transition change.
- `DecisionEconomicGateTest` (5): budget denial REPLAN + zero provider calls, approval→ASK_USER, capability FAILED→REPLAN, offline-security outranks budget allow, no-allocation proceeds.
- `AgentOrchestratorGovernanceIntegrationTest` (4): real execution → attributed ledger record (exact cost 15,000 micro), CostRecorded event truth, token-quota exhaustion gate, unpriced-provider UNKNOWN accounting.
- `GovernancePersistenceTest` (5, Robolectric+Room) + `GovernanceMigrationTest` (1, raw SQLite): round-trips preserving nulls/UNKNOWN, workspace isolation queries, scope aggregations, allocation lifecycle, v10 schema + migration data preservation.

All 201 pre-existing tests continue to pass (zero regressions; two latent aggregation bugs they could not see are now fixed).

---

## M. Android validation performed — honest status

| Validation | Status |
|---|---|
| JVM unit tests (fakes, real logic) | TESTED (245/245) |
| Robolectric + Room in-memory (persistence/restart simulation via service recreation) | TESTED |
| Raw-SQLite migration DDL + data preservation | TESTED |
| Full debug APK build (resources, manifest, dex) | TESTED (assembleDebug) |
| Physical device / emulator run | **NOT PERFORMED** — no device/emulator in this environment |
| Foreground execution, process-death on device, ONNX model download on device | **NOT PERFORMED** |

Per the truthfulness contract, runtime capability states in a real deployment will read PARTIAL/UNKNOWN until real evidence flows in; the Robolectric tests validate the derivation machinery, not device behavior.

---

## N. Remaining limitations

1. Device/emulator validation absent (see M).
2. Pricing data availability is provider-dependent: nothing is priced by default — costs stay UNKNOWN until discovery/user config publishes prices (by design; never guessed).
3. Rate-limit limits (RPM/TPM numbers) are not auto-discovered — `configureLimits` must be fed by provider config/discovery; windows track usage and honor 429 backoffs regardless.
4. AUTO_DOWNGRADE / AUTO_LOCAL_FALLBACK currently surface as REPLAN-with-reason; the re-decided loop must pick the cheaper/local candidate from the existing candidate space (a direct candidate substitution engine is future work).
5. Estimation uses a 3:1 input:output split labeled as estimate (confidence ≤ 0.5); no provider-side pre-execution quote API exists.
6. Currency: ledger is multi-currency-capable, but telemetry COST_USD normalization only records USD (explicit, no silent FX).

---

## O. Known technical debt (explicit)

- `SessionEntity.totalTokensConsumed` / `recordSessionTokens` still dead (sessionId not propagated into the execution path).
- `AgentBudget.usedTokens/inFlightTokens` not mutated in memory; agent-level enforcement reads the persistent ledger instead (AgentDefinition is reconstructed per launch — persisting mutable agent budget state would duplicate ledger authority).
- `CircuitBreakerService`, `PolicyVersionService`, `ProviderRoutingService` remain dormant (pre-existing; routing's pricing inputs now exist via PricingEntry, making activation a cheap follow-up).
- RadarEvolutionScreen (ecosystem feed) legacy defects (fabricated bootstrap items, RSS dedupe) were NOT in this phase's scope and remain as documented in the prior audit; the new radar does not depend on them.
- Tasks / decision cases / Q-table remain app-global (workspace column absent) — cross-workspace isolation covers RAG, memory, workflows, telemetry, radar, and economics.
- WorkspaceContextEngine budget suggestions (`APPROACHING_BUDGET`) unwired.
- `RagPipelineService.maxTokenBudget` dead parameter remains (v1 path).

---

## P. Capabilities still below operational readiness (radar-derived, honest)

- `VISION`, `SHELL_EXECUTION`, `SYSTEM_EXECUTION`: PLANNED (no production implementation — declared, not implemented).
- `SEARCH`, `INTEGRATION_SYNC`: BLOCKED under OFFLINE policy by design; PARTIAL until an online search resource is provisioned/validated.
- `EMBEDDING`: PARTIAL until the ONNX semantic model is provisioned on device (lexical fallback continues, honestly labeled).
- All capabilities: UNKNOWN health until real execution evidence accumulates in the deployment environment.

## Q. Budget / pricing information still UNKNOWN

- All provider billing classes default to UNKNOWN (no pricing published by default).
- Gemini bootstrap, multi-source search, local embedding: no pricing entries seeded; LOCAL compute cost intentionally non-monetized (recorded as UNKNOWN, never zero).
- Model-specific pricing only exists where discovery published it; effective-time versioning is enforced but no pricing-update poller exists.

## R. Radar gaps still unresolved

- Ecosystem-feed radar (`IntelligenceRadarPipeline`) → operational radar bridge not built (REGISTERED evolution stage still does not materialize resources).
- No scheduled/background radar refresh (WorkManager absent); re-derivation is event-driven + on-demand.
- Agent attribution on evidence rows is null at emission time (DecisionRecord carries provider/resource identity; agent linkage via task parameters only).
- UI-exposure declaration map is hand-maintained (honest but static).

## S. Recommended next phase

1. **Device validation program**: instrumented tests on a real device for persistence/restart, foreground execution, ONNX provisioning, budget accounting end-to-end — converts TESTED → ANDROID-VALIDATED.
2. **Direct candidate substitution** for AUTO_DOWNGRADE / AUTO_LOCAL_FALLBACK (economic gate returns the substitute resource instead of REPLAN).
3. **Rate-limit configuration ingestion** from provider metadata/discovery into the governor.
4. **Close the ecosystem-radar loop**: EvolutionCandidate REGISTERED → control-plane materialization; fix the legacy feed's RSS dedupe and remove fabricated bootstrap items.
5. **Activate dormant services** with the new foundations: CircuitBreaker (resource health), ProviderRouting (pricing-aware selection via PricingEntry), PolicyVersion promotion of budget/radar policy snapshots.
6. Workspace column for tasks/decision-cases if cross-workspace task isolation becomes a requirement.
