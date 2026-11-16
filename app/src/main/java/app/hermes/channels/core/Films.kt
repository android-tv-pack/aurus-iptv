package app.hermes.channels.core

/**
 * A library of films and series that a provider offers beside its channels, asked for over a small web interface
 * (several providers and players share it; the link is called a "VPortal" link by some).
 *
 * The link has the form `portal::[key:KEY]http://host/path/`. Every question is a JSON object sent to that address, with
 * the key in it; the first one holds the key alone. Every answer is a JSON object with a `type`:
 *  - a shelf (`category`, also the first answer): `items`, each with a `title` and the `request` that opens it; with
 *    `count` when there are more than were sent (asked for by `offset` and `limit` in the request);
 *  - a film (`stream`): `url`, and `variants` by height;
 *  - a series (`multistream`): `items`, its episodes, each a film;
 *  - `error`, with a `description`.
 * The first answer may also name the request to search with (`controls.search.request`, the words in place of {USERINPUT})
 * and ways to narrow the whole library down (`controls.filters`: each a `title` and `items`, each of those a `title` and
 * a `request`). One choice of each way may be put together into one question, which is asked by itself (not together with
 * a shelf's) and may be asked for in pieces like a shelf.
 */
object Films {

    class Link(val key: String, val address: String)

    /** the two parts of a library link; null when it is not one */
    fun link(text: String): Link? {
        val t = text.trim()
        val m = Regex("""^(?:portal::)?\[key:([^\]\s]+)]\s*(https?://\S+)$""", RegexOption.IGNORE_CASE).find(t) ?: return null
        return Link(m.groupValues[1], m.groupValues[2])
    }

    enum class Kind { SHELF, FILM, SERIES }

    class Entry(
        val kind: Kind, val title: String, val id: String,
        val poster: String, val small: String,
        val year: Int, val minutes: Int, val about: String, val age: Int,
        val fhd: Boolean, val uhd: Boolean, val hdr: Boolean,
        /** what to ask to open it (a shelf's entries, a film's addresses, a series' episodes) */
        val request: Map<String, Any?>,
        /** where it plays from ("" until it was asked for, or for a shelf or a series) */
        val address: String,
        /** by height, the highest first: "1080" to its address */
        val variants: List<Pair<String, String>>,
        val parts: List<Entry>,
    ) {
        /** "2025 · 2 h 54 min · 4K HDR" */
        fun facts(): String {
            val out = ArrayList<String>()
            if (year > 0) out += year.toString()
            if (minutes > 0) out += if (minutes >= 60) "${minutes / 60} h ${minutes % 60} min" else "$minutes min"
            val q = (if (uhd) "4K" else if (fhd) "Full HD" else "") + (if (hdr) " HDR" else "")
            if (q.isNotBlank()) out += q.trim()
            if (age > 0) out += "$age+"
            return out.joinToString("  \u00B7  ")
        }
    }

    /** a way to narrow the library down (by year, by kind of film ...): its name and its choices, each with what to ask */
    class Filter(val title: String, val choices: List<Pair<String, Map<String, Any?>>>)

    class Reply(val entry: Entry?, val count: Int, val search: Map<String, Any?>?, val error: String, val filters: List<Filter> = emptyList())

    /** what an answer says; never throws: a text that is no answer gives an error */
    fun reply(text: String): Reply {
        val o = try { Json.obj(Json.parse(text)) } catch (_: Exception) { emptyMap() }
        if (o.isEmpty()) return Reply(null, 0, null, "The library's answer could not be read")
        if (Json.str(o["type"]) == "error") return Reply(null, 0, null, Json.str(o["description"]).ifEmpty { "The library refused" })
        val search = Json.obj(Json.obj(Json.obj(o["controls"])["search"])["request"]).takeIf { it.isNotEmpty() }
        val filters = Json.list(Json.obj(o["controls"])["filters"]).mapNotNull { f ->
            val m = Json.obj(f)
            val choices = Json.list(m["items"]).mapNotNull { c -> Json.obj(c).let { x -> Json.obj(x["request"]).takeIf { it.isNotEmpty() }?.let { Json.str(x["title"]).trim() to it } } }
            if (choices.isEmpty()) null else Filter(Json.str(m["title"]).trim(), choices)
        }
        return Reply(entry(o), num(o["count"]), search, "", filters)
    }

