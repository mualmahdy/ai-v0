package com.example.domain.core.resource

/**
 * P0.1 — Resource Identity Types (APPROVED-BASELINE v2.1, Section C — LOCKED).
 *
 * The four locked identity abstractions. No additional identity abstractions are
 * permitted beyond these four (Section M — Forbidden).
 *
 * Identity semantics (Section C, Locked):
 * - [ProviderId]  answers WHICH vendor/source. Permanent. A new provider means a new id.
 * - [ServiceId]   answers WHICH model/service. Permanent per model. A different model means a new id.
 * - [ResourceId]  answers WHAT resource this is. Stable across configuration edits.
 *                 Only a genuine resource replacement (different serviceId or providerId)
 *                 changes it.
 * - [ConfigurationVersion] answers WHICH config revision. Increments on any config
 *                 change (key rotation, endpoint, params).
 *
 * RULE ID-6: The string format of [ResourceId] is an implementation detail;
 * uniqueness is enforced by ResourceRegistryService, never by string parsing.
 */
@JvmInline
value class ProviderId(val value: String) {
    init {
        require(value.isNotBlank()) { "ProviderId must not be blank" }
    }
}

@JvmInline
value class ServiceId(val value: String) {
    init {
        require(value.isNotBlank()) { "ServiceId must not be blank" }
    }
}

/**
 * Stable logical identity of a resource. Never encodes endpoint, key, or adapter
 * details (P0.1 rules). Once registered and persisted it is never mutated
 * (RULE ID-8): superseded resources are marked, not renamed.
 */
@JvmInline
value class ResourceId(val value: String) {
    init {
        require(value.isNotBlank()) { "ResourceId must not be blank" }
    }
}

/**
 * Monotonically increasing configuration revision counter.
 *
 * RULE ID-1: API key rotation      -> increment, ResourceId unchanged.
 * RULE ID-2: Endpoint URL change   -> increment, ResourceId unchanged.
 * RULE ID-3: Generation parameters -> increment, ResourceId unchanged.
 */
@JvmInline
value class ConfigurationVersion(val value: Int) {
    init {
        require(value >= 0) { "ConfigurationVersion must be >= 0" }
    }

    /** Returns the next configuration revision (version + 1). */
    fun increment(): ConfigurationVersion = ConfigurationVersion(this.value + 1)

    companion object {
        /** The initial configuration revision assigned at registration time. */
        val INITIAL: ConfigurationVersion = ConfigurationVersion(1)
    }
}
