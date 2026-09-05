package com.example.application.tools

import com.example.domain.core.tools.ToolDeclaration
import com.example.domain.core.tools.ToolParameter
import com.example.domain.core.tools.lifecycle.PrincipalType
import com.example.domain.core.tools.lifecycle.ToolCallOutcome
import com.example.domain.core.tools.lifecycle.ToolExecutionPolicy
import com.example.domain.core.tools.lifecycle.ToolLifecycleState
import com.example.domain.core.tools.lifecycle.ToolPermission
import com.example.domain.core.tools.lifecycle.ToolValidationResult
import com.example.domain.core.tools.lifecycle.ToolVersion
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Phase 5 — ToolLifecycleService unit tests.
 *
 * Closes the test-coverage aspect of P5-P0-04 (Tool Ecosystem): proves
 * the lifecycle state machine, validation, authorization, and audit
 * trail work as designed. Uses in-memory fakes (no Room).
 */
class ToolLifecycleServiceTest {

    @Test
    fun `register creates a tool in REGISTERED state with version 1_0_0`() = kotlinx.coroutines.runBlocking {
        val env = makeEnv()
        val toolId = env.service.register(makeDeclaration("test_tool_1"), "1.0.0")
        assertNotNull(toolId)
        val active = env.toolLifecycleDao.active()
        assertEquals(1, active.size)
        assertEquals("test_tool_1", active.first().toolName)
        assertEquals(ToolLifecycleState.REGISTERED.storageCode, active.first().lifecycleState)
        env.cleanup()
    }

    @Test
    fun `validate detects empty name and description`() = kotlinx.coroutines.runBlocking {
        val env = makeEnv()
        val toolId = env.service.register(
            ToolDeclaration(name = "", description = ""),
            "1.0.0"
        )
        val result = env.service.validate(toolId)
        assertFalse(result.isValid)
        assertTrue(result.declarationIssues.any { it.contains("اسم") })
        assertTrue(result.declarationIssues.any { it.contains("وصف") })
        env.cleanup()
    }

    @Test
    fun `validate detects duplicate parameter names`() = kotlinx.coroutines.runBlocking {
        val env = makeEnv()
        val toolId = env.service.register(
            ToolDeclaration(
                name = "test_tool",
                description = "desc",
                parameters = listOf(
                    ToolParameter("path", "string", "first"),
                    ToolParameter("path", "string", "duplicate")
                )
            ),
            "1.0.0"
        )
        val result = env.service.validate(toolId)
        assertTrue(result.parameterSchemaIssues.any { it.contains("مكرر") })
        env.cleanup()
    }

    @Test
    fun `validate detects sensitive tool without required permissions`() = kotlinx.coroutines.runBlocking {
        val env = makeEnv()
        val toolId = env.service.register(
            ToolDeclaration(
                name = "sensitive_tool",
                description = "desc",
                isSensitive = true,
                requiredPermissions = emptyList()
            ),
            "1.0.0"
        )
        val result = env.service.validate(toolId)
        assertTrue(result.permissionIssues.any { it.contains("حساسة") })
        env.cleanup()
    }

    @Test
    fun `authorize denies when no permission grant exists`() = kotlinx.coroutines.runBlocking {
        val env = makeEnv()
        val toolId = env.service.register(makeDeclaration("test_tool_2"), "1.0.0")
        env.service.validate(toolId)
        val result = env.service.authorize(toolId, PrincipalType.AGENT, "agent_general", ToolPermission.EXECUTE)
        assertFalse(result.isAuthorized)
        env.cleanup()
    }

    @Test
    fun `authorize allows when explicit permission grant exists`() = kotlinx.coroutines.runBlocking {
        val env = makeEnv()
        val toolId = env.service.register(makeDeclaration("test_tool_3"), "1.0.0")
        env.service.validate(toolId)
        env.service.grantPermission(
            com.example.domain.core.tools.lifecycle.ToolPermissionGrant(
                principalType = PrincipalType.AGENT,
                principalId = "agent_general",
                toolName = "test_tool_3",
                permission = ToolPermission.EXECUTE,
                isAllowed = true,
                grantedBy = "test"
            )
        )
        val result = env.service.authorize(toolId, PrincipalType.AGENT, "agent_general", ToolPermission.EXECUTE)
        assertTrue(result.isAuthorized)
        env.cleanup()
    }

    @Test
    fun `checkPermission returns true only for granted permissions`() = kotlinx.coroutines.runBlocking {
        val env = makeEnv()
        env.service.grantPermission(
            com.example.domain.core.tools.lifecycle.ToolPermissionGrant(
                principalType = PrincipalType.AGENT,
                principalId = "agent_general",
                toolName = "test_tool_4",
                permission = ToolPermission.EXECUTE,
                isAllowed = true,
                grantedBy = "test"
            )
        )
        assertTrue(env.service.checkPermission("test_tool_4", PrincipalType.AGENT, "agent_general", ToolPermission.EXECUTE))
        assertFalse(env.service.checkPermission("test_tool_4", PrincipalType.AGENT, "agent_general", ToolPermission.ADMIN))
        env.cleanup()
    }

    @Test
    fun `hashArguments is stable for same arguments regardless of order`() {
        val env = makeEnv()
        val a = env.service.hashArguments(mapOf("x" to 1, "y" to "hello"))
        val b = env.service.hashArguments(mapOf("y" to "hello", "x" to 1))
        assertEquals(a, b)
        env.cleanup()
    }

