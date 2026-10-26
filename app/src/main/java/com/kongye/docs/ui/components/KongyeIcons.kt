package com.kongye.docs.ui.components

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathFillType
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.addPathNodes
import androidx.compose.ui.unit.dp

/** 细线风格图标（24 视口，1.7 线宽），用 SVG 路径描述 */
object KongyeIcons {
    private class Spec {
        val strokes = mutableListOf<Pair<String, Float>>()
        val fills = mutableListOf<Pair<String, PathFillType>>()
        fun s(d: String, alpha: Float = 1f) { strokes += d to alpha }
        fun f(d: String) { fills += d to PathFillType.NonZero }

        /** 奇偶填充：路径中的内层形状会镂空 */
        fun fe(d: String) { fills += d to PathFillType.EvenOdd }
    }

    private fun icon(name: String, width: Float = 1.7f, build: Spec.() -> Unit): ImageVector {
        val spec = Spec().apply(build)
        return ImageVector.Builder(name, 24.dp, 24.dp, 24f, 24f).apply {
            spec.fills.forEach { (d, type) -> addPath(pathData = addPathNodes(d), fill = SolidColor(Color.Black), pathFillType = type) }
            spec.strokes.forEach { (d, a) ->
                addPath(
                    pathData = addPathNodes(d), stroke = SolidColor(Color.Black), strokeAlpha = a,
                    strokeLineWidth = width, strokeLineCap = StrokeCap.Round, strokeLineJoin = StrokeJoin.Round,
                )
            }
        }.build()
    }

    private fun circle(cx: Float, cy: Float, r: Float) = "M${cx - r} ${cy}a$r $r 0 1 0 ${2 * r} 0a$r $r 0 1 0 ${-2 * r} 0z"
    private fun rrect(x: Float, y: Float, w: Float, h: Float, r: Float) =
        "M${x + r} ${y}h${w - 2 * r}a$r $r 0 0 1 $r ${r}v${h - 2 * r}a$r $r 0 0 1 ${-r} ${r}h${-(w - 2 * r)}a$r $r 0 0 1 ${-r} ${-r}v${-(h - 2 * r)}a$r $r 0 0 1 $r ${-r}z"

    /** 齿轮外轮廓（6 个齿）：每个齿取内圈两点、外圈两点，圆角线帽让齿形圆润 */
    private fun gear(): String {
        val sb = StringBuilder()
        for (i in 0 until 6) {
            val base = i * Math.PI / 3 - Math.PI / 2
            listOf(7.0 to -19.0, 9.4 to -11.0, 9.4 to 11.0, 7.0 to 19.0).forEachIndexed { j, (r, deg) ->
                val t = base + Math.toRadians(deg)
                val x = Math.round((12 + r * Math.cos(t)) * 100) / 100f
                val y = Math.round((12 + r * Math.sin(t)) * 100) / 100f
                sb.append(if (i == 0 && j == 0) "M" else "L").append(x).append(' ').append(y)
            }
        }
        return sb.append('Z').toString()
    }

    private const val FOLDER ="M3.5 7.5a2 2 0 0 1 2-2h4l2 2.5h7a2 2 0 0 1 2 2v7.5a2 2 0 0 1-2 2h-13a2 2 0 0 1-2-2z"
    private const val STAR = "M12 3.8l2.5 5.1 5.6.8-4 3.9.9 5.6-5-2.6-5 2.6.9-5.6-4-3.9 5.6-.8z"
    private const val DOC = "M7 3.5h7l4.5 4.5v11a1.5 1.5 0 0 1-1.5 1.5H7a1.5 1.5 0 0 1-1.5-1.5V5A1.5 1.5 0 0 1 7 3.5z"

