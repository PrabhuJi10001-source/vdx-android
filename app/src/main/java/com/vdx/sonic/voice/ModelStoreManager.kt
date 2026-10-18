package com.vdx.sonic.voice

import android.content.Context
import java.io.File

/**
 * ModelStoreManager — the LANGUAGE PACK system (v0.1.5, user-ordered).
 *
 * "I want to make APK in such a way that if a person decides to install a certain
 * language, then you ask this question. Do you want to install this language"
 * — a Languages tab lists every installable voice language; tapping one shows an
 * install dialog (size + one-time + works offline note); on confirm the model
 * downloads ONCE into the app's private storage.
 *
 * Languages are backed by the Handy engine's model set (whisper.cpp, MIT):
 * the multilingual model covers English + Hindi (+90 more) in ONE file with
 * automatic language detection, so Hindi and Hinglish share a single pack.
 */
object ModelStoreManager {

    data class LanguagePack(
        val id: String,            // stable key in preferences
        val displayName: String,
        val covers: String,        // plain-language description
        val fileName: String,      // file in filesDir/models/
        val downloadUrl: String,
        val sizeBytesEstimate: Long
    )

    /** The available packs. The multilingual Whisper model is the Handy engine's
     *  recommended default: English + Hindi + Hinglish code-switching in one model. */
    val PACKS = listOf(
        LanguagePack(
            id = "whisper_multilingual",
            displayName = "Handy Voice Engine (Hindi + Hinglish + English)",
            covers = "Understands spoken Hindi, Hinglish and English in one pack. Auto-detects the language — install once, use offline forever.",
            fileName = "ggml-small.bin",
            downloadUrl = "https://huggingface.co/ggerganov/whisper.cpp/resolve/main/ggml-small.bin",
            sizeBytesEstimate = 466_000_000L
        )
    )

    fun modelsDir(context: Context): File =
        File(context.filesDir, "models").apply { if (!exists()) mkdirs() }

    fun installedFile(context: Context, pack: LanguagePack): File? {
        val f = File(modelsDir(context), pack.fileName)
        return if (f.isFile && f.length() > 0) f else null
    }

    fun isInstalled(context: Context, pack: LanguagePack): Boolean =
        installedFile(context, pack) != null

    /** Selected pack id for the voice engine (empty = system recognizer only). */
    private const val PREFS = "voice_engine_prefs"
    private const val KEY_SELECTED = "selected_pack"

    fun selectedPackId(context: Context): String =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(KEY_SELECTED, "") ?: ""

    fun setSelected(context: Context, packId: String) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putString(KEY_SELECTED, packId).apply()
    }

    fun selectedPack(context: Context): LanguagePack? =
        PACKS.firstOrNull { it.id == selectedPackId(context) }

    fun delete(context: Context, pack: LanguagePack): Boolean {
        val f = File(modelsDir(context), pack.fileName)
        if (f.isFile && f.delete()) {
            if (selectedPackId(context) == pack.id) setSelected(context, "")
            return true
        }
        return false
    }

    /**
     * Stream the model to disk with progress callbacks (onProgress 0..100).
     * Plain HttpURLConnection — no new dependency; resumable from scratch on
     * failure (delete partial, retry clean).
     */
    fun download(context: Context, pack: LanguagePack,
                 onProgress: (Int) -> Unit): Boolean {
        val tmpFile = File(modelsDir(context), pack.fileName + ".part")
        val dst = File(modelsDir(context), pack.fileName)
        return try {
            val conn = java.net.URL(pack.downloadUrl).openConnection() as java.net.HttpURLConnection
            conn.connectTimeout = 30_000
            conn.readTimeout = 60_000
            conn.instanceFollowRedirects = true
            if (conn.responseCode !in 200..299) return false
            val total = conn.contentLengthLong.takeIf { it > 0 } ?: pack.sizeBytesEstimate
            conn.inputStream.use { ins ->
                tmpFile.outputStream().use { outs ->
                    val buf = ByteArray(1 shl 16)
                    var read = 0L; var lastPct = -1; var n: Int
                    while (ins.read(buf).also { n = it } != -1) {
                        outs.write(buf, 0, n)
                        read += n
                        val pct = ((read * 100) / total).toInt()
                        if (pct != lastPct && pct % 2 == 0) { onProgress(pct.coerceIn(0, 100)); lastPct = pct }
                    }
                    outs.flush()
                }
            }
            if (tmpFile.isFile && tmpFile.length() > 0) {
                if (dst.exists()) dst.delete()
                tmpFile.renameTo(dst) && dst.isFile && dst.length() > 0
            } else {
                tmpFile.delete(); false
            }
        } catch (_: Exception) {
            tmpFile.delete()
            false
        }
    }
}
