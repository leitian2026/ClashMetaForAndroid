package com.github.kr328.clash.design.custom

import android.content.Context
import android.content.SharedPreferences
import android.text.InputType
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.widget.TextViewCompat
import com.github.kr328.clash.design.R
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout

/**
 * CUSTOM #99: the dialog title and IP input controls, extracted from IpTestDialog.ask
 * without changing their behavior.
 */
internal class IpTestDialogInput private constructor(
    val hint: TextView,
    val titleRow: LinearLayout,
    val inputLabel: TextView,
    val inputField: TextInputLayout,
    val input: TextInputEditText,
    val inputHeader: View,
    private val inputHistory: IpTestInputHistory,
) {
    fun updateHeader() = inputHistory.updateHeader()

    fun flush() = inputHistory.flush()

    fun dismissOnCancellation(dialog: android.app.Dialog) = inputHistory.dismissOnCancellation(dialog)

    private data class State(var hintExpanded: Boolean = false)

    companion object {
        fun create(
            context: Context,
            prefs: SharedPreferences,
            presetIps: String?,
            density: Float,
            pad: Int,
        ): IpTestDialogInput {
            val state = State()
            val hint = TextView(context).apply {
                setText(R.string.iptest_dialog_hint)
                textSize = 13f
                alpha = 0.7f
                visibility = View.GONE
            }

            val titleRow = LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(pad, 0, pad, 0)
            }
            val titleText = TextView(context).apply {
                setText(R.string.iptest_dialog_title)
                TextViewCompat.setTextAppearance(this, com.google.android.material.R.style.TextAppearance_MaterialComponents_Headline6)
                isSingleLine = true
                ellipsize = android.text.TextUtils.TruncateAt.END
                val dialogWidth = minOf(context.resources.displayMetrics.widthPixels, (560 * density).toInt()) -
                    (2 * pad) - (60 * density).toInt()
                maxWidth = dialogWidth.coerceAtLeast((80 * density).toInt())
            }
            val helpButton = TextView(context).apply {
                setText(R.string.iptest_dialog_help)
                textSize = 14f
                isClickable = true
                isFocusable = true
                setPadding((8 * density).toInt(), (8 * density).toInt(), 0, (8 * density).toInt())
                setOnClickListener {
                    state.hintExpanded = !state.hintExpanded
                    hint.visibility = if (state.hintExpanded) View.VISIBLE else View.GONE
                }
            }
            titleRow.addView(titleText, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT))
            titleRow.addView(helpButton, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT))

            val inputLabel = TextView(context).apply {
                setText(R.string.iptest_test_box_label)
                textSize = 13f
                alpha = 0.7f
                setPadding(0, pad / 2, 0, 0)
            }

            val inputField = LayoutInflater.from(context)
                .inflate(R.layout.dialog_iptest_outlined_field, null, false) as TextInputLayout
            val input = inputField.findViewById<TextInputEditText>(R.id.iptest_outlined_input).apply {
                setHint(R.string.iptest_ips_hint)
                minLines = 1
                maxLines = 12
                gravity = Gravity.TOP or Gravity.START
                inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE or
                    InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
                isVerticalScrollBarEnabled = true
                setHorizontallyScrolling(false)

                try {
                    // CUSTOM.md #156 step 4: a supplied preset wins for this dialog only; otherwise keep the manual history.
                    setText(presetIps ?: (prefs.getString("ip_text", "") ?: ""))
                } catch (_: Exception) {
                }
            }

            // CUSTOM.md #43: input history and its header live outside this large suspend function.
            val inputHistory = IpTestInputHistory(context, prefs, input, inputLabel, presetIps == null, density)

            // CUSTOM.md #43: draw saved-address hints inside matching input rows using the cached snapshot.
            IpTestSavedIpHint.create(context, input)

            return IpTestDialogInput(hint, titleRow, inputLabel, inputField, input, inputHistory.header, inputHistory)
        }
    }
}
