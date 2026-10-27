package com.kongye.docs

import android.content.Context
import com.kongye.docs.data.*
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import java.io.File
import java.security.MessageDigest

object V28Regression {
    val names=listOf("version-preview-current-baseline","update-source-and-version-validation","verified-update-download-and-install-intent","github-live-release-check")
    private fun sha(bytes: ByteArray)=MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
    private fun metadata(tag: String,url: String,size: Long,digest: String?=null) = buildJsonObject {
        put("tag_name",tag); put("body","更新测试"); put("draft",false); put("prerelease",false)
        putJsonArray("assets") { addJsonObject { put("name","KongyeDocs-$tag.apk"); put("browser_download_url",url); put("size",size); if(digest!=null) put("digest","sha256:$digest") } }
    }.toString()
    fun run(context: Context,name: String) {
        when(name) {
            "version-preview-current-baseline" -> {
                val selected=DocVersion(kind=VersionKind.LOCAL,title="同一标题",content=DocContent(listOf(Block(text="历史版本多出的乙"))))
                val current=DocContent(listOf(Block(text="当前正文独有的甲")))
                val parts=VersionDiff.preview("同一标题",current,selected)
                check(parts.filter { it.kind!=DiffKind.ADDED }.joinToString("") { it.text }=="同一标题\n\n"+current.plainText())
                check(parts.filter { it.kind!=DiffKind.REMOVED }.joinToString("") { it.text }=="同一标题\n\n"+selected.content.plainText())
                check(parts.any { it.kind==DiffKind.ADDED && "乙" in it.text } && parts.any { it.kind==DiffKind.REMOVED && "甲" in it.text })
            }
            "update-source-and-version-validation" -> {
                check(AppUpdates.compare("v2.10","2.9")>0 && AppUpdates.compare("2.8.0","2.8")==0 && AppUpdates.compare("2.7","2.8")<0)
                val client=AppUpdates()
                val good=metadata("v2.9","https://github.com/Mayleaves/KongyeDocs/releases/download/v2.9/app.apk",100)
                check(client.parse(good).version=="2.9")
                check(runCatching { client.parse(good.replace("github.com/Mayleaves/KongyeDocs","example.com/evil/repo")) }.isFailure)
                check(runCatching { client.parse(good.replace("v2.9","../../escape")) }.isFailure)
                check(runCatching { client.parse(good.replace("https://","http://")) }.isFailure)
                check(runCatching { client.parse(good.replace("\"prerelease\":false","\"prerelease\":true")) }.isFailure)
                check(runCatching { client.parse(metadata("v2.9","https://github.com/Mayleaves/KongyeDocs/releases/download/v2.9/app.apk",101L*1024*1024)) }.isFailure)
            }
            "verified-update-download-and-install-intent" -> runBlocking {
                val actual=AppUpdates.installed(context)
                val baseline=actual.copy(version="2.8",code=19)
                val bytes=File(context.applicationInfo.sourceDir).readBytes()
                MockDav().use { server ->
                    val url="http://127.0.0.1:${server.port}/backup/payload.apk"
                    val json=metadata("v"+actual.version,url,bytes.size.toLong(),sha(bytes))
                    server.files["latest"]=json.toByteArray(); server.files["payload.apk"]=bytes
                    val client=AppUpdates("http://127.0.0.1:${server.port}/backup/latest",true) { baseline }
                    val release=client.latest(context)
                    val file=client.download(context,release)
                    check(file.readBytes().contentEquals(bytes))
                    val intent=client.installIntent(context,file,release)
                    check(intent.action==android.content.Intent.ACTION_INSTALL_PACKAGE && intent.type==AppUpdates.APK_MIME && intent.data?.authority==context.packageName+".files")
                    check(runCatching { client.validateApk(context,file,release,baseline.copy(certificates=setOf("wrong"))) }.isFailure)
                    check(runCatching { client.validateApk(context,file,release,baseline.copy(packageName="other.app")) }.isFailure)
                    check(runCatching { client.validateApk(context,file,release,actual) }.isFailure)
                    check(runCatching { client.validateApk(context,file,release.copy(sha256="0".repeat(64)),baseline) }.isFailure)
                    server.files["payload.apk"]=bytes.copyOf(bytes.size-1)
                    check(runCatching { client.download(context,release) }.isFailure)
                    check(!File(context.cacheDir,"updates/download.part.apk").exists())
                }
            }
            "github-live-release-check" -> runBlocking {
                val release=AppUpdates().latest(context)
                check(release.url.startsWith("https://github.com/Mayleaves/KongyeDocs/releases/download/") && release.size>0)
                println("Verified live GitHub release: ${release.tag}; APK bytes=${release.size}; digest=${release.sha256!=null}")
            }
        }
    }
}
