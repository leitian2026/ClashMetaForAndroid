package com.github.kr328.clash.service.custom

import android.content.Context
import com.github.kr328.clash.core.Clash
import com.github.kr328.clash.service.R

// CUSTOM (CUSTOM.md #7): text of the status-bar notification (replaces the upload/download speed):
// "<flag> <ip> [· <flag> <ip> ...] · <node>" (every address in use, CUSTOM.md #7). It is rebuilt only after NodeIpNotifier marked it stale (the core told us
// the node IP changed), otherwise the cached text is returned.
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
