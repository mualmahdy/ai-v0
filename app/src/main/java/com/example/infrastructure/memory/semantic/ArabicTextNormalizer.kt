package com.example.infrastructure.memory.semantic

/**
 * Arabic text normalization for retrieval quality (audit 2026 fix).
 *
 * Arabic text has orthographic variants that split vector and lexical matches:
 *  - أ / إ / آ / ٱ → ا   (alef variants)
 *  - ة → ه               (taa marbuta)
 *  - ى → ي               (alef maqsura)
 *  - diacritics (tashkeel) removed
 *  - ــ (tatweel/kashida) removed
 *
 * Used by both the lexical fallback embedding and RAG's lexical scoring so
 * Arabic queries match Arabic documents reliably.
 */
object ArabicTextNormalizer {

    private val tashkeelRegex = Regex("[\\u064B-\\u065F\\u0670]")
    private val tatweelRegex = Regex("\\u0640")

    fun normalize(text: String): String {
        var out = text
        out = tashkeelRegex.replace(out, "")
        out = tatweelRegex.replace(out, "")
        val sb = StringBuilder(out.length)
        for (ch in out) {
            when (ch) {
                'أ', 'إ', 'آ', 'ٱ' -> sb.append('ا')
                'ة' -> sb.append('ه')
                'ى' -> sb.append('ي')
                'ؤ' -> sb.append('و')
                'ئ' -> sb.append('ي')
                else -> sb.append(ch)
            }
        }
        return sb.toString()
    }
}
