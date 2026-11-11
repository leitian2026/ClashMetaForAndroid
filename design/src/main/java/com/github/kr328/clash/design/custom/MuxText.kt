package com.github.kr328.clash.design.custom

import android.content.Context
import com.github.kr328.clash.core.model.MuxIp
import com.github.kr328.clash.core.model.MuxNode
import com.github.kr328.clash.core.util.CountryFlag
import com.github.kr328.clash.design.R
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

/**
 * CUSTOM (CUSTOM.md #20, not upstream): the text of the "underlying connections per node" block of
 * the connections page, one line per node and one indented line per address.
 */
object MuxText {
    fun build(context: Context, nodes: List<MuxNode>): String {
        if (nodes.isEmpty()) return ""

        val lines = ArrayList<String>()
        val now = System.currentTimeMillis()

        for (n in nodes) {
            val name = n.node.ifEmpty { context.getString(R.string.connections_mux_other) }

            lines += if (n.udp > 0) {
                context.getString(R.string.connections_mux_node_udp, name, n.tcp, n.requests, n.udp)
            } else {
                context.getString(R.string.connections_mux_node, name, n.tcp, n.requests)
            }

            for (ip in n.ips) {
                lines += "    " + context.getString(R.string.connections_mux_ip, address(context, ip), ip.count) + since(ip, now)
            }

            for (ip in n.udpIps) {
                lines += "    " + context.getString(R.string.connections_mux_udp_ip, address(context, ip), ip.count) + since(ip, now)
            }
        }

        return lines.joinToString("\n")
    }

    // r27: the time the node started to be connected to this address, as a plain clock time ("13:42:07",
    // with the date in front when it is not today). No "x minutes ago": that would need a timer to stay right.
    private fun since(ip: MuxIp, now: Long): String {
        if (ip.since <= 0L) return ""

        val then = Calendar.getInstance().apply { timeInMillis = ip.since }
        val today = Calendar.getInstance().apply { timeInMillis = now }
        val sameDay = then.get(Calendar.YEAR) == today.get(Calendar.YEAR) &&
            then.get(Calendar.DAY_OF_YEAR) == today.get(Calendar.DAY_OF_YEAR)
        val pattern = if (sameDay) "HH:mm:ss" else "MM-dd HH:mm"

        return " · " + SimpleDateFormat(pattern, Locale.getDefault()).format(Date(ip.since))
    }

    private fun address(context: Context, ip: MuxIp): String {
        val flag = CountryFlag.of(ip.country)
        val text = ip.ip.ifEmpty { context.getString(R.string.connections_mux_udp_unknown) }

        return if (flag.isEmpty()) text else "$flag $text"
    }
}
