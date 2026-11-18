package com.github.kr328.clash.design.custom

import android.content.Context
import com.github.kr328.clash.core.model.MuxIp
import com.github.kr328.clash.core.util.CountryFlag
import com.github.kr328.clash.design.R
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

/**
 * CUSTOM (CUSTOM.md #20, not upstream): small text helpers of the "underlying connections" page.
 * (r211: the page is no longer one block of text; MuxDesign builds the rows. [build] was removed.)
 */
object MuxText {
    /** The time the node started to be connected to this address: `13:42:07`, or `11-10 23:08` when it is not today. "" = unknown. */
    fun sinceTime(ip: MuxIp, now: Long): String {
        if (ip.since <= 0L) return ""
        val then = Calendar.getInstance().apply { timeInMillis = ip.since }
        val today = Calendar.getInstance().apply { timeInMillis = now }
        val sameDay = then.get(Calendar.YEAR) == today.get(Calendar.YEAR) &&
            then.get(Calendar.DAY_OF_YEAR) == today.get(Calendar.DAY_OF_YEAR)
        val pattern = if (sameDay) "HH:mm:ss" else "MM-dd HH:mm"
        return SimpleDateFormat(pattern, Locale.getDefault()).format(Date(ip.since))
    }

    /** `🇯🇵 1.2.3.4` (the flag only when already known), or the "address unknown" text for a UDP socket without a peer. */
    fun address(context: Context, ip: MuxIp): String {
        val flag = CountryFlag.of(ip.country)
        val text = ip.ip.ifEmpty { context.getString(R.string.connections_mux_udp_unknown) }
        return if (flag.isEmpty()) text else "$flag $text"
    }
}
