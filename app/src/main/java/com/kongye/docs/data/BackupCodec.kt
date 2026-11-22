package com.kongye.docs.data

import android.util.Base64
import android.util.Base64OutputStream
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.decodeFromStream
import kotlinx.serialization.json.encodeToStream
import java.io.File
import java.io.FilterInputStream
import java.io.FilterOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.io.RandomAccessFile
import java.security.DigestOutputStream
import java.security.MessageDigest

/** The v1 reader remains compatible with backups produced by 2.1, which omitted default fields. */
@OptIn(ExperimentalSerializationApi::class)
object BackupCodec {
    const val MAX_BYTES = 64 * 1024 * 1024
    const val APP_ID = "kongye-docs"
    /** 2.1–2.2 写的是旧名 moji-notes，仍然可以恢复 */
    private val supportedApps = setOf(APP_ID, "moji-notes")
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private val id = Regex("[0-9a-f]{16}")
    private val image = Regex("[0-9a-f]{16}\\.(?:jpg|jpeg|png)")
    private val exportedFile = Regex("[0-9a-f]{16}\\.(?:png|txt|md)")
    fun validId(value: String) = id.matches(value)
    fun validImage(value: String) = image.matches(value)
    fun validateLibrary(library: Library) {
        val all=library.items.associateBy { it.id }
        require(all.size==library.items.size && all.keys.all(::validId)) { "目录包含非法或重复 ID" }
        require(library.templates.all { validId(it.id) } && library.templates.map { it.id }.distinct().size==library.templates.size) { "模板目录无效" }
        all.values.forEach { item ->
            val seen=mutableSetOf(item.id)
            var parent=item.parentId
            while(parent!=null) {
                val folder=all[parent]
                require(folder?.isFolder==true && seen.add(parent)) { "目录关系无效" }
                parent=folder.parentId
            }
        }
    }
    private fun sha256() = MessageDigest.getInstance("SHA-256")
    private fun ByteArray.hex() = joinToString("") { "%02x".format(it) }
    fun sha(bytes: ByteArray) = sha256().digest(bytes).hex()
    private fun canonical(backup: Backup) = backup.copy(
        checksum = "", docs = backup.docs.toSortedMap(), images = backup.images.toSortedMap(),
        versions = backup.versions.toSortedMap(), templates = backup.templates.toSortedMap(), exportFiles = backup.exportFiles.toSortedMap(),
    )
    fun encode(backup: Backup): String {
        val unsigned = canonical(backup.copy(version = 2))
        val signed = unsigned.copy(checksum = sha(json.encodeToString(unsigned).toByteArray()))
        val text = json.encodeToString(signed)
        require(text.toByteArray().size <= MAX_BYTES) { "备份超过 64 MB，请先整理下载记录或分批导出文档" }
        validate(signed)
        return text
    }
    /** Write the canonical v2 JSON while Base64-encoding attachments in blocks. */
    internal fun write(backup: Backup, images: Map<String, File>, exports: Map<String, File>, target: File) {
        val snapshot = canonical(backup.copy(version = 2))
        validateContents(snapshot, images.keys, exports.keys)
        require(images.values.all { it.length() > 0L }) { "备份图片为空" }
        val digest = sha256()
        val bounded = BackupOutput(target.outputStream().buffered())
        val checksumOffset = DigestOutputStream(bounded, digest).use { output ->
            output.ascii("{")
            output.field("app", snapshot.app)
            output.field("version", snapshot.version)
            output.field("exportedAt", snapshot.exportedAt)
            output.field("nickname", snapshot.nickname)
            output.field("items", snapshot.items)
            output.field("docs", snapshot.docs)
            output.fileMap("images", images)
            output.field("library", snapshot.library)
            output.field("versions", snapshot.versions)
            output.field("templates", snapshot.templates)
            output.field("exportRecords", snapshot.exportRecords)
            output.fileMap("exportFiles", exports)
            output.field("revision", snapshot.revision)
            output.ascii("\"checksum\":\"")
            val offset = bounded.size
            // The digest covers an empty checksum value. Patch the reserved space after writing.
            output.on(false)
            output.ascii("0".repeat(64))
            output.on(true)
            output.ascii("\"")
            snapshot.vault?.let {
                output.ascii(",\"vault\":")
                json.encodeToStream(it, output)
            }
            output.ascii("}")
            offset
        }
        RandomAccessFile(target, "rw").use { file ->
            file.seek(checksumOffset)
            file.write(digest.digest().hex().toByteArray(Charsets.US_ASCII))
            file.fd.sync()
        }
    }

    private fun OutputStream.ascii(value: String) = write(value.toByteArray(Charsets.US_ASCII))

    private inline fun <reified T> OutputStream.field(name: String, value: T) {
        ascii("\"$name\":")
        json.encodeToStream(value, this)
        ascii(",")
    }

