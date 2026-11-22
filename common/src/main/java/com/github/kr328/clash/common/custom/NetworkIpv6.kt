package com.github.kr328.clash.common.custom

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import androidx.core.content.getSystemService
import java.net.Inet6Address
import java.net.InetAddress

// CUSTOM (CUSTOM.md #23, #24, #168): information about the network below the VPN.
object NetworkIpv6 {
    fun hasIpv6(context: Context): Boolean {
        val cm = context.getSystemService<ConnectivityManager>() ?: return true
        return try {
            val network = best(cm) ?: return true
            val properties = cm.getLinkProperties(network) ?: return true
            val global = properties.linkAddresses.any { isGlobal(it.address) }
            val route = properties.routes.any { it.isDefaultRoute && it.gateway is Inet6Address }
            global && route
        } catch (e: Exception) { true }
    }

    /** The selected non-VPN Internet network. Callers must bind requests to this Network. */
    fun bestNetwork(context: Context): Network? {
        val cm = context.getSystemService<ConnectivityManager>() ?: return null
        return try { best(cm) } catch (_: Exception) { null }
    }

    fun signature(context: Context): String {
        val cm = context.getSystemService<ConnectivityManager>() ?: return ""
        return try {
            val network = best(cm) ?: return ""
            "$network|${transport(cm, network)}|ipv6=${hasIpv6(context)}"
        } catch (e: Exception) { "" }
    }

    private fun isGlobal(address: InetAddress): Boolean {
        if (address !is Inet6Address) return false
        if (address.isLinkLocalAddress || address.isLoopbackAddress || address.isMulticastAddress || address.isAnyLocalAddress) return false
        return (address.address[0].toInt() and 0xFE) != 0xFC
    }

    @Suppress("DEPRECATION")
    private fun best(cm: ConnectivityManager): Network? {
        var best: Network? = null
        var bestScore = Int.MAX_VALUE
        for (network in cm.allNetworks) {
            val caps = cm.getNetworkCapabilities(network) ?: continue
            if (!caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)) continue
            if (!caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN)) continue
            val score = (if (caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)) 0 else 100) + score(caps)
            if (score < bestScore) { best = network; bestScore = score }
        }
        return best
    }

    private fun score(caps: NetworkCapabilities): Int = when {
        caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> 0
        caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> 1
        caps.hasTransport(NetworkCapabilities.TRANSPORT_BLUETOOTH) -> 3
        caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> 4
        else -> 20
    }

    private fun transport(cm: ConnectivityManager, network: Network): String {
        val caps = cm.getNetworkCapabilities(network) ?: return "?"
        return when {
            caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> "wifi"
            caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> "ethernet"
            caps.hasTransport(NetworkCapabilities.TRANSPORT_BLUETOOTH) -> "bluetooth"
            caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> "cellular"
            else -> "other"
        }
    }
}
