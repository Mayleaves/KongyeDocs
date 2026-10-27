package com.kongye.docs.ui.editor

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.kongye.docs.ui.ShareKind
import com.kongye.docs.ui.components.BottomSheet
import com.kongye.docs.ui.components.HLine
import com.kongye.docs.ui.components.KongyeIcons
import com.kongye.docs.ui.components.KongyeSwitch
import com.kongye.docs.ui.theme.Kongye

/** 文档右上角“…”菜单里的各项操作 */
class DocMenuActions(
    val onToggleStar: () -> Unit,
    val onInfo: () -> Unit,
    val onFind: () -> Unit,
    val onSaveTemplate: () -> Unit,
    val onComments: () -> Unit,
    val onToc: () -> Unit,
    val onLocalVersions: () -> Unit,
    val onShare: (ShareKind) -> Unit,
    val onDelete: () -> Unit,
)

private val iconTint get() = if (Kongye.dark) Color(0xFFBDBDBD) else Color(0xFF555555)

@Composable
fun DocMenuSheet(starred: Boolean, words: Int, actions: DocMenuActions, onDismiss: () -> Unit) {
    BottomSheet(onDismiss, dragToDismiss = false) { dismiss ->
        Column(Modifier.heightIn(max = 680.dp).verticalScroll(rememberScrollState())) {
            MenuRow(
                KongyeIcons.Bookmark, "收藏",
                trailing = {
                    KongyeSwitch(starred, { actions.onToggleStar() })
                },
                onClick = actions.onToggleStar,
            )
            HLine(color = Kongye.Divider)
            MenuRow(KongyeIcons.InfoFilled, "文档信息", trailing = { Text("总字数：$words", fontSize = 15.sp, color = Kongye.Sub) }) {
                dismiss(actions.onInfo)
            }
            HLine(color = Kongye.Divider)
            MenuRow(KongyeIcons.FindReplace, "查找替换") { dismiss(actions.onFind) }
            Band()
            MenuRow(KongyeIcons.TemplateFilled, "保存为模板") { dismiss(actions.onSaveTemplate) }
            HLine(color = Kongye.Divider)
            MenuRow(KongyeIcons.CommentFilled, "查看评论") { dismiss(actions.onComments) }
            HLine(color = Kongye.Divider)
            MenuRow(KongyeIcons.TocFilled, "查看目录") { dismiss(actions.onToc) }
            HLine(color = Kongye.Divider)
            MenuRow(KongyeIcons.SaveFilled, "查看本地版本") { dismiss(actions.onLocalVersions) }
            Band()
            Row(Modifier.fillMaxWidth().padding(vertical = 18.dp)) {
                ShareTile(ShareKind.IMAGE, "分享图片") { dismiss { actions.onShare(ShareKind.IMAGE) } }
                ShareTile(ShareKind.TEXT, "分享文字") { dismiss { actions.onShare(ShareKind.TEXT) } }
                ShareTile(ShareKind.MARKDOWN, "分享 Markdown") { dismiss { actions.onShare(ShareKind.MARKDOWN) } }
            }
            Band()
            Box(
                Modifier.fillMaxWidth().height(56.dp).clickable { dismiss(actions.onDelete) },
                contentAlignment = Alignment.Center,
            ) { Text("删除文档", fontSize = 16.sp, color = Kongye.Danger) }
            Box(Modifier.fillMaxWidth().background(Kongye.Band).navigationBarsPadding().height(12.dp))
        }
    }
}

@Composable
private fun Band() {
    Column {
        HLine(color = Kongye.Line)
        Box(Modifier.fillMaxWidth().height(14.dp).background(Kongye.Band))
        HLine(color = Kongye.Line)
    }
}

@Composable
private fun MenuRow(icon: ImageVector, label: String, trailing: (@Composable () -> Unit)? = null, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().height(56.dp).clickable(onClick = onClick).padding(start = 22.dp, end = 18.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, null, Modifier.size(22.dp), tint = iconTint)
        Spacer(Modifier.width(20.dp))
        Text(label, Modifier.weight(1f), fontSize = 16.sp, color = Kongye.Ink)
        trailing?.invoke()
    }
}

@Composable
private fun RowScope.ShareTile(kind: ShareKind, label: String, onClick: () -> Unit) {
    Column(
        Modifier.weight(1f).clickable(onClick = onClick).padding(vertical = 6.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        // 与条目操作面板一致的单色线性图标，占位高度不变，排版保持原样
        val icon = when (kind) {
            ShareKind.IMAGE -> KongyeIcons.Image
            ShareKind.TEXT -> KongyeIcons.TextT
            ShareKind.MARKDOWN -> KongyeIcons.Markdown
        }
        Box(Modifier.height(56.dp), contentAlignment = Alignment.Center) {
            Icon(icon, null, Modifier.size(32.dp), tint = Kongye.Ink2)
        }
        Spacer(Modifier.height(10.dp))
        Text(label, fontSize = 14.sp, color = Kongye.Ink2)
    }
}