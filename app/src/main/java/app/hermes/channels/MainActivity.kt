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
import androidx.media3.common.Tracks
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
import app.hermes.channels.core.M3u
import app.hermes.channels.core.Stamp
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * The whole app is this one screen: the channel plays full-screen, and everything else comes over it.
 *
 *  - Up / Down (or CH+ / CH-): the next / previous channel of the list that was chosen last
 *  - OK or Left: the channels. Lists on the left, the channels beside them; the channel under the focus plays in a
 *    small window on the right, with what it shows now (and what that is about) and later below it.
 *    OK watches it full-screen, Back returns to the channel that was on. Holding OK makes a favourite.
 *  - Right: the programmes of the channel that plays
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
        ui = Ui(this)
        prefs = Prefs(this)
        root = FrameLayout(this).apply { setBackgroundColor(Color.BLACK) }
        // (the order matters: the backdrop of the channel window lies under the picture, its lists over it)
        backdrop = View(this).apply {
            background = GradientDrawable(GradientDrawable.Orientation.TL_BR, intArrayOf(Color.rgb(14, 19, 38), Color.rgb(6, 8, 16)))
            visibility = View.GONE
        }
        root.addView(backdrop, FrameLayout.LayoutParams(-1, -1))
        surface = SurfaceView(this)
        root.addView(surface, FrameLayout.LayoutParams(-1, -1, Gravity.TOP or Gravity.START))
        buildInfo()
        buildBrowse()
        buildGuide()
        note = ui.label(15f, white(225), lines = 4).apply { gravity = Gravity.CENTER; setPadding(u(80), 0, u(80), 0) }
        root.addView(note, FrameLayout.LayoutParams(-1, -2, Gravity.CENTER))
        digits = ui.label(40f, Color.WHITE, true).apply { setShadowLayer(u(6).toFloat(), 0f, 0f, Color.BLACK); visibility = View.GONE }
        root.addView(digits, FrameLayout.LayoutParams(-2, -2, Gravity.TOP or Gravity.END).apply { setMargins(0, u(28), u(44), 0) })
        setContentView(root)
        page = Page(ui, root, "Settings", "Channels " + BuildConfig.VERSION_NAME)
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
        buildPlayer()
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
    }

    override fun onStop() {
        super.onStop()
        main.removeCallbacksAndMessages(null)
        if (browsing) closeBrowse(false)
        player?.release()
        player = null
    }

    private fun onChannels() {
        val l = Repo.lib
        if (l == null) {
            say(if (Repo.error == "none") "No playlist yet.\nPress MENU or OK for the settings to give its link." else "Could not read the playlist.\nPress MENU or OK for the settings to try again.")
            lib = null
            page.refresh()
            return
        }
        lib = l
        say("")
        scope = l.list(scopeName, favs, recents).ifEmpty { scopeName = Library.ALL; l.list(Library.ALL, favs, recents) }
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
        play(if (i in l.channels.indices) i else scope.firstOrNull() ?: 0)
    }

    private fun onGuide() {
        if (infoBar.alpha > 0f) fillInfo()
        if (browsing) { chanAdapter.notifyDataSetChanged(); if (detailsFor >= 0) fillDetails(detailsFor) }
        page.refresh()
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
    private val tsFlags get() = if ("plain" in trial) 0 else DefaultTsPayloadReaderFactory.FLAG_ALLOW_NON_IDR_KEYFRAMES or DefaultTsPayloadReaderFactory.FLAG_DETECT_ACCESS_UNITS

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
        var refTime = -1L
        var decided = false
        var moveVideo = false
        /** in the stream's own units (90,000 a second); 0 = nothing to move */
        var delta = 0L
        var rawVideo = 0L
        var rawSound = 0L

    }
    private var align = Align()
    /** the sound's own times are not to be trusted on the channel that plays: it is timed by its length instead */
    @Volatile private var steadySound = false

    /** reads the stream's pieces as usual, and puts picture and sound on one clock where a channel needs it ([Align]) */
    private inner class Pieces(private val al: Align) : androidx.media3.exoplayer.hls.HlsExtractorFactory {
        private val usual = DefaultHlsExtractorFactory(tsFlags, true)
        override fun createExtractor(uri: Uri, format: androidx.media3.common.Format, muxedCaptionFormats: MutableList<androidx.media3.common.Format>?,
                                     timestampAdjuster: androidx.media3.common.util.TimestampAdjuster, responseHeaders: MutableMap<String, MutableList<String>>,
                                     sniffingExtractorInput: androidx.media3.extractor.ExtractorInput, playerId: androidx.media3.exoplayer.analytics.PlayerId): androidx.media3.exoplayer.hls.HlsMediaChunkExtractor {
            val readers = object : androidx.media3.extractor.ts.TsPayloadReader.Factory {
                val d = DefaultTsPayloadReaderFactory(tsFlags)
                override fun createInitialPayloadReaders() = d.createInitialPayloadReaders()
                override fun createPayloadReader(streamType: Int, esInfo: androidx.media3.extractor.ts.TsPayloadReader.EsInfo): androidx.media3.extractor.ts.TsPayloadReader? {
                    val r = d.createPayloadReader(streamType, esInfo) ?: return null
                    // (0x15 = notes beside the programme, 0x06 may be subtitles: neither has a part in this)
                    if (streamType == 0x15 || streamType == 0x06 || "asis" in trial) return r
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
                                        if (al.refIsVideo == null) al.refIsVideo = isVideo
                                        if (al.refIsVideo == isVideo) al.refTime = t
                                        else {
                                            al.decided = true
                                            val diff = Stamp.diff(t, al.refTime)
                                            if (kotlin.math.abs(diff) > 5 * 90000L) {
                                                al.delta = diff; al.moveVideo = isVideo
                                                steadySound = true
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
            @Suppress("DEPRECATION")
            val ts = androidx.media3.extractor.ts.TsExtractor(androidx.media3.extractor.ts.TsExtractor.MODE_HLS, timestampAdjuster, readers)
            val isTs = try { ts.sniff(sniffingExtractorInput) } catch (_: java.io.EOFException) { false } finally { sniffingExtractorInput.resetPeekPosition() }
            return if (isTs) androidx.media3.exoplayer.hls.BundledHlsMediaChunkExtractor(ts, format, timestampAdjuster)
            else usual.createExtractor(uri, format, muxedCaptionFormats, timestampAdjuster, responseHeaders, sniffingExtractorInput, playerId)
        }
    }

    private fun source(address: String): MediaSource {
        val item = MediaItem.fromUri(address)
        return if (Util.inferContentType(Uri.parse(address)) == C.CONTENT_TYPE_HLS)
            HlsMediaSource.Factory(http).setExtractorFactory(Pieces(align)).createMediaSource(item)
        else DefaultMediaSourceFactory(http, DefaultExtractorsFactory().setTsExtractorFlags(tsFlags)).createMediaSource(item)
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

    /** the sound's clock, moved by the "Sound delay" setting: the picture follows this clock */
    private inner class Renderers(ctx: Context) : DefaultRenderersFactory(ctx) {
        override fun buildAudioSink(context: Context, enableFloatOutput: Boolean, enableAudioTrackPlaybackParams: Boolean): AudioSink? {
            val sink = super.buildAudioSink(context, enableFloatOutput, enableAudioTrackPlaybackParams) ?: return null
            return object : ForwardingAudioSink(sink) {
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
            .setTrackTypeDisabled(C.TRACK_TYPE_AUDIO, "nosound" in trial).setTrackTypeDisabled(C.TRACK_TYPE_VIDEO, "nopicture" in trial))
        val p = ExoPlayer.Builder(this, Renderers(this)).setTrackSelector(sel).build()
        p.setVideoSurfaceView(surface)
        p.addListener(listener)
        // (DEBUG) which pieces of the stream were fetched, and the times they carry: never their addresses
        p.addAnalyticsListener(object : androidx.media3.exoplayer.analytics.AnalyticsListener {
            override fun onLoadCompleted(t: androidx.media3.exoplayer.analytics.AnalyticsListener.EventTime, l: androidx.media3.exoplayer.source.LoadEventInfo, d: androidx.media3.exoplayer.source.MediaLoadData) {
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
    private var stuck = 0
    private var rateAt = 0L
    private var rateFrom = 0
    private var rated = false
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
                Log.d(tag, "#${current + 1} state=${p.playbackState} at=${p.currentPosition}ms tunnel=$tunnel buffered=${p.totalBufferedDuration}ms " +
                    "video[in=${v?.queuedInputBufferCount} out=${v?.renderedOutputBufferCount} drop=${v?.droppedBufferCount}] audio[out=${a?.renderedOutputBufferCount}]")
            }
            val now = SystemClock.elapsedRealtime()
            val frames = if (v == null) -1 else v.renderedOutputBufferCount + v.droppedBufferCount + v.skippedOutputBufferCount
            if (p.playbackState == Player.STATE_READY && p.playWhenReady && frames >= 0) {
                if (frames == lastFrames) stuck++ else stuck = 0
                if (tunnel && stuck >= 4) {
                    Log.w(tag, "#${current + 1} the picture stopped with hardware sync: standard way")
                    noTunnel.add(current)
                    play(current, true, browsing)
                    return
                }
                if (!rated) {
                    if (rateAt == 0L) { if (frames > 0) { rateAt = now; rateFrom = frames } }
                    else if (now - rateAt >= 5500 && frames - rateFrom < 40) { rateAt = now; rateFrom = frames }      // (it had not got going yet)
                    else if (now - rateAt >= 5500) {
                        rated = true
                        channelFps = (frames - rateFrom) * 1000f / (now - rateAt)
                        Log.i(tag, "#${current + 1} ${"%.1f".format(Locale.US, channelFps)} pictures a second")
                        matchScreen()
                        page.refresh()
                    }
                }
            } else rateAt = 0L
            lastFrames = frames
            main.postDelayed(this, 2000)
        }
    }

    /** the screen's rate that suits [fps] pictures a second, or 0 when the screen is best left as it is */
    private fun rateFor(fps: Float): Float = when {
        fps in 24.6f..25.6f || fps in 48.5f..51f -> 50f
        fps in 29f..30.6f || fps in 58f..61f -> 60f
        else -> 0f
    }

    /** switches the screen to the rate of the channel that plays (same resolution), so that motion is even */
    private fun matchScreen() {
        val lp = window.attributes
        var want = 0
        val target = if (prefs.matchRate) rateFor(channelFps) else 0f
        @Suppress("DEPRECATION") val d = windowManager.defaultDisplay
        val cur = d.mode
        if (target > 0f && kotlin.math.abs(cur.refreshRate - target) > 0.2f) {
            // (of 59.94 and 60, the one broadcasts use)
            val aim = if (target == 60f) 59.94f else target
            val m = d.supportedModes.filter { it.physicalWidth == cur.physicalWidth && it.physicalHeight == cur.physicalHeight && kotlin.math.abs(it.refreshRate - target) < 0.2f }
                .minByOrNull { kotlin.math.abs(it.refreshRate - aim) }
            if (m != null) { want = m.modeId; Log.i(tag, "screen: ${"%.2f".format(Locale.US, cur.refreshRate)} to ${"%.2f".format(Locale.US, m.refreshRate)} a second") }
        } else if (target > 0f) want = cur.modeId
        // (0 = no wish: when the setting is off, or the channel's rate has no screen rate that suits)
        if (lp.preferredDisplayModeId != want && (want != 0 || !prefs.matchRate)) { lp.preferredDisplayModeId = want; window.attributes = lp }
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
            if (i < 0) null else k + "\t" + l.channels[i].name.replace('\t', ' ') + "\t" + Repo.logo(i)
        }
        prefs.homeRows = rows
        Home.publish(this, rows)
    }

    /** [preview]: the channel is only looked at in the small window; it is not remembered as the one that was on */
    private fun play(i: Int, again: Boolean = false, preview: Boolean = false) {
        val p = player ?: return
        val l = lib ?: return
        val c = l.channels.getOrNull(i) ?: return
        if (!again) retries = 0
        current = i
        if (testStream == null && !preview) prefs.last = l.keyOf(i)
        say("")
        if (!preview && !browsing && !page.isOpen) showInfo()
        setTunnel(prefs.hardwareSync && i !in noTunnel)
        firstPicture = false
        formatLine = ""
        lastFrames = -1; stuck = 0; rateAt = 0L; rated = false; channelFps = 0f
        startedAt = SystemClock.elapsedRealtime()
        Log.i(tag, "play #${i + 1}" + (if (tunnel) " (hardware sync)" else ""))
        main.removeCallbacks(probe); main.postDelayed(probe, 2000)
        main.removeCallbacks(tunnelCheck); if (tunnel) main.postDelayed(tunnelCheck, 8000)
        main.removeCallbacks(watched); if (!preview) main.postDelayed(watched, 30_000)
        align = Align()
        steadySound = false
        p.setMediaSource(source(c.url))
        p.prepare()
        p.playWhenReady = true
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
        }
        override fun onRenderedFirstFrame() {
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
            for (g in t.groups) for (i in 0 until g.length) if (g.isTrackSelected(i)) {
                val f = g.getTrackFormat(i)
                val what = when (g.type) { C.TRACK_TYPE_VIDEO -> "video"; C.TRACK_TYPE_AUDIO -> "audio"; C.TRACK_TYPE_TEXT -> "text"; else -> "other" }
                Log.i(tag, "#${current + 1} $what ${f.sampleMimeType} ${if (f.width > 0) "${f.width}x${f.height}" else ""} ${if (f.channelCount > 0) "${f.channelCount}ch" else ""} ${f.language.orEmpty()} supported=${g.isTrackSupported(i)}")
                val kind = when (f.sampleMimeType) {
                    "video/avc" -> "H.264"; "video/hevc" -> "H.265"; "video/mpeg2" -> "MPEG-2"
                    "audio/mp4a-latm" -> "AAC"; "audio/mpeg-L2" -> "MPEG audio"; "audio/mpeg" -> "MP3"; "audio/ac3" -> "Dolby Digital"; "audio/eac3" -> "Dolby Digital Plus"
                    else -> null
                }
                if (g.type == C.TRACK_TYPE_VIDEO && f.width > 0) parts += "${f.width} x ${f.height}" + (if (kind != null) "  $kind" else "")
                else if (g.type == C.TRACK_TYPE_AUDIO && kind != null) parts += kind
            }
            formatLine = parts.joinToString("  ·  ")
            page.refresh()
        }
        override fun onPlayerError(e: PlaybackException) {
            // (the message may hold the stream's address: only the kind of error is logged)
            Log.w(tag, "#${current + 1} error ${e.errorCodeName} (${e.cause?.javaClass?.simpleName})" + (if (retries < 2) ", trying again" else ""))
            // a decoder that stumbles while channels are changed, or a live stream that moved on, plays at the next try;
            // the second try is without the hardware keeping step, in case that is what it stumbles over
            if (retries < 2) {
                retries++
                if (retries == 2 && tunnel) noTunnel.add(current)
                main.postDelayed({ play(current, true, browsing) }, 300L * retries)
                return
            }
            say("This channel does not play right now.")
        }
    }

    // ---------------------------------------------------------------- where the picture is

    /** the whole screen, or the small window of the channel list; the picture keeps its shape inside it */
    private fun placeSurface() {
        val dm = resources.displayMetrics
        val boxX: Int; val boxY: Int; val boxW: Int; val boxH: Int
        if (browsing) { boxX = u(MINI_X); boxY = u(MINI_Y); boxW = u(MINI_W); boxH = u(MINI_W) * 9 / 16 }
        else { boxX = 0; boxY = 0; boxW = dm.widthPixels; boxH = dm.heightPixels }
        var w = boxW; var h = (boxW / videoAspect).toInt()
        if (h > boxH) { h = boxH; w = (boxH * videoAspect).toInt() }
        val lp = surface.layoutParams as FrameLayout.LayoutParams
        lp.width = w; lp.height = h
        lp.leftMargin = boxX + (boxW - w) / 2
        lp.topMargin = boxY + (boxH - h) / 2
        surface.layoutParams = lp
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
        infoName = ui.label(19f, Color.WHITE, true)
        infoNow = ui.label(13.5f, white(235))
        infoProg = Bar(this)
        infoNext = ui.label(11.5f, white(160))
        col.addView(infoName)
        col.addView(infoNow, LinearLayout.LayoutParams(-1, -2).apply { topMargin = u(2) })
        col.addView(infoProg, LinearLayout.LayoutParams(u(360), maxOf(3, u(2.5f))).apply { topMargin = u(6) })
        col.addView(infoNext, LinearLayout.LayoutParams(-1, -2).apply { topMargin = u(5) })
        infoBar.addView(col, LinearLayout.LayoutParams(0, -2, 1f).apply { leftMargin = u(18) })
        infoClock = ui.label(22f, white(235)).apply { typeface = Typeface.create("sans-serif-light", Typeface.NORMAL) }
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

    // ---------------------------------------------------------------- the channel window

    private lateinit var backdrop: View
    private lateinit var browse: LinearLayout
    private lateinit var groupList: ListView
    private lateinit var chanList: ListView
    private lateinit var chanHint: TextView
    private lateinit var dLogo: ImageView
    private lateinit var dName: TextView
    private lateinit var dNow: TextView
    private lateinit var dTime: TextView
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
        val on = ui.rounded(white(38), u(9), u(2), accent)
        val off = ui.rounded(Color.TRANSPARENT, 0)
        selector = off
        setOnFocusChangeListener { _, has -> selector = if (has) on else off; invalidateViews() }
        setDrawSelectorOnTop(false)
        clipToPadding = false
        setPadding(0, u(20), 0, u(20))
    }

    private val groupAdapter = object : BaseAdapter() {
        override fun getCount() = groupRows.size
        override fun getItem(p: Int) = groupRows[p]
        override fun getItemId(p: Int) = p.toLong()
        override fun getView(p: Int, old: View?, parent: ViewGroup): View {
            val t = (old as? TextView) ?: ui.label(13f, Color.WHITE).apply { setPadding(u(12), u(8), u(8), u(8.5f)) }
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
                setPadding(u(4), u(5), u(8), u(5))
                val num = ui.label(10.5f, white(120)).apply { gravity = Gravity.END }
                addView(num, LinearLayout.LayoutParams(u(32), -2))
                val logo = ImageView(context).apply { scaleType = ImageView.ScaleType.FIT_CENTER }
                addView(logo, LinearLayout.LayoutParams(u(36), u(36)).apply { leftMargin = u(9) })
                val col = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
                val name = ui.label(13.5f, Color.WHITE)
                val now = ui.label(10.5f, white(150))
                val bar = Bar(context)
                col.addView(name); col.addView(now)
                col.addView(bar, LinearLayout.LayoutParams(-1, maxOf(2, u(1.5f))).apply { topMargin = u(3) })
                addView(col, LinearLayout.LayoutParams(0, -2, 1f).apply { leftMargin = u(11) })
                val star = ui.label(13f, accent)
                addView(star, LinearLayout.LayoutParams(-2, -2).apply { leftMargin = u(6) })
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
            h.now.text = cur?.title ?: ""
            (h.bar as Bar).f = if (cur != null) Guide.progress(cur, t) else -1f
            h.star.text = if (l.keyOf(i) in favs) "\u2605" else ""
            Images.show(Repo.logo(i), h.logo, u(36))
            return row
        }
    }

    private fun buildBrowse() {
        browse = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(u(SIDE_PAD), 0, u(28), 0)
            visibility = View.GONE
        }
        groupList = list().apply { adapter = groupAdapter }
        chanList = list().apply { adapter = chanAdapter }
        browse.addView(groupList, LinearLayout.LayoutParams(u(GROUPS_W), -1))
        val mid = FrameLayout(this)
        mid.addView(chanList, FrameLayout.LayoutParams(-1, -1))
        chanHint = ui.label(12.5f, white(170), lines = 6).apply { setPadding(u(18), u(32), u(18), 0) }
        mid.addView(chanHint, FrameLayout.LayoutParams(-1, -2))
        browse.addView(mid, LinearLayout.LayoutParams(u(CHANS_W), -1).apply { leftMargin = u(GAP) })

        // the right side: the picture's window is left free, what the channel shows is told below it
        val right = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(0, u(MINI_Y) + u(MINI_W) * 9 / 16 + u(14), 0, u(16)) }
        val head = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        dLogo = ImageView(this).apply { scaleType = ImageView.ScaleType.FIT_CENTER }
        head.addView(dLogo, LinearLayout.LayoutParams(u(34), u(34)))
        dName = ui.label(16f, Color.WHITE, true)
        head.addView(dName, LinearLayout.LayoutParams(0, -2, 1f).apply { leftMargin = u(10) })
        right.addView(head)
        dNow = ui.label(14f, accent, true, lines = 2)
        right.addView(dNow, LinearLayout.LayoutParams(-1, -2).apply { topMargin = u(10) })
        dTime = ui.label(11f, white(150))
        right.addView(dTime, LinearLayout.LayoutParams(-1, -2).apply { topMargin = u(2) })
        dBar = Bar(this)
        right.addView(dBar, LinearLayout.LayoutParams(-1, maxOf(3, u(2.5f))).apply { topMargin = u(6) })
        dDesc = ui.label(11.5f, white(205), lines = 5).apply { setLineSpacing(u(2.5f).toFloat(), 1f) }
        right.addView(dDesc, LinearLayout.LayoutParams(-1, -2).apply { topMargin = u(9) })
        dLaterCaps = ui.caps("LATER", white(120))
        right.addView(dLaterCaps, LinearLayout.LayoutParams(-2, -2).apply { topMargin = u(12) })
        dLater = ui.label(11.5f, white(190), lines = 4).apply { setLineSpacing(u(3).toFloat(), 1f) }
        right.addView(dLater, LinearLayout.LayoutParams(-1, -2).apply { topMargin = u(4) })
        browse.addView(right, LinearLayout.LayoutParams(u(MINI_W), -1).apply { leftMargin = u(GAP_RIGHT) })
        root.addView(browse, FrameLayout.LayoutParams(-1, -1))

        groupList.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, v: View?, p: Int, id: Long) { if (groupList.hasFocus()) choose(groupRows[p].first, false) }
            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }
        groupList.setOnItemClickListener { _, _, p, _ ->
            when (val id = groupRows[p].first) {
                "search" -> askSearch()
                "settings" -> openSettings()
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
        chanList.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, v: View?, p: Int, id: Long) { if (chanList.hasFocus()) shown.getOrNull(p)?.let { look(it) } }
            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }
        chanList.setOnFocusChangeListener { v, has ->
            // (this replaces the listener set in list(): it paints the selector too, and looks at the channel under the focus)
            val lv = v as ListView
            lv.selector = if (has) ui.rounded(white(38), u(9), u(2), accent) else ui.rounded(Color.TRANSPARENT, 0)
            lv.invalidateViews()
            if (has) shown.getOrNull(lv.selectedItemPosition)?.let { look(it) }
        }
        chanList.setOnItemClickListener { _, _, p, _ ->
            val i = shown[p]
            scopeName = shownName; scope = shown; prefs.scope = scopeName
            closeBrowse(false)
            lib?.let { prefs.last = it.keyOf(i) }
            if (i != current) play(i) else { showInfo(); main.removeCallbacks(watched); main.postDelayed(watched, 30_000) }
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
                KeyEvent.KEYCODE_DPAD_LEFT -> { if (ev.action == KeyEvent.ACTION_DOWN) groupList.requestFocus(); true }
                KeyEvent.KEYCODE_DPAD_RIGHT -> true
                else -> false
            }
        }
    }

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
                "settings" -> "Press OK for the settings: playlist, guide, playback and the home screen."
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
        browse.visibility = View.VISIBLE
        placeSurface()
        val name = if (groupRows.any { it.first == scopeName } && current in l.list(scopeName, favs, recents)) scopeName else Library.ALL
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
        browse.visibility = View.GONE
        backdrop.visibility = View.GONE
        detailsFor = -1
        placeSurface()
        if (back && before >= 0) {
            if (current != before) play(before) else { main.removeCallbacks(watched); main.postDelayed(watched, 30_000) }
        }
    }

    /** the focus is on channel [i]: tell about it at once, and after a moment show it in the small window */
    private fun look(i: Int) {
        fillDetails(i)
        main.removeCallbacks(lookLater)
        main.postDelayed(lookLater, 600)
    }

    private val lookLater = Runnable {
        if (!browsing) return@Runnable
        val i = detailsFor
        if (i >= 0 && i != current) play(i, preview = true)
    }

    /** the right side: what channel [i] shows now, what that is about, and what comes later */
    private fun fillDetails(i: Int) {
        val l = lib ?: return
        val c = l.channels.getOrNull(i) ?: return
        val changed = detailsFor != i
        detailsFor = i
        val t = System.currentTimeMillis()
        dName.text = "${i + 1}   ${c.name}"
        Images.show(Repo.logo(i), dLogo, u(34))
        val (cur, next) = Repo.nowNext(i, t)
        dNow.text = cur?.title ?: "No programme guide for this channel"
        dNow.setTextColor(if (cur != null) accent else white(150))
        dTime.text = if (cur != null) hm(cur.start) + " \u2013 " + hm(cur.stop) else c.group
        dBar.f = if (cur != null) Guide.progress(cur, t) else -1f
        if (changed) {
            dDesc.text = ""
            dLater.text = if (next != null) hm(next.start) + "   " + next.title else ""
            dLaterCaps.visibility = if (next != null) View.VISIBLE else View.INVISIBLE
            // what it is about, and the rest of the evening, are read from the guide's file
            Thread({
                val all = Repo.programmes(this, i)
                main.post {
                    if (detailsFor != i || !browsing) return@post
                    val now = System.currentTimeMillis()
                    val on = all.firstOrNull { it.start <= now && now < it.stop }
                    dDesc.text = on?.desc.orEmpty()
                    val later = all.filter { it.start >= (on?.stop ?: now) }.take(4)
                    dLater.text = later.joinToString("\n") { hm(it.start) + "   " + it.title }
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

    private val guideAdapter = object : BaseAdapter() {
        override fun getCount() = progs.size
        override fun getItem(p: Int) = progs[p]
        override fun getItemId(p: Int) = p.toLong()
        override fun getView(p: Int, old: View?, parent: ViewGroup): View {
            val row = (old as? LinearLayout) ?: LinearLayout(this@MainActivity).apply {
                orientation = LinearLayout.HORIZONTAL
                setPadding(u(10), u(6), u(10), u(6))
                addView(ui.label(12f, white(150)), LinearLayout.LayoutParams(u(46), -2))
                val col = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
                col.addView(ui.label(12.5f, Color.WHITE, lines = 2))
                col.addView(ui.label(10.5f, white(150), lines = 3).apply { setLineSpacing(u(2).toFloat(), 1f) })
                addView(col, LinearLayout.LayoutParams(0, -2, 1f))
            }
            val g = progs[p]
            val t = System.currentTimeMillis()
            val on = g.start <= t && t < g.stop
            (row.getChildAt(0) as TextView).apply { text = hm(g.start); setTextColor(if (on) accent else white(150)) }
            val col = row.getChildAt(1) as LinearLayout
            (col.getChildAt(0) as TextView).apply { text = g.title; setTextColor(if (on) accent else if (g.stop <= t) white(110) else Color.WHITE) }
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
        guideTitle = ui.label(11f, accent, true, lines = 4).apply { letterSpacing = 0.14f; setPadding(u(10), u(24), u(10), 0) }
        guideBox.addView(guideTitle)
        guideList = list().apply { adapter = guideAdapter }
        guideBox.addView(guideList, LinearLayout.LayoutParams(-1, 0, 1f))
        root.addView(guideBox, FrameLayout.LayoutParams(u(400), -1, Gravity.START))
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
        val name = l.channels[i].name.uppercase(Locale.getDefault())
        guideTitle.text = name
        guideBox.visibility = View.VISIBLE
        progs = emptyList(); guideAdapter.notifyDataSetChanged()
        guideList.requestFocus()
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

    private fun reload(force: Boolean) {
        if (browsing) closeBrowse(false)
        lib = null; current = -1
        player?.stop()
        say("Reading the playlist")
        Repo.start(this, force, { onChannels() }, { onGuide() })
        page.refresh()
    }

    private lateinit var page: Page

    private fun link(title: String, message: String, hint: String, text: String, done: (String) -> Unit) {
        val sh = Sheet(ui, title, message)
        val e = sh.input(text, hint, InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI)
        sh.button("Cancel").button("Save", primary = true) { done(e.text.toString().trim()) }.show { page.focus() }
    }

    private fun openSettings() {
        if (browsing) closeBrowse(true)
        hideGuide()
        main.removeCallbacks(hideInfo); infoBar.alpha = 0f
        fun num(n: Int) = String.format(Locale.US, "%,d", n)
        val tabs = listOf(
            Page.Tab("Playlist", "Your channels come from a playlist link (M3U) that your provider gives you. The link holds your subscription key: " +
                "it is kept only inside this app, and is not shown again once it is saved.",
                stats = {
                    val l = Repo.lib
                    listOf("CHANNELS" to (if (l == null) (if (Repo.working) "Reading" else "None") else num(l.channels.size) + " in " + l.groups.size + " groups"),
                        "WITH A LOGO" to (if (l == null) "\u2014" else num(l.channels.indices.count { Repo.logo(it).isNotEmpty() })),
                        "LAST FETCHED" to stamp(Repo.listTime(this)))
                }, rows = listOf(
                Page.Row("Playlist link", "The link from your provider", { if (prefs.playlist.isEmpty()) "Not set" else "Set" }, steps = false) {
                    link("Playlist link", "Paste or type the link of your playlist. The one in use is not shown here, because it holds your subscription key.",
                        "The link of your playlist (.m3u or .m3u8)", "") { v -> if (v.isNotEmpty()) { prefs.playlist = v; Repo.forget(this); reload(true) } }
                },
                Page.Row("Logos list", "An optional second list that carries the channels' logos", { if (prefs.extra.isEmpty()) "None" else "Set" }, steps = false) {
                    link("Logos list", "Some providers' playlists have no logos. A second, public list can supply them: channels are matched by number, else by name. Leave empty for none.",
                        "The link of the list with logos", prefs.extra) { v -> prefs.extra = v; Repo.forget(this); reload(true) }
                },
                Page.Row("Fetch the playlist again", "For channels that were added or moved", { if (Repo.working) "Working" else "" }, steps = false) { reload(true) }
            )),
            Page.Tab("Guide", "What is on now and later comes from a programme guide (XMLTV). Most playlists name one, so there is usually nothing to set. " +
                "It is read again by itself twice a day; a large guide takes about a minute.",
                stats = {
                    val l = Repo.lib
                    listOf("STATUS" to Repo.guideNote.ifEmpty { if (Repo.guideTime(this) > 0) "Ready" else if (Repo.working) "Reading the guide" else "Not read yet" },
                        "CHANNELS WITH A GUIDE" to (if (l == null) "\u2014" else num(Repo.covered) + " of " + num(l.channels.size)),
                        "LAST READ" to stamp(Repo.guideTime(this)),
                        "FROM" to (Uri.parse(Repo.guideLink).host ?: "\u2014"))
                }, rows = listOf(
                Page.Row("Guide link", "XMLTV, .xml or .xml.gz", { if (prefs.guide.isEmpty()) "From the playlist" else "Your own" }, steps = false) {
                    link("Guide link", "The link of a programme guide in XMLTV form. Leave empty to use the one your playlist names.",
                        "The link of the guide (.xml or .xml.gz)", prefs.guide) { v -> prefs.guide = v; Repo.forget(this); reload(true) }
                },
                Page.Row("Read the guide again", "Also fetches the playlist again", { if (Repo.working) "Working" else "" }, steps = false) { reload(true) }
            )),
            Page.Tab("Playback", "Picture and sound are kept in step the standard way, where you can move the sound yourself, or by the box's own video hardware. " +
                "A channel whose picture stops with the hardware is played the standard way by itself. When the screen follows the channel, it goes dark for a moment as it changes.",
                stats = {
                    listOf("THIS CHANNEL" to formatLine.ifEmpty { "\u2014" } + (if (channelFps > 0f) "\n" + Math.round(channelFps) + " pictures a second" else ""),
                        "SCREEN" to screenLine(),
                        "IN STEP BY" to (if (tunnel) "The video hardware" else if (prefs.hardwareSync) "Standard (this channel does not play with the hardware)" else "Standard" +
                            (if (prefs.soundDelay != 0) ", sound " + (if (prefs.soundDelay > 0) "+" else "") + prefs.soundDelay + " ms" else "")))
                }, rows = listOf(
                Page.Row("Picture and sound in step", "Who keeps them together", { if (prefs.hardwareSync) "Video hardware" else "Standard" }) {
                    prefs.hardwareSync = !prefs.hardwareSync
                    noTunnel.clear()
                    if (current >= 0) play(current, true)
                },
                Page.Row("Sound delay", "+ plays the sound later, \u2212 earlier. If lips move before the words, add", {
                    (if (prefs.soundDelay > 0) "+" else "") + prefs.soundDelay + " ms" }, { !prefs.hardwareSync }) { d ->
                    prefs.soundDelay = prefs.soundDelay + 20 * d
                    delayUs = prefs.soundDelay * 1000L
                },
                Page.Row("Screen follows the channel", "For even motion the TV is switched to 50 or 60 a second", { if (prefs.matchRate) "On" else "Off" }) {
                    prefs.matchRate = !prefs.matchRate
                    matchScreen()
                },
                Page.Row("Up on the remote", "Which way Up and Down go through the channels", { if (prefs.upIsNext) "Next channel" else "Previous channel" }) {
                    prefs.upIsNext = !prefs.upIsNext
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
            ))
        )
        page.open(tabs)
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
        if (page.isOpen) {
            if (keyCode == KeyEvent.KEYCODE_BACK || keyCode == KeyEvent.KEYCODE_MENU) { page.close(); return true }
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
        when (keyCode) {
            KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_CHANNEL_UP, KeyEvent.KEYCODE_MEDIA_NEXT -> { zap(1); return true }
            KeyEvent.KEYCODE_DPAD_DOWN, KeyEvent.KEYCODE_CHANNEL_DOWN, KeyEvent.KEYCODE_MEDIA_PREVIOUS -> { zap(-1); return true }
            KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_DPAD_LEFT -> { openBrowse(); return true }
            KeyEvent.KEYCODE_MENU, KeyEvent.KEYCODE_SETTINGS -> { if (testStream == null) openSettings(); return true }
            KeyEvent.KEYCODE_DPAD_RIGHT, KeyEvent.KEYCODE_GUIDE -> { if (lib != null && current >= 0 && testStream == null) showGuide(current); return true }
            KeyEvent.KEYCODE_INFO -> { if (infoBar.alpha > 0f) { main.removeCallbacks(hideInfo); hideInfo.run() } else showInfo(8000); return true }
            KeyEvent.KEYCODE_BACK -> if (infoBar.alpha > 0.5f) { main.removeCallbacks(hideInfo); hideInfo.run(); return true }
        }
        return super.onKeyDown(keyCode, event)
    }

    private companion object {
        // the channel window, in screen units (the screen is 960 wide): lists, channels, then the picture's small window
        const val SIDE_PAD = 22
        const val GROUPS_W = 150
        const val GAP = 8
        const val CHANS_W = 330
        const val GAP_RIGHT = 22
        const val MINI_W = 400
        const val MINI_X = SIDE_PAD + GROUPS_W + GAP + CHANS_W + GAP_RIGHT
        const val MINI_Y = 26
    }
}
