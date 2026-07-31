// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.latin.utils

import android.content.Context
import android.content.SharedPreferences
import android.view.View
import android.view.ViewConfiguration
import android.view.ViewGroup
import android.widget.ImageButton
import android.widget.ImageView
import androidx.core.content.edit
import androidx.core.view.forEach
import helium314.keyboard.ai.AiProgressDrawable
import helium314.keyboard.event.HapticEvent
import helium314.keyboard.keyboard.internal.KeyboardIconsSet
import helium314.keyboard.keyboard.internal.keyboard_parser.floris.KeyCode
import helium314.keyboard.latin.AudioAndHapticFeedbackManager
import helium314.keyboard.latin.R
import helium314.keyboard.latin.common.Constants.Separators
import helium314.keyboard.latin.settings.Defaults
import helium314.keyboard.latin.settings.Settings
import helium314.keyboard.latin.utils.ToolbarKey.*
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.util.EnumMap
import java.util.Locale

fun createToolbarKey(context: Context, key: ToolbarKey): ImageButton {
    val button = ImageButton(context, null, R.attr.suggestionWordStyle)
    button.scaleType = ImageView.ScaleType.CENTER
    button.tag = key
    button.contentDescription = key.name.lowercase().getStringResourceOrName("", context)
    setToolbarButtonActivatedState(button)
    try {
        val icon = KeyboardIconsSet.instance.getNewDrawable(key.name, context)
        if (icon != null) {
            button.setImageDrawable(icon)
        } else {
            // Fallback: create a simple text bitmap for unrecognized keys
            val label = when (key) {
                AI_FIX -> "Fx"
                AI_TRANSLATE_TAMIL -> "Ta"
                AI_CUSTOM_1 -> "C1"
                AI_CUSTOM_2 -> "C2"
                AI_CUSTOM_3 -> "C3"
                AI_CUSTOM_4 -> "C4"
                AI_CUSTOM_5 -> "C5"
                else -> key.name.take(2)
            }
            val size = (24 * context.resources.displayMetrics.density).toInt().coerceAtLeast(1)
            val bmp = android.graphics.Bitmap.createBitmap(size, size, android.graphics.Bitmap.Config.ARGB_8888)
            val canvas = android.graphics.Canvas(bmp)
            val paint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG)
            paint.color = android.graphics.Color.parseColor("#8AB4F8")
            paint.textSize = 11 * context.resources.displayMetrics.density
            paint.textAlign = android.graphics.Paint.Align.CENTER
            val x = (size / 2).toFloat()
            val y = (size / 2 + paint.textSize / 3).toFloat()
            canvas.drawText(label, x, y, paint)
            button.setImageBitmap(bmp)
        }
    } catch (_: Exception) {
        // never crash the keyboard because of a missing icon
    }
    // Sriboard: keep a registry of live AI toolbar buttons so LatinIME can swap in
    // the progress drawable while an AI request is running.
    if (key.name.startsWith("AI_")) AiToolbarButtonRegistry.register(key, button)
    return button
}

/**
 * Sriboard: live AI toolbar buttons (created by [createToolbarKey]).
 * Used to show the in-key progress indicator while an AI request is in flight.
 * Stale entries (buttons removed from a rebuilt toolbar) are harmless — they are
 * detached views and simply won't render.
 */
object AiToolbarButtonRegistry {
    private val buttons = mutableMapOf<ToolbarKey, ImageButton>()
    private val progressDrawables = mutableMapOf<ToolbarKey, AiProgressDrawable>()

    fun register(key: ToolbarKey, button: ImageButton) {
        buttons[key] = button
    }

