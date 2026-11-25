package com.github.kr328.clash.design.custom.editor

import android.content.Context
import com.github.kr328.clash.design.R
import com.google.android.material.dialog.MaterialAlertDialogBuilder

// CUSTOM (CUSTOM.md #177, not upstream): presentation only; planning and applying stay with the host.
class EditorFixDialog(
    private val context: Context,
    private val line: Int,
    private val problemDescription: String,
    private val plan: FixPlan,
    private val onApply: (FixCandidate) -> Unit,
    private val onJump: () -> Unit,
) {
    fun show() {
        if (plan.candidates.isEmpty()) return
        var selected = plan.defaultSelectedIndex
        val labels = plan.candidates.map(::describe).toTypedArray()
        val builder = MaterialAlertDialogBuilder(context)
            .setTitle(context.getString(R.string.editor_fix_title, line + 1))
            .setMessage(
                problemDescription + if (plan.candidates.size == 1) {
                    "\n\n" + labels[0]
                } else {
                    ""
                },
            )
            .setNegativeButton(android.R.string.cancel, null)
            .setNeutralButton(R.string.editor_fix_jump) { _, _ -> onJump() }
            .setPositiveButton(R.string.editor_fix_apply) { _, _ ->
                selected?.let { plan.candidates.getOrNull(it)?.let(onApply) }
            }

        if (plan.candidates.size > 1) {
            builder.setSingleChoiceItems(labels, selected ?: -1) { _, which -> selected = which }
        }
        val dialog = builder.create()
        dialog.setOnShowListener {
            dialog.getButton(androidx.appcompat.app.AlertDialog.BUTTON_POSITIVE).isEnabled = selected != null
        }
        dialog.show()
        dialog.getButton(androidx.appcompat.app.AlertDialog.BUTTON_POSITIVE).isEnabled = selected != null
        if (plan.candidates.size > 1) {
            dialog.listView?.setOnItemClickListener { _, _, which, _ ->
                selected = which
                dialog.getButton(androidx.appcompat.app.AlertDialog.BUTTON_POSITIVE).isEnabled = true
            }
        }
    }

    private fun describe(candidate: FixCandidate): String {
        val value = candidate.description
        return when (value.kind) {
            FixDescriptionKind.Tabs -> context.getString(R.string.editor_fix_tabs, value.tabLines, value.tabWidth)
            FixDescriptionKind.Indent -> context.getString(
                if (value.movedLines == 0) R.string.editor_fix_indent_single else R.string.editor_fix_indent,
                value.oldIndent,
                value.newIndent,
                value.movedLines,
            )
            FixDescriptionKind.Rename -> context.getString(
                if (value.builtIn) R.string.editor_fix_rename_builtin else R.string.editor_fix_rename,
                value.newName,
                value.definitionLine,
            )
            FixDescriptionKind.DeleteLine -> context.getString(R.string.editor_fix_delete, value.deletedLine)
        }
    }
}
