package com.github.kr328.clash.design.custom

import android.os.Bundle

/** The mutually exclusive status filter shown on the saved-IP page. */
internal enum class IpFilter {
    ALL,
    REACHABLE,
    UNREACHABLE,
    UNTESTED,
}

// CUSTOM.md #156 (r290): keep saved-IP page filters and restorable view state in one place.
internal class SavedIpFilterState private constructor(
    var currentFilter: IpFilter,
    val selectedTableFilters: LinkedHashMap<Int, LinkedHashSet<String>>,
    var sort: SavedIpSort,
    var query: String,
    var searchExpanded: Boolean,
    var expandedOperatorId: String?,
) {
    fun matchesStatus(status: SavedIpStatus): Boolean = when (currentFilter) {
        IpFilter.ALL -> true
        IpFilter.REACHABLE -> status == SavedIpStatus.REACHABLE
        IpFilter.UNREACHABLE -> status == SavedIpStatus.UNREACHABLE
        IpFilter.UNTESTED -> status == SavedIpStatus.UNTESTED
    }

    fun save(outState: Bundle) {
        outState.putString(KEY_STATUS, currentFilter.name)
        selectedTableFilters.forEach { (column, values) -> outState.putStringArrayList(KEY_TABLE_FILTER_PREFIX + column, ArrayList(values)) }
        outState.putString(KEY_SORT, sort.name)
        outState.putString(KEY_QUERY, query)
        outState.putBoolean(KEY_SEARCH_EXPANDED, searchExpanded)
        outState.putString(KEY_EXPANDED_OPERATOR, expandedOperatorId)
    }

    companion object {
        private const val KEY_STATUS = "saved_ip_filter_status"
        private const val KEY_LEGACY_STATUSES = "saved_ip_filter_statuses"
        private const val KEY_TABLE_FILTER_PREFIX = "saved_ip_table_filter_"
        private const val KEY_SORT = "saved_ip_filter_sort"
        private const val KEY_QUERY = "saved_ip_filter_query"
        private const val KEY_SEARCH_EXPANDED = "saved_ip_filter_search_expanded"
        private const val KEY_EXPANDED_OPERATOR = "saved_ip_filter_expanded_operator"

        fun restore(state: Bundle?): SavedIpFilterState {
            val filter = state?.getString(KEY_STATUS)?.let { name ->
                runCatching { IpFilter.valueOf(name) }.getOrNull()
            } ?: run {
                // A former multi-selection has no exact single-filter equivalent; restore it as All.
                val legacy = state?.getStringArrayList(KEY_LEGACY_STATUSES).orEmpty()
                    .mapNotNull { name -> runCatching { SavedIpStatus.valueOf(name) }.getOrNull() }
                    .distinct()
                if (legacy.size == 1) {
                    when (legacy.single()) {
                        SavedIpStatus.REACHABLE -> IpFilter.REACHABLE
                        SavedIpStatus.UNREACHABLE -> IpFilter.UNREACHABLE
                        SavedIpStatus.UNTESTED -> IpFilter.UNTESTED
                    }
                } else IpFilter.ALL
            }
            val tableFilters = LinkedHashMap<Int, LinkedHashSet<String>>()
            for (column in 0 until 32) {
                val values = state?.getStringArrayList(KEY_TABLE_FILTER_PREFIX + column).orEmpty()
                if (values.isNotEmpty()) tableFilters[column] = LinkedHashSet(values)
            }
            val sort = state?.getString(KEY_SORT)?.let { name ->
                runCatching { SavedIpSort.valueOf(name) }.getOrNull()
            } ?: SavedIpSort.DEFAULT

            return SavedIpFilterState(
                currentFilter = filter,
                selectedTableFilters = tableFilters,
                sort = sort,
                query = state?.getString(KEY_QUERY).orEmpty(),
                searchExpanded = state?.getBoolean(KEY_SEARCH_EXPANDED) ?: false,
                expandedOperatorId = state?.getString(KEY_EXPANDED_OPERATOR),
            )
        }
    }
}
