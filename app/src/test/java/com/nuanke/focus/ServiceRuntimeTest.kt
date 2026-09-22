package com.nuanke.focus

import android.app.KeyguardManager
import android.app.NotificationManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.PowerManager
import android.provider.Settings
import android.view.accessibility.AccessibilityEvent
import com.nuanke.focus.data.AppRule
import com.nuanke.focus.data.FocusPhase
import com.nuanke.focus.data.FocusState
import com.nuanke.focus.diagnostics.ServiceDiagnostics
import com.nuanke.focus.focus.FocusTimerService
import com.nuanke.focus.monitor.FocusGuardAccessibilityService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.async
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowSystemClock
import org.robolectric.util.ReflectionHelpers
import java.time.Duration

/** Framework-backed lifecycle/storage/overlay checks, not a substitute for a vivo device test. */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28, 34], application = NuankeApplication::class)
class ServiceRuntimeTest {
    private val dispatcher = StandardTestDispatcher()
    private val app get() = RuntimeEnvironment.getApplication() as NuankeApplication
    private val store get() = app.store

    @Before fun before() {
        Dispatchers.setMain(dispatcher)
        ServiceDiagnostics.activityVisible(false)
        shadowOf(app.getSystemService(PowerManager::class.java)).setIsInteractive(true)
        shadowOf(app.getSystemService(KeyguardManager::class.java)).setKeyguardLocked(false)
    }

    @After fun after() {
        Dispatchers.resetMain()
        ServiceDiagnostics.activityVisible(false)
    }

    private fun awaitMain(condition: () -> Boolean) {
        val deadline = System.nanoTime() + 10_000_000_000L
        while (!condition() && System.nanoTime() < deadline) {
            shadowOf(android.os.Looper.getMainLooper()).idle()
            dispatcher.scheduler.advanceTimeBy(10)
            dispatcher.scheduler.runCurrent()
            Thread.sleep(10)
        }
        dispatcher.scheduler.runCurrent()
        assertTrue("Asynchronous state did not settle\n${ServiceDiagnostics.report(app)}", condition())
    }

    private fun <T> io(block: suspend () -> T): T {
        val task = CoroutineScope(Dispatchers.IO).async { withTimeout(10000) { block() } }
        awaitMain { task.isCompleted }
        return runBlocking { task.await() }
    }

    private fun event(service: FocusGuardAccessibilityService, pkg: String) {
        service.onAccessibilityEvent(AccessibilityEvent.obtain(AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED).apply { packageName = pkg })
    }

    @Test fun `system permission handles short and full component names`() {
        val component = ComponentName(app, FocusGuardAccessibilityService::class.java)
        listOf(component.flattenToString(), component.flattenToShortString()).forEach {
            Settings.Secure.putString(app.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES, "other/.Service:$it")
            assertTrue(ServiceDiagnostics.enabled(app))
        }
        Settings.Secure.putString(app.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES, "other/.Service")
        assertFalse(ServiceDiagnostics.enabled(app))
    }

    @Test fun `guard connects reconnects and unbinds without disabling itself`() {
        val controller = Robolectric.buildService(FocusGuardAccessibilityService::class.java).create()
        val service = controller.get()
        try {
            repeat(2) {
                ReflectionHelpers.callInstanceMethod<Unit>(service, "onServiceConnected")
                awaitMain { ServiceDiagnostics.health.value.ready }
                assertTrue(ServiceDiagnostics.health.value.connected)
                assertNull(ServiceDiagnostics.health.value.issue)
                service.onUnbind(Intent())
                assertFalse(ServiceDiagnostics.health.value.connected)
            }
        } finally { controller.destroy() }
    }

