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
    val accent = Plus.accent(Prefs(a)).let { if (it != 0) it else Color.rgb(0, 229, 255) }
    val ink = Color.rgb(8, 11, 22)

    fun u(n: Float): Int = (n * unit).toInt()
    fun u(n: Int): Int = u(n.toFloat())
    fun white(alpha: Int) = Color.argb(alpha, 255, 255, 255)

    private val textScale = Plus.textScale(Prefs(a))
    fun label(size: Float, color: Int, medium: Boolean = false, lines: Int = 1) = TextView(a).apply {
        setTextSize(TypedValue.COMPLEX_UNIT_PX, u(size) * textScale)
        setTextColor(color)
        maxLines = lines
        ellipsize = TextUtils.TruncateAt.END
        if (medium) typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
    }

    fun caps(text: String, color: Int) = label(9.5f, color, true).apply { letterSpacing = 0.22f; this.text = text }

    fun rounded(fill: Int, radius: Int, strokeW: Int = 0, stroke: Int = 0) = GradientDrawable().apply {
        setColor(fill); cornerRadius = radius.toFloat()
        if (strokeW > 0) setStroke(strokeW, stroke)
    }
}
