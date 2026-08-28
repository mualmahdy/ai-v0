package com.example.domain.core.workspace

import com.example.domain.core.Outcome
import com.example.domain.core.agent.AgentId
import com.example.domain.core.task.TaskId

/**
 * First-class Resource types in the AI-V0 Workspace.
 */
enum class ResourceType {
    WORKSPACE,
    PROJECT,
    TASK,
    CONVERSATION,
    AGENT,
    MODEL,
    PROVIDER,
    TOOL,
    SKILL,
    PLUGIN,
    MCP_SERVER,
    INTEGRATION,
    FILE,
    DOCUMENT,
    MEMORY,
    KNOWLEDGE,
    EXECUTION,
    DECISION,
    OBSERVATION,
    RESULT,
    EVENT
}

/**
 * Representation of a node in the Workspace Resource Graph.
 */
data class ResourceNode(
    val id: String,
    val type: ResourceType,
    val name: String,
    val metadata: Map<String, String> = emptyMap(),
    val createdAtTimestampMs: Long = System.currentTimeMillis()
)

/**
 * Relation types between resources in the computational environment.
 */
enum class ResourceEdgeType {
    CONTAINS,
    PRODUCED_BY,
    DEPENDS_ON,
    CONSUMES,
    EXECUTES_ON,
    OBSERVES,
    DECIDED_BY,
    GENERATES_EVENT,
    REFERENCES_KNOWLEDGE,
    USES_TOOL,
    DISCOVERED_BY
}

/**
 * Directed edge representing relationship between two resources.
 */
data class ResourceEdge(
    val sourceId: String,
    val targetId: String,
    val type: ResourceEdgeType,
    val weight: Float = 1.0f,
    val metadata: Map<String, String> = emptyMap()
)

/**
 * Immutable Resource Graph holding the connected topology of the active Workspace.
 */
data class ResourceGraph(
    val nodes: Map<String, ResourceNode> = emptyMap(),
    val edges: List<ResourceEdge> = emptyList()
) {
    fun addNode(node: ResourceNode): ResourceGraph {
        return copy(nodes = nodes + (node.id to node))
    }

    fun addEdge(edge: ResourceEdge): ResourceGraph {
        return copy(edges = edges + edge)
    }

    fun getNode(id: String): ResourceNode? = nodes[id]

    fun getOutgoingEdges(sourceId: String): List<ResourceEdge> =
        edges.filter { it.sourceId == sourceId }

    fun getIncomingEdges(targetId: String): List<ResourceEdge> =
        edges.filter { it.targetId == targetId }

    fun getRelatedNodes(id: String, edgeType: ResourceEdgeType? = null): List<ResourceNode> {
        val targetIds = edges.filter { it.sourceId == id && (edgeType == null || it.type == edgeType) }
            .map { it.targetId }
        return targetIds.mapNotNull { nodes[it] }
    }
}
