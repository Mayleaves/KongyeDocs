package com.kongye.docs.data

import android.content.Context
import android.content.Intent
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import androidx.core.content.FileProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.*
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.nio.file.Files
import java.nio.file.StandardCopyOption.ATOMIC_MOVE
import java.nio.file.StandardCopyOption.REPLACE_EXISTING
import java.security.MessageDigest
import java.util.concurrent.TimeUnit
import kotlin.coroutines.coroutineContext

data class AppRelease(val version: String,val tag: String,val notes: String,val url: String,val size: Long,val sha256: String?)
data class InstalledApp(val packageName: String,val version: String,val code: Long,val certificates: Set<String>)

/** Public releases only. The installation itself is performed by Android's package installer. */
internal class AppUpdates(
    private val api: String = API,
    private val localFixture: Boolean = false,
    private val currentApp: (Context)->InstalledApp = { installed(it) },
) {
    companion object {
        const val REPOSITORY="https://github.com/Mayleaves/KongyeDocs"
        const val API="https://api.github.com/repos/Mayleaves/KongyeDocs/releases/latest"
        const val APK_MIME="application/vnd.android.package-archive"
        private const val MAX_APK=100L*1024*1024
        private val versionPattern=Regex("^v?([0-9]+(?:\\.[0-9]+){0,3})$")
        fun version(value: String): List<Long> {
            val text=versionPattern.matchEntire(value.trim())?.groupValues?.get(1) ?: error("发布版本号格式不受支持")
            return text.split('.').map { it.toLongOrNull() ?: error("发布版本号无效") }
        }
        fun compare(a: String,b: String): Int {
            val x=version(a); val y=version(b)
            repeat(maxOf(x.size,y.size)) { i -> val order=x.getOrElse(i){0L}.compareTo(y.getOrElse(i){0L}); if(order!=0) return order }
            return 0
        }
        private fun digest(bytes: ByteArray)=MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
        @Suppress("DEPRECATION")
        fun installed(context: Context): InstalledApp = packageInfo(context.packageManager.getPackageInfo(context.packageName,if(Build.VERSION.SDK_INT>=28) PackageManager.GET_SIGNING_CERTIFICATES else PackageManager.GET_SIGNATURES))
        @Suppress("DEPRECATION")
        private fun packageInfo(info: PackageInfo): InstalledApp {
            val signatures=if(Build.VERSION.SDK_INT>=28) info.signingInfo?.apkContentsSigners else info.signatures
            return InstalledApp(info.packageName,info.versionName ?: "0",if(Build.VERSION.SDK_INT>=28) info.longVersionCode else info.versionCode.toLong(),signatures.orEmpty().map { digest(it.toByteArray()) }.toSet())
        }
    }
    private val client=OkHttpClient.Builder().connectTimeout(20,TimeUnit.SECONDS).readTimeout(30,TimeUnit.SECONDS).followSslRedirects(false).build()
    private fun isFixture(url: String)=localFixture && url.toHttpUrl().host in setOf("127.0.0.1","localhost")
    internal fun parse(text: String): AppRelease {
        val json=Json.parseToJsonElement(text).jsonObject
        require(json["draft"]?.jsonPrimitive?.booleanOrNull!=true && json["prerelease"]?.jsonPrimitive?.booleanOrNull!=true) { "不是正式发布版本" }
        val tag=json["tag_name"]?.jsonPrimitive?.content ?: error("发布信息缺少版本号")
        val ver=versionPattern.matchEntire(tag)?.groupValues?.get(1) ?: error("发布版本号格式不受支持")
        version(ver)
        val assets=json["assets"]?.jsonArray.orEmpty().map { it.jsonObject }
        val apk=assets.firstOrNull { it["name"]?.jsonPrimitive?.content?.endsWith(".apk",true)==true } ?: error("最新版本没有可安装的 APK 附件")
        val url=apk["browser_download_url"]?.jsonPrimitive?.content ?: error("APK 下载地址缺失")
        val size=apk["size"]?.jsonPrimitive?.longOrNull ?: error("APK 大小缺失")
        require(size in 1..MAX_APK) { "APK 大小异常" }
        val parsed=url.toHttpUrl()
        require(isFixture(url) || parsed.isHttps && parsed.host=="github.com" && parsed.pathSegments.take(5)==listOf("Mayleaves","KongyeDocs","releases","download",tag)) { "APK 不是此仓库的发布附件" }
        val rawDigest=apk["digest"]?.jsonPrimitive?.contentOrNull
        val sha=rawDigest?.let { require(it.startsWith("sha256:")) { "APK 校验格式不受支持" }; it.removePrefix("sha256:").lowercase() }
        require(sha==null || Regex("[0-9a-f]{64}").matches(sha)) { "APK 校验值无效" }
        return AppRelease(ver,tag,json["body"]?.jsonPrimitive?.contentOrNull.orEmpty().take(6000),url,size,sha)
    }
    suspend fun latest(context: Context): AppRelease = withContext(Dispatchers.IO) {
        require(api==API || isFixture(api)) { "不受信任的更新源" }
        val prefs=context.getSharedPreferences("app-updates",Context.MODE_PRIVATE)
        val request=Request.Builder().url(api).header("Accept","application/vnd.github+json").header("User-Agent","KongyeDocs/"+installed(context).version)
        if(api==API) prefs.getString("etag",null)?.let { request.header("If-None-Match",it) }
        client.newCall(request.build()).execute().use { r ->
            val body=when(r.code) {
                304 -> prefs.getString("release",null) ?: error("更新缓存失效，请重试")
                404 -> error("仓库还没有正式发布版本")
                403,429 -> error("GitHub 暂时限制了请求，请稍后重试")
                else -> {
                    check(r.isSuccessful) { "检查更新失败（${r.code}）" }
                    val input=r.body?.byteStream() ?: error("更新信息为空")
                    val output=java.io.ByteArrayOutputStream()
                    input.use { stream ->
                        val buffer=ByteArray(8192)
                        while(true) { val n=stream.read(buffer); if(n<0) break; require(output.size()+n<=256*1024) { "更新信息过大" }; output.write(buffer,0,n) }
                    }
                    output.toString("UTF-8")
                }
            }
            val release=parse(body)
            if(api==API && r.code!=304) prefs.edit().putString("etag",r.header("ETag")).putString("release",body).apply()
            release
        }
    }
    internal fun validateApk(context: Context,file: File,release: AppRelease,current: InstalledApp = currentApp(context)) {
        require(file.isFile && file.length()==release.size) { "APK 下载不完整" }
        release.sha256?.let { expected ->
            val sum=MessageDigest.getInstance("SHA-256")
            file.inputStream().use { input -> val buffer=ByteArray(65536); while(true) { val n=input.read(buffer); if(n<0) break; sum.update(buffer,0,n) } }
            require(sum.digest().joinToString("") { "%02x".format(it) }==expected) { "APK 完整性校验失败，请重新下载" }
        }
        @Suppress("DEPRECATION")
        val info=context.packageManager.getPackageArchiveInfo(file.path,if(Build.VERSION.SDK_INT>=28) PackageManager.GET_SIGNING_CERTIFICATES else PackageManager.GET_SIGNATURES) ?: error("无法读取安装包")
        val archive=packageInfo(info)
        require(archive.packageName==current.packageName) { "安装包不属于空叶文档" }
        require(current.certificates.isNotEmpty() && archive.certificates==current.certificates) { "安装包签名不一致，已停止安装" }
        require(archive.code>current.code && compare(archive.version,current.version)>0) { "此安装包不是更新版本" }
        require(compare(archive.version,release.version)==0) { "安装包版本与发布信息不一致" }
    }
    suspend fun download(context: Context,release: AppRelease,onProgress: (Float)->Unit = {}): File = withContext(Dispatchers.IO) {
        require(compare(release.version,currentApp(context).version)>0) { "已安装此版本或更新版本" }
        // Re-validate the source even if a caller supplies a release directly.
        val url=release.url.toHttpUrl()
        require(isFixture(release.url) || url.isHttps && url.host=="github.com" && url.pathSegments.take(5)==listOf("Mayleaves","KongyeDocs","releases","download",release.tag)) { "不受信任的下载地址" }
        version(release.version)
        val root=File(context.cacheDir,"updates").apply { mkdirs() }
        val part=File(root,"download.part.apk")
        val target=File(root,"KongyeDocs-${release.version}.apk")
        try {
            client.newCall(Request.Builder().url(url).header("User-Agent","KongyeDocs").build()).execute().use { r ->
                check(r.isSuccessful) { "下载失败（${r.code}）" }
                val finalUrl=r.request.url
                require(isFixture(finalUrl.toString()) || finalUrl.isHttps && (finalUrl.host=="github.com" || finalUrl.host.endsWith(".githubusercontent.com"))) { "下载被重定向到不受信任的地址" }
                val input=r.body?.byteStream() ?: error("APK 内容为空")
                var written=0L; var last=0L
                input.use { stream -> FileOutputStream(part).use { output ->
                    val buffer=ByteArray(65536)
                    while(true) {
                        coroutineContext.ensureActive()
                        val n=stream.read(buffer); if(n<0) break
                        written+=n; require(written<=release.size && written<=MAX_APK) { "APK 大小与发布信息不一致" }
                        output.write(buffer,0,n)
                        val now=android.os.SystemClock.uptimeMillis()
                        if(now-last>100) { last=now; withContext(Dispatchers.Main) { onProgress(written.toFloat()/release.size) } }
                    }
                    output.fd.sync()
                } }
            }
            validateApk(context,part,release)
            Files.move(part.toPath(),target.toPath(),ATOMIC_MOVE,REPLACE_EXISTING)
            withContext(Dispatchers.Main) { onProgress(1f) }
            target
        } catch(error: Throwable) { part.delete(); throw error }
    }
    suspend fun installIntent(context: Context,file: File,release: AppRelease): Intent = withContext(Dispatchers.IO) {
        validateApk(context,file,release)
        val root=File(context.cacheDir,"updates").canonicalFile
        require(file.canonicalFile.parentFile==root) { "安装包路径无效" }
        val uri=FileProvider.getUriForFile(context,context.packageName+".files",file)
        Intent(Intent.ACTION_INSTALL_PACKAGE).setDataAndType(uri,APK_MIME).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK).apply {
            clipData=android.content.ClipData.newRawUri("APK",uri)
        }
    }
}
