package app.hermes.channels.core

/** A small reader of JSON, enough for what servers of channels answer: objects as maps, arrays as lists, the rest as text, numbers, true/false, null. */
object Json {
    fun parse(text: String): Any? = Reader(text).run { skip(); value() }
    /** the value as text, whatever it was sent as ("7", 7, 7.0) */
    fun str(v: Any?): String = when (v) {
        null -> ""
        is Double -> if (v == Math.floor(v) && !v.isInfinite()) v.toLong().toString() else v.toString()
        else -> v.toString()
    }
    @Suppress("UNCHECKED_CAST") fun obj(v: Any?): Map<String, Any?> = v as? Map<String, Any?> ?: emptyMap()
    @Suppress("UNCHECKED_CAST") fun list(v: Any?): List<Any?> = when (v) { is List<*> -> v; is Map<*, *> -> (v as Map<String, Any?>).values.toList(); else -> emptyList() }

    private class Reader(val s: String) {
        var i = 0
        fun skip() { while (i < s.length && s[i].isWhitespace()) i++ }
        fun value(): Any? {
            if (i >= s.length) throw IllegalArgumentException("json ends early")
            return when (s[i]) {
                '{' -> { i++; val m = LinkedHashMap<String, Any?>(); skip()
                    if (s[i] == '}') { i++; return m }
                    while (true) { skip(); val k = string(); skip(); expect(':'); skip(); m[k] = value(); skip(); if (s[i] == ',') { i++; continue }; expect('}'); return m } }
                '[' -> { i++; val l = ArrayList<Any?>(); skip()
                    if (s[i] == ']') { i++; return l }
                    while (true) { skip(); l.add(value()); skip(); if (s[i] == ',') { i++; continue }; expect(']'); return l } }
                '"' -> string()
                't' -> { i += 4; true }
                'f' -> { i += 5; false }
                'n' -> { i += 4; null }
                else -> { val from = i; while (i < s.length && (s[i].isDigit() || s[i] in "+-.eE")) i++
                    if (from == i) throw IllegalArgumentException("json: unexpected ${s[i]}"); s.substring(from, i).toDouble() }
            }
        }
        fun expect(c: Char) { if (i >= s.length || s[i] != c) throw IllegalArgumentException("json: $c expected"); i++ }
        fun string(): String {
            expect('"')
            val b = StringBuilder()
            while (true) {
                val c = s[i++]
                when (c) {
                    '"' -> return b.toString()
                    '\\' -> when (val e = s[i++]) {
                        'n' -> b.append('\n'); 't' -> b.append('\t'); 'r' -> b.append('\r'); 'b' -> b.append('\b'); 'f' -> b.append('\u000C')
                        'u' -> { b.append(s.substring(i, i + 4).toInt(16).toChar()); i += 4 }
                        else -> b.append(e)
                    }
                    else -> b.append(c)
                }
            }
        }
    }
}

private fun enc(s: String) = java.net.URLEncoder.encode(s, "UTF-8")
/** for a value between the quotes of a playlist's line, and its name */
private fun plain(s: String) = s.replace('"', '\'').replace('\n', ' ').replace('\r', ' ').trim()

/**
 * A login of the kind called Xtream Codes: a server's address, a name and a password. The server lists its channels
 * as JSON; they are written out as a playlist, which the app reads like any other.
 */
object Xtream {
    /** the link such a login is kept as: the address of the server's list of channels */
    fun link(server: String, user: String, pass: String): String {
        var s = server.trim()
        if (!s.startsWith("http://") && !s.startsWith("https://")) s = "http://$s"
        // (an address copied with a page of the server behind it: only the server is meant)
        val slash = s.indexOf('/', s.indexOf("://") + 3)
        if (slash > 0) s = s.substring(0, slash)
        return "$s/player_api.php?username=${enc(user.trim())}&password=${enc(pass.trim())}"
    }
    fun isLogin(link: String) = "/player_api.php?" in link && "action=" !in link
    fun categories(link: String) = "$link&action=get_live_categories"
    fun streams(link: String) = "$link&action=get_live_streams"

