package com.github.kr328.clash.service.custom

import android.content.Context
import com.github.kr328.clash.core.Clash
import com.github.kr328.clash.service.R

// CUSTOM #7/#176: the separate IP line in the status-bar notification:
// "<flag> <ip> [· <flag> <ip> ...] · <node>" (every address in use). It is rebuilt only after
// NodeIpNotifier marks it stale (the core reports a node IP change); otherwise cached text is returned.
object NodeIpText {
    @Volatile
    private var dirty = true
    private var cached = ""

    fun invalidate() {
        dirty = true
    }

    @Synchronized
    fun get(context: Context): String {
        if (dirty) {
            val result = build(context)
            cached = result.first
            dirty = !result.second
        }

        return cached
    }

    private fun build(context: Context): Pair<String, Boolean> {
        val state = try {
            Clash.queryTunnelState()
        } catch (e: Exception) {
            return context.getString(R.string.custom_notification_read_failed) to false
        }

        val ips = state.ipsText { context.getString(R.string.custom_ips_more, it) }

        if (ips.isEmpty()) return context.getString(R.string.custom_notification_no_ip) to true

        return "$ips · ${state.currentNode}" to true
    }
}
