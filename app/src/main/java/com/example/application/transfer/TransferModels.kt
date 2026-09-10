package com.example.application.transfer

import com.example.domain.core.project.DependencyStatus

/**
 * ============================================================================
 * REPAIR ORDER §9–§14 — TRANSFER SUBSYSTEM MODELS
 * ============================================================================
 */

/** Uniform transfer outcome (import/export/clone/move). */
sealed class TransferOutcome {
    data class Success(
        val message: String,
        val importedFileCount: Int = 0,
        val skippedFileCount: Int = 0,
        val remappedIds: Int = 0
    ) : TransferOutcome()

    data class Failure(
        val code: String,
        val message: String,
        /** True when the destination was NOT mutated (validation-stage failure). */
        val destinationUntouched: Boolean = true
    ) : TransferOutcome()
}

/** REPAIR ORDER §12 — explicit conflict policies; REPLACE is never the default. */
enum class ImportConflictPolicy {
    /** Surface the conflict to the user (the default). */
    ASK,
    /** Import under a de-conflicted name (suffix). */
    RENAME,
    /** Overwrite the existing item (explicit choice only). */
    REPLACE,
    /** Merge content where semantically possible (knowledge documents). */
    MERGE,
    /** Skip the conflicting item, import the rest. */
    SKIP,
    /** Abort the whole import. */
    CANCEL
}

/**
 * REPAIR ORDER §12 — import compatibility report. Imported projects must
 * never silently pretend to be fully operational: unresolved dependencies
 * are listed explicitly.
 */
data class ImportCompatibilityReport(
    val compatible: Boolean,
    val packageSchemaVersion: Int,
    val findings: List<Finding>
) {
    enum class Severity { COMPATIBLE, MISSING_RESOURCE, INCOMPATIBLE_RESOURCE, MISSING_MODEL, MISSING_EMBEDDING, MISSING_TOOL, UNSUPPORTED_WORKFLOW, POLICY_DOWNGRADE }

    data class Finding(
        val severity: Severity,
        val dependencyType: String,
        val key: String,
        val detail: String,
        val status: DependencyStatus = DependencyStatus.RESOLVED
    )

    val hasBlockers: Boolean
        get() = findings.any {
            it.severity != Severity.COMPATIBLE && it.severity != Severity.POLICY_DOWNGRADE
        }
}

/** REPAIR ORDER §10/§12 — validation failure taxonomy for packages. */
enum class PackageValidationError {
    NOT_A_PACKAGE,
    MISSING_MANIFEST,
    UNSUPPORTED_SCHEMA,
    INCOMPATIBLE_APP,
    INVALID_MANIFEST,
    HASH_MISMATCH,
    PATH_TRAVERSAL_DETECTED,
    ARCHIVE_TOO_LARGE,
    ARCHIVE_CORRUPT,
    ENTRY_COUNT_EXCEEDED,
    MISSING_PROJECT_JSON,
    SECRET_INCLUSION_DETECTED
}

/** Staging/atomic-promotion import state machine. */
enum class ImportStage {
    READ,
    VALIDATE_MANIFEST,
    VALIDATE_SCHEMA,
    VALIDATE_PATHS,
    VALIDATE_LIMITS,
    VALIDATE_HASHES,
    RESOLVE_DEPENDENCIES,
    RESOLVE_IDS,
    DETECT_CONFLICTS,
    STAGE,
    IMPORT,
    REBUILD_INDEXES,
    VERIFY,
    COMMIT
}

/** Limits protecting against oversized/corrupted archives (§9). */
data class TransferLimits(
    val maxEntryCount: Int = 20_000,
    val maxUncompressedBytes: Long = 512L * 1024 * 1024,
    val maxSingleEntryBytes: Long = 128L * 1024 * 1024,
    val maxManifestBytes: Long = 4L * 1024 * 1024
) {
    companion object {
        val DEFAULT = TransferLimits()
    }
}
