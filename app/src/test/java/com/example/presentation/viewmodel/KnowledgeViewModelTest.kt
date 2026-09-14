package com.example.presentation.viewmodel

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.example.application.rag.KnowledgePersistenceService
import com.example.application.rag.RagPipelineService
import com.example.application.registry.ComponentRegistry
import com.example.application.usecases.ManageMemoryUseCase
import com.example.domain.core.DegradedReason
import com.example.domain.core.Outcome
import com.example.domain.core.memory.EmbeddingVector
import com.example.domain.core.memory.MemoryEntry
import com.example.domain.core.memory.MemoryType
import com.example.domain.core.memory.SafeEmbeddingProviderMetadata
import com.example.domain.core.memory.ScoredMemoryRecord
import com.example.domain.core.memory.RetrievalMode
import com.example.domain.core.memory.VectorStoreFailure
import com.example.domain.core.rag.DocumentChunk
import com.example.domain.core.rag.KnowledgeDocument
import com.example.domain.core.workspace.Workspace
import com.example.domain.ports.memory.EmbeddingProviderPort
import com.example.domain.ports.memory.MemoryRepositoryPort
import com.example.infrastructure.memory.semantic.LocalSemanticEmbeddingRouter
import com.example.infrastructure.memory.semantic.OnnxSemanticEmbeddingAdapter
import com.example.infrastructure.persistence.dao.DocumentChunkDao
import com.example.infrastructure.persistence.dao.KnowledgeDocumentDao
import com.example.infrastructure.persistence.entities.DocumentChunkEntity
import com.example.infrastructure.persistence.entities.KnowledgeDocumentEntity
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
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
 * KnowledgeViewModelTest — GAP-21 (Design Closure 2026) behavioral coverage
 * for the KNOWLEDGE feature ViewModel (ADR-6 slice 3)
 * ============================================================================
 *
 * Drives the REAL RagPipelineService (resource pipeline + lexical fallback,
 * no fake at the service seam) and the REAL ManageMemoryUseCase over an
 * in-memory MemoryRepositoryPort, and asserts the contract extracted from
 * MainViewModel:
 *
 *  - the documents listing follows the pipeline's live index AND re-scopes
 *    from persistence when the ACTIVE WORKSPACE changes (the collector that
 *    replaced MainViewModel.observeWorkspace's RAG reload);
 *  - the semantic engine is honest end-to-end: readiness mirrors the REAL
 *    router (a Robolectric-faked provisioned ONNX file set makes the REAL
 *    adapter's isProvisioned true — no network, no stubbed outcome),
 *    provisioning failure surfaces its real diagnostic, and the in-flight
 *    flag always resets;
 *  - ingest sanitizes the title (P0-8), clears the form, and surfaces the
 *    P1-14 honest FAILED-persistence diagnostic instead of faking success;
 *  - retrieval assembles the context; deletion surfaces both outcomes;
 *  - the memory browser surfaces every Outcome variant (Success / Degraded
 *    partial / Error) and refreshes after a successful store.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class KnowledgeViewModelTest {

    private val dispatcher = UnconfinedTestDispatcher()

    private lateinit var memoryRepository: FakeMemoryRepository
    private lateinit var activeWorkspace: MutableStateFlow<Workspace?>
    private lateinit var persistence: ScriptedKnowledgePersistence
    private lateinit var pipeline: RagPipelineService
    private lateinit var viewModel: KnowledgeViewModel

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
        memoryRepository = FakeMemoryRepository()
        activeWorkspace = MutableStateFlow(null)
        persistence = ScriptedKnowledgePersistence()
        pipeline = newPipeline(fallback = null, persistence = persistence)
        viewModel = KnowledgeViewModel(
            ragPipelineService = pipeline,
            manageMemoryUseCase = ManageMemoryUseCase(memoryRepository),
            activeWorkspace = activeWorkspace
        )
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    // ------------------------------------------------------------------
    // Construction helpers
    // ------------------------------------------------------------------

    private fun newPipeline(
        fallback: EmbeddingProviderPort?,
        persistence: KnowledgePersistenceService?
    ): RagPipelineService {
        val registry = ComponentRegistry()
        return RagPipelineService(
            resourceRegistry = registry.resourceRegistry,
            runtimeAdapterResolver = registry.runtimeAdapterResolver,
            fallbackEmbeddingProvider = fallback,
            persistenceService = persistence,
            workspaceIdProvider = { activeWorkspace.value?.id ?: "default" }
        )
    }

    private fun activateWorkspace(id: String) {
        activeWorkspace.value = Workspace(
            id = id,
            name = "مساحة $id",
            description = "",
            activeProjectId = 0L
        )
    }

    private fun doc(
        id: String,
        title: String,
        content: String = "محتوى المستند $id"
    ): KnowledgeDocument = KnowledgeDocument(
        id = id,
        title = title,
        sourceUri = "workspace://docs/$id.md",
        content = content
    )

    /**
     * The real RagPipelineService hops to Dispatchers.Default/IO inside its
     * suspend bodies — the Unconfined test main does NOT wait for those. This
     * waits (bounded) for the async work to settle before asserting.
     */
    private fun awaitUntil(timeoutMs: Long = 10_000, condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (!condition() && System.currentTimeMillis() < deadline) {
            Thread.sleep(20)
        }
    }

    // ------------------------------------------------------------------
    // Live index + workspace re-scope
    // ------------------------------------------------------------------

    @Test
    fun `init mirrors the pipeline live index into the listing`() {
        // The real pipeline bootstraps its default knowledge corpus.
        assertTrue(
            "القائمة الحية تعكس فهرس الخدمة الحقيقي (المستندات الافتراضية)",
            viewModel.state.value.knowledgeDocuments.isNotEmpty()
        )
    }

    @Test
    fun `workspace activation reloads the scoped knowledge from persistence`() {
        val scoped = listOf(doc("doc_persisted", "مستند مساحة العمل"))
        persistence.docsByWorkspace["ws_a"] = scoped

        activateWorkspace("ws_a")

        assertEquals(
            "تبديل مساحة العمل يعيد تحميل الفهرس من مخزن المساحة الجديدة",
            listOf("doc_persisted"),
            viewModel.state.value.knowledgeDocuments.map { it.id }
        )
    }

    // ------------------------------------------------------------------
    // Semantic engine (honesty end-to-end on the REAL router/adapter)
    // ------------------------------------------------------------------

    @Test
    fun `readiness mirrors the honest service flag (false without a local router)`() {
        viewModel.refreshSemanticModelStatus()
        assertFalse("بلا موجه دلالي محلي: العلم صادق بـ false", viewModel.state.value.semanticModelReady)
    }

    @Test
    fun `provisioning failure is honest - readiness stays false and the diagnostic surfaces`() {
        // No fallbackEmbeddingProvider → the pipeline reports there is NO
        // local semantic path in this configuration.
        viewModel.provisionLocalSemanticModel()

        val state = viewModel.state.value
        assertFalse(state.semanticModelReady)
        assertFalse(state.isProvisioningSemanticModel)
        assertTrue(
            "رسالة الفشل الصادقة تظهر",
            state.diagnosticBanner?.contains("تعذر تجهيز النموذج الدلالي المحلي") == true
        )
    }

    @Test
    fun `provisioning success flips readiness through the REAL router`() {
        // REAL OnnxSemanticEmbeddingAdapter over a Robolectric filesDir with
        // a faked provisioned model file set — isProvisioned is TRUE by the
        // adapter's own real check, so provision() short-circuits to Success
        // with NO network. This exercises the production path, not a stub.
        val context = ApplicationProvider.getApplicationContext<Context>()
        val modelDir = File(context.filesDir, "semantic_embedding").apply { mkdirs() }
        File(modelDir, "minilm_l6_int8.onnx").writeBytes(ByteArray(1_100_000))
        File(modelDir, "vocab.txt").writeText("## fake vocab ##")

        val adapter = OnnxSemanticEmbeddingAdapter(context)
        val router = LocalSemanticEmbeddingRouter(adapter, FakeLexicalEmbedding())
        val provisionedPipeline = newPipeline(fallback = router, persistence = persistence)
        val vm = KnowledgeViewModel(
            ragPipelineService = provisionedPipeline,
            manageMemoryUseCase = ManageMemoryUseCase(memoryRepository),
            activeWorkspace = activeWorkspace
        )

        assertTrue("الخدمة تقرأ الجاهزية الحقيقية للموجه", provisionedPipeline.isLocalSemanticModelReady)

        vm.provisionLocalSemanticModel()
        // The REAL adapter's provision() hops to Dispatchers.IO — wait for
        // the readiness flip to settle before asserting.
        awaitUntil { vm.state.value.semanticModelReady }

        val state = vm.state.value
        assertTrue(state.semanticModelReady)
        assertFalse(state.isProvisioningSemanticModel)
        assertTrue(
            "بانر الجاهزية الصادق يظهر مع حد التوافق",
            state.diagnosticBanner?.contains("جاهز") == true &&
                state.diagnosticBanner?.contains("حد التوافق") == true
        )
    }

    // ------------------------------------------------------------------
    // Ingest (sanitization + honest persistence states)
    // ------------------------------------------------------------------

    @Test
    fun `ingest sanitizes the title, indexes the document and clears the form`() {
        viewModel.updateDocTitle("تقرير/2026:الجزء*الثالث?")
        viewModel.updateDocContent("محتوى قابل للاسترجاع عن مبادئ الأمان والحوكمة.")

        viewModel.ingestNewDocument()
        awaitUntil { viewModel.state.value.newDocTitle.isEmpty() }

        val state = viewModel.state.value
        assertEquals("الحقول تُمسح بعد الإدخال", "", state.newDocTitle)
        assertEquals("", state.newDocContent)
        val ingested = state.knowledgeDocuments.lastOrNull { it.title.contains("2026") }
        assertNotNull("المستند دخل الفهرس الحي", ingested)
        assertTrue(
            "العنوان منقّى من فواصل المسارات (P0-8)",
            ingested!!.title.contains("/").not() && ingested.title.contains(":").not() &&
                ingested.title.contains("*").not() && ingested.title.contains("?").not()
        )
        assertTrue(
            "معرّف المصدر مبني على العنوان المنقّى",
            ingested.sourceUri.startsWith("workspace://docs/")
        )
    }

    @Test
    fun `ingest with FAILED persistence surfaces the honest diagnostic`() {
        val failing = newPipeline(fallback = null, persistence = ThrowingPersistence())
        val vm = KnowledgeViewModel(
            ragPipelineService = failing,
            manageMemoryUseCase = ManageMemoryUseCase(memoryRepository),
            activeWorkspace = activeWorkspace
        )
        vm.updateDocTitle("مستند سيفشل حفظه")
        vm.updateDocContent("محتوى")

        vm.ingestNewDocument()
        awaitUntil { vm.state.value.newDocTitle.isEmpty() }

        val state = vm.state.value
        assertTrue(
            "التشخيص الصادق لفشل الحفظ (P1-14) يظهر",
            state.diagnosticBanner?.contains("RAG_PERSISTENCE_FAILED") == true
        )
        assertEquals("الحقول تُمسح حتى عند الفشل (المستند دخل الفهرس الحي)", "", state.newDocTitle)
    }

    @Test
    fun `ingest ignores blank input`() {
        val before = viewModel.state.value.knowledgeDocuments.size
        viewModel.updateDocTitle("   ")
        viewModel.updateDocContent("   ")
        viewModel.ingestNewDocument()
        assertEquals(before, viewModel.state.value.knowledgeDocuments.size)
    }

    // ------------------------------------------------------------------
    // Retrieval + deletion
    // ------------------------------------------------------------------

    @Test
    fun `queryKnowledgeRag assembles the context from the real pipeline`() {
        viewModel.queryKnowledgeRag("مبادئ الأمان")
        awaitUntil { viewModel.state.value.assembledRagContext != null }
        assertNotNull("السياق المُجمّع من الاسترجاع الحقيقي", viewModel.state.value.assembledRagContext)
    }

    @Test
    fun `queryKnowledgeRag ignores blank queries`() {
        viewModel.queryKnowledgeRag("   ")
        assertNull(viewModel.state.value.assembledRagContext)
    }

    @Test
    fun `deleteKnowledgeDocument success shows the confirmation banner`() {
        viewModel.updateDocTitle("مستند مؤقت")
        viewModel.updateDocContent("محتوى")
        viewModel.ingestNewDocument()
        awaitUntil { viewModel.state.value.knowledgeDocuments.any { it.title == "مستند مؤقت" } }
        val created = viewModel.state.value.knowledgeDocuments.last { it.title == "مستند مؤقت" }

        viewModel.deleteKnowledgeDocument(created.id)
        awaitUntil {
            viewModel.state.value.diagnosticBanner?.contains("تم حذف المستند") == true
        }

        val state = viewModel.state.value
        assertTrue(
            "بانر تأكيد الحذف",
            state.diagnosticBanner?.contains("تم حذف المستند") == true
        )
        assertTrue(
            "المستند غادر الفهرس الحي",
            state.knowledgeDocuments.none { it.id == created.id }
        )
    }

    @Test
    fun `deleteKnowledgeDocument failure surfaces the real error`() {
        // persistenceService == null → the pipeline reports volatile-only
        // deletion as an honest Error.
        val volatilePipeline = newPipeline(fallback = null, persistence = null)
        val vm = KnowledgeViewModel(
            ragPipelineService = volatilePipeline,
            manageMemoryUseCase = ManageMemoryUseCase(memoryRepository),
            activeWorkspace = activeWorkspace
        )
        vm.updateDocTitle("مستند متطاير")
        vm.updateDocContent("محتوى")
        vm.ingestNewDocument()
        awaitUntil { vm.state.value.knowledgeDocuments.any { it.title == "مستند متطاير" } }
        val created = vm.state.value.knowledgeDocuments.last { it.title == "مستند متطاير" }

        vm.deleteKnowledgeDocument(created.id)
        awaitUntil { vm.state.value.errorMessage != null }

        assertNotNull("رسالة الخطأ الصادقة (حذف في الذاكرة فقط)", vm.state.value.errorMessage)
    }

    // ------------------------------------------------------------------
    // Long-term memory browser
    // ------------------------------------------------------------------

    @Test
    fun `init loads the active memories through the real use case`() {
        assertEquals(
            "القائمة الأولية من getActiveMemories",
            listOf("m1", "m2"),
            viewModel.state.value.allMemories.map { it.id }
        )
    }

    @Test
    fun `searchMemory surfaces the Success outcome`() {
        memoryRepository.nextRetrieve = Outcome.Success(
            listOf(
                ScoredMemoryRecord(
                    entry = memoryEntry("m1"),
                    similarityScore = 0.9f,
                    retrievalMode = RetrievalMode.LEXICAL_FALLBACK
                )
            )
        )
        viewModel.updateMemoryQuery("الأمان")
        viewModel.searchMemory()

        val state = viewModel.state.value
        assertEquals(1, state.retrievedMemories.size)
        assertFalse(state.isSearchingMemory)
    }

    @Test
    fun `searchMemory surfaces the Degraded partial value`() {
        memoryRepository.nextRetrieve = Outcome.Degraded(
            partialValue = emptyList(),
            reason = DegradedReason.LEXICAL_FALLBACK,
            diagnosticMessage = "بحث متدهور جزئياً"
        )
        viewModel.updateMemoryQuery("الأمان")
        viewModel.searchMemory()

        val state = viewModel.state.value
        assertTrue(state.retrievedMemories.isEmpty())
        assertFalse(state.isSearchingMemory)
    }

    @Test
    fun `searchMemory surfaces the Error outcome and resets the in-flight flag`() {
        memoryRepository.nextRetrieve = Outcome.Error(
            failure = VectorStoreFailure.StorageReadError("تخزين غير متاح"),
            diagnosticMessage = "تخزين غير متاح"
        )
        viewModel.updateMemoryQuery("الأمان")
        viewModel.searchMemory()

        val state = viewModel.state.value
        assertFalse(state.isSearchingMemory)
        assertEquals("تخزين غير متاح", state.errorMessage)
    }

    @Test
    fun `searchMemory ignores blank queries`() {
        viewModel.updateMemoryQuery("  ")
        viewModel.searchMemory()
        assertFalse(viewModel.state.value.isSearchingMemory)
    }

    @Test
    fun `addNewMemory stores the insight, clears the form and refreshes`() {
        memoryRepository.nextStore = Outcome.Success(Unit)
        viewModel.updateNewMemoryContent("يفضل المستخدم الردود المختصرة")
        viewModel.addNewMemory()

        val state = viewModel.state.value
        assertEquals("", state.newMemoryContent)
        assertEquals(
            "القائمة تُحدَّث بعد التخزين الناجح",
            listOf("m1", "m2", "stored"),
            state.allMemories.map { it.id }
        )
        assertEquals("يفضل المستخدم الردود المختصرة", memoryRepository.lastStored?.content)
        assertEquals(MemoryType.FACTUAL_INSIGHT, memoryRepository.lastStored?.type)
    }

    @Test
    fun `addNewMemory surfaces the store error honestly`() {
        memoryRepository.nextStore = Outcome.Error(
            failure = VectorStoreFailure.StorageWriteError("تعذر الكتابة"),
            diagnosticMessage = "تعذر الكتابة"
        )
        viewModel.updateNewMemoryContent("معرفة لن تُخزَّن")
        viewModel.addNewMemory()

        val state = viewModel.state.value
        assertEquals("تعذر الكتابة", state.errorMessage)
        // The form is NOT cleared on failure — the user's input survives.
        assertEquals("معرفة لن تُخزَّن", state.newMemoryContent)
    }

    @Test
    fun `addNewMemory ignores blank content`() {
        viewModel.updateNewMemoryContent("   ")
        viewModel.addNewMemory()
        assertNull(memoryRepository.lastStored)
    }

    // ------------------------------------------------------------------
    // Feature-owned channels
    // ------------------------------------------------------------------

    @Test
    fun `error and banner channels are dismissible`() {
        memoryRepository.nextRetrieve = Outcome.Error(
            failure = VectorStoreFailure.StorageReadError("خطأ"),
            diagnosticMessage = "خطأ"
        )
        viewModel.updateMemoryQuery("س")
        viewModel.searchMemory()
        assertNotNull(viewModel.state.value.errorMessage)

        viewModel.clearErrorMessage()
        assertNull(viewModel.state.value.errorMessage)

        viewModel.provisionLocalSemanticModel() // honest failure → banner
        assertNotNull(viewModel.state.value.diagnosticBanner)

        viewModel.dismissDiagnosticBanner()
        assertNull(viewModel.state.value.diagnosticBanner)
    }

    // ==================================================================
    // Fakes (port/DAO level — the services above them are REAL)
    // ==================================================================

    /** In-memory MemoryRepositoryPort with scripted outcomes. */
    private class FakeMemoryRepository : MemoryRepositoryPort {
        var nextRetrieve: Outcome<List<ScoredMemoryRecord>, VectorStoreFailure> =
            Outcome.Success(emptyList())
        var nextStore: Outcome<Unit, VectorStoreFailure> = Outcome.Success(Unit)
        var lastStored: MemoryEntry? = null

        private val memories = mutableListOf(
            memoryEntry("m1"),
            memoryEntry("m2")
        )

        override suspend fun storeMemory(entry: MemoryEntry): Outcome<Unit, VectorStoreFailure> {
            lastStored = entry
            val outcome = nextStore
            if (outcome is Outcome.Success) {
                memories.add(entry.copy(id = "stored"))
            }
            return outcome
        }

        override suspend fun retrieveMemories(
            query: String,
            topK: Int,
            minConfidence: Float
        ): Outcome<List<ScoredMemoryRecord>, VectorStoreFailure> = nextRetrieve

        override suspend fun getAllActiveMemories(): Outcome<List<MemoryEntry>, VectorStoreFailure> =
            Outcome.Success(memories.toList())

        override suspend fun deleteMemory(id: String): Outcome<Unit, VectorStoreFailure> =
            Outcome.Success(Unit)
    }

    /** Scripted knowledge persistence (open class — only the load is faked). */
    private class ScriptedKnowledgePersistence : KnowledgePersistenceService(OkDocDao(), OkChunkDao()) {
        var docsByWorkspace: MutableMap<String, List<KnowledgeDocument>> = mutableMapOf()

        override suspend fun loadWorkspaceKnowledge(
            workspaceId: String,
            projectId: Long?
        ): Pair<List<KnowledgeDocument>, List<DocumentChunk>> =
            (docsByWorkspace[workspaceId] ?: emptyList()) to emptyList()
    }

    /** Persistence whose writes always fail (P1-14 honest-failure path). */
    private class ThrowingPersistence : KnowledgePersistenceService(ThrowingDocDao(), OkChunkDao()) {
        override suspend fun loadWorkspaceKnowledge(
            workspaceId: String,
            projectId: Long?
        ): Pair<List<KnowledgeDocument>, List<DocumentChunk>> =
            emptyList<KnowledgeDocument>() to emptyList<DocumentChunk>()
    }

    private open class OkDocDao : KnowledgeDocumentDao {
        override fun observeDocumentsForWorkspace(workspaceId: String): Flow<List<KnowledgeDocumentEntity>> =
            MutableStateFlow(emptyList())
        override suspend fun getDocumentsForWorkspace(workspaceId: String): List<KnowledgeDocumentEntity> = emptyList()
        override suspend fun getWorkspaceSharedDocuments(workspaceId: String): List<KnowledgeDocumentEntity> = emptyList()
        override suspend fun getProjectPrivateDocuments(projectId: Long): List<KnowledgeDocumentEntity> = emptyList()
        override suspend fun getDocumentsForRetrieval(workspaceId: String, projectId: Long?): List<KnowledgeDocumentEntity> = emptyList()
        override suspend fun getDocumentById(id: String): KnowledgeDocumentEntity? = null
        override suspend fun getDocumentByIdForWorkspace(id: String, workspaceId: String): KnowledgeDocumentEntity? = null
        override suspend fun insertOrUpdate(document: KnowledgeDocumentEntity) {}
        override suspend fun insertAll(documents: List<KnowledgeDocumentEntity>) {}
        override suspend fun archive(id: String, now: Long) {}
        override suspend fun deleteById(id: String) {}
        override suspend fun deleteByIdForWorkspace(id: String, workspaceId: String) {}
        override suspend fun deleteAllForWorkspace(workspaceId: String) {}
        override suspend fun deleteAllForProject(projectId: Long) {}
        override suspend fun reassignProject(ids: List<String>, projectId: Long?) {}
        override suspend fun countForWorkspace(workspaceId: String): Int = 0
    }

    private class ThrowingDocDao : OkDocDao() {
        override suspend fun insertOrUpdate(document: KnowledgeDocumentEntity) {
            throw IllegalStateException("DOC_WRITE_FAILED")
        }
        override suspend fun insertAll(documents: List<KnowledgeDocumentEntity>) {
            throw IllegalStateException("DOC_WRITE_FAILED")
        }
    }

    private class OkChunkDao : DocumentChunkDao {
        override suspend fun getChunksForWorkspace(workspaceId: String): List<DocumentChunkEntity> = emptyList()
        override suspend fun getChunksForDocument(documentId: String): List<DocumentChunkEntity> = emptyList()
        override suspend fun insertOrUpdate(chunk: DocumentChunkEntity) {}
        override suspend fun insertAll(chunks: List<DocumentChunkEntity>) {}
        override suspend fun deleteChunksForDocument(documentId: String) {}
        override suspend fun deleteChunksForWorkspace(workspaceId: String) {}
        override suspend fun countForWorkspace(workspaceId: String): Int = 0
    }

    /** Deterministic lexical fallback for the REAL semantic router. */
    private class FakeLexicalEmbedding : EmbeddingProviderPort {
        override val providerId = "fake_lexical"
        override val dimension = 8
        override val metadata = SafeEmbeddingProviderMetadata(
            id = providerId,
            name = "Fake Lexical",
            providerType = "LEXICAL_TEST",
            dimension = dimension,
            isLocal = true,
            isEnabled = true
        )

        override suspend fun generateEmbeddings(
            texts: List<String>
        ): Outcome<List<EmbeddingVector>, com.example.domain.core.memory.EmbeddingFailure> =
            Outcome.Success(
                texts.map {
                    EmbeddingVector(
                        values = FloatArray(dimension) { idx -> (it.hashCode() % 97 + idx) / 97f }
                    )
                }
            )
    }
}

private fun memoryEntry(id: String): MemoryEntry = MemoryEntry(
    id = id,
    content = "ذكرى $id",
    type = MemoryType.FACTUAL_INSIGHT
)
