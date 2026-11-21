package com.github.kr328.clash.custom

import java.util.concurrent.CopyOnWriteArraySet
import kotlin.math.max

/**
 * CUSTOM #158: event-driven process-wide exclusion between the IP test page and saved-IP speed actions.
 * No timer, polling, or background work is used.
 */
internal object IpSpeedTestCoordinator {
    @Volatile
    var isBusy: Boolean = false
        private set

    private val listeners = CopyOnWriteArraySet<(Boolean) -> Unit>()

    data class DownloadProgress(val ip: String, val direct: Boolean, val percent: Int, val savedOperatorId: String? = null)
    @Volatile private var downloadProgress: DownloadProgress? = null
    fun currentProgress(): DownloadProgress? = downloadProgress
    fun updateProgress(
        ip: String,
        direct: Boolean,
        bytes: Long,
        elapsedMs: Long,
        maxBytes: Long,
        maxSeconds: Int,
        savedOperatorId: String? = null,
    ): DownloadProgress {
        val byteRatio = bytes.toDouble() / maxBytes.coerceAtLeast(1L).toDouble()
        val timeRatio = elapsedMs.toDouble() / (maxSeconds.coerceAtLeast(1).toDouble() * 1000.0)
        val percent = (max(byteRatio, timeRatio) * 100.0).toInt().coerceIn(0, 100)
        return DownloadProgress(ip, direct, percent, savedOperatorId).also { downloadProgress = it }
    }
    fun clearProgress() { downloadProgress = null }

    @Synchronized
    fun tryAcquire(): Boolean {
        if (isBusy) return false
        setBusy(true)
        return true
    }

    @Synchronized
    fun setBusy(value: Boolean) {
        if (isBusy == value) return
        isBusy = value
        listeners.forEach { listener -> runCatching { listener(value) } }
    }

    fun addListener(listener: (Boolean) -> Unit): () -> Unit {
        listeners.add(listener)
        listener(isBusy)
        return { listeners.remove(listener) }
    }
}
