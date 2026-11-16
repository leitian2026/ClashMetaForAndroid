package com.github.kr328.clash.design.custom

import android.content.Context
import androidx.annotation.StringRes
import com.github.kr328.clash.design.R

// CUSTOM (CUSTOM.md #73, not part of upstream): a few words that say what an HTTP status code of the target site means,
// shown after the code in the "target reaction" cell and in the log. Pure lookup: no request, no timer, nothing is kept.
object TargetStatusText {
    // the short words of the common codes; every other code falls back to the words of its class (2xx / 3xx / 4xx / 5xx)
    @StringRes
    private fun resOf(code: Int): Int = when (code) {
        200 -> R.string.iptest_http_200
        204 -> R.string.iptest_http_204
        301 -> R.string.iptest_http_301
        302 -> R.string.iptest_http_302
        303 -> R.string.iptest_http_303
        304 -> R.string.iptest_http_304
        307 -> R.string.iptest_http_307
        308 -> R.string.iptest_http_308
        400 -> R.string.iptest_http_400
        401 -> R.string.iptest_http_401
        403 -> R.string.iptest_http_403
        404 -> R.string.iptest_http_404
        405 -> R.string.iptest_http_405
        407 -> R.string.iptest_http_407
        429 -> R.string.iptest_http_429
        451 -> R.string.iptest_http_451
        500 -> R.string.iptest_http_500
        502 -> R.string.iptest_http_502
        503 -> R.string.iptest_http_503
        504 -> R.string.iptest_http_504
        520 -> R.string.iptest_http_520
        521 -> R.string.iptest_http_521
        522 -> R.string.iptest_http_522
        523 -> R.string.iptest_http_523
        524 -> R.string.iptest_http_524
        525 -> R.string.iptest_http_525
        526 -> R.string.iptest_http_526
        in 200..299 -> R.string.iptest_http_2xx
        in 300..399 -> R.string.iptest_http_3xx
        in 400..499 -> R.string.iptest_http_4xx
        in 500..599 -> R.string.iptest_http_5xx
        else -> 0
    }

    // "" when the code is not an HTTP status (0 = no answer)
    fun of(context: Context, code: Int): String {
        val res = resOf(code)

        return if (res == 0) "" else context.getString(res)
    }
}
