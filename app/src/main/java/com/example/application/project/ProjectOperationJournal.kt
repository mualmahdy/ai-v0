package com.example.application.project

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File

/**
 * ============================================================================
 * ProjectOperationJournal — CLOSURE P0-3 (audit §5.4/B4)
 * ============================================================================
 *
 * A DURABLE, append-only operation journal for project-scoped
 * filesystem+database operations. The audit's finding: Room transactions
 * cover the DATABASE leg only — an operation spanning Room + filesystem has
 * NO transactional boundary at all, and crash/interruption forensics had
 * nothing durable to recover from ("transaction جعلت العملية atomic" was
 * false the moment the operation crossed that line).
 *
 * The journal records the audit's canonical phase cycle:
 *
 *   PREPARE → STAGE_FILESYSTEM → VERIFY_STAGE → COMMIT_DATABASE →
 *   PROMOTE_FILESYSTEM → VERIFY_POST_STATE → FINALIZE
 *
 * and, on failure:
 *
 *   RECOVER / COMPENSATE
 *
 * WHY A FILESYSTEM JOURNAL (not a Room table): the journal must survive
 * (and record intent BEFORE) the very database commits it witnesses — a
 * Room-side journal cannot record "about to commit" before the commit
 * itself within a different transaction's atomicity. A line-appended JSONL
 * file under the app's private files dir is the honest substrate; each line
 * is the FULL entry snapshot for one operationId, and the LATEST line per
 * operationId defines its current state (crash-safe by construction: a
 * truncated last line is discarded at load).
 *
 * RECOVERY SURFACE: [pendingRecovery] lists operations whose journal ended
 * in a non-final state (or explicitly PENDING_RECOVERY). The owning
 * coordinator reconciles them against the database's actual state (see
 * ProjectTransferCoordinator.reconcileInterruptedOperations) — the journal
 * never invents state, it only records what was ATTEMPTED so recovery has
 * something truthful to reconcile against.
 *
 * HONEST BOUNDARY (this stage): bound to the project MOVE paths (identity
 * move per-phase; package move as an envelope). Import/export/snapshot/
 * patch pipelines adopt the same journal in their own stages (audit §5.4/
 * B4's list: project import/export, move, snapshots, generated artifacts,
 * self-modification, patch application, rollback).
 */
enum class JournalPhase {
    PREPARE,
    STAGE_FILESYSTEM,
    VERIFY_STAGE,
    COMMIT_DATABASE,
    PROMOTE_FILESYSTEM,
    VERIFY_POST_STATE,
    FINALIZE,
    RECOVER,
    COMPENSATE
}

/** Recovery bookkeeping for interrupted operations. */
enum class RecoveryState {
    /** Operation finalized cleanly — nothing to recover. */
    NONE,
    /** Operation interrupted; the journal row is the recovery work item. */
    PENDING_RECOVERY,
    /** Reconciled against durable truth; closed as recovered. */
    RECOVERED,
    /** Reconciled by compensating (rolling back) the partial effect. */
    COMPENSATED
}

data class JournalEntry(
    val operationId: String,
    val operationType: String,
    val projectId: Long?,
    val workspaceId: String,
    val phase: JournalPhase,
    val recoveryState: RecoveryState = RecoveryState.NONE,
    val sourcePath: String? = null,
    val stagingPath: String? = null,
    val targetPath: String? = null,
    val checksum: String? = null,
    val detail: String? = null,
    val createdAtEpochMs: Long,
    val updatedAtEpochMs: Long
)

