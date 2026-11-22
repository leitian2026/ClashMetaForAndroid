package com.github.kr328.clash.service.custom

import android.content.Context
import android.content.Intent
import com.github.kr328.clash.common.custom.CarrierSwitchConfig
import com.github.kr328.clash.common.custom.CarrierSwitchStore
import com.github.kr328.clash.common.custom.CarrierSwitchTarget
import com.github.kr328.clash.common.custom.CustomIntents
import com.github.kr328.clash.common.custom.NetworkIdentitySnapshot
import com.github.kr328.clash.common.custom.NetworkIdentityStore
import com.github.kr328.clash.core.Clash
import com.github.kr328.clash.service.StatusProvider
import com.github.kr328.clash.service.data.Selection
import com.github.kr328.clash.service.data.SelectionDao
import com.github.kr328.clash.service.store.ServiceStore
import com.github.kr328.clash.service.util.sendBroadcastSelf
import java.util.Locale
import kotlin.concurrent.thread

/** CUSTOM (CUSTOM.md #169): event-driven carrier-to-selector switching. */
object CarrierSwitch {
    fun applyAsync(context: Context, identity: NetworkIdentitySnapshot) {
        thread(name = "carrier-switch") {
            apply(context.applicationContext, identity)
        }
    }

    fun applyCurrent(context: Context) {
        val identity = NetworkIdentityStore.read(context) ?: return
        apply(context.applicationContext, identity)
    }

    private fun apply(context: Context, identity: NetworkIdentitySnapshot) {
        if (identity.signature.isEmpty() || identity.error.isNotEmpty()) return
        if (identity.operator.isBlank()) return
        if (identity.operator == "未知" && identity.rawOperator.isBlank()) return

        val uuid = ServiceStore(context).activeProfile ?: return
        val config = CarrierSwitchStore.read(context, uuid)
        val matched = match(config, identity) ?: return
        val hasIpv6 = identity.signature.contains("ipv6=true")
        val target = if (hasIpv6 && matched.v6Group.isNotBlank() && matched.v6Node.isNotBlank()) {
            CarrierSwitchTarget(matched.v6Group, matched.v6Node)
        } else {
            CarrierSwitchTarget(matched.group, matched.node)
        }
        if (target.group.isBlank() || target.node.isBlank()) return

        val running = StatusProvider.serviceRunning
        val switched = if (running) {
            val success = Clash.patchSelector(target.group, target.node)
            if (success) {
                SelectionDao().setSelected(Selection(uuid, target.group, target.node))
            }
            success
        } else {
            SelectionDao().setSelected(Selection(uuid, target.group, target.node))
            false
        }

        val latest = NetworkIdentityStore.read(context) ?: identity
        if (latest.signature != identity.signature) return
        val updated = when {
            !running -> latest.copy(switchResult = "", switchError = "")
            switched -> latest.copy(
                switchResult = "${target.group} / ${target.node}",
                switchError = "",
            )
            else -> latest.copy(
                switchResult = "",
                switchError = "${target.group} / ${target.node}",
            )
        }
        NetworkIdentityStore.write(context, updated)
        context.sendBroadcastSelf(Intent(CustomIntents.ACTION_ISSUES_CHANGED))
    }

    private fun match(config: CarrierSwitchConfig, identity: NetworkIdentitySnapshot): CarrierSwitchTarget? {
        val operator = identity.operator.lowercase(Locale.ROOT)
        val raw = identity.rawOperator.lowercase(Locale.ROOT)
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
        if (regional != null) return CarrierSwitchTarget(regional.group, regional.node, regional.v6Group, regional.v6Node)
        when (identity.operator) {
            "电信" -> return config.telecom
            "联通" -> return config.unicom
            "移动" -> return config.mobile
        }
        return config.custom.firstOrNull { item ->
            if (item.region.isNotBlank()) return@firstOrNull false
            val keyword = item.keyword.trim().lowercase(Locale.ROOT)
            keyword.isNotEmpty() && (operator.contains(keyword) || raw.contains(keyword))
        }?.let { CarrierSwitchTarget(it.group, it.node, it.v6Group, it.v6Node) }
    }
}
