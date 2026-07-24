package com.vdx

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.View
import android.view.inputmethod.EditorInfo
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.vdx.logging.SessionLogger
import org.json.JSONObject

class MainActivity : AppCompatActivity() {

    private lateinit var statusText: TextView
    private lateinit var actionButton: Button
    private lateinit var accessibilityButton: Button
    private lateinit var permissionDetail: TextView
    private lateinit var textInput: EditText

    // Harness views
    private lateinit var harnessLog: TextView
    private lateinit var btnTestYoutube: Button
    private lateinit var btnTestDialer: Button
    private lateinit var btnTestSettings: Button
    private lateinit var btnTestReadscreen: Button
    private lateinit var btnTestReadsms: Button
    private lateinit var btnTestCall: Button
    private lateinit var btnTestWhatsApp: Button
    private lateinit var btnTestUber: Button
    private lateinit var btnTestSms: Button
    private lateinit var btnTestEmail: Button
    private lateinit var btnTestAll: Button
    private lateinit var btnClearLog: Button

    // Structured logger for harness sessions
    private var harnessLogger: SessionLogger? = null

    private val audioPermission = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted -> updateStatus() }

    private val notificationPermission = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted -> updateStatus() }

    private val overlayPermission = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { updateStatus() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        statusText = findViewById(R.id.status_text)
        actionButton = findViewById(R.id.btn_action)
        accessibilityButton = findViewById(R.id.btn_accessibility)
        permissionDetail = findViewById(R.id.permission_detail)
        textInput = findViewById(R.id.text_input)

        // Harness views
        harnessLog = findViewById(R.id.harness_log)
        btnTestYoutube = findViewById(R.id.btn_test_youtube)
        btnTestDialer = findViewById(R.id.btn_test_dialer)
        btnTestSettings = findViewById(R.id.btn_test_settings)
        btnTestReadscreen = findViewById(R.id.btn_test_readscreen)
        btnTestReadsms = findViewById(R.id.btn_test_readsms)
        btnTestCall = findViewById(R.id.btn_test_call)
        btnTestWhatsApp = findViewById(R.id.btn_test_whatsapp)
        btnTestUber = findViewById(R.id.btn_test_uber)
        btnTestSms = findViewById(R.id.btn_test_sms)
        btnTestEmail = findViewById(R.id.btn_test_email)
        btnTestAll = findViewById(R.id.btn_test_all)
        btnClearLog = findViewById(R.id.btn_clear_log)

        // Text input — type an intent and press send
        textInput.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_SEND) {
                val text = textInput.text.toString().trim()
                if (text.isNotBlank()) {
                    processTextIntent(text)
                    textInput.text.clear()
                }
                true
            } else false
        }

        updateStatus()

        // Single action button — context changes based on state
        actionButton.setOnClickListener {
            when {
                !hasAudioPermission() -> {
                    audioPermission.launch(Manifest.permission.RECORD_AUDIO)
                }
                !hasOverlayPermission() -> {
                    val intent = Intent(
                        Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                        Uri.parse("package:$packageName")
                    )
                    overlayPermission.launch(intent)
                }
                !BubbleForegroundService.isRunning -> {
                    startBubbleService()
                }
                else -> {
                    stopBubbleService()
                }
            }
            updateStatus()
        }

        accessibilityButton.setOnClickListener {
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        }

        accessibilityButton.setOnLongClickListener {
            startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                Uri.parse("package:$packageName")))
            true
        }

        // ════════════════════════════════════════════════════════════════
        // DEVELOPER HARNESS — RobotHand Direct Execution
        // ════════════════════════════════════════════════════════════════
        // Each button invokes RobotHand.execute() with a hardcoded intent,
        // bypassing Bubble, STT, and LLM entirely.
        // Results are logged to harnessLog, logcat, and structured JSON log.

        btnTestYoutube.setOnClickListener { runHarnessTest("YouTube", VdxIntent.YouTube("cat videos")) }
        btnTestDialer.setOnClickListener { runHarnessTest("Dialer", VdxIntent.AppLaunch("Phone")) }
        btnTestSettings.setOnClickListener { runHarnessTest("Settings", VdxIntent.AppLaunch("Settings")) }
        btnTestReadscreen.setOnClickListener { runHarnessTest("Read Screen", VdxIntent.ReadScreen) }
        btnTestReadsms.setOnClickListener { runHarnessTest("Read SMS", VdxIntent.ReadSms) }
        btnTestCall.setOnClickListener { runHarnessTest("Call", VdxIntent.Call("555-1234")) }
        btnTestWhatsApp.setOnClickListener { runHarnessTest("WhatsApp", VdxIntent.WhatsApp("Mom", "Hello from VDX test")) }
        btnTestUber.setOnClickListener { runHarnessTest("Uber", VdxIntent.Uber("airport")) }
        btnTestSms.setOnClickListener { runHarnessTest("SMS", VdxIntent.Sms("John", "Test message")) }
        btnTestEmail.setOnClickListener { runHarnessTest("Email", VdxIntent.Email("test@example.com", "Test email body")) }

        // Run all tests sequentially
        btnTestAll.setOnClickListener {
            appendHarnessLog("══════ RUNNING ALL TESTS ══════")
            val testCases = listOf(
                "Call" to VdxIntent.Call("555-1234"),
                "SMS" to VdxIntent.Sms("John", "Test message"),
                "YouTube" to VdxIntent.YouTube("cat videos"),
                "Dialer" to VdxIntent.AppLaunch("Phone"),
                "Settings" to VdxIntent.AppLaunch("Settings"),
                "Read Screen" to VdxIntent.ReadScreen,
                "Read SMS" to VdxIntent.ReadSms,
                "WhatsApp" to VdxIntent.WhatsApp("Mom", "Hello from VDX test"),
                "Uber" to VdxIntent.Uber("airport"),
                "Email" to VdxIntent.Email("test@example.com", "Test email body")
            )
            runAllTests(testCases)
        }

        btnClearLog.setOnClickListener {
            harnessLog.text = ""
        }
    }

    private fun startBubbleService() {
        val intent = Intent(this, BubbleForegroundService::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(intent)
        } else {
            startService(intent)
        }
    }

    private fun stopBubbleService() {
        stopService(Intent(this, BubbleForegroundService::class.java))
    }

    override fun onResume() {
        super.onResume()
        updateStatus()
    }

    private fun updateStatus() {
        val audio = hasAudioPermission()
        val overlay = hasOverlayPermission()
        val bubbleRunning = BubbleForegroundService.isRunning
        val accessibilityOn = isAccessibilityEnabled()

        // Status panel
        statusText.text = buildString {
            appendLine("VDX V1 — 0.1.0-alpha")
            appendLine()
            appendLine("🔵 Mic:     ${if (audio) "✅ Granted" else "❌ Required"}")
            appendLine("🔵 Overlay:  ${if (overlay) "✅ Granted" else "❌ Required"}")
            appendLine("🔵 Bubble:  ${if (bubbleRunning) "✅ Running" else "⚪ Not started"}")
            appendLine("🔵 Access:  ${if (accessibilityOn) "✅ Enabled" else "❌ Disabled"}")
            appendLine()
            if (!audio || !overlay) {
                appendLine("Tap below to grant permissions.")
            } else if (!bubbleRunning) {
                appendLine("Tap below to start VDX bubble.")
            } else {
                appendLine("VDX is live. Tap the floating bubble to speak.")
            }
        }

        // Action button text
        actionButton.text = when {
            !audio -> "🎤 Grant Microphone"
            !overlay -> "📱 Grant Overlay"
            !bubbleRunning -> "🚀 Start VDX Bubble"
            else -> "⏹ Stop Bubble"
        }

        // Show accessibility button only if bubble is running but accessibility is off
        accessibilityButton.visibility = if (bubbleRunning && !accessibilityOn) View.VISIBLE else View.GONE

        // Permission detail
        permissionDetail.text = if (!audio || !overlay) {
            "VDX needs microphone + overlay permissions to work. " +
            "Mic: ${if (audio) "✅" else "❌"}  Overlay: ${if (overlay) "✅" else "❌"}"
        } else {
            ""
        }

        // Auto-request notification permission on Android 13+
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
            != PackageManager.PERMISSION_GRANTED) {
            notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    private fun hasAudioPermission(): Boolean =
        ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) ==
        PackageManager.PERMISSION_GRANTED

    private fun hasOverlayPermission(): Boolean =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) Settings.canDrawOverlays(this)
        else true

    private fun isAccessibilityEnabled(): Boolean {
        val enabled = Settings.Secure.getString(
            contentResolver,
            Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
        ) ?: return false
        return enabled.contains(packageName)
    }

    private fun processTextIntent(text: String) {
        val result = IntentEngine.parse(text)
        val msg = when (result) {
            is VdxIntent.WhatsApp -> {
                try {
                    val waIntent = Intent(Intent.ACTION_VIEW, Uri.parse("https://wa.me/${result.contact}"))
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    startActivity(waIntent)
                    "Opening WhatsApp for ${result.contact}"
                } catch (e: Exception) {
                    "WhatsApp not available"
                }
            }
            is VdxIntent.Call -> {
                val callIntent = Intent(Intent.ACTION_DIAL, Uri.parse("tel:${result.contact}"))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                startActivity(callIntent)
                "Calling ${result.contact}"
            }
            is VdxIntent.ReadSms -> {
                val smsIntent = Intent(Intent.ACTION_MAIN).apply {
                    addCategory(Intent.CATEGORY_APP_MESSAGING)
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                startActivity(smsIntent)
                "Opening messages"
            }
            is VdxIntent.Uber -> {
                try {
                    val uberIntent = Intent(Intent.ACTION_VIEW,
                        Uri.parse("https://m.uber.com/ul?action=setPickup&pickup=my_location&drop[formatted_address]=${result.destination}"))
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    startActivity(uberIntent)
                    "Opening Uber for ${result.destination}"
                } catch (e: Exception) {
                    val mapsIntent = Intent(Intent.ACTION_VIEW, Uri.parse("https://maps.google.com/?q=${result.destination}"))
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    startActivity(mapsIntent)
                    "Uber not available, opened maps"
                }
            }
            is VdxIntent.Memory -> "Session memory: ${SessionMemory().getAll().size} items"
            is VdxIntent.YouTube -> "Searching YouTube for ${result.searchQuery}"
            is VdxIntent.Sms -> "Opening messages for ${result.contact}"
            is VdxIntent.Email -> "Opening email for ${result.contact}"
            is VdxIntent.AppLaunch -> "Opening ${result.appName}"
            is VdxIntent.ReadScreen -> "Reading screen"
            is VdxIntent.Clarification -> result.question
            is VdxIntent.Unknown -> "Heard \"$text\". Try: call, whatsapp, uber, youtube, sms, email, open, read messages, read screen, memory"
        }
        Toast.makeText(this, msg, Toast.LENGTH_LONG).show()
        updateStatus()
    }

    // ════════════════════════════════════════════════════════════════
    // Harness: Run RobotHand with a hardcoded intent on a background
    // thread, then log the result to the harness log, logcat, and
    // structured JSON log.
    // ════════════════════════════════════════════════════════════════

    private fun runHarnessTest(label: String, intent: VdxIntent) {
        val robotHand = RobotHand(this, null)
        appendHarnessLog("▶ $label — executing RobotHand...")

        // Initialize structured logger for this test run
        val sessionId = "harness-${java.text.SimpleDateFormat("yyyyMMdd-HHmmss", java.util.Locale.US).format(java.util.Date())}"
        val logger = SessionLogger.create(this, sessionId)
        harnessLogger = logger

        logger.log("HARNESS_START", mapOf<String, Any?>(
            "label" to label,
            "intent" to (intent::class.simpleName ?: "Unknown"),
            "intent_data" to intent.toString()
        ))

        Thread {
            val startMs = System.currentTimeMillis()
            try {
                val result = robotHand.execute(intent)
                val elapsed = System.currentTimeMillis() - startMs
                logger.log("HARNESS_RESULT", mapOf(
                    "label" to label,
                    "status" to "success",
                    "result" to result,
                    "duration_ms" to elapsed
                ))
                runOnUiThread {
                    appendHarnessLog("✓ $label → ${result.take(120)} (${elapsed}ms)")
                    Toast.makeText(this, "$label: ${result.take(80)}", Toast.LENGTH_SHORT).show()
                }
            } catch (e: Exception) {
                val elapsed = System.currentTimeMillis() - startMs
                logger.logError("HARNESS_FAILURE", "${label} failed after ${elapsed}ms", e)
                runOnUiThread {
                    appendHarnessLog("✗ $label FAILED after ${elapsed}ms: ${e.message ?: "unknown error"}")
                    Toast.makeText(this, "$label failed: ${e.message?.take(60) ?: "unknown"}", Toast.LENGTH_SHORT).show()
                }
            }
        }.start()
    }

    /**
     * Run all test cases sequentially on a background thread.
     * Each test gets its own RobotHand instance. Results are logged
     * to structured JSON and the harness log.
     */
    private fun runAllTests(testCases: List<Pair<String, VdxIntent>>) {
        val sessionId = "suite-${java.text.SimpleDateFormat("yyyyMMdd-HHmmss", java.util.Locale.US).format(java.util.Date())}"
        val logger = SessionLogger.create(this, sessionId)
        harnessLogger = logger

        logger.log("SUITE_START", mapOf("test_count" to testCases.size))

        Thread {
            var passed = 0
            var failed = 0

            testCases.forEachIndexed { index, (label, intent) ->
                val robotHand = RobotHand(this, null)
                val startMs = System.currentTimeMillis()
                val logPrefix = "[${index + 1}/${testCases.size}]"

                runOnUiThread {
                    appendHarnessLog("$logPrefix $label...")
                }

                try {
                    val result = robotHand.execute(intent)
                    val elapsed = System.currentTimeMillis() - startMs
                    logger.log("TEST_PASS", mapOf<String, Any?>(
                        "label" to label,
                        "intent" to (intent::class.simpleName ?: "Unknown"),
                        "result" to result,
                        "duration_ms" to elapsed
                    ))
                    passed++
                    runOnUiThread {
                        appendHarnessLog("  ✓ $label (${elapsed}ms)")
                    }
                } catch (e: Exception) {
                    val elapsed = System.currentTimeMillis() - startMs
                    logger.logError("TEST_FAIL", "${label} failed after ${elapsed}ms", e)
                    failed++
                    runOnUiThread {
                        appendHarnessLog("  ✗ $label FAILED: ${e.message?.take(60) ?: "unknown"} (${elapsed}ms)")
                    }
                }
            }

            logger.log("SUITE_RESULT", mapOf(
                "total" to testCases.size,
                "passed" to passed,
                "failed" to failed
            ))

            runOnUiThread {
                appendHarnessLog("══════ RESULTS: $passed passed, $failed failed, ${testCases.size} total ══════")
                Toast.makeText(this, "Suite: $passed/$passed passed, $failed failed", Toast.LENGTH_LONG).show()
            }
        }.start()
    }

    private fun appendHarnessLog(line: String) {
        val current = harnessLog.text.toString()
        val lines = current.split("\n")
        // Keep last 30 lines
        val trimmed = if (lines.size >= 30) lines.drop(lines.size - 29).joinToString("\n") else current
        harnessLog.text = trimmed.trimEnd() + "\n" + line
        // Auto-scroll to bottom
        harnessLog.post { harnessLog.scrollTo(0, harnessLog.lineCount * harnessLog.lineHeight) }
    }
}
