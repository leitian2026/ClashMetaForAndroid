package com.github.kr328.clash.design.custom.editor

// CUSTOM (CUSTOM.md #50, not upstream): finds the mistakes of a Clash config that can be seen in the text alone, without
// the core: a Tab in the indent, a line indented more than the one above allows, a line whose indent matches no level
// above it, keys that are given twice in one block, proxy and group names that are given twice, groups and rules that
// point at a proxy, group or provider that is not defined. It reads the text once from top to bottom (one pass, nothing
// is kept afterwards) and says which line each mistake is on. No Android classes, so it can be tested on the JVM.

enum class DiagKind {
    Tab,
    IndentDeeper,
    IndentMisaligned,
    DupKey,
    DupName,
    MissingProxy,
    MissingPolicy,
    MissingProvider,
    MissingRuleSet,
}

/** [line] is 0-based; [severity] 0 is an error (the file is not valid YAML), 1 is a warning; [other] is the 1-based line of the first one for duplicates. */
class Diagnostic(
    val line: Int,
    val severity: Int,
    val kind: DiagKind,
    val arg: String = "",
    val other: Int = 0,
)

object YamlLint {
    fun run(text: String): List<Diagnostic> {
        val lines = text.split('\n').map { if (it.endsWith("\r")) it.substring(0, it.length - 1) else it }

        return Walker(lines).walk()
    }

    private val builtin = setOf("DIRECT", "REJECT", "REJECT-DROP", "PASS", "COMPATIBLE", "GLOBAL")
    private const val MAX_LINE = 4000 // a longer line is not read
    private const val MAX_DIAG = 2000
    private const val NONE = '\u0000'

    private val anchorOnly = Regex("^(?:[&!]\\S*)(?:\\s+[&!]\\S*)*$")
    private val blockHead = Regex("^[|>][+-]?\\d*[+-]?$")
    private const val QUOTE_RE = "[\"']?"
    private val nameInFlow = Regex(
        "[{,]\\s*" + QUOTE_RE + "name" + QUOTE_RE + "\\s*:\\s*(\"(?:[^\"\\\\]|\\\\.)*\"|'(?:[^']|'')*'|[^,}]*)",
    )
    private val proxiesInFlow = Regex("[{,]\\s*" + QUOTE_RE + "proxies" + QUOTE_RE + "\\s*:\\s*\\[([^\\]]*)\\]")
    private val useInFlow = Regex("[{,]\\s*" + QUOTE_RE + "use" + QUOTE_RE + "\\s*:\\s*\\[([^\\]]*)\\]")

    private class Level(val col: Int) {
        val keys = HashMap<String, Int>()
    }

    private class Ref(val line: Int, val name: String, val kind: String)

    // a flow collection ("[a, b]", "{a: 1}") that may go on over several lines; for the lists that name proxies or
    // providers ([kind] not empty) the items are collected
    private class Flow(val kind: String) {
        var depth = 0
        val sb = StringBuilder()
        var quote = NONE
        var top = NONE
        var closed = false
    }

    private fun indentOf(s: String): Int {
        var i = 0

        while (i < s.length && s[i] == ' ') i++

        return i
    }

    private fun isItemAt(s: String, i: Int): Boolean {
        return i < s.length && s[i] == '-' && (i + 1 == s.length || s[i + 1] == ' ')
    }

    private fun stripComment(v: String): String {
        val c = if (v.startsWith("#")) 0 else v.indexOf(" #")

        return if (c >= 0) v.substring(0, c).trimEnd() else v.trimEnd()
    }

    private fun unquote(t: String): String {
        val v = stripComment(t.trim()).trim()

        if (v.length >= 2 && ((v[0] == '\'' && v[v.length - 1] == '\'') || (v[0] == '"' && v[v.length - 1] == '"'))) {
            return v.substring(1, v.length - 1)
        }

        return v
    }

    // the index of the quote that closes a string of the quote character [q], looking from [from]; -1 when it is not there
    private fun closeIndex(s: String, q: Char, from: Int): Int {
        var i = from

        while (i < s.length) {
            val c = s[i]

            if (q == '"' && c == '\\') {
                i += 2

                continue
            }

            if (c == q) {
                if (q == '\'' && i + 1 < s.length && s[i + 1] == '\'') {
                    i += 2

                    continue
                }

                return i
            }

            i++
        }

        return -1
    }

