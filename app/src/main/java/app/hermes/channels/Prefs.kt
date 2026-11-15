package app.hermes.channels

import android.content.Context

/** What the app remembers. The playlist link holds the subscription key: it lives only here, in the app's private storage. */
class Prefs(ctx: Context) {
    private val p = ctx.applicationContext.getSharedPreferences("s", Context.MODE_PRIVATE)

    private fun str(k: String) = p.getString(k, "") ?: ""
    private fun put(k: String, v: String) = p.edit().putString(k, v).apply()
    private fun lines(k: String) = str(k).split('\n').filter { it.isNotEmpty() }

    // ---- the playlists. Each has a number; what belongs to one playlist (its links, favourites, recent channels ...) is kept
    //      under that number, the first one's under the plain names (as it was before there could be several).
    /** the playlist that is watched */
    var source: Int get() = p.getInt("source", 0); set(v) { p.edit().putInt("source", v).apply() }
    private val pre: String get() = source.let { if (it == 0) "" else "s$it." }
    /** number and name of every playlist, in the order they were added */
    val sources: List<Pair<Int, String>> get() =
        lines("sources").mapNotNull { l -> l.substringBefore('\t').toIntOrNull()?.let { it to l.substringAfter('\t', "Playlist") } }
            .ifEmpty { if (str("playlist").isNotEmpty()) listOf(0 to "Playlist 1") else emptyList() }
    private fun putSources(list: List<Pair<Int, String>>) = put("sources", list.joinToString("\n") { "${it.first}\t${it.second.replace('\t', ' ').replace('\n', ' ')}" })
    /** how many channels playlist [id] had when it was read last (0 = not read yet) */
    fun channelCount(id: Int): Int = p.getInt((if (id == 0) "" else "s$id.") + "count", 0)
    fun setChannelCount(n: Int) { p.edit().putInt(pre + "count", n).apply() }
    val sourceName: String get() = sources.firstOrNull { it.first == source }?.second ?: ""
    /** a new, empty playlist; returns its number */
    fun addSource(name: String = ""): Int {
        val cur = sources
        val id = (cur.maxOfOrNull { it.first } ?: -1) + 1
        putSources(cur + (id to name.ifBlank { "Playlist ${cur.size + 1}" }))
        return id
    }
    fun renameSource(id: Int, name: String) { if (name.isNotBlank()) putSources(sources.map { if (it.first == id) id to name.trim() else it }) }
    /** forgets playlist [id] with everything that belongs to it; another one (if there is one) becomes the one watched */
    fun removeSource(id: Int) {
        val rest = sources.filter { it.first != id }
        val e = p.edit()
        if (id == 0) for (k in arrayOf("playlist", "extra", "guide", "favs", "recents", "last", "scope", "homeRows", "prev", "pictures", "mended", "count", "epg")) e.remove(k)
        else for (k in p.all.keys) if (k.startsWith("s$id.")) e.remove(k)
        e.apply()
        putSources(rest)
        if (source == id) source = rest.firstOrNull()?.first ?: 0
    }

    var playlist: String get() = str(pre + "playlist"); set(v) = put(pre + "playlist", v.trim())
    /** an optional second playlist that carries logos and guide ids */
    var extra: String get() = str(pre + "extra"); set(v) = put(pre + "extra", v.trim())
    /** the programme guide (XMLTV); empty = the one the playlist names */
    var guide: String get() = str(pre + "guide"); set(v) = put(pre + "guide", v.trim())

    // ---- the EPGs (programme guides): each a number, a name and a link; a playlist uses one of them, or the one it names itself
    val epgs: List<Triple<Int, String, String>> get() = lines("epgs").mapNotNull { l ->
        val f = l.split('\t')
        val id = f.getOrNull(0)?.toIntOrNull()
        if (id == null || f.size < 3) null else Triple(id, f[1], f[2])
    }
    private fun putEpgs(list: List<Triple<Int, String, String>>) = put("epgs", list.joinToString("\n") { "${it.first}\t${it.second.replace('\t', ' ').replace('\n', ' ')}\t${it.third.trim()}" })
    /** a new EPG; returns its number (numbers start at 1: 0 stands for "the one the playlist names") */
    fun addEpg(link: String, name: String = ""): Int {
        val cur = epgs
        val id = (cur.maxOfOrNull { it.first } ?: 0) + 1
        putEpgs(cur + Triple(id, name.ifBlank { "EPG ${cur.size + 1}" }, link))
        return id
    }
    fun renameEpg(id: Int, name: String) { if (name.isNotBlank()) putEpgs(epgs.map { if (it.first == id) Triple(id, name.trim(), it.third) else it }) }
    fun setEpgLink(id: Int, link: String) { if (link.isNotBlank()) putEpgs(epgs.map { if (it.first == id) Triple(id, it.second, link) else it }) }
    fun removeEpg(id: Int) = putEpgs(epgs.filter { it.first != id })
    private fun preOf(source: Int) = if (source == 0) "" else "s$source."
    /** which EPG playlist [source] uses; 0 = the one the playlist itself names (also when the one chosen was removed) */
    fun epgOf(source: Int): Int = p.getInt(preOf(source) + "epg", 0).let { id -> if (epgs.any { it.first == id }) id else 0 }
    fun setEpgOf(source: Int, id: Int) { p.edit().putInt(preOf(source) + "epg", id).apply() }
    /** the link of the EPG the watched playlist uses; "" = the one the playlist names */
    val guideLink: String get() = epgOf(source).let { id -> epgs.firstOrNull { it.first == id }?.third } ?: guide
    /** (a link given to one playlist directly, as earlier versions kept it, becomes an EPG of its own) */
    fun migrateEpg() { if (guide.isNotEmpty() && epgOf(source) == 0) { setEpgOf(source, addEpg(guide)); guide = "" } }

