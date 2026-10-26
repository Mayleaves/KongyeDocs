package com.kongye.docs.data

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class Template(
    val id: String,
    val name: String,
    val desc: String,
    val accent: Long,
    val title: String,
    val content: () -> DocContent,
)

object Templates {
    const val RED = 0xFFF54A45
    const val BLUE = 0xFF245BDB
    const val YELLOW_BG = 0xFFF8E6AB
    const val GREEN_BG = 0xFFB7EDB1

    private fun today(pattern: String = "yyyy年M月d日") = SimpleDateFormat(pattern, Locale.CHINA).format(Date())
    private fun weekday() = SimpleDateFormat("EEEE", Locale.CHINA).format(Date())

    private fun doc(vararg blocks: Block) = DocContent(blocks.toList())
    private fun t(text: String = "") = Block(type = BlockType.TEXT, text = text)
    private fun h2(text: String) = Block(type = BlockType.H2, text = text)
    private fun li(text: String, indent: Int = 0) = Block(type = BlockType.BULLET, text = text, indent = indent)
    private fun num(text: String) = Block(type = BlockType.NUMBER, text = text)
    private fun todo(text: String, checked: Boolean = false) = Block(type = BlockType.TODO, text = text, checked = checked)
    private fun quote(text: String) = Block(type = BlockType.QUOTE, text = text)
    private fun hr() = Block(type = BlockType.DIVIDER)
    private fun table(vararg rows: List<String>) = Block(type = BlockType.TABLE, table = rows.toList())

    private class Seg(val text: String, val styles: List<InlineStyle>, val color: Long)

    private fun s(text: String, vararg styles: InlineStyle, color: Long = 0) = Seg(text, styles.toList(), color)

    private fun rich(vararg segs: Seg, type: BlockType = BlockType.TEXT): Block {
        val sb = StringBuilder()
        val spans = mutableListOf<StyleRange>()
        segs.forEach { seg ->
            val start = sb.length
            sb.append(seg.text)
            seg.styles.forEach { spans += StyleRange(start, sb.length, it, seg.color) }
        }
        return Block(type = type, text = sb.toString(), spans = spans)
    }

    /** “标签：内容”，标签加粗 */
    private fun kv(label: String, value: String = "") = rich(s("$label：", InlineStyle.BOLD), s(value))

    /** 新装时唯一的一篇文档：使用说明（带一条示例评论） */
    fun welcome(): Pair<String, DocContent> {
        val thread = CommentThread(
            comments = listOf(Comment(text = "这是一条示例评论。点正文里带下划线的文字就能查看，在这里可以回复，也可以结束评论。")),
        )
        val example = "像这样加一条评论"
        val commentLine = rich(s("选中一段文字，点工具栏上的评论按钮，就能"), s(example), s("。"))
            .let { b ->
                val start = b.text.indexOf(example)
                b.copy(spans = b.spans + StyleRange(start, start + example.length, InlineStyle.COMMENT, url = thread.id))
            }
        return "欢迎使用空叶文档" to DocContent(
            listOf(
                t("空叶文档是一款简洁的笔记应用：无需注册、无需联网，所有内容都只保存在这台手机里。这篇说明带你快速上手，读完可以删掉。"),
                h2("开始写作"),
                num("点右下角的 + 新建文档或文件夹，也可以从模板库挑一个模板开始"),
                num("编辑时，键盘上方是工具栏：「Aa」设置标题、加粗和颜色，「段落」切换列表、缩进和首行缩进，「+」插入图片、表格、链接和代码块"),
                num("工具栏的顺序可以在「我的 → 编辑工具栏」里调整"),
                rich(
                    s("正文支持 "), s("加粗", InlineStyle.BOLD), s("、"), s("斜体", InlineStyle.ITALIC), s("、"),
                    s("下划线", InlineStyle.UNDERLINE), s("、"), s("删除线", InlineStyle.STRIKE), s("、"),
                    s("彩色文字", InlineStyle.COLOR, color = RED), s(" 和 "), s("背景高亮", InlineStyle.HIGHLIGHT, color = YELLOW_BG), s("。"),
                ),
                h2("Markdown 快捷输入"),
                li("# 加空格是一级标题，## 是二级标题，### 是三级标题"),
                li("- 加空格开始列表，1. 加空格开始编号列表，[] 加空格创建待办"),
                li("> 加空格是引用，``` 加空格是代码块，--- 加空格是分割线"),
                h2("整理文件"),
                li("长按文件进入多选，可以批量收藏、移动或删除"),
                li("在「文件」里长按后直接拖动，可以调整顺序（会切换为手动排序）"),
                li("首页左右滑动，在「最近」「收藏」「创建」之间切换"),
                li("搜索能找到标题、正文和评论里的文字"),
                h2("评论与版本"),
                commentLine,
                li("文档右上角的 ··· 里可以查看目录、查找替换、查看历史和本地版本，改错或误删的内容也能找回来"),
                h2("分享与备份"),
                li("··· 里可以把文档分享为图片、文字或 Markdown，图片底部的落款可以自定义"),
                li("「我的 → 本地备份 / 云端备份」可以定期自动备份，换手机时从备份恢复"),
                li("删除的文件会先放进回收站，彻底删除前都能恢复"),
                h2("试一试"),
                todo("读完这篇说明", true),
                todo("新建第一篇文档"),
                todo("把常用的文档加入收藏"),
                todo("打开自动备份"),
                hr(),
                quote("小秘密：「我的」里的小猫和「关于」里的叶子，都可以点一点。"),
            ),
            comments = listOf(thread),
        )
    }

