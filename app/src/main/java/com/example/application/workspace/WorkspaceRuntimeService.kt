package com.example.application.workspace

import com.example.application.execution.ExecutionHost
import com.example.domain.core.network.NetworkPolicy
import com.example.domain.core.workspace.Workspace
import com.example.infrastructure.persistence.dao.ProjectDao
import com.example.infrastructure.persistence.dao.WorkspaceDao
import com.example.infrastructure.persistence.entities.ProjectEntity
import com.example.infrastructure.persistence.entities.WorkspaceEntity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import java.util.UUID

/**
 * GAP-CLOSURE P0-03: thrown by [WorkspaceRuntimeService.requireActiveWorkspaceId]
 * when NO workspace is active (bootstrap incomplete or failed). Previously
 * the method fail-OPENED to the literal "default" — data written before
 * bootstrap landed in a scope nobody owns. Fail-closed is the honest
 * contract; scope-less callers should use [activeWorkspaceIdOrNull] (null =
 * unattributed, never a fabricated id).
 */
class NoActiveWorkspaceStateException(message: String) : IllegalStateException(message)

/**
 * Phase 2 — WorkspaceRuntimeService
 * =================================
 *
 * Turns `Workspace` from a Domain-only model into a first-class runtime citizen
 * with persistence, multi-workspace switching, and lifecycle management.
 *
 * Before Phase 2: the app hardcoded a single workspace (project id=1L) and
 * `activeProject` in UiState was never assigned. `listProjects()` returned
 * only the default project. There was no concept of "switch workspace" or
 * "create new workspace" — the user was always in the implicit default.
 *
 * After Phase 2:
 *   - Users can create multiple workspaces (e.g. "Personal", "Work", "Research")
 *   - Each workspace has its own NetworkPolicy, AutonomyPolicy, and settings
 *   - Each workspace tracks its own active project
 *   - The active workspace is persisted and restored on app restart
 *   - Switching workspaces emits a StateFlow update the UI can observe
 *   - The default workspace is auto-created on first launch so existing flows
 *     continue to work (the implicit project id=1L becomes the active project
 *     of the default workspace)
 *
 * Threading: All public methods are safe to call from any dispatcher. State
 * mutations are guarded by a Mutex to prevent races between concurrent
 * create/switch/delete operations. The active-workspace StateFlow is the
 * single source of truth for "which workspace am I currently in?".
 */
