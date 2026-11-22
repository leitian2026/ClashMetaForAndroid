package com.github.kr328.clash.custom

import com.github.kr328.clash.BaseActivity
import com.github.kr328.clash.common.custom.CarrierSwitchStore
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
        val groupNames = withClash { queryProxyGroupNames(true) }
        val nodes = LinkedHashMap<String, List<String>>()
        for (group in groupNames) {
            val proxyGroup = withClash { queryProxyGroup(group, ProxySort.Default) }
            if (proxyGroup.type == "Selector") {
                nodes[group] = proxyGroup.proxies.filterNot { it.isGroup }.map { it.name }
            }
        }

        val initial = withContext(Dispatchers.IO) {
            CarrierSwitchStore.read(this@CarrierSwitchActivity, profile.uuid)
        }
        val design = CarrierSwitchDesign(this, initial, nodes.keys.toList(), nodes)
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
                        is CarrierSwitchDesign.Request.Save -> {
                            withContext(Dispatchers.IO) {
                                CarrierSwitchStore.write(this@CarrierSwitchActivity, profile.uuid, it.config)
                            }
                            // The service applies the same matcher used after a fresh network identification.
                            withClash { applyCarrierSwitch() }
                            design.showSaved()
                        }
                    }
                }
            }
        }
    }
}
