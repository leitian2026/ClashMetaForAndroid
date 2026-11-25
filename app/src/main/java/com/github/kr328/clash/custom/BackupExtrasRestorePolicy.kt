// CUSTOM (CUSTOM.md #17)
package com.github.kr328.clash.custom

import java.util.UUID

/** Pure mapping and path checks shared by the backup restore adapter and JVM tests. */
internal object BackupExtrasRestorePolicy {
    data class SelectionValue(val backupUuid: String, val proxy: String, val selected: String)

    fun mapSelections(
        values: List<SelectionValue>,
        uuidMap: Map<String, UUID>,
    ): Map<UUID, List<Pair<String, String>>> {
        val result = LinkedHashMap<UUID, MutableList<Pair<String, String>>>()
        values.forEach { value ->
            val uuid = uuidMap[value.backupUuid] ?: return@forEach
            result.getOrPut(uuid) { ArrayList() }.add(value.proxy to value.selected)
        }
        return result
    }

    fun isSafeRelativePath(path: String): Boolean {
        if (path.isBlank() || path.startsWith('/') || path.startsWith('\\') || '\u0000' in path) return false
        if ('\\' in path) return false
        return path.split('/').all { it.isNotEmpty() && it != "." && it != ".." }
    }
}
