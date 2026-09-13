package com.example.application.usecases

import com.example.application.provider.ProviderControlPlaneService
import com.example.application.provider.ProviderPreset
import com.example.domain.core.Outcome
import com.example.domain.core.capability.CapabilityType
import com.example.domain.core.provider.Provider
import com.example.domain.core.provider.ProviderService
import com.example.domain.core.provider.ServiceConfiguration
import com.example.domain.core.provider.ServiceType
import com.example.domain.core.provider.offering.OfferingType
import com.example.domain.core.provider.offering.ServiceOffering

/**
 * ============================================================================
 * ConnectProviderUseCase — GAP-19 (Design Closure 2026, ADR-6 step 2)
 * ============================================================================
 *
 * Extracted from MainViewModel.connectProviderFullChain — the full provider
 * connection chain is APPLICATION business logic (7 governed steps against
 * the control plane) that lived in the ViewModel with VM-invented id
 * schemes. The ViewModel now maps [onStep] progress + the terminal
 * [Result] into its wizard UiState; every domain construction and control
 * plane decision lives HERE, testable without any presentation class.
 *
 * Chain: Provider → Service → Configuration(+vault key) → Offering →
 * Materialize → Validate (auto-promotes to ENABLED on success).
 */
class ConnectProviderUseCase(
    private val providerControlPlaneService: ProviderControlPlaneService
) {

    /** Terminal outcome of the chain; the VM renders it verbatim. */
    sealed interface Result {
        /** Validation passed; the resource auto-promoted to ENABLED. */
        data class Connected(val resourceId: String, val message: String) : Result

        /** Persisted successfully but the network validation failed. */
        data class SavedUnverified(val message: String) : Result

        /** A step failed; [step] is the 1-based wizard step, [message] the diagnostic. */
        data class Failed(val step: Int, val message: String) : Result

        /** Guard failure before the chain started (missing API key). */
        data class Rejected(val message: String) : Result
    }

    /** Progress callback: the 1-based wizard step + its display label. */
    suspend operator fun invoke(
        preset: ProviderPreset,
        providerName: String,
        endpointUrl: String,
        modelName: String,
        apiKey: String?,
        onStep: suspend (step: Int, label: String) -> Unit = { _, _ -> }
    ): Result {
        if (preset.requiresApiKey && apiKey.isNullOrBlank()) {
            return Result.Rejected("هذا المزوّد يتطلب مفتاح API — أدخل المفتاح ثم أعد المحاولة")
        }

        val providerId = "prov_${System.currentTimeMillis()}"
        val serviceId = "${providerId}_${preset.serviceType.code}"
        val authAlias = "${providerId}_key"
        val offeringId = when (preset.serviceType) {
            ServiceType.SEARCH -> "${providerId}_search"
            else -> "${providerId}_${modelName.replace(Regex("[^A-Za-z0-9._-]"), "_")}"
        }

        // 1. Provider
        onStep(1, "إنشاء المزوّد…")
        val provider = Provider(
            id = providerId,
            name = providerName,
            description = "${preset.displayName} — ${preset.description}",
            websiteUrl = preset.websiteUrl,
            isLocal = preset.isLocal,
            isEnabled = true
        )
        when (val r = providerControlPlaneService.createProvider(provider)) {
            is Outcome.Error -> return Result.Failed(1, "تعذر إنشاء المزوّد: ${r.diagnosticMessage}")
            else -> Unit
        }

        // 2. Service
        onStep(2, "تسجيل الخدمة (${preset.serviceType.displayName})…")
        val service = ProviderService(
            id = serviceId,
            providerId = providerId,
            name = "${preset.displayName} — ${preset.serviceType.displayName}",
            serviceType = preset.serviceType,
            supportedProtocolIds = listOf(preset.protocolId.code),
            isEnabled = true
        )
        when (val r = providerControlPlaneService.addService(service)) {
            is Outcome.Error -> return Result.Failed(2, "تعذر تسجيل الخدمة: ${r.diagnosticMessage}")
            else -> Unit
        }

        // 3. Configuration (+ vault key)
        onStep(3, "حفظ الإعدادات و المفتاح…")
        val config = ServiceConfiguration(
            id = "cfg_${serviceId}",
            serviceId = serviceId,
            protocolId = preset.protocolId,
            endpointUrl = endpointUrl,
            defaultOfferingId = modelName,
            authAlias = authAlias,
            isEnabled = true,
            isDefault = true
        )
        when (val r = providerControlPlaneService.saveConfiguration(config)) {
            is Outcome.Error -> return Result.Failed(3, "تعذر حفظ الإعدادات: ${r.diagnosticMessage}")
            else -> Unit
        }
        if (!apiKey.isNullOrBlank()) {
            when (val r = providerControlPlaneService.storeSecret(authAlias, apiKey)) {
                is Outcome.Error -> return Result.Failed(3, "تعذر حفظ المفتاح في القبو المشفّر: ${r.diagnosticMessage}")
                else -> Unit
            }
        }

        // 4. Offering
        onStep(4, "تسجيل النموذج/النقطة…")
        val offering = ServiceOffering(
            id = offeringId,
            serviceId = serviceId,
            offeringType = when (preset.serviceType) {
                ServiceType.SEARCH -> OfferingType.ENDPOINT
                else -> OfferingType.MODEL
            },
            name = modelName.ifBlank { preset.displayName },
            description = preset.description,
            contextWindowTokens = preset.contextWindowTokens,
            // GAP-25 (Design Closure 2026, honest capability declarations):
            // REASONING + STREAMING are UNVERIFIED defaults for LLM presets —
            // the wizard's validation step proves endpoint reachability +
            // auth via GET /models ONLY; it never probes streaming (SSE) or
            // reasoning behavior. The discovery path already declares only
            // verified capabilities; these provisional defaults stay because
            // decision-engine contracts filter offerings by them — capability
            // VERIFICATION is deferred to the ADR-6 redesign track.
            supportedCapabilities = when (preset.serviceType) {
                ServiceType.LLM -> setOf(
                    CapabilityType.LLM_GENERATION,
                    CapabilityType.REASONING,
                    CapabilityType.STREAMING
                )
                ServiceType.EMBEDDING -> setOf(
                    CapabilityType.EMBEDDING,
                    CapabilityType.MEMORY_RETRIEVAL
                )
                else -> setOf(CapabilityType.SEARCH)
            },
            isLocal = preset.isLocal,
            isAvailable = true,
            discoverySource = "USER_CONNECT_WIZARD"
        )
        when (val r = providerControlPlaneService.registerOffering(offering)) {
            is Outcome.Error -> return Result.Failed(4, "تعذر تسجيل النموذج: ${r.diagnosticMessage}")
            else -> Unit
        }

        // 5. Materialize
        onStep(5, "تهيئة المورد التشغيلي…")
        val resourceId = when (
            val r = providerControlPlaneService.materializeResource(providerId, serviceId, offeringId)
        ) {
            is Outcome.Success -> r.value.resourceId
            is Outcome.Error -> return Result.Failed(5, "تعذر تهيئة المورد: ${r.diagnosticMessage}")
            is Outcome.Degraded -> r.partialValue?.resourceId
                ?: return Result.Failed(5, "تعذر تهيئة المورد: ${r.diagnosticMessage}")
        }

        // 6. Validate (network check with the stored key)
        onStep(6, "التحقق الفعلي من الاتصال بمفتاحك… (طلب شبكة واحد)")
        return when (val r = providerControlPlaneService.validateResource(resourceId)) {
            is Outcome.Success -> {
                val result = r.value
                if (result.isSuccess) {
                    // validateResource auto-promoted the record to ENABLED.
                    Result.Connected(
                        resourceId = resourceId.value,
                        message = "تم ربط «${preset.displayName}» بنجاح — المورد مُفعّل وجاهز للاستخدام (${result.message})"
                    )
                } else {
                    Result.SavedUnverified(
                        "تم الحفظ لكن التحقق فشل: ${result.message}\n" +
                            "راجع المفتاح/العنوان ثم اضغط «إعادة التحقق» في بطاقة المورد."
                    )
                }
            }
            is Outcome.Error -> Result.Failed(6, "فشل التحقق: ${r.diagnosticMessage}")
            is Outcome.Degraded -> Result.Failed(6, "فشل التحقق: ${r.diagnosticMessage}")
        }
    }
}
