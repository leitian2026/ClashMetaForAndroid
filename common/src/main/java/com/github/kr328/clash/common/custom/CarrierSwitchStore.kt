package com.github.kr328.clash.common.custom

import android.content.Context
import android.util.AtomicFile
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.UUID

data class CarrierSwitchTarget(
    val group: String = "",
    val node: String = "",
)

data class CarrierSwitchKeyword(
    val keyword: String,
    val group: String,
    val node: String,
)

data class CarrierSwitchConfig(
    val telecom: CarrierSwitchTarget = CarrierSwitchTarget(),
    val unicom: CarrierSwitchTarget = CarrierSwitchTarget(),
    val mobile: CarrierSwitchTarget = CarrierSwitchTarget(),
    val custom: List<CarrierSwitchKeyword> = emptyList(),
)

/** Per-profile mappings. The app process is the only writer; the service only reads on a switch trigger. */
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
    }

    fun decode(json: JSONObject): CarrierSwitchConfig {
        fun target(name: String) = json.optJSONObject(name)?.let {
            CarrierSwitchTarget(it.optString("group"), it.optString("node"))
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
        return CarrierSwitchConfig(target("telecom"), target("unicom"), target("mobile"), custom)
    }

    fun isEmpty(value: CarrierSwitchConfig): Boolean =
        listOf(value.telecom, value.unicom, value.mobile).all { it.group.isBlank() || it.node.isBlank() } &&
            value.custom.none { it.keyword.isNotBlank() && it.group.isNotBlank() && it.node.isNotBlank() }

    fun read(context: Context, uuid: UUID): CarrierSwitchConfig = synchronized(lock) {
        try {
            decode(JSONObject(String(file(context, uuid).readFully(), Charsets.UTF_8)))
        } catch (_: Exception) {
            CarrierSwitchConfig()
        }
    }

    fun write(context: Context, uuid: UUID, value: CarrierSwitchConfig) = synchronized(lock) {
        val atomic = file(context, uuid)
        var stream: java.io.FileOutputStream? = null
        try {
            val output = atomic.startWrite()
            stream = output
            val json = encode(value)
            output.write(json.toString().toByteArray(Charsets.UTF_8))
            atomic.finishWrite(output)
            stream = null
        } catch (_: Exception) {
            if (stream != null) atomic.failWrite(stream)
        }
    }
}
