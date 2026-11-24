package com.github.kr328.clash.design.custom

import android.content.Context
import android.content.Intent
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.ForegroundColorSpan
import android.view.View
import android.widget.TextView
import com.github.kr328.clash.core.model.TunnelState
import com.github.kr328.clash.common.custom.CarrierSwitchEnabledStore
import com.github.kr328.clash.common.custom.UpdateGuardRecord
import com.github.kr328.clash.common.custom.UpdateGuardStore
import java.util.UUID
import com.github.kr328.clash.common.custom.NetworkIdentitySnapshot
import com.github.kr328.clash.core.custom.IssuesSnapshot
import com.github.kr328.clash.design.R
import androidx.core.content.ContextCompat

sealed class IssuesDisplayState {
    data class Summary(
        val running: Boolean,
        val snapshot: IssuesSnapshot?,
        val identity: NetworkIdentitySnapshot?,
        val readFailed: String? = null,
    ) : IssuesDisplayState()
}

// CUSTOM (CUSTOM.md #6, #7) on the home page:
//  - GeoSite reminder: red text under the app name while an app update has replaced the user's
//    downloaded geosite.dat with the built-in one; tapping it opens the Meta feature settings
//    (where the download is). It disappears once geosite is downloaded again.
//  - Current node IP: update() returns the text appended to the subtext of the "Proxy" card
//    (" · 🇺🇸 1.2.3.4 · 🇯🇵 5.6.7.8": every address in use). Refreshed with every fetch and whenever the core reports that the node
//    IP in use changed (event, see CUSTOM.md #7). No timer, no polling.
class MainCustomCards(
    private val context: Context,
    root: View,
) {
    private val geoSiteNotice: TextView? = root.findViewById<TextView>(R.id.custom_geosite_notice)?.apply {
        setOnClickListener { openGeoSiteSettings() }
    }

    private val issuesCard: com.github.kr328.clash.design.view.LargeActionCard? =
        root.findViewById(R.id.custom_issues_card)

    // CUSTOM: read per-subscription warning files only when a file's modification time changes.
    private var updateGuardSignature: Long? = null
    private var cachedUpdateGuard: UpdateGuardRecord? = null

    private fun updateGuard(): UpdateGuardRecord? {
        val directory = context.filesDir.resolve("custom/update_guard")
        val files = directory.listFiles()?.filter { it.isFile && it.extension == "json" }?.sortedBy { it.name }.orEmpty()
        var signature = files.size.toLong()
        for (file in files) {
            signature = signature * 31L + file.name.hashCode()
            signature = signature * 31L + file.lastModified()
            signature = signature * 31L + file.length()
        }
        if (updateGuardSignature == signature) return cachedUpdateGuard

        updateGuardSignature = signature
        cachedUpdateGuard = files.mapNotNull { file ->
            val uuid = try { UUID.fromString(file.nameWithoutExtension) } catch (_: Exception) { null }
                ?: return@mapNotNull null
            UpdateGuardStore.read(context, uuid)?.takeIf { !it.ignored }
        }.maxByOrNull { it.time }
        return cachedUpdateGuard
    }

    // CUSTOM #168: this card stays visible even when Clash is stopped or there are no problems.
    fun setIssues(state: IssuesDisplayState) {
        val card = issuesCard ?: return
        val summary = state as? IssuesDisplayState.Summary ?: return
        val active = summary.snapshot?.items.orEmpty().filter { !it.ignored && !it.resolved }
        val updateGuard = updateGuard()
        val identity = summary.identity
        val networkError = identity?.takeIf { it.hasNetworkError }
        val total = active.size + (if (networkError != null) 1 else 0) + (if (updateGuard != null) 1 else 0)
        val switchResult = identity?.switchResult.orEmpty()
        val automaticEnabled = CarrierSwitchEnabledStore.read(context)
        val onlyMissingSwitchTarget = active.isEmpty() &&
            summary.readFailed == null &&
            identity?.error.isNullOrEmpty() &&
            identity?.switchError.isNullOrEmpty() &&
            identity?.switchMissing?.isNotEmpty() == true
        val showSwitchEntry = updateGuard == null && (
            !automaticEnabled ||
                (total == 0 && summary.readFailed == null && switchResult.isNotEmpty()) ||
                onlyMissingSwitchTarget
            )
        card.setOnClickListener {
            val target = if (showSwitchEntry) {
                "com.github.kr328.clash.custom.CarrierSwitchActivity"
            } else {
                "com.github.kr328.clash.custom.ErrorsActivity"
            }
            context.startActivity(Intent().setClassName(context, target))
        }
        val identityLine = identity?.takeIf { it.hasIdentity }?.let {
            context.getString(R.string.network_identity_current, NetworkIdentityText.location(it))
        }

        if (total == 0 && summary.readFailed == null) {
            card.text = context.getString(R.string.network_identity_card_current)
            val location = identity?.takeIf { it.hasIdentity }?.let { NetworkIdentityText.location(it) }
                ?: context.getString(if (identity == null) R.string.network_identity_pending else R.string.network_identity_unknown)
            card.subtext = buildList {
                add(location)
                if (!automaticEnabled) {
                    add(context.getString(R.string.carrier_switch_home_disabled))
                } else {
                    if (switchResult.isNotEmpty()) add(context.getString(R.string.carrier_switch_home_result, switchResult))
                    identity?.switchNotice?.takeIf { it.isNotEmpty() }?.let { add(it) }
                }
            }.joinToString("\n")
        } else {
            card.text = if (summary.readFailed != null && total == 0) {
                context.getString(R.string.issues_card_read_failed_title)
            } else {
                context.getString(R.string.network_identity_card_issues, total)
            }
            val lines = ArrayList<String>()
            var missingLineIndex = -1
            updateGuard?.let {
                lines += context.getString(R.string.update_guard_summary, it.oldCount, it.newCount)
            }
            if (networkError != null) {
                if (networkError.switchError.isNotEmpty()) {
                    lines += context.getString(R.string.network_identity_error_switch, networkError.switchError)
                } else if (networkError.error.isNotEmpty()) {
                    lines += NetworkIdentityText.error(context, networkError.error)
                }
                networkError.switchMissing.takeIf { it.isNotEmpty() }?.let {
                    missingLineIndex = lines.size
                    lines += it
                }
            }
            active.take((2 - lines.size).coerceAtLeast(0)).forEach {
                lines += context.getString(IssueText.kindRes(it.kind), IssueText.subject(it))
            }
            if (summary.readFailed != null && lines.isEmpty()) lines += summary.readFailed
            if (total > lines.size) {
                lines += context.getString(R.string.main_issues_card_more)
            }
            identityLine?.let { lines += it }
            if (automaticEnabled) identity?.switchNotice?.takeIf { it.isNotEmpty() }?.let { lines += it }
            val subtext = SpannableStringBuilder()
            lines.forEachIndexed { index, line ->
                if (index > 0) subtext.append('\n')
                val lineStart = subtext.length
                subtext.append(line)
                if (index == missingLineIndex) {
                    val colonIndex = line.indexOfAny(charArrayOf('：', ':'))
                    val spanEnd = lineStart + if (colonIndex >= 0) colonIndex + 1 else line.length
                    if (spanEnd > lineStart) {
                        subtext.setSpan(
                            ForegroundColorSpan(ContextCompat.getColor(context, R.color.custom_notice_red)),
                            lineStart,
                            spanEnd,
                            Spanned.SPAN_EXCLUSIVE_EXCLUSIVE,
                        )
                    }
                }
            }
            card.subtext = subtext
        }
        card.visibility = View.VISIBLE
    }

    fun update(running: Boolean, state: TunnelState): String? {
        geoSiteNotice?.visibility = if (GeoSiteResetNotice.isPending(context)) View.VISIBLE else View.GONE

        if (!running) return null

        // every address the requests went through most recently (CUSTOM.md #7)
        val text = state.ipsText { context.getString(R.string.custom_ips_more, it) }

        // does the node in use work (CUSTOM.md #37); nothing is written while that is not known yet
        val age = if (state.reachAt > 0) context.getString(R.string.custom_reach_age, AgeText.reachAgo(context, state.reachAt)) else ""
        val reach = when {
            state.reach > 0 -> context.getString(R.string.custom_reach_ok, state.reach) + age
            state.reach < 0 -> {
                val failure = when {
                    state.reachReason == "timeout" -> context.getString(R.string.custom_reach_fail_timeout)
                    state.reachReason.startsWith("http:") -> context.getString(R.string.custom_reach_fail_http, state.reachReason.substringAfter(':').toIntOrNull() ?: 0)
                    else -> context.getString(R.string.custom_reach_fail_connect)
                }
                failure + age
            }
            else -> ""
        }

        val parts = listOf(text, reach).filter { it.isNotEmpty() }

        return if (parts.isEmpty()) null else " · " + parts.joinToString(" · ")
    }

    private fun openGeoSiteSettings() {
        // the settings activity lives in the app module, so address it by name
        val intent = Intent().setClassName(context, "com.github.kr328.clash.MetaFeatureSettingsActivity")

        context.startActivity(intent)
    }
}
