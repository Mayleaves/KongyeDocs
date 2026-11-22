package com.kongye.docs.data

import android.content.Context
import android.os.Build
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.*
import okhttp3.*
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import org.w3c.dom.Element
import java.io.ByteArrayInputStream
import java.io.File
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.TimeUnit
import javax.xml.parsers.DocumentBuilderFactory

@Serializable internal data class SyncVersion(val id: String,val revision: String,val clock: Map<String,Long>,
    val value: JsonElement?=null,val device: String="",val time: Long=0)
@Serializable internal data class SyncState(val clock: Map<String,Long> = emptyMap(),val versions: List<SyncVersion> = emptyList())
@Serializable internal data class LocalSyncState(val device: String=UUID.randomUUID().toString(),val state: SyncState=SyncState(),
    val applied: Map<String,JsonElement> = emptyMap(),val uploadedHash: String="")
@Serializable private data class SyncEnvelope(val format: Int=1,val kind: String,val encrypted: VaultBackup)
data class SyncConflict(val kind: String,val id: String,val name: String,val versions: List<SyncChoice>)
data class SyncChoice(val revision: String,val device: String,val time: Long,val deleted: Boolean)
data class CloudSyncStatus(val running: Boolean=false,val message: String="",val lastSuccess: Long=0,val conflicts: List<SyncConflict> = emptyList())

/** Vector clocks keep concurrent edits and deletion markers, regardless of wall-clock skew. */
internal object SyncMerge {
    fun clock(a: Map<String,Long>,b: Map<String,Long>)=(a.keys+b.keys).associateWith { maxOf(a[it] ?: 0,b[it] ?: 0) }
    private fun dominates(a: Map<String,Long>,b: Map<String,Long>)=
        b.all { (k,v) -> (a[k] ?: 0)>=v } && (a.keys+b.keys).any { (a[it] ?: 0)>(b[it] ?: 0) }
    fun merge(a: SyncState,b: SyncState): SyncState {
        val records=(a.versions+b.versions).groupBy { it.revision }.map { (_,v) ->
            require(v.distinct().size==1) { "同步版本标识冲突" }; v.first()
        }.groupBy { it.id }.values.flatMap { records ->
            records.filter { r -> records.none { it!==r && dominates(it.clock,r.clock) } }
        }.sortedWith(compareBy({it.id},{it.revision}))
        return SyncState(clock(a.clock,b.clock),records)
    }
    fun reconcile(local: LocalSyncState,current: Map<String,JsonElement>,deviceName: String): LocalSyncState {
        val ids=(local.applied.keys+current.keys).filter { local.applied[it]!=current[it] }
        if(ids.isEmpty()) return local
        val clock=local.state.clock.toMutableMap().apply { this[local.device]=Math.addExact(this[local.device] ?: 0,1L) }
        val changes=ids.map { id ->
            // Only observed versions of THIS item are causal parents. An open
            // editor may still show an older value while a remote version waits.
            val parents=local.state.versions.filter { it.id==id && it.value==local.applied[id] }
                .fold(emptyMap<String,Long>()) { c,v -> SyncMerge.clock(c,v.clock) }.toMutableMap()
            parents[local.device]=clock.getValue(local.device)
            SyncVersion(id,UUID.randomUUID().toString(),parents,current[id],deviceName,System.currentTimeMillis())
        }
        return local.copy(state=merge(local.state,SyncState(clock,changes)),applied=current)
    }
    fun materialize(state: SyncState,current: Map<String,JsonElement>): Map<String,JsonElement> =
        state.versions.groupBy { it.id }.mapNotNull { (id,versions) ->
            val values=versions.map { it.value }.distinct()
            val observed=versions.firstOrNull { it.value==current[id] }
            val chosen=if(values.size==1) values.first() else if(observed!=null) observed.value
                else versions.filter { it.value!=null }.minByOrNull { it.revision }?.value
            chosen?.let { id to it }
        }.toMap()
}

