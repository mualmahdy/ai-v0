package com.example.infrastructure.persistence.adapter

import com.example.domain.ports.provider.ResourceRecordRepository
import com.example.domain.core.provider.ResourceRecord
import com.example.infrastructure.persistence.dao.ResourceRecordDao

class RoomResourceRecordRepository(private val dao: ResourceRecordDao) : ResourceRecordRepository {
    
    override suspend fun saveRecord(record: ResourceRecord): Long {
        return 0L
    }

    override suspend fun getRecordById(id: Long): ResourceRecord? {
        return null
    }

    override suspend fun getRecordsByType(type: String): List<ResourceRecord> {
        return emptyList()
    }

    override suspend fun getAllRecords(): List<ResourceRecord> {
        return emptyList()
    }

    override suspend fun updateRecord(record: ResourceRecord) {
    }

    override suspend fun deleteRecord(id: Long) {
    }

    override suspend fun getActiveRecords(): List<ResourceRecord> {
        return emptyList()
    }
}
