package app.hermes.channels

import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.text.InputType
import android.text.TextUtils
import android.util.Log
import android.util.TypedValue
import android.view.Gravity
import android.view.KeyEvent
import android.view.SurfaceView
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.AdapterView
import android.widget.BaseAdapter
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.TextView
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.Tracks
import androidx.media3.common.VideoSize
import androidx.media3.common.util.Util
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.hls.DefaultHlsExtractorFactory
import androidx.media3.exoplayer.hls.HlsMediaSource
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.exoplayer.source.MediaSource
import androidx.media3.extractor.DefaultExtractorsFactory
import androidx.media3.extractor.ts.DefaultTsPayloadReaderFactory
import app.hermes.channels.core.Guide
import app.hermes.channels.core.Library
import app.hermes.channels.core.M3u
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * The whole app is this one screen: the channel plays full-screen, and everything else comes over it.
 *
 *  - Up / Down (or CH+ / CH-): next / previous channel of the list that was chosen last
 *  - OK or Left: the channels (lists on the left, channels in the middle; Right on a channel shows its programmes,
 *    holding OK makes it a favourite)
 *  - Right: the programmes of the channel that plays
 *  - number keys: the channel with that number
 *  - INFO: what plays now and next
 *
 * The playlist link holds the subscription key: it stays in the app's private storage and is never logged;
 * the log names channels by their number in the list.
 */
class MainActivity : Activity() {

    private val tag = "Channels"
    private val main = Handler(Looper.getMainLooper())
    private lateinit var prefs: Prefs
    private lateinit var root: FrameLayout
    private lateinit var surface: SurfaceView
    private lateinit var note: TextView
    private var player: ExoPlayer? = null

    private var lib: Library? = null
    /** the channel that plays, as its place in the playlist; -1 = none yet */
    private var current = -1
    /** the list Up / Down move in */
    private var scopeName = Library.ALL
    private var scope: List<Int> = emptyList()
    private var favs: MutableSet<String> = HashSet()
    private var recents: List<String> = emptyList()
    private var found = ""
    private var startedAt = 0L
    private var retries = 0
    /** a channel asked for from outside (the home screen's row) before the playlist was ready */
    private var wanted: String? = null
    private var testStream: String? = null

    private val accent = Color.rgb(0, 229, 255)
    private fun white(a: Int) = Color.argb(a, 255, 255, 255)
    private var unit = 2f
    private fun u(n: Float): Int = (n * unit).toInt()
    private fun u(n: Int): Int = u(n.toFloat())
    private val clock = SimpleDateFormat("HH:mm", Locale.US)
    private fun hm(t: Long): String = clock.format(Date(t))

    private fun label(size: Float, color: Int, medium: Boolean = false) = TextView(this).apply {
        setTextSize(TypedValue.COMPLEX_UNIT_PX, u(size).toFloat())
        setTextColor(color)
        maxLines = 1
        ellipsize = TextUtils.TruncateAt.END
        if (medium) typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
    }
    private fun rounded(fill: Int, radius: Int, strokeW: Int = 0, stroke: Int = 0) = GradientDrawable().apply {
        setColor(fill); cornerRadius = radius.toFloat()
        if (strokeW > 0) setStroke(strokeW, stroke)
    }

    /** a thin line that shows how far a programme has run */
    private inner class Bar(ctx: Context) : View(ctx) {
        var f = -1f
            set(v) { field = v; invalidate() }
        private val p = Paint()
        override fun onDraw(c: Canvas) {
            if (f < 0f) return
            p.color = white(50); c.drawRect(0f, 0f, width.toFloat(), height.toFloat(), p)
            p.color = accent; c.drawRect(0f, 0f, width * f, height.toFloat(), p)
        }
    }

