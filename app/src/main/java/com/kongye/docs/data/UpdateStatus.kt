package com.kongye.docs.data

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/** Shares update availability between the app's foreground check and the About screen. */
internal object UpdateStatus {
    private val checking = Mutex()
    private val defaultClient = AppUpdates()
    private val available = MutableStateFlow<String?>(null)
    val availableVersion = available.asStateFlow()

    suspend fun check(context: Context, client: AppUpdates = defaultClient): AppRelease =
        checking.withLock {
            val current = AppUpdates.installed(context).version
            val cached = withContext(Dispatchers.IO) {
                val metadata = context.getSharedPreferences("app-updates", Context.MODE_PRIVATE)
                    .getString("release", null)
                metadata?.let { runCatching { AppUpdates().parse(it) }.getOrNull() }
            }
            if (cached != null) {
                available.value = cached.version.takeIf { AppUpdates.compare(it, current) > 0 }
            }
            client.latest(context).also { release ->
                available.value = release.version.takeIf { AppUpdates.compare(it, current) > 0 }
            }
        }
}