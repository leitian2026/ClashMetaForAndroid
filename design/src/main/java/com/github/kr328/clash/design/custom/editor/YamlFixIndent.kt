package com.github.kr328.clash.design.custom.editor

// CUSTOM (CUSTOM.md #177, not upstream): conservative block-indent repair candidates.
object YamlFixIndent {
    fun plan(text: String, diagnostic: Diagnostic): FixPlan? {
        if (diagnostic.kind != DiagKind.IndentDeeper && diagnostic.kind != DiagKind.IndentMisaligned) return null
        val lines = YamlFix.splitLines(text)
        val lineNumber = diagnostic.line
        val current = lines.getOrNull(lineNumber) ?: return null
        val oldIndent = indent(current.text)
        val previous = (lineNumber - 1 downTo 0).firstOrNull { meaningful(lines[it].text) } ?: return null
        val parent = lines[previous].text
        val levels = ArrayList<Int>()
        var last = Int.MAX_VALUE
        for (index in previous downTo 0) {
            val value = lines[index].text
            if (!meaningful(value)) continue
            val amount = indent(value)
            if (levels.isEmpty() || amount < last) {
                levels += amount
                last = amount
                if (amount == 0) break
            }
        }
        val targets = levels.toMutableList()
        if (diagnostic.kind == DiagKind.IndentMisaligned && opensChildren(parent)) {
            targets += indent(parent) + YamlFix.detectIndentWidth(text)
        }
        val ranked = targets.distinct().filter { it != oldIndent && it >= 0 }
            .map { it to kotlin.math.abs(it - oldIndent) }
            .sortedWith(compareBy<Pair<Int, Int>> { it.second }.thenBy { it.first })
        if (ranked.isEmpty()) return null
        val maxDistance = ranked.getOrNull(1)?.second ?: ranked.first().second
        val choices = ranked.filter { it.second <= maxDistance }
        val blockEnd = blockEnd(lines, lineNumber, oldIndent)
        if (blockEnd < lineNumber) return null
        for (index in lineNumber..blockEnd) {
            val value = lines[index].text
            if (value.take(indent(value)).contains('\t')) return null
        }
        val nonEmptyCount = (lineNumber..blockEnd).count { lines[it].text.isNotBlank() }
        val candidates = ArrayList<Pair<Int, FixCandidate>>()
        for ((target, distance) in choices) {
            val delta = target - oldIndent
            if (delta == 0) continue
            val changes = ArrayList<YamlRename.Change>()
            var invalid = false
            for (index in lineNumber..blockEnd) {
                val value = lines[index].text
                if (value.isBlank()) continue
                val amount = indent(value)
                val newIndent = amount + delta
                if (newIndent < 0) { invalid = true; break }
                changes += YamlRename.Change(lines[index].start, lines[index].start + amount, " ".repeat(newIndent))
            }
            if (invalid || changes.isEmpty()) continue
            val candidate = FixCandidate(
                changes,
                FixDescription(FixDescriptionKind.Indent, oldIndent, target, (nonEmptyCount - 1).coerceAtLeast(0)),
            )
            if (YamlFix.validated(text, diagnostic, candidate)) candidates += distance to candidate
        }
        if (candidates.isEmpty()) return null
        val defaultIndex = if (candidates.size == 1 ||
            (candidates.size > 1 && candidates[0].first < candidates[1].first)
        ) 0 else null
        return FixPlan(candidates.map { it.second }, defaultIndex)
    }

    private fun indent(line: String): Int {
        var index = 0
        while (index < line.length && line[index] == ' ') index++
        return index
    }

    private fun meaningful(line: String): Boolean = line.isNotBlank() && !line.trimStart().startsWith("#")

    private fun opensChildren(line: String): Boolean {
        val value = line.substringBeforeComment().trimEnd()
        return value.endsWith(":") || value == "-"
    }

    private fun String.substringBeforeComment(): String {
        var quote = '\u0000'
        var index = 0
        while (index < length) {
            val char = this[index]
            if (quote != '\u0000') {
                if (char == '\\' && quote == '"' && index + 1 < length) index += 2
                else {
                    if (char == quote) quote = '\u0000'
                    index++
                }
            } else if ((char == '"' || char == '\'') &&
                (index == 0 || this[index - 1].isWhitespace() || this[index - 1] in ":[,{-")
            ) {
                quote = char
                index++
            } else if (char == '#' && (index == 0 || this[index - 1].isWhitespace())) {
                return substring(0, index)
            } else index++
        }
        return this
    }

    private fun blockEnd(lines: List<YamlFix.SourceLine>, start: Int, oldIndent: Int): Int {
        var lastContent = start
        for (index in start + 1 until lines.size) {
            val value = lines[index].text
            if (meaningful(value) && indent(value) <= oldIndent) break
            if (meaningful(value)) lastContent = index
        }
        return lastContent
    }
}
