package com.puretasbeeh

import android.view.HapticFeedbackConstants
import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.*
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay

private val LightC = lightColorScheme(
    primary = Color(0xFF718873), onPrimary = Color(0xFFF3F0E8),
    background = Color(0xFFF3F0E8), onBackground = Color(0xFF35483A),
    surface = Color(0xFFDCE4D8), onSurface = Color(0xFF35483A), secondary = Color(0xFFB8B5AA)
)
private val DarkC = darkColorScheme(
    primary = Color(0xFF8FA591), onPrimary = Color(0xFF252925),
    background = Color(0xFF252925), onBackground = Color(0xFFF3F0E8),
    surface = Color(0xFF323832), onSurface = Color(0xFFF3F0E8), secondary = Color(0xFFB8B5AA)
)

private enum class Screen { Home, Stats, Settings }

private val GOALS = listOf(0 to "مفتوح", 33 to "33", 100 to "100", 500 to "500", 1000 to "1000")

@Composable
fun AppRoot(vm: TasbeehViewModel) {
    val s = vm.state
    val dark = when (s.theme) { 1 -> false; 2 -> true; else -> isSystemInDarkTheme() }
    var screen by remember { mutableStateOf(Screen.Home) }
    BackHandler(enabled = screen != Screen.Home) { screen = Screen.Home }
    CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Rtl) {
        MaterialTheme(colorScheme = if (dark) DarkC else LightC) {
            Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                Box(Modifier.fillMaxSize().systemBarsPadding().padding(20.dp)) {
                    when (screen) {
                        Screen.Home -> HomeScreen(vm, { screen = Screen.Stats }, { screen = Screen.Settings })
                        Screen.Stats -> StatsScreen(vm) { screen = Screen.Home }
                        Screen.Settings -> SettingsScreen(vm) { screen = Screen.Home }
                    }
                }
            }
        }
    }
}

@Composable
private fun HomeScreen(vm: TasbeehViewModel, onStats: () -> Unit, onSettings: () -> Unit) {
    val s = vm.state
    val view = LocalView.current
    val cs = MaterialTheme.colorScheme
    var cool by remember { mutableStateOf(false) }
    var tapKey by remember { mutableIntStateOf(0) }
    var confirmReset by remember { mutableStateOf(false) }
    var drag by remember { mutableFloatStateOf(0f) }
    val flashAlpha = remember { Animatable(0f) }
    val initialFlash = remember { vm.flash }

    LaunchedEffect(vm.flash) {
        if (vm.flash != initialFlash) {
            flashAlpha.animateTo(0.25f, tween(200))
            flashAlpha.animateTo(0f, tween(250))
        }
    }
    LaunchedEffect(tapKey) {
        if (tapKey > 0 && s.strict) {
            cool = true
            delay(maxOf(1000L, s.intervalMs))
            cool = false
        }
    }

    Box(Modifier.fillMaxSize().background(cs.primary.copy(alpha = flashAlpha.value))) {
        Column(Modifier.fillMaxSize(), horizontalAlignment = Alignment.CenterHorizontally) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                TextButton(onClick = onStats) { Text("الإحصائيات", color = cs.secondary) }
                TextButton(onClick = onSettings) { Text("الإعدادات", color = cs.secondary) }
            }
            Spacer(Modifier.weight(1f))
            Box(
                Modifier.fillMaxWidth().padding(vertical = 8.dp)
                    .pointerInput(s.dhikr) {
                        detectHorizontalDragGestures(
                            onDragStart = { drag = 0f },
                            onHorizontalDrag = { _, d -> drag += d },
                            onDragEnd = {
                                if (drag < -80f) vm.setDhikr(s.dhikr + 1)
                                else if (drag > 80f) vm.setDhikr(s.dhikr - 1)
                            }
                        )
                    }.semantics { contentDescription = "الذكر الحالي، اسحب لتغييره" },
                contentAlignment = Alignment.Center
            ) {
                Text(DHIKR[s.dhikr], fontSize = 26.sp, fontWeight = FontWeight.Medium,
                    textAlign = TextAlign.Center, color = cs.onBackground)
            }
            Text("${s.count}", fontSize = 88.sp, fontWeight = FontWeight.Light, color = cs.onBackground)
            if (s.goal > 0) {
                LinearProgressIndicator(
                    progress = { (s.count.toFloat() / s.goal).coerceIn(0f, 1f) },
                    modifier = Modifier.width(200.dp).padding(bottom = 8.dp),
                    color = cs.primary, trackColor = cs.surface
                )
            }
            Spacer(Modifier.height(12.dp))
            Box(
                Modifier.size(200.dp).clip(CircleShape)
                    .background(cs.primary).alpha(if (cool) 0.45f else 1f)
                    .semantics { contentDescription = "اضغط بعد إتمام الذكر لاحتساب تسبيحة" }
                    .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) {
                        if (vm.tap()) {
                            if (s.vibrate) view.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
                            tapKey++
                        }
                    }
            )
            Spacer(Modifier.height(20.dp))
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                GOALS.forEach { (g, label) ->
                    FilterChip(selected = s.goal == g, onClick = { vm.setGoal(g) }, label = { Text(label) })
                }
            }
            if (s.strict) {
                val item = LIBRARY[(s.total % LIBRARY.size).toInt()]
                Spacer(Modifier.height(12.dp))
                val left = s.strictLeftMs / 1000
                Text("المتبقي من الجلسة: ${"%d:%02d".format(left / 60, left % 60)}", fontSize = 13.sp, color = cs.secondary)
                Spacer(Modifier.height(6.dp))
                Text(item.text, fontSize = 14.sp, textAlign = TextAlign.Center, color = cs.onBackground)
                Text("${item.source} · ${item.ref} · ${item.grade}", fontSize = 12.sp, color = cs.secondary)
            }
            Spacer(Modifier.weight(1f))
            TextButton(onClick = { confirmReset = true }) { Text("إعادة ضبط العداد", color = cs.secondary) }
        }
    }
    if (confirmReset) {
        AlertDialog(
            onDismissRequest = { confirmReset = false },
            confirmButton = { TextButton({ vm.resetCount(); confirmReset = false }) { Text("نعم") } },
            dismissButton = { TextButton({ confirmReset = false }) { Text("إلغاء") } },
            text = { Text("هل تريد إعادة العداد إلى الصفر؟") }
        )
    }
}

