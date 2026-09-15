package com.autel.sdksample.tak

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import androidx.core.content.ContextCompat
import com.autel.sdksample.R

/**
 * What the CAMERA is set to save, as a TWO-WAY TOGGLE: the movie camera on the left, the still
 * camera on the right, the lit half is the mode the camera reports (operator, 2026-09-15).
 *
 * ## It is a control now, and a readout still
 *
 * From 2026-09-13 to today this was a readout with no frame, on the argument that the
 * application did not drive the media mode. The operator's rule now is that it does: a tap on
 * a half asks the camera for that mode WITHOUT taking a still or starting a recording, and the
 * shutter and REC keep changing the mode by themselves. The lit half always shows what the
 * CAMERA reports (the 2 Hz push), never what was tapped — a tap that the camera ignores leaves
 * the highlight where it was, which is the truth. Unknown is its own state: both halves amber
 * until the camera answers (§4.6).
 *
 * Drawn as one pill (§6.7 radius and stroke) split by a hairline, the lit half in the active
 * fill with its glyph in `tp_state_go`, the other half idle with a white glyph. The glyphs are
 * [CameraGlyphs], the same shapes the record pill drew, so a pilot learns one symbol per mode.
 * Each glyph carries the HUD's outline, so it stays legible over bright ground.
 */
class MediaModeView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : View(context, attrs) {

    /** What the camera says. Null is UNKNOWN and is its own state — §4.6. */
    enum class Mode { PHOTO, VIDEO }

    /** Set by the flight screen: the pilot tapped a half and wants that mode. */
    var onModeRequested: ((Mode) -> Unit)? = null

    private var mode: Mode? = null
    private var otherLabel: String? = null

    private val outlineWidth = resources.getDimension(R.dimen.hud_text_outline_width)
    private val textSize = resources.getDimension(R.dimen.flight_readout_text_size)
    private val radius = resources.getDimension(R.dimen.hud_pill_radius)
    private val stroke = resources.getDimension(R.dimen.hud_pill_stroke)
    private val unknownColor = ContextCompat.getColor(context, R.color.tp_state_unknown)
    private val goColor = ContextCompat.getColor(context, R.color.tp_state_go)
    private val outlineColor = ContextCompat.getColor(context, R.color.tp_hud_outline)
    private val idleFill = ContextCompat.getColor(context, R.color.tp_pill_idle_fill)
    private val idleStroke = ContextCompat.getColor(context, R.color.tp_pill_idle_stroke)
    private val activeFill = ContextCompat.getColor(context, R.color.tp_pill_active_fill)
    private val unknownFill = ContextCompat.getColor(context, R.color.tp_pill_unknown_fill)

    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val strokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; strokeWidth = stroke
    }
    private val glyphFill = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = outlineWidth / 2f
        strokeJoin = Paint.Join.ROUND
        strokeCap = Paint.Cap.ROUND
    }
    private val glyphStroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = outlineWidth * 2f
        strokeJoin = Paint.Join.ROUND
        strokeCap = Paint.Cap.ROUND
        color = outlineColor
    }

    private val glyph = Path()
    private val body = RectF()
    private val pill = RectF()
    private val half = RectF()

    private val glyphSize: Float get() = textSize * 1.15f
    /** Each half is a square-ish cell around its glyph; the pill is two cells. */
    private val cell: Float get() = glyphSize * 2.2f
    private val cellH: Float get() = glyphSize * 1.8f

    fun setMode(m: Mode?, unhandledName: String? = null) {
        if (m == mode && unhandledName == otherLabel) return
        mode = m
        otherLabel = unhandledName
        contentDescription = when {
            unhandledName != null -> "Camera mode $unhandledName"
            m == Mode.PHOTO -> "Camera is in photo mode; tap the movie camera for video"
            m == Mode.VIDEO -> "Camera is in video mode; tap the still camera for photo"
            else -> "Camera mode not known"
        }
        invalidate()
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val pad = stroke
        val w = cell * 2 + pad * 2
        val h = cellH + pad * 2
        setMeasuredDimension(
            resolveSize(Math.ceil(w.toDouble()).toInt(), widthMeasureSpec),
            resolveSize(Math.ceil(h.toDouble()).toInt(), heightMeasureSpec),
        )
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (event.action == MotionEvent.ACTION_DOWN) return true
        if (event.action != MotionEvent.ACTION_UP) return false
        val wanted = if (event.x < width / 2f) Mode.VIDEO else Mode.PHOTO
        performClick()
        onModeRequested?.invoke(wanted)
        return true
    }

    override fun performClick(): Boolean { super.performClick(); return true }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val pad = stroke / 2f   // a stroke is centred on its path: inset by half — §6.7
        pill.set(pad, pad, width - pad, height - pad)
        val unknown = mode == null || otherLabel != null

        // The lit half first, clipped to the pill, then the whole pill's stroke over it.
        fillPaint.color = idleFill
        canvas.drawRoundRect(pill, radius, radius, fillPaint)
        if (!unknown) {
            val mid = width / 2f
            if (mode == Mode.VIDEO) half.set(pill.left, pill.top, mid, pill.bottom)
            else half.set(mid, pill.top, pill.right, pill.bottom)
            canvas.save()
            canvas.clipRect(half)
            fillPaint.color = activeFill
            canvas.drawRoundRect(pill, radius, radius, fillPaint)
            canvas.restore()
        } else {
            fillPaint.color = unknownFill
            canvas.drawRoundRect(pill, radius, radius, fillPaint)
        }
        strokePaint.color = if (unknown) unknownColor else idleStroke
        canvas.drawRoundRect(pill, radius, radius, strokePaint)
        // The hairline between the halves.
        canvas.drawLine(width / 2f, pill.top + stroke, width / 2f, pill.bottom - stroke, strokePaint)

        // Glyphs: movie left, still right; the lit one in the go colour.
        val cy = height / 2f
        val leftCx = width / 4f
        val rightCx = width * 3f / 4f
        drawGlyph(canvas, leftCx, cy, movie = true,
            colour = if (unknown) unknownColor else if (mode == Mode.VIDEO) goColor else Color.WHITE)
        drawGlyph(canvas, rightCx, cy, movie = false,
            colour = if (unknown) unknownColor else if (mode == Mode.PHOTO) goColor else Color.WHITE)
    }

    private fun drawGlyph(canvas: Canvas, cx: Float, cy: Float, movie: Boolean, colour: Int) {
        val s = glyphSize
        if (movie) CameraGlyphs.movie(glyph, body, cx - s / 2f, cy - s / 2f, s)
        else CameraGlyphs.still(glyph, body, cx - s / 2f, cy - s / 2f, s)
        canvas.drawPath(glyph, glyphStroke)
        glyphFill.color = colour
        canvas.drawPath(glyph, glyphFill)
    }
}
