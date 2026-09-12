# AI-V0 ARCHITECTURAL REPAIR — DELIVERY REPORT

**Artifact:** `ai-v0-repair-order-implementation.patch` (496 KB, 75 files, +8,749/−164)
**Target branch:** `main` (verified against pristine HEAD `b5b259d`)
**Verification:** `git apply --check` CLEAN against `b5b259d`; full test suite + assembleDebug executed successfully.

---

## 1. Implementation Summary

Both user-facing failures were fixed at the ROOT (no hiding, no superficial patches), and the approved workspace/project/resource portability capabilities were implemented on a unified scope architecture.

### Failure A — startup "No project is associated with the current workspace" (ROOT CAUSE FIX)
The error came from `MainViewModel.currentProjectIdOrInform()` reading a UiState.activeProject that was only populated by a fire-and-forget bootstrap, with **no state machine, no transactional workspace+project creation, and no stale-reference reconciliation** (a deleted/archived/foreign `lastActiveProjectId` produced the error forever). Fixed by:

- **`WorkspaceBootstrapOrchestrator`** (new): explicit state machine `BOOTSTRAPPING → WORKSPACE_READY → PROJECT_RESOLVED → CONTEXT_READY → READY` with explicit failure states `NO_WORKSPACE | PROJECT_NOT_FOUND | CONTEXT_REPAIR_REQUIRED | BOOTSTRAP_FAILED` (GAP-25, Design Closure 2026: the never-emitted `PROJECT_CORRUPT` constant was removed — a corrupt `rootPath` is repaired, not fatal). Workspace + required project creation is ONE Room transaction (a half-created workspace can no longer exist); active workspace AND project restored deterministically; stale `lastActiveProjectId` reconciled to the workspace's most-recent ACTIVE OWNED project, else explicit `PROJECT_NOT_FOUND` (never "project 1"/first-project fallback); idempotent re-runs; corrupt `rootPath` repaired to the canonical sandbox path; multiple-active-workspace corruption repaired atomically.
- `WorkspaceRuntimeService` delegates bootstrap to the state machine and exposes `bootstrapState` as the single startup truth; UI gates project-dependent features on `READY`; failure messages are actionable and phase-aware instead of the recurring raw error.

### Failure B — universal "AUTONOMY_POLICY_BLOCKED" (ROOT CAUSE FIX)
The decision engine ranked sensitive tool actions for EVERY task (including Quick Chat, whose agent has no TOOL_EXECUTION), and governance blocked them POST-HOC, killing the task. The pipeline was inverted. Fixed by re-ordering the pipeline to the mandated shape:

```
Intent → Task Contract → Required Capabilities → Admissible Action Set → Decision/Ranking → Governance → Execution
```

- **`TaskContractResolver` + `TaskContracts`** (new): intent classification (QUICK_CHAT / CHAT / COMPLEX / CODING / SEARCH / KNOWLEDGE / FILE / WORKFLOW). Quick Chat is a legitimate generation-only mode; ordinary chat structurally cannot nominate sensitive tools.
- **`DecisionService.applyAdmissibleActionSet`** (new filter): the action space is constrained by (a) task semantics (contract), (b) the assigned agent's capability binding (no TOOL_EXECUTION → no tool actions), (c) policy (ASSISTED removes the sensitive family). Control actions are always admissible (never an empty space).
- **Non-terminal autonomy gate**: a governor block records a NEGATIVE observation and re-decides (bounded); after repeated blocks it escalates to a DURABLE consent request (`approvalRequester` → `HumanApprovalGate`) instead of killing the task. The approval surface is now reachable (`pendingApprovals`/approve/reject wired through ViewModel) — previously `approve`/`reject`/`grant` had ZERO production callers.
- **Policy authority**: effective autonomy policy is resolved ONCE at launch (pinned workspace policy read from persistence BY ID — never the currently-active StateFlow); `WorkspaceRuntimeService.updateAutonomyPolicy` persists the authoritative column (the UI toggle was previously decorative); `MainViewModel.setAutonomyPolicy` routes to the authoritative layer; task constraints sourced from workspace policy; hierarchy = workspace ⊔ task where a child may restrict, never elevate.

## 2. Architectural Changes

