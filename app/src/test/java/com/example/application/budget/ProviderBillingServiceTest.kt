package com.example.application.budget

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.example.infrastructure.persistence.AppDatabase
import com.example.infrastructure.persistence.BudgetCoreMigrationSql
import com.example.infrastructure.persistence.Stage2MigrationSql
import com.example.infrastructure.persistence.Stage3MigrationSql
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
 * STAGE 3 — ProviderBillingService (the invoice import path, roadmap
 * Phase 3: "مسار استيراد الفواتير")
 * ============================================================================
 * I-14 external-id dedup, amount-consistency honesty (I-27), the
 * adjusts_billing_record_id correction chain and DB-level immutability.
 */
@RunWith(RobolectricTestRunner::class)
class ProviderBillingServiceTest {

    private lateinit var db: AppDatabase
    private lateinit var billing: ProviderBillingService

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
        billing = ProviderBillingService(database = db, clock = { 0L })
    }

    @After
    fun tearDown() {
        db.close()
    }

    private fun spec(
        externalId: String? = "INV-1",
        gross: Long? = 1000L,
        net: Long? = 800L,
        tax: Long? = 300L,
        credit: Long? = 100L,
        adjustment: Long? = 0L // present-and-zero keeps the consistency check strict (all five components)
    ) = ProviderBillingService.ImportSpec(
        providerId = "prov-b",
        externalBillingId = externalId,
        billingRecordType = ProviderBillingService.TYPE_INVOICE,
        recordGranularity = ProviderBillingService.GRANULARITY_MONTHLY,
        periodStartEpochMs = 0L,
        periodEndEpochMs = 10_000L,
        primaryAmountType = ProviderBillingService.PRIMARY_GROSS_PAYABLE,
        primaryAmountMicroUnits = gross,
        grossPayableMicroUnits = gross,
        netBeforeTaxMicroUnits = net,
        taxAmountMicroUnits = tax,
        creditAmountMicroUnits = credit,
        adjustmentAmountMicroUnits = adjustment,
        currency = "USD",
        sourceAuthority = "test"
    )

    @Test
    fun `import is idempotent per external id`() = runBlocking {
        val first = billing.import(spec())
        assertTrue(first is ProviderBillingService.ImportResult.Imported)
        val second = billing.import(spec())
        assertTrue("same external id replays, never duplicates", second is ProviderBillingService.ImportResult.Replayed)
        assertEquals(
            (first as ProviderBillingService.ImportResult.Imported).recordId,
            (second as ProviderBillingService.ImportResult.Replayed).recordId
        )
        val rows = db.openHelper.writableDatabase.query("SELECT COUNT(*) FROM provider_billing_record")
        rows.use { c -> c.moveToFirst(); assertEquals(1, c.getInt(0)) }
    }

    @Test
    fun `amount consistency - gross = net + tax - credit + adjustment, else honest INCONSISTENT`() = runBlocking {
        // 800 + 300 - 100 + 0 = 1000 == gross -> CONSISTENT
        val consistent = billing.import(spec()) as ProviderBillingService.ImportResult.Imported
        assertEquals(
            ProviderBillingService.CONSISTENCY_CONSISTENT,
            billing.byId(consistent.recordId)!!.amountConsistencyStatus
        )

        // 800 + 300 - 100 + 0 = 1000 != 900 -> INCONSISTENT (never "fixed")
        val inconsistent = billing.import(spec(externalId = "INV-2", gross = 900L))
                as ProviderBillingService.ImportResult.Imported
        assertEquals(
            ProviderBillingService.CONSISTENCY_INCONSISTENT,
            billing.byId(inconsistent.recordId)!!.amountConsistencyStatus
        )

        // partial breakdown -> UNKNOWN (honest absence)
        val unknown = billing.import(spec(externalId = "INV-3", net = null))
                as ProviderBillingService.ImportResult.Imported
        assertEquals(
            ProviderBillingService.CONSISTENCY_UNKNOWN,
            billing.byId(unknown.recordId)!!.amountConsistencyStatus
        )
    }

    @Test
    fun `corrections chain via adjusts_billing_record_id, never UPDATE`() = runBlocking {
        val original = billing.import(spec()) as ProviderBillingService.ImportResult.Imported
        val correction = billing.import(
            spec(externalId = "INV-1-FIX", gross = 1100L, net = 900L, tax = 300L).copy(
                billingRecordType = ProviderBillingService.TYPE_ADJUSTMENT,
                adjustsBillingRecordId = original.recordId
            )
        ) as ProviderBillingService.ImportResult.Imported
        assertEquals(original.recordId, billing.byId(correction.recordId)!!.adjustsBillingRecordId)

        // both rows survive; the original was never touched
        try {
            db.openHelper.writableDatabase.execSQL("UPDATE provider_billing_record SET billing_status='VOID'")
            assertTrue("billing UPDATE must be rejected", false)
        } catch (expected: android.database.SQLException) {
            // trigger fired — correct
        }
    }

    @Test
    fun `records without external ids never dedup - each import is a distinct record`() = runBlocking {
        billing.import(spec(externalId = null, gross = 500L))
        billing.import(spec(externalId = null, gross = 500L))
        val rows = db.openHelper.writableDatabase.query("SELECT COUNT(*) FROM provider_billing_record")
        rows.use { c -> c.moveToFirst(); assertEquals(2, c.getInt(0)) }
    }
}
