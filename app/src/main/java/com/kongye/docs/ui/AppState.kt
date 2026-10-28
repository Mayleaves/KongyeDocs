package com.kongye.docs.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.kongye.docs.data.Item
import com.kongye.docs.data.Repository
import com.kongye.docs.data.Settings
import com.kongye.docs.data.VersionKind

sealed interface Screen {
    data object Home : Screen
    data class Folder(val id: String) : Screen
    data object Trash : Screen
    data class Templates(val parentId: String?) : Screen

    /** 只预览模板内容，点“使用此模板”才会真正新建文档 */
    data class TemplatePreview(val templateId: String, val userTemplate: Boolean, val parentId: String?) : Screen
    data class Editor(val docId: String, val isNew: Boolean = false) : Screen

    /** 文档生成长图后的预览，可保存或分享 */
    data class ImagePreview(val docId: String) : Screen

    /** 查看本地版本 */
    data class Versions(val docId: String) : Screen
    data class VersionPreview(val docId: String, val versionId: String) : Screen

    /** 我的 → 下载记录，以及单条记录的查看页 */
    data object Downloads : Screen
    data class ExportView(val id: String) : Screen

    /** 我的 → 本地备份 / 云端备份（WebDAV） */
    data object LocalBackup : Screen
    data object CloudBackup : Screen

    /** 我的 → 关于 */
    data object About : Screen
}

/** 显示用的版本号（与 build.gradle 的 versionName 一致） */
const val APP_VERSION = "2.14"

/** 底部导航：首页 / 文件 / 我的 */
enum class Tab { HOME, FILES, ME }

/** 首页顶部的分栏 */
enum class HomeSub(val label: String) { RECENT("最近"), STARRED("收藏"), CREATED("创建") }

/**
 * 排序方式：sheet 里显示完整名称，排序栏里显示简称；时间默认从新到旧，名称、类型默认正序。
 * 手动排序是长按拖动排好的顺序（只在文件页 / 文件夹里提供），没有方向。
 */
enum class SortMode(val label: String, val short: String, val defaultDesc: Boolean) {
    NAME("按文件名排序", "按文件名", false),
    TYPE("按类型排序", "按类型", false),
    CREATED("按创建时间排序", "按创建时间", true),
    UPDATED("按更新时间排序", "按更新时间", true),
    MANUAL("手动排序", "手动排序", false),
}

/** 一种排序方式加方向 */
data class SortSpec(val mode: SortMode, val desc: Boolean = mode.defaultDesc) {
    /** 在排序面板里点了某一项：点当前项则反转方向（手动排序除外），点别的项则切换并用它的默认方向 */
    fun pick(m: SortMode) = when {
        m != mode -> SortSpec(m)
        m == SortMode.MANUAL -> this
        else -> copy(desc = !desc)
    }
}

/** 搜索范围（齿轮里设置）：时间按“最近更新”过滤 */
enum class SearchTime(val label: String, val days: Int?) {
    ANY("任意时间", null), WEEK("最近一周", 7), MONTH("最近一月", 30), YEAR("最近一年", 365),
}

enum class SearchType(val label: String) { ANY("任意类型"), FOLDER("文件夹"), DOC("文档"), COMMENT("评论") }

sealed interface DialogRequest {
    data class NewFolder(val parentId: String?) : DialogRequest
    data class Rename(val item: Item) : DialogRequest
    /** 移动一个或多个条目（多选时为多个） */
    data class Move(val items: List<Item>) : DialogRequest
    /** 多选后批量移到回收站 */
    data class TrashMany(val items: List<Item>) : DialogRequest
    data class Destroy(val item: Item) : DialogRequest
    data object EmptyTrash : DialogRequest
}

/** 全局界面状态：页面栈、底部标签、弹窗请求 */
object AppState {
    val stack = mutableStateListOf<Screen>(Screen.Home)
    var forward by mutableStateOf(true)
        private set
    var tab by mutableStateOf(Tab.HOME)
    var homeSub by mutableStateOf(HomeSub.RECENT)