    @Test fun `real overlay shows once for 66 events and lock clears foreground`() {
        val rule = AppRule("video.test", "测试视频", sessionLimitMinutes = 1, dailyLimitMinutes = 999)
        io { store.upsertRule(rule) }
        val countBefore = io { store.todayStats.first() }.blockedAttempts
        val controller = Robolectric.buildService(FocusGuardAccessibilityService::class.java).create()
        val service = controller.get()
        try {
            ReflectionHelpers.callInstanceMethod<Unit>(service, "onServiceConnected")
            awaitMain { ServiceDiagnostics.health.value.ready }
            event(service, rule.packageName)
            ReflectionHelpers.setField(service, "sessionElapsedMillis", 60_000L)
            ReflectionHelpers.callInstanceMethod<Unit>(service, "evaluateActivePackage")
            val overlay = ReflectionHelpers.getField<Any?>(service, "overlay")
            assertNotNull(overlay)
            assertNotNull(ReflectionHelpers.getField<Any?>(overlay, "view"))
            repeat(66) { event(service, app.packageName); event(service, rule.packageName) }
            io { store.todayStats.first { it.blockedAttempts > countBefore } }
            assertEquals(countBefore + 1, io { store.todayStats.first() }.blockedAttempts)
            assertNull(ServiceDiagnostics.health.value.issue)
            assertEquals(0L, ReflectionHelpers.getField<Long>(service, "sessionElapsedMillis"))
            // Expiry must release the same visible entry instead of immediately retriggering its old session limit.
            io { store.setCooldown(rule.packageName, System.currentTimeMillis() - 1) }
            ReflectionHelpers.getField<MutableMap<String, Long>>(service, "localCooldowns").clear()
            awaitMain {
                ReflectionHelpers.getField<com.nuanke.focus.data.StoreSnapshot?>(service, "snapshot")
                    ?.runtime?.cooldowns?.firstOrNull { it.packageName == rule.packageName }?.untilEpochMillis?.let { it < System.currentTimeMillis() } == true
            }
            ReflectionHelpers.callInstanceMethod<Unit>(service, "evaluateActivePackage")
            assertNull(ReflectionHelpers.getField<Any?>(overlay, "view"))
            ServiceDiagnostics.activityVisible(true)
            event(service, app.packageName)
            assertEquals(app.packageName, ReflectionHelpers.getField<String>(service, "activePackage"))
            ServiceDiagnostics.activityVisible(false)
            event(service, rule.packageName)
            shadowOf(app.getSystemService(PowerManager::class.java)).setIsInteractive(false)
            event(service, rule.packageName)
            assertNull(ReflectionHelpers.getField<Any?>(service, "activePackage"))
        } finally { controller.destroy() }
    }

    @Test fun `failed overlay attachment does not count or prevent later retry`() {
        val rule = AppRule("video.failure", "测试应用", sessionLimitMinutes = 1, dailyLimitMinutes = 999)
        io { store.upsertRule(rule) }
        val before = io { store.todayStats.first() }.blockedAttempts
        val controller = Robolectric.buildService(FocusGuardAccessibilityService::class.java).create()
        val service = controller.get()
        try {
            ReflectionHelpers.callInstanceMethod<Unit>(service, "onServiceConnected")
            awaitMain { ServiceDiagnostics.health.value.ready }
            val overlayClass = Class.forName("com.nuanke.focus.monitor.BlockOverlay")
            val overlay = overlayClass.getDeclaredConstructor(android.accessibilityservice.AccessibilityService::class.java)
                .apply { isAccessible = true }.newInstance(service)
            val manager = ReflectionHelpers.getField<android.view.WindowManager>(overlay, "windowManager")
            val broken = java.lang.reflect.Proxy.newProxyInstance(javaClass.classLoader, arrayOf(android.view.WindowManager::class.java)) { _, _, _ ->
                throw IllegalArgumentException("test attachment failure")
            }
            ReflectionHelpers.setField(overlay, "windowManager", broken)
            ReflectionHelpers.setField(service, "overlay", overlay)
            event(service, rule.packageName)
            ReflectionHelpers.setField(service, "sessionElapsedMillis", 60_000L)
            ReflectionHelpers.callInstanceMethod<Unit>(service, "evaluateActivePackage")
            assertEquals(before, io { store.todayStats.first() }.blockedAttempts)
            assertNull(ReflectionHelpers.getField<Any?>(overlay, "view"))
            assertTrue(ServiceDiagnostics.health.value.connected)
            val retryAfter = ReflectionHelpers.getField<Long>(service, "retryOverlayAfter")
            assertTrue(retryAfter > android.os.SystemClock.elapsedRealtime())
            ReflectionHelpers.setField(overlay, "windowManager", manager)
            ShadowSystemClock.advanceBy(Duration.ofSeconds(6))
            ReflectionHelpers.callInstanceMethod<Unit>(service, "evaluateActivePackage")
            assertNotNull(ReflectionHelpers.getField<Any?>(overlay, "view"))
            io { store.todayStats.first { it.blockedAttempts > before } }
            assertEquals(before + 1, io { store.todayStats.first() }.blockedAttempts)
        } finally { controller.destroy() }
    }

