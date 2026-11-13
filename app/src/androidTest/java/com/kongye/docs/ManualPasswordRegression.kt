package com.kongye.docs

import android.app.Instrumentation
import android.content.Intent
import android.os.SystemClock
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo
import com.kongye.docs.autofill.AutofillActivity
import com.kongye.docs.vault.ManualPassword
import com.kongye.docs.vault.PasswordRules
import com.kongye.docs.vault.lengthHint

object ManualPasswordRegression {
    fun run(inst: Instrumentation) {
        for (mask in 1..31) for (length in listOf(4, 5, 8, 16, 20, 64)) {
            val option = ManualPassword(length, mask and 1 != 0, mask and 2 != 0, mask and 4 != 0, mask and 8 != 0, mask and 16 != 0)
            if (option.groups.size > length) continue
            repeat(20) {
                val p = option.generate()
                check(p.length == length)
                check(p.all { c -> option.groups.any { c in it } })
                check(option.groups.all { group -> p.any { it in group } })
                if (!option.ambiguous) check(p.none { it in "10iolIOL" })
            }
        }
        check(runCatching { ManualPassword(upper=false, lower=false, digits=false, symbols=false).generate() }.isFailure)
        check(runCatching { ManualPassword(length=4, ambiguous=true).generate() }.isFailure)
        check(PasswordRules.parse("密码要求8-20字符").lengthHint() == "页面长度：8–20 位")
        check(PasswordRules().lengthHint() == "页面未提供长度范围")
        inst.runOnMainSync {
            val row = android.widget.RemoteViews(inst.targetContext.packageName, R.layout.autofill_item)
            row.setTextViewText(R.id.autofill_title, "测试账号")
            row.setViewVisibility(R.id.autofill_app_icon, android.view.View.VISIBLE)
            val view = row.apply(inst.targetContext, null)
            check(view.findViewById<android.widget.TextView>(R.id.autofill_title).text == "测试账号")
        }
        val auto = inst.uiAutomation
        auto.serviceInfo = auto.serviceInfo.apply { flags = flags or android.accessibilityservice.AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS }
        fun walk(n: AccessibilityNodeInfo?, label: String): AccessibilityNodeInfo? {
            n ?: return null
            if (n.text?.toString() == label || n.contentDescription?.toString() == label) return n
            for (i in 0 until n.childCount) walk(n.getChild(i), label)?.let { return it }
            return null
        }
        fun waitFor(label: String, condition: () -> Boolean) {
            val until = SystemClock.uptimeMillis() + 10000
            while (SystemClock.uptimeMillis() < until) { if (condition()) return; Thread.sleep(80) }
            error("Timed out: $label")
        }
        for (mode in listOf(AutofillActivity.MODE_MORE, AutofillActivity.MODE_GENERATE)) {
            val activity = inst.startActivitySync(Intent(inst.targetContext, AutofillActivity::class.java)
                .putExtra(AutofillActivity.EXTRA_MODE, mode).putExtra(AutofillActivity.EXTRA_LABEL, "界面测试")
                .putExtra(AutofillActivity.EXTRA_RULES, PasswordRules(minLength=8, maxLength=20).toBundle())
                .putExtra(AutofillActivity.EXTRA_RULES_STATED, true).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            try {
                waitFor("content $mode") { walk(auto.rootInActiveWindow, if (mode == AutofillActivity.MODE_MORE) "搜索密码空间" else "长度：16 字符") != null }
                waitFor("keyboard stays open $mode") { auto.windows.any { it.type == AccessibilityWindowInfo.TYPE_INPUT_METHOD } }
                if (mode == AutofillActivity.MODE_GENERATE) {
                    for (label in listOf("大写字母 ( A → Z )", "小写字母 ( a → z )", "常用数字 ( 2 → 9 )")) check(walk(auto.rootInActiveWindow, label) != null)
                }
            } finally { inst.runOnMainSync { activity.finish() } }
        }
    }
}
