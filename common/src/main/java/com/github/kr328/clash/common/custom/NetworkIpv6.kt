package com.github.kr328.clash.common.custom

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import androidx.core.content.getSystemService
import java.net.Inet6Address
import java.net.InetAddress

// CUSTOM (CUSTOM.md #23, #24): what the system says about the network the core's own sockets use (the one
// below the VPN). Read on demand (when the errors page is shown, and once after a network change); nothing
// here runs on its own.
object NetworkIpv6 {
    /**
     * Whether the network below the VPN can really use IPv6: a global address (not link-local, not unique-local,
     * not loopback) and an IPv6 default route. Unknown (no network, no permission) counts as "has IPv6", so that
     * a node is never hidden because of a failed check.
     */
    fun hasIpv6(context: Context): Boolean {
        val cm = context.getSystemService<ConnectivityManager>() ?: return true

        return try {
            val network = best(cm) ?: return true
            val properties = cm.getLinkProperties(network) ?: return true

            val global = properties.linkAddresses.any { isGlobal(it.address) }
            val route = properties.routes.any { it.isDefaultRoute && it.gateway is Inet6Address }

            global && route
        } catch (e: Exception) {
            true
        }
    }

    /**
     * A short text that changes when the network below the VPN changes (another network, another kind of
     * network, IPv6 appearing or going away). Addresses are left out on purpose: IPv6 privacy addresses rotate
     * all the time, and that is not a change worth a new speed test. "" when there is no network.
     */
    fun signature(context: Context): String {
        val cm = context.getSystemService<ConnectivityManager>() ?: return ""

        return try {
            val network = best(cm) ?: return ""

            "$network|${transport(cm, network)}|ipv6=${hasIpv6(context)}"
        } catch (e: Exception) {
            ""
        }
    }

    private fun isGlobal(address: InetAddress): Boolean {
        if (address !is Inet6Address) return false
        if (address.isLinkLocalAddress || address.isLoopbackAddress || address.isMulticastAddress || address.isAnyLocalAddress) {
            return false
        }

        // unique-local fc00::/7 is private, not a way out
        return (address.address[0].toInt() and 0xFE) != 0xFC
    }

    // The network the core's sockets use: same preference as the service's NetworkObserveModule (Wi-Fi, then
    // ethernet, then the rest), validated networks first.
    @Suppress("DEPRECATION")
    private fun best(cm: ConnectivityManager): Network? {
        var best: Network? = null
        var bestScore = Int.MAX_VALUE

        for (network in cm.allNetworks) {
            val caps = cm.getNetworkCapabilities(network) ?: continue

            if (!caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)) continue
            if (!caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN)) continue

            val score = (if (caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)) 0 else 100) + score(caps)

            if (score < bestScore) {
                best = network
                bestScore = score
            }
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
