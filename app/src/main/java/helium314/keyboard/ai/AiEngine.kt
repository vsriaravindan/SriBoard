// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.ai

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.view.inputmethod.InputConnection
import helium314.keyboard.keyboard.internal.keyboard_parser.floris.KeyCode
import helium314.keyboard.latin.settings.Settings
import helium314.keyboard.latin.utils.prefs
import kotlinx.coroutines.*
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Core AI engine that handles toolbar key presses.
 *
 * Flow:
 * 1. User presses AI toolbar key → key code arrives via [handleKeyCode]
 * 2. Read text before cursor via InputConnection
 * 3. Call AI API on background thread
 * 4. Commit result via InputConnection.commitText()
 * 5. Undo on second press
 *
 * No AccessibilityService, no Developer Options needed — this runs inside the IME.
 */
class AiEngine(private val context: Context) {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val isProcessing = AtomicBoolean(false)
    private val handler = Handler(Looper.getMainLooper())

    // For undo: save original text + cursor position
    private var lastOriginalText: String? = null
    private var lastOriginalCursor: Int = -1
    private var lastKeyCode: Int = -1

    // Callback for UI state changes
    var onProcessingStateChanged: ((Boolean) -> Unit)? = null

    /**
     * Map an AI ToolbarKey to its corresponding key code
     */
    companion object {
        fun presetTypeToKeyCode(type: AiPrefs.PresetType): Int = when (type) {
            AiPrefs.PresetType.FIX -> KeyCode.AI_FIX
            AiPrefs.PresetType.TRANSLATE_TAMIL -> KeyCode.AI_TRANSLATE_TAMIL
            AiPrefs.PresetType.CUSTOM_1 -> KeyCode.AI_CUSTOM_1
            AiPrefs.PresetType.CUSTOM_2 -> KeyCode.AI_CUSTOM_2
            AiPrefs.PresetType.CUSTOM_3 -> KeyCode.AI_CUSTOM_3
            AiPrefs.PresetType.CUSTOM_4 -> KeyCode.AI_CUSTOM_4
            AiPrefs.PresetType.CUSTOM_5 -> KeyCode.AI_CUSTOM_5
        }

        fun keyCodeToPresetType(keyCode: Int): AiPrefs.PresetType? = when (keyCode) {
            KeyCode.AI_FIX -> AiPrefs.PresetType.FIX
            KeyCode.AI_TRANSLATE_TAMIL -> AiPrefs.PresetType.TRANSLATE_TAMIL
            KeyCode.AI_CUSTOM_1 -> AiPrefs.PresetType.CUSTOM_1
            KeyCode.AI_CUSTOM_2 -> AiPrefs.PresetType.CUSTOM_2
            KeyCode.AI_CUSTOM_3 -> AiPrefs.PresetType.CUSTOM_3
            KeyCode.AI_CUSTOM_4 -> AiPrefs.PresetType.CUSTOM_4
            KeyCode.AI_CUSTOM_5 -> AiPrefs.PresetType.CUSTOM_5
            else -> null
        }

        fun isAiKeyCode(keyCode: Int): Boolean = keyCode in KeyCode.AI_CUSTOM_5..KeyCode.AI_FIX

        private const val CONFIRM_HAPTIC = 1
        private const val REJECT_HAPTIC = 2
    }

