package com.github.kr328.clash.common.custom

import android.content.Context
import android.util.AtomicFile
import org.json.JSONObject
import java.io.File

/** CUSTOM (CUSTOM.md #168): no polling and no multi-process SharedPreferences. */
object NetworkIdentityStore {
    private const val FILE_NAME = "custom_network_identity.json"
    private val lock = Any()
    private fun file(context: Context) = AtomicFile(File(context.applicationContext.filesDir, FILE_NAME))

    fun read(context: Context): NetworkIdentitySnapshot? = synchronized(lock) {
        try {
            val json = JSONObject(String(file(context).readFully(), Charsets.UTF_8))
            NetworkIdentitySnapshot(
                signature = json.optString("signature"),
                operator = json.optString("operator"),
                province = json.optString("province"),
                city = json.optString("city"),
                error = json.optString("error"),
                source = json.optString("source"),
                checkedAt = json.optLong("checkedAt"),
                rawOperator = json.optString("rawOperator", ""),
                switchResult = json.optString("switchResult", ""),
                switchError = json.optString("switchError", ""),
                switchNotice = json.optString("switchNotice", ""),
            )
        } catch (_: Exception) { null }
    }

    fun write(context: Context, value: NetworkIdentitySnapshot) = synchronized(lock) {
        val atomic = file(context)
        var stream: java.io.FileOutputStream? = null
        try {
            val output = atomic.startWrite()
            stream = output
            val json = JSONObject()
                .put("signature", value.signature)
                .put("operator", value.operator)
                .put("province", value.province)
                .put("city", value.city)
                .put("error", value.error)
                .put("source", value.source)
                .put("checkedAt", value.checkedAt)
                .put("rawOperator", value.rawOperator)
                .put("switchResult", value.switchResult)
                .put("switchError", value.switchError)
                .put("switchNotice", value.switchNotice)
            output.write(json.toString().toByteArray(Charsets.UTF_8))
            atomic.finishWrite(output)
            stream = null
        } catch (_: Exception) {
            if (stream != null) atomic.failWrite(stream)
        }
    }
}
