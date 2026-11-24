package com.github.kr328.clash.custom

import android.content.Intent
import com.github.kr328.clash.BaseActivity
import com.github.kr328.clash.common.custom.CarrierSwitchEnabledStore
import com.github.kr328.clash.common.custom.CarrierSwitchConfig
import com.github.kr328.clash.common.custom.CarrierSwitchStore
import com.github.kr328.clash.common.custom.CarrierSwitchTarget
import com.github.kr328.clash.common.custom.CarrierSwitchTargetCheck
import com.github.kr328.clash.common.custom.CarrierSwitchTargetState
import com.github.kr328.clash.common.custom.CustomIntents
import com.github.kr328.clash.common.custom.NetworkIdentityStore
import com.github.kr328.clash.design.custom.CarrierSwitchDesign
import com.github.kr328.clash.core.model.ProxySort
import com.github.kr328.clash.util.withClash
import com.github.kr328.clash.util.withProfile
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.isActive
import kotlinx.coroutines.selects.select
import kotlinx.coroutines.withContext

/** CUSTOM (CUSTOM.md #169): configure carrier mappings for the currently active profile. */
class CarrierSwitchActivity : BaseActivity<CarrierSwitchDesign>() {
    override suspend fun main() {
        val profile = withProfile { queryActive() } ?: return finish()
        val groupNames = try {
            withClash { queryProxyGroupNames(true) }
        } catch (_: Exception) {
            emptyList()
        }

        val initial = withContext(Dispatchers.IO) {
            CarrierSwitchStore.read(this@CarrierSwitchActivity, profile.uuid)
        }
        val initialMissingTargets = findMissingTargets(initial)
        val identityDiagnostic = withContext(Dispatchers.IO) {
            NetworkIdentityStore.read(this@CarrierSwitchActivity)?.diagnostic.orEmpty()
        }
        var automaticEnabled = withContext(Dispatchers.IO) {
            CarrierSwitchEnabledStore.read(this@CarrierSwitchActivity)
        }
        val design = CarrierSwitchDesign(
            this, initial, groupNames, automaticEnabled, identityDiagnostic, initialMissingTargets,
        )
        setContentDesign(design)

        while (isActive) {
            select<Unit> {
                events.onReceive {
                    when (it) {
                        Event.ServiceRecreated -> finish()
                        else -> Unit
                    }
                }
                design.requests.onReceive {
                    when (it) {
                        is CarrierSwitchDesign.Request.SetEnabled -> {
                            if (it.enabled == automaticEnabled) return@onReceive
                            val saved = withContext(Dispatchers.IO) {
                                CarrierSwitchEnabledStore.write(this@CarrierSwitchActivity, it.enabled)
                            }
                            if (saved) {
                                automaticEnabled = it.enabled
                                design.setAutomaticEnabled(automaticEnabled)
                                sendBroadcast(Intent(CustomIntents.ACTION_ISSUES_CHANGED).setPackage(packageName))
                                if (automaticEnabled) {
                                    withClash { applyCarrierSwitch() }
                                }
                            } else {
                                design.setAutomaticEnabled(automaticEnabled)
                                design.showEnableSaveFailed()
                            }
                        }
                        is CarrierSwitchDesign.Request.LoadNodes -> {
                            val nodes = try {
                                withClash {
                                    val group = queryProxyGroup(it.group, ProxySort.Default)
                                    if (group.type == "Selector") {
                                        group.proxies.filterNot { proxy -> proxy.isGroup }.map { proxy -> proxy.name }
                                    } else {
                                        emptyList()
                                    }
                                }
                            } catch (_: Exception) {
                                emptyList()
                            }
                            design.showNodes(it.group, nodes, it.selected)
                        }
                        is CarrierSwitchDesign.Request.Save -> {
                            val saved = withContext(Dispatchers.IO) {
                                CarrierSwitchStore.write(this@CarrierSwitchActivity, profile.uuid, it.config)
                            }
                            if (saved) {
                                // The service applies the same matcher used after a fresh network identification.
                                withClash { applyCarrierSwitch() }
                                design.setMissingTargets(findMissingTargets(it.config))
                                design.showSaved()
                            } else {
                                design.showSaveFailed()
                            }
                        }
                    }
                }
            }
        }
    }

    private suspend fun findMissingTargets(config: CarrierSwitchConfig): Set<String> {
        val targets = buildList {
            add("telecom" to config.telecom)
            add("unicom" to config.unicom)
            add("mobile" to config.mobile)
            add("broadcast" to config.broadcast)
            config.custom.forEachIndexed { index, item ->
                add("custom:$index" to CarrierSwitchTarget(item.group, item.node))
            }
        }.filter { (_, target) -> target.group.isNotBlank() && target.node.isNotBlank() }

        if (targets.isEmpty()) return emptySet()

        return try {
            withClash {
                val groups = mutableMapOf<String, Triple<String, List<String>, String>?>()
                targets.forEach { (_, target) ->
                    if (!groups.containsKey(target.group)) {
                        groups[target.group] = try {
                            val group = queryProxyGroup(target.group, ProxySort.Default)
                            Triple(group.type, group.proxies.map { it.name }, group.now)
                        } catch (e: CancellationException) {
                            throw e
                        } catch (_: Exception) {
                            null
                        }
                    }
                }
                targets.mapNotNull { (key, target) ->
                    val snapshot = groups[target.group] ?: return@mapNotNull null
                    val state = CarrierSwitchTargetCheck.check(
                        snapshot.first,
                        snapshot.second,
                        snapshot.third,
                        target.node,
                    )
                    if (state == CarrierSwitchTargetState.GroupMissing ||
                        state == CarrierSwitchTargetState.NodeMissing
                    ) key else null
                }.toSet()
            }
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            emptySet()
        }
    }
}
