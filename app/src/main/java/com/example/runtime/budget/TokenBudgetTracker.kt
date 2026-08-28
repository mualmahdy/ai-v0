package com.example.runtime.budget

import com.example.data.local.db.daos.TokenBudgetDao
import com.example.data.local.db.entities.TokenBudgetUsageEntity
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap

class TokenBudgetTracker(
    private val tokenBudgetDao: TokenBudgetDao
) {
    private val usageMap = ConcurrentHashMap<String, Int>() // "projectId_agentName" -> used
    private val inFlightMap = ConcurrentHashMap<String, Int>()
    private val mutex = Mutex()

    fun estimateTokens(text: String): Int {
        if (text.isEmpty()) return 0
        val arabicCharCount = text.count { it in '\u0600'..'\u06FF' || it in '\u0750'..'\u077F' }
        val arabicRatio = arabicCharCount.toFloat() / text.length
        val charsPerToken = (arabicRatio * 2.2f) + ((1.0f - arabicRatio) * 4.0f)
        return maxOf(1, (text.length / charsPerToken).toInt())
    }

    suspend fun ensureAvailable(projectId: Long, agentName: String, budgetLimit: Int) {
        val key = "${projectId}_$agentName"
        val used = getUsedTokens(projectId, agentName)
        if (used >= budgetLimit) {
            throw IllegalStateException("تجاوز الوكيل '$agentName' حد الميزانية المخصصة ($used/$budgetLimit Tokens)")
        }
    }

    suspend fun getUsedTokens(projectId: Long, agentName: String): Int {
        val key = "${projectId}_$agentName"
        usageMap[key]?.let { return it }
        val fromDb = tokenBudgetDao.getUsage(projectId, agentName)?.usedTokens ?: 0
        usageMap[key] = fromDb
        return fromDb
    }

    suspend fun recordUsage(projectId: Long, agentName: String, inputTokens: Int, outputTokens: Int) {
        val key = "${projectId}_$agentName"
        val added = maxOf(0, inputTokens) + maxOf(0, outputTokens)
        mutex.withLock {
            val current = getUsedTokens(projectId, agentName)
            val newTotal = current + added
            usageMap[key] = newTotal

            val now = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(Date())
            tokenBudgetDao.recordUsage(
                TokenBudgetUsageEntity(
                    compositeKey = key,
                    projectId = projectId,
                    agentName = agentName,
                    usedTokens = newTotal,
                    updatedAt = now
                )
            )
        }
    }

    fun markStarted(projectId: Long, agentName: String) {
        val key = "${projectId}_$agentName"
        inFlightMap[key] = (inFlightMap[key] ?: 0) + 1
    }

    fun markFinished(projectId: Long, agentName: String) {
        val key = "${projectId}_$agentName"
        val curr = inFlightMap[key] ?: 1
        inFlightMap[key] = maxOf(0, curr - 1)
    }

    fun getInFlight(projectId: Long, agentName: String): Int {
        val key = "${projectId}_$agentName"
        return inFlightMap[key] ?: 0
    }

    suspend fun reset(projectId: Long, agentName: String) {
        val key = "${projectId}_$agentName"
        mutex.withLock {
            usageMap[key] = 0
            inFlightMap[key] = 0
            tokenBudgetDao.resetUsage(projectId, agentName)
        }
    }
}
