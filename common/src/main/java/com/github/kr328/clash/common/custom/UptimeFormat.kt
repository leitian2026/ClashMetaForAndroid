package com.github.kr328.clash.common.custom

/**
 * changes.md #174. Pure logic for the "running for ..." text on the home page.
 * Both times are SystemClock.elapsedRealtime() values (milliseconds), so a wall-clock change does not matter.
 */
object UptimeFormat {
    /** Whole minutes between [startedAt] and [now]; -1 when the start is unknown or lies after [now]. */
    fun minutes(startedAt: Long, now: Long): Long {
        if (startedAt < 0L || now < startedAt) return -1L
        return (now - startedAt) / 60_000L
    }

    /** "23m", "1h 23m" or "2d 1h 23m" with the given unit words; "" when [totalMinutes] is negative. */
    fun format(totalMinutes: Long, day: String, hour: String, minute: String, sep: String): String {
        if (totalMinutes < 0L) return ""
        val d = totalMinutes / 1440L
        val h = totalMinutes % 1440L / 60L
        val m = totalMinutes % 60L
        return when {
            d > 0L -> "$d$day$sep$h$hour$sep$m$minute"
            h > 0L -> "$h$hour$sep$m$minute"
            else -> "$m$minute"
        }
    }
}
