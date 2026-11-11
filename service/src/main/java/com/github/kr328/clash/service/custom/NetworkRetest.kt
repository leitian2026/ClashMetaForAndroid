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
import com.github.kr328.clash.common.custom.NetworkIpv6
import com.github.kr328.clash.common.log.Log
import com.github.kr328.clash.core.custom.CustomConnections
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread

// CUSTOM (CUSTOM.md #24): after the network below the VPN changed (Wi-Fi <-> mobile data, another Wi-Fi,
// IPv6 appearing or going away) and settled, tell the core so, and run one latency test of every group:
// a speed test made on the old network says nothing about the new one.
//
// Event driven, no timer, no polling: it is the system's network callback that wakes this up. After the last
// event of a burst there is one short one-shot wait (SETTLE_MS) so that a network that is still coming up is
// not measured half-way; then the network is looked at once, and only a real change leads to a test.
//
// Screen off: the test is not run (it would only make the radio work for nothing). The change is still
// recorded, and the test runs once when the screen turns on. Nothing is registered for that until it is needed.
//
// Started once per process by NodeIpNotifier.start() (same idempotent pattern).
object NetworkRetest {
    private const val SETTLE_MS = 4000L

    private val started = AtomicBoolean(false)

    private lateinit var app: Context
    private val handler = Handler(Looper.getMainLooper())

    // only touched on the main thread (the callback, the settle runnable and the receiver all run there)
    private var last: String = ""
    private var waitingForScreen = false

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
            runTest()
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
    }

    private fun changed() {
        // restart the wait: the last event of a burst decides
        handler.removeCallbacks(settle)
        handler.postDelayed(settle, SETTLE_MS)
    }

    private fun onSettled() {
        val now = NetworkIpv6.signature(app)

        if (now == last) return // link properties churn, same network: nothing to redo

        val before = last
        last = now

        Log.i("NetworkRetest network changed: $before -> $now")

        if (now.isEmpty()) return // no network at all: nothing to test, the next network will tell

        send("changed")

        val interactive = app.getSystemService<PowerManager>()?.isInteractive ?: true

        if (interactive) {
            runTest()
        } else {
            waitForScreen()
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

        try {
            app.unregisterReceiver(screenOn)
        } catch (_: Exception) {
        }
    }

    private fun runTest() {
        send("run")
    }

    // The call into the core returns at once, but it is a JNI call into Go: not on the main thread.
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
