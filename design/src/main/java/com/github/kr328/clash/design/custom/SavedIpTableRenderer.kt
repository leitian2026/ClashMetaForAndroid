package com.github.kr328.clash.design.custom

import android.view.View
import android.view.ViewGroup
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import com.github.kr328.clash.design.R
import com.google.android.material.dialog.MaterialAlertDialogBuilder

// changes.md #175: saved-IP table construction and sticky horizontal table headers.
internal class SavedIpTableRenderer(
    private val env: SavedIpEnv,
    private val state: SavedIpRenderState,
    private val selection: SavedIpSelection,
    private val filterMenus: SavedIpFilterMenus,
) {
fun savedTableFor(operator: SavedIpOperator): Pair<IpTestView, IntArray> =
        state.savedTableRenderers.getOrPut(operator.id) {
            val renderer = IpTestView(
                env.context, direct = false, target = false, savedTable = true,
                env.visibleSavedColumns = env.visibleSavedColumns,
                env.showDirectSpeed = env.showDirectSpeed,
            )
            val width = (env.operatorList.width - env.operatorList.paddingLeft - env.operatorList.paddingRight)
                .takeIf { it > 0 } ?: (env.context.resources.displayMetrics.widthPixels - savedIpDp(env.density, 32))
            val widths = renderer.savedTableWidths(
                width,
                operator.ips.map { it.result ?: com.github.kr328.clash.core.model.IpTestRow(ip = it.address, ok = true) },
                operator.colos,
                operator.coloCountries,
            )
            renderer to widths
        }

fun bindSavedTable(container: LinearLayout, operator: SavedIpOperator, entries: List<SavedIpEntry>) {
        val (renderer, widths) = savedTableFor(operator)
        // CUSTOM #156: hidden direct-speed filter state remains stored but is not shown in the table header.
        val selectedFilters = env.filterState.selectedTableFilters[operator.id].orEmpty()
            .filterKeys { env.showDirectSpeed || it != "SPEED" }
        // CUSTOM.md #166: calculate from the complete operator list, before UI filters are applied.
        val recommendedIps = IpRecommend.forSaved(operator.ips, System.currentTimeMillis())
        val speedSortLabel = when {
            env.currentSort(operator.id) == SavedIpSort.DEFAULT -> ""
            env.currentSort(operator.id).isFastest -> "按最快"
            else -> "按最慢"
        }
        val onHeaderClick: (Int, View) -> Unit = { columnIndex, anchor ->
            val columnName = renderer.savedTableColumnName(columnIndex)
            if (columnName == "SPEED" || columnName == "SPEED_NODE") filterMenus.showSpeedSortMenu(anchor, columnName, operator.id)
            else filterMenus.showTableColumnPopup(anchor, operator, columnIndex)
        }
        fun makeTable(rows: List<SavedIpEntry>, tableRenderer: IpTestView): View = tableRenderer.savedTableView(
            entries = rows,
            configIpSources = env.configuredIpSources,
            widths = widths,
            scrollX = state.savedTableScrollX,
            onScroll = { state.savedTableScrollX = it },
            isSelectionMode = { env.selectedOperatorId == operator.id },
            isSelected = { address -> env.selectedOperatorId == operator.id && address in env.selectedAddresses },
            onCopy = ::env.copyIp,
            onDetail = { title, detail ->
                MaterialAlertDialogBuilder(env.context).setTitle(title).setMessage(detail)
                    .setPositiveButton(R.string.close, null).show()
            },
            onFailureDetail = { title, detail ->
                val message = TextView(env.context).apply {
                    text = detail
                    textSize = 14f
                    setTextColor(env.colorText)
                    setTextIsSelectable(true)
                    setPadding(savedIpDp(env.density, 16), savedIpDp(env.density, 8), savedIpDp(env.density, 16), savedIpDp(env.density, 8))
                }
                val scroll = ScrollView(env.context).apply {
                    isVerticalScrollBarEnabled = true
                    addView(message, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
                }
                env.dismissFailureDetailDialog()
                val dialog = MaterialAlertDialogBuilder(env.context).setTitle(title).setView(
                    scroll,
                    savedIpDp(env.density, 12),
                    savedIpDp(env.density, 8),
                    savedIpDp(env.density, 12),
                    savedIpDp(env.density, 8),
                ).setPositiveButton(R.string.close, null).create()
                env.failureDetailDialog = dialog
                dialog.setOnDismissListener {
                    if (env.failureDetailDialog === dialog) env.failureDetailDialog = null
                }
                dialog.show()
            },
            onLongPress = { address -> selection.toggleSelection(operator.id, address) },
            selectedFilters = selectedFilters,
            speedSortLabel = speedSortLabel,
            speedSortColumnName = env.currentSort(operator.id).columnName,
            onHeaderClick = onHeaderClick,
            recommendedIps = recommendedIps,
            env.speedAvailable = env.speedAvailable,
            env.speedBusy = env.speedBusy,
            env.onSpeedTest = { address, direct -> env.onSpeedTest(operator.id, address, direct) },
        )

        val table = makeTable(entries, renderer).apply { tag = SAVED_TABLE_TAG }
        state.stickyHeaderTables[operator.id] = table
        container.addView(table, savedIpFullWidth())

        // Keep a separate header-only renderer so the main renderer retains row handles for in-place updates.
        val stickyRenderer = IpTestView(
            env.context, direct = false, target = false, savedTable = true,
            env.visibleSavedColumns = env.visibleSavedColumns, env.showDirectSpeed = env.showDirectSpeed,
        )
        val stickyCopy = makeTable(emptyList(), stickyRenderer)
        state.stickyHeaderCopies[operator.id] = stickyCopy
        val sourceScroll = findHorizontalScroll(table)
        val copyScroll = findHorizontalScroll(stickyCopy)
        if (sourceScroll != null && copyScroll != null) {
            var syncing = false
            sourceScroll.setOnScrollChangeListener { _, x, _, _, _ ->
                state.savedTableScrollX = x
                if (!syncing && copyScroll.scrollX != x) {
                    syncing = true
                    copyScroll.scrollTo(x, 0)
                    syncing = false
                }
            }
            copyScroll.setOnScrollChangeListener { _, x, _, _, _ ->
                state.savedTableScrollX = x
                if (!syncing && sourceScroll.scrollX != x) {
                    syncing = true
                    sourceScroll.scrollTo(x, 0)
                    syncing = false
                }
            }
            state.stickyHeaderSourceScrolls[operator.id] = sourceScroll
            state.stickyHeaderCopyScrolls[operator.id] = copyScroll
        }
    }

fun findHorizontalScroll(view: View): HorizontalScrollView? {
        if (view is HorizontalScrollView) return view
        if (view is ViewGroup) {
            for (i in 0 until view.childCount) {
                findHorizontalScroll(view.getChildAt(i))?.let { return it }
            }
        }
        return null
    }

fun updateStickyHeader() {
        val viewportLocation = IntArray(2)
        env.operatorList.getLocationOnScreen(viewportLocation)
        val viewportTop = viewportLocation[1]
        var activeOperatorId: String? = null
        for (operator in env.snapshotCache.operators) {
            // Only expanded operators have a table entry; use the cached reference instead of
            // traversing the section's view tree on every vertical scroll event.
            val table = state.stickyHeaderTables[operator.id] ?: continue
            if (table.height <= 0) continue
            val tableLocation = IntArray(2)
            table.getLocationOnScreen(tableLocation)
            if (tableLocation[1] < viewportTop && tableLocation[1] + table.height > viewportTop) {
                activeOperatorId = operator.id
                break
            }
        }

        if (activeOperatorId == null) {
            env.stickyHeaderContainer.visibility = View.GONE
            state.stickyHeaderOperatorId = null
            return
        }

        val copy = state.stickyHeaderCopies[activeOperatorId] ?: return
        if (state.stickyHeaderOperatorId != activeOperatorId || env.stickyHeaderContainer.childCount == 0) {
            env.stickyHeaderContainer.removeAllViews()
            (copy.parent as? ViewGroup)?.removeView(copy)
            env.stickyHeaderContainer.addView(copy, savedIpFullWidth())
            state.stickyHeaderOperatorId = activeOperatorId
        }
        env.stickyHeaderContainer.visibility = View.VISIBLE
        val sourceScroll = state.stickyHeaderSourceScrolls[activeOperatorId]
        val copyScroll = state.stickyHeaderCopyScrolls[activeOperatorId]
        if (sourceScroll != null && copyScroll != null && copyScroll.scrollX != sourceScroll.scrollX) {
            copyScroll.scrollTo(sourceScroll.scrollX, 0)
        }
    }
}
