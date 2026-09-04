package com.example.application

import com.example.application.decision.DecisionService
import com.example.application.registry.ComponentRegistry
import com.example.application.security.SecurityGuardService
import com.example.domain.core.Outcome
import com.example.domain.core.agent.AgentBudget
import com.example.domain.core.agent.AgentDefinition
import com.example.domain.core.agent.AgentId
import com.example.domain.core.agent.AgentIdentity
import com.example.domain.core.agent.AgentRole
import com.example.domain.core.capability.CapabilityDescriptor
import com.example.domain.core.capability.CapabilityMatchLevel
import com.example.domain.core.capability.CapabilityResourceGraph
import com.example.domain.core.capability.CapabilityState
import com.example.domain.core.capability.CapabilityStatus
import com.example.domain.core.capability.CapabilityType
import com.example.domain.core.capability.Locality
import com.example.domain.core.capability.NetworkRequirement
import com.example.domain.core.capability.SideEffectClassification
import com.example.domain.core.decision.CaseBase
import com.example.domain.core.decision.CbrMdpEngine
import com.example.domain.core.decision.DecisionActionType
import com.example.domain.core.llm.LlmFailure
import com.example.domain.core.llm.LlmRequest
import com.example.domain.core.llm.LlmResponse
import com.example.domain.core.llm.SafeProviderMetadata
import com.example.domain.core.llm.TokenUsage
import com.example.domain.core.network.NetworkPolicy
import com.example.domain.core.security.SecurityPolicy
import com.example.domain.core.task.TaskCapabilityRequirements
import com.example.domain.core.task.TaskConstraints
import com.example.domain.core.task.TaskDefinition
import com.example.domain.core.task.TaskId
import com.example.domain.core.task.TaskInput
import com.example.domain.core.task.TaskLifecycleState
import com.example.domain.core.tools.ToolDeclaration
import com.example.domain.core.tools.ToolFailure
import com.example.domain.core.tools.ToolInput
import com.example.domain.core.tools.ToolOutput
import com.example.domain.ports.llm.LlmProviderPort
import com.example.domain.ports.tools.ToolPort
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import kotlinx.coroutines.runBlocking
import org.junit.Test

/**
 * Production-grade behavioral tests verifying the Capability Intelligence Core.
 *
 * Validates the complete pipeline:
 * Task Understanding → Structured Capabilities → Resources → Candidate Actions → DecisionService → CBR-MDP.
 */
class CapabilityIntelligenceCoreTest {

    private lateinit var registry: ComponentRegistry
    private lateinit var securityGuard: SecurityGuardService
    private lateinit var caseBase: CaseBase
    private lateinit var cbrMdpEngine: CbrMdpEngine
    private lateinit var decisionService: DecisionService

    @Before
    fun setUp() {
        registry = ComponentRegistry()
        securityGuard = SecurityGuardService()
        caseBase = CaseBase()
        cbrMdpEngine = CbrMdpEngine(caseBase = caseBase)
        decisionService = DecisionService(
            cbrMdpEngine = cbrMdpEngine,
            componentRegistry = registry,
            securityGuard = securityGuard,
            defaultSecurityPolicy = SecurityPolicy()
        )
    }

    @Test
    fun testCapabilityTaxonomyAndDefaults() {
        val fileWriteCap = CapabilityType.FILE_WRITE
        assertEquals(NetworkRequirement.LOCAL_ONLY, fileWriteCap.defaultNetworkRequirement)
        assertEquals(Locality.LOCAL_ON_DEVICE, fileWriteCap.defaultLocality)
        assertEquals(SideEffectClassification.STATE_MUTATION, fileWriteCap.defaultSideEffect)

        val searchCap = CapabilityType.SEARCH
        assertEquals(NetworkRequirement.ONLINE_ONLY, searchCap.defaultNetworkRequirement)
        assertEquals(Locality.REMOTE_CLOUD, searchCap.defaultLocality)
        assertEquals(SideEffectClassification.READ_ONLY, searchCap.defaultSideEffect)
    }

