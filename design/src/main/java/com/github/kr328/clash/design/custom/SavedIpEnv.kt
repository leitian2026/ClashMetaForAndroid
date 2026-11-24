package com.github.kr328.clash.design.custom

import android.content.Context
import android.view.View

// changes.md #175: live access to SavedIpDesign state shared by extracted feature classes.
internal interface SavedIpEnv {
    val context: Context
    val root: View
    val snapshotCache: SavedIpSnapshot
    val selectedOperatorId: String?
    val selectedAddresses: Set<String>
    var failureDetailDialog: android.app.Dialog?

    fun refresh()
    fun clearSelectionState()
    fun writeSnapshot(transform: (SavedIpSnapshot) -> SavedIpSnapshot): Boolean
}
