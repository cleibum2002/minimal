package com.angel.minimal

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.os.SystemClock
import android.view.MotionEvent
import android.view.View
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.acos
import kotlin.math.atan2
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.random.Random

private const val W = 60f // Rocky's box in drawing units; his hands stand at y = 48
private const val H = 56f
private const val THIGH = 13f // thick upper arm out sideways from the shoulder, long forearm down to the hand
private const val SHIN = 24f
private const val HIP_R = 8f // shoulders round the top of the body
private const val FOOT_R = 22f // hands round the ground
private const val DEPTH = 0.2f // nearer things sit lower on screen
private const val STAND_Y = 26f // body centre height
private const val REST_Y = 32f
private const val DUTY = 0.8f // each hand down 80% of the cycle; five hands, so four are always down
private const val STEP = 4f
private const val STRIDE = 2 * STEP / DUTY
private const val NOTE_MS = 1600L
private const val SAY_MS = 2000L
private const val BUMP_MS = 1600L
private const val TAP_MS = 350L
private const val AMAZE_MS = 2200L
private const val BUMPER = 1 // the front-right arm does the fist bump
private const val CHEERER = 2 // and the front-left joins in for "amaze"
private const val BEAT_MS = 450L // dance tempo, about 133 bpm
private const val SWAP = 0 // the dance from the film: one hand up, one folded across him, trading places
private const val PARTY = 1 // sway, raise the roof, twirl, both hands up
private val DANCE_BEATS = intArrayOf(13, 14)
private const val TAU = 2 * PI.toFloat()

// Seen from the front: two arms towards us, two to the sides, one hidden behind. Eridians have no front, so he never
// turns to walk; he only spins when dancing, by two arms' worth, which ends in the same pose.
private const val YAW = -0.314f // -18 degrees

// a tall five-sided column of rock plates, a little narrower at the bottom; centre (0, 0)
private val BODY = floatArrayOf(-7.5f, -10.8f, -5f, -13.1f, -1f, -13.7f, 3.5f, -13.4f, 7f, -11.4f, 8f, -3.3f, 7.5f, 3.6f,
    6f, 8.8f, 2.5f, 11.7f, -2.5f, 11.7f, -6f, 8.8f, -7.5f, 3.6f, -8f, -3.3f)

private enum class Act { REST, WALK, BUMP, AMAZE, DANCE }

