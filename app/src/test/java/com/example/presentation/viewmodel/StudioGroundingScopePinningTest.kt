package com.example.presentation.viewmodel

import android.content.Context
import androidx.lifecycle.viewModelScope
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.example.application.artifacts.ArtifactService
import com.example.application.decision.DecisionService
import com.example.application.orchestration.AgentOrchestrator
import com.example.application.registry.ComponentRegistry
import com.example.application.security.SecurityGuardService
import com.example.application.session.ConversationSessionService
import com.example.application.testing.TestResourceRegistration
import com.example.application.transfer.FileTransferService
import com.example.application.usecases.ExecuteAgentTaskUseCase
import com.example.application.workspace.WorkspaceRuntimeService
import com.example.domain.core.Outcome
import com.example.domain.core.events.ExecutionEvent
import com.example.domain.core.llm.LlmFailure
import com.example.domain.core.llm.LlmRequest
import com.example.domain.core.llm.LlmResponse
import com.example.domain.core.llm.SafeProviderMetadata
import com.example.domain.core.llm.TokenUsage
import com.example.domain.core.session.ConversationSessionId
import com.example.domain.core.session.TurnAttachment
import com.example.domain.core.workspace.Workspace
import com.example.domain.ports.llm.LlmProviderPort
import com.example.infrastructure.persistence.AppDatabase
import com.example.infrastructure.storage.SandboxProjectFileStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestCoroutineScheduler
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
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
 * StudioGroundingScopePinningTest — CHAT FUNCTIONAL RESIDUAL CLOSURE P5
 * ============================================================================
 *
 * P5: the attachment grounding digest must be built from the SAME pinned
 * context the execution uses. The scope (workspace + project) is captured
 * SYNCHRONOUSLY when the send is accepted — a project switch that lands
 * between acceptance and the grounding coroutine (the window the production
 * dispatchers allow) can neither ground another project's sandbox nor pin
 * the execution into the wrong project.
 *
 * This proof uses a [StandardTestDispatcher] as Main so the send coroutine
 * is QUEUED (not run eagerly): the test switches the active project
 * deterministically BETWEEN acceptance and the grounding build — exactly
 * the interleave the production dispatcher model permits.
 *
 * The stack is REAL (Robolectric): Room artifacts, the real sandbox file
 * store + transfer service, the real governed kernel — mock only at the
 * LLM port (the repo's convention).
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class StudioGroundingScopePinningTest {

    private val scheduler = TestCoroutineScheduler()
    private val mainDispatcher = StandardTestDispatcher(scheduler)

    private val signalBus = MutableSharedFlow<StudioSignal>(extraBufferCapacity = 256)

    private lateinit var context: Context
    private lateinit var database: AppDatabase
    private lateinit var registry: ComponentRegistry
    private lateinit var executeAgentTaskUseCase: ExecuteAgentTaskUseCase
    private lateinit var workspaceService: WorkspaceRuntimeService
    private lateinit var repository: FakeConversationSessionRepositoryForVm
    private lateinit var sessionService: ConversationSessionService
    private lateinit var coordinator: com.example.application.attachment.ChatAttachmentCoordinator
    private lateinit var viewModel: StudioViewModel
    private lateinit var activeWorkspace: Workspace

    /** LLM requests captured from the mock provider. */
    private val capturedRequests = mutableListOf<LlmRequest>()

    /** The drafts handed back by an aborted send (§14 callback recorder). */
    private val abortedDrafts = mutableListOf<List<TurnAttachment>>()

    /** The fake SAF source (exactly where the ContentPort doc says fakes go). */
    private val contentFiles = mutableMapOf<String, Pair<String, ByteArray>>()

    private val fakeContentPort = object : FileTransferService.ContentPort {
        override fun openRead(uri: String): InputStream? =
            contentFiles[uri]?.second?.let { ByteArrayInputStream(it) }

        override fun openWrite(uri: String): java.io.OutputStream? = null

        override fun queryDisplayName(uri: String): String? = contentFiles[uri]?.first

        override fun querySize(uri: String): Long? = contentFiles[uri]?.second?.size?.toLong()
    }

    @Before
    fun setUp() = runBlocking {
        Dispatchers.setMain(mainDispatcher)

        context = ApplicationProvider.getApplicationContext<Context>()
        database = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()

        // --- REAL governed execution kernel (mock only at the LLM port) ---
        registry = ComponentRegistry()
        val securityGuard = SecurityGuardService()
        val decisionService = DecisionService(
            com.example.domain.core.decision.CbrMdpEngine(),
            registry,
            securityGuard
        )
        val orchestrator = AgentOrchestrator(registry, securityGuard, decisionService)
        executeAgentTaskUseCase = ExecuteAgentTaskUseCase(orchestrator)

        val workspaceDao = FakeWorkspaceDaoForVm()
        val projectDao = FakeProjectDaoForVm()
        workspaceService = WorkspaceRuntimeService(
            workspaceDao = workspaceDao,
            projectDao = projectDao,
            coroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        )
        Thread.sleep(100) // let the default bootstrap settle
        activeWorkspace = workspaceService.createWorkspace("مساحة التأريض", "اختبار")
        orchestrator.workspaceIdProvider = { activeWorkspace.id }

        val mockProvider = object : LlmProviderPort {
            override val providerId: String = "mock_grounding_provider"
            override val metadata: SafeProviderMetadata = SafeProviderMetadata(
                id = "mock_grounding_provider",
                name = "Mock Grounding",
                providerType = "MOCK",
                defaultModel = "grounding-mock-v1",
                isConfigured = true,
                isOnline = true,
                isLocal = true,
                supportedCapabilities = listOf("grounding-mock-v1")
            )

            override suspend fun generate(request: LlmRequest): Outcome<LlmResponse, LlmFailure> =
                Outcome.Success(
                    LlmResponse(
                        text = "جواب فوري",
                        toolCalls = emptyList(),
                        usage = TokenUsage(10, 20),
                        finishReason = "STOP",
                        modelId = "grounding-mock-v1"
                    )
                )

            override fun stream(request: LlmRequest, executionId: String): Flow<ExecutionEvent> = flow {
                capturedRequests += request
                emit(ExecutionEvent.ContentChunk(executionId, "الجواب ", sequenceIndex = 0))
                emit(ExecutionEvent.Completed(executionId, "الجواب الكامل", totalDurationMs = 30))
            }
        }
        TestResourceRegistration.registerLlmProvider(
            registry,
            mockProvider,
            capabilities = setOf(
                com.example.domain.core.capability.CapabilityType.LLM_GENERATION,
                com.example.domain.core.capability.CapabilityType.STREAMING
            ),
            isLocal = true
        )

        // --- REAL attachment stack (sandbox + transfer + artifacts) ---
        val sandboxDir = File(context.getDir("grounding_workspaces", Context.MODE_PRIVATE), "projects")
        val fileStore = SandboxProjectFileStore(sandboxDir)
        val artifactService = ArtifactService(database = database, fileStore = fileStore)
        coordinator = com.example.application.attachment.ChatAttachmentCoordinator(
            fileTransferService = FileTransferService(fileStore),
            artifactService = artifactService,
            workspaceRuntimeService = workspaceService,
            fileStore = fileStore,
            contentPort = fakeContentPort
        )

        repository = FakeConversationSessionRepositoryForVm()
        repository.activeWorkspaceId = activeWorkspace.id
        sessionService = ConversationSessionService(
            repository = repository,
            workspaceIdProvider = { repository.activeWorkspaceId }
        )

        viewModel = StudioViewModel(
            executeAgentTaskUseCase = executeAgentTaskUseCase,
            componentRegistry = registry,
            workspaceRuntimeService = workspaceService,
            conversationSessionService = sessionService,
            networkMonitorProvider = null,
            appContext = null,
            signalBus = signalBus,
            attachmentCoordinator = coordinator
        )
    }

    @After
    fun tearDown() {
        if (::viewModel.isInitialized) viewModel.viewModelScope.cancel()
        database.close()
        Dispatchers.resetMain()
    }

    // ------------------------------------------------------------------
    // Helpers
    // ------------------------------------------------------------------

    /** awaitUntil + scheduler advancement (viewModelScope runs on the test scheduler). */
    private fun awaitUntilAdvanced(
        timeoutMs: Long = 15_000,
        condition: () -> Boolean
    ) {
        val start = System.currentTimeMillis()
        while (!condition() && System.currentTimeMillis() - start < timeoutMs) {
            scheduler.advanceUntilIdle()
            if (condition()) break
            Thread.sleep(20)
        }
        scheduler.advanceUntilIdle()
        assertTrue("Timed out waiting for the condition (execution pipeline stalled?)", condition())
    }

    /** Imports one text attachment into the ACTIVE project's sandbox. */
    private suspend fun importTextAttachment(uri: String, name: String, content: String): TurnAttachment {
        contentFiles[uri] = name to content.toByteArray(Charsets.UTF_8)
        return coordinator.importFileAttachment(uri = uri)
    }

    private suspend fun createSiblingProject(): Long {
        val daoField = WorkspaceRuntimeService::class.java.getDeclaredField("projectDao")
        daoField.isAccessible = true
        val dao = daoField.get(workspaceService) as FakeProjectDaoForVm
        val entity = com.example.infrastructure.persistence.entities.ProjectEntity(
            name = "مشروع التأريض الشقيق",
            description = "",
            rootPath = "",
            createdAtEpochMs = System.currentTimeMillis(),
            updatedAtEpochMs = System.currentTimeMillis(),
            workspaceId = activeWorkspace.id
        )
        val generated = (dao.stored.keys.maxOrNull() ?: 500L) + 1
        dao.stored[generated] = entity.copy(id = generated)
        return generated
    }

    // ------------------------------------------------------------------
    // P5 — the grounding digest is built from the SEND-PINNED scope
    // ------------------------------------------------------------------

    @Test
    fun `P5 a project switch between acceptance and grounding neither breaks the send nor grounds the wrong sandbox`() =
        runBlocking {
            val attachment = importTextAttachment(
                uri = "content://grounding/notes.txt",
                name = "ملاحظات.txt",
                content = "محتوى ملف الاختبار للتأريض المثبت"
            )
            val pinnedProjectId = workspaceService.activeProjectIdOrNull()
            assertNotNull(pinnedProjectId)

            // The send is ACCEPTED (synchronously) — its scope is pinned NOW.
            viewModel.updatePromptInput("لخص الملف المرفق")
            val accepted = viewModel.executePrompt(
                agent = null,
                attachments = listOf(attachment),
                onSendAborted = { drafts -> abortedDrafts += drafts }
            )
            assertTrue(accepted)

            // …and the active project switches BEFORE the send coroutine runs
            // (the interleave the production dispatchers allow).
            val siblingProjectId = createSiblingProject()
            workspaceService.setActiveProject(siblingProjectId)

            // The grounding + send decision run now (the switch already
            // landed between acceptance and the coroutine — deterministic).
            scheduler.advanceUntilIdle()

            // The send was NOT aborted (the attachment lives in the pinned
            // project's sandbox — the digest reads THAT root, not the new
            // active project's).
            assertEquals(
                "the send must not be aborted by the mid-flight switch",
                0,
                abortedDrafts.size
            )
            assertNull(
                "no grounding failure may be fabricated for the pinned sandbox",
                viewModel.state.value.errorMessage
            )

            // The execution completes under the SEND-PINNED scope.
            awaitUntilAdvanced { repository.appendedTurns.isNotEmpty() }
            // The LLM request carries the PINNED project's attachment content.
            assertTrue(capturedRequests.isNotEmpty())
            val request = capturedRequests.last()
            assertTrue(
                "the digest must ground the pinned project's sandbox content",
                request.messages.last().content.contains("محتوى ملف الاختبار للتأريض المثبت")
            )
            // The durable session is pinned to the SEND-time project — the
            // execution and its grounding share ONE context.
            val turn = repository.appendedTurns.single()
            val session = repository.getSession(turn.sessionId)!!
            assertEquals(pinnedProjectId, session.projectId)
        }

    @Test
    fun `P5b the coordinator grounds from the explicitly pinned project, not the current one`() =
        runBlocking {
            val attachment = importTextAttachment(
                uri = "content://grounding/pinned.txt",
                name = "مثبت.txt",
                content = "أدلة المشروع الأول"
            )
            val pinnedProjectId = workspaceService.activeProjectIdOrNull()!!

            // The CURRENT project moves on; the PINNED one is the one the send
            // captured (P5: the digest must read THAT project's sandbox).
            val siblingProjectId = createSiblingProject()
            workspaceService.setActiveProject(siblingProjectId)
            assertEquals(siblingProjectId, workspaceService.activeProjectIdOrNull())

            // P5: the PINNED project is passed explicitly — the digest reads
            // THAT project's sandbox even though the live active project has
            // moved on.
            val outcome = coordinator.buildGroundingDigest(
                workspaceId = activeWorkspace.id,
                projectId = pinnedProjectId,
                attachments = listOf(attachment)
            )
            assertEquals(
                "grounding must read the pinned project's sandbox (not the live active project)",
                0,
                outcome.failures.size
            )
            assertTrue(outcome.digest.contains("أدلة المشروع الأول"))
        }
}
