package com.angel.minimal

import android.app.AppOpsManager
import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.Context
import android.os.Process
import java.time.LocalDate
import java.time.ZoneId

/** Today's on-screen time per app, from Android's usage log (needs "Usage access"). */
object ScreenTime {

    data class Ev(val pkg: String, val cls: String, val resumed: Boolean, val time: Long)

    fun allowed(c: Context) = c.getSystemService(AppOpsManager::class.java).unsafeCheckOpNoThrow(
        AppOpsManager.OPSTR_GET_USAGE_STATS, Process.myUid(), c.packageName,
    ) == AppOpsManager.MODE_ALLOWED

    /** Milliseconds each app has been on screen since midnight. */
    fun today(c: Context): Map<String, Long> {
        val start = LocalDate.now().atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli()
        val now = System.currentTimeMillis()
        val log = c.getSystemService(UsageStatsManager::class.java).queryEvents(start, now)
        val events = mutableListOf<Ev>()
        val e = UsageEvents.Event()
        while (log.getNextEvent(e)) {
            if (e.eventType == UsageEvents.Event.ACTIVITY_RESUMED || e.eventType == UsageEvents.Event.ACTIVITY_PAUSED) {
                events += Ev(e.packageName, e.className.orEmpty(), e.eventType == UsageEvents.Event.ACTIVITY_RESUMED, e.timeStamp)
            }
        }
        return totals(events, start, now)
    }

    /** Pairs each screen's "opened" with its "closed" and adds up the time per app. */
    fun totals(events: List<Ev>, start: Long, now: Long): Map<String, Long> {
        val openSince = HashMap<String, Long>()
        val seen = HashSet<String>()
        val total = HashMap<String, Long>()
        for (e in events) {
            val screen = e.pkg + "/" + e.cls
            if (e.resumed) {
                openSince.putIfAbsent(screen, e.time)
            } else {
                // a close with no open before it today means the app was already open at midnight
                val from = openSince.remove(screen) ?: if (screen !in seen) start else null
                if (from != null) total.merge(e.pkg, e.time - from, Long::plus)
            }
            seen += screen
        }
        openSince.forEach { (screen, from) -> total.merge(screen.substringBefore('/'), now - from, Long::plus) }
        return total
    }

    fun format(ms: Long): String {
        val m = ms / 60_000
        return when {
            m < 60 -> "${m}m"
            m % 60 == 0L -> "${m / 60}h"
            else -> "${m / 60}h ${m % 60}m"
        }
    }
}
