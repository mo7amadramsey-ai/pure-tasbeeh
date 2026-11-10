package com.puretasbeeh

import android.app.Application
import android.content.Context
import android.os.SystemClock
import androidx.compose.runtime.*
import androidx.datastore.preferences.core.*
import androidx.datastore.preferences.preferencesDataStore
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.time.LocalDate

val Context.ds by preferencesDataStore("pure_tasbeeh")

/** الحد الأقصى المطلق لجلسة الوضع الصارم بالدقائق. قرار المستخدم: لا يمكن تجاوزه أبداً. */
const val MAX_STRICT_MIN = 10

private object K {
    val dhikr = intPreferencesKey("dhikr")
    val count = intPreferencesKey("count")
    val goal = intPreferencesKey("goal")
    val vibrate = booleanPreferencesKey("vibrate")
    val theme = intPreferencesKey("theme")
    val strict = booleanPreferencesKey("strict")
    val strictUntil = longPreferencesKey("strict_until")
    val strictMin = intPreferencesKey("strict_min")
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
    val strictUntil: Long = 0, val strictLeftMs: Long = 0, val strictMin: Int = 5,
    val daily: Map<String, Int> = emptyMap(), val per: Map<String, Int> = emptyMap()
)

private fun parse(s: String?): Map<String, Int> =
    s.orEmpty().split(";").filter { it.contains("=") }.associate {
        val parts = it.split("=")
        parts[0] to (parts[1].toIntOrNull() ?: 0)
    }

private fun enc(m: Map<String, Int>) = m.entries.joinToString(";") { "${it.key}=${it.value}" }

private fun Preferences.toState(): UiState {
    val now = System.currentTimeMillis()
    val until = this[K.strictUntil] ?: 0L
    // سقف صارم عند القراءة: مهما كانت القيمة المخزنة، لا يتجاوز المتبقي الحد الأقصى
    val left = (until - now).coerceIn(0L, MAX_STRICT_MIN * 60_000L)
    return toStateBase(until, left, (this[K.strictMin] ?: 5).coerceIn(1, MAX_STRICT_MIN))
}

private fun Preferences.toStateBase(until: Long, left: Long, minutes: Int) = UiState(
    dhikr = this[K.dhikr] ?: 0, count = this[K.count] ?: 0, goal = this[K.goal] ?: 0,
    vibrate = this[K.vibrate] ?: true, theme = this[K.theme] ?: 0, strict = left > 0L,
    intervalMs = this[K.interval] ?: 1000L, total = this[K.total] ?: 0L, sessions = this[K.sessions] ?: 0,
    strictUntil = until, strictLeftMs = left, strictMin = minutes,
    daily = parse(this[K.daily]), per = parse(this[K.per])
)

class TasbeehViewModel(app: Application) : AndroidViewModel(app) {
    private val store = app.ds
    var state by mutableStateOf(UiState()); private set
    var flash by mutableIntStateOf(0); private set
    private var lastTap = 0L

    init {
        viewModelScope.launch { store.data.collect { state = it.toState() } }
        // مؤقت الوضع الصارم: يحدّث المتبقي كل ثانية، وينهي الجلسة تلقائياً عند انتهاء الوقت
        viewModelScope.launch {
            while (true) {
                delay(1000)
                val s = state
                if (s.strictUntil > 0L) {
                    val left = (s.strictUntil - System.currentTimeMillis()).coerceIn(0L, MAX_STRICT_MIN * 60_000L)
                    if (left <= 0L) {
                        state = s.copy(strict = false, strictLeftMs = 0L, strictUntil = 0L)
                        store.edit { it[K.strictUntil] = 0L }
                    } else if (left != s.strictLeftMs) {
                        state = s.copy(strictLeftMs = left)
                    }
                }
            }
        }
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
    /** تشغيل الوضع الصارم يبدأ جلسة محدودة المدة (1 إلى 10 دقائق)، وإيقافه ينهيها فوراً. */
    fun setStrict(v: Boolean) {
        val minutes = state.strictMin.coerceIn(1, MAX_STRICT_MIN)
        val until = if (v) System.currentTimeMillis() + minutes * 60_000L else 0L
        viewModelScope.launch { store.edit { it[K.strictUntil] = until } }
    }
    fun setStrictMin(m: Int) = put(K.strictMin, m.coerceIn(1, MAX_STRICT_MIN))
    fun setInterval(ms: Long) = put(K.interval, ms.coerceIn(1000L, 10000L))
    fun clearStats() {
        viewModelScope.launch {
            store.edit { it.remove(K.total); it.remove(K.sessions); it.remove(K.daily); it.remove(K.per) }
        }
    }
}
