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
    fun testToolSelection_StrictlyCapabilityDriven() {
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
    fun testDecisionService_CapabilityGapBlockNotification() {
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
    fun testDegradedToolExclusion_WhenFailuresExceedThreshold() {
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
}
