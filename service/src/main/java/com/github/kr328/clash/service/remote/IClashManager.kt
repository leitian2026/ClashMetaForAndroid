package com.github.kr328.clash.service.remote

import com.github.kr328.clash.core.Clash
import com.github.kr328.clash.core.model.*
import com.github.kr328.kaidl.BinderInterface

@BinderInterface
interface IClashManager {
    fun queryTunnelState(): TunnelState
    fun queryTrafficTotal(): Long
    fun queryProxyGroupNames(excludeNotSelectable: Boolean): List<String>
    fun queryProxyGroup(name: String, proxySort: ProxySort): ProxyGroup
    fun queryConfiguration(): UiConfiguration
    fun queryProviders(): ProviderList

    fun patchSelector(group: String, name: String): Boolean
    // CUSTOM (CUSTOM.md #169): apply the saved mapping to the cached current network.
    fun applyCarrierSwitch()

    suspend fun healthCheck(group: String)
    suspend fun updateProvider(type: Provider.Type, name: String)

    fun queryOverride(slot: Clash.OverrideSlot): ConfigurationOverride
    fun patchOverride(slot: Clash.OverrideSlot, configuration: ConfigurationOverride)
    fun clearOverride(slot: Clash.OverrideSlot)

    fun setLogObserver(observer: ILogObserver?)

    // CUSTOM (CUSTOM.md #8): active connections (JSON, see ConnectionSnapshot) and closing one
    fun queryCustomConnections(sort: Int, filter: String): String
    // CUSTOM #158: stream download progress through the existing native logcat event interface.
    fun queryCustomConnectionsWithProgress(sort: Int, filter: String, observer: ILogObserver): String
    fun closeCustomConnection(id: String): Boolean
}