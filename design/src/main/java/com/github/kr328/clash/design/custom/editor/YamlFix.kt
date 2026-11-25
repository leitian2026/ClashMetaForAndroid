package com.github.kr328.clash.design.custom.editor

// CUSTOM: #177 (not upstream): shared, Android-free repair plan types and Tab repair.
enum class FixDescriptionKind { Tabs, Indent, Rename, DeleteLine }

data class FixDescription(
    val kind: FixDescriptionKind,
    val oldIndent: Int = 0,
    val newIndent: Int = 0,
    val movedLines: Int = 0,
    val tabLines: Int = 0,
    val tabWidth: Int = 0,
    val newName: String = "",
    val definitionLine: Int = 0,
    val builtIn: Boolean = false,
    val deletedLine: Int = 0,
)

data class FixCandidate(val changes: List<YamlRename.Change>, val description: FixDescription)
data class FixPlan(val candidates: List<FixCandidate>, val defaultSelectedIndex: Int? = null)

object YamlFix {
    fun plan(text: String, diagnostic: Diagnostic): FixPlan? = when (diagnostic.kind) {
        DiagKind.Tab -> planTabs(text)
        DiagKind.IndentDeeper, DiagKind.IndentMisaligned -> YamlFixIndent.plan(text, diagnostic)
        DiagKind.MissingProxy, DiagKind.MissingPolicy, DiagKind.MissingProvider, DiagKind.MissingRuleSet ->
            YamlFixName.plan(text, diagnostic)
        else -> null
    }

    internal fun planTabs(text: String): FixPlan? {
        val tabLines = YamlLint.run(text).filter { it.kind == DiagKind.Tab }.map { it.line }.distinct()
        if (tabLines.isEmpty()) return null
        val width = detectIndentWidth(text)
        val lines = splitLines(text)
        val changes = tabLines.mapNotNull { number ->
            val line = lines.getOrNull(number) ?: return@mapNotNull null
            var end = 0
            while (end < line.text.length && (line.text[end] == ' ' || line.text[end] == '\t')) end++
            if (end == 0) return@mapNotNull null
            val old = line.text.substring(0, end)
            val replacement = old.replace("\t", " ".repeat(width))
            if (old == replacement) null else YamlRename.Change(line.start, line.start + end, replacement)
        }
        if (changes.isEmpty()) return null
        val candidate = FixCandidate(changes, FixDescription(FixDescriptionKind.Tabs, tabLines = changes.size, tabWidth = width))
        val changed = YamlRename.apply(text, changes)
        if (YamlLint.run(changed).any { it.kind == DiagKind.Tab }) return null
        return FixPlan(listOf(candidate), 0)
    }

    internal fun detectIndentWidth(text: String): Int {
        val widths = splitLines(text).mapNotNull { line ->
            val value = line.text
            if (value.isBlank() || value.trimStart().startsWith("#")) return@mapNotNull null
            var spaces = 0
            while (spaces < value.length && value[spaces] == ' ') spaces++
            spaces.takeIf { it > 0 }
        }
        val width = widths.minOrNull() ?: 2
        return if (width in 2..8) width else 2
    }

    internal data class SourceLine(val start: Int, val text: String, val ending: String)

    internal fun splitLines(text: String): List<SourceLine> {
        val result = ArrayList<SourceLine>()
        var start = 0
        while (start <= text.length) {
            val newline = text.indexOf('\n', start)
            val end = if (newline < 0) text.length else newline
            val raw = text.substring(start, end)
            val cr = raw.endsWith("\r")
            result += SourceLine(start, if (cr) raw.dropLast(1) else raw, (if (cr) "\r" else "") + if (newline >= 0) "\n" else "")
            if (newline < 0) break
            start = newline + 1
        }
        return result
    }

    internal fun isSameProblem(before: Diagnostic, after: Diagnostic): Boolean =
        before.kind == after.kind && before.line == after.line && before.arg == after.arg

    internal fun validated(text: String, diagnostic: Diagnostic, candidate: FixCandidate): Boolean {
        val before = YamlLint.run(text)
        val after = YamlLint.run(YamlRename.apply(text, candidate.changes))
        if (diagnostic.kind == DiagKind.Tab) return after.none { it.kind == DiagKind.Tab }
        if (after.any { isSameProblem(diagnostic, it) }) return false
        return after.count { it.severity == 0 } <= before.count { it.severity == 0 }
    }
}
