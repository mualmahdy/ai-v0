package com.example.application.governed

import com.example.domain.core.Outcome
import com.example.domain.core.security.governance.AdmissionDecision
import com.example.domain.core.security.governance.ToolAdmissionRequest
import com.example.domain.core.security.governance.PrincipalType
import com.example.domain.core.storage.StorageFailure
import com.example.domain.core.tools.ToolDeclaration
import com.example.domain.core.tools.ToolFailure
import com.example.domain.core.tools.ToolInput
import com.example.domain.core.tools.ToolOutput
import com.example.domain.core.tools.ToolParameter
import com.example.domain.core.capability.CapabilityType
import com.example.domain.core.capability.Locality
import com.example.domain.core.capability.NetworkRequirement
import com.example.domain.core.capability.SideEffectClassification
import com.example.domain.core.tools.patch.FilePatchEngine
import com.example.domain.core.tools.patch.FilePatchRequest
import com.example.domain.core.tools.patch.PatchHunk
import com.example.domain.ports.storage.WorkspaceStoragePort
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.UUID

/**
 * ============================================================================
 * CodingToolchainService — Phase 1 (Coding Tools + Closed Loop primitives)
 * ============================================================================
 *
 * The governed coding toolchain: every tool executes ONLY through
 * [AdmissionControlService.admit]. There is no direct execution path —
 * `executeTool` refuses to run when admission did not explicitly ALLOW.
 *
 * Tools (risk profiled in AdmissionControlService.defaultRiskProfiles):
 *   list_files / read_file / search_files          (LOW  — read-only)
 *   create_file / apply_patch / write_file         (MEDIUM — mutation, patch preferred)
 *   rename_file                                    (MEDIUM — mutation)
 *   delete_file                                    (HIGH  — destructive)
 *   run_code / run_tests                           (CRITICAL — sandbox required;
 *                                                  HONESTLY unavailable on
 *                                                  Android in-app: admission
 *                                                  denies with
 *                                                  SANDBOX_INSUFFICIENT_ISOLATION
 *                                                  rather than executing
 *                                                  without true isolation)
 */
