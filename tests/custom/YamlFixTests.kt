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

        val deeperText = "a: 1\n    b: 2\n      c: 3\n"
        val deeper = YamlLint.run(deeperText).first { it.kind == DiagKind.IndentDeeper }
        val deeperPlan = YamlFix.plan(deeperText, deeper)
        check(deeperPlan?.candidates?.isNotEmpty() == true, "YamlFix offers a validated deeper-indent repair")
        val deeperCandidate = deeperPlan?.candidates?.firstOrNull()
        if (deeperCandidate != null) {
            val fixed = YamlRename.apply(deeperText, deeperCandidate.changes)
            check(YamlLint.run(fixed).none { it.kind == DiagKind.IndentDeeper },
                "YamlFix moves the indented block together")
        }

        val misalignedText = "root:\n  child:\n    nested: value\n   sibling: value"
        val misaligned = YamlLint.run(misalignedText).first { it.kind == DiagKind.IndentMisaligned }
        val misalignedPlan = YamlFix.plan(misalignedText, misaligned)
        check(misalignedPlan?.candidates?.isNotEmpty() == true,
            "YamlFix offers validated alternatives for misaligned indentation")

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

        val siblingList = "proxy-groups:\n  - name: g\n    type: select\n    proxies:\n      - absent\n      - nodeA\nproxies:\n  - name: nodeA\n    type: direct\n"
        val siblingProblem = YamlLint.run(siblingList).first { it.kind == DiagKind.MissingProxy }
        val siblingPlan = YamlFix.plan(siblingList, siblingProblem)
        check(siblingPlan?.candidates?.any { it.description.kind == FixDescriptionKind.DeleteLine } == true,
            "YamlFix offers deletion when the same list has another item")

        val separateLists = "proxy-groups:\n  - name: g1\n    type: select\n    proxies:\n      - absent\n  - name: g2\n    type: select\n    proxies:\n      - nodeA\nproxies:\n  - name: nodeA\n    type: direct\n"
        val separateProblem = YamlLint.run(separateLists).first { it.kind == DiagKind.MissingProxy }
        val separatePlan = YamlFix.plan(separateLists, separateProblem)
        check(separatePlan?.candidates?.none { it.description.kind == FixDescriptionKind.DeleteLine } == true,
            "YamlFix does not treat another list's item as a deletion sibling")

        val deleteOnlyText = "proxy-groups:\n  - name: g\n    type: select\n    use:\n      - missingA\n      - missingB\n"
        val deleteOnlyProblem = YamlLint.run(deleteOnlyText).first { it.kind == DiagKind.MissingProvider }
        val deleteOnlyPlan = YamlFix.plan(deleteOnlyText, deleteOnlyProblem)
        check(deleteOnlyPlan?.candidates?.singleOrNull()?.description?.kind == FixDescriptionKind.DeleteLine &&
            deleteOnlyPlan.defaultSelectedIndex == null,
            "YamlFix keeps a sole delete option unselected for explicit user choice")

        val duplicate = Diagnostic(0, 0, DiagKind.DupKey, "port")
        check(YamlFix.plan("port: 1\nport: 2\n", duplicate) == null,
            "YamlFix does not guess a duplicate-key repair")
        check(YamlFix.splitLines("a\r\nb\n").map { it.ending } == listOf("\r\n", "\n", ""),
            "YamlFix preserves CRLF and trailing newline boundaries")
    }
}
