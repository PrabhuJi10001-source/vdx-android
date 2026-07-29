package com.vdx.sonic.diag

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import androidx.core.content.ContextCompat
import com.vdx.sonic.HealthCheck
import com.vdx.sonic.HealthFixAction
import com.vdx.sonic.HealthReport

/**
 * DiagnosticsEngine — permission and health checks for VDX Sonic.
 *
 * Exposes visible health for all critical system dependencies.
 * The user should be able to understand quickly why something is not working.
 */
class DiagnosticsEngine(private val context: Context) {

    companion object {
        private const val TAG = "Sonic-Diag"
        private const val PREFS_NAME = "vdx_prefs"
    }

    /**
     * Run all health checks and return a report.
     */
    fun checkAll(): HealthReport {
        return HealthReport(
            microphone = checkMicrophone(),
            overlay = checkOverlay(),
            accessibility = checkAccessibility(),
            batteryOptimization = checkBatteryOptimization(),
            serviceRunning = checkServiceRunning(),
            harnessReady = checkHarnessReady(),
            networkAvailable = checkNetwork()
        )
    }

    /**
     * Quick check — is the system ready to use?
     */
    fun isReady(): Boolean {
        val report = checkAll()
        return report.microphone.ok &&
               report.overlay.ok &&
               report.accessibility.ok &&
               report.serviceRunning.ok
    }

    /**
     * Get a human-readable summary of what's wrong.
     */
    fun getBlockers(): List<String> {
        val report = checkAll()
        val blockers = mutableListOf<String>()

        if (!report.microphone.ok) blockers.add("Microphone permission required")
        if (!report.overlay.ok) blockers.add("Overlay permission required")
        if (!report.accessibility.ok) blockers.add("Accessibility service not enabled")
        if (!report.batteryOptimization.ok) blockers.add("Battery optimization may affect performance")
        if (!report.serviceRunning.ok) blockers.add("Bubble service not running")
        if (!report.harnessReady.ok) blockers.add("Cannot read screen — accessibility may be restricted")
        if (!report.networkAvailable.ok) blockers.add("No internet — cloud ASR unavailable")

        return blockers
    }

    // ──────────────────────────────────────────────────────────────
    // Individual checks
    // ──────────────────────────────────────────────────────────────

    private fun checkMicrophone(): HealthCheck {
        val granted = ContextCompat.checkSelfPermission(
            context, android.Manifest.permission.RECORD_AUDIO
        ) == PackageManager.PERMISSION_GRANTED

        return HealthCheck(
            ok = granted,
            message = if (granted) "Microphone granted" else "Microphone permission required",
            fixAction = if (!granted) HealthFixAction.OPEN_MIC_SETTINGS else null
        )
    }

    private fun checkOverlay(): HealthCheck {
        val granted = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            Settings.canDrawOverlays(context)
        } else true

        return HealthCheck(
            ok = granted,
            message = if (granted) "Overlay granted" else "Overlay permission required",
            fixAction = if (!granted) HealthFixAction.OPEN_OVERLAY_SETTINGS else null
        )
    }

    private fun checkAccessibility(): HealthCheck {
        val enabled = try {
            val enabledServices = Settings.Secure.getString(
                context.contentResolver,
                Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
            ) ?: ""
            enabledServices.contains(context.packageName)
        } catch (e: Exception) { false }

        return HealthCheck(
            ok = enabled,
            message = if (enabled) "Accessibility service enabled" else "Accessibility service not enabled",
            fixAction = if (!enabled) HealthFixAction.OPEN_ACCESSIBILITY_SETTINGS else null
        )
    }

    private fun checkBatteryOptimization(): HealthCheck {
        val isExempted = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            val pm = context.getSystemService(Context.POWER_SERVICE) as PowerManager
            pm.isIgnoringBatteryOptimizations(context.packageName)
        } else true

        return HealthCheck(
            ok = isExempted,
            message = if (isExempted) "Battery optimization disabled for VDX" else "Battery optimization may affect performance",
            fixAction = if (!isExempted) HealthFixAction.OPEN_BATTERY_SETTINGS else null
        )
    }

    private fun checkServiceRunning(): HealthCheck {
        // Check via the static flag set by BubbleForegroundService
        val running = try {
            val cls = Class.forName("com.vdx.BubbleForegroundService")
            val field = cls.getDeclaredField("isRunning")
            field.isAccessible = true
            field.getBoolean(null)
        } catch (e: Exception) { false }

        return HealthCheck(
            ok = running,
            message = if (running) "Bubble service running" else "Bubble service not running",
            fixAction = if (!running) HealthFixAction.START_SERVICE else null
        )
    }

    private fun checkHarnessReady(): HealthCheck {
        // Check if accessibility service instance is available
        val ready = try {
            val cls = Class.forName("com.vdx.VdxAccessibilityService")
            val field = cls.getDeclaredField("instance")
            field.isAccessible = true
            field.get(null) != null
        } catch (e: Exception) { false }

        return HealthCheck(
            ok = ready,
            message = if (ready) "Harness ready" else "Cannot read screen — accessibility may be restricted",
            fixAction = if (!ready) HealthFixAction.ENABLE_HARNESS else null
        )
    }

    private fun checkNetwork(): HealthCheck {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
        val connected = cm?.let {
            val network = it.activeNetwork ?: return@let false
            val caps = it.getNetworkCapabilities(network) ?: return@let false
            caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
        } ?: false

        return HealthCheck(
            ok = connected,
            message = if (connected) "Network available" else "No internet connection",
            fixAction = if (!connected) HealthFixAction.CONFIGURE_NETWORK else null
        )
    }
}
