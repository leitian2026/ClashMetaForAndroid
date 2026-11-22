package com.github.kr328.clash.service.custom

import android.content.Context
import android.net.Network
import android.net.NetworkCapabilities
import android.telephony.TelephonyManager
import android.telephony.SubscriptionManager
import android.os.Build
import androidx.core.content.getSystemService
import com.github.kr328.clash.common.custom.NetworkIdentitySnapshot
import com.github.kr328.clash.common.custom.NetworkIpv6
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.InetAddress
import java.net.URL
import java.net.SocketTimeoutException
import java.nio.charset.Charset
import java.util.Locale

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
                    signature = signature, operator = local, source = "mobile_mcc_mnc", checkedAt = System.currentTimeMillis(),
                )
            }
        }

        val ipip = request(network, "https://myip.ipip.net") { body -> parseIpip(body) }
        val pconline = request(network, "https://whois.pconline.com.cn/ipJson.jsp?json=true") { body -> parsePconline(body) }

        if (ipip.answer != null && pconline.answer != null && ipip.answer.operator != pconline.answer.operator) {
            return failed(signature, "operator_mismatch", "online")
        }

        val answer = ipip.answer ?: pconline.answer
        if (answer != null) {
            return NetworkIdentitySnapshot(
                signature = signature,
                operator = answer.operator,
                province = answer.province,
                city = answer.city,
                source = answer.source,
                checkedAt = System.currentTimeMillis(),
            )
        }

        val error = when {
            ipip.bindFailed && pconline.bindFailed -> "bind_failed"
            ipip.timedOut || pconline.timedOut -> "timeout"
            else -> "all_failed"
        }
        return failed(signature, error, if (cellular) "online_fallback" else "online")
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
            connection = opened as? HttpURLConnection ?: return RequestResult(null, false, true)
            connection.connectTimeout = TIMEOUT_MS
            connection.readTimeout = TIMEOUT_MS
            connection.instanceFollowRedirects = false
            connection.setRequestProperty("User-Agent", USER_AGENT)
            connection.setRequestProperty("Accept", "text/plain,application/json")
            connection.connect()
            if (connection.responseCode !in 200..299) return RequestResult(null, false)
            val body = connection.inputStream.use { input ->
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
                decode(out.toByteArray(), connection.contentType)
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
        if (tail.isEmpty()) return null
        val tokens = tail.split(Regex("[\\s,，]+")).filter { it.isNotBlank() }
        val operator = classify(tokens.lastOrNull().orEmpty()) ?: "未知"
        val locations = tokens.dropLast(1).filterNot { it in setOf("中国", "China", "CN") }
        return Answer(ip, operator, locations.firstOrNull().orEmpty(), locations.drop(1).joinToString(""), "ipip")
    }

    private fun parsePconline(body: String): Answer? {
        val json = try { JSONObject(body) } catch (_: Exception) { return null }
        val ip = findPublicIp(json.optString("ip").ifEmpty { body }) ?: return null
        val province = json.optString("pro").takeUnless { it == "null" }.orEmpty()
        val city = json.optString("city").takeUnless { it == "null" }.orEmpty()
        val address = json.optString("addr")
        val operator = classify(address) ?: "未知"
        return Answer(ip, operator, province, city, "pconline")
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
            } else {
                val first = address.address[0].toInt() and 0xff
                if (first and 0xfe == 0xfc || first == 0xff) continue
            }
            return value
        }
        return null
    }

    private fun failed(signature: String, error: String, source: String = "") =
        NetworkIdentitySnapshot(signature = signature, error = error, source = source, checkedAt = System.currentTimeMillis())
}
