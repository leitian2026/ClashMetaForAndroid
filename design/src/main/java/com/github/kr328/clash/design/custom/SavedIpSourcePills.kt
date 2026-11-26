package com.github.kr328.clash.design.custom

import android.content.Context
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.text.TextUtils
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import com.github.kr328.clash.core.model.IpTestConfigIp
import com.google.android.material.dialog.MaterialAlertDialogBuilder

// CUSTOM (changes.md #156): source capsules and their full provenance are rendered without extra config reads.
internal object SavedIpSourcePills {
    fun create(
        context: Context,
        sources: List<IpTestConfigIp>,
        night: Boolean,
        dp: (Int) -> Int,
    ): LinearLayout {
        val pillFill = if (night) Color.argb(64, 129, 199, 132) else Color.argb(48, 46, 125, 50)
        val pillText = if (night) Color.parseColor("#90CAF9") else Color.parseColor("#1565C0")
        val group = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
        val labels = sources.mapNotNull { source ->
            when (source.via) {
                "server" -> "已在 server"
                "hosts", "alias" -> "已在 hosts"
                "dns" -> "配置解析 IP"
                else -> null
            }
        }.distinct()
        if (labels.isNotEmpty()) {
            group.addView(LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                labels.forEachIndexed { index, label ->
                    addView(
                        TextView(context).apply {
                            text = label
                            textSize = 12f
                            maxLines = 1
                            ellipsize = TextUtils.TruncateAt.END
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
            })
        }
        val names = sources.flatMap { source ->
            when (source.via) {
                "server" -> source.from.ifEmpty { listOf(source.name) }
                else -> listOf(source.name)
            }
        }.map(String::trim).filter(String::isNotEmpty).distinct()
        if (names.isNotEmpty()) {
            val preview = buildString {
                append(names.take(2).joinToString("、"))
                if (names.size > 2) append(" 等 ${names.size} 个")
            }
            group.addView(TextView(context).apply {
                text = preview
                textSize = 12f
                maxLines = 2
                ellipsize = TextUtils.TruncateAt.END
                setTextColor(pillText)
                setPadding(dp(2), dp(1), dp(2), dp(1))
                isClickable = true
                isFocusable = true
                setOnClickListener {
                    MaterialAlertDialogBuilder(context)
                        .setTitle("配置来源")
                        .setMessage(names.joinToString("\n"))
                        .setPositiveButton(android.R.string.ok, null)
                        .show()
                }
            }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        }
        return group
    }
}
