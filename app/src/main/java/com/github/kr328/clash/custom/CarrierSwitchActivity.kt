package com.github.kr328.clash.custom

import android.content.Intent
import com.github.kr328.clash.BaseActivity
import com.github.kr328.clash.common.custom.CarrierSwitchEnabledStore
import com.github.kr328.clash.common.custom.CarrierSwitchStore
import com.github.kr328.clash.common.custom.CustomIntents
import com.github.kr328.clash.common.custom.NetworkIdentityStore
import com.github.kr328.clash.design.custom.CarrierSwitchDesign
import com.github.kr328.clash.core.model.ProxySort
import com.github.kr328.clash.util.withClash
import com.github.kr328.clash.util.withProfile
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
        val identityDiagnostic = withContext(Dispatchers.IO) {
            NetworkIdentityStore.read(this@CarrierSwitchActivity)?.diagnostic.orEmpty()
        }
        var automaticEnabled = withContext(Dispatchers.IO) {
            CarrierSwitchEnabledStore.read(this@CarrierSwitchActivity)
        }
        val design = CarrierSwitchDesign(this, initial, groupNames, automaticEnabled, identityDiagnostic)
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
}
