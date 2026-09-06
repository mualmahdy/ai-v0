package com.example.infrastructure.memory.semantic

/**
 * WordPiece tokenizer (pure Kotlin — unit-testable on the JVM).
 *
 * Implements the BERT-style WordPiece algorithm used by the MiniLM family of
 * sentence-transformer models served through ONNX Runtime:
 *
 *   raw text -> lowercased/cleaned -> whitespace pre-tokenization ->
 *   punctuation splitting -> greedy longest-match-first WordPiece ->
 *   [CLS] tokens [SEP] -> inputIds / attentionMask / tokenTypeIds.
 *
 * This is the REAL tokenizer that matches the model's embedding space —
 * NOT a hash-based substitute.
 */
class WordPieceTokenizer(
    private val vocab: Map<String, Int>,
    val maxSequenceLength: Int = 256,
    private val clsToken: String = "[CLS]",
    private val sepToken: String = "[SEP]",
    private val unkToken: String = "[UNK]",
    private val padToken: String = "[PAD]",
    private val doLowerCase: Boolean = true
) {

    private val clsId = vocab[clsToken] ?: 101
    private val sepId = vocab[sepToken] ?: 102
    private val unkId = vocab[unkToken] ?: 100
    private val padId = vocab[padToken] ?: 0

    data class Encoding(
        val inputIds: LongArray,
        val attentionMask: LongArray,
        val tokenTypeIds: LongArray
    )

    fun encode(text: String): Encoding {
        val pieces = tokenize(text)
        val trimmed = pieces.take(maxSequenceLength - 2) // reserve [CLS] + [SEP]
        val ids = LongArray(maxSequenceLength) { padId.toLong() }
        val mask = LongArray(maxSequenceLength)
        val types = LongArray(maxSequenceLength)

        ids[0] = clsId.toLong()
        mask[0] = 1L
        for (i in trimmed.indices) {
            ids[i + 1] = (vocab[trimmed[i]] ?: unkId).toLong()
            mask[i + 1] = 1L
        }
        ids[trimmed.size + 1] = sepId.toLong()
        mask[trimmed.size + 1] = 1L

        return Encoding(ids, mask, types)
    }

    /** Batch encode with fixed padding (model static shape). */
    fun encodeBatch(texts: List<String>): List<Encoding> = texts.map { encode(it) }

    /**
     * WordPiece segmentation of one pre-tokenized word. Greedy
     * longest-match-first with "##" continuation prefixes.
     */
    fun tokenizeWord(word: String): List<String> {
        if (word.isEmpty()) return emptyList()
        val tokens = mutableListOf<String>()
        var start = 0
        while (start < word.length) {
            var end = word.length
            var current: String? = null
            while (start < end) {
                val candidate = if (start == 0) word.substring(0, end) else "##" + word.substring(start, end)
                if (vocab.containsKey(candidate)) {
                    current = candidate
                    break
                }
                end--
            }
            if (current == null) {
                // Whole word is unknown -> single [UNK] (BERT convention).
                return listOf(unkToken)
            }
            tokens.add(current)
            start = end
        }
        return tokens
    }

    /** Full pipeline: clean -> pre-tokenize -> wordpiece. */
    fun tokenize(text: String): List<String> {
        val cleaned = if (doLowerCase) basicClean(text.lowercase()) else basicClean(text)
        val words = splitWords(cleaned)
        val out = mutableListOf<String>()
        for (word in words) {
            out.addAll(tokenizeWord(word))
        }
        return out
    }

    /** Strips control chars and normalizes whitespace (CJK chars kept — Arabic/Chinese need them). */
    private fun basicClean(text: String): String =
        text.filterNot { it.isISOControl() }.trim()

    /**
     * Splits on whitespace AND isolates punctuation as separate tokens
     * (BERT convention) — this includes Arabic punctuation ، ؛ ؟.
     */
    private fun splitWords(text: String): List<String> {
        val words = mutableListOf<String>()
        val current = StringBuilder()
        for (ch in text) {
            if (ch.isWhitespace()) {
                if (current.isNotEmpty()) {
                    words.add(current.toString())
                    current.setLength(0)
                }
            } else if (isPunctuation(ch)) {
                if (current.isNotEmpty()) {
                    words.add(current.toString())
                    current.setLength(0)
                }
                words.add(ch.toString())
            } else {
                current.append(ch)
            }
        }
        if (current.isNotEmpty()) words.add(current.toString())
        return words
    }

    private fun isPunctuation(ch: Char): Boolean {
        if (ch.isLetterOrDigit()) return false
        // Unicode letter/digit exclusion covers most scripts; treat common
        // ASCII + Arabic punctuation explicitly (fast path).
        val code = ch.code
        return code in 33..47 || code in 58..64 || code in 91..96 || code in 123..126 ||
            ch == '،' || ch == '؛' || ch == '؟' || ch == '«' || ch == '»'
    }
}
