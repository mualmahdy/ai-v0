package com.example.application.usecases

import com.example.application.provider.ProviderControlPlaneService
import com.example.application.provider.ProviderPreset
import com.example.domain.core.Outcome
import com.example.domain.core.capability.CapabilityType
import com.example.domain.core.provider.Provider
import com.example.domain.core.provider.ProviderService
import com.example.domain.core.provider.ServiceConfiguration
import com.example.domain.core.provider.ServiceProtocolId
import com.example.domain.core.provider.ServiceType
import com.example.domain.core.provider.offering.OfferingType
import com.example.domain.core.provider.offering.ServiceOffering
import com.example.infrastructure.llm.gemini.GeminiThinkingCapability
import com.example.infrastructure.llm.gemini.GeminiThinkingSupport

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
        /**
         * STAGE 7 (auto-discovery — the model name becomes OPTIONAL): when
         * true and the model name is blank, step 4 turns into a real
         * discovery against the SAVED configuration (GET /models with the
         * stored key), every discovered model is REGISTERED as an offering,
         * and the chain materializes the preset's default model when the
         * endpoint exposes it (otherwise the first discovered model).
         */
        autoDiscover: Boolean = false,
        onStep: suspend (step: Int, label: String) -> Unit = { _, _ -> }
    ): Result {
        if (preset.requiresApiKey && apiKey.isNullOrBlank()) {
            return Result.Rejected("هذا المزوّد يتطلب مفتاح API — أدخل المفتاح ثم أعد المحاولة")
        }
        val discoveryEligible = preset.serviceType == ServiceType.LLM ||
            preset.serviceType == ServiceType.EMBEDDING
        if (modelName.isBlank() && !autoDiscover && preset.serviceType != ServiceType.SEARCH) {
            return Result.Rejected(
                "أدخل اسم النموذج أو فعّل الاكتشاف التلقائي ليختار النظام نموذجاً متاحاً"
            )
        }
        if (modelName.isBlank() && autoDiscover && !discoveryEligible) {
            return Result.Rejected(
                "الاكتشاف التلقائي غير متاح لهذا النوع من الخدمات — أدخل الاسم/النقطة يدوياً"
            )
        }

        val providerId = "prov_${System.currentTimeMillis()}"
        val serviceId = "${providerId}_${preset.serviceType.code}"
        val authAlias = "${providerId}_key"
        val effectiveModelName = modelName.trim()
        val offeringId = when (preset.serviceType) {
            ServiceType.SEARCH -> "${providerId}_search"
            else -> "${providerId}_${effectiveModelName.replace(Regex("[^A-Za-z0-9._-]"), "_")}"
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

        // 4. Offering — manual registration OR real discovery.
        // `materializedOffering` is the registered row the resource will be
        // materialized from (and whose capabilities step 6 may upgrade).
        val materializedOffering: ServiceOffering
        if (effectiveModelName.isBlank()) {
            // ---------- STAGE 7: the auto-discovery arm ----------
            onStep(4, "اكتشاف النماذج المتاحة من المزوّد… (طلب شبكة واحد)")
            val discovered: List<ServiceOffering> = when (
                val discovery = providerControlPlaneService.discoverOfferings(serviceId)
            ) {
                is Outcome.Success -> discovery.value
                is Outcome.Degraded -> return Result.Failed(
                    4,
                    "فشل اكتشاف النماذج: ${discovery.diagnosticMessage} — " +
                        "أدخل اسم النموذج يدوياً وأعد المحاولة."
                )
                is Outcome.Error -> return Result.Failed(
                    4,
                    "فشل اكتشاف النماذج: ${discovery.diagnosticMessage} — " +
                        "تأكد من العنوان والمفتاح، أو أدخل اسم النموذج يدوياً."
                )
            }
            val models = discovered.filter {
                it.offeringType == OfferingType.MODEL && it.isAvailable
            }
            if (models.isEmpty()) {
                return Result.Failed(
                    4,
                    "لم يكتشف النظام أي نموذج من هذا العنوان — قد لا يدعم المزوّد الاكتشاف التلقائي. " +
                        "أدخل اسم النموذج يدوياً وأعد المحاولة."
                )
            }
            // The preset's curated default (when the endpoint really exposes
            // it) wins; otherwise the first discovered model.
            val chosen = models.firstOrNull {
                preset.defaultModel.isNotBlank() && it.name == preset.defaultModel
            } ?: models.first()
            materializedOffering = chosen
            // Pin the chosen model as the configuration's default offering
            // (upsert — version bump, the repo contract).
            when (
                val r = providerControlPlaneService.saveConfiguration(
                    config.copy(defaultOfferingId = chosen.id)
                )
            ) {
                is Outcome.Error -> return Result.Failed(
                    4,
                    "تعذّر تثبيت النموذج المكتشف (${chosen.name}) في الإعدادات: ${r.diagnosticMessage}"
                )
                else -> Unit
            }
        } else {
            // ---------- The manual arm (unchanged behavior) ----------
            onStep(4, "تسجيل النموذج/النقطة…")
            val offering = ServiceOffering(
                id = offeringId,
                serviceId = serviceId,
                offeringType = when (preset.serviceType) {
                    ServiceType.SEARCH -> OfferingType.ENDPOINT
                    else -> OfferingType.MODEL
                },
                name = effectiveModelName.ifBlank { preset.displayName },
                description = preset.description,
                contextWindowTokens = preset.contextWindowTokens,
                // ------------------------------------------------------------------
                // GAP-25 → CLOSED (CLOSURE P1-1, audit §5 item 8): capability
                // declarations are now EVIDENCE-DERIVED, never assumed.
                //
                // The OLD behavior declared REASONING + STREAMING for EVERY
                // LLM offering as unverified defaults — gemini-2.0-flash
                // received REASONING even though the curated thinking table
                // itself says that family has no thinkingConfig.
                //
                // The NEW behavior declares the DOCUMENTED FLOOR at
                // registration (LLM_GENERATION always; STREAMING documented
                // by both adapter families' implemented SSE paths; REASONING
                // documented ONLY by the curated Gemini thinking table —
                // never for OpenAI-compatible presets, whose reasoning
                // support this stage simply does not know), and step 6's
                // validation UPGRADES the declaration with the runtime
                // snapshot (floor ∪ verified − runtime-rejected) via
                // [OperationalResourceSnapshot.declaredCapabilities].
                // ------------------------------------------------------------------
                supportedCapabilities = when (preset.serviceType) {
                    ServiceType.LLM -> documentedLlmCapabilityFloor(
                        preset.protocolId, effectiveModelName
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
            materializedOffering = offering
        }

        // 5. Materialize
        onStep(5, "تهيئة المورد التشغيلي…")
        val resourceId = when (
            val r = providerControlPlaneService.materializeResource(
                providerId, serviceId, materializedOffering.id
            )
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
                    // ----------------------------------------------------------
                    // CLOSURE P1-1: upgrade the offering's capability
                    // declarations with the validation run's RUNTIME
                    // snapshot — floor ∪ verified − runtime-rejected. A
                    // REJECTED thinking probe REMOVES REASONING from a
                    // statically-documented model; a VERIFIED probe ADDS it
                    // to an undocumented one. No snapshot (no adapter /
                    // failed floor) keeps the documented floor — the honest
                    // registration-time declaration.
                    // ----------------------------------------------------------
                    var upgradeNote = ""
                    val snapshot = result.operationalSnapshot
                    if (snapshot != null && preset.serviceType == ServiceType.LLM) {
                        val floor = documentedLlmCapabilityFloor(preset.protocolId, effectiveModelName)
                        val declared = snapshot.declaredCapabilities(floor)
                        if (declared != materializedOffering.supportedCapabilities) {
                            when (val up = providerControlPlaneService.registerOffering(
                                materializedOffering.copy(supportedCapabilities = declared)
                            )) {
                                is Outcome.Error -> upgradeNote =
                                    " (تعذّر تحديث إعلانات القدرات: ${up.diagnosticMessage})"
                                else -> Unit
                            }
                        }
                    }
                    // validateResource auto-promoted the record to ENABLED.
                    Result.Connected(
                        resourceId = resourceId.value,
                        message = "تم ربط «${preset.displayName}» بنجاح — المورد مُفعّل وجاهز للاستخدام" +
                            (if (effectiveModelName.isBlank()) " (النموذج المكتشف تلقائياً)" else "") +
                            " (${result.message})" +
                            upgradeNote
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

    /**
     * CLOSURE P1-1 (audit §5 item 8): the DOCUMENTED capability floor for an
     * LLM offering — what may be declared BEFORE any runtime probe ran.
     *
     *  - LLM_GENERATION — every wizard LLM preset speaks a generation
     *    protocol; the validation floor (P0-4) then PROVES it per resource.
     *  - STREAMING — documented-by-implementation for every wizard protocol
     *    (the Gemini REST adapter and the OpenAI-compatible adapter both
     *    implement the SSE stream path); it stays a documented claim until a
     *    future stage's streaming probe verifies it per resource.
     *  - REASONING — documented ONLY by the curated Gemini thinking table
     *    ([GeminiThinkingCapability] — the runtime override registry makes
     *    this consult PROBE evidence first when it exists). OpenAI-compatible
     *    presets get NO reasoning declaration this stage: nothing documents
     *    per-model reasoning for them, so claiming it would be exactly the
     *    fabricated capability the audit rejected.
     */
    private fun documentedLlmCapabilityFloor(
        protocolId: ServiceProtocolId,
        modelName: String
    ): Set<CapabilityType> {
        val floor = mutableSetOf(
            CapabilityType.LLM_GENERATION,
            CapabilityType.STREAMING
        )
        if (protocolId == ServiceProtocolId.GEMINI_NATIVE &&
            GeminiThinkingCapability.forModel(modelName) == GeminiThinkingSupport.SUPPORTED
        ) {
            floor += CapabilityType.REASONING
        }
        return floor
    }
}
