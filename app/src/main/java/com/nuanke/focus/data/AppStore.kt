package com.nuanke.focus.data

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import java.time.LocalDate
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

private val Context.nuankeDataStore by preferencesDataStore(name = "nuanke_local")

class AppStore(private val context: Context) {
    private val json = Json { ignoreUnknownKeys = true }

    private object Keys {
        val rules = stringPreferencesKey("rules")
        val stats = stringPreferencesKey("stats")
        val focus = stringPreferencesKey("focus")
        val runtime = stringPreferencesKey("runtime")
    }

    val rules: Flow<List<AppRule>> = context.nuankeDataStore.data.map { preferences ->
        decode(preferences[Keys.rules], emptyList())
    }

    val statsArchive: Flow<StatsArchive> = context.nuankeDataStore.data.map { preferences ->
        decode(preferences[Keys.stats], StatsArchive())
    }

    val todayStats: Flow<DayStats> = statsArchive.map { archive ->
        archive.days.firstOrNull { it.date == LocalDate.now().toString() }
            ?: DayStats(LocalDate.now().toString())
    }

    val focusState: Flow<FocusState> = context.nuankeDataStore.data.map { preferences ->
        decode(preferences[Keys.focus], FocusState())
    }

    val runtimeState: Flow<RuntimeState> = context.nuankeDataStore.data.map { preferences ->
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

    suspend fun addAppUsage(packageName: String, elapsedMillis: Long) {
        if (elapsedMillis <= 0) return
        updateToday { day ->
            val usage = day.appUsageMillis.toMutableMap()
            usage[packageName] = (usage[packageName] ?: 0L) + elapsedMillis
            day.copy(appUsageMillis = usage)
        }
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

