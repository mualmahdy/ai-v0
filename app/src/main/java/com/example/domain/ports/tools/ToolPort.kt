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
