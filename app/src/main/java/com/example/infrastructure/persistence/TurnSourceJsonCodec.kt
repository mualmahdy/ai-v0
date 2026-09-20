package com.example.infrastructure.persistence

import com.example.domain.core.session.TurnSourceRef
import org.json.JSONArray
import org.json.JSONObject

/**
 * ============================================================================
 * TurnSourceJsonCodec — the durable serialization of a turn's citation
 * chains (FUNCTIONAL CLOSURE Phase 1 §9, DB v19)
 * ============================================================================
 *
 * ONE lossless codec used by both sides of the Room mapping (write on
 * appendTurn, read on session reopen), mirroring [TurnAttachmentJsonCodec]'s
 * honesty contract: unknown fields are ignored on read, and a malformed
 * array degrades to an EMPTY source list (the turn's text stays intact —
 * sources rendering never takes the conversation down), never to a crash.
 */
object TurnSourceJsonCodec {

    fun encode(sources: List<TurnSourceRef>): String {
        val array = JSONArray()
        for (source in sources) {
            array.put(
                JSONObject()
                    .put("title", source.title)
                    .apply {
                        source.url?.let { put("url", it) }
                        source.providerId?.let { put("providerId", it) }
                        source.confidenceScore?.let { put("confidenceScore", it.toDouble()) }
                    }
            )
        }
        return array.toString()
    }

    fun decode(json: String?): List<TurnSourceRef> {
        if (json.isNullOrBlank()) return emptyList()
        return try {
            val array = JSONArray(json)
            val result = ArrayList<TurnSourceRef>(array.length())
            for (index in 0 until array.length()) {
                val obj = array.optJSONObject(index) ?: continue
                val title = obj.optString("title", "")
                if (title.isBlank()) continue
                result += TurnSourceRef(
                    title = title,
                    url = obj.optString("url").takeIf { it.isNotBlank() },
                    providerId = obj.optString("providerId").takeIf { it.isNotBlank() },
                    confidenceScore = if (obj.has("confidenceScore") && !obj.isNull("confidenceScore")) {
                        obj.optDouble("confidenceScore", 0.0).toFloat()
                    } else {
                        null
                    }
                )
            }
            result
        } catch (_: Exception) {
            // Malformed legacy payload — degrade to "no sources" honestly.
            emptyList()
        }
    }
}
