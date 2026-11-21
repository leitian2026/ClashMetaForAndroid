package com.github.kr328.clash.design.custom

import android.content.Context
import android.content.SharedPreferences
import android.widget.EditText
import android.widget.Toast

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

    enum class SaveStatus { UNCHANGED, SAVED, CLAMPED }

    data class SaveResult(
        val status: SaveStatus,
        val limits: Limits,
        val maxMbClamped: Boolean = false,
        val maxSecondsClamped: Boolean = false,
    )

    private var lastToast: Toast? = null

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

    // CUSTOM #99: validate and persist both fields as one preference update, only when values changed.
    fun saveInputs(prefs: SharedPreferences, maxMbText: String?, maxSecondsText: String?): SaveResult {
        val parsedMb = maxMbText?.trim()?.toIntOrNull()
        val parsedSeconds = maxSecondsText?.trim()?.toIntOrNull()
        val clamped = (parsedMb != null && parsedMb !in MIN_MAX_MB..MAX_MAX_MB) ||
            (parsedSeconds != null && parsedSeconds !in MIN_MAX_SECONDS..MAX_MAX_SECONDS)
        val limits = Limits(
            parsedMb?.coerceIn(MIN_MAX_MB, MAX_MAX_MB) ?: DEFAULT_MAX_MB,
            parsedSeconds?.coerceIn(MIN_MAX_SECONDS, MAX_MAX_SECONDS) ?: DEFAULT_MAX_SECONDS,
        )
        val current = read(prefs)
        if (limits == current) {
            return SaveResult(
                SaveStatus.UNCHANGED,
                limits,
                parsedMb != null && parsedMb !in MIN_MAX_MB..MAX_MAX_MB,
                parsedSeconds != null && parsedSeconds !in MIN_MAX_SECONDS..MAX_MAX_SECONDS,
            )
        }
        prefs.edit()
            .putInt(PREF_MAX_MB, limits.maxMb)
            .putInt(PREF_MAX_SECONDS, limits.maxSeconds)
            .apply()
        return SaveResult(
            if (clamped) SaveStatus.CLAMPED else SaveStatus.SAVED,
            limits,
            parsedMb != null && parsedMb !in MIN_MAX_MB..MAX_MAX_MB,
            parsedSeconds != null && parsedSeconds !in MIN_MAX_SECONDS..MAX_MAX_SECONDS,
        )
    }

    // CUSTOM #99: only focus-loss events trigger persistence; dismiss flushes without a Toast.
    fun bindAutoSave(
        context: Context,
        prefs: SharedPreferences,
        maxMbInput: EditText,
        maxSecondsInput: EditText,
    ) {
        fun save(showToast: Boolean, lostFocusInput: EditText? = null) {
            val result = saveInputs(prefs, maxMbInput.text?.toString(), maxSecondsInput.text?.toString())
            if (result.status != SaveStatus.UNCHANGED && showToast) {
                lastToast?.cancel()
                val message = when (result.status) {
                    SaveStatus.SAVED -> context.getString(
                        R.string.iptest_download_limit_saved,
                        result.limits.maxMb,
                        result.limits.maxSeconds,
                    )
                    SaveStatus.CLAMPED -> context.getString(
                        R.string.iptest_download_limit_clamped,
                        result.limits.maxMb,
                        result.limits.maxSeconds,
                    )
                    SaveStatus.UNCHANGED -> return
                }
                lastToast = Toast.makeText(context, message, Toast.LENGTH_SHORT).also { it.show() }
            }
            // Only rewrite the field that has just lost focus; never touch the field still being edited.
            when (lostFocusInput) {
                maxMbInput -> if (result.maxMbClamped) maxMbInput.setText(result.limits.maxMb.toString())
                maxSecondsInput -> if (result.maxSecondsClamped) maxSecondsInput.setText(result.limits.maxSeconds.toString())
            }
        }
        val listener = android.view.View.OnFocusChangeListener { view, hasFocus ->
            if (!hasFocus) save(true, view as? EditText)
        }
        maxMbInput.onFocusChangeListener = listener
        maxSecondsInput.onFocusChangeListener = listener
        maxMbInput.setTag(R.id.iptest_download_limit_flush, Runnable { save(false) })
    }

    fun flushPending(maxMbInput: EditText) {
        (maxMbInput.getTag(R.id.iptest_download_limit_flush) as? Runnable)?.run()
    }

    fun clampMaxBytes(value: Long): Long =
        value.coerceIn(MIN_MAX_MB.toLong() * 1024L * 1024L, MAX_MAX_MB.toLong() * 1024L * 1024L)

    fun clampMaxSeconds(value: Long): Long =
        value.coerceIn(MIN_MAX_SECONDS.toLong(), MAX_MAX_SECONDS.toLong())
}