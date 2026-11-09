package com.github.kr328.clash.design.custom

import android.content.Context
import android.content.Intent
import android.view.View
import android.widget.TextView
import com.github.kr328.clash.core.model.TunnelState
import com.github.kr328.clash.core.util.CountryFlag
import com.github.kr328.clash.design.R

// CUSTOM (CUSTOM.md #6, #7) on the home page:
//  - GeoSite reminder: red text under the app name while an app update has replaced the user's
//    downloaded geosite.dat with the built-in one; tapping it opens the Meta feature settings
//    (where the download is). It disappears once geosite is downloaded again.
//  - Current node IP: update() returns the text appended to the subtext of the "Proxy" card
//    (" · 🇺🇸 1.2.3.4"). Refreshed with every fetch and, while the page is open and Clash is
//    running, by the page's existing 1-second ticker (every 3rd tick). No timer of our own.
class MainCustomCards(
    private val context: Context,
    root: View,
) {
    private val geoSiteNotice: TextView? = root.findViewById<TextView>(R.id.custom_geosite_notice)?.apply {
        setOnClickListener { openGeoSiteSettings() }
    }

    fun update(running: Boolean, state: TunnelState): String? {
        geoSiteNotice?.visibility = if (GeoSiteResetNotice.isPending(context)) View.VISIBLE else View.GONE

        if (!running || state.currentIp.isEmpty()) return null

        val flag = CountryFlag.of(state.currentCountry).ifEmpty { "？" }

        return " · $flag ${state.currentIp}"
    }

    private fun openGeoSiteSettings() {
        // the settings activity lives in the app module, so address it by name
        val intent = Intent().setClassName(context, "com.github.kr328.clash.MetaFeatureSettingsActivity")

        context.startActivity(intent)
    }
}