| Area | Change |
|---|---|
| **Scope/context (§4/§6/§8)** | `ScopeType`, `ResourceScope`, `AccessContext`, `ScopeGrant`, `ScopeRules` (ONE place: child→ancestor-shared allowed; sibling→private denied; parent→child-private denied unless explicitly shared; explicit grants widen), `ContextResolverService` |
| **Multi-project (§5/§27)** | `ProjectRuntimeService` (create/select/rename/archive/restore/trash/delete/purge, transition table, duplicate-name refusal, audit); WorkspaceRuntimeService stays a workspace-scope runtime |
| **Isolation (§6)** | Scoped DAO queries everywhere (sibling-invisible by SQL); `ToolLifecycleService` migrated from UNSCOPED `permission_grants` lookup to `lookupScoped`; grants carry explicit workspace scope |
| **Artifacts (§7/§8/§29)** | `ArtifactService` unified library (existence ≠ knowledge-indexing; scope/owner/type/mime/size/hash/source/classification/indexing-state); `SandboxProjectFileStore` (containment-checked read/write/copy/move/delete/list/stat/stream/hash + Zip-entry validation) |
| **Transfers (§9–§14)** | `FileTransferService` (staging + ATOMIC PROMOTION; Zip-Slip/traversal/oversize/hash-corruption guards; stream-based so SAF plugs in at the UI), `ProjectPackageService` (.aiv0project versioned manifest format, validation pipeline Read→Validate→Resolve→Stage→Import→Verify→Commit, deterministic ID remapping, conflict policies ASK/RENAME/REPLACE/MERGE/SKIP/CANCEL — REPLACE never default, compatibility report, verified clone/move), `SessionExportService` (canonical JSON → TXT/MD/JSON, workspace-authorized, redacting) |
| **RAG (§15)** | `projectId` scoping + `RetrievalScopeMode` (PROJECT_ONLY / PROJECT_AND_WORKSPACE / WORKSPACE_ONLY / APPLICATION); retrieval is workspace-scoped with stale-working-set repair; eviction honestly labeled RAM-only (persistent knowledge retained; bounds enforced after load — thrash loop fixed); O(N²) BM25 tokenization fixed; `RoomVectorStoreAdapter.upsert` workspace attribution fixed (was writing invisible rows) |
| **Provider truth (§16)** | Discovery no longer fabricates STREAMING/REASONING/VISION for arbitrary endpoints (only service-type-implied capabilities claimed); `ResourceLocality` enum ON_DEVICE/LAN/EMULATOR_HOST/REMOTE (10.0.2.2 explicitly NOT on-device) |
| **Network (§17)** | Discovery egress bypass CLOSED (production path now egress-gated, fail-closed when unwired); Ollama embedding adapter egress wiring fixed (was always failing closed); `network_security_config.xml` permits cleartext ONLY for localhost/127.0.0.1/10.0.2.2/169.254.0.2 (narrowest safe config — no global weakening) |
| **Policy/decision (§18/§19/§31)** | `PolicyVersionService` hang fixed (runBlocking + infinite Room Flow collect → direct `byId` DAO lookups); `CbrMdpEngine` honestly documented as a heuristic decision/ranking layer + `ACTION_SPACE_VERSION` binding (stale learned Q-rows invalidated deterministically); `DecisionExplanationService` (structured facts, no chain-of-thought) |
| **Readiness/deps/snapshots/repair (§24–§28)** | `ProjectReadinessService` (READY/DEGRADED/BLOCKED derived from authoritative state), dependency graph (REQUIRED/OPTIONAL × RESOLVED/MISSING/INCOMPATIBLE), snapshots (create/list/delete + pre-destructive protection), `RepairCenterService` (DETECT→EXPLAIN→REPAIR→VERIFY, deterministic auditable repairs) |
| **Audit (§30)** | `AuditTrailService` (WHO/WHAT/WHEN/SOURCE SCOPE/TARGET SCOPE/RESOURCE/POLICY/RESULT; secret-redacting; never stores secrets) |
| **Execution (§21)** | Task-loop wall-clock timeout enforcement (explicit TASK_TIMEOUT state, resumable); scope-lifecycle tests preserved |

## 3. Database/Schema Changes (v15 → v16, complete migration chain preserved)

- `projects` += `lifecycleState` (default 'ACTIVE'; `isArchived=1` backfills to 'ARCHIVED'), `archivedAtEpochMs`, `trashedAtEpochMs` (+lifecycleState index)
- `knowledge_documents`, `chat_sessions`, `tasks` += nullable queryable `projectId` (+indices); `tasks.projectId` backfilled deterministically from the pinned canonical execution context JSON (both `,` and `}` terminators handled)
- `mdp_q_values` += `actionSpaceVersion` (legacy rows NULL = invalidated honestly at engine load)
- NEW tables: `artifacts` (unified library), `project_dependencies` (composite PK), `project_snapshots`, `audit_events`
- Purely additive — existing data survives (proven by migration tests); `WorkspaceDao.updateAutonomyPolicy`/`autonomyPolicyFor`, `PolicyVersionDao.byId`, scoped project/knowledge/session/task DAO methods, `ServiceOfferingDao.all`, `HumanApprovalRequestDao.findPending`
- `exportSchema` remains false (pre-existing project decision; schema equivalence covered by raw-SQLite migration tests — no regression introduced)

## 4. Tests Executed (command + result)

```
./gradlew testDebugUnitTest assembleDebug   → BUILD SUCCESSFUL
./gradlew testDebugUnitTest                 → 565 tests, 0 failures (was 483)
git diff --check                            → clean
git apply --check (patch vs pristine b5b259d) → CLEAN
```

