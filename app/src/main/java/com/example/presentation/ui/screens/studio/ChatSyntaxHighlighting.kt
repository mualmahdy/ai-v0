package com.example.presentation.ui.screens.studio

import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle

/**
 * ============================================================================
 * ChatSyntaxHighlighting — dependency-free code highlighting (FRONTIER
 * chat upgrade 2026)
 * ============================================================================
 *
 * A PURE Kotlin tokenizer (zero Compose state, zero regex-per-char cost on
 * the hot path where avoidable, fully JVM-unit-testable) that splits code
 * lines into typed [HighlightToken]s: keywords, strings, numbers, comments
 * and annotations. The RENDERER half ([toHighlightAnnotatedString] +
 * [CodeHighlightPalette]) maps tokens to theme-derived [AnnotatedString]
 * spans — the SAME palette source in light and dark modes (MaterialTheme
 * colorScheme), so highlighted code never hard-codes colors.
 *
 * Honesty contract (same family as the markdown parser):
 *  - TOLERANT, never crashing: an unterminated string paints the rest of
 *    the line as a string; an unclosed block comment paints to the end of
 *    the code; an unknown language degrades to PLAIN text (still rendered,
 *    never lost, never a guess presented as structure).
 *  - Deliberately a HIGHLIGHTER, not a parser: it does not validate the
 *    language — it colors what looks like keywords/strings/comments so a
 *    model's code fence reads like code.
 */

/** The token kinds the renderer can color. */
enum class HighlightKind { KEYWORD, STRING, NUMBER, COMMENT, ANNOTATION, PLAIN }

/** One colored stretch of a code line. */
data class HighlightToken(val text: String, val kind: HighlightKind)

/** The language families the tokenizer knows (unknown labels → PLAIN). */
enum class CodeLanguageFamily { KOTLIN, JAVA, PYTHON, JAVASCRIPT, JSON, BASH, SQL, XML, C_LIKE, PLAIN }

/** Theme-derived colors for the token kinds (built in composition). */
data class CodeHighlightPalette(
    val keyword: androidx.compose.ui.graphics.Color,
    val string: androidx.compose.ui.graphics.Color,
    val number: androidx.compose.ui.graphics.Color,
    val comment: androidx.compose.ui.graphics.Color,
    val annotation: androidx.compose.ui.graphics.Color,
    val plain: androidx.compose.ui.graphics.Color
)

object ChatSyntaxHighlighting {

    // ------------------------------------------------------------------
    // Language resolution (labels models actually emit)
    // ------------------------------------------------------------------

    fun familyOf(language: String?): CodeLanguageFamily {
        val normalized = language?.trim()?.lowercase().orEmpty()
        if (normalized.isEmpty()) return CodeLanguageFamily.PLAIN
        return when (normalized) {
            "kotlin", "kt" -> CodeLanguageFamily.KOTLIN
            "java" -> CodeLanguageFamily.JAVA
            "python", "py" -> CodeLanguageFamily.PYTHON
            "javascript", "js", "typescript", "ts", "jsx", "tsx", "node" -> CodeLanguageFamily.JAVASCRIPT
            "json" -> CodeLanguageFamily.JSON
            "bash", "sh", "shell", "zsh", "console", "terminal" -> CodeLanguageFamily.BASH
            "sql", "sqlite", "postgres", "mysql" -> CodeLanguageFamily.SQL
            "xml", "html", "svg" -> CodeLanguageFamily.XML
            "c", "cpp", "c++", "csharp", "cs", "go", "rust", "rs", "swift", "php" -> CodeLanguageFamily.C_LIKE
            else -> CodeLanguageFamily.PLAIN
        }
    }

    // ------------------------------------------------------------------
    // Keyword sets (upper-case set lookups, one allocation per family)
    // ------------------------------------------------------------------

    private val kotlinKeywords = setOf(
        "as", "as?", "break", "by", "catch", "class", "companion", "const", "constructor",
        "continue", "crossinline", "data", "do", "dynamic", "else", "enum", "external",
        "false", "final", "finally", "for", "fun", "get", "if", "import", "in", "!in",
        "init", "inline", "interface", "internal", "is", "!is", "lateinit", "noinline",
        "null", "object", "open", "operator", "out", "override", "package", "private",
        "protected", "public", "reified", "return", "sealed", "set", "super", "suspend",
        "tailrec", "this", "throw", "true", "try", "typealias", "val", "var", "vararg",
        "when", "where", "while"
    )

