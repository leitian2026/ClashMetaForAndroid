package com.github.kr328.clash.design.custom.editor

import com.github.kr328.clash.service.custom.NotificationDisplayPolicy

object NotificationDisplayPolicyTests {
    fun run() {
        check(
            NotificationDisplayPolicy.useDynamicNotification(showTraffic = true, showIp = true),
            "both notification lines select the dynamic module",
        )
        check(
            NotificationDisplayPolicy.useDynamicNotification(showTraffic = true, showIp = false),
            "traffic only selects the dynamic module",
        )
        check(
            NotificationDisplayPolicy.useDynamicNotification(showTraffic = false, showIp = true),
            "IP only selects the dynamic module",
        )
        check(
            !NotificationDisplayPolicy.useDynamicNotification(showTraffic = false, showIp = false),
            "both notification lines disabled select the static module",
        )

        check(
            NotificationDisplayPolicy.shouldRefreshTraffic(showTraffic = true, screenInteractive = true),
            "traffic refreshes while enabled and screen is on",
        )
        check(
            !NotificationDisplayPolicy.shouldRefreshTraffic(showTraffic = true, screenInteractive = false),
            "traffic does not refresh while screen is off",
        )
        check(
            !NotificationDisplayPolicy.shouldRefreshTraffic(showTraffic = false, screenInteractive = true),
            "traffic does not refresh when disabled",
        )

        check(
            NotificationDisplayPolicy.shouldRefreshIp(showIp = true, screenInteractive = true),
            "IP event can refresh while enabled and screen is on",
        )
        check(
            !NotificationDisplayPolicy.shouldRefreshIp(showIp = true, screenInteractive = false),
            "IP event is deferred while screen is off",
        )
        check(
            !NotificationDisplayPolicy.shouldRefreshIp(showIp = false, screenInteractive = true),
            "IP is not read when disabled",
        )
    }
}
