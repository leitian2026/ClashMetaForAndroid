package com.github.kr328.clash.design.custom.editor

object YamlPairsTests {
    fun run() {
        check(YamlPairs.matchBracket("a(b)c", 1) == 3, "YamlPairs forward matching")
        check(YamlPairs.matchBracket("a(b)c", 3) == 1, "YamlPairs backward matching")
        check(YamlPairs.matchBracket("((x))", 0) == 4, "YamlPairs nested same-kind brackets")
        check(YamlPairs.matchBracket("([)]", 0) == 2, "YamlPairs ignores other bracket kinds")
        check(YamlPairs.matchBracket("(", 0) == YamlPairs.UNMATCHED, "YamlPairs unmatched opener")
        check(YamlPairs.matchBracket(")", 0) == YamlPairs.UNMATCHED, "YamlPairs unmatched closer")
        check(YamlPairs.matchBracket("(((", 0, limit = 2) == YamlPairs.UNKNOWN, "YamlPairs forward limit")
        check(YamlPairs.matchBracket(")))", 2, limit = 1) == YamlPairs.UNKNOWN, "YamlPairs backward limit")
        check(YamlPairs.matchBracket("abc", 1) == YamlPairs.UNKNOWN, "YamlPairs non-bracket")
        val plain = YamlPairs.quotes("key: \"value\"")
        check(plain.size == 1 && plain[0][0] == 5 && plain[0][1] == 11, "YamlPairs ordinary double quotes")
        val escaped = "key: \"a\\\"b\""
        val escapedRanges = YamlPairs.quotes(escaped)
        check(escapedRanges.size == 1 && escapedRanges[0][1] == escaped.lastIndexOf('"'), "YamlPairs escaped double quote")
        val doubled = """key: 'it''s'"""
        val doubledRanges = YamlPairs.quotes(doubled)
        check(doubledRanges.size == 1 && doubledRanges[0][1] == doubled.lastIndexOf('\''), "YamlPairs doubled single quote")
        val open = YamlPairs.quotes("value: 'unfinished")
        check(open.size == 1 && open[0][1] == -1, "YamlPairs unclosed quote")
        check(YamlPairs.quotes("don't").isEmpty(), "YamlPairs apostrophe inside word")
        check(YamlPairs.quotes("x # \"comment\"").isEmpty(), "YamlPairs ignores quotes in comment")
    }
}
