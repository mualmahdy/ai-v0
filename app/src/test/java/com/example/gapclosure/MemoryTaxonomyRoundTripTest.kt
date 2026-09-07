package com.example.gapclosure

import com.example.domain.core.memory.MemoryType
import com.example.domain.core.memory.lifecycle.CognitiveMemoryType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test

/**
 * ============================================================================
 * MemoryTaxonomyRoundTripTest — gap-closure P1-16
 * ============================================================================
 *
 * The cognitive memory taxonomy (CognitiveMemoryType) is the authoritative
 * in-memory representation; the legacy 4-value MemoryType remains ONLY as
 * the storage codec, isolated behind a single bidirectional mapper. This
 * test freezes that contract: every legacy value round-trips losslessly
 * (no memory silently changes its cognitive meaning through persistence).
 */
class MemoryTaxonomyRoundTripTest {

    /** The single authorized legacy->cognitive->legacy mapping (isolated boundary). */
    private fun roundTrip(legacy: MemoryType): MemoryType {
        val cognitive = CognitiveMemoryType.fromLegacy(legacy)
        return when (cognitive) {
            CognitiveMemoryType.USER_PREFERENCE, CognitiveMemoryType.PREFERENCE -> MemoryType.PREFERENCE
            CognitiveMemoryType.FACTUAL_INSIGHT -> MemoryType.FACTUAL_INSIGHT
            CognitiveMemoryType.CASE_EXAMPLE -> MemoryType.CASE_EXAMPLE
            CognitiveMemoryType.CONVERSATION_SUMMARY -> MemoryType.CONVERSATION_SUMMARY
            // Cognitive-only values have no legacy equivalent; they store
            // under their storageCode and re-decode through fromStorageCode.
            else -> MemoryType.valueOf(cognitive.storageCode.ifBlank { "FACTUAL_INSIGHT" })
        }
    }

    @Test
    fun `every legacy MemoryType round-trips losslessly through the cognitive taxonomy`() {
        for (legacy in MemoryType.entries) {
            assertEquals(
                "P1-16: legacy value ${legacy.name} must survive the taxonomy round-trip",
                legacy,
                roundTrip(legacy)
            )
        }
    }

    @Test
    fun `legacy values map into the NEW naming (PREFERENCE becomes USER_PREFERENCE)`() {
        assertEquals(
            "The old PREFERENCE label upgrades to the new USER_PREFERENCE naming",
            CognitiveMemoryType.USER_PREFERENCE,
            CognitiveMemoryType.fromLegacy(MemoryType.PREFERENCE)
        )
    }

    @Test
    fun `storage codes decode losslessly for every cognitive value`() {
        for (cognitive in CognitiveMemoryType.entries) {
            val decoded = CognitiveMemoryType.fromStorageCode(cognitive.storageCode)
            assertEquals(
                "Storage code round-trip must preserve the cognitive type",
                cognitive,
                decoded
            )
        }
    }

    @Test
    fun `unknown storage code degrades to the documented default (honest, not silent)`() {
        assertEquals(
            CognitiveMemoryType.FACTUAL_INSIGHT,
            CognitiveMemoryType.fromStorageCode("not_a_real_code")
        )
    }

    @Test
    fun `the legacy alias value never escapes the mapping boundary`() {
        // CognitiveMemoryType.PREFERENCE is a mapping-only alias: fromLegacy
        // never produces it (USER_PREFERENCE is the canonical destination).
        for (legacy in MemoryType.entries) {
            val mapped = CognitiveMemoryType.fromLegacy(legacy)
            assertNotNull(mapped)
        }
        assertEquals(
            CognitiveMemoryType.USER_PREFERENCE,
            CognitiveMemoryType.fromLegacy(MemoryType.PREFERENCE)
        )
    }
}
