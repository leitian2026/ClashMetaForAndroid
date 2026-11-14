package com.github.kr328.clash.design.custom.editor

// CUSTOM (CUSTOM.md #46, not upstream): the colouring of one YAML line (keys, strings, numbers, words like true and
// null, anchors, comments, the dash of a list item). It only says which part of the line gets which kind; the
// editor paints just the lines on the screen. No Android classes.
enum class Tok { Key, Str, Num, Word, Anchor, Comment, Punct }

private val keyRegion = Regex("""^('[^']*'|"[^"]*"|[^\s#'"{\[\-][^#]*?|-[^\s#][^#]*?)(\s*:)(?=\s|$)""")
private val numberPattern = Regex("^[-+]?(\\d+\\.?\\d*|\\.\\d+)([eE][-+]?\\d+)?$")
private val wordSet = setOf("true", "false", "null", "~", "yes", "no", "on", "off")

object YamlHighlighter {
    /** Calls [add] with (from, to, kind) for every coloured part of [line]; the numbers are columns in the line. */
    fun line(line: String, add: (Int, Int, Tok) -> Unit) {
        var i = YamlLine.indentOf(line)

        if (i >= line.length) return

        if (line[i] == '#') {
            add(i, line.length, Tok.Comment)

            return
        }

        if (YamlLine.isItemAt(line, i)) {
            add(i, i + 1, Tok.Punct)
            i++

            while (i < line.length && line[i] == ' ') i++

            // a list of lists: "- - a"
            while (i < line.length && YamlLine.isItemAt(line, i)) {
                add(i, i + 1, Tok.Punct)
                i++

                while (i < line.length && line[i] == ' ') i++
            }
        }

        if (i >= line.length) return

        val rest = line.substring(i)
        val key = keyRegion.find(rest)

        if (key != null) {
            val name = key.groups[1]!!
            val colon = key.groups[2]!!

            add(i + name.range.first, i + name.range.last + 1, Tok.Key)
            add(i + colon.range.last, i + colon.range.last + 1, Tok.Punct)

            i += key.range.last + 1
        }

        value(line, i, add)
    }

    private fun value(line: String, from: Int, add: (Int, Int, Tok) -> Unit) {
        var i = from

        while (i < line.length) {
            val c = line[i]

            when {
                c == ' ' || c == '\t' || c == ',' || c == '[' || c == ']' || c == '{' || c == '}' -> i++
                c == '#' && (i == 0 || line[i - 1] == ' ' || line[i - 1] == '\t') -> {
                    add(i, line.length, Tok.Comment)

                    return
                }
                c == '\'' || c == '"' -> {
                    val end = endOfQuote(line, i)

                    add(i, end, Tok.Str)

                    i = end
                }
                else -> {
                    var end = i

                    while (end < line.length && line[end] != ' ' && line[end] != '\t' && line[end] != ',' &&
                        line[end] != ']' && line[end] != '}' && line[end] != '[' && line[end] != '{'
                    ) {
                        end++
                    }

                    if (end == i) end = i + 1

                    val word = line.substring(i, end)

                    when {
                        word == ":" -> add(i, end, Tok.Punct)
                        word.lowercase() in wordSet -> add(i, end, Tok.Word)
                        numberPattern.matches(word) -> add(i, end, Tok.Num)
                        word.startsWith("&") || word.startsWith("*") || word.startsWith("!") -> add(i, end, Tok.Anchor)
                    }

                    i = end
                }
            }
        }
    }

    // the index after the closing quote of the string that starts at [start]; the end of the line when it is not closed
    private fun endOfQuote(line: String, start: Int): Int {
        val quote = line[start]
        var i = start + 1

        while (i < line.length) {
            val c = line[i]

            if (quote == '"' && c == '\\') {
                i += 2

                continue
            }

            if (c == quote) {
                if (quote == '\'' && i + 1 < line.length && line[i + 1] == '\'') {
                    i += 2

                    continue
                }

                return i + 1
            }

            i++
        }

        return line.length
    }
}
