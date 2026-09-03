package com.example.domain.core.extension

import com.example.domain.core.capability.CapabilityType

/**
 * Lifecycle state of an installed Skill.
 */
enum class SkillState {
    AVAILABLE,
    INSTALLED,
    ENABLED,
    DISABLED
}

/**
 * Manifest definition of a reusable high-level Skill.
 *
 * FIX DOM-P2-22: Previously defaulted to `isVerified = true`. Every skill was marked
 * verified by default with no verification process. Now defaults to `false` — a skill
 * must be explicitly verified (via the Phase 7 verification pipeline) before isVerified
 * can be set to true.
 */
data class SkillManifest(
    val id: String,
    val name: String,
    val version: String,
    val description: String,
    val category: String = "DEVELOPMENT",
    val requiredCapabilities: Set<CapabilityType> = emptySet(),
    val requiredTools: List<String> = emptyList(),
    val requiredModels: List<String> = emptyList(),
    val permissions: List<String> = emptyList(),
    val state: SkillState = SkillState.AVAILABLE,
    val workflowTemplate: String? = null,
    val author: String = "AI-V0 Core Community",
    val isVerified: Boolean = false,
    val installedTimestampMs: Long? = null
)
