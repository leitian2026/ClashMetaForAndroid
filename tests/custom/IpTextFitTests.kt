package com.github.kr328.clash.design.custom.editor

import com.github.kr328.clash.design.custom.IpTextFit

object IpTextFitTests {
    fun run() {
        val unchanged = IpTextFit.fit("2001:db8::1", 100f, 18f, 12f) { text, size ->
            text.length * size
        }
        check(unchanged.text == "2001:db8::1" && unchanged.textSize == 18f, "IPv6 text stays unchanged when it fits")

        val smaller = IpTextFit.fit("2001:db8::1", 15f, 2f, 1f) { text, size ->
            text.length * size
        }
        check(smaller.text == "2001:db8::1" && smaller.textSize in 1f..2f && smaller.textSize < 2f,
            "IPv6 text shrinks before it is shortened")

        val shortened = IpTextFit.fit("2001:0db8:85a3:0000:0000:8a2e:0370:7334", 12f, 2f, 1f) { text, size ->
            text.length * size
        }
        check(shortened.text.contains("…") && shortened.text.startsWith("2001:") && shortened.text.endsWith(":7334"),
            "long IPv6 text keeps both ends around a middle ellipsis")
        check(shortened.text.length <= 12, "middle-ellipsized IPv6 text fits the available width")

        val noSpace = IpTextFit.fit("2001:db8::1", 0f, 18f, 12f) { text, size ->
            text.length * size
        }
        check(noSpace.text.isEmpty(), "zero width produces no text")
    }
}
