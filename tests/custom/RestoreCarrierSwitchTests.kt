package com.github.kr328.clash.design.custom.editor

import com.github.kr328.clash.custom.RestoreCarrierSwitchPolicy

object RestoreCarrierSwitchTests {
    fun run() {
        check(!RestoreCarrierSwitchPolicy.shouldApply(0), "no restored mappings: do not apply")
        check(!RestoreCarrierSwitchPolicy.shouldApply(-1), "non-positive restored mappings: do not apply")
        check(RestoreCarrierSwitchPolicy.shouldApply(1), "restored mapping: apply")
        check(
            RestoreCarrierSwitchPolicy.shouldWaitForProfileLoaded(1, true, true, "active-id"),
            "active profile changed while Clash runs: wait for loaded event",
        )
        check(
            !RestoreCarrierSwitchPolicy.shouldWaitForProfileLoaded(1, false, true, "active-id"),
            "active profile unchanged: apply immediately",
        )
        check(
            !RestoreCarrierSwitchPolicy.shouldWaitForProfileLoaded(1, true, false, "active-id"),
            "Clash stopped: apply immediately",
        )
        check(
            !RestoreCarrierSwitchPolicy.shouldWaitForProfileLoaded(1, true, true, null),
            "no active profile: do not wait for loaded event",
        )
        check(
            !RestoreCarrierSwitchPolicy.shouldWaitForProfileLoaded(0, true, true, "active-id"),
            "no restored mappings: do not wait",
        )
    }
}
