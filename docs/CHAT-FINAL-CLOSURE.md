# Chat Final Closure — Invariants

Scope: the Chat Workspace's backend/runtime correctness and presentation
closure. This document records the INVARIANTS this closure establishes (the
"why they can never regress" contract); it deliberately does not re-narrate
implementation details that the code and its tests already state.

## 1. Session / execution binding invariant

A prompt execution captures its **session candidate** (the active session id)
**synchronously at send acceptance**, together with the pinned
workspace/project. The whole execution lifecycle — session reuse check, turn
persistence, approval events, first-turn titling — uses that candidate
end-to-end. The live `activeSessionId` is never read again by a running
execution.

Consequence: opening/switching to session B while execution A (accepted on
session A) is still establishing its session can never make A reuse or
persist into B. Incompatible candidates are released and a NEW session is
created under the execution's own pinned scope (the pre-existing §2 rule).

Regression proof: `StudioViewModelFinalClosureTest`
(`P1-1 an execution accepted on session A never persists into session B…`),
driven deterministically through the no-op-by-default
`sessionEstablishmentProbe` constructor seam (the same convention as
`detachedPersistenceFailureSink`).

## 2. Fail-closed durable-session invariant

`ensureActiveSession` returns an explicit `Established | Failed` outcome —
never a swallowed null. On `Failed`, the execution **refuses to run**: the
LLM is not invoked, no turn is fabricated, the user message keeps an honest
FAILED lifecycle block, and an explicit user-visible error states that
nothing was persisted. "No durable session ⇒ no normal persisted execution."

## 3. Persistence-result honesty invariant

Every durable write's **result** is part of the state machine:

- `appendTurn` returning `null` (workspace-authorized no-op) is treated
  exactly like a thrown failure — honest banner (attached execution) or the
  detached-failure sink (detached execution); titling never runs for a turn
  that was not persisted.
- `appendTimelineEvent` returning `false` ⇒ NO inline PENDING approval block
  is displayed (a block is shown as durable history only when its event
  really landed); the failure surfaces explicitly and the user is routed to
  the governance surface where the same gate request is resolvable.
- `updateTimelineEventApprovalState` returning `false`/throwing ⇒ the UI
  still shows the GATE's decision (the gate is the approval authority), and
  an explicit error states that the durable event may show a stale state
  after a reopen. The UI never silently claims the persisted state moved.
- `setSessionModel` is persist-first: the displayed model selection moves
  only after the durable pin is verified; failure keeps the previous
  selection and surfaces the error (nothing is shown as saved when it is
  not).

## 4. Approval durable-state invariant

Each approval block's durable mirror location (session + workspace) is
**tracked when its event is written** (and re-registered when a session is
reopened from its durable events). Decision updates target that tracked
location — never the live `activeSessionId`. View release (scope change,
view reset, session deletion) drops the registrations with the released
conversation.

`grantAlwaysForApproval` verifies the pending request EXISTS before doing
anything; a missing/already-resolved request can never end in
"APPROVED + تم السماح دائماً…" — it fails explicitly. The gate resolution's
real value (APPROVED / REJECTED / EXPIRED) decides the surfaced state.

## 5. Capability invocation scope-snapshot invariant

Every user-initiated capability invocation (tool / skill / MCP / direct
search / knowledge retrieval) captures its **(workspace, project, session)
synchronously at acceptance** and pins it into the SAME `ExecutionScope`
coroutine-context element the governed kernel uses:

- `ExecutionService.executeStandaloneTool` accepts `projectId`/`sessionId`
  and pins them — `FileSystemTool.resolveProjectId` resolves the pinned
  project FIRST (no fallback to the live active project mid-invocation).
- Direct search and knowledge retrieval wrap their pipelines in a pinned
  `ExecutionScope` — the search adapter's local fallback and the RAG
  retrieval scope resolve the pinned scope, never the live one.

Regression proofs: `ChatCapabilitiesScopePinningTest` (deterministic via a
`StandardTestDispatcher`: the invocation is queued, the live scope is
switched, then the coroutine runs — the invocation still operates on the
acceptance scope).

## 6. Capability presentation truthfulness

- Vision's hub status is **derived from the radar's real operational state**
  (AVAILABLE → available; DEGRADED → available-with-hint; BLOCKED / FAILED /
  DISABLED / PARTIAL / DEPRECATED → honestly unavailable with the radar's
  rationale). PLANNED is reserved for the radar's genuine PLANNED
  declarations — its current declaration table has VISION implemented=false,
  which is exactly what production shows. No fake availability, no masked
  real state.
- The "بحث ذكي" hub row presents the Search Intelligence pipeline by its
  real name and shape. There is NO independent Deep Research workflow in
  this version and nothing in the chat UI implies one.
- Search result states are honest: SUCCESS_WITH_RESULTS / SUCCESS_EMPTY /
  PARTIAL (a source degraded or failed) / FAILED are distinct.
  `SearchIntelligenceResult.isPartial` means "some source degraded or
  failed" — NEVER "zero results" (per-provider call outcomes are counted,
  not dropped).
- `ExecutionLifecycleProjection.apply` takes an optional `nowMs` clock
  parameter (default: wall clock) — deterministic testing without a refactor.

