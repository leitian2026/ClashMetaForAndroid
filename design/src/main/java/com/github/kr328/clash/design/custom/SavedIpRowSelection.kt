package com.github.kr328.clash.design.custom

import android.view.View
import android.view.ViewGroup
import android.widget.CheckBox

// CUSTOM (changes.md #156): attach row selection handlers without expanding the size-limited table renderer.
internal object SavedIpRowSelection {
    fun attach(
        leftRow: ViewGroup,
        line: ViewGroup,
        leftRowContainer: View,
        gridRowContainer: View,
        selectionMode: Boolean,
        selectionCheckBox: CheckBox?,
        isSelectionMode: () -> Boolean,
        onLongPress: () -> Boolean,
        selectedBackground: Int,
        surfaceBackground: Int,
    ) {
        fun toggleRowSelection() {
            val nowSelected = onLongPress()
            if (isSelectionMode()) {
                val background = if (nowSelected) selectedBackground else surfaceBackground
                leftRow.setBackgroundColor(background)
                line.setBackgroundColor(background)
                leftRowContainer.setBackgroundColor(background)
                gridRowContainer.setBackgroundColor(background)
                selectionCheckBox?.isChecked = nowSelected
            }
        }

        if (selectionMode) {
            fun attachSelectionClick(view: View) {
                if (view !is CheckBox) {
                    view.setOnClickListener { toggleRowSelection() }
                }
                if (view is ViewGroup) {
                    for (childIndex in 0 until view.childCount) attachSelectionClick(view.getChildAt(childIndex))
                }
            }
            attachSelectionClick(leftRow)
            attachSelectionClick(line)
            selectionCheckBox?.setOnClickListener { toggleRowSelection() }
        }

        fun attachLongPress(view: View) {
            view.setOnLongClickListener { toggleRowSelection(); true }
            if (view is ViewGroup) for (childIndex in 0 until view.childCount) attachLongPress(view.getChildAt(childIndex))
        }
        attachLongPress(leftRow)
        attachLongPress(line)
    }
}
