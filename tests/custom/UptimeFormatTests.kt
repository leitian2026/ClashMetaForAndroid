package com.github.kr328.clash.design.custom.editor

import com.github.kr328.clash.common.custom.UptimeFormat

object UptimeFormatTests {
    fun run() {
        minutesBetween()
        unknownStart()
        zhFormat()
        enFormat()
    }

    private fun minutesBetween() {
        check(UptimeFormat.minutes(1_000L, 1_000L) == 0L, "uptime: same instant is 0 minutes")
        check(UptimeFormat.minutes(0L, 59_999L) == 0L, "uptime: 59.999 s still 0 minutes")
        check(UptimeFormat.minutes(0L, 60_000L) == 1L, "uptime: 60 s is 1 minute")
        check(UptimeFormat.minutes(5_000L, 5_000L + 83L * 60_000L + 30_000L) == 83L, "uptime: 83 min 30 s is 83 minutes")
    }

    private fun unknownStart() {
        check(UptimeFormat.minutes(-1L, 10_000L) == -1L, "uptime: unknown start is -1")
        check(UptimeFormat.minutes(20_000L, 10_000L) == -1L, "uptime: start after now is -1")
        check(UptimeFormat.format(-1L, "天", "小时", "分", "") == "", "uptime: negative minutes give empty text")
    }

    private fun zhFormat() {
        fun f(m: Long) = UptimeFormat.format(m, "天", "小时", "分", "")
        check(f(0L) == "0分", "uptime zh: 0 minutes")
        check(f(23L) == "23分", "uptime zh: under an hour")
        check(f(60L) == "1小时0分", "uptime zh: exactly one hour")
        check(f(83L) == "1小时23分", "uptime zh: 1h23m")
        check(f(1439L) == "23小时59分", "uptime zh: just under a day")
        check(f(1440L) == "1天0小时0分", "uptime zh: exactly one day")
        check(f(2L * 1440L + 83L) == "2天1小时23分", "uptime zh: 2d1h23m")
    }

    private fun enFormat() {
        fun f(m: Long) = UptimeFormat.format(m, "d", "h", "m", " ")
        check(f(23L) == "23m", "uptime en: under an hour")
        check(f(83L) == "1h 23m", "uptime en: 1h 23m")
        check(f(2L * 1440L + 83L) == "2d 1h 23m", "uptime en: 2d 1h 23m")
    }
}
