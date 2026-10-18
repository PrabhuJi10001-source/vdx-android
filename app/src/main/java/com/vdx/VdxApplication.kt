package com.vdx

import android.app.Application
import com.vdx.telemetry.Telemetry
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * VdxApplication — adds a self-contained CRASH CATCHER (2026-10-18 stability round).
 *
 * The maker reported: "crashed when I turned on 2nd accessibility" but had no logcat.
 * From now on the app records its own crashes to a local file so any device can
 * produce a crash report WITHOUT adb — the report is shown on the next launch
 * (MainActivity reads it into the labs log) and can be shared as plain text.
 *
 * Nothing is uploaded: the crash file stays on-device (privacy law).
 */
class VdxApplication : Application() {

    override fun onCreate() {
        super.onCreate()
        installCrashCatcher()
        // v0.1.8 SYSTEM-VOICE LAW (maker-ordered): the voice engine ALWAYS starts on
        // the phone's own default system voice. Any installed language pack stays
        // dormant unless the user deliberately re-activates it in the Languages tab.
        com.vdx.sonic.voice.ModelStoreManager.setSelected(this, "")
        // Essential-events telemetry (opt-in, DPDP-clean). Initialises the event
        // queue, the pending batch file, and the upload worker. No network is
        // touched until the user opts in — consent is checked before any upload.
        Telemetry.init(this)
        // Device profile is local. Failure to read a fact leaves it null.
        // This does not call Elastic Web or the App Store.
        try {
            val probe = com.vdx.capability.AndroidDeviceProbe(this).snapshot()
            com.vdx.capability.InstallationPlane.remember(probe, System.currentTimeMillis())
        } catch (e: Exception) {
            android.util.Log.w("VdxApplication", "device discovery failed", e)
        }
    }

    private fun installCrashCatcher() {
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            try {
                writeCrash(throwable, thread?.name ?: "?")
            } catch (_: Exception) {
                // last-resort silence: never let the catcher itself crash
            }
            // still hand over to the system handler (so the process lifecycle stays honest)
            previous?.uncaughtException(thread, throwable) ?: kotlin.system.exitProcess(2)
        }
    }

    private fun writeCrash(throwable: Throwable, threadName: String) {
        val dir = File(filesDir, "diagnostics")
        if (!dir.exists()) dir.mkdirs()
        val f = File(dir, "last_crash.log")
        val ts = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date())
        val sw = StringWriter()
        throwable.printStackTrace(PrintWriter(sw))
        // keep the file bounded (drop old content beyond ~48 KB)
        val current = if (f.exists()) f.readText() else ""
        val trimmed = if (current.length > 48_000) "" else current
        val entry = buildString {
            append(trimmed)
            append("=== CRASH $ts (thread: $threadName) ===\n")
            append(sw.toString())
            append("\n\n")
        }
        f.writeText(entry)
        android.util.Log.e("VdxCrashCatcher", "crash recorded to ${f.absolutePath}", throwable)
    }
}