    /** whether the server's answer to the login says the name and password are good */
    fun accepted(info: String): Boolean = try {
        val u = Json.obj(Json.obj(Json.parse(info))["user_info"])
        Json.str(u["auth"]) != "0" && u.isNotEmpty() && Json.str(u["status"]).lowercase().let { it.isEmpty() || it == "active" || it == "trial" }
    } catch (e: Exception) { false }

    fun toM3u(link: String, info: String, categories: String, streams: String): String {
        val base = link.substringBefore("/player_api.php")
        val q = link.substringAfter('?').split('&').associate { it.substringBefore('=') to java.net.URLDecoder.decode(it.substringAfter('=', ""), "UTF-8") }
        val user = q["username"].orEmpty(); val pass = q["password"].orEmpty()
        // (as a list of pieces where the server offers that: the way the app plays best)
        val formats = try { Json.list(Json.obj(Json.obj(Json.parse(info))["user_info"])["allowed_output_formats"]).map { Json.str(it) } } catch (e: Exception) { emptyList() }
        val ext = if (formats.isEmpty() || "m3u8" in formats) "m3u8" else "ts"
        val names = Json.list(Json.parse(categories)).associate { Json.obj(it).let { c -> Json.str(c["category_id"]) to Json.str(c["category_name"]) } }
        val out = StringBuilder("#EXTM3U url-tvg=\"$base/xmltv.php?username=${enc(user)}&password=${enc(pass)}\"\n")
        for (v in Json.list(Json.parse(streams))) {
            val c = Json.obj(v)
            val id = Json.str(c["stream_id"]); val name = plain(Json.str(c["name"]))
            if (id.isEmpty() || name.isEmpty()) continue
            val days = if (Json.str(c["tv_archive"]) == "1") Json.str(c["tv_archive_duration"]).toIntOrNull() ?: 0 else 0
            out.append("#EXTINF:-1 tvg-id=\"").append(plain(Json.str(c["epg_channel_id"]))).append("\" tvg-logo=\"").append(plain(Json.str(c["stream_icon"])))
                .append("\" group-title=\"").append(plain(names[Json.str(c["category_id"])].orEmpty())).append('"')
            if (days > 0) out.append(" catchup=\"xc\" catchup-days=\"").append(days).append('"')
            out.append(',').append(name).append('\n')
            out.append(base).append("/live/").append(enc(user)).append('/').append(enc(pass)).append('/').append(id).append('.').append(ext).append('\n')
        }
        return out.toString()
    }
}

/**
 * A login of the kind set-top boxes use (a "portal", Stalker or Ministra): the portal's address and the address of the
 * box's network card (MAC). The portal lists its channels; the address a channel plays from is asked for each time
 * it is played. [get] fetches an address with the headers given.
 */
class Portal(val link: String, private val get: (String, Map<String, String>) -> String) {
    private val address = link.removePrefix(MARK).substringBefore('#')
    private val mac = link.substringAfter('#', "")
    private var endpoint = ""
    @Volatile private var token = ""

    private fun headers(): Map<String, String> {
        val h = LinkedHashMap<String, String>()
        h["User-Agent"] = "Mozilla/5.0 (QtEmbedded; U; Linux; C) AppleWebKit/533.3 (KHTML, like Gecko) MAG200 stbapp ver: 2 rev: 250 Safari/533.3"
        h["X-User-Agent"] = "Model: MAG250; Link: WiFi"
        h["Cookie"] = "mac=${enc(mac)}; stb_lang=en; timezone=Europe/London"
        if (token.isNotEmpty()) h["Authorization"] = "Bearer $token"
        return h
    }
    private fun ask(query: String): Any? = Json.obj(Json.parse(get("$endpoint?$query&JsHttpRequest=1-xml", headers())))["js"]

