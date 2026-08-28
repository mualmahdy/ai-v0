package com.example.infrastructure.tools

import com.example.domain.core.Outcome
import com.example.domain.core.tools.ToolDeclaration
import com.example.domain.core.tools.ToolFailure
import com.example.domain.core.tools.ToolInput
import com.example.domain.core.tools.ToolOutput
import com.example.domain.core.tools.ToolParameter
import com.example.domain.ports.tools.ToolPort
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Clean Infrastructure Adapter for running isolated, safe diagnostics commands.
 *
 * Strictly adheres to Android Sandbox realities: Does NOT pretend to have full root/bash terminal,
 * but executes genuine built-in safe diagnostics (memory, disk, time, echo, env info).
 */
class SafeDiagnosticsTool(
    private val sandboxDir: File
) : ToolPort {

    override val declaration: ToolDeclaration = ToolDeclaration(
        name = "safe_diagnostics_tool",
        description = "تشغيل أوامر الفحص والتشخيص الآمنة داخل بيئة أندرويد المعزولة.",
        parameters = listOf(
            ToolParameter(
                name = "command",
                type = "string",
                description = "الأمر المراد تشخيصه: sysinfo, meminfo, diskusage, date, ping_echo",
                isRequired = true,
                enumValues = listOf("sysinfo", "meminfo", "diskusage", "date", "ping_echo")
            ),
            ToolParameter(
                name = "payload",
                type = "string",
                description = "بيانات أو معاملات اختيارية للأمر",
                isRequired = false
            )
        ),
        isSensitive = false,
        requiresHumanConsent = false
    )

    override suspend fun execute(input: ToolInput): Outcome<ToolOutput, ToolFailure> {
        val command = input.arguments["command"]?.toString()?.lowercase() ?: "sysinfo"
        val payload = input.arguments["payload"]?.toString() ?: ""

        val runtime = Runtime.getRuntime()
        val mb = 1024 * 1024

        return when (command) {
            "sysinfo" -> {
                val info = buildString {
                    appendLine("=== Android AI-V0 System Diagnostics ===")
                    appendLine("OS: Android (Linux Kernel)")
                    appendLine("Available Processors: ${runtime.availableProcessors()}")
                    appendLine("Max JVM Memory: ${runtime.maxMemory() / mb} MB")
                    appendLine("Total JVM Memory: ${runtime.totalMemory() / mb} MB")
                    appendLine("Free JVM Memory: ${runtime.freeMemory() / mb} MB")
                    appendLine("Sandbox Root: ${sandboxDir.absolutePath}")
                }
                Outcome.Success(ToolOutput(content = info))
            }
            "meminfo" -> {
                val usedMem = (runtime.totalMemory() - runtime.freeMemory()) / mb
                val maxMem = runtime.maxMemory() / mb
                val freeMem = runtime.freeMemory() / mb
                val info = "ذاكرة JVM: المستخدم $usedMem MB من أصل $maxMem MB (المتبقي $freeMem MB)"
                Outcome.Success(ToolOutput(content = info))
            }
            "diskusage" -> {
                val totalSpace = sandboxDir.totalSpace / mb
                val usableSpace = sandboxDir.usableSpace / mb
                val usedSpace = totalSpace - usableSpace
                val info = "مساحة التخزين في الـ Sandbox: $usedSpace MB مستخدمة / $usableSpace MB متاحة (الإجمالي: $totalSpace MB)"
                Outcome.Success(ToolOutput(content = info))
            }
            "date" -> {
                val now = SimpleDateFormat("yyyy-MM-dd HH:mm:ss z", Locale.getDefault()).format(Date())
                Outcome.Success(ToolOutput(content = "الوقت الحالي للنظام: $now"))
            }
            "ping_echo" -> {
                val response = if (payload.isNotBlank()) "Echo: $payload" else "Pong! System active and responsive."
                Outcome.Success(ToolOutput(content = response))
            }
            else -> {
                Outcome.Error(
                    failure = ToolFailure.CapabilityUnavailable(
                        capabilityName = command,
                        message = "الأمر '$command' غير متاح كأمر آمن في بيئة أندرويد المعزولة."
                    ),
                    diagnosticMessage = "الأمر غير معتمد."
                )
            }
        }
    }
}
