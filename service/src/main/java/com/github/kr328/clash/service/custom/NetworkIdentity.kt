package com.github.kr328.clash.service.custom

import android.content.Context
import android.net.Network
import android.net.NetworkCapabilities
import android.telephony.TelephonyManager
import android.telephony.SubscriptionManager
import android.os.Build
import androidx.core.content.getSystemService
import com.github.kr328.clash.common.custom.CarrierOperatorConsensus
import com.github.kr328.clash.common.custom.CarrierOperatorVote
import com.github.kr328.clash.common.custom.NetworkIdentitySnapshot
import com.github.kr328.clash.common.custom.NetworkIpv6
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.InetAddress
import java.net.URL
import java.net.SocketTimeoutException
import java.nio.charset.Charset
import java.util.Locale
import java.util.concurrent.Executors

/** CUSTOM (CUSTOM.md #168): one lookup for the selected network below the VPN. */
object NetworkIdentity {
    private const val TIMEOUT_MS = 5000
    private const val MAX_BODY = 4096
    private const val USER_AGENT = "Mozilla/5.0 (Linux; Android) AppleWebKit/537.36 Chrome/120 Mobile Safari/537.36"

    private data class Answer(
        val ip: String,
        val operator: String,
        val province: String = "",
        val city: String = "",
        val source: String,
        val rawOperator: String = "",
    )
    private data class RequestResult(val answer: Answer?, val timedOut: Boolean, val bindFailed: Boolean = false)

    fun identify(context: Context, signature: String): NetworkIdentitySnapshot {
        val network = NetworkIpv6.bestNetwork(context)
            ?: return failed(signature, "no_network")

        val cellular = try {
            val caps = context.getSystemService<android.net.ConnectivityManager>()?.getNetworkCapabilities(network)
            caps?.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) == true
        } catch (_: Exception) { false }

        if (cellular) {
            val local = readMobileOperator(context)
            if (local != null) {
                return NetworkIdentitySnapshot(
                    signature = signature,
                    operator = local,
                    source = "mobile_mcc_mnc",
                    diagnostic = "SIM/MCC-MNC：$local；IPIP：未查询；PConline：未查询；Bilibili：未查询",
                    checkedAt = System.currentTimeMillis(),
                )
            }
        }

        // Run the providers together; total wait is bounded by one timeout window.
        val executor = Executors.newFixedThreadPool(3)
        val requests = try {
            val ipip = executor.submit<RequestResult> {
                request(network, "https://myip.ipip.net") { body -> parseIpip(body) }
            }
            val pconline = executor.submit<RequestResult> {
                request(network, "https://whois.pconline.com.cn/ipJson.jsp?json=true") { body -> parsePconline(body) }
            }
            val bilibili = executor.submit<RequestResult> {
                request(network, "https://api.bilibili.com/x/web-interface/zone") { body -> parseBilibili(body) }
            }
            listOf(ipip.get(), pconline.get(), bilibili.get())
        } finally {
            executor.shutdownNow()
        }
        val answers = requests.mapNotNull { it.answer }
        val sourceNames = listOf("IPIP", "PConline", "Bilibili")
        val diagnostic = requests.mapIndexed { index, result ->
            val outcome = result.answer?.let { answer ->
                buildString {
                    append(answer.operator)
                    if (answer.ip.isNotEmpty()) append(" · ").append(answer.ip)
                    if (answer.rawOperator.isNotEmpty()) append("（").append(answer.rawOperator).append("）")
                }
            } ?: when {
                result.timedOut -> "超时"
                result.bindFailed -> "网络绑定失败"
                else -> "无有效结果"
            }
            "${sourceNames[index]}：$outcome"
        }.joinToString("\n")
        val votes = answers.map { CarrierOperatorVote(it.source, it.operator) }
        val consensus = CarrierOperatorConsensus.resolve(votes)

        if (consensus != null) {
            val answer = answers.firstOrNull {
                it.operator == consensus.operator && (it.province.isNotEmpty() || it.city.isNotEmpty())
            } ?: answers.first { it.operator == consensus.operator }
            return NetworkIdentitySnapshot(
                signature = signature,
                operator = consensus.operator,
                province = answer.province,
                city = answer.city,
                source = "online_consensus:${consensus.sources.joinToString("+")}",
                rawOperator = answer.rawOperator,
                diagnostic = diagnostic,
                checkedAt = System.currentTimeMillis(),
            )
        }

