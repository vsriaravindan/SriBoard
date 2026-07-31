// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.ai

import android.content.Context
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.util.AttributeSet
import android.view.Gravity
import android.view.View
import android.view.inputmethod.EditorInfo
import android.widget.EditText
import android.widget.HorizontalScrollView
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import helium314.keyboard.latin.R
import helium314.keyboard.latin.common.ColorType
import helium314.keyboard.latin.settings.Settings
import helium314.keyboard.keyboard.internal.keyboard_parser.floris.KeyCode

/**
 * Sriboard v1.7: AI Quick Panel — a row of labeled preset chips + a custom-prompt bar,
 * shown above the keyboard when the AI_MENU toolbar key is tapped.
 *
 * The chips are built from the user's ENABLED presets (AiPresetManager) every time the
 * panel opens, so they always match the current preset setup. Tapping a chip runs ONE
 * preset via [onChipClick]; submitting the prompt runs a one-off instruction via
 * [onPromptSubmit]. The status row shows request progress (from AiEngine callbacks).
 */
class AiQuickPanelView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : LinearLayout(context, attrs) {

    var onChipClick: ((AiPrefs.PresetType) -> Unit)? = null
    var onPromptSubmit: ((String) -> Unit)? = null

    private val colors = Settings.getValues().mColors
    private val chipContainer: LinearLayout
    private val promptEdit: EditText
    private val statusRow: LinearLayout
    private val statusProgress: ProgressBar
    private val statusText: TextView
    private var promptFocused = false
    private var pendingHighSurrogate: Char? = null

