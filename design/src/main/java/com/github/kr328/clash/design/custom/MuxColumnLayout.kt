package com.github.kr328.clash.design.custom

import android.content.Context
import android.util.AttributeSet
import android.view.View
import android.widget.LinearLayout
import com.github.kr328.clash.design.R

/**
 * CUSTOM (CUSTOM.md #20, r211, not upstream): a LinearLayout that is never wider than
 * `@dimen/mux_content_max_width`. Put it in a parent with `layout_gravity="center_horizontal"` and on a tablet the
 * content is a centered column instead of being stretched over the whole screen. Only the measure is capped.
 */
class MuxColumnLayout @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : LinearLayout(context, attrs) {
    private val maxWidthPx = context.resources.getDimensionPixelSize(R.dimen.mux_content_max_width)

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val mode = View.MeasureSpec.getMode(widthMeasureSpec)
        val size = View.MeasureSpec.getSize(widthMeasureSpec)
        val spec = if (mode != View.MeasureSpec.UNSPECIFIED && size > maxWidthPx) {
            View.MeasureSpec.makeMeasureSpec(
                maxWidthPx,
                if (mode == View.MeasureSpec.EXACTLY) View.MeasureSpec.EXACTLY else View.MeasureSpec.AT_MOST,
            )
        } else {
            widthMeasureSpec
        }

        super.onMeasure(spec, heightMeasureSpec)
    }
}
