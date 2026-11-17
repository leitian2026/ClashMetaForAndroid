package com.github.kr328.clash.custom

import com.github.kr328.clash.BaseActivity
import com.github.kr328.clash.core.custom.CustomConnections
import com.github.kr328.clash.core.model.ConfigCheckReport
import com.github.kr328.clash.core.model.DnsCheckReport
import com.github.kr328.clash.core.model.EffectiveConfig
import com.github.kr328.clash.core.model.IpTestReport
import com.github.kr328.clash.core.model.IpTestRow
import com.github.kr328.clash.design.custom.AgeText
import com.github.kr328.clash.design.custom.ErrorsText
import com.github.kr328.clash.design.custom.ConfigCheckDesign
import com.github.kr328.clash.design.custom.ConfigCheckText
import com.github.kr328.clash.design.custom.CountryName
import com.github.kr328.clash.design.custom.TargetStatusText
import com.github.kr328.clash.design.R
import com.github.kr328.clash.service.util.importedDir
import com.github.kr328.clash.util.withClash
import com.github.kr328.clash.util.withProfile
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.selects.select
import kotlinx.coroutines.withContext
import org.json.JSONObject
import kotlin.random.Random

// CUSTOM (CUSTOM.md #22): the config check page. A test runs only when the user taps the button, and
// stops being waited for when they tap it again (the core finishes its own attempts, each at most a few
// seconds). Nothing runs on its own, no timer.
class ConfigCheckActivity : BaseActivity<ConfigCheckDesign>() {
    private var job: Job? = null

    // CUSTOM.md #43: the running job is a "test other IPs"; the core has to be told to stop it
    private var ipTesting = false

    // CUSTOM (CUSTOM.md #52): what the last round used (for the download of a row), the rows it has, the download running
    private class IpRun(val direct: Boolean, val domain: String, val node: String, val ports: String, val url: String)

    private var lastRun: IpRun? = null
    private var ipRows = ArrayList<IpTestRow>()
    private var speedJob: Job? = null
    private var speedIp = ""

    override suspend fun main() {
        val design = ConfigCheckDesign(this)

        setContentDesign(design)

        // CUSTOM.md #87: restore only the latest IP test round; a new round replaces this saved state.
        design.restoreIpTest()?.let { saved ->
            lastRun = IpRun(saved.direct, saved.domain, saved.node, saved.ports, saved.url)
            ipRows = ArrayList(saved.report.rows)
        }

        // CUSTOM.md #52: a tap on "测速" / "取消" of a row of the IP test
        design.onSpeedTest = { ip ->
            if (job?.isActive != true && speedJob?.isActive != true) {
                speedJob = launch { runSpeed(design, ip) }
            }
        }
        design.onSpeedCancel = { launch { cancelSpeed(design) } }

        while (isActive) {
            select<Unit> {
                design.requests.onReceive {
                    when (it) {
                        ConfigCheckDesign.Request.Copy -> design.copyAll()
                        ConfigCheckDesign.Request.Flush -> flush(design) // CUSTOM.md #26
                        ConfigCheckDesign.Request.Effective -> {
                            // CUSTOM.md #30: ignored while a test is running (its button is the "cancel" one)
                            if (job?.isActive != true) job = launch { runEffective(design) }

                            Unit
                        }
                        ConfigCheckDesign.Request.IpTest -> {
                            // CUSTOM.md #43: while a test runs its button is the "cancel" one
                            // (CUSTOM.md #52: ignored while a row downloads, that has its own "取消")
                            if (speedJob?.isActive == true) {
                                Unit
                            } else if (job?.isActive == true) {
                                cancelRunning(design)
                            } else {
                                job = launch { runIpTest(design) }
                            }

                            Unit
                        }
                        ConfigCheckDesign.Request.Toggle, ConfigCheckDesign.Request.Dns -> {
                            val running = job?.isActive == true

                            if (running) {
                                cancelRunning(design)
                            } else if (it == ConfigCheckDesign.Request.Dns) {
                                job = launch { runDns(design) } // CUSTOM.md #25
                            } else {
                                job = launch { runCheck(design) }
                            }

                            Unit
                        }
                    }
                }
            }
        }
    }

    private suspend fun cancelRunning(design: ConfigCheckDesign) {
        job?.cancel()
        job = null

        val wasIp = ipTesting

        if (ipTesting) {
            ipTesting = false

            try {
                withClash { closeCustomConnection(CustomConnections.CANCEL_IPTEST) }
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
            }
        }

        design.setRunning(false)
        design.setIpRunning(false)

        if (wasIp) {
            design.ipLog(getString(R.string.iptest_log_cancelled)) // the table stays, with what was done before
        } else {
            design.showText(getString(R.string.check_cancelled))
        }
    }

