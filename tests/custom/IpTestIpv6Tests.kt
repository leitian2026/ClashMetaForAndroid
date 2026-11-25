package com.github.kr328.clash.design.custom.editor

import com.github.kr328.clash.design.custom.IpTestIpv6

object IpTestIpv6Tests {
    fun run() {
        check(IpTestIpv6.isIPv6Address("2001:db8::1"), "IPv6 address is recognized")
        check(!IpTestIpv6.isIPv6Address("192.0.2.1"), "IPv4 address is not recognized as IPv6")

        check(IpTestIpv6.shouldSkip("2001:db8::1", direct = true, hasIpv6 = false),
            "direct IPv6 target is skipped without IPv6 connectivity")
        check(!IpTestIpv6.shouldSkip("192.0.2.1", direct = true, hasIpv6 = false),
            "direct IPv4 target is retained without IPv6 connectivity")
        check(!IpTestIpv6.shouldSkip("2001:db8::1", direct = true, hasIpv6 = true),
            "direct IPv6 target is retained when IPv6 is available")
        check(!IpTestIpv6.shouldSkip("2001:db8::1", direct = false, hasIpv6 = false),
            "via-node IPv6 target is never skipped based on the local network")
    }
}
