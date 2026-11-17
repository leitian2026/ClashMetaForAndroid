package com.github.kr328.clash.design.custom

import android.content.Context
import com.github.kr328.clash.core.model.MuxIp
import com.github.kr328.clash.core.model.MuxSnapshot
import com.github.kr328.clash.core.util.CountryFlag
import com.github.kr328.clash.design.R
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

/**
 * CUSTOM (CUSTOM.md #20, not upstream): the text of the "underlying connections per node" block,
 * one line per node and one indented line per address.
 */
object MuxText {
    fun build(context: Context, snapshot: MuxSnapshot): String {
        if (snapshot.mux.isEmpty() && snapshot.nodesOmitted == 0) return ""
        val lines = ArrayList<String>()
        val now = System.currentTimeMillis()

        for (n in snapshot.mux) {
            val name = n.node.ifEmpty { context.getString(R.string.connections_mux_other) }
            lines += if (n.udp > 0) {
                context.getString(R.string.connections_mux_node_udp, name, n.tcp, n.connections, n.udp)
            } else {
                context.getString(R.string.connections_mux_node, name, n.tcp, n.connections)
            }
            for (ip in n.ips) {
                lines += "    " + context.getString(R.string.connections_mux_ip, address(context, ip), ip.count) + since(context, ip, now)
            }
            if (n.ipsOmitted > 0) lines += "    " + context.getString(R.string.mux_ips_omitted, n.ipsOmitted)
            for (ip in n.udpIps) {
                lines += "    " + context.getString(R.string.connections_mux_udp_ip, address(context, ip), ip.count) + since(context, ip, now)
            }
            if (n.udpIpsOmitted > 0) lines += "    " + context.getString(R.string.mux_udp_ips_omitted, n.udpIpsOmitted)
        }
        if (snapshot.nodesOmitted > 0) lines += context.getString(R.string.mux_nodes_omitted, snapshot.nodesOmitted)
        return lines.joinToString("\n")
    }

    private fun since(context: Context, ip: MuxIp, now: Long): String {
        if (ip.since <= 0L) return ""
        val then = Calendar.getInstance().apply { timeInMillis = ip.since }
        val today = Calendar.getInstance().apply { timeInMillis = now }
        val sameDay = then.get(Calendar.YEAR) == today.get(Calendar.YEAR) &&
            then.get(Calendar.DAY_OF_YEAR) == today.get(Calendar.DAY_OF_YEAR)
        val pattern = if (sameDay) "HH:mm:ss" else "MM-dd HH:mm"
        val time = SimpleDateFormat(pattern, Locale.getDefault()).format(Date(ip.since))
        return " · " + context.getString(R.string.connections_mux_since, time)
    }

    private fun address(context: Context, ip: MuxIp): String {
        val flag = CountryFlag.of(ip.country)
        val text = ip.ip.ifEmpty { context.getString(R.string.connections_mux_udp_unknown) }
        return if (flag.isEmpty()) text else "$flag $text"
    }
}
