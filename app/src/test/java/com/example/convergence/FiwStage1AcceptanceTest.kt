package com.example.convergence

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.example.application.artifacts.ArtifactService
import com.example.application.budget.EntitlementManagementService
import com.example.application.budget.EntitlementService
import com.example.application.transfer.SessionExportService
import com.example.infrastructure.persistence.AppDatabase
import com.example.infrastructure.storage.SandboxProjectFileStore
import com.example.infrastructure.persistence.TurnAttachmentJsonCodec
import com.example.infrastructure.persistence.entities.ConversationSessionEntity
import com.example.infrastructure.persistence.entities.ConversationTurnEntity
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File

/**
 * ============================================================================
 * FiwStage1AcceptanceTest — FIW Stage 1/3 (Functional Integration &
 * Workflow Closure) acceptance over the REAL services and REAL Room
 * ============================================================================
 *
 * Covers the three Stage-1 closure groups plus the Stage-3 entitlements
 * surface, all against the production implementation (no mocks):
 *
 *  A. RESULT→ARTIFACT linkage (DB v29): a chat-result save records its
 *     producing TURN; the session-restore join (`forSession`) returns the
 *     artifacts keyed by turn, workspace-authorized.
 *  B. UNIFIED SESSION EXPORT: the canonical representation carries the
 *     attachment references, citation chains and produced artifacts; the
 *     TXT/Markdown renders mention them by name (never as content claims).
 *  C. THE IMPORTABLE SESSION PACKAGE round-trip: build → import as a NEW
 *     session → turns land in order, artifacts' payloads restore into the
 *     target project with remapped ids, attachments ride with their turns;
 *     explicit rejections for wrong schema / unsupported version / unsafe
 *     paths / missing fields — with the destination untouched.
 *  D. THE ENTITLEMENTS SURFACE (Stage 3): registration → derived view
 *     (conservative remaining from the accruals engine), idempotent
 *     replay, honest shape rejection, consumption reflected.
 */
@RunWith(RobolectricTestRunner::class)
class FiwStage1AcceptanceTest {

