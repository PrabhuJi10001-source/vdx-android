package com.vdx.settings

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey

/**
 * KeyVault — BYOK API key storage for VDX.
 *
 * Stores AI provider keys in [EncryptedSharedPreferences] backed by the Android
 * Keystore (MasterKey AES256_GCM), so keys are encrypted at rest and the raw
 * value never lands in a plain SharedPreferences file. One class, no DI, no
 * settings library (ponytail rule).
 *
 * Supported keys:
 *  - Gemini -> vdx_llm_api_key
 *  - OpenAI -> vdx_openai_api_key
 *  - Groq  -> vdx_groq_api_key
 *  - Sarvam -> vdx_sarvam_api_key
 *
 * Migration: at app start, call [migrateFromLegacy]. Any key found in the OLD
 * plain `vdx_prefs` SharedPreferences (including the historical alternate names
 * `groq_api_key` / `openai_api_key`) is copied into the vault and removed from
 * the plain prefs. After migration the plain prefs only ever serve as a one-time
 * source; they never hold keys going forward.
 *
 * Security rules honoured: keys are never logged, never exposed via BuildConfig,
 * and never exported. Only the short provider name (e.g. "Gemini") is ever logged.
 */
object KeyVault {
    private const val TAG = "KeyVault"

    /** Encrypted prefs file name. Plain SharedPreferences with the same name won't exist. */
    private const val VAULT_FILE = "vdx_vault"

    // Canonical key names (public so callers can reference them without literals).
    const val GEMINI = "vdx_llm_api_key"
    const val OPENAI = "vdx_openai_api_key"
    const val GROQ = "vdx_groq_api_key"
    const val SARVAM = "vdx_sarvam_api_key"

    /** All provider keys handled by the vault. */
    val ALL_KEYS: List<String> = listOf(GEMINI, OPENAI, GROQ, SARVAM)

    /**
     * Legacy alternate names that may still hold keys in the plain `vdx_prefs`
     * file from an older flow. These are migration sources only, never reads.
     */
    private val LEGACY_ALIASES = mapOf(
        GEMINI to listOf(GEMINI),
        OPENAI to listOf(OPENAI, "openai_api_key"),
        GROQ to listOf(GROQ, "groq_api_key"),
        SARVAM to listOf(SARVAM)
    )

    private const val LEGACY_PREFS = "vdx_prefs"

    @Volatile
    private var vault: SharedPreferences? = null

    /**
     * Lazily obtain the encrypted prefs instance. Creating the [MasterKey] /
     * [EncryptedSharedPreferences] is relatively expensive, so the instance is
     * cached for process lifetime.
     */
    fun instance(context: Context): SharedPreferences? {
        vault?.let { return it }
        synchronized(this) {
            vault?.let { return it }
            val inst = try {
                buildVault(context)
            } catch (e: Throwable) {
                // Keystore may be genuinely unavailable (unlikely on real devices,
                // common in pure-JVM unit tests). Never fail loudly and never fall
                // back to plain storage — that would defeat the point of a vault.
                Log.e(TAG, "EncryptedSharedPreferences unavailable", e)
                null
            }
            vault = inst
            return inst
        }
    }

    /** True when the vault is usable. Callers should guard writes on this. */
    fun isAvailable(context: Context): Boolean = instance(context) != null

    /** Read a key from the vault. Returns null if absent, unavailable, or empty. */
    fun get(context: Context, key: String): String? {
        val v = vaultOrNull(context) ?: return null
        return v.getString(key, null)?.takeIf { it.isNotBlank() }
    }

    /** Store a key in the vault. No-op if the vault is unavailable. */
    fun put(context: Context, key: String, value: String?) {
        val v = vaultOrNull(context) ?: return
        if (value.isNullOrBlank()) {
            v.edit().remove(key).apply()
        } else {
            v.edit().putString(key, value).apply()
        }
    }

    /**
     * One-time migration from the legacy plain `vdx_prefs` file. For each provider
     * key, if it exists in the plain prefs, copy into the vault and remove the
     * plain value (along with any alias the value was found under). Safe to call
     * every app start: it is idempotent.
     */
    fun migrateFromLegacy(context: Context) {
        val v = vaultOrNull(context) ?: return
        val legacy = context.getSharedPreferences(LEGACY_PREFS, Context.MODE_PRIVATE)
        var migrated = 0
        for ((canonical, aliases) in LEGACY_ALIASES) {
            var value: String? = null
            for (alias in aliases) {
                value = legacy.getString(alias, null)
                if (!value.isNullOrBlank()) break
            }
            if (value.isNullOrBlank()) continue
            // Copy into vault, then clear from plain prefs regardless of which
            // alias it was found under.
            if (v.contains(canonical).not()) {
                v.edit().putString(canonical, value).apply()
            }
            legacy.edit().apply {
                for (alias in aliases) remove(alias)
            }.apply()
            migrated++
            Log.i(TAG, "Migrated ${provinceName(canonical)} API key into vault")
        }
        if (migrated > 0) Log.i(TAG, "KeyVault migration complete: $migrated key(s)")
    }

    private fun vaultOrNull(context: Context): SharedPreferences? = instance(context)

    private fun buildVault(context: Context): SharedPreferences {
        val masterKey = MasterKey.Builder(context, "vdx_vault_master")
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()
        return EncryptedSharedPreferences.create(
            context,
            VAULT_FILE,
            masterKey,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
        )
    }

    /** Short, safe label for logging — never the key itself. */
    private fun provinceName(key: String): String = when (key) {
        GEMINI -> "Gemini"
        OPENAI -> "OpenAI"
        GROQ -> "Groq"
        SARVAM -> "Sarvam"
        else -> key
    }
}
