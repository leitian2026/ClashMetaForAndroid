package com.github.kr328.clash.service.clash.module

import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.getSystemService
import com.github.kr328.clash.common.compat.getColorCompat
import com.github.kr328.clash.common.compat.pendingIntentFlags
import com.github.kr328.clash.common.constants.Components
import com.github.kr328.clash.common.constants.Intents
import com.github.kr328.clash.common.custom.CustomIntents
import com.github.kr328.clash.common.util.ticker
import com.github.kr328.clash.core.Clash
import com.github.kr328.clash.core.util.trafficDownload
import com.github.kr328.clash.core.util.trafficUpload
import com.github.kr328.clash.service.R
import com.github.kr328.clash.service.StatusProvider
import com.github.kr328.clash.service.custom.NodeIpNotifier
import com.github.kr328.clash.service.custom.NodeIpText
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.selects.select
import java.util.concurrent.TimeUnit

class DynamicNotificationModule(service: Service) : Module<Unit>(service) {
    private val builder = NotificationCompat.Builder(service, StaticNotificationModule.CHANNEL_ID)
        .setSmallIcon(R.drawable.ic_logo_service)
        .setOngoing(true)
        .setColor(service.getColorCompat(R.color.color_clash))
        .setOnlyAlertOnce(true)
        .setShowWhen(false)
        .setContentTitle("Not Selected")
        .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
        .setContentIntent(
            PendingIntent.getActivity(
                service,
                R.id.nf_clash_status,
                Intent().setComponent(Components.MAIN_ACTIVITY)
                    .setFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP),
                pendingIntentFlags(PendingIntent.FLAG_UPDATE_CURRENT)
            )
        )

    private val notificationManager = NotificationManagerCompat.from(service)

    // CUSTOM.md #35: the text of the notification as last posted; null = post at the next update
    private var lastPosted: String? = null

    private fun update() {
        val total = Clash.queryTrafficTotal()

        val uploaded = total.trafficUpload()
        val downloaded = total.trafficDownload()

        val text = NodeIpText.get(service) // CUSTOM: CUSTOM.md #7 (was: upload/download speed)
        val sub = service.getString(
            R.string.clash_notification_content,
            uploaded, downloaded
        )

        // CUSTOM.md #35: every notify() is a call to the system; when the text is the same as the one on
        // screen (no traffic, no new IP) there is nothing to tell it
        val key = "$text\n$sub"

        if (key == lastPosted) return

        lastPosted = key

        val notification = builder
            .setContentText(text)
            .setSubText(sub)
            .build()

        notificationManager.notify(R.id.nf_clash_status, notification)
    }

    override suspend fun run() = coroutineScope {
        var shouldUpdate = service.getSystemService<PowerManager>()?.isInteractive ?: true

        val screenToggle = receiveBroadcast(false, Channel.CONFLATED) {
            addAction(Intent.ACTION_SCREEN_ON)
            addAction(Intent.ACTION_SCREEN_OFF)
        }

        val profileLoaded = receiveBroadcast(capacity = Channel.CONFLATED) {
            addAction(Intents.ACTION_PROFILE_LOADED)
        }

        // CUSTOM: CUSTOM.md #7 - node IP changed (event from the core)
        val nodeIpChanged = receiveBroadcast(capacity = Channel.CONFLATED) {
            addAction(CustomIntents.ACTION_NODE_IP_CHANGED)
        }

        NodeIpNotifier.start(service) // CUSTOM: CUSTOM.md #7

        // CUSTOM.md #35: every 5 seconds (was 1). A new IP does not wait for it: nodeIpChanged below updates at once.
        val ticker = ticker(TimeUnit.SECONDS.toMillis(5))

        while (true) {
            select<Unit> {
                screenToggle.onReceive {
                    when (it.action) {
                        Intent.ACTION_SCREEN_ON -> {
                            shouldUpdate = true
                            lastPosted = null // post once when the screen is on again
                        }
                        Intent.ACTION_SCREEN_OFF ->
                            shouldUpdate = false
                    }
                }
                nodeIpChanged.onReceive { // CUSTOM: CUSTOM.md #7
                    if (shouldUpdate) update()
                }
                profileLoaded.onReceive {
                    builder.setContentTitle(StatusProvider.currentProfile ?: "Not selected")
                    lastPosted = null // CUSTOM.md #35: the title changed, post at the next update even if the text is the same
                }
                if (shouldUpdate) {
                    ticker.onReceive {
                        update()
                    }
                }
            }
        }
    }
}