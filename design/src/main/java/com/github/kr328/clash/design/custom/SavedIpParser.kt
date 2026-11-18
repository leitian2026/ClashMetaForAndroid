package com.github.kr328.clash.design.custom

import java.net.Inet6Address
import java.net.InetAddress

/** Result of extracting addresses from arbitrary pasted text. */
data class SavedIpExtraction(
    val addresses: List<String>,
    val duplicates: Int,
    val ignored: Int,
)

/**
 * CUSTOM.md #156 step 3: extracts literal IP addresses without DNS resolution.
 *
 * IPv4 candidates are checked octet by octet. IPv6 is passed to InetAddress only after every character has been
 * restricted to hexadecimal digits, colon and dot, and only when the candidate contains a colon.
 */
object SavedIpParser {
    private val ipv4Pattern = Regex("(?<![0-9.])(?:[0-9]{1,3}\\.){3}[0-9]{1,3}(?![0-9.])")
    private val bracketedIpv6Pattern = Regex("\\[([0-9A-Fa-f:.]+)]")
    private val separators = Regex("[\\s,;，；|(){}<>\\\"'`]+")

    fun extract(text: String): SavedIpExtraction {
        val addresses = LinkedHashSet<String>()
        var duplicates = 0
        var ignored = 0

        for (token in text.split(separators).filter { it.isNotBlank() }) {
            var found = false

            for (match in bracketedIpv6Pattern.findAll(token)) {
                val address = normalizeIpv6(match.groupValues[1])
                if (address != null) {
                    found = true
                    if (!addresses.add(address)) duplicates++
                }
            }

            for (match in ipv4Pattern.findAll(token)) {
                val address = normalizeIpv4(match.value)
                if (address != null) {
                    found = true
                    if (!addresses.add(address)) duplicates++
                }
            }

            if (!found && token.all { it in "0123456789abcdefABCDEF:." } && ':' in token) {
                val address = normalizeIpv6(token)
                if (address != null) {
                    found = true
                    if (!addresses.add(address)) duplicates++
                }
            }

            if (!found) ignored++
        }

        return SavedIpExtraction(addresses.toList(), duplicates, ignored)
    }

    private fun normalizeIpv4(value: String): String? {
        val parts = value.split('.')
        if (parts.size != 4) return null

        val octets = ArrayList<Int>(4)
        for (part in parts) {
            if (part.isEmpty() || part.length > 3 || part.any { !it.isDigit() }) return null
            val octet = part.toIntOrNull()?.takeIf { it in 0..255 } ?: return null
            octets += octet
        }

        return octets.joinToString(".")
    }

    private fun normalizeIpv6(value: String): String? {
        if (value.isEmpty() || ':' !in value || value.any { it !in "0123456789abcdefABCDEF:." }) return null

        val address = try {
            // This call is deliberately unreachable for hostnames or arbitrary text.
            InetAddress.getByName(value) as? Inet6Address
        } catch (_: Exception) {
            null
        } ?: return null

        val bytes = address.address
        if (bytes.size != 16) return null
        val words = IntArray(8) { index ->
            ((bytes[index * 2].toInt() and 0xff) shl 8) or (bytes[index * 2 + 1].toInt() and 0xff)
        }

        var bestStart = -1
        var bestLength = 0
        var index = 0
        while (index < words.size) {
            if (words[index] != 0) {
                index++
                continue
            }
            val start = index
            while (index < words.size && words[index] == 0) index++
            val length = index - start
            if (length >= 2 && length > bestLength) {
                bestStart = start
                bestLength = length
            }
        }

        if (bestStart < 0) return words.joinToString(":") { it.toString(16) }

        val left = words.take(bestStart).joinToString(":") { it.toString(16) }
        val right = words.drop(bestStart + bestLength).joinToString(":") { it.toString(16) }
        return when {
            left.isEmpty() && right.isEmpty() -> "::"
            left.isEmpty() -> "::$right"
            right.isEmpty() -> "$left::"
            else -> "$left::$right"
        }
    }
}
