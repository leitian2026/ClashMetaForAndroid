package com.github.kr328.clash.design.custom

import android.content.Context
import android.view.View

// changes.md #175: live access to SavedIpDesign state shared by extracted feature classes.
internal interface SavedIpEnv {
    val context: Context
    val root: View
    val snapshotCache: SavedIpSnapshot
    var selectedOperatorId: String?
    val selectedAddresses: MutableSet<String>
    val filterState: SavedIpFilterState
    var expandedOperatorId: String?
    val canRetest: Boolean
    val selectionActions: android.widget.LinearLayout
    val selectedCountView: android.widget.TextView
    val retestButton: android.widget.TextView
    val deleteSelectedButton: android.widget.TextView
    val selectUnreachableButton: android.widget.TextView
    val selectOldestButton: android.widget.TextView
    val copyHostsButton: android.widget.TextView
    val savedIpUiPreferences: android.content.SharedPreferences
    var showDirectSpeed: Boolean
    val density: Float
    val colorSurface: Int
    val searchInput: android.widget.EditText
    val moreButton: View
    val savedTableRenderers: MutableMap<String, Pair<IpTestView, IntArray>>
    val configuredIpSources: Map<String, Set<String>>
    val renderedDataVersion: Long
    var visibleSavedColumns: Set<String>
    var failureDetailDialog: android.app.Dialog?

    fun refresh()
    fun renderRows()
    fun renderOperator(operatorId: String, showEmptyState: Boolean = true)
    fun savedTableFor(operator: SavedIpOperator): Pair<IpTestView, IntArray>
    fun currentSort(operatorId: String): SavedIpSort
    fun setCurrentSort(operatorId: String, value: SavedIpSort)
    fun collapseSearch()
    fun updateSearchUi()
    fun visibleEntries(operator: SavedIpOperator, applySort: Boolean = false): List<SavedIpEntry>
    fun clearSelectionState()
    fun writeSnapshot(transform: (SavedIpSnapshot) -> SavedIpSnapshot): Boolean
}
