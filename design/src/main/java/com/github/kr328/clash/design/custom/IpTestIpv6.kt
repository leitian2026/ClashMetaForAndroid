package com.github.kr328.clash.design.custom

object IpTestIpv6 {
    const val NO_IPV6_CODE = "noipv6"

    fun isIPv6Address(value: String): Boolean = value.indexOf(':') >= 0

    fun shouldSkip(value: String, direct: Boolean, hasIpv6: Boolean): Boolean =
        direct && !hasIpv6 && isIPv6Address(value)
}
