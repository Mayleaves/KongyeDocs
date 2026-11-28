package com.kongye.docs.ui.screens

import android.content.Intent
import android.net.Uri
import android.provider.Settings as AndroidSettings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.kongye.docs.data.AppRelease
import com.kongye.docs.data.AppUpdates
import com.kongye.docs.data.ReleaseNotes
import com.kongye.docs.data.UpdateStatus
import com.kongye.docs.ui.AppState
import com.kongye.docs.ui.Screen
import com.kongye.docs.ui.components.*
import com.kongye.docs.ui.theme.Kongye
import com.kongye.docs.util.sizeText
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import java.io.File

/**
 * 下载进度：与其他弹窗一致的细圆角进度条（灰色底槽、主色进度，没有 Material 进度条末端的圆点），
 * 下方左边是已下载 / 总大小，右边是百分比
 */
@Composable
internal fun DownloadBar(progress: Float, size: Long) {
    val shown by animateFloatAsState(progress.coerceIn(0f, 1f), tween(200), label = "download")
    Column(Modifier.fillMaxWidth().padding(horizontal = 24.dp).padding(top = 6.dp)) {
        Box(Modifier.fillMaxWidth().height(4.dp).clip(RoundedCornerShape(2.dp)).background(Kongye.Line2)) {
            if (shown > 0f) Box(Modifier.fillMaxWidth(shown).fillMaxHeight().clip(RoundedCornerShape(2.dp)).background(Kongye.Primary))
        }
        Spacer(Modifier.height(10.dp))
        Row(Modifier.fillMaxWidth()) {
            Text(
                if (size > 0) "${sizeText((size * progress).toLong()).ifEmpty { "0 KB" }} / ${sizeText(size)}" else "正在下载…",
                Modifier.weight(1f), fontSize = 13.sp, color = Kongye.Text3,
            )
            Text("${(progress * 100).toInt()}%", fontSize = 13.sp, color = Kongye.Text2)
        }
    }
}

/** 强调条目开头的主题词（如“保存网页密码：”），让多行更新说明一眼好扫读 */
private fun formatUpdateHighlight(item: String) = buildAnnotatedString {
    val cnColon = item.indexOf('：')
    val enColon = item.indexOf(": ")
    val splitAt = when {
        cnColon in 1..14 -> cnColon + 1
        enColon in 1..14 -> enColon + 2
        else -> -1
    }
    if (splitAt > 0) {
        withStyle(SpanStyle(color = Kongye.Text, fontWeight = FontWeight.Medium)) {
            append(item.substring(0, splitAt))
        }
        append(item.substring(splitAt))
    } else {
        append(item)
    }
}

