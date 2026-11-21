package com.github.kr328.clash.custom

import java.util.concurrent.CopyOnWriteArraySet

/**
 * CUSTOM #158: event-driven process-wide exclusion between the IP test page and saved-IP speed actions.
 * No timer, polling, or background work is used.
 */
internal object IpSpeedTestCoordinator {
    @Volatile
    var isBusy: Boolean = false
        private set

    private val listeners = CopyOnWriteArraySet<(Boolean) -> Unit>()

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
