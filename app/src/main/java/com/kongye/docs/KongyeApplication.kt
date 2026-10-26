package com.kongye.docs

import android.app.Application
import com.kongye.docs.data.Repository
import com.kongye.docs.data.Settings
import com.kongye.docs.data.BackupJobService

class KongyeApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        Settings.init(this)
        Repository.init(this)
        BackupJobService.schedule(this)
    }
}
