package com.vdx.sonic.voice

import java.util.Locale

/**
 * PromptTemplate — locale-aware voice prompt template engine.
 *
 * Voice-first XFin pattern: prompts are string constants with
 * `{{variable}}` placeholders, rendered at call time with a variables map.
 *
 * Three locales are supported: `en` (English), `hi` (Hindi), `hinglish`
 * (Hindi+English code-switching). Templates are stored in a registry keyed
 * by locale → template name → template string. When a template is missing
 * for the requested locale, the English fallback is used.
 *
 * Centralising every spoken prompt here gives:
 *  - one source of truth for the assistant's voice (tone, wording)
 *  - i18n / locale swap of any phrase without touching call sites
 *  - no reflection, no format-string parsing surprises — a single regex
 *    pass over the template replaces every `{{key}}` with the matching value.
 */
object PromptTemplate {

    private val PLACEHOLDER = Regex("""\{\{\s*([a-zA-Z_][a-zA-Z0-9_]*)\s*\}\}""")

    // ──────────────────────────────────────────────────────────────
    // Locale keys
    // ──────────────────────────────────────────────────────────────

    const val LOCALE_EN = "en"
    const val LOCALE_HI = "hi"
    const val LOCALE_HINGLISH = "hinglish"

    // ──────────────────────────────────────────────────────────────
    // Template name constants (used as registry keys by call sites)
    // ──────────────────────────────────────────────────────────────

    // Confirmation prompts
    const val CONFIRMATION_TAP = "CONFIRMATION_TAP"
    const val CONFIRMATION_ACTION = "CONFIRMATION_ACTION"
    const val CONFIRMATION_GENERIC = "CONFIRMATION_GENERIC"

    // Cancellation prompts
    const val CANCELLED = "CANCELLED"
    const val NOTE_CANCELLED = "NOTE_CANCELLED"
    const val NOTE_CONFIRM = "NOTE_CONFIRM"

    // Error prompts
    const val ERROR_NOT_FOUND = "ERROR_NOT_FOUND"
    const val ERROR_CLICK = "ERROR_CLICK"
    const val ERROR_NO_SCROLL = "ERROR_NO_SCROLL"
    const val ERROR_SCROLL = "ERROR_SCROLL"
    const val ERROR_NO_FIELD = "ERROR_NO_FIELD"
    const val ERROR_NOT_EDITABLE = "ERROR_NOT_EDITABLE"
    const val ERROR_SET_TEXT = "ERROR_SET_TEXT"
    const val ERROR_TAP = "ERROR_TAP"
    const val ERROR_GLOBAL = "ERROR_GLOBAL"
    const val ERROR_GENERIC = "ERROR_GENERIC"

    // Success prompts
    const val SUCCESS_TAP = "SUCCESS_TAP"
    const val SUCCESS_TAPPED = "SUCCESS_TAPPED"
    const val SUCCESS_SCROLL = "SUCCESS_SCROLL"
    const val SUCCESS_TEXT_ENTERED = "SUCCESS_TEXT_ENTERED"
    const val SUCCESS_BACK = "SUCCESS_BACK"
    const val SUCCESS_HOME = "SUCCESS_HOME"

    // Welcome / listening prompts
    const val WELCOME = "WELCOME"
    const val NO_AUDIO = "NO_AUDIO"
    const val DIDNT_CATCH = "DIDNT_CATCH"
    const val CANT_DO_BY_VOICE = "CANT_DO_BY_VOICE"
    const val WHAT_NEXT = "WHAT_NEXT"

    // Onboarding prompts (Task 3)
    const val ONBOARDING_WELCOME = "ONBOARDING_WELCOME"
    const val ONBOARDING_LANGUAGE = "ONBOARDING_LANGUAGE"
    const val ONBOARDING_NAME = "ONBOARDING_NAME"
    const val ONBOARDING_CAPABILITIES = "ONBOARDING_CAPABILITIES"
    const val ONBOARDING_DONE = "ONBOARDING_DONE"

    // ──────────────────────────────────────────────────────────────
    // Template registry: locale → templateName → template string
    // ──────────────────────────────────────────────────────────────

