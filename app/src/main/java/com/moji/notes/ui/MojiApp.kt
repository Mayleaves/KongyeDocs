package com.moji.notes.ui

import com.moji.notes.ui.theme.Moji

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.material3.Text
import androidx.compose.material3.Button
import androidx.compose.ui.unit.dp
import androidx.compose.ui.Alignment
import com.moji.notes.data.Repository
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import com.moji.notes.ui.components.ToastHost
import com.moji.notes.ui.editor.EditorScreen
import com.moji.notes.ui.home.HomeScreen
import com.moji.notes.ui.screens.AboutScreen
import com.moji.notes.ui.screens.CloudBackupScreen
import com.moji.notes.ui.screens.DownloadsScreen
import com.moji.notes.ui.screens.LocalBackupScreen
import com.moji.notes.ui.screens.ExportViewScreen
import com.moji.notes.ui.screens.FolderScreen
import com.moji.notes.ui.screens.ImagePreviewScreen
import com.moji.notes.ui.screens.TemplatePreviewScreen
import com.moji.notes.ui.screens.TemplatesScreen
import com.moji.notes.ui.screens.TrashScreen
import com.moji.notes.ui.screens.VersionPreviewScreen
import com.moji.notes.ui.screens.VersionsScreen
import com.moji.notes.ui.search.SearchOverlay

@Composable
fun MojiApp() {
    val problem by Repository.problem.collectAsState()
    if (problem.isNotEmpty() && AppState.current !is Screen.Editor && AppState.current != Screen.LocalBackup && AppState.current != Screen.CloudBackup) {
        Column(Modifier.fillMaxSize().background(Moji.Surface).padding(28.dp), verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
            Text("数据保护模式", color = Moji.Ink)
            Text(problem, Modifier.padding(vertical = 20.dp), color = Moji.Sub)
            Button(onClick = { AppState.push(Screen.LocalBackup) }) { Text("从本地备份恢复") }
            Button(onClick = { AppState.push(Screen.CloudBackup) }) { Text("从云端备份恢复") }
        }
        return
    }
    BackHandler(enabled = AppState.stack.size > 1) { AppState.pop() }
    // 多选时按返回键先退出多选（后注册的优先）
    BackHandler(enabled = AppState.selection != null) { AppState.endSelection() }
    val stateHolder = rememberSaveableStateHolder()
    Box(Modifier.fillMaxSize().background(Moji.Surface)) {
        AnimatedContent(
            targetState = AppState.current,
            transitionSpec = {
                val d = 260
                if (AppState.forward) {
                    (slideInHorizontally(tween(d)) { it } + fadeIn(tween(d))) togetherWith
                        (slideOutHorizontally(tween(d)) { -it / 4 } + fadeOut(tween(d)))
                } else {
                    (slideInHorizontally(tween(d)) { -it / 4 } + fadeIn(tween(d))) togetherWith
                        (slideOutHorizontally(tween(d)) { it } + fadeOut(tween(d)))
                }
            },
            label = "navigation",
        ) { screen ->
            stateHolder.SaveableStateProvider(screen.toString()) {
                when (screen) {
                    Screen.Home -> HomeScreen()
                    is Screen.Folder -> FolderScreen(screen.id)
                    Screen.Trash -> TrashScreen()
                    is Screen.Templates -> TemplatesScreen(screen.parentId)
                    is Screen.TemplatePreview -> TemplatePreviewScreen(screen.templateId, screen.userTemplate, screen.parentId)
                    is Screen.Editor -> EditorScreen(screen.docId, screen.isNew)
                    is Screen.ImagePreview -> ImagePreviewScreen(screen.docId)
                    is Screen.Versions -> VersionsScreen(screen.docId, screen.kind)
                    is Screen.VersionPreview -> VersionPreviewScreen(screen.docId, screen.versionId)
                    Screen.Downloads -> DownloadsScreen()
                    is Screen.ExportView -> ExportViewScreen(screen.id)
                    Screen.LocalBackup -> LocalBackupScreen()
                    Screen.CloudBackup -> CloudBackupScreen()
                    Screen.About -> AboutScreen()
                }
            }
        }
        // 首页 / 文件里的搜索：浮在当前页面上（顶部搜索栏滑入、背景变暗）
        if (AppState.searchOpen) SearchOverlay()
        GlobalOverlays()
        ToastHost()
        IntroOverlay()
    }
}
