# Product Decisions — AI-V0 (Design Closure 2026, Phase 3 / P3)

> This file records explicit, dated PRODUCT and ARCHITECTURE decisions made
> while closing the Design Closure 2026 gap register (Phases 0–3). Each
> entry states the decision, the reason, and what would reopen it. It is
> the in-repo home the audit asked for (the code comments referenced
> "Design Closure 2026, ADR-x" with no in-repo record).

---

## D-1 — UI language: Arabic-only, inline (GAP-20 / ADR-6 point 5)

**Decision (2026-09):** the product is **Arabic-only for now**. All
user-facing copy lives as Arabic string literals in the Compose screens
(~600 literals across 14 screens), the layout direction is pinned to RTL
in `MainActivity` (`LocalLayoutDirection provides LayoutDirection.Rtl`)
regardless of device locale, and there is **no i18n infrastructure** (a
single `values/strings.xml` containing only `app_name`; no `values-*`
qualifiers, no `localeConfig`, no per-app language support).

**Why:** the audience is Arabic-first; every screen already renders a
coherent RTL Arabic experience. Extracting ~600 literals to resources NOW
would be churn with no user-visible value — and per ADR-6 the
MainViewModel/UiState decomposition (feature ViewModels) comes FIRST; doing
resource extraction before that split would just move the same debt.

**Reopen when:** the UI-redesign track (ADR-6) starts. Then: NEW screens
introduce `stringResource()` from day one; existing literals migrate screen
-by-screen as each screen is redesigned. Do not mix inline literals into
new screens after that point.

---

## D-11 — Terminal-event topology: a non-fatal provider error is NOT terminal; the loop's Completed is honest about DEGRADED completion, not about success

**Decision (2026-09, ADR-6 slice 6 — the "Decision slice" verdict the
slice-2 findings deferred to):** the execution kernel's terminal-event
topology stays AS IS. When a provider error is NON-FATAL at the step level
(within the task's retry budget / degradation policy), the closed loop
counts it and CONTINUES; the run eventually terminates with
`ExecutionEvent.Completed` carrying whatever accumulated output exists —
the Arabic fallback «اكتملت معالجة المهمة.» when nothing accumulated. The
transcript therefore shows an Error entry followed by a Completed entry,
which the Studio renders as two turns.

**Why not "fix" it now (the honest scope call):**
- The two events describe two DIFFERENT truths, not a contradiction: the
  step failed (Error), and the run ended (Completed — possibly degraded).
  `ExecutionEvent.Completed` already carries `isDegraded` +
  `degradedReason`; the semantics are "the loop finished", not "every step
  succeeded".
- Making a provider error TERMINATE the loop would change retry/degradation
  semantics (ADR-4 territory — the governed execution contract), a product
  behavior change with its own blast radius (idempotency ledger, resumable
  state, task persistence) — not a UI-redesign matter. The ADR-6 track's
  own rule is verbatim moves + display-honesty repairs only.
- The user-facing lie, if any, is in how the STUDIO RENDERS the pair, not
  in the kernel's emissions.

**Reopen when:** the Studio transcript rendering is redesigned to merge a
non-fatal Error + subsequent degraded Completed into ONE turn (a display
change owned by the Studio feature), OR a product decision makes provider
errors terminal (an execution-contract change owned by ADR-4). Until then
this topology is contractual.

**RESOLVED (2026-09, frontier chat upgrade):** the reopen condition fired —
the Studio transcript merge landed. A non-approval provider Error is now
RECORDED (banner feedback immediate) and rendered ONCE, folded into the
run's terminal Completed: ONE timeline entry, ONE durable turn (the merge
is durable-level BY DESIGN — the timeline is rebuilt from durable turns on
session reopen, so a display-only merge would resurrect the double entry).
The merged turn keeps the provider's own failure message (the kernel's
generic fallback never masks a real error) and renders the honest pill
«اكتمل جزئياً بعد خطأ» when the Completed carried isDegraded. The
finally-guard preserves durability (a kernel death before any terminal
event still lands the failed turn). The kernel's event topology stays
EXACTLY as this decision ruled — the change is wholly in the Studio
display layer, per the reopen clause's own ownership boundary.

---

