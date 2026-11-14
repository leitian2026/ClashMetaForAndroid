package com.github.kr328.clash.design.custom.editor

// CUSTOM (CUSTOM.md #46, not upstream): what to suggest at the caret of a Clash config. Ported from FlClash's
// `lib/features/editor/clash_completion.dart` and `completion_matcher.dart` (GPL-3.0): the keys the block under the
// caret may still have (from the schema, by the `type` of its map), the values of a key, rule types and their
// parts, and the names of proxies, groups and providers the document itself defines.

/**
 * One candidate. [insert] is what replaces the typed part (the label when null); [cursorBack] puts the caret that
 * many characters before the end of what was inserted.
 */
class Suggestion(
    val label: String,
    val detail: String = "",
    val insert: String? = null,
    val cursorBack: Int = 0,
)

/** [prefix] is the part before the caret that a chosen suggestion replaces. */
class Completion(val prefix: String, val suggestions: List<Suggestion>)

/** The caret's place: [line] is the 0-based line, [column] the caret's column in it. */
class CompletionRequest(
    val lines: List<String>,
    val line: Int,
    val column: Int,
    val documentWords: () -> List<String>,
) {
    val lineText: String get() = lines[line]

    val textBeforeCaret: String get() = lineText.substring(0, column.coerceIn(0, lineText.length))
}

private val caretLinePattern = Regex("^( *)(-(?: +|$))?(.*)$")
private val keyTypedPattern = Regex("^[\\w.-]*$")
private val valueTypedPattern = Regex("""^('[^']*'|"[^"]*"|[^\s#'"{\[][^#]*?):\s+(.*)$""")
private val wordBeforeCaret = Regex("[\\w-]*$")

private const val MAX_SUGGESTIONS = 60

private val baseTargets = listOf("DIRECT", "REJECT", "REJECT-DROP", "PASS", "PASS-RULE", "COMPATIBLE")

class ClashCompletion(private val schema: ClashSchema) {
    fun complete(request: CompletionRequest): Completion? {
        val before = request.textBeforeCaret

        if (before.contains(" #") || before.trimStart().startsWith("#")) return null

        val match = caretLinePattern.find(before) ?: return null
        val indent = match.groupValues[1].length
        val dash = match.groups[2]?.value
        val startsItem = dash != null
        val typed = match.groupValues[3]
        val column = indent + (dash?.length ?: 0)
        val lines = YamlDocumentLines(request.lines)
        val scope = lines.mappingScope(request.line, column, startsItem, indent)
        val node = if (scope == null) null else resolve(scope.path)

        if (scope == null || node == null) return words(request, before)

        val rest = request.lineText.substring(request.column.coerceIn(0, request.lineText.length))

        if (node.kind == SchemaKind.Scalar) return scalarItem(request, node, typed)

        if (node.kind != SchemaKind.Map) return null

        val fields = node.fieldsFor(scope.siblings["type"])
        val valueMatch = valueTypedPattern.find(typed)

        if (valueMatch != null) {
            val key = unquoteYaml(valueMatch.groupValues[1])
            val value = fields[key] ?: node.entry ?: return words(request, before)

            return completeValue(request, value, valueMatch.groupValues[2])
        }

        if (typed.isEmpty() || !keyTypedPattern.matches(typed)) return null

        if (fields.isEmpty()) return words(request, before)

        val atLineEnd = rest.isBlank()
        val childIndent = " ".repeat(column + 2)
        val candidates = ArrayList<Suggestion>()

        for ((key, value) in fields) {
            if (scope.siblings.containsKey(key)) continue

            val body = when (value.kind) {
                SchemaKind.Scalar -> "$key: "
                SchemaKind.Map -> "$key:\n$childIndent"
                SchemaKind.List -> "$key:\n$childIndent- "
            }

            candidates.add(Suggestion(key, value.hint, if (atLineEnd) body else null))
        }

        return Completion(typed, rank(typed, candidates))
    }

    private fun resolve(path: List<YamlStep>): SchemaNode? {
        var node: SchemaNode? = schema.root

        for (step in path) {
            val current = node ?: return null

            node = when {
                step is YamlKeyStep && current.kind == SchemaKind.Map -> current.fieldsFor(null)[step.key] ?: current.entry
                step is YamlItemStep && current.kind == SchemaKind.List -> current.entry
                else -> null
            }

            if (node == null) return null
        }

        return node
    }

    private fun completeValue(request: CompletionRequest, schemaNode: SchemaNode, typed: String): Completion? {
        var text = typed

        if (text.startsWith("[")) {
            val entry = schemaNode.entry

            if (schemaNode.kind != SchemaKind.List || entry == null) return null

            val cut = maxOf(text.lastIndexOf('['), text.lastIndexOf(','))

            text = text.substring(cut + 1).trimStart()

            return scalar(request, entry, text)
        }

        if (schemaNode.kind != SchemaKind.Scalar) return null

        if (text.startsWith("'") || text.startsWith("\"")) text = text.substring(1)

        return scalar(request, schemaNode, text)
    }

