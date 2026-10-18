package com.example.application.budget

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.example.infrastructure.persistence.AppDatabase
import com.example.infrastructure.persistence.BudgetCoreMigrationSql
import com.example.infrastructure.persistence.Stage2MigrationSql
import com.example.infrastructure.persistence.Stage3MigrationSql
import com.example.infrastructure.persistence.Stage4MigrationSql
import com.example.infrastructure.persistence.Stage5MigrationSql
import com.example.infrastructure.persistence.entities.CostCalculationEntity
import com.example.infrastructure.persistence.entities.ModelEntity
import com.example.infrastructure.persistence.entities.ModelIdentityMatchEntity
import com.example.infrastructure.persistence.entities.ModelOfferingEntity
import com.example.infrastructure.persistence.entities.PdmProviderEntity
import com.example.infrastructure.persistence.entities.PricingProfileEntity
import com.example.infrastructure.persistence.entities.PricingProfileVersionEntity
import com.example.infrastructure.persistence.entities.ProviderBillingRecordEntity
import com.example.infrastructure.persistence.entities.ProviderIntegrationEntity
import com.example.infrastructure.persistence.entities.ProviderModelRecordEntity
import com.example.infrastructure.persistence.entities.ProviderRequestAttemptEntity
import com.example.infrastructure.persistence.entities.ReconciliationAdjustmentEntity
import com.example.infrastructure.persistence.entities.ReconciliationRecordEntity
import com.example.infrastructure.persistence.entities.RequestOperationEntity
import com.example.infrastructure.persistence.entities.UsageMeasurementEntity
import com.example.infrastructure.persistence.entities.UsageRecordEntity
import com.example.infrastructure.persistence.entities.AccessContextEntity
import com.example.testing.FakeTelemetryPort
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * ============================================================================
 * RationalizationService tests — توصيات الترشيد + مؤشر جودة المصالحة
 * ============================================================================
 * Every family is proven on REAL data (the ledger posting path for
 * utilization, the registry + pricing chains for the comparison, the
 * reconciliation chain for the quality indicator), and the honesty arms
 * are asserted: INSUFFICIENT_DATA is returned exactly when the data is
 * thin — never a guessed number.
 */
@RunWith(RobolectricTestRunner::class)
class RationalizationServiceTest {

    private lateinit var db: AppDatabase
    private lateinit var ledger: BudgetLedgerService
    private lateinit var rational: RationalizationService
    private lateinit var telemetryPort: FakeTelemetryPort

    private val dayMs = 86_400_000L
    private val t0 = 1_000_000L

