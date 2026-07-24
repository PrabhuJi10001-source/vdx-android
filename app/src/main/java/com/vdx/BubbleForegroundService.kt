package com.vdx

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.SystemClock
import android.provider.Settings
import android.speech.tts.TextToSpeech
import android.text.InputType
import android.util.Log
import android.util.TypedValue
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.view.animation.AlphaAnimation
import android.view.animation.Animation
import android.view.animation.RotateAnimation
import android.view.animation.ScaleAnimation
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import android.net.Uri
import com.vdx.memory.UserMemoryStore
import kotlinx.coroutines.*
import java.util.Locale

/**
 * BubbleForegroundService — Messenger-style floating chat-head bubble.
 *
 * State machine:
 *   IDLE      → purple bubble, slow pulse + halo glow
 *   LISTENING → green bubble, fast waveform pulse
 *   THINKING  → amber bubble, spinning icon
 *   EXECUTING → blue bubble, progress bar + action text overlay
 *   SPEAKING  → blue bubble, slow pulse
 *   ERROR     → red bubble, shake
 *
 * Interactions:
 *   tap       → start SpeechRecognizer (or text fallback if no mic)
 *   long-press → show keyboard overlay (EditText) for noisy environments
 *
 * Intent routing:
 *   1. LlmBridge.extract() → VdxIntent
 *   2. RobotHand.execute() → accessibility-tree navigation inside target apps
 *   3. Fallback to deep-link intents if RobotHand fails
 *   4. Unknown + focused text field → insert text via accessibility
 *   5. Unknown + no text field → TTS "I can: call, whatsapp, uber, read messages, memory"
 */
class BubbleForegroundService : Service() {

    companion object {
        private const val TAG = "VDX"

        var isRunning = false
            private set
        private const val CHANNEL_ID = "vdx_bubble"
        private const val NOTIF_ID = 1

        /** Called by VdxAccessibilityService when a text field gains focus. */
        fun onTextFieldFocused() {
            // future hook; currently the service polls on demand
        }
    }

    // ──────────────────────────────────────────────────────────────────────
    // State Machine
    // ──────────────────────────────────────────────────────────────────────

    enum class BubbleState {
        IDLE, LISTENING, THINKING, EXECUTING, SPEAKING, ERROR
    }

    // ──────────────────────────────────────────────────────────────────────
    // Fields
    // ──────────────────────────────────────────────────────────────────────

    private var windowManager: WindowManager? = null
    private var bubbleView: View? = null
    private var bubbleIcon: ImageView? = null
    private var bubbleContainer: LinearLayout? = null
    private var bubbleParams: WindowManager.LayoutParams? = null
    private var haloView: View? = null
    private var progressBar: ProgressBar? = null
    private var textOverlay: View? = null
    private var executingOverlay: TextView? = null
    private var bubbleX = 0
    private var bubbleY = 0

    private var voiceCaptureManager: VoiceCaptureManager? = null
    private var tts: TextToSpeech? = null
    private val userMemoryStore by lazy { UserMemoryStore(this) }
    private val sessionMemory = SessionMemory()
    private var isListening = false

    private val llmBridge by lazy { LlmBridge(this) }
    private val robotHand by lazy { RobotHand(this, tts) }

    private var currentState: BubbleState = BubbleState.IDLE
        set(value) {
            field = value
            handler.post { updateBubbleVisuals() }
        }

    private val handler = Handler(Looper.getMainLooper())
    private var pulseRunnable: Runnable? = null

    // ──────────────────────────────────────────────────────────────────────
    // Lifecycle
    // ──────────────────────────────────────────────────────────────────────

    override fun onCreate() {
        super.onCreate()
        Log.d(TAG, "onCreate: BubbleForegroundService starting")
        isRunning = true
        createNotificationChannel()
        startForeground(NOTIF_ID, buildNotification())
        initTTS()
        showBubble()
        Log.d(TAG, "onCreate: service started, bubble should be visible")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Log.d(TAG, "onStartCommand: intent=$intent, flags=$flags, startId=$startId")
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        Log.d(TAG, "onDestroy: cleaning up BubbleForegroundService")
        isRunning = false
        stopPulse()
        hideExecutingOverlay()
        bubbleView?.let { windowManager?.removeView(it) }
        bubbleView = null
        textOverlay?.let { windowManager?.removeView(it) }
        textOverlay = null
        voiceCaptureManager?.stopCapture()
        voiceCaptureManager = null
        tts?.stop()
        tts?.shutdown()
        Log.d(TAG, "onDestroy: service destroyed")
    }

