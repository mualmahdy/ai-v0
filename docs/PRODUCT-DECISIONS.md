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
