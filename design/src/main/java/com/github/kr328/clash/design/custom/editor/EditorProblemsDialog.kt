package com.github.kr328.clash.design.custom.editor

import android.content.Context
import com.github.kr328.clash.design.R
import com.google.android.material.dialog.MaterialAlertDialogBuilder

// CUSTOM: #177 (not upstream): displays the editor's YAML problem list and owns its descriptions.
interface EditorProblemsHost {
    fun problemContext(): Context
    fun problemItems(): List<Diagnostic>
    fun selectProblem(diagnostic: Diagnostic)
    fun currentProblemText(): String
    fun canReplaceProblemText(): Boolean
    fun replaceProblemText(replacement: String): Boolean
    fun refreshProblemUi()
    fun launchProblemTask(task: suspend () -> Unit)
    fun jumpToProblemLine(line: Int)
    fun showProblemToast(message: String)
}

class EditorProblemsDialog(private val host: EditorProblemsHost) {
    private val context get() = host.problemContext()

    fun describe(d: Diagnostic): String = when (d.kind) {
        DiagKind.Tab -> context.getString(R.string.editor_problem_tab)
        DiagKind.IndentDeeper -> context.getString(R.string.editor_problem_deeper)
        DiagKind.IndentMisaligned -> context.getString(R.string.editor_problem_misaligned)
        DiagKind.DupKey -> context.getString(R.string.editor_problem_dup_key, d.arg, d.other)
        DiagKind.DupName -> context.getString(R.string.editor_problem_dup_name, d.arg, d.other)
        DiagKind.MissingProxy -> context.getString(R.string.editor_problem_missing_proxy, d.arg)
        DiagKind.MissingPolicy -> context.getString(R.string.editor_problem_missing_policy, d.arg)
        DiagKind.MissingProvider -> context.getString(R.string.editor_problem_missing_provider, d.arg)
        DiagKind.MissingRuleSet -> context.getString(R.string.editor_problem_missing_ruleset, d.arg)
    }

    fun showProblems() {
        val all = host.problemItems()
        if (all.isEmpty()) {
            host.showProblemToast(context.getString(R.string.editor_problems_none))
            return
        }
        val shown = all.take(300)
        val items = shown.map {
            context.getString(R.string.editor_problem_item, it.line + 1, describe(it))
        }.toTypedArray()
        MaterialAlertDialogBuilder(context)
            .setTitle(context.getString(R.string.editor_problems_title, all.size, shown.size))
            .setItems(items) { _, which -> host.selectProblem(shown[which]) }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }
}
