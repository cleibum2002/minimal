package com.angel.minimal

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.os.SystemClock
import android.view.MotionEvent
import android.view.View
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.random.Random

private const val W = 60f // the cat's box in drawing units; the ground is at y = 50
private const val H = 56f
private const val BONE = 9f // each of the two bones in a leg

// A pose is 23 numbers, drawn facing right: hip, shoulder and head (x, y), head tilt, face (0 side-on, 1 towards you),
// paws (x, y) back-far, back-near, front-far, front-near, the tail's bend and tip (x, y),
// which way the back and front knees bend (1 backwards, 0 straight), and the back thigh's width.
// Sitting, the back feet lie flat under a big round haunch with just the toes peeking out.
private val SIT = floatArrayOf(17f, 39.5f, 28f, 28f, 32f, 16f, 0f, 0f, 19.5f, 50f, 20.5f, 50f, 30f, 50f, 32f, 50f, 0f, 47f, 2f, 34f, 0f, 1f, 8.5f)
private val STAND = floatArrayOf(15f, 31f, 35f, 31f, 44f, 21f, 0f, 0f, 15f, 50f, 15f, 50f, 35f, 50f, 35f, 50f, 1f, 24f, 5f, 12f, 1f, 1f, 4.6f)
private val CROUCH = floatArrayOf(15f, 35f, 35f, 36f, 44f, 27f, 0f, 0f, 15f, 50f, 15f, 50f, 35f, 50f, 35f, 50f, 1f, 30f, 3f, 20f, 1f, 1f, 4.6f)
private val AIR = floatArrayOf(15f, 30f, 35f, 28f, 44f, 19f, -0.1f, 0f, 6f, 42f, 9f, 44f, 46f, 38f, 48f, 40f, 0f, 30f, -2f, 20f, 1f, 1f, 4.6f)
// stretch: hips high, chest low, front legs reaching straight out along the ground
private val BOW = floatArrayOf(16f, 28f, 34f, 40f, 45f, 37f, -0.15f, 0f, 15f, 50f, 18f, 50f, 51f, 49f, 53f, 49.5f, 6f, 12f, 12f, 3f, 1f, 1f, 4.6f)
private const val TILT = 6
private const val FACE = 7
private val XS = intArrayOf(0, 2, 4, 8, 10, 12, 14, 16, 18)

// every number chases its target like a spring: body steady, head a little bouncy, tail loose so it trails behind
private val OMEGA = floatArrayOf(16f, 16f, 16f, 16f, 14f, 14f, 9f, 12f, 50f, 50f, 50f, 50f, 50f, 50f, 50f, 50f, 8f, 8f, 6f, 6f, 14f, 14f, 10f)
private val ZETA = floatArrayOf(.9f, .9f, .9f, .9f, .7f, .7f, .7f, 1f, 1f, 1f, 1f, 1f, 1f, 1f, 1f, 1f, .55f, .55f, .45f, .45f, 1f, 1f, 1f)

private const val MEOW_MS = 1400L // each "meow" floats up and fades over this long; the second follows 450 ms later

// walking: a cat's lateral sequence (hind right, fore right, hind left, fore left), each paw down 70% of the cycle
private const val DUTY = 0.7f
private const val STEP = 4.5f
private const val STRIDE = 2 * STEP / DUTY // ground covered per cycle, so planted paws don't slide
private val PAW_PHASE = floatArrayOf(0.5f, 0f, 0.75f, 0.25f) // back-far, back-near, front-far, front-near
private const val TAU = 2 * PI.toFloat()

private val SIDE_EYES = floatArrayOf(4.2f, -1.8f)
private val FRONT_EYES = floatArrayOf(-3.4f, -1f, 3.4f, -1f)

private enum class Mode { SIT, WALK, STRETCH, JUMP }