    private val javaKeywords = setOf(
        "abstract", "assert", "boolean", "break", "byte", "case", "catch", "char", "class",
        "const", "continue", "default", "do", "double", "else", "enum", "extends", "final",
        "finally", "float", "for", "goto", "if", "implements", "import", "instanceof",
        "int", "interface", "long", "native", "new", "package", "private", "protected",
        "public", "record", "return", "sealed", "permits", "short", "static", "strictfp",
        "super", "switch", "synchronized", "this", "throw", "throws", "transient", "try",
        "var", "void", "volatile", "while", "yield", "true", "false", "null"
    )

    private val pythonKeywords = setOf(
        "and", "as", "assert", "async", "await", "break", "class", "continue", "def",
        "del", "elif", "else", "except", "finally", "for", "from", "global", "if",
        "import", "in", "is", "lambda", "match", "case", "nonlocal", "not", "or",
        "pass", "raise", "return", "try", "while", "with", "yield", "None", "True",
        "False", "self"
    )

    private val javascriptKeywords = setOf(
        "async", "await", "break", "case", "catch", "class", "const", "continue",
        "debugger", "default", "delete", "do", "else", "enum", "export", "extends",
        "false", "finally", "for", "from", "function", "get", "if", "implements",
        "import", "in", "instanceof", "interface", "let", "namespace", "new", "null",
        "of", "private", "protected", "public", "readonly", "return", "set", "static",
        "super", "switch", "this", "throw", "true", "try", "type", "typeof", "undefined",
        "var", "void", "while", "yield", "declare"
    )

    private val bashKeywords = setOf(
        "if", "then", "else", "elif", "fi", "for", "while", "until", "do", "done",
        "case", "esac", "function", "in", "return", "local", "export", "readonly",
        "declare", "unset", "set", "shift", "source", "alias", "exit", "echo", "cd",
        "ls", "cp", "mv", "rm", "mkdir", "rmdir", "cat", "grep", "sed", "awk", "curl",
        "wget", "sudo", "apt", "chmod", "chown", "kill", "ps", "find", "head", "tail",
        "true", "false"
    )

    private val sqlKeywords = setOf(
        "SELECT", "FROM", "WHERE", "INSERT", "INTO", "VALUES", "UPDATE", "SET", "DELETE",
        "CREATE", "TABLE", "ALTER", "DROP", "INDEX", "VIEW", "JOIN", "LEFT", "RIGHT",
        "INNER", "OUTER", "FULL", "CROSS", "ON", "GROUP", "BY", "ORDER", "HAVING",
        "LIMIT", "OFFSET", "AS", "AND", "OR", "NOT", "NULL", "IS", "PRIMARY", "KEY",
        "FOREIGN", "REFERENCES", "UNIQUE", "DEFAULT", "CHECK", "CONSTRAINT", "DISTINCT",
        "COUNT", "SUM", "AVG", "MIN", "MAX", "CASE", "WHEN", "THEN", "ELSE", "END",
        "UNION", "ALL", "LIKE", "ILIKE", "IN", "BETWEEN", "EXISTS", "ASC", "DESC",
        "WITH", "RECURSIVE", "BEGIN", "COMMIT", "ROLLBACK", "TRANSACTION"
    )

    /** JSON has no keywords — but its three literals read like keywords. */
    private val jsonKeywords = setOf("true", "false", "null")

    private val cLikeKeywords = setOf(
        "alignas", "alignof", "auto", "bool", "break", "case", "catch", "chan", "class",
        "const", "constexpr", "continue", "crate", "default", "defer", "delete", "do",
        "double", "else", "enum", "explicit", "export", "extern", "false", "final",
        "finally", "float", "fn", "for", "friend", "func", "go", "goto", "if",
        "impl", "implements", "import", "inline", "int", "interface", "let", "long",
        "mut", "namespace", "new", "nil", "noexcept", "null", "nullptr", "operator",
        "override", "package", "private", "protected", "public", "record", "register",
        "return", "self", "short", "signed", "sizeof", "static", "struct", "super",
        "switch", "template", "this", "throw", "throws", "trait", "true", "try", "type",
        "typedef", "typename", "union", "unsafe", "unsigned", "use", "using", "val",
        "virtual", "void", "volatile", "where", "while", "yield"
    )

