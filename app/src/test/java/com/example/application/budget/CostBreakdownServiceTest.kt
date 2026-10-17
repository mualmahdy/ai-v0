package com.example.application.budget

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.example.domain.core.budget.TokenUsageRecord
import com.example.domain.core.budget.UsageAccountingInput
import com.example.infrastructure.persistence.AppDatabase
import com.example.infrastructure.persistence.BudgetCoreMigrationSql
import com.example.infrastructure.persistence.Stage2MigrationSql
import com.example.infrastructure.persistence.entities.PricingEntryEntity
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.math.BigDecimal

/**
 * ============================================================================
 * STAGE 2 — CostBreakdownServiceTest (G08 + the Phase-2 exit criterion)
 * ============================================================================
 * G08 (closed this stage): deterministic decimal money math with golden
 * reference tests — exact divisions, HALF_UP ties, sub-micro remainders,
 * repeating-decimal safety, and the ROUND_LINE_THEN_SUM total.
 *
 * The Phase-2 exit criterion: "حساب تكلفة قابل للتدقيق سطرًا بسطر يطابق
 * نتائج المحرك الحالي ضمن سياسة التقريب المعتمدة" — the itemized
 * computation must match the legacy engine (computeCostMicro, truncating
 * integer division) within the approved rounding policy: |Δ| ≤ 1 micro
 * per line under HALF_UP-vs-truncation, and EXACT equality whenever the
 * division is exact.
 */
@RunWith(RobolectricTestRunner::class)
class CostBreakdownServiceTest {

    private lateinit var db: AppDatabase
    private lateinit var catalog: PricingCatalogService
    private lateinit var breakdown: CostBreakdownService

    private val now = 1_000L

