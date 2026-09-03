package com.example.application.audit

/**
 * AI-V0 Ultimate — Critical Findings Register
 * ============================================
 *
 * Phase 1 of the 8-phase engineering remediation plan.
 * This file is the authoritative source of truth for every defect discovered
 * during the Critical Discovery audit and tracks the status of each fix.
 *
 * Verdict legend (per audit spec):
 *   REAL       — fully implemented and behaves as documented
 *   PARTIAL    — real implementation, but missing pieces or incorrect behaviour
 *   MOCK       — pretends to be real with hardcoded responses
 *   STUB       — returns empty/hardcoded value, no real logic
 *   DEMO       — runs only on synthetic inputs, not real workloads
 *   SYNTHETIC  — fabricated priors/defaults presented as if measured
 *   UNUSED     — declared but no caller in the codebase
 *   DEAD       — declared but neither called nor callable
 *   MISLEADING — name or KDoc claims a behaviour the code does not deliver
 *   BROKEN     — has a correctness defect at runtime
 *
 * Status legend:
 *   OPEN       — known issue, not yet addressed
 *   IN_PROGRESS— fix in progress
 *   FIXED      — fix merged and verified by tests
 *   WONT_FIX   — intentionally not fixing (with rationale)
 *   DEFERRED   — moved to a later phase
 *
 * Priority legend:
 *   P0 — correctness defect, must be fixed before any feature work
 *   P1 — architectural gap, blocks the next phase
 *   P2 — honesty fix (synthetic default → honest default)
 *   P3 — type safety / API surface cleanup
 *   P4 — documentation / dead-code removal
 */
object CriticalFindingsRegister {

