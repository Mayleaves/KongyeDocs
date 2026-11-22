package com.kongye.docs.ui.vault

import com.kongye.docs.data.VaultEntry
import java.text.Collator
import java.util.Locale

internal enum class VaultSort(val label: String,val detail: String) {
    ALPHABET("字母排序","A–Z · #"), CREATED("按增加时间","从新到旧"), UPDATED("按修改时间","从新到旧");

    fun direction(descending: Boolean)=if(this==ALPHABET) {
        if(descending) "# · Z–A" else "A–Z · #"
    } else if(descending) "从新到旧" else "从旧到新"

    fun order(entries: List<VaultEntry>,descending: Boolean=this!=ALPHABET): List<VaultEntry> {
        val collator=Collator.getInstance(Locale.CHINA)
        val alphabet=Comparator<VaultEntry> { a,b ->
            val bucket=VaultAlphabet.letters.indexOf(VaultAlphabet.key(a.name)).compareTo(VaultAlphabet.letters.indexOf(VaultAlphabet.key(b.name)))
            if(bucket!=0) bucket else collator.compare(a.name,b.name).takeIf { it!=0 } ?: a.id.compareTo(b.id)
        }
        val comparator=when(this) {
            ALPHABET -> if(descending) alphabet.reversed() else alphabet
            CREATED -> (if(descending) compareByDescending<VaultEntry> { it.createdAt } else compareBy { it.createdAt }).then(alphabet)
            UPDATED -> (if(descending) compareByDescending<VaultEntry> { it.updatedAt } else compareBy { it.updatedAt }).then(alphabet)
        }
        return entries.sortedWith(comparator)
    }

    companion object { fun read(value: String)=entries.firstOrNull { it.name==value } ?: ALPHABET }
}
