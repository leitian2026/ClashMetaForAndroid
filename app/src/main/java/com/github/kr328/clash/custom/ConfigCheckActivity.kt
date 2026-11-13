package com.github.kr328.clash.custom

import com.github.kr328.clash.BaseActivity
import com.github.kr328.clash.core.custom.CustomConnections
import com.github.kr328.clash.core.model.ConfigCheckReport
import com.github.kr328.clash.core.model.DnsCheckReport
import com.github.kr328.clash.core.model.EffectiveConfig
import com.github.kr328.clash.core.model.IpTestReport
import com.github.kr328.clash.design.custom.ConfigCheckDesign
import com.github.kr328.clash.design.custom.ConfigCheckText
import com.github.kr328.clash.design.R
import com.github.kr328.clash.service.util.importedDir
import com.github.kr328.clash.util.withClash
import com.github.kr328.clash.util.withProfile
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.selects.select
import org.json.JSONObject

// CUSTOM (CUSTOM.md #22): the config check page. A test runs only when the user taps the button, and
// stops being waited for when they tap it again (the core finishes its own attempts, each at most a few
// seconds). Nothing runs on its own, no timer.
class ConfigCheckActivity : BaseActivity<ConfigCheckDesign>() {
    private var job: Job? = null

    // CUSTOM.md #43: the running job is a "test other IPs"; the core has to be told to stop it
    private var ipTesting = false

    override suspend fun main() {
        val design = ConfigCheckDesign(this)

        setContentDesign(design)

        // CUSTOM.md #43: a tap on "look up" of a row of the IP test
        design.onLookup = { ip -> launch { lookupOwner(design, ip) } }

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
                            if (job?.isActive == true) {
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
        design.showText(getString(R.string.check_cancelled))
    }

    // CUSTOM (CUSTOM.md #43): test other IPs. A dialog asks for the addresses, the node and the way to test: straight
    // to the address ("direct", uses the domain field of the page as SNI and Host) or through the node with its server
    // replaced by the address ("node"). Runs only when tapped; the core stops when the button is tapped again.
    private suspend fun runIpTest(design: ConfigCheckDesign) {
        if (!clashRunning) {
            design.showText(getString(R.string.connections_not_running))
            return
        }

        var failure = ""

        val report = try {
            val nodes = IpTestReport.parse(
                withClash {
                    queryCustomConnections(
                        CustomConnections.SORT_CHECK,
                        JSONObject().put("kind", "ip").put("mode", "nodes").toString(),
                    )
                },
            ).nodes

            val choice = design.askIpTest(nodes) ?: return

            if (choice.ips.isBlank()) {
                design.showText(getString(R.string.iptest_need_ips))
                return
            }

            val domain = if (choice.mode == "direct") design.domain() else ""

            if (choice.mode == "direct" && domain.isEmpty()) {
                design.showText(getString(R.string.iptest_need_domain))
                return
            }

            if (choice.mode == "node" && choice.node.isEmpty()) {
                design.showText(getString(R.string.iptest_need_node))
                return
            }

            val request = JSONObject()
                .put("kind", "ip")
                .put("mode", choice.mode)
                .put("domain", domain)
                .put("node", choice.node)
                .put("ips", choice.ips)
                .toString()

            design.setIpRunning(true)
            ipTesting = true

            IpTestReport.parse(withClash { queryCustomConnections(CustomConnections.SORT_CHECK, request) })
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            failure = getString(R.string.check_error, e.message ?: e.javaClass.simpleName)

            null
        }

        ipTesting = false
        design.setIpRunning(false)

        when {
            report == null -> design.showText(failure)
            report.error == "no address" -> design.showText(getString(R.string.iptest_need_ips))
            report.error == "domain only node" -> design.showText(getString(R.string.iptest_domain_node_only))
            report.error.isNotEmpty() -> design.showText(getString(R.string.check_error, report.error))
            else -> design.showIpTest(report)
        }
    }

    // CUSTOM.md #43: who runs this address, asked from public services only when the user taps "look up" on its row
    private suspend fun lookupOwner(design: ConfigCheckDesign, ip: String) {
        val owner = try {
            val request = JSONObject().put("kind", "ip").put("mode", "asn").put("ips", ip).toString()

            IpTestReport.parse(withClash { queryCustomConnections(CustomConnections.SORT_CHECK, request) })
                .rows.firstOrNull()?.asn.orEmpty()
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            ""
        }

        design.setIpOwner(ip, owner)
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