    val Plus = icon("plus", 1.9f) { s("M12 5v14M5 12h14") }
    val Clock = icon("clock") { s(circle(12f, 12f, 8.5f)); s("M12 7.5V12l3 2") }
    val Folder = icon("folder") { s(FOLDER) }
    val FolderFilled = icon("folderFilled") { f(FOLDER); s(FOLDER) }
    val FolderPlus = icon("folderPlus") { s(FOLDER); s("M12 11v5M9.5 13.5h5") }
    val Star = icon("star") { s(STAR) }
    val StarFilled = icon("starFilled") { f(STAR); s(STAR) }
    val User = icon("user") { s(circle(12f, 8.5f, 3.8f)); s("M4.5 20c.8-3.6 3.9-5.8 7.5-5.8s6.7 2.2 7.5 5.8") }
    val UserFilled = icon("userFilled") { f(circle(12f, 8.5f, 3.8f)); s(circle(12f, 8.5f, 3.8f)); f("M4.5 20c.8-3.6 3.9-5.8 7.5-5.8s6.7 2.2 7.5 5.8z"); s("M4.5 20c.8-3.6 3.9-5.8 7.5-5.8s6.7 2.2 7.5 5.8z") }
    val Search = icon("search") { s(circle(11f, 11f, 6.5f)); s("M16 16l4.5 4.5") }
    val Back = icon("back", 1.9f) { s("M15 5l-7 7 7 7") }
    val ChevronRight = icon("chevronRight") { s("M9.5 6l6 6-6 6") }
    val ChevronDown = icon("chevronDown") { s("M7 10l5 5 5-5") }
    val More = icon("more") { f(circle(5.5f, 12f, 1.5f)); f(circle(12f, 12f, 1.5f)); f(circle(18.5f, 12f, 1.5f)) }
    val MoreV = icon("moreV") { f(circle(12f, 5.5f, 1.6f)); f(circle(12f, 12f, 1.6f)); f(circle(12f, 18.5f, 1.6f)) }

    // 列表 / 网格视图切换
    val ViewList = icon("viewList", 2.2f) {
        f(circle(4.6f, 6f, 1.4f)); f(circle(4.6f, 12f, 1.4f)); f(circle(4.6f, 18f, 1.4f))
        s("M9 6h11M9 12h11M9 18h11")
    }
    val ViewGrid = icon("viewGrid") {
        f(rrect(4f, 4f, 7.2f, 7.2f, 0.8f)); f(rrect(12.8f, 4f, 7.2f, 7.2f, 0.8f))
        f(rrect(4f, 12.8f, 7.2f, 7.2f, 0.8f)); f(rrect(12.8f, 12.8f, 7.2f, 7.2f, 0.8f))
    }

    // 文档“更多”菜单（实心，石墨风格）
    val Bookmark = icon("bookmark") { f("M6.5 3h11A1.5 1.5 0 0 1 19 4.5V21l-7-4.6L5 21V4.5A1.5 1.5 0 0 1 6.5 3z") }
    val InfoFilled = icon("infoFilled") { fe(circle(12f, 12f, 9.2f) + "M10.9 10.4h2.2v7.2h-2.2z" + circle(12f, 7.6f, 1.35f)) }
    val FindReplace = icon("findReplace") {
        f("M4 3h10.5L19 7.5V10.6h-2V8.5h-3.5V5H6v14h4.6v2H4z")
        s(circle(15.8f, 15.8f, 3.4f)); s("M18.4 18.4l2.6 2.6")
    }
    val TemplateFilled = icon("templateFilled") {
        f(rrect(3f, 3.5f, 18f, 4.2f, 1f)); f(rrect(3f, 9.2f, 6.4f, 11.3f, 1f)); f(rrect(11f, 9.2f, 10f, 11.3f, 1f))
    }
    val TocFilled = icon("tocFilled") {
        fe(rrect(3.5f, 3.5f, 17f, 17f, 2f) + "M7 7.6h2v1.8H7zM11 7.6h6v1.8h-6zM7 11.1h2v1.8H7zM11 11.1h6v1.8h-6zM7 14.6h2v1.8H7zM11 14.6h6v1.8h-6z")
    }
    val HistoryFilled = icon("historyFilled") { fe(circle(12f, 12f, 9.2f) + "M11 6.8h2v4.7l3.3 2-1 1.7-4.3-2.6z") }
    val SaveFilled = icon("saveFilled") {
        fe("M6 3h10.2L20 6.8V19a2 2 0 0 1-2 2H6a2 2 0 0 1-2-2V5a2 2 0 0 1 2-2zM7 5h8v4.5H7zM7.5 13h9v6h-9z")
    }

