package com.nuanke.focus.focus

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.os.IBinder
import androidx.core.app.NotificationCompat
import com.nuanke.focus.diagnostics.ServiceDiagnostics
import com.nuanke.focus.MainActivity
import com.nuanke.focus.NuankeApplication
import com.nuanke.focus.R
import com.nuanke.focus.data.AppStore
import com.nuanke.focus.data.FocusPhase
import com.nuanke.focus.data.FocusState
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

class FocusTimerService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate + CoroutineExceptionHandler { _, error ->
        ServiceDiagnostics.error(this, "专注计时", error)
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    })
    private val stateLock = Mutex()
    private val store by lazy {
        (application as? NuankeApplication)?.store ?: AppStore(applicationContext)
    }
    private var timerJob: Job? = null
    private var state = FocusState()

    override fun onCreate() {
        super.onCreate()
        active = true
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannels(
            listOf(
                NotificationChannel(
                    TIMER_CHANNEL_ID,
                    getString(R.string.focus_notification_channel),
                    NotificationManager.IMPORTANCE_LOW,
                ),
                NotificationChannel(
                    EVENT_CHANNEL_ID,
                    getString(R.string.focus_event_notification_channel),
                    NotificationManager.IMPORTANCE_HIGH,
                ).apply {
                    description = "在专注阶段开始和结束时提醒"
                    setSound(null, null)
                    enableVibration(true)
                    vibrationPattern = EVENT_VIBRATION_PATTERN
                },
            ),
        )
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action != ACTION_STOP) {
            try { startForeground(NOTIFICATION_ID, notification(state)) } catch (error: RuntimeException) {
                ServiceDiagnostics.error(this, "启动专注通知", error)
                stopSelf()
                return START_NOT_STICKY
            }
        }
        scope.launch {
            stateLock.withLock {
                when (intent?.action) {
                    ACTION_START -> startFromIntent(intent)
                    ACTION_STOP -> stopTimer()
                    else -> restoreTimer()
                }
            }
        }
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        active = false
        timerJob?.cancel()
        scope.cancel()
        super.onDestroy()
    }

    private suspend fun startFromIntent(intent: Intent) {
        val stored = store.currentFocusState()
        if (stored.running) { state = stored; launchTicker(); return }
        val focusMinutes = intent.getIntExtra(EXTRA_FOCUS_MINUTES, 25).coerceIn(1, 180)
        val breakMinutes = intent.getIntExtra(EXTRA_BREAK_MINUTES, 5).coerceIn(1, 60)
        val rounds = intent.getIntExtra(EXTRA_ROUNDS, 4).coerceIn(1, 12)
        val now = System.currentTimeMillis()
        state = FocusState(
            running = true,
            phase = FocusPhase.FOCUS,
            taskLabel = intent.getStringExtra(EXTRA_TASK).orEmpty(),
            startedAtEpochMillis = now,
            phaseEndsAtEpochMillis = now + focusMinutes * 60_000L,
            focusMinutes = focusMinutes,
            breakMinutes = breakMinutes,
            currentRound = 1,
            totalRounds = rounds,
            blockedPackages = intent.getStringArrayListExtra(EXTRA_BLOCKED_PACKAGES)?.toSet().orEmpty(),
        )
        store.setFocusState(state)
        announceFocusStarted(state)
        launchTicker()
    }

    private suspend fun restoreTimer() {
        state = store.currentFocusState()
        if (!state.running) {
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
        } else {
            launchTicker()
        }
    }

    private fun launchTicker() {
        timerJob?.cancel()
        timerJob = scope.launch {
            while (state.running) {
                stateLock.withLock {
                    val now = System.currentTimeMillis()
                    if (state.running && now >= state.phaseEndsAtEpochMillis) transitionPhase(now)
                }
                if (!state.running) break
                getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, notification(state))
                delay(1_000)
            }
        }
    }

    private suspend fun transitionPhase(now: Long) {
        val previous = state
        val transition = FocusCycle.advance(state, now)
        val accepted = store.commitFocus(previous, transition.state, transition.completedFocusMillis, transition.completedFocusMillis > 0)
        state = if (accepted) transition.state else store.currentFocusState()
        if (!accepted) return
        when (transition.event) {
            FocusCycleEvent.SESSION_COMPLETED -> announce(
                title = "专注完成",
                message = "完成了 ${previous.totalRounds} 轮，辛苦了，记得舒展一下。",
            )
            FocusCycleEvent.FOCUS_ENDED_FOR_BREAK -> announce(
                title = "本轮专注结束",
                message = "第 ${previous.currentRound} 轮完成，休息 ${state.breakMinutes} 分钟。",
            )
            FocusCycleEvent.FOCUS_STARTED -> announceFocusStarted(state)
            FocusCycleEvent.NONE -> Unit
        }
        if (!state.running) {
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
        }
    }

    private suspend fun stopTimer() {
        state = store.currentFocusState()
        val wasRunning = state.running
        timerJob?.cancel()
        val previous = state
        state = state.copy(running = false, phase = FocusPhase.COMPLETED)
        val partialMillis = if (wasRunning && previous.phase == FocusPhase.FOCUS) {
            (previous.focusMinutes * 60_000L - (previous.phaseEndsAtEpochMillis - System.currentTimeMillis()).coerceAtLeast(0))
                .coerceIn(0, previous.focusMinutes * 60_000L)
        } else 0L
        store.commitFocus(previous, state, partialMillis)
        if (wasRunning) {
            announce(
                title = "专注已结束",
                message = "本次专注已由你手动结束。",
            )
        }
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun announceFocusStarted(value: FocusState) {
        announce(
            title = "专注开始",
            message = "第 ${value.currentRound}/${value.totalRounds} 轮 · ${value.focusMinutes} 分钟",
        )
    }

    private fun announce(title: String, message: String) {
        val openIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val alert = NotificationCompat.Builder(this, EVENT_CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(title)
            .setContentText(message)
            .setContentIntent(openIntent)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_REMINDER)
            .setAutoCancel(true)
            .build()
        runCatching { getSystemService(NotificationManager::class.java).notify(EVENT_NOTIFICATION_ID, alert) }
            .onFailure { ServiceDiagnostics.error(this, "发送专注提醒", it) }
    }

    private fun notification(value: FocusState): Notification {
        val openIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val stopIntent = PendingIntent.getService(
            this,
            1,
            Intent(this, FocusTimerService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val remaining = (value.phaseEndsAtEpochMillis - System.currentTimeMillis()).coerceAtLeast(0)
        val minutes = TimeUnit.MILLISECONDS.toMinutes(remaining)
        val seconds = TimeUnit.MILLISECONDS.toSeconds(remaining) % 60
        val phaseText = if (value.phase == FocusPhase.BREAK) "休息" else "专注"
        val task = value.taskLabel.ifBlank { "此刻的一件事" }
        return NotificationCompat.Builder(this, TIMER_CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle("$phaseText · ${value.currentRound}/${value.totalRounds} 轮")
            .setContentText("$task · %02d:%02d".format(minutes, seconds))
            .setContentIntent(openIntent)
            .setOngoing(value.running)
            .setOnlyAlertOnce(true)
            .addAction(0, "结束", stopIntent)
            .build()
    }

    companion object {
        var active = false
            private set
        const val ACTION_START = "com.nuanke.focus.action.START_FOCUS"
        const val ACTION_STOP = "com.nuanke.focus.action.STOP_FOCUS"
        const val EXTRA_TASK = "task"
        const val EXTRA_FOCUS_MINUTES = "focus_minutes"
        const val EXTRA_BREAK_MINUTES = "break_minutes"
        const val EXTRA_ROUNDS = "rounds"
        const val EXTRA_BLOCKED_PACKAGES = "blocked_packages"
        private const val TIMER_CHANNEL_ID = "nuanke_focus_timer"
        private const val EVENT_CHANNEL_ID = "nuanke_focus_events_v1"
        private const val NOTIFICATION_ID = 1001
        private const val EVENT_NOTIFICATION_ID = 1002
        private val EVENT_VIBRATION_PATTERN = longArrayOf(0, 180, 90, 120)
    }
}
