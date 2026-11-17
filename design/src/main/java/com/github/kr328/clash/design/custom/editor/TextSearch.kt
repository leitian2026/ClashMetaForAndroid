package com.github.kr328.clash.design.custom.editor

import java.util.regex.Pattern
import java.util.regex.PatternSyntaxException

/**
 * Text search shared by the editor's find and replace actions. Ordinary text mode deliberately delegates to
 * String.indexOf / lastIndexOf so its matching semantics stay identical to the original editor.
 */
class TextSearch(
    private val query: String,
    private val matchCase: Boolean,
    private val regex: Boolean,
) {
    private val pattern: Pattern? = if (!regex || query.isEmpty()) null else try {
        Pattern.compile(
            query,
            Pattern.MULTILINE or if (matchCase) 0 else Pattern.CASE_INSENSITIVE or Pattern.UNICODE_CASE,
        )
    } catch (_: PatternSyntaxException) {
        null
    }

    val valid: Boolean = !regex || query.isEmpty() || pattern != null
    var timedOut: Boolean = false
        private set

    private class SearchTimeout : RuntimeException()

    private class DeadlineText(private val source: CharSequence, private val deadline: Long) : CharSequence {
        override val length: Int get() = source.length
        override fun get(index: Int): Char {
            if (System.nanoTime() > deadline) throw SearchTimeout()
            return source[index]
        }
        override fun subSequence(startIndex: Int, endIndex: Int): CharSequence =
            DeadlineText(source.subSequence(startIndex, endIndex), deadline)
        override fun toString(): String = source.toString()
    }

    private fun matcher(text: CharSequence): java.util.regex.Matcher =
        pattern!!.matcher(DeadlineText(text, System.nanoTime() + 2_000_000_000L))

    private fun range(start: Int, end: Int): IntRange = start until end

    fun next(text: CharSequence, from: Int): IntRange? {
        timedOut = false
        if (query.isEmpty() || !valid) return null
        if (!regex) {
            val at = text.toString().indexOf(query, from.coerceIn(0, text.length), ignoreCase = !matchCase)
            return if (at < 0) null else range(at, at + query.length)
        }
        return try {
            val m = matcher(text)
            var found: IntRange? = null
            if (m.find(from.coerceIn(0, text.length))) {
                do {
                    if (m.end() > m.start()) {
                        found = range(m.start(), m.end())
                        break
                    }
                } while (m.find())
            }
            found
        } catch (_: SearchTimeout) {
            timedOut = true
            null
        }
    }

    fun prev(text: CharSequence, before: Int): IntRange? {
        timedOut = false
        if (query.isEmpty() || !valid) return null
        val limit = before.coerceIn(0, text.length)
        if (!regex) {
            val at = text.toString().lastIndexOf(query, limit - 1, ignoreCase = !matchCase)
            return if (at < 0) null else range(at, at + query.length)
        }
        return try {
            val m = matcher(text)
            var found: IntRange? = null
            while (m.find()) {
                if (m.start() >= limit) break
                if (m.end() > m.start()) found = range(m.start(), m.end())
            }
            found
        } catch (_: SearchTimeout) {
            timedOut = true
            null
        }
    }

    fun count(text: CharSequence): Int {
        timedOut = false
        if (query.isEmpty() || !valid) return 0
        if (!regex) {
            val s = text.toString()
            var total = 0
            var at = s.indexOf(query, 0, ignoreCase = !matchCase)
            while (at >= 0) {
                total++
                at = s.indexOf(query, at + query.length, ignoreCase = !matchCase)
            }
            return total
        }
        return try {
            val m = matcher(text)
            var total = 0
            while (m.find() && total < 9999) if (m.end() > m.start()) total++
            total
        } catch (_: SearchTimeout) {
            timedOut = true
            0
        }
    }

    /** Returns (1-based match index at the caret or zero, total matches) using one matcher / deadline. */
    fun position(text: CharSequence, caretStart: Int, caretEnd: Int): Pair<Int, Int> {
        timedOut = false
        if (query.isEmpty() || !valid) return 0 to 0
        if (!regex) {
            val s = text.toString()
            var total = 0
            var index = 0
            var at = s.indexOf(query, 0, ignoreCase = !matchCase)
            while (at >= 0) {
                total++
                if (at == caretStart && at + query.length == caretEnd) index = total
                at = s.indexOf(query, at + query.length, ignoreCase = !matchCase)
            }
            return index to total
        }
        return try {
            val m = matcher(text)
            var total = 0
            var index = 0
            while (m.find() && total < 9999) {
                if (m.end() > m.start()) {
                    total++
                    if (m.start() == caretStart && m.end() == caretEnd) index = total
                }
            }
            index to total
        } catch (_: SearchTimeout) {
            timedOut = true
            0 to 0
        }
    }

    /** Expands one replacement using Matcher replacement rules; returns only the replacement text. */
    fun replacement(text: CharSequence, range: IntRange, with: String): String? {
        timedOut = false
        if (!valid || range.isEmpty()) return null
        if (!regex) {
            val s = text.toString()
            return if (range.last < s.length && s.regionMatches(range.first, query, 0, query.length, ignoreCase = !matchCase)) with else null
        }
        return try {
            val m = matcher(text)
            if (!m.find(range.first) || m.start() != range.first || m.end() != range.last + 1) return null
            val result = StringBuffer()
            m.appendReplacement(result, with)
            val replacementEnd = result.length
            m.appendTail(result)
            result.substring(range.first, replacementEnd)
        } catch (_: SearchTimeout) {
            timedOut = true
            null
        } catch (_: IllegalArgumentException) {
            null
        } catch (_: IndexOutOfBoundsException) {
            null
        }
    }

    /** Replaces all non-empty matches, keeping zero-width matches untouched and advancing safely. */
    fun replaceAllText(text: String, with: String): Triple<String, Int, Int>? {
        timedOut = false
        if (!valid || query.isEmpty()) return Triple(text, 0, -1)
        if (!regex) {
            val out = StringBuilder(text.length)
            var from = 0
            var count = 0
            var first = -1
            var at = text.indexOf(query, 0, ignoreCase = !matchCase)
            while (at >= 0) {
                if (first < 0) first = at
                out.append(text, from, at).append(with)
                count++
                from = at + query.length
                at = text.indexOf(query, from, ignoreCase = !matchCase)
            }
            if (count == 0) return Triple(text, 0, -1)
            out.append(text, from, text.length)
            return Triple(out.toString(), count, first)
        }
        return try {
            val m = matcher(text)
            val out = StringBuffer(text.length)
            var count = 0
            var first = -1
            while (m.find()) {
                if (m.end() <= m.start()) continue
                if (first < 0) first = m.start()
                m.appendReplacement(out, with)
                count++
            }
            if (count == 0) return Triple(text, 0, -1)
            m.appendTail(out)
            Triple(out.toString(), count, first)
        } catch (_: SearchTimeout) {
            timedOut = true
            null
        } catch (_: IllegalArgumentException) {
            null
        } catch (_: IndexOutOfBoundsException) {
            null
        }
    }
}
