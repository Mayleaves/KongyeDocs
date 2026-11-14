package com.kongye.docs

import android.app.Instrumentation
import android.content.Context
import android.content.ContextWrapper
import android.os.SystemClock
import android.view.accessibility.AccessibilityWindowInfo
import com.kongye.docs.data.*
import com.kongye.docs.vault.AppCerts
import java.io.File

/** Temporary fake accounts and native host; always restores the prior system provider. */
object AutofillProbe {
    fun run(inst: Instrumentation) {
        inst.uiAutomation.serviceInfo=inst.uiAutomation.serviceInfo.apply { flags=flags or android.accessibilityservice.AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS }
        fun <T> main(action: ()->T): T { var result: Result<T>?=null;inst.runOnMainSync { result=runCatching(action) };return result!!.getOrThrow() }
        fun shell(command: String)=inst.uiAutomation.executeShellCommand(command).use { java.io.FileInputStream(it.fileDescriptor).use { input -> input.readBytes().toString(Charsets.UTF_8) } }
        val context=inst.targetContext
        val original=shell("settings get secure autofill_service").trim()
        val fixture=object: ContextWrapper(context) {
            val root=File(context.filesDir,"autofill-probe-${System.currentTimeMillis()}").apply { mkdirs() }
            override fun getFilesDir()=root
            override fun getApplicationContext(): Context=this
        }
        val stop=File(context.getExternalFilesDir(null),"autofill-probe-stop").also { it.delete() }
        val host="org.kongye.autofillfixture"
        try {
            main {
                Settings.init(fixture); Repository.init(fixture)
                val cert=AppCerts.of(context,host)
                for(i in 1..2) VaultStore.add(VaultEntry(type=VaultType.PASSWORD,name="示例账号$i",account="tester$i@example.org",password="OnlyFakeTest123!",apps=listOf(host),appCerts=if(cert==null) emptyMap() else mapOf(host to cert)))
            }
            shell("settings put secure autofill_service ${context.packageName}/com.kongye.docs.autofill.KongyeAutofillService")
            shell("am force-stop $host")
            shell("am start -n $host/.MainActivity")
            val until=SystemClock.uptimeMillis()+180000
            var previous=""
            while(SystemClock.uptimeMillis()<until && !stop.exists()) {
                val windows=inst.uiAutomation.windows
                val state="ime="+windows.any { it.type==AccessibilityWindowInfo.TYPE_INPUT_METHOD }+" windows="+windows.map { "${it.type}:${it.title}" }
                if(state!=previous) { android.util.Log.d("KongyeAutofillProbe",state);previous=state }
                Thread.sleep(150)
            }
        } finally {
            shell("am force-stop $host")
            if(original.isBlank() || original=="null") shell("settings delete secure autofill_service") else shell("settings put secure autofill_service $original")
            main { Settings.init(context);Repository.init(context) }
        }
    }
}
