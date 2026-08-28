package com.example.domain.ports.provider

import com.example.domain.core.Outcome
import com.example.domain.core.model.ModelDescriptor
import com.example.domain.core.provider.ProviderDescriptor

sealed class DiscoveryFailure(val message: String, val isRecoverable: Boolean) {
    class AuthenticationRequired(val providerId: String) : DiscoveryFailure("يتطلب المصادقة أو المفتاح السري", false)
    class NetworkError(val error: String) : DiscoveryFailure("فشل الاتصال بالخادم: $error", true)
    class ParsingError(val error: String) : DiscoveryFailure("خطأ في معالجة استجابة النماذج: $error", false)
    class UnsupportedProvider(val providerId: String) : DiscoveryFailure("المزود غير مدعوم للاستكشاف التلقائي", false)
}

/**
 * Port for automatic model discovery from provider endpoints.
 */
interface ModelDiscoveryPort {
    val providerId: String
    suspend fun discoverModels(): Outcome<List<ModelDescriptor>, DiscoveryFailure>
    suspend fun checkHealth(): Outcome<ProviderDescriptor, DiscoveryFailure>
}
