package com.github.kr328.clash.custom

import android.content.Context
import android.net.Uri
import com.github.kr328.clash.common.constants.Authorities
import com.github.kr328.clash.service.custom.ServiceUptimeClock

/** changes.md #174. One provider call per home-page fetch(); never called from the 1 s ticker. */
object ServiceUptime {
    /** elapsedRealtime of the service start, or -1 when unknown. Blocking binder call: run it off the main thread. */
    fun startedAt(context: Context): Long {
        return try {
            val uri = Uri.Builder().scheme("content").authority(Authorities.STATUS_PROVIDER).build()
            ServiceUptimeClock.read(
                context.contentResolver.call(uri, ServiceUptimeClock.METHOD_STARTED_AT, null, null)
            )
        } catch (e: Exception) {
            -1L
        }
    }
}
