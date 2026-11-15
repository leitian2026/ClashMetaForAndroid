package com.github.kr328.clash.custom

import com.github.kr328.clash.BaseActivity
import com.github.kr328.clash.BaseActivity.Event
import com.github.kr328.clash.core.custom.CustomConnections
import com.github.kr328.clash.core.custom.IssuesSnapshot
import com.github.kr328.clash.design.custom.IssuesDesign
import com.github.kr328.clash.util.withClash
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.isActive
import kotlinx.coroutines.selects.select

// CUSTOM (CUSTOM.md #53): the page behind the home card and the notification: every problem of the running config
// the core found, each with an "ignore" button. Event driven: read when the page opens or comes back, when the proxy
// starts / stops, and by its buttons; nothing runs while the page is not on screen.
class IssuesActivity : BaseActivity<IssuesDesign>() {
    override suspend fun main() {
        val design = IssuesDesign(this)

        setContentDesign(design)

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
                        IssuesDesign.Request.Refresh -> design.refresh()
                        IssuesDesign.Request.Clear -> {
                            command { closeCustomConnection(CustomConnections.ISSUE_CLEAR) }
                            design.refresh()
                        }
                        is IssuesDesign.Request.Ignore -> {
                            val prefix = if (it.ignore) CustomConnections.ISSUE_IGNORE else CustomConnections.ISSUE_UNIGNORE

                            command { closeCustomConnection(prefix + it.id) }
                            design.refresh()
                        }
                    }
                }
            }
        }
    }

    private suspend fun command(block: suspend com.github.kr328.clash.service.remote.IClashManager.() -> Unit) {
        if (!clashRunning) return

        try {
            withClash { block() }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // the core could not be asked
        }
    }

    private suspend fun IssuesDesign.refresh() {
        var snapshot: IssuesSnapshot? = null

        if (clashRunning) {
            try {
                snapshot = IssuesSnapshot.parse(withClash { queryCustomConnections(CustomConnections.SORT_ISSUES, "") })
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // shown as empty
            }
        }

        patch(clashRunning, snapshot)
    }
}
