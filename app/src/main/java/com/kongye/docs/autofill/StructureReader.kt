package com.kongye.docs.autofill

import android.app.assist.AssistStructure
import android.view.View
import android.view.autofill.AutofillId
import com.kongye.docs.vault.VaultApi

/**
 * 把系统交来的页面结构（AssistStructure）读成 Page：可输入的框和页面上的文字，按出现顺序排列。
 * 只在 onFillRequest / onSaveRequest 里用，读完就丢，不保存页面内容。
 */
object StructureReader {
    private const val MAX_NODES = 4000
    private const val MAX_TEXTS = 300

    private sealed class Item {
        class Field(val node: AssistStructure.ViewNode, val label: String) : Item()
        class Text(val text: String, val button: Boolean) : Item()
    }

    /** 提交按钮（用来触发保存）：按钮文字像"登录 / 注册 / 提交 / 下一步" */
    data class Read(val page: Page<AutofillId>, val submit: AutofillId?)

    private val SUBMIT = Regex("""^(?:立即|免费|快速)?(?:登\s*录|注\s*册|提交|确定|确认|完成|下一步|保存|继续|创建(?:账号|帐号|账户)?|修改|重置|登录/注册)$|^(?:log\s*in|sign\s*in|sign\s*up|register|submit|continue|next|save|create\s+account|done|ok)$""", RegexOption.IGNORE_CASE)

    fun read(structure: AssistStructure): Read {
        val activity = structure.activityComponent
        val pkg = activity?.packageName.orEmpty()
        val items = ArrayList<Item>()
        var domain = ""
        var submit: AutofillId? = null
        var count = 0

        fun visit(node: AssistStructure.ViewNode) {
            if (++count > MAX_NODES) return
            if (node.visibility != View.VISIBLE) return
            if (domain.isEmpty()) node.webDomain?.let { d -> VaultApi.host(d)?.let { domain = it } }
            val html = node.htmlInfo
            val attrs = html?.attributes?.associate { it.first.lowercase() to it.second.orEmpty() }.orEmpty()
            val tag = html?.tag?.lowercase().orEmpty()
            val type = attrs["type"]?.lowercase().orEmpty()
            val className = node.className.orEmpty()
            val text = sequenceOf(node.text?.toString(), node.contentDescription?.toString(), attrs["aria-label"], attrs["title"])
                .mapNotNull { it?.trim()?.takeIf(String::isNotEmpty) }.firstOrNull().orEmpty()
            val isInput = node.autofillId != null && node.autofillType == View.AUTOFILL_TYPE_TEXT && (
                className.contains("EditText") || node.inputType != 0 ||
                    tag == "input" && type !in setOf("hidden", "submit", "button", "checkbox", "radio", "image", "reset", "file", "range", "color", "search") ||
                    tag == "textarea")
            val isButton = className.contains("Button") || tag == "button" || tag == "input" && type in setOf("submit", "button") ||
                node.isClickable && text.isNotEmpty() && text.length <= 12 && !isInput
            when {
                isInput -> {
                    val label = listOfNotNull(
                        node.hint, node.idEntry, node.contentDescription?.toString(),
                        attrs["placeholder"], attrs["aria-label"], attrs["name"], attrs["id"], attrs["title"], attrs["label"],
                    ).filter { it.isNotBlank() }.joinToString(" ")
                    items += Item.Field(node, label)
                }
                text.isNotEmpty() && items.size < MAX_TEXTS -> {
                    val label = if (isButton) text else text.take(200)
                    items += Item.Text(label, isButton)
                    if (isButton && submit == null && SUBMIT.matches(text)) submit = node.autofillId
                }
                isButton && attrs["value"]?.isNotBlank() == true -> {
                    val v = attrs["value"]!!.trim()
                    items += Item.Text(v, true)
                    if (submit == null && SUBMIT.matches(v)) submit = node.autofillId
                }
            }
            for (i in 0 until node.childCount) visit(node.getChildAt(i))
        }
        for (w in 0 until structure.windowNodeCount) visit(structure.getWindowNodeAt(w).rootViewNode)

        // 每个框：前面紧挨着的一段短文字算标签，后面到下一个框之前的最多 3 段文字算"附近文字"
        val fields = ArrayList<PageField<AutofillId>>()
        items.forEachIndexed { i, item ->
            if (item !is Item.Field) return@forEachIndexed
            val node = item.node
            val before = (items.getOrNull(i - 1) as? Item.Text)?.takeIf { !it.button && it.text.length <= 40 }?.text.orEmpty()
            val after = items.drop(i + 1).takeWhile { it !is Item.Field }.filterIsInstance<Item.Text>().filter { !it.button }.take(3).joinToString("\n") { it.text }
            val html = node.htmlInfo
            val attrs = html?.attributes?.associate { it.first.lowercase() to it.second.orEmpty() }.orEmpty()
            fields += PageField(
                id = node.autofillId!!,
                hints = node.autofillHints?.toList().orEmpty(),
                htmlType = attrs["type"]?.lowercase().orEmpty(),
                autocomplete = attrs["autocomplete"]?.lowercase()?.trim().orEmpty(),
                inputType = node.inputType,
                label = listOf(item.label, before).filter { it.isNotBlank() }.joinToString(" "),
                nearby = after,
                htmlAttrs = attrs,
                focused = node.isFocused,
                value = node.autofillValue?.takeIf { it.isText }?.textValue?.toString().orEmpty(),
            )
        }
        val texts = items.filterIsInstance<Item.Text>().map { PageText(it.text, it.button) }
        val registrationScreen = domain.isEmpty() && Page.isRegistrationScreen(activity?.className.orEmpty())
        return Read(Page(pkg, domain, fields, texts, registrationScreen), submit)
    }
}