    // "key: value" -> (key, the text after the colon); null when the line is not a key. A key is the text up to the first
    // ": " (or a ":" at the end) that no comment comes before
    private fun splitKey(content: String): Pair<String, String>? {
        if (content.isEmpty()) return null

        val c0 = content[0]

        if (c0 in "#{[|>&*!%@`") return null
        if (c0 == '-' && (content.length == 1 || content[1] == ' ')) return null

        if (c0 == '\'' || c0 == '"') {
            val e = closeIndex(content, c0, 1)

            if (e < 0) return null

            var j = e + 1

            while (j < content.length && content[j] == ' ') j++

            if (j < content.length && content[j] == ':' && (j + 1 == content.length || content[j + 1] == ' ')) {
                return Pair(content.substring(1, e), content.substring(j + 1))
            }

            return null
        }

        val comment = content.indexOf(" #")
        var from = 0

        while (from < content.length) {
            val j = content.indexOf(':', from)

            if (j < 0) return null
            if (comment in 0 until j) return null

            if (j + 1 == content.length || content[j + 1] == ' ') {
                return Pair(content.substring(0, j).trimEnd(), content.substring(j + 1))
            }

            from = j + 1
        }

        return null
    }

    private fun tokenStart(s: String, i: Int): Boolean = i == 0 || s[i - 1] in " \t[{,:"

    // the items of "a, 'b, c', d" (the inside of a flow list on one line)
    private fun splitItems(inner: String): List<String> {
        val out = ArrayList<String>()
        val sb = StringBuilder()
        var q = NONE
        var i = 0

        while (i < inner.length) {
            val c = inner[i]

            if (q != NONE) {
                sb.append(c)

                if (c == '\\' && q == '"' && i + 1 < inner.length) {
                    sb.append(inner[i + 1])
                    i += 2

                    continue
                }

                if (c == q) q = NONE
            } else if ((c == '\'' || c == '"') && sb.isBlank()) {
                q = c
                sb.append(c)
            } else if (c == ',') {
                out.add(sb.toString().trim())
                sb.setLength(0)
            } else {
                sb.append(c)
            }

            i++
        }

        val last = sb.toString().trim()

        if (last.isNotEmpty()) out.add(last)

        return out
    }

    private class Walker(private val lines: List<String>) {
        private val diags = ArrayList<Diagnostic>()
        private val stack = ArrayList<Level>()
        private val refs = ArrayList<Ref>()
        private val names = HashMap<String, Int>() // proxies and groups share their names
        private val providers = HashSet<String>()
        private val ruleProviders = HashSet<String>()

        // the list of a group that is being read ("proxies:" or "use:" with the items on the next lines)
        private var listKind = ""
        private var listCol = -1

        private fun add(line: Int, severity: Int, kind: DiagKind, arg: String = "", other: Int = 0) {
            if (diags.size < MAX_DIAG) diags.add(Diagnostic(line, severity, kind, arg, other))
        }

