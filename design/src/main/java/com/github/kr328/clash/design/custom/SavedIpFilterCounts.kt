package com.github.kr328.clash.design.custom

// CUSTOM (CUSTOM.md #164): count visible rows for each value when a saved-table column popup is opened.
internal object SavedIpFilterCounts {
    fun count(
        entries: List<SavedIpEntry>,
        columnName: String,
        valueOf: (SavedIpEntry, String) -> String,
        selectedStatuses: Set<SavedIpStatus>,
        selectedOtherColumns: Map<String, Set<String>>,
        query: String,
        matchesSearch: (SavedIpEntry, String) -> Boolean,
    ): Map<String, Int> {
        val counts = LinkedHashMap<String, Int>()
        for (entry in entries) {
            if (selectedStatuses.isNotEmpty() && entry.status !in selectedStatuses) continue

            var matchesColumns = true
            for ((otherColumn, selectedValues) in selectedOtherColumns) {
                if (otherColumn == columnName || selectedValues.isEmpty()) continue
                if (valueOf(entry, otherColumn) !in selectedValues) {
                    matchesColumns = false
                    break
                }
            }
            if (!matchesColumns) continue
            if (query.isNotBlank() && !matchesSearch(entry, query)) continue

            val value = valueOf(entry, columnName)
            if (value.isNotBlank() && value != "-") {
                counts[value] = (counts[value] ?: 0) + 1
            }
        }
        return counts
    }
}
