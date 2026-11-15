package com.github.kr328.clash.design.custom

import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import com.github.kr328.clash.core.custom.IssueItem
import com.github.kr328.clash.core.custom.IssuesSnapshot
import com.github.kr328.clash.design.R
import com.google.android.material.button.MaterialButton

// CUSTOM (CUSTOM.md #53): the "config problems" block at the top of the errors page: every problem of the running
// config the core found (a dead DNS server, a node address that does not answer, a failed subscription update, ...),
// one row each with an "ignore" button; ignored and resolved ones are listed below, an ignored one can be un-ignored.
// [onIgnore] gets (id, true = ignore / false = un-ignore).
class IssuesBlock(private val context: Context, private val onIgnore: (String, Boolean) -> Unit) {
    private val night = (context.resources.configuration.uiMode and android.content.res.Configuration.UI_MODE_NIGHT_MASK) ==
        android.content.res.Configuration.UI_MODE_NIGHT_YES
    private val colorWarn = if (night) Color.parseColor("#FFB74D") else Color.parseColor("#E65100")

    private fun dp(v: Int) = (v * context.resources.displayMetrics.density).toInt()

    /** Fills [list]; nothing is shown (the list is emptied) when there is no problem. */
    fun fill(list: LinearLayout, snapshot: IssuesSnapshot?) {
        list.removeAllViews()

        val items = snapshot?.items.orEmpty()
        val active = items.filter { !it.ignored && !it.resolved }
        val ignored = items.filter { it.ignored && !it.resolved }
        val resolved = items.filter { it.resolved }

        section(list, R.string.issues_section_active, active, false)
        section(list, R.string.issues_section_ignored, ignored, true)
        section(list, R.string.issues_section_resolved, resolved, true)
    }

    /** The same as plain text, for "copy all". */
    fun plain(snapshot: IssuesSnapshot?): String {
        val items = snapshot?.items.orEmpty().filter { !it.resolved && !it.ignored }

        if (items.isEmpty()) return ""

        return context.getString(R.string.issues_section_active, items.size) + "\n" +
            items.joinToString("\n") { "  " + title(it) + " · " + detail(it) }
    }

    private fun section(list: LinearLayout, title: Int, items: List<IssueItem>, ignoredList: Boolean) {
        if (items.isEmpty()) return

        list.addView(
            TextView(context).apply {
                text = context.getString(title, items.size)
                textSize = 12f
                setTextColor(colorWarn)
                setPadding(dp(16), dp(14), dp(16), dp(4))
            },
        )

        for (it in items) list.addView(row(it, ignoredList))
    }

    private fun row(item: IssueItem, ignoredList: Boolean): View {
        val row = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(16), dp(8), dp(8), dp(8))
        }

        val col = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }

        col.addView(
            TextView(context).apply {
                text = title(item)
                textSize = 14f
                setTypeface(null, Typeface.BOLD)
            },
        )
        col.addView(
            TextView(context).apply {
                text = detail(item)
                textSize = 12f
                alpha = 0.75f
            },
        )

        row.addView(col, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))

        if (!item.resolved) {
            row.addView(
                MaterialButton(context, null, com.google.android.material.R.attr.borderlessButtonStyle).apply {
                    setText(if (ignoredList) R.string.issues_unignore else R.string.issues_ignore)
                    isAllCaps = false
                    setOnClickListener { onIgnore(item.id, !ignoredList) }
                },
                LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT),
            )
        }

        return row
    }

    private fun title(item: IssueItem): String = context.getString(IssueText.kindRes(item.kind), IssueText.subject(item))

    private fun detail(item: IssueItem): String {
        val parts = ArrayList<String>()

        if (item.code.isNotEmpty() && item.code != "other") parts += ErrorsText.explain(context, item.code)

        if (item.reason.isNotEmpty()) parts += item.reason

        if (item.count > 1) parts += context.getString(R.string.issues_count, item.count)

        parts += AgeText.testedAgo(context, item.time)

        return parts.joinToString(" · ")
    }
}
