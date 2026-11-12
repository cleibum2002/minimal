package com.angel.minimal

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

class Option(val key: String, val label: String, val default: String, val choices: List<Pair<String, String>>)

val OPTIONS = listOf(
    Option("theme", "Theme", "dark", listOf("dark" to "Dark", "light" to "Light")),
    Option("size", "Text size", "m", listOf("s" to "S", "m" to "M", "l" to "L")),
    Option("align", "Alignment", "left", listOf("left" to "Left", "center" to "Center", "right" to "Right")),
    Option("clock", "Clock", "12", listOf("12" to "12h", "24" to "24h")),
    Option("date", "Date", "show", listOf("show" to "Show", "hide" to "Hide")),
    Option("battery", "Battery", "show", listOf("show" to "Show", "hide" to "Hide")),
    Option("screentime", "Screen time", "show", listOf("show" to "Show", "hide" to "Hide")),
    // key and "show" kept from when the cat was the only pet, so existing settings carry over
    Option("cat", "Pet", "show", listOf("show" to "Cat", "rocky" to "Rocky", "hide" to "Hide")),
    Option("pause", "Mindful pause", "5", listOf("0" to "Off", "5" to "5s", "10" to "10s")),
    Option("mono", "Monochrome", "off", listOf("off" to "Off", "on" to "On")),
)

// Facebook, Instagram, TikTok (both package names it ships under), YouTube
private val SOCIAL = listOf(
    "com.facebook.katana", "com.instagram.android", "com.zhiliaoapp.musically",
    "com.ss.android.ugc.trill", "com.google.android.youtube",
)
private val DEFAULT_LISTS = mapOf("distract" to SOCIAL, "mono" to SOCIAL)

/** Launcher settings. App lists hold package names, e.g. "com.instagram.android". */
class Prefs(context: Context) {
    private val p = context.getSharedPreferences("launcher", Context.MODE_PRIVATE)

    operator fun get(key: String): String = p.getString(key, null) ?: OPTIONS.first { it.key == key }.default
    operator fun set(key: String, value: String) = p.edit().putString(key, value).apply()

    fun list(key: String): List<String> =
        p.getString(key, null)?.split('\n')?.filter { it.isNotEmpty() } ?: DEFAULT_LISTS[key].orEmpty()

    fun setList(key: String, value: List<String>) = p.edit().putString(key, value.joinToString("\n")).apply()

    fun toggle(key: String, item: String) {
        val l = list(key)
        setList(key, if (item in l) l - item else l + item)
    }

    fun json(key: String) = JSONObject(p.getString(key, null) ?: "{}")
    private fun putJson(key: String, value: JSONObject) = p.edit().putString(key, value.toString()).apply()

    /** A blank name goes back to the app's real name. */
    fun rename(pkg: String, name: String) {
        val n = json("names")
        if (name.isBlank()) n.remove(pkg) else n.put(pkg, name.trim().take(30))
        putJson("names", n)
    }

    /** All app limits, minus blocks that have already ended. */
    fun rules(now: Long = System.currentTimeMillis()): List<Rule> {
        val raw = p.getString("rules", null)
        val all = if (raw == null) {
            // v0.3 kept per-app daily limits as {"package": minutes}
            json("limits").let { o -> o.keys().asSequence().map { Rule(apps = listOf(it), mins = o.getInt(it)) }.toList() }
        } else {
            val a = JSONArray(raw)
            (0 until a.length()).map { i ->
                val o = a.getJSONObject(i)
                val apps = o.getJSONArray("apps")
                Rule(o.optString("name"), (0 until apps.length()).map(apps::getString), o.optInt("mins"), o.optLong("until"))
            }
        }
        return all.filter { it.mins > 0 || it.until > now }
    }

    fun setRules(rules: List<Rule>) {
        val a = JSONArray(rules.map {
            JSONObject().put("name", it.name).put("apps", JSONArray(it.apps)).put("mins", it.mins).put("until", it.until)
        })
        p.edit().putString("rules", a.toString()).apply()
    }

    /** The last known app list as (package, name), so the launcher can draw instantly after a restart. */
    fun cachedApps(): List<Pair<String, String>> = p.getString("appCache", null)?.lines()
        ?.mapNotNull { line -> line.split('\t').takeIf { it.size == 2 }?.let { it[0] to it[1] } }.orEmpty()

    fun cacheApps(apps: List<Pair<String, String>>) = p.edit()
        .putString("appCache", apps.joinToString("\n") { (pkg, name) -> pkg + "\t" + name.replace('\t', ' ').replace('\n', ' ') })
        .apply()

    fun reset() = p.edit().clear().apply()
}
