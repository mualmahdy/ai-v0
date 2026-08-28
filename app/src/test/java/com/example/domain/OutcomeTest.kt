package com.example.domain

import com.example.domain.core.DegradedReason
import com.example.domain.core.Outcome
import com.example.domain.core.flatMap
import com.example.domain.core.isDegraded
import com.example.domain.core.isError
import com.example.domain.core.isSuccess
import com.example.domain.core.map
import com.example.domain.core.valueOrNull
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class OutcomeTest {

    @Test
    fun `test Outcome Success map and flatMap`() {
        val success: Outcome<Int, String> = Outcome.Success(10)
        assertTrue(success.isSuccess())
        assertFalse(success.isDegraded())
        assertFalse(success.isError())
        assertEquals(10, success.valueOrNull())

        val mapped = success.map { it * 2 }
        assertTrue(mapped is Outcome.Success)
        assertEquals(20, (mapped as Outcome.Success).value)

        val flatMapped = mapped.flatMap { Outcome.Success("Value: $it") }
        assertTrue(flatMapped is Outcome.Success)
        assertEquals("Value: 20", (flatMapped as Outcome.Success).value)
    }

    @Test
    fun `test Outcome Degraded preserving diagnostic reasons`() {
        val degraded: Outcome<String, String> = Outcome.Degraded(
            partialValue = "Partial Text",
            reason = DegradedReason.LEXICAL_FALLBACK,
            diagnosticMessage = "Lexical search was used"
        )

        assertTrue(degraded.isDegraded())
        assertFalse(degraded.isSuccess())
        assertEquals("Partial Text", degraded.valueOrNull())

        val mapped = degraded.map { it.uppercase() }
        assertTrue(mapped is Outcome.Degraded)
        assertEquals("PARTIAL TEXT", (mapped as Outcome.Degraded).partialValue)
        assertEquals(DegradedReason.LEXICAL_FALLBACK, mapped.reason)
    }

    @Test
    fun `test Outcome Error propagation`() {
        val error: Outcome<Int, String> = Outcome.Error("ACCESS_DENIED", "Security policy violated")
        assertTrue(error.isError())
        assertNull(error.valueOrNull())

        val mapped = error.map { it * 5 }
        assertTrue(mapped is Outcome.Error)
        assertEquals("ACCESS_DENIED", (mapped as Outcome.Error).failure)
    }
}
