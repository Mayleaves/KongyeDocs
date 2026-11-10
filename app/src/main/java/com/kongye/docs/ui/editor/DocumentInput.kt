package com.kongye.docs.ui.editor

import android.content.ClipboardManager
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.Bundle
import android.view.KeyEvent
import android.view.inputmethod.ExtractedTextRequest
import android.view.inputmethod.InputConnection
import android.view.inputmethod.InputConnectionWrapper
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.InterceptPlatformTextInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.PlatformTextInputInterceptor
import androidx.compose.ui.platform.PlatformTextInputMethodRequest
import com.kongye.docs.util.Share

@Composable
internal fun DocumentInput(editor: EditorState, content: @Composable () -> Unit) {
    val context=LocalContext.current
    val interceptor=remember(editor,context) {
        PlatformTextInputInterceptor { request,next ->
            next.startInputMethod(PlatformTextInputMethodRequest { info ->
                val target = request.createInputConnection(info)
                // The body field marks its own session. Text may still be catching
                // up after undo/focus changes, so comparing extracted text here can
                // incorrectly disable document commands for an entire IME session.
                val documentInput = info.extras?.getInt("com.kongye.input.EDITOR_PROTOCOL") == 1
                DocumentInputConnection(target,context,editor,documentInput)
            })
        }
    }
    InterceptPlatformTextInput(interceptor,content)
}

/** Identify body sessions without granting document commands to titles or dialogs. */
@Composable
internal fun DocumentBodyInput(content: @Composable () -> Unit) {
    val interceptor = remember {
        PlatformTextInputInterceptor { request, next ->
            next.startInputMethod(PlatformTextInputMethodRequest { info ->
                request.createInputConnection(info).also {
                    val extras = info.extras ?: Bundle().also { info.extras = it }
                    extras.putInt("com.kongye.input.EDITOR_PROTOCOL", 1)
                }
            })
        }
    }
    InterceptPlatformTextInput(interceptor, content)
}

