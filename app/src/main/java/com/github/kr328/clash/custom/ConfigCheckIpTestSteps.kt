package com.github.kr328.clash.custom

import com.github.kr328.clash.core.model.IpTestReport
import com.github.kr328.clash.core.model.IpTestRow
import com.github.kr328.clash.common.custom.NetworkIpv6
import com.github.kr328.clash.design.custom.IpTestIpv6
import com.github.kr328.clash.design.custom.ConfigCheckDesign
import com.github.kr328.clash.design.custom.IpTestChoice
import com.github.kr328.clash.design.custom.CountryName
import com.github.kr328.clash.design.R
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.random.Random
import org.json.JSONObject

// changes.md #175
class ConfigCheckIpTestSteps {
    data class Prepared(
        val choice: IpTestChoice,
        val direct: Boolean,
        val domain: String,
        val target: String,
        val warnings: List<String>,
        val head: String,
        val targets: List<IpTestRow>,
    )

    private data class LoopState(
        val ownerQueue: Channel<String>,
        val ownerJob: Job,
        var landNoNodeSaid: Boolean = false,
        var tested: Int = 0,
        val exitOwnerFailureLogged: HashSet<String> = HashSet(),
    )

    suspend fun prepare(
        design: ConfigCheckDesign,
        presetIps: String?,
        query: suspend (String) -> IpTestReport,
        cancelResolve: suspend () -> Unit,
        format: (Int, Array<out Any>) -> String,
    ): Prepared? {
        fun message(id: Int, vararg args: Any): String = format(id, args)

        val nodes = query(JSONObject().put("kind", "ip").put("mode", "nodes").toString()).nodes
        val choice = design.askIpTest(
            nodes,
            readConfig = {
                try {
                    query(JSONObject().put("kind", "ip").put("mode", "config").toString())
                } catch (e: CancellationException) {
                    throw e
                } catch (_: Exception) {
                    null
                }
            },
            presetIps = presetIps,
            resolveDomains = { domains ->
                try {
                    query(JSONObject().put("kind", "ip").put("mode", "resolve").put("domains", domains).toString())
                } catch (e: CancellationException) {
                    throw e
                } catch (_: Exception) {
                    null
                }
            },
            normalizeIps = { ips ->
                try {
                    query(JSONObject().put("kind", "ip").put("mode", "normalize").put("ips", ips).toString())
                } catch (e: CancellationException) {
                    throw e
                } catch (_: Exception) {
                    null
                }
            },
            cancelResolve = {
                try {
                    cancelResolve()
                } catch (e: CancellationException) {
                    throw e
                } catch (_: Exception) {
                }
            },
        ) ?: return null

        if (choice.ips.isBlank()) {
            design.showText(message(R.string.iptest_need_ips))
            return null
        }
        val residualDomains = choice.ips.split(Regex("[\\s,;，；、]+" )).filter { isResidualDomainToken(it) }.distinct()
        if (residualDomains.isNotEmpty()) {
            design.showText(message(R.string.iptest_resolve_before_test, residualDomains.joinToString("、")))
            return null
        }
        val direct = choice.mode == "direct"
        val domain = if (direct) design.domain() else ""
        if (direct && domain.isEmpty()) {
            design.showText(message(R.string.iptest_need_domain))
            return null
        }
        if (!direct && choice.node.isEmpty()) {
            design.showText(message(R.string.iptest_need_node))
            return null
        }
        val target = if (direct) "" else choice.target
        if (target.isNotEmpty() && !Regex("^https?://[^/\\s]+.*$", RegexOption.IGNORE_CASE).matches(target)) {
            design.showText(message(R.string.iptest_target_bad))
            return null
        }
        val parsed = query(
            JSONObject().put("kind", "ip").put("mode", "parse").put("direct", direct)
                .put("ips", choice.ips).toString(),
        )
        when (parsed.error) {
            "no address" -> {
                design.showText(message(R.string.iptest_need_ips))
                return null
            }
            "domain only node" -> {
                design.showText(message(R.string.iptest_domain_node_only))
                return null
            }
        }
        // Read the underlying network once for this round. Through-node tests are never filtered by this result.
        val hasIpv6 = NetworkIpv6.hasIpv6(design.root.context)
        val targets = parsed.rows.map { row ->
            if (IpTestIpv6.shouldSkip(row.ip, direct, hasIpv6)) {
                row.copy(code = IpTestIpv6.NO_IPV6_CODE, reason = message(R.string.iptest_no_ipv6_reason))
            } else {
                row
            }
        }
        val noIpv6Count = targets.count { it.code == IpTestIpv6.NO_IPV6_CODE }
        val warnings = ArrayList<String>()
        if (noIpv6Count > 0) warnings += message(R.string.iptest_no_ipv6_warning, noIpv6Count)
        if (parsed.skipped.isNotEmpty()) warnings += message(R.string.iptest_skipped, parsed.skipped.joinToString("、"))
        if (parsed.invalid.isNotEmpty()) warnings += message(R.string.iptest_invalid, parsed.invalid.joinToString("、"))
        if (parsed.cut > 0) warnings += message(R.string.iptest_cut, parsed.cut)
        if (parsed.duplicates > 0) warnings += message(R.string.iptest_duplicates_removed, parsed.duplicates)
        val head = if (direct) message(R.string.iptest_head_direct, domain) else message(R.string.iptest_head_node, choice.node)
        return Prepared(choice, direct, domain, target, warnings, head, targets)
    }

