package com.github.kr328.clash.design.custom

import android.os.Bundle
import org.json.JSONArray
import org.json.JSONObject

// CUSTOM (CUSTOM.md #164): status and column filters are isolated per saved-IP operator.
// CUSTOM (CUSTOM.md #159/#164): persist independent filters by operator ID and selected speed column / direction.
internal class SavedIpFilterState private constructor(
    val selectedStatuses: LinkedHashMap<String, LinkedHashSet<SavedIpStatus>>,
    val selectedTableFilters: LinkedHashMap<String, LinkedHashMap<String, LinkedHashSet<String>>>,
    val sorts: LinkedHashMap<String, SavedIpSort>,
    var legacySort: SavedIpSort?,
    var query: String,
    var searchExpanded: Boolean,
    var expandedOperatorId: String?,
) {
    fun sortFor(operatorId: String): SavedIpSort = sorts[operatorId] ?: legacySort ?: SavedIpSort.DEFAULT

    fun setSort(operatorId: String, value: SavedIpSort) {
        sorts[operatorId] = value
    }

    // CUSTOM.md #156: persistent state intentionally excludes search text and search visibility.
    fun persistentJson(): String {
        val statuses = JSONArray()
        selectedStatuses.forEach { (operatorId, values) ->
            values.forEach { status -> statuses.put(JSONObject().put("operatorId", operatorId).put("status", status.name)) }
        }
        val filters = JSONArray()
        selectedTableFilters.forEach { (operatorId, columns) ->
            columns.forEach { (columnName, values) ->
                filters.put(JSONObject().put("operatorId", operatorId).put("column", columnName).put("values", JSONArray(values.toList())))
            }
        }
        val sortItems = JSONArray()
        sorts.forEach { (operatorId, sort) ->
            sortItems.put(JSONObject().put("operatorId", operatorId).put("sort", sort.name))
        }
        return JSONObject()
            .put("sort", legacySort?.name ?: SavedIpSort.DEFAULT.name)
            .put("operatorSorts", sortItems)
            .put("expandedOperatorId", expandedOperatorId ?: JSONObject.NULL)
            .put("statuses", statuses)
            .put("tableFilters", filters)
            .toString()
    }

    fun save(outState: Bundle) {
        selectedStatuses.forEach { (operatorId, statuses) ->
            outState.putStringArrayList(KEY_STATUS_PREFIX + operatorId, ArrayList(statuses.map { it.name }))
        }
        selectedTableFilters.forEach { (operatorId, columns) ->
            columns.forEach { (column, values) ->
                outState.putStringArrayList(KEY_TABLE_FILTER_PREFIX + operatorId + "__" + column, ArrayList(values))
            }
        }
        sorts.forEach { (operatorId, sort) -> outState.putString(KEY_SORT_PREFIX + operatorId, sort.name) }
        outState.putString(KEY_SORT, legacySort?.name ?: SavedIpSort.DEFAULT.name)
        outState.putString(KEY_QUERY, query)
        outState.putBoolean(KEY_SEARCH_EXPANDED, searchExpanded)
        outState.putString(KEY_EXPANDED_OPERATOR, expandedOperatorId)
    }

    companion object {
        private const val KEY_STATUS_PREFIX = "saved_ip_statuses_"
        private const val KEY_TABLE_FILTER_PREFIX = "saved_ip_table_filter_"
        private const val KEY_SORT = "saved_ip_filter_sort"
        private const val KEY_SORT_PREFIX = "saved_ip_filter_sort_"
        private const val KEY_QUERY = "saved_ip_filter_query"
        private const val KEY_SEARCH_EXPANDED = "saved_ip_filter_search_expanded"
        private const val KEY_EXPANDED_OPERATOR = "saved_ip_filter_expanded_operator"
        private val SAVED_TABLE_COLUMNS = setOf(
            "COLO", "ASN", "GEO", "EXIT", "PORTS", "LOSS", "MIN", "MAX", "AVG",
            "JITTER", "EXIT_IP", "TCOLO", "TARGET", "PHASE", "SPEED", "SPEED_NODE",
        )

        fun restorePersistent(raw: String?): SavedIpFilterState {
            if (raw.isNullOrBlank()) return restore(null)
            return try {
                val root = JSONObject(raw)
                val statuses = LinkedHashMap<String, LinkedHashSet<SavedIpStatus>>()
                val statusItems = root.optJSONArray("statuses") ?: JSONArray()
                for (i in 0 until statusItems.length()) {
                    val item = statusItems.optJSONObject(i) ?: continue
                    val id = item.optString("operatorId")
                    val status = runCatching { SavedIpStatus.valueOf(item.optString("status")) }.getOrNull() ?: continue
                    if (id.isNotEmpty()) statuses.getOrPut(id) { LinkedHashSet() }.add(status)
                }
                val filters = LinkedHashMap<String, LinkedHashMap<String, LinkedHashSet<String>>>()
                val filterItems = root.optJSONArray("tableFilters") ?: JSONArray()
                for (i in 0 until filterItems.length()) {
                    val item = filterItems.optJSONObject(i) ?: continue
                    val id = item.optString("operatorId")
                    val column = item.optString("column")
                    if (id.isEmpty() || column !in SAVED_TABLE_COLUMNS) continue
                    val values = item.optJSONArray("values") ?: continue
                    val selected = LinkedHashSet<String>()
                    for (j in 0 until values.length()) values.optString(j).takeIf { it.isNotEmpty() }?.let(selected::add)
                    if (selected.isNotEmpty()) filters.getOrPut(id) { LinkedHashMap() }[column] = selected
                }
                val legacySort = runCatching { SavedIpSort.valueOf(root.optString("sort")) }
                    .getOrNull()?.takeIf { it != SavedIpSort.DEFAULT }
                val sorts = LinkedHashMap<String, SavedIpSort>()
                val sortItems = root.optJSONArray("operatorSorts") ?: JSONArray()
                for (i in 0 until sortItems.length()) {
                    val item = sortItems.optJSONObject(i) ?: continue
                    val id = item.optString("operatorId")
                    val sort = runCatching { SavedIpSort.valueOf(item.optString("sort")) }.getOrNull() ?: continue
                    if (id.isNotBlank()) sorts[id] = sort
                }
                val expanded = if (root.isNull("expandedOperatorId")) null else root.optString("expandedOperatorId").takeIf { it.isNotEmpty() }
                SavedIpFilterState(statuses, filters, sorts, legacySort, "", false, expanded)
            } catch (_: Exception) {
                restore(null)
            }
        }

        fun restore(state: Bundle?): SavedIpFilterState {
            val statuses = LinkedHashMap<String, LinkedHashSet<SavedIpStatus>>()
            val tableFilters = LinkedHashMap<String, LinkedHashMap<String, LinkedHashSet<String>>>()
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
                        val separator = suffix.lastIndexOf("__")
                        if (separator <= 0) return@forEach // old numeric-index Bundle keys are intentionally ignored
                        val id = suffix.substring(0, separator)
                        val column = suffix.substring(separator + 2)
                        if (column !in SAVED_TABLE_COLUMNS) return@forEach
                        val values = state?.getStringArrayList(key).orEmpty()
                        if (id.isNotEmpty() && values.isNotEmpty()) {
                            tableFilters.getOrPut(id) { LinkedHashMap() }[column] = LinkedHashSet(values)
                        }
                    }
                }
            }
            val legacySort = state?.getString(KEY_SORT)?.let { runCatching { SavedIpSort.valueOf(it) }.getOrNull() }
                ?.takeIf { it != SavedIpSort.DEFAULT }
            val sorts = LinkedHashMap<String, SavedIpSort>()
            state?.keySet().orEmpty().filter { it.startsWith(KEY_SORT_PREFIX) }.forEach { key ->
                val id = key.removePrefix(KEY_SORT_PREFIX)
                val sort = state?.getString(key)?.let { runCatching { SavedIpSort.valueOf(it) }.getOrNull() }
                if (id.isNotBlank() && sort != null) sorts[id] = sort
            }
            return SavedIpFilterState(
                selectedStatuses = statuses,
                selectedTableFilters = tableFilters,
                sorts = sorts,
                legacySort = legacySort,
                query = state?.getString(KEY_QUERY).orEmpty(),
                searchExpanded = state?.getBoolean(KEY_SEARCH_EXPANDED) ?: false,
                expandedOperatorId = state?.getString(KEY_EXPANDED_OPERATOR),
            )
        }
    }
}
