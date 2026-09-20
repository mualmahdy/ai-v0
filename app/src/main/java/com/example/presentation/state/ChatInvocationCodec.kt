package com.example.presentation.state

import com.example.domain.core.tools.ToolDeclaration
import com.example.domain.core.tools.ToolParameter
import org.json.JSONArray
import org.json.JSONObject

/**
 * ============================================================================
 * ChatInvocationCodec — the PURE typed-argument contract between the chat
 * capability sheets and the governed execution path (FUNCTIONAL CLOSURE
 * Phase 1 §17/§18/§19)
 * ============================================================================
 *
 * Every user-facing capability invocation goes through the SAME governed
 * boundary with a JSON arguments payload. This codec owns payload CORRECTNESS
 * at the UI side, so the payload NEVER violates the tool's declaration only
 * for the backend to discover later:
 *
 *  §17 (skills): the manifest's `defaultValue` is SHOWN and RIDDEN BY THE
 *   PAYLOAD — a default the user did not override is still sent; an optional
 *   parameter with no default and no user value is OMITTED (never a fake
 *   empty string).
 *
 *  §18 (tools): parameters are TYPED — enum → selection, boolean → boolean,
 *   number → numeric validation, object/array → validated JSON, required →
 *   validation, optional → omission. The emitted JSON carries real types
 *   (numbers as numbers, booleans as booleans), never stringified values.
 *
 *  §19 (MCP): the raw JSON arguments field is VALIDATED — malformed input is
 *   a VISIBLE error that blocks the invoke; it can never be silently
 *   converted into valid-empty arguments.
 *
 * Pure JVM (org.json is the serialization library already used across this
 * codebase — no new dependency): fully unit-testable without Android.
 */
object ChatInvocationCodec {

    /** The outcome of a payload build: the JSON, or the honest first error. */
    sealed interface BuildOutcome {
        /** The valid, typed arguments JSON (object literal, e.g. `{"a":1}`). */
        data class Ok(val argumentsJson: String) : BuildOutcome

        /** The user-visible validation error (the invoke is blocked). */
        data class Invalid(val reason: String) : BuildOutcome
    }

    // ------------------------------------------------------------------
    // §17 — skill arguments (string parameters with defaults)
    // ------------------------------------------------------------------

    /**
     * Builds a skill's arguments JSON from the form values:
     *  - a NON-BLANK user value wins;
     *  - else the manifest's `defaultValue` rides the payload (THE §17 fix —
     *    a default the user saw but never typed is still sent);
     *  - else: required ⇒ validation error; optional ⇒ OMITTED from the
     *    payload (never a fake empty string).
     */
    fun buildSkillArguments(
        parameters: List<com.example.domain.core.extension.SkillParameterDefinition>,
        values: Map<String, String>
    ): BuildOutcome {
        val json = JSONObject()
        for (parameter in parameters) {
            val userValue = values[parameter.name]?.trim()
            val effective = when {
                !userValue.isNullOrBlank() -> userValue
                !parameter.defaultValue.isNullOrBlank() -> parameter.defaultValue.trim()
                parameter.isRequired -> return BuildOutcome.Invalid(
                    "الوسيط المطلوب «${parameter.label}» فارغ."
                )
                else -> null // optional, no default, no user value → OMITTED
            }
            if (effective != null) json.put(parameter.name, effective)
        }
        return BuildOutcome.Ok(json.toString())
    }

    // ------------------------------------------------------------------
    // §18 — tool arguments (typed by declaration)
    // ------------------------------------------------------------------

    /**
     * Builds a tool's arguments JSON from the RAW form values, coerced to the
     * DECLARED types. Validation is total before any output: the first
     * violation is reported (the UI shows it and blocks the invoke).
     */
    fun buildToolArguments(
        declaration: ToolDeclaration,
        values: Map<String, String>
    ): BuildOutcome {
        val json = JSONObject()
        for (parameter in declaration.parameters) {
            val raw = values[parameter.name]?.trim()
            if (raw.isNullOrEmpty()) {
                if (parameter.isRequired) {
                    return BuildOutcome.Invalid(
                        "الوسيط المطلوب «${parameter.name}» فارغ."
                    )
                }
                // Optional + empty → OMITTED (never a fake empty string).
                continue
            }
            when (val coerced = coerce(parameter, raw)) {
                is CoerceResult.Invalid -> return BuildOutcome.Invalid(coerced.reason)
                is CoerceResult.Value -> json.put(parameter.name, coerced.value)
            }
        }
        return BuildOutcome.Ok(json.toString())
    }

