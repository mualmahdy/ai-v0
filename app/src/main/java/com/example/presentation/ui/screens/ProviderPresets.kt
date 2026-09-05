package com.example.presentation.ui.screens

import com.example.domain.core.provider.ServiceProtocolId
import com.example.domain.core.provider.ServiceType

/**
 * ============================================================================
 * ProviderPresets — guided "Connect Provider" catalog
 * ============================================================================
 *
 * FIX (user feedback: "I can't add a provider whose services I can use"):
 * the previous Add-Provider dialog created a bare Provider row with no
 * Service / Configuration / Offering — a dead end the user could never use.
 *
 * A preset carries EVERYTHING needed to complete the full chain:
 *   Provider → Service → Configuration(+vault key) → Offering → Materialize
 *   → Validate → Enable
 *
 * so a user picks a provider, pastes one API key, and gets a working resource.
 */
data class ProviderPreset(
    val id: String,
    val displayName: String,
    val description: String,
    val serviceType: ServiceType,
    val protocolId: ServiceProtocolId,
    val defaultEndpoint: String,
    val defaultModel: String,
    val suggestedModels: List<String> = emptyList(),
    val offeringIdLabel: String = "اسم النموذج",
    val requiresApiKey: Boolean = true,
    val keyHint: String? = null,
    val websiteUrl: String? = null,
    val isLocal: Boolean = false,
    val contextWindowTokens: Int = 128_000
)

