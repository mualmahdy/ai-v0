package com.example.presentation.viewmodel

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.example.application.artifacts.ArtifactService
import com.example.application.attachment.ChatAttachmentCoordinator
import com.example.application.execution.ExecutionService
import com.example.application.extension.ExtensionManager
import com.example.application.rag.RagPipelineService
import com.example.application.registry.ComponentRegistry
import com.example.application.search.SearchIntelligenceService
import com.example.application.security.SecurityGuardService
import com.example.application.workspace.WorkspaceRuntimeService
import com.example.domain.core.Outcome
import com.example.domain.core.capability.CapabilityType
import com.example.domain.core.execution.ExecutionScope
import com.example.domain.core.search.SafeSearchProviderMetadata
import com.example.domain.core.search.SearchFailure
import com.example.domain.core.search.SearchQuery
import com.example.domain.core.search.SearchResultItem
import com.example.domain.core.search.SearchResultSet
import com.example.domain.core.tools.ToolDeclaration
import com.example.domain.core.tools.ToolInput
import com.example.domain.core.tools.ToolOutput
import com.example.domain.core.tools.ToolParameter
import com.example.domain.ports.search.SearchProviderPort
import com.example.domain.ports.tools.ToolPort
import com.example.infrastructure.integration.IntegrationGateway
import com.example.infrastructure.mcp.McpClient
import com.example.infrastructure.memory.LocalDeterministicEmbeddingAdapter
import com.example.infrastructure.persistence.AppDatabase
import com.example.infrastructure.persistence.entities.ProjectEntity
import com.example.infrastructure.storage.SandboxProjectFileStore
import com.example.presentation.state.ChatEntry
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.ByteArrayInputStream
import java.io.File
import java.io.InputStream

