package com.github.kr328.clash.design.custom

import android.content.Context

// CUSTOM (CUSTOM.md #6): is the user's downloaded geosite.dat gone because of an app update?
// MainApplication.extractGeoFiles() deletes geosite.dat when it is older than the app's last update
// time, so "recorded mtime < lastUpdateTime" means it was replaced by the built-in one. The home page
// shows a card for as long as this is true; downloading a new GeoSite records a newer time, which
// makes the card disappear. Pure local check when the home page is shown: no timers, no network.

object GeoSiteResetNotice {
    fun isPending(context: Context): Boolean {
        val store = context.getSharedPreferences(GeoSiteDownloader.STORE_NAME, Context.MODE_PRIVATE)

        val installedAt = store.getLong(GeoSiteDownloader.KEY_INSTALLED_AT, 0L)
        if (installedAt == 0L) return false

        val updatedAt = try {
            context.packageManager.getPackageInfo(context.packageName, 0).lastUpdateTime
        } catch (e: Exception) {
            return false
        }

        return updatedAt > installedAt
    }
}
