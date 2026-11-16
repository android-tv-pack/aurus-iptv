package app.hermes.channels

import app.hermes.channels.core.Films
import app.hermes.channels.core.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class FilmsTest {

    @Test fun aLinkHasAKeyAndAnAddress() {
        val l = Films.link(" portal::[key:abc-123]http://films.example.com/api/v1/ ")
        assertNotNull(l)
        assertEquals("abc-123", l!!.key)
        assertEquals("http://films.example.com/api/v1/", l.address)
        assertNotNull(Films.link("[key:k]https://x.example/api/"))
        assertNull(Films.link("http://films.example.com/list.m3u"))
        assertNull(Films.link("portal::[key:]http://x.example/"))
    }

    @Test fun theFirstAnswerListsShelvesAndTheSearch() {
        val r = Films.reply("""{"type":"videoportal","url":"http://x/api/","request":{"key":"k"},"items":[
            {"type":"category","title":"Films","request":{"cmd":"flicks","fid":1,"offset":0,"limit":0}},
            {"type":"category","title":"Series","request":{"cmd":"flicks","fid":2,"offset":0,"limit":0}}],
            "controls":{"search":{"request":{"cmd":"search","query":"{USERINPUT}"}}}}""")
        assertEquals("", r.error)
        assertEquals(2, r.entry!!.parts.size)
        assertEquals(Films.Kind.SHELF, r.entry!!.parts[0].kind)
        assertEquals("Series", r.entry!!.parts[1].title)
        val q = Films.searching(r.search!!, "the \"best\"")
        assertEquals("""{"key":"k","cmd":"search","query":"the \"best\""}""", Films.body("k", q))
    }

    @Test fun aShelfListsFilmsAndSeriesAndSaysHowManyThereAre() {
        val r = Films.reply("""{"type":"category","items":[
            {"type":"stream","title":" One ","img":"http://x/h.jpg","imglr":"http://x/l.jpg","request":{"cmd":"flick","fid":7},"fid":7,
             "url":"http://x/7/master.m3u8","fhd":true,"4k":false,"hdr":false,"year":2025,"duration":174,"description":"About it","agelimit":16},
            {"type":"multistream","title":"Two. Season 5.","img":"http://x/h2.jpg","request":{"cmd":"flick","fid":9},"fid":9,"4k":true,"hdr":true,"year":2012,"duration":0}],
            "count":13137}""")
        assertEquals(13137, r.count)
        val a = r.entry!!.parts[0]; val b = r.entry!!.parts[1]
        assertEquals(Films.Kind.FILM, a.kind); assertEquals("One", a.title); assertEquals("7", a.id)
        assertEquals("http://x/l.jpg", a.small)
        assertEquals("2025  \u00B7  2 h 54 min  \u00B7  Full HD  \u00B7  16+", a.facts())
        assertEquals(Films.Kind.SERIES, b.kind); assertEquals("http://x/h2.jpg", b.small)
        assertEquals("2012  \u00B7  4K HDR", b.facts())
        assertEquals("""{"key":"k","cmd":"flick","fid":9}""", Films.body("k", b.request))
    }

    @Test fun aFilmHasItsAddressesTheHighestFirst() {
        val r = Films.reply("""{"type":"stream","title":"One","fid":7,"variants":{"480":"http://x/1.m3u8","1080":"http://x/3.m3u8","720":"http://x/2.m3u8","auto":"http://x/m.m3u8"},"url":"http://x/m.m3u8"}""")
        assertEquals("http://x/m.m3u8", r.entry!!.address)
        assertEquals(listOf("1080" to "http://x/3.m3u8", "720" to "http://x/2.m3u8", "480" to "http://x/1.m3u8"), r.entry!!.variants)
    }

    @Test fun aSeriesHasItsEpisodes() {
        val r = Films.reply("""{"type":"multistream","title":"Two","items":[{"type":"stream","title":"Episode 1","request":{"cmd":"flick","fid":10},"fid":10,"url":"http://x/10.m3u8"},
            {"type":"stream","title":"Episode 2","request":{"cmd":"flick","fid":11},"fid":11,"url":"http://x/11.m3u8"}],"fid":9}""")
        assertEquals(2, r.entry!!.parts.size)
        assertEquals("http://x/11.m3u8", r.entry!!.parts[1].address)
    }

    @Test fun aRefusalIsSaidAndRubbishDoesNotThrow() {
        assertEquals("subscription required", Films.reply("""{"type":"error", "description":"subscription required"}""").error)
        assertTrue(Films.reply("<html>").error.isNotEmpty())
        assertTrue(Films.reply("").error.isNotEmpty())
    }

    @Test fun aLongShelfIsAskedForInPieces() {
        val req = Json.obj(Json.parse("""{"cmd":"flicks","fid":1,"offset":0,"limit":0}"""))
        assertEquals("""{"key":"k","cmd":"flicks","fid":1,"offset":60,"limit":30}""", Films.body("k", req, 60, 30))
    }
}