    /** English templates — the source of truth and fallback. */
    private val enTemplates: Map<String, String> = mapOf(
        CONFIRMATION_TAP to "Tap {{target}}? Say yes to confirm.",
        CONFIRMATION_ACTION to "{{action}} {{target}}? Say yes to confirm.",
        CONFIRMATION_GENERIC to "Are you sure you want to {{action}}? Say yes to confirm.",
        CANCELLED to "Cancelled.",
        NOTE_CANCELLED to "Note cancelled.",
        NOTE_CONFIRM to "Save this note? \"{{body}}\"",
        ERROR_NOT_FOUND to "Could not find element on screen.",
        ERROR_CLICK to "Could not click element.",
        ERROR_NO_SCROLL to "No scrollable area on screen.",
        ERROR_SCROLL to "Could not scroll {{direction}}.",
        ERROR_NO_FIELD to "Could not find text field.",
        ERROR_NOT_EDITABLE to "Element is not editable.",
        ERROR_SET_TEXT to "Could not enter text.",
        ERROR_TAP to "Could not tap screen.",
        ERROR_GLOBAL to "Action not supported.",
        ERROR_GENERIC to "Action failed: {{reason}}.",
        SUCCESS_TAP to "Tapped {{target}}.",
        SUCCESS_TAPPED to "Tapped.",
        SUCCESS_SCROLL to "Scrolled {{direction}}.",
        SUCCESS_TEXT_ENTERED to "Text entered.",
        SUCCESS_BACK to "Went back.",
        SUCCESS_HOME to "Went home.",
        WELCOME to "VDX Sonic ready. Tap the bubble and speak.",
        NO_AUDIO to "No audio captured. Tap the bubble, speak, then tap again.",
        DIDNT_CATCH to "I didn't catch that. Could you say it again?",
        CANT_DO_BY_VOICE to "I can't do that by voice. Please do it directly on screen.",
        WHAT_NEXT to "What would you like me to do?",
        ONBOARDING_WELCOME to "Welcome to VDX. I'll help you use your phone by voice. Let's set up a few things.",
        ONBOARDING_LANGUAGE to "What language do you prefer? Hindi, English, or Hinglish?",
        ONBOARDING_NAME to "What's your name?",
        ONBOARDING_CAPABILITIES to "Do you want me to help you make calls, send messages, and book rides? Say yes to confirm.",
        ONBOARDING_DONE to "Tap the floating bubble anytime and speak. Try saying 'call mom' or 'open WhatsApp'."
    )

    /** Hindi templates. */
    private val hiTemplates: Map<String, String> = mapOf(
        CONFIRMATION_TAP to "{{target}} टैप करें? पुष्टि के लिए हां बोलें।",
        CONFIRMATION_ACTION to "{{action}} {{target}}? पुष्टि के लिए हां बोलें।",
        CONFIRMATION_GENERIC to "क्या आप वाकई {{action}} चाहते हैं? पुष्टि के लिए हां बोलें।",
        CANCELLED to "रद्द किया गया।",
        NOTE_CANCELLED to "नोट रद्द किया गया।",
        NOTE_CONFIRM to "यह नोट सेव करें? \"{{body}}\"",
        ERROR_NOT_FOUND to "स्क्रीन पर एलिमेंट नहीं मिला।",
        ERROR_CLICK to "एलिमेंट पर क्लिक नहीं हो सका।",
        ERROR_NO_SCROLL to "स्क्रीन पर कोई स्क्रॉल करने योग्य क्षेत्र नहीं है।",
        ERROR_SCROLL to "{{direction}} दिशा में स्क्रॉल नहीं हो सका।",
        ERROR_NO_FIELD to "टेक्स्ट फील्ड नहीं मिला।",
        ERROR_NOT_EDITABLE to "एलिमेंट एडिट करने योग्य नहीं है।",
        ERROR_SET_TEXT to "टेक्स्ट दर्ज नहीं हो सका।",
        ERROR_TAP to "स्क्रीन पर टैप नहीं हो सका।",
        ERROR_GLOBAL to "एक्शन समर्थित नहीं है।",
        ERROR_GENERIC to "एक्शन विफल: {{reason}}।",
        SUCCESS_TAP to "{{target}} टैप किया।",
        SUCCESS_TAPPED to "टैप किया।",
        SUCCESS_SCROLL to "{{direction}} दिशा में स्क्रॉल किया।",
        SUCCESS_TEXT_ENTERED to "टेक्स्ट दर्ज हुआ।",
        SUCCESS_BACK to "वापस गए।",
        SUCCESS_HOME to "होम पर गए।",
        WELCOME to "VDX Sonic तैयार है। बबल पर टैप करें और बोलें।",
        NO_AUDIO to "ऑडियो कैप्चर नहीं हुआ। बबल पर टैप करें, बोलें, फिर दोबारा टैप करें।",
        DIDNT_CATCH to "समझ नहीं आया। फिर से बोलें।",
        CANT_DO_BY_VOICE to "मैं यह वॉइस से नहीं कर सकता। कृपया स्क्रीन पर सीधे करें।",
        WHAT_NEXT to "आप मुझसे क्या करवाना चाहते हैं?",
        ONBOARDING_WELCOME to "VDX में आपका स्वागत है। मैं आपको वॉइस से फोन इस्तेमाल करने में मदद करूंगा। चलिए कुछ चीजें सेट करते हैं।",
        ONBOARDING_LANGUAGE to "आपकी पसंद की भाषा क्या है? हिंदी, अंग्रेजी, या हिंग्लिश?",
        ONBOARDING_NAME to "आपका नाम क्या है?",
        ONBOARDING_CAPABILITIES to "क्या आप चाहते हैं कि मैं कॉल करने, मैसेज भेजने, और राइड बुक करने में मदद करूं? पुष्टि के लिए हां बोलें।",
        ONBOARDING_DONE to "कभी भी फ्लोटिंग बबल पर टैप करें और बोलें। 'कॉल मॉम' या 'ओपन व्हाट्सएप' बोलकर आज़माएं।"
    )

