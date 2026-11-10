package com.kongye.docs

import android.app.Application
import com.kongye.docs.data.Repository
import com.kongye.docs.data.Settings
import com.kongye.docs.data.BackupJobService
import com.kongye.docs.vault.VaultAccess
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class KongyeApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        try {
            Settings.init(this)
            Repository.init(this)
            com.kongye.docs.data.VaultStore.dropApps { com.kongye.docs.vault.Browsers.isBrowser(this, it) }
            BackupJobService.schedule(this)
            // 锁屏时清掉密码空间的对外解锁状态
            VaultAccess.register(this)
        } finally {
            ready.countDown()
        }
    }

    companion object {
        private val ready = CountDownLatch(1)

        /** 对外接口在 Application.onCreate 读完设置和密码空间之前可能已被调用：先等一下 */
        fun awaitReady() { ready.await(10, TimeUnit.SECONDS) }
    }
}
