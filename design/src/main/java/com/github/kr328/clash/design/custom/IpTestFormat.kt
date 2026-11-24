package com.github.kr328.clash.design.custom

import android.content.Context
import android.graphics.Typeface
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.ForegroundColorSpan
import android.text.style.StyleSpan
import com.github.kr328.clash.core.model.IpTestRow
import com.github.kr328.clash.design.R
import java.util.Locale

private val CERTIFICATE_DATE_REGEX = Regex("""\d{4}-\d{2}-\d{2}""")
private val HTTP_STATUS_REGEX = Regex("\\bHTTP(?:/\\d(?:\\.\\d)?)?\\s+(\\d{3})\\b", RegexOption.IGNORE_CASE)
internal fun certificateIssuerName(value: String): String {
    val parts = value.split(',').map { it.trim() }
    val commonName = parts.firstOrNull { it.startsWith("CN=", ignoreCase = true) }?.substringAfter('=')
    val organization = parts.firstOrNull { it.startsWith("O=", ignoreCase = true) }?.substringAfter('=')
    return when {
        !commonName.isNullOrBlank() && !organization.isNullOrBlank() -> "$commonName ($organization)"
        !commonName.isNullOrBlank() -> commonName
        !organization.isNullOrBlank() -> organization
        else -> value
    }
}
internal fun certificateDate(value: String): String = CERTIFICATE_DATE_REGEX.find(value)?.value ?: value
internal fun markdownCell(value: String): String = value.replace("|", "\\|").replace("\r", " ").replace("\n", " ")
internal fun hostOf(location: String): String {
    val host = try { java.net.URI(location).host } catch (_: Exception) { null }
    return host ?: location.take(30)
}
internal fun portsPlain(r: IpTestRow): String =
    if (r.ports.isEmpty()) "-" else r.ports.joinToString(" ") { (if (it.ok) "" else "✗") + it.port }
