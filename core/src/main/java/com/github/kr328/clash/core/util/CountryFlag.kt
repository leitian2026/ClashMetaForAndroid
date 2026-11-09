package com.github.kr328.clash.core.util

// CUSTOM (CUSTOM.md #1, #7; lives in :core so both :design and :service can use it): "US" -> the flag emoji, "" for anything that is not a 2-letter code.
object CountryFlag {
    fun of(code: String?): String {
        if (code == null || code.length != 2) return ""

        val a = code[0].uppercaseChar()
        val b = code[1].uppercaseChar()

        if (a !in 'A'..'Z' || b !in 'A'..'Z') return ""

        val base = 0x1F1E6 - 'A'.code

        return String(Character.toChars(base + a.code)) + String(Character.toChars(base + b.code))
    }
}