    /**
     * Authoritative list of every defect found in Phase 1.
     * Append-only. Update `status` as fixes land.
     */
    val findings: List<Finding> = listOf(
        // ────────────────────────────────────────────────────────────────────
        // P0 — Correctness Bugs (Domain Core)
        // ────────────────────────────────────────────────────────────────────
        Finding(
            id = "DOM-P0-01",
            severity = Severity.P0,
            verdict = Verdict.BROKEN,
            status = FixStatus.FIXED,
            title = "CaseBase bootstrap feature-vector length mismatch",
            file = "domain/core/decision/CaseBase.kt:113-163",
            description = """
                DecisionState.toFeatureVector() emits a length-15 vector, but the 5 bootstrap
                cases in bootstrapDefaultCases() use length-11 vectors. computeCosineSimilarity
                truncates to the shorter vector, so the last 4 features (hasSearchEvidence,
                hasMemoryEvidence, hasToolExecutionEvidence, lastActionSuccess) are NEVER
                compared against bootstrap cases. The evidence signal is silently dropped.
            """.trimIndent(),
            fix = "Pad bootstrap vectors to length 15 with positional documentation; add a FeatureVectorSchema version constant and reject persisted cases with mismatched schemas.",
        ),
        Finding(
            id = "DOM-P0-02",
            severity = Severity.P0,
            verdict = Verdict.BROKEN,
            status = FixStatus.FIXED,
            title = "TaskLifecycleState persistence round-trip broken",
            file = "application/orchestration/AgentOrchestrator.kt:374,419",
            description = """
                AgentOrchestrator.persistTaskFinal writes stateStr = "DEGRADED", but
                "DEGRADED" is NOT a valid TaskLifecycleState enum value. On resume,
                TaskLifecycleState.valueOf("DEGRADED") throws IllegalArgumentException.
                A degraded task saved to Room cannot be resumed.
            """.trimIndent(),
            fix = "Add DEGRADED to TaskLifecycleState enum, OR persist COMPLETED with isDegraded flag (already persisted). Update resumeTask to read the flag.",
        ),
        Finding(
            id = "DOM-P0-03",
            severity = Severity.P0,
            verdict = Verdict.BROKEN,
            status = FixStatus.FIXED,
            title = "CapabilityGapAnalysis.candidateResourcesForMissing aliasing bug",
            file = "domain/core/capability/Capability.kt:411",
            description = """
                `candidateResourcesForMissing = candidateResourcesForPending` is evaluated at
                construction with candidateResourcesForPending = emptyMap() (Kotlin evaluates
                default args left-to-right). So candidateResourcesForMissing is ALWAYS empty
                unless explicitly passed. Silent data-loss for callers reading the field.
            """.trimIndent(),
            fix = "Remove the aliasing default; make candidateResourcesForMissing an explicit required-or-defaulted-to-emptyMap field.",
        ),
        Finding(
            id = "DOM-P0-04",
            severity = Severity.P0,
            verdict = Verdict.MISLEADING,
            status = FixStatus.FIXED,
            title = "OutcomeService required-output-keys escape hatch",
            file = "application/outcome/OutcomeService.kt:126",
            description = """
                `if (accumulatedEvidence.containsKey(requiredKey) || finalOutputText.isNotBlank())`
                — the `||` means ANY non-blank final output satisfies EVERY required output key,
                even when the evidence map does not contain the key. STRICT verification is
                effectively bypassed when any text is produced.
            """.trimIndent(),
            fix = "Drop the `|| finalOutputText.isNotBlank()` clause for STRICT strategy. Keep it only for PERMISSIVE strategy.",
        ),

        // ────────────────────────────────────────────────────────────────────
        // P0 — Correctness Bugs (Application)
        // ────────────────────────────────────────────────────────────────────
        Finding(
            id = "APP-P0-05",
            severity = Severity.P0,
            verdict = Verdict.STUB,
            status = FixStatus.FIXED,
            title = "listSessions() returns hardcoded empty list",
            file = "infrastructure/storage/SandboxWorkspaceStorageAdapter.kt:231-233",
            description = """
                `listSessions(projectId)` always returns Outcome.Success(emptyList()), even
                though saveSession() DOES write to Room. Sessions are persisted but never
                readable. Asymmetric persistence = invisible data loss.
            """.trimIndent(),
            fix = "Implement listSessions to query SessionDao by projectId. Add SessionDao.getSessionsByProject Flow query.",
        ),
        Finding(
            id = "APP-P0-06",
            severity = Severity.P0,
            verdict = Verdict.MISLEADING,
            status = FixStatus.FIXED,
            title = "listProjects() returns only the active project",
            file = "infrastructure/storage/SandboxWorkspaceStorageAdapter.kt:190-201",
            description = """
                The method name promises a list of projects, but it returns ONLY the active
                project (hardcoded id=1L) wrapped in a singleton list. Multi-project
                workspaces are not supported despite the schema allowing them.
            """.trimIndent(),
            fix = "Query ProjectDao.getAll() and return the full list.",
        ),
        Finding(
            id = "APP-P0-07",
            severity = Severity.P0,
            verdict = Verdict.BROKEN,
            status = FixStatus.FIXED,
            title = "AgentOrchestrator.resumeTask drops most TaskDefinition fields",
            file = "application/orchestration/AgentOrchestrator.kt:414-420",
            description = """
                resumeTask rebuilds TaskDefinition with ONLY 4 fields (id, agentId, rawPrompt,
                state). It drops specification, requirements, constraints, budget,
                successCriteria, currentStepIndex, outcomeSummary. The resumed task starts
                fresh from step 0 with default constraints — effectively a re-execution,
                not a resume.
            """.trimIndent(),
            fix = "Extend TaskEntity with all required fields (or JSON-serialize the TaskDefinition) and reconstruct fully in resumeTask.",
        ),
        Finding(
            id = "APP-P0-08",
            severity = Severity.P0,
            verdict = Verdict.STUB,
            status = FixStatus.FIXED,
            title = "ExecutionService.executePlanAction returns hardcoded string",
            file = "application/execution/ExecutionService.kt:860-882",
            description = """
                CREATE_PLAN and REPLAN actions return a fixed 3-line Arabic string. No real
                planner is invoked. The action type is effectively a placeholder.
            """.trimIndent(),
            fix = "Implement a TaskPlanner that decomposes the goal into a WorkflowPlan using LLM-driven decomposition (or rule-based fallback).",
        ),
        Finding(
            id = "APP-P0-09",
            severity = Severity.P0,
            verdict = Verdict.MISLEADING,
            status = FixStatus.FIXED,
            title = "SELECT_AGENT / DELEGATE actions are runtime no-ops",
            file = "application/execution/ExecutionService.kt:137-145",
            description = """
                These actions return a success string but never swap the executing agent in
                AgentOrchestrator. The selectedAgentId in outputData is never consumed.
                The orchestrator continues executing with the original agent.
            """.trimIndent(),
            fix = "Either implement real agent swapping (orchestrator pauses, swaps agent context, resumes) or remove these actions from the action space and document that agent assignment happens only at task creation.",
        ),

        // ────────────────────────────────────────────────────────────────────
        // P0 — Correctness Bugs (Infrastructure)
        // ────────────────────────────────────────────────────────────────────
        Finding(
            id = "INF-P0-10",
            severity = Severity.P0,
            verdict = Verdict.BROKEN,
            status = FixStatus.FIXED,
            title = "AppDatabase uses fallbackToDestructiveMigration",
            file = "infrastructure/persistence/AppDatabase.kt:69",
            description = """
                Any schema change in Phase 2+ will silently destroy all user data (projects,
                sessions, memory vectors, decision cases, radar items). This is the single
                most dangerous line in the codebase for a production-targeted app.
            """.trimIndent(),
            fix = "Replace with explicit Migration objects. Set exportSchema = true. Check in schemas/ JSON snapshots.",
        ),
        Finding(
            id = "INF-P0-11",
            severity = Severity.P0,
            verdict = Verdict.MISLEADING,
            status = FixStatus.FIXED,
            title = "McpClient.discoverServer returns Success(UNAVAILABLE) on errors",
            file = "infrastructure/mcp/McpClient.kt:125-131",
            description = """
                Exceptions and HTTP non-200 responses return Outcome.Success with
                health=UNAVAILABLE or DEGRADED. Errors masquerade as success — callers cannot
                distinguish "discovered but unhealthy" from "discovery failed".
            """.trimIndent(),
            fix = "Return Outcome.Error with structured McpDiscoveryFailure on exceptions and non-2xx responses.",
        ),
        Finding(
            id = "INF-P0-12",
            severity = Severity.P0,
            verdict = Verdict.BROKEN,
            status = FixStatus.FIXED,
            title = "GeminiLlmAdapter maps SYSTEM role to 'system' string",
            file = "infrastructure/llm/gemini/GeminiLlmAdapter.kt:152",
            description = """
                Gemini API rejects the 'system' role string in content(). System instructions
                must go through GenerativeModel(systemInstruction = ...). System prompts are
                silently mishandled — likely treated as user content or rejected.
            """.trimIndent(),
            fix = "Use GenerativeModel(systemInstruction = ...) API for system messages; map only user/assistant/model roles to content().",
        ),
        Finding(
            id = "INF-P0-13",
            severity = Severity.P0,
            verdict = Verdict.MISLEADING,
            status = FixStatus.FIXED,
            title = "OpenAiCompatibleAdapter.stream() is fake streaming",
            file = "infrastructure/llm/custom/OpenAiCompatibleAdapter.kt:124-145",
            description = """
                stream() calls generate() (blocking), then emits the entire response as a
                single ContentChunk with sequenceIndex = 0. No stream:true parameter sent,
                no SSE parsing, no chunked transfer. Streaming UI is a lie; UX is identical
                to non-streaming.
            """.trimIndent(),
            fix = "Send stream=true parameter; parse SSE data: lines; emit ContentChunk per parsed delta.",
        ),
        Finding(
            id = "INF-P0-14",
            severity = Severity.P0,
            verdict = Verdict.MISLEADING,
            status = FixStatus.FIXED,
            title = "OpenAiCompatibleDiscoveryAdapter claims fake local models",
            file = "infrastructure/llm/discovery/OpenAiCompatibleDiscoveryAdapter.kt:93-134",
            description = """
                getLocalFallbackModels() returns 2 fabricated models (llama-3.2-3b-instruct,
                qwen-2.5-coder-7b) with isLocalOnDevice = true. These models are NOT
                actually loaded on the device. UI displays them as available local models.
            """.trimIndent(),
            fix = "Either remove the fake local models OR relabel them as isLocalOnDevice = false, discoverySource = HARDCODED_FALLBACK.",
        ),

        // ────────────────────────────────────────────────────────────────────
        // P1 — Architectural Gaps (defer to appropriate phase)
        // ────────────────────────────────────────────────────────────────────
        Finding(
            id = "DOM-P1-15",
            severity = Severity.P1,
            verdict = Verdict.MISLEADING,
            status = FixStatus.DEFERRED,
            title = "CbrMdpEngine is not the MDP defined in cbr-mdp.txt",
            file = "domain/core/decision/CbrMdpEngine.kt",
            description = """
                KDoc claims "Real CBR-MDP". The code is a single-shot heuristic scorer with
                per-action-type EMA. There is no transition kernel P(s'|s,a), no Bellman
                operator, no Bayesian belief update, no value iteration. The formal CBR-MDP
                from cbr-mdp.txt is not implemented.
            """.trimIndent(),
            fix = "Phase 6: implement real MDP with transition model and Bayesian belief update, OR rename to HeuristicDecisionScorer and update KDoc honestly.",
        ),
        Finding(
            id = "DOM-P1-16",
            severity = Severity.P1,
            verdict = Verdict.PARTIAL,
            status = FixStatus.DEFERRED,
            title = "WorkflowEngine executes sequentially, not as DAG",
            file = "application/orchestration/WorkflowEngine.kt:48-182",
            description = """
                ExecutionMode SEQUENTIAL / DIRECTED_ACYCLIC_GRAPH / FAN_OUT_PARALLEL all
                execute identically (declaration-order, single-pass, skip-on-missing-deps
                without deferring). No topological sort, no parallel branches.
            """.trimIndent(),
            fix = "Phase 6: implement real DAG executor with topological sort, defer-not-skip, parallel branches for FAN_OUT_PARALLEL.",
        ),
        Finding(
            id = "DOM-P1-17",
            severity = Severity.P1,
            verdict = Verdict.PARTIAL,
            status = FixStatus.DEFERRED,
            title = "ResourceType enum duplicated (21 vs 6 values)",
            file = "domain/core/workspace/ResourceGraph.kt vs domain/core/resource/ResourceModels.kt",
            description = """
                workspace.ResourceType has 21 values (WORKSPACE, PROJECT, TASK, ...).
                resource.ResourceType has 6 values (LLM, SEARCH, EMBEDDING, TOOL, STORAGE,
                INTEGRATION). The 21-value enum is largely dead. Code uses the 6-value enum.
            """.trimIndent(),
            fix = "Phase 2: unify into a single ResourceType enum or remove the dead 21-value enum.",
        ),
        Finding(
            id = "DOM-P1-18",
            severity = Severity.P1,
            verdict = Verdict.PARTIAL,
            status = FixStatus.DEFERRED,
            title = "Dead TaskLifecycleState values",
            file = "domain/core/task/TaskModels.kt",
            description = """
                READY, PLANNING, WAITING, BLOCKED, REPLANNING, CANCELLED are declared but
                never set by any code path. Only RUNNING, COMPLETED, FAILED are used.
            """.trimIndent(),
            fix = "Phase 4: implement the full lifecycle state machine or remove the dead values.",
        ),

        // ────────────────────────────────────────────────────────────────────
        // P2 — Honesty Fixes (synthetic defaults → honest defaults)
        // ────────────────────────────────────────────────────────────────────
        Finding(
            id = "DOM-P2-19",
            severity = Severity.P2,
            verdict = Verdict.SYNTHETIC,
            status = FixStatus.FIXED,
            title = "ResourceRecord defaults to HEALTHY / runtimeSupported=true / ENABLED",
            file = "domain/core/resource/ResourceModels.kt:48-50",
            description = "Resources default to healthy/enabled before any verification.",
            fix = "Default healthStatus = UNKNOWN, runtimeSupported = false, lifecycleState = DISCOVERED.",
        ),
        Finding(
            id = "DOM-P2-20",
            severity = Severity.P2,
            verdict = Verdict.SYNTHETIC,
            status = FixStatus.FIXED,
            title = "McpServerDescriptor defaults health=HEALTHY, latencyMs=45L",
            file = "domain/core/extension/McpModels.kt",
            description = "New MCP server claims HEALTHY with 45ms latency without ping.",
            fix = "Default health = UNKNOWN, latencyMs = 0L, lastPingTimestampMs = null.",
        ),
        Finding(
            id = "DOM-P2-21",
            severity = Severity.P2,
            verdict = Verdict.SYNTHETIC,
            status = FixStatus.FIXED,
            title = "IntegrationDescriptor defaults health=HEALTHY",
            file = "domain/core/extension/IntegrationModels.kt",
            description = "Integration that has never been connected shouldn't claim HEALTHY.",
            fix = "Default health = UNKNOWN. isConnected = false is already correct.",
        ),
        Finding(
            id = "DOM-P2-22",
            severity = Severity.P2,
            verdict = Verdict.SYNTHETIC,
            status = FixStatus.FIXED,
            title = "SkillManifest defaults isVerified=true",
            file = "domain/core/extension/SkillModels.kt",
            description = "Every skill is marked verified by default with no verification process.",
            fix = "Default isVerified = false. Set true only after explicit verification.",
        ),
        Finding(
            id = "DOM-P2-23",
            severity = Severity.P2,
            verdict = Verdict.SYNTHETIC,
            status = FixStatus.FIXED,
            title = "RadarItem defaults confidence=0.95f",
            file = "domain/core/radar/RadarModels.kt",
            description = "Every radar item claims 0.95 confidence by default with no measurement.",
            fix = "Default confidence = 0.0f; require callers to set explicit values.",
        ),
        Finding(
            id = "DOM-P2-24",
            severity = Severity.P2,
            verdict = Verdict.SYNTHETIC,
            status = FixStatus.FIXED,
            title = "ModelDescriptor defaults confidence=0.95f, discoverySource='AUTOMATIC_DISCOVERY'",
            file = "domain/core/model/ModelModels.kt:46-47",
            description = "Model descriptors default to high confidence and discovery source without measurement.",
            fix = "Default confidence = 0.0f, discoverySource = 'UNSPECIFIED'.",
        ),
        Finding(
            id = "DOM-P2-25",
            severity = Severity.P2,
            verdict = Verdict.SYNTHETIC,
            status = FixStatus.FIXED,
            title = "CbrMdpEngine.actionSuccessEstimates initialized to 0.85f",
            file = "domain/core/decision/CbrMdpEngine.kt:20-22",
            description = "Synthetic 0.85 prior on every action type, not derived from data.",
            fix = "Initialize to 0.5f (uninformative prior).",
        ),

        // ────────────────────────────────────────────────────────────────────
        // P3 — Type Safety / Concurrency
        // ────────────────────────────────────────────────────────────────────
        Finding(
            id = "APP-P3-26",
            severity = Severity.P3,
            verdict = Verdict.BROKEN,
            status = FixStatus.FIXED,
            title = "ComponentRegistry default IDs race condition",
            file = "application/registry/ComponentRegistry.kt:37-39",
            description = """
                defaultLlmProviderId / defaultSearchProviderId / defaultEmbeddingProviderId
                are plain `var` read/written from concurrent register/unregister calls.
            """.trimIndent(),
            fix = "Use AtomicReference<String?>.",
        ),
        Finding(
            id = "APP-P3-27",
            severity = Severity.P3,
            verdict = Verdict.BROKEN,
            status = FixStatus.FIXED,
            title = "RagPipelineService.chunks not thread-safe",
            file = "application/rag/RagPipelineService.kt:29",
            description = """
                `chunks: MutableList<DocumentChunk>` is mutated from Dispatchers.Default in
                ingestDocument(). Concurrent ingest calls will race.
            """.trimIndent(),
            fix = "Use CopyOnWriteArrayList or Mutex with withLock.",
        ),
        Finding(
            id = "APP-P3-28",
            severity = Severity.P3,
            verdict = Verdict.BROKEN,
            status = FixStatus.OPEN,
            title = "ProviderControlPlaneService uses runBlocking inside coroutine",
            file = "application/provider/ProviderControlPlaneService.kt:62,71,80",
            description = """
                runBlocking { providerRepository.getSecretForProvider(...) } is called from
                inside a Dispatchers.Default coroutine. Blocks the worker thread; can
                deadlock under structured concurrency.
            """.trimIndent(),
            fix = "Refactor to fetch secrets via suspend lambda before createLlmAdapter.",
        ),
        Finding(
            id = "APP-P3-29",
            severity = Severity.P3,
            verdict = Verdict.BROKEN,
            status = FixStatus.OPEN,
            title = "IntelligenceRadarPipeline state-persistence divergence",
            file = "application/radar/IntelligenceRadarPipeline.kt:265-268",
            description = """
                advanceEvolutionStage updates in-memory state synchronously but launches
                dao.updateStage as fire-and-forget coroutine. If process dies, state-persistence
                divergence. Also auto-sets governanceApproved = true and securityAuditPassed
                = true for late stages without any audit.
            """.trimIndent(),
            fix = "Persist synchronously OR use async + await. Stop auto-approving governance; require explicit audit evidence.",
        ),

        // ────────────────────────────────────────────────────────────────────
        // P4 — Dead Code / Documentation
        // ────────────────────────────────────────────────────────────────────
        Finding(
            id = "DOM-P4-30",
            severity = Severity.P4,
            verdict = Verdict.DEAD,
            status = FixStatus.OPEN,
            title = "domain.models.Models.kt is legacy parallel model",
            file = "domain/models/Models.kt",
            description = """
                Pre-dates domain.core.*. Contains a flat sequential WorkflowPlan incompatible
                with the new DAG WorkflowPlan. BeliefState is modeled here but never consumed.
            """.trimIndent(),
            fix = "Mark @Deprecated with migration pointers to domain.core.*.",
        ),
        Finding(
            id = "UI-P4-31",
            severity = Severity.P4,
            verdict = Verdict.DEAD,
            status = FixStatus.OPEN,
            title = "CapabilitiesScreen and MemoryRagScreen are dead code",
            file = "presentation/ui/screens/CapabilitiesScreen.kt, MemoryRagScreen.kt",
            description = "Zero imports from any other file. Unreachable from navigation.",
            fix = "Either delete or wire into navigation as dedicated tabs.",
        ),
        Finding(
            id = "UI-P4-32",
            severity = Severity.P4,
            verdict = Verdict.DEAD,
            status = FixStatus.OPEN,
            title = "sessionRepository injected into MainViewModel but never used",
            file = "presentation/viewmodel/MainViewModel.kt:67",
            description = "Dead DI. SessionRepositoryPort is wired for nothing.",
            fix = "Remove injection OR wire it to a Sessions tab.",
        ),

        // ────────────────────────────────────────────────────────────────────
        // UI Layer — Phase 7 scope (deferred)
        // ────────────────────────────────────────────────────────────────────
        Finding(
            id = "UI-P0-33",
            severity = Severity.P0,
            verdict = Verdict.MISSING,
            status = FixStatus.DEFERRED,
            title = "No Settings screen exists in the codebase",
            file = "(missing)",
            description = """
                Spec requires 15+ settings domains: AI, Models, Providers, Network, Privacy,
                Storage, Memory, RAG, Agents, Security, Tools, MCP, Extensions, Workspaces,
                Performance, Diagnostics. Grep confirms: ZERO files named *Settings*.
            """.trimIndent(),
            fix = "Phase 7: create SettingsScreen with all 16 spec categories.",
        ),
        Finding(
            id = "UI-P0-34",
            severity = Severity.P0,
            verdict = Verdict.MISSING,
            status = FixStatus.DEFERRED,
            title = "No Evidence / Artifacts / Verification UI",
            file = "(missing)",
            description = "Grep returns zero hits for Evidence/Artifact UI components.",
            fix = "Phase 7: add EvidencePanel, ArtifactsPanel, VerificationReportCard composables.",
        ),
        Finding(
            id = "UI-P0-35",
            severity = Severity.P0,
            verdict = Verdict.MISLEADING,
            status = FixStatus.DEFERRED,
            title = "AgentStudio is a chat console, not an agent designer",
            file = "presentation/ui/screens/AgentStudioScreen.kt",
            description = """
                Spec requires Identity / Role / Goal / Policy / Authority / Model / Tools /
                Memory / Workspace scope / Budget / Evaluation. None of these are editable.
                The 3 agents are hardcoded in MainViewModel.initializeAgents().
            """.trimIndent(),
            fix = "Phase 7: rename current to AgentConsoleScreen; create new AgentStudioScreen as designer with all 10 fields.",
        ),
        Finding(
            id = "UI-P0-36",
            severity = Severity.P0,
            verdict = Verdict.MISSING,
            status = FixStatus.DEFERRED,
            title = "No Workspace > Project > Task > Conversation hierarchy",
            file = "presentation/ui/MainAppScreen.kt",
            description = """
                UI is flat 8-tab nav. activeProject is never assigned. activeTasks
                permanently empty. No workspace switcher, no project selector, no task list,
                no conversation history. Spec calls for unified hierarchy.
            """.trimIndent(),
            fix = "Phase 7: replace flat nav with Workspace drawer > Project selector > Task list > Conversation surface.",
        ),
    )

