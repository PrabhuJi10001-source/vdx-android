package com.vdx.sonic.voice

import org.json.JSONObject
import java.util.Base64

/**
 * AudioEventProtocol — bidirectional WebSocket audio event format.
 *
 * Voice-first streaming protocol. This is a protocol definition +
 * (de)serialization layer, NOT a network server. It defines the message
 * envelope so VDX Sonic's audio pipeline can eventually stream to/from any
 * WebSocket endpoint (a telephony bridge, a cloud ASR
 * streaming endpoint).
 *
 * Event types:
 *   - start : audio format metadata (encoding, sample_rate, channels) + custom params
 *   - media : sequential base64-encoded audio chunks (320 bytes, 16-bit PCM)
 *   - dtmf  : keypad input
 *   - stop  : session end
 *
 * Wire format is JSON: `{"event":"start","payload":{...}}`.
 *
 * No reflection — [AudioEventSerializer] dispatches on the sealed-class
 * type explicitly. No new dependencies — `org.json` (already used by every
 * other voice engine in this package) + `java.util.Base64` (API 26+).
 */
sealed class AudioEvent {

    /** Session handshake: declares the audio format the media stream will carry. */
    data class Start(
        val encoding: String = "pcm16",
        val sampleRate: Int = 16000,
        val channels: Int = 1,
        /** Arbitrary per-session parameters (language hint, VAD enable, etc.). */
        val params: Map<String, String> = emptyMap()
    ) : AudioEvent()

    /**
     * A sequential audio chunk. [sequence] is a 0-based monotonic counter so the
     * receiver can detect drops / reorder. [base64Audio] is the raw PCM bytes
     * (320 bytes = 160 samples @ 16-bit) encoded as base64.
     */
    data class Media(
        val sequence: Long,
        val base64Audio: String
    ) : AudioEvent()

    /** A DTMF keypad event. [key] is one of 0-9, *, #, A-D. */
    data class Dtmf(
        val key: String
    ) : AudioEvent()

    /** Session teardown. [reason] is optional (normal, error, timeout, …). */
    data class Stop(
        val reason: String? = null
    ) : AudioEvent()
}

/**
 * (De)serialization for [AudioEvent]. JSON wire format:
 * ```
 * {"event":"start","payload":{"encoding":"pcm16","sample_rate":16000,"channels":1,"params":{...}}}
 * {"event":"media","payload":{"seq":0,"audio":"<base64>"}}
 * {"event":"dtmf","payload":{"key":"5"}}
 * {"event":"stop","payload":{"reason":"normal"}}
 * ```
 */
object AudioEventSerializer {

    private const val KEY_EVENT = "event"
    private const val KEY_PAYLOAD = "payload"

    fun toJson(event: AudioEvent): String {
        val (type, payload) = when (event) {
            is AudioEvent.Start -> "start" to JSONObject().apply {
                put("encoding", event.encoding)
                put("sample_rate", event.sampleRate)
                put("channels", event.channels)
                val p = JSONObject()
                event.params.forEach { (k, v) -> p.put(k, v) }
                put("params", p)
            }
            is AudioEvent.Media -> "media" to JSONObject().apply {
                put("seq", event.sequence)
                put("audio", event.base64Audio)
            }
            is AudioEvent.Dtmf -> "dtmf" to JSONObject().apply {
                put("key", event.key)
            }
            is AudioEvent.Stop -> "stop" to JSONObject().apply {
                event.reason?.let { put("reason", it) }
            }
        }
        return JSONObject().put(KEY_EVENT, type).put(KEY_PAYLOAD, payload).toString()
    }

    fun fromJson(json: String): AudioEvent {
        val root = JSONObject(json)
        val type = root.getString(KEY_EVENT)
        val payload = root.optJSONObject(KEY_PAYLOAD) ?: JSONObject()
        return when (type) {
            "start" -> AudioEvent.Start(
                encoding = payload.optString("encoding", "pcm16"),
                sampleRate = payload.optInt("sample_rate", 16000),
                channels = payload.optInt("channels", 1),
                params = payload.optJSONObject("params")?.let { p ->
                    buildMap {
                        for (k in p.keys()) put(k, p.optString(k))
                    }
                } ?: emptyMap()
            )
            "media" -> AudioEvent.Media(
                sequence = payload.getLong("seq"),
                base64Audio = payload.getString("audio")
            )
            "dtmf" -> AudioEvent.Dtmf(
                key = payload.getString("key")
            )
            "stop" -> AudioEvent.Stop(
                reason = if (payload.has("reason")) payload.getString("reason") else null
            )
            else -> throw IllegalArgumentException("Unknown audio event type: $type")
        }
    }

    // ──────────────────────────────────────────────────────────────
    // Convenience: raw-bytes ↔ base64 for the media path
    // ──────────────────────────────────────────────────────────────

    /** Encode a raw PCM chunk to a base64 string for a [AudioEvent.Media] event. */
    fun encodeAudio(pcm: ByteArray): String =
        Base64.getEncoder().encodeToString(pcm)

    /** Decode the base64 audio payload of a [AudioEvent.Media] event back to bytes. */
    fun decodeAudio(base64: String): ByteArray =
        Base64.getDecoder().decode(base64)
}