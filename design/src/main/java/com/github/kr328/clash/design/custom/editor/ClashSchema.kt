package com.github.kr328.clash.design.custom.editor

import org.json.JSONArray
import org.json.JSONObject

// CUSTOM (CUSTOM.md #46, not upstream): the part of the mihomo configuration the editor completes against. The data
// (res/raw/clash_schema.json) is FlClash's `lib/features/editor/clash_schema.dart` (GPL-3.0) turned into JSON; a map
// lists its [fields] plus [variants] keyed by the value of its `type`, a map with free keys and a list describe
// their members with [entry].
enum class SchemaKind { Scalar, Map, List }

class SchemaNode(
    val kind: SchemaKind,
    val fields: Map<String, SchemaNode>,
    val variants: Map<String, Map<String, SchemaNode>>,
    val entry: SchemaNode?,
    val values: List<String>,
    /** Where a scalar's candidates come from besides [values]: plain, rule, rulePayload, policy, outbound, proxyProvider, ruleProvider, subRule. */
    val scalar: String,
) {
    /** The fields of a map whose `type` is [type], all variants when unknown. */
    fun fieldsFor(type: String?): Map<String, SchemaNode> {
        if (variants.isEmpty()) return fields

        val out = LinkedHashMap<String, SchemaNode>(fields)
        val variant = if (type == null) null else variants[type]

        if (variant != null) {
            out.putAll(variant)
        } else {
            for (v in variants.values) out.putAll(v)
        }

        return out
    }

    val hint: String
        get() = when (kind) {
            SchemaKind.Map -> "{…}"
            SchemaKind.List -> "[…]"
            SchemaKind.Scalar -> if (values.size > 3) values.take(3).joinToString(" | ") + " | …" else values.joinToString(" | ")
        }
}

class ClashSchema(
    val root: SchemaNode,
    val ruleTypes: Map<String, String>,
    val noResolveRuleTypes: Set<String>,
    val rulePayloadValues: Map<String, List<String>>,
    val ruleOptions: List<String>,
) {
    companion object {
        fun parse(json: String): ClashSchema {
            val data = JSONObject(json)
            val defs = data.getJSONObject("defs")
            val cache = HashMap<String, SchemaNode>()

            fun strings(array: JSONArray?): List<String> {
                if (array == null) return emptyList()

                return List(array.length()) { array.getString(it) }
            }

            fun node(obj: JSONObject): SchemaNode {
                val ref = obj.optString("r")

                if (ref.isNotEmpty()) {
                    cache[ref]?.let { return it }

                    val built = node(defs.getJSONObject(ref))

                    cache[ref] = built

                    return built
                }

                fun fieldsOf(o: JSONObject?): Map<String, SchemaNode> {
                    val out = LinkedHashMap<String, SchemaNode>()

                    if (o != null) {
                        val keys = o.keys()

                        while (keys.hasNext()) {
                            val k = keys.next()

                            out[k] = node(o.getJSONObject(k))
                        }
                    }

                    return out
                }

                val kind = when (obj.getString("k")) {
                    "m" -> SchemaKind.Map
                    "l" -> SchemaKind.List
                    else -> SchemaKind.Scalar
                }

                val variants = LinkedHashMap<String, Map<String, SchemaNode>>()
                val v = obj.optJSONObject("v")

                if (v != null) {
                    val keys = v.keys()

                    while (keys.hasNext()) {
                        val k = keys.next()

                        variants[k] = fieldsOf(v.getJSONObject(k))
                    }
                }

                return SchemaNode(
                    kind = kind,
                    fields = fieldsOf(obj.optJSONObject("f")),
                    variants = variants,
                    entry = obj.optJSONObject("e")?.let { node(it) },
                    values = strings(obj.optJSONArray("x")),
                    scalar = obj.optString("s", "plain"),
                )
            }

            val ruleTypes = LinkedHashMap<String, String>()
            val rt = data.getJSONObject("ruleTypes")
            val rtKeys = rt.keys()

            while (rtKeys.hasNext()) {
                val k = rtKeys.next()

                ruleTypes[k] = rt.getString(k)
            }

            val payloads = LinkedHashMap<String, List<String>>()
            val pl = data.getJSONObject("payloads")
            val plKeys = pl.keys()

            while (plKeys.hasNext()) {
                val k = plKeys.next()

                payloads[k] = strings(pl.getJSONArray(k))
            }

            return ClashSchema(
                root = node(data.getJSONObject("root")),
                ruleTypes = ruleTypes,
                noResolveRuleTypes = strings(data.getJSONArray("noResolve")).toSet(),
                rulePayloadValues = payloads,
                ruleOptions = strings(data.getJSONArray("ruleOptions")),
            )
        }
    }
}
