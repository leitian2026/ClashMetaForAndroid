package com.github.kr328.clash.custom

import com.github.kr328.clash.BaseActivity
import com.github.kr328.clash.BaseActivity.Event
import com.github.kr328.clash.core.custom.CustomConnections
import com.github.kr328.clash.common.custom.NetworkIpv6
import com.github.kr328.clash.core.model.ErrorSnapshot
import com.github.kr328.clash.core.model.NodeInfo
import com.github.kr328.clash.core.model.ProxySort
import com.github.kr328.clash.design.custom.CrashRecorder
import com.github.kr328.clash.design.custom.ErrorsDesign
import com.github.kr328.clash.design.custom.ErrorsReport
import com.github.kr328.clash.util.withClash
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.isActive
import kotlinx.coroutines.selects.select
import kotlinx.coroutines.withContext

// CUSTOM (CUSTOM.md #21): the errors and diagnostics page. It only shows data that already exists:
// the core's own warnings / errors (failed connections with node, rule, destination and reason),
// the speed-test result of each node, and the crash records of this app.
//
// Event driven, no timer: refreshed when the page opens or comes back, when the proxy starts / stops
// or a profile loads, and by its refresh button. Nothing runs while the page is not on screen.
class ErrorsActivity : BaseActivity<ErrorsDesign>() {
    // The nodes whose timeout is on screen now: what "clear" hides.
    private var shownTimeouts: List<String> = emptyList()

    private val prefs by lazy { getSharedPreferences("custom_errors", MODE_PRIVATE) }

    override suspend fun main() {
        val design = ErrorsDesign(this)

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
                        ErrorsDesign.Request.Refresh -> design.refresh()
                        ErrorsDesign.Request.Copy -> design.copyAll()
                        ErrorsDesign.Request.Clear -> {
                            if (clashRunning) {
                                try {
                                    withClash { closeCustomConnection(CustomConnections.CLEAR_ERRORS) }
                                } catch (e: CancellationException) {
                                    throw e
                                } catch (e: Exception) {
                                    // the core could not be asked; the crash records are still cleared
                                }
                            }
                            withContext(Dispatchers.IO) { CrashRecorder.clear(this@ErrorsActivity) }
                            dismissTimeouts()

                            design.refresh()
                        }
                    }
                }
            }
        }
    }

    private suspend fun ErrorsDesign.refresh() {
        val running = clashRunning

        var snapshot: ErrorSnapshot? = null
        var timeouts: List<String>? = null
        var skipped: List<String> = emptyList() // CUSTOM.md #23
        var info: NodeInfo? = null // CUSTOM.md #23 #24

        if (running) {
            try {
                snapshot = ErrorSnapshot.parse(
                    withClash { queryCustomConnections(CustomConnections.SORT_ERRORS, "") },
                )
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // shown as "not available": the other blocks still work
            }

            try {
                var timingOut = withClash {
                    val names = LinkedHashSet<String>()

                    for (group in queryProxyGroupNames(false)) {
                        for (p in queryProxyGroup(group, ProxySort.Default).proxies) {
                            // 0 = never tested; a value above Short.MAX_VALUE is how the core reports a timeout
                            if (!p.isGroup && p.delay > Short.MAX_VALUE) names += p.name
                        }
                    }

                    names.toList()
                }

                // CUSTOM.md #23 #24: IPv6-only nodes cannot pass a speed test on a network without IPv6, so their
                // timeout is expected and is not listed as one; and the page says how old the test is.
                info = try {
                    NodeInfo.parse(withClash { queryCustomConnections(CustomConnections.SORT_NODEINFO, "") })
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    null
                }

                val ipv6Nodes = info?.ipv6.orEmpty()

                if (ipv6Nodes.isNotEmpty() && timingOut.isNotEmpty() && !NetworkIpv6.hasIpv6(this@ErrorsActivity)) {
                    val v6 = ipv6Nodes.toHashSet()

                    skipped = timingOut.filter { it in v6 }
                    timingOut = timingOut.filter { it !in v6 }
                }

                timeouts = filterDismissed(timingOut)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // no profile loaded yet
            }
        }

        val (crashes, exits) = withContext(Dispatchers.IO) {
            CrashRecorder.read(this@ErrorsActivity) to CrashRecorder.exitRecords(this@ErrorsActivity)
        }

        shownTimeouts = timeouts.orEmpty()

        patch(
            ErrorsReport(
                running, snapshot, timeouts, crashes, exits,
                ipv6Skipped = skipped,
                testedAt = info?.testedAt ?: 0,
                networkChangedAt = info?.networkChangedAt ?: 0,
            ),
        )
    }

    // A node's timeout is its latest speed-test result, not a log line, so "clear" cannot delete it. It
    // hides the ones shown now instead: a node stays hidden until it tests fine once (then it drops out of
    // the saved names, so a later timeout shows again). Read and written only when the page refreshes or
    // "clear" is tapped.
    private fun dismissTimeouts() {
        if (shownTimeouts.isEmpty()) return

        val saved = HashSet(prefs.getStringSet(KEY_DISMISSED, emptySet()).orEmpty())

        if (saved.addAll(shownTimeouts)) prefs.edit().putStringSet(KEY_DISMISSED, saved).apply()
    }

    private fun filterDismissed(timingOut: List<String>): List<String> {
        val saved = prefs.getStringSet(KEY_DISMISSED, emptySet()).orEmpty()

        if (saved.isEmpty()) return timingOut

        val still = saved.filterTo(HashSet()) { it in timingOut }

        if (still.size != saved.size) prefs.edit().putStringSet(KEY_DISMISSED, still).apply()

        return timingOut.filter { it !in still }
    }

    private companion object {
        const val KEY_DISMISSED = "dismissed_timeouts"
    }
}
