package com.puretasbeeh

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.AudioManager
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import android.os.PowerManager
import android.os.SystemClock
import android.provider.Settings
import android.view.HapticFeedbackConstants
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelChildren
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking

/** حالة مشتركة بين الخدمة ونافذة المقاطعة. */
object Interrupt {
    /** وقت (بالميلي ثانية، ساعة الجدار) ينتهي عنده سقف المقاطعة الحالية؛ 0 = لا توجد مقاطعة. */
    @Volatile var pendingUntil = 0L
    @Volatile var visible = false
}

/**
 * خدمة تعمل في الخلفية: كل N دقيقة تفتح نافذة التسبيح فوق أي تطبيق.
 * قيود الأمان: لا تقاطع أثناء المكالمات أو والشاشة مطفأة، ولا تتجاوز المقاطعة 10 دقائق.
 */
class InterruptService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startAsForeground()
        scope.coroutineContext.cancelChildren()
        scope.launch { loop() }
        return START_STICKY
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    private fun startAsForeground() {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel("auto", "المقاطعة التلقائية", NotificationManager.IMPORTANCE_LOW)
        )
        val n = Notification.Builder(this, "auto")
            .setSmallIcon(android.R.drawable.ic_popup_reminder)
            .setContentTitle("التسبيح فقط")
            .setContentText("المقاطعة التلقائية مفعّلة")
            .setOngoing(true)
            .build()
        if (Build.VERSION.SDK_INT >= 34) {
            startForeground(1, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else {
            startForeground(1, n)
        }
    }

    private suspend fun loop() {
        val pm = getSystemService(PowerManager::class.java)
        val am = getSystemService(AudioManager::class.java)
        var nextAt = SystemClock.elapsedRealtime() + everyMs(ds.data.first())
        while (true) {
            delay(15_000)
            val cfg = ds.data.first()
            if (cfg[K.auto] != true) { stopSelf(); return }
            val now = SystemClock.elapsedRealtime()
            val wall = System.currentTimeMillis()
            // لا نقاطع إلا إذا: الإذن ممنوح، الشاشة مضاءة، لا مكالمة جارية، والنافذة غير ظاهرة
            val canShow = Settings.canDrawOverlays(this) && pm.isInteractive &&
                am.mode == AudioManager.MODE_NORMAL && !Interrupt.visible
            if (Interrupt.pendingUntil > wall) {
                // مقاطعة لم تُنجَز: أعد إظهارها إن غادرها المستخدم، حتى ينتهي السقف
                if (canShow) show()
            } else if (now >= nextAt && canShow) {
                Interrupt.pendingUntil = wall + MAX_STRICT_MIN * 60_000L
                show()
                nextAt = now + everyMs(cfg)
            }
        }
    }

    private fun everyMs(p: androidx.datastore.preferences.core.Preferences): Long =
        (p[K.every] ?: 30).coerceIn(5, 240) * 60_000L

    private fun show() {
        startActivity(Intent(this, InterruptActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }
}

/** يعيد تشغيل الخدمة بعد إعادة تشغيل الهاتف إن كانت المقاطعة التلقائية مفعّلة. */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED) return
        val on = runBlocking { context.ds.data.first()[K.auto] ?: false }
        if (on) context.startForegroundService(Intent(context, InterruptService::class.java))
    }
}

/** نافذة المقاطعة: تغطي الشاشة حتى يُنجز المستخدم العدد المطلوب، ثم تُغلق فيعود لما كان يفعله. */
class InterruptActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        // سقف مطلق: إن فُتحت دون مقاطعة معلّقة نعطيها 10 دقائق من الآن كحد أقصى
        val now = System.currentTimeMillis()
        if (Interrupt.pendingUntil <= now) Interrupt.pendingUntil = now + MAX_STRICT_MIN * 60_000L
        setContent {
            val vm: TasbeehViewModel = viewModel()
            InterruptScreen(vm) {
                Interrupt.pendingUntil = 0L
                finish()
            }
        }
    }

    override fun onStart() { super.onStart(); Interrupt.visible = true }
    override fun onStop() { super.onStop(); Interrupt.visible = false }
}

@Composable
private fun InterruptScreen(vm: TasbeehViewModel, onDone: () -> Unit) {
    val s = vm.state
    val dark = when (s.theme) { 1 -> false; 2 -> true; else -> isSystemInDarkTheme() }
    val view = LocalView.current
    val scope = rememberCoroutineScope()
    var n by remember { mutableIntStateOf(0) }
    var lastTap by remember { mutableLongStateOf(0L) }
    var cool by remember { mutableStateOf(false) }
    var leftSec by remember { mutableLongStateOf(MAX_STRICT_MIN * 60L) }

    // لا رجوع قبل الإتمام؛ السقف الزمني (10 دقائق) يضمن أن النافذة تُغلق دائماً
    BackHandler(true) {}
    LaunchedEffect(Unit) {
        while (true) {
            val left = (Interrupt.pendingUntil - System.currentTimeMillis()).coerceIn(0L, MAX_STRICT_MIN * 60_000L)
            leftSec = left / 1000
            if (left <= 0L) { onDone(); break }
            delay(1000)
        }
    }

    CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Rtl) {
        MaterialTheme(colorScheme = if (dark) DarkC else LightC) {
            val cs = MaterialTheme.colorScheme
            Surface(Modifier.fillMaxSize(), color = cs.background) {
                Column(
                    Modifier.fillMaxSize().systemBarsPadding().padding(24.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Spacer(Modifier.weight(1f))
                    Text("أنجز هذا الذكر ثم أكمل ما كنت تفعله", fontSize = 16.sp,
                        color = cs.secondary, textAlign = TextAlign.Center)
                    Spacer(Modifier.height(20.dp))
                    Text(DHIKR[s.dhikr], fontSize = 28.sp, fontWeight = FontWeight.Medium,
                        textAlign = TextAlign.Center, color = cs.onBackground)
                    Text("$n / ${s.taskCount}", fontSize = 64.sp, fontWeight = FontWeight.Light,
                        color = cs.onBackground)
                    Spacer(Modifier.height(24.dp))
                    Box(
                        Modifier.size(200.dp).clip(CircleShape)
                            .background(cs.primary).alpha(if (cool) 0.45f else 1f)
                            .semantics { contentDescription = "اضغط بعد إتمام الذكر لاحتساب تسبيحة" }
                            .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) {
                                val now = SystemClock.elapsedRealtime()
                                val gap = maxOf(1000L, s.intervalMs)
                                if (now - lastTap >= gap) {
                                    lastTap = now
                                    n++
                                    vm.record()
                                    if (s.vibrate) view.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
                                    if (n >= s.taskCount) {
                                        vm.completeSession()
                                        onDone()
                                    } else {
                                        cool = true
                                        scope.launch { delay(gap); cool = false }
                                    }
                                }
                            }
                    )
                    Spacer(Modifier.height(20.dp))
                    Text("المتبقي: ${"%d:%02d".format(leftSec / 60, leftSec % 60)}", fontSize = 13.sp, color = cs.secondary)
                    Spacer(Modifier.weight(1f))
                }
            }
        }
    }
}
