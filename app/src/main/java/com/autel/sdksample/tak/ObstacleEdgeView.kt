package com.autel.sdksample.tak

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.view.View

/**
 * Obstacle proximity drawn as arcs on the edges of the FPV, one per aircraft face.
 *
 * MODELLED ON AUTEL EXPLORER, deliberately. The operator flew Explorer immediately after a wall
 * strike on this app and its HUD did the job well: an arc bowing in from the edge nearest the
 * obstacle, with the distance printed on it, amber at moderate range and red when close. Copying
 * a proven safety display beats inventing one, and a pilot who switches between the two apps
 * should not have to learn two visual languages for the same hazard.
 *
 * FOUR EDGES PLUS AN EXPLICIT REAR READOUT. The aircraft reports six faces. Left, right, up and
 * down map to screen edges the way Explorer draws them — keeping that shared visual language
 * matters, because the operator switches between the two apps and must not have to re-learn what
 * an edge means. Front is omitted: an obstacle dead ahead is already IN the video.
 *
 * Rear was omitted too, at first, on the reasoning that a forward-looking view has no honest
 * place to put "behind you". The operator flew it and immediately asked where reverse was, which
 * exposed that as exactly backwards — behind the aircraft is the ONE direction the camera cannot
 * show, so it is where a readout earns the most. The fix is to label it, not to drop it: rear
 * gets its own captioned indicator above the bottom arc, visually distinct from the down arc so
 * the two can never be read as each other.
 *
 * UNITS: centimetres. Nothing in the SDK documents this — it was inferred from magnitudes and
 * then FIELD-VALIDATED on 2026-08-02, when the operator flew the display against real obstacles
 * and judged the distances accurate. That is agreement with reality at the ranges that matter,
 * not a bench calibration, so the readout is trustworthy for flying and should not be quoted to
 * the inch. Every distance still passes through [CM_PER_FOOT] alone, so if a future airframe or
 * firmware disagrees, exactly one number changes.
 */
