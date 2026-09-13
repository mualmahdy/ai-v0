package com.example.domain.ports.tools

import com.example.domain.core.Outcome
import com.example.domain.core.tools.ToolDeclaration
import com.example.domain.core.tools.ToolFailure
import com.example.domain.core.tools.ToolInput
import com.example.domain.core.tools.ToolOutput

/**
 * Standard Port for executable tools.
 */
interface ToolPort {
    val declaration: ToolDeclaration

    /**
     * Executes the tool with the provided inputs and context.
     */
    suspend fun execute(input: ToolInput): Outcome<ToolOutput, ToolFailure>
}

/**
 * GAP-02 part 2 (Design Closure 2026, ADR-2c — governed toolchain as the
 * production path): a [ToolPort] whose `execute()` runs its OWN admission
 * pipeline (the same ordered stages, the same AdmissionControlService) and
 * is therefore THE single gate for its calls.
 *
 * The execution boundary (ExecutionService) MUST NOT re-admit a
 * self-admitting tool: a second `admit()` would double-consume the one-shot
 * approval token, duplicate rate-limit slots and budget authorization, and
 * write a second audit trail. The boundary keeps only its non-consent
 * defenses (declaration resolution, tool-lifecycle enforcement, agent
 * capability check) and delegates the rest to the tool's internal pipeline.
 */
interface SelfAdmittingTool : ToolPort
