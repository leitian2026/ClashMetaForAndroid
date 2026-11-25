package com.github.kr328.clash.design.custom.editor

import com.github.kr328.clash.custom.BackupExtrasRestorePolicy
import java.util.UUID

object BackupExtrasRestoreTests {
    fun run() {
        val target = UUID.fromString("00000000-0000-0000-0000-000000000001")
        val mapped = BackupExtrasRestorePolicy.mapSelections(
            listOf(
                BackupExtrasRestorePolicy.SelectionValue("old", "proxy-a", "node-a"),
                BackupExtrasRestorePolicy.SelectionValue("old", "proxy-b", "node-b"),
                BackupExtrasRestorePolicy.SelectionValue("missing", "proxy-c", "node-c"),
            ),
            mapOf("old" to target),
        )
        check(mapped[target] == listOf("proxy-a" to "node-a", "proxy-b" to "node-b"), "selection UUID remapping")
        check(BackupExtrasRestorePolicy.isSafeRelativePath("custom/history/a.json"), "nested backup path accepted")
        check(!BackupExtrasRestorePolicy.isSafeRelativePath("../outside"), "parent traversal rejected")
        check(!BackupExtrasRestorePolicy.isSafeRelativePath("/absolute"), "absolute path rejected")
        check(!BackupExtrasRestorePolicy.isSafeRelativePath("a\\b"), "backslash path rejected")
        check(!BackupExtrasRestorePolicy.isSafeRelativePath("a//b"), "empty path segment rejected")
    }
}