    private fun scalarItem(request: CompletionRequest, item: SchemaNode, typed: String): Completion? {
        return when (item.scalar) {
            "rule" -> rule(request, typed, true)
            "rulePayload" -> rule(request, typed, false)
            else -> scalar(request, item, typed)
        }
    }

    private fun scalar(request: CompletionRequest, node: SchemaNode, typed: String): Completion? {
        if (typed.isEmpty()) return null

        val candidates: List<Suggestion> = when (node.scalar) {
            "policy" -> policies(request, false)
            "outbound" -> policies(request, true)
            "proxyProvider" -> plain(names(request).proxyProviders)
            "ruleProvider" -> plain(names(request).ruleProviders)
            "subRule" -> plain(names(request).subRules)
            else -> plain(node.values) + (if (node.values.isEmpty()) plain(request.documentWords()) else emptyList())
        }

        return completeFrom(typed, candidates)
    }

    private fun rule(request: CompletionRequest, typed: String, withPolicy: Boolean): Completion? {
        val type = typed.split(',').first().trim().uppercase()
        val open = typed.indexOf('(')

        if (open >= 0) {
            val depth = typed.count { it == '(' } - typed.count { it == ')' }

            if (depth > 0) {
                val inner = typed.substring(typed.lastIndexOf('(') + 1)

                return ruleParts(request, inner, withPolicy = false, nested = true)
            }

            val tail = typed.substring(typed.lastIndexOf(')') + 1)

            if (!withPolicy || !tail.startsWith(",") || tail.indexOf(',', 1) >= 0) return null

            val text = tail.substring(1).trimStart()

            return completeFrom(
                text,
                if (type == "SUB-RULE") plain(names(request).subRules) else policies(request, true),
            )
        }

        return ruleParts(request, typed, withPolicy, false)
    }

    private fun ruleParts(request: CompletionRequest, typed: String, withPolicy: Boolean, nested: Boolean): Completion? {
        val parts = typed.split(',')
        val text = parts.last().trimStart()
        val type = parts.first().trim().uppercase()

        when (parts.size) {
            1 -> {
                val candidates = ArrayList<Suggestion>()

                for ((ruleType, example) in schema.ruleTypes) {
                    if (nested && ruleType == "MATCH") continue

                    if (!withPolicy && (ruleType == "MATCH" || ruleType == "SUB-RULE")) continue

                    val group = ruleType == "AND" || ruleType == "OR" || ruleType == "NOT"

                    candidates.add(
                        if (group) Suggestion(ruleType, example, "$ruleType,(())", 2) else Suggestion(ruleType, example, "$ruleType,"),
                    )
                }

                return completeFrom(text, candidates)
            }
            2 -> {
                if (type == "MATCH") return if (withPolicy) completeFrom(text, policies(request, true)) else null

                return completeFrom(
                    text,
                    if (type == "RULE-SET") plain(names(request).ruleProviders) else plain(schema.rulePayloadValues[type] ?: emptyList()),
                )
            }
            3 -> {
                if (withPolicy && !nested) return completeFrom(text, policies(request, true))

                if (schema.noResolveRuleTypes.contains(type)) return completeFrom(text, plain(schema.ruleOptions))
            }
            4 -> {
                if (schema.noResolveRuleTypes.contains(type)) return completeFrom(text, plain(schema.ruleOptions))
            }
        }

        return null
    }

    private fun words(request: CompletionRequest, before: String): Completion? {
        val typed = wordBeforeCaret.find(before)?.value ?: ""

        return completeFrom(typed, plain(request.documentWords()))
    }

    private fun policies(request: CompletionRequest, withGlobal: Boolean): List<Suggestion> {
        val names = names(request)
        val out = ArrayList<Suggestion>()

        for ((key, value) in names.groups) out.add(Suggestion(key, value))
        for ((key, value) in names.proxies) out.add(Suggestion(key, value))

        for (policy in if (withGlobal) baseTargets + "GLOBAL" else baseTargets) {
            if (!names.groups.containsKey(policy)) out.add(Suggestion(policy))
        }

        return out
    }

    // the names are read again only when the text has changed since the last time they were asked for
    private var namesOf: List<String>? = null
    private var namesValue: DocumentNames? = null

    private fun names(request: CompletionRequest): DocumentNames {
        val cached = namesValue

        if (cached != null && namesOf === request.lines) return cached

        val fresh = DocumentNames(request.lines)

        namesOf = request.lines
        namesValue = fresh

        return fresh
    }
}

private fun plain(labels: Iterable<String>): List<Suggestion> = labels.map { Suggestion(it) }

