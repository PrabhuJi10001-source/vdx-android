package com.vdx.sonic.voice

import org.junit.Assert.*
import org.junit.Test

/**
 * Unit tests for PromptTemplate — the XFin-adapted template engine.
 *
 * Verifies placeholder substitution, unknown-placeholder preservation, and
 * the no-variable fast path.
 */
class PromptTemplateTest {

    @Test
    fun render_substitutesSingleVariable() {
        val out = PromptTemplate.render(
            PromptTemplate.CONFIRMATION_TAP,
            mapOf("target" to "Delete")
        )
        assertEquals("Tap Delete? Say yes to confirm.", out)
    }

    @Test
    fun render_substitutesMultipleVariables() {
        val out = PromptTemplate.render(
            PromptTemplate.CONFIRMATION_ACTION,
            mapOf("action" to "send", "target" to "Mom")
        )
        assertEquals("send Mom? Say yes to confirm.", out)
    }

    @Test
    fun render_noVariables_returnsTemplateUnchanged() {
        val out = PromptTemplate.render(PromptTemplate.CANCELLED)
        assertEquals("Cancelled.", out)
    }

    @Test
    fun render_emptyVariablesMap_returnsTemplateUnchanged() {
        val out = PromptTemplate.render(PromptTemplate.SUCCESS_TAPPED, emptyMap())
        assertEquals("Tapped.", out)
    }

    @Test
    fun render_unknownPlaceholderLeftAsIs() {
        val out = PromptTemplate.render(
            "Hello {{name}}, tap {{target}}?",
            mapOf("target" to "OK")
        )
        assertEquals("Hello {{name}}, tap OK?", out)
    }

    @Test
    fun render_whitespaceInPlaceholderIsTolerated() {
        val out = PromptTemplate.render("Hi {{  name  }}!", mapOf("name" to "Sam"))
        assertEquals("Hi Sam!", out)
    }

    @Test
    fun render_errorGenericWithReason() {
        val out = PromptTemplate.render(
            PromptTemplate.ERROR_GENERIC,
            mapOf("reason" to "timeout")
        )
        assertEquals("Action failed: timeout.", out)
    }

    @Test
    fun render_noteConfirmWithBody() {
        val out = PromptTemplate.render(
            PromptTemplate.NOTE_CONFIRM,
            mapOf("body" to "buy milk")
        )
        assertEquals("Save this note? \"buy milk\"", out)
    }

    @Test
    fun render_scrollWithDirection() {
        val out = PromptTemplate.render(
            PromptTemplate.SUCCESS_SCROLL,
            mapOf("direction" to "down")
        )
        assertEquals("Scrolled down.", out)
    }

    @Test
    fun constants_areNonBlank() {
        // Smoke check: every declared template constant is non-blank.
        assertTrue(PromptTemplate.CONFIRMATION_TAP.isNotBlank())
        assertTrue(PromptTemplate.CONFIRMATION_ACTION.isNotBlank())
        assertTrue(PromptTemplate.CONFIRMATION_GENERIC.isNotBlank())
        assertTrue(PromptTemplate.CANCELLED.isNotBlank())
        assertTrue(PromptTemplate.NOTE_CANCELLED.isNotBlank())
        assertTrue(PromptTemplate.NOTE_CONFIRM.isNotBlank())
        assertTrue(PromptTemplate.ERROR_NOT_FOUND.isNotBlank())
        assertTrue(PromptTemplate.ERROR_CLICK.isNotBlank())
        assertTrue(PromptTemplate.ERROR_NO_SCROLL.isNotBlank())
        assertTrue(PromptTemplate.ERROR_SCROLL.isNotBlank())
        assertTrue(PromptTemplate.ERROR_NO_FIELD.isNotBlank())
        assertTrue(PromptTemplate.ERROR_NOT_EDITABLE.isNotBlank())
        assertTrue(PromptTemplate.ERROR_SET_TEXT.isNotBlank())
        assertTrue(PromptTemplate.ERROR_TAP.isNotBlank())
        assertTrue(PromptTemplate.ERROR_GLOBAL.isNotBlank())
        assertTrue(PromptTemplate.ERROR_GENERIC.isNotBlank())
        assertTrue(PromptTemplate.SUCCESS_TAP.isNotBlank())
        assertTrue(PromptTemplate.SUCCESS_TAPPED.isNotBlank())
        assertTrue(PromptTemplate.SUCCESS_SCROLL.isNotBlank())
        assertTrue(PromptTemplate.SUCCESS_TEXT_ENTERED.isNotBlank())
        assertTrue(PromptTemplate.SUCCESS_BACK.isNotBlank())
        assertTrue(PromptTemplate.SUCCESS_HOME.isNotBlank())
        assertTrue(PromptTemplate.WELCOME.isNotBlank())
        assertTrue(PromptTemplate.NO_AUDIO.isNotBlank())
        assertTrue(PromptTemplate.DIDNT_CATCH.isNotBlank())
        assertTrue(PromptTemplate.CANT_DO_BY_VOICE.isNotBlank())
        assertTrue(PromptTemplate.WHAT_NEXT.isNotBlank())
    }

    // ── Locale-aware rendering tests ──────────────────────────────

