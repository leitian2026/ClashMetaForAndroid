package com.github.kr328.clash.design.custom

import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.PopupMenu
import android.widget.TextView
import com.github.kr328.clash.design.R

// changes.md #175: operator sections and row headers for the saved-IP page.
internal class SavedIpRenderer(
    private val env: SavedIpEnv,
    private val state: SavedIpRenderState,
    private val selection: SavedIpSelection,
    private val filterMenus: SavedIpFilterMenus,
) {
    private val tableRenderer = SavedIpTableRenderer(env, state, selection, filterMenus)

    fun savedTableFor(operator: SavedIpOperator): Pair<IpTestView, IntArray> =
        tableRenderer.savedTableFor(operator)

    fun updateStickyHeader() = tableRenderer.updateStickyHeader()

    // CUSTOM #158: update saved download buttons from the process-wide event source.
    fun setSpeedState(available: Boolean, busy: Boolean) {
        if (env.speedAvailable == available && env.speedBusy == busy) return
        env.speedAvailable = available
        env.speedBusy = busy
        // CUSTOM #158: update currently bound buttons without rebuilding the table.
        state.savedTableRenderers.values.forEach { (renderer, _) ->
            renderer.setSavedSpeedAvailability(env.speedAvailable, env.speedBusy)
        }
    }

    // CUSTOM #158: update only the active download percentage in the already-bound cell.
    fun updateSpeedProgress(operatorId: String, address: String, direct: Boolean, percent: Int?) {
        state.savedTableRenderers[operatorId]?.first?.updateSavedSpeedProgress(address, direct, percent)
    }

    // CUSTOM #158/#159: update one speed cell and move existing row views when its order changes.
    fun updateSpeedResult(operatorId: String, address: String, direct: Boolean, speed: Double, short: Boolean) {
        val (snapshot, version) = SavedIpStore.readVersioned(env.context)
        env.snapshotCache = snapshot
        env.renderedDataVersion = version
        val operator = env.snapshotCache.operators.firstOrNull { it.id == operatorId } ?: return
        filterMenus.invalidateSortCache(operatorId)
        val table = state.savedTableRenderers[operatorId]?.first ?: return
        table.updateSavedSpeed(address, direct, speed, short, env.speedAvailable, env.speedBusy)
        val updatedColumn = if (direct) "SPEED" else "SPEED_NODE"
        if ((operatorId == env.expandedOperatorId || operatorId == env.selectedOperatorId) &&
            env.currentSort(operatorId).columnName == updatedColumn
        ) {
            val query = env.searchInput.text?.toString()?.trim().orEmpty()
            val visible = filterMenus.visibleEntries(operator, applySort = true)
            val ordered = if (query.isEmpty()) visible else {
                visible.filter { entry ->
                    entry.address.contains(query, ignoreCase = true) ||
                        SAVED_SEARCH_COLUMNS.any { column ->
                            column in env.visibleSavedColumns &&
                                table.savedTableFilterValue(entry, column).contains(query, ignoreCase = true)
                        }
                }
            }
            table.moveSavedRow(address, ordered)
        }
    }

    fun setConfigIpSources(value: Map<String, Set<String>>) {
        if (env.configuredIpSources == value) return
        val changedIps = (env.configuredIpSources.keys + value.keys).filterTo(HashSet()) { ip ->
            env.configuredIpSources[ip] != value[ip]
        }
        env.configuredIpSources = value
        if (changedIps.isEmpty()) return
        val affectedOperatorIds = env.snapshotCache.operators
            .filter { operator -> operator.ips.any { it.address in changedIps } }
            .map { it.id }
        affectedOperatorIds.forEach { operatorId -> renderOperator(operatorId) }
    }

fun renderRows() {
        env.cancelPendingSearchRender()
        env.dismissFailureDetailDialog()
        env.renderedQuery = env.searchInput.text?.toString().orEmpty()
        env.operatorContent.removeAllViews()
        state.operatorSections.clear()
        state.stickyHeaderCopies.clear()
        state.stickyHeaderTables.clear()
        state.stickyHeaderSourceScrolls.clear()
        state.stickyHeaderCopyScrolls.clear()
        env.stickyHeaderContainer.removeAllViews()
        env.stickyHeaderContainer.visibility = View.GONE
        state.stickyHeaderOperatorId = null
        env.snapshotCache.operators.forEach { renderOperator(it.id, showEmptyState = false) }
        if (env.operatorContent.childCount == 0) addEmptyState()
        tableRenderer.updateStickyHeader()
        env.operatorList.post { tableRenderer.updateStickyHeader() }
    }

fun addEmptyState() {
        env.operatorContent.addView(TextView(env.context).apply {
            tag = SavedIpRenderState.SavedIpRenderState.EMPTY_STATE_TAG
            setText(if (env.snapshotCache.operators.none { it.ips.isNotEmpty() }) R.string.saved_ip_empty else R.string.saved_ip_filter_no_match)
            textSize = 14f
            setTextColor(env.colorMuted)
            setPadding(0, savedIpDp(env.density, 12), 0, savedIpDp(env.density, 16))
        }, savedIpFullWidth())
    }

fun renderOperator(operatorId: String, showEmptyState: Boolean = true) {
        val operator = env.snapshotCache.operators.firstOrNull { it.id == operatorId } ?: return
        env.dismissFailureDetailDialog()
        state.stickyHeaderCopies.remove(operatorId)
        state.stickyHeaderTables.remove(operatorId)
        state.stickyHeaderSourceScrolls.remove(operatorId)
        state.stickyHeaderCopyScrolls.remove(operatorId)
        if (state.stickyHeaderOperatorId == operatorId) {
            env.stickyHeaderContainer.removeAllViews()
            env.stickyHeaderContainer.visibility = View.GONE
            state.stickyHeaderOperatorId = null
        }
        state.operatorSections.remove(operatorId)?.let { env.operatorContent.removeView(it) }
        env.operatorContent.findViewWithTag<View>(SavedIpRenderState.EMPTY_STATE_TAG)?.let { env.operatorContent.removeView(it) }

        val query = env.searchInput.text?.toString()?.trim().orEmpty()
        val expanded = query.isNotEmpty() || env.expandedOperatorId == operator.id || env.selectedOperatorId == operator.id
        // Keep status filters, column filters and search active during multi-selection.
        // Calculate speed sorting only for the explicitly expanded operator group.
        val filtered = env.visibleEntries(operator, applySort = operator.id == env.expandedOperatorId || env.selectedOperatorId == operator.id)
        val entries = if (query.isEmpty()) filtered else {
            val renderer = tableRenderer.savedTableFor(operator).first
            filtered.filter { entry ->
                entry.address.contains(query, ignoreCase = true) ||
                    SAVED_SEARCH_COLUMNS.any { column ->
                        column in env.visibleSavedColumns &&
                            renderer.savedTableFilterValue(entry, column).contains(query, ignoreCase = true)
                    }
            }
        }
        val hasActiveFilters = env.filterState.selectedStatuses.containsKey(operator.id) ||
            env.filterState.selectedTableFilters.containsKey(operator.id)
        if (entries.isNotEmpty() || (operator.ips.isNotEmpty() && hasActiveFilters)) {
            val section = LinearLayout(env.context).apply {
                tag = operator.id
                orientation = LinearLayout.VERTICAL
            }
            val group = LinearLayout(env.context).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(0, savedIpDp(env.density, 10), 0, savedIpDp(env.density, 8))
            }
            bindOperatorRow(group, operator, entries, expanded)
            section.addView(group, savedIpFullWidth())
            if (expanded) {
                if (entries.isNotEmpty()) {
                    tableRenderer.bindSavedTable(section, operator, entries)
                } else {
                    tableRenderer.bindSavedTable(section, operator, emptyList())
                    section.addView(TextView(env.context).apply {
                        setText(R.string.saved_ip_filter_no_match)
                        textSize = 13f
                        setTextColor(env.colorMuted)
                        setPadding(savedIpDp(env.density, 48), savedIpDp(env.density, 8), 0, savedIpDp(env.density, 12))
                    }, savedIpFullWidth())
                }
            }
            val targetOrder = env.snapshotCache.operators.indexOfFirst { it.id == operatorId }
            var insertAt = env.operatorContent.childCount
            for (i in 0 until env.operatorContent.childCount) {
                val childId = env.operatorContent.getChildAt(i).tag as? String ?: continue
                val childOrder = env.snapshotCache.operators.indexOfFirst { it.id == childId }
                if (childOrder > targetOrder) { insertAt = i; break }
            }
            env.operatorContent.addView(section, insertAt, savedIpFullWidth())
            state.operatorSections[operatorId] = section
        }
        if (showEmptyState && env.operatorContent.childCount == 0) addEmptyState()
        tableRenderer.updateStickyHeader()
        env.operatorList.post { tableRenderer.updateStickyHeader() }
    }

