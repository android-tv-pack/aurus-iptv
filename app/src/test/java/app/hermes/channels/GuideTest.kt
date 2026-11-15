package app.hermes.channels

import app.hermes.channels.core.Guide
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class GuideTest {

    @Test fun timesAreRead() {
        assertEquals(0L, Guide.xmltvTime("19700101000000 +0000"))
        assertEquals(1_000L * 1794084900L, Guide.xmltvTime("20261107235500 +0300"))
        assertEquals(Guide.xmltvTime("20240229120000 +0000") + 86_400_000L, Guide.xmltvTime("20240301120000"))
        assertEquals(0L, Guide.xmltvTime("soon"))
    }

    @Test fun blocksAreRead() {
        val (id, p) = Guide.progOf("<programme start=\"20261107231300 +0300\" stop=\"20261108010000 +0300\" channel=\"8900\">\n\t<title lang=\"ru\">Tom &amp; Jerry</title>\n</programme>")!!
        assertEquals("8900", id); assertEquals("Tom & Jerry", p.title); assertEquals(107 * 60_000L, p.stop - p.start)
        assertEquals("", p.desc)
        assertEquals("A cat & a mouse.", Guide.progOf("<programme start=\"20261107231300 +0300\" stop=\"20261108010000 +0300\" channel=\"1\"><title>T</title><desc lang=\"en\">A cat &amp; a mouse.</desc></programme>")!!.second.desc)
        assertNull(Guide.progOf("<programme start=\"x\" stop=\"y\" channel=\"1\"><title>t</title></programme>"))
        val c = Guide.chanOf("<channel id=\"8900\">\n\t<display-name lang=\"ru\">Cinema HD</display-name>\n\t<display-name>Cinema</display-name>\n\t<icon src=\"http://g.example/8900.png\" />\n</channel>")!!
        assertEquals(listOf("Cinema HD", "Cinema"), c.names); assertEquals("http://g.example/8900.png", c.icon)
    }

    @Test fun nowAndNext() {
        val l = listOf(Guide.Prog(0, 100, "a"), Guide.Prog(100, 200, "b"), Guide.Prog(200, 300, "c"))
        assertEquals("b" to "c", Guide.nowNext(l, 150).let { it.first?.title to it.second?.title })
        assertEquals(null to "a", Guide.nowNext(l, -5).let { it.first?.title to it.second?.title })
        assertEquals("c" to null, Guide.nowNext(l, 250).let { it.first?.title to it.second?.title })
        assertEquals(0.5f, Guide.progress(l[1], 150), 0.001f)
    }

    @Test fun aGuideIsWalkedPieceByPiece() {
        val xml = "<?xml version=\"1.0\"?><tv>\n<channel id=\"8900\">\n\t<display-name>One</display-name>\n</channel><channel id=\"146\"><display-name>Two</display-name></channel>\n" +
            "<programme start=\"20261107231300 +0300\" stop=\"20261108010000 +0300\" channel=\"8900\">\n\t<title lang=\"ru\">A</title>\n</programme>\n" +
            "<programme start=\"20261107231300 +0300\" stop=\"20261108010000 +0300\" channel=\"146\"><title>B</title><desc>long &lt;text&gt;</desc></programme></tv>"
        val chans = ArrayList<String>(); val progs = ArrayList<String>()
        val s = Guide.Scanner({ chans.add(Guide.chanOf(it)!!.id) }, { progs.add(Guide.progOf(it)!!.second.title) })
        val b = xml.toByteArray()
        var i = 0
        while (i < b.size) { val n = minOf(7, b.size - i); s.feed(b.copyOfRange(i, i + n), n); i += n }     // every tag is cut somewhere
        assertEquals(listOf("8900", "146"), chans)
        assertEquals(listOf("A", "B"), progs)
    }
}
