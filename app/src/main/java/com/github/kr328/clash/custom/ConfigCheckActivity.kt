package com.github.kr328.clash.custom

import com.github.kr328.clash.BaseActivity
import com.github.kr328.clash.core.custom.CustomConnections
import com.github.kr328.clash.core.model.ConfigCheckReport
import com.github.kr328.clash.core.model.DnsCheckReport
import com.github.kr328.clash.design.custom.ConfigCheckDesign
import com.github.kr328.clash.design.custom.ConfigCheckText
import com.github.kr328.clash.design.custom.DnsCheckText
import com.github.kr328.clash.design.R
import com.github.kr328.clash.util.withClash
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

    override suspend fun main() {
        val design = ConfigCheckDesign(this)

        setContentDesign(design)

        while (isActive) {
            select<Unit> {
                design.requests.onReceive {
                    when (it) {
                        ConfigCheckDesign.Request.Copy -> design.copyAll()
                        ConfigCheckDesign.Request.Toggle, ConfigCheckDesign.Request.Dns -> {
                            val running = job?.isActive == true

                            if (running) {
                                job?.cancel()
                                job = null
                                design.setRunning(false)
                                design.showText(getString(R.string.check_cancelled))
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

        val text = try {
            val request = JSONObject().put("domain", domain).put("kind", "dns").toString()

            DnsCheckText.build(
                this,
                DnsCheckReport.parse(withClash { queryCustomConnections(CustomConnections.SORT_CHECK, request) }),
            )
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            getString(R.string.check_error, e.message ?: e.javaClass.simpleName)
        }

        design.setRunning(false)
        design.showText(text)
    }
}
