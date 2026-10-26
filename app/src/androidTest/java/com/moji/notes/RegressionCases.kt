package com.moji.notes

import android.content.Context
import com.moji.notes.data.*
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File
import java.util.concurrent.CountDownLatch
import kotlin.concurrent.thread

/** Uses synthetic documents only. The instrumentation runner supplies a separate files directory. */
object RegressionCases {
    val names = listOf("corrupt-library", "previous-library", "corrupt-document", "full-backup", "checksum", "path-traversal",
        "transaction-rollback", "crash-recovery", "restore-folder", "restore-snapshot", "missing-image", "shared-image", "legacy-format", "synchronous-save", "concurrent-save-restore", "settings-encryption", "image-import")
    private val json = Json { encodeDefaults = true; ignoreUnknownKeys = true }
    private val docId = "a".repeat(16)
    private val folderId = "b".repeat(16)
    private val imageName = "c".repeat(16) + ".jpg"
    private fun body(text: String) = DocContent(listOf(Block(text = text)))
    private fun requireTest(value: Boolean, reason: String) { check(value) { reason } }
    private fun initialize(context: Context, library: Library = Library()) {
        val root = context.filesDir.apply { mkdirs() }
        DurableFiles.write(File(root, "library.json"), json.encodeToString(library))
        Settings.init(context); Repository.init(context)
    }
    private fun rawBackup(items: List<Item>, docs: Map<String,DocContent>, images: Map<String,String> = emptyMap()) = json.encodeToString(Backup(items=items, docs=docs, images=images))