    private fun keywordsFor(family: CodeLanguageFamily): Set<String>? = when (family) {
        CodeLanguageFamily.KOTLIN -> kotlinKeywords
        CodeLanguageFamily.JAVA -> javaKeywords
        CodeLanguageFamily.PYTHON -> pythonKeywords
        CodeLanguageFamily.JAVASCRIPT -> javascriptKeywords
        CodeLanguageFamily.JSON -> jsonKeywords
        CodeLanguageFamily.BASH -> bashKeywords
        CodeLanguageFamily.SQL -> sqlKeywords
        CodeLanguageFamily.XML -> null
        CodeLanguageFamily.C_LIKE -> cLikeKeywords
        CodeLanguageFamily.PLAIN -> null
    }

    /** Family-specific line-comment prefixes. */
    private fun lineCommentPrefix(family: CodeLanguageFamily): String? = when (family) {
        CodeLanguageFamily.KOTLIN, CodeLanguageFamily.JAVA, CodeLanguageFamily.JAVASCRIPT,
        CodeLanguageFamily.C_LIKE -> "//"
        CodeLanguageFamily.PYTHON, CodeLanguageFamily.BASH -> "#"
        CodeLanguageFamily.SQL -> "--"
        else -> null
    }

    /** Whether ' starts a string literal in this family. */
    private fun singleQuoteStrings(family: CodeLanguageFamily): Boolean = when (family) {
        CodeLanguageFamily.KOTLIN, CodeLanguageFamily.JAVA, CodeLanguageFamily.PYTHON,
        CodeLanguageFamily.JAVASCRIPT, CodeLanguageFamily.C_LIKE, CodeLanguageFamily.SQL,
        CodeLanguageFamily.BASH, CodeLanguageFamily.XML -> true
        else -> false
    }

    // ------------------------------------------------------------------
    // Tokenization
    // ------------------------------------------------------------------

    /**
     * Tokenizes a whole code block into PER-LINE token lists (the renderer
     * prints line numbers per row). Block-comment state is threaded across
     * lines internally; adjacent same-kind tokens are merged so the
     * annotated string stays compact.
     */
    fun tokenize(code: String, family: CodeLanguageFamily): List<List<HighlightToken>> {
        var inBlockComment = false
        return code.lines().map { line ->
            val result = tokenizeLine(line, family, inBlockComment)
            inBlockComment = result.endsInBlockComment
            result.tokens
        }
    }

    data class LineTokenizeResult(
        val tokens: List<HighlightToken>,
        val endsInBlockComment: Boolean
    )

