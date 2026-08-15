package com.nuanke.focus.focus

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.os.IBinder
import androidx.core.app.NotificationCompat
import com.nuanke.focus.MainActivity
import com.nuanke.focus.NuankeApplication
import com.nuanke.focus.R
import com.nuanke.focus.data.FocusPhase
import com.nuanke.focus.data.FocusState
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

class FocusTimerService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val store by lazy { (application as NuankeApplication).store }
    private var timerJob: Job? = null
    private var state = FocusState()

    override fun onCreate() {
        super.onCreate()
        getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(CHANNEL_ID, getString(R.string.focus_notification_channel), NotificationManager.IMPORTANCE_LOW),
        )
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> startFromIntent(intent)
            ACTION_STOP -> stopTimer()
            else -> restoreTimer()
        }
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        timerJob?.cancel()
        scope.cancel()
        super.onDestroy()
    }

    private fun startFromIntent(intent: Intent) {
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
        scope.launch { store.setFocusState(state) }
        startForeground(NOTIFICATION_ID, notification(state))
        launchTicker()
    }

    private fun restoreTimer() {
        startForeground(NOTIFICATION_ID, notification(FocusState(running = true, phase = FocusPhase.FOCUS)))
        scope.launch {
            state = store.focusState.first()
            if (!state.running) {
                stopSelf()
            } else {
                launchTicker()
            }
        }
    }

    private fun launchTicker() {
        timerJob?.cancel()
        timerJob = scope.launch {
            while (state.running) {
                val now = System.currentTimeMillis()
                if (now >= state.phaseEndsAtEpochMillis) transitionPhase(now)
                getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, notification(state))
                delay(1_000)
            }
        }
    }

    private suspend fun transitionPhase(now: Long) {
        state = when (state.phase) {
            FocusPhase.FOCUS -> {
                store.recordFocusCompleted(state.focusMinutes * 60_000L)
                if (state.currentRound >= state.totalRounds) {
                    state.copy(running = false, phase = FocusPhase.COMPLETED, phaseEndsAtEpochMillis = now)
                } else {
                    state.copy(phase = FocusPhase.BREAK, phaseEndsAtEpochMillis = now + state.breakMinutes * 60_000L)
                }
            }
            FocusPhase.BREAK -> state.copy(
                phase = FocusPhase.FOCUS,
                currentRound = state.currentRound + 1,
                phaseEndsAtEpochMillis = now + state.focusMinutes * 60_000L,
            )
            FocusPhase.COMPLETED -> state.copy(running = false)
        }
        store.setFocusState(state)
        if (!state.running) {
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
        }
    }

    private fun stopTimer() {
        timerJob?.cancel()
        state = state.copy(running = false, phase = FocusPhase.COMPLETED)
        scope.launch { store.setFocusState(state) }
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
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
        return NotificationCompat.Builder(this, CHANNEL_ID)
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
        const val ACTION_START = "com.nuanke.focus.action.START_FOCUS"
        const val ACTION_STOP = "com.nuanke.focus.action.STOP_FOCUS"
        const val EXTRA_TASK = "task"
        const val EXTRA_FOCUS_MINUTES = "focus_minutes"
        const val EXTRA_BREAK_MINUTES = "break_minutes"
        const val EXTRA_ROUNDS = "rounds"
        const val EXTRA_BLOCKED_PACKAGES = "blocked_packages"
        private const val CHANNEL_ID = "nuanke_focus_timer"
        private const val NOTIFICATION_ID = 1001
    }
}
