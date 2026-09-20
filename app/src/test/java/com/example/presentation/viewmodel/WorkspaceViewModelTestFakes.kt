package com.example.presentation.viewmodel

import com.example.domain.core.session.ConversationSession
import com.example.domain.core.session.ConversationSessionId
import com.example.domain.core.session.ConversationSessionWithTurns
import com.example.domain.core.session.ConversationTurn
import com.example.domain.ports.session.ConversationSessionRepositoryPort
import com.example.infrastructure.persistence.dao.ProjectDao
import com.example.infrastructure.persistence.dao.WorkspaceDao
import com.example.infrastructure.persistence.entities.ProjectEntity
import com.example.infrastructure.persistence.entities.WorkspaceEntity
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map

/**
 * ============================================================================
 * WorkspaceViewModelTestFakes — shared pure-JVM DAO fakes (ADR-6 slice 1)
 * ============================================================================
 *
 * In-memory WorkspaceDao/ProjectDao doubles so the SETTINGS feature
 * ViewModel behavioral tests can drive the REAL WorkspaceRuntimeService
 * with no Android framework and no Room (the same fake semantics proven by
 * WorkspaceRuntimeServiceTest — extracted here because two feature-VM test
 * suites now need them).
 */
// (ADR-6 slice 4) open for the governance test's exploding-DAO variant —
// the honest-gate path needs a bootstrap that fails without a workspace.
open class FakeWorkspaceDaoForVm : WorkspaceDao {
    val stored = mutableMapOf<String, WorkspaceEntity>()
    var insertOrUpdateCount = 0
    var deactivateAllCount = 0
    val setActiveCalls = mutableListOf<Pair<String, Long>>()

    override fun observeAllWorkspaces(): Flow<List<WorkspaceEntity>> = MutableStateFlow(stored.values.toList())
    override suspend fun getAllWorkspaces(): List<WorkspaceEntity> = stored.values.toList()
    override suspend fun getWorkspaceById(id: String): WorkspaceEntity? = stored[id]
    override suspend fun getActiveWorkspace(): WorkspaceEntity? = stored.values.firstOrNull { it.isActive }
    override fun observeActiveWorkspace(): Flow<WorkspaceEntity?> = MutableStateFlow(stored.values.firstOrNull { it.isActive })

    override suspend fun insertOrUpdate(workspace: WorkspaceEntity) {
        stored[workspace.id] = workspace
        insertOrUpdateCount++
    }

    override suspend fun update(workspace: WorkspaceEntity) {
        stored[workspace.id] = workspace
    }

    override suspend fun deactivateAll() {
        stored.forEach { (id, entity) -> stored[id] = entity.copy(isActive = false) }
        deactivateAllCount++
    }

    override suspend fun setActive(id: String, now: Long) {
        stored[id]?.let { stored[id] = it.copy(isActive = true, lastAccessedEpochMs = now) }
        setActiveCalls.add(id to now)
    }

    override suspend fun setActiveProject(workspaceId: String, projectId: Long?, now: Long) {
        stored[workspaceId]?.let {
            stored[workspaceId] = it.copy(lastActiveProjectId = projectId, lastAccessedEpochMs = now)
        }
    }

    override suspend fun deleteById(id: String) {
        stored.remove(id)
    }

    override suspend fun updateAutonomyPolicy(workspaceId: String, policy: String, now: Long) {
        stored[workspaceId]?.let {
            stored[workspaceId] = it.copy(autonomyPolicy = policy, lastAccessedEpochMs = now)
        }
    }

    override suspend fun autonomyPolicyFor(workspaceId: String): String? =
        stored[workspaceId]?.autonomyPolicy
}

/**
 * ADR-6 SLICE 2 — in-memory [ConversationSessionRepositoryPort] double so
 * the STUDIO + SESSIONS feature ViewModel behavioral tests can drive the
 * REAL ConversationSessionService with no Android framework and no Room.
 * Semantics mirror the Room adapter's authorization contract: every
 * workspace-authorized operation no-ops (or returns false/null) for rows
 * owned by a DIFFERENT workspace, exactly like the SQL implementation.
 */
class FakeConversationSessionRepositoryForVm : ConversationSessionRepositoryPort {

    /** All session rows (any workspace) — mutations update the live flow. */
    private val sessionsFlow = MutableStateFlow<List<ConversationSession>>(emptyList())

    /** Turn rows keyed by session id. */
    private val turns = mutableListOf<ConversationTurn>()

