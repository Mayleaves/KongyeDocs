package com.kongye.docs

import android.app.Instrumentation
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.graphics.Rect
import android.os.Bundle
import android.os.SystemClock
import android.view.InputDevice
import android.view.MotionEvent
import android.view.accessibility.AccessibilityNodeInfo
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import com.kongye.docs.autofill.AutofillActivity
import com.kongye.docs.autofill.PendingSaves
import com.kongye.docs.data.*
import com.kongye.docs.ui.theme.KongyeTheme
import com.kongye.docs.ui.vault.VaultPage
import com.kongye.docs.vault.VaultApi
import java.io.File

internal object NewVaultUiRegression {
    fun run(inst: Instrumentation,saveOnly: Boolean=false,pass: (String)->Unit) {
        fun <T> main(action: ()->T): T { var r: Result<T>?=null; inst.runOnMainSync { r=runCatching(action) }; return r!!.getOrThrow() }
        val ctx=inst.targetContext
        val activity=startTestActivity(inst,ctx)
        val fixture=object: ContextWrapper(ctx) {
            val root=File(ctx.filesDir,"new-vault-ui-${newId()}").apply { mkdirs() }
            override fun getFilesDir()=root
            override fun getApplicationContext(): Context=this
        }
        val auto=inst.uiAutomation
        var saving: android.app.Activity?=null
        auto.serviceInfo=auto.serviceInfo.apply { flags=flags or android.accessibilityservice.AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS }
        fun find(label: String): AccessibilityNodeInfo? {
            auto.clearCache()
            fun walk(n: AccessibilityNodeInfo): AccessibilityNodeInfo? {
                if(!n.isVisibleToUser) return null
                if(n.text?.toString()==label || n.contentDescription?.toString()==label) return n
                for(i in 0 until n.childCount) n.getChild(i)?.let { walk(it)?.let { return it } }
                return null
            }
            return auto.windows.mapNotNull { it.root }.filter { it.packageName?.toString()==ctx.packageName }.firstNotNullOfOrNull(::walk)
        }
        fun waitFor(label: String,test: ()->Boolean) {
            val until=SystemClock.uptimeMillis()+16000
            while(SystemClock.uptimeMillis()<until) { if(test()) return; Thread.sleep(50) }
            error("Timed out: $label")
        }
        fun scroll() {
            auto.clearCache()
            fun walk(n: AccessibilityNodeInfo): Boolean {
                if(n.isScrollable && n.performAction(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD)) return true
                for(i in 0 until n.childCount) n.getChild(i)?.let { if(walk(it)) return true }
                return false
            }
            auto.windows.mapNotNull { it.root }.firstOrNull { walk(it) }
            Thread.sleep(200)
        }
        fun locate(label: String): AccessibilityNodeInfo {
            repeat(6) { find(label)?.let { return it }; scroll() }
            error("Missing $label")
        }
        fun click(label: String) {
            var n=locate(label)
            while(!n.isClickable) n=n.parent ?: error("No click for $label")
            check(n.performAction(AccessibilityNodeInfo.ACTION_CLICK))
        }
        fun longPress(label: String) {
            Thread.sleep(350)
            val bounds=Rect().also { locate(label).getBoundsInScreen(it) }
            val down=SystemClock.uptimeMillis()
            for(action in listOf(MotionEvent.ACTION_DOWN,MotionEvent.ACTION_UP)) {
                val event=MotionEvent.obtain(down,SystemClock.uptimeMillis(),action,bounds.exactCenterX(),bounds.exactCenterY(),0)
                    .apply { source=InputDevice.SOURCE_TOUCHSCREEN }
                check(auto.injectInputEvent(event,true)); event.recycle()
                if(action==MotionEvent.ACTION_DOWN) Thread.sleep(android.view.ViewConfiguration.getLongPressTimeout()+250L)
            }
        }
        fun swipe(right: Boolean) {
            val width=activity.window.decorView.width.toFloat()
            val y=activity.window.decorView.height*.50f
            val from=if(right) width*.22f else width*.78f
            val to=if(right) width*.78f else width*.22f
            val down=SystemClock.uptimeMillis()
            for(step in 0..15) {
                val action=when(step) { 0 -> MotionEvent.ACTION_DOWN; 15 -> MotionEvent.ACTION_UP; else -> MotionEvent.ACTION_MOVE }
                val event=MotionEvent.obtain(down,SystemClock.uptimeMillis(),action,from+(to-from)*step/15f,y,0).apply { source=InputDevice.SOURCE_TOUCHSCREEN }
                check(auto.injectInputEvent(event,true)); event.recycle(); Thread.sleep(20)
            }
            Thread.sleep(450)
        }
        fun text(label: String,value: String,expected: String) {
            locate(label).performAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_SHOW_ON_SCREEN.id)
            Thread.sleep(150)
            fun input(view: android.view.View): android.view.inputmethod.InputConnection? {
                if(view.onCheckIsTextEditor()) view.onCreateInputConnection(android.view.inputmethod.EditorInfo())?.let { return it }
                if(view is android.view.ViewGroup) for(i in 0 until view.childCount) input(view.getChildAt(i))?.let { return it }
                return null
            }
            fun hasExpectedFocus()=main { saving?.let { input(it.window.decorView)?.getExtractedText(android.view.inputmethod.ExtractedTextRequest(),0)?.text?.toString()==expected }==true }
            repeat(8) {
                if(hasExpectedFocus()) return@repeat
                locate(label).performAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_SHOW_ON_SCREEN.id)
                Thread.sleep(250)
                val bounds=Rect().also { locate(label).getBoundsInScreen(it) }
                val down=SystemClock.uptimeMillis()
                for(action in listOf(MotionEvent.ACTION_DOWN,MotionEvent.ACTION_UP)) {
                    val event=MotionEvent.obtain(down,SystemClock.uptimeMillis(),action,bounds.exactCenterX(),bounds.exactCenterY(),0)
                    check(auto.injectInputEvent(event,true)); event.recycle()
                }
                Thread.sleep(250)
            }
            waitFor("focused field $label") { hasExpectedFocus() }
            main {
                val connection=input(saving!!.window.decorView)!!
                val before=connection.getExtractedText(android.view.inputmethod.ExtractedTextRequest(),0)?.text ?: error("No field text for $label")
                check(connection.setSelection(0,before.length))
                check(connection.commitText(value,1)) { "Cannot enter text into $label" }
            }
            waitFor("entered field $label") { main { input(saving!!.window.decorView)?.getExtractedText(android.view.inputmethod.ExtractedTextRequest(),0)?.text?.toString()==value } }
        }
        try {
            main {
                Settings.init(fixture); Repository.init(fixture)
                for(letter in 'A'..'Z') repeat(3) { VaultStore.save(VaultEntry(type=VaultType.PASSWORD,name="$letter 密码 $it",password="synthetic")) }
                VaultStore.save(VaultEntry(type=VaultType.PASSWORD,name="百度",password="synthetic"))
                VaultStore.save(VaultEntry(type=VaultType.PASSWORD,name="123",password="synthetic"))
                val pinned=VaultEntry(type=VaultType.PASSWORD,name="收藏测试",password="synthetic",pinnedAt=System.currentTimeMillis())
                VaultStore.save(pinned)
                VaultStore.save(VaultEntry(type=VaultType.OTP,name="动态密码测试",account="otp-test",secret="JBSWY3DPEHPK3PXP"))
                activity.window.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                com.kongye.docs.ui.AppState.goHome(com.kongye.docs.ui.Tab.FILES)
                com.kongye.docs.ui.AppState.filesSub=com.kongye.docs.ui.FilesSub.VAULT
                activity.setContent { KongyeTheme { com.kongye.docs.ui.KongyeApp() } }
            }
            if(!saveOnly) {
            waitFor("ordinary category count excludes favorites") { find("共 80 个密码")!=null }
            waitFor("unconfigured backup hint") { find("尚未设置密码备份")!=null }
            click("去设置")
            waitFor("backup hint opens settings") { main { com.kongye.docs.ui.AppState.current==com.kongye.docs.ui.Screen.VaultSettings } && find("密码备份")!=null }
            main { com.kongye.docs.ui.AppState.pop() }
            waitFor("back to vault") { find("普通密码")!=null && find("尚未设置密码备份")!=null }
            main { Settings.updateBackup { it.copy(vaultPassword="synthetic-ui-test-password") } }
            waitFor("configured backup hides hint") { find("尚未设置密码备份")==null }
            main { Settings.updateBackup { it.copy(vaultPassword="") } }
            waitFor("clearing backup password restores hint") { find("尚未设置密码备份")!=null }
            check(find("••••••")==null && find("显示密码")==null && find("更多")==null)
            check(find("密码填充")==null && find("密码备份")==null)
            check(find("收藏测试")==null)
            swipe(true)
            waitFor("swipe to OTP") { find("动态密码测试")!=null && find("A 密码 0")==null && find("共 1 个动态密码")!=null }
            check(main { com.kongye.docs.ui.AppState.filesSub==com.kongye.docs.ui.FilesSub.VAULT })
            swipe(true)
            waitFor("swipe to favorites") { find("收藏测试")!=null && find("动态密码测试")==null && find("共 1 个密码")!=null }
            val favoriteIcon=Rect().also { locate("已收藏").getBoundsInScreen(it) }
            val favoriteName=Rect().also { locate("收藏测试").getBoundsInScreen(it) }
            check(favoriteIcon.centerX()<favoriteName.left) { "Favorite marker must replace the leading key icon" }
            auto.takeScreenshot()?.let { bmp -> File(ctx.getExternalFilesDir(null),"vault-favorite-icon.png").outputStream().use { bmp.compress(android.graphics.Bitmap.CompressFormat.PNG,100,it) }; bmp.recycle() }
            swipe(true)
            check(main { com.kongye.docs.ui.AppState.filesSub==com.kongye.docs.ui.FilesSub.VAULT })
            click("收藏测试")
            waitFor("detail still available") { find("编辑")!=null }
            click("关闭")
            waitFor("detail dismissed") { find("编辑")==null }
            longPress("收藏测试")
            waitFor("favorite menu") { find("取消收藏")!=null && find("复制全部字段")!=null }
            click("取消收藏")
            waitFor("unfavorite removes from category") { find("暂无收藏")!=null }
            click("普通密码")
            waitFor("unfavorite returns to ordinary") { find("A 密码 0")!=null }
            longPress("A 密码 0"); click("收藏")
            waitFor("ordinary moves into favorites") { VaultStore.entries.value.single { it.name=="A 密码 0" }.pinned && find("A 密码 0")==null }
            click("收藏")
            waitFor("ordinary is in favorites") { find("A 密码 0")!=null }
            longPress("A 密码 0"); click("取消收藏")
            waitFor("ordinary leaves favorites") { find("暂无收藏")!=null }
            click("动态密码")
            waitFor("OTP shown") { find("动态密码测试")!=null }
            longPress("动态密码测试"); click("收藏")
            waitFor("OTP leaves type category") { find("暂无动态密码")!=null }
            click("收藏")
            waitFor("OTP in favorites") { find("动态密码测试")!=null }
            longPress("动态密码测试"); click("取消收藏")
            click("动态密码")
            waitFor("OTP restored to category") { find("动态密码测试")!=null }
            click("普通密码")
            waitFor("ordinary category") { find("A 密码 0")!=null && find("动态密码测试")==null }
            check(find("☆")==null && find("置顶")==null)
            check(find("••••••")==null && find("显示密码")==null && find("更多")==null)
            pass("backup-onboarding-hint-navigation-and-favorites-migration-with-vault-only-swipes")
            waitFor("index") { find("密码字母滑动索引")!=null }
            val rect=Rect().also { find("密码字母滑动索引")!!.getBoundsInScreen(it) }
            val fab=Rect().also { locate("新建").getBoundsInScreen(it) }
            check(kotlin.math.abs(fab.exactCenterX()-(activity.window.decorView.width-48*ctx.resources.displayMetrics.density))<3*ctx.resources.displayMetrics.density) { "Add button moved from its original position" }
            fun event(action: Int,y: Float,down: Long) {
                val e=MotionEvent.obtain(down,SystemClock.uptimeMillis(),action,rect.exactCenterX(),y,0).apply { source=InputDevice.SOURCE_TOUCHSCREEN }
                check(auto.injectInputEvent(e,true)); e.recycle()
            }
            val down=SystemClock.uptimeMillis()
            event(MotionEvent.ACTION_DOWN,rect.top+rect.height()*.20f,down)
            waitFor("active alphabet gesture") { main { com.kongye.docs.ui.AppState.vaultIndexDragging } }
            waitFor("add button hidden while sliding") { find("新建")==null }
            event(MotionEvent.ACTION_MOVE,rect.top+rect.height()*9.5f/27f,down)
            Thread.sleep(450)
            fun letterBounds(label: String): Rect? {
                fun walk(n: AccessibilityNodeInfo): AccessibilityNodeInfo? {
                    if(n.text?.toString()==label) return n
                    for(i in 0 until n.childCount) n.getChild(i)?.let { walk(it)?.let { return it } }
                    return null
                }
                return find("密码字母滑动索引")?.let(::walk)?.let { Rect().also(it::getBoundsInScreen) }
            }
            waitFor("neighboring K bends left around finger") { letterBounds("K")?.centerX()?.let { it<rect.centerX()-ctx.resources.displayMetrics.density*10 }==true }
            val out=File(ctx.getExternalFilesDir(null),"sync-ui").apply { mkdirs() }
            auto.takeScreenshot()?.let { bmp -> File(out,"alphabet-wave-J.png").outputStream().use { bmp.compress(android.graphics.Bitmap.CompressFormat.PNG,100,it) }; bmp.recycle() }
            event(MotionEvent.ACTION_MOVE,rect.top+rect.height()*25.5f/27f,down)
            Thread.sleep(450)
            auto.takeScreenshot()?.let { bmp -> File(out,"alphabet-wave-Z.png").outputStream().use { bmp.compress(android.graphics.Bitmap.CompressFormat.PNG,100,it) }; bmp.recycle() }
            event(MotionEvent.ACTION_UP,rect.top+rect.height()*25.5f/27f,down)
            waitFor("add button returns after sliding") { find("新建")!=null }
            waitFor("Z jump") { find("Z 密码 0")!=null }
            auto.takeScreenshot()?.let { bmp -> File(out,"alphabet-Z.png").outputStream().use { bmp.compress(android.graphics.Bitmap.CompressFormat.PNG,100,it) }; bmp.recycle() }
            val time=SystemClock.uptimeMillis(); event(MotionEvent.ACTION_DOWN,rect.top+2f,time); event(MotionEvent.ACTION_UP,rect.top+2f,time)
            waitFor("A jump") { find("A 密码 0")!=null }
            pass("vault-index-A-to-Z-has-no-star-and-retains-wave-and-original-add-position")

            main { com.kongye.docs.ui.AppState.filesSub=com.kongye.docs.ui.FilesSub.DESKTOP }
            Thread.sleep(500); swipe(false)
            check(main { com.kongye.docs.ui.AppState.filesSub==com.kongye.docs.ui.FilesSub.DESKTOP })
            swipe(true)
            check(main { com.kongye.docs.ui.AppState.filesSub==com.kongye.docs.ui.FilesSub.DESKTOP })
            main { com.kongye.docs.ui.AppState.tab=com.kongye.docs.ui.Tab.ME }
            check(find("密码备份")==null && find("密码填充")==null)
            click("密码空间")
            waitFor("nested vault settings") { main { com.kongye.docs.ui.AppState.current==com.kongye.docs.ui.Screen.VaultSettings } && find("密码备份")!=null }
            waitFor("vault settings total and disjoint category counts") { find("共 82 个密码")!=null && find("收藏：0 个")!=null && find("动态密码：1 个")!=null && find("普通密码：81 个")!=null }
            auto.takeScreenshot()?.let { bmp -> File(ctx.getExternalFilesDir(null),"vault-settings-stats.png").outputStream().use { bmp.compress(android.graphics.Bitmap.CompressFormat.PNG,100,it) }; bmp.recycle() }
            click("密码备份")
            waitFor("backup setting") { find("设置备份密码")!=null }
            auto.performGlobalAction(android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_BACK)
            waitFor("backup dialog dismissed") { find("设置备份密码")==null }
            click("密码填充")
            waitFor("fill screen opens") { main { com.kongye.docs.ui.AppState.current==com.kongye.docs.ui.Screen.VaultFill } && find("保存前确认")!=null }
            main { com.kongye.docs.ui.AppState.goHome(com.kongye.docs.ui.Tab.FILES) }
            pass("desktop-swipes-do-not-switch-pages-and-Me-vault-subpage-contains-backup-and-fill")

            }
            val input=VaultApi.SaveInput("","save-ui.example","自动存入测试","demo-account","synthetic-password",
                fields=listOf(VaultApi.SaveField("email","邮箱","demo@example.test")))
            val token=PendingSaves.put(input)
            val monitor=inst.addMonitor(AutofillActivity::class.java.name,null,false)
            val saveActivity: AutofillActivity
            try {
                main { activity.startActivity(Intent(activity,AutofillActivity::class.java)
                    .putExtra(AutofillActivity.EXTRA_MODE,AutofillActivity.MODE_SAVE)
                    .putExtra(AutofillActivity.EXTRA_TOKEN,token).putExtra(AutofillActivity.EXTRA_PKG,"")
                    .putExtra(AutofillActivity.EXTRA_DOMAIN,"save-ui.example")) }
                saveActivity=inst.waitForMonitorWithTimeout(monitor,12000) as? AutofillActivity ?: error("Save activity did not start")
                saving=saveActivity
            } finally { inst.removeMonitor(monitor) }
            waitFor("save labels") { find("账号字段名称")!=null }
            val previewDir=File(ctx.getExternalFilesDir(null),"save-preview").apply { mkdirs() }
            fun screenshot(name: String) {
                // Synthetic isolated data only; the real password UI keeps FLAG_SECURE.
                main { saveActivity.window.clearFlags(android.view.WindowManager.LayoutParams.FLAG_SECURE) }
                Thread.sleep(400)
                auto.takeScreenshot()?.let { bmp -> File(previewDir,name).outputStream().use { bmp.compress(android.graphics.Bitmap.CompressFormat.PNG,100,it) }; bmp.recycle() }
                main { saveActivity.window.addFlags(android.view.WindowManager.LayoutParams.FLAG_SECURE) }
            }
            check(main { androidx.core.view.ViewCompat.getRootWindowInsets(saveActivity.window.decorView)?.isVisible(androidx.core.view.WindowInsetsCompat.Type.ime())!=true })
            screenshot("save-password-light.png")
            main { com.kongye.docs.ui.theme.Kongye.dark=true }
            screenshot("save-password-dark.png")
            main { com.kongye.docs.ui.theme.Kongye.dark=false }
            text("账号字段名称","我的登录名","账号")
            text("密码字段名称","我的口令","密码")
            text("字段 1 名称","联系邮箱","邮箱")
            click("＋ 添加字段")
            text("字段 2 名称","备用信息","新字段")
            text("字段 2 内容","extra-test-value","")
            main { androidx.core.view.WindowCompat.getInsetsController(saveActivity.window,saveActivity.window.decorView).hide(androidx.core.view.WindowInsetsCompat.Type.ime()) }
            Thread.sleep(450)
            screenshot("save-password-custom-fields.png")
            click("保存")
            waitFor("custom saved") { VaultStore.entries.value.any { it.name=="自动存入测试" } }
            val saved=VaultStore.entries.value.single { it.name=="自动存入测试" }
            check(saved.accountLabel=="我的登录名" && saved.passwordLabel=="我的口令")
            check(saved.fields.any { it.label=="联系邮箱" } && saved.fields.any { it.label=="备用信息" && it.value=="extra-test-value" }) { "Unexpected test field labels: ${saved.fields.map { it.label }}" }
            pass("real-autofill-save-ui-renames-core-and-extra-fields-and-adds-custom-content")
            main { saveActivity.finish() }
        } catch(error: Throwable) {
            auto.takeScreenshot()?.let { bmp ->
                File(ctx.getExternalFilesDir(null),"vault-ui-failure.png").outputStream().use { bmp.compress(android.graphics.Bitmap.CompressFormat.PNG,100,it) }; bmp.recycle()
            }
            throw error
        } finally {
            main { saving?.finish(); activity.finish(); Settings.init(ctx); Repository.init(ctx) }
        }
    }
}