    /** Typed coercion of one raw string per the parameter's declaration. */
    private sealed interface CoerceResult {
        data class Value(val value: Any?) : CoerceResult
        data class Invalid(val reason: String) : CoerceResult
    }

    private fun coerce(parameter: ToolParameter, raw: String): CoerceResult {
        val type = parameter.type.lowercase().trim()
        if (parameter.enumValues.isNotEmpty()) {
            return if (raw in parameter.enumValues) {
                CoerceResult.Value(raw)
            } else {
                CoerceResult.Invalid(
                    "قيمة «${parameter.name}» يجب أن تكون إحدى: " +
                            parameter.enumValues.joinToString("، ")
                )
            }
        }
        return when (type) {
            "number" -> when (val number = parseNumber(raw)) {
                null -> CoerceResult.Invalid(
                    "الوسيط «${parameter.name}» رقمي — «$raw» ليس رقماً صالحاً."
                )
                else -> CoerceResult.Value(number)
            }
            "boolean" -> when (raw.lowercase()) {
                "true" -> CoerceResult.Value(true)
                "false" -> CoerceResult.Value(false)
                else -> CoerceResult.Invalid(
                    "الوسيط «${parameter.name}» منطقي — استخدم true أو false."
                )
            }
            "object" -> {
                val parsed = parseJsonObject(raw)
                if (parsed == null) {
                    CoerceResult.Invalid(
                        "الوسيط «${parameter.name}» كائن JSON — الصيغة غير صالحة."
                    )
                } else {
                    CoerceResult.Value(parsed)
                }
            }
            "array" -> {
                val parsed = parseJsonArray(raw)
                if (parsed == null) {
                    CoerceResult.Invalid(
                        "الوسيط «${parameter.name}» مصفوفة JSON — الصيغة غير صالحة."
                    )
                } else {
                    CoerceResult.Value(parsed)
                }
            }
            // "string" and anything undeclared stay strings (the declaration
            // is the truth — an unknown type is never silently re-typed).
            else -> CoerceResult.Value(raw)
        }
    }

    private fun parseNumber(raw: String): Any? = when {
        raw.contains('.') || raw.contains('e') || raw.contains('E') ->
            raw.toDoubleOrNull()
        else -> raw.toLongOrNull() ?: raw.toDoubleOrNull()
    }

    private fun parseJsonObject(raw: String): Any? = runCatching {
        val obj = JSONObject(raw) // throws unless the ROOT is an object
        obj
    }.getOrNull()

    private fun parseJsonArray(raw: String): Any? = runCatching {
        val array = JSONArray(raw) // throws unless the ROOT is an array
        array
    }.getOrNull()

    // ------------------------------------------------------------------
    // §19 — MCP raw-JSON validation
    // ------------------------------------------------------------------

    /** The outcome of parsing the MCP arguments field. */
    sealed interface McpArgsOutcome {
        /** Blank field → no arguments (the honest, user-intended `{}`). */
        data object Empty : McpArgsOutcome

        /** Valid JSON object → its (string-coerced) argument map + raw JSON. */
        data class Ok(val rawJson: String) : McpArgsOutcome

        /** Malformed / non-object JSON → the visible error; the invoke BLOCKS. */
        data class Malformed(val reason: String) : McpArgsOutcome
    }

    /**
     * Validates the raw MCP arguments field. Malformed JSON (or a non-object
     * root) is a VISIBLE error — it can never silently become valid-empty
     * arguments (the §19 corruption this codec exists to prevent).
     */
    fun parseMcpArgumentsJson(raw: String): McpArgsOutcome {
        val trimmed = raw.trim()
        if (trimmed.isEmpty()) return McpArgsOutcome.Empty
        return try {
            // JSONObject(...) REJECTS non-object roots (arrays/scalars throw)
            // and re-serializes canonically on toString().
            McpArgsOutcome.Ok(JSONObject(trimmed).toString())
        } catch (_: Exception) {
            McpArgsOutcome.Malformed(
                "وسائط JSON غير صالحة — صحّح الصيغة قبل الاستدعاء (مثال: {\"query\": \"…\"})."
            )
        }
    }
}
