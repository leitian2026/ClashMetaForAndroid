package com.github.kr328.clash.design.custom.editor

// CUSTOM (CUSTOM.md #46, not upstream): reading the shape of a YAML text line by line, to find out where in the
// config the caret is (which key it is under, which keys the block already has). Ported from FlClash's
// `lib/features/editor/yaml_lines.dart` (GPL-3.0). No Android classes, so it can be tested on the JVM.

private val linePattern = Regex("^( *)(-(?: +|$))?(.*)$")
private val keyPattern = Regex("""^('[^']*'|"[^"]*"|[^\s#'"{\[\-][^#]*?|-[^\s#][^#]*?):(?:\s+(.*))?$""")

class YamlLine private constructor(
    val indent: Int,
    val isItem: Boolean,
    val contentColumn: Int,
    val content: String,
    val key: String?,
    val value: String?,
) {
    companion object {
        fun indentOf(text: String): Int {
            var indent = 0

            while (indent < text.length && text[indent] == ' ') indent++

            return indent
        }

        fun isItemAt(text: String, indent: Int): Boolean {
            return indent < text.length && text[indent] == '-' && (indent + 1 == text.length || text[indent + 1] == ' ')
        }

        fun parse(text: String): YamlLine? {
            val match = linePattern.find(text) ?: return null
            val content = match.groupValues[3]
            val dash = match.groups[2]?.value

            if (dash == null && (content.isEmpty() || content.startsWith("#"))) return null

            val indent = match.groupValues[1].length
            val keyMatch = keyPattern.find(content)

            return YamlLine(
                indent = indent,
                isItem = dash != null,
                contentColumn = indent + (dash?.length ?: 0),
                content = content,
                key = keyMatch?.let { unquoteYaml(it.groupValues[1]) },
                value = keyMatch?.let { unquoteYaml(it.groups[2]?.value ?: "") },
            )
        }
    }
}

fun unquoteYaml(text: String): String {
    var value = text.trim()
    val comment = if (value.startsWith("#")) 0 else value.indexOf(" #")

    if (comment >= 0) value = value.substring(0, comment).trimEnd()

    if (value.length >= 2 &&
        ((value.startsWith("'") && value.endsWith("'")) || (value.startsWith("\"") && value.endsWith("\"")))
    ) {
        return value.substring(1, value.length - 1)
    }

    return value
}

sealed class YamlStep

class YamlKeyStep(val key: String) : YamlStep()

class YamlItemStep : YamlStep()

/** The keys a block mapping already has on lines other than the caret's. */
class YamlMappingScope(val path: List<YamlStep>, val siblings: Map<String, String>)

class YamlDocumentLines(val lines: List<String>) {
    private fun lineWithin(index: Int, maxIndent: Int): YamlLine? {
        val text = lines[index]

        return if (YamlLine.indentOf(text) > maxIndent) null else YamlLine.parse(text)
    }

    /** Null when an ancestor is not a bare key the scope could hang from. */
    fun mappingScope(line: Int, column0: Int, startsItem: Boolean = false, itemIndent: Int = 0): YamlMappingScope? {
        var column = column0
        val path = ArrayList<YamlStep>()
        val siblings = LinkedHashMap<String, String>()

        collectFollowingSiblings(line, column, siblings)

        var index = line - 1
        var collecting = true

        if (startsItem) {
            path.add(YamlItemStep())
            collecting = false

            val owner = listOwner(index, itemIndent, path) ?: return null

            index = owner.first
            column = owner.second
        }

        // above a top-level key only siblings remain, read only while collecting
        while (index >= 0 && (column > 0 || collecting)) {
            val current = lineWithin(index, column)

            index--

            if (current == null || current.contentColumn > column) continue

            if (current.contentColumn == column) {
                val key = current.key

                if (collecting && key != null && !siblings.containsKey(key)) siblings[key] = current.value ?: ""

                if (!current.isItem) continue

                path.add(YamlItemStep())
                collecting = false

                val owner = listOwner(index, current.indent, path) ?: return null

                index = owner.first
                column = owner.second

                continue
            }

            val key = current.key

            if (key == null || !current.value.isNullOrEmpty()) return null

            path.add(YamlKeyStep(key))
            collecting = false

            if (current.isItem) {
                path.add(YamlItemStep())

                val owner = listOwner(index, current.indent, path) ?: return null

                index = owner.first
                column = owner.second
            } else {
                column = current.indent
            }
        }

        return YamlMappingScope(path.reversed(), siblings)
    }

    private fun listOwner(from: Int, dashColumn: Int, path: MutableList<YamlStep>): Pair<Int, Int>? {
        var index = from

        while (index >= 0) {
            val text = lines[index]

            index--

            val indent = YamlLine.indentOf(text)

            if (indent > dashColumn || (indent == dashColumn && YamlLine.isItemAt(text, indent))) continue

            val current = YamlLine.parse(text) ?: continue
            val key = current.key

            if (key == null || !current.value.isNullOrEmpty()) return null

            path.add(YamlKeyStep(key))

            if (current.isItem) {
                path.add(YamlItemStep())

                return listOwner(index, current.indent, path)
            }

            return Pair(index, current.indent)
        }

        return Pair(-1, 0)
    }

    private fun collectFollowingSiblings(line: Int, column: Int, siblings: MutableMap<String, String>) {
        for (index in line + 1 until lines.size) {
            val current = lineWithin(index, column)

            if (current == null || current.contentColumn > column) continue

            if (current.contentColumn < column || current.isItem) return

            val key = current.key

            if (key != null && !siblings.containsKey(key)) siblings[key] = current.value ?: ""
        }
    }
}
