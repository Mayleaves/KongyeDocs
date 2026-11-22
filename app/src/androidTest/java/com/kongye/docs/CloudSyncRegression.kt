package com.kongye.docs

import android.app.Instrumentation
import android.content.Context
import android.content.ContextWrapper
import android.graphics.Bitmap
import com.kongye.docs.data.*
import com.kongye.docs.ui.vault.VaultAlphabet
import com.kongye.docs.vault.VaultApi
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import java.io.File
import java.util.UUID

internal object CloudSyncRegression {
    fun run(inst: Instrumentation,pass: (String)->Unit) = runBlocking {
        val context=inst.targetContext
        // MIUI can freeze a headless instrumentation process mid-exchange.
        // Keep the test activity visible before installing isolated repositories.
        val activity=startTestActivity(inst,context)
        inst.runOnMainSync { activity.window.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON) }
        val running=java.util.concurrent.atomic.AtomicBoolean(true)
        val watchdog=kotlin.concurrent.thread(isDaemon=true,name="Sync-test-watchdog") {
            while(running.get()) {
                Thread.sleep(10000)
                if(running.get()) File(context.cacheDir,"sync-test-threads.txt").writeText(
                    Thread.getAllStackTraces().entries.joinToString("\n\n") { (t,stack) -> t.name+" ["+t.state+"]\n"+stack.joinToString("\n") })
            }
        }
        fun fixture(label: String)=object: ContextWrapper(context) {
            val root=File(context.filesDir,"sync-test-$label-${newId()}").apply { mkdirs() }
            override fun getFilesDir()=root
            override fun getApplicationContext(): Context=this
        }
        val a=fixture("a"); val b=fixture("b")
        val password="SyntheticSyncPassword#2026"
        val id=newId(); val other=newId()
        fun record(value: String?)=value?.let { JsonPrimitive(it) }
        var base=LocalSyncState(device=UUID.randomUUID().toString())
        base=SyncMerge.reconcile(base,mapOf(id to record("base")!!),"A")
        val peer=LocalSyncState(device=UUID.randomUUID().toString(),state=base.state,applied=base.applied)
        val left=SyncMerge.reconcile(base,mapOf(id to record("left")!!),"A")
        val right=SyncMerge.reconcile(peer,mapOf(id to record("right")!!),"B")
        val merged=SyncMerge.merge(left.state,right.state)
        check(merged.versions.size==2)
        check(SyncMerge.merge(left.state,right.state)==SyncMerge.merge(right.state,left.state))
        check(SyncMerge.merge(merged,merged)==merged)
        val deleted=SyncMerge.reconcile(base,emptyMap(),"A")
        check(SyncMerge.materialize(SyncMerge.merge(deleted.state,right.state),emptyMap()).isEmpty())
        val staleOpen=LocalSyncState(device=peer.device,state=SyncMerge.merge(base.state,left.state),applied=base.applied)
        val late=SyncMerge.reconcile(staleOpen,mapOf(id to record("late edit")!!),"B")
        check(late.state.versions.size==2) { "Unseen remote version was overwritten by stale editor" }
        pass("vector-clocks-merge-commutatively-and-preserve-edit-delete-and-stale-editor-conflicts")
        fun activate(ctx: Context) { Settings.init(ctx); Repository.init(ctx) }
        fun offline() { Settings.updateBackup { it.copy(cloudSync=false,davUrl="",vaultPassword=password) } }
        MockDav(fullPaths=true).use { server ->
            val url="http://127.0.0.1:${server.port}/"
            fun configured() { Settings.updateBackup { it.copy(cloudSync=true,davUrl=url,davUser="synthetic",davPassword="synthetic",allowInsecureDav=true,vaultPassword=password) } }
            suspend fun sync(ctx: Context,success: Boolean=true) {
                configured(); CloudSync.run(ctx)
                if(success) check(!CloudSync.status.value.message.startsWith("同步失败")) { CloudSync.status.value.message }
                AutoBackup.flushVaultBackups(ctx)
                offline()
            }
            try {
                activate(a); offline()
                Repository.items.value.toList().forEach { Repository.destroy(it.id) }
                val entry=VaultEntry(id=id,type=VaultType.PASSWORD,name="共享测试",account="synthetic",password="original")
                VaultStore.save(entry)
                val imageName=newId()+".png"
                val imageFile=File(Repository.imagesDir,imageName)
                Bitmap.createBitmap(24,12,Bitmap.Config.ARGB_8888).also { bitmap -> imageFile.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG,100,it) }; bitmap.recycle() }
                val doc=Repository.createDoc(null,"共享文档",DocContent(listOf(Block(text="第一版"),Block(type=BlockType.IMAGE,image=imageName,imageRatio=2f))))
                sync(a)
                activate(b); offline()
                Repository.items.value.toList().forEach { Repository.destroy(it.id) }
                sync(b)
                check(VaultStore.get(id)?.password=="original")
                check(Repository.get(doc.id)?.title=="共享文档")
                val imported=Repository.loadDoc(doc.id).blocks.first { it.type==BlockType.IMAGE }.image
                check(File(Repository.imagesDir,imported).readBytes().contentEquals(imageFile.readBytes()))
                check(server.files.filterKeys { it.contains("sync-v1/") }.values.none { it.decodeToString().contains("original") || it.decodeToString().contains("第一版") })
                pass("two-independent-installations-exchange-encrypted-passwords-documents-and-image-bytes")

