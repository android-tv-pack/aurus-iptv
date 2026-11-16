package app.hermes.channels

import android.app.Activity
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.Gravity
import android.view.KeyEvent
import android.view.SurfaceView
import android.view.View
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.VideoSize
import androidx.media3.exoplayer.ExoPlayer

/**
 * Plays one film or episode. OK pauses and goes on; Left and Right move ten seconds (held: faster), Up and Down five
 * minutes; Back leaves, and where the film was left is remembered.
 */
class FilmPlayActivity : Activity() {
    private val tag = "Channels"
    private lateinit var ui: Ui
    private lateinit var prefs: Prefs
    private val main = Handler(Looper.getMainLooper())
    private var player: ExoPlayer? = null
    private lateinit var surface: SurfaceView
    private lateinit var bar: LinearLayout
    private lateinit var times: TextView
    private lateinit var state: TextView
    private lateinit var line: Line
    private lateinit var note: TextView
    private var id = ""
    private var address = ""
    private var startAt = 0L
    /** a film is a list of one; a series' episodes follow one another */
    private var titles: Array<String> = emptyArray()
    private var addresses: Array<String> = emptyArray()
    private var ids: Array<String> = emptyArray()
    private var index = 0
    private var series = ""
    private lateinit var heading: TextView
    private lateinit var cues: CueView
    /** while Left or Right is held the film does not jump at every step: it goes there when the key is let go */
    private var aim = -1L

    private class Line(a: Activity, val fill: Int) : View(a) {
        var part = 0f; var ahead = 0f
        private val p = Paint(Paint.ANTI_ALIAS_FLAG)
        private val r = RectF()
        override fun onDraw(c: Canvas) {
            val h = height.toFloat(); val w = width.toFloat()
            p.color = Color.argb(60, 255, 255, 255); r.set(0f, 0f, w, h); c.drawRoundRect(r, h / 2, h / 2, p)
            p.color = Color.argb(110, 255, 255, 255); r.set(0f, 0f, w * ahead.coerceIn(0f, 1f), h); c.drawRoundRect(r, h / 2, h / 2, p)
            p.color = fill; r.set(0f, 0f, w * part.coerceIn(0f, 1f), h); c.drawRoundRect(r, h / 2, h / 2, p)
        }
    }

