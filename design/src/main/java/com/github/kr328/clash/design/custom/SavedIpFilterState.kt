package com.github.kr328.clash.design.custom

import android.os.Bundle

// CUSTOM.md #156 (r286): centralize saved-IP page filters and restorable view state.
/**
 * Page-local saved-IP filters and view state.
 *
 * Empty status selection means all statuses. Selecting every status is normalized back to empty,
 * keeping one canonical representation for the unfiltered state.
 */
internal class SavedIpFilterState private constructor(
    val selectedStatuses: LinkedHashSet<SavedIpStatus>,
    val selectedCountryCodes: LinkedHashSet<String>,
    var sort: SavedIpSort,
    var query: String,
    var searchExpanded: Boolean,
    var expandedPanel: String,
    var expandedOperatorId: String?,
) {
    fun matchesStatus(status: SavedIpStatus): Boolean =
        selectedStatuses.isEmpty() || status in selectedStatuses

    fun save(outState: Bundle) {
        outState.putStringArrayList(KEY_STATUSES, ArrayList(selectedStatuses.map { it.name }))
        outState.putStringArrayList(KEY_COUNTRIES, ArrayList(selectedCountryCodes))
        outState.putString(KEY_SORT, sort.name)
        outState.putString(KEY_QUERY, query)
        outState.putBoolean(KEY_SEARCH_EXPANDED, searchExpanded)
        outState.putString(KEY_EXPANDED_PANEL, expandedPanel)
        outState.putString(KEY_EXPANDED_OPERATOR, expandedOperatorId)
    }

    companion object {
        private const val KEY_STATUSES = "saved_ip_filter_statuses"
        private const val KEY_COUNTRIES = "saved_ip_filter_countries"
        private const val KEY_SORT = "saved_ip_filter_sort"
        private const val KEY_QUERY = "saved_ip_filter_query"
        private const val KEY_SEARCH_EXPANDED = "saved_ip_filter_search_expanded"
        private const val KEY_EXPANDED_PANEL = "saved_ip_filter_expanded_panel"
        private const val KEY_EXPANDED_OPERATOR = "saved_ip_filter_expanded_operator"

        fun restore(state: Bundle?): SavedIpFilterState {
            val statuses = LinkedHashSet<SavedIpStatus>()
            state?.getStringArrayList(KEY_STATUSES)?.forEach { name ->
                runCatching { SavedIpStatus.valueOf(name) }.getOrNull()?.let(statuses::add)
            }
            if (statuses.size == SavedIpStatus.values().size) statuses.clear()

            val countries = LinkedHashSet(state?.getStringArrayList(KEY_COUNTRIES).orEmpty())
            val sort = state?.getString(KEY_SORT)?.let { name ->
                runCatching { SavedIpSort.valueOf(name) }.getOrNull()
            } ?: SavedIpSort.DEFAULT

            return SavedIpFilterState(
                selectedStatuses = statuses,
                selectedCountryCodes = countries,
                sort = sort,
                query = state?.getString(KEY_QUERY).orEmpty(),
                searchExpanded = state?.getBoolean(KEY_SEARCH_EXPANDED) ?: false,
                expandedPanel = state?.getString(KEY_EXPANDED_PANEL).orEmpty(),
                expandedOperatorId = state?.getString(KEY_EXPANDED_OPERATOR),
            )
        }
    }
}
