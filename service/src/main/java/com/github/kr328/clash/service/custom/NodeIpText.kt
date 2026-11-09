package com.github.kr328.clash.service.custom

import android.content.Context
import com.github.kr328.clash.core.Clash
import com.github.kr328.clash.core.util.CountryFlag
import com.github.kr328.clash.service.R

// CUSTOM (CUSTOM.md #7): text of the status-bar notification (replaces the upload/download speed):
// "<flag> <ip> · <node>". Called from DynamicNotificationModule's existing 1-second update (which
// already stops when the screen is off); the core is only asked every 3rd call.
object NodeIpText {
    private var calls = 0
    private var cached = ""

    fun get(context: Context): String {
        if (calls++ % 3 == 0 || cached.isEmpty()) {
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
