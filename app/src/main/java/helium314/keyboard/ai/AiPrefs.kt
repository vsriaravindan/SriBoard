// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.ai

import android.content.Context
import helium314.keyboard.latin.settings.Settings
import helium314.keyboard.latin.utils.prefs

object AiPrefs {
    // Provider types
    enum class Provider(val displayName: String, val defaultModel: String, val requiresEndpoint: Boolean = false) {
        GOOGLE_AI("Google AI (Gemini)", "gemini-3.5-flash"),
        GROK("Grok (xAI)", "grok-4.20"),
        DEEPSEEK_FLASH("DeepSeek Flash", "deepseek-v4-flash"),
        DEEPSEEK_PRO("DeepSeek Pro", "deepseek-v4-pro"),
        CUSTOM_OPENAI("OpenAI Compatible", "gpt-5.6", requiresEndpoint = true);

        /** Current, verified model IDs for this provider (as of Aug 2026). */
        fun availableModels(): List<String> = when (this) {
            GOOGLE_AI -> listOf("gemini-3.5-flash", "gemini-3.5-flash-lite", "gemini-3.1-pro", "gemini-3.1-flash-lite", "gemini-2.5-flash", "gemini-2.5-pro")
            GROK -> listOf("grok-4.20", "grok-4.20-0309", "grok-4.20-0309-reasoning", "grok-4.20-0309-non-reasoning", "grok-2")
            DEEPSEEK_FLASH -> listOf("deepseek-v4-flash", "deepseek-chat")
            DEEPSEEK_PRO -> listOf("deepseek-v4-pro", "deepseek-reasoner")
            CUSTOM_OPENAI -> listOf("gpt-5.6", "gpt-5.6-luna", "gpt-5.6-sol", "gpt-5.6-terra", "gpt-5-4")
        }

        fun defaultEndpoint() = when (this) {
            GOOGLE_AI -> ""
            GROK -> "https://api.x.ai/v1"
            DEEPSEEK_FLASH, DEEPSEEK_PRO -> "https://api.deepseek.com/v1"
            CUSTOM_OPENAI -> ""
        }
    }

    // Preset types
    enum class PresetType(val defaultPrompt: String) {
        FIX("Fix only English grammar and spelling errors. Return ONLY the corrected text, nothing else."),
        TRANSLATE_TAMIL("Translate the following text to Tamil. Return ONLY the translated text, nothing else."),
        CUSTOM_1(""),
        CUSTOM_2(""),
        CUSTOM_3(""),
        CUSTOM_4(""),
        CUSTOM_5("")
    }

    data class Preset(
        val type: PresetType,
        var prompt: String = type.defaultPrompt,
        var enabled: Boolean = true,
        var order: Int = type.ordinal
    )

    /**
     * IMPORTANT: Must use context.prefs() (device-protected storage, same as Heliboard's
     * settings file). Using getSharedPreferences("settings", ...) writes/reads a DIFFERENT
     * file, which makes keys appear erased.
     */
    fun prefs(context: Context) = context.prefs()

    fun getProvider(context: Context): Provider {
        val name = prefs(context).getString(Settings.PREF_AI_PROVIDER, Provider.GOOGLE_AI.name) ?: Provider.GOOGLE_AI.name
        return try { Provider.valueOf(name) } catch (_: Exception) { Provider.GOOGLE_AI }
    }

    fun getApiKey(context: Context): String {
        return prefs(context).getString(Settings.PREF_AI_API_KEY, "") ?: ""
    }

    fun setApiKey(context: Context, key: String) {
        prefs(context).edit().putString(Settings.PREF_AI_API_KEY, key).apply()
    }

    fun getModel(context: Context): String {
        val provider = getProvider(context)
        val saved = prefs(context).getString(Settings.PREF_AI_MODEL, "") ?: ""
        // migrate saved defaults that are no longer current (v2.2: model names updated)
        if (saved.isBlank() || saved in LEGACY_DEFAULT_MODELS) return provider.defaultModel
        return saved
    }

    private val LEGACY_DEFAULT_MODELS = setOf("gemini-2.0-flash", "grok-2", "deepseek-chat", "deepseek-reasoner", "gpt-4o-mini")

    fun getEndpoint(context: Context): String {
        val provider = getProvider(context)
        return prefs(context).getString(Settings.PREF_AI_ENDPOINT, provider.defaultEndpoint()) ?: provider.defaultEndpoint()
    }
}
