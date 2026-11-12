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

    fun update(running: Boolean, state: TunnelState): String? {
        geoSiteNotice?.visibility = if (GeoSiteResetNotice.isPending(context)) View.VISIBLE else View.GONE

        if (!running) return null

        // every address the requests went through most recently (CUSTOM.md #7)
        val text = state.ipsText { context.getString(R.string.custom_ips_more, it) }

        return if (text.isEmpty()) null else " · $text"
    }

    private fun openGeoSiteSettings() {
        // the settings activity lives in the app module, so address it by name
        val intent = Intent().setClassName(context, "com.github.kr328.clash.MetaFeatureSettingsActivity")

        context.startActivity(intent)
    }
}
