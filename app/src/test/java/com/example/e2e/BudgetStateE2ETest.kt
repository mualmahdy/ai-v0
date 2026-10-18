package com.example.e2e

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.example.application.budget.BudgetLedgerService
import com.example.application.budget.CurrentStateService
import com.example.application.budget.ObservationWriterService
import com.example.infrastructure.persistence.AppDatabase
import com.example.infrastructure.persistence.BudgetCoreMigrationSql
import com.example.infrastructure.persistence.Stage2MigrationSql
import com.example.infrastructure.persistence.Stage3MigrationSql
import com.example.infrastructure.persistence.Stage4MigrationSql
import com.example.infrastructure.persistence.Stage5MigrationSql
import com.example.infrastructure.persistence.entities.FreshnessPolicyEntity
import com.example.infrastructure.persistence.entities.PdmProviderEntity
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * ============================================================================
 * STAGE 5 e2e #1 — the observation writers -> the derived projection ->
 * the G05 whole-version freeze, on one real database
 * ============================================================================
 * The documented deferral list this closes: current_resource_state +
 * its sources («إسقاط مشتق قابل لإعادة البناء») and the G05 whole-version
 * freeze («published versions are frozen images» — now enforced at the DB
 * level while the service's retirement path keeps working).
 */
@RunWith(RobolectricTestRunner::class)
class BudgetStateE2ETest {

    private lateinit var db: AppDatabase
    private lateinit var ledger: BudgetLedgerService
    private lateinit var writer: ObservationWriterService
    private lateinit var states: CurrentStateService

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
        val ids: () -> String = { "id-${tick++}" }
        ledger = BudgetLedgerService(database = db, idGenerator = ids, clock = { t0 })
        writer = ObservationWriterService(database = db, idGenerator = ids, clock = { t0 })
        states = CurrentStateService(database = db, idGenerator = ids, clock = { t0 })
    }

    @After
    fun tearDown() {
        db.close()
    }

    private suspend fun seedRegistry() {
        db.providerRegistryDao().insertProvider(
            PdmProviderEntity(
                providerId = "prov-e2e", providerCode = "pdx-e2e", name = "PDX e2e",
                displayName = null, description = null, providerStatus = "ACTIVE",
                createdAtEpochMs = 0, updatedAtEpochMs = 0, createdBy = null, updatedBy = null
            )
        )
        db.observationDao().insertFreshnessPolicy(
            FreshnessPolicyEntity(
                policyId = "fp-e2e", metricId = "METRIC_TOTAL_TOKENS", scopeId = null,
                expectedUpdateIntervalSeconds = 3600, stalenessThresholdSeconds = 86_400,
                sourceDelaySeconds = 0, unknownWhenExpired = 1,
                effectiveFromEpochMs = 0, effectiveUntilEpochMs = null, createdAtEpochMs = 0
            )
        )
    }

    @Test
    fun `writers land, the projection derives, supersedes and rebuilds - end to end`() = runBlocking {
        seedRegistry()

        // ---- day 1: the writer lands an honest fact (subject=provider) ----
        writer.record(
            ObservationWriterService.ObservationSpec(
                providerId = "prov-e2e", subjectProviderId = "prov-e2e",
                metricId = "METRIC_TOTAL_TOKENS", valueType = "NUMERIC",
                numericValuePrecise = "120000", unitId = "UNIT_TOKEN",
                scopeId = null, observedAtEpochMs = t0, sourceAuthority = "E2E"
            )
        )
        val d1 = states.derive(
            CurrentStateService.DeriveSpec(
                metricId = "METRIC_TOTAL_TOKENS", subjectProviderId = "prov-e2e",
                derivationPolicyId = "fp-e2e", nowEpochMs = t0 + 60_000
            )
        ) as CurrentStateService.DeriveOutcome.Derived
        assertEquals("OK", d1.stateStatus)
        assertEquals("FRESH", d1.freshnessState)
        assertEquals("120000", db.currentStateDao().activeStates().single().currentNumericValuePrecise)

        // ---- day 2: a newer fact supersedes it (one transaction, one active row) ----
        writer.record(
            ObservationWriterService.ObservationSpec(
                providerId = "prov-e2e", subjectProviderId = "prov-e2e",
                metricId = "METRIC_TOTAL_TOKENS", valueType = "NUMERIC",
                numericValuePrecise = "250000", unitId = "UNIT_TOKEN",
                scopeId = null, observedAtEpochMs = t0 + dayMs, sourceAuthority = "E2E"
            )
        )
        val d2 = states.derive(
            CurrentStateService.DeriveSpec(
                metricId = "METRIC_TOTAL_TOKENS", subjectProviderId = "prov-e2e",
                derivationPolicyId = "fp-e2e", nowEpochMs = t0 + dayMs + 60_000
            )
        ) as CurrentStateService.DeriveOutcome.Derived
        assertEquals(listOf(d1.stateId), d2.supersededStateIds)
        assertEquals(1, db.currentStateDao().countActiveStates())
        assertEquals("250000", db.currentStateDao().activeStates().single().currentNumericValuePrecise)
        // both states carry their sources (the derivation lineage is durable):
        // d1 had ONE observation -> PRIMARY only; d2 saw BOTH -> PRIMARY (newest)
        // + SUPPORTING (the older one in the evaluation window)
        assertEquals(1, db.currentStateDao().sourcesForState(d1.stateId).size)
        assertEquals("PRIMARY", db.currentStateDao().sourcesForState(d1.stateId).single().sourceRole)
        val d2Sources = db.currentStateDao().sourcesForState(d2.stateId)
        assertEquals(2, d2Sources.size)
        assertEquals(1, d2Sources.count { it.sourceRole == "PRIMARY" })
        assertEquals(1, d2Sources.count { it.sourceRole == "SUPPORTING" })

        // ---- the DB-level guard rejects a raw double-active write ----
        try {
            db.openHelper.writableDatabase.execSQL(
                "INSERT INTO current_resource_state (state_id, subject_provider_id, metric_id, current_value_type, " +
                    "current_numeric_value_precise, scope_id, state_as_of_epoch_ms, freshness_state, " +
                    "derivation_policy_id, state_status, is_active, updated_at_epoch_ms) VALUES " +
                    "('st-raw-e2e','prov-e2e','METRIC_TOTAL_TOKENS','NUMERIC','999',null," +
                    (t0 + dayMs) + ",'FRESH','fp-e2e','OK',1,0)"
            )
            assertTrue("the at-most-one-active guard must ABORT a raw double-active write", false)
        } catch (expected: android.database.SQLException) {
            assertTrue(expected.message!!.contains("already has an active row"))
        }

        // ---- the projection is rebuildable from the observations alone ----
        val after = states.rebuild()
        assertEquals(1, after)
        assertEquals("250000", db.currentStateDao().activeStates().single().currentNumericValuePrecise)
    }

    @Test
    fun `G05 - the whole-version freeze closes the gate while the retirement path keeps working`() = runBlocking {
        seedRegistry()
        // a real governing policy (v1) through the REAL service path
        val created = ledger.createBudget(
            BudgetLedgerService.CreateBudgetRequest(
                scopeType = "WORKSPACE", platformObjectType = "WORKSPACE",
                platformObjectId = "ws-g05", providerId = null,
                budgetName = "G05 e2e", currency = "USD",
                limitMicroUnits = 1_000_000L,
                periodStartEpochMs = t0, periodEndEpochMs = t0 + 30 * dayMs
            )
        )
        // some real posting so the period is genuinely governing financial reality
        ledger.postUsageActual(
            scopeType = "WORKSPACE", platformObjectId = "ws-g05",
            amountMicroUnits = 100_000L, reason = "g05",
            idempotencyKey = "g05-1", nowEpochMs = t0 + 1_000
        )

        // (a) the SERVICE retirement path still works: a new version rows in
        //     and the old ACTIVE one is RETIRED (status/updated_at only).
        val v2 = ledger.createPolicyVersion(
            familyId = created.policyFamilyId,
            updatedRequest = BudgetLedgerService.CreateBudgetRequest(
                scopeType = "WORKSPACE", platformObjectType = "WORKSPACE",
                platformObjectId = "ws-g05", providerId = null,
                budgetName = "G05 e2e v2", currency = "USD",
                limitMicroUnits = 2_000_000L,
                periodStartEpochMs = t0 + 30 * dayMs,
                periodEndEpochMs = t0 + 60 * dayMs
            )
        )
        assertEquals(2, db.budgetPolicyDao().familyHistory(created.policyFamilyId).map { it.policyVersion }.sorted().last())
        val v1Row = db.budgetPolicyDao().familyHistory(created.policyFamilyId)
            .first { it.budgetPolicyId == created.policyId }
        assertEquals("RETIRED", v1Row.policyStatus)
        assertEquals(1_000_000L, v1Row.defaultLimitMicroUnits)
        assertEquals("G05 e2e", v1Row.budgetName)
        // v1 keeps serving its own period untouched (full historical reinterpretation)
        assertEquals(created.policyId, v1Row.budgetPolicyId)

        // (b) the DB-level freeze: a substantive UPDATE of the governing v1 is ABORTED
        try {
            db.openHelper.writableDatabase.execSQL(
                "UPDATE budget_policy SET default_limit_micro_units=9000000 WHERE budget_policy_id='${created.policyId}'"
            )
            assertTrue("a governing version's limit must be frozen at the DB level", false)
        } catch (expected: android.database.SQLException) {
            assertTrue(expected.message!!.contains("frozen"))
        }
        try {
            db.openHelper.writableDatabase.execSQL(
                "UPDATE budget_policy SET budget_name='Smuggled rename' WHERE budget_policy_id='${created.policyId}'"
            )
            assertTrue("a governing version's name must be frozen at the DB level", false)
        } catch (expected: android.database.SQLException) { }

        // (c) the honest no-op update of the SAME values stays legal (the
        //     trigger compares OLD vs NEW — equality passes the freeze).
        db.openHelper.writableDatabase.execSQL(
            "UPDATE budget_policy SET default_limit_micro_units=1000000, updated_at_epoch_ms=50 WHERE budget_policy_id='${created.policyId}'"
        )

        // (d) v2 (which governs its own fresh period) is equally frozen
        try {
            db.openHelper.writableDatabase.execSQL(
                "UPDATE budget_policy SET currency='EUR' WHERE budget_policy_id='${v2.policyId}'"
            )
            assertTrue("v2 governs a period and must be frozen too", false)
        } catch (expected: android.database.SQLException) { }

        // (e) the version chain stays queryable and stable
        val history = db.budgetPolicyDao().familyHistory(created.policyFamilyId)
            .sortedBy { it.policyVersion }
        assertEquals(listOf(1, 2), history.map { it.policyVersion })
        assertNotNull(history.first { it.policyStatus == "ACTIVE" && it.policyVersion == 2 })
    }
}