        val error = when {
            CarrierOperatorConsensus.hasDisagreement(votes) -> "operator_mismatch"
            requests.all { it.bindFailed } -> "bind_failed"
            requests.any { it.timedOut } -> "timeout"
            else -> "all_failed"
        }
        return failed(signature, error, if (cellular) "online_fallback" else "online", diagnostic)
    }

    private fun readMobileOperator(context: Context): String? {
        return try {
            val base = context.getSystemService<TelephonyManager>() ?: return null
            val tm = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                val subscriptionId = SubscriptionManager.getDefaultDataSubscriptionId()
                if (subscriptionId >= 0) base.createForSubscriptionId(subscriptionId) else base
            } else {
                base
            }
            val code = tm.networkOperator?.takeIf { it.length >= 5 } ?: return null
            val mccMnc = code.take(5)
            when (mccMnc) {
                "46000", "46002", "46004", "46007", "46008" -> "移动"
                "46001", "46006", "46009", "46010" -> "联通"
                "46003", "46005", "46011", "46012" -> "电信"
                "46015" -> "广电"
                else -> null
            }
        } catch (_: SecurityException) {
            null
        } catch (_: Exception) {
            null
        }
    }

    private fun request(network: Network, address: String, parse: (String) -> Answer?): RequestResult {
        var connection: HttpURLConnection? = null
        return try {
            val opened = try {
                network.openConnection(URL(address))
            } catch (_: Exception) {
                return RequestResult(null, false, true)
            }
            val http = opened as? HttpURLConnection ?: return RequestResult(null, false, true)
            connection = http
            http.connectTimeout = TIMEOUT_MS
            http.readTimeout = TIMEOUT_MS
            http.instanceFollowRedirects = false
            http.setRequestProperty("User-Agent", USER_AGENT)
            http.setRequestProperty("Accept", "text/plain,application/json")
            http.connect()
            if (http.responseCode !in 200..299) return RequestResult(null, false)
            val body = http.inputStream.use { input ->
                val out = java.io.ByteArrayOutputStream()
                val buffer = ByteArray(1024)
                var total = 0
                while (true) {
                    val count = input.read(buffer)
                    if (count < 0) break
                    total += count
                    if (total > MAX_BODY) return RequestResult(null, false)
                    out.write(buffer, 0, count)
                }
                decode(out.toByteArray(), http.contentType)
            }
            RequestResult(parse(body), false)
        } catch (_: SocketTimeoutException) {
            RequestResult(null, true)
        } catch (_: Exception) {
            RequestResult(null, false)
        } finally {
            connection?.disconnect()
        }
    }

    private fun decode(bytes: ByteArray, contentType: String?): String {
        val charsetName = Regex("charset\\s*=\\s*[\"']?([^;\"' ]+)", RegexOption.IGNORE_CASE)
            .find(contentType.orEmpty())?.groupValues?.getOrNull(1)
        val charset = try { Charset.forName(charsetName ?: "UTF-8") } catch (_: Exception) { Charsets.UTF_8 }
        val text = String(bytes, charset)
        return if (text.contains('�')) String(bytes, Charset.forName("GBK")) else text
    }

    private fun parseIpip(body: String): Answer? {
        val ip = findPublicIp(body) ?: return null
        val tail = body.substringAfter(ip).substringAfter("来自于", "").trim()
        if (tail.isEmpty()) return Answer(ip, "未知", source = "ipip")
        val tokens = tail.split(Regex("[\\s,，]+")).filter { it.isNotBlank() }
        val operator = classify(tokens.lastOrNull().orEmpty()) ?: "未知"
        val locations = tokens.dropLast(1).filterNot { it in setOf("中国", "China", "CN") }
        return Answer(ip, operator, locations.firstOrNull().orEmpty(), locations.drop(1).joinToString(""), "ipip", tokens.lastOrNull().orEmpty())
    }

    private fun parsePconline(body: String): Answer? {
        val json = try { JSONObject(body) } catch (_: Exception) { return null }
        val ip = findPublicIp(json.optString("ip").ifEmpty { body }) ?: return null
        val province = json.optString("pro").takeUnless { it == "null" }.orEmpty()
        val city = json.optString("city").takeUnless { it == "null" }.orEmpty()
        val address = json.optString("addr")
        var rawText = address.removePrefix("中国")
        if (province.isNotEmpty()) rawText = rawText.replaceFirst(province, "")
        if (city.isNotEmpty()) rawText = rawText.replaceFirst(city, "")
        rawText = rawText.trim().trimStart('省', '市', '区', ' ')
        val rawOperator = rawText.split(Regex("[\\s,，]+")).lastOrNull()?.takeIf { it.isNotBlank() }
            ?: address.split(Regex("[\\s,，]+")).lastOrNull().orEmpty()
        val operator = classify(rawOperator) ?: "未知"
        return Answer(ip, operator, province, city, "pconline", rawOperator)
    }

    private fun parseBilibili(body: String): Answer? {
        val json = try { JSONObject(body) } catch (_: Exception) { return null }
        if (json.optInt("code", -1) != 0) return null
        val data = json.optJSONObject("data") ?: return null
        val ip = findPublicIp(data.optString("addr")) ?: return null
        val province = data.optString("province").takeUnless { it == "null" }.orEmpty()
        val city = data.optString("city").takeUnless { it == "null" }.orEmpty()
        val rawOperator = data.optString("isp").takeUnless { it == "null" }.orEmpty()
        val operator = classify(rawOperator) ?: "未知"
        return Answer(ip, operator, province, city, "bilibili", rawOperator)
    }

    private fun classify(raw: String): String? {
        val value = raw.lowercase(Locale.ROOT)
        return when {
            listOf("联通", "unicom", "cnc").any { it in value } -> "联通"
            listOf("电信", "telecom", "chinanet").any { it in value } -> "电信"
            listOf("移动", "mobile", "cmnet", "cmcc").any { it in value } -> "移动"
            listOf("广电", "broadcast").any { it in value } -> "广电"
            else -> null
        }
    }

    private fun findPublicIp(text: String): String? {
        val candidates = Regex("(?<![0-9a-fA-F:.])(?:[0-9]{1,3}\\.){3}[0-9]{1,3}(?![0-9])|(?<![0-9a-fA-F:])[0-9a-fA-F]{0,4}:[0-9a-fA-F:]{2,}(?![0-9a-fA-F:])")
            .findAll(text)
        for (match in candidates) {
            val value = match.value
            val address = try { InetAddress.getByName(value) } catch (_: Exception) { continue }
            if (address.isAnyLocalAddress || address.isLoopbackAddress || address.isLinkLocalAddress || address.isSiteLocalAddress || address.isMulticastAddress) continue
            if (address.address.size == 4) {
                val b = address.address.map { it.toInt() and 0xff }
                if (b[0] == 0 || b[0] == 10 || b[0] == 127 || b[0] >= 224) continue
                if (b[0] == 169 && b[1] == 254) continue
                if (b[0] == 172 && b[1] in 16..31) continue
                if (b[0] == 192 && b[1] == 168) continue
                if (b[0] == 100 && b[1] in 64..127) continue
                if (b[0] == 192 && b[1] == 0 && b[2] == 2) continue
                if (b[0] == 198 && b[1] == 51 && b[2] == 100) continue
                if (b[0] == 203 && b[1] == 0 && b[2] == 113) continue
                if (b[0] == 198 && b[1] in 18..19) continue
            } else {
                val first = address.address[0].toInt() and 0xff
                if (first and 0xfe == 0xfc || first == 0xff) continue
                if (value.lowercase(Locale.ROOT).startsWith("2001:db8:")) continue
            }
            return value
        }
        return null
    }

    private fun failed(signature: String, error: String, source: String = "", diagnostic: String = "") =
        NetworkIdentitySnapshot(
            signature = signature,
            error = error,
            source = source,
            diagnostic = diagnostic,
            checkedAt = System.currentTimeMillis(),
        )
}
