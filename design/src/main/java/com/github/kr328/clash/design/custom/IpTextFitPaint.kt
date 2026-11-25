package com.github.kr328.clash.design.custom

import android.graphics.Paint
import android.util.LruCache

class IpTextFitPaint {
    private data class Key(
        val text: String,
        val width: Int,
        val baseSize: Float,
        val minSize: Float,
    )

    private val measurePaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val cache = LruCache<Key, IpTextFit.Result>(48)

    fun fit(
        text: String,
        availableWidth: Float,
        baseTextSize: Float,
        minTextSize: Float,
    ): IpTextFit.Result {
        val width = availableWidth.coerceAtLeast(0f)
        val key = Key(text, width.toBits(), baseTextSize, minTextSize)
        cache.get(key)?.let { return it }

        val result = IpTextFit.fit(text, width, baseTextSize, minTextSize) { value, size ->
            measurePaint.textSize = size
            measurePaint.measureText(value)
        }
        cache.put(key, result)
        return result
    }
}
