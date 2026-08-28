package com.example.domain.core.workspace

import com.example.domain.core.network.NetworkPolicy

/**
 * Workspace entity representing the user's active computational domain.
 */
data class Workspace(
    val id: String,
    val name: String,
    val description: String,
    val activeProjectId: Long,
    val networkPolicy: NetworkPolicy = NetworkPolicy.HYBRID,
    val resourceGraph: ResourceGraph = ResourceGraph(),
    val settings: Map<String, String> = emptyMap(),
    val createdAtTimestampMs: Long = System.currentTimeMillis(),
    val lastAccessedTimestampMs: Long = System.currentTimeMillis()
)
