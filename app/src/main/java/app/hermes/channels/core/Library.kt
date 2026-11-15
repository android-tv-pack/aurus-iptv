package app.hermes.channels.core

/** The channels of a playlist as the viewer sees them: groups, favourites, recents, search, numbers. Plain Kotlin. */
class Library(val channels: List<M3u.Channel>) {

    /** what a channel is remembered by: its number in the stream address, else its name (a playlist may reorder) */
    fun keyOf(i: Int): String = keyOf(channels[i])
    private val byKey: Map<String, Int> = HashMap<String, Int>().also { m -> for ((i, c) in channels.withIndex()) if (!m.containsKey(keyOf(c))) m[keyOf(c)] = i }

    fun indexOf(key: String): Int = byKey[key] ?: -1

    val groups: List<String> = M3u.groups(channels)

    /** the channels (as positions in the playlist) of a list the viewer can choose */
    fun list(what: String, favs: Collection<String>, recents: List<String>): List<Int> = when {
        what == ALL -> channels.indices.toList()
        what == FAVS -> favs.map { indexOf(it) }.filter { it >= 0 }.sorted()
        what == RECENT -> recents.map { indexOf(it) }.filter { it >= 0 }
        what.startsWith(FOUND) -> search(what.substring(FOUND.length))
        else -> channels.indices.filter { channels[it].group == what }
    }

    /** channels whose name contains every word of [q]; names that start with it come first */
    fun search(q: String): List<Int> {
        val words = q.lowercase().split(' ', '\t').filter { it.isNotEmpty() }
        if (words.isEmpty()) return emptyList()
        val hit = channels.indices.filter { i -> val n = channels[i].name.lowercase(); words.all { n.contains(it) } }
        val first = words[0]
        return hit.sortedBy { if (channels[it].name.lowercase().startsWith(first)) 0 else 1 }
    }

    /** the channel after ([step] 1) or before (-1) [current] within [scope], going round; [current] itself when it is alone */
    fun step(scope: List<Int>, current: Int, step: Int): Int {
        if (scope.isEmpty()) return current
        val at = scope.indexOf(current)
        if (at < 0) return scope[if (step > 0) 0 else scope.size - 1]
        return scope[(at + step + scope.size) % scope.size]
    }

    companion object {
        const val ALL = "\u0001all"
        const val FAVS = "\u0001favs"
        const val RECENT = "\u0001recent"
        const val FOUND = "\u0001found:"

        fun keyOf(c: M3u.Channel): String = c.sid.ifEmpty { "n:" + M3u.norm(c.name) }

        /** [key] put first in [list], once, at most [keep] */
        fun pushed(list: List<String>, key: String, keep: Int = 30): List<String> = (listOf(key) + list.filter { it != key }).take(keep)

        /** the channel number typed so far with [digit] added (at most four digits; a leading zero is dropped) */
        fun typed(soFar: String, digit: Int): String = (soFar + digit).trimStart('0').take(4)

        /** the position in the playlist of channel number [typed] ("12" is the twelfth channel), -1 when there is none */
        fun numbered(typed: String, count: Int): Int {
            val n = typed.toIntOrNull() ?: return -1
            return if (n in 1..count) n - 1 else -1
        }
    }
}