    suspend fun begin(
        design: ConfigCheckDesign,
        prepared: Prepared,
        resetRound: () -> Unit,
        markBegun: () -> Unit,
        markRunning: () -> Unit,
        format: (Int, Array<out Any>) -> String,
    ) {
        fun message(id: Int, vararg args: Any): String = format(id, args)
        val choice = prepared.choice
        resetRound()
        design.ipBegin(prepared.direct, prepared.head, "", prepared.warnings, prepared.target.isNotEmpty())
        design.ipSaveContext(prepared.domain, choice.node, choice.ports, choice.url)
        markBegun()
        design.setIpRunning(true)
        markRunning()
        design.ipProgress(prepared.targets.size, 0)
        design.ipLog(
            message(
                R.string.iptest_log_start,
                prepared.targets.size,
                if (prepared.direct) message(R.string.iptest_log_mode_direct, prepared.domain)
                else message(R.string.iptest_log_mode_node, choice.node),
            ),
        )
    }

    suspend fun runLoop(
        design: ConfigCheckDesign,
        direct: Boolean,
        domain: String,
        choice: IpTestChoice,
        target: String,
        head: String,
        warnings: List<String>,
        targets: List<IpTestRow>,
        rows: () -> ArrayList<IpTestRow>,
        addRow: (IpTestRow) -> Unit,
        query: suspend (String) -> IpTestReport,
        fetchColos: suspend () -> Unit,
        ownerLookup: suspend (String) -> Unit,
        format: (Int, Array<out Any>) -> String,
    ) {
        fun message(id: Int, vararg args: Any): String = format(id, args)
        fetchColos()
        val ownerQueue = Channel<String>(Channel.UNLIMITED)
        val state = LoopState(
            ownerQueue,
            CoroutineScope(currentCoroutineContext()).launch {
                for (ip in ownerQueue) ownerLookup(ip)
            },
        )
        try {
            for ((index, item) in targets.withIndex()) {
                if (item.code == IpTestIpv6.NO_IPV6_CODE) {
                    addRow(item)
                    design.ipRows(rows())
                    design.ipLog(message(R.string.iptest_log_no_ipv6, item.ip))
                    continue
                }
                if (!runTarget(index, item, targets.size, design, direct, domain, choice, target,
                        head, warnings, state, rows, addRow, query, format)) break
            }
        } finally {
            state.ownerQueue.close()
            state.ownerJob.join()
        }
        design.ipLog(message(R.string.iptest_log_done, rows().size))
    }

    private suspend fun runTarget(
        index: Int,
        item: IpTestRow,
        total: Int,
        design: ConfigCheckDesign,
        direct: Boolean,
        domain: String,
        choice: IpTestChoice,
        target: String,
        head: String,
        warnings: List<String>,
        state: LoopState,
        rows: () -> ArrayList<IpTestRow>,
        addRow: (IpTestRow) -> Unit,
        query: suspend (String) -> IpTestReport,
        format: (Int, Array<out Any>) -> String,
    ): Boolean {
        fun message(id: Int, vararg args: Any): String = format(id, args)
        if (index > 0) delay(Random.nextLong(1000, 3001))
        design.ipLog(message(R.string.iptest_log_ip_start, index + 1, total, item.ip))
        val request = JSONObject()
            .put("kind", "ip")
            .put("mode", if (direct) "probe" else "nodeone")
            .put("domain", domain)
            .put("node", choice.node)
            .put("target", target)
            .put("ips", item.ip)
            .put("ports", choice.ports)
            .put("ext", if (direct) choice.ext.joinToString(",") else "")
            .put("wsPath", if (direct && "ws" in choice.ext) choice.wsPath else "")
            .toString()
        val report = query(request)
        if (report.cancelled) return false
        state.tested++
        design.ipProgress(total, state.tested)
        val row = report.rows.firstOrNull()
        if (report.error.isNotEmpty() || row == null) {
            design.ipLog(message(R.string.iptest_log_error, report.error.ifEmpty { item.ip }))
            return true
        }
        addRow(row)
        publishRow(row, report, design, direct, domain, choice, target, head, warnings,
            state, rows, format)
        return true
    }