    init {
        orientation = VERTICAL
        val pad = dp(8)
        setPadding(pad, pad, pad, pad)
        setBackgroundColor(colors.get(ColorType.STRIP_BACKGROUND))

        // Row 1: horizontally scrollable chips (enabled presets)
        chipContainer = LinearLayout(context).apply { orientation = HORIZONTAL }
        addView(
            HorizontalScrollView(context).apply {
                isHorizontalScrollBarEnabled = false
                isFillViewport = false
                addView(chipContainer, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT))
            },
            LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT)
        )

        // Row 2: custom prompt bar
        val promptRow = LinearLayout(context).apply {
            orientation = HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(6), 0, 0)
        }
        promptEdit = EditText(context).apply {
            hint = "Custom instruction… (e.g. write it as a poem)"
            setSingleLine(true)
            textSize = 14f
            inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
            imeOptions = EditorInfo.IME_ACTION_DONE or EditorInfo.IME_FLAG_NO_EXTRACT_UI
            setOnEditorActionListener { _, actionId, _ ->
                if (actionId == EditorInfo.IME_ACTION_DONE || actionId == EditorInfo.IME_ACTION_SEND
                    || actionId == EditorInfo.IME_ACTION_UNSPECIFIED) {
                    submitPrompt()
                    true
                } else false
            }
            // Sriboard: the IME's own keyboard types into the app's field via
            // InputConnection — this in-IME field needs its own focus tracking so
            // LatinIME can route our key events into it instead.
            setOnFocusChangeListener { _, hasFocus -> promptFocused = hasFocus }
        }
        promptRow.addView(promptEdit, LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f))
        val sendButton = ImageButton(context).apply {
            setImageResource(R.drawable.ic_ai_fix)
            scaleType = android.widget.ImageView.ScaleType.CENTER
            background = null
            contentDescription = "Run AI prompt"
            setOnClickListener { submitPrompt() }
        }
        promptRow.addView(sendButton, LayoutParams(dp(36), dp(36)))
        addView(promptRow, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))

        // Row 3: progress status (hidden while idle)
        statusRow = LinearLayout(context).apply {
            orientation = HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(6), 0, 0)
            visibility = View.GONE
        }
        statusProgress = ProgressBar(context, null, android.R.attr.progressBarStyleHorizontal).apply {
            max = 100
            progress = 0
        }
        statusText = TextView(context).apply {
            text = ""
            textSize = 12f
            setPadding(dp(8), 0, 0, 0)
        }
        statusRow.addView(statusProgress, LayoutParams(0, dp(6), 1f))
        statusRow.addView(statusText, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT))
        addView(statusRow, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
    }

    /** Rebuild chips from the user's currently enabled presets. */
    fun refreshChips() {
        chipContainer.removeAllViews()
        val chipColor = colors.get(ColorType.TOOL_BAR_KEY)
        val pillColor = colors.get(ColorType.TOOL_BAR_KEY_ENABLED_BACKGROUND)
        for (preset in AiPresetManager.getEnabledPresets(context)) {
            val type = try {
                AiPrefs.PresetType.valueOf(preset.type)
            } catch (_: Exception) {
                continue
            }
            val chip = TextView(context).apply {
                text = AiPresetManager.displayName(preset)
                textSize = 13f
                setTextColor(chipColor)
                setSingleLine(true)
                background = pillGradient(pillColor)
                setPadding(dp(14), dp(8), dp(14), dp(8))
                isClickable = true
                isFocusable = true
                setOnClickListener { onChipClick?.invoke(type) }
            }
            val lp = LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT).apply { marginEnd = dp(8) }
            chipContainer.addView(chip, lp)
        }
    }

    /** Show/hide request progress. [percent] null = indeterminate. */
    fun setProcessingState(processing: Boolean, percent: Int?) {
        statusRow.visibility = if (processing) View.VISIBLE else View.GONE
        if (!processing) return
        if (percent != null && percent in 1..99) {
            statusProgress.isIndeterminate = false
            statusProgress.progress = percent
            statusText.text = "$percent%"
        } else {
            statusProgress.isIndeterminate = true
            statusText.text = ""
        }
    }

    /** True while the prompt field has focus — LatinIME routes keys here instead of the app. */
    fun isPromptFocused(): Boolean = promptFocused

    /** Drop focus from the prompt field (panel hidden / keyboard dismissed). */
    fun clearPromptFocus() {
        promptFocused = false
        promptEdit.clearFocus()
        pendingHighSurrogate = null
    }

    /**
     * Consume one key code from the IME's own keyboard while the prompt field is
     * focused. Characters are inserted into the field, Enter submits, backspace
     * deletes. Returns true if the event was handled here.
     */
    fun consumeKeyEvent(code: Int): Boolean {
        if (!promptFocused) return false
        return when (code) {
            KeyCode.DELETE -> {
                deleteLastChar()
                true
            }
            KeyCode.SHIFT_ENTER, '\n'.code -> {
                submitPrompt()
                true
            }
            else -> if (code > 0) {
                appendChar(code)
                true
            } else false
        }
    }

    private fun appendChar(code: Int) {
        val ch = code.toChar()
        val pending = pendingHighSurrogate
        when {
            ch.isHighSurrogate() -> {
                pendingHighSurrogate = ch
                return
            }
            ch.isLowSurrogate() && pending != null -> {
                pendingHighSurrogate = null
                insertText("$pending$ch")
            }
            else -> {
                pendingHighSurrogate = null
                insertText(ch.toString())
            }
        }
    }

    private fun insertText(text: String) {
        val editable = promptEdit.text ?: return
        val start = promptEdit.selectionStart.coerceAtLeast(0)
        val end = promptEdit.selectionEnd.coerceAtLeast(0)
        val insertAt = if (start in 0..editable.length && end in 0..editable.length) minOf(start, end) else editable.length
        editable.insert(insertAt, text)
        promptEdit.setSelection(insertAt + text.length)
    }

    private fun deleteLastChar() {
        val editable = promptEdit.text ?: return
        val sel = promptEdit.selectionStart
        val cursor = if (sel in 0 until editable.length) sel else editable.length
        if (cursor <= 0) return
        var start = cursor - 1
        // delete a full surrogate pair (emoji) in one go
        if (start > 0 && editable[start].isLowSurrogate() && editable[start - 1].isHighSurrogate()) start -= 1
        editable.delete(start, cursor)
        promptEdit.setSelection(start)
    }

    private fun submitPrompt() {
        val text = promptEdit.text?.toString()?.trim().orEmpty()
        if (text.isEmpty()) return
        promptEdit.text?.clear()
        pendingHighSurrogate = null
        onPromptSubmit?.invoke(text)
    }

    private fun pillGradient(color: Int): GradientDrawable = GradientDrawable().apply {
        cornerRadius = dp(18).toFloat()
        // ~45% alpha so the pill reads on any theme
        setColor(Color.argb(120, Color.red(color), Color.green(color), Color.blue(color)))
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()
}
