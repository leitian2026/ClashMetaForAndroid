package com.github.kr328.clash.design.custom.editor

import java.text.Normalizer
import java.util.Locale

// CUSTOM: #177 (not upstream): suggest only uniquely located, syntax-checked reference repairs.
object YamlFixName {
    private data class NameChoice(val name: String, val line: Int, val builtIn: Boolean = false)
    private data class Ranked(val choice: NameChoice, val distance: Int, val prefix: Boolean)

    fun plan(text: String, diagnostic: Diagnostic): FixPlan? {
        val expectedKind = when (diagnostic.kind) {
            DiagKind.MissingProxy -> "proxy"
            DiagKind.MissingPolicy -> "policy"
            DiagKind.MissingProvider -> "use"
            DiagKind.MissingRuleSet -> "ruleset"
            else -> return null
        }
        val lines = YamlFix.splitLines(text)
        val line = lines.getOrNull(diagnostic.line) ?: return null
        val links = YamlLint.links(text)
        val references = links.refs.filter { it.line == diagnostic.line && it.kind == expectedKind && it.name == diagnostic.arg }
        if (references.isEmpty()) return null
        val occurrences = YamlRename.occurrences(line.text, diagnostic.arg)
        if (occurrences.size != 1) return null
        val range = occurrences.single()
        val quote = when {
            range.start > 0 && range.end < line.text.length &&
                line.text[range.start - 1] == '"' && line.text[range.end] == '"' -> '"'
            range.start > 0 && range.end < line.text.length &&
                line.text[range.start - 1] == '\'' && line.text[range.end] == '\'' -> '\''
            else -> null
        }
        val pool = when (expectedKind) {
            "proxy", "policy" -> buildList {
                links.names.forEach { (name, definition) -> add(NameChoice(name, definition)) }
                YamlLint.builtin.forEach { add(NameChoice(it, -1, true)) }
            }
            "use" -> links.providers.map { NameChoice(it.key, it.value) }
            else -> links.ruleProviders.map { NameChoice(it.key, it.value) }
        }.distinctBy { it.name }
        val targetKey = normalize(diagnostic.arg)
        val exact = pool.filter { normalize(it.name) == targetKey }
        val choices = if (exact.isNotEmpty()) {
            exact.map { Ranked(it, 0, false) }
        } else {
            fuzzyChoices(targetKey, pool)
        }
        val candidates = ArrayList<FixCandidate>()
        for (ranked in choices) {
            val replacement = YamlRename.encode(ranked.choice.name, quote)
            val change = YamlRename.Change(line.start + range.start, line.start + range.end, replacement)
            val candidate = FixCandidate(
                listOf(change),
                FixDescription(
                    FixDescriptionKind.Rename,
                    newName = ranked.choice.name,
                    definitionLine = ranked.choice.line + 1,
                    builtIn = ranked.choice.builtIn,
                ),
            )
            if (YamlFix.validated(text, diagnostic, candidate)) candidates += candidate
        }
        if (diagnostic.kind == DiagKind.MissingProxy || diagnostic.kind == DiagKind.MissingProvider) {
            deleteCandidate(text, diagnostic, line)?.let { candidates += it }
        }
        if (candidates.isEmpty()) return null
        val namesCount = candidates.count { it.description.kind == FixDescriptionKind.Rename }
        val defaultIndex = when {
            exact.size == 1 && namesCount == 1 -> 0
            exact.size > 1 -> null
            choices.size == 1 && namesCount == 1 -> 0
            else -> null
        }
        return FixPlan(candidates, defaultIndex)
    }