/** Each installation writes its own encrypted checkpoint; devices never overwrite each other. */
object CloudSync {
    internal val json=Json { ignoreUnknownKeys=true; encodeDefaults=true }
    private val scope=CoroutineScope(SupervisorJob()+Dispatchers.IO)
    private val mutex=Mutex()
    private var pending: Job?=null
    private var statusScope=""
    private val _status=MutableStateFlow(CloudSyncStatus())
    val status=_status.asStateFlow()
    private val client=OkHttpClient.Builder().connectTimeout(15,TimeUnit.SECONDS).callTimeout(90,TimeUnit.SECONDS).build()
    private val namePattern=Regex("(vault|docs)-[a-f0-9-]{36}\\.json")
    internal fun scopeKey(s: BackupSettings)=MessageDigest.getInstance("SHA-256")
        .digest((s.davUrl.trim().trimEnd('/')+"\n"+s.davUser).toByteArray()).take(12).joinToString("") { "%02x".format(it) }
    private fun root(context: Context)=context.applicationContext.filesDir
    private fun relative(s: BackupSettings,kind: String)="cloud-sync/${scopeKey(s)}/$kind.json"
    private fun load(context: Context,s: BackupSettings,kind: String): LocalSyncState {
        val file=File(root(context),relative(s,kind))
        if(!file.exists() && !File(file.parentFile,file.name+".bak").exists()) return LocalSyncState()
        return DurableFiles.read(file) { json.decodeFromString<LocalSyncState>(CredentialStore.decrypt(it,CredentialStore.VAULT_ALIAS)) }
    }
    private fun encoded(state: LocalSyncState)=CredentialStore.encrypt(json.encodeToString(state),CredentialStore.VAULT_ALIAS).toByteArray()
    private fun lock(kind: String): Any=if(kind=="vault") VaultStore else Repository
    private fun snapshot(kind: String)=if(kind=="vault") VaultStore.syncSnapshot() else Repository.syncSnapshot()
    private fun apply(context: Context,s: BackupSettings,kind: String,state: LocalSyncState,values: Map<String,JsonElement>) {
        val metadata=relative(s,kind) to encoded(state.copy(applied=values))
        if(kind=="vault") VaultStore.applySync(values,metadata) else Repository.applySync(values,metadata)
    }
    fun request(context: Context,delayMillis: Long=0) {
        val s=Settings.value.backup
        if(!s.cloudSync || s.davUrl.isBlank() || s.vaultPassword.isEmpty()) return
        val app=context.applicationContext
        synchronized(this) {
            // Mutations debounce network access; a running exchange is allowed to finish.
            pending?.takeIf { !it.isActive || delayMillis>0 }?.cancel()
            pending=scope.launch { delay(delayMillis); run(app) }
        }
    }
    fun changed(context: Context) = request(context,1200)
    suspend fun run(context: Context) = mutex.withLock {
        val storage=Repository.syncContext
        val s=Settings.value.backup
        if(!s.cloudSync || s.davUrl.isBlank() || s.vaultPassword.isEmpty()) return@withLock
        if(statusScope!=scopeKey(s)) { statusScope=scopeKey(s); _status.value=CloudSyncStatus() }
        _status.value=_status.value.copy(running=true,message="正在同步…")
        try {
            val directory=directory(s)
            for(url in listOf(directory.newBuilder().removePathSegment(directory.pathSize-2).build(),directory)) {
                client.newCall(request(url,s).method("MKCOL",null).build()).execute().use {
                    require(it.isSuccessful || it.code==405) { "无法创建同步目录（${it.code}）" }
                }
            }
            for(kind in listOf("vault","docs")) {
                require(Repository.syncContext.filesDir==storage.filesDir) { "数据目录已切换，等待下次同步" }
                exchange(storage,s,kind,directory)
            }
            _status.value=_status.value.copy(running=false,message=if(_status.value.conflicts.isEmpty()) "已同步" else "有冲突待处理",lastSuccess=System.currentTimeMillis())
        } catch(cancelled: CancellationException) { _status.value=_status.value.copy(running=false); throw cancelled }
        catch(error: Exception) { _status.value=_status.value.copy(running=false,message="同步失败："+(error.message ?: "请检查网络及备份密码")) }
    }
    private fun directory(s: BackupSettings): HttpUrl {
        val raw=AutoBackup.normalizeUrl(s.davUrl).let { if(it.startsWith("http")) it else "https://$it" }
        val url=raw.toHttpUrl()
        require(url.isHttps || s.allowInsecureDav) { "请使用 HTTPS 或开启局域网 HTTP" }
        return url.newBuilder().addPathSegment("KongyeDocs").addPathSegment("sync-v1").addPathSegment("").build()
    }
    private fun request(url: HttpUrl,s: BackupSettings)=Request.Builder().url(url).header("Authorization",Credentials.basic(s.davUser,s.davPassword,Charsets.UTF_8))
    private fun list(s: BackupSettings,dir: HttpUrl): List<String> {
        val body="""<?xml version="1.0"?><d:propfind xmlns:d="DAV:"><d:prop><d:getetag/></d:prop></d:propfind>""".toRequestBody("application/xml".toMediaType())
        return client.newCall(request(dir,s).header("Depth","1").method("PROPFIND",body).build()).execute().use { r ->
            require(r.code==207 || r.isSuccessful) { "无法读取同步目录（${r.code}）" }
            val source=read(r).decodeToString()
            // Android's built-in parser doesn't expose the Xerces feature flags.
            // Reject declarations before parsing so external entities cannot load.
            require(!source.contains("<!DOCTYPE",true) && !source.contains("<!ENTITY",true)) { "同步目录 XML 不允许外部实体" }
            val factory=DocumentBuilderFactory.newInstance().apply { isNamespaceAware=true }
            val xml=factory.newDocumentBuilder().parse(ByteArrayInputStream(source.toByteArray()))
            val links=xml.getElementsByTagNameNS("DAV:","href")
            (0 until links.length).mapNotNull { index ->
                val url=dir.resolve(links.item(index).textContent) ?: return@mapNotNull null
                if(url.host!=dir.host || url.port!=dir.port || url.scheme!=dir.scheme || url.encodedPath.substringBeforeLast('/')!=dir.encodedPath.trimEnd('/')) return@mapNotNull null
                url.pathSegments.lastOrNull()?.takeIf(namePattern::matches)
            }.distinct()
        }
    }
    private fun read(r: Response): ByteArray {
        val body=requireNotNull(r.body)
        require(body.contentLength()<=BackupCodec.MAX_BYTES) { "同步数据超过 64 MB" }
        return body.byteStream().use { input ->
            val out=java.io.ByteArrayOutputStream(); val buffer=ByteArray(8192)
            while(true) { val n=input.read(buffer); if(n<0) break; require(out.size()+n<=BackupCodec.MAX_BYTES) { "同步数据超过 64 MB" }; out.write(buffer,0,n) }
            out.toByteArray()
        }
    }
    private fun validate(state: SyncState) {
        require(state.clock.size<=10000 && state.clock.values.all { it>0 }) { "同步时钟无效" }
        require(state.versions.size<=100000 && state.versions.map { it.revision }.distinct().size==state.versions.size) { "同步记录无效" }
        state.versions.forEach { v ->
            require(BackupCodec.validId(v.id) && v.clock.isNotEmpty() && v.clock.all { (k,n) -> n>0 && n<=(state.clock[k] ?: 0) }) { "同步版本无效" }
        }
    }
    private fun exchange(context: Context,s: BackupSettings,kind: String,dir: HttpUrl) {
        synchronized(lock(kind)) {
            val local=SyncMerge.reconcile(load(context,s,kind),snapshot(kind),Build.MODEL)
            DurableFiles.write(File(root(context),relative(s,kind)),encoded(local))
        }
        var remote=SyncState()
        val remoteFiles=list(s,dir).filter { it.startsWith("$kind-") }
        remoteFiles.forEach { name ->
            val bytes=client.newCall(request(dir.newBuilder().addPathSegment(name).build(),s).get().build()).execute().use {
                require(it.isSuccessful) { "读取同步文件失败（${it.code}）" }; read(it)
            }
            val envelope=json.decodeFromString<SyncEnvelope>(bytes.decodeToString())
            require(envelope.format==1 && envelope.kind==kind) { "同步格式不支持" }
            val state=json.decodeFromString<SyncState>(VaultStore.openText(envelope.encrypted,s.vaultPassword))
            validate(state)
            remote=SyncMerge.merge(remote,state)
        }
        val checkpoint=synchronized(lock(kind)) {
            require(Settings.value.backup.let { scopeKey(it)==scopeKey(s) && it.vaultPassword==s.vaultPassword && it.cloudSync }) { "同步设置已改变" }
            require(Repository.syncContext.filesDir==context.filesDir) { "数据目录已切换，等待下次同步" }
            val current=snapshot(kind)
            val local=SyncMerge.reconcile(load(context,s,kind),current,Build.MODEL)
            val merged=SyncMerge.merge(local.state,remote)
            var materialized=SyncMerge.materialize(merged,current)
            if(kind=="docs") {
                // Don't replace a live editor's body or its unsaved text. The
                // next exchange after closing it will apply the waiting value.
                val opened=com.kongye.docs.ui.editor.EditorSession.openIds()
                materialized=materialized.filterKeys { it !in opened }+current.filterKeys { it in opened }
            }
            val result=local.copy(state=merged,applied=materialized)
            apply(context,s,kind,result,materialized)
            updateConflicts(kind,merged)
            result
        }
        val plain=json.encodeToString(checkpoint.state)
        val fingerprint=MessageDigest.getInstance("SHA-256").digest(plain.toByteArray()).joinToString("") { "%02x".format(it) }
        if(checkpoint.uploadedHash==fingerprint && "$kind-${checkpoint.device}.json" in remoteFiles) return
        val envelope=SyncEnvelope(kind=kind,encrypted=VaultStore.sealText(plain,s.vaultPassword))
        val bytes=json.encodeToString(envelope).toByteArray()
        require(bytes.size<=BackupCodec.MAX_BYTES) { "同步数据超过 64 MB" }
        val url=dir.newBuilder().addPathSegment("$kind-${checkpoint.device}.json").build()
        client.newCall(request(url,s).put(bytes.toRequestBody("application/json".toMediaType())).build()).execute().use {
            require(it.isSuccessful) { "上传同步数据失败（${it.code}）" }
        }
        val verify=client.newCall(request(url,s).get().build()).execute().use { require(it.isSuccessful) { "同步回读失败" }; read(it) }
        require(bytes.contentEquals(verify)) { "同步回读校验失败" }
        synchronized(lock(kind)) {
            val latest=load(context,s,kind)
            DurableFiles.write(File(root(context),relative(s,kind)),encoded(latest.copy(uploadedHash=fingerprint)))
        }
    }
    private fun updateConflicts(kind: String,state: SyncState) {
        val conflicts=state.versions.groupBy { it.id }.filterValues { it.map { v->v.value }.distinct().size>1 }.map { (id,v) ->
            val value=v.firstOrNull { it.value!=null }?.value?.jsonObject
            val name=if(kind=="vault") value?.get("name")?.jsonPrimitive?.content else value?.get("item")?.jsonObject?.get("title")?.jsonPrimitive?.content
            SyncConflict(kind,id,name.orEmpty().ifEmpty { if(kind=="vault") "密码" else "文档" },v.map { SyncChoice(it.revision,it.device,it.time,it.value==null) })
        }
        _status.value=_status.value.copy(conflicts=_status.value.conflicts.filterNot { it.kind==kind }+conflicts)
    }
    fun resolve(context: Context,conflict: SyncConflict,revision: String) {
        scope.launch {
            runCatching { resolveLocal(context,conflict,revision) }
                .onFailure { e -> _status.value=_status.value.copy(message="处理失败：${e.message}") }
            request(context)
        }
    }

