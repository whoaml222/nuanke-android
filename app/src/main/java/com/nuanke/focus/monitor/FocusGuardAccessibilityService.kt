package com.nuanke.focus.monitor

import android.accessibilityservice.AccessibilityService
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.SystemClock
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import com.nuanke.focus.NuankeApplication
import com.nuanke.focus.R
import com.nuanke.focus.data.AppRule
import com.nuanke.focus.data.DayStats
import com.nuanke.focus.data.FocusState
import com.nuanke.focus.data.RuntimeState
import com.nuanke.focus.domain.BlockReason
import com.nuanke.focus.domain.RuleDecision
import com.nuanke.focus.domain.RuleEvaluator
import com.nuanke.focus.domain.TimeMath
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

class FocusGuardAccessibilityService : AccessibilityService() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val store by lazy { (application as NuankeApplication).store }
    private val overlay by lazy { BlockOverlay(this) }

    private var rules: Map<String, AppRule> = emptyMap()
    private var todayStats: DayStats? = null
    private var focusState = FocusState()
    private var runtimeState = RuntimeState()
    private var activePackage: String? = null
    private var sessionElapsedMillis = 0L
    private var lastTickElapsed = 0L
    private var pendingUsageMillis = 0L
    private var lastFlushElapsed = 0L
    private var ticker: Job? = null
    private var blockedEntryPackage: String? = null

    override fun onServiceConnected() {
        super.onServiceConnected()
        scope.launch { store.rules.collectLatest { rules = it.associateBy(AppRule::packageName) } }
        scope.launch { store.todayStats.collectLatest { todayStats = it } }
        scope.launch { store.focusState.collectLatest { focusState = it } }
        scope.launch { store.runtimeState.collectLatest { runtimeState = it } }
        ticker = scope.launch { runTicker() }
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event?.eventType != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) return
        val packageName = event.packageName?.toString()?.takeIf(String::isNotBlank) ?: return
        // Privacy boundary: packageName is the only event field consumed. Never inspect source/text/nodes.
        if (packageName == activePackage) return

        flushPendingUsage()
        activePackage = packageName
        sessionElapsedMillis = 0L
        pendingUsageMillis = 0L
        lastTickElapsed = SystemClock.elapsedRealtime()
        lastFlushElapsed = lastTickElapsed
        blockedEntryPackage = null

        if (packageName == this.packageName || packageName == "com.android.systemui") {
            overlay.dismiss()
            return
        }
        evaluateActivePackage()
    }

    override fun onInterrupt() = Unit

    override fun onDestroy() {
        flushPendingUsage()
        overlay.dismiss()
        ticker?.cancel()
        scope.cancel()
        super.onDestroy()
    }

    private suspend fun runTicker() {
        lastTickElapsed = SystemClock.elapsedRealtime()
        lastFlushElapsed = lastTickElapsed
        while (true) {
            delay(1_000)
            val nowElapsed = SystemClock.elapsedRealtime()
            val delta = (nowElapsed - lastTickElapsed).coerceIn(0, 5_000)
            lastTickElapsed = nowElapsed
            val packageName = activePackage
            val rule = packageName?.let(rules::get)
            val currentlyBlocked = overlay.isShowingFor(packageName)
            if (packageName != null && rule != null && !currentlyBlocked) {
                sessionElapsedMillis += delta
                pendingUsageMillis += delta
                if (nowElapsed - lastFlushElapsed >= 5_000) flushPendingUsage()
                evaluateActivePackage()
            } else if (packageName != null && focusState.blocks(packageName, System.currentTimeMillis())) {
                evaluateActivePackage()
            }
        }
    }

    private fun evaluateActivePackage() {
        val packageName = activePackage ?: return
        if (packageName == this.packageName || packageName == "com.android.systemui") return
        val rule = rules[packageName] ?: return
        val now = System.currentTimeMillis()
        val cooldown = runtimeState.cooldowns.firstOrNull { it.packageName == packageName }?.untilEpochMillis
        val daily = (todayStats?.appUsageMillis?.get(packageName) ?: 0L) + pendingUsageMillis
        val decision = RuleEvaluator.evaluate(
            rule = rule,
            sessionElapsedMillis = sessionElapsedMillis,
            dailyElapsedMillis = daily,
            nowEpochMillis = now,
            nextMidnightEpochMillis = TimeMath.nextMidnightEpochMillis(now),
            cooldownUntilEpochMillis = cooldown,
            blockedByFocus = focusState.blocks(packageName, now),
            focusEndsAtEpochMillis = focusState.phaseEndsAtEpochMillis,
        )
        if (decision is RuleDecision.Block) block(rule, decision)
    }

    private fun block(rule: AppRule, decision: RuleDecision.Block) {
        if (decision.reason == BlockReason.SESSION_LIMIT || decision.reason == BlockReason.DAILY_LIMIT) {
            runtimeState = RuntimeState(
                runtimeState.cooldowns.filterNot { it.packageName == rule.packageName } +
                    com.nuanke.focus.data.Cooldown(rule.packageName, decision.untilEpochMillis),
            )
            scope.launch { store.setCooldown(rule.packageName, decision.untilEpochMillis) }
        }
        if (blockedEntryPackage != rule.packageName) {
            blockedEntryPackage = rule.packageName
            scope.launch { store.recordBlockedAttempt() }
        }
        overlay.show(
            packageName = rule.packageName,
            appLabel = rule.appLabel,
            message = if (decision.reason == BlockReason.COOLDOWN) {
                "刚才的约定还在生效。${rule.reminder}"
            } else {
                rule.reminder
            },
            untilEpochMillis = decision.untilEpochMillis,
            onConfirm = {
                performGlobalAction(GLOBAL_ACTION_HOME)
                overlay.dismiss()
            },
        )
    }

    private fun flushPendingUsage() {
        val packageName = activePackage ?: return
        val elapsed = pendingUsageMillis
        if (elapsed <= 0 || rules[packageName] == null) return
        pendingUsageMillis = 0L
        lastFlushElapsed = SystemClock.elapsedRealtime()
        scope.launch { store.addAppUsage(packageName, elapsed) }
    }
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

        val root = LinearLayout(service).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(dp(28), dp(40), dp(28), dp(40))
            setBackgroundColor(Color.argb(248, 255, 247, 238))
        }
        val card = LinearLayout(service).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(dp(26), dp(28), dp(26), dp(24))
            background = rounded(Color.rgb(255, 253, 249), 28)
            elevation = dp(8).toFloat()
        }
        val eyebrow = TextView(service).apply {
            text = "给自己一个温柔的停顿"
            setTextColor(Color.rgb(102, 122, 85))
            textSize = 14f
        }
        val title = TextView(service).apply {
            text = service.getString(R.string.block_title, appLabel)
            setTextColor(Color.rgb(63, 52, 44))
            textSize = 24f
            setTypeface(typeface, Typeface.BOLD)
            gravity = Gravity.CENTER
            setPadding(0, dp(12), 0, dp(12))
        }
        val body = TextView(service).apply {
            text = message
            setTextColor(Color.rgb(102, 87, 75))
            textSize = 17f
            gravity = Gravity.CENTER
        }
        val remaining = TextView(service).apply {
            val millis = (untilEpochMillis - System.currentTimeMillis()).coerceAtLeast(0)
            val minutes = TimeUnit.MILLISECONDS.toMinutes(millis).coerceAtLeast(1)
            text = service.getString(R.string.block_remaining, minutes)
            setTextColor(Color.rgb(102, 122, 85))
            textSize = 14f
            gravity = Gravity.CENTER
            setPadding(0, dp(14), 0, dp(18))
        }
        val button = Button(service).apply {
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
        windowManager.addView(root, params)
        view = root
    }

    fun dismiss() {
        view?.let { runCatching { windowManager.removeView(it) } }
        view = null
        packageName = null
    }
}
