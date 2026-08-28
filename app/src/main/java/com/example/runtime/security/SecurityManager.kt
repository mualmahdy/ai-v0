package com.example.runtime.security

import android.util.Base64
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.util.regex.Pattern
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

object PromptInjectionDetector {
    private val PATTERNS = listOf(
        // English Patterns
        Pattern.compile("(?i)ignore\\s+(all\\s+)?(previous|above)\\s+instructions?"),
        Pattern.compile("(?i)disregard\\s+(all\\s+)?(previous|above)\\s+instructions?"),
        Pattern.compile("(?i)system\\s*:\\s*.*?new\\s+instruction"),
        Pattern.compile("(?i)\\[INST\\].*?\\[/INST\\]"),
        Pattern.compile("(?i)<\\|im_start\\|>.*?<\\|im_end\\|>"),
        Pattern.compile("(?i)%%.*?%%"),
        Pattern.compile("(?i)<<SYS>>.*?<</SYS>>"),
        Pattern.compile("(?i)dan\\s*:"),
        Pattern.compile("(?i)jailbreak"),
        Pattern.compile("(?i)pretend\\s+you\\s+are"),
        
        // Arabic Patterns
        Pattern.compile("تجاهل\\s+(كل\\s+)?(التعليمات|الاوامر)\\s+(السابقه|اعلاه)"),
        Pattern.compile("تجاهل\\s+كل\\s+ما\\s+(سبق|قيل)"),
        Pattern.compile("انس\\s+(كل\\s+)?(التعليمات|الاوامر)\\s+(السابقه)"),
        Pattern.compile("تظاهر\\s+(و)?(بانك|انك)"),
        Pattern.compile("تصرف\\s+(و)?كانك"),
        Pattern.compile("من\\s+الان\\s+فصاعدا\\s+انت"),
        Pattern.compile("تجاوز\\s+(كل\\s+)?القيود"),
        Pattern.compile("كسر\\s+(كل\\s+)?القيود"),
        Pattern.compile("اختراق\\s+النموذج"),
        Pattern.compile("جيلبريك")
    )

    private fun normalize(text: String): String {
        // Strip Arabic diacritics and normalize alef/hamza
        var normalized = text.replace(Regex("[\u064B-\u065F\u0670]"), "")
        normalized = normalized.replace(Regex("[إأآٱ]"), "ا")
        normalized = normalized.replace('ى', 'ي').replace('ة', 'ه')
        return normalized.lowercase()
    }

    fun detect(prompt: String): Boolean {
        val normalized = normalize(prompt)
        for (pattern in PATTERNS) {
            if (pattern.matcher(normalized).find()) {
                return true
            }
        }
        return false
    }
}

object SecurityManager {
    private const val MASTER_KEY_SEED = "AI_V0_ULTIMATE_NATIVE_ANDROID_KEY_2026"
    private val keySpec: SecretKeySpec by lazy {
        val digest = MessageDigest.getInstance("SHA-256")
        val keyBytes = digest.digest(MASTER_KEY_SEED.toByteArray(StandardCharsets.UTF_8))
        SecretKeySpec(keyBytes, "AES")
    }

    fun encrypt(plainText: String): String {
        if (plainText.isEmpty()) return ""
        return try {
            val cipher = Cipher.getInstance("AES/CBC/PKCS5Padding")
            val iv = ByteArray(16) { 0 } // Fixed IV for deterministic persistence in offline room
            cipher.init(Cipher.ENCRYPT_MODE, keySpec, IvParameterSpec(iv))
            val encrypted = cipher.doFinal(plainText.toByteArray(StandardCharsets.UTF_8))
            Base64.encodeToString(encrypted, Base64.NO_WRAP)
        } catch (e: Exception) {
            plainText
        }
    }

    fun decrypt(encryptedText: String): String {
        if (encryptedText.isEmpty()) return ""
        return try {
            val cipher = Cipher.getInstance("AES/CBC/PKCS5Padding")
            val iv = ByteArray(16) { 0 }
            cipher.init(Cipher.DECRYPT_MODE, keySpec, IvParameterSpec(iv))
            val decoded = Base64.decode(encryptedText, Base64.NO_WRAP)
            String(cipher.doFinal(decoded), StandardCharsets.UTF_8)
        } catch (e: Exception) {
            encryptedText
        }
    }

    fun sanitizeText(text: String, maxLength: Int = 10000): String {
        return text.take(maxLength).trim()
    }
}
