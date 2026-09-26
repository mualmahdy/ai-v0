# AI-V0 — Product Closure Record (CLOSURE Phase)

This document is the single authoritative record of the closure phase: the
architecture invariants, lifecycle contracts, scope rules, attachment and
artifact models, transfer guarantees, UI information architecture, the
rendering support matrix, the accessibility contract, the test matrix, and
the KNOWN DELIBERATE LIMITATIONS. Every claim here is backed by a shipped
implementation and (where feasible in this environment) an automated test.

---

## 1. Architecture Invariants

### 1.1 Immutable Invocation Scope (P0)

When a chat invocation is ACCEPTED, one immutable tuple is captured:

```
(workspaceId, projectId, sessionId, executionId)
```

- The acceptance-time capture happens synchronously in
  `StudioViewModel.executePrompt` / `launchExecutionForUserEntry`
  (workspace + project) and `executeText` (session candidate).
- The tuple travels explicitly: `ExecuteAgentTaskUseCase` →
  `AgentOrchestrator.executeTaskStream(pinnedWorkspaceId, pinnedProjectId,
  pinnedSessionId)` → `CanonicalExecutionContext` → the coroutine-context
  `ExecutionScope` that every execution layer resolves.
- `CanonicalExecutionContext` now carries `sessionId` (additive codec: a
  legacy payload decodes to `null` — the honest "not a chat execution").