    /** 文件页、收藏、创建各自的排序方式；文件页的会记住（手动排序下次打开仍是手动顺序） */
    private var filesSort by mutableStateOf(savedFilesSort())
    var sort: SortSpec
        get() = filesSort
        set(value) {
            filesSort = value
            Settings.update { it.copy(filesSortMode = value.mode.name, filesSortDesc = value.desc) }
        }
    var starredSort by mutableStateOf(SortSpec(SortMode.UPDATED))
    var createdSort by mutableStateOf(SortSpec(SortMode.CREATED))

    /** 正在显示操作面板的条目 */
    var menuFor by mutableStateOf<Item?>(null)

    /** 正在显示分享面板的文档 */
    var shareFor by mutableStateOf<Item?>(null)

    /** 新建面板（值为目标文件夹 id，桌面根目录用 ROOT） */
    var createIn by mutableStateOf<String?>(null)
    var dialog by mutableStateOf<DialogRequest?>(null)

    /** 请求触发“导入文件”，值为目标文件夹（ROOT 表示桌面） */
    var importInto by mutableStateOf<String?>(null)

    /** 多选：null 表示不在多选状态，否则是已选中的条目 id */
    var selection by mutableStateOf<Set<String>?>(null)

    /** 搜索浮层是否打开，以及搜索范围 */
    var searchOpen by mutableStateOf(false)
    var searchTime by mutableStateOf(SearchTime.ANY)
    var searchType by mutableStateOf(SearchType.ANY)

    /** 从搜索结果打开文档时要直接打开的评论 */
    var pendingComment by mutableStateOf<String?>(null)

    const val ROOT = "__root__"

    private fun savedFilesSort(): SortSpec {
        val s = Settings.value
        val mode = SortMode.entries.firstOrNull { it.name == s.filesSortMode } ?: return SortSpec(SortMode.UPDATED)
        return SortSpec(mode, s.filesSortDesc)
    }

    val current: Screen get() = stack.last()

    fun push(screen: Screen) {
        forward = true
        selection = null
        stack.add(screen)
    }

    fun pop(): Boolean {
        if (stack.size <= 1) return false
        if (current is Screen.Editor && !com.kongye.docs.ui.editor.EditorSession.closeDocument((current as Screen.Editor).docId)) return false
        forward = false
        selection = null
        stack.removeAt(stack.lastIndex)
        return true
    }

    /** 返回到主页并切换标签 */
    fun goHome(tab: Tab) {
        if (stack.filterIsInstance<Screen.Editor>().any { !com.kongye.docs.ui.editor.EditorSession.closeDocument(it.docId) }) return
        forward = false
        selection = null
        while (stack.size > 1) stack.removeAt(stack.lastIndex)
        this.tab = tab
    }

    fun startSelection(id: String) { selection = setOf(id) }

    fun toggleSelect(id: String) {
        val s = selection ?: return
        selection = if (id in s) s - id else s + id
    }

    fun endSelection() { selection = null }

    fun openDoc(id: String, isNew: Boolean = false) {
        runCatching { Repository.loadDoc(id); Repository.versions(id); Repository.markOpened(id) }
            .onFailure { com.kongye.docs.ui.components.Toast.show(it.message ?: "读取失败，原文件已保留"); return }
        push(Screen.Editor(id, isNew))
    }

    fun open(item: Item) = if (item.isFolder) push(Screen.Folder(item.id)) else openDoc(item.id)

    /** 用模板新建的文档：页面栈换成“文件（及所在文件夹）→ 编辑器”，从编辑器返回直接回到文件 */
    fun openFromTemplate(docId: String, parentId: String?, isNew: Boolean = false) {
        goHome(Tab.FILES)
        if (parentId != null) stack.add(Screen.Folder(parentId))
        openDoc(docId, isNew)
    }

    fun newDoc(parentId: String?) {
        val item = runCatching { Repository.createDoc(parentId) }.getOrElse { com.kongye.docs.ui.components.Toast.show(it.message ?: "新建失败"); return }
        openDoc(item.id, isNew = true)
    }

    /** 当前所在的文件夹（新建时的默认位置） */
    fun currentFolder(): String? = when (val s = current) {
        is Screen.Folder -> s.id
        else -> null
    }

    fun requestCreate(parentId: String?) { createIn = parentId ?: ROOT }
}

fun String?.fromRoot(): String? = if (this == AppState.ROOT) null else this
