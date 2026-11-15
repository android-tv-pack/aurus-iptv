package app.hermes.channels

import org.junit.Assert.assertEquals
import org.junit.Test

class M3uTest {
    private val text = """
        #EXTM3U url-tvg="http://g.example/epg.xml.gz"
        #EXTINF:0 tvg-id="one" tvg-logo="http://l.example/1.png" group-title="News",First, the channel
        http://host.example/iptv/SECRETKEY/19241/index.m3u8
        #EXTINF:-1,Second
        #EXTGRP:Films
        http://host.example/iptv/SECRETKEY/77/index.m3u8
        # a comment
        http://host.example/orphan.m3u8
    """.trimIndent()

    @Test fun aPlaylistIsRead() {
        val p = M3u.parse(text)
        assertEquals("http://g.example/epg.xml.gz", p.guideUrl)
        assertEquals(listOf("First, the channel", "Second"), p.channels.map { it.name })
        assertEquals(listOf("News", "Films"), M3u.groups(p.channels))
        assertEquals("19241", p.channels[0].sid)
        assertEquals("one", p.channels[0].tvgId)
        assertEquals("http://host.example/iptv/SECRETKEY/77/index.m3u8", p.channels[1].url)
    }

    @Test fun logosComeFromACompanionList() {
        val own = M3u.parse(text)
        val other = M3u.parse("#EXTINF:0 tvg-id=\"s2\" tvg-logo=\"http://l.example/77.png\",Other name\nhttp://x.example/a/b/c/77.m3u8\n#EXTINF:0 tvg-logo=\"http://l.example/z.png\",SECOND\nhttp://x.example/z.m3u8")
        val m = M3u.merge(own, other)
        assertEquals("http://l.example/77.png", m.channels[1].logo)      // by number
        assertEquals("s2", m.channels[1].tvgId)
        assertEquals("http://l.example/1.png", m.channels[0].logo)       // its own is kept
    }
}
