package app.hermes.channels

import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView

/**
 * The settings: a full-screen page with tabs along the top, the tab's settings on the left (each with a line of
 * explanation) and, on the right, what the tab is for and its figures.
 *
 * Up from the first setting reaches the tabs, Left / Right change the tab there; on a setting Left / Right step
 * through its values, OK acts.
 */
class Page(private val ui: Ui, private val host: FrameLayout, private val title: String, private val footer: String) {

    /** a setting; [steps] = Left and Right step through its values (otherwise OK acts) */
    class Row(val title: String, val desc: String, val value: () -> String, val shown: () -> Boolean = { true },
              val steps: Boolean = true, val act: (Int) -> Unit)
    class Tab(val name: String, val blurb: String, val stats: () -> List<Pair<String, String>> = { emptyList() }, val rows: List<Row>)

    private var view: View? = null
    private var refreshAll: (() -> Unit)? = null
    private var refocus: (() -> Unit)? = null
    private var last = 0
    private var lastRow = 0
    /** set before [open]: the focus goes to the row it was on when the page was closed */
    var keepRow = false
    /** where the page stands (tab, row), and standing there again when it is opened next: for an app that is drawn anew */
    fun place() = last to lastRow
    fun resume(at: Pair<Int, Int>) { last = at.first; lastRow = at.second; keepRow = true }
    val isOpen: Boolean get() = view != null

    fun refresh() { refreshAll?.invoke() }
    /** after a dialog has closed, the focus goes back to the setting it was opened from */
    fun focus() { refocus?.invoke() }

