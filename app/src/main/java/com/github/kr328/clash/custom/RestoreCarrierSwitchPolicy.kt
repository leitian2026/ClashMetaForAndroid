package com.github.kr328.clash.custom

/** Pure restore-time decisions, kept separate so the conditions can be tested on the JVM. */
internal object RestoreCarrierSwitchPolicy {
    fun shouldApply(carrierRestored: Int): Boolean = carrierRestored > 0

    fun shouldWaitForProfileLoaded(
        carrierRestored: Int,
        activeProfileChanged: Boolean,
        clashRunning: Boolean,
        activeProfileUuid: String?,
    ): Boolean = carrierRestored > 0 &&
        activeProfileChanged &&
        clashRunning &&
        activeProfileUuid != null
}