    private lateinit var db: AppDatabase
    private lateinit var baseDir: File
    private lateinit var fileStore: SandboxProjectFileStore
    private lateinit var artifacts: ArtifactService
    private lateinit var exporter: SessionExportService
    private lateinit var entitlements: EntitlementService
    private lateinit var entitlementManagement: EntitlementManagementService

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .allowMainThreadQueries()
            .addCallback(object : androidx.room.RoomDatabase.Callback() {
                override fun onCreate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
                    // The fresh-install parity: the DB-level protections +
                    // the reference data (UNIT_TOKEN & friends) the
                    // entitlement FKs depend on (the v23+ pattern).
                    com.example.infrastructure.persistence.BudgetCoreMigrationSql.TRIGGERS.forEach(db::execSQL)
                    com.example.infrastructure.persistence.Stage2MigrationSql.TRIGGERS.forEach(db::execSQL)
                    com.example.infrastructure.persistence.Stage3MigrationSql.TRIGGERS.forEach(db::execSQL)
                    com.example.infrastructure.persistence.Stage2MigrationSql.STATEMENTS
                        .filter { it.startsWith("INSERT") }.forEach(db::execSQL)
                }
            })
            .build()
        // The applicability scope the entitlement FK resolves (the same
        // seeding pattern EntitlementServiceTest uses).
        db.openHelper.writableDatabase.execSQL(
            "INSERT INTO scope_descriptor (scope_id, scope_kind, canonical_representation, canonical_hash, " +
                "hash_algorithm, canonicalization_version, provider_dimension_state, integration_dimension_state, " +
                "access_context_dimension_state, provider_scope_node_dimension_state, offering_dimension_state, " +
                "request_attempt_dimension_state, budget_policy_dimension_state, budget_period_dimension_state, " +
                "region_dimension_state, endpoint_dimension_state, period_dimension_state, platform_dimension_state, " +
                "platform_object_type, platform_object_id, created_at_epoch_ms) VALUES " +
                "('sd-gptx','WORKSPACE','v1|WORKSPACE|ws-fiw','h','SHA-256',1,'NOT_APPLICABLE','NOT_APPLICABLE'," +
                "'NOT_APPLICABLE','NOT_APPLICABLE','NOT_APPLICABLE','NOT_APPLICABLE','UNKNOWN','UNKNOWN'," +
                "'NOT_APPLICABLE','NOT_APPLICABLE','NOT_APPLICABLE','KNOWN','WORKSPACE','ws-fiw',0)"
        )
        baseDir = File(context.filesDir, "test_fiw_stage1").apply { deleteRecursively(); mkdirs() }
        fileStore = SandboxProjectFileStore(baseDir)
        artifacts = ArtifactService(db, fileStore)
        exporter = SessionExportService(db, auditTrail = null, artifactService = artifacts)
        entitlements = EntitlementService(db)
        entitlementManagement = EntitlementManagementService(db, entitlements)
    }

    @After
    fun tearDown() {
        db.close()
        baseDir.deleteRecursively()
    }

    // ------------------------------------------------------------------
    // A — the result→artifact turn linkage (DB v29)
    // ------------------------------------------------------------------

    @Test
    fun `result save records its producing turn and the session join reads it back`() = runBlocking {
        val descriptor = artifacts.saveAssistantResultAsArtifact(
            workspaceId = "ws1",
            projectId = 9L,
            sessionId = "sess_a",
            executionId = null,
            name = "تقرير",
            content = "المحتوى".toByteArray(),
            createdBy = "user",
            turnId = "turn_1"
        )
        assertEquals("the linkage is persisted", "turn_1", descriptor.turnId)

        val join = artifacts.forSession("ws1", "sess_a")
        assertEquals(1, join.size)
        assertEquals("turn_1", join.first().turnId)
        assertEquals("تقرير", join.first().name)

        // A legacy save (no turn) honestly carries null.
        val legacy = artifacts.saveAssistantResultAsArtifact(
            workspaceId = "ws1", projectId = 9L, sessionId = "sess_a",
            executionId = null, name = "قديم", content = "x".toByteArray()
        )
        assertNull(legacy.turnId)
        // The join returns BOTH (the pre-fix save renders no per-message
        // card — it is still reachable through the project listing).
        assertEquals(2, artifacts.forSession("ws1", "sess_a").size)
    }

    @Test
    fun `the session join is workspace-authorized - a foreign workspace sees nothing`() = runBlocking {
        artifacts.saveAssistantResultAsArtifact(
            workspaceId = "ws1", projectId = 9L, sessionId = "sess_a",
            executionId = null, name = "ن", content = "x".toByteArray(), turnId = "turn_1"
        )
        assertTrue(
            "another workspace's session artifacts are NOT FOUND",
            artifacts.forSession("ws2", "sess_a").isEmpty()
        )
    }

    @Test
    fun `the scope-aware descriptor resolves a real row and hides a foreign one`() = runBlocking {
        val saved = artifacts.saveAssistantResultAsArtifact(
            workspaceId = "ws1", projectId = 9L, sessionId = null,
            executionId = null, name = "desc.md", content = "m".toByteArray()
        )
        val scope = com.example.domain.core.context.ResourceScope.Project("ws1", 9L)
        val resolved = artifacts.descriptor(scope, saved.id)
        assertNotNull("the owning scope resolves the row", resolved)
        assertEquals("text/markdown", resolved!!.mimeType)

        val foreignScope = com.example.domain.core.context.ResourceScope.Project("ws2", 9L)
        assertNull(
            "a foreign scope is indistinguishable from nonexistent",
            artifacts.descriptor(foreignScope, saved.id)
        )
    }

    // ------------------------------------------------------------------
    // B — the unified canonical export
    // ------------------------------------------------------------------

    private fun seedSessionWithTurn(sessionId: String, workspaceId: String, projectId: Long?) {
        runBlocking {
            db.conversationSessionDao().upsert(
                ConversationSessionEntity(
                    sessionId = sessionId, workspaceId = workspaceId, title = "جلسة $sessionId",
                    mode = "QUICK_CHAT", projectId = projectId,
                    createdAtEpochMs = 1, lastActiveAtEpochMs = 1
                )
            )
            db.conversationTurnDao().insert(
                ConversationTurnEntity(
                    turnId = "t_$sessionId", sessionId = sessionId,
                    prompt = "السؤال", answer = "الجواب",
                    tokensConsumed = 5, durationMs = 10, isSuccessful = true, eventCount = 2,
                    createdAtEpochMs = 2,
                    attachmentsJson = TurnAttachmentJsonCodec.encode(
                        listOf(
                            com.example.domain.core.session.TurnAttachment(
                                id = "attm_1", name = "ملف.txt", mimeType = "text/plain",
                                sizeBytes = 3L, storageUri = "uploads/ملف.txt",
                                provenance = "SAF_FILE", groundingState = "GROUNDED"
                            )
                        )
                    ),
                    sourcesJson = "[{\"title\":\"مصدر\",\"url\":\"https://example.com\"}]"
                )
            )
            artifacts.saveAssistantResultAsArtifact(
                workspaceId = workspaceId, projectId = projectId ?: 9L, sessionId = sessionId,
                executionId = null, name = "مخرج.md", content = "م".toByteArray(),
                turnId = "t_$sessionId"
            )
        }
    }

    @Test
    fun `the canonical representation carries attachments, sources and artifacts`() = runBlocking {
        seedSessionWithTurn("sess_canon", "ws1", 9L)
        val canonical = exporter.canonicalSession("ws1", "sess_canon")
        assertNotNull(canonical)
        val messages = canonical!!.getJSONArray("messages")
        assertEquals(2, messages.length())
        val userMessage = messages.getJSONObject(0)
        assertEquals("user", userMessage.getString("role"))
        assertEquals(1, userMessage.getJSONArray("attachments").length())
        assertEquals("ملف.txt", userMessage.getJSONArray("attachments").getJSONObject(0).getString("name"))
        val assistantMessage = messages.getJSONObject(1)
        assertEquals(1, assistantMessage.getJSONArray("sources").length())
        assertEquals(1, assistantMessage.getJSONArray("artifacts").length())
        assertEquals("مخرج.md", assistantMessage.getJSONArray("artifacts").getJSONObject(0).getString("name"))
    }

    @Test
    fun `the TXT and Markdown renders mention attachments and sources by name`() = runBlocking {
        seedSessionWithTurn("sess_render", "ws1", 9L)
        val txt = exporter.exportSession("ws1", "sess_render", SessionExportService.Format.TXT)!!
        assertTrue(txt.contains("[مرفق] ملف.txt"))
        assertTrue(txt.contains("[مصدر] مصدر"))
        assertTrue(txt.contains("[مخرج] مخرج.md"))
        val md = exporter.exportSession("ws1", "sess_render", SessionExportService.Format.MARKDOWN)!!
        assertTrue(md.contains("مرفق: ملف.txt"))
        assertTrue(md.contains("مصدر: مصدر"))
        assertTrue(md.contains("مخرج: مخرج.md"))
    }

    // ------------------------------------------------------------------
    // C — the importable session package (round-trip + rejections)
    // ------------------------------------------------------------------

    @Test
    fun `package round-trip - a new session lands with turns, attachments and artifact payloads`() = runBlocking {
        seedSessionWithTurn("sess_pkg", "ws1", 9L)
        val built = exporter.buildSessionPackage("ws1", "sess_pkg")
        assertTrue(built is SessionExportService.PackageBuild.Built)
        val pkg = (built as SessionExportService.PackageBuild.Built).packageJson
        assertEquals(SessionExportService.PACKAGE_SCHEMA, pkg.getString("schema"))
        assertEquals(1, pkg.getJSONArray("artifacts").length())
        assertTrue(pkg.getJSONArray("artifacts").getJSONObject(0).getBoolean("payloadIncluded"))

        val outcome = exporter.importSessionPackage("ws1", 9L, pkg)
        assertTrue(
            "imported, not rejected: ${(outcome as? SessionExportService.ImportOutcome.Rejected)?.reason}",
            outcome is SessionExportService.ImportOutcome.Imported
        )
        val imported = outcome as SessionExportService.ImportOutcome.Imported
        assertEquals(1, imported.turnCount)
        assertEquals(1, imported.artifactsRestored)
        assertEquals(1, imported.payloadsRestored)

        // The new session exists, is titled (مستوردة), and its turn carries
        // the attachment + content.
        val newSession = db.conversationSessionDao().byIdAndWorkspace(imported.newSessionId, "ws1")
        assertNotNull(newSession)
        assertTrue(newSession!!.title.contains("مستوردة"))
        assertEquals(1, newSession.turnCount)
        val turns = db.conversationTurnDao().forSessionOnce(imported.newSessionId)
        assertEquals(1, turns.size)
        assertEquals("السؤال", turns.first().prompt)
        assertEquals("الجواب", turns.first().answer)
        assertEquals(1, TurnAttachmentJsonCodec.decode(turns.first().attachmentsJson).size)
        assertEquals("PACKAGE_IMPORT", TurnAttachmentJsonCodec.decode(turns.first().attachmentsJson).first().provenance)

        // The artifact payload restored into the SAME project, linked to the
        // REMAPPED turn (the session-restore join sees it).
        val join = artifacts.forSession("ws1", imported.newSessionId)
        assertEquals(1, join.size)
        assertEquals(turns.first().turnId, join.first().turnId)
        val scope = com.example.domain.core.context.ResourceScope.Project("ws1", 9L)
        assertEquals("م", artifacts.readContent(scope, join.first().id)?.toString(Charsets.UTF_8))
    }

    @Test
    fun `import NEVER overwrites - the source session is untouched by the import`() = runBlocking {
        seedSessionWithTurn("sess_keep", "ws1", 9L)
        val pkg = (exporter.buildSessionPackage("ws1", "sess_keep")
            as SessionExportService.PackageBuild.Built).packageJson
        val outcome = exporter.importSessionPackage("ws1", 9L, pkg)
            as SessionExportService.ImportOutcome.Imported
        assertTrue(outcome.newSessionId != "sess_keep")
        assertNotNull(db.conversationSessionDao().byIdAndWorkspace("sess_keep", "ws1"))
        assertEquals(1, db.conversationTurnDao().forSessionOnce("sess_keep").size)
    }

    @Test
    fun `wrong schema is rejected explicitly`() = runBlocking {
        val outcome = exporter.importSessionPackage(
            "ws1", 9L, JSONObject().put("schema", "something.else").put("schemaVersion", 1)
        )
        assertTrue(outcome is SessionExportService.ImportOutcome.Rejected)
        assertTrue(
            (outcome as SessionExportService.ImportOutcome.Rejected).reason.contains("مخطط")
        )
    }

    @Test
    fun `unsupported future version is rejected with the supported versions named`() = runBlocking {
        seedSessionWithTurn("sess_v", "ws1", 9L)
        val pkg = (exporter.buildSessionPackage("ws1", "sess_v")
            as SessionExportService.PackageBuild.Built).packageJson
            .put("schemaVersion", 99)
        val outcome = exporter.importSessionPackage("ws1", 9L, pkg)
        assertTrue(outcome is SessionExportService.ImportOutcome.Rejected)
        assertTrue((outcome as SessionExportService.ImportOutcome.Rejected).reason.contains("99"))
    }

    @Test
    fun `an unsafe attachment path rejects the WHOLE import - destination untouched`() = runBlocking {
        seedSessionWithTurn("sess_evil", "ws1", 9L)
        val pkg = (exporter.buildSessionPackage("ws1", "sess_evil")
            as SessionExportService.PackageBuild.Built).packageJson
        // Tamper: a traversal attachment storageUri.
        val messages = pkg.getJSONObject("session").getJSONArray("messages")
        messages.getJSONObject(0).getJSONArray("attachments")
            .getJSONObject(0).put("storageUri", "../../escape.txt")
        val before = db.conversationSessionDao().byIdAndWorkspace("sess_evil", "ws1")
        val outcome = exporter.importSessionPackage("ws1", 9L, pkg)
        assertTrue(outcome is SessionExportService.ImportOutcome.Rejected)
        assertTrue(
            (outcome as SessionExportService.ImportOutcome.Rejected).reason.contains("غير آمن")
        )
        // Nothing was written.
        assertEquals(before, db.conversationSessionDao().byIdAndWorkspace("sess_evil", "ws1"))
    }

    @Test
    fun `missing title is rejected`() = runBlocking {
        seedSessionWithTurn("sess_notitle", "ws1", 9L)
        val pkg = (exporter.buildSessionPackage("ws1", "sess_notitle")
            as SessionExportService.PackageBuild.Built).packageJson
        pkg.getJSONObject("session").put("title", "")
        val outcome = exporter.importSessionPackage("ws1", 9L, pkg)
        assertTrue(outcome is SessionExportService.ImportOutcome.Rejected)
    }

    // ------------------------------------------------------------------
    // D — the entitlements management surface (FIW Stage 3)
    // ------------------------------------------------------------------

    @Test
    fun `registration produces the derived view with the conservative remaining`() = runBlocking {
        val outcome = entitlementManagement.registerEntitlement(
            EntitlementManagementService.RegistrationSpec(
                providerId = "openai",
                applicabilityScopeId = "sd-gptx",
                entitlementType = "FREE_QUOTA",
                valueKind = EntitlementService.VALUE_QUANTITY_LIMIT,
                quantityLimitPrecise = "1000000",
                unitId = "UNIT_TOKEN"
            )
        )
        assertTrue(outcome is EntitlementManagementService.RegistrationOutcome.Registered)
        val views = entitlementManagement.listEntitlements()
        assertEquals(1, views.size)
        val view = views.first()
        assertEquals("FREE_QUOTA", view.entitlementType)
        assertTrue(view.isWindowActive)
        assertEquals(0, view.consumptionCount)
        assertEquals("1000000 UNIT_TOKEN", view.remainingLabel)
        assertEquals("UNKNOWN", view.verificationStatus)
    }

    @Test
    fun `consumption decreases the engine's conservative remaining`() = runBlocking {
        val registered = entitlementManagement.registerEntitlement(
            EntitlementManagementService.RegistrationSpec(
                providerId = "openai", applicabilityScopeId = "sd-gptx",
                entitlementType = "FREE_QUOTA",
                valueKind = EntitlementService.VALUE_QUANTITY_LIMIT,
                quantityLimitPrecise = "1000", unitId = "UNIT_TOKEN"
            )
        ) as EntitlementManagementService.RegistrationOutcome.Registered

        entitlements.consume(
            entitlementId = registered.entitlementId,
            consumptionGranularity = EntitlementService.GRANULARITY_REQUEST_ATTEMPT,
            idempotencyKey = "attempt_1",
            quantityPrecise = "300"
        )
        // A REPLAY of the same idempotency key never double-deducts.
        entitlements.consume(
            entitlementId = registered.entitlementId,
            consumptionGranularity = EntitlementService.GRANULARITY_REQUEST_ATTEMPT,
            idempotencyKey = "attempt_1",
            quantityPrecise = "300"
        )
        val view = entitlementManagement.listEntitlements().first()
        assertEquals(1, view.consumptionCount)
        assertEquals("700 UNIT_TOKEN", view.remainingLabel)
    }

    @Test
    fun `registration replay is idempotent - the same shape is one record`() = runBlocking {
        val spec = EntitlementManagementService.RegistrationSpec(
            providerId = "p", applicabilityScopeId = "sd-gptx",
            entitlementType = "TRIAL",
            valueKind = EntitlementService.VALUE_UNLIMITED
        )
        entitlementManagement.registerEntitlement(spec)
        val replay = entitlementManagement.registerEntitlement(spec)
        assertTrue(replay is EntitlementManagementService.RegistrationOutcome.Replayed)
        assertEquals(1, entitlementManagement.listEntitlements().size)
    }

    @Test
    fun `an invalid value shape is rejected with the engine's reason`() = runBlocking {
        val outcome = entitlementManagement.registerEntitlement(
            EntitlementManagementService.RegistrationSpec(
                providerId = "p", applicabilityScopeId = "sd-gptx",
                entitlementType = "FREE_QUOTA",
                valueKind = EntitlementService.VALUE_QUANTITY_LIMIT
                // quantity + unit MISSING → the exactly-one-of CHECK fails
            )
        )
        assertTrue(outcome is EntitlementManagementService.RegistrationOutcome.Rejected)
        assertTrue(
            (outcome as EntitlementManagementService.RegistrationOutcome.Rejected).reason.isNotBlank()
        )
        assertTrue(entitlementManagement.listEntitlements().isEmpty())
    }

    @Test
    fun `a blank provider or scope is rejected before the engine`() = runBlocking {
        val outcome = entitlementManagement.registerEntitlement(
            EntitlementManagementService.RegistrationSpec(
                providerId = " ", applicabilityScopeId = "sd-gptx",
                entitlementType = "FREE_QUOTA", valueKind = EntitlementService.VALUE_UNLIMITED
            )
        )
        assertTrue(outcome is EntitlementManagementService.RegistrationOutcome.Rejected)
    }

    @Test
    fun `an expired window derives isWindowActive false - never a stored lie`() = runBlocking {
        val now = System.currentTimeMillis()
        entitlementManagement.registerEntitlement(
            EntitlementManagementService.RegistrationSpec(
                providerId = "p", applicabilityScopeId = "sd-gptx",
                entitlementType = "TRIAL",
                valueKind = EntitlementService.VALUE_UNLIMITED,
                startsAtEpochMs = now - 10_000,
                expiresAtEpochMs = now - 1_000
            )
        )
        val view = entitlementManagement.listEntitlements().first()
        assertTrue(!view.isWindowActive)
    }
}
