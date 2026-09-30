package com.example.presentation.state

/**
 * ============================================================================
 * EMERGENCY HOTFIX R2 — the regeneration turn marker
 * ============================================================================
 *
 * User report: "the regenerate button sometimes repeats the message in the
 * chat."
 *
 * Root cause: a targeted regeneration is NON-DESTRUCTIVE by design — the
 * live timeline anchors the new answer under the ORIGINAL user entry and
 * does not repeat the question. The DURABLE layer, however, persisted the
 * regenerated execution as a full new turn (prompt + answer), so reopening
 * the session rebuilt the timeline as
 *
 *     [user: Q] [assistant: A1] [user: Q again] [assistant: A2]
 *
 * — the question visibly duplicated, "sometimes" (exactly: after a reopen /
 * session switch). The marker closes the gap WITHOUT a schema migration:
 * the regenerated turn's PROMPT column carries an invisible WORD JOINER
 * prefix; [rebuildTimeline] then anchors that turn's assistant entry to the
 * original user entry instead of minting a duplicate user bubble — the
 * reopened transcript matches what the live conversation actually showed.
 *
 * Pure string contract: marking is idempotent, stripping is lossless, and
 * the marker survives the Room round-trip (it is a plain BMP character).
 */
object RegenerationTurnMarker {

    /** U+2060 WORD JOINER — invisible, zero-width, non-breaking. */
    private const val MARKER = "\u2060"

    /** Marks a prompt as belonging to a REGENERATED turn (idempotent). */
    fun mark(prompt: String): String = if (isRegeneration(prompt)) prompt else MARKER + prompt

    /** TRUE when the stored prompt belongs to a regenerated turn. */
    fun isRegeneration(prompt: String): Boolean = prompt.startsWith(MARKER)

    /** Removes the marker — the user-visible prompt text. */
    fun strip(prompt: String): String =
        if (isRegeneration(prompt)) prompt.removePrefix(MARKER) else prompt
}
