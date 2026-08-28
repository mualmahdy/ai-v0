package com.example.domain.core.network

/**
 * Supported Network & Offline operation policies in AI-V0 Platform.
 */
enum class NetworkPolicy(val code: String, val displayName: String, val allowsCloud: Boolean, val allowsLocal: Boolean) {
    OFFLINE("offline", "وضع عدم الاتصال (Offline Only)", allowsCloud = false, allowsLocal = true),
    LOCAL_FIRST("local_first", "الأولوية للمعالجة المحلية (Local-First)", allowsCloud = true, allowsLocal = true),
    HYBRID("hybrid", "النمط الهجين الذكي (Intelligent Hybrid)", allowsCloud = true, allowsLocal = true),
    CLOUD_FIRST("cloud_first", "الأولوية للنماذج السحابية (Cloud-First)", allowsCloud = true, allowsLocal = true)
}