fun bindOperatorRow(
        container: LinearLayout,
        operator: SavedIpOperator,
        entries: List<SavedIpEntry>,
        expanded: Boolean,
    ) {
        val reachable = operator.ips.count { it.status == SavedIpStatus.REACHABLE }
        val unreachable = operator.ips.count { it.status == SavedIpStatus.UNREACHABLE }
        val untested = operator.ips.count { it.status == SavedIpStatus.UNTESTED }
        val allUnreachable = operator.ips.any { it.status == SavedIpStatus.UNREACHABLE }
        val header = LinearLayout(env.context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            minimumHeight = savedIpDp(env.density, 56)
        }
        val arrow = TextView(env.context).apply {
            text = if (expanded) "▾" else "▸"
            textSize = 18f
            setTextColor(env.colorLink)
            gravity = Gravity.CENTER
        }
        header.addView(arrow, LinearLayout.LayoutParams(savedIpDp(env.density, 48), savedIpDp(env.density, 48)))
        val title = LinearLayout(env.context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        title.addView(TextView(env.context).apply {
            text = env.context.getString(R.string.saved_ip_group_title, savedIpDisplayName(env.context, operator), entries.size)
            textSize = 15f
            setTextColor(env.colorText)
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            maxLines = 1
        }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        val statusTags = LinearLayout(env.context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        statusTags.addView(groupStatusTag(operator, null, "全部", operator.ips.size, env.colorLink))
        statusTags.addView(groupStatusTag(operator, SavedIpStatus.REACHABLE, env.context.getString(R.string.saved_ip_status_reachable), reachable, Color.rgb(46, 125, 50)))
        statusTags.addView(groupStatusTag(operator, SavedIpStatus.UNREACHABLE, env.context.getString(R.string.saved_ip_status_unreachable), unreachable, Color.rgb(198, 40, 40)))
        statusTags.addView(groupStatusTag(operator, SavedIpStatus.UNTESTED, env.context.getString(R.string.saved_ip_status_untested), untested, Color.rgb(239, 143, 0)))
        title.addView(statusTags, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        header.addView(title, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        val menuButton = TextView(env.context).apply {
            text = "⋮"
            textSize = 22f
            gravity = Gravity.CENTER
            setTextColor(env.colorLink)
            background = savedIpSelectableBackground(env.context)
            contentDescription = env.context.getString(R.string.saved_ip_group_menu)
            setOnClickListener { anchor ->
                PopupMenu(env.context, anchor).apply {
                    menu.add(0, 1, 0, R.string.saved_ip_delete_unreachable).isEnabled = allUnreachable
                    menu.add(0, 4, 1, R.string.saved_ip_delete_older)
                    menu.add(0, 5, 2, R.string.saved_ip_select_age)
                    menu.add(0, 6, 5, R.string.saved_ip_show_columns)
                    if (operator.id !in SavedIpStore.builtInOperatorIds) {
                        menu.add(0, 2, 3, R.string.saved_ip_rename)
                        menu.add(0, 3, 4, R.string.saved_ip_delete)
                    }
                    setOnMenuItemClickListener { item ->
                        when (item.itemId) {
                            1 -> env.confirmDeleteUnreachable(operator)
                            2 -> env.editOperator(operator)
                            3 -> env.confirmDeleteOperator(operator)
                            4 -> env.chooseDeleteOlder(operator)
                            5 -> selection.chooseSelectAge(operator)
                            6 -> filterMenus.showSavedColumnsDialog()
                        }
                        true
                    }
                    show()
                }
            }
        }
        header.addView(menuButton, LinearLayout.LayoutParams(savedIpDp(env.density, 48), savedIpDp(env.density, 48)))
        container.addView(header, savedIpFullWidth())
        container.setOnClickListener {
            env.expandedOperatorId = if (env.expandedOperatorId == operator.id) null else operator.id
            env.renderRows()
        }
        container.addView(View(env.context).apply { setBackgroundColor(env.colorDivider) }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, savedIpDp(env.density, 1)))
    }

fun groupStatusTag(
        operator: SavedIpOperator,
        status: SavedIpStatus?,
        label: String,
        count: Int,
        color: Int,
    ): TextView = TextView(env.context).apply {
        text = "$label $count"
        textSize = 12f
        val selectedStatuses = env.filterState.selectedStatuses[operator.id].orEmpty()
        val selected = if (status == null) selectedStatuses.isEmpty() else selectedStatuses.singleOrNull() == status
        setTextColor(if (selected) Color.WHITE else color)
        setPadding(savedIpDp(env.density, 7), savedIpDp(env.density, 4), savedIpDp(env.density, 7), savedIpDp(env.density, 4))
        background = GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = savedIpDp(env.density, 8).toFloat()
            setColor(if (selected) color else Color.argb(24, Color.red(color), Color.green(color), Color.blue(color)))
        }
        setOnClickListener {
            if (status == null) {
                env.filterState.selectedStatuses.remove(operator.id)
            } else {
                env.filterState.selectedStatuses[operator.id] = linkedSetOf(status)
            }
            val previousSelection = env.selectedOperatorId
            selection.clearSelectionState()
            renderOperator(operator.id)
            if (previousSelection != null && previousSelection != operator.id) renderOperator(previousSelection)
        }
    }
}