    fun run(context: Context, name: String) {
        val root = context.filesDir.apply { mkdirs() }
        when (name) {
            "corrupt-library" -> {
                File(root,"docs").mkdirs(); File(root,"images").mkdirs()
                File(root,"docs/$docId.json").writeText(json.encodeToString(body("original")))
                val image=File(root,"images/$imageName").apply { writeBytes(byteArrayOf(1,2,3)); setLastModified(System.currentTimeMillis()-3*86400000L) }
                File(root,"library.json").writeText("{broken")
                Settings.init(context); Repository.init(context)
                requireTest(Repository.isReadOnly && File(root,"library.json").readText()=="{broken" && image.exists(), "Corrupt library overwritten or image deleted")
                requireTest(runCatching { Repository.createDoc(null,"must fail") }.isFailure, "Protected storage accepted write")
            }
            "previous-library" -> {
                File(root,"docs").mkdirs()
                File(root,"docs/$docId.json").writeText(json.encodeToString(body("preserved")))
                File(root,"library.json.bak").writeText(json.encodeToString(Library(items=listOf(Item(id=docId,type=ItemType.DOC)))))
                File(root,"library.json").writeText("broken")
                Settings.init(context); Repository.init(context)
                requireTest(!Repository.isReadOnly && Repository.loadDoc(docId).bodyText()=="preserved", "Previous valid library not recovered")
                requireTest(root.listFiles().orEmpty().any { it.name.startsWith("library.json.corrupt-") }, "Damaged evidence not preserved")
            }
            "corrupt-document" -> {
                initialize(context)
                val item=Repository.createDoc(null,"original",body("original"))
                // Clear the in-memory document cache by reinitializing, then damage the primary and its prior copy.
                File(root,"docs/${item.id}.json").writeText("broken")
                File(root,"docs/${item.id}.json.bak").delete()
                Repository.init(context)
                requireTest(runCatching { Repository.loadDoc(item.id) }.isFailure, "Damaged document silently became blank")
                requireTest(runCatching { Repository.saveDoc(item.id,"new",body("new")) }.isFailure && File(root,"docs/${item.id}.json").readText()=="broken", "Damaged document overwritten")
            }
            "full-backup" -> {
                initialize(context)
                File(root,"images/$imageName").writeBytes(byteArrayOf(1,2,3))
                val withImage=DocContent(listOf(Block(type=BlockType.IMAGE,image=imageName), Block(text="version image")))
                val item=Repository.createDoc(null,"current",body("current"))
                Repository.addVersion(item.id,VersionKind.LOCAL,"old",withImage)
                val template=Repository.saveAsTemplate("template","template",body("template-only"))
                Repository.recordExport(ExportKind.TEXT,"export.txt",false,item.id,text="download-only")
                Repository.setNickname("test nickname")
                val text=Repository.exportBackup(); val backup=BackupCodec.decode(text)
                requireTest(backup.version==2 && backup.versions[item.id]!!.isNotEmpty() && backup.templates[template.id]!!.bodyText()=="template-only", "History/template omitted")
                requireTest(imageName in backup.images && backup.exportFiles.isNotEmpty() && backup.library?.nickname=="test nickname", "Attachment/download/metadata omitted")
                // Reuse the same fixture in a clean subdirectory through a second context in the runner.
                Repository.importBackup(text)
                requireTest(Repository.templatePreview(template.id).bodyText()=="template-only" && Repository.versions(item.id).any { it.content==withImage }, "Full restore failed")
            }
            "checksum" -> {
                initialize(context)
                val item=Repository.createDoc(null,"current",body("CURRENT"))
                val tampered=Repository.exportBackup().replace("CURRENT","TAMPERED")
                requireTest(runCatching { Repository.importBackup(tampered) }.isFailure && Repository.loadDoc(item.id).bodyText()=="CURRENT", "Checksum did not protect current content")
                requireTest(runCatching { Repository.importBackup("{}") }.isFailure, "Empty object accepted")
                requireTest(runCatching { Repository.importBackup(json.encodeToString(Backup(version=999))) }.isFailure, "Future format accepted")
            }
            "path-traversal" -> {
                initialize(context)
                val payload=json.encodeToString(Backup(docs=mapOf("../settings" to body("escape")), images=mapOf("../marker" to "YWJj")))
                val settings=File(root,"settings.json").let { if(it.exists()) it.readText() else null }
                requireTest(runCatching { Repository.importBackup(payload) }.isFailure && !File(root,"marker").exists(), "Traversal accepted")
                requireTest(File(root,"settings.json").let { if(it.exists()) it.readText() else null }==settings, "Traversal modified settings")
            }
            "transaction-rollback" -> {
                File(root,"first.json").writeText("ORIGINAL")
                File(root,"blocked").writeText("parent is a file")
                val result=runCatching { DurableFiles.transaction(root, linkedMapOf("first.json" to "NEW".toByteArray(), "blocked/second.json" to "FAIL".toByteArray())) }
                requireTest(result.isFailure && File(root,"first.json").readText()=="ORIGINAL" && !File(root,".transaction").exists(), "Partial transaction not rolled back")
            }
            "crash-recovery" -> {
                File(root,"first.json").writeText("PARTIALLY APPLIED")
                File(root,".transaction/old").mkdirs()
                File(root,".transaction/old/first.json").writeText("ORIGINAL")
                File(root,".transaction/journal.json").writeText("""{"entries":[{"path":"first.json","existed":true}]}""")
                DurableFiles.recover(root)
                requireTest(File(root,"first.json").readText()=="ORIGINAL" && !File(root,".transaction").exists(), "Startup did not roll back interrupted restore")
            }
            "restore-folder" -> {
                val old=Item(id=folderId,type=ItemType.FOLDER,title="folder",deletedAt=System.currentTimeMillis()-31*86400000L)
                initialize(context,Library(items=listOf(old.copy(deletedAt=null))))
                val new=Repository.createDoc(folderId,"new after backup",body("NEW"))
                Repository.importBackup(rawBackup(listOf(old),emptyMap()))
                requireTest(Repository.isAlive(Repository.get(new.id)!!), "New child hidden after restore")
                Repository.init(context)
                requireTest(Repository.get(new.id)!=null && Repository.isAlive(Repository.get(new.id)!!) && Repository.loadDoc(new.id).bodyText()=="NEW", "New child lost after restart")
            }
            "restore-snapshot" -> {
                initialize(context)
                val item=Repository.createDoc(null,"current",body("NEW CURRENT"))
                Repository.importBackup(rawBackup(listOf(item),mapOf(item.id to body("OLD BACKUP"))))
                requireTest(Repository.loadDoc(item.id).bodyText()=="OLD BACKUP" && Repository.versions(item.id).any { it.content.bodyText()=="NEW CURRENT" }, "Restore failed to retain previous current content")
                val safety=File(root,"recovery").listFiles().orEmpty().single { it.name.endsWith(".json") }
                requireTest(BackupCodec.decode(safety.readText()).docs[item.id]!!.bodyText()=="NEW CURRENT", "Safety snapshot is missing or invalid")
            }
            "missing-image" -> {
                initialize(context)
                Repository.createDoc(null,"missing",DocContent(listOf(Block(type=BlockType.IMAGE,image=imageName))))
                requireTest(runCatching { Repository.exportBackup() }.isFailure, "Missing image silently omitted")
            }
            "shared-image" -> {
                initialize(context)
                val file=File(root,"images/$imageName").apply { writeBytes(byteArrayOf(1,2,3)) }
                val image=DocContent(listOf(Block(type=BlockType.IMAGE,image=imageName)))
                val a=Repository.createDoc(null,"A",image)
                val b=Repository.createDoc(null,"B",body("B"))
                Repository.addVersion(b.id,VersionKind.LOCAL,"history",image)
                Repository.destroy(a.id)
                requireTest(file.exists() && BackupCodec.decode(Repository.exportBackup()).images.containsKey(imageName), "Shared history image lost")
            }
            "legacy-format" -> {
                initialize(context)
                val item=Item(id=docId,type=ItemType.DOC,title="legacy")
                // This mimics the 2.1 serializer: default app/version fields are omitted.
                val legacy=Json.encodeToString(Backup(items=listOf(item),docs=mapOf(item.id to body("V2.1"))))
                Repository.importBackup(legacy)
                requireTest(Repository.loadDoc(item.id).bodyText()=="V2.1", "2.1 backup compatibility broken")
            }
            "synchronous-save" -> {
                initialize(context)
                val item=Repository.createDoc(null,"initial",body("initial"))
                Repository.saveDoc(item.id,"saved",body("SAVED NOW"))
                requireTest(File(root,"docs/${item.id}.json").readText().contains("SAVED NOW"), "Save acknowledged before disk commit")
                Repository.init(context)
                requireTest(Repository.get(item.id)?.title=="saved" && Repository.loadDoc(item.id).bodyText()=="SAVED NOW", "Restart lost committed document")
            }
            "concurrent-save-restore" -> {
                initialize(context)
                val item=Repository.createDoc(null,"initial",body("initial"))
                val backup=rawBackup(listOf(item),mapOf(item.id to body("RESTORED")))
                val start=CountDownLatch(1)
                var failure: Throwable? = null
                val threads=listOf(thread { start.await(); runCatching { repeat(4) { Repository.saveDoc(item.id,"saved",body("QUEUED $it")) } }.onFailure { failure=it } },
                    thread { start.await(); runCatching { repeat(4) { Repository.importBackup(backup) } }.onFailure { failure=it } })
                start.countDown(); threads.forEach { it.join() }
                failure?.let { throw it }
                val cached=Repository.loadDoc(item.id)
                requireTest(json.decodeFromString<DocContent>(File(root,"docs/${item.id}.json").readText())==cached, "Restore/save left inconsistent cache and disk")
            }
            "settings-encryption" -> {
                initialize(context)
                Settings.updateBackup { it.copy(davPassword="SYNTHETIC-TEST-PASSWORD") }
                Settings.updateBackup { it.copy(davUser="test-user") }
                requireTest(!File(root,"settings.json").readText().contains("SYNTHETIC-TEST-PASSWORD") && !File(root,"settings.json.bak").readText().contains("SYNTHETIC-TEST-PASSWORD"), "Plaintext password persisted")
                Settings.init(context)
                requireTest(Settings.value.backup.davPassword=="SYNTHETIC-TEST-PASSWORD", "Stored password failed to decrypt")
                File(root,"settings.json").writeText(json.encodeToString(AppSettings(backup=BackupSettings(davPassword="LEGACY-SYNTHETIC-PASSWORD"))))
                Settings.init(context)
                requireTest(Settings.value.backup.davPassword=="LEGACY-SYNTHETIC-PASSWORD" && !File(root,"settings.json").readText().contains("LEGACY-SYNTHETIC-PASSWORD") && !File(root,"settings.json.bak").readText().contains("LEGACY-SYNTHETIC-PASSWORD"), "Legacy plaintext credential not migrated")
            }
            "image-import" -> {
                initialize(context)
                val input=File(root,"synthetic-image.png")
                val bitmap=android.graphics.Bitmap.createBitmap(16,16,android.graphics.Bitmap.Config.ARGB_8888)
                bitmap.eraseColor(android.graphics.Color.RED)
                input.outputStream().use { check(bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG,100,it)) }
                val imported=Repository.importImage(android.net.Uri.fromFile(input)) ?: error("Image import failed")
                check(Repository.loadBitmap(imported.first)!=null)
                Repository.createDoc(null,"image",DocContent(listOf(Block(type=BlockType.IMAGE,image=imported.first))))
                check(imported.first in BackupCodec.decode(Repository.exportBackup()).images)
            }
            else -> error("Unknown test: $name")
        }
    }
}