                activate(a); offline(); VaultStore.save(VaultStore.get(id)!!.copy(password="from-a")); sync(a)
                activate(b); offline(); VaultStore.save(VaultEntry(id=other,type=VaultType.PASSWORD,name="来自B",password="from-b")); sync(b)
                activate(a); sync(a)
                check(VaultStore.get(id)?.password=="from-a" && VaultStore.get(other)?.password=="from-b")
                pass("different-offline-record-edits-merge-without-losing-either-device")

                activate(b); sync(b)
                activate(a); offline(); VaultStore.save(VaultStore.get(id)!!.copy(password="concurrent-a")); sync(a)
                activate(b); offline(); VaultStore.save(VaultStore.get(id)!!.copy(password="concurrent-b")); sync(b)
                val conflict=CloudSync.status.value.conflicts.single { it.kind=="vault" && it.id==id }
                check(conflict.versions.size==2)
                configured()
                val chosen=conflict.versions.single { CloudSync.preview(b,conflict,it.revision)?.jsonObject?.get("password")?.jsonPrimitive?.content=="concurrent-a" }
                CloudSync.resolveLocal(b,conflict,chosen.revision); sync(b)
                activate(a); sync(a)
                check(VaultStore.get(id)?.password=="concurrent-a" && CloudSync.status.value.conflicts.none { it.id==id })
                pass("same-password-concurrent-edits-keep-both-versions-and-explicit-resolution-propagates")

                VaultStore.delete(other); sync(a)
                activate(b); sync(b)
                check(VaultStore.get(other)==null)
                sync(b); activate(a); sync(a)
                check(VaultStore.get(other)==null)
                pass("deletion-markers-propagate-and-old-device-checkpoints-do-not-resurrect-passwords")

                Repository.saveDoc(doc.id,"A的文档",DocContent(listOf(Block(text="A离线文字"))))
                sync(a)
                activate(b); offline()
                Repository.saveDoc(doc.id,"B的文档",DocContent(listOf(Block(text="B离线文字"))))
                sync(b)
                val docConflict=CloudSync.status.value.conflicts.single { it.kind=="docs" && it.id==doc.id }
                check(docConflict.versions.size==2)
                configured(); val choice=docConflict.versions.single { CloudSync.preview(b,docConflict,it.revision)?.jsonObject?.get("item")?.jsonObject?.get("title")?.jsonPrimitive?.content=="B的文档" }
                CloudSync.resolveLocal(b,docConflict,choice.revision); sync(b)
                activate(a); sync(a)
                check(CloudSync.status.value.conflicts.none { it.kind=="docs" && it.id==doc.id })
                check(Repository.versions(doc.id).isNotEmpty())
                Repository.destroy(doc.id); sync(a)
                activate(b); sync(b)
                check(Repository.get(doc.id)==null)
                pass("concurrent-rich-documents-retain-both-versions-archive-before-replace-and-sync-permanent-delete")

                VaultStore.save(VaultEntry(type=VaultType.PASSWORD,name="断网上传",password="retry-value"))
                server.rejectPut.set(true); sync(b,success=false)
                check(CloudSync.status.value.message.startsWith("同步失败"))
                server.rejectPut.set(false); sync(b)
                activate(a); sync(a)
                check(VaultStore.entries.value.any { it.password=="retry-value" })
                val before=VaultStore.entries.value
                Settings.updateBackup { it.copy(cloudSync=true,davUrl=url,vaultPassword="wrong-password",allowInsecureDav=true) }
                val puts=server.requests.count { it.first=="PUT" && it.second.contains("sync-v1") }
                CloudSync.run(a)
                check(CloudSync.status.value.message.startsWith("同步失败"))
                check(VaultStore.entries.value==before)
                check(server.requests.count { it.first=="PUT" && it.second.contains("sync-v1") }==puts)
                offline()
                pass("failed-uploads-retry-after-reload-and-wrong-password-never-overwrites-cloud-or-local-data")

                check(VaultAlphabet.key("百度")=="B" && VaultAlphabet.key("支付宝")=="Z" && VaultAlphabet.key("Apple")=="A" && VaultAlphabet.key("123")=="#")
                val saved=VaultApi.saveCredential(context,VaultApi.SaveInput("","sync-fields.example","字段测试","demo","synthetic-pass",fields=listOf(VaultApi.SaveField("other","我的邮箱","demo@example.test")),accountLabel="登录名",passwordLabel="登录密钥"))
                val custom=VaultStore.get(saved.id)!!
                check(custom.accountLabel=="登录名" && custom.passwordLabel=="登录密钥" && custom.fields.any { it.label=="我的邮箱" })
                pass("chinese-and-latin-alphabet-buckets-and-autofill-custom-field-labels-roundtrip")
            } finally {
                running.set(false)
                Settings.init(context); Repository.init(context)
                inst.runOnMainSync { activity.finish() }
            }
        }
    }
}