## D-12 — i18n at UI-redesign track closure: Arabic-only stands; string extraction is the PREREQUISITE for any language expansion (GAP-20 / ADR-6 slice 8)

**Decision (2026-09, ADR-6 slice 8 — the track-closure verdict the
next-slices table deferred to):** the product stays **Arabic-only with
inline literals** — D-1's substance is unchanged and now CONFIRMED as the
deliberate end-state of the seven-slice UI-redesign track, not a temporary
stopgap. The track deliberately introduced **zero** `stringResource()`
migration: no NEW screens were created (every slice re-architected an
EXISTING screen onto its feature ViewModel), and D-1's "new screens adopt
resources from day one" clause was therefore never triggered. Mixing a
~600-literal resource extraction into slices whose own rule was
verbatim-move-plus-display-honesty-repairs-only would have been exactly the
churn-without-user-value D-1 predicted, with real regression risk on every
redesigned surface.

**The standing rule going forward:** any language expansion — a second
locale, per-app language selection, unpinning RTL — REQUIRES the string
extraction as a PREREQUISITE, done screen-by-screen. The track made that
migration tractable: every screen now has exactly ONE owning feature
ViewModel whose GAP-21 behavioral suite pins its behavior, so a
screen-scoped extraction can be verified per screen instead of as one
unverifiable global churn.

**Reopen when:** a concrete second-language requirement exists; or the
D-11 Studio-transcript redesign lands and touches the remaining inline
literals anyway.

---

## D-13 — the app shell keeps NO writerless display state: UiState.isDegraded / degradedReason / diagnosticBanner REMOVED (GAP-23 family / ADR-6 slice 8)

**Decision (2026-09, ADR-6 slice 8 — the latent-shell-mirror verdict the
slice-7 delivery deferred to):** the three shell fields are DELETED, not
wired. `isDegraded` / `degradedReason` had NO WRITER since the slice-2
extraction (their only reader styled a banner leg that was permanently
false), and `diagnosticBanner` itself had NO WRITER either — the shell's
banner block in MainAppScreen could never render anything, and its dismiss
function dismissed a channel nobody wrote.

**Why delete rather than write them:** there is NO honest shell-scope
degradation source. The shell owns the bootstrap gate (which has its own
explicit full-screen failure surface) and display mirrors; real
degradation display already lives in the features that own real sources —
the Studio feature's execution-degradation banner, the workflow report's
DEGRADED outcome row, and the durable execution rows. Fabricating a
shell-level writer (e.g. OR-ing feature degradations into the shell) would
re-introduce exactly the shared-mutable-state coupling the seven slices
removed, to style a banner that today renders nothing.

**Reopen when:** a genuinely shell-scoped diagnostic gains a real source
(e.g. a global resource-pressure or connectivity authority) — then it gets
a REAL channel with a REAL writer, never a writerless hook resurrected from
version control.

---

## D-2 — Audit truth: two tables, two documented mandates, ONE unified reader (GAP-24 / ADR-8 option ج)

**Decision (2026-09):** keep BOTH audit tables with EXPLICIT, distinct
roles, and read them through ONE unified reader so the observatory sees a
single audit truth:

- `audit_trail` — **runtime governance/security decisions** (admission
  verdicts, permission grants/revokes, budget-gate denials, bootstrap
  degradation). Written via `TelemetryPort.recordAudit`; severity/decision
  model (INFO/WARN/ERROR/CRITICAL + ALLOW/DENY/...).
- `audit_events` (REPAIR ORDER §30) — **user/project lifecycle & portability
  actions** with full who/what/when/source-scope/target-scope/policy/result
  attribution. Written via `AuditTrailService`.

The unified reader is `RoomTelemetryRepository.auditEvents(...)`
(both overloads): it `combine`s both tables' Room-invalidation flows, maps
§30 rows into the feed's severity/decision shape
(SUCCESS→INFO, DENIED/DEGRADED→WARN, FAILURE→ERROR; provenance preserved in
the `attributes["source"]` tag), and re-sorts by time. Workspace scoping
(GAP-04) applies to BOTH sources; `null` workspace stays the honest empty
feed.