class CodingToolchainService(
    private val admission: AdmissionControlService,
    private val workspaceStorage: WorkspaceStoragePort
) {

    /** Declarations of the governed coding tools (schema source of truth). */
    val declarations: Map<String, ToolDeclaration> get() = Companion.declarations

    /**
     * Executes a governed tool call. The admission pipeline runs FIRST and
     * its decision is FINAL: no execution without an explicit ALLOW.
     */
    suspend fun executeTool(
        executionId: String,
        principalId: String,
        principalType: PrincipalType = PrincipalType.AGENT,
        toolName: String,
        arguments: Map<String, Any?>,
        projectId: Long,
        workspaceId: String,
        approvalTokenId: String? = null
    ): Outcome<ToolOutput, ToolFailure> = withContext(Dispatchers.Default) {

        // ---- Build the admission request with path arguments -------------
        val pathArgs = extractPathArguments(toolName, arguments)
        val request = ToolAdmissionRequest(
            requestId = "adm_${UUID.randomUUID()}",
            executionId = executionId,
            toolName = toolName,
            arguments = arguments,
            principalType = principalType,
            principalId = principalId,
            workspaceId = workspaceId,
            projectId = projectId,
            pathArguments = pathArgs,
            approvalTokenId = approvalTokenId
        )

        // ---- THE ONLY GATE. No bypass. ------------------------------------
        val admissionResult = admission.admit(request)
        if (admissionResult.decision != AdmissionDecision.ALLOWED) {
            return@withContext Outcome.Error(
                ToolFailure.SecurityDenied(
                    ruleName = admissionResult.denyStage?.stageCode
                        ?: if (admissionResult.decision == AdmissionDecision.NEEDS_HUMAN_APPROVAL) "HUMAN_APPROVAL_PENDING" else "ADMISSION_REFUSED",
                    message = admissionResult.denyReason
                        ?: "بانتظار موافقة بشرية: ${admissionResult.approvalRequestId}"
                )
            )
        }

        // ---- Governed execution -------------------------------------------
        executeAdmitted(toolName, arguments, projectId)
    }

    private suspend fun executeAdmitted(
        toolName: String,
        arguments: Map<String, Any?>,
        projectId: Long
    ): Outcome<ToolOutput, ToolFailure> {
        return when (toolName) {
            "list_files" -> listFiles(projectId, arguments["sub_directory"]?.toString())
            "read_file" -> readFile(projectId, arguments["path"]?.toString() ?: "")
            "search_files" -> searchFiles(projectId, arguments["query"]?.toString() ?: "", arguments["sub_directory"]?.toString())
            "create_file" -> createFile(projectId, arguments["path"]?.toString() ?: "", arguments["content"]?.toString() ?: "")
            "write_file" -> writeFile(projectId, arguments["path"]?.toString() ?: "", arguments["content"]?.toString() ?: "", arguments["expected_hash"]?.toString())
            "apply_patch" -> applyPatch(projectId, arguments)
            "rename_file" -> renameFile(projectId, arguments["from_path"]?.toString() ?: "", arguments["to_path"]?.toString() ?: "")
            "delete_file" -> deleteFile(projectId, arguments["path"]?.toString() ?: "")
            "run_code", "run_tests" -> Outcome.Error(
                ToolFailure.CapabilityUnavailable(
                    capabilityName = toolName,
                    message = "التنفيذ الحقيقي غير متاح: يتطلب عزلاً حقيقياً على مستوى العملية لا يوفره تشغيل داخل التطبيق. " +
                        "الرفض صريح — لا محاكاة ولا نجاح زائف."
                )
            )
            else -> Outcome.Error(ToolFailure.InternalExecutionError("أداة غير معروفة: $toolName"))
        }
    }

    // ---------------------- Tool implementations (REAL) ------------------ //

    private suspend fun listFiles(projectId: Long, subDirectory: String?): Outcome<ToolOutput, ToolFailure> {
        return when (val r = workspaceStorage.listFiles(projectId, subDirectory)) {
            is Outcome.Success -> Outcome.Success(
                ToolOutput(content = r.value.joinToString("\n") { it.relativePath })
            )
            is Outcome.Degraded -> Outcome.Degraded(
                partialValue = r.partialValue?.let { list -> ToolOutput(list.joinToString("\n") { it.relativePath }) },
                reason = r.reason,
                diagnosticMessage = r.diagnosticMessage,
                underlyingFailure = null
            )
            is Outcome.Error -> Outcome.Error(mapStorageFailure(r.failure, subDirectory ?: ""))
        }
    }

    private suspend fun readFile(projectId: Long, path: String): Outcome<ToolOutput, ToolFailure> {
        return when (val r = workspaceStorage.readFile(projectId, path)) {
            is Outcome.Success -> Outcome.Success(ToolOutput(content = r.value))
            is Outcome.Degraded -> Outcome.Degraded(
                partialValue = r.partialValue?.let { ToolOutput(it) },
                reason = r.reason,
                diagnosticMessage = r.diagnosticMessage
            )
            is Outcome.Error -> Outcome.Error(mapStorageFailure(r.failure, path))
        }
    }

    private suspend fun searchFiles(
        projectId: Long,
        query: String,
        subDirectory: String?
    ): Outcome<ToolOutput, ToolFailure> {
        if (query.isBlank()) {
            return Outcome.Error(ToolFailure.InvalidParameters(listOf("query"), "الاستعلام فارغ."))
        }
        val listing = when (val l = workspaceStorage.listFiles(projectId, subDirectory)) {
            is Outcome.Success -> l.value
            is Outcome.Degraded -> l.partialValue ?: emptyList()
            is Outcome.Error -> return Outcome.Error(mapStorageFailure(l.failure, subDirectory ?: ""))
        }
        val hits = mutableListOf<String>()
        for (entry in listing) {
            val read = workspaceStorage.readFile(projectId, entry.relativePath)
            val content = (read as? Outcome.Success)?.value ?: continue
            content.lineSequence().forEachIndexed { index, line ->
                if (line.contains(query, ignoreCase = true)) {
                    hits += "${entry.relativePath}:${index + 1}: ${line.trim().take(120)}"
                }
            }
        }
        return Outcome.Success(ToolOutput(content = if (hits.isEmpty()) "لا نتائج." else hits.take(200).joinToString("\n")))
    }

    private suspend fun createFile(projectId: Long, path: String, content: String): Outcome<ToolOutput, ToolFailure> {
        if (workspaceStorage.fileExists(projectId, path)) {
            return Outcome.Error(ToolFailure.InvalidParameters(listOf("path"), "الملف موجود بالفعل — استخدم apply_patch للتعديل."))
        }
        return when (val r = workspaceStorage.writeFile(projectId, path, content)) {
            is Outcome.Success -> Outcome.Success(ToolOutput(content = "تم إنشاء $path (${content.length} حرفاً)."))
            is Outcome.Degraded -> Outcome.Degraded(
                partialValue = ToolOutput("تم إنشاء $path مع تنبيه."),
                reason = r.reason, diagnosticMessage = r.diagnosticMessage
            )
            is Outcome.Error -> Outcome.Error(mapStorageFailure(r.failure, path))
        }
    }

    private suspend fun writeFile(
        projectId: Long,
        path: String,
        content: String,
        expectedHash: String?
    ): Outcome<ToolOutput, ToolFailure> {
        // Optimistic concurrency: verify expected hash BEFORE overwriting.
        if (expectedHash != null) {
            val current = when (val r = workspaceStorage.readFile(projectId, path)) {
                is Outcome.Success -> r.value
                is Outcome.Error -> return Outcome.Error(mapStorageFailure(r.failure, path))
                is Outcome.Degraded -> r.partialValue ?: return Outcome.Error(
                    ToolFailure.InternalExecutionError("تعذر التحقق من المحتوى الحالي قبل الكتابة.")
                )
            }
            val actual = FilePatchEngine.sha256Hex(current)
            if (!actual.equals(expectedHash, ignoreCase = true)) {
                return Outcome.Error(
                    ToolFailure.SecurityDenied(
                        ruleName = "OPTIMISTIC_CONCURRENCY_CONFLICT",
                        message = "المحتوى تغيّر منذ آخر قراءة (توقّع $expectedHash ووُجد $actual). الكتابة الصامتة فوق تعديل متزامن ممنوعة."
                    )
                )
            }
        }
        return when (val r = workspaceStorage.writeFile(projectId, path, content)) {
            is Outcome.Success -> Outcome.Success(
                ToolOutput(
                    content = "تمت كتابة $path (${content.length} حرفاً).",
                    attributes = mapOf("newHash" to FilePatchEngine.sha256Hex(content))
                )
            )
            is Outcome.Degraded -> Outcome.Degraded(
                partialValue = ToolOutput("تمت كتابة $path مع تنبيه."),
                reason = r.reason, diagnosticMessage = r.diagnosticMessage
            )
            is Outcome.Error -> Outcome.Error(mapStorageFailure(r.failure, path))
        }
    }

    private suspend fun applyPatch(projectId: Long, arguments: Map<String, Any?>): Outcome<ToolOutput, ToolFailure> {
        val path = arguments["path"]?.toString() ?: return Outcome.Error(
            ToolFailure.InvalidParameters(listOf("path"), "مسار الرقعة مفقود.")
        )
        @Suppress("UNCHECKED_CAST")
        val rawHunks = arguments["hunks"] as? List<Map<String, Any?>>
            ?: return Outcome.Error(ToolFailure.InvalidParameters(listOf("hunks"), "الرُقع مفقودة أو ليست قائمة."))
        val hunks = rawHunks.mapNotNull { raw ->
            val expect = raw["expect"]?.toString()
            val replaceWith = raw["replaceWith"]?.toString() ?: ""
            if (expect.isNullOrEmpty()) null else PatchHunk(expect, replaceWith)
        }
        if (hunks.isEmpty()) {
            return Outcome.Error(ToolFailure.InvalidParameters(listOf("hunks"), "لا رُقع صالحة."))
        }

        val current = when (val r = workspaceStorage.readFile(projectId, path)) {
            is Outcome.Success -> r.value
            is Outcome.Error -> if ((r.failure as? StorageFailure.FileNotFound) != null) null else return Outcome.Error(mapStorageFailure(r.failure, path))
            is Outcome.Degraded -> r.partialValue ?: return Outcome.Error(
                ToolFailure.InternalExecutionError("تعذر قراءة الملف قبل تطبيق الرقعة.")
            )
        }

        val patchRequest = FilePatchRequest(
            relativePath = path,
            hunks = hunks,
            expectedContentHash = arguments["expected_hash"]?.toString(),
            createIfMissing = current == null
        )
        val result = FilePatchEngine.apply(patchRequest, current)
        if (!result.isFullyApplied) {
            val reason = result.hunksRejected.joinToString("; ") { it.toString() }
            return Outcome.Error(ToolFailure.SecurityDenied(ruleName = "PATCH_REJECTED", message = reason))
        }
        return when (val w = workspaceStorage.writeFile(projectId, path, result.resultingContent)) {
            is Outcome.Success -> Outcome.Success(
                ToolOutput(
                    content = "تم تطبيق ${result.appliedHunks} رقعة على $path.",
                    attributes = mapOf("newHash" to result.resultingContentHash)
                )
            )
            is Outcome.Degraded -> Outcome.Degraded(
                partialValue = ToolOutput("تم تطبيق الرقعة مع تنبيه كتابة."),
                reason = w.reason, diagnosticMessage = w.diagnosticMessage
            )
            is Outcome.Error -> Outcome.Error(mapStorageFailure(w.failure, path))
        }
    }

    private suspend fun renameFile(projectId: Long, fromPath: String, toPath: String): Outcome<ToolOutput, ToolFailure> {
        if (fromPath == toPath) return Outcome.Error(
            ToolFailure.InvalidParameters(listOf("from_path", "to_path"), "المساران متطابقان.")
        )
        if (!workspaceStorage.fileExists(projectId, fromPath)) {
            return Outcome.Error(ToolFailure.PermissionDenied(fromPath, "المصدر غير موجود."))
        }
        if (workspaceStorage.fileExists(projectId, toPath)) {
            return Outcome.Error(
                ToolFailure.SecurityDenied(
                    ruleName = "RENAME_DESTINATION_EXISTS",
                    message = "الوجهة موجودة؛ الرفض بدل الكتابة فوقها."
                )
            )
        }
        val content = when (val r = workspaceStorage.readFile(projectId, fromPath)) {
            is Outcome.Success -> r.value
            is Outcome.Error -> return Outcome.Error(mapStorageFailure(r.failure, fromPath))
            is Outcome.Degraded -> r.partialValue ?: return Outcome.Error(
                ToolFailure.InternalExecutionError("تعذر قراءة المصدر قبل النقل.")
            )
        }
        val write = when (val r = workspaceStorage.writeFile(projectId, toPath, content)) {
            is Outcome.Success -> r
            is Outcome.Degraded -> r
            is Outcome.Error -> return Outcome.Error(mapStorageFailure(r.failure, toPath))
        }
        val delete = workspaceStorage.deleteFile(projectId, fromPath)
        return when {
            delete is Outcome.Error -> Outcome.Degraded(
                partialValue = ToolOutput("تم النقل إلى $toPath لكن حذف المصدر فشل — تحقق يدوي مطلوب."),
                reason = com.example.domain.core.DegradedReason.UNKNOWN_DEGRADATION,
                diagnosticMessage = (delete as Outcome.Error<StorageFailure>).failure.toString()
            )
            else -> Outcome.Success(ToolOutput(content = "تم النقل من $fromPath إلى $toPath."))
        }.let { outcome ->
            if (write is Outcome.Degraded && outcome is Outcome.Success) {
                Outcome.Degraded(
                    partialValue = outcome.value,
                    reason = write.reason,
                    diagnosticMessage = write.diagnosticMessage
                )
            } else outcome
        }
    }

    private suspend fun deleteFile(projectId: Long, path: String): Outcome<ToolOutput, ToolFailure> {
        return when (val r = workspaceStorage.deleteFile(projectId, path)) {
            is Outcome.Success -> Outcome.Success(ToolOutput(content = "تم حذف $path."))
            is Outcome.Degraded -> Outcome.Degraded(
                partialValue = ToolOutput("حُذف $path مع تنبيه."),
                reason = r.reason, diagnosticMessage = r.diagnosticMessage
            )
            is Outcome.Error -> Outcome.Error(mapStorageFailure(r.failure, path))
        }
    }

    private fun extractPathArguments(toolName: String, arguments: Map<String, Any?>): List<String> = when (toolName) {
        "read_file", "create_file", "write_file", "apply_patch", "delete_file" ->
            listOfNotNull(arguments["path"]?.toString())
        "rename_file" -> listOfNotNull(
            arguments["from_path"]?.toString(),
            arguments["to_path"]?.toString()
        )
        "list_files", "search_files" -> listOfNotNull(arguments["sub_directory"]?.toString())
        else -> emptyList()
    }

    private fun mapStorageFailure(failure: StorageFailure, path: String): ToolFailure = when (failure) {
        is StorageFailure.FileNotFound -> ToolFailure.PermissionDenied(path, "الملف غير موجود.")
        is StorageFailure.AccessDenied -> ToolFailure.SecurityDenied("STORAGE_ACCESS_DENIED", failure.toString())
        is StorageFailure.DiskSpaceExceeded -> ToolFailure.InternalExecutionError("مساحة القرص غير كافية.")
        is StorageFailure.ReadWriteError -> ToolFailure.InternalExecutionError(failure.message)
        is StorageFailure.CorruptedData -> ToolFailure.InternalExecutionError(failure.message)
    }

    companion object {
        /** Static declaration registry so admission can resolve schemas
         *  without constructing the (circularly dependent) service. */
        val declarations: Map<String, ToolDeclaration> = buildDeclarations()

        private fun buildDeclarations(): Map<String, ToolDeclaration> {
            val pathParam = ToolParameter("path", "string", "المسار النسبي داخل مساحة العمل", isRequired = true)
            return mapOf(
                "list_files" to ToolDeclaration(
                    name = "list_files",
                    description = "يسرد الملفات ضمن مجلد في مساحة العمل.",
                    parameters = listOf(ToolParameter("sub_directory", "string", "مجلد فرعي اختياري", isRequired = false)),
                    networkRequirement = NetworkRequirement.LOCAL_ONLY,
                    sideEffects = SideEffectClassification.READ_ONLY,
                    providedCapabilities = setOf(CapabilityType.FILE_READ),
                    locality = Locality.LOCAL_ON_DEVICE
                ),
                "read_file" to ToolDeclaration(
                    name = "read_file",
                    description = "يقرأ محتوى ملف نصي من مساحة العمل.",
                    parameters = listOf(pathParam),
                    networkRequirement = NetworkRequirement.LOCAL_ONLY,
                    sideEffects = SideEffectClassification.READ_ONLY,
                    providedCapabilities = setOf(CapabilityType.FILE_READ),
                    locality = Locality.LOCAL_ON_DEVICE
                ),
                "search_files" to ToolDeclaration(
                    name = "search_files",
                    description = "يبحث نصياً في ملفات مساحة العمل عن استعلام.",
                    parameters = listOf(
                        ToolParameter("query", "string", "نص البحث", isRequired = true),
                        ToolParameter("sub_directory", "string", "مجلد فرعي اختياري", isRequired = false)
                    ),
                    networkRequirement = NetworkRequirement.LOCAL_ONLY,
                    sideEffects = SideEffectClassification.READ_ONLY,
                    providedCapabilities = setOf(CapabilityType.FILE_READ),
                    locality = Locality.LOCAL_ON_DEVICE
                ),
                "create_file" to ToolDeclaration(
                    name = "create_file",
                    description = "ينشئ ملفاً جديداً بمحتوى (يفشل إذا كان موجوداً).",
                    parameters = listOf(pathParam, ToolParameter("content", "string", "المحتوى", isRequired = true)),
                    networkRequirement = NetworkRequirement.LOCAL_ONLY,
                    sideEffects = SideEffectClassification.IDEMPOTENT,
                    providedCapabilities = setOf(CapabilityType.FILE_WRITE),
                    locality = Locality.LOCAL_ON_DEVICE
                ),
                "write_file" to ToolDeclaration(
                    name = "write_file",
                    description = "يكتب محتوى ملف موجوداً (كتابة كاملة).",
                    parameters = listOf(
                        pathParam,
                        ToolParameter("content", "string", "المحتوى الجديد", isRequired = true),
                        ToolParameter("expected_hash", "string", "هاش المحتوى المتوقع لمنع الكتابة فوق تعديل متزامن", isRequired = false)
                    ),
                    networkRequirement = NetworkRequirement.LOCAL_ONLY,
                    sideEffects = SideEffectClassification.STATE_MUTATION,
                    providedCapabilities = setOf(CapabilityType.FILE_WRITE),
                    locality = Locality.LOCAL_ON_DEVICE
                ),
                "apply_patch" to ToolDeclaration(
                    name = "apply_patch",
                    description = "الأداة المفضلة للتعديل: رقع ذرّية موجهة بالسياق مع تحقق تزامني.",
                    parameters = listOf(
                        pathParam,
                        ToolParameter("hunks", "array", "قائمة الرُقع [{expect, replaceWith}]", isRequired = true),
                        ToolParameter("expected_hash", "string", "هاش المحتوى المتوقع (تزامن تفاؤلي)", isRequired = false)
                    ),
                    networkRequirement = NetworkRequirement.LOCAL_ONLY,
                    sideEffects = SideEffectClassification.STATE_MUTATION,
                    providedCapabilities = setOf(CapabilityType.FILE_WRITE),
                    locality = Locality.LOCAL_ON_DEVICE
                ),
                "rename_file" to ToolDeclaration(
                    name = "rename_file",
                    description = "يعيد تسمية/نقل ملف داخل مساحة العمل.",
                    parameters = listOf(
                        ToolParameter("from_path", "string", "المسار الحالي", isRequired = true),
                        ToolParameter("to_path", "string", "المسار الجديد", isRequired = true)
                    ),
                    networkRequirement = NetworkRequirement.LOCAL_ONLY,
                    sideEffects = SideEffectClassification.STATE_MUTATION,
                    providedCapabilities = setOf(CapabilityType.FILE_WRITE),
                    locality = Locality.LOCAL_ON_DEVICE
                ),
                "delete_file" to ToolDeclaration(
                    name = "delete_file",
                    description = "يحذف ملفاً من مساحة العمل (تدميري).",
                    parameters = listOf(pathParam),
                    networkRequirement = NetworkRequirement.LOCAL_ONLY,
                    sideEffects = SideEffectClassification.IRREVERSIBLE,
                    isSensitive = true,
                    requiresHumanConsent = true,
                    providedCapabilities = setOf(CapabilityType.FILE_WRITE),
                    locality = Locality.LOCAL_ON_DEVICE
                ),
                "run_code" to ToolDeclaration(
                    name = "run_code",
                    description = "تنفيذ شيفرة داخل صندوق رملي بعزل حقيقي (غير متاح بأمان داخل تطبيق أندرويد حالياً).",
                    parameters = listOf(
                        ToolParameter("language", "string", "لغة الشيفرة", isRequired = true, enumValues = listOf("kotlin", "java", "python")),
                        ToolParameter("code", "string", "الشيفرة المراد تنفيذها", isRequired = true)
                    ),
                    networkRequirement = NetworkRequirement.LOCAL_ONLY,
                    sideEffects = SideEffectClassification.IRREVERSIBLE,
                    isSensitive = true,
                    requiresHumanConsent = true,
                    providedCapabilities = setOf(CapabilityType.SHELL_EXECUTION),
                    locality = Locality.LOCAL_ON_DEVICE
                ),
                "run_tests" to ToolDeclaration(
                    name = "run_tests",
                    description = "تشغيل اختبارات المشروع داخل صندوق رملي (يتطلب عزلاً حقيقياً).",
                    parameters = listOf(ToolParameter("target", "string", "هدف الاختبارات", isRequired = false)),
                    networkRequirement = NetworkRequirement.LOCAL_ONLY,
                    sideEffects = SideEffectClassification.IRREVERSIBLE,
                    isSensitive = true,
                    requiresHumanConsent = true,
                    providedCapabilities = setOf(CapabilityType.SHELL_EXECUTION),
                    locality = Locality.LOCAL_ON_DEVICE
                )
            )
        }
    }
}
