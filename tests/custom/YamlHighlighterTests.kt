package com.github.kr328.clash.design.custom.editor

private fun yamlTokens(line: String): List<Pair<String, Tok>> {
    val result = ArrayList<Pair<String, Tok>>()

    YamlHighlighter.line(line) { from, to, kind ->
        result.add(line.substring(from, to) to kind)
    }

    return result
}

private fun hasYamlToken(tokens: List<Pair<String, Tok>>, text: String, kind: Tok): Boolean {
    return tokens.any { it.first == text && it.second == kind }
}

object YamlHighlighterTests {
    fun run() {
        val single = yamlTokens("""- {name: "[Vless] HK", server: 1.2.3.4, port: 443}""")
        check(
            listOf("name", "server", "port").all { hasYamlToken(single, it, Tok.Key) },
            "flow map keys are coloured as keys",
        )
        check(single.count { it.second == Tok.Punct && it.first == ":" } == 3, "flow map key colons are punctuation")

        val quoted = yamlTokens("""- {name: "[Vless] HK TG:@MiaChatChannel", url: "https://example.test/a,b"}""")
        check(hasYamlToken(quoted, "\"[Vless] HK TG:@MiaChatChannel\"", Tok.NodeName), "quoted node name has its own token")
        check(hasYamlToken(quoted, "\"https://example.test/a,b\"", Tok.Str), "quoted colon and comma remain string content")
        check(
            quoted.filter { it.second == Tok.Key }.map { it.first } == listOf("name", "url"),
            "quoted colons and commas do not create false keys",
        )

        val nested = yamlTokens("xhttp-opts: {path: /, host: x}")
        check(
            listOf("xhttp-opts", "path", "host").all { hasYamlToken(nested, it, Tok.Key) },
            "nested flow map keys are coloured",
        )

        val sequence = yamlTokens("alpn: [h2, http/1.1]")
        check(hasYamlToken(sequence, "alpn", Tok.Key), "sequence property remains a key")
        check(sequence.none { it.second == Tok.Key && it.first != "alpn" }, "sequence values do not become keys")

        val urls = yamlTokens("{url: https://example.test/a,b, ip: 2001:db8::1}")
        check(
            urls.filter { it.second == Tok.Key }.map { it.first } == listOf("url", "ip"),
            "URL and IPv6 colons do not create keys",
        )
        check(urls.count { it.second == Tok.Punct && it.first == ":" } == 2, "URL and IPv6 colons are not punctuation")

        val merge = yamlTokens("{ <<: *p, name: JP-1 }")
        check(hasYamlToken(merge, "<<", Tok.Key), "merge key is recognised")
        check(hasYamlToken(merge, "*p", Tok.Anchor), "merge anchor keeps anchor colour")

        val quotedName = yamlTokens("""{name: "JP-1"}""")
        val plainName = yamlTokens("{name: JP-2}")
        check(hasYamlToken(quotedName, "\"JP-1\"", Tok.NodeName), "quoted node name is highlighted")
        check(hasYamlToken(plainName, "JP-2", Tok.NodeName), "plain node name is highlighted")

        val blockName = yamlTokens("""name: "block" """.trimEnd())
        val comment = yamlTokens("# name: comment")
        val ordinary = yamlTokens("server: https://example.test/a,b")
        check(hasYamlToken(blockName, "name", Tok.Key), "block key keeps existing key colour")
        check(hasYamlToken(blockName, "\"block\"", Tok.Str), "block value keeps existing string colour")
        check(blockName.none { it.second == Tok.NodeName }, "block name value is not recoloured")
        check(comment.any { it.second == Tok.Comment }, "comment keeps existing comment colour")
        check(ordinary.filter { it.second == Tok.Key }.map { it.first } == listOf("server"), "ordinary key-value line is unchanged")
        check(ordinary.none { it.second == Tok.NodeName }, "ordinary value is not recoloured as a node name")
    }
}
