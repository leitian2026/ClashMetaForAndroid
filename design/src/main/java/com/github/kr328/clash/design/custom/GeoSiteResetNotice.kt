package com.github.kr328.clash.design.custom

import android.content.Context

// CUSTOM (CUSTOM.md #6): is the user's downloaded geosite.dat gone because of an app update?
// MainApplication.extractGeoFiles() deletes geosite.dat when it is older than the app's last update
// time, so "recorded mtime < lastUpdateTime" means it was replaced by the built-in one. The home page
// shows a card for as long as this is true; downloading a new GeoSite records a newer time, which
// makes the card disappear. Pure local check when the home page is shown: no timers, no network.
//
// CUSTOM #16: a file downloaded with this version has a kept copy that GeoFileKeeper puts back after
// every app update, so it is never "reset" and the card stays hidden. Only a file downloaded with an
// older version (no kept copy) can still be reset by an update.
object GeoSiteResetNotice {
    fun isPending(context: Context): Boolean {
        if (GeoFileKeeper.hasCopy(context, GeoFile.GeoSite)) return false

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
