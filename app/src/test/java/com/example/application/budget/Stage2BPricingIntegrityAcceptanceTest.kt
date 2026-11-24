package com.example.application.budget

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.example.domain.core.budget.BillingClass
import com.example.infrastructure.persistence.AppDatabase
import com.example.infrastructure.persistence.BudgetCoreMigrationSql
import com.example.infrastructure.persistence.Stage2MigrationSql
import com.example.infrastructure.persistence.Stage3MigrationSql
import com.example.infrastructure.persistence.budget.RoomEconomicStore
import kotlinx.coroutines.async
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
 * STAGE 2B — Workstream C + D: pricing identity, append-only history,
 * catalog-mirror recovery, provenance & verification honesty
 * ============================================================================
 * Order §5 coverage:
 *  1. Two materially different price writes under the SAME injected clock
 *     timestamp → BOTH rows survive (collision-resistant identity; the old
 *     @Upsert-replace defect is closed).
 *  2. Repeated identical writes → idempotent no-op.
 *  3. Two distinct versions with increasing effective times → ordered
 *     catalog versions.
 *  4. A simulated catalog-sync failure AFTER the pricing entry was
 *     persisted → honest partial-failure outcome (entry stands).
 *  5. Retry after partial failure → the catalog catches up; historical
 *     resolveAt results stay stable.
 *  6. Historical lookup before/after a new price version → reproducible.
 *  7. Concurrent registration of the same identity → exactly-once rows.
 * Order §6 coverage (WS-D):
 *  - USER_CONFIG provenance ≠ independent verification; the catalog stamps
 *    UNVERIFIED and nothing promotes USER_CONFIG to VERIFIED.
 *  - Evidence reference recorded in the provenance chain (user-attested).
 *  - Identity probe verdicts surface honestly (no silent validity).
 */
@RunWith(RobolectricTestRunner::class)
class Stage2BPricingIntegrityAcceptanceTest {

    private lateinit var db: AppDatabase
    private lateinit var economicStore: RoomEconomicStore
    private lateinit var catalog: PricingCatalogService
    private lateinit var management: PricingManagementService

    /** Frozen clock — every write sees the SAME timestamp on purpose. */
    private val tick = 10_000L

