package com.kongye.docs

import android.content.Context
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import com.kongye.docs.data.*
import com.kongye.docs.ui.AppState
import com.kongye.docs.ui.Screen
import com.kongye.docs.ui.Tab
import com.kongye.docs.ui.editor.*
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import java.io.File
import kotlin.random.Random

object FeatureRegression {
    val names=listOf("version-copy-delete-restore","configured-backup-frequency","local-versions-v25-switch-and-interval","whole-article-actions","unicode-change-diff","legacy-version-checksum")
    private val json=Json { encodeDefaults=true; ignoreUnknownKeys=true }
    private fun init(context: Context) {
        AppState.goHome(Tab.HOME)
        context.filesDir.mkdirs()
        DurableFiles.write(File(context.filesDir,"library.json"),json.encodeToString(Library()))
        Settings.init(context); Repository.init(context)
    }
    fun run(context: Context,name: String) {
        when(name) {
            "version-copy-delete-restore" -> {
                init(context)
                val image = newId()+".png"
                val bytes = byteArrayOf(1,2,3,4)
                DurableFiles.write(File(Repository.imagesDir,image),bytes)
                val thread = CommentThread(comments=listOf(Comment(text="版本评论")))
                val old = DocContent(listOf(Block(text="版本正文",spans=listOf(StyleRange(0,2,InlineStyle.COMMENT,url=thread.id))),Block(type=BlockType.IMAGE,image=image)),listOf(thread))
                val now = DocContent(listOf(Block(text="当前正文")))
                val item = Repository.createDoc(null,"当前标题",now)
                Repository.addVersion(item.id,VersionKind.LOCAL,"旧标题",old)
                val version = Repository.versions(item.id).single()
                val copy = Repository.duplicateVersion(item.id,version.id)
                val copied = Repository.loadDoc(copy.id)
                check(copy.id!=item.id && copy.title=="旧标题 副本")
                check(copied.bodyText()==old.bodyText() && copied.comments==old.comments)
                check(copied.blocks.first().spans==old.blocks.first().spans)
                val copyImage = copied.blocks.last().image
                check(copyImage!=image && File(Repository.imagesDir,copyImage).readBytes().contentEquals(bytes))
                check(Repository.loadDoc(item.id)==now && Repository.get(item.id)!!.title=="当前标题")
                check(Repository.deleteVersion(item.id,version.id) && !Repository.deleteVersion(item.id,version.id))
                check(Repository.versions(item.id).isEmpty() && Repository.loadDoc(item.id)==now)
                check(File(Repository.imagesDir,image).exists() && File(Repository.imagesDir,copyImage).exists())
                Repository.init(context)
                check(Repository.versions(item.id).isEmpty())
                check(Repository.loadDoc(copy.id)==copied)
                Repository.restoreVersion(item.id,version)
                check(Repository.loadDoc(item.id)==old)
                check(Repository.versions(item.id).any { it.content==now && it.title=="当前标题" })
            }
            "configured-backup-frequency" -> {
                val now=System.currentTimeMillis()
                for(hours in listOf(6,24,72,168)) {
                    check(!AutoBackup.isDue(now-4*60_000L,hours,now))
                    check(!AutoBackup.isDue(now-hours*3_600_000L+1,hours,now))
                    check(AutoBackup.isDue(now-hours*3_600_000L,hours,now))
                }
                check(AutoBackup.isDue(0,24,now))
                check(!AutoBackup.isDue(now+3_600_000L,6,now))
                check(AutoBackup.isDue(now-7*3_600_000L,6,now) && !AutoBackup.isDue(now-7*3_600_000L,24,now))
            }
            "local-versions-v25-switch-and-interval" -> {
                init(context)
                check(Settings.value.autoLocalVersion)
                Settings.update { it.copy(autoLocalVersion=false) }
                val item=Repository.createDoc(null,"本地版本",DocContent(listOf(Block(text="修改前"))))
                val editor=EditorSession.obtain(item.id,false)
                AppState.push(Screen.Editor(item.id))
                val block=editor.blocks.first()
                editor.onValueChange(block,TextFieldValue(ZWS+"修改后",TextRange(4)))
                check(editor.save() && Repository.versions(item.id).isEmpty())
                AppState.push(Screen.Versions(item.id)); check(AppState.pop())
                check(EditorSession.obtain(item.id,false)===editor)
                check(AppState.pop() && Repository.versions(item.id).isEmpty())
                Settings.update { it.copy(autoLocalVersion=true) }
                val reopened=EditorSession.obtain(item.id,false)
                AppState.push(Screen.Editor(item.id))
                reopened.onTitleChange(TextFieldValue("新标题"))
                check(reopened.save())
                check(Repository.versions(item.id).size==1 && Repository.versions(item.id).single().kind==VersionKind.LOCAL)
                reopened.onTitleChange(TextFieldValue("再次修改")); check(reopened.save())
                check(Repository.versions(item.id).size==1)
                check(AppState.pop() && Repository.versions(item.id).size==1)
                check(Repository.addVersion(item.id,VersionKind.LOCAL,"再次修改",Repository.loadDoc(item.id),"手动保存"))
                check(Repository.versions(item.id).size==2)
                Repository.clearVersions(item.id,VersionKind.LOCAL)
                check(Repository.versions(item.id).isEmpty())
            }
            "whole-article-actions" -> {
                init(context)
                val original=DocContent(listOf(Block(text="第一段甲乙"),Block(text="最后一段丙丁"),Block(type=BlockType.TABLE,table=listOf(listOf("表格甲","表格乙")))))
                val item=Repository.createDoc(null,"全文标题",original)
                val editor=EditorState(item.id,false)
                editor.focusedId=editor.blocks.first().id
                editor.selectArticle()
                check(editor.articleSelected && editor.title.selection.collapsed)
                check(editor.blocks.filter { it.type.isText }.all { it.value.selection.min==1 && it.value.selection.max==it.value.text.length })
                val copied=editor.selectedArticleText()
                check(listOf("第一段甲乙","最后一段丙丁","表格甲","表格乙").all { it in copied } && "全文标题" !in copied && ZWS !in copied)
                editor.toggleStyle(InlineStyle.BOLD)
                check(editor.blocks.filter { it.type.isText }.all { it.spans.any { r -> r.style==InlineStyle.BOLD && r.start==1 && r.end==it.value.text.length } })
                editor.replaceArticleSelection("")
                check(editor.title.text=="全文标题" && editor.content().bodyText().isEmpty())
                editor.undo()
                check(editor.title.text=="全文标题" && editor.blocks.size==3)
                editor.selectArticle()
                editor.onValueChange(editor.blocks.first(),TextFieldValue(ZWS+"替换全文",TextRange(5)))
                check(editor.title.text=="全文标题" && editor.blocks.size==1 && editor.blocks.single().text=="替换全文")
                editor.undo(); check(editor.title.text=="全文标题" && editor.blocks.size==3)
            }
            "unicode-change-diff" -> {
                fun verify(a: String,b: String) {
                    val parts=VersionDiff.between(a,b)
                    check(parts.filter { it.kind!=DiffKind.ADDED }.joinToString("") { it.text }==a)
                    check(parts.filter { it.kind!=DiffKind.REMOVED }.joinToString("") { it.text }==b)
                    parts.forEach { p ->
                        var i=0
                        while(i<p.text.length) {
                            if(Character.isHighSurrogate(p.text[i])) { check(i+1<p.text.length && Character.isLowSurrogate(p.text[i+1])); i+=2 }
                            else { check(!Character.isLowSurrogate(p.text[i])); i++ }
                        }
                    }
                }
                val diff=VersionDiff.between("原来的旧文字","原来的新文字")
                check(diff.any { it.kind==DiffKind.REMOVED && it.text=="旧" } && diff.any { it.kind==DiffKind.ADDED && it.text=="新" })
                val random=Random(26)
                val chars=listOf("甲","乙","a","b","\n","🙂","👩🏽‍💻","é")
                repeat(350) {
                    val a=List(random.nextInt(30)) { chars.random(random) }.joinToString("")
                    val b=List(random.nextInt(30)) { chars.random(random) }.joinToString("")
                    verify(a,b)
                }
                verify("","新增"); verify("删除","")
                verify("a".repeat(120_000)+"旧"+"b".repeat(120_000),"a".repeat(120_000)+"新"+"b".repeat(120_000))
                verify("甲".repeat(1500),"乙".repeat(1500))
            }
            "legacy-version-checksum" -> {
                init(context)
                val content=DocContent(listOf(Block(text="旧快照")))
                val old=DocVersion(kind=VersionKind.LOCAL,title="旧版本",content=content)
                check("previousText" !in json.parseToJsonElement(json.encodeToString(old)).jsonObject)
                val item=Repository.createDoc(null,"旧版本",content)
                Repository.addVersion(item.id,VersionKind.LOCAL,"旧版本",content)
                val backup=Repository.exportBackup()
                check(BackupCodec.decode(backup).versions[item.id]!!.single().content==content)
            }
        }
    }
}