    // CUSTOM (CUSTOM.md #43, #52): test other IPs. A dialog asks for the addresses, the node, the ports and the download
    // address, and the way to test: straight to the address ("direct", uses the domain field of the page as SNI and
    // Host) or through the node with its server replaced by the address ("node"). The addresses are tested one at a
    // time, the app waiting the random 1 to 3 seconds between two (no timer: the wait is a suspended coroutine), and
    // the table and the live status get what each one found. Runs only when tapped; the core stops when the button is
    // tapped again.
    private suspend fun runIpTest(design: ConfigCheckDesign) {
        if (!clashRunning) {
            design.showText(getString(R.string.connections_not_running))
            return
        }

        var begun = false
        var failure = ""

        try {
            val nodes = IpTestReport.parse(
                withClash {
                    queryCustomConnections(
                        CustomConnections.SORT_CHECK,
                        JSONObject().put("kind", "ip").put("mode", "nodes").toString(),
                    )
                },
            ).nodes

            // CUSTOM.md #69: "read the IPs of the config" in the dialog; only when tapped. null = could not be read
            val choice = design.askIpTest(nodes) {
                try {
                    IpTestReport.parse(
                        withClash {
                            queryCustomConnections(
                                CustomConnections.SORT_CHECK,
                                JSONObject().put("kind", "ip").put("mode", "config").toString(),
                            )
                        },
                    )
                } catch (e: CancellationException) {
                    throw e
                } catch (_: Exception) {
                    null
                }
            } ?: return

            if (choice.ips.isBlank()) {
                design.showText(getString(R.string.iptest_need_ips))
                return
            }

            val direct = choice.mode == "direct"
            val domain = if (direct) design.domain() else ""

            if (direct && domain.isEmpty()) {
                design.showText(getString(R.string.iptest_need_domain))
                return
            }

            if (!direct && choice.node.isEmpty()) {
                design.showText(getString(R.string.iptest_need_node))
                return
            }

            // CUSTOM.md #66: the target address (through-node test only): a site the user really uses, http(s) only
            val target = if (direct) "" else choice.target

            if (target.isNotEmpty() && !Regex("^https?://[^/\\s]+.*$", RegexOption.IGNORE_CASE).matches(target)) {
                design.showText(getString(R.string.iptest_target_bad))
                return
            }

            // the list as the core reads it (addresses, domains, words it does not understand, the limit of 20)
            val parsed = IpTestReport.parse(
                withClash {
                    queryCustomConnections(
                        CustomConnections.SORT_CHECK,
                        JSONObject().put("kind", "ip").put("mode", "parse").put("direct", direct)
                            .put("ips", choice.ips).toString(),
                    )
                },
            )

            when (parsed.error) {
                "no address" -> {
                    design.showText(getString(R.string.iptest_need_ips))
                    return
                }
                "domain only node" -> {
                    design.showText(getString(R.string.iptest_domain_node_only))
                    return
                }
            }

            val warnings = ArrayList<String>()

            if (parsed.skipped.isNotEmpty()) warnings += getString(R.string.iptest_skipped, parsed.skipped.joinToString("、"))
            if (parsed.invalid.isNotEmpty()) warnings += getString(R.string.iptest_invalid, parsed.invalid.joinToString("、"))
            if (parsed.cut > 0) warnings += getString(R.string.iptest_cut, parsed.cut)

            val head = if (direct) {
                getString(R.string.iptest_head_direct, domain)
            } else {
                getString(R.string.iptest_head_node, choice.node)
            }

            val targets = parsed.rows

            lastRun = IpRun(direct, domain, choice.node, choice.ports, choice.url)
            ipRows = ArrayList()

            design.ipBegin(direct, head, "", warnings, target.isNotEmpty())
            design.ipSaveContext(domain, choice.node, choice.ports, choice.url)
            begun = true
            design.setIpRunning(true)
            ipTesting = true
            design.ipProgress(targets.size, 0)

            design.ipLog(
                getString(
                    R.string.iptest_log_start,
                    targets.size,
                    if (direct) {
                        getString(R.string.iptest_log_mode_direct, domain)
                    } else {
                        getString(R.string.iptest_log_mode_node, choice.node)
                    },
                ),
            )

            var landNoNodeSaid = false
            var tested = 0

            fetchColos(design) // CUSTOM.md #63: the through-node table has the data centre column too

            for ((i, t) in targets.withIndex()) {
                if (i > 0) delay(Random.nextLong(1000, 3001)) // the random gap between two addresses

                design.ipLog(getString(R.string.iptest_log_ip_start, i + 1, targets.size, t.ip))

                val request = JSONObject()
                    .put("kind", "ip")
                    .put("mode", if (direct) "probe" else "nodeone")
                    .put("domain", domain)
                    .put("node", choice.node)
                    .put("target", target)
                    .put("ips", t.ip)
                    .put("ports", choice.ports)
                    .toString()

                val report = IpTestReport.parse(withClash { queryCustomConnections(CustomConnections.SORT_CHECK, request) })

                if (report.cancelled) break

                tested++
                design.ipProgress(targets.size, tested)

                val row = report.rows.firstOrNull()

                if (report.error.isNotEmpty() || row == null) {
                    design.ipLog(getString(R.string.iptest_log_error, report.error.ifEmpty { t.ip }))
                    continue
                }

                ipRows.add(row)

                val sub = if (direct) {
                    if (report.echSource.isNotEmpty()) {
                        getString(R.string.iptest_ech_source, report.echSource)
                    } else {
                        getString(R.string.iptest_ech_none)
                    }
                } else if (target.isNotEmpty()) {
                    getString(R.string.iptest_target_sub, target) // CUSTOM.md #66: no reference, the tries went to the target
                } else {
                    val ref = report.ref

                    if (ref != null) {
                        getString(R.string.iptest_ref, ref.ip, ref.ms, AgeText.testedAgo(this, ref.at))
                    } else {
                        getString(R.string.iptest_ref_none)
                    }
                }

                design.ipInfo(head, sub, warnings)
                design.ipRows(ipRows)

                if (row.ok) {
                    val open = row.ports.filter { it.ok }.joinToString(" ") { it.port.toString() }

                    design.ipLog(
                        if (direct) {
                            getString(R.string.iptest_log_ip_ok, row.ip, row.avg, row.loss, open.ifEmpty { "-" })
                        } else {
                            getString(R.string.iptest_log_ip_ok_noports, row.ip, row.avg, row.loss)
                        },
                    )

                    // CUSTOM.md #68: the jitter and where the time went (0 = not measured)
                    design.ipLog(
                        getString(
                            if (direct) R.string.iptest_log_split_direct else R.string.iptest_log_split_node,
                            row.ip,
                            row.jitter,
                            row.dial,
                            row.shake,
                            row.first,
                        ),
                    )

                    if (direct) {
                        design.ipLog(
                            if (row.asn.isNotEmpty()) {
                                getString(R.string.iptest_log_asn_ok, row.ip, row.asn)
                            } else {
                                getString(R.string.iptest_log_asn_none, row.ip)
                            },
                        )

                        // CUSTOM.md #62: the registered country comes with the operator, the exit country is asked through a
                        // node of the config whose server is the typed domain (no such node: said once, then silent)
                        if (row.geo.isNotEmpty()) {
                            design.ipLog(getString(R.string.iptest_log_geo, row.ip, CountryName.label(row.geo)))
                        }

                        if (report.landNode.isEmpty()) {
                            if (!landNoNodeSaid) {
                                landNoNodeSaid = true
                                design.ipLog(getString(R.string.iptest_log_land_no_node, domain))
                            }
                        } else if (row.country.isNotEmpty()) {
                            design.ipLog(getString(R.string.iptest_log_land_ok, row.ip, CountryName.label(row.country), report.landNode))
                        } else {
                            design.ipLog(getString(R.string.iptest_log_land_none, row.ip, report.landNode))
                        }
                    }

                    // CUSTOM.md #63: through a node: the data centre comes from one extra direct connection (SNI = the node's
                    // own domain), the operator and the registered country from the public service
                    if (!direct && target.isNotEmpty()) {
                        design.ipLog(
                            if (row.targetStatus == 0) {
                                getString(R.string.iptest_log_target_none, row.ip)
                            } else {
                                getString(R.string.iptest_log_target, row.ip, row.targetStatus, row.targetNote.ifEmpty { "-" }, row.targetColo.ifEmpty { "-" }, TargetStatusText.of(this@ConfigCheckActivity, row.targetStatus))
                            },
                        )

                        // CUSTOM.md #67: what the page itself says
                        when (row.targetSoft) {
                            "refresh", "script" -> design.ipLog(getString(R.string.iptest_log_soft_redirect, row.ip, row.targetSoftNote))
                            "region" -> design.ipLog(getString(R.string.iptest_log_soft_region, row.ip, row.targetSoftNote))
                        }
                    }

                    if (!direct && !row.isDomain) {
                        design.ipLog(
                            when {
                                row.colo.isNotEmpty() -> getString(R.string.iptest_log_node_colo_ok, row.ip, row.colo)
                                row.coloErr == "nosni" -> getString(R.string.iptest_log_node_colo_nosni, row.ip)
                                else -> getString(R.string.iptest_log_node_colo_fail, row.ip, row.coloErr.ifEmpty { "-" })
                            },
                        )

                        design.ipLog(
                            if (row.asn.isNotEmpty()) {
                                getString(R.string.iptest_log_asn_ok, row.ip, row.asn)
                            } else {
                                getString(R.string.iptest_log_asn_none, row.ip)
                            },
                        )

                        if (row.geo.isNotEmpty()) {
                            design.ipLog(getString(R.string.iptest_log_geo, row.ip, CountryName.label(row.geo)))
                        }
                    }
                } else {
                    design.ipLog(getString(R.string.iptest_log_ip_fail, row.ip, ErrorsText.explain(this, row.code), row.loss))
                }
            }

            design.ipLog(getString(R.string.iptest_log_done, ipRows.size))
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            failure = getString(R.string.check_error, e.message ?: e.javaClass.simpleName)

            if (begun) design.ipLog(failure) else design.showText(failure)
        } finally {
            ipTesting = false

            withContext(NonCancellable) { design.setIpRunning(false) }
        }
    }

