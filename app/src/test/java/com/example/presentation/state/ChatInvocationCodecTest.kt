package com.example.presentation.state

import com.example.domain.core.tools.ToolDeclaration
import com.example.domain.core.tools.ToolParameter
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * ============================================================================
 * ChatInvocationCodecTest — FUNCTIONAL CLOSURE Phase 1 §17/§18/§23
 * ============================================================================
 *
 * The PURE typed-argument contract between the chat capability sheets and
 * the governed execution path — fully JVM (org.json only), no Android:
 *
 *  §17 skills: defaults are visible AND ride the payload; user overrides
 *      replace them; optional blanks are omitted (never a fake empty string).
 *  §18 tools: enum → selection; boolean → boolean; number → numeric
 *      validation; object/array → validated JSON; required → validation;
 *      optional → omission; the payload carries REAL types.
 *  §19 MCP: malformed JSON is a VISIBLE error that blocks the invoke; a
 *      valid object rides canonically; blank means no arguments.
 */
class ChatInvocationCodecTest {

    private fun skillParam(
        name: String,
        required: Boolean = true,
        default: String? = null
    ) = com.example.domain.core.extension.SkillParameterDefinition(
        name = name,
        label = name,
        description = null,
        isRequired = required,
        defaultValue = default
    )

    private fun toolParam(
        name: String,
        type: String = "string",
        required: Boolean = true,
        enum: List<String> = emptyList()
    ) = ToolParameter(
        name = name,
        type = type,
        description = "",
        isRequired = required,
        enumValues = enum
    )

    private fun declaration(vararg parameters: ToolParameter) =
        ToolDeclaration(name = "t", description = "", parameters = parameters.toList())

    // ------------------------------------------------------------------
    // §17 — skill arguments (defaults + optional omission)
    // ------------------------------------------------------------------

    @Test
    fun `a default the user did not touch RIDES the payload`() {
        val outcome = ChatInvocationCodec.buildSkillArguments(
            listOf(
                skillParam("target", required = true, default = "production"),
                skillParam("extra", required = false, default = null)
            ),
            values = emptyMap() // the user typed NOTHING
        )

        assertTrue(outcome is ChatInvocationCodec.BuildOutcome.Ok)
        val json = org.json.JSONObject((outcome as ChatInvocationCodec.BuildOutcome.Ok).argumentsJson)
        // §17: the visible default is in the payload.
        assertEquals("production", json.getString("target"))
        // And the optional blank is OMITTED — never a fake empty string.
        assertFalse(json.has("extra"))
    }

    @Test
    fun `a user override replaces the default`() {
        val outcome = ChatInvocationCodec.buildSkillArguments(
            listOf(skillParam("target", required = true, default = "production")),
            values = mapOf("target" to "staging")
        )

        val json = org.json.JSONObject((outcome as ChatInvocationCodec.BuildOutcome.Ok).argumentsJson)
        assertEquals("staging", json.getString("target"))
    }

    @Test
    fun `a missing required skill parameter is a visible validation error`() {
        val outcome = ChatInvocationCodec.buildSkillArguments(
            listOf(skillParam("target", required = true, default = null)),
            values = emptyMap()
        )

        assertTrue(outcome is ChatInvocationCodec.BuildOutcome.Invalid)
        assertTrue(
            (outcome as ChatInvocationCodec.BuildOutcome.Invalid).reason.contains("target")
        )
    }

    // ------------------------------------------------------------------
    // §18 — tool arguments (typed by declaration)
    // ------------------------------------------------------------------

    @Test
    fun `numbers ride the payload as NUMBERS`() {
        val outcome = ChatInvocationCodec.buildToolArguments(
            declaration(toolParam("count", type = "number")),
            values = mapOf("count" to "42")
        )

        val json = org.json.JSONObject((outcome as ChatInvocationCodec.BuildOutcome.Ok).argumentsJson)
        assertEquals(42L, json.getLong("count"))
    }

    @Test
    fun `a non-numeric number parameter is a visible validation error`() {
        val outcome = ChatInvocationCodec.buildToolArguments(
            declaration(toolParam("count", type = "number")),
            values = mapOf("count" to "ليس رقماً")
        )

        assertTrue(outcome is ChatInvocationCodec.BuildOutcome.Invalid)
        assertTrue((outcome as ChatInvocationCodec.BuildOutcome.Invalid).reason.contains("count"))
    }

    @Test
    fun `booleans ride the payload as BOOLEANS`() {
        val outcome = ChatInvocationCodec.buildToolArguments(
            declaration(toolParam("dryRun", type = "boolean")),
            values = mapOf("dryRun" to "true")
        )

        val json = org.json.JSONObject((outcome as ChatInvocationCodec.BuildOutcome.Ok).argumentsJson)
        assertTrue(json.getBoolean("dryRun"))
    }

    @Test
    fun `a non-boolean boolean parameter is a visible validation error`() {
        val outcome = ChatInvocationCodec.buildToolArguments(
            declaration(toolParam("dryRun", type = "boolean")),
            values = mapOf("dryRun" to "ربما")
        )

        assertTrue(outcome is ChatInvocationCodec.BuildOutcome.Invalid)
    }

