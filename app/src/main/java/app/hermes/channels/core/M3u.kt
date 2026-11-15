package app.hermes.channels.core

/**
 * Reading a playlist (M3U).
 *
 * Everything in this package is plain Kotlin with no Android and no Java classes, so that it can be shared
 * with other platforms (Kotlin Multiplatform) as it is. Unit-tested.
 */
object M3u {

    class Channel(val name: String, val group: String, val url: String, var logo: String, var tvgId: String,
                  /** how what the channel showed earlier is asked for; null = it is not recorded */
                  val rec: Catchup.Rule? = null) {
        /** the channel's number in its stream address (".../KEY/19241/index.m3u8" -> "19241"), "" when it has none */
        val sid: String get() = streamId(url)
    }
    class Playlist(val channels: List<Channel>, val guideUrl: String)

    private val attr = Regex("""([A-Za-z][A-Za-z0-9-]*)="([^"]*)"""")

    fun streamId(url: String): String {
        val parts = url.substringBefore('?').trimEnd('/').split('/')
        for (i in parts.indices.reversed()) {
            val p = parts[i].substringBefore('.')
            if (i >= 4 && p.length in 1..9 && p.all { it.isDigit() }) return p
        }
        return ""
    }

    fun parse(text: String): Playlist {
        val out = ArrayList<Channel>()
        var guide = ""
        var name: String? = null
        var group = ""
        var logo = ""
        var id = ""
        var head: Map<String, String> = emptyMap()
        var rec: Catchup.Rule? = null
        for (raw in text.lineSequence()) {
            val l = raw.trim().trimStart('\uFEFF')
            when {
                l.startsWith("#EXTM3U") -> {
                    val a = attr.findAll(l).associate { it.groupValues[1].lowercase() to it.groupValues[2] }
                    guide = (a["url-tvg"] ?: a["x-tvg-url"] ?: a["tvg-url"] ?: "").split(',').first().trim()
                    head = a
                }
                l.startsWith("#EXTINF") -> {
                    val a = attr.findAll(l).associate { it.groupValues[1].lowercase() to it.groupValues[2] }
                    // the name follows the first comma behind the attributes (a name may contain commas itself)
                    val from = attr.findAll(l).lastOrNull()?.range?.last ?: 0
                    val comma = l.indexOf(',', from)
                    name = (if (comma >= 0) l.substring(comma + 1) else a["tvg-name"] ?: "").trim()
                    group = a["group-title"] ?: ""
                    logo = a["tvg-logo"] ?: ""
                    id = a["tvg-id"] ?: ""
                    rec = Catchup.rule(a, head)
                }
                l.startsWith("#EXTGRP:") -> if (group.isEmpty()) group = l.substring(8).trim()
                l.isEmpty() || l.startsWith("#") -> {}
                else -> {
                    val n = name
                    if (!n.isNullOrEmpty()) out.add(Channel(n, group, l, logo, id, rec))
                    name = null; group = ""; logo = ""; id = ""; rec = null
                }
            }
        }
        return Playlist(out, guide)
    }

    /** letters and digits only, lower case: for comparing names */
    fun norm(s: String): String = s.lowercase().filter { it.isLetterOrDigit() }

    /** logos, guide ids and the guide's address that [own] lacks, taken from [other]: by channel number, else by name */
    fun merge(own: Playlist, other: Playlist?): Playlist {
        if (other == null) return own
        val bySid = HashMap<String, Channel>()
        val byName = HashMap<String, Channel>()
        for (c in other.channels) {
            if (c.sid.isNotEmpty()) bySid.putIfAbsent(c.sid, c)
            byName.putIfAbsent(norm(c.name), c)
        }
        for (c in own.channels) {
            if (c.logo.isNotEmpty() && c.tvgId.isNotEmpty()) continue
            val o = (if (c.sid.isNotEmpty()) bySid[c.sid] else null) ?: byName[norm(c.name)] ?: continue
            if (c.logo.isEmpty()) c.logo = o.logo
            if (c.tvgId.isEmpty()) c.tvgId = o.tvgId
        }
        return Playlist(own.channels, own.guideUrl.ifEmpty { other.guideUrl })
    }

    /** the groups in the order the playlist names them */
    fun groups(channels: List<Channel>): List<String> = channels.map { it.group }.filter { it.isNotEmpty() }.distinct()
}
