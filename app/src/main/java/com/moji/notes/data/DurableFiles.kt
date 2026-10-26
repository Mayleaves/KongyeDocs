package com.moji.notes.data

import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.nio.channels.FileChannel
import java.nio.file.Files
import java.nio.file.StandardCopyOption.ATOMIC_MOVE
import java.nio.file.StandardCopyOption.REPLACE_EXISTING
import java.nio.file.StandardOpenOption.READ

/** Same-directory atomic replacement; never delete the good file to make a rename succeed. */
object DurableFiles {
    private val json = Json { encodeDefaults = true }
    @Serializable private data class Entry(val path: String, val existed: Boolean)
    @Serializable private data class Journal(val entries: List<Entry>)

    private fun syncDirectory(dir: File) {
        // Some providers do not support opening a directory. File contents are always synced.
        runCatching { FileChannel.open(dir.toPath(), READ).use { it.force(true) } }
    }

    fun write(file: File, text: String) = write(file, text.toByteArray(Charsets.UTF_8))

    fun write(file: File, bytes: ByteArray, keepPrevious: Boolean = true) {
        val parent = requireNotNull(file.parentFile)
        check(parent.isDirectory || parent.mkdirs()) { "无法创建数据目录" }
        val next = File(parent, file.name + ".new")
        FileOutputStream(next).use { it.write(bytes); it.fd.sync() }
        if (keepPrevious && file.isFile) {
            val previous = File(parent, file.name + ".bak.new")
            file.inputStream().use { input -> FileOutputStream(previous).use { out -> input.copyTo(out); out.fd.sync() } }
            Files.move(previous.toPath(), File(parent, file.name + ".bak").toPath(), ATOMIC_MOVE, REPLACE_EXISTING)
        }
        Files.move(next.toPath(), file.toPath(), ATOMIC_MOVE, REPLACE_EXISTING)
        syncDirectory(parent)
    }

    fun <T> read(file: File, decode: (String) -> T): T {
        val result = runCatching { decode(file.readText()) }
        if (result.isSuccess) return result.getOrThrow()
        val previous = File(file.parentFile, file.name + ".bak")
        val recovered = runCatching { val text = previous.readText(); text to decode(text) }
        if (recovered.isSuccess) {
            val (text, value) = recovered.getOrThrow()
            if (file.exists()) file.copyTo(File(file.parentFile, file.name + ".corrupt-" + System.currentTimeMillis()), overwrite = false)
            write(file, text.toByteArray(), keepPrevious = false)
            return value
        }
        throw IOException("无法读取 ${file.name}，原文件已保留，请从备份恢复", result.exceptionOrNull())
    }

    private fun target(root: File, path: String): File {
        require(path.isNotBlank() && !path.startsWith('/') && !path.contains('\\')) { "非法数据路径" }
        val result = File(root, path).canonicalFile
        require(result.path.startsWith(root.canonicalPath + File.separator)) { "数据路径越界" }
        return result
    }

    /** Caller must serialize all access. An interrupted operation is rolled back before loading data. */
    fun transaction(root: File, changes: Map<String, ByteArray>) {
        recover(root)
        val txn = File(root, ".transaction")
        check(txn.mkdirs()) { "无法创建恢复事务" }
        var prepared = false
        try {
            val entries = changes.map { (path, bytes) ->
                val current = target(root, path)
                if (current.exists()) {
                    require(current.isFile) { "数据目标不是文件" }
                    write(target(File(txn, "old"), path), current.readBytes(), false)
                }
                write(target(File(txn, "next"), path), bytes, false)
                Entry(path, current.isFile)
            }
            write(File(txn, "journal.json"), json.encodeToString(Journal(entries)))
            prepared = true
            entries.forEach { entry -> write(target(root, entry.path), target(File(txn, "next"), entry.path).readBytes()) }
            write(File(txn, "committed"), "ok")
        } catch (error: Exception) {
            if (prepared) {
                try { recover(root) } catch (rollback: Exception) { error.addSuppressed(rollback) }
            } else txn.deleteRecursively()
            throw error
        }
        // A leftover committed journal can safely be removed on the next startup.
        txn.deleteRecursively()
    }

    fun recover(root: File) {
        val txn = File(root, ".transaction")
        if (!txn.exists()) return
        val journal = File(txn, "journal.json")
        if (!File(txn, "committed").exists() && journal.exists()) {
            val entries = json.decodeFromString<Journal>(journal.readText()).entries
            entries.forEach { entry ->
                val file = target(root, entry.path)
                if (entry.existed) write(file, target(File(txn, "old"), entry.path).readBytes(), false)
                else {
                    check(!file.exists() || file.delete()) { "无法回滚恢复操作" }
                    File(file.parentFile, file.name + ".bak").delete()
                }
            }
        }
        check(txn.deleteRecursively()) { "无法清理恢复事务，请重启后重试" }
        syncDirectory(root)
    }
}
