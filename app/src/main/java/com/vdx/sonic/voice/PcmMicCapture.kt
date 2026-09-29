package com.vdx.sonic.voice

import android.annotation.SuppressLint
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.util.Log
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Tap-to-talk 16 kHz mono PCM capture. Feeds [com.vdx.sonic.SonicEngine.process]
 * so Groq/Sarvam ASR (already built) actually run. Not a server. Not VAD-auto-commit —
 * caller starts on orb tap and stops on the second tap.
 */
class PcmMicCapture {
    companion object {
        private const val TAG = "PcmMicCapture"
        const val SAMPLE_RATE = 16_000
        private const val MAX_SECONDS = 30
        private const val CHANNEL = AudioFormat.CHANNEL_IN_MONO
        private const val ENCODING = AudioFormat.ENCODING_PCM_16BIT
    }

    private val running = AtomicBoolean(false)
    private var recorder: AudioRecord? = null
    private val chunks = ArrayList<ShortArray>()
    private var worker: Thread? = null

    val isRunning: Boolean get() = running.get()

    @SuppressLint("MissingPermission")
    fun start(): Boolean {
        if (!running.compareAndSet(false, true)) return false
        val min = AudioRecord.getMinBufferSize(SAMPLE_RATE, CHANNEL, ENCODING)
        if (min <= 0) {
            running.set(false)
            return false
        }
        val rec = try {
            AudioRecord(
                MediaRecorder.AudioSource.VOICE_RECOGNITION,
                SAMPLE_RATE,
                CHANNEL,
                ENCODING,
                min * 2
            )
        } catch (e: Exception) {
            Log.e(TAG, "AudioRecord create failed", e)
            running.set(false)
            return false
        }
        if (rec.state != AudioRecord.STATE_INITIALIZED) {
            rec.release()
            running.set(false)
            return false
        }
        synchronized(chunks) { chunks.clear() }
        recorder = rec
        try {
            rec.startRecording()
        } catch (e: Exception) {
            Log.e(TAG, "startRecording failed", e)
            rec.release()
            recorder = null
            running.set(false)
            return false
        }
        val maxSamples = SAMPLE_RATE * MAX_SECONDS
        worker = Thread {
            val buf = ShortArray(min)
            var total = 0
            while (running.get() && total < maxSamples) {
                val n = try {
                    rec.read(buf, 0, buf.size)
                } catch (_: Exception) {
                    break
                }
                if (n > 0) {
                    synchronized(chunks) { chunks.add(buf.copyOf(n)) }
                    total += n
                }
            }
        }.apply {
            name = "vdx-pcm-mic"
            isDaemon = true
            start()
        }
        return true
    }

    fun stop(): ShortArray {
        running.set(false)
        try {
            worker?.join(500)
        } catch (_: Exception) { }
        worker = null
        try {
            recorder?.stop()
        } catch (_: Exception) { }
        try {
            recorder?.release()
        } catch (_: Exception) { }
        recorder = null
        val collected = synchronized(chunks) {
            val all = chunks.toList()
            chunks.clear()
            all
        }
        val total = collected.sumOf { it.size }
        if (total == 0) return ShortArray(0)
        val out = ShortArray(total)
        var i = 0
        for (c in collected) {
            c.copyInto(out, i)
            i += c.size
        }
        return out
    }
}
