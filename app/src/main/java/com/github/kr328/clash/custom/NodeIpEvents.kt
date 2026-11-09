package com.github.kr328.clash.custom

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import androidx.activity.ComponentActivity
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import com.github.kr328.clash.common.compat.registerReceiverCompat
import com.github.kr328.clash.common.constants.Permissions
import com.github.kr328.clash.common.custom.CustomIntents
import kotlinx.coroutines.channels.Channel

// CUSTOM (CUSTOM.md #7): one value arrives on the returned channel whenever the service reports that
// the node IP in use changed. The receiver is registered only while the activity is started
// (the normal fetch on ActivityStart covers what was missed while it was stopped).
fun ComponentActivity.customNodeIpEvents(): Channel<Unit> {
    val channel = Channel<Unit>(Channel.CONFLATED)

    val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            channel.trySend(Unit)
        }
    }

    val activity = this

    lifecycle.addObserver(object : DefaultLifecycleObserver {
        override fun onStart(owner: LifecycleOwner) {
            activity.registerReceiverCompat(
                receiver,
                IntentFilter(CustomIntents.ACTION_NODE_IP_CHANGED),
                Permissions.RECEIVE_SELF_BROADCASTS,
                null,
            )
        }

        override fun onStop(owner: LifecycleOwner) {
            try {
                activity.unregisterReceiver(receiver)
            } catch (e: IllegalArgumentException) {
                // not registered
            }
        }
    })

    return channel
}
