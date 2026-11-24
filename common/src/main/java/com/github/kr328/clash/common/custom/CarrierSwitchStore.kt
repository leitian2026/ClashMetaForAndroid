package com.github.kr328.clash.common.custom

import android.content.Context
import android.util.AtomicFile
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.UUID

/** Per-profile mappings. The app process writes; the service process deletes the file when its profile is deleted. */
object CarrierSwitchStore {
    private val lock = Any()

    private fun file(context: Context, uuid: UUID) = AtomicFile(
        File(context.applicationContext.filesDir, "custom_carrier_switch_$uuid.json")
    )

    fun encode(value: CarrierSwitchConfig): JSONObject = JSONObject().apply {
        fun target(value: CarrierSwitchTarget) = JSONObject()
            .put("group", value.group)
            .put("node", value.node)
        val custom = JSONArray()
        value.custom.forEach {
            custom.put(JSONObject()
                .put("keyword", it.keyword)
                .put("group", it.group)
                .put("node", it.node))
        }
        put("telecom", target(value.telecom))
        put("unicom", target(value.unicom))
        put("mobile", target(value.mobile))
        put("custom", custom)
        put("broadcast", target(value.broadcast))
    }

    fun decode(json: JSONObject): CarrierSwitchConfig {
        fun target(name: String) = json.optJSONObject(name)?.let {
            CarrierSwitchTarget(
                it.optString("group"),
                it.optString("node"),
            )
        } ?: CarrierSwitchTarget()
        val customJson = json.optJSONArray("custom") ?: JSONArray()
        val custom = ArrayList<CarrierSwitchKeyword>(customJson.length())
        for (index in 0 until customJson.length()) {
            val item = customJson.optJSONObject(index) ?: continue
            custom += CarrierSwitchKeyword(
                item.optString("keyword"),
                item.optString("group"),
                item.optString("node"),
            )
        }
        return CarrierSwitchConfig(
            telecom = target("telecom"),
            unicom = target("unicom"),
            mobile = target("mobile"),
            custom = custom,
            broadcast = target("broadcast"),
        )
    }

    fun isEmpty(value: CarrierSwitchConfig): Boolean {
        fun hasTarget(group: String, node: String) = group.isNotBlank() && node.isNotBlank()
        fun hasAnyTarget(target: CarrierSwitchTarget) =
            hasTarget(target.group, target.node)

        val fixedEmpty = listOf(value.telecom, value.unicom, value.mobile, value.broadcast).none(::hasAnyTarget)
        val customEmpty = value.custom.none {
            it.keyword.isNotBlank() &&
                hasTarget(it.group, it.node)
        }
        return fixedEmpty && customEmpty
    }

    fun read(context: Context, uuid: UUID): CarrierSwitchConfig = synchronized(lock) {
        try {
            decode(JSONObject(String(file(context, uuid).readFully(), Charsets.UTF_8)))
        } catch (_: Exception) {
            CarrierSwitchConfig()
        }
    }

    fun delete(context: Context, uuid: UUID) = synchronized(lock) {
        try { file(context, uuid).delete() } catch (_: Exception) { /* Best-effort cleanup. */ }
    }

    fun write(context: Context, uuid: UUID, value: CarrierSwitchConfig): Boolean = synchronized(lock) {
        val atomic = file(context, uuid)
        var stream: java.io.FileOutputStream? = null
        try {
            val output = atomic.startWrite()
            stream = output
            val json = encode(value)
            output.write(json.toString().toByteArray(Charsets.UTF_8))
            atomic.finishWrite(output)
            stream = null
            true
        } catch (_: Exception) {
            if (stream != null) atomic.failWrite(stream)
            false
        }
    }
}