    @Before
    fun setup() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .allowMainThreadQueries()
            .addCallback(object : androidx.room.RoomDatabase.Callback() {
                override fun onCreate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
                    BudgetCoreMigrationSql.TRIGGERS.forEach(db::execSQL)
                    Stage2MigrationSql.TRIGGERS.forEach(db::execSQL)
                    Stage2MigrationSql.STATEMENTS.filter { it.startsWith("INSERT") }.forEach(db::execSQL)
                }
            })
            .build()
        catalog = PricingCatalogService(database = db, clock = { now })
        breakdown = CostBreakdownService(database = db, pricingCatalog = catalog, clock = { now })
    }

    @After
    fun tearDown() {
        db.close()
    }

    private fun usageInput(
        executionId: String,
        input: Int, output: Int, cached: Int,
        provider: String = "prov", model: String = "m1",
        actualReport: Boolean = true
    ) = UsageAccountingInput(
        executionId = executionId, taskId = null, workspaceId = null, agentId = null,
        providerId = provider, serviceId = null, modelId = model, resourceId = null,
        usage = TokenUsageRecord(inputTokens = input, outputTokens = output, cachedTokens = cached),
        isActualProviderReport = actualReport,
        timestampEpochMs = now
    )

    private suspend fun seedPricing(
        inputPrice: Long? = 1500L, outputPrice: Long? = 3000L, cachedPrice: Long? = null,
        entryId: String = "pe-1"
    ) {
        db.pricingEntryDao().upsert(
            PricingEntryEntity(
                id = entryId, scopeType = "MODEL", providerId = "prov",
                serviceId = null, modelId = "m1",
                inputPriceMicroPerMillion = inputPrice,
                outputPriceMicroPerMillion = outputPrice,
                cachedInputPriceMicroPerMillion = cachedPrice,
                currency = "USD", billingClass = "CLOUD", pricingVersion = "v1",
                effectiveFromEpochMs = 0, effectiveToEpochMs = null,
                provenance = "test", createdAtEpochMs = 0
            )
        )
        catalog.syncFromLegacyEntries()
    }

    // ------------------------------------------------------------------
    // G08 golden cases (pure math, pinned exactly)
    // ------------------------------------------------------------------

    @Test
    fun `G08 golden - exact division`() {
        // 1,000,000 tokens × 1500 micro/1M = 1500 micro exactly
        val r = CostMath.computeLine(1_000_000, BigDecimal("1500"))
        assertEquals("1500", r.preRoundedAmountPrecise)
        assertEquals(1500L, r.finalAmountMicroUnits)
        // 500,000 × 2000 / 1M = 1000 exactly
        assertEquals(1000L, CostMath.computeLine(500_000, BigDecimal("2000")).finalAmountMicroUnits)
    }

    @Test
    fun `G08 golden - HALF_UP ties round away from zero`() {
        // 1000 × 1500 / 1M = 1.5 micro -> HALF_UP -> 2 (legacy truncation: 1)
        assertEquals(2L, CostMath.computeLine(1_000, BigDecimal("1500")).finalAmountMicroUnits)
        // 500 × 1500 / 1M = 0.75 -> 1
        assertEquals(1L, CostMath.computeLine(500, BigDecimal("1500")).finalAmountMicroUnits)
        // 250 × 1500 / 1M = 0.375 -> 0
        assertEquals(0L, CostMath.computeLine(250, BigDecimal("1500")).finalAmountMicroUnits)
    }

    @Test
    fun `G08 golden - pre-rounded evidence keeps the exact decimal`() {
        // 333 × 1500 / 1M = 0.4995 exactly — finite, preserved verbatim
        val r = CostMath.computeLine(333, BigDecimal("1500"))
        assertEquals("0.4995", r.preRoundedAmountPrecise)
        assertEquals(0L, r.finalAmountMicroUnits)
    }

    @Test
    fun `G08 golden - ROUND_LINE_THEN_SUM sums the rounded lines only`() {
        val lines = listOf(
            CostMath.computeLine(1_000, BigDecimal("1500")),  // 1.5 -> 2
            CostMath.computeLine(1_000, BigDecimal("2500"))   // 2.5 -> 3
        )
        assertEquals(5L, CostMath.sumLines(lines.map { it.finalAmountMicroUnits }))
    }

    // ------------------------------------------------------------------
    // Full recording: the audit trail row by row
    // ------------------------------------------------------------------

    @Test
    fun `record writes the full itemized audit trail`() = runBlocking {
        seedPricing(inputPrice = 1500, outputPrice = 3000, cachedPrice = 750)

        val result = breakdown.record(
            usageInput("exec-1", input = 1_000_000, output = 2_000_000, cached = 500_000)
        )

        assertNotNull(result.costCalculationId)
        assertEquals(1500L + 6000L + 375L, result.totalAmountMicroUnits)
        assertEquals(CostBreakdownService.STATUS_CALCULATED, result.calculationStatus)

        // usage facts
        val record = db.usageRecordDao().byAttemptSourceVersion("exec-1", "PROVIDER", "1")
        assertNotNull(record)
        assertEquals(4, db.usageMeasurementDao().forRecord(record!!.usageRecordId).size)

        // itemized lines, in component order, each with its evidence
        val calcId = result.costCalculationId!!
        val lines = db.costCalculationLineDao().forCalculation(calcId)
        assertEquals(3, lines.size)
        assertEquals(1500L, lines[0].finalAmountMicroUnits)
        assertEquals("1500", lines[0].rateValuePrecise)
        assertEquals("1500", lines[0].preRoundedAmountPrecise)
        assertEquals(6000L, lines[1].finalAmountMicroUnits)
        assertEquals(375L, lines[2].finalAmountMicroUnits)
        assertEquals("HALF_UP", lines[0].roundingMode)
        assertEquals("USAGE_MEASUREMENT", lines[0].lineBasisType)
        assertNotNull(lines[0].usageMeasurementId)

        // the total is EXACTLY the sum of the persisted line integers
        assertEquals(lines.sumOf { it.finalAmountMicroUnits!! }, result.totalAmountMicroUnits)

        // inputs + line inputs (quantity measurement, rate constant, unit constant)
        assertEquals(4, db.costCalculationInputDao().forCalculation(calcId).size)
        lines.forEach { line ->
            val lineInputs = db.costCalculationLineInputDao().forLine(line.lineId)
            assertEquals(3, lineInputs.size)
            assertTrue(lineInputs.any { it.inputRole == "QUANTITY" && it.usageMeasurementId != null })
            assertTrue(lineInputs.any { it.inputRole == "RATE" && it.constantValuePrecise != null })
            assertTrue(lineInputs.any { it.inputRole == "PRICE_UNIT_QUANTITY" && it.constantValuePrecise == "1000000" })
        }
    }

    @Test
    fun `recording is idempotent on retries`() = runBlocking {
        seedPricing()
        val input = usageInput("exec-2", input = 100_000, output = 100_000, cached = 0)

        val first = breakdown.record(input)
        val second = breakdown.record(input)

        assertEquals(first.usageRecordId, second.usageRecordId)
        assertEquals(first.costCalculationId, second.costCalculationId)
        assertEquals(1, db.usageRecordDao().byAttemptSourceVersion("exec-2", "PROVIDER", "1")
            ?.let { 1 } ?: 0)
    }

    @Test
    fun `no pricing version means usage facts land but no calculation is fabricated`() = runBlocking {
        val result = breakdown.record(usageInput("exec-3", 100, 200, 0, provider = "nobody"))
        assertNotNull(result.usageRecordId)
        assertNull(result.costCalculationId)
        assertNull(result.totalAmountMicroUnits)
        assertEquals("usage facts still recorded", 1,
            db.usageRecordDao().byAttemptSourceVersion("exec-3", "PROVIDER", "1")
                ?.let { 1 } ?: 0)
    }

    @Test
    fun `a NOT_CALCULABLE version yields an honest NOT_POSSIBLE calculation`() = runBlocking {
        seedPricing(inputPrice = null, outputPrice = null, cachedPrice = null)
        val result = breakdown.record(usageInput("exec-4", 100, 200, 0))
        assertNotNull(result.costCalculationId)
        assertEquals(CostBreakdownService.STATUS_NOT_POSSIBLE, result.calculationStatus)
        assertNull(result.totalAmountMicroUnits)
    }

    // ------------------------------------------------------------------
    // The Phase-2 exit criterion: engine parity within the approved policy
    // ------------------------------------------------------------------

    @Test
    fun `PARITY - itemized totals match the legacy engine within the rounding policy`() = runBlocking {
        // reference sample set: (in, out, cached, inPrice, outPrice, cachedPrice)
        // BF-14: this was written as 6-argument Triple(...) calls — the
        // stdlib Triple holds exactly three. A named row type reads better.
        data class ParitySample(
            val input: Int, val output: Int, val cached: Int,
            val inPrice: Long, val outPrice: Long, val cachedPrice: Long?
        )
        val samples = listOf(
            ParitySample(1_000_000, 2_000_000, 0, 1500L, 3000L, null),
            ParitySample(1_234, 4_321, 0, 1500L, 3000L, null),
            ParitySample(999_999, 1, 500, 1500L, 3000L, 750L),
            ParitySample(1, 1, 1, 333L, 777L, 111L),
            ParitySample(0, 500_000, 0, 2500L, 1250L, null),
            ParitySample(3_333_333, 3_333_333, 3_333_333, 1234L, 4321L, 555L),
            ParitySample(250, 250, 250, 1500L, 1500L, 1500L),
            ParitySample(10_000_000, 10_000_000, 10_000_000, 15L, 30L, 7L)
        )
        for ((index, sample) in samples.withIndex()) {
            val (input, output, cached, inPrice, outPrice, cachedPrice) = sample
            // a distinct entry id per sample: the catalog's mirror is
            // idempotent BY ENTRY ID (I-13), so re-using one id would
            // legitimately skip the re-priced samples.
            seedPricing(
                inputPrice = inPrice, outputPrice = outPrice, cachedPrice = cachedPrice,
                entryId = "pe-parity-$index"
            )

            val result = breakdown.record(usageInput("exec-parity-$index", input, output, cached))
            val legacy = EconomicGovernanceService.computeCostMicro(
                inputTokens = input, outputTokens = output, cachedTokens = cached,
                inputPriceMicro = inPrice, outputPriceMicro = outPrice, cachedPriceMicro = cachedPrice
            )

            assertNotNull("sample $index must produce a calculation", result.costCalculationId)
            val itemized = result.totalAmountMicroUnits!!
            val lineCount = db.costCalculationLineDao()
                .forCalculation(result.costCalculationId!!).size
            val delta = kotlin.math.abs(itemized - legacy!!)
            assertTrue(
                "sample $index: |itemized($itemized) - legacy($legacy)| = $delta must be <= $lineCount (1 micro per HALF_UP-vs-truncation line)",
                delta <= lineCount
            )
        }
    }

    @Test
    fun `PARITY - exact equality whenever every line divides exactly`() = runBlocking {
        seedPricing(inputPrice = 1000, outputPrice = 2000, cachedPrice = 500)
        val result = breakdown.record(usageInput("exec-parity-exact", 1_000_000, 1_000_000, 1_000_000))
        val legacy = EconomicGovernanceService.computeCostMicro(
            inputTokens = 1_000_000, outputTokens = 1_000_000, cachedTokens = 1_000_000,
            inputPriceMicro = 1000, outputPriceMicro = 2000, cachedPriceMicro = 500
        )
        assertEquals(legacy, result.totalAmountMicroUnits)
    }
}