    /**
     * Handle an AI key code. Called from LatinIME.onEvent() on main thread.
     * @return true if this was an AI key code (handled), false otherwise
     */
    fun handleKeyCode(keyCode: Int, connection: InputConnection?): Boolean {
        if (!isAiKeyCode(keyCode)) return false
        if (connection == null) return true

        val presetType = keyCodeToPresetType(keyCode) ?: return true

        // Check if AI is enabled
        val prefs = context.prefs()
        if (!prefs.getBoolean(Settings.PREF_AI_ENABLED, false)) {
            showToast("AI features are disabled. Enable in AI Settings.")
            performHaptic(REJECT_HAPTIC)
            return true
        }

        // Check if already processing
        if (!isProcessing.compareAndSet(false, true)) {
            // Second press = undo
            handleUndo(connection, keyCode)
            return true
        }

        // Second press while processing = cancel not supported (too complex), just ignore
        if (lastKeyCode == keyCode && lastOriginalText != null) {
            handleUndo(connection, keyCode)
            isProcessing.set(false)
            return true
        }

        // Read text before cursor
        val textBeforeCursor = connection.getTextBeforeCursor(4000, 0)?.toString() ?: ""
        val textAfterCursor = connection.getTextAfterCursor(4000, 0)?.toString() ?: ""
        val fullText = textBeforeCursor + (connection.getSelectedText(0)?.toString() ?: "")

        if (fullText.isBlank()) {
            showToast("No text to process")
            performHaptic(REJECT_HAPTIC)
            isProcessing.set(false)
            return true
        }

        // Save for undo
        lastOriginalText = textBeforeCursor + (connection.getSelectedText(0)?.toString() ?: "") + textAfterCursor
        lastOriginalCursor = connection.getTextBeforeCursor(4000, 0)?.length ?: 0
        lastKeyCode = keyCode

        onProcessingStateChanged?.invoke(true)

        // Get preset prompt
        val presets = AiPresetManager.getEnabledPresets(context)
        val preset = presets.find { it.type == presetType.name }
        val prompt = preset?.prompt ?: presetType.defaultPrompt

        if (prompt.isBlank()) {
            showToast("No prompt configured for this preset")
            performHaptic(REJECT_HAPTIC)
            isProcessing.set(false)
            onProcessingStateChanged?.invoke(false)
            return true
        }

        // Launch AI call on background thread
        scope.launch {
            val result = withContext(Dispatchers.IO) {
                val provider = AiPrefs.getProvider(context)
                val apiKey = AiPrefs.getApiKey(context)
                val model = AiPrefs.getModel(context)
                val endpoint = AiPrefs.getEndpoint(context)

                AiApiClient.generate(provider, apiKey, model, endpoint, prompt, fullText)
            }

            onProcessingStateChanged?.invoke(false)

            if (result.success && result.text.isNotBlank()) {
                // Replace the text via InputConnection
                val ic = connection ?: run {
                    showToast("Connection lost")
                    performHaptic(REJECT_HAPTIC)
                    isProcessing.set(false)
                    return@launch
                }

                // Delete text before cursor, then commit the AI result
                val deleteCount = textBeforeCursor.length + (connection.getSelectedText(0)?.length ?: 0)
                if (deleteCount > 0) {
                    ic.deleteSurroundingText(deleteCount, 0)
                }
                ic.commitText(result.text, 1)

                performHaptic(CONFIRM_HAPTIC)
                showToast("Done")
            } else {
                showToast(result.errorMessage.ifBlank { "AI error" })
                performHaptic(REJECT_HAPTIC)
            }

            isProcessing.set(false)
        }

        return true
    }

    /**
     * Undo the last AI replacement by restoring original text
     */
    private fun handleUndo(connection: InputConnection, keyCode: Int) {
        val original = lastOriginalText ?: return
        val cursor = lastOriginalCursor

        // Replace all text with original
        connection.deleteSurroundingText(4000, 4000)
        connection.commitText(original, 1)
        if (cursor >= 0) {
            connection.setSelection(cursor, cursor)
        }

        lastOriginalText = null
        lastOriginalCursor = -1
        lastKeyCode = -1
        performHaptic(CONFIRM_HAPTIC)
        showToast("Undone")
    }

    fun destroy() {
        scope.cancel()
    }

    private fun showToast(msg: String) {
        handler.post {
            android.widget.Toast.makeText(context, msg, android.widget.Toast.LENGTH_SHORT).show()
        }
    }

    @Suppress("DEPRECATION")
    private fun performHaptic(feedbackType: Int) {
        handler.post {
            try {
                if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.S) {
                    val vm = context.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as? VibratorManager
                    val v = vm?.defaultVibrator
                    when (feedbackType) {
                        CONFIRM_HAPTIC -> v?.vibrate(VibrationEffect.createPredefined(VibrationEffect.EFFECT_TICK))
                        REJECT_HAPTIC -> v?.vibrate(VibrationEffect.createPredefined(VibrationEffect.EFFECT_DOUBLE_CLICK))
                    }
                } else if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q) {
                    val v = context.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
                    when (feedbackType) {
                        CONFIRM_HAPTIC -> v?.vibrate(VibrationEffect.createPredefined(VibrationEffect.EFFECT_TICK))
                        REJECT_HAPTIC -> v?.vibrate(VibrationEffect.createPredefined(VibrationEffect.EFFECT_DOUBLE_CLICK))
                    }
                } else {
                    val v = context.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
                    if (feedbackType == CONFIRM_HAPTIC) v?.vibrate(50)
                    else v?.vibrate(100)
                }
            } catch (_: Exception) {}
        }
    }
}