    // the data centre list (code -> city, code -> country) of Cloudflare, fetched online once per round; without it the
    // data centres have no English name and no flag (the Chinese names are built in)
    private suspend fun fetchColos(design: ConfigCheckDesign) {
        try {
            val report = IpTestReport.parse(
                withClash {
                    queryCustomConnections(
                        CustomConnections.SORT_CHECK,
                        JSONObject().put("kind", "ip").put("mode", "colo").toString(),
                    )
                },
            )

            if (report.colos.isNotEmpty()) {
                design.ipColos(report.colos, report.coloCountries)
                design.ipLog(getString(R.string.iptest_log_colo_ok, report.colos.size))
            } else {
                design.ipLog(getString(R.string.iptest_log_colo_fail, report.error.removePrefix("colo: ").ifEmpty { "-" }))
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            design.ipLog(getString(R.string.iptest_log_colo_fail, e.message ?: e.javaClass.simpleName))
        }
    }

    // CUSTOM.md #96: write failures back to the matching IP row, even when the core returns no result row.
    private suspend fun setSpeedError(design: ConfigCheckDesign, ip: String, error: String) {
        val raw = error.ifBlank { "-" }
        ipRows = ArrayList(ipRows.map {
            if (it.ip == ip) it.copy(speed = 0.0, speedBytes = 0, speedMs = 0, speedErr = raw) else it
        })
        design.ipRows(ipRows)
    }

    // CUSTOM (CUSTOM.md #52): the download test of one row, only when its "测速" is tapped. Directly to the address,
    // or through the node in node mode. At most 10 MB or 5 seconds (the core stops it); "取消" stops it sooner.
    private suspend fun runSpeed(design: ConfigCheckDesign, ip: String) {
        val run = lastRun ?: return

        if (!clashRunning) {
            design.showText(getString(R.string.connections_not_running))
            return
        }

        speedIp = ip

        design.ipSpeedBusy(ip)
        design.ipLog(getString(R.string.iptest_log_speed_start, ip))

        try {
            val request = JSONObject()
                .put("kind", "ip")
                .put("mode", "download")
                .put("node", if (run.direct) "" else run.node)
                .put("ips", ip)
                .put("url", run.url)
                .toString()

            val report = IpTestReport.parse(withClash { queryCustomConnections(CustomConnections.SORT_CHECK, request) })
            val row = report.rows.firstOrNull()

            when {
                report.cancelled -> design.ipLog(getString(R.string.iptest_log_speed_cancelled, ip))
                report.error.isNotEmpty() || row == null -> {
                    val error = report.error.ifEmpty { "测速没有返回结果" }
                    setSpeedError(design, ip, error)
                    design.ipLog(getString(R.string.iptest_log_speed_fail, ip, error))
                }
                else -> {
                    ipRows = ArrayList(
                        ipRows.map {
                            if (it.ip == ip) {
                                it.copy(speed = row.speed, speedBytes = row.speedBytes, speedMs = row.speedMs, speedErr = row.speedErr)
                            } else {
                                it
                            }
                        },
                    )

                    design.ipRows(ipRows)

                    if (row.speedErr.isNotEmpty()) {
                        design.ipLog(getString(R.string.iptest_log_speed_fail, ip, row.speedErr))
                    } else {
                        design.ipLog(
                            getString(
                                R.string.iptest_log_speed_done,
                                ip,
                                String.format(java.util.Locale.US, "%.2f", row.speed),
                                String.format(java.util.Locale.US, "%.1f MB", row.speedBytes / 1e6),
                                row.speedMs,
                            ),
                        )
                    }
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            val error = e.message ?: e.javaClass.simpleName
            setSpeedError(design, ip, error)
            design.ipLog(getString(R.string.iptest_log_speed_fail, ip, error))
        } finally {
            withContext(NonCancellable) { design.ipSpeedBusy(null) }
        }
    }

    private suspend fun cancelSpeed(design: ConfigCheckDesign) {
        val running = speedJob?.takeIf { it.isActive } ?: return

        running.cancel()
        speedJob = null

        try {
            withClash { closeCustomConnection(CustomConnections.CANCEL_IPTEST) }
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
        }

        design.ipSpeedBusy(null)
        design.ipLog(getString(R.string.iptest_log_speed_cancelled, speedIp))
    }

    private suspend fun runCheck(design: ConfigCheckDesign) {
        val domain = design.domain()

        if (domain.isEmpty()) {
            design.showText(getString(R.string.check_need_domain))
            return
        }

        if (!clashRunning) {
            design.showText(getString(R.string.connections_not_running))
            return
        }

        design.setRunning(true)

        val text = try {
            val request = JSONObject().put("domain", domain).toString()

            ConfigCheckText.build(
                this,
                ConfigCheckReport.parse(withClash { queryCustomConnections(CustomConnections.SORT_CHECK, request) }),
            )
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            getString(R.string.check_error, e.message ?: e.javaClass.simpleName)
        }

        design.setRunning(false)
        design.showText(text)
    }

    // CUSTOM (CUSTOM.md #30): the config of the profile in use, with what the core and the app changed marked in
    // place. The core reads the config.yaml that is loaded (the saved one, not an unsaved edit), runs the same steps
    // it runs on loading and compares; nothing is loaded or downloaded. Runs once per tap, nothing in the background.
    private suspend fun runEffective(design: ConfigCheckDesign) {
        if (!clashRunning) {
            design.showText(getString(R.string.connections_not_running))
            return
        }

        design.showText(getString(R.string.eff_running))

        try {
            val profile = withProfile { queryActive() }

            if (profile == null) {
                design.showText(getString(R.string.eff_no_profile))
                return
            }

            val dir = importedDir.resolve(profile.uuid.toString())

            val config = EffectiveConfig.parse(
                withClash { queryCustomConnections(CustomConnections.SORT_EFFECTIVE, dir.absolutePath) },
            )

            if (config.ok) {
                design.showEffective(config)
            } else {
                design.showText(getString(R.string.eff_error, config.error))
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            design.showText(getString(R.string.eff_error, e.message ?: e.javaClass.simpleName))
        }
    }

    // CUSTOM (CUSTOM.md #26): clears the core's DNS cache, or the DNS cache and the fake-ip map. Runs only when
    // the user picks it in the dialog; the fake-ip map also closes every connection (the core does that).
    private suspend fun flush(design: ConfigCheckDesign) {
        if (!clashRunning) {
            design.showText(getString(R.string.connections_not_running))
            return
        }

        val choice = design.askFlush()

        if (choice == 0) return

        val id = if (choice == 2) CustomConnections.FLUSH_FAKEIP else CustomConnections.FLUSH_DNS

        val ok = try {
            withClash { closeCustomConnection(id) }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            false
        }

        design.showText(
            getString(
                when {
                    !ok -> R.string.check_flush_failed
                    choice == 2 -> R.string.check_flush_done_fakeip
                    else -> R.string.check_flush_done_dns
                },
            ),
        )
    }

    // CUSTOM (CUSTOM.md #25): the DNS check. Same rules as the ECH check: runs only when tapped, nothing in the background.
    private suspend fun runDns(design: ConfigCheckDesign) {
        val domain = design.domain()

        if (domain.isEmpty()) {
            design.showText(getString(R.string.check_dns_need_domain))
            return
        }

        if (!clashRunning) {
            design.showText(getString(R.string.connections_not_running))
            return
        }

        design.setRunning(true, dns = true)

        var failure = ""

        val report = try {
            val request = JSONObject().put("domain", domain).put("kind", "dns").toString()

            DnsCheckReport.parse(withClash { queryCustomConnections(CustomConnections.SORT_CHECK, request) })
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            failure = getString(R.string.check_error, e.message ?: e.javaClass.simpleName)

            null
        }

        design.setRunning(false)

        // CUSTOM.md #26 (r32): the page has lines that open on a tap
        if (report != null) design.showDns(report) else design.showText(failure)
    }
}