    private fun makeDeclaration(name: String): ToolDeclaration {
        return ToolDeclaration(
            name = name,
            description = "Test tool for $name",
            parameters = listOf(ToolParameter("input", "string", "input value"))
        )
    }

    private fun makeEnv(): TestEnv {
        val toolLifecycleDao = FakeToolLifecycleDao()
        val toolHealthDao = FakeToolHealthDao()
        val toolAuditDao = FakeToolAuditDao()
        val permissionGrantDao = FakePermissionGrantDao()
        val service = ToolLifecycleService(
            toolLifecycleDao = toolLifecycleDao,
            toolHealthDao = toolHealthDao,
            toolAuditDao = toolAuditDao,
            permissionGrantDao = permissionGrantDao,
            declarationProvider = { null }
        )
        return TestEnv(service, toolLifecycleDao, toolHealthDao, toolAuditDao, permissionGrantDao)
    }

    private class TestEnv(
        val service: ToolLifecycleService,
        val toolLifecycleDao: FakeToolLifecycleDao,
        val toolHealthDao: FakeToolHealthDao,
        val toolAuditDao: FakeToolAuditDao,
        val permissionGrantDao: FakePermissionGrantDao
    ) {
        fun cleanup() {}
    }

    // --- In-memory fakes (project convention: no MockK) ---

    private class FakeToolLifecycleDao : com.example.infrastructure.persistence.dao.ToolLifecycleDao {
        private val states = mutableMapOf<String, com.example.infrastructure.persistence.entities.ToolLifecycleStateEntity>()
        override suspend fun byName(toolName: String) = states.values.firstOrNull { it.toolName == toolName }
        override suspend fun active() = states.values.filter { it.isEnabled && it.lifecycleState != "REVOKED" }
        override fun allFlow(): kotlinx.coroutines.flow.Flow<List<com.example.infrastructure.persistence.entities.ToolLifecycleStateEntity>> =
            kotlinx.coroutines.flow.flowOf(states.values.toList())
        override suspend fun upsert(state: com.example.infrastructure.persistence.entities.ToolLifecycleStateEntity) {
            states[state.toolId] = state
        }
        override suspend fun updateLifecycle(id: String, state: String, now: Long) {
            states[id]?.let { states[id] = it.copy(lifecycleState = state, lastValidatedAtEpochMs = now) }
        }
        override suspend fun revoke(id: String, reason: String, now: Long) {
            states[id]?.let { states[id] = it.copy(lifecycleState = "REVOKED", isEnabled = false, revokedAtEpochMs = now, revokeReason = reason) }
        }
    }

    private class FakeToolHealthDao : com.example.infrastructure.persistence.dao.ToolHealthDao {
        private val snapshots = mutableMapOf<String, com.example.infrastructure.persistence.entities.ToolHealthSnapshotEntity>()
        override suspend fun all() = snapshots.values.toList()
        override suspend fun byTool(toolId: String) = snapshots[toolId]
        override suspend fun upsert(snapshot: com.example.infrastructure.persistence.entities.ToolHealthSnapshotEntity) {
            snapshots[snapshot.toolId] = snapshot
        }
        override suspend fun updateCircuitState(toolId: String, state: String, openedAt: Long?) {
            snapshots[toolId]?.let { snapshots[toolId] = it.copy(circuitState = state, openedAtEpochMs = openedAt) }
        }
    }

    private class FakeToolAuditDao : com.example.infrastructure.persistence.dao.ToolAuditDao {
        private val entries = mutableListOf<com.example.infrastructure.persistence.entities.ToolAuditEntity>()
        override suspend fun forTool(toolName: String, limit: Int) = entries.filter { it.toolName == toolName }.take(limit)
        override fun forExecution(executionId: String): kotlinx.coroutines.flow.Flow<List<com.example.infrastructure.persistence.entities.ToolAuditEntity>> =
            kotlinx.coroutines.flow.flowOf(entries.filter { it.executionId == executionId })
        override fun recent(limit: Int): kotlinx.coroutines.flow.Flow<List<com.example.infrastructure.persistence.entities.ToolAuditEntity>> =
            kotlinx.coroutines.flow.flowOf(entries.takeLast(limit))
        override suspend fun insert(entity: com.example.infrastructure.persistence.entities.ToolAuditEntity): Long {
            entries.add(entity.copy(id = entries.size + 1L))
            return entries.size.toLong()
        }
        override suspend fun successCountForTool(toolName: String) = entries.count { it.toolName == toolName && it.outcome == "SUCCESS" }
        override suspend fun failureCountForTool(toolName: String) = entries.count { it.toolName == toolName && it.outcome == "FAILURE" }
    }

    private class FakePermissionGrantDao : com.example.infrastructure.persistence.dao.PermissionGrantDao {
        private val grants = mutableListOf<com.example.infrastructure.persistence.entities.PermissionGrantEntity>()
        override suspend fun forPrincipal(principalType: String, principalId: String) =
            grants.filter { it.principalType == principalType && it.principalId == principalId }
        override suspend fun lookup(principalType: String, principalId: String, resourceType: String, resourceId: String, permission: String) =
            grants.firstOrNull {
                it.principalType == principalType && it.principalId == principalId &&
                    it.resourceType == resourceType && it.resourceId == resourceId && it.permission == permission
            }
        override suspend fun upsert(grant: com.example.infrastructure.persistence.entities.PermissionGrantEntity): Long {
            grants.add(grant.copy(id = grants.size + 1L))
            return grants.size.toLong()
        }
        override suspend fun revoke(id: Long) {
            grants.removeAll { it.id == id }
        }
    }
}
