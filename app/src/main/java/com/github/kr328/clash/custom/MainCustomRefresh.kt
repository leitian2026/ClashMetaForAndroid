package com.github.kr328.clash.custom

import android.content.Context
import com.github.kr328.clash.design.MainDesign
import com.github.kr328.clash.util.withClash

// CUSTOM (CUSTOM.md #7): re-reads the current node IP for the home page. Called from MainActivity
// when the service broadcasts that the node IP in use changed.
suspend fun MainDesign.refreshCustom(context: Context, running: Boolean) {
    val state = withClash { queryTunnelState() }

    setCustomCards(running, state, loadNetExtra(context, running, state.currentNode)) // CUSTOM.md #33
}