    @Test
    fun locale_englishIsDefault() {
        assertEquals("en", PromptTemplate.getLocale())
    }

    @Test
    fun locale_setLocaleToHindi() {
        try {
            PromptTemplate.setLocale("hi")
            assertEquals("hi", PromptTemplate.getLocale())
        } finally {
            PromptTemplate.setLocale("en")
        }
    }

    @Test
    fun locale_setLocaleToHinglish() {
        try {
            PromptTemplate.setLocale("hinglish")
            assertEquals("hinglish", PromptTemplate.getLocale())
        } finally {
            PromptTemplate.setLocale("en")
        }
    }

    @Test
    fun locale_setLocaleNormalizesHindiInput() {
        try {
            PromptTemplate.setLocale("Hindi")
            assertEquals("hi", PromptTemplate.getLocale())
        } finally {
            PromptTemplate.setLocale("en")
        }
    }

    @Test
    fun locale_renderHindiCancelled() {
        val out = PromptTemplate.render(PromptTemplate.CANCELLED, emptyMap(), "hi")
        assertEquals("रद्द किया गया।", out)
    }

    @Test
    fun locale_renderHinglishCancelled() {
        val out = PromptTemplate.render(PromptTemplate.CANCELLED, emptyMap(), "hinglish")
        assertEquals("Cancel kar diya.", out)
    }

    @Test
    fun locale_renderHindiDidntCatch() {
        val out = PromptTemplate.render(PromptTemplate.DIDNT_CATCH, emptyMap(), "hi")
        assertEquals("समझ नहीं आया। फिर से बोलें।", out)
    }

    @Test
    fun locale_renderHinglishDidntCatch() {
        val out = PromptTemplate.render(PromptTemplate.DIDNT_CATCH, emptyMap(), "hinglish")
        assertEquals("Samajh nahi aaya. Phir se bolo?", out)
    }

    @Test
    fun locale_renderHindiConfirmationTap() {
        val out = PromptTemplate.render(
            PromptTemplate.CONFIRMATION_TAP,
            mapOf("target" to "Delete"),
            "hi"
        )
        assertEquals("Delete टैप करें? पुष्टि के लिए हां बोलें।", out)
    }

    @Test
    fun locale_renderHinglishConfirmationTap() {
        val out = PromptTemplate.render(
            PromptTemplate.CONFIRMATION_TAP,
            mapOf("target" to "Delete"),
            "hinglish"
        )
        assertEquals("Delete tap karun? Haan bolo to confirm.", out)
    }

    @Test
    fun locale_fallsBackToEnglishWhenMissing() {
        // Use a locale that has no templates — should fall back to English.
        val out = PromptTemplate.render(PromptTemplate.CANCELLED, emptyMap(), "fr")
        assertEquals("Cancelled.", out)
    }

    @Test
    fun locale_activeLocaleUsedInNoLocaleOverload() {
        try {
            PromptTemplate.setLocale("hi")
            val out = PromptTemplate.render(PromptTemplate.CANCELLED)
            assertEquals("रद्द किया गया।", out)
        } finally {
            PromptTemplate.setLocale("en")
        }
    }

    @Test
    fun locale_ttsLocaleForHindi() {
        try {
            PromptTemplate.setLocale("hi")
            assertEquals(java.util.Locale("hi", "IN"), PromptTemplate.ttsLocale())
        } finally {
            PromptTemplate.setLocale("en")
        }
    }

    @Test
    fun locale_ttsLocaleForEnglish() {
        assertEquals(java.util.Locale.US, PromptTemplate.ttsLocale())
    }

    @Test
    fun locale_ttsLocaleForHinglish() {
        try {
            PromptTemplate.setLocale("hinglish")
            assertEquals(java.util.Locale.US, PromptTemplate.ttsLocale())
        } finally {
            PromptTemplate.setLocale("en")
        }
    }

    @Test
    fun locale_sttLanguageTagForHindi() {
        assertEquals("hi-IN", PromptTemplate.sttLanguageTag("hi"))
    }

    @Test
    fun locale_sttLanguageTagForHinglish() {
        assertEquals("en-IN", PromptTemplate.sttLanguageTag("hinglish"))
    }

    @Test
    fun locale_sttLanguageTagForEnglish() {
        assertEquals("en-US", PromptTemplate.sttLanguageTag("en"))
    }

    @Test
    fun onboardingTemplatesExistInAllLocales() {
        // Verify onboarding templates are non-blank in all three locales.
        for (locale in listOf("en", "hi", "hinglish")) {
            assertTrue(PromptTemplate.render(PromptTemplate.ONBOARDING_WELCOME, emptyMap(), locale).isNotBlank())
            assertTrue(PromptTemplate.render(PromptTemplate.ONBOARDING_LANGUAGE, emptyMap(), locale).isNotBlank())
            assertTrue(PromptTemplate.render(PromptTemplate.ONBOARDING_NAME, emptyMap(), locale).isNotBlank())
            assertTrue(PromptTemplate.render(PromptTemplate.ONBOARDING_CAPABILITIES, emptyMap(), locale).isNotBlank())
            assertTrue(PromptTemplate.render(PromptTemplate.ONBOARDING_DONE, emptyMap(), locale).isNotBlank())
        }
    }
}