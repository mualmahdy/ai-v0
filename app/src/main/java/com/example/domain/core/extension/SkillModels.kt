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
 * GAP-19 (Design Closure 2026, ADR-6 step 6): a DECLARED parameter of a
 * skill — the run-skill form GENERATES from the manifest's parameter list
 * instead of branching on skill-id string matching (the dialog previously
 * hardcoded `skillId.contains("scaffold")`, so a new skill could never
 * expose its parameters without UI code).
 */
data class SkillParameterDefinition(
    val name: String,
    val label: String,
    val description: String? = null,
    val isRequired: Boolean = true,
    val isMultiline: Boolean = false,
    val defaultValue: String? = null
)

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
    /** Declared execution parameters (GAP-19 — the run form derives from these). */
    val parameters: List<SkillParameterDefinition> = emptyList(),
    val state: SkillState = SkillState.AVAILABLE,
    val workflowTemplate: String? = null,
    val author: String = "AI-V0 Core Community",
    val isVerified: Boolean = false,
    val installedTimestampMs: Long? = null
)
