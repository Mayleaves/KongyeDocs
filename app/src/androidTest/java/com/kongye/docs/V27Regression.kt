package com.kongye.docs

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import androidx.compose.ui.text.TextRange
import com.kongye.docs.data.*
import com.kongye.docs.ui.editor.*
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File

object V27Regression {
    val names=listOf("spanning-selection-cut-undo-format","continuous-line-diff","legacy-history-purge-and-backup-filter","real-saf-backup-delete","real-webdav-backup-delete")
    private val json=Json { encodeDefaults=true }
    fun run(context: Context, target: Context, name: String) {
        DurableFiles.write(File(context.filesDir,"library.json"),json.encodeToString(Library()))
        Settings.init(context); Repository.init(context)
        when(name) {
            "spanning-selection-cut-undo-format" -> {
                val thread=CommentThread(comments=listOf(Comment(text="保留评论")))
                val doc=DocContent(listOf(Block(text="第一段甲乙",spans=listOf(StyleRange(0,1,InlineStyle.COMMENT,url=thread.id))),Block(text="第二段丙丁"),Block(text="最后段戊己")),listOf(thread))
                val item=Repository.createDoc(null,"标题保留",doc)
                val e=EditorState(item.id,false)
                e.selection.set(SelectionPoint(e.blocks[0].id,4),SelectionPoint(e.blocks[2].id,4))
                check(e.selection.text()=="甲乙\n第二段丙丁\n最后段")
                check(e.blocks[0].value.selection==TextRange(4,6) && e.blocks[2].value.selection==TextRange(1,4))
                e.toggleStyle(InlineStyle.BOLD)
                check(e.blocks[0].spans.any { it.style==InlineStyle.BOLD && it.start==4 && it.end==6 })
                e.selection.replace("")
                check(e.title.text=="标题保留" && e.blocks.single().text=="第一段戊己")
                check(e.content().comments==listOf(thread) && e.blocks.single().spans.any { it.style==InlineStyle.COMMENT })
                e.undo(); check(e.blocks.size==3 && e.blocks[1].text=="第二段丙丁")
                e.selection.set(SelectionPoint(e.blocks[2].id,4),SelectionPoint(e.blocks[0].id,4))
                e.selection.replace("新\n内容")
                check(e.blocks.map { it.text }==listOf("第一段新","内容戊己"))
                check(e.save() && Repository.loadDoc(item.id).blocks.map { it.text }==listOf("第一段新","内容戊己"))
                val rich=DocContent(listOf(Block(text="甲乙正文",spans=listOf(StyleRange(2,4,InlineStyle.COMMENT,url=thread.id),StyleRange(2,4,InlineStyle.BOLD)))),listOf(thread))
                val second=Repository.createDoc(null,"标题",rich)
                val titleCut=EditorState(second.id,false)
                titleCut.selection.set(SelectionPoint(TITLE_KEY,1),SelectionPoint(titleCut.blocks.first().id,3))
                titleCut.selection.replace("")
                check(titleCut.title.text=="标" && titleCut.blocks.first().text=="正文" && titleCut.content().comments==listOf(thread))
                check(titleCut.blocks.first().spans.any { it.style==InlineStyle.BOLD && it.start==1 && it.end==3 })
                val code=Repository.createDoc(null,"代码",DocContent(listOf(Block(type=BlockType.CODE,text="甲\n乙\n丙"))))
                val codeEdit=EditorState(code.id,false)
                codeEdit.selection.set(SelectionPoint(codeEdit.blocks.first().id,3),SelectionPoint(codeEdit.blocks.first().id,4))
                codeEdit.selection.replace("丁")
                check(codeEdit.blocks.single().type==BlockType.CODE && codeEdit.blocks.single().text=="甲\n丁\n丙")

            }
            "continuous-line-diff" -> {
                val parts=VersionDiff.between("这段包含你，另一处才有好。","你好")
                check(parts.none { it.kind==DiffKind.SAME && it.text.any { c -> !c.isWhitespace() } })
                val before="保留整行\n原来的旧文字\n尾段不变"
                val after="保留整行\n原来的新文字\n尾段不变"
                val diff=VersionDiff.between(before,after)
                check(diff.any { it.kind==DiffKind.SAME && "保留整行" in it.text })
                check(diff.filter { it.kind!=DiffKind.ADDED }.joinToString("") { it.text }==before)
                check(diff.filter { it.kind!=DiffKind.REMOVED }.joinToString("") { it.text }==after)
            }
            "legacy-history-purge-and-backup-filter" -> {
                val item=Repository.createDoc(null,"当前",DocContent(listOf(Block(text="正文保留"))))
                val history=DocVersion(kind=VersionKind.HISTORY,title="旧历史",content=DocContent(listOf(Block(text="停止保留的历史"))))
                val local=history.copy(id=newId(),kind=VersionKind.LOCAL,title="本地版本")
                val file=File(context.filesDir,"versions/${item.id}.json")
                DurableFiles.write(file,json.encodeToString(listOf(history,local)))
                Repository.init(context)
                check(Repository.versions(item.id)==listOf(local))
                check(!file.readText().contains("HISTORY") && !File(file.path+".bak").readText().contains("HISTORY"))
                check(!Repository.addVersion(item.id,VersionKind.HISTORY,"禁止",history.content))
                check(BackupCodec.decode(Repository.exportBackup()).versions.values.flatten().all { it.kind==VersionKind.LOCAL })
                val backup=BackupCodec.decode(Repository.exportBackup())
                val old=BackupCodec.encode(backup.copy(versions=mapOf(item.id to listOf(history,local))))
                Repository.importBackup(old)
                check(Repository.versions(item.id).all { it.kind==VersionKind.LOCAL } && Repository.loadDoc(item.id).bodyText()=="正文保留")
            }
            "real-saf-backup-delete" -> runBlocking {
                val tree=DocumentsContract.buildTreeDocumentUri(target.packageName+".test-backups","root")
                Settings.updateBackup { it.copy(localTree=tree.toString()) }
                Repository.createDoc(null,"测试备份",DocContent(listOf(Block(text="只用于删除测试"))))
                val name=AutoBackup.backupLocalNow(target)
                val entry=AutoBackup.listLocal(target).first { it.name==name }
                val file=File(target.filesDir,"v27-saf-fixture/$name")
                check(file.isFile)
                TestBackupProvider.rejectDelete=true
                try { check(runCatching { AutoBackup.deleteLocal(target,entry) }.isFailure && file.exists()) } finally { TestBackupProvider.rejectDelete=false }
                check(runCatching { AutoBackup.deleteLocal(target,entry.copy(id="root/../settings.json",name="settings.json")) }.isFailure)
                AutoBackup.deleteLocal(target,entry)
                check(!file.exists() && AutoBackup.listLocal(target).none { it.id==entry.id })
            }
            "real-webdav-backup-delete" -> runBlocking {
                MockDav().use { server ->
                    val name=AutoBackup.MANUAL_PREFIX+"20261026-120000.json"
                    val other=AutoBackup.MANUAL_PREFIX+"20261026-120001.json"
                    server.files[name]="test".toByteArray(); server.files[other]="preserve".toByteArray()
                    Settings.updateBackup { it.copy(davUrl="http://127.0.0.1:${server.port}/",davDir="backup",allowInsecureDav=true) }
                    val entry=AutoBackup.listDav().first { it.name==name }
                    server.rejectDelete.set(true)
                    check(runCatching { AutoBackup.deleteDav(entry) }.isFailure && server.files.containsKey(name))
                    server.rejectDelete.set(false)
                    check(runCatching { AutoBackup.deleteDav(entry.copy(name="../settings.json",id="../settings.json")) }.isFailure)
                    AutoBackup.deleteDav(entry)
                    check(!server.files.containsKey(name) && server.files.containsKey(other) && AutoBackup.listDav().none { it.name==name })
                }
            }
        }
    }
}
