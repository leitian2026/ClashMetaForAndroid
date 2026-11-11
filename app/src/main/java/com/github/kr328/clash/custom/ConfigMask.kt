package com.github.kr328.clash.custom

// CUSTOM (CUSTOM.md #29, not upstream): hides the secrets of a config before it goes into the diagnostic bundle.
// The values of keys that hold passwords / ids / keys become ***, and a link (`url:`) keeps only its scheme and
// host. It works on the text, so block style (`password: x`) and flow style (`{password: x, ...}`) are both
// covered. It is a best effort: node server addresses stay, and a secret under a key not in the list stays too,
// which is why the dialog and the summary tell the user to look through the bundle before sending it.
object ConfigMask {
    private val secret = Regex(
        "(?i)(\\b(?:password|passwd|uuid|psk|private-key|pre-shared-key|public-key|auth-str|auth|obfs-password|" +
            "token|secret|short-id|username)\\s*:\\s*)(\"[^\"\\n]*\"|'[^'\\n]*'|[^,}\\s#][^,}\\n#]*)",
    )

    private val link = Regex("(?i)(\\burl\\s*:\\s*[\"']?)(https?://[^/\\s\"'},]+)[^\\s\"'},]*")

    fun mask(text: String): String {
        return text
            .replace(secret) { it.groupValues[1] + "***" }
            .replace(link) { it.groupValues[1] + it.groupValues[2] + "/***" }
    }
}
