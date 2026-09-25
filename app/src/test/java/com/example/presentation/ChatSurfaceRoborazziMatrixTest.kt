package com.example.presentation

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.LayoutDirection
import com.example.presentation.state.ApprovalBlockState
import com.example.presentation.state.CapabilityKind
import com.example.presentation.state.ChatArtifactRef
import com.example.presentation.state.ChatEntry
import com.example.presentation.state.ChatEntryAttachment
import com.example.presentation.state.ChatSourceRef
import com.example.presentation.state.ExecutionPhase
import com.example.presentation.state.ExecutionStep
import com.example.presentation.state.LiveExecutionState
import com.example.presentation.ui.screens.studio.ConversationTimeline
import com.example.presentation.ui.screens.studio.ExecutionLifecycleView
import com.example.ui.theme.MyApplicationTheme
import com.github.takahirom.roborazzi.RobolectricDeviceQualifiers
import com.github.takahirom.roborazzi.captureRoboImage
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * ============================================================================
 * ChatSurfaceRoborazziMatrixTest — the FRONTIER chat-upgrade screenshot
 * matrix (the matrix UI-DESIGN-CLOSURE.md §"DEFERRED" item 3 deferred to
 * the chat redesign it now ships with)
 * ============================================================================
 *
 * The chat surface's reference captures, composed directly over the
 * conversation-first composables (no ViewModel scaffolding — the timeline
 * contract is PURE state in, rendered timeline out; the behavioral suites
 * own the ViewModel wiring):
 *
 *  1. the RICH conversation: user message + assistant answer carrying
 *     markdown (highlighted kotlin fence, bold, table), collapsible
 *     sources, COLLAPSIBLE REASONING (collapsed default), a capability
 *     result block and a PENDING inline approval block;
 *  2. the D-11 merged failure entry (failed + degraded pill) beside a
 *     clean answer;
 *  3. the live THINKING phase (reasoning collapsed AND expanded);
 *  4. the live STREAMING phase rendering markdown as it arrives (the
 *     streaming-polish contract) with the real step history.
 *
 * Determinism contract: every timestamp is FIXED (startedAtMs = 0 kills
 * the live duration ticker; fixed step timestamps pin the elapsed labels)
 * so the captures compare byte-stably against the committed references.
 * Arabic-first: RTL exactly like MainActivity.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = RobolectricDeviceQualifiers.Pixel8, sdk = [36])
class ChatSurfaceRoborazziMatrixTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    private val screenshotDir = "src/test/screenshots/chat"

    private fun rtl(content: @androidx.compose.runtime.Composable () -> Unit) {
        composeTestRule.setContent {
            MyApplicationTheme {
                CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Rtl) {
                    content()
                }
            }
        }
    }

    private val markdownAnswer = """
        **الخلاصة**: المحلّل الجديد يعالج **الغامق** و*المائل* و`المفرّد`.

        ```kotlin
        // توثيق الجلسة
        @Composable
        fun SessionCard(title: String, count: Int = 0) {
            val label = if (count > 0) "دورة ${'$'}count" else "فارغة"
            Text(text = label)
        }
        ```

        | المرحلة | الحالة |
        |---------|--------|
        | التحليل | مكتملة |
        | التنفيذ | جارية |
    """.trimIndent()

    private fun richAssistantEntry(id: String) = ChatEntry.Assistant(
        id = id,
        text = markdownAnswer,
        agentName = "المحلل التقني",
        agentRole = "تحليل الأنظمة",
        isSuccessful = true,
        modelResourceId = "model-x",
        tokensConsumed = 1_240,
        durationMs = 8_400L,
        eventCount = 12,
        sources = listOf(
            ChatSourceRef(title = "توثيق Compose — القوائم", url = "https://example.com/docs", providerId = "search", confidenceScore = 0.9f),
            ChatSourceRef(title = "دليل Kotlin للأنماط", url = null, providerId = "knowledge", confidenceScore = null)
        ),
        artifacts = listOf(
            ChatArtifactRef(
                artifactId = "art_1", name = "تقرير-الجلسة.md", type = "DOCUMENT",
                mimeType = "text/markdown", sizeBytes = 2_048, storageUri = "file://sandbox/art1"
            )
        ),
        reasoning = "أراجع أولاً بنية القوائم في Compose، ثم أقارن أنماط إدارة الحالة، وأخلص إلى أن الحل الأبسط يخدم الحالة المطلوبة هنا دون تعقيد إضافي."
    )

    // ------------------------------------------------------------------
    // 1. The rich conversation
    // ------------------------------------------------------------------

    @Test
    fun chat_timeline_rich_conversation() {
        val timeline = listOf(
            ChatEntry.User(
                id = "u1",
                text = "لخّص لي حالة القوائم في Compose مع مثال كود.",
                attachments = listOf(
                    ChatEntryAttachment(
                        id = "att1", name = "ملاحظات-المراجعة.md", mimeType = "text/markdown",
                        sizeBytes = 3_072, storageUri = "file://sandbox/att1", artifactId = "art_1"
                    )
                )
            ),
            richAssistantEntry("a1"),
            ChatEntry.CapabilityResult(
                id = "cap1",
                kind = CapabilityKind.SEARCH,
                title = "بحث ويبي موثوق",
                summary = "تم — 5 نتائج ذات صلة عالية",
                detail = null,
                sources = listOf(ChatSourceRef(title = "مرجع أول", url = "https://example.com/1", providerId = "search", confidenceScore = 0.8f)),
                isPending = false
            ),
            ChatEntry.ApprovalBlock(
                id = "apr1",
                approvalId = "apr_x",
                executionId = "exec_x",
                toolName = "حذف ملفات مؤقتة",
                riskLevel = "HIGH",
                description = "حذف 12 ملفاً مؤقتاً من مساحة العمل",
                requestedAction = "تنفيذ الحذف",
                justification = "سياسة الحوكمة تتطلب موافقة صريحة للعمليات عالية الخطورة",
                state = ApprovalBlockState.PENDING
            )
        )
        rtl {
            ConversationTimeline(
                timeline = timeline,
                liveExecution = null,
                streamText = "",
                sendSignal = 0,
                conversationKey = "shot-rich",
                emptyContent = {},
                onCopy = {}, onEdit = {}, onRegenerate = {}, onRetry = {},
                onApprove = {}, onReject = {}, onRetryAfterApproval = {}, onGrantAlways = {},
                modifier = Modifier.fillMaxSize()
            )
        }
        composeTestRule.waitForIdle()
        composeTestRule.onRoot().captureRoboImage(filePath = "$screenshotDir/timeline_rich_conversation.png")
    }

    // ------------------------------------------------------------------
    // 2. The D-11 merged failure entry beside a clean answer
    // ------------------------------------------------------------------

    @Test
    fun chat_timeline_merged_failure_entry() {
        val timeline = listOf(
            ChatEntry.User(id = "u2", text = "جرّب التوليد من المزود الاحتياطي"),
            ChatEntry.Assistant(
                id = "a2_merged",
                text = "فشل مزود الاختبار — تمت إعادة المحاولة بنمط تراجعي وأُنتج جزء من الجواب قبل التوقف.",
                agentName = "المحلل التقني",
                agentRole = "تحليل الأنظمة",
                isSuccessful = false,
                modelResourceId = "model-x",
                tokensConsumed = 640,
                durationMs = 3_100L,
                eventCount = 7,
                isDegraded = true,
                reasoning = "أبدأ بالتحقق من جاهزية المزود، وألاحظ انقطاع الاستجابة بعد المقطع الأول."
            ),
            ChatEntry.Assistant(
                id = "a2_clean",
                text = "**الجواب النظيف**: كل شيء سار كما هو مخطط هذه المرة.",
                agentName = "المحلل التقني",
                agentRole = "تحليل الأنظمة",
                isSuccessful = true,
                tokensConsumed = 512,
                durationMs = 1_800L,
                eventCount = 4
            )
        )
        rtl {
            ConversationTimeline(
                timeline = timeline,
                liveExecution = null,
                streamText = "",
                sendSignal = 0,
                conversationKey = "shot-fail",
                emptyContent = {},
                onCopy = {}, onEdit = {}, onRegenerate = {}, onRetry = {},
                onApprove = {}, onReject = {}, onRetryAfterApproval = {}, onGrantAlways = {},
                modifier = Modifier.fillMaxSize()
            )
        }
        composeTestRule.waitForIdle()
        composeTestRule.onRoot().captureRoboImage(filePath = "$screenshotDir/timeline_merged_failure_entry.png")
    }

    // ------------------------------------------------------------------
    // 3. The live THINKING phase (collapsed + expanded reasoning)
    // ------------------------------------------------------------------

    private fun thinkingLive() = LiveExecutionState(
        executionId = "exec_think",
        phase = ExecutionPhase.THINKING,
        startedAtMs = 0L, // kills the duration ticker — deterministic captures
        actionCount = 1,
        toolCount = 1,
        steps = listOf(
            ExecutionStep(label = "استعلام شبكي موثوق", timestampMs = 1_500L),
            ExecutionStep(label = "تم: وُجدت 5 نتائج", timestampMs = 2_800L)
        ),
        originUserEntryId = "u3"
    )

    private val thinkingReasoning =
        "المستخدم يسأل عن مقارنة معمارية؛ سأبدأ بجمع الحقائق من الاستعلام الشبكي، " +
            "ثم أرتّب أوجه المقارنة في جدول، وأختم بتوصية عملية مرتبطة بحجم مشروعه."

    @Test
    fun chat_live_thinking_collapsed() {
        rtl {
            ExecutionLifecycleView(
                live = thinkingLive(),
                streamText = "",
                reasoningText = thinkingReasoning,
                onCopyStream = {}
            )
        }
        composeTestRule.waitForIdle()
        composeTestRule.onRoot().captureRoboImage(filePath = "$screenshotDir/live_thinking_collapsed.png")
    }

    @Test
    fun chat_live_thinking_expanded() {
        rtl {
            ExecutionLifecycleView(
                live = thinkingLive(),
                streamText = "",
                reasoningText = thinkingReasoning,
                onCopyStream = {}
            )
        }
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithTag("reasoning_block_exec_think").performClick()
        composeTestRule.waitForIdle()
        composeTestRule.onRoot().captureRoboImage(filePath = "$screenshotDir/live_thinking_expanded.png")
    }

    // ------------------------------------------------------------------
    // 4. The live STREAMING phase — markdown renders as it arrives
    // ------------------------------------------------------------------

    @Test
    fun chat_live_streaming_markdown() {
        val streamingText = """
            **مقدمة الجواب** تصل تدريجياً وتُنسّق فوراً:

            ```kotlin
            val summary = sessions.sumOf { it.turnCount }
            println("الدورات: ${'$'}summary")
            ```

            وجدول الجزء الواصل حتى الآن:
        """.trimIndent()
        val live = LiveExecutionState(
            executionId = "exec_stream",
            phase = ExecutionPhase.STREAMING,
            startedAtMs = 0L,
            actionCount = 2,
            toolCount = 1,
            steps = listOf(
                ExecutionStep(label = "تنفيذ أداة برمجية مباشرة", timestampMs = 1_200L),
                ExecutionStep(label = "تم: اكتمل الحساب", timestampMs = 2_400L)
            ),
            originUserEntryId = "u4"
        )
        rtl {
            ExecutionLifecycleView(
                live = live,
                streamText = streamingText,
                reasoningText = thinkingReasoning,
                onCopyStream = {}
            )
        }
        composeTestRule.waitForIdle()
        composeTestRule.onRoot().captureRoboImage(filePath = "$screenshotDir/live_streaming_markdown.png")
    }
}
