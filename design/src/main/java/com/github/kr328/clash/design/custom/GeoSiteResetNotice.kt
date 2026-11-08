package com.github.kr328.clash.design.custom

import android.app.Activity
import android.content.Context
import com.github.kr328.clash.design.R
import com.google.android.material.dialog.MaterialAlertDialogBuilder

// CUSTOM: tell the user, once, that an app update replaced their downloaded geosite.dat with the
// built-in one (see CUSTOM.md #6). MainApplication.extractGeoFiles() deletes geosite.dat when it is
// older than the app's last update time, so "recorded mtime < lastUpdateTime" means it was replaced.
// Pure local check at activity start: no timers, no network.

object GeoSiteResetNotice {
    fun showIfNeeded(activity: Activity) {
        val store = activity.getSharedPreferences(GeoSiteDownloader.STORE_NAME, Context.MODE_PRIVATE)

        val installedAt = store.getLong(GeoSiteDownloader.KEY_INSTALLED_AT, 0L)
        if (installedAt == 0L) return

        val updatedAt = try {
            activity.packageManager.getPackageInfo(activity.packageName, 0).lastUpdateTime
        } catch (e: Exception) {
            return
        }

        if (updatedAt <= installedAt) return

        store.edit().remove(GeoSiteDownloader.KEY_INSTALLED_AT).apply()

        MaterialAlertDialogBuilder(activity)
            .setTitle(R.string.geosite_reset_title)
            .setMessage(R.string.geosite_reset_message)
            .setPositiveButton(R.string.ok) { _, _ -> }
            .show()
    }
}
