package com.example.application.workspace

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
    private val coroutineScope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
) {
    private val _activeWorkspace = MutableStateFlow<Workspace?>(null)
    val activeWorkspace: StateFlow<Workspace?> = _activeWorkspace.asStateFlow()

    private val _allWorkspaces = MutableStateFlow<List<Workspace>>(emptyList())
    val allWorkspaces: StateFlow<List<Workspace>> = _allWorkspaces.asStateFlow()

    private val mutex = Mutex()

    init {
        // Bootstrap on first init: ensure at least one workspace exists and is active.
        coroutineScope.launch {
            bootstrapDefaultWorkspaceIfNeeded()
            refreshAllWorkspaces()
            refreshActiveWorkspace()
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
        val ownProjectId: Long? = projectDao?.let { dao ->
            runCatching {
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
            }.getOrNull()
        }

        val entity = WorkspaceEntity(
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
        workspaceDao.deactivateAll()
        workspaceDao.insertOrUpdate(entity)
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
        workspaceDao.deactivateAll()
        workspaceDao.setActive(workspaceId, System.currentTimeMillis())
        refreshAllWorkspaces()
        refreshActiveWorkspace()
        true
    }

    /**
     * Updates the active project for the current workspace. Pass null to clear
     * the active project (e.g. when the user deletes the project).
     */
    suspend fun setActiveProject(projectId: Long?) {
        val active = _activeWorkspace.value ?: return
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
     */
    suspend fun deleteWorkspace(workspaceId: String): Boolean = mutex.withLock {
        val all = workspaceDao.getAllWorkspaces()
        if (all.size <= 1) return@withLock false // never let the user delete the last workspace
        val target = all.firstOrNull { it.id == workspaceId } ?: return@withLock false
        workspaceDao.deleteById(workspaceId)
        if (target.isActive) {
            val nextActive = all.filter { it.id != workspaceId }.maxByOrNull { it.lastAccessedEpochMs }
            if (nextActive != null) {
                workspaceDao.deactivateAll()
                workspaceDao.setActive(nextActive.id, System.currentTimeMillis())
            }
        }
        refreshAllWorkspaces()
        refreshActiveWorkspace()
        true
    }

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
