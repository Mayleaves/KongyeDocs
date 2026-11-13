package com.kongye.docs.ui.editor

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.systemGestureExclusion
import androidx.compose.ui.draw.shadow
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.kongye.docs.ui.theme.Kongye
import kotlin.math.roundToInt

private fun DocumentSelection.caret(point: SelectionPoint, cursorWidth: Float = 0f): Pair<Offset,Float>? {
    val coords = coordinates[point.key]?.takeIf { it.isAttached } ?: return null
    val layout = editor.layouts[point.key] ?: return null
    val rect = layout.getCursorRect(point.offset.coerceIn(0,layout.layoutInput.text.length))
    val center = if (cursorWidth > 0f) {
        // Match Compose's 2dp cursor stroke: clamp and snap its centre to pixels.
        val x = (rect.left + cursorWidth / 2).coerceAtMost(coords.size.width - cursorWidth / 2).coerceAtLeast(cursorWidth / 2)
        if (cursorWidth.toInt() % 2 == 1) kotlin.math.floor(x) + 0.5f else kotlin.math.round(x)
    } else rect.left
    return coords.localToRoot(Offset(center,rect.bottom)) to rect.height
}

private fun androidx.compose.ui.unit.Density.cursorWidth() = kotlin.math.floor(2.dp.toPx()).coerceAtLeast(1f)

internal fun Modifier.documentSelectionInput(editor: EditorState): Modifier =
    onGloballyPositioned { editor.selection.viewport = it }.pointerInput(editor) {
        awaitEachGesture {
            val down = awaitFirstDown(requireUnconsumed=false,pass=PointerEventPass.Initial)
            val selection = editor.selection
            val range = selection.range
            if (range == null) { dragInsertionHandle(editor,down); return@awaitEachGesture }
            val viewport = selection.viewport?.takeIf { it.isAttached } ?: return@awaitEachGesture
            val start = selection.caret(range.start) ?: return@awaitEachGesture
            val end = selection.caret(range.end) ?: return@awaitEachGesture
            val origin = viewport.localToRoot(Offset.Zero)
            val width = 48.dp.toPx(); val height = 48.dp.toPx()
            fun hit(point: Offset, left: Boolean): Rect {
                val local = point-origin
                return Rect(local.x-if(left) width else 0f,local.y,local.x+if(left) 0f else width,local.y+height)
            }
            val left = when {
                hit(start.first,true).contains(down.position) -> true
                hit(end.first,false).contains(down.position) -> false
                else -> return@awaitEachGesture
            }
            down.consume()
            val anchor = if(left) start else end
            val grab = viewport.localToRoot(down.position)-(anchor.first-Offset(0f,anchor.second/2f))
            selection.beginDrag(left)
            selection.dragRoot = viewport.localToRoot(down.position)-grab
            try {
                do {
                    val event = awaitPointerEvent(PointerEventPass.Initial)
                    val change = event.changes.firstOrNull { it.id == down.id } ?: break
                    change.consume()
                    val root = viewport.localToRoot(change.position)-grab
                    selection.dragRoot = root
                    selection.move(left,root)
                    val pressed = change.pressed
                } while(pressed)
            } finally { selection.endDrag() }
        }
    }

/** Insertion handle hit area: a finger-sized box centred under the caret. */
private const val INSERTION_HIT_DP = 48

private suspend fun androidx.compose.ui.input.pointer.AwaitPointerEventScope.dragInsertionHandle(
    editor: EditorState, down: androidx.compose.ui.input.pointer.PointerInputChange,
) {
    val selection = editor.selection
    val handle = editor.visibleInsertionHandle ?: return
    val viewport = selection.viewport?.takeIf { it.isAttached } ?: return
    val caret = selection.caret(handle, cursorWidth()) ?: return
    val local = caret.first-viewport.localToRoot(Offset.Zero)
    val half = INSERTION_HIT_DP.dp.toPx()/2f
    if (!Rect(local.x-half,local.y,local.x+half,local.y+half*2).contains(down.position)) return
    down.consume()
    // Keep the finger's offset from the caret line, so the caret does not jump on grab.
    val grab = viewport.localToRoot(down.position)-(caret.first-Offset(0f,caret.second/2f))
    editor.insertionDragRoot = viewport.localToRoot(down.position) - grab
    try {
        do {
            val event = awaitPointerEvent(PointerEventPass.Initial)
            val change = event.changes.firstOrNull { it.id == down.id } ?: break
            change.consume()
            val root = viewport.localToRoot(change.position) - grab
            editor.insertionDragRoot = root
            editor.moveInsertionHandle(root)
            val pressed = change.pressed
        } while (pressed)
    } finally { editor.insertionDragRoot = null }
}

