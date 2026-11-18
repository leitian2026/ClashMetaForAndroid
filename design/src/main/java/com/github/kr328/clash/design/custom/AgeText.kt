package com.github.kr328.clash.design.custom

import android.content.Context
import com.github.kr328.clash.design.R

/** CUSTOM (CUSTOM.md #12, #24, #156): "3 min ago" style text for a time in the past (same words as the node cards). */
object AgeText {
    /** [at] and [now] are unix milliseconds. Computed when asked; it never updates itself. */
    fun reachAgo(context: Context, at: Long, now: Long = System.currentTimeMillis()): String {
        val seconds = ((now - at) / 1000).coerceAtLeast(0)
        return when {
            seconds < 60 -> context.getString(R.string.reach_age_now)
            seconds < 3600 -> context.getString(R.string.reach_age_minutes, (seconds / 60).toInt())
            seconds < 86400 -> context.getString(R.string.reach_age_hours, (seconds / 3600).toInt())
            else -> context.getString(R.string.reach_age_days, (seconds / 86400).toInt())
        }
    }

    /** Compact forms used only when a saved-IP row has insufficient horizontal room. */
    fun reachAgoCompact(context: Context, at: Long, now: Long = System.currentTimeMillis()): String {
        val seconds = ((now - at) / 1000).coerceAtLeast(0)
        return when {
            seconds < 60 -> context.getString(R.string.saved_ip_time_compact_now)
            seconds < 3600 -> context.getString(R.string.saved_ip_time_compact_minutes, (seconds / 60).toInt())
            seconds < 86400 -> context.getString(R.string.saved_ip_time_compact_hours, (seconds / 3600).toInt())
            else -> context.getString(R.string.saved_ip_time_compact_days, (seconds / 86400).toInt())
        }
    }

    fun testedAgo(context: Context, at: Long, now: Long = System.currentTimeMillis()): String {
        val seconds = ((now - at) / 1000).coerceAtLeast(0)

        return when {
            seconds < 10 -> context.getString(R.string.ip_delay_age_now)
            seconds < 60 -> context.getString(R.string.ip_delay_age_seconds, seconds.toInt())
            seconds < 3600 -> context.getString(R.string.ip_delay_age_minutes, (seconds / 60).toInt())
            seconds < 86400 -> context.getString(R.string.ip_delay_age_hours, (seconds / 3600).toInt())
            else -> context.getString(R.string.ip_delay_age_days, (seconds / 86400).toInt())
        }
    }
}