    private fun OutputStream.fileMap(name: String, files: Map<String, File>) {
        ascii("\"$name\":{")
        files.toSortedMap().entries.forEachIndexed { index, (key, file) ->
            if (index > 0) ascii(",")
            ascii("\"$key\":\"")
            Base64OutputStream(this, Base64.NO_WRAP or Base64.NO_CLOSE).use { encoded ->
                file.inputStream().use { it.copyTo(encoded) }
            }
            ascii("\"")
        }
        ascii("},")
    }

    private class BackupOutput(output: OutputStream) : FilterOutputStream(output) {
        var size = 0L
            private set

        private fun add(count: Int) {
            size += count
            require(size <= MAX_BYTES) { "备份超过 64 MB，请先整理下载记录或分批导出文档" }
        }

        override fun write(value: Int) {
            add(1)
            out.write(value)
        }

        override fun write(bytes: ByteArray, offset: Int, length: Int) {
            add(length)
            out.write(bytes, offset, length)
        }
    }

    fun decode(text: String): Backup {
        require(text.length <= MAX_BYTES && text.toByteArray().size <= MAX_BYTES) { "备份超过 64 MB" }
        return verify(json.decodeFromString<Backup>(text))
    }

    fun decode(input: InputStream): Backup = verify(json.decodeFromStream<Backup>(object : FilterInputStream(input) {
        private var size = 0L

        private fun count(bytes: Int) {
            size += bytes
            require(size <= MAX_BYTES) { "备份超过 64 MB" }
        }

        override fun read(): Int = super.read().also { if (it >= 0) count(1) }

        override fun read(bytes: ByteArray, offset: Int, length: Int): Int =
            super.read(bytes, offset, length).also { if (it > 0) count(it) }
    }))

    private fun verify(backup: Backup): Backup {
        require(backup.app in supportedApps && backup.version in 1..2) { "不是受支持的空叶文档备份" }
        if (backup.version == 2) {
            val digest = sha256()
            val sink = object : OutputStream() {
                override fun write(value: Int) = Unit
                override fun write(bytes: ByteArray, offset: Int, length: Int) = Unit
            }
            DigestOutputStream(sink, digest).use { json.encodeToStream(canonical(backup), it) }
            require(backup.checksum == digest.digest().hex()) { "备份校验失败，内容可能不完整或已被修改" }
        }
        validate(backup)
        return backup
    }
    fun validate(backup: Backup) {
        validateContents(backup, backup.images.keys, backup.exportFiles.keys)
        backup.images.values.forEach { require(Base64.decode(it, Base64.NO_WRAP).isNotEmpty()) { "备份图片为空" } }
    }

    private fun validateContents(backup: Backup, images: Set<String>, exports: Set<String>) {
        val items = backup.items.associateBy { it.id }
        require(items.size == backup.items.size) { "备份包含重复文档 ID" }
        require(items.keys.all(::validId) && backup.docs.keys.all(::validId) && backup.versions.keys.all(::validId) && backup.templates.keys.all(::validId)) { "备份包含非法文档 ID" }
        require(images.all(::validImage) && exports.all { exportedFile.matches(it) }) { "备份包含非法文件名" }
        require(items.values.filter { it.isDoc }.all { it.id in backup.docs } && backup.docs.keys.all { items[it]?.isDoc == true }) { "备份正文与清单不一致" }
        require(backup.versions.keys.all { items[it]?.isDoc == true }) { "备份版本没有对应文档" }
        items.values.forEach { item ->
            val seen = mutableSetOf(item.id)
            var parent = item.parentId
            while (parent != null) {
                val folder = items[parent]
                require(folder?.isFolder == true && seen.add(parent)) { "备份文件夹结构无效" }
                parent = folder.parentId
            }
        }
        val library = backup.library
        if (library != null) {
            require(library.items == backup.items) { "备份目录清单不一致" }
            require(library.templates.map { it.id }.distinct().size == library.templates.size && library.templates.all { validId(it.id) && it.id in backup.templates }) { "备份模板不完整" }
        }
        val contents = backup.docs.values + backup.templates.values + backup.versions.values.flatten().map { it.content }
        contents.forEach { doc ->
            require(doc.blocks.isNotEmpty() && doc.blocks.map { it.id }.distinct().size == doc.blocks.size) { "备份正文结构无效" }
            doc.blocks.forEach { b ->
                require(validId(b.id) && b.indent in 0..64 && b.spans.all { it.start >= 0 && it.end >= it.start && it.end <= b.text.length }) { "备份段落结构无效" }
                if (b.image.isNotEmpty()) require(validImage(b.image) && b.image in images) { "备份缺少图片 ${b.image}" }
            }
        }
        backup.exportRecords.forEach { r -> require(validId(r.id) && "${r.id}.${r.kind.ext}" in exports) { "备份下载记录不完整" } }
        backup.vault?.let { v ->
            require(v.kdf == VaultStore.KDF && v.count >= 0 && listOf(v.salt, v.iv, v.data).all { Base64.decode(it, Base64.NO_WRAP).isNotEmpty() }) { "备份里的密码空间格式损坏" }
        }
    }
}
