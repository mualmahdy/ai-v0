package com.example.domain.ports.provider

import com.example.domain.core.Outcome

/**
 * Port for secure storage and retrieval of credentials, API keys, and auth tokens.
 * Backed by cryptographic device Keystore / Encrypted storage.
 * Prevents plain-text secrets in Room, logs, state, or diagnostic dumps.
 */
interface SecureCredentialStoragePort {
    /**
     * Securely stores a secret key under the specified provider alias.
     */
    suspend fun storeSecret(alias: String, secret: String): Outcome<Unit, String>

    /**
     * Retrieves the decrypted secret key if present.
     */
    suspend fun getSecret(alias: String): Outcome<String?, String>

    /**
     * Deletes the secret key under the specified alias.
     */
    suspend fun deleteSecret(alias: String): Outcome<Unit, String>

    /**
     * Checks if a secret is securely stored for the given alias without loading it into memory.
     */
    fun hasSecret(alias: String): Boolean
}
