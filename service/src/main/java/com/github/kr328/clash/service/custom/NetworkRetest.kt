package com.github.kr328.clash.service.custom

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.ConnectivityManager
import android.net.LinkProperties
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import androidx.core.content.getSystemService
import com.github.kr328.clash.common.compat.registerReceiverCompat
import com.github.kr328.clash.common.custom.CustomIntents
import com.github.kr328.clash.common.custom.NetworkIdentitySnapshot
import com.github.kr328.clash.common.custom.NetworkIdentityStore
import com.github.kr328.clash.common.custom.NetworkIpv6
import com.github.kr328.clash.common.log.Log
import com.github.kr328.clash.core.custom.CustomConnections
import com.github.kr328.clash.service.util.sendBroadcastSelf
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlin.concurrent.thread

// CUSTOM (CUSTOM.md #24, #168): event-driven work after the network below the VPN changes.
// No polling. Network requests are bound to the selected non-VPN Network and are deferred while the screen is off.
object NetworkRetest {
    private const val SETTLE_MS = 4000L

    private val started = AtomicBoolean(false)
    private val identityGeneration = AtomicInteger()

    private lateinit var app: Context
    private val handler = Handler(Looper.getMainLooper())

    // only touched on the main thread
    private var last: String = ""
    private var waitingForScreen = false
    private var pendingIdentitySignature = ""
    private var pendingTest = false

    private val settle = Runnable { onSettled() }

    private val callback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) = changed()
        override fun onLost(network: Network) = changed()
        override fun onLinkPropertiesChanged(network: Network, linkProperties: LinkProperties) = changed()
        override fun onCapabilitiesChanged(network: Network, networkCapabilities: NetworkCapabilities) = Unit
    }

    private val screenOn = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            stopWaitingForScreen()
            val signature = pendingIdentitySignature
            pendingIdentitySignature = ""
            if (signature.isNotEmpty()) identify(signature)
            if (pendingTest) {
                pendingTest = false
                runTest()
            }
        }
    }

    fun start(context: Context) {
        if (!started.compareAndSet(false, true)) return
        app = context.applicationContext

        val cm = app.getSystemService<ConnectivityManager>() ?: return
        last = NetworkIpv6.signature(app)

        val request = NetworkRequest.Builder()
            .addCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN)
            .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .build()

        try {
            cm.registerNetworkCallback(request, callback)
        } catch (e: Exception) {
            Log.w("NetworkRetest register failed", e)
        }

        if (last.isEmpty()) {
            saveNoNetwork()
        } else if (isInteractive()) {
            identify(last)
        } else {
            pendingIdentitySignature = last
            waitForScreen()
        }
    }

    private fun changed() {
        handler.removeCallbacks(settle)
        handler.postDelayed(settle, SETTLE_MS)
    }

    private fun onSettled() {
        val now = NetworkIpv6.signature(app)
        if (now == last) return

        val before = last
        last = now
        Log.i("NetworkRetest network changed: $before -> $now")

        if (now.isEmpty()) {
            pendingIdentitySignature = ""
            pendingTest = false
            identityGeneration.incrementAndGet()
            saveNoNetwork()
            return
        }

        send("changed")
        pendingTest = true

        if (isInteractive()) {
            identify(now)
            pendingTest = false
            runTest()
        } else {
            pendingIdentitySignature = now
            waitForScreen()
        }
    }

    private fun isInteractive(): Boolean = app.getSystemService<PowerManager>()?.isInteractive ?: true

    private fun saveNoNetwork() {
        NetworkIdentityStore.write(
            app,
            NetworkIdentitySnapshot(
                signature = "",
                error = "no_network",
                source = "network_callback",
                checkedAt = System.currentTimeMillis(),
            ),
        )
        app.sendBroadcastSelf(Intent(CustomIntents.ACTION_ISSUES_CHANGED))
        refreshIssueNotification() // CUSTOM #168: refresh operator text without blocking the main thread.
    }

    private fun identify(signature: String) {
        if (signature.isEmpty()) return
        val cached = NetworkIdentityStore.read(app)
        if (cached?.signature == signature) return

        val generation = identityGeneration.incrementAndGet()
        thread(name = "network-identity") {
            val result = try {
                NetworkIdentity.identify(app, signature)
            } catch (e: Exception) {
                Log.w("Network identity lookup failed", e)
                NetworkIdentitySnapshot(signature = signature, error = "all_failed", checkedAt = System.currentTimeMillis())
            }

            handler.post {
                if (generation != identityGeneration.get()) return@post
                if (NetworkIpv6.signature(app) != signature) return@post
                NetworkIdentityStore.write(app, result)
                app.sendBroadcastSelf(Intent(CustomIntents.ACTION_ISSUES_CHANGED))
                refreshIssueNotification() // CUSTOM #168: refresh operator text without blocking the main thread.
            }
        }
    }

    private fun refreshIssueNotification() {
        thread(name = "network-identity-notification") {
            IssueNotifier.update(app)
        }
    }

    private fun waitForScreen() {
        if (waitingForScreen) return
        waitingForScreen = true
        try {
            app.registerReceiverCompat(screenOn, IntentFilter(Intent.ACTION_SCREEN_ON))
        } catch (e: Exception) {
            waitingForScreen = false
        }
    }

    private fun stopWaitingForScreen() {
        if (!waitingForScreen) return
        waitingForScreen = false
        try { app.unregisterReceiver(screenOn) } catch (_: Exception) { }
    }

    private fun runTest() {
        send("run")
    }

    private fun send(mode: String) {
        thread(name = "network-retest") {
            try {
                CustomConnections.query(CustomConnections.SORT_RETEST, mode)
            } catch (e: Exception) {
                Log.w("NetworkRetest $mode failed", e)
            }
        }
    }
}
