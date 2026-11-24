package com.example.application.budget

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.example.application.decision.DecisionService
import com.example.application.execution.ExecutionService
import com.example.application.observation.ObservationService
import com.example.application.outcome.OutcomeService
import com.example.application.orchestration.AgentOrchestrator
import com.example.application.registry.ComponentRegistry
import com.example.application.security.SecurityGuardService
import com.example.application.testing.TestResourceRegistration
import com.example.domain.core.Outcome
import com.example.domain.core.agent.AgentBudget
import com.example.domain.core.agent.AgentDefinition
import com.example.domain.core.agent.AgentId
import com.example.domain.core.agent.AgentIdentity
import com.example.domain.core.agent.AgentRole
import com.example.domain.core.budget.BillingClass
import com.example.domain.core.budget.MoneyAmount
import com.example.domain.core.budget.PricingEntry
import com.example.domain.core.budget.PricingScope
import com.example.domain.core.capability.CapabilityType
import com.example.domain.core.events.ExecutionEvent
import com.example.domain.core.llm.LlmFailure
import com.example.domain.core.llm.LlmRequest
import com.example.domain.core.llm.LlmResponse
import com.example.domain.core.llm.SafeProviderMetadata
import com.example.domain.core.llm.TokenUsage
import com.example.domain.core.search.SafeSearchProviderMetadata
import com.example.domain.core.search.SearchFailure
import com.example.domain.core.search.SearchQuery
import com.example.domain.core.search.SearchResultItem
import com.example.domain.core.search.SearchResultSet
import com.example.domain.core.task.TaskBudget
import com.example.domain.core.task.TaskDefinition
import com.example.domain.core.task.TaskId
import com.example.domain.core.task.TaskInput
import com.example.domain.core.task.TaskSuccessCriteria
import com.example.domain.core.task.VerificationStrategy
import com.example.domain.ports.llm.LlmProviderPort
import com.example.domain.ports.search.SearchProviderPort
import com.example.infrastructure.persistence.AppDatabase
import com.example.infrastructure.persistence.BudgetCoreMigrationSql
import com.example.infrastructure.persistence.Stage2MigrationSql
import com.example.infrastructure.persistence.Stage3MigrationSql
import com.example.infrastructure.persistence.budget.RoomEconomicStore
import com.example.infrastructure.persistence.dao.ActionIntentDao
import com.example.infrastructure.persistence.entities.ActionIntentEntity
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Before
import java.util.concurrent.atomic.AtomicInteger

/**
 * ============================================================================
 * STAGE 3A — the PRODUCTION-PATH harness (DEBT-5)
 * ============================================================================
 *
 * One deterministic fixture exercising the COMPLETE production chain with
 * REAL components on a REAL (in-memory) Room database with the production
 * DB triggers:
 *
 *   DecisionService → AgentOrchestrator → ExecutionService
 *     → provider invocation boundary (the ONLY test double — a scripted
 *       LlmProviderPort)
 *     → BudgetActualsFeed / EconomicGovernanceService.accountUsageSuspend
 *     → HoldSettlementService → BudgetLedgerService
 *
 * The orchestration and accounting core is NEVER mocked: the orchestrator,
 * decision service, execution service, governance facade, hold gate,
 * settlement service, ledger, policy management and the Room-backed
 * economic store are the PRODUCTION classes over the SAME database, wired
 * exactly like AppContainer wires them. The canonical
 * (executionId, stepIndex, attempt) identity is carried by the chain
 * itself; the workspace identity is pinned by the orchestrator's
 * ExecutionScope (P0-02).
 *
 * Shared by:
 *   - Stage3ADispatchStateAcceptanceTest (DEBT-3),
 *   - Stage3AWorkspaceIdentityParityTest (DEBT-4),
 *   - Stage3AProductionLifecycleAcceptanceTest (DEBT-5).
 */
abstract class EconomicGovernanceProductionHarness {

    protected lateinit var db: AppDatabase
    protected lateinit var ledgerService: BudgetLedgerService
    protected lateinit var holdGate: HoldBasedBudgetGate
    protected lateinit var settlementService: HoldSettlementService
    protected lateinit var economicStore: RoomEconomicStore
    protected lateinit var governance: EconomicGovernanceService
    protected lateinit var policyManagement: BudgetPolicyManagementService
    protected lateinit var registry: ComponentRegistry
    protected lateinit var securityGuard: SecurityGuardService
    protected lateinit var decisionService: DecisionService
    protected lateinit var executionService: ExecutionService
    protected lateinit var orchestrator: AgentOrchestrator
    protected lateinit var fxService: CurrencyConversionService
    protected lateinit var scriptedProvider: ScriptedProvider
    protected lateinit var controllableIntentDao: ControllableActionIntentDao

