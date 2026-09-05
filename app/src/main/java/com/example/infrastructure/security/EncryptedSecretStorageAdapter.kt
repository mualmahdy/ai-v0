package com.example.infrastructure.security

import android.content.Context
import android.content.SharedPreferences
import android.util.Base64
import com.example.domain.core.Outcome
import com.example.domain.ports.provider.SecureCredentialStoragePort
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Robust, secure credential storage adapter.
 * Uses AES-GCM (Galois/Counter Mode) encryption to guarantee confidentiality and authenticity.
 * Keys live EXCLUSIVELY in the Android Keystore — the ciphertext on disk is
 * useless without the hardware-backed key.
 *
 * FIX S-2 (audit c03919d): the previous implementation stored a FALLBACK
 * SOFTWARE KEY (random 32 bytes, base64) in the SAME SharedPreferences file
 * as the ciphertext. Anyone with the prefs file (backup, root, adb backup)
 * obtained key + ciphertext together — the encryption was decorative.
 * The fallback path is now REMOVED: if the Android Keystore is unavailable,
 * storeSecret/getSecret return an explicit Outcome.Error instead of silently
 * downgrading to the vulnerable software-key scheme.
 */
class EncryptedSecretStorageAdapter(
    private val context: Context,
    private val prefsName: String = "secure_vault_credentials"
) : SecureCredentialStoragePort {

    private val prefs: SharedPreferences by lazy {
        context.getSharedPreferences(prefsName, Context.MODE_PRIVATE)
    }

    private val keyAlias = "AI_V0_MASTER_CREDENTIAL_KEY"
    private val androidKeyStore = "AndroidKeyStore"
    private val gcmTagLength = 128
    private val ivLength = 12

    init {
        ensureMasterKey()
    }

    private fun ensureMasterKey() {
        try {
            val ks = KeyStore.getInstance(androidKeyStore)
            ks.load(null)
            if (!ks.containsAlias(keyAlias)) {
                val keyGen = KeyGenerator.getInstance("AES", androidKeyStore)
                val keySpec = android.security.keystore.KeyGenParameterSpec.Builder(
                    keyAlias,
                    android.security.keystore.KeyProperties.PURPOSE_ENCRYPT or android.security.keystore.KeyProperties.PURPOSE_DECRYPT
                )
                    .setBlockModes(android.security.keystore.KeyProperties.BLOCK_MODE_GCM)
                    .setEncryptionPaddings(android.security.keystore.KeyProperties.ENCRYPTION_PADDING_NONE)
                    .setKeySize(256)
                    .build()
                keyGen.init(keySpec)
                keyGen.generateKey()
            }
        } catch (_: Exception) {
            // Keystore unavailable in this environment (e.g. JVM unit tests).
            // FIX S-2: NO software fallback is created — operations that need the
            // key fail explicitly via getSecretKey() throwing SecurityException.
        }
    }

    /**
     * FIX S-2: returns the Keystore-backed key or throws SecurityException.
     * The old path silently generated a software key stored next to the
     * ciphertext (critical vulnerability) — removed.
     */
    private fun getSecretKey(): SecretKey {
        val ks = KeyStore.getInstance(androidKeyStore)
        ks.load(null)
        if (ks.containsAlias(keyAlias)) {
            return (ks.getEntry(keyAlias, null) as KeyStore.SecretKeyEntry).secretKey
        }
        throw SecurityException("Android Keystore key unavailable — refusing to fall back to an insecure software key.")
    }

    override suspend fun storeSecret(alias: String, secret: String): Outcome<Unit, String> = withContext(Dispatchers.IO) {
        try {
            if (secret.isBlank()) {
                deleteSecret(alias)
                return@withContext Outcome.Success(Unit)
            }

            val key = getSecretKey()
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.ENCRYPT_MODE, key)
            val iv = cipher.iv

            val cipherText = cipher.doFinal(secret.toByteArray(Charsets.UTF_8))
            val combined = ByteArray(iv.size + cipherText.size)
            System.arraycopy(iv, 0, combined, 0, iv.size)
            System.arraycopy(cipherText, 0, combined, iv.size, cipherText.size)

            val base64Value = Base64.encodeToString(combined, Base64.NO_WRAP)
            prefs.edit().putString("secret_$alias", base64Value).apply()

            Outcome.Success(Unit)
        } catch (e: SecurityException) {
            // FIX S-2: explicit failure — never silently downgrade security.
            Outcome.Error(
                "فشل الحفظ: مخزن مفاتيح الجهاز (Android Keystore) غير متاح، " +
                    "ولن يتم استخدام مفتاح برمجي غير آمن. لا يمكن تخزين الأسرار في هذه البيئة."
            )
        } catch (e: Exception) {
            Outcome.Error("فشل تشفير وحفظ المفتاح السري بأمان: ${e.localizedMessage}")
        }
    }

    override suspend fun getSecret(alias: String): Outcome<String?, String> = withContext(Dispatchers.IO) {
        try {
            val rawPref = prefs.getString("secret_$alias", null) ?: return@withContext Outcome.Success(null)
            val combined = Base64.decode(rawPref, Base64.NO_WRAP)
            if (combined.size < ivLength) {
                return@withContext Outcome.Success(null)
            }

            val iv = ByteArray(ivLength)
            val cipherText = ByteArray(combined.size - ivLength)
            System.arraycopy(combined, 0, iv, 0, ivLength)
            System.arraycopy(combined, ivLength, cipherText, 0, cipherText.size)

            val key = getSecretKey()
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            val spec = GCMParameterSpec(gcmTagLength, iv)
            cipher.init(Cipher.DECRYPT_MODE, key, spec)

            val decryptedBytes = cipher.doFinal(cipherText)
            val plainText = String(decryptedBytes, Charsets.UTF_8)

            Outcome.Success(plainText)
        } catch (e: SecurityException) {
            // FIX S-2: explicit failure — never silently downgrade security.
            Outcome.Error(
                "فشل الاسترجاع: مخزن مفاتيح الجهاز غير متاح — رُفض استخدام بديل برمجي غير آمن."
            )
        } catch (e: Exception) {
            Outcome.Error("فشل فك تشفير المفتاح السري: ${e.localizedMessage}")
        }
    }

    override suspend fun deleteSecret(alias: String): Outcome<Unit, String> = withContext(Dispatchers.IO) {
        try {
            prefs.edit().remove("secret_$alias").apply()
            Outcome.Success(Unit)
        } catch (e: Exception) {
            Outcome.Error("فشل حذف المفتاح السري: ${e.localizedMessage}")
        }
    }

    override fun hasSecret(alias: String): Boolean {
        return prefs.contains("secret_$alias")
    }
}
