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
 * The settings: a full-screen page with tabs along the top, the tab's settings on the left (name and value) and, on
 * the right, a card that speaks of what has the focus: on a tab, what the tab is for and its figures; on a setting,
 * what it does, what each of its choices means, and its own figures.
 *
 * Up from the first setting reaches the tabs, Left / Right change the tab there; on a setting Left / Right step
 * through its values, OK acts.
 */
class Page(private val ui: Ui, private val host: FrameLayout, private val title: String, private val footer: String) {

    /** a setting; [steps] = Left and Right step through its values (otherwise OK acts); [desc] is a short line, [about]
     *  the card's full text about it (else [Abouts], else [desc]); [facts] = figures the card shows for it */
    class Row(val title: String, val desc: String, val value: () -> String, val shown: () -> Boolean = { true },
              val steps: Boolean = true, val about: String? = null, val facts: (() -> List<Pair<String, String>>)? = null,
              val act: (Int) -> Unit)
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
            orientation = LinearLayout.VERTICAL; setPadding(ui.u(48), ui.u(30), ui.u(48), ui.u(14))
            clipToPadding = false; clipChildren = false
        }
        page.addView(ui.label(25f, ui.fg).apply { text = title; typeface = Typeface.create("sans-serif-light", Typeface.NORMAL) })
        val tabBar = LinearLayout(a).apply { orientation = LinearLayout.HORIZONTAL }
        val tabViews = ArrayList<TextView>()
        page.addView(tabBar, LinearLayout.LayoutParams(-1, -2).apply { topMargin = ui.u(14); leftMargin = -ui.u(4) })

        val list = LinearLayout(a).apply { orientation = LinearLayout.VERTICAL }
        val scroll = ScrollView(a).apply {
            isVerticalScrollBarEnabled = false; overScrollMode = View.OVER_SCROLL_NEVER; addView(list, ViewGroup.LayoutParams(-1, -2))
            // (rows fade out at the list's foot instead of being cut)
            isVerticalFadingEdgeEnabled = true; setFadingEdgeLength(ui.u(26))
        }
        val side = LinearLayout(a).apply {
            orientation = LinearLayout.VERTICAL
            background = ui.rounded(ui.white(10), ui.u(16), ui.u(1), ui.white(22))
            setPadding(ui.u(22), ui.u(18), ui.u(22), ui.u(20))
        }
        // (the card takes the room it needs, never more than the body's: the tabs above never move)
        val sideBox = FrameLayout(a)
        sideBox.addView(side, FrameLayout.LayoutParams(-1, -2))
        val body = LinearLayout(a).apply { orientation = LinearLayout.HORIZONTAL }
        body.addView(scroll, LinearLayout.LayoutParams(ui.u(400), -1))
        body.addView(sideBox, LinearLayout.LayoutParams(0, -1, 1f).apply { leftMargin = ui.u(28) })
        page.addView(body, LinearLayout.LayoutParams(-1, 0, 1f).apply { topMargin = ui.u(16) })
        page.addView(ui.label(10.5f, ui.white(100)).apply { text = footer; gravity = Gravity.END }, LinearLayout.LayoutParams(-1, -2).apply { topMargin = ui.u(8) })
        p.addView(page, ViewGroup.LayoutParams(-1, -1))

        val rowViews = ArrayList<Triple<Row, View, TextView>>()
        var focused: View? = null
        /** the setting the card speaks of (null: the tab) */
        var told: Row? = null
        fun visibleRows() = rowViews.filter { it.second.visibility == View.VISIBLE }.map { it.second }
        fun facts(box: LinearLayout, list: List<Pair<String, String>>) {
            for ((k, v) in list) {
                box.addView(ui.caps(k, ui.white(130)), LinearLayout.LayoutParams(-2, -2).apply { topMargin = ui.u(12) })
                box.addView(ui.label(14.5f, ui.fg, lines = 2).apply { text = v; setLineSpacing(ui.u(2).toFloat(), 1f) }, LinearLayout.LayoutParams(-1, -2).apply { topMargin = ui.u(2) })
            }
        }
        fun card() {
            side.removeAllViews()
            val tab = tabs[cur]
            side.addView(ui.caps(tab.name.uppercase(), ui.accent))
            val r = told
            if (r == null) {
                side.addView(ui.label(13f, ui.white(195), lines = 9).apply { text = tab.blurb; setLineSpacing(ui.u(3.5f).toFloat(), 1f) },
                    LinearLayout.LayoutParams(-1, -2).apply { topMargin = ui.u(10) })
                val box = LinearLayout(a).apply { orientation = LinearLayout.VERTICAL }
                facts(box, tab.stats())
                side.addView(box)
            } else {
                side.addView(ui.label(17f, ui.fg, true, lines = 2).apply { text = r.title.trim() }, LinearLayout.LayoutParams(-1, -2).apply { topMargin = ui.u(8) })
                if (r.steps) side.addView(ui.label(14.5f, ui.accent, lines = 2).apply { text = r.value() }, LinearLayout.LayoutParams(-1, -2).apply { topMargin = ui.u(3) })
                val about = r.about ?: Abouts.of(r.title) ?: r.desc
                side.addView(ui.label(13f, ui.white(195), lines = 10).apply { text = about; setLineSpacing(ui.u(3.5f).toFloat(), 1f) },
                    LinearLayout.LayoutParams(-1, -2).apply { topMargin = ui.u(9) })
                val box = LinearLayout(a).apply { orientation = LinearLayout.VERTICAL }
                facts(box, r.facts?.invoke() ?: emptyList())
                side.addView(box)
            }
        }
        fun refresh() {
            for ((r, line, v) in rowViews) {
                v.text = if (r.steps) "\u2039   ${r.value()}   \u203A" else r.value()
                line.visibility = if (r.shown()) View.VISIBLE else View.GONE
            }
            card()
        }
        fun styleTabs() {
            for ((i, t) in tabViews.withIndex()) {
                val has = t.isFocused
                t.setTextColor(if (i == cur || has) ui.fg else ui.white(150))
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
            list.removeAllViews(); rowViews.clear(); focused = null; told = null
            scroll.scrollTo(0, 0)
            for (r in tab.rows) {
                val line = LinearLayout(a).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                    isFocusable = true
                    isClickable = true
                    setPadding(ui.u(16), ui.u(10), ui.u(16), ui.u(10.5f))
                    background = ui.rounded(ui.white(10), ui.u(11))
                }
                line.addView(ui.label(15.5f, ui.fg).apply { text = r.title }, LinearLayout.LayoutParams(0, -2, 1f))
                // (a long value is shortened, never the setting's name: the card beside it shows the value in full)
                val value = ui.label(14.5f, ui.white(200)).apply { gravity = Gravity.END; maxWidth = ui.u(170) }
                line.addView(value, LinearLayout.LayoutParams(-2, -2).apply { leftMargin = ui.u(12) })
                rowViews += Triple(r, line, value)
                line.setOnFocusChangeListener { v, has ->
                    v.background = if (has) ui.rounded(ui.white(40), ui.u(11), ui.u(2), ui.accent) else ui.rounded(ui.white(10), ui.u(11))
                    value.setTextColor(if (has) ui.accent else ui.white(200))
                    if (has) { focused = v; lastRow = rowViews.indexOfFirst { it.second === v }; told = r; card() }
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
            refresh()
            styleTabs()
        }
        for ((i, tab) in tabs.withIndex()) {
            val t = ui.label(14.5f, ui.white(150), true).apply {
                text = tab.name
                isFocusable = true
                isClickable = true
                setPadding(ui.u(15), ui.u(6), ui.u(15), ui.u(6.5f))
            }
            t.setOnFocusChangeListener { _, has ->
                if (has && cur != i) show(i) else { if (has && told != null) { told = null; card() }; styleTabs() }
            }
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
