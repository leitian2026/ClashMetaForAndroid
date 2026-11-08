package com.github.kr328.clash.design.custom

import android.content.Context
import android.content.Intent
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import com.github.kr328.clash.common.compat.getDrawableCompat
import com.github.kr328.clash.core.model.TunnelState
import com.github.kr328.clash.design.R
import com.github.kr328.clash.design.util.getPixels
import com.github.kr328.clash.design.view.LargeActionCard

// CUSTOM (CUSTOM.md #6, #7) on the home page:
//  - GeoSite reminder: red text under the app name while an app update has replaced the user's
//    downloaded geosite.dat with the built-in one; tapping it opens the Meta feature settings
//    (where the download is). It disappears once geosite is downloaded again.
//  - Current node IP card (always visible, right below the status card): the IP of the node
//    carrying the traffic with the flag of its exit country; tapping refreshes it. Updated when
//    the page is shown or refreshed, never by a timer.
class MainCustomCards(
    private val context: Context,
    container: ViewGroup,
    private val onRefresh: () -> Unit,
) {
    private val geoSiteNotice: TextView? = container.findViewById<TextView>(R.id.custom_geosite_notice)?.apply {
        setOnClickListener { openGeoSiteSettings() }
    }

    private val ipCard = newCard(R.drawable.ic_baseline_dns).apply {
        setOnClickListener { onRefresh() }
    }

    init {
        // child 0 is the title row, child 1 the status card
        container.addView(ipCard, minOf(2, container.childCount))
    }

    fun update(running: Boolean, state: TunnelState) {
        geoSiteNotice?.visibility = if (GeoSiteResetNotice.isPending(context)) View.VISIBLE else View.GONE

        if (!running || state.currentIp.isEmpty()) {
            ipCard.text = context.getString(R.string.main_current_ip_none)
            ipCard.subtext = context.getString(R.string.main_current_ip_refresh)

            return
        }

        val flag = CountryFlag.of(state.currentCountry).ifEmpty { "？" }

        ipCard.text = "$flag ${state.currentIp}"
        ipCard.subtext = context.getString(R.string.main_current_ip_node, state.currentNode)
    }

    private fun newCard(iconRes: Int): LargeActionCard {
        val margin = context.getPixels(R.dimen.main_card_margin_vertical)

        return LargeActionCard(context).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            ).apply {
                topMargin = margin
                bottomMargin = margin
            }

            icon = context.getDrawableCompat(iconRes)
        }
    }

    private fun openGeoSiteSettings() {
        // the settings activity lives in the app module, so address it by name
        val intent = Intent().setClassName(context, "com.github.kr328.clash.MetaFeatureSettingsActivity")

        context.startActivity(intent)
    }
}
