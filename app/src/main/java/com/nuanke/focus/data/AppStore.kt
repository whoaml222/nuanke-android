package com.nuanke.focus.data

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import java.time.LocalDate
import com.nuanke.focus.diagnostics.ServiceDiagnostics
import com.nuanke.focus.domain.DailyDurations
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.retryWhen
import kotlinx.coroutines.flow.shareIn
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

private val Context.nuankeDataStore by preferencesDataStore(name = "nuanke_local")

data class StoreSnapshot(
    val rules: List<AppRule>,
    val archive: StatsArchive,
    val focus: FocusState,
    val runtime: RuntimeState,
)

class AppStore(context: Context) {
    private val context = context.applicationContext
    private val json = Json { ignoreUnknownKeys = true }
    private val writes = Channel<suspend () -> Unit>(Channel.UNLIMITED)
    private val writer = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    init {
        writer.launch {
            for (operation in writes) {
                try { operation() } catch (error: Exception) {
                    if (error is CancellationException) throw error
                    ServiceDiagnostics.error(this@AppStore.context, "保存本机记录", error)
                }
            }
        }
    }

    /** Process-lifetime queue: service teardown cannot cancel an accepted write. */
    fun enqueue(operation: suspend () -> Unit) { check(writes.trySend(operation).isSuccess) }

    private val preferences = this.context.nuankeDataStore.data.retryWhen { cause, _ ->
        if (cause is Exception && cause !is CancellationException) {
            ServiceDiagnostics.error(this@AppStore.context, "读取本机记录", cause)
            delay(3_000)
            true
        } else false
    }.shareIn(writer, SharingStarted.Eagerly, replay = 1)

    val snapshots: Flow<StoreSnapshot> = preferences.map { values ->
        StoreSnapshot(
            decode(values[Keys.rules], emptyList()),
            decode(values[Keys.stats], StatsArchive()),
            decode(values[Keys.focus], FocusState()),
            decode(values[Keys.runtime], RuntimeState()),
        )
    }

    private object Keys {
        val rules = stringPreferencesKey("rules")
        val stats = stringPreferencesKey("stats")
        val focus = stringPreferencesKey("focus")
        val runtime = stringPreferencesKey("runtime")
    }

    val rules: Flow<List<AppRule>> = preferences.map { preferences ->
        decode(preferences[Keys.rules], emptyList())
    }

    val statsArchive: Flow<StatsArchive> = preferences.map { preferences ->
        decode(preferences[Keys.stats], StatsArchive())
    }

    val todayStats: Flow<DayStats> = combine(statsArchive, flow {
        while (true) { emit(LocalDate.now().toString()); delay(1_000) }
    }) { archive, today ->
        archive.days.firstOrNull { it.date == today } ?: DayStats(today)
    }

    val focusState: Flow<FocusState> = preferences.map { preferences ->
        decode(preferences[Keys.focus], FocusState())
    }

    /** Commands read the authoritative transaction state, never a possibly lagging UI replay. */
    suspend fun currentFocusState(): FocusState =
        decode(context.nuankeDataStore.data.first()[Keys.focus], FocusState())

    val runtimeState: Flow<RuntimeState> = preferences.map { preferences ->
        decode(preferences[Keys.runtime], RuntimeState())
    }

    suspend fun upsertRule(rule: AppRule) {
        context.nuankeDataStore.edit { preferences ->
            val current: List<AppRule> = decode(preferences[Keys.rules], emptyList())
            val updated = current.filterNot { it.packageName == rule.packageName } + rule
            preferences[Keys.rules] = json.encodeToString(updated.sortedBy { it.appLabel })
        }
    }

    suspend fun deleteRule(packageName: String) {
        context.nuankeDataStore.edit { preferences ->
            val current: List<AppRule> = decode(preferences[Keys.rules], emptyList())
            preferences[Keys.rules] = json.encodeToString(current.filterNot { it.packageName == packageName })
        }
    }

