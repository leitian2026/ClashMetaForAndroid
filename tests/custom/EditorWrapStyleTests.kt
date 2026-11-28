package com.github.kr328.clash.design.custom.editor

object EditorWrapStyleTests {
    fun run() {
        check(EditorWrapStyle.visibleColumns(400, 10f) == 40, "wrap visible columns")
        check(EditorWrapStyle.visibleColumns(0, 10f) == 0, "wrap zero width")
        check(EditorWrapStyle.continuationIndentColumns("short", 10) == null, "wrap short line unchanged")
        check(EditorWrapStyle.continuationIndentColumns("1234567890", 10) == null, "wrap exact threshold unchanged")
        check(EditorWrapStyle.continuationIndentColumns("12345678901", 10) == 4, "wrap long line gets four columns")
        check(EditorWrapStyle.continuationIndentColumns("   12345678901", 10) == 7, "wrap keeps leading spaces plus four")
        check(EditorWrapStyle.continuationIndentColumns("", 10) == null, "wrap empty line unchanged")
        check(EditorWrapStyle.continuationIndentColumns("a very long line", 0) == null, "wrap disabled without width")
        check(!EditorWrapStyle.needsSeparator(1), "separator omitted for one display line")
        check(EditorWrapStyle.needsSeparator(2), "separator for two display lines")
        check(EditorWrapStyle.needsSeparator(3), "separator for multiple display lines")
    }
}