    // 底部导航（实心）
    /** 五边形房子，底部中间开一道窄门 */
    val NavHome = icon("navHome") {
        fe("M12 3.6L19.6 9.1V19.3a.5.5 0 0 1-.5.5H4.9a.5.5 0 0 1-.5-.5V9.1ZM11 14.6h2v5.2h-2Z")
    }
    val NavFiles = icon("navFiles") {
        fe("M3 6.5A1.5 1.5 0 0 1 4.5 5H10l2 2.2h7.5A1.5 1.5 0 0 1 21 8.7v10.8a1.5 1.5 0 0 1-1.5 1.5h-15A1.5 1.5 0 0 1 3 19.5ZM9 13h6v1.8H9Z")
    }
    val NavMe = icon("navMe") { f(circle(12f, 7.8f, 4.3f)); f("M3.6 21c.4-4.4 4-7.4 8.4-7.4s8 3 8.4 7.4Z") }
    val Share = icon("share") { s("M12 4v11"); s("M8 8l4-4 4 4"); s("M5 13v5a2 2 0 0 0 2 2h10a2 2 0 0 0 2-2v-5") }
    val Trash = icon("trash") {
        s("M4 7h16"); s("M9.5 7V4.8h5V7")
        s("M6.5 7l.9 12.2a1.5 1.5 0 0 0 1.5 1.3h6.2a1.5 1.5 0 0 0 1.5-1.3L17.5 7"); s("M10 11v6M14 11v6")
    }
    val Template = icon("template") { s(rrect(4f, 4f, 16f, 16f, 2f)); s("M4 9.5h16M9.5 9.5V20") }
    val Download = icon("download") { s("M12 4v11"); s("M8 11l4 4 4-4"); s("M5 19.5h14") }
    val Upload = icon("upload") { s("M12 15V4"); s("M8 8l4-4 4 4"); s("M5 19.5h14") }
    val Restore = icon("restore") { s("M4.5 12a7.5 7.5 0 1 0 2.2-5.3"); s("M4 4.5v4h4") }
    val Edit = icon("edit") { s("M4 20h4L19 9a2.83 2.83 0 0 0-4-4L4 16z"); s("M13.5 6.5l4 4") }
    val Move = icon("move") { s(FOLDER); s("M10 13.5h6M13.5 11l2.5 2.5-2.5 2.5") }
    val Copy = icon("copy") { s(rrect(8f, 8f, 12f, 12f, 2f)); s("M16 8V6a2 2 0 0 0-2-2H6a2 2 0 0 0-2 2v8a2 2 0 0 0 2 2h2") }
    val Info = icon("info") { s(circle(12f, 12f, 8.5f)); s("M12 11v5.5"); f(circle(12f, 7.9f, 1.1f)) }
    val Toc = icon("toc") { s("M4 6h2M9 6h11M6.5 12h2M11.5 12h8.5M6.5 18h2M11.5 18h8.5") }
    val Sort = icon("sort") { s("M7 5v14M4 16l3 3 3-3M17 19V5M14 8l3-3 3 3") }
    val ArrowDown = icon("arrowDown", 1.9f) { s("M12 5v14M6.5 13.5L12 19l5.5-5.5") }
    val ArrowUp = icon("arrowUp", 1.9f) { s("M12 19V5M6.5 10.5L12 5l5.5 5.5") }
    /** 调节 / 自定义（三条横线带滑块） */
    val Tune = icon("tune") {
        s("M4 7h16M4 12h16M4 17h16")
        f(circle(15f, 7f, 2.2f)); f(circle(8.5f, 12f, 2.2f)); f(circle(16.5f, 17f, 2.2f))
    }
    /** 拖动把手 ≡ */
    val DragHandle = icon("dragHandle") { s("M5 8h14M5 12h14M5 16h14") }
    val Close = icon("close") { s("M6 6l12 12M18 6L6 18") }
    /** 齿轮（设置） */
    val Gear = icon("gear", 1.6f) { s(gear()); s(circle(12f, 12f, 3.1f)) }
    val Check = icon("check", 2f) { s("M5 12.5l4.5 4.5L19 7.5") }
    val CheckCircle = icon("checkCircle", 1.5f) { s(circle(12f, 12f, 8.6f)); s("M8.2 12.2l2.6 2.6 5-5.2") }
    val Doc = icon("doc") { s(DOC); s("M14 3.5V8h4.5"); s("M8.5 12.5h7M8.5 16h5") }
    /** 评论：带三个点的对话气泡 */
    private const val BUBBLE = "M5 4.5h14a2 2 0 0 1 2 2V15a2 2 0 0 1-2 2h-7.3L7 20.6V17H5a2 2 0 0 1-2-2V6.5a2 2 0 0 1 2-2z"
    val Comment = icon("comment") { s(BUBBLE); f(circle(8.3f, 10.8f, 1.1f)); f(circle(12f, 10.8f, 1.1f)); f(circle(15.7f, 10.8f, 1.1f)) }
    val CommentFilled = icon("commentFilled") {
        fe("M5 4h14a2.5 2.5 0 0 1 2.5 2.5V15a2.5 2.5 0 0 1-2.5 2.5h-7.1L7 21.2v-3.7H5A2.5 2.5 0 0 1 2.5 15V6.5A2.5 2.5 0 0 1 5 4z" +
            circle(8.2f, 10.8f, 1.3f) + circle(12f, 10.8f, 1.3f) + circle(15.8f, 10.8f, 1.3f))
    }
    val Camera = icon("camera") { s(rrect(3f, 7f, 18f, 13f, 2.5f)); s("M8.5 7l1.4-2.5h4.2L15.5 7"); s(circle(12f, 13.5f, 3.4f)) }
    /** 纯文字（T） */
    val TextT = icon("textT") { s("M5.5 7V5h13v2"); s("M12 5v14"); s("M9 19h6") }
    /** Markdown：方框里的 M 和向下箭头 */
    val Markdown = icon("markdown") {
        s(rrect(2.5f, 5.5f, 19f, 13f, 2.2f))
        s("M6 15V9l2.5 3L11 9v6")
        s("M16.5 9v5.8M14.3 12.8l2.2 2.2 2.2-2.2")
    }
    val Count = icon("count") { s("M5 6h14M5 12h14M5 18h8") }
    val OpenLink = icon("openLink") { s("M14 4h6v6"); s("M20 4l-9 9"); s("M18 14v4a2 2 0 0 1-2 2H6a2 2 0 0 1-2-2V8a2 2 0 0 1 2-2h4") }
    val KeyboardHide = icon("keyboardHide") {
        s(rrect(3f, 3.5f, 18f, 11f, 2f)); s("M7 7.5h.01M10.5 7.5h.01M14 7.5h.01M17 7.5h.01M7 11h10"); s("M9.5 18l2.5 2.5 2.5-2.5")
    }