        fun walk(): List<Diagnostic> {
            var quote = NONE
            var block = -1
            var flow: Flow? = null
            var prevOpens = false

            var section = ""
            var sectionChild = -1
            var sectionDash = -1
            var itemDash = -1
            var itemMap = -1

            for (i in lines.indices) {
                val ln = lines[i]

                if (ln.length > MAX_LINE) continue

                if (quote != NONE) {
                    if (closeIndex(ln, quote, 0) >= 0) quote = NONE

                    continue
                }

                if (block >= 0) {
                    if (ln.isBlank() || indentOf(ln) > block) continue

                    block = -1
                }

                val open = flow

                if (open != null) {
                    feedFlow(open, ln, i)

                    if (open.closed) flow = null

                    continue
                }

                val t = ln.trim()

                if (t.isEmpty() || t.startsWith("#")) continue

                if ((ln.startsWith("---") || ln.startsWith("...")) && ln.substring(3).isBlank()) {
                    stack.clear()
                    section = ""
                    prevOpens = false

                    continue
                }

                if (ln.startsWith("%")) continue

                var lead = 0

                while (lead < ln.length && (ln[lead] == ' ' || ln[lead] == '\t')) lead++

                if (ln.substring(0, lead).contains('\t')) {
                    add(i, 0, DiagKind.Tab)
                    prevOpens = false

                    continue
                }

                var indent = lead
                var pos = indent
                val dashes = ArrayList<Int>()

                while (isItemAt(ln, pos)) {
                    dashes.add(pos)
                    pos++

                    while (pos < ln.length && ln[pos] == ' ') pos++
                }

                val rest = ln.substring(pos)
                val hasDash = dashes.isNotEmpty()
                val kv = if (rest.isNotEmpty()) splitKey(rest) else null

                // the levels of indentation
                if (hasDash || kv != null) {
                    var popped: Level? = null

                    while (stack.isNotEmpty() && stack[stack.size - 1].col > indent) popped = stack.removeAt(stack.size - 1)

                    if (stack.isEmpty()) {
                        stack.add(Level(indent))
                    } else if (stack[stack.size - 1].col == indent) {
                        // a line of the same level
                    } else if (popped != null) {
                        // it went back to a column that no level above has: it is taken for the level it should have
                        // been on, so that the lines after it are not blamed as well
                        add(i, 0, DiagKind.IndentMisaligned)
                        stack.add(popped)
                        indent = popped.col
                    } else {
                        if (!prevOpens) add(i, 0, DiagKind.IndentDeeper)

                        stack.add(Level(indent))
                    }

                    for (d in 1 until dashes.size) stack.add(Level(dashes[d]))

                    if (hasDash && kv != null) stack.add(Level(pos))
                }

                val keyCol = pos
                var key = ""
                var valRaw = ""

                if (kv != null) {
                    key = unquote(kv.first)
                    valRaw = kv.second

                    val level = stack[stack.size - 1]

                    if (level.col == keyCol && key != "<<") {
                        val first = level.keys[key]

                        if (first != null) add(i, 1, DiagKind.DupKey, key, first + 1) else level.keys[key] = i
                    }
                }

                val valClean = if (kv != null) stripComment(valRaw.trim()) else stripComment(rest.trim())

                // what the line means for proxies, groups, rules and providers
                if (!hasDash && kv != null && indent == 0) {
                    section = key
                    sectionChild = -1
                    sectionDash = -1
                    itemDash = -1
                    itemMap = -1
                    listKind = ""
                    listCol = -1
                } else if (section == "proxies" || section == "proxy-groups") {
                    if (hasDash) {
                        if (sectionDash == -1) sectionDash = indent

                        if (indent == sectionDash) {
                            itemDash = indent
                            listKind = ""
                            listCol = -1
                            itemMap = if (kv != null) keyCol else -1

                            if (kv != null) {
                                itemKey(section, key, valClean, i, keyCol)
                            } else if (rest.startsWith("{")) {
                                flowItem(section, rest, i)
                            }
                        } else if (listKind.isNotEmpty() && indent >= listCol && dashes.size == 1 && kv == null) {
                            val nm = unquote(rest)

                            if (nm.isNotEmpty() && !rest.startsWith("{") && !rest.startsWith("[")) refs.add(Ref(i, nm, listKind))
                        }
                    } else if (kv != null) {
                        if (itemMap == -1 && itemDash >= 0 && indent > itemDash) itemMap = indent

                        if (listKind.isNotEmpty() && indent <= listCol) {
                            listKind = ""
                            listCol = -1
                        }

                        if (indent == itemMap) {
                            listKind = ""
                            listCol = -1
                            itemKey(section, key, valClean, i, keyCol)
                        }
                    }
                } else if (section == "rules") {
                    if (hasDash && dashes.size == 1) checkRule(rest, i)
                } else if (section == "proxy-providers" || section == "rule-providers") {
                    if (!hasDash && kv != null) {
                        if (sectionChild == -1 && indent > 0) sectionChild = indent

                        if (indent == sectionChild) {
                            if (section == "proxy-providers") providers.add(key) else ruleProviders.add(key)
                        }
                    }
                }

                // what the rest of the line starts: a block text, a list over several lines, a string over several lines
                val tail: String
                val owner: Int

                if (kv != null) {
                    prevOpens = valClean.isEmpty() || anchorOnly.matches(valClean)
                    tail = valRaw.trim()
                    owner = keyCol
                } else {
                    tail = rest.trim()
                    prevOpens = hasDash && (tail.isEmpty() || anchorOnly.matches(stripComment(tail)))
                    owner = if (hasDash) dashes[dashes.size - 1] else indent
                }

                val tailClean = stripComment(tail)

                if (tailClean.isNotEmpty() && blockHead.matches(tailClean)) {
                    block = owner
                    prevOpens = false
                } else if (tail.isNotEmpty() && !tail.startsWith("#")) {
                    val c0 = tail[0]

                    if (c0 == '[' || c0 == '{') {
                        var kind = ""

                        if (kv != null && section == "proxy-groups" && keyCol == itemMap && c0 == '[') {
                            if (key == "proxies") kind = "proxy" else if (key == "use") kind = "use"
                        }

                        val f = Flow(kind)

                        feedFlow(f, tail, i)

                        if (!f.closed) flow = f

                        prevOpens = false
                    } else if (c0 == '\'' || c0 == '"') {
                        if (closeIndex(tail, c0, 1) < 0) quote = c0
                    }
                }
            }

            finish()

            return diags
        }