    // ────────────────────────────────────────────────────────────────────────
    // Aggregates
    // ────────────────────────────────────────────────────────────────────────

    val totalFindings: Int get() = findings.size

    val openCount: Int get() = findings.count { it.status == FixStatus.OPEN }
    val fixedCount: Int get() = findings.count { it.status == FixStatus.FIXED }
    val inProgressCount: Int get() = findings.count { it.status == FixStatus.IN_PROGRESS }
    val deferredCount: Int get() = findings.count { it.status == FixStatus.DEFERRED }

    fun findingsBySeverity(severity: Severity): List<Finding> =
        findings.filter { it.severity == severity }

    fun findingsByStatus(status: FixStatus): List<Finding> =
        findings.filter { it.status == status }

    /**
     * Runtime truth summary — what is REAL vs FAKE in this codebase today.
     * Used as the Phase 1 Exit Gate input.
     */
    val runtimeTruthSummary: RuntimeTruthSummary = RuntimeTruthSummary(
        realComponents = listOf(
            "Outcome<T,F> monad with Success/Degraded/Error",
            "Capability taxonomy with 19 types, 10 categories, prerequisites, evidence contracts",
            "ResourceCapabilityGraph bipartite matching with network/policy enforcement",
            "SecurityGuardService with 4-layer policy + AES-GCM secret redaction",
            "McpClient JSON-RPC 2.0 over HTTP (initialize handshake still missing)",
            "ProviderControlPlaneService Room Flow sync with real ping validation",
            "RuntimeAdapterResolver strict 6-check validation",
            "Room persistence for tasks, providers, memory, decision cases, radar items",
            "EncryptedSecretStorageAdapter AndroidKeyStore AES-GCM (fallback weak)",
            "GeminiLlmAdapter real Firebase AI SDK streaming (system role broken)",
            "TavilySearchAdapter real Tavily API",
            "MultiSourceSearchAdapter Tavily + Wikipedia + workspace fallback",
            "GitHubReleasesRadarSource + RssFeedRadarSource real fetches",
            "FileSystemTool real sandbox read/write/list/delete",
            "SafeDiagnosticsTool real JVM metrics",
            "CleanArchitectureScaffolderSkill real file generation",
            "SecurityAuditorSkill real regex static analysis",
            "AgentOrchestrator closed-loop DECIDE-EXECUTE-OBSERVE-BELIEF-REDECIDE",
            "DecisionService capability-grounded candidate generation + governance enforcement",
            "ExecutionService real LLM streaming + tool-callback loop",
            "WorkflowEngine real cycle detection + sequential dep resolution",
            "OutcomeService structured verification with CapabilityEvidenceRegistry",
        ),
        partialComponents = listOf(
            "CbrMdpEngine (heuristic, not real MDP)",
            "WorkflowEngine (sequential, not DAG)",
            "RagPipelineService (hashing embeddings, not semantic)",
            "ExtensionManager (real MCP, fake plugin loading)",
            "IntelligenceRadarPipeline (stages 3-8 not implemented)",
            "AgentOrchestrator (resumeTask broken)",
            "GeminiLlmAdapter (system role broken, config ignored)",
            "OpenAiCompatibleAdapter (fake streaming, all errors → timeout)",
            "OpenAiCompatibleDiscoveryAdapter (fake local fallback models)",
            "SandboxWorkspaceStorageAdapter (listSessions stub, listProjects misleading)",
            "ProviderAdapterFactory (embedding adapter local-only, accepts 400/405)",
        ),
        fakeOrMissing = listOf(
            "LocalDeterministicEmbeddingAdapter (hashing trick, not semantic)",
            "listSessions() (returns emptyList())",
            "executePlanAction (returns hardcoded string)",
            "SELECT_AGENT/DELEGATE (runtime no-op)",
            "Plugin loading mechanism (no classloader/DEX/reflection)",
            "Intelligence Radar stages 3-8 (UNDERSTAND/CLASSIFY/RELEVANCE/CAPABILITY/EVALUATE)",
            "Real TaskPlanner (CREATE_PLAN returns stub)",
            "Settings UI (zero files)",
            "Evidence/Artifacts/Verification UI (zero files)",
            "Agent Studio designer (chat console only)",
            "Workspace>Project>Task hierarchy (flat nav)",
        ),
    )

    // ────────────────────────────────────────────────────────────────────────
    // Data classes
    // ────────────────────────────────────────────────────────────────────────

    enum class Severity { P0, P1, P2, P3, P4 }
    enum class Verdict { REAL, PARTIAL, MOCK, STUB, DEMO, SYNTHETIC, UNUSED, DEAD, MISLEADING, BROKEN, MISSING }
    enum class FixStatus { OPEN, IN_PROGRESS, FIXED, WONT_FIX, DEFERRED }

    data class Finding(
        val id: String,
        val severity: Severity,
        val verdict: Verdict,
        val status: FixStatus,
        val title: String,
        val file: String,
        val description: String,
        val fix: String,
    )

    data class RuntimeTruthSummary(
        val realComponents: List<String>,
        val partialComponents: List<String>,
        val fakeOrMissing: List<String>,
    )
}
