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
    val expandedOperatorId: String?
    val canRetest: Boolean
    val selectionActions: android.widget.LinearLayout
    val selectedCountView: android.widget.TextView
    val retestButton: android.widget.TextView
    val deleteSelectedButton: android.widget.TextView
    val selectUnreachableButton: android.widget.TextView
    val selectOldestButton: android.widget.TextView
    val copyHostsButton: android.widget.TextView
    var failureDetailDialog: android.app.Dialog?

    fun refresh()
    fun renderOperator(operatorId: String, showEmptyState: Boolean = true)
    fun visibleEntries(operator: SavedIpOperator, applySort: Boolean = false): List<SavedIpEntry>
    fun clearSelectionState()
    fun writeSnapshot(transform: (SavedIpSnapshot) -> SavedIpSnapshot): Boolean
}
