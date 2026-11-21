package com.github.kr328.clash.custom

import android.content.Intent
import android.os.Bundle
import com.github.kr328.clash.BaseActivity
import com.github.kr328.clash.core.custom.CustomConnections
import com.github.kr328.clash.core.model.IpTestReport
import com.github.kr328.clash.design.R
import com.github.kr328.clash.design.custom.SavedIpDesign
import com.github.kr328.clash.design.custom.SavedIpStore
import android.widget.Toast
import com.github.kr328.clash.util.withClash
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import org.json.JSONObject

/** CUSTOM.md #156 step 3/4: local saved-IP management and the hand-off to the existing IP test flow. */
class SavedIpActivity : BaseActivity<SavedIpDesign>() {
    private var page: SavedIpDesign? = null
    private var restoredState: Bundle? = null
    private var configuredIpSources: Map<String, Set<String>> = emptyMap()
    private var configReadJob: Job? = null
    private var speedJob: Job? = null
    private var removeSpeedListener: (() -> Unit)? = null
    private val speedStateListener: (Boolean) -> Unit = { busy ->
        runOnUiThread { page?.setSpeedState(clashRunning, busy) }
    }

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
            onSpeedTest = { operatorId, address, direct -> startSavedSpeed(operatorId, address, direct) },
            speedAvailable = clashRunning,
            speedBusy = IpSpeedTestCoordinator.isBusy,
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
        design.setConfigIpSources(configuredIpSources)
    }

    // CUSTOM.md #156: read the active profile's configured IP sources once on each page start.
    // Reuse the existing read-only conftest mode="config" path; failures and an unloaded config stay silent.
    override fun onStart() {
        super.onStart()
        removeSpeedListener?.invoke()
        removeSpeedListener = IpSpeedTestCoordinator.addListener(speedStateListener)
        page?.setSpeedState(clashRunning, IpSpeedTestCoordinator.isBusy)
        configReadJob?.cancel()
        configReadJob = launch {
            val sources: Map<String, Set<String>> = try {
                if (!clashRunning) emptyMap() else {
                    val request = JSONObject().put("kind", "ip").put("mode", "config").toString()
                    IpTestReport.parse(
                        withClash { queryCustomConnections(CustomConnections.SORT_CHECK, request) },
                    ).config.filter { it.ip.isNotBlank() }
                        .groupBy({ it.ip.trim() }, { it.via })
                        .mapValues { (_, vias) -> vias.toSet() }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                emptyMap()
            }
            configuredIpSources = sources
            page?.setConfigIpSources(sources)
        }
    }

    override fun onStop() {
        removeSpeedListener?.invoke()
        removeSpeedListener = null
        page?.cancelPendingSearchRender()
        page?.savePersistentUiState()
        configReadJob?.cancel()
        configReadJob = null
        super.onStop()
    }

    // CUSTOM.md #156 step 24: refresh the existing page when it is reordered to the front.
    override fun onBackPressed() {
        if (page?.handleBackPressed() != true) super.onBackPressed()
    }

    override fun onDestroy() {
        removeSpeedListener?.invoke()
        removeSpeedListener = null
        speedJob?.cancel()
        page?.disposeUndo()
        super.onDestroy()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        page?.setCanRetest(intent.getBooleanExtra(EXTRA_CAN_RETEST, false))
    }

    override fun onResume() {
        super.onResume()
        page?.setSpeedState(clashRunning, IpSpeedTestCoordinator.isBusy)
        page?.refreshIfDataChanged()
    }

    // CUSTOM #158: reuse the existing conftest mode=download request path for saved-IP speed actions.
    private fun startSavedSpeed(operatorId: String, address: String, direct: Boolean) {
        if (!clashRunning) {
            Toast.makeText(this, R.string.saved_ip_speed_not_running, Toast.LENGTH_SHORT).show()
            return
        }
        if (!IpSpeedTestCoordinator.tryAcquire()) return
        page?.setSpeedState(clashRunning, true)
        speedJob = launch {
            try {
                val prefs = getSharedPreferences("custom_config_check", MODE_PRIVATE)
                val node = if (direct) "" else runCatching { prefs.getString("ip_node", "").orEmpty() }.getOrDefault("")
                if (!direct) {
                    if (node.isBlank()) {
                        Toast.makeText(this@SavedIpActivity, R.string.saved_ip_speed_need_node, Toast.LENGTH_LONG).show()
                        return@launch
                    }
                    val nodes = try {
                        IpTestReport.parse(
                            withClash {
                                queryCustomConnections(
                                    CustomConnections.SORT_CHECK,
                                    JSONObject().put("kind", "ip").put("mode", "nodes").toString(),
                                )
                            },
                        ).nodes
                    } catch (e: CancellationException) {
                        throw e
                    } catch (_: Exception) {
                        emptyList()
                    }
                    if (node !in nodes) {
                        Toast.makeText(this@SavedIpActivity, R.string.saved_ip_speed_need_node, Toast.LENGTH_LONG).show()
                        return@launch
                    }
                }
                val defaultUrl = if (direct) IpDownloadSpeed.DEFAULT_URL_DIRECT else IpDownloadSpeed.DEFAULT_URL_NODE
                val url = prefs.getString(if (direct) "ip_url_direct" else "ip_url_node", defaultUrl)
                    .orEmpty().ifBlank { defaultUrl }
                val report = IpDownloadSpeed.run(address, node, url) { request ->
                    withClash { queryCustomConnections(CustomConnections.SORT_CHECK, request) }
                }
                if (report.cancelled) return@launch
                val row = report.rows.firstOrNull()
                if (report.error.isNotEmpty() || row == null) {
                    Toast.makeText(
                        this@SavedIpActivity,
                        getString(R.string.saved_ip_speed_failed, report.error.ifBlank { "No result" }),
                        Toast.LENGTH_LONG,
                    ).show()
                    return@launch
                }
                SavedIpStore.updateSpeed(this@SavedIpActivity, operatorId, address, direct, row.speed)
                page?.refreshIfDataChanged()
                if (row.speedErr.isNotEmpty()) {
                    Toast.makeText(this@SavedIpActivity, getString(R.string.saved_ip_speed_failed, row.speedErr), Toast.LENGTH_LONG).show()
                } else {
                    val label = getString(if (direct) R.string.saved_ip_speed_direct else R.string.saved_ip_speed_node)
                    Toast.makeText(
                        this@SavedIpActivity,
                        getString(R.string.saved_ip_speed_done, label, String.format(java.util.Locale.US, "%.1f", row.speed)),
                        Toast.LENGTH_SHORT,
                    ).show()
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Toast.makeText(
                    this@SavedIpActivity,
                    getString(R.string.saved_ip_speed_failed, e.message ?: e.javaClass.simpleName),
                    Toast.LENGTH_LONG,
                ).show()
            } finally {
                IpSpeedTestCoordinator.setBusy(false)
                page?.setSpeedState(clashRunning, IpSpeedTestCoordinator.isBusy)
            }
        }
    }

    companion object {
        const val EXTRA_CAN_RETEST = "saved_ip_can_retest"
        const val EXTRA_PRESET_IPS = "saved_ip_preset_ips"
        const val EXTRA_OPERATOR_ID = "saved_ip_operator_id"
    }
}