    /** A catalog whose sync FAILS on demand (the WS-C partial-failure seam). */
    private var failSync = false
    private lateinit var failingCatalog: PricingCatalogService

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
                    Stage2MigrationSql.STATEMENTS.filter { it.startsWith("INSERT") }.forEach(db::execSQL)
                }
            })
            .build()
        economicStore = RoomEconomicStore(
            pricingDao = db.pricingEntryDao(),
            ledgerDao = db.costLedgerEntryDao(),
            allocationDao = db.budgetAllocationDao()
        )
        catalog = PricingCatalogService(database = db, clock = { tick })
        failingCatalog = object : PricingCatalogService(database = db, clock = { tick }) {
            override suspend fun syncFromLegacyEntries(): PricingCatalogService.SyncResult {
                if (failSync) throw IllegalStateException("simulated catalog outage")
                return super.syncFromLegacyEntries()
            }
        }
        management = PricingManagementService(
            pricingRepository = economicStore,
            pricingCatalog = failingCatalog,
            clock = { tick }
        )
    }

    @After
    fun tearDown() {
        db.close()
    }

    private fun request(
        input: Long?, output: Long? = null, currency: String = "USD",
        from: Long? = null, until: Long? = null,
        note: String? = null, evidence: String? = null
    ) = PricingManagementService.UserPricingRequest(
        providerId = "prov", serviceId = "svc", modelId = "m1",
        inputPriceMicroPerMillion = input, outputPriceMicroPerMillion = output,
        cachedInputPriceMicroPerMillion = null,
        currency = currency, billingClass = BillingClass.PAID,
        effectiveFromEpochMs = from, effectiveUntilEpochMs = until,
        sourceNote = note, evidenceReference = evidence
    )

    private suspend fun entryCount(): Int =
        db.openHelper.writableDatabase.query("SELECT COUNT(*) FROM pricing_entries")
            .use { c -> c.moveToFirst(); c.getInt(0) }

    private suspend fun catalogVersionCount(): Int {
        val profileId = PricingCatalogService.Ids.profileId(
            "prov", PricingCatalogService.Ids.profileCode("MODEL", "m1", "svc")
        )
        return db.pricingProfileVersionDao().forProfile(profileId).size
    }

    // ------------------------------------------------------------------
    // 1. Two materially different writes under the SAME clock tick — both
    //    survive; no historical record is overwritten.
    // ------------------------------------------------------------------

    @Test
    fun `two materially different prices under the same timestamp both survive`() = runBlocking {
        val first = management.registerUserConfiguredPricing(request(input = 1_500L))
        val second = management.registerUserConfiguredPricing(request(input = 2_000L))

        assertTrue(first is PricingManagementService.RegisterOutcome.Registered)
        assertTrue(second is PricingManagementService.RegisterOutcome.Registered)
        assertEquals(2, entryCount()) // BOTH rows — the second never replaces the first
        val ids = economicStore.allPricing().map { it.id }.toSet()
        assertEquals(2, ids.size)
        // Both prices are queryable history.
        val prices = economicStore.allPricing().mapNotNull { it.inputPricePerMillion?.amountMicro }.toSet()
        assertEquals(setOf(1_500L, 2_000L), prices)
        // The catalog mirrored BOTH as separate versions.
        assertEquals(2, catalogVersionCount())
    }

    // ------------------------------------------------------------------
    // 2. Repeated identical writes remain idempotent no-ops.
    // ------------------------------------------------------------------

    @Test
    fun `repeated identical writes are verified no-ops`() = runBlocking {
        management.registerUserConfiguredPricing(request(input = 1_500L))
        repeat(3) {
            val repeated = management.registerUserConfiguredPricing(request(input = 1_500L))
            assertTrue(repeated is PricingManagementService.RegisterOutcome.Unchanged)
        }
        assertEquals(1, entryCount())
        assertEquals(1, catalogVersionCount())
    }

    // ------------------------------------------------------------------
    // 3. Two versions with increasing effective times resolve in order.
    // ------------------------------------------------------------------

    @Test
    fun `distinct versions with increasing effective times resolve in order`() = runBlocking {
        management.registerUserConfiguredPricing(request(input = 1_500L, from = 1_000L))
        management.registerUserConfiguredPricing(request(input = 2_500L, from = 5_000L))

        assertEquals(1_500L, management.resolveAt("prov", "svc", "m1", 3_000L)!!
            .components.single { it.componentCode == "INPUT_TOKENS" }.rateAmountPrecise!!.toLong())
        assertEquals(2_500L, management.resolveAt("prov", "svc", "m1", 7_000L)!!
            .components.single { it.componentCode == "INPUT_TOKENS" }.rateAmountPrecise!!.toLong())
    }

    // ------------------------------------------------------------------
    // 4. A simulated catalog-sync failure AFTER the entry was persisted:
    //    the entry STANDS, the failure is reported honestly.
    // ------------------------------------------------------------------

    @Test
    fun `catalog sync failure after persistence is a reported partial failure`() = runBlocking {
        failSync = true
        val outcome = management.registerUserConfiguredPricing(request(input = 1_500L))

        assertTrue(outcome is PricingManagementService.RegisterOutcome.Registered)
        val registered = outcome as PricingManagementService.RegisterOutcome.Registered
        assertNull(registered.catalogSync)
        assertEquals("simulated catalog outage", registered.catalogSyncFailure)
        // The pricing entry WAS persisted (the write is not lost).
        assertEquals(1, entryCount())
        assertEquals(1_500L, economicStore.allPricing().single().inputPricePerMillion!!.amountMicro)
        // The catalog mirror is stale (the partial-failure state).
        assertEquals(0, catalogVersionCount())
        failSync = false
    }

    // ------------------------------------------------------------------
    // 5. Retry after partial failure: the recovery path catches the catalog
    //    up; historical resolution stays stable.
    // ------------------------------------------------------------------

    @Test
    fun `retry after partial failure heals the catalog without losing history`() = runBlocking {
        // (a) The partial-failure state: entry persisted, catalog stale.
        failSync = true
        management.registerUserConfiguredPricing(request(input = 1_500L))
        assertEquals(1, entryCount())
        assertEquals(0, catalogVersionCount())
        failSync = false

        // (b) A LATER SAVE of a materially different price heals EVERYTHING
        //     (the mirror processes every unrepresented entry).
        management.registerUserConfiguredPricing(request(input = 2_000L))
        assertEquals(2, entryCount())
        assertEquals(2, catalogVersionCount())

        // (c) The EXPLICIT recovery action also heals a stale mirror.
        failSync = true
        management.registerUserConfiguredPricing(request(input = 3_000L))
        failSync = false
        assertEquals(3, entryCount())
        assertEquals(2, catalogVersionCount()) // still stale
        val healed = management.resyncPricingCatalog()
        assertEquals(1, healed.versionsCreated)
        assertEquals(3, catalogVersionCount())

        // Historical resolution is stable across the whole chain.
        assertNotNull(management.resolveAt("prov", "svc", "m1", tick))
    }

    // ------------------------------------------------------------------
    // 6. Historical lookup before and after a new price version stays
    //    reproducible (append-only version chain).
    // ------------------------------------------------------------------

    @Test
    fun `historical lookups stay reproducible after new price versions`() = runBlocking {
        management.registerUserConfiguredPricing(request(input = 1_500L, from = 1_000L, until = 5_000L))
        val insideBefore = management.resolveAt("prov", "svc", "m1", 3_000L)
        assertEquals(1_500L, insideBefore!!.components.single { it.componentCode == "INPUT_TOKENS" }.rateAmountPrecise!!.toLong())
        val versionIdBefore = insideBefore.version.pricingProfileVersionId

        management.registerUserConfiguredPricing(request(input = 9_900L, from = 5_000L))

        // The same instant resolves to the SAME version, unchanged.
        val insideAfter = management.resolveAt("prov", "svc", "m1", 3_000L)
        assertEquals(versionIdBefore, insideAfter!!.version.pricingProfileVersionId)
        assertEquals(1_500L, insideAfter.components.single { it.componentCode == "INPUT_TOKENS" }.rateAmountPrecise!!.toLong())
        // The new window resolves to the new version.
        assertEquals(9_900L, management.resolveAt("prov", "svc", "m1", 6_000L)!!
            .components.single { it.componentCode == "INPUT_TOKENS" }.rateAmountPrecise!!.toLong())
        // Before any window: honestly UNKNOWN.
        assertNull(management.resolveAt("prov", "svc", "m1", 500L))
    }

    // ------------------------------------------------------------------
    // 7. Concurrent registration of the same identity — exactly-once rows.
    // ------------------------------------------------------------------

    @Test
    fun `concurrent registration of the same identity posts exactly once`() = runBlocking {
        val outcomes = kotlinx.coroutines.coroutineScope {
            (1..8).map {
                async(kotlinx.coroutines.Dispatchers.IO) {
                    runCatching { management.registerUserConfiguredPricing(request(input = 1_500L)) }
                }
            }.map { it.await() }
        }
        assertTrue(outcomes.all { it.isSuccess })

        // ONE entry, ONE catalog version — the upsert idempotency and the
        // catalog's pricing-source skip keep it exactly-once.
        assertEquals(1, entryCount())
        assertEquals(1, catalogVersionCount())
    }

    // ------------------------------------------------------------------
    // WS-D — provenance vs verification honesty.
    // ------------------------------------------------------------------

    @Test
    fun `user-config pricing stays UNVERIFIED in the catalog - never promoted to VERIFIED`() = runBlocking {
        management.registerUserConfiguredPricing(request(input = 1_500L, evidence = "https://prov.example/pricing"))

        val resolved = management.resolveAt("prov", "svc", "m1", tick)!!
        assertEquals("UNVERIFIED", resolved.version.verificationStatus)
        // The provenance chain records the user-entered source + the evidence
        // reference (user-attested accuracy — NOT independent verification).
        assertTrue(resolved.version.authority.startsWith("USER_CONFIG"))
        assertTrue(resolved.version.authority.contains("ref=https://prov.example/pricing"))

        val entry = economicStore.allPricing().single()
        assertEquals("USER_CONFIG|ref=https://prov.example/pricing", entry.provenance)
        // No code path flips USER_CONFIG to a verified stamp: the only
        // verification constant the catalog writes is UNVERIFIED.
        assertEquals("UNVERIFIED", PricingCatalogService.VERIFICATION_UNVERIFIED)
    }

    @Test
    fun `the identity probe surfaces unknown providers honestly - never silent validity`() = runBlocking {
        var probed: Pair<String, String?>? = null
        val probedManagement = PricingManagementService(
            pricingRepository = economicStore,
            pricingCatalog = catalog,
            clock = { tick },
            identityProbe = { providerId, serviceId ->
                probed = providerId to serviceId
                if (providerId == "known") PricingManagementService.PricingIdentityStatus.PROVIDER_AND_SERVICE_FOUND
                else PricingManagementService.PricingIdentityStatus.PROVIDER_UNKNOWN
            }
        )

        val known = probedManagement.registerUserConfiguredPricing(
            request(input = 1_500L).copy(providerId = "known")
        )
        assertTrue((known as PricingManagementService.RegisterOutcome.Registered).identityStatus ==
            PricingManagementService.PricingIdentityStatus.PROVIDER_AND_SERVICE_FOUND)

        val unknown = probedManagement.registerUserConfiguredPricing(
            request(input = 1_600L).copy(providerId = "ghost")
        )
        // The write is possible (pre-registration) but NEVER silently valid.
        assertTrue((unknown as PricingManagementService.RegisterOutcome.Registered).identityStatus ==
            PricingManagementService.PricingIdentityStatus.PROVIDER_UNKNOWN)
        assertEquals("ghost" to "svc", probed)

        // Without a probe the outcome says validation did not run.
        val noProbe = management.registerUserConfiguredPricing(request(input = 1_700L))
        assertTrue((noProbe as PricingManagementService.RegisterOutcome.Registered).identityStatus ==
            PricingManagementService.PricingIdentityStatus.VALIDATION_UNAVAILABLE)
    }

    @Test
    fun `a note-only change is a documented no-op - material equality is prices currency class window`() = runBlocking {
        management.registerUserConfiguredPricing(request(input = 1_500L, note = "first"))
        // Same MATERIAL content (prices/currency/class/window) with a
        // different note → the Stage-2 documented no-op (the note is not a
        // material field; repeated saves never spam versions).
        val edited = management.registerUserConfiguredPricing(request(input = 1_500L, note = "second"))
        assertTrue(edited is PricingManagementService.RegisterOutcome.Unchanged)
        assertEquals(1, entryCount())
        assertEquals(1, catalogVersionCount())
    }
}
