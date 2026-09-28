package com.example.closure

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.example.application.decision.DecisionContext
import com.example.domain.core.decision.CaseBase
import com.example.domain.core.decision.CbrMdpEngine
import com.example.domain.core.decision.DecisionAction
import com.example.domain.core.decision.DecisionActionType
import com.example.domain.core.decision.DecisionCase
import com.example.domain.core.decision.DecisionRecord
import com.example.domain.core.decision.DecisionResult
import com.example.domain.core.decision.DecisionState
import com.example.domain.core.resource.ResourceId
import com.example.domain.core.task.TaskDefinition
import com.example.domain.core.task.TaskId
import com.example.domain.core.task.TaskInput
import com.example.infrastructure.persistence.AppDatabase
import com.example.infrastructure.persistence.repository.RoomDecisionCaseStore
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
 * ClosureCompletionTests — the P1-3 (resource-aware CBR) + §5/item-1
 * (scope unification) completion invariants.
 * ============================================================================
 *
 * Every test here pins a contract the completion stage introduced or
 * tightened:
 *   1. The decision record's RESOURCE-IDENTITY projection survives the Room
 *      case-store round-trip (previously dropped — audit §5/item 6:
 *      "stored cases lose the DecisionRecord on reload").
 *   2. Legacy (pre-v21) rows load honestly UNATTRIBUTED — never a synthetic
 *      record, never an implicit re-assignment.
 *   3. Resource-aware retrieval: an exact nominal identity match ranks the
 *      same-resource case first; a case WITHOUT a record earns nothing.
 *   4. Per-resource reward priors aggregate only ATTRIBUTED rewards.
 *   5. Cold-start scoring consults the measured per-resource prior instead
 *      of the neutral 0.5.
 *   6. The state carries the active resource identity projected from the
 *      decision history (the audit gap: "the resource axis exists in Q-cells
 *      only; states carry no resource identity").
 *   7. The feature vector stays length-15 — nominal identity NEVER enters
 *      the metric vector (a hash of a nominal id would be pseudo-metric
 *      noise; the padding contract is test-pinned by Phase1FixVerificationTest).
 */
@RunWith(RobolectricTestRunner::class)
class ClosureCompletionTests {

    private lateinit var db: AppDatabase
    private lateinit var store: RoomDecisionCaseStore

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        store = RoomDecisionCaseStore(db.decisionCaseDao())
    }

    @After
    fun tearDown() {
        db.close()
    }

    // ------------------------------------------------------------------
    // Helpers
    // ------------------------------------------------------------------

    private fun recordForResource(rid: String): DecisionRecord = DecisionRecord(
        selectedResourceId = ResourceId(rid),
        providerId = "provider-$rid",
        serviceId = "service-$rid",
        configurationVersion = 7L,
        requiredCapabilities = emptySet(),
        rationale = "اختبار",
        confidence = 0.9f,
        governanceState = "APPROVED"
    )

    private fun case(
        id: String,
        actionType: DecisionActionType = DecisionActionType.SELECT_MODEL,
        record: DecisionRecord? = null,
        reward: Float = 0.8f,
        features: FloatArray = floatArrayOf(
            0.5f, 0.0f, 1.0f, 0.0f, 0.0f, 1.0f, 0.0f, 1.0f, 1.0f, 0.0f, 0.2f, 0.0f, 0.0f, 0.0f, 0.0f
        )
    ): DecisionCase = DecisionCase(
        id = id,
        problemFeatures = features,
        chosenAction = DecisionAction(actionType, targetId = record?.selectedResourceId?.value, decisionRecord = record),
        outcomeReward = reward,
        taskType = "TEST"
    )

    private fun task(): TaskDefinition = TaskDefinition(
        id = TaskId("task-closure-completion"),
        assignedAgentId = com.example.domain.core.agent.AgentId("default"),
        input = TaskInput(rawPrompt = "اختبار الإغلاق")
    )

    // ------------------------------------------------------------------
    // 1. Decision-record durability through the Room case store
    // ------------------------------------------------------------------

    @Test
    fun `decision record identity survives the Room case store round-trip`() = runBlocking {
        val original = case(
            id = "case_with_record",
            record = recordForResource("res_alpha")
        )
        store.append(original)

        val reloaded = store.loadAll().single()

        assertEquals("case id survives", original.id, reloaded.id)
        val record = reloaded.chosenAction.decisionRecord
        assertNotNull("the decision record must survive the reload (P1-3: it was previously dropped)", record)
        assertEquals(ResourceId("res_alpha"), record!!.selectedResourceId)
        assertEquals("provider-res_alpha", record.providerId)
        assertEquals("service-res_alpha", record.serviceId)
        assertEquals(7L, record.configurationVersion)
        assertEquals("APPROVED", record.governanceState)
        assertEquals(0.9f, record.confidence, 0.0001f)
    }

    @Test
    fun `legacy rows without record columns load honestly unattributed`() = runBlocking {
        // A pre-v21 row: record columns are NULL (the migration's default).
        val legacy = case(id = "legacy_row", record = null)
        store.append(legacy)

        val reloaded = store.loadAll().single()

        assertNull(
            "a legacy row must stay honestly UNATTRIBUTED — never a synthetic record",
            reloaded.chosenAction.decisionRecord
        )
        assertEquals(DecisionActionType.SELECT_MODEL, reloaded.chosenAction.type)
    }

    // ------------------------------------------------------------------
    // 2. Resource-aware retrieval (nominal identity as a MATCH term)
    // ------------------------------------------------------------------

    @Test
    fun `an exact resource identity match outranks an identical unattributed case`() {
        val caseBase = CaseBase()
        runBlocking {
            caseBase.addCase(case(id = "same_resource", record = recordForResource("res_alpha")))
            caseBase.addCase(case(id = "no_record"))
        }
        // A near- (not exactly-) identical query: base cosine is high but
        // below 1.0, so the identity bonus is visible below the 1.0 cap.
        val query = floatArrayOf(
            0.6f, 0.0f, 1.0f, 0.0f, 0.0f, 1.0f, 0.0f, 1.0f, 1.0f, 0.0f, 0.2f, 0.0f, 0.0f, 0.0f, 0.0f
        )

        val ranked = caseBase.findSimilarCases(
            query,
            k = 2,
            minSimilarity = 0.0f,
            activeResourceId = ResourceId("res_alpha")
        )

        assertEquals(2, ranked.size)
        assertEquals(
            "the same-resource case must rank FIRST (identity match beats no-record)",
            "same_resource",
            ranked.first().first.id
        )
        assertTrue(
            "the identity bonus must be a real, bounded boost",
            ranked.first().second > ranked.last().second
        )
        assertTrue(
            "the boosted similarity stays within the [0, 1] contract",
            ranked.first().second <= 1.0f
        )
    }

    @Test
    fun `a case on ANOTHER resource earns no identity bonus`() {
        val caseBase = CaseBase()
        runBlocking {
            caseBase.addCase(case(id = "other_resource", record = recordForResource("res_beta")))
        }
        val query = floatArrayOf(
            0.5f, 0.0f, 1.0f, 0.0f, 0.0f, 1.0f, 0.0f, 1.0f, 1.0f, 0.0f, 0.2f, 0.0f, 0.0f, 0.0f, 0.0f
        )

        val withIdentity = caseBase.findSimilarCases(
            query, k = 1, minSimilarity = 0.0f, activeResourceId = ResourceId("res_alpha")
        ).single()
        val withoutIdentity = caseBase.findSimilarCases(
            query, k = 1, minSimilarity = 0.0f, activeResourceId = null
        ).single()

        assertEquals(
            "\"other resource\" never masquerades as \"same resource\"",
            withoutIdentity.second,
            withIdentity.second,
            0.0001f
        )
    }

    // ------------------------------------------------------------------
    // 3. Per-resource reward priors
    // ------------------------------------------------------------------

    @Test
    fun `priors aggregate only attributed rewards per resource`() {
        val caseBase = CaseBase()
        runBlocking {
            caseBase.addCase(case(id = "a1", record = recordForResource("res_alpha"), reward = 1.0f))
            caseBase.addCase(case(id = "a2", record = recordForResource("res_alpha"), reward = 0.0f))
            caseBase.addCase(case(id = "b1", record = recordForResource("res_beta"), reward = -0.5f))
            caseBase.addCase(case(id = "unattributed", record = null, reward = 1.0f))
        }

        val priors = caseBase.resourceRewardPriors()

        assertEquals(2, priors.size)
        assertEquals(0.5f, priors[ResourceId("res_alpha")]!!.meanReward, 0.0001f)
        assertEquals(2, priors[ResourceId("res_alpha")]!!.caseCount)
        assertEquals(-0.5f, priors[ResourceId("res_beta")]!!.meanReward, 0.0001f)
        assertNull("an unattributed reward must never tilt a named prior", priors[ResourceId("unattributed")])
    }

    // ------------------------------------------------------------------
    // 4. Cold-start scoring consults the measured prior
    // ------------------------------------------------------------------

    @Test
    fun `cold-start scoring uses the measured resource prior over the neutral`() = runBlocking {
        // Seed history: resource "res_bad" has consistently FAILED cases whose
        // features are DISSIMILAR to the query (so the query's matching-case
        // set stays empty for its action family — the cold-start path).
        val farFeatures = FloatArray(15) { 0.05f }
        val caseBase = CaseBase()
        caseBase.addCase(
            case(
                id = "bad_history_1",
                actionType = DecisionActionType.RETRIEVE_KNOWLEDGE,
                record = recordForResource("res_bad"),
                reward = -1.0f,
                features = farFeatures
            )
        )
        caseBase.addCase(
            case(
                id = "bad_history_2",
                actionType = DecisionActionType.RETRIEVE_KNOWLEDGE,
                record = recordForResource("res_bad"),
                reward = -1.0f,
                features = farFeatures
            )
        )

        val engine = CbrMdpEngine(caseBase = caseBase)
        val state = DecisionState(taskId = TaskId("cold-start"))
        // A resource-targeted candidate on the historically-failing resource,
        // with NO similar same-type case (the seeded cases are dissimilar).
        val candidates = listOf(
            DecisionAction(DecisionActionType.RETRIEVE_KNOWLEDGE, targetId = "res_bad")
        )

        val result = engine.evaluateAndSelectAction(state, candidates)
        val scored = result.evaluatedAlternatives.single()

        assertTrue(
            "the measured prior (-1.0 coerced to 0.0) must displace the neutral 0.5 " +
                "for a historically-failing resource (got cbrScore=${scored.cbrScore})",
            scored.cbrScore < 0.25f
        )
    }

    // ------------------------------------------------------------------
    // 5. The state carries the active resource identity (projection)
    // ------------------------------------------------------------------

    @Test
    fun `the state carries the active resource projected from the decision history`() {
        val chosen = DecisionAction(
            DecisionActionType.SELECT_MODEL,
            targetId = "res_gamma",
            decisionRecord = recordForResource("res_gamma")
        )
        val historyEntry = DecisionResult(
            chosenAction = chosen,
            confidence = 0.9f,
            rationale = "اختيار",
            stateSnapshot = DecisionState(taskId = TaskId("hist")),
            evaluatedAlternatives = emptyList(),
            matchedHistoricalCasesCount = 0
        )
        val context = DecisionContext(
            task = task(),
            decisionHistory = listOf(historyEntry)
        )

        val state = context.toDecisionState()

        assertEquals(
            "the state's resource identity = the LAST decision record's selected resource",
            ResourceId("res_gamma"),
            state.activeResourceId
        )
    }

    @Test
    fun `a state without decision history is honestly unbound`() {
        val state = DecisionContext(task = task()).toDecisionState()

        assertNull("no history → no active resource (honestly unbound)", state.activeResourceId)
    }

    // ------------------------------------------------------------------
    // 6. The metric vector is UNTOUCHED by nominal identity
    // ------------------------------------------------------------------

    @Test
    fun `nominal resource identity never enters the metric vector`() {
        val bound = DecisionState(taskId = TaskId("bound"), activeResourceId = ResourceId("res_alpha"))
        val unbound = DecisionState(taskId = TaskId("unbound"))

        assertEquals(
            "the feature vector contract is length-15 regardless of identity (Phase1 pin)",
            15,
            bound.toFeatureVector().size
        )
        assertTrue(
            "identity must not alter the metric vector",
            bound.toFeatureVector().contentEquals(unbound.toFeatureVector())
        )
    }

    // ------------------------------------------------------------------
    // 7. Durable reload restores the priors (the full P1-3 loop)
    // ------------------------------------------------------------------

    @Test
    fun `a reloaded case base restores both identity ranking and priors`() = runBlocking {
        // Append through the REAL Room store, then build a CaseBase whose
        // backing store reloads those rows — the persisted identity must
        // power BOTH retrieval ranking and priors after restart.
        store.append(case(id = "durable_1", record = recordForResource("res_durable"), reward = 0.9f))

        val reloadedCases = store.loadAll()
        val freshBase = CaseBase()
        reloadedCases.forEach { reloaded ->
            // addCase is the in-memory admission path; the record rides the case.
            freshBase.addCase(reloaded)
        }

        val priors = freshBase.resourceRewardPriors()
        assertEquals(
            "the reloaded record powers the priors",
            0.9f,
            priors[ResourceId("res_durable")]!!.meanReward,
            0.0001f
        )

        val query = floatArrayOf(
            0.5f, 0.0f, 1.0f, 0.0f, 0.0f, 1.0f, 0.0f, 1.0f, 1.0f, 0.0f, 0.2f, 0.0f, 0.0f, 0.0f, 0.0f
        )
        val ranked = freshBase.findSimilarCases(
            query, k = 1, minSimilarity = 0.0f, activeResourceId = ResourceId("res_durable")
        )
        assertTrue(
            "the reloaded record powers identity-aware ranking",
            ranked.isNotEmpty() && ranked.first().second > 0.99f
        )
    }

    // ------------------------------------------------------------------
    // 8. The scope unification surface (coordinator + canonical shape)
    // ------------------------------------------------------------------

    @Test
    fun `the canonical ScopeSnapshot projects into the pinned ExecutionScope`() {
        val snapshot = com.example.domain.core.execution.ScopeSnapshot.capture(
            operationId = "op_completion_test",
            workspaceId = "ws_1",
            projectId = 5L,
            sessionId = "sess_9"
        )

        val projected = snapshot.toExecutionScope()

        assertEquals("op_completion_test", projected.executionId)
        assertEquals("ws_1", projected.workspaceId)
        assertEquals(5L, projected.projectId)
        assertEquals("sess_9", projected.sessionId)
    }

    @Test
    fun `an unattributed snapshot projects to the honest unattributed workspace`() {
        val snapshot = com.example.domain.core.execution.ScopeSnapshot.capture(
            operationId = "op_unattributed",
            workspaceId = null,
            projectId = null
        )

        assertEquals("unattributed", snapshot.toExecutionScope().workspaceId)
    }
}
