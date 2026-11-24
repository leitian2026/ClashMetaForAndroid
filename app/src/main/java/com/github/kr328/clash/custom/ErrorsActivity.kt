package com.github.kr328.clash.custom

import com.github.kr328.clash.BaseActivity
import com.github.kr328.clash.BaseActivity.Event
import com.github.kr328.clash.core.custom.CustomConnections
import com.github.kr328.clash.core.custom.IssuesSnapshot
import com.github.kr328.clash.service.remote.IClashManager
import com.github.kr328.clash.common.custom.NetworkIpv6
import com.github.kr328.clash.common.custom.NetworkIdentityStore
import com.github.kr328.clash.common.custom.UpdateGuardRecord
import com.github.kr328.clash.common.custom.UpdateGuardStore
import com.github.kr328.clash.core.model.ErrorSnapshot
import com.github.kr328.clash.core.model.NodeInfo
import com.github.kr328.clash.core.model.ProxySort
import com.github.kr328.clash.design.custom.CrashRecorder
import com.github.kr328.clash.design.custom.ErrorsDesign
import com.github.kr328.clash.design.custom.ErrorsReport
import com.github.kr328.clash.design.custom.SubscriptionUpdateDiff
import com.github.kr328.clash.design.custom.ConfigDiff
import com.github.kr328.clash.service.custom.ConfigUpdateSnapshot
import com.github.kr328.clash.service.store.ServiceStore
import com.github.kr328.clash.service.util.importedDir
import com.github.kr328.clash.util.withClash
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.isActive
import kotlinx.coroutines.selects.select
import kotlinx.coroutines.withContext
import java.util.UUID

// CUSTOM (CUSTOM.md #21): the errors and diagnostics page. It only shows data that already exists:
// the core's own warnings / errors (failed connections with node, rule, destination and reason),
// the speed-test result of each node, and the crash records of this app.
//
// Event driven, no timer: refreshed when the page opens or comes back, when the proxy starts / stops
// or a profile loads, and by its refresh button. Nothing runs while the page is not on screen.
class ErrorsActivity : BaseActivity<ErrorsDesign>() {
    // The nodes whose timeout is on screen now: what "clear" hides.
    private var shownTimeouts: List<String> = emptyList()
    private var shownTestedAt: Long = 0
    private var subscriptionUpdate: SubscriptionUpdateDiff? = null // CUSTOM.md #170: read once when the page opens
    private var updateGuardRecord: UpdateGuardRecord? = null // CUSTOM #173: read once when the page opens
    private var updateGuardUuid: UUID? = null

    private val prefs by lazy { getSharedPreferences("custom_errors", MODE_PRIVATE) }

