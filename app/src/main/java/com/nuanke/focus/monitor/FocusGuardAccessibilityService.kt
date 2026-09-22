package com.nuanke.focus.monitor

import android.accessibilityservice.AccessibilityService
import android.app.KeyguardManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.PowerManager
import android.os.SystemClock
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import com.nuanke.focus.NuankeApplication
import com.nuanke.focus.R
import com.nuanke.focus.data.AppStore
import com.nuanke.focus.data.AppRule
import com.nuanke.focus.data.StoreSnapshot
import com.nuanke.focus.domain.BlockReason
import com.nuanke.focus.domain.ForegroundEntryTracker
import com.nuanke.focus.domain.RuleDecision
import com.nuanke.focus.domain.RuleEvaluator
import com.nuanke.focus.domain.TimeMath
import com.nuanke.focus.domain.UsageClock
import com.nuanke.focus.domain.DailyDurations
import com.nuanke.focus.diagnostics.ServiceDiagnostics
import java.time.LocalDate
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

class FocusGuardAccessibilityService : AccessibilityService() {
    private val store by lazy { (application as? NuankeApplication)?.store ?: AppStore(applicationContext) }
    private var connectionScope: CoroutineScope? = null
    private var overlay: BlockOverlay? = null
    private val entryTracker = ForegroundEntryTracker(setOf("com.android.systemui"))
    private val clock = UsageClock()
    private var snapshot: StoreSnapshot? = null
    private var activePackage: String? = null
    private var sessionElapsedMillis = 0L
    private var pendingUsageMillis = 0L
    private var usageDate = ""
    private val dailyUsage = mutableMapOf<String, Long>()
    private val localCooldowns = mutableMapOf<String, Long>()
    private var retryOverlayAfter = 0L
    private var receiverRegistered = false
    private val screenReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == Intent.ACTION_SCREEN_OFF) guarded("锁屏暂停") { leaveForeground() }
        }
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        guarded("连接守护") {
            connectionScope?.cancel()
            leaveForeground()
            snapshot = null
            ServiceDiagnostics.connected(this)
            if (!receiverRegistered) {
                try {
                    val filter = IntentFilter(Intent.ACTION_SCREEN_OFF)
                    if (android.os.Build.VERSION.SDK_INT >= 33) {
                        registerReceiver(screenReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
                    } else {
                        // SCREEN_OFF is a protected system broadcast; no app-defined permission is needed.
                        @Suppress("DEPRECATION")
                        registerReceiver(screenReceiver, filter)
                    }
                    receiverRegistered = true
                } catch (error: RuntimeException) {
                    // Screen-state polling below remains available if a vendor rejects registration.
                    ServiceDiagnostics.error(this, "注册锁屏监听", error)
                }
            }
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
            connectionScope = scope
            scope.launch {
                while (isActive) {
                    try {
                        store.snapshots.collect {
                            snapshot = it
                            refreshDate()
                            val stored = it.archive.days.firstOrNull { day -> day.date == usageDate }
                            stored?.appUsageMillis?.forEach { (pkg, millis) ->
                                dailyUsage[pkg] = maxOf(dailyUsage[pkg] ?: 0, millis)
                            }
                            ServiceDiagnostics.ready()
                            guarded("更新守护规则") { evaluateActivePackage() }
                        }
                    } catch (error: Exception) {
                        if (error is CancellationException) throw error
                        snapshot = null
                        ServiceDiagnostics.error(this@FocusGuardAccessibilityService, "加载守护规则", error)
                        delay(3_000)
                    }
                }
            }
            scope.launch {
                clock.reset(SystemClock.elapsedRealtime())
                while (isActive) {
                    delay(1_000)
                    guarded("守护计时") {
                        if (!interactive()) {
                            leaveForeground()
                        } else {
                            if (ServiceDiagnostics.activityVisible && activePackage != packageName) changePackage(packageName)
                            accrueUsage()
                            if (pendingUsageMillis >= 5_000) flushPendingUsage()
                            evaluateActivePackage()
                        }
                    }
                }
            }
        }
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event?.eventType != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) return
        guarded("处理前台切换") {
            // Never access event.source, text, nodes, or window contents.
            val foreground = event.packageName?.toString()?.takeIf(String::isNotBlank) ?: return@guarded
            if (!interactive()) { leaveForeground(); return@guarded }
            // Only our visible activity is a real app transition. Our overlay is not.
            if (foreground == packageName && !ServiceDiagnostics.activityVisible) return@guarded
            val ime = android.provider.Settings.Secure.getString(contentResolver, android.provider.Settings.Secure.DEFAULT_INPUT_METHOD)
                ?.substringBefore('/')
            if (foreground == ime) return@guarded
            changePackage(foreground)
        }
    }

    private fun changePackage(foreground: String) {
        accrueUsage()
        val entry = entryTracker.observe(foreground) ?: return
        flushPendingUsage()
        activePackage = entry.packageName
        sessionElapsedMillis = 0
        retryOverlayAfter = 0
        clock.reset(SystemClock.elapsedRealtime())
        overlay?.dismiss()
        evaluateActivePackage()
    }

    private fun interactive(): Boolean =
        getSystemService(PowerManager::class.java).isInteractive &&
            !getSystemService(KeyguardManager::class.java).isKeyguardLocked

    private fun refreshDate() {
        val date = LocalDate.now().toString()
        if (usageDate == date) return
        usageDate = date
        dailyUsage.clear()
        snapshot?.archive?.days?.firstOrNull { it.date == date }?.appUsageMillis?.let(dailyUsage::putAll)
    }

    private fun accrueUsage() {
        val delta = clock.tick(SystemClock.elapsedRealtime(), interactive())
        refreshDate()
        val pkg = activePackage ?: return
        val rule = snapshot?.rules?.firstOrNull { it.packageName == pkg } ?: return
        if (!rule.enabled || entryTracker.isBlocked(pkg) || delta == 0L) return
        sessionElapsedMillis += delta
        pendingUsageMillis += delta
        val todayPart = DailyDurations.split(System.currentTimeMillis(), delta)[usageDate] ?: 0
        dailyUsage[pkg] = (dailyUsage[pkg] ?: 0) + todayPart
    }

    private fun evaluateActivePackage() {
        val data = snapshot ?: return
        val pkg = activePackage ?: return
        if (!interactive()) return
        val rule = data.rules.firstOrNull { it.packageName == pkg }
        if (rule == null || !rule.enabled || pkg in safetyPackages(this)) {
            overlay?.dismiss()
            entryTracker.releaseBlock()
            sessionElapsedMillis = 0
            return
        }
        refreshDate()
        val now = System.currentTimeMillis()
        val cooldown = maxOf(
            data.runtime.cooldowns.firstOrNull { it.packageName == pkg }?.untilEpochMillis ?: 0,
            localCooldowns[pkg] ?: 0,
        )
        val decision = RuleEvaluator.evaluate(
            rule, sessionElapsedMillis, dailyUsage[pkg] ?: 0, now,
            TimeMath.nextMidnightEpochMillis(now), cooldown,
            data.focus.blocks(pkg, now), data.focus.phaseEndsAtEpochMillis,
        )
        when (decision) {
            RuleDecision.Allow -> {
                if (entryTracker.isBlocked(pkg)) sessionElapsedMillis = 0
                overlay?.dismiss()
                entryTracker.releaseBlock()
            }
            is RuleDecision.Block -> block(rule, decision)
        }
    }

    private fun block(rule: AppRule, decision: RuleDecision.Block) {
        if (entryTracker.isBlocked(rule.packageName) || SystemClock.elapsedRealtime() < retryOverlayAfter) return
        try {
            val currentOverlay = overlay ?: BlockOverlay(this).also { overlay = it }
            currentOverlay.show(
                rule.packageName, rule.appLabel,
                if (decision.reason == BlockReason.COOLDOWN) "刚才的约定还在生效。${rule.reminder}" else rule.reminder,
                decision.untilEpochMillis,
            ) {
                guarded("返回桌面") {
                    // Keep the reminder visible if Android rejects the Home action.
                    if (performGlobalAction(GLOBAL_ACTION_HOME)) {
                        currentOverlay.dismiss()
                        activePackage = null
                        clock.reset(SystemClock.elapsedRealtime())
                    }
                }
            }
            if (entryTracker.markBlocked(rule.packageName)) {
                flushPendingUsage()
                sessionElapsedMillis = 0
                if (decision.reason == BlockReason.SESSION_LIMIT || decision.reason == BlockReason.DAILY_LIMIT) {
                    localCooldowns[rule.packageName] = decision.untilEpochMillis
                    store.enqueue { store.setCooldown(rule.packageName, decision.untilEpochMillis) }
                }
                store.enqueue { store.recordBlockedAttempt() }
            }
        } catch (error: Exception) {
            // Do not count failed attachment or hammer WindowManager every tick.
            retryOverlayAfter = SystemClock.elapsedRealtime() + 5_000
            ServiceDiagnostics.error(this, "显示提醒", error)
        }
    }

    private fun flushPendingUsage() {
        val pkg = activePackage ?: return
        val elapsed = pendingUsageMillis
        if (elapsed <= 0) return
        pendingUsageMillis = 0
        val end = System.currentTimeMillis()
        store.enqueue { store.addAppUsage(pkg, elapsed, end) }
    }

    private fun leaveForeground() {
        flushPendingUsage()
        activePackage = null
        sessionElapsedMillis = 0
        entryTracker.reset()
        clock.reset(SystemClock.elapsedRealtime())
        overlay?.dismiss()
    }

    private fun disconnect() {
        guarded("保存守护状态") { leaveForeground() }
        connectionScope?.cancel()
        connectionScope = null
        snapshot = null
        if (receiverRegistered) {
            runCatching { unregisterReceiver(screenReceiver) }
            receiverRegistered = false
        }
        ServiceDiagnostics.disconnected(this)
    }

    private inline fun guarded(operation: String, block: () -> Unit) {
        try { block() } catch (error: Exception) {
            if (error is CancellationException) throw error
            ServiceDiagnostics.error(this, operation, error)
        }
    }

    override fun onInterrupt() { guarded("守护中断") { leaveForeground() } }
    override fun onUnbind(intent: Intent?): Boolean { disconnect(); return super.onUnbind(intent) }
    override fun onDestroy() { disconnect(); super.onDestroy() }
}

