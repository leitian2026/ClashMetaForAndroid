package com.github.kr328.clash.common.custom

/** Pure gate shared by automatic carrier-switch triggers and JVM tests. */
object CarrierSwitchTogglePolicy {
    fun shouldApply(enabled: Boolean): Boolean = enabled
}
