package com.github.kr328.clash.common.custom

import java.util.Locale

object CarrierSwitchMatcher {
    fun match(config: CarrierSwitchConfig, identity: NetworkIdentitySnapshot): CarrierSwitchTarget? {
        val operator = identity.operator.trim().lowercase(Locale.ROOT)
        val raw = identity.rawOperator.trim().lowercase(Locale.ROOT)
        val fixed = when (identity.operator) {
            "电信" -> config.telecom
            "联通" -> config.unicom
            "移动" -> config.mobile
            else -> null
        }
        if (fixed != null && fixed.group.isNotBlank() && fixed.node.isNotBlank()) return fixed

        return config.custom.firstOrNull { item ->
            if (item.group.isBlank() || item.node.isBlank()) return@firstOrNull false
            val keyword = item.keyword.trim().lowercase(Locale.ROOT)
            keyword.isNotEmpty() && (operator.contains(keyword) || raw.contains(keyword))
        }?.let { CarrierSwitchTarget(it.group, it.node) }
    }
    fun needsSwitch(currentNode: String?, targetNode: String): Boolean =
        currentNode != targetNode
}