New/updated coverage (per §34 categories): **STARTUP** (bootstrap state machine ×10: first launch, restart, stale pin, archived pin, foreign pin, no-fallback, idempotency, multi-active corruption, corrupt rootPath); **ISOLATION** (scope rules ×15; project A↛B knowledge/sessions/files; lifecycle ×12); **AUTONOMY** (admissible action set ×14: quick-chat/chat/coding/search contracts, agent-capability binding, ASSISTED removal, no-over-blocking; E2E ×3: ordinary chat completes with a strict governor, non-terminal block, durable consent); **IMPORT/EXPORT** (×13: round-trip with new identity + ID remapping, duplicate names ASK/RENAME, malformed/corrupted package, path traversal, secret-exclusion enforcement, clone source-intact, verified move, Zip-Slip, hash mismatch); **RAG** (×9: scope modes, sibling rejection, persistence round-trip, RAM-vs-persistent eviction); **MIGRATION** (×5: v15→v16 columns/backfill/new tables/data survival); plus updated fakes and the stabilized drain test.

## 5. Environment Limitations (exact)

| Command | Status | Cause |
|---|---|---|
| `assembleDebug` | ✅ EXECUTED SUCCESSFULLY (100 MB debug APK) | Android SDK 36 + JDK 21 provisioned in-session |
| `testDebugUnitTest` (Robolectric) | ✅ EXECUTED SUCCESSFULLY (565/565) | — |
| `bundleRelease` / signed AAB | ⚠️ NOT RUN | Release signing requires `KEYSTORE_PATH`/`STORE_PASSWORD`/`KEY_PASSWORD` env secrets (CI-only per docs/RELEASE-SIGNING.md) |
| `connectedDebugAndroidTest` | ⚠️ NOT RUN | No device/emulator in this environment |
| `lintDebug` | ⚠️ Not part of the default verification task chain; compile-level validation complete | R8/lint run inside release pipeline |

Everything claimed above was actually executed in this session.

## 6. Files Included (75 files, +8,749/−164)

**New domain (7):** ScopeModels, ProjectModels, TaskIntentModels, ArtifactModels, AuditModels + updated Knowledge/Session/ToolLifecycle models
**New application (19):** WorkspaceBootstrapOrchestrator, ProjectRuntimeService, ProjectReadinessService, ContextResolverService, TaskContractResolver, DecisionExplanationService, ArtifactService, AuditTrailService, FileTransferService, ProjectPackageService, SessionExportService, RepairCenterService
**New infrastructure (4):** SandboxProjectFileStore, ProjectPortabilityEntities, ProjectPortabilityDaos, network_security_config.xml
**Modified core:** AgentOrchestrator (admissible set + non-terminal governance + pinned policy + timeout), DecisionService/DecisionContext, ExecuteAgentTaskUseCase, WorkspaceRuntimeService, PolicyVersionService, ToolLifecycleService, HumanApprovalGate(+ports/stores), RagPipelineService/RagIntelligenceService/KnowledgePersistenceService, RoomVectorStoreAdapter, DiscoveryAdapterFactory, ProtocolAdapterFactory, ProviderControlPlaneService, CbrMdpEngine/MdpLearningStore/RoomMdpLearningStore, AppDatabase (v16), 7 DAO files, 4 entity files, ExecutionScope, AppContainer, MainViewModel, UiState, AndroidManifest
**Tests (8 new files + 5 updated):** BootstrapStateMachineTest, ScopeRulesTest, AdmissibleActionSetTest, AutonomyPipelineE2ETest, ProjectIsolationAndLifecycleTest, ProjectPackageTransferTest, RagScopeModeTest, Migration15to16Test

## 7. Integration Instructions

```bash
# 1. Prepare a clean working tree on main at b5b259d (or later main that still
#    applies; the patch was verified against b5b259d exactly):
git clone https://github.com/mualmahdy/ai-v0 && cd ai-v0
git checkout main

# 2. Verify the patch applies (dry-run):
git apply --check ai-v0-repair-order-implementation.patch

# 3. Apply as a commit (preserves authorship + full message):
git am ai-v0-repair-order-implementation.patch
#    — or, if you prefer a plain working-tree change:
#    git apply ai-v0-repair-order-implementation.patch

# 4. Validate:
./gradlew testDebugUnitTest assembleDebug   # expected: 565 tests green, BUILD SUCCESSFUL

# 5. Push:
git push origin main
```

No migration risk: DB v15→v16 is purely additive; the full migration chain 1→16 ships in the patch; upgrade behavior is covered by `Migration15to16Test` against a raw seeded v15 schema.

## 8. Known Honesty Notes (per §1 rules 13/19)

- The "CBR-MDP" engine is now honestly documented as a heuristic decision/ranking layer with tabular-Q learning (it never implemented the axiomatic model of cbr-mdp.txt; the code comment now says so explicitly).
- Legacy wiring paths (pure-JVM tests without the bootstrap orchestrator/project runtime) are retained with null-default constructor parameters — documented as test wiring, not production ambiguity; production wiring (AppContainer) passes all services.
- The egress authority is closed for the paths fixed in this patch; `McpAdapter`/`ResourceValidators`/`RadarDiscoverySources`/`OnnxSemanticEmbeddingAdapter.provision` still carry their own pre-existing clients — flagged for follow-up hardening (their risk is bounded: validators/sources are user-triggered, MCP is opt-in).
