package com.github.kr328.clash.design.custom

import android.content.Context
import android.os.SystemClock
import com.github.kr328.clash.common.custom.UptimeFormat
import com.github.kr328.clash.design.R

/**
 * changes.md #174. The "running for ..." text after "运行中" on the home page.
 * Shows whole minutes, so the 1 s ticker only subtracts and compares; the text is rebuilt once a minute.
 */
class MainUptime(private val context: Context) {
    // SystemClock.elapsedRealtime() of the service start; -1 = unknown or stopped
    private var startedAt = -1L
    // minutes shown last time; -2 = nothing shown yet
    private var shownMinutes = -2L

    fun setStart(startedAt: Long) {
        this.startedAt = startedAt
        shownMinutes = -2L
    }

    fun changed(): Boolean = UptimeFormat.minutes(startedAt, SystemClock.elapsedRealtime()) != shownMinutes

    /** null = show only the plain running text. */
    fun text(): String? {
        val minutes = UptimeFormat.minutes(startedAt, SystemClock.elapsedRealtime())
        shownMinutes = minutes
        if (minutes < 0L) return null
        return UptimeFormat.format(
            minutes,
            context.getString(R.string.custom_uptime_day),
            context.getString(R.string.custom_uptime_hour),
            context.getString(R.string.custom_uptime_minute),
            context.getString(R.string.custom_uptime_sep)
        )
    }
}
