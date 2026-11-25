package com.example.domain.core.storage

/**
 * ============================================================================
 * FIW Stage 1/2 — FileKindResolver: the ONE type-aware file classification
 * ============================================================================
 * The audit found every consumer guessing file types differently (or not at
 * all — `readText()` was the "universal reader"). This resolver is the
 * single honest source for "what is this file" derived from its NAME:
 *
 *  - [Kind.TEXT]…[Kind.CODE] — decodable as UTF-8 text (editor/preview OK);
 *  - [Kind.IMAGE] / [Kind.PDF] — real binary formats (never `readText`);
 *  - [Kind.BINARY] — any other binary (honest "no inline preview" + export);
 *  - [Kind.UNKNOWN] — no recognizable extension (treated as BINARY until
 *    content proves otherwise — the conservative direction: never decode an
 *    unknown file as text, never claim it was read).
 *
 * MIME values follow the registered IANA types for the common extensions.
 * This is a PURE function of the file name — no filesystem access, no
 * content sniffing (a content sniff would belong to the storage layer).
 */
object FileKindResolver {

    enum class Kind {
        TEXT, MARKDOWN, JSON, CSV, CODE, IMAGE, PDF, BINARY, UNKNOWN
    }

    data class Resolution(
        val kind: Kind,
        val mimeType: String,
        val extension: String
    ) {
        /** True when the file is safely decodable as UTF-8 text content. */
        val isTextual: Boolean get() = kind in setOf(Kind.TEXT, Kind.MARKDOWN, Kind.JSON, Kind.CSV, Kind.CODE)
        /** True for the known real binary formats. */
        val isBinary: Boolean get() = kind == Kind.IMAGE || kind == Kind.PDF || kind == Kind.BINARY || kind == Kind.UNKNOWN
    }

    private val textByExtension = mapOf(
        "txt" to "text/plain",
        "text" to "text/plain",
        "log" to "text/plain",
        "md" to "text/markdown",
        "markdown" to "text/markdown",
        "mdown" to "text/markdown",
        "json" to "application/json",
        "geojson" to "application/json",
        "jsonl" to "application/jsonl",
        "ndjson" to "application/jsonl",
        "csv" to "text/csv",
        "tsv" to "text/tab-separated-values",
        "xml" to "application/xml",
        "yaml" to "application/yaml",
        "yml" to "application/yaml",
        "toml" to "application/toml",
        "ini" to "text/plain",
        "properties" to "text/plain",
        "html" to "text/html",
        "htm" to "text/html",
        "css" to "text/css",
        "kt" to "text/x-kotlin",
        "kts" to "text/x-kotlin",
        "java" to "text/x-java-source",
        "py" to "text/x-python",
        "js" to "text/javascript",
        "mjs" to "text/javascript",
        "ts" to "text/typescript",
        "tsx" to "text/typescript",
        "jsx" to "text/javascript",
        "c" to "text/x-c",
        "h" to "text/x-c",
        "cpp" to "text/x-c++",
        "hpp" to "text/x-c++",
        "cc" to "text/x-c++",
        "cs" to "text/x-csharp",
        "go" to "text/x-go",
        "rs" to "text/x-rust",
        "rb" to "text/x-ruby",
        "php" to "text/x-php",
        "swift" to "text/x-swift",
        "sh" to "application/x-sh",
        "bash" to "application/x-sh",
        "zsh" to "application/x-sh",
        "sql" to "application/sql",
        "gradle" to "text/x-groovy",
        "groovy" to "text/x-groovy",
        "proto" to "text/x-protobuf",
        "gradle.kts" to "text/x-kotlin"
    )

    private val imageByExtension = mapOf(
        "png" to "image/png",
        "jpg" to "image/jpeg",
        "jpeg" to "image/jpeg",
        "gif" to "image/gif",
        "webp" to "image/webp",
        "bmp" to "image/bmp",
        "svg" to "image/svg+xml"
    )

    /** Resolves the honest kind + MIME for a file name (pure, total). */
    fun resolve(fileName: String): Resolution {
        val clean = fileName.substringAfterLast('/').trim()
        val extension = clean.substringAfterLast('.', "").lowercase()
        if (extension.isEmpty()) {
            return Resolution(Kind.UNKNOWN, "application/octet-stream", "")
        }
        textByExtension[extension]?.let { mime ->
            val kind = when {
                extension in setOf("md", "markdown", "mdown") -> Kind.MARKDOWN
                extension in setOf("json", "geojson") -> Kind.JSON
                extension in setOf("csv", "tsv") -> Kind.CSV
                extension in setOf("txt", "text", "log", "ini", "properties") -> Kind.TEXT
                extension == "html" || extension == "htm" || extension == "css" -> Kind.CODE
                else -> Kind.CODE
            }
            return Resolution(kind, mime, extension)
        }
        imageByExtension[extension]?.let { mime ->
            return Resolution(Kind.IMAGE, mime, extension)
        }
        if (extension == "pdf") {
            return Resolution(Kind.PDF, "application/pdf", extension)
        }
        return Resolution(Kind.BINARY, "application/octet-stream", extension)
    }
}
