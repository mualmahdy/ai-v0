package com.example.presentation

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import com.example.presentation.ui.screens.studio.ChartPoint
import com.example.presentation.ui.screens.studio.ChatComposer
import com.example.presentation.ui.screens.studio.RichMarkdownContent
import com.example.presentation.ui.screens.studio.DiagramEdgeLabeled
import com.example.presentation.ui.screens.studio.DiagramRenderer
import com.example.presentation.ui.screens.studio.BarChartRenderer
import com.example.presentation.ui.screens.studio.LineChartRenderer
import com.example.presentation.ui.screens.studio.ChartDataFallback
import com.example.presentation.ui.screens.studio.MathNodeView
import com.example.presentation.ui.screens.studio.MathTypesetter
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
 * ClosureTechnicalRenderersRoborazziTest — CLOSURE §20 (visual regression
 * contract for the technical renderers)
 * ============================================================================
 *
 * Reference captures for the CLOSURE-phase rendering surface:
 *  1. REAL math typesetting (fractions with a rule, radical + overline,
 *     baseline-shifted scripts, big operators with limits) — and the
 *     honest Unicode-approximation fallback WITH its visible notice;
 *  2. REAL bar + line charts (axes, gridlines, value labels) and the
 *     honest data-table fallback for non-plottable data;
 *  3. The layered diagram renderer (Arabic node ids + edge labels) and the
 *     RAW SOURCE fallback for unsupported structures;
 *  4. The CONSOLIDATED composer (§13: [+] [input] [send] — no duplicated
 *     agent/model chip, transient gauge) at the three width fixtures
 *     (360dp phone / 411dp phone / 840dp expanded).
 *
 * Determinism: fixed inputs; no clocks; no view models.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = RobolectricDeviceQualifiers.Pixel8, sdk = [36])
class ClosureTechnicalRenderersRoborazziTest {

    @get:Rule
    val composeRule = createComposeRule()

    private fun capture(block: @androidx.compose.runtime.Composable androidx.compose.foundation.layout.ColumnScope.() -> Unit) {
        composeRule.setContent {
            CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Rtl) {
                MyApplicationTheme {
                    androidx.compose.foundation.layout.Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(8.dp)
                    ) {
                        block()
                    }
                }
            }
        }
        composeRule.onRoot().captureRoboImage()
    }

    // ------------------------------------------------------------------
    // 1. Math — the real typesetter + the honest fallback
    // ------------------------------------------------------------------

    @Test
    fun `math typesetter - structural subset renders as layout`() {
        capture {
            val (tree, _) = MathTypesetter.parse("\\frac{a+b}{c-d} + \\sqrt{x^2+1} + \\sum_{i=1}^{n} a_i^2")
            MathNodeView(tree)
        }
    }

    @Test
    fun `math block - approximation fallback carries the visible notice`() {
        capture {
            RichMarkdownContent(
                markdown = "المعادلة:\n\n\$\$\\frac{a}{b}\$\$\n\nوالنص التقريبي خارج المجموعة: matrix ...",
                modifier = Modifier.fillMaxWidth()
            )
        }
    }

    // ------------------------------------------------------------------
    // 2. Charts — real renderers + honest fallback
    // ------------------------------------------------------------------

    @Test
    fun `bar chart renderer - axes gridlines and value labels`() {
        capture {
            BarChartRenderer(
                points = listOf(
                    ChartPoint("يناير", 4f),
                    ChartPoint("فبراير", 7f),
                    ChartPoint("مارس", 5.5f),
                    ChartPoint("أبريل", 9f)
                ),
                modifier = Modifier.fillMaxWidth()
            )
        }
    }

    @Test
    fun `line chart renderer - polyline points and end labels`() {
        capture {
            LineChartRenderer(
                points = listOf(
                    ChartPoint("1", 3f),
                    ChartPoint("2", 6f),
                    ChartPoint("3", 2.5f),
                    ChartPoint("4", 8f),
                    ChartPoint("5", 7f)
                ),
                modifier = Modifier.fillMaxWidth()
            )
        }
    }

    @Test
    fun `chart data fallback - non plottable data renders a readable table`() {
        capture {
            ChartDataFallback(
                points = listOf(
                    ChartPoint("عنصر أ", Float.NaN),
                    ChartPoint("عنصر ب", 2f)
                )
            )
        }
    }

    // ------------------------------------------------------------------
    // 3. Diagrams — layered renderer with Arabic ids + raw fallback
    // ------------------------------------------------------------------

    @Test
    fun `diagram renderer - layered layout with arabic ids and edge labels`() {
        capture {
            DiagramRenderer(
                edges = listOf(
                    DiagramEdgeLabeled("المستخدم", "طلب", "المعالج"),
                    DiagramEdgeLabeled("المعالج", null, "قاعدة البيانات"),
                    DiagramEdgeLabeled("المعالج", "رد", "المستخدم")
                ),
                rawSource = "المستخدم --طلب--> المعالج"
            )
        }
    }

    @Test
    fun `diagram renderer - unsupported source renders raw with a notice`() {
        capture {
            DiagramRenderer(
                edges = emptyList(),
                rawSource = "sequenceDiagram\n    A->>B: غير مدعوم"
            )
        }
    }

    // ------------------------------------------------------------------
    // 4. The consolidated composer at the width fixtures (§13/§20)
    // ------------------------------------------------------------------

    @Test
    @Config(qualifiers = "w360dp-h800dp-360dpi")
    fun `consolidated composer at 360dp - input and primary actions only`() {
        capture {
            ChatComposer(
                value = "اكتب رسالة…",
                isExecuting = false,
                onValueChange = {},
                onSend = {},
                onCancel = {},
                onClearDraft = {}
            )
        }
    }

    @Test
    @Config(qualifiers = RobolectricDeviceQualifiers.Pixel8)
    fun `consolidated composer at 411dp - executing state`() {
        capture {
            ChatComposer(
                value = "",
                isExecuting = true,
                onValueChange = {},
                onSend = {},
                onCancel = {},
                onClearDraft = {}
            )
        }
    }

    @Test
    @Config(qualifiers = "w840dp-h600dp-480dpi")
    fun `consolidated composer at 840dp - attachment chips state`() {
        capture {
            ChatComposer(
                value = "سؤال عن المرفقات",
                isExecuting = false,
                onValueChange = {},
                onSend = {},
                onCancel = {},
                onClearDraft = {},
                attachmentDrafts = listOf(
                    com.example.domain.core.session.TurnAttachment(
                        id = "attm_1",
                        name = "تقرير.pdf",
                        mimeType = "application/pdf",
                        sizeBytes = 1024,
                        storageUri = "attachments/x.pdf",
                        groundingState = "ATTACHMENT_ONLY"
                    ),
                    com.example.domain.core.session.TurnAttachment(
                        id = "attm_2",
                        name = "مجلد المشروع",
                        mimeType = "inode/directory",
                        sizeBytes = 2048,
                        storageUri = "attachments/folder_1",
                        groundingState = "GROUNDED",
                        folderReportJson = """{"mode":"GROUNDED","totalFiles":10,"readableTextFiles":6,"groundedFiles":5,"ingestedFiles":0,"skippedFiles":5,"digestChars":9000,"notes":[]}"""
                    )
                )
            )
        }
    }
}
