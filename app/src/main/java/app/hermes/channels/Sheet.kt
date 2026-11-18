package app.hermes.channels

import android.app.Dialog
import android.content.Context
import android.graphics.Color
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.util.TypedValue

/**
 * The app's dialog: a dark card in the middle of the screen with a title, a few words of explanation, and then a
 * text field, a list of choices and buttons, whichever are asked for. Back closes it without doing anything.
 */
class Sheet(private val ui: Ui, private val title: String, private val message: String? = null) {

    private class Item(val label: String, val value: (() -> String)?, val act: () -> Unit)
    private class Button(val label: String, val primary: Boolean, val act: () -> Unit)

    private val items = ArrayList<Item>()
    private val buttons = ArrayList<Button>()
    private var field: EditText? = null
    private var picture = 0

    /** a picture under the words (a code to scan with a phone) */
    fun picture(res: Int): Sheet { picture = res; return this }

    fun item(label: String, value: (() -> String)? = null, act: () -> Unit): Sheet { items += Item(label, value, act); return this }

    /** a button at the bottom; the [primary] one is also what "Done" on the keyboard presses */
    fun button(label: String, primary: Boolean = false, act: () -> Unit = {}): Sheet { buttons += Button(label, primary, act); return this }

    /** a text field; read its text in a button's action */
    fun input(text: String, hint: String, type: Int): EditText {
        val e = EditText(ui.a).apply {
            setText(text)
            this.hint = hint
            inputType = type
            setSingleLine(true)
            setSelectAllOnFocus(true)
            imeOptions = EditorInfo.IME_ACTION_DONE
            setTextSize(TypedValue.COMPLEX_UNIT_PX, ui.u(14).toFloat())
            setTextColor(ui.fg)
            setHintTextColor(ui.white(90))
            setPadding(ui.u(14), ui.u(9), ui.u(14), ui.u(9))
        }
        field = e
        return e
    }

    fun show(onDismiss: () -> Unit = {}) {
        val a = ui.a
        val d = Dialog(a, android.R.style.Theme_Translucent_NoTitleBar)
        val root = FrameLayout(a).apply { setBackgroundColor(Color.argb(175, 0, 0, 0)) }
        val card = LinearLayout(a).apply {
            orientation = LinearLayout.VERTICAL
            background = ui.rounded(Color.rgb(16, 21, 40), ui.u(18), ui.u(1), ui.white(34))
            setPadding(ui.u(22), ui.u(20), ui.u(22), ui.u(18))
        }
        card.addView(ui.label(18f, ui.fg, lines = 2).apply { text = title })
        message?.let {
            card.addView(ui.label(12f, ui.white(170), lines = 12).apply { text = it; setLineSpacing(ui.u(3).toFloat(), 1f) },
                LinearLayout.LayoutParams(-1, -2).apply { topMargin = ui.u(8) })
        }
        if (picture != 0) card.addView(android.widget.ImageView(a).apply {
            setImageResource(picture)
            background = ui.rounded(ui.fg, ui.u(10))
            clipToOutline = true
        }, LinearLayout.LayoutParams(ui.u(150), ui.u(150)).apply { topMargin = ui.u(14); gravity = Gravity.CENTER_HORIZONTAL })
        var first: View? = null
        field?.let { e ->
            fun paint(has: Boolean) { e.background = ui.rounded(ui.white(if (has) 30 else 14), ui.u(11), ui.u(if (has) 2 else 1), if (has) ui.accent else ui.white(40)) }
            paint(false)
            e.setOnFocusChangeListener { _, has -> paint(has) }
            // the keyboard would cover the buttons: it comes up when OK is pressed on the field, not by itself
            e.showSoftInputOnFocus = false
            e.setOnClickListener { (a.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager).showSoftInput(e, 0) }
            e.setOnEditorActionListener { _, id, _ ->
                if (id == EditorInfo.IME_ACTION_DONE) { buttons.firstOrNull { it.primary }?.let { b -> d.dismiss(); b.act() }; true } else false
            }
            card.addView(e, LinearLayout.LayoutParams(-1, -2).apply { topMargin = ui.u(14) })
            first = e
        }
        if (items.isNotEmpty()) {
            val list = LinearLayout(a).apply { orientation = LinearLayout.VERTICAL }
            for (it in items) {
                val line = LinearLayout(a).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                    isFocusable = true
                    isClickable = true
                    setPadding(ui.u(15), ui.u(9.5f), ui.u(15), ui.u(10))
                    background = ui.rounded(ui.white(10), ui.u(11))
                }
                line.addView(ui.label(14f, ui.fg).apply { text = it.label }, LinearLayout.LayoutParams(0, -2, 1f))
                val value = ui.label(13f, ui.white(190)).apply { text = it.value?.invoke().orEmpty() }
                line.addView(value, LinearLayout.LayoutParams(-2, -2).apply { leftMargin = ui.u(12) })
                line.setOnFocusChangeListener { v, has ->
                    v.background = if (has) ui.rounded(ui.white(40), ui.u(11), ui.u(2), ui.accent) else ui.rounded(ui.white(10), ui.u(11))
                    value.setTextColor(if (has) ui.accent else ui.white(190))
                }
                line.setOnClickListener { _ -> d.dismiss(); it.act() }
                list.addView(line, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = ui.u(5) })
                if (first == null) first = line
            }
            val scroll = object : ScrollView(a) {
                override fun onMeasure(w: Int, h: Int) = super.onMeasure(w, MeasureSpec.makeMeasureSpec(ui.u(300), MeasureSpec.AT_MOST))
            }.apply { isVerticalScrollBarEnabled = false; overScrollMode = View.OVER_SCROLL_NEVER }
            scroll.addView(list, ViewGroup.LayoutParams(-1, -2))
            card.addView(scroll, LinearLayout.LayoutParams(-1, -2).apply { topMargin = ui.u(14) })
        }
        if (buttons.isNotEmpty()) {
            val bar = LinearLayout(a).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.END }
            for (b in buttons) {
                val t = ui.label(13f, ui.fg, true).apply {
                    text = b.label
                    isFocusable = true
                    isClickable = true
                    setPadding(ui.u(17), ui.u(7), ui.u(17), ui.u(7.5f))
                }
                fun paint(has: Boolean) {
                    t.setTextColor(if (has) ui.ink else if (b.primary) ui.accent else ui.white(215))
                    t.background = when {
                        has -> ui.rounded(ui.accent, ui.u(16))
                        b.primary -> ui.rounded(Color.TRANSPARENT, ui.u(16), ui.u(1), ui.accent)
                        else -> ui.rounded(ui.white(18), ui.u(16))
                    }
                }
                paint(false)
                t.setOnFocusChangeListener { _, has -> paint(has) }
                t.setOnClickListener { d.dismiss(); b.act() }
                // Down from the text field goes to the button that saves
                if (b.primary) { t.id = View.generateViewId(); field?.nextFocusDownId = t.id }
                bar.addView(t, LinearLayout.LayoutParams(-2, -2).apply { leftMargin = ui.u(8) })
                if (first == null) first = t
            }
            card.addView(bar, LinearLayout.LayoutParams(-1, -2).apply { topMargin = ui.u(if (items.isEmpty()) 16 else 10) })
        }
        root.addView(card, FrameLayout.LayoutParams(ui.u(440), -2, Gravity.CENTER))
        d.setContentView(root, ViewGroup.LayoutParams(-1, -1))
        d.window?.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_HIDDEN or WindowManager.LayoutParams.SOFT_INPUT_ADJUST_PAN)
        d.setOnDismissListener { onDismiss() }
        d.show()
        first?.requestFocus()
    }
}
