package com.vdx.sonic.voice

/**
 * WakeWordDetector — activation-phrase spotting for hands-free, zero-UI interaction.
 *
 * Detects the activation phrase ("Hey Vision" / "Hey VDX") in a spoken transcript
 * and strips it so the remainder can be parsed as the actual command. Works on both
 * full results and partial transcripts, so the service can start listening for the
 * command the moment the wake word is heard.
 *
 * Design notes:
 *  - Rule-based (no local model download — cloud-only policy). Fast, deterministic,
 *    and testable in a headless harness.
 *  - Tolerant of ASR variance: "hey vision", "hey vdx", "hey v d x", "hey vdx" all match.
 *  - Returns the command remainder so the caller can feed it straight to IntentParser.
 */
object WakeWordDetector {

    /** Canonical activation phrases, normalized to lowercase. */
    private val ACTIVATION_PHRASES = listOf(
        "hey vision",
        "hey vdx",
        "hey v d x",
        "okay vision",
        "ok vision",
        "vision"
    )

    /** Phrases that are pure activation with no command attached. */
    private val PURE_ACTIVATION = listOf(
        "hey vision",
        "hey vdx",
        "hey v d x",
        "okay vision",
        "ok vision",
        "vision"
    )

    /**
     * Result of wake-word detection on a transcript.
     *
     * @property activated true if the activation phrase was present.
     * @property command the transcript with the activation phrase stripped (may be blank
     *                   if the user only said the wake word).
     * @property pureActivation true if the transcript was ONLY the wake word (no command).
     */
    data class Detection(
        val activated: Boolean,
        val command: String,
        val pureActivation: Boolean
    )

    /**
     * Detect the activation phrase in [rawText].
     *
     * @param rawText the ASR transcript (full or partial).
     * @return a [Detection] describing whether the wake word fired and the command remainder.
     */
    fun detect(rawText: String): Detection {
        val text = rawText.trim()
        if (text.isBlank()) {
            return Detection(activated = false, command = "", pureActivation = false)
        }

        val normalized = text.lowercase()
            .replace(Regex("""[.,!?]+$"""), "")
            .trim()

        // Pure activation: the whole utterance is just the wake word.
        for (phrase in PURE_ACTIVATION) {
            if (normalized == phrase) {
                return Detection(activated = true, command = "", pureActivation = true)
            }
        }

        // Activation phrase followed by a command: strip the phrase, keep the rest.
        for (phrase in ACTIVATION_PHRASES) {
            if (normalized.startsWith("$phrase ")) {
                val command = text.substring(phrase.length).trim()
                return Detection(activated = true, command = command, pureActivation = false)
            }
        }

        return Detection(activated = false, command = text, pureActivation = false)
    }

    /**
     * True if the transcript contains the activation phrase anywhere (not just at the start).
     * Useful for partial transcripts where ASR may not have aligned the phrase to the start yet.
     */
    fun containsActivation(rawText: String): Boolean {
        val normalized = rawText.lowercase()
        return ACTIVATION_PHRASES.any { normalized.contains(it) }
    }
}
