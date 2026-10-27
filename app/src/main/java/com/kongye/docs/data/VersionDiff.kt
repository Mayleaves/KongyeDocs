package com.kongye.docs.data

enum class DiffKind { SAME, ADDED, REMOVED }
data class DiffPart(val text: String, val kind: DiffKind)

/** Bounded Myers diff on Unicode code points. A large rewrite falls back to one replacement. */
object VersionDiff {
    fun preview(currentTitle: String,current: DocContent,selected: DocVersion): List<DiffPart> = between(currentTitle+"\n\n"+current.plainText(),selected.title+"\n\n"+selected.content.plainText())

    /** Align complete lines first. Isolated matching characters inside a rewrite are changes. */
    fun between(before: String, after: String): List<DiffPart> {
        if (before == after) return listOf(DiffPart(after, DiffKind.SAME))
        fun lines(text: String) = Regex("[^\\n]*\\n|[^\\n]+$").findAll(text).map { it.value }.toList()
        val a = lines(before); val b = lines(after)
        if (a.size + b.size > 50_000) return coherent(before, after)
        val tokens = (a + b).distinct()
        val ids = tokens.withIndex().associate { it.value to it.index + 0x10000 }
        fun encoded(lines: List<String>) = buildString { lines.forEach { appendCodePoint(ids.getValue(it)) } }
        val result = mutableListOf<DiffPart>()
        val removed = StringBuilder(); val added = StringBuilder()
        fun flush() {
            if (removed.isNotEmpty() || added.isNotEmpty()) result += coherent(removed.toString(), added.toString())
            removed.clear(); added.clear()
        }
        characters(encoded(a), encoded(b)).forEach { part ->
            val text = buildString { part.text.codePoints().forEach { append(tokens[it - 0x10000]) } }
            when (part.kind) {
                DiffKind.SAME -> { flush(); result += DiffPart(text, DiffKind.SAME) }
                DiffKind.REMOVED -> removed.append(text)
                DiffKind.ADDED -> added.append(text)
            }
        }
        flush()
        return merge(result)
    }

    private fun coherent(before: String, after: String): List<DiffPart> {
        val out = mutableListOf<DiffPart>()
        val removed = StringBuilder(); val added = StringBuilder()
        fun flush() {
            if (removed.isNotEmpty()) out += DiffPart(removed.toString(), DiffKind.REMOVED)
            if (added.isNotEmpty()) out += DiffPart(added.toString(), DiffKind.ADDED)
            removed.clear(); added.clear()
        }
        characters(before, after).forEach { part ->
            if (part.kind == DiffKind.SAME && part.text.codePointCount(0,part.text.length) >= 2 && part.text.any { !it.isWhitespace() }) {
                flush(); out += part
            } else {
                if (part.kind != DiffKind.ADDED) removed.append(part.text)
                if (part.kind != DiffKind.REMOVED) added.append(part.text)
            }
        }
        flush()
        return out
    }

    private fun merge(parts: List<DiffPart>): List<DiffPart> {
        val out = mutableListOf<DiffPart>()
        parts.filter { it.text.isNotEmpty() }.forEach {
            if (out.lastOrNull()?.kind == it.kind) out[out.lastIndex] = DiffPart(out.last().text + it.text,it.kind) else out += it
        }
        return out
    }

    private fun characters(before: String, after: String): List<DiffPart> {
        if (before == after) return listOf(DiffPart(after, DiffKind.SAME))
        val a=before.codePoints().toArray(); val b=after.codePoints().toArray()
        var prefix=0
        while(prefix<a.size && prefix<b.size && a[prefix]==b[prefix]) prefix++
        var suffix=0
        while(suffix<a.size-prefix && suffix<b.size-prefix && a[a.lastIndex-suffix]==b[b.lastIndex-suffix]) suffix++
        val left=a.copyOfRange(prefix,a.size-suffix); val right=b.copyOfRange(prefix,b.size-suffix)
        val result=mutableListOf<DiffPart>()
        fun append(text: String, kind: DiffKind) {
            if(text.isEmpty()) return
            if(result.lastOrNull()?.kind==kind) result[result.lastIndex]=DiffPart(result.last().text+text,kind)
            else result+=DiffPart(text,kind)
        }
        fun text(points: IntArray, start: Int=0, end: Int=points.size)=String(points,start,end-start)
        append(text(a,0,prefix),DiffKind.SAME)
        val limit=minOf(left.size+right.size,512)
        val offset=limit+1
        val v=IntArray(2*limit+3)
        val trace=mutableListOf<IntArray>()
        var distance=-1
        outer@ for(d in 0..limit) {
            for(k in -d..d step 2) {
                val index=offset+k
                var x=if(k==-d || k!=d && v[index-1]<v[index+1]) v[index+1] else v[index-1]+1
                var y=x-k
                while(x<left.size && y<right.size && left[x]==right[y]) { x++; y++ }
                v[index]=x
                if(x>=left.size && y>=right.size) { distance=d; break }
            }
            trace+=v.copyOf()
            if(distance>=0) break@outer
        }
        if(distance<0) {
            append(text(left),DiffKind.REMOVED); append(text(right),DiffKind.ADDED)
        } else {
            val reversed=mutableListOf<Pair<Int,DiffKind>>()
            var x=left.size; var y=right.size
            for(d in distance downTo 1) {
                val previous=trace[d-1]
                val k=x-y
                val previousK=if(k==-d || k!=d && previous[offset+k-1]<previous[offset+k+1]) k+1 else k-1
                val px=previous[offset+previousK]; val py=px-previousK
                while(x>px && y>py) { reversed+=left[--x] to DiffKind.SAME; y-- }
                if(x==px) reversed+=right[--y] to DiffKind.ADDED else reversed+=left[--x] to DiffKind.REMOVED
            }
            while(x>0 && y>0) { reversed+=left[--x] to DiffKind.SAME; y-- }
            while(x>0) reversed+=left[--x] to DiffKind.REMOVED
            while(y>0) reversed+=right[--y] to DiffKind.ADDED
            // Build runs instead of repeatedly concatenating a large unchanged string.
            var kind: DiffKind?=null
            val run=StringBuilder()
            for((point,next) in reversed.asReversed()) {
                if(kind!=next && run.isNotEmpty()) { append(run.toString(),kind!!); run.clear() }
                kind=next; run.appendCodePoint(point)
            }
            if(run.isNotEmpty()) append(run.toString(),kind!!)
        }
        append(text(a,a.size-suffix),DiffKind.SAME)
        return result
    }
}
