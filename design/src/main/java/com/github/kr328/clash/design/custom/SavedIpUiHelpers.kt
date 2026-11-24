package com.github.kr328.clash.design.custom

import android.content.Context
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView

// changes.md #175: shared UI construction helpers for the saved-IP page.
internal fun savedIpDisplayName(context: Context, operator: SavedIpOperator): String =
    savedIpOperatorDisplayName(context, operator)

internal fun savedIpActionButton(
    context: Context,
    colorLink: Int,
    density: Float,
    text: Int,
    click: () -> Unit,
) = TextView(context).apply {
    setText(text)
    textSize = 14f
    setTextColor(colorLink)
    gravity = android.view.Gravity.CENTER
    minHeight = savedIpDp(density, 40)
    setPadding(savedIpDp(density, 4), 0, savedIpDp(density, 4), 0)
    background = savedIpSelectableBackground(context)
    setOnClickListener { click() }
}

internal fun savedIpActionText(
    context: Context,
    colorLink: Int,
    density: Float,
    text: Int,
    click: (View) -> Unit,
) = TextView(context).apply {
    setText(text)
    textSize = 13f
    setTextColor(colorLink)
    gravity = android.view.Gravity.CENTER
    minHeight = savedIpDp(density, 40)
    background = savedIpSelectableBackground(context)
    setPadding(savedIpDp(density, 8), savedIpDp(density, 4), savedIpDp(density, 8), savedIpDp(density, 4))
    setOnClickListener { click(it) }
}

internal fun savedIpAddDivider(context: Context, colorDivider: Int, density: Float, parent: LinearLayout) {
    parent.addView(
        View(context).apply { setBackgroundColor(colorDivider) },
        LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, savedIpDp(density, 1)),
    )
    parent.addView(View(context), LinearLayout.LayoutParams(1, savedIpDp(density, 10)))
}

internal fun savedIpSelectableBackground(context: Context) = android.util.TypedValue().let { value ->
    context.theme.resolveAttribute(android.R.attr.selectableItemBackground, value, true)
    context.getDrawable(value.resourceId)
}

internal fun savedIpThemeColor(context: Context, attribute: Int, fallback: Int): Int {
    val values = context.obtainStyledAttributes(intArrayOf(attribute))
    return try {
        values.getColor(0, fallback)
    } finally {
        values.recycle()
    }
}

internal fun savedIpDp(density: Float, value: Int): Int = (value * density + 0.5f).toInt()

internal fun savedIpFullWidth() =
    LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)

internal fun savedIpWeighted(density: Float, gap: Int = 0) =
    LinearLayout.LayoutParams(0, savedIpDp(density, 40), 1f).apply {
        if (gap > 0) {
            marginStart = savedIpDp(density, gap)
            marginEnd = savedIpDp(density, gap)
        }
    }
