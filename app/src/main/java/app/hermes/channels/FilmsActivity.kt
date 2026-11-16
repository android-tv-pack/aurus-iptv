package app.hermes.channels

import android.app.Activity
import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.InputType
import android.util.Log
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.widget.AdapterView
import android.widget.BaseAdapter
import android.widget.FrameLayout
import android.widget.GridView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.TextView
import app.hermes.channels.core.Films
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.Executors

/**
 * Films and series: the shelves of the library at the left, what is on the chosen shelf as a wall of posters, and a page
 * for one film or series. Nothing is kept of the library: every screen is asked for when it is opened.
 */
class FilmsActivity : Activity() {
    private val tag = "Channels"
    private lateinit var ui: Ui
    private lateinit var prefs: Prefs
    private val main = Handler(Looper.getMainLooper())
    private val net = Executors.newSingleThreadExecutor()
    private var link: Films.Link? = null

    private lateinit var rail: ListView
    private lateinit var wall: GridView
    private lateinit var head: TextView
    private lateinit var note: TextView
    private lateinit var detail: FrameLayout

    private var shelves: List<Films.Entry> = emptyList()
    private var search: Map<String, Any?>? = null
    /** the shelf that is shown: what it was asked with, what came of it so far, how many it has in all */
    private var shelfRequest: Map<String, Any?> = emptyMap()
    private var shelfTitle = ""
    private val items = ArrayList<Films.Entry>()
    private var total = 0
    private var more = false
    private var loading = false
    /** every question has a number: an answer that comes after a newer question was asked is dropped */
    private var asked = 0
    /** in the rail: the shelf that is open */
    private var open = -1

    override fun onCreate(b: Bundle?) {
        super.onCreate(b)
        ui = Ui(this); prefs = Prefs(this)
        link = Films.link(prefs.films)
        val root = FrameLayout(this).apply { setBackgroundColor(ui.ink) }

        val left = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(ui.u(18), ui.u(22), ui.u(8), ui.u(14)) }
        left.addView(ui.caps("FILMS AND SERIES", ui.accent))
        rail = ListView(this).apply {
            divider = null; selector = ui.rounded(ui.white(26), ui.u(9)); isVerticalScrollBarEnabled = false
            adapter = railAdapter
            setOnItemClickListener { _, _, i, _ -> pick(i) }
            // (Right from a shelf goes to its posters)
            nextFocusRightId = WALL
        }
        left.addView(rail, LinearLayout.LayoutParams(-1, 0, 1f).apply { topMargin = ui.u(12) })
        root.addView(left, FrameLayout.LayoutParams(ui.u(190), -1))