    /** Fake monotonic clock — every production service sees the SAME tick. */
    protected var tick = 1_000L
    protected var idSeq = 0L
    protected val periodDays = 30L
    protected val periodMs = periodDays * 86_400_000L

    protected val workspaceId = "ws-prod-path"

    protected val agent = AgentDefinition(
        identity = AgentIdentity(
            id = AgentId("prod_agent"),
            name = "Prod Agent",
            role = AgentRole.GENERAL_ASSISTANT,
            description = "Harness agent",
            systemPrompt = "You are a test agent."
        ),
        allowedCapabilities = setOf(CapabilityType.LLM_GENERATION, CapabilityType.REASONING),
        budget = AgentBudget()
    )

    @Before
    open fun setup() {
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
        ledgerService = BudgetLedgerService(
            database = db,
            idGenerator = { "id-${idSeq++}" },
            clock = { tick }
        )
        fxService = CurrencyConversionService(database = db, clock = { tick })
        holdGate = HoldBasedBudgetGate(ledgerService, clock = { tick }, fx = fxService)
        settlementService = HoldSettlementService(
            database = db,
            ledger = ledgerService,
            telemetry = null,
            clock = { tick },
            fx = fxService
        )
        economicStore = RoomEconomicStore(
            pricingDao = db.pricingEntryDao(),
            ledgerDao = db.costLedgerEntryDao(),
            allocationDao = db.budgetAllocationDao()
        )
        governance = EconomicGovernanceService(
            pricingRepository = economicStore,
            costLedger = economicStore,
            allocationRepository = economicStore,
            rateLimitGovernor = RateLimitGovernor(),
            telemetry = null,
            scope = CoroutineScope(SupervisorJob()),
            holdBasedBudgetCore = holdGate,
            holdSettlement = settlementService
        )
        policyManagement = BudgetPolicyManagementService(
            database = db,
            ledger = ledgerService,
            allocationRepository = economicStore,
            clock = { tick },
            idGenerator = { "id-${idSeq++}" }
        )
        registry = ComponentRegistry()
        securityGuard = SecurityGuardService()
        decisionService = DecisionService(
            cbrMdpEngine = com.example.domain.core.decision.CbrMdpEngine(),
            resourceCapabilityGraph = registry.resourceCapabilityGraph,
            securityGuard = securityGuard,
            economicGovernance = governance
        )
        scriptedProvider = ScriptedProvider()
        TestResourceRegistration.registerLlmProvider(registry, scriptedProvider)

        executionService = ExecutionService(
            runtimeAdapterResolver = registry.runtimeAdapterResolver,
            resourceRegistry = registry.resourceRegistry,
            securityGuard = securityGuard,
            memoryRepositoryProvider = { registry.getMemoryRepository() }
        ).apply {
            economicGovernance = this@EconomicGovernanceProductionHarness.governance
            admissionControl = com.example.application.governed.AdmissionControlService.forRegistry(registry)
        }

        controllableIntentDao = ControllableActionIntentDao(db.actionIntentDao())

        orchestrator = AgentOrchestrator(
            registry = registry,
            securityGuard = securityGuard,
            decisionService = decisionService,
            budgetActualsFeed = BudgetActualsFeed(
                ledger = ledgerService,
                entitlements = EntitlementService(database = db, clock = { tick }),
                database = db,
                clock = { tick }
            ),
            executionService = executionService,
            observationService = ObservationService(),
            outcomeService = OutcomeService(),
            taskDao = null,
            actionIntentDao = controllableIntentDao,
            defaultSecurityPolicy = com.example.domain.core.security.SecurityPolicy(),
            coroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
            economicGovernanceService = governance
        ).also {
            it.workspaceIdProvider = { workspaceId }
        }
    }

    @After
    open fun tearDown() {
        db.close()
    }

    // ------------------------------------------------------------------
    // The deterministic provider at the external boundary
    // ------------------------------------------------------------------

    /**
     * The ONLY test double: a scripted LlmProviderPort. Deterministic
     * knobs: usage numbers, hanging streams (timeout/cancellation tests),
     * thrown exceptions. Everything else in the chain is production code.
     */
    class ScriptedProvider : LlmProviderPort {
        override val providerId: String = "prod_path_provider"
        override val metadata: SafeProviderMetadata = SafeProviderMetadata(
            id = "prod_path_provider", name = "ProdPath", providerType = "SCRIPTED",
            defaultModel = "prod-model", isConfigured = true, isOnline = true, isLocal = false
        )

