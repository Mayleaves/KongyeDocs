package com.kongye.docs.data

import kotlinx.serialization.Serializable
import java.util.UUID

fun newId(): String = UUID.randomUUID().toString().replace("-", "").take(16)

@Serializable
enum class ItemType { DOC, FOLDER }

/** 桌面上的一个条目：文档或文件夹（文档正文另存在 docs/<id>.json） */
@Serializable
data class Item(
    val id: String = newId(),
    val type: ItemType,
    val title: String = "",
    val parentId: String? = null,
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = createdAt,
    val openedAt: Long = 0L,
    val starred: Boolean = false,
    val deletedAt: Long? = null,
    val preview: String = "",
    val words: Int = 0,
) {
    val isDoc get() = type == ItemType.DOC
    val isFolder get() = type == ItemType.FOLDER
    val displayTitle get() = title.ifBlank { if (isDoc) "无标题" else "未命名文件夹" }
}

@Serializable
enum class BlockType {
    TEXT, H1, H2, H3, BULLET, NUMBER, TODO, QUOTE, CODE, DIVIDER, IMAGE, TABLE;

    val isText get() = this != DIVIDER && this != IMAGE && this != TABLE
    val isList get() = this == BULLET || this == NUMBER || this == TODO
    val isHeading get() = this == H1 || this == H2 || this == H3
}

@Serializable
enum class Align { START, CENTER, END }

/** COMMENT 不是显示样式，而是评论的锚点：标记这段文字挂着哪条评论 */
@Serializable
enum class InlineStyle { BOLD, ITALIC, UNDERLINE, STRIKE, COLOR, HIGHLIGHT, LINK, COMMENT }

/** 行内样式区间 [start, end)，偏移量基于块的纯文本 */
@Serializable
data class StyleRange(
    val start: Int,
    val end: Int,
    val style: InlineStyle,
    val color: Long = 0L,
    /** 链接地址；评论锚点（COMMENT）时是评论 id */
    val url: String = "",
) {
    fun sameKind(other: StyleRange) = style == other.style && color == other.color && url == other.url
}

@Serializable
data class Block(
    val id: String = newId(),
    val type: BlockType = BlockType.TEXT,
    val text: String = "",
    val spans: List<StyleRange> = emptyList(),
    val checked: Boolean = false,
    val indent: Int = 0,
    val align: Align = Align.START,
    val image: String = "",
    val imageRatio: Float = 0f,
    val table: List<List<String>> = emptyList(),
    /** 段落首行缩进两个字 */
    val firstIndent: Boolean = false,
)

/** 首行缩进只对普通段落、标题和引用有意义 */
val BlockType.canFirstIndent get() = this == BlockType.TEXT || this == BlockType.QUOTE || isHeading

@Serializable
data class Comment(val id: String = newId(), val text: String, val time: Long = System.currentTimeMillis())

/** 一条评论（可以有多条回复），挂在正文里 COMMENT 锚点标出的文字上 */
@Serializable
data class CommentThread(val id: String = newId(), val comments: List<Comment> = emptyList())

@Serializable
data class DocContent(val blocks: List<Block> = listOf(Block()), val comments: List<CommentThread> = emptyList())

/** 去掉评论及其锚点（存为模板、用模板新建时用） */
fun DocContent.withoutComments(): DocContent =
    if (comments.isEmpty() && blocks.none { b -> b.spans.any { it.style == InlineStyle.COMMENT } }) this
    else DocContent(blocks.map { b -> b.copy(spans = b.spans.filterNot { it.style == InlineStyle.COMMENT }) })