internal fun add(sb: SpannableStringBuilder, text: String, color: Int? = null, bold: Boolean = false) {
    val start = sb.length
    sb.append(text)
    if (color != null) sb.setSpan(ForegroundColorSpan(color), start, sb.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
    if (bold) sb.setSpan(StyleSpan(Typeface.BOLD), start, sb.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
}
internal fun extText(text: String, color: Int): CharSequence {
    val sb = SpannableStringBuilder()
    add(sb, text, color)
    return sb
}
internal fun handshakeFailureText(context: Context, reason: String): String =
    context.getString(R.string.iptest_handshake_failed) + reason
internal fun latencyColor(ms: Int, colorGood: Int, colorWarn: Int, colorBad: Int): Int = when {
    ms < 150 -> colorGood
    ms < 300 -> colorWarn
    else -> colorBad
}
internal fun jitterColor(ms: Int, colorGood: Int, colorWarn: Int, colorBad: Int): Int = when {
    ms < 30 -> colorGood
    ms < 100 -> colorWarn
    else -> colorBad
}
internal fun lossColor(loss: Int, colorGood: Int, colorWarn: Int, colorBad: Int): Int = when {
    loss == 0 -> colorGood
    loss < 50 -> colorWarn
    else -> colorBad
}
internal fun targetColor(r: IpTestRow, colorBad: Int, colorMuted: Int, colorWarn: Int, colorGood: Int): Int = when {
    !r.ok -> colorBad
    r.targetStatus == 0 -> colorMuted
    r.targetSoft == "region" -> colorBad
    r.targetSoft == "refresh" || r.targetSoft == "script" -> colorWarn
    r.targetStatus in 200..299 -> colorGood
    r.targetStatus in 300..399 -> colorWarn
    else -> colorBad
}
internal fun explain(context: Context, code: String, reason: String): String {
    val text = context.getString(when (code) {
        "timeout" -> R.string.check_code_timeout
        "refused" -> R.string.check_code_refused
        "reset" -> R.string.check_code_reset
        "eof" -> R.string.check_code_eof
        "dns" -> R.string.check_code_dns
        "network" -> R.string.check_code_network
        "tls" -> R.string.check_code_tls
        "auth" -> R.string.check_code_auth
        "reject" -> R.string.check_code_reject
        "echrejected" -> R.string.check_code_echrejected
        else -> return context.getString(R.string.check_failed_raw, ErrorsText.explain(context, code), reason.ifEmpty { "-" })
    })
    return context.getString(R.string.check_failed, text)
}
internal fun savedFailureSummary(context: Context, row: IpTestRow): String {
    val reason = row.reason.lowercase(Locale.ROOT)
    val category = when {
        "certificate" in reason || "x509" in reason -> "证书校验失败"
        "alpn" in reason -> "ALPN 不匹配"
        "context canceled" in reason || "context cancelled" in reason ||
            "operation was canceled" in reason || "operation was cancelled" in reason -> "已取消"
        else -> when (row.code) {
            "timeout" -> "连接超时"
            "refused" -> "连接被拒绝"
            "reset" -> "连接被重置"
            "eof" -> "连接提前关闭"
            "dns" -> "DNS 解析失败"
            "network" -> "网络不可达"
            "tls" -> "TLS 握手失败"
            "auth" -> "认证失败"
            "reject" -> "规则拒绝"
            "echrejected" -> "ECH 被拒绝"
            else -> ""
        }
    }
    return if (category.isEmpty()) context.getString(R.string.iptest_handshake_failed).trimEnd('：', ':', ' ')
    else handshakeFailureText(context, category)
}
internal fun targetText(context: Context, r: IpTestRow): String {
    if (!r.ok) return handshakeFailureText(context, r.reason.ifBlank { r.code.ifBlank { "-" } })
    if (r.targetStatus == 0) return "-"
    val note = if (r.targetNote.isEmpty()) "" else " → " + hostOf(r.targetNote)
    val word = TargetStatusText.of(context, r.targetStatus).let { if (it.isEmpty()) "" else " " + it }
    return r.targetStatus.toString() + word + note + softLine(context, r)
}
internal fun pageHintText(context: Context, row: IpTestRow): String {
    val existing = row.targetSoftNote.ifBlank { row.targetSoft }
    if (existing.isNotBlank()) return existing
    if (row.targetPageHint.isBlank()) return ""
    val title = row.targetPageHint.substringBefore('\n')
    val body = row.targetPageHint.substringAfter('\n', "").ifBlank { "-" }
    return if (title.isNotBlank()) context.getString(R.string.iptest_page_hint, title, body)
    else context.getString(R.string.iptest_page_hint_no_title, body)
}
internal fun softLine(context: Context, r: IpTestRow): String = when (r.targetSoft) {
    "refresh", "script" -> "\n" + context.getString(R.string.iptest_soft_redirect, hostOf(r.targetSoftNote))
    "region" -> "\n" + context.getString(R.string.iptest_soft_region)
    else -> ""
}
internal fun portDetail(context: Context, r: IpTestRow): String {
    val sb = StringBuilder()
    for (p in r.ports) sb.append(if (p.ok) context.getString(R.string.iptest_port_ok, p.port, p.ms)
        else context.getString(R.string.iptest_port_fail, p.port, explain(context, p.code, p.reason))).append('\n')
    if (r.ports.isEmpty()) sb.append(context.getString(R.string.iptest_port_none)).append('\n')
    sb.append('\n').append(context.getString(R.string.iptest_port_note))
    return sb.toString()
}
internal fun extShortReason(context: Context, reason: String): String {
    val lower = reason.lowercase()
    if (lower.contains("deadline exceeded") || lower.contains("timeout") || lower.contains("timed out"))
        return context.getString(R.string.iptest_ext_timeout)
    return if (reason.length > 16) reason.take(16) + "…" else reason
}
internal fun coloHead(code: String, coloCountries: Map<String, String>): String {
    val flag = CountryFlag.of(coloCountries[code.uppercase(Locale.US)])
    return if (flag.isEmpty()) code else "$flag $code"
}
internal fun coloTail(code: String, colos: Map<String, String>): String {
    val up = code.uppercase(Locale.US)
    val names = listOfNotNull(ColoNames.cn(up), colos[up]).filter { it.isNotEmpty() }.distinct()
    return if (names.isEmpty()) "" else "（" + names.joinToString(" ") + "）"
}
internal fun coloLabel(code: String, colos: Map<String, String>, coloCountries: Map<String, String>): String =
    if (code.isEmpty()) "-" else coloHead(code, coloCountries) + coloTail(code, colos)
internal fun phaseText(context: Context, r: IpTestRow, directMode: Boolean): String {
    if (!r.ok) return "-"
    val v = { n: Int -> if (n > 0) context.getString(R.string.iptest_ms_short, n) else "-" }
    return context.getString(if (directMode) R.string.iptest_phase_cell_direct else R.string.iptest_phase_cell_node,
        v(r.dial), v(r.shake), v(r.first))
}
internal fun speedText(context: Context, r: IpTestRow, speedIp: String?): String = when {
    speedIp == r.ip -> "…"
    r.speed > 0 -> context.getString(R.string.iptest_speed_cell, String.format(Locale.US, "%.1f", r.speed))
    r.speedErr.isNotEmpty() -> {
        val status = HTTP_STATUS_REGEX.find(r.speedErr)?.groupValues?.get(1)
        val lower = r.speedErr.lowercase(Locale.US)
        val reason = when {
            status != null -> "HTTP $status"
            lower.contains("timeout") || lower.contains("deadline exceeded") -> context.getString(R.string.iptest_speed_timeout)
            lower.contains("no data") -> context.getString(R.string.iptest_speed_no_data)
            else -> r.speedErr.trim().take(20)
        }
        context.getString(R.string.iptest_speed_failed) + "\n" + reason
    }
    else -> "-"
}
