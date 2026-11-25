package com.github.kr328.clash.design.custom.editor

/** Pure YAML rename planning. Offsets are UTF-16 indices, matching Kotlin String and EditText. */
object YamlRename {
    data class Change(val start: Int, val end: Int, val replacement: String)
    data class Location(val line: Int, val column: Int, val reason: String)
    data class Plan(
        val oldName: String? = null,
        val changes: List<Change> = emptyList(),
        val definitionCount: Int = 0,
        val referenceCount: Int = 0,
        val untouched: List<Location> = emptyList(),
        val otherOccurrences: List<Int> = emptyList(),
        val error: String? = null,
    ) {
        val isValid: Boolean get() = error == null && changes.isNotEmpty()
    }

    private data class Line(val number: Int, val start: Int, val text: String)
    internal data class ValueRange(val start: Int, val end: Int, val quote: Char?)

    private val builtIns = setOf("DIRECT", "REJECT", "REJECT-DROP", "PASS", "COMPATIBLE", "GLOBAL")

    fun plan(text: String, caretOffset: Int, newName: String): Plan {
        val links = YamlLint.links(text)
        if (newName.isBlank()) return Plan(error = "新名字不能为空")
        if (newName.indexOf(',') >= 0 || newName.indexOf('\n') >= 0 || newName.indexOf('\r') >= 0) {
            return Plan(error = "新名字不能包含逗号或换行")
        }

        val lines = lines(text)
        val offset = caretOffset.coerceIn(0, text.length)
        val caretLine = lineAt(lines, offset)
        val caretColumn = offset - lines[caretLine].start

        var selectedName: String? = null
        var selectedByReference = false

        val refsAtCaretLine = links.refs.filter {
            it.line == caretLine && (it.kind == "proxy" || it.kind == "policy")
        }
        for (link in refsAtCaretLine) {
            val occurrences = occurrences(lines[caretLine].text, link.name)
            val hit = occurrences.any { caretColumn >= it.start && caretColumn <= it.end }
            if (hit) {
                val resolved = YamlLint.resolve(links, text, offset)
                if (resolved.link?.name == link.name &&
                    (resolved.link.kind == "proxy" || resolved.link.kind == "policy")
                ) {
                    selectedName = link.name
                    selectedByReference = true
                    break
                }
            }
        }

        if (selectedName == null) {
            for ((name, lineNumber) in links.names) {
                if (lineNumber != caretLine) continue
                val value = definitionValue(lines[caretLine].text, name) ?: continue
                if (caretColumn >= value.start && caretColumn <= value.end) {
                    selectedName = name
                    break
                }
            }
        }

        val oldName = selectedName ?: return Plan(error = "光标没有位于可重命名的组名、节点名或引用上")
        if (oldName in builtIns) return Plan(oldName = oldName, error = "内置名称不能重命名：$oldName")
        if (newName == oldName) return Plan(oldName = oldName, error = "新名字与旧名字相同")
        links.names[newName]?.let { line ->
            return Plan(oldName = oldName, error = "新名字已在第 " + (line + 1) + " 行定义")
        }

        val definitionLine = links.names[oldName]
            ?: return Plan(oldName = oldName, error = "找不到名称定义")
        val definition = definitionValue(lines[definitionLine].text, oldName)
            ?: return Plan(oldName = oldName, error = "无法确认定义位置")
        val changes = ArrayList<Change>()
        val untouched = ArrayList<Location>()
        val defQuote = definition.quote
        val defReplacement = encode(newName, defQuote)
        changes += Change(lines[definitionLine].start + definition.start, lines[definitionLine].start + definition.end, defReplacement)

        val refsByLine = links.refs
            .filter { it.name == oldName && (it.kind == "proxy" || it.kind == "policy") }
            .groupBy { it.line }

        for ((lineNumber, refs) in refsByLine) {
            if (lineNumber !in lines.indices) continue
            val line = lines[lineNumber]
            val candidates = occurrences(line.text, oldName).filterNot {
                lineNumber == definitionLine && it.start == definition.start && it.end == definition.end
            }
            if (refs.size != 1 || candidates.size != 1) {
                untouched += Location(lineNumber + 1, candidates.firstOrNull()?.start ?: 0, "这一行有多个候选位置，无法唯一确认")
                continue
            }
            val range = candidates.single()
            val quote = quoteAt(line.text, range.start, range.end)
            changes += Change(line.start + range.start, line.start + range.end, encode(newName, quote))
        }

        val ordered = changes.sortedByDescending { it.start }
        for (i in 1 until ordered.size) {
            if (ordered[i - 1].start < ordered[i].end) {
                return Plan(oldName = oldName, error = "候选修改位置重叠，已取消重命名")
            }
        }

        val changedRanges = changes.map { it.start until it.end }
        val other = ArrayList<Int>()
        for (line in lines) {
            val found = occurrences(line.text, oldName)
            if (found.any { range ->
                    val absolute = line.start + range.start
                    changedRanges.none { absolute in it }
                }
            ) {
                if (other.size < 20) other += line.number + 1
            }
        }

        return Plan(
            oldName = oldName,
            changes = ordered,
            definitionCount = 1,
            referenceCount = changes.size - 1,
            untouched = untouched.take(20),
            otherOccurrences = other,
        )
    }