    /** The workspace the SERVICE currently resolves as active. */
    var activeWorkspaceId: String = "default"

    /** Counters for assertions. */
    var upsertCount = 0
    var appendCount = 0
    var deleteCount = 0
    val appendedTurns = mutableListOf<ConversationTurn>()

    /** FUNCTIONAL CLOSURE (Phase 1 §9): durable timeline-event storage. */
    val timelineEvents = mutableListOf<com.example.domain.core.session.ConversationTimelineEvent>()
    var appendTimelineEventCount = 0
    var updateTimelineEventStateCount = 0

    fun seed(session: ConversationSession) {
        sessionsFlow.value = sessionsFlow.value + session
    }

    fun seedTurn(turn: ConversationTurn) {
        turns += turn
    }

    override fun observeSessions(workspaceId: String): Flow<List<ConversationSession>> =
        sessionsFlow.map { list -> list.filter { it.workspaceId == workspaceId } }

    override fun observeTurns(sessionId: ConversationSessionId): Flow<List<ConversationTurn>> =
        MutableStateFlow(turns.filter { it.sessionId == sessionId })

    override suspend fun getSession(id: ConversationSessionId): ConversationSession? =
        sessionsFlow.value.firstOrNull { it.id == id }

    override suspend fun getSessionForWorkspace(
        id: ConversationSessionId,
        workspaceId: String
    ): ConversationSession? =
        sessionsFlow.value.firstOrNull { it.id == id && it.workspaceId == workspaceId }

    override suspend fun getSessionWithTurnsForWorkspace(
        id: ConversationSessionId,
        workspaceId: String
    ): ConversationSessionWithTurns? {
        val session = getSessionForWorkspace(id, workspaceId) ?: return null
        return ConversationSessionWithTurns(
            session = session,
            turns = turns.filter { it.sessionId == id },
            timelineEvents = timelineEvents.filter { it.sessionId == id }
                .sortedBy { it.createdAtEpochMs }
        )
    }

    override suspend fun upsertSession(session: ConversationSession) {
        upsertCount++
        sessionsFlow.value = sessionsFlow.value.filter { it.id != session.id } + session
    }

    override suspend fun appendTurnForWorkspace(
        turn: ConversationTurn,
        workspaceId: String
    ): Boolean {
        val session = getSessionForWorkspace(turn.sessionId, workspaceId) ?: return false
        appendCount++
        appendedTurns += turn
        turns += turn
        upsertSession(
            session.copy(
                turnCount = session.turnCount + 1,
                totalTokensConsumed = session.totalTokensConsumed + turn.tokensConsumed,
                lastActiveAtEpochMs = System.currentTimeMillis()
            )
        )
        return true
    }

    override suspend fun deleteSessionForWorkspace(
        id: ConversationSessionId,
        workspaceId: String
    ): Boolean {
        val session = getSessionForWorkspace(id, workspaceId) ?: return false
        deleteCount++
        sessionsFlow.value = sessionsFlow.value - session
        turns.removeAll { it.sessionId == id }
        return true
    }

    override suspend fun updateSessionModelForWorkspace(
        id: ConversationSessionId,
        workspaceId: String,
        modelResourceId: String?,
        modelDisplayName: String?
    ) {
        val session = getSessionForWorkspace(id, workspaceId) ?: return
        upsertSession(session.copy(modelResourceId = modelResourceId, modelDisplayName = modelDisplayName))
    }

    override suspend fun renameSessionForWorkspace(
        id: ConversationSessionId,
        workspaceId: String,
        title: String
    ): Boolean {
        val session = getSessionForWorkspace(id, workspaceId) ?: return false
        upsertSession(session.copy(title = title))
        return true
    }

    // FUNCTIONAL CLOSURE (Phase 1 §9): timeline-event persistence in the fake.
    override suspend fun appendTimelineEventForWorkspace(
        event: com.example.domain.core.session.ConversationTimelineEvent,
        workspaceId: String
    ): Boolean {
        val session = getSessionForWorkspace(event.sessionId, workspaceId) ?: return false
        appendTimelineEventCount++
        timelineEvents.removeAll { it.id == event.id }
        timelineEvents += event
        return true
    }

    override suspend fun timelineEventsForSession(
        sessionId: ConversationSessionId
    ): List<com.example.domain.core.session.ConversationTimelineEvent> =
        timelineEvents.filter { it.sessionId == sessionId }.sortedBy { it.createdAtEpochMs }