    fun open(tabs: List<Tab>) {
        if (view != null) return
        var cur = last.coerceIn(0, tabs.size - 1)
        val a = ui.a
        val p = FrameLayout(a)
        p.background = GradientDrawable(GradientDrawable.Orientation.TL_BR, intArrayOf(Color.rgb(14, 19, 38), Color.rgb(6, 8, 16)))
        p.isClickable = true
        val page = LinearLayout(a).apply {
            orientation = LinearLayout.VERTICAL; setPadding(ui.u(48), ui.u(30), ui.u(48), ui.u(16))
            clipToPadding = false; clipChildren = false
        }
        page.addView(ui.label(25f, Color.WHITE).apply { text = title; typeface = Typeface.create("sans-serif-light", Typeface.NORMAL) })
        val tabBar = LinearLayout(a).apply { orientation = LinearLayout.HORIZONTAL }
        val tabViews = ArrayList<TextView>()
        page.addView(tabBar, LinearLayout.LayoutParams(-1, -2).apply { topMargin = ui.u(14); leftMargin = -ui.u(4) })

        val list = LinearLayout(a).apply { orientation = LinearLayout.VERTICAL }
        val scroll = ScrollView(a).apply { isVerticalScrollBarEnabled = false; overScrollMode = View.OVER_SCROLL_NEVER; addView(list, ViewGroup.LayoutParams(-1, -2)) }
        val side = LinearLayout(a).apply {
            orientation = LinearLayout.VERTICAL
            background = ui.rounded(ui.white(10), ui.u(16), ui.u(1), ui.white(22))
            setPadding(ui.u(20), ui.u(18), ui.u(20), ui.u(18))
        }
        val body = LinearLayout(a).apply { orientation = LinearLayout.HORIZONTAL }
        body.addView(scroll, LinearLayout.LayoutParams(ui.u(470), -1))
        body.addView(side, LinearLayout.LayoutParams(0, -2, 1f).apply { leftMargin = ui.u(26) })
        page.addView(body, LinearLayout.LayoutParams(-1, 0, 1f).apply { topMargin = ui.u(16) })
        val foot = LinearLayout(a).apply { orientation = LinearLayout.HORIZONTAL }
        foot.addView(ui.label(10f, ui.white(110)).apply { text = "\u25C0 \u25B6  change      OK  select      BACK  close" }, LinearLayout.LayoutParams(0, -2, 1f))
        foot.addView(ui.label(10f, ui.white(110)).apply { text = footer })
        page.addView(foot, LinearLayout.LayoutParams(-1, -2).apply { topMargin = ui.u(10) })
        p.addView(page, ViewGroup.LayoutParams(-1, -1))

        val rowViews = ArrayList<Triple<Row, View, TextView>>()
        var statsBox: LinearLayout? = null
        var focused: View? = null
        fun visibleRows() = rowViews.filter { it.second.visibility == View.VISIBLE }.map { it.second }
        fun refresh() {
            for ((r, line, v) in rowViews) {
                v.text = if (r.steps) "\u2039   ${r.value()}   \u203A" else r.value()
                line.visibility = if (r.shown()) View.VISIBLE else View.GONE
            }
            statsBox?.let { box ->
                box.removeAllViews()
                for ((k, v) in tabs[cur].stats()) {
                    box.addView(ui.caps(k, ui.white(120)), LinearLayout.LayoutParams(-2, -2).apply { topMargin = ui.u(11) })
                    box.addView(ui.label(14f, Color.WHITE, lines = 6).apply { text = v; setLineSpacing(ui.u(2).toFloat(), 1f) }, LinearLayout.LayoutParams(-2, -2).apply { topMargin = ui.u(2) })
                }
            }
        }
        fun styleTabs() {
            for ((i, t) in tabViews.withIndex()) {
                val has = t.isFocused
                t.setTextColor(if (i == cur || has) Color.WHITE else ui.white(140))
                t.background = when {
                    has -> ui.rounded(ui.white(46), ui.u(15), ui.u(2), ui.accent)
                    i == cur -> ui.rounded(ui.white(26), ui.u(15))
                    else -> null
                }
            }
        }
        fun show(i: Int) {
            cur = i; last = i
            val tab = tabs[i]
            list.removeAllViews(); rowViews.clear(); focused = null
            scroll.scrollTo(0, 0)
            for (r in tab.rows) {
                val line = LinearLayout(a).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                    isFocusable = true
                    isClickable = true
                    setPadding(ui.u(16), ui.u(7.5f), ui.u(16), ui.u(8))
                    background = ui.rounded(ui.white(10), ui.u(11))
                }
                val names = LinearLayout(a).apply { orientation = LinearLayout.VERTICAL }
                names.addView(ui.label(14.5f, Color.WHITE).apply { text = r.title })
                names.addView(ui.label(10.5f, ui.white(120)).apply { text = r.desc })
                line.addView(names, LinearLayout.LayoutParams(0, -2, 1f))
                val value = ui.label(13.5f, ui.white(210)).apply { gravity = Gravity.END }
                line.addView(value, LinearLayout.LayoutParams(-2, -2).apply { leftMargin = ui.u(12) })
                rowViews += Triple(r, line, value)
                line.setOnFocusChangeListener { v, has ->
                    v.background = if (has) ui.rounded(ui.white(40), ui.u(11), ui.u(2), ui.accent) else ui.rounded(ui.white(10), ui.u(11))
                    value.setTextColor(if (has) ui.accent else ui.white(210))
                    if (has) { focused = v; lastRow = rowViews.indexOfFirst { it.second === v } }
                }
                line.setOnClickListener { r.act(1); refresh() }
                line.setOnKeyListener { v, kc, ev ->
                    val down = ev.action == KeyEvent.ACTION_DOWN
                    when (kc) {
                        KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_DPAD_RIGHT -> {
                            if (down && r.steps) { r.act(if (kc == KeyEvent.KEYCODE_DPAD_LEFT) -1 else 1); refresh() }
                            true
                        }
                        KeyEvent.KEYCODE_DPAD_UP -> if (visibleRows().firstOrNull() === v) { if (down) tabViews[cur].requestFocus(); true } else false
                        KeyEvent.KEYCODE_DPAD_DOWN -> visibleRows().lastOrNull() === v
                        else -> false
                    }
                }
                list.addView(line, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = ui.u(5) })
            }
            side.removeAllViews()
            side.addView(ui.caps(tab.name.uppercase(), ui.accent))
            side.addView(ui.label(12f, ui.white(170), lines = 12).apply { text = tab.blurb; setLineSpacing(ui.u(3).toFloat(), 1f) }, LinearLayout.LayoutParams(-1, -2).apply { topMargin = ui.u(10) })
            statsBox = LinearLayout(a).apply { orientation = LinearLayout.VERTICAL }
            side.addView(statsBox)
            refresh()
            styleTabs()
        }
        for ((i, tab) in tabs.withIndex()) {
            val t = ui.label(13.5f, ui.white(140), true).apply {
                text = tab.name
                isFocusable = true
                isClickable = true
                setPadding(ui.u(15), ui.u(6), ui.u(15), ui.u(6.5f))
            }
            t.setOnFocusChangeListener { _, has -> if (has && cur != i) show(i) else styleTabs() }
            t.setOnKeyListener { _, kc, ev ->
                val down = ev.action == KeyEvent.ACTION_DOWN
                when (kc) {
                    KeyEvent.KEYCODE_DPAD_DOWN, KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER -> { if (down) visibleRows().firstOrNull()?.requestFocus(); true }
                    KeyEvent.KEYCODE_DPAD_UP -> true
                    KeyEvent.KEYCODE_DPAD_LEFT -> i == 0
                    KeyEvent.KEYCODE_DPAD_RIGHT -> i == tabs.size - 1
                    else -> false
                }
            }
            tabViews += t
            tabBar.addView(t, LinearLayout.LayoutParams(-2, -2).apply { rightMargin = ui.u(4) })
        }
        refreshAll = { refresh() }
        refocus = { (focused ?: visibleRows().firstOrNull() ?: tabViews[cur]).requestFocus() }
        host.addView(p, ViewGroup.LayoutParams(-1, -1))
        view = p
        val row = if (keepRow) lastRow else -1
        keepRow = false
        show(cur)
        if (row >= 0) focused = rowViews.getOrNull(row)?.second?.takeIf { it.visibility == View.VISIBLE }
        focus()
    }

    fun close() {
        view?.let { host.removeView(it) }
        view = null; refreshAll = null; refocus = null
    }
}
