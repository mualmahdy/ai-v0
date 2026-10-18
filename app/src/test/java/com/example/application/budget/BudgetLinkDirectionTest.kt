package com.example.application.budget

import android.content.Context
import androidx.room.Room
import androidx.sqlite.db.SupportSQLiteDatabase
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
 * STAGE 6 — G07 acceptance: the unidirectional link chain
 * ============================================================================
 * PDM-G07: "اتجاه الربط الأحادي حجز ← تسوية ← قيد — اتجاه صحيح يمنع دورة
 * المفاتيح؛ يؤكد كما هو". The study's verdict is CONFIRM-AS-IS: the physical
 * schema already points one way. This suite turns that confirmation into
 * executable evidence — the acceptance register can only cite a gate as
 * closed when the assertion runs in the verification build:
 *
 *   1. budget_hold         → parents: budget_period ONLY (no back edge)
 *   2. budget_ledger_entry → parents: budget_period, budget_hold, itself
 *                            (reversal) — NEVER budget_hold_settlement
 *   3. budget_hold_settlement → parents: budget_hold + budget_ledger_entry
 *
 * The three PRAGMA reads prove the graph is ACYCLIC in the hold→settlement→
 * entry direction (a cycle would need a hold referencing a settlement or
 * an entry referencing a settlement). RESTRICT then proves the chain is
 * enforced by SQLite itself: a consumed hold cannot be deleted.
 */
@RunWith(RobolectricTestRunner::class)
class BudgetLinkDirectionTest {

    private lateinit var db: AppDatabase
    private lateinit var ledger: BudgetLedgerService
    private var tick = 1_000L

    @Before
    fun setup() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .allowMainThreadQueries()
            .addCallback(object : androidx.room.RoomDatabase.Callback() {
                override fun onCreate(db: SupportSQLiteDatabase) {
                    BudgetCoreMigrationSql.TRIGGERS.forEach(db::execSQL)
                    Stage2MigrationSql.TRIGGERS.forEach(db::execSQL)
                    Stage3MigrationSql.TRIGGERS.forEach(db::execSQL)
                    Stage2MigrationSql.STATEMENTS.filter { it.startsWith("INSERT") }.forEach(db::execSQL)
                }
            })
            .build()
        ledger = BudgetLedgerService(
            database = db,
            idGenerator = { "id-${tick++}" },
            clock = { tick }
        )
    }

    @After
    fun tearDown() {
        db.close()
    }

    /** The PARENT tables of [table], straight from SQLite's own schema. */
    private fun parentTables(table: String): Set<String> =
        db.openHelper.readableDatabase.query("PRAGMA foreign_key_list($table)").use { c ->
            val parents = mutableSetOf<String>()
            while (c.moveToNext()) parents.add(c.getString(2))
            parents
        }

    @Test
    fun `G07 - the hold table has NO foreign key into settlements or entries (no back edge)`() {
        val parents = parentTables("budget_hold")
        assertEquals(
            "a hold references its period ONLY — a back edge into the settlement/entry side would create a key cycle",
            setOf("budget_period"),
            parents
        )
    }

    @Test
    fun `G07 - the entry table points to period, hold and itself only - never to a settlement`() {
        val parents = parentTables("budget_ledger_entry")
        assertEquals(
            "entries may reference their period, their hold and their reversal original — nothing downstream",
            setOf("budget_period", "budget_hold", "budget_ledger_entry"),
            parents
        )
    }

    @Test
    fun `G07 - the settlement table is the one-way bridge - hold and entry as its only parents`() {
        val parents = parentTables("budget_hold_settlement")
        assertEquals(
            "a settlement links ONE hold to (at most) ONE entry — the unidirectional chain the PDM requires",
            setOf("budget_hold", "budget_ledger_entry"),
            parents
        )
    }

    @Test
    fun `G07 - RESTRICT - a hold with settlements cannot be deleted, and neither can its settle entry`() = runBlocking {
        val budget = ledger.createBudget(
            BudgetLedgerService.CreateBudgetRequest(
                scopeType = "WORKSPACE", platformObjectType = "WORKSPACE", platformObjectId = "ws-1",
                providerId = null, budgetName = "ws", currency = "USD",
                limitMicroUnits = 1_000_000L,
                periodStartEpochMs = 0L, periodEndEpochMs = 10_000_000L,
                overagePolicy = BudgetLedgerService.OVERAGE_HARD_LIMIT
            )
        )
        val hold = ledger.authorizeAndReserve(
            scopeType = "WORKSPACE", platformObjectId = "ws-1",
            amountMicroUnits = 100_000L, reason = "exec:g7", idempotencyKey = "auth:g7"
        ) as BudgetLedgerService.HoldReservation.Reserved
        val settle = ledger.settleHold(hold.holdId, 40_000L, "settle:g7")
            as BudgetLedgerService.SettlementOutcome.Settled

        // FK enforcement is explicit — the assertion must not depend on the
        // connection's default pragma state.
        db.openHelper.writableDatabase.execSQL("PRAGMA foreign_keys = ON")

        // Deleting the hold while a settlement (and an entry) references it: RESTRICT.
        var holdDeleted = false
        try {
            db.openHelper.writableDatabase.execSQL("DELETE FROM budget_hold WHERE hold_id = ?", arrayOf(hold.holdId))
            holdDeleted = true
        } catch (_: android.database.sqlite.SQLiteConstraintException) {
            // the expected RESTRICT rejection
        }
        assertTrue("SQLite must RESTRICT the deletion of a hold with settlements", !holdDeleted)

        // Deleting the settle's entry while a settlement references it: RESTRICT.
        var entryDeleted = false
        try {
            db.openHelper.writableDatabase.execSQL("DELETE FROM budget_ledger_entry WHERE entry_id = ?", arrayOf(settle.ledgerEntryId))
            entryDeleted = true
        } catch (_: android.database.sqlite.SQLiteConstraintException) {
            // the expected RESTRICT rejection
        }
        assertTrue("SQLite must RESTRICT the deletion of an entry with settlements", !entryDeleted)

        // And the chain itself is intact: exactly one hold, one entry, one SETTLE.
        assertEquals(1, db.budgetHoldDao().forPeriod(budget.periodId).size)
        assertEquals(1, db.budgetLedgerEntryDao().forPeriod(budget.periodId).size)
        assertEquals(1, db.budgetHoldSettlementDao().forHold(hold.holdId).size)
    }
}
