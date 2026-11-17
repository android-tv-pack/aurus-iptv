package app.hermes.channels

import app.hermes.channels.core.Json
import app.hermes.channels.core.M3u
import app.hermes.channels.core.Portal
import app.hermes.channels.core.Xtream
import app.hermes.channels.core.Catchup
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LoginsTest {
    @Test fun json() {
        val v = Json.obj(Json.parse("""{"a":[1,"two",{"b":null}],"c":"x\"y\u0041","d":-1.5e1,"e":true}"""))
        assertEquals("1", Json.str(Json.list(v["a"])[0]))
        assertEquals("x\"yA", v["c"])
        assertEquals("-15", Json.str(v["d"]))
        assertEquals(true, v["e"])
    }

    @Test fun xtreamLink() {
        assertEquals("http://tv.example:8080/player_api.php?username=me&password=p%40ss", Xtream.link(" tv.example:8080/get.php?x=1 ", "me", "p@ss"))
        assertTrue(Xtream.isLogin(Xtream.link("https://tv.example", "a", "b")))
        assertFalse(Xtream.isLogin("http://tv.example/list.m3u"))
    }

    @Test fun xtreamAccepted() {
        assertTrue(Xtream.accepted("""{"user_info":{"auth":1,"status":"Active"}}"""))
        assertFalse(Xtream.accepted("""{"user_info":{"auth":0}}"""))
        assertFalse(Xtream.accepted("""{"user_info":{"auth":1,"status":"Expired"}}"""))
        assertFalse(Xtream.accepted("<html>"))
    }

    @Test fun xtreamPlaylist() {
        val link = Xtream.link("http://tv.example:8080", "me", "pw")
        val text = Xtream.toM3u(link, """{"user_info":{"auth":1,"allowed_output_formats":["m3u8","ts"]}}""",
            """[{"category_id":"3","category_name":"News"}]""",
            """[{"num":1,"name":"One \"HD\"","stream_id":101,"stream_icon":"http://l.example/1.png","epg_channel_id":"one.tv","category_id":"3","tv_archive":1,"tv_archive_duration":"7"},
               {"name":"Two","stream_id":"102","stream_icon":"","epg_channel_id":null,"category_id":"9","tv_archive":0,"tv_archive_duration":0}]""")
        val p = M3u.parse(text)
        assertEquals("http://tv.example:8080/xmltv.php?username=me&password=pw", p.guideUrl)
        assertEquals(2, p.channels.size)
        val one = p.channels[0]
        assertEquals("One 'HD'", one.name); assertEquals("News", one.group); assertEquals("one.tv", one.tvgId)
        assertEquals("http://tv.example:8080/live/me/pw/101.m3u8", one.url)
        assertEquals("101", one.sid)
        assertEquals(7, one.rec!!.days)
        // what it showed earlier is asked for the way such servers want it
        assertTrue(Catchup.address(one.url, one.rec!!, 1_700_000_000, 1_700_003_600, 3600).startsWith("http://tv.example:8080/timeshift/me/pw/60/"))
        assertNull(p.channels[1].rec)
        assertEquals("", p.channels[1].group)
        // a server that offers no list of pieces: the plain stream
        assertTrue(M3u.parse(Xtream.toM3u(link, """{"user_info":{"auth":1,"allowed_output_formats":["ts"]}}""", "[]", """[{"name":"A","stream_id":5}]""")).channels[0].url.endsWith("/5.ts"))
    }

    @Test fun mac() {
        assertEquals("00:1A:79:AB:CD:EF", Portal.mac("00:1a:79:ab:cd:ef"))
        assertEquals("00:1A:79:AB:CD:EF", Portal.mac("001a79abcdef"))
        assertNull(Portal.mac("00:1A:79"))
    }

    @Test fun portal() {
        val asked = ArrayList<String>()
        var sitting = 0
        val p = Portal(Portal.link("portal.example/c/", "00:1A:79:AB:CD:EF")) { url, h ->
            asked += url.substringAfter("action=").substringBefore('&')
            assertTrue(h["Cookie"]!!.contains("mac=00%3A1A%3A79%3AAB%3ACD%3AEF"))
            if (!url.startsWith("http://portal.example/portal.php?")) throw java.io.IOException("404")
            when {
                "action=handshake" in url -> { sitting++; """{"js":{"token":"T$sitting"}}""" }
                "action=get_profile" in url -> """{"js":{"id":1}}"""
                "action=get_genres" in url -> """{"js":[{"id":"*","title":"All"},{"id":"2","title":"Sport"}]}"""
                "action=get_all_channels" in url -> """{"js":{"total_items":2,"data":[{"id":"7","name":"Seven","cmd":"ffmpeg http://localhost/ch/7","logo":"7.png","tv_genre_id":"2","xmltv_id":"seven"},{"id":"8","name":"Eight","cmd":"ffrt http://real.example/8.m3u8","logo":"http://l.example/8.png","tv_genre_id":"*"}]}}"""
                "action=create_link" in url -> if (h["Authorization"] == "Bearer T2") """{"js":{"cmd":"ffmpeg http://stream.example/play/7?token=abc"}}""" else """{"js":[]}"""
                else -> "{}"
            }
        }
        p.login()
        val list = M3u.parse(p.m3u())
        assertEquals(2, list.channels.size)
        assertEquals("Sport", list.channels[0].group); assertEquals("", list.channels[1].group)
        assertEquals("", list.channels[0].logo); assertEquals("http://l.example/8.png", list.channels[1].logo)
        assertEquals("7", list.channels[0].sid)
        assertTrue(list.channels[0].url.startsWith(Portal.SCHEME))
        // (the first sitting is forgotten by this portal: it is made known anew, and then the address comes)
        assertEquals("http://stream.example/play/7?token=abc", p.address(list.channels[0].url))
        assertEquals(2, sitting)
    }

    @Test fun textGoesIntoAWebAddressAndComesBackAsJavaDoesIt() {
        for (t in listOf("plain", "a b&c=d/e?f", "\u043f\u0440\u0438\u0432\u0435\u0442 \u4e16\u754c", "100% + ~!*'()", "ffmpeg http://host/ch/1_.m3u8")) {
            org.junit.Assert.assertEquals(java.net.URLEncoder.encode(t, "UTF-8"), app.hermes.channels.core.Web.enc(t))
            org.junit.Assert.assertEquals(t, app.hermes.channels.core.Web.dec(java.net.URLEncoder.encode(t, "UTF-8")))
        }
        org.junit.Assert.assertEquals("50% off", app.hermes.channels.core.Web.dec("50% off"))
    }
}
