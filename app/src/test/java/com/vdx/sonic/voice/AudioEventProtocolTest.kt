package com.vdx.sonic.voice

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Unit tests for AudioEventProtocol — the bidirectional
 * WebSocket audio event format.
 *
 * Verifies JSON (de)serialization round-trips for all four event types and
 * the base64 audio encode/decode helpers.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class AudioEventProtocolTest {

    @Test
    fun startEvent_roundTrips() {
        val original = AudioEvent.Start(
            encoding = "pcm16",
            sampleRate = 8000,
            channels = 1,
            params = mapOf("language" to "en-US", "vad" to "true")
        )
        val json = AudioEventSerializer.toJson(original)
        val back = AudioEventSerializer.fromJson(json) as AudioEvent.Start
        assertEquals("pcm16", back.encoding)
        assertEquals(8000, back.sampleRate)
        assertEquals(1, back.channels)
        assertEquals("en-US", back.params["language"])
        assertEquals("true", back.params["vad"])
    }

    @Test
    fun startEvent_usesDefaultsWhenOmitted() {
        val json = """{"event":"start","payload":{}}"""
        val back = AudioEventSerializer.fromJson(json) as AudioEvent.Start
        assertEquals("pcm16", back.encoding)
        assertEquals(16000, back.sampleRate)
        assertEquals(1, back.channels)
        assertTrue(back.params.isEmpty())
    }

    @Test
    fun mediaEvent_roundTrips() {
        val original = AudioEvent.Media(sequence = 42, base64Audio = "AAAA")
        val json = AudioEventSerializer.toJson(original)
        val back = AudioEventSerializer.fromJson(json) as AudioEvent.Media
        assertEquals(42, back.sequence)
        assertEquals("AAAA", back.base64Audio)
    }

    @Test
    fun mediaEvent_jsonHasCorrectShape() {
        val original = AudioEvent.Media(sequence = 0, base64Audio = "AAAA")
        val json = AudioEventSerializer.toJson(original)
        val root = JSONObject(json)
        assertEquals("media", root.getString("event"))
        assertEquals(0, root.getJSONObject("payload").getLong("seq"))
        assertEquals("AAAA", root.getJSONObject("payload").getString("audio"))
    }

    @Test
    fun dtmfEvent_roundTrips() {
        val original = AudioEvent.Dtmf(key = "5")
        val json = AudioEventSerializer.toJson(original)
        val back = AudioEventSerializer.fromJson(json) as AudioEvent.Dtmf
        assertEquals("5", back.key)
    }

    @Test
    fun dtmfEvent_supportsAllKeypadKeys() {
        for (key in listOf("0", "1", "9", "*", "#", "A", "D")) {
            val json = AudioEventSerializer.toJson(AudioEvent.Dtmf(key))
            val back = AudioEventSerializer.fromJson(json) as AudioEvent.Dtmf
            assertEquals(key, back.key)
        }
    }

    @Test
    fun stopEvent_roundTripsWithReason() {
        val original = AudioEvent.Stop(reason = "normal")
        val json = AudioEventSerializer.toJson(original)
        val back = AudioEventSerializer.fromJson(json) as AudioEvent.Stop
        assertEquals("normal", back.reason)
    }

    @Test
    fun stopEvent_roundTripsWithoutReason() {
        val original = AudioEvent.Stop(reason = null)
        val json = AudioEventSerializer.toJson(original)
        val back = AudioEventSerializer.fromJson(json) as AudioEvent.Stop
        assertNull(back.reason)
    }

    @Test
    fun stopEvent_fromJsonWithoutReasonField_isNull() {
        val json = """{"event":"stop","payload":{}}"""
        val back = AudioEventSerializer.fromJson(json) as AudioEvent.Stop
        assertNull(back.reason)
    }

    @Test
    fun encodeDecodeAudio_roundTrips() {
        val pcm = ByteArray(320) { it.toByte() }  // 320 bytes = 160 samples @ 16-bit
        val base64 = AudioEventSerializer.encodeAudio(pcm)
        val back = AudioEventSerializer.decodeAudio(base64)
        assertArrayEquals(pcm, back)
    }

    @Test
    fun encodeAudio_producesValidMediaEvent() {
        val pcm = ByteArray(320) { 0x7F }
        val base64 = AudioEventSerializer.encodeAudio(pcm)
        val event = AudioEvent.Media(sequence = 1, base64Audio = base64)
        val json = AudioEventSerializer.toJson(event)
        val back = AudioEventSerializer.fromJson(json) as AudioEvent.Media
        val decoded = AudioEventSerializer.decodeAudio(back.base64Audio)
        assertArrayEquals(pcm, decoded)
        assertEquals(1, back.sequence)
    }

    @Test(expected = IllegalArgumentException::class)
    fun fromJson_unknownType_throws() {
        AudioEventSerializer.fromJson("""{"event":"bogus","payload":{}}""")
    }

    @Test
    fun startEvent_withCustomParams_preservesAll() {
        val params = mapOf(
            "language" to "hi-IN",
            "vad" to "true",
            "model" to "whisper-large"
        )
        val original = AudioEvent.Start(params = params)
        val json = AudioEventSerializer.toJson(original)
        val back = AudioEventSerializer.fromJson(json) as AudioEvent.Start
        assertEquals(params, back.params)
    }
}