package com.github.kr328.clash.design.custom.editor

import com.github.kr328.clash.custom.RestoreCarrierSwitchPolicy
import com.github.kr328.clash.common.custom.CarrierSwitchTogglePolicy

object RestoreCarrierSwitchTests {
    fun run() {
        check(RestoreCarrierSwitchPolicy.shouldApplyAfterRestore(false), "restore without profile reload applies mappings")
        check(!RestoreCarrierSwitchPolicy.shouldApplyAfterRestore(true), "profile-loaded hook owns mapping application")
        check(CarrierSwitchTogglePolicy.shouldApply(true), "automatic carrier switching applies when enabled")
        check(!CarrierSwitchTogglePolicy.shouldApply(false), "automatic carrier switching is skipped when disabled")
    }
}
