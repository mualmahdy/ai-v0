package com.example.domain.ports.provider

import com.example.domain.core.provider.ResourceRecord

interface ResourceRecordRepository {
    suspend fun saveRecord(record: ResourceRecord): Long
    suspend fun getRecordById(id: Long): ResourceRecord?
    suspend fun getRecordsByType(type: String): List<ResourceRecord>
    suspend fun getAllRecords(): List<ResourceRecord>
    suspend fun updateRecord(record: ResourceRecord)
    suspend fun deleteRecord(id: Long)
    suspend fun getActiveRecords(): List<ResourceRecord>
}
