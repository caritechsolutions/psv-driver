package com.example.psvdriver

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.view.View
import androidx.core.content.ContextCompat
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.min

/**
 * A 270° speedometer arc with a red "over the limit" zone. Self-contained Canvas
 * drawing — no dependency. Driven by [setSpeed] (km/h) and [setLimit] (km/h, 0 =
 * no limit so the redline is hidden). The arc + number turn red the instant speed
 * is at/over the limit; the audible alarm stays debounced in the activity.
 */
class SpeedGaugeView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0,
) : View(context, attrs, defStyleAttr) {

    private var speed = 0
    private var limit = 0

    private val density = resources.displayMetrics.density
    private val arcStroke = 24f * density

    private val trackPaint = arcPaint(R.color.ps_stroke)
    private val redZonePaint = arcPaint(R.color.ps_red)
    private val progressPaint = arcPaint(R.color.ps_green)

    private val numberPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER
        isFakeBoldText = true
    }
    private val unitPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER
        color = ContextCompat.getColor(context, R.color.ps_on_dark_muted)
    }
    private val limitPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER
        color = ContextCompat.getColor(context, R.color.ps_on_dark_muted)
    }

    private val oval = RectF()

    private fun arcPaint(colorRes: Int) = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = arcStroke
        strokeCap = Paint.Cap.ROUND
        color = ContextCompat.getColor(context, colorRes)
    }

    fun setSpeed(kmh: Int) {
        if (kmh == speed) return
        speed = kmh
        invalidate()
    }

    fun setLimit(kmh: Int) {
        if (kmh == limit) return
        limit = kmh
        invalidate()
    }

    /** Full-scale of the dial: a round number comfortably above the limit. */
    private fun scaleMax(): Int {
        if (limit <= 0) return 120
        val raw = max(80, ceil(limit * 1.3).toInt())
        return ((raw + 19) / 20) * 20 // round up to nearest 20
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val w = MeasureSpec.getSize(widthMeasureSpec)
        val hMode = MeasureSpec.getMode(heightMeasureSpec)
        val desiredH = (w * 0.7f).toInt()
        val h = when (hMode) {
            MeasureSpec.EXACTLY -> MeasureSpec.getSize(heightMeasureSpec)
            MeasureSpec.AT_MOST -> min(desiredH, MeasureSpec.getSize(heightMeasureSpec))
            else -> desiredH
        }
        setMeasuredDimension(w, h)
    }

    override fun onDraw(canvas: Canvas) {
        val over = limit > 0 && speed >= limit
        val maxScale = scaleMax()

        val w = width.toFloat()
        val h = height.toFloat()
        val pad = arcStroke / 2f + 2f * density
        // 270° sweep with the gap at the bottom; arc spans ~1.707r vertically, 2r wide.
        // Reserve arcStroke at top+bottom so the rounded caps never clip.
        val r = min((w - arcStroke) / 2f, (h - 2f * pad) / 1.707f)
        val cx = w / 2f
        val cy = pad + r
        oval.set(cx - r, cy - r, cx + r, cy + r)

        // Background track (full sweep).
        canvas.drawArc(oval, START_ANGLE, SWEEP_ANGLE, false, trackPaint)

        // Red zone from the limit to full-scale (the redline).
        if (limit in 1 until maxScale) {
            val limitFrac = limit.toFloat() / maxScale
            val redStart = START_ANGLE + SWEEP_ANGLE * limitFrac
            canvas.drawArc(oval, redStart, SWEEP_ANGLE * (1f - limitFrac), false, redZonePaint)
        }

        // Progress from 0 to current speed — green normally, red once at/over limit.
        val speedFrac = (speed.toFloat() / maxScale).coerceIn(0f, 1f)
        if (speedFrac > 0f) {
            progressPaint.color = ContextCompat.getColor(
                context, if (over) R.color.ps_red else R.color.ps_green
            )
            canvas.drawArc(oval, START_ANGLE, SWEEP_ANGLE * speedFrac, false, progressPaint)
        }

        // Center readouts.
        numberPaint.color = ContextCompat.getColor(
            context, if (over) R.color.ps_red else R.color.ps_on_dark
        )
        numberPaint.textSize = r * 0.6f
        unitPaint.textSize = r * 0.18f
        limitPaint.textSize = r * 0.16f

        canvas.drawText(speed.toString(), cx, cy + numberPaint.textSize * 0.35f, numberPaint)
        canvas.drawText("km/h", cx, cy + numberPaint.textSize * 0.75f, unitPaint)

        val limitLabel = if (limit > 0) "LIMIT $limit" else "NO LIMIT"
        canvas.drawText(limitLabel, cx, cy + r * 0.62f, limitPaint)
    }

    private companion object {
        // Canvas angles are clockwise from 3 o'clock; this opens a 90° gap at the bottom.
        const val START_ANGLE = 135f
        const val SWEEP_ANGLE = 270f
    }
}