    fun setProcessing(processing: Boolean, percent: Int?) {
        if (!processing) {
            progressDrawables.values.forEach { it.stop() }
            progressDrawables.clear()
            buttons.forEach { (key, button) ->
                try {
                    button.scaleType = ImageView.ScaleType.CENTER
                    button.setImageDrawable(KeyboardIconsSet.instance.getNewDrawable(key.name, button.context))
                } catch (_: Exception) {
                    // keep whatever is set — never crash over an icon
                }
            }
            return
        }
        buttons.forEach { (key, button) ->
            val drawable = progressDrawables.getOrPut(key) {
                // fill the whole key with the progress ring so it is impossible to miss
                button.scaleType = ImageView.ScaleType.FIT_XY
                AiProgressDrawable(density = button.resources.displayMetrics.density).also { d -> button.setImageDrawable(d) }
            }
            drawable.update(percent)
        }
    }

    fun clear() {
        progressDrawables.values.forEach { it.stop() }
        progressDrawables.clear()
        buttons.clear()
    }
}

/**
 * Sriboard: switch AI toolbar buttons between the progress indicator and their
 * normal icons. Called by LatinIME when an AI request starts/finishes.
 * Must run on the main thread. [percent] null = indeterminate spinner.
 */
fun setAiToolbarProcessing(processing: Boolean, percent: Int? = null) {
    AiToolbarButtonRegistry.setProcessing(processing, percent)
}

fun setToolbarButtonsActivatedStateOnPrefChange(buttonsGroup: ViewGroup, key: String?) {
    // settings need to be updated when buttons change
    if (key != Settings.PREF_AUTO_CORRECTION
        && key != Settings.PREF_ALWAYS_INCOGNITO_MODE
        && key != GestureDataGatheringSettings.PREF_BACKGROUND_GATHERING_ENABLED
        && key != GestureDataGatheringSettings.PREF_BACKGROUND_DISABLED_BEFORE_TIME_MILLIS
        && key?.startsWith(Settings.PREF_ONE_HANDED_MODE_PREFIX) == false)
        return

    GlobalScope.launch {
        delay(10) // need to wait until SettingsValues are reloaded
        buttonsGroup.forEach { if (it is ImageButton) setToolbarButtonActivatedState(it) }
    }
}

private fun setToolbarButtonActivatedState(button: ImageButton) {
    button.isActivated = when (button.tag) {
        INCOGNITO -> button.context.prefs().getBoolean(Settings.PREF_ALWAYS_INCOGNITO_MODE, Defaults.PREF_ALWAYS_INCOGNITO_MODE)
        ONE_HANDED -> Settings.getValues().mOneHandedModeEnabled
        SPLIT -> Settings.getValues().mIsSplitKeyboardEnabled
        AUTOCORRECT -> Settings.getValues().mAutoCorrectionEnabledPerUserSettings
        BACKGROUND_GATHERING -> useBackgroundGathering
        else -> true
    }
}

