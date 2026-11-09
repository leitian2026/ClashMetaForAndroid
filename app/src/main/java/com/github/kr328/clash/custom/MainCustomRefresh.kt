package com.github.kr328.clash.custom

import com.github.kr328.clash.design.MainDesign
import com.github.kr328.clash.util.withClash

// CUSTOM (CUSTOM.md #7): re-reads the current node IP for the home page. Called from MainActivity's
// existing 1-second ticker (every 3rd tick), so it only runs while the page is open and Clash runs.
suspend fun MainDesign.refreshCustom(running: Boolean) {
    val state = withClash { queryTunnelState() }

    setCustomCards(running, state)
}
