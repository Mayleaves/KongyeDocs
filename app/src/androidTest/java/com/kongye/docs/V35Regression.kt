package com.kongye.docs

import android.app.Instrumentation
import android.content.Intent
import android.os.SystemClock
import android.view.accessibility.AccessibilityNodeInfo
import com.kongye.docs.data.*
import com.kongye.docs.ui.AppState
import com.kongye.docs.ui.Screen
import com.kongye.docs.ui.Tab
import com.kongye.docs.autofill.AutofillActivity
import java.io.File

object V35Regression {
    fun run(inst: Instrumentation,probe: String?=null,pass: (String)->Unit) {
        inst.uiAutomation.serviceInfo=inst.uiAutomation.serviceInfo.apply { flags=flags or android.accessibilityservice.AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS }
        fun <T> main(action: ()->T): T { var result: Result<T>?=null;inst.runOnMainSync { result=runCatching(action) };return result!!.getOrThrow() }
        fun find(label: String): AccessibilityNodeInfo? {
            if(android.os.Build.VERSION.SDK_INT>=33) inst.uiAutomation.clearCache()
            fun walk(node: AccessibilityNodeInfo?): AccessibilityNodeInfo? {
                node ?: return null
                if(node.text?.contains(label)==true || node.contentDescription?.contains(label)==true) return node
                for(i in 0 until node.childCount) walk(node.getChild(i))?.let { return it }
                return null
            }
            return inst.uiAutomation.windows.mapNotNull { it.root }.firstNotNullOfOrNull { walk(it) }
        }
        fun waitFor(label: String,condition: ()->Boolean) {
            val end=SystemClock.uptimeMillis()+12000
            while(SystemClock.uptimeMillis()<end) { if(condition()) return;Thread.sleep(60) }
            error("Timed out: $label")
        }
        val context=inst.targetContext
        val history=ReleaseHistory.read(context)
        check(history.size>20 && history.any { it.version=="3.4.2" && it.notes.contains("长图光标") })
        check(history.zipWithNext().all { (a,b) -> AppUpdates.compare(a.version,b.version)>0 })
        check(ReleaseHistory.parse("## v1.2 — 2026-01-02\n**粗体**\n## v1.10\n- 更新").map { it.version }==listOf("1.10","1.2"))
        pass("bundled-history-keeps-older-markdown-details-and-sorts-versions-numerically")
        main {
            val method=com.kongye.docs.autofill.KongyeAutofillService::class.java.getDeclaredMethod("presentation",String::class.java,String::class.java,String::class.java,String::class.java,Boolean::class.javaPrimitiveType).apply { isAccessible=true }
            val service=com.kongye.docs.autofill.KongyeAutofillService()
            val attach=android.content.ContextWrapper::class.java.getDeclaredMethod("attachBaseContext",android.content.Context::class.java).apply { isAccessible=true }
            attach.invoke(service,context)
            val account=(method.invoke(service,"示例账号","示例 · ********","示","org.example",false) as android.widget.RemoteViews).apply(context,null)
            val more=(method.invoke(service,"更多密码","搜索","⋯","org.example",true) as android.widget.RemoteViews).apply(context,null)
            val alone=(method.invoke(service,"更多密码","搜索","⋯","org.example",false) as android.widget.RemoteViews).apply(context,null)
            check(alone.findViewById<android.view.View>(R.id.autofill_section_divider).visibility==android.view.View.GONE)
            check(account.findViewById<android.view.View>(R.id.autofill_divider).visibility==android.view.View.VISIBLE)
            check(more.findViewById<android.view.View>(R.id.autofill_section_divider).visibility==android.view.View.VISIBLE)
            check(more.findViewById<android.view.View>(R.id.autofill_section_divider).layoutParams.height>account.findViewById<android.view.View>(R.id.autofill_divider).layoutParams.height)
            check(!account.isFocusable && account.findViewById<android.view.View>(R.id.autofill_section_divider).visibility==android.view.View.GONE)
        }
        pass("account-rows-have-thin-gray-lines-more-passwords-has-full-width-thicker-separator")
        if(android.os.Build.VERSION.SDK_INT>=30) main {
            val pending=android.app.PendingIntent.getActivity(context,998,Intent(context,AutofillActivity::class.java),android.app.PendingIntent.FLAG_IMMUTABLE)
            val style=androidx.autofill.inline.UiVersions.newStylesBuilder().addStyle(androidx.autofill.inline.v1.InlineSuggestionUi.newStyleBuilder().build()).build()
            val spec=android.widget.inline.InlinePresentationSpec.Builder(android.util.Size(80,32),android.util.Size(300,40)).setStyle(style).build()
            val chip=android.service.autofill.InlinePresentation(androidx.autofill.inline.v1.InlineSuggestionUi.newContentBuilder(pending).setTitle("更多密码").build().slice,spec,false)
            val dataset=com.kongye.docs.autofill.KongyeAutofillService().inlineDataset(listOf(android.widget.EditText(context).autofillId),chip,pending.intentSender)
            fun presentation(name: String)=android.service.autofill.Dataset::class.java.getDeclaredMethod(name,Int::class.javaPrimitiveType).apply { isAccessible=true }.invoke(dataset,0)
            check(presentation("getFieldPresentation")==null && presentation("getFieldDialogPresentation")==null)
            check(presentation("getFieldInlinePresentation")!=null)
            val service=com.kongye.docs.autofill.KongyeAutofillService()
            check(service.keyboardCandidatesOnly("com.kongye.keyboard/.KongyeIME"))
            check(service.keyboardCandidatesOnly("com.kongye.keyboard.debug/.KongyeIME"))
            check(!service.keyboardCandidatesOnly("com.iflytek.inputmethod/.FlyIME"))
            check(!service.keyboardCandidatesOnly(""))
            val dropdown=service.menuDataset(listOf(android.widget.EditText(context).autofillId),android.widget.RemoteViews(context.packageName,R.layout.autofill_item),pending.intentSender)
            check(android.service.autofill.Dataset::class.java.getDeclaredMethod("getFieldPresentation",Int::class.javaPrimitiveType).invoke(dropdown,0)!=null)
            check(android.service.autofill.Dataset::class.java.getDeclaredMethod("getFieldInlinePresentation",Int::class.javaPrimitiveType).invoke(dropdown,0)==null)
        }
        pass("autofill-dataset-has-only-keyboard-presentation-no-floating-dropdown-or-dialog")
        val activity=if(probe=="generator") {
            inst.startActivitySync(Intent(context,AutofillActivity::class.java).putExtra(AutofillActivity.EXTRA_MODE,AutofillActivity.MODE_GENERATE).putExtra(AutofillActivity.EXTRA_LABEL,"界面样式测试").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        } else {
            main { AppState.goHome(Tab.HOME) }
            startTestActivity(inst,context).also { main { AppState.push(Screen.UpdateHistory) } }
        }
        try {
            if(probe=="generator") {
                waitFor("generator") { find("生成随机密码")!=null }
            } else {
                waitFor("history") { find("v3.4.2")!=null }
                fun click(label: String) {
                    var node=find(label) ?: error("Missing $label")
                    while(!node.isClickable && node.parent!=null) node=node.parent
                    check(node.performAction(AccessibilityNodeInfo.ACTION_CLICK))
                }
                click("下一页")
                waitFor("next history page") { find("第 2 /")!=null }
                click("上一页")
                waitFor("previous history page") { find("第 1 /")!=null }
                fun query(text: String) {
                    val bounds=android.graphics.Rect();find("搜索更新历史")!!.getBoundsInScreen(bounds)
                    inst.uiAutomation.executeShellCommand("input tap ${bounds.centerX()} ${bounds.centerY()}").use { java.io.FileInputStream(it.fileDescriptor).use { input -> input.readBytes() } }
                    Thread.sleep(150)
                    fun editable(node: AccessibilityNodeInfo?): AccessibilityNodeInfo? {
                        node ?: return null
                        if(node.isEditable && node.actionList.any { it.id==AccessibilityNodeInfo.ACTION_SET_TEXT }) return node
                        for(n in 0 until node.childCount) editable(node.getChild(n))?.let { return it }
                        return null
                    }
                    val search=inst.uiAutomation.windows.mapNotNull { it.root }.filter { it.packageName?.toString()==context.packageName }.firstNotNullOfOrNull { editable(it) }
                        ?: error("No editable history search field")
                    search.performAction(AccessibilityNodeInfo.ACTION_FOCUS)
                    check(search.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT,android.os.Bundle().apply {
                        putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE,text)
                    })) { "Search node class=${search.className} editable=${search.isEditable} focused=${search.isFocused} actions=${search.actionList.map { it.id }} bounds=$bounds children=${search.childCount}" }
                }
                query("3.4.2")
                waitFor("history version search") { find("共 1 个版本")!=null && find("v3.4.2")!=null }
                query("no-history-result-92381")
                waitFor("empty history results") { find("没有找到相关更新")!=null && find("第 1 / 1 页")!=null }
                query("长图光标滚动")
                waitFor("history notes search") { find("v3.4.2")!=null }
                pass("history-searches-versions-and-notes-pages-and-handles-empty-results")
                var node=find("v3.4.2")!!
                while(!node.isClickable && node.parent!=null) node=node.parent
                check(node.performAction(AccessibilityNodeInfo.ACTION_CLICK))
                waitFor("history details") { find("v3.4.2 更新详情")!=null && find("长图光标滚动")!=null }
                check(find("**长图光标滚动**")==null)
                pass("history-opens-full-markdown-detail-without-showing-raw-bold-markers")
            }
            if(probe!=null) {
                val stop=File(context.getExternalFilesDir(null),"v35-ui-stop").also { it.delete() }
                val end=SystemClock.uptimeMillis()+120000
                while(SystemClock.uptimeMillis()<end && !stop.exists()) Thread.sleep(150)
            }
        } finally { main { AppState.goHome(Tab.HOME);activity.finish() } }
    }
}