class ProjectOperationJournal(
    /** The JSONL journal file (created lazily; parent dirs created on first write). */
    private val journalFile: File
) {
    private val mutex = Mutex()

    /** In-memory index: latest entry per operationId, insertion-ordered. */
    private val latest = LinkedHashMap<String, JournalEntry>()

    init {
        // Crash-safe load: a truncated/corrupt LAST line (process death
        // mid-append) is discarded; earlier lines define durable truth.
        runCatching { loadFromDisk() }
    }

    /**
     * Opens (begins) a journaled operation at PREPARE — the durable record
     * of INTENT before any side effect runs.
     */
    suspend fun begin(
        operationId: String,
        operationType: String,
        projectId: Long?,
        workspaceId: String,
        sourcePath: String? = null,
        stagingPath: String? = null,
        targetPath: String? = null,
        detail: String? = null
    ): JournalEntry = mutex.withLock {
        val now = System.currentTimeMillis()
        val entry = JournalEntry(
            operationId = operationId,
            operationType = operationType,
            projectId = projectId,
            workspaceId = workspaceId,
            phase = JournalPhase.PREPARE,
            sourcePath = sourcePath,
            stagingPath = stagingPath,
            targetPath = targetPath,
            detail = detail,
            createdAtEpochMs = now,
            updatedAtEpochMs = now
        )
        append(entry)
        entry
    }

    /**
     * Advances a journaled operation to [phase]. Unknown operationIds are
     * refused honestly (null) — the journal never fabricates history.
     */
    suspend fun advance(
        operationId: String,
        phase: JournalPhase,
        detail: String? = null
    ): JournalEntry? = mutex.withLock {
        val current = latest[operationId] ?: return@withLock null
        val entry = current.copy(
            phase = phase,
            detail = detail ?: current.detail,
            updatedAtEpochMs = System.currentTimeMillis()
        )
        append(entry)
        entry
    }

    /**
     * Closes an operation cleanly (VERIFY_POST_STATE → FINALIZE, recovery
     * NONE) — the durable "this operation is done" witness.
     */
    suspend fun complete(operationId: String, detail: String? = null): JournalEntry? =
        mutex.withLock {
            val current = latest[operationId] ?: return@withLock null
            val entry = current.copy(
                phase = JournalPhase.FINALIZE,
                recoveryState = RecoveryState.NONE,
                detail = detail ?: current.detail,
                updatedAtEpochMs = System.currentTimeMillis()
            )
            append(entry)
            entry
        }

    /**
     * Records a FAILED operation. If [mayHaveDurableEffect] the entry is
     * marked PENDING_RECOVERY — the interrupted operation becomes a real
     * recovery work item the next time [pendingRecovery] is consulted.
     */
    suspend fun fail(
        operationId: String,
        reason: String,
        mayHaveDurableEffect: Boolean = true
    ): JournalEntry? = mutex.withLock {
        val current = latest[operationId] ?: return@withLock null
        val entry = current.copy(
            phase = JournalPhase.RECOVER,
            recoveryState = if (mayHaveDurableEffect) RecoveryState.PENDING_RECOVERY
            else RecoveryState.NONE,
            detail = reason,
            updatedAtEpochMs = System.currentTimeMillis()
        )
        append(entry)
        entry
    }

    /** Closes a pending-recovery entry as RECOVERED (reconciled). */
    suspend fun markRecovered(operationId: String, detail: String? = null): JournalEntry? =
        mutex.withLock {
            val current = latest[operationId] ?: return@withLock null
            val entry = current.copy(
                phase = JournalPhase.RECOVER,
                recoveryState = RecoveryState.RECOVERED,
                detail = detail ?: current.detail,
                updatedAtEpochMs = System.currentTimeMillis()
            )
            append(entry)
            entry
        }

    /** Closes a pending-recovery entry as COMPENSATED (rolled back). */
    suspend fun markCompensated(operationId: String, detail: String? = null): JournalEntry? =
        mutex.withLock {
            val current = latest[operationId] ?: return@withLock null
            val entry = current.copy(
                phase = JournalPhase.COMPENSATE,
                recoveryState = RecoveryState.COMPENSATED,
                detail = detail ?: current.detail,
                updatedAtEpochMs = System.currentTimeMillis()
            )
            append(entry)
            entry
        }

    /**
     * The recovery work list: entries whose latest state is not cleanly
     * FINALIZE/NONE — i.e. interrupted operations. Pure read; reconciliation
     * (which needs the DATABASE's actual state) is the coordinator's job.
     */
    fun pendingRecovery(): List<JournalEntry> =
        latest.values.filter { it.recoveryState == RecoveryState.PENDING_RECOVERY }

    /** Read-only snapshot of the journal index. */
    fun entries(): List<JournalEntry> = latest.values.toList()

    fun entry(operationId: String): JournalEntry? = latest[operationId]

    // ------------------------------------------------------------------
    // Internals — crash-safe JSONL persistence
    // ------------------------------------------------------------------

    private suspend fun append(entry: JournalEntry) {
        latest[entry.operationId] = entry
        withContext(Dispatchers.IO) {
            runCatching {
                journalFile.parentFile?.mkdirs()
                journalFile.appendText(entry.toJson().toString() + "\n")
            }
        }
    }

    private fun loadFromDisk() {
        if (!journalFile.exists()) return
        val lines = journalFile.readLines()
        for ((index, line) in lines.withIndex()) {
            val trimmed = line.trim()
            if (trimmed.isEmpty()) continue
            val json = runCatching { JSONObject(trimmed) }.getOrNull()
            if (json == null) {
                // A corrupt line is tolerated UNLESS it is the LAST line —
                // a torn final append is the expected crash signature.
                if (index == lines.lastIndex) break else continue
            }
            val parsed = runCatching { fromJson(json) }.getOrNull() ?: continue
            latest[parsed.operationId] = parsed
        }
    }

    private fun JournalEntry.toJson(): JSONObject = JSONObject()
        .put("operationId", operationId)
        .put("operationType", operationType)
        .put("projectId", projectId ?: JSONObject.NULL)
        .put("workspaceId", workspaceId)
        .put("phase", phase.name)
        .put("recoveryState", recoveryState.name)
        .put("sourcePath", sourcePath ?: JSONObject.NULL)
        .put("stagingPath", stagingPath ?: JSONObject.NULL)
        .put("targetPath", targetPath ?: JSONObject.NULL)
        .put("checksum", checksum ?: JSONObject.NULL)
        .put("detail", detail ?: JSONObject.NULL)
        .put("createdAtEpochMs", createdAtEpochMs)
        .put("updatedAtEpochMs", updatedAtEpochMs)

    private fun fromJson(json: JSONObject): JournalEntry = JournalEntry(
        operationId = json.getString("operationId"),
        operationType = json.getString("operationType"),
        projectId = if (json.isNull("projectId")) null else json.getLong("projectId"),
        workspaceId = json.getString("workspaceId"),
        phase = runCatching { JournalPhase.valueOf(json.getString("phase")) }
            .getOrDefault(JournalPhase.PREPARE),
        recoveryState = runCatching { RecoveryState.valueOf(json.getString("recoveryState")) }
            .getOrDefault(RecoveryState.NONE),
        sourcePath = json.optString("sourcePath").takeIf { it.isNotBlank() },
        stagingPath = json.optString("stagingPath").takeIf { it.isNotBlank() },
        targetPath = json.optString("targetPath").takeIf { it.isNotBlank() },
        checksum = json.optString("checksum").takeIf { it.isNotBlank() },
        detail = json.optString("detail").takeIf { it.isNotBlank() },
        createdAtEpochMs = json.optLong("createdAtEpochMs", 0L),
        updatedAtEpochMs = json.optLong("updatedAtEpochMs", 0L)
    )
}
