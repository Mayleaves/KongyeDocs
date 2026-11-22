package com.kongye.docs.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.kongye.docs.data.*
import com.kongye.docs.ui.components.*
import com.kongye.docs.ui.theme.Kongye
import com.kongye.docs.util.formatFullTime
import kotlinx.serialization.json.decodeFromJsonElement

internal object SyncUi { var conflicts by mutableStateOf(false) }

@Composable internal fun CloudSyncSection() {
    val context=LocalContext.current
    val settings by Settings.state.collectAsState()
    val state by CloudSync.status.collectAsState()
    var password by remember { mutableStateOf(false) }
    val s=settings.backup
    SettingsTitle("多设备同步")
    SettingsGroup {
        SwitchRow("同步密码和文档",s.cloudSync) { enabled ->
            if(enabled && s.davUrl.isBlank()) Toast.show("请先填写 WebDAV 服务器")
            else if(enabled && s.vaultPassword.isEmpty()) password=true
            else { Settings.updateBackup { it.copy(cloudSync=enabled) }; if(enabled) CloudSync.request(context); BackupJobService.schedule(context) }
        }
        SettingRow("立即同步",if(state.running) "正在同步…" else null,showChevron=false) {
            if(!s.cloudSync) Toast.show("请先开启多设备同步") else CloudSync.request(context)
        }
        SettingRow("同步冲突",if(state.conflicts.isEmpty()) "无" else "${state.conflicts.size} 项待处理",last=true) { SyncUi.conflicts=true }
    }
    Text(if(state.message.isNotEmpty()) state.message else "两台手机使用同一 WebDAV 账号和备份密码。打开应用自动同步，修改后自动上传；并发修改保留双方版本。文档图片一并同步，正在编辑的文档关闭后更新。",
        Modifier.padding(horizontal=20.dp,vertical=8.dp),fontSize=12.sp,color=Kongye.Sub)
    if(password) InputDialog("同步加密密码","","两台手机填写相同的备份密码",password=true,onDismiss={password=false}) { value ->
        if(value.isNotEmpty()) {
            Settings.updateBackup { it.copy(vaultPassword=value,cloudSync=true) }
            BackupJobService.schedule(context); CloudSync.request(context)
        }
        password=false
    }
}

@Composable internal fun CloudSyncConflicts() {
    if(!SyncUi.conflicts) return
    val context=LocalContext.current
    val state by CloudSync.status.collectAsState()
    var selected by remember { mutableStateOf<SyncConflict?>(null) }
    var preview by remember { mutableStateOf<Pair<SyncConflict,SyncChoice>?>(null) }
    val current=selected?.let { old -> state.conflicts.firstOrNull { it.kind==old.kind && it.id==old.id } }
    BottomSheet({SyncUi.conflicts=false}) { dismiss ->
        Text("同步冲突",Modifier.padding(20.dp),fontSize=18.sp,color=Kongye.Ink)
        Text("双方版本均已保留。选择要采用的版本后，其他设备会同步这次选择。",Modifier.padding(horizontal=20.dp),fontSize=13.sp,color=Kongye.Sub)
        Column(Modifier.heightIn(max=460.dp).verticalScroll(rememberScrollState())) {
            if(state.conflicts.isEmpty()) Text("没有待处理的冲突",Modifier.padding(24.dp),color=Kongye.Sub)
            if(current==null) state.conflicts.forEach { conflict ->
                SettingRow(conflict.name,if(conflict.kind=="vault") "密码 · ${conflict.versions.size} 个版本" else "文档 · ${conflict.versions.size} 个版本") { selected=conflict }
            } else {
                SettingRow("返回冲突列表") { selected=null }
                current.versions.forEach { version ->
                    if(!version.deleted) SettingRow("查看此版本",version.device+" · "+formatFullTime(version.time)) { preview=current to version }
                    SettingRow(if(version.deleted) "采用删除操作" else "采用此版本",version.device+" · "+formatFullTime(version.time)) {
                        CloudSync.resolve(context,current,version.revision); selected=null
                    }
                }
            }
        }
        SettingRow("关闭",showChevron=false,last=true) { dismiss { SyncUi.conflicts=false } }
    }
    preview?.let { (conflict,choice) ->
        val data=remember(conflict.id,choice.revision) { runCatching { CloudSync.preview(context,conflict,choice.revision) }.getOrNull() }
        if(data==null) NoticeDialog("版本不可用","该版本可能已被处理，请刷新同步后再试。") { preview=null }
        else if(conflict.kind=="vault") {
            val entry=CloudSync.json.decodeFromJsonElement<VaultEntry>(data)
            com.kongye.docs.ui.vault.VaultDetailSheet(entry,onEdit={},editable=false) { preview=null }
        } else {
            val doc=CloudSync.json.decodeFromJsonElement<Repository.SyncDocument>(data)
            InfoDialog(doc.item.displayTitle,listOf("设备" to choice.device,"内容" to doc.content?.bodyText().orEmpty())) { preview=null }
        }
    }
}
