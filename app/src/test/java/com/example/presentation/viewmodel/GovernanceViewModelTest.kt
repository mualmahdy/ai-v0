package com.example.presentation.viewmodel

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.example.application.budget.EconomicGovernanceService
import com.example.application.governed.HumanApprovalGate
import com.example.application.radar.CapabilityRadarService
import com.example.application.security.PermissionGrantService
import com.example.application.testing.FakeBudgetAllocationRepository
import com.example.application.testing.FakeCostLedger
import com.example.application.testing.FakePricingRepository
import com.example.application.testing.FakeRadarPersistence
import com.example.application.usecases.ManageWorkspaceBudgetUseCase
import com.example.application.workspace.WorkspaceRuntimeService
import com.example.domain.core.budget.BillingClass
import com.example.domain.core.budget.CostStatus
import com.example.domain.core.budget.MoneyAmount
import com.example.domain.core.budget.TokenUsageRecord
import com.example.domain.core.budget.UsageCostRecord
import com.example.domain.core.capability.CapabilityType
import com.example.domain.core.network.NetworkPolicy
import com.example.domain.core.observability.AuditEvent
import com.example.domain.core.observability.DimensionSummary
import com.example.domain.core.observability.ExecutionTraceNode
import com.example.domain.core.observability.MeasurementHealth
import com.example.domain.core.observability.MetricSample
import com.example.domain.core.observability.MetricSnapshot
import com.example.domain.core.observability.MetricType
import com.example.domain.core.provider.HealthStatus
import com.example.domain.core.radar.CapabilityEvaluationDimensions
import com.example.domain.core.radar.CapabilityHealth
import com.example.domain.core.radar.CapabilityTrend
import com.example.domain.core.radar.OperationalCapabilityState
import com.example.domain.core.radar.RadarCapabilityStatus
import com.example.domain.core.radar.RadarRecommendation
import com.example.domain.core.radar.RadarRecommendationType
import com.example.domain.core.radar.RecommendationPriority
import com.example.domain.core.resource.ResourceId
import com.example.domain.core.resource.ResourceLifecycleState
import com.example.domain.core.resource.ResourceRecord
import com.example.domain.core.resource.ResourceType
import com.example.domain.core.security.governance.Permission
import com.example.domain.core.security.governance.PrincipalType
import com.example.domain.core.security.governance.SecurableResourceType
import com.example.domain.ports.governed.HumanApprovalRequest
import com.example.domain.ports.observability.TelemetryPort
import com.example.infrastructure.governed.RoomHumanApprovalStore
import com.example.infrastructure.persistence.AppDatabase
import com.example.infrastructure.persistence.entities.WorkspaceEntity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
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
 * GovernanceViewModelTest — GAP-21 (Design Closure 2026) behavioral coverage
 * for the GOVERNANCE feature ViewModel (ADR-6 slice 4)
 * ============================================================================
 *
 * Drives the REAL services the observatory reads — no service-seam stubbing;
 * only DAO/port-level fakes:
 *
 *  - REAL HumanApprovalGate over a REAL RoomHumanApprovalStore
 *    (Room in-memory under Robolectric) — the approval loop
 *    (approve / reject / "allow always") resolves through the same
 *    durable store the production AppContainer wires;
 *  - REAL PermissionGrantService over the same Room database's
 *    permissionGrantDao — the standing EXECUTE grant is verified by
 *    querying the granted rows back through the real service;
 *  - REAL CapabilityRadarService over FakeRadarPersistence with a real
 *    resource snapshot — the observatory's statuses/recommendations flow
 *    from the same derivation the production screen renders;
 *  - REAL EconomicGovernanceService + REAL ManageWorkspaceBudgetUseCase
 *    over the shared governance fakes — the budget editor's save is the
 *    real allocation write, echoed back through the real summary query;
 *  - REAL WorkspaceRuntimeService over the shared workspace fakes.
 *
 * Asserted feature contract (extracted from MainViewModel):
 *  - the approval queue loads reactively and resolves with the CONSTRUCTOR
 *    principal (durable device-local user id — GAP-02);
 *  - "allow always" records a standing EXECUTE grant AND resolves the
 *    current request (ADR-2c), with the honest unavailable error when the
 *    grant service is absent;
 *  - the budget editor saves through the use-case and echoes the formatted
 *    amount; the observatory refresh populates statuses + budget + ledger +
 *    tokens + measurement health from backend truth;
 *  - the refresh is bootstrap-aware: without a ready workspace it surfaces
 *    the honest gate banner (P0-03) instead of fabricating data;
 *  - the session network-policy mirror follows the studio signal bus
 *    (the radar snapshot's input — no shared mutable state);
 *  - the radar observers are flatMapLatest-scoped to the ACTIVE workspace:
 *    a STALE workspace's upsert cannot bleed into the observatory (the
 *    inherited stacked-collector last-writer race is gone);
 *  - the diagnostic banner + error channel are the feature's own state.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class GovernanceViewModelTest {

    private val dispatcher = UnconfinedTestDispatcher()
    private val principal = "gov-test-user"

    private lateinit var db: AppDatabase
    private lateinit var gate: HumanApprovalGate
    private lateinit var permissions: PermissionGrantService
    private lateinit var radarPersistence: FakeRadarPersistence
    private lateinit var radar: CapabilityRadarService
    private lateinit var ledger: FakeCostLedger
    private lateinit var economics: EconomicGovernanceService
    private lateinit var budgetUseCase: ManageWorkspaceBudgetUseCase
    private lateinit var workspaceService: WorkspaceRuntimeService
    private lateinit var signalBus: MutableSharedFlow<StudioSignal>
    private lateinit var viewModel: GovernanceViewModel

    /** Mutable resource registry snapshot the real radar derives from. */
    private val resources = mutableListOf<ResourceRecord>()

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
        val context = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        gate = HumanApprovalGate(store = RoomHumanApprovalStore(db.humanApprovalRequestDao()))
        permissions = PermissionGrantService(
            permissionGrantDao = db.permissionGrantDao(),
            telemetryPort = NoopTelemetryPort(),
            deviceUserPrincipalId = { principal }
        )
        radarPersistence = FakeRadarPersistence()
        radar = CapabilityRadarService(
            persistence = radarPersistence,
            resourceSnapshotProvider = { resources.toList() },
            embeddingSemanticProvisioned = { false },
            scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        )
        ledger = FakeCostLedger()
        economics = EconomicGovernanceService(
            pricingRepository = FakePricingRepository(),
            costLedger = ledger,
            allocationRepository = FakeBudgetAllocationRepository()
        )
        budgetUseCase = ManageWorkspaceBudgetUseCase(economics)
        workspaceService = WorkspaceRuntimeService(
            workspaceDao = FakeWorkspaceDaoForVm(),
            projectDao = null,
            coroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        )
        Thread.sleep(100) // let the default-workspace bootstrap settle
        signalBus = MutableSharedFlow(extraBufferCapacity = 256)
        viewModel = newViewModel()
    }

    @After
    fun tearDown() {
        db.close()
        Dispatchers.resetMain()
    }

    private fun newViewModel(
        grantService: PermissionGrantService? = permissions,
        radarService: CapabilityRadarService? = radar
    ): GovernanceViewModel = GovernanceViewModel(
        workspaceRuntimeService = workspaceService,
        capabilityRadarService = radarService,
        economicGovernanceService = economics,
        humanApprovalGate = gate,
        permissionGrantService = grantService,
        manageWorkspaceBudgetUseCase = budgetUseCase,
        networkMonitorProvider = null, // fail-closed offline — honest derivation
        telemetryPort = NoopTelemetryPort(),
        localPrincipalId = principal,
        studioSignals = signalBus
    )

    /**
     * Documented helper (StudioViewModelTest pattern): the real services hop
     * to Dispatchers.IO internally (Room queries, grant writes), so outcomes
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

    // ------------------------------------------------------------------
    // Approval loop (GAP-02) — the REAL gate + the REAL grant service
    // ------------------------------------------------------------------

    @Test
    fun `pending approvals load from the real gate on init`() = runBlocking {
        val request = gate.requestApproval(
            executionId = "exec-1",
            toolName = "fs_write",
            riskLevel = "HIGH",
            prompt = "كتابة ملف داخل مساحة العمل",
            justification = "اختبار"
        )
        // The queue is a PULL surface (refreshed with the observatory): the
        // seeding goes straight to the durable store, then the refresh
        // loads it — no init-vs-seed race.
        viewModel.refreshGovernance()

        awaitUntil { viewModel.state.value.pendingApprovals.isNotEmpty() }
        assertEquals(1, viewModel.state.value.pendingApprovals.size)
        assertEquals(request.approvalId, viewModel.state.value.pendingApprovals.first().approvalId)
    }

    @Test
    fun `approveSensitiveAction resolves APPROVED with the constructor principal`() = runBlocking {
        val request = gate.requestApproval(
            executionId = "exec-2",
            toolName = "fs_delete",
            riskLevel = "HIGH",
            prompt = "حذف ملف",
            justification = "اختبار"
        )
        viewModel.refreshGovernance()

        viewModel.approveSensitiveAction(request.approvalId)

        awaitUntil { viewModel.state.value.diagnosticBanner != null }
        assertEquals("تمت الموافقة على الإجراء الحساس.", viewModel.state.value.diagnosticBanner)
        awaitUntil { viewModel.state.value.pendingApprovals.isEmpty() }
        val resolved = db.humanApprovalRequestDao().find(request.approvalId)
        assertNotNull(resolved)
        assertEquals("APPROVED", resolved!!.resolution)
        assertEquals(principal, resolved.resolvedBy)
    }

    @Test
    fun `rejectSensitiveAction resolves REJECTED with honest banner`() = runBlocking {
        val request = gate.requestApproval(
            executionId = "exec-3",
            toolName = "net_fetch",
            riskLevel = "CRITICAL",
            prompt = "استدعاء شبكة خارجية",
            justification = "اختبار"
        )
        viewModel.refreshGovernance()

        viewModel.rejectSensitiveAction(request.approvalId)

        awaitUntil { viewModel.state.value.diagnosticBanner != null }
        assertEquals("تم رفض الإجراء الحساس.", viewModel.state.value.diagnosticBanner)
        awaitUntil { viewModel.state.value.pendingApprovals.isEmpty() }
        val resolved = db.humanApprovalRequestDao().find(request.approvalId)
        assertNotNull(resolved)
        assertEquals("REJECTED", resolved!!.resolution)
        assertEquals(principal, resolved.resolvedBy)
    }

    @Test
    fun `grantAlwaysForApproval records a standing EXECUTE grant and resolves the request`() = runBlocking {
        val request = gate.requestApproval(
            executionId = "exec-4",
            toolName = "shell_exec",
            riskLevel = "CRITICAL",
            prompt = "تنفيذ أمر حساس",
            justification = "اختبار"
        )
        viewModel.refreshGovernance()

        viewModel.grantAlwaysForApproval(request.approvalId)

        awaitUntil { viewModel.state.value.diagnosticBanner != null }
        assertEquals(
            "تم السماح دائماً بهذه الأداة (منح EXECUTE دائم).",
            viewModel.state.value.diagnosticBanner
        )
        awaitUntil { viewModel.state.value.pendingApprovals.isEmpty() }
        // The standing grant is REAL: query it back through the service.
        awaitUntil {
            runBlocking {
                permissions.check(
                    principalType = PrincipalType.USER,
                    principalId = principal,
                    resourceType = SecurableResourceType.TOOL,
                    resourceId = "shell_exec",
                    permission = Permission.EXECUTE
                )
            }
        }
        // And the CURRENT request is resolved so the in-flight execution can proceed.
        val resolved = db.humanApprovalRequestDao().find(request.approvalId)
        assertNotNull(resolved)
        assertEquals("APPROVED", resolved!!.resolution)
    }

    @Test
    fun `grantAlwaysForApproval surfaces the honest unavailable error without the grant service`() {
        val vmWithoutGrants = newViewModel(grantService = null)
        val request = runBlocking {
            gate.requestApproval(
                executionId = "exec-5",
                toolName = "fs_read",
                riskLevel = "MEDIUM",
                prompt = "قراءة ملف",
                justification = "اختبار"
            )
        }

        vmWithoutGrants.grantAlwaysForApproval(request.approvalId)

        awaitUntil { vmWithoutGrants.state.value.errorMessage != null }
        assertEquals("خدمة منح الأذونات غير متاحة.", vmWithoutGrants.state.value.errorMessage)
    }

    @Test
    fun `listPendingApprovals delivers the real queue to the callback`() {
        runBlocking {
            gate.requestApproval(
                executionId = "exec-6",
                toolName = "fs_write",
                riskLevel = "HIGH",
                prompt = "كتابة",
                justification = "اختبار"
            )
        }
        var delivered: List<HumanApprovalRequest>? = null
        viewModel.listPendingApprovals { delivered = it }

        awaitUntil { delivered != null }
        assertTrue(delivered!!.any { it.toolName == "fs_write" })
    }

    // ------------------------------------------------------------------
    // Budget editor (real use-case + real economics service)
    // ------------------------------------------------------------------

    @Test
    fun `setWorkspaceBudgetAllocationUsd saves through the real use-case and echoes the formatted amount`() = runBlocking {
        val wsId = workspaceService.requireActiveWorkspaceId()

        viewModel.setWorkspaceBudgetAllocationUsd(5.0)

        awaitUntil { viewModel.state.value.workspaceBudgetStatus?.allocation != null }
        assertFalse(viewModel.state.value.isSavingBudgetAllocation)
        assertEquals("5.00", viewModel.state.value.budgetAllocationInputUsd)
        val allocation = viewModel.state.value.workspaceBudgetStatus!!.allocation!!
        assertEquals(5_000_000L, allocation.allocated.amountMicro)
        assertEquals("USD", allocation.allocated.currency)
        assertEquals(wsId, allocation.scope.scopeId)
    }

    // ------------------------------------------------------------------
    // Observatory refresh (real radar + real economics + honest gates)
    // ------------------------------------------------------------------

    @Test
    fun `refreshGovernance populates the observatory from real backend truth`() = runBlocking {
        val wsId = workspaceService.requireActiveWorkspaceId()
        resources += llmResource()
        ledger.insert(
            UsageCostRecord(
                id = "rec-1",
                executionId = "exec-x",
                taskId = null,
                workspaceId = wsId,
                agentId = null,
                providerId = "prov",
                serviceId = "svc",
                modelId = null,
                resourceId = "res:prov:svc:llm",
                usage = TokenUsageRecord(inputTokens = 80, outputTokens = 40),
                appliedPricing = null,
                cost = MoneyAmount.of(120L, "USD"),
                costStatus = CostStatus.ESTIMATED,
                billingClass = BillingClass.PAID,
                timestampEpochMs = System.currentTimeMillis()
            )
        )

        viewModel.refreshGovernance()

        awaitUntil { viewModel.state.value.workspaceTokensConsumed == 120L }
        assertTrue(viewModel.state.value.radarCapabilityStatuses.isNotEmpty())
        assertNotNull(viewModel.state.value.workspaceBudgetStatus)
        assertEquals(1, viewModel.state.value.costLedgerRecent.size)
        assertNotNull(viewModel.state.value.measurementHealth)
    }

    @Test
    fun `refreshGovernance without a ready workspace surfaces the honest gate banner`() {
        // A service whose bootstrap FAILS (the DAO explodes on insert) keeps
        // the active-workspace flow null — the P0-03 honest gate path.
        val brokenService = WorkspaceRuntimeService(
            workspaceDao = ExplodingWorkspaceDaoForGov(),
            projectDao = null,
            coroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        )
        val vm = GovernanceViewModel(
            workspaceRuntimeService = brokenService,
            capabilityRadarService = null,
            economicGovernanceService = null,
            humanApprovalGate = null,
            permissionGrantService = null,
            manageWorkspaceBudgetUseCase = null,
            networkMonitorProvider = null,
            telemetryPort = null,
            localPrincipalId = principal,
            studioSignals = null
        )

        // awaitActiveWorkspaceId waits a bounded 5s (virtual time on the
        // test scheduler) before honestly returning null.
        dispatcher.scheduler.advanceTimeBy(5_100L)
        dispatcher.scheduler.runCurrent()

        awaitUntil { vm.state.value.diagnosticBanner != null }
        assertTrue(
            vm.state.value.diagnosticBanner!!.contains("مساحة العمل لم تجهز بعد")
        )
        // And NOTHING was fabricated while gated:
        assertTrue(vm.state.value.radarCapabilityStatuses.isEmpty())
        assertNull(vm.state.value.workspaceBudgetStatus)
    }

    // ------------------------------------------------------------------
    // Studio signal bus — the session policy display mirror
    // ------------------------------------------------------------------

    @Test
    fun `the session policy mirror follows the studio signal bus`() {
        assertEquals(NetworkPolicy.HYBRID, viewModel.state.value.networkPolicy)

        signalBus.tryEmit(StudioSignal.NetworkPolicyChanged(policy = NetworkPolicy.OFFLINE))

        awaitUntil { viewModel.state.value.networkPolicy == NetworkPolicy.OFFLINE }
        signalBus.tryEmit(StudioSignal.NetworkPolicyChanged(policy = NetworkPolicy.CLOUD_FIRST))
        awaitUntil { viewModel.state.value.networkPolicy == NetworkPolicy.CLOUD_FIRST }
    }

    // ------------------------------------------------------------------
    // Radar observation — flatMapLatest workspace re-scope
    // ------------------------------------------------------------------

    @Test
    fun `workspace re-scope replaces the radar collector - a stale workspace upsert cannot bleed in`() = runBlocking {
        val wsA = workspaceService.createWorkspace("مساحة أ", "اختبار")
        radarPersistence.upsertCapabilityStatus(capabilityStatus("cap_a", wsA.id))
        awaitUntil { viewModel.state.value.radarCapabilityStatuses.any { it.capabilityKey == "cap_a" } }

        val wsB = workspaceService.createWorkspace("مساحة ب", "اختبار")
        workspaceService.switchWorkspace(wsB.id)
        radarPersistence.upsertCapabilityStatus(capabilityStatus("cap_b", wsB.id))
        awaitUntil { viewModel.state.value.radarCapabilityStatuses.any { it.capabilityKey == "cap_b" } }

        // A STALE-workspace write: with the inherited stacked collectors the
        // old wsA observer would still be alive and could overwrite the
        // observatory with the PREVIOUS workspace's data (last-writer race).
        radarPersistence.upsertCapabilityStatus(capabilityStatus("cap_a2", wsA.id))
        Thread.sleep(150) // give any stale collector a real chance to bleed

        val keys = viewModel.state.value.radarCapabilityStatuses.map { it.capabilityKey }
        assertTrue(keys.contains("cap_b"))
        assertFalse(keys.contains("cap_a2"))
    }

    @Test
    fun `dismissRadarRecommendation persists through the real radar service`() = runBlocking {
        val wsId = workspaceService.requireActiveWorkspaceId()
        radarPersistence.upsertRecommendations(
            listOf(
                RadarRecommendation(
                    id = "reco-1",
                    capabilityKey = CapabilityType.LLM_GENERATION.code,
                    workspaceId = wsId,
                    type = RadarRecommendationType.PROVISION_RESOURCE,
                    priority = RecommendationPriority.HIGH,
                    message = "قم بتجهيز مورد توليد",
                    actionHint = null,
                    supportingEvidenceIds = emptyList(),
                    createdAtEpochMs = System.currentTimeMillis()
                )
            )
        )
        awaitUntil { viewModel.state.value.radarRecommendations.any { it.id == "reco-1" } }

        viewModel.dismissRadarRecommendation("reco-1")

        awaitUntil { viewModel.state.value.radarRecommendations.none { it.id == "reco-1" } }
    }

    // ------------------------------------------------------------------
    // Feature-owned channels
    // ------------------------------------------------------------------

    @Test
    fun `the diagnostic banner dismisses from the feature's own state`() = runBlocking {
        gate.requestApproval(
            executionId = "exec-7",
            toolName = "fs_write",
            riskLevel = "HIGH",
            prompt = "كتابة",
            justification = "اختبار"
        )
        viewModel.refreshGovernance()
        awaitUntil { viewModel.state.value.pendingApprovals.isNotEmpty() }
        viewModel.approveSensitiveAction(viewModel.state.value.pendingApprovals.first().approvalId)
        awaitUntil { viewModel.state.value.diagnosticBanner != null }

        viewModel.dismissDiagnosticBanner()

        assertNull(viewModel.state.value.diagnosticBanner)
    }

    // ------------------------------------------------------------------
    // Helpers
    // ------------------------------------------------------------------

    private fun llmResource(
        lifecycle: ResourceLifecycleState = ResourceLifecycleState.ACTIVE,
        runtimeSupported: Boolean = true,
        health: HealthStatus = HealthStatus.HEALTHY,
        isLocal: Boolean = false
    ) = ResourceRecord(
        resourceId = ResourceId("res:prov:svc:llm"),
        providerId = "prov",
        serviceId = "svc",
        resourceType = ResourceType.LLM,
        capabilities = setOf(CapabilityType.LLM_GENERATION, CapabilityType.REASONING, CapabilityType.STREAMING),
        configurationVersion = 1L,
        lifecycleState = lifecycle,
        runtimeSupported = runtimeSupported,
        healthStatus = health,
        isLocal = isLocal
    )

    private fun capabilityStatus(key: String, workspaceId: String) = RadarCapabilityStatus(
        capabilityKey = key,
        workspaceId = workspaceId,
        state = OperationalCapabilityState.AVAILABLE,
        dimensions = CapabilityEvaluationDimensions(
            declared = true, implemented = true, configured = true,
            provisioned = true, dependencyAvailable = true,
            runtimeAvailable = true, runtimeValidated = true,
            resourceUsable = true, policyAllowed = true,
            healthConfirmed = true, uiExposed = true, evidenceFresh = true
        ),
        health = CapabilityHealth.HEALTHY,
        trend = CapabilityTrend.STABLE,
        evidenceCount = 1,
        lastEvidenceEpochMs = 1L,
        contributingResourceIds = emptyList(),
        rationale = "اختبار",
        derivedAtEpochMs = 1L
    )

    /** The workspace DAO whose insert explodes — bootstrap fails honestly. */
    private class ExplodingWorkspaceDaoForGov : FakeWorkspaceDaoForVm() {
        override suspend fun insertOrUpdate(workspace: WorkspaceEntity) {
            throw IllegalStateException("انفجار مقصود في اختبار البوابة الصادقة")
        }
    }

    /** Minimal no-op TelemetryPort (project convention: hand-rolled fakes). */
    private class NoopTelemetryPort : TelemetryPort {
        override suspend fun record(sample: MetricSample) = Unit
        override suspend fun recordBatch(samples: List<MetricSample>) = Unit
        override suspend fun recordAudit(event: AuditEvent): Long = 0L
        override suspend fun recordTraceNode(node: ExecutionTraceNode) = Unit
        override fun snapshots(): Flow<List<MetricSnapshot>> = flowOf(emptyList())
        override fun dimensionSummaries(): Flow<List<DimensionSummary>> = flowOf(emptyList())
        override fun auditEvents(limit: Int): Flow<List<AuditEvent>> = flowOf(emptyList())
        override fun traceForExecution(executionId: String): Flow<List<ExecutionTraceNode>> = flowOf(emptyList())
        override fun recentTraceNodes(limit: Int): Flow<List<ExecutionTraceNode>> = flowOf(emptyList())
        override suspend fun snapshotByType(type: MetricType): List<MetricSnapshot> = emptyList()
        override suspend fun measurementHealth(): MeasurementHealth = MeasurementHealth()
    }
}
