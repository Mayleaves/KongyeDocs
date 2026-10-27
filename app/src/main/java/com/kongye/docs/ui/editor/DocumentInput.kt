package com.kongye.docs.ui.editor

import android.content.ClipboardManager
import android.content.Context
import android.os.Handler
import android.os.Looper
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
                DocumentInputConnection(request.createInputConnection(info),context,editor)
            })
        }
    }
    InterceptPlatformTextInput(interceptor,content)
}

/** Bridge both menu actions and keyboards that implement Select All using setSelection. */
internal class DocumentInputConnection(target: InputConnection, private val context: Context, private val editor: EditorState) : InputConnectionWrapper(target,false) {
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
    // Keep surrounding/extracted text and composition local to the focused field;
    // changing their coordinate space can make an IME duplicate the whole article.
}
