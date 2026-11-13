package com.github.kr328.clash.custom

import com.github.kr328.clash.design.MainDesign
import com.github.kr328.clash.util.withClash

// CUSTOM (CUSTOM.md #7): re-reads the current node IP for the home page. Called from MainActivity
// when the service broadcasts that the node IP in use changed (or that the node's "works / does not work"
// answer changed, CUSTOM.md #37).
suspend fun MainDesign.refreshCustom(running: Boolean) {
    val state = withClash { queryTunnelState() }

    setCustomCards(running, state)

    // CUSTOM.md #38: the node in use works -> the config version in use is marked as worked
    if (running && state.reach > 0) ConfigVerified.mark(context)
}
