package com.github.kr328.clash.design.custom

import android.content.Context
import com.github.kr328.clash.core.custom.IpHistoryEntry
import com.github.kr328.clash.core.model.TunnelState
import com.github.kr328.clash.core.util.CountryFlag
import com.github.kr328.clash.design.R
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

// CUSTOM (CUSTOM.md #33): what the "network status" card on the home page shows. It only gathers what
// the app already has (the node and the addresses in use from #7, the node's last delay, the last
// switch from #10) into two lines; nothing is measured for it.

/** What the home page needs besides the tunnel state: the delay of the node in use and the last two IP switches. */
class NetExtra(
    val delay: Int?, // milliseconds, null when the node has no delay yet
    val last: IpHistoryEntry?, // the newest switch
    val previous: IpHistoryEntry?, // the one before it
)

object MainCustomNet {
    /** The text of the card; null (the card is hidden) when the core is not running or no node is in use. */
    fun text(context: Context, running: Boolean, state: TunnelState, extra: NetExtra?): String? {
        if (!running || state.currentNode.isBlank()) return null

        val ips = state.ipsText { context.getString(R.string.custom_ips_more, it) }

        // line 1: 🇺🇸 1.2.3.4 · node name · 86 ms
        val first = ArrayList<String>()

        if (ips.isNotEmpty()) first.add(ips)

        first.add(state.currentNode)

        val delay = extra?.delay

        if (delay != null && delay > 0) first.add(context.getString(R.string.custom_net_delay, delay))

        val lines = ArrayList<String>()

        lines.add(first.joinToString(" · "))

        // line 2: the last switch, "10:42  🇯🇵 5.6.7.8 → 🇺🇸 1.2.3.4"
        val last = extra?.last

        if (last != null && last.ip.isNotEmpty()) {
            val time = formatTime(last.t)
            val now = describe(last)
            val previous = extra.previous

            lines.add(
                if (previous != null && previous.ip.isNotEmpty()) {
                    context.getString(R.string.custom_net_switch, time, describe(previous), now)
                } else {
                    context.getString(R.string.custom_net_switch_first, time, now)
                },
            )
        }

        return lines.joinToString("\n")
    }

    private fun describe(entry: IpHistoryEntry): String = "${CountryFlag.of(entry.country)} ${entry.ip}".trim()

    // "10:42" for today, "11-12 10:42" for an earlier day
    private fun formatTime(millis: Long): String {
        val today = Calendar.getInstance()
        val then = Calendar.getInstance().apply { timeInMillis = millis }

        val sameDay = today.get(Calendar.YEAR) == then.get(Calendar.YEAR) &&
            today.get(Calendar.DAY_OF_YEAR) == then.get(Calendar.DAY_OF_YEAR)

        return SimpleDateFormat(if (sameDay) "HH:mm" else "MM-dd HH:mm", Locale.getDefault()).format(Date(millis))
    }
}
