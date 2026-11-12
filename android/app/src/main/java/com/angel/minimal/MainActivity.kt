package com.angel.minimal

import android.animation.LayoutTransition
import android.animation.ObjectAnimator
import android.animation.ValueAnimator
import android.app.Activity
import android.app.AlertDialog
import android.content.ActivityNotFoundException
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Paint
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.BatteryManager
import android.os.Bundle
import android.os.CountDownTimer
import android.os.SystemClock
import android.provider.AlarmClock
import android.provider.MediaStore
import android.provider.Settings
import android.text.Editable
import android.text.InputType
import android.text.SpannableString
import android.text.TextWatcher
import android.text.style.ForegroundColorSpan
import android.text.InputFilter
import android.view.GestureDetector
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.view.WindowInsets
import android.view.WindowInsetsController
import android.view.animation.DecelerateInterpolator
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.ScrollView
import android.widget.TextClock
import android.widget.TextView
import android.widget.Toast
import android.window.OnBackInvokedDispatcher
import org.json.JSONObject
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlin.concurrent.thread
import kotlin.math.abs

private const val LETTERS = "#ABCDEFGHIJKLMNOPQRSTUVWXYZ"
private const val DAY = 24 * 60 * 60_000L

// always kept built, so home, the app list and the pause screen appear instantly
private val EAGER = listOf("home", "drawer", "pause")

/**
 * The whole launcher is one screen holding several views: home, drawer (app list), settings,
 * pause, usage (screen time) and mono (monochrome setup).
 * A settings change rebuilds them; settings, usage and mono are only built when opened.
 * Slow work (reading every app's name, today's usage log) runs off the main thread in [refresh].
 */
class MainActivity : Activity() {

    private data class App(val pkg: String, val name: String)

    private lateinit var prefs: Prefs
    private lateinit var root: FrameLayout
    private val screens = linkedMapOf<String, View>()
    private lateinit var list: LinearLayout
    private lateinit var drawerScroll: ScrollView
    private lateinit var letters: LinearLayout
    private lateinit var bubble: TextView
    private lateinit var search: EditText
    private lateinit var battery: TextView
    private lateinit var batteryFill: View
    private var screenTimeView: TextView? = null
    private lateinit var settingsScroll: ScrollView
    private lateinit var pauseTitle: TextView
    private lateinit var count: TextView
    private lateinit var pauseText: TextView
    private lateinit var openAnyway: TextView

    private var apps = listOf<App>()
    private var labels = mapOf<String, String>() // package → app name, for fast lookups
    private var names = JSONObject()
    private var usage = mapOf<String, Long>() // today's screen time per app, in ms
    private var usageAllowed = false
    private var rules = listOf<Rule>()
    private var lastLetter = ' '
    private var screen = "home"
    private var prev = "home" // where back returns to from settings, pause or screen time
    private var pausedPkg: String? = null
    private var countdown: CountDownTimer? = null
    private var batteryLevel = 0
    private var charging = false
    private var pulse: ObjectAnimator? = null
    private var hiddenOpen = false // the hidden-apps dropdown in settings
    private var editing = false // home apps can be dragged and removed
    private var timing = "" // how long the last refresh took, shown at the bottom of settings

    // theme, recalculated on every render
    private var bg = 0
    private var fg = 0
    private var dim = 0
    private var rule = 0
    private var fs = 22f
    private val dark get() = prefs["theme"] == "dark"
    private val dialogTheme
        get() = if (dark) android.R.style.Theme_DeviceDefault_Dialog_Alert else android.R.style.Theme_DeviceDefault_Light_Dialog_Alert
    private val align
        get() = when (prefs["align"]) {
            "center" -> Gravity.CENTER_HORIZONTAL
            "right" -> Gravity.END
            else -> Gravity.START
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = Prefs(this)
        window.setDecorFitsSystemWindows(false) // Android 15+ does this by itself; Android 14 needs asking
        window.isNavigationBarContrastEnforced = false
        root = FrameLayout(this)
        // the app draws behind the status bar, nav buttons and keyboard, so pad around them
        root.setOnApplyWindowInsetsListener { v, insets ->
            val b = insets.getInsets(WindowInsets.Type.systemBars() or WindowInsets.Type.ime())
            v.setPadding(b.left, b.top, b.right, b.bottom)
            WindowInsets.CONSUMED
        }
        setContentView(root)
        onBackInvokedDispatcher.registerOnBackInvokedCallback(OnBackInvokedDispatcher.PRIORITY_DEFAULT) { back() }
        setApps(prefs.cachedApps().map { (pkg, name) -> App(pkg, name) })
        render()
    }

    override fun onResume() {
        super.onResume()
        batteryFill.scaleX = 0f // the battery bar fills up again each time you come back
        registerReceiver(batteryReceiver, IntentFilter(Intent.ACTION_BATTERY_CHANGED), RECEIVER_NOT_EXPORTED)
        if (screen !in EAGER) rebuild(screen) // e.g. back from the phone's settings with a permission now on
        refresh()
        showLastCrash()
    }

    /** Reloads the app list and today's screen time off the main thread, then redraws only what changed. */
    private fun refresh() {
        thread {
            val t0 = SystemClock.uptimeMillis()
            val fresh = queryApps()
            val t1 = SystemClock.uptimeMillis()
            val allowed = ScreenTime.allowed(this)
            val today = if (allowed) ScreenTime.today(this) else emptyMap()
            val t2 = SystemClock.uptimeMillis()
            runOnUiThread {
                if (isDestroyed) return@runOnUiThread
                val t3 = SystemClock.uptimeMillis()
                val changed = fresh != apps || allowed != usageAllowed
                if (fresh != apps) {
                    setApps(fresh)
                    prefs.cacheApps(fresh.map { it.pkg to it.name })
                }
                usageAllowed = allowed
                usage = today.filterKeys { installed(it) }
                if (changed) render() else updateScreenTime()
                timing = "apps ${t1 - t0} ms · screen time ${t2 - t1} ms · redraw ${SystemClock.uptimeMillis() - t3} ms"
            }
        }
    }

    private fun updateScreenTime() {
        screenTimeView?.text = screenTimeText()
        if (screen == "usage") rebuild("usage")
    }

    private fun screenTimeText() = if (usageAllowed) "${ScreenTime.format(usage.values.sum())} today" else "tap to turn on screen time"

