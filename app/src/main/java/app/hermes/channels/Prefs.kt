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
    var homeChannel: Long get() = p.getLong("homeChannel", 0L); set(v) { p.edit().putLong("homeChannel", v).apply() }
}
