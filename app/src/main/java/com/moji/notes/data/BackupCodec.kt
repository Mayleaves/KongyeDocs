package com.moji.notes.data

import android.util.Base64
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import java.security.MessageDigest

/** The v1 reader remains compatible with backups produced by 2.1, which omitted default fields. */
object BackupCodec {
    const val MAX_BYTES = 64 * 1024 * 1024
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
    fun sha(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
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
    fun decode(text: String): Backup {
        require(text.length <= MAX_BYTES && text.toByteArray().size <= MAX_BYTES) { "备份超过 64 MB" }
        val fields = json.parseToJsonElement(text).jsonObject
        require("items" in fields) { "备份缺少文档清单" }
        val backup = json.decodeFromString<Backup>(text)
        require(backup.app == "moji-notes" && backup.version in 1..2) { "不是受支持的空叶文档备份" }
        if (backup.version == 2) {
            require(backup.checksum.isNotEmpty() && backup.checksum == sha(json.encodeToString(canonical(backup)).toByteArray())) { "备份校验失败，内容可能不完整或已被修改" }
        }
        validate(backup)
        return backup
    }
    fun validate(backup: Backup) {
        val items = backup.items.associateBy { it.id }
        require(items.size == backup.items.size) { "备份包含重复文档 ID" }
        require(items.keys.all(::validId) && backup.docs.keys.all(::validId) && backup.versions.keys.all(::validId) && backup.templates.keys.all(::validId)) { "备份包含非法文档 ID" }
        require(backup.images.keys.all(::validImage) && backup.exportFiles.keys.all { exportedFile.matches(it) }) { "备份包含非法文件名" }
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
                if (b.image.isNotEmpty()) require(validImage(b.image) && b.image in backup.images) { "备份缺少图片 ${b.image}" }
            }
        }
        backup.images.values.forEach { require(Base64.decode(it, Base64.NO_WRAP).isNotEmpty()) { "备份图片为空" } }
        backup.exportRecords.forEach { r -> require(validId(r.id) && "${r.id}.${r.kind.ext}" in backup.exportFiles) { "备份下载记录不完整" } }
    }
}