fun getCodeForToolbarKey(key: ToolbarKey) = Settings.getInstance().getCustomToolbarKeyCode(key) ?: when (key) {
    VOICE -> KeyCode.VOICE_INPUT
    CLIPBOARD -> KeyCode.CLIPBOARD
    NUMPAD -> KeyCode.NUMPAD
    DPAD -> KeyCode.DPAD
    UNDO -> KeyCode.UNDO
    REDO -> KeyCode.REDO
    SETTINGS -> KeyCode.SETTINGS
    SELECT_ALL -> KeyCode.CLIPBOARD_SELECT_ALL
    SELECT_WORD -> KeyCode.CLIPBOARD_SELECT_WORD
    COPY -> KeyCode.CLIPBOARD_COPY
    CUT -> KeyCode.CLIPBOARD_CUT
    PASTE -> KeyCode.CLIPBOARD_PASTE
    ONE_HANDED -> KeyCode.TOGGLE_ONE_HANDED_MODE
    INCOGNITO -> KeyCode.TOGGLE_INCOGNITO_MODE
    AUTOCORRECT -> KeyCode.TOGGLE_AUTOCORRECT
    CLEAR_CLIPBOARD -> KeyCode.CLIPBOARD_CLEAR_HISTORY
    CLOSE_HISTORY -> KeyCode.CLIPBOARD
    EMOJI -> KeyCode.EMOJI
    LEFT -> KeyCode.ARROW_LEFT
    RIGHT -> KeyCode.ARROW_RIGHT
    UP -> KeyCode.ARROW_UP
    DOWN -> KeyCode.ARROW_DOWN
    WORD_LEFT -> KeyCode.WORD_LEFT
    WORD_RIGHT -> KeyCode.WORD_RIGHT
    PAGE_UP -> KeyCode.PAGE_UP
    PAGE_DOWN -> KeyCode.PAGE_DOWN
    FULL_LEFT -> KeyCode.MOVE_START_OF_LINE
    FULL_RIGHT -> KeyCode.MOVE_END_OF_LINE
    PAGE_START -> KeyCode.MOVE_START_OF_PAGE
    PAGE_END -> KeyCode.MOVE_END_OF_PAGE
    SPLIT -> KeyCode.SPLIT_LAYOUT
    FLOATING -> KeyCode.TOGGLE_FLOATING_WINDOW
    BACKGROUND_GATHERING -> KeyCode.BACKGROUND_GATHERING
    // Sriboard AI toolbar keys
    AI_FIX -> KeyCode.AI_FIX
    AI_TRANSLATE_TAMIL -> KeyCode.AI_TRANSLATE_TAMIL
    AI_CUSTOM_1 -> KeyCode.AI_CUSTOM_1
    AI_CUSTOM_2 -> KeyCode.AI_CUSTOM_2
    AI_CUSTOM_3 -> KeyCode.AI_CUSTOM_3
    AI_CUSTOM_4 -> KeyCode.AI_CUSTOM_4
    AI_CUSTOM_5 -> KeyCode.AI_CUSTOM_5
    AI_MENU -> KeyCode.AI_MENU
}

fun getCodeForToolbarKeyLongClick(key: ToolbarKey) = Settings.getInstance().getCustomToolbarLongpressCode(key) ?: when (key) {
    CLIPBOARD -> KeyCode.CLIPBOARD_PASTE
    UNDO -> KeyCode.REDO
    REDO -> KeyCode.UNDO
    SELECT_ALL -> KeyCode.CLIPBOARD_SELECT_WORD
    SELECT_WORD -> KeyCode.CLIPBOARD_SELECT_ALL
    COPY -> KeyCode.CLIPBOARD_CUT
    PASTE -> KeyCode.CLIPBOARD
    LEFT -> KeyCode.KEY_REPEAT
    RIGHT -> KeyCode.KEY_REPEAT
    UP -> KeyCode.KEY_REPEAT
    DOWN -> KeyCode.KEY_REPEAT
    WORD_LEFT -> KeyCode.KEY_REPEAT
    WORD_RIGHT -> KeyCode.KEY_REPEAT
    PAGE_UP -> KeyCode.MOVE_START_OF_PAGE
    PAGE_DOWN -> KeyCode.MOVE_END_OF_PAGE
    BACKGROUND_GATHERING -> KeyCode.BACKGROUND_GATHERING_TEMP_OFF
    else -> KeyCode.UNSPECIFIED
}

// names need to be aligned with resources strings (using lowercase of key.name)
enum class ToolbarKey {
    VOICE, CLIPBOARD, NUMPAD, DPAD, UNDO, REDO, SETTINGS, SELECT_ALL, SELECT_WORD, COPY, CUT, PASTE, ONE_HANDED, FLOATING, SPLIT,
    INCOGNITO, AUTOCORRECT, CLEAR_CLIPBOARD, CLOSE_HISTORY, EMOJI, LEFT, RIGHT, UP, DOWN, WORD_LEFT, WORD_RIGHT,
    PAGE_UP, PAGE_DOWN, FULL_LEFT, FULL_RIGHT, PAGE_START, PAGE_END, BACKGROUND_GATHERING,
    // Sriboard AI toolbar keys
    AI_FIX, AI_TRANSLATE_TAMIL, AI_CUSTOM_1, AI_CUSTOM_2, AI_CUSTOM_3, AI_CUSTOM_4, AI_CUSTOM_5, AI_MENU
}

