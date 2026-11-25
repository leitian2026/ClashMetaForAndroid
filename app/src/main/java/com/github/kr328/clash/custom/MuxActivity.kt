package com.github.kr328.clash.custom

import com.github.kr328.clash.BaseActivity
import com.github.kr328.clash.BaseActivity.Event
import com.github.kr328.clash.common.util.ticker
import com.github.kr328.clash.core.custom.CustomConnections
import com.github.kr328.clash.core.model.MuxSnapshot
import com.github.kr328.clash.design.custom.MuxDesign
import com.github.kr328.clash.util.withClash
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.isActive
import kotlinx.coroutines.selects.select
import java.util.concurrent.TimeUnit

// CUSTOM (CUSTOM.md #20): how many sockets the core has open to each node (is multiplexing working?).
//
// Refreshed (a) when the page opens or comes back, (b) by its refresh button, and (c) every 2 seconds,
// but only while the page is on screen and the proxy is running: the timer branch is left out of the
// select while the activity is stopped, so nothing wakes up in the background.
class MuxActivity : BaseActivity<MuxDesign>() {
    override suspend fun main() {
        val design = MuxDesign(this)

        setContentDesign(design)

        val ticker = ticker(TimeUnit.SECONDS.toMillis(2))

        while (isActive) {
            select<Unit> {
                events.onReceive {
                    when (it) {
                        Event.ActivityStart,
                        Event.ServiceRecreated,
                        Event.ClashStart, Event.ClashStop,
                        Event.ProfileLoaded -> design.refresh()
                        else -> Unit
                    }
                }
                design.requests.onReceive {
                    when (it) {
                        MuxDesign.Request.Refresh -> design.refresh()
                    }
                }
                if (clashRunning && activityStarted) {
                    ticker.onReceive {
                        design.refresh()
                    }
                }
            }
        }
    }

    // what the page shows now; an unchanged answer is not parsed or drawn again
    private var shownRunning: Boolean? = null
    private var shownRaw: String? = null
    private var shownError: String? = null

    private suspend fun MuxDesign.refresh() {
        val running = clashRunning
        var error: String? = null

        val raw = if (running) {
            try {
                withClash { queryCustomConnections(CustomConnections.SORT_MUX, "") }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                error = e.message?.takeIf { it.isNotBlank() } ?: e.javaClass.simpleName
                null
            }
        } else {
            null
        }

        // Unchanged successful data is already represented, including a prior parse failure.
        // For a failed query (raw == null), compare the error too so a changed failure is still shown.
        if (running == shownRunning && raw == shownRaw && (raw != null || error == shownError)) return

        val snapshot = raw?.let {
            try {
                MuxSnapshot.parse(it)
            } catch (e: Exception) {
                error = e.message?.takeIf { it.isNotBlank() } ?: e.javaClass.simpleName
                null
            }
        }

        shownRunning = running
        shownRaw = raw
        shownError = error

        patch(snapshot, running, error)
    }
}
