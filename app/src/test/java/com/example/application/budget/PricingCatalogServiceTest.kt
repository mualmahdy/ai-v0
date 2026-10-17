package com.example.application.budget

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.example.domain.core.budget.PricingScope
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

/**
 * ============================================================================
 * STAGE 2 — PricingCatalogServiceTest
 * ============================================================================
 * The pricing catalog's two contracts:
 *  1. RESOLUTION reproduces the engine's documented precedence
 *     (MODEL > SERVICE > PROVIDER; window-filtered; most recent
 *     effective_from wins) — the parity the roadmap Phase-2 exit
 *     criterion is tested against;
 *  2. SYNC is an idempotent mirror of the legacy pricing_entries surface
 *     (I-13): same rows -> no new versions; a materially edited entry ->
 *     the next version of its profile (corrections are version rows,
 *     never UPDATEs).
 */
@RunWith(RobolectricTestRunner::class)
class PricingCatalogServiceTest {

    private lateinit var db: AppDatabase
    private lateinit var catalog: PricingCatalogService

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
    }

    @After
    fun tearDown() {
        db.close()
    }

    private fun entry(
        id: String, scope: PricingScope, providerId: String,
        modelId: String? = null, serviceId: String? = null,
        inputPrice: Long? = 1500L, outputPrice: Long? = 3000L, cachedPrice: Long? = null,
        fromMs: Long = 0L, toMs: Long? = null, createdAtMs: Long = 0L
    ): PricingEntryEntity = PricingEntryEntity(
        id = id, scopeType = scope.name, providerId = providerId,
        serviceId = serviceId, modelId = modelId,
        inputPriceMicroPerMillion = inputPrice, outputPriceMicroPerMillion = outputPrice,
        cachedInputPriceMicroPerMillion = cachedPrice,
        currency = "USD", billingClass = "CLOUD", pricingVersion = "v1",
        effectiveFromEpochMs = fromMs, effectiveToEpochMs = toMs,
        provenance = "test", createdAtEpochMs = createdAtMs
    )

    private suspend fun seed(vararg entries: PricingEntryEntity) {
        entries.forEach { db.pricingEntryDao().upsert(it) }
    }

    private fun componentsOf(versionId: String) = runBlocking {
        db.pricingComponentDao().forVersion(versionId)
    }

    @Test
    fun `sync mirrors legacy entries then stays idempotent`() = runBlocking {
        seed(
            entry("e1", PricingScope.MODEL, "prov", modelId = "m1"),
            entry("e2", PricingScope.PROVIDER, "prov")
        )
        val first = catalog.syncFromLegacyEntries()
        assertEquals(2, first.profilesCreated)
        assertEquals(2, first.versionsCreated)
        assertEquals(4, first.componentsCreated) // 2 (model) + 2 (provider)

        val second = catalog.syncFromLegacyEntries()
        assertEquals("same entries must not create anything", 0, second.versionsCreated)

        val third = catalog.syncFromLegacyEntries()
        assertEquals(0, third.versionsCreated)
    }

    @Test
    fun `an edited legacy entry lands as the next profile version`() = runBlocking {
        seed(entry("e1", PricingScope.MODEL, "prov", modelId = "m1", inputPrice = 1500))
        catalog.syncFromLegacyEntries()

        // a material edit = a NEW legacy row (new id + new effectiveFrom)
        seed(entry("e1-v2", PricingScope.MODEL, "prov", modelId = "m1", inputPrice = 2000, fromMs = 500))
        val res = catalog.syncFromLegacyEntries()

        assertEquals(1, res.versionsCreated)
        val profile = db.pricingProfileDao().byProviderAndCode("prov", "MODEL:m1:-")
        assertNotNull(profile)
        val versions = db.pricingProfileVersionDao().forProfile(profile!!.pricingProfileId)
        assertEquals(2, versions.size)
        assertEquals(2, versions[1].versionNumber)
        assertEquals("2000", componentsOf(versions[1].pricingProfileVersionId)
            .first { it.componentCode == "INPUT_TOKENS" }.rateAmountPrecise)
    }

    @Test
    fun `resolution precedence is MODEL then SERVICE then PROVIDER`() = runBlocking {
        seed(
            entry("p", PricingScope.PROVIDER, "prov", inputPrice = 9000),
            entry("s", PricingScope.SERVICE, "prov", serviceId = "svc", inputPrice = 5000),
            entry("m", PricingScope.MODEL, "prov", modelId = "m1", inputPrice = 1500)
        )
        catalog.syncFromLegacyEntries()

        val withAll = catalog.resolvePricingVersion("prov", "svc", "m1", now)
        assertNotNull(withAll)
        assertEquals("MODEL:m1:-", withAll!!.profile.profileCode)
        assertEquals("1500", withAll.components.first { it.componentCode == "INPUT_TOKENS" }.rateAmountPrecise)

        val noModel = catalog.resolvePricingVersion("prov", "svc", null, now)
        assertNotNull(noModel)
        assertEquals("SERVICE:-:svc", noModel!!.profile.profileCode)

        val noService = catalog.resolvePricingVersion("prov", null, null, now)
        assertNotNull(noService)
        assertEquals("PROVIDER:-:-", noService!!.profile.profileCode)

        assertNull("unknown provider resolves to nothing (honest UNKNOWN)",
            catalog.resolvePricingVersion("other", null, null, now))
    }

    @Test
    fun `resolution honors effective windows`() = runBlocking {
        seed(
            entry("old", PricingScope.MODEL, "prov", modelId = "m1", inputPrice = 1111,
                fromMs = 0, toMs = 100),
            entry("new", PricingScope.MODEL, "prov", modelId = "m1", inputPrice = 2222,
                fromMs = 100, toMs = null)
        )
        catalog.syncFromLegacyEntries()

        val at50 = catalog.resolvePricingVersion("prov", null, "m1", 50)
        assertEquals("1111", at50!!.components.first { it.componentCode == "INPUT_TOKENS" }.rateAmountPrecise)

        val at100 = catalog.resolvePricingVersion("prov", null, "m1", 100)
        assertEquals("2222", at100!!.components.first { it.componentCode == "INPUT_TOKENS" }.rateAmountPrecise)

        // a model with no pricing at all resolves to nothing — honest
        // UNKNOWN, never a fabricated price
        assertNull(catalog.resolvePricingVersion("prov", null, "m9", 50))
    }

    @Test
    fun `price-less entries mirror as NOT_CALCULABLE with no components`() = runBlocking {
        seed(entry("np", PricingScope.MODEL, "prov", modelId = "m1",
            inputPrice = null, outputPrice = null, cachedPrice = null))
        val res = catalog.syncFromLegacyEntries()
        assertEquals(1, res.versionsCreated)
        assertEquals(0, res.componentsCreated)

        val resolved = catalog.resolvePricingVersion("prov", null, "m1", now)
        assertNotNull(resolved)
        assertEquals(PricingCatalogService.STATUS_NOT_CALCULABLE, resolved!!.version.pricingStatus)
        assertTrue(resolved.components.isEmpty())
    }

    @Test
    fun `ensureSyncedAndResolve self-heals when the catalog is empty`() = runBlocking {
        seed(entry("e1", PricingScope.MODEL, "prov", modelId = "m1"))
        // no explicit sync — the resolver mirrors on demand (I-13)
        val resolved = catalog.ensureSyncedAndResolve("prov", null, "m1", now)
        assertNotNull(resolved)
        assertEquals("1500", resolved!!.components.first { it.componentCode == "INPUT_TOKENS" }.rateAmountPrecise)
    }
}