    /** If the app crashed last time, show the error once so it can be shared for fixing. */
    private fun showLastCrash() {
        val file = MinimalApp.crashFile(application)
        if (!file.exists()) return
        val report = file.readText()
        file.delete()
        val text = tv(report).styled(11f).apply {
            typeface = Typeface.MONOSPACE
            setTextIsSelectable(true)
            setPadding(dp(22), dp(8), dp(22), dp(8))
        }
        AlertDialog.Builder(this, dialogTheme).setTitle("Minimal crashed last time")
            .setView(ScrollView(this).apply { addView(text) })
            .setPositiveButton("Share") { _, _ ->
                startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, report), null))
            }
            .setNegativeButton("Close", null)
            .show()
    }

    override fun onPause() {
        super.onPause()
        unregisterReceiver(batteryReceiver)
        pulse?.cancel()
    }

    override fun onStop() {
        super.onStop()
        if (editing) stopEditing()
        // come back to a clean home screen after opening an app; stay put after a trip to the phone's settings
        if (screen == "drawer" || screen == "pause") show("home", animate = false)
    }

    /** The home button was pressed while we're already in front. */
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        if (editing) stopEditing()
        if (screen != "home") show("home")
    }

    private val batteryReceiver = object : BroadcastReceiver() {
        override fun onReceive(c: Context, i: Intent) {
            batteryLevel = i.getIntExtra(BatteryManager.EXTRA_LEVEL, 0) * 100 / i.getIntExtra(BatteryManager.EXTRA_SCALE, 100)
            val status = i.getIntExtra(BatteryManager.EXTRA_STATUS, -1)
            charging = status == BatteryManager.BATTERY_STATUS_CHARGING || status == BatteryManager.BATTERY_STATUS_FULL
            showBattery()
        }
    }

    /** The bar slides to the battery level; while charging it's brighter and gently pulses. */
    private fun showBattery() {
        battery.text = if (charging) "$batteryLevel% · charging" else "$batteryLevel%"
        batteryFill.setBackgroundColor(if (charging) fg else dim)
        batteryFill.animate().scaleX(batteryLevel / 100f).setDuration(900).setInterpolator(DecelerateInterpolator()).start()
        pulse?.cancel()
        batteryFill.alpha = 1f
        if (charging) pulse = ObjectAnimator.ofFloat(batteryFill, View.ALPHA, 1f, 0.3f).apply {
            duration = 1100
            repeatCount = ValueAnimator.INFINITE
            repeatMode = ValueAnimator.REVERSE
            start()
        }
    }

    // ---------- apps ----------

    /** Slow (it reads every app's name), so it runs in [refresh], off the main thread. */
    private fun queryApps(): List<App> {
        val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        return packageManager.queryIntentActivities(intent, 0)
            .map { App(it.activityInfo.packageName, it.loadLabel(packageManager).toString()) }
            .filter { it.pkg != packageName }
            // shortcut: one entry per package, so an app with two launcher icons shows only one; key by activity if that matters
            .distinctBy { it.pkg }
            .sortedBy { it.pkg } // stable order, so "did anything change?" is a plain comparison
    }

    private fun setApps(list: List<App>) {
        apps = list
        labels = list.associate { it.pkg to it.name }
    }

    private fun label(pkg: String) = names.optString(pkg).ifEmpty { labels[pkg] ?: pkg }
    private fun installed(pkg: String) = pkg in labels

    /** The app list as shown: not hidden, matching the search, A–Z by the name you see. */
    private fun visibleApps(): List<App> {
        val q = search.text.toString().trim()
        val hidden = prefs.list("hidden").toSet()
        return apps.asSequence().filter { it.pkg !in hidden }.map { App(it.pkg, label(it.pkg)) }
            .filter { it.name.contains(q, ignoreCase = true) }
            .sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER) { it.name })
            .toList()
    }

    private fun launch(pkg: String) {
        val pause = prefs["pause"].toInt()
        val hit = Limits.check(rules, pkg, usage, System.currentTimeMillis())
        when {
            hit != null && hit.blocked -> showBlocked(pkg, hit.rule)
            hit != null -> {
                val used = ScreenTime.format(hit.used)
                val limit = ScreenTime.format(hit.rule.mins * 60_000L)
                val message = if (hit.rule.name.isEmpty()) "You've spent $used on ${label(pkg)} today. Your daily limit is $limit."
                else "You've spent $used on your \"${hit.rule.name}\" apps today. Their daily limit is $limit."
                startPause(pkg, "limit reached.", message, seconds = maxOf(pause, 10))
            }
            pkg in prefs.list("distract") && pause > 0 -> startPause(
                pkg, "take a breath.",
                "You marked ${label(pkg)} as distracting. Do you really need it right now?",
                seconds = pause,
            )
            else -> open(pkg)
        }
    }

    private fun open(pkg: String) {
        packageManager.getLaunchIntentForPackage(pkg)?.let { start(it) }
    }

    /** Opening another app must never crash the launcher: it can be missing, or refuse us. */
    private fun start(intent: Intent) {
        try {
            startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        } catch (e: ActivityNotFoundException) {
            Toast.makeText(this, "Can't open that", Toast.LENGTH_SHORT).show()
        } catch (e: SecurityException) {
            Toast.makeText(this, "That app didn't let Minimal open it", Toast.LENGTH_SHORT).show()
        }
    }

    private fun appSettings(pkg: String) = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", pkg, null))

    // ---------- navigation ----------

    // the app list waits off to the right; home slides partly left as it comes in
    private fun offscreen(name: String) = when (name) {
        "drawer" -> root.width.toFloat()
        "home" -> -0.3f * root.width
        else -> 0f
    }

    private fun show(target: String, animate: Boolean = true) {
        if (target != screen && target !in EAGER) rebuild(target) // settings, usage, mono: built fresh when opened
        if (screen == "home" || screen == "drawer") prev = screen
        if (target != "pause") countdown?.cancel()
        if (target != "drawer") {
            getSystemService(InputMethodManager::class.java).hideSoftInputFromWindow(root.windowToken, 0)
            if (search.text.isNotEmpty()) search.setText("")
        }
        screen = target
        screens.forEach { (name, v) ->
            val on = name == target
            val off = offscreen(name)
            if (!animate) {
                v.translationX = if (on) 0f else off
                v.alpha = if (on) 1f else 0f
                v.visibility = if (on) View.VISIBLE else View.GONE
                return@forEach
            }
            if (on && v.visibility != View.VISIBLE) {
                v.translationX = off
                v.alpha = 0f
                v.visibility = View.VISIBLE
            }
            if (on || v.visibility == View.VISIBLE) {
                v.animate().translationX(if (on) 0f else off).alpha(if (on) 1f else 0f)
                    .setDuration(260).setInterpolator(DecelerateInterpolator())
                    .withEndAction { if (!on) v.visibility = View.GONE }
                    .start()
            }
        }
    }

    private fun back() {
        if (editing) return stopEditing()
        when (screen) {
            "settings", "pause", "usage" -> show(prev)
            "drawer" -> show("home")
            "mono" -> show("settings")
        }
    }

    private val swipe by lazy {
        GestureDetector(this, object : GestureDetector.SimpleOnGestureListener() {
            override fun onFling(e1: MotionEvent?, e2: MotionEvent, vx: Float, vy: Float): Boolean {
                val dx = e2.x - (e1 ?: return false).x
                val dy = e2.y - e1.y
                if (abs(dx) < dp(60) || abs(dx) < abs(dy)) return false // mostly-vertical drags are scrolls
                when {
                    dx < 0 && screen == "home" -> show("drawer")
                    dx > 0 && screen == "drawer" -> show("home")
                    else -> return false
                }
                return true
            }
        })
    }

    override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
        // a swipe that ends on an app name must not also open that app
        if (swipe.onTouchEvent(ev) && ev.actionMasked == MotionEvent.ACTION_UP) ev.action = MotionEvent.ACTION_CANCEL
        return super.dispatchTouchEvent(ev)
    }

    // ---------- building the screens ----------

    private fun render() {
        bg = if (dark) 0xFF000000.toInt() else 0xFFF2F1EC.toInt()
        fg = if (dark) 0xFFF0F0F0.toInt() else 0xFF141414.toInt()
        dim = if (dark) 0xFF7D7D7D.toInt() else 0xFF7A7A7A.toInt()
        rule = if (dark) 0xFF1F1F1F.toInt() else 0xFFDEDBD3.toInt()
        fs = mapOf("s" to 18f, "m" to 22f, "l" to 27f).getValue(prefs["size"])
        names = prefs.json("names")
        usageAllowed = ScreenTime.allowed(this) // a quick check; the usage itself comes from refresh()
        rules = prefs.rules()
        val lightBars = WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS or WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS
        window.insetsController?.setSystemBarsAppearance(if (dark) 0 else lightBars, lightBars)

        val scrollY = if (::settingsScroll.isInitialized) settingsScroll.scrollY else 0
        root.removeAllViews()
        screens.clear()
        root.setBackgroundColor(bg)
        (EAGER + screen).distinct().forEach(::rebuild)
        if (screen == "settings") settingsScroll.post { settingsScroll.scrollTo(0, scrollY) } // keep your place after changing a setting
    }

    /** Builds one screen (again), in place, keeping whether it's showing. */
    private fun rebuild(name: String) {
        val old = screens[name]
        val v = when (name) {
            "home" -> buildHome()
            "drawer" -> buildDrawer()
            "settings" -> buildSettings()
            "pause" -> buildPause()
            "usage" -> buildUsage()
            else -> buildMono()
        }
        val index = old?.let { root.indexOfChild(it) } ?: -1
        old?.let(root::removeView)
        screens[name] = v
        root.addView(v, index, FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))
        val on = name == screen
        v.visibility = if (on) View.VISIBLE else View.GONE
        v.alpha = if (on) 1f else 0f
        v.translationX = if (on) 0f else offscreen(name)
    }

    /** Clock, date, battery and screen time. */
    private fun homeHeader() = column().apply {
        setPadding(0, 0, 0, 0)
        val clockFormat = if (prefs["clock"] == "24") "HH:mm" else "h:mm"
        add(TextClock(context).apply {
            format12Hour = clockFormat
            format24Hour = clockFormat
            gravity = align
            setOnClickListener { start(Intent(AlarmClock.ACTION_SHOW_ALARMS)) }
        }.styled(fs * 3, weight = 200))
        if (prefs["date"] == "show") add(TextClock(context).apply {
            format12Hour = "EEEE, d MMMM"
            format24Hour = format12Hour
            gravity = align
            setPadding(0, dp(8), 0, 0)
        }.styled(fs * 0.7f, dim))
        val batteryShown = if (prefs["battery"] == "show") View.VISIBLE else View.GONE
        battery = add(tv("$batteryLevel%").styled(fs * 0.7f, dim).apply {
            gravity = align
            setPadding(0, dp(6), 0, 0)
            visibility = batteryShown
        })
        batteryFill = View(context).apply {
            pivotX = 0f
            scaleX = 0f
        }
        addView(FrameLayout(context).apply {
            background = GradientDrawable().apply { setColor(rule); cornerRadius = dp(2).toFloat() }
            clipToOutline = true
            addView(batteryFill, FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))
            visibility = batteryShown
        }, LinearLayout.LayoutParams(dp(56), dp(3)).apply {
            gravity = align
            topMargin = dp(8)
        })
        showBattery()
        screenTimeView = if (prefs["screentime"] == "show") add(tv(screenTimeText()).styled(fs * 0.7f, dim).apply {
            gravity = align
            setPadding(0, dp(6), 0, 0)
            setOnClickListener { if (usageAllowed) show("usage") else askUsageAccess() }
        }) else null
        // each line hugs its text, so the header re-measures when the clock or date changes and the cat gets the rest
        for (i in 0 until childCount) getChildAt(i).layoutParams.let { if (it.width == MATCH_PARENT) it.width = WRAP_CONTENT }
        gravity = align
    }

    private fun buildHome() = column().apply {
        val head = homeHeader()
        // the pet walks in the empty space on the side away from the text; centered text leaves no room
        if (prefs["cat"] != "hide" && align != Gravity.CENTER_HORIZONTAL) add(LinearLayout(context).apply {
            val pet = if (prefs["cat"] == "rocky") RockyView(context, fg, bg) else CatView(context, fg, bg)
            if (align == Gravity.END) addView(pet, LinearLayout.LayoutParams(0, MATCH_PARENT, 1f))
            addView(head)
            if (align != Gravity.END) addView(pet, LinearLayout.LayoutParams(0, MATCH_PARENT, 1f))
        }) else add(head)

        add(View(context), weight = 1f)
        val hidden = prefs.list("hidden")
        val favs = prefs.list("favs").filter { installed(it) && it !in hidden }
        when {
            editing -> {
                add(favEditor(favs))
                add(tv("drag to reorder · tap for options · done").styled(12f, dim).apply {
                    gravity = align
                    setPadding(0, dp(14), 0, 0)
                    setOnClickListener { stopEditing() }
                })
            }
            favs.isEmpty() -> add(tv("long-press an app in the list to add it here").styled(14f, dim).apply { gravity = align })
            else -> favs.forEach { pkg ->
                add(appRow(pkg, fs).apply {
                    setOnLongClickListener { editing = true; rebuild("home"); true }
                })
            }
        }
        add(View(context), weight = 1f)

        add(LinearLayout(context).apply {
            addView(tv("phone").styled(fs * 0.65f, dim).apply { setOnClickListener { start(Intent(Intent.ACTION_DIAL)) } })
            addView(View(context), LinearLayout.LayoutParams(0, 0, 1f))
            addView(tv("camera").styled(fs * 0.65f, dim).apply {
                setOnClickListener { start(Intent(MediaStore.INTENT_ACTION_STILL_IMAGE_CAMERA)) }
            })
        })
        add(tv("swipe left for all apps").styled(11f, dim).apply {
            gravity = Gravity.CENTER
            letterSpacing = 0.08f
            setPadding(0, dp(14), 0, 0)
            setOnClickListener { show("drawer") }
        })
        setOnLongClickListener { show("settings"); true }
    }

    /** Home apps while editing: drag a row to move it, ✕ removes it, a tap opens its menu. */
    private fun favEditor(favs: List<String>) = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        // the other rows slide out of the way as you drag
        layoutTransition = LayoutTransition().apply {
            setAnimator(LayoutTransition.APPEARING, null)
            setAnimator(LayoutTransition.DISAPPEARING, null)
            setDuration(160)
        }
        favs.forEach { pkg ->
            addView(LinearLayout(context).apply {
                tag = pkg
                gravity = Gravity.CENTER_VERTICAL
                setPadding(0, dp(9), 0, dp(9))
                addView(tv("≡").styled(fs * 0.8f, dim).apply { setPadding(0, 0, dp(16), 0) })
                addView(tv(label(pkg)).styled(fs), LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
                addView(tv("✕").styled(fs * 0.75f, dim).apply {
                    setPadding(dp(16), dp(4), 0, dp(4))
                    setOnClickListener { prefs.setList("favs", prefs.list("favs") - pkg); rebuild("home") }
                })
            })
        }
        // the whole list handles the drag, so moving a row around doesn't cut the gesture off
        var dragged: View? = null
        var downY = 0f
        var moved = false
        setOnTouchListener { _, e ->
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downY = e.y
                    moved = false
                    dragged = (0 until childCount).map { getChildAt(it) }.firstOrNull { e.y >= it.top && e.y < it.bottom }
                    dragged?.alpha = 0.5f
                    parent.requestDisallowInterceptTouchEvent(true)
                }
                MotionEvent.ACTION_MOVE -> dragged?.let { row ->
                    if (abs(e.y - downY) > dp(8)) moved = true
                    val to = (e.y / row.height).toInt().coerceIn(0, childCount - 1)
                    if (to != indexOfChild(row)) {
                        removeView(row)
                        addView(row, to)
                    }
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> dragged?.let { row ->
                    row.alpha = 1f
                    dragged = null
                    if (moved) prefs.setList("favs", (0 until childCount).map { getChildAt(it).tag as String })
                    else if (e.actionMasked == MotionEvent.ACTION_UP) menu(row.tag as String)
                }
            }
            true
        }
    }

    private fun stopEditing() {
        editing = false
        rebuild("home")
    }

    private fun buildDrawer() = column().apply {
        search = EditText(context).styled(fs * 0.8f).apply {
            hint = "search apps"
            setHintTextColor(dim)
            background = null
            setPadding(0, dp(8), 0, dp(8))
            isSingleLine = true
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
            imeOptions = EditorInfo.IME_ACTION_GO
            addTextChangedListener(object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
                override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
                override fun afterTextChanged(s: Editable?) = renderList()
            })
            setOnEditorActionListener { _, _, _ -> visibleApps().firstOrNull()?.let { launch(it.pkg) }; true }
        }
        add(LinearLayout(context).apply {
            gravity = Gravity.CENTER_VERTICAL
            addView(search, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
            addView(ImageView(context).apply {
                setImageResource(R.drawable.ic_gear)
                setColorFilter(dim)
                setPadding(dp(8), dp(8), dp(8), dp(8))
                contentDescription = "Launcher settings"
                setOnClickListener { show("settings") }
            }, LinearLayout.LayoutParams(dp(40), dp(40)))
        })
        add(View(context).apply { setBackgroundColor(rule) }, height = dp(1))
        list = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, dp(8), 0, 0)
        }
        drawerScroll = ScrollView(context).apply {
            isVerticalScrollBarEnabled = false
            addView(list)
        }
        // A–Z bar on the right edge: drag along it to jump through the list
        letters = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            clipChildren = false // the letters bulge out past the bar
            LETTERS.forEach { c ->
                addView(tv(c.toString()).styled(11f, dim).apply { gravity = Gravity.CENTER }, LinearLayout.LayoutParams(MATCH_PARENT, 0, 1f))
            }
            setOnTouchListener { v, e ->
                val pos = e.y / v.height * LETTERS.length // which letter the finger is on, as a fraction
                when (e.actionMasked) {
                    MotionEvent.ACTION_DOWN, MotionEvent.ACTION_MOVE -> {
                        bulge(pos)
                        jumpTo(pos.toInt().coerceIn(0, LETTERS.length - 1))
                    }
                    MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> endJump()
                }
                true
            }
        }
        bubble = tv("").styled(fs * 2f, weight = 300).apply {
            gravity = Gravity.CENTER
            visibility = View.GONE
            background = GradientDrawable().apply { setColor(rule); cornerRadius = dp(18).toFloat() }
        }
        add(FrameLayout(context).apply {
            addView(LinearLayout(context).apply {
                clipChildren = false
                addView(drawerScroll, LinearLayout.LayoutParams(0, MATCH_PARENT, 1f))
                addView(letters, LinearLayout.LayoutParams(dp(24), MATCH_PARENT))
            }, FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))
            addView(bubble, FrameLayout.LayoutParams(dp(76), dp(76), Gravity.CENTER))
        }, weight = 1f)
        renderList()
    }

    /** Letters near the finger grow and lean out to the left, like a wave following it. */
    private fun bulge(pos: Float) {
        for (i in 0 until letters.childCount) {
            val near = (1 - abs(i + 0.5f - pos) / 3f).coerceAtLeast(0f) // 1 under the finger, 0 from three letters away
            letters.getChildAt(i).apply {
                scaleX = 1 + near
                scaleY = 1 + near
                translationX = -dp(26) * near
            }
        }
    }

    private fun jumpTo(index: Int) {
        val letter = LETTERS[index]
        if (letter == lastLetter) return
        lastLetter = letter
        val rows = (0 until list.childCount).map(list::getChildAt)
        // rows are tagged with their first letter ('#' for numbers and symbols) in renderList
        (rows.firstOrNull { (it.tag as? Char ?: '#') >= letter } ?: rows.lastOrNull())?.let { drawerScroll.scrollTo(0, it.top) }
        bubble.text = letter.toString()
        bubble.visibility = View.VISIBLE
        for (i in 0 until letters.childCount) (letters.getChildAt(i) as TextView).setTextColor(if (i == index) fg else dim)
        letters.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
    }

    private fun endJump() {
        lastLetter = ' '
        bubble.visibility = View.GONE
        for (i in 0 until letters.childCount) {
            (letters.getChildAt(i) as TextView).apply {
                setTextColor(dim)
                animate().scaleX(1f).scaleY(1f).translationX(0f).setDuration(200).start()
            }
        }
    }

    private fun renderList() {
        list.removeAllViews()
        val distract = prefs.list("distract").toSet()
        val shown = visibleApps()
        if (shown.isEmpty()) list.addView(tv("no apps found").styled(fs * 0.85f, dim).apply { gravity = align })
        letters.visibility = if (search.text.isEmpty()) View.VISIBLE else View.GONE
        shown.forEach { app ->
            list.addView(appRow(app.pkg, fs * 0.85f).apply {
                setPadding(0, dp(8), 0, dp(8))
                tag = app.name.firstOrNull()?.uppercaseChar()?.takeIf { it in 'A'..'Z' } ?: '#'
                if (app.pkg in distract) text = SpannableString(app.name + "  •").apply {
                    setSpan(ForegroundColorSpan(dim), length - 1, length, 0)
                }
            })
        }
    }

    private fun buildSettings() = column().apply {
        add(tv("launcher settings").styled(fs * 1.2f, weight = 300).apply { setPadding(0, 0, 0, dp(14)) })
        val body = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
        settingsScroll = add(ScrollView(context).apply {
            isVerticalScrollBarEnabled = false
            addView(body)
        }, weight = 1f)

        OPTIONS.forEach { opt ->
            body.addView(LinearLayout(context).apply {
                gravity = Gravity.CENTER_VERTICAL
                setPadding(0, dp(10), 0, dp(10))
                addView(tv(opt.label).styled(15f), LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
                opt.choices.forEach { (value, text) ->
                    val on = prefs[opt.key] == value
                    addView(tv(text).styled(14f, if (on) bg else dim).apply {
                        setPadding(dp(9), dp(4), dp(9), dp(4))
                        if (on) background = GradientDrawable().apply { setColor(fg); cornerRadius = dp(6).toFloat() }
                        setOnClickListener { choose(opt.key, value) }
                    })
                }
            })
            body.addView(View(context).apply { setBackgroundColor(rule) }, LinearLayout.LayoutParams(MATCH_PARENT, dp(1)))
        }
        body.addView(limitsSection())
        body.addView(appSection("distracting apps", "distract", "none, long-press an app to add"))
        if (prefs["mono"] == "on") body.addView(appSection("monochrome apps", "mono", "none, long-press an app to add"))
        body.addView(hiddenSection())
        body.addView(link("set as default home app") { start(Intent(Settings.ACTION_HOME_SETTINGS)) })
        body.addView(link("reset everything") {
            AlertDialog.Builder(this@MainActivity, dialogTheme)
                .setMessage("Reset all launcher settings?")
                .setPositiveButton("Reset") { _, _ -> prefs.reset(); Grayscale.set(this@MainActivity, false); render() }
                .setNegativeButton("Cancel", null)
                .show()
        })
        if (timing.isNotEmpty()) body.addView(tv("last refresh: $timing").styled(11f, dim).apply { setPadding(0, dp(18), 0, 0) })
    }

    private fun choose(key: String, value: String) {
        when {
            key == "mono" && value == "on" && prefs["mono"] != "on" -> return show("mono") // set it up first
            key == "screentime" && value == "show" && !usageAllowed -> askUsageAccess()
        }
        prefs[key] = value
        if (key == "mono" && value == "off") Grayscale.set(this, false)
        render()
    }

    /** A titled list of apps; tapping one removes it from the list. */
    private fun appSection(title: String, key: String, empty: String) = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        addView(tv(title.uppercase()).styled(12f, dim).apply {
            letterSpacing = 0.08f
            setPadding(0, dp(18), 0, dp(4))
        })
        val items = prefs.list(key).filter { installed(it) }
        if (items.isEmpty()) addView(tv(empty).styled(13f, dim))
        items.forEach { pkg ->
            addView(tv("${label(pkg)}  ✕").styled(14f).apply {
                setPadding(0, dp(6), 0, dp(6))
                setOnClickListener { prefs.toggle(key, pkg); render() }
            })
        }
    }

    /** Collapsed by default. Tap an app to open it; long-press for the menu, where you can unhide it. */
    private fun hiddenSection() = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        val items = prefs.list("hidden").filter { installed(it) }.sortedBy { label(it).lowercase() }
        addView(tv("HIDDEN APPS (${items.size})  ${if (hiddenOpen) "▾" else "▸"}").styled(12f, dim).apply {
            letterSpacing = 0.08f
            setPadding(0, dp(18), 0, dp(4))
            setOnClickListener { hiddenOpen = !hiddenOpen; render() }
        })
        if (!hiddenOpen) return@apply
        if (items.isEmpty()) addView(tv("none").styled(13f, dim))
        items.forEach { pkg ->
            addView(appRow(pkg, fs * 0.75f).apply {
                gravity = Gravity.START
                setPadding(0, dp(6), 0, dp(6))
            })
        }
        if (items.isNotEmpty()) addView(tv("long-press an app to unhide it").styled(12f, dim).apply { setPadding(0, dp(4), 0, 0) })
    }

    private fun buildPause() = column().apply {
        gravity = Gravity.CENTER
        pauseTitle = add(tv("").styled(fs * 1.4f, weight = 300).apply { gravity = Gravity.CENTER })
        count = add(tv("").styled(fs * 3.4f, weight = 200).apply { gravity = Gravity.CENTER })
        pauseText = add(tv("").styled(14f, dim).apply {
            gravity = Gravity.CENTER
            setPadding(dp(24), dp(8), dp(24), dp(8))
        })
        val centered = LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT).apply {
            gravity = Gravity.CENTER_HORIZONTAL
            topMargin = dp(12)
        }
        addView(pill("never mind") { show(prev) }, centered)
        openAnyway = pill("open anyway") { pausedPkg?.let { open(it) } }
        addView(openAnyway, LinearLayout.LayoutParams(centered))
    }

    private fun startPause(pkg: String, title: String, message: String, seconds: Int) {
        pausedPkg = pkg
        show("pause")
        pauseTitle.text = title
        pauseText.text = message
        openAnyway.visibility = View.VISIBLE
        openAnyway.isEnabled = false
        openAnyway.alpha = 0.3f
        countdown = object : CountDownTimer(seconds * 1000L, 1000) {
            override fun onTick(msLeft: Long) {
                count.text = "${(msLeft + 999) / 1000}"
            }

            override fun onFinish() {
                count.text = "0"
                openAnyway.isEnabled = true
                openAnyway.alpha = 1f
            }
        }.start()
    }

    /** A block has no "open anyway"; it ends on its own, or when you delete it in settings. */
    private fun showBlocked(pkg: String, rule: Rule) {
        show("pause")
        pauseTitle.text = "blocked."
        count.text = Limits.left(rule.until - System.currentTimeMillis())
        val who = if (rule.name.isEmpty()) "You blocked ${label(pkg)}" else "Your \"${rule.name}\" limit blocks ${label(pkg)}"
        pauseText.text = "$who until ${until(rule.until)}."
        openAnyway.visibility = View.GONE
    }

    // ---------- screen time ----------

    private fun buildUsage() = column().apply {
        add(tv("screen time today").styled(fs * 1.2f, weight = 300))
        add(tv(ScreenTime.format(usage.values.sum())).styled(fs * 2.4f, weight = 200).apply { setPadding(0, dp(6), 0, dp(14)) })
        val body = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
        add(ScrollView(context).apply {
            isVerticalScrollBarEnabled = false
            addView(body)
        }, weight = 1f)
        val used = usage.filterValues { it >= 60_000 }.entries.sortedByDescending { it.value }
        if (used.isEmpty()) body.addView(tv("nothing yet today").styled(14f, dim))
        used.forEach { (pkg, ms) ->
            val limit = (soloRule(pkg)?.mins ?: 0) * 60_000L
            body.addView(LinearLayout(context).apply {
                gravity = Gravity.CENTER_VERTICAL
                setPadding(0, dp(9), 0, dp(9))
                addView(tv(label(pkg)).styled(fs * 0.75f), LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
                val time = ScreenTime.format(ms) + if (limit > 0) " / ${ScreenTime.format(limit)}" else ""
                addView(tv(time).styled(14f, if (limit in 1..ms) fg else dim))
                setOnClickListener { soloLimit(pkg) }
            })
        }
        add(tv("tap an app to set a limit").styled(12f, dim).apply { setPadding(0, dp(12), 0, 0) })
    }

    // ---------- app limits ----------

    /** The limit set on this one app from its long-press menu (groups are separate). */
    private fun soloRule(pkg: String) = rules.firstOrNull { it.name.isEmpty() && it.apps == listOf(pkg) }

    private fun describe(r: Rule) = if (r.until > 0) "blocked until ${until(r.until)}" else "${ScreenTime.format(r.mins * 60_000L)} a day"

    private fun until(ms: Long): String = Instant.ofEpochMilli(ms).atZone(ZoneId.systemDefault())
        .format(DateTimeFormatter.ofPattern(if (prefs["clock"] == "24") "EEE d MMM, HH:mm" else "EEE d MMM, h:mm a"))

    private fun saveRules(list: List<Rule>) {
        prefs.setRules(list)
        render()
        if (list.any { it.mins > 0 } && !usageAllowed) askUsageAccess() // daily limits need screen time
    }

    private fun soloLimit(pkg: String) {
        val solo = soloRule(pkg)
        chooseLimit("Limit for ${label(pkg)}") { picked ->
            saveRules(rules - listOfNotNull(solo) + listOfNotNull(picked?.copy(name = "", apps = listOf(pkg))))
        }
    }

    /** Daily allowances and blocks, presets or custom. [pick] gets null for "no limit". */
    private fun chooseLimit(title: String, pick: (Rule?) -> Unit) {
        val now = System.currentTimeMillis()
        val items = listOf<Pair<String, () -> Unit>>(
            "no limit" to { pick(null) },
            "15m a day" to { pick(Rule(mins = 15)) },
            "30m a day" to { pick(Rule(mins = 30)) },
            "1h a day" to { pick(Rule(mins = 60)) },
            "2h a day" to { pick(Rule(mins = 120)) },
            "custom daily limit…" to {
                askAmount("Daily limit", listOf("minutes" to 1, "hours" to 60)) { m -> pick(Rule(mins = m.coerceAtMost(24 * 60L).toInt())) }
            },
            "block for 1 day" to { pick(Rule(until = now + DAY)) },
            "block for 1 week" to { pick(Rule(until = now + 7 * DAY)) },
            "block for a custom time…" to {
                askAmount("Block for", listOf("hours" to 60, "days" to 24 * 60, "weeks" to 7 * 24 * 60)) { m ->
                    pick(Rule(until = System.currentTimeMillis() + m * 60_000L))
                }
            },
        )
        AlertDialog.Builder(this, dialogTheme).setTitle(title)
            .setItems(items.map { it.first }.toTypedArray()) { _, i -> items[i].second() }
            .show()
    }

    /** A number plus a unit, e.g. "15" + "hours". Gives back minutes. */
    private fun askAmount(title: String, units: List<Pair<String, Int>>, done: (Long) -> Unit) {
        val input = EditText(this).apply {
            inputType = InputType.TYPE_CLASS_NUMBER
            filters = arrayOf(InputFilter.LengthFilter(4))
            hint = "how many?"
        }
        val unitPicker = RadioGroup(this).apply {
            orientation = RadioGroup.HORIZONTAL
            units.forEach { (name, minutes) ->
                addView(RadioButton(context).apply {
                    id = View.generateViewId()
                    text = name
                    tag = minutes
                })
            }
            check(getChildAt(units.size - 1).id)
        }
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(22), dp(8), dp(22), 0)
            addView(input)
            addView(unitPicker)
        }
        AlertDialog.Builder(this, dialogTheme).setTitle(title).setView(box)
            .setPositiveButton("Set") { _, _ ->
                val n = input.text.toString().toLongOrNull()?.takeIf { it > 0 } ?: return@setPositiveButton
                done(n * (unitPicker.findViewById<RadioButton>(unitPicker.checkedRadioButtonId).tag as Int))
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun limitsSection() = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        addView(tv("APP LIMITS").styled(12f, dim).apply {
            letterSpacing = 0.08f
            setPadding(0, dp(18), 0, dp(4))
        })
        if (rules.isEmpty()) addView(tv("none yet").styled(13f, dim))
        rules.forEachIndexed { i, r ->
            val name = r.name.ifEmpty { r.apps.firstOrNull()?.let(::label) ?: "?" }
            addView(LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(0, dp(6), 0, dp(6))
                addView(tv(name).styled(15f))
                val used = if (r.mins > 0 && usageAllowed) " · ${ScreenTime.format(r.apps.sumOf { usage[it] ?: 0L })} used today" else ""
                addView(tv(describe(r) + used).styled(12f, dim))
                if (r.name.isNotEmpty()) addView(tv(r.apps.filter { installed(it) }.joinToString(", ") { label(it) }).styled(12f, dim))
                setOnClickListener { editRule(i) }
            })
        }
        addView(link("+ new app limit group") { newGroup() }.apply { setPadding(0, dp(8), 0, 0) })
    }

    private fun editRule(i: Int) {
        val r = rules[i]
        fun replace(new: Rule?) = saveRules(rules.toMutableList().apply { if (new == null) removeAt(i) else set(i, new) })
        val items = mutableListOf<Pair<String, () -> Unit>>(
            "change limit" to { chooseLimit("Limit") { picked -> replace(picked?.copy(name = r.name, apps = r.apps)) } },
        )
        if (r.name.isNotEmpty()) {
            items += "change apps" to { pickApps(r.apps) { apps -> replace(r.copy(apps = apps)) } }
            items += "rename" to { askText("Group name", r.name) { name -> replace(r.copy(name = name)) } }
        }
        items += "delete" to { replace(null) }
        AlertDialog.Builder(this, dialogTheme).setTitle(r.name.ifEmpty { r.apps.firstOrNull()?.let(::label) ?: "" })
            .setItems(items.map { it.first }.toTypedArray()) { _, k -> items[k].second() }
            .show()
    }

    private fun newGroup() = askText("Name this group, e.g. Social", "") { name ->
        pickApps(emptyList()) { apps ->
            chooseLimit("Limit for $name") { picked ->
                if (picked != null && apps.isNotEmpty()) saveRules(rules + picked.copy(name = name, apps = apps))
            }
        }
    }

    private fun pickApps(selected: List<String>, done: (List<String>) -> Unit) {
        val all = apps.sortedBy { label(it.pkg).lowercase() }
        val checked = all.map { it.pkg in selected }.toBooleanArray()
        AlertDialog.Builder(this, dialogTheme).setTitle("Pick apps")
            .setMultiChoiceItems(all.map { label(it.pkg) }.toTypedArray(), checked) { _, k, on -> checked[k] = on }
            .setPositiveButton("Next") { _, _ -> done(all.filterIndexed { k, _ -> checked[k] }.map { it.pkg }) }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun askText(title: String, initial: String, done: (String) -> Unit) {
        val input = EditText(this).apply {
            setText(initial)
            setSelection(text.length)
            isSingleLine = true
        }
        val box = FrameLayout(this).apply {
            setPadding(dp(22), dp(8), dp(22), 0)
            addView(input)
        }
        AlertDialog.Builder(this, dialogTheme).setTitle(title).setView(box)
            .setPositiveButton("OK") { _, _ -> input.text.toString().trim().takeIf { it.isNotEmpty() }?.let { done(it.take(30)) } }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun askUsageAccess() {
        AlertDialog.Builder(this, dialogTheme).setTitle("Allow usage access")
            .setMessage(
                "Screen time and daily limits need usage access. On the next screen, find Minimal and turn it on.\n\n" +
                    "If it says \"Controlled by Restricted Setting\": open App info, tap ⋮ in the top-right, " +
                    "choose \"Allow restricted settings\", then try again.",
            )
            .setPositiveButton("Open") { _, _ -> start(Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS)) }
            .setNeutralButton("App info") { _, _ -> start(appSettings(packageName)) }
            .setNegativeButton("Cancel", null)
            .show()
    }

    // ---------- monochrome setup ----------

    private fun buildMono() = column().apply {
        val granted = Grayscale.allowed(context)
        val serviceOn = FocusService.isOn(context)
        add(LinearLayout(context).apply {
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, 0, 0, dp(12))
            addView(tv("←").styled(22f).apply {
                setPadding(0, 0, dp(16), 0)
                setOnClickListener { show("settings") }
            })
            addView(tv("monochrome mode").styled(17f))
        })
        add(View(context).apply { setBackgroundColor(fg) }, height = dp(1))
        val body = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
        add(ScrollView(context).apply {
            isVerticalScrollBarEnabled = false
            addView(body)
        }, weight = 1f)

        body.addView(ImageView(context).apply {
            setImageResource(R.drawable.ic_mono)
            setPadding(0, dp(24), 0, dp(24))
        }, LinearLayout.LayoutParams(MATCH_PARENT, dp(144)))
        body.addView(tv(
            "Color makes apps more engaging and keeps you scrolling. Showing your chosen apps in grayscale " +
                "makes them easier to put down. Once it's on, you pick which apps go gray.",
        ).styled(14f).apply {
            gravity = Gravity.CENTER
            setLineSpacing(0f, 1.3f)
        })
        body.addView(step("1. Allow grayscale control", granted).apply {
            if (!granted) {
                addView(tv("One time only. Connect your phone to your PC, then run:").styled(13f, dim))
                addView(tv("adb shell pm grant $packageName android.permission.WRITE_SECURE_SETTINGS").styled(12f).apply {
                    typeface = Typeface.MONOSPACE
                    setTextIsSelectable(true)
                    setPadding(0, dp(6), 0, 0)
                })
            }
        })
        body.addView(step("2. Turn on Minimal in Accessibility", serviceOn).apply {
            if (!serviceOn) {
                addView(tv("If Minimal is grayed out there: open app info → ⋮ → Allow restricted settings, then try again.").styled(13f, dim))
                addView(link("open accessibility settings") { start(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) }.apply {
                    setPadding(0, dp(8), 0, 0)
                })
                addView(link("open app info") { start(appSettings(packageName)) }.apply { setPadding(0, dp(8), 0, 0) })
            }
        })

        val ready = granted && serviceOn
        add(tv(if (ready) "Turn on" else "Finish the steps above").styled(15f, bg).apply {
            gravity = Gravity.CENTER
            setPadding(0, dp(13), 0, dp(13))
            background = GradientDrawable().apply { setColor(fg); cornerRadius = dp(99).toFloat() }
            alpha = if (ready) 1f else 0.35f
            if (ready) setOnClickListener {
                prefs["mono"] = "on"
                render()
                show("settings")
            }
        }).apply { (layoutParams as LinearLayout.LayoutParams).topMargin = dp(12) }
    }

    private fun step(title: String, done: Boolean) = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(0, dp(20), 0, 0)
        addView(tv(if (done) "$title  ✓" else title).styled(15f).apply { setPadding(0, 0, 0, dp(4)) })
    }

    // ---------- long-press menu ----------

    private fun menu(pkg: String) {
        val items = mutableListOf<Pair<String, () -> Unit>>()
        items += (if (pkg in prefs.list("favs")) "remove from home" else "add to home") to { prefs.toggle("favs", pkg); render() }
        items += "rename" to { rename(pkg) }
        items += (if (pkg in prefs.list("distract")) "unmark as distracting" else "mark as distracting") to {
            prefs.toggle("distract", pkg)
            render()
        }
        items += (soloRule(pkg)?.let { "limit: ${describe(it)}" } ?: "set limit") to { soloLimit(pkg) }
        if (prefs["mono"] == "on") {
            items += (if (pkg in prefs.list("mono")) "show in color" else "show in monochrome") to { prefs.toggle("mono", pkg); render() }
        }
        if (pkg in prefs.list("hidden")) {
            items += "unhide" to { prefs.toggle("hidden", pkg); render() }
        } else {
            items += "hide from app list" to {
                prefs.toggle("hidden", pkg)
                prefs.setList("favs", prefs.list("favs") - pkg)
                render()
            }
        }
        items += "app info" to { start(appSettings(pkg)) }
        items += "uninstall" to { start(Intent(Intent.ACTION_DELETE, Uri.fromParts("package", pkg, null))) }

        val title = label(pkg) + (usage[pkg]?.let { " · ${ScreenTime.format(it)} today" } ?: "")
        AlertDialog.Builder(this, dialogTheme).setTitle(title)
            .setItems(items.map { it.first }.toTypedArray()) { _, i -> items[i].second() }
            .show()
    }

    private fun rename(pkg: String) {
        val input = EditText(this).apply {
            setText(label(pkg))
            setSelection(text.length)
            isSingleLine = true
        }
        val box = FrameLayout(this).apply {
            setPadding(dp(22), dp(8), dp(22), 0)
            addView(input)
        }
        AlertDialog.Builder(this, dialogTheme).setTitle("Rename").setView(box)
            .setPositiveButton("Save") { _, _ -> prefs.rename(pkg, input.text.toString()); render() }
            .setNeutralButton("Original name") { _, _ -> prefs.rename(pkg, ""); render() }
            .setNegativeButton("Cancel", null)
            .show()
    }

    // ---------- small view helpers ----------

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    private fun tv(text: CharSequence) = TextView(this).apply { this.text = text }

    private fun <T : TextView> T.styled(size: Float = fs, color: Int = fg, weight: Int = 400) = apply {
        textSize = size
        setTextColor(color)
        typeface = Typeface.create(Typeface.DEFAULT, weight, false)
    }

    private fun column() = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(28), dp(24), dp(28), dp(16))
    }

    private fun <V : View> LinearLayout.add(v: V, weight: Float = 0f, height: Int = WRAP_CONTENT): V {
        addView(v, LinearLayout.LayoutParams(MATCH_PARENT, if (weight > 0) 0 else height, weight))
        return v
    }

    private fun appRow(pkg: String, size: Float) = tv(label(pkg)).styled(size).apply {
        gravity = align
        setPadding(0, dp(9), 0, dp(9))
        setOnClickListener { launch(pkg) }
        setOnLongClickListener { menu(pkg); true }
    }

    private fun pill(text: String, action: () -> Unit) = tv(text).styled(15f).apply {
        background = GradientDrawable().apply {
            setStroke(dp(1), rule)
            cornerRadius = dp(99).toFloat()
        }
        setPadding(dp(22), dp(10), dp(22), dp(10))
        setOnClickListener { action() }
    }

    private fun link(text: String, action: () -> Unit) = tv(text).styled(14f, dim).apply {
        paintFlags = paintFlags or Paint.UNDERLINE_TEXT_FLAG
        setPadding(0, dp(18), 0, 0)
        setOnClickListener { action() }
    }
}
