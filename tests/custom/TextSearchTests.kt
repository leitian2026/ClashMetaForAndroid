package com.github.kr328.clash.design.custom.editor

object TextSearchTests {
    fun run() {
        check(TextSearch("cat", true, false).next("cat dog cat", 1) == (8..10), "TextSearch ordinary next")
        check(TextSearch("CAT", false, false).next("cat", 0) == (0..2), "TextSearch case-insensitive")
        check(TextSearch("\\d+", true, true).next("a12b", 0) == (1..2), "TextSearch regex next")
        check(TextSearch("cat", true, false).prev("cat dog cat", 9) == (8..10), "TextSearch ordinary previous")
        check(TextSearch("cat", true, false).next("cat", 3) == null, "TextSearch next does not wrap by itself")
        check(TextSearch("cat", true, false).prev("cat", 0) == null, "TextSearch previous does not wrap by itself")
        check(TextSearch("\\d+", true, true).prev("a12 b3", 6) == (5..5), "TextSearch regex previous")
        check(TextSearch("aa", true, false).count("aaaaa") == 2, "TextSearch non-overlapping count")
        check(TextSearch("one", true, false).position("one two one", 8, 11) == (2 to 2), "TextSearch 1-based position")
        check(TextSearch("(\\w+)-(\\w+)", true, true).replacement("foo-bar", 0..6, "\\$2/\\$1") == "bar/foo", "TextSearch replacement groups")
        val all = TextSearch("(\\w)(\\d)", true, true).replaceAllText("a1 b2", "\\$2\\$1")
        check(all == Triple("1a 2b", 2, 0), "TextSearch replaceAll result and count")
        val invalid = TextSearch("(", true, true)
        check(!invalid.valid && invalid.next("abc", 0) == null, "TextSearch invalid regex")
        check(TextSearch("(?=b)", true, true).next("ab", 0) == null, "TextSearch zero-width next is skipped")
        check(TextSearch("(?=b)", true, true).count("ab") == 0, "TextSearch zero-width count is skipped")
        check(TextSearch("(?=b)", true, true).replaceAllText("ab", "X") == Triple("ab", 0, -1), "TextSearch zero-width replacement leaves text unchanged")
    }
}
