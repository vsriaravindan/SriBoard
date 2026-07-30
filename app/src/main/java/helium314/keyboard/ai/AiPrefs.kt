// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.ai

import android.content.Context
import helium314.keyboard.latin.settings.Settings

object AiPrefs {
    // Provider types
    enum class Provider(val displayName: String, val defaultModel: String, val requiresEndpoint: Boolean = false) {
        GOOGLE_AI("Google AI (Gemini)", "gemini-2.0-flash"),
        GROK("Grok (xAI)", "grok-2"),
        DEEPSEEK_FLASH("DeepSeek Flash", "deepseek-chat"),
        DEEPSEEK_PRO("DeepSeek Pro", "deepseek-reasoner"),
        CUSTOM_OPENAI("OpenAI Compatible", "gpt-4o-mini", requiresEndpoint = true);

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

    fun getProvider(context: Context): Provider {
        val name = context.getSharedPreferences("settings", Context.MODE_PRIVATE)
            .getString(Settings.PREF_AI_PROVIDER, Provider.GOOGLE_AI.name) ?: Provider.GOOGLE_AI.name
        return try { Provider.valueOf(name) } catch (_: Exception) { Provider.GOOGLE_AI }
    }

    fun getApiKey(context: Context): String {
        return context.getSharedPreferences("settings", Context.MODE_PRIVATE)
            .getString(Settings.PREF_AI_API_KEY, "") ?: ""
    }

    fun getModel(context: Context): String {
        val provider = getProvider(context)
        return context.getSharedPreferences("settings", Context.MODE_PRIVATE)
            .getString(Settings.PREF_AI_MODEL, provider.defaultModel) ?: provider.defaultModel
    }

    fun getEndpoint(context: Context): String {
        val provider = getProvider(context)
        return context.getSharedPreferences("settings", Context.MODE_PRIVATE)
            .getString(Settings.PREF_AI_ENDPOINT, provider.defaultEndpoint()) ?: provider.defaultEndpoint()
    }
}
