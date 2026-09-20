package com.example.presentation.viewmodel

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.example.application.attachment.ChatAttachmentCoordinator
import com.example.application.artifacts.ArtifactService
import com.example.application.execution.ExecutionService
import com.example.application.extension.ExtensionManager
import com.example.application.rag.RagPipelineService
import com.example.application.registry.ComponentRegistry
import com.example.application.search.SearchIntelligenceService
import com.example.application.security.SecurityGuardService
import com.example.application.workspace.WorkspaceRuntimeService
import com.example.domain.core.Outcome
import com.example.domain.core.capability.CapabilityType
import com.example.domain.core.search.SearchQuery
import com.example.domain.core.search.SearchResultItem
import com.example.domain.core.search.SearchResultSet
import com.example.domain.core.search.SearchFailure
import com.example.domain.core.tools.ToolDeclaration
import com.example.domain.core.tools.ToolInput
import com.example.domain.core.tools.ToolOutput
import com.example.domain.ports.memory.EmbeddingProviderPort
import com.example.domain.ports.search.SearchProviderPort
import com.example.domain.ports.tools.ToolPort
import com.example.domain.core.search.SafeSearchProviderMetadata
import com.example.infrastructure.integration.IntegrationGateway
import com.example.infrastructure.mcp.McpClient
import com.example.infrastructure.memory.LocalDeterministicEmbeddingAdapter
import com.example.infrastructure.persistence.AppDatabase
import com.example.infrastructure.storage.SandboxProjectFileStore
import com.example.presentation.state.CapabilityKind
import com.example.presentation.state.ChatCapabilityKey
import com.example.presentation.state.ChatCapabilityStatus
import com.example.presentation.state.ChatEntry
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.withTimeout
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
 * ChatCapabilitiesViewModelTest — CHAT CAPABILITIES Task 2 (the capability
 * layer) — Robolectric, REAL services behind the ViewModel
 * ============================================================================
 *
 * The REQUIRED test areas covered here (Task 2 §21):
 *  1/2/3 — the catalog refresh wiring (facts from the REAL registries);
 *  4/5 — attachment selection state + removal;
 *  9/10 — tool invocation + result shape (the governed path);
 *  11 — skill invocation (registered ToolPort — the same governed path);
 *  12 — MCP invocation (the REAL in-process bridge tool);
 *  13/14 — search invocation + citation/source mapping;
 *  28 — error and degraded capability behavior.
 */
@RunWith(RobolectricTestRunner::class)
class ChatCapabilitiesViewModelTest {

    private val dispatcher = UnconfinedTestDispatcher()

