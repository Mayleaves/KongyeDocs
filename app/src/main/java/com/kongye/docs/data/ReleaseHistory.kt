package com.kongye.docs.data

import android.content.Context

internal data class ReleaseHistoryEntry(val version: String,val date: String,val notes: String)

/** The full changelog is bundled by Gradle so old release details work offline. */
internal object ReleaseHistory {
    fun read(context: Context): List<ReleaseHistoryEntry> = context.assets.open("release-history.md").bufferedReader().use { parse(it.readText()) }
    internal fun parse(markdown: String): List<ReleaseHistoryEntry> {
        val header=Regex("(?m)^##\\s+v?([0-9]+(?:\\.[0-9]+){0,3})([^\\r\\n]*)$")
        val matches=header.findAll(markdown).toList()
        return matches.mapIndexed { index,m ->
            ReleaseHistoryEntry(m.groupValues[1],Regex("[0-9]{4}-[0-9]{2}-[0-9]{2}").find(m.groupValues[2])?.value.orEmpty(),
                markdown.substring(m.range.last+1,matches.getOrNull(index+1)?.range?.first ?: markdown.length).trim())
        }.distinctBy { it.version }.sortedWith { a,b -> AppUpdates.compare(b.version,a.version) }
    }
}
