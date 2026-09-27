package com.example.closure

import com.example.application.project.JournalPhase
import com.example.application.project.ProjectOperationJournal
import com.example.application.project.RecoveryState
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * ============================================================================
 * ProjectOperationJournalTest — CLOSURE P0-3 (audit §5.4/B4)
 * ============================================================================
 *
 * INVARIANT — a journaled operation leaves DURABLE forensics:
 *
 *   1. A cleanly-completed operation (PREPARE → … → FINALIZE) leaves NO
 *      recovery work item.
 *   2. An INTERRUPTED operation (fail with durable effect possible) stays
 *      PENDING_RECOVERY — visible to the next process's recovery scan.
 *   3. The journal survives process death: a NEW journal instance over the
 *      same file reloads the truth (the crash-safe JSONL contract).
 *   4. A torn final line (process death mid-append) is DISCARDED — earlier
 *      lines still define durable truth.
 */
class ProjectOperationJournalTest {

    @get:Rule
    val folder = TemporaryFolder()

    private fun journal(): ProjectOperationJournal =
        ProjectOperationJournal(journalFile = folder.newFile("project-operation-journal.jsonl"))

    @Test
    fun `a cleanly-completed operation leaves no recovery work item`() = runBlocking {
        val journal = journal()
        journal.begin(
            operationId = "op_move_1",
            operationType = "PROJECT_MOVE_IDENTITY",
            projectId = 42L,
            workspaceId = "ws_a"
        )
        journal.advance("op_move_1", JournalPhase.COMMIT_DATABASE)
        journal.advance("op_move_1", JournalPhase.VERIFY_POST_STATE)
        journal.complete("op_move_1")

        assertTrue(journal.pendingRecovery().isEmpty())
        val entry = journal.entry("op_move_1")
        assertNotNull(entry)
        assertEquals(JournalPhase.FINALIZE, entry!!.phase)
        assertEquals(RecoveryState.NONE, entry.recoveryState)
    }

    @Test
    fun `an interrupted operation stays PENDING_RECOVERY - visible to the next scan`() = runBlocking {
        val journal = journal()
        journal.begin(
            operationId = "op_move_2",
            operationType = "PROJECT_MOVE_IDENTITY",
            projectId = 7L,
            workspaceId = "ws_a"
        )
        journal.advance("op_move_2", JournalPhase.COMMIT_DATABASE)
        // Crash signature: the operation fails (or the process dies) before
        // FINALIZE — the entry must become a recovery work item.
        journal.fail("op_move_2", "interrupted: process death before finalize")

        val pending = journal.pendingRecovery()
        assertEquals(listOf("op_move_2"), pending.map { it.operationId })
        assertEquals(RecoveryState.PENDING_RECOVERY, pending.single().recoveryState)

        // Recovery closes it honestly.
        journal.markRecovered("op_move_2", "reconciled: transaction rolled back")
        assertTrue(journal.pendingRecovery().isEmpty())
    }

    @Test
    fun `the journal survives process death - a new instance reloads the same truth`() = runBlocking {
        val file = folder.newFile("project-operation-journal.jsonl")
        val first = ProjectOperationJournal(journalFile = file)
        first.begin(
            operationId = "op_move_3",
            operationType = "PROJECT_MOVE_IDENTITY",
            projectId = 9L,
            workspaceId = "ws_a"
        )
        first.fail("op_move_3", "interrupted")

        // A NEW process (fresh instance, same file) sees the SAME truth.
        val second = ProjectOperationJournal(journalFile = file)
        val pending = second.pendingRecovery()
        assertEquals(listOf("op_move_3"), pending.map { it.operationId })
        assertEquals("the durable intent survives the restart", "ws_a", pending.single().workspaceId)
        assertEquals(java.lang.Long.valueOf(9L), pending.single().projectId)
    }

    @Test
    fun `a torn final line is discarded - earlier lines still define durable truth`() = runBlocking {
        val file = folder.newFile("project-operation-journal.jsonl")
        val first = ProjectOperationJournal(journalFile = file)
        first.begin(
            operationId = "op_move_4",
            operationType = "PROJECT_MOVE_IDENTITY",
            projectId = 11L,
            workspaceId = "ws_a"
        )
        first.complete("op_move_4")

        // Simulate a torn append: half a JSON line at the file's tail.
        file.appendText("{\"operationId\":\"op_torn\",\"operationType\":\"PROJ")

        val second = ProjectOperationJournal(journalFile = file)
        // The torn operation is DISCARDED (never fabricated into truth)…
        assertNull(second.entry("op_torn"))
        // …and the completed operation's truth is intact.
        assertEquals(
            JournalPhase.FINALIZE,
            second.entry("op_move_4")!!.phase
        )
    }
}