/** Bridge both menu actions and keyboards that implement Select All using setSelection. */
internal class DocumentInputConnection(target: InputConnection, private val context: Context, private val editor: EditorState, private val documentInput: Boolean = false) : InputConnectionWrapper(target,false) {
    private fun bodyInput() = documentInput && editor.focused?.type?.isText == true && !editor.titleFocused &&
        editor.focusedCell == null && editor.commentDraft == null && editor.commentView == null && !editor.findMode
    private val handler=Handler(Looper.getMainLooper())
    private fun <T> main(action: () -> T): T {
        if(Looper.myLooper()==Looper.getMainLooper()) return action()
        val result=java.util.concurrent.FutureTask(action)
        handler.post(result)
        return result.get(2,java.util.concurrent.TimeUnit.SECONDS)
    }
    private fun selectBody(): Boolean = main {
        super.finishComposingText()
        val focused=editor.focusedId
        val cell=editor.focusedCell
        editor.selectArticle()
        editor.focusedId=focused
        editor.focusedCell=cell
        editor.selection.fromKeyboard=true
        true
    }
    override fun performContextMenuAction(id: Int): Boolean {
        if(id==android.R.id.selectAll) return selectBody()
        return main {
            when {
                id==android.R.id.undo && bodyInput() -> { editor.undo(); true }
                id==android.R.id.redo && bodyInput() -> { editor.redo(); true }
                id==android.R.id.copy && editor.selection.active -> { Share.copy(context,"文档",editor.selection.text()); editor.selection.clear(); true }
                id==android.R.id.cut && editor.selection.active -> { Share.copy(context,"文档",editor.selection.text()); editor.selection.replace(""); true }
                id==android.R.id.paste && editor.selection.active -> {
                    val clip=context.getSystemService(ClipboardManager::class.java).primaryClip
                    if(clip!=null) editor.selection.replace((0 until clip.itemCount).joinToString("\n") { clip.getItemAt(it).coerceToText(context).toString() })
                    true
                }
                else -> super.performContextMenuAction(id)
            }
        }
    }
    override fun performPrivateCommand(action: String?, data: Bundle?): Boolean = main {
        if (!bodyInput()) return@main super.performPrivateCommand(action,data)
        when (action) {
            "com.kongye.input.UNDO" -> { super.finishComposingText(); editor.undo(); true }
            "com.kongye.input.REDO" -> { super.finishComposingText(); editor.redo(); true }
            "com.kongye.input.SELECTION_START" -> editor.keyboardSelection.start()
            "com.kongye.input.SELECTION_END" -> { editor.keyboardSelection.end(); true }
            "com.kongye.input.SELECT_MOVE" -> editor.keyboardSelection.move(data?.getString("direction").orEmpty())
            else -> super.performPrivateCommand(action,data)
        }
    }
    override fun setSelection(start: Int,end: Int): Boolean = main {
        // Some IMEs read the active paragraph and then select 0..length instead of
        // sending android.R.id.selectAll. Expand that whole-field command to the body.
        val local=super.getExtractedText(ExtractedTextRequest(),0)?.text?.toString()
        val low=minOf(start,end); val high=maxOf(start,end)
        val entire=local!=null && high>low && low in 0..(if(local.startsWith(ZWS)) 1 else 0) &&
            high >= local.length - (if(low==0 && local.startsWith(ZWS)) 1 else 0)
        if(entire) selectBody()
        else {
            if(editor.selection.active) editor.selection.clear()
            super.setSelection(start,end)
        }
    }
    override fun sendKeyEvent(event: KeyEvent): Boolean {
        // Apply direction keys against the latest document caret before returning.
        // Forwarding to the old paragraph queues events across an input-session
        // restart, and rapid repeats can read the same unrecomposed field value.
        if (main { editor.cursorNavigation.handle(event) }) return true
        val action=when {
            event.isCtrlPressed && event.keyCode==KeyEvent.KEYCODE_A -> android.R.id.selectAll
            event.keyCode==KeyEvent.KEYCODE_COPY || event.isCtrlPressed && event.keyCode==KeyEvent.KEYCODE_C -> android.R.id.copy
            event.keyCode==KeyEvent.KEYCODE_CUT || event.isCtrlPressed && event.keyCode==KeyEvent.KEYCODE_X -> android.R.id.cut
            event.keyCode==KeyEvent.KEYCODE_PASTE || event.isCtrlPressed && event.keyCode==KeyEvent.KEYCODE_V -> android.R.id.paste
            else -> null
        }
        if(action!=null) return event.action!=KeyEvent.ACTION_DOWN || performContextMenuAction(action)
        return super.sendKeyEvent(event)
    }
    override fun getSelectedText(flags: Int): CharSequence? = main {
        if(editor.selection.active) editor.selection.text() else super.getSelectedText(flags)
    }
    override fun deleteSurroundingText(beforeLength: Int,afterLength: Int): Boolean = main {
        if(editor.selection.active) { editor.selection.replace(""); true }
        else super.deleteSurroundingText(beforeLength,afterLength)
    }
    override fun deleteSurroundingTextInCodePoints(beforeLength: Int,afterLength: Int): Boolean = main {
        if(editor.selection.active) { editor.selection.replace(""); true }
        else super.deleteSurroundingTextInCodePoints(beforeLength,afterLength)
    }
    // Extracted text, offsets and composition stay local. At a paragraph boundary,
    // expose only its separator: some IMEs check one character before sending Down.
    // Returning the following paragraph's text would risk duplicating body content.
    override fun getTextAfterCursor(n: Int, flags: Int): CharSequence? = main {
        val local = super.getTextAfterCursor(n, flags)
        if (n <= 0 || local == null || local.isNotEmpty() || editor.titleFocused ||
            editor.focusedCell != null || editor.commentDraft != null || editor.commentView != null ||
            editor.findMode || editor.selection.active) return@main local
        val b = editor.focused?.takeIf { it.type.isText } ?: return@main local
        if (!b.value.selection.collapsed || b.value.selection.end != b.value.text.length) return@main local
        // Confirm this connection belongs to that body paragraph, rather than a
        // dialog field or an input session still waiting for a focus handoff.
        val extracted = super.getExtractedText(ExtractedTextRequest(), 0) ?: return@main local
        if (extracted.text?.toString() != b.value.text || extracted.selectionStart != b.value.text.length ||
            extracted.selectionEnd != b.value.text.length) return@main local
        if ((editor.blocks.indexOf(b) + 1 until editor.blocks.size).any { editor.blocks[it].type.isText }) "\n" else local
    }
}