    suspend fun addAppUsage(packageName: String, elapsedMillis: Long, endEpochMillis: Long = System.currentTimeMillis()) {
        if (elapsedMillis <= 0) return
        context.nuankeDataStore.edit { preferences ->
            var archive: StatsArchive = decode(preferences[Keys.stats], StatsArchive())
            DailyDurations.split(endEpochMillis, elapsedMillis).forEach { (date, duration) ->
                archive = archive.updateDay(date) { day ->
                    day.copy(appUsageMillis = day.appUsageMillis + (packageName to ((day.appUsageMillis[packageName] ?: 0) + duration)))
                }
            }
            preferences[Keys.stats] = json.encodeToString(archive)
        }
    }

    /** Commit statistics and timer state together; repeated transitions are idempotent. */
    suspend fun commitFocus(expected: FocusState, next: FocusState, duration: Long = 0, completed: Boolean = false): Boolean {
        var accepted = false
        context.nuankeDataStore.edit { values ->
            val stored: FocusState = decode(values[Keys.focus], FocusState())
            if (stored != expected) return@edit
            var archive: StatsArchive = decode(values[Keys.stats], StatsArchive())
            val end = minOf(System.currentTimeMillis(), expected.phaseEndsAtEpochMillis)
            DailyDurations.split(end, duration).forEach { (date, millis) ->
                archive = archive.updateDay(date) { it.copy(focusMillis = it.focusMillis + millis) }
            }
            if (completed) {
                val date = java.time.Instant.ofEpochMilli((expected.phaseEndsAtEpochMillis - 1).coerceAtLeast(0))
                    .atZone(java.time.ZoneId.systemDefault()).toLocalDate().toString()
                archive = archive.updateDay(date) { it.copy(focusCompletions = it.focusCompletions + 1) }
            }
            values[Keys.stats] = json.encodeToString(archive)
            values[Keys.focus] = json.encodeToString(next)
            accepted = true
        }
        return accepted
    }

    suspend fun recordBlockedAttempt() = updateToday { day ->
        day.copy(blockedAttempts = day.blockedAttempts + 1)
    }

    suspend fun recordFocusCompleted(elapsedMillis: Long) = updateToday { day ->
        day.copy(
            focusMillis = day.focusMillis + elapsedMillis.coerceAtLeast(0),
            focusCompletions = day.focusCompletions + 1,
        )
    }

    suspend fun setFocusState(state: FocusState) {
        context.nuankeDataStore.edit { it[Keys.focus] = json.encodeToString(state) }
    }

    suspend fun setCooldown(packageName: String, untilEpochMillis: Long) {
        context.nuankeDataStore.edit { preferences ->
            val current: RuntimeState = decode(preferences[Keys.runtime], RuntimeState())
            val now = System.currentTimeMillis()
            val updated = current.cooldowns
                .filter { it.packageName != packageName && it.untilEpochMillis > now } +
                Cooldown(packageName, untilEpochMillis)
            preferences[Keys.runtime] = json.encodeToString(RuntimeState(updated))
        }
    }

    private suspend fun updateToday(transform: (DayStats) -> DayStats) {
        context.nuankeDataStore.edit { preferences ->
            val archive: StatsArchive = decode(preferences[Keys.stats], StatsArchive())
            val today = LocalDate.now().toString()
            val current = archive.days.firstOrNull { it.date == today } ?: DayStats(today)
            val retained = archive.days.filterNot { it.date == today }
                .sortedByDescending { it.date }
                .take(89)
            preferences[Keys.stats] = json.encodeToString(StatsArchive(listOf(transform(current)) + retained))
        }
    }

    private inline fun <reified T> decode(raw: String?, fallback: T): T =
        if (raw.isNullOrBlank()) fallback else runCatching { json.decodeFromString<T>(raw) }.getOrDefault(fallback)
}

private fun StatsArchive.updateDay(date: String, transform: (DayStats) -> DayStats): StatsArchive = StatsArchive(
    (days.filterNot { it.date == date } + transform(days.firstOrNull { it.date == date } ?: DayStats(date)))
        .sortedByDescending(DayStats::date).take(90),
)
