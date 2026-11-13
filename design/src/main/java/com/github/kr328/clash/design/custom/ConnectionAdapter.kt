package com.github.kr328.clash.design.custom

import android.content.Context
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.github.kr328.clash.common.compat.preferredLocale
import com.github.kr328.clash.core.model.ConnectionItem
import com.github.kr328.clash.core.util.CountryFlag
import com.github.kr328.clash.design.R
import com.github.kr328.clash.design.util.toBytesString
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

// CUSTOM (CUSTOM.md #8): rows of the connections page. Rows are diffed by connection id, so only the
// rows whose traffic changed are rebound on a refresh.
class ConnectionAdapter(
    private val context: Context,
    private val open: (ConnectionItem) -> Unit,
) : RecyclerView.Adapter<ConnectionAdapter.Holder>() {
    class Holder(view: View) : RecyclerView.ViewHolder(view) {
        val title: TextView = view.findViewById(R.id.connection_title)
        val node: TextView = view.findViewById(R.id.connection_node)
        val info: TextView = view.findViewById(R.id.connection_info)
    }

    var items: List<ConnectionItem> = emptyList()

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder {
        return Holder(
            LayoutInflater.from(parent.context).inflate(R.layout.adapter_connection, parent, false)
        )
    }

    override fun onBindViewHolder(holder: Holder, position: Int) {
        val item = items[position]

        holder.title.text = ConnectionText.target(item)
        holder.node.text = ConnectionText.node(item)
        holder.info.text = ConnectionText.info(context, item)
        holder.itemView.setOnClickListener { open(item) }
    }

    override fun getItemCount(): Int {
        return items.size
    }
}

// Texts of a connection, shared by the rows and the detail dialog.
object ConnectionText {
    fun target(item: ConnectionItem): String {
        val name = item.host.ifEmpty { item.ip }

        return when {
            name.isEmpty() -> "-"
            item.port.isEmpty() -> name
            name.contains(':') -> "[$name]:${item.port}" // IPv6 literal
            else -> "$name:${item.port}"
        }
    }

    // "<flag> <node> · <node ip>"; the node is the one that really carries the connection
    fun node(item: ConnectionItem): String {
        val leaf = item.chains.firstOrNull() ?: return "-"
        val flag = CountryFlag.of(item.nodeCountry)

        return buildString {
            if (flag.isNotEmpty()) append(flag).append(' ')

            append(leaf)

            if (item.nodeIp.isNotEmpty()) append(" · ").append(item.nodeIp)
        }
    }

    fun info(context: Context, item: ConnectionItem): String {
        return listOf(
            item.network.uppercase(Locale.ROOT),
            ruleWithVerdict(context, item),
            traffic(item),
            item.process,
            time(context, item.start, "HH:mm:ss"),
        ).filter { it.isNotEmpty() }.joinToString(" · ")
    }

    fun detail(context: Context, item: ConnectionItem): String {
        val lines = ArrayList<String>()

        fun add(label: Int, value: String) {
            if (value.isNotEmpty()) {
                lines.add(
                    context.getString(
                        R.string.connection_detail_line,
                        context.getString(label),
                        value,
                    )
                )
            }
        }

        val target = target(item)

        add(
            R.string.connection_label_target,
            if (item.host.isNotEmpty() && item.ip.isNotEmpty()) "$target (${item.ip})" else target,
        )
        add(R.string.connection_label_source, item.source)
        add(
            R.string.connection_label_network,
            listOf(item.network.uppercase(Locale.ROOT), item.type)
                .filter { it.isNotEmpty() }
                .joinToString(" · "),
        )
        // the core lists the carrying node first; the route reads better from the group to the node
        add(R.string.connection_label_chain, item.chains.asReversed().joinToString(" → "))
        add(R.string.connection_label_verdict, verdict(context, item))
        add(R.string.connection_label_node_ip, nodeIp(item))
        add(R.string.connection_label_rule, rule(item))
        add(R.string.connection_label_app, item.process)
        add(R.string.connection_label_start, time(context, item.start, "yyyy-MM-dd HH:mm:ss"))
        add(R.string.connection_label_traffic, traffic(item))

        return lines.joinToString("\n")
    }

    private fun nodeIp(item: ConnectionItem): String {
        if (item.nodeIp.isEmpty()) return ""

        val flag = CountryFlag.of(item.nodeCountry)

        return if (flag.isEmpty()) item.nodeIp else "$flag ${item.nodeIp}"
    }

    // CUSTOM.md #39: "Direct ← GeoSite,cn": says why a connection did not go through the proxy
    private fun ruleWithVerdict(context: Context, item: ConnectionItem): String {
        val r = rule(item)

        if (r.isEmpty()) return ""

        return when (leafKind(item)) {
            LeafKind.Direct -> context.getString(R.string.connection_row_direct, r)
            LeafKind.Reject -> context.getString(R.string.connection_row_reject, r)
            else -> r
        }
    }

    private fun verdict(context: Context, item: ConnectionItem): String {
        val r = rule(item)

        if (r.isEmpty()) return ""

        return when (leafKind(item)) {
            LeafKind.Direct -> context.getString(R.string.connection_verdict_direct, r)
            LeafKind.Reject -> context.getString(R.string.connection_verdict_reject, r)
            else -> context.getString(
                R.string.connection_verdict_proxy,
                item.chains.asReversed().joinToString(" → "),
                r,
            )
        }
    }

    private enum class LeafKind { Direct, Reject, Other }

    // the core lists the node that really carries the connection first
    private fun leafKind(item: ConnectionItem): LeafKind {
        val leaf = item.chains.firstOrNull() ?: return LeafKind.Other

        return when {
            leaf.equals("DIRECT", ignoreCase = true) -> LeafKind.Direct
            leaf.startsWith("REJECT", ignoreCase = true) -> LeafKind.Reject
            else -> LeafKind.Other
        }
    }

    private fun rule(item: ConnectionItem): String {
        return when {
            item.rule.isEmpty() -> ""
            item.payload.isEmpty() -> item.rule
            else -> "${item.rule},${item.payload}"
        }
    }

    private fun traffic(item: ConnectionItem): String {
        return "↑ ${item.upload.toBytesString()} ↓ ${item.download.toBytesString()}"
    }

    fun time(context: Context, millis: Long, pattern: String): String {
        if (millis <= 0) return ""

        val locale = context.resources.configuration.preferredLocale

        return SimpleDateFormat(pattern, locale).format(Date(millis))
    }
}