/** A prefix beats a segment prefix, which beats the letters in order. */
fun completionScore(candidate: String, typed: String): Int? {
    if (typed.isEmpty()) return 0

    val lower = candidate.lowercase()
    val lowerTyped = typed.lowercase()

    if (lower.startsWith(lowerTyped)) {
        return 3000 - candidate.length + (if (candidate.startsWith(typed)) 100 else 0)
    }

    for (i in 1 until candidate.length) {
        if (startsSegment(candidate, i) && lower.startsWith(lowerTyped, i)) {
            return 2000 - i - candidate.length
        }
    }

    if (lower.isEmpty() || lower[0] != lowerTyped[0]) return null

    var matched = 1
    var i = 1

    while (i < lower.length && matched < lowerTyped.length) {
        if (lower[i] == lowerTyped[matched]) matched++

        i++
    }

    return if (matched == lowerTyped.length) 1000 - candidate.length else null
}

private fun startsSegment(text: String, index: Int): Boolean {
    val previous = text[index - 1]

    if (previous == '-' || previous == '_' || previous == '.') return true

    val current = text[index]

    return previous.lowercaseChar() == previous && previous.uppercaseChar() != previous &&
        current.uppercaseChar() == current && current.lowercaseChar() != current
}

fun rank(typed: String, candidates: Iterable<Suggestion>): List<Suggestion> {
    val seen = HashSet<String>()
    val scored = ArrayList<Triple<Int, Int, Suggestion>>()
    var index = 0

    for (candidate in candidates) {
        val label = candidate.label

        if (!seen.add(label) || (label == typed && candidate.insert == null)) continue

        val score = completionScore(label, typed) ?: continue

        scored.add(Triple(score, index++, candidate))
    }

    scored.sortWith(compareBy<Triple<Int, Int, Suggestion>>({ -it.first }, { it.second }))

    return scored.take(MAX_SUGGESTIONS).map { it.third }
}

fun completeFrom(typed: String, candidates: Iterable<Suggestion>): Completion? {
    if (typed.isEmpty()) return null

    return Completion(typed, rank(typed, candidates))
}

/** The names rules and groups refer to, read from the document's own top-level sections. */
class DocumentNames(lines: List<String>) {
    val groups = LinkedHashMap<String, String>()
    val proxies = LinkedHashMap<String, String>()
    val proxyProviders = ArrayList<String>()
    val ruleProviders = ArrayList<String>()
    val subRules = ArrayList<String>()

    private class Item(val column: Int) {
        var name: String? = null
        var type: String? = null

        fun readFlow(content: String) {
            for (match in flowField.findAll(content)) {
                val value = unquoteYaml(match.groupValues[2])

                if (match.groupValues[1] == "name") name = value else type = value
            }
        }

        companion object {
            private val flowField = Regex("""(?:^|[{,])\s*(name|type)\s*:\s*('[^']*'|"[^"]*"|[^,}]*)""")
        }
    }

    init {
        var section: String? = null
        var childIndent: Int? = null
        var item: Item? = null

        fun flush() {
            val current = item
            val name = current?.name

            if (current != null && name != null && name.isNotEmpty()) {
                val target = if (section == "proxy-groups") groups else proxies

                if (!target.containsKey(name)) target[name] = current.type ?: ""
            }

            item = null
        }

        for (text in lines) {
            if (!mayName(text, section, childIndent, item)) continue

            val line = YamlLine.parse(text) ?: continue

            if (line.indent == 0 && !line.isItem) {
                flush()
                section = line.key
                childIndent = null

                continue
            }

            when (section) {
                "proxies", "proxy-groups" -> {
                    var skip = false

                    if (line.isItem) {
                        if (childIndent == null) childIndent = line.indent

                        if (line.indent == childIndent) {
                            flush()

                            val created = Item(line.contentColumn)

                            item = created

                            if (line.content.startsWith("{")) {
                                created.readFlow(line.content)
                                flush()

                                skip = true
                            }
                        }
                    }

                    val current = item

                    if (!skip && current != null && line.contentColumn == current.column) {
                        when (line.key) {
                            "name" -> current.name = line.value
                            "type" -> current.type = line.value
                        }
                    }
                }
                "proxy-providers", "rule-providers", "sub-rules" -> {
                    if (!line.isItem && line.key != null) {
                        if (childIndent == null) childIndent = line.indent

                        if (line.indent == childIndent) {
                            val names = when (section) {
                                "proxy-providers" -> proxyProviders
                                "rule-providers" -> ruleProviders
                                else -> subRules
                            }

                            names.add(line.key)
                        }
                    }
                }
            }
        }

        flush()
    }

    private fun mayName(text: String, section: String?, childIndent: Int?, item: Item?): Boolean {
        val indent = YamlLine.indentOf(text)

        if (indent == text.length) return false

        val isItem = YamlLine.isItemAt(text, indent)

        if (indent == 0 && !isItem) return true

        return when (section) {
            "proxies", "proxy-groups" ->
                isItem || (
                    item != null && indent == item.column &&
                        (text.startsWith("name", indent) || text.startsWith("type", indent) ||
                            text.startsWith("\"", indent) || text.startsWith("'", indent))
                    )
            "proxy-providers", "rule-providers", "sub-rules" -> !isItem && (childIndent == null || indent == childIndent)
            else -> false
        }
    }
}