    /** Tokenizes ONE line (block-comment carry state in/out). PURE. */
    fun tokenizeLine(
        line: String,
        family: CodeLanguageFamily,
        startsInBlockComment: Boolean = false
    ): LineTokenizeResult {
        val raw = mutableListOf<HighlightToken>()
        var inBlockComment = startsInBlockComment
        var i = 0
        val n = line.length
        val keywords = keywordsFor(family)
        val lineComment = lineCommentPrefix(family)
        val blockComment = family == CodeLanguageFamily.XML ||
            family == CodeLanguageFamily.KOTLIN || family == CodeLanguageFamily.JAVA ||
            family == CodeLanguageFamily.JAVASCRIPT || family == CodeLanguageFamily.C_LIKE
        val xmlComment = family == CodeLanguageFamily.XML

        while (i < n) {
            if (inBlockComment) {
                val close = if (xmlComment) line.indexOf("-->", i) else line.indexOf("*/", i)
                if (close < 0) {
                    raw += HighlightToken(line.substring(i), HighlightKind.COMMENT)
                    return LineTokenizeResult(merge(raw), true)
                }
                val end = close + (if (xmlComment) 3 else 2)
                raw += HighlightToken(line.substring(i, end), HighlightKind.COMMENT)
                i = end
                inBlockComment = false
                continue
            }

            val c = line[i]

            // Line comment — the rest of the line is a comment.
            if (lineComment != null && line.startsWith(lineComment, i)) {
                raw += HighlightToken(line.substring(i), HighlightKind.COMMENT)
                i = n
                break
            }

            // Block comments.
            if (blockComment && line.startsWith("/*", i)) {
                val close = line.indexOf("*/", i + 2)
                if (close < 0) {
                    raw += HighlightToken(line.substring(i), HighlightKind.COMMENT)
                    return LineTokenizeResult(merge(raw), true)
                }
                raw += HighlightToken(line.substring(i, close + 2), HighlightKind.COMMENT)
                i = close + 2
                continue
            }
            if (xmlComment && line.startsWith("<!--", i)) {
                val close = line.indexOf("-->", i + 4)
                if (close < 0) {
                    raw += HighlightToken(line.substring(i), HighlightKind.COMMENT)
                    return LineTokenizeResult(merge(raw), true)
                }
                raw += HighlightToken(line.substring(i, close + 3), HighlightKind.COMMENT)
                i = close + 3
                continue
            }

            // Strings (double-quoted everywhere; single-quoted per family;
            // backticks for JS templates and bash command substitution).
            val quote = when {
                c == '"' -> '"'
                c == '\'' && singleQuoteStrings(family) -> '\''
                c == '`' && (family == CodeLanguageFamily.JAVASCRIPT || family == CodeLanguageFamily.BASH) -> '`'
                else -> null
            }
            if (quote != null) {
                var j = i + 1
                var closed = false
                while (j < n) {
                    if (line[j] == '\\') {
                        j += 2
                        continue
                    }
                    if (line[j] == quote) {
                        closed = true
                        j++
                        break
                    }
                    j++
                }
                if (!closed) j = n // TOLERANT: unterminated string paints to EOL
                raw += HighlightToken(line.substring(i, j), HighlightKind.STRING)
                i = j
                continue
            }

            // Numbers (hex, decimal, underscores, exponent; a digit after an
            // identifier char is part of that identifier — checked below).
            if (c.isDigit() && (i == 0 || !isIdentifierChar(line[i - 1]))) {
                var j = i
                if (c == '0' && i + 1 < n && (line[i + 1] == 'x' || line[i + 1] == 'X')) {
                    j = i + 2
                    while (j < n && (line[j].isDigit() || line[j] in 'a'..'f' || line[j] in 'A'..'F' || line[j] == '_')) j++
                } else {
                    while (j < n && (line[j].isDigit() || line[j] == '_' || line[j] == '.')) {
                        // stop if the '.' is not followed by a digit (range like 1..5)
                        if (line[j] == '.' && (j + 1 >= n || !line[j + 1].isDigit())) break
                        j++
                    }
                    if (j < n && (line[j] == 'e' || line[j] == 'E')) {
                        var k = j + 1
                        if (k < n && (line[k] == '+' || line[k] == '-')) k++
                        if (k < n && line[k].isDigit()) {
                            j = k
                            while (j < n && (line[j].isDigit() || line[j] == '_')) j++
                        }
                    }
                    // numeric literal suffixes (L, f, u, ul…)
                    while (j < n && line[j].lowercaseChar() in "lufd") {
                        if (j + 1 < n && isIdentifierChar(line[j + 1])) break
                        j++
                    }
                }
                raw += HighlightToken(line.substring(i, j), HighlightKind.NUMBER)
                i = j
                continue
            }

            // Annotations / decorators: @Name (kotlin/java/python).
            if (c == '@' && i + 1 < n && (line[i + 1].isLetter() || line[i + 1] == '_') &&
                (family == CodeLanguageFamily.KOTLIN || family == CodeLanguageFamily.JAVA ||
                    family == CodeLanguageFamily.PYTHON)
            ) {
                var j = i + 1
                while (j < n && isIdentifierChar(line[j])) j++
                raw += HighlightToken(line.substring(i, j), HighlightKind.ANNOTATION)
                i = j
                continue
            }

            // XML tags: </name and <name → the tag name is the KEYWORD.
            if (family == CodeLanguageFamily.XML && c == '<' && i + 1 < n &&
                (line[i + 1].isLetter() || line[i + 1] == '/' || line[i + 1] == '!' || line[i + 1] == '?')
            ) {
                var j = i + 1
                if (line[j] == '/') j++
                val nameStart = j
                while (j < n && (isIdentifierChar(line[j]) || line[j] == ':' || line[j] == '-' || line[j] == '.')) j++
                if (j > nameStart) {
                    raw += HighlightToken(line.substring(i, nameStart), HighlightKind.PLAIN)
                    raw += HighlightToken(line.substring(nameStart, j), HighlightKind.KEYWORD)
                    i = j
                    continue
                }
            }

            // Identifiers / keywords.
            if (c.isLetter() || c == '_') {
                var j = i
                while (j < n && isIdentifierChar(line[j])) j++
                val word = line.substring(i, j)
                val isKeyword = keywords != null && (
                    (family == CodeLanguageFamily.SQL && word.uppercase() in keywords) ||
                        (family != CodeLanguageFamily.SQL && word in keywords)
                    )
                raw += HighlightToken(
                    word,
                    if (isKeyword) HighlightKind.KEYWORD else HighlightKind.PLAIN
                )
                i = j
                continue
            }

            // Everything else: plain.
            raw += HighlightToken(c.toString(), HighlightKind.PLAIN)
            i++
        }

        val merged = merge(if (family == CodeLanguageFamily.JSON) markJsonKeys(merge(raw)) else merge(raw))
        return LineTokenizeResult(merged, inBlockComment)
    }

