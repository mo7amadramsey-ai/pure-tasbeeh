package com.puretasbeeh

import android.app.Application
import android.content.Context
import android.os.SystemClock
import androidx.compose.runtime.*
import androidx.datastore.preferences.core.*
import androidx.datastore.preferences.preferencesDataStore
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.launch
import java.time.LocalDate

val Context.ds by preferencesDataStore("pure_tasbeeh")

private object K {
    val dhikr = intPreferencesKey("dhikr")
    val count = intPreferencesKey("count")
    val goal = intPreferencesKey("goal")
    val vibrate = booleanPreferencesKey("vibrate")
    val theme = intPreferencesKey("theme")
    val strict = booleanPreferencesKey("strict")
    val interval = longPreferencesKey("interval")
    val total = longPreferencesKey("total")
    val sessions = intPreferencesKey("sessions")
    val daily = stringPreferencesKey("daily")
    val per = stringPreferencesKey("per")
}

data class UiState(
    val dhikr: Int = 0, val count: Int = 0, val goal: Int = 0,
    val vibrate: Boolean = true, val theme: Int = 0, val strict: Boolean = false,
    val intervalMs: Long = 1000, val total: Long = 0, val sessions: Int = 0,
    val daily: Map<String, Int> = emptyMap(), val per: Map<String, Int> = emptyMap()
)

private fun parse(s: String?): Map<String, Int> =
    s.orEmpty().split(";").filter { it.contains("=") }.associate {
        val parts = it.split("=")
        parts[0] to (parts[1].toIntOrNull() ?: 0)
    }

private fun enc(m: Map<String, Int>) = m.entries.joinToString(";") { "${it.key}=${it.value}" }

private fun Preferences.toState() = UiState(
    dhikr = this[K.dhikr] ?: 0, count = this[K.count] ?: 0, goal = this[K.goal] ?: 0,
    vibrate = this[K.vibrate] ?: true, theme = this[K.theme] ?: 0, strict = this[K.strict] ?: false,
    intervalMs = this[K.interval] ?: 1000L, total = this[K.total] ?: 0L, sessions = this[K.sessions] ?: 0,
    daily = parse(this[K.daily]), per = parse(this[K.per])
)

class TasbeehViewModel(app: Application) : AndroidViewModel(app) {
    private val store = app.ds
    var state by mutableStateOf(UiState()); private set
    var flash by mutableIntStateOf(0); private set
    private var lastTap = 0L

    init {
        viewModelScope.launch { store.data.collect { state = it.toState() } }
    }

    /** يعيد true إذا احتُسبت الضغطة. */
    fun tap(): Boolean {
        val now = SystemClock.elapsedRealtime()
        val s = state
        val min = if (s.strict) maxOf(1000L, s.intervalMs) else 60L
        if (now - lastTap < min) return false
        lastTap = now
        val nc = if (s.goal > 0 && s.count >= s.goal) 1 else s.count + 1
        val reached = s.goal > 0 && nc == s.goal
        state = s.copy(count = nc)
        if (reached) flash++
        val today = LocalDate.now().toString()
        val dk = s.dhikr.toString()
        viewModelScope.launch {
            store.edit { p ->
                p[K.count] = nc
                p[K.total] = (p[K.total] ?: 0L) + 1
                if (reached) p[K.sessions] = (p[K.sessions] ?: 0) + 1
                val d = parse(p[K.daily]).toMutableMap()
                d[today] = (d[today] ?: 0) + 1
                val kept = d.entries.sortedBy { it.key }.takeLast(60).associate { it.key to it.value }
                p[K.daily] = enc(kept)
                val per = parse(p[K.per]).toMutableMap()
                per[dk] = (per[dk] ?: 0) + 1
                p[K.per] = enc(per)
            }
        }
        return true
    }

    private fun <T> put(key: Preferences.Key<T>, v: T) {
        viewModelScope.launch { store.edit { it[key] = v } }
    }

    fun setDhikr(i: Int) {
        val n = (i + DHIKR.size) % DHIKR.size
        viewModelScope.launch { store.edit { it[K.dhikr] = n; it[K.count] = 0 } }
    }
    fun setGoal(g: Int) = put(K.goal, g)
    fun resetCount() = put(K.count, 0)
    fun setVibrate(v: Boolean) = put(K.vibrate, v)
    fun setTheme(t: Int) = put(K.theme, t)
    fun setStrict(v: Boolean) = put(K.strict, v)
    fun setInterval(ms: Long) = put(K.interval, ms.coerceIn(1000L, 10000L))
    fun clearStats() {
        viewModelScope.launch {
            store.edit { it.remove(K.total); it.remove(K.sessions); it.remove(K.daily); it.remove(K.per) }
        }
    }
}
