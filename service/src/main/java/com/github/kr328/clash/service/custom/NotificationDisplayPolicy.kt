package com.github.kr328.clash.service.custom

object NotificationDisplayPolicy {
    fun useDynamicNotification(showTraffic: Boolean, showIp: Boolean): Boolean =
        showTraffic || showIp

    fun shouldRefreshTraffic(showTraffic: Boolean, screenInteractive: Boolean): Boolean =
        showTraffic && screenInteractive

    fun shouldRefreshIp(showIp: Boolean, screenInteractive: Boolean): Boolean =
        showIp && screenInteractive
}
