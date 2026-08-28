package com.example.domain.core

/**
 * Standard Operational Outcome Contract.
 *
 * Strictly models the 3-state operational paradigm:
 * - [Success]: Full, uncompromised operational success.
 * - [Degraded]: Partial success or operational fallback with explicit, machine-readable reason and diagnostics.
 * - [Error]: Operational or domain failure with structured failure descriptors.
 *
 * This outcome is machine-consumable by:
 * Orchestrator, Workflow Engine, Tool Execution, Search, Memory, Model Generation, Security, and UI Diagnostics.
 */
sealed interface Outcome<out Value, out Failure> {

    /**
     * Represents complete and valid operational execution.
     */
    data class Success<out Value>(
        val value: Value,
        val metadata: OutcomeMetadata = OutcomeMetadata.Empty
    ) : Outcome<Value, Nothing>

    /**
     * Represents partial success or degraded operation (e.g. lexical search fallback,
     * embedding unavailable, truncated context, tool executed with warning).
     *
     * MUST NOT be treated as a silent Success or as an Error.
     */
    data class Degraded<out Value, out Failure>(
        val partialValue: Value?,
        val reason: DegradedReason,
        val diagnosticMessage: String,
        val underlyingFailure: Failure? = null,
        val metadata: OutcomeMetadata = OutcomeMetadata.Empty
    ) : Outcome<Value, Failure>

    /**
     * Represents a definite operational failure.
     */
    data class Error<out Failure>(
        val failure: Failure,
        val diagnosticMessage: String = "",
        val metadata: OutcomeMetadata = OutcomeMetadata.Empty
    ) : Outcome<Nothing, Failure>
}

/**
 * Machine-readable categorized reasons for degraded execution.
 */
enum class DegradedReason(val code: String, val userFriendlyLabel: String) {
    EMBEDDING_UNAVAILABLE("embedding_unavailable", "نموذج التضمين غير متاح (تم التحول للبحث النصي)"),
    SEARCH_PROVIDER_PARTIAL("search_provider_partial", "مزود البحث أعاد نتائج جزئية أو غير مكتملة"),
    LEXICAL_FALLBACK("lexical_fallback", "استرجاع معجمي بديل لعدم توفر متجهات دلالية"),
    CONTEXT_TRUNCATED("context_truncated", "تم اقتطاع السياق للالتزام بحدود التوكنز"),
    FALLBACK_MODEL_USED("fallback_model_used", "تم استخدام نموذج بديل بسبب انشغال النموذج الأساسي"),
    TOOL_WARNING("tool_warning", "الأداة نفذت العملية مع تنبيهات غير حرجة"),
    BUDGET_APPROACHING_LIMIT("budget_approaching_limit", "الميزانية تقترب من الحد الأقصى المسموح"),
    RATE_LIMIT_BACKOFF("rate_limit_backoff", "تم تقليل معدل الطلبات بسبب حدود المزود"),
    PLATFORM_CAPABILITY_RESTRICTED("platform_capability_restricted", "القدرة مقيدة بواسطة بيئة تشغيل أندرويد"),
    CACHE_FALLBACK("cache_fallback", "تم استخدام بيانات مخبأة لتعذر الوصول للشبكة"),
    UNKNOWN_DEGRADATION("unknown_degradation", "انخفاض غير مصنف في جودة التشغيل")
}

/**
 * Metadata associated with an outcome (timing, tokens, telemetry).
 */
data class OutcomeMetadata(
    val durationMs: Long = 0L,
    val tokensConsumed: Int = 0,
    val providerId: String? = null,
    val attributes: Map<String, String> = emptyMap()
) {
    companion object {
        val Empty = OutcomeMetadata()
    }
}

/**
 * Functional extensions for clean, non-crashing outcome handling.
 */
inline fun <T, E, R> Outcome<T, E>.map(transform: (T) -> R): Outcome<R, E> {
    return when (this) {
        is Outcome.Success -> Outcome.Success(transform(value), metadata)
        is Outcome.Degraded -> Outcome.Degraded(
            partialValue = partialValue?.let(transform),
            reason = reason,
            diagnosticMessage = diagnosticMessage,
            underlyingFailure = underlyingFailure,
            metadata = metadata
        )
        is Outcome.Error -> this
    }
}

inline fun <T, E, R> Outcome<T, E>.flatMap(transform: (T) -> Outcome<R, E>): Outcome<R, E> {
    return when (this) {
        is Outcome.Success -> transform(value)
        is Outcome.Degraded -> {
            if (partialValue != null) {
                when (val next = transform(partialValue)) {
                    is Outcome.Success -> Outcome.Degraded(
                        partialValue = next.value,
                        reason = reason,
                        diagnosticMessage = diagnosticMessage,
                        underlyingFailure = underlyingFailure,
                        metadata = metadata
                    )
                    is Outcome.Degraded -> Outcome.Degraded(
                        partialValue = next.partialValue,
                        reason = next.reason,
                        diagnosticMessage = "${diagnosticMessage}; ${next.diagnosticMessage}",
                        underlyingFailure = next.underlyingFailure ?: underlyingFailure,
                        metadata = next.metadata
                    )
                    is Outcome.Error -> next
                }
            } else {
                Outcome.Degraded(
                    partialValue = null,
                    reason = reason,
                    diagnosticMessage = diagnosticMessage,
                    underlyingFailure = underlyingFailure,
                    metadata = metadata
                )
            }
        }
        is Outcome.Error -> this
    }
}

fun <T, E> Outcome<T, E>.isSuccess(): Boolean = this is Outcome.Success
fun <T, E> Outcome<T, E>.isDegraded(): Boolean = this is Outcome.Degraded
fun <T, E> Outcome<T, E>.isError(): Boolean = this is Outcome.Error

fun <T, E> Outcome<T, E>.valueOrNull(): T? = when (this) {
    is Outcome.Success -> value
    is Outcome.Degraded -> partialValue
    is Outcome.Error -> null
}
