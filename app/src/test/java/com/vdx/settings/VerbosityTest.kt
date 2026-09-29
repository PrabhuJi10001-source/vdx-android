package com.vdx.settings

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** JVM tests for the accessibility verbosity dial (pure logic — no Android calls). */
class VerbosityTest {

    @Test
    fun `clamp keeps values within 0 to 10`() {
        assertEquals(0, Verbosity.clamp(-5))
        assertEquals(0, Verbosity.clamp(0))
        assertEquals(5, Verbosity.clamp(5))
        assertEquals(10, Verbosity.clamp(10))
        assertEquals(10, Verbosity.clamp(99))
    }

    @Test
    fun `default level is 5`() {
        assertEquals(5, Verbosity.DEFAULT)
    }

    @Test
    fun `caption is non-empty for every level 0 to 10`() {
        for (level in 0..10) {
            assertTrue("caption for level $level must be non-blank", Verbosity.caption(level).isNotBlank())
        }
    }

    @Test
    fun `caption clamps out-of-range levels`() {
        // caption() itself clamps, so extreme inputs still return a valid band label.
        assertTrue(Verbosity.caption(-3).isNotBlank())
        assertTrue(Verbosity.caption(15).isNotBlank())
    }

    @Test
    fun `filter is silent at level 0 regardless of min level`() {
        // Even error/confirmation messages (minLevel 1) are NOT spoken at SILENT.
        assertFalse(VerbosityFilter.shouldSpeak(level = 0, minLevel = Verbosity.MIN_ERROR))
        assertFalse(VerbosityFilter.shouldSpeak(level = 0, minLevel = Verbosity.MIN_SCREEN))
    }

    @Test
    fun `filter gates below the message min level`() {
        // Level 1 speaks errors/confirmations but not success chatter (min 3).
        assertTrue(VerbosityFilter.shouldSpeak(level = 1, minLevel = Verbosity.MIN_ERROR))
        assertFalse(VerbosityFilter.shouldSpeak(level = 1, minLevel = Verbosity.MIN_SUCCESS))
    }

    @Test
    fun `decision captures the spoken flag and levels`() {
        val allowed = VerbosityFilter.decide(minLevel = Verbosity.MIN_SUCCESS, level = 5)
        assertTrue(allowed.spoken)
        assertEquals(5, allowed.level)
        assertEquals(Verbosity.MIN_SUCCESS, allowed.minLevel)

        val denied = VerbosityFilter.decide(minLevel = Verbosity.MIN_SCREEN, level = 5)
        assertFalse(denied.spoken)
        assertEquals(5, denied.level)
        assertEquals(Verbosity.MIN_SCREEN, denied.minLevel)
    }

    @Test
    fun `speak invokes the function reference only when allowed`() {
        var calls = 0
        val invoked = VerbosityFilter.speak(minLevel = Verbosity.MIN_ERROR, level = 1) { calls++ }
        assertTrue(invoked.spoken)
        assertEquals(1, calls)

        // At level 0 the speaker must not be called.
        calls = 0
        val silent = VerbosityFilter.speak(minLevel = Verbosity.MIN_ERROR, level = 0) { calls++ }
        assertFalse(silent.spoken)
        assertEquals(0, calls)
    }
}
