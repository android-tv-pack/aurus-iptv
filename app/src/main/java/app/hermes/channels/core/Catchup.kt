package app.hermes.channels.core

/**
 * Recordings of what a channel showed earlier ("catch-up"), as playlists describe them.
 *
 * A playlist says per channel (or once in its first line) how many days are kept and, sometimes, how they are asked
 * for. The ways in use:
 *  - shift: the channel's own address with `utc=START&lutc=NOW` added (also what is meant when only days are given)
 *  - append: the channel's address with the playlist's own text added
 *  - default: an address of its own, given by the playlist
 *  - flussonic: `.../index.m3u8` becomes `.../index-START-LENGTH.m3u8` (and `mpegts` becomes `timeshift_abs-START.ts`)
 *  - xc: `host/user/pass/ID` becomes `host/timeshift/user/pass/MINUTES/Y-m-d:H-M/ID.ts`
 * In the playlist's texts `${start}`, `{utc}`, `${end}`, `{lutc}`, `${duration}`, `${offset}`, `{Y}` `{m}` `{d}` `{H}` `{M}` `{S}` stand for the times.
 */
object Catchup {

    class Rule(val kind: String, val source: String, val days: Int)

    /** what a channel's line ([line]) and the playlist's first line ([head]) say; null = the channel is not recorded */
    fun rule(line: Map<String, String>, head: Map<String, String>): Rule? {
        // (what the channel's own line says goes before what the first line says for all)
        fun of(vararg names: String): String? {
            for (from in arrayOf(line, head)) for (n in names) from[n]?.let { if (it.isNotBlank()) return it.trim() }
            return null
        }
        var kind = (of("catchup", "catchup-type") ?: "").lowercase()
        val source = of("catchup-source") ?: ""
        var days = of("catchup-days", "tvg-rec", "timeshift")?.toDoubleOrNull()?.let { if (it > 0 && it < 1) 1 else it.toInt() }
            ?: of("catchup-time")?.toLongOrNull()?.let { ((it + 86399) / 86400).toInt() } ?: 0
        if (kind.isEmpty()) kind = when { source.isNotEmpty() -> "default"; days > 0 -> "shift"; else -> return null }
        else if (days <= 0) {
            // (stated as recorded, without saying for how long; "0" written out means not recorded)
            if (of("catchup-days", "tvg-rec", "timeshift", "catchup-time") != null) return null
            days = 3
        }
        if (days <= 0) return null
        if (kind == "fs") kind = "flussonic"
        if (kind == "xtream" || kind == "xtream-codes") kind = "xc"
        return Rule(kind, source, days.coerceAtMost(30))
    }

    private fun two(n: Int) = if (n < 10) "0$n" else n.toString()

    /** year, month, day, hour, minute, second (UTC) of a time in seconds */
    fun parts(sec: Long): IntArray {
        val days = sec.floorDiv(86400L)
        val rem = (sec - days * 86400L).toInt()
        // (days since 1970 to a date: the usual civil-calendar arithmetic)
        val z = days + 719468
        val era = z.floorDiv(146097L)
        val doe = (z - era * 146097).toInt()
        val yoe = (doe - doe / 1460 + doe / 36524 - doe / 146096) / 365
        val doy = doe - (365 * yoe + yoe / 4 - yoe / 100)
        val mp = (5 * doy + 2) / 153
        val d = doy - (153 * mp + 2) / 5 + 1
        val m = if (mp < 10) mp + 3 else mp - 9
        val y = (yoe + era * 400).toInt() + (if (m <= 2) 1 else 0)
        return intArrayOf(y, m, d, rem / 3600, rem / 60 % 60, rem % 60)
    }

    private fun filled(text: String, start: Long, now: Long, length: Long): String {
        val p = parts(start)
        var s = text
        fun put(value: String, vararg names: String) { for (n in names) { s = s.replace("\${$n}", value).replace("{$n}", value) } }
        put(start.toString(), "start", "utc", "timestamp")
        put((start + length).toString(), "end", "utcend")
        put(now.toString(), "now", "lutc")
        put(length.toString(), "duration")
        put((now - start).toString(), "offset")
        put(p[0].toString(), "Y"); put(two(p[1]), "m"); put(two(p[2]), "d"); put(two(p[3]), "H"); put(two(p[4]), "M"); put(two(p[5]), "S")
        return s
    }

    private val xc = Regex("""^(https?://[^/]+)/(?:live/)?([^/]+)/([^/]+)/(\d+)(\.\w+)?$""")

    /**
     * The address that plays channel [live] from [start] on (times in seconds; [length] = how much is asked for,
     * where the way of asking needs to know).
     */
    fun address(live: String, rule: Rule, start: Long, now: Long, length: Long): String {
        fun shift() = live + (if ('?' in live) "&" else "?") + "utc=$start&lutc=$now"
        return when (rule.kind) {
            "default" -> if (rule.source.isEmpty()) shift() else filled(rule.source, start, now, length)
            "append" -> live + filled(rule.source, start, now, length)
            "flussonic" -> {
                val path = live.substringBefore('?'); val query = live.substringAfter('?', "").let { if (it.isEmpty()) "" else "?$it" }
                val file = path.substringAfterLast('/')
                when {
                    file.endsWith(".m3u8") -> path.substringBeforeLast('/') + "/" + file.removeSuffix(".m3u8") + "-$start-$length.m3u8" + query
                    file == "mpegts" -> path.substringBeforeLast('/') + "/timeshift_abs-$start.ts" + query
                    else -> shift()
                }
            }
            "xc" -> {
                val m = xc.find(live.substringBefore('?')) ?: return shift()
                val p = parts(start)
                val (host, user, pass, id, ext) = m.destructured
                "$host/timeshift/$user/$pass/${(length + 59) / 60}/${p[0]}-${two(p[1])}-${two(p[2])}:${two(p[3])}-${two(p[4])}/$id" + (if (ext == ".m3u8") ".m3u8" else ".ts")
            }
            else -> shift()
        }
    }
}