    private fun num(v: Any?): Int = (v as? Double)?.toInt() ?: Json.str(v).toIntOrNull() ?: 0
    private fun flag(v: Any?): Boolean = v == true || Json.str(v) == "1"

    private fun entry(o: Map<String, Any?>): Entry {
        val type = Json.str(o["type"])
        val parts = (o["items"] as? List<*>)?.mapNotNull { p -> Json.obj(p).takeIf { it.isNotEmpty() }?.let { entry(it) } } ?: emptyList()
        val kind = when (type) { "stream" -> Kind.FILM; "multistream" -> Kind.SERIES; else -> Kind.SHELF }
        val variants = Json.obj(o["variants"]).mapNotNull { (k, v) -> k.toIntOrNull()?.let { h -> h to Json.str(v) } }
            .filter { it.second.isNotEmpty() }.sortedByDescending { it.first }.map { it.first.toString() to it.second }
        val poster = Json.str(o["img"])
        return Entry(kind, Json.str(o["title"]).trim(), Json.str(o["fid"]), poster, Json.str(o["imglr"]).ifEmpty { poster },
            num(o["year"]), num(o["duration"]), Json.str(o["description"]).trim(), num(o["agelimit"]),
            flag(o["fhd"]), flag(o["4k"]), flag(o["hdr"]), Json.obj(o["request"]), Json.str(o["url"]), variants, parts)
    }

    /** the question to send: the key, what the entry asks for, and (for a long shelf) from where and how many */
    fun body(key: String, request: Map<String, Any?> = emptyMap(), offset: Int = -1, limit: Int = -1): String {
        val m = LinkedHashMap<String, Any?>()
        m["key"] = key
        m.putAll(request)
        if (offset >= 0) m["offset"] = offset
        if (limit >= 0) m["limit"] = limit
        return write(m)
    }

    /** several choices (one of each way to narrow down) as one question */
    fun narrowed(choices: List<Map<String, Any?>>): Map<String, Any?> = LinkedHashMap<String, Any?>().also { m -> for (c in choices) m.putAll(c) }

    /** an entry as one line of text to keep, and back ([kept] gives null for a line that is not one) */
    fun keep(e: Entry): String = write(mapOf("kind" to e.kind.name, "title" to e.title, "id" to e.id, "img" to e.poster, "imglr" to e.small, "year" to e.year, "request" to e.request))
    fun kept(line: String): Entry? {
        val o = try { Json.obj(Json.parse(line)) } catch (_: Exception) { return null }
        val kind = Kind.values().firstOrNull { it.name == Json.str(o["kind"]) } ?: return null
        val id = Json.str(o["id"]); if (id.isEmpty()) return null
        return Entry(kind, Json.str(o["title"]), id, Json.str(o["img"]), Json.str(o["imglr"]), num(o["year"]), 0, "", 0, false, false, false, Json.obj(o["request"]), "", emptyList(), emptyList())
    }

    /** the search request with the words put in */
    fun searching(search: Map<String, Any?>, words: String): Map<String, Any?> =
        search.mapValues { (_, v) -> if (v is String) v.replace("{USERINPUT}", words) else v }

    fun write(v: Any?): String = StringBuilder().also { put(it, v) }.toString()

    private fun put(b: StringBuilder, v: Any?) {
        when (v) {
            null -> b.append("null")
            is Boolean -> b.append(v)
            is Double -> b.append(Json.str(v))
            is Number -> b.append(v)
            is Map<*, *> -> { b.append('{'); var first = true
                for ((k, x) in v) { if (!first) b.append(','); first = false; put(b, k.toString()); b.append(':'); put(b, x) }
                b.append('}') }
            is List<*> -> { b.append('['); for ((i, x) in v.withIndex()) { if (i > 0) b.append(','); put(b, x) }; b.append(']') }
            else -> { b.append('"')
                for (c in v.toString()) when {
                    c == '"' -> b.append("\\\""); c == '\\' -> b.append("\\\\"); c == '\n' -> b.append("\\n"); c == '\r' -> b.append("\\r"); c == '\t' -> b.append("\\t")
                    c < ' ' -> b.append("\\u%04x".format(c.code))
                    else -> b.append(c)
                }
                b.append('"') }
        }
    }
}
