package com.github.kr328.clash.custom

import com.github.kr328.clash.core.custom.CustomConnections
import com.github.kr328.clash.core.custom.IssuesSnapshot
import com.github.kr328.clash.design.custom.IssuesDisplayState
import com.github.kr328.clash.design.MainDesign
import kotlinx.coroutines.CancellationException
import com.github.kr328.clash.util.withClash

// CUSTOM (CUSTOM.md #7): re-reads the current node IP for the home page. Called from MainActivity
// when the service broadcasts that the node IP in use changed (or that the node's "works / does not work"
// answer changed, CUSTOM.md #37).
suspend fun MainDesign.refreshCustom(running: Boolean) {
    val state = withClash { queryTunnelState() }

    setCustomCards(running, state)
    val issues = refreshIssues(running) // CUSTOM.md #53

    // CUSTOM.md #147: only mark a reachable node when the issues snapshot confirms no active problems
    if (running && state.reach > 0 && issues is IssuesDisplayState.NoIssues) ConfigVerified.mark(context)
}

// CUSTOM (CUSTOM.md #53): the card for problems of the running config. Asked whenever the home page is fetched or
// refreshed by an event; nothing here runs on its own.
suspend fun MainDesign.refreshIssues(running: Boolean): IssuesDisplayState {
    val result = if (!running) {
        IssuesDisplayState.NotRunning
    } else {
        try {
            val snapshot = IssuesSnapshot.parse(withClash { queryCustomConnections(CustomConnections.SORT_ISSUES, "") })
            if (snapshot.items.none { !it.ignored && !it.resolved }) IssuesDisplayState.NoIssues else IssuesDisplayState.Loaded(snapshot)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            IssuesDisplayState.ReadFailed(e.message?.takeIf { it.isNotBlank() } ?: e.javaClass.simpleName)
        }
    }
    setCustomIssues(result)
    return result
}
