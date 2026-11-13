package com.kongye.docs.ui.editor

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
        DocumentClipboard.copy(context,editor,cut)
    }
    fun paste() {
        DocumentClipboard.paste(context,editor)
    }
    return this
        .filterTextContextMenuComponents { component ->
            component.key !in listOf(TextContextMenuKeys.SelectAllKey,TextContextMenuKeys.PasteKey) && (!editor.selection.active || component.key !in listOf(TextContextMenuKeys.CopyKey,TextContextMenuKeys.CutKey))
        }
        .appendTextContextMenuComponents {
            if(editor.selection.active) {
                item(ArticleCopy,copyLabel) { copy(false); close() }
                if(!editor.findMode) {
                    item(ArticleCut,cutLabel) { copy(true); close() }
                    item(ArticlePaste,pasteLabel) { paste(); close() }
                }
            } else {
                item(ArticleSelectAll,selectAllLabel) { editor.selectArticle(); close() }
                if(!editor.findMode) item(ArticlePaste,pasteLabel) { paste(); close() }
            }
        }
        .onPreviewKeyEvent { event ->
            if(event.type!=KeyEventType.KeyDown || !event.isCtrlPressed) false
            else when(event.key) {
                Key.A -> { editor.selectArticle(); true }
                Key.C -> if(editor.selection.active) { copy(false); true } else false
                Key.X -> if(editor.selection.active && !editor.findMode) { copy(true); true } else false
                Key.V -> if(!editor.findMode) { paste(); true } else false
                else -> false
            }
        }
}
