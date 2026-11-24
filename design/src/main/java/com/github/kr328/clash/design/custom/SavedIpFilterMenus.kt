package com.github.kr328.clash.design.custom

import android.graphics.drawable.ColorDrawable
import android.view.View
import android.view.ViewGroup
import android.widget.CheckBox
import android.widget.LinearLayout
import android.widget.PopupMenu
import android.widget.PopupWindow
import android.widget.ScrollView
import com.github.kr328.clash.design.R
import com.google.android.material.dialog.MaterialAlertDialogBuilder

// changes.md #175: saved-IP column, filter, and sorting menus.
internal class SavedIpFilterMenus(private val env: SavedIpEnv) {
    private data class OrderedEntriesCache(val version: Long, val entries: List<SavedIpEntry>)
    private data class SortedEntriesCache(val version: Long, val sort: SavedIpSort, val entries: List<SavedIpEntry>)

    val savedTableColumnOptions = IpTestView(env.context, direct = false, target = false, savedTable = true).savedTableColumnOptions()
    private val allSavedColumnNames = savedTableColumnOptions.mapTo(LinkedHashSet()) { it.first }
    var visibleSavedColumns: Set<String> = env.savedIpUiPreferences.getStringSet(KEY_VISIBLE_SAVED_COLUMNS, null)
        ?.let { stored -> (stored + "SPEED_NODE").filter { it in allSavedColumnNames }.toSet() }
        ?: allSavedColumnNames

    private val defaultOrderCache = HashMap<String, OrderedEntriesCache>()
    private val sortedEntriesCache = HashMap<String, SortedEntriesCache>()
    private var tableFilterPopup: PopupWindow? = null
    private var activeTableFilterOperatorId: String? = null
    private var activeTableFilterColumn: String = ""

    fun clearSortCaches() {
        defaultOrderCache.clear()
        sortedEntriesCache.clear()
    }

    fun invalidateSortCache(operatorId: String) {
        defaultOrderCache.remove(operatorId)
        sortedEntriesCache.remove(operatorId)
    }

