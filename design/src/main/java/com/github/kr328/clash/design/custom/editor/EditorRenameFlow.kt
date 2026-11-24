package com.github.kr328.clash.design.custom.editor

import android.content.Context
import android.text.InputType
import android.view.ViewGroup
import android.widget.LinearLayout
import com.github.kr328.clash.design.R
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout

/** Two-step rename UI. The callback is invoked only after the user confirms the complete plan. */
object EditorRenameFlow {
    fun show(
        context: Context,
        text: String,
        caretOffset: Int,
        onConfirm: (String, YamlRename.Plan) -> Unit,
    ) {
        val initial = YamlRename.plan(text, caretOffset, "\u0000")
        val oldName = initial.oldName
        if (oldName == null) {
            MaterialAlertDialogBuilder(context)
                .setMessage(context.getString(R.string.editor_rename_error_position))
                .setPositiveButton(android.R.string.ok, null)
                .show()
            return
        }

        val input = TextInputEditText(context).apply {
            inputType = InputType.TYPE_CLASS_TEXT
            setText(oldName)
            setSelection(text?.length ?: oldName.length)
        }
        val inputLayout = TextInputLayout(context).apply {
            hint = context.getString(R.string.editor_rename_new_name)
            addView(
                input,
                ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT),
            )
        }
        val container = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            val padding = (20 * context.resources.displayMetrics.density).toInt()
            setPadding(padding, padding / 2, padding, 0)
            addView(inputLayout, LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            ))
        }

        val dialog = MaterialAlertDialogBuilder(context)
            .setTitle(R.string.editor_rename_title)
            .setView(container)
            .setNegativeButton(android.R.string.cancel, null)
            .setPositiveButton(android.R.string.ok, null)
            .create()
        dialog.setOnShowListener {
            dialog.getButton(android.app.AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val newName = input.text?.toString().orEmpty()
                val plan = YamlRename.plan(text, caretOffset, newName)
                if (plan.error != null) {
                    inputLayout.error = errorText(context, plan.error)
                    return@setOnClickListener
                }
                inputLayout.error = null
                dialog.dismiss()
                confirm(context, text, plan, onConfirm)
            }
        }
        dialog.show()
        input.requestFocus()
    }

    private fun confirm(
        context: Context,
        text: String,
        plan: YamlRename.Plan,
        onConfirm: (String, YamlRename.Plan) -> Unit,
    ) {
        val message = buildString {
            append(context.getString(
                R.string.editor_rename_confirm_summary,
                plan.changes.size,
                plan.referenceCount,
            ))
            if (plan.untouched.isNotEmpty()) {
                append("\n\n")
                append(context.getString(R.string.editor_rename_untouched))
                plan.untouched.take(20).forEach {
                    append("\n")
                    append(context.getString(R.string.editor_rename_location, it.line, it.column + 1))
                }
            }
            if (plan.otherOccurrences.isNotEmpty()) {
                append("\n\n")
                append(context.getString(R.string.editor_rename_other))
                plan.otherOccurrences.take(20).forEach { line ->
                    append("\n")
                    append(context.getString(R.string.editor_rename_line, line))
                }
            }
        }
        MaterialAlertDialogBuilder(context)
            .setTitle(R.string.editor_rename_confirm_title)
            .setMessage(message)
            .setNegativeButton(android.R.string.cancel, null)
            .setPositiveButton(android.R.string.ok) { _, _ ->
                onConfirm(YamlRename.apply(text, plan.changes), plan)
            }
            .show()
    }

    private fun errorText(context: Context, error: String): String = when {
        error.contains("不能为空") -> context.getString(R.string.editor_rename_error_empty)
        error.contains("相同") -> context.getString(R.string.editor_rename_error_same)
        error.contains("已在第") -> {
            val line = error.substringAfter("第 ").substringBefore(" 行").toIntOrNull() ?: 0
            context.getString(R.string.editor_rename_error_duplicate, line)
        }
        error.contains("内置名称") -> context.getString(R.string.editor_rename_error_builtin)
        error.contains("逗号或换行") -> context.getString(R.string.editor_rename_error_characters)
        else -> context.getString(R.string.editor_rename_error_position)
    }
}
