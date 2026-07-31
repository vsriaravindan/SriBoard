// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.ai

import android.content.Context
import helium314.keyboard.latin.settings.Settings
import helium314.keyboard.latin.utils.prefs
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
        val order: Int = 0,
        val name: String = ""   // friendly name; blank = "Custom N" fallback
    )

    // Sriboard v1.5: only CUSTOM_1/2 stay as bare "Custom" slots; CUSTOM_3..5 are
    // pre-filled with frequently used prompts (editable, can be disabled like any other).
    private val defaultPresets = listOf(
        SerializablePreset(
            type = AiPrefs.PresetType.FIX.name,
            prompt = AiPrefs.PresetType.FIX.defaultPrompt,
            enabled = true,
            order = 0,
            name = "Fix (English)"
        ),
        SerializablePreset(
            type = AiPrefs.PresetType.TRANSLATE_TAMIL.name,
            prompt = AiPrefs.PresetType.TRANSLATE_TAMIL.defaultPrompt,
            enabled = false,
            order = 1,
            name = "Translate to Tamil"
        ),
        SerializablePreset(
            type = AiPrefs.PresetType.CUSTOM_1.name,
            prompt = "",
            enabled = false,
            order = 2,
            name = "Custom 1"
        ),
        SerializablePreset(
            type = AiPrefs.PresetType.CUSTOM_2.name,
            prompt = "",
            enabled = false,
            order = 3,
            name = "Custom 2"
        ),
        SerializablePreset(
            type = AiPrefs.PresetType.CUSTOM_3.name,
            prompt = "Rewrite the following text to sound professional and polished. Return ONLY the rewritten text, nothing else.",
            enabled = true,
            order = 4,
            name = "Rewrite professionally"
        ),
        SerializablePreset(
            type = AiPrefs.PresetType.CUSTOM_4.name,
            prompt = "Condense the following text to be shorter and more concise while keeping the original meaning. Return ONLY the condensed text, nothing else.",
            enabled = true,
            order = 5,
            name = "Make it shorter"
        ),
        SerializablePreset(
            type = AiPrefs.PresetType.CUSTOM_5.name,
            prompt = "Translate the following text into English. Return ONLY the translated text, nothing else.",
            enabled = true,
            order = 6,
            name = "Translate to English"
        )
    )

    private const val PREF_AI_PRESETS_SEEDED = "ai_presets_seeded_v2"

    /** Friendly display name: preset name, or "Custom N" fallback for unnamed slots. */
    fun displayName(preset: SerializablePreset): String {
        if (preset.name.isNotBlank()) return preset.name
        return try {
            val type = AiPrefs.PresetType.valueOf(preset.type)
            when (type) {
                AiPrefs.PresetType.FIX -> "Fix (English)"
                AiPrefs.PresetType.TRANSLATE_TAMIL -> "Translate to Tamil"
                else -> "Custom ${type.ordinal - 1}"
            }
        } catch (_: Exception) {
            preset.type
        }
    }

    fun getPresets(context: Context): List<SerializablePreset> {
        val prefs = context.prefs()
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
            // v1.5 migration: seed CUSTOM_3..5 defaults for existing installs, but only
            // where the stored prompt is still blank (user never edited it).
            if (!prefs.getBoolean(PREF_AI_PRESETS_SEEDED, false)) {
                var changed = false
                for (default in defaultPresets) {
                    val idx = merged.indexOfFirst { it.type == default.type }
                    if (idx >= 0) {
                        val current = merged[idx]
                        if (current.prompt.isBlank() && default.prompt.isNotBlank()) {
                            merged[idx] = current.copy(prompt = default.prompt, name = default.name, enabled = true)
                            changed = true
                        } else if (current.name.isBlank() && default.name.isNotBlank()) {
                            merged[idx] = current.copy(name = default.name)
                            changed = true
                        }
                    }
                }
                if (changed) savePresets(context, merged)
                prefs.edit().putBoolean(PREF_AI_PRESETS_SEEDED, true).apply()
            }
            merged.sortedBy { it.order }
        } catch (_: Exception) {
            defaultPresets
        }
    }

    fun savePresets(context: Context, presets: List<SerializablePreset>) {
        val prefs = context.prefs()
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
