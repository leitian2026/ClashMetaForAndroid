package com.github.kr328.clash.common.custom

import android.content.Context
import android.util.AtomicFile
import org.json.JSONObject
import java.io.File

/** Global automatic carrier-switch setting shared by the app and service processes. */
object CarrierSwitchEnabledStore {
    private val lock = Any()

    private fun file(context: Context) = AtomicFile(
        File(context.applicationContext.filesDir, "custom_carrier_switch_enabled.json")
    )

    /** Missing or unreadable settings keep the historical default: enabled. */
    fun read(context: Context): Boolean = synchronized(lock) {
        try {
            JSONObject(String(file(context).readFully(), Charsets.UTF_8)).optBoolean("enabled", true)
        } catch (_: Exception) {
            true
        }
    }

    fun write(context: Context, enabled: Boolean): Boolean = synchronized(lock) {
        val atomic = file(context)
        var stream: java.io.FileOutputStream? = null
        try {
            val output = atomic.startWrite()
            stream = output
            output.write(JSONObject().put("enabled", enabled).toString().toByteArray(Charsets.UTF_8))
            atomic.finishWrite(output)
            stream = null
            true
        } catch (_: Exception) {
            if (stream != null) atomic.failWrite(stream)
            false
        }
    }
}