    // 编辑器工具栏
    val TextStyle = icon("textStyle") {
        s("M3 18L7.5 6h1.5L13.5 18"); s("M4.8 14h6.9")
        s("M15.5 11.6c.6-.7 1.4-1.1 2.5-1.1 1.6 0 2.6.9 2.6 2.5V18")
        s("M20.6 14.6c-.6-.3-1.4-.5-2.3-.5-1.6 0-2.8.7-2.8 2 0 1.1.9 1.9 2.1 1.9 1.5 0 2.6-1 3-2.3")
    }
    val Undo = icon("undo") { s("M9 14L4 9l5-5"); s("M4 9h10.5a5.5 5.5 0 0 1 0 11H11") }
    val Redo = icon("redo") { s("M15 14l5-5-5-5"); s("M20 9H9.5a5.5 5.5 0 0 0 0 11H13") }
    val Bold = icon("bold", 2.2f) { s("M7 5h6a3.5 3.5 0 0 1 0 7H7z"); s("M7 12h7a3.5 3.5 0 0 1 0 7H7z") }
    val Italic = icon("italic") { s("M10 5h8M6 19h8M14 5l-4 14") }
    val Underline = icon("underline") { s("M7 4v7a5 5 0 0 0 10 0V4"); s("M5 20h14") }
    val Strike = icon("strike") {
        s("M4 12h16")
        s("M16.5 7.5C16 5.6 14.2 4.5 12 4.5c-2.6 0-4.5 1.4-4.5 3.5 0 1.3.7 2.3 2.2 3")
        s("M8 16.5c.6 1.9 2.4 3 4.5 3 2.7 0 4.5-1.4 4.5-3.6 0-.9-.3-1.7-1-2.4")
    }
    val Bullet = icon("bullet") { f(circle(5f, 6.5f, 1.3f)); f(circle(5f, 12f, 1.3f)); f(circle(5f, 17.5f, 1.3f)); s("M9.5 6.5H20M9.5 12H20M9.5 17.5H20") }
    val Numbered = icon("numbered") {
        s("M10.5 7h9.5M10.5 17h9.5"); s("M4.5 5l1.5-1v6")
        s("M3.8 14.6c.3-.6.9-.9 1.5-.9.8 0 1.4.5 1.4 1.2 0 1-2.9 2.2-2.9 3.6h3")
    }
    val Todo = icon("todo") {
        s(rrect(3.5f, 4f, 7f, 7f, 1.5f)); s("M5.3 7.6l1.4 1.4L9 6.5"); s(rrect(3.5f, 13.5f, 7f, 7f, 1.5f)); s("M14 7.5h6.5M14 17h6.5")
    }
    val Quote = icon("quote") { s("M5 6v12"); s("M9.5 8h9.5M9.5 12h9.5M9.5 16h6") }
    val Code = icon("code") { s("M8.5 7L3.5 12l5 5M15.5 7l5 5-5 5") }
    val Divider = icon("divider") { s("M3 12h18"); s("M7 7h10M7 17h10", .45f) }
    val Link = icon("link") {
        s("M10 14a4.5 4.5 0 0 0 6.4 0l3.2-3.2a4.5 4.5 0 0 0-6.4-6.4L11.8 5.8")
        s("M14 10a4.5 4.5 0 0 0-6.4 0l-3.2 3.2a4.5 4.5 0 0 0 6.4 6.4l1.4-1.4")
    }
    val Image = icon("image") { s(rrect(3.5f, 4.5f, 17f, 15f, 2f)); s(circle(9f, 10f, 1.6f)); s("M20.5 16l-5-5-9 8.5") }
    val Table = icon("table") { s(rrect(3.5f, 4.5f, 17f, 15f, 2f)); s("M3.5 10h17M3.5 15h17M10 4.5v15") }
    val TableEdit = icon("tableEdit") { s(rrect(3.5f, 4.5f, 17f, 15f, 2f)); s("M3.5 10h17M10 4.5v15"); s("M13.5 15h5M16 12.5v5") }
    val AlignLeft = icon("alignLeft") { s("M4 6h16M4 10h10M4 14h16M4 18h10") }
    val AlignCenter = icon("alignCenter") { s("M4 6h16M7 10h10M4 14h16M7 18h10") }
    val AlignRight = icon("alignRight") { s("M4 6h16M10 10h10M4 14h16M10 18h10") }
    val Indent = icon("indent") { s("M4 6h16M11 10h9M11 14h9M4 18h16"); s("M4 9.5l3 2.5-3 2.5") }
    val Outdent = icon("outdent") { s("M4 6h16M11 10h9M11 14h9M4 18h16"); s("M7 9.5L4 12l3 2.5") }
    val ClearFormat = icon("clearFormat") { s("M5 7V5h12v2"); s("M11 5l-3 14"); s("M14.5 14.5l5 5M19.5 14.5l-5 5") }
}
