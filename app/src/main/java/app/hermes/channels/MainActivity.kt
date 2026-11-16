package app.hermes.channels

import android.app.Activity
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
import android.util.Log
import android.view.Gravity
import android.view.KeyEvent
import android.view.SurfaceView
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.AdapterView
import android.widget.BaseAdapter
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.TextView
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.Format
import androidx.media3.common.TrackSelectionOverride
import androidx.media3.common.Tracks
import androidx.media3.common.text.CueGroup
import androidx.media3.common.VideoSize
import androidx.media3.common.util.Util
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.audio.AudioSink
import androidx.media3.exoplayer.audio.ForwardingAudioSink
import androidx.media3.exoplayer.hls.DefaultHlsExtractorFactory
import androidx.media3.exoplayer.hls.HlsMediaSource
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.exoplayer.source.MediaSource
import androidx.media3.exoplayer.trackselection.DefaultTrackSelector
import androidx.media3.extractor.DefaultExtractorsFactory
import androidx.media3.extractor.ts.DefaultTsPayloadReaderFactory
import app.hermes.channels.core.Guide
import app.hermes.channels.core.Library
import app.hermes.channels.core.Catchup
import app.hermes.channels.core.Portal
import app.hermes.channels.core.Xtream
import app.hermes.channels.core.M3u
import app.hermes.channels.core.Rate
import app.hermes.channels.core.Stamp
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * The whole app is this one screen: the channel plays full-screen, and everything else comes over it.
 *
 *  - Up / Down (or CH+ / CH-): the next / previous channel of the list that was chosen last
 *  - OK or Left: the channels. Lists on the left, the channels beside them; the channel that is watched (or, if set so,
 *    the one under the focus) plays in a small window on the right, with what it shows now (and what that is about) and later below it.
 *    OK watches it full-screen, Back returns to the channel that was on. Holding OK makes a favourite.
 *  - Right: the programmes of the channel that plays
 *  - hold OK: the bar with pause, start over, sound, subtitles and picture size; Left / Right choose one, OK steps it
 *  - a recording (a programme of the days before chosen under Right, "start over", or after a pause): Left / Right
 *    move a minute, OK pauses, Back returns to what is on now
 *  - Back: the channel that was on before
 *  - number keys: the channel with that number;  INFO: what plays now and next;  MENU: the settings
 *
 * The playlist link holds the subscription key: it stays in the app's private storage and is never logged;
 * the log names channels by their number in the list.
 */
class MainActivity : Activity() {

    private val tag = "Channels"
    private val main = Handler(Looper.getMainLooper())
    private lateinit var prefs: Prefs
    private lateinit var ui: Ui
    private lateinit var root: FrameLayout
    private lateinit var surface: SurfaceView
    private lateinit var cues: CueView
    /** black over the picture while a channel changes, for those who do not want the last picture to stay */
    private lateinit var shade: View
    private lateinit var cornerClock: TextView
    private lateinit var note: TextView
    private var player: ExoPlayer? = null
    private var selector: DefaultTrackSelector? = null

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
    private var trial = ""

    private fun u(n: Float) = ui.u(n)
    private fun u(n: Int) = ui.u(n)
    private fun white(a: Int) = ui.white(a)
    private val accent get() = ui.accent
    private val clock = SimpleDateFormat("HH:mm", Locale.US)
    private fun hm(t: Long): String = clock.format(Date(t))
    private fun stamp(t: Long): String = if (t == 0L) "Not yet" else SimpleDateFormat("d MMM HH:mm", Locale.getDefault()).format(Date(t))

    /** a thin line that shows how far a programme has run */
    private inner class Bar(ctx: Context) : View(ctx) {
        var f = -1f
            set(v) { field = v; invalidate() }
        var fill = accent
        var track = white(46)
        private val p = Paint(Paint.ANTI_ALIAS_FLAG)
        override fun onDraw(c: Canvas) {
            if (f < 0f) return
            val w = width.toFloat(); val h = height.toFloat(); val r = h / 2
            p.color = track; c.drawRoundRect(0f, 0f, w, h, r, r, p)
            p.color = fill; c.drawRoundRect(0f, 0f, maxOf(h, w * f), h, r, r, p)
        }
    }

