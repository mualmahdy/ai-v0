package com.example.domain.ports.llm

import com.example.domain.core.Outcome
import com.example.domain.core.events.ExecutionEvent
import com.example.domain.core.llm.LlmFailure
import com.example.domain.core.llm.LlmRequest
import com.example.domain.core.llm.LlmResponse
import com.example.domain.core.llm.SafeProviderMetadata
import kotlinx.coroutines.flow.Flow

/**
 * Standard Port for LLM Providers.
 *
 * Implements non-streaming and streaming generation contracts.
 * Strictly decoupled from specific vendor SDKs.
 */
interface LlmProviderPort {
    val providerId: String
    val metadata: SafeProviderMetadata

    /**
     * Executes a standard non-streaming LLM request.
     */
    suspend fun generate(request: LlmRequest): Outcome<LlmResponse, LlmFailure>

    /**
     * Streams operational execution events (chunks, tool calls, telemetry).
     */
    fun stream(request: LlmRequest, executionId: String): Flow<ExecutionEvent>
}