    @Test
    fun testCapabilityResourceGraphBipartiteIndexing() {
        val graph = CapabilityResourceGraph()
        val desc1 = CapabilityDescriptor(
            type = CapabilityType.FILE_WRITE,
            state = CapabilityState.AVAILABLE,
            providerId = "tool_file_writer",
            resourceType = "TOOL",
            isLocal = true
        )
        val desc2 = CapabilityDescriptor(
            type = CapabilityType.FILE_READ,
            state = CapabilityState.AVAILABLE,
            providerId = "tool_file_writer",
            resourceType = "TOOL",
            isLocal = true
        )
        val desc3 = CapabilityDescriptor(
            type = CapabilityType.SEARCH,
            state = CapabilityState.AVAILABLE,
            providerId = "search_google",
            resourceType = "PROVIDER",
            isLocal = false
        )

        graph.registerDescriptors(listOf(desc1, desc2, desc3))

        val fileWriters = graph.getResourcesProviding(CapabilityType.FILE_WRITE)
        assertEquals(1, fileWriters.size)
        assertEquals("tool_file_writer", fileWriters.first().providerId)

        val toolCaps = graph.getCapabilitiesProvidedBy("tool_file_writer")
        assertEquals(2, toolCaps.size)
        assertTrue(toolCaps.any { it.type == CapabilityType.FILE_WRITE })
        assertTrue(toolCaps.any { it.type == CapabilityType.FILE_READ })
    }

    @Test
    fun testDeterministicMatching_FullMatch() {
        val graph = CapabilityResourceGraph()
        val match = graph.matchCapabilities(
            required = setOf(CapabilityType.FILE_READ, CapabilityType.FILE_WRITE),
            optional = setOf(CapabilityType.TOOL_EXECUTION),
            prohibited = emptySet(),
            candidateCapabilities = setOf(CapabilityType.FILE_READ, CapabilityType.FILE_WRITE, CapabilityType.TOOL_EXECUTION),
            networkPolicy = NetworkPolicy.HYBRID,
            isNetworkAvailable = true,
            isCandidateLocal = true
        )

        assertEquals(CapabilityMatchLevel.FULL_MATCH, match.matchLevel)
        assertTrue(match.isFullMatch)
        assertEquals(2, match.satisfiedCapabilities.size)
        assertTrue(match.missingCapabilities.isEmpty())
        assertFalse(match.hasViolations)
        assertEquals(1.0f, match.coverageRatio, 0.01f)
    }

    @Test
    fun testDeterministicMatching_ProhibitedConflict() {
        val graph = CapabilityResourceGraph()
        val match = graph.matchCapabilities(
            required = setOf(CapabilityType.FILE_READ),
            optional = emptySet(),
            prohibited = setOf(CapabilityType.SHELL_EXECUTION),
            candidateCapabilities = setOf(CapabilityType.FILE_READ, CapabilityType.SHELL_EXECUTION),
            networkPolicy = NetworkPolicy.HYBRID,
            isNetworkAvailable = true,
            isCandidateLocal = true
        )

        assertEquals(CapabilityMatchLevel.CONFLICT, match.matchLevel)
        assertTrue(match.hasViolations)
        assertTrue(match.prohibitedViolations.contains(CapabilityType.SHELL_EXECUTION))
    }

    @Test
    fun testDeterministicMatching_OfflineNetworkConstraint() {
        val graph = CapabilityResourceGraph()
        val match = graph.matchCapabilities(
            required = setOf(CapabilityType.SEARCH),
            optional = emptySet(),
            prohibited = emptySet(),
            candidateCapabilities = setOf(CapabilityType.SEARCH),
            networkPolicy = NetworkPolicy.OFFLINE,
            isNetworkAvailable = false,
            isCandidateLocal = false
        )

        assertEquals(CapabilityMatchLevel.CONFLICT, match.matchLevel)
        assertTrue(match.conflictingCapabilities.contains(CapabilityType.SEARCH))
        assertTrue(match.missingCapabilities.contains(CapabilityType.SEARCH))
    }

