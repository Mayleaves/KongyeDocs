package com.kongye.docs.data

/** A short extract of user-facing changes; build logs and checksums stay on GitHub. */
internal object ReleaseNotes {
    fun summary(body: String): String {
        val bullets=mutableListOf<String>()
        val prose=mutableListOf<String>()
        var code=false
        var technicalSection=false
        val technical=Regex("""^(?:验证|测试|追加验证|此前.*验证|另用|另直接|构建|技术(?:信息|说明|细节)?|包名|版本号|签名|安装说明|校验|SHA[- ]?256|APK(?:\s+SHA|\s*校验)|versionCode|Release\s*构建|Debug\s*/).*""",RegexOption.IGNORE_CASE)
        fun plain(text: String)=text
            .replace(Regex("""!?\[([^]]*)]\([^)]*\)"""),"$1")
            .replace(Regex("<[^>]*>"),"")
            .replace(Regex("[*_`~]"),"")
            .replace(Regex("\\s+")," ").trim()
        body.lineSequence().forEach { raw ->
            val line=raw.trim()
            if(line.startsWith("```") || line.startsWith("~~~")) { code=!code; return@forEach }
            if(code || line.isBlank() || line.startsWith("<!--") || line.startsWith('|')) return@forEach
            if(line.startsWith('#')) {
                technicalSection=technical.matches(plain(line.trimStart('#',' ')))
                return@forEach
            }
            if(technicalSection) return@forEach
            val bullet=Regex("""^(?:[-*+]\s+|\d+[.)、]\s*)(.+)$""").matchEntire(line)
            val text=plain(bullet?.groupValues?.get(1) ?: line)
            if(text.isBlank() || technical.matches(text) || Regex("[0-9a-fA-F]{32,}").containsMatchIn(text) || text.startsWith("http")) return@forEach
            if(bullet!=null) bullets+=text else prose+=text
        }
        fun shorten(text: String): String {
            val count=text.codePointCount(0,text.length)
            return if(count<=64) text else text.substring(0,text.offsetByCodePoints(0,63)).trimEnd('，','、','；','。')+"…"
        }
        val highlights=(if(bullets.isNotEmpty()) bullets else prose).distinct().take(3)
        return if(highlights.isEmpty()) "可在 GitHub 查看本次更新的完整说明。"
        else highlights.joinToString("\n") { "• "+shorten(it) }
    }
}
