package com.example.presentation.state

import com.example.domain.core.DegradedReason
import com.example.domain.core.agent.AgentDefinition
import com.example.domain.core.capability.CapabilityDescriptor
import com.example.domain.core.events.ExecutionEvent
import com.example.domain.core.memory.MemoryEntry
import com.example.domain.core.memory.ScoredMemoryRecord
import com.example.domain.core.storage.ProjectMetadata
import com.example.domain.core.storage.WorkspaceFileEntry

enum class ActiveNavigationTab {
    STUDIO,
    MEMORY_RAG,
    FILES,
    CAPABILITIES,
    SETTINGS
}

data class ExecutionStepItem(
    val id: String,
    val title: String,
    val detail: String,
    val isRunning: Boolean = false,
    val isSuccess: Boolean = false,
    val isError: Boolean = false,
    val isDegraded: Boolean = false
)

data class UiState(
    val activeTab: ActiveNavigationTab = ActiveNavigationTab.STUDIO,
    val activeProject: ProjectMetadata? = null,
    val activeAgent: AgentDefinition? = null,
    val availableAgents: List<AgentDefinition> = emptyList(),
    val promptInput: String = "",
    val isExecuting: Boolean = false,
    val executionLog: List<ExecutionEvent> = emptyList(),
    val streamText: String = "",
    val isDegraded: Boolean = false,
    val degradedReason: DegradedReason? = null,
    val diagnosticBanner: String? = null,
    val currentTokensConsumed: Int = 0,
    val sessionTotalTokens: Int = 0,
    val remainingBudget: Int = 30000,
    // Memory Tab State
    val memoryQuery: String = "",
    val retrievedMemories: List<ScoredMemoryRecord> = emptyList(),
    val allMemories: List<MemoryEntry> = emptyList(),
    val isSearchingMemory: Boolean = false,
    val newMemoryContent: String = "",
    // Files Tab State
    val workspaceFiles: List<WorkspaceFileEntry> = emptyList(),
    val selectedFileContent: String? = null,
    val selectedFilePath: String? = null,
    val isFileLoading: Boolean = false,
    // Capabilities Tab State
    val capabilities: List<CapabilityDescriptor> = emptyList(),
    // Error notification
    val errorMessage: String? = null
)
