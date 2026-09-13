package com.example.application.governed

import com.example.domain.core.Outcome
import com.example.domain.core.execution.ExecutionScope
import com.example.domain.core.security.governance.PrincipalType
import com.example.domain.core.tools.ToolDeclaration
import com.example.domain.core.tools.ToolFailure
import com.example.domain.core.tools.ToolInput
import com.example.domain.core.tools.ToolOutput
import com.example.domain.ports.tools.SelfAdmittingTool
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.UUID
import kotlin.coroutines.coroutineContext

/**
 * ============================================================================
 * GovernedCodingToolAdapter — GAP-02 part 2 (Design Closure 2026, ADR-2c)
 * ============================================================================
 *
 * THE production bridge that makes [CodingToolchainService] reachable from
 * the agent/model execution paths: one adapter per governed tool name
 * (read_file / write_file / apply_patch / …), registered into the runtime
 * ComponentRegistry exactly like any other [ToolPort], so the LLM's tool
 * advertisement and the decision engine's resource selection see the
 * governed toolchain.
 *
 * SINGLE GATE: the adapter is [SelfAdmittingTool] — `execute()` delegates
 * to [CodingToolchainService.executeTool], which runs the FULL ordered
 * admission pipeline (the same AdmissionControlService instance the
 * execution boundary uses) and consumes the one-shot approval token
 * exactly once at stage 9. The execution boundary deliberately does NOT
 * re-admit self-admitting tools (double admission would double-consume the
 * token and duplicate rate-limit/budget/audit work).
 *
 * Context binding mirrors [com.example.infrastructure.tools.FileSystemTool]:
 * the PINNED [ExecutionScope] (workspace + sandbox project bound at
 * execution launch) wins; user-driven calls outside executions fall back to
 * the ACTIVE workspace's own project; missing context fails HONESTLY
 * (no shared-project fallback, no fabricated workspace).
 *
 * GAP-02 consent semantics through this path (unified at admission stage 9):
 *  - approve-once  : the approval token rides the request (looked up via
 *                    [approvalTokenProvider]) and is consumed one-shot;
 *  - allow-always  : the production consentGrantPort sees a standing EXECUTE
 *                    grant (agent- or device-user-scoped) and allows without
 *                    consuming any token;
 *  - neither       : stage 9 persists a PENDING approval request and pauses
 *                    (NEEDS_HUMAN_APPROVAL) — the governance surface renders
 *                    it so the user can decide.
 */
class GovernedCodingToolAdapter(
    private val toolchain: CodingToolchainService,
    private val governedToolName: String,
    /** Fallback workspace id for user-driven calls outside executions. */
    private val workspaceIdProvider: () -> String?,
    /** Fallback sandbox project id (the ACTIVE workspace's OWN project). */
    private val projectIdProvider: () -> Long?,
    /** Attribution for unattributed (user-driven) calls: the local device user. */
    private val fallbackPrincipalId: () -> String,
    /**
     * GAP-02 (ADR-2c) token TRANSPORT — a NON-consuming lookup of an
     * already-APPROVED request's token for (executionId, toolName). The
     * one-shot consumption stays inside admission stage 9.
     */
    private val approvalTokenProvider: (suspend (executionId: String, toolName: String) -> String?)? = null
) : SelfAdmittingTool {

    override val declaration: ToolDeclaration =
        CodingToolchainService.Companion.declarations.getValue(governedToolName)

    override suspend fun execute(input: ToolInput): Outcome<ToolOutput, ToolFailure> =
        withContext(Dispatchers.Default) {
            val scope = coroutineContext[ExecutionScope.Key]
            val executionId = scope?.executionId
                ?: input.executionId
                ?: "adhoc_${UUID.randomUUID()}"
            val workspaceId = scope?.workspaceId?.takeIf { it.isNotBlank() && it != "unattributed" }
                ?: workspaceIdProvider()
            if (workspaceId.isNullOrBlank()) {
                return@withContext Outcome.Error(
                    ToolFailure.SecurityDenied(
                        ruleName = "WORKSPACE_CONTEXT_REQUIRED",
                        message = "لا توجد مساحة عمل مرتبطة بالتنفيذ — ترفض الأداة المحكومة العملها بدلاً من الانتماء الصامت لمساحة عشوائية."
                    )
                )
            }
            val projectId = scope?.projectId?.takeIf { it > 0 }
                ?: projectIdProvider()?.takeIf { it > 0 }
            if (projectId == null) {
                return@withContext Outcome.Error(
                    failure = ToolFailure.CapabilityUnavailable(
                        capabilityName = governedToolName,
                        message = "PROJECT_CONTEXT_REQUIRED: لا يوجد مشروع مرتبط بمساحة العمل الحالية — ترفض الأداة المحكومة الوصول."
                    ),
                    diagnosticMessage = "PROJECT_CONTEXT_REQUIRED"
                )
            }
            val principalId = input.principalId ?: fallbackPrincipalId()
            val principalType = if (input.principalId != null) PrincipalType.AGENT else PrincipalType.USER
            // Token transport only resolves INSIDE a real execution (an
            // ad-hoc user-driven call has no persisted approval context).
            val token = if (scope != null) {
                try {
                    approvalTokenProvider?.invoke(executionId, governedToolName)
                } catch (_: Exception) {
                    null // transport failure is honest: the pause path handles it
                }
            } else null

            toolchain.executeTool(
                executionId = executionId,
                principalId = principalId,
                principalType = principalType,
                toolName = governedToolName,
                arguments = input.arguments,
                projectId = projectId,
                workspaceId = workspaceId,
                approvalTokenId = token
            )
        }
}