    private lateinit var database: AppDatabase
    private lateinit var registry: ComponentRegistry
    private lateinit var executionService: ExecutionService
    private lateinit var permissionGrants: com.example.application.security.PermissionGrantService
    private lateinit var grantDao: FakePermissionGrantDaoForVm
    private lateinit var extensionManager: ExtensionManager
    private lateinit var ragPipelineService: RagPipelineService
    private lateinit var searchService: SearchIntelligenceService
    private lateinit var coordinator: ChatAttachmentCoordinator
    private lateinit var workspaceService: WorkspaceRuntimeService
    private lateinit var viewModel: ChatCapabilitiesViewModel
    private val extensionScope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)

    private val contentFiles = mutableMapOf<String, Pair<String, ByteArray>>()

    private val fakeContentPort = object : FileTransferContentPortFake() {
        override fun openRead(uri: String): InputStream? =
            contentFiles[uri]?.second?.let { ByteArrayInputStream(it) }

        override fun queryDisplayName(uri: String): String? = contentFiles[uri]?.first

        override fun querySize(uri: String): Long? = contentFiles[uri]?.second?.size?.toLong()
    }

    /** The fake search source — a REAL SearchProviderPort, scripted results. */
    private val scriptedSearchResults = mutableListOf<SearchResultItem>()

    private val fakeSearchProvider = object : SearchProviderPort {
        override val providerId: String = "fake_search_provider"
        override val metadata: SafeSearchProviderMetadata = SafeSearchProviderMetadata(
            id = "fake_search_provider",
            name = "Fake Search",
            providerType = "TEST",
            isConfigured = true,
            isEnabled = true,
            priority = 1
        )

        override suspend fun search(query: SearchQuery): Outcome<SearchResultSet, SearchFailure> =
            Outcome.Success(
                SearchResultSet(
                    query = query.query,
                    items = scriptedSearchResults.toList(),
                    providerId = providerId
                )
            )
    }

    @Before
    fun setUp() = runBlocking {
        Dispatchers.setMain(dispatcher)
        val context = ApplicationProvider.getApplicationContext<Context>()
        database = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        val sandboxDir = File(context.getDir("caps_workspaces", Context.MODE_PRIVATE), "projects")
        val fileStore = SandboxProjectFileStore(sandboxDir)
        val artifactService = ArtifactService(database = database, fileStore = fileStore)

        registry = ComponentRegistry()
        // The REAL governed boundary wiring (exactly as production composes
        // it): the universal admission gate + the fail-closed permission
        // grants, so tool/skill/MCP invocations run the SAME path as prod.
        grantDao = FakePermissionGrantDaoForVm()
        permissionGrants = com.example.application.security.PermissionGrantService(
            permissionGrantDao = grantDao,
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

        // REAL production executable skills (the manifests the extension
        // registry declares them under already exist by default).
        extensionManager = ExtensionManager(
            componentRegistry = registry,
            mcpClient = McpClient(),
            integrationGateway = IntegrationGateway(),
            extensionConfigDao = null,
            executableSkills = listOf(com.example.infrastructure.skills.SecurityAuditorSkill()),
            coroutineScope = extensionScope
        )

        searchService = SearchIntelligenceService(searchProvider = fakeSearchProvider)
        ragPipelineService = RagPipelineService(
            resourceRegistry = registry.resourceRegistry,
            runtimeAdapterResolver = registry.runtimeAdapterResolver,
            fallbackEmbeddingProvider = LocalDeterministicEmbeddingAdapter(),
            persistenceService = null,
            workspaceIdProvider = { "ws_caps_test" }
        )

        workspaceService = WorkspaceRuntimeService(
            workspaceDao = FakeWorkspaceDaoForVm(),
            projectDao = FakeProjectDaoForVm(),
            coroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        )
        Thread.sleep(100)
        workspaceService.createWorkspace("مساحة القدرات", "اختبار")

        coordinator = ChatAttachmentCoordinator(
            fileTransferService = com.example.application.transfer.FileTransferService(fileStore),
            artifactService = artifactService,
            workspaceRuntimeService = workspaceService,
            fileStore = fileStore,
            contentPort = fakeContentPort
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
    }

    @After
    fun tearDown() {
        extensionScope.cancel()
        Dispatchers.resetMain()
        database.close()
    }

    // ------------------------------------------------------------------
    // 1/2/3 — the availability catalog wiring (real registries → policy)
    // ------------------------------------------------------------------

    @Test
    fun `the catalog reflects the real registries and project binding`() = runBlocking {
        awaitUntil { viewModel.state.value.capabilities.isNotEmpty() }
        val state = viewModel.state.value

        // The REAL skill manifests from the extension registry (mirror, not a copy).
        assertTrue(state.skills.any { it.id == "skill_code_review_security" })
        // The real MCP bootstrap servers with their real health.
        assertTrue(state.mcpServers.any { it.id == "mcp_local_bridge" })
        // The registered tool declarations (skill + MCP bridge tools).
        assertTrue(state.tools.isNotEmpty())

        val attachFile = state.capabilities.first { it.key == ChatCapabilityKey.ATTACH_FILE }
        assertEquals(
            "an active project exists => file attach is available",
            ChatCapabilityStatus.AVAILABLE,
            attachFile.status
        )
        val skills = state.capabilities.first { it.key == ChatCapabilityKey.SKILLS }
        assertEquals(ChatCapabilityStatus.AVAILABLE, skills.status)
        val mcp = state.capabilities.first { it.key == ChatCapabilityKey.MCP_SERVERS }
        assertEquals(ChatCapabilityStatus.AVAILABLE, mcp.status)
        // Vision stays PLANNED (§7 — implemented=false in the radar truth).
        assertEquals(
            ChatCapabilityStatus.PLANNED,
            state.capabilities.first { it.key == ChatCapabilityKey.VISION_ANALYSIS }.status
        )
        // Knowledge availability follows the REAL corpus (the pipeline ships
        // bootstrapped default documents — honest, they are real content).
        awaitUntil { state.knowledgeDocumentCount > 0 || viewModel.state.value.knowledgeDocumentCount > 0 }
        assertTrue(
            "the corpus is seeded => knowledge retrieval is available",
            viewModel.state.value.knowledgeDocumentCount > 0
        )
        assertEquals(
            ChatCapabilityStatus.AVAILABLE,
            viewModel.state.value.capabilities
                .first { it.key == ChatCapabilityKey.KNOWLEDGE_RETRIEVAL }.status
        )
    }

    // ------------------------------------------------------------------
    // 4/5 — attachment selection state + removal
    // ------------------------------------------------------------------

    @Test
    fun `a scope change drops the stale drafts and re-resolves the catalog (§21 freshness)`() = runBlocking {
        awaitUntil { viewModel.state.value.capabilities.isNotEmpty() }

        // Stage a draft in the CURRENT project's sandbox.
        contentFiles["content://saf/stale.txt"] = "stale.txt" to "بيانات قديمة".toByteArray()
        viewModel.pickFiles(listOf("content://saf/stale.txt"), listOf("text/plain"))
        awaitUntil { viewModel.state.value.attachmentDrafts.size == 1 }
        awaitUntil { !viewModel.state.value.isImportingAttachment }
        val draft = viewModel.state.value.attachmentDrafts.single()
        assertNotNull(draft.artifactId)

        // Switch the active project under the composer (the workspace runtime's
        // OWN flow — the collector must fire, not a UI callback).
        val daoField = WorkspaceRuntimeService::class.java.getDeclaredField("projectDao")
        daoField.isAccessible = true
        val dao = daoField.get(workspaceService) as FakeProjectDaoForVm
        val entity = com.example.infrastructure.persistence.entities.ProjectEntity(
            name = "مشروع آخر",
            description = "",
            rootPath = "",
            createdAtEpochMs = System.currentTimeMillis(),
            updatedAtEpochMs = System.currentTimeMillis(),
            workspaceId = workspaceService.activeWorkspaceIdOrNull()!!
        )
        dao.stored[777L] = entity.copy(id = 777L)
        workspaceService.setActiveProject(777L)

        // §21: the drafts are GONE (they belong to the previous project's
        // sandbox — their imported files went with them, §13).
        awaitUntil { viewModel.state.value.attachmentDrafts.isEmpty() }
        // The catalog re-resolved under the new scope (the same availability,
        // re-derived — not a stale snapshot).
        awaitUntil { viewModel.state.value.capabilities.isNotEmpty() }
        val attachFile = viewModel.state.value.capabilities
            .first { it.key == ChatCapabilityKey.ATTACH_FILE }
        assertEquals(
            "the new project is active => attach is still available (fresh facts)",
            ChatCapabilityStatus.AVAILABLE,
            attachFile.status
        )
    }

    @Test
    fun `picked files stage as drafts and can be removed before send`() = runBlocking {
        contentFiles["content://saf/a.txt"] = "a.txt" to "محتوى أ".toByteArray()
        contentFiles["content://saf/b.md"] = "b.md" to "محتوى ب".toByteArray()

        viewModel.pickFiles(
            listOf("content://saf/a.txt", "content://saf/b.md"),
            listOf("text/plain", "text/markdown")
        )
        awaitUntil { viewModel.state.value.attachmentDrafts.size == 2 }
        awaitUntil { !viewModel.state.value.isImportingAttachment }

        val drafts = viewModel.state.value.attachmentDrafts
        assertEquals(listOf("a.txt", "b.md"), drafts.map { it.name })
        assertTrue(drafts.all { it.artifactId != null })

        viewModel.removeAttachment(drafts.first().id)
        // FUNCTIONAL CLOSURE (§13): removal is now a REAL cleanup (artifact row
        // + sandbox file) — asynchronous by nature, so wait for the draft to
        // actually leave the composer state.
        awaitUntil { viewModel.state.value.attachmentDrafts.size == 1 }
        assertEquals(listOf("b.md"), viewModel.state.value.attachmentDrafts.map { it.name })

        viewModel.clearAttachmentDrafts()
        assertTrue(viewModel.state.value.attachmentDrafts.isEmpty())
    }

    @Test
    fun `a vanished source surfaces the honest attachment error`() = runBlocking {
        // Nothing registered under this uri — the fake port reports it gone.
        viewModel.pickFiles(listOf("content://saf/ghost.txt"), listOf("text/plain"))
        awaitUntil { !viewModel.state.value.isImportingAttachment }

        assertTrue(
            "the honest import failure is shown",
            viewModel.state.value.attachmentError?.contains("تعذر فتح الملف") == true
        )
        assertTrue("no phantom draft is staged", viewModel.state.value.attachmentDrafts.isEmpty())
    }

    // ------------------------------------------------------------------
    // 9/10 — tool invocation + result shape (the governed path)
    // ------------------------------------------------------------------

    @Test
    fun `a registered tool executes through the governed path and lands as a block`() = runBlocking {
        registry.registerTool(fakeTool("tool_test_echo") { input ->
            Outcome.Success(ToolOutput(content = "أداة ردت: ${input.arguments["value"]}"))
        })
        viewModel.refreshCapabilities()

        val results = mutableListOf<ChatEntry.CapabilityResult>()
        viewModel.invokeTool(
            toolName = "tool_test_echo",
            argumentsJson = """{"value":"مرحبا"}""",
            agent = null,
            isMcp = false
        ) { results += it }

        awaitUntil { results.isNotEmpty() }
        val block = results.single()
        assertEquals(CapabilityKind.TOOL, block.kind)
        assertEquals("tool_test_echo", block.title)
        assertTrue(block.isSuccessful)
        assertEquals("أداة ردت: مرحبا", block.detail)
    }

    @Test
    fun `an unknown tool fails honestly with no fake success`() = runBlocking {
        val results = mutableListOf<ChatEntry.CapabilityResult>()
        viewModel.invokeTool(
            toolName = "tool_that_does_not_exist",
            argumentsJson = "{}",
            agent = null,
            isMcp = false
        ) { results += it }

        awaitUntil { results.isNotEmpty() }
        val block = results.single()
        assertFalse(block.isSuccessful)
        assertNotNull(block.summary)
    }

    // ------------------------------------------------------------------
    // 11 — skill invocation (registered ToolPort = the same governed path)
    // ------------------------------------------------------------------

    @Test
    fun `a registered skill executes and lands as a SKILL block`() = runBlocking {
        val results = mutableListOf<ChatEntry.CapabilityResult>()
        viewModel.invokeTool(
            toolName = "skill_code_review_security",
            argumentsJson = """{"content":"password = \"sk-live-123\""}""",
            agent = null,
            isMcp = false
        ) { results += it }

        awaitUntil { results.isNotEmpty() }
        val block = results.single()
        assertEquals(CapabilityKind.SKILL, block.kind)
        assertTrue("the real SecurityAuditorSkill ran: ${block.summary}", block.isSuccessful)
        assertTrue(block.detail!!.isNotBlank())
    }

    // ------------------------------------------------------------------
    // 12 — MCP invocation (the REAL in-process bridge tool)
    // ------------------------------------------------------------------

    @Test
    fun `the healthy local MCP bridge tool executes through the governed path`() = runBlocking {
        // MCP tools are SENSITIVE (isMcp ⇒ fail-closed permission path) —
        // grant EXECUTE once (the same standing consent production's
        // "allow always" records) so the governed path can run it.
        runBlocking {
            permissionGrants.grant(
                principalType = com.example.domain.core.security.governance.PrincipalType.AGENT,
                principalId = com.example.application.session.ConversationSessionService.QUICK_CHAT_AGENT_ID,
                resourceType = com.example.domain.core.security.governance.SecurableResourceType.TOOL,
                resourceId = "mcp_local_bridge__system_diagnostics",
                permission = com.example.domain.core.security.governance.Permission.EXECUTE,
                grantedBy = "test"
            )
        }
        val results = mutableListOf<ChatEntry.CapabilityResult>()
        viewModel.invokeTool(
            toolName = "mcp_local_bridge__system_diagnostics",
            argumentsJson = "{}",
            agent = null,
            isMcp = true
        ) { results += it }

        awaitUntil { results.isNotEmpty() }
        val block = results.single()
        assertEquals(CapabilityKind.MCP, block.kind)
        assertTrue("the real in-process diagnostics ran: ${block.summary}", block.isSuccessful)
        assertTrue("diagnostics detail is real runtime data", !block.detail.isNullOrBlank())
    }

    @Test
    fun `an MCP tool WITHOUT a grant is denied honestly (fail-closed)`() = runBlocking {
        val results = mutableListOf<ChatEntry.CapabilityResult>()
        viewModel.invokeTool(
            toolName = "mcp_local_bridge__workspace_summary",
            argumentsJson = "{}",
            agent = null,
            isMcp = true
        ) { results += it }

        awaitUntil { results.isNotEmpty() }
        val block = results.single()
        assertEquals(CapabilityKind.MCP, block.kind)
        assertFalse("sensitive MCP tools require recorded consent", block.isSuccessful)
        assertTrue(
            "the honest governance reason surfaces",
            block.summary.contains("حساسة") || block.detail.orEmpty().contains("إذن")
        )
    }

    // ------------------------------------------------------------------
    // 13/14 — search invocation + citation/source mapping
    // ------------------------------------------------------------------

    @Test
    fun `search runs the real pipeline and maps the citations into sources`() = runBlocking {
        scriptedSearchResults += listOf(
            SearchResultItem("نتيجة أولى", "https://example.com/1", "مقتطف أول"),
            SearchResultItem("نتيجة ثانية", "https://example.com/2", "مقتطف ثانٍ")
        )

        val results = mutableListOf<ChatEntry.CapabilityResult>()
        viewModel.invokeSearch("استعلام الاختبار", agent = null) { results += it }

        awaitUntil { results.isNotEmpty() }
        val block = results.single()
        assertEquals(CapabilityKind.SEARCH, block.kind)
        assertTrue(block.isSuccessful)
        assertTrue(block.summary.contains("2"))
        // The REAL citation chains became collapsible sources.
        val urls = block.sources.map { it.url }
        assertTrue(urls.contains("https://example.com/1"))
        assertTrue(urls.contains("https://example.com/2"))
        // The pipeline's source id (the multi-source adapter identity).
        assertTrue(block.sources.all { !it.providerId.isNullOrBlank() })
    }

    @Test
    fun `an empty search result is honest, not an error`() = runBlocking {
        val results = mutableListOf<ChatEntry.CapabilityResult>()
        viewModel.invokeSearch("لا شيء", agent = null) { results += it }

        awaitUntil { results.isNotEmpty() }
        val block = results.single()
        assertTrue(block.isSuccessful)
        assertTrue(block.summary.contains("لا نتائج"))
        assertTrue(block.sources.isEmpty())
    }

    // ------------------------------------------------------------------
    // 12/28 — knowledge retrieval (real pipeline, honest states)
    // ------------------------------------------------------------------

    @Test
    fun `knowledge retrieval is honest on an empty corpus`() = runBlocking {
        val results = mutableListOf<ChatEntry.CapabilityResult>()
        viewModel.invokeKnowledgeRetrieval("أي شيء") { results += it }

        awaitUntil { results.isNotEmpty() }
        val block = results.single()
        assertEquals(CapabilityKind.KNOWLEDGE_RETRIEVAL, block.kind)
        assertTrue(block.isSuccessful)
        assertTrue(block.summary.contains("لا توجد مقاطع"))
    }

    @Test
    fun `knowledge retrieval returns the real chunks after an ingest`() = runBlocking {
        val before = viewModel.state.value.knowledgeDocumentCount
        ragPipelineService.ingestDocument(
            title = "وثيقة المعرفة",
            content = "هذه وثيقة تحتوي معلومات فريدة عن الديناصورات الطائرة في العصر الجوراسي.",
            sourceUri = "workspace://docs/test.md",
            tags = emptyList(),
            projectId = null
        )
        awaitUntil { viewModel.state.value.knowledgeDocumentCount > before }
        // The availability followed the REAL corpus.
        assertEquals(
            ChatCapabilityStatus.AVAILABLE,
            viewModel.state.value.capabilities
                .first { it.key == ChatCapabilityKey.KNOWLEDGE_RETRIEVAL }.status
        )

        val results = mutableListOf<ChatEntry.CapabilityResult>()
        viewModel.invokeKnowledgeRetrieval("الديناصورات الطائرة") { results += it }

        awaitUntil { results.isNotEmpty() }
        val block = results.single()
        assertTrue(block.isSuccessful)
        assertTrue(block.summary.contains("تم استرجاع"))
        assertTrue(block.detail!!.contains("وثيقة المعرفة"))
    }

    // ------------------------------------------------------------------
    // 28 — degraded capability behavior
    // ------------------------------------------------------------------

    @Test
    fun `a degraded tool outcome is surfaced as degraded, not as success`() = runBlocking {
        registry.registerTool(fakeTool("tool_test_degraded") {
            Outcome.Degraded(
                partialValue = ToolOutput(content = "نتيجة جزئية"),
                reason = com.example.domain.core.DegradedReason.TOOL_WARNING,
                diagnosticMessage = "تحذير أداة"
            )
        })
        viewModel.refreshCapabilities()

        val results = mutableListOf<ChatEntry.CapabilityResult>()
        viewModel.invokeTool("tool_test_degraded", "{}", null, false) { results += it }

        awaitUntil { results.isNotEmpty() }
        val block = results.single()
        assertTrue("degraded is still a usable outcome", block.isSuccessful)
        assertTrue(block.isDegraded)
        assertNotNull(block.degradedMessage)
    }

    // ------------------------------------------------------------------
    // Helpers
    // ------------------------------------------------------------------

    private fun fakeTool(
        name: String,
        behavior: suspend (ToolInput) -> Outcome<ToolOutput, com.example.domain.core.tools.ToolFailure>
    ): ToolPort = object : ToolPort {
        override val declaration: ToolDeclaration = ToolDeclaration(
            name = name,
            description = "أداة اختبار: $name"
        )

        override suspend fun execute(input: ToolInput): Outcome<ToolOutput, com.example.domain.core.tools.ToolFailure> =
            behavior(input)
    }

    private fun awaitUntil(
        timeoutMs: Long = 15_000,
        condition: () -> Boolean
    ) {
        val start = System.currentTimeMillis()
        while (!condition() && System.currentTimeMillis() - start < timeoutMs) {
            Thread.sleep(20)
        }
        assertTrue("Timed out waiting for the condition", condition())
    }
}

/** The fake content port base (only the used members overridden per test). */
private abstract class FileTransferContentPortFake : com.example.application.transfer.FileTransferService.ContentPort {
    override fun openRead(uri: String): InputStream? = null
    override fun openWrite(uri: String): java.io.OutputStream? = null
    override fun queryDisplayName(uri: String): String? = null
    override fun querySize(uri: String): Long? = null
}
