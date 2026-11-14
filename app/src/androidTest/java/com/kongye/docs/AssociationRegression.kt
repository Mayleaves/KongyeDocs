package com.kongye.docs

import android.app.Instrumentation
import android.content.Context
import android.content.ContextWrapper
import android.os.Bundle
import com.kongye.docs.data.*
import com.kongye.docs.vault.*
import java.io.File
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

object AssociationRegression {
    fun run(inst:Instrumentation,hold:Boolean=false) {
        KongyeApplication.awaitReady()
        val ctx=inst.targetContext
        val directory=File(ctx.filesDir,"association-fixture-${System.nanoTime()}").apply {mkdirs()}
        val fixture=object:ContextWrapper(ctx) {
            override fun getFilesDir()=directory
            override fun getApplicationContext():Context=this
        }
        fun main(action:()->Unit) {inst.runOnMainSync {action()}}
        fun call(method:String,args:Bundle)=VaultApi.handle(ctx,"com.kongye.keyboard",method,args)
        val args=Bundle().apply {putString("package","fixture.example.app")}
        try {
            main {Settings.init(fixture);Repository.init(fixture);Settings.update {it.copy(vaultFill=true)}}
            val entry=VaultStore.add(VaultEntry(type=VaultType.PASSWORD,name="Association fixture",account="sample@example.test",
                password="SyntheticOnly!7",apps=listOf("fixture.example.app","fixture.other.app"),url="example.test",fields=listOf(VaultField(label="网址",value="example.test"),VaultField(label="备注",value="keep this note"))))
            val listed=call("association.list",args)
            check(listed.getString("status")=="ok")
            @Suppress("DEPRECATION")
            val row=listed.getParcelableArrayList<Bundle>("entries")!!.single()
            check(!row.containsKey("password") && row.getString("account")!="sample@example.test")
            val remove=Bundle(args).apply {putString("id",entry.id);putString("scope","wrong")}
            check(call("association.remove",remove).getString("status")=="error")
            remove.putString("scope",listed.getString("scope"))
            VaultAccess.lock()
            check(call("association.remove",remove).getString("status")=="locked")
            check("fixture.example.app" in VaultStore.get(entry.id)!!.apps)
            VaultAccess.unlock()
            check(call("association.remove",remove).getString("status")=="ok")
            val remaining=VaultStore.get(entry.id)!!
            check(remaining.apps==listOf("fixture.other.app") && remaining.password==entry.password)
            check(call("association.remove",remove).getString("status")=="not_found")
            val browser=Bundle().apply {putString("package","com.android.chrome")}
            VaultApi.observeAutofillTarget("com.android.chrome","login.example.test")
            VaultStore.update(entry.id) {it.copy(excludedDomains=listOf("login.example.test"))}
            check(VaultApi.match("","login.example.test").isEmpty())
            val website=call("association.list",browser)
            @Suppress("DEPRECATION")
            check(website.getParcelableArrayList<Bundle>("entries")!!.single().getString("id")==entry.id)
            check(website.getString("removeMode")=="clear_url")
            check(website.getString("scope")=="login.example.test")
            val removeWebsite=Bundle(browser).apply {putString("id",entry.id);putString("scope","login.example.test")}
            VaultApi.observeAutofillTarget("com.android.chrome","different.test")
            check(call("association.remove",removeWebsite).getString("status")=="error")
            VaultApi.observeAutofillTarget("com.android.chrome","login.example.test")
            check(call("association.remove",removeWebsite).getString("status")=="ok")
            val suppressed=VaultStore.get(entry.id)!!
            check(suppressed.url.isEmpty() && suppressed.excludedDomains.isEmpty())
            check(suppressed.fields.none {it.label=="网址"} && suppressed.field("备注")=="keep this note")
            VaultStore.init(fixture)
            check(VaultStore.get(entry.id)!!.url.isEmpty() && VaultStore.get(entry.id)!!.password==entry.password)
            check(VaultApi.match("","login.example.test").isEmpty())
            check(VaultApi.match("","other.example.test").isEmpty())
            val restored=VaultApi.bind(suppressed,"","login.example.test")
            check(restored.excludedDomains.isEmpty() && restored.url=="login.example.test")
            val json=Json {encodeDefaults=true}
            check(json.decodeFromString<VaultEntry>(json.encodeToString(suppressed)).excludedDomains==suppressed.excludedDomains)
            check(VaultApi.handle(ctx,"untrusted.app","association.remove",remove).getString("status")=="denied")
            inst.sendStatus(0,Bundle().apply {putString("stream","ASSOCIATION LOCK / SCOPE / MASKING / REMOVAL / SITE / REBIND CHECKS PASSED\n")})
            if(hold) {
                val uiEntry=VaultStore.add(VaultEntry(type=VaultType.PASSWORD,name="Keyboard association fixture",
                    account="fixture@example.test",password="SyntheticOnly!7",apps=listOf("com.kongye.keyboard.test")))
                val stop=File(ctx.cacheDir,"association-ui-stop")
                stop.delete()
                VaultAccess.unlock()
                inst.sendStatus(0,Bundle().apply {putString("stream","ASSOCIATION UI FIXTURE READY\n")})
                val deadline=android.os.SystemClock.uptimeMillis()+150000
                while(!stop.exists() && android.os.SystemClock.uptimeMillis()<deadline)Thread.sleep(100)
                check(VaultStore.get(uiEntry.id)!!.apps.isEmpty()) {"Keyboard did not remove fixture association"}
                check(VaultStore.get(uiEntry.id)!!.password==uiEntry.password)
                stop.delete()
                inst.sendStatus(0,Bundle().apply {putString("stream","ASSOCIATION UI FIXTURE VERIFIED\n")})
            }

        } finally {
            main {VaultAccess.lock();Settings.init(ctx);Repository.init(ctx)}
        }
    }
}
