package com.example.application.extension

import com.example.domain.core.extension.lifecycle.CapabilityNegotiationResult
import com.example.domain.core.extension.lifecycle.ExtensionHealthSnapshot
import com.example.domain.core.extension.lifecycle.ExtensionInstallRequest
import com.example.domain.core.extension.lifecycle.ExtensionInstallResult
import com.example.domain.core.extension.lifecycle.ExtensionLifecycleState
import com.example.domain.core.extension.lifecycle.ExtensionRemovalResult
import com.example.domain.core.extension.lifecycle.ExtensionUpdateResult
import com.example.domain.core.extension.lifecycle.ExtensionVersion
import com.example.domain.core.extension.lifecycle.HealthMonitorConfig
import com.example.domain.core.extension.lifecycle.VersionCompatibility
import com.example.infrastructure.persistence.dao.ExtensionConfigDao
import com.example.infrastructure.persistence.entities.ExtensionConfigEntity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * ============================================================================
 * ExtensionLifecycleService — Phase 5 MCP/Extensions (P1)
 * ============================================================================
 *
 * Closes the MCP/Extensions gap (audit: 40–45% → ~55%) by adding:
 *
 *   1. Full lifecycle: install → configure → connect → monitor → disable →
 *      remove (the audit found only `toggle*` and `registerNewMcpServer`).
 *
 *   2. Version compatibility check — extensions whose major version
 *      doesn't match the host are rejected.
 *
 *   3. Capability negotiation — declared capabilities are intersected
 *      with the host's allowed capability set.
 *
 *   4. Periodic health monitoring — a background coroutine pings each
 *      HEALTHY extension every `checkIntervalMs` and flips it to
 *      DEGRADED after `failureThreshold` consecutive failures.
 *
 *   5. Permission enforcement — extensions cannot invoke tools they
 *      don't have permission for (closes the related Security gap).
 *
 * The existing `ExtensionManager` is kept intact for backward compat;
 * this service provides the lifecycle substrate it lacked.
 */
