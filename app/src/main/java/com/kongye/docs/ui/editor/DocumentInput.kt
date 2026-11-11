package com.kongye.docs.ui.editor

import android.content.ClipboardManager
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.Bundle
import android.view.KeyEvent
import android.view.inputmethod.ExtractedTextRequest
import android.view.inputmethod.ExtractedText
import android.view.inputmethod.SurroundingText
import android.view.inputmethod.TextAttribute
import android.text.TextUtils
import android.view.inputmethod.InputConnection
import android.view.inputmethod.InputConnectionWrapper
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.platform.InterceptPlatformTextInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.core.view.inputmethod.EditorInfoCompat
import kotlinx.coroutines.flow.collectLatest
import androidx.compose.ui.platform.PlatformTextInputInterceptor
import androidx.compose.ui.platform.PlatformTextInputMethodRequest
import com.kongye.docs.util.Share

@Composable
internal fun DocumentInput(editor: EditorState, content: @Composable () -> Unit) {
    val context=LocalContext.current
    val view=LocalView.current
    val ime=remember(editor,view) { DocumentIme(editor,view) }
    LaunchedEffect(ime) {
        snapshotFlow { ime.snapshot() to editor.inputEpoch }.collectLatest { (_,epoch) ->
            // Paragraph fields can report their local offsets during recomposition.
            // Publish the document offsets after that frame, including monitor data.
            withFrameNanos { }
            view.post { if(editor.inputEpoch==epoch) ime.nativeEpoch=epoch; ime.notifyIme() }
        }
    }
    DisposableEffect(ime) { onDispose { ime.active=false } }
    val interceptor=remember(editor,context,ime) {
        PlatformTextInputInterceptor { request,next ->
            next.startInputMethod(PlatformTextInputMethodRequest { info ->
                val target = request.createInputConnection(info)
                // The body field marks its own session. Text may still be catching
                // up after undo/focus changes, so comparing extracted text here can
                // incorrectly disable document commands for an entire IME session.
                val documentInput = info.extras?.getInt("com.kongye.input.EDITOR_PROTOCOL") == 1
                ime.active=documentInput
                if (documentInput) {
                    val state=ime.snapshot()
                    info.initialSelStart=state.start; info.initialSelEnd=state.end
                    EditorInfoCompat.setInitialSurroundingText(info,state.text)
                    view.post { ime.notifyIme() }
                }
                DocumentInputConnection(target,context,editor,documentInput,ime,info.extras?.getString("com.kongye.input.BLOCK"))
            })
        }
    }
    InterceptPlatformTextInput(interceptor,content)
}

/** Identify body sessions without granting document commands to titles or dialogs. */
@Composable
internal fun DocumentBodyInput(blockId: String,content: @Composable () -> Unit) {
    val interceptor = remember(blockId) {
        PlatformTextInputInterceptor { request, next ->
            next.startInputMethod(PlatformTextInputMethodRequest { info ->
                request.createInputConnection(info).also {
                    val extras = info.extras ?: Bundle().also { info.extras = it }
                    extras.putInt("com.kongye.input.EDITOR_PROTOCOL", 1)
                    extras.putString("com.kongye.input.BLOCK",blockId)
                }
            })
        }
    }
    InterceptPlatformTextInput(interceptor, content)
}

