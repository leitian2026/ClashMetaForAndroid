package com.github.kr328.clash.custom

import com.github.kr328.clash.BaseActivity
import com.github.kr328.clash.BaseActivity.Event
import com.github.kr328.clash.common.util.ticker
import com.github.kr328.clash.core.model.ConnectionSnapshot
import com.github.kr328.clash.design.R as DesignR
import com.github.kr328.clash.design.custom.ConnectionsDesign
import com.github.kr328.clash.design.ui.ToastDuration
import com.github.kr328.clash.util.withClash
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.isActive
import kotlinx.coroutines.selects.select
import java.util.concurrent.TimeUnit

// CUSTOM (CUSTOM.md #8): the active connections of the core.
//
// Refreshed (a) when the page opens or comes back, (b) by its refresh button, (c) when the service
// reports that the node IP in use changed (same event as the home page), and (d) every 2 seconds, but
// only while the page is on screen and the proxy is running: the timer branch is left out of the
// select while the activity is stopped, so nothing wakes up in the background.
class ConnectionsActivity : BaseActivity<ConnectionsDesign>() {
    override suspend fun main() {
        val design = ConnectionsDesign(this)

        setContentDesign(design)

        val ticker = ticker(TimeUnit.SECONDS.toMillis(2))
        val nodeIp = customNodeIpEvents()

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
                        ConnectionsDesign.Request.Refresh -> design.refresh()
                        ConnectionsDesign.Request.OpenHistory -> design.showHistory()
                        is ConnectionsDesign.Request.Open -> {
                            // the loop waits here, so the list does not change under the dialog
                            if (design.showDetail(it.item)) {
                                val closed = try {
                                    withClash { closeCustomConnection(it.item.id) }
                                } catch (e: CancellationException) {
                                    throw e
                                } catch (e: Exception) {
                                    false
                                }

                                design.showToast(
                                    if (closed) DesignR.string.connection_closed else DesignR.string.connection_gone,
                                    ToastDuration.Short,
                                )
                            }

                            design.refresh()
                        }
                    }
                }
                nodeIp.onReceive {
                    design.refresh()
                }
                if (clashRunning && activityStarted) {
                    ticker.onReceive {
                        design.refresh()
                    }
                }
            }
        }
    }

    private suspend fun ConnectionsDesign.refresh() {
        val running = clashRunning

        val snapshot = if (running) {
            try {
                ConnectionSnapshot.parse(withClash { queryCustomConnections() })
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                null // keeps showing "no connections" until the next refresh works
            }
        } else {
            null
        }

        patch(snapshot, running)
    }
}