val PROVIDER_PRESETS: List<ProviderPreset> = listOf(
    ProviderPreset(
        id = "gemini",
        displayName = "Google Gemini",
        description = "نماذج Gemini مباشرة عبر واجهة Google — بمفتاحك من Google AI Studio (مجاناً)",
        serviceType = ServiceType.LLM,
        protocolId = ServiceProtocolId.GEMINI_NATIVE,
        defaultEndpoint = "https://generativelanguage.googleapis.com",
        defaultModel = "gemini-2.5-flash",
        suggestedModels = listOf(
            "gemini-2.5-flash",
            "gemini-2.5-pro",
            "gemini-2.5-flash-lite",
            "gemini-2.0-flash"
        ),
        requiresApiKey = true,
        keyHint = "احصل على المفتاح من aistudio.google.com → Get API Key",
        websiteUrl = "https://aistudio.google.com",
        contextWindowTokens = 1_048_576
    ),
    ProviderPreset(
        id = "openai",
        displayName = "OpenAI",
        description = "نماذج GPT عبر واجهة OpenAI الرسمية",
        serviceType = ServiceType.LLM,
        protocolId = ServiceProtocolId.OPENAI_NATIVE,
        defaultEndpoint = "https://api.openai.com",
        defaultModel = "gpt-4o-mini",
        suggestedModels = listOf(
            "gpt-4o-mini",
            "gpt-4o",
            "gpt-4.1",
            "gpt-4.1-mini",
            "o4-mini"
        ),
        requiresApiKey = true,
        keyHint = "احصل على المفتاح من platform.openai.com → API Keys",
        websiteUrl = "https://platform.openai.com",
        contextWindowTokens = 128_000
    ),
    ProviderPreset(
        id = "groq",
        displayName = "Groq",
        description = "استدلال فائق السرعة لنماذج مفتوحة (Llama وغيرها) — طبقة مجانية سخية",
        serviceType = ServiceType.LLM,
        protocolId = ServiceProtocolId.OPENAI_COMPATIBLE,
        defaultEndpoint = "https://api.groq.com/openai",
        defaultModel = "llama-3.3-70b-versatile",
        suggestedModels = listOf(
            "llama-3.3-70b-versatile",
            "llama-3.1-8b-instant",
            "gemma2-9b-it",
            "qwen-2.5-32b"
        ),
        requiresApiKey = true,
        keyHint = "احصل على المفتاح من console.groq.com/keys",
        websiteUrl = "https://console.groq.com/keys",
        contextWindowTokens = 128_000
    ),
    ProviderPreset(
        id = "openrouter",
        displayName = "OpenRouter",
        description = "بوابة موحدة لمئات النماذج (OpenAI وAnthropic وGoogle والمفتوحة) بمفتاح واحد",
        serviceType = ServiceType.LLM,
        protocolId = ServiceProtocolId.OPENAI_COMPATIBLE,
        defaultEndpoint = "https://openrouter.ai/api",
        defaultModel = "google/gemini-2.0-flash-001",
        suggestedModels = listOf(
            "google/gemini-2.0-flash-001",
            "openai/gpt-4o-mini",
            "anthropic/claude-3.5-haiku",
            "meta-llama/llama-3.3-70b-instruct"
        ),
        requiresApiKey = true,
        keyHint = "احصل على المفتاح من openrouter.ai/keys",
        websiteUrl = "https://openrouter.ai/keys",
        contextWindowTokens = 200_000
    ),
    ProviderPreset(
        id = "deepseek",
        displayName = "DeepSeek",
        description = "نماذج DeepSeek الاقتصادية القوية في البرمجة والاستدلال",
        serviceType = ServiceType.LLM,
        protocolId = ServiceProtocolId.OPENAI_COMPATIBLE,
        defaultEndpoint = "https://api.deepseek.com",
        defaultModel = "deepseek-chat",
        suggestedModels = listOf(
            "deepseek-chat",
            "deepseek-reasoner"
        ),
        requiresApiKey = true,
        keyHint = "احصل على المفتاح من platform.deepseek.com",
        websiteUrl = "https://platform.deepseek.com",
        contextWindowTokens = 64_000
    ),
    ProviderPreset(
        id = "mistral",
        displayName = "Mistral AI",
        description = "نماذج Mistral الأوروبية مع طبقة مجانية",
        serviceType = ServiceType.LLM,
        protocolId = ServiceProtocolId.OPENAI_COMPATIBLE,
        defaultEndpoint = "https://api.mistral.ai",
        defaultModel = "mistral-small-latest",
        suggestedModels = listOf(
            "mistral-small-latest",
            "mistral-large-latest",
            "open-mistral-nemo"
        ),
        requiresApiKey = true,
        keyHint = "احصل على المفتاح من console.mistral.ai",
        websiteUrl = "https://console.mistral.ai",
        contextWindowTokens = 128_000
    ),
    ProviderPreset(
        id = "ollama",
        displayName = "Ollama (محلي)",
        description = "شغّل النماذج على جهازك أو خادمك المحلي — بدون مفتاح وبدون إنترنت",
        serviceType = ServiceType.LLM,
        protocolId = ServiceProtocolId.OLLAMA_NATIVE,
        defaultEndpoint = "http://127.0.0.1:11434",
        defaultModel = "llama3.2",
        suggestedModels = listOf(
            "llama3.2",
            "qwen2.5",
            "gemma2",
            "phi3.5"
        ),
        requiresApiKey = false,
        keyHint = "لا يحتاج مفتاحاً — شغّل Ollama على نفس الجهاز أو الشبكة",
        websiteUrl = "https://ollama.com",
        isLocal = true,
        contextWindowTokens = 32_000
    ),
    ProviderPreset(
        id = "openai_compatible",
        displayName = "مزوّد متوافق مخصص",
        description = "أي خدمة متوافقة مع OpenAI (LM Studio وvLLM وTogether وTogeth وغيرها)",
        serviceType = ServiceType.LLM,
        protocolId = ServiceProtocolId.OPENAI_COMPATIBLE,
        defaultEndpoint = "https://your-endpoint.example.com",
        defaultModel = "",
        suggestedModels = emptyList(),
        requiresApiKey = false,
        keyHint = "اترك المفتاح فارغاً إذا كانت الخدمة لا تتطلب مصادقة",
        contextWindowTokens = 128_000
    ),
    ProviderPreset(
        id = "openai_embeddings",
        displayName = "OpenAI Embeddings",
        description = "تضمينات دلالية عالية الجودة للبحث والذاكرة (text-embedding-3)",
        serviceType = ServiceType.EMBEDDING,
        protocolId = ServiceProtocolId.OPENAI_NATIVE,
        defaultEndpoint = "https://api.openai.com",
        defaultModel = "text-embedding-3-small",
        suggestedModels = listOf(
            "text-embedding-3-small",
            "text-embedding-3-large"
        ),
        requiresApiKey = true,
        keyHint = "نفس مفتاح OpenAI الخاص بحسابك",
        websiteUrl = "https://platform.openai.com",
        contextWindowTokens = 8_192
    ),
    ProviderPreset(
        id = "tavily",
        displayName = "Tavily (بحث الويب)",
        description = "بحث ويب مُحسَّن للمساعدات الذكية — يُكمل محرك البحث المدمج",
        serviceType = ServiceType.SEARCH,
        protocolId = ServiceProtocolId.TAVILY_NATIVE,
        defaultEndpoint = "https://api.tavily.com",
        defaultModel = "tavily-search",
        offeringIdLabel = "اسم الخدمة",
        suggestedModels = listOf("tavily-search"),
        requiresApiKey = true,
        keyHint = "احصل على المفتاح من app.tavily.com (1000 طلب مجاناً شهرياً)",
        websiteUrl = "https://app.tavily.com",
        contextWindowTokens = 0
    )
)
