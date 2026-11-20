package com.github.kr328.clash.design.custom

import java.util.Locale

// CUSTOM.md #160: country-code filtering is kept pure so UI state does not leak into saved-IP storage.
internal fun savedIpCountryCodes(operators: List<SavedIpOperator>): List<String> =
    operators.asSequence()
        .flatMap { it.ips.asSequence() }
        .map { it.countryCode.trim().uppercase(Locale.US) }
        .filter { code ->
            code.length == 2 && code[0] in 'A'..'Z' && code[1] in 'A'..'Z'
        }
        .distinct()
        .sortedBy { CountryName.label(it) }
        .toList()

internal fun filterSavedIpEntriesByCountry(
    entries: List<SavedIpEntry>,
    selectedCountryCodes: Set<String>,
): List<SavedIpEntry> {
    if (selectedCountryCodes.isEmpty()) return entries
    return entries.filter { it.countryCode.trim().uppercase(Locale.US) in selectedCountryCodes }
}
