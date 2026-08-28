package com.example.infrastructure.tools

import com.example.domain.core.Outcome
import com.example.domain.core.storage.StorageFailure
import com.example.domain.core.storage.WorkspaceFileEntry
import com.example.domain.core.tools.ToolDeclaration
import com.example.domain.core.tools.ToolFailure
import com.example.domain.core.tools.ToolInput
import com.example.domain.core.tools.ToolOutput
import com.example.domain.core.tools.ToolParameter
import com.example.domain.ports.storage.WorkspaceStoragePort
import com.example.domain.ports.tools.ToolPort

/**
 * Clean Infrastructure Adapter for workspace file system operations.
 */
class FileSystemTool(
    private val storagePort: WorkspaceStoragePort,
    private val defaultProjectId: Long = 1L
) : ToolPort {

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
        requiresHumanConsent = false
    )

    override suspend fun execute(input: ToolInput): Outcome<ToolOutput, ToolFailure> {
        val action = input.arguments["action"]?.toString()?.lowercase() ?: "list"
        val path = input.arguments["path"]?.toString() ?: ""
        val content = input.arguments["content"]?.toString() ?: ""

        return when (action) {
            "read" -> {
                if (path.isBlank()) {
                    return Outcome.Error(
                        failure = ToolFailure.InvalidParameters(listOf("path"), "يجب تحديد مسار الملف للقراءة."),
                        diagnosticMessage = "المسار غير محدد."
                    )
                }
                when (val result: Outcome<String, StorageFailure> = storagePort.readFile(defaultProjectId, path)) {
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
                when (val result: Outcome<Unit, StorageFailure> = storagePort.writeFile(defaultProjectId, path, content)) {
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
                when (val result: Outcome<List<WorkspaceFileEntry>, StorageFailure> = storagePort.listFiles(defaultProjectId, path.ifBlank { null })) {
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
                when (val result: Outcome<Unit, StorageFailure> = storagePort.deleteFile(defaultProjectId, path)) {
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