    /** Hinglish templates (Hindi+English code-switching). */
    private val hinglishTemplates: Map<String, String> = mapOf(
        CONFIRMATION_TAP to "{{target}} tap karun? Haan bolo to confirm.",
        CONFIRMATION_ACTION to "{{action}} {{target}}? Haan bolo to confirm.",
        CONFIRMATION_GENERIC to "Kya aap waakai {{action}} chahte ho? Haan bolo to confirm.",
        CANCELLED to "Cancel kar diya.",
        NOTE_CANCELLED to "Note cancel kar diya.",
        NOTE_CONFIRM to "Yeh note save karun? \"{{body}}\"",
        ERROR_NOT_FOUND to "Screen pe element nahi mila.",
        ERROR_CLICK to "Element pe click nahi hua.",
        ERROR_NO_SCROLL to "Screen pe koi scrollable area nahi hai.",
        ERROR_SCROLL to "{{direction}} direction me scroll nahi hua.",
        ERROR_NO_FIELD to "Text field nahi mila.",
        ERROR_NOT_EDITABLE to "Element editable nahi hai.",
        ERROR_SET_TEXT to "Text enter nahi hua.",
        ERROR_TAP to "Screen pe tap nahi hua.",
        ERROR_GLOBAL to "Action supported nahi hai.",
        ERROR_GENERIC to "Action fail hua: {{reason}}.",
        SUCCESS_TAP to "{{target}} tap kiya.",
        SUCCESS_TAPPED to "Tap kiya.",
        SUCCESS_SCROLL to "{{direction}} direction me scroll kiya.",
        SUCCESS_TEXT_ENTERED to "Text enter ho gaya.",
        SUCCESS_BACK to "Wapas gaye.",
        SUCCESS_HOME to "Home pe gaye.",
        WELCOME to "VDX Sonic ready hai. Bubble pe tap karo aur bolo.",
        NO_AUDIO to "Audio capture nahi hua. Bubble pe tap karo, bolo, phir dobara tap karo.",
        DIDNT_CATCH to "Samajh nahi aaya. Phir se bolo?",
        CANT_DO_BY_VOICE to "Main yeh voice se nahi kar sakta. Screen pe directly karo.",
        WHAT_NEXT to "Aap mujhse kya karwana chahte ho?",
        ONBOARDING_WELCOME to "VDX me aapka swagat hai. Main aapko voice se phone use karne me help karunga. Chaliye kuch cheezein set karte hain.",
        ONBOARDING_LANGUAGE to "Aapki pasand ki language kya hai? Hindi, English, ya Hinglish?",
        ONBOARDING_NAME to "Aapka naam kya hai?",
        ONBOARDING_CAPABILITIES to "Kya aap chahte ho main calls karne, messages bhejne, aur rides book karne me help karu? Haan bolo to confirm.",
        ONBOARDING_DONE to "Floating bubble pe kabhi bhi tap karo aur bolo. 'call mom' ya 'open WhatsApp' bolke try karo."
    )

