package com.github.kr328.clash.design.custom

import android.content.Context
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView

// CUSTOM (changes.md #156): keep source-pill drawing outside the size-limited savedTableView function.
internal object SavedIpSourcePills {
    fun create(
        context: Context,
        labels: List<String>,
        night: Boolean,
        dp: (Int) -> Int,
    ): LinearLayout {
        val pillFill = if (night) Color.argb(64, 129, 199, 132) else Color.argb(48, 46, 125, 50)
        val pillText = if (night) Color.parseColor("#90CAF9") else Color.parseColor("#1565C0")
        return LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            labels.forEachIndexed { index, label ->
                addView(
                    TextView(context).apply {
                        text = label
                        textSize = 12f
                        maxLines = 1
                        ellipsize = android.text.TextUtils.TruncateAt.END
                        setTextColor(pillText)
                        setPadding(dp(8), dp(1), dp(8), dp(1))
                        background = GradientDrawable().apply {
                            setColor(pillFill)
                            cornerRadius = dp(12).toFloat()
                        }
                    },
                    LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.WRAP_CONTENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT,
                    ).apply {
                        topMargin = dp(1)
                        bottomMargin = dp(1)
                        if (index > 0) marginStart = dp(4)
                    },
                )
            }
        }
    }
}
