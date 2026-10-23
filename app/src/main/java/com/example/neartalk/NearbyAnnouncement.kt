package com.example.neartalk

import android.util.Base64

internal object NearbyAnnouncement {
    const val SERVICE_TYPE = "_neartalk._tcp"
    const val MAX_BYTES = 180
    private val idPattern = Regex("[a-f0-9]{32}")
    private const val base64Flags = Base64.NO_WRAP or Base64.NO_PADDING

    fun encode(id: String, text: String, request: Boolean = false): Map<String, String> {
        val bytes = text.toByteArray(Charsets.UTF_8)
        require(idPattern.matches(id))
        require(text.isNotBlank() && bytes.size <= MAX_BYTES)
        // Android's Wi-Fi Direct TXT parser strips trailing '=' padding.
        return mapOf("v" to "1", "id" to id, "m" to Base64.encodeToString(bytes, base64Flags),
            "k" to if (request) "request1" else "message")
    }

    fun decode(domain: String, record: Map<String, String>, sender: Peer): BroadcastItem? {
        if (record["v"] != "1") return null
        val id = record["id"] ?: return null
        if (!idPattern.matches(id)) return null
        if (!domain.equals("$id.$SERVICE_TYPE.local.", ignoreCase = true)) return null
        val encoded = record["m"] ?: return null
        if (encoded.length > 240) return null
        val bytes = try {
            Base64.decode(encoded, Base64.DEFAULT)
        } catch (_: IllegalArgumentException) {
            return null
        }
        if (bytes.size > MAX_BYTES) return null
        if (Base64.encodeToString(bytes, base64Flags) != encoded.trimEnd('=')) return null
        val text = bytes.toString(Charsets.UTF_8)
        if (text.isBlank() || !text.toByteArray(Charsets.UTF_8).contentEquals(bytes)) return null
        val kind = record["k"]
        if (kind != null && kind != "message" && kind != "request1") return null
        val request = kind == "request1"
        return BroadcastItem(id = id, sender = sender, text = text,
            kind = if (request) BroadcastKind.UnicastRequest else BroadcastKind.Message,
            requestId = if (request) id else null)
    }
}
