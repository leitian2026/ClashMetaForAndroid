package com.github.kr328.clash.service.custom

import android.content.Context
import android.content.Intent
import com.github.kr328.clash.common.custom.CarrierSwitchConfig
import com.github.kr328.clash.common.custom.CarrierSwitchEnabledStore
import com.github.kr328.clash.common.custom.CarrierSwitchMatcher
import com.github.kr328.clash.common.custom.CarrierSwitchStore
import com.github.kr328.clash.common.custom.CarrierSwitchTogglePolicy
import com.github.kr328.clash.common.custom.CarrierSwitchTarget
import com.github.kr328.clash.common.custom.CustomIntents
import com.github.kr328.clash.common.custom.NetworkIdentitySnapshot
import com.github.kr328.clash.common.custom.NetworkIdentityStore
import com.github.kr328.clash.common.custom.NetworkIpv6
import com.github.kr328.clash.core.Clash
import com.github.kr328.clash.service.StatusProvider
import com.github.kr328.clash.service.R
import com.github.kr328.clash.service.data.Selection
import com.github.kr328.clash.service.data.SelectionDao
import com.github.kr328.clash.service.store.ServiceStore
import com.github.kr328.clash.service.util.sendBroadcastSelf
import kotlin.concurrent.thread

/** CUSTOM (CUSTOM.md #169): event-driven carrier-to-selector switching. */
object CarrierSwitch {
    private val applyLock = Any()

    fun applyAsync(context: Context, identity: NetworkIdentitySnapshot) {
        thread(name = "carrier-switch") {
            val app = context.applicationContext
            synchronized(applyLock) {
                if (!CarrierSwitchTogglePolicy.shouldApply(CarrierSwitchEnabledStore.read(app))) return@synchronized
                val latest = NetworkIdentityStore.read(app) ?: return@synchronized
                if (!isCurrentIdentity(app, identity.signature) || latest.signature != identity.signature) return@synchronized
                apply(app, latest)
            }
        }
    }

    fun applyCurrent(context: Context) {
        val app = context.applicationContext
        synchronized(applyLock) {
            if (!CarrierSwitchTogglePolicy.shouldApply(CarrierSwitchEnabledStore.read(app))) return
            val identity = NetworkIdentityStore.read(app) ?: return
            if (!isCurrentIdentity(app, identity.signature)) return
            apply(app, identity)
        }
    }

    fun applyAfterProfileLoaded(context: Context) {
        thread(name = "carrier-switch-profile-loaded") {
            val app = context.applicationContext
            synchronized(applyLock) {
                if (!CarrierSwitchTogglePolicy.shouldApply(CarrierSwitchEnabledStore.read(app))) return@synchronized
                val latest = NetworkIdentityStore.read(app) ?: return@synchronized
                if (!isCurrentIdentity(app, latest.signature)) return@synchronized
                val identity = clearSwitchStatus(app, latest) ?: return@synchronized
                apply(app, identity)
            }
        }
    }

    fun clearSwitchResult(context: Context) {
        val latest = NetworkIdentityStore.read(context) ?: return
        if (latest.switchResult.isEmpty() && latest.switchError.isEmpty()) return
        if (NetworkIdentityStore.writeIfSignature(
                context,
                latest.signature,
                latest.copy(switchResult = "", switchError = ""),
            )
        ) {
            context.sendBroadcastSelf(Intent(CustomIntents.ACTION_ISSUES_CHANGED))
        }
    }

    private fun isCurrentIdentity(context: Context, signature: String): Boolean {
        if (signature.isEmpty()) return false
        val latest = NetworkIdentityStore.read(context) ?: return false
        return latest.signature == signature && NetworkIpv6.signature(context) == signature
    }

    private fun clearSwitchStatus(
        context: Context,
        latest: NetworkIdentitySnapshot,
    ): NetworkIdentitySnapshot? {
        val cleared = latest.copy(switchResult = "", switchError = "", switchNotice = "")
        if (cleared != latest) {
            if (!NetworkIdentityStore.writeIfSignature(context, latest.signature, cleared)) return null
            context.sendBroadcastSelf(Intent(CustomIntents.ACTION_ISSUES_CHANGED))
        }
        return cleared
    }

    private fun apply(context: Context, identity: NetworkIdentitySnapshot) {
        if (!isCurrentIdentity(context, identity.signature) || identity.error.isNotEmpty()) return
        val operator = identity.operator.trim()
        val rawOperator = identity.rawOperator.trim()
        val knownOperator = operator.isNotEmpty() &&
            !operator.equals("未知", ignoreCase = true) && !operator.equals("unknown", ignoreCase = true)
        val knownRawOperator = rawOperator.isNotEmpty() &&
            !rawOperator.equals("未知", ignoreCase = true) && !rawOperator.equals("unknown", ignoreCase = true)
        if (!knownOperator && !knownRawOperator) return

        val uuid = ServiceStore(context).activeProfile ?: return
        val config = CarrierSwitchStore.read(context, uuid)
        val matched = CarrierSwitchMatcher.match(config, identity)
        val knownCarrier = operator in setOf("电信", "联通", "移动")
        if (matched == null || matched.group.isBlank() || matched.node.isBlank()) {
            if (knownCarrier && NetworkIdentityStore.writeIfSignature(
                    context,
                    identity.signature,
                    identity.copy(
                        switchResult = "",
                        switchError = "",
                        switchNotice = context.getString(R.string.carrier_switch_missing_target, operator),
                    ),
                )
            ) {
                context.sendBroadcastSelf(Intent(CustomIntents.ACTION_ISSUES_CHANGED))
            }
            return
        }
        val target = CarrierSwitchTarget(matched.group, matched.node)

        if (!isCurrentIdentity(context, identity.signature)) return
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
        if (NetworkIdentityStore.writeIfSignature(context, identity.signature, updated)) {
            context.sendBroadcastSelf(Intent(CustomIntents.ACTION_ISSUES_CHANGED))
        }
    }
}