/** Rocky the Eridian beside the clock. He rests, chirps in chords, wanders, says "amaze", dances, and fist-bumps when tapped. */
class RockyView(context: Context, fg: Int, bg: Int) : View(context) {
    private val u = resources.displayMetrics.density * 0.9f // px per drawing unit
    private val art = LineArt(fg, bg)
    private val noteFill = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = fg }
    private val noteLine = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        color = fg
        strokeCap = Paint.Cap.ROUND
    }
    private val sayPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = fg
        typeface = resources.getFont(R.font.rubik)
        textSize = 7f
    }

    // per arm: upper arm, forearm, three fingers
    private val arms = Array(25) { Path() }
    private val armWidths = FloatArray(25)
    private val body = arrayOf(Path().apply {
        moveTo(BODY[0], BODY[1])
        for (i in 2 until BODY.size step 2) lineTo(BODY[i], BODY[i + 1])
        close()
    })
    private val bodyWidth = floatArrayOf(0f)
    private val edges = Path()
    private val co = FloatArray(5) // each arm's direction round the body this frame
    private val si = FloatArray(5) // and how far towards us it points
    private val order = IntArray(5) { it } // arms back to front
    private val flag = Path()
    private val oval = RectF()

    private var act = Act.REST
    private var since = 0L
    private var nextIdea = 0L
    private var pos = -1f // left edge of Rocky's box in px; -1 until we know our width
    private var target = 0f
    private var facing = 1f
    private var speed = 0f // drawing units per second
    private var phase = 0f // walk cycle, 0..1
    private var y = REST_Y
    private var vy = 0f
    private var bump = 0f // 0..1, the right hand raised for a fist bump
    private var vbump = 0f
    private var cheer = 0f // 0..1, the left hand raised too
    private var vcheer = 0f
    private var groove = 0f // 0..1, how much he's dancing
    private var vgroove = 0f
    private var swap = 0f // dancing: 1 right hand up and left folded across him, -1 the other way round
    private var vswap = 0f
    private var chirpAt = -10_000L
    private var chirpN = 0
    private var sayAt = -10_000L
    private var amazeAt = -10_000L
    private var tapMove = 0 // taps take turns: fist bump, amaze, the film's dance, the party dance
    private var routine = SWAP
    private var chordBeat = -1 // the dance beat that last got a chord
    private var nextChirp = 0L
    private var tapArm = 0 // resting, he drums one hand at a time
    private var tapAt = -10_000L
    private var nextTap = 0L
    private var started = false
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
        val goalY = if (act == Act.REST) REST_Y else STAND_Y
        val beat = (now - since).toFloat() / BEAT_MS
        val party = act == Act.DANCE && routine == PARTY
        val goalCheer = when {
            act == Act.AMAZE -> if (now - since < AMAZE_MS) 1f else 0f
            party -> raised(CHEERER, beat)
            else -> 0f
        }
        val goalBump = when {
            act == Act.BUMP -> if (now - since < BUMP_MS) 1f else 0f
            party -> raised(BUMPER, beat)
            else -> goalCheer
        }
        val dancing = act == Act.DANCE && routine == SWAP && beat < DANCE_BEATS[SWAP] - 1
        val goalGroove = if (dancing) 1f else 0f
        // the arms trade places every two beats
        val goalSwap = if (!dancing) 0f else if ((beat / 2).toInt() % 2 == 0) 1f else -1f
        if (!started || !moves) {
            y = goalY
            bump = goalBump
            cheer = goalCheer
            groove = goalGroove
            swap = goalSwap
            started = true
        } else spring(dt, goalY, goalBump, goalCheer, goalGroove, goalSwap)

        c.save()
        c.translate(pos, height - H * u)
        c.scale(u, u)
        drawRocky(c, now, moves)
        c.restore()

        if (!moves) return
        removeCallbacks(tick) // one redraw loop, however many times we got drawn
        // 30 fps while anything moves; resting, only a slow bob, so 10 fps is plenty
        postOnAnimationDelayed(tick, if (busy(now)) 25L else 90L)
    }

    private val tick = Runnable { invalidate() }

    override fun onTouchEvent(e: MotionEvent): Boolean {
        if (e.action != MotionEvent.ACTION_DOWN || (act != Act.REST && act != Act.WALK)) return false
        if (e.x < pos || e.x > pos + W * u || e.y < height - H * u) return false
        val now = SystemClock.uptimeMillis()
        when (tapMove) {
            0 -> start(Act.BUMP, now)
            1 -> start(Act.AMAZE, now)
            else -> start(Act.DANCE, now, if (tapMove == 2) SWAP else PARTY)
        }
        tapMove = (tapMove + 1) % 4
        invalidate()
        return true
    }

    private fun start(next: Act, now: Long, dance: Int = Random.nextInt(2)) {
        act = next
        since = now
        if (next == Act.BUMP) sayAt = now + 250
        if (next == Act.AMAZE) amazeAt = now + 250
        if (next == Act.DANCE) {
            routine = dance
            chordBeat = -1
        }
    }

    /** The party dance, for a front arm: sway, raise the roof one hand at a time, twirl, then both hands up. */
    private fun raised(arm: Int, beat: Float) = when {
        beat < 4 -> 0f
        beat < 8 -> if (beat.toInt() % 2 == (if (arm == BUMPER) 0 else 1)) 1f else 0f
        beat < 10 -> 0f
        beat < DANCE_BEATS[PARTY] -> 1f
        else -> 0f
    }

    /** Decides what Rocky does next and moves him. */
    private fun think(now: Long, dt: Float, lo: Float, hi: Float) {
        if (act != Act.WALK) speed -= speed * min(1f, dt * 5) // hands settle back under him
        if (now >= nextChirp && (act == Act.REST || act == Act.WALK)) { // he talks in chords, often
            chirpAt = now
            chirpN = Random.nextInt(2, 4)
            nextChirp = now + Random.nextLong(4000, 9000)
        }
        when (act) {
            Act.REST -> {
                if (now >= nextTap) {
                    tapArm = Random.nextInt(4) // not the one hidden behind him
                    tapAt = now
                    nextTap = now + Random.nextLong(1200, 3500)
                }
                if (now < nextIdea) return
                nextIdea = now + Random.nextLong(3000, 8000)
                val roll = Random.nextFloat()
                if (roll < 0.15f) return start(Act.AMAZE, now)
                if (roll < 0.25f) return start(Act.DANCE, now)
                // somewhere on the far side of the space, so every walk is a real one
                val mid = (lo + hi) / 2
                target = if (pos < mid) mid + Random.nextFloat() * (hi - mid) else lo + Random.nextFloat() * (mid - lo)
                facing = if (target > pos) 1f else -1f
                act = Act.WALK
            }
            Act.WALK -> {
                target = target.coerceIn(lo, hi)
                val left = abs(target - pos) / u
                val up = y < STAND_Y + 2 // stands up before setting off
                val want = if (up) min(12f, left * 2.5f) else 0f
                speed += (want - speed) * min(1f, dt * 5)
                val moved = min(speed * dt, left)
                pos += moved * u * facing
                phase = (phase + moved / STRIDE) % 1f // hands keep pace with the ground, so they don't slide
                if (up && left < 0.3f && speed < 1f) rest(now)
            }
            Act.BUMP -> if (now - since > BUMP_MS + 600) rest(now)
            Act.AMAZE -> if (now - since > AMAZE_MS + 600) rest(now)
            Act.DANCE -> {
                val beat = ((now - since) / BEAT_MS).toInt()
                if (beat % 4 == 0 && beat != chordBeat && beat < DANCE_BEATS[routine]) { // a chord every bar
                    chordBeat = beat
                    chirpAt = now
                    chirpN = 3
                }
                if (now - since > DANCE_BEATS[routine] * BEAT_MS + 400) rest(now)
            }
        }
    }

    private fun rest(now: Long) {
        act = Act.REST
        nextIdea = now + Random.nextLong(3000, 8000)
    }

    private fun spring(dt: Float, goalY: Float, goalBump: Float, goalCheer: Float, goalGroove: Float, goalSwap: Float) {
        val n = max(1, ceil(dt * 120).toInt())
        val h = dt / n
        repeat(n) {
            vy += (64 * (goalY - y) - 2 * 0.85f * 8 * vy) * h
            y += vy * h
            vbump += (100 * (goalBump - bump) - 2 * 0.7f * 10 * vbump) * h
            bump += vbump * h
            vcheer += (100 * (goalCheer - cheer) - 2 * 0.7f * 10 * vcheer) * h
            cheer += vcheer * h
            vgroove += (64 * (goalGroove - groove) - 2 * 8 * vgroove) * h
            groove += vgroove * h
            vswap += (81 * (goalSwap - swap) - 2 * 0.75f * 9 * vswap) * h // a little overshoot, so each swap snaps
            swap += vswap * h
        }
    }

    private fun busy(now: Long) = act != Act.REST || abs(vy) > 0.3f || abs(vbump) > 0.05f || abs(vcheer) > 0.05f ||
        abs(vgroove) > 0.05f || abs(vswap) > 0.05f || speed > 0.05f ||
        now - chirpAt < NOTE_MS + 160 * chirpN || now - sayAt < SAY_MS || now - amazeAt < AMAZE_MS + 400 ||
        now - tapAt < TAP_MS

    private fun drawRocky(c: Canvas, now: Long, moves: Boolean) {
        val t = now / 1000f
        val beat = (now - since).toFloat() / BEAT_MS
        val party = moves && act == Act.DANCE && routine == PARTY && beat < DANCE_BEATS[PARTY]
        val pulse = abs(sin(PI.toFloat() * beat)) // one push per beat
        val bob = when {
            !moves -> 0f
            act == Act.WALK -> 0.5f * sin(5 * TAU * phase)
            act == Act.AMAZE -> 0.6f * sin(now * 0.016f) // excited bounce
            party -> 1.5f * (0.5f + 0.5f * cos(TAU * beat)) // dips on every beat
            // the film's dance: dips with each sway, and deeper as the arms trade places
            act == Act.DANCE -> groove * (1.2f * pulse + 1.5f * (1 - abs(swap)))
            else -> 0.6f * sin(t * 1.6f)
        }
        val sway = when {
            !moves -> 0f
            act == Act.REST -> 1.2f * sin(t * 0.9f) // a little, over planted hands
            party -> (if (beat < 4) 2.5f else 1.2f) * sin(PI.toFloat() * beat) // side to side on the beat
            else -> 2.5f * swap * groove * pulse // towards the raised hand, twice: left, left, then right, right
        }
        val cx = 30f + sway
        val cy = y + bob
        val hipY = cy - 6 // shoulders just under the top of the body
        val gait = min(1f, speed / 4f)
        // raised hands wave in turn
        val wiggle = if (moves && (act == Act.AMAZE || (party && beat >= 10))) 1.5f * sin(now * 0.012f) else 0f
        val tap = (now - tapAt).toFloat() / TAP_MS
        val tapLift = if (moves && act == Act.REST && tap in 0f..1f) 3.5f * sin(PI.toFloat() * tap) else 0f
        // the party twirl: two arms' worth round, eased, stepping each hand as it goes
        val spin = if (party && beat >= 8 && beat < 10) (beat - 8) / 2 else 0f
        // the film's dance twists a little towards the raised hand
        val turn = YAW + spin * spin * (3 - 2 * spin) * 2 * TAU / 5 + 0.15f * swap * groove
        for (i in 0..4) {
            co[i] = cos(turn + i * TAU / 5)
            si[i] = sin(turn + i * TAU / 5)
        }
        for (i in 1..4) { // back to front
            val a = order[i]
            var j = i - 1
            while (j >= 0 && si[order[j]] > si[a]) order[j + 1] = order[j--]
            order[j + 1] = a
        }
        for (i in 0..4) {
            val step = if (spin > 0) 3f * max(0f, sin(TAU * (2 * spin + i / 5f))) else 0f
            arm(i, cx, hipY, gait, wiggle, step + if (i == tapArm) tapLift else 0f)
        }
        edges.reset() // the column's upright edges on the side facing us
        for (k in 0..4) {
            val a = turn + (k + 0.5f) * TAU / 5
            if (sin(a) < 0.25f) continue
            val x = 6.5f * cos(a)
            edges.moveTo(x, -11f)
            edges.lineTo(x * 0.97f, 0f)
            edges.lineTo(x * 0.8f, 9f)
        }

        for (i in order) if (si[i] < -0.05f) drawArm(c, i)
        c.save()
        c.translate(cx, cy)
        art.group(c, body, bodyWidth)
        c.drawPath(edges, art.ink)
        c.restore()
        for (i in order) if (si[i] >= -0.05f) drawArm(c, i)

        // music: Rocky talks in chords
        for (n in 0 until chirpN) {
            val k = (now - chirpAt - 160 * n).toFloat() / NOTE_MS
            if (k < 0 || k >= 1) continue
            val alpha = (255 * min(1f, k / 0.12f) * min(1f, (1 - k) / 0.4f)).toInt()
            noteFill.alpha = alpha
            noteLine.alpha = alpha
            val dx = (n - 1) * 7f
            val nx = cx + dx + sin(k * 5 + dx) * 1.5f
            val ny = cy - 19 - 12 * (1 - (1 - k) * (1 - k)) - dx * 0.4f
            c.save()
            c.scale(1.4f, 1.4f, nx, ny)
            note(c, nx, ny, n % 2 == 1)
            c.restore()
        }
        val k = (now - sayAt).toFloat() / SAY_MS
        if (k >= 0 && k < 1) {
            sayPaint.alpha = (255 * min(1f, k / 0.1f) * min(1f, (1 - k) / 0.3f)).toInt()
            val text = "fist my bump"
            val w = sayPaint.measureText(text)
            // stays inside the view
            c.drawText(text, (cx + 12).coerceIn(-pos / u, (width - pos) / u - w), cy - 28 - 3 * k, sayPaint)
        }
        // "amaze amaze amaze" above both raised hands, one word at a time
        val ka = (now - amazeAt).toFloat() / (AMAZE_MS + 400)
        if (ka >= 0 && ka < 1) {
            val full = sayPaint.measureText("amaze amaze amaze")
            val word = sayPaint.measureText("amaze ")
            val x = (cx - full / 2).coerceIn(-pos / u, (width - pos) / u - full)
            for (n in 0..2) {
                val pop = (now - amazeAt - 300 * n) / 150f
                if (pop <= 0) break
                sayPaint.alpha = (255 * min(1f, pop) * min(1f, (1 - ka) / 0.25f)).toInt()
                c.drawText("amaze", x + n * word, cy - 30 - 3 * ka, sayPaint)
            }
        }
    }

    /** Solves arm [i] from shoulder to hand and builds its paths: two-bone IK in the arm's own upright plane. */
    private fun arm(i: Int, cx: Float, hipY: Float, gait: Float, wiggle: Float, lift: Float) {
        val hx = cx + HIP_R * co[i]
        val hz = HIP_R * si[i]
        var fx = 30f + FOOT_R * co[i] // hands stay put when the body sways
        var fz = FOOT_R * si[i]
        var fy = 48f - lift
        val q = (phase + i / 5f) % 1f
        if (q < DUTY) fx += (STEP - 2 * STEP * q / DUTY) * facing * gait // planted, sliding back under him
        else {
            val s = (q - DUTY) / (1 - DUTY) // lifted, swinging forward
            fx += (-STEP + 2 * STEP * s * s * (3 - 2 * s)) * facing * gait
            fy -= 4 * sin(PI.toFloat() * s) * gait
        }
        val raise = when (i) {
            BUMPER -> bump
            CHEERER -> cheer
            else -> 0f
        }
        if (raise > 0.01f) { // the hand comes up beside him, on its own side
            val side = if (co[i] > 0) 1 else -1
            fx += (cx + 22 * side - fx) * raise
            fz += (8 - fz) * raise
            fy += (hipY - 16 + wiggle * side - fy) * raise
        }
        val dx = fx - hx
        val dz = fz - hz
        val dy = fy - hipY
        val d = max(hypot(dx, dz), 0.01f)
        val full = max(hypot(d, dy), 0.01f)
        val l = full.coerceIn(SHIN - THIGH + 0.01f, THIGH + SHIN - 0.01f)
        val es = d * l / full
        val ey = dy * l / full
        val al = acos(((THIGH * THIGH + l * l - SHIN * SHIN) / (2 * THIGH * l)).coerceIn(-1f, 1f))
        val bend = 1 - 2 * raise // raising a hand, the elbow drops below it
        val ka = atan2(ey, es) - al * bend // upper arm swung up from the shoulder-to-hand line
        val ks = THIGH * cos(ka)
        val ux = dx / d
        val uz = dz / d
        // (along the arm, height) to screen: nearer points sit a little lower
        val sx = hx
        val sy = hipY + hz * DEPTH
        var kx = hx + ux * ks
        var ky = hipY + THIGH * sin(ka) + (hz + uz * ks) * DEPTH
        var wx = hx + ux * es
        var wy = hipY + ey + (hz + uz * es) * DEPTH
        // the dance from the film: one front hand straight up, elbow out at shoulder height; the other folded
        // across his front, elbow out low and forearm level. Placed directly, since the elbow leaves the arm's plane.
        val mine = when (i) {
            BUMPER -> swap
            CHEERER -> -swap
            else -> 0f
        } * groove
        if (mine != 0f) {
            val side = if (co[i] > 0) 1 else -1
            val up = max(0f, mine)
            val fold = max(0f, -mine)
            kx += (cx + 18 * side - kx) * up + (cx + 13 * side - kx) * fold
            ky += (hipY + 2 - ky) * up + (hipY + 8 - ky) * fold
            wx += (cx + 18 * side - wx) * up + (cx - 5 * side - wx) * fold
            wy += (hipY - 18 - wy) * up + (hipY + 9 - wy) * fold
        }
        val w = 1 + 0.12f * si[i] // nearer arms a touch thicker
        val at = i * 5
        arms[at].taper(sx, sy, 3.4f * w, kx, ky, 2.9f * w, oval)
        arms[at + 1].taper(kx, ky, 3f * w, wx, wy, 2f * w, oval)
        val a = atan2(wy - ky, wx - kx)
        for (f in 0..2) {
            val spread = a + (f - 1) * 0.45f
            arms[at + 2 + f].line(wx, wy, wx + 3 * cos(spread), wy + 3 * sin(spread))
            armWidths[at + 2 + f] = 1.3f * w
        }
    }

    /** Upper arm, then forearm with fingers, so the elbow shows as a joint. */
    private fun drawArm(c: Canvas, i: Int) {
        art.group(c, arms, armWidths, i * 5, i * 5 + 1)
        art.group(c, arms, armWidths, i * 5 + 1, i * 5 + 5)
    }

    /** A music note at (x, y): one with a flag, or two beamed together. */
    private fun note(c: Canvas, x: Float, y: Float, beamed: Boolean) {
        head(c, x, y)
        noteLine.strokeWidth = 0.8f
        c.drawLine(x + 1.4f, y - 0.3f, x + 1.4f, y - 6, noteLine)
        if (beamed) {
            head(c, x + 4, y - 1)
            c.drawLine(x + 5.4f, y - 1.3f, x + 5.4f, y - 7, noteLine)
            noteLine.strokeWidth = 1.3f
            c.drawLine(x + 1.4f, y - 6, x + 5.4f, y - 7, noteLine)
        } else {
            flag.reset()
            flag.moveTo(x + 1.4f, y - 6)
            flag.quadTo(x + 3.2f, y - 4.8f, x + 3.1f, y - 3)
            c.drawPath(flag, noteLine)
        }
    }

    private fun head(c: Canvas, x: Float, y: Float) {
        c.save()
        c.rotate(-20f, x, y)
        oval.set(x - 1.6f, y - 1.15f, x + 1.6f, y + 1.15f)
        c.drawOval(oval, noteFill)
        c.restore()
    }
}
