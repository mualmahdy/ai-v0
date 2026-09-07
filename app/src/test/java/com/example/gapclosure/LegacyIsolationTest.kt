package com.example.gapclosure

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * ============================================================================
 * LegacyIsolationTest — gap-closure P2-01 / P2-02 / P2-03 / P2-04 (freeze)
 * ============================================================================
 *
 * The legacy architecture is ISOLATED AND FROZEN (per the agreed remediation
 * strategy: عزل وتأمين — no mass deletion in this round). This test guards
 * the freeze: no NEW runtime code may depend on the legacy parallel models,
 * the legacy provider DB/API, the legacy quota system, or the deprecated
 * budget fields.
 *
 * The allowlist contains exactly the files that legitimately own each
 * legacy artifact (declaration sites); anything else referencing them
 * fails this test.
 */
class LegacyIsolationTest {

    private fun moduleRoot(): File {
        // Unit tests run with the app module dir as working dir.
        var dir = File(System.getProperty("user.dir")!!)
        var attempts = 0
        while (attempts < 4) {
            if (File(dir, "src/main/java/com/example").exists()) return dir
            dir = dir.parentFile ?: break
            attempts++
        }
        return File(System.getProperty("user.dir")!!)
    }

    private fun allMainKotlinFiles(): List<File> =
        File(moduleRoot(), "src/main/java").walkTopDown()
            .filter { it.isFile && it.extension == "kt" }
            .toList()

    private fun relativePath(file: File): String =
        file.relativeTo(File(moduleRoot(), "src/main/java")).invariantSeparatorsPath

    private fun forbiddenReferences(
        patterns: List<Regex>,
        allowlist: Set<String>
    ): Map<String, List<String>> {
        val violations = mutableMapOf<String, List<String>>()
        for (file in allMainKotlinFiles()) {
            val path = relativePath(file)
            if (path in allowlist) continue
            val text = file.readText()
            val hits = patterns.mapNotNull { regex ->
                regex.findAll(text).map { it.value }.distinct().firstOrNull()
            }
            if (hits.isNotEmpty()) violations[path] = hits
        }
        return violations
    }

    @Test
    fun `P2-01 legacy domain models are not referenced by any runtime code`() {
        val violations = forbiddenReferences(
            patterns = listOf(Regex("import com\\.example\\.domain\\.models\\.[A-Za-z]+")),
            allowlist = setOf("com/example/domain/models/Models.kt")
        )
        assertEquals(
            "No runtime code may import the legacy parallel models: $violations",
            0,
            violations.size
        )
    }

    @Test
    fun `P2-02 legacy provider DB API is frozen to its declaration sites`() {
        val violations = forbiddenReferences(
            patterns = listOf(
                Regex("providerConfigDao\\(\\)"),
                Regex("ProviderConfigEntity\\(")
            ),
            allowlist = setOf(
                "com/example/infrastructure/persistence/AppDatabase.kt",
                "com/example/infrastructure/persistence/entities/Entities.kt",
                "com/example/infrastructure/persistence/dao/Daos.kt",
                "com/example/infrastructure/persistence/dao/ProviderDao.kt",
                // P2-02: the frozen legacy adapter island (bridge-only file).
                "com/example/infrastructure/provider/RoomProviderRepositoryAdapter.kt"
            )
        )
        assertEquals(
            "Legacy ProviderConfig DB usage must not grow beyond the declaration sites: $violations",
            0,
            violations.size
        )
    }

    @Test
    fun `P2-03 legacy quota system is not used by the runtime`() {
        val violations = forbiddenReferences(
            patterns = listOf(
                Regex("\\bResourceQuota\\("),
                Regex("\\bQuotaUsage\\("),
                Regex("\\bQuotaAction\\b")
            ),
            allowlist = setOf(
                "com/example/domain/core/budget/EconomicModels.kt",
                "com/example/domain/models/Models.kt",
                // P2-03: declaration site of the superseded quota models.
                "com/example/domain/core/resilience/ResilienceModels.kt"
            )
        )
        assertEquals(
            "The legacy quota system is superseded by BudgetAllocation/TaskBudget/RateLimitGovernor: $violations",
            0,
            violations.size
        )
    }

    @Test
    fun `P2-04 deprecated monetary budget fields are not consumed by the runtime`() {
        val violations = forbiddenReferences(
            patterns = listOf(
                Regex("\\.maxCostEstimatedUsd\\b"),
                Regex("\\.estimatedCostUsd\\b")
            ),
            allowlist = setOf(
                "com/example/domain/core/task/TaskModels.kt",
                "com/example/domain/core/llm/LlmModels.kt"
            )
        )
        assertEquals(
            "Deprecated budget hints must not be consumed — the economic ledger is the authority: $violations",
            0,
            violations.size
        )
    }

    @Test
    fun `P2-05 legacy RAG retrieval budget API is no longer called with the legacy parameter`() {
        val violations = forbiddenReferences(
            patterns = listOf(
                Regex("retrieveRelevantContext\\([^)]*maxTokenBudget[^)]*\\)")
            ),
            allowlist = setOf(
                // P2-05: the deprecated overload's own declaration site.
                "com/example/application/rag/RagPipelineService.kt"
            )
        )
        assertEquals(
            "Callers must use the budget-less overload (authority moved to RagIntelligenceService): $violations",
            0,
            violations.size
        )
    }

    @Test
    fun `test suite exists and scans real sources`() {
        val files = allMainKotlinFiles()
        assertTrue("Source scan must find the main source set (found ${files.size})", files.size > 100)
        assertTrue(
            "The orchestrator source must be present in the scan",
            files.any { relativePath(it) == "com/example/application/orchestration/AgentOrchestrator.kt" }
        )
    }
}