private class BlockOverlay(private val service: AccessibilityService) {
    private val windowManager = service.getSystemService(WindowManager::class.java)
    private var view: View? = null
    private var packageName: String? = null

    fun isShowingFor(candidate: String?): Boolean = view != null && candidate == packageName

    fun show(
        packageName: String,
        appLabel: String,
        message: String,
        untilEpochMillis: Long,
        onConfirm: () -> Unit,
    ) {
        if (isShowingFor(packageName)) return
        dismiss()
        this.packageName = packageName

        val density = service.resources.displayMetrics.density
        fun dp(value: Int) = (value * density).toInt()
        fun rounded(color: Int, radius: Int) = GradientDrawable().apply {
            setColor(color)
            cornerRadius = dp(radius).toFloat()
        }

        val uiContext = android.view.ContextThemeWrapper(service, R.style.Theme_Nuanke)
        val root = LinearLayout(uiContext).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(dp(28), dp(40), dp(28), dp(40))
            setBackgroundColor(Color.argb(248, 255, 247, 238))
        }
        val card = LinearLayout(uiContext).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(dp(26), dp(28), dp(26), dp(24))
            background = rounded(Color.rgb(255, 253, 249), 28)
            elevation = dp(8).toFloat()
        }
        val eyebrow = TextView(uiContext).apply {
            text = "给自己一个温柔的停顿"
            setTextColor(Color.rgb(102, 122, 85))
            textSize = 14f
        }
        val title = TextView(uiContext).apply {
            text = service.getString(R.string.block_title, appLabel)
            setTextColor(Color.rgb(63, 52, 44))
            textSize = 24f
            setTypeface(typeface, Typeface.BOLD)
            gravity = Gravity.CENTER
            setPadding(0, dp(12), 0, dp(12))
        }
        val body = TextView(uiContext).apply {
            text = message
            setTextColor(Color.rgb(102, 87, 75))
            textSize = 17f
            gravity = Gravity.CENTER
        }
        val remaining = TextView(uiContext).apply {
            val millis = (untilEpochMillis - System.currentTimeMillis()).coerceAtLeast(0)
            val minutes = TimeUnit.MILLISECONDS.toMinutes(millis).coerceAtLeast(1)
            text = service.getString(R.string.block_remaining, minutes)
            setTextColor(Color.rgb(102, 122, 85))
            textSize = 14f
            gravity = Gravity.CENTER
            setPadding(0, dp(14), 0, dp(18))
        }
        val button = Button(uiContext).apply {
            text = "知道了，去学习"
            isAllCaps = false
            textSize = 16f
            setTextColor(Color.WHITE)
            background = rounded(Color.rgb(230, 111, 66), 18)
            setPadding(dp(24), dp(12), dp(24), dp(12))
            setOnClickListener { onConfirm() }
        }
        card.addView(eyebrow)
        card.addView(title)
        card.addView(body)
        card.addView(remaining)
        card.addView(button, LinearLayout.LayoutParams(-1, dp(56)))
        root.addView(card, LinearLayout.LayoutParams(-1, -2))

        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT,
        ).apply { gravity = Gravity.CENTER }
        val scroll = ScrollView(uiContext).apply {
            isFillViewport = true
            addView(root)
        }
        windowManager.addView(scroll, params)
        view = scroll
    }

    fun dismiss() {
        view?.let { runCatching { windowManager.removeView(it) } }
        view = null
        packageName = null
    }
}