    val all: List<Template> = listOf(
        Template("blank", "空白文档", "从零开始自由书写", 0xFF417FF9, "") { DocContent() },
        Template("meeting", "会议纪要", "记录议题、结论和待办", 0xFF3D7EFF, "会议纪要") {
            doc(
                kv("会议时间", today("yyyy年M月d日 HH:mm")), kv("会议地点"), kv("主持人"), kv("参会人员"), kv("记录人"),
                h2("会议议题"), num("议题一"), num("议题二"),
                h2("讨论内容"), li("要点"), li("要点"),
                h2("会议结论"), t(),
                h2("待办事项"),
                table(listOf("事项", "负责人", "截止时间"), listOf("", "", ""), listOf("", "", "")),
            )
        },
        Template("weekly", "工作周报", "总结本周，规划下周", 0xFF2EA121, "工作周报 · ${today("M月d日")}") {
            doc(
                h2("本周完成"), todo("已完成的工作", true), todo("已完成的工作", true),
                h2("进行中"), li("工作内容（进度 50%）"),
                h2("下周计划"), todo("计划一"), todo("计划二"),
                h2("问题与风险"), t(),
                quote("本周心得："),
            )
        },
        Template("reading", "读书笔记", "摘录、感想与行动", 0xFFFF8800, "读书笔记") {
            doc(
                kv("书名"), kv("作者"), kv("阅读日期", today()), kv("推荐指数", "★★★★☆"),
                h2("内容概要"), t(),
                h2("精彩摘录"), quote("摘录一"), quote("摘录二"),
                h2("我的思考"), t(),
                h2("行动清单"), todo("要实践的一件事"),
            )
        },
        Template("todo", "待办清单", "今天、本周、以后", 0xFF7F3BF5, "待办清单") {
            doc(
                h2("今天 · ${today("M月d日")}"), todo("最重要的一件事"), todo(""),
                h2("本周"), todo(""),
                h2("以后再说"), todo(""),
            )
        },
        Template("diary", "日记", "记录每一天", 0xFFF54A45, today()) {
            doc(
                rich(s("${today()}  ${weekday()}", InlineStyle.COLOR, color = 0xFF8F959E)),
                kv("天气"), kv("心情"),
                h2("今天发生了什么"), t(),
                h2("值得感谢的三件事"), num(""), num(""), num(""),
                h2("明天想做"), todo(""),
            )
        },
        Template("travel", "旅行计划", "行程、清单与预算", 0xFF14C0D1, "旅行计划") {
            doc(
                kv("目的地"), kv("出行日期"), kv("同行"),
                h2("行程安排"),
                table(listOf("日期", "城市", "安排"), listOf("第 1 天", "", ""), listOf("第 2 天", "", ""), listOf("第 3 天", "", "")),
                h2("行李清单"), todo("证件"), todo("充电器"), todo("换洗衣物"), todo("常用药品"),
                h2("预算"),
                table(listOf("项目", "金额"), listOf("交通", ""), listOf("住宿", ""), listOf("餐饮", "")),
            )
        },
        Template("project", "项目计划", "目标、里程碑与分工", 0xFF245BDB, "项目计划") {
            doc(
                h2("项目背景"), t(),
                h2("项目目标"), num("目标一"), num("目标二"),
                h2("里程碑"),
                table(listOf("阶段", "内容", "负责人", "时间"), listOf("需求", "", "", ""), listOf("开发", "", "", ""), listOf("上线", "", "", "")),
                h2("风险与应对"), li("风险："), li("应对："),
            )
        },
    )

    fun byId(id: String) = all.firstOrNull { it.id == id }
}