    @Test
    fun testCapabilityGapAnalysis_SatisfiedAndMissing() {
        val graph = CapabilityResourceGraph()
        graph.registerDescriptor(
            CapabilityDescriptor(
                type = CapabilityType.FILE_STORAGE,
                state = CapabilityState.AVAILABLE,
                providerId = "storage_tool",
                resourceType = "TOOL",
                isLocal = true
            )
        )

        val requirements = TaskCapabilityRequirements(
            requiredCapabilities = setOf(CapabilityType.FILE_STORAGE, CapabilityType.SEARCH),
            optionalCapabilities = setOf(CapabilityType.STREAMING)
        )

        val gap = graph.analyzeGap(
            taskId = "task_001",
            requirements = requirements,
            currentlySatisfied = setOf(CapabilityType.FILE_STORAGE),
            networkPolicy = NetworkPolicy.HYBRID,
            isNetworkAvailable = true
        )

        assertEquals(CapabilityStatus.CAPABILITY_PARTIAL, gap.status)
        assertFalse(gap.isFullySatisfied)
        assertTrue(gap.satisfiedCapabilities.contains(CapabilityType.FILE_STORAGE))
        assertTrue(gap.missingCapabilities.contains(CapabilityType.SEARCH))
    }

    @Test
    fun testAgentSelection_ViaCapabilityMatching() {
        val coderAgent = AgentDefinition(
            identity = AgentIdentity(
                id = AgentId("coder_agent"),
                name = "Coder",
                role = AgentRole.CODER,
                description = "Writes clean code",
                systemPrompt = ""
            ),
            allowedCapabilities = setOf(CapabilityType.CODE_ENGINEERING, CapabilityType.TOOL_EXECUTION, CapabilityType.FILE_WRITE),
            budget = AgentBudget(maxTokens = 30000),
            locality = Locality.LOCAL_ON_DEVICE
        )

        val researcherAgent = AgentDefinition(
            identity = AgentIdentity(
                id = AgentId("researcher_agent"),
                name = "Researcher",
                role = AgentRole.RESEARCHER,
                description = "Searches knowledge",
                systemPrompt = ""
            ),
            allowedCapabilities = setOf(CapabilityType.SEARCH, CapabilityType.MEMORY_RETRIEVAL),
            budget = AgentBudget(maxTokens = 30000),
            locality = Locality.LOCAL_ON_DEVICE
        )

        val taskCode = TaskDefinition(
            id = TaskId("task_code"),
            assignedAgentId = AgentId("default"),
            input = TaskInput("Build a Room database repository module"),
            requirements = TaskCapabilityRequirements(
                requiredCapabilities = setOf(CapabilityType.CODE_ENGINEERING, CapabilityType.TOOL_EXECUTION)
            )
        )

        val taskSearch = TaskDefinition(
            id = TaskId("task_search"),
            assignedAgentId = AgentId("default"),
            input = TaskInput("Search for recent Android documentation"),
            requirements = TaskCapabilityRequirements(
                requiredCapabilities = setOf(CapabilityType.SEARCH)
            )
        )

        val selectedForCode = decisionService.selectSuitableAgent(taskCode, listOf(coderAgent, researcherAgent))
        assertEquals("coder_agent", selectedForCode?.identity?.id?.value)

        val selectedForSearch = decisionService.selectSuitableAgent(taskSearch, listOf(coderAgent, researcherAgent))
        assertEquals("researcher_agent", selectedForSearch?.identity?.id?.value)
    }

    @Test
    fun testToolSelection_StrictlyCapabilityDriven() = runBlocking {
        val dummyTool = object : ToolPort {
            override val declaration: ToolDeclaration = ToolDeclaration(
                name = "special_crypto_signer",
                description = "Signs cryptographic payloads",
                providedCapabilities = setOf(CapabilityType.HASH_COMPUTATION, CapabilityType.TOOL_EXECUTION),
                networkRequirement = NetworkRequirement.LOCAL_ONLY,
                locality = Locality.LOCAL_ON_DEVICE
            )

            override suspend fun execute(input: ToolInput): Outcome<ToolOutput, ToolFailure> {
                return Outcome.Success(ToolOutput("signed_hash_12345"))
            }
        }

        registry.registerTool(dummyTool)

        val taskNeedingCrypto = TaskDefinition(
            id = TaskId("task_crypto"),
            assignedAgentId = AgentId("default"),
            input = TaskInput("Compute cryptographic signature hash"),
            requirements = TaskCapabilityRequirements(
                requiredCapabilities = setOf(CapabilityType.HASH_COMPUTATION)
            )
        )

        val context = decisionService.buildDecisionContext(task = taskNeedingCrypto)
        val actions = decisionService.generateCandidateActions(context)

        val executeToolAction = actions.firstOrNull { it.type == DecisionActionType.EXECUTE_TOOL && it.targetId == "special_crypto_signer" }
        assertNotNull("Tool providing HASH_COMPUTATION must be included as a candidate action", executeToolAction)
    }