    /** makes itself known to the portal; throws when no way of asking it answers */
    fun login() {
        val root = address.substringBefore("/c/").substringBefore("/c").trimEnd('/').let { if (it.endsWith("/stalker_portal")) it.removeSuffix("/stalker_portal") else it }
        val ways = (if ("stalker_portal" in address) listOf("$root/stalker_portal/server/load.php") else emptyList()) +
            listOf("$root/portal.php", "$root/server/load.php", "$root/stalker_portal/server/load.php")
        var last: Exception? = null
        for (w in ways.distinct()) {
            try {
                endpoint = w; token = ""
                val t = Json.str(Json.obj(ask("type=stb&action=handshake&token="))["token"])
                if (t.isEmpty()) continue
                token = t
                try { ask("type=stb&action=get_profile&hd=1&auth_second_step=0") } catch (e: Exception) {}
                return
            } catch (e: Exception) { last = e }
        }
        token = ""
        throw last ?: java.io.IOException("no portal")
    }

    /** the portal's channels as a playlist; each is played from an address of the form [SCHEME]… that [address] turns into a real one */
    fun m3u(): String {
        val genres = Json.list(ask("type=itv&action=get_genres")).associate { Json.obj(it).let { g -> Json.str(g["id"]) to Json.str(g["title"]) } }
        val all = ask("type=itv&action=get_all_channels")
        val out = StringBuilder("#EXTM3U\n")
        for (v in Json.list(Json.obj(all)["data"] ?: all)) {
            val c = Json.obj(v)
            val id = Json.str(c["id"]); val name = plain(Json.str(c["name"])); val cmd = Json.str(c["cmd"])
            if (id.isEmpty() || name.isEmpty() || cmd.isEmpty()) continue
            val logo = Json.str(c["logo"]).let { if (it.startsWith("http")) plain(it) else "" }
            val group = genres[Json.str(c["tv_genre_id"])].orEmpty().let { if (it.equals("all", true)) "" else it }
            out.append("#EXTINF:-1 tvg-id=\"").append(plain(Json.str(c["xmltv_id"]))).append("\" tvg-logo=\"").append(logo).append("\" group-title=\"").append(plain(group)).append("\",").append(name).append('\n')
            out.append(SCHEME).append("tv/ch/").append(id).append("?cmd=").append(enc(cmd)).append('\n')
        }
        return out.toString()
    }

    /** the address the channel plays from right now */
    fun address(channel: String): String {
        val cmd = java.net.URLDecoder.decode(channel.substringAfter("?cmd=", ""), "UTF-8")
        fun real(s: String) = s.trim().split(' ').last { it.isNotEmpty() }
        fun once(): String = real(Json.str(Json.obj(ask("type=itv&action=create_link&cmd=${enc(cmd)}&series=&forced_storage=undefined&disable_ad=0&download=0"))["cmd"]))
        if (token.isEmpty()) login()
        val a = try { once() } catch (e: Exception) { "" }
        // (the portal has forgotten this sitting: once more, made known anew)
        val b = if (a.startsWith("http")) a else { login(); try { once() } catch (e: Exception) { "" } }
        if (b.startsWith("http")) return b
        // (a portal that gives no address of its own: the one the channel is listed with, where it is a real one)
        val listed = real(cmd)
        if (listed.startsWith("http") && "localhost" !in listed) return listed
        throw java.io.IOException("no address")
    }

    companion object {
        const val MARK = "portal+"
        const val SCHEME = "portal://"
        private val macForm = Regex("""^([0-9A-F]{2}:){5}[0-9A-F]{2}$""")
        /** the MAC as it is written (upper case, colons), or null when it is not one */
        fun mac(typed: String): String? {
            val hex = typed.uppercase().filter { it in "0123456789ABCDEF" }
            val m = if (hex.length == 12) hex.chunked(2).joinToString(":") else typed.trim().uppercase()
            return if (macForm.matches(m)) m else null
        }
        fun link(address: String, mac: String): String {
            var s = address.trim()
            if (!s.startsWith("http://") && !s.startsWith("https://")) s = "http://$s"
            return MARK + s.substringBefore('#') + "#" + mac
        }
        fun isLogin(link: String) = link.startsWith(MARK)
    }
}
