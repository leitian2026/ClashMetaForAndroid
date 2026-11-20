package com.github.kr328.clash.design.custom

import android.os.Bundle

// CUSTOM (CUSTOM.md #164): status and column filters are isolated per saved-IP operator.
// CUSTOM (CUSTOM.md #164): persist independent filters by operator ID and page-wide speed order.
internal class SavedIpFilterState private constructor(
    val selectedStatuses: LinkedHashMap<String, LinkedHashSet<SavedIpStatus>>,
    val selectedTableFilters: LinkedHashMap<String, LinkedHashMap<Int, LinkedHashSet<String>>>,
    var sort: SavedIpSort,
    var query: String,
    var searchExpanded: Boolean,
    var expandedOperatorId: String?,
) {
    fun save(outState: Bundle) {
        selectedStatuses.forEach { (operatorId, statuses) ->
            outState.putStringArrayList(KEY_STATUS_PREFIX + operatorId, ArrayList(statuses.map { it.name }))
        }
        selectedTableFilters.forEach { (operatorId, columns) ->
            columns.forEach { (column, values) ->
                outState.putStringArrayList(KEY_TABLE_FILTER_PREFIX + operatorId + "_" + column, ArrayList(values))
            }
        }
        outState.putString(KEY_SORT, sort.name)
        outState.putString(KEY_QUERY, query)
        outState.putBoolean(KEY_SEARCH_EXPANDED, searchExpanded)
        outState.putString(KEY_EXPANDED_OPERATOR, expandedOperatorId)
    }

    companion object {
        private const val KEY_STATUS_PREFIX = "saved_ip_statuses_"
        private const val KEY_TABLE_FILTER_PREFIX = "saved_ip_table_filter_"
        private const val KEY_SORT = "saved_ip_filter_sort"
        private const val KEY_QUERY = "saved_ip_filter_query"
        private const val KEY_SEARCH_EXPANDED = "saved_ip_filter_search_expanded"
        private const val KEY_EXPANDED_OPERATOR = "saved_ip_filter_expanded_operator"

        fun restore(state: Bundle?): SavedIpFilterState {
            val statuses = LinkedHashMap<String, LinkedHashSet<SavedIpStatus>>()
            val tableFilters = LinkedHashMap<String, LinkedHashMap<Int, LinkedHashSet<String>>>()
            state?.keySet().orEmpty().forEach { key ->
                when {
                    key.startsWith(KEY_STATUS_PREFIX) -> {
                        val id = key.removePrefix(KEY_STATUS_PREFIX)
                        val values = state?.getStringArrayList(key).orEmpty()
                            .mapNotNull { runCatching { SavedIpStatus.valueOf(it) }.getOrNull() }
                        // CUSTOM (CUSTOM.md #164): migrate any earlier multi-select state to one status.
                        if (values.isNotEmpty()) statuses[id] = linkedSetOf(values.first())
                    }
                    key.startsWith(KEY_TABLE_FILTER_PREFIX) -> {
                        val suffix = key.removePrefix(KEY_TABLE_FILTER_PREFIX)
                        val column = suffix.substringAfterLast('_').toIntOrNull() ?: return@forEach
                        val id = suffix.substringBeforeLast('_')
                        val values = state?.getStringArrayList(key).orEmpty()
                        if (id.isNotEmpty() && values.isNotEmpty()) {
                            tableFilters.getOrPut(id) { LinkedHashMap() }[column] = LinkedHashSet(values)
                        }
                    }
                }
            }
            val sort = state?.getString(KEY_SORT)?.let { runCatching { SavedIpSort.valueOf(it) }.getOrNull() }
                ?: SavedIpSort.DEFAULT
            return SavedIpFilterState(
                selectedStatuses = statuses,
                selectedTableFilters = tableFilters,
                sort = sort,
                query = state?.getString(KEY_QUERY).orEmpty(),
                searchExpanded = state?.getBoolean(KEY_SEARCH_EXPANDED) ?: false,
                expandedOperatorId = state?.getString(KEY_EXPANDED_OPERATOR),
            )
        }
    }
}