## 7. Responsive Chat topology

`ChatAdaptiveLayout.panePolicyFor(widthClass, availableWidthDp)` is the PURE
policy the chat shell renders: proportional pane widths with clamps
(sessions 30% ∈ [220, 300]dp; context 22% ∈ [240, 280]dp), a guaranteed
usable chat column (≥ `CHAT_MIN_WIDTH_DP` = 420dp), and honest downgrades —
the context pane drops first (its content stays reachable through the
header's context sheet), then the sessions pane falls back to the compact
bottom-sheet surface. The class topology (compact = chat + sheet,
medium = sessions + chat, expanded = sessions + chat + context) is the
baseline at the widths where it fits. Fixed 300dp + 280dp panes beside the
navigation rail can no longer squeeze the chat into an unusable strip at a
class's lower bound (600dp / 840dp devices).

## 8. Presentation actionability

- Sources carrying a real http(s) URL are openable (platform
  `ACTION_VIEW`); rows without a browser target stay readable. Rows keep
  ≥48dp touch targets and explicit semantics.
- Attachment chips ellipsize long filenames (never break the layout); no
  fake previews (no thumbnail pipeline exists for chat attachments).
- Message actions, capability rows, attachment removal, and the stream-copy
  control keep ≥48dp interactive targets with meaningful content
  descriptions.

## 9. FRONTIER UPGRADE 2026 — rendering pipeline invariants

The frontier chat upgrade (one-pipeline markdown, syntax highlighting,
reasoning tokens, session management, D-11 merge, context gauge) adds four
invariants to this closure:

### 9.1 One-markdown-pipeline invariant

`ChatMarkdownParser` is THE markdown parser. The rich renderer
(`ChatRichContent`) owns chart/mermaid/math/quote/wide-table dispatch and
delegates every basic-markdown stretch to the same typed `MdBlock`s the
plain surface used to re-parse per line. No second inline scanner exists;
every tolerance rule (unclosed markers stay literal, lone `$`/`~` degrade
honestly) lives in exactly one tested place. Regression proof:
`ChatMarkdownTest` + `ChatRichContentTest` (the parser contract suites).

### 9.2 Highlighter-never-loses invariant

`ChatSyntaxHighlighting` is a HIGHLIGHTER, not a parser: joined token text
always rebuilds the input line byte-for-byte (tested), unknown languages
degrade to the exact pre-highlighting plain rendering, and the palette is
derived from the ACTIVE MaterialTheme colorScheme (never hard-coded —
correct in light and dark). Regression proof: `ChatSyntaxHighlightingTest`
(17 JVM-pure cases, Arabic text inside strings/comments survives verbatim).

### 9.3 Reasoning-lane separation invariant

Reasoning is a FIRST-CLASS separate lane: `ExecutionEvent.ReasoningChunk`
never mixes into `ContentChunk`, the answer accumulator, or the final text
(by adapter construction AND by ExecutionService contract); providers that
report no reasoning never trigger a fabricated thinking placeholder
(honest absence). The live UI shows the model's OWN thinking in a subdued
collapsible lane (its own phase THINKING — «يفكر…»), which rides the
finished Assistant entry as runtime-only state (a restored session honestly
shows none — the durable turn stores the answer). Regression proof:
`ReasoningTokenStreamingTest` (6 adversarial cases) +
`ConversationTimelineTest` (THINKING projection).

### 9.4 One-turn-per-question invariant (the D-11 resolution)

A provider Error + the run's terminal Completed land as ONE timeline entry
and ONE durable turn (the timeline is REBUILT from durable turns — a
display-only merge would have resurrected the double entry on reopen). The
failed turn carries the provider's own failure message (the kernel's
generic fallback never masks a real error); a failed+degraded entry renders
the honest merged pill «اكتمل جزئياً بعد خطأ». Durability is never lost by
the merge: the finally-guard lands the failed turn if the kernel dies
before any terminal event. The kernel's event topology itself is untouched
(D-11's own scope call). Regression proof: the updated
`StudioViewModelTest` single-turn pin + the four Studio ViewModel suites.

### 9.5 Context-gauge honesty invariant

`ContextWindowGauge` renders ONLY measured usage + the governance layer's
REAL remaining budget; unknown figures (REMAINING_UNKNOWN sentinel) render
nothing — never a fabricated 0% bar. Severity tinting is threshold-honest
(primary → tertiary ≥70% → error ≥90%). Regression proof:
`ConversationTimelineTest` (gauge contract cases).

### 9.6 Screenshot matrix (the deferred-3 closure)

`ChatSurfaceRoborazziMatrixTest` pins the chat surface's reference
captures (rich conversation with highlighted code + sources + collapsed
reasoning + approval; the D-11 merged failure entry; live THINKING
collapsed/expanded; live STREAMING markdown) under the slice-6/7 matrix
precedent: fixed timestamps for byte-stable comparisons, RTL like
production, references committed at `app/src/test/screenshots/chat/`.
Recording uses the dedicated `recordRoborazziDebug` task; plain
`testDebugUnitTest` runs the tests capture-silent (the same contract as
the slice-6/7 matrices — CI's plain gate stays green; verification is the
`verifyRoborazziDebug` task: 5/5 unchanged).
