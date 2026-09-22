package com.nuanke.focus

import android.app.Application
import android.content.Context
import android.os.Process
import androidx.core.content.edit
import com.nuanke.focus.diagnostics.ServiceDiagnostics

/**
 * Keeps startup diagnostics on-device so a vendor-specific failure does not
 * turn into an unexplained crash loop. Nothing here is sent over the network.
 */
internal object StartupCrashGuard {
    private const val PREFS = "nuanke_startup_guard"
    private const val KEY_STARTING = "startup_in_progress"
    private const val KEY_REPORT = "startup_report"
    private const val MAX_REPORT_CHARS = 16_000

    @Volatile
    private var installed = false

    fun install(application: Application) {
        if (installed) return
        synchronized(this) {
            if (installed) return
            val previous = Thread.getDefaultUncaughtExceptionHandler()
            Thread.setDefaultUncaughtExceptionHandler { thread, error ->
                runCatching {
                    ServiceDiagnostics.error(application, "进程异常退出", error)
                    val prefs = application.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                    if (prefs.getBoolean(KEY_STARTING, false)) {
                        // A crash handler must persist before the process is terminated.
                        prefs.edit(commit = true) {
                            putBoolean(KEY_STARTING, false)
                            putString(KEY_REPORT, format(error))
                        }
                    }
                }
                if (previous != null) {
                    previous.uncaughtException(thread, error)
                } else {
                    Process.killProcess(Process.myPid())
                }
            }
            installed = true
        }
    }

    fun beginStartup(context: Context) {
        // Persist synchronously so an immediate startup crash can be recognized.
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit(commit = true) {
            putBoolean(KEY_STARTING, true)
        }
    }

    fun markStartupComplete(context: Context) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit {
            putBoolean(KEY_STARTING, false)
            remove(KEY_REPORT)
        }
    }

    fun recordStartupFailure(context: Context, error: Throwable) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit(commit = true) {
            putBoolean(KEY_STARTING, false)
            putString(KEY_REPORT, format(error))
        }
    }

    fun previousReport(context: Context): String? =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(KEY_REPORT, null)

    fun clear(context: Context) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit {
            clear()
        }
    }

    private fun format(error: Throwable): String {
        return buildString {
            appendLine("暖刻 ${BuildConfig.VERSION_NAME} 启动诊断")
            appendLine("Android ${android.os.Build.VERSION.RELEASE} / API ${android.os.Build.VERSION.SDK_INT}")
            appendLine("设备 ${android.os.Build.MANUFACTURER} ${android.os.Build.MODEL}")
            appendLine()
            append(ServiceDiagnostics.sanitizedStack(error))
        }.take(MAX_REPORT_CHARS)
    }
}
