package com.angel.minimal

import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import kotlin.math.abs
import kotlin.math.asin
import kotlin.math.atan2
import kotlin.math.hypot

internal const val LINE = 1.5f
private val PASSES = booleanArrayOf(true, false) // outline pass, then fill pass

/** Paints for the white-outline pets. */
internal class LineArt(fg: Int, bg: Int) {
    // each part is drawn twice: a wide pass in the line colour, then a narrower pass in the background colour,
    // so overlapping parts merge into one outline with no seams at the joints
    private val tubeOut = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        color = fg
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }
    private val tubeIn = Paint(tubeOut).apply { color = bg }
    private val shapeOut = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL_AND_STROKE
        color = fg
        strokeWidth = 2 * LINE
        strokeJoin = Paint.Join.ROUND
    }
    private val shapeIn = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = bg }
    val ink = Paint(tubeOut).apply { strokeWidth = LINE }
    val dot = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = fg }

    /** Draws [paths] as one merged outline; a width of 0 means a filled shape, otherwise a line that wide. */
    fun group(c: Canvas, paths: Array<Path>, w: FloatArray, from: Int = 0, to: Int = paths.size) {
        for (out in PASSES) for (i in from until to) {
            if (w[i] > 0) {
                val paint = if (out) tubeOut else tubeIn
                paint.strokeWidth = if (out) w[i] + 2 * LINE else w[i]
                c.drawPath(paths[i], paint)
            } else c.drawPath(paths[i], if (out) shapeOut else shapeIn)
        }
    }
}

/** The outline around two circles: radius r1 at one end narrowing to r2 at the other, so joints have no knobs. */
internal fun Path.taper(ax: Float, ay: Float, r1: Float, bx: Float, by: Float, r2: Float, oval: RectF) {
    reset()
    val len = hypot(bx - ax, by - ay)
    if (len <= abs(r1 - r2) + 0.01f) { // one circle swallows the other
        if (r1 > r2) addCircle(ax, ay, r1, Path.Direction.CW) else addCircle(bx, by, r2, Path.Direction.CW)
        return
    }
    val phi = Math.toDegrees(atan2((by - ay).toDouble(), (bx - ax).toDouble())).toFloat()
    val th = Math.toDegrees(asin(((r1 - r2) / len).toDouble())).toFloat()
    oval.set(ax - r1, ay - r1, ax + r1, ay + r1)
    arcTo(oval, phi + 90 + th, 180 - 2 * th, true)
    oval.set(bx - r2, by - r2, bx + r2, by + r2)
    arcTo(oval, phi - 90 - th, 180 + 2 * th, false)
    close()
}

internal fun Path.line(x1: Float, y1: Float, x2: Float, y2: Float) {
    reset()
    moveTo(x1, y1)
    lineTo(x2, y2)
}