    // ---------------------------------------------------------------- life

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        unit = resources.displayMetrics.heightPixels / 540f
        prefs = Prefs(this)
        root = FrameLayout(this).apply { setBackgroundColor(Color.BLACK) }
        surface = SurfaceView(this)
        root.addView(surface, FrameLayout.LayoutParams(-1, -1, Gravity.CENTER))
        buildInfo()
        buildPanel()
        note = label(15f, white(225)).apply { gravity = Gravity.CENTER; maxLines = 4; setPadding(u(80), 0, u(80), 0) }
        root.addView(note, FrameLayout.LayoutParams(-1, -2, Gravity.CENTER))
        digits = label(40f, Color.WHITE, true).apply { setShadowLayer(u(6).toFloat(), 0f, 0f, Color.BLACK); visibility = View.GONE }
        root.addView(digits, FrameLayout.LayoutParams(-2, -2, Gravity.TOP or Gravity.END).apply { setMargins(0, u(28), u(44), 0) })
        setContentView(root)
        takeDropped()
        favs = prefs.favs.toMutableSet()
        recents = prefs.recents
        scopeName = prefs.scope.ifEmpty { Library.ALL }
        read(intent)
    }

    /** dropped into the app's files folder: `playlist.txt` (line 1 the playlist link, line 2 an optional list with logos), `guide.txt` (the guide's link) */
    private fun takeDropped() {
        val dir = getExternalFilesDir(null) ?: return
        fun take(name: String): List<String> {
            val f = File(dir, name)
            if (!f.exists()) return emptyList()
            val lines = try { f.readLines().map { it.trim() }.filter { it.isNotEmpty() } } catch (_: Exception) { emptyList() }
            f.delete()
            return lines
        }
        val list = take("playlist.txt")
        if (list.isNotEmpty()) { prefs.playlist = list[0]; prefs.extra = list.getOrNull(1).orEmpty(); Repo.forget(this); Log.i(tag, "a playlist link was taken") }
        val guide = take("guide.txt")
        if (guide.isNotEmpty()) { prefs.guide = guide[0]; Repo.forget(this); Log.i(tag, "a guide link was taken") }
    }

    /** what the app was started for: a channel of the home screen's row, or (for testing) a number in the list or a stream */
    private fun read(i: Intent) {
        val d = i.data
        if (d != null && d.scheme == "channels" && d.host == "play") wanted = d.lastPathSegment
        if (i.hasExtra("index")) { wanted = "#" + i.getIntExtra("index", 1); i.removeExtra("index") }
        testStream = i.getStringExtra("test")
        i.data = null
    }

    override fun onNewIntent(i: Intent) {
        super.onNewIntent(i)
        setIntent(i)
        read(i)
        if (lib != null) startPlaying()
    }

    override fun onStart() {
        super.onStart()
        val p = ExoPlayer.Builder(this).build()
        p.setVideoSurfaceView(surface)
        p.addListener(listener)
        player = p
        val test = testStream
        if (test != null) {
            lib = Library(listOf(M3u.Channel("Test stream", "", test, "", "")))
            scope = listOf(0); play(0)
            return
        }
        say("Reading the playlist")
        Repo.start(this, false, { onChannels() }, { onGuide() })
        main.postDelayed(tick, 30_000)
    }

    override fun onStop() {
        super.onStop()
        main.removeCallbacksAndMessages(null)
        player?.release()
        player = null
    }

    private fun onChannels() {
        val l = Repo.lib
        if (l == null) {
            say(if (Repo.error == "none") "No playlist yet.\nPress OK and choose Settings to give its link." else "Could not read the playlist.\nPress OK and choose Settings to try again.")
            lib = null
            return
        }
        lib = l
        say("")
        scope = l.list(scopeName, favs, recents).ifEmpty { scopeName = Library.ALL; l.list(Library.ALL, favs, recents) }
        if (player != null) startPlaying()
    }

    private fun startPlaying() {
        val l = lib ?: return
        val w = wanted
        wanted = null
        val i = when {
            w == null -> l.indexOf(prefs.last)
            w.startsWith("#") -> (w.substring(1).toIntOrNull() ?: 1) - 1
            else -> l.indexOf(w)
        }
        if (w != null && current == i && player?.playbackState == Player.STATE_READY) return
        play(if (i in l.channels.indices) i else scope.firstOrNull() ?: 0)
    }

    private fun onGuide() {
        if (infoBar.alpha > 0f) fillInfo()
        if (panel.visibility == View.VISIBLE) { chanAdapter.notifyDataSetChanged() }
    }

    private val tick = object : Runnable {
        override fun run() {
            Repo.keepFresh(this@MainActivity) { onGuide() }
            onGuide()
            main.postDelayed(this, 30_000)
        }
    }

    private fun say(s: String) { note.text = s; note.visibility = if (s.isEmpty()) View.GONE else View.VISIBLE }

    // ---------------------------------------------------------------- playing

    private val http by lazy {
        DefaultHttpDataSource.Factory()
            .setUserAgent("Mozilla/5.0 (Linux; Android) Channels")
            .setAllowCrossProtocolRedirects(true)
            .setConnectTimeoutMs(10000).setReadTimeoutMs(15000)
    }
    // broadcast feeds often have no "IDR" pictures at all: without this their picture never starts
    private val tsFlags = DefaultTsPayloadReaderFactory.FLAG_ALLOW_NON_IDR_KEYFRAMES or DefaultTsPayloadReaderFactory.FLAG_DETECT_ACCESS_UNITS

    private fun source(address: String): MediaSource {
        val item = MediaItem.fromUri(address)
        return if (Util.inferContentType(Uri.parse(address)) == C.CONTENT_TYPE_HLS)
            HlsMediaSource.Factory(http).setExtractorFactory(DefaultHlsExtractorFactory(tsFlags, true)).createMediaSource(item)
        else DefaultMediaSourceFactory(http, DefaultExtractorsFactory().setTsExtractorFlags(tsFlags)).createMediaSource(item)
    }

    /** (adb shell setprop log.tag.Channels DEBUG) what the player is doing, every two seconds */
    private val probe = object : Runnable {
        override fun run() {
            val p = player ?: return
            if (Log.isLoggable(tag, Log.DEBUG)) {
                val v = p.videoDecoderCounters; val a = p.audioDecoderCounters
                Log.d(tag, "#${current + 1} state=${p.playbackState} buffered=${p.totalBufferedDuration}ms " +
                    "video[in=${v?.queuedInputBufferCount} out=${v?.renderedOutputBufferCount} drop=${v?.droppedBufferCount}] audio[out=${a?.renderedOutputBufferCount}]")
            }
            main.postDelayed(this, 2000)
        }
    }

    /** half a minute on a channel is watching it: it joins the recent ones, here and on the TV's home screen */
    private val watched = Runnable {
        val l = lib ?: return@Runnable
        if (current !in l.channels.indices || testStream != null) return@Runnable
        recents = Library.pushed(recents, l.keyOf(current))
        prefs.recents = recents
        val rows = (recents.take(10) + favs.filter { it !in recents.take(10) }).mapNotNull { k ->
            val i = l.indexOf(k)
            if (i < 0) null else k + "\t" + l.channels[i].name.replace('\t', ' ') + "\t" + Repo.logo(i)
        }
        prefs.homeRows = rows
        Home.publish(this, rows)
    }

    private fun play(i: Int, again: Boolean = false) {
        val p = player ?: return
        val l = lib ?: return
        val c = l.channels.getOrNull(i) ?: return
        if (!again) retries = 0
        current = i
        if (testStream == null) prefs.last = l.keyOf(i)
        say("")
        showInfo()
        startedAt = SystemClock.elapsedRealtime()
        Log.i(tag, "play #${i + 1}")
        main.removeCallbacks(probe); main.postDelayed(probe, 2000)
        main.removeCallbacks(watched); main.postDelayed(watched, 30_000)
        p.setMediaSource(source(c.url))
        p.prepare()
        p.playWhenReady = true
    }

    private fun zap(step: Int) {
        val l = lib ?: return
        if (scope.size < 2 && current in scope) return
        play(l.step(scope, current, step))
    }

    private val listener = object : Player.Listener {
        override fun onPlaybackStateChanged(state: Int) {
            val name = when (state) { Player.STATE_BUFFERING -> "buffering"; Player.STATE_READY -> "ready"; Player.STATE_ENDED -> "ended"; else -> "idle" }
            Log.i(tag, "#${current + 1} $name after ${SystemClock.elapsedRealtime() - startedAt} ms")
        }
        override fun onRenderedFirstFrame() { Log.i(tag, "#${current + 1} first picture after ${SystemClock.elapsedRealtime() - startedAt} ms") }
        override fun onVideoSizeChanged(v: VideoSize) { Log.i(tag, "#${current + 1} picture ${v.width}x${v.height}") }
        override fun onTracksChanged(t: Tracks) {
            for (g in t.groups) for (i in 0 until g.length) if (g.isTrackSelected(i)) {
                val f = g.getTrackFormat(i)
                val what = when (g.type) { C.TRACK_TYPE_VIDEO -> "video"; C.TRACK_TYPE_AUDIO -> "audio"; C.TRACK_TYPE_TEXT -> "text"; else -> "other" }
                Log.i(tag, "#${current + 1} $what ${f.sampleMimeType} ${if (f.width > 0) "${f.width}x${f.height}" else ""} ${if (f.channelCount > 0) "${f.channelCount}ch" else ""} ${f.language.orEmpty()} supported=${g.isTrackSupported(i)}")
            }
        }
        override fun onPlayerError(e: PlaybackException) {
            // (the message may hold the stream's address: only the kind of error is logged)
            Log.w(tag, "#${current + 1} error ${e.errorCodeName} (${e.cause?.javaClass?.simpleName})" + (if (retries < 2) ", trying again" else ""))
            // a decoder that stumbles while channels are changed, or a live stream that moved on, plays at the next try
            if (retries < 2) { retries++; main.postDelayed({ play(current, true) }, 300L * retries); return }
            say("This channel does not play right now.")
        }
    }

    // ---------------------------------------------------------------- what plays (the bar at the bottom)

    private lateinit var infoBar: LinearLayout
    private lateinit var infoLogo: ImageView
    private lateinit var infoName: TextView
    private lateinit var infoNow: TextView
    private lateinit var infoNext: TextView
    private lateinit var infoClock: TextView
    private lateinit var infoProg: Bar
    private val hideInfo = Runnable { infoBar.animate().alpha(0f).setDuration(350).start() }

    private fun buildInfo() {
        infoBar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = GradientDrawable(GradientDrawable.Orientation.BOTTOM_TOP, intArrayOf(Color.argb(235, 6, 9, 18), Color.argb(170, 6, 9, 18), Color.TRANSPARENT))
            setPadding(u(44), u(46), u(44), u(26))
            alpha = 0f
        }
        infoLogo = ImageView(this).apply { scaleType = ImageView.ScaleType.FIT_CENTER }
        infoBar.addView(infoLogo, LinearLayout.LayoutParams(u(58), u(58)))
        val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        infoName = label(19f, Color.WHITE, true)
        infoNow = label(13.5f, white(235))
        infoProg = Bar(this)
        infoNext = label(11.5f, white(160))
        col.addView(infoName)
        col.addView(infoNow, LinearLayout.LayoutParams(-1, -2).apply { topMargin = u(2) })
        col.addView(infoProg, LinearLayout.LayoutParams(u(360), maxOf(3, u(2.5f))).apply { topMargin = u(6) })
        col.addView(infoNext, LinearLayout.LayoutParams(-1, -2).apply { topMargin = u(5) })
        infoBar.addView(col, LinearLayout.LayoutParams(0, -2, 1f).apply { leftMargin = u(18) })
        infoClock = label(22f, white(235)).apply { typeface = Typeface.create("sans-serif-light", Typeface.NORMAL) }
        infoBar.addView(infoClock)
        root.addView(infoBar, FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM))
    }

    private fun fillInfo() {
        val l = lib ?: return
        val c = l.channels.getOrNull(current) ?: return
        val now = System.currentTimeMillis()
        infoName.text = "${current + 1}   ${c.name}"
        Images.show(Repo.logo(current), infoLogo, u(58))
        val (cur, next) = Repo.nowNext(current, now)
        infoNow.text = if (cur != null) cur.title + "    " + hm(cur.start) + " \u2013 " + hm(cur.stop) else c.group
        infoProg.f = if (cur != null) Guide.progress(cur, now) else -1f
        infoNext.text = if (next != null) "Next  " + hm(next.start) + "   " + next.title else ""
        infoClock.text = hm(now)
    }

    private fun showInfo(ms: Long = 5000) {
        fillInfo()
        infoBar.animate().cancel(); infoBar.alpha = 1f
        main.removeCallbacks(hideInfo); main.postDelayed(hideInfo, ms)
    }

    // ---------------------------------------------------------------- the channels (lists, channels, programmes)

    private lateinit var panel: LinearLayout
    private lateinit var groupList: ListView
    private lateinit var chanList: ListView
    private lateinit var guideBox: LinearLayout
    private lateinit var guideTitle: TextView
    private lateinit var guideList: ListView
    private lateinit var chanHint: TextView
    /** the entries of the left column: what it is (a list's name, or "search" / "settings") and how it reads */
    private var groupRows: List<Pair<String, String>> = emptyList()
    private var shownName = Library.ALL
    private var shown: List<Int> = emptyList()
    private var progs: List<Guide.Prog> = emptyList()

    private fun list(): ListView = ListView(this).apply {
        divider = null
        isVerticalScrollBarEnabled = false
        overScrollMode = View.OVER_SCROLL_NEVER
        // only the column that has the focus shows where it is
        val on = rounded(white(38), u(9), u(2), accent)
        val off = rounded(Color.TRANSPARENT, 0)
        selector = off
        setOnFocusChangeListener { _, has -> selector = if (has) on else off; invalidateViews() }
        setDrawSelectorOnTop(false)
        clipToPadding = false
        setPadding(0, u(18), 0, u(18))
    }

    private val groupAdapter = object : BaseAdapter() {
        override fun getCount() = groupRows.size
        override fun getItem(p: Int) = groupRows[p]
        override fun getItemId(p: Int) = p.toLong()
        override fun getView(p: Int, old: View?, parent: ViewGroup): View {
            val t = (old as? TextView) ?: label(13.5f, Color.WHITE).apply { setPadding(u(14), u(8), u(10), u(8.5f)) }
            val (id, name) = groupRows[p]
            t.text = name
            // the list whose channels are shown stays marked while the focus is on the channels
            t.setTextColor(if (id == shownName) accent else if (id == "search" || id == "settings") white(170) else Color.WHITE)
            t.typeface = Typeface.create(if (id == shownName) "sans-serif-medium" else "sans-serif", Typeface.NORMAL)
            return t
        }
    }

    private class ChanRow(val num: TextView, val logo: ImageView, val name: TextView, val now: TextView, val bar: View, val star: TextView)

    private val chanAdapter = object : BaseAdapter() {
        override fun getCount() = shown.size
        override fun getItem(p: Int) = shown[p]
        override fun getItemId(p: Int) = shown[p].toLong()
        override fun getView(p: Int, old: View?, parent: ViewGroup): View {
            val row = old ?: LinearLayout(this@MainActivity).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(u(6), u(5), u(10), u(5))
                val num = label(11f, white(120)).apply { gravity = Gravity.END }
                addView(num, LinearLayout.LayoutParams(u(34), -2))
                val logo = ImageView(context).apply { scaleType = ImageView.ScaleType.FIT_CENTER }
                addView(logo, LinearLayout.LayoutParams(u(38), u(38)).apply { leftMargin = u(10) })
                val col = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
                val name = label(14f, Color.WHITE)
                val now = label(10.5f, white(150))
                val bar = Bar(context)
                col.addView(name); col.addView(now)
                col.addView(bar, LinearLayout.LayoutParams(-1, maxOf(2, u(1.5f))).apply { topMargin = u(3) })
                addView(col, LinearLayout.LayoutParams(0, -2, 1f).apply { leftMargin = u(12) })
                val star = label(13f, accent)
                addView(star, LinearLayout.LayoutParams(-2, -2).apply { leftMargin = u(8) })
                tag = ChanRow(num, logo, name, now, bar, star)
            }
            val h = row.tag as ChanRow
            val l = lib ?: return row
            val i = shown[p]
            val c = l.channels[i]
            h.num.text = (i + 1).toString()
            h.name.text = c.name
            h.name.setTextColor(if (i == current) accent else Color.WHITE)
            val t = System.currentTimeMillis()
            val cur = Repo.nowNext(i, t).first
            h.now.text = cur?.title ?: ""
            (h.bar as Bar).f = if (cur != null) Guide.progress(cur, t) else -1f
            h.star.text = if (l.keyOf(i) in favs) "\u2605" else ""
            Images.show(Repo.logo(i), h.logo, u(38))
            return row
        }
    }

    private val guideAdapter = object : BaseAdapter() {
        override fun getCount() = progs.size
        override fun getItem(p: Int) = progs[p]
        override fun getItemId(p: Int) = p.toLong()
        override fun getView(p: Int, old: View?, parent: ViewGroup): View {
            val row = (old as? LinearLayout) ?: LinearLayout(this@MainActivity).apply {
                orientation = LinearLayout.HORIZONTAL
                setPadding(u(10), u(6), u(10), u(6))
                addView(label(12f, white(150)), LinearLayout.LayoutParams(u(46), -2))
                addView(label(12.5f, Color.WHITE).apply { maxLines = 2 }, LinearLayout.LayoutParams(0, -2, 1f))
            }
            val g = progs[p]
            val t = System.currentTimeMillis()
            val on = g.start <= t && t < g.stop
            (row.getChildAt(0) as TextView).apply { text = hm(g.start); setTextColor(if (on) accent else white(150)) }
            (row.getChildAt(1) as TextView).apply { text = g.title; setTextColor(if (on) accent else if (g.stop <= t) white(110) else Color.WHITE) }
            return row
        }
    }

    private fun buildPanel() {
        panel = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            background = GradientDrawable(GradientDrawable.Orientation.LEFT_RIGHT, intArrayOf(Color.argb(244, 6, 9, 18), Color.argb(232, 6, 9, 18), Color.argb(205, 6, 9, 18)))
            setPadding(u(22), 0, u(14), 0)
            visibility = View.GONE
            isClickable = true
        }
        groupList = list().apply { adapter = groupAdapter }
        chanList = list().apply { adapter = chanAdapter }
        guideList = list().apply { adapter = guideAdapter }
        panel.addView(groupList, LinearLayout.LayoutParams(u(176), -1))
        val mid = FrameLayout(this)
        mid.addView(chanList, FrameLayout.LayoutParams(-1, -1))
        chanHint = label(12.5f, white(170)).apply { maxLines = 5; setPadding(u(20), u(30), u(20), 0) }
        mid.addView(chanHint, FrameLayout.LayoutParams(-1, -2))
        panel.addView(mid, LinearLayout.LayoutParams(u(376), -1).apply { leftMargin = u(10) })
        guideBox = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; visibility = View.GONE }
        guideTitle = label(11f, accent, true).apply { letterSpacing = 0.14f; maxLines = 4; setPadding(u(10), u(22), u(10), 0) }
        guideBox.addView(guideTitle)
        guideBox.addView(guideList, LinearLayout.LayoutParams(-1, 0, 1f))
        panel.addView(guideBox, LinearLayout.LayoutParams(u(300), -1).apply { leftMargin = u(10) })
        root.addView(panel, FrameLayout.LayoutParams(-2, -1, Gravity.START))

        groupList.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, v: View?, p: Int, id: Long) { if (groupList.hasFocus()) choose(groupRows[p].first, false) }
            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }
        groupList.setOnItemClickListener { _, _, p, _ ->
            when (val id = groupRows[p].first) {
                "search" -> askSearch()
                "settings" -> askSettings()
                else -> { choose(id, false); if (shown.isNotEmpty()) chanList.requestFocus() }
            }
        }
        groupList.setOnKeyListener { _, k, ev ->
            when (k) {
                KeyEvent.KEYCODE_DPAD_RIGHT -> { if (ev.action == KeyEvent.ACTION_DOWN && shown.isNotEmpty()) chanList.requestFocus(); true }
                KeyEvent.KEYCODE_DPAD_LEFT -> true
                else -> false
            }
        }
        chanList.setOnItemClickListener { _, _, p, _ ->
            val i = shown[p]
            scopeName = shownName; scope = shown; prefs.scope = scopeName
            closePanel()
            if (i != current) play(i) else showInfo()
        }
        chanList.setOnItemLongClickListener { _, _, p, _ ->
            val l = lib ?: return@setOnItemLongClickListener true
            val key = l.keyOf(shown[p])
            if (!favs.remove(key)) favs.add(key)
            prefs.favs = favs
            chanAdapter.notifyDataSetChanged()
            true
        }
        chanList.setOnKeyListener { _, k, ev ->
            when (k) {
                KeyEvent.KEYCODE_DPAD_LEFT -> { if (ev.action == KeyEvent.ACTION_DOWN) { hideGuide(); groupList.requestFocus() }; true }
                KeyEvent.KEYCODE_DPAD_RIGHT -> { if (ev.action == KeyEvent.ACTION_DOWN) shown.getOrNull(chanList.selectedItemPosition)?.let { showGuide(it, true) }; true }
                else -> false
            }
        }
        guideList.setOnKeyListener { _, k, ev ->
            when (k) {
                KeyEvent.KEYCODE_DPAD_LEFT -> { if (ev.action == KeyEvent.ACTION_DOWN) { if (panelFull) { hideGuide(); chanList.requestFocus() } else closePanel() }; true }
                KeyEvent.KEYCODE_DPAD_RIGHT -> true
                else -> false
            }
        }
    }

    /** true = lists and channels are shown; false = only the programmes of the channel that plays */
    private var panelFull = true

    private fun groupsNow(): List<Pair<String, String>> {
        val l = lib
        val out = ArrayList<Pair<String, String>>()
        out += "search" to "Search"
        if (found.isNotEmpty()) out += (Library.FOUND + found) to "\u201C$found\u201D"
        out += Library.FAVS to "Favourites"
        out += Library.RECENT to "Recent"
        out += Library.ALL to "All channels"
        if (l != null) for (g in l.groups) out += g to g
        out += "settings" to "Settings"
        return out
    }

    /** shows the channels of the list [id] in the middle column */
    private fun choose(id: String, focusCurrent: Boolean) {
        val l = lib
        if (id == "search" || id == "settings" || l == null) {
            shownName = id; shown = emptyList()
            chanHint.text = when (id) {
                "search" -> "Press OK to look for a channel by its name."
                "settings" -> "Press OK for the playlist link, the guide link, and fetching both again." + (if (Repo.guideNote.isNotEmpty()) "\n\n" + Repo.guideNote + "." else "")
                else -> ""
            }
        } else {
            shownName = id
            shown = l.list(id, favs, recents)
            chanHint.text = when {
                shown.isNotEmpty() -> ""
                id == Library.FAVS -> "No favourites yet.\nHold OK on a channel to make it one."
                id == Library.RECENT -> "Channels you watch for half a minute are listed here."
                id.startsWith(Library.FOUND) -> "No channel has that in its name."
                else -> ""
            }
        }
        hideGuide()
        chanAdapter.notifyDataSetChanged()
        groupAdapter.notifyDataSetChanged()
        val at = if (focusCurrent) shown.indexOf(current).coerceAtLeast(0) else 0
        chanList.setSelectionFromTop(at, if (focusCurrent) u(200) else 0)
    }

    private fun openPanel() {
        if (testStream != null) return
        panelFull = true
        groupList.visibility = View.VISIBLE
        (chanList.parent as View).visibility = View.VISIBLE
        groupRows = groupsNow()
        groupAdapter.notifyDataSetChanged()
        panel.visibility = View.VISIBLE
        main.removeCallbacks(hideInfo); infoBar.alpha = 0f
        val l = lib
        if (l == null) {             // no playlist: only Settings makes sense
            choose("settings", false)
            groupList.setSelection(groupRows.size - 1)
            groupList.requestFocus()
            return
        }
        val name = if (groupRows.any { it.first == scopeName } && current in l.list(scopeName, favs, recents)) scopeName else Library.ALL
        choose(name, true)
        groupList.setSelection(groupRows.indexOfFirst { it.first == name }.coerceAtLeast(0))
        if (shown.isNotEmpty()) chanList.requestFocus() else groupList.requestFocus()
    }

    private fun closePanel() {
        panel.visibility = View.GONE
        hideGuide()
    }

    private fun hideGuide() { guideBox.visibility = View.GONE; progs = emptyList(); guideAdapter.notifyDataSetChanged() }

    /** the programmes of channel [i], beside the channels ([full]) or alone (for the channel that plays) */
    private fun showGuide(i: Int, full: Boolean) {
        val l = lib ?: return
        if (!full) {
            panelFull = false
            groupList.visibility = View.GONE
            (chanList.parent as View).visibility = View.GONE
            panel.visibility = View.VISIBLE
            main.removeCallbacks(hideInfo); infoBar.alpha = 0f
        }
        val name = l.channels[i].name.uppercase(Locale.getDefault())
        guideTitle.text = name
        guideBox.visibility = View.VISIBLE
        progs = emptyList(); guideAdapter.notifyDataSetChanged()
        if (!full) guideList.requestFocus()
        Thread({
            val list = Repo.programmes(this, i)
            main.post {
                if (guideBox.visibility != View.VISIBLE) return@post
                val t = System.currentTimeMillis()
                progs = list.filter { it.stop > t - 2 * 3600_000 }
                guideAdapter.notifyDataSetChanged()
                if (progs.isEmpty()) { guideTitle.text = name + "\n\nNO PROGRAMME GUIDE FOR THIS CHANNEL"; return@post }
                guideList.setSelectionFromTop(progs.indexOfFirst { it.stop > t }.coerceAtLeast(0), u(60))
                guideList.requestFocus()
            }
        }, "channels-progs").start()
    }

    // ---------------------------------------------------------------- search, settings

    private fun ask(title: String, hint: String, text: String, type: Int, done: (String) -> Unit) {
        val e = EditText(this).apply { setText(text); this.hint = hint; inputType = type; setSingleLine(true); setSelectAllOnFocus(true) }
        val holder = FrameLayout(this).apply { setPadding(u(24), u(8), u(24), 0); addView(e) }
        AlertDialog.Builder(this, android.R.style.Theme_Material_Dialog_Alert)
            .setTitle(title).setView(holder)
            .setPositiveButton("OK") { _, _ -> done(e.text.toString().trim()) }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun askSearch() = ask("Search", "A part of the channel's name", found, InputType.TYPE_CLASS_TEXT) { q ->
        if (q.isEmpty()) return@ask
        found = q
        groupRows = groupsNow()
        groupAdapter.notifyDataSetChanged()
        choose(Library.FOUND + q, false)
        groupList.setSelection(groupRows.indexOfFirst { it.first == Library.FOUND + q })
        if (shown.isNotEmpty()) chanList.requestFocus()
    }

    private fun reload(force: Boolean) {
        closePanel()
        lib = null; current = -1
        player?.stop()
        say("Reading the playlist")
        Repo.start(this, force, { onChannels() }, { onGuide() })
    }

    private fun askSettings() {
        val items = arrayOf("Playlist link" + (if (prefs.playlist.isEmpty()) "  (not set)" else "  (set)"),
            "Guide link" + (if (prefs.guide.isEmpty()) "  (from the playlist)" else ""), "Fetch the playlist and the guide again")
        AlertDialog.Builder(this, android.R.style.Theme_Material_Dialog_Alert)
            .setTitle("Settings")
            .setItems(items) { _, w ->
                when (w) {
                    // the link in use is not shown: it holds the subscription key
                    0 -> ask("Playlist link", "The link of your playlist (.m3u / .m3u8)", "", InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI) { v ->
                        if (v.isNotEmpty()) { prefs.playlist = v; Repo.forget(this); reload(true) }
                    }
                    1 -> ask("Guide link", "XMLTV, .xml or .xml.gz (empty: the one the playlist names)", prefs.guide, InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI) { v ->
                        prefs.guide = v; Repo.forget(this); reload(true)
                    }
                    else -> reload(true)
                }
            }
            .setNegativeButton("Close", null)
            .show()
    }

    // ---------------------------------------------------------------- keys

    private lateinit var digits: TextView
    private var typed = ""
    private val goTyped = Runnable {
        val l = lib
        val i = if (l != null) Library.numbered(typed, l.channels.size) else -1
        typed = ""; digits.visibility = View.GONE
        if (i >= 0 && l != null) { scopeName = Library.ALL; scope = l.list(Library.ALL, favs, recents); prefs.scope = scopeName; play(i) }
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        val open = panel.visibility == View.VISIBLE
        if (keyCode == KeyEvent.KEYCODE_BACK && open) {
            if (panelFull && guideBox.visibility == View.VISIBLE) { hideGuide(); chanList.requestFocus() } else closePanel()
            return true
        }
        if (open) return super.onKeyDown(keyCode, event)
        if (keyCode in KeyEvent.KEYCODE_0..KeyEvent.KEYCODE_9 && lib != null && testStream == null) {
            typed = Library.typed(typed, keyCode - KeyEvent.KEYCODE_0)
            digits.text = typed; digits.visibility = if (typed.isEmpty()) View.GONE else View.VISIBLE
            main.removeCallbacks(goTyped); main.postDelayed(goTyped, 1600)
            return true
        }
        when (keyCode) {
            KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_CHANNEL_UP, KeyEvent.KEYCODE_MEDIA_NEXT -> { zap(1); return true }
            KeyEvent.KEYCODE_DPAD_DOWN, KeyEvent.KEYCODE_CHANNEL_DOWN, KeyEvent.KEYCODE_MEDIA_PREVIOUS -> { zap(-1); return true }
            KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_MENU -> { openPanel(); return true }
            KeyEvent.KEYCODE_DPAD_RIGHT, KeyEvent.KEYCODE_GUIDE -> { if (lib != null && current >= 0 && testStream == null) showGuide(current, false); return true }
            KeyEvent.KEYCODE_INFO -> { if (infoBar.alpha > 0f) { main.removeCallbacks(hideInfo); hideInfo.run() } else showInfo(8000); return true }
            KeyEvent.KEYCODE_BACK -> if (infoBar.alpha > 0.5f) { main.removeCallbacks(hideInfo); hideInfo.run(); return true }
        }
        return super.onKeyDown(keyCode, event)
    }
}
