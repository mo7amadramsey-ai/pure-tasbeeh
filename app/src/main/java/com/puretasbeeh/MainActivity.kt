package com.puretasbeeh

import android.app.NotificationManager
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.lifecycle.viewmodel.compose.viewModel

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            val vm: TasbeehViewModel = viewModel()
            AppRoot(vm)
        }
    }

    /**
     * يطبّق أو يرفع حماية جلسة التركيز اليدوية (بلا تثبيت للشاشة بطلب المستخدم):
     * 1) إبقاء الشاشة مضاءة،
     * 2) عدم الإزعاج بوضع «الأولوية» إن مُنح الإذن (المكالمات حسب إعدادات النظام).
     * لا يعطّل شيئاً من إعدادات الجهاز، ولا يمنع الطوارئ.
     */
    fun applyFocus(on: Boolean) {
        val nm = getSystemService(NotificationManager::class.java)
        val prefs = getSharedPreferences("focus", MODE_PRIVATE)
        if (on) {
            window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            if (nm.isNotificationPolicyAccessGranted) {
                // نحفظ الوضع السابق في التخزين حتى نستعيده حتى لو أُغلق التطبيق
                if (!prefs.contains("saved")) {
                    prefs.edit().putInt("saved", nm.currentInterruptionFilter).apply()
                }
                nm.setInterruptionFilter(NotificationManager.INTERRUPTION_FILTER_PRIORITY)
            }
        } else {
            window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            if (prefs.contains("saved")) {
                val old = prefs.getInt("saved", NotificationManager.INTERRUPTION_FILTER_ALL)
                if (nm.isNotificationPolicyAccessGranted) nm.setInterruptionFilter(old)
                prefs.edit().remove("saved").apply()
            }
        }
    }
}