    @Test
    fun testDecisionService_CapabilityGapBlockNotification() = runBlocking {
        val taskWithImpossibleCap = TaskDefinition(
            id = TaskId("task_impossible"),
            assignedAgentId = AgentId("default"),
            input = TaskInput("Execute cloud quantum operation"),
            requirements = TaskCapabilityRequirements(
                requiredCapabilities = setOf(CapabilityType.INTEGRATION_SYNC),
                localityConstraint = Locality.LOCAL_ON_DEVICE
            )
        )

        val context = decisionService.buildDecisionContext(
            task = taskWithImpossibleCap,
            networkPolicy = NetworkPolicy.OFFLINE,
            isNetworkAvailable = false
        )

        val actions = decisionService.generateCandidateActions(context)
        val askUserAction = actions.firstOrNull { it.type == DecisionActionType.ASK_USER && it.targetId == "capability_gap_resolution" }

        assertNotNull("When required capabilities are blocked, DecisionService must offer user intervention candidate", askUserAction)
    }

    @Test
    fun testAgentSelection_ReturnsNullWhenNoCapableAgent() {
        val coderAgent = AgentDefinition(
            identity = AgentIdentity(
                id = AgentId("coder_agent"),
                name = "Coder",
                role = AgentRole.CODER,
                description = "Writes clean code",
                systemPrompt = ""
            ),
            allowedCapabilities = setOf(CapabilityType.CODE_ENGINEERING, CapabilityType.TOOL_EXECUTION),
            budget = AgentBudget(maxTokens = 30000),
            locality = Locality.LOCAL_ON_DEVICE
        )

        val taskVision = TaskDefinition(
            id = TaskId("task_vision"),
            assignedAgentId = AgentId("default"),
            input = TaskInput("Inspect screenshot image"),
            requirements = TaskCapabilityRequirements(
                requiredCapabilities = setOf(CapabilityType.VISION)
            )
        )

        val selected = decisionService.selectSuitableAgent(taskVision, listOf(coderAgent))
        assertNull("Agent selection must return null when no candidate satisfies required capabilities (Rule 8)", selected)
    }

    @Test
    fun testEvidenceBasedSatisfaction_PendingUntilEvidenceRecorded() {
        val graph = CapabilityResourceGraph()
        val desc = CapabilityDescriptor(
            type = CapabilityType.SEARCH,
            state = CapabilityState.AVAILABLE,
            providerId = "search_engine",
            resourceType = "PROVIDER",
            isLocal = false
        )
        graph.registerDescriptor(desc)

        val requirements = TaskCapabilityRequirements(
            requiredCapabilities = setOf(CapabilityType.SEARCH)
        )

        // Before evidence
        val gapBefore = graph.analyzeGap(
            taskId = "task_search_01",
            requirements = requirements,
            currentlySatisfied = emptySet()
        )
        assertFalse("Cannot be marked satisfied before evidence exists (Rule 14)", gapBefore.isFullySatisfied)
        assertTrue(gapBefore.pendingCapabilities.contains(CapabilityType.SEARCH))
        assertTrue(gapBefore.candidateResourcesForPending.containsKey(CapabilityType.SEARCH))

        // After evidence
        val gapAfter = graph.analyzeGap(
            taskId = "task_search_01",
            requirements = requirements,
            currentlySatisfied = setOf(CapabilityType.SEARCH)
        )
        assertTrue("Must be marked satisfied after evidence is recorded (Rule 14)", gapAfter.isFullySatisfied)
        assertEquals(CapabilityStatus.CAPABILITY_SATISFIED, gapAfter.status)
        assertTrue(gapAfter.satisfiedCapabilities.contains(CapabilityType.SEARCH))
    }