    fun dismiss() = dismissFilterPopups()

fun showSavedColumnsDialog() {
        val selected = visibleSavedColumns.toMutableSet()
        // CUSTOM #156: do not offer the direct-speed column while its page-wide switch is off.
        val availableOptions = savedTableColumnOptions.filter { env.showDirectSpeed || it.first != "SPEED" }
        val labels = availableOptions.map { it.second }.toTypedArray()
        val checked = BooleanArray(availableOptions.size) { availableOptions[it].first in selected }
        MaterialAlertDialogBuilder(env.context)
            .setTitle(R.string.saved_ip_show_columns_title)
            .setMultiChoiceItems(labels, checked) { _, which, isChecked ->
                val columnName = availableOptions[which].first
                if (isChecked) selected.add(columnName) else selected.remove(columnName)
            }
            .setNegativeButton(android.R.string.cancel, null)
            .setPositiveButton(android.R.string.ok) { _, _ ->
                val next = selected.intersect(allSavedColumnNames)
                visibleSavedColumns = next
                env.savedIpUiPreferences.edit()
                    .putStringSet(KEY_VISIBLE_SAVED_COLUMNS, HashSet(next))
                    .apply()
                env.filterState.selectedTableFilters.keys.toList().forEach { operatorId ->
                    val filters = env.filterState.selectedTableFilters[operatorId] ?: return@forEach
                    filters.keys.retainAll(next + "SPEED") // CUSTOM #156: preserve the direct-speed filter state.
                    if (filters.isEmpty()) env.filterState.selectedTableFilters.remove(operatorId)
                }
                env.filterState.sorts.keys.toList().forEach { operatorId ->
                    val sort = env.currentSort(operatorId)
                    if (sort.columnName != null && sort.columnName !in next) {
                        env.setCurrentSort(operatorId, SavedIpSort.DEFAULT)
                        sortedEntriesCache.remove(operatorId)
                    }
                }
                env.savedTableRenderers.clear()
                env.refresh()
            }
            .show()
    }

fun visibleEntries(operator: SavedIpOperator, applySort: Boolean = false): List<SavedIpEntry> {
        // Collapsed groups need only their filtered count; do not sort their rows.
        val ordered = if (!applySort) {
            operator.ips
        } else {
            val defaultOrdered = defaultOrderCache[operator.id]
                ?.takeIf { it.version == env.renderedDataVersion }
                ?.entries
                ?: SavedIpStore.sortEntries(operator.ips).also {
                    defaultOrderCache[operator.id] = OrderedEntriesCache(env.renderedDataVersion, it)
                }
            // Speed sorting is cached by data version and option; filters only retain items from this order.
            val cached = sortedEntriesCache[operator.id]
            if (cached != null && cached.version == env.renderedDataVersion && cached.sort == env.currentSort(operator.id)) {
                cached.entries
            } else {
                val sorted = sortSavedIpEntries(defaultOrdered, env.currentSort(operator.id))
                sortedEntriesCache[operator.id] = SortedEntriesCache(env.renderedDataVersion, env.currentSort(operator.id), sorted)
                sorted
            }
        }
        val selectedStatuses = env.filterState.selectedStatuses[operator.id].orEmpty()
        val filtered = ordered.filter { entry -> selectedStatuses.isEmpty() || entry.status in selectedStatuses }
        val tableFiltered = filterSavedIpEntriesByColumns(operator, filtered)
        // CUSTOM (CUSTOM.md #156): pin recommended and configured IPs above the remaining rows.
        val recommendedIps = IpRecommend.forSaved(operator.ips, System.currentTimeMillis())
        return tableFiltered.sortedBy { entry ->
            entry.address !in recommendedIps && entry.address !in env.configuredIpSources
        }
    }

fun dismissFilterPopups() {
        tableFilterPopup?.dismiss()
        tableFilterPopup = null
    }

fun showTableColumnPopup(anchor: View, operator: SavedIpOperator, columnIndex: Int) {
        if (env.filterState.searchExpanded) env.collapseSearch()
        val renderer = env.savedTableFor(operator).first
        val columnName = renderer.savedTableColumnName(columnIndex)
        if (columnName.isEmpty()) return
        if (tableFilterPopup?.isShowing == true && activeTableFilterOperatorId == operator.id && activeTableFilterColumn == columnName) {
            tableFilterPopup?.dismiss(); tableFilterPopup = null; return
        }
        dismissFilterPopups()
        val options = renderer.savedTableFilterOptions(operator.ips, columnName)
        if (options.isEmpty()) return
        // CUSTOM (CUSTOM.md #164): calculate once on popup open; the current column's own selection is excluded.
        val query = env.searchInput.text?.toString()?.trim().orEmpty()
        val counts = SavedIpFilterCounts.count(
            entries = operator.ips,
            columnName = columnName,
            valueOf = renderer::savedTableFilterValue,
            selectedStatuses = env.filterState.selectedStatuses[operator.id].orEmpty(),
            selectedOtherColumns = env.filterState.selectedTableFilters[operator.id].orEmpty(),
            query = query,
            matchesSearch = { entry, search ->
                entry.address.contains(search, ignoreCase = true) ||
                    SAVED_SEARCH_COLUMNS.any { column ->
                        column in visibleSavedColumns &&
                            renderer.savedTableFilterValue(entry, column).contains(search, ignoreCase = true)
                    }
            },
        )
        val operatorFilters = env.filterState.selectedTableFilters.getOrPut(operator.id) { LinkedHashMap() }
        val selected = operatorFilters.getOrPut(columnName) { LinkedHashSet() }
        var selectionChanged = false
        var selectionClearedOperatorId: String? = null
        val choices = LinearLayout(env.context).apply { orientation = LinearLayout.VERTICAL; setPadding(savedIpDp(env.density, 8), savedIpDp(env.density, 4), savedIpDp(env.density, 8), savedIpDp(env.density, 4)) }
        options.forEach { value ->
            choices.addView(CheckBox(env.context).apply {
                tag = value
                text = env.context.getString(R.string.saved_filter_count, value, counts[value] ?: 0)
                textSize = 14f; isChecked = value in selected; minHeight = savedIpDp(env.density, 44)
                setOnCheckedChangeListener { _, checked ->
                    selectionChanged = true
                    if (checked) selected.add(value) else selected.remove(value)
                    if (selected.isEmpty()) operatorFilters.remove(columnName)
                    if (operatorFilters.isEmpty()) env.filterState.selectedTableFilters.remove(operator.id)
                    // CUSTOM (CUSTOM.md #165): defer every affected operator rebuild until the popup closes.
                    if (env.selectedOperatorId != null) selectionClearedOperatorId = env.selectedOperatorId
                    env.clearSelectionState(); env.updateSearchUi()
                }
            }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        }
        val scroll = ScrollView(env.context).apply {
            isFillViewport = true; isVerticalScrollBarEnabled = true
            addView(choices, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        }
        val popupWidth = maxOf(anchor.width, savedIpDp(env.density, 220)).coerceAtMost(env.context.resources.displayMetrics.widthPixels - savedIpDp(env.density, 24))
        val popupHeight = minOf(savedIpDp(env.density, 280), savedIpDp(env.density, 48 * options.size.coerceAtLeast(1)))
        val popup = PopupWindow(scroll, popupWidth, popupHeight, true).apply {
            setBackgroundDrawable(ColorDrawable(env.colorSurface)); elevation = savedIpDp(env.density, 8).toFloat(); isOutsideTouchable = true
            setOnDismissListener {
                if (selected.isEmpty()) {
                    operatorFilters.remove(columnName)
                    if (operatorFilters.isEmpty()) env.filterState.selectedTableFilters.remove(operator.id)
                }
                if (tableFilterPopup === this) tableFilterPopup = null
                // Tapping outside without changing any checkbox must not rebuild the whole list.
                if (selectionChanged) {
                    env.renderOperator(operator.id)
                    selectionClearedOperatorId?.takeIf { it != operator.id }?.let { env.renderOperator(it) }
                }
            }
        }
        tableFilterPopup = popup; activeTableFilterOperatorId = operator.id; activeTableFilterColumn = columnName
        popup.showAsDropDown(anchor)
    }

fun reconcileTableFilters(snapshot: SavedIpSnapshot) {
        val operatorIds = env.filterState.selectedTableFilters.keys.toList()
        for (operatorId in operatorIds) {
            val operator = snapshot.operators.firstOrNull { it.id == operatorId }
            val filters = env.filterState.selectedTableFilters[operatorId] ?: continue
            if (operator == null) {
                env.filterState.selectedTableFilters.remove(operatorId)
                continue
            }
            val renderer = env.savedTableFor(operator).first
            for (columnName in filters.keys.toList()) {
                // CUSTOM #156: do not reconcile away the direct-speed filter while its column is hidden.
                if (columnName == "SPEED") continue
                filters[columnName]?.retainAll(renderer.savedTableFilterOptions(operator.ips, columnName).toSet())
                if (filters[columnName].isNullOrEmpty()) filters.remove(columnName)
            }
            if (filters.isEmpty()) env.filterState.selectedTableFilters.remove(operatorId)
        }
        val validIds = snapshot.operators.mapTo(HashSet()) { it.id }
        env.filterState.selectedStatuses.keys.retainAll(validIds)
    }

fun filterSavedIpEntriesByColumns(operator: SavedIpOperator, entries: List<SavedIpEntry>): List<SavedIpEntry> {
        val filters = env.filterState.selectedTableFilters[operator.id].orEmpty()
            .filterKeys { env.showDirectSpeed || it != "SPEED" }
        if (filters.isEmpty()) return entries
        val renderer = env.savedTableFor(operator).first
        return entries.filter { entry -> filters.all { (columnName, values) ->
            values.isEmpty() || renderer.savedTableFilterValue(entry, columnName) in values
        } }
    }

fun showSpeedSortMenu(anchor: View, columnName: String, operatorId: String) {
        if (env.filterState.searchExpanded) env.collapseSearch()
        dismissFilterPopups()
        PopupMenu(env.context, anchor).apply {
            menu.add(0, 1, 0, "按最快").isCheckable = true
            menu.add(0, 2, 1, "按最慢").isCheckable = true
            menu.setGroupCheckable(0, true, true)
            val fastest = SavedIpSort.forColumn(columnName, true)
            val slowest = SavedIpSort.forColumn(columnName, false)
            when (env.currentSort(operatorId)) {
                fastest -> menu.findItem(1)?.isChecked = true
                slowest -> menu.findItem(2)?.isChecked = true
                else -> Unit
            }
            setOnMenuItemClickListener { item ->
                val chosen = if (item.itemId == 1) fastest else slowest
                env.setCurrentSort(operatorId, if (env.currentSort(operatorId) == chosen) SavedIpSort.DEFAULT else chosen)
                sortedEntriesCache.remove(operatorId)
                env.renderOperator(operatorId)
                true
            }
            show()
        }
    }

fun showSavedIpMoreMenu() {
        PopupMenu(env.context, env.moreButton).apply {
            menu.add(0, 1, 0, R.string.saved_ip_show_direct_speed).apply {
                isCheckable = true
                isChecked = env.showDirectSpeed
            }
            setOnMenuItemClickListener {
                env.showDirectSpeed = !env.showDirectSpeed
                SavedIpDirectSpeedSetting.write(env.context, env.showDirectSpeed)
                env.savedTableRenderers.clear()
                env.renderRows()
                true
            }
            show()
        }
    }

    private companion object {
        const val KEY_VISIBLE_SAVED_COLUMNS = "visible_columns"
    }
}