class ObstacleEdgeView @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null, defStyle: Int = 0,
) : View(context, attrs, defStyle) {

    /** One edge's current state. Distance is in the raw sensor units. */
    private data class Edge(var cm: Int?)

    private val left = Edge(null)
    private val right = Edge(null)
    private val top = Edge(null)
    private val bottom = Edge(null)
    private val rear = Edge(null)

    private val arcPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
    }
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textAlign = Paint.Align.CENTER
        isFakeBoldText = true
    }
    private val labelBg = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val rect = RectF()

    // Reused across draws. This view redraws at the radar's push rate, so onDraw must not
    // allocate: the chevron Path is reset and refilled, and one FontMetrics is filled in place
    // (the `fontMetrics` property allocates a fresh object on every read).
    private val chevronPath = android.graphics.Path()
    private val fontMetrics = Paint.FontMetrics()

    /**
     * Feeds the latest radar sample. Safe to call at the sensor's own rate.
     *
     * The aircraft sends ONE face per push, round-robin, leaving the others zeroed — so this
     * must never clear a face just because it is absent from this sample. Each edge keeps its
     * last real reading until that same face reports again. Clearing on absence made all four
     * edges strobe at the push rate, which is worse than useless on a safety display.
     */
    fun update(info: com.autel.common.flycontroller.visual.AvoidanceRadarInfo?) {
        if (info == null) { clear(); return }
        nearest(info.left)?.let { left.cm = it }
        nearest(info.right)?.let { right.cm = it }
        nearest(info.top)?.let { top.cm = it }
        nearest(info.bottom)?.let { bottom.cm = it }
        nearest(info.rear)?.let { rear.cm = it }
        invalidate()
    }

    fun clear() {
        left.cm = null; right.cm = null; top.cm = null; bottom.cm = null
        rear.cm = null
        invalidate()
    }

    /**
     * Closest real reading on one face, or null if the face said nothing this push.
     *
     * Returns null for BOTH sentinels but they mean opposite things, so the caller must not
     * conflate them: 0 = "not in this push" (keep the old value), 10000 = "clear". Clear is
     * mapped to [CLEAR] rather than null so a face that genuinely sees nothing stops drawing.
     */
    private fun nearest(face: FloatArray?): Int? {
        face ?: return null
        var best: Float? = null
        var sawClear = false
        for (v in face) {
            if (v <= 0f) continue
            if (v >= AutelAvoidance.CLEAR_SENTINEL) { sawClear = true; continue }
            if (best == null || v < best!!) best = v
        }
        return best?.toInt() ?: if (sawClear) CLEAR else null
    }

    /**
     * Height of the toolbar covering the top of the video, in pixels.
     *
     * This view is full-screen behind the app's chrome, so its top edge is NOT the top of what
     * the pilot can see. Fed from the toolbar's real measured height after layout (see
     * FlightActivity) rather than a hardcoded dp, so a toolbar change cannot silently push the
     * top-face warning back out of sight.
     *
     * Only the TOP face needs this: left, right and bottom are clear of the toolbar, and the HUD
     * column on the right sits above this view's right arc without covering it.
     */
    private var topInset = 0f

    fun setTopInset(px: Float) {
        if (topInset == px) return
        topInset = px
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()
        drawEdge(canvas, left, Side.LEFT, w, h)
        drawEdge(canvas, right, Side.RIGHT, w, h)
        drawEdge(canvas, top, Side.TOP, w, h)
        drawEdge(canvas, bottom, Side.BOTTOM, w, h)
        drawRear(canvas, w, h)
    }

    private enum class Side { LEFT, RIGHT, TOP, BOTTOM }

    private fun drawEdge(canvas: Canvas, e: Edge, side: Side, w: Float, h: Float) {
        val cm = e.cm ?: return
        if (cm == CLEAR || cm > WARN_CM) return          // nothing worth showing

        // Nearer = more opaque, thicker and redder. A binary red/not-red gives the pilot no
        // sense of closing rate, which is the thing they actually steer on.
        val t = (1f - (cm.toFloat() / WARN_CM)).coerceIn(0f, 1f)
        arcPaint.color = if (cm <= DANGER_CM) COLOR_DANGER else COLOR_WARN
        arcPaint.alpha = (90 + 165 * t).toInt().coerceAtMost(255)
        arcPaint.strokeWidth = dp(5f) + dp(9f) * t

        val bow = dp(26f) + dp(30f) * t                 // how far the arc bows inward
        val inset = dp(4f)
        val span = 0.62f                                // fraction of the edge the arc covers

        val cx: Float; val cy: Float
        when (side) {
            Side.LEFT -> {
                val len = h * span
                rect.set(inset - bow, (h - len) / 2f, inset + bow, (h + len) / 2f)
                canvas.drawArc(rect, -70f, 140f, false, arcPaint)
                cx = inset + bow + dp(20f); cy = h / 2f
            }
            Side.RIGHT -> {
                val len = h * span
                rect.set(w - inset - bow, (h - len) / 2f, w - inset + bow, (h + len) / 2f)
                canvas.drawArc(rect, 110f, 140f, false, arcPaint)
                cx = w - inset - bow - dp(20f); cy = h / 2f
            }
            Side.TOP -> {
                // Pushed below the toolbar by [topInset]. Without it the arc and its distance
                // label drew from the view's top edge and sat underneath the toolbar, so the one
                // face whose warning means "you are about to hit something above you" was the
                // one the pilot could not read.
                val len = w * span
                val top = topInset + inset
                rect.set((w - len) / 2f, top - bow, (w + len) / 2f, top + bow)
                canvas.drawArc(rect, 20f, 140f, false, arcPaint)
                cx = w / 2f; cy = top + bow + dp(22f)
            }
            Side.BOTTOM -> {
                val len = w * span
                rect.set((w - len) / 2f, h - inset - bow, (w + len) / 2f, h - inset + bow)
                canvas.drawArc(rect, 200f, 140f, false, arcPaint)
                cx = w / 2f; cy = h - inset - bow - dp(14f)
            }
        }
        drawLabel(canvas, cx, cy, cm)
    }


    /**
     * Rear proximity, as a captioned readout rather than an edge arc.
     *
     * NOT an arc, deliberately. Every arc in this view means "the hazard is off the screen in
     * this direction", and that reading breaks down for rear — the bottom edge already means
     * DOWN, and a second arc sharing it would make the two indistinguishable at a glance, on a
     * display whose whole job is to be read at a glance. So rear gets a shape nothing else uses:
     * a back-pointing chevron with the word REAR on it. Unmissable, unambiguous, and it cannot be
     * mistaken for the ground.
     *
     * Sits clear above the bottom arc's own label by [REAR_LIFT], which exceeds that arc's
     * maximum bow plus its label height, so the two never collide however close either gets.
     */
    private fun drawRear(canvas: Canvas, w: Float, h: Float) {
        val cm = rear.cm ?: return
        if (cm == CLEAR || cm > WARN_CM) return

        val t = (1f - (cm.toFloat() / WARN_CM)).coerceIn(0f, 1f)
        arcPaint.color = if (cm <= DANGER_CM) COLOR_DANGER else COLOR_WARN
        arcPaint.alpha = (110 + 145 * t).toInt().coerceAtMost(255)
        arcPaint.strokeWidth = dp(4f) + dp(5f) * t

        val cx = w / 2f
        val cy = h - REAR_LIFT * resources.displayMetrics.density

        // Two stacked chevrons pointing DOWN-AND-BACK — away from the direction of view.
        val half = dp(20f)
        val drop = dp(9f)
        for (i in 0 until 2) {
            val yTop = cy + dp(13f) + i * dp(9f)
            chevronPath.reset()
            chevronPath.moveTo(cx - half, yTop)
            chevronPath.lineTo(cx, yTop + drop)
            chevronPath.lineTo(cx + half, yTop)
            arcPaint.style = Paint.Style.STROKE
            canvas.drawPath(chevronPath, arcPaint)
        }

        drawLabel(canvas, cx, cy, cm, "REAR ")
    }

    /** Distance in feet on a filled pill, matching Explorer's readout. */
    private fun drawLabel(canvas: Canvas, cx: Float, cy: Float, cm: Int, caption: String = "") {
        val feet = cm / CM_PER_FOOT
        val text = caption + "%.1fft".format(feet)
        textPaint.textSize = dp(15f)
        val tw = textPaint.measureText(text)
        val padH = dp(8f); val padV = dp(5f)
        val fm = fontMetrics.also { textPaint.getFontMetrics(it) }
        rect.set(cx - tw / 2f - padH, cy - (-fm.ascent) - padV,
                 cx + tw / 2f + padH, cy + fm.descent + padV)
        labelBg.color = arcPaint.color
        labelBg.alpha = 235
        canvas.drawRoundRect(rect, dp(5f), dp(5f), labelBg)
        canvas.drawText(text, cx, cy, textPaint)
    }

    private fun dp(v: Float) = v * resources.displayMetrics.density

    companion object {
        /** Face reported "clear". Distinct from "no data" so the edge stops drawing. */
        private const val CLEAR = Int.MAX_VALUE

        /** Start drawing at this range, go red at this one. Both in the raw sensor units. */
        private const val WARN_CM = 1200      // ~39 ft
        private const val DANGER_CM = 400     // ~13 ft

        // Precomputed so onDraw never runs Color.parseColor (a string parse + allocation) per
        // edge per frame. Red inside DANGER_CM, amber beyond it.
        private const val COLOR_DANGER = 0xFFFF3B30.toInt()
        private const val COLOR_WARN = 0xFFFFCC00.toInt()

        /**
         * ⚠ THE ONE ASSUMPTION IN THIS FILE. Nothing in the SDK documents the radar's units;
         * centimetres is inferred from magnitudes that line up with what Explorer showed at the
         * same moment (its HUD read 6.0/17.0/26.0/28.0 ft while raw samples ran in the tens to
         * low hundreds). Verify by hovering a known distance from a wall and comparing this
         * label against Explorer's. If it is wrong, change ONLY this constant.
         */
        private const val CM_PER_FOOT = 30.48f

        /** dp above the bottom edge for the rear readout. Clears the bottom arc's
         *  maximum bow (56dp) plus its label, so the two never overlap. */
        private const val REAR_LIFT = 118f
    }
}
