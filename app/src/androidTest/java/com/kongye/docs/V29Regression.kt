package com.kongye.docs

import android.content.Context
import com.kongye.docs.data.*
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.*
import java.io.File

object V29Regression {
    fun timestampRoundtrip(context: Context) {
        Settings.init(context); Repository.init(context)
        val comment=Comment(text="时间持久化测试")
        val thread=CommentThread(comments=listOf(comment))
        val content=DocContent(listOf(Block(text="版本正文")),comments=listOf(thread))
        val item=Repository.createDoc(null,"时间测试",content)
        check(Repository.addVersion(item.id,VersionKind.LOCAL,item.title,content))
        val saved=Repository.versions(item.id).single()
        Repository.init(context)
        check(Repository.versions(item.id).single()==saved)
        val json=Json { encodeDefaults=true }
        val encoded=json.encodeToJsonElement(saved).jsonObject
        val oldComment=JsonObject(json.encodeToJsonElement(comment).jsonObject.filterKeys { it!="time" })
        val oldThread=JsonObject(json.encodeToJsonElement(thread).jsonObject+ ("comments" to JsonArray(listOf(oldComment))))
        val oldContent=JsonObject(json.encodeToJsonElement(content).jsonObject+ ("comments" to JsonArray(listOf(oldThread))))
        val old=JsonObject((encoded.filterKeys { it!="time" })+("content" to oldContent))
        val file=File(context.filesDir,"versions/${item.id}.json")
        file.writeText(JsonArray(listOf(old)).toString())
        val stamp=1_700_000_000_000L
        check(file.setLastModified(stamp))
        Repository.init(context)
        val migrated=Repository.versions(item.id).single()
        check(migrated.id==saved.id && migrated.title==saved.title && migrated.content.blocks==saved.content.blocks)
        check(migrated.time==stamp && migrated.content.comments.single().comments.single().time==stamp)
        check(migrated.content.comments.single().comments.single().text==comment.text)
        Repository.init(context)
        check(Repository.versions(item.id).single()==migrated)
        val recovered=json.decodeFromString<List<DocVersion>>(File(file.path+".bak").readText()).single()
        check(recovered==migrated)
        check(BackupCodec.decode(Repository.exportBackup()).versions[item.id]!!.single()==migrated)
    }
}
