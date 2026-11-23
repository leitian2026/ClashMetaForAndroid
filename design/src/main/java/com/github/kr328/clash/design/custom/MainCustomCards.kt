package com.github.kr328.clash.design.custom

import android.content.Context
import android.content.Intent
import android.view.View
import android.widget.TextView
import com.github.kr328.clash.core.model.TunnelState
import com.github.kr328.clash.common.custom.NetworkIdentitySnapshot
import com.github.kr328.clash.core.custom.IssuesSnapshot
import com.github.kr328.clash.design.R

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

    // CUSTOM #168: this card stays visible even when Clash is stopped or there are no problems.
    fun setIssues(state: IssuesDisplayState) {
        val card = issuesCard ?: return
        val summary = state as? IssuesDisplayState.Summary ?: return
        val active = summary.snapshot?.items.orEmpty().filter { !it.ignored && !it.resolved }
        val identity = summary.identity
        val networkError = identity?.takeIf { it.hasNetworkError }
        val total = active.size + (if (networkError != null) 1 else 0)
        val switchResult = identity?.switchResult.orEmpty()
        val showSwitchEntry = total == 0 && summary.readFailed == null && switchResult.isNotEmpty()
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
                if (switchResult.isNotEmpty()) add(context.getString(R.string.carrier_switch_home_result, switchResult))
                identity?.switchNotice?.takeIf { it.isNotEmpty() }?.let { add(it) }
            }.joinToString("\n")
        } else {
            card.text = if (summary.readFailed != null && total == 0) {
                context.getString(R.string.issues_card_read_failed_title)
            } else {
                context.getString(R.string.network_identity_card_issues, total)
            }
            val lines = ArrayList<String>()
            if (networkError != null) lines += if (networkError.switchError.isNotEmpty()) {
                context.getString(R.string.network_identity_error_switch, networkError.switchError)
            } else {
                NetworkIdentityText.error(context, networkError.error)
            }
            active.take(2 - lines.size).forEach {
                lines += context.getString(IssueText.kindRes(it.kind), IssueText.subject(it))
            }
            if (summary.readFailed != null && lines.isEmpty()) lines += summary.readFailed
            if (active.size + (if (networkError != null) 1 else 0) > lines.size) {
                lines += context.getString(R.string.main_issues_card_more)
            }
            identityLine?.let { lines += it }
            identity?.switchNotice?.takeIf { it.isNotEmpty() }?.let { lines += it }
            card.subtext = lines.joinToString("\n")
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
