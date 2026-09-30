package com.nuanke.focus.diagnostics

import android.app.ActivityManager
import android.content.ComponentName
import android.content.Context
import android.os.Build
import android.provider.Settings
import androidx.core.content.edit
import com.nuanke.focus.BuildConfig
import com.nuanke.focus.monitor.FocusGuardAccessibilityService
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

data class GuardHealth(val connected: Boolean = false, val ready: Boolean = false, val issue: String? = null)

/** Local operational evidence only: no event packages, labels, content, or exception messages. */
object ServiceDiagnostics {
    private val mutableHealth = MutableStateFlow(GuardHealth())
    val health = mutableHealth.asStateFlow()
    private val visibleActivities = java.util.Collections.newSetFromMap(java.util.IdentityHashMap<Any, Boolean>())
    private var legacyVisible = false
    val activityVisible: Boolean get() = synchronized(visibleActivities) { legacyVisible || visibleActivities.isNotEmpty() }

    fun activityVisible(visible: Boolean) { synchronized(visibleActivities) { legacyVisible = visible } }
    fun activityVisible(owner: Any, visible: Boolean) {
        synchronized(visibleActivities) {
            if (visible) visibleActivities.add(owner) else visibleActivities.remove(owner)
        }
    }

    fun connected(context: Context) {
        mutableHealth.value = GuardHealth(connected = true)
        note(context, "守护已连接，正在加载规则")
    }

    fun ready() { mutableHealth.value = GuardHealth(connected = true, ready = true) }

    fun disconnected(context: Context) {
        mutableHealth.value = GuardHealth()
        note(context, "守护已断开")
    }

    fun error(context: Context, operation: String, error: Throwable) {
        mutableHealth.value = mutableHealth.value.copy(issue = "$operation 暂时异常")
        note(context, "$operation: ${sanitizedStack(error)}")
    }

    fun sanitizedStack(error: Throwable): String = buildString {
        var cause: Throwable? = error
        repeat(3) {
            cause?.let { failure ->
                appendLine().append(failure.javaClass.name)
                failure.stackTrace.take(8).forEach { frame -> appendLine().append("  ").append(frame) }
                cause = failure.cause.takeUnless { it === failure }
            }
        }
    }

    private fun note(context: Context, text: String) {
        runCatching {
            val prefs = context.getSharedPreferences("nuanke_service_diagnostics", Context.MODE_PRIVATE)
            val entry = "${java.time.Instant.now()} $text"
            val previous = prefs.getString("events", "").orEmpty()
            prefs.edit(commit = true) { putString("events", (entry + "\n\n" + previous).take(8_000)) }
        }
    }

    fun enabled(context: Context): Boolean = runCatching {
        val expected = ComponentName(context, FocusGuardAccessibilityService::class.java)
        Settings.Secure.getString(context.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES)
            ?.split(':')?.any { ComponentName.unflattenFromString(it) == expected } == true
    }.getOrDefault(false)

    fun report(context: Context): String = buildString {
        appendLine("暖刻 ${BuildConfig.VERSION_NAME} 本机守护诊断")
        appendLine("Android API ${Build.VERSION.SDK_INT}")
        appendLine("系统开关：${enabled(context)}；服务连接：${health.value.connected}；规则就绪：${health.value.ready}")
        if (Build.VERSION.SDK_INT >= 30) {
            runCatching {
                context.getSystemService(ActivityManager::class.java)
                    .getHistoricalProcessExitReasons(context.packageName, 0, 3)
                    .forEach { exit ->
                        appendLine("进程退出：${java.time.Instant.ofEpochMilli(exit.timestamp)}；原因码 ${exit.reason}；状态 ${exit.status}")
                    }
            }
        }
        appendLine(context.getSharedPreferences("nuanke_service_diagnostics", Context.MODE_PRIVATE).getString("events", "暂无事件"))
    }
}
