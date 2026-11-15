package com.github.kr328.clash.design.custom

import com.github.kr328.clash.core.custom.IssueItem
import com.github.kr328.clash.design.R

/** CUSTOM (CUSTOM.md #53): the words for the kinds of problems of the running config. */
object IssueText {
    fun kindRes(kind: String): Int = when (kind) {
        "doh" -> R.string.issue_doh
        "ip" -> R.string.issue_ip
        "sub" -> R.string.issue_sub
        "ech" -> R.string.issue_ech
        "dns" -> R.string.issue_dns
        "geo" -> R.string.issue_geo
        "provider" -> R.string.issue_provider
        else -> R.string.issue_other
    }

    fun subject(item: IssueItem): String =
        if (item.kind == "ip" && item.extra.isNotEmpty()) "${item.extra} (${item.subject})" else item.subject
}
