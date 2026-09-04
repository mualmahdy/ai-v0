package com.example.domain.ports.provider

import com.example.domain.core.provider.Provider

interface ProviderRepository {
    suspend fun saveProvider(provider: Provider): Long
    suspend fun getProviderById(id: Long): Provider?
    suspend fun getAllProviders(): List<Provider>
    suspend fun updateProvider(provider: Provider)
    suspend fun deleteProvider(id: Long)
    suspend fun getProviderByName(name: String): Provider?
}
