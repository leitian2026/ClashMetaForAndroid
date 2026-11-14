package com.github.kr328.clash.design.custom.editor

// CUSTOM (CUSTOM.md #50, not upstream): which bracket or quote goes with the one at the caret, for the highlight that
// shows a missing partner. Looked up only when the caret moves, never in the background. No Android classes.
object YamlPairs {
    private const val OPENERS = "([{"
    private const val CLOSERS = ")]}"

    const val UNMATCHED = -1 // looked to the end of the text and found no partner
    const val UNKNOWN = -2 // not a bracket, or gave up before the end (too far away to say)

    /**
     * The index of the bracket that goes with the one at [pos] ([UNMATCHED] / [UNKNOWN] when there is none). Only
     * brackets of the same kind are counted, and only up to [limit] characters away.
     */
    fun matchBracket(text: CharSequence, pos: Int, limit: Int = 20000): Int {
        if (pos < 0 || pos >= text.length) return UNKNOWN

        val c = text[pos]
        val open = OPENERS.indexOf(c)

        if (open >= 0) {
            val partner = CLOSERS[open]
            val end = minOf(text.length, pos + limit)
            var depth = 0
            var i = pos

            while (i < end) {
                val x = text[i]

                if (x == c) {
                    depth++
                } else if (x == partner) {
                    depth--

                    if (depth == 0) return i
                }

                i++
            }

            return if (end == text.length) UNMATCHED else UNKNOWN
        }

        val close = CLOSERS.indexOf(c)

        if (close >= 0) {
            val partner = OPENERS[close]
            val end = maxOf(0, pos - limit)
            var depth = 0
            var i = pos

            while (i >= end) {
                val x = text[i]

                if (x == c) {
                    depth++
                } else if (x == partner) {
                    depth--

                    if (depth == 0) return i
                }

                i--
            }

            return if (end == 0) UNMATCHED else UNKNOWN
        }

        return UNKNOWN
    }

    /**
     * The strings of one line that are written with quotes, as (opening quote, closing quote) columns; the closing one
     * is -1 when the string is not closed. A quote only opens a string where a value or an item starts (at the start of
     * the text, after a space, `[`, `{`, `,` or `:`), so the apostrophe in `don't` is not taken for one.
     */
    fun quotes(line: String): List<IntArray> {
        val out = ArrayList<IntArray>()
        var i = 0

        while (i < line.length) {
            val c = line[i]

            if (c == '#' && (i == 0 || line[i - 1] == ' ' || line[i - 1] == '\t')) break

            if ((c == '\'' || c == '"') && (i == 0 || line[i - 1] in " \t[{,:")) {
                val close = closeOf(line, i)

                out.add(intArrayOf(i, close))

                if (close < 0) break

                i = close + 1

                continue
            }

            i++
        }

        return out
    }

    // the column of the quote that closes the string opened at [start]; -1 when it is not closed on this line
    private fun closeOf(line: String, start: Int): Int {
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

                return i
            }

            i++
        }

        return -1
    }
}
