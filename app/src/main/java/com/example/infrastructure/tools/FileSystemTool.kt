package com.example.infrastructure.tools

import com.example.domain.core.Outcome
import com.example.domain.core.capability.CapabilityType
import com.example.domain.core.capability.Locality
import com.example.domain.core.capability.NetworkRequirement
import com.example.domain.core.capability.SideEffectClassification
import com.example.domain.core.execution.ExecutionScope
import com.example.domain.core.storage.StorageFailure
import com.example.domain.core.storage.WorkspaceFileEntry
import com.example.domain.core.tools.ToolDeclaration
import com.example.domain.core.tools.ToolFailure
import com.example.domain.core.tools.ToolInput
import com.example.domain.core.tools.ToolOutput
import com.example.domain.core.tools.ToolParameter
import com.example.domain.ports.storage.WorkspaceStoragePort
import com.example.domain.ports.tools.ToolPort
import kotlin.coroutines.coroutineContext

/**
 * Clean Infrastructure Adapter for workspace file system operations.
 *
 * GAP-CLOSURE P0-04 + P0 CONVERGENCE: the sandbox project is resolved PER
 * CALL, in priority order:
 *
 *   1. The PINNED [ExecutionScope] projectId — when the tool runs inside an
 *      agent execution, the workspace pinned AT LAUNCH decides the target
 *      sandbox (a mid-run workspace switch can no longer re-target file
 *      operations to another workspace's sandbox).
 *   2. The [projectIdProvider] (wired by the AppContainer to the ACTIVE
 *      workspace's OWN project) — user-driven paths outside executions.
 *   3. Nothing → the tool fails honestly (PROJECT_CONTEXT_REQUIRED).
 *
 * The legacy `defaultProjectId = 1L` constructor fallback was REMOVED —
 * there is no construction that can silently re-arm the shared-project
 * cross-workspace data bleed.
 */