    /** Full registry: locale key → (template name → template string). */
    private val registry: Map<String, Map<String, String>> = mapOf(
        LOCALE_EN to enTemplates,
        LOCALE_HI to hiTemplates,
        LOCALE_HINGLISH to hinglishTemplates
    )

    // ──────────────────────────────────────────────────────────────
    // Active locale
    // ──────────────────────────────────────────────────────────────

    @Volatile
    private var activeLocale: String = LOCALE_EN

    /** Set the active locale for the session. Accepts "en", "hi", or "hinglish". */
    fun setLocale(locale: String) {
        activeLocale = when (locale.lowercase().trim()) {
            "hi", "hindi", "hi-in" -> LOCALE_HI
            "hinglish", "hinglish-in" -> LOCALE_HINGLISH
            else -> LOCALE_EN
        }
    }

    /** Get the active locale key. */
    fun getLocale(): String = activeLocale

    /** Map the active locale to a Java Locale for TTS engine initialization. */
    fun ttsLocale(): Locale = when (activeLocale) {
        LOCALE_HI -> Locale("hi", "IN")
        else -> Locale.US
    }

    /** Map a locale string to the SpeechRecognizer language tag. */
    fun sttLanguageTag(locale: String = activeLocale): String = when (locale) {
        LOCALE_HI -> "hi-IN"
        LOCALE_HINGLISH -> "en-IN"
        else -> "en-US"
    }

    // ──────────────────────────────────────────────────────────────
    // Rendering — locale-aware
    // ──────────────────────────────────────────────────────────────

    /**
     * Render a template by key, looking up the locale-specific version.
     * Falls back to English if the template is missing for the requested locale.
     */
    fun render(templateKey: String, variables: Map<String, String>, locale: String): String {
        val template = lookupTemplate(templateKey, locale)
        return renderRaw(template, variables)
    }

    /**
     * Render using the active locale.
     *
     * This single overload handles both cases:
     * - If [templateKey] is a known registry key (e.g. CONFIRMATION_TAP), the
     *   locale-specific template is looked up and rendered.
     * - If [templateKey] is a raw template string (e.g. "Hi {{name}}!"), it is
     *   rendered directly with placeholder substitution.
     * This dual behavior preserves backward compatibility with existing call
     * sites that pass either a constant key or a raw string.
     */
    fun render(templateKey: String, variables: Map<String, String> = emptyMap()): String {
        // If the string is a known template key, use the locale-aware lookup.
        // Otherwise, treat it as a raw template string and substitute directly.
        val fromRegistry = lookupTemplateOrNull(templateKey, activeLocale)
        val raw = fromRegistry ?: templateKey
        return renderRaw(raw, variables)
    }

    /**
     * Look up a template for the given locale, falling back to English
     * if not found in the requested locale.
     */
    private fun lookupTemplate(templateKey: String, locale: String): String {
        return lookupTemplateOrNull(templateKey, locale)
            ?: enTemplates[templateKey]
            ?: templateKey // last resort: return the key itself (visible in output)
    }

    private fun lookupTemplateOrNull(templateKey: String, locale: String): String? {
        return registry[locale]?.get(templateKey)
            ?: registry[activeLocale]?.get(templateKey)
    }

    /**
     * Core rendering: substitute every `{{key}}` placeholder with the
     * matching value from [variables]. Unknown placeholders are left as-is
     * (not stripped) so a missing variable is visible in the spoken output
     * rather than silently elided.
     */
    private fun renderRaw(template: String, variables: Map<String, String>): String {
        if (variables.isEmpty()) return template
        return PLACEHOLDER.replace(template) { m ->
            variables[m.groupValues[1]] ?: m.value
        }
    }
}