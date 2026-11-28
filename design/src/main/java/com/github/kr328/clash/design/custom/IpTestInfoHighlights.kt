package com.github.kr328.clash.design.custom

import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.ForegroundColorSpan

internal fun highlightTargetUrl(text: String, mutedColor: Int, badColor: Int): SpannableStringBuilder =
    SpannableStringBuilder(text).apply {
        setSpan(ForegroundColorSpan(mutedColor), 0, length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        val url = Regex("https?://\\S+", RegexOption.IGNORE_CASE).find(text)
        if (url != null && text.contains("目标网址"))
            setSpan(ForegroundColorSpan(badColor), url.range.first, url.range.last + 1, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
    }

internal fun highlightUnicomIpv4(text: String, badColor: Int): SpannableStringBuilder =
    SpannableStringBuilder(text).apply {
        // #43: highlight every IPv4 node label, regardless of carrier, not just Unicom.
        val match = Regex("(?:节点\\s*)?[\\p{L}\\p{N} _-]*ipv4", RegexOption.IGNORE_CASE).find(text)
        if (match != null) setSpan(ForegroundColorSpan(badColor), match.range.first, match.range.last + 1, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
    }