enum class ToolbarMode {
    EXPANDABLE, TOOLBAR_KEYS, SUGGESTION_STRIP, HIDDEN,
}

val toolbarKeyStrings = entries.associateWithTo(EnumMap(ToolbarKey::class.java)) { it.toString().lowercase(Locale.US) }

val defaultToolbarPref by lazy {
    val default = listOf(SETTINGS, VOICE, CLIPBOARD, UNDO, REDO, SELECT_WORD, COPY, PASTE, LEFT, RIGHT, AI_FIX)
    val others = entries.filterNot { it in default || it == CLOSE_HISTORY }
    default.joinToString(Separators.ENTRY) { it.name + Separators.KV + true } + Separators.ENTRY +
            others.joinToString(Separators.ENTRY) { it.name + Separators.KV + false }
}

val defaultPinnedToolbarPref = entries.filterNot { it == CLOSE_HISTORY }.joinToString(Separators.ENTRY) {
    it.name + Separators.KV + false
}

val defaultClipboardToolbarPref by lazy {
    val default = listOf(CLEAR_CLIPBOARD, UP, DOWN, LEFT, RIGHT, UNDO, CUT, COPY, PASTE, SELECT_WORD, CLOSE_HISTORY)
    val others = entries.filterNot { it in default }
    default.joinToString(Separators.ENTRY) { it.name + Separators.KV + true } + Separators.ENTRY +
            others.joinToString(Separators.ENTRY) { it.name + Separators.KV + false }
}

/** add missing keys, typically because a new key has been added */
fun upgradeToolbarPrefs(prefs: SharedPreferences) {
    upgradeToolbarPref(prefs, Settings.PREF_TOOLBAR_KEYS, defaultToolbarPref)
    upgradeToolbarPref(prefs, Settings.PREF_PINNED_TOOLBAR_KEYS, defaultPinnedToolbarPref)
    upgradeToolbarPref(prefs, Settings.PREF_CLIPBOARD_TOOLBAR_KEYS, defaultClipboardToolbarPref)
}

private fun upgradeToolbarPref(prefs: SharedPreferences, pref: String, default: String) {
    if (!prefs.contains(pref)) return
    val list = prefs.getString(pref, default)!!.split(Separators.ENTRY).toMutableList()
    val splitDefault = defaultToolbarPref.split(Separators.ENTRY)
    splitDefault.forEach { entry ->
        val keyWithSeparator = entry.substringBefore(Separators.KV) + Separators.KV
        if (list.none { it.startsWith(keyWithSeparator) })
            list.add("${keyWithSeparator}false")
    }
    // likely not needed, but better prepare for possibility of key removal
    list.removeAll {
        try {
            ToolbarKey.valueOf(it.substringBefore(Separators.KV))
            false
        } catch (_: IllegalArgumentException) {
            true
        }
    }
    prefs.edit { putString(pref, list.joinToString(Separators.ENTRY)) }
}

fun getEnabledToolbarKeys(prefs: SharedPreferences) = getEnabledToolbarKeys(prefs, Settings.PREF_TOOLBAR_KEYS, defaultToolbarPref)

/**
 * Sriboard: ensure AI toolbar keys exist and AI_FIX is enabled by default.
 * Runs on every app start (release too), so users upgrading from HeliBoard get the
 * AI keys added to their existing toolbar prefs.
 */
fun enableAiToolbarKeys(prefs: SharedPreferences) {
    // One-time migration: adds AI keys to existing HeliBoard toolbar prefs and enables AI_FIX.
    if (prefs.getBoolean(PREF_AI_TOOLBAR_MIGRATED, false)) return
    upgradeToolbarPref(prefs, Settings.PREF_TOOLBAR_KEYS, defaultToolbarPref)
    upgradeToolbarPref(prefs, Settings.PREF_PINNED_TOOLBAR_KEYS, defaultPinnedToolbarPref)
    upgradeToolbarPref(prefs, Settings.PREF_CLIPBOARD_TOOLBAR_KEYS, defaultClipboardToolbarPref)
    // AI_FIX must be enabled by default (others stay off until user enables them)
    setToolbarKeyEnabled(prefs, Settings.PREF_TOOLBAR_KEYS, defaultToolbarPref, ToolbarKey.AI_FIX, true)
    prefs.edit { putBoolean(PREF_AI_TOOLBAR_MIGRATED, true) }
}