    @Test
    fun testDegradedToolExclusion_WhenFailuresExceedThreshold() = runBlocking {
        val failingTool = object : ToolPort {
            override val declaration: ToolDeclaration = ToolDeclaration(
                name = "flaky_tool",
                description = "Frequently failing tool",
                providedCapabilities = setOf(CapabilityType.SHELL_EXECUTION),
                networkRequirement = NetworkRequirement.LOCAL_ONLY,
                locality = Locality.LOCAL_ON_DEVICE
            )

            override suspend fun execute(input: ToolInput): Outcome<ToolOutput, ToolFailure> {
                return Outcome.Error(ToolFailure.InternalExecutionError("Internal timeout"))
            }
        }

        registry.registerTool(failingTool)
        // Record 3 consecutive failures
        registry.recordFailure("flaky_tool", "timeout")
        registry.recordFailure("flaky_tool", "timeout")
        registry.recordFailure("flaky_tool", "timeout")

        val taskShell = TaskDefinition(
            id = TaskId("task_shell"),
            assignedAgentId = AgentId("default"),
            input = TaskInput("Run local shell command"),
            requirements = TaskCapabilityRequirements(
                requiredCapabilities = setOf(CapabilityType.SHELL_EXECUTION)
            )
        )

        val context = decisionService.buildDecisionContext(task = taskShell)
        val actions = decisionService.generateCandidateActions(context)

        val toolAction = actions.firstOrNull { it.type == DecisionActionType.EXECUTE_TOOL && it.targetId == "flaky_tool" }
        assertNull("Tool with >= 3 consecutive failures must be excluded from candidate actions", toolAction)
    }

    @Test
    fun testStructuredTaskProducesStructuredRequirements_WithProvenance() {
        val spec = com.example.domain.core.task.TaskSpecification(
            objective = "Analyze repository vulnerabilities",
            requirements = TaskCapabilityRequirements(
                requiredCapabilities = setOf(CapabilityType.SECURITY_AUDIT)
            ),
            provenance = com.example.domain.core.task.TaskSpecificationProvenance.STRUCTURED_EXPLICIT
        )
        val task = TaskDefinition(
            id = TaskId("spec_task_1"),
            assignedAgentId = AgentId("default"),
            input = TaskInput("Audit this codebase"),
            specification = spec
        )

        val resolved = decisionService.resolveTaskRequirements(task)
        // SECURITY_AUDIT requires CODE_ANALYSIS prerequisite
        assertTrue(resolved.requiredCapabilities.contains(CapabilityType.SECURITY_AUDIT))
        assertTrue(resolved.requiredCapabilities.contains(CapabilityType.CODE_ANALYSIS))
        assertEquals(com.example.domain.core.task.TaskSpecificationProvenance.STRUCTURED_EXPLICIT, task.specification?.provenance)
    }

    @Test
    fun testCapabilityPrerequisitesTransitiveResolution_AndCycleSafety() {
        // CODE_ENGINEERING requires CODE_ANALYSIS and TOOL_EXECUTION
        val direct = setOf(CapabilityType.CODE_ENGINEERING)
        val resolved = com.example.domain.core.capability.CapabilityPrerequisites.resolvePrerequisites(direct)
        assertTrue(resolved.resolvedCapabilities.contains(CapabilityType.CODE_ENGINEERING))
        assertTrue(resolved.resolvedCapabilities.contains(CapabilityType.CODE_ANALYSIS))
        assertTrue(resolved.resolvedCapabilities.contains(CapabilityType.TOOL_EXECUTION))
        assertFalse(resolved.hasCycles)

        // Test cycle safety with custom definition
        val cyclicMap = mapOf(
            CapabilityType.SEARCH to listOf(CapabilityType.MEMORY_RETRIEVAL),
            CapabilityType.MEMORY_RETRIEVAL to listOf(CapabilityType.SEARCH)
        )
        val cyclicResult = com.example.domain.core.capability.CapabilityPrerequisites.resolvePrerequisites(
            setOf(CapabilityType.SEARCH),
            customDefinitions = cyclicMap
        )
        assertTrue(cyclicResult.hasCycles)
        assertTrue(cyclicResult.cycleNodes.isNotEmpty())
    }

