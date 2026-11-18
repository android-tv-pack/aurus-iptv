package app.hermes.channels

import android.app.Activity
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
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
import android.widget.HorizontalScrollView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.TextView
import app.hermes.channels.core.Films
import java.net.HttpURLConnection
import java.net.URL
import java.util.Calendar
import java.util.concurrent.Executors

/**
 * Films and series. At the left what there is to choose from (what was begun, a search, the library by kind and year,
 * a film picked by chance, the library's shelves); at the right a wall of posters, with a few words about the one in
 * focus above it and its colours spread softly over the whole screen; and a page for one film or series.
 * Nothing is kept of the library itself: every screen is asked for when it is opened.
 */
class FilmsActivity : Activity() {
    private val tag = "Channels"
    private lateinit var ui: Ui
    private lateinit var prefs: Prefs
    private val main = Handler(Looper.getMainLooper())
    private val net = Executors.newSingleThreadExecutor()
    private var link: Films.Link? = null

    private lateinit var glowA: ImageView
    private lateinit var glowB: ImageView
    private lateinit var rail: ListView
    private lateinit var wall: GridView
    private lateinit var shelfName: TextView
    private lateinit var name: TextView
    private lateinit var facts: TextView
    private lateinit var about: TextView
    private lateinit var chips: LinearLayout
    private lateinit var note: TextView
    private lateinit var detail: FrameLayout

    private var shelves: List<Films.Entry> = emptyList()
    private var search: Map<String, Any?>? = null
    private var filters: List<Films.Filter> = emptyList()
    /** of each way to narrow down, the choice made (-1 = any) */
    private var chosen = IntArray(0)

    /** what the rail offers: a name, and what OK on it does */
    private class Offer(val name: String, val act: () -> Unit)
    private var offers: List<Offer> = emptyList()
    /** in the rail: the one that is open */
    private var open = -1

    /** the wall: what it was asked with, what came of it so far, how many there are in all */
    private var shelfRequest: Map<String, Any?> = emptyMap()
    private var shelfTitle = ""
    private var paged = false
    private val items = ArrayList<Films.Entry>()
    private var total = 0
    private var more = false
    private var loading = false
    /** every question has a number: an answer that comes after a newer question was asked is dropped */
    private var asked = 0

    override fun onCreate(b: Bundle?) {
        super.onCreate(b)
        ui = Ui(this); prefs = Prefs(this)
        link = Films.link(prefs.films)
        val root = FrameLayout(this).apply { setBackgroundColor(ui.ink) }

        // the colours of the poster in focus, spread over the screen and dimmed: two pictures, one fading into the other
        glowA = ImageView(this).apply { scaleType = ImageView.ScaleType.CENTER_CROP; alpha = 0f }
        glowB = ImageView(this).apply { scaleType = ImageView.ScaleType.CENTER_CROP; alpha = 0f }
        root.addView(glowA, FrameLayout.LayoutParams(-1, -1)); root.addView(glowB, FrameLayout.LayoutParams(-1, -1))
        root.addView(View(this).apply {
            background = GradientDrawable(GradientDrawable.Orientation.LEFT_RIGHT, intArrayOf(Color.argb(245, 8, 11, 22), Color.argb(150, 8, 11, 22), Color.argb(105, 8, 11, 22)))
        }, FrameLayout.LayoutParams(-1, -1))
        root.addView(View(this).apply {
            background = GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, intArrayOf(Color.argb(0, 8, 11, 22), Color.argb(40, 8, 11, 22), Color.argb(215, 8, 11, 22)))
        }, FrameLayout.LayoutParams(-1, -1))