/**
 * ============================================================================
 * ChatCapabilitiesScopePinningTest — CHAT FINAL CLOSURE (§4/§5) regression
 * proofs for the capability layer's invocation SCOPE SNAPSHOT
 * ============================================================================
 *
 * The scope (workspace + project) is captured SYNCHRONOUSLY AT ACCEPTANCE —
 * a project/workspace switch that lands between the user's tap and the
 * coroutine's dispatch can neither re-target a tool's sandbox, re-scope a
 * direct search, nor re-scope a knowledge retrieval.
 *
 * Determinism: a [StandardTestDispatcher] main — the invocation coroutine is
 * QUEUED by the tap and only RUNS when the test advances the scheduler AFTER
 * switching the live scope. With the pre-fix code (scope read inside the
 * coroutine) these tests FAIL: the invocation sees the NEW scope; with the
 * pinned snapshot they pass: the invocation sees the acceptance scope.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class ChatCapabilitiesScopePinningTest {

    private val dispatcher = StandardTestDispatcher()

    private lateinit var database: AppDatabase
    private lateinit var registry: ComponentRegistry
    private lateinit var executionService: ExecutionService
    private lateinit var extensionManager: ExtensionManager
    private lateinit var ragPipelineService: RagPipelineService
    private lateinit var searchService: SearchIntelligenceService
    private lateinit var coordinator: ChatAttachmentCoordinator
    private lateinit var workspaceService: WorkspaceRuntimeService
    private lateinit var projectDao: FakeProjectDaoForVm
    private lateinit var viewModel: ChatCapabilitiesViewModel
    private val extensionScope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)

    /** The ExecutionScope the RECORDING search provider saw (null if none). */
    @Volatile
    private var searchSeenScope: ExecutionScope? = null

    /** The ExecutionScope the RECORDING tool saw (null if none). */
    @Volatile
    private var toolSeenScope: ExecutionScope? = null

    private val fakeSearchProvider = object : SearchProviderPort {
        override val providerId: String = "scope_pinned_search"
        override val metadata: SafeSearchProviderMetadata = SafeSearchProviderMetadata(
            id = "scope_pinned_search",
            name = "Scope Pinned Search",
            providerType = "TEST",
            isConfigured = true,
            isEnabled = true,
            priority = 1
        )

        override suspend fun search(query: SearchQuery): Outcome<SearchResultSet, SearchFailure> {
            searchSeenScope = currentCoroutineContext()[ExecutionScope.Key]
            return Outcome.Success(
                SearchResultSet(
                    query = query.query,
                    items = listOf(
                        SearchResultItem(
                            title = "نتيجة",
                            url = "https://example.com/result",
                            snippet = "snippet"
                        )
                    ),
                    providerId = providerId
                )
            )
        }
    }

    /** A real ToolPort that records the ExecutionScope it executes under. */
    private val recordingTool = object : ToolPort {
        override val declaration: ToolDeclaration = ToolDeclaration(
            name = "scope_recorder_tool",
            description = "يسجل نطاق التنفيذ",
            parameters = listOf(
                ToolParameter(
                    name = "note",
                    type = "string",
                    description = "ملاحظة",
                    isRequired = false
                )
            ),
            isSensitive = false,
            requiresHumanConsent = false,
            providedCapabilities = setOf(CapabilityType.TOOL_EXECUTION)
        )

        override suspend fun execute(input: ToolInput): Outcome<ToolOutput, com.example.domain.core.tools.ToolFailure> {
            toolSeenScope = currentCoroutineContext()[ExecutionScope.Key]
            return Outcome.Success(ToolOutput(content = "تم"))
        }
    }

    /** Minimal ContentPort double (the attachment seam — unused paths). */
    private val contentPortFake = object : com.example.application.transfer.FileTransferService.ContentPort {
        override fun openRead(uri: String): InputStream? = ByteArrayInputStream(ByteArray(0))
        override fun openWrite(uri: String): java.io.OutputStream? = null
        override fun queryDisplayName(uri: String): String? = null
        override fun querySize(uri: String): Long? = null
    }

    @Before
    fun setUp() = runBlocking {
        Dispatchers.setMain(dispatcher)
        val context = ApplicationProvider.getApplicationContext<Context>()
        database = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        val sandboxDir = File(context.getDir("scope_pin_workspaces", Context.MODE_PRIVATE), "projects")
        val fileStore = SandboxProjectFileStore(sandboxDir)
        val artifactService = ArtifactService(database = database, fileStore = fileStore)

        registry = ComponentRegistry()
        registry.registerTool(recordingTool)

        val permissionGrants = com.example.application.security.PermissionGrantService(
            permissionGrantDao = FakePermissionGrantDaoForVm(),
            telemetryPort = NoopTelemetryForVm
        )
        executionService = ExecutionService(
            runtimeAdapterResolver = registry.runtimeAdapterResolver,
            resourceRegistry = registry.resourceRegistry,
            securityGuard = SecurityGuardService(),
            permissionGrantService = permissionGrants,
            admissionControl = com.example.application.governed.GovernedPipelineFactory
                .admissionForRegistry(registry)
        )

        extensionManager = ExtensionManager(
            componentRegistry = registry,
            mcpClient = McpClient(),
            integrationGateway = IntegrationGateway(),
            extensionConfigDao = null,
            executableSkills = emptyList(),
            coroutineScope = extensionScope
        )

        searchService = SearchIntelligenceService(searchProvider = fakeSearchProvider)
        ragPipelineService = RagPipelineService(
            resourceRegistry = registry.resourceRegistry,
            runtimeAdapterResolver = registry.runtimeAdapterResolver,
            fallbackEmbeddingProvider = LocalDeterministicEmbeddingAdapter(),
            persistenceService = null,
            workspaceIdProvider = { "ws_scope_pin" }
        )

        projectDao = FakeProjectDaoForVm()
        workspaceService = WorkspaceRuntimeService(
            workspaceDao = FakeWorkspaceDaoForVm(),
            projectDao = projectDao,
            coroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        )
        Thread.sleep(100)
        workspaceService.createWorkspace("مساحة تثبيت النطاق", "اختبار")
        bindNewActiveProject("مشروع أ")

        coordinator = ChatAttachmentCoordinator(
            fileTransferService = com.example.application.transfer.FileTransferService(fileStore),
            artifactService = artifactService,
            workspaceRuntimeService = workspaceService,
            fileStore = fileStore,
            contentPort = contentPortFake
        )

        viewModel = ChatCapabilitiesViewModel(
            extensionManager = extensionManager,
            componentRegistry = registry,
            searchIntelligenceService = searchService,
            ragPipelineService = ragPipelineService,
            attachmentCoordinator = coordinator,
            workspaceRuntimeService = workspaceService,
            executionService = executionService,
            capabilityRadarService = null,
            networkMonitorProvider = null
        )
        // Settle the init-block collectors (StandardTestDispatcher queues them).
        dispatcher.scheduler.advanceUntilIdle()
    }

    @After
    fun tearDown() {
        extensionScope.cancel()
        Dispatchers.resetMain()
        database.close()
    }

    /** Creates + binds a NEW active project in the active workspace. */
    private fun bindNewActiveProject(name: String): Long {
        val workspaceId = runBlocking { workspaceService.activeWorkspaceIdOrNull() }
        val id = runBlocking {
            projectDao.insertProject(
                ProjectEntity(
                    id = 0L,
                    name = name,
                    description = null,
                    rootPath = "projects/$name",
                    createdAtEpochMs = System.currentTimeMillis(),
                    updatedAtEpochMs = System.currentTimeMillis(),
                    isArchived = false,
                    workspaceId = workspaceId
                )
            )
        }
        runBlocking { workspaceService.setActiveProject(id) }
        return id
    }

    /**
     * The acceptance-time queue is drained, the pipeline hops to REAL
     * Dispatchers.IO/Default threads, and its continuation re-queues on the
     * test scheduler — so the wait ALTERNATES scheduler advances with real
     * sleeps until the observable condition holds.
     */
    private fun awaitUntilAdvanced(
        timeoutMs: Long = 15_000,
        condition: () -> Boolean
    ) {
        val start = System.currentTimeMillis()
        while (!condition() && System.currentTimeMillis() - start < timeoutMs) {
            dispatcher.scheduler.advanceUntilIdle()
            if (condition()) break
            Thread.sleep(20)
        }
        assertTrue("Timed out waiting for the condition (pipeline stalled?)", condition())
    }

    // ------------------------------------------------------------------
    // §4 — standalone tool invocation scope pinning
    // ------------------------------------------------------------------

    @Test
    fun `a tool invoked in project A still executes under project A after the live project switched to B`() {
        val projectA = runBlocking { workspaceService.activeProjectIdOrNull() }
        assertNotNull(projectA)

        // The invocation is ACCEPTED (queued on the test scheduler)…
        viewModel.invokeTool(
            toolName = "scope_recorder_tool",
            argumentsJson = "{}",
            agent = null,
            isMcp = false,
            sessionId = "sess_scope_pin"
        ) { }

        // …the live scope switches to project B BEFORE the coroutine runs…
        val projectB = bindNewActiveProject("مشروع ب")
        assertTrue(projectA != projectB)
        awaitUntilAdvanced { toolSeenScope != null }

        // …and the tool STILL executed under the pinned PROJECT A scope (the
        // FileSystemTool resolveProjectId contract: pinned scope first).
        assertNotNull("the tool must run INSIDE an ExecutionScope", toolSeenScope)
        assertEquals(
            "the pinned project at acceptance wins over the live switch",
            projectA,
            toolSeenScope?.projectId
        )
        assertEquals("sess_scope_pin", toolSeenScope?.sessionId)
    }

    // ------------------------------------------------------------------
    // §5 — direct search scope pinning
    // ------------------------------------------------------------------

    @Test
    fun `a search invoked under workspace A keeps its scope snapshot after a workspace switch`() {
        val workspaceA = runBlocking { workspaceService.activeWorkspaceIdOrNull() }

        viewModel.invokeSearch(query = "اختبار النطاق", agent = null, sessionId = "sess_scope_pin") { }

        // The live workspace moves on BEFORE the coroutine runs.
        runBlocking { workspaceService.createWorkspace("مساحة أخرى", "لاحقاً") }
        awaitUntilAdvanced { searchSeenScope != null }

        val seen = searchSeenScope
        assertNotNull("the search pipeline must run INSIDE an ExecutionScope", seen)
        assertEquals(
            "the pinned workspace at acceptance wins over the live switch",
            workspaceA,
            seen?.workspaceId
        )
        assertEquals("sess_scope_pin", seen?.sessionId)
    }

    // ------------------------------------------------------------------
    // §5 — knowledge retrieval (RAG) scope pinning (observable isolation)
    // ------------------------------------------------------------------

    @Test
    fun `a knowledge retrieval pinned to project A never returns project B private knowledge after a live switch`() {
        val projectA = runBlocking { workspaceService.activeProjectIdOrNull() }
        assertNotNull(projectA)

        // Private knowledge for sibling projects:
        //  - A's private doc (visible to a retrieval pinned to A)
        //  - B's private doc (MUST stay invisible to a retrieval pinned to A)
        runBlocking {
            ragPipelineService.ingestDocument(
                title = "مستند المشروع أ",
                content = "محتوى حصري للمشروع أ: تفاصيل الخطة السرية ألف",
                sourceUri = "mem://a",
                projectId = projectA
            )
        }
        val projectB = bindNewActiveProject("مشروع ب")
        runBlocking {
            ragPipelineService.ingestDocument(
                title = "مستند المشروع ب",
                content = "محتوى حصري للمشروع ب: تفاصيل الخطة السرية باء",
                sourceUri = "mem://b",
                projectId = projectB
            )
        }
        // Re-bind project A as the ACTIVE project — the retrieval below is
        // ACCEPTED under project A's scope.
        runBlocking { workspaceService.setActiveProject(projectA) }

        // The retrieval is ACCEPTED while project A is active…
        var result: ChatEntry.CapabilityResult? = null
        viewModel.invokeKnowledgeRetrieval(query = "تفاصيل الخطة السرية", sessionId = "sess_scope_pin") { entry ->
            result = entry
        }

        // …then the LIVE project switches to B BEFORE the coroutine runs —
        // the pre-fix code ran UNPINNED (no ExecutionScope ⇒ no project
        // filter ⇒ B's private doc leaked); the pinned snapshot keeps the
        // retrieval scoped to the ACCEPTANCE project A.
        awaitUntilAdvanced { result != null }

        assertNotNull(result)
        assertTrue(result!!.isSuccessful)
        val detail = result!!.detail.orEmpty()
        assertTrue(
            "A's own private knowledge is retrievable under its pinned scope",
            detail.contains("المشروع أ")
        )
        assertFalse(
            "B's private knowledge must NEVER leak into a retrieval pinned to A",
            detail.contains("المشروع ب")
        )
    }
}