    // ---------------------------------------------------------------- life

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        ui = Ui(this)
        prefs = Prefs(this)
        compact = Plus.compact(prefs)
        if (Repo.source != prefs.source) Repo.switchTo(prefs.source)
        prefs.migrateEpg()
        root = FrameLayout(this).apply { setBackgroundColor(Color.BLACK) }
        // (the order matters: the backdrop of the channel window lies under the picture, its lists over it)
        backdrop = object : View(this) {
            private val p = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.BLACK }
            // (black behind the small picture: a channel that is not as wide as the window gets black bars, not the backdrop)
            override fun onDraw(c: Canvas) {
                val x = u(MINI_X).toFloat(); val y = u(MINI_Y).toFloat(); val w = u(MINI_W).toFloat(); val h = (u(MINI_W) * 9 / 16).toFloat()
                c.drawRoundRect(x, y, x + w, y + h, u(14).toFloat(), u(14).toFloat(), p)
            }
        }.apply {
            background = GradientDrawable(GradientDrawable.Orientation.TL_BR, intArrayOf(Color.rgb(15, 20, 40), Color.rgb(6, 8, 16)))
            visibility = View.GONE
        }
        root.addView(backdrop, FrameLayout.LayoutParams(-1, -1))
        surface = SurfaceView(this)
        root.addView(surface, FrameLayout.LayoutParams(-1, -1, Gravity.TOP or Gravity.START))
        shade = View(this).apply { setBackgroundColor(Color.BLACK); visibility = View.GONE }
        root.addView(shade, FrameLayout.LayoutParams(-1, -1, Gravity.TOP or Gravity.START))
        cues = CueView(this)
        cues.look(prefs.subLook, false)
        root.addView(cues, FrameLayout.LayoutParams(-1, -1, Gravity.TOP or Gravity.START))
        buildOverlays()
        setContentView(root)
        (getSystemService(Context.DISPLAY_SERVICE) as android.hardware.display.DisplayManager).registerDisplayListener(screenWatch, main)
        Plus.start(plusHost)
        resumeAt?.let { resumeAt = null; if (it.first >= 0) { page.resume(it); main.post { if (!page.isOpen) openSettings() } } }
        takeDropped()
        favs = prefs.favs.toMutableSet()
        recents = prefs.recents
        scopeName = prefs.scope.ifEmpty { Library.ALL }
        read(intent)
    }

    /** everything that lies over the picture: built when the app starts, and once more when its look changes */
    private fun buildOverlays() {
        chips.clear()
        cornerClock = ui.label(15f, white(225)).apply { setShadowLayer(u(4).toFloat(), 0f, 0f, Color.BLACK); visibility = View.GONE }
        root.addView(cornerClock, FrameLayout.LayoutParams(-2, -2, Gravity.TOP or Gravity.END).apply { setMargins(0, u(18), u(30), 0) })
        buildInfo()
        buildBrowse()
        buildGuide()
        applyGlass()
        note = ui.label(15f, white(225), lines = 4).apply { gravity = Gravity.CENTER; setPadding(u(80), 0, u(80), 0) }
        root.addView(note, FrameLayout.LayoutParams(-1, -2, Gravity.CENTER))
        digits = ui.label(40f, Color.WHITE, true).apply { setShadowLayer(u(6).toFloat(), 0f, 0f, Color.BLACK); visibility = View.GONE }
        root.addView(digits, FrameLayout.LayoutParams(-2, -2, Gravity.TOP or Gravity.END).apply { setMargins(0, u(44), u(44), 0) })
        page = Page(ui, root, "Settings", "Aurus IPTV " + BuildConfig.VERSION_NAME.removeSuffix("-beta") + (if (isBeta) "  \u00B7  BETA" else ""))
    }

    /** the app's look has changed (an extra): what lies over the picture is built anew, while the picture goes on */
    private fun restyle() {
        val at = if (page.isOpen) page.place() else null
        if (browsing) closeBrowse(true)
        hideGuide(); closeOptions()
        main.removeCallbacks(hideInfo)
        for (i in root.childCount - 1 downTo 0) root.getChildAt(i).let { if (it !== backdrop && it !== surface && it !== shade && it !== cues) root.removeViewAt(i) }
        ui = Ui(this)
        compact = Plus.compact(prefs)
        buildOverlays()
        clockTick.run()
        if (at != null) { page.resume(at); openSettingsNow() }
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
        if (list.isNotEmpty()) { ensureSource(); prefs.playlist = list[0]; list.getOrNull(1)?.let { prefs.extra = it }; Repo.forget(this); Log.i(tag, "a playlist link was taken") }
        val guide = take("guide.txt")
        if (guide.isNotEmpty()) { prefs.setEpgOf(prefs.source, prefs.addEpg(guide[0])); Repo.forgetGuide(this, prefs.source); Log.i(tag, "a guide link was taken") }
    }

    /** what the app was started for: a channel of the home screen's row, or (for testing) a number in the list or a stream */
    private fun read(i: Intent) {
        val d = i.data
        if (d != null && d.scheme == "channels" && d.host == "play") wanted = d.lastPathSegment
        if (i.hasExtra("index")) { wanted = "#" + i.getIntExtra("index", 1); i.removeExtra("index") }
        testStream = i.getStringExtra("test")
        // (for testing a channel that misbehaves) without its sound, or with other ways of reading the stream
        trial = i.getStringExtra("trial").orEmpty()
        i.data = null
    }

    override fun onNewIntent(i: Intent) {
        super.onNewIntent(i)
        setIntent(i)
        read(i)
        if (lib != null) { if (browsing) closeBrowse(false); startPlaying() }
    }

    override fun onStart() {
        super.onStart()
        Plus.resume()
        buildPlayer()
        // a newer version is looked for once a day (shown in the settings), and the installer's news redraws them
        Update.onChange = { main.post { page.refresh() } }
        Update.check(prefs, false) { page.refresh() }
        placeSurface()
        val test = testStream
        if (test != null) {
            lib = Library(listOf(M3u.Channel("Test stream", "", test, "", "")))
            scope = listOf(0); play(0)
            return
        }
        say("Reading the playlist")
        Repo.start(this, false, { onChannels() }, { onGuide() })
        main.postDelayed(tick, 30_000)
        clockTick.run()
    }

    override fun onStop() {
        super.onStop()
        screenHeld = false; main.removeCallbacks(screenFirst)
        Plus.stop(resumeAt != null)
        other = false
        main.removeCallbacksAndMessages(null)
        sleepMin = 0; sleepAt = 0L; sleepWarned = false
        shade.visibility = View.GONE
        closeOptions()
        if (browsing) closeBrowse(false)
        player?.release()
        player = null
    }

    private fun onChannels() {
        val l = Repo.lib
        if (l == null) {
            say(when (Repo.error) {
                "none" -> "No playlist yet.\nPress MENU or OK for the settings to give its link."
                "login" -> "The server did not accept the name and password.\nPress MENU or OK for the settings to give them again."
                else -> "Could not read the playlist.\nPress MENU or OK for the settings to try again."
            })
            lib = null
            page.refresh()
            return
        }
        lib = l
        if (testStream == null && prefs.channelCount(prefs.source) != l.channels.size) { prefs.setChannelCount(l.channels.size); reopenSettings() }
        say("")
        scope = listed(scopeName).ifEmpty { scopeName = Library.ALL; listed(Library.ALL) }
        if (player != null) startPlaying()
        page.refresh()
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
        play(if (i in l.channels.indices && !Plus.hides(l.channels[i])) i else scope.firstOrNull() ?: 0)
    }

    private fun onGuide() {
        if (infoBar.alpha > 0f) fillInfo()
        if (browsing) { chanAdapter.notifyDataSetChanged(); if (detailsFor >= 0) fillDetails(detailsFor) }
        page.refresh()
    }

    /** a first playlist to put a link into, where there is none yet */
    private fun ensureSource() { if (prefs.sources.isEmpty()) { prefs.source = prefs.addSource(); Repo.switchTo(prefs.source) } }

    /** the small clock in the corner: there while watching, when it is wanted */
    private val clockTick = object : Runnable {
        override fun run() {
            main.removeCallbacks(this)
            val on = prefs.clock && !browsing
            cornerClock.visibility = if (on) View.VISIBLE else View.GONE
            if (on) { cornerClock.text = hm(System.currentTimeMillis()); main.postDelayed(this, 10_000) }
        }
    }

    /** how see-through the bars over the picture are */
    private fun applyGlass() {
        val k = 1f - prefs.glass / 100f
        fun ink(a: Int) = Color.argb((a * k).toInt(), 6, 9, 18)
        infoBar.background = GradientDrawable(GradientDrawable.Orientation.BOTTOM_TOP, intArrayOf(ink(255), ink(244), ink(160), Color.TRANSPARENT))
        guideBox.background = GradientDrawable(GradientDrawable.Orientation.LEFT_RIGHT, intArrayOf(ink(252), ink(238)))
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
            .setUserAgent("Mozilla/5.0 (Linux; Android) AurusIPTV")
            .setAllowCrossProtocolRedirects(true)
            .setConnectTimeoutMs(10000).setReadTimeoutMs(15000)
    }
    // broadcast feeds often have no "IDR" pictures at all: without this their picture never starts
    // Pictures are taken as the stream marks them. Working out where each picture begins from its slices instead cut the
    // pictures of some sport channels in three: the box's decoder took each third for a damaged picture (two errors per
    // picture) and the channel stuttered. That way is kept only for a channel that shows no picture without it.
    private val tsFlags get() = if ("plain" in trial) 0
        else if ("idronly" in trial) DefaultTsPayloadReaderFactory.FLAG_DETECT_ACCESS_UNITS
        else if (findUnits || "au" in trial) DefaultTsPayloadReaderFactory.FLAG_ALLOW_NON_IDR_KEYFRAMES or DefaultTsPayloadReaderFactory.FLAG_DETECT_ACCESS_UNITS
        else DefaultTsPayloadReaderFactory.FLAG_ALLOW_NON_IDR_KEYFRAMES
    /** the channel that plays has its pictures' beginnings worked out from their slices (see [tsFlags]) */
    private var findUnits = false
    private val needUnits = HashSet<Int>()

    /**
     * Some channels are sent with picture and sound on two different clocks, hours apart (seen on a 4K channel:
     * 9,602 seconds). A player then shows one picture and waits for the rest. Here the two are brought onto one
     * clock: whichever of the two starts second in the stream is moved by the difference, measured once where it
     * starts, against the time the other had reached at that place. Streams whose clocks agree are left alone.
     *
     * On that channel the sound's times also ran at about half speed, so the player kept losing and finding its
     * place. For such a channel the sound is therefore timed by how much of it has been played ([steadySound]).
     */
    private class Align {
        var refIsVideo: Boolean? = null
        /** the piece of the stream the two above were noted in */
        var piece: Any? = null
        var refTime = -1L
        var decided = false
        var moveVideo = false
        /** in the stream's own units (90,000 a second); 0 = nothing to move */
        var delta = 0L
        var rawVideo = 0L
        var rawSound = 0L

    }
    private var align = Align()
    /** the channel that plays is read the careful way ([Pieces]); the others are read the player's usual way */
    private var mending = false
    /** channels whose picture stopped and that were therefore tried the careful way, in this run */
    private val tryMend = HashSet<Int>()
    @Volatile private var playKey = ""
    /** the sound's own times are not to be trusted on the channel that plays: it is timed by its length instead */
    @Volatile private var steadySound = false

    /**
     * A sound a channel sends inside its picture stream. Broadcast channels often send several this way (a dubbing and
     * the original, say). The player's reader for such streams hands on one of them only, so here they are noted as
     * the stream lists them, and the one that is wanted is let through ([pick]).
     */
    private class Sound(val lang: String, val type: Int)
    /** by channel, for this run: the sounds it sends, and the one chosen on it by hand */
    private val soundsOf = HashMap<String, List<Sound>>()
    private val soundPick = HashMap<String, Int>()
    /** of the channel that plays */
    private var sounds: List<Sound> = emptyList()
    /** which of them is let through (-1: the stream is read the usual way, which gives the first) */
    @Volatile private var pick = -1

    private fun langOf(code: String): String = if (code.isEmpty() || code == "und") "" else (Util.normalizeLanguageCode(code) ?: code)
    /** the sound to play on a channel: the one chosen on it by hand, else the one in the language chosen last, else the first */
    private fun wantedSound(list: List<Sound>, key: String): Int {
        soundPick[key]?.let { if (it < list.size) return it }
        val want = langOf(prefs.audioLang)
        if (want.isNotEmpty()) list.indexOfFirst { langOf(it.lang) == want }.let { if (it >= 0) return it }
        return 0
    }
    private fun soundName(x: Sound, n: Int): String {
        val code = langOf(x.lang)
        val lang = if (code.isEmpty()) "" else Locale.forLanguageTag(code).displayLanguage.replaceFirstChar { it.uppercase() }
        return lang.ifEmpty { "Track $n" }
    }
    private fun soundKind(type: Int) = when (type) { 0x03, 0x04 -> "MPEG"; 0x0F, 0x11 -> "AAC"; 0x81 -> "Dolby Digital"; 0x87, 0x84 -> "Dolby Digital Plus"; 0x82, 0x8A -> "DTS"; else -> "" }

    /** the stream's reader found these sounds in the channel that plays */
    private fun gotSounds(key: String, gen: Int, list: List<Sound>) {
        if (gen != playGen || key != playKey || key.isEmpty() || list.size < 2) return
        val known = soundsOf[key]
        if (known == null || known.size != list.size) Log.i(tag, "#${current + 1} sends ${list.size} sounds: " + list.joinToString { langOf(it.lang).ifEmpty { "?" } + " " + soundKind(it.type) })
        soundsOf[key] = list; sounds = list
        // read the usual way, the first plays: another one is wanted (the language chosen last), so it is read again for that one
        if (pick < 0 && wantedSound(list, key) != 0) play(current, true, browsing)
        else if (optionsOpen) refreshChips()
    }

    /**
     * Reads the stream's pieces: notes the sounds a channel sends and lets the wanted one through, and puts picture
     * and sound on one clock where a channel needs it ([Align], only with [mend]).
     */
    private inner class Pieces(private val al: Align, private val mend: Boolean) : androidx.media3.exoplayer.hls.HlsExtractorFactory {
        private val usual = DefaultHlsExtractorFactory(tsFlags, true)
        // (subtitles: handled exactly as the usual factory is told to handle them)
        private var parser: androidx.media3.extractor.text.SubtitleParser.Factory = androidx.media3.extractor.text.DefaultSubtitleParserFactory()
        private var parse = true
        override fun setSubtitleParserFactory(f: androidx.media3.extractor.text.SubtitleParser.Factory): androidx.media3.exoplayer.hls.HlsExtractorFactory { parser = f; usual.setSubtitleParserFactory(f); return this }
        override fun experimentalParseSubtitlesDuringExtraction(on: Boolean): androidx.media3.exoplayer.hls.HlsExtractorFactory { parse = on; usual.experimentalParseSubtitlesDuringExtraction(on); return this }
        override fun getOutputTextFormat(f: Format): Format = usual.getOutputTextFormat(f)
        override fun createExtractor(uri: Uri, format: androidx.media3.common.Format, muxedCaptionFormats: MutableList<androidx.media3.common.Format>?,
                                     timestampAdjuster: androidx.media3.common.util.TimestampAdjuster, responseHeaders: MutableMap<String, MutableList<String>>,
                                     sniffingExtractorInput: androidx.media3.extractor.ExtractorInput, playerId: androidx.media3.exoplayer.analytics.PlayerId): androidx.media3.exoplayer.hls.HlsMediaChunkExtractor {
            val piece = Any()
            val only = pick; val key = playKey; val gen = playGen
            // (as the usual reader is set up: captions sent inside the picture are offered, and what the list of pieces
            // says the stream does not hold is not looked for)
            var flags = tsFlags
            val codecs = format.codecs
            if (!codecs.isNullOrEmpty()) {
                if (!androidx.media3.common.MimeTypes.containsCodecsCorrespondingToMimeType(codecs, androidx.media3.common.MimeTypes.AUDIO_AAC)) flags = flags or DefaultTsPayloadReaderFactory.FLAG_IGNORE_AAC_STREAM
                if (!androidx.media3.common.MimeTypes.containsCodecsCorrespondingToMimeType(codecs, androidx.media3.common.MimeTypes.VIDEO_H264)) flags = flags or DefaultTsPayloadReaderFactory.FLAG_IGNORE_H264_STREAM
            }
            val captions = if (muxedCaptionFormats != null) { flags = flags or DefaultTsPayloadReaderFactory.FLAG_OVERRIDE_CAPTION_DESCRIPTORS; muxedCaptionFormats }
                else listOf(Format.Builder().setSampleMimeType(androidx.media3.common.MimeTypes.APPLICATION_CEA608).build())
            val readers = object : androidx.media3.extractor.ts.TsPayloadReader.Factory {
                val d = DefaultTsPayloadReaderFactory(flags, captions)
                val found = ArrayList<Sound>()
                val tell = Runnable { gotSounds(key, gen, synchronized(found) { found.toList() }) }
                override fun createInitialPayloadReaders(): android.util.SparseArray<androidx.media3.extractor.ts.TsPayloadReader> { synchronized(found) { found.clear() }; return d.createInitialPayloadReaders() }
                override fun createPayloadReader(streamType: Int, esInfo: androidx.media3.extractor.ts.TsPayloadReader.EsInfo): androidx.media3.extractor.ts.TsPayloadReader? {
                    if (Log.isLoggable(tag, Log.DEBUG)) Log.d(tag, "the stream lists kind 0x${Integer.toHexString(streamType)} ${esInfo.language.orEmpty()} (${esInfo.descriptorBytes.size} bytes of notes)")
                    // the sounds, in the order the stream lists them; with one picked, the others are not read
                    if (streamType == 0x03 || streamType == 0x04 || streamType == 0x0F || streamType == 0x11 || streamType == 0x81 || streamType == 0x87 || streamType == 0x84 || streamType == 0x82 || streamType == 0x8A) {
                        val n = synchronized(found) { found.add(Sound(esInfo.language.orEmpty(), streamType)); found.size - 1 }
                        main.removeCallbacks(tell); main.post(tell)
                        if (only >= 0 && n != only) return null
                    }
                    val r = d.createPayloadReader(streamType, esInfo) ?: return null
                    // (0x15 = notes beside the programme, 0x06 may be subtitles: neither has a part in this)
                    if (!mend || streamType == 0x15 || streamType == 0x06 || "asis" in trial) return r
                    val isVideo = streamType == 0x01 || streamType == 0x02 || streamType == 0x10 || streamType == 0x1B || streamType == 0x24
                    return object : androidx.media3.extractor.ts.TsPayloadReader {
                        var logged = 0
                        override fun init(t: androidx.media3.common.util.TimestampAdjuster, o: androidx.media3.extractor.ExtractorOutput, g: androidx.media3.extractor.ts.TsPayloadReader.TrackIdGenerator) = r.init(t, o, g)
                        override fun seek() = r.seek()
                        override fun consume(data: androidx.media3.common.util.ParsableByteArray, flags: Int) {
                            if ((flags and androidx.media3.extractor.ts.TsPayloadReader.FLAG_PAYLOAD_UNIT_START_INDICATOR) != 0 && data.bytesLeft() >= 14) {
                                val a = data.data; val p = data.position
                                val has = (a[p + 7].toInt() and 255) shr 6
                                val both = has == 3 && data.bytesLeft() >= 19
                                if (has >= 2 && a[p] == 0.toByte() && a[p + 1] == 0.toByte() && a[p + 2] == 1.toByte()) {
                                    val shown = Stamp.read(a, p + 9)
                                    val t = if (both) Stamp.read(a, p + 14) else shown
                                    if (!al.decided) {
                                        // (only within one piece of the stream: where sound comes in pieces of its own, the two
                                        // are fetched for different moments, and their times say nothing about each other)
                                        if (al.piece !== piece) { al.piece = piece; al.refIsVideo = null }
                                        if (al.refIsVideo == null) al.refIsVideo = isVideo
                                        if (al.refIsVideo == isVideo) al.refTime = t
                                        else {
                                            al.decided = true
                                            val diff = Stamp.diff(t, al.refTime)
                                            if (kotlin.math.abs(diff) > 5 * 90000L) {
                                                al.delta = diff; al.moveVideo = isVideo
                                                steadySound = true
                                                val k = playKey
                                                main.post { prefs.addMended(k) }
                                                Log.i(tag, "#${current + 1} picture and sound are sent ${kotlin.math.abs(diff) / 90000} s apart: put on one clock")
                                            }
                                        }
                                    }
                                    if (Log.isLoggable(tag, Log.DEBUG)) {
                                        if (isVideo) al.rawVideo = t else al.rawSound = t
                                        if (logged++ % 400 == 0) Log.d(tag, "packet ${if (isVideo) "picture" else "sound"} kind=$streamType at ${t / 90} ms; picture is ${Stamp.diff(al.rawVideo, al.rawSound) / 90} ms ahead of sound in the stream")
                                    }
                                    if (al.delta != 0L && al.moveVideo == isVideo) {
                                        Stamp.write(a, p + 9, shown - al.delta)
                                        if (both) Stamp.write(a, p + 14, t - al.delta)
                                    }
                                }
                            }
                            r.consume(data, flags)
                        }
                    }
                }
            }
            // The usual way of reading keeps one stream of each kind, the first. With a sound picked, every stream is read
            // by its own number and the factory above leaves out the sounds that are not wanted.
            fun make() = androidx.media3.extractor.ts.TsExtractor(
                if (only >= 0) androidx.media3.extractor.ts.TsExtractor.MODE_SINGLE_PMT else androidx.media3.extractor.ts.TsExtractor.MODE_HLS,
                if (parse) 0 else androidx.media3.extractor.ts.TsExtractor.FLAG_EMIT_RAW_SUBTITLE_DATA, parser, timestampAdjuster, readers,
                androidx.media3.extractor.ts.TsExtractor.DEFAULT_TIMESTAMP_SEARCH_BYTES)
            val ts = make()
            val isTs = try { ts.sniff(sniffingExtractorInput) } catch (_: java.io.EOFException) { false } finally { sniffingExtractorInput.resetPeekPosition() }
            if (!isTs) return usual.createExtractor(uri, format, muxedCaptionFormats, timestampAdjuster, responseHeaders, sniffingExtractorInput, playerId)
            if (only < 0) return androidx.media3.exoplayer.hls.BundledHlsMediaChunkExtractor(ts, format, timestampAdjuster)
            return OneSound(ts)
        }
    }

    /**
     * A piece of the stream read with every stream under its own number. That way of reading is made for files: it
     * would go looking through the piece for how long it lasts, which a piece that is still arriving does not allow.
     * So the piece's length is kept from it.
     */
    private class OneSound(private val ts: androidx.media3.extractor.ts.TsExtractor) : androidx.media3.exoplayer.hls.HlsMediaChunkExtractor {
        private val holder = androidx.media3.extractor.PositionHolder()
        override fun init(output: androidx.media3.extractor.ExtractorOutput) = ts.init(output)
        override fun read(input: androidx.media3.extractor.ExtractorInput): Boolean =
            ts.read(object : androidx.media3.extractor.ForwardingExtractorInput(input) { override fun getLength() = C.LENGTH_UNSET.toLong() }, holder) == androidx.media3.extractor.Extractor.RESULT_CONTINUE
        override fun isPackedAudioExtractor() = false
        override fun isReusable() = true
        override fun recreate(): androidx.media3.exoplayer.hls.HlsMediaChunkExtractor = this
        override fun onTruncatedSegmentParsed() = ts.seek(0, 0)
    }

    /**
     * A channel's server that is busy for a moment (it answers 502, 503 ... to a request for the next piece) is asked
     * again, a little later each time, for about half a minute, instead of giving the channel up after three quick tries.
     */
    private val patient = object : androidx.media3.exoplayer.upstream.DefaultLoadErrorHandlingPolicy(8) {
        private fun busy(info: androidx.media3.exoplayer.upstream.LoadErrorHandlingPolicy.LoadErrorInfo) =
            (info.exception as? androidx.media3.datasource.HttpDataSource.InvalidResponseCodeException)?.responseCode in 500..599
        override fun getFallbackSelectionFor(options: androidx.media3.exoplayer.upstream.LoadErrorHandlingPolicy.FallbackOptions,
                                             info: androidx.media3.exoplayer.upstream.LoadErrorHandlingPolicy.LoadErrorInfo) =
            if (busy(info)) null else super.getFallbackSelectionFor(options, info)
        override fun getRetryDelayMsFor(info: androidx.media3.exoplayer.upstream.LoadErrorHandlingPolicy.LoadErrorInfo): Long {
            if (!busy(info)) return super.getRetryDelayMsFor(info)
            busyAt = SystemClock.elapsedRealtime()
            if (info.errorCount == 1 || Log.isLoggable(tag, Log.DEBUG)) Log.w(tag, "#${current + 1} the server is busy (HTTP ${(info.exception as androidx.media3.datasource.HttpDataSource.InvalidResponseCodeException).responseCode}): asking again" +
                " (try ${info.errorCount}, the piece from ${info.mediaLoadData.mediaStartTimeMs} to ${info.mediaLoadData.mediaEndTimeMs} ms, the list reaches to $listEndMs ms)")
            return minOf(1000L * info.errorCount, 4000L)
        }
    }

    /** when the channel's server last refused a piece (0 = it has not) */
    @Volatile private var busyAt = 0L

    /** how far the channel's list of pieces reaches, as the player last saw it (for the log) */
    @Volatile private var listEndMs = 0L

    private fun source(address: String): MediaSource {
        val item = MediaItem.fromUri(address)
        return if (Util.inferContentType(Uri.parse(address)) == C.CONTENT_TYPE_HLS)
            HlsMediaSource.Factory(http).setLoadErrorHandlingPolicy(patient).setExtractorFactory(if ("stock" !in trial) Pieces(align, mending) else DefaultHlsExtractorFactory(tsFlags, true)).createMediaSource(item)
        else DefaultMediaSourceFactory(http, DefaultExtractorsFactory().setTsExtractorFlags(tsFlags)).setLoadErrorHandlingPolicy(patient).createMediaSource(item)
    }

    /** how much later the sound is played against the picture (the "Sound delay" setting), in microseconds */
    @Volatile private var delayUs = 0L
    /** is the picture kept in step by the video hardware right now ("tunneled" playback)? */
    private var tunnel = false
    /** channels that did not play that way: they play the standard way for the rest of this run */
    private val noTunnel = HashSet<Int>()
    private var firstPicture = false
    private var videoAspect = 16f / 9f
    private var formatLine = ""
    /** how much of the channel arrives for each second it plays, in kbit (from the pieces fetched; 0 = not known yet) */
    private var streamKbps = 0L
    /** the channel that plays is sent with a picture only */
    private var noSound = false

    /**
     * What the channel that plays is made of, in one line: the picture's size, how it is coded, whether it is HDR, how
     * many pictures a second, how much data a second, and the sound. It is what the provider sends; the app plays it as it is.
     */
    private fun techLine(): String {
        if (other) return Plus.otherLine()
        val p = player ?: return ""
        val parts = ArrayList<String>()
        val v = p.videoFormat ?: tracks(C.TRACK_TYPE_VIDEO).firstOrNull { it.selected }?.format
        if (v != null && v.width > 0) {
            var x = "${v.width} \u00D7 ${v.height}"
            when (v.sampleMimeType) { "video/avc" -> "H.264"; "video/hevc" -> "H.265"; "video/mpeg2" -> "MPEG-2"; "video/av01" -> "AV1"; "video/dolby-vision" -> "Dolby Vision"; else -> null }?.let { x += "  $it" }
            when (v.colorInfo?.colorTransfer) { C.COLOR_TRANSFER_ST2084 -> "HDR10"; C.COLOR_TRANSFER_HLG -> "HLG"; else -> null }?.let { x += "  $it" }
            parts += x
        }
        if (channelFps > 0f) parts += "${Math.round(channelFps)} a second"
        if (streamKbps > 0) parts += (if (streamKbps >= 1000) "%.1f Mbit/s".format(Locale.US, streamKbps / 1000f) else "$streamKbps kbit/s")
        val a = p.audioFormat ?: tracks(C.TRACK_TYPE_AUDIO).firstOrNull { it.selected }?.format
        if (a != null) {
            val kind = when (a.sampleMimeType) { "audio/mp4a-latm" -> "AAC"; "audio/mpeg-L2" -> "MPEG audio"; "audio/mpeg" -> "MP3"; "audio/ac3" -> "Dolby Digital"; "audio/eac3" -> "Dolby Digital Plus"; else -> "" }
            val ch = when (a.channelCount) { 1 -> "mono"; 2 -> "stereo"; 6 -> "5.1"; 8 -> "7.1"; else -> "" }
            listOf(kind, ch).filter { it.isNotEmpty() }.joinToString(" ").let { if (it.isNotEmpty()) parts += it }
        }
        if (a == null && noSound) parts += "no sound is sent"
        return parts.joinToString("  \u00B7  ")
    }

    /** the sound's clock, moved by the "Sound delay" setting: the picture follows this clock */
    private inner class Renderers(ctx: Context) : DefaultRenderersFactory(ctx) {
        // The box's decoder gives the pictures of some channels times of its own making (steps of 16.6 ms on a channel of 50
        // a second, then a leap of 190 ms to catch up): shown by those, the channel jerks, and its rate is taken for 60.
        // The pictures come out in the order they are shown in, so each is given the earliest time that went in and
        // has not come out yet. A decoder that hands the times back unchanged is left alone.
        override fun buildVideoRenderers(context: Context, extensionRendererMode: Int, mediaCodecSelector: androidx.media3.exoplayer.mediacodec.MediaCodecSelector,
                                         enableDecoderFallback: Boolean, eventHandler: android.os.Handler, eventListener: androidx.media3.exoplayer.video.VideoRendererEventListener,
                                         allowedVideoJoiningTimeMs: Long, out: ArrayList<androidx.media3.exoplayer.Renderer>) {
            out.add(object : androidx.media3.exoplayer.video.MediaCodecVideoRenderer(context, codecAdapterFactory, mediaCodecSelector, allowedVideoJoiningTimeMs,
                enableDecoderFallback, eventHandler, eventListener, 50) {
                private val waiting = java.util.TreeSet<Long>()     // the times that went in and have not come out
                private val taken = HashSet<Long>()                 // ... and the ones given to pictures lately
                private var lastIndex = -1; private var lastTime = 0L
                private var own = 0                                 // pictures in a row that came out with a time that never went in
                private var far = 0                                 // ... and whose given time is far from the decoder's
                private var off = false                             // the order does not hold on this channel: the decoder's times stand
                private var said = false
                private var base = -1; private var low = Int.MAX_VALUE; private var seen = 0
                private var nIn = 0; private var nOut = 0; private var nSeen = 0
                private val tIn = LongArray(60); private val tOut = LongArray(60); private val tRaw = LongArray(60)
                private fun steps(t: LongArray) = (1 until t.size).joinToString(" ") { ((t[it] - t[it - 1]) / 100).toString() }
                private fun clear() { waiting.clear(); taken.clear(); lastIndex = -1; own = 0; far = 0; off = false; said = false; base = -1; low = Int.MAX_VALUE; seen = 0; nIn = 0; nOut = 0; nSeen = 0 }
                override fun onPositionReset(positionUs: Long, joining: Boolean, sampleStreamIsResetToKeyFrame: Boolean) { clear(); super.onPositionReset(positionUs, joining, sampleStreamIsResetToKeyFrame) }
                override fun onDisabled() { clear(); super.onDisabled() }
                override fun onQueueInputBuffer(buffer: androidx.media3.decoder.DecoderInputBuffer) {
                    if (!tunnel) { waiting.add(buffer.timeUs); if (waiting.size > 400) waiting.pollFirst() }
                    if (nIn < tIn.size) { tIn[nIn++] = buffer.timeUs; if (nIn == tIn.size && Log.isLoggable(tag, Log.DEBUG)) Log.d(tag, "into the decoder, steps in 0.1 ms: " + steps(tIn)) }
                    super.onQueueInputBuffer(buffer)
                }
                private fun trueTime(index: Int, given: Long): Long {
                    if (tunnel || off) return given
                    if (index == lastIndex) return lastTime             // (the same picture, asked about again until it is due)
                    var t = given
                    // the time that went in nearest to the one that comes out: the decoder may hand it back unchanged, or moved
                    // a little onto a grid of its own (which alone makes a channel of 50 a second judder)
                    val lo = waiting.floor(given); val hi = waiting.ceiling(given)
                    val near = if (lo != null && (hi == null || given - lo <= hi - given)) lo else hi
                    if (near != null && kotlin.math.abs(near - given) <= NEAR_US) {
                        // found: it is the picture's true time; what went in before it will not come out any more
                        own = 0; far = 0
                        waiting.headSet(near, true).clear(); taken.clear()
                        t = near
                    } else if (taken.any { kotlin.math.abs(it - given) <= NEAR_US }) {
                        own = 0; far = 0; taken.clear()
                    } else if (waiting.isNotEmpty()) {
                        val first = waiting.pollFirst()!!
                        taken.add(first); if (taken.size > 64) taken.clear()
                        // (a decoder that makes two pictures of one gives every other picture a time that never went in: only a
                        // row of pictures with times of the decoder's making counts)
                        if (++own < 12) { lastIndex = index; lastTime = given; return given }
                        t = first
                        if (kotlin.math.abs(t - given) > 600_000) far++ else far = 0
                        if (far >= 40) { off = true; Log.w(tag, "#${current + 1} the decoder's times and the stream's do not fit together: the decoder's are used"); return given }
                        if (own == 25 && !said) { said = true; Log.i(tag, "#${current + 1} the decoder gives the pictures times of its own: the stream's times are used") }
                        // pictures the decoder threw away leave their times behind: when more wait than at the start, the oldest go
                        low = minOf(low, waiting.size)
                        if (++seen >= 150) {
                            if (base < 0) base = low else if (low > base) repeat(low - base) { waiting.pollFirst() }
                            seen = 0; low = Int.MAX_VALUE
                        }
                    }
                    lastIndex = index; lastTime = t
                    return t
                }
                override fun processOutputBuffer(positionUs: Long, elapsedRealtimeUs: Long, codec: androidx.media3.exoplayer.mediacodec.MediaCodecAdapter?, buffer: java.nio.ByteBuffer?,
                                                 bufferIndex: Int, bufferFlags: Int, sampleCount: Int, bufferPresentationTimeUs: Long, isDecodeOnlyBuffer: Boolean,
                                                 isLastBuffer: Boolean, format: androidx.media3.common.Format): Boolean {
                    val fresh = bufferIndex != lastIndex
                    val t = trueTime(bufferIndex, bufferPresentationTimeUs)
                    if (fresh && nSeen < 40 && Log.isLoggable(tag, Log.DEBUG)) { nSeen++; Log.d(tag, "out $nSeen: decoder ${bufferPresentationTimeUs / 1000} ms, used ${t / 1000} ms, playing at ${positionUs / 1000} ms, waiting ${waiting.size} from ${waiting.firstOrNull()?.div(1000)}, own=$own") }
                    val done = super.processOutputBuffer(positionUs, elapsedRealtimeUs, codec, buffer, bufferIndex, bufferFlags, sampleCount, t, isDecodeOnlyBuffer, isLastBuffer, format)
                    if (done) {
                        lastIndex = -1
                        // the channel's rate, from the times its pictures are shown at; the first ones are not counted
                        // (a channel often starts with some left out, and the decoder's own times are not yet set right)
                        val g = playGen
                        if (frameGen != g) { frameGen = g; frameN = 0 }
                        if (frameN < frameTimes.size) {
                            frameTimes[frameN++] = t
                            if (frameN == frameTimes.size) {
                                val fps = Rate.of(frameTimes.copyOfRange(20, frameTimes.size))
                                if (Log.isLoggable(tag, Log.DEBUG)) Log.d(tag, "rated from steps (0.1 ms): " + (21 until frameTimes.size).joinToString(" ") { ((frameTimes[it] - frameTimes[it - 1]) / 100).toString() } + "; own=$own waiting=${waiting.size}")
                                main.post { if (g == playGen) gotRate(fps, "timed") }
                            }
                        }
                        if (nOut < tOut.size) {
                            tRaw[nOut] = bufferPresentationTimeUs; tOut[nOut++] = t
                            if (nOut == tOut.size && Log.isLoggable(tag, Log.DEBUG)) { Log.d(tag, "out of the decoder, steps in 0.1 ms: " + steps(tRaw)); Log.d(tag, "shown at, steps in 0.1 ms: " + steps(tOut)) }
                        }
                    }
                    return done
                }
            })
        }
        // sound the device has no decoder for (MPEG Layer II on some TVs) is decoded by the app; the device's own come first
        override fun buildAudioRenderers(context: Context, extensionRendererMode: Int, mediaCodecSelector: androidx.media3.exoplayer.mediacodec.MediaCodecSelector,
                                         enableDecoderFallback: Boolean, audioSink: AudioSink, eventHandler: android.os.Handler,
                                         eventListener: androidx.media3.exoplayer.audio.AudioRendererEventListener, out: ArrayList<androidx.media3.exoplayer.Renderer>) {
            super.buildAudioRenderers(context, extensionRendererMode, mediaCodecSelector, enableDecoderFallback, audioSink, eventHandler, eventListener, out)
            out.add(Mp2Renderer(eventHandler, eventListener, audioSink))
        }
        override fun buildAudioSink(context: Context, enableFloatOutput: Boolean, enableAudioTrackPlaybackParams: Boolean): AudioSink? {
            // (as the usual output is built, with one step of the app's own in it: speech made clearer)
            val sink = androidx.media3.exoplayer.audio.DefaultAudioSink.Builder(context).setEnableFloatOutput(enableFloatOutput)
                .setEnableAudioTrackPlaybackParams(enableAudioTrackPlaybackParams).setAudioProcessors(arrayOf(Speech())).build()
            return object : ForwardingAudioSink(sink) {
                // Dolby sound decoded here, where that is chosen and the device can: the output is then said not to take it as it is
                private fun kept(f: Format) = when (f.sampleMimeType) {
                    androidx.media3.common.MimeTypes.AUDIO_AC3 -> prefs.decodeAc3 && canDecode(androidx.media3.common.MimeTypes.AUDIO_AC3)
                    androidx.media3.common.MimeTypes.AUDIO_E_AC3, androidx.media3.common.MimeTypes.AUDIO_E_AC3_JOC -> prefs.decodeEac3 && canDecode(androidx.media3.common.MimeTypes.AUDIO_E_AC3)
                    else -> false
                }
                override fun supportsFormat(format: Format) = if (kept(format)) false else super.supportsFormat(format)
                override fun getFormatSupport(format: Format) = if (kept(format)) AudioSink.SINK_FORMAT_UNSUPPORTED else super.getFormatSupport(format)
                // for a channel whose sound carries wrong times: each piece of sound follows the one before
                private var perSecond = 0L          // bytes of sound a second (0: not plain sound, its length is not known here)
                private var unitUs = 0L             // else: how long one unit of coded sound lasts
                private var nextUs = C.TIME_UNSET
                private var pending: java.nio.ByteBuffer? = null
                private var pendingUs = 0L
                override fun configure(config: AudioSink.AudioSinkConfig) {
                    val inputFormat = config.format
                    val rate = inputFormat.sampleRate
                    perSecond = if (inputFormat.sampleMimeType == "audio/raw" && rate > 0 && inputFormat.channelCount > 0)
                        Util.getPcmFrameSize(inputFormat.pcmEncoding, inputFormat.channelCount).toLong() * rate else 0L
                    unitUs = if (perSecond == 0L && rate > 0 && (inputFormat.sampleMimeType == "audio/ac3" || inputFormat.sampleMimeType == "audio/eac3")) 1536L * 1_000_000L / rate else 0L
                    super.configure(config)
                }
                override fun handleBuffer(buffer: java.nio.ByteBuffer, presentationTimeUs: Long, encodedAccessUnitCount: Int): Boolean {
                    if (!steadySound || (perSecond == 0L && unitUs == 0L)) { nextUs = C.TIME_UNSET; return super.handleBuffer(buffer, presentationTimeUs, encodedAccessUnitCount) }
                    if (nextUs == C.TIME_UNSET) nextUs = presentationTimeUs
                    if (pending !== buffer) {
                        pending = buffer
                        pendingUs = if (perSecond > 0L) buffer.remaining() * 1_000_000L / perSecond else unitUs * encodedAccessUnitCount
                    }
                    val done = super.handleBuffer(buffer, nextUs, encodedAccessUnitCount)
                    if (done) { nextUs += pendingUs; pending = null }
                    return done
                }
                override fun flush() { nextUs = C.TIME_UNSET; pending = null; super.flush() }
                override fun getCurrentPositionUs(sourceEnded: Boolean): Long {
                    val p = super.getCurrentPositionUs(sourceEnded)
                    return if (p == AudioSink.CURRENT_POSITION_NOT_SET) p else p + delayUs
                }
            }
        }
    }

    private fun buildPlayer() {
        player?.release()
        delayUs = prefs.soundDelay * 1000L
        val sel = DefaultTrackSelector(this)
        tunnel = prefs.hardwareSync
        sel.setParameters(sel.buildUponParameters().setTunnelingEnabled(tunnel)
            .setTrackTypeDisabled(C.TRACK_TYPE_AUDIO, "nosound" in trial).setTrackTypeDisabled(C.TRACK_TYPE_VIDEO, "nopicture" in trial)
            .setPreferredAudioLanguage(prefs.audioLang.ifEmpty { null }).setPreferredTextLanguage(prefs.subLang.ifEmpty { null })
            .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, prefs.subLang.isEmpty()).setForceHighestSupportedBitrate(prefs.bestQuality))
        // a channel starts to move with 0.8 s in hand (the usual 2.5 s showed its first picture standing still for a second; with
        // 0.3 s a channel whose server is slow to begin moved, then stopped again at once); after a stall it takes more in hand
        val load = androidx.media3.exoplayer.DefaultLoadControl.Builder().setBufferDurationsMs(20_000, 50_000, 800, 2_500).build()
        val p = ExoPlayer.Builder(this, Renderers(this)).setTrackSelector(sel).setLoadControl(load).build()
        p.setVideoSurfaceView(surface)
        p.addListener(listener)
        // (DEBUG) which pieces of the stream were fetched, and the times they carry: never their addresses
        p.addAnalyticsListener(object : androidx.media3.exoplayer.analytics.AnalyticsListener {
            override fun onLoadCompleted(t: androidx.media3.exoplayer.analytics.AnalyticsListener.EventTime, l: androidx.media3.exoplayer.source.LoadEventInfo, d: androidx.media3.exoplayer.source.MediaLoadData) {
                // pieces that take as long to arrive as they last: the connection is no faster than the channel
                val lasts = d.mediaEndTimeMs - d.mediaStartTimeMs
                if (d.dataType == C.DATA_TYPE_MEDIA && lasts > 500 && d.mediaStartTimeMs >= 0 && l.bytesLoaded > 0) {
                    val k = l.bytesLoaded * 8 / lasts
                    streamKbps = if (streamKbps == 0L) k else (streamKbps * 2 + k) / 3
                }
                if (d.dataType == C.DATA_TYPE_MEDIA && lasts > 0 && d.mediaStartTimeMs >= 0) {
                    if (l.loadDurationMs > 2000 && l.loadDurationMs >= lasts * 9 / 10) {
                        slowPieces++
                        if (slowPieces == 2) Log.w(tag, "#${current + 1} the connection is no faster than the channel: ${l.bytesLoaded * 8 / l.loadDurationMs} kbit/s arrive, the channel is ${l.bytesLoaded * 8 / lasts} kbit/s")
                    } else if (l.loadDurationMs < lasts / 2) slowPieces = 0
                }
                if (Log.isLoggable(tag, Log.DEBUG)) Log.d(tag, "#${current + 1} piece type=${d.dataType} track=${d.trackType} ${d.trackFormat?.codecs ?: ""} ${d.trackFormat?.containerMimeType ?: ""} " +
                    "from ${d.mediaStartTimeMs} to ${d.mediaEndTimeMs} ms, ${l.bytesLoaded / 1024} KB in ${l.loadDurationMs} ms")
            }
        })
        selector = sel
        player = p
    }

    private fun setTunnel(on: Boolean) {
        if (on == tunnel) return
        tunnel = on
        selector?.let { it.setParameters(it.buildUponParameters().setTunnelingEnabled(on)) }
    }

    // what the watch below remembers of the channel that plays
    private var lastFrames = -1
    /** since when the player waits for the channel's server, and since when it plays without waiting (0 = it does not) */
    private var waitSince = 0L
    private var goodSince = 0L
    private var gaveUp = false
    /** pieces in a row that arrived no faster than they play, and whether the screen has said so on this channel */
    private var slowPieces = 0
    private var slowSaid = false
    private val unsay = Runnable { if (note.text.startsWith("The connection is slower")) say("") }
    private fun saySlow() {
        if (slowSaid) return
        slowSaid = true
        say("The connection is slower than this channel needs.\nThe picture will stop now and then.")
        main.removeCallbacks(unsay); main.postDelayed(unsay, 8000)
    }
    /** a channel that was given up is tried again every 20 s for as long as it is on, so that it plays once its server does */
    private var laterPending = false
    private val tryLater = Runnable { laterPending = false; if (current >= 0 && player != null) { Log.i(tag, "#${current + 1} trying the channel again"); play(current, true, browsing) } }
    private fun later() { if (!laterPending) { laterPending = true; main.postDelayed(tryLater, 20_000) } }
    private var stuck = 0
    private var rateAt = 0L
    private var rateFrom = 0
    private var rated = false
    /** counts the channel changes, so that pictures of the channel before are not taken for this one's */
    @Volatile private var playGen = 0
    // (these three are touched on the player's own thread only)
    private val frameTimes = LongArray(80)
    private var frameN = 0
    private var frameGen = -1
    /** pictures a second of the channel that plays, as counted; 0 = not known yet */
    private var channelFps = 0f

    /**
     * Every two seconds, a look at the channel that plays: how many pictures a second it has (the screen is then
     * switched to suit), and whether the picture has stopped while the hardware keeps step (it is then played the
     * standard way). With `adb shell setprop log.tag.Channels DEBUG` it also logs what the player is doing.
     */
    private val probe = object : Runnable {
        override fun run() {
            val p = player ?: return
            val v = p.videoDecoderCounters; val a = p.audioDecoderCounters
            if (Log.isLoggable(tag, Log.DEBUG)) {
                listEndMs = p.duration
                Log.d(tag, "#${current + 1} state=${p.playbackState} list=${p.duration}ms behind=${p.currentLiveOffset}ms at=${p.currentPosition}ms tunnel=$tunnel buffered=${p.totalBufferedDuration}ms " +
                    "video[in=${v?.queuedInputBufferCount} out=${v?.renderedOutputBufferCount} drop=${v?.droppedBufferCount}] audio[out=${a?.renderedOutputBufferCount}]")
            }
            val now = SystemClock.elapsedRealtime()
            // a channel whose server sends next to nothing (the player waits and waits): asked again, as changing away and back would;
            // one that has played steadily for a while has its tries back
            if (p.playbackState == Player.STATE_BUFFERING && p.playWhenReady) {
                goodSince = 0L
                if (waitSince == 0L) waitSince = now
                else if (now - waitSince >= (if (busyAt > 0 && now - busyAt < 6_000) 3_000 else 12_000)) {
                    if (retries < 5) {
                        retries++
                        Log.w(tag, "#${current + 1} nothing arrives for ${(now - waitSince) / 1000} s: asking the server again (try $retries)")
                        say("The channel's server is slow.\nTrying again \u2026")
                        play(current, true, browsing)
                        return
                    }
                    if (!gaveUp) { gaveUp = true; Log.w(tag, "#${current + 1} the server does not deliver"); say("This channel's server is not delivering right now.\nThe app keeps trying.") }
                    later()
                }
            } else {
                waitSince = 0L
                if (p.playbackState == Player.STATE_READY) { if (goodSince == 0L) goodSince = now else if (now - goodSince > 10_000) { retries = 0; gaveUp = false } }
            }
            val frames = if (v == null) -1 else v.renderedOutputBufferCount + v.droppedBufferCount + v.skippedOutputBufferCount
            if (p.playbackState == Player.STATE_READY && p.playWhenReady && frames >= 0) {
                if (frames == lastFrames) stuck++ else stuck = 0
                if (tunnel && stuck >= 4) {
                    Log.w(tag, "#${current + 1} the picture stopped with hardware sync: standard way")
                    noTunnel.add(current)
                    play(current, true, browsing)
                    return
                }
                // (sound but never a picture: its stream may not mark where the pictures begin)
                if (!tunnel && stuck >= 3 && frames == 0 && current !in needUnits && v != null) {
                    Log.w(tag, "#${current + 1} sound but no picture: working out the pictures from their slices")
                    needUnits.add(current)
                    play(current, true, browsing)
                    return
                }
                if (!tunnel && stuck >= 4 && !mending && current !in tryMend) {
                    Log.w(tag, "#${current + 1} the picture stopped while the sound goes on: reading the stream the careful way")
                    tryMend.add(current)
                    play(current, true, browsing)
                    return
                }
                if (!rated) {
                    if (rateAt == 0L) { if (frames > 0) { rateAt = now; rateFrom = frames } }
                    else if (now - rateAt >= 5500 && frames - rateFrom < 40) { rateAt = now; rateFrom = frames }      // (it had not got going yet)
                    else if (now - rateAt >= 5500) {
                        gotRate((frames - rateFrom) * 1000f / (now - rateAt), "counted")
                    }
                }
            } else rateAt = 0L
            lastFrames = frames
            main.postDelayed(this, 2000)
        }
    }

    /** the channel's rate is known ([how]: declared by the stream, timed from its first pictures, or counted) */
    private fun gotRate(fps: Float, how: String) {
        if (rated || fps <= 0f) return
        rated = true
        channelFps = fps
        if (playKey.isNotEmpty() && kotlin.math.abs(prefs.rate(playKey) - fps) > 0.3f) prefs.setRate(playKey, fps)
        Log.i(tag, "#${current + 1} ${"%.2f".format(Locale.US, fps)} pictures a second ($how, ${SystemClock.elapsedRealtime() - startedAt} ms in)")
        // (not while channels are only looked at in the small window: the screen would go dark again and again)
        if (!browsing) rateKnown()
        page.refresh()
    }

    /** the channel has been on for a few seconds: the viewer stays with it */
    private var settled = false
    private val settle = Runnable { settled = true; if (!browsing) matchScreen() }
    private fun rateKnown() { if (settled || !prefs.rateWait) matchScreen() }

    /** the screen's mode (same resolution) that suits a channel of [fps] pictures a second; 0 = none, the screen stays as it is */
    private fun modeFor(fps: Float): Int {
        if (fps <= 0f) return 0
        @Suppress("DEPRECATION") val d = windowManager.defaultDisplay
        val cur = d.mode
        val modes = d.supportedModes.filter { it.physicalWidth == cur.physicalWidth && it.physicalHeight == cur.physicalHeight }
        val i = Rate.pick(fps, FloatArray(modes.size) { modes[it].refreshRate })
        return if (i < 0) 0 else modes[i].modeId
    }

    /** switches the screen to the rate of the channel that plays (same resolution), so that motion is even */
    private fun matchScreen() {
        val lp = window.attributes
        var want = 0
        @Suppress("DEPRECATION") val d = windowManager.defaultDisplay
        val cur = d.mode
        if (prefs.matchRate) {
            want = modeFor(channelFps)
            // a rate that is not known, or one the screen has nothing for: it stays as it is
            if (want == 0) return
            if (want != cur.modeId) Log.i(tag, "screen: ${"%.2f".format(Locale.US, cur.refreshRate)} to ${"%.2f".format(Locale.US, d.supportedModes.first { it.modeId == want }.refreshRate)} a second")
        }
        if (lp.preferredDisplayModeId != want) {
            if (want != 0 && want != cur.modeId) holdForScreen()
            lp.preferredDisplayModeId = want; window.attributes = lp
        }
    }

    // ---- while the screen changes its rate it is dark, and for some seconds after it the picture did not move while the
    // sound ran on: the channel waits for the screen, and plays on when it is there (for a live channel: a moment later
    // than before, with nothing missed)
    private var screenHeld = false
    private val screenFree = Runnable {
        if (!screenHeld) return@Runnable
        screenHeld = false
        Log.i(tag, "screen: changed, playing on")
        if (!paused && !held && !other) player?.play()
    }
    private fun holdForScreen() {
        val p = player ?: return
        if (other || held || paused) return
        screenHeld = true
        p.pause()
        screenTimed = true
        main.removeCallbacks(screenFree); main.postDelayed(screenFree, 4500)
    }
    /**
     * A channel whose rate is remembered and is not the screen's: the screen is changed while the channel changes, before
     * its first picture, so that the dark moment is part of the change and not a break a few seconds into watching.
     * (After a moment's stay only: while channels are passed through one after another the screen is left alone.)
     */
    private val screenFirst = Runnable { if (screenHeld) { screenTimed = false; matchScreen(); if (!screenTimed) screenFree.run() } }
    /** the screen is being changed, and the wait for it is timed */
    private var screenTimed = false
    override fun onDestroy() {
        (getSystemService(Context.DISPLAY_SERVICE) as android.hardware.display.DisplayManager).unregisterDisplayListener(screenWatch)
        super.onDestroy()
    }
    private val screenWatch = object : android.hardware.display.DisplayManager.DisplayListener {
        override fun onDisplayAdded(id: Int) {}
        override fun onDisplayRemoved(id: Int) {}
        override fun onDisplayChanged(id: Int) {
            @Suppress("DEPRECATION") val now = windowManager.defaultDisplay.mode.modeId
            if (screenHeld && now == window.attributes.preferredDisplayModeId) { main.removeCallbacks(screenFree); main.postDelayed(screenFree, 500) }
        }
    }

    private fun screenLine(): String {
        @Suppress("DEPRECATION") val m = windowManager.defaultDisplay.mode
        return "${m.physicalWidth} x ${m.physicalHeight}, ${"%.2f".format(Locale.US, m.refreshRate).removeSuffix(".00")} a second"
    }

    /** a channel that shows no picture with the hardware keeping step is played the standard way */
    private val tunnelCheck = Runnable {
        if (tunnel && !firstPicture && current >= 0 && player?.currentTracks?.isTypeSelected(C.TRACK_TYPE_VIDEO) == true) {
            Log.w(tag, "#${current + 1} no picture with hardware sync: standard way")
            noTunnel.add(current)
            play(current, true, browsing)
        }
    }

    /** half a minute on a channel is watching it: it joins the recent ones, here and on the TV's home screen */
    private val watched = Runnable {
        val l = lib ?: return@Runnable
        if (current !in l.channels.indices || testStream != null || browsing) return@Runnable
        recents = Library.pushed(recents, l.keyOf(current))
        prefs.recents = recents
        publishHome()
    }

    private fun publishHome() {
        val l = lib ?: return
        val rows = (recents.take(10) + favs.filter { it !in recents.take(10) }).mapNotNull { k ->
            val i = l.indexOf(k)
            if (i < 0 || Plus.hides(l.channels[i])) null else k + "\t" + l.channels[i].name.replace('\t', ' ') + "\t" + Repo.logo(i)
        }
        prefs.homeRows = rows
        Home.publish(this, rows)
    }

    /** [preview]: the channel is only looked at in the small window; it is not remembered as the one that was on */
    private fun play(i: Int, again: Boolean = false, preview: Boolean = false, at: Long = if (again) shiftAt else 0L) {
        val p = player ?: return
        val l = lib ?: return
        val c = l.channels.getOrNull(i) ?: return
        if (held) return
        if (!again) retries = 0
        current = i
        if (!preview) commit(i)
        // a recording: from the moment [at] on (not while channels are only looked at)
        val rule = c.rec
        val from = if (at > 0L && rule != null && prefs.archive && !preview && testStream == null) at else 0L
        shiftAt = from; shiftSeekTo = 0L; paused = false
        shiftLast = SystemClock.elapsedRealtime()
        main.removeCallbacks(shiftTick); main.removeCallbacks(shiftGo)
        if (from > 0L) { main.postDelayed(shiftTick, 1000); if (shiftProgsFor != i) loadShiftProgs(i) }
        if (testStream == null && !preview) prefs.last = l.keyOf(i)
        if (!again) say("")
        if (!preview && !browsing && !page.isOpen) showInfo()
        setTunnel(prefs.hardwareSync && i !in noTunnel)
        firstPicture = false
        formatLine = ""
        lastFrames = -1; stuck = 0; rateAt = 0L; rated = false; channelFps = 0f
        waitSince = 0L; goodSince = 0L; busyAt = 0L; if (!again) { gaveUp = false; slowPieces = 0; slowSaid = false; streamKbps = 0L }
        noSound = false
        main.removeCallbacks(tryLater); laterPending = false
        playGen++
        startedAt = SystemClock.elapsedRealtime()
        Log.i(tag, "play #${i + 1}" + (if (tunnel) " (hardware sync)" else "") + (if (from > 0L) " (recording, ${(System.currentTimeMillis() - from) / 60000} min back)" else "") +
            (if (rule != null) " [recorded ${rule.days} d]" else "") + (if (Repo.nowNext(i, System.currentTimeMillis()).first != null) " [guide]" else ""))
        main.removeCallbacks(probe); main.postDelayed(probe, 2000)
        main.removeCallbacks(tunnelCheck); if (tunnel) main.postDelayed(tunnelCheck, 8000)
        main.removeCallbacks(watched); if (!preview) main.postDelayed(watched, 30_000)
        align = Align()
        steadySound = false
        playKey = if (testStream == null) l.keyOf(i) else ""
        // a channel watched before: the screen is set for its rate now, while the channels change, not a second into its picture
        // (while channels are changed one after another the screen stays as it is: a change of its rate makes the TV go dark for a moment)
        if (!again) { settled = false; main.removeCallbacks(settle); if (!preview) main.postDelayed(settle, SETTLE_MS) }
        screenHeld = false; main.removeCallbacks(screenFree); main.removeCallbacks(screenFirst)
        if (!preview && !browsing && prefs.matchRate && playKey.isNotEmpty()) prefs.rate(playKey).let { if (it > 0f) {
            channelFps = it
            @Suppress("DEPRECATION") val now = windowManager.defaultDisplay.mode.modeId
            val want = modeFor(it)
            if (want != 0 && want != now && !other && !held) { screenHeld = true; main.postDelayed(screenFirst, 350) } else rateKnown()
        } }
        mending = "mend" in trial || i in tryMend || playKey in prefs.mended
        // a channel known to send several sounds: the wanted one is read from the start (the first needs no picking)
        sounds = soundsOf[playKey].orEmpty()
        pick = if (sounds.size > 1) wantedSound(sounds, playKey).let { if (it > 0) it else -1 } else -1
        findUnits = i in needUnits
        // a choice of sound or subtitles made on the channel before does not fit this one's tracks: the languages chosen last decide
        selector?.let { it.setParameters(it.buildUponParameters().clearOverridesOfType(C.TRACK_TYPE_AUDIO).clearOverridesOfType(C.TRACK_TYPE_TEXT)
            .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, prefs.subLang.isEmpty())) }
        cues.show(emptyList())
        pictureMode = prefs.picture(l.keyOf(i))
        placeSurface()
        if (prefs.blackSwitch) shade.visibility = View.VISIBLE
        val nowSec = System.currentTimeMillis() / 1000
        // (an address in a form there is no reader for must not take the app down: the channel is said not to play)
        val gen = playGen
        fun go(address: String) {
            // (an extra) a channel that is played by another engine: the app's own player lets the surface go and waits
            if (playKey.isNotEmpty() && Plus.takes(playKey)) {
                main.removeCallbacks(probe); main.removeCallbacks(tunnelCheck); main.removeCallbacks(settle)
                p.stop()
                if (!other) { other = true; p.clearVideoSurface() }
                shade.visibility = View.VISIBLE
                Plus.play(address, surface)
                return
            }
            if (other) { other = false; Plus.letGo(); p.setVideoSurfaceView(surface) }
            val src = try { source(address) } catch (e: Exception) {
                Log.w(tag, "#${i + 1} no reader for this kind of address (${e.cause?.javaClass?.simpleName ?: e.javaClass.simpleName})")
                p.stop()
                shade.visibility = View.VISIBLE
                say("This channel is sent in a form the app cannot play.")
                return
            }
            // the decoder of the channel before is let go, not handed on: handed on, the box's decoder stumbled over a channel
            // of another picture size, which cost half a second and a second start
            p.stop()
            // (the sound comes in over a moment, not at once: at once it can click)
            fader?.cancel(); p.volume = 0f; faded = false
            p.setMediaSource(src)
            p.prepare()
            // (a channel that waits for the screen to change starts when the screen is there)
            p.playWhenReady = !screenHeld
        }
        // a portal's channel: the address it plays from is asked for now, each time
        if (c.url.startsWith(Portal.SCHEME)) {
            Thread({
                val a = try { Repo.portalAddress(this, c.url) } catch (e: Exception) { Log.w(tag, "#${i + 1} the portal gave no address (${e.javaClass.simpleName})"); null }
                main.post {
                    if (gen != playGen || player !== p) return@post
                    if (a != null) go(a) else { p.stop(); shade.visibility = View.VISIBLE; say("The portal gave no address for this channel.\nIt is tried again shortly."); later() }
                }
            }, "channels-portal").start()
        } else go(if (from > 0L && rule != null) Catchup.address(c.url, rule, from / 1000, nowSec, (nowSec - from / 1000).coerceIn(60, 4 * 3600)) else c.url)
    }

    /** the channels of the list [id], without those that are not to be shown */
    private fun listed(id: String): List<Int> {
        val l = lib ?: return emptyList()
        val all = l.list(id, favs, recents)
        return if (Plus.PRESENT) all.filter { !Plus.hides(l.channels[it]) } else all
    }

    /** the device has a decoder of its own for this kind of sound */
    private val decoders = HashMap<String, Boolean>()
    private fun canDecode(mime: String) = synchronized(decoders) { decoders.getOrPut(mime) {
        try { androidx.media3.exoplayer.mediacodec.MediaCodecUtil.getDecoderInfos(mime, false, false).isNotEmpty() } catch (e: Exception) { false } } }

    /** what is done with a kind of Dolby sound, as things are: the choice, and where it cannot be followed, why */
    private fun dolbyNow(decode: Boolean, encoding: Int, mime: String): String = when {
        !takes(encoding) -> if (canDecode(mime)) "Decoded here (the TV does not take it)" else "Not played: neither the TV nor this device takes it"
        decode && !canDecode(mime) -> "Passed on (this device cannot decode it)"
        decode -> "Decoded here"
        else -> "Passed on"
    }

    /** what the TV, or the receiver between, says it takes as it is */
    private fun tvTakes(): String {
        val caps = try { androidx.media3.exoplayer.audio.AudioCapabilities.getCapabilities(this, androidx.media3.common.AudioAttributes.DEFAULT, null) } catch (e: Exception) { return "\u2014" }
        val parts = ArrayList<String>()
        if (caps.supportsEncoding(C.ENCODING_AC3)) parts += "Dolby Digital"
        if (caps.supportsEncoding(C.ENCODING_E_AC3)) parts += "Dolby Digital Plus"
        if (caps.supportsEncoding(C.ENCODING_E_AC3_JOC)) parts += "Atmos"
        if (caps.supportsEncoding(C.ENCODING_DTS)) parts += "DTS"
        return if (parts.isEmpty()) "Plain sound only: Dolby is decoded here" else parts.joinToString(", ")
    }
    private fun takes(encoding: Int) = try { androidx.media3.exoplayer.audio.AudioCapabilities.getCapabilities(this, androidx.media3.common.AudioAttributes.DEFAULT, null).supportsEncoding(encoding) } catch (e: Exception) { false }

    /**
     * Speech made clearer, in sound that is decoded here (not in sound passed on as it is). Of two channels: what both
     * carry alike, where speech is, is raised around 2.5 kHz and what differs between them is lowered. Of six: mixed
     * down to two with more of the centre channel, where speech is.
     */
    private inner class Speech : androidx.media3.common.audio.BaseAudioProcessor() {
        private var six = false
        private var level = 0
        private var b0 = 1.0; private var b1 = 0.0; private var b2 = 0.0; private var a1 = 0.0; private var a2 = 0.0
        private var x1 = 0.0; private var x2 = 0.0; private var y1 = 0.0; private var y2 = 0.0
        override fun onConfigure(f: androidx.media3.common.audio.AudioProcessor.AudioFormat): androidx.media3.common.audio.AudioProcessor.AudioFormat {
            level = prefs.speech
            if (level == 0 || f.encoding != C.ENCODING_PCM_16BIT || (f.channelCount != 2 && f.channelCount != 6)) return androidx.media3.common.audio.AudioProcessor.AudioFormat.NOT_SET
            six = f.channelCount == 6
            Log.i(tag, "speech made clearer (${if (level == 1) "on" else "strong"}): ${f.channelCount} channels of sound at ${f.sampleRate} Hz")
            // a bell around 2.5 kHz, +4 or +7 dB
            val gain = Math.pow(10.0, (if (level == 1) 4.0 else 7.0) / 40.0)
            val w = 2 * Math.PI * 2500.0 / f.sampleRate
            val alpha = Math.sin(w) / (2 * 0.8)
            val a0 = 1 + alpha / gain
            b0 = (1 + alpha * gain) / a0; b1 = -2 * Math.cos(w) / a0; b2 = (1 - alpha * gain) / a0; a1 = b1; a2 = (1 - alpha / gain) / a0
            return androidx.media3.common.audio.AudioProcessor.AudioFormat(f.sampleRate, 2, C.ENCODING_PCM_16BIT)
        }
        override fun onFlush(streamMetadata: androidx.media3.common.audio.AudioProcessor.StreamMetadata) { x1 = 0.0; x2 = 0.0; y1 = 0.0; y2 = 0.0 }
        private fun clip(v: Double) = (if (v > 32767.0) 32767.0 else if (v < -32768.0) -32768.0 else v).toInt().toShort()
        override fun queueInput(input: java.nio.ByteBuffer) {
            val frames = input.remaining() / (if (six) 12 else 4)
            val out = replaceOutputBuffer(frames * 4)
            if (six) {
                // (front left, front right, centre, low, back left, back right)
                val c = if (level == 1) 1.0 else 1.4
                val k = 1.0 / (1.0 + c * 0.6 + 0.4)
                for (i in 0 until frames) {
                    val l = input.short.toDouble(); val r = input.short.toDouble(); val ce = input.short.toDouble(); input.short
                    val bl = input.short.toDouble(); val br = input.short.toDouble()
                    out.putShort(clip((l + c * ce + 0.5 * bl) * k * 1.6)); out.putShort(clip((r + c * ce + 0.5 * br) * k * 1.6))
                }
            } else {
                val side = if (level == 1) 0.8 else 0.63
                val pre = if (level == 1) 0.84 else 0.75
                for (i in 0 until frames) {
                    val l = input.short.toDouble(); val r = input.short.toDouble()
                    val m = (l + r) * 0.5; val sd = (l - r) * 0.5 * side
                    val y = b0 * m + b1 * x1 + b2 * x2 - a1 * y1 - a2 * y2
                    x2 = x1; x1 = m; y2 = y1; y1 = y
                    out.putShort(clip((y + sd) * pre)); out.putShort(clip((y - sd) * pre))
                }
            }
            out.flip()
        }
    }

    // ---- the sound comes and goes over a moment (leaving the app, coming back, a new channel): cut off at once it can click
    private var fader: android.animation.ValueAnimator? = null
    private var faded = true
    private fun fadeTo(v: Float, ms: Long) {
        fader?.cancel()
        val p = player ?: return
        fader = android.animation.ValueAnimator.ofFloat(p.volume, v).apply {
            duration = ms
            addUpdateListener { player?.volume = it.animatedValue as Float }
            start()
        }
    }
    override fun onPause() { super.onPause(); fadeTo(0f, 90) }
    override fun onResume() { super.onResume(); if (faded) fadeTo(1f, 220) }

    /** the channel that is on is played by another engine (an extra) */
    private var other = false
    /** something else plays (an extra): the app's own player waits */
    private var held = false

    private val plusHost = object : PlusHost {
        override val activity get() = this@MainActivity
        override val ui get() = this@MainActivity.ui
        override val prefs get() = this@MainActivity.prefs
        override val root get() = this@MainActivity.root
        override val main get() = this@MainActivity.main
        override fun library() = lib
        override fun current() = current
        override fun watch(key: String) {
            val l = lib ?: return
            val i = l.indexOf(key)
            if (i < 0 || Plus.hides(l.channels[i])) return
            if (page.isOpen) page.close()
            if (browsing) closeBrowse(false)
            hideGuide(); closeOptions()
            scopeName = Library.ALL; scope = listed(Library.ALL); prefs.scope = scopeName
            play(i)
        }
        override fun say(text: String) { this@MainActivity.say(text); if (text.isNotEmpty()) main.postDelayed({ if (note.text == text) this@MainActivity.say("") }, 3500) }
        override fun restyle() = this@MainActivity.restyle()
        override fun refocus() { if (page.isOpen) { page.refresh(); page.focus() } else if (guideBox.visibility == View.VISIBLE) guideList.requestFocus() else if (browsing) groupList.requestFocus() }
        override fun replay() { if (current >= 0) play(current, true) }
        override fun picture() { shade.visibility = View.GONE; firstPicture = true; this@MainActivity.say("") }
        override fun hold(on: Boolean) {
            held = on
            if (on) {
                if (other) { other = false; Plus.letGo(); player?.setVideoSurfaceView(surface) }
                if (page.isOpen) page.close()
                if (browsing) closeBrowse(false)
                hideGuide(); closeOptions()
                main.removeCallbacks(hideInfo); infoBar.alpha = 0f
                player?.stop()
                this@MainActivity.say("")
            }
        }
        override fun player(): ExoPlayer = ExoPlayer.Builder(this@MainActivity)
            .setLoadControl(androidx.media3.exoplayer.DefaultLoadControl.Builder().setBufferDurationsMs(10_000, 20_000, 800, 2_500).build()).build()
        override fun source(address: String): MediaSource {
            val item = MediaItem.fromUri(address)
            return if (Util.inferContentType(Uri.parse(address)) == C.CONTENT_TYPE_HLS)
                HlsMediaSource.Factory(http).setLoadErrorHandlingPolicy(patient).setExtractorFactory(DefaultHlsExtractorFactory(tsFlags, true)).createMediaSource(item)
            else DefaultMediaSourceFactory(http, DefaultExtractorsFactory().setTsExtractorFlags(tsFlags)).setLoadErrorHandlingPolicy(patient).createMediaSource(item)
        }
        override fun favourites(): List<Int> = listed(Library.FAVS)
        override fun recent(): List<Int> = listed(Library.RECENT)
        override fun relist() {
            val l = lib ?: return
            scope = listed(scopeName).ifEmpty { scopeName = Library.ALL; listed(Library.ALL) }
            if (browsing) { groupRows = groupsNow(); choose(shownName, false) }
            if (page.isOpen) page.refresh()
            publishHome()
            // (the channel that plays is one that is no longer shown: the first that is)
            if (current in l.channels.indices && Plus.hides(l.channels[current])) scope.firstOrNull()?.let { play(it) }
        }
    }

    private fun zap(step: Int) {
        val l = lib ?: return
        if (scope.size < 2 && current in scope) return
        play(l.step(scope, current, if (prefs.upIsNext) step else -step))
    }

    private val listener = object : Player.Listener {
        override fun onPlaybackStateChanged(state: Int) {
            val name = when (state) { Player.STATE_BUFFERING -> "buffering"; Player.STATE_READY -> "ready"; Player.STATE_ENDED -> "ended"; else -> "idle" }
            Log.i(tag, "#${current + 1} $name after ${SystemClock.elapsedRealtime() - startedAt} ms")
            if (state == Player.STATE_BUFFERING && firstPicture && slowPieces >= 2) saySlow()
            if (state == Player.STATE_READY && !faded) { faded = true; fadeTo(1f, 220) }
        }
        override fun onRenderedFirstFrame() {
            shade.visibility = View.GONE
            if (!sleepWarned) say("")
            firstPicture = true
            Log.i(tag, "#${current + 1} first picture after ${SystemClock.elapsedRealtime() - startedAt} ms")
        }
        override fun onVideoSizeChanged(v: VideoSize) {
            Log.i(tag, "#${current + 1} picture ${v.width}x${v.height}")
            if (v.width > 0 && v.height > 0) {
                val r = v.width * (if (v.pixelWidthHeightRatio > 0f) v.pixelWidthHeightRatio else 1f) / v.height
                if (kotlin.math.abs(r - videoAspect) > 0.01f) { videoAspect = r; placeSurface() }
            }
        }
        override fun onTracksChanged(t: Tracks) {
            val parts = ArrayList<String>()
            // what the device cannot play is said once, with its kind: such a track is silently left out otherwise
            for (g in t.groups) for (i in 0 until g.length) if (!g.isTrackSupported(i)) {
                val f = g.getTrackFormat(i)
                Log.w(tag, "#${current + 1} this device cannot play ${f.sampleMimeType} ${f.codecs.orEmpty()} ${if (f.channelCount > 0) "${f.channelCount}ch" else ""} ${if (f.sampleRate > 0) "${f.sampleRate}Hz" else ""} (support ${g.getTrackSupport(i)})")
            }
            for (g in t.groups) for (i in 0 until g.length) if (g.isTrackSelected(i)) {
                val f = g.getTrackFormat(i)
                val what = when (g.type) { C.TRACK_TYPE_VIDEO -> "video"; C.TRACK_TYPE_AUDIO -> "audio"; C.TRACK_TYPE_TEXT -> "text"; else -> "other" }
                Log.i(tag, "#${current + 1} $what ${f.sampleMimeType} ${if (f.width > 0) "${f.width}x${f.height}" else ""} ${if (f.channelCount > 0) "${f.channelCount}ch" else ""} ${f.language.orEmpty()} supported=${g.isTrackSupported(i)}")
                val kind = when (f.sampleMimeType) {
                    "video/avc" -> "H.264"; "video/hevc" -> "H.265"; "video/mpeg2" -> "MPEG-2"
                    "audio/mp4a-latm" -> "AAC"; "audio/mpeg-L2" -> "MPEG audio"; "audio/mpeg" -> "MP3"; "audio/ac3" -> "Dolby Digital"; "audio/eac3" -> "Dolby Digital Plus"
                    else -> null
                }
                if (g.type == C.TRACK_TYPE_VIDEO && f.frameRate > 0f) gotRate(f.frameRate, "declared")
                if (g.type == C.TRACK_TYPE_VIDEO && f.width > 0) parts += "${f.width} x ${f.height}" + (if (kind != null) "  $kind" else "")
                else if (g.type == C.TRACK_TYPE_AUDIO && kind != null) parts += kind
            }
            formatLine = parts.joinToString("  ·  ")
            if (infoBar.alpha > 0f) fillInfo()
            val sounds = tracks(C.TRACK_TYPE_AUDIO).size; val subs = tracks(C.TRACK_TYPE_TEXT).size
            if (sounds > 1 || subs > 0) Log.i(tag, "#${current + 1} tracks: $sounds sound, $subs subtitles")
            // a channel sent without any sound (seen on a 4K channel for a time): said, so that it is not taken for a fault of the player
            if (sounds == 0 && t.groups.none { it.type == C.TRACK_TYPE_AUDIO } && t.groups.any { it.type == C.TRACK_TYPE_VIDEO }) {
                Log.w(tag, "#${current + 1} is sent without sound")
                noSound = true
            }
            if (optionsOpen) refreshChips()
            page.refresh()
        }
        override fun onCues(cueGroup: CueGroup) { cues.show(cueGroup.cues) }
        override fun onPlayerError(e: PlaybackException) {
            // (the message may hold the stream's address: only the kind of error is logged)
            // what the server answered and to which kind of request (the list of pieces, or a piece), without the address
            val http = (e.cause as? androidx.media3.datasource.HttpDataSource.InvalidResponseCodeException)?.let { x ->
                val path = x.dataSpec.uri.path.orEmpty()
                " HTTP ${x.responseCode} for " + (if (path.endsWith(".m3u8") || path.endsWith(".m3u")) "the list" else "a piece") + " at byte ${x.dataSpec.position}"
            }.orEmpty()
            // A live channel whose playing fell behind what the server still keeps (a connection slower than the channel,
            // a long pause): there is nothing wrong with the channel, it goes on from what is on now. Never given up on.
            if (e.errorCode == PlaybackException.ERROR_CODE_BEHIND_LIVE_WINDOW && shiftAt == 0L) {
                Log.w(tag, "#${current + 1} fell behind what the server keeps: going on from what is on now")
                if (slowPieces >= 2) saySlow()
                play(current, true, browsing)
                return
            }
            Log.w(tag, "#${current + 1} error ${e.errorCodeName} (${e.cause?.javaClass?.simpleName})$http" + (if (retries < (if (e.errorCode in 2000..2999) 5 else 2)) ", trying again" else ", trying again every 20 s"))
            // a decoder that stumbles while channels are changed, or a live stream that moved on, plays at the next try;
            // the second try is without the hardware keeping step, in case that is what it stumbles over
            // (a server or a network that does not answer gets more tries, further apart, and the screen says what goes on)
            val net = e.errorCode in 2000..2999
            if (retries < (if (net) 5 else 2)) {
                retries++
                if (retries == 2 && tunnel) noTunnel.add(current)
                if (net && retries >= 2) { shade.visibility = View.VISIBLE; say("The channel's server is not answering.\nTrying again \u2026") }
                main.postDelayed({ play(current, true, browsing) }, if (net) 1200L * retries else if (retries == 1) 0L else 300L * retries)
                return
            }
            // (the last picture of the channel before would stay under the words)
            shade.visibility = View.VISIBLE
            say(if (shiftAt > 0L) "This channel has no recording of that time.\nBack returns to what is on now." else if (net) "This channel's server is not delivering right now.\nThe app keeps trying." else "This channel does not play right now.\nThe app keeps trying.")
            // (whatever it was that failed, the channel is tried again every 20 s for as long as it stays on)
            if (shiftAt == 0L) later()
        }
    }

    // ---------------------------------------------------------------- where the picture is

    /** the picture's size on the channel that plays, see [PICTURE] */
    private var pictureMode = 0

    /** the whole screen, or the small window of the channel list (there the picture always keeps its shape) */
    private fun placeSurface() {
        val dm = resources.displayMetrics
        val boxX: Int; val boxY: Int; val boxW: Int; val boxH: Int
        if (browsing) { boxX = u(MINI_X); boxY = u(MINI_Y); boxW = u(MINI_W); boxH = u(MINI_W) * 9 / 16 }
        else { boxX = 0; boxY = 0; boxW = dm.widthPixels; boxH = dm.heightPixels }
        val mode = if (browsing) 0 else pictureMode
        val shape = when (mode) { 3 -> 16f / 9f; 4 -> 4f / 3f; else -> videoAspect }
        var w = boxW; var h = (boxW / shape).toInt()
        when (mode) {
            2 -> h = boxH                                                                  // stretched over the screen
            1 -> if (h < boxH) { h = boxH; w = (boxH * shape).toInt() }                    // fills the screen, its edges are cut
            else -> if (h > boxH) { h = boxH; w = (boxH * shape).toInt() }                 // all of it, with bars where it does not reach
        }
        val lp = surface.layoutParams as FrameLayout.LayoutParams
        lp.width = w; lp.height = h
        lp.leftMargin = boxX + (boxW - w) / 2
        lp.topMargin = boxY + (boxH - h) / 2
        surface.layoutParams = lp
        // subtitles lie over the part of the picture that is on the screen
        val cp = cues.layoutParams as FrameLayout.LayoutParams
        cp.width = minOf(w, boxW); cp.height = minOf(h, boxH)
        cp.leftMargin = maxOf(lp.leftMargin, boxX); cp.topMargin = maxOf(lp.topMargin, boxY)
        cues.layoutParams = cp
        val sp = shade.layoutParams as FrameLayout.LayoutParams
        sp.width = boxW; sp.height = boxH; sp.leftMargin = boxX; sp.topMargin = boxY
        shade.layoutParams = sp
        // what there is to say (a channel that does not play ...) is said where the picture would be
        val np = note.layoutParams as FrameLayout.LayoutParams
        if (browsing) { np.gravity = Gravity.TOP or Gravity.START; np.width = boxW; np.leftMargin = boxX; np.topMargin = boxY + boxH / 2 - u(20) }
        else { np.gravity = Gravity.CENTER; np.width = -1; np.leftMargin = 0; np.topMargin = 0 }
        note.layoutParams = np
        note.setPadding(if (browsing) u(24) else u(80), 0, if (browsing) u(24) else u(80), 0)
    }

    // ---------------------------------------------------------------- what plays (the bar at the bottom)

    private lateinit var infoBar: LinearLayout
    private lateinit var infoLogo: ImageView
    private lateinit var infoName: TextView
    private lateinit var infoNow: TextView
    private lateinit var infoNext: TextView
    private lateinit var infoClock: TextView
    private lateinit var infoTimes: LinearLayout
    private lateinit var infoStart: TextView
    private lateinit var infoStop: TextView
    private lateinit var infoLeft: TextView
    private lateinit var infoProg: Bar
    private lateinit var infoHint: TextView
    private lateinit var infoTech: TextView
    private lateinit var chipRow: LinearLayout
    private val chips = ArrayList<TextView>()
    /** the bar is up with its three choices (sound, subtitles, picture size) to step through */
    private var optionsOpen = false
    private val hideInfo = Runnable { if (optionsOpen) closeOptions() else if (!paused) infoBar.animate().alpha(0f).setDuration(350).start() }

    private fun buildInfo() {
        infoBar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = GradientDrawable(GradientDrawable.Orientation.BOTTOM_TOP, intArrayOf(Color.argb(252, 6, 9, 18), Color.argb(236, 6, 9, 18), Color.argb(150, 6, 9, 18), Color.TRANSPARENT))
            setPadding(u(44), u(52), u(44), u(24))
            alpha = 0f
        }
        infoLogo = ImageView(this).apply { scaleType = ImageView.ScaleType.FIT_CENTER }
        infoBar.addView(infoLogo, LinearLayout.LayoutParams(u(58), u(58)))
        val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        infoName = ui.label(19f, Color.WHITE, true)
        infoNow = ui.label(13.5f, white(235))
        col.addView(infoName)
        col.addView(infoNow, LinearLayout.LayoutParams(-1, -2).apply { topMargin = u(2) })
        // how far the programme has run: when it began, the bar, when the next one begins, how long that is from now
        infoTimes = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        infoStart = ui.label(11f, white(170))
        infoProg = Bar(this)
        infoStop = ui.label(11f, white(170))
        infoLeft = ui.label(11f, accent, true)
        infoTimes.addView(infoStart)
        infoTimes.addView(infoProg, LinearLayout.LayoutParams(u(330), maxOf(4, u(3))).apply { leftMargin = u(9); rightMargin = u(9) })
        infoTimes.addView(infoStop)
        infoTimes.addView(infoLeft, LinearLayout.LayoutParams(-2, -2).apply { leftMargin = u(14) })
        col.addView(infoTimes, LinearLayout.LayoutParams(-2, -2).apply { topMargin = u(5) })
        infoNext = ui.label(11.5f, white(160))
        col.addView(infoNext, LinearLayout.LayoutParams(-1, -2).apply { topMargin = u(5) })
        // what the channel is made of (see [techLine])
        infoTech = ui.label(10.5f, white(150))
        col.addView(infoTech, LinearLayout.LayoutParams(-1, -2).apply { topMargin = u(6) })
        infoHint = ui.label(10f, white(105)).apply { text = "Hold OK   sound, subtitles, picture size" }
        col.addView(infoHint, LinearLayout.LayoutParams(-1, -2).apply { topMargin = u(5) })
        chipRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; visibility = View.GONE }
        for (i in 0..7) {
            val t = ui.label(11.5f, Color.WHITE, true).apply {
                isFocusable = true
                isClickable = true
                setPadding(u(11), u(5.5f), u(11), u(6))
            }
            fun paint(has: Boolean) {
                t.setTextColor(if (has) ui.ink else white(225))
                t.background = if (has) ui.rounded(accent, u(14)) else ui.rounded(white(22), u(14), u(1), white(40))
            }
            paint(false)
            t.setOnFocusChangeListener { _, has -> paint(has) }
            t.setOnClickListener { step(i); if (optionsOpen) { refreshChips(); main.removeCallbacks(hideInfo); main.postDelayed(hideInfo, 9000) } }
            chips += t
            chipRow.addView(t, LinearLayout.LayoutParams(-2, -2).apply { rightMargin = u(6) })
        }
        col.addView(chipRow, LinearLayout.LayoutParams(-2, -2).apply { topMargin = u(9) })
        infoBar.addView(col, LinearLayout.LayoutParams(0, -2, 1f).apply { leftMargin = u(18) })
        infoClock = ui.label(22f, white(235)).apply { typeface = Typeface.create("sans-serif-light", Typeface.NORMAL) }
        infoBar.addView(infoClock)
        for (t in arrayOf(infoName, infoNow, infoStart, infoStop, infoLeft, infoNext, infoHint, infoClock)) t.setShadowLayer(u(3).toFloat(), 0f, 0f, Color.BLACK)
        root.addView(infoBar, FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM))
    }

    private fun fillInfo() {
        val l = lib ?: return
        val c = l.channels.getOrNull(current) ?: return
        val now = System.currentTimeMillis()
        // a recording (or a paused channel) is told about as of its own moment, not as of now
        val at = when { shiftSeekTo > 0L -> shiftSeekTo; shiftAt > 0L -> shiftAt; paused -> pausedAt; else -> now }
        val behind = at < now - 15_000
        infoName.text = "${current + 1}   ${c.name}"
        Images.show(Repo.logo(current), infoLogo, u(58))
        val (cur, next) = if (!behind) Repo.nowNext(current, now) else Guide.nowNext(if (shiftProgsFor == current) shiftProgs else null, at)
        infoNow.text = cur?.title ?: if (behind) "Recorded" else c.group
        infoTimes.visibility = if (cur != null || behind || paused) View.VISIBLE else View.GONE
        infoStart.visibility = if (cur != null) View.VISIBLE else View.GONE
        infoStop.visibility = infoStart.visibility; infoProg.visibility = infoStart.visibility
        if (cur != null) {
            infoStart.text = hm(cur.start); infoStop.text = hm(cur.stop)
            infoProg.f = Guide.progress(cur, at)
        }
        infoLeft.text = when {
            behind -> (if (paused) "PAUSED" else "RECORDED") + "   " + dayHm(at) + "   \u00B7   " + agoText(((now - at) / 60_000).toInt()) + " behind"
            paused -> "PAUSED"
            cur != null -> leftText(((cur.stop - now + 59_999) / 60_000).toInt())
            else -> ""
        }
        infoNext.text = if (next != null) "Next  " + hm(next.start) + "   " + next.title else ""
        infoNext.visibility = if (next != null) View.VISIBLE else View.GONE
        techLine().let { infoTech.text = it; infoTech.visibility = if (it.isEmpty()) View.GONE else View.VISIBLE }
        infoHint.text = when {
            behind || paused -> "\u25C0 \u25B6  one minute        OK  " + (if (paused) "play" else "pause") + "        BACK  what is on now        HOLD OK  more"
            canShift() -> "Hold OK   pause, start over, sound, subtitles, picture size"
            else -> "Hold OK   sound, subtitles, picture size"
        }
        infoClock.text = hm(now)
    }

    private fun agoText(min: Int): String = when {
        min < 1 -> "moments"
        min < 60 -> "$min min"
        min < 48 * 60 -> if (min % 60 == 0) "${min / 60} h" else "${min / 60} h ${min % 60} min"
        else -> "${min / 1440} days"
    }

    /** a time of today as 19:30, of another day as Sat 19:30 */
    private fun dayHm(t: Long): String {
        val day = SimpleDateFormat("yyyyMMdd", Locale.US)
        return if (day.format(Date(t)) == day.format(Date())) hm(t) else SimpleDateFormat("EEE HH:mm", Locale.getDefault()).format(Date(t))
    }

    private fun showInfo(ms: Long = 5000) {
        fillInfo()
        if (optionsOpen) return
        infoBar.animate().cancel(); infoBar.alpha = 1f
        // (it stays up while the channel is paused)
        main.removeCallbacks(hideInfo); if (!paused) main.postDelayed(hideInfo, ms)
    }

    // ---------------------------------------------------------------- recordings: what was on earlier, start over, pause

    /** 0 = what is on now; else the moment (clock time) of the recording that plays, moving on as it plays */
    private var shiftAt = 0L
    /** where Left / Right are moving to, until the keys rest and it is played (0 = nowhere) */
    private var shiftSeekTo = 0L
    private var shiftLast = 0L
    private var paused = false
    /** the moment of the picture that stands still, and when it was stopped */
    private var pausedAt = 0L
    private var pausedSince = 0L
    /** the programmes of the days before, of the channel [shiftProgsFor] */
    private var shiftProgs: List<Guide.Prog> = emptyList()
    private var shiftProgsFor = -1

    /** is what the channel that plays showed earlier kept? */
    private fun canShift() = testStream == null && prefs.archive && lib?.channels?.getOrNull(current)?.rec != null

    private fun loadShiftProgs(i: Int) {
        Thread({
            val list = Repo.programmes(this, i, past = true)
            main.post { shiftProgs = list; shiftProgsFor = i; if (infoBar.alpha > 0f && current == i) fillInfo() }
        }, "channels-earlier").start()
    }

    /** the recording's moment moves on while it plays; when it has caught up with now, the channel itself takes over */
    private val shiftTick = object : Runnable {
        override fun run() {
            val p = player ?: return
            if (shiftAt <= 0L) return
            val e = SystemClock.elapsedRealtime()
            if (p.isPlaying) shiftAt += e - shiftLast
            shiftLast = e
            if (shiftAt >= System.currentTimeMillis() - 15_000) { play(current); return }
            if (infoBar.alpha > 0f && shiftSeekTo == 0L) fillInfo()
            main.postDelayed(this, 1000)
        }
    }

    private val shiftGo = Runnable {
        val to = shiftSeekTo
        shiftSeekTo = 0L
        if (to > 0L) { if (to >= System.currentTimeMillis() - 20_000) play(current) else play(current, at = to) }
    }

    /** Left / Right: back or on by [delta]; played once the keys rest */
    private fun shiftSeek(delta: Long) {
        val rule = lib?.channels?.getOrNull(current)?.rec
        if (rule == null || !canShift()) return
        val now = System.currentTimeMillis()
        val base = when { shiftSeekTo > 0L -> shiftSeekTo; shiftAt > 0L -> shiftAt; paused -> pausedAt; else -> now }
        shiftSeekTo = (base + delta).coerceIn(now - rule.days * 86_400_000L + 120_000, now)
        if (shiftProgsFor != current) loadShiftProgs(current)
        showInfo(6000)
        main.removeCallbacks(shiftGo); main.postDelayed(shiftGo, 700)
    }

    private fun togglePause() {
        val p = player ?: return
        if (!paused) {
            // (a channel that is not recorded could not be taken up again where it stopped)
            if (!canShift() || current < 0) return
            paused = true
            pausedAt = if (shiftAt > 0L) shiftAt else System.currentTimeMillis()
            pausedSince = SystemClock.elapsedRealtime()
            if (other) Plus.otherPause(true) else p.pause()
            if (shiftProgsFor != current) loadShiftProgs(current)
            showInfo()
        } else {
            val stood = SystemClock.elapsedRealtime() - pausedSince
            // after a moment's pause what was fetched plays on; after a longer one it is asked for again from where it stopped
            val back = prefs.resumeBack * 1000L
            if (stood < 8000 && back == 0L) { paused = false; if (other) Plus.otherPause(false) else p.play(); showInfo() } else play(current, at = pausedAt - back)
        }
    }

    /** the programme that is on, from its beginning */
    private fun startOver() {
        val cur = Repo.nowNext(current, System.currentTimeMillis()).first ?: return
        if (canShift()) play(current, at = cur.start)
    }

    // ---------------------------------------------------------------- the channel before

    /** the channel that is being watched (not one that is only looked at in the small window), and the one before it */
    private var watchingIdx = -1
    private var lastChannel = -1

    private fun commit(i: Int) {
        if (i == watchingIdx) return
        if (watchingIdx >= 0) { lastChannel = watchingIdx; lib?.let { if (testStream == null) prefs.prev = it.keyOf(lastChannel) } }
        watchingIdx = i
    }

    /** Back: to the channel that was on before this one. False when there is none. */
    private fun swapLast(): Boolean {
        val l = lib ?: return false
        if (testStream != null) return false
        val i = if (lastChannel >= 0) lastChannel else l.indexOf(prefs.prev)
        if (i !in l.channels.indices || i == current) return false
        play(i)
        return true
    }

    // ---------------------------------------------------------------- sound, subtitles, picture size (hold OK while watching)

    private class Track(val group: Tracks.Group, val index: Int, val format: Format, val selected: Boolean)

    /** the tracks of one kind the channel has and this device can play */
    private fun tracks(type: Int): List<Track> {
        val out = ArrayList<Track>()
        val t = player?.currentTracks ?: return out
        for (g in t.groups) if (g.type == type) for (i in 0 until g.length) if (g.isTrackSupported(i)) out += Track(g, i, g.getTrackFormat(i), g.isTrackSelected(i))
        return out
    }

    /** how a track reads: its language, the name the channel gives it, else its number */
    private fun named(f: Format, n: Int): String {
        val code = f.language.orEmpty()
        val lang = if (code.isEmpty() || code == "und") "" else Locale.forLanguageTag(code).displayLanguage.replaceFirstChar { it.uppercase() }
        val label = f.label.orEmpty()
        return when {
            label.isNotEmpty() && lang.isNotEmpty() && !label.equals(lang, true) -> "$lang ($label)"
            label.isNotEmpty() -> label
            lang.isNotEmpty() -> lang
            else -> "Track $n"
        }
    }

    private fun chipText(i: Int): String = when (i) {
        0 -> if (paused) "\u25B6  Play" else "\u275A\u275A  Pause"
        1 -> if (shiftAt > 0L || paused) "What is on now" else "Start over"
        2 -> {
            val all = tracks(C.TRACK_TYPE_AUDIO)
            val at = all.indexOfFirst { it.selected }
            if (all.size < 2 && sounds.size > 1) {
                // several sounds inside the picture stream: the player knows the one that is read only
                val cur = if (pick < 0) 0 else pick.coerceAtMost(sounds.size - 1)
                val x = sounds[cur]
                val twin = sounds.any { it !== x && soundName(it, 0) == soundName(x, 0) }
                val ch = all.firstOrNull()?.format?.channelCount ?: 0
                val kind = when (ch) { 1 -> "mono"; 2 -> "stereo"; 6 -> "5.1"; 8 -> "7.1"; else -> "" }
                "Sound   " + soundName(x, cur + 1) + (if (kind.isNotEmpty()) " \u00B7 $kind" else "") + (if (twin && soundKind(x.type).isNotEmpty()) " \u00B7 " + soundKind(x.type) else "") + "   ${cur + 1} of ${sounds.size}"
            } else if (all.isEmpty() || at < 0) "Sound   \u2014" else {
                val f = all[at].format
                val kind = when (f.channelCount) { 1 -> "mono"; 2 -> "stereo"; 6 -> "5.1"; 8 -> "7.1"; else -> "" }
                // (tracks that read alike, as two in one language do, are told apart by how they are coded)
                val twin = all.any { it !== all[at] && named(it.format, 0) == named(f, 0) && it.format.channelCount == f.channelCount }
                val coded = if (!twin) "" else when (f.sampleMimeType) {
                    "audio/mp4a-latm" -> "AAC"; "audio/mpeg-L2" -> "MPEG"; "audio/mpeg" -> "MP3"; "audio/ac3" -> "Dolby Digital"; "audio/eac3" -> "Dolby Digital Plus"; else -> ""
                }
                "Sound   " + named(f, at + 1) + (if (kind.isNotEmpty()) " \u00B7 $kind" else "") + (if (coded.isNotEmpty()) " \u00B7 $coded" else "") + (if (all.size > 1) "   ${at + 1} of ${all.size}" else "")
            }
        }
        3 -> {
            val all = tracks(C.TRACK_TYPE_TEXT)
            val at = all.indexOfFirst { it.selected }
            "Subtitles   " + when { all.isEmpty() -> "none"; at < 0 -> "Off"; else -> named(all[at].format, at + 1) + (if (all.size > 1) "   ${at + 1} of ${all.size}" else "") }
        }
        4 -> "Picture   " + PICTURE[pictureMode]
        5 -> "Lip sync   " + (if (prefs.soundDelay == 0) "0" else (if (prefs.soundDelay > 0) "sound +" else "sound \u2212") + kotlin.math.abs(prefs.soundDelay) + " ms") + "   \u25B2\u25BC"
        7 -> Plus.engineChip(playKey).orEmpty()
        else -> "Sleep   " + if (sleepAt == 0L) "off" else "${((sleepAt - SystemClock.elapsedRealtime() + 59_999) / 60_000).coerceAtLeast(1)} min"
    }

    private fun refreshChips() {
        for ((i, c) in chips.withIndex()) c.text = (if (other) Plus.otherChip(i) else null) ?: chipText(i)
        // pausing and starting over need the channel to be recorded
        val rec = canShift()
        chips[0].visibility = if (rec) View.VISIBLE else View.GONE
        chips[1].visibility = if (rec && (shiftAt > 0L || paused || Repo.nowNext(current, System.currentTimeMillis()).first != null)) View.VISIBLE else View.GONE
        // (with the hardware keeping step there is nothing to set)
        chips[5].visibility = if (tunnel) View.GONE else View.VISIBLE
        chips[7].visibility = if (Plus.engineChip(playKey) != null) View.VISIBLE else View.GONE
    }

    /** OK on a choice goes to its next value */
    private fun step(i: Int) {
        if (other && Plus.otherStep(i)) return
        val sel = selector ?: return
        when (i) {
            0 -> { closeOptions(); togglePause() }
            1 -> { closeOptions(); if (shiftAt > 0L || paused) play(current) else startOver() }
            2 -> {
                val all = tracks(C.TRACK_TYPE_AUDIO)
                if (all.size < 2 && sounds.size > 1 && playKey.isNotEmpty()) {
                    // the next of the sounds inside the picture stream: the channel is read again for that one, and its
                    // language is the one looked for on other channels from now on
                    val next = ((if (pick < 0) 0 else pick) + 1) % sounds.size
                    soundPick[playKey] = next
                    langOf(sounds[next].lang).let { if (it.isNotEmpty()) prefs.audioLang = it }
                    Log.i(tag, "#${current + 1} sound ${next + 1} of ${sounds.size} chosen")
                    play(current, true, browsing)
                    refreshChips()
                    return
                }
                if (all.size < 2) return
                val next = all[(all.indexOfFirst { it.selected } + 1) % all.size]
                val lang = next.format.language.orEmpty()
                if (testStream == null) prefs.audioLang = lang
                sel.setParameters(sel.buildUponParameters().setOverrideForType(TrackSelectionOverride(next.group.mediaTrackGroup, next.index)).setPreferredAudioLanguage(lang.ifEmpty { null }))
            }
            3 -> {
                val all = tracks(C.TRACK_TYPE_TEXT)
                if (all.isEmpty()) return
                val at = all.indexOfFirst { it.selected } + 1
                if (at >= all.size) {
                    if (testStream == null) prefs.subLang = ""
                    sel.setParameters(sel.buildUponParameters().clearOverridesOfType(C.TRACK_TYPE_TEXT).setTrackTypeDisabled(C.TRACK_TYPE_TEXT, true).setPreferredTextLanguage(null))
                    cues.show(emptyList())
                } else {
                    val next = all[at]
                    val lang = next.format.language.orEmpty().ifEmpty { "und" }
                    if (testStream == null) prefs.subLang = lang
                    sel.setParameters(sel.buildUponParameters().setTrackTypeDisabled(C.TRACK_TYPE_TEXT, false)
                        .setOverrideForType(TrackSelectionOverride(next.group.mediaTrackGroup, next.index)).setPreferredTextLanguage(lang))
                }
            }
            5 -> say("Up plays the sound later (for a picture that is late),\nDown plays it earlier.").also { main.postDelayed({ if (optionsOpen) say("") }, 3500) }
            4 -> {
                pictureMode = (pictureMode + 1) % PICTURE.size
                lib?.let { if (testStream == null && current >= 0) prefs.setPicture(it.keyOf(current), pictureMode) }
                placeSurface()
            }
            7 -> { Plus.engineStep(playKey); closeOptions(); play(current, true) }
            else -> setSleep(when (sleepMin) { 0 -> 30; 30 -> 60; 60 -> 90; else -> 0 })
        }
    }

    // ---- the sleep timer: after so many minutes playback stops and the app is left; a minute before, it says so
    private var sleepMin = 0
    private var sleepAt = 0L
    private var sleepWarned = false
    private val sleepWarn = Runnable {
        sleepWarned = true
        say("Sleep timer: stopping in a minute.\nPress any key to keep watching.")
        main.postDelayed(sleepStop, 60_000)
    }
    private val sleepStop = Runnable {
        sleepWarned = false; sleepMin = 0; sleepAt = 0L
        say("")
        player?.pause()
        moveTaskToBack(true)
    }
    private fun setSleep(min: Int) {
        main.removeCallbacks(sleepWarn); main.removeCallbacks(sleepStop)
        if (sleepWarned) say("")
        sleepWarned = false
        sleepMin = min
        sleepAt = if (min == 0) 0L else SystemClock.elapsedRealtime() + min * 60_000L
        if (min > 0) main.postDelayed(sleepWarn, (min - 1) * 60_000L)
    }

    private fun openOptions() {
        if (lib == null || current < 0 || optionsOpen) return
        optionsOpen = true
        fillInfo()
        refreshChips()
        infoHint.visibility = View.GONE
        chipRow.visibility = View.VISIBLE
        infoBar.animate().cancel(); infoBar.alpha = 1f
        (chips.firstOrNull { it.visibility == View.VISIBLE } ?: chips[2]).requestFocus()
        main.removeCallbacks(hideInfo); main.postDelayed(hideInfo, 9000)
    }

    private fun closeOptions() {
        if (!optionsOpen) return
        optionsOpen = false
        main.removeCallbacks(hideInfo)
        chipRow.visibility = View.GONE
        infoHint.visibility = View.VISIBLE
        if (paused) fillInfo() else infoBar.animate().alpha(0f).setDuration(250).start()
    }

    // ---------------------------------------------------------------- the channel window

    private lateinit var backdrop: View
    private lateinit var browse: LinearLayout
    private lateinit var groupList: ListView
    private lateinit var chanList: ListView
    private lateinit var chanHint: TextView
    private lateinit var dLogo: ImageView
    private lateinit var dName: TextView
    private lateinit var dNow: TextView
    private lateinit var dNum: TextView
    private lateinit var dTimes: LinearLayout
    private lateinit var dStart: TextView
    private lateinit var dStop: TextView
    private lateinit var dLeft: TextView
    private lateinit var headList: TextView
    private lateinit var miniTag: TextView
    private lateinit var headClock: TextView
    /** how many channels each list of the left column has */
    private var groupCounts: Map<String, Int> = emptyMap()
    private lateinit var dBar: Bar
    private lateinit var dDesc: TextView
    private lateinit var dLaterCaps: TextView
    private lateinit var dLater: TextView
    private var browsing = false
    /** the channel that was on when the window was opened: Back returns to it */
    private var before = -1
    /** the entries of the left column: what it is (a list's name, or "search" / "settings") and how it reads */
    private var groupRows: List<Pair<String, String>> = emptyList()
    private var shownName = Library.ALL
    private var shown: List<Int> = emptyList()
    /** the channel the right side tells about */
    private var detailsFor = -1

    private fun list(): ListView = ListView(this).apply {
        divider = null
        isVerticalScrollBarEnabled = false
        overScrollMode = View.OVER_SCROLL_NEVER
        // only the column that has the focus shows where it is
        val on = ui.rounded(white(30), u(12), maxOf(2, u(1.5f)), accent)
        val off = ui.rounded(Color.TRANSPARENT, 0)
        selector = off
        setOnFocusChangeListener { _, has -> selector = if (has) on else off; invalidateViews() }
        setDrawSelectorOnTop(false)
        clipToPadding = false
        setPadding(0, u(8), 0, u(8))
        // rows fade out at the top and bottom edge instead of being cut
        isVerticalFadingEdgeEnabled = true
        setFadingEdgeLength(u(22))
    }

    private class GroupRow(val mark: View, val name: TextView, val count: TextView)

    private val groupAdapter = object : BaseAdapter() {
        override fun getCount() = groupRows.size
        override fun getItem(p: Int) = groupRows[p]
        override fun getItemId(p: Int) = p.toLong()
        // (entries whose id begins with # are the small headings between the lists: the focus passes over them)
        override fun areAllItemsEnabled() = false
        override fun isEnabled(p: Int) = !groupRows[p].first.startsWith("#")
        override fun getViewTypeCount() = 2
        override fun getItemViewType(p: Int) = if (groupRows[p].first.startsWith("#")) 1 else 0
        override fun getView(p: Int, old: View?, parent: ViewGroup): View {
            val (id, name) = groupRows[p]
            if (id.startsWith("#")) {
                val t = (old as? TextView) ?: ui.caps("", white(95)).apply { setPadding(u(15), u(13), 0, u(5)) }
                t.text = name
                return t
            }
            val row = old ?: LinearLayout(this@MainActivity).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(u(6), u(7), u(10), u(7.5f))
                val mark = View(context).apply { background = ui.rounded(accent, u(2)) }
                addView(mark, LinearLayout.LayoutParams(u(3), u(13)))
                val n = ui.label(12.5f, Color.WHITE)
                addView(n, LinearLayout.LayoutParams(0, -2, 1f).apply { leftMargin = u(7) })
                val cnt = ui.label(9.5f, white(95))
                addView(cnt, LinearLayout.LayoutParams(-2, -2).apply { leftMargin = u(6) })
                tag = GroupRow(mark, n, cnt)
            }
            val h = row.tag as GroupRow
            // the list whose channels are shown stays marked while the focus is on the channels
            val shownHere = id == shownName
            h.mark.visibility = if (shownHere) View.VISIBLE else View.INVISIBLE
            h.name.text = name
            h.name.setTextColor(if (shownHere) accent else if (id == "search" || id == "settings" || id == "source") white(185) else white(235))
            h.name.typeface = Typeface.create(if (shownHere) "sans-serif-medium" else "sans-serif", Typeface.NORMAL)
            h.count.text = Plus.groupNote(id) ?: groupCounts[id]?.let { String.format(Locale.US, "%,d", it) } ?: ""
            return row
        }
    }

    /** one line a channel in the list (an extra) */
    private var compact = false
    private class ChanRow(val num: TextView, val logo: ImageView, val name: TextView, val now: TextView, val bar: View, val star: TextView)

    private val chanAdapter = object : BaseAdapter() {
        override fun getCount() = shown.size
        override fun getItem(p: Int) = shown[p]
        override fun getItemId(p: Int) = shown[p].toLong()
        override fun getView(p: Int, old: View?, parent: ViewGroup): View {
            val row = old ?: LinearLayout(this@MainActivity).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(u(9), u(7), u(12), u(7))
                val logo = ImageView(context).apply {
                    scaleType = ImageView.ScaleType.FIT_CENTER
                    background = ui.rounded(white(14), u(9))
                    setPadding(u(4), u(4), u(4), u(4))
                }
                if (compact) setPadding(u(9), u(3.5f), u(12), u(3.5f))
                addView(logo, if (compact) LinearLayout.LayoutParams(u(27), u(27)) else LinearLayout.LayoutParams(u(40), u(40)))
                val col = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
                val name = ui.label(13.5f, Color.WHITE, true)
                val now = ui.label(10.5f, white(140))
                val bar = Bar(context).apply { fill = white(150); track = white(26) }
                col.addView(name)
                col.addView(now, LinearLayout.LayoutParams(-1, -2).apply { topMargin = u(1) })
                col.addView(bar, LinearLayout.LayoutParams(u(96), maxOf(2, u(2))).apply { topMargin = u(4.5f) })
                addView(col, LinearLayout.LayoutParams(0, -2, 1f).apply { leftMargin = u(12) })
                val side = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL; gravity = Gravity.END }
                val star = ui.label(11f, accent)
                val num = ui.label(9.5f, white(95))
                side.addView(star); side.addView(num)
                addView(side, LinearLayout.LayoutParams(-2, -2).apply { leftMargin = u(8) })
                tag = ChanRow(num, logo, name, now, bar, star)
            }
            val h = row.tag as ChanRow
            val l = lib ?: return row
            val i = shown[p]
            val c = l.channels[i]
            h.num.text = (i + 1).toString()
            h.name.text = c.name
            // the channel that was on when the window was opened stays marked
            h.name.setTextColor(if (i == before) accent else Color.WHITE)
            val t = System.currentTimeMillis()
            val cur = Repo.nowNext(i, t).first
            // (without a guide: the channel's group, where the list shown is not that group itself)
            h.now.text = cur?.title ?: if (shownName == c.group) "" else c.group.replaceFirstChar { it.titlecase() }
            (h.bar as Bar).f = if (cur != null) Guide.progress(cur, t) else -1f
            h.bar.visibility = if (cur != null) View.VISIBLE else View.GONE
            h.star.text = if (l.keyOf(i) in favs) "\u2605" else ""
            Images.show(Repo.logo(i), h.logo, u(40))
            if (compact) { h.now.visibility = View.GONE; h.bar.visibility = View.GONE }
            return row
        }
    }

    private fun buildBrowse() {
        val page = FrameLayout(this).apply { visibility = View.GONE }
        // across the top: what this is, which list is shown, the time
        val head = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.BOTTOM }
        head.addView(ui.label(21f, Color.WHITE).apply { text = "Channels"; typeface = Typeface.create("sans-serif-light", Typeface.NORMAL) })
        headList = ui.caps("", accent)
        head.addView(headList, LinearLayout.LayoutParams(0, -2, 1f).apply { leftMargin = u(16); bottomMargin = u(4) })
        headClock = ui.label(17f, white(215)).apply { typeface = Typeface.create("sans-serif-light", Typeface.NORMAL) }
        head.addView(headClock)
        page.addView(head, FrameLayout.LayoutParams(-1, u(MINI_Y - 12)).apply { leftMargin = u(SIDE_PAD + 8); rightMargin = u(SIDE_PAD) })

        browse = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        groupList = list().apply { adapter = groupAdapter }
        chanList = list().apply { adapter = chanAdapter }
        // the lists sit on a card of their own
        val card = FrameLayout(this).apply { background = ui.rounded(white(9), u(14), 1, white(16)); setPadding(u(4), 0, u(4), 0) }
        card.addView(groupList, FrameLayout.LayoutParams(-1, -1))
        browse.addView(card, LinearLayout.LayoutParams(u(GROUPS_W), -1))
        val mid = FrameLayout(this)
        mid.addView(chanList, FrameLayout.LayoutParams(-1, -1))
        chanHint = ui.label(12.5f, white(165), lines = 6).apply { setPadding(u(18), u(26), u(18), 0); setLineSpacing(u(3).toFloat(), 1f) }
        mid.addView(chanHint, FrameLayout.LayoutParams(-1, -2))
        browse.addView(mid, LinearLayout.LayoutParams(u(CHANS_W), -1).apply { leftMargin = u(GAP) })

        // the right side: the picture's window is left free (a thin frame is drawn round it), what the channel shows is told below it
        val miniH = u(MINI_W) * 9 / 16
        val right = FrameLayout(this)
        right.addView(object : View(this) {
            private val edge = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = maxOf(1.5f, u(1).toFloat()); color = white(42) }
            private val corner = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(10, 13, 26) }
            private val path = android.graphics.Path()
            // the picture itself has square corners: they are covered, so that it sits in a rounded window
            override fun onDraw(c: Canvas) {
                val w = width.toFloat(); val h = height.toFloat(); val r = u(14).toFloat()
                path.reset(); path.fillType = android.graphics.Path.FillType.EVEN_ODD
                path.addRect(0f, 0f, w, h, android.graphics.Path.Direction.CW)
                path.addRoundRect(0f, 0f, w, h, r, r, android.graphics.Path.Direction.CW)
                c.drawPath(path, corner)
                val in2 = edge.strokeWidth / 2
                c.drawRoundRect(in2, in2, w - in2, h - in2, r, r, edge)
            }
        }, FrameLayout.LayoutParams(-1, miniH))
        // which channel the small window shows, when that is not the one the focus is on
        miniTag = ui.label(9.5f, Color.WHITE, true).apply {
            letterSpacing = 0.06f
            background = ui.rounded(Color.argb(170, 6, 9, 18), u(9))
            setPadding(u(9), u(3.5f), u(9), u(4))
        }
        right.addView(miniTag, FrameLayout.LayoutParams(-2, -2).apply { leftMargin = u(10); topMargin = miniH - u(30); rightMargin = u(10) })
        val about = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val top = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        dLogo = ImageView(this).apply { scaleType = ImageView.ScaleType.FIT_CENTER; background = ui.rounded(white(14), u(8)); setPadding(u(3), u(3), u(3), u(3)) }
        top.addView(dLogo, LinearLayout.LayoutParams(u(30), u(30)))
        dName = ui.label(15.5f, Color.WHITE, true)
        top.addView(dName, LinearLayout.LayoutParams(0, -2, 1f).apply { leftMargin = u(10) })
        dNum = ui.label(10.5f, white(105))
        top.addView(dNum, LinearLayout.LayoutParams(-2, -2).apply { leftMargin = u(8) })
        about.addView(top)
        dNow = ui.label(13.5f, accent, true, lines = 2)
        about.addView(dNow, LinearLayout.LayoutParams(-1, -2).apply { topMargin = u(9) })
        dTimes = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        dStart = ui.label(10.5f, white(165))
        dBar = Bar(this)
        dStop = ui.label(10.5f, white(165))
        dLeft = ui.label(10.5f, accent, true)
        dTimes.addView(dStart)
        dTimes.addView(dBar, LinearLayout.LayoutParams(0, maxOf(3, u(2.5f)), 1f).apply { leftMargin = u(8); rightMargin = u(8) })
        dTimes.addView(dStop)
        dTimes.addView(dLeft, LinearLayout.LayoutParams(-2, -2).apply { leftMargin = u(12) })
        about.addView(dTimes, LinearLayout.LayoutParams(-1, -2).apply { topMargin = u(5) })
        dDesc = ui.label(11f, white(190), lines = 3).apply { setLineSpacing(u(2.5f).toFloat(), 1f) }
        about.addView(dDesc, LinearLayout.LayoutParams(-1, -2).apply { topMargin = u(8) })
        dLaterCaps = ui.caps("LATER", white(105))
        about.addView(dLaterCaps, LinearLayout.LayoutParams(-2, -2).apply { topMargin = u(10) })
        dLater = ui.label(11f, white(205), lines = 3).apply { setLineSpacing(u(3).toFloat(), 1f) }
        about.addView(dLater, LinearLayout.LayoutParams(-1, -2).apply { topMargin = u(3) })
        right.addView(about, FrameLayout.LayoutParams(-1, -2).apply { topMargin = miniH + u(13) })
        browse.addView(right, LinearLayout.LayoutParams(u(MINI_W), -1).apply { leftMargin = u(GAP_RIGHT) })
        page.addView(browse, FrameLayout.LayoutParams(-1, -1).apply { leftMargin = u(SIDE_PAD); topMargin = u(MINI_Y); bottomMargin = u(30) })

        // along the bottom: what the keys do here
        page.addView(ui.label(9.5f, white(100)).apply { text = "OK  watch        HOLD OK  favourite        \u25C0 \u25B6  lists and channels        BACK  close"; letterSpacing = 0.04f },
            FrameLayout.LayoutParams(-2, -2, Gravity.BOTTOM or Gravity.START).apply { leftMargin = u(SIDE_PAD + GROUPS_W + GAP + 12); bottomMargin = u(10) })
        browsePage = page
        root.addView(page, FrameLayout.LayoutParams(-1, -1))

        groupList.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, v: View?, p: Int, id: Long) { if (groupList.hasFocus() && !groupRows[p].first.startsWith("#")) choose(groupRows[p].first, false) }
            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }
        groupList.setOnItemClickListener { _, _, p, _ ->
            when (val id = groupRows[p].first) {
                "search" -> askSearch()
                "settings" -> openSettings()
                "source" -> sourceSheet { if (browsing) groupList.requestFocus() }
                else -> if (!Plus.groupClick(id)) { choose(id, false); if (shown.isNotEmpty()) chanList.requestFocus() }
            }
        }
        groupList.setOnItemLongClickListener { _, _, p, _ -> Plus.groupHeld(groupRows[p].first, groupRows[p].second) }
        groupList.setOnKeyListener { _, k, ev ->
            when (k) {
                KeyEvent.KEYCODE_DPAD_RIGHT -> { if (ev.action == KeyEvent.ACTION_DOWN && shown.isNotEmpty()) chanList.requestFocus(); true }
                KeyEvent.KEYCODE_DPAD_LEFT -> true
                else -> false
            }
        }
        chanList.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, v: View?, p: Int, id: Long) { if (chanList.hasFocus()) shown.getOrNull(p)?.let { look(it) } }
            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }
        chanList.setOnFocusChangeListener { v, has ->
            // (this replaces the listener set in list(): it paints the selector too, and looks at the channel under the focus)
            val lv = v as ListView
            lv.selector = if (has) ui.rounded(white(30), u(12), maxOf(2, u(1.5f)), accent) else ui.rounded(Color.TRANSPARENT, 0)
            lv.invalidateViews()
            if (has) shown.getOrNull(lv.selectedItemPosition)?.let { look(it) }
        }
        chanList.setOnItemClickListener { _, _, p, _ ->
            val i = shown[p]
            scopeName = shownName; scope = shown; prefs.scope = scopeName
            closeBrowse(false)
            lib?.let { prefs.last = it.keyOf(i) }
            if (i != current) play(i) else { commit(i); showInfo(); main.removeCallbacks(watched); main.postDelayed(watched, 30_000) }
        }
        chanList.setOnItemLongClickListener { _, _, p, _ ->
            val l = lib ?: return@setOnItemLongClickListener true
            val key = l.keyOf(shown[p])
            if (!favs.remove(key)) favs.add(key)
            prefs.favs = favs
            groupCounts = groupCounts + (Library.FAVS to favs.size)
            chanAdapter.notifyDataSetChanged(); groupAdapter.notifyDataSetChanged()
            true
        }
        chanList.setOnKeyListener { _, k, ev ->
            when (k) {
                KeyEvent.KEYCODE_DPAD_LEFT -> { if (ev.action == KeyEvent.ACTION_DOWN) groupList.requestFocus(); true }
                KeyEvent.KEYCODE_DPAD_RIGHT -> true
                else -> false
            }
        }
    }

    /** the whole channel window (heading, lists, what the keys do) */
    private lateinit var browsePage: View

    private fun groupsNow(): List<Pair<String, String>> {
        val l = lib
        val out = ArrayList<Pair<String, String>>()
        val counts = HashMap<String, Int>()
        // (with more than one playlist: which one is watched, and the way to another)
        if (prefs.sources.size > 1) { out += "#source" to "PLAYLIST"; out += "source" to prefs.sourceName; out += "#lists" to "LISTS" }
        out += "search" to "Search"
        if (found.isNotEmpty()) out += (Library.FOUND + found) to "\u201C$found\u201D"
        out += Library.FAVS to "Favourites"
        out += Library.RECENT to "Recent"
        out += Library.ALL to "All channels"
        if (l != null) {
            if (l.groups.isNotEmpty()) out += "#groups" to "GROUPS"
            // (shown with a capital, whatever the playlist writes)
            for (g in l.groups) out += g to g.replaceFirstChar { it.titlecase() }
            for ((id, _) in out) if (!id.startsWith("#") && id != "search" && id != "source") counts[id] = listed(id).size
        }
        out += "#app" to "APP"
        out += "settings" to "Settings"
        groupCounts = counts
        return out
    }

    /** shows the channels of the list [id] in the middle column */
    private fun choose(id: String, focusCurrent: Boolean) {
        val l = lib
        if (id == "search" || id == "settings" || id == "source" || l == null) {
            shownName = id; shown = emptyList()
            chanHint.text = when (id) {
                "search" -> "Press OK to look for a channel by its name."
                "settings" -> "Press OK for the settings: playlists, EPG, playback, interface and the home screen."
                "source" -> "Press OK to watch from another playlist, or to add one."
                else -> ""
            }
        } else {
            shownName = id
            shown = listed(id)
            chanHint.text = when {
                shown.isNotEmpty() -> ""
                id == Library.FAVS -> "No favourites yet.\nHold OK on a channel to make it one."
                id == Library.RECENT -> "Channels you watch for half a minute are listed here."
                id.startsWith(Library.FOUND) -> "No channel has that in its name."
                else -> ""
            }
        }
        headList.text = when (id) {
            "search", "settings", "source" -> ""
            else -> (groupRows.firstOrNull { it.first == id }?.second ?: id).uppercase(Locale.getDefault()) + "   \u00B7   " + String.format(Locale.US, "%,d", shown.size)
        }
        chanAdapter.notifyDataSetChanged()
        groupAdapter.notifyDataSetChanged()
        val at = if (focusCurrent) shown.indexOf(current).coerceAtLeast(0) else 0
        chanList.setSelectionFromTop(at, if (focusCurrent) u(200) else 0)
    }

    private fun openBrowse() {
        if (testStream != null || browsing) return
        hideGuide()
        val l = lib
        if (l == null) { openSettings(); return }          // no playlist: only the settings make sense
        browsing = true
        before = current
        main.removeCallbacks(hideInfo); infoBar.alpha = 0f
        main.removeCallbacks(watched)
        groupRows = groupsNow()
        backdrop.visibility = View.VISIBLE
        browsePage.visibility = View.VISIBLE
        miniTag.visibility = if (prefs.previewFocus) View.GONE else View.VISIBLE
        miniTag.text = "WATCHING   " + (current + 1) + "  " + l.channels[current.coerceIn(0, l.channels.size - 1)].name
        clockTick.run()
        placeSurface()
        val name = if (groupRows.any { it.first == scopeName } && current in listed(scopeName)) scopeName else Library.ALL
        choose(name, true)
        groupList.setSelection(groupRows.indexOfFirst { it.first == name }.coerceAtLeast(0))
        fillDetails(current)
        if (shown.isNotEmpty()) chanList.requestFocus() else groupList.requestFocus()
    }

    /** [back]: the channel that was on before comes back */
    private fun closeBrowse(back: Boolean) {
        if (!browsing) return
        browsing = false
        main.removeCallbacks(lookLater)
        browsePage.visibility = View.GONE
        clockTick.run()
        backdrop.visibility = View.GONE
        detailsFor = -1
        placeSurface()
        if (!back) rateKnown()
        if (back && before >= 0) {
            if (current != before) play(before) else { main.removeCallbacks(watched); main.postDelayed(watched, 30_000) }
        }
    }

    /** the focus is on channel [i]: tell about it at once, and after a moment show it in the small window */
    private fun look(i: Int) {
        fillDetails(i)
        main.removeCallbacks(lookLater)
        // (unless the small window is to stay on the channel that is watched)
        if (prefs.previewFocus) main.postDelayed(lookLater, 600)
    }

    private val lookLater = Runnable {
        if (!browsing) return@Runnable
        val i = detailsFor
        if (i >= 0 && i != current) play(i, preview = true)
    }

    private fun leftText(min: Int): String = when {
        min <= 0 -> ""
        min < 60 -> "$min min left"
        min % 60 == 0 -> "${min / 60} h left"
        else -> "${min / 60} h ${min % 60} min left"
    }

    /** "19:00   Evening news" lines, the times a little dimmer than the titles */
    private fun laterLines(list: List<Guide.Prog>): CharSequence {
        val b = android.text.SpannableStringBuilder()
        for (g in list) {
            if (b.isNotEmpty()) b.append('\n')
            val at = b.length
            b.append(hm(g.start))
            b.setSpan(android.text.style.ForegroundColorSpan(white(120)), at, b.length, 0)
            b.append("    ").append(g.title)
        }
        return b
    }

    /** the right side: what channel [i] shows now, what that is about, and what comes later */
    private fun fillDetails(i: Int) {
        val l = lib ?: return
        val c = l.channels.getOrNull(i) ?: return
        val changed = detailsFor != i
        detailsFor = i
        val t = System.currentTimeMillis()
        headClock.text = hm(t)
        dName.text = c.name
        dNum.text = (i + 1).toString()
        Images.show(Repo.logo(i), dLogo, u(30))
        val (cur, next) = Repo.nowNext(i, t)
        dNow.text = cur?.title ?: "No programme guide for this channel"
        dNow.setTextColor(if (cur != null) accent else white(140))
        dTimes.visibility = if (cur != null) View.VISIBLE else View.GONE
        if (cur != null) {
            dStart.text = hm(cur.start); dStop.text = hm(cur.stop)
            dBar.f = Guide.progress(cur, t)
            val min = ((cur.stop - t + 59_999) / 60_000).toInt()
            dLeft.text = leftText(min)
        }
        if (changed) {
            dDesc.text = ""; dDesc.visibility = View.GONE
            dLater.text = if (next != null) laterLines(listOf(next)) else ""
            dLaterCaps.visibility = if (next != null) View.VISIBLE else View.INVISIBLE
            // what it is about, and the rest of the evening, are read from the guide's file
            Thread({
                val all = Repo.programmes(this, i)
                main.post {
                    if (detailsFor != i || !browsing) return@post
                    val now = System.currentTimeMillis()
                    val on = all.firstOrNull { it.start <= now && now < it.stop }
                    dDesc.text = on?.desc.orEmpty()
                    dDesc.visibility = if (dDesc.text.isEmpty()) View.GONE else View.VISIBLE
                    val later = all.filter { it.start >= (on?.stop ?: now) }.take(3)
                    dLater.text = laterLines(later)
                    dLaterCaps.visibility = if (later.isNotEmpty()) View.VISIBLE else View.INVISIBLE
                }
            }, "channels-about").start()
        }
    }

    // ---------------------------------------------------------------- the programmes of the channel that plays

    private lateinit var guideBox: LinearLayout
    private lateinit var guideTitle: TextView
    private lateinit var guideList: ListView
    private var progs: List<Guide.Prog> = emptyList()
    /** the channel whose programmes are listed is recorded: those of the days before are listed too and can be played */
    private var guideRec = false
    /** the channel whose programmes are listed */
    private var guideOf = -1

    private val guideAdapter = object : BaseAdapter() {
        override fun getCount() = progs.size
        override fun getItem(p: Int) = progs[p]
        override fun getItemId(p: Int) = p.toLong()
        override fun getView(p: Int, old: View?, parent: ViewGroup): View {
            val row = (old as? LinearLayout) ?: LinearLayout(this@MainActivity).apply {
                orientation = LinearLayout.HORIZONTAL
                setPadding(u(10), u(6), u(10), u(6))
                addView(ui.label(11.5f, white(150)), LinearLayout.LayoutParams(u(70), -2))
                val col = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
                col.addView(ui.label(12.5f, Color.WHITE, lines = 2))
                col.addView(ui.label(10.5f, white(150), lines = 3).apply { setLineSpacing(u(2).toFloat(), 1f) })
                addView(col, LinearLayout.LayoutParams(0, -2, 1f))
            }
            val g = progs[p]
            val t = System.currentTimeMillis()
            val on = g.start <= t && t < g.stop
            (row.getChildAt(0) as TextView).apply { text = dayHm(g.start); setTextColor(if (on) accent else white(150)) }
            val col = row.getChildAt(1) as LinearLayout
            (col.getChildAt(0) as TextView).apply { text = (lib?.channels?.getOrNull(guideOf)?.let { Plus.programmeMark(Library.keyOf(it), g) } ?: "") + g.title; setTextColor(if (on) accent else if (g.stop <= t) (if (guideRec) white(200) else white(110)) else Color.WHITE) }
            (col.getChildAt(1) as TextView).apply { text = g.desc; visibility = if (g.desc.isEmpty()) View.GONE else View.VISIBLE }
            return row
        }
    }

    private fun buildGuide() {
        guideBox = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = GradientDrawable(GradientDrawable.Orientation.LEFT_RIGHT, intArrayOf(Color.argb(244, 6, 9, 18), Color.argb(228, 6, 9, 18)))
            setPadding(u(26), 0, u(18), 0)
            visibility = View.GONE
            isClickable = true
        }
        guideTitle = ui.label(11f, accent, true, lines = 8).apply { letterSpacing = 0.14f; setPadding(u(10), u(24), u(10), 0) }
        guideBox.addView(guideTitle)
        guideList = list().apply { adapter = guideAdapter }
        guideBox.addView(guideList, LinearLayout.LayoutParams(-1, 0, 1f))
        root.addView(guideBox, FrameLayout.LayoutParams(u(400), -1, Gravity.START))
        // OK on a programme that is over, or is on: it plays from its beginning, where the channel is recorded
        guideList.setOnItemClickListener { _, _, p, _ ->
            val g = progs.getOrNull(p) ?: return@setOnItemClickListener
            val rule = lib?.channels?.getOrNull(current)?.rec
            val now = System.currentTimeMillis()
            if (g.start > now) {
                val c = lib?.channels?.getOrNull(guideOf)
                if (c != null && Plus.programme(Library.keyOf(c), c.name, g)) guideAdapter.notifyDataSetChanged()
                return@setOnItemClickListener
            }
            if (rule == null || !canShift() || g.start < now - rule.days * 86_400_000L + 120_000) return@setOnItemClickListener
            hideGuide()
            play(current, at = g.start)
        }
        guideList.setOnKeyListener { _, k, ev ->
            when (k) {
                KeyEvent.KEYCODE_DPAD_LEFT -> { if (ev.action == KeyEvent.ACTION_DOWN) hideGuide(); true }
                KeyEvent.KEYCODE_DPAD_RIGHT -> true
                else -> false
            }
        }
    }

    private fun hideGuide() { guideBox.visibility = View.GONE; progs = emptyList(); guideAdapter.notifyDataSetChanged() }

    private fun showGuide(i: Int) {
        val l = lib ?: return
        main.removeCallbacks(hideInfo); infoBar.alpha = 0f
        val days = l.channels[i].rec?.days ?: 0
        guideRec = i == current && canShift() && days > 0
        guideOf = i
        val name = l.channels[i].name.uppercase(Locale.getDefault()) + (if (guideRec) "\nOK PLAYS A PROGRAMME FROM ITS BEGINNING  \u00B7  $days DAYS BACK" else "") +
            Plus.guideHint().let { if (it.isEmpty()) "" else "\n" + it }
        guideTitle.text = name
        guideBox.visibility = View.VISIBLE
        progs = emptyList(); guideAdapter.notifyDataSetChanged()
        guideList.requestFocus()
        Thread({
            val list = Repo.programmes(this, i, past = guideRec)
            main.post {
                if (guideBox.visibility != View.VISIBLE) return@post
                val t = System.currentTimeMillis()
                progs = list.filter { it.stop > t - (if (guideRec) days * 86_400_000L - 120_000 else 2 * 3600_000L) }
                guideAdapter.notifyDataSetChanged()
                if (progs.isEmpty()) { guideTitle.text = name + "\n\nNO PROGRAMME GUIDE FOR THIS CHANNEL"; return@post }
                guideList.setSelectionFromTop(progs.indexOfFirst { it.stop > t }.coerceAtLeast(0), u(60))
                guideList.requestFocus()
            }
        }, "channels-progs").start()
    }

    // ---------------------------------------------------------------- search, settings

    private fun askSearch() {
        val sh = Sheet(ui, "Search", "A part of the channel's name is enough.")
        val q = sh.input(found, "Channel name", InputType.TYPE_CLASS_TEXT)
        sh.button("Cancel").button("Search", primary = true) {
            val text = q.text.toString().trim()
            if (text.isEmpty()) return@button
            found = text
            groupRows = groupsNow()
            groupAdapter.notifyDataSetChanged()
            choose(Library.FOUND + text, false)
            groupList.setSelection(groupRows.indexOfFirst { it.first == Library.FOUND + text })
            if (shown.isNotEmpty()) chanList.requestFocus()
        }.show { if (browsing && !chanList.hasFocus()) groupList.requestFocus() }
    }

    /** another playlist is watched from now on: its channels, favourites, recent channels and guide */
    private fun switchSource(id: Int) {
        if (id == prefs.source && Repo.lib != null) return
        prefs.source = id
        Repo.switchTo(id)
        prefs.migrateEpg()
        favs = prefs.favs.toMutableSet()
        recents = prefs.recents
        scopeName = prefs.scope.ifEmpty { Library.ALL }
        found = ""
        Home.publish(this, prefs.homeRows)
        reload(false)
    }

    /** choosing among the playlists, or adding one */
    private fun sourceSheet(after: () -> Unit) {
        val sh = Sheet(ui, "Playlists", "Each playlist has its own channels, favourites and recent channels. Choose the one to watch.")
        for ((id, name) in prefs.sources) sh.item(name, { if (id == prefs.source) "Watching" else "" }) { switchSource(id) }
        sh.item("Add a playlist", { "" }) { addSource() }
        sh.button("Close").show(after)
    }

    private fun addSource() {
        val n = prefs.sources.size + 1
        Sheet(ui, "Add a playlist", "What were you given for your channels? It is added to your playlists as \u201CPlaylist $n\u201D and watched at once; you can rename it afterwards.")
            .item("A link", { "M3U playlist" }) { addLink() }
            .item("A server, a name and a password", { "Xtream Codes" }) { addXtream() }
            .item("A portal and a MAC address", { "Stalker, Ministra" }) { addPortal() }
            .button("Cancel").show { if (page.isOpen) page.focus() }
    }

    /** the playlist behind [link] is added and watched */
    private fun added(link: String) {
        val id = prefs.addSource()
        prefs.source = id
        Repo.switchTo(id)
        prefs.playlist = link
        switchSource(id)
        reopenSettings()
    }

    /** one thing asked for, on a sheet of its own; [then] gets what was typed, when it is not empty */
    private fun ask(title: String, text: String, hint: String, type: Int, next: String, then: (String) -> Unit) {
        val sh = Sheet(ui, title, text)
        val e = sh.input("", hint, type)
        sh.button("Cancel").button(next, primary = true) { e.text.toString().trim().let { if (it.isNotEmpty()) then(it) } }.show { if (page.isOpen) page.focus() }
    }

    private fun addLink() = ask("Add a playlist", "Paste or type the link of the playlist (M3U).", "The link of the playlist (.m3u or .m3u8)", InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI, "Add") { added(it) }

    private fun addXtream() {
        val plain = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD
        ask("Xtream Codes  \u00B7  1 of 3", "The address of the server, as your provider gave it.", "http://server.example:8080", InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI, "Next") { server ->
            ask("Xtream Codes  \u00B7  2 of 3", "Your user name.", "User name", plain, "Next") { user ->
                ask("Xtream Codes  \u00B7  3 of 3", "Your password. It is kept on this device only.", "Password", plain, "Add") { pass -> added(Xtream.link(server, user, pass)) }
            }
        }
    }

    private fun addPortal() {
        ask("Portal  \u00B7  1 of 2", "The address of the portal, as your provider gave it.", "http://portal.example/c/", InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI, "Next") { address ->
            ask("Portal  \u00B7  2 of 2", "The MAC address your provider has registered for you: twelve letters and digits.", "00:1A:79:00:00:00", InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD, "Add") { typed ->
                val mac = Portal.mac(typed)
                if (mac == null) Sheet(ui, "That is not a MAC address", "It has twelve letters and digits, such as 00:1A:79:12:34:56.").button("Close", primary = true).show { if (page.isOpen) page.focus() }
                else added(Portal.link(address, mac))
            }
        }
    }

    private fun reload(force: Boolean) {
        closeOptions()
        shade.visibility = View.VISIBLE
        noTunnel.clear(); tryMend.clear(); needUnits.clear()
        if (browsing) closeBrowse(false)
        lib = null; current = -1
        watchingIdx = -1; lastChannel = -1; shiftAt = 0L; paused = false; shiftProgsFor = -1
        player?.stop()
        say("Reading the playlist")
        Repo.start(this, force, { onChannels() }, { onGuide() })
        page.refresh()
    }

    private lateinit var page: Page

    private fun epgName(id: Int): String = if (id == 0) "From the playlist" else prefs.epgs.firstOrNull { it.first == id }?.second ?: "From the playlist"

    /** playlist [source] takes its programmes from EPG [id] from now on (0: from the one it names itself) */
    private fun useEpg(source: Int, id: Int) {
        if (prefs.epgOf(source) == id) return
        prefs.setEpgOf(source, id)
        Repo.forgetGuide(this, source)
        if (source == prefs.source) reload(false)
    }

    /** choosing where a playlist takes its programmes from */
    private fun epgPicker(source: Int) {
        val name = prefs.sources.firstOrNull { it.first == source }?.second ?: return
        val now = prefs.epgOf(source)
        val sh = Sheet(ui, "EPG of \u201C$name\u201D", "Where this playlist takes its programmes from." + (if (prefs.epgs.isEmpty()) " To use another EPG, add one in the settings under EPG." else ""))
        sh.item("From the playlist", { if (now == 0) "\u2713" else "" }) { useEpg(source, 0); reopenSettings() }
        for ((id, n, _) in prefs.epgs) sh.item(n, { if (now == id) "\u2713" else "" }) { useEpg(source, id); reopenSettings() }
        sh.button("Close").show { if (page.isOpen) page.focus() }
    }

    private fun addEpg() {
        val sh = Sheet(ui, "Add an EPG", "Paste or type the link of a programme guide in XMLTV form. It is saved as \u201CEPG ${prefs.epgs.size + 1}\u201D" +
            (if (prefs.sources.isEmpty()) "." else " and used for the playlist you are watching; other playlists can be given it too."))
        val e = sh.input("", "The link of the EPG (.xml or .xml.gz)", InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI)
        sh.button("Cancel").button("Add", primary = true) {
            val v = e.text.toString().trim()
            if (v.isEmpty()) return@button
            val id = prefs.addEpg(v)
            if (prefs.sources.isNotEmpty()) useEpg(prefs.source, id)
            reopenSettings()
        }.show { if (page.isOpen) page.focus() }
    }

    /** OK on a saved EPG in the settings: what can be done with it */
    private fun epgSheet(id: Int) {
        val (_, name, address) = prefs.epgs.firstOrNull { it.first == id } ?: return
        val users = prefs.sources.filter { prefs.epgOf(it.first) == id }
        val sh = Sheet(ui, name, (Uri.parse(address).host ?: "A saved EPG") + ". " + (if (users.isEmpty()) "No playlist uses it." else "Used by " + users.joinToString(", ") { it.second } + "."))
        if (prefs.sources.isNotEmpty() && prefs.epgOf(prefs.source) != id) sh.item("Use for \u201C${prefs.sourceName}\u201D") { useEpg(prefs.source, id); reopenSettings() }
        sh.item("Rename") {
            val r = Sheet(ui, "Rename", "What this EPG is called in the lists.")
            val e = r.input(name, "Name", InputType.TYPE_CLASS_TEXT)
            r.button("Cancel").button("Save", primary = true) { prefs.renameEpg(id, e.text.toString()); reopenSettings() }.show { if (page.isOpen) page.focus() }
        }
        sh.item("Change the link") {
            link("Link of \u201C$name\u201D", "The link of the programme guide in XMLTV form.", "The link of the EPG (.xml or .xml.gz)", address) { v ->
                if (v.isEmpty() || v == address) return@link
                prefs.setEpgLink(id, v)
                // (every playlist that uses it reads it anew)
                for ((sid, _) in users) Repo.forgetGuide(this, sid)
                if (users.any { it.first == prefs.source }) reload(false)
                reopenSettings()
            }
        }
        sh.item("Remove") {
            Sheet(ui, "Remove \u201C$name\u201D?", if (users.isEmpty()) "The EPG is forgotten." else "The EPG is forgotten. " + users.joinToString(", ") { it.second } + " then use" + (if (users.size == 1) "s" else "") + " the EPG the playlist names.")
                .button("Keep", primary = true).button("Remove") {
                    prefs.removeEpg(id)
                    for ((sid, _) in users) Repo.forgetGuide(this, sid)
                    if (users.any { it.first == prefs.source }) reload(false)
                    reopenSettings()
                }.show { if (page.isOpen) page.focus() }
        }
        sh.button("Close").show { if (page.isOpen) page.focus() }
    }

    /** OK on "Aurus IPTV update": looks for a newer version, or offers the one that was found */
    private fun askUpdate() {
        val r = Update.available
        if (r == null || Update.progress >= 0) {
            if (Update.progress < 0) Update.check(prefs, true) { page.refresh() }
            page.refresh()
            return
        }
        // (No asking beforehand for leave to install: on TVs the app is listed under "Install unknown apps" only once it has
        // tried. Android's installer asks for that leave itself the first time, with its own way to the setting.)
        Sheet(ui, "Aurus IPTV ${r.name}", "You have ${BuildConfig.VERSION_NAME.replace("-", " ")}. The new version is downloaded from the app's releases on GitHub, " +
            "then Android asks you to confirm the update." + (if (Update.allowed(this)) "" else " The first time, Android also asks you to allow Aurus IPTV to install apps: choose Settings there and switch it on.") +
            " Your playlists and settings stay as they are.")
            .button("Download and install", primary = true) { Update.install(this, r) { page.refresh() }; page.refresh() }
            .button("Not now")
            .show { if (page.isOpen) page.focus() }
    }

    /** the settings are drawn again (the playlists listed in them changed) */
    private fun reopenSettings() { if (page.isOpen) { page.close(); page.keepRow = true; openSettings() } }

    /** OK on a playlist in the settings: what can be done with it */
    private fun playlistSheet(id: Int) {
        val name = prefs.sources.firstOrNull { it.first == id }?.second ?: return
        val active = id == prefs.source
        val n = prefs.channelCount(id)
        val sh = Sheet(ui, name, (if (active) "The playlist you are watching" else "A saved playlist") + (if (n > 0) ", " + String.format(Locale.US, "%,d", n) + " channels." else "."))
        if (!active) sh.item("Watch this playlist") { switchSource(id); reopenSettings() }
        sh.item("Rename") {
            val r = Sheet(ui, "Rename", "What this playlist is called in the lists.")
            val e = r.input(name, "Name", InputType.TYPE_CLASS_TEXT)
            r.button("Cancel").button("Save", primary = true) { prefs.renameSource(id, e.text.toString()); reopenSettings() }.show { if (page.isOpen) page.focus() }
        }
        sh.item("Change the link") {
            link("Link of \u201C$name\u201D", "Paste or type the new link. The one in use is not shown, because it holds your subscription key." + (if (active) "" else " The playlist is then the one watched."),
                "The link of the playlist (.m3u or .m3u8)", "") { v ->
                if (v.isEmpty()) return@link
                if (!active) { prefs.source = id; Repo.switchTo(id) }
                prefs.playlist = v
                Repo.forget(this)
                if (active) reload(true) else switchSource(id)
                reopenSettings()
            }
        }
        sh.item("EPG", { epgName(prefs.epgOf(id)) }) { epgPicker(id) }
        if (active) sh.item("Fetch it again", { if (Repo.working) "Working" else "" }) { reload(true) }
        sh.item("Remove") {
            Sheet(ui, "Remove \u201C$name\u201D?", "Its link, favourites, recent channels and EPG are forgotten on this device." + (if (prefs.sources.size > 1) " The other playlists stay." else ""))
                .button("Keep", primary = true).button("Remove") {
                    Repo.forgetFiles(this, id)
                    prefs.removeSource(id)
                    if (active) switchSource(prefs.source)
                    reopenSettings()
                }.show { if (page.isOpen) page.focus() }
        }
        sh.button("Close").show { if (page.isOpen) page.focus() }
    }

    private fun link(title: String, message: String, hint: String, text: String, done: (String) -> Unit) {
        val sh = Sheet(ui, title, message)
        val e = sh.input(text, hint, InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI)
        sh.button("Cancel").button("Save", primary = true) { done(e.text.toString().trim()) }.show { page.focus() }
    }

    /** a row for one part of the subtitles' look ([at]: its place in [Prefs.subLook]); stepping it shows a sample */
    private fun subRow(title: String, desc: String, at: Int, names: Array<String>) = Page.Row(title, desc, { names[prefs.subLook[at].coerceIn(0, names.size - 1)] }) { d ->
        val l = prefs.subLook.toMutableList()
        l[at] = (l[at] + (if (d == 0) 1 else d) + names.size) % names.size
        prefs.subLook = l
        cues.look(l, true)
    }

    private fun openSettings() {
        if (Plus.settingsAsked { openSettingsNow() }) return
        openSettingsNow()
    }

    private fun openSettingsNow() {
        if (browsing) closeBrowse(true)
        hideGuide()
        main.removeCallbacks(hideInfo); infoBar.alpha = 0f
        fun num(n: Int) = String.format(Locale.US, "%,d", n)
        val tabs = listOf(
            Page.Tab("Playlists", "Your channels come from a playlist link (M3U) that your provider gives you. Every playlist you add is listed here; " +
                "press OK on one to watch it, rename it, change its link or remove it. A link holds your subscription key: it is kept only inside this app and is not shown again once it is saved.",
                stats = {
                    val l = Repo.lib
                    listOf("WATCHING" to prefs.sourceName.ifEmpty { "No playlist yet" },
                        "CHANNELS" to (if (l == null) (if (Repo.working) "Reading" else "None") else num(l.channels.size) + " in " + l.groups.size + " groups"),
                        "RECORDED" to (if (l == null) "\u2014" else if (Repo.recCount == 0) "None, the playlist names no recordings" else num(Repo.recCount) + " channels, up to " + Repo.recDays + " days back"),
                        "LAST FETCHED" to stamp(Repo.listTime(this)))
                }, rows = prefs.sources.map { (id, name) ->
                    Page.Row(name, prefs.channelCount(id).let { if (it > 0) num(it) + " channels" else "Not read yet" }, { if (id == prefs.source) "\u2713  Watching" else "" }, steps = false) { playlistSheet(id) }
                } + Page.Row("\uFF0B  Add a playlist", if (prefs.sources.isEmpty()) "Give the link of your playlist to start" else "Another provider's link, or a list of your own", { "" }, steps = false) { addSource() }
            ),
            Page.Tab("EPG", "The EPG (electronic programme guide) tells what is on now and later. Most playlists name one of their own. You can also save EPGs here " +
                "and choose, for each playlist, which one it takes its programmes from. An EPG is read again by itself twice a day; a large one takes about a minute.",
                stats = {
                    val l = Repo.lib
                    listOf("PLAYLIST" to prefs.sourceName.ifEmpty { "\u2014" },
                        "STATUS" to Repo.guideNote.ifEmpty { if (Repo.guideTime(this) > 0) "Ready" else if (Repo.working) "Reading the EPG" else "Not read yet" },
                        "CHANNELS WITH EPG" to (if (l == null) "\u2014" else num(Repo.covered) + " of " + num(l.channels.size)),
                        "CHANNELS WITH A LOGO" to (if (l == null) "\u2014" else num(l.channels.indices.count { Repo.logo(it).isNotEmpty() })),
                        "LAST READ" to stamp(Repo.guideTime(this)) + (Uri.parse(Repo.guideLink).host?.let { ", from $it" } ?: ""))
                }, rows = listOf(
                    Page.Row("EPG of this playlist", "Where \u201C" + prefs.sourceName + "\u201D takes its programmes from", { epgName(prefs.epgOf(prefs.source)) }, { prefs.sources.isNotEmpty() }, steps = false) { epgPicker(prefs.source) }
                ) + prefs.epgs.map { (id, name, link) ->
                    Page.Row(name, Uri.parse(link).host ?: "Saved EPG", { if (prefs.epgOf(prefs.source) == id) "\u2713  In use" else "" }, steps = false) { epgSheet(id) }
                } + listOf(
                    Page.Row("\uFF0B  Add an EPG", "A link in XMLTV form (.xml or .xml.gz)", { "" }, steps = false) { addEpg() },
                    Page.Row("Logos link", "Optional: a second list that gives this playlist's channels their logos and EPG names", { if (prefs.extra.isEmpty()) "None" else "Set" }, { prefs.sources.isNotEmpty() }, steps = false) {
                        link("Logos link", "Some providers' playlists carry no logos and do not say which EPG channel each one is. A second, public list (M3U) can supply both: " +
                            "channels are matched by number, else by name. Leave empty for none.",
                            "The link of the list with logos", prefs.extra) { v -> prefs.extra = v; Repo.forget(this); reload(true) }
                    },
                    Page.Row("Read the EPG again", "Also fetches the playlist again", { if (Repo.working) "Working" else "" }, { prefs.sources.isNotEmpty() }, steps = false) { reload(true) }
                )
            ),
            Page.Tab("Playback", "Picture and sound are kept in step the standard way, where you can move the sound yourself, or by the box's own video hardware. " +
                "A channel whose picture stops with the hardware is played the standard way by itself. With the refresh rate on Auto, the screen goes dark for a moment when it changes.",
                stats = {
                    listOf("THIS CHANNEL" to techLine().ifEmpty { "\u2014" }, "THE TV TAKES" to tvTakes(),
                        "SCREEN" to screenLine(),
                        "IN STEP BY" to (if (tunnel) "The video hardware" else if (prefs.hardwareSync) "Standard (this channel does not play with the hardware)" else "Standard" +
                            (if (prefs.soundDelay != 0) ", sound " + (if (prefs.soundDelay > 0) "+" else "") + prefs.soundDelay + " ms" else "")))
                }, rows = listOf(
                Page.Row("Picture and sound in step", "Who keeps them together", { if (prefs.hardwareSync) "Video hardware" else "Standard" }) {
                    prefs.hardwareSync = !prefs.hardwareSync
                    noTunnel.clear()
                    if (current >= 0) play(current, true)
                },
                Page.Row("Quality", "Of channels sent in several: follow the connection, or the best",
                    { if (prefs.bestQuality) "Always the highest" else "Automatic" }) {
                    prefs.bestQuality = !prefs.bestQuality
                    selector?.let { it.setParameters(it.buildUponParameters().setForceHighestSupportedBitrate(prefs.bestQuality)) }
                    if (current >= 0) play(current, true)
                },
                Page.Row("Dolby Digital", "Passed on to the TV as it is, or decoded here to stereo", { dolbyNow(prefs.decodeAc3, C.ENCODING_AC3, androidx.media3.common.MimeTypes.AUDIO_AC3) }) {
                    prefs.decodeAc3 = !prefs.decodeAc3
                    if (current >= 0) play(current, true)
                },
                Page.Row("Dolby Digital Plus", "The same, for the newer kind. Some soundbars stay silent with it", { dolbyNow(prefs.decodeEac3, C.ENCODING_E_AC3, androidx.media3.common.MimeTypes.AUDIO_E_AC3) }) {
                    prefs.decodeEac3 = !prefs.decodeEac3
                    if (current >= 0) play(current, true)
                },
                Page.Row("Clearer speech", "Raises voices in sound that is decoded here, not in Dolby passed on", { arrayOf("Off", "On", "Strong")[prefs.speech] }) { d ->
                    prefs.speech = (prefs.speech + d + 3) % 3
                    if (current >= 0) play(current, true)
                },
                Page.Row("Sound delay", "+ plays the sound later, \u2212 earlier. If you hear the words before the lips move (the picture is late), add", {
                    (if (prefs.soundDelay > 0) "+" else "") + prefs.soundDelay + " ms" }, { !prefs.hardwareSync }) { d ->
                    // small steps near nought, larger ones further out (some TVs show the picture a second late)
                    val v = prefs.soundDelay
                    val step = if (kotlin.math.abs(v + d) < 200) 20 else 100
                    prefs.soundDelay = if (step == 100) (v + 100 * d) / 100 * 100 else v + 20 * d
                    delayUs = prefs.soundDelay * 1000L
                    if (other) Plus.otherDelay(prefs.soundDelay)
                },
                Page.Row("Refresh rate", "The TV is switched to the channel's rate, for even motion. It goes dark for a moment each time it switches",
                    { if (!prefs.matchRate) "Off" else if (prefs.rateWait) "Auto, once you stay on a channel" else "Auto, at once" }) { d ->
                    // Off, once you stay, at once
                    val now = if (!prefs.matchRate) 0 else if (prefs.rateWait) 1 else 2
                    val next = (now + (if (d < 0) 2 else 1)) % 3
                    prefs.matchRate = next != 0
                    if (next != 0) prefs.rateWait = next == 1
                    matchScreen()
                },
                Page.Row("Catch-up", "Earlier programmes, start over and pause, on channels the playlist says are recorded", { if (prefs.archive) "On" else "Off" }) {
                    prefs.archive = !prefs.archive
                    if (!prefs.archive && (shiftAt > 0L || paused) && current >= 0) play(current)
                },
                Page.Row("After a pause", "The channel goes on a little earlier, so that nothing is missed", { if (prefs.resumeBack == 0) "Where it stopped" else "${prefs.resumeBack} s earlier" }, { prefs.archive }) { d ->
                    prefs.resumeBack = when (prefs.resumeBack) { 0 -> if (d > 0) 5 else 10; 5 -> if (d > 0) 10 else 0; else -> if (d > 0) 0 else 5 }
                },
                Page.Row("Up on the remote", "Which way Up and Down go through the channels", { if (prefs.upIsNext) "Next channel" else "Previous channel" }) {
                    prefs.upIsNext = !prefs.upIsNext
                }
            )),
            Page.Tab("Interface", "How the app looks while you watch. The sleep timer is not here: hold OK while watching, it is the last choice in the bar.",
                rows = listOf(
                Page.Row("Clock on screen", "A small clock stays in the top right corner", { if (prefs.clock) "On" else "Off" }) {
                    prefs.clock = !prefs.clock
                    clockTick.run()
                },
                subRow("Subtitle size", "Of subtitles sent as text; a sample shows for a moment", 0, arrayOf("Small", "Usual", "Large", "Largest")),
                subRow("Subtitle colour", "White, or a colour that stands out from the picture", 1, arrayOf("White", "Yellow", "Cyan", "Green")),
                subRow("Subtitle weight", "How heavy the letters are", 4, arrayOf("Thin", "Regular", "Medium", "Bold")),
                subRow("Subtitle letters", "Plain, with serifs, all of one width, or narrow", 5, arrayOf("Plain", "Serif", "Typewriter", "Narrow")),
                subRow("Subtitle background", "A dark edge round the letters, or a dark plate behind them", 2, arrayOf("Edge", "Plate")),
                subRow("Subtitle height", "Higher, where the TV cuts off the bottom edge", 3, arrayOf("Bottom", "Higher", "Higher still")),
                Page.Row("While a channel changes", "What is seen until the new channel's picture comes", { if (prefs.blackSwitch) "Black screen" else "Last picture" }) {
                    prefs.blackSwitch = !prefs.blackSwitch
                },
                Page.Row("Small window in the channel list", "What plays there while you move through the channels", { if (prefs.previewFocus) "The channel in focus" else "The channel you watch" }) {
                    prefs.previewFocus = !prefs.previewFocus
                },
                Page.Row("See-through bars", "How much of the picture shows through the bar and the programme list", { "${prefs.glass} %" }) { d ->
                    prefs.glass = prefs.glass + 10 * d
                    applyGlass()
                }
            )),
            Page.Tab("Home screen", "The channels you watched last, and your favourites, can be given to the TV's home screen. Each one there opens this app directly on that channel.",
                stats = { listOf("ON THE HOME SCREEN" to (if (prefs.homeRow) num(prefs.homeRows.size.coerceAtMost(15)) + " channels" else "Nothing"), "FAVOURITES" to num(favs.size), "RECENT" to num(recents.size)) },
                rows = listOf(
                Page.Row("Channels on the home screen", "Recent channels and favourites", { if (prefs.homeRow) "On" else "Off" }) {
                    prefs.homeRow = !prefs.homeRow
                    Home.publish(this, prefs.homeRows)
                },
                Page.Row("Forget recent channels", "Empties the Recent list, here and on the home screen", { num(recents.size) }, steps = false) {
                    recents = emptyList(); prefs.recents = recents
                    publishHome()
                }
            )),
            Page.Tab("About", "Aurus IPTV is a player for your own playlist: it brings no channels of its own. It is part of Android TV Pack, a set of free apps for TV boxes, " +
                "with no adverts, no account and no tracking. Your playlist link stays on this device.",
                stats = { listOf("NETWORK USE" to "Only your playlist, its guide and logos, and the channels you watch") },
                rows = listOf(
                Page.Row("Aurus IPTV update", "A newer version, from the app's public releases on GitHub", { Update.label() }, steps = false) { askUpdate() },
                Page.Row("Version", if (isBeta) "A beta: still being tested, expect rough edges" else "Aurus IPTV for Android TV", { BuildConfig.VERSION_NAME }, steps = false) {},
                Page.Row("Developer", "Android TV Pack", { DEVELOPER }, steps = false) {},
                Page.Row("GitHub", "Source, new versions, reporting a fault", { GITHUB }, steps = false) {
                    // (a TV is a poor place to read a web page: the address is given as a code for a phone)
                    Sheet(ui, GITHUB, "Scan the code with a phone, or type the address into a browser. There you find the source, new versions, and where to report a fault or ask for something.")
                        .picture(R.drawable.qr_github).button("Close", primary = true).show { page.focus() }
                },
                Page.Row("Buy me a Ko-fi", "Aurus IPTV is free. If you like it, you can thank its developer", { "\u2665" }, steps = false) {
                    Sheet(ui, KOFI, "Aurus IPTV is free and stays free, with no adverts. If it is useful to you, you can support its development with a coffee: " +
                        "scan the code with a phone, or type the address into a browser. Thank you.")
                        .picture(R.drawable.qr_kofi).button("Close", primary = true).show { page.focus() }
                },
                Page.Row("Open-source parts", "What the app is built with", { "" }, steps = false) {
                    Sheet(ui, "Open-source parts", "AndroidX Media3 (ExoPlayer), by The Android Open Source Project, under the Apache License 2.0.\n\n" +
                        "Kotlin, by JetBrains and contributors, under the Apache License 2.0.\n\n" +
                        "The MPEG Layer II sound decoder follows pl_mpeg by Dominic Szablewski (MIT licence), itself based on kjmp2 by Martin J. Fiedler.\n\n" +
                        "The full licences: github.com/android-tv-pack/aurus-iptv, THIRD-PARTY-NOTICES.md")
                        .button("Close", primary = true).show { page.focus() }
                }
            ))
        )
        // (the extras' tab, where a build has them, stands before About)
        val extra = Plus.tab()
        page.open(if (extra != null) tabs.dropLast(1) + extra + tabs.last() else tabs)
    }

    // ---------------------------------------------------------------- keys

    private lateinit var digits: TextView
    private var typed = ""
    private val goTyped = Runnable {
        val l = lib
        val i = if (l != null) Library.numbered(typed, l.channels.size) else -1
        typed = ""; digits.visibility = View.GONE
        if (i >= 0 && l != null && !Plus.hides(l.channels[i])) { scopeName = Library.ALL; scope = listed(Library.ALL); prefs.scope = scopeName; play(i) }
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        // (a key in the sleep timer's last minute: someone is still watching)
        if (sleepWarned) { setSleep(0); return true }
        if (page.isOpen) {
            if (keyCode == KeyEvent.KEYCODE_BACK || keyCode == KeyEvent.KEYCODE_MENU) { page.close(); return true }
            return super.onKeyDown(keyCode, event)
        }
        if (optionsOpen) {
            main.removeCallbacks(hideInfo); main.postDelayed(hideInfo, 9000)
            if (keyCode == KeyEvent.KEYCODE_BACK) { closeOptions(); return true }
            // (Left and Right walk the three choices, OK steps the one in focus; the channel stays)
            if (keyCode == KeyEvent.KEYCODE_DPAD_UP || keyCode == KeyEvent.KEYCODE_DPAD_DOWN) {
                // on "Lip sync": the sound a little later or earlier, heard and seen at once
                if (chips[5].isFocused) {
                    prefs.soundDelay = prefs.soundDelay + (if (keyCode == KeyEvent.KEYCODE_DPAD_UP) 20 else -20)
                    delayUs = prefs.soundDelay * 1000L
                    if (other) Plus.otherDelay(prefs.soundDelay)
                    say(""); refreshChips()
                }
                return true
            }
            return super.onKeyDown(keyCode, event)
        }
        if (browsing) {
            if (keyCode == KeyEvent.KEYCODE_BACK) { closeBrowse(true); return true }
            if (keyCode == KeyEvent.KEYCODE_MENU) { openSettings(); return true }
            return super.onKeyDown(keyCode, event)
        }
        if (guideBox.visibility == View.VISIBLE) {
            if (keyCode == KeyEvent.KEYCODE_BACK) { hideGuide(); return true }
            return super.onKeyDown(keyCode, event)
        }
        if (keyCode in KeyEvent.KEYCODE_0..KeyEvent.KEYCODE_9 && lib != null && testStream == null) {
            typed = Library.typed(typed, keyCode - KeyEvent.KEYCODE_0)
            digits.text = typed; digits.visibility = if (typed.isEmpty()) View.GONE else View.VISIBLE
            main.removeCallbacks(goTyped); main.postDelayed(goTyped, 1600)
            return true
        }
        if (shiftAt > 0L || paused) {
            // a recording, or a paused channel: Left / Right move in time, Back returns to what is on now (OK: see onKeyUp)
            when (keyCode) {
                KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_MEDIA_REWIND -> { shiftSeek(-60_000); return true }
                KeyEvent.KEYCODE_DPAD_RIGHT, KeyEvent.KEYCODE_MEDIA_FAST_FORWARD -> { shiftSeek(60_000); return true }
                KeyEvent.KEYCODE_BACK -> { play(current); return true }
                KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE, KeyEvent.KEYCODE_MEDIA_PLAY, KeyEvent.KEYCODE_MEDIA_PAUSE -> { if (event.repeatCount == 0) togglePause(); return true }
            }
        } else when (keyCode) {
            KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE, KeyEvent.KEYCODE_MEDIA_PAUSE -> { if (event.repeatCount == 0) togglePause(); return true }
            KeyEvent.KEYCODE_MEDIA_REWIND -> { shiftSeek(-60_000); return true }
        }
        when (keyCode) {
            KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_CHANNEL_UP, KeyEvent.KEYCODE_MEDIA_NEXT -> { zap(1); return true }
            KeyEvent.KEYCODE_DPAD_DOWN, KeyEvent.KEYCODE_CHANNEL_DOWN, KeyEvent.KEYCODE_MEDIA_PREVIOUS -> { zap(-1); return true }
            // OK: let go at once = the channels, held = sound, subtitles, picture size
            KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER -> { if (event.repeatCount == 0) event.startTracking(); return true }
            KeyEvent.KEYCODE_DPAD_LEFT -> { openBrowse(); return true }
            KeyEvent.KEYCODE_CAPTIONS, KeyEvent.KEYCODE_MEDIA_AUDIO_TRACK -> { openOptions(); return true }
            KeyEvent.KEYCODE_MENU, KeyEvent.KEYCODE_SETTINGS -> { if (testStream == null) openSettings(); return true }
            KeyEvent.KEYCODE_DPAD_RIGHT, KeyEvent.KEYCODE_GUIDE -> { if (lib != null && current >= 0 && testStream == null) showGuide(current); return true }
            KeyEvent.KEYCODE_INFO -> { if (infoBar.alpha > 0f) { main.removeCallbacks(hideInfo); hideInfo.run() } else showInfo(8000); return true }
            // Back: the channel that was on before (with none, the bar goes, then the app is left)
            KeyEvent.KEYCODE_BACK -> { if (swapLast()) return true; if (infoBar.alpha > 0.5f) { main.removeCallbacks(hideInfo); hideInfo.run(); return true } }
        }
        return super.onKeyDown(keyCode, event)
    }

    private fun watching() = !page.isOpen && !browsing && !optionsOpen && guideBox.visibility != View.VISIBLE

    override fun onKeyLongPress(keyCode: Int, event: KeyEvent): Boolean {
        if ((keyCode == KeyEvent.KEYCODE_DPAD_CENTER || keyCode == KeyEvent.KEYCODE_ENTER) && watching()) { openOptions(); return true }
        return super.onKeyLongPress(keyCode, event)
    }

    override fun onKeyUp(keyCode: Int, event: KeyEvent): Boolean {
        if ((keyCode == KeyEvent.KEYCODE_DPAD_CENTER || keyCode == KeyEvent.KEYCODE_ENTER) && event.isTracking && !event.isCanceled && watching()) {
            if (shiftAt > 0L || paused) togglePause() else openBrowse()
            return true
        }
        return super.onKeyUp(keyCode, event)
    }

    private companion object {
        /** the place in the settings to open again after the app was drawn anew */
        var resumeAt: Pair<Int, Int>? = null
        /** how long a channel is on before the screen's rate is changed for it */
        const val SETTLE_MS = 5000L
        /** how far a picture's time may come out of the decoder from the time that went in and still be taken for the same picture */
        const val NEAR_US = 14_500L
        /** a build still being tested: its version name ends in "-beta" (dropping the ending makes it a release) */
        val isBeta = BuildConfig.VERSION_NAME.endsWith("-beta")
        const val DEVELOPER = "aze038"
        const val GITHUB = "github.com/android-tv-pack/aurus-iptv"
        const val KOFI = "ko-fi.com/G0U5290K4T"
        /** the picture's sizes, in the order OK steps through them */
        val PICTURE = arrayOf("As sent", "Fill the screen", "Stretch", "16:9", "4:3")
        // the channel window, in screen units (the screen is 960 wide): lists, channels, then the picture's small window
        const val SIDE_PAD = 26
        const val GROUPS_W = 156
        const val GAP = 10
        const val CHANS_W = 318
        const val GAP_RIGHT = 24
        const val MINI_W = 400
        const val MINI_X = SIDE_PAD + GROUPS_W + GAP + CHANS_W + GAP_RIGHT
        const val MINI_Y = 52
    }
}