private const val PREF_AI_TOOLBAR_MIGRATED = "ai_toolbar_keys_migrated"

/** Sriboard: every AI toolbar key. */
val allAiToolbarKeys = listOf(
    ToolbarKey.AI_FIX,
    ToolbarKey.AI_TRANSLATE_TAMIL,
    ToolbarKey.AI_CUSTOM_1,
    ToolbarKey.AI_CUSTOM_2,
    ToolbarKey.AI_CUSTOM_3,
    ToolbarKey.AI_CUSTOM_4,
    ToolbarKey.AI_CUSTOM_5,
    ToolbarKey.AI_MENU
)

/**
 * Sriboard: idempotently enable ALL AI keys in the main toolbar. Called whenever
 * AI is turned on with an API key (settings save, app start) so every AI feature
 * becomes immediately accessible — no manual Settings → Toolbar dance needed.
 * The user can still disable individual keys later.
 */
fun enableAllAiToolbarKeys(prefs: SharedPreferences) {
    upgradeToolbarPref(prefs, Settings.PREF_TOOLBAR_KEYS, defaultToolbarPref)
    allAiToolbarKeys.forEach { key ->
        setToolbarKeyEnabled(prefs, Settings.PREF_TOOLBAR_KEYS, defaultToolbarPref, key, true)
    }
}

/** Sriboard: AI is configured when the master switch is on and an API key exists. */
fun isAiConfigured(prefs: SharedPreferences): Boolean =
    prefs.getBoolean(Settings.PREF_AI_ENABLED, false)
        && !(prefs.getString(Settings.PREF_AI_API_KEY, "") ?: "").isBlank()

private fun setToolbarKeyEnabled(prefs: SharedPreferences, pref: String, default: String, key: ToolbarKey, enabled: Boolean) {
    val string = prefs.getString(pref, default)!!
    val entries = string.split(Separators.ENTRY).toMutableList()
    val index = entries.indexOfFirst { it.startsWith(key.name + Separators.KV) }
    if (index >= 0) {
        entries[index] = key.name + Separators.KV + enabled
        prefs.edit { putString(pref, entries.joinToString(Separators.ENTRY)) }
    }
}

fun getPinnedToolbarKeys(prefs: SharedPreferences) = getEnabledToolbarKeys(prefs, Settings.PREF_PINNED_TOOLBAR_KEYS, defaultPinnedToolbarPref)

fun getEnabledClipboardToolbarKeys(prefs: SharedPreferences) = getEnabledToolbarKeys(prefs, Settings.PREF_CLIPBOARD_TOOLBAR_KEYS, defaultClipboardToolbarPref)

fun addPinnedKey(prefs: SharedPreferences, key: ToolbarKey) {
    // remove the existing version of this key and add the enabled one after the last currently enabled key
    val string = prefs.getString(Settings.PREF_PINNED_TOOLBAR_KEYS, defaultPinnedToolbarPref)!!
    val keys = string.split(Separators.ENTRY).toMutableList()
    keys.removeAll { it.startsWith(key.name + Separators.KV) }
    val lastEnabledIndex = keys.indexOfLast { it.endsWith("true") }
    keys.add(lastEnabledIndex + 1, key.name + Separators.KV + "true")
    prefs.edit { putString(Settings.PREF_PINNED_TOOLBAR_KEYS, keys.joinToString(Separators.ENTRY)) }
}

fun removePinnedKey(prefs: SharedPreferences, key: ToolbarKey) {
    // just set it to disabled
    val string = prefs.getString(Settings.PREF_PINNED_TOOLBAR_KEYS, defaultPinnedToolbarPref)!!
    val result = string.split(Separators.ENTRY).joinToString(Separators.ENTRY) {
        if (it.startsWith(key.name + Separators.KV))
            key.name + Separators.KV + "false"
        else it
    }
    prefs.edit { putString(Settings.PREF_PINNED_TOOLBAR_KEYS, result) }
}