    @Test
    fun testRequiredAndOptionalCapabilitiesRemainDistinct() {
        val reqs = TaskCapabilityRequirements(
            requiredCapabilities = setOf(CapabilityType.FILE_READ),
            optionalCapabilities = setOf(CapabilityType.SEARCH)
        )
        val graph = CapabilityResourceGraph()
        val gap = graph.analyzeGap(
            taskId = "gap_opt",
            requirements = reqs,
            currentlySatisfied = setOf(CapabilityType.FILE_READ)
        )
        assertTrue("Task is satisfied when all REQUIRED are met, optional missing does not block", gap.isFullySatisfied)
        assertEquals(CapabilityStatus.CAPABILITY_SATISFIED, gap.status)
        assertFalse(gap.satisfiedCapabilities.contains(CapabilityType.SEARCH))
    }

    @Test
    fun testPartialPlan_DoesNotClaimSatisfaction() {
        val reqs = TaskCapabilityRequirements(
            requiredCapabilities = setOf(CapabilityType.FILE_READ, CapabilityType.SEARCH, CapabilityType.HASH_COMPUTATION)
        )
        val graph = CapabilityResourceGraph()
        val gap = graph.analyzeGap(
            taskId = "partial_gap",
            requirements = reqs,
            currentlySatisfied = setOf(CapabilityType.FILE_READ, CapabilityType.SEARCH)
        )
        assertFalse("A+B satisfied when A+B+C required must NOT be fully satisfied (Rule 7, 10)", gap.isFullySatisfied)
        assertEquals(setOf(CapabilityType.HASH_COMPUTATION), gap.missingCapabilities)
    }

    @Test
    fun testOutcomeVerification_EvidenceContractEnforcement() {
        val outcomeService = com.example.application.outcome.OutcomeService()
        val task = TaskDefinition(
            id = TaskId("task_eval_1"),
            assignedAgentId = AgentId("default"),
            input = TaskInput("Search for recent papers"),
            requirements = TaskCapabilityRequirements(
                requiredCapabilities = setOf(CapabilityType.SEARCH)
            )
        )

        // 1. LLM output alone without searchResults evidence -> Fails verification (Rule 14, 24)
        val reportWithoutEvidence = outcomeService.verifyTaskCompletion(
            task = task,
            accumulatedEvidence = emptyMap(),
            finalOutputText = "Here are the papers that I found online.",
            lastAction = com.example.domain.core.decision.DecisionAction(DecisionActionType.COMPLETE)
        )
        assertFalse("LLM output alone without evidence contract fulfillment must NOT verify (Rule 24)", reportWithoutEvidence.isSatisfied)

        // 2. With structured searchResults evidence -> Passes verification
        val reportWithEvidence = outcomeService.verifyTaskCompletion(
            task = task,
            accumulatedEvidence = mapOf("searchResults" to listOf("Paper 1", "Paper 2")),
            finalOutputText = "Here are the papers: Paper 1, Paper 2",
            lastAction = com.example.domain.core.decision.DecisionAction(DecisionActionType.COMPLETE)
        )
        assertTrue("Evidence satisfying the capability contract must verify successfully", reportWithEvidence.isSatisfied)
    }

    @Test
    fun testStepLevelRequirements_InWorkflowEngine() {
        val step1 = com.example.domain.core.workflow.StepNode(
            id = "s1",
            taskId = TaskId("task_s1"),
            agentRole = AgentRole.RESEARCHER,
            description = "Gather sources",
            requirements = TaskCapabilityRequirements(requiredCapabilities = setOf(CapabilityType.SEARCH))
        )
        val step2 = com.example.domain.core.workflow.StepNode(
            id = "s2",
            taskId = TaskId("task_s2"),
            agentRole = AgentRole.SECURITY_GUARD,
            description = "Audit sources",
            requirements = TaskCapabilityRequirements(requiredCapabilities = setOf(CapabilityType.SECURITY_AUDIT)),
            dependencies = setOf("s1")
        )

        val plan = com.example.domain.core.workflow.WorkflowPlan(
            id = com.example.domain.core.workflow.WorkflowId("workflow_test"),
            goal = "Test Workflow Goal",
            steps = listOf(step1, step2)
        )

        assertEquals(setOf(CapabilityType.SEARCH), plan.steps[0].requirements.requiredCapabilities)
        assertEquals(setOf(CapabilityType.SECURITY_AUDIT), plan.steps[1].requirements.requiredCapabilities)
        assertTrue(plan.steps[1].dependencies.contains("s1"))
    }
}
