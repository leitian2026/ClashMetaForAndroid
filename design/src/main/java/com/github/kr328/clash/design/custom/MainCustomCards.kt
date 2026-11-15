package com.github.kr328.clash.design.custom

import android.content.Context
import android.content.Intent
import android.view.View
import android.widget.TextView
import com.github.kr328.clash.core.model.TunnelState
import com.github.kr328.clash.design.R

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
        root.findViewById<com.github.kr328.clash.design.view.LargeActionCard>(R.id.custom_issues_card)?.apply {
            // the page lives in the app module, so address it by name
            setOnClickListener {
                context.startActivity(Intent().setClassName(context, "com.github.kr328.clash.custom.ErrorsActivity"))
            }
        }

    // CUSTOM.md #53: one line per problem; what does not fit is behind the tap (the list page)
    fun setIssues(snapshot: com.github.kr328.clash.core.custom.IssuesSnapshot?) {
        val card = issuesCard ?: return
        val active = snapshot?.items.orEmpty().filter { !it.ignored && !it.resolved }

        if (active.isEmpty()) {
            card.visibility = View.GONE
            return
        }

        card.text = context.getString(R.string.issues_card_title, active.size)

        val lines = active.take(2).map { context.getString(IssueText.kindRes(it.kind), IssueText.subject(it)) }
        val more = if (active.size > lines.size) listOf(context.getString(R.string.main_issues_card_more)) else emptyList()

        card.subtext = (lines + more).joinToString("\n")
        card.visibility = View.VISIBLE
    }

    fun update(running: Boolean, state: TunnelState): String? {
        geoSiteNotice?.visibility = if (GeoSiteResetNotice.isPending(context)) View.VISIBLE else View.GONE

        if (!running) return null

        // every address the requests went through most recently (CUSTOM.md #7)
        val text = state.ipsText { context.getString(R.string.custom_ips_more, it) }

        // does the node in use work (CUSTOM.md #37); nothing is written while that is not known yet
        val reach = when {
            state.reach > 0 -> context.getString(R.string.custom_reach_ok, state.reach)
            state.reach < 0 -> context.getString(R.string.custom_reach_fail)
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
