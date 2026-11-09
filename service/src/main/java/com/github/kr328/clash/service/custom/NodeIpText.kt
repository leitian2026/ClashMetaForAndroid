package com.github.kr328.clash.service.custom

import android.content.Context
import com.github.kr328.clash.core.Clash
import com.github.kr328.clash.core.util.CountryFlag
import com.github.kr328.clash.service.R

// CUSTOM (CUSTOM.md #7): text of the status-bar notification (replaces the upload/download speed):
// "<flag> <ip> · <node>". It is rebuilt only after NodeIpNotifier marked it stale (the core told us
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
            dirty = false
            cached = build(context)
        }

        return cached
    }

    private fun build(context: Context): String {
        val state = try {
            Clash.queryTunnelState()
        } catch (e: Exception) {
            return context.getString(R.string.custom_notification_no_ip)
        }

        if (state.currentIp.isEmpty()) return context.getString(R.string.custom_notification_no_ip)

        val flag = CountryFlag.of(state.currentCountry).ifEmpty { "？" }

        return "$flag ${state.currentIp} · ${state.currentNode}"
    }
}