    override fun onCreate(b: Bundle?) {
        super.onCreate(b)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        ui = Ui(this); prefs = Prefs(this)
        titles = intent.getStringArrayExtra("titles") ?: emptyArray()
        addresses = intent.getStringArrayExtra("addresses") ?: emptyArray()
        ids = intent.getStringArrayExtra("ids") ?: emptyArray()
        series = intent.getStringExtra("series").orEmpty()
        index = intent.getIntExtra("index", 0).coerceIn(0, (addresses.size - 1).coerceAtLeast(0))
        id = ids.getOrNull(index).orEmpty()
        address = addresses.getOrNull(index).orEmpty()
        // (an episode goes on from where it was left; a film from where its page said)
        startAt = if (intent.hasExtra("at")) intent.getLongExtra("at", 0L) else prefs.filmAt(id)
        val root = FrameLayout(this).apply { setBackgroundColor(Color.BLACK) }
        surface = SurfaceView(this)
        root.addView(surface, FrameLayout.LayoutParams(-1, -1, Gravity.CENTER))
        // subtitles, drawn as on the channels (and with the look chosen there)
        cues = CueView(this).apply { look(prefs.subLook, false) }
        root.addView(cues, FrameLayout.LayoutParams(-1, -1))

        bar = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = GradientDrawable(GradientDrawable.Orientation.BOTTOM_TOP, intArrayOf(Color.argb(230, 0, 0, 0), Color.TRANSPARENT))
            setPadding(ui.u(40), ui.u(60), ui.u(40), ui.u(26))
        }
        val top = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.BOTTOM }
        heading = ui.label(17f, Color.WHITE, true)
        top.addView(heading, LinearLayout.LayoutParams(0, -2, 1f))
        state = ui.label(12f, ui.accent, true)
        top.addView(state, LinearLayout.LayoutParams(-2, -2).apply { rightMargin = ui.u(14) })
        times = ui.label(12.5f, ui.white(210))
        top.addView(times)
        bar.addView(top)
        line = Line(this, ui.accent)
        bar.addView(line, LinearLayout.LayoutParams(-1, ui.u(4)).apply { topMargin = ui.u(10) })
        bar.addView(ui.label(10f, ui.white(120)).apply { text = "OK  pause      \u25C0 \u25B6  10 seconds      \u25B2 \u25BC  5 minutes      MENU  sound, subtitles      BACK  leave" }, LinearLayout.LayoutParams(-1, -2).apply { topMargin = ui.u(9) })
        root.addView(bar, FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM))

        note = ui.label(14f, Color.WHITE, lines = 3).apply { gravity = Gravity.CENTER }
        root.addView(note, FrameLayout.LayoutParams(ui.u(460), -2, Gravity.CENTER))
        setContentView(root)
    }

    override fun onStart() {
        super.onStart()
        if (address.isEmpty()) { finish(); return }
        val p = ExoPlayer.Builder(this).build()
        player = p
        p.setVideoSurfaceView(surface)
        p.addListener(object : Player.Listener {
            override fun onVideoSizeChanged(s: VideoSize) {
                if (s.width <= 0 || s.height <= 0) return
                // the picture keeps its shape, as large as the screen takes it
                val root = surface.parent as View
                val shape = s.width * s.pixelWidthHeightRatio / s.height
                val w: Int; val h: Int
                if (root.width.toFloat() / root.height > shape) { h = root.height; w = (h * shape).toInt() } else { w = root.width; h = (w / shape).toInt() }
                surface.layoutParams = FrameLayout.LayoutParams(w, h, Gravity.CENTER)
            }
            override fun onPlaybackStateChanged(st: Int) {
                if (st == Player.STATE_ENDED) {
                    prefs.setFilmAt(id, 0L)
                    // the next episode follows by itself
                    if (index + 1 < addresses.size) { index++; load(0L); return }
                    ended = true; finish()
                }
                if (st == Player.STATE_READY) note.text = ""
                if (st == Player.STATE_BUFFERING && note.text.isEmpty()) main.postDelayed(waiting, 1500)
                if (st != Player.STATE_BUFFERING) main.removeCallbacks(waiting)
                tick.run()
            }
            override fun onIsPlayingChanged(on: Boolean) { tick.run() }
            override fun onCues(c: androidx.media3.common.text.CueGroup) { cues.show(c.cues) }
            override fun onPlayerError(e: PlaybackException) {
                Log.w(tag, "film: ${e.errorCodeName}")
                // one more try from where it was, then it is said
                if (!retried) { retried = true; val at = p.currentPosition; p.prepare(); if (at > 0) p.seekTo(at); return }
                note.text = "This film does not play right now."
                show(true)
            }
        })
        load(if (leftAt > 0L) leftAt else startAt)
    }

    /** the film, or the episode that is on, from [at] */
    private fun load(at: Long) {
        val p = player ?: return
        id = ids.getOrNull(index).orEmpty(); address = addresses.getOrNull(index).orEmpty()
        heading.text = (if (series.isNotEmpty()) "$series  \u00B7  " else "") + titles.getOrNull(index).orEmpty()
        retried = false; aim = -1L
        p.setMediaItem(MediaItem.fromUri(address))
        if (at > 0L) p.seekTo(at)
        p.prepare()
        p.playWhenReady = true
        Log.i(tag, "film: play" + (if (addresses.size > 1) " ${index + 1} of ${addresses.size}" else "") + (if (at > 0L) " from ${at / 1000} s" else ""))
        show(false)
    }

    /** the sounds and the subtitles the film has, to choose from */
    private fun choices() {
        val p = player ?: return
        fun of(type: Int) = p.currentTracks.groups.filter { it.type == type }.flatMap { g -> (0 until g.length).filter { g.isTrackSupported(it) }.map { g to it } }
        fun nameOf(g: androidx.media3.common.Tracks.Group, i: Int): String {
            val f = g.getTrackFormat(i)
            val lang = f.language?.takeIf { it != "und" }?.let { java.util.Locale.forLanguageTag(it).displayLanguage.replaceFirstChar { c -> c.uppercase() } }.orEmpty()
            return listOf(lang, f.label.orEmpty()).filter { it.isNotBlank() }.distinct().joinToString("  \u00B7  ").ifEmpty { "Track ${i + 1}" }
        }
        val sounds = of(C.TRACK_TYPE_AUDIO); val texts = of(C.TRACK_TYPE_TEXT)
        val sh = Sheet(ui, "Sound and subtitles", if (sounds.size < 2 && texts.isEmpty()) "This film has one sound and no subtitles." else null)
        if (sounds.size > 1) for ((g, i) in sounds) sh.item("Sound:  " + nameOf(g, i), { if (g.isTrackSelected(i)) "\u2713" else "" }) {
            p.trackSelectionParameters = p.trackSelectionParameters.buildUpon().setOverrideForType(androidx.media3.common.TrackSelectionOverride(g.mediaTrackGroup, i)).build()
        }
        if (texts.isNotEmpty()) {
            val off = p.trackSelectionParameters.disabledTrackTypes.contains(C.TRACK_TYPE_TEXT) || texts.none { (g, i) -> g.isTrackSelected(i) }
            sh.item("Subtitles:  Off", { if (off) "\u2713" else "" }) { p.trackSelectionParameters = p.trackSelectionParameters.buildUpon().setTrackTypeDisabled(C.TRACK_TYPE_TEXT, true).build() }
            for ((g, i) in texts) sh.item("Subtitles:  " + nameOf(g, i), { if (!off && g.isTrackSelected(i)) "\u2713" else "" }) {
                p.trackSelectionParameters = p.trackSelectionParameters.buildUpon().setTrackTypeDisabled(C.TRACK_TYPE_TEXT, false).setOverrideForType(androidx.media3.common.TrackSelectionOverride(g.mediaTrackGroup, i)).build()
            }
        }
        sh.button("Close", primary = true).show { }
    }
    private var retried = false
    private var ended = false
    private var leftAt = 0L
    private val waiting = Runnable { if (player?.playbackState == Player.STATE_BUFFERING) note.text = "Loading\u2026" }

    /** where the film is left is noted as soon as the screen is left (the library's page, which comes next, shows it) */
    override fun onPause() {
        super.onPause()
        player?.let { p ->
            val at = if (aim >= 0) aim else p.currentPosition; val all = p.duration
            leftAt = at
            // (the first half minute and the last minutes do not count as "left in the middle")
            if (!ended && id.isNotEmpty()) prefs.setFilmAt(id, if (all != C.TIME_UNSET && at > 30_000 && at < all - maxOf(60_000L, all / 25)) at else 0L)
            p.playWhenReady = false
        }
    }

    override fun onStop() {
        super.onStop()
        main.removeCallbacksAndMessages(null)
        player?.release()
        player = null
    }

    private fun clock(ms: Long): String { val s = (ms / 1000).coerceAtLeast(0); return if (s >= 3600) "%d:%02d:%02d".format(s / 3600, s / 60 % 60, s % 60) else "%d:%02d".format(s / 60, s % 60) }

    private val tick = object : Runnable {
        override fun run() {
            main.removeCallbacks(this)
            val p = player ?: return
            val all = p.duration.takeIf { it != C.TIME_UNSET && it > 0 } ?: 0L
            val at = if (aim >= 0) aim else p.currentPosition
            times.text = clock(at) + (if (all > 0) "  /  " + clock(all) else "")
            line.part = if (all > 0) at.toFloat() / all else 0f
            line.ahead = if (all > 0) p.bufferedPosition.toFloat() / all else 0f
            line.invalidate()
            state.text = if (aim >= 0) (if (aim >= p.currentPosition) "\u25B6\u25B6" else "\u25C0\u25C0") else if (!p.playWhenReady) "PAUSED" else ""
            if (bar.visibility == View.VISIBLE) main.postDelayed(this, 500)
        }
    }

    private val hide = Runnable { if (player?.playWhenReady == true && aim < 0) bar.animate().alpha(0f).setDuration(200).withEndAction { bar.visibility = View.GONE }.start() }
    private fun show(stay: Boolean) {
        bar.animate().cancel(); bar.alpha = 1f; bar.visibility = View.VISIBLE
        tick.run()
        main.removeCallbacks(hide)
        if (!stay) main.postDelayed(hide, 4000)
    }

    private val go = Runnable { val p = player ?: return@Runnable; if (aim >= 0) { p.seekTo(aim); aim = -1L; show(!p.playWhenReady) } }
    private fun move(by: Long) {
        val p = player ?: return
        val all = p.duration.takeIf { it != C.TIME_UNSET && it > 0 } ?: return
        aim = ((if (aim >= 0) aim else p.currentPosition) + by).coerceIn(0L, (all - 5000).coerceAtLeast(0L))
        show(true)
        // (it goes there a moment after the last press, so that a row of presses is one jump)
        main.removeCallbacks(go); main.postDelayed(go, 450)
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        val p = player ?: return super.onKeyDown(keyCode, event)
        when (keyCode) {
            KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_NUMPAD_ENTER, KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE, KeyEvent.KEYCODE_SPACE -> {
                if (event.repeatCount > 0) return true
                p.playWhenReady = !p.playWhenReady; show(!p.playWhenReady); return true
            }
            KeyEvent.KEYCODE_MEDIA_PLAY -> { p.playWhenReady = true; show(false); return true }
            KeyEvent.KEYCODE_MEDIA_PAUSE -> { p.playWhenReady = false; show(true); return true }
            // (held, the steps grow: 10 s, then 30 s, then a minute)
            KeyEvent.KEYCODE_DPAD_RIGHT, KeyEvent.KEYCODE_MEDIA_FAST_FORWARD -> { move(step(event)); return true }
            KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_MEDIA_REWIND -> { move(-step(event)); return true }
            KeyEvent.KEYCODE_MENU, KeyEvent.KEYCODE_CAPTIONS, KeyEvent.KEYCODE_MEDIA_AUDIO_TRACK -> { choices(); return true }
            KeyEvent.KEYCODE_MEDIA_NEXT -> { if (index + 1 < addresses.size) { prefs.setFilmAt(id, 0L); index++; load(prefs.filmAt(ids[index])) }; return true }
            KeyEvent.KEYCODE_MEDIA_PREVIOUS -> { if (index > 0) { index--; load(0L) }; return true }
            KeyEvent.KEYCODE_DPAD_UP -> { move(300_000); return true }
            KeyEvent.KEYCODE_DPAD_DOWN -> { move(-300_000); return true }
        }
        return super.onKeyDown(keyCode, event)
    }
    private fun step(e: KeyEvent): Long = if (e.repeatCount > 40) 60_000 else if (e.repeatCount > 12) 30_000 else 10_000
}