    @Before
    fun setup() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .allowMainThreadQueries()
            .addCallback(object : androidx.room.RoomDatabase.Callback() {
                override fun onCreate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
                    BudgetCoreMigrationSql.TRIGGERS.forEach(db::execSQL)
                    Stage2MigrationSql.TRIGGERS.forEach(db::execSQL)
                    Stage3MigrationSql.TRIGGERS.forEach(db::execSQL)
                    Stage4MigrationSql.TRIGGERS.forEach(db::execSQL)
                    Stage5MigrationSql.TRIGGERS.forEach(db::execSQL)
                    Stage2MigrationSql.STATEMENTS.filter { it.startsWith("INSERT") }.forEach(db::execSQL)
                }
            })
            .build()
        var tick = 1L
        ledger = BudgetLedgerService(
            database = db,
            idGenerator = { "id-${tick++}" },
            clock = { t0 }
        )
        telemetryPort = FakeTelemetryPort()
        rational = RationalizationService(database = db, telemetry = telemetryPort, clock = { t0 })
    }

    @After
    fun tearDown() {
        db.close()
    }

    // ------------------------------------------------------------------
    // Under-utilization — a REAL low-utilization run through the ledger.
    // ------------------------------------------------------------------

    @Test
    fun `under-utilization is recommended with the exact numbers, over the audit channel`() = runBlocking {
        val created = ledger.createBudget(
            BudgetLedgerService.CreateBudgetRequest(
                scopeType = "WORKSPACE",
                platformObjectType = "WORKSPACE",
                platformObjectId = "ws-rat",
                providerId = null,
                budgetName = "Rationalization test period",
                currency = "USD",
                limitMicroUnits = 3_000_000L,
                periodStartEpochMs = t0,
                periodEndEpochMs = t0 + 60 * dayMs
            )
        )
        // 5 distinct posting days at 100k each = 500k of a 3M limit (16.7%)
        for (d in 1..5) {
            ledger.postUsageActual(
                scopeType = "WORKSPACE",
                platformObjectId = "ws-rat",
                amountMicroUnits = 100_000L,
                reason = "day-$d",
                idempotencyKey = "rat-$d",
                nowEpochMs = t0 + d * dayMs - 1_000
            )
        }

        val recs = rational.underUtilization(nowEpochMs = t0 + 6 * dayMs)
        assertEquals(1, recs.size)
        val rec = recs.single()
        assertEquals(RationalizationService.Vocab.KIND_UNDER_UTILIZATION, rec.kind)
        assertEquals(created.periodId, rec.resourceId)
        assertEquals("500000", rec.evidence["postedConsumptionMicroUnits"])
        assertEquals("3000000", rec.evidence["limitMicroUnits"])
        assertEquals("5", rec.evidence["distinctPostingDays"])
        assertTrue(rec.evidence["utilization"]!!.startsWith("0.16"))
        assertEquals(RationalizationService.Vocab.POLICY_VERSION, rec.policyVersion)

        // the audit channel: the durable artifact of the recommendation
        val ids = rational.emit(recs, asOfEpochMs = t0 + 6 * dayMs)
        assertEquals(1, ids.size)
        assertEquals(1, telemetryPort.auditEvents.size)
        val event = telemetryPort.auditEvents.single()
        assertEquals("RATIONALIZATION", event.action)
        assertEquals("UNDER_UTILIZATION", event.decision)
        assertEquals("budget_period", event.resourceType)
        assertEquals(created.periodId, event.resourceId)
        assertEquals("rationalization:v1", event.attributes["policyVersion"])
        assertEquals("500000", event.attributes["postedConsumptionMicroUnits"])
    }

    @Test
    fun `thin data stays silent - 2 posting days never emit an under-utilization verdict`() = runBlocking {
        ledger.createBudget(
            BudgetLedgerService.CreateBudgetRequest(
                scopeType = "WORKSPACE",
                platformObjectType = "WORKSPACE",
                platformObjectId = "ws-thin",
                providerId = null,
                budgetName = "Thin period",
                currency = "USD",
                limitMicroUnits = 3_000_000L,
                periodStartEpochMs = t0,
                periodEndEpochMs = t0 + 60 * dayMs
            )
        )
        for (d in 1..2) {
            ledger.postUsageActual(
                scopeType = "WORKSPACE", platformObjectId = "ws-thin",
                amountMicroUnits = 100_000L, reason = "day-$d",
                idempotencyKey = "thin-$d", nowEpochMs = t0 + d * dayMs - 1_000
            )
        }
        val recs = rational.underUtilization(nowEpochMs = t0 + 3 * dayMs)
        assertTrue("the I-38 minimum-data discipline keeps the verdict silent", recs.isEmpty())
    }

    @Test
    fun `consumption past the limit is recommended for an overage review`() = runBlocking {
        ledger.createBudget(
            BudgetLedgerService.CreateBudgetRequest(
                scopeType = "WORKSPACE",
                platformObjectType = "WORKSPACE",
                platformObjectId = "ws-over",
                providerId = null,
                budgetName = "Overage period",
                currency = "USD",
                limitMicroUnits = 1_000_000L,
                periodStartEpochMs = t0,
                periodEndEpochMs = t0 + 60 * dayMs
            )
        )
        for (d in 1..12) {
            ledger.postUsageActual(
                scopeType = "WORKSPACE", platformObjectId = "ws-over",
                amountMicroUnits = 150_000L, reason = "day-$d",
                idempotencyKey = "over-$d", nowEpochMs = t0 + d * dayMs - 1_000
            )
        }
        val recs = rational.underUtilization(nowEpochMs = t0 + 13 * dayMs)
        val overage = recs.filter { it.kind == RationalizationService.Vocab.KIND_OVERAGE_REVIEW }
        assertEquals(1, overage.size)
        assertEquals("1800000", overage.single().evidence["postedConsumptionMicroUnits"])
        assertEquals("1000000", overage.single().evidence["limitMicroUnits"])
        // 180% utilization - past the limit
        assertTrue(overage.single().evidence["utilization"]!!.startsWith("1.8"))
    }

    // ------------------------------------------------------------------
    // Provider comparison - the identity chain + measured volume.
    // ------------------------------------------------------------------

    private var cmpScopeId: String = ""

    private suspend fun seedComparisonChain() {
        // a real descriptor the pricing profile's applicability FK needs
        ledger.createBudget(
            BudgetLedgerService.CreateBudgetRequest(
                scopeType = "WORKSPACE",
                platformObjectType = "WORKSPACE",
                platformObjectId = "ws-cmp",
                providerId = null,
                budgetName = "Comparison period",
                currency = "USD",
                limitMicroUnits = 10_000_000L,
                periodStartEpochMs = t0,
                periodEndEpochMs = t0 + 60 * dayMs
            )
        )
        cmpScopeId = ledger.scopeIdFor("WORKSPACE", "ws-cmp")
            ?: throw IllegalStateException("descriptor missing for ws-cmp")
        val reg = db.providerRegistryDao()
        val models = db.modelRegistryDao()
        // two providers, one canonical model
        for (p in listOf("prov-a" to "pa", "prov-b" to "pb")) {
            reg.insertProvider(
                PdmProviderEntity(
                    providerId = p.first, providerCode = p.second, name = p.second,
                    displayName = null, description = null, providerStatus = "ACTIVE",
                    createdAtEpochMs = 0, updatedAtEpochMs = 0, createdBy = null, updatedBy = null
                )
            )
            reg.insertIntegration(
                ProviderIntegrationEntity(
                    integrationId = "int-${p.second}", providerId = p.first,
                    integrationCode = "main", integrationType = "API",
                    protocol = null, protocolVersion = null, defaultEndpoint = null,
                    authenticationScheme = null, integrationStatus = "ACTIVE",
                    createdAtEpochMs = 0, updatedAtEpochMs = 0
                )
            )
        }
        models.insertModel(
            ModelEntity(
                modelId = "mdl-1", canonicalName = "gpt-test", modelFamily = "gpt",
                modelVersion = "1", revision = null, modalities = null, capabilities = null,
                contextLimit = null, outputLimit = null, lifecycleStatus = "ACTIVE",
                metadata = null, createdAtEpochMs = 0, updatedAtEpochMs = 0
            )
        )
        // each provider's native record, VERIFIED to the same canonical model
        for (p in listOf("prov-a" to "pa", "prov-b" to "pb")) {
            models.insertProviderModelRecord(
                ProviderModelRecordEntity(
                    providerModelRecordId = "pmr-${p.second}", providerId = p.first,
                    integrationId = "int-${p.second}", providerNativeModelId = "native-${p.second}",
                    displayName = null, providerVersion = null, discoverySource = "TEST",
                    discoveryStatus = "DISCOVERED", rawMetadata = null,
                    firstSeenAtEpochMs = 0, lastSeenAtEpochMs = 0
                )
            )
            models.insertIdentityMatch(
                ModelIdentityMatchEntity(
                    matchId = "match-${p.second}", providerModelRecordId = "pmr-${p.second}",
                    canonicalModelId = "mdl-1", matchStatus = "VERIFIED",
                    confidenceScore = "0.99", matchReason = "TEST",
                    validFromEpochMs = 0, validUntilEpochMs = null,
                    matchedAtEpochMs = 0, supersededByMatchId = null
                )
            )
            models.insertOffering(
                ModelOfferingEntity(
                    offeringId = "off-${p.second}", providerModelRecordId = "pmr-${p.second}",
                    offeringCode = "off-${p.second}", endpoint = null, region = null,
                    serviceMode = null, variant = null, availabilityStatus = "AVAILABLE",
                    availabilitySource = "TEST", createdAtEpochMs = 0, updatedAtEpochMs = 0
                )
            )
            reg.insertAccessContext(
                AccessContextEntity(
                    accessContextId = "ac-${p.second}", providerId = p.first,
                    integrationId = "int-${p.second}", accountScopeNodeId = null,
                    billingScopeNodeId = null, contextLabel = "test",
                    defaultRegion = null, contextStatus = "ACTIVE",
                    createdAtEpochMs = 0, updatedAtEpochMs = 0, lastVerifiedAtEpochMs = 0
                )
            )
        }
    }

    private suspend fun seedCalculation(
        providerCode: String,
        attemptId: String,
        costMicro: Long,
        inputTokens: String,
        outputTokens: String
    ) {
        val models = db.modelRegistryDao()
        models.insertRequestOperation(
            RequestOperationEntity(
                requestOperationId = "op-$attemptId", applicationRef = null, workspaceRef = null,
                projectRef = null, sessionRef = null, operationType = "LLM_CALL",
                operationStatus = "COMPLETED", createdAtEpochMs = 0, clientOperationId = null
            )
        )
        models.insertRequestAttempt(
            ProviderRequestAttemptEntity(
                requestAttemptId = attemptId,
                requestOperationId = "op-$attemptId",
                providerId = if (providerCode == "pa") "prov-a" else "prov-b",
                integrationId = "int-$providerCode",
                offeringId = "off-$providerCode",
                accessContextId = "ac-$providerCode",
                credentialBindingId = null, providerRequestId = null, clientAttemptId = null,
                attemptNumber = 1, startedAtEpochMs = 0, completedAtEpochMs = 1,
                requestStatus = "COMPLETED", httpStatus = 200, providerErrorCode = null,
                retryOfAttemptId = null, idempotencyKey = null
            )
        )
        // usage record + measurements
        db.usageRecordDao().insert(
            UsageRecordEntity(
                usageRecordId = "ur-$attemptId", requestAttemptId = attemptId,
                reportSource = "TEST", reportVersion = "1",
                reportedAtEpochMs = 0, retrievedAtEpochMs = 0, reportStatus = "FINAL",
                isFinalReport = 1, supersedesUsageRecordId = null,
                providerRequestId = null, recordedAtEpochMs = 0
            )
        )
        db.usageMeasurementDao().insert(
            UsageMeasurementEntity(
                measurementId = "um-in-$attemptId", usageRecordId = "ur-$attemptId",
                metricId = "METRIC_INPUT_TOKENS", valuePrecise = inputTokens,
                unitId = "UNIT_TOKEN", sourceAuthority = "TEST", sourceField = null,
                measurementStatus = "FINAL", knowledgeStatus = "KNOWN",
                unknownReasonCode = null, recordedAtEpochMs = 0
            )
        )
        db.usageMeasurementDao().insert(
            UsageMeasurementEntity(
                measurementId = "um-out-$attemptId", usageRecordId = "ur-$attemptId",
                metricId = "METRIC_OUTPUT_TOKENS", valuePrecise = outputTokens,
                unitId = "UNIT_TOKEN", sourceAuthority = "TEST", sourceField = null,
                measurementStatus = "FINAL", knowledgeStatus = "KNOWN",
                unknownReasonCode = null, recordedAtEpochMs = 0
            )
        )
        // pricing chain + the calculation row
        db.pricingProfileDao().insert(
            PricingProfileEntity(
                pricingProfileId = "pp-$attemptId", providerId = if (providerCode == "pa") "prov-a" else "prov-b",
                profileCode = "code-$attemptId", currency = "USD",
                applicabilityScopeId = cmpScopeId, profileStatus = "ACTIVE",
                createdAtEpochMs = 0, updatedAtEpochMs = 0
            )
        )
        db.pricingProfileVersionDao().insert(
            PricingProfileVersionEntity(
                pricingProfileVersionId = "ppv-$attemptId", pricingProfileId = "pp-$attemptId",
                versionNumber = 1, pricingSource = "TEST", authority = "TEST",
                effectiveFromEpochMs = 0, effectiveUntilEpochMs = null,
                retrievedAtEpochMs = 0, recordedAtEpochMs = 0, effectiveStatus = "ACTIVE",
                verificationStatus = "VERIFIED", precedence = 0, pricingStatus = "ACTIVE",
                roundingStrategy = "ROUND_LINE_THEN_SUM", createdAtEpochMs = 0
            )
        )
        db.costCalculationDao().insert(
            CostCalculationEntity(
                costCalculationId = "cc-$attemptId", calculationLevel = "ATTEMPT",
                requestAttemptId = attemptId, usageRecordId = "ur-$attemptId",
                calculationScopeId = null, periodStartEpochMs = null, periodEndEpochMs = null,
                pricingProfileVersionId = "ppv-$attemptId", calculationEngineVersion = "test",
                ruleSetVersion = "test", inputHash = "h-$attemptId", currency = "USD",
                totalAmountMicroUnits = costMicro, calculationStatus = "CALCULATED",
                confidenceState = "HIGH", calculatedAtEpochMs = 0, recordedAtEpochMs = 0,
                supersedesCalculationId = null
            )
        )
    }

    @Test
    fun `provider comparison - full chain, the cheaper provider is recommended`() = runBlocking {
        seedComparisonChain()
        // provider A: 100 tokens for 1,000,000 micro (10,000 per token)
        seedCalculation("pa", "att-a1", 1_000_000L, "60", "40")
        // provider B: 100 tokens for 250,000 micro (2,500 per token) - 4x cheaper
        seedCalculation("pb", "att-b1", 250_000L, "50", "50")

        val recs = rational.providerComparison()
        assertEquals(1, recs.size)
        val rec = recs.single()
        assertEquals(RationalizationService.Vocab.KIND_PROVIDER_COMPARISON, rec.kind)
        assertEquals("mdl-1", rec.resourceId)
        assertEquals("gpt-test", rec.evidence["canonicalModelName"])
        assertEquals("1000000", rec.evidence["provider.prov-a.costMicroUnits"])
        assertEquals("250000", rec.evidence["provider.prov-b.costMicroUnits"])
        // 4.0x gap: 10,000 vs 2,500 per token
        assertTrue(rec.evidence["provider.prov-a.perToken"]!!.startsWith("10000"))
        assertTrue(rec.evidence["provider.prov-b.perToken"]!!.startsWith("2500"))
        assertTrue(rec.headline.contains("prov-b"))
    }

    @Test
    fun `provider comparison - empty registry stays honestly silent`() = runBlocking {
        val recs = rational.providerComparison()
        assertTrue("no registry, no volume - no fabricated comparison", recs.isEmpty())
    }

    @Test
    fun `provider comparison - a small gap stays silent`() = runBlocking {
        seedComparisonChain()
        // A: 100 tokens for 1,000,000; B: 100 tokens for 900,000 - a 1.11x gap
        seedCalculation("pa", "att-a2", 1_000_000L, "60", "40")
        seedCalculation("pb", "att-b2", 900_000L, "50", "50")
        val recs = rational.providerComparison()
        assertTrue("a 1.11x gap is under the 1.2 policy floor - silent", recs.isEmpty())
    }

    // ------------------------------------------------------------------
    // Reconciliation quality - the unexplained share + the modal suggestion.
    // ------------------------------------------------------------------

    private suspend fun seedReconciliation(
        billingId: String,
        adjustments: List<Pair<String, Long>> // (type, signed amount)
    ): String {
        // a real descriptor via the real budget path (the reconciliation FK)
        ledger.createBudget(
            BudgetLedgerService.CreateBudgetRequest(
                scopeType = "WORKSPACE",
                platformObjectType = "WORKSPACE",
                platformObjectId = "ws-quality-$billingId",
                providerId = null,
                budgetName = "Quality $billingId",
                currency = "USD",
                limitMicroUnits = 10_000_000L,
                periodStartEpochMs = t0,
                periodEndEpochMs = t0 + 30 * dayMs
            )
        )
        val scopeId = ledger.scopeIdFor("WORKSPACE", "ws-quality-$billingId")
            ?: throw IllegalStateException("descriptor missing for ws-quality-$billingId")
        db.providerBillingRecordDao().insert(
            ProviderBillingRecordEntity(
                providerBillingRecordId = billingId, providerId = "prov-bill",
                accessContextId = null, externalBillingId = null,
                billingRecordType = "INVOICE", recordGranularity = "MONTHLY",
                periodStartEpochMs = 0, periodEndEpochMs = 30 * dayMs,
                scopeId = null, primaryAmountType = "GROSS_PAYABLE",
                primaryAmountMicroUnits = 10_000_000L, grossPayableMicroUnits = 10_000_000L,
                netBeforeTaxMicroUnits = null, taxAmountMicroUnits = null,
                creditAmountMicroUnits = null, adjustmentAmountMicroUnits = null,
                currency = "USD", amountConsistencyStatus = "CONSISTENT",
                sourceAuthority = "TEST", reportedAtEpochMs = 0, retrievedAtEpochMs = 0,
                recordedAtEpochMs = 0, billingStatus = "POSTED", adjustsBillingRecordId = null
            )
        )
        db.reconciliationRecordDao().insert(
            ReconciliationRecordEntity(
                reconciliationId = "rec-$billingId", providerBillingRecordId = billingId,
                scopeId = scopeId, periodStartEpochMs = 0, periodEndEpochMs = 30 * dayMs,
                providerCurrency = "USD", localCurrency = "USD", comparisonCurrency = "USD",
                conversionRuleId = null, localAmountMicroUnits = 9_500_000L,
                providerAmountMicroUnits = 10_000_000L, differenceMicroUnits = 500_000L,
                toleranceMicroUnits = 0, coverageStatus = "COMPLETE",
                reconciliationStatus = "WITHIN_TOLERANCE", finalityStatus = "DRAFT",
                reasonCode = null, ruleVersion = "fx:v1", reconciledAtEpochMs = null,
                recordedAtEpochMs = 0, supersededBy = null
            )
        )
        var n = 0
        for ((type, amount) in adjustments) {
            db.reconciliationAdjustmentDao().insert(
                ReconciliationAdjustmentEntity(
                    adjustmentId = "adj-$billingId-${n++}", reconciliationId = "rec-$billingId",
                    adjustmentType = type, amountMicroUnits = amount, currency = "USD",
                    reasonCode = "TEST", notes = null, createdAtEpochMs = 0
                )
            )
        }
        return "rec-$billingId"
    }

    @Test
    fun `quality indicator - the unexplained share is computed, thin history stays INSUFFICIENT_DATA`() = runBlocking {
        // 100k tax + 50k fee + 200k unexplained, all provider prov-bill
        val recId = seedReconciliation(
            "bill-1",
            listOf(
                "PROVIDER_ONLY_TAX" to 100_000L,
                "PROVIDER_ONLY_FEE" to 50_000L,
                "LOCAL_UNMODELED_ADJUSTMENT" to 200_000L
            )
        )
        val recs = rational.reconciliationQualityRecommendations()
        assertEquals(1, recs.size)
        val rec = recs.single()
        assertEquals(recId, rec.resourceId)
        // unexplained share = 200k / 350k = 0.571
        assertTrue(rec.evidence["unexplainedShare"]!!.startsWith("0.571"))
        // only 2 classified rows in history - under MIN_HISTORY_FOR_SUGGESTION(3)
        assertEquals("INSUFFICIENT_DATA", rec.evidence["suggestedClassification"])

        // ---- accumulate history for provider prov-bill: 3 classified rows ----
        seedReconciliation(
            "bill-2",
            listOf(
                "PROVIDER_ONLY_TAX" to 30_000L,
                "PROVIDER_ONLY_TAX" to 40_000L,
                "PROVIDER_ONLY_FEE" to 10_000L
            )
        )
        val after = rational.reconciliationQuality()
        val forBilling1 = after.first { it.reconciliationId == recId }
        assertEquals("PROVIDER_ONLY_TAX", forBilling1.suggestion)
        assertTrue(forBilling1.suggestionBasis.contains("modal classification"))
        // and the engine's basis names the counts
        assertTrue(forBilling1.suggestionBasis.contains("5"))
    }
}
