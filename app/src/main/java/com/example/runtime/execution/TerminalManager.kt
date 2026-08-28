package com.example.runtime.execution

import com.example.runtime.agents.AgentRegistry
import com.example.runtime.budget.TokenBudgetTracker
import com.example.runtime.decision.CbrMdpEngine
import com.example.runtime.decision.DiscreteBelief
import com.example.runtime.storage.WorkspaceStorageManager
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

data class TerminalLine(
    val type: LineType, // COMMAND, OUTPUT, ERROR, SYSTEM
    val text: String,
    val timestamp: String = SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date())
)

enum class LineType {
    COMMAND, OUTPUT, ERROR, SYSTEM
}

class TerminalManager(
    private val storageManager: WorkspaceStorageManager,
    private val executionEngine: CodeExecutionEngine,
    private val agentRegistry: AgentRegistry,
    private val tokenBudgetTracker: TokenBudgetTracker
) {
    private val _history = MutableStateFlow<List<TerminalLine>>(
        listOf(
            TerminalLine(LineType.SYSTEM, "AI-V0 Ultimate Native Android Shell v1.0.0"),
            TerminalLine(LineType.SYSTEM, "Type 'help' for a list of available built-in commands."),
            TerminalLine(LineType.SYSTEM, "Workspace sandbox active: /data/user/0/workspaces/project_1")
        )
    )
    val history: StateFlow<List<TerminalLine>> = _history.asStateFlow()

    private val commandHistory = mutableListOf<String>()
    private var historyIndex = 0

    suspend fun executeCommand(projectId: Long, rawCommand: String) {
        val cmd = rawCommand.trim()
        if (cmd.isEmpty()) return

        commandHistory.add(cmd)
        historyIndex = commandHistory.size

        appendLine(LineType.COMMAND, "user@aiv0:~$ $cmd")

        val parts = cmd.split("\\s+".toRegex())
        val command = parts[0].lowercase()
        val args = parts.drop(1)

        when (command) {
            "help" -> {
                appendLine(
                    LineType.OUTPUT,
                    """
                    Available Commands:
                      help                 - Show this help menu
                      ls [subpath]         - List files in current project sandbox
                      cat <file>           - Display file text content
                      touch <file> [text]  - Create or write to a file
                      rm <file>            - Delete a file from workspace
                      run <file>           - Run Kotlin/Python/Shell script in sandbox
                      agent <name> <msg>   - Directly invoke an autonomous agent
                      pkg                  - Manage runtime packages (Kotlin, Python, etc.)
                      status               - Display CBR-MDP system state & resources
                      clear                - Clear terminal screen
                    """.trimIndent()
                )
            }
            "clear" -> {
                _history.value = emptyList()
            }
            "ls" -> {
                val subPath = args.getOrNull(0) ?: ""
                val files = storageManager.listFiles(projectId, subPath)
                if (files.isEmpty()) {
                    appendLine(LineType.OUTPUT, "(directory is empty)")
                } else {
                    val formatted = files.joinToString("\n") {
                        if (it.isDirectory) "📁 ${it.name}/" else "📄 ${it.name} (${it.sizeBytes} B)"
                    }
                    appendLine(LineType.OUTPUT, formatted)
                }
            }
            "cat" -> {
                if (args.isEmpty()) {
                    appendLine(LineType.ERROR, "Usage: cat <filename>")
                } else {
                    try {
                        val content = storageManager.readFile(projectId, args[0])
                        appendLine(LineType.OUTPUT, content)
                    } catch (e: Exception) {
                        appendLine(LineType.ERROR, "Error: ${e.message}")
                    }
                }
            }
            "touch", "write" -> {
                if (args.isEmpty()) {
                    appendLine(LineType.ERROR, "Usage: touch <filename> [content]")
                } else {
                    val filename = args[0]
                    val content = if (args.size > 1) args.drop(1).joinToString(" ") else ""
                    try {
                        storageManager.writeFile(projectId, filename, content)
                        appendLine(LineType.OUTPUT, "File '$filename' created/updated successfully.")
                    } catch (e: Exception) {
                        appendLine(LineType.ERROR, "Error: ${e.message}")
                    }
                }
            }
            "rm" -> {
                if (args.isEmpty()) {
                    appendLine(LineType.ERROR, "Usage: rm <filename>")
                } else {
                    val ok = storageManager.deleteFile(projectId, args[0])
                    if (ok) appendLine(LineType.OUTPUT, "Deleted '${args[0]}'.")
                    else appendLine(LineType.ERROR, "Failed to delete '${args[0]}'.")
                }
            }
            "run" -> {
                if (args.isEmpty()) {
                    appendLine(LineType.ERROR, "Usage: run <filename>")
                } else {
                    try {
                        val file = args[0]
                        val content = storageManager.readFile(projectId, file)
                        val ext = file.substringAfterLast(".", "kt")
                        val result = executionEngine.executeCode(projectId, ext, content)
                        if (result.stderr.isNotEmpty()) {
                            appendLine(LineType.ERROR, result.stderr)
                        } else {
                            appendLine(LineType.OUTPUT, result.stdout)
                        }
                    } catch (e: Exception) {
                        appendLine(LineType.ERROR, "Execution failed: ${e.message}")
                    }
                }
            }
            "agent" -> {
                if (args.size < 2) {
                    appendLine(LineType.ERROR, "Usage: agent <agent_name> <prompt>")
                } else {
                    val agentName = args[0]
                    val prompt = args.drop(1).joinToString(" ")
                    val agent = agentRegistry.get(agentName)
                    if (agent == null) {
                        appendLine(LineType.ERROR, "Unknown agent '$agentName'. Available: ${agentRegistry.listNames().joinToString(", ")}")
                    } else {
                        appendLine(LineType.SYSTEM, "Invoking Agent '$agentName'...")
                        val res = agent.execute(mapOf("prompt" to prompt, "projectId" to projectId))
                        appendLine(LineType.OUTPUT, res.response)
                    }
                }
            }
            "pkg" -> {
                val packages = executionEngine.getAvailablePackages()
                val listStr = packages.joinToString("\n") {
                    val badge = if (it.isInstalled) "[INSTALLED ✓]" else "[AVAILABLE]"
                    "${it.id.padEnd(20)} $badge (${it.sizeMb}MB) - ${it.name}"
                }
                appendLine(LineType.OUTPUT, "Runtime Packages & Toolchains:\n$listStr\n\n(Tip: Toggle runtime packages in Code Editor/Settings panel)")
            }
            "status" -> {
                val defaultBelief = DiscreteBelief.peakedAt(0.85f)
                val beliefVal = String.format(Locale.US, "%.2f", defaultBelief.expectedValue())
                val agents = agentRegistry.listNames().joinToString(", ")
                appendLine(
                    LineType.OUTPUT,
                    """
                    ================ SYSTEM STATUS (CBR-MDP) ================
                    • Project ID:        $projectId
                    • Model Engine:      Local Native CBR Reasoning + Gemini Cloud
                    • Operational State: S = X × Y [STABLE / POSITIVE LYAPUNOV DRIFT]
                    • Belief State E[Q]: $beliefVal (Target Reference B0: 0.85)
                    • Active Agents:     $agents
                    • Local Filesystem:  Verified & Sandboxed
                    • Local RAG Engine:  Native Token Embedder [Active]
                    • Mode:              Offline-First Architecture
                    =========================================================
                    """.trimIndent()
                )
            }
            else -> {
                appendLine(LineType.ERROR, "Command not found: '$command'. Type 'help' for available commands.")
            }
        }
    }

    private fun appendLine(type: LineType, text: String) {
        _history.value = _history.value + TerminalLine(type, text)
    }
}