/** A chubby line-drawn cat beside the clock. It sits and looks around, wanders, stretches, and jumps when tapped. */
class CatView(context: Context, fg: Int, bg: Int) : View(context) {
    private val u = resources.displayMetrics.density * 0.9f // px per drawing unit
    private val art = LineArt(fg, bg)
    private val meowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = fg
        typeface = resources.getFont(R.font.rubik)
    }

    // a leg is a thigh, a shin and a paw, each tapering into the next; 0 width = a filled shape
    private val farLegs = Array(6) { Path() }
    private val farWidths = FloatArray(6)
    private val parts = Array(12) { Path() } // body, neck, tail, near back leg (3), near front leg (3), ears, skull, cheeks
    private val widths = floatArrayOf(0f, 10f, 3.4f, 0f, 0f, 0f, 0f, 0f, 0f, 0f, 0f, 0f)
    private val oval = RectF()
    private val bodyM = Matrix()
    private val m = Matrix()
    private val pts = FloatArray(4)

    private val cur = FloatArray(23)
    private val vel = FloatArray(23)
    private val goal = FloatArray(23)
    private var started = false
    private var mode = Mode.SIT
    private var since = 0L
    private var nextIdea = 0L
    private var look = 0 // 0 ahead, 1 up, 2 at you
    private var nextLook = 0L
    private var pos = -1f // left edge of the cat's box in px; -1 until we know our width
    private var target = 0f
    private var facing = 1f
    private var speed = 0f // drawing units per second
    private var phase = 0f // walk cycle, 0..1
    private var jumpStage = 0 // 0 crouch, 1 in the air, 2 landed
    private var lift = 0f // jump height in units, negative is up
    private var liftSpeed = 0f
    private var landedAt = 0L
    private var blinkAt = 0L
    private var blinkLen = 150L
    private var meowAt = -10_000L
    private var last = 0L

    init {
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
    }

    override fun onDraw(c: Canvas) {
        val now = SystemClock.uptimeMillis()
        // drawing stops while the home screen is hidden, so cap the step to avoid a jump on return
        val dt = if (last == 0L) 0f else min(now - last, 50L) / 1000f
        last = now
        val lo = 4 * u
        val hi = width - (W + 4) * u
        if (hi <= lo) return
        val moves = ValueAnimator.areAnimatorsEnabled() // off when the phone's animations are turned off
        if (pos < 0) {
            pos = lo + Random.nextFloat() * (hi - lo)
            nextIdea = now + Random.nextLong(3000, 8000)
        }
        pos = pos.coerceIn(lo, hi)
        if (moves) think(now, dt, lo, hi)
        aim(now)
        if (!started || !moves) {
            goal.copyInto(cur)
            started = true
        } else spring(dt)
        if (now > blinkAt + blinkLen) {
            blinkAt = now + Random.nextLong(2000, 6000)
            blinkLen = if (look == 2 && Random.nextBoolean()) 700L else 150L // a slow blink at you now and then
        }

        c.save()
        c.translate(pos, height - H * u + lift * u)
        c.scale(u, u)
        drawCat(c, moves && now >= blinkAt)
        val meowing = moves && now - meowAt < MEOW_MS + 450
        if (meowing) for (k in 0..1) meow(c, now - meowAt - 450 * k, k)
        c.restore()

        if (!moves) return
        removeCallbacks(tick) // one redraw loop, however many times we got drawn
        // 30 fps while moving (as smooth as the preview, a third of a 90 Hz screen's frames); sitting still,
        // only the breathing and tail move, so 10 fps is plenty. Waiting for the frame after the delay keeps the pacing even.
        postOnAnimationDelayed(tick, if (mode != Mode.SIT || !settled() || meowing) 25L else 90L)
    }

    private val tick = Runnable { invalidate() }

    /** A little "meow" [ms] into its life, just past the cheek, floating up and fading; [k] 1 is the smaller second one. */
    private fun meow(c: Canvas, ms: Long, k: Int) {
        val t = ms.toFloat() / MEOW_MS
        if (t < 0 || t >= 1) return
        meowPaint.textSize = if (k == 0) 8f else 6.5f
        meowPaint.alpha = (255 * min(1f, t / 0.12f) * min(1f, (1 - t) / 0.35f)).toInt()
        val w = meowPaint.measureText("meow")
        val x = cur[4] + facing * (11 + 6 * k)
        // reads away from the face, and stays inside the view
        val left = (if (facing > 0) x else x - w).coerceIn(-pos / u, (width - pos) / u - w)
        c.drawText("meow", left, cur[5] - 2 - 11 * k - 4 * (1 - (1 - t) * (1 - t)), meowPaint)
    }

    override fun onTouchEvent(e: MotionEvent): Boolean {
        if (e.action != MotionEvent.ACTION_DOWN || mode == Mode.JUMP) return false
        if (e.x < pos || e.x > pos + W * u || e.y < height - H * u) return false
        start(Mode.JUMP, SystemClock.uptimeMillis())
        jumpStage = 0
        invalidate()
        return true
    }

    private fun start(next: Mode, now: Long) {
        mode = next
        since = now
    }

    /** Decides what the cat does next and moves it. */
    private fun think(now: Long, dt: Float, lo: Float, hi: Float) {
        when (mode) {
            Mode.SIT -> {
                if (now >= nextLook) {
                    look = Random.nextInt(3)
                    nextLook = now + Random.nextLong(2500, 6000)
                    if (look == 2 && Random.nextFloat() < 0.4f) meowAt = now + 500 // says hi now and then
                }
                if (now < nextIdea) return
                if (Random.nextFloat() < 0.25f) return start(Mode.STRETCH, now)
                target = lo + Random.nextFloat() * (hi - lo)
                if (abs(target - pos) > 10 * u) {
                    facing = if (target > pos) 1f else -1f
                    look = 0
                    start(Mode.WALK, now)
                } else nextIdea = now + 3000
            }
            Mode.WALK -> {
                target = target.coerceIn(lo, hi)
                val left = abs(target - pos) / u
                val turned = (cur[2] - cur[0]) * facing > 14 // finish turning round before setting off
                val want = if (turned) min(15f, left * 2.5f) else 0f // eases in, and slows to a stop
                speed += (want - speed) * min(1f, dt * 5)
                val moved = min(speed * dt, left)
                pos += moved * u * facing
                phase = (phase + moved / STRIDE) % 1f // feet keep pace with the ground, so they don't slide
                if (turned && left < 0.3f && speed < 1.5f) sit(now)
            }
            Mode.STRETCH -> if (now - since > 2400) sit(now)
            Mode.JUMP -> when (jumpStage) {
                0 -> if (now - since > 220) {
                    jumpStage = 1
                    liftSpeed = -75f
                }
                1 -> {
                    liftSpeed += 260f * dt
                    lift += liftSpeed * dt
                    pos = (pos + 10 * u * facing * dt).coerceIn(lo, hi)
                    if (lift >= 0f) {
                        lift = 0f
                        jumpStage = 2
                        landedAt = now
                        meowAt = now + 600
                    }
                }
                else -> if (now - landedAt > 160) sit(now)
            }
        }
    }

    private fun sit(now: Long) {
        start(Mode.SIT, now)
        nextIdea = now + Random.nextLong(5000, 14000)
    }

    /** Sets the pose the cat is heading for. */
    private fun aim(now: Long) {
        val t = now / 1000f
        when (mode) {
            Mode.SIT -> SIT
            Mode.WALK -> STAND
            Mode.STRETCH -> BOW
            Mode.JUMP -> if (jumpStage == 1) AIR else CROUCH
        }.copyInto(goal)
        when (mode) {
            Mode.SIT -> {
                val breath = sin(t * 2f) * 0.35f
                goal[3] += breath
                goal[5] += breath
                if (look == 1) goal[TILT] = -0.45f
                if (look == 2) goal[FACE] = 1f
                goal[18] += sin(t * 1.3f) * 2f
            }
            Mode.WALK -> {
                for (i in 0..3) {
                    val q = (phase + PAW_PHASE[i]) % 1f
                    if (q < DUTY) goal[8 + 2 * i] += STEP - 2 * STEP * q / DUTY // planted, sliding back under the body
                    else {
                        val s = (q - DUTY) / (1 - DUTY) // lifted, swinging forward
                        goal[8 + 2 * i] += -STEP + 2 * STEP * s * s * (3 - 2 * s)
                        goal[9 + 2 * i] -= 3f * sin(PI.toFloat() * s)
                    }
                }
                goal[1] += 0.5f * sin(2 * TAU * phase)
                goal[3] += 0.5f * sin(2 * TAU * phase + 1.5f)
                goal[16] += sin(TAU * phase) * 1.5f
                goal[18] += sin(TAU * phase - 1.2f) * 2.5f
            }
            Mode.STRETCH -> goal[18] += sin(t * 2.5f) * 1.5f
            Mode.JUMP -> {}
        }
        if (facing < 0) for (i in XS) goal[i] = W - goal[i]
        if (started && (cur[2] - cur[0]) * facing < 4) goal[FACE] = 1f // faces you while turning round
    }

    private fun spring(dt: Float) {
        val slow = if (mode == Mode.STRETCH) 0.55f else 1f // stretches are long and lazy
        val n = max(1, ceil(dt * 120).toInt())
        val h = dt / n
        repeat(n) {
            for (i in cur.indices) {
                val w = OMEGA[i] * slow
                vel[i] += (w * w * (goal[i] - cur[i]) - 2 * ZETA[i] * w * vel[i]) * h
                cur[i] += vel[i] * h
            }
        }
    }

    private fun settled() = (0 until 16).all { abs(vel[it]) < if (it == TILT || it == FACE) 0.2f else 1f }

    private fun drawCat(c: Canvas, blink: Boolean) {
        val p = cur
        val side = if (p[2] >= p[0]) 1f else -1f
        val len = hypot(p[2] - p[0], p[3] - p[1])
        val ux = if (len > 0.5f) (p[2] - p[0]) / len else side
        val uy = if (len > 0.5f) (p[3] - p[1]) / len else 0f
        leg(farLegs, 0, p[0], p[1] + 4, p[8], p[9], side, p[20], p[22])
        leg(farLegs, 3, p[2], p[3] + 4, p[12], p[13], side, p[21], 3.8f)
        body(parts[0], len)
        // the body is drawn along the spine from the hip; mirrored when the cat faces left so the belly stays down
        bodyM.setValues(floatArrayOf(ux, -uy * side, p[0], uy, ux * side, p[1], 0f, 0f, 1f))
        parts[0].transform(bodyM)
        parts[1].line(p[2], p[3], p[4], p[5])
        parts[2].apply {
            reset()
            moveTo(p[0] - ux * 7, p[1] - uy * 7)
            quadTo(p[16], p[17], p[18], p[19])
        }
        leg(parts, 3, p[0], p[1] + 4, p[10], p[11], side, p[20], p[22])
        leg(parts, 6, p[2], p[3] + 4, p[14], p[15], side, p[21], 3.8f)
        val front = p[FACE] > 0.5f
        head(front)
        // the head narrows as it swaps between side-on and facing you, so the turn reads as a turn
        m.setScale(side * (0.8f + 0.2f * abs(2 * p[FACE] - 1)), 1f)
        m.postRotate(Math.toDegrees((p[TILT] * side).toDouble()).toFloat())
        m.postTranslate(p[4], p[5])
        for (i in 9..11) parts[i].transform(m)

        art.group(c, farLegs, farWidths)
        art.group(c, parts, widths)
        val eyes = if (front) FRONT_EYES else SIDE_EYES
        for (i in eyes.indices step 2) {
            pts[0] = eyes[i] - if (blink) 1.3f else 0f
            pts[1] = eyes[i + 1]
            pts[2] = eyes[i] + 1.3f
            pts[3] = eyes[i + 1]
            m.mapPoints(pts)
            if (blink) c.drawLine(pts[0], pts[1], pts[2], pts[3], art.ink) else c.drawCircle(pts[0], pts[1], 1.1f, art.dot)
        }
    }

    /** Side-on cat body of spine length [l]: x runs from the hip to the shoulder, y down towards the belly. */
    private fun body(path: Path, l: Float) {
        path.reset()
        path.moveTo(-7f, -6f)
        path.cubicTo(-3f, -9.5f, 3f, -9.5f, l * 0.5f, -8f) // over the hips
        path.cubicTo(l * 0.8f, -7f, l, -10f, l + 5f, -7f) // up over the shoulder blades
        path.cubicTo(l + 9f, -4f, l + 9.5f, 4f, l + 4f, 8f) // chest
        path.cubicTo(l * 0.7f, 12f, l * 0.3f, 12.5f, 0f, 9.5f) // round belly
        path.cubicTo(-6f, 8f, -10f, 1f, -7f, -6f) // rump
        path.close()
    }

    /** Two-bone leg from the hip (or shoulder) to the paw, into paths[at..at+2]; [knee] 1 bends it backwards, 0 keeps it straight. */
    private fun leg(paths: Array<Path>, at: Int, ax: Float, ay: Float, px: Float, py: Float, side: Float, knee: Float, thigh: Float) {
        val len = max(hypot(px - ax, py - ay), 0.01f)
        val ux = (px - ax) / len
        val uy = (py - ay) / len
        val d = min(len, 2 * BONE - 0.01f)
        val ex = ax + ux * d
        val ey = ay + uy * d
        val bend = sqrt(max(0f, BONE * BONE - d * d / 4)) * side * knee
        val kx = (ax + ex) / 2 - uy * bend
        val ky = (ay + ey) / 2 + ux * bend
        paths[at].taper(ax, ay, thigh, kx, ky, 2.7f, oval) // the knee is as wide as the shin, so no knob
        paths[at + 1].taper(kx, ky, 2.7f, ex, ey, 2.1f, oval)
        paths[at + 2].taper(ex, ey, 2.1f, ex + 1.8f * side, ey + 0.3f, 2.2f, oval)
    }

    /** Ears, skull and cheeks around (0, 0), facing right; drawCat moves them into place. */
    private fun head(front: Boolean) {
        val (ears, skull, cheeks) = Triple(parts[9], parts[10], parts[11])
        ears.reset()
        skull.reset()
        cheeks.reset()
        skull.addCircle(0f, 0f, 8.5f, Path.Direction.CW)
        if (front) {
            ears.triangle(-8.2f, -3f, -7.5f, -13.5f, -2.5f, -7.8f)
            ears.triangle(8.2f, -3f, 7.5f, -13.5f, 2.5f, -7.8f)
            cheeks.addOval(-10f, -3f, 10f, 8f, Path.Direction.CW)
        } else {
            ears.triangle(-6.5f, -4f, -5f, -13f, 0f, -7.8f)
            ears.triangle(1f, -8.2f, 6f, -12.5f, 7.2f, -3.5f)
            cheeks.addCircle(6f, 2.5f, 4.2f, Path.Direction.CW)
        }
    }

    private fun Path.triangle(x1: Float, y1: Float, x2: Float, y2: Float, x3: Float, y3: Float) {
        moveTo(x1, y1)
        lineTo(x2, y2)
        lineTo(x3, y3)
        close()
    }
}
