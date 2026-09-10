package com.example.application.decision

import com.example.domain.core.decision.DecisionAction
import com.example.domain.core.decision.DecisionResult
import com.example.domain.core.task.TaskContract

/**
 * ============================================================================
 * REPAIR ORDER §31 — SAFE DECISION EXPLANATION
 * ============================================================================
 * Structured FACTS about a decision — never hidden chain-of-thought. Helps
 * diagnose incorrect agent behavior by exposing:
 *   - task intent/category + contract rationale
 *   - required capabilities
 *   - admissible action set (why an action was/wasn't available)
 *   - the selected action + score vs alternatives
 *   - policy constraints that applied
 *   - resource availability signals
 */
object DecisionExplanationService {

    data class Explanation(
        val intentCategory: String,
        val contractRationale: String,
        val requiredCapabilities: List<String>,
        val admissibleActionTypes: List<String>,
        val excludedActionTypes: List<String>,
        val selectedAction: String,
        val selectedTarget: String?,
        val score: Float?,
        val alternatives: List<Alternative>,
        val policyConstraint: String?,
        val highLevelReason: String
    ) {
        data class Alternative(val action: String, val target: String?, val score: Float?)
    }

    fun explain(
        contract: TaskContract?,
        decisionResult: DecisionResult,
        effectiveAutonomyPolicy: String?
    ): Explanation {
        val chosen: DecisionAction = decisionResult.chosenAction
        val admissible = contract?.admissibleActions?.map { it.code } ?: emptyList()
        val prohibited = contract?.prohibitedActions?.map { it.code } ?: emptyList()
        val alternatives: List<Explanation.Alternative> = decisionResult.evaluatedAlternatives
            .sortedByDescending { it.finalScore }
            .take(5)
            .map { scored ->
                Explanation.Alternative(
                    action = scored.action.type.code,
                    target = scored.action.targetId,
                    score = scored.finalScore
                )
            }
        val policyNote = effectiveAutonomyPolicy?.let {
            "السياسة الفعالة: $it — الأفعال الحساسة تخضع للموافقة قبل التنفيذ."
        }
        return Explanation(
            intentCategory = contract?.category?.name ?: "UNKNOWN",
            contractRationale = contract?.rationale ?: "لا يوجد عقد مهمة (مسار قديم).",
            requiredCapabilities = contract?.requiredCapabilities?.map { it.name } ?: emptyList(),
            admissibleActionTypes = admissible,
            excludedActionTypes = prohibited,
            selectedAction = chosen.type.code,
            selectedTarget = chosen.targetId,
            score = decisionResult.confidence,
            alternatives = alternatives,
            policyConstraint = policyNote,
            highLevelReason = decisionResult.rationale
        )
    }

    /** Human-readable rendering (Arabic-first, like the app's surfaces). */
    fun render(e: Explanation): String = buildString {
        appendLine("تفسير القرار (حقائق منظمة):")
        appendLine("- نية المهمة: ${e.intentCategory}")
        if (e.requiredCapabilities.isNotEmpty()) {
            appendLine("- القدرات المطلوبة: ${e.requiredCapabilities.joinToString()}")
        }
        if (e.admissibleActionTypes.isNotEmpty()) {
            appendLine("- الأفعال المسموحة: ${e.admissibleActionTypes.joinToString()}")
        }
        if (e.excludedActionTypes.isNotEmpty()) {
            appendLine("- الأفعال المستبعدة بعقد المهمة: ${e.excludedActionTypes.joinToString()}")
        }
        appendLine("- الإجراء المختار: ${e.selectedAction}" + (e.selectedTarget?.let { " ($it)" } ?: ""))
        e.score?.let { appendLine("- الثقة: ${"%.2f".format(it)}") }
        e.policyConstraint?.let { appendLine("- قيد السياسة: $it") }
        if (e.alternatives.isNotEmpty()) {
            appendLine("- بدائل مرتبة: " + e.alternatives.joinToString { a ->
                "${a.action}(${"%.2f".format(a.score ?: 0f)})"
            })
        }
        append("- السبب العام: ${e.highLevelReason}")
    }
}
