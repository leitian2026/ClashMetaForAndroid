package com.github.kr328.clash.design.custom

import android.view.View
import android.widget.HorizontalScrollView
import android.widget.LinearLayout

// changes.md #175: mutable caches and sticky-header references owned by saved-IP rendering.
internal class SavedIpRenderState {
    var renderedDataVersion = -1L
    val savedTableRenderers = HashMap<String, Pair<IpTestView, IntArray>>()
    val operatorSections = HashMap<String, LinearLayout>()
    val stickyHeaderCopies = HashMap<String, View>()
    val stickyHeaderTables = HashMap<String, View>()
    val stickyHeaderSourceScrolls = HashMap<String, HorizontalScrollView>()
    val stickyHeaderCopyScrolls = HashMap<String, HorizontalScrollView>()
    var stickyHeaderOperatorId: String? = null
    var savedTableScrollX = 0

    companion object {
        val EMPTY_STATE_TAG = Any()
        val SAVED_TABLE_TAG = Any()
    }
}
