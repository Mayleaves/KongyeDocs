package com.moji.notes

import android.app.Application
import com.moji.notes.data.Repository
import com.moji.notes.data.Settings

class MojiApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        Settings.init(this)
        Repository.init(this)
    }
}