    fun apply(text: String, changes: List<Change>): String {
        val result = StringBuilder(text)
        changes.sortedByDescending { it.start }.forEach { change ->
            result.replace(change.start, change.end, change.replacement)
        }
        return result.toString()
    }

    private fun lines(text: String): List<Line> {
        val result = ArrayList<Line>()
        var start = 0
        var number = 0
        while (start <= text.length) {
            val end = text.indexOf('\n', start).let { if (it < 0) text.length else it }
            val raw = text.substring(start, end)
            result += Line(number++, start, if (raw.endsWith("\r")) raw.dropLast(1) else raw)
            if (end == text.length) break
            start = end + 1
        }
        return result
    }

    private fun lineAt(lines: List<Line>, offset: Int): Int {
        var low = 0
        var high = lines.lastIndex
        while (low < high) {
            val mid = (low + high + 1) ushr 1
            if (lines[mid].start <= offset) low = mid else high = mid - 1
        }
        return low
    }

    /** Finds the scalar value belonging to a YAML key named exactly "name". */
    private fun definitionValue(line: String, expected: String): ValueRange? {
        var found: ValueRange? = null
        var i = 0
        var quote = '\u0000'
        while (i < line.length) {
            val c = line[i]
            if (quote != '\u0000') {
                if (c == '\\' && quote == '"' && i + 1 < line.length) { i += 2; continue }
                if (c == quote) quote = '\u0000'
                i++
                continue
            }
            if (c == '#') break
            val keyStart = i
            var keyEnd = i
            var keyQuote: Char? = null
            if (c == '"' || c == '\'') {
                keyQuote = c
                keyEnd = line.indexOf(c, i + 1)
                if (keyEnd < 0) { i++; continue }
                if (line.substring(i + 1, keyEnd) != "name") { i = keyEnd + 1; continue }
            } else if (line.startsWith("name", i)) {
                keyEnd = i + 4
            } else {
                if (c == '"' || c == '\'') quote = c
                i++
                continue
            }
            val previous = if (keyStart == 0) '\u0000' else line[keyStart - 1]
            if (previous != '\u0000' && !previous.isWhitespace() && previous != '{' && previous != ',' && previous != '-') {
                i = keyEnd + 1
                continue
            }
            var colon = keyEnd + if (keyQuote == null) 0 else 1
            while (colon < line.length && line[colon].isWhitespace()) colon++
            if (colon >= line.length || line[colon] != ':') { i = keyEnd + 1; continue }
            var valueStart = colon + 1
            while (valueStart < line.length && line[valueStart].isWhitespace()) valueStart++
            if (valueStart >= line.length || line[valueStart] == '#' || line[valueStart] == ',' || line[valueStart] == '}') {
                i = valueStart + 1
                continue
            }
            val q = line[valueStart].takeIf { it == '"' || it == '\'' }
            var valueEnd: Int
            if (q != null) {
                var p = valueStart + 1
                while (p < line.length) {
                    if (line[p] == '\\' && q == '"' && p + 1 < line.length) { p += 2; continue }
                    if (line[p] == q) break
                    p++
                }
                if (p >= line.length) { i = valueStart + 1; continue }
                valueEnd = p
                val decoded = decode(line.substring(valueStart + 1, valueEnd), q)
                if (decoded == expected) {
                    val candidate = ValueRange(valueStart + 1, valueEnd, q)
                    if (found != null) return null
                    found = candidate
                }
                i = p + 1
            } else {
                valueEnd = valueStart
                while (valueEnd < line.length && line[valueEnd] != ',' && line[valueEnd] != '}' && line[valueEnd] != '#') valueEnd++
                while (valueEnd > valueStart && line[valueEnd - 1].isWhitespace()) valueEnd--
                if (line.substring(valueStart, valueEnd) == expected) {
                    val candidate = ValueRange(valueStart, valueEnd, null)
                    if (found != null) return null
                    found = candidate
                }
                i = maxOf(valueEnd, valueStart) + 1
            }
        }
        return found
    }