        /** Provider invocations ENTERED (the boundary crossings). */
        val invocations = AtomicInteger(0)

        /** Completed whenever a provider invocation is ENTERED. */
        val entered = CompletableDeferred<Unit>()

        /** When set, a stream() invocation awaits it before finishing
         *  (the hang knob for timeout / cancellation-after-boundary). */
        var hangLatch: CompletableDeferred<Unit>? = null

        /** When set, the stream throws after entering (lost stream). */
        var failStream = false

        /** Reported usage (provider-reported, not estimated). */
        var promptTokens = 5_000
        var completionTokens = 5_000

        var responseText = "إجابة اختبارية كاملة تحقق معايير الإخراج."

        override suspend fun generate(request: LlmRequest): Outcome<LlmResponse, LlmFailure> =
            Outcome.Success(
                LlmResponse(
                    text = responseText,
                    usage = TokenUsage(promptTokens = promptTokens, completionTokens = completionTokens),
                    modelId = "prod-model"
                )
            )

        override fun stream(request: LlmRequest, executionId: String): Flow<ExecutionEvent> = flow {
            invocations.incrementAndGet()
            entered.complete(Unit)
            hangLatch?.await()
            if (failStream) throw java.io.IOException("SCRIPTED_STREAM_LOST")
            emit(
                ExecutionEvent.UsageBudgetUpdate(
                    executionId = executionId,
                    promptTokens = promptTokens,
                    completionTokens = completionTokens,
                    totalSessionTokens = promptTokens + completionTokens,
                    remainingBudgetTokens = ExecutionEvent.UsageBudgetUpdate.REMAINING_UNKNOWN,
                    totalTokens = promptTokens + completionTokens,
                    providerId = providerId,
                    modelId = "prod-model"
                )
            )
        }
    }

    /** A scripted SEARCH provider (for the search-boundary tests). */
    class ScriptedSearchProvider : SearchProviderPort {
        override val providerId: String = "prod_path_search"
        override val metadata: SafeSearchProviderMetadata = SafeSearchProviderMetadata(
            id = "prod_path_search", name = "ProdPathSearch", providerType = "SCRIPTED",
            isConfigured = true, isEnabled = true, priority = 1
        )
        val invocations = AtomicInteger(0)
        var failWithNetworkError = false

        override suspend fun search(query: SearchQuery): Outcome<SearchResultSet, SearchFailure> {
            invocations.incrementAndGet()
            return if (failWithNetworkError) {
                Outcome.Error(
                    failure = SearchFailure.NetworkError("scripted network failure"),
                    diagnosticMessage = "scripted network failure"
                )
            } else {
                Outcome.Success(
                    SearchResultSet(
                        query = query.query,
                        items = listOf(
                            SearchResultItem(
                                title = "نتيجة اختبارية",
                                url = "https://example.com/prod-path",
                                snippet = "snippet"
                            )
                        ),
                        providerId = providerId
                    )
                )
            }
        }
    }

    /**
     * A test-controllable ActionIntentDao: delegates every call to the REAL
     * Room DAO, but can hold an `insertIntent` on a latch — a deterministic
     * suspension point BETWEEN the decide-time hold creation and
     * executeAction's provider boundary (used to prove a pre-dispatch
     * cancellation releases the reservation). The action-intent ledger is
     * the production wiring position; only its response TIME is controlled,
     * never its data.
     */
    class ControllableActionIntentDao(
        private val real: ActionIntentDao
    ) : ActionIntentDao {
        var holdLatch: CompletableDeferred<Unit>? = null

        override suspend fun insertIntent(intent: ActionIntentEntity): Long {
            holdLatch?.await()
            return real.insertIntent(intent)
        }

        override suspend fun getIntent(executionId: String, actionKey: String): ActionIntentEntity? =
            real.getIntent(executionId, actionKey)

        override suspend fun getIntentsForExecution(executionId: String): List<ActionIntentEntity> =
            real.getIntentsForExecution(executionId)

        override suspend fun updateIntentOutcome(
            executionId: String,
            actionKey: String,
            state: String,
            fingerprint: String?,
            summary: String?,
            now: Long
        ) = real.updateIntentOutcome(executionId, actionKey, state, fingerprint, summary, now)

        override suspend fun clearIntentsForExecution(executionId: String) =
            real.clearIntentsForExecution(executionId)

        override suspend fun completedIntentCount(executionId: String): Int =
            real.completedIntentCount(executionId)
    }

    // ------------------------------------------------------------------
    // Task fixtures
    // ------------------------------------------------------------------

