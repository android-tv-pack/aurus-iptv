package app.hermes.channels

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import android.view.View
import androidx.media3.common.text.Cue

/** Subtitles over the picture: lines of text at the bottom (white with a dark edge), or the pictures some broadcasts send instead of text. */
class CueView(ctx: Context) : View(ctx) {
    private var cues: List<Cue> = emptyList()
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private val text = TextPaint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE; typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL) }
    private val edge = TextPaint(text).apply { style = Paint.Style.STROKE; color = Color.BLACK; strokeJoin = Paint.Join.ROUND }
    private val box = RectF()
    private val plate = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.argb(200, 0, 0, 0) }

    // ---- how subtitles sent as text look (those sent as pictures come as the broadcaster drew them)
    /** 0 small, 1 usual, 2 large, 3 largest */
    var size = 1
    /** 0 white, 1 yellow, 2 cyan, 3 green */
    var colour = 0
    /** 0 a dark edge round the letters, 1 a dark plate behind the lines */
    var back = 0
    /** 0 at the bottom, 1 higher, 2 higher still */
    var raise = 0
    /** a line shown for a moment to see the look by, over everything */
    private var sample: String? = null
    private val sampleOff = Runnable { sample = null; translationZ = 0f; invalidate() }
    /** 0 thin, 1 regular, 2 medium, 3 bold */
    var weight = 2
    /** 0 plain letters, 1 with serifs, 2 all of one width, 3 narrow */
    var face = 0
    /** [l]: size, colour, background, height, weight, kind of letters */
    fun look(l: List<Int>, show: Boolean) {
        size = l[0]; colour = l[1]; back = l[2]; raise = l[3]; weight = l[4]; face = l[5]
        val family = Typeface.create(arrayOf("sans-serif", "serif", "monospace", "sans-serif-condensed")[face.coerceIn(0, 3)], Typeface.NORMAL)
        val w = intArrayOf(300, 400, 500, 700)[weight.coerceIn(0, 3)]
        // (before Android 9 a letter is regular or bold, nothing between)
        val tf = if (android.os.Build.VERSION.SDK_INT >= 28) Typeface.create(family, w, false) else Typeface.create(family, if (w >= 500) Typeface.BOLD else Typeface.NORMAL)
        text.typeface = tf; edge.typeface = tf
        if (show) { sample = "Subtitles look like this"; translationZ = 1000f; removeCallbacks(sampleOff); postDelayed(sampleOff, 5000) }
        invalidate()
    }

    fun show(c: List<Cue>) { if (c.isEmpty() && cues.isEmpty()) return; cues = c; invalidate() }

    override fun onDraw(c: Canvas) {
        if (cues.isEmpty() && sample == null) return
        val w = width.toFloat(); val h = height.toFloat()
        val lines = StringBuilder()
        sample?.let { lines.append(it) }
        for (q in cues) {
            val b = q.bitmap
            if (b != null) {
                val bw = if (q.size != Cue.DIMEN_UNSET) q.size * w else minOf(w, b.width.toFloat())
                val bh = if (q.bitmapHeight != Cue.DIMEN_UNSET) q.bitmapHeight * h else bw * b.height / b.width
                var x = if (q.position != Cue.DIMEN_UNSET) q.position * w else (w - bw) / 2
                var y = if (q.line != Cue.DIMEN_UNSET && q.lineType == Cue.LINE_TYPE_FRACTION) q.line * h else h * 0.94f - bh
                if (q.position != Cue.DIMEN_UNSET) x -= when (q.positionAnchor) { Cue.ANCHOR_TYPE_MIDDLE -> bw / 2; Cue.ANCHOR_TYPE_END -> bw; else -> 0f }
                if (q.line != Cue.DIMEN_UNSET) y -= when (q.lineAnchor) { Cue.ANCHOR_TYPE_MIDDLE -> bh / 2; Cue.ANCHOR_TYPE_END -> bh; else -> 0f }
                box.set(x, y, x + bw, y + bh)
                c.drawBitmap(b, null, box, paint)
            } else {
                val t = q.text?.toString()?.trim().orEmpty()
                if (t.isNotEmpty() && sample == null) { if (lines.isNotEmpty()) lines.append('\n'); lines.append(t) }
            }
        }
        if (lines.isEmpty()) return
        val size = h * floatArrayOf(0.04f, 0.05f, 0.062f, 0.076f)[this.size.coerceIn(0, 3)]
        text.color = intArrayOf(Color.WHITE, Color.rgb(255, 235, 80), Color.rgb(120, 235, 255), Color.rgb(140, 245, 150))[colour.coerceIn(0, 3)]
        text.textSize = size; edge.textSize = size; edge.strokeWidth = size * 0.16f
        val width = (w * 0.86f).toInt()
        fun layout(p: TextPaint) = StaticLayout.Builder.obtain(lines, 0, lines.length, p, width).setAlignment(Layout.Alignment.ALIGN_CENTER).build()
        val under = layout(edge); val over = layout(text)
        c.save()
        c.translate((w - width) / 2, h * floatArrayOf(0.94f, 0.86f, 0.76f)[raise.coerceIn(0, 2)] - under.height)
        if (back == 1) {
            // a plate behind each line, as wide as the line
            val pad = size * 0.3f
            for (i in 0 until over.lineCount) c.drawRoundRect(over.getLineLeft(i) - pad, over.getLineTop(i).toFloat(), over.getLineRight(i) + pad, over.getLineBottom(i).toFloat(), pad * 0.5f, pad * 0.5f, plate)
        } else under.draw(c)
        over.draw(c)
        c.restore()
    }
}
