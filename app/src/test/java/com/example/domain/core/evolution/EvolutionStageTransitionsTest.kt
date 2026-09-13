package com.example.domain.core.evolution

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * GAP-19 (Design Closure 2026, ADR-6 step 6): the evolution stage transition
 * table is a DOMAIN authority — both the pipeline's governance gate and the
 * radar screen's buttons derive from it. This pins the table contract.
 */
class EvolutionStageTransitionsTest {

    @Test
    fun `the ordered pipeline ends at REGISTERED and excludes REJECTED`() {
        assertEquals(9, EvolutionStageTransitions.ordered.size)
        assertEquals(EvolutionStage.DISCOVERED, EvolutionStageTransitions.ordered.first())
        assertEquals(EvolutionStage.REGISTERED, EvolutionStageTransitions.ordered.last())
        assertFalse(EvolutionStageTransitions.ordered.contains(EvolutionStage.REJECTED))
    }

    @Test
    fun `nextOf walks one forward step and is null at the terminals`() {
        assertEquals(EvolutionStage.UNDERSTOOD, EvolutionStageTransitions.nextOf(EvolutionStage.DISCOVERED))
        assertEquals(EvolutionStage.CLASSIFIED, EvolutionStageTransitions.nextOf(EvolutionStage.UNDERSTOOD))
        assertEquals(EvolutionStage.EVALUATED, EvolutionStageTransitions.nextOf(EvolutionStage.CLASSIFIED))
        assertEquals(EvolutionStage.CANDIDATE, EvolutionStageTransitions.nextOf(EvolutionStage.EVALUATED))
        assertEquals(EvolutionStage.APPROVAL_PENDING, EvolutionStageTransitions.nextOf(EvolutionStage.CANDIDATE))
        assertEquals(EvolutionStage.INTEGRATED, EvolutionStageTransitions.nextOf(EvolutionStage.APPROVAL_PENDING))
        assertEquals(EvolutionStage.VERIFIED, EvolutionStageTransitions.nextOf(EvolutionStage.INTEGRATED))
        assertEquals(EvolutionStage.REGISTERED, EvolutionStageTransitions.nextOf(EvolutionStage.VERIFIED))
        assertNull(EvolutionStageTransitions.nextOf(EvolutionStage.REGISTERED))
        assertNull(EvolutionStageTransitions.nextOf(EvolutionStage.REJECTED))
    }

    @Test
    fun `allowed transitions are single forward steps or rejection from anywhere`() {
        assertTrue(EvolutionStageTransitions.isAllowedTransition(EvolutionStage.DISCOVERED, EvolutionStage.UNDERSTOOD))
        // Skipping a stage is forbidden (the pipeline gate rejects it).
        assertFalse(EvolutionStageTransitions.isAllowedTransition(EvolutionStage.DISCOVERED, EvolutionStage.CLASSIFIED))
        assertFalse(EvolutionStageTransitions.isAllowedTransition(EvolutionStage.CANDIDATE, EvolutionStage.REGISTERED))
        // Backwards is forbidden.
        assertFalse(EvolutionStageTransitions.isAllowedTransition(EvolutionStage.VERIFIED, EvolutionStage.INTEGRATED))
        // Rejection is reachable from anywhere.
        assertTrue(EvolutionStageTransitions.isAllowedTransition(EvolutionStage.DISCOVERED, EvolutionStage.REJECTED))
        assertTrue(EvolutionStageTransitions.isAllowedTransition(EvolutionStage.REGISTERED, EvolutionStage.REJECTED))
    }
}