    /**
     * A task that can never verify COMPLETE (impossible evidence key) so the
     * loop keeps deciding — real billable LLM steps execute through the
     * production chain until maxSteps.
     */
    protected fun unfulfillableTask(tokenLimit: Int = 300_000) = TaskDefinition(
        id = TaskId("prod-path-task"),
        assignedAgentId = agent.identity.id,
        goal = "استمر في التحليل الكامل",
        input = TaskInput(rawPrompt = "حلّل بالتفصيل الكامل"),
        budget = TaskBudget(tokenLimit = tokenLimit),
        successCriteria = TaskSuccessCriteria(
            verificationStrategy = VerificationStrategy.STRICT,
            minOutputLengthChars = 5,
            requiredEvidenceKeys = listOf("impossible_evidence_key_xyz")
        )
    )

    /**
     * PROVIDER-scope pricing: micro-units per million tokens. Provider scope
     * is the deterministic level for BOTH gates of a billable step — the
     * engine's chosen actions carry (resourceId, providerId, serviceId) in
     * their payload while modelId is optional, so a MODEL-scope entry would
     * resolve UNKNOWN at the decide gate (I-3, no hold) and KNOWN at the
     * pre-dispatch gate — an estimate-granularity quirk this harness does
     * not exercise; PROVIDER scope resolves identically on both sides.
     */
    protected suspend fun seedPricing(
        inputMicroPerMillion: Long = 1500,
        outputMicroPerMillion: Long = 3000,
        currency: String = "USD",
        providerId: String = "prod_path_provider"
    ) {
        economicStore.upsertPricing(
            PricingEntry(
                id = "pe-prod-path",
                scope = PricingScope.PROVIDER,
                providerId = providerId,
                serviceId = null,
                modelId = null,
                inputPricePerMillion = MoneyAmount.of(inputMicroPerMillion, currency),
                outputPricePerMillion = MoneyAmount.of(outputMicroPerMillion, currency),
                cachedInputPricePerMillion = null,
                billingClass = BillingClass.PAID,
                pricingVersion = "v1",
                effectiveFromEpochMs = 0,
                effectiveToEpochMs = null,
                provenance = "HARNESS"
            )
        )
    }

    protected suspend fun configureWorkspaceBudget(
        limitMicroUnits: Long = 5_000_000L,
        currency: String = "USD",
        workspace: String = workspaceId
    ): BudgetPolicyManagementService.ConfigureOutcome =
        policyManagement.configureWorkspaceBudget(
            BudgetPolicyManagementService.ConfigureWorkspaceBudgetRequest(
                workspaceId = workspace,
                limitMicroUnits = limitMicroUnits,
                currency = currency,
                overagePolicy = BudgetLedgerService.OVERAGE_HARD_LIMIT,
                periodStartEpochMs = tick,
                periodEndEpochMs = tick + periodMs
            )
        )

    /** Runs the production loop to its end and returns every event. */
    protected fun runTask(task: TaskDefinition): List<ExecutionEvent> = runBlocking {
        orchestrator.executeTaskStream(agent, task).toList()
    }

    /** Runs with an external timeout (TimeoutCancellationException propagates). */
    protected fun runTaskWithTimeout(task: TaskDefinition, timeoutMs: Long): List<ExecutionEvent> =
        runBlocking {
            withTimeout(timeoutMs) { orchestrator.executeTaskStream(agent, task).toList() }
        }

    // ------------------------------------------------------------------
    // Persisted-state probes (assertions are on ROWS, never messages)
    // ------------------------------------------------------------------

    protected suspend fun holdsForExecution(executionId: String): List<com.example.infrastructure.persistence.entities.BudgetHoldEntity> =
        db.budgetHoldDao().allForReason("exec:$executionId")

    protected suspend fun allHoldsInPeriod(periodId: String): List<com.example.infrastructure.persistence.entities.BudgetHoldEntity> =
        db.budgetHoldDao().forPeriod(periodId)

    protected suspend fun settlementsForHold(holdId: String): List<com.example.infrastructure.persistence.entities.BudgetHoldSettlementEntity> =
        db.budgetHoldSettlementDao().forHold(holdId)

    protected suspend fun entriesForPeriod(periodId: String): List<com.example.infrastructure.persistence.entities.BudgetLedgerEntryEntity> =
        db.budgetLedgerEntryDao().forPeriod(periodId)

    protected suspend fun activePeriodId(workspace: String = workspaceId): String? {
        val active = ledgerService.activeBudgetForScope(
            scopeType = BudgetPolicyManagementService.WORKSPACE_SCOPE_TYPE,
            platformObjectId = workspace,
            nowEpochMs = tick
        )
        return active?.period?.budgetPeriodId
    }
}
