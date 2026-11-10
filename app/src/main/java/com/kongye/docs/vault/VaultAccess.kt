package com.kongye.docs.vault

import android.app.KeyguardManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build

/**
 * 对外解锁状态：只放在内存里。指纹 / 锁屏密码验证后 5 分钟内，键盘和悬浮球的 reveal 直接返回；
 * 锁屏（屏幕关闭）立刻失效，进程被系统回收也随之失效。
 */
object VaultAccess {
    const val UNLOCK_MILLIS = 5 * 60_000L

    @Volatile
    private var until = 0L

    /** 解锁到什么时候（毫秒）；没解锁或已过期、或现在处于锁屏状态时为 0 */
    fun unlockedUntil(context: Context): Long {
        val end = until
        if (end <= System.currentTimeMillis()) return 0L
        if (context.getSystemService(KeyguardManager::class.java)?.isKeyguardLocked == true) { lock(); return 0L }
        return end
    }

    fun isUnlocked(context: Context) = unlockedUntil(context) > 0L

    fun unlock() { until = System.currentTimeMillis() + UNLOCK_MILLIS }

    fun lock() { until = 0L }

    /** 屏幕关闭（锁屏）就清掉解锁状态 */
    val screenOffReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action == Intent.ACTION_SCREEN_OFF) lock()
        }
    }

    fun register(context: Context) {
        val filter = IntentFilter(Intent.ACTION_SCREEN_OFF)
        if (Build.VERSION.SDK_INT >= 33) context.registerReceiver(screenOffReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
        else context.registerReceiver(screenOffReceiver, filter)
    }
}
