package com.github.kr328.clash.design.custom

import android.content.Context
import com.github.kr328.clash.core.model.MuxIp
import com.github.kr328.clash.core.model.MuxNode
import com.github.kr328.clash.core.util.CountryFlag
import com.github.kr328.clash.design.R

/**
 * CUSTOM (CUSTOM.md #20, not upstream): the text of the "underlying connections per node" block of
 * the connections page, one line per node and one indented line per address.
 */
object MuxText {
    fun build(context: Context, nodes: List<MuxNode>): String {
        if (nodes.isEmpty()) return ""

        val lines = ArrayList<String>()

        for (n in nodes) {
            val name = n.node.ifEmpty { context.getString(R.string.connections_mux_other) }

            lines += if (n.udp > 0) {
                context.getString(R.string.connections_mux_node_udp, name, n.tcp, n.requests, n.udp)
            } else {
                context.getString(R.string.connections_mux_node, name, n.tcp, n.requests)
            }

            for (ip in n.ips) {
                lines += "    " + context.getString(R.string.connections_mux_ip, address(context, ip), ip.count)
            }

            for (ip in n.udpIps) {
                lines += "    " + context.getString(R.string.connections_mux_udp_ip, address(context, ip), ip.count)
            }
        }

        return lines.joinToString("\n")
    }

    private fun address(context: Context, ip: MuxIp): String {
        val flag = CountryFlag.of(ip.country)
        val text = ip.ip.ifEmpty { context.getString(R.string.connections_mux_udp_unknown) }

        return if (flag.isEmpty()) text else "$flag $text"
    }
}