class WorkspaceRuntimeService(
    private val workspaceDao: WorkspaceDao,
    /**
     * GAP-CLOSURE P0-04: optional project DAO so every NEW workspace gets its
     * OWN sandbox project row instead of falling back to the legacy shared
     * project id=1L (cross-workspace data bleed).
     */
    private val projectDao: ProjectDao? = null,
    /** Resolves the sandbox root directory for a project id (wired from context by AppContainer). */
    private val projectRootPathResolver: (Long) -> String = { id -> "workspaces/proj_$id" },
    private val coroutineScope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
    /**
     * REPAIR ORDER §3A — the bootstrap state machine. When provided, the
     * service's init{} bootstrap delegates to the orchestrator (instead of
     * the old fire-and-forget, non-transactional, non-reconciling path) and
     * exposes [bootstrapState] as the SINGLE startup truth for the UI and
     * for project-dependent feature gating. Null = legacy JVM-test wiring.
     */
    private val bootstrapOrchestrator: com.example.application.bootstrap.WorkspaceBootstrapOrchestrator? = null,
    /**
     * REPAIR ORDER §5 — the project lifecycle runtime. When provided,
     * project operations delegate here (WorkspaceRuntimeService is NOT a
     * monolithic project manager). Null = legacy JVM-test wiring.
     */
    private val projectRuntime: com.example.application.project.ProjectRuntimeService? = null,
    /**
     * GAP-16 (Design Closure 2026): transaction runner for every multi-write
     * workspace mutation (create/switch/delete). Default = identity (honest
     * for pure-JVM tests with fake DAOs); production wires Room's
     * `database::withTransaction` so a mid-sequence crash leaves NO partial
     * state (previously createWorkspace could persist a deactivated world
     * with no new row, or a row with no project).
     */
    private val transactionRunner: suspend (suspend () -> Unit) -> Unit = { it() }
) {
    private val _activeWorkspace = MutableStateFlow<Workspace?>(null)
    val activeWorkspace: StateFlow<Workspace?> = _activeWorkspace.asStateFlow()

    private val _allWorkspaces = MutableStateFlow<List<Workspace>>(emptyList())
    val allWorkspaces: StateFlow<List<Workspace>> = _allWorkspaces.asStateFlow()

    /** REPAIR ORDER §3A — observable startup state machine. */
    val bootstrapState: StateFlow<com.example.application.bootstrap.BootstrapState>
        get() = bootstrapOrchestrator?.state ?: MutableStateFlow(com.example.application.bootstrap.BootstrapState.BOOTSTRAPPING)

    private val mutex = Mutex()

    init {
        // Bootstrap on first init: ensure at least one workspace exists and is active.
        // REPAIR ORDER §3A: when the state-machine orchestrator is wired it
        // OWNS the bootstrap (transactional workspace+project creation,
        // deterministic restoration, stale-reference reconciliation); this
        // collector then refreshes the runtime flows from the resulting
        // authoritative state. Idempotent by construction.
        coroutineScope.launch {
            runCatching {
                bootstrapOrchestrator?.bootstrap()
                    ?: bootstrapDefaultWorkspaceIfNeeded()
                refreshAllWorkspaces()
                refreshActiveWorkspace()
            }.onFailure {
                // Honest failure: the bootstrap state machine surfaces it;
                // legacy wiring (no orchestrator) keeps the old behavior.
            }
        }
    }

    /**
     * Ensures a default workspace exists on first launch. Idempotent — if a
     * workspace is already present and active, this is a no-op.
     *
     * The default workspace is named "مساحة العمل الافتراضية" (Default Workspace)
     * and uses HYBRID network policy + SUPERVISED autonomy policy — matching
     * the previous hardcoded behaviour so existing users see no regression.
     *
     * P0 CONVERGENCE (audit step 12 §6): the default workspace now gets its
     * OWN real sandbox project row (created through [ProjectDao], exactly
     * like every other workspace) instead of pointing at the LEGACY shared
     * project id=1L. When no project DAO is wired (pure-JVM tests), the
     * workspace binds NO project (lastActiveProjectId = null — the honest
     * "not bound" state) rather than fabricating an implicit 1L. Existing
     * installs referencing 1L are repaired by MIGRATION_11_TO_12, which
     * materializes the reference as a real owned row.
     */
    private suspend fun bootstrapDefaultWorkspaceIfNeeded() {
        val existing = workspaceDao.getAllWorkspaces()
        if (existing.isEmpty()) {
            val now = System.currentTimeMillis()
            val defaultId = "default"
            // P0-04/P0 convergence: the default workspace's OWN project row —
            // never the legacy implicit projectId=1L.
            val ownProjectId: Long? = projectDao?.let { dao ->
                runCatching {
                    val provisional = ProjectEntity(
                        name = "مشروع مساحة العمل الافتراضية",
                        description = "مشروع sandbox مملوك لمساحة العمل الافتراضية",
                        rootPath = "",
                        createdAtEpochMs = now,
                        updatedAtEpochMs = now,
                        workspaceId = defaultId
                    )
                    val generatedId = dao.insertProject(provisional)
                    dao.updateProject(
                        provisional.copy(
                            id = generatedId,
                            rootPath = projectRootPathResolver(generatedId)
                        )
                    )
                    generatedId
                }.getOrNull()
            }
            workspaceDao.insertOrUpdate(
                WorkspaceEntity(
                    id = defaultId,
                    name = "مساحة العمل الافتراضية",
                    description = "مساحة العمل الأساسية المعزولة لتنسيق الوكلاء والملفات",
                    networkPolicy = NetworkPolicy.HYBRID.name,
                    autonomyPolicy = "SUPERVISED",
                    settingsJson = "{}",
                    isActive = true,
                    lastActiveProjectId = ownProjectId,
                    createdAtEpochMs = now,
                    lastAccessedEpochMs = now
                )
            )
        } else if (existing.none { it.isActive }) {
            // No active workspace — activate the most recently accessed one.
            val mostRecent = existing.maxByOrNull { it.lastAccessedEpochMs }
            if (mostRecent != null) {
                workspaceDao.deactivateAll()
                workspaceDao.setActive(mostRecent.id, System.currentTimeMillis())
            }
        }
    }

    /**
     * Creates a new workspace. Returns the created Workspace domain object.
     * The new workspace becomes the active workspace automatically.
     *
     * GAP-CLOSURE P0-04: the workspace gets its OWN dedicated sandbox project
     * (never the legacy shared projectId=1L). Every workspace — default or
     * not — owns its project; the "default" id has no special entitlement.
     *
     * GAP-16 (Design Closure 2026): project insert + deactivateAll + the
     * workspace row insert run in ONE transaction ([transactionRunner]) —
     * an injected mid-sequence failure leaves NO orphan (no deactivated
     * world, no half-created workspace).
     */
    suspend fun createWorkspace(
        name: String,
        description: String,
        networkPolicy: NetworkPolicy = NetworkPolicy.HYBRID,
        autonomyPolicy: String = "SUPERVISED",
        settings: Map<String, String> = emptyMap()
    ): Workspace = mutex.withLock {
        val now = System.currentTimeMillis()
        val id = "ws_" + UUID.randomUUID().toString().take(12)

        // P0-04: per-workspace sandbox project — real isolation, explicitly owned.
        // GAP-16: project row + deactivation + the workspace row are ONE
        // transaction — a mid-sequence failure leaves NO partial state (no
        // orphan project, no deactivated world without a new row, no
        // workspace without its required project). A project-insert failure
        // now FAILS the whole creation (fail-closed) instead of silently
        // creating a project-less workspace.
        transactionRunner {
            val ownProjectId: Long? = projectDao?.let { dao ->
                val provisional = ProjectEntity(
                    name = "مشروع $name",
                    description = description,
                    rootPath = "",
                    createdAtEpochMs = now,
                    updatedAtEpochMs = now,
                    workspaceId = id
                )
                val generatedId = dao.insertProject(provisional)
                dao.updateProject(
                    provisional.copy(
                        id = generatedId,
                        rootPath = projectRootPathResolver(generatedId)
                    )
                )
                generatedId
            }

            workspaceDao.insertOrUpdate(
                WorkspaceEntity(
                    id = id,
                    name = name,
                    description = description,
                    networkPolicy = networkPolicy.name,
                    autonomyPolicy = autonomyPolicy,
                    settingsJson = encodeSettings(settings),
                    isActive = true,
                    lastActiveProjectId = ownProjectId,
                    createdAtEpochMs = now,
                    lastAccessedEpochMs = now
                )
            )
            workspaceDao.deactivateAll()
            workspaceDao.setActive(id, now)
        }
        refreshAllWorkspaces()
        refreshActiveWorkspace()
        // FIX R-2 (audit c03919d): previously `_activeWorkspace.value!!` — a
        // null active workspace (e.g. DAO refresh failure right after insert)
        // crashed with a NullPointerException. Now an explicit honest error.
        _activeWorkspace.value
            ?: throw IllegalStateException(
                "Workspace '$id' was persisted but could not be reloaded as active — " +
                    "database refresh failed after insert."
            )
    }

    /**
     * Switches the active workspace to the one with the given id.
     * Returns true if the switch succeeded, false if the workspace doesn't exist.
     */
    suspend fun switchWorkspace(workspaceId: String): Boolean = mutex.withLock {
        val target = workspaceDao.getWorkspaceById(workspaceId) ?: return@withLock false
        // GAP-16 (Design Closure 2026): the switch is ONE transaction and the
        // target's project pin is RECONCILED IMMEDIATELY (previously the stale
        // lastActiveProjectId survived until the next process bootstrap):
        //   - pin resolves (exists + ACTIVE + owned by the target) → kept;
        //   - pin stale (deleted/archived/trashed/foreign) → repaired to the
        //     most recently updated ACTIVE OWNED project, else cleared.
        // Same reconciliation semantics as the bootstrap state machine
        // (REPAIR ORDER §3A root cause 3), applied at switch time.
        transactionRunner {
            workspaceDao.deactivateAll()
            workspaceDao.setActive(workspaceId, System.currentTimeMillis())
        }
        val dao = projectDao
        if (dao != null) {
            val pinned = target.lastActiveProjectId
            val resolvable = pinned?.let { dao.resolvableProjectForWorkspace(it, workspaceId) }
            if (resolvable == null) {
                val replacement = dao.mostRecentActiveProjectForWorkspace(workspaceId)
                workspaceDao.setActiveProject(workspaceId, replacement?.id, System.currentTimeMillis())
            }
        }
        refreshAllWorkspaces()
        refreshActiveWorkspace()
        true
    }

    /**
     * Updates the active project for the current workspace. Pass null to clear
     * the active project (e.g. when the user deletes the project).
     *
     * REPAIR ORDER §5: when the project runtime is wired, selection is
     * validated by it (the project must be ACTIVE and owned by the
     * workspace — a stale/archived/foreign reference is REJECTED, which
     * is the reconciliation input of the bootstrap state machine).
     */
    suspend fun setActiveProject(projectId: Long?) {
        val active = _activeWorkspace.value ?: return
        if (projectId != null && projectId > 0) {
            val runtime = projectRuntime
            if (runtime != null) {
                if (!runtime.selectProject(active.id, projectId)) {
                    return // rejected: stale/archived/foreign — honest no-op, reconciliation handles the rest
                }
                _activeWorkspace.update { it?.copy(activeProjectId = projectId) }
                return
            }
        }
        workspaceDao.setActiveProject(active.id, projectId, System.currentTimeMillis())
        _activeWorkspace.update { it?.copy(activeProjectId = projectId ?: 0L) }
    }

    /**
     * Updates the network policy of the active workspace. Emits a new
     * activeWorkspace StateFlow value so the UI reacts immediately.
     */
    suspend fun updateNetworkPolicy(policy: NetworkPolicy) {
        val active = _activeWorkspace.value ?: return
        val now = System.currentTimeMillis()
        val entity = workspaceDao.getWorkspaceById(active.id) ?: return
        workspaceDao.update(entity.copy(networkPolicy = policy.name, lastAccessedEpochMs = now))
        _activeWorkspace.update { it?.copy(networkPolicy = policy) }
    }

    /**
     * REPAIR ORDER §20 — updates the AUTHORITATIVE autonomy policy of the
     * active workspace (persisted column, the single governance authority
     * consumed by the execution pipeline). Previously NO update path
     * existed: the column was frozen at creation and the UI toggle was
     * decorative (UI-local state with zero runtime consumers).
     */
    suspend fun updateAutonomyPolicy(policy: com.example.domain.core.task.AutonomyPolicy) {
        val active = _activeWorkspace.value ?: return
        workspaceDao.updateAutonomyPolicy(active.id, policy.name, System.currentTimeMillis())
        // Refresh so the surfaced domain model carries the new policy.
        refreshActiveWorkspace()
    }

    /**
     * REPAIR ORDER §20 — pinned-workspace policy lookup (suspend, direct
     * DAO read). Used by the orchestrator at EXECUTION LAUNCH to resolve
     * the governing policy of the workspace the execution is pinned to —
     * never the currently-active StateFlow (mid-run switches cannot
     * change a live execution's governance).
     */
    suspend fun autonomyPolicyForWorkspace(workspaceId: String): com.example.domain.core.task.AutonomyPolicy? {
        return workspaceDao.autonomyPolicyFor(workspaceId)
            ?.let { name -> runCatching { com.example.domain.core.task.AutonomyPolicy.valueOf(name) }.getOrNull() }
    }

    /**
     * Updates settings on the active workspace (merges with existing settings).
     */
    suspend fun updateSettings(updates: Map<String, String>) {
        val active = _activeWorkspace.value ?: return
        val now = System.currentTimeMillis()
        val entity = workspaceDao.getWorkspaceById(active.id) ?: return
        val merged = decodeSettings(entity.settingsJson) + updates
        workspaceDao.update(entity.copy(settingsJson = encodeSettings(merged), lastAccessedEpochMs = now))
        _activeWorkspace.update { it?.copy(settings = merged) }
    }

    /**
     * Renames a workspace.
     */
    suspend fun renameWorkspace(workspaceId: String, newName: String, newDescription: String? = null): Boolean {
        return mutex.withLock {
            val entity = workspaceDao.getWorkspaceById(workspaceId) ?: return@withLock false
            val updated = entity.copy(
                name = newName,
                description = newDescription ?: entity.description,
                lastAccessedEpochMs = System.currentTimeMillis()
            )
            workspaceDao.update(updated)
            refreshAllWorkspaces()
            if (entity.isActive) refreshActiveWorkspace()
            true
        }
    }

    /**
     * Deletes a workspace. Refuses to delete if it's the only workspace left
     * (returns false). If the deleted workspace was active, activates the most
     * recently accessed remaining workspace.
     *
     * P1-14 (audit 2026 §7/§25 — no execution drain before deletion):
     * executions attributed to this workspace are CANCELLED AND DRAINED
     * first. If they cannot be drained within the bounded timeout the
     * deletion is REFUSED (returns false) — a live execution must never be
     * orphaned above a deleted workspace root.
     */
    suspend fun deleteWorkspace(workspaceId: String): Boolean {
        // P1-14: drain this workspace's executions BEFORE taking the lock
        // (draining joins the jobs; a job that calls back into this service
        // must never wait on the mutex we hold — that would deadlock). The
        // authoritative emptiness re-check happens INSIDE the lock below.
        ExecutionHost.drainWorkspace(workspaceId, drainTimeoutMs)
        return mutex.withLock {
            val all = workspaceDao.getAllWorkspaces()
            if (all.size <= 1) return@withLock false // never let the user delete the last workspace
            val target = all.firstOrNull { it.id == workspaceId } ?: return@withLock false
            // P1-14 fail-closed gate: if executions are STILL live for this
            // workspace (started after the drain, or refusing to die), the
            // deletion is REFUSED — a live execution must never be orphaned
            // above a deleted workspace root.
            if (ExecutionHost.executionsFor(workspaceId).isNotEmpty()) return@withLock false
            // GAP-16: row delete + successor activation are ONE transaction —
            // a crash between them previously left ZERO active workspaces
            // (the "no project associated" startup failure).
            transactionRunner {
                workspaceDao.deleteById(workspaceId)
                if (target.isActive) {
                    val nextActive = all.filter { it.id != workspaceId }.maxByOrNull { it.lastAccessedEpochMs }
                    if (nextActive != null) {
                        workspaceDao.deactivateAll()
                        workspaceDao.setActive(nextActive.id, System.currentTimeMillis())
                    }
                }
            }
            refreshAllWorkspaces()
            refreshActiveWorkspace()
            true
        }
    }

    /** P1-14: bounded drain wait for [deleteWorkspace] (overridable in tests). */
    var drainTimeoutMs: Long = 5_000L

    /**
     * GAP-CLOSURE P0-03 — FAIL-CLOSED accessor: the id of the ACTIVE
     * workspace, or [NoActiveWorkspaceStateException] when none is active
     * (bootstrap incomplete/failed).
     *
     * Previously this method fail-OPENED to the literal `"default"`, so
     * pre-bootstrap writes landed in a scope nobody owns. Runtime paths that
     * NEED a workspace must fail honestly; observability paths that can
     * honestly attribute "none" should use [activeWorkspaceIdOrNull].
     */
    fun requireActiveWorkspaceId(): String {
        return _activeWorkspace.value?.id
            ?: throw NoActiveWorkspaceStateException(
                "NO_ACTIVE_WORKSPACE: لم تكتمل تهيئة مساحة العمل النشطة بعد — رفض الوصول بدلاً من إرجاع معرّف افتراضي قد يكتب البيانات في نطاق لا يملكه أحد."
            )
    }

    /**
     * GAP-CLOSURE P0-03 — honest nullable accessor for observability paths:
     * null means UNATTRIBUTED (never a fabricated "default" id).
     */
    fun activeWorkspaceIdOrNull(): String? = _activeWorkspace.value?.id

    /**
     * GAP-CLOSURE P0-04 — the active workspace's OWN sandbox project id
     * (null or non-positive = no project bound; NEVER an implicit 1L).
     */
    fun activeProjectIdOrNull(): Long? =
        _activeWorkspace.value?.activeProjectId?.takeIf { it > 0L }

    /**
     * GAP-CLOSURE P0-02 — bootstrap-aware suspend accessor: waits (bounded)
     * for the workspace bootstrap to complete, then returns the active id
     * or null when the timeout expires (the caller decides how to fail).
     */
    suspend fun awaitActiveWorkspaceId(timeoutMs: Long = 5_000L): String? {
        _activeWorkspace.value?.id?.let { return it }
        return withTimeoutOrNull(timeoutMs) {
            _activeWorkspace.first { ws -> ws != null }?.id
        }
    }

    private suspend fun refreshAllWorkspaces() {
        val entities = workspaceDao.getAllWorkspaces()
        _allWorkspaces.value = entities.map { it.toDomain() }
    }

    private suspend fun refreshActiveWorkspace() {
        val entity = workspaceDao.getActiveWorkspace()
        _activeWorkspace.value = entity?.toDomain()
    }

    private fun WorkspaceEntity.toDomain(): Workspace = Workspace(
        id = id,
        name = name,
        description = description,
        activeProjectId = lastActiveProjectId ?: 0L,
        networkPolicy = try {
            NetworkPolicy.valueOf(networkPolicy)
        } catch (_: IllegalArgumentException) {
            NetworkPolicy.HYBRID
        },
        settings = decodeSettings(settingsJson) + mapOf(
            // The workspace's AUTHORITATIVE autonomy policy (defect family 4:
            // agent autonomy governance derives from the workspace's stored
            // policy — the entity column exists since v1 but was never
            // surfaced to the domain model, so nothing could enforce it).
            "autonomyPolicy" to autonomyPolicy
        ),
        createdAtTimestampMs = createdAtEpochMs,
        lastAccessedTimestampMs = lastAccessedEpochMs
    )

    private fun decodeSettings(json: String): Map<String, String> {
        if (json.isBlank() || json == "{}") return emptyMap()
        return try {
            val arr = org.json.JSONObject(json)
            val out = mutableMapOf<String, String>()
            for (key in arr.keys()) {
                out[key] = arr.getString(key)
            }
            out
        } catch (_: Exception) {
            emptyMap()
        }
    }

    private fun encodeSettings(settings: Map<String, String>): String {
        if (settings.isEmpty()) return "{}"
        return try {
            val obj = org.json.JSONObject()
            settings.forEach { (k, v) -> obj.put(k, v) }
            obj.toString()
        } catch (_: Exception) {
            "{}"
        }
    }
}
