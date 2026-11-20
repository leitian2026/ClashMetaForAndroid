package com.github.kr328.clash.custom

import android.content.Intent
import android.os.Bundle
import com.github.kr328.clash.BaseActivity
import com.github.kr328.clash.core.custom.CustomConnections
import com.github.kr328.clash.core.model.IpTestReport
import com.github.kr328.clash.design.custom.SavedIpDesign
import com.github.kr328.clash.util.withClash
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import org.json.JSONObject

/** CUSTOM.md #156 step 3/4: local saved-IP management and the hand-off to the existing IP test flow. */
class SavedIpActivity : BaseActivity<SavedIpDesign>() {
    private var page: SavedIpDesign? = null
    private var restoredState: Bundle? = null
    private var configuredHostsIps: Set<String> = emptySet()
    private var hostsReadJob: Job? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        restoredState = savedInstanceState
        super.onCreate(savedInstanceState)
    }

    override fun onSaveInstanceState(outState: Bundle) {
        page?.saveState(outState)
        super.onSaveInstanceState(outState)
    }

    override suspend fun main() {
        val canRetest = intent.getBooleanExtra(EXTRA_CAN_RETEST, false)
        val design = SavedIpDesign(
            this,
            onBack = { finish() },
            canRetest = canRetest,
            savedState = restoredState,
            onRetest = { operatorId, addresses ->
                // CUSTOM.md #156 step 25: reuse the existing config-check page instead of stacking test pages.
                startActivity(
                    Intent(this, ConfigCheckActivity::class.java)
                        .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
                        .putExtra(EXTRA_PRESET_IPS, addresses.joinToString("\n"))
                        .putExtra(EXTRA_OPERATOR_ID, operatorId),
                )
            },
        )
        page = design
        setContentDesign(design)
        design.setHostsIps(configuredHostsIps)
    }

    // CUSTOM.md #156: read the active profile's configured node/hosts IPs once on each page start.
    // Reuse the existing read-only conftest mode="config" path; failures and an unloaded config stay silent.
    override fun onStart() {
        super.onStart()
        hostsReadJob?.cancel()
        hostsReadJob = launch {
            val ips = try {
                if (!clashRunning) emptySet() else {
                    val request = JSONObject().put("kind", "ip").put("mode", "config").toString()
                    IpTestReport.parse(
                        withClash { queryCustomConnections(CustomConnections.SORT_CHECK, request) },
                    ).config.mapNotNull { it.ip.trim().takeIf(String::isNotEmpty) }.toSet()
                }
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                emptySet()
            }
            configuredHostsIps = ips
            page?.setHostsIps(ips)
        }
    }

    override fun onStop() {
        hostsReadJob?.cancel()
        hostsReadJob = null
        super.onStop()
    }

    // CUSTOM.md #156 step 24: refresh the existing page when it is reordered to the front.
    override fun onBackPressed() {
        if (page?.handleBackPressed() != true) super.onBackPressed()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        page?.setCanRetest(intent.getBooleanExtra(EXTRA_CAN_RETEST, false))
    }

    override fun onResume() {
        super.onResume()
        page?.refresh()
    }

    companion object {
        const val EXTRA_CAN_RETEST = "saved_ip_can_retest"
        const val EXTRA_PRESET_IPS = "saved_ip_preset_ips"
        const val EXTRA_OPERATOR_ID = "saved_ip_operator_id"
    }
}