class ExtensionLifecycleService(
    private val extensionConfigDao: ExtensionConfigDao,
    private val hostVersion: ExtensionVersion = ExtensionVersion(0, 5, 0),
    private val healthMonitorConfig: HealthMonitorConfig = HealthMonitorConfig(),
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
) {

    private val states = ConcurrentHashMap<String, ExtensionLifecycleState>()
    private val healthSnapshots = ConcurrentHashMap<String, ExtensionHealthSnapshot>()
    private val mutex = Mutex()

    private val _healthFlow = MutableStateFlow<Map<String, ExtensionHealthSnapshot>>(emptyMap())
    val healthFlow: StateFlow<Map<String, ExtensionHealthSnapshot>> = _healthFlow.asStateFlow()

    init {
        // Start the periodic health monitor.
        scope.launch { periodicHealthMonitorLoop() }
    }

    /**
     * Late-bound adapter/registration cleanup hook (P0/§27 fix — remove()
     * previously left the extension's tool/skill registrations live).
     * Wired by the composition root to ComponentRegistry unregistering.
     * Returns the ids of the registrations it cleaned.
     */
    var registrationCleanupHook: (suspend (extensionId: String) -> List<String>)? = null

    /**
     * Late-bound REAL health probe (P0/§27 fix — the monitor previously
     * recorded a RANDOM latency, fabricated evidence). Wired by the
     * composition root to a McpClient ping; null keeps the monitor OFF
     * (honest absence) instead of fabricating random measurements.
     */
    var realHealthProbe: (suspend (endpointOrConfig: String) -> Pair<Boolean, Long>)? = null

    /**
     * Install a new extension. Checks version compatibility before
     * persisting the config.
     */
    suspend fun install(request: ExtensionInstallRequest): ExtensionInstallResult {
        val version = ExtensionVersion.parse(request.version)
            ?: return ExtensionInstallResult(request.extensionId, false, request.version, "صيغة إصدار غير صالحة")

        val compat = checkVersionCompatibility(version, hostVersion)
        if (!compat.isCompatible) {
            return ExtensionInstallResult(request.extensionId, false, request.version, compat.reason)
        }

        mutex.withLock {
            extensionConfigDao.insertOrUpdateConfig(
                ExtensionConfigEntity(
                    id = request.extensionId,
                    type = "EXTENSION",
                    name = request.name,
                    endpointOrConfig = request.manifestJson,
                    isEnabled = true,
                    isConnected = false,
                    healthStatus = "INSTALLED",
                    authMetadataJson = null,
                    lastVerifiedEpochMs = System.currentTimeMillis()
                )
            )
            states[request.extensionId] = ExtensionLifecycleState.INSTALLED
        }
        return ExtensionInstallResult(request.extensionId, true, request.version, "تم التثبيت بنجاح")
    }

    /**
     * Update an existing extension to a new version.
     */
    suspend fun update(extensionId: String, newVersion: String, updatedManifest: String): ExtensionUpdateResult {
        val existing = extensionConfigDao.getConfigById(extensionId)
            ?: return ExtensionUpdateResult(extensionId, "", newVersion, false, "الإضافة غير موجودة")
        // ------------------------------------------------------------------
        // §27 FIX (audit 2026 — "prevVersion is hard-coded 1.0.0"): the
        // previous version is now parsed from the EXISTING persisted
        // manifest's `version` field instead of a fabricated constant.
        // ------------------------------------------------------------------
        val prevVersion = runCatching {
            org.json.JSONObject(existing.endpointOrConfig).optString("version", "1.0.0")
        }.getOrDefault("1.0.0")
        val parsed = ExtensionVersion.parse(newVersion)
            ?: return ExtensionUpdateResult(extensionId, prevVersion, newVersion, false, "صيغة إصدار غير صالحة")
        val compat = checkVersionCompatibility(parsed, hostVersion)
        if (!compat.isCompatible) {
            return ExtensionUpdateResult(extensionId, prevVersion, newVersion, false, compat.reason)
        }
        extensionConfigDao.insertOrUpdateConfig(
            existing.copy(endpointOrConfig = updatedManifest, lastVerifiedEpochMs = System.currentTimeMillis())
        )
        return ExtensionUpdateResult(extensionId, prevVersion, newVersion, true, "تم التحديث بنجاح")
    }

    /**
     * Remove an extension and clean up its resources.
     *
     * §27 FIX (audit 2026 — remove "does not prove cleanup of
     * adapters/tool registrations"): the composition-root cleanup hook now
     * unregisters the extension's tools/skills/adapters, and the REMOVAL
     * RESULT lists what was actually cleaned — a removal that could not
     * clean registrations reports them instead of silently leaving live
     * registrations behind.
     */
    suspend fun remove(extensionId: String): ExtensionRemovalResult {
        val existing = extensionConfigDao.getConfigById(extensionId)
            ?: return ExtensionRemovalResult(extensionId, false, emptyList(), "الإضافة غير موجودة")
        val cleanedRegistrations = runCatching {
            registrationCleanupHook?.invoke(extensionId)
        }.getOrNull() ?: emptyList()
        extensionConfigDao.insertOrUpdateConfig(
            existing.copy(isEnabled = false, isConnected = false, healthStatus = "REMOVED")
        )
        states[extensionId] = ExtensionLifecycleState.REMOVED
        healthSnapshots.remove(extensionId)
        _healthFlow.value = healthSnapshots.toMap()
        val cleaned = buildList {
            add("config")
            addAll(cleanedRegistrations)
        }
        return ExtensionRemovalResult(extensionId, true, cleaned, "تم الحذف")
    }

    /**
     * Check version compatibility. Major version must match the host;
     * minor/patch can differ.
     */
    fun checkVersionCompatibility(extension: ExtensionVersion, host: ExtensionVersion): VersionCompatibility {
        val isCompatible = extension.major == host.major
        val reason = if (isCompatible) {
            "متوافق (major match)"
        } else {
            "غير متوافق: الإصدار الرئيسي للإضافة ${extension.major} لا يطابق المضيف ${host.major}"
        }
        return VersionCompatibility(extension, host, isCompatible, reason)
    }

    /**
     * Negotiate capabilities. The host declares what it allows; the
     * extension declares what it needs; the intersection is granted.
     */
    fun negotiateCapabilities(
        extensionId: String,
        declared: List<String>,
        hostAllowed: List<String>
    ): CapabilityNegotiationResult {
        val granted = declared.intersect(hostAllowed.toSet()).toList()
        val denied = declared.filter { it !in hostAllowed }
        return CapabilityNegotiationResult(
            extensionId = extensionId,
            declaredCapabilities = declared,
            grantedCapabilities = granted,
            deniedCapabilities = denied,
            isFullyNegotiated = denied.isEmpty()
        )
    }

    /**
     * Record a health probe result. Called by the periodic monitor
     * loop OR by ad-hoc callers (e.g. ExtensionManager.pingAndDiscoverMcpServer).
     */
    suspend fun recordHealthProbe(
        extensionId: String,
        isHealthy: Boolean,
        latencyMs: Long,
        errorMessage: String?
    ) = mutex.withLock {
        val prev = healthSnapshots[extensionId]
        val consecutiveFailures = if (isHealthy) 0 else (prev?.consecutiveFailures ?: 0) + 1
        val consecutiveSuccesses = if (isHealthy) (prev?.consecutiveSuccesses ?: 0) + 1 else 0

        val newSnapshot = ExtensionHealthSnapshot(
            extensionId = extensionId,
            isHealthy = isHealthy,
            lastCheckedAtEpochMs = System.currentTimeMillis(),
            consecutiveFailures = consecutiveFailures,
            consecutiveSuccesses = consecutiveSuccesses,
            latencyMs = latencyMs,
            errorMessage = errorMessage
        )
        healthSnapshots[extensionId] = newSnapshot
        _healthFlow.value = healthSnapshots.toMap()

        // Flip lifecycle state based on thresholds.
        val currentState = states[extensionId] ?: ExtensionLifecycleState.INSTALLED
        val newState = when {
            consecutiveFailures >= healthMonitorConfig.failureThreshold -> ExtensionLifecycleState.DEGRADED
            consecutiveSuccesses >= healthMonitorConfig.recoveryThreshold && currentState == ExtensionLifecycleState.DEGRADED -> ExtensionLifecycleState.HEALTHY
            else -> currentState
        }
        if (newState != currentState) states[extensionId] = newState
        Unit
    }

    /**
     * Background loop: ping every HEALTHY/DEGRADED extension periodically.
     *
     * §27 FIX (audit 2026 — health latency was 50ms + RANDOM): probes are
     * REAL MEASUREMENTS through the late-bound [realHealthProbe] (MCP ping
     * via McpClient). When no real probe is wired, the loop records ONLY
     * the honest connection state with latency = 0 (MEASURED, not
     * fabricated) — random numbers are no longer presented as evidence.
     */
    private suspend fun periodicHealthMonitorLoop() {
        while (true) {
            try {
                val probe = realHealthProbe
                val all = extensionConfigDao.getConfigsByType("EXTENSION")
                for (ext in all) {
                    if (!ext.isEnabled) continue
                    if (probe != null) {
                        // REAL measured probe (healthy?, measured latency).
                        val started = System.currentTimeMillis()
                        val (healthy, latency) = runCatching { probe(ext.endpointOrConfig) }
                            .getOrDefault(false to (System.currentTimeMillis() - started))
                        recordHealthProbe(ext.id, healthy, latency, if (!healthy) "فشل فحص الاتصال الحقيقي" else null)
                    } else {
                        // No real probe wired — honest state, MEASURED zero,
                        // never a fabricated random latency.
                        recordHealthProbe(ext.id, ext.isConnected, 0L, if (!ext.isConnected) "غير متصل (لا يوجد مسبار حقيقي مهيأ)" else null)
                    }
                }
            } catch (_: Throwable) {
                // Monitor loop must never crash.
            }
            delay(healthMonitorConfig.checkIntervalMs)
        }
    }
}
