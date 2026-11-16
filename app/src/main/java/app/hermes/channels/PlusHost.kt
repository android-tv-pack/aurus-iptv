package app.hermes.channels

import android.os.Handler
import android.widget.FrameLayout
import app.hermes.channels.core.Library

/** What the extras ([Plus]) may ask of the app. */
interface PlusHost {
    val activity: MainActivity
    val ui: Ui
    val prefs: Prefs
    val root: FrameLayout
    val main: Handler
    fun library(): Library?
    /** the channel that plays (its place in the playlist), -1 = none */
    fun current(): Int
    /** leaves whatever is open and plays the channel [key] */
    fun watch(key: String)
    fun say(text: String)
    /** draws the whole app anew (a change of its look); the settings open again where they stood */
    fun restyle()
    /** the focus goes back to where it was before a sheet */
    fun refocus()
    /** which channels are shown has changed */
    fun relist()
}
