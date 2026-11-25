package com.github.kr328.clash.design.custom.editor

object YamlFixTests {
    fun run() {
        val tabText = "\tbad: value\n"
        val tabDiagnostic = YamlLint.run(tabText).first { it.kind == DiagKind.Tab }
        val tabPlan = YamlFix.plan(tabText, tabDiagnostic)
        check(tabPlan != null && tabPlan.candidates.size == 1, "YamlFix creates one Tab repair")
        if (tabPlan != null) {
            val fixed = YamlRename.apply(tabText, tabPlan.candidates.single().changes)
            check(fixed == "  bad: value\n", "YamlFix replaces indentation Tab with detected spaces")
            check(YamlLint.run(fixed).none { it.kind == DiagKind.Tab }, "YamlFix Tab repair removes Tab diagnostic")
        }

        val missingText = "proxy-groups:\n  - name: g\n    type: select\n    proxies:\n      - nodeB\nproxies:\n  - name: nodeA\n    type: direct\n"
        val missing = YamlLint.run(missingText).first { it.kind == DiagKind.MissingProxy }
        val renamePlan = YamlFix.plan(missingText, missing)
        check(renamePlan != null && renamePlan.candidates.any { it.description.newName == "nodeA" },
            "YamlFix suggests existing proxy name")
        val rename = renamePlan?.candidates?.firstOrNull { it.description.newName == "nodeA" }
        if (rename != null) {
            val fixed = YamlRename.apply(missingText, rename.changes)
            check(fixed.contains("- nodeA\n"), "YamlFix rename changes only reference value")
            check(YamlLint.run(fixed).none { it.kind == DiagKind.MissingProxy },
                "YamlFix rename removes missing proxy diagnostic")
        }

        val duplicate = Diagnostic(0, 0, DiagKind.DupKey, "port")
        check(YamlFix.plan("port: 1\nport: 2\n", duplicate) == null,
            "YamlFix does not guess a duplicate-key repair")
        check(YamlFix.splitLines("a\r\nb\n").map { it.ending } == listOf("\r\n", "\n", ""),
            "YamlFix preserves CRLF and trailing newline boundaries")
    }
}