    /**
     * JSON: a STRING immediately followed by ':' is an object KEY — recolor
     * it as KEYWORD so JSON structure reads at a glance.
     */
    private fun markJsonKeys(tokens: List<HighlightToken>): List<HighlightToken> {
        val out = tokens.toMutableList()
        for (i in out.indices) {
            if (out[i].kind != HighlightKind.STRING) continue
            var j = i + 1
            while (j < out.size && out[j].text.isBlank()) j++
            if (j < out.size && out[j].text.startsWith(":")) {
                out[i] = out[i].copy(kind = HighlightKind.KEYWORD)
            }
        }
        return out
    }

    /** Merges adjacent tokens of the same kind (compact spans). */
    private fun merge(tokens: List<HighlightToken>): List<HighlightToken> {
        val merged = mutableListOf<HighlightToken>()
        for (token in tokens) {
            val last = merged.lastOrNull()
            if (last != null && last.kind == token.kind && last.text.isNotEmpty() && token.text.isNotEmpty()) {
                merged[merged.size - 1] = last.copy(text = last.text + token.text)
            } else {
                merged += token
            }
        }
        return merged
    }

    private fun isIdentifierChar(c: Char): Boolean =
        c.isLetterOrDigit() || c == '_'
}

/**
 * PURE tokens → colored [AnnotatedString]: keywords bold-tinted, strings
 * tertiary, numbers secondary, comments outline, annotations italic.
 */
fun List<HighlightToken>.toHighlightAnnotatedString(palette: CodeHighlightPalette): AnnotatedString =
    buildAnnotatedString {
        this@toHighlightAnnotatedString.forEach { token ->
            when (token.kind) {
                HighlightKind.KEYWORD -> withStyle(
                    SpanStyle(color = palette.keyword, fontWeight = FontWeight.SemiBold)
                ) { append(token.text) }

                HighlightKind.STRING -> withStyle(
                    SpanStyle(color = palette.string)
                ) { append(token.text) }

                HighlightKind.NUMBER -> withStyle(
                    SpanStyle(color = palette.number)
                ) { append(token.text) }

                HighlightKind.COMMENT -> withStyle(
                    SpanStyle(color = palette.comment, fontStyle = FontStyle.Italic)
                ) { append(token.text) }

                HighlightKind.ANNOTATION -> withStyle(
                    SpanStyle(color = palette.annotation, fontStyle = FontStyle.Italic)
                ) { append(token.text) }

                HighlightKind.PLAIN -> withStyle(
                    SpanStyle(color = palette.plain)
                ) { append(token.text) }
            }
        }
    }