**Adjacent honesty repairs shipped with this decision:**
- `recordAudit` write failures are now COUNTED and surfaced
  (`MeasurementHealth` → the Governance "صحة القياس" card) instead of a
  swallowed `-1L`.
- `SessionExportService` is now wired to `AuditTrailService` in
  `AppContainer` (its SESSION_EXPORTED events previously never landed).
- `ArtifactService` labels its events truthfully (FILE_REGISTERED /
  FILE_READ / FILE_COPIED — previously all three were FILE_EXPORTED).

**Long-term (recorded, NOT yet decided):** ADR-8's option (أ) — unify on the
richer `audit_events` schema with a new reader — is the planned end state,
to be executed at the NEXT schema touch (it merges with the ADR-1 migration
discipline). Until then the roles above are contractual.

---

## D-3 — Vector similarity: one strict kernel; recorded exemptions and debt (GAP-28)

**Decision (2026-09):** `domain.core.memory.VectorMath` is the SINGLE
similarity kernel. The four divergent cosine copies
(RagPipelineService / RagIntelligenceService / MemoryLifecycleService /
RoomVectorStoreAdapter) now all delegate to it; its contract is
**strict**: dimension mismatch → 0 (never min-length truncation — a
truncated dot product is a fabricated score), zero-vector → 0, result
coerced to [-1, 1]. RagPipelineService's previous truncation semantics were
upgraded to the strict kernel deliberately: mismatched pairs could only
reach its cosine when the score was discarded by the `vectorCompatible`
gate anyway.

**Exemptions (deliberate, documented):**
- `CaseBase.computeCosineSimilarity` keeps its **zero-padding** variant —
  a test-pinned domain fix (FIX DOM-P0-01) for legacy decision feature
  vectors; see its KDoc.
- The RAG pipeline's 32-dim lexical generator stays LOCAL (different
  tokenizer + dimension): persisted document chunks already carry 32-dim
  vectors from it, and changing the space would silently invalidate stored
  corpora. The memory subsystem's 128-dim lexical builder IS unified
  (`VectorMath.lexicalSparseVector`).

**Deferred debt (recorded; matters as corpora grow):**
- Vector storage is JSON text (~3-5x bloat vs BLOB). Migration to BLOB
  (+ possibly sqlite-vec) is deferred to the next schema-touching phase —
  it must ride an ADR-1-disciplined migration, not a drive-by.
- Vector decode failure policy is now uniform (skip the row, count it,
  log it) across KnowledgePersistenceService, RoomVectorStoreAdapter,
  MemoryLifecycleService, and RagIntelligenceService.

---

## D-4 — Bootstrap corruption semantics: repair, not fail (GAP-25)

**Decision (2026-09):** a project `rootPath` outside the sanctioned
projects directory is REPAIRED to the canonical sandbox path, recorded via
the bootstrap repair note (`repaired_corrupt_root_path`) and surfaced as a
bootstrap degradation — it is NOT a fatal startup failure. The
never-emitted `PROJECT_CORRUPT` failure constant was removed (declaring a
failure state that never fires is the dishonesty, not the repair).

**Reopen when:** a corruption class is discovered that CANNOT be repaired
safely — then add a real failure state that the startup gate renders.

---

## D-5 — Capability declarations: provisional until verified (GAP-25)

**Decision (2026-09):** the connect-wizard and bootstrap LLM offerings
declare REASONING + STREAMING as **UNVERIFIED defaults**. Their comments
now say so explicitly: the wizard's validation proves endpoint
reachability + auth (GET /models) only; it never probes streaming (SSE) or
reasoning behavior. The discovery path (DiscoveryAdapterFactory) already
declares only verified capabilities. The unverified defaults are KEPT
because decision-engine contracts filter offerings by these capabilities —
removing them would starve those contracts today. Real capability
verification (probe-based) belongs to the ADR-6 redesign track.

---

## D-6 — i18n-adjacent: build inputs (GAP-26, closed)

`.env.example` no longer carries any API-key placeholder: its only content
is the explanatory comment from Phase 1 (GAP-08/ADR-7 deleted the dead
GEMINI plumbing; no BuildConfig field is generated from it). Re-add a real
key only when a live consumer is wired.

---

## D-7 — Retrieval authority is ONE pipeline (RagIntelligenceService deleted)

