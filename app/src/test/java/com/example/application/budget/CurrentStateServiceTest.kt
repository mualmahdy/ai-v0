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
import com.example.infrastructure.persistence.entities.FreshnessPolicyEntity
import com.example.infrastructure.persistence.entities.PdmProviderEntity
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

/**
 * ============================================================================
 * CurrentStateService + ObservationWriterService unit tests
 * ============================================================================
 * The fresh-install path (in-memory Room + the four trigger families from
 * the onCreate callback). The DB CHECKs live only on the migration path —
 * here the SERVICE-level validation and the TRIGGERS are under test (the
 * I-25 defense-in-depth split: each layer proven on its own path).
 */
@RunWith(RobolectricTestRunner::class)
class CurrentStateServiceTest {

    private lateinit var db: AppDatabase
    private lateinit var writer: ObservationWriterService
    private lateinit var states: CurrentStateService

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
                providerId = "prov-1", providerCode = "pdx", name = "PDX",
                displayName = null, description = null, providerStatus = "ACTIVE",
                createdAtEpochMs = 0, updatedAtEpochMs = 0, createdBy = null, updatedBy = null
            )
        )
        // fp-1: a policy that DROPS stale values (unknown_when_expired=1)
        db.observationDao().insertFreshnessPolicy(
            FreshnessPolicyEntity(
                policyId = "fp-1",
                metricId = "METRIC_INPUT_TOKENS",
                scopeId = null,
                expectedUpdateIntervalSeconds = 3600,
                stalenessThresholdSeconds = 86_400,
                sourceDelaySeconds = 0,
                unknownWhenExpired = 1,
                effectiveFromEpochMs = 0,
                effectiveUntilEpochMs = null,
                createdAtEpochMs = 0
            )
        )
        // fp-2: a policy that KEEPS stale values, flagged STALE
        db.observationDao().insertFreshnessPolicy(
            FreshnessPolicyEntity(
                policyId = "fp-2",
                metricId = "METRIC_INPUT_TOKENS",
                scopeId = null,
                expectedUpdateIntervalSeconds = 3600,
                stalenessThresholdSeconds = 86_400,
                sourceDelaySeconds = 0,
                unknownWhenExpired = 0,
                effectiveFromEpochMs = 0,
                effectiveUntilEpochMs = null,
                createdAtEpochMs = 0
            )
        )
    }

    // ------------------------------------------------------------------
    // The writer: exactly-one-subject + value/knowledge shape.
    // ------------------------------------------------------------------

    @Test
    fun `writer rejects zero or two subjects`() = runBlocking {
        seedRegistry()
        try {
            writer.record(
                ObservationWriterService.ObservationSpec(
                    providerId = "prov-1",
                    metricId = "METRIC_INPUT_TOKENS",
                    valueType = "NUMERIC",
                    numericValuePrecise = "100",
                    observedAtEpochMs = t0,
                    sourceAuthority = "TEST"
                )
            )
            assertTrue("zero subjects must be rejected", false)
        } catch (expected: IllegalArgumentException) { }

        try {
            writer.record(
                ObservationWriterService.ObservationSpec(
                    providerId = "prov-1",
                    subjectProviderId = "prov-1",
                    subjectModelId = "m-1",
                    metricId = "METRIC_INPUT_TOKENS",
                    valueType = "NUMERIC",
                    numericValuePrecise = "100",
                    observedAtEpochMs = t0,
                    sourceAuthority = "TEST"
                )
            )
            assertTrue("two subjects must be rejected", false)
        } catch (expected: IllegalArgumentException) { }
    }

    @Test
    fun `writer rejects a KNOWN observation without its typed value and a non-KNOWN one with it`() = runBlocking {
        seedRegistry()
        try {
            writer.record(
                ObservationWriterService.ObservationSpec(
                    providerId = "prov-1", subjectProviderId = "prov-1",
                    metricId = "METRIC_INPUT_TOKENS", valueType = "NUMERIC",
                    observedAtEpochMs = t0, sourceAuthority = "TEST"
                )
            )
            assertTrue("KNOWN NUMERIC without a value must be rejected", false)
        } catch (expected: IllegalArgumentException) { }

        try {
            writer.record(
                ObservationWriterService.ObservationSpec(
                    providerId = "prov-1", subjectProviderId = "prov-1",
                    metricId = "METRIC_INPUT_TOKENS", valueType = "NUMERIC",
                    numericValuePrecise = "100",
                    observedAtEpochMs = t0, sourceAuthority = "TEST",
                    knowledgeStatus = "UNKNOWN"
                )
            )
            assertTrue("non-KNOWN with a value must be rejected", false)
        } catch (expected: IllegalArgumentException) { }

        // the legal UNKNOWN shape goes through (an explicit unknown fact)
        val out = writer.record(
            ObservationWriterService.ObservationSpec(
                providerId = "prov-1", subjectProviderId = "prov-1",
                metricId = "METRIC_INPUT_TOKENS", valueType = "NUMERIC",
                observedAtEpochMs = t0, sourceAuthority = "TEST",
                knowledgeStatus = "UNKNOWN", unknownReasonCode = "PROVIDER_SILENT"
            )
        )
        assertTrue(out is ObservationWriterService.WriteOutcome.Written)
    }

    // ------------------------------------------------------------------
    // The projection: freshness tiers, supersede, rebuild.
    // ------------------------------------------------------------------

    @Test
    fun `derive - FRESH, PRIMARY and SUPPORTING sources, value carried`() = runBlocking {
        seedRegistry()
        writer.record(
            ObservationWriterService.ObservationSpec(
                providerId = "prov-1", subjectProviderId = "prov-1",
                metricId = "METRIC_INPUT_TOKENS", valueType = "NUMERIC",
                numericValuePrecise = "400",
                unitId = "UNIT_TOKEN",
                observedAtEpochMs = t0 - 600_000, sourceAuthority = "TEST"
            )
        )
        writer.record(
            ObservationWriterService.ObservationSpec(
                providerId = "prov-1", subjectProviderId = "prov-1",
                metricId = "METRIC_INPUT_TOKENS", valueType = "NUMERIC",
                numericValuePrecise = "500",
                unitId = "UNIT_TOKEN",
                observedAtEpochMs = t0, sourceAuthority = "TEST"
            )
        )

        val outcome = states.derive(
            CurrentStateService.DeriveSpec(
                metricId = "METRIC_INPUT_TOKENS",
                subjectProviderId = "prov-1",
                derivationPolicyId = "fp-1",
                nowEpochMs = t0 + 60_000
            )
        )
        assertTrue(outcome is CurrentStateService.DeriveOutcome.Derived)
        val derived = outcome as CurrentStateService.DeriveOutcome.Derived
        assertEquals("OK", derived.stateStatus)
        assertEquals("FRESH", derived.freshnessState)
        val sources = db.currentStateDao().sourcesForState(derived.stateId)
        assertEquals(2, sources.size)
        // PRIMARY = the NEWEST observation; the older one is SUPPORTING
        val primary = sources.first { it.sourceRole == "PRIMARY" }
        val supporting = sources.first { it.sourceRole == "SUPPORTING" }
        assertEquals(derived.primarySourceObservationId, primary.observationId)
        assertEquals(derived.supportingSourceObservationIds, listOf(supporting.observationId))
        val active = db.currentStateDao().activeStateFor(
            metricId = "METRIC_INPUT_TOKENS", scopeId = null,
            subjectProviderId = "prov-1", subjectAccessContextId = null,
            subjectProviderScopeNodeId = null, subjectProviderModelRecordId = null,
            subjectModelId = null, subjectOfferingId = null, subjectRequestAttemptId = null
        )
        assertNotNull(active)
        assertEquals("500", active!!.currentNumericValuePrecise)
        assertEquals("UNIT_TOKEN", active.unitId)
        assertEquals("OK", active.stateStatus)
        assertEquals("FRESH", active.freshnessState)
    }

    @Test
    fun `derive - AGING and STALE tiers, and the policy dropping a stale value to UNKNOWN`() = runBlocking {
        seedRegistry()
        writer.record(
            ObservationWriterService.ObservationSpec(
                providerId = "prov-1", subjectProviderId = "prov-1",
                metricId = "METRIC_INPUT_TOKENS", valueType = "NUMERIC",
                numericValuePrecise = "500", unitId = "UNIT_TOKEN",
                observedAtEpochMs = t0, sourceAuthority = "TEST"
            )
        )

        // AGING: beyond the interval (3600s) but within the threshold (86400s)
        val aging = states.derive(
            CurrentStateService.DeriveSpec(
                metricId = "METRIC_INPUT_TOKENS", subjectProviderId = "prov-1",
                derivationPolicyId = "fp-1", nowEpochMs = t0 + 7_200_000L
            )
        ) as CurrentStateService.DeriveOutcome.Derived
        assertEquals("AGING", aging.freshnessState)
        assertEquals("OK", aging.stateStatus)

        // STALE under fp-2 (unknown_when_expired=0): the value is KEPT,
        // flagged STALE — never silently presented as fresh
        val stale = states.derive(
            CurrentStateService.DeriveSpec(
                metricId = "METRIC_INPUT_TOKENS", subjectProviderId = "prov-1",
                derivationPolicyId = "fp-2", nowEpochMs = t0 + 200_000_000L
            )
        ) as CurrentStateService.DeriveOutcome.Derived
        assertEquals("STALE", stale.freshnessState)
        assertEquals("STALE", stale.stateStatus)
        assertEquals("500", db.currentStateDao().activeStates().single().currentNumericValuePrecise)

        // far beyond the threshold under fp-1 (unknown_when_expired=1): the
        // value is honestly DROPPED — never a stale-but-here-anyway number.
        val dropped = states.derive(
            CurrentStateService.DeriveSpec(
                metricId = "METRIC_INPUT_TOKENS", subjectProviderId = "prov-1",
                derivationPolicyId = "fp-1", nowEpochMs = t0 + 2_000_000_000L
            )
        ) as CurrentStateService.DeriveOutcome.Derived
        assertEquals("UNKNOWN", dropped.freshnessState)
        assertEquals("UNKNOWN", dropped.stateStatus)
        val activeRow = db.currentStateDao().activeStates().single()
        assertNull(activeRow.currentNumericValuePrecise)
    }

    @Test
    fun `derive - NOT_DERIVABLE when no valid observation exists`() = runBlocking {
        seedRegistry()
        val outcome = states.derive(
            CurrentStateService.DeriveSpec(
                metricId = "METRIC_INPUT_TOKENS", subjectProviderId = "prov-1",
                derivationPolicyId = "fp-1", nowEpochMs = t0
            )
        )
        assertTrue(outcome is CurrentStateService.DeriveOutcome.NotDerivable)
        assertEquals(0, db.currentStateDao().countStates())
    }

    @Test
    fun `derive - supersede retires the incumbent and the uniqueness trigger backs it`() = runBlocking {
        seedRegistry()
        writer.record(
            ObservationWriterService.ObservationSpec(
                providerId = "prov-1", subjectProviderId = "prov-1",
                metricId = "METRIC_INPUT_TOKENS", valueType = "NUMERIC",
                numericValuePrecise = "500", observedAtEpochMs = t0, sourceAuthority = "TEST"
            )
        )
        val first = states.derive(
            CurrentStateService.DeriveSpec(
                metricId = "METRIC_INPUT_TOKENS", subjectProviderId = "prov-1",
                derivationPolicyId = "fp-1", nowEpochMs = t0
            )
        ) as CurrentStateService.DeriveOutcome.Derived

        // a second, newer observation -> a second derivation supersedes
        writer.record(
            ObservationWriterService.ObservationSpec(
                providerId = "prov-1", subjectProviderId = "prov-1",
                metricId = "METRIC_INPUT_TOKENS", valueType = "NUMERIC",
                numericValuePrecise = "900", observedAtEpochMs = t0 + 1_000, sourceAuthority = "TEST"
            )
        )
        val second = states.derive(
            CurrentStateService.DeriveSpec(
                metricId = "METRIC_INPUT_TOKENS", subjectProviderId = "prov-1",
                derivationPolicyId = "fp-1", nowEpochMs = t0 + 2_000
            )
        ) as CurrentStateService.DeriveOutcome.Derived

        assertEquals(listOf(first.stateId), second.supersededStateIds)
        // exactly one ACTIVE row; the incumbent is retired
        assertEquals(1, db.currentStateDao().countActiveStates())
        assertEquals(2, db.currentStateDao().countStates())
        val retired = db.currentStateDao().stateById(first.stateId)!!
        assertEquals(0, retired.isActive)
        // the active row carries the NEWEST value
        assertEquals("900", db.currentStateDao().activeStates().single().currentNumericValuePrecise)

        // the DB-level guard: a raw second ACTIVE row for the same key is ABORTED
        try {
            db.openHelper.writableDatabase.execSQL(
                "INSERT INTO current_resource_state (state_id, subject_provider_id, metric_id, current_value_type, " +
                    "current_numeric_value_precise, scope_id, state_as_of_epoch_ms, freshness_state, " +
                    "derivation_policy_id, state_status, is_active, updated_at_epoch_ms) VALUES " +
                    "('st-raw','prov-1','METRIC_INPUT_TOKENS','NUMERIC','123',null," + (t0 + 2000) + ",'FRESH','fp-1','OK',1,0)"
            )
            assertTrue("a raw second active row must be ABORTED by the trigger", false)
        } catch (expected: android.database.SQLException) {
            assertTrue(expected.message!!.contains("already has an active row"))
        }
    }

    @Test
    fun `rebuild - the projection is rebuildable from the observations alone`() = runBlocking {
        seedRegistry()
        writer.record(
            ObservationWriterService.ObservationSpec(
                providerId = "prov-1", subjectProviderId = "prov-1",
                metricId = "METRIC_INPUT_TOKENS", valueType = "NUMERIC",
                numericValuePrecise = "500", observedAtEpochMs = t0, sourceAuthority = "TEST"
            )
        )
        writer.record(
            ObservationWriterService.ObservationSpec(
                providerId = "prov-1", subjectProviderId = "prov-1",
                metricId = "METRIC_INPUT_TOKENS", valueType = "NUMERIC",
                numericValuePrecise = "900", observedAtEpochMs = t0 + 1_000, sourceAuthority = "TEST"
            )
        )
        states.derive(
            CurrentStateService.DeriveSpec(
                metricId = "METRIC_INPUT_TOKENS", subjectProviderId = "prov-1",
                derivationPolicyId = "fp-1", nowEpochMs = t0 + 2_000
            )
        )
        assertEquals(1, db.currentStateDao().countActiveStates())

        val after = states.rebuild()
        // the rebuild re-derived the single key from the observations alone
        assertEquals(1, after)
        val active = db.currentStateDao().activeStates().single()
        assertEquals("900", active.currentNumericValuePrecise)
        // the previous rows are all retired — one active row per key
        assertTrue(db.currentStateDao().countStates() >= 2)
    }

    @Test
    fun `scope dimensions - insert and read back per scope`() = runBlocking {
        seedRegistry()
        // the FK parent scope row
        db.openHelper.writableDatabase.execSQL(
            "INSERT INTO scope_descriptor (scope_id, scope_kind, canonical_representation, canonical_hash, " +
                "hash_algorithm, canonicalization_version, provider_dimension_state, integration_dimension_state, " +
                "access_context_dimension_state, provider_scope_node_dimension_state, offering_dimension_state, " +
                "request_attempt_dimension_state, budget_policy_dimension_state, budget_period_dimension_state, " +
                "region_dimension_state, endpoint_dimension_state, period_dimension_state, platform_dimension_state, " +
                "platform_object_type, platform_object_id, created_at_epoch_ms) VALUES " +
                "('sd-x','WORKSPACE','v1|WORKSPACE|ws-x','hx','SHA-256',1,'NOT_APPLICABLE','NOT_APPLICABLE'," +
                "'NOT_APPLICABLE','NOT_APPLICABLE','NOT_APPLICABLE','NOT_APPLICABLE','UNKNOWN','UNKNOWN'," +
                "'NOT_APPLICABLE','NOT_APPLICABLE','NOT_APPLICABLE','KNOWN','WORKSPACE','ws-x',0)"
        )
        db.scopeDimensionDao().insertInternal(
            com.example.infrastructure.persistence.entities.ScopeInternalDimensionEntity(
                scopeDimensionId = "dim-1", scopeId = "sd-x", dimensionCode = "cost_center",
                dimensionState = "KNOWN", dimensionValue = "CC-7", valueType = "TEXT",
                createdAtEpochMs = 0
            )
        )
        db.scopeDimensionDao().insertCustom(
            com.example.infrastructure.persistence.entities.ScopeCustomDimensionEntity(
                scopeDimensionId = "cdim-1", scopeId = "sd-x", namespace = "team",
                dimensionCode = "cost_center", dimensionState = "NOT_APPLICABLE",
                dimensionValue = null, valueType = null, createdAtEpochMs = 0
            )
        )
        assertEquals(1, db.scopeDimensionDao().internalForScope("sd-x").size)
        assertEquals(1, db.scopeDimensionDao().customForScope("sd-x").size)
        assertEquals("CC-7", db.scopeDimensionDao().internalForScope("sd-x").single().dimensionValue)
    }
}
