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
import android.os.PowerManager
import com.github.kr328.clash.util.withClash
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject

/** CUSTOM.md #156 step 3/4: local saved-IP management and the hand-off to the existing IP test flow. */
class SavedIpActivity : BaseActivity<SavedIpDesign>() {
    private var page: SavedIpDesign? = null
    private var restoredState: Bundle? = null
    private var configuredIpSources: Map<String, Set<String>> = emptyMap()
    private var configReadJob: Job? = null
    private var savedIpReadJob: Job? = null
    private var savedIpLoaded = false
    private var speedJob: Job? = null
    private var pageVisible = false
    private data class SpeedTarget(val operatorId: String, val address: String, val direct: Boolean)
    private var activeSpeedTarget: SpeedTarget? = null
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
        loadSavedIpSnapshot()
    }

    // CUSTOM.md #156: show the page shell first; load and parse the saved list only while this page is started.
    private fun loadSavedIpSnapshot() {
        val targetPage = page ?: return
        if (!activityStarted || savedIpLoaded || savedIpReadJob?.isActive == true) return
        savedIpReadJob = launch(Dispatchers.IO) {
            val (snapshot, version) = SavedIpStore.readVersioned(this@SavedIpActivity)
            withContext(Dispatchers.Main) {
                if (page === targetPage && !isFinishing && !isDestroyed) {
                    targetPage.setInitialSnapshot(snapshot, version)
                    savedIpLoaded = true
                }
            }
        }
    }

    // CUSTOM.md #156: read the active profile's configured IP sources once on each page start.
    // Reuse the existing read-only conftest mode="config" path; failures and an unloaded config stay silent.
    override fun onStart() {
        super.onStart()
        loadSavedIpSnapshot()
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
        savedIpReadJob?.cancel()
        savedIpReadJob = null
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
        savedIpReadJob?.cancel()
        savedIpReadJob = null
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
        pageVisible = true
        page?.setSpeedState(clashRunning, IpSpeedTestCoordinator.isBusy)
        page?.refreshIfDataChanged()
        activeSpeedTarget?.let { target ->
            val progress = IpSpeedTestCoordinator.currentProgress()?.takeIf {
                it.savedOperatorId == target.operatorId && it.ip == target.address && it.direct == target.direct
            }?.percent
            page?.updateSpeedProgress(target.operatorId, target.address, target.direct, progress)
        }
    }

    override fun onPause() {
        pageVisible = false
        super.onPause()
    }

    private fun screenInteractive(): Boolean =
        (getSystemService(POWER_SERVICE) as? PowerManager)?.isInteractive == true

    // CUSTOM #158: reuse the existing conftest mode=download request path for saved-IP speed actions.
    private fun startSavedSpeed(operatorId: String, address: String, direct: Boolean) {
        if (!clashRunning) {
            Toast.makeText(this, R.string.saved_ip_speed_not_running, Toast.LENGTH_SHORT).show()
            return
        }
        if (!IpSpeedTestCoordinator.tryAcquire()) return
        activeSpeedTarget = SpeedTarget(operatorId, address, direct)
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
                val progressObserver = IpDownloadProgressObserver.create { bytes, elapsedMs ->
                    val progress = IpSpeedTestCoordinator.updateProgress(address, direct, bytes, elapsedMs, operatorId)
                    if (pageVisible && screenInteractive()) {
                        runOnUiThread {
                            if (pageVisible && screenInteractive()) page?.updateSpeedProgress(operatorId, address, direct, progress.percent)
                        }
                    }
                }
                val report = IpDownloadSpeed.run(address, node, url) { request ->
                    withClash { queryCustomConnectionsWithProgress(CustomConnections.SORT_CHECK, request, progressObserver) }
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
                SavedIpStore.updateSpeed(this@SavedIpActivity, operatorId, address, direct, row.speed, row.speedShort)
                page?.updateSpeedResult(operatorId, address, direct, row.speed, row.speedShort)
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
                IpSpeedTestCoordinator.clearProgress()
                if (pageVisible && screenInteractive()) page?.updateSpeedProgress(operatorId, address, direct, null)
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
