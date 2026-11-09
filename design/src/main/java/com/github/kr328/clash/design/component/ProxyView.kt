package com.github.kr328.clash.design.component

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.view.View
import com.github.kr328.clash.common.compat.getDrawableCompat
import com.github.kr328.clash.design.R
import com.github.kr328.clash.core.util.CountryFlag
import com.github.kr328.clash.design.store.UiStore

class ProxyView(
    context: Context,
    config: ProxyViewConfig,
) : View(context) {

    init {
        background = context.getDrawableCompat(config.clickableBackground)
    }

    // CUSTOM: re-measure when a new state (possibly with per-IP rows) is bound
    var state: ProxyViewState? = null
        set(value) {
            field = value
            requestLayout()
        }

    // CUSTOM: extra height used by the per-IP delay rows (0 when there are none)
    private var ipRowStep = 0f
    private var ipExtraHeight = 0
    private val ipTimeoutText by lazy { context.getString(R.string.ip_delay_timeout) }

    // CUSTOM (CUSTOM.md #12): when the per-IP delays were measured (0 = unknown). The text under the
    // IP rows is computed when the view is drawn; nothing ticks to keep it up to date.
    private fun testedAt(): Long = state?.proxy?.ipDelays?.maxOfOrNull { it.at } ?: 0L

    private fun ageText(at: Long): String {
        val seconds = ((System.currentTimeMillis() - at) / 1000).coerceAtLeast(0)

        return when {
            seconds < 10 -> context.getString(R.string.ip_delay_age_now)
            seconds < 60 -> context.getString(R.string.ip_delay_age_seconds, seconds.toInt())
            seconds < 3600 -> context.getString(R.string.ip_delay_age_minutes, (seconds / 60).toInt())
            seconds < 86400 -> context.getString(R.string.ip_delay_age_hours, (seconds / 3600).toInt())
            else -> context.getString(R.string.ip_delay_age_days, (seconds / 86400).toInt())
        }
    }

    constructor(context: Context) : this(context, ProxyViewConfig(context, 2))
    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val state = state ?: return super.onMeasure(widthMeasureSpec, heightMeasureSpec)

        val width = when (MeasureSpec.getMode(widthMeasureSpec)) {
            MeasureSpec.UNSPECIFIED ->
                resources.displayMetrics.widthPixels
            MeasureSpec.AT_MOST, MeasureSpec.EXACTLY ->
                MeasureSpec.getSize(widthMeasureSpec)
            else ->
                throw IllegalArgumentException("invalid measure spec")
        }

        state.paint.apply {
            reset()

            textSize = state.config.textSize

            getTextBounds("Stub!", 0, 1, state.rect)
        }

        val textHeight = state.rect.height()
        // CUSTOM: one extra row per tested IP, plus one row for "tested N min ago"
        val ipCount = state.proxy.ipDelays.size
        val ipRows = if (ipCount > 0 && testedAt() > 0) ipCount + 1 else ipCount
        ipRowStep = textHeight + state.config.textMargin * 0.6f
        ipExtraHeight = if (ipRows > 0) (ipRowStep * ipRows).toInt() else 0

        val exceptHeight = (state.config.layoutPadding * 2 +
                state.config.contentPadding * 2 +
                textHeight * 2 +
                state.config.textMargin).toInt() + ipExtraHeight

        val height = when (MeasureSpec.getMode(heightMeasureSpec)) {
            MeasureSpec.UNSPECIFIED ->
                exceptHeight
            MeasureSpec.AT_MOST, MeasureSpec.EXACTLY ->
                exceptHeight.coerceAtMost(MeasureSpec.getSize(heightMeasureSpec))
            else ->
                throw IllegalArgumentException("invalid measure spec")
        }

        setMeasuredDimension(width, height)
    }

    override fun draw(canvas: Canvas) {
        val state = state ?: return super.draw(canvas)

        if (state.update(false))
            postInvalidate()

        val width = width.toFloat()
        val height = height.toFloat()

        val paint = state.paint

        paint.reset()

        paint.color = state.background
        paint.style = Paint.Style.FILL

        // draw background
        canvas.apply {
            if (state.config.proxyLine==1) {
                drawRect(0f, 0f, width, height, paint)
            } else {
                val path = state.path

                path.reset()

                path.addRoundRect(
                    state.config.layoutPadding,
                    state.config.layoutPadding,
                    width - state.config.layoutPadding,
                    height - state.config.layoutPadding,
                    state.config.cardRadius,
                    state.config.cardRadius,
                    Path.Direction.CW,
                )

                paint.setShadowLayer(
                    state.config.cardRadius,
                    state.config.cardOffset,
                    state.config.cardOffset,
                    state.config.shadow
                )

                drawPath(path, paint)

                clipPath(path)
            }
        }

        super.draw(canvas)
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)

        val state = state ?: return

        val paint = state.paint

        val width = width.toFloat()
        // CUSTOM: the original two-line layout is laid out in the area above the IP rows
        val height = height.toFloat() - ipExtraHeight

        paint.textSize = state.config.textSize

        // measure delay text bounds
        val delayCount = paint.breakText(
            state.delayText,
            false,
            (width - state.config.layoutPadding * 2 - state.config.contentPadding * 2)
                .coerceAtLeast(0f),
            null
        )

        state.paint.getTextBounds(state.delayText, 0, delayCount, state.rect)

        val delayWidth = state.rect.width()

        val mainTextWidth = (width -
                state.config.layoutPadding * 2 -
                state.config.contentPadding * 2 -
                delayWidth -
                state.config.textMargin * 2
                )
            .coerceAtLeast(0f)

        // measure title text bounds
        val titleCount = paint.breakText(
            state.title,
            false,
            mainTextWidth,
            null,
        )

        // measure subtitle text bounds
        val subtitleCount = paint.breakText(
            state.subtitle,
            false,
            mainTextWidth,
            null,
        )

        // text draw measure
        val textOffset = (paint.descent() + paint.ascent()) / 2

        paint.reset()

        paint.textSize = state.config.textSize
        paint.isAntiAlias = true
        paint.color = state.controls

        // draw delay
        canvas.apply {
            val x = width - state.config.layoutPadding - state.config.contentPadding - delayWidth
            val y = height / 2f - textOffset

            drawText(state.delayText, 0, delayCount, x, y, paint)
        }

        // draw title
        canvas.apply {
            val x = state.config.layoutPadding + state.config.contentPadding
            val y = state.config.layoutPadding +
                    (height - state.config.layoutPadding * 2) / 3f - textOffset

            drawText(state.title, 0, titleCount, x, y, paint)
        }

        // draw subtitle
        canvas.apply {
            val x = state.config.layoutPadding + state.config.contentPadding
            val y = state.config.layoutPadding +
                    (height - state.config.layoutPadding * 2) / 3f * 2 - textOffset

            drawText(state.subtitle, 0, subtitleCount, x, y, paint)
        }

        // CUSTOM: draw one row per tested IP: "ip ........ delay"
        val ipDelays = state.proxy.ipDelays

        if (ipDelays.isNotEmpty()) {
            paint.alpha = 0xB4

            val left = state.config.layoutPadding + state.config.contentPadding
            val right = width - state.config.layoutPadding - state.config.contentPadding
            val subtitleY = state.config.layoutPadding +
                    (height - state.config.layoutPadding * 2) / 3f * 2

            ipDelays.forEachIndexed { index, item ->
                val text = if (item.delay > 0) item.delay.toString() else ipTimeoutText
                val y = subtitleY + ipRowStep * (index + 1) - textOffset
                val textWidth = paint.measureText(text)

                // flag of the exit country in front of the address; "？" when it could not be found
                val flag = if (item.delay > 0) CountryFlag.of(item.country).ifEmpty { "？" } else ""
                val label = if (flag.isEmpty()) item.ip else "$flag ${item.ip}"

                val ipCount = paint.breakText(
                    label,
                    false,
                    (right - left - textWidth - state.config.textMargin).coerceAtLeast(0f),
                    null,
                )

                canvas.drawText(label, 0, ipCount, left, y, paint)
                canvas.drawText(text, right - textWidth, y, paint)
            }

            // CUSTOM (CUSTOM.md #12): when these delays were measured
            val at = testedAt()

            if (at > 0) {
                val age = ageText(at)
                val ageSize = state.config.textSize * 0.85f
                val y = subtitleY + ipRowStep * (ipDelays.size + 1) - textOffset

                paint.textSize = ageSize
                paint.alpha = 0x80

                canvas.drawText(age, right - paint.measureText(age), y, paint)
            }
        }
    }
}