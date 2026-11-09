package com.github.kr328.clash.service.custom

import android.content.Context
import android.content.Intent
import com.github.kr328.clash.common.custom.CustomIntents
import com.github.kr328.clash.core.custom.NodeIpWatcher
import com.github.kr328.clash.service.util.sendBroadcastSelf

// CUSTOM (CUSTOM.md #7): subscribes once per process; when the core reports a node IP change the
// notification text is marked stale and a self-broadcast wakes the home page and the notification.
object NodeIpNotifier {
    fun start(context: Context) {
        val app = context.applicationContext

        NodeIpWatcher.start {
            NodeIpText.invalidate()

            app.sendBroadcastSelf(Intent(CustomIntents.ACTION_NODE_IP_CHANGED))
        }
    }
}