- After acceptance, NO execution layer may read the live
  active-workspace/active-project providers:
  - When a pinned workspace exists, the kernel NEVER consults
    `projectIdProvider` (an unbound project stays null — never another
    workspace's project).
  - The TelemetryService live-workspace fallback was REMOVED — events with
    no `Started` binding are honestly UNATTRIBUTED.
  - The in-process MCP bridge (`workspace_summary`) resolves its project
    from the pinned `ExecutionScope` only and fails closed otherwise.
  - The autonomy governor resolves the pinned execution workspace only.
  - Durable side-effects (turn persistence, approval blocks, capability
    events, approval-state mirrors) run on the app-wide
  `ExecutionHost.durableScope` — they survive ViewModel destruction and
    carry their pinned ids as explicit parameters.
- **Adversarial acceptance test**: `ImmutableInvocationScopeTest` wires
  live providers that THROW after the switch; the scope-capturing mock LLM
  proves the innermost execution layer saw the complete pinned tuple.

### 1.2 Scope hierarchy

```
Application → Workspace → Project → Session → Turn
```

- Attachments, turns, timeline events, artifacts and knowledge are
  project/workspace-scoped by SQL predicates (sibling-invisible).
- Cross-scope reads are impossible by construction (workspace-authorized
  DAOs; a foreign row is indistinguishable from nonexistent).

---

## 2. Transfer Guarantees (P1 §4)

**One source of truth for Move**: `ProjectTransferCoordinator`.

| Path | Semantics | Verification |
|---|---|---|
| Move (same device) | IDENTITY-PRESERVING rebind of every scoped row (sessions + turns + timeline, knowledge + chunks, tasks, artifacts) in ONE Room transaction | In-transaction count witnesses (before/after) — any mismatch rolls the whole move back |
| Move (cross-package) | Export → import as a NEW identity, deep-verified, then FULL source cascade | The v3 import pipeline's verification + explicit source cascade |

**The v3 `.aiv0project` package**:

```
manifest.json       schema 3, counts (files/knowledge/sessions/turns/
                    timeline/tasks/artifacts), FULL content hashes
                    (every file — no 500-file sample), files.zip digest
manifest.sha256     canonical manifest digest (v2+ mechanism)
project.json / files.zip / knowledge.json / sessions.json / turns.json /
timeline.json / tasks.json / artifacts.json / dependencies.json / about.json
```

**Atomicity contract** (no partial destination state):

```
READ → VALIDATE (manifest/digest/hashes/COUNT CONTRACTS)
     → STAGE (files land in .staging_import_pkg_*; hash re-verified
              after write; ANY mismatch aborts before a single DB row)
     → TRANSFER (ONE Room transaction: all rows or none)
     → COMMIT (staging → project root; atomic rename with copy fallback)
     → VERIFY (post-state counts + per-file hashes vs manifest;
               ANY mismatch ⇒ full ROLLBACK: rows + promoted root)
     → REBUILD_INDEXES (RAG chunks re-split + re-embedded on THIS device)
     → audit
```

- **Integrity ≠ authenticity**: manifest hashes prove the package was not
  corrupted in transit. They do NOT prove who created it — there is no
  signature; the appId check is a compatibility gate, not a trust
  boundary. This distinction is deliberate.
- REPLACE conflict policy cascades the replaced project fully (its rows
  die in the same transaction that inserts the replacement).
- `createSnapshot` persists the REAL payload at
  `<workspaces>/snapshots/<id>.aiv0project` + its hash;
  `restoreSnapshot` imports it as a verified new project and only then
  cascades the pre-restore project — a failed restore never destroys the
  current state.

---

## 3. Portability Contracts (P1 §5)

| Asset | What travels | Rebuild strategy |
|---|---|---|
| Sessions | Metadata + FULL turns + timeline events (ids remapped; turnCount recomputed from ACTUAL landed turns) | direct |
| Turn attachments + citations | `attachmentsJson` / `sourcesJson` ride the turn rows | direct |
| Knowledge | Document metadata + FULL content (the trusted rebuild source) | chunks are NOT portable (embedding vectors are resource-bound) — re-split + re-embed on the destination via the RAG pipeline; `totalChunks` reflects the REAL rebuilt count (0 + an honest note when no pipeline is wired) |
| Tasks | FULL state: lifecycle (preserved — NEVER blanket COMPLETED), goal, checkpoint, canonical execution context, agent/model bindings, budgets | direct; imported RUNNING tasks are handled by the recovery surface (resume or reconcile) |
| Artifacts | Row metadata (scope, type, mime, hash, storageUri, security classification) + payload bytes inside files.zip | direct |
| Dependencies | Declarations, re-evaluated at runtime against the destination's resources | honest re-evaluation (compatibility report) |

---

## 4. Attachment & Folder Model (P1 §6/§7)

**The hierarchy is explicit**: Application → Workspace → Project → Session → Turn.
Every turn attachment carries an honest `groundingState`:

| State | Meaning |
|---|---|
| `ATTACHMENT_ONLY` | A message-level reference; content NOT sent to the model (non-text, or folder in reference-only mode) |
| `GROUNDED` | A bounded digest of the content rode the request as clearly-marked user evidence |
| `KNOWLEDGE_IMPORTED` | The content entered the project's knowledge corpus (a DISTINCT act — never conflated with attaching) |

**Folder understanding** (`FolderUnderstandingService`):

| Mode | Contract |
|---|---|
| `ATTACHMENT_ONLY` | Stored + referenceable; NOTHING read. Displayed as "مرفق فقط" |
| `GROUNDED` | Readable text files (bounded: ≤40 files, ≤512KB each, ≤12k chars digest) enter the turn's grounding digest; the UI shows exactly how many files were read/skipped |
| `KNOWLEDGE_IMPORT` | Readable files enter the corpus through the REAL RAG pipeline |

A folder is NEVER displayed as "analyzed" without actual ingestion or
grounding — the report (`folderReportJson`) rides the attachment and the
chip renders the actual counts.

---

## 5. Artifact Lifecycle (P1 §8)

```
Assistant Result → Create Artifact (v1) → Preview → Edit → Save (next version)
                → Version History → Rollback (new version with old bytes)
```

- DB v20: `artifact_versions` (append-only) + `artifacts.currentVersion`.
- Saving an assistant result creates a REAL project-scoped artifact with
  version 1 (`saveAssistantResultAsArtifact`).
- Every edit saves as the NEXT version — history is never rewritten.
- Rollback creates a NEW version whose bytes equal the target version
  (auditable, non-destructive).
- Governed file saves in the coding workspace land as artifact versions
  too (per-save undo through the same pipeline).
- The SmartArtifactCanvas carries the full surface: preview, direct edit,
  save-as-new-version, versions sheet with rollback.

---

## 6. Coding Workspace (P1 §9)

- Files → Open → Edit (governed write) → Save (= new artifact version) →
  Rollback through the artifact version history.
- Every mutation goes through the governed admission pipeline (the
  `CodingToolchainService` declarations; `run_code`/`run_tests` are
  honestly denied on-device).
- Mobile-first: the editor is the focus; dangerous mutations pass
  governance; all writes are attributed to the pinned execution scope.

---

## 7. Governance & Recovery (P1 §10/§11)

**Governance Center** (the Governance screen) now exposes:

- Pending approvals (approve-once / allow-always / reject) — the REAL gate;
- **Active grants**: every standing consent with scope (GLOBAL vs
  workspace), principal, granter and expiry — with a REAL revoke path
  (`PermissionGrantService.revoke`, audited);
- **Approval history**: every decision the gate ever recorded, with the
  execution identity.

**System Health** (the HEALTH route, "صحة النظام"):

- Interrupted executions: RUNNING tasks with NO live execution — detected
  by EXACT task identity (another live execution in the same workspace can
  never mask a stale task), each offering RESUME (the real durable resume
  through the canonical context + checkpoint) or reconcile-to-FAILED;
- Deterministic condition repairs (stale active-project pins, orphaned
  projects, invalid knowledge references) with honest post-repair
  verification (a repair that did not fix the condition says so).

---

## 8. Mobile Information Architecture (P1 §12-15)

| Surface | Shows |
|---|---|
| Global (TopBar) | Workspace + active project ONLY |
| Chat header | Session identity + THIS conversation's project binding + one context summary chip + policy chip + live execution indicator |
| Composer | Input + primary actions permanently; attachments/diagnostics only when relevant; the token gauge is TRANSIENT (NEAR_FULL/CRITICAL or executing) |
| Conversation Context (§14) | ONE concept — summary in the header; details/edit in the context sheet |
| More (capability center) | Three groups: Workspace (Explorer/Knowledge/Files/Projects-adjacent), Intelligence (Providers/Tasks/Decision/Radar), Governance & System (Governance/Extensions/Health/Settings) |

The agent/model chip was REMOVED from the composer (it was duplicated
three ways); the chat header now renders the project binding it previously
dropped.

---

## 9. Rendering Support Matrix (P1 §16/§17)

| Content | Supported | Honest degradation |
|---|---|---|
| Markdown | headings 1-6, lists, emphasis, links, quotes, tables (wide → h-scroll), fenced code | tolerant parser (unclosed markers → literal) |
| Code | syntax highlighting (Kotlin/Java/Python/JS/JSON/Bash/SQL/XML/C-like), line numbers, copy, h-scroll | unknown language → plain monospace |
| Math | REAL typesetting for: `\frac`, `\sqrt` (radical + overline), `^{}`/`_{}` (baseline-shifted scripts), `\sum`/`\int`/`\prod` with limits, greek + operator table | anything else → Unicode approximation WITH a visible "approximation" notice |
| Charts | REAL bar chart + line chart (Canvas: axes, gridlines, value/axis labels); `chart:line` first-line directive | non-plottable data → readable data-table fallback |
| Diagrams | documented subset: `A --> B`, `A --label--> B`, `---`, `==>`, `graph TD/LR` headers, Unicode/Arabic node ids; layered topological layout | unparseable source → RAW SOURCE with an explicit notice (never a fake graph) |
| Bidi | mixed Arabic + technical runs are isolated (LRI/PDI) so paths/URLs/identifiers keep their visual order; code/math LTR-pinned | — |

---

## 10. Accessibility Contract (P2 §18 — current state)

- IconButtons are 48dp minimums (Material default) with Arabic
  contentDescriptions; decorative icons are `contentDescription = null`.
- New surfaces (health, grants, transfer, canvas versions) expose Arabic
  screen-reader labels and test tags.
- The live execution indicator + honest disabled-capability reasons are
  rendered as visible text (screen-reader accessible by content).
- Known gap (deliberate, tracked): TalkBack-specific semantics tuning and
  full keyboard focus-order suites are NOT yet automated in this
  environment (see Test Report — unverified section).

---

## 11. Localization (P2 §19 — current state)

- New screens/sections added in this phase are resource-backed
  (`health_*`, `projects_*`, `more_health_*`).
- The pre-existing studio surface still carries Arabic literals inline
  (the D-12 rule adopted resources per-new-surface). A full sweep is a
  tracked follow-up; the terminology used in all new surfaces is unified
  (مخرج = artifact، منح = grant، لقطة = snapshot، استعادة = restore).

---

## 12. Test Matrix (closure additions)

| Contract | Test |
|---|---|
| Immutable invocation scope (A→B adversarial, exploding providers) | `gapclosure/ImmutableInvocationScopeTest` |
| v3 round-trip portability (turns/timeline/tasks/artifacts) | `gapclosure/ClosureTransferAtomicityTest` |
| Count-contract rejection (destination untouched) | `gapclosure/ClosureTransferAtomicityTest` |
| Identity-preserving verified move (scoped-row rebind + witnesses) | `gapclosure/ClosureTransferAtomicityTest` |
| Task lifecycle honesty (no blanket COMPLETED) | `gapclosure/ClosureTransferAtomicityTest` |
| Artifact versioning (create/read/rollback, append-only) | `gapclosure/ClosureTransferAtomicityTest` |
| Folder understanding (grounded modes + honest reports) | `gapclosure/ClosureTransferAtomicityTest` |
| Bidi isolation of technical runs | `gapclosure/ClosureTransferAtomicityTest` |
| Package safety (traversal/secret/tamper/zip-slip) | `convergence/ProjectPackageTransferTest` (pre-existing, v3-updated) |
| Session/project/workspace isolation | pre-existing gapclosure suites (all remain green) |
| Composer consolidation (no duplicate context chip) | `presentation/ChatInputAcceptanceTest` (updated contract) |
| Migration v19→v20 | `convergence/Migration18to19Test` + chain validation (version 20) |

---

## 13. Known Deliberate Limitations

1. **Embeddings are device-local**: imported knowledge is re-embedded with
   the destination's active embedding resource (or the lexical fallback,
   honestly labeled). Chunk vectors never travel.
