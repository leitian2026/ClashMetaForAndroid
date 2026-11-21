package com.github.kr328.clash.design.custom

import android.content.SharedPreferences

/**
 * CUSTOM #99/#158: shared download-test limits used by the dialog and both app pages.
 * Invalid or missing preference values fall back to the defaults.
 */
object IpDownloadLimit {
    const val DEFAULT_MAX_MB = 30
    const val DEFAULT_MAX_SECONDS = 6
    const val MIN_MAX_MB = 1
    const val MAX_MAX_MB = 200
    const val MIN_MAX_SECONDS = 1
    const val MAX_MAX_SECONDS = 60

    private const val PREF_MAX_MB = "ip_download_max_mb"
    private const val PREF_MAX_SECONDS = "ip_download_max_seconds"

    data class Limits(val maxMb: Int, val maxSeconds: Int) {
        val maxBytes: Long
            get() = maxMb.toLong() * 1024L * 1024L
    }

    fun parse(maxMb: String?, maxSeconds: String?): Limits = Limits(
        maxMb?.trim()?.toIntOrNull()?.takeIf { it in MIN_MAX_MB..MAX_MAX_MB } ?: DEFAULT_MAX_MB,
        maxSeconds?.trim()?.toIntOrNull()?.takeIf { it in MIN_MAX_SECONDS..MAX_MAX_SECONDS } ?: DEFAULT_MAX_SECONDS,
    )

    fun read(prefs: SharedPreferences): Limits = Limits(
        prefs.getInt(PREF_MAX_MB, DEFAULT_MAX_MB).takeIf { it in MIN_MAX_MB..MAX_MAX_MB } ?: DEFAULT_MAX_MB,
        prefs.getInt(PREF_MAX_SECONDS, DEFAULT_MAX_SECONDS)
            .takeIf { it in MIN_MAX_SECONDS..MAX_MAX_SECONDS } ?: DEFAULT_MAX_SECONDS,
    )

    fun save(prefs: SharedPreferences, limits: Limits) {
        prefs.edit()
            .putInt(PREF_MAX_MB, limits.maxMb.coerceIn(MIN_MAX_MB, MAX_MAX_MB))
            .putInt(PREF_MAX_SECONDS, limits.maxSeconds.coerceIn(MIN_MAX_SECONDS, MAX_MAX_SECONDS))
            .apply()
    }

    fun clampMaxBytes(value: Long): Long =
        value.coerceIn(MIN_MAX_MB.toLong() * 1024L * 1024L, MAX_MAX_MB.toLong() * 1024L * 1024L)

    fun clampMaxSeconds(value: Long): Long =
        value.coerceIn(MIN_MAX_SECONDS.toLong(), MAX_MAX_SECONDS.toLong())
}