        // a key of an item of "proxies:" or "proxy-groups:"
        private fun itemKey(section: String, key: String, valClean: String, line: Int, keyCol: Int) {
            if (key == "name") {
                val nm = unquote(valClean)

                if (nm.isNotEmpty()) registerName(nm, line)
            } else if (section == "proxy-groups" && (key == "proxies" || key == "use")) {
                if (valClean.isEmpty()) {
                    listKind = if (key == "proxies") "proxy" else "use"
                    listCol = keyCol
                }
            }
        }

        private fun registerName(name: String, line: Int) {
            val first = names[name]

            if (first != null) add(line, 1, DiagKind.DupName, name, first + 1) else names[name] = line
        }

        private fun feedFlow(f: Flow, text: String, line: Int) {
            var i = 0

            while (i < text.length) {
                val c = text[i]

                if (f.quote != NONE) {
                    f.sb.append(c)

                    if (c == '\\' && f.quote == '"' && i + 1 < text.length) {
                        f.sb.append(text[i + 1])
                        i += 2

                        continue
                    }

                    if (c == f.quote) f.quote = NONE

                    i++

                    continue
                }

                if (c == '#' && (i == 0 || text[i - 1] == ' ' || text[i - 1] == '\t')) break

                if ((c == '\'' || c == '"') && tokenStart(text, i) && f.depth > 0) {
                    f.quote = c
                    f.sb.append(c)
                    i++

                    continue
                }

                if (c == '[' || c == '{') {
                    f.depth++

                    if (f.depth == 1) f.top = c else f.sb.append(c)

                    i++

                    continue
                }

                if (c == ']' || c == '}') {
                    f.depth--

                    if (f.depth <= 0) {
                        flush(f, line)
                        f.closed = true
                        f.depth = 0

                        return
                    }

                    f.sb.append(c)
                    i++

                    continue
                }

                if (c == ',' && f.depth == 1) {
                    flush(f, line)
                    i++

                    continue
                }

                f.sb.append(c)
                i++
            }
        }

        private fun flush(f: Flow, line: Int) {
            val item = f.sb.toString().trim()

            f.sb.setLength(0)

            if (f.kind.isNotEmpty() && f.top == '[' && item.isNotEmpty()) {
                val nm = unquote(item)

                if (nm.isNotEmpty()) refs.add(Ref(line, nm, f.kind))
            }
        }

        // "- {name: a, type: select, proxies: [b, c]}" on one line
        private fun flowItem(section: String, rest: String, line: Int) {
            val m = nameInFlow.find(rest)

            if (m != null) {
                val nm = unquote(m.groupValues[1])

                if (nm.isNotEmpty()) registerName(nm, line)
            }

            if (section == "proxy-groups") {
                val p = proxiesInFlow.find(rest)

                if (p != null) {
                    for (item in splitItems(p.groupValues[1])) {
                        val nm = unquote(item)

                        if (nm.isNotEmpty()) refs.add(Ref(line, nm, "proxy"))
                    }
                }

                val u = useInFlow.find(rest)

                if (u != null) {
                    for (item in splitItems(u.groupValues[1])) {
                        val nm = unquote(item)

                        if (nm.isNotEmpty()) refs.add(Ref(line, nm, "use"))
                    }
                }
            }
        }

        // a line of "rules:": the last part is the proxy or group it points at (after the options "no-resolve" and "src");
        // RULE-SET also names a rule provider; SUB-RULE names a sub-rule and is left alone
        private fun checkRule(rest: String, line: Int) {
            val text = unquote(rest)

            if (text.isEmpty()) return

            val fields = text.split(',').map { it.trim() }.toMutableList()

            if (fields.size < 2) return

            val type = fields[0].uppercase()

            if (type == "SUB-RULE") return

            if (type == "RULE-SET") refs.add(Ref(line, fields[1], "ruleset"))

            while (fields.size > 2 && (fields[fields.size - 1].lowercase() == "no-resolve" || fields[fields.size - 1].lowercase() == "src")) {
                fields.removeAt(fields.size - 1)
            }

            val policy = fields[fields.size - 1]

            if (policy.isEmpty() || policy.contains('(') || policy.contains(')')) return

            refs.add(Ref(line, policy, "policy"))
        }

        private fun finish() {
            for (r in refs) {
                when (r.kind) {
                    "proxy" -> if (!names.containsKey(r.name) && r.name !in builtin) add(r.line, 1, DiagKind.MissingProxy, r.name)
                    "policy" -> if (!names.containsKey(r.name) && r.name !in builtin) add(r.line, 1, DiagKind.MissingPolicy, r.name)
                    "use" -> if (r.name !in providers) add(r.line, 1, DiagKind.MissingProvider, r.name)
                    "ruleset" -> if (r.name !in ruleProviders) add(r.line, 1, DiagKind.MissingRuleSet, r.name)
                }
            }

            diags.sortBy { it.line }
        }
    }
}
