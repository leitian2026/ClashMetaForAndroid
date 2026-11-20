package com.github.kr328.clash.design.custom

import android.content.Context
import com.github.kr328.clash.design.R
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

/**
 * CUSTOM (CUSTOM.md #156): the "tested ... ago" line under each IP in the saved-IP table.
 * Under 15 days: seconds / minutes+seconds / hours+minutes / days+hours. 15 days or more: the date
 * (without the year when it is this year). Computed once when a row is built; it never ticks by itself.
 */
object SavedIpTimeText {
    private const val DATE_AFTER_MS = 15L * 24L * 60L * 60L * 1000L

    /** [at] and [now] are unix milliseconds. Returns "" for an IP that was never tested ([at] <= 0). */
    fun text(context: Context, at: Long, now: Long = System.currentTimeMillis()): String {
        if (at <= 0L) return ""
        if (at > now) return AgeText.reachAgo(context, at, now) // clock moved back: "just now"
        if (now - at >= DATE_AFTER_MS) {
            val tested = Calendar.getInstance().apply { timeInMillis = at }
            val current = Calendar.getInstance().apply { timeInMillis = now }
            val pattern = context.getString(
                if (tested.get(Calendar.YEAR) == current.get(Calendar.YEAR)) {
                    R.string.saved_ip_time_date_same_year
                } else {
                    R.string.saved_ip_time_date_other_year
                },
            )
            return SimpleDateFormat(pattern, Locale.getDefault()).format(Date(at))
        }
        val seconds = ((now - at) / 1000L).coerceAtLeast(0L)
        return when {
            seconds < 60L -> context.getString(R.string.saved_ip_elapsed_seconds, seconds.toInt())
            seconds < 3600L -> context.getString(
                R.string.saved_ip_elapsed_minutes_seconds,
                (seconds / 60L).toInt(),
                (seconds % 60L).toInt(),
            )
            seconds < 86400L -> context.getString(
                R.string.saved_ip_elapsed_hours_minutes,
                (seconds / 3600L).toInt(),
                ((seconds % 3600L) / 60L).toInt(),
            )
            else -> context.getString(
                R.string.saved_ip_elapsed_days_hours,
                (seconds / 86400L).toInt(),
                ((seconds % 86400L) / 3600L).toInt(),
            )
        }
    }
}
