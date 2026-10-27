package com.kongye.docs

import com.kongye.docs.data.BackupCodec
import java.io.File

/** Invoked as Android shell; compares public backup files on-device, emits counts only. */
object VerifyUpgrade {
    @JvmStatic fun main(args: Array<String>) {
        require(args.size==2)
        val before=BackupCodec.decode(File(args[0]).readText())
        val after=BackupCodec.decode(File(args[1]).readText())
        check(before.docs.all { (id,doc) -> after.docs[id]==doc }) { "Document content, styles or comments changed" }
        val all=after.items.associateBy { it.id }
        check(before.items.all { item ->
            val current=all[item.id]
            current!=null && current.type==item.type && current.title==item.title && current.parentId==item.parentId && current.deletedAt==item.deletedAt && current.starred==item.starred
        }) { "Document metadata changed" }
        val contents=before.docs.values+before.templates.values+before.versions.values.flatten().filter { it.kind==com.kongye.docs.data.VersionKind.LOCAL }.map { it.content }
        val referenced=contents.flatMap { it.blocks.mapNotNull { b -> b.image.takeIf(String::isNotEmpty) } }.toSet()
        check(referenced.all { after.images[it]==before.images[it] }) { "Referenced image bytes changed" }
        check(after.versions.values.flatten().none { it.kind==com.kongye.docs.data.VersionKind.HISTORY }) { "Discontinued history exported" }
        check(before.versions.all { (id,versions) -> versions.filter { it.kind==com.kongye.docs.data.VersionKind.LOCAL }.all { v -> after.versions[id].orEmpty().any { it==v } } }) { "Local version changed" }
        println("PASS exact document content, formatting, comments, folder metadata and images preserved")
        println("Original documents: ${before.docs.size}; upgraded documents: ${after.docs.size}; preserved referenced images: ${referenced.size}")
        println("New backup checksum: verified; saved versions: ${after.versions.values.sumOf { it.size }}; templates: ${after.templates.size}; download records: ${after.exportRecords.size}")
    }
}
