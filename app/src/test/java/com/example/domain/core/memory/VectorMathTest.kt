package com.example.domain.core.memory

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * ============================================================================
 * GAP-28 (Design Closure 2026) — VectorMathTest
 * ============================================================================
 *
 * The single similarity kernel's CONTRACT, pinned directly:
 *  - identical vectors → +1, opposite vectors → -1 (coercion bounds);
 *  - orthogonal vectors → 0;
 *  - dimension mismatch → 0 (STRICT: never a min-length truncated score —
 *    that was RagPipelineService's old behavior, deliberately upgraded);
 *  - empty / zero vectors → 0;
 *  - the shared lexical builder: L2-normalized, deterministic,
 *    dimension-parameterized, term-frequency weighted.
 */
class VectorMathTest {

    // --- cosine kernel contract ---

    @Test
    fun `identical vectors score one`() {
        assertEquals(1.0f, VectorMath.cosine(floatArrayOf(1f, 2f, 3f), floatArrayOf(1f, 2f, 3f)), 1e-6f)
    }

    @Test
    fun `opposite vectors score minus one`() {
        assertEquals(-1.0f, VectorMath.cosine(floatArrayOf(1f, 0f), floatArrayOf(-1f, 0f)), 1e-6f)
    }

    @Test
    fun `orthogonal vectors score zero`() {
        assertEquals(0.0f, VectorMath.cosine(floatArrayOf(1f, 0f), floatArrayOf(0f, 1f)), 1e-6f)
    }

    @Test
    fun `dimension mismatch scores ZERO - never a truncated pseudo-score`() {
        // The old RagPipelineService kernel would have compared the first 2
        // dimensions and returned a fabricated score here. The strict kernel
        // refuses to compare different vector spaces at all.
        assertEquals(0.0f, VectorMath.cosine(floatArrayOf(1f, 1f), floatArrayOf(1f, 1f, 1f)))
    }

    @Test
    fun `empty vectors score zero`() {
        assertEquals(0.0f, VectorMath.cosine(floatArrayOf(), floatArrayOf(1f)))
        assertEquals(0.0f, VectorMath.cosine(floatArrayOf(), floatArrayOf()))
    }

    @Test
    fun `zero vector scores zero`() {
        assertEquals(0.0f, VectorMath.cosine(floatArrayOf(0f, 0f), floatArrayOf(1f, 1f)))
    }

    @Test
    fun `result is scale invariant within the cosine range`() {
        // Parallel vectors of any magnitude stay at 1.
        assertEquals(1.0f, VectorMath.cosine(floatArrayOf(0.001f, 0.002f), floatArrayOf(100f, 200f)), 1e-4f)
    }

    // --- shared lexical builder contract ---

    @Test
    fun `lexical vector is L2-normalized`() {
        val v = VectorMath.lexicalSparseVector("hello world hello")
        val norm = kotlin.math.sqrt(v.values.sumOf { (it * it).toDouble() })
        assertEquals("cosine == dot product requires a unit vector", 1.0, norm, 1e-6)
    }

    @Test
    fun `lexical vector is deterministic and self-similar`() {
        val a = VectorMath.lexicalSparseVector("الذاكرة الدلالية المحلية")
        val b = VectorMath.lexicalSparseVector("الذاكرة الدلالية المحلية")
        assertEquals(1.0f, VectorMath.cosine(a.values, b.values), 1e-6f)
        assertEquals(a.dimension, b.dimension)
    }

    @Test
    fun `lexical vector respects the dimension parameter`() {
        assertEquals(128, VectorMath.lexicalSparseVector("text").dimension)
        assertEquals(64, VectorMath.lexicalSparseVector("text", dimension = 64).dimension)
    }

    @Test
    fun `blank text produces an all-zero vector - scored zero against anything`() {
        val v = VectorMath.lexicalSparseVector("   ")
        assertTrue(v.values.all { it == 0f })
        assertEquals(0.0f, VectorMath.cosine(v.values, VectorMath.lexicalSparseVector("anything").values))
    }

    @Test
    fun `term frequency weighting - repeated token dominates the bucket mass`() {
        // "alpha beta alpha alpha": tf(alpha)=3, tf(beta)=1 → squared mass
        // 9+1=10. A single-token "beta" query is the unit vector e_beta, so
        // the cosine is beta's tf over the total norm: 1/sqrt(10).
        val mixed = VectorMath.lexicalSparseVector("alpha beta alpha alpha")
        val onlyBeta = VectorMath.lexicalSparseVector("beta")
        val sim = VectorMath.cosine(mixed.values, onlyBeta.values)
        assertEquals(1f / kotlin.math.sqrt(10f), sim, 1e-5f)
    }
}
