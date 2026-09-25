package com.example.presentation.state

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * ARTIFACT CANVAS (§10): the ONE textual-preview read policy + the honest
 * size formatter — pure functions, tested without composition because the
 * ViewModel's read decision and the canvas's degradation message BOTH
 * derive from them (a single definition, a single contract).
 */
class ChatArtifactPreviewPolicyTest {

    private fun ref(
        name: String,
        mimeType: String,
        type: String = "DOCUMENT",
        sizeBytes: Long = 2_048
    ) = ChatArtifactRef(
        artifactId = "art_test",
        name = name,
        type = type,
        mimeType = mimeType,
        sizeBytes = sizeBytes,
        storageUri = "file://sandbox/art_test"
    )

    // ---- isTextuallyPreviewable: the mime route ----

    @Test
    fun `any text mime type is previewable`() {
        assertTrue(ref("a.md", "text/markdown").isTextuallyPreviewable())
        assertTrue(ref("a.txt", "text/plain").isTextuallyPreviewable())
        assertTrue(ref("a.csv", "text/csv").isTextuallyPreviewable())
    }

    @Test
    fun `structured text mime types are previewable`() {
        assertTrue(ref("a.json", "application/json").isTextuallyPreviewable())
        assertTrue(ref("a.xml", "application/xml").isTextuallyPreviewable())
        assertTrue(ref("a.js", "text/javascript").isTextuallyPreviewable())
        assertTrue(ref("a.yaml", "application/x-yaml").isTextuallyPreviewable())
    }

    @Test
    fun `mime parameters never break the policy`() {
        assertTrue(ref("a.md", "text/markdown; charset=utf-8").isTextuallyPreviewable())
        assertTrue(ref("a.txt", "TEXT/PLAIN; charset=utf-8").isTextuallyPreviewable())
    }

    @Test
    fun `binary mime types are never previewable - even with a text-looking name`() {
        assertFalse(ref("photo.png", "image/png").isTextuallyPreviewable())
        assertFalse(ref("doc.pdf", "application/pdf").isTextuallyPreviewable())
        assertFalse(ref("blob.bin", "application/octet-stream").isTextuallyPreviewable())
    }

    // ---- isTextuallyPreviewable: the extension fallback (unknown mime) ----

    @Test
    fun `known code and document extensions are previewable via the extension fallback`() {
        assertTrue(ref("Script.kt", "application/octet-stream").isTextuallyPreviewable())
        assertTrue(ref("notes.markdown", "").isTextuallyPreviewable())
        assertTrue(ref("build.gradle", "").isTextuallyPreviewable())
        assertTrue(ref("query.sql", "").isTextuallyPreviewable())
        assertTrue(ref("Config.TOML", "").isTextuallyPreviewable())
    }

    @Test
    fun `unknown extensions without a textual mime stay unpreviewable`() {
        assertFalse(ref("archive.zip", "application/octet-stream").isTextuallyPreviewable())
        assertFalse(ref("library.so", "").isTextuallyPreviewable())
        assertFalse(ref("المرفقات", "", type = "FOLDER").isTextuallyPreviewable())
    }

    // ---- formatArtifactSize: honest human sizes ----

    @Test
    fun `byte-scale sizes render as plain bytes`() {
        assertEquals("0 بايت", formatArtifactSize(0))
        assertEquals("512 بايت", formatArtifactSize(512))
        assertEquals("1023 بايت", formatArtifactSize(1_023))
    }

    @Test
    fun `kilobyte scale renders one decimal`() {
        assertEquals("1.0 ك.ب", formatArtifactSize(1_024))
        assertEquals("1.5 ك.ب", formatArtifactSize(1_536))
        assertEquals("2.0 ك.ب", formatArtifactSize(2_048))
    }

    @Test
    fun `megabyte scale renders one decimal`() {
        assertEquals("1.0 م.ب", formatArtifactSize(1_048_576))
        // 1,500,000 bytes = 1464.8 KiB = 1.43 MiB
        assertEquals("1.4 م.ب", formatArtifactSize(1_500_000))
    }

    @Test
    fun `negative sizes degrade honestly to zero`() {
        assertEquals("0 بايت", formatArtifactSize(-42))
    }
}
