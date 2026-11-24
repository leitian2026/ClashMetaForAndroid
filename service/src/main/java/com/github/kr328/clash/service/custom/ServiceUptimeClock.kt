package com.github.kr328.clash.service.custom

import android.os.Bundle
import android.os.SystemClock

/**
 * changes.md #174. The moment the proxy service really started, kept in the :background process
 * (StatusProvider.serviceRunning is set there). Only set on start and cleared on stop: no timer, no polling.
 * The value is SystemClock.elapsedRealtime(), which is the same clock in every process.
 */
object ServiceUptimeClock {
    const val METHOD_STARTED_AT = "startedAt"
    private const val KEY = "elapsed"

    @Volatile
    private var startedAt: Long = -1L

    fun onRunningChanged(running: Boolean) {
        startedAt = if (running) SystemClock.elapsedRealtime() else -1L
    }

    /** Answer of StatusProvider.call(METHOD_STARTED_AT); null when the service is not running. */
    fun answer(): Bundle? {
        val value = startedAt
        return if (value >= 0L) Bundle().apply { putLong(KEY, value) } else null
    }

    fun read(result: Bundle?): Long = result?.getLong(KEY, -1L) ?: -1L
}
