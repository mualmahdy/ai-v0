package com.example.domain.core.resource

import com.example.domain.core.llm.LlmMessage

/**
 * P0.5 — Execution input carrier (implementation detail).
 *
 * The LOCKED DecisionRecord (Section F) has no input-payload field ("Fields on
 * DecisionRecord beyond Section F" are forbidden, Section M). The actual content
 * to execute (prompt, query, embedding texts) therefore travels alongside the
 * decision as a separate execution-input object; it is runtime input, NOT
 * decision state, and is never persisted inside the DecisionRecord.
 */
data class ResourceExecutionInput(
    /** LLM generation input. */
    val prompt: String? = null,
    val systemPrompt: String? = null,
    val conversationHistory: List<LlmMessage> = emptyList(),
    /** Web-search input. */
    val searchQuery: String? = null,
    /** Embedding input. */
    val embeddingTexts: List<String> = emptyList(),
    /** Free-form execution metadata (e.g. stepId, taskId echo). */
    val metadata: Map<String, String> = emptyMap()
)