@Serializable
data class Library(
    val items: List<Item> = emptyList(),
    val nickname: String = "我",
    /** 文件页用网格（缩略图）展示 */
    val filesGrid: Boolean = true,
    /** 首页用网格展示 */
    val homeGrid: Boolean = false,
    /** 用户自己“保存为模板”的模板（正文在 templates/<id>.json） */
    val templates: List<UserTemplate> = emptyList(),
    /** 分享图片底部的落款，空字符串表示不显示 */
    val footer: String = DEFAULT_FOOTER,
    /** 编辑器工具栏的顺序（工具 id），空表示默认顺序 */
    val toolbar: List<String> = emptyList(),
    /** 手动排序：文件夹 id（桌面根目录用空字符串）-> 拖动排好的条目顺序 */
    val manualOrder: Map<String, List<String>> = emptyMap(),
    val revision: Long = 0L,
)

const val DEFAULT_FOOTER = "由 空叶文档 生成"

/** 下载记录：每次分享 / 保存的图片、文字、Markdown 都留一份副本（存放在 exports/ 下） */
@Serializable
enum class ExportKind(val ext: String, val label: String) { IMAGE("png", "图片"), TEXT("txt", "文字"), MARKDOWN("md", "Markdown") }

@Serializable
data class ExportRecord(
    val id: String = newId(),
    val time: Long = System.currentTimeMillis(),
    val kind: ExportKind,
    val fileName: String,
    /** true：保存到了相册；false：分享给了其他应用 */
    val saved: Boolean = false,
    val docId: String = "",
)

@Serializable
data class UserTemplate(
    val id: String = newId(),
    val name: String,
    val title: String,
    val createdAt: Long = System.currentTimeMillis(),
)

/** HISTORY 仅兼容旧数据；LOCAL 保留 v2.5 的手动、定时自动及恢复前版本。 */
@Serializable
enum class VersionKind { HISTORY, LOCAL }

@Serializable
data class DocVersion(
    val id: String = newId(),
    val time: Long = System.currentTimeMillis(),
    val kind: VersionKind,
    val note: String = "",
    val title: String,
    val content: DocContent,
    val words: Int = 0,
)

@Serializable
data class Backup(
    val app: String = BackupCodec.APP_ID,
    val version: Int = 1,
    val exportedAt: Long = System.currentTimeMillis(),
    val nickname: String = "我",
    val items: List<Item> = emptyList(),
    val docs: Map<String, DocContent> = emptyMap(),
    val images: Map<String, String> = emptyMap(),
    val library: Library? = null,
    val versions: Map<String, List<DocVersion>> = emptyMap(),
    val templates: Map<String, DocContent> = emptyMap(),
    val exportRecords: List<ExportRecord> = emptyList(),
    val exportFiles: Map<String, String> = emptyMap(),
    val revision: Long = 0L,
    val checksum: String = "",
)

/** 文档纯文本，用于搜索、预览、字数统计和分享 */
fun DocContent.plainText(): String = blocks.joinToString("\n") { b ->
    when (b.type) {
        BlockType.DIVIDER -> "———"
        BlockType.IMAGE -> "[图片]"
        BlockType.TABLE -> b.table.joinToString("\n") { it.joinToString("\t") }
        BlockType.TODO -> (if (b.checked) "☑ " else "☐ ") + b.text
        BlockType.BULLET -> "• " + b.text
        else -> if (b.firstIndent && b.type.canFirstIndent) "　　" + b.text else b.text
    }
}

fun DocContent.bodyText(): String = blocks.joinToString("\n") { b ->
    when (b.type) {
        BlockType.TABLE -> b.table.joinToString(" ") { it.joinToString(" ") }
        else -> b.text
    }
}.trim()

private val cjk = Regex("[\\u3400-\\u4dbf\\u4e00-\\u9fff\\uf900-\\ufaff\\u3040-\\u30ff\\uac00-\\ud7af]")
private val latinWord = Regex("[A-Za-z0-9]+(?:['’.\\-][A-Za-z0-9]+)*")

/** 字数：每个汉字（含日韩文字）算 1，每个英文单词或数字算 1 */
fun countWords(text: String): Int = cjk.findAll(text).count() + latinWord.findAll(text).count()
