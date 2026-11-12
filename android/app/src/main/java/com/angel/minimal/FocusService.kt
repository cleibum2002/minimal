package com.angel.minimal

import android.Manifest
import android.accessibilityservice.AccessibilityService
import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager
import android.provider.Settings
import android.view.accessibility.AccessibilityEvent
import android.widget.Toast

/**
 * Watches which app comes to the front and turns grayscale on for the apps you picked.
 * It only reads the app's name, never what's on screen.
 */
class FocusService : AccessibilityService() {

    override fun onAccessibilityEvent(event: AccessibilityEvent) {
        if (event.eventType != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) return
        val pkg = event.packageName?.toString() ?: return
        val cls = event.className?.toString() ?: return
        // only full app screens count; keyboards, popups and the notification shade leave grayscale as it is
        try {
            packageManager.getActivityInfo(ComponentName(pkg, cls), 0)
        } catch (e: PackageManager.NameNotFoundException) {
            return
        }
        val prefs = Prefs(this)
        // a blocked app opened some other way (a notification, recent apps) gets sent home
        if (prefs.rules().any { pkg in it.apps && it.until > System.currentTimeMillis() }) {
            performGlobalAction(GLOBAL_ACTION_HOME)
            Toast.makeText(this, "That app is blocked right now", Toast.LENGTH_SHORT).show()
            return
        }
        Grayscale.set(this, prefs["mono"] == "on" && pkg in prefs.list("mono"))
    }

    override fun onInterrupt() {}

    companion object {
        fun isOn(c: Context) = Settings.Secure.getString(c.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES)
            ?.contains("${c.packageName}/") == true
    }
}

/** Android's own grayscale filter. Changing it needs a permission granted once over ADB. */
object Grayscale {
    private const val ENABLED = "accessibility_display_daltonizer_enabled"
    private const val MODE = "accessibility_display_daltonizer"

    fun allowed(c: Context) =
        c.checkSelfPermission(Manifest.permission.WRITE_SECURE_SETTINGS) == PackageManager.PERMISSION_GRANTED

    fun set(c: Context, on: Boolean) {
        if (!allowed(c)) return
        val cr = c.contentResolver
        val want = if (on) 1 else 0
        if (Settings.Secure.getInt(cr, ENABLED, 0) == want) return
        if (on) Settings.Secure.putInt(cr, MODE, 0) // 0 = monochromacy, i.e. grayscale
        Settings.Secure.putInt(cr, ENABLED, want)
    }
}
