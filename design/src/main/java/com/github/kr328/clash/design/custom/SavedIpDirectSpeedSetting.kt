package com.github.kr328.clash.design.custom

import android.content.Context

/** CUSTOM #156: independent saved-IP page display preference; never changes stored IP data. */
internal object SavedIpDirectSpeedSetting {
    private const val KEY = "show_direct_speed"

    fun read(context: Context): Boolean =
        context.getSharedPreferences("custom_saved_ip_ui", Context.MODE_PRIVATE).getBoolean(KEY, false)

    fun write(context: Context, enabled: Boolean) {
        context.getSharedPreferences("custom_saved_ip_ui", Context.MODE_PRIVATE)
            .edit()
            .putBoolean(KEY, enabled)
            .apply()
    }
}
