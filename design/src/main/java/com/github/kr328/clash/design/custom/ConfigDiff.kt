package com.github.kr328.clash.design.custom

import com.github.kr328.clash.core.model.EffectiveConfig
import com.github.kr328.clash.core.model.EffectiveLine

/**
 * CUSTOM (CUSTOM.md #56, not upstream): what a config text would change in another one, as the lines that the
 * "effective config" page shows (see [EffectiveText]): [EffectiveLine.CHANGED] a value that is not the same (the
 * old value is struck through), [EffectiveLine.REMOVED] a line the old text has and the new one has not,
 * [EffectiveLine.ADDED] a line only the new text has. Every line of the new text is there (nothing is folded),
 * the removed ones sit where they stood.
 *
 * It does not run a YAML parser: the text is laid out as a tree by its indentation, and the nodes of one level are
 * matched by what names them: a key by its name, a list item by its `name:` (also inside `{...}`) or, without one,
 * by its first key or its whole text. Comments, blank lines and `---` are not compared. A node that has the same
 * name in both texts is compared in place: its value, then what is under it.
 *
 * Pure Kotlin, in memory, runs once per call (nothing in the background).
 */
object ConfigDiff {
    private class Node(val indent: Int, val body: String, val isItem: Boolean) {
        val children = ArrayList<Node>()
        var key: String? = null // the key of a `key: value` line (for an item: of the text after "- ")
        var kvValue: String = "" // what follows the key's colon
        var rest: String = "" // an item: the text after "- "
        var id: String = ""
    }

    /** [old] is the config in use, [new] the text that would replace it. */
    fun diff(old: String, new: String): EffectiveConfig {
        val out = ArrayList<EffectiveLine>()

        diffLists(parse(old), parse(new), out)

        return EffectiveConfig(
            ok = true,
            lines = out,
            changed = out.count { it.status == EffectiveLine.CHANGED },
            removed = out.count { it.status == EffectiveLine.REMOVED },
            added = out.count { it.status == EffectiveLine.ADDED },
        )
    }

    // ---- reading -------------------------------------------------------------------------------

    private fun parse(text: String): List<Node> {
        val roots = ArrayList<Node>()
        val stack = ArrayList<Node>()

        for (raw in text.lineSequence()) {
            val line = raw.trimEnd('\r')
            val t = line.trimStart(' ')

            if (t.isEmpty() || t.startsWith("#") || t == "---" || t == "...") continue

            val body = stripComment(t).trimEnd()

            if (body.isEmpty()) continue

            val indent = line.length - t.length
            val node = makeNode(indent, body)

            while (stack.isNotEmpty()) {
                val top = stack[stack.size - 1]

                if (top.indent > indent) {
                    stack.removeAt(stack.size - 1)

                    continue
                }

                // a list that is not indented under its key ("proxies:" then "- name: a" at the same column)
                val listUnderKey = node.isItem && !top.isItem && top.key != null && top.kvValue.isEmpty()

                if (top.indent == indent && !listUnderKey) {
                    stack.removeAt(stack.size - 1)

                    continue
                }

                break
            }

            if (stack.isEmpty()) roots.add(node) else stack[stack.size - 1].children.add(node)

            stack.add(node)
        }

        return roots
    }

    private fun makeNode(indent: Int, body: String): Node {
        val isItem = body == "-" || body.startsWith("- ")
        val node = Node(indent, body, isItem)
        val rest = if (isItem) body.substring(1).trim() else body
        val kv = splitKey(rest)

        node.rest = rest

        if (kv != null) {
            node.key = kv.first
            node.kvValue = kv.second
        }

        node.id = when {
            isItem -> itemId(node, rest)
            node.key != null -> "k:" + node.key
            else -> "l:$body"
        }

        return node
    }

    private fun itemId(node: Node, rest: String): String {
        if (rest.startsWith("{")) {
            val name = Regex("""[{,]\s*["']?name["']?\s*:\s*([^,}]+)""").find(rest)?.groupValues?.get(1)?.trim()

            return if (name != null) "n:" + unquote(name) else "i:$rest"
        }

        return when {
            node.key == "name" -> "n:" + unquote(node.kvValue)
            node.key != null -> "ik:" + node.key
            else -> "i:$rest"
        }
    }

    private fun unquote(s: String): String {
        if (s.length >= 2 && (s[0] == '"' || s[0] == '\'') && s[s.length - 1] == s[0]) return s.substring(1, s.length - 1)

        return s
    }

