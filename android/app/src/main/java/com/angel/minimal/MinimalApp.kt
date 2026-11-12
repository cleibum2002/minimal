package com.angel.minimal

import android.app.Application
import java.io.File
import java.time.LocalDateTime

/** Saves the error of any crash to crash.txt, so the launcher can show it on the next start. */
class MinimalApp : Application() {
    override fun onCreate() {
        super.onCreate()
        val system = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, error ->
            try {
                val version = packageManager.getPackageInfo(packageName, 0).versionName
                crashFile(this).writeText("Minimal $version, ${LocalDateTime.now()}\n\n${error.stackTraceToString()}")
            } catch (_: Exception) {
                // never let the crash logger hide the real crash
            }
            system?.uncaughtException(thread, error)
        }
    }

    companion object {
        fun crashFile(app: Application) = File(app.filesDir, "crash.txt")
    }
}
