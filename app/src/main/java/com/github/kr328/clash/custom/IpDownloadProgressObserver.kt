package com.github.kr328.clash.custom

import com.github.kr328.clash.core.model.LogMessage
import com.github.kr328.clash.service.remote.ILogObserver

/** CUSTOM #158: decode the two numeric values carried by the existing native logcat event channel. */
internal object IpDownloadProgressObserver {
    private const val PREFIX = "[APP] IP_DOWNLOAD_PROGRESS|"

    fun create(onProgress: (bytes: Long, elapsedMs: Long) -> Unit): ILogObserver =
        object : ILogObserver {
            override fun newItem(log: LogMessage) {
                val message = log.message
                if (!message.startsWith(PREFIX)) return
                val values = message.substring(PREFIX.length).split('|', limit = 2)
                if (values.size != 2) return
                val bytes = values[0].toLongOrNull() ?: return
                val elapsedMs = values[1].toLongOrNull() ?: return
                onProgress(bytes, elapsedMs)
            }
        }
}