    /** `key: value` or `key:` -> (key, value); null when the text is not a key line (a scalar, a `{...}`). */
    private fun splitKey(s: String): Pair<String, String>? {
        if (s.isEmpty()) return null

        val c = s[0]

        if (c == '"' || c == '\'') {
            var i = 1

            while (i < s.length && s[i] != c) {
                if (c == '"' && s[i] == '\\') i++

                i++
            }

            if (i >= s.length) return null

            i++

            var j = i

            while (j < s.length && s[j] == ' ') j++

            if (j >= s.length || s[j] != ':') return null

            val after = s.substring(j + 1)

            if (after.isNotEmpty() && after[0] != ' ') return null

            return Pair(s.substring(0, i), after.trim())
        }

        if (c in "{[&*!|>#%@") return null

        for (k in s.indices) {
            if (s[k] == ':' && (k + 1 == s.length || s[k + 1] == ' ')) {
                return Pair(s.substring(0, k).trim(), s.substring(k + 1).trim())
            }
        }

        return null
    }

    // cuts a trailing ` # comment` that is not inside quotes
    private fun stripComment(s: String): String {
        var quote = ' '
        var i = 0

        while (i < s.length) {
            val c = s[i]

            if (quote != ' ') {
                if (c == quote) quote = ' ' else if (quote == '"' && c == '\\') i++
            } else if (c == '"' || c == '\'') {
                if (i == 0 || s[i - 1] in " {[,:") quote = c
            } else if (c == '#' && i > 0 && s[i - 1] == ' ') {
                return s.substring(0, i).trimEnd()
            }

            i++
        }

        return s
    }

    // ---- comparing -----------------------------------------------------------------------------

    private fun preOf(n: Node): String = when {
        n.isItem -> "- "
        n.key != null -> n.key + ":" + (if (n.kvValue.isNotEmpty()) " " else "")
        else -> ""
    }

    private fun valueOf(n: Node): String = when {
        n.isItem -> n.rest
        n.key != null -> n.kvValue
        else -> n.body
    }

    private fun emitAll(n: Node, status: Int, out: MutableList<EffectiveLine>) {
        out.add(EffectiveLine(indent = n.indent, pre = preOf(n), value = valueOf(n), status = status))

        for (c in n.children) emitAll(c, status, out)
    }

    private fun diffPair(o: Node, n: Node, out: MutableList<EffectiveLine>) {
        val oldValue = valueOf(o)
        val newValue = valueOf(n)

        if (oldValue == newValue) {
            out.add(EffectiveLine(indent = n.indent, pre = preOf(n), value = newValue))
        } else {
            out.add(EffectiveLine(indent = n.indent, pre = preOf(n), value = newValue, old = oldValue, status = EffectiveLine.CHANGED))
        }

        diffLists(o.children, n.children, out)
    }

    private fun diffLists(olds: List<Node>, news: List<Node>, out: MutableList<EffectiveLine>) {
        if (olds.isEmpty()) {
            for (n in news) emitAll(n, EffectiveLine.ADDED, out)

            return
        }

        if (news.isEmpty()) {
            for (o in olds) emitAll(o, EffectiveLine.REMOVED, out)

            return
        }

        // the nodes that have the same name, first with first
        val pool = HashMap<String, ArrayList<Int>>()

        olds.forEachIndexed { i, o -> pool.getOrPut(o.id) { ArrayList() }.add(i) }

        val cursor = HashMap<String, Int>()
        val match = IntArray(news.size) { -1 }
        val used = BooleanArray(olds.size)

        for (j in news.indices) {
            val list = pool[news[j].id] ?: continue
            val at = cursor[news[j].id] ?: 0

            if (at < list.size) {
                match[j] = list[at]
                used[list[at]] = true
                cursor[news[j].id] = at + 1
            }
        }

        // the removed nodes are put where they stood: before the next node that is kept
        var flushed = 0

        for (j in news.indices) {
            val i = match[j]

            if (i < 0) {
                emitAll(news[j], EffectiveLine.ADDED, out)

                continue
            }

            if (i >= flushed) {
                for (k in flushed until i) if (!used[k]) emitAll(olds[k], EffectiveLine.REMOVED, out)

                flushed = i + 1
            }

            diffPair(olds[i], news[j], out)
        }

        for (k in flushed until olds.size) if (!used[k]) emitAll(olds[k], EffectiveLine.REMOVED, out)
    }
}
