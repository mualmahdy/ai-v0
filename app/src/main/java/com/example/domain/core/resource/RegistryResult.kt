package com.example.domain.core.resource

/**
 * P0.2 — RegistryResult (APPROVED-BASELINE v2.1, Section D — LOCKED, corrected
 * hierarchy per the document's own correction: NotFound must NOT inherit from
 * RejectedInvalidTransition).
 *
 * Explicit states only; never null.
 */
sealed interface RegistryResult {
    object Success : RegistryResult

    /** RULE REG-1: duplicate logical identity registration is rejected. No overwrite, no silent merge. */
    data class RejectedDuplicate(val resourceId: ResourceId) : RegistryResult

    /** An invalid lifecycle transition was requested (Section E transition table). */
    data object RejectedInvalidTransition : RegistryResult

    /** Lookup target does not exist in the registry. */
    data class NotFound(val resourceId: ResourceId) : RegistryResult

    /** Unexpected failure (storage error, etc.). */
    data class Error(val cause: Throwable) : RegistryResult
}
