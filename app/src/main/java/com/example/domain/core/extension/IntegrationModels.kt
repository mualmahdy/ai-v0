package com.example.domain.core.extension

import com.example.domain.core.provider.HealthStatus

/**
 * Generic external integration descriptor (Google Drive, GitHub, Notion, Dropbox, etc.).
 *
 * FIX DOM-P2-21: Previously defaulted to `health = HEALTHY`. An integration that has
 * never been connected shouldn't claim HEALTHY. Now defaults to `health = UNKNOWN`.
 * `isConnected` is already an explicit constructor parameter (no default), which is correct.
 */
data class IntegrationDescriptor(
    val id: String,
    val name: String,
    val serviceType: String, // "GOOGLE_DRIVE", "GITHUB", "DROPBOX", "NOTION"
    val isConnected: Boolean,
    val accountIdentifier: String? = null,
    val health: HealthStatus = HealthStatus.UNKNOWN,
    val supportedOperations: List<String> = emptyList(), // "READ_FILES", "WRITE_COMMITS", "LIST_DOCS"
    val requiredScopes: List<String> = emptyList(),
    val iconName: String = "ic_integration",
    val lastSyncTimestampMs: Long? = null
)