class FileSystemTool(
    private val storagePort: WorkspaceStoragePort,
    private val projectIdProvider: () -> Long?
) : ToolPort {

    /** Resolves the sandbox project for THIS call; null = fail honestly. */
    private suspend fun resolveProjectId(): Long? {
        // 1. Pinned execution scope (the workspace bound at execution launch).
        coroutineContext[ExecutionScope.Key]?.projectId?.let { pinned ->
            return pinned.takeIf { it > 0 }
        }
        // 2. Active workspace's own project (user-driven paths).
        return projectIdProvider()?.takeIf { it > 0 }
    }

    private fun noProjectFailure(): Outcome<ToolOutput, ToolFailure> = Outcome.Error(
        failure = ToolFailure.CapabilityUnavailable(
            capabilityName = "workspace_file_tool",
            message = "PROJECT_CONTEXT_REQUIRED: لا يوجد مشروع مرتبط بمساحة العمل الحالية — ترفض الأداة الوصول بدلاً من الكتابة في مشروع مشترك قديم."
        ),
        diagnosticMessage = "PROJECT_CONTEXT_REQUIRED"
    )

    override val declaration: ToolDeclaration = ToolDeclaration(
        name = "workspace_file_tool",
        description = "أداة إدارة وقراءة وكتابة ملفات مساحة العمل.",
        parameters = listOf(
            ToolParameter(
                name = "action",
                type = "string",
                description = "العملية المطلوبة: read, write, list, delete",
                isRequired = true,
                enumValues = listOf("read", "write", "list", "delete")
            ),
            ToolParameter(
                name = "path",
                type = "string",
                description = "المسار النسبي للملف داخل مساحة العمل",
                isRequired = false
            ),
            ToolParameter(
                name = "content",
                type = "string",
                description = "محتوى الملف عند الكتابة",
                isRequired = false
            )
        ),
        isSensitive = false,
        requiresHumanConsent = false,
        providedCapabilities = setOf(
            CapabilityType.FILE_STORAGE,
            CapabilityType.FILE_READ,
            CapabilityType.FILE_WRITE,
            CapabilityType.TOOL_EXECUTION
        ),
        networkRequirement = NetworkRequirement.LOCAL_ONLY,
        sideEffects = SideEffectClassification.STATE_MUTATION,
        locality = Locality.LOCAL_ON_DEVICE
    )


    override suspend fun execute(input: ToolInput): Outcome<ToolOutput, ToolFailure> {
        val action = input.arguments["action"]?.toString()?.lowercase() ?: "list"
        val path = input.arguments["path"]?.toString() ?: ""
        val content = input.arguments["content"]?.toString() ?: ""
        val projectId = resolveProjectId() ?: return noProjectFailure()

        return when (action) {
            "read" -> {
                if (path.isBlank()) {
                    return Outcome.Error(
                        failure = ToolFailure.InvalidParameters(listOf("path"), "يجب تحديد مسار الملف للقراءة."),
                        diagnosticMessage = "المسار غير محدد."
                    )
                }
                when (val result: Outcome<String, StorageFailure> = storagePort.readFile(projectId, path)) {
                    is Outcome.Success -> Outcome.Success(ToolOutput(content = result.value))
                    is Outcome.Degraded -> Outcome.Degraded(
                        partialValue = result.partialValue?.let { ToolOutput(content = it) },
                        reason = result.reason,
                        diagnosticMessage = result.diagnosticMessage
                    )
                    is Outcome.Error -> Outcome.Error(
                        failure = ToolFailure.InternalExecutionError(result.diagnosticMessage),
                        diagnosticMessage = result.diagnosticMessage
                    )
                }
            }
            "write" -> {
                if (path.isBlank()) {
                    return Outcome.Error(
                        failure = ToolFailure.InvalidParameters(listOf("path"), "يجب تحديد مسار الملف للكتابة.")
                    )
                }
                when (val result: Outcome<Unit, StorageFailure> = storagePort.writeFile(projectId, path, content)) {
                    is Outcome.Success -> Outcome.Success(ToolOutput(content = "تم حفظ الملف بنجاح في: $path"))
                    is Outcome.Degraded -> Outcome.Degraded(
                        partialValue = ToolOutput(content = "تم حفظ الملف مع تنبيه."),
                        reason = result.reason,
                        diagnosticMessage = result.diagnosticMessage
                    )
                    is Outcome.Error -> Outcome.Error(
                        failure = ToolFailure.InternalExecutionError(result.diagnosticMessage)
                    )
                }
            }
            "list" -> {
                when (val result: Outcome<List<WorkspaceFileEntry>, StorageFailure> = storagePort.listFiles(projectId, path.ifBlank { null })) {
                    is Outcome.Success -> {
                        val fileListStr = result.value.joinToString("\n") { file ->
                            val type = if (file.isDirectory) "[DIR]" else "[FILE]"
                            "$type ${file.relativePath} (${file.sizeBytes} B)"
                        }
                        Outcome.Success(ToolOutput(content = if (fileListStr.isBlank()) "المجلد فارغ." else fileListStr))
                    }
                    is Outcome.Degraded -> Outcome.Degraded(
                        partialValue = result.partialValue?.let { list ->
                            ToolOutput(content = list.joinToString("\n") { it.relativePath })
                        },
                        reason = result.reason,
                        diagnosticMessage = result.diagnosticMessage
                    )
                    is Outcome.Error -> Outcome.Error(
                        failure = ToolFailure.InternalExecutionError(result.diagnosticMessage)
                    )
                }
            }
            "delete" -> {
                if (path.isBlank()) {
                    return Outcome.Error(
                        failure = ToolFailure.InvalidParameters(listOf("path"), "يجب تحديد مسار الملف للحذف.")
                    )
                }
                when (val result: Outcome<Unit, StorageFailure> = storagePort.deleteFile(projectId, path)) {
                    is Outcome.Success -> Outcome.Success(ToolOutput(content = "تم حذف الملف بنجاح: $path"))
                    is Outcome.Degraded -> Outcome.Degraded(
                        partialValue = ToolOutput(content = "تم حذف الملف مع تنبيه."),
                        reason = result.reason,
                        diagnosticMessage = result.diagnosticMessage
                    )
                    is Outcome.Error -> Outcome.Error(
                        failure = ToolFailure.InternalExecutionError(result.diagnosticMessage)
                    )
                }
            }
            else -> Outcome.Error(
                failure = ToolFailure.InvalidParameters(listOf("action"), "العملية $action غير مدعومة.")
            )
        }
    }
}
