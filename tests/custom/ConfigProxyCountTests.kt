package com.github.kr328.clash.design.custom.editor

import com.github.kr328.clash.common.custom.ConfigProxyCount

object ConfigProxyCountTests {
    fun run() {
        blockEntries()
        flowStyleBlockEntries()
        commentsAndBlankLines()
        emptyProxies()
        noProxiesSection()
        oneLineFlowIsUnknown()
        dropBoundaries()
    }

    private fun blockEntries() {
        val text = """
            proxies:
              - name: A
              - name: B
              - name: C
            proxy-groups:
              - name: G
        """.trimIndent()
        check(ConfigProxyCount.count(text) == 3, "counts block proxy entries")
    }

    private fun flowStyleBlockEntries() {
        val text = """
            proxies:
              - {name: A, type: ss}
              - {name: B, type: ss}
            rules:
              - MATCH,DIRECT
        """.trimIndent()
        check(ConfigProxyCount.count(text) == 2, "counts flow mapping entries in block sequence")
    }

    private fun commentsAndBlankLines() {
        val text = """
            proxies:
              # comment
              
              - name: A # inline comment
              # another comment
              - name: B
            proxy-groups: []
        """.trimIndent()
        check(ConfigProxyCount.count(text) == 2, "ignores comments and blank lines")
    }

    private fun emptyProxies() {
        check(ConfigProxyCount.count("proxies: []\nproxy-groups: []") == 0, "empty flow proxies has zero entries")
        check(ConfigProxyCount.count("proxies:\nproxy-groups: []") == 0, "empty block proxies has zero entries")
    }

    private fun noProxiesSection() {
        check(ConfigProxyCount.count("proxy-groups: []\nrules: []") == 0, "missing proxies section has zero entries")
    }

    private fun oneLineFlowIsUnknown() {
        check(ConfigProxyCount.count("proxies: [A, B]") == -1, "one-line flow sequence is unknown")
        check(ConfigProxyCount.count("proxies: [{name: A}, {name: B}]") == -1, "one-line flow mappings are unknown")
    }

    private fun dropBoundaries() {
        check(ConfigProxyCount.dropped(10, 5), "drop at exact half threshold")
        check(!ConfigProxyCount.dropped(9, 4), "old count below ten does not trigger")
        check(!ConfigProxyCount.dropped(10, 6), "drop smaller than half does not trigger")
        check(!ConfigProxyCount.dropped(10, -1), "unknown new count does not trigger")
        check(!ConfigProxyCount.dropped(-1, 0), "unknown old count does not trigger")
    }
}