    internal suspend fun resolveLocal(context: Context,conflict: SyncConflict,revision: String) = mutex.withLock {
        val s=Settings.value.backup
        synchronized(lock(conflict.kind)) {
            val current=snapshot(conflict.kind)
            val local=SyncMerge.reconcile(load(context,s,conflict.kind),current,Build.MODEL)
            val choice=local.state.versions.single { it.id==conflict.id && it.revision==revision }
            val clock=local.state.clock.toMutableMap().apply { this[local.device]=Math.addExact(this[local.device] ?: 0,1L) }
            val versions=local.state.versions.filterNot { it.id==conflict.id }+
                SyncVersion(conflict.id,UUID.randomUUID().toString(),clock,choice.value,Build.MODEL,System.currentTimeMillis())
            val state=SyncState(clock,versions)
            var values=SyncMerge.materialize(state,current)
            if(conflict.kind=="docs") {
                val opened=com.kongye.docs.ui.editor.EditorSession.openIds()
                values=values.filterKeys { it !in opened }+current.filterKeys { it in opened }
            }
            apply(context,s,conflict.kind,local.copy(state=state,applied=values),values)
            updateConflicts(conflict.kind,state)
        }
    }

    internal fun preview(context: Context,conflict: SyncConflict,revision: String): JsonElement? =
        synchronized(lock(conflict.kind)) {
            load(context,Settings.value.backup,conflict.kind).state.versions
                .single { it.id==conflict.id && it.revision==revision }.value
        }
}
