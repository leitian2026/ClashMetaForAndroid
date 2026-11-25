package com.github.kr328.clash.design.custom.editor

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import com.github.kr328.clash.design.R

// CUSTOM (CUSTOM.md #177, not upstream): computes and applies a selected YAML repair outside EditorDesign.
class EditorFixController(private val host: EditorProblemsHost) {
    fun selectProblem(diagnostic: Diagnostic) {
        host.launchProblemTask { prepare(diagnostic) }
    }

    private suspend fun prepare(diagnostic: Diagnostic) {
        if (!host.canReplaceProblemText()) {
            rejectLarge(diagnostic)
            return
        }
        val snapshot = host.currentProblemText()
        if (snapshot.length > MAX_TEXT || snapshot.count { it == '\n' } >= MAX_LINES) {
            rejectLarge(diagnostic)
            return
        }
        val plan = withContext(Dispatchers.Default) { YamlFix.plan(snapshot, diagnostic) }
        if (plan == null || plan.candidates.isEmpty()) {
            host.jumpToProblemLine(diagnostic.line)
            host.showProblemToast(host.problemContext().getString(R.string.editor_fix_no_suggestion))
            return
        }
        EditorFixDialog(
            host.problemContext(),
            diagnostic.line,
            EditorProblemsDialog(host).describe(diagnostic),
            plan,
            onApply = { candidate -> host.launchProblemTask { apply(snapshot, diagnostic, candidate) } },
            onJump = { host.jumpToProblemLine(diagnostic.line) },
        ).show()
    }

    private suspend fun apply(snapshot: String, diagnostic: Diagnostic, candidate: FixCandidate) {
        if (host.currentProblemText() != snapshot) {
            host.showProblemToast(host.problemContext().getString(R.string.editor_fix_changed))
            return
        }
        val replacement = withContext(Dispatchers.Default) {
            if (YamlFix.validated(snapshot, diagnostic, candidate)) YamlRename.apply(snapshot, candidate.changes) else null
        }
        if (host.currentProblemText() != snapshot) {
            host.showProblemToast(host.problemContext().getString(R.string.editor_fix_changed))
            return
        }
        if (replacement == null || !host.replaceProblemText(replacement)) {
            host.showProblemToast(host.problemContext().getString(R.string.editor_fix_too_big))
            return
        }
        host.refreshProblemUi()
        host.showProblemToast(host.problemContext().getString(R.string.editor_fix_done))
    }

    private fun rejectLarge(diagnostic: Diagnostic) {
        host.jumpToProblemLine(diagnostic.line)
        host.showProblemToast(host.problemContext().getString(R.string.editor_fix_too_big))
    }

    private companion object {
        const val MAX_TEXT = 150_000
        const val MAX_LINES = 3000
    }
}