**Decision (2026-09, ADR-4/ADR-7):** `RagIntelligenceService` and its
`domain.core.rag.intelligence` models are DELETED. The service was
production-dead its whole life (tested-only, zero production callers)
while the LIVE pipeline (`RagPipelineService`) already ships hybrid
retrieval — semantic 0.6 + lexical F1 0.4 + contains-boost rerank +
bounded scan — under the gap-closed guards (scope-mode isolation,
embedding-compatibility boundary, Arabic normalization, per-chunk
retrievalMode). Its v2 delta (BM25/RRF/heuristic rerank) was marginal and
never justified a SECOND retrieval authority — the exact DUPLICATED
AUTHORITY pattern this closure eliminates. The retired `@Deprecated`
message on `retrieveRelevantContext(query, topK, maxTokenBudget)` no
longer claims a "budget authority moved to RagIntelligenceService" that
never existed: the pipeline owns its bounded scan/assembly budget.

**Reopen when:** agentic retrieval (ADR-4 medium-term — passing retrieval
tools to the model in AGENT modes) lands on the redesign track; it extends
the ONE pipeline, it does not resurrect the deleted layer.

---

## D-8 — Scope resolution authority: ScopeRules + pinned ExecutionScope

**Decision (2026-09, ADR-7):** `ContextResolverService` is DELETED. It was
wired with ZERO callers (main and test), its "ONE service… every service"
KDoc was contradicted by the 8+ production sites that read
`ExecutionScope` inline, and its injected `AppDatabase` was never used.
The canonical scope authority remains the pinned
`ExecutionScope` (workspace + sandbox project bound at execution launch)
plus `ScopeRules`; every service resolves scope from the coroutine context
— there is no second resolver to drift from it.

**Reopen when:** a real multi-source scope-resolution need appears (e.g.
flow-restore of an interrupted execution's scope) — extend
`ExecutionScope`/`ScopeRules`, do not re-introduce a parallel resolver.

---

## D-9 — Memory lifecycle surface = what production calls

**Decision (2026-09, ADR-7):** `rank` / `forget` / `storeScoped` /
`retrieveScoped` are DELETED from `MemoryLifecyclePort` (and
`MemoryLifecycleService`), together with `ForgettingPolicy`,
`RankedMemoryRecord`, the MemoryDao methods that served ONLY them
(getMemoriesBelowDecay, deleteArchivedOlderThan, activeCountGlobal,
leastRecentlyUsedGlobal, getActiveForWorkspaceAndAgentAndTypes,
getMemoryById), and the service's never-read `memoryRepository` +
`embeddingProvider` constructor params. All four APIs had ZERO production
callers since inception; `rank` was reachable only from the uncalled
`retrieveScoped` and carried an N+1 (one getMemoryById round-trip per
memory); `forget`'s semantics overlap the production-called `applyDecay` +
`consolidate`. The production surface is exactly what production calls:
decay + consolidate (bootstrap) and namespaces (AgentLifecycleService).

**Reopen when:** a real ranking consumer appears (e.g. the memory screen
sorting by final rank) — re-derive it from the LIVE retrieval scores
(`RoomVectorStoreAdapter`), not from a second N+1 path.

---

## D-10 — WorkspaceContextEngine deleted; resource_edges schema stays until ADR-1

**Decision (2026-09, ADR-7):** `WorkspaceContextEngine`, its
`domain.core.workspace.context` models (events, snapshot, suggestions,
signals — zero importers after the engine's deletion) are DELETED. Every
input path was dead (`registerResource`/`registerDependency`/
`recordExecutionActivity` had zero callers) so `resource_edges` was never
written, its events had zero collectors, and the only production-read
surface — the suggestions flow feeding a dashboard stat + a feed filter +
their cards — was permanently EMPTY: a fabricated-zero capability, removed
with the engine (feed filter/cards + the dashboard stat now show real
rows only). The `resource_edges` TABLE/DAO/entity stay schema-registered
(NO v18 touch in this phase — schema changes are frozen to ADR-1); they
are droppable at the next ADR-1 schema change.

**Reopen when:** the redesign track actually builds a live resource-graph
surface — then wire a REAL input path first (writers before readers), or
drop the table at the next ADR-1 schema touch.