    private fun fuzzyChoices(target: String, pool: List<NameChoice>): List<Ranked> {
        if (target.length < 3) return emptyList()
        val edits = ArrayList<Ranked>()
        val prefixes = ArrayList<Ranked>()
        for (choice in pool) {
            val key = normalize(choice.name)
            if (key.length < 3 || kotlin.math.abs(key.length - target.length) > 2) continue
            val distance = editDistance(target, key, if (target.length <= 5) 1 else 2)
            if (distance >= 0) edits += Ranked(choice, distance, false)
            else if (target.startsWith(key) || key.startsWith(target)) {
                prefixes += Ranked(choice, kotlin.math.abs(target.length - key.length), true)
            }
        }
        val sortedEdits = edits.sortedWith(compareBy<Ranked> { it.distance }.thenBy { it.choice.line }.thenBy { it.choice.name })
        val sortedPrefixes = prefixes.sortedWith(compareBy<Ranked> { it.distance }.thenBy { it.choice.line }.thenBy { it.choice.name })
        return (sortedEdits + sortedPrefixes).take(3)
    }

    private fun normalize(value: String): String = Normalizer.normalize(value, Normalizer.Form.NFKC)
        .lowercase(Locale.ROOT)
        .filterNot { Character.isWhitespace(it) || it == '\u00A0' || it == '\u3000' ||
            it == '\u200B' || it == '\u200C' || it == '\u200D' || it == '\uFEFF' }

    /** Returns -1 when the bounded edit distance exceeds the limit. */
    private fun editDistance(a: String, b: String, limit: Int): Int {
        if (kotlin.math.abs(a.length - b.length) > limit) return -1
        var previous = IntArray(b.length + 1) { it }
        for (i in 1..a.length) {
            val current = IntArray(b.length + 1)
            current[0] = i
            var rowMin = current[0]
            val from = maxOf(1, i - limit)
            val to = minOf(b.length, i + limit)
            for (j in 1 until from) current[j] = limit + 1
            for (j in from..to) {
                val cost = if (a[i - 1] == b[j - 1]) 0 else 1
                current[j] = minOf(previous[j] + 1, current[j - 1] + 1, previous[j - 1] + cost)
                rowMin = minOf(rowMin, current[j])
            }
            for (j in to + 1..b.length) current[j] = limit + 1
            if (rowMin > limit) return -1
            previous = current
        }
        return previous[b.length].takeIf { it <= limit } ?: -1
    }

    private fun deleteCandidate(
        text: String,
        diagnostic: Diagnostic,
        line: YamlFix.SourceLine,
    ): FixCandidate? {
        val source = line.text
        val indent = source.takeWhile { it == ' ' }.length
        val rest = source.drop(indent)
        val match = Regex("""^-\s*(?:"([^"]*)"|'((?:[^']|'')*)'|([^\s#]+))\s*(?:#.*)?$""").matchEntire(rest)
            ?: return null
        val itemName = match.groupValues.drop(1).firstOrNull { it.isNotEmpty() } ?: return null
        if (itemName != diagnostic.arg) return null
        val lines = YamlFix.splitLines(text)
        val parentIndex = (diagnostic.line - 1 downTo 0).firstOrNull { index ->
            val parentLine = lines[index].text
            parentLine.isNotBlank() && !parentLine.trimStart().startsWith("#") &&
                parentLine.takeWhile { it == ' ' }.length < indent
        } ?: return null
        val parentIndent = lines[parentIndex].text.takeWhile { it == ' ' }.length
        var siblings = 0
        for (index in parentIndex + 1 until lines.size) {
            val other = lines[index].text
            if (other.isBlank() || other.trimStart().startsWith("#")) continue
            val otherIndent = other.takeWhile { it == ' ' }.length
            if (otherIndent <= parentIndent) break
            if (index != diagnostic.line && otherIndent == indent &&
                other.drop(indent).startsWith("-") && meaningfulItem(other.drop(indent))
            ) siblings++
        }
        if (siblings == 0) return null
        val end = line.start + line.text.length + line.ending.length
        val candidate = FixCandidate(
            listOf(YamlRename.Change(line.start, end, "")),
            FixDescription(FixDescriptionKind.DeleteLine, deletedLine = diagnostic.line + 1),
        )
        return candidate.takeIf { YamlFix.validated(text, diagnostic, it) }
    }

    private fun meaningfulItem(value: String): Boolean =
        value.startsWith("-") && value.drop(1).trim().isNotEmpty() && !value.drop(1).trimStart().startsWith("#")
}