    var favs: Set<String> get() = lines(pre + "favs").toSet(); set(v) = put(pre + "favs", v.joinToString("\n"))
    var recents: List<String> get() = lines(pre + "recents"); set(v) = put(pre + "recents", v.joinToString("\n"))
    /** the channel that played last (see Library.keyOf) and the list it was chosen from */
    var last: String get() = str(pre + "last"); set(v) = put(pre + "last", v)
    var scope: String get() = str(pre + "scope"); set(v) = put(pre + "scope", v)
    /** what the TV's home screen is given: key, name, logo per line (so it can be given again without the playlist) */
    var homeRows: List<String> get() = lines(pre + "homeRows"); set(v) = put(pre + "homeRows", v.joinToString("\n"))
    private fun flag(k: String, d: Boolean) = p.getBoolean(k, d)
    private fun put(k: String, v: Boolean) = p.edit().putBoolean(k, v).apply()

    /** picture and sound are kept in step by the device's own video hardware ("tunneled" playback); off unless chosen:
     *  on the box it was built on, a 4K channel showed one picture and stopped that way */
    var hardwareSync: Boolean get() = flag("hardwareSync2", false); set(v) = put("hardwareSync2", v)
    /** the screen is switched to the channel's frame rate (50 or 60 a second), so that motion is even */
    var matchRate: Boolean get() = flag("matchRate", true); set(v) = put("matchRate", v)
    /** how much later (+) or earlier (-) the sound is played against the picture, in milliseconds (not with hardwareSync) */
    var soundDelay: Int get() = p.getInt("soundDelay", 0).coerceIn(-400, 400); set(v) { p.edit().putInt("soundDelay", v.coerceIn(-400, 400)).apply() }
    /** Up on the remote is the next channel (false: the previous one) */
    var upIsNext: Boolean get() = flag("upIsNext", true); set(v) = put("upIsNext", v)
    /** the channels watched last are given to the TV's home screen */
    var homeRow: Boolean get() = flag("homeRow", true); set(v) = put("homeRow", v)

    /** the language of the sound chosen last ("" = whatever the channel plays first); used on every channel that has it */
    var audioLang: String get() = str("audioLang"); set(v) = put("audioLang", v)
    /** the language of the subtitles chosen last; "" = subtitles are off */
    var subLang: String get() = str("subLang"); set(v) = put("subLang", v)
    /** the picture's size, for the channels where it was changed (see MainActivity.PICTURE); 0 = as the channel sends it */
    fun picture(key: String): Int = lines(pre + "pictures").firstOrNull { it.startsWith("$key\t") }?.substringAfter('\t')?.toIntOrNull() ?: 0
    fun setPicture(key: String, mode: Int) {
        val rest = lines(pre + "pictures").filter { !it.startsWith("$key\t") }
        put(pre + "pictures", (if (mode == 0) rest else (rest + "$key\t$mode")).takeLast(300).joinToString("\n"))
    }

    /** channels whose stream is sent with picture and sound on different clocks: they are read the careful way from the start */
    val mended: Set<String> get() = lines(pre + "mended").toSet()
    fun addMended(key: String) { if (key.isNotEmpty() && key !in mended) put(pre + "mended", (lines(pre + "mended") + key).takeLast(200).joinToString("\n")) }

    /** past programmes, start over and pause, on channels the playlist says are recorded */
    var archive: Boolean get() = flag("archive", true); set(v) = put("archive", v)
    /** the channel that was on before the one that plays (Back goes to it) */
    var prev: String get() = str(pre + "prev"); set(v) = put(pre + "prev", v)

    /** a small clock stays in the corner while watching */
    var clock: Boolean get() = flag("clock", false); set(v) = put("clock", v)
    /** while a channel changes the screen is black (false: the last picture of the channel before stays) */
    var blackSwitch: Boolean get() = flag("blackSwitch", false); set(v) = put("blackSwitch", v)
    /** how see-through the bars over the picture are, in per cent (0 - 50) */
    var glass: Int get() = p.getInt("glass", 10).coerceIn(0, 50); set(v) { p.edit().putInt("glass", v.coerceIn(0, 50)).apply() }
    /** after a pause the channel goes on this many seconds earlier (0, 5 or 10) */
    var resumeBack: Int get() = p.getInt("resumeBack", 5).coerceIn(0, 10); set(v) { p.edit().putInt("resumeBack", v.coerceIn(0, 10)).apply() }

    /** in the channel list the small window plays the channel under the focus (false: it stays on the channel that is watched) */
    var previewFocus: Boolean get() = flag("previewFocus", false); set(v) = put("previewFocus", v)

    var homeChannel: Long get() = p.getLong("homeChannel", 0L); set(v) { p.edit().putLong("homeChannel", v).apply() }
}