    // ──────────────────────────────────────────────────────────────────────
    // Notification
    // ──────────────────────────────────────────────────────────────────────

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID, "VDX Bubble", NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "VDX voice assistant bubble"
                setShowBadge(false)
            }
            getSystemService(NotificationManager::class.java)
                .createNotificationChannel(channel)
        }
    }

    private fun buildNotification(): Notification {
        val builder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Notification.Builder(this, CHANNEL_ID)
        } else {
            Notification.Builder(this)
        }
        return builder
            .setContentTitle("VDX")
            .setContentText("Voice assistant active — tap bubble to speak")
            .setSmallIcon(R.drawable.vdx_bubble)
            .setOngoing(true)
            .build()
    }

    // ──────────────────────────────────────────────────────────────────────
    // TTS
    // ──────────────────────────────────────────────────────────────────────

    private fun initTTS() {
        Log.d(TAG, "initTTS: initializing TextToSpeech with Google TTS engine")
        tts = TextToSpeech(this, { status ->
            if (status == TextToSpeech.SUCCESS) {
                tts?.language = Locale.US
                // ISSUE 2(c): Audio attributes with USAGE_ASSISTANT and CONTENT_TYPE_SPEECH
                val audioAttributes = AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_ASSISTANT)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build()
                tts?.setAudioAttributes(audioAttributes)
                Log.d(TAG, "initTTS: TTS ready, language=${Locale.US}, audioAttrs=USAGE_ASSISTANT/CONTENT_TYPE_SPEECH")
            } else {
                Log.e(TAG, "initTTS: TTS init failed, status=$status")
            }
        }, "com.google.android.tts")
    }

    private fun speak(text: String) {
        Log.d(TAG, "speak: \"$text\"")
        // ISSUE 2(b): Set STREAM_MUSIC to max volume so TTS is loud
        val audioManager = getSystemService(Context.AUDIO_SERVICE) as AudioManager
        audioManager.setStreamVolume(
            AudioManager.STREAM_MUSIC,
            audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC),
            0
        )
        // ISSUE 2(a): Request transient audio focus on STREAM_MUSIC
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val focusRequest = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT)
                .setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_ASSISTANT)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                        .build()
                )
                .build()
            audioManager.requestAudioFocus(focusRequest)
        } else {
            @Suppress("DEPRECATION")
            audioManager.requestAudioFocus(null, AudioManager.STREAM_MUSIC, AudioManager.AUDIOFOCUS_GAIN_TRANSIENT)
        }
        tts?.speak(text, TextToSpeech.QUEUE_FLUSH, null, "vdx_utterance")
        showTopToast(text)
    }

    /** ISSUE 3(d): Show a toast at the top of the screen so it doesn't overlap the bubble. */
    private fun showTopToast(text: String) {
        val toast = Toast.makeText(this, text, Toast.LENGTH_SHORT)
        toast.setGravity(Gravity.TOP or Gravity.CENTER_HORIZONTAL, 0, dpToPx(48))
        toast.show()
        Log.d(TAG, "Toast: $text")
    }

    // ──────────────────────────────────────────────────────────────────────
    // Bubble View
    // ──────────────────────────────────────────────────────────────────────

    private fun dpToPx(dp: Int): Int =
        TypedValue.applyDimension(
            TypedValue.COMPLEX_UNIT_DIP, dp.toFloat(), resources.displayMetrics
        ).toInt()

    private fun canDrawOverlays(): Boolean =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) Settings.canDrawOverlays(this)
        else true

    private fun overlayType(): Int =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        else
            @Suppress("DEPRECATION") WindowManager.LayoutParams.TYPE_PHONE

    private fun showBubble() {
        Log.d(TAG, "showBubble: creating bubble overlay")
        if (!canDrawOverlays()) {
            Log.w(TAG, "showBubble: overlay permission not granted")
            showTopToast("VDX needs overlay permission")
            return
        }

        windowManager = getSystemService(Context.WINDOW_SERVICE) as WindowManager

        // ISSUE 1(a): 64dp x 64dp (was 56dp x 80dp)
        // ISSUE 1(d): 64dp > 48dp minimum tap target
        val bubbleSize = dpToPx(80)
        val haloSize = dpToPx(96)   // ISSUE 1(c): larger halo behind bubble
        val x = dpToPx(16)
        val y = dpToPx(120)
        bubbleX = x
        bubbleY = y

        // ISSUE 1(c): Halo — translucent glow circle behind the bubble
        val halo = View(this).apply {
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(Color.argb(40, 108, 58, 237))
            }
        }
        haloView = halo

        // VDX mic icon (vector drawable)
        val icon = ImageView(this).apply {
            setImageResource(R.drawable.vdx_bubble)
            scaleType = ImageView.ScaleType.CENTER_INSIDE
            setColorFilter(Color.WHITE)
        }
        bubbleIcon = icon

        // Progress bar (hidden by default, shown in THINKING / EXECUTING)
        val progress = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
            max = 100
            progress = 0
            visibility = View.GONE
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                dpToPx(4)
            )
        }
        progressBar = progress

        // Container: pill with icon + progress stacked vertically
        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(dpToPx(12), dpToPx(8), dpToPx(12), dpToPx(8))
            addView(icon, LinearLayout.LayoutParams(dpToPx(32), dpToPx(32)))
            addView(progress)
        }
        bubbleContainer = container

        // Apply pill-shaped GradientDrawable background
        applyPillBackground(container, BubbleState.IDLE)
        applyHaloBackground(halo, BubbleState.IDLE)
        container.elevation = dpToPx(8).toFloat()

        // ISSUE 1(c): FrameLayout wrapping halo (behind) + container (front)
        val frame = FrameLayout(this).apply {
            addView(halo, FrameLayout.LayoutParams(haloSize, haloSize, Gravity.CENTER))
            addView(container, FrameLayout.LayoutParams(bubbleSize, bubbleSize, Gravity.CENTER))
        }

        val params = WindowManager.LayoutParams(
            haloSize,
            haloSize,
            overlayType(),
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                WindowManager.LayoutParams.FLAG_TOUCHABLE_WHEN_WAKING,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            this.x = x
            this.y = y
        }
        bubbleParams = params

        // Touch: drag + tap + long-press
        // Uses GestureDetector for tap/long-press and raw touch for drag.
        // This avoids the double-fire issues from a single OnTouchListener
        // that inconsistently handles ACTION_OUTSIDE.
        frame.isClickable = true
        frame.isFocusable = false

        val gestureDetector = android.view.GestureDetector(this, object : android.view.GestureDetector.SimpleOnGestureListener() {
            override fun onLongPress(e: MotionEvent) {
                onLongPress()
            }

            override fun onSingleTapUp(e: MotionEvent): Boolean {
                onTap()
                return true
            }

            override fun onDown(e: MotionEvent): Boolean {
                // Must return true to consume the event sequence
                return true
            }
        })

        // Disable long-press timeout from GestureDetector so our 500ms threshold
        // in the drag handler is the sole arbiter. We handle long-press ourselves
        // via the drag handler's heldMs check.
        gestureDetector.setIsLongpressEnabled(false)

        frame.setOnTouchListener(object : View.OnTouchListener {
            private var initialX = 0
            private var initialY = 0
            private var touchX = 0f
            private var touchY = 0f
            private var moved = false
            private var downTime = 0L

            override fun onTouch(v: View, event: MotionEvent): Boolean {
                Log.d(TAG, "onTouch: action=${event.action} x=${event.x} y=${event.y} rawX=${event.rawX} rawY=${event.rawY}")
                when (event.action) {
                    MotionEvent.ACTION_DOWN -> {
                        initialX = params.x
                        initialY = params.y
                        touchX = event.rawX
                        touchY = event.rawY
                        moved = false
                        downTime = SystemClock.uptimeMillis()
                        v.animate().scaleX(0.92f).scaleY(0.92f).setDuration(80).start()
                        // Also feed to gesture detector for tap detection
                        gestureDetector.onTouchEvent(event)
                    }
                    MotionEvent.ACTION_MOVE -> {
                        val dx = event.rawX - touchX
                        val dy = event.rawY - touchY
                        if (kotlin.math.abs(dx) > 10 || kotlin.math.abs(dy) > 10) moved = true
                        if (moved) {
                            params.x = initialX + dx.toInt()
                            params.y = initialY + dy.toInt()
                            bubbleX = params.x
                            bubbleY = params.y
                            windowManager?.updateViewLayout(bubbleView, params)
                        }
                    }
                    MotionEvent.ACTION_UP -> {
                        v.animate().scaleX(1f).scaleY(1f).setDuration(80).start()
                        if (!moved) {
                            val heldMs = SystemClock.uptimeMillis() - downTime
                            if (heldMs >= 500) {
                                onLongPress()
                            } else {
                                // Feed to gesture detector for single-tap
                                gestureDetector.onTouchEvent(event)
                            }
                        }
                    }
                    MotionEvent.ACTION_CANCEL -> {
                        // System cancelled the gesture — reset state
                        v.animate().scaleX(1f).scaleY(1f).setDuration(80).start()
                        moved = false
                    }
                    MotionEvent.ACTION_OUTSIDE -> {
                        // Consume but do nothing — prevents synthetic double-fire
                        Log.d(TAG, "onTouch: ACTION_OUTSIDE (consumed)")
                    }
                }
                return true
            }
        })

        bubbleView = frame
        windowManager?.addView(frame, params)
        currentState = BubbleState.IDLE
        // ISSUE 1(e): Log bubble position
        Log.d(TAG, "showBubble: bubble at x=$x y=$y size=$bubbleSize")
        showTopToast("VDX bubble is live — tap to speak, hold to type")
    }

    // ──────────────────────────────────────────────────────────────────────
    // Pill Background — GradientDrawable with rounded corners
    // ──────────────────────────────────────────────────────────────────────

    private fun applyPillBackground(view: View, state: BubbleState) {
        val (startColor, endColor) = stateColors(state)
        val cornerRadius = dpToPx(28).toFloat()

        val drawable = GradientDrawable().apply {
            orientation = GradientDrawable.Orientation.LEFT_RIGHT
            colors = intArrayOf(startColor, endColor)
            this.cornerRadius = cornerRadius
            setStroke(dpToPx(2), Color.argb(80, 255, 255, 255))
        }
        view.background = drawable
    }

    /** ISSUE 1(c): Apply translucent halo color matching the current state */
    private fun applyHaloBackground(view: View, state: BubbleState) {
        val color = when (state) {
            BubbleState.IDLE      -> Color.argb(40, 108, 58, 237)
            BubbleState.LISTENING -> Color.argb(60, 34, 197, 94)
            BubbleState.THINKING  -> Color.argb(50, 245, 158, 11)
            BubbleState.EXECUTING -> Color.argb(50, 59, 130, 246)
            BubbleState.SPEAKING  -> Color.argb(50, 59, 130, 246)
            BubbleState.ERROR     -> Color.argb(60, 239, 68, 68)
        }
        view.background = GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(color)
        }
    }

    private fun stateColors(state: BubbleState): Pair<Int, Int> {
        return when (state) {
            BubbleState.IDLE      -> Color.parseColor("#6c3aed") to Color.parseColor("#5b2fd9")
            BubbleState.LISTENING -> Color.parseColor("#22c55e") to Color.parseColor("#16a34a")
            BubbleState.THINKING  -> Color.parseColor("#f59e0b") to Color.parseColor("#d97706")
            BubbleState.EXECUTING -> Color.parseColor("#3b82f6") to Color.parseColor("#2563eb")
            BubbleState.SPEAKING  -> Color.parseColor("#3b82f6") to Color.parseColor("#2563eb")
            BubbleState.ERROR     -> Color.parseColor("#ef4444") to Color.parseColor("#f59e0b")
        }
    }

    private fun updateBubbleVisuals() {
        val container = bubbleContainer ?: return
        val progress = progressBar ?: return
        val halo = haloView ?: return

        Log.d(TAG, "updateBubbleVisuals: state=$currentState")

        // Clear previous animations
        stopPulse()
        bubbleIcon?.clearAnimation()

        // Hide executing overlay unless we're in EXECUTING state
        if (currentState != BubbleState.EXECUTING) {
            hideExecutingOverlay()
        }

        // Update background colors
        applyPillBackground(container, currentState)
        applyHaloBackground(halo, currentState)

        // Progress bar visibility
        when (currentState) {
            BubbleState.THINKING -> {
                progress.isIndeterminate = true
                progress.visibility = View.VISIBLE
            }
            BubbleState.EXECUTING -> {
                progress.isIndeterminate = false
                progress.visibility = View.VISIBLE
                progress.progress = 0
                animateProgress(progress)
            }
            else -> {
                progress.visibility = View.GONE
            }
        }

        // ISSUE 4: State-specific animations
        when (currentState) {
            BubbleState.IDLE -> startIdlePulse()           // (b) slow scale 1.0→1.1→1.0 every 2s
            BubbleState.LISTENING -> startListeningPulse()  // (a) green + fast waveform pulse
            BubbleState.THINKING -> startThinkingSpin()    // (b) spinning progress indicator
            BubbleState.SPEAKING -> startSpeakingPulse()   // (d) blue + slow pulse
            BubbleState.ERROR -> shakeBubble(container)    // (e) red + shake
            BubbleState.EXECUTING -> { /* progress bar + text overlay handle visuals */ }
        }
    }

    private fun animateProgress(bar: ProgressBar) {
        handler.post(object : Runnable {
            override fun run() {
                if (bar.progress < 100) {
                    bar.progress += 5
                    handler.postDelayed(this, 50)
                }
            }
        })
    }

    // ──────────────────────────────────────────────────────────────────────
    // Animations
    // ──────────────────────────────────────────────────────────────────────

    /** ISSUE 1(b): IDLE — slow scale 1.0 → 1.1 → 1.0 every 2 seconds */
    private fun startIdlePulse() {
        val container = bubbleContainer ?: return
        val scaleAnim = ScaleAnimation(
            1f, 1.1f, 1f, 1.1f,
            Animation.RELATIVE_TO_SELF, 0.5f,
            Animation.RELATIVE_TO_SELF, 0.5f
        ).apply {
            duration = 1000
            repeatMode = Animation.REVERSE
            repeatCount = Animation.INFINITE
        }
        container.startAnimation(scaleAnim)
        pulseRunnable = Runnable { container.startAnimation(scaleAnim) }
    }

    /** ISSUE 4(a): LISTENING — fast waveform-like scale animation (green color from stateColors) */
    private fun startListeningPulse() {
        val container = bubbleContainer ?: return
        val scaleAnim = ScaleAnimation(
            1f, 1.25f, 1f, 1.25f,
            Animation.RELATIVE_TO_SELF, 0.5f,
            Animation.RELATIVE_TO_SELF, 0.5f
        ).apply {
            duration = 200
            repeatMode = Animation.REVERSE
            repeatCount = Animation.INFINITE
        }
        container.startAnimation(scaleAnim)
        pulseRunnable = Runnable { container.startAnimation(scaleAnim) }
    }

    /** ISSUE 4(d): SPEAKING — slow gentle pulse (blue color from stateColors) */
    private fun startSpeakingPulse() {
        val container = bubbleContainer ?: return
        val scaleAnim = ScaleAnimation(
            1f, 1.08f, 1f, 1.08f,
            Animation.RELATIVE_TO_SELF, 0.5f,
            Animation.RELATIVE_TO_SELF, 0.5f
        ).apply {
            duration = 1500
            repeatMode = Animation.REVERSE
            repeatCount = Animation.INFINITE
        }
        container.startAnimation(scaleAnim)
        pulseRunnable = Runnable { container.startAnimation(scaleAnim) }
    }

    /** ISSUE 4(b): THINKING — spinning progress indicator on the bubble icon */
    private fun startThinkingSpin() {
        val icon = bubbleIcon ?: return
        val rotate = RotateAnimation(
            0f, 360f,
            Animation.RELATIVE_TO_SELF, 0.5f,
            Animation.RELATIVE_TO_SELF, 0.5f
        ).apply {
            duration = 800
            repeatMode = Animation.RESTART
            repeatCount = Animation.INFINITE
        }
        icon.startAnimation(rotate)
    }

    private fun stopPulse() {
        bubbleContainer?.clearAnimation()
        bubbleIcon?.clearAnimation()
        pulseRunnable = null
    }

    /** ISSUE 4(e): ERROR — shake briefly (red color from stateColors) */
    private fun shakeBubble(view: View) {
        val shake = android.view.animation.TranslateAnimation(
            0f, dpToPx(6).toFloat(), 0f, 0f
        ).apply {
            duration = 50
            repeatMode = Animation.REVERSE
            repeatCount = 3
        }
        view.startAnimation(shake)
    }

    // ──────────────────────────────────────────────────────────────────────
    // Executing Overlay — ISSUE 4(c): text near the bubble showing what it's doing
    // ──────────────────────────────────────────────────────────────────────

    private fun showExecutingOverlay(text: String) {
        hideExecutingOverlay()
        val tv = TextView(this).apply {
            setText(text)
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            setPadding(dpToPx(12), dpToPx(6), dpToPx(12), dpToPx(6))
            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                setColor(Color.argb(200, 0, 0, 0))
                cornerRadius = dpToPx(16).toFloat()
            }
        }
        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            overlayType(),
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            this.x = bubbleX + dpToPx(100)
            this.y = bubbleY
        }
        executingOverlay = tv
        windowManager?.addView(tv, params)
    }

    private fun hideExecutingOverlay() {
        executingOverlay?.let {
            try {
                windowManager?.removeView(it)
            } catch (e: Exception) {
                Log.w(TAG, "hideExecutingOverlay: failed to remove view", e)
            }
        }
        executingOverlay = null
    }

    private fun describeIntent(intent: VdxIntent): String {
        return when (intent) {
            is VdxIntent.Call      -> "Calling ${intent.contact}..."
            is VdxIntent.WhatsApp  -> "WhatsApp ${intent.contact}..."
            is VdxIntent.Sms       -> "SMS ${intent.contact}..."
            is VdxIntent.Uber      -> "Uber to ${intent.destination}..."
            is VdxIntent.YouTube   -> "YouTube: ${intent.searchQuery}..."
            is VdxIntent.Email     -> "Email ${intent.contact}..."
            is VdxIntent.AppLaunch -> "Opening ${intent.appName}..."
            is VdxIntent.ReadSms   -> "Reading messages..."
            is VdxIntent.ReadScreen-> "Reading screen..."
            else -> intent::class.simpleName ?: "Working..."
        }
    }

    // ──────────────────────────────────────────────────────────────────────
    // Interactions
    // ──────────────────────────────────────────────────────────────────────

    private fun onTap() {
        Log.d(TAG, "onTap: isListening=$isListening")
        if (isListening) return
        startVoiceCapture()
    }

    private fun onLongPress() {
        Log.d(TAG, "onLongPress: showing text input overlay")
        if (isListening) {
            Log.d(TAG, "onLongPress: stopping VoiceCaptureManager (was listening)")
            voiceCaptureManager?.stopCapture()
            isListening = false
        }
        showTextInputOverlay()
    }

    // ──────────────────────────────────────────────────────────────────────
    // Voice Capture (AudioRecord + VAD + Cloud STT)
    // ──────────────────────────────────────────────────────────────────────

    private fun startVoiceCapture() {
        Log.d(TAG, "startVoiceCapture: initializing VoiceCaptureManager")
        if (isListening) {
            Log.w(TAG, "startVoiceCapture: already listening, ignoring")
            return
        }
        isListening = true
        currentState = BubbleState.LISTENING
        showTopToast("🎤 Listening...")

        // Create VoiceCaptureManager with callback
        voiceCaptureManager = VoiceCaptureManager(this, object : VoiceCaptureManager.Callback {
            override fun onSpeechStart() {
                Log.d(TAG, "VCM onSpeechStart: speech detected")
                handler.post {
                    if (currentState == BubbleState.LISTENING) {
                        Log.d(TAG, "VCM onSpeechStart: bubble already in LISTENING state")
                    }
                }
            }

            override fun onSpeechEnd(audioData: ShortArray) {
                Log.d(TAG, "VCM onSpeechEnd: ${audioData.size} samples captured")
                handler.post {
                    isListening = false
                    currentState = BubbleState.THINKING
                }
            }

            override fun onTranscript(text: String) {
                Log.d(TAG, "VCM onTranscript: transcript=\"$text\"")
                handler.post {
                    if (text.isBlank()) {
                        // No STT API key configured — fallback to keyboard
                        Log.d(TAG, "VCM onTranscript: no transcript (no API key), showing keyboard fallback")
                        speak("No STT API configured. Say your command or use keyboard.")
                        currentState = BubbleState.IDLE
                        handler.postDelayed({
                            showTextInputOverlay()
                        }, 1000)
                    } else {
                        // We got a transcript — process it
                        showTopToast("Heard: $text")
                        Log.d(TAG, "Awareness: Heard \"$text\"")
                        handleIntent(text)
                    }
                }
            }

            override fun onError(message: String) {
                Log.e(TAG, "VCM onError: $message")
                handler.post {
                    isListening = false
                    currentState = BubbleState.ERROR
                    speak(message)
                    handler.postDelayed({
                        currentState = BubbleState.IDLE
                        Log.d(TAG, "VCM onError: showing keyboard fallback")
                        showTextInputOverlay()
                    }, 800)
                }
            }

            override fun onAudioLevel(rmsdB: Float) {
                // Update bubble pulse intensity based on audio level
                // Map -60..0 dB to scale multiplier 1.0..1.4
                val normalized = ((rmsdB + 60f) / 60f).coerceIn(0f, 1f)
                val scaleBoost = normalized * 0.4f
                handler.post {
                    val container = bubbleContainer ?: return@post
                    // Only adjust if in LISTENING state
                    if (currentState == BubbleState.LISTENING) {
                        val scale = 1f + scaleBoost
                        container.scaleX = scale
                        container.scaleY = scale
                    }
                }
            }

            override fun onIntent(intent: VdxIntent) {
                Log.d(TAG, "VCM onIntent: $intent")
                handler.post {
                    handleIntentFromVoice(intent)
                }
            }
        })

        Log.d(TAG, "startVoiceCapture: calling startCapture()")
        voiceCaptureManager?.startCapture()
        Log.d(TAG, "startVoiceCapture: VoiceCaptureManager.startCapture called")
    }

    // ──────────────────────────────────────────────────────────────────────
    // Text Input Overlay (keyboard fallback)
    // ──────────────────────────────────────────────────────────────────────

    private fun showTextInputOverlay() {
        Log.d(TAG, "showTextInputOverlay: creating text input overlay")
        // Remove any existing overlay
        textOverlay?.let { windowManager?.removeView(it) }

        currentState = BubbleState.LISTENING

        val editText = EditText(this).apply {
            hint = "Type: call mom, whatsapp john, uber to airport..."
            setTextColor(Color.parseColor("#1a1a2e"))
            setHintTextColor(Color.GRAY)
            setPadding(dpToPx(16), dpToPx(14), dpToPx(16), dpToPx(14))
            inputType = InputType.TYPE_CLASS_TEXT
            textSize = 15f
            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                setColor(Color.parseColor("#f5f5f7"))
                cornerRadius = dpToPx(12).toFloat()
                setStroke(dpToPx(1), Color.parseColor("#6c3aed"))
            }
        }

        val innerPadding = dpToPx(12)
        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(innerPadding, innerPadding, innerPadding, innerPadding)
            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                setColor(Color.WHITE)
                cornerRadius = dpToPx(20).toFloat()
                setStroke(dpToPx(1), Color.parseColor("#e0e0e0"))
            }
            addView(editText)
        }

        val overlayParams = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            overlayType(),
            WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.CENTER
            dimAmount = 0.4f
        }

        // We need a dim layer behind, so wrap in a FrameLayout
        val dimView = View(this).apply {
            setBackgroundColor(Color.argb(100, 0, 0, 0))
        }
        val frame = FrameLayout(this).apply {
            addView(dimView, FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            ))
            addView(container, FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.WRAP_CONTENT,
                Gravity.CENTER
            ).apply {
                marginStart = dpToPx(24)
                marginEnd = dpToPx(24)
            })
        }

        // Tap outside to dismiss
        dimView.setOnTouchListener { _, event ->
            if (event.action == MotionEvent.ACTION_DOWN) {
                removeTextOverlay()
                currentState = BubbleState.IDLE
            }
            true
        }

        // Submit on Enter
        editText.setOnEditorActionListener { _, _, _ ->
            val text = editText.text.toString().trim()
            removeTextOverlay()
            if (text.isNotBlank()) {
                Log.d(TAG, "text input submitted: \"$text\"")
                // ISSUE 3(a): Show what was heard (typed)
                showTopToast("Heard: $text")
                Log.d(TAG, "Awareness: Heard \"$text\"")
                currentState = BubbleState.THINKING
                handleIntent(text)
            } else {
                currentState = BubbleState.IDLE
            }
            true
        }

        textOverlay = frame
        windowManager?.addView(frame, overlayParams)
        editText.requestFocus()
        val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
        imm.showSoftInput(editText, 0)
    }

    private fun removeTextOverlay() {
        textOverlay?.let {
            windowManager?.removeView(it)
            textOverlay = null
        }
    }

    // ──────────────────────────────────────────────────────────────────────
    // Intent Handling
    // ──────────────────────────────────────────────────────────────────────

    private fun handleIntent(transcript: String) {
        Log.d(TAG, "handleIntent: transcript=\"$transcript\"")
        currentState = BubbleState.THINKING
        // Run LLM extraction on a background thread, then dispatch on main thread
        llmBridge.extractAsync(transcript) { result ->
            Log.d(TAG, "handleIntent: parsed intent = ${result::class.simpleName} → $result")
            // ISSUE 3(b): Show the parsed intent — Toast at top of screen
            val intentDescription = result::class.simpleName ?: "Unknown"
            showTopToast("Intent: $intentDescription")
            Log.d(TAG, "Awareness: Intent parsed = $intentDescription")

            when (result) {
                // ── Memory: read from persistent user memory store ──
                is VdxIntent.Memory -> {
                    Log.d(TAG, "handleIntent: Memory intent — reading user memory")
                    // Run on IO thread
                    kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.IO).launch {
                        val count = userMemoryStore.count()
                        val top = userMemoryStore.getTopMemories()
                        val summary = if (count == 0) {
                            "No memories stored yet."
                        } else {
                            val items = top.take(5).joinToString(". ") { "${it.key}: ${it.value}" }
                            "You have $count memories. Top: $items"
                        }
                        handler.post {
                            speak(summary)
                            currentState = BubbleState.SPEAKING
                            handler.postDelayed({ currentState = BubbleState.IDLE }, 3000)
                        }
                    }
                }

                // ── Clarification: speak the question ──
                is VdxIntent.Clarification -> {
                    Log.d(TAG, "handleIntent: Clarification — question=\"${result.question}\"")
                    sessionMemory.add("Clarification: ${result.question}")
                    currentState = BubbleState.SPEAKING
                    speak(result.question)
                    handler.postDelayed({ currentState = BubbleState.IDLE }, 3000)
                }

                // ── Unknown: try text insertion via accessibility, then speak capabilities ──
                is VdxIntent.Unknown -> {
                    Log.d(TAG, "handleIntent: Unknown intent — trying text insertion")
                    val a11y = VdxAccessibilityService.instance
                    if (a11y != null) {
                        val focused = a11y.findFocusedTextField()
                        if (focused != null) {
                            Log.d(TAG, "handleIntent: found focused text field, inserting transcript")
                            val inserted = a11y.insertText(focused, transcript)
                            if (inserted) {
                                speak("Inserted text.")
                                currentState = BubbleState.SPEAKING
                                handler.postDelayed({ currentState = BubbleState.IDLE }, 1500)
                                return@extractAsync
                            } else {
                                Log.w(TAG, "handleIntent: text insertion failed")
                            }
                        } else {
                            Log.d(TAG, "handleIntent: no focused text field found")
                        }
                    } else {
                        Log.w(TAG, "handleIntent: accessibility service not running")
                    }
                    // No text field or accessibility not enabled → speak capabilities
                    speak("I can: call, whatsapp, sms, uber, youtube, email, open apps, read messages, read screen, memory")
                    currentState = BubbleState.SPEAKING
                    handler.postDelayed({ currentState = BubbleState.IDLE }, 2500)
                }

                // ── All action intents: dispatch to RobotHand ──
                else -> {
                    sessionMemory.add("${result::class.simpleName} → $result")
                    currentState = BubbleState.EXECUTING
                    executeWithRobotHand(result)
                }
            }
        }
    }

    /**
     * Handle a pre-parsed VdxIntent from voice capture (Gemini multimodal).
     * Skips LLM extraction since the intent is already structured.
     */
    private fun handleIntentFromVoice(intent: VdxIntent) {
        Log.d(TAG, "handleIntentFromVoice: $intent")
        currentState = BubbleState.THINKING
        val intentDescription = intent::class.simpleName ?: "Unknown"
        showTopToast("Intent: $intentDescription")

        when (intent) {
            is VdxIntent.Memory -> {
                kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.IO).launch {
                    val count = userMemoryStore.count()
                    val top = userMemoryStore.getTopMemories()
                    val summary = if (count == 0) {
                        "No memories stored yet."
                    } else {
                        "I remember ${count} things. Top: ${top.take(3).joinToString { it.key }}"
                    }
                    handler.post {
                        speak(summary)
                        currentState = BubbleState.IDLE
                    }
                }
            }
            is VdxIntent.Clarification -> {
                sessionMemory.add("Clarification: ${intent.question}")
                speak(intent.question)
                currentState = BubbleState.IDLE
            }
            is VdxIntent.Unknown -> {
                sessionMemory.add("Unknown: ${intent.raw}")
                speak("I didn't understand. Try: call, whatsapp, uber, youtube, sms, email, open.")
                currentState = BubbleState.IDLE
            }
            else -> {
                sessionMemory.add("${intent::class.simpleName} → $intent")
                currentState = BubbleState.EXECUTING
                executeWithRobotHand(intent)
            }
        }
    }

    /**
     * Execute a [VdxIntent] via RobotHand on a background thread.
     * RobotHand walks the accessibility tree inside target apps.
     * If RobotHand fails, falls back to deep-link navigation.
     */
    private fun executeWithRobotHand(intent: VdxIntent) {
        val intentName = intent::class.simpleName
        Log.d(TAG, "executeWithRobotHand: dispatching $intentName to RobotHand")

        // ISSUE 3(c): Show what's being executed — Toast at top of screen
        val action = intentName ?: "action"
        showTopToast("Executing: $action")
        Log.d(TAG, "Awareness: Executing $action")

        // ISSUE 4(c): Show text overlay near bubble with action description
        showExecutingOverlay(describeIntent(intent))

        Thread {
            try {
                val resultStr = robotHand.execute(intent)
                Log.d(TAG, "executeWithRobotHand: RobotHand result for $intentName = \"$resultStr\"")
                handler.post {
                    hideExecutingOverlay()
                    currentState = BubbleState.SPEAKING
                    handler.postDelayed({ currentState = BubbleState.IDLE }, 2000)
                }
            } catch (e: Exception) {
                Log.e(TAG, "executeWithRobotHand: RobotHand failed for $intentName", e)
                handler.post {
                    hideExecutingOverlay()
                    Log.w(TAG, "executeWithRobotHand: falling back to deep link for $intentName")
                    fallbackDeepLink(intent)
                }
            }
        }.start()
    }

    /**
     * Fallback deep-link navigation — used when RobotHand fails.
     * Launches intents via URI schemes (wa.me, tel:, smsto:, etc.).
     */
    private fun fallbackDeepLink(intent: VdxIntent) {
        Log.w(TAG, "fallbackDeepLink: ${intent::class.simpleName}")
        hideExecutingOverlay()
        try {
            when (intent) {
                is VdxIntent.WhatsApp -> {
                    speak("Opening WhatsApp for ${intent.contact}.")
                    val waIntent = Intent(Intent.ACTION_VIEW).apply {
                        data = Uri.parse("https://wa.me/${intent.contact}")
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    }
                    startActivity(waIntent)
                    handler.postDelayed({ currentState = BubbleState.SPEAKING }, 500)
                    handler.postDelayed({ currentState = BubbleState.IDLE }, 2000)
                }

                is VdxIntent.Call -> {
                    speak("Calling ${intent.contact}.")
                    val callIntent = Intent(Intent.ACTION_DIAL).apply {
                        data = Uri.parse("tel:${intent.contact}")
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    }
                    startActivity(callIntent)
                    handler.postDelayed({ currentState = BubbleState.IDLE }, 1500)
                }

                is VdxIntent.Sms -> {
                    speak("Opening messages for ${intent.contact}.")
                    val smsIntent = Intent(Intent.ACTION_VIEW).apply {
                        data = Uri.parse("smsto:${intent.contact}")
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    }
                    startActivity(smsIntent)
                    handler.postDelayed({ currentState = BubbleState.IDLE }, 2000)
                }

                is VdxIntent.ReadSms -> {
                    speak("Opening messages.")
                    val smsIntent = Intent(Intent.ACTION_MAIN).apply {
                        addCategory(Intent.CATEGORY_APP_MESSAGING)
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    }
                    startActivity(smsIntent)
                    handler.postDelayed({ currentState = BubbleState.IDLE }, 1500)
                }

                is VdxIntent.Uber -> {
                    speak("Opening Uber for ${intent.destination}.")
                    val uberIntent = Intent(Intent.ACTION_VIEW).apply {
                        data = Uri.parse(
                            "https://m.uber.com/ul?action=setPickup&pickup=my_location" +
                            "&drop[formatted_address]=${intent.destination}"
                        )
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    }
                    try {
                        startActivity(uberIntent)
                        handler.postDelayed({ currentState = BubbleState.IDLE }, 2000)
                    } catch (e: Exception) {
                        Log.w(TAG, "fallbackDeepLink: Uber deep link failed, trying maps", e)
                        val mapsIntent = Intent(Intent.ACTION_VIEW).apply {
                            data = Uri.parse("https://maps.google.com/?q=${intent.destination}")
                            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        }
                        startActivity(mapsIntent)
                        speak("Uber not available. Opened maps instead.")
                        handler.postDelayed({ currentState = BubbleState.IDLE }, 2000)
                    }
                }

                is VdxIntent.YouTube -> {
                    speak("Searching YouTube for ${intent.searchQuery}.")
                    val ytIntent = Intent(Intent.ACTION_VIEW).apply {
                        data = Uri.parse("https://www.youtube.com/results?search_query=${intent.searchQuery}")
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    }
                    startActivity(ytIntent)
                    handler.postDelayed({ currentState = BubbleState.IDLE }, 2000)
                }

                is VdxIntent.Email -> {
                    speak("Opening email for ${intent.contact}.")
                    val emailIntent = Intent(Intent.ACTION_SENDTO).apply {
                        data = Uri.parse("mailto:${intent.contact}")
                        putExtra(Intent.EXTRA_TEXT, intent.message)
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    }
                    startActivity(emailIntent)
                    handler.postDelayed({ currentState = BubbleState.IDLE }, 2000)
                }

                is VdxIntent.AppLaunch -> {
                    speak("Opening ${intent.appName}.")
                    val launchIntent = packageManager.getLaunchIntentForPackage(intent.appName)
                    if (launchIntent != null) {
                        launchIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        startActivity(launchIntent)
                    } else {
                        val searchIntent = Intent(Intent.ACTION_VIEW).apply {
                            data = Uri.parse("market://details?id=${intent.appName}")
                            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        }
                        try {
                            startActivity(searchIntent)
                        } catch (e: Exception) {
                            speak("Could not find ${intent.appName}.")
                            currentState = BubbleState.ERROR
                            handler.postDelayed({ currentState = BubbleState.IDLE }, 1500)
                            return
                        }
                    }
                    handler.postDelayed({ currentState = BubbleState.IDLE }, 2000)
                }

                is VdxIntent.ReadScreen -> {
                    val a11y = VdxAccessibilityService.instance
                    if (a11y != null) {
                        speak("Reading screen.")
                        handler.postDelayed({ currentState = BubbleState.IDLE }, 2000)
                    } else {
                        speak("Accessibility service not enabled. Enable it in settings.")
                        currentState = BubbleState.ERROR
                        handler.postDelayed({ currentState = BubbleState.IDLE }, 2500)
                    }
                }

                // Memory, Clarification, Unknown are handled before this fallback
                else -> {
                    Log.w(TAG, "fallbackDeepLink: unexpected intent ${intent::class.simpleName}")
                    currentState = BubbleState.IDLE
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "fallbackDeepLink: deep link also failed for ${intent::class.simpleName}", e)
            currentState = BubbleState.ERROR
            speak("Could not complete action.")
            handler.postDelayed({ currentState = BubbleState.IDLE }, 1500)
        }
    }
}