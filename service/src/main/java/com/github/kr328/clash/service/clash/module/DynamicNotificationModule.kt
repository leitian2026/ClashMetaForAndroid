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
import com.github.kr328.clash.service.custom.NotificationDisplayPolicy
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

    private fun update(showTraffic: Boolean, showIp: Boolean) {
        val trafficText: String?
        val trafficSubText: String?

        if (showTraffic) {
            val now = Clash.queryTrafficNow()
            val total = Clash.queryTrafficTotal()

            trafficText = service.getString(
                R.string.clash_notification_content,
                "${now.trafficUpload()}/s", "${now.trafficDownload()}/s"
            )
            trafficSubText = service.getString(
                R.string.clash_notification_content,
                total.trafficUpload(), total.trafficDownload()
            )
        } else {
            trafficText = null
            trafficSubText = null
        }

        // CUSTOM: do not read tunnel state when the IP line is disabled.
        val ipText = if (showIp) NodeIpText.get(service) else null
        val contentText = trafficText ?: ipText
        val expandedText = if (trafficText != null && ipText != null) {
            "$trafficText\n$ipText"
        } else {
            null
        }

        // CUSTOM #35: avoid notifying Android when every displayed line is unchanged.
        val key = "$contentText\n$trafficSubText\n$expandedText"
        if (key == lastPosted) return

        lastPosted = key

        val notificationBuilder = builder
            .setContentText(contentText)
            .setSubText(trafficSubText)

        if (expandedText != null) {
            notificationBuilder.setStyle(NotificationCompat.BigTextStyle().bigText(expandedText))
        } else {
            notificationBuilder.setStyle(null)
        }

        notificationManager.notify(R.id.nf_clash_status, notificationBuilder.build())
    }

    override suspend fun run() = coroutineScope {
        val store = com.github.kr328.clash.service.store.ServiceStore(service)
        val showTraffic = store.dynamicNotification
        val showIp = store.showIp
        var shouldUpdate = service.getSystemService<PowerManager>()?.isInteractive ?: true

        val screenToggle = receiveBroadcast(false, Channel.CONFLATED) {
            addAction(Intent.ACTION_SCREEN_ON)
            addAction(Intent.ACTION_SCREEN_OFF)
        }

        val profileLoaded = receiveBroadcast(capacity = Channel.CONFLATED) {
            addAction(Intents.ACTION_PROFILE_LOADED)
        }

        // CUSTOM: subscribe to IP events only when the IP line is enabled.
        val nodeIpChanged = if (showIp) {
            receiveBroadcast(capacity = Channel.CONFLATED) {
                addAction(CustomIntents.ACTION_NODE_IP_CHANGED)
            }
        } else {
            null
        }

        if (showIp) NodeIpNotifier.start(service) // CUSTOM: CUSTOM.md #7

        // CUSTOM #35: traffic refreshes every 5 seconds only while enabled and interactive.
        val trafficTicker = if (showTraffic) ticker(TimeUnit.SECONDS.toMillis(5)) else null

        if (!showTraffic && NotificationDisplayPolicy.shouldRefreshIp(showIp, shouldUpdate)) {
            update(showTraffic, showIp)
        }

        while (true) {
            select<Unit> {
                screenToggle.onReceive {
                    when (it.action) {
                        Intent.ACTION_SCREEN_ON -> {
                            shouldUpdate = true
                            lastPosted = null // post once when the screen is on again
                            if (!showTraffic && NotificationDisplayPolicy.shouldRefreshIp(showIp, true)) {
                                update(showTraffic, showIp)
                            }
                        }
                        Intent.ACTION_SCREEN_OFF ->
                            shouldUpdate = false
                    }
                }
                if (nodeIpChanged != null) {
                    nodeIpChanged.onReceive { // CUSTOM #7: core event, never polled
                        if (NotificationDisplayPolicy.shouldRefreshIp(showIp, shouldUpdate)) {
                            update(showTraffic, showIp)
                        }
                    }
                }
                profileLoaded.onReceive {
                    builder.setContentTitle(StatusProvider.currentProfile ?: "Not selected")
                    lastPosted = null // CUSTOM #35: the title changed
                    if (!showTraffic && NotificationDisplayPolicy.shouldRefreshIp(showIp, shouldUpdate)) {
                        update(showTraffic, showIp)
                    }
                }
                if (trafficTicker != null && NotificationDisplayPolicy.shouldRefreshTraffic(showTraffic, shouldUpdate)) {
                    trafficTicker.onReceive {
                        update(showTraffic, showIp)
                    }
                }
            }
        }
    }
}
