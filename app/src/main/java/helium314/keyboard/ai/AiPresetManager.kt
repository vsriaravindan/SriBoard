// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.ai

import android.content.Context
import helium314.keyboard.latin.settings.Settings
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/**
 * Manages AI presets — their prompts, enabled state, and order.
 * Persisted as JSON in SharedPreferences.
 * Automatically included in Heliboard-style backup/restore (all prefs are backed up).
 */
object AiPresetManager {

    private val json = Json { ignoreUnknownKeys = true }

    @Serializable
    data class SerializablePreset(
        val type: String,
        val prompt: String,
        val enabled: Boolean = true,
        val order: Int = 0
    )

    private val defaultPresets = listOf(
        SerializablePreset(
            type = AiPrefs.PresetType.FIX.name,
            prompt = AiPrefs.PresetType.FIX.defaultPrompt,
            enabled = true,
            order = 0
        ),
        SerializablePreset(
            type = AiPrefs.PresetType.TRANSLATE_TAMIL.name,
            prompt = AiPrefs.PresetType.TRANSLATE_TAMIL.defaultPrompt,
            enabled = false,
            order = 1
        ),
        SerializablePreset(
            type = AiPrefs.PresetType.CUSTOM_1.name,
            prompt = "",
            enabled = false,
            order = 2
        ),
        SerializablePreset(
            type = AiPrefs.PresetType.CUSTOM_2.name,
            prompt = "",
            enabled = false,
            order = 3
        ),
        SerializablePreset(
            type = AiPrefs.PresetType.CUSTOM_3.name,
            prompt = "",
            enabled = false,
            order = 4
        ),
        SerializablePreset(
            type = AiPrefs.PresetType.CUSTOM_4.name,
            prompt = "",
            enabled = false,
            order = 5
        ),
        SerializablePreset(
            type = AiPrefs.PresetType.CUSTOM_5.name,
            prompt = "",
            enabled = false,
            order = 6
        )
    )

    fun getPresets(context: Context): List<SerializablePreset> {
        val prefs = context.getSharedPreferences("settings", Context.MODE_PRIVATE)
        val stored = prefs.getString(Settings.PREF_AI_PRESETS_JSON, null) ?: return defaultPresets
        return try {
            val parsed = json.decodeFromString<List<SerializablePreset>>(stored)
            // Ensure all preset types exist (in case new ones added in update)
            val existingTypes = parsed.map { it.type }.toSet()
            val merged = parsed.toMutableList()
            for (default in defaultPresets) {
                if (default.type !in existingTypes) {
                    merged.add(default)
                }
            }
            merged.sortedBy { it.order }
        } catch (_: Exception) {
            defaultPresets
        }
    }

    fun savePresets(context: Context, presets: List<SerializablePreset>) {
        val prefs = context.getSharedPreferences("settings", Context.MODE_PRIVATE)
        prefs.edit().putString(Settings.PREF_AI_PRESETS_JSON, json.encodeToString(presets)).apply()
    }

    fun updatePreset(context: Context, type: AiPrefs.PresetType, prompt: String, enabled: Boolean) {
        val presets = getPresets(context).toMutableList()
        val index = presets.indexOfFirst { it.type == type.name }
        if (index >= 0) {
            presets[index] = presets[index].copy(prompt = prompt, enabled = enabled)
        } else {
            presets.add(SerializablePreset(type.name, prompt, enabled, presets.size))
        }
        savePresets(context, presets)
    }

    fun reorderPresets(context: Context, fromIndex: Int, toIndex: Int) {
        val presets = getPresets(context).toMutableList()
        if (fromIndex < 0 || fromIndex >= presets.size || toIndex < 0 || toIndex >= presets.size) return
        val item = presets.removeAt(fromIndex)
        presets.add(toIndex, item)
        // Update order field
        val reordered = presets.mapIndexed { i, p -> p.copy(order = i) }
        savePresets(context, reordered)
    }

    fun getEnabledPresets(context: Context): List<SerializablePreset> {
        return getPresets(context).filter { it.enabled }
    }
}
