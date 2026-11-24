package com.github.kr328.clash.design.custom.editor

object YamlRenameTests {
    fun run() {
        blockDefinitionAndReferences()
        flowProxyReferences()
        rulePolicyReference()
        quotedNameStyle()
        specialNameGetsQuotes()
        duplicateNameRejected()
        builtinRejected()
        ambiguousLineIsNotChanged()
        reportsOtherOccurrences()
    }

    private fun blockDefinitionAndReferences() {
        val text = """
            proxies:
              - name: A
              - name: B
            proxy-groups:
              - name: G
                proxies:
                  - A
            rules:
              - DOMAIN,example.com,G
        """.trimIndent()
        val plan = YamlRename.plan(text, text.indexOf("A", text.indexOf("name: A")), "Renamed")
        val result = YamlRename.apply(text, plan.changes)
        check(plan.error == null && plan.definitionCount == 1 && plan.referenceCount == 1, "rename block definition and proxy reference")
        check(result.contains("name: Renamed") && result.contains("- Renamed"), "block rename writes both locations")
        check(result.contains("DOMAIN,example.com,G"), "unrelated policy is not changed")
    }

    private fun flowProxyReferences() {
        val text = """
            proxies:
              - name: A
              - name: B
            proxy-groups:
              - {name: G, type: select, proxies: [A, B]}
        """.trimIndent()
        val at = text.indexOf("A, B")
        val plan = YamlRename.plan(text, at, "Node A")
        val result = YamlRename.apply(text, plan.changes)
        check(plan.error == null && plan.referenceCount == 1, "flow proxy reference resolves")
        check(result.contains("proxies: [\"Node A\", B]"), "flow proxy reference is quoted when needed")
    }

    private fun rulePolicyReference() {
        val text = """
            proxies:
              - name: A
            rules:
              - DOMAIN,example.com,A
        """.trimIndent()
        val at = text.lastIndexOf("A")
        val plan = YamlRename.plan(text, at, "B")
        check(plan.error == null && plan.referenceCount == 1, "rule policy reference resolves")
        check(YamlRename.apply(text, plan.changes).contains("DOMAIN,example.com,B"), "rule policy is renamed")
    }

    private fun quotedNameStyle() {
        val text = """
            proxies:
              - name: 'A'
              - name: B
            proxy-groups:
              - name: G
                proxies: ['A']
        """.trimIndent()
        val at = text.indexOf("A", text.indexOf("name:"))
        val plan = YamlRename.plan(text, at, "C")
        val result = YamlRename.apply(text, plan.changes)
        check(plan.error == null, "quoted name can be selected")
        check(result.contains("name: 'C'") && result.contains("proxies: ['C']"), "single quote style is preserved")
    }

    private fun specialNameGetsQuotes() {
        val text = """
            proxies:
              - name: A
              - name: B
            proxy-groups:
              - name: G
                proxies: [A]
        """.trimIndent()
        val plan = YamlRename.plan(text, text.indexOf("A", text.indexOf("name:")), "A: B")
        val result = YamlRename.apply(text, plan.changes)
        check(plan.error == null, "special replacement name is accepted")
        check(result.contains("name: \"A: B\"") && result.contains("proxies: [\"A: B\"]"), "special replacement name is double quoted")
    }

    private fun duplicateNameRejected() {
        val text = """
            proxies:
              - name: A
              - name: B
        """.trimIndent()
        val plan = YamlRename.plan(text, text.indexOf("A", text.indexOf("name:")), "B")
        check(plan.error?.contains("第 3 行") == true, "duplicate name reports its definition line")
    }

    private fun builtinRejected() {
        val text = """
            proxies:
              - name: A
            proxy-groups:
              - name: G
                proxies: [DIRECT, A]
        """.trimIndent()
        val plan = YamlRename.plan(text, text.indexOf("DIRECT"), "Local")
        check(plan.error?.contains("内置名称") == true, "built-in name cannot be renamed")
    }

    private fun ambiguousLineIsNotChanged() {
        val text = """
            proxies:
              - name: A
              - name: B
            proxy-groups:
              - {name: G, type: select, dialer-proxy: A, proxies: [A]}
            rules:
              - DOMAIN,example.com,A
        """.trimIndent()
        val plan = YamlRename.plan(text, text.indexOf("A", text.indexOf("name:")), "C")
        val result = YamlRename.apply(text, plan.changes)
        check(plan.error == null && plan.referenceCount == 1, "only uniquely located reference is changed")
        check(result.contains("dialer-proxy: A, proxies: [A]"), "ambiguous same-line candidates stay unchanged")
        check(result.contains("DOMAIN,example.com,C"), "separate confirmed reference is changed")
        check(plan.untouched.any { it.line == 6 }, "ambiguous location is reported")
    }

    private fun reportsOtherOccurrences() {
        val text = """
            proxies:
              - name: A
              - name: B
            proxy-groups:
              - name: G
                proxies: [A]
            proxy-groups-extra:
              - dialer-proxy: A
        """.trimIndent()
        val plan = YamlRename.plan(text, text.indexOf("A", text.indexOf("name:")), "C")
        check(plan.otherOccurrences.contains(8), "unmodified non-comment occurrence is reported")
    }
}