    @Test
    fun `an enum parameter must be one of the declared values`() {
        val enumDeclaration = declaration(toolParam("mode", enum = listOf("fast", "safe")))

        val ok = ChatInvocationCodec.buildToolArguments(
            enumDeclaration,
            values = mapOf("mode" to "safe")
        )
        assertTrue(ok is ChatInvocationCodec.BuildOutcome.Ok)

        val bad = ChatInvocationCodec.buildToolArguments(
            enumDeclaration,
            values = mapOf("mode" to "turbo")
        )
        assertTrue(bad is ChatInvocationCodec.BuildOutcome.Invalid)
        assertTrue((bad as ChatInvocationCodec.BuildOutcome.Invalid).reason.contains("fast"))
    }

    @Test
    fun `object and array parameters carry validated structured JSON`() {
        val outcome = ChatInvocationCodec.buildToolArguments(
            declaration(
                toolParam("config", type = "object", required = false),
                toolParam("tags", type = "array", required = false)
            ),
            values = mapOf(
                "config" to """{"a": 1}""",
                "tags" to """["x", "y"]"""
            )
        )

        val json = org.json.JSONObject((outcome as ChatInvocationCodec.BuildOutcome.Ok).argumentsJson)
        assertEquals(1, json.getJSONObject("config").getInt("a"))
        assertEquals("y", json.getJSONArray("tags").getString(1))
    }

    @Test
    fun `malformed object or array JSON is a visible validation error`() {
        val badObject = ChatInvocationCodec.buildToolArguments(
            declaration(toolParam("config", type = "object")),
            values = mapOf("config" to """{"a": """)
        )
        assertTrue(badObject is ChatInvocationCodec.BuildOutcome.Invalid)

        val badArray = ChatInvocationCodec.buildToolArguments(
            declaration(toolParam("tags", type = "array")),
            values = mapOf("tags" to """[1, 2""")
        )
        assertTrue(badArray is ChatInvocationCodec.BuildOutcome.Invalid)
    }

    @Test
    fun `a missing required tool parameter is a visible validation error`() {
        val outcome = ChatInvocationCodec.buildToolArguments(
            declaration(toolParam("path")),
            values = emptyMap()
        )

        assertTrue(outcome is ChatInvocationCodec.BuildOutcome.Invalid)
        assertTrue((outcome as ChatInvocationCodec.BuildOutcome.Invalid).reason.contains("path"))
    }

    @Test
    fun `an optional blank parameter is OMITTED from the payload`() {
        val outcome = ChatInvocationCodec.buildToolArguments(
            declaration(
                toolParam("path", required = true),
                toolParam("note", required = false)
            ),
            values = mapOf("path" to "/tmp", "note" to "   ")
        )

        val json = org.json.JSONObject((outcome as ChatInvocationCodec.BuildOutcome.Ok).argumentsJson)
        assertEquals("/tmp", json.getString("path"))
        // Optional blank → omitted, NOT an empty string the tool must parse.
        assertFalse(json.has("note"))
    }

    // ------------------------------------------------------------------
    // §19 — MCP raw-JSON validation
    // ------------------------------------------------------------------

    @Test
    fun `a blank MCP arguments field means no arguments`() {
        assertTrue(
            ChatInvocationCodec.parseMcpArgumentsJson("") is ChatInvocationCodec.McpArgsOutcome.Empty
        )
        assertTrue(
            ChatInvocationCodec.parseMcpArgumentsJson("   ") is ChatInvocationCodec.McpArgsOutcome.Empty
        )
    }

    @Test
    fun `a valid MCP JSON object rides canonically`() {
        val outcome = ChatInvocationCodec.parseMcpArgumentsJson("""{"query": "أخبار", "limit": 5}""")

        assertTrue(outcome is ChatInvocationCodec.McpArgsOutcome.Ok)
        val json = org.json.JSONObject((outcome as ChatInvocationCodec.McpArgsOutcome.Ok).rawJson)
        assertEquals("أخبار", json.getString("query"))
        assertEquals(5, json.getInt("limit"))
    }

    @Test
    fun `malformed MCP JSON is a VISIBLE error — never silently empty`() {
        val outcome = ChatInvocationCodec.parseMcpArgumentsJson("""{"query": """)

        assertTrue(outcome is ChatInvocationCodec.McpArgsOutcome.Malformed)
        assertTrue(
            (outcome as ChatInvocationCodec.McpArgsOutcome.Malformed).reason.contains("غير صالحة")
        )
    }

    @Test
    fun `a non-object MCP root (array or scalar) is malformed too`() {
        assertTrue(
            ChatInvocationCodec.parseMcpArgumentsJson("""["a", "b"]""") is
                    ChatInvocationCodec.McpArgsOutcome.Malformed
        )
        assertTrue(
            ChatInvocationCodec.parseMcpArgumentsJson("""42""") is
                    ChatInvocationCodec.McpArgsOutcome.Malformed
        )
    }
}
