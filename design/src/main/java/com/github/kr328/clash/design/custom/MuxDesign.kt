package com.github.kr328.clash.design.custom

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.drawable.GradientDrawable
import android.graphics.Typeface
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import com.github.kr328.clash.core.model.MuxIp
import com.github.kr328.clash.core.model.MuxNode
import com.github.kr328.clash.core.model.MuxSnapshot
import com.github.kr328.clash.design.Design
import com.github.kr328.clash.design.R
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.github.kr328.clash.design.databinding.DesignMuxBinding
import com.github.kr328.clash.design.util.applyFrom
import com.github.kr328.clash.design.util.layoutInflater
import com.github.kr328.clash.design.util.root
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

// CUSTOM (CUSTOM.md #20): the "underlying connections" page, how many sockets the core has open to each node.
class MuxDesign(context: Context) : Design<MuxDesign.Request>(context) {
    enum class Request {
        Refresh,
    }

    private val binding = DesignMuxBinding
        .inflate(context.layoutInflater, context.root, false)

    override val root: View
        get() = binding.root

    fun request(request: Request) {
        requests.trySend(request)
    }

    /** [error] is non-null only when querying or parsing the core response failed. */
    suspend fun patch(snapshot: MuxSnapshot?, running: Boolean, error: String? = null) {
        val message = when {
            !running -> context.getString(R.string.connections_not_running)
            error != null -> context.getString(R.string.mux_error, error)
            snapshot == null || (snapshot.mux.isEmpty() && snapshot.nodesOmitted == 0) -> context.getString(R.string.connections_empty)
            else -> null
        }

        withContext(Dispatchers.Main) {
            if (message != null) {
                binding.muxList.removeAllViews()
                binding.muxList.visibility = View.GONE
                binding.muxTextView.visibility = View.VISIBLE
                // no write (and no relayout) when the text did not change
                if (binding.muxTextView.text.toString() != message) {
                    binding.muxTextView.text = message
                }
            } else if (snapshot != null) {
                // only called when the answer of the core changed (MuxActivity compares it first)
                binding.muxTextView.visibility = View.GONE
                binding.muxList.visibility = View.VISIBLE
                rebuild(snapshot)
            }
        }
    }

    // CUSTOM (CUSTOM.md #20, r211): grouped layout, one block per node: a bold title row (name, counts), then one row
    // per address (flag + address, count and start time in two right-aligned columns), a thin divider between nodes.
    private val dp = context.resources.displayMetrics.density
    private val sidePadding = context.resources.getDimensionPixelSize(R.dimen.item_tailing_margin)

    private companion object {
        const val NAME_SP = 18f
        const val COUNTS_SP = 14f
        const val ADDRESS_SP = 16f
    }

    private fun px(value: Float): Int = (value * dp).toInt()

    private fun themeColors(attr: Int): ColorStateList? {
        val array = context.obtainStyledAttributes(intArrayOf(attr))
        try {
            return array.getColorStateList(0)
        } finally {
            array.recycle()
        }
    }

    private fun text(
        value: String,
        sp: Float,
        color: Int,
        bold: Boolean = false,
        width: Int = ViewGroup.LayoutParams.WRAP_CONTENT,
        weight: Float = 0f,
        end: Boolean = false,
    ): TextView = TextView(context).apply {
        text = value
        setTextSize(TypedValue.COMPLEX_UNIT_SP, sp)
        setTextColor(themeColors(color))
        if (bold) setTypeface(typeface, Typeface.BOLD)
        if (end) gravity = Gravity.END
        layoutParams = LinearLayout.LayoutParams(width, ViewGroup.LayoutParams.WRAP_CONTENT, weight)
    }

