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
        fun root(): AccessibilityNodeInfo? {
            if(android.os.Build.VERSION.SDK_INT>=33) auto.clearCache()
            return auto.windows.mapNotNull { it.root }.firstOrNull { it.packageName?.toString()==inst.targetContext.packageName } ?: auto.rootInActiveWindow
        }
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
                waitFor("content $mode") { walk(root(), if (mode == AutofillActivity.MODE_MORE) "搜索密码空间" else "长度：16 字符") != null }
                if(mode==AutofillActivity.MODE_MORE)
                    waitFor("search keyboard stays open") { auto.windows.any { it.type == AccessibilityWindowInfo.TYPE_INPUT_METHOD } }
                else {
                    Thread.sleep(600)
                    check(auto.windows.none { it.type==AccessibilityWindowInfo.TYPE_INPUT_METHOD }) { "Generator unexpectedly opens keyboard" }
                }
                if(mode==AutofillActivity.MODE_MORE) {
                    fun top(): Int { val rect=android.graphics.Rect();walk(root(),"选择要填入的密码")!!.getBoundsInScreen(rect);return rect.top }
                    val before=top()
                    inst.runOnMainSync { activity.getSystemService(android.view.inputmethod.InputMethodManager::class.java).hideSoftInputFromWindow(activity.window.decorView.windowToken,0) }
                    waitFor("hide search keyboard") { auto.windows.none { it.type==AccessibilityWindowInfo.TYPE_INPUT_METHOD } }
                    check(kotlin.math.abs(top()-before)<3) { "Search title moved when hiding keyboard" }
                    inst.runOnMainSync { activity.getSystemService(android.view.inputmethod.InputMethodManager::class.java).showSoftInput(activity.currentFocus,android.view.inputmethod.InputMethodManager.SHOW_IMPLICIT) }
                    waitFor("show search keyboard") { auto.windows.any { it.type==AccessibilityWindowInfo.TYPE_INPUT_METHOD } }
                    check(kotlin.math.abs(top()-before)<3) { "Search title moved when showing keyboard" }
                }
                if (mode == AutofillActivity.MODE_GENERATE) {
                    fun click(label: String) {
                        var node=walk(root(),label) ?: error("Missing $label")
                        while(!node.isClickable && node.parent!=null) node=node.parent
                        check(node.performAction(AccessibilityNodeInfo.ACTION_CLICK));Thread.sleep(120)
                    }
                    click("增加密码长度")
                    waitFor("length plus") { walk(root(),"长度：17 字符")!=null }
                    click("减少密码长度")
                    waitFor("length minus") { walk(root(),"长度：16 字符")!=null }
                    check(auto.windows.none { it.type==AccessibilityWindowInfo.TYPE_INPUT_METHOD })
                    // Only generated test data is shown; production keeps FLAG_SECURE.
                    inst.runOnMainSync { activity.window.clearFlags(android.view.WindowManager.LayoutParams.FLAG_SECURE) }
                    Thread.sleep(300)
                    auto.takeScreenshot()?.let { bitmap ->
                        java.io.File(inst.targetContext.getExternalFilesDir(null),"v35-generator.png").outputStream().use { bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG,100,it) }; bitmap.recycle()
                    }
                    inst.runOnMainSync {
                        runCatching {
                            val view=activity.window.decorView
                            val bitmap=android.graphics.Bitmap.createBitmap(view.width,view.height,android.graphics.Bitmap.Config.ARGB_8888)
                            val canvas=android.graphics.Canvas(bitmap);canvas.drawColor(android.graphics.Color.LTGRAY);view.draw(canvas)
                            java.io.File(inst.targetContext.getExternalFilesDir(null),"v35-generator-view.png").outputStream().use { bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG,100,it) };bitmap.recycle()
                        }
                    }
                    fun scroll(n: AccessibilityNodeInfo?): Boolean {
                        n ?: return false
                        if(n.isScrollable && n.className?.toString()!="android.widget.EditText" && n.performAction(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD)) return true
                        for(i in 0 until n.childCount) if(scroll(n.getChild(i))) return true
                        return false
                    }
                    for (label in listOf("大写字母", "小写字母", "常用数字")) {
                        repeat(6) { if(walk(root(),label)==null) { scroll(root());Thread.sleep(250) } }
                        check(walk(root(),label)!=null) { "Missing generator option: $label" }
                    }
                }
            } finally { inst.runOnMainSync { activity.finish() } }
        }
    }
}