        val left = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(ui.u(20), ui.u(24), ui.u(8), ui.u(14)) }
        left.addView(ui.label(17f, ui.fg, true).apply { text = greeting() })
        left.addView(ui.label(11f, ui.white(140)).apply { text = "What shall we watch?" }, LinearLayout.LayoutParams(-1, -2).apply { topMargin = ui.u(2) })
        rail = ListView(this).apply {
            divider = null; selector = ui.rounded(ui.white(30), ui.u(10)); isVerticalScrollBarEnabled = false
            adapter = railAdapter
            setOnItemClickListener { _, _, i, _ -> offers.getOrNull(i)?.act?.invoke() }
            nextFocusRightId = WALL
        }
        left.addView(rail, LinearLayout.LayoutParams(-1, 0, 1f).apply { topMargin = ui.u(16) })
        root.addView(left, FrameLayout.LayoutParams(ui.u(RAIL), -1))

        val right = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(ui.u(12), ui.u(22), ui.u(20), 0) }
        // a few words about the film in focus
        shelfName = ui.caps("", ui.accent)
        right.addView(shelfName)
        name = ui.label(22f, ui.fg, true)
        right.addView(name, LinearLayout.LayoutParams(-1, -2).apply { topMargin = ui.u(5) })
        facts = ui.label(11.5f, ui.white(200), true)
        right.addView(facts, LinearLayout.LayoutParams(-1, ui.u(17)).apply { topMargin = ui.u(3) })
        about = ui.label(11.5f, ui.white(165), lines = 2).apply { setLineSpacing(ui.u(2).toFloat(), 1f) }
        right.addView(about, LinearLayout.LayoutParams(ui.u(560), ui.u(40)).apply { topMargin = ui.u(3) })
        chips = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; visibility = View.GONE }
        right.addView(chips, LinearLayout.LayoutParams(-1, -2).apply { topMargin = ui.u(6) })
        wall = GridView(this).apply {
            id = WALL
            // (Right from the last poster of a row stays there: it would leave the wall for whatever lies above it)
            nextFocusRightId = WALL
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
                override fun onItemSelected(p: AdapterView<*>?, v: View?, i: Int, id: Long) {
                    items.getOrNull(i)?.let { tell(it) }
                    if (more && !loading && i >= items.size - COLS * 3) fetch(false)
                }
                override fun onNothingSelected(p: AdapterView<*>?) {}
            }
        }
        right.addView(wall, LinearLayout.LayoutParams(-1, 0, 1f).apply { topMargin = ui.u(8) })
        root.addView(right, FrameLayout.LayoutParams(-1, -1).apply { leftMargin = ui.u(RAIL) })

        note = ui.label(13f, ui.white(170), lines = 4).apply { gravity = Gravity.CENTER }
        root.addView(note, FrameLayout.LayoutParams(ui.u(420), -2, Gravity.CENTER).apply { leftMargin = ui.u(RAIL / 2); topMargin = ui.u(40) })

        detail = FrameLayout(this).apply { visibility = View.GONE; isClickable = true }
        root.addView(detail, FrameLayout.LayoutParams(-1, -1))
        setContentView(root)

        if (link == null) { say("There is no library link yet. Add one in Settings, under Playlists."); return }
        say("Asking the library\u2026")
        ask(emptyMap(), -1, -1) { r ->
            if (r.error.isNotEmpty()) { say(r.error); return@ask }
            shelves = r.entry?.parts?.filter { it.kind == Films.Kind.SHELF } ?: emptyList()
            search = r.search; filters = r.filters; chosen = IntArray(filters.size) { -1 }
            if (shelves.isEmpty()) { say("The library has nothing to show."); return@ask }
            setOffers()
            rail.requestFocus()
            // what was begun comes first when there is any, else the library's first shelf
            val at = if (begun().isNotEmpty()) 0 else offers.indexOfFirst { it.name == nice(shelves[0].title) }.coerceAtLeast(0)
            // (the rail stays at its top, so that what comes before the shelves is seen)
            rail.setSelection(0); rail.post { rail.setSelectionFromTop(at, rail.getChildAt(0)?.height?.times(at) ?: 0) }
            offers[at].act()
        }
    }

    override fun onDestroy() { asked++; net.shutdownNow(); super.onDestroy() }

    private fun greeting(): String = when (Calendar.getInstance().get(Calendar.HOUR_OF_DAY)) {
        in 5..11 -> "Good morning"; in 12..17 -> "Good afternoon"; in 18..22 -> "Good evening"; else -> "A late one?"
    }
    private fun nice(s: String) = s.replaceFirstChar { it.uppercase() }
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

    // ---- the rail

    /** the films and series that were begun and not seen to the end */
    private fun begun(): List<Films.Entry> = prefs.filmsBegun.mapNotNull { Films.kept(it) }.filter { it.kind == Films.Kind.SERIES || prefs.filmAt(it.id) > 0L }

    private fun setOffers() {
        val out = ArrayList<Offer>()
        fun add(name: String, act: (Int) -> Unit) { val i = out.size; out += Offer(name) { act(i) } }
        if (begun().isNotEmpty()) add("\u25B6  Go on watching") { i -> mark(i); narrowing(false); put("Go on watching", begun()) }
        search?.let { s -> add("\u2315  Search") { i -> find(s, i) } }
        if (filters.isNotEmpty()) add("\u2728  By mood") { i -> mark(i); narrowing(true); narrow() }
        add("\uD83C\uDFB2  Surprise me") { _ -> surprise() }
        for (sh in shelves) add(nice(sh.title)) { i -> mark(i); narrowing(false); openShelf(sh.request, nice(sh.title), sh.request.containsKey("offset")) }
        offers = out
        railAdapter.notifyDataSetChanged()
    }
    private fun mark(i: Int) { open = i; railAdapter.notifyDataSetChanged() }

    private val railAdapter = object : BaseAdapter() {
        override fun getCount() = offers.size
        override fun getItem(i: Int) = i
        override fun getItemId(i: Int) = i.toLong()
        override fun getView(i: Int, old: View?, parent: ViewGroup): View {
            val v = (old as? TextView) ?: ui.label(13.5f, ui.fg).apply { setPadding(ui.u(12), ui.u(8.5f), ui.u(10), ui.u(9)) }
            v.text = offers[i].name
            v.setTextColor(if (i == open) ui.accent else ui.fg)
            return v
        }
    }

    private fun find(s: Map<String, Any?>, at: Int) {
        val sh = Sheet(ui, "Search", "A film or a series, by its name.")
        val e = sh.input("", "Name", InputType.TYPE_CLASS_TEXT)
        sh.button("Cancel").button("Search", primary = true) {
            val words = e.text.toString().trim()
            if (words.isNotEmpty()) { mark(at); narrowing(false); openShelf(Films.searching(s, words), "Search  \u00B7  $words", false) }
        }.show { rail.requestFocus() }
    }

    /** one film from somewhere in the library's first shelf, opened as if it had been chosen */
    private fun surprise() {
        val sh = shelves.firstOrNull { it.request.containsKey("offset") } ?: return
        say("Picking one\u2026")
        ask(sh.request, 0, 1) { r ->
            val n = r.count
            if (r.error.isNotEmpty() || n <= 0) { say(""); return@ask }
            ask(sh.request, (Math.random() * n).toInt().coerceIn(0, n - 1), 1) { r2 ->
                say(if (items.isEmpty()) "Nothing here." else "")
                r2.entry?.parts?.firstOrNull { it.kind != Films.Kind.SHELF }?.let { show(it) }
            }
        }
    }

    // ---- by mood: the library narrowed down by the ways it offers (kind of film, years)

    private fun narrowing(on: Boolean) {
        chips.visibility = if (on) View.VISIBLE else View.GONE
        if (!on || chips.childCount > 0) return
        for ((f, filter) in filters.withIndex()) {
            val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
            row.addView(ui.caps(filter.title.uppercase(), ui.white(120)), LinearLayout.LayoutParams(ui.u(48), -2))
            val line = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; setPadding(0, ui.u(3), ui.u(40), ui.u(3)) }
            val views = ArrayList<TextView>()
            fun paint() { for ((i, v) in views.withIndex()) { val on2 = chosen[f] == i - 1
                v.background = ui.rounded(if (v.isFocused) ui.accent else if (on2) ui.white(54) else ui.white(16), ui.u(13), if (on2 && !v.isFocused) ui.u(1) else 0, ui.accent)
                v.setTextColor(if (v.isFocused) ui.ink else ui.fg) } }
            for (i in -1 until filter.choices.size) {
                val v = ui.label(11.5f, ui.fg, true).apply {
                    text = if (i < 0) "Any" else nice(filter.choices[i].first)
                    isFocusable = true; isClickable = true
                    setPadding(ui.u(11), ui.u(4.5f), ui.u(11), ui.u(5))
                    setOnFocusChangeListener { _, _ -> paint() }
                    setOnClickListener { chosen[f] = i; paint(); narrow() }
                    nextFocusDownId = if (f == filters.size - 1) WALL else View.NO_ID
                }
                views += v
                line.addView(v, LinearLayout.LayoutParams(-2, -2).apply { rightMargin = ui.u(6) })
            }
            paint()
            row.addView(HorizontalScrollView(this).apply { isHorizontalScrollBarEnabled = false; addView(line) }, LinearLayout.LayoutParams(0, -2, 1f))
            chips.addView(row, LinearLayout.LayoutParams(-1, -2).apply { topMargin = ui.u(2) })
        }
    }

    private fun narrow() {
        val picked = filters.indices.filter { chosen[it] >= 0 }
        if (picked.isEmpty()) {
            asked++
            items.clear(); total = 0; more = false; loading = false; wallAdapter.notifyDataSetChanged()
            shelfName.text = "BY MOOD"; tell(null)
            say("Choose a kind of film, or the years it is from. Both together work as well.")
            return
        }
        val title = picked.joinToString("  \u00B7  ") { nice(filters[it].choices[chosen[it]].first) }
        openShelf(Films.narrowed(picked.map { filters[it].choices[chosen[it]].second }), title, true)
    }

    // ---- the wall of posters

    private fun openShelf(request: Map<String, Any?>, title: String, inPieces: Boolean) {
        shelfRequest = request; shelfTitle = title; paged = inPieces
        items.clear(); total = 0; more = false; loading = false
        wallAdapter.notifyDataSetChanged()
        shelfName.text = title.uppercase(); tell(null)
        fetch(true)
    }

    /** a wall that is not asked of the library (what was begun) */
    private fun put(title: String, list: List<Films.Entry>) {
        asked++
        shelfTitle = title; paged = false
        items.clear(); items.addAll(list); total = list.size; more = false; loading = false
        wallAdapter.notifyDataSetChanged()
        shelfName.text = title.uppercase()
        say(if (list.isEmpty()) "Nothing here." else "")
        if (list.isNotEmpty()) { wall.setSelection(0); tell(list[0]) }
    }

    private fun fetch(first: Boolean) {
        loading = true
        if (first) say("Asking the library\u2026")
        ask(shelfRequest, if (paged) items.size else -1, if (paged) PAGE else -1, keep = !first) { r ->
            loading = false
            if (r.error.isNotEmpty()) { if (first) say(r.error); more = false; return@ask }
            val got = r.entry?.parts?.filter { it.kind != Films.Kind.SHELF } ?: emptyList()
            // (asked for sixty, a library may send one more: the same one comes first in the next piece)
            val fresh = got.filter { g -> items.none { it.id == g.id && g.id.isNotEmpty() } }
            items.addAll(fresh)
            total = maxOf(r.count, items.size)
            more = paged && fresh.isNotEmpty() && items.size < total
            wallAdapter.notifyDataSetChanged()
            shelfName.text = shelfTitle.uppercase() + (if (total > 0) "   \u00B7   " + "%,d".format(total) else "")
            say(if (items.isEmpty()) "Nothing here." else "")
            if (first && items.isNotEmpty()) { wall.setSelection(0); tell(items[0]) }
        }
    }

    /** the words above the wall are about [e], and the screen takes its colours */
    private fun tell(e: Films.Entry?) {
        name.text = e?.title.orEmpty()
        facts.text = e?.let { (if (it.kind == Films.Kind.SERIES) "Series  \u00B7  " else "") + it.facts() }.orEmpty()
        about.text = e?.about.orEmpty()
        main.removeCallbacks(glow)
        wanted = e?.small.orEmpty()
        // (a moment after the focus rests: not for every poster it passes)
        if (wanted.isNotEmpty()) main.postDelayed(glow, 260)
    }
    private var wanted = ""
    private var glowing = ""
    private var front = false
    private val glow = Runnable {
        val url = wanted
        if (url == glowing) return@Runnable
        Images.get(url, ui.u(POSTER_H)) { b ->
            if (b == null || url != wanted || isFinishing) return@get
            glowing = url
            // (made very small and drawn large again: only its colours are left)
            val soft = try { Bitmap.createScaledBitmap(b, 12, 18, true) } catch (_: Exception) { return@get }
            val up = if (front) glowA else glowB; val down = if (front) glowB else glowA
            front = !front
            up.setImageBitmap(soft)
            up.animate().cancel(); down.animate().cancel()
            up.animate().alpha(0.9f).setDuration(600).start()
            down.animate().alpha(0f).setDuration(600).start()
        }
    }

    private inner class Tile(val box: LinearLayout, val poster: ImageView, val name: TextView, val sub: TextView, val left: View)

    private val wallAdapter = object : BaseAdapter() {
        override fun getCount() = items.size
        override fun getItem(i: Int) = items[i]
        override fun getItemId(i: Int) = i.toLong()
        override fun getView(i: Int, old: View?, parent: ViewGroup): View {
            val t = (old?.tag as? Tile) ?: run {
                val box = LinearLayout(this@FilmsActivity).apply { orientation = LinearLayout.VERTICAL; setPadding(ui.u(3), ui.u(3), ui.u(3), ui.u(3)) }
                val frame = FrameLayout(this@FilmsActivity)
                val poster = ImageView(this@FilmsActivity).apply { scaleType = ImageView.ScaleType.CENTER_CROP; background = ui.rounded(ui.white(14), ui.u(6)); clipToOutline = true }
                frame.addView(poster, FrameLayout.LayoutParams(-1, -1))
                // a film that was begun: a line under its poster
                val left = View(this@FilmsActivity).apply { background = ui.rounded(ui.accent, ui.u(2)) }
                frame.addView(left, FrameLayout.LayoutParams(ui.u(40), ui.u(3), Gravity.BOTTOM or Gravity.START).apply { leftMargin = ui.u(6); bottomMargin = ui.u(6) })
                box.addView(frame, LinearLayout.LayoutParams(-1, ui.u(POSTER_H)))
                val name = ui.label(11f, ui.fg, true)
                box.addView(name, LinearLayout.LayoutParams(-1, -2).apply { topMargin = ui.u(5) })
                val sub = ui.label(9.5f, ui.white(130))
                box.addView(sub, LinearLayout.LayoutParams(-1, -2).apply { topMargin = ui.u(1) })
                Tile(box, poster, name, sub, left).also { box.tag = it }
            }
            val e = items[i]
            t.name.text = e.title
            t.sub.text = listOfNotNull(e.year.takeIf { it > 0 }?.toString(), if (e.kind == Films.Kind.SERIES) "Series" else null, if (e.uhd) "4K" else null).joinToString("  \u00B7  ")
            t.left.visibility = if (e.kind == Films.Kind.FILM && prefs.filmAt(e.id) > 0L) View.VISIBLE else View.GONE
            Images.show(e.small, t.poster, ui.u(POSTER_H))
            return t.box
        }
    }

    // ---- the page of one film or series

    private var shown: Films.Entry? = null
    private var back: View? = null

    private fun show(e: Films.Entry) {
        val fresh = detail.visibility != View.VISIBLE
        if (fresh) back = currentFocus
        shown = e
        draw(e, null)
        if (fresh) { detail.alpha = 0f; detail.visibility = View.VISIBLE; detail.animate().alpha(1f).setDuration(220).start() }
        // the film's addresses, or the series' episodes, are asked for now
        ask(e.request, -1, -1) { r ->
            if (shown !== e) return@ask
            if (r.error.isNotEmpty() || r.entry == null) draw(e, r.error.ifEmpty { "The library gave nothing for this one." }) else draw(e, null, r.entry)
        }
    }

    private fun hide() {
        asked++
        shown = null
        detail.animate().cancel()
        detail.visibility = View.GONE; detail.removeAllViews()
        // (what was begun may have changed while a film played)
        val had = offers.size
        setOffers()
        if (offers.size != had) {
            val by = offers.size - had
            if (open >= 0) open += by
            rail.setSelection((rail.selectedItemPosition + by).coerceIn(0, offers.size - 1))
        }
        wallAdapter.notifyDataSetChanged(); railAdapter.notifyDataSetChanged()
        (back?.takeIf { it.isAttachedToWindow } ?: wall).requestFocus()
    }

    /** [full]: what the library said when asked for this one (null while that is awaited); [trouble]: why there is none */
    private fun draw(e: Films.Entry, trouble: String?, full: Films.Entry? = null) {
        detail.removeAllViews()
        // the poster's colours behind the page
        val wash = ImageView(this).apply { scaleType = ImageView.ScaleType.CENTER_CROP; setBackgroundColor(ui.ink) }
        detail.addView(wash, FrameLayout.LayoutParams(-1, -1))
        Images.get(e.small, ui.u(POSTER_H)) { b -> if (b != null && shown === e) try { wash.setImageBitmap(Bitmap.createScaledBitmap(b, 12, 18, true)) } catch (_: Exception) {} }
        detail.addView(View(this).apply {
            background = GradientDrawable(GradientDrawable.Orientation.LEFT_RIGHT, intArrayOf(Color.argb(150, 8, 11, 22), Color.argb(215, 8, 11, 22), Color.argb(240, 8, 11, 22)))
        }, FrameLayout.LayoutParams(-1, -1))
        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; setPadding(ui.u(50), ui.u(44), ui.u(50), ui.u(24)) }
        val poster = ImageView(this).apply { scaleType = ImageView.ScaleType.CENTER_CROP; background = ui.rounded(ui.white(14), ui.u(12)); clipToOutline = true; elevation = ui.u(12).toFloat() }
        Images.show(e.poster.ifEmpty { e.small }, poster, ui.u(345))
        row.addView(poster, LinearLayout.LayoutParams(ui.u(230), ui.u(345)))
        val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        row.addView(col, LinearLayout.LayoutParams(0, -1, 1f).apply { leftMargin = ui.u(34) })
        col.addView(ui.caps(if (e.kind == Films.Kind.SERIES) "SERIES" else "FILM", ui.accent))
        col.addView(ui.label(26f, ui.fg, true, lines = 2).apply { text = e.title }, LinearLayout.LayoutParams(-1, -2).apply { topMargin = ui.u(6) })
        val f = (full ?: e).facts().ifEmpty { e.facts() }
        if (f.isNotEmpty()) col.addView(ui.label(12.5f, ui.white(215), true).apply { text = f }, LinearLayout.LayoutParams(-1, -2).apply { topMargin = ui.u(7) })
        val words = e.about.ifEmpty { full?.about.orEmpty() }
        if (words.isNotEmpty()) col.addView(ui.label(13f, ui.white(190), lines = if ((full ?: e).kind == Films.Kind.SERIES) 4 else 9).apply { text = words; setLineSpacing(ui.u(3.5f).toFloat(), 1f) },
            LinearLayout.LayoutParams(ui.u(520), -2).apply { topMargin = ui.u(14) })
        detail.addView(row, FrameLayout.LayoutParams(-1, -1))
        if (trouble != null) { col.addView(ui.label(13f, ui.white(170), lines = 3).apply { text = trouble }, LinearLayout.LayoutParams(-1, -2).apply { topMargin = ui.u(18) }); return }
        if (full == null) { col.addView(ui.label(13f, ui.white(150)).apply { text = "Asking the library\u2026" }, LinearLayout.LayoutParams(-1, -2).apply { topMargin = ui.u(18) }); return }

        if (full.kind == Films.Kind.SERIES) {
            col.addView(ui.caps("EPISODES  \u00B7  ${full.parts.size}", ui.white(150)), LinearLayout.LayoutParams(-1, -2).apply { topMargin = ui.u(16) })
            val list = ListView(this).apply {
                divider = null; selector = ui.rounded(ui.white(30), ui.u(9)); isVerticalScrollBarEnabled = false
                adapter = object : BaseAdapter() {
                    override fun getCount() = full.parts.size
                    override fun getItem(i: Int) = full.parts[i]
                    override fun getItemId(i: Int) = i.toLong()
                    override fun getView(i: Int, old: View?, parent: ViewGroup): View {
                        val v = (old as? TextView) ?: ui.label(13.5f, ui.fg).apply { setPadding(ui.u(12), ui.u(8.5f), ui.u(10), ui.u(9)) }
                        val p = full.parts[i]
                        val at = prefs.filmAt(p.id)
                        v.text = p.title + (if (at > 0L) "      left at " + clock(at) else "")
                        return v
                    }
                }
                setOnItemClickListener { _, _, i, _ -> episodes(e, full, i) }
            }
            col.addView(list, LinearLayout.LayoutParams(ui.u(420), 0, 1f).apply { topMargin = ui.u(6) })
            // (the episode that was left, else the first)
            val at = full.parts.indexOfFirst { prefs.filmAt(it.id) > 0L }.coerceAtLeast(0)
            list.requestFocus(); list.setSelection(at)
            return
        }

        val bar = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        col.addView(bar, LinearLayout.LayoutParams(-2, -2).apply { topMargin = ui.u(22) })
        val at = prefs.filmAt(e.id)
        var first: View? = null
        fun button(text: String, act: () -> Unit): TextView {
            val v = ui.label(13.5f, ui.fg, true).apply {
                this.text = text
                isFocusable = true; isClickable = true
                setPadding(ui.u(18), ui.u(9), ui.u(18), ui.u(10))
                fun paint(has: Boolean) { background = ui.rounded(if (has) ui.accent else ui.white(26), ui.u(18)); setTextColor(if (has) ui.ink else ui.fg) }
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
            fun label() = "Quality   " + prefs.filmHeight.let { h -> if (h.isEmpty() || full.variants.none { it.first == h }) "Automatic" else h + "p" }
            q = button(label()) {
                val all = listOf("") + full.variants.map { it.first }
                prefs.filmHeight = all[(all.indexOf(prefs.filmHeight).coerceAtLeast(0) + 1) % all.size]
                q.text = label()
            }
        }
        if (at > 0L) button("Forget where I was") { prefs.setFilmAt(e.id, 0L); prefs.dropFilmBegun(e.id); draw(e, null, full) }
        first?.requestFocus()
    }

    private fun clock(ms: Long): String { val s = ms / 1000; return if (s >= 3600) "%d:%02d:%02d".format(s / 3600, s / 60 % 60, s % 60) else "%d:%02d".format(s / 60, s % 60) }

    /** from episode [i] on, one after the other (an episode's qualities would have to be asked for one by one: it plays as the stream chooses) */
    private fun episodes(series: Films.Entry, full: Films.Entry, i: Int) {
        val parts = full.parts.filter { it.address.isNotEmpty() }
        val at = parts.indexOf(full.parts[i]); if (at < 0) return
        prefs.noteFilmBegun(series.id, Films.keep(series))
        startActivity(Intent(this, FilmPlayActivity::class.java)
            .putExtra("series", series.title)
            .putExtra("titles", parts.map { it.title }.toTypedArray())
            .putExtra("addresses", parts.map { it.address }.toTypedArray())
            .putExtra("ids", parts.map { it.id }.toTypedArray())
            .putExtra("index", at))
    }

    private fun play(e: Films.Entry, full: Films.Entry, at: Long) {
        val address = full.variants.firstOrNull { it.first == prefs.filmHeight }?.second ?: full.address.ifEmpty { full.variants.firstOrNull()?.second.orEmpty() }
        if (address.isEmpty()) return
        prefs.noteFilmBegun(e.id, Films.keep(e))
        startActivity(Intent(this, FilmPlayActivity::class.java)
            .putExtra("titles", arrayOf(e.title)).putExtra("addresses", arrayOf(address)).putExtra("ids", arrayOf(e.id)).putExtra("index", 0).putExtra("at", at))
    }

    override fun onResume() {
        super.onResume()
        // back from a film: where it was left shows on its page
        val e = shown ?: return
        if (detail.visibility == View.VISIBLE) show(e)
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        if (keyCode == KeyEvent.KEYCODE_BACK) {
            if (detail.visibility == View.VISIBLE) { hide(); return true }
            if (!rail.hasFocus()) { rail.requestFocus(); return true }
        }
        // (Left from the first poster of a row goes to the rail)
        if (keyCode == KeyEvent.KEYCODE_DPAD_LEFT && detail.visibility != View.VISIBLE && wall.hasFocus() && wall.selectedItemPosition % COLS == 0) { rail.requestFocus(); return true }
        return super.onKeyDown(keyCode, event)
    }

    private companion object {
        const val COLS = 6
        const val PAGE = 60
        const val POSTER_H = 150
        const val RAIL = 196
        val WALL = View.generateViewId()
    }
}