    private fun row(vararg views: View, startPadding: Int = 0, vertical: Float = 0f): LinearLayout =
        LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPaddingRelative(sidePadding + startPadding, px(vertical), sidePadding, px(vertical))
            views.forEach { addView(it) }
        }

    private fun divider(startInset: Int = 0, endInset: Int = 0): View = View(context).apply {
        setBackgroundColor(themeColors(android.R.attr.colorControlHighlight)?.defaultColor ?: 0)
        layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, maxOf(1, px(0.5f))).apply {
            marginStart = startInset
            marginEnd = endInset
        }
    }

    // CUSTOM (r212): one address = two lines of the same size and color, the address on top, "N 条 · 起始 hh:mm:ss" below
    private fun addressRow(prefix: String, ip: MuxIp, count: String, now: Long): View {
        val since = MuxText.sinceTime(ip, now)
        val second = if (since.isEmpty()) count else context.getString(R.string.mux_row_second, count, since)
        val first = text(prefix + MuxText.address(context, ip), ADDRESS_SP, android.R.attr.textColorPrimary).apply {
            typeface = Typeface.MONOSPACE
        }
        return LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPaddingRelative(sidePadding + px(12f), px(8f), sidePadding, px(8f))
            addView(first)
            addView(text(second, ADDRESS_SP, android.R.attr.textColorPrimary))
        }
    }

    private fun nodeBlock(n: MuxNode, now: Long): View {
        val block = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
        val other = n.node.isEmpty()

        val title = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(
                text(
                    if (other) context.getString(R.string.mux_other_name) else n.node,
                    NAME_SP, android.R.attr.textColorPrimary,
                ),
            )
            if (other) {
                addView(
                    text(context.getString(R.string.mux_other_tag), 12f, android.R.attr.textColorSecondary).apply {
                        setPaddingRelative(px(8f), px(2f), px(8f), px(2f))
                        background = GradientDrawable().apply {
                            cornerRadius = px(10f).toFloat()
                            setColor(themeColors(android.R.attr.colorControlHighlight)?.defaultColor ?: 0)
                        }
                        (layoutParams as LinearLayout.LayoutParams).marginStart = px(8f)
                    },
                )
            }
            // the counts sit right behind the name, not at the right edge
            val counts = if (n.udp > 0) {
                context.getString(R.string.mux_group_counts_udp, n.tcp, n.connections, n.udp)
            } else {
                context.getString(R.string.mux_group_counts, n.tcp, n.connections)
            }
            addView(text(counts, COUNTS_SP, android.R.attr.textColorSecondary).apply {
                (layoutParams as LinearLayout.LayoutParams).marginStart = px(10f)
            })
        }
        block.addView(row(title, vertical = 0f).apply { setPaddingRelative(sidePadding, px(12f), sidePadding, px(6f)) })
        block.addView(divider(sidePadding, sidePadding))

        // thin divider between two addresses, indented like the addresses
        val rows = ArrayList<View>()
        for (ip in n.ips) rows.add(addressRow("", ip, context.getString(R.string.mux_row_count_tcp, ip.count), now))
        for (ip in n.udpIps) rows.add(addressRow("UDP ", ip, context.getString(R.string.mux_row_count_udp, ip.count), now))
        rows.forEachIndexed { i, v ->
            if (i > 0) block.addView(divider(sidePadding + px(12f), sidePadding))
            block.addView(v)
        }
        if (n.ipsOmitted > 0) block.addView(omitted(context.getString(R.string.mux_ips_omitted, n.ipsOmitted)))
        if (n.udpIpsOmitted > 0) block.addView(omitted(context.getString(R.string.mux_udp_ips_omitted, n.udpIpsOmitted)))

        block.setPadding(0, 0, 0, px(8f))
        return block
    }

    private fun omitted(value: String): View =
        row(text(value, 12f, android.R.attr.textColorTertiary, weight = 1f, width = 0), startPadding = px(12f), vertical = 4f)

    private fun rebuild(snapshot: MuxSnapshot) {
        val list = binding.muxList
        val now = System.currentTimeMillis()

        list.removeAllViews()

        snapshot.mux.forEachIndexed { index, node ->
            if (index > 0) list.addView(divider())
            list.addView(nodeBlock(node, now))
        }

        if (snapshot.nodesOmitted > 0) {
            list.addView(divider())
            list.addView(row(text(context.getString(R.string.mux_nodes_omitted, snapshot.nodesOmitted), 12f, android.R.attr.textColorTertiary), vertical = 10f))
        }
    }

    init {
        binding.self = this

        binding.muxHintButton.setOnClickListener {
            MaterialAlertDialogBuilder(context)
                .setTitle(R.string.mux_title)
                .setMessage(R.string.mux_help)
                .setPositiveButton(android.R.string.ok, null)
                .show()
        }

        binding.activityBarLayout.applyFrom(context)
    }
}
