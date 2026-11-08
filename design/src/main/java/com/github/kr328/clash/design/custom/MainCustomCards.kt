package com.github.kr328.clash.design.custom

import android.content.Context
import android.content.Intent
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import com.github.kr328.clash.common.compat.getDrawableCompat
import com.github.kr328.clash.core.model.TunnelState
import com.github.kr328.clash.design.R
import com.github.kr328.clash.design.util.getPixels
import com.github.kr328.clash.design.view.LargeActionCard

// CUSTOM (CUSTOM.md #6, #7): two cards on the home page, right below the status card.
//  - GeoSite reminder: shown while an app update has replaced the user's downloaded geosite.dat
//    with the built-in one; tapping opens the Meta feature settings (where the download is).
//  - Current node IP: the node IP the core dialed most recently, with the flag of its exit
//    country; tapping refreshes it. Updated when the page is shown or refreshed, never by a timer.
class MainCustomCards(
    private val context: Context,
    container: ViewGroup,
    private val onRefresh: () -> Unit,
) {
    private val geoSiteCard = newCard(R.drawable.ic_baseline_update).apply {
        text = context.getString(R.string.main_geosite_reset_title)
        subtext = context.getString(R.string.main_geosite_reset_hint)
        setOnClickListener { openGeoSiteSettings() }
        visibility = View.GONE
    }

    private val ipCard = newCard(R.drawable.ic_baseline_dns).apply {
        setOnClickListener { onRefresh() }
        visibility = View.GONE
    }

    init {
        // child 0 is the title row, child 1 the status card
        val index = minOf(2, container.childCount)

        container.addView(geoSiteCard, index)
        container.addView(ipCard, index + 1)
    }

    fun update(running: Boolean, state: TunnelState) {
        geoSiteCard.visibility = if (GeoSiteResetNotice.isPending(context)) View.VISIBLE else View.GONE

        if (!running) {
            ipCard.visibility = View.GONE

            return
        }

        ipCard.visibility = View.VISIBLE

        if (state.currentIp.isEmpty()) {
            ipCard.text = context.getString(R.string.main_current_ip_none)
            ipCard.subtext = context.getString(R.string.main_current_ip_refresh)
        } else {
            val flag = CountryFlag.of(state.currentCountry).ifEmpty { "？" }

            ipCard.text = "$flag ${state.currentIp}"
            ipCard.subtext = context.getString(R.string.main_current_ip_node, state.currentNode)
        }
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