/** Blue teardrop under a caret placed by a tap; it disappears once the user types. */
@Composable
private fun BoxScope.InsertionHandle(editor: EditorState, scroll: ScrollState) {
    val selection = editor.selection
    val handle = editor.visibleInsertionHandle ?: return
    val viewport = selection.viewport ?: return
    if (!viewport.isAttached) return
    @Suppress("UNUSED_VARIABLE") val frame = scroll.value + selection.layoutEpoch
    val density = androidx.compose.ui.platform.LocalDensity.current
    val caret = selection.caret(handle, with(density) { cursorWidth() })?.first ?: return
    val local = caret-viewport.localToRoot(Offset.Zero)
    if (local.y < 0 || local.y > viewport.size.height) return
    val color = androidx.compose.foundation.text.selection.LocalTextSelectionColors.current.handleColor
    val width = 22.dp
    // Circle of radius r plus a 45° square corner on top: total height r·(1+√2).
    val height = width/2*(1f+kotlin.math.sqrt(2f))
    val half = with(density) { width.toPx()/2f }
    Canvas(Modifier.offset { IntOffset((local.x-half).roundToInt(),local.y.roundToInt()) }
        .size(width,height).systemGestureExclusion().semantics { contentDescription="插入手柄" }) {
        val r = size.width/2f
        val cy = size.height-r
        drawCircle(color,r,Offset(r,cy))
        val d = r/kotlin.math.sqrt(2f)
        val path = androidx.compose.ui.graphics.Path().apply {
            moveTo(r,0f); lineTo(r+d,cy-d); lineTo(r,cy); lineTo(r-d,cy-d); close()
        }
        drawPath(path,color)
    }
    LaunchedEffect(editor.insertionDragRoot != null) {
        while (editor.insertionDragRoot != null) {
            withFrameNanos { }
            val root = editor.insertionDragRoot ?: break
            val y = root.y - viewport.localToRoot(Offset.Zero).y
            val margin = with(density) { 48.dp.toPx() }
            val delta = when {
                y < margin -> -((margin-y)/5).coerceAtMost(28f)
                y > viewport.size.height-margin -> ((y-viewport.size.height+margin)/5).coerceAtMost(28f)
                else -> 0f
            }
            if (delta != 0f) {
                scroll.scrollBy(delta)
                withFrameNanos { }
                editor.moveInsertionHandle(root)
            }
        }
    }
}

/** The stable viewport owns dragging; moving handle drawings never change pointer coordinates. */
@Composable
internal fun BoxScope.SelectionHandles(editor: EditorState, scroll: ScrollState) {
    val selection = editor.selection
    val range = selection.range ?: run { InsertionHandle(editor,scroll); return }
    val viewport = selection.viewport ?: return
    if (!viewport.isAttached) return
    @Suppress("UNUSED_VARIABLE") val frame = scroll.value + selection.layoutEpoch
    val origin = viewport.localToRoot(Offset.Zero)
    val density = androidx.compose.ui.platform.LocalDensity.current
    val color = androidx.compose.foundation.text.selection.LocalTextSelectionColors.current.handleColor
    listOf(true to range.start,false to range.end).forEach { (left,point) ->
        val caret = selection.caret(point)?.first ?: return@forEach
        val local = caret-origin
        if(local.y < 0 || local.y > viewport.size.height) return@forEach
        val width = with(density) { 25.dp.toPx() }
        Canvas(Modifier.offset { IntOffset((local.x-if(left) width else 0f).roundToInt(),local.y.roundToInt()) }
            .size(25.dp,25.dp).systemGestureExclusion().semantics { contentDescription=if(left) "选择起点" else "选择终点" }) {
            // Same circle + upper corner geometry and 25dp size as the platform Compose handle.
            val radius=size.width/2f
            drawCircle(color,radius,Offset(radius,radius))
            drawRect(color,Offset(if(left) radius else 0f,0f),Size(radius,radius))
        }
    }
    LaunchedEffect(selection.dragStart) {
        val left = selection.dragStart ?: return@LaunchedEffect
        while (selection.dragStart != null) {
            withFrameNanos { }
            val root = selection.dragRoot ?: continue
            val top = viewport.localToRoot(Offset.Zero).y
            val y = root.y-top
            val margin = with(density) { 48.dp.toPx() }
            val delta = when {
                y < margin -> -((margin-y)/5).coerceAtMost(28f)
                y > viewport.size.height-margin -> ((y-viewport.size.height+margin)/5).coerceAtMost(28f)
                else -> 0f
            }
            if(delta != 0f) { scroll.scrollBy(delta); withFrameNanos { }; selection.move(left,root) }
        }
    }
}

/** Use Android's existing floating text menu, with no additional app toolbar. */
@Suppress("DEPRECATION")
@Composable
internal fun BoxScope.SelectionActions(editor: EditorState) {
    val selection=editor.selection
    val toolbar=androidx.compose.ui.platform.LocalTextToolbar.current
    val context=androidx.compose.ui.platform.LocalContext.current
    val clip=context.getSystemService(android.content.ClipboardManager::class.java)
    val viewport=selection.viewport
    val range=selection.range
    val epoch=selection.layoutEpoch
    LaunchedEffect(range,selection.dragStart,epoch,selection.fromKeyboard) {
        if(range==null || selection.dragStart!=null || viewport?.isAttached!=true) { toolbar.hide(); return@LaunchedEffect }
        val start=selection.caret(range.start) ?: return@LaunchedEffect
        val end=selection.caret(range.end) ?: return@LaunchedEffect
        val top=viewport.localToRoot(Offset.Zero).y
        val rect=Rect(minOf(start.first.x,end.first.x),maxOf(top,start.first.y-start.second),maxOf(start.first.x,end.first.x)+1f,minOf(top+viewport.size.height,end.first.y))
        toolbar.showMenu(rect,
            onCopyRequested={ DocumentClipboard.copy(context,editor) },
            onPasteRequested=if(clip.hasPrimaryClip()) ({ DocumentClipboard.paste(context,editor) }) else null,
            onCutRequested={ DocumentClipboard.copy(context,editor,cut=true) },
            onSelectAllRequested=if(!editor.articleSelected) ({ editor.selectArticle() }) else null,
        )
    }
    DisposableEffect(toolbar) { onDispose { toolbar.hide() } }
}

/** Only the document coordinator draws selection; native per-field handles must never overlap it. */
@Composable
internal fun DocumentSelectionDrawing(content: @Composable () -> Unit) {
    CompositionLocalProvider(
        androidx.compose.foundation.text.selection.LocalTextSelectionColors provides
            androidx.compose.foundation.text.selection.TextSelectionColors(androidx.compose.ui.graphics.Color.Transparent,androidx.compose.ui.graphics.Color.Transparent),
        content = content,
    )
}