/** Bridge both menu actions and keyboards that implement Select All using setSelection. */
internal class DocumentInputConnection(target: InputConnection, private val context: Context, private val editor: EditorState, private val documentInput: Boolean = false, private val ime: DocumentIme? = null, private val blockId: String? = null) : InputConnectionWrapper(target,false) {
    private fun bodyInput() = documentInput && editor.bodyInputActive && !editor.titleFocused &&
        editor.focusedCell == null && editor.commentDraft == null && editor.commentView == null && !editor.findMode
    private val handler=Handler(Looper.getMainLooper())
    private fun nativeReady(): Boolean {
        if(ime?.nativeEpoch!=editor.inputEpoch || editor.selection.active || editor.focusedId!=blockId || editor.pendingFocus!=null) return false
        val value=editor.focused?.value ?: return false
        val native=super.getExtractedText(ExtractedTextRequest(),0) ?: return false
        return native.text?.toString()==value.text && native.selectionStart==value.selection.start && native.selectionEnd==value.selection.end
    }
    private fun <T> main(action: () -> T): T {
        if(Looper.myLooper()==Looper.getMainLooper()) return action()
        val result=java.util.concurrent.FutureTask(action)
        handler.post(result)
        return result.get(2,java.util.concurrent.TimeUnit.SECONDS)
    }
    private fun selectBody(): Boolean = main {
        if (bodyInput()) ime?.finishComposition() else super.finishComposingText()
        val focused=editor.focusedId
        val cell=editor.focusedCell
        editor.selectArticle()
        editor.focusedId=focused
        editor.focusedCell=cell
        editor.selection.fromKeyboard=true
        ime?.notifyIme()
        true
    }
    override fun performContextMenuAction(id: Int): Boolean {
        if(id==android.R.id.selectAll && main { bodyInput() }) return selectBody()
        return main {
            if(!bodyInput()) return@main super.performContextMenuAction(id)
            val result=when {
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
            if(bodyInput()) ime?.notifyIme()
            result
        }
    }
    override fun performPrivateCommand(action: String?, data: Bundle?): Boolean = main {
        if (!bodyInput()) return@main super.performPrivateCommand(action,data)
        val result=when (action) {
            "com.kongye.input.UNDO" -> { ime?.finishComposition(); editor.undo(); true }
            "com.kongye.input.REDO" -> { ime?.finishComposition(); editor.redo(); true }
            "com.kongye.input.SELECTION_START" -> editor.keyboardSelection.start()
            "com.kongye.input.SELECTION_END" -> { editor.keyboardSelection.end(); true }
            "com.kongye.input.SELECT_MOVE" -> editor.keyboardSelection.move(data?.getString("direction").orEmpty()).also { ime?.selectionMoved() }
            else -> super.performPrivateCommand(action,data)
        }
        ime?.notifyIme()
        result
    }
    override fun setSelection(start: Int,end: Int): Boolean = main {
        if(bodyInput() && ime!=null) return@main ime.select(start,end).also { ime.notifyIme() }
        super.setSelection(start,end)
    }
    override fun sendKeyEvent(event: KeyEvent): Boolean {
        if(!main { bodyInput() }) return super.sendKeyEvent(event)
        if(main { editor.imageCaret!=null } && event.keyCode==KeyEvent.KEYCODE_ENTER) {
            if(event.action==KeyEvent.ACTION_DOWN) main { ime?.insert("\n",1,false); ime?.notifyIme() }
            return true
        }
        if(main { bodyInput() }) {
            if(event.keyCode in listOf(KeyEvent.KEYCODE_SHIFT_LEFT,KeyEvent.KEYCODE_SHIFT_RIGHT)) {
                ime?.shiftHeld=event.action==KeyEvent.ACTION_DOWN
                return true
            }
            val direction=when(event.keyCode) {
                KeyEvent.KEYCODE_DPAD_LEFT -> "left"
                KeyEvent.KEYCODE_DPAD_RIGHT -> "right"
                KeyEvent.KEYCODE_DPAD_UP -> "up"
                KeyEvent.KEYCODE_DPAD_DOWN -> "down"
                else -> null
            }
            if(direction!=null && (event.isShiftPressed || ime?.shiftHeld==true) && !event.isCtrlPressed && !event.isAltPressed) {
                if(event.action==KeyEvent.ACTION_DOWN) main {
                    ime?.finishComposition()
                    if(!editor.keyboardSelection.engaged) ime?.beginShiftSelection()
                    editor.keyboardSelection.move(direction,chooseHandle=false)
                    ime?.selectionMoved(); ime?.notifyIme()
                }
                return true
            }
            val history=when {
                event.isCtrlPressed && event.keyCode==KeyEvent.KEYCODE_Z && !event.isShiftPressed -> android.R.id.undo
                event.isCtrlPressed && (event.keyCode==KeyEvent.KEYCODE_Y || event.keyCode==KeyEvent.KEYCODE_Z && event.isShiftPressed) -> android.R.id.redo
                else -> null
            }
            if(history!=null) return event.action!=KeyEvent.ACTION_DOWN || performContextMenuAction(history)
        }
        // Apply direction keys against the latest document caret before returning.
        // Forwarding to the old paragraph queues events across an input-session
        // restart, and rapid repeats can read the same unrecomposed field value.
        if (main { editor.cursorNavigation.handle(event).also { if(it) ime?.notifyIme() } }) return true
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
        if(bodyInput() && editor.selection.active) editor.selection.text()
        else if(bodyInput() && ime!=null) ime.snapshot().let { state ->
            if(state.start==state.end) null else state.text.substring(minOf(state.start,state.end),maxOf(state.start,state.end))
        } else super.getSelectedText(flags)
    }
    override fun deleteSurroundingText(beforeLength: Int,afterLength: Int): Boolean = main {
        if(bodyInput() && ime!=null) return@main ime.delete(beforeLength,afterLength,false).also { ime.notifyIme() }
        super.deleteSurroundingText(beforeLength,afterLength)
    }
    override fun deleteSurroundingTextInCodePoints(beforeLength: Int,afterLength: Int): Boolean = main {
        if(bodyInput() && ime!=null) return@main ime.delete(beforeLength,afterLength,true).also { ime.notifyIme() }
        super.deleteSurroundingTextInCodePoints(beforeLength,afterLength)
    }
    override fun getTextAfterCursor(n: Int, flags: Int): CharSequence? = main {
        if(bodyInput() && ime!=null) {
            val state=ime.snapshot(); val start=maxOf(state.start,state.end)
            return@main state.text.substring(start,(start+n.coerceAtLeast(0).toLong()).coerceAtMost(state.text.length.toLong()).toInt())
        }
        super.getTextAfterCursor(n,flags)
    }

    override fun getTextBeforeCursor(n: Int,flags: Int): CharSequence? = main {
        if(!bodyInput() || ime==null) return@main super.getTextBeforeCursor(n,flags)
        val state=ime.snapshot(); val end=minOf(state.start,state.end)
        state.text.substring((end-n.coerceAtLeast(0).toLong()).coerceAtLeast(0).toInt(),end)
    }
    override fun getExtractedText(request: ExtractedTextRequest?,flags: Int): ExtractedText? = main {
        if(!bodyInput() || ime==null) return@main super.getExtractedText(request,flags)
        if(flags and InputConnection.GET_EXTRACTED_TEXT_MONITOR != 0) {
            ime.monitorToken=request?.token
            ime.monitorLimit=request?.hintMaxChars?.takeIf { it>0 } ?: 100000
        }
        val state=ime.snapshot()
        state.extracted(request?.hintMaxChars?.takeIf { it>0 } ?: 100000)
    }
    @androidx.annotation.RequiresApi(31)
    override fun getSurroundingText(beforeLength: Int,afterLength: Int,flags: Int): SurroundingText? = main {
        if(!bodyInput() || ime==null) return@main super.getSurroundingText(beforeLength,afterLength,flags)
        if(beforeLength<0 || afterLength<0) return@main null
        val state=ime.snapshot()
        val from=(minOf(state.start,state.end)-beforeLength.toLong()).coerceAtLeast(0).toInt()
        val to=(maxOf(state.start,state.end)+afterLength.toLong()).coerceAtMost(state.text.length.toLong()).toInt()
        SurroundingText(state.text.substring(from,to),state.start-from,state.end-from,from)
    }
    override fun commitText(text: CharSequence?,newCursorPosition: Int): Boolean = main {
        if(bodyInput() && ime!=null) (if(nativeReady()) super.commitText(text,newCursorPosition) else ime.insert(text,newCursorPosition,false)).also { ime.notifyIme() }
        else super.commitText(text,newCursorPosition)
    }
    override fun setComposingText(text: CharSequence?,newCursorPosition: Int): Boolean = main {
        if(bodyInput() && ime!=null) (if(nativeReady()) super.setComposingText(text,newCursorPosition) else ime.insert(text,newCursorPosition,true)).also { ime.notifyIme() }
        else super.setComposingText(text,newCursorPosition)
    }
    override fun finishComposingText(): Boolean = main {
        if(bodyInput() && ime!=null) ime.finishComposition().also { ime.notifyIme() } else super.finishComposingText()
    }
    override fun setComposingRegion(start: Int,end: Int): Boolean = main {
        if(bodyInput() && ime!=null) ime.composingRegion(start,end).also { ime.notifyIme() } else super.setComposingRegion(start,end)
    }
    @androidx.annotation.RequiresApi(33)
    override fun commitText(text: CharSequence,newCursorPosition: Int,textAttribute: TextAttribute?): Boolean = commitText(text,newCursorPosition)
    @androidx.annotation.RequiresApi(33)
    override fun setComposingText(text: CharSequence,newCursorPosition: Int,textAttribute: TextAttribute?): Boolean = setComposingText(text,newCursorPosition)
    @androidx.annotation.RequiresApi(33)
    override fun setComposingRegion(start: Int,end: Int,textAttribute: TextAttribute?): Boolean = setComposingRegion(start,end)
    @androidx.annotation.RequiresApi(34)
    override fun replaceText(start: Int,end: Int,text: CharSequence,newCursorPosition: Int,textAttribute: TextAttribute?): Boolean = main {
        if(bodyInput() && ime!=null) ime.select(start,end) && ime.insert(text,newCursorPosition,false).also { ime.notifyIme() }
        else super.replaceText(start,end,text,newCursorPosition,textAttribute)
    }
    override fun getCursorCapsMode(reqModes: Int): Int = main {
        if(bodyInput() && ime!=null) ime.snapshot().let { TextUtils.getCapsMode(it.text,minOf(it.start,it.end),reqModes) }
        else super.getCursorCapsMode(reqModes)
    }
    override fun beginBatchEdit(): Boolean = main {
        if(bodyInput() && ime!=null) { ime.batchDepth++; true } else super.beginBatchEdit()
    }
    override fun endBatchEdit(): Boolean = main {
        if(bodyInput() && ime!=null) { ime.batchDepth=(ime.batchDepth-1).coerceAtLeast(0); ime.notifyIme(); true } else super.endBatchEdit()
    }
    override fun closeConnection() {
        main { ime?.batchDepth=0; ime?.shiftHeld=false }
        super.closeConnection()
    }
}
