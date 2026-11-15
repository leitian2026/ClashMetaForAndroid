package com.github.kr328.clash.service.custom

import android.annotation.SuppressLint
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.github.kr328.clash.common.custom.CustomIntents
import com.github.kr328.clash.core.custom.CustomConnections
import com.github.kr328.clash.core.custom.IssueItem
import com.github.kr328.clash.core.custom.IssuesSnapshot
import com.github.kr328.clash.service.R
import com.github.kr328.clash.service.util.sendBroadcastSelf

// CUSTOM (CUSTOM.md #53): the notification for problems of the running config. Called only when the core says the
// list changed (an event) and when a subscription update ended; there is no timer.
//
// One line per problem, the two newest shown; tapping opens the errors page (its first block lists them). It alerts (sound / heads-up) only when
// a problem appears that was not notified before; other changes update it silently. It is removed when no problem is
// left that is neither ignored nor resolved.
object IssueNotifier {
    private const val CHANNEL = "custom_issues_channel"
    private const val NOTIFICATION_ID = 0x1ECA // fixed: one notification that is updated in place
    private const val PAGE = "com.github.kr328.clash.custom.ErrorsActivity" // the errors page, lives in the app module

    private val notified = HashSet<String>() // ids that already alerted

    // a subscription update ended: [reason] null = it worked
    fun subscription(context: Context, name: String, reason: String?) {
        try {
            val cmd = if (reason == null) "subok|$name" else "sub|$name|${reason.replace('|', '/')}"

            CustomConnections.query(CustomConnections.SORT_ISSUES, cmd)

            update(context)
        } catch (e: Throwable) {
            // the core could not be asked; the update notification of the profile is still shown
        }
    }

    @Synchronized
    fun update(context: Context) {
        val app = context.applicationContext

        val snapshot = try {
            IssuesSnapshot.parse(CustomConnections.query(CustomConnections.SORT_ISSUES, ""))
        } catch (e: Throwable) {
            return
        }

        app.sendBroadcastSelf(Intent(CustomIntents.ACTION_ISSUES_CHANGED)) // the home card

        val manager = NotificationManagerCompat.from(app)
        val active = snapshot.items.filter { !it.ignored && !it.resolved }

        if (active.isEmpty()) {
            notified.clear()
            manager.cancel(NOTIFICATION_ID)
            return
        }

        val fresh = active.any { notified.add(it.id) }

        notified.retainAll(active.map { it.id }.toSet())

        post(app, manager, active, fresh)
    }

    @SuppressLint("MissingPermission") // without the notification permission notify() does nothing
    private fun post(app: Context, manager: NotificationManagerCompat, active: List<IssueItem>, alert: Boolean) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            manager.createNotificationChannel(
                NotificationChannel(CHANNEL, app.getString(R.string.custom_issue_channel), NotificationManager.IMPORTANCE_DEFAULT),
            )
        }

        val lines = active.take(2).map { line(app, it) }
        val title = app.getString(R.string.custom_issue_title, active.size)

        val open = PendingIntent.getActivity(
            app,
            0,
            Intent().setClassName(app, PAGE).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

        val style = NotificationCompat.InboxStyle().setBigContentTitle(title)

        lines.forEach { style.addLine(it) }

        if (active.size > lines.size) style.setSummaryText(app.getString(R.string.custom_issue_more, active.size - lines.size))

        val notification = NotificationCompat.Builder(app, CHANNEL)
            .setSmallIcon(R.drawable.ic_logo_service)
            .setContentTitle(title)
            .setContentText(lines.first())
            .setStyle(style)
            .setContentIntent(open)
            .setAutoCancel(false)
            .setOnlyAlertOnce(!alert)
            .setCategory(NotificationCompat.CATEGORY_STATUS)
            .build()

        manager.notify(NOTIFICATION_ID, notification)
    }

    private fun line(context: Context, item: IssueItem): String {
        val kind = when (item.kind) {
            "doh" -> R.string.custom_issue_doh
            "ip" -> R.string.custom_issue_ip
            "sub" -> R.string.custom_issue_sub
            "ech" -> R.string.custom_issue_ech
            "dns" -> R.string.custom_issue_dns
            "geo" -> R.string.custom_issue_geo
            "provider" -> R.string.custom_issue_provider
            else -> R.string.custom_issue_other
        }

        val subject = if (item.kind == "ip" && item.extra.isNotEmpty()) "${item.extra} (${item.subject})" else item.subject

        return context.getString(kind, subject)
    }
}
