package app.hermes.channels

import android.content.Context

/** What the app remembers. The playlist link holds the subscription key: it lives only here, in the app's private storage. */
class Prefs(ctx: Context) {
    private val p = ctx.applicationContext.getSharedPreferences("s", Context.MODE_PRIVATE)

    private fun str(k: String) = p.getString(k, "") ?: ""
    private fun put(k: String, v: String) = p.edit().putString(k, v).apply()
    private fun lines(k: String) = str(k).split('\n').filter { it.isNotEmpty() }

    var playlist: String get() = str("playlist"); set(v) = put("playlist", v.trim())
    /** an optional second playlist that carries logos and guide ids */
    var extra: String get() = str("extra"); set(v) = put("extra", v.trim())
    /** the programme guide (XMLTV); empty = the one the playlist names */
    var guide: String get() = str("guide"); set(v) = put("guide", v.trim())

    var favs: Set<String> get() = lines("favs").toSet(); set(v) = put("favs", v.joinToString("\n"))
    var recents: List<String> get() = lines("recents"); set(v) = put("recents", v.joinToString("\n"))
    /** the channel that played last (see Library.keyOf) and the list it was chosen from */
    var last: String get() = str("last"); set(v) = put("last", v)
    var scope: String get() = str("scope"); set(v) = put("scope", v)
    /** what the TV's home screen is given: key, name, logo per line (so it can be given again without the playlist) */
    var homeRows: List<String> get() = lines("homeRows"); set(v) = put("homeRows", v.joinToString("\n"))
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

    var homeChannel: Long get() = p.getLong("homeChannel", 0L); set(v) { p.edit().putLong("homeChannel", v).apply() }
}
