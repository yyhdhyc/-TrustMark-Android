package com.trustmarktool.data

import android.content.Context
import com.trustmarktool.model.GenerateMode

/**
 * Small, synchronous settings store for the generate page. SharedPreferences is
 * deliberate here: the app needs the saved text before the first Compose frame,
 * and there is no extra dependency or migration to maintain.
 */
class GeneratePreferences(context: Context) {

    private val prefs = context.applicationContext.getSharedPreferences(
        FILE_NAME,
        Context.MODE_PRIVATE,
    )

    data class Snapshot(
        val text: String,
        val batchId: String?,
        val mode: GenerateMode,
        val strength: Float,
    )

    fun load(): Snapshot {
        val text = if (prefs.contains(KEY_TEXT)) {
            prefs.getString(KEY_TEXT, "") ?: ""
        } else {
            DEFAULT_TEXT
        }
        val batchId = prefs.getString(KEY_BATCH_ID, null)?.takeIf { it.isNotBlank() }
        val mode = when (prefs.getString(KEY_MODE, null)) {
            GenerateMode.BATCH.name -> GenerateMode.BATCH
            else -> GenerateMode.UNIVERSAL
        }
        // Strength applies to both generate modes since v1.2.2; values written
        // by older versions are absent and fall back to the shared default.
        val strength = prefs.getFloat(KEY_STRENGTH, DEFAULT_STRENGTH)
            .coerceIn(MIN_STRENGTH, MAX_STRENGTH)
        return Snapshot(text = text, batchId = batchId, mode = mode, strength = strength)
    }

    fun saveText(value: String) {
        prefs.edit().putString(KEY_TEXT, value).apply()
    }

    fun saveBatch(batchId: String?) {
        prefs.edit().apply {
            if (batchId.isNullOrBlank()) remove(KEY_BATCH_ID) else putString(KEY_BATCH_ID, batchId)
        }.apply()
    }

    fun saveMode(mode: GenerateMode) {
        prefs.edit().putString(KEY_MODE, mode.name).apply()
    }

    fun saveStrength(value: Float) {
        prefs.edit().putFloat(KEY_STRENGTH, value).apply()
    }

    private companion object {
        const val FILE_NAME = "generate_preferences"
        const val KEY_TEXT = "fixed_text"
        const val KEY_BATCH_ID = "selected_batch_id"
        const val KEY_MODE = "generate_mode"
        const val KEY_STRENGTH = "embed_strength"
        const val DEFAULT_TEXT = "ACME"
        const val DEFAULT_STRENGTH = 1.3f
        const val MIN_STRENGTH = 0.5f
        const val MAX_STRENGTH = 2.0f
    }
}
