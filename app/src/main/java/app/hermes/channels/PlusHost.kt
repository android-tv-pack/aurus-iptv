package app.hermes.channels

import android.os.Handler
import android.view.KeyEvent
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
    /** plays the channel that is on once more (the way it is played has changed) */
    fun replay()
    /** another engine shows its first picture */
    fun picture()
    /** the app's own playing stops ([on]) for something else to play, or may go on again */
    fun hold(on: Boolean)
    /** a plain player of its own, for another picture; whoever asks for it lets it go */
    fun player(): androidx.media3.exoplayer.ExoPlayer
    /** what a player plays the stream at [address] from */
    fun source(address: String): androidx.media3.exoplayer.source.MediaSource
}