@Composable
private fun Header(title: String, onBack: () -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        TextButton(onClick = onBack) { Text("رجوع", color = MaterialTheme.colorScheme.secondary) }
        Spacer(Modifier.weight(1f))
        Text(title, fontSize = 20.sp, fontWeight = FontWeight.Medium)
        Spacer(Modifier.weight(1f))
        Spacer(Modifier.width(64.dp))
    }
}

@Composable
private fun StatsScreen(vm: TasbeehViewModel, onBack: () -> Unit) {
    val s = vm.state
    var confirm by remember { mutableStateOf(false) }
    val today = java.time.LocalDate.now().toString()
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Header("الإحصائيات", onBack)
        StatRow("تسبيحات اليوم", "${s.daily[today] ?: 0}")
        StatRow("الإجمالي", "${s.total}")
        StatRow("الجلسات المكتملة", "${s.sessions}")
        Text("حسب الذكر", fontWeight = FontWeight.Medium, modifier = Modifier.padding(top = 10.dp))
        DHIKR.forEachIndexed { i, d -> StatRow(d, "${s.per[i.toString()] ?: 0}") }
        Text("آخر الأيام", fontWeight = FontWeight.Medium, modifier = Modifier.padding(top = 10.dp))
        s.daily.entries.sortedByDescending { it.key }.take(7).forEach { StatRow(it.key, "${it.value}") }
        TextButton(onClick = { confirm = true }) { Text("مسح الإحصائيات") }
    }
    if (confirm) {
        AlertDialog(
            onDismissRequest = { confirm = false },
            confirmButton = { TextButton({ vm.clearStats(); confirm = false }) { Text("مسح") } },
            dismissButton = { TextButton({ confirm = false }) { Text("إلغاء") } },
            text = { Text("سيتم مسح كل الإحصائيات ولا يمكن التراجع. هل أنت متأكد؟") }
        )
    }
}

@Composable
private fun StatRow(label: String, value: String) {
    Row(
        Modifier.fillMaxWidth().clip(MaterialTheme.shapes.medium)
            .background(MaterialTheme.colorScheme.surface).padding(14.dp),
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(label); Text(value, fontWeight = FontWeight.Medium)
    }
}

@Composable
private fun SettingsScreen(vm: TasbeehViewModel, onBack: () -> Unit) {
    val s = vm.state
    val sec = MaterialTheme.colorScheme.secondary
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        Header("الإعدادات", onBack)
        SwitchRow("الاهتزاز", s.vibrate) { vm.setVibrate(it) }
        Text("المظهر")
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            listOf(0 to "تلقائي", 1 to "نهاري", 2 to "ليلي").forEach { (t, l) ->
                FilterChip(selected = s.theme == t, onClick = { vm.setTheme(t) }, label = { Text(l) })
            }
        }
        Text("هدف التسبيح")
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            GOALS.forEach { (g, l) ->
                FilterChip(selected = s.goal == g, onClick = { vm.setGoal(g) }, label = { Text(l) })
            }
        }
        SwitchRow("الوضع الصارم", s.strict) { vm.setStrict(it) }
        Text("في الوضع الصارم لا تُقبل ضغطة قبل مرور الفاصل الزمني، ويجب أن تضغط بعد إتمام الذكر. تنتهي الجلسة تلقائياً بانتهاء مدتها، والحد الأقصى $MAX_STRICT_MIN دقائق ولا يمكن تجاوزه.",
            fontSize = 13.sp, color = sec)
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("الفاصل الزمني: ${s.intervalMs / 1000} ث")
            OutlinedButton({ vm.setInterval(s.intervalMs - 1000) }) { Text("−") }
            OutlinedButton({ vm.setInterval(s.intervalMs + 1000) }) { Text("+") }
        }
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("مدة الجلسة: ${s.strictMin} د")
            OutlinedButton({ vm.setStrictMin(s.strictMin - 1) }) { Text("−") }
            OutlinedButton({ vm.setStrictMin(s.strictMin + 1) }) { Text("+") }
        }
        Text("الخصوصية: كل بياناتك محفوظة على جهازك فقط، بلا حسابات ولا إعلانات ولا تتبع.",
            fontSize = 13.sp, color = sec)
    }
}

@Composable
private fun SwitchRow(label: String, v: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween) {
        Text(label); Switch(checked = v, onCheckedChange = onChange)
    }
}
