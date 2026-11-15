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

    fun show(c: List<Cue>) { if (c.isEmpty() && cues.isEmpty()) return; cues = c; invalidate() }

    override fun onDraw(c: Canvas) {
        if (cues.isEmpty()) return
        val w = width.toFloat(); val h = height.toFloat()
        val lines = StringBuilder()
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
                if (t.isNotEmpty()) { if (lines.isNotEmpty()) lines.append('\n'); lines.append(t) }
            }
        }
        if (lines.isEmpty()) return
        val size = h * 0.05f
        text.textSize = size; edge.textSize = size; edge.strokeWidth = size * 0.16f
        val width = (w * 0.86f).toInt()
        fun layout(p: TextPaint) = StaticLayout.Builder.obtain(lines, 0, lines.length, p, width).setAlignment(Layout.Alignment.ALIGN_CENTER).build()
        val under = layout(edge); val over = layout(text)
        c.save()
        c.translate((w - width) / 2, h * 0.94f - under.height)
        under.draw(c); over.draw(c)
        c.restore()
    }
}
