package app.hermes.channels

import app.hermes.channels.core.Library
import app.hermes.channels.core.M3u
import org.junit.Assert.assertEquals
import org.junit.Test

class LibraryTest {
    private fun ch(name: String, group: String, n: Int) = M3u.Channel(name, group, "http://h.example/iptv/KEY/$n/index.m3u8", "", "")
    private val lib = Library(listOf(ch("First News", "News", 11), ch("Cinema One", "Films", 22), ch("News 24", "News", 33), ch("One Music", "Music", 44),
        M3u.Channel("No Number", "Music", "http://h.example/x.m3u8", "", "")))

    @Test fun channelsAreKnownByTheirNumberOrName() {
        assertEquals("22", lib.keyOf(1))
        assertEquals("n:nonumber", lib.keyOf(4))
        assertEquals(2, lib.indexOf("33"))
        assertEquals(-1, lib.indexOf("99"))
    }

    @Test fun lists() {
        assertEquals(listOf("News", "Films", "Music"), lib.groups)
        assertEquals(listOf(0, 2), lib.list("News", emptyList(), emptyList()))
        assertEquals(5, lib.list(Library.ALL, emptyList(), emptyList()).size)
        assertEquals(listOf(1, 3), lib.list(Library.FAVS, listOf("44", "22", "gone"), emptyList()))        // in the playlist's order
        assertEquals(listOf(3, 0), lib.list(Library.RECENT, emptyList(), listOf("44", "gone", "11")))      // newest first
        assertEquals(listOf(3, 1), lib.list(Library.FOUND + "one", emptyList(), emptyList()))              // "One Music" starts with it
        assertEquals(listOf(2), lib.search("news 24"))
        assertEquals(emptyList<Int>(), lib.search("  "))
    }

    @Test fun stepping() {
        val news = listOf(0, 2)
        assertEquals(2, lib.step(news, 0, 1)); assertEquals(0, lib.step(news, 2, 1)); assertEquals(2, lib.step(news, 0, -1))
        assertEquals(0, lib.step(news, 3, 1))            // from a channel outside the list: its first
        assertEquals(2, lib.step(news, 3, -1))
        assertEquals(3, lib.step(emptyList(), 3, 1))
    }

    @Test fun recentsAndNumbers() {
        assertEquals(listOf("c", "a", "b"), Library.pushed(listOf("a", "b", "c"), "c"))
        assertEquals(listOf("d", "a"), Library.pushed(listOf("a", "b"), "d", keep = 2))
        assertEquals("12", Library.typed(Library.typed("", 1), 2))
        assertEquals("5", Library.typed("0", 5))
        assertEquals("1234", Library.typed("1234", 5))
        assertEquals(11, Library.numbered("12", 3338)); assertEquals(-1, Library.numbered("4000", 3338)); assertEquals(-1, Library.numbered("", 10))
    }
}
