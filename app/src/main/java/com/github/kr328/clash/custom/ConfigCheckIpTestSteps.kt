package com.github.kr328.clash.custom

import com.github.kr328.clash.core.custom.CustomConnections
import com.github.kr328.clash.core.model.IpTestReport
import com.github.kr328.clash.design.custom.ConfigCheckDesign
import com.github.kr328.clash.design.custom.IpTestChoice
import com.github.kr328.clash.design.R
import kotlinx.coroutines.CancellationException
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
        val targets: List<com.github.kr328.clash.core.model.IpTestConfigIp>,
    )

    suspend fun prepare(
        design: ConfigCheckDesign,
        presetIps: String?,
        query: suspend (String) -> IpTestReport,
        format: (Int, Array<out Any?>) -> String,
    ): Prepared? {
        fun message(id: Int, vararg args: Any): String = format(id, args)

        val nodes = query(
            JSONObject().put("kind", "ip").put("mode", "nodes").toString(),
        ).nodes

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
                    // The cancellation request uses the same core command as before.
                    query(JSONObject().put("kind", "ip").put("mode", "cancel").toString())
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

        val warnings = ArrayList<String>()
        if (parsed.skipped.isNotEmpty()) warnings += message(R.string.iptest_skipped, parsed.skipped.joinToString("、"))
        if (parsed.invalid.isNotEmpty()) warnings += message(R.string.iptest_invalid, parsed.invalid.joinToString("、"))
        if (parsed.cut > 0) warnings += message(R.string.iptest_cut, parsed.cut)
        if (parsed.duplicates > 0) warnings += message(R.string.iptest_duplicates_removed, parsed.duplicates)
        val head = if (direct) {
            message(R.string.iptest_head_direct, domain)
        } else {
            message(R.string.iptest_head_node, choice.node)
        }
        return Prepared(choice, direct, domain, target, warnings, head, parsed.rows)
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