    @Test fun `guard teardown still commits its pending usage`() {
        val rule = AppRule("video.pending", "测试应用")
        io { store.upsertRule(rule) }
        val before = io { store.todayStats.first() }.appUsageMillis[rule.packageName] ?: 0
        val controller = Robolectric.buildService(FocusGuardAccessibilityService::class.java).create()
        val service = controller.get()
        ReflectionHelpers.callInstanceMethod<Unit>(service, "onServiceConnected")
        awaitMain { ServiceDiagnostics.health.value.ready }
        event(service, rule.packageName)
        ReflectionHelpers.setField(service, "pendingUsageMillis", 2200L)
        controller.destroy()
        io { store.todayStats.first { (it.appUsageMillis[rule.packageName] ?: 0) >= before + 2200 } }
        assertEquals(before + 2200, io { store.todayStats.first() }.appUsageMillis[rule.packageName])
    }

    @Test fun `activity startup renders without a startup recovery report`() {
        val controller = Robolectric.buildActivity(MainActivity::class.java).setup()
        try {
            shadowOf(android.os.Looper.getMainLooper()).idle()
            dispatcher.scheduler.runCurrent()
            assertNull(StartupCrashGuard.previousReport(app))
            assertTrue(ServiceDiagnostics.activityVisible)
        } finally { controller.pause().stop().destroy() }
    }

    @Test fun `focus state and statistics commit once and preserve partial work`() = io {
        val before = store.statsArchive.first().days.sumOf { it.focusMillis }
        val state = FocusState(running = true, phase = FocusPhase.FOCUS, phaseEndsAtEpochMillis = System.currentTimeMillis(), totalRounds = 1)
        store.setFocusState(state)
        val done = state.copy(running = false, phase = FocusPhase.COMPLETED)
        assertTrue(store.commitFocus(state, done, 60000, true))
        assertFalse(store.commitFocus(state, done, 60000, true))
        assertEquals(before + 60000, store.statsArchive.first { it.days.sumOf { day -> day.focusMillis } == before + 60000 }.days.sumOf { it.focusMillis })
        val partial = state.copy(phaseEndsAtEpochMillis = System.currentTimeMillis() + 60000)
        store.setFocusState(partial)
        assertTrue(store.commitFocus(partial, done, 20000))
        assertEquals(before + 80000, store.statsArchive.first { it.days.sumOf { day -> day.focusMillis } == before + 80000 }.days.sumOf { it.focusMillis })
    }

    @Test fun `timer start duplicate start and stop persist and notify`() {
        io { store.setFocusState(FocusState()) }
        val controller = Robolectric.buildService(FocusTimerService::class.java).create()
        val service = controller.get()
        val start = Intent(app, FocusTimerService::class.java).setAction(FocusTimerService.ACTION_START)
            .putExtra(FocusTimerService.EXTRA_FOCUS_MINUTES, 1)
        try {
            service.onStartCommand(start, 0, 1)
            awaitMain { ReflectionHelpers.getField<FocusState>(service, "state").running }
            val initial = io { store.focusState.first { it.running } }
            service.onStartCommand(start, 0, 2)
            dispatcher.scheduler.runCurrent()
            assertEquals(initial, io { store.focusState.first() })
            // Robolectric uptime is virtual, but Java wall time is real. Backdate the persisted phase.
            val backdated = initial.copy(phaseEndsAtEpochMillis = System.currentTimeMillis() + 39_000)
            io { store.setFocusState(backdated) }
            service.onStartCommand(Intent().setAction(FocusTimerService.ACTION_STOP), 0, 3)
            awaitMain { !ReflectionHelpers.getField<FocusState>(service, "state").running }
            val stopped = io { store.focusState.first { !it.running } }
            assertEquals(FocusPhase.COMPLETED, stopped.phase)
            assertTrue(io { store.statsArchive.first() }.days.sumOf { it.focusMillis } >= 20000)
            awaitMain {
                shadowOf(app.getSystemService(NotificationManager::class.java)).getNotification(1002)
                    ?.extras?.getString(android.app.Notification.EXTRA_TITLE) == "专注已结束"
            }
        } finally { controller.destroy() }
    }

    @Test fun `diagnostic excludes exception messages and user content`() {
        ServiceDiagnostics.error(app, "测试操作", IllegalStateException("secret chat device identifier"))
        val report = ServiceDiagnostics.report(app)
        assertFalse(report.contains("secret chat"))
        assertTrue(report.contains("IllegalStateException"))
        assertTrue(report.contains("Android API"))
    }
}
