package com.github.kr328.clash.design.custom

import java.util.Locale

// CUSTOM (CUSTOM.md #62, not upstream): "US" -> "🇺🇸 美国" (the flag and the country's name in the language of the app,
// from the system's own list of country names, so there is no table of names in the app). A code that is not two letters
// gives "-"; a code the system does not know is shown as it is.
object CountryName {
    fun label(code: String?): String {
        if (code.isNullOrEmpty()) return "-"

        val up = code.uppercase(Locale.US)
        val flag = CountryFlag.of(up)

        if (flag.isEmpty()) return "-"

        val name = Locale("", up).getDisplayCountry(Locale.getDefault())
        val text = if (name.isNullOrEmpty() || name.equals(up, ignoreCase = true)) up else name

        return "$flag $text"
    }
}
