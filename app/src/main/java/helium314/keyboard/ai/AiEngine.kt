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

    // Current in-flight request (for cancel on second tap)
    private var currentJob: Job? = null

    // Callbacks for UI state changes (invoked on the main thread)
    var onProcessingStateChanged: ((Boolean) -> Unit)? = null

    /** Progress 0..100 of the current request, posted to the main thread. */
    var onProgress: ((Int) -> Unit)? = null

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
     * Handle an AI toolbar key code. Called from LatinIME.onEvent() on main thread.
     * @return true if this was an AI key code (handled), false otherwise
     */
    fun handleKeyCode(keyCode: Int, connection: InputConnection?, isPasswordField: Boolean = false): Boolean {
        if (!isAiKeyCode(keyCode)) return false
        val presetType = keyCodeToPresetType(keyCode) ?: return true
        return runPreset(presetType, connection, isPasswordField)
    }

    /**
     * Run an AI preset by type (used by the AI Quick Panel chips).
     * ONE API call per invocation — presets are prompt templates, not requests.
     */
    @JvmOverloads
    fun runPreset(type: AiPrefs.PresetType, connection: InputConnection?, isPasswordField: Boolean = false): Boolean {
        if (connection == null) return true
        val prompt = AiPresetManager.getEnabledPresets(context).find { it.type == type.name }?.prompt
            ?: type.defaultPrompt
        return runRequest(prompt, connection, isPasswordField)
    }

    /**
     * Run a one-off custom prompt (used by the AI Quick Panel prompt bar).
     * Always appends a "return only the output" instruction so the API reply is
     * clean text, not a chatty explanation.
     */
    @JvmOverloads
    fun runPrompt(prompt: String, connection: InputConnection?, isPasswordField: Boolean = false): Boolean {
        if (connection == null) return true
        if (prompt.isBlank()) {
            showToast("Enter a prompt first")
            performHaptic(REJECT_HAPTIC)
            return true
        }
        val promptWithInstruction = prompt.trim() + "\nReturn ONLY the output, nothing else."
        return runRequest(promptWithInstruction, connection, isPasswordField)
    }

    /**
     * Shared request pipeline: password check → AI enabled check → cancel-on-2nd-tap →
     * read text → call API → commit result. Used by toolbar keys, panel chips and the
     * prompt bar. Exactly ONE [AiApiClient.generate] call per invocation.
     */
    private fun runRequest(prompt: String, connection: InputConnection, isPasswordField: Boolean): Boolean {
        // Sriboard: never send password field content to an AI API
        if (isPasswordField) {
            showToast("AI is blocked on password fields")
            performHaptic(REJECT_HAPTIC)
            return true
        }

        // Check if AI is enabled
        val prefs = context.prefs()
        if (!prefs.getBoolean(Settings.PREF_AI_ENABLED, false)) {
            showToast("AI features are disabled. Enable in AI Settings.")
            performHaptic(REJECT_HAPTIC)
            return true
        }

        // Request in flight → second tap cancels it
        if (isProcessing.get()) {
            cancelCurrentRequest()
            return true
        }

        if (!isProcessing.compareAndSet(false, true)) return true

        if (prompt.isBlank()) {
            showToast("No prompt configured for this preset")
            performHaptic(REJECT_HAPTIC)
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

        // Save for potential future undo
        lastOriginalText = textBeforeCursor + (connection.getSelectedText(0)?.toString() ?: "") + textAfterCursor
        lastOriginalCursor = connection.getTextBeforeCursor(4000, 0)?.length ?: 0

        onProcessingStateChanged?.invoke(true)

        // Launch AI call on background thread
        showToast("AI processing…")
        currentJob = scope.launch {
            val myJob = coroutineContext[Job]
            val result = withContext(Dispatchers.IO) {
                val provider = AiPrefs.getProvider(context)
                val apiKey = AiPrefs.getApiKey(context)
                val model = AiPrefs.getModel(context)
                val endpoint = AiPrefs.getEndpoint(context)

                AiApiClient.generate(provider, apiKey, model, endpoint, prompt, fullText) { percent ->
                    // progress arrives on the IO thread → forward on main
                    handler.post { onProgress?.invoke(percent) }
                }
            }

            // Discard if the user cancelled this request or a newer one superseded it —
            // a stale coroutine must never touch shared state.
            if (myJob == null || !myJob.isActive || currentJob !== myJob) {
                return@launch
            }

            // Restore the icon/indicator FIRST. Nothing after this point may re-create
            // the progress drawable, or it would stay stuck on the key (v1.5/1.6 bug).
            onProcessingStateChanged?.invoke(false)

            if (result.success && result.text.isNotBlank()) {
                // Delete text before cursor, then commit the AI result
                val deleteCount = textBeforeCursor.length + (connection.getSelectedText(0)?.length ?: 0)
                if (deleteCount > 0) {
                    connection.deleteSurroundingText(deleteCount, 0)
                }
                connection.commitText(result.text, 1)

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
     * Cancel the in-flight AI request (second tap on an AI key while processing).
     * The HTTP read itself is blocking and not interruptible — the result is simply
     * discarded when it arrives, so the UI returns to idle immediately.
     */
    private fun cancelCurrentRequest() {
        currentJob?.cancel()
        currentJob = null
        isProcessing.set(false)
        // clear the undo slot so the next tap starts a fresh request instead of
        // toggling undo on the never-committed original text
        lastOriginalText = null
        lastOriginalCursor = -1
        onProcessingStateChanged?.invoke(false)
        performHaptic(REJECT_HAPTIC)
        showToast("AI request cancelled")
    }

    /**
     * Undo the last AI replacement by restoring original text.
     * Note: no longer wired to the AI key (v1.6) — use the toolbar UNDO key.
     * Kept for potential future use.
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
        performHaptic(CONFIRM_HAPTIC)
        showToast("Undone")
    }

    fun destroy() {
        currentJob?.cancel()
        currentJob = null
        scope.cancel()
    }

    private fun showToast(msg: String) {
        handler.post {
            try {
                android.widget.Toast.makeText(context, msg, android.widget.Toast.LENGTH_LONG).apply {
                    // show above the keyboard, otherwise it's hidden behind the IME
                    setGravity(android.view.Gravity.TOP or android.view.Gravity.CENTER_HORIZONTAL, 0, 200)
                    show()
                }
            } catch (_: Exception) {}
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