        val right = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(ui.u(10), ui.u(20), ui.u(18), 0) }
        head = ui.label(15f, Color.WHITE, true)
        right.addView(head)
        wall = GridView(this).apply {
            id = WALL
            numColumns = COLS
            horizontalSpacing = ui.u(10); verticalSpacing = ui.u(12)
            stretchMode = GridView.STRETCH_COLUMN_WIDTH
            selector = ui.rounded(Color.TRANSPARENT, ui.u(8), ui.u(2), ui.accent)
            setDrawSelectorOnTop(true)
            isVerticalScrollBarEnabled = false
            clipToPadding = false; setPadding(ui.u(3), ui.u(4), ui.u(3), ui.u(24))
            adapter = wallAdapter
            setOnItemClickListener { _, _, i, _ -> items.getOrNull(i)?.let { show(it) } }
            onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
                override fun onItemSelected(p: AdapterView<*>?, v: View?, i: Int, id: Long) { if (more && !loading && i >= items.size - COLS * 3) fetchShelf(false) }
                override fun onNothingSelected(p: AdapterView<*>?) {}
            }
        }
        right.addView(wall, LinearLayout.LayoutParams(-1, 0, 1f).apply { topMargin = ui.u(10) })
        root.addView(right, FrameLayout.LayoutParams(-1, -1).apply { leftMargin = ui.u(190) })

        note = ui.label(13f, ui.white(170), lines = 4).apply { gravity = Gravity.CENTER }
        root.addView(note, FrameLayout.LayoutParams(ui.u(420), -2, Gravity.CENTER).apply { leftMargin = ui.u(95) })

        detail = FrameLayout(this).apply { setBackgroundColor(Color.argb(250, 8, 11, 22)); visibility = View.GONE; isClickable = true }
        root.addView(detail, FrameLayout.LayoutParams(-1, -1))
        setContentView(root)

        if (link == null) { say("There is no library link yet. Add one in Settings, under Playlists.") ; return }
        say("Asking the library\u2026")
        ask(emptyMap(), -1, -1) { r ->
            if (r.error.isNotEmpty()) { say(r.error); return@ask }
            shelves = r.entry?.parts?.filter { it.kind == Films.Kind.SHELF } ?: emptyList()
            search = r.search
            railAdapter.notifyDataSetChanged()
            if (shelves.isEmpty()) { say("The library has nothing to show."); return@ask }
            rail.requestFocus()
            (if (search != null) 1 else 0).let { rail.setSelection(it); pick(it) }
        }
    }

    override fun onDestroy() { asked++; net.shutdownNow(); super.onDestroy() }

    private fun say(text: String) { note.text = text; note.visibility = if (text.isEmpty()) View.GONE else View.VISIBLE }

    // ---- asking

    /** sends a question; [done] gets the answer on the main thread, unless a newer question was asked meanwhile */
    private fun ask(request: Map<String, Any?>, offset: Int, limit: Int, keep: Boolean = false, done: (Films.Reply) -> Unit) {
        val l = link ?: return
        val n = if (keep) asked else ++asked
        val body = Films.body(l.key, request, offset, limit)
        net.execute {
            val r = try {
                val c = URL(l.address).openConnection() as HttpURLConnection
                c.connectTimeout = 10_000; c.readTimeout = 20_000
                c.requestMethod = "POST"; c.doOutput = true
                c.setRequestProperty("Content-Type", "application/json")
                c.outputStream.use { it.write(body.toByteArray()) }
                val code = c.responseCode
                if (code !in 200..299) Films.Reply(null, 0, null, "The library answered with an error ($code)")
                else Films.reply(c.inputStream.bufferedReader().use { it.readText() })
            } catch (e: Exception) {
                Log.w(tag, "films: ${e.javaClass.simpleName}")
                Films.Reply(null, 0, null, "The library could not be reached")
            }
            main.post { if (n == asked && !isFinishing) done(r) }
        }
    }

    // ---- the rail of shelves

    private fun railCount() = shelves.size + (if (search != null) 1 else 0)
    private fun railTitle(i: Int): String = if (search != null && i == 0) "\u2315  Search" else shelves[i - (if (search != null) 1 else 0)].title.replaceFirstChar { it.uppercase() }

    private val railAdapter = object : BaseAdapter() {
        override fun getCount() = railCount()
        override fun getItem(i: Int) = i
        override fun getItemId(i: Int) = i.toLong()
        override fun getView(i: Int, old: View?, parent: ViewGroup): View {
            val v = (old as? TextView) ?: ui.label(13.5f, Color.WHITE).apply { setPadding(ui.u(12), ui.u(8.5f), ui.u(10), ui.u(9)) }
            v.text = railTitle(i)
            v.setTextColor(if (i == open) ui.accent else Color.WHITE)
            return v
        }
    }

    private fun pick(i: Int) {
        val s = search
        if (s != null && i == 0) {
            val sh = Sheet(ui, "Search", "A film or a series, by its name.")
            val e = sh.input("", "Name", InputType.TYPE_CLASS_TEXT)
            sh.button("Cancel").button("Search", primary = true) {
                val words = e.text.toString().trim()
                if (words.isNotEmpty()) { open = 0; railAdapter.notifyDataSetChanged(); openShelf(Films.searching(s, words), "Search: $words") }
            }.show { rail.requestFocus() }
            return
        }
        val shelf = shelves[i - (if (s != null) 1 else 0)]
        open = i; railAdapter.notifyDataSetChanged()
        openShelf(shelf.request, shelf.title.replaceFirstChar { it.uppercase() })
    }

    // ---- the wall of posters

    private fun openShelf(request: Map<String, Any?>, title: String) {
        shelfRequest = request; shelfTitle = title
        items.clear(); total = 0; more = false; loading = false
        wallAdapter.notifyDataSetChanged()
        head.text = title
        fetchShelf(true)
    }

    private fun fetchShelf(first: Boolean) {
        loading = true
        if (first) say("Asking the library\u2026")
        // (a shelf that is asked for in pieces says so by having "offset" in its request; a search comes whole)
        val paged = shelfRequest.containsKey("offset")
        ask(shelfRequest, if (paged) items.size else -1, if (paged) PAGE else -1, keep = !first) { r ->
            loading = false
            if (r.error.isNotEmpty()) { if (first) say(r.error); more = false; return@ask }
            val got = r.entry?.parts?.filter { it.kind != Films.Kind.SHELF } ?: emptyList()
            items.addAll(got)
            total = maxOf(r.count, items.size)
            more = paged && got.isNotEmpty() && items.size < total
            wallAdapter.notifyDataSetChanged()
            head.text = shelfTitle + (if (total > 0) "   \u00B7   " + "%,d".format(total) else "")
            say(if (items.isEmpty()) "Nothing here." else "")
            if (first && items.isNotEmpty()) wall.setSelection(0)
        }
    }

    private inner class Tile(val box: LinearLayout, val poster: ImageView, val name: TextView, val sub: TextView)

    private val wallAdapter = object : BaseAdapter() {
        override fun getCount() = items.size
        override fun getItem(i: Int) = items[i]
        override fun getItemId(i: Int) = i.toLong()
        override fun getView(i: Int, old: View?, parent: ViewGroup): View {
            val t = (old?.tag as? Tile) ?: run {
                val box = LinearLayout(this@FilmsActivity).apply { orientation = LinearLayout.VERTICAL; setPadding(ui.u(3), ui.u(3), ui.u(3), ui.u(3)) }
                val poster = ImageView(this@FilmsActivity).apply { scaleType = ImageView.ScaleType.CENTER_CROP; background = ui.rounded(ui.white(14), ui.u(6)); clipToOutline = true }
                box.addView(poster, LinearLayout.LayoutParams(-1, ui.u(POSTER_H)))
                val name = ui.label(11f, Color.WHITE, true)
                box.addView(name, LinearLayout.LayoutParams(-1, -2).apply { topMargin = ui.u(5) })
                val sub = ui.label(9.5f, ui.white(130))
                box.addView(sub, LinearLayout.LayoutParams(-1, -2).apply { topMargin = ui.u(1) })
                Tile(box, poster, name, sub).also { box.tag = it }
            }
            val e = items[i]
            t.name.text = e.title
            t.sub.text = listOfNotNull(e.year.takeIf { it > 0 }?.toString(), if (e.kind == Films.Kind.SERIES) "Series" else null, if (e.uhd) "4K" else null).joinToString("  \u00B7  ")
            Images.show(e.small, t.poster, ui.u(POSTER_H))
            return t.box
        }
    }

    // ---- the page of one film or series

    private var shown: Films.Entry? = null
    private var back: View? = null

    private fun show(e: Films.Entry) {
        back = currentFocus
        shown = e
        draw(e, null)
        detail.visibility = View.VISIBLE
        // the film's addresses, or the series' episodes, are asked for now
        ask(e.request, -1, -1) { r ->
            if (shown !== e) return@ask
            if (r.error.isNotEmpty() || r.entry == null) draw(e, r.error.ifEmpty { "The library gave nothing for this one." }) else draw(e, null, r.entry)
        }
    }

    private fun hide() {
        asked++
        shown = null
        detail.visibility = View.GONE; detail.removeAllViews()
        (back ?: wall).requestFocus()
    }

    /** [full]: what the library said when asked for this one (null while that is awaited); [trouble]: why there is none */
    private fun draw(e: Films.Entry, trouble: String?, full: Films.Entry? = null) {
        detail.removeAllViews()
        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; setPadding(ui.u(44), ui.u(40), ui.u(44), ui.u(24)) }
        val poster = ImageView(this).apply { scaleType = ImageView.ScaleType.CENTER_CROP; background = ui.rounded(ui.white(14), ui.u(10)); clipToOutline = true }
        Images.show(e.poster.ifEmpty { e.small }, poster, ui.u(330))
        row.addView(poster, LinearLayout.LayoutParams(ui.u(220), ui.u(330)))
        val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        row.addView(col, LinearLayout.LayoutParams(0, -1, 1f).apply { leftMargin = ui.u(30) })
        col.addView(ui.label(24f, Color.WHITE, true, lines = 2).apply { text = e.title })
        val facts = e.facts()
        if (facts.isNotEmpty()) col.addView(ui.label(12.5f, ui.accent, true).apply { text = facts }, LinearLayout.LayoutParams(-1, -2).apply { topMargin = ui.u(6) })
        if (e.about.isNotEmpty()) col.addView(ui.label(12.5f, ui.white(190), lines = if (e.kind == Films.Kind.SERIES) 4 else 9).apply { text = e.about; setLineSpacing(ui.u(3).toFloat(), 1f) },
            LinearLayout.LayoutParams(-1, -2).apply { topMargin = ui.u(12) })
        detail.addView(row, FrameLayout.LayoutParams(-1, -1))
        if (trouble != null) { col.addView(ui.label(13f, ui.white(170), lines = 3).apply { text = trouble }, LinearLayout.LayoutParams(-1, -2).apply { topMargin = ui.u(18) }); return }
        if (full == null) { col.addView(ui.label(13f, ui.white(150)).apply { text = "Asking the library\u2026" }, LinearLayout.LayoutParams(-1, -2).apply { topMargin = ui.u(18) }); return }

        if (full.kind == Films.Kind.SERIES) {
            col.addView(ui.caps("EPISODES  \u00B7  ${full.parts.size}", ui.white(150)), LinearLayout.LayoutParams(-1, -2).apply { topMargin = ui.u(16) })
            val list = ListView(this).apply {
                divider = null; selector = ui.rounded(ui.white(26), ui.u(9)); isVerticalScrollBarEnabled = false
                adapter = object : BaseAdapter() {
                    override fun getCount() = full.parts.size
                    override fun getItem(i: Int) = full.parts[i]
                    override fun getItemId(i: Int) = i.toLong()
                    override fun getView(i: Int, old: View?, parent: ViewGroup): View {
                        val v = (old as? TextView) ?: ui.label(13.5f, Color.WHITE).apply { setPadding(ui.u(12), ui.u(8.5f), ui.u(10), ui.u(9)) }
                        val p = full.parts[i]
                        val at = prefs.filmAt(p.id)
                        v.text = p.title + (if (at > 0L) "      left at " + clock(at) else "")
                        return v
                    }
                }
                setOnItemClickListener { _, _, i, _ -> episode(e, full.parts[i]) }
            }
            col.addView(list, LinearLayout.LayoutParams(-1, 0, 1f).apply { topMargin = ui.u(6) })
            // (the episode that was left, else the first)
            val at = full.parts.indexOfFirst { prefs.filmAt(it.id) > 0L }.coerceAtLeast(0)
            list.requestFocus(); list.setSelection(at)
            return
        }

        val bar = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        col.addView(bar, LinearLayout.LayoutParams(-2, -2).apply { topMargin = ui.u(20) })
        val at = prefs.filmAt(e.id)
        var first: View? = null
        fun button(text: String, act: () -> Unit): TextView {
            val v = ui.label(13.5f, Color.WHITE, true).apply {
                this.text = text
                isFocusable = true; isClickable = true
                setPadding(ui.u(18), ui.u(9), ui.u(18), ui.u(10))
                fun paint(has: Boolean) { background = ui.rounded(if (has) ui.accent else ui.white(22), ui.u(18)); setTextColor(if (has) ui.ink else Color.WHITE) }
                paint(false)
                setOnFocusChangeListener { _, has -> paint(has) }
                setOnClickListener { act() }
            }
            bar.addView(v, LinearLayout.LayoutParams(-2, -2).apply { rightMargin = ui.u(10) })
            if (first == null) first = v
            return v
        }
        if (at > 0L) button("\u25B6  Go on from " + clock(at)) { play(e, full, at) }
        button(if (at > 0L) "From the start" else "\u25B6  Play") { play(e, full, 0L) }
        if (full.variants.size > 1) {
            lateinit var q: TextView
            fun name() = "Quality   " + prefs.filmHeight.let { h -> if (h.isEmpty() || full.variants.none { it.first == h }) "Automatic" else h + "p" }
            q = button(name()) {
                val all = listOf("") + full.variants.map { it.first }
                prefs.filmHeight = all[(all.indexOf(prefs.filmHeight).coerceAtLeast(0) + 1) % all.size]
                q.text = name()
            }
        }
        first?.requestFocus()
    }

    private fun clock(ms: Long): String { val s = ms / 1000; return if (s >= 3600) "%d:%02d:%02d".format(s / 3600, s / 60 % 60, s % 60) else "%d:%02d".format(s / 60, s % 60) }

    /** an episode's addresses come with the series; its qualities would have to be asked for one by one, so it plays as the stream chooses */
    private fun episode(series: Films.Entry, p: Films.Entry) {
        if (p.address.isEmpty()) return
        start(series.title + "  \u00B7  " + p.title, p.address, p.id, prefs.filmAt(p.id))
    }

    private fun play(e: Films.Entry, full: Films.Entry, at: Long) {
        val address = full.variants.firstOrNull { it.first == prefs.filmHeight }?.second ?: full.address.ifEmpty { full.variants.firstOrNull()?.second.orEmpty() }
        if (address.isEmpty()) return
        start(e.title, address, e.id, at)
    }

    private fun start(title: String, address: String, id: String, at: Long) {
        startActivity(Intent(this, FilmPlayActivity::class.java).putExtra("title", title).putExtra("address", address).putExtra("id", id).putExtra("at", at))
    }

    override fun onResume() {
        super.onResume()
        // back from a film: where it was left shows on its page
        val e = shown ?: return
        if (detail.visibility == View.VISIBLE) { val from = back; show(e); back = from }
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        if (keyCode == KeyEvent.KEYCODE_BACK) {
            if (detail.visibility == View.VISIBLE) { hide(); return true }
            if (wall.hasFocus()) { rail.requestFocus(); return true }
        }
        // (Left from the first poster of a row goes to the shelves)
        if (keyCode == KeyEvent.KEYCODE_DPAD_LEFT && detail.visibility != View.VISIBLE && wall.hasFocus() && wall.selectedItemPosition % COLS == 0) { rail.requestFocus(); return true }
        return super.onKeyDown(keyCode, event)
    }

    private companion object {
        const val COLS = 6
        const val PAGE = 60
        const val POSTER_H = 168
        val WALL = View.generateViewId()
    }
}