private fun getEnabledToolbarKeys(prefs: SharedPreferences, pref: String, default: String): List<ToolbarKey> {
    val string = prefs.getString(pref, default)!!
    return string.split(Separators.ENTRY).mapNotNull {
        val split = it.split(Separators.KV)
        if (split.last() == "true") {
            try {
                ToolbarKey.valueOf(split.first())
            } catch (_: IllegalArgumentException) {
                null
            }
        } else null
    }
}

fun writeCustomKeyCodes(prefs: SharedPreferences, codes: EnumMap<ToolbarKey, Pair<Int?, Int?>>) {
    val string = codes.mapNotNull { entry -> entry.value?.let { "${entry.key.name},${it.first},${it.second}" } }.joinToString(";")
    prefs.edit { putString(Settings.PREF_TOOLBAR_CUSTOM_KEY_CODES, string) }
}

fun readCustomKeyCodes(prefs: SharedPreferences): EnumMap<ToolbarKey, Pair<Int?, Int?>> {
    val map = EnumMap<ToolbarKey, Pair<Int?, Int?>>(ToolbarKey::class.java)
    prefs.getString(Settings.PREF_TOOLBAR_CUSTOM_KEY_CODES, Defaults.PREF_TOOLBAR_CUSTOM_KEY_CODES)!!
        .split(";").forEach {
            runCatching {
                val s = it.split(",")
                map[ToolbarKey.valueOf(s[0])] = s[1].toIntOrNull() to s[2].toIntOrNull()
            }
        }
    return map
}

fun getCustomKeyCode(key: ToolbarKey, prefs: SharedPreferences): Int? {
    if (customToolbarKeyCodes == null)
        customToolbarKeyCodes = readCustomKeyCodes(prefs)
    return customToolbarKeyCodes!![key]?.first
}

fun getCustomLongpressKeyCode(key: ToolbarKey, prefs: SharedPreferences): Int? {
    if (customToolbarKeyCodes == null)
        customToolbarKeyCodes = readCustomKeyCodes(prefs)
    return customToolbarKeyCodes!![key]?.second
}

fun clearCustomToolbarKeyCodes() {
    customToolbarKeyCodes = null
}

fun onClickToolbarKey(view: View, onCodeInput: (Int) -> Unit) {
    AudioAndHapticFeedbackManager.getInstance().performHapticAndAudioFeedback(KeyCode.NOT_SPECIFIED, view, HapticEvent.KEY_PRESS)
    val code = getCodeForToolbarKey(view.tag as ToolbarKey)
    if (code != KeyCode.UNSPECIFIED) {
        onCodeInput(code)
    }
}

fun onLongClickToolbarKey(view: View, onCodeInput: (Int, Boolean) -> Unit) {
    AudioAndHapticFeedbackManager.getInstance().performHapticAndAudioFeedback(KeyCode.NOT_SPECIFIED, view, HapticEvent.KEY_LONG_PRESS)
    val longClickCode = getCodeForToolbarKeyLongClick(view.tag as ToolbarKey)
    if (longClickCode == KeyCode.KEY_REPEAT) {
        onClickToolbarKey(view) { onCodeInput(it, false) }
        repeatToolbarKey(view) { onClickToolbarKey(view) { onCodeInput(it, true) } }
    } else if (longClickCode != KeyCode.UNSPECIFIED) {
        onCodeInput(longClickCode, false)
    }
}

private fun repeatToolbarKey(view: View, onClick: (view: View) -> Unit) {
    view.handler.postDelayed({
        if (view.isPressed) {
            onClick(view)
            repeatToolbarKey(view, onClick)
        }
    }, view.resources.getInteger(R.integer.config_key_repeat_interval).toLong())
}

private var customToolbarKeyCodes: EnumMap<ToolbarKey, Pair<Int?, Int?>>? = null
