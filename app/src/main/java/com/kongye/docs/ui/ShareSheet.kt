package com.kongye.docs.ui

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext
import com.kongye.docs.data.Converter
import com.kongye.docs.data.ExportKind
import com.kongye.docs.data.Repository
import com.kongye.docs.data.plainText
import com.kongye.docs.ui.components.ActionSheet
import com.kongye.docs.ui.components.KongyeIcons
import com.kongye.docs.ui.components.SheetAction
import com.kongye.docs.util.Share
import com.kongye.docs.util.safeFileName
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** 文档的三种分享方式：图片（先预览）、文字、Markdown 文件 */
enum class ShareKind { IMAGE, TEXT, MARKDOWN }

/** 下载记录里的文件名，和石墨一样带上时间：无标题_10_25_135129.png */
fun exportName(title: String, kind: ExportKind): String =
    safeFileName(title) + "_" + SimpleDateFormat("MM_dd_HHmmss", Locale.CHINA).format(Date()) + "." + kind.ext

fun shareDoc(context: Context, docId: String, kind: ShareKind) {
    val title = Repository.get(docId)?.displayTitle ?: "无标题"
    val doc = Repository.loadDoc(docId)
    when (kind) {
        ShareKind.IMAGE -> AppState.push(Screen.ImagePreview(docId))
        ShareKind.TEXT -> {
            val text = title + "\n\n" + doc.plainText()
            Share.text(context, title, text)
            Repository.recordExport(ExportKind.TEXT, exportName(title, ExportKind.TEXT), saved = false, docId = docId, text = text)
        }
        ShareKind.MARKDOWN -> {
            val md = Converter.toMarkdown(title, doc)
            Share.file(context, safeFileName(title) + ".md", "text/markdown", md)
            Repository.recordExport(ExportKind.MARKDOWN, exportName(title, ExportKind.MARKDOWN), saved = false, docId = docId, text = md)
        }
    }
}

/**
 * 分享面板。prepare 会在分享之前调用（编辑器里用来先保存，保证分享的是最新内容）。
 */
@Composable
fun DocShareSheet(docId: String, onDismiss: () -> Unit, prepare: () -> Unit = {}) {
    val context = LocalContext.current
    fun go(kind: ShareKind) {
        prepare()
        shareDoc(context, docId, kind)
    }
    ActionSheet(
        onDismiss = onDismiss,
        actions = listOf(
            SheetAction(KongyeIcons.Image, "分享为图片") { go(ShareKind.IMAGE) },
            SheetAction(KongyeIcons.TextT, "分享为文字") { go(ShareKind.TEXT) },
            SheetAction(KongyeIcons.Markdown, "分享为 Markdown") { go(ShareKind.MARKDOWN) },
        ),
    )
}
