/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.music.utils

import android.content.Context
import android.content.Intent
import android.os.Build
import com.metrolist.music.BuildConfig
import com.metrolist.music.ui.screens.CrashActivity
import timber.log.Timber
import java.io.PrintWriter
import java.io.StringWriter
import kotlin.system.exitProcess

class CrashHandler private constructor(
    private val applicationContext: Context
) : Thread.UncaughtExceptionHandler {

    private val defaultHandler: Thread.UncaughtExceptionHandler? =
        Thread.getDefaultUncaughtExceptionHandler()

    override fun uncaughtException(thread: Thread, throwable: Throwable) {
        try {
            // Swallow ForegroundServiceStartNotAllowedException from Media3's
            // background notification refresh path (issue #123). Android 12+
            // refuses startForegroundService() when the app is in the background
            // and no exemption applies. Crashing the process here is the wrong
            // response: it just means we couldn't promote the service to the
            // foreground this time. Report once as non-fatal and continue.
            if (isForegroundServiceStartNotAllowed(throwable)) {
                Timber.tag("CrashHandler").w(
                    throwable,
                    "Swallowed ForegroundServiceStartNotAllowedException",
                )
                CrashReporter.reportNonFatal(throwable)
                return
            }

            val crashLog = buildCrashLog(throwable)
            Timber.e(throwable, "App crashed")

            // Best-effort upload to the project's Issues tracker. Bounded by an internal
            // timeout so a slow network never delays the crash dialog.
            CrashReporter.reportFatal(throwable)

            // Launch crash activity
            val intent = Intent(applicationContext, CrashActivity::class.java).apply {
                putExtra(EXTRA_CRASH_LOG, crashLog)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
            }
            applicationContext.startActivity(intent)

            // Kill the current process
            android.os.Process.killProcess(android.os.Process.myPid())
            exitProcess(1)
        } catch (e: Exception) {
            // If we fail to handle the crash, fall back to default handler
            Timber.e(e, "Error handling crash")
            defaultHandler?.uncaughtException(thread, throwable)
        }
    }

    private fun isForegroundServiceStartNotAllowed(throwable: Throwable): Boolean {
        var current: Throwable? = throwable
        while (current != null) {
            if (current.javaClass.name == "android.app.ForegroundServiceStartNotAllowedException") {
                return true
            }
            current = current.cause
        }
        return false
    }

    private fun buildCrashLog(throwable: Throwable): String {
        val stackTrace = StringWriter().apply {
            throwable.printStackTrace(PrintWriter(this))
        }.toString()

        return buildString {
            appendLine("Meld Crash Report")
            appendLine("=".repeat(50))
            appendLine()
            appendLine("Manufacturer: ${Build.MANUFACTURER}")
            appendLine("Device: ${Build.MODEL}")
            appendLine("Android version: ${Build.VERSION.RELEASE} (SDK ${Build.VERSION.SDK_INT})")
            appendLine("App version: ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})")
            appendLine()
            appendLine("=".repeat(50))
            appendLine("Stacktrace:")
            appendLine("=".repeat(50))
            appendLine()
            append(stackTrace)
        }
    }

    companion object {
        const val EXTRA_CRASH_LOG = "crash_log"

        fun install(context: Context) {
            val handler = CrashHandler(context.applicationContext)
            Thread.setDefaultUncaughtExceptionHandler(handler)
            Timber.d("CrashHandler installed")
        }
    }
}