2. **Diagrams render top-down only** (documented; the mobile constraint).
   `graph LR` is accepted and rendered TD.
3. **Math typesetting subset**: fractions, roots, scripts, big operators,
   symbols. Matrices/cases fall back to the Unicode approximation with a
   visible notice.
4. **Keyboard-state visual fixtures** (§20 keyboard matrix) are Robolectric
   environment-dependent — recorded as UNVERIFIED in the test report.
5. **TalkBack/keyboard-navigation suites** are contract-documented but not
   automated in this environment.
6. **Session-level attachment visibility**: attachments remain
   project-scope-bound; the turn-level reference is honest but the
   storage is project-sandboxed (documented design, not a silent leak).
7. **Export/UI on SAF**: export/import go through SAF streams; the
   AndroidContentPort has no in-test fake for the ProjectsScreen launchers
   (the service layer is fully tested).

---

## 14. Definition-of-Done Crosscheck

| Requirement | State |
|---|---|
| No scope escape | Enforced + adversarially tested |
| No partial transfer reported as success | Atomic pipeline + rollback + count contracts, tested |
| No user-facing capability without execution path | Transfer/governance/health/artifact surfaces all route through real services |
| Session/Project/Workspace isolation | Pre-existing suites green |
| Project portable (identity-preserving AND package) | Both paths implemented + tested |
| RAG portable | Content travels; chunks rebuilt on-device (tested via report honesty) |
| Artifact save/persist/retrieve/version/rollback | Implemented + tested |
| Coding workflow usable on the phone | Editor + governed saves + version undo (Files surface) |
| Governance manageable (not inline-only) | Grants list + revoke + history |
| Recovery usable | System Health screen with exact-identity resume/reconcile |
| Chat does not repeat context three ways | Composer chip removed; header/sheet split |
| Rich technical content rendered honestly | Math/charts/diagrams/bidi matrix above |
| Regression suite | All suites green (see Test Report) |
| APK/build status | Known precisely — see the Test Report |
