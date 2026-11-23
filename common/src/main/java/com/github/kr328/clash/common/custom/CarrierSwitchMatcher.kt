package com.github.kr328.clash.common.custom

import java.util.Locale

object CarrierSwitchMatcher {
    fun match(config: CarrierSwitchConfig, identity: NetworkIdentitySnapshot): CarrierSwitchTarget? {
        val operator = identity.operator.trim().lowercase(Locale.ROOT)
        val raw = identity.rawOperator.trim().lowercase(Locale.ROOT)
        val province = identity.province.lowercase(Locale.ROOT)
        val city = identity.city.lowercase(Locale.ROOT)
        val regional = config.custom.firstOrNull { item ->
            val region = item.region.trim().lowercase(Locale.ROOT)
            if (region.isEmpty()) return@firstOrNull false
            val regionMatches = province.contains(region) || city.contains(region)
            val keyword = item.keyword.trim().lowercase(Locale.ROOT)
            val keywordMatches = keyword.isEmpty() || operator.contains(keyword) || raw.contains(keyword)
            regionMatches && keywordMatches
        }
        if (regional != null) return CarrierSwitchTarget(regional.group, regional.node)
        when (identity.operator) {
            "电信" -> return config.telecom
            "联通" -> return config.unicom
            "移动" -> return config.mobile
        }
        return config.custom.firstOrNull { item ->
            if (item.region.isNotBlank()) return@firstOrNull false
            val keyword = item.keyword.trim().lowercase(Locale.ROOT)
            keyword.isNotEmpty() && (operator.contains(keyword) || raw.contains(keyword))
        }?.let { CarrierSwitchTarget(it.group, it.node) }
    }
}
