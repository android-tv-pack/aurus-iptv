package app.hermes.channels

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import android.util.TypedValue
import android.view.Gravity
import android.view.KeyEvent
import android.view.SurfaceView
import android.view.View
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.TextView
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.Tracks
import androidx.media3.common.VideoSize
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/**
 * Stage 1: the playback test. Reads the playlist, plays a channel full-screen, Up / Down change the channel.
 *
 * The playlist link holds the subscription key: it is kept in the app's private settings and never logged;
 * the log names channels by their number in the list only.
 */
class MainActivity : Activity() {

    private val tag = "Channels"
    private val main = Handler(Looper.getMainLooper())
    private lateinit var surface: SurfaceView
    private lateinit var bar: TextView
    private lateinit var note: TextView
    private var player: ExoPlayer? = null
    private var channels: List<M3u.Channel> = emptyList()
    private var index = 0
    private var startedAt = 0L
    private val prefs by lazy { getSharedPreferences("s", Context.MODE_PRIVATE) }
    private val hideBar = Runnable { bar.animate().alpha(0f).setDuration(400).start() }

    private fun u(n: Float): Int = (n * resources.displayMetrics.heightPixels / 540f).toInt()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        val root = FrameLayout(this).apply { setBackgroundColor(Color.BLACK) }
        surface = SurfaceView(this)
        root.addView(surface, FrameLayout.LayoutParams(-1, -1, Gravity.CENTER))
        bar = TextView(this).apply {
            setTextSize(TypedValue.COMPLEX_UNIT_PX, u(17f).toFloat())
            setTextColor(Color.WHITE)
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            setShadowLayer(u(4f).toFloat(), 0f, 0f, Color.BLACK)
            setPadding(u(40f), u(24f), u(40f), u(24f))
            alpha = 0f
        }
        root.addView(bar, FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM))
        note = TextView(this).apply {
            setTextSize(TypedValue.COMPLEX_UNIT_PX, u(15f).toFloat())
            setTextColor(Color.argb(220, 255, 255, 255))
            gravity = Gravity.CENTER
            setPadding(u(80f), 0, u(80f), 0)
        }
        root.addView(note, FrameLayout.LayoutParams(-1, -2, Gravity.CENTER))
        setContentView(root)
        takeDropped()
        index = prefs.getInt("index", 0)
    }

    /** `playlist.txt` dropped into the app's files folder: line 1 the playlist link, line 2 (optional) a companion list with logos */
    private fun takeDropped() {
        val f = File(getExternalFilesDir(null) ?: return, "playlist.txt")
        if (!f.exists()) return
        val lines = try { f.readLines().map { it.trim() }.filter { it.isNotEmpty() } } catch (_: Exception) { emptyList() }
        f.delete()
        if (lines.isEmpty()) return
        prefs.edit().putString("playlist", lines[0]).putString("extra", lines.getOrNull(1).orEmpty()).apply()
        File(filesDir, "playlist.m3u").delete()
        Log.i(tag, "a playlist link was taken")
    }

    override fun onStart() {
        super.onStart()
        val p = ExoPlayer.Builder(this)
            .setMediaSourceFactory(DefaultMediaSourceFactory(DefaultHttpDataSource.Factory()
                .setUserAgent("Mozilla/5.0 (Linux; Android) Channels")
                .setAllowCrossProtocolRedirects(true)
                .setConnectTimeoutMs(10000).setReadTimeoutMs(15000)))
            .build()
        p.setVideoSurfaceView(surface)
        p.addListener(listener)
        player = p
        // (for testing) am start ... --es test <address of a stream>
        val test = intent.getStringExtra("test")
        if (test != null) {
            channels = listOf(M3u.Channel("Test stream", "", test, "", ""))
            index = 0
            play()
        } else load()
    }

    override fun onStop() {
        super.onStop()
        main.removeCallbacksAndMessages(null)
        player?.release()
        player = null
    }

    override fun onNewIntent(i: Intent) {
        super.onNewIntent(i)
        setIntent(i)
    }

    private fun say(s: String) { note.text = s; note.visibility = if (s.isEmpty()) View.GONE else View.VISIBLE }

    private fun http(address: String): String {
        var c = URL(address).openConnection() as HttpURLConnection
        var hops = 0
        while (true) {
            c.connectTimeout = 10000; c.readTimeout = 30000
            c.setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android) Channels")
            val code = c.responseCode
            if (code in 301..308 && hops++ < 4) {
                val next = URL(c.url, c.getHeaderField("Location") ?: throw java.io.IOException("HTTP $code"))
                c.disconnect(); c = next.openConnection() as HttpURLConnection
                continue
            }
            if (code != 200) throw java.io.IOException("HTTP $code")
            return c.inputStream.bufferedReader().use { it.readText() }
        }
    }

    private fun load() {
        val link = prefs.getString("playlist", "").orEmpty()
        if (link.isEmpty()) { say("No playlist yet.\nPut its link into playlist.txt in this app's files folder."); return }
        say("Reading the playlist")
        Thread({
            try {
                val cache = File(filesDir, "playlist.m3u")
                val t0 = SystemClock.elapsedRealtime()
                val text = try { http(link).also { cache.writeText(it) } } catch (e: Exception) { if (cache.exists()) cache.readText() else throw e }
                val list = M3u.parse(text)
                Log.i(tag, "playlist: ${list.channels.size} channels, ${M3u.groups(list.channels).size} groups, ${SystemClock.elapsedRealtime() - t0} ms")
                main.post {
                    channels = list.channels
                    if (channels.isEmpty()) say("The playlist has no channels.") else { index = index.coerceIn(0, channels.size - 1); play() }
                }
            } catch (e: Exception) {
                Log.w(tag, "playlist failed: ${e.javaClass.simpleName}")
                main.post { say("Could not read the playlist.") }
            }
        }, "channels-list").start()
    }

    private fun play() {
        val p = player ?: return
        val c = channels.getOrNull(index) ?: return
        prefs.edit().putInt("index", index).apply()
        say("")
        bar.text = "${index + 1}   ${c.name}" + (if (c.group.isNotEmpty()) "   ·   ${c.group}" else "")
        bar.animate().cancel(); bar.alpha = 1f
        main.removeCallbacks(hideBar); main.postDelayed(hideBar, 5000)
        startedAt = SystemClock.elapsedRealtime()
        Log.i(tag, "play #${index + 1}")
        p.setMediaItem(MediaItem.fromUri(c.url))
        p.prepare()
        p.playWhenReady = true
    }

    private val listener = object : Player.Listener {
        override fun onPlaybackStateChanged(state: Int) {
            val name = when (state) { Player.STATE_BUFFERING -> "buffering"; Player.STATE_READY -> "ready"; Player.STATE_ENDED -> "ended"; else -> "idle" }
            Log.i(tag, "#${index + 1} $name after ${SystemClock.elapsedRealtime() - startedAt} ms")
        }
        override fun onRenderedFirstFrame() { Log.i(tag, "#${index + 1} first picture after ${SystemClock.elapsedRealtime() - startedAt} ms") }
        override fun onVideoSizeChanged(v: VideoSize) { Log.i(tag, "#${index + 1} picture ${v.width}x${v.height}") }
        override fun onTracksChanged(t: Tracks) {
            for (g in t.groups) for (i in 0 until g.length) if (g.isTrackSelected(i)) {
                val f = g.getTrackFormat(i)
                val what = when (g.type) { C.TRACK_TYPE_VIDEO -> "video"; C.TRACK_TYPE_AUDIO -> "audio"; C.TRACK_TYPE_TEXT -> "text"; else -> "other" }
                Log.i(tag, "#${index + 1} $what ${f.sampleMimeType} ${f.codecs.orEmpty()} ${if (f.width > 0) "${f.width}x${f.height}@${f.frameRate}" else ""} ${if (f.channelCount > 0) "${f.channelCount}ch ${f.sampleRate}Hz" else ""} ${f.language.orEmpty()} supported=${g.isTrackSupported(i)}")
            }
        }
        override fun onPlayerError(e: PlaybackException) {
            // (the message may hold the stream's address: only the kind of error is logged)
            Log.w(tag, "#${index + 1} error ${e.errorCodeName} (${e.cause?.javaClass?.simpleName})")
            say("This channel does not play (${e.errorCodeName.removePrefix("ERROR_CODE_").lowercase().replace('_', ' ')}).")
        }
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        if (channels.size > 1) when (keyCode) {
            KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_CHANNEL_UP -> { index = (index + 1) % channels.size; play(); return true }
            KeyEvent.KEYCODE_DPAD_DOWN, KeyEvent.KEYCODE_CHANNEL_DOWN -> { index = (index - 1 + channels.size) % channels.size; play(); return true }
        }
        if (keyCode == KeyEvent.KEYCODE_DPAD_CENTER || keyCode == KeyEvent.KEYCODE_ENTER || keyCode == KeyEvent.KEYCODE_INFO) {
            bar.animate().cancel(); bar.alpha = 1f
            main.removeCallbacks(hideBar); main.postDelayed(hideBar, 5000)
            return true
        }
        return super.onKeyDown(keyCode, event)
    }
}
