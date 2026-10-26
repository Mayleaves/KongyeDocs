package com.moji.notes

import android.app.Application
import com.moji.notes.data.Repository
import com.moji.notes.data.Settings
import com.moji.notes.data.BackupJobService

class MojiApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        Settings.init(this)
        Repository.init(this)
        BackupJobService.schedule(this)
    }
}
