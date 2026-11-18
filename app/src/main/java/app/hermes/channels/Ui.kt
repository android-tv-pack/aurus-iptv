package app.hermes.channels

import android.app.Activity
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.text.TextUtils
import android.util.TypedValue
import android.widget.TextView

/** The look shared by every screen of the app: sizes relative to a 540-unit-high screen, colours, text. */
class Ui(val a: Activity) {
    private val unit = a.resources.displayMetrics.heightPixels / 540f
    val accent = Plus.accent(Prefs(a)).let { if (it != 0) it else CYAN }
    /** the colour of text (Settings > Interface > Text colour): soft white unless chosen otherwise, easier on the eyes than pure white */
    val fg: Int = TONES[Prefs(a).textTone.coerceIn(0, TONES.size - 1)].second
    val ink = Color.rgb(8, 11, 22)

    fun u(n: Float): Int = (n * unit).toInt()
    fun u(n: Int): Int = u(n.toFloat())
    /** the text's colour, see-through by [alpha] (also used for the faint fills of cards and rows) */
    fun white(alpha: Int) = Color.argb(alpha, Color.red(fg), Color.green(fg), Color.blue(fg))

    private val textScale = Plus.textScale(Prefs(a))
    fun label(size: Float, color: Int, medium: Boolean = false, lines: Int = 1) = TextView(a).apply {
        setTextSize(TypedValue.COMPLEX_UNIT_PX, u(size) * textScale)
        setTextColor(if (color == Color.WHITE) fg else color)
        maxLines = lines
        ellipsize = TextUtils.TruncateAt.END
        if (medium) typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
    }

    fun caps(text: String, color: Int) = label(10.5f, color, true).apply { letterSpacing = 0.22f; this.text = text }

    fun rounded(fill: Int, radius: Int, strokeW: Int = 0, stroke: Int = 0) = GradientDrawable().apply {
        setColor(fill); cornerRadius = radius.toFloat()
        if (strokeW > 0) setStroke(strokeW, stroke)
    }

    companion object {
        /** the app's own accent: a cyan calmed down for the eyes (it was 0, 229, 255) */
        val CYAN = Color.rgb(96, 208, 228)
        /** the text colours to choose from, by name */
        val TONES = listOf("Soft white" to Color.rgb(228, 231, 238), "Warm" to Color.rgb(238, 226, 206), "Bright" to Color.rgb(246, 247, 250))
    }
}
