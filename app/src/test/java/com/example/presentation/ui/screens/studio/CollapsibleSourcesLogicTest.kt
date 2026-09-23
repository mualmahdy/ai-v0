package com.example.presentation.ui.screens.studio

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * CHAT FINAL CLOSURE (§11 actionable sources) — the PURE citation-row logic:
 * which URLs are browser-openable, and the readable domain label. The row
 * UI itself is a thin renderer over these decisions.
 */
class CollapsibleSourcesLogicTest {

    @Test
    fun `http and https urls are browser-openable`() {
        assertTrue("https://example.com/a".isWebUrl())
        assertTrue("http://example.com/a".isWebUrl())
        assertTrue("HTTPS://EXAMPLE.COM/A".isWebUrl())
    }

    @Test
    fun `internal and missing urls are NOT browser-openable`() {
        assertFalse("workspace://reports/q3.txt".isWebUrl())
        assertFalse("content://media/external/42".isWebUrl())
        assertFalse("".isWebUrl())
        assertFalse(null.isWebUrl())
        assertFalse("not-a-url".isWebUrl())
    }

    @Test
    fun `the readable domain strips the www prefix`() {
        assertEquals("example.com", sourceDomain("https://www.example.com/article?x=1"))
        assertEquals("news.org", sourceDomain("https://news.org/story"))
    }

    @Test
    fun `the domain is null for absent or malformed urls - the provider id is the honest fallback`() {
        assertNull(sourceDomain(null))
        assertNull(sourceDomain(""))
        // A malformed URI has no parseable host.
        assertNull(sourceDomain("::::"))
        // A workspace:// citation still resolves its "host" segment — the
        // row stays readable (openability is decided separately by isWebUrl).
        assertEquals("reports", sourceDomain("workspace://reports/q3.txt"))
    }
}
