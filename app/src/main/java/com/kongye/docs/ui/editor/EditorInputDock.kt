package com.kongye.docs.ui.editor

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.layout.layout
import androidx.compose.ui.unit.Dp
import com.kongye.docs.ui.theme.Kongye

/** One bottom slot is shared by the IME and tools, including the IME animation frames. */
@Composable
internal fun EditorInputDock(panel: EdPanel?,panelHeight: Dp,content: @Composable ()->Unit) {
    val ime=WindowInsets.ime
    val navigation=WindowInsets.navigationBars
    Box(Modifier.fillMaxWidth().background(Kongye.Surface).clipToBounds().layout { measurable,constraints ->
        // Read insets during layout: composition can be one frame behind the system keyboard.
        val bottom=navigation.getBottom(this)
        val tools=if(panel!=null) panelHeight.roundToPx()+bottom else 0
        val height=maxOf(ime.getBottom(this),bottom,tools).coerceAtMost(constraints.maxHeight)
        val child=measurable.measure(constraints.copy(minHeight=height,maxHeight=height))
        layout(child.width,height) { child.placeRelative(0,0) }
    }) {
        if(panel!=null) Box(Modifier.fillMaxWidth().height(panelHeight)) { content() }
    }
}
