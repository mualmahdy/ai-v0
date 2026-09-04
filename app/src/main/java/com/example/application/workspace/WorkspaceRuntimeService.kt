package com.example.application.workspace

import com.example.domain.core.network.NetworkPolicy
import com.example.domain.core.workspace.Workspace
import com.example.infrastructure.persistence.dao.WorkspaceDao
import com.example.infrastructure.persistence.entities.WorkspaceEntity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.UUID

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
     */
    private suspend fun bootstrapDefaultWorkspaceIfNeeded() {
        val existing = workspaceDao.getAllWorkspaces()
        if (existing.isEmpty()) {
            val now = System.currentTimeMillis()
            val defaultId = "default"
            workspaceDao.insertOrUpdate(
                WorkspaceEntity(
                    id = defaultId,
                    name = "مساحة العمل الافتراضية",
                    description = "مساحة العمل الأساسية المعزولة لتنسيق الوكلاء والملفات",
                    networkPolicy = NetworkPolicy.HYBRID.name,
                    autonomyPolicy = "SUPERVISED",
                    settingsJson = "{}",
                    isActive = true,
                    lastActiveProjectId = 1L, // the legacy default project
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
        val entity = WorkspaceEntity(
            id = id,
            name = name,
            description = description,
            networkPolicy = networkPolicy.name,
            autonomyPolicy = autonomyPolicy,
            settingsJson = encodeSettings(settings),
            isActive = true,
            lastActiveProjectId = null,
            createdAtEpochMs = now,
            lastAccessedEpochMs = now
        )
        workspaceDao.deactivateAll()
        workspaceDao.insertOrUpdate(entity)
        refreshAllWorkspaces()
        refreshActiveWorkspace()
        _activeWorkspace.value!!
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
     * Returns the workspace id that should be used as the current scope for
     * RAG persistence, resource edges, and other workspace-scoped data.
     *
     * If no workspace is active yet (e.g. during cold start before bootstrap
     * completes), returns "default" so callers have a stable key to write to.
     */
    fun requireActiveWorkspaceId(): String {
        return _activeWorkspace.value?.id ?: "default"
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
        settings = decodeSettings(settingsJson),
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
