package com.vdx.memory

import android.content.Context

/**
 * Stub for optional on-device Nano LLM. Real availability is device-dependent.
 * Tests only verify this API does not crash.
 */
class NanoFallback(private val context: Context) {
    fun isAvailable(): Boolean = false

    suspend fun generate(prompt: String): String? = null
}
