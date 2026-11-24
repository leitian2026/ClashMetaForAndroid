package com.github.kr328.clash.common.custom

import android.content.Context
import android.util.AtomicFile
import org.json.JSONObject
import java.io.File
import java.util.UUID

data class UpdateGuardRecord(
    val time: Long,
    val oldCount: Int,
    val newCount: Int,
    val ignored: Boolean = false,
)

/** CUSTOM #173: one latest node-count drop warning per subscription. */
object UpdateGuardStore {
    private val lock = Any()

    private fun file(context: Context, uuid: UUID) = AtomicFile(
        File(context.applicationContext.filesDir, "custom/update_guard/$uuid.json"),
    )

    fun read(context: Context, uuid: UUID): UpdateGuardRecord? = synchronized(lock) {
        try {
            val json = JSONObject(String(file(context, uuid).readFully(), Charsets.UTF_8))
            UpdateGuardRecord(
                time = json.optLong("time", 0L),
                oldCount = json.optInt("oldCount", -1),
                newCount = json.optInt("newCount", -1),
                ignored = json.optBoolean("ignored", false),
            )
        } catch (_: Exception) {
            null
        }
    }

    fun write(context: Context, uuid: UUID, record: UpdateGuardRecord): Boolean = synchronized(lock) {
        val atomic = file(context, uuid)
        var stream: java.io.FileOutputStream? = null
        try {
            val output = atomic.startWrite()
            stream = output
            val json = JSONObject()
                .put("time", record.time)
                .put("oldCount", record.oldCount)
                .put("newCount", record.newCount)
                .put("ignored", record.ignored)
            output.write(json.toString().toByteArray(Charsets.UTF_8))
            atomic.finishWrite(output)
            stream = null
            true
        } catch (_: Exception) {
            if (stream != null) atomic.failWrite(stream)
            false
        }
    }

    fun setIgnored(context: Context, uuid: UUID, ignored: Boolean): Boolean {
        val current = read(context, uuid) ?: return false
        return write(context, uuid, current.copy(ignored = ignored))
    }

    fun delete(context: Context, uuid: UUID) = synchronized(lock) {
        try {
            file(context, uuid).delete()
        } catch (_: Exception) {
        }
    }
}