    override suspend fun updateTimelineEventApprovalStateForWorkspace(
        sessionId: ConversationSessionId,
        approvalId: String,
        state: String,
        workspaceId: String
    ): Boolean {
        if (getSessionForWorkspace(sessionId, workspaceId) == null) return false
        updateTimelineEventStateCount++
        timelineEvents.replaceAll { event ->
            if (event.approvalId == approvalId) {
                event.copy(approvalState = state, isSuccessful = state != "REJECTED")
            } else {
                event
            }
        }
        return true
    }
}

class FakeProjectDaoForVm : ProjectDao {
    val stored = mutableMapOf<Long, ProjectEntity>()
    private var nextId = 100L

    override fun getAllActiveProjects(): Flow<List<ProjectEntity>> =
        MutableStateFlow(stored.values.filter { !it.isArchived })

    override suspend fun getAllActiveProjectsList(): List<ProjectEntity> =
        stored.values.filter { !it.isArchived }

    override suspend fun getProjectById(id: Long): ProjectEntity? = stored[id]

    override fun getActiveProjectsForWorkspace(workspaceId: String): Flow<List<ProjectEntity>> =
        MutableStateFlow(stored.values.filter { !it.isArchived && it.workspaceId == workspaceId })

    override suspend fun getActiveProjectsForWorkspaceList(workspaceId: String): List<ProjectEntity> =
        stored.values.filter { !it.isArchived && it.workspaceId == workspaceId }

    override suspend fun getProjectByIdForWorkspace(id: Long, workspaceId: String): ProjectEntity? =
        stored[id]?.takeIf { it.workspaceId == workspaceId }

    override suspend fun archiveProjectForWorkspace(id: Long, workspaceId: String) {
        stored[id]?.let { if (it.workspaceId == workspaceId) stored[id] = it.copy(isArchived = true) }
    }

    override suspend fun forWorkspaceInState(workspaceId: String, state: String): List<ProjectEntity> =
        stored.values.filter { it.workspaceId == workspaceId && it.effectiveLifecycleState == state }

    override suspend fun activeProjectsForWorkspaceList(workspaceId: String): List<ProjectEntity> =
        stored.values.filter { it.workspaceId == workspaceId && it.effectiveLifecycleState == "ACTIVE" }

    override suspend fun mostRecentActiveProjectForWorkspace(workspaceId: String): ProjectEntity? =
        stored.values.filter { it.workspaceId == workspaceId && it.effectiveLifecycleState == "ACTIVE" }
            .maxByOrNull { it.updatedAtEpochMs }

    override suspend fun resolvableProjectForWorkspace(id: Long, workspaceId: String): ProjectEntity? =
        stored[id]?.takeIf { it.workspaceId == workspaceId && it.effectiveLifecycleState == "ACTIVE" }

    override suspend fun setLifecycleState(id: Long, workspaceId: String, state: String, now: Long, archived: Boolean, archivedAt: Long?, trashedAt: Long?) {
        stored[id]?.let {
            if (it.workspaceId == workspaceId) {
                stored[id] = it.copy(
                    lifecycleState = state, isArchived = archived,
                    archivedAtEpochMs = archivedAt, trashedAtEpochMs = trashedAt,
                    updatedAtEpochMs = now
                )
            }
        }
    }

    override suspend fun renameProjectForWorkspace(id: Long, workspaceId: String, name: String, description: String?, now: Long) {
        stored[id]?.let {
            if (it.workspaceId == workspaceId) stored[id] = it.copy(name = name, description = description, updatedAtEpochMs = now)
        }
    }

    override suspend fun moveProjectToWorkspace(id: Long, sourceWorkspaceId: String, targetWorkspaceId: String, now: Long): Int {
        val p = stored[id] ?: return 0
        if (p.workspaceId != sourceWorkspaceId) return 0
        stored[id] = p.copy(workspaceId = targetWorkspaceId, updatedAtEpochMs = now)
        return 1
    }

    override suspend fun countByNameForWorkspace(workspaceId: String, name: String): Int =
        stored.values.count { it.workspaceId == workspaceId && it.name.equals(name, ignoreCase = true) }

    override suspend fun deleteProjectRow(id: Long) { stored.remove(id) }

    override suspend fun insertProject(project: ProjectEntity): Long {
        val id = nextId++
        stored[id] = project.copy(id = id)
        return id
    }

    override suspend fun updateProject(project: ProjectEntity) {
        stored[project.id] = project
    }

    override suspend fun archiveProject(id: Long) {
        stored[id]?.let { stored[id] = it.copy(isArchived = true) }
    }
}
