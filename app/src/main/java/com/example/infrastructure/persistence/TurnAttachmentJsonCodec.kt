package com.example.infrastructure.persistence

import com.example.domain.core.session.TurnAttachment
import org.json.JSONArray
import org.json.JSONObject

/**
 * ============================================================================
 * TurnAttachmentJsonCodec — the durable serialization of a turn's attachment
 * references (CHAT CAPABILITIES Task 2 §16, DB v18)
 * ============================================================================
 *
 * ONE lossless codec used by both sides of the Room mapping (write on
 * appendTurn, read on session reopen). org.json is the serialization library
 * already used across this persistence layer (see SessionExportService's
 * canonical JSON) — no new dependency.
 *
 * Honesty contract: unknown fields are ignored on read, and a malformed
 * array degrades to an EMPTY attachment list (the turn's text stays intact —
 * attachment rendering never takes the conversation down), never to a crash.
 */
object TurnAttachmentJsonCodec {

    fun encode(attachments: List<TurnAttachment>): String {
        val array = JSONArray()
        for (attachment in attachments) {
            array.put(
                JSONObject()
                    .put("id", attachment.id)
                    .put("name", attachment.name)
                    .put("mimeType", attachment.mimeType)
                    .put("sizeBytes", attachment.sizeBytes)
                    .put("storageUri", attachment.storageUri)
                    .put("provenance", attachment.provenance)
                    .apply { attachment.artifactId?.let { put("artifactId", it) } }
            )
        }
        return array.toString()
    }

    fun decode(json: String?): List<TurnAttachment> {
        if (json.isNullOrBlank()) return emptyList()
        return try {
            val array = JSONArray(json)
            val result = ArrayList<TurnAttachment>(array.length())
            for (index in 0 until array.length()) {
                val obj = array.optJSONObject(index) ?: continue
                val id = obj.optString("id", "")
                val name = obj.optString("name", "")
                val storageUri = obj.optString("storageUri", "")
                if (id.isBlank() || storageUri.isBlank()) continue
                result += TurnAttachment(
                    id = id,
                    name = name.ifBlank { storageUri.substringAfterLast('/') },
                    mimeType = obj.optString("mimeType", "application/octet-stream"),
                    sizeBytes = obj.optLong("sizeBytes", 0L),
                    storageUri = storageUri,
                    artifactId = obj.optString("artifactId").takeIf { it.isNotBlank() },
                    provenance = obj.optString("provenance", "SAF_FILE").ifBlank { "SAF_FILE" }
                )
            }
            result
        } catch (_: Exception) {
            // Malformed legacy payload — degrade to "no attachments" honestly.
            emptyList()
        }
    }
}