    private fun decode(value: String, quote: Char): String {
        if (quote == '\'') return value.replace("''", "'")
        val out = StringBuilder()
        var i = 0
        while (i < value.length) {
            if (value[i] == '\\' && i + 1 < value.length) {
                val next = value[i + 1]
                out.append(when (next) { 'n' -> '\n'; 'r' -> '\r'; 't' -> '\t'; else -> next })
                i += 2
            } else out.append(value[i++])
        }
        return out.toString()
    }

    /** Exact scalar matches outside comments; quoted names are allowed, substrings of longer names are not. */
    internal fun occurrences(line: String, name: String): List<ValueRange> {
        val result = ArrayList<ValueRange>()
        var commentAt = line.length
        var quote = '\u0000'
        var i = 0
        while (i < line.length) {
            val c = line[i]
            if (quote != '\u0000') {
                if (c == '\\' && quote == '"' && i + 1 < line.length) { i += 2; continue }
                if (c == quote) quote = '\u0000'
                i++
                continue
            }
            if ((c == '"' || c == '\'') && (i == 0 || line[i - 1].isWhitespace() || line[i - 1] == ':' || line[i - 1] == '[' || line[i - 1] == ',')) {
                quote = c
                i++
                continue
            }
            if (c == '#' && (i == 0 || line[i - 1].isWhitespace())) { commentAt = i; break }
            i++
        }
        var at = line.indexOf(name)
        while (at >= 0 && at < commentAt) {
            val end = at + name.length
            val before = if (at == 0) '\u0000' else line[at - 1]
            val after = if (end >= line.length) '\u0000' else line[end]
            val leftOk = before == '\u0000' || before.isWhitespace() || before in "[:{,[\\\"'"
            val rightOk = after == '\u0000' || after.isWhitespace() || after in ",]}\\\"'"
            if (leftOk && rightOk) result += ValueRange(at, end, null)
            at = line.indexOf(name, at + 1)
        }
        return result
    }

    private fun quoteAt(line: String, start: Int, end: Int): Char? {
        if (start > 0 && line[start - 1] == '"' && end < line.length && line[end] == '"') return '"'
        if (start > 0 && line[start - 1] == '\'' && end < line.length && line[end] == '\'') return '\''
        return null
    }

    internal fun encode(value: String, originalQuote: Char?): String {
        // The recorded range excludes existing quote delimiters, so preserve only their escaped contents.
        if (originalQuote == '\'') return value.replace("'", "''")
        if (originalQuote == '"') return doubleQuotedContent(value)
        if (!needsQuotes(value)) return value
        return doubleQuoted(value)
    }

    internal fun needsQuotes(value: String): Boolean {
        if (value != value.trim()) return true
        if (value.contains(": ") || value.contains(" #")) return true
        if (value.firstOrNull() in listOf('&', '*', '!', '|', '>', '\'', '"', '%', '@', '`', '[', '{')) return true
        if (value.equals("true", true) || value.equals("false", true) || value.equals("null", true) || value == "~") return true
        return value.toDoubleOrNull() != null
    }

    private fun doubleQuoted(value: String): String = "\"" + doubleQuotedContent(value) + "\""

    private fun doubleQuotedContent(value: String): String = buildString(value.length) {
        value.forEach { c ->
            when (c) {
                '\\' -> append("\\\\")
                '"' -> append("\\\"")
                '\n' -> append("\\n")
                '\r' -> append("\\r")
                '\t' -> append("\\t")
                else -> append(c)
            }
        }
    }
}
