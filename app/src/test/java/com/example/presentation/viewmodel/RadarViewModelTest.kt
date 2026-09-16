package com.example.presentation.viewmodel

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.example.application.radar.IntelligenceRadarPipeline
import com.example.domain.core.evolution.EvolutionStage
import com.example.domain.core.evolution.EvolutionStageTransitions
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * ============================================================================
 * RadarViewModelTest — GAP-21 (Design Closure 2026) behavioral coverage for
 * the RADAR feature ViewModel (ADR-6 slice 6)
 * ============================================================================
 *
 * Drives the REAL 9-stage pipeline the observatory reads — no service-seam
 * stubbing; only the SOURCE list is emptied (zero radar sources = ZERO
 * network: refreshRadarFeed's discovery stage finds nothing to fetch, so
 * every refresh is deterministic under Robolectric) and the persistence is
 * a REAL in-memory Room database (the same DAO pair the production
 * AppContainer wires):
 *
 *  - the two Room-backed collectors (radarItems / evolutionCandidates)
 *    reflect the pipeline's bootstrap truth reactively;
 *  - the refresh writes the GAP-23 spinner flag honestly and settles it;
 *  - the FULL promotion lifecycle runs through the REAL governance gates:
 *    single-step order (GATE_STAGE_ORDER), audit+approval before
 *    INTEGRATED (GATE_GOVERNANCE_APPROVAL — FIX F-10 verdicts surface in
 *    the feature's own error channel), the audit/approval recorders
 *    (GAP-CLOSURE P1-17), and the measure/retire REGISTERED-only gates;
 *  - the diagnostic banner + error channel are the feature's own state.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class RadarViewModelTest {

    private val dispatcher = UnconfinedTestDispatcher()

    private lateinit var db: com.example.infrastructure.persistence.AppDatabase
    private lateinit var pipeline: IntelligenceRadarPipeline
    private lateinit var viewModel: RadarViewModel

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
        val context = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(context, com.example.infrastructure.persistence.AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        pipeline = IntelligenceRadarPipeline(
            // Zero radar sources: NO network — discovery finds nothing, so
            // refreshRadarFeed deterministically keeps the bootstrap items.
            radarSources = emptyList(),
            radarItemDao = db.radarItemDao(),
            evolutionCandidateDao = db.evolutionCandidateDao(),
            measurementRecorder = null,
            coroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        )
        viewModel = RadarViewModel(pipeline)
    }

    @After
    fun tearDown() {
        db.close()
        Dispatchers.resetMain()
    }

    /**
     * Documented helper (GovernanceViewModelTest pattern): the pipeline hops
     * to Dispatchers.IO internally (Room queries, stage gates), so outcomes
     * settle asynchronously even under the Unconfined Main dispatcher.
     */
    private fun awaitUntil(
        timeoutMs: Long = 5_000L,
        intervalMs: Long = 25L,
        condition: () -> Boolean
    ) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (!condition()) {
            if (System.currentTimeMillis() > deadline) {
                throw AssertionError("Condition not met within ${timeoutMs}ms")
            }
            Thread.sleep(intervalMs)
        }
    }

    private fun firstCandidateId(): String =
        pipeline.evolutionCandidates.value.first().id

    // ------------------------------------------------------------------
    // Init collectors — the pipeline's bootstrap truth, reflected reactively
    // ------------------------------------------------------------------

    @Test
    fun `init reflects the pipeline bootstrap items and candidates`() {
        awaitUntil { viewModel.state.value.radarItems.isNotEmpty() }
        awaitUntil { viewModel.state.value.evolutionCandidates.isNotEmpty() }
        assertEquals(pipeline.radarItems.value.size, viewModel.state.value.radarItems.size)
        assertEquals(pipeline.evolutionCandidates.value.size, viewModel.state.value.evolutionCandidates.size)
        // The bootstrap candidates start HONESTLY unapproved (no fabricated
        // audit/governance flags).
        assertTrue(viewModel.state.value.evolutionCandidates.all { !it.securityAuditPassed })
        assertTrue(viewModel.state.value.evolutionCandidates.all { !it.governanceApproved })
    }

    @Test
    fun `refreshRadar settles the spinner honestly with no sources`() {
        viewModel.refreshRadar()
        // Terminal state: the flag is back to false (the GAP-23 writer ran
        // around the REAL pipeline call), the feed survived the refresh.
        awaitUntil { !viewModel.state.value.isRadarRefreshing }
        awaitUntil { viewModel.state.value.radarItems.isNotEmpty() }
        assertFalse(viewModel.state.value.isRadarRefreshing)
    }

    // ------------------------------------------------------------------
    // Promotion lifecycle — the REAL governance gates (FIX F-10 / P1-17)
    // ------------------------------------------------------------------

    @Test
    fun `advanceCandidateStage promotes a single forward step with the honest banner`() {
        awaitUntil { viewModel.state.value.evolutionCandidates.isNotEmpty() }
        val candidateId = firstCandidateId()
        val next = EvolutionStageTransitions.nextOf(EvolutionStage.DISCOVERED)!!

        viewModel.advanceCandidateStage(candidateId, next)

        awaitUntil { viewModel.state.value.diagnosticBanner != null }
        assertEquals("تمت ترقية المرشح إلى ${next.displayName}.", viewModel.state.value.diagnosticBanner)
        awaitUntil {
            viewModel.state.value.evolutionCandidates.firstOrNull { it.id == candidateId }?.stage == next
        }
        // The banner is the feature's own dismissible channel.
        viewModel.dismissDiagnosticBanner()
        assertNull(viewModel.state.value.diagnosticBanner)
    }

    @Test
    fun `advanceCandidateStage surfaces the stage-order gate verdict honestly`() {
        awaitUntil { viewModel.state.value.evolutionCandidates.isNotEmpty() }
        // DISCOVERED → INTEGRATED skips the governance stages: the REAL gate
        // rejects it and the verdict lands in the feature's error channel.
        viewModel.advanceCandidateStage(firstCandidateId(), EvolutionStage.INTEGRATED)

        awaitUntil { viewModel.state.value.errorMessage != null }
        assertTrue(viewModel.state.value.errorMessage!!.contains("يتخطى مراحل الحوكمة"))
        // The candidate was NOT mutated.
        assertEquals(
            EvolutionStage.DISCOVERED,
            viewModel.state.value.evolutionCandidates.first().stage
        )
        viewModel.clearErrorMessage()
        assertNull(viewModel.state.value.errorMessage)
    }

    @Test
    fun `integration requires BOTH security audit and governance approval`() {
        awaitUntil { viewModel.state.value.evolutionCandidates.isNotEmpty() }
        val candidateId = firstCandidateId()

        // Walk the ordered pipeline one honest step at a time:
        // DISCOVERED → … → APPROVAL_PENDING (single steps are allowed).
        var stage: EvolutionStage = EvolutionStage.DISCOVERED
        while (stage != EvolutionStage.APPROVAL_PENDING) {
            val next = EvolutionStageTransitions.nextOf(stage)!!
            viewModel.advanceCandidateStage(candidateId, next)
            awaitUntil {
                viewModel.state.value.evolutionCandidates.firstOrNull { it.id == candidateId }?.stage == next
            }
            stage = next
        }

        // APPROVAL_PENDING → INTEGRATED without the audit + approval: the
        // REAL governance gate rejects it explicitly.
        viewModel.advanceCandidateStage(candidateId, EvolutionStage.INTEGRATED)
        awaitUntil { viewModel.state.value.errorMessage != null }
        assertTrue(viewModel.state.value.errorMessage!!.contains("يجب اجتياز التدقيق الأمني"))

        // Record the approvals through the REAL recorders (P1-17)…
        // (TEST-side race fix, the ProvidersViewModelTest lesson: await the
        // SPECIFIC terminal banner — a bare "!= null" would pass on the
        // previous promotion's banner.)
        viewModel.recordCandidateSecurityAudit(candidateId, passed = true)
        awaitUntil { viewModel.state.value.diagnosticBanner == "نتيجة التدقيق الأمني: ناجح." }
        awaitUntil {
            viewModel.state.value.evolutionCandidates.firstOrNull { it.id == candidateId }?.securityAuditPassed == true
        }

        viewModel.recordCandidateGovernanceApproval(candidateId, approved = true)
        awaitUntil { viewModel.state.value.diagnosticBanner == "موافقة الحوكمة: ممنوحة." }
        awaitUntil {
            viewModel.state.value.evolutionCandidates.firstOrNull { it.id == candidateId }?.governanceApproved == true
        }

        // …now the integration passes the REAL gate.
        viewModel.advanceCandidateStage(candidateId, EvolutionStage.INTEGRATED)
        awaitUntil {
            viewModel.state.value.evolutionCandidates.firstOrNull { it.id == candidateId }?.stage == EvolutionStage.INTEGRATED
        }
    }

    @Test
    fun `recordCandidateSecurityAudit echoes the failure verdict honestly`() {
        awaitUntil { viewModel.state.value.evolutionCandidates.isNotEmpty() }
        val candidateId = firstCandidateId()

        viewModel.recordCandidateSecurityAudit(candidateId, passed = false)

        awaitUntil { viewModel.state.value.diagnosticBanner != null }
        assertEquals("نتيجة التدقيق الأمني: فاشل.", viewModel.state.value.diagnosticBanner)
        awaitUntil {
            viewModel.state.value.evolutionCandidates.firstOrNull { it.id == candidateId }?.securityAuditPassed == false
        }
    }

    @Test
    fun `measureRegisteredCapability gates honestly on the REGISTERED stage`() {
        awaitUntil { viewModel.state.value.evolutionCandidates.isNotEmpty() }
        // A DISCOVERED candidate cannot be measured — the honest gate.
        viewModel.measureRegisteredCapability(firstCandidateId())

        awaitUntil { viewModel.state.value.errorMessage != null }
        assertTrue(viewModel.state.value.errorMessage!!.contains("القياس متاح فقط للقدرات المسجلة"))
    }

    @Test
    fun `retireRegisteredCapability gates honestly on the REGISTERED stage`() {
        awaitUntil { viewModel.state.value.evolutionCandidates.isNotEmpty() }
        viewModel.retireRegisteredCapability(firstCandidateId(), "تقادم")

        awaitUntil { viewModel.state.value.errorMessage != null }
        assertTrue(viewModel.state.value.errorMessage!!.contains("الإحالة للتقاعد متاحة فقط للقدرات المسجلة"))
    }

    @Test
    fun `unknown candidate surfaces the honest error`() {
        awaitUntil { viewModel.state.value.evolutionCandidates.isNotEmpty() }
        viewModel.advanceCandidateStage("evol_unknown", EvolutionStage.UNDERSTOOD)

        awaitUntil { viewModel.state.value.errorMessage != null }
        assertTrue(viewModel.state.value.errorMessage!!.contains("المرشح غير موجود"))
    }
}
