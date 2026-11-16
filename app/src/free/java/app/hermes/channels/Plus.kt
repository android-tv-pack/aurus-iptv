package app.hermes.channels

import app.hermes.channels.core.Guide
import app.hermes.channels.core.M3u

/**
 * Aurus Plus: extras that are not part of the open app. This is the open app's side of them: it has none, and
 * everything here answers "nothing". A build with the extras puts its own file in this one's place
 * (see `app/build.gradle.kts`). What the extras may ask of the app is [PlusHost].
 */
object Plus {
    const val PRESENT = false

    /** the colour of what is in focus; 0 = the app's own */
    fun accent(prefs: Prefs): Int = 0
    /** how much larger than usual the app's text is */
    fun textScale(prefs: Prefs): Float = 1f
    /** the list of channels with one line a channel */
    fun compact(prefs: Prefs): Boolean = false

    fun start(host: PlusHost) {}
    /** the app is in front */
    fun resume() {}
    /** the app is left; [redrawn] = only to be drawn anew */
    fun stop(redrawn: Boolean) {}

    /** the tab of the extras in the settings, if there is one */
    fun tab(): Page.Tab? = null

    /** a channel that is not to be shown, nor reached */
    fun hides(c: M3u.Channel): Boolean = false
    /** what stands beside a list's name in place of its number of channels; null = the number */
    fun groupNote(id: String): String? = null
    /** OK on a list of channels; true = seen to here, the list is not opened */
    fun groupClick(id: String): Boolean = false
    /** OK held on a list of channels; true = seen to here */
    fun groupHeld(id: String, name: String): Boolean = false
    /** the settings are asked for; true = [then] is called when they may open */
    fun settingsAsked(then: () -> Unit): Boolean = false

    /** OK on a programme of channel [key] that has not begun; true = something changed for it */
    fun programme(key: String, channel: String, p: Guide.Prog): Boolean = false
    /** what stands before the title of a programme; "" = nothing */
    fun programmeMark(key: String, p: Guide.Prog): String = ""
    /** a line under the name of the channel whose programmes are listed; "" = none */
    fun guideHint(): String = ""
}
