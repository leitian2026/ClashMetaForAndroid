package com.github.kr328.clash.service.custom

// CUSTOM #176: keeps notification selection and refresh policy independent of Android APIs.
object NotificationDisplayPolicy {
    fun useDynamicNotification(showTraffic: Boolean, showIp: Boolean): Boolean =
        showTraffic || showIp

    fun shouldRefreshTraffic(showTraffic: Boolean, screenInteractive: Boolean): Boolean =
        showTraffic && screenInteractive

    fun shouldRefreshIp(showIp: Boolean, screenInteractive: Boolean): Boolean =
        showIp && screenInteractive
}
