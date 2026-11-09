package com.github.kr328.clash.core.custom

import androidx.annotation.Keep
import com.github.kr328.clash.core.bridge.FetchCallback
import java.util.concurrent.atomic.AtomicBoolean

// CUSTOM (CUSTOM.md #7): own JNI entry (cpp/custom_bridge.c) so that the upstream Bridge.kt is untouched.
@Keep
object CustomBridge {
    init {
        System.loadLibrary("bridge")
    }

    external fun nativeSubscribeNodeIp(callback: FetchCallback)
}

// Tells `onChange` (on a core thread) whenever the node IP in use changes. Event driven: the core
// calls back only when it saw a dial to a different node IP; there is no polling.
object NodeIpWatcher {
    private val started = AtomicBoolean(false)

    fun start(onChange: () -> Unit) {
        if (!started.compareAndSet(false, true)) return

        CustomBridge.nativeSubscribeNodeIp(object : FetchCallback {
            override fun report(statusJson: String) {
                onChange()
            }

            override fun complete(error: String?) = Unit
        })
    }
}
