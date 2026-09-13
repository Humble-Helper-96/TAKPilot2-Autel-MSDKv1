package com.autel.sdksample.tak

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.view.View
import androidx.core.content.ContextCompat
import com.autel.sdksample.R

/**
 * Record-to-SD badge — a dot and the word "REC", drawn as a toggle pill to the same
 * specification as the AR, IR and exterior-lights pills beside it (operator, 2026-09-12).
 *
 * ## What it was, and why that was wrong
 *
 * It was a SWITCH: a fully-round capsule on a flat grey fill with a large white knob circle at
 * the left end. Squaring the corner to `hud_pill_radius` was tried first and made it worse — a
 * switch with a border. **The knob was the problem.** A big filled circle at one end of a
 * rounded track is the universal affordance for a slider, so the control read as something the
 * pilot drags rather than something they tap, whatever the corner did.
 *
 * ## What it is now
 *
 * The same treatment as [R.drawable.bg_pill_active], hue changed: a 30 % wash of the state
 * colour, a full-strength stroke of it, and the CONTENT — the dot and the label — in that same
 * colour. Idle takes the neutral fill and stroke with white content. Nothing is white-on-solid
 * any more, and the six pills in the capsule finally read as one family.
 *
 * ⚠ **RED IS NOT GREEN, AND THAT IS THE POINT** (operator, 2026-09-12). Green on the pills
 * beside this one means "this feature is on". Red here means "the camera is writing to the
 * card" — a different question, and the one a pilot must never misread. Only the hue departs
 * from the neighbours; the treatment does not.
 */
class RecordToggleView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : View(context, attrs) {

    // Flight-tuned HUD colours, resolved once per view. Were literals here until
    // 2026-08-14 (conformance A1); the values are unchanged — see takpilot_colors.xml.
    private val COLOR_RECORDING = ContextCompat.getColor(context, R.color.tp_hud_toggle_active)
    private val COLOR_IDLE_CONTENT = ContextCompat.getColor(context, R.color.tp_text_primary)

    /** The pill's shape and idle look, shared with the drawable-backed pills beside it. */
    private val cornerRadius = resources.getDimension(R.dimen.hud_pill_radius)
    private val pillStroke = resources.getDimension(R.dimen.hud_pill_stroke)
    private val idleFill = ContextCompat.getColor(context, R.color.tp_pill_idle_fill)
    private val idleStroke = ContextCompat.getColor(context, R.color.tp_pill_idle_stroke)
    private val liveFill = ContextCompat.getColor(context, R.color.tp_pill_live_fill)

    private var isRecording: Boolean = false

    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val strokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = pillStroke
    }
    private val dotPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        isFakeBoldText = true
        textAlign = Paint.Align.LEFT
    }

    private val trackRect = RectF()

    fun setRecording(recording: Boolean) {
        isRecording = recording
        invalidate()
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        // ⚠ INSET BY HALF THE STROKE, OR THE BORDER IS CLIPPED FLAT on all four sides. A stroke
        // is CENTRED on the path, thus its outer half falls outside the view, and the canvas
        // handed to onDraw is clipped to the view's bounds. A shape drawable does this for you;
        // a canvas does not. Same fault and same fix as OutlinedTextView.
        val half = pillStroke / 2f
        trackRect.set(half, half, w - half, h - half)
        textPaint.textSize = h * 0.4f
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val h = height.toFloat()
        val w = width.toFloat()

        val content = if (isRecording) COLOR_RECORDING else COLOR_IDLE_CONTENT
        fillPaint.color = if (isRecording) liveFill else idleFill
        strokePaint.color = if (isRecording) COLOR_RECORDING else idleStroke
        canvas.drawRoundRect(trackRect, cornerRadius, cornerRadius, fillPaint)
        canvas.drawRoundRect(trackRect, cornerRadius, cornerRadius, strokePaint)

        // The dot and the label are CENTRED AS ONE GROUP, not pinned to the ends. The knob used
        // to hold the left end and the text was centred in what was left, so the two moved
        // independently when the view's width changed. Measuring the pair keeps the pill
        // readable at any width the layout gives it.
        val dotRadius = h * 0.10f
        val gap = h * 0.16f
        val label = "REC"
        val textWidth = textPaint.measureText(label)
        val groupWidth = dotRadius * 2f + gap + textWidth
        val startX = (w - groupWidth) / 2f
        val cy = h / 2f

        dotPaint.color = content
        canvas.drawCircle(startX + dotRadius, cy, dotRadius, dotPaint)

        textPaint.color = content
        val textY = cy - (textPaint.descent() + textPaint.ascent()) / 2f
        canvas.drawText(label, startX + dotRadius * 2f + gap, textY, textPaint)
    }
}