@Composable
internal fun UpdateEntries(client: AppUpdates = remember { AppUpdates() }, between: @Composable () -> Unit = {}) {
    val context=LocalContext.current
    val scope=rememberCoroutineScope()
    val availableVersion by UpdateStatus.availableVersion.collectAsState()
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
        between()
        SettingRow(
            "检查更新",
            when {
                stage == "checking" -> "正在检查…"
                availableVersion != null -> "新版本 v$availableVersion"
                else -> "v" + AppUpdates.installed(context).version
            },
            valueColor = if (availableVersion != null && stage != "checking") Kongye.Primary else Kongye.Sub,
        ) {
            if(stage=="checking" || stage=="download") return@SettingRow
            stage="checking"
            job=scope.launch {
                try {
                    val latest=UpdateStatus.check(context, client)
                    val current=AppUpdates.installed(context)
                    release=latest
                    if(AppUpdates.compare(latest.version,current.version)>0) stage="available"
                    else {
                        message=if(AppUpdates.compare(latest.version,current.version)==0) "已是最新版本 · v${current.version}" else "本机为 v${current.version}，GitHub 最新发布为 v${latest.version}，无需更新。"
                        stage="latest"
                    }
                } catch(cancel: CancellationException) { throw cancel }
                catch(failure: Exception) { error(failure) }
            }
        }
        SettingRow("更新历史", "", last = true) { AppState.push(Screen.UpdateHistory) }
    }
    when(stage) {
        "available","latest" -> DialogFrame(onDismiss={stage="idle"}) {
            val isAvailable = stage == "available"
            if (isAvailable) {
                val installedVersion = remember(context) { AppUpdates.installed(context).version }
                val sizeLabel = sizeText(release?.size ?: 0L)
                val meta = if (sizeLabel.isNotEmpty()) "当前 v$installedVersion · $sizeLabel" else "当前 v$installedVersion"
                val summary = remember(release?.notes) { ReleaseNotes.summary(release?.notes.orEmpty()) }
                val lines = remember(summary) { summary.lines().filter { it.isNotBlank() } }
                Text(
                    "发现新版本 v${release?.version}",
                    Modifier.fillMaxWidth().padding(start = 20.dp, end = 20.dp, top = 22.dp),
                    fontSize = 18.sp, fontWeight = FontWeight.SemiBold, textAlign = TextAlign.Center, color = Kongye.Text,
                )
                Text(
                    meta,
                    Modifier.fillMaxWidth().padding(start = 20.dp, end = 20.dp, top = 4.dp),
                    fontSize = 12.sp, textAlign = TextAlign.Center, color = Kongye.Text3,
                )
                Spacer(Modifier.height(14.dp))
                Column(
                    Modifier.fillMaxWidth()
                        .padding(horizontal = 20.dp)
                        .clip(RoundedCornerShape(10.dp))
                        .background(Kongye.Card)
                        .semantics { contentDescription = summary }
                        .padding(horizontal = 14.dp, vertical = 12.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    lines.forEach { raw ->
                        if (raw.startsWith("• ")) {
                            val item = raw.removePrefix("• ")
                            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Top) {
                                Box(
                                    Modifier.padding(top = 8.dp).size(4.5.dp)
                                        .clip(CircleShape).background(Kongye.Primary.copy(alpha = 0.75f)),
                                )
                                Spacer(Modifier.width(9.dp))
                                Text(
                                    formatUpdateHighlight(item),
                                    Modifier.weight(1f),
                                    fontSize = 13.5.sp, lineHeight = 20.5.sp, color = Kongye.Text2,
                                )
                            }
                        } else {
                            Text(raw, fontSize = 13.5.sp, lineHeight = 20.5.sp, color = Kongye.Text2)
                        }
                    }
                }
            } else {
                DialogTitle("检查更新")
                Text(
                    message,
                    Modifier.fillMaxWidth().padding(horizontal = 24.dp),
                    fontSize = 14.sp, color = Kongye.Text2, textAlign = TextAlign.Center, lineHeight = 21.sp,
                )
            }
            Row(
                Modifier.align(Alignment.CenterHorizontally)
                    .padding(top = 10.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .clickable {
                        stage = "idle"
                        AppState.push(Screen.UpdateHistory)
                    }
                    .padding(horizontal = 12.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("查看更新历史", color = Kongye.Link, fontSize = 13.sp)
                Spacer(Modifier.width(2.dp))
                Icon(KongyeIcons.ChevronRight, null, Modifier.size(13.dp), tint = Kongye.Link)
            }
            DialogButtons(
                if (isAvailable) "下载更新" else "知道了",
                Kongye.Primary,
                onCancel = if (isAvailable) ({ stage = "idle" }) else null,
                topSpacing = 10.dp,
            ) {
                if (isAvailable) download() else stage = "idle"
            }
        }
        "download" -> DialogFrame(onDismiss={job?.cancel();stage="idle"}) {
            DialogTitle("正在下载 v${release?.version}")
            DownloadBar(progress,release?.size ?: 0L)
            DialogButtons("取消",Kongye.Text2,onCancel=null) { job?.cancel();stage="idle" }
        }
        "ready" -> ConfirmDialog("安装更新","安装包已下载并校验。接下来由系统确认安装，现有文档会保留。","继续安装",onDismiss={stage="idle"}) { requestInstall() }
        "permission" -> ConfirmDialog("允许安装更新","系统需要允许空叶文档安装应用。请在下一页开启此来源的安装权限，再返回继续。","去设置",onDismiss={stage="ready"}) {
            try { permission.launch(Intent(AndroidSettings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,Uri.parse("package:"+context.packageName))) }
            catch(failure: Exception) { error(failure) }
        }
        "error" -> NoticeDialog("更新失败",message) {stage="idle"}
    }
}
