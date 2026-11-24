package com.github.kr328.clash.common.custom

/** Pure rename application for saved carrier-switch targets. */
object CarrierSwitchRename {
    fun apply(
        config: CarrierSwitchConfig,
        renames: List<Pair<String, String>>,
        definedNames: Set<String>,
    ): CarrierSwitchConfig? {
        val merged = merge(renames)
        if (merged.isEmpty()) return null

        fun renamed(value: String): String {
            val target = merged[value] ?: return value
            return if (value !in definedNames && target in definedNames) target else value
        }

        fun target(value: CarrierSwitchTarget) = value.copy(
            group = renamed(value.group),
            node = renamed(value.node),
        )

        val result = config.copy(
            telecom = target(config.telecom),
            unicom = target(config.unicom),
            mobile = target(config.mobile),
            broadcast = target(config.broadcast),
            custom = config.custom.map { item ->
                item.copy(group = renamed(item.group), node = renamed(item.node))
            },
        )
        return result.takeIf { it != config }
    }

    private fun merge(renames: List<Pair<String, String>>): Map<String, String> {
        val next = LinkedHashMap<String, String>()
        renames.forEach { (old, new) ->
            if (old.isNotEmpty() && new.isNotEmpty()) next[old] = new
        }
        if (next.isEmpty()) return emptyMap()

        val result = LinkedHashMap<String, String>()
        for (start in next.keys) {
            var current = start
            val seen = HashSet<String>()
            seen += start
            while (true) {
                val following = next[current] ?: break
                if (!seen.add(following)) {
                    current = start
                    break
                }
                current = following
            }
            if (current != start) result[start] = current
        }
        return result
    }
}