    override suspend fun main() {
        val design = ErrorsDesign(this)

        setContentDesign(design)
        subscriptionUpdate = readSubscriptionUpdateOnce()
        updateGuardRecord = readUpdateGuardOnce()

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
                design.issueRequests.onReceive { (id, ignore) -> // CUSTOM.md #53
                    val prefix = if (ignore) CustomConnections.ISSUE_IGNORE else CustomConnections.ISSUE_UNIGNORE

                    askCore { closeCustomConnection(prefix + id) }
                    design.refresh()
                }
                design.requests.onReceive {
                    when (it) {
                        ErrorsDesign.Request.Refresh -> design.refresh()
                        ErrorsDesign.Request.Copy -> design.copyAll()
                        ErrorsDesign.Request.IgnoreUpdateGuard -> {
                            val uuid = updateGuardUuid
                            val saved = uuid != null && withContext(Dispatchers.IO) {
                                UpdateGuardStore.setIgnored(this@ErrorsActivity, uuid, true)
                            }
                            if (saved) {
                                updateGuardRecord = null
                                design.setUpdateGuard(null) {}
                            }
                        }
                        ErrorsDesign.Request.Clear -> {
                            askCore { // the core could not be asked: the crash records are still cleared
                                closeCustomConnection(CustomConnections.CLEAR_ERRORS)
                                closeCustomConnection(CustomConnections.ISSUE_CLEAR) // CUSTOM.md #53
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

    private suspend fun askCore(block: suspend IClashManager.() -> Unit) {
        if (!clashRunning) return

        try {
            withClash { block() }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // the core could not be asked
        }
    }

    private suspend fun ErrorsDesign.refresh() {
        val running = clashRunning

        var snapshot: ErrorSnapshot? = null
        var timeouts: List<String>? = null
        var skipped: List<String> = emptyList() // CUSTOM.md #23
        var info: NodeInfo? = null // CUSTOM.md #23 #24
        var issues: IssuesSnapshot? = null // CUSTOM.md #53

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

            try { // CUSTOM.md #53: the problems of the running config
                issues = IssuesSnapshot.parse(withClash { queryCustomConnections(CustomConnections.SORT_ISSUES, "") })
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // the block stays empty
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

                timeouts = filterDismissed(timingOut, info?.testedAt ?: 0)
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
        shownTestedAt = info?.testedAt ?: 0

        patch(
            ErrorsReport(
                running, snapshot, timeouts, crashes, exits,
                ipv6Skipped = skipped,
                testedAt = info?.testedAt ?: 0,
                networkChangedAt = info?.networkChangedAt ?: 0,
                issues = issues,
                networkIdentity = NetworkIdentityStore.read(this@ErrorsActivity), // CUSTOM (CUSTOM.md #168)
                subscriptionUpdate = subscriptionUpdate,
            ),
        )
        design.setUpdateGuard(updateGuardRecord) {
            design.requests.trySend(ErrorsDesign.Request.IgnoreUpdateGuard)
        }
    }


    /** CUSTOM #173: read the active subscription warning once when the diagnostics page opens. */
    private suspend fun readUpdateGuardOnce(): UpdateGuardRecord? = withContext(Dispatchers.IO) {
        try {
            val uuid = ServiceStore(this@ErrorsActivity).activeProfile ?: return@withContext null
            val record = UpdateGuardStore.read(this@ErrorsActivity, uuid) ?: return@withContext null
            if (record.ignored) return@withContext null
            updateGuardUuid = uuid
            record
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            null
        }
    }

    /** CUSTOM (CUSTOM.md #170): compare once when this diagnostics page is opened, never on update/background. */
    private suspend fun readSubscriptionUpdateOnce(): SubscriptionUpdateDiff? = withContext(Dispatchers.IO) {
        try {
            val uuid = ServiceStore(this@ErrorsActivity).activeProfile ?: return@withContext null
            val old = ConfigUpdateSnapshot.read(this@ErrorsActivity, uuid) ?: return@withContext null
            val currentFile = importedDir.resolve("$uuid/config.yaml")
            if (!currentFile.isFile || currentFile.length() <= 0L ||
                currentFile.length() > ConfigUpdateSnapshot.MAX_BYTES
            ) return@withContext null
            val current = currentFile.readText(Charsets.UTF_8)
            if (old == current) return@withContext null
            val diff = ConfigDiff.diff(old, current)
            val added = diff.added + diff.changed
            val removed = diff.removed + diff.changed
            if (added == 0 && removed == 0) null else SubscriptionUpdateDiff(added, removed)
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            null
        }
    }

    // NodeInfo.testedAt is one timestamp for the latest completed batch, not a per-node timestamp. Store it
    // with each cleared node: a later batch makes a still-timing-out node visible again. Read / write only
    // when the page refreshes or "clear" is tapped.
    private fun dismissTimeouts() {
        if (shownTimeouts.isEmpty()) return

        val saved = HashSet(prefs.getStringSet(KEY_DISMISSED, emptySet()).orEmpty())

        for (name in shownTimeouts) {
            saved.removeAll { dismissedNodeName(it) == name }
            saved += "$name|$shownTestedAt"
        }

        prefs.edit().putStringSet(KEY_DISMISSED, saved).apply()
    }

    private fun filterDismissed(timingOut: List<String>, testedAt: Long): List<String> {
        val saved = prefs.getStringSet(KEY_DISMISSED, emptySet()).orEmpty()

        if (saved.isEmpty()) return timingOut

        val still = HashSet<String>()
        val hidden = HashSet<String>()

        for (entry in saved) {
            val separator = entry.lastIndexOf('|')
            val parsedAt = if (separator > 0) entry.substring(separator + 1).toLongOrNull() else null
            val name = if (parsedAt == null) entry else entry.substring(0, separator)
            val clearedAt = parsedAt ?: testedAt

            if (name !in timingOut) continue

            if (testedAt <= clearedAt) {
                hidden += name
                // Keep legacy node-only entries until a real testedAt is available; then migrate once.
                still += if (parsedAt == null && testedAt == 0L) entry else "$name|$clearedAt"
            }
        }

        if (still != saved) prefs.edit().putStringSet(KEY_DISMISSED, still).apply()

        return timingOut.filter { it !in hidden }
    }

    private fun dismissedNodeName(value: String): String {
        val separator = value.lastIndexOf('|')
        if (separator <= 0 || value.substring(separator + 1).toLongOrNull() == null) return value
        return value.substring(0, separator)
    }

    private companion object {
        const val KEY_DISMISSED = "dismissed_timeouts"
    }
}
