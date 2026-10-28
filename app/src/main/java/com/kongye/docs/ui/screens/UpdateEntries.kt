package com.kongye.docs.ui.screens

import android.content.Intent
import android.net.Uri
import android.provider.Settings as AndroidSettings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.kongye.docs.data.AppRelease
import com.kongye.docs.data.AppUpdates
import com.kongye.docs.data.ReleaseNotes
import com.kongye.docs.ui.components.*
import com.kongye.docs.ui.theme.Kongye
import com.kongye.docs.util.Share
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import java.io.File

@Composable
internal fun UpdateEntries(client: AppUpdates = remember { AppUpdates() }, between: @Composable () -> Unit = {}) {
    val context=LocalContext.current
    val scope=rememberCoroutineScope()
    var stage by remember { mutableStateOf("idle") }
    var release by remember { mutableStateOf<AppRelease?>(null) }
    var apk by remember { mutableStateOf<File?>(null) }
    var progress by remember { mutableFloatStateOf(0f) }
    var message by remember { mutableStateOf("") }
    var job by remember { mutableStateOf<Job?>(null) }
    fun error(error: Throwable) { message=error.message ?: "网络连接失败，请稍后重试"; stage="error" }
    fun install() {
        val file=apk ?: return
        val version=release ?: return
        scope.launch {
            try { context.startActivity(client.installIntent(context,file,version)); stage="idle" }
            catch(cancel: CancellationException) { throw cancel }
            catch(failure: Exception) { error(failure) }
        }
    }
    val permission=rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        if(context.packageManager.canRequestPackageInstalls()) install()
        else { stage="ready"; Toast.show("尚未允许安装更新，可稍后继续") }
    }
    fun requestInstall() {
        if(!context.packageManager.canRequestPackageInstalls()) stage="permission" else install()
    }
    fun download() {
        val version=release ?: return
        progress=0f; stage="download"
        job=scope.launch {
            try { apk=client.download(context,version) { progress=it }; stage="ready" }
            catch(cancel: CancellationException) { stage="idle"; throw cancel }
            catch(failure: Exception) { error(failure) }
        }
    }
    SettingsGroup {
        SettingRow("GitHub","Mayleaves/KongyeDocs") { if(!Share.openUrl(context,AppUpdates.REPOSITORY)) Toast.show("没有可用的浏览器") }
        between()
        SettingRow("检查更新",if(stage=="checking") "正在检查…" else "v"+AppUpdates.installed(context).version,last=true) {
            if(stage=="checking" || stage=="download") return@SettingRow
            stage="checking"
            job=scope.launch {
                try {
                    val latest=client.latest(context)
                    val current=AppUpdates.installed(context)
                    release=latest
                    if(AppUpdates.compare(latest.version,current.version)>0) stage="available"
                    else {
                        message=if(AppUpdates.compare(latest.version,current.version)==0) "当前已经是最新版本（v${current.version}）。" else "本机为 v${current.version}，GitHub 最新发布为 v${latest.version}，无需更新。"
                        stage="latest"
                    }
                } catch(cancel: CancellationException) { throw cancel }
                catch(failure: Exception) { error(failure) }
            }
        }
    }
    when(stage) {
        "available" -> ConfirmDialog("发现新版本 v${release?.version}",ReleaseNotes.summary(release?.notes.orEmpty()),"下载更新",onDismiss={stage="idle"}) { download() }
        "download" -> DialogFrame(onDismiss={job?.cancel();stage="idle"}) {
            DialogTitle("正在下载更新")
            Text("${(progress*100).toInt()}%",Modifier.fillMaxWidth().padding(horizontal=24.dp,vertical=10.dp),fontSize=14.sp,color=Kongye.Text)
            LinearProgressIndicator(progress={progress},modifier=Modifier.fillMaxWidth().padding(horizontal=24.dp),color=Kongye.Primary)
            DialogButtons("取消",Kongye.Text2,onCancel=null) { job?.cancel();stage="idle" }
        }
        "ready" -> ConfirmDialog("安装更新","安装包已下载并校验。接下来由系统确认安装，现有文档会保留。","继续安装",onDismiss={stage="idle"}) { requestInstall() }
        "permission" -> ConfirmDialog("允许安装更新","系统需要允许空叶文档安装应用。请在下一页开启此来源的安装权限，再返回继续。","去设置",onDismiss={stage="ready"}) {
            try { permission.launch(Intent(AndroidSettings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,Uri.parse("package:"+context.packageName))) }
            catch(failure: Exception) { error(failure) }
        }
        "latest" -> NoticeDialog("检查更新",message) {stage="idle"}
        "error" -> NoticeDialog("更新失败",message) {stage="idle"}
    }
}
