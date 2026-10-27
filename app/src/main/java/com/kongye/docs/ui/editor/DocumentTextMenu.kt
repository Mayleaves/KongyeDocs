package com.kongye.docs.ui.editor

import android.content.ClipboardManager
import androidx.compose.foundation.text.contextmenu.builder.item
import androidx.compose.foundation.text.contextmenu.data.TextContextMenuKeys
import androidx.compose.foundation.text.contextmenu.modifier.appendTextContextMenuComponents
import androidx.compose.foundation.text.contextmenu.modifier.filterTextContextMenuComponents
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import com.kongye.docs.util.Share

private object ArticleSelectAll
private object ArticleCopy
private object ArticleCut
private object ArticlePaste

/** Native selection remains paragraph based; these actions operate on the whole editor document. */
@Composable
internal fun Modifier.documentTextMenu(editor: EditorState): Modifier {
    val context=LocalContext.current
    val copyLabel=stringResource(android.R.string.copy)
    val cutLabel=stringResource(android.R.string.cut)
    val pasteLabel=stringResource(android.R.string.paste)
    val selectAllLabel=stringResource(android.R.string.selectAll)
    fun copy(cut: Boolean) {
        Share.copy(context,"文档",editor.selectedArticleText())
        if(cut) editor.selection.replace("") else editor.clearArticleSelection()
    }
    fun paste() {
        val clip=context.getSystemService(ClipboardManager::class.java).primaryClip ?: return
        val text=(0 until clip.itemCount).joinToString("\n") { clip.getItemAt(it).coerceToText(context).toString() }
        editor.selection.replace(text)
    }
    return this
        .filterTextContextMenuComponents { component ->
            component.key !== TextContextMenuKeys.SelectAllKey && (!editor.selection.active || component.key !in listOf(TextContextMenuKeys.CopyKey,TextContextMenuKeys.CutKey,TextContextMenuKeys.PasteKey))
        }
        .appendTextContextMenuComponents {
            if(editor.selection.active) {
                item(ArticleCopy,copyLabel) { copy(false); close() }
                if(!editor.findMode) {
                    item(ArticleCut,cutLabel) { copy(true); close() }
                    item(ArticlePaste,pasteLabel) { paste(); close() }
                }
            } else item(ArticleSelectAll,selectAllLabel) { editor.selectArticle(); close() }
        }
        .onPreviewKeyEvent { event ->
            if(event.type!=KeyEventType.KeyDown || !event.isCtrlPressed) false
            else when(event.key) {
                Key.A -> { editor.selectArticle(); true }
                Key.C -> if(editor.selection.active) { copy(false); true } else false
                Key.X -> if(editor.selection.active && !editor.findMode) { copy(true); true } else false
                Key.V -> if(editor.selection.active && !editor.findMode) { paste(); true } else false
                else -> false
            }
        }
}
