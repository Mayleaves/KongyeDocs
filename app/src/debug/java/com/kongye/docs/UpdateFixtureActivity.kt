package com.kongye.docs

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.material3.Text
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.kongye.docs.data.AppUpdates
import com.kongye.docs.ui.screens.UpdateEntries
import com.kongye.docs.ui.theme.KongyeTheme
import java.io.File

/** Exists in debug builds only; production APKs cannot override the GitHub update source. */
class UpdateFixtureActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if(!packageName.endsWith(".upgradefixture")) { finish(); return }
        val api=intent.getStringExtra("api") ?: run { finish(); return }
        if(!api.startsWith("http://127.0.0.1:")) { finish(); return }
        val sentinel=File(filesDir,"upgrade-sentinel.txt")
        if(!sentinel.exists()) sentinel.writeText("KongyeDocs update fixture: original data")
        val client=AppUpdates(api,true)
        setContent { KongyeTheme { Column(Modifier.fillMaxSize().padding(top=60.dp)) { Text("独立安装升级验证"); UpdateEntries(client) } } }
    }
}
