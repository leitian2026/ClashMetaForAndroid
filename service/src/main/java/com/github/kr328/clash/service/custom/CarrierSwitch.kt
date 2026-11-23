package com.github.kr328.clash.service.custom

import android.content.Context
import android.content.Intent
import com.github.kr328.clash.common.custom.CarrierSwitchConfig
import com.github.kr328.clash.common.custom.CarrierSwitchEnabledStore
import com.github.kr328.clash.common.custom.CarrierSwitchStore
import com.github.kr328.clash.common.custom.CarrierSwitchTogglePolicy
import com.github.kr328.clash.common.custom.CarrierSwitchTarget
import com.github.kr328.clash.common.custom.CustomIntents
import com.github.kr328.clash.common.custom.NetworkIdentitySnapshot
import com.github.kr328.clash.common.custom.NetworkIdentityStore
import com.github.kr328.clash.core.Clash
import com.github.kr328.clash.service.StatusProvider
import com.github.kr328.clash.service.R
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
            val app = context.applicationContext
            if (!CarrierSwitchTogglePolicy.shouldApply(CarrierSwitchEnabledStore.read(app))) return@thread
            apply(app, identity)
        }
    }

    fun applyCurrent(context: Context) {
        val app = context.applicationContext
        if (!CarrierSwitchTogglePolicy.shouldApply(CarrierSwitchEnabledStore.read(app))) return
        val identity = NetworkIdentityStore.read(app) ?: return
        apply(app, identity)
    }

    fun applyAfterProfileLoaded(context: Context) {
        thread(name = "carrier-switch-profile-loaded") {
            val app = context.applicationContext
            if (!CarrierSwitchTogglePolicy.shouldApply(CarrierSwitchEnabledStore.read(app))) return@thread
            val identity = clearSwitchStatus(app) ?: return@thread
            apply(app, identity)
        }
    }

    fun clearSwitchResult(context: Context) {
        val latest = NetworkIdentityStore.read(context) ?: return
        if (latest.switchResult.isEmpty() && latest.switchError.isEmpty()) return
        NetworkIdentityStore.write(context, latest.copy(switchResult = "", switchError = ""))
        context.sendBroadcastSelf(Intent(CustomIntents.ACTION_ISSUES_CHANGED))
    }

    private fun clearSwitchStatus(context: Context): NetworkIdentitySnapshot? {
        val latest = NetworkIdentityStore.read(context) ?: return null
        val cleared = latest.copy(switchResult = "", switchError = "", switchNotice = "")
        if (cleared != latest) {
            NetworkIdentityStore.write(context, cleared)
            context.sendBroadcastSelf(Intent(CustomIntents.ACTION_ISSUES_CHANGED))
        }
        return cleared
    }

    private fun apply(context: Context, identity: NetworkIdentitySnapshot) {
        if (identity.signature.isEmpty() || identity.error.isNotEmpty()) return
        val operator = identity.operator.trim()
        val rawOperator = identity.rawOperator.trim()
        val knownOperator = operator.isNotEmpty() &&
            !operator.equals("未知", ignoreCase = true) && !operator.equals("unknown", ignoreCase = true)
        val knownRawOperator = rawOperator.isNotEmpty() &&
            !rawOperator.equals("未知", ignoreCase = true) && !rawOperator.equals("unknown", ignoreCase = true)
        if (!knownOperator && !knownRawOperator) return

        val uuid = ServiceStore(context).activeProfile ?: return
        val config = CarrierSwitchStore.read(context, uuid)
        val matched = match(config, identity)
        val knownCarrier = operator in setOf("电信", "联通", "移动")
        if (matched == null || matched.group.isBlank() || matched.node.isBlank()) {
            if (knownCarrier) {
                NetworkIdentityStore.write(
                    context,
                    identity.copy(
                        switchResult = "",
                        switchError = "",
                        switchNotice = context.getString(R.string.carrier_switch_missing_target, operator),
                    ),
                )
                context.sendBroadcastSelf(Intent(CustomIntents.ACTION_ISSUES_CHANGED))
            }
            return
        }
        val target = CarrierSwitchTarget(matched.group, matched.node)

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

        val updated = when {
            !running -> identity.copy(switchResult = "", switchError = "", switchNotice = "")
            switched -> identity.copy(
                switchResult = "${target.group} / ${target.node}",
                switchError = "",
                switchNotice = "",
            )
            else -> identity.copy(
                switchResult = "",
                switchError = "${target.group} / ${target.node}",
                switchNotice = "",
            )
        }
        NetworkIdentityStore.write(context, updated)
        context.sendBroadcastSelf(Intent(CustomIntents.ACTION_ISSUES_CHANGED))
    }

    private fun match(config: CarrierSwitchConfig, identity: NetworkIdentitySnapshot): CarrierSwitchTarget? {
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
