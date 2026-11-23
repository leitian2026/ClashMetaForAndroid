package com.github.kr328.clash.design.custom.editor

import com.github.kr328.clash.custom.RestoreCarrierSwitchPolicy

object RestoreCarrierSwitchTests {
    fun run() {
        check(RestoreCarrierSwitchPolicy.shouldApplyAfterRestore(false), "restore without profile reload applies mappings")
        check(!RestoreCarrierSwitchPolicy.shouldApplyAfterRestore(true), "profile-loaded hook owns mapping application")
    }
}
