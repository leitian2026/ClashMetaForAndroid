package com.github.kr328.clash.design.custom.editor

object YamlLintTests {
    fun run() {
        check(YamlLint.run("port: 7890\nmode: rule\n").isEmpty(), "YamlLint valid scalar config")
        check(YamlLint.run("\tbad: value").any { it.kind == DiagKind.Tab }, "YamlLint tab indentation")
        check(YamlLint.run("a: 1\n    b: 2").any { it.kind == DiagKind.IndentDeeper }, "YamlLint unexpected deeper indentation")
        check(YamlLint.run("root:\n  child:\n    nested: value\n   sibling: value")
            .any { it.kind == DiagKind.IndentMisaligned }, "YamlLint misaligned indentation")
        check(YamlLint.run("port: 1\nport: 2").any { it.kind == DiagKind.DupKey }, "YamlLint duplicate key")
        check(YamlLint.run("proxies:\n  - name: p\n    type: direct\n  - name: p\n    type: direct")
            .any { it.kind == DiagKind.DupName }, "YamlLint duplicate proxy name")
        check(YamlLint.run("proxy-groups:\n  - name: g\n    type: select\n    proxies:\n      - absent")
            .any { it.kind == DiagKind.MissingProxy }, "YamlLint missing proxy reference")
        check(YamlLint.run("proxy-groups:\n  - name: g\n    type: select\n    use:\n      - providerX")
            .any { it.kind == DiagKind.MissingProvider }, "YamlLint missing proxy provider")
        check(YamlLint.run("rules:\n  - RULE-SET,missing,DIRECT")
            .any { it.kind == DiagKind.MissingRuleSet }, "YamlLint missing rule provider")
        val text = "proxy-groups:\n  - name: groupA\n    type: select\n    proxies:\n      - nodeA\nproxies:\n  - name: nodeA\n    type: direct"
        val links = YamlLint.links(text)
        check(links.refs.any { it.name == "nodeA" && it.kind == "proxy" }, "YamlLint links collects proxy reference")
        val resolution = YamlLint.resolve(links, text, text.indexOf("nodeA"))
        check(resolution.link?.name == "nodeA" && resolution.definitionLine == 6, "YamlLint resolve finds proxy definition")
        val builtins = "rules:\n  - MATCH,DIRECT"
        val builtinLinks = YamlLint.links(builtins)
        val builtin = YamlLint.resolve(builtinLinks, builtins, builtins.indexOf("DIRECT"))
        check(builtin.builtIn && builtin.definitionLine == null, "YamlLint resolve recognizes built-in policy")
        val providerText = "proxy-providers:\n  providerA:\n    type: http\nproxy-groups:\n  - name: g\n    type: select\n    use:\n      - providerA"
        val providerLinks = YamlLint.links(providerText)
        val providerResolution = YamlLint.resolve(providerLinks, providerText, providerText.lastIndexOf("providerA"))
        check(providerResolution.definitionLine == 1, "YamlLint resolve finds provider definition")
    }
}
