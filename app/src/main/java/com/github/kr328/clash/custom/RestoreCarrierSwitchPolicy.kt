package com.github.kr328.clash.custom

/** Pure restore-time decision: a profile load owns the next carrier application. */
internal object RestoreCarrierSwitchPolicy {
    fun shouldApplyAfterRestore(configurationReloadRequested: Boolean): Boolean =
        !configurationReloadRequested
}
