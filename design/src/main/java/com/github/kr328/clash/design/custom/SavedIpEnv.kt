package com.github.kr328.clash.design.custom

import android.content.Context
import android.view.View

// changes.md #175: live access to SavedIpDesign state shared by extracted feature classes.
// Shared filterable saved-table columns used by the page, filter menus, and renderer.
internal val SAVED_SEARCH_COLUMNS = arrayOf("COLO", "ASN", "GEO", "EXIT", "PORTS", "EXIT_IP", "TCOLO", "TARGET")

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
    val colorText: Int
    val colorLink: Int
    val colorMuted: Int
    val colorDivider: Int
    val operatorContent: android.widget.LinearLayout
    val operatorList: android.widget.ScrollView
    val stickyHeaderContainer: android.widget.LinearLayout
    val speedAvailable: Boolean
    val speedBusy: Boolean
    val onSpeedTest: (String, String, Boolean) -> Unit
    var renderedDataVersion: Long
    var renderedQuery: String?
    val searchInput: android.widget.EditText
    val searchBox: android.widget.FrameLayout
    val searchIconButton: android.widget.FrameLayout
    val searchCloseButton: android.widget.TextView
    val searchIndicator: View
    val searchClearButton: android.widget.TextView
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
    fun cancelPendingSearchRender()
    fun dismissFailureDetailDialog()
    fun copyIp(address: String)
    fun confirmDeleteUnreachable(operator: SavedIpOperator)
    fun editOperator(operator: SavedIpOperator)
    fun confirmDeleteOperator(operator: SavedIpOperator)
    fun chooseDeleteOlder(operator: SavedIpOperator)
    fun toggleSelection(operatorId: String, address: String): Boolean
    fun expandSearch()
    fun updateSearchUi()
    fun visibleEntries(operator: SavedIpOperator, applySort: Boolean = false): List<SavedIpEntry>
    fun clearSelectionState()
    fun writeSnapshot(transform: (SavedIpSnapshot) -> SavedIpSnapshot): Boolean
}
