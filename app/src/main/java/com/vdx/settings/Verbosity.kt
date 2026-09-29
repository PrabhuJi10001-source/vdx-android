package com.vdx.settings

import android.content.Context

/**
 * Accessibility verbosity dial for VDX.
 *
 * One persisted Int (0..10, default 5) mapped to observable speech behaviour. The dial
 * is a plain preference (NOT a secret — KeyVault is not needed). It gates HOW MUCH the
 * TTS speaks, not the wording of any message and not the visual feedback path.
 *
 * Bands (semantics):
 *   0   = SILENT        — no TTS at all; visual feedback only (toasts + bubble colours).
 *   1-2 = MINIMAL       — speak only destructive-action confirmations + errors.
 *   3-4 = DEFAULT-LOW   — success one-liners + errors + confirmations.
 *   5-6 = STANDARD      — default: step narration ("Calling Mom", "Opening WhatsApp").
 *   7-8 = STEP-BY-STEP  — additionally announce each RobotHand step as it executes.
 *   9-10= SCREEN READER — also announce screen content on app switch / read-screen.
 */
object Verbosity {

    const val LEVEL_KEY = "vdx_verbosity"
    const val DEFAULT = 5
    const val MIN = 0
    const val MAX = 10

    // Message categories → minimum level at which that message is spoken.
    // A message is spoken only when the configured level >= its minLevel (and level > 0).
    const val MIN_ERROR = 1      // errors, destructive-action confirmations
    const val MIN_CONFIRM = 1    // confirmation prompts / cancellations
    const val MIN_SUCCESS = 3    // success one-liners ("Opened WhatsApp")
    const val MIN_STANDARD = 5   // step narration (current default behaviour)
    const val MIN_STEP = 7       // per-RobotHand-step announcements
    const val MIN_SCREEN = 9     // screen-reading announcements

    private const val PREFS = "vdx_prefs"

    /** A level and the plain-language caption for the audio band it belongs to. */
    data class Band(val level: Int, val caption: String)

    /**
     * Plain-language caption per [level], matching the UI card bands. Never blank for
     * any level 0..10.
     */
    private val captions: Map<Int, String> = mapOf(
        0 to "Silent — visual only",
        1 to "Minimal — errors and confirmations",
        2 to "Minimal — errors and confirmations",
        3 to "Default — results spoken",
        4 to "Default — results spoken",
        5 to "Default — results spoken",
        6 to "Default — results spoken",
        7 to "Full narration",
        8 to "Full narration",
        9 to "Screen reader mode (all changes announced)",
        10 to "Screen reader mode (all changes announced)"
    )

    /** The band caption for a given [level] (clamped; never null). */
    fun caption(level: Int): String = captions[clamp(level)]!!

    /** Clamp into the valid 0..10 range. Pure, testable. */
    fun clamp(value: Int): Int = value.coerceIn(MIN, MAX)

    /** Read the persisted level, clamped. */
    fun level(context: Context): Int =
        clamp(context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getInt(LEVEL_KEY, DEFAULT))

    /** Persist a level (clamped). Immediate — no save button needed. */
    fun set(context: Context, value: Int) {
        val v = clamp(value)
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putInt(LEVEL_KEY, v)
            .apply()
    }
}

/**
 * The single choke point for TTS at every level. Pure decision logic (no Android calls):
 * the actual speech implementation is injected via [speaker] as a function reference so
 * this object stays unit-testable on the JVM. Visual feedback (toasts) is NOT funneled
 * through here — that path is unconditional for deaf/silent-level parity.
 */
object VerbosityFilter {

    /** Outcome of a gating decision — kept so callers/tests can inspect why nothing spoke. */
    data class SpeakDecision(
        val spoken: Boolean,
        val level: Int,
        val minLevel: Int
    )

    /**
     * Should a message gated at [minLevel] be spoken when the configured level is [level]?
     * Level 0 (SILENT) never speaks regardless of minLevel.
     */
    fun shouldSpeak(level: Int, minLevel: Int): Boolean =
        level in 1..Verbosity.MAX && level >= minLevel

    /** Decide whether a message gated at [minLevel] passes for [level]. Pure, testable. */
    fun decide(minLevel: Int, level: Int): SpeakDecision =
        SpeakDecision(spoken = shouldSpeak(level, minLevel), level = level, minLevel = minLevel)

    /**
     * Gate [speaker] at [minLevel]. Returns the [SpeakDecision]. If the message is allowed
     * (level >= minLevel, level > 0), invokes [speaker] (the actual speech call) once.
     */
    fun speak(minLevel: Int, level: Int, speaker: () -> Unit): SpeakDecision {
        val decision = decide(minLevel, level)
        if (decision.spoken) speaker()
        return decision
    }
}
