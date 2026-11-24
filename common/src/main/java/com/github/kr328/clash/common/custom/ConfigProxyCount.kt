package com.github.kr328.clash.common.custom

/** Counts only proxy entries written directly in the top-level proxies block. */
object ConfigProxyCount {
    const val MIN_OLD_COUNT = 10
    const val DROP_DIVISOR = 2

    fun count(text: String): Int {
        var start = 0
        var inProxies = false
        var itemIndent = -1
        var total = 0

        while (start <= text.length) {
            val end = text.indexOf('\n', start).let { if (it < 0) text.length else it }
            var lineEnd = end
            if (lineEnd > start && text[lineEnd - 1] == '\r') lineEnd--
            val line = text.substring(start, lineEnd)
            val trimmed = line.trimStart()
            val indent = line.length - trimmed.length

            if (trimmed.isNotEmpty() && !trimmed.startsWith("#")) {
                if (!inProxies) {
                    if (indent == 0 && trimmed.startsWith("proxies") &&
                        trimmed.length > 7 && (trimmed[7] == ':' || trimmed[7].isWhitespace())
                    ) {
                        val colon = trimmed.indexOf(':')
                        if (colon >= 0) {
                            val value = withoutComment(trimmed.substring(colon + 1)).trim()
                            if (value == "[]") return 0
                            if (value.isNotEmpty()) return -1
                            inProxies = true
                        }
                    }
                } else {
                    if (indent == 0) return total
                    if (itemIndent < 0) {
                        if (trimmed.startsWith("- ")) {
                            itemIndent = indent
                            total++
                        } else {
                            return -1
                        }
                    } else if (indent < itemIndent) {
                        return total
                    } else if (indent == itemIndent) {
                        if (trimmed.startsWith("- ")) total++ else return total
                    }
                }
            }

            if (end == text.length) break
            start = end + 1
        }
        return if (inProxies) total else 0
    }

    fun dropped(oldCount: Int, newCount: Int): Boolean =
        oldCount >= MIN_OLD_COUNT &&
            newCount >= 0 &&
            oldCount >= 0 &&
            newCount <= oldCount / DROP_DIVISOR

    private fun withoutComment(value: String): String {
        var quote = '\u0000'
        var i = 0
        while (i < value.length) {
            val c = value[i]
            if (quote != '\u0000') {
                if (c == '\\' && quote == '"' && i + 1 < value.length) { i += 2; continue }
                if (c == quote) quote = '\u0000'
            } else if (c == '"' || c == '\'') {
                quote = c
            } else if (c == '#' && (i == 0 || value[i - 1].isWhitespace())) {
                return value.substring(0, i)
            }
            i++
        }
        return value
    }
}