    private suspend fun publishRow(
        row: IpTestRow,
        report: IpTestReport,
        design: ConfigCheckDesign,
        direct: Boolean,
        domain: String,
        choice: IpTestChoice,
        target: String,
        head: String,
        warnings: List<String>,
        state: LoopState,
        rows: () -> ArrayList<IpTestRow>,
        format: (Int, Array<out Any>) -> String,
    ) {
        fun message(id: Int, vararg args: Any): String = format(id, args)
        if (direct && row.mtu.tested) {
            row.mtu.detail.lineSequence().filter { it.isNotBlank() }.forEach { step ->
                design.ipLog(message(R.string.iptest_log_mtu_step, row.ip, step))
            }
        }
        val contextSub = if (direct) {
            if (report.echSource.isNotEmpty()) message(R.string.iptest_ech_source, report.echSource)
            else message(R.string.iptest_ech_none)
        } else if (target.isNotEmpty()) {
            message(R.string.iptest_target_sub, target)
        } else ""
        val measureNote = message(if (direct) R.string.iptest_measure_direct_note else R.string.iptest_measure_node_note)
        design.ipInfo(head, listOf(contextSub, measureNote).filter { it.isNotEmpty() }.joinToString("\n"), warnings)
        design.ipRows(rows())
        if (!direct && target.isNotEmpty()) {
            design.ipLog(message(R.string.iptest_log_target_protocol, row.ip,
                row.targetAlpn.ifEmpty { "<empty>" }, row.targetHttpProto.ifEmpty { "<empty>" }))
        }
        if (row.ok) {
            logSuccessfulRow(row, report, design, direct, domain, target, state, format)
        } else {
            val rawReason = row.reason.ifBlank { row.code.ifBlank { "-" } }
            design.ipLog(message(R.string.iptest_log_ip_fail, row.ip,
                message(R.string.iptest_handshake_failed) + rawReason, row.loss))
        }
    }

    private suspend fun logSuccessfulRow(
        row: IpTestRow,
        report: IpTestReport,
        design: ConfigCheckDesign,
        direct: Boolean,
        domain: String,
        target: String,
        state: LoopState,
        format: (Int, Array<out Any>) -> String,
    ) {
        fun message(id: Int, vararg args: Any): String = format(id, args)
        val open = row.ports.filter { it.ok }.joinToString(" ") { it.port.toString() }
        design.ipLog(
            if (direct) message(R.string.iptest_log_ip_ok, row.ip, row.avg, row.loss, open.ifEmpty { "-" })
            else message(R.string.iptest_log_ip_ok_noports, row.ip, row.avg, row.loss),
        )
        if (!direct && row.exitIp.isNotEmpty() && row.exitAsn.isEmpty() && state.exitOwnerFailureLogged.add(row.exitIp)) {
            design.ipLog(message(R.string.iptest_log_exit_asn_none, row.exitIp))
        }
        if (direct) {
            design.ipLog(message(R.string.iptest_log_split_direct, row.ip, row.jitter, row.dial, row.shake, row.first))
            if (report.landNode.isEmpty()) {
                if (!state.landNoNodeSaid) {
                    state.landNoNodeSaid = true
                    design.ipLog(message(R.string.iptest_log_land_no_node, domain))
                }
            } else if (row.country.isNotEmpty()) {
                design.ipLog(message(R.string.iptest_log_land_ok, row.ip, CountryName.label(row.country), report.landNode))
            } else {
                design.ipLog(message(R.string.iptest_log_land_none, row.ip, report.landNode))
            }
        }
        if (!direct && target.isNotEmpty()) {
            if (row.targetStatus == 0) design.ipLog(message(R.string.iptest_log_target_none, row.ip))
            when (row.targetSoft) {
                "refresh", "script" -> design.ipLog(message(R.string.iptest_log_soft_redirect, row.ip, row.targetSoftNote))
                "region" -> design.ipLog(message(R.string.iptest_log_soft_region, row.ip, row.targetSoftNote))
            }
        }
        if (!direct && !row.isDomain && row.colo.isEmpty()) {
            val text = if (row.coloErr == "nosni") message(R.string.iptest_log_node_colo_nosni, row.ip)
            else message(R.string.iptest_log_node_colo_fail, row.ip, row.coloErr.ifEmpty { "-" })
            design.ipLog(text)
        }
        if (!row.isDomain) state.ownerQueue.send(row.ip)
    }

    private fun isResidualDomainToken(raw: String): Boolean {
        var value = raw.trim()
        if (value.count { it == ':' } == 1) value = value.substringBeforeLast(':')
        value = value.trimEnd('.')
        val labels = value.split('.')
        if (labels.size < 2 || labels.any { it.isEmpty() || it.length > 63 || it.first() == '-' || it.last() == '-' }) return false
        if (labels.last().none { it in 'a'..'z' || it in 'A'..'Z' }) return false
        return labels.all { label ->
            label.all { it in 'a'..'z' || it in 'A'..'Z' || it in '0'..'9' || it == '-' }
        }
    }
}
