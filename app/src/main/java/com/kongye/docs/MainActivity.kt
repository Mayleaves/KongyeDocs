package com.kongye.docs

import android.app.UiModeManager
import android.content.Intent
import android.content.res.Configuration
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.ui.graphics.toArgb
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.kongye.docs.data.AutoBackup
import com.kongye.docs.data.Converter
import com.kongye.docs.data.Repository
import com.kongye.docs.data.Settings
import com.kongye.docs.data.ThemeMode
import com.kongye.docs.ui.AppState
import com.kongye.docs.ui.HomeSub
import com.kongye.docs.ui.KongyeApp
import com.kongye.docs.ui.Tab
import com.kongye.docs.ui.components.Toast
import com.kongye.docs.ui.editor.EditorSession
import com.kongye.docs.ui.theme.Kongye
import com.kongye.docs.ui.theme.KongyeTheme
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.util.Calendar

class MainActivity : ComponentActivity() {
    private var appliedDark: Boolean? = null
    private var appliedNightMode = -1

    override fun onCreate(savedInstanceState: Bundle?) {
        applyTheme()
        super.onCreate(savedInstanceState)
        handleShare(intent)
        setContent { KongyeTheme { KongyeApp() } }
        // Android 12+ 的系统启动画面是纯色的，直接移除，紧接着由 IntroOverlay 播放一笔画动画
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) splashScreen.setOnExitAnimationListener { it.remove() }
        // 设置变化时立即切换；定时模式下每半分钟检查一次是否到了切换时间
        lifecycleScope.launch { Settings.state.collect { applyTheme() } }
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                while (true) {
                    applyTheme()
                    delay(30_000)
                }
            }
        }
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        // 跟随系统时，系统深色模式变了也要切换
        applyTheme()
    }

    /**
     * 根据“深色模式”设置算出当前是否用深色，更新界面颜色和状态栏图标；
     * Android 12+ 还把结果告诉系统（应用级夜间模式），下次冷启动的系统启动画面也会是对应的颜色。
     */
    private fun applyTheme() {
        val st = Settings.value
        val dark = when (st.theme) {
            ThemeMode.LIGHT -> false
            ThemeMode.DARK -> true
            ThemeMode.SYSTEM -> (resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES
            ThemeMode.SCHEDULE -> inSchedule(st.darkFrom, st.darkTo)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val mode = when {
                st.theme == ThemeMode.SYSTEM -> UiModeManager.MODE_NIGHT_AUTO
                dark -> UiModeManager.MODE_NIGHT_YES
                else -> UiModeManager.MODE_NIGHT_NO
            }
            if (mode != appliedNightMode) {
                appliedNightMode = mode
                getSystemService(UiModeManager::class.java)?.setApplicationNightMode(mode)
            }
        }
        if (dark == appliedDark) return
        appliedDark = dark
        Kongye.dark = dark
        val bars = if (dark) SystemBarStyle.dark(Color.TRANSPARENT) else SystemBarStyle.light(Color.TRANSPARENT, Color.TRANSPARENT)
        enableEdgeToEdge(statusBarStyle = bars, navigationBarStyle = bars)
        window.setBackgroundDrawable(ColorDrawable(Kongye.Surface.toArgb()))
    }

    private fun inSchedule(from: Int, to: Int): Boolean {
        val c = Calendar.getInstance()
        val now = c.get(Calendar.HOUR_OF_DAY) * 60 + c.get(Calendar.MINUTE)
        return if (from <= to) now in from until to else now >= from || now < to
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleShare(intent)
    }

    override fun onStart() {
        super.onStart()
        AutoBackup.runIfDue(this)
    }

    override fun onStop() {
        super.onStop()
        EditorSession.current?.save()
        // 离开应用时：如果距上次自动备份已满所选间隔，就在后台备份一次
        AutoBackup.runIfDue(this, includeChanges = true)
    }

    /** 其他应用“分享到空叶文档”的文字，直接新建一篇笔记并打开 */
    private fun handleShare(intent: Intent?) {
        if (intent?.action != Intent.ACTION_SEND || intent.type != "text/plain") return
        val text = intent.getStringExtra(Intent.EXTRA_TEXT)?.trim().orEmpty()
        intent.action = null
        if (text.isEmpty()) return
        val subject = intent.getStringExtra(Intent.EXTRA_SUBJECT)?.trim().orEmpty()
        val title = subject.ifEmpty { text.lineSequence().first().take(30) }
        val item = Repository.createDoc(null, title, Converter.fromPlainText(text))
        AppState.goHome(Tab.HOME)
        AppState.homeSub = HomeSub.RECENT
        AppState.openDoc(item.id)
        Toast.done("已从分享内容创建文档")
    }
}
