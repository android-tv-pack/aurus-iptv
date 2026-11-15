package app.hermes.channels.core

/** Reading a programme guide (XMLTV) of any size, piece by piece, and asking it what is on. Plain Kotlin. */
object Guide {

    class Prog(val start: Long, val stop: Long, val title: String, /** what it is about, "" when the guide does not say */ val desc: String = "")
    /** a channel of the guide: its id, its logo and the names it is known by */
    class Chan(val id: String, val icon: String, val names: List<String>)

    /** XMLTV time "20261107235500 +0300" as milliseconds, 0 when it is not one */
    fun xmltvTime(s: String): Long {
        val t = s.trim()
        if (t.length < 14) return 0
        if (!t.substring(0, 14).all { it in '0'..'9' }) return 0
        fun n(a: Int, b: Int): Int = t.substring(a, b).toInt()
        val y = n(0, 4); val mo = n(4, 6); val d = n(6, 8); val h = n(8, 10); val mi = n(10, 12); val se = n(12, 14)
        var off = 0
        val z = t.substring(14).trim()
        if (z.length >= 5 && (z[0] == '+' || z[0] == '-')) {
            val hh = z.substring(1, 3).toIntOrNull() ?: 0
            val mm = z.substring(3, 5).toIntOrNull() ?: 0
            off = (hh * 60 + mm) * (if (z[0] == '-') -1 else 1)
        }
        // days since 1970-01-01 (no calendar object: this runs for every programme of the guide)
        val yy = if (mo <= 2) y - 1 else y
        val era = (if (yy >= 0) yy else yy - 399) / 400
        val yoe = yy - era * 400
        val doy = (153 * (mo + (if (mo > 2) -3 else 9)) + 2) / 5 + d - 1
        val doe = yoe * 365 + yoe / 4 - yoe / 100 + doy
        val days = era * 146097L + doe - 719468L
        return ((days * 86400L + h * 3600L + mi * 60L + se) - off * 60L) * 1000L
    }

    fun unescape(s: String): String =
        if (s.indexOf('&') < 0) s else s.replace("&lt;", "<").replace("&gt;", ">").replace("&quot;", "\"").replace("&apos;", "'").replace("&#39;", "'").replace("&amp;", "&")

    private val titleTag = Regex("""<title[^>]*>([^<]*)</title>""")
    private val descTag = Regex("""<desc[^>]*>([^<]*)</desc>""")
    private val displayName = Regex("""<display-name[^>]*>([^<]*)</display-name>""")
    private val iconTag = Regex("""<icon[^>]*\ssrc="([^"]*)"""")

    /** value of attribute [name] in an opening tag */
    fun attrOf(tag: String, name: String): String {
        val i = tag.indexOf("$name=\"")
        if (i < 0) return ""
        val from = i + name.length + 2
        val to = tag.indexOf('"', from)
        return if (to < 0) "" else tag.substring(from, to)
    }

    /** a "<programme ...>...</programme>" block as (channel id, programme), or null */
    fun progOf(block: String): Pair<String, Prog>? {
        val end = block.indexOf('>')
        if (end < 0) return null
        val head = block.substring(0, end)
        val ch = attrOf(head, "channel")
        val start = xmltvTime(attrOf(head, "start"))
        val stop = xmltvTime(attrOf(head, "stop"))
        val title = titleTag.find(block)?.groupValues?.get(1)?.trim().orEmpty()
        if (ch.isEmpty() || start == 0L || stop <= start || title.isEmpty()) return null
        val desc = descTag.find(block)?.groupValues?.get(1)?.trim().orEmpty()
        return ch to Prog(start, stop, unescape(title), unescape(desc))
    }

    /** a "<channel ...>...</channel>" block, or null */
    fun chanOf(block: String): Chan? {
        val end = block.indexOf('>')
        if (end < 0) return null
        val id = attrOf(block.substring(0, end), "id")
        if (id.isEmpty()) return null
        val names = displayName.findAll(block).map { unescape(it.groupValues[1].trim()) }.filter { it.isNotEmpty() }.toList()
        return Chan(id, iconTag.find(block)?.groupValues?.get(1).orEmpty(), names)
    }

    /** what is on at [now] and what follows ([list] in time order) */
    fun nowNext(list: List<Prog>?, now: Long): Pair<Prog?, Prog?> {
        if (list == null) return null to null
        val cur = list.firstOrNull { it.start <= now && now < it.stop }
        val next = list.filter { it.start >= (cur?.stop ?: now) }.minByOrNull { it.start }
        return cur to next
    }

    /** how far [p] has run at [now], 0..1 */
    fun progress(p: Prog, now: Long): Float = ((now - p.start).toFloat() / (p.stop - p.start)).coerceIn(0f, 1f)

    /**
     * Walks a guide without holding it. Give it the file piece by piece with [feed]: every channel block goes to
     * [onChannel], every programme block to [onProgramme].
     */
    class Scanner(private val onChannel: (String) -> Unit, private val onProgramme: (String) -> Unit) {
        private val cap = ByteArray(1 shl 15)
        private var capLen = 0
        private var state = 0           // 0 look for '<', 1 tag name, 3 block to its end
        private var endPat = ByteArray(0)
        private var endAt = 0
        private var isProg = false
        private val nameBuf = ByteArray(12)
        private var nameLen = 0
        private val pEnd = "</programme>".encodeToByteArray()
        private val cEnd = "</channel>".encodeToByteArray()
        private val lt = '<'.code.toByte()

        fun feed(buf: ByteArray, n: Int) {
            var i = 0
            while (i < n) {
                val b = buf[i]
                when (state) {
                    0 -> {
                        while (i < n && buf[i] != lt) i++           // most of a guide is skipped here
                        if (i < n) { state = 1; nameLen = 0 }
                    }
                    1 -> {
                        val c = b.toInt()
                        if (c == ' '.code || c == '>'.code || c == '\t'.code || c == '\n'.code || c == '\r'.code) {
                            val name = nameBuf.decodeToString(0, nameLen)
                            if (name == "programme" || name == "channel") {
                                isProg = name == "programme"
                                cap[0] = lt
                                nameBuf.copyInto(cap, 1, 0, nameLen)
                                capLen = nameLen + 1
                                cap[capLen++] = b
                                endPat = if (isProg) pEnd else cEnd
                                endAt = 0
                                state = 3
                            } else state = 0
                        } else if (b == lt) { nameLen = 0 }
                        else if (nameLen < nameBuf.size) nameBuf[nameLen++] = b
                        else state = 0
                    }
                    else -> {
                        if (capLen < cap.size) cap[capLen++] = b else { state = 0; i++; continue }
                        endAt = if (b == endPat[endAt]) endAt + 1 else if (b == endPat[0]) 1 else 0
                        if (endAt == endPat.size) {
                            val s = cap.decodeToString(0, capLen)
                            if (isProg) onProgramme(s) else onChannel(s)
                            state = 0
                        }
                    }
                }
                i++
            }
        }
    }
}
