package com.example.gapclosure

import com.example.domain.core.Outcome
import com.example.domain.core.storage.StorageFailure
import com.example.domain.core.storage.WorkspaceFileEntry
import com.example.domain.core.tools.ToolInput
import com.example.domain.ports.storage.WorkspaceStoragePort
import com.example.infrastructure.tools.FileSystemTool
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * ============================================================================
 * FileSystemToolScopeTest — gap-closure P0-04
 * ============================================================================
 *
 * Proves agent-driven file operations are scoped to the ACTIVE WORKSPACE'S
 * OWN project:
 *  - the projectIdProvider is consulted per call;
 *  - when no project is bound the tool FAILS honestly
 *    (PROJECT_CONTEXT_REQUIRED) instead of writing to the legacy shared
 *    project id=1L;
 *  - a bound project routes every operation to that project id.
 */
class FileSystemToolScopeTest {

    /** Capturing fake of the storage port. */
    private class CapturingStorage : WorkspaceStoragePort {
        val writtenProjects = mutableListOf<Long>()
        val readProjects = mutableListOf<Long>()

        override suspend fun readFile(projectId: Long, relativePath: String): Outcome<String, StorageFailure> {
            readProjects.add(projectId)
            return Outcome.Success("content-of-$relativePath")
        }

        override suspend fun writeFile(projectId: Long, relativePath: String, content: String): Outcome<Unit, StorageFailure> {
            writtenProjects.add(projectId)
            return Outcome.Success(Unit)
        }

        override suspend fun listFiles(projectId: Long, relativePath: String?): Outcome<List<WorkspaceFileEntry>, StorageFailure> {
            return Outcome.Success(emptyList())
        }

        override suspend fun deleteFile(projectId: Long, relativePath: String): Outcome<Unit, StorageFailure> {
            return Outcome.Success(Unit)
        }

        override suspend fun fileExists(projectId: Long, relativePath: String): Boolean = true
    }

    @Test
    fun `no bound project fails honestly instead of writing to project 1L`() = runBlocking {
        val storage = CapturingStorage()
        val tool = FileSystemTool(
            storagePort = storage,
            projectIdProvider = { null } // active workspace has NO project bound
        )

        val outcome = tool.execute(ToolInput(toolName = "workspace_file_tool", arguments = mapOf("action" to "write", "path" to "Main.kt", "content" to "x")))

        assertTrue("Must fail when no project is bound (P0-04)", outcome is Outcome.Error)
        assertEquals("PROJECT_CONTEXT_REQUIRED", (outcome as Outcome.Error).diagnosticMessage)
        assertEquals(
            "Nothing may be written to the legacy shared project 1L",
            0,
            storage.writtenProjects.size
        )
    }

    @Test
    fun `bound project routes operations to the workspace OWN project`() = runBlocking {
        val storage = CapturingStorage()
        val tool = FileSystemTool(
            storagePort = storage,
            projectIdProvider = { 42L } // the active workspace's own project
        )

        tool.execute(ToolInput(toolName = "workspace_file_tool", arguments = mapOf("action" to "write", "path" to "Main.kt", "content" to "x")))
        tool.execute(ToolInput(toolName = "workspace_file_tool", arguments = mapOf("action" to "read", "path" to "Main.kt")))

        assertEquals(listOf(42L), storage.writtenProjects)
        assertEquals(listOf(42L), storage.readProjects)
        assertEquals(
            "The legacy shared project 1L must never be touched",
            0,
            (storage.writtenProjects + storage.readProjects).count { it == 1L }
        )
    }

    @Test
    fun `project binding is resolved PER CALL (workspace switches are honored)`() = runBlocking {
        val storage = CapturingStorage()
        var currentProject: Long? = 7L
        val tool = FileSystemTool(storagePort = storage, projectIdProvider = { currentProject })

        tool.execute(ToolInput(toolName = "workspace_file_tool", arguments = mapOf("action" to "write", "path" to "a.txt", "content" to "1")))
        currentProject = 9L // user switched to another workspace
        tool.execute(ToolInput(toolName = "workspace_file_tool", arguments = mapOf("action" to "write", "path" to "b.txt", "content" to "2")))

        assertEquals(listOf(7L, 9L), storage.writtenProjects)
    }
}
