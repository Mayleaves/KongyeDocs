package com.kongye.docs.data

import android.content.Context
import android.util.AtomicFile
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File
import java.io.IOException

@Serializable
internal data class ReleaseHistoryEntry(val version: String,val date: String,val notes: String)

internal object ReleaseHistory {
    @Synchronized
    fun read(context: Context): List<ReleaseHistoryEntry> {
        val published = try {
            Json.decodeFromString<List<ReleaseHistoryEntry>>(cache(context).readFully().decodeToString())
        } catch (_: IOException) {
            emptyList()
        } catch (_: IllegalArgumentException) {
            emptyList()
        }
        return merge(readBundled(context), published)
    }

    @Synchronized
    fun save(context: Context, published: List<ReleaseHistoryEntry>): List<ReleaseHistoryEntry> {
        val history = merge(readBundled(context), published)
        val data = Json.encodeToString(published).encodeToByteArray()
        val cache = cache(context)
        val output = cache.startWrite()
        try {
            output.write(data)
            cache.finishWrite(output)
        } catch (error: Exception) {
            cache.failWrite(output)
            throw error
        }
        return history
    }

    internal fun merge(
        bundled: List<ReleaseHistoryEntry>,
        published: List<ReleaseHistoryEntry>,
    ): List<ReleaseHistoryEntry> {
        fun key(entry: ReleaseHistoryEntry) = AppUpdates.version(entry.version).dropLastWhile { it == 0L }
        val local = bundled.associateBy(::key)
        val remote = published.distinctBy(::key).associateBy(::key)
        return (local + remote.mapValues { (version, entry) ->
            val fallback = local[version]
            entry.copy(
                date = entry.date.ifBlank { fallback?.date.orEmpty() },
                notes = entry.notes.ifBlank { fallback?.notes.orEmpty() },
            )
        }).values.sortedWith { a, b -> AppUpdates.compare(b.version, a.version) }
    }

    private fun readBundled(context: Context) =
        context.assets.open("release-history.md").bufferedReader().use { parse(it.readText()) }

    private fun cache(context: Context) = AtomicFile(File(context.filesDir, "github-release-history.json"))

    internal fun parse(markdown: String): List<ReleaseHistoryEntry> {
        val header=Regex("(?m)^##\\s+v?([0-9]+(?:\\.[0-9]+){0,3})([^\\r\\n]*)$")
        val matches=header.findAll(markdown).toList()
        return matches.mapIndexed { index,m ->
            ReleaseHistoryEntry(m.groupValues[1],Regex("[0-9]{4}-[0-9]{2}-[0-9]{2}").find(m.groupValues[2])?.value.orEmpty(),
                markdown.substring(m.range.last+1,matches.getOrNull(index+1)?.range?.first ?: markdown.length).trim())
        }.distinctBy { it.version }.sortedWith { a,b -> AppUpdates.compare(b.version,a.version) }
    }
}
